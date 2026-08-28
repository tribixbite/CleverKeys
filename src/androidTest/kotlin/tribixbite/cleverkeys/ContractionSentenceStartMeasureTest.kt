package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * UT-7 MEASUREMENT (ARC-013) — where do the I-contractions rank in the USER-VISIBLE
 * suggestion bar, and does the literal lead them?
 *
 * The v1.5.0 UT round deferred "I'd under-ranked at sentence start" and it was never
 * re-measured. Two verified facts frame this measurement:
 *
 *  1. **No sentence-start ranking signal exists anywhere** (zero `sentenceStart|afterPeriod`
 *     hits in main; `capitalizeIWord` fixes casing only, after ranking) — so today's bar
 *     ordering is position-independent by construction, and one measurement covers both
 *     "sentence start" and "mid-sentence".
 *  2. The apostrophe surface is NOT produced by [WordPredictor] (first revision of this test
 *     asserted at that layer and learned otherwise): `im`/`id`/`ill` are real dictionary
 *     words, so the alias-skip guard excludes their alias keys from the prefix index, and
 *     contraction surfacing happens in [SuggestionHandler]'s injection/REPLACE layer. This
 *     test therefore drives the REAL SuggestionHandler + SuggestionBar wiring (same
 *     construction as [ContractionFlickerIntegrationTest], where typed `its` provably
 *     surfaces `it's`).
 *
 * Output: logcat `UT7Measure` lines — run via ew-cli with `--outputs merged_results_xml,logcat`.
 * The hard assertion is wiring-sanity only (the bar produced suggestions); the ranks ARE the
 * measurement and feed the HANDOFF §0 ARC-013 "post-period boost?" decision.
 */
@RunWith(AndroidJUnit4::class)
class ContractionSentenceStartMeasureTest {

    private lateinit var context: Context
    private lateinit var suggestionBar: SuggestionBar
    private lateinit var suggestionHandler: SuggestionHandler

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

        synchronized(ContractionSentenceStartMeasureTest::class.java) {
            if (!initAttempted) {
                initAttempted = true
                try {
                    sharedConfig = Config.globalConfig()
                    sharedConfig!!.autocorrect_enabled = true
                    sharedConfig!!.word_prediction_enabled = true

                    sharedContractionManager = ContractionManager(context)
                    sharedContractionManager!!.loadMappings()

                    sharedPredictor = WordPredictor().also {
                        it.setContext(context)
                        it.setConfig(sharedConfig!!)
                        it.loadDictionary(context, "en")
                    }
                    sharedPredictionCoordinator = PredictionCoordinator(context, sharedConfig!!)
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("UT7Measure", "init OOM — skipping")
                    sharedPredictor = null
                }
            }
        }
        assumeNotNull("WordPredictor required (may OOM on small heap)", sharedPredictor)

        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            suggestionBar = SuggestionBar(context)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)

        val stubReceiver = object : KeyEventHandler.IReceiver {
            override fun handle_event_key(ev: KeyValue.Event) {}
            override fun set_shift_state(state: Boolean, lock: Boolean) {}
            override fun set_compose_pending(pending: Boolean) {}
            override fun selection_state_changed(selectionIsOngoing: Boolean) {}
            override fun getCurrentInputConnection(): InputConnection? = null
            override fun getHandler(): Handler = Handler(Looper.getMainLooper())
            override fun handle_text_typed(text: String) {}
        }

        val predCoord = sharedPredictionCoordinator!!
        try {
            val wpField = PredictionCoordinator::class.java.getDeclaredField("wordPredictor")
            wpField.isAccessible = true
            wpField.set(predCoord, sharedPredictor)
        } catch (e: Exception) {
            android.util.Log.w("UT7Measure", "reflection inject failed: ${e.message}")
        }
        assumeNotNull("WordPredictor must be accessible", predCoord.getWordPredictor())

        suggestionHandler = SuggestionHandler(
            context, sharedConfig!!, PredictionContextTracker(),
            predCoord, sharedContractionManager!!, KeyEventHandler(stubReceiver)
        )
        suggestionHandler.setSuggestionBar(suggestionBar)
    }

    private fun drainMainThread() {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
    }

    /** Type [word] letter-by-letter through the real handler; return the bar's list. */
    private fun barAfterTyping(word: String): List<String> {
        for (ch in word) suggestionHandler.handleRegularTyping(ch.toString(), null, null)
        Thread.sleep(1000)
        drainMainThread()
        return suggestionBar.getCurrentSuggestions()
    }

    /**
     * One case per test method: the harness has no public typed-word reset, and a fresh
     * [SuggestionHandler] per `@Before` (the [ContractionFlickerIntegrationTest] pattern)
     * is the clean isolation. Bar may capitalize (I'm) — matched case-insensitively.
     */
    private fun measure(typed: String, contraction: String) {
        val bar = barAfterTyping(typed)
        val cRank = bar.indexOfFirst { it.equals(contraction, ignoreCase = true) }
        val litRank = bar.indexOfFirst { it.equals(typed, ignoreCase = true) }
        android.util.Log.i(
            "UT7Measure",
            "typed='%s' bar=%s | contraction '%s' rank=%d, literal rank=%d %s".format(
                typed, bar.take(6), contraction, cRank, litRank,
                when {
                    cRank == -1 -> "(contraction ABSENT from bar)"
                    litRank == -1 || cRank < litRank -> "(contraction leads)"
                    else -> "(literal leads — UT-7's complaint; no sentence-start signal exists to flip it)"
                }
            )
        )
        android.util.Log.i(
            "UT7Measure",
            "NOTE ordering is position-independent today (no sentence-start signal in main); " +
                "this rank holds at sentence start AND mid-sentence by construction."
        )
        assertTrue(
            "suggestion bar must produce suggestions for typed '$typed' " +
                "(wiring sanity; the ranks above are the measurement)",
            bar.isNotEmpty()
        )
    }

    @Test fun rank_im() = measure("im", "i'm")
    @Test fun rank_ill() = measure("ill", "i'll")
    @Test fun rank_id() = measure("id", "i'd")
}
