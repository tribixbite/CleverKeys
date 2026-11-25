package tribixbite.cleverkeys

import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Measurement (not a gate) of Auto-Detect Language, the [LanguageDetector] that
 * `WordPredictor.tryAutoLanguageDetection` runs after every committed word once five are recent
 * (window: the last 20; a switch clears it). Opt-in: `-PgeoFull=true`; reads the static-LM
 * builder's held-out sentences (`~/.cache/cleverkeys-corpora/static-lm-eval/heldout_<lang>.txt`,
 * local only) and Assume-skips without them.
 *
 * Cells, per language pair (primary P, secondary S), typing the held-out text word by word:
 * - **false switches**: typing P only, starting in P, switches per 1,000 words (the rule since
 *   2026-11-25: only to S, only above the sensitivity);
 * - **old rule**: the same stream with the pre-2026-11-25 rule (any of the detector's languages);
 *   for P alone this is what a single-language user got, Multi-Language off;
 * - **reaction**: typing S, starting in P, the median words until the switch to S (and the share
 *   of 40-word runs that never switch).
 */
class LanguageDetectionEvalTest {

    private val evalDir = File(System.getProperty("user.home"), ".cache/cleverkeys-corpora/static-lm-eval")
    private val sensitivity = Defaults.LANGUAGE_DETECTION_SENSITIVITY

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    /** Lowercased word tokens of up to [maxWords] words, in file order. */
    private fun words(lang: String, maxWords: Int): List<String> {
        val f = File(evalDir, "heldout_$lang.txt")
        Assume.assumeTrue("no ${f.path}", f.isFile)
        val out = ArrayList<String>(maxWords)
        val token = Regex("[\\p{L}']+")
        f.useLines { lines ->
            for (line in lines) {
                for (m in token.findAll(line.lowercase())) {
                    out += m.value
                    if (out.size >= maxWords) return out
                }
            }
        }
        return out
    }

    /** Types [stream] starting in [start]; returns the language after each word. */
    private fun type(stream: List<String>, start: String, allowed: Set<String>?): List<String> {
        val detector = LanguageDetector()
        val window = ArrayList<String>()
        var current = start
        val after = ArrayList<String>(stream.size)
        for (w in stream) {
            window += w
            while (window.size > 20) window.removeAt(0)
            if (window.size >= 5) {
                val r = detector.detectLanguageFromWordsWithConfidence(window)
                if (r != null && r.language != current && r.confidence >= sensitivity &&
                    (allowed == null || r.language in allowed)
                ) {
                    current = r.language
                    window.clear()
                }
            }
            after += current
        }
        return after
    }

    private fun switchesPer1000(states: List<String>): Double {
        var n = 0
        for (i in 1 until states.size) if (states[i] != states[i - 1]) n++
        return 1000.0 * n / states.size
    }

    @Test
    fun measureAutoDetect() {
        Assume.assumeTrue("opt-in: -PgeoFull=true", System.getProperty("geoFull") == "true")
        val pairs = listOf("en" to "de", "en" to "fr", "en" to "es", "en" to "pt", "en" to "sv",
            "de" to "fr", "fr" to "es", "es" to "pt", "de" to "sv")
        val corpus = HashMap<String, List<String>>()
        fun text(l: String) = corpus.getOrPut(l) { words(l, 20_000) }
        println("AUTO-DETECT EVAL  sensitivity=$sensitivity  window=20  (held-out sentences)")
        println("single language (old rule, Multi-Language off):")
        for (l in listOf("en", "de", "fr", "es", "pt", "sv")) {
            val states = type(text(l), l, null)
            val away = states.count { it != l }
            println("  $l: %.2f switches / 1000 words, %.1f%% of words typed under another language"
                .format(switchesPer1000(states), 100.0 * away / states.size))
        }
        println("pairs (P+S): false switches typing P / 1000 words [new rule | old rule]; reaction typing S")
        var measured = 0
        for ((p, s) in pairs) {
            val pText = text(p)
            val newRule = switchesPer1000(type(pText, p, setOf(p, s)))
            val oldRule = switchesPer1000(type(pText, p, null))
            val sText = text(s)
            val reactions = ArrayList<Int>()
            var never = 0
            var runs = 0
            var i = 0
            while (i + 40 <= sText.size && runs < 300) {
                val states = type(sText.subList(i, i + 40), p, setOf(p, s))
                val at = states.indexOf(s)
                if (at < 0) never++ else reactions += at + 1
                runs++
                i += 40
            }
            reactions.sort()
            val median = if (reactions.isEmpty()) -1 else reactions[reactions.size / 2]
            println("  $p+$s: %.2f | %.2f; reaction median %d words, %.0f%% of %d runs never switch"
                .format(newRule, oldRule, median, 100.0 * never / runs, runs))
            measured++
        }
        assertWithMessage("measured something").that(measured).isGreaterThan(0)
    }
}
