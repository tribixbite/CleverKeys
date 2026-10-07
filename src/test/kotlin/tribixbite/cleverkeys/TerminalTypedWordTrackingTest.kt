package tribixbite.cleverkeys

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
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Terminal typed-word tracking (Seeker/Termux report, 2026-10-07): "the suggestion bar keeps
 * showing stale words … it's constantly trying to autocorrect words and just keeps appending
 * newly typed letters."
 *
 * A terminal exposes no readable text buffer — `getTextBeforeCursor` returns "" — so every
 * editor-sync path reads nothing and the keystroke tracker in [PredictionContextTracker] is
 * the only record of the word being typed. That tracker only ever ended a word on a typed
 * non-letter character. Enter, Tab, Esc, arrows, Home/End and Ctrl chords are KEY EVENTS:
 * they never reach `handle_text_typed`, and in an ordinary editor the selection callback
 * that follows them re-syncs the tracker — a terminal sends none. So after `ls` Enter, typing
 * `cd` tracked `lscd`; the bar showed corrections of the phantom word and a tapped suggestion
 * deleted four characters.
 *
 * # Harness
 *
 * Mock tier. REAL: [SuggestionHandler] (Objenesis-allocated production bodies), the tracker,
 * and [KeyEventHandler] for the routing pins. The editor is a terminal double: package
 * `com.termux`, an InputConnection whose reads always return "" (the Termux contract).
 *
 * RED (2026-10-07, hook plumbing present but SuggestionHandler.handleNonTextInput empty and
 * KeyEventHandler not yet calling it): 10 of 12 failed — `ls`/Enter/`cd` tracked "lscd";
 * Ctrl+C gave "foobar"; delete-last-word gave "foox"; a tap after Enter sent 4 backspaces
 * instead of 2; predictions for "abc"/"ab"/"a" repainted the bar after the line was emptied
 * ([ax], [abx], [abcx], [abx], [ax]); swipe final autocorrect committed "is " for "ls"; the key
 * routing reported no non-text key. Already green: space autocorrect skips terminals, and an
 * ordinary editor keeps its tracker.
 */
class TerminalTypedWordTrackingTest {

    private val objenesis = ObjenesisStd()

    private lateinit var config: Config
    private lateinit var tracker: PredictionContextTracker
    private lateinit var predictor: WordPredictor
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var bar: SuggestionBar
    private lateinit var keyevents: KeyEventHandler
    private lateinit var ic: InputConnection
    private lateinit var handler: SuggestionHandler

    /** Prediction tasks submitted and not yet run (so a test can order them against keys). */
    private val pendingTasks = ArrayList<Runnable>()

    /** Every word list the handler pushed to the bar, in order. */
    private val barPosts = ArrayList<List<String>>()

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
        config.custom_terminal_packages = emptySet()
        config.primary_language = "en"
        every { Config.globalConfig() } returns config
        every { Config.globalConfigOrNull() } returns config

        tracker = PredictionContextTracker()

