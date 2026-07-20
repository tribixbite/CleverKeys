package tribixbite.cleverkeys.swipe.geometric

/**
 * PROVISIONAL accuracy floors for the Phase-5 synthetic harness.
 *
 * ## Why PROVISIONAL
 * These floors deliberately prove **signal, not quality**: Phase 5 runs against
 * UNTUNED σ/λ defaults, so the numbers here are conservative pass-lines chosen so the
 * suite goes green with the default [GeometricEngineConfig] while still catching a
 * genuinely broken pipeline (e.g. a projection regression that drops every accented
 * word, a scorer that never ranks the true word, or a pruner that loses recall).
 *
 * Phase 6 TUNES the constants against this harness and then **ratchets** these floors
 * to the FINAL Accuracy-Thresholds table (spec Testing Strategy) and removes the
 * PROVISIONAL marker. Do NOT treat these as the shipping quality bar.
 *
 * Carries no `Test` suffix so `TestRunnerListDriftTest` skips it (NFR-3 test hygiene).
 */
object GeoAccuracyThresholds {

    /** Marker so grep/tests can assert Phase 5 has not accidentally shipped as final. */
    const val PROVISIONAL = true

    /**
     * Per-tier top-K floors (fractions in [0,1]). PROVISIONAL — well below the FINAL
     * table so untuned defaults pass while a broken pipeline (top-K ≈ 0) fails loudly.
     *
     * FINAL (Phase 6) for reference: CLEAN top-3 ≥ 97%; TYPICAL top-1 ≥ 78 / top-3 ≥ 92
     * / top-5 ≥ 95; SLOPPY top-1 ≥ 55 / top-3 ≥ 78 / top-5 ≥ 85.
     */
    object Floors {
        // CLEAN: near-ideal traces; the decoder should nail these even untuned.
        const val CLEAN_TOP1 = 0.70
        const val CLEAN_TOP3 = 0.85

        // TYPICAL: the spec's explicit Phase-5 example floor ("TYPICAL top-3 ≥ 60% —
        // proves signal, not quality").
        const val TYPICAL_TOP1 = 0.45
        const val TYPICAL_TOP3 = 0.60
        const val TYPICAL_TOP5 = 0.65

        // SLOPPY: heavy noise; a much lower provisional bar (still non-trivial).
        const val SLOPPY_TOP1 = 0.25
        const val SLOPPY_TOP3 = 0.45
        const val SLOPPY_TOP5 = 0.55
    }

    /**
     * Non-QWERTY layouts start `NON_QWERTY_PENALTY` points below the QWERTY floors
     * (spec: "same floors − 3 pts initially" — JCUKEN packs 31 letters in the same
     * width ⇒ smaller kw ⇒ relatively noisier). We use a slightly larger provisional
     * cushion so the untuned dense-layout run is not flaky.
     */
    const val NON_QWERTY_PENALTY = 0.05

    /**
     * Per-stage prune-recall floors (the true word survives each prune stage). These
     * attribute a top-K miss to pruning vs scoring: if recall is high but top-K is low,
     * the SCORER is at fault, not the pruner (guards the dense-layout noisy-endpoint
     * bucket risk). PROVISIONAL — the FINAL table is CLEAN ≥ 99.5 / TYPICAL ≥ 99 /
     * SLOPPY ≥ 97 per stage; here we assert the surviving-in-final-shortlist recall,
     * which is looser than per-stage but the strongest thing measurable without
     * instrumenting internal stages from a test.
     */
    object PruneRecall {
        const val CLEAN = 0.97
        const val TYPICAL = 0.90
        const val SLOPPY = 0.75
    }

    /**
     * Tail-stratum prior-drowning canary (spec): the tail stratum's CLEAN top-3 may not
     * trail the top-1k stratum's CLEAN top-3 by more than this many points — proving the
     * frequency prior (`λ_f·ln(1+rank)`) does not drown reachable tail words.
     */
    const val TAIL_CANARY_MAX_GAP_PTS = 0.15
}
