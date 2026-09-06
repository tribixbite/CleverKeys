package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcCandidate
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.geometric.GeoLayoutFixtures
import tribixbite.cleverkeys.swipe.geometric.GeoTraceSynthesizer
import tribixbite.cleverkeys.swipe.geometric.GeometricEngineConfig

/**
 * Issue-#162 replay instrument + characterization pins: swiped "gorgeous" (reported never
 * to surface at any rank in v1.5.0) against the SHIPPING CTC decode path.
 *
 * ## Findings (2026-09-03, this harness)
 *
 * The report is **not reproducible on the current engine — the defect belonged to the
 * DELETED neural engine**. Timeline: the issue was filed 2026-07-22 against v1.5.0
 * (tagged 2026-07-15); `CtcEngineAdapter` first landed 2026-08-14 (`617155a7`) and the
 * neural engine was removed 2026-08-18 (ADR-011). v1.5.0 had no CTC path at all, and the
 * reported wrong outputs (capitalized "Gothenburg", "violoncello", "googlers") are
 * word-vocabulary decodes of that removed engine — the CTC beam is trie-constrained and
 * lowercase-only and cannot emit them in that form.
 *
 * On today's shipped encoder + `CtcBeamDecoder` (en lexicon, shipped preset λ=4.0,
 * beam 100, topK 8), "gorgeous" decoded at **rank 1 on all 27 tested shapes**: canonical
 * straight-line, coarser/finer step counts, slow, two wobble amplitudes, an 'f' mis-start
 * ("forgeous"), and 17 humanlike synthesizer traces across CLEAN/TYPICAL/SLOPPY tiers.
 * Forced-decode (constrained-trie Viterbi, see [CtcReplayEngine.forcedDecode]) on the
 * canonical trace puts "gorgeous" at final 22.35 (ctc −4.28) vs the best competitor
 * "gorges" 20.18 — and vs the reporter's observed winners: forgetting 18.55, foothold
 * 16.87, gothenburg 15.81, github 15.05. The emissions STRONGLY support g-o-r-g-e-o-u-s;
 * the self-crossing path (o and g revisited) is not a failure mode for this encoder.
 * The frequency prior also favors it: byte 191 (ln 5.252) is the highest of the cohort.
 *
 * ## What the pins mean
 *
 * Today's behavior is HEALTHY, so the characterization tests pin the healthy state:
 * they go red if a future model/decoder/lexicon change regresses this gesture class
 * (path self-crossing / repeated-region words), which would silently re-open #162's
 * report class. A red here is a visible "the gorgeous shape regressed" signal, not
 * noise — re-run [gorgeousDiagnostic] for the full forced-score picture before acting.
 */
class CtcDecodeReplayTest {

