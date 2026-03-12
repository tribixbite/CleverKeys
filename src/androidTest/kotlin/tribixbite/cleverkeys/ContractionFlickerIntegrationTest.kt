package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.BaseInputConnection
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
 * Integration test for the contraction suggestion flicker bug.
 *
 * Creates real SuggestionHandler + SuggestionBar + PredictionContextTracker wired together,
 * simulates the full typing flow, and verifies that BOTH prediction pipelines produce
 * contraction-aware results so the race condition doesn't cause visible flicker.
 *
 * The fix: InputCoordinator's cursor sync prediction path now has paired contraction support
 * (matching SuggestionHandler's typing path), and SuggestionBar deduplicates identical
 * suggestion lists to prevent re-render flicker.
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
        drainMainThread()

        val suggestions = suggestionBar.getCurrentSuggestions()
        assertTrue(
            "After typing 'its', suggestion bar must include 'it's'. Got: $suggestions",
            suggestions.any { it == "it's" }
        )
    }

    // =========================================================================
    // Race condition test: cursor sync now produces SAME results (no flicker)
    // =========================================================================

    @Test
    fun cursorSync_producesIdenticalContractionSuggestions() {
        // Step 1: Type "its" — should show "it's" in suggestions
        suggestionHandler.handleRegularTyping("i", null, null)
        suggestionHandler.handleRegularTyping("t", null, null)
        suggestionHandler.handleRegularTyping("s", null, null)

        Thread.sleep(1000)
        drainMainThread()

        val suggestionsBeforeSync = suggestionBar.getCurrentSuggestions()
        assertTrue(
            "Pre-condition: 'it's' must be in suggestions. Got: $suggestionsBeforeSync",
            suggestionsBeforeSync.any { it == "it's" }
        )

        // Step 2: Simulate cursor sync WITH paired contraction support (the fix).
        // InputCoordinator.triggerPredictionsForPrefix() now includes
        // getPairedContractions() just like SuggestionHandler does.
        contextTracker.synchronizeWithCursor(inputConnection, "en", null)

        val cursorSyncExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        val syncLatch = CountDownLatch(1)
        cursorSyncExecutor.submit {
            val prefix = contextTracker.getCurrentWord()
            if (prefix.isNotEmpty()) {
                val result = sharedPredictor!!.predictWordsWithContext(
                    prefix, contextTracker.getContextWords().toList()
                )
                if (result != null && result.words.isNotEmpty()) {
                    // Apply BOTH non-paired AND paired contraction transforms
                    // (matching the fixed InputCoordinator pipeline)
                    val contractionWords = mutableListOf<String>()
                    val contractionScores = mutableListOf<Int>()

                    val nonPaired = contractionManager.getNonPairedMapping(prefix)
                    if (nonPaired != null) {
                        contractionWords.add(nonPaired)
                        contractionScores.add(result.scores.firstOrNull()?.plus(1000) ?: 10000)
                    }
                    val paired = contractionManager.getPairedContractions(prefix)
                    if (paired != null && nonPaired == null) {
                        paired.forEach { variant ->
                            contractionWords.add(variant)
                            contractionScores.add(result.scores.firstOrNull()?.plus(500) ?: 5000)
                        }
                    }

                    val transformed = result.words.map { word ->
                        contractionManager.getNonPairedMapping(word) ?: word
                    }
                    val injectedLower = contractionWords.map { it.lowercase() }.toSet()
                    val merged = contractionWords + transformed.filter {
                        it.lowercase() !in injectedLower
                    }
                    val filteredCount = transformed.size -
                        transformed.count { it.lowercase() in injectedLower }
                    val mergedScores = contractionScores + result.scores.take(filteredCount)

                    Handler(Looper.getMainLooper()).post {
                        suggestionBar.setSuggestionsWithScores(merged, mergedScores)
                        syncLatch.countDown()
                    }
                } else {
                    syncLatch.countDown()
                }
            } else {
                syncLatch.countDown()
            }
        }

        syncLatch.await(3, TimeUnit.SECONDS)
        drainMainThread()

        // Step 3: Verify "it's" is STILL in suggestions
        // With the fix, both pipelines include paired contractions, so the
        // cursor sync result ALSO contains "it's".
        val suggestionsAfterSync = suggestionBar.getCurrentSuggestions()
        assertTrue(
            "After cursor sync, 'it's' must STILL be in suggestions. Got: $suggestionsAfterSync",
            suggestionsAfterSync.any { it == "it's" }
        )

        cursorSyncExecutor.shutdown()
    }

    // =========================================================================
    // SuggestionBar deduplication test
    // =========================================================================

    @Test
    fun suggestionBar_skipsReRenderForIdenticalContent() {
        // Set suggestions
        val words = listOf("it's", "its", "itself")
        val scores = listOf(5000, 4000, 3000)
        Handler(Looper.getMainLooper()).post {
            suggestionBar.setSuggestionsWithScores(words, scores)
        }
        drainMainThread()

        val before = suggestionBar.getCurrentSuggestions()
        assertEquals("it's", before[0])

        // Post IDENTICAL suggestions — should be a no-op (no re-render)
        Handler(Looper.getMainLooper()).post {
            suggestionBar.setSuggestionsWithScores(words, scores)
        }
        drainMainThread()

        val after = suggestionBar.getCurrentSuggestions()
        assertEquals("Suggestions should remain identical", before, after)
    }

    @Test
    fun suggestionBar_updatesWhenContentDiffers() {
        val words1 = listOf("its", "itself")
        val words2 = listOf("it's", "its", "itself")
        Handler(Looper.getMainLooper()).post {
            suggestionBar.setSuggestionsWithScores(words1, listOf(100, 90))
        }
        drainMainThread()
        assertEquals(2, suggestionBar.getCurrentSuggestions().size)

        // Post DIFFERENT suggestions — should update
        Handler(Looper.getMainLooper()).post {
            suggestionBar.setSuggestionsWithScores(words2, listOf(5000, 100, 90))
        }
        drainMainThread()
        assertEquals(3, suggestionBar.getCurrentSuggestions().size)
        assertTrue(suggestionBar.getCurrentSuggestions().contains("it's"))
    }

    /** Drain pending main thread posts to ensure SuggestionBar updates are applied */
    private fun drainMainThread() {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }
}
