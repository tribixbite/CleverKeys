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

    private companion object {
        /**
         * Larger smoke sample for the weird fixture only (see `smoke_typical_topK_meetsFinalFloors`):
         * 100 words × 3 seeds = 300 TYPICAL decodes give a stable ≥ 0.92 (93.3% measured),
         * where the shared 40-word SMOKE_SAMPLE_SIZE straddles the floor by ±1 decode.
         */
        const val WEIRD_SMOKE_SAMPLE_SIZE = 100
    }

    @Test
    fun weirdLayout_isNotDead_andHasFourLetterRows() {
        // Sanity: the custom layout must be alive (many English words typeable) despite
        // its odd geometry — proving per-layout vocabulary filtering, not a dead index.
        assertWithMessage("weird custom layout must have letter nodes for a/e/i/o/u etc.")
            .that(layout.letterNodeCount).isGreaterThan(20)
    }

    @Test
    fun smoke_typical_topK_meetsFinalFloors() {
        // Weird-custom's TYPICAL top-3 sits right around the 0.92 floor on a small sample
        // (the adversarial `scale="7"` grid makes a few words rank-4 borderline), so the
        // 40-word SMOKE_SAMPLE_SIZE is statistically inadequate here: it oscillates 91.7 ↔
        // 93.3% by ±1 decode across sample sizes while the STABLE full-grid value is 94.6%
        // (the 2026-07-20 fix IMPROVED it from 94.1%). Use a larger 100-word smoke for this
        // one fixture so the floor assertion is robust, not noise-gated. (Other layouts keep
        // SMOKE_SAMPLE_SIZE; they clear 0.92 with margin at 40.)
        val n = if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else WEIRD_SMOKE_SAMPLE_SIZE
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

        // ARC-030 — RECALL FIRST, deliberately. This fixture is the PRUNER-limited one
        // (Step-0: recall 80.2% vs the 93.3% QWERTY control), and `endpointInsetKw = 0.30`
        // exists to recover it (→ 87.6% then; 90.3% measured at HEAD 2026-08-28 after the
        // direction channel + cap levers). Until now only top-3 was asserted here, which is
        // the WRONG instrument for a pruner regression twice over: it is bounded by this
        // fixture's intrinsic 74.3% top-5 ceiling, and it cannot distinguish "the true word
        // never reached the scorer" from "the scorer mis-ranked it" — the exact attribution
        // question that drove the whole 2026-07-20 investigation. Measured empirically:
        // zeroing endpointInsetKw drops top-3 to 65.9%, i.e. it clears the 0.66 floor's
        // shoulder by 0.14 pt — one tuning tweak away from silently passing.
        // Asserting recall BEFORE top-3 means a pruner regression is REPORTED as one.
        val recall = harness.pruneRecall(full, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
        println("[arc-030] en/weird SLOPPY prune-recall = ${"%.1f".format(recall * 100)}% " +
            "(floor ${GeoAccuracyThresholds.PruneRecall.WEIRD_SLOPPY})")
        assertWithMessage(
            "en/weird SLOPPY prune recall (documented per-layout fixture floor) — a drop " +
                "here means the endpoint-inset dual-anchor bucketing stopped recovering " +
                "this pruner-limited fixture: the true word is not reaching the scorer at all"
        ).that(recall).isAtLeast(GeoAccuracyThresholds.PruneRecall.WEIRD_SLOPPY)

        // DOCUMENTED PER-LAYOUT FLOOR (research doc §3): the weird-custom fixture is a
        // deliberately hostile, `scale="7"` column-misaligned grid whose SLOPPY top-5
        // CEILING (even a perfect ranker) is 74.3%, so the shared 0.78 top-3 is
        // intrinsically unreachable. The 2026-07-20 fix (inset + direction channel + cap)
        // raised its recall 80.2→87.6% and top-3 64.5→68.5%; this fixture-only floor
        // ratchets to that measured reality. See GeoAccuracyThresholds.Floors.WEIRD_SLOPPY_TOP3.
        assertWithMessage("en/weird SLOPPY top-3 (documented per-layout fixture floor)")
            .that(sloppy.top3).isAtLeast(GeoAccuracyThresholds.Floors.WEIRD_SLOPPY_TOP3)
    }
}
