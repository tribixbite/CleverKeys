package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SAS-1 (v1.5.0, user-requested): smart auto-space around punctuation.
 *
 * Feature A — no leading auto-space after opening punctuation:
 *   `(` + swipe "word" → `(word ` (was `( word `).
 *
 * Feature B — closing punctuation swallows the AUTOMATIC trailing space:
 *   swipe "word" (commits `word `) then type `.` → `word.` (was `word .`).
 *   Only the automatic space may be swallowed: manually typed spaces,
 *   cursor movement, backspace, and field switches all preserve the space.
 *
 * Harness mirrors Issue151UrlBarSuggestionTapTest (real SuggestionHandler,
 * BaseInputConnection over its internal editable) and additionally wires a
 * real KeyEventHandler whose IReceiver delegates the auto-space pending
 * state to the shared PredictionContextTracker — exactly as
 * KeyEventReceiverBridge does in production. Punctuation is typed through
 * the production key path: KeyEventHandler.key_up → sendText.
 */
@RunWith(AndroidJUnit4::class)
class SmartAutoSpaceTest {

    /**
     * BaseInputConnection(view, fullEditor=true) edits its OWN internal
     * Editable. getExtractedText is overridden (Base returns null) so the
     * position-stamp validation path is exercised like in real editors.
     */
    private class TestInputConnection(target: EditText) : BaseInputConnection(target, true) {
        fun editableText(): String = editable?.toString() ?: ""

        fun setCursor(position: Int) {
            editable?.let { Selection.setSelection(it, position) }
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            val et = ExtractedText()
            val ed = editable
            et.text = ed?.toString() ?: ""
            et.startOffset = 0
            val sel = ed?.let { Selection.getSelectionEnd(it) } ?: 0
            et.selectionStart = if (sel >= 0) sel else (ed?.length ?: 0)
            et.selectionEnd = et.selectionStart
            return et
        }
    }

    private lateinit var context: Context
    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var suggestionHandler: SuggestionHandler
    private lateinit var keyEventHandler: KeyEventHandler
    private lateinit var inputConnection: TestInputConnection
    private lateinit var editText: EditText

