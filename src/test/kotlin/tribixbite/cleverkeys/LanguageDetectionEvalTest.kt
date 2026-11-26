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

    // ---- Default-sensitivity sweep (pre-registered: docs/eval/2026-11-26-auto-detect-threshold.md) ----

    /**
     * Lowercased tokens of one half of `heldout_<lang>.txt`, in file order: a sentence is a line
     * and its id the 0-based line index; dev = even ids, confirm = odd ids.
     */
    private fun halfWords(lang: String, half: String, maxWords: Int): List<String> {
        val f = File(evalDir, "heldout_$lang.txt")
        Assume.assumeTrue("no ${f.path}", f.isFile)
        val parity = when (half) {
            "dev" -> 0
            "confirm" -> 1
            else -> throw IllegalArgumentException("AUTO_DETECT_EVAL_HALF must be dev or confirm, not $half")
        }
        val out = ArrayList<String>(maxWords)
        val token = Regex("[\\p{L}']+")
        f.useLines { lines ->
            for ((id, line) in lines.withIndex()) {
                if (id % 2 != parity) continue
                for (m in token.findAll(line.lowercase())) {
                    out += m.value
                    if (out.size >= maxWords) return out
                }
            }
        }
        return out
    }

    /**
     * Types [stream] with production rules: the real [MultiLanguageManager] choosing between
     * [pair], `WordPredictor`'s window (last 20 words, detection from 5, cleared on a switch).
     * @return the language after each word
     */
    private fun typeProduction(stream: List<String>, start: String, pair: Set<String>, threshold: Float): List<String> {
        val manager = MultiLanguageManager(start)
        val window = ArrayList<String>()
        var current = start
        val after = ArrayList<String>(stream.size)
        for (w in stream) {
            window += w
            while (window.size > 20) window.removeAt(0)
            if (window.size >= 5) {
                val switched = manager.detectAndSwitch(window, threshold, pair)
                if (switched != null && switched != current && switched in pair) {
                    current = switched
                    window.clear()
                }
            }
            after += current
        }
        return after
    }

    /** One pair's cells at one threshold; see the pre-registration for definitions. */
    private data class SweepCells(val fs: Double, val never: Double, val median: Int, val back: Double)

    private fun sweepCells(p: String, s: String, pText: List<String>, sText: List<String>, threshold: Float): SweepCells {
        val pair = setOf(p, s)
        // FS: switches P -> S per 1,000 words of P typed from P.
        val states = typeProduction(pText, p, pair, threshold)
        var fsCount = 0
        for (i in states.indices) {
            val before = if (i == 0) p else states[i - 1]
            if (before == p && states[i] == s) fsCount++
        }
        val fs = 1000.0 * fsCount / pText.size
        // never / median: 40-word runs of S from P; a run that never switches counts as 41.
        val reactions = ArrayList<Int>()
        var i = 0
        while (i + 40 <= sText.size && reactions.size < 300) {
            val at = typeProduction(sText.subList(i, i + 40), p, pair, threshold).indexOf(s)
            reactions += if (at < 0) 41 else at + 1
            i += 40
        }
        val never = reactions.count { it > 40 }.toDouble() / reactions.size
        val median = reactions.sorted()[reactions.size / 2]
        // back: 40-word runs of P from S; the share back in P within 20 words.
        var backRuns = 0
        var backOk = 0
        i = 0
        while (i + 40 <= pText.size && backRuns < 300) {
            val at = typeProduction(pText.subList(i, i + 40), s, pair, threshold).indexOf(p)
            if (at in 0 until 20) backOk++
            backRuns++
            i += 40
        }
        return SweepCells(fs, never, median, backOk.toDouble() / backRuns)
    }

    @Test
    fun sweepDefaultSensitivity() {
        Assume.assumeTrue("opt-in: -PgeoFull=true", System.getProperty("geoFull") == "true")
        val half = System.getenv("AUTO_DETECT_EVAL_HALF")
        Assume.assumeTrue("set AUTO_DETECT_EVAL_HALF=dev|confirm", half != null)
        val thresholds = (System.getenv("AUTO_DETECT_EVAL_THRESHOLDS") ?: "0.40,0.45,0.50,0.55,0.60")
            .split(',').map { it.trim().toFloat() }
        val pairs = listOf("en" to "de", "en" to "fr", "en" to "es", "en" to "pt", "en" to "sv",
            "de" to "fr", "fr" to "es", "es" to "pt", "de" to "sv")
        val fsText = HashMap<String, List<String>>()
        val runText = HashMap<String, List<String>>()
        fun fsWords(l: String) = fsText.getOrPut(l) { halfWords(l, half!!, 20_000) }
        fun runWords(l: String) = runText.getOrPut(l) { halfWords(l, half!!, 12_000) }
        println("AUTO-DETECT SWEEP  half=$half  window=20, detect from 5  (pre-registered 2026-11-26)")
        var measured = 0
        for (t in thresholds) {
            var selectable = true
            var gates = true
            for ((p, s) in pairs) {
                val cb = sweepCells(p, s, fsWords(p), runWords(s), t)
                val g1 = cb.fs <= 3.0
                val g2 = cb.never <= 0.15
                val g3 = cb.median <= 20
                val g4 = cb.back >= 0.85
                if (!(g1 && g4)) selectable = false
                if (!(g1 && g2 && g3 && g4)) gates = false
                println("  t=%.2f %s+%s: FS %.2f/1000  never %.1f%%  median %d  back %.1f%%  [G1 %s G2 %s G3 %s G4 %s]"
                    .format(t, p, s, cb.fs, 100 * cb.never, cb.median, 100 * cb.back,
                        ok(g1), ok(g2), ok(g3), ok(g4)))
                measured++
            }
            println("  t=%.2f: selection rule (G1 and G4, every pair) %s; all four gates %s"
                .format(t, ok(selectable), ok(gates)))
        }
        assertWithMessage("measured something").that(measured).isGreaterThan(0)
    }

    private fun ok(b: Boolean) = if (b) "pass" else "FAIL"
}
