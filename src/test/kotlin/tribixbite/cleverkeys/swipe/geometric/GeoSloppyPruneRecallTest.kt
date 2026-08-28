package tribixbite.cleverkeys.swipe.geometric

import org.junit.Test

/**
 * STEP-0 MEASUREMENT (research doc `docs/history/audits/2026-07-20-geo-sloppy-research.md` §0):
 * decompose the SLOPPY top-3 shortfall on the two failing layouts (en/Dvorak,
 * en/weird-custom) into a **pruning loss** (the true word never reaches the scorer)
 * vs a **scoring loss** (the true word is scored but out-ranked) — with en/QWERTY as a
 * passing control.
 *
 * The four research lenses disagreed on *how much* of each layout's gap is pruner vs
 * scorer, and that split decides the fix (endpoint-inset bucketing vs location tunnel).
 * The disagreement is real but was never measured: the SLOPPY test path calls only
 * [GeoAccuracyHarness.runGrid], never [GeoAccuracyHarness.pruneRecall] at SLOPPY. This
 * test closes that instrumentation gap PERMANENTLY (research doc §4 "instrumentation
 * debt") using only existing public harness APIs — NO engine or harness source edits.
 *
 * ## Decision rule (research doc §0 / `failure` §6)
 *  - weird_custom SLOPPY recall ≈ 65% → pruning-dominated → Step 1a (endpoint-inset
 *    dual-anchor bucketing) is the fix; no scorer change can reach 0.78 without it.
 *  - weird_custom recall ≈ 95% → scorer-dominated → skip 1a, go to the direction channel.
 *  - Dvorak recall ≈ 95% (predicted — its top-5 is healthy) → confirms Dvorak needs the
 *    Step 1b location tunnel (a within-shortlist reordering fix), not pruning.
 *
 * ## Gating
 * `-PgeoFull`-gated (heavy: full 500-word × K=5 SLOPPY grid against the full 98k
 * dictionary per layout). Without the flag it prints a skip line and passes, so it never
 * burdens the default `runPureTests` budget. It asserts nothing (it is a *measurement*,
 * not a floor) — the numbers it prints drive the fix decision recorded in the spec's
 * As-Built Notes and [GeoAccuracyThresholds].
 *
 * Carries the `Test` suffix (it IS a JUnit measurement class) and is registered in
 * `build.gradle` `pureTestClasses` (mandatory — `TestRunnerListDriftTest` fails otherwise).
 * Purity (NFR-3): `kotlin.*` + the pure engine + the test harness only.
 */
class GeoSloppyPruneRecallTest {

    private val enDict = GeoTestFixtures.englishCkdt()

    private val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val dvorak = GeoLayoutFixtures.loadShipped("latn_dvorak")
    private val weird = GeoLayoutFixtures.loadFixture("weird_custom")

    /**
     * Measure SLOPPY whole-pruner recall on Dvorak + weird-custom (the two failing
     * layouts) and QWERTY (the passing control), each against the FULL English
     * dictionary, at the full-grid sample/seed count. Also breaks Dvorak's SLOPPY
     * top-K out by length stratum to confirm (or refute) `failure`'s claim that its loss
     * concentrates in SHORT (2–3 letter, same-row) words — the location-tunnel signature.
     */
    @Test
    fun measure_sloppyPruneRecall_decomposition() {
        val harness = GeoAccuracyHarness(qwerty, enDict, "en/QWERTY")
        if (!harness.geoFull()) {
            println("[skip] GeoSloppyPruneRecallTest — set -PgeoFull to measure SLOPPY prune-recall")
            return
        }

        // One harness per layout (each owns its own warmed index). Decode/prune are always
        // against the full 98k lexicon; the sample only selects WHICH words to type.
        val layouts = listOf(
            Triple("en/QWERTY", qwerty, "control (passing: SLOPPY top-3 79.5%)"),
            Triple("en/Dvorak", dvorak, "failing: SLOPPY top-3 75.8% — top-5 healthy (81.6%), reordering signature"),
            Triple("en/weird", weird, "failing: SLOPPY top-3 64.5% — top-5 low (69.6%), pruning signature"),
        )

        println("=== STEP-0 SLOPPY prune-recall decomposition (full grid, en dict) ===")
        for ((label, layout, note) in layouts) {
            val h = GeoAccuracyHarness(layout, enDict, label)
            val sample = h.stratifiedSample(GeoAccuracyHarness.FULL_SAMPLE_SIZE)
            // Whole-pruner final-shortlist recall at SLOPPY — the true attribution signal.
            val recall = h.pruneRecall(sample, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
            println("[step0] $label SLOPPY prune-recall = ${"%.1f".format(recall * 100)}%  ($note)")
        }

        // Dvorak length-stratum breakdown: `failure` §6 predicts its SLOPPY loss lives in
        // SHORT (2–3 letter) same-row words (all five Dvorak vowels are home-row-adjacent),
        // where the shape channel is faded out and disambiguation rests on the location
        // channel — the location-tunnel (Step 1b) target. A healthy MID/LONG top-3 with a
        // depressed SHORT top-3 confirms a scorer-reordering (not a pruning) loss.
        val dvorakH = GeoAccuracyHarness(dvorak, enDict, "en/Dvorak")
        val dvorakSample = dvorakH.stratifiedSample(GeoAccuracyHarness.FULL_SAMPLE_SIZE)
        println("--- en/Dvorak SLOPPY top-K by length stratum (scorer-vs-pruner shape check) ---")
        for (stratum in GeoAccuracyHarness.LenStratum.values()) {
            val slice = dvorakSample.filter { it.lenStratum == stratum }
            if (slice.isEmpty()) {
                println("[step0] en/Dvorak SLOPPY len=$stratum — no sampled words")
                continue
            }
            val acc = dvorakH.runGrid(slice, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
            val recall = dvorakH.pruneRecall(slice, GeoTraceSynthesizer.Tier.SLOPPY, GeoAccuracyHarness.FULL_SEEDS)
            println("[step0] en/Dvorak SLOPPY len=$stratum n=${acc.decodes} " +
                "top1=${"%.1f".format(acc.top1 * 100)}% top3=${"%.1f".format(acc.top3 * 100)}% " +
                "top5=${"%.1f".format(acc.top5 * 100)}% recall=${"%.1f".format(recall * 100)}%")
        }
        println("=== STEP-0 measurement complete — apply the research doc §0 decision rule ===")
    }
}
