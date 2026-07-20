package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-5 accuracy harness for **French / AZERTY** — a NON-default layout: it runs a
 * small smoke grid on every `runPureTests` (so the suite stays under budget) and the
 * full 500×K=5 grid only under `-PgeoFull` (spec M25). Floors are the FINAL
 * Accuracy-Thresholds table (non-QWERTY penalty applied).
 *
 * Decode is ALWAYS against the FULL French dictionary (`fr_enhanced.bin`, CKDT).
 * Exercises the tier-3 NFD accent recovery (é→e-key) that French relies on heavily.
 */
class GeoAccuracyAzertyFrTest {

    private val layout = GeoLayoutFixtures.loadShipped("latn_azerty_fr")
    private val dict = GeoTestFixtures.frenchCkdt()
    private val harness = GeoAccuracyHarness(layout, dict, "fr/AZERTY")
    private val p = GeoAccuracyThresholds.NON_QWERTY_PENALTY

    @Test
    fun smoke_typical_topK_meetsFinalFloors() {
        val n = if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else GeoAccuracyHarness.SMOKE_SAMPLE_SIZE
        val seeds = if (harness.geoFull()) GeoAccuracyHarness.FULL_SEEDS else GeoAccuracyHarness.DEFAULT_SEEDS
        val sample = harness.stratifiedSample(n)
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("fr/AZERTY TYPICAL top-3 (FINAL smoke floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP3 - p)
        // Prune recall attributes any top-K miss to pruning vs scoring.
        val recall = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("fr/AZERTY TYPICAL prune recall (FINAL)")
            .that(recall).isAtLeast(GeoAccuracyThresholds.PruneRecall.TYPICAL - p)
    }

    @Test
    fun clean_topK_meetsFinalFloors() {
        val n = if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else GeoAccuracyHarness.SMOKE_SAMPLE_SIZE
        val seeds = if (harness.geoFull()) GeoAccuracyHarness.FULL_SEEDS else GeoAccuracyHarness.DEFAULT_SEEDS
        val sample = harness.stratifiedSample(n)
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.CLEAN, seeds)
        assertWithMessage("fr/AZERTY CLEAN top-3 (FINAL floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP3 - p)
    }

    @Test
    fun sloppy_underGeoFull() {
        if (!harness.geoFull()) {
            println("[skip] fr/AZERTY SLOPPY grid — set -PgeoFull to run")
            return
        }
        val full = harness.stratifiedSample(GeoAccuracyHarness.FULL_SAMPLE_SIZE)
        val sloppy = harness.runGrid(full, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
        assertWithMessage("fr/AZERTY SLOPPY top-3 (FINAL, full grid)")
            .that(sloppy.top3).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP3 - p)
    }
}
