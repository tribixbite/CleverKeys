package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Accuracy harness for **Russian / ЙЦУКЕН** — the second DEFAULT in-suite grid
 * (spec M25) and `ROADMAP.md:56`'s first geometric-swipe target: prove Russian is
 * demonstrably decodable against the FULL 50k ru lexicon before any wiring. Under
 * `-PgeoFull` the SAME assertions run at the full-grid size for every tier.
 *
 * Non-QWERTY layouts start `NON_QWERTY_PENALTY` points below the QWERTY floors (spec:
 * ЙЦУКЕН packs 31 letters in the same width ⇒ smaller kw ⇒ relatively noisier). The
 * floors are the FINAL Accuracy-Thresholds table (Phase-6 ratchet; deviations recorded
 * in [GeoAccuracyThresholds] and the spec's As-Built Notes).
 *
 * Same assertions as the English grid: TYPICAL top-K, CLEAN top-K + the
 * ceiling-relative CLEAN top-1 bound, prune recall, tail-canary; SLOPPY (top-1/3/5 +
 * recall) behind `-PgeoFull`. Decode is ALWAYS against the full dictionary
 * (`dictionary.size ≥ 50_000` is guaranteed by the CKDT fixture loader).
 */
class GeoAccuracyJcukenRuTest {

    private val layout = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
    private val dict = GeoTestFixtures.russianCkdt()
    private val harness = GeoAccuracyHarness(layout, dict, "ru/JCUKEN")

    private val sampleSize =
        if (harness.geoFull()) GeoAccuracyHarness.FULL_SAMPLE_SIZE else GeoAccuracyHarness.DEFAULT_SAMPLE_SIZE
    private val seeds =
        if (harness.geoFull()) GeoAccuracyHarness.FULL_SEEDS else GeoAccuracyHarness.DEFAULT_SEEDS
    private val sample by lazy { harness.stratifiedSample(sampleSize) }

    // Non-QWERTY floors: the QWERTY FINAL floors minus the dense-layout penalty.
    private val p = GeoAccuracyThresholds.NON_QWERTY_PENALTY

    @Test
    fun ruLexicon_isFullSize_notThe5kUnigramStub() {
        // Guard M12: the ru harness must load dictionary.bin (50k), never unigrams.txt (5k).
        assertWithMessage("ru dictionary must be the full 50k CKDT, not the 5k unigram stub")
            .that(dict.size).isAtLeast(50_000)
    }

    @Test
    fun typical_topK_meetsFinalNonQwertyFloors() {
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("ru/JCUKEN TYPICAL top-1 (FINAL, non-QWERTY)")
            .that(acc.top1).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP1 - p)
        assertWithMessage("ru/JCUKEN TYPICAL top-3 (FINAL, non-QWERTY)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP3 - p)
        assertWithMessage("ru/JCUKEN TYPICAL top-5 (FINAL, non-QWERTY)")
            .that(acc.top5).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP5 - p)
    }

    @Test
    fun clean_topK_meetsFinalNonQwertyFloors_andCeilingRelativeBound() {
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.CLEAN, seeds)
        assertWithMessage("ru/JCUKEN CLEAN top-1 (FINAL, non-QWERTY)")
            .that(acc.top1).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP1 - p)
        assertWithMessage("ru/JCUKEN CLEAN top-3 (FINAL, non-QWERTY)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP3 - p)
        // Spec Testing Strategy: CLEAN top-1 ≥ measured ambiguity ceiling − 3 pts
        // (the ceiling is an ideal-trace decode over the sample vs the full lexicon).
        val ceiling = harness.ambiguityCeiling(sample)
        assertWithMessage("ru/JCUKEN CLEAN top-1 must be within CEILING_MARGIN of the " +
            "measured ambiguity ceiling (${"%.3f".format(ceiling)})")
            .that(acc.top1).isAtLeast(ceiling - GeoAccuracyThresholds.Floors.CEILING_MARGIN)
    }

    @Test
    fun pruneRecall_meetsFinalFloors_cleanAndTypical() {
        val clean = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.CLEAN, seeds)
        val typical = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.TYPICAL, seeds)
        assertWithMessage("ru/JCUKEN CLEAN prune recall (FINAL)")
            .that(clean).isAtLeast(GeoAccuracyThresholds.PruneRecall.CLEAN - p)
        assertWithMessage("ru/JCUKEN TYPICAL prune recall (FINAL)")
            .that(typical).isAtLeast(GeoAccuracyThresholds.PruneRecall.TYPICAL - p)
    }

    @Test
    fun tailStratum_doesNotDrownBelowHead_priorCanary() {
        val (headTop3, tailTop3) = harness.tailCanary(sample, seeds)
        val gap = headTop3 - tailTop3
        println("[tail-canary] ru/JCUKEN head-top3=${"%.3f".format(headTop3)} " +
            "tail-top3=${"%.3f".format(tailTop3)} gap=${"%.3f".format(gap)}")
        assertWithMessage("ru/JCUKEN tail CLEAN top-3 must not trail the head by > the canary gap")
            .that(gap).isAtMost(GeoAccuracyThresholds.TAIL_CANARY_MAX_GAP_PTS)
    }

    @Test
    fun sloppy_fullGrid_underGeoFull() {
        if (!harness.geoFull()) {
            println("[skip] ru/JCUKEN SLOPPY grid — set -PgeoFull to run")
            return
        }
        val sloppy = harness.runGrid(sample, GeoTraceSynthesizer.Tier.SLOPPY, seeds)
        assertWithMessage("ru/JCUKEN SLOPPY top-1 (FINAL, non-QWERTY, full grid)")
            .that(sloppy.top1).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP1 - p)
        assertWithMessage("ru/JCUKEN SLOPPY top-3 (FINAL, non-QWERTY, full grid)")
            .that(sloppy.top3).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP3 - p)
        assertWithMessage("ru/JCUKEN SLOPPY top-5 (FINAL, non-QWERTY, full grid)")
            .that(sloppy.top5).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP5 - p)
        val recall = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.SLOPPY, seeds)
        assertWithMessage("ru/JCUKEN SLOPPY prune recall (FINAL, non-QWERTY, full grid)")
            .that(recall).isAtLeast(GeoAccuracyThresholds.PruneRecall.SLOPPY - p)
    }
}
