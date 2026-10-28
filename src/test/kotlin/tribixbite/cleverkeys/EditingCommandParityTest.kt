package tribixbite.cleverkeys

import android.content.ClipData
import android.content.Context
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.clipboard.PrivateCopyDispatch
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CustomShortSwipeExecutor
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import java.lang.reflect.Proxy

/**
 * Todo item 12 (2026-10-12): every Editing-kind command must have the SAME effect whether it
 * is pressed as a layout key (`KeyEventHandler.key_up` → `handleEditingKey`) or fired as a
 * custom short-swipe / popover / palette COMMAND mapping (`Keyboard2View.onCustomShortSwipe`).
 *
 * Before the unification the mapping surface ran its own copies (`CustomShortSwipeExecutor`'s
 * context-menu calls and `Keyboard2View.executeEditingCommand`), which diverged:
 * `pasteAsPlainText` sent a plain paste, `clear`/`forward_delete_word`/`delete_last_word`/
 * `selection_cancel` were silent no-ops, the inline clipboard editor was bypassed (the hidden
 * target app was edited), terminal typed-word tracking was never told about the edit, and
 * text assist/replace launched a chooser on one surface but a bare context-menu id on the other.
 *
 * Harness: a REAL [Keyboard2View] (Objenesis + spy, the [Keyboard2ViewCustomSwipeDispatchTest]
 * idiom) with the REAL [CustomShortSwipeExecutor], and a REAL [KeyEventHandler] (spied only so
 * `send_key_down_up` — whose android.jar `KeyEvent` constructor throws "Stub!" here — records
 * instead). Both entry points act on one recording InputConnection, one recording receiver and
 * one context (receiver mode flags default to false, editor calls to success); only EFFECTS are logged (editor writes, receiver side effects, user-visible
 * feedback, clipboard/private-copy actions), never reads. Each command runs under every
 * combination of {selection, no selection} × {inline clipboard editor open, closed} ×
 * {ordinary app, terminal, blind remote field (RustDesk)}.
 */
class EditingCommandParityTest {

    private val objenesis = ObjenesisStd()
    private val log = mutableListOf<String>()

