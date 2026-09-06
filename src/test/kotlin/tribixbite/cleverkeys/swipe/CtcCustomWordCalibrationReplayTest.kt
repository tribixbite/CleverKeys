package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcCandidate
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcSwipeDecoder

/**
 * Wave U2 — replay measurement + pins for the CUSTOM-WORD frequency calibration
 * (maintainer report: "custom and unusual words are harder to swipe than they should
 * be — especially custom words").
 *
 * ## The defect being measured
 *
 * The en base lexicon (`en_enhanced.json`) uses a compressed byte scale whose FLOOR is
 * 134 (measured: min 134, max 255 across 98,140 words), and the shipped en preset's
 * λ = 4.0 multiplies `ln(freq)` on exactly that scale. The old `CtcLexiconMerge`
 * clamped a custom word's stored frequency into 1..255 RAW — so the Add-Word dialog's
 * historical default of 100 produced `ln 100 = 4.61 < ln 134 = 4.90`: a default-added
 * custom word carried a WEAKER lexicon prior than every single word in the English
 * dictionary (λ·Δ = 4.0×0.29 ≈ 1.17 final-score points below the rarest base word,
 * ≈ 3.74 below a 255-frequency word). The calibrated merge maps stored 1..255 linearly
 * onto [base_floor..255], so stored 100 lands at ≈ 181 (ln 5.20) and the new dialog
 * default 255 lands at the cap.
 *
 * ## What this class does
 *
 * [customWordCalibrationDiagnostic] prints the full rank/margin table for a nonsense
 * custom word ("flurble") and a real-word-adjacent name ("bowien", one edit from base
 * "bowie" @176) at three effective frequencies — raw 100 (the pre-fix shipping
 * behavior, injected directly), merge(stored=100) (legacy stored values through the
 * calibrated merge), merge(stored=255) (the new dialog default) — across the same
 * deterministic straight/wobbled shapes the #162 pins use ([CtcTraceShapes]).
 * [unusualBaseWordDiagnostic] measures the honest baseline for "unusual" BASE words:
 * a floor-frequency (134) word vs its common competitors — that gap is the designed
 * prior, not the defect, and is reported rather than retuned.
 *
 * The pins assert the calibrated state: the dialog-default custom word wins its own
 * trace, and a legacy stored-100 word is strictly better off than the raw-100 prior it
 * used to get. Decoder params are untouched — this is a LEXICON-side calibration.
 */
class CtcCustomWordCalibrationReplayTest {

