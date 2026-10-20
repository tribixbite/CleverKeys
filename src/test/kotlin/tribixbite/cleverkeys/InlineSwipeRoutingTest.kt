package tribixbite.cleverkeys

import android.content.res.Resources
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import java.lang.reflect.Proxy

/**
 * Maintainer report 2026-10-20: "Can we add swipe support to clipboard entry editing? Currently
 * it only supports tap typing, and swipes insert into the foreground active app text field."
 *
 * Tap typing reaches the inline fields through KeyEventHandler.sendText's routing chain, but a
 * swiped word was committed by SuggestionHandler straight to the app's InputConnection. These
 * tests drive the real pipeline entry points with an inline field active and require that the
 * word lands in the FIELD (with its spacing, bar replacement and backspace undo) while the
 * app's InputConnection is never touched, and that nothing is learned.
 *
 * Harness: mock tier, Objenesis-allocated SuggestionHandler with the fields the swipe path
 * reads (same pattern as [SwipeAutocapCommitTest]); a real KeyEventHandler over a recording
 * receiver proxy (same pattern as [InlineFieldShortcutTest]).
 */
class InlineSwipeRoutingTest {

    private val objenesis = ObjenesisStd()

    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var bar: SuggestionBar
    private lateinit var ic: InputConnection
    private lateinit var resources: Resources
    private lateinit var config: Config
    private lateinit var inputCoordinator: InputCoordinator
    private lateinit var editorInfo: EditorInfo

    private var barWords: List<String> = emptyList()

    /** The clipboard entry editor as the swipe pipeline sees it (caret-aware). */
    private class FakeEditor(var text: String, var caret: Int = text.length, var session: Long = 7L) : InlineTextTarget {
        override val field = InlineField.CLIPBOARD_EDIT
        override fun state() = InlineFieldState(text, caret, caret, session)
        override fun replace(start: Int, end: Int, text: String): Boolean {
            this.text = this.text.substring(0, start) + text + this.text.substring(end)
            caret = start + text.length
            return true
        }
    }

    /** The active inline target the handler sees; null = typing goes to the app. */
    private var inlineTarget: InlineTextTarget? = null

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.auto_space_after_suggestion = true
        config.backspace_undo_swipe = true
        config.swipe_engine_mode = "ctc"
        every { Config.globalConfig() } returns config

        val predictor = mockk<WordPredictor>(relaxed = true)
        every { predictor.applyUserWordCaseToList(any()) } answers { firstArg() }
        coordinator = mockk(relaxed = true)
        every { coordinator.getDictionaryManager() } returns mockk(relaxed = true)
        every { coordinator.getWordPredictor() } returns predictor

        contextTracker = mockk(relaxed = true)
        every { contextTracker.getLastCommitSource() } returns PredictionSource.UNKNOWN
        every { contextTracker.getLastAutoInsertedWord() } returns null
        every { contextTracker.wasLastInputSwipe() } returns true
        every { contextTracker.getCurrentWordLength() } returns 0
        every { contextTracker.getCurrentWord() } returns ""
        every { contextTracker.shouldSyncForInputType(any()) } returns true

        barWords = emptyList()
        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.setSuggestionsWithScores(any(), any(), any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
        }
        every { bar.getTopSuggestion() } answers { barWords.firstOrNull() }

