package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * State-machine integration tests for the suggestion pipeline.
 *
 * These exercise multi-step flows that pure JVM tests can't reach because
 * they require BaseInputConnection + a real Looper. Coverage focuses on
 * end-state of contextTracker / suggestionBar / dictionary after a sequence
 * of operations:
 *
 *   - typing → backspace → state correct
 *   - tap suggestion → context committed, currentWord cleared
 *   - tap exact_add: prefix → user dictionary updated
 *   - tap raw: prefix → "raw:" stripped, autocorrect skipped
 *   - tap "i" / "i'm" → capitalized to "I" / "I'm"
 *   - manual selection → autocorrect skipped (issue #63)
 *   - clearAll → state reset
 *
 * Patterns mirror ContractionFlickerIntegrationTest:
 *   - Companion-object cached WordPredictor (heavy init OOM-resilient)
 *   - assumeNotNull guard so OOM skips rather than fails
 *   - drainMainThread() helper for async result delivery
 */
@RunWith(AndroidJUnit4::class)
class SuggestionStateMachineIntegrationTest {

    private lateinit var context: Context
    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var contractionManager: ContractionManager
    private lateinit var suggestionBar: SuggestionBar
    private lateinit var suggestionHandler: SuggestionHandler
    private lateinit var inputConnection: BaseInputConnection
    private lateinit var editText: EditText
    private val editorInfo = EditorInfo().apply { packageName = "com.example" }

    companion object {
        private var sharedPredictor: WordPredictor? = null
        private var sharedContractionManager: ContractionManager? = null
        private var sharedConfig: Config? = null
        private var sharedPredictionCoordinator: PredictionCoordinator? = null
        @Volatile private var initAttempted = false
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        contextTracker = PredictionContextTracker()

        synchronized(SuggestionStateMachineIntegrationTest::class.java) {
            if (!initAttempted) {
                initAttempted = true
                try {
                    sharedConfig = Config.globalConfig().apply {
                        autocorrect_enabled = true
                        word_prediction_enabled = true
                        autocapitalize_i_words = true
                    }

                    sharedContractionManager = ContractionManager(context).also { it.loadMappings() }

                    sharedPredictor = WordPredictor().also {
                        it.setContext(context)
                        it.setConfig(sharedConfig!!)
                        it.loadDictionary(context, "en")
                    }

                    sharedPredictionCoordinator = PredictionCoordinator(context, sharedConfig!!)

                    // Inject loaded predictor + a real DictionaryManager via reflection
                    // so the coordinator's lazy init path doesn't fire (which would
                    // OOM the process re-loading dicts).
                    PredictionCoordinator::class.java.getDeclaredField("wordPredictor").apply {
                        isAccessible = true
                        set(sharedPredictionCoordinator, sharedPredictor)
                    }
                    val dictMgr = DictionaryManager(context).apply { setLanguage("en") }
                    PredictionCoordinator::class.java.getDeclaredField("dictionaryManager").apply {
                        isAccessible = true
                        set(sharedPredictionCoordinator, dictMgr)
                    }
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("SuggStateMachineTest", "WordPredictor init OOM — tests will be skipped")
                    sharedPredictor = null
                } catch (e: Exception) {
                    android.util.Log.w("SuggStateMachineTest", "Setup failed: ${e.message}")
                    sharedPredictor = null
                }
            }
        }

        assumeNotNull("WordPredictor required", sharedPredictor)
        contractionManager = sharedContractionManager!!

        // SuggestionBar (View) + EditText (View) created on the main thread.
        val viewLatch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            suggestionBar = SuggestionBar(context)
            editText = EditText(context).apply { setText("") }
            viewLatch.countDown()
        }
        viewLatch.await(5, TimeUnit.SECONDS)

        inputConnection = BaseInputConnection(editText, true)

        // Stub IReceiver — we only call IC-side ops below; receiver is unused
        // by onSuggestionSelected once we pass an IC explicitly.
        val stubReceiver = object : KeyEventHandler.IReceiver {
            override fun handle_event_key(ev: KeyValue.Event) {}
            override fun set_shift_state(state: Boolean, lock: Boolean) {}
            override fun set_compose_pending(pending: Boolean) {}
            override fun selection_state_changed(selectionIsOngoing: Boolean) {}
            override fun getCurrentInputConnection(): InputConnection? = inputConnection
            override fun getHandler(): Handler = Handler(Looper.getMainLooper())
            override fun handle_text_typed(text: String) {}
        }
        val keyEventHandler = KeyEventHandler(stubReceiver)

        suggestionHandler = SuggestionHandler(
            context, sharedConfig!!, contextTracker,
            sharedPredictionCoordinator!!, contractionManager, keyEventHandler
        )
        suggestionHandler.setSuggestionBar(suggestionBar)

        // Reset shared state — these objects are reused across tests.
        contextTracker.clearAll()
        sharedConfig!!.show_exact_typed_word = false
    }

    // =========================================================================
    // Typing → Backspace state transitions
    // =========================================================================

    @Test
    fun typing_thenBackspaceToEmpty_clearsSuggestions() {
        // Type "abc" → currentWord = "abc"
        suggestionHandler.handleRegularTyping("a", inputConnection, editorInfo)
        suggestionHandler.handleRegularTyping("b", inputConnection, editorInfo)
        suggestionHandler.handleRegularTyping("c", inputConnection, editorInfo)
        Thread.sleep(800)
        drainMainThread()

        assertEquals("Pre-condition: 3-char word", 3, contextTracker.getCurrentWordLength())

        // Backspace 3× → empty
        suggestionHandler.handleBackspace()
        suggestionHandler.handleBackspace()
        suggestionHandler.handleBackspace()
        drainMainThread()

        assertEquals("currentWord empty after 3 backspaces", 0, contextTracker.getCurrentWordLength())
        assertTrue(
            "SuggestionBar must have no suggestions after empty currentWord. Got: ${suggestionBar.getCurrentSuggestions()}",
            suggestionBar.getCurrentSuggestions().isEmpty()
        )
    }

    @Test
    fun typing_thenBackspaceOneChar_updatesPredictionsForShorterPrefix() {
        // Type "thi" → predictions for "thi"
        suggestionHandler.handleRegularTyping("t", inputConnection, editorInfo)
        suggestionHandler.handleRegularTyping("h", inputConnection, editorInfo)
        suggestionHandler.handleRegularTyping("i", inputConnection, editorInfo)
        Thread.sleep(800)
        drainMainThread()
        val before = suggestionBar.getCurrentSuggestions()

        // Backspace once → currentWord becomes "th", predictions update
        suggestionHandler.handleBackspace()
        Thread.sleep(800)
        drainMainThread()

        assertEquals("currentWord = 'th'", "th", contextTracker.getCurrentWord())
        val after = suggestionBar.getCurrentSuggestions()
        // Result set should differ because prefix differs (this is a regression
        // for "stale suggestions after backspace" bugs).
        assertNotEquals(
            "Suggestions for 'thi' and 'th' must differ (before=$before, after=$after)",
            before, after
        )
    }

    // =========================================================================
    // Suggestion selection state transitions
    // =========================================================================

    @Test
    fun selectSuggestion_commitsWordAndClearsCurrentWord() {
        suggestionHandler.handleRegularTyping("h", inputConnection, editorInfo)
        suggestionHandler.handleRegularTyping("e", inputConnection, editorInfo)
        Thread.sleep(500)
        drainMainThread()
        assertTrue(contextTracker.getCurrentWordLength() > 0)

        suggestionHandler.onSuggestionSelected("hello", inputConnection, editorInfo, context.resources)
        drainMainThread()

        assertEquals(
            "currentWord must be empty after onSuggestionSelected",
            0, contextTracker.getCurrentWordLength()
        )
        assertTrue(
            "Context must contain the selected word",
            contextTracker.getContextWords().any { it.equals("hello", ignoreCase = true) }
        )
    }

    @Test
    fun selectSuggestion_emptyOrNull_isNoOp() {
        // Defensive: null/empty/blank must not throw.
        suggestionHandler.onSuggestionSelected(null, inputConnection, editorInfo, context.resources)
        suggestionHandler.onSuggestionSelected("", inputConnection, editorInfo, context.resources)
        suggestionHandler.onSuggestionSelected("   ", inputConnection, editorInfo, context.resources)

        // No assertion needed — if this didn't throw, the no-op contract holds.
        // Verify state unchanged from setUp (clearAll'd in @Before).
        assertEquals(0, contextTracker.getCurrentWordLength())
        assertTrue(
            "No words should have been committed to context",
            contextTracker.getContextWords().isEmpty()
        )
    }

    // =========================================================================
    // Capitalize-I (issue #72) state transitions
    // =========================================================================

    @Test
    fun selectIWord_capitalizesLowerCaseI() {
        // BaseInputConnection holds its OWN Editable — NOT the target view's
        // editableText. So commitText writes to BaseInputConnection.editable,
        // not editText.text. Read back via the IC's editable for truth.
        val (_, freshIc) = freshEditorAndIc()
        runOnMain {
            suggestionHandler.onSuggestionSelected("i", freshIc, editorInfo, context.resources)
        }
        drainMainThread()

        val committed = freshIc.editable.toString()
        assertTrue(
            "Lower-case 'i' must be capitalized to 'I' when committed. Got: \"$committed\"",
            committed.contains("I")
        )
        assertFalse(
            "Standalone lowercase 'i' must not appear in committed text. Got: \"$committed\"",
            committed.matches(Regex(".*\\bi\\b.*"))
        )
    }

    @Test
    fun selectIM_capitalizesAsImWithApostrophe() {
        val (_, freshIc) = freshEditorAndIc()
        runOnMain {
            suggestionHandler.onSuggestionSelected("i'm", freshIc, editorInfo, context.resources)
        }
        drainMainThread()

        val committed = freshIc.editable.toString()
        assertTrue(
            "\"i'm\" must commit as \"I'm\" via InputConnection. Got: \"$committed\"",
            committed.contains("I'm")
        )
    }

    // =========================================================================
    // Special-prefix selection (raw:, dict_add:, exact_add:)
    // =========================================================================

    @Test
    fun selectRawPrefixedWord_stripsRawPrefix() {
        // "raw:hello" must commit as "hello", not "raw:hello".
        suggestionHandler.onSuggestionSelected("raw:hello", inputConnection, editorInfo, context.resources)
        drainMainThread()

        val ctx = contextTracker.getContextWords()
        assertFalse(
            "Context must not contain 'raw:' prefix. Got: $ctx",
            ctx.any { it.startsWith("raw:") }
        )
        assertTrue(
            "Context should contain 'hello' (raw prefix stripped). Got: $ctx",
            ctx.any { it.equals("hello", ignoreCase = true) }
        )
    }

    @Test
    fun selectExactAddPrefix_addsToUserDictionary() {
        val novel = "qwzqwz" + System.currentTimeMillis().toString().takeLast(4)

        // Pre-condition: word is NOT in dictionary
        assertFalse(
            "Pre-condition: novel word must not be in dictionary",
            sharedPredictor!!.isInDictionary(novel)
        )

        sharedConfig!!.show_exact_typed_word = true
        // Type the novel word so handleExactWordAdd has a currentWord to delete.
        novel.forEach { suggestionHandler.handleRegularTyping(it.toString(), inputConnection, editorInfo) }
        Thread.sleep(500)
        drainMainThread()

        suggestionHandler.onSuggestionSelected(
            "exact_add:$novel", inputConnection, editorInfo, context.resources
        )
        drainMainThread()
        Thread.sleep(300) // refreshCustomWords is async on some paths

        // Post-condition: word now in user dictionary
        assertTrue(
            "exact_add: must add the word to the user dictionary. word=$novel",
            sharedPredictor!!.isInDictionary(novel)
        )
        // currentWord must be cleared
        assertEquals(0, contextTracker.getCurrentWordLength())
    }

    // =========================================================================
    // Manual selection (#63) and known contraction skip-autocorrect
    // =========================================================================

    @Test
    fun manualSelection_doesNotMutateWord() {
        // With isManualSelection=true, autocorrect must NOT silently change the
        // user's chosen word. Pick a real dictionary word so autocorrect could
        // theoretically nudge it but won't.
        suggestionHandler.onSuggestionSelected(
            "hello", inputConnection, editorInfo, context.resources, isManualSelection = true
        )
        drainMainThread()

        val ctx = contextTracker.getContextWords()
        assertTrue(
            "Manual selection of 'hello' must commit exactly 'hello'. Got: $ctx",
            ctx.any { it.equals("hello", ignoreCase = true) }
        )
    }

    @Test
    fun selectKnownContraction_preservesApostrophe() {
        // "it's" is a known paired contraction — must commit with apostrophe intact.
        suggestionHandler.onSuggestionSelected("it's", inputConnection, editorInfo, context.resources)
        drainMainThread()

        val ctx = contextTracker.getContextWords()
        assertTrue(
            "Known contraction 'it's' must commit with apostrophe. Got: $ctx",
            ctx.any { it == "it's" }
        )
    }

    // =========================================================================
    // Multi-word context state transition
    // =========================================================================

    @Test
    fun consecutiveSelections_keepMostRecentInWindow() {
        // PredictionContextTracker caps at MAX_CONTEXT_WORDS=2 for bigram
        // predictions — the OLDEST entry must be evicted, the newest preserved.
        suggestionHandler.onSuggestionSelected("hello", inputConnection, editorInfo, context.resources)
        suggestionHandler.onSuggestionSelected("there", inputConnection, editorInfo, context.resources)
        suggestionHandler.onSuggestionSelected("friend", inputConnection, editorInfo, context.resources)
        drainMainThread()

        val ctx = contextTracker.getContextWords().toList()
        assertEquals(
            "Context window must enforce MAX_CONTEXT_WORDS=2. Got: $ctx",
            2, ctx.size
        )
        assertEquals("Newest word must be last (FIFO eviction)", "friend", ctx.last())
        assertFalse(
            "Oldest word 'hello' must have been evicted. Got: $ctx",
            ctx.contains("hello")
        )
    }

    // =========================================================================
    // clearAll resets state
    // =========================================================================

    @Test
    fun clearAll_resetsContextAndCurrentWord() {
        suggestionHandler.handleRegularTyping("h", inputConnection, editorInfo)
        suggestionHandler.handleRegularTyping("i", inputConnection, editorInfo)
        suggestionHandler.onSuggestionSelected("hello", inputConnection, editorInfo, context.resources)
        drainMainThread()

        assertTrue(contextTracker.getContextWords().isNotEmpty())

        contextTracker.clearAll()

        assertEquals("currentWord cleared", 0, contextTracker.getCurrentWordLength())
        assertTrue("Context cleared", contextTracker.getContextWords().isEmpty())
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private fun drainMainThread() {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun clearEditText() {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            editText.setText("")
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun runOnMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    /** Build a fresh EditText + BaseInputConnection on the main thread. */
    private fun freshEditorAndIc(): Pair<EditText, BaseInputConnection> {
        val editHolder = arrayOfNulls<EditText>(1)
        val icHolder = arrayOfNulls<BaseInputConnection>(1)
        runOnMain {
            val ed = EditText(context).apply { setText("") }
            editHolder[0] = ed
            icHolder[0] = BaseInputConnection(ed, true)
        }
        return Pair(editHolder[0]!!, icHolder[0]!!)
    }
}