    private fun assumeOrt() {
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + onnxruntime.native.path are set",
            CtcReplayEngine.ortAvailable(),
        )
    }

    // ── trace synthesis on the golden geometry ─────────────────────────────────
    //
    // The generators live in [CtcTraceShapes] (extracted verbatim, wave U2) so the
    // custom-word calibration replay measures the exact same gesture shapes these
    // #162 pins do. These wrappers keep the pin bodies unchanged.

    private fun straightTrace(
        word: String,
        layout: CtcLayout,
        stepsPerSegment: Int = 12,
        stepMs: Double = 16.0,
    ): Triple<DoubleArray, DoubleArray, DoubleArray> =
        CtcTraceShapes.straight(word, layout, stepsPerSegment, stepMs)

    private fun wobbledTrace(
        word: String,
        layout: CtcLayout,
        amp: Double,
        cycles: Double,
    ): Triple<DoubleArray, DoubleArray, DoubleArray> =
        CtcTraceShapes.wobbled(word, layout, amp, cycles)

    /** The named trace shapes the pins iterate — one place so diagnostic and pin agree. */
    private fun shapesFor(layout: CtcLayout): List<Pair<String, Triple<DoubleArray, DoubleArray, DoubleArray>>> =
        listOf(
            "canonical straight 12/seg" to straightTrace(TARGET, layout),
            "straight 6/seg" to straightTrace(TARGET, layout, stepsPerSegment = 6),
            "straight 24/seg" to straightTrace(TARGET, layout, stepsPerSegment = 24),
            "slow straight (32ms/step)" to straightTrace(TARGET, layout, stepMs = 32.0),
            "wobble amp=0.01" to wobbledTrace(TARGET, layout, 0.01, 3.0),
            "wobble amp=0.02" to wobbledTrace(TARGET, layout, 0.02, 5.0),
            // Reporter's hypothesis probe: start point misread as 'f' (adjacent key).
            "mis-start 'f' (forgeous shape)" to straightTrace("forgeous", layout),
        )

    private fun fmt(c: CtcCandidate): String =
        "%-14s final=%8.3f ctc=%9.3f len=%d lnF=%.3f".format(
            c.word, c.finalScore, c.ctcScore, c.length, c.logFreq)

    // ── the replay instrument (prints everything; the place to look on a red pin) ──

    @Test
    fun gorgeousDiagnostic() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            println("[162] EP=${CtcReplayEngine.executionProvider}")
            println("[162] freq bytes: gorgeous=${engine.frequencyOf(TARGET)} " +
                REPORTED_WINNERS.joinToString(" ") { "$it=${engine.frequencyOf(it)}" })
            val layout = engine.layoutGeometry
            val forced = listOf(TARGET, "gorgeously", "gorges", "georges") + REPORTED_WINNERS
            for ((label, trace) in shapesFor(layout)) {
                val (x, y, t) = trace
                val detailed = engine.decodeDetailed(x, y, t)
                val slate = engine.decode(x, y, t)
                println("[162] ── $label ──")
                println("[162] greedy='${detailed.greedy}' rank($TARGET)=${slate.words.indexOf(TARGET)}")
                println("[162] slate=${slate.words} scores=${slate.scores}")
                for (c in detailed.candidates) println("[162]   beam  ${fmt(c)}")
                for (c in engine.forcedDecode(forced, x, y, t)) println("[162]   FORCE ${fmt(c)}")
                println("[162]   rescue(greedy)=" +
                    engine.rescueFor(detailed.greedy, detailed.candidates.map { it.word }.toSet()))
            }
            // Control words sharing the g/o/r/e region: same generator, same geometry.
            for (w in listOf("gorges", "georges", "forgetting", "gorgeously")) {
                val (x, y, t) = straightTrace(w, layout)
                val slate = engine.decode(x, y, t)
                println("[162] control '$w' rank=${slate.words.indexOf(w)} slate=${slate.words.take(5)}")
            }
        }
    }

    // ── characterization pins (healthy-state; red = the gorgeous shape regressed) ──

    /**
     * PIN 1 — every deterministic synthetic shape of the #162 gesture decodes "gorgeous"
     * at RANK 1, and its forced (constrained-trie Viterbi) final score beats every one of
     * the reporter's observed winners by at least [FORCED_MARGIN_MIN] on the canonical
     * trace. Measured 2026-09-03: margins +3.8 (forgetting) to +7.3 (github); the
     * nearest competitor overall ("gorges") trails by +2.17.
     */
    @Test
    fun gorgeousIsRankOneOnDeterministicShapes() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            for ((label, trace) in shapesFor(layout)) {
                val (x, y, t) = trace
                val slate = engine.decode(x, y, t)
                assertWithMessage(
                    "REGRESSION of the issue-#162 gesture class (self-crossing swipe path): " +
                        "'$TARGET' must decode at rank 1 on shape [$label] but the slate was " +
                        "${slate.words}. Run gorgeousDiagnostic for forced-score details."
                ).that(slate.words.firstOrNull()).isEqualTo(TARGET)
            }

            val (x, y, t) = straightTrace(TARGET, layout)
            val forced = engine.forcedDecode(listOf(TARGET) + REPORTED_WINNERS, x, y, t)
                .associateBy { it.word }
            val target = forced.getValue(TARGET)
            for (w in REPORTED_WINNERS) {
                val rival = forced[w] ?: continue // absent = beam-unreachable on this trace, fine
                assertWithMessage(
                    "forced-decode margin collapsed: '$TARGET' (final=${target.finalScore}) must " +
                        "beat reported-#162 winner '$w' (final=${rival.finalScore}) by >= " +
                        "$FORCED_MARGIN_MIN on the canonical trace. The emissions no longer " +
                        "clearly support g-o-r-g-e-o-u-s — a model/lexicon change moved this."
                ).that(target.finalScore - rival.finalScore).isAtLeast(FORCED_MARGIN_MIN)
            }
        }
    }

    /**
     * PIN 2 — humanlike traces (geo synthesizer: Bezier corner-cutting, jitter, velocity
     * model; fixed seeds, deterministic) also decode "gorgeous" at rank 1 across all
     * three degradation tiers. Measured 2026-09-03: 17/17 rank 1, top-score ratios over
     * the runner-up between 2.0x (SLOPPY seed 0) and 20x. The tier list and seeds are
     * pinned; if a legitimate engine change moves ONE sloppy seed off rank 1, loosen with
     * a recorded reason rather than deleting the pin.
     */
    @Test
    fun gorgeousIsRankOneOnHumanlikeTraces() {
        assumeOrt()
        val geoLayout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val synth = GeoTraceSynthesizer(GeometricEngineConfig())
        val canvasW = 1000f
        val canvasH = canvasW / geoLayout.aspect
        CtcReplayEngine.build("en").use { engine ->
            var decoded = 0
            for (tier in listOf(
                GeoTraceSynthesizer.Tier.CLEAN,
                GeoTraceSynthesizer.Tier.TYPICAL,
                GeoTraceSynthesizer.Tier.SLOPPY,
            )) {
                for (seed in 0L until 6L) {
                    val trace = synth.synthesize(TARGET, geoLayout, canvasW, canvasH, tier, seed = seed)
                        ?: continue
                    val n = trace.size
                    val x = DoubleArray(n); val y = DoubleArray(n); val t = DoubleArray(n)
                    for (i in 0 until n) {
                        x[i] = (trace[i].x / canvasW).toDouble()
                        y[i] = (trace[i].y / canvasH).toDouble()
                        t[i] = trace[i].tMillis.toDouble()
                    }
                    val slate = engine.decode(x, y, t)
                    decoded++
                    println("[162-h] $tier seed=$seed rank=${slate.words.indexOf(TARGET)} " +
                        "slate=${slate.words.take(5)} scores=${slate.scores.take(5)}")
                    assertWithMessage(
                        "REGRESSION of the issue-#162 gesture class on a humanlike trace " +
                            "($tier seed=$seed): '$TARGET' must be rank 1 but slate was " +
                            "${slate.words}. Run gorgeousDiagnostic for the full picture."
                    ).that(slate.words.firstOrNull()).isEqualTo(TARGET)
                }
            }
            // The synthesizer may skip a seed (null trace); make sure the pin still bites.
            assertWithMessage("humanlike pin must actually decode a meaningful sample")
                .that(decoded).isAtLeast(12)
        }
    }

    private companion object {
        const val TARGET = "gorgeous"

        /** The wrong outputs the #162 reporter observed (v1.5.0, neural-era), a-z-folded. */
        val REPORTED_WINNERS = listOf("gothenburg", "forgetting", "github", "goog", "googlers", "foothold")

        /**
         * Minimum forced-final-score margin of [TARGET] over each reported winner on the
         * canonical trace. Measured margins 2026-09-03 were +3.8..+7.3 (see class KDoc);
         * 1.0 is far below all of them while still guaranteeing strict acoustic preference.
         */
        const val FORCED_MARGIN_MIN = 1.0
    }
}
