package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.UserWordFrequency
import tribixbite.cleverkeys.swipe.ctc.CtcCandidate
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge

/**
 * Final-letter replay (maintainer report 2026-10-07: "I can never swipe `adb` — I get `an` —
 * and when I swipe `somethings` I get `something` even though I swiped all the way back to
 * the s"). Evidence and the corpus measurement: `docs/eval/2026-10-07-final-letter-drops.md`.
 *
 * ## What the replay established
 *
 *  - `adb` is in NEITHER English swipe lexicon and has zero training traces. A user word does
 *    reach the CTC trie ([CtcLexiconMerge] — the custom-word preference plus the platform user
 *    dictionary), and at the default stored frequency 255 its prior already beats `an`'s. It
 *    still loses: the encoder emits nothing for `d` (a ~28° bend in pixel space) and reads the
 *    stop on `b` as `n`. That is an encoder limitation; no decoder constant can reach it.
 *  - `somethings` IS in the lexicon (byte 168 vs `something` 219). On a straight trace the
 *    encoder emits the final `s` at ≥ .96 and the word wins by a near-tie margin (< 0.2 final-
 *    score points): λ·Δln f (1.06, top 2 % of the lexicon's s-plural prior gaps) almost cancels
 *    the length-normalized evidence for the extra letter. A personal-dictionary entry (the
 *    "Prefer … when swiping?" remedy) turns it into a clear win.
 *
 * The pins below hold what is true and useful to keep true; they deliberately do not pin the
 * `adb` loss (a better encoder should be free to fix it).
 */
class CtcFinalLetterReplayTest {