    private var selection: CharSequence? = null
    private var editMode = false
    /** What the connection answers to performContextMenuAction (on a device: IPC success, always true). */
    private var contextMenuAccepted = true
    /**
     * What the editor hands back for the text before / after the cursor. Plain strings by
     * default — Compose and Chrome never return spans; a stock EditText returns its
     * SuggestionSpans when asked with GET_TEXT_WITH_STYLES ([withReplacementSpan]).
     */
    private var textBefore: CharSequence = ""
    private var textAfter: CharSequence = ""
    // An ordinary text field unless a test says otherwise: objenesis leaves inputType 0, which
    // is TYPE_NULL — a blind-field signal since 2026-10-13 (TerminalUtils.editorKind).
    private val editorInfo: EditorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT
    }

    private lateinit var context: Context
    private lateinit var inputConnection: InputConnection
    private lateinit var handler: KeyEventHandler
    private lateinit var view: Keyboard2View

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.getString(any()) } answers { "str#${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg()) } answers { "str#${firstArg<Int>()}" }
        every { context.startActivity(any()) } answers { log += "startActivity" }
        val clipItem = mockk<ClipData.Item>()
        every { clipItem.coerceToText(any()) } returns "clip"
        val clip = mockk<ClipData>()
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns clipItem
        val clipboard = mockk<android.content.ClipboardManager>(relaxed = true)
        every { clipboard.primaryClip } returns clip
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        mockkObject(PrivateCopyDispatch)
        every { PrivateCopyDispatch.execute(any(), any(), any(), any()) } answers {
            log += "privateCopy"
            arg<(String) -> Unit>(3)("private-copied")
        }
        mockkObject(tribixbite.cleverkeys.ClipboardHistoryService.Companion)
        every {
            tribixbite.cleverkeys.ClipboardHistoryService.clearSystemClipboard(any())
        } answers {
            log += "clearSystemClipboard"
            R.string.system_clipboard_cleared
        }

        inputConnection = recordingProxy(InputConnection::class.java, IC_EFFECTS, defaultBoolean = true) { name ->
            when (name) {
                "getSelectedText" -> selection
                "getTextBeforeCursor" -> textBefore
                "getTextAfterCursor" -> textAfter
                "performContextMenuAction" -> contextMenuAccepted
                else -> NO_ANSWER
            }
        }
        val receiver = recordingProxy(KeyEventHandler.IReceiver::class.java, null, defaultBoolean = false) { name ->
            when (name) {
                "getCurrentInputConnection" -> inputConnection
                "getCurrentEditorInfo" -> editorInfo
                "getContext" -> context
                "isClipboardEditMode" -> editMode
                else -> NO_ANSWER
            }
        }

        handler = spyk(objenesis.newInstance(KeyEventHandler::class.java).apply {
            setField("recv", receiver)
            setField("autocap", mockk<Autocapitalisation>(relaxed = true))
            setField("mods", Pointers.Modifiers.EMPTY)
            // getCursorPos (selection_cancel) lazily builds an ExtractedTextRequest, whose
            // android.jar constructor throws "Stub!": pre-seed the cache (KeyEventHandlerSliderTest).
            setField("moveCursorReq", mockk<android.view.inputmethod.ExtractedTextRequest>(relaxed = true))
        })
        every { handler.send_key_down_up(any(), any()) } answers {
            log += "keyDownUp(${firstArg<Int>()},${secondArg<Int>()})"
        }

        // Not inside `apply {}`: there `handler` would resolve to Config's own (null) field.
        val config = mockk<Config>(relaxed = true)
        config.setField("handler", handler)
        mockkObject(Config.Companion)
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config

        val service = mockk<CleverKeysService>(relaxed = true)
        every { service.currentInputConnection } answers { inputConnection }
        every { service.currentInputEditorInfo } answers { editorInfo }
        every { service.showSuggestionBarMessage(any(), any()) } answers { log += "feedback(${firstArg<String>()})" }

        view = spyk(objenesis.newInstance(Keyboard2View::class.java))
        every { view.context } returns context
        every { view.performHapticFeedback(any()) } returns true
        view.setField("_keyboard2", service)
        view.setField("_config", config)
        view.setField("_customSwipeExecutor", CustomShortSwipeExecutor(context))
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    /** The catalogue names cover every [KeyValue.Editing] value, so a new one cannot be skipped. */
    @Test
    fun everyEditingValueHasACommandNameUnderTest() {
        val covered = EDITING_NAMES.map { KeyValue.getKeyByName(it).getEditing() }.toSet()
        assertEquals(KeyValue.Editing.values().toSet(), covered)
    }

    @Test
    fun everyEditingCommandHasTheSameEffectFromALayoutKeyAndAMapping() {
        val mismatches = mutableListOf<String>()
        for (name in EDITING_NAMES) compareSurfaces(name, name, mismatches)
        assertTrue("Divergent editing commands:\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    /** Pre-2026 mappings saved under the legacy AvailableCommand spelling run the same command. */
    @Test
    fun legacyCommandNamesHaveTheSameEffectAsTheirLayoutKey() {
        val mismatches = mutableListOf<String>()
        for ((legacy, name) in LEGACY_NAMES) compareSurfaces(legacy, name, mismatches)
        assertTrue("Divergent legacy commands:\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    // Fixed divergences, pinned individually so a regression names the behaviour it breaks.

    @Test
    fun pasteAsPlainTextMappingPastesAsPlainText() {
        assertEquals(
            listOf("performContextMenuAction(${android.R.id.pasteAsPlainText})"),
            mappingEffects("pasteAsPlainText").filter { it.startsWith("performContextMenuAction") }
        )
    }

    @Test
    fun clearMappingErasesTheField() {
        val effects = mappingEffects("clear")
        assertTrue(effects.toString(), "performContextMenuAction(${android.R.id.selectAll})" in effects)
        assertTrue(effects.toString(), "commitText(,1)" in effects)
    }

    @Test
    fun deletionAndSelectionMappingsAreNotSilentNoOps() {
        assertTrue("keyDownUp(112,12288)" in mappingEffects("forward_delete_word"))
        assertTrue("handle_delete_last_word()" in mappingEffects("delete_last_word"))
    }

    @Test
    fun pasteMappingTargetsTheOpenInlineClipboardEditor() {
        editMode = true
        assertEquals(listOf("pasteToClipboardEdit()"), mappingEffects("paste"))
    }

    @Test
    fun mappingEditTellsTheReceiverTheTypedWordEnded() {
        assertTrue("handle_non_text_input()" in mappingEffects("undo"))
    }

    /**
     * 2026-10-15: undo/redo used `performContextMenuAction(undo/redo)`, which only a stock
     * EditText honours — Chrome/WebView (ImeAdapterImpl) and Compose (StatelessInputConnection)
     * return false, so the commands were silent no-ops there. Ctrl+Z / Ctrl+Shift+Z key events
     * are honoured by all three (docs/research/2026-10-15-ime-editor-actions.md §1.3, §1.4, §2.1).
     * Never BOTH: an EditText would then undo twice.
     */
    @Test
    fun undoAndRedoSendCtrlZShortcutsInsteadOfTheContextMenuIds() {
        editorInfo.packageName = "com.example.notes"
        val ctrl = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        val ctrlShift = ctrl or android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        val z = android.view.KeyEvent.KEYCODE_Z
        for (sel in listOf<CharSequence?>("sel", null)) {
            selection = sel
            for ((name, meta) in listOf("undo" to ctrl, "redo" to ctrlShift)) {
                val expected = listOf("handle_non_text_input()", "keyDownUp($z,$meta)")
                assertEquals("$name key", expected, layoutEffects(name))
                assertEquals("$name mapping", expected, mappingEffects(name))
            }
        }
    }

    /**
     * In a terminal Ctrl+Z is SIGTSTP (suspends the foreground job) and a blind remote field
     * forwards it to a remote shell the same way, so undo/redo there must not become Ctrl+Z:
     * they report "unavailable" instead (the context-menu id was already a no-op there).
     */
    @Test
    fun undoAndRedoInATerminalOrBlindFieldReportUnavailableInsteadOfSendingCtrlZ() {
        for (pkg in listOf("com.termux", "com.carriez.flutter_hbb")) {
            editorInfo.packageName = pkg
            for (name in listOf("undo", "redo")) {
                for (effects in listOf(layoutEffects(name), mappingEffects(name))) {
                    assertTrue("$name $pkg $effects", effects.none { it.startsWith("keyDownUp") })
                    assertTrue("$name $pkg $effects", effects.none { it.startsWith("performContextMenuAction") })
                    assertTrue("$name $pkg $effects", "feedback(str#${R.string.dynamic_action_unavailable})" in effects)
                }
            }
        }
    }

    @Test
    fun textAssistLayoutKeyOffersTheSameNoSelectionFeedbackAsTheMapping() {
        selection = null
        val expected = "feedback(str#${R.string.text_action_no_selection})"
        assertEquals(listOf("handle_non_text_input()", expected), layoutEffects("textAssist"))
    }

    /**
     * Maintainer decision 2026-10-13: textAssist/replaceText keep the app chooser, and the
     * editor's own context-menu replace action (the layout key's behaviour before 7d1a3271) is
     * its own command — no chooser, no selection precondition (the editor decides). The bare id
     * goes out whenever a usable SuggestionSpan touches the cursor (see
     * [replacePopupWithoutASuggestionSpanAtTheCursorSaysUnavailable] for the other case).
     */
    @Test
    fun editorMenuReplaceSendsTheBareContextMenuIdWithoutAChooser() {
        withReplacementSpan {
            for (sel in listOf<CharSequence?>("sel", null)) {
                selection = sel
                val expected = listOf("handle_non_text_input()", "performContextMenuAction(${android.R.id.replaceText})")
                assertEquals("key sel=${sel != null}", expected, layoutEffects("replaceTextMenu"))
                assertEquals("mapping sel=${sel != null}", expected, mappingEffects("replaceTextMenu"))
            }
        }
        // The chooser variants never send the bare id when nothing is selected: they report
        // "no text selected" instead (the chooser itself needs a real Intent, absent here).
        selection = null
        for (name in listOf("textAssist", "replaceText")) {
            assertTrue(name, layoutEffects(name).none { it.startsWith("performContextMenuAction") })
        }
    }

    /**
     * 2026-10-21: an IME cannot tell whether the editor took a context-menu action. The
     * IME-side connection returns true whenever the IPC call succeeded (AOSP
     * IRemoteInputConnectionInvoker), so a "refused → unavailable" message added on its result
     * passed this suite yet never fired on the Saga's Compose field. Pin the honest behaviour:
     * the same effects whatever the connection answers, and no message.
     */
    @Test
    fun editorMenuActionsDoNotInferAnythingFromTheConnectionsAnswer() {
        withReplacementSpan {
            for ((name, id) in listOf("replaceTextMenu" to android.R.id.replaceText, "autofill" to android.R.id.autofill)) {
                val expected = listOf("handle_non_text_input()", "performContextMenuAction($id)")
                for (answer in listOf(true, false)) {
                    contextMenuAccepted = answer
                    assertEquals("$name key answer=$answer", expected, layoutEffects(name))
                    assertEquals("$name mapping answer=$answer", expected, mappingEffects(name))
                }
            }
        }
        contextMenuAccepted = true
    }

    /**
     * Todo item 7 (Saga 2026-10-21): in a Compose field (CleverKeys' settings search) the Replace
     * popup command was a SILENT no-op. The editor's answer is invisible to the IME, but the
     * popup's precondition is not: stock EditText shows it only for a SuggestionSpan touching
     * the selection start (Editor.SuggestionsPopupWindow.updateSuggestions returns false
     * otherwise), and Compose and Chrome implement no replaceText and return plain text. So
     * when the styled text around the cursor carries no usable span the popup cannot appear in
     * ANY editor: say so in the bar and send nothing (EditText's replace() would still move a
     * selection to its midpoint).
     */
    @Test
    fun replacePopupWithoutASuggestionSpanAtTheCursorSaysUnavailable() {
        val unavailable = listOf("handle_non_text_input()", "feedback(str#${R.string.dynamic_action_unavailable})")
        val sent = listOf("handle_non_text_input()", "performContextMenuAction(${android.R.id.replaceText})")
        // Plain text (Compose, Chrome): unavailable, with or without a selection.
        for (sel in listOf<CharSequence?>("sel", null)) {
            selection = sel
            textBefore = "hello wor"; textAfter = "ld"
            assertEquals("key plain sel=${sel != null}", unavailable, layoutEffects("replaceTextMenu"))
            assertEquals("mapping plain sel=${sel != null}", unavailable, mappingEffects("replaceTextMenu"))
        }
        selection = null
        // A span that ends before the cursor (an earlier word) does not count.
        textBefore = spannedWith(length = 10, start = 0, end = 5, span = suggestionSpan(arrayOf("help")))
        textAfter = ""
        assertEquals("span not touching", unavailable, layoutEffects("replaceTextMenu"))
        // A span with no suggestions and no misspelled/grammar flag shows nothing either.
        textBefore = spannedWith(length = 5, start = 0, end = 5, span = suggestionSpan(emptyArray()))
        assertEquals("empty span", unavailable, layoutEffects("replaceTextMenu"))
        // A misspelled span with no suggestions still opens the popup ("Add to dictionary").
        textBefore = spannedWith(length = 5, start = 0, end = 5, span = suggestionSpan(emptyArray(), SuggestionSpan.FLAG_MISSPELLED))
        assertEquals("misspelled span", sent, layoutEffects("replaceTextMenu"))
        // The word AFTER the cursor (span starting at the cursor) counts.
        textBefore = "go "
        textAfter = spannedWith(length = 5, start = 0, end = 5, span = suggestionSpan(arrayOf("help")))
        assertEquals("span after cursor", sent, layoutEffects("replaceTextMenu"))
        assertEquals("span after cursor mapping", sent, mappingEffects("replaceTextMenu"))
        textBefore = ""; textAfter = ""
    }

    /** Runs [block] with a swiped word (SuggestionSpan with alternatives) just before the cursor. */
    private fun withReplacementSpan(block: () -> Unit) {
        textBefore = spannedWith(length = 4, start = 0, end = 4, span = suggestionSpan(arrayOf("held", "hello")))
        try { block() } finally { textBefore = ""; textAfter = "" }
    }

    private fun suggestionSpan(suggestions: Array<String>, flags: Int = 0): SuggestionSpan = mockk {
        every { this@mockk.suggestions } returns suggestions
        every { this@mockk.flags } returns flags
    }

    /** A [Spanned] of [length] chars carrying [span] over [start]..[end] (android.jar's are stubs here). */
    private fun spannedWith(length: Int, start: Int, end: Int, span: SuggestionSpan): Spanned = mockk {
        every { this@mockk.length } returns length
        every { getSpans(any(), any(), SuggestionSpan::class.java) } returns arrayOf(span)
        every { getSpanStart(span) } returns start
        every { getSpanEnd(span) } returns end
        every { this@mockk.toString() } returns "x".repeat(length)
    }

    /**
     * Maintainer decision 2026-10-17: `textAssistMenu` is removed — android.R.id.textAssist is
     * handled by no editor, so it was a silent no-op. A saved mapping or layout value still
     * resolves (Editing.ASSIST_MENU keeps its ordinal) and now refuses with a bar message,
     * touching neither the app nor the typed-word state, on every surface and in every mode.
     */
    @Test
    fun removedTextAssistMenuRefusesVisiblyAndSendsNothing() {
        val expected = listOf("feedback(str#${R.string.text_assist_menu_removed})")
        for (sel in listOf<CharSequence?>("sel", null)) for (edit in listOf(false, true)) {
            for (pkg in listOf("com.example.notes", "com.termux")) {
                selection = sel; editMode = edit; editorInfo.packageName = pkg
                val tag = "sel=${sel != null} edit=$edit pkg=$pkg"
                assertEquals("key $tag", expected, layoutEffects("textAssistMenu"))
                assertEquals("mapping $tag", expected, mappingEffects("textAssistMenu"))
            }
        }
    }

    // ------------------------------------------------------------------ harness

    private fun compareSurfaces(mappingName: String, keyName: String, mismatches: MutableList<String>) {
        for (sel in listOf<CharSequence?>("sel", null)) for (edit in listOf(false, true)) {
            for (pkg in listOf("com.example.notes", "com.termux", "com.carriez.flutter_hbb")) {
                selection = sel; editMode = edit; editorInfo.packageName = pkg
                val key = layoutEffects(keyName)
                val mapping = mappingEffects(mappingName)
                if (key != mapping) {
                    mismatches += "$mappingName [sel=${sel != null} edit=$edit pkg=$pkg]\n" +
                        "    key:     $key\n    mapping: $mapping"
                }
            }
        }
    }

    private fun layoutEffects(name: String): List<String> {
        log.clear()
        handler.key_up(KeyValue.getKeyByName(name), Pointers.Modifiers.EMPTY, false)
        return log.toList()
    }

    private fun mappingEffects(name: String): List<String> {
        log.clear()
        view.onCustomShortSwipe(
            ShortSwipeMapping("q", SwipeDirection.NE, "x", ActionType.COMMAND, name)
        )
        return log.toList()
    }

    /**
     * An interface proxy that answers from [answer] (falling back to a type default) and logs
     * calls: those named in [effects], or — when null — every call that is not a read.
     */
    private fun <T> recordingProxy(
        type: Class<T>,
        effects: Set<String>?,
        defaultBoolean: Boolean,
        answer: (String) -> Any?,
    ): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            val name = method.name
            when (name) {
                "equals" -> return@newProxyInstance proxy === args?.get(0)
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "toString" -> return@newProxyInstance type.simpleName
            }
            val isEffect = effects?.contains(name) ?: READ_PREFIXES.none { name.startsWith(it) }
            if (isEffect) {
                // Both feedback channels end in the suggestion bar (KeyboardReceiver forwards
                // showPrivateCopyFeedback to showSuggestionBarMessage): log them alike.
                log += if (name == "showPrivateCopyFeedback") "feedback(${args!![0]})"
                else "$name(${args.orEmpty().joinToString(",")})"
            }
            val answered = answer(name)
            if (answered !== NO_ANSWER) return@newProxyInstance answered
            when (method.returnType) {
                java.lang.Boolean.TYPE -> defaultBoolean
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as T
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError("field $name not found on ${javaClass.name}")
    }

    private companion object {
        val NO_ANSWER = Any()

        /** Receiver calls that only read state; everything else is a side effect. */
        val READ_PREFIXES = listOf("get", "is", "was", "has", "can")

        val IC_EFFECTS = setOf(
            "performContextMenuAction", "commitText", "sendKeyEvent", "setSelection",
            "deleteSurroundingText", "deleteSurroundingTextInCodePoints", "beginBatchEdit",
            "endBatchEdit", "finishComposingText", "setComposingText", "setComposingRegion",
            "performEditorAction", "commitContent",
        )

        /** One catalogue/layout name per [KeyValue.Editing] value. */
        val EDITING_NAMES = listOf(
            "copy", "copy_private", "clear_clipboard", "paste", "cut", "selectAll", "shareText",
            "pasteAsPlainText", "undo", "redo", "delete_word", "forward_delete_word", "clear",
            "delete_last_word", "selection_cancel", "replaceText", "textAssist", "autofill",
            "doc_home", "doc_end", "append_possessive", "append_apostrophe",
            "textAssistMenu", "replaceTextMenu",
            "word_left", "word_right", "select_word_left", "select_word_right", "select_word", "select_line",
            "case_cycle",
        )

        /** Legacy SCREAMING_SNAKE AvailableCommand names that denote an editing command. */
        val LEGACY_NAMES = listOf(
            "COPY" to "copy", "PRIVATE_COPY" to "copy_private", "PASTE" to "paste",
            "CUT" to "cut", "SELECT_ALL" to "selectAll", "UNDO" to "undo", "REDO" to "redo",
            "CURSOR_DOC_START" to "doc_home", "CURSOR_DOC_END" to "doc_end",
            "DELETE_WORD" to "delete_word", "TEXT_ASSIST" to "textAssist",
            "REPLACE_TEXT" to "replaceText", "WORD_LEFT" to "word_left", "WORD_RIGHT" to "word_right",
        )
    }
}
