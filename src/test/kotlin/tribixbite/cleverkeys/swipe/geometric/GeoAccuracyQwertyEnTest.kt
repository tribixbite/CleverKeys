package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-5 accuracy harness for **English / QWERTY** — one of the two DEFAULT in-suite
 * grids (spec M25): `DEFAULT_SAMPLE_SIZE` stratified words × TYPICAL × `DEFAULT_SEEDS`
 * seeds, decoded against the FULL 98,140-word English dictionary.
 *
 * ## PROVISIONAL floors (spec Phase 5 / M24)
 * The floors asserted here live in [GeoAccuracyThresholds] and are deliberately
 * conservative — they prove SIGNAL (the pipeline decodes real words at real ranks
 * under untuned σ/λ), not shipping QUALITY. Phase 6 tunes the constants and ratchets
 * these to the FINAL Accuracy-Thresholds table.
 *
 * ## What is asserted
 *  - TYPICAL top-1/3/5 ≥ the provisional QWERTY floors (the default grid).
 *  - CLEAN top-1/3 ≥ the provisional CLEAN floors (near-ideal traces — the strongest
 *    signal; if these fail the scorer is broken).
 *  - Per-stage PRUNE RECALL ≥ the provisional recall floor at CLEAN + TYPICAL (a top-K
 *    miss is attributed to pruning vs scoring; guards the noisy-endpoint bucket risk).
 *  - Tail-stratum CLEAN top-3 does not trail the top-1k stratum by > the canary gap
 *    (the frequency-prior drowning guard).
 *  - SLOPPY floors + the full 500×K=5 grid run only under `-PgeoFull`.
 *
 * Decode is ALWAYS against the full dictionary — the sample only selects WHICH words to
 * type (the ambiguity ceiling is the full lexicon, per spec).
 */
class GeoAccuracyQwertyEnTest {

    private val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val dict = GeoTestFixtures.englishCkdt()
    private val harness = GeoAccuracyHarness(layout, dict, "en/QWERTY")

    // The default in-suite stratified sample (memoized so CLEAN/TYPICAL/recall reuse it).
    private val sample by lazy { harness.stratifiedSample(GeoAccuracyHarness.DEFAULT_SAMPLE_SIZE) }

    @Test
    fun typical_topK_meetsProvisionalQwertyFloors() {
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.TYPICAL, GeoAccuracyHarness.DEFAULT_SEEDS)
        assertWithMessage("en/QWERTY TYPICAL top-1 (PROVISIONAL floor)")
            .that(acc.top1).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP1)
        assertWithMessage("en/QWERTY TYPICAL top-3 (PROVISIONAL floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP3)
        assertWithMessage("en/QWERTY TYPICAL top-5 (PROVISIONAL floor)")
            .that(acc.top5).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP5)
    }

    @Test
    fun clean_topK_meetsProvisionalFloors() {
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.CLEAN, GeoAccuracyHarness.DEFAULT_SEEDS)
        assertWithMessage("en/QWERTY CLEAN top-1 (PROVISIONAL floor)")
            .that(acc.top1).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP1)
        assertWithMessage("en/QWERTY CLEAN top-3 (PROVISIONAL floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP3)
    }

    @Test
    fun pruneRecall_meetsProvisionalFloors_cleanAndTypical() {
        val clean = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.CLEAN, GeoAccuracyHarness.DEFAULT_SEEDS)
        val typical = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.TYPICAL, GeoAccuracyHarness.DEFAULT_SEEDS)
        assertWithMessage("en/QWERTY CLEAN prune recall (PROVISIONAL)")
            .that(clean).isAtLeast(GeoAccuracyThresholds.PruneRecall.CLEAN)
        assertWithMessage("en/QWERTY TYPICAL prune recall (PROVISIONAL)")
            .that(typical).isAtLeast(GeoAccuracyThresholds.PruneRecall.TYPICAL)
    }

    @Test
    fun tailStratum_doesNotDrownBelowHead_priorCanary() {
        val (headTop3, tailTop3) = harness.tailCanary(sample, GeoAccuracyHarness.DEFAULT_SEEDS)
        val gap = headTop3 - tailTop3
        println("[tail-canary] en/QWERTY head-top3=${"%.3f".format(headTop3)} " +
            "tail-top3=${"%.3f".format(tailTop3)} gap=${"%.3f".format(gap)}")
        assertWithMessage("en/QWERTY tail CLEAN top-3 must not trail the head by > the canary gap " +
            "(prior-drowning guard)")
            .that(gap).isAtMost(GeoAccuracyThresholds.TAIL_CANARY_MAX_GAP_PTS)
    }

    @Test
    fun sloppy_and_fullGrid_underGeoFull() {
        // SLOPPY + the full 500×K=5 grid are heavy; run only under -PgeoFull so the
        // default suite stays under budget (spec M25).
        if (!harness.geoFull()) {
            println("[skip] en/QWERTY SLOPPY + full grid — set -PgeoFull to run")
            return
        }
        val full = harness.stratifiedSample(GeoAccuracyHarness.FULL_SAMPLE_SIZE)
        val sloppy = harness.runGrid(full, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
        assertWithMessage("en/QWERTY SLOPPY top-3 (PROVISIONAL floor, full grid)")
            .that(sloppy.top3).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP3)
        assertWithMessage("en/QWERTY SLOPPY top-5 (PROVISIONAL floor, full grid)")
            .that(sloppy.top5).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP5)
    }
}