    private fun assumeOrt() {
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + onnxruntime.native.path are set",
            CtcReplayEngine.ortAvailable(),
        )
    }

    /** 1-based rank of [word] in [cands], or -1 when absent. */
    private fun rankOf(cands: List<CtcCandidate>, word: String): Int =
        cands.indexOfFirst { it.word == word }.let { if (it < 0) -1 else it + 1 }

    /** Final-score margin of [word] over [rival] (positive = [word] ahead); NaN when either is absent. */
    private fun margin(cands: List<CtcCandidate>, word: String, rival: String): Double {
        val a = cands.firstOrNull { it.word == word } ?: return Double.NaN
        val b = cands.firstOrNull { it.word == rival } ?: return Double.NaN
        return a.finalScore - b.finalScore
    }

    // ── measurement instrument ─────────────────────────────────────────────────────

    @Test
    fun finalLetterDiagnostic() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            val base = engine.baseLexiconPairs()
            val beam = engine.scoringParams.beamWidth
            println("[FL] EP=${CtcReplayEngine.executionProvider} bytes: " +
                (listOf(ADB, "an", "ab") + PLURALS.flatMap { listOf(it, it.dropLast(1)) })
                    .joinToString(" ") { "$it=${engine.frequencyOf(it)}" })
            val adbMerged = CtcLexiconMerge.merge(base, listOf(ADB to UserWordFrequency.DEFAULT), emptySet())
            val adbDecoder = engine.decoderWithLexicon(adbMerged, topK = beam)
            val shipped = engine.fullBeamDecoder()
            for ((label, shape) in shapes(ADB, layout)) {
                val (x, y, t) = shape
                val detailed = adbDecoder.decodeDetailed(x, y, t)
                val c = detailed.candidates
                println("[FL] adb@custom255 %-12s greedy=%-6s rank=%2d margin(adb-an)=%7.3f top3=%s".format(
                    label, detailed.greedy, rankOf(c, ADB), margin(c, ADB, "an"),
                    c.take(3).map { "%s:%.2f".format(it.word, it.finalScore) }))
            }
            for (word in PLURALS) {
                val stem = word.dropLast(1)
                for ((label, shape) in shapes(word, layout)) {
                    val (x, y, t) = shape
                    val c = shipped.decode(x, y, t)
                    println("[FL] %-10s %-12s rank=%2d margin(vs %s)=%7.3f top3=%s".format(
                        word, label, rankOf(c, word), stem, margin(c, word, stem),
                        c.take(3).map { "%s:%.2f".format(it.word, it.finalScore) }))
                }
            }
        }
    }

    /** The deterministic shapes the diagnostic decodes — straight plus two wobbles ([CtcTraceShapes]). */
    private fun shapes(word: String, layout: CtcLayout) = listOf(
        "straight" to CtcTraceShapes.straight(word, layout),
        "wobble 0.02" to CtcTraceShapes.wobbled(word, layout, 0.02, 5.0),
        "wobble 0.035" to CtcTraceShapes.wobbled(word, layout, 0.035, 4.0),
    )

    // ── pins ───────────────────────────────────────────────────────────────────────

    /** Coverage facts the report turns on: `adb` absent, `somethings` present below its stem. */
    @Test
    fun lexiconCoverageOfTheReportedWords() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            assertWithMessage("adb is not an English base word").that(engine.frequencyOf(ADB)).isNull()
            val plural = engine.frequencyOf("somethings")
            val stem = engine.frequencyOf("something")
            assertWithMessage("somethings is a base word").that(plural).isNotNull()
            assertWithMessage("and rarer than its stem").that(plural!!).isLessThan(stem!!)
        }
    }

    /**
     * A user word reaches the decode: `adb` added at the dictionary default is a member of the
     * final beam for its own straight trace, with a prior at the top of the base scale. (It does
     * not win — see the class KDoc — and that is deliberately not pinned.)
     */
    @Test
    fun customAdbReachesTheFinalBeam() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val merged = CtcLexiconMerge.merge(
                engine.baseLexiconPairs(), listOf(ADB to UserWordFrequency.DEFAULT), emptySet())
            assertWithMessage("default-stored custom word maps to the scale cap")
                .that(merged[ADB]).isEqualTo(CtcLexiconMerge.MAX_FREQ)
            val decoder = engine.decoderWithLexicon(merged, topK = engine.scoringParams.beamWidth)
            val (x, y, t) = CtcTraceShapes.straight(ADB, engine.layoutGeometry)
            val cands = decoder.decode(x, y, t)
            assertWithMessage("custom 'adb' must be in the final beam; beam head ${cands.take(8).map { it.word }}")
                .that(rankOf(cands, ADB)).isGreaterThan(0)
        }
    }

    /**
     * The shipped remedy for a rare plural whose stem dominates the prior: a personal-dictionary
     * entry at the default frequency makes `somethings` rank 1 on every measured shape, with a
     * clear margin over `something` (the shipped lexicon alone gives a sub-0.2 near-tie).
     */
    @Test
    fun personalDictionarySomethingsWinsItsTrace() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            val merged = CtcLexiconMerge.merge(
                engine.baseLexiconPairs(), listOf("somethings" to UserWordFrequency.DEFAULT), emptySet())
            val decoder = engine.decoderWithLexicon(merged, topK = engine.scoringParams.beamWidth)
            for ((label, shape) in listOf(
                "straight" to CtcTraceShapes.straight("somethings", layout),
                "wobble 0.02" to CtcTraceShapes.wobbled("somethings", layout, 0.02, 5.0),
            )) {
                val (x, y, t) = shape
                val cands = decoder.decode(x, y, t)
                assertWithMessage("$label: slate ${cands.take(3).map { it.word }}")
                    .that(cands.first().word).isEqualTo("somethings")
                assertWithMessage("$label: margin over 'something'")
                    .that(margin(cands, "somethings", "something")).isGreaterThan(REMEDY_MARGIN)
            }
        }
    }

    /**
     * Ordinary s-plurals keep their final letter on the shipped stack (straight traces): the
     * decoder does not systematically prefer the stem. Real-trace rates are in the eval note.
     */
    @Test
    fun ordinaryPluralsKeepTheirFinalS() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            for (word in PLURALS - "somethings") {
                val (x, y, t) = CtcTraceShapes.straight(word, layout)
                val cands = engine.decodeDetailed(x, y, t).candidates
                assertWithMessage("'$word' straight trace; slate ${cands.take(3).map { it.word }}")
                    .that(cands.first().word).isEqualTo(word)
            }
        }
    }

    private companion object {
        const val ADB = "adb"

        /** `somethings` (the report) plus common plurals whose stems are also base words. */
        val PLURALS = listOf("somethings", "things", "others", "words", "keyboards", "emails", "tests", "apps")

        /** Final-score lead the personal-dictionary remedy must give (λ·ln(255/168) ≈ 1.67 on top of the tie). */
        const val REMEDY_MARGIN = 1.0
    }
}
