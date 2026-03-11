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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * True integration test for the contraction suggestion flicker bug.
 *
 * Creates real SuggestionHandler + SuggestionBar + PredictionContextTracker wired together,
 * simulates the full typing flow, and reproduces the cursor sync race condition that causes
 * paired contractions to appear then disappear.
 *
 * This test catches the actual bug: InputCoordinator's cursor sync path uses a separate
 * executor to run predictions WITHOUT paired contraction support, overwriting the correct
 * results from SuggestionHandler's typing path.
 *
 * Without the fix (expectingSelectionUpdate=true in SuggestionHandler), the cursor sync
 * fires after each keystroke's commitText triggers onUpdateSelection, and the paired
 * contractions vanish from the suggestion bar.
 */
@RunWith(AndroidJUnit4::class)
class ContractionFlickerIntegrationTest {

    private lateinit var context: Context
    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var contractionManager: ContractionManager
    private lateinit var suggestionBar: SuggestionBar
    private lateinit var suggestionHandler: SuggestionHandler
    private lateinit var inputConnection: BaseInputConnection

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

        // One-time heavy initialization
        synchronized(ContractionFlickerIntegrationTest::class.java) {
            if (!initAttempted) {
                initAttempted = true
                try {
                    sharedConfig = Config.globalConfig()
                    sharedConfig!!.autocorrect_enabled = true
                    sharedConfig!!.word_prediction_enabled = true

                    sharedContractionManager = ContractionManager(context)
                    sharedContractionManager!!.loadMappings()

                    sharedPredictor = WordPredictor()
                    sharedPredictor!!.setContext(context)
                    sharedPredictor!!.setConfig(sharedConfig!!)
                    sharedPredictor!!.loadDictionary(context, "en")

                    sharedPredictionCoordinator = PredictionCoordinator(context, sharedConfig!!)
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("ContractionFlickerIntTest",
                        "WordPredictor init OOM — tests will be skipped")
                    sharedPredictor = null
                }
            }
        }

        assumeNotNull("WordPredictor required", sharedPredictor)

        contractionManager = sharedContractionManager!!

        // Create SuggestionBar on main thread (it's a View)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            suggestionBar = SuggestionBar(context)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)

        // Create a stub IReceiver — SuggestionHandler only uses keyeventhandler.send_key_down_up()
        // which calls recv.getCurrentInputConnection()?.sendKeyEvent(), so null IC = no-op
        val stubReceiver = object : KeyEventHandler.IReceiver {
            override fun handle_event_key(ev: KeyValue.Event) {}
            override fun set_shift_state(state: Boolean, lock: Boolean) {}
            override fun set_compose_pending(pending: Boolean) {}
            override fun selection_state_changed(selectionIsOngoing: Boolean) {}
            override fun getCurrentInputConnection(): InputConnection? = null
            override fun getHandler(): Handler = Handler(Looper.getMainLooper())
            override fun handle_text_typed(text: String) {}
        }
        val keyEventHandler = KeyEventHandler(stubReceiver)

        // Wire up PredictionCoordinator with the shared WordPredictor via reflection
        // (no public setter for wordPredictor, but getWordPredictor() returns it)
        val predCoord = sharedPredictionCoordinator!!
        try {
            val wpField = PredictionCoordinator::class.java.getDeclaredField("wordPredictor")
            wpField.isAccessible = true
            wpField.set(predCoord, sharedPredictor)
        } catch (e: Exception) {
            android.util.Log.w("ContractionFlickerIntTest",
                "Could not inject WordPredictor via reflection: ${e.message}")
        }
        assumeNotNull("WordPredictor must be accessible", predCoord.getWordPredictor())

        // Create SuggestionHandler with all real dependencies
        suggestionHandler = SuggestionHandler(
            context, sharedConfig!!, contextTracker,
            predCoord, contractionManager, keyEventHandler
        )
        suggestionHandler.setSuggestionBar(suggestionBar)

