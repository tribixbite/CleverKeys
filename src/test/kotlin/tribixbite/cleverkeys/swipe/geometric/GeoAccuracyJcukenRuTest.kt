package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-5 accuracy harness for **Russian / ЙЦУКЕН** — the second DEFAULT in-suite grid
 * (spec M25) and `ROADMAP.md:56`'s first geometric-swipe target: prove Russian is
 * demonstrably decodable against the FULL 50k ru lexicon before any wiring.
 *
 * Non-QWERTY layouts start `NON_QWERTY_PENALTY` points below the QWERTY floors (spec:
 * ЙЦУКЕН packs 31 letters in the same width ⇒ smaller kw ⇒ relatively noisier). The
 * floors here are still PROVISIONAL (Phase 6 ratchets to the FINAL table).
 *
 * Same assertions as the English grid: TYPICAL top-K, CLEAN top-K, prune recall,
 * tail-canary; SLOPPY + full grid behind `-PgeoFull`. Decode is ALWAYS against the full
 * dictionary (`dictionary.size ≥ 50_000` is guaranteed by the CKDT fixture loader).
 */
class GeoAccuracyJcukenRuTest {

    private val layout = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
    private val dict = GeoTestFixtures.russianCkdt()
    private val harness = GeoAccuracyHarness(layout, dict, "ru/JCUKEN")

    private val sample by lazy { harness.stratifiedSample(GeoAccuracyHarness.DEFAULT_SAMPLE_SIZE) }

    // Non-QWERTY floors: the QWERTY provisional floors minus the dense-layout penalty.
    private val p = GeoAccuracyThresholds.NON_QWERTY_PENALTY

    @Test
    fun ruLexicon_isFullSize_notThe5kUnigramStub() {
        // Guard M12: the ru harness must load dictionary.bin (50k), never unigrams.txt (5k).
        assertWithMessage("ru dictionary must be the full 50k CKDT, not the 5k unigram stub")
            .that(dict.size).isAtLeast(50_000)
    }

    @Test
    fun typical_topK_meetsProvisionalNonQwertyFloors() {
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.TYPICAL, GeoAccuracyHarness.DEFAULT_SEEDS)
        assertWithMessage("ru/JCUKEN TYPICAL top-1 (PROVISIONAL, non-QWERTY)")
            .that(acc.top1).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP1 - p)
        assertWithMessage("ru/JCUKEN TYPICAL top-3 (PROVISIONAL, non-QWERTY)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP3 - p)
        assertWithMessage("ru/JCUKEN TYPICAL top-5 (PROVISIONAL, non-QWERTY)")
            .that(acc.top5).isAtLeast(GeoAccuracyThresholds.Floors.TYPICAL_TOP5 - p)
    }

    @Test
    fun clean_topK_meetsProvisionalNonQwertyFloors() {
        val acc = harness.runGrid(sample, GeoTraceSynthesizer.Tier.CLEAN, GeoAccuracyHarness.DEFAULT_SEEDS)
        assertWithMessage("ru/JCUKEN CLEAN top-1 (PROVISIONAL, non-QWERTY)")
            .that(acc.top1).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP1 - p)
        assertWithMessage("ru/JCUKEN CLEAN top-3 (PROVISIONAL, non-QWERTY)")
            .that(acc.top3).isAtLeast(GeoAccuracyThresholds.Floors.CLEAN_TOP3 - p)
    }

    @Test
    fun pruneRecall_meetsProvisionalFloors_cleanAndTypical() {
        val clean = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.CLEAN, GeoAccuracyHarness.DEFAULT_SEEDS)
        val typical = harness.pruneRecall(sample, GeoTraceSynthesizer.Tier.TYPICAL, GeoAccuracyHarness.DEFAULT_SEEDS)
        assertWithMessage("ru/JCUKEN CLEAN prune recall (PROVISIONAL)")
            .that(clean).isAtLeast(GeoAccuracyThresholds.PruneRecall.CLEAN - p)
        assertWithMessage("ru/JCUKEN TYPICAL prune recall (PROVISIONAL)")
            .that(typical).isAtLeast(GeoAccuracyThresholds.PruneRecall.TYPICAL - p)
    }

    @Test
    fun tailStratum_doesNotDrownBelowHead_priorCanary() {
        val (headTop3, tailTop3) = harness.tailCanary(sample, GeoAccuracyHarness.DEFAULT_SEEDS)
        val gap = headTop3 - tailTop3
        println("[tail-canary] ru/JCUKEN head-top3=${"%.3f".format(headTop3)} " +
            "tail-top3=${"%.3f".format(tailTop3)} gap=${"%.3f".format(gap)}")
        assertWithMessage("ru/JCUKEN tail CLEAN top-3 must not trail the head by > the canary gap")
            .that(gap).isAtMost(GeoAccuracyThresholds.TAIL_CANARY_MAX_GAP_PTS)
    }

    @Test
    fun sloppy_and_fullGrid_underGeoFull() {
        if (!harness.geoFull()) {
            println("[skip] ru/JCUKEN SLOPPY + full grid — set -PgeoFull to run")
            return
        }
        val full = harness.stratifiedSample(GeoAccuracyHarness.FULL_SAMPLE_SIZE)
        val sloppy = harness.runGrid(full, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
        assertWithMessage("ru/JCUKEN SLOPPY top-3 (PROVISIONAL, full grid)")
            .that(sloppy.top3).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP3 - p)
        assertWithMessage("ru/JCUKEN SLOPPY top-5 (PROVISIONAL, full grid)")
            .that(sloppy.top5).isAtLeast(GeoAccuracyThresholds.Floors.SLOPPY_TOP5 - p)
    }
}