        inputCoordinator = mockk(relaxed = true)
        every { inputCoordinator.getCurrentSwipeData() } returns null

        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } returns ""
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        resources = mockk(relaxed = true)
        editorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.notes"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        inlineTarget = null
    }

    @After
    fun teardown() = unmockkAll()

    private fun handler(): SuggestionHandler {
        val handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contextTracker", contextTracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("contractionManager", mockk<ContractionManager>(relaxed = true))
        handler.setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        handler.setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
        handler.setInlineTargetProvider { inlineTarget }
        return handler
    }

    private fun SuggestionHandler.swipe(vararg words: String, shift: Boolean = false) =
        handleSwipePredictionResults(
            words.toList(), words.indices.map { 100 - it }, ic, editorInfo, resources,
            shift, false, inputCoordinator, SuggestionOrigin.CTC
        )

    private fun SuggestionHandler.tap(word: String): String? =
        onSuggestionSelected(word, ic, editorInfo, resources, isManualSelection = true)

    /** Nothing at all reached the app's InputConnection. */
    private fun assertAppUntouched() {
        verify(exactly = 0) { ic.commitText(any(), any()) }
        verify(exactly = 0) { ic.setComposingText(any(), any()) }
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
        verify(exactly = 0) { ic.finishComposingText() }
        verify(exactly = 0) { ic.getTextBeforeCursor(any(), any()) }
        verify(exactly = 0) { ic.beginBatchEdit() }
        verify(exactly = 0) { ic.sendKeyEvent(any()) }
    }

    // ── swipe auto-insert ─────────────────────────────────────────────────────────

    @Test
    fun aSwipeWhileTheEntryEditorIsOpenGoesIntoTheEditorNotTheApp() {
        val editor = FakeEditor("Meet at")
        inlineTarget = editor

        handler().swipe("noon", "moon")

        assertEquals("Meet at noon", editor.text)
        assertAppUntouched()
        assertWithMessage("the alternatives are on the bar").that(barWords).containsExactly("noon", "moon").inOrder()
    }

    @Test
    fun aSwipeIntoAnInlineFieldIsNeitherLearnedNorCapturedNorAppOwned() {
        inlineTarget = FakeEditor("")

        handler().swipe("hello")

        verify(exactly = 0) { contextTracker.setLastAutoInsertedWord(any()) }
        verify(exactly = 0) { contextTracker.commitWord(any(), any(), any()) }
        verify { contextTracker.setWasLastInputSwipe(false) }
        verify { inputCoordinator.resetSwipeData() }
        verify(exactly = 0) { inputCoordinator.getCurrentSwipeData() }
    }

    @Test
    fun aLatchedShiftCapitalizesTheSwipedWordAndIsSpent() {
        val editor = FakeEditor("")
        inlineTarget = editor

        handler().swipe("paris", shift = true)

        assertEquals("Paris", editor.text)
        verify { inputCoordinator.clearLatchedShiftAfterSwipe() }
    }

    @Test
    fun withNoInlineFieldTheSwipeStillCommitsToTheApp() {
        handler().swipe("hello")

        verify { ic.commitText(match { it.toString() == "hello " }, 1) }
    }

    // ── bar tap replaces the swiped word in the editor ────────────────────────────

    @Test
    fun aBarTapReplacesTheSwipedWordInTheEditor() {
        val editor = FakeEditor("Meet at")
        inlineTarget = editor
        val handler = handler()

        handler.swipe("noon", "moon")
        assertEquals("moon", handler.tap("moon"))

        assertEquals("Meet at moon", editor.text)
        assertAppUntouched()
    }

    @Test
    fun aBarTapWithNoSwipedWordInsertsIntoTheEditor() {
        val editor = FakeEditor("abc")
        inlineTarget = editor

        handler().tap("def")

        assertEquals("abc def", editor.text)
        assertAppUntouched()
    }

    @Test
    fun specialChipsAreRefusedWhileAnInlineFieldOwnsTyping() {
        val editor = FakeEditor("abc")
        inlineTarget = editor

        assertNull(handler().tap(Suggestion.EXACT_ADD_PREFIX + "abcd"))

        assertEquals("abc", editor.text)
        assertAppUntouched()
    }

    // ── backspace undo ──────────────────────────────────────────────────────────

    @Test
    fun backspaceUndoRemovesTheSwipedWordFromTheEditor() {
        val editor = FakeEditor("Meet at")
        inlineTarget = editor
        val handler = handler()

        handler.swipe("noon")
        assertTrue(handler.undoInlineSwipe())

        assertEquals("Meet at", editor.text)
        assertFalse("a second backspace is ordinary", handler.undoInlineSwipe())
        assertAppUntouched()
    }

    @Test
    fun backspaceUndoHonoursTheSetting() {
        config.backspace_undo_swipe = false
        val editor = FakeEditor("")
        inlineTarget = editor
        val handler = handler()

        handler.swipe("noon")

        assertFalse(handler.undoInlineSwipe())
        assertEquals("noon", editor.text)
    }

    // ── KeyEventHandler: the target and the backspace route ───────────────────────

    private val log = mutableListOf<String>()
    private var mode: String? = null

    private fun <T> recordingProxy(type: Class<T>, answer: (String, Array<Any?>?) -> Any?): T =
        @Suppress("UNCHECKED_CAST")
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            log += method.name + "(" + (args?.joinToString(",") ?: "") + ")"
            answer(method.name, args) ?: when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        } as T

    private fun keyHandler(hooks: KeyEventHandler.LearningHooks?): KeyEventHandler {
        val receiver = recordingProxy(KeyEventHandler.IReceiver::class.java) { name, args ->
            when (name) {
                "getCurrentInputConnection" -> ic
                "getCurrentEditorInfo" -> editorInfo
                "isClipboardTagMode" -> mode == "tag"
                "isClipboardEditMode" -> mode == "edit" || mode == "edit+search"
                "isClipboardSearchMode" -> mode == "search" || mode == "edit+search"
                "isEmojiPaneOpen" -> mode == "emoji"
                "isGifPaneOpen" -> mode == "gif"
                "inlineFieldState" -> InlineFieldState("x", 1, 1, 3L).takeIf { args?.get(0) == InlineField.CLIPBOARD_EDIT }
                "replaceInInlineField" -> true
                else -> null
            }
        }
        return objenesis.newInstance(KeyEventHandler::class.java).apply {
            setField("recv", receiver)
            setField("autocap", mockk<Autocapitalisation>(relaxed = true))
            setField("mods", Pointers.Modifiers.EMPTY)
            learningHooks = hooks
        }
    }

    @Test
    fun theActiveTargetFollowsTheSendTextPriorityAndUsesTheReceiver() {
        val keys = keyHandler(null)
        mode = null
        assertNull(keys.activeInlineTarget())
        mode = "edit+search" // edit outranks search, exactly as in sendText
        val target = keys.activeInlineTarget()!!
        assertEquals(InlineField.CLIPBOARD_EDIT, target.field)
        assertEquals(InlineFieldState("x", 1, 1, 3L), target.state())
        log.clear()
        assertTrue(target.replace(0, 1, "y"))
        assertEquals(listOf("replaceInInlineField(CLIPBOARD_EDIT,0,1,y)"), log)
        mode = "tag"; assertEquals(InlineField.CLIPBOARD_TAG, keys.activeInlineField())
        mode = "search"; assertEquals(InlineField.CLIPBOARD_SEARCH, keys.activeInlineField())
        mode = "emoji"; assertEquals(InlineField.EMOJI_SEARCH, keys.activeInlineField())
        mode = "gif"; assertEquals(InlineField.GIF_SEARCH, keys.activeInlineField())
    }

    private fun backspace(keys: KeyEventHandler): List<String> {
        log.clear()
        keys.key_up(KeyValue.keyeventKey("del", KeyEvent.KEYCODE_DEL, 0), Pointers.Modifiers.EMPTY, false)
        return log.filter { !it.startsWith("is") && !it.startsWith("get") }
    }

    @Test
    fun backspaceAfterAnInlineSwipeUndoesItInsteadOfDeletingOneCharacter() {
        mode = "edit"
        val hooks = mockk<KeyEventHandler.LearningHooks>(relaxed = true)
        every { hooks.undoInlineSwipe() } returns true
        assertEquals(emptyList<String>(), backspace(keyHandler(hooks)))
        verify(exactly = 1) { hooks.undoInlineSwipe() }
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }

        every { hooks.undoInlineSwipe() } returns false
        assertEquals(listOf("backspaceClipboardEdit()"), backspace(keyHandler(hooks)))
    }

    @Test
    fun outsideInlineFieldsBackspaceNeverAsksForTheInlineUndo() {
        mode = null
        val hooks = mockk<KeyEventHandler.LearningHooks>(relaxed = true)
        // Only the routing decision matters: with no inline field the backspace takes the app
        // path, whose android.jar KeyEvent constructor throws "Stub!" at the very end — after
        // the point where the inline hook would have been consulted.
        runCatching { backspace(keyHandler(hooks)) }
        verify(exactly = 0) { hooks.undoInlineSwipe() }
    }

    // ── bridge delegation of the new IReceiver methods ────────────────────────────

    @Test
    fun theReceiverBridgeDelegatesTheInlineFieldMethods() {
        val bridge = KeyEventReceiverBridge(mockk<CleverKeysService>(relaxed = true), mockk<Handler>(relaxed = true))
        val receiver = mockk<KeyboardReceiver>(relaxed = true)
        val state = InlineFieldState("abc", 3, 3, 1L)
        every { receiver.inlineFieldState(InlineField.GIF_SEARCH) } returns state
        every { receiver.replaceInInlineField(InlineField.GIF_SEARCH, 0, 3, "x") } returns true
        bridge.setReceiver(receiver)
        val recv: KeyEventHandler.IReceiver = bridge

        assertEquals(state, recv.inlineFieldState(InlineField.GIF_SEARCH))
        assertTrue(recv.replaceInInlineField(InlineField.GIF_SEARCH, 0, 3, "x"))
        verify(exactly = 1) { receiver.replaceInInlineField(InlineField.GIF_SEARCH, 0, 3, "x") }
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                f.set(this, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }
}
