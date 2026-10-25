package tribixbite.cleverkeys

import android.content.ClipData
import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Blind text fields (maintainer requirement, 2026-10-13): "we need to support circumstances
 * where the text field is blind — like GUI-based remote desktop e.g. RustDesk (including using
 * RustDesk to control a remote terminal)".
 *
 * # The RustDesk model
 *
 * [RustDeskField] reproduces what RustDesk's Android remote-desktop view does with what an IME
 * writes (rustdesk/rustdesk master 0b5a3d74, `flutter/lib/mobile/pages/remote_page.dart`):
 *  - the hidden multiline field starts as `'1' * 1024` (L28) and is never reset while typing,
 *    so `getTextBeforeCursor` reads placeholders followed by everything typed this session;
 *  - every change is forwarded as a LENGTH-ONLY diff (L328-L372): longer → the appended tail
 *    is typed on the remote; shorter → ONE `VK_BACK` however many chars went; same length →
 *    nothing;
 *  - a `KEYCODE_DEL` key event reaches the remote as Backspace through the field's
 *    `RawKeyFocusScope` and leaves the field unchanged.
 * Its remote-TERMINAL view is stricter still (empty field reset after every commit, so
 * `deleteSurroundingText` does nothing); the generic [blindEditor] double — TYPE_NULL, reads
 * always "" — stands for it and for VNC clients (AVNC, bVNC use TYPE_NULL).
 *
 * The tests assert what the REMOTE ends up with, so they pin user-visible outcomes rather than
 * call shapes.
 *
 * RED before the policy (2026-10-13, RustDesk unknown to the detector), 9 of 12: a TYPE_NULL
 * field ran the space autocorrect (deleteSurroundingText(4)); "i" + space left "iI "; a tap after a cursor sync deleted one
 * char of "hel" ("hehello"); backspace-undo of a swipe removed one char of "hello "; paste and
 * paste-as-plain-text sent context-menu ids; clear erased via select-all; the possessive suffix
 * ran; delete-last-word read the placeholder text.
 */
class BlindFieldPolicyTest {

    private val objenesis = ObjenesisStd()

    private lateinit var config: Config
    private lateinit var tracker: PredictionContextTracker
    private lateinit var predictor: WordPredictor
    private lateinit var keyevents: KeyEventHandler
    private lateinit var handler: SuggestionHandler
    private lateinit var field: RustDeskField
    private lateinit var ic: InputConnection

    /** Key events the IME sent: "keyCode/meta". */
    private val keysSent = ArrayList<String>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.word_prediction_enabled = true
        config.autocorrect_enabled = true
        config.swipe_final_autocorrect_enabled = true
        config.auto_space_after_suggestion = true
        config.auto_space_before_suggestion = false
        config.autocapitalize_i_words = true
        config.next_word_prediction_enabled = false
        config.on_device_learning_enabled = false
        config.backspace_undo_swipe = true
        config.custom_terminal_packages = emptySet()
        config.primary_language = "en"
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config

        tracker = PredictionContextTracker()
        predictor = mockk(relaxed = true)
        every { predictor.predictWordsWithContext(any(), any()) } answers {
            WordPredictor.PredictionResult(listOf("${firstArg<String>()}x"), listOf(100))
        }
        every { predictor.autoCorrect(any()) } answers {
            when (firstArg<String>()) { "teh" -> "the"; else -> firstArg() }
        }
        every { predictor.isInDictionary(any()) } returns true
        every { predictor.isInDictionary(any(), any()) } returns true
        val coordinator = mockk<PredictionCoordinator>(relaxed = true)
        every { coordinator.getWordPredictor() } returns predictor