    private val plainEditorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "com.example.notes"
    }

    companion object {
        private var sharedPredictor: WordPredictor? = null
        private var sharedConfig: Config? = null
        private var sharedCoordinator: PredictionCoordinator? = null
        private var sharedContractions: ContractionManager? = null
        @Volatile private var initAttempted = false
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        contextTracker = PredictionContextTracker()

        synchronized(SmartAutoSpaceTest::class.java) {
            if (!initAttempted) {
                initAttempted = true
                try {
                    sharedConfig = Config.globalConfig()
                    sharedContractions = ContractionManager(context).also { it.loadMappings() }
                    sharedPredictor = WordPredictor().apply {
                        setContext(context)
                        setConfig(sharedConfig!!)
                        loadDictionary(context, "en")
                    }
                    sharedCoordinator = PredictionCoordinator(context, sharedConfig!!)
                    PredictionCoordinator::class.java.getDeclaredField("wordPredictor").apply {
                        isAccessible = true
                        set(sharedCoordinator, sharedPredictor)
                    }
                } catch (e: OutOfMemoryError) {
                    sharedPredictor = null
                }
            }
        }
        assumeNotNull("WordPredictor required", sharedPredictor)

        // Pin every pref this feature interacts with to a deterministic state.
        sharedConfig!!.word_prediction_enabled = true
        sharedConfig!!.autocorrect_enabled = false
        sharedConfig!!.swipe_final_autocorrect_enabled = false
        sharedConfig!!.auto_space_before_suggestion = true
        sharedConfig!!.auto_space_after_suggestion = true
        sharedConfig!!.smart_punctuation = true
        sharedConfig!!.double_space_to_period = false
        sharedConfig!!.backspace_undo_swipe = false
        sharedConfig!!.backspace_undo_autocorrect = false
        sharedConfig!!.termux_mode_enabled = false

        // Receiver mirrors KeyEventReceiverBridge: auto-space pending state
        // lives on the shared PredictionContextTracker.
        val receiver = object : KeyEventHandler.IReceiver {
            override fun handle_event_key(ev: KeyValue.Event) {}
            override fun set_shift_state(state: Boolean, lock: Boolean) {}
            override fun set_compose_pending(pending: Boolean) {}
            override fun selection_state_changed(selectionIsOngoing: Boolean) {}
            override fun getCurrentInputConnection(): InputConnection? = inputConnection
            override fun getHandler(): Handler = Handler(Looper.getMainLooper())
            override fun handle_text_typed(text: String) {}
            override fun wasLastSpaceAutoInserted(): Boolean =
                contextTracker.lastSpaceWasAutoInserted
            override fun setLastSpaceAutoInserted(value: Boolean) {
                if (value) {
                    contextTracker.lastSpaceWasAutoInserted = true
                } else {
                    contextTracker.invalidateAutoSpacePending()
                }
            }
            override fun getAutoSpaceStampedPosition(): Int =
                contextTracker.autoSpaceStampedPosition
            override fun markAutoSpacePending(expectedCursorPosition: Int) =
                contextTracker.markAutoSpacePending(expectedCursorPosition)
        }
        keyEventHandler = KeyEventHandler(receiver)
        suggestionHandler = SuggestionHandler(
            context, sharedConfig!!, contextTracker,
            sharedCoordinator!!, sharedContractions!!, keyEventHandler
        )

        val latch = CountDownLatch(1)
        val editHolder = arrayOfNulls<EditText>(1)
        val barHolder = arrayOfNulls<SuggestionBar>(1)
        Handler(Looper.getMainLooper()).post {
            barHolder[0] = SuggestionBar(context)
            editHolder[0] = EditText(context)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        suggestionHandler.setSuggestionBar(barHolder[0]!!)
        editText = editHolder[0]!!
        inputConnection = TestInputConnection(editText)
    }

    /** Production swipe auto-insert path: wasLastInputSwipe → onSuggestionSelected. */
    private fun swipe(word: String) {
        contextTracker.setWasLastInputSwipe(true)
        suggestionHandler.onSuggestionSelected(
            word, inputConnection, plainEditorInfo, context.resources, isManualSelection = false
        )
    }

    /** Production suggestion-tap path (shares the same auto-space mechanism). */
    private fun tap(word: String) {
        suggestionHandler.onSuggestionSelected(
            word, inputConnection, plainEditorInfo, context.resources, isManualSelection = true
        )
    }

    /** Production key path for typed characters: key_up → sendText. */
    private fun press(c: Char) {
        keyEventHandler.key_up(KeyValue.makeCharKey(c), Pointers.Modifiers.EMPTY, false)
    }

    private fun pressBackspace() {
        keyEventHandler.key_up(
            KeyValue.getKeyByName("backspace"), Pointers.Modifiers.EMPTY, false
        )
    }

    private fun editorText() = inputConnection.editableText()

    // ── Feature A: no leading auto-space after opening punctuation ──────────

    @Test
    fun openParen_swipe_noLeadingSpace() {
        inputConnection.commitText("(", 1)
        swipe("word")
        assertEquals("(word ", editorText())
    }

    @Test
    fun openBracketBrace_swipe_noLeadingSpace() {
        inputConnection.commitText("[", 1)
        swipe("word")
        assertEquals("[word ", editorText())
    }

    @Test
    fun straightDoubleQuote_atFieldStart_swipe_noLeadingSpace() {
        inputConnection.commitText("\"", 1)
        swipe("word")
        assertEquals("\"word ", editorText())
    }

    @Test
    fun straightDoubleQuote_afterSpace_swipe_noLeadingSpace() {
        inputConnection.commitText("He said \"", 1)
        swipe("word")
        assertEquals("He said \"word ", editorText())
    }

    @Test
    fun curlyOpeningSingleQuote_swipe_noLeadingSpace() {
        inputConnection.commitText("‘", 1)
        swipe("word")
        assertEquals("‘word ", editorText())
    }

    @Test
    fun curlyOpeningDoubleQuote_swipe_noLeadingSpace() {
        inputConnection.commitText("she wrote “", 1)
        swipe("word")
        assertEquals("she wrote “word ", editorText())
    }

    @Test
    fun invertedQuestionAndExclamation_swipe_noLeadingSpace() {
        inputConnection.commitText("¿", 1)
        swipe("qué")
        assertEquals("¿qué ", editorText())
    }

    @Test
    fun apostropheAfterLetter_possessive_swipe_keepsLeadingSpace() {
        // `'` right after a letter is a possessive/closing quote, NOT an opener
        inputConnection.commitText("kids'", 1)
        swipe("toys")
        assertEquals("kids' toys ", editorText())
    }

    @Test
    fun openParen_tapPath_noLeadingSpace() {
        // Suggestion taps share the same auto-space mechanism as swipes
        inputConnection.commitText("(", 1)
        tap("word")
        assertEquals("(word ", editorText())
    }

    // ── Feature B: closing punctuation swallows the AUTOMATIC space ─────────

    @Test
    fun period_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        assertEquals("word ", editorText())
        press('.')
        // Sentence-ending punctuation re-adds a trailing space (v1.2.8 autocap)
        assertEquals("word. ", editorText())
    }

    @Test
    fun closeParen_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        press(')')
        assertEquals("word)", editorText())
    }

    @Test
    fun comma_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        press(',')
        assertEquals("word,", editorText())
    }

    @Test
    fun curlyClosingQuote_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        press('”')
        assertEquals("word”", editorText())
    }

    @Test
    fun ellipsis_afterSwipe_swallowsAutoSpace() {
        swipe("word")
        press('…')
        assertEquals("word…", editorText())
    }

    @Test
    fun apostrophe_afterSwipeAutoSpace_swallowsForPossessive() {
        // swipe "kids" → `kids `; typing ' must yield `kids'` (→ kids's/kids')
        swipe("kids")
        press('\'')
        assertEquals("kids'", editorText())
    }

    @Test
    fun straightDoubleQuote_withUnmatchedOpenQuote_swallowsAutoSpace() {
        // One prior `"` → parity says this is a CLOSING quote → swallow
        inputConnection.commitText("\"", 1)
        swipe("word")
        assertEquals("\"word ", editorText())
        press('"')
        assertEquals("\"word\"", editorText())
    }

    @Test
    fun straightDoubleQuote_noOpenQuote_isOpening_keepsAutoSpace() {
        // No prior `"` → parity says OPENING quote → the space must stay
        swipe("said")
        press('"')
        assertEquals("said \"", editorText())
    }

    @Test
    fun period_afterTapCommit_swallowsAutoSpace() {
        // Taps share the mechanism: type partial, tap suggestion, then punctuation
        inputConnection.commitText("hel", 1)
        contextTracker.appendToCurrentWord("hel")
        tap("hello")
        assertEquals("hello ", editorText())
        press('.')
        assertEquals("hello. ", editorText())
    }

    // ── Manual space must NEVER be eaten ─────────────────────────────────────

    @Test
    fun manuallyTypedSpace_period_keepsSpace() {
        for (c in "word") press(c)
        press(' ')
        press('.')
        assertEquals("word .", editorText())
    }

    @Test
    fun manualSpaceAfterSwipe_period_keepsManualSpace() {
        // swipe → `word `, user types their own space, then `.`:
        // the typed space invalidated the pending state, nothing is swallowed
        swipe("word")
        press(' ')
        press('.')
        assertEquals("word  .", editorText())
    }

    // ── Invalidation ─────────────────────────────────────────────────────────

    @Test
    fun cursorMovedAwayFromStamp_period_keepsSpace() {
        // "one " typed manually, "two " auto-committed at the end, then the
        // cursor jumps back to just after the manual space at position 4.
        // Prev char IS a space but the position stamp no longer matches —
        // that manual space must not be swallowed.
        inputConnection.commitText("one ", 1)
        swipe("two")
        assertEquals("one two ", editorText())
        inputConnection.setCursor(4)
        press('.')
        assertEquals("one .two ", editorText())
    }

    @Test
    fun cursorMoveInvalidation_viaTrackerHook() {
        // InputCoordinator.onCursorMoved feeds onCursorPositionChanged: the
        // commit's own selection callback (== stamp) keeps the pending state,
        // any other position kills it.
        swipe("word")
        assertTrue(contextTracker.lastSpaceWasAutoInserted)
        val stamp = contextTracker.autoSpaceStampedPosition
        assertEquals(5, stamp)
        contextTracker.onCursorPositionChanged(stamp) // commit's own update
        assertTrue(contextTracker.lastSpaceWasAutoInserted)
        contextTracker.onCursorPositionChanged(2)     // user moved the cursor
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
        press('.')
        assertEquals("word .", editorText())
    }

    @Test
    fun backspace_invalidatesPendingAutoSpace() {
        swipe("word")
        assertTrue(contextTracker.lastSpaceWasAutoInserted)
        pressBackspace()
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
    }

    @Test
    fun fieldSwitch_clearAll_invalidatesPendingAutoSpace() {
        // onFinishInputView → contextTracker.clearAll()
        swipe("word")
        assertTrue(contextTracker.lastSpaceWasAutoInserted)
        contextTracker.clearAll()
        assertFalse(contextTracker.lastSpaceWasAutoInserted)
        assertEquals(-1, contextTracker.autoSpaceStampedPosition)
        press('.')
        assertEquals("word .", editorText())
    }

    @Test
    fun secondPunctuation_chainsWithExactlyOneDeletionEach() {
        // `.` swallows the swipe's auto-space and re-adds one (stamped again);
        // `)` swallows only that re-added space. Never a double deletion.
        swipe("word")
        press('.')
        assertEquals("word. ", editorText())
        press(')')
        assertEquals("word.)", editorText())
    }

    @Test
    fun otherCharInput_invalidates_soLaterPunctuationKeepsManualSpace() {
        // swipe → letter typed (invalidates) → manual space → period keeps it
        swipe("word")
        press('s')
        press(' ')
        press('.')
        assertEquals("word s .", editorText())
    }

    // ── Plain regression: normal word-space-word flow unchanged ─────────────

    @Test
    fun consecutiveSwipes_singleSpaceBetweenWords() {
        swipe("hello")
        swipe("world")
        assertEquals("hello world ", editorText())
    }

    @Test
    fun typedPartial_tapReplacement_unchanged() {
        // Issue #151-adjacent control: tap replaces the typed partial
        inputConnection.commitText("hel", 1)
        contextTracker.appendToCurrentWord("hel")
        tap("hello")
        assertEquals("hello ", editorText())
    }
}
