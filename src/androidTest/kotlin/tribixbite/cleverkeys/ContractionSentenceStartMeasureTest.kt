package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UT-7 MEASUREMENT (ARC-013) — where do the I-contractions rank at sentence start?
 *
 * The v1.5.0 UT round deferred "I'd under-ranked at sentence start" and it was never
 * re-measured. Verified separately: **no sentence-start ranking signal exists anywhere**
 * (zero hits for `sentenceStart|afterPeriod` in main; `capitalizeIWord` fixes casing
 * only, after ranking). So this test does not expect a difference — it MEASURES the
 * baseline so the "add a post-period boost?" decision (HANDOFF §0 ARC-013) is made on
 * numbers instead of a two-month-old anecdote.
 *
 * Output goes to logcat as `UT7Measure` lines — run via ew-cli with
 * `--outputs merged_results_xml,logcat` or the numbers are lost (see the ew-cli skill).
 *
 * Assertions are sanity-only (predictions exist; the contraction is REACHABLE somewhere
 * in the list). Rank positions are reported, not asserted — they are the measurement.
 */
@RunWith(AndroidJUnit4::class)
class ContractionSentenceStartMeasureTest {

    private lateinit var context: Context
    private lateinit var predictor: WordPredictor

    companion object {
        // One dictionary load for the class (same OOM discipline as TypingSimulationTest).
        private var sharedPredictor: WordPredictor? = null
        @Volatile private var initAttempted = false
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        synchronized(ContractionSentenceStartMeasureTest::class.java) {
            if (!initAttempted) {
                initAttempted = true
                try {
                    TestConfigHelper.ensureConfigInitialized(context)
                    val config = Config.globalConfig()
                    sharedPredictor = WordPredictor().also {
                        it.setContext(context)
                        it.setConfig(config)
                        it.loadDictionary(context, "en")
                    }
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("UT7Measure", "WordPredictor init OOM — skipping")
                    sharedPredictor = null
                }
            }
        }
        assumeNotNull("WordPredictor required (may OOM on small heap)", sharedPredictor)
        predictor = sharedPredictor!!
    }

    private fun report(typed: String, target: String, contextWords: List<String>, label: String): Int {
        val result = predictor.predictWordsWithContext(typed, contextWords)
        val rank = result.words.indexOfFirst { it.equals(target, ignoreCase = true) }
        android.util.Log.i(
            "UT7Measure",
            "typed='%s' target='%s' ctx=%s rank=%d top5=%s".format(
                typed, target, label, rank, result.words.take(5)
            )
        )
        return rank
    }

    @Test
    fun iContractionRanks_sentenceStartVsMidSentence() {
        // The three I-contractions UT-7 named, measured in both positions. A sentence
        // start is an EMPTY context (nothing committed since the period); mid-sentence
        // uses a neutral preceding word.
        val cases = listOf(
            Triple("id", "i'd", "id"),
            Triple("ill", "i'll", "ill"),
            Triple("im", "i'm", "im"),
        )
        var reachable = 0
        for ((typed, contraction, literal) in cases) {
            val startRank = report(typed, contraction, emptyList(), "sentence-start")
            val literalStart = report(typed, literal, emptyList(), "sentence-start")
            val midRank = report(typed, contraction, listOf("yesterday"), "mid-sentence")
            android.util.Log.i(
                "UT7Measure",
                "SUMMARY typed='%s': contraction@start=%d literal@start=%d contraction@mid=%d %s".format(
                    typed, startRank, literalStart, midRank,
                    if (startRank in 0 until literalStart || literalStart == -1) "(contraction leads)"
                    else "(LITERAL leads at sentence start — UT-7's complaint)"
                )
            )
            if (startRank >= 0) reachable++
        }
        assertTrue(
            "at least two of the three I-contractions must be reachable in the " +
                "prediction list at all (reachability, not rank — rank is the measurement)",
            reachable >= 2
        )
    }
}
