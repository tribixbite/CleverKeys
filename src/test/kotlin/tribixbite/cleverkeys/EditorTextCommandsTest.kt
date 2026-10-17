package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import android.view.KeyEvent
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
import java.lang.reflect.Proxy

/**
 * The editor commands added 2026-10-17 (docs/research/2026-10-15-ime-editor-actions.md §4.2),
 * driven through the ONE implementation (`KeyEventHandler.handleEditingKey`) against a fake
 * editor buffer: word moves and word selection, select word / line, and case cycling.
 *
 * What is pinned is what the EDITOR ends up with (text and selection, in UTF-16 offsets) and
 * which refusals reach the user — ordinary editors are driven by readback-computed
 * `setSelection`, editors without readback by Ctrl(+Shift)+arrow key events, terminal-style
 * (terminal / blind) fields only by key events that cannot rewrite or select unseen text, and an
 * open inline clipboard field never lets a command reach the app behind the panel.
 *
 * Harness: a real [KeyEventHandler] (Objenesis + spy so `send_key_down_up`, whose android.jar
 * `KeyEvent` constructor throws "Stub!", records instead). `EditorTextWindowReader.capture` and
 * `EditorReadback.capture` build an `ExtractedTextRequest` (an android.jar stub here), so both
 * are answered from the fake buffer, field for field.
 */
class EditorTextCommandsTest {

    private val objenesis = ObjenesisStd()
    private val log = mutableListOf<String>()

    // ------------------------------------------------------------------ fake editor
    private val text = StringBuilder()
    private var selStart = 0
    private var selEnd = 0
    /** False simulates an editor that cannot report its selection (no ExtractedText). */
    private var readbackAvailable = true
    /** Simulates an editor that rewrites committed text (autoformat): applied to every commit. */
    private var commitTransform: (String) -> String = { it }
    /** Simulates an editor that changes its text between the IME's reads and its write. */
    private var mutateOnFinishComposing: (() -> Unit)? = null
    /** Answer of the inline editor navigation call. */
    private var inlineNavigationResult = true
    private var inlineMode: String? = null
    /** Committed CharSequences, kept whole (spans included) for inspection. */
    val committed = mutableListOf<CharSequence>()