        val bar = mockk<SuggestionBar>(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null

        field = RustDeskField()
        ic = field.connection()

        val contractions = mockk<ContractionManager>(relaxed = true)
        every { contractions.isKnownContraction(any()) } returns false
        every { contractions.isContractionKey(any()) } returns false
        every { contractions.getNonPairedMapping(any()) } returns null
        every { contractions.generatePossessive(any()) } returns null

        val tasks = mockk<PredictionTaskRunner>(relaxed = true)
        val main = mockk<Handler>(relaxed = true)
        every { main.post(any()) } answers { firstArg<Runnable>().run(); true }

        keysSent.clear()
        keyevents = mockk(relaxed = true)
        every { keyevents.send_key_down_up(any(), any()) } answers { sendKey(firstArg(), secondArg()) }

        handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contextTracker", tracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("contractionManager", contractions)
        handler.setField("keyeventhandler", keyevents)
        handler.setField("predictionTasks", tasks)
        handler.setField("mainHandler", main)
        handler.setField("fieldAllowsPersonalizedLearning", true)
        handler.setField("autocorrectMemo", tribixbite.cleverkeys.autocorrect.AutocorrectMemo())
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    private fun editor(pkg: String, type: Int): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = pkg
            inputType = type
        }

    /** RustDesk's hidden field: TextInputType.multiline, autocorrect false (TextInputPlugin). */
    private fun rustDesk() = editor(
        "com.carriez.flutter_hbb",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
    )

    /** Any TYPE_NULL field (AVNC, bVNC, a remote terminal): nothing reads back. */
    private fun blindEditor() = editor("com.example.vnc", InputType.TYPE_NULL)

    private fun sendKey(code: Int, meta: Int) {
        keysSent += "$code/$meta"
        if (code == KeyEvent.KEYCODE_DEL && meta == 0) field.remoteBackspace()
    }

    /** Type like KeyEventHandler.sendText: commit the char, then report it to the handler. */
    private fun type(text: String, editor: EditorInfo) {
        for (c in text) {
            ic.commitText(c.toString(), 1)
            handler.handleRegularTyping(c.toString(), ic, editor)
        }
    }

    private fun tap(word: String, editor: EditorInfo) =
        handler.onSuggestionSelected(word, ic, editor, mockk<Resources>(relaxed = true), isManualSelection = true)

    // ------------------------------------------------------------------ rewrites

