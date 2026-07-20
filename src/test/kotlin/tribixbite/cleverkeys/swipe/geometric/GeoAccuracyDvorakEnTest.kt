package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-5 accuracy harness for **English / Dvorak** — a NON-default LATIN layout with a
 * radically different key arrangement (smoke by default; full grid under `-PgeoFull`,
 * spec M25). Proves the engine carries NO QWERTY geometry assumption: the same English
 * dictionary decodes on a completely different centroid map. PROVISIONAL floors; Phase 6
 * ratchets to the FINAL table.
 *
 * Dvorak is a standard 10-column Latin layout (kw ≈ QWERTY), so it uses the QWERTY
 * provisional floors WITHOUT the dense-layout penalty. Decode is ALWAYS against the FULL
 * 98,140-word English dictionary.
 */
class GeoAccuracyDvorakEnTest {

    private val layout = GeoLayoutFixtures.loadShipped("latn_dvorak")
    private val dict = GeoTestFixtures.englishCkdt()
    private val harness = GeoAccuracyHarness(layout, dict, "en/Dvorak")

    @Test
    fun smoke_typical_topK_meetsProvisionalFloors() {
        val n = if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else GeoAccuracyHarness.SMOKE_SAMPLE_SIZE
        val seeds = if (harness.geoFull()) GeoAccuracyHarness.FULL_SEEDS else GeoAccuracyHarness.DEFAULT_SEEDS
        val sample = harness.stratifiedSample(n)
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("en/Dvorak TYPICAL top-3 (PROVISIONAL smoke floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP3)
        val recall = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("en/Dvorak TYPICAL prune recall (PROVISIONAL)")
            .that(recall).isAtLeast(GeoAccuracyThresholds.PruneRecall.TYPICAL)
    }

    @Test
    fun clean_topK_meetsProvisionalFloors() {
        val sample = harness.stratifiedSample(GeoAccuracyHarness.SMOKE_SAMPLE_SIZE)
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.CLEAN, GeoAccuracyHarness.DEFAULT_SEEDS)
        assertWithMessage("en/Dvorak CLEAN top-3 (PROVISIONAL floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP3)
    }

    @Test
    fun sloppy_underGeoFull() {
        if (!harness.geoFull()) {
            println("[skip] en/Dvorak SLOPPY grid — set -PgeoFull to run")
            return
        }
        val full = harness.stratifiedSample(GeoAccuracyHarness.FULL_SAMPLE_SIZE)
        val sloppy = harness.runGrid(full, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
        assertWithMessage("en/Dvorak SLOPPY top-3 (PROVISIONAL, full grid)")
            .that(sloppy.top3).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP3)
    }
}