    private val editorInfo: EditorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        packageName = "com.example.notes"
    }

    private lateinit var context: Context
    private lateinit var handler: KeyEventHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.getString(any()) } answers { "str#${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg()) } answers { "str#${firstArg<Int>()}" }

        val ic = Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { proxy, method, args ->
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeEditor"
                "getTextBeforeCursor" -> text.substring(maxOf(0, lo - (args!![0] as Int)), lo)
                "getTextAfterCursor" -> text.substring(hi, minOf(text.length, hi + (args!![0] as Int)))
                "getSelectedText" -> if (lo == hi) null else text.substring(lo, hi)
                "setSelection" -> {
                    selStart = args!![0] as Int; selEnd = args[1] as Int
                    log += "setSelection($selStart,$selEnd)"; true
                }
                "commitText" -> {
                    val inserted = args!![0] as CharSequence
                    committed += inserted
                    val written = commitTransform(inserted.toString())
                    text.replace(lo, hi, written)
                    selStart = lo + written.length; selEnd = selStart
                    log += "commitText($inserted)"; true
                }
                "finishComposingText" -> { mutateOnFinishComposing?.invoke(); log += "finishComposingText()"; true }
                "beginBatchEdit", "endBatchEdit" -> true
                "getExtractedText" -> null
                else -> {
                    log += "${method.name}(${args.orEmpty().joinToString(",")})"
                    if (method.returnType == java.lang.Boolean.TYPE) true else null
                }
            }
        } as InputConnection

        val receiver = Proxy.newProxyInstance(KeyEventHandler.IReceiver::class.java.classLoader, arrayOf(KeyEventHandler.IReceiver::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> return@newProxyInstance proxy === args?.get(0)
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "toString" -> return@newProxyInstance "Receiver"
                "getCurrentInputConnection" -> return@newProxyInstance ic
                "getCurrentEditorInfo" -> return@newProxyInstance editorInfo
                "getContext" -> return@newProxyInstance context
                "isClipboardEditMode" -> return@newProxyInstance inlineMode == "edit"
                "isClipboardSearchMode" -> return@newProxyInstance inlineMode == "search"
            }
            if (READ_PREFIXES.none { method.name.startsWith(it) }) {
                log += if (method.name == "showPrivateCopyFeedback") "feedback(${args!![0]})"
                else "${method.name}(${args.orEmpty().joinToString(",")})"
            }
            when {
                method.name == "navigateClipboardEdit" -> inlineNavigationResult
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                else -> null
            }
        } as KeyEventHandler.IReceiver

        handler = spyk(objenesis.newInstance(KeyEventHandler::class.java).apply {
            setField("recv", receiver)
            setField("autocap", mockk<Autocapitalisation>(relaxed = true))
            setField("mods", Pointers.Modifiers.EMPTY)
        })
        every { handler.send_key_down_up(any(), any()) } answers {
            log += "keyDownUp(${keyName(firstArg())},${metaName(secondArg())})"
        }

        val config = mockk<Config>(relaxed = true)
        config.setField("primary_language", "en")
        mockkObject(Config.Companion)
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config

        mockkObject(EditorTextWindowReader)
        every { EditorTextWindowReader.capture(any(), any()) } answers { if (readbackAvailable) window(secondArg()) else null }
        mockkObject(EditorReadback.Companion)
        every { EditorReadback.capture(any()) } answers { if (readbackAvailable) readback() else null }
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ------------------------------------------------------------------ word moves (command 5)

    @Test
    fun wordLeftAndRightMoveTheCaretByReadbackInAnOrdinaryEditor() {
        editor("hello brave new world", 21)
        assertEquals(listOf("handle_non_text_input()", "finishComposingText()", "setSelection(16,16)"), press("word_left"))
        press("word_left")
        assertEquals(12 to 12, selection())
        editor("hello brave new world", 0)
        press("word_right")
        assertEquals(5 to 5, selection())
        // A selection collapses: left from its lower end, right from its upper end.
        editor("hello brave new world", 7, 9)
        press("word_left")
        assertEquals(6 to 6, selection())
        editor("hello brave new world", 7, 9)
        press("word_right")
        assertEquals(11 to 11, selection())
    }

    @Test
    fun selectWordLeftAndRightExtendFromTheAnchor() {
        editor("one two three", 7)
        press("select_word_left")
        assertEquals(7 to 4, selection())
        press("select_word_left")
        assertEquals(7 to 0, selection())
        editor("one two three", 3)
        press("select_word_right")
        assertEquals(3 to 7, selection())
        press("select_word_right")
        assertEquals(3 to 13, selection())
    }

    @Test
    fun withoutReadbackWordCommandsFallBackToCtrlArrowKeyEvents() {
        editor("hello world", 11)
        readbackAvailable = false
        assertEquals(listOf("handle_non_text_input()", "keyDownUp(LEFT,CTRL)"), press("word_left"))
        assertEquals(listOf("handle_non_text_input()", "keyDownUp(RIGHT,CTRL)"), press("word_right"))
        assertEquals(listOf("handle_non_text_input()", "keyDownUp(LEFT,CTRL+SHIFT)"), press("select_word_left"))
        assertEquals(listOf("handle_non_text_input()", "keyDownUp(RIGHT,CTRL+SHIFT)"), press("select_word_right"))
    }

    @Test
    fun inATerminalOrBlindFieldWordMovesAreKeyEventsAndSelectionIsRefused() {
        for (pkg in listOf("com.termux", "com.carriez.flutter_hbb")) {
            editorInfo.packageName = pkg
            editor("ls -la /tmp", 11)
            assertEquals(pkg, listOf("handle_non_text_input()", "keyDownUp(LEFT,CTRL)"), press("word_left"))
            assertEquals(pkg, listOf("handle_non_text_input()", "keyDownUp(RIGHT,CTRL)"), press("word_right"))
            for (name in listOf("select_word_left", "select_word_right", "select_word", "select_line", "case_cycle")) {
                assertEquals("$name $pkg", listOf("handle_non_text_input()", UNAVAILABLE), press(name))
            }
            assertEquals("ls -la /tmp", text.toString())
        }
    }

    // ------------------------------------------------------------------ select word / line (command 6)

    @Test
    fun selectWordSelectsTheWordAtTheCursorIncludingInnerApostrophes() {
        editor("we don't stop", 5)
        press("select_word")
        assertEquals(3 to 8, selection())
        // A selection grows to the words it touches.
        editor("we don't stop", 4, 10)
        press("select_word")
        assertEquals(3 to 13, selection())
        // Caret between two spaces: nothing to select.
        editor("a  b", 2)
        assertEquals(listOf("handle_non_text_input()", "feedback(str#${R.string.keyboard_text_menu_no_word})"), press("select_word"))
        assertEquals(2 to 2, selection())
    }

    @Test
    fun selectLineSelectsTheLineWithoutItsBreak() {
        editor("first\nsecond line\nthird", 9)
        press("select_line")
        assertEquals(6 to 17, selection())
        editor("only", 2)
        press("select_line")
        assertEquals(0 to 4, selection())
    }

    @Test
    fun aWordOrLineThatRunsPastWhatWasReadIsRefusedNotGuessed() {
        // The line is longer than the read reach on both sides: its bounds are unknown.
        val long = "x".repeat(EditorTextNavigation.LINE_REACH + 10)
        editor("$long $long", long.length)
        assertEquals(listOf("handle_non_text_input()", UNAVAILABLE), press("select_line"))
        // A word that runs past the reach on both sides of the caret.
        val word = "y".repeat(2 * EditorTextNavigation.WORD_REACH + 10)
        editor(word, word.length / 2)
        assertEquals(listOf("handle_non_text_input()", UNAVAILABLE), press("select_word"))
    }

    @Test
    fun selectWordAndLineWithoutReadbackAreRefused() {
        editor("hello world", 3)
        readbackAvailable = false
        assertEquals(listOf("handle_non_text_input()", UNAVAILABLE), press("select_word"))
        assertEquals(listOf("handle_non_text_input()", UNAVAILABLE), press("select_line"))
    }

    @Test
    fun anEditorChangedBetweenReadAndWriteIsNotMoved() {
        editor("hello world", 11)
        mutateOnFinishComposing = { text.insert(0, "X"); selStart++; selEnd++ }
        val effects = press("select_word")
        assertTrue(effects.toString(), effects.none { it.startsWith("setSelection") })
        assertTrue(effects.toString(), UNAVAILABLE in effects)
    }

    @Test
    fun wordAndLineCommandsActOnTheOpenInlineClipboardEditorNeverOnTheApp() {
        inlineMode = "edit"
        editor("hello world", 11)
        for ((name, command) in listOf(
            "word_left" to "WORD_LEFT", "word_right" to "WORD_RIGHT",
            "select_word_left" to "SELECT_WORD_LEFT", "select_word_right" to "SELECT_WORD_RIGHT",
            "select_word" to "SELECT_WORD", "select_line" to "SELECT_LINE",
        )) {
            assertEquals(name, listOf("navigateClipboardEdit($command)"), press(name))
        }
        inlineNavigationResult = false
        assertEquals(
            listOf("navigateClipboardEdit(SELECT_WORD)", "feedback(str#${R.string.keyboard_text_menu_no_word})"),
            press("select_word"),
        )
        // The other inline fields (search, tags, emoji/GIF) swallow them.
        inlineMode = "search"
        for (name in listOf("word_left", "select_word", "select_line")) assertEquals(name, emptyList<String>(), press(name))
        assertEquals(11 to 11, selection())
    }

    // ------------------------------------------------------------------ case cycle (command 1)

    @Test
    fun caseCycleRewritesTheWordBeforeTheAutoSpaceAndKeepsItSelected() {
        editor("i said hello ", 13)
        val effects = press("case_cycle")
        assertEquals("i said Hello ", text.toString())
        assertEquals(7 to 12, selection())
        // Composition is finished before the write; the word is selected, replaced, re-selected.
        assertTrue(effects.toString(), effects.indexOf("finishComposingText()") < effects.indexOf("commitText(Hello)"))
        assertTrue(effects.toString(), "onExplicitEditStarted()" in effects)
        press("case_cycle")
        assertEquals("i said HELLO ", text.toString())
        assertEquals(7 to 12, selection())
        press("case_cycle")
        assertEquals("i said hello ", text.toString())
    }

    @Test
    fun caseCycleOfASelectionCoversEveryWordAndTracksLengthChanges() {
        editor("x hello world y", 2, 13)
        press("case_cycle")
        assertEquals("x Hello World y", text.toString())
        assertEquals(2 to 13, selection())
        // ß uppercases to SS: the re-selection follows the new UTF-16 length.
        editor("straße", 0, 6)
        press("case_cycle")
        assertEquals("Straße", text.toString())
        press("case_cycle")
        assertEquals("STRASSE", text.toString())
        assertEquals(0 to 7, selection())
        // Mixed case starts the cycle over at lower case.
        editor("iPhone", 0, 6)
        press("case_cycle")
        assertEquals("iphone", text.toString())
    }

    @Test
    fun caseCycleUsesTheKeyboardLanguage() {
        Config.globalConfig()!!.setField("primary_language", "tr")
        editor("istanbul", 8)
        press("case_cycle")
        assertEquals("\u0130stanbul", text.toString())
    }

    @Test
    fun caseCycleRefusesWhatItCannotVerify() {
        // No word at or before the cursor.
        editor("end. ", 5)
        assertEquals(listOf("handle_non_text_input()", "feedback(str#${R.string.keyboard_text_menu_no_word})"), press("case_cycle"))
        // Nothing with letter case.
        editor("1234", 0, 4)
        assertEquals(listOf("handle_non_text_input()", "feedback(str#${R.string.case_cycle_failed})"), press("case_cycle"))
        // No readback.
        editor("hello", 5)
        readbackAvailable = false
        assertEquals(listOf("handle_non_text_input()", UNAVAILABLE), press("case_cycle"))
        readbackAvailable = true
        // The editor changed between the read and the write: nothing is written.
        editor("hello world", 11)
        mutateOnFinishComposing = { text.insert(0, "X"); selStart++; selEnd++ }
        val effects = press("case_cycle")
        assertTrue(effects.toString(), effects.none { it.startsWith("commitText") })
        assertTrue(effects.toString(), UNAVAILABLE in effects)
        assertEquals("Xhello world", text.toString())
    }

    @Test
    fun caseCycleReportsAWriteTheEditorDidNotAcknowledgeExactly() {
        editor("hello", 5)
        commitTransform = { "$it!" }
        val effects = press("case_cycle")
        assertTrue(effects.toString(), "feedback(str#${R.string.case_cycle_failed})" in effects)
        // The unverified result is not re-selected as if it were the word.
        assertTrue(effects.toString(), effects.none { it == "setSelection(0,5)" && effects.indexOf(it) > effects.indexOf("commitText(Hello)") })
    }

    @Test
    fun caseCycleNeverActsBehindAnInlineField() {
        editor("hello", 5)
        for (mode in listOf("edit", "search")) {
            inlineMode = mode
            assertEquals(mode, listOf(UNAVAILABLE), press("case_cycle"))
        }
        assertEquals("hello", text.toString())
    }

    // ------------------------------------------------------------------ share (command 3)

    /**
     * Share used `performContextMenuAction(android.R.id.shareText)`, which only a stock EditText
     * honours (Chrome/WebView and Compose return false — research §1.3, §1.4): it now reads the
     * selection and opens the IME's own ACTION_SEND chooser, never both.
     */
    @Test
    fun shareSendsTheSelectionThroughTheImesOwnChooserNeverTheContextMenuId() {
        mockkObject(TextActionLauncher)
        every { TextActionLauncher.share(any(), any(), any()) } answers { log += "share(${secondArg<String>()})" }
        editor("say hello there", 4, 9)
        assertEquals(listOf("handle_non_text_input()", "share(hello)"), press("shareText"))
        // Nothing selected: say so, share nothing.
        editor("say hello there", 4)
        assertEquals(
            listOf("handle_non_text_input()", "feedback(str#${R.string.text_action_no_selection})"),
            press("shareText"),
        )
    }

    @Test
    fun shareIsRefusedInTerminalStyleFieldsAndSwallowedBehindInlineFields() {
        mockkObject(TextActionLauncher)
        every { TextActionLauncher.share(any(), any(), any()) } answers { log += "share(${secondArg<String>()})" }
        editorInfo.packageName = "com.carriez.flutter_hbb"
        editor("1111", 0, 4)
        assertEquals(listOf("handle_non_text_input()", UNAVAILABLE), press("shareText"))
        editorInfo.packageName = "com.example.notes"
        for (mode in listOf("edit", "search")) {
            inlineMode = mode
            assertEquals(mode, emptyList<String>(), press("shareText"))
        }
    }

    // ------------------------------------------------------------------ harness

    private fun editor(content: String, start: Int, end: Int = start) {
        text.setLength(0); text.append(content); selStart = start; selEnd = end
        mutateOnFinishComposing = null
        commitTransform = { it }
    }

    private fun selection() = selStart to selEnd

    private fun press(name: String): List<String> {
        log.clear()
        handler.key_up(KeyValue.getKeyByName(name), Pointers.Modifiers.EMPTY, false)
        return log.toList()
    }

    private fun window(reach: Int): EditorTextWindow {
        val lo = minOf(selStart, selEnd); val hi = maxOf(selStart, selEnd)
        val before = text.substring(maxOf(0, lo - reach), lo)
        val after = text.substring(hi, minOf(text.length, hi + reach))
        return EditorTextWindow(selStart, selEnd, before, text.substring(lo, hi), after,
            beforeComplete = before.length == lo, afterComplete = hi + after.length == text.length)
    }

    /** Mirrors EditorReadback.capture's fields over the fake buffer. */
    private fun readback(): EditorReadback {
        val ctor = EditorReadback::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, String::class.java, String::class.java,
        ).apply { isAccessible = true }
        val lo = minOf(selStart, selEnd); val hi = maxOf(selStart, selEnd)
        return ctor.newInstance(selStart, selEnd,
            text.substring(maxOf(0, lo - EditorReadback.GUARD_LENGTH), lo),
            text.substring(hi, minOf(text.length, hi + EditorReadback.GUARD_LENGTH)),
            text.substring(lo, hi))
    }

    private fun keyName(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        else -> code.toString()
    }

    private fun metaName(meta: Int): String {
        val ctrl = (meta and KeyEvent.META_CTRL_ON) != 0
        val shift = (meta and KeyEvent.META_SHIFT_ON) != 0
        return listOfNotNull("CTRL".takeIf { ctrl }, "SHIFT".takeIf { shift }).joinToString("+").ifEmpty { "0" }
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
        val READ_PREFIXES = listOf("get", "is", "was", "has", "can")
        val UNAVAILABLE = "feedback(str#${R.string.dynamic_action_unavailable})"
    }
}
