package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-5 accuracy harness for the **weird custom layout** (`weird_custom.xml`) — the
 * proof that NO QWERTY assumption leaked into the engine (spec Success Metrics). It is a
 * 4-letter-row, non-uniform-width, per-key/per-row-shifted, scale-renormalized layout
 * that deliberately omits q/x/z (words containing them are untypeable → auto-filtered
 * by the harness's typeable sampler, exercising FR-4).
 *
 * Decode is ALWAYS against the FULL 98,140-word English dictionary — the sample only
 * selects typeable words. Smoke by default; full grid under `-PgeoFull` (spec M25).
 * FINAL floors (this custom layout is ~7 wide with an appended bottom row → not
 * dense, so the QWERTY floors apply without the dense penalty).
 */
class GeoAccuracyWeirdLayoutTest {

    private val layout = GeoLayoutFixtures.loadFixture("weird_custom")
    private val dict = GeoTestFixtures.englishCkdt()
    private val harness = GeoAccuracyHarness(layout, dict, "en/weird")

    @Test
    fun weirdLayout_isNotDead_andHasFourLetterRows() {
        // Sanity: the custom layout must be alive (many English words typeable) despite
        // its odd geometry — proving per-layout vocabulary filtering, not a dead index.
        assertWithMessage("weird custom layout must have letter nodes for a/e/i/o/u etc.")
            .that(layout.letterNodeCount).isGreaterThan(20)
    }

    @Test
    fun smoke_typical_topK_meetsFinalFloors() {
        val n = if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else GeoAccuracyHarness.SMOKE_SAMPLE_SIZE
        val seeds = if (harness.geoFull()) GeoAccuracyHarness.FULL_SEEDS else GeoAccuracyHarness.DEFAULT_SEEDS
        val sample = harness.stratifiedSample(n)
        assertWithMessage("weird layout must yield a non-empty typeable sample")
            .that(sample).isNotEmpty()
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("en/weird TYPICAL top-3 (FINAL smoke floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP3)
        val recall = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("en/weird TYPICAL prune recall (FINAL)")
            .that(recall).isAtLeast(GeoAccuracyThresholds.PruneRecall.TYPICAL)
    }

    @Test
    fun clean_topK_meetsFinalFloors() {
        val n = if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else GeoAccuracyHarness.SMOKE_SAMPLE_SIZE
        val seeds = if (harness.geoFull()) GeoAccuracyHarness.FULL_SEEDS else GeoAccuracyHarness.DEFAULT_SEEDS
        val sample = harness.stratifiedSample(n)
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.CLEAN, seeds)
        assertWithMessage("en/weird CLEAN top-3 (FINAL floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP3)
    }

    @Test
    fun sloppy_underGeoFull() {
        if (!harness.geoFull()) {
            println("[skip] en/weird SLOPPY grid — set -PgeoFull to run")
            return
        }
        val full = harness.stratifiedSample(GeoAccuracyHarness.FULL_SAMPLE_SIZE)
        val sloppy = harness.runGrid(full, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
        assertWithMessage("en/weird SLOPPY top-3 (FINAL, full grid)")
            .that(sloppy.top3).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP3)
    }
}