        predictor = mockk(relaxed = true)
        every { predictor.predictWordsWithContext(any(), any()) } answers {
            val partial = firstArg<String>()
            WordPredictor.PredictionResult(listOf("${partial}x"), listOf(100))
        }
        // A shell token is "corrected" to prose if autocorrect is allowed to run.
        every { predictor.autoCorrect(any()) } answers {
            when (firstArg<String>()) { "ls" -> "is"; "cd" -> "ce"; else -> firstArg() }
        }
        every { predictor.isInDictionary(any()) } returns true
        every { predictor.isInDictionary(any(), any()) } returns true
        coordinator = mockk(relaxed = true)
        every { coordinator.getWordPredictor() } returns predictor

        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.setSuggestionsWithScores(any(), any(), any()) } answers {
            barPosts.add(firstArg<List<String>>().toList())
        }

        ic = mockk(relaxed = true)
        every { ic.getTextBeforeCursor(any(), any()) } returns ""
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        every { ic.commitText(any(), any()) } returns true
        every { ic.getCursorCapsMode(any()) } returns 0

        val contractions = mockk<ContractionManager>(relaxed = true)
        every { contractions.isKnownContraction(any()) } returns false
        every { contractions.isContractionKey(any()) } returns false
        every { contractions.getNonPairedMapping(any()) } returns null
        every { contractions.generatePossessive(any()) } returns null

        pendingTasks.clear()
        barPosts.clear()
        val tasks = mockk<PredictionTaskRunner>(relaxed = true)
        every { tasks.cancelAndSubmit(any()) } answers { pendingTasks.add(firstArg()) }
        val main = mockk<Handler>(relaxed = true)
        every { main.post(any()) } answers { firstArg<Runnable>().run(); true }

        keyevents = mockk(relaxed = true)
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
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    private fun editor(pkg: String = "com.termux"): EditorInfo = objenesis.newInstance(EditorInfo::class.java).apply {
        packageName = pkg
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun type(word: String, editor: EditorInfo) {
        for (c in word) handler.handleRegularTyping(c.toString(), ic, editor)
    }

    /** Runs the prediction tasks queued so far, in submission order (each posts inline). */
    private fun runPredictions() {
        val queued = pendingTasks.toList()
        pendingTasks.clear()
        queued.forEach { it.run() }
    }

    private fun tracked() = tracker.getCurrentWord()

    // ------------------------------------------------------------------ word boundaries

    @Test
    fun enterInATerminalEndsTheTypedWord() {
        val termux = editor()
        type("ls", termux)
        handler.handleNonTextInput(termux)
        type("cd", termux)
        assertWithMessage("ls, Enter, cd must track only the new word").that(tracked()).isEqualTo("cd")
    }

    @Test
    fun ctrlCAndAnyOtherNonTextKeyEndTheTypedWord() {
        val termux = editor()
        type("foo", termux)
        handler.handleNonTextInput(termux)
        type("bar", termux)
        assertWithMessage("foo, Ctrl+C, bar").that(tracked()).isEqualTo("bar")
    }

    @Test
    fun theNonTextKeyClearsTheBarAndCancelsItsPendingPrediction() {
        val termux = editor()
        type("ls", termux)
        handler.handleNonTextInput(termux)
        verify(atLeast = 1) { bar.clearSuggestions() }
        // The prediction for "ls" was computed against a line the shell has since taken.
        runPredictions()
        assertWithMessage("no suggestion for the submitted word may reach the bar")
            .that(barPosts).isEmpty()
    }

    @Test
    fun backspaceShrinksTheTrackedWordAndAnEmptyWordShowsNothing() {
        val termux = editor()
        type("abc", termux)
        repeat(3) { handler.handleBackspace() }
        assertWithMessage("abc, backspace x3").that(tracked()).isEmpty()
        // Predictions queued for "abc"/"ab"/"a" complete after the line is empty.
        runPredictions()
        assertWithMessage("a prediction for a deleted word must not repaint the bar")
            .that(barPosts).isEmpty()
        type("x", termux)
        assertWithMessage("abc, backspace x3, x").that(tracked()).isEqualTo("x")
        runPredictions()
        assertWithMessage("the bar reflects the current tracked word only")
            .that(barPosts.last()).containsExactly("xx")
    }

    @Test
    fun aTappedSuggestionAfterEnterDeletesOnlyTheNewWord() {
        val termux = editor()
        type("ls", termux)
        handler.handleNonTextInput(termux)
        type("cd", termux)
        handler.onSuggestionSelected("cdx", ic, termux, mockk<Resources>(relaxed = true), isManualSelection = true)
        verify(exactly = 2) { keyevents.send_key_down_up(KeyEvent.KEYCODE_DEL, 0) }
    }

    @Test
    fun deleteLastWordInATerminalEndsTheTypedWord() {
        val termux = editor()
        type("foo", termux)
        handler.handleDeleteLastWord(ic, termux)
        type("x", termux)
        assertWithMessage("foo, Ctrl+W (delete-last-word), x").that(tracked()).isEqualTo("x")
    }

    // ------------------------------------------------------------------ shared predicate

    @Test
    fun aCustomTerminalPackageGetsTheSameReset() {
        config.custom_terminal_packages = setOf("org.custom.shell")
        val shell = editor("org.custom.shell")
        type("ls", shell)
        handler.handleNonTextInput(shell)
        type("cd", shell)
        assertWithMessage("custom terminal package").that(tracked()).isEqualTo("cd")
    }

    @Test
    fun anOrdinaryEditorKeepsItsTrackerForCursorSync() {
        // Ordinary editors report the selection change that follows the key and re-sync from
        // their real buffer; the key itself must not pre-empt that.
        val notes = editor("com.example.notes")
        type("ls", notes)
        handler.handleNonTextInput(notes)
        assertWithMessage("non-terminal editor").that(tracked()).isEqualTo("ls")
    }

    // ------------------------------------------------------------------ autocorrect

    @Test
    fun spaceAutocorrectNeverRewritesATerminalWord() {
        val termux = editor()
        type("ls", termux)
        handler.handleRegularTyping(" ", ic, termux)
        verify(exactly = 0) { ic.deleteSurroundingText(any(), any()) }
        verify(exactly = 0) { ic.commitText(match { it.toString().startsWith("is") }, any()) }
    }

    @Test
    fun swipeFinalAutocorrectNeverRewritesATerminalWord() {
        val termux = editor()
        tracker.setWasLastInputSwipe(true)
        handler.onSuggestionSelected("ls", ic, termux, mockk<Resources>(relaxed = true), isManualSelection = false)
        verify(exactly = 0) { ic.commitText(match { it.toString().startsWith("is") }, any()) }
        verify { ic.commitText(match { it.toString().startsWith("ls") }, any()) }
    }

    // ------------------------------------------------------------------ key routing

    private fun keyHandler(): Pair<KeyEventHandler, KeyEventHandler.IReceiver> {
        val recv = mockk<KeyEventHandler.IReceiver>(relaxed = true)
        every { recv.getHandler() } returns mockk(relaxed = true)
        every { recv.isClipboardTagMode() } returns false
        every { recv.isClipboardEditMode() } returns false
        every { recv.isClipboardSearchMode() } returns false
        every { recv.isEmojiPaneOpen() } returns false
        every { recv.isGifPaneOpen() } returns false
        every { recv.getLastAutocorrectOriginalWord() } returns null
        every { recv.getLastAutoInsertedWord() } returns null
        // A null connection makes sendKeyevent return before constructing an android KeyEvent.
        every { recv.getCurrentInputConnection() } returns null
        return KeyEventHandler(recv) to recv
    }

    @Test
    fun everyNonTextKeyIsReportedAndTextAndBackspaceAreNot() {
        val (keys, recv) = keyHandler()
        KeyModifier.set_modmap(null)
        val ctrlC = KeyModifier.modify(KeyValue.makeCharKey('c'), KeyValue.Modifier.CTRL)
        val nonText = listOf("enter", "tab", "esc", "up", "down", "left", "right", "home", "end", "page_up")
            .map { KeyValue.getKeyByName(it) } + ctrlC
        for (key in nonText) keys.key_up(key, Pointers.Modifiers.EMPTY, false)
        verify(exactly = nonText.size) { recv.handle_non_text_input() }

        keys.key_up(KeyValue.getKeyByName("backspace"), Pointers.Modifiers.EMPTY, false)
        verify(exactly = nonText.size) { recv.handle_non_text_input() }
        verify(exactly = 1) { recv.handle_backspace() }
    }

    @Test
    fun theImeActionEditingCommandsAndCursorSlidersAreReported() {
        val (keys, recv) = keyHandler()
        keys.key_up(KeyValue.getKeyByName("action"), Pointers.Modifiers.EMPTY, false)
        keys.key_up(KeyValue.getKeyByName("pasteAsPlainText"), Pointers.Modifiers.EMPTY, false)
        keys.key_up(KeyValue.getKeyByName("cursor_left"), Pointers.Modifiers.EMPTY, false)
        verify(exactly = 3) { recv.handle_non_text_input() }
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
        throw AssertionError("field '$name' not found on ${javaClass.simpleName}")
    }
}
