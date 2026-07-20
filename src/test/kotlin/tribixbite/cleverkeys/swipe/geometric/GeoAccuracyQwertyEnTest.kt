package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Accuracy harness for **English / QWERTY** — one of the two DEFAULT in-suite grids
 * (spec M25): `DEFAULT_SAMPLE_SIZE` stratified words × TYPICAL × `DEFAULT_SEEDS`
 * seeds, decoded against the FULL 98,140-word English dictionary. Under `-PgeoFull`
 * the SAME assertions run at the full-grid size (`FULL_SAMPLE_SIZE` × `FULL_SEEDS`)
 * for every tier, per the spec's full-grid definition.
 *
 * ## FINAL floors (Phase 6 ratchet)
 * The floors asserted here live in [GeoAccuracyThresholds] and are the FINAL
 * Accuracy-Thresholds table (ratcheted from the Phase-5 provisional values; the two
 * documented deviations — SLOPPY top-5 = 0.82 and whole-pruner recall floors — are
 * recorded there and in the spec's As-Built Notes).
 *
 * ## What is asserted
 *  - TYPICAL top-1/3/5 ≥ the FINAL QWERTY floors (the default grid).
 *  - CLEAN top-1/3 ≥ the FINAL CLEAN floors, PLUS the spec's ceiling-relative bound:
 *    CLEAN top-1 ≥ measured ambiguity ceiling − `CEILING_MARGIN` (the ceiling is an
 *    ideal-trace decode over the sample against the full dictionary).
 *  - Prune recall ≥ the FINAL floors at CLEAN + TYPICAL (a top-K miss is attributed
 *    to pruning vs scoring; guards the noisy-endpoint bucket risk).
 *  - Tail-stratum CLEAN top-3 does not trail the top-1k stratum by > the canary gap
 *    (the frequency-prior drowning guard).
 *  - SLOPPY top-1/3/5 + SLOPPY prune recall run only under `-PgeoFull` (heavy grid).
 *
 * Decode is ALWAYS against the full dictionary — the sample only selects WHICH words to
 * type (the ambiguity ceiling is the full lexicon, per spec).
 */
class GeoAccuracyQwertyEnTest {

    private val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val dict = GeoTestFixtures.englishCkdt()
    private val harness = GeoAccuracyHarness(layout, dict, "en/QWERTY")

    // The in-suite stratified sample; escalated to the spec's full grid under -PgeoFull
    // (memoized so CLEAN/TYPICAL/recall/ceiling reuse it).
    private val sampleSize =
        if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else GeoAccuracyHarness.DEFAULT_SAMPLE_SIZE
    private val seeds =
        if (harness.geoFull()) GeoAccuracyHarness.FULL_SEEDS else GeoAccuracyHarness.DEFAULT_SEEDS
    private val sample by lazy { harness.stratifiedSample(sampleSize) }

    @Test
    fun typical_topK_meetsFinalQwertyFloors() {
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("en/QWERTY TYPICAL top-1 (FINAL floor)")
            .that(acc.top1).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP1)
        assertWithMessage("en/QWERTY TYPICAL top-3 (FINAL floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP3)
        assertWithMessage("en/QWERTY TYPICAL top-5 (FINAL floor)")
            .that(acc.top5).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP5)
    }

    @Test
    fun clean_topK_meetsFinalFloors_andCeilingRelativeBound() {
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.CLEAN, seeds)
        assertWithMessage("en/QWERTY CLEAN top-1 (FINAL floor)")
            .that(acc.top1).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP1)
        assertWithMessage("en/QWERTY CLEAN top-3 (FINAL floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP3)
        // Spec Testing Strategy: measure the ambiguity ceiling (ideal-trace decode over
        // the sample vs the FULL dictionary), then assert CLEAN top-1 ≥ ceiling − 3 pts.
        // A projection change that raises template collisions lowers the ceiling AND
        // CLEAN accuracy together; this relative bound catches a scorer/noise regression
        // that the fixed floor alone would mask until accuracy fell 5+ points.
        val ceiling = harness.ambiguityCeiling(sample)
        assertWithMessage("en/QWERTY CLEAN top-1 must be within CEILING_MARGIN of the " +
            "measured ambiguity ceiling (${"%.3f".format(ceiling)})")
            .that(acc.top1).isAtLeast(ceiling - GeoAccuracyThresholds.Floors.CEILING_MARGIN)
    }

    @Test
    fun pruneRecall_meetsFinalFloors_cleanAndTypical() {
        val clean = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.CLEAN, seeds)
        val typical = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("en/QWERTY CLEAN prune recall (FINAL floor)")
            .that(clean).isAtLeast(GeoAccuracyThresholds.PruneRecall.CLEAN)
        assertWithMessage("en/QWERTY TYPICAL prune recall (FINAL floor)")
            .that(typical).isAtLeast(GeoAccuracyThresholds.PruneRecall.TYPICAL)
    }

    @Test
    fun tailStratum_doesNotDrownBelowHead_priorCanary() {
        val (headTop3, tailTop3) = harness.tailCanary(sample, seeds)
        val gap = headTop3 - tailTop3
        println("[tail-canary] en/QWERTY head-top3=${"%.3f".format(headTop3)} " +
            "tail-top3=${"%.3f".format(tailTop3)} gap=${"%.3f".format(gap)}")
        assertWithMessage("en/QWERTY tail CLEAN top-3 must not trail the head by > the canary gap " +
            "(prior-drowning guard)")
            .that(gap).isAtMost(GeoAccuracyThresholds.TAIL_CANARY_MAX_GAP_PTS)
    }

    @Test
    fun sloppy_fullGrid_underGeoFull() {
        // SLOPPY is the heaviest tier; run only under -PgeoFull so the default suite
        // stays under budget (spec M25). Asserts the FULL FINAL row: top-1/3/5 + the
        // SLOPPY prune-recall floor (previously-dead constants, now enforced).
        if (!harness.geoFull()) {
            println("[skip] en/QWERTY SLOPPY grid — set -PgeoFull to run")
            return
        }
        val sloppy = harness.runGrid(sample, GeoTraceSynthesizer.Tier.SLOPPY, seeds)
        assertWithMessage("en/QWERTY SLOPPY top-1 (FINAL floor, full grid)")
            .that(sloppy.top1).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP1)
        assertWithMessage("en/QWERTY SLOPPY top-3 (FINAL floor, full grid)")
            .that(sloppy.top3).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP3)
        assertWithMessage("en/QWERTY SLOPPY top-5 (FINAL floor, full grid)")
            .that(sloppy.top5).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP5)
        val recall = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.SLOPPY, seeds)
        assertWithMessage("en/QWERTY SLOPPY prune recall (FINAL floor, full grid)")
            .that(recall).isAtLeast(GeoAccuracyThresholds.PruneRecall.SLOPPY)
    }
}