        // Create InputConnection for cursor sync simulation
        val editText: EditText
        val editLatch = CountDownLatch(1)
        val holder = arrayOfNulls<EditText>(1)
        Handler(Looper.getMainLooper()).post {
            holder[0] = EditText(context).apply { setText("hello its") }
            editLatch.countDown()
        }
        editLatch.await(5, TimeUnit.SECONDS)
        editText = holder[0]!!
        inputConnection = BaseInputConnection(editText, true)
    }

    // =========================================================================
    // Core integration test: typing path shows contractions
    // =========================================================================

    @Test
    fun typingIts_suggestionBarShowsPairedContractions() {
        // Simulate typing "i", "t", "s" through the REAL SuggestionHandler
        suggestionHandler.handleRegularTyping("i", null, null)
        suggestionHandler.handleRegularTyping("t", null, null)
        suggestionHandler.handleRegularTyping("s", null, null)

        // Wait for async prediction to complete and post to SuggestionBar
        Thread.sleep(1000)

        // Run any pending main thread posts (predictions post via suggestionBar.post{})
        val checkLatch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { checkLatch.countDown() }
        checkLatch.await(2, TimeUnit.SECONDS)

        val suggestions = suggestionBar.getCurrentSuggestions()
        assertTrue(
            "After typing 'its', suggestion bar must include 'it's'. Got: $suggestions",
            suggestions.any { it == "it's" }
        )
    }

    // =========================================================================
    // Race condition test: cursor sync overwrites contractions
    // =========================================================================

    @Test
    fun cursorSync_doesNotOverwriteContractionSuggestions() {
        // Step 1: Type "its" — should show "it's" in suggestions
        suggestionHandler.handleRegularTyping("i", null, null)
        suggestionHandler.handleRegularTyping("t", null, null)
        suggestionHandler.handleRegularTyping("s", null, null)

        // Wait for typing predictions to complete
        Thread.sleep(1000)
        drainMainThread()

        val suggestionsBeforeSync = suggestionBar.getCurrentSuggestions()
        assertTrue(
            "Pre-condition: 'it's' must be in suggestions. Got: $suggestionsBeforeSync",
            suggestionsBeforeSync.any { it == "it's" }
        )

        // Step 2: Simulate cursor sync race condition
        // This is what InputCoordinator.onCursorMoved() does after onUpdateSelection:
        //   1. synchronizeWithCursor() — rebuilds currentWord from IC
        //   2. triggerPredictionsForPrefix() — runs predictions WITHOUT paired contractions
        //
        // If expectingSelectionUpdate=true (our fix), synchronizeWithCursor skips.
        // If not, it runs and the separate prediction overwrites suggestions.
        contextTracker.synchronizeWithCursor(inputConnection, "en", null)

        // Simulate InputCoordinator's prediction path (NO paired contractions)
        val cursorSyncExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        val syncLatch = CountDownLatch(1)
        cursorSyncExecutor.submit {
            val prefix = contextTracker.getCurrentWord()
            if (prefix.isNotEmpty()) {
                val result = sharedPredictor!!.predictWordsWithContext(
                    prefix, contextTracker.getContextWords().toList()
                )
                if (result != null && result.words.isNotEmpty()) {
                    // Apply ONLY non-paired contraction transform (matching InputCoordinator)
                    val transformed = result.words.map { word ->
                        contractionManager.getNonPairedMapping(word) ?: word
                    }
                    // Post to SuggestionBar on main thread
                    suggestionBar.post {
                        suggestionBar.setSuggestionsWithScores(transformed, result.scores)
                        syncLatch.countDown()
                    }
                } else {
                    syncLatch.countDown()
                }
            } else {
                // If prefix is empty (sync cleared it), the sync overwrites
                suggestionBar.post {
                    suggestionBar.clearSuggestions()
                    syncLatch.countDown()
                }
            }
        }

        // Wait for cursor sync prediction to complete
        syncLatch.await(3, TimeUnit.SECONDS)
        drainMainThread()

        // Step 3: Verify suggestions STILL contain "it's"
        // Without the fix, synchronizeWithCursor runs, cursor sync prediction
        // posts results WITHOUT "it's", and paired contractions disappear.
        val suggestionsAfterSync = suggestionBar.getCurrentSuggestions()
        assertTrue(
            "After cursor sync, 'it's' must STILL be in suggestions. Got: $suggestionsAfterSync",
            suggestionsAfterSync.any { it == "it's" }
        )

        cursorSyncExecutor.shutdown()
    }

    // =========================================================================
    // Verifies the flag lifecycle during the race
    // =========================================================================

    @Test
    fun handleRegularTyping_setsExpectingSelectionUpdate() {
        // The fix: handleRegularTyping must set the flag BEFORE the typing prediction runs
        assertFalse("Flag starts false", contextTracker.expectingSelectionUpdate)

        suggestionHandler.handleRegularTyping("a", null, null)

        assertTrue(
            "After handleRegularTyping, expectingSelectionUpdate must be true",
            contextTracker.expectingSelectionUpdate
        )
    }

    @Test
    fun handleBackspace_setsExpectingSelectionUpdate() {
        // Type a word first so backspace has something to delete
        contextTracker.appendToCurrentWord("a")
        contextTracker.appendToCurrentWord("b")
        assertFalse("Flag starts false", contextTracker.expectingSelectionUpdate)

        suggestionHandler.handleBackspace()

        assertTrue(
            "After handleBackspace, expectingSelectionUpdate must be true",
            contextTracker.expectingSelectionUpdate
        )
    }

    /** Drain pending main thread posts to ensure SuggestionBar updates are applied */
    private fun drainMainThread() {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }
}