    @Test
    fun spaceAutocorrectNeverRewritesTheRemote() {
        // After a space, so the token is "teh" and not the placeholder run "111…teh" (which the
        // non-prose guard would have refused to correct on its own).
        type("ok teh ", rustDesk())
        assertWithMessage("remote after 'ok teh' + space").that(field.remote()).isEqualTo("ok teh ")
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun iWordCapitalizationNeverRewritesTheRemote() {
        type("i ", rustDesk())
        assertWithMessage("remote after 'i' + space").that(field.remote()).isEqualTo("i ")
    }

    @Test
    fun aTypeNullFieldIsNeverAutocorrected() {
        val blind = blindEditor()
        field.blind = true
        type("teh ", blind)
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
        verify(exactly = 0) { ic.commitText(match { it.toString().startsWith("the") }, any()) }
    }

    // ------------------------------------------------------------------ typed-word tracking

    @Test
    fun cursorSyncKeepsTheTypedWordAndATapReplacesItOnTheRemote() {
        val rd = rustDesk()
        type("hel", rd)
        // Flutter reports the selection change; the debounced sync reads the hidden field.
        tracker.synchronizeWithCursor(ic, "en", rd)
        assertWithMessage("tracked word after a cursor sync").that(tracker.getCurrentWord()).isEqualTo("hel")
        tap("hello", rd)
        assertWithMessage("remote after tapping 'hello'").that(field.remote().trimEnd()).isEqualTo("hello")
    }

    @Test
    fun aTapWithNoTrackedWordSendsNoBackspacesToTheRemote() {
        val rd = rustDesk()
        type("ls", rd)
        handler.handleNonTextInput(rd) // Enter: the remote took the line
        tap("cd", rd)
        assertWithMessage("no Backspace may reach the remote").that(keysSent).isEmpty()
        assertWithMessage("remote").that(field.remote().trimEnd()).isEqualTo("lscd")
    }

    @Test
    fun deleteLastWordOnARemoteFieldIsCtrlBackspaceNeverCtrlW() {
        val rd = rustDesk()
        type("foo", rd)
        handler.handleDeleteLastWord(ic, rd)
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        assertWithMessage("keys sent").that(keysSent).containsExactly("${KeyEvent.KEYCODE_DEL}/$ctrl")
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
        type("x", rd)
        assertWithMessage("the deleted word is no longer tracked").that(tracker.getCurrentWord()).isEqualTo("x")
    }

    @Test
    fun deleteLastWordInATerminalIsStillCtrlW() {
        val termux = editor("com.termux", InputType.TYPE_NULL)
        handler.handleDeleteLastWord(ic, termux)
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        assertWithMessage("keys sent").that(keysSent).containsExactly("${KeyEvent.KEYCODE_W}/$ctrl")
    }

    // ------------------------------------------------------------------ swipe corrections

    /**
     * A swipe auto-insert through the SHIPPED entry point ([SuggestionHandler.handleSwipePredictionResults]),
     * so the tracking it leaves for a later bar tap is the production one, not a test's guess.
     */
    private fun swipe(slate: List<String>, editor: EditorInfo) {
        val bar = handler.getField("suggestionBar") as SuggestionBar
        every { bar.getTopSuggestion() } returns slate.first()
        val coordinator = mockk<InputCoordinator>(relaxed = true)
        every { coordinator.getCurrentSwipeData() } returns null
        tracker.setWasLastInputSwipe(true)
        handler.handleSwipePredictionResults(
            slate, slate.map { 100 }, ic, editor, mockk(relaxed = true),
            shiftActive = false, shiftLocked = false, inputCoordinator = coordinator,
        )
    }

    /**
     * Maintainer report 2026-10-25 (RustDesk): tapping a swipe correction from the bar removed
     * the swiped word AND the space before it. Root cause: the REPLACE branch assumed the swipe
     * typed `word + " "` and, in a terminal-style editor, added one more Backspace whenever the
     * character before the cursor was a space — which is the swipe's own TRAILING space, so the
     * extra key event ate the space before the word. RustDesk's hidden field does not change on
     * a key-event Backspace, so the leading-space readback afterwards saw that stale space and
     * the replacement went in without one: `ok hello ` → tap `help` → `okhelp `.
     *
     * Every auto-space combination, three contexts, both blind field kinds: the replacement must
     * take exactly the swipe's place — the same leading space the swipe had, the trailing space
     * the after-setting asks for — and nothing before the swiped word may go.
     */
    @Test
    fun aSwipeCorrectionTapReplacesExactlyWhatTheSwipeTypedOnTheRemote() {
        val failures = ArrayList<String>()
        for (blind in listOf(false, true)) for (after in listOf(true, false)) for (before in listOf(true, false)) {
            for (context in listOf("ok ", "ok", "")) {
                field = RustDeskField(); ic = field.connection(); field.blind = blind
                tracker.clearAll()
                keysSent.clear()
                config.auto_space_after_suggestion = after
                config.auto_space_before_suggestion = before
                val rd = if (blind) blindEditor() else rustDesk()
                type(context, rd)
                swipe(listOf("hello", "help"), rd)
                val swiped = field.remote()
                tap("help", rd)
                val got = field.remote()
                val expected = swiped.removeSuffix(if (after) "hello " else "hello") + if (after) "help " else "help"
                if (got != expected) {
                    failures += "blind=$blind after=$after before=$before context='$context': " +
                        "swipe gave '$swiped', tap gave '$got', expected '$expected'"
                }
            }
        }
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }

    /** The same rule in an ordinary editor, whose text reads back and deletes in place. */
    @Test
    fun aSwipeCorrectionTapInAnOrdinaryEditorKeepsThePrecedingSpace() {
        val failures = ArrayList<String>()
        for (after in listOf(true, false)) for (before in listOf(true, false)) for (context in listOf("ok ", "ok")) {
            field = RustDeskField(); ic = field.connection()
            tracker.clearAll()
            config.auto_space_after_suggestion = after
            config.auto_space_before_suggestion = before
            val notes = editor("com.example.notes", InputType.TYPE_CLASS_TEXT)
            type(context, notes)
            swipe(listOf("hello", "help"), notes)
            val swiped = field.typed()
            tap("help", notes)
            val got = field.typed()
            val expected = swiped.removeSuffix(if (after) "hello " else "hello") + if (after) "help " else "help"
            if (got != expected) {
                failures += "after=$after before=$before context='$context': swipe gave '$swiped', " +
                    "tap gave '$got', expected '$expected'"
            }
        }
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }

    // ------------------------------------------------------------------ KeyEventHandler

    private fun keyHandler(editor: EditorInfo): Pair<KeyEventHandler, KeyEventHandler.IReceiver> {
        val clipItem = mockk<ClipData.Item>()
        every { clipItem.coerceToText(any()) } returns "clip text"
        val clip = mockk<ClipData>()
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns clipItem
        val clipboard = mockk<android.content.ClipboardManager>(relaxed = true)
        every { clipboard.primaryClip } returns clip
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
        every { context.getString(any()) } returns "unavailable"

        val recv = mockk<KeyEventHandler.IReceiver>(relaxed = true)
        every { recv.getHandler() } returns mockk(relaxed = true)
        every { recv.isClipboardTagMode() } returns false
        every { recv.isClipboardEditMode() } returns false
        every { recv.isClipboardSearchMode() } returns false
        every { recv.isEmojiPaneOpen() } returns false
        every { recv.isGifPaneOpen() } returns false
        every { recv.getLastAutocorrectOriginalWord() } returns null
        every { recv.getLastAutoInsertedWord() } returns null
        every { recv.getCurrentInputConnection() } returns ic
        every { recv.getCurrentEditorInfo() } returns editor
        every { recv.getContext() } returns context
        val keys = spyk(KeyEventHandler(recv))
        // android.jar's KeyEvent constructor throws "Stub!" here: record instead.
        every { keys.send_key_down_up(any(), any()) } answers { sendKey(firstArg(), secondArg()) }
        return keys to recv
    }

    private fun press(keys: KeyEventHandler, name: String) =
        keys.key_up(KeyValue.getKeyByName(name), Pointers.Modifiers.EMPTY, false)

    /**
     * A TYPE_NULL field (VNC clients) has no paste of its own: the platform's dummy connection
     * ignores a context-menu paste, so the IME commits the clip text itself (2026-10-13).
     */
    @Test
    fun pasteKeysCommitTheClipTextToABlindField() {
        for (name in listOf("paste", "pasteAsPlainText")) {
            field = RustDeskField(); ic = field.connection()
            val (keys, _) = keyHandler(blindEditor())
            press(keys, name)
            assertWithMessage("remote after '$name'").that(field.remote()).isEqualTo("clip text")
            verify(exactly = 0) { ic.performContextMenuAction(any()) }
        }
    }

    /**
     * RustDesk's fields are Flutter text inputs, and Flutter's InputConnectionAdaptor performs a
     * context-menu paste itself (reads the clip, edits the field, reports the change). Before
     * 2026-10-13 the paste key sent exactly that and worked in RustDesk; the blind-field change
     * replaced it with a committed clip, and the paste subkey stopped working in RustDesk remote
     * sessions (maintainer, 2026-10-25). RustDesk gets its field's own paste again.
     */
    @Test
    fun pasteKeysInRustDeskUseTheFieldsOwnPaste() {
        for ((name, id) in listOf("paste" to android.R.id.paste, "pasteAsPlainText" to android.R.id.pasteAsPlainText)) {
            field = RustDeskField(); ic = field.connection()
            val (keys, _) = keyHandler(rustDesk())
            press(keys, name)
            verify(exactly = 1) { ic.performContextMenuAction(id) }
            verify(exactly = 0) { ic.commitText(any(), any()) }
            assertWithMessage("remote after '$name'").that(field.remote()).isEqualTo("clip text")
        }
    }

    @Test
    fun pasteKeysInAnOrdinaryEditorStillUseTheEditorMenu() {
        val (keys, _) = keyHandler(editor("com.example.notes", InputType.TYPE_CLASS_TEXT))
        press(keys, "paste")
        press(keys, "pasteAsPlainText")
        verify { ic.performContextMenuAction(android.R.id.paste) }
        verify { ic.performContextMenuAction(android.R.id.pasteAsPlainText) }
        verify(exactly = 0) { ic.commitText(any(), any()) }
    }

    @Test
    fun clearLeavesTheUnseenTextOfABlindFieldAlone() {
        type("abc", rustDesk())
        val (keys, _) = keyHandler(rustDesk())
        press(keys, "clear")
        assertWithMessage("remote after 'clear'").that(field.remote()).isEqualTo("abc")
        verify(exactly = 0) { ic.performContextMenuAction(any()) }
    }

    @Test
    fun backspaceUndoOfASwipeDeletesTheWholeWordOnTheRemote() {
        ic.commitText("hello ", 1)
        val (keys, recv) = keyHandler(rustDesk())
        every { recv.getLastAutoInsertedWord() } returns "hello"
        press(keys, "backspace")
        assertWithMessage("remote after backspace-undo of 'hello '").that(field.remote()).isEqualTo("")
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun aSuffixCommandIsRefusedInABlindField() {
        val (keys, _) = keyHandler(rustDesk())
        val hooks = mockk<KeyEventHandler.LearningHooks>(relaxed = true)
        keys.learningHooks = hooks
        assertWithMessage("execute_suffix").that(keys.execute_suffix("'s")).isFalse()
        verify(exactly = 0) { hooks.appendSuffix(any()) }
    }

    // ------------------------------------------------------------------ the RustDesk double

    /**
     * RustDesk's remote-desktop hidden field and the remote text it produces. With [blind] set
     * the field reads back "" (TYPE_NULL / remote-terminal behaviour) but still forwards.
     */
    private class RustDeskField {
        private val text = StringBuilder("1".repeat(1024))
        private val remote = StringBuilder()
        var blind = false

        fun remote(): String = remote.toString()

        /** The field's own text after the placeholder run (an ordinary editor's content). */
        fun typed(): String = text.toString().removePrefix("1".repeat(1024))

        fun remoteBackspace() {
            if (remote.isNotEmpty()) remote.setLength(remote.length - 1)
        }

        /** handleSoftKeyboardInput: the length-only diff (remote_page.dart L328-L372). */
        private fun changed(oldLength: Int) {
            when {
                text.length < oldLength -> remoteBackspace()
                text.length > oldLength -> remote.append(text.substring(oldLength))
            }
        }

        fun connection(): InputConnection {
            val conn = mockk<InputConnection>(relaxed = true)
            every { conn.getTextBeforeCursor(any(), any()) } answers {
                if (blind) "" else text.takeLast(firstArg<Int>()).toString()
            }
            every { conn.getTextAfterCursor(any(), any()) } returns ""
            every { conn.getSelectedText(any()) } returns null
            every { conn.getCursorCapsMode(any()) } returns 0
            every { conn.commitText(any(), any()) } answers {
                val old = text.length
                text.append(firstArg<CharSequence>())
                changed(old)
                true
            }
            every { conn.deleteSurroundingText(any(), any()) } answers {
                val old = text.length
                text.setLength(maxOf(0, old - firstArg<Int>()))
                changed(old)
                true
            }
            // Flutter's InputConnectionAdaptor handles paste / paste-as-plain-text in the adaptor:
            // the clip goes into the field and the change reaches handleSoftKeyboardInput.
            every { conn.performContextMenuAction(any()) } answers {
                val id = firstArg<Int>()
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                    val old = text.length
                    text.append("clip text")
                    changed(old)
                }
                true
            }
            return conn
        }
    }

    private fun Any.getField(name: String): Any? {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredFields.firstOrNull { it.name == name }?.let { it.isAccessible = true; return it.get(this) }
            cls = cls.superclass
        }
        throw AssertionError("field '$name' not found on ${javaClass.simpleName}")
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val f = cls.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                f.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError("field '$name' not found on ${javaClass.simpleName}")
    }
}