    private fun assumeOrt() {
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + onnxruntime.native.path are set",
            CtcReplayEngine.ortAvailable(),
        )
    }

    /** The deterministic shapes each configuration decodes — straight plus two wobbles. */
    private fun shapesFor(
        word: String,
        layout: CtcLayout,
    ): List<Pair<String, Triple<DoubleArray, DoubleArray, DoubleArray>>> = listOf(
        "straight" to CtcTraceShapes.straight(word, layout),
        "wobble 0.02" to CtcTraceShapes.wobbled(word, layout, 0.02, 5.0),
        "wobble 0.035" to CtcTraceShapes.wobbled(word, layout, 0.035, 4.0),
    )

    /** 1-based rank of [word] in [cands] (or -1), and its final-score margin: positive = lead over the runner-up when rank 1, negative = deficit vs rank 1 otherwise. */
    private fun rankAndMargin(cands: List<CtcCandidate>, word: String): Pair<Int, Double> {
        val idx = cands.indexOfFirst { it.word == word }
        if (idx < 0) return -1 to Double.NaN
        val margin = if (idx == 0) {
            cands[0].finalScore - (cands.getOrNull(1)?.finalScore ?: cands[0].finalScore)
        } else {
            cands[idx].finalScore - cands[0].finalScore
        }
        return (idx + 1) to margin
    }

    /** Base + [word] at LITERAL frequency [freq] — the raw prior, bypassing the merge. */
    private fun rawLexicon(
        base: List<Pair<String, Double>>,
        word: String,
        freq: Double,
    ): LinkedHashMap<String, Double> {
        val map = LinkedHashMap<String, Double>(base.size * 2)
        map[word] = freq
        for ((w, f) in base) if (w != word) map[w] = f
        return map
    }

    private fun decodeRow(
        decoder: CtcSwipeDecoder,
        word: String,
        label: String,
        shape: Triple<DoubleArray, DoubleArray, DoubleArray>,
    ): String {
        val (x, y, t) = shape
        val cands = decoder.decode(x, y, t)
        val (rank, margin) = rankAndMargin(cands, word)
        return "%-8s %-12s rank=%2d margin=%8.3f top3=%s".format(
            word, label, rank, margin,
            cands.take(3).map { "%s:%.2f".format(it.word, it.finalScore) },
        )
    }

    // ── the measurement instrument (prints the full before/after table) ────────────

    @Test
    fun customWordCalibrationDiagnostic() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            val base = engine.baseLexiconPairs()
            val floor = base.minOf { it.second }
            println("[U2] EP=${CtcReplayEngine.executionProvider} baseFloor=$floor baseSize=${base.size}")
            for (word in listOf(NONSENSE, NAME_LIKE)) {
                val configs = LinkedHashMap<String, LinkedHashMap<String, Double>>()
                configs["raw@100"] = rawLexicon(base, word, 100.0) // pre-fix shipping prior
                for (stored in listOf(LEGACY_STORED, DEFAULT_STORED)) {
                    val merged = CtcLexiconMerge.merge(base, listOf(word to stored), emptySet())
                    configs["merge@$stored"] = merged
                }
                for ((label, lexicon) in configs) {
                    println("[U2] '$word' $label -> effFreq=${lexicon[word]}")
                    val decoder = engine.decoderWithLexicon(lexicon)
                    for ((shapeLabel, shape) in shapesFor(word, layout)) {
                        println("[U2]   " + decodeRow(decoder, word, shapeLabel, shape))
                    }
                }
            }
        }
    }

    /**
     * Honest-baseline measurement for "unusual" BASE words: a floor-frequency word
     * ("toml" @134) against its common neighborhood (told 217 / tool 219 / toll 187).
     * Whatever gap this prints is the DESIGNED λ-weighted prior over the shipped en
     * byte scale — reported for the maintainer answer, deliberately NOT retuned here.
     */
    @Test
    fun unusualBaseWordDiagnostic() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            val cohort = listOf(UNUSUAL) + UNUSUAL_RIVALS
            println("[U2] freq bytes: " + cohort.joinToString(" ") { "$it=${engine.frequencyOf(it)}" })
            for ((shapeLabel, shape) in shapesFor(UNUSUAL, layout)) {
                val (x, y, t) = shape
                val cands = engine.decodeDetailed(x, y, t).candidates
                val (rank, margin) = rankAndMargin(cands, UNUSUAL)
                println("[U2] '$UNUSUAL' $shapeLabel rank=$rank margin=%.3f top3=%s".format(
                    margin, cands.take(3).map { "%s:%.2f".format(it.word, it.finalScore) }))
            }
            val (x, y, t) = CtcTraceShapes.straight(UNUSUAL, layout)
            for (c in engine.forcedDecode(cohort, x, y, t)) {
                println("[U2]   FORCE %-8s final=%8.3f ctc=%9.3f lnF=%.3f".format(
                    c.word, c.finalScore, c.ctcScore, c.logFreq))
            }
        }
    }

    // ── pins (calibrated state; red before the wave-U2 merge fix) ──────────────────

    /**
     * PIN 1 — a custom word stored at the NEW dialog default (255) decodes at rank 1
     * on its own straight trace, for both the nonsense word and the name-like word.
     * This is the promise the Add-Word dialog now makes: a user adding a word wants
     * it to win its own gesture.
     */
    @Test
    fun customWordAtDialogDefaultDecodesAtRankOne() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            val base = engine.baseLexiconPairs()
            for (word in listOf(NONSENSE, NAME_LIKE)) {
                val merged = CtcLexiconMerge.merge(base, listOf(word to DEFAULT_STORED), emptySet())
                val decoder = engine.decoderWithLexicon(merged)
                val (x, y, t) = CtcTraceShapes.straight(word, layout)
                val cands = decoder.decode(x, y, t)
                assertWithMessage(
                    "custom word '$word' stored at the dialog default ($DEFAULT_STORED) must decode " +
                        "at rank 1 on its own straight trace; slate was ${cands.map { it.word }}"
                ).that(cands.firstOrNull()?.word).isEqualTo(word)
            }
        }
    }

    /**
     * PIN 2 — a LEGACY stored value of 100 (the old dialog default, deliberately NOT
     * rewritten in storage) is lifted by the merge-time calibration: its effective
     * frequency clears the base floor, and on every measured shape its rank is no
     * worse and its straight-trace margin strictly better than the raw-100 prior the
     * old clamp gave it. Red before the fix (merge(100) == raw@100 exactly).
     */
    @Test
    fun legacyStoredHundredIsLiftedAboveTheRawPrior() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            val base = engine.baseLexiconPairs()
            val floor = base.minOf { it.second }
            val merged = CtcLexiconMerge.merge(base, listOf(NONSENSE to LEGACY_STORED), emptySet())
            assertWithMessage("merge must lift a legacy stored 100 above the base floor ($floor)")
                .that(merged[NONSENSE]!!).isGreaterThan(floor)

            val calibrated = engine.decoderWithLexicon(merged)
            val rawLegacy = engine.decoderWithLexicon(rawLexicon(base, NONSENSE, 100.0))
            for ((shapeLabel, shape) in shapesFor(NONSENSE, layout)) {
                val (x, y, t) = shape
                val (rankNew, marginNew) = rankAndMargin(calibrated.decode(x, y, t), NONSENSE)
                val (rankOld, marginOld) = rankAndMargin(rawLegacy.decode(x, y, t), NONSENSE)
                // Absent (-1) sorts as worst.
                val rankNewOrd = if (rankNew < 0) Int.MAX_VALUE else rankNew
                val rankOldOrd = if (rankOld < 0) Int.MAX_VALUE else rankOld
                assertWithMessage(
                    "calibrated legacy-100 must never rank WORSE than the raw-100 prior " +
                        "($shapeLabel: new=$rankNew old=$rankOld)"
                ).that(rankNewOrd).isAtMost(rankOldOrd)
                if (shapeLabel == "straight") {
                    assertWithMessage(
                        "calibrated legacy-100 must strictly improve the straight-trace margin " +
                            "(new=$marginNew old=$marginOld)"
                    ).that(marginNew).isGreaterThan(marginOld)
                }
            }
        }
    }

    private companion object {
        /** Not in the base lexicon and not adjacent to a strong base word cluster. */
        const val NONSENSE = "flurble"

        /** Name-like custom word one edit from base "bowie" (byte 176). */
        const val NAME_LIKE = "bowien"

        /** A base word sitting AT the en byte-scale floor (134). */
        const val UNUSUAL = "toml"
        val UNUSUAL_RIVALS = listOf("told", "tool", "toll", "tomb")

        /** The historical dialog/migration default the calibration must rescue. */
        const val LEGACY_STORED = 100

        /** The new dialog default: a user-added word should win its own trace. */
        const val DEFAULT_STORED = 255
    }
}
