package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-5 accuracy harness for **German / QWERTZ** — a NON-default layout (smoke by
 * default; full grid under `-PgeoFull`, spec M25). FINAL floors with the
 * non-QWERTY penalty (Phase-6 ratchet).
 *
 * Decode is ALWAYS against the FULL German dictionary (`de_enhanced.bin`, CKDT), which
 * holds the longest real word in the fixture set ("nationalsozialistischen", ~85.6 kw)
 * — a stress on the length-ratio prune + the u16 path-length range.
 */
class GeoAccuracyQwertzDeTest {

    private val layout = GeoLayoutFixtures.loadShipped("latn_qwertz_de")
    private val dict = GeoTestFixtures.germanCkdt()
    private val harness = GeoAccuracyHarness(layout, dict, "de/QWERTZ")
    private val p = GeoAccuracyThresholds.NON_QWERTY_PENALTY

    @Test
    fun smoke_typical_topK_meetsFinalFloors() {
        val n = if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else GeoAccuracyHarness.SMOKE_SAMPLE_SIZE
        val seeds = if (harness.geoFull()) GeoAccuracyHarness.FULL_SEEDS else GeoAccuracyHarness.DEFAULT_SEEDS
        val sample = harness.stratifiedSample(n)
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("de/QWERTZ TYPICAL top-3 (FINAL smoke floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP3 - p)
        val recall = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("de/QWERTZ TYPICAL prune recall (FINAL)")
            .that(recall).isAtLeast(GeoAccuracyThresholds.PruneRecall.TYPICAL - p)
    }

    @Test
    fun clean_topK_meetsFinalFloors() {
        val n = if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else GeoAccuracyHarness.SMOKE_SAMPLE_SIZE
        val seeds = if (harness.geoFull()) GeoAccuracyHarness.FULL_SEEDS else GeoAccuracyHarness.DEFAULT_SEEDS
        val sample = harness.stratifiedSample(n)
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.CLEAN, seeds)
        assertWithMessage("de/QWERTZ CLEAN top-3 (FINAL floor)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP3 - p)
    }

    @Test
    fun sloppy_underGeoFull() {
        if (!harness.geoFull()) {
            println("[skip] de/QWERTZ SLOPPY grid — set -PgeoFull to run")
            return
        }
        val full = harness.stratifiedSample(GeoAccuracyHarness.FULL_SAMPLE_SIZE)
        val sloppy = harness.runGrid(full, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
        assertWithMessage("de/QWERTZ SLOPPY top-3 (FINAL, full grid)")
            .that(sloppy.top3).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP3 - p)
    }
}
