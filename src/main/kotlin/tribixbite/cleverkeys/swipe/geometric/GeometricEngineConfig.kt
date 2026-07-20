package tribixbite.cleverkeys.swipe.geometric

/**
 * All tunable knobs for the geometric swipe-decoding engine.
 *
 * This is a plain, immutable data class: the engine core NEVER reads
 * SharedPreferences (NFR-3 purity). Any future calibration flow (see the
 * grade-a-roadmap `SwipeCalibrationActivity` precedent) constructs and passes a
 * populated [GeometricEngineConfig] instance instead of mutating global state,
 * which keeps decode() deterministic (NFR-4).
 *
 * Unit conventions (see the spec's Coordinate contract):
 *  - **kw units**: physical distance divided by [LayoutGeometry.meanKeyWidth];
 *    thresholds that compare finger travel / key spacing live here.
 *  - **normalized-shape units**: distances measured after each path is
 *    independently centroid-translated and bbox-scaled (SHARK2 Eq. 1); these are
 *    word-span-dependent (see Open Question 3).
 *
 * Every default below is the pinned value from the spec's Config Surface table
 * and Algorithm Specification; do not change them without re-running the Phase-6
 * harness (they are load-bearing for the accuracy/perf thresholds).
 */
data class GeometricEngineConfig(
    /** Arc-length resample target N. 32 → 128 B/word templates, µs-scale scoring. */
    val resamplePoints: Int = 32,
    /** σ_s, shape-channel std-dev, normalized-shape units (word-span-dependent; OQ3). */
    val shapeSigma: Float = 0.30f,
    /** σ_l, location-channel std-dev, kw units. */
    val locationSigma: Float = 0.50f,
    /**
     * SHARK2 LOCATION-TUNNEL half-width W (research doc §2 Step 1b / spec OQ-1). `0`
     * (default) is the current STRICT index-aligned location term `d_kw(uᵢ, tᵢ)`. When
     * `W > 0`, each gesture point matches the CLOSEST template point within a ±W index
     * window: `d_loc(i) = min over j∈[i−W, i+W] d_kw(uᵢ, t_j)`, still α-weighted. This
     * relaxes the mid-gesture location penalty that SLOPPY per-point jitter inflates on
     * tight same-row arcs — the Dvorak short-word failure regime (all five vowels
     * home-row-adjacent → near-collinear ≲2 kw paths where the shape channel is faded
     * out and disambiguation rests entirely on location; Step-0 measured Dvorak
     * scorer-limited: SLOPPY recall 92.9% healthy but top-3 only 75.8%).
     *
     * Unlike raising σ_l (measured to HURT SLOPPY top-5, [GeoAccuracyThresholds]), the
     * tunnel is a MIN not a σ inflation — it relaxes the penalty for mid-gesture drift
     * without widening the tail everywhere.
     *
     * **Default `0` (strict, tunnel OFF) — the tunnel is NOT deployable as a global
     * default.** The SLOPPY W-sweep {0, 1, 2} showed W=1 lifts SLOPPY everywhere
     * (en/QWERTY top-3 79.5→81.6%, en/Dvorak 75.8→78.2%), BUT the full regression grid
     * caught it REGRESSING CLEAN/TYPICAL on the passing QWERTY layout: CLEAN top-3
     * 98.1→95.6% (below the 0.97 floor) and the tail-canary gap 0.05→0.11 (over 0.10) —
     * the min-over-window relaxes location discrimination on clean traces, letting
     * colliding templates match, exactly the reason Floris/CleverKeys dropped the tunnel
     * in v1 (spec OQ-1). Per "set the swept value as default ONLY if the full regression
     * grid stays green", the tunnel stays OFF; reaching 0.78 on Dvorak via a
     * CLEAN-safe scorer signal (e.g. the OQ-8 direction channel) is a tracked follow-up.
     * The knob remains for future gated/calibrated experiments. Decision trail: spec
     * As-Built Notes.
     */
    val locationTunnelHalfWidth: Int = 0,
    /** λ_f, frequency-prior weight: prior term = −λ_f·ln(1 + ordinalRank). §5 derivation. */
    val frequencyWeight: Float = 0.12f,
    /**
     * DIRECTION/TANGENT channel weight (research doc Rank 3 / spec OQ-8, ASK precedent
     * `GestureTypingDetector.DIRECTION_PENALTY_FACTOR = 1.0`). `0.0` (default) disables
     * it — a bit-identical no-op. When `> 0`, a bounded, length-invariant per-segment
     * direction penalty `mean_i(1 − cos θ_i)` over the N−1 index-aligned gesture vs
     * template segment tangents is subtracted from S(w), CAPPED at [directionPenaltyCap]
     * (like `cornerBonus`) so it can never dominate. Unlike the positional shape sum
     * (noise-ACCUMULATING), aggregate tangent direction is noise-AVERAGING (zero-mean
     * jitter cancels in the segment vector), so it targets key-boundary-adjacent
     * ambiguity where absolute geometry is swamped but approach angle survives — the
     * scorer-limited regime (post-inset weird-custom: recall ~88% yet top-3 ~68%). It is
     * a genuinely NEW channel, not a σ re-tune (the σ sweep is saturated).
     *
     * **Default `0.30`** (tuned + ablated 2026-07-20). The weight sweep {0, 0.15, 0.30,
     * 0.50} was MONOTONICALLY non-regressing on EVERY layout × tier (unlike the location
     * tunnel): it slightly IMPROVES CLEAN top-3 everywhere (QWERTY 97.9→98.2%, weird
     * 98.7→99.0%) — the noise-averaging property — while lifting SLOPPY (weird top-3
     * 67.8→68.5%, Dvorak 75.4→75.8% [its peak], QWERTY/JCUKEN flat-to-up) and TYPICAL.
     * 0.30 is Dvorak's SLOPPY peak and safely mid-range under the [directionPenaltyCap].
     * Decision trail: spec As-Built Notes.
     */
    val directionPenaltyWeight: Float = 0.30f,
    /** TOTAL cap (log-domain) on the direction penalty — bounds its influence like the corner bonus. */
    val directionPenaltyCap: Float = 0.5f,
    /** Below this template path length (kw), the shape channel's weight fades toward 0. */
    val shortWordShapeFloorKw: Float = 2.0f,
    /** Lower clamp (kw) on the shape-normalization scale — kills jitter amplification / div-by-0. */
    val minShapeScaleKw: Float = 1.5f,
    /** σ for the length-ratio sanity penalty: ((ratio−1)/lengthRatioSigma)². */
    val lengthRatioSigma: Float = 0.35f,
    /** TOTAL cap on the corner-anchor bonus (normalized by template corner count). */
    val cornerAnchorBonus: Float = 0.25f,
    /** Interior turn angle (degrees) at/above which a resampled point is a corner. */
    val cornerAngleThresholdDeg: Float = 55f,
    /** Soft start-anchor radius (kw): overshoot beyond this of the first template key is penalized. */
    val startNeighborRadius: Float = 0.9f,
    /** Soft end-anchor radius (kw); ends are looser than starts (ASK precedent — users overshoot ends). */
    val endNeighborRadius: Float = 1.1f,
    /** Length-ratio prune lower bound (gesturePathLen / templatePathLen). */
    val lengthRatioMin: Float = 0.55f,
    /** Length-ratio prune upper bound. */
    val lengthRatioMax: Float = 1.9f,
    /**
     * Hard cap on how many candidates the scorer materializes/scores per swipe.
     *
     * **Raised 800 → 1200 (2026-07-20)** to absorb the endpoint-inset union. The inset
     * probes extra (start, end) bucket pairs, so mean pre-cap survivors rose from ~641 to
     * ~925 on QWERTY TYPICAL; at the old 800 cap those extra candidates COMPETE for the
     * best-K slots by the `|ln ratio|` proxy and can EVICT the true word, dropping TYPICAL
     * prune-3 cap-survival to 98.0% (below the 99% NFR floor) — recall is therefore NOT
     * monotone in [endpointInsetKw]. cap=1200 restores 99.5% cap-survival (≈ 1.2% of the
     * 98k lexicon) with ample latency headroom (warm decode still ≈ 3 ms median vs the
     * 30 ms NFR-1 floor; NFR-2 memo memory is independent of this cap).
     */
    val maxCandidatesScored: Int = 1200,
    /** k-nearest keys per gesture endpoint used for extremity bucketing (widened when dense). */
    val extremityNeighbors: Int = 2,
    /** kw below this (≳13 columns) ⇒ widen extremity buckets to 3-nearest per end. */
    val denseLayoutKwThreshold: Float = 0.075f,
    /**
     * HOSTILE-LAYOUT RECALL KNOB (research doc §2 Step 1a). Distance (kw) each gesture
     * endpoint is backed off ALONG the path before a SECOND nearest-key lookup; the
     * pruner unions the raw-endpoint and inset-endpoint extremity buckets. This undoes
     * SLOPPY overshoot + endpoint-Gaussian jitter (σ_end = 0.30 kw) that pushes a noisy
     * endpoint onto an adjacent key and drops the true word out of the 2-nearest bucket
     * BEFORE scoring — the pruning ceiling on irregular grids (weird-custom SLOPPY
     * prune-recall = 80.2% measured, vs 93.3% QWERTY control).
     *
     * `0.0` is a **bit-identical no-op**: the inset point is the endpoint itself, so both
     * inset buckets equal the raw buckets and no floor can move. Union-widening is
     * endpoint-only (≤ 2× the endpoint buckets, bounded by [maxCandidatesScored]) — NOT
     * the rejected global 3×3.
     *
     * **Default `0.30`** (tuned 2026-07-20). A SLOPPY sweep {0, 0.30, 0.35, 0.40} kw
     * (with the location tunnel OFF — see [locationTunnelHalfWidth], which the regression
     * grid rejected as a default) picked 0.30: it lifts hostile weird-custom SLOPPY
     * prune-recall 80.2→87.6% (Step-0 measured weird pruner-limited: recall 80.2% vs 93.3%
     * QWERTY) with CLEAN/TYPICAL unchanged (inset anchors coincide with the raw endpoints
     * on clean traces).
     *
     * COUPLING (recall is NOT monotone in this knob): the inset probes extra (start, end)
     * bucket pairs, so mean pre-cap survivors rose ~641→925 on QWERTY TYPICAL. At the old
     * 800 cap those extra candidates EVICTED ~2% of true words (cap-survival 98.0% < 99%),
     * so [maxCandidatesScored] was raised 800→1200 in the same change to keep cap-survival
     * ≥ 99%. Do not raise this knob further without re-checking cap-survival. Decision
     * trail: spec As-Built Notes.
     */
    val endpointInsetKw: Float = 0.30f,
    /**
     * RESERVED: DTW band width. Only `0` (proportional, index-aligned matching) is
     * implemented — the banded-DTW experimental path was evaluated and not built
     * (Phase 6 found no accuracy win to justify it). Any non-zero value fails fast in
     * `init` instead of silently no-oping, so a future calibration flow cannot believe
     * it enabled DTW when nothing reads the knob.
     */
    val dtwBand: Int = 0,
    /** Square-loop side length (kw) inserted at doubled letters for the loop template variant. */
    val doubleLetterLoopRadius: Float = 0.25f,
    /** typeableFraction below this flags an index DEAD_LAYOUT (FR-4 coverage guard). */
    val deadLayoutCoverageThreshold: Float = 0.20f,
    /** Softmax temperature T for the S(w) → 0–1000 mapping (fixed, not min-max). */
    val softmaxTemperature: Float = 1.0f,
    /** Maximum ranked candidates emitted in the PredictionResult. */
    val maxResults: Int = 10,
    /** Per-index lazy full-template memo capacity (Tier B LRU). */
    val templateMemoCapacity: Int = 4096,
    /** Number of live TemplateIndex instances retained by the top-level LRU cache. */
    val indexCacheCapacity: Int = 3,
) {
    init {
        // Fail-fast on configs that would produce NaN/degenerate geometry. These
        // are programming errors (a mis-wired calibration flow), surfaced loudly
        // rather than silently corrupting scores downstream.
        require(resamplePoints >= 2) { "resamplePoints must be >= 2, was $resamplePoints" }
        require(shapeSigma > 0f) { "shapeSigma must be > 0, was $shapeSigma" }
        require(locationSigma > 0f) { "locationSigma must be > 0, was $locationSigma" }
        require(minShapeScaleKw > 0f) { "minShapeScaleKw must be > 0, was $minShapeScaleKw" }
        require(shortWordShapeFloorKw > 0f) { "shortWordShapeFloorKw must be > 0, was $shortWordShapeFloorKw" }
        require(lengthRatioSigma > 0f) { "lengthRatioSigma must be > 0, was $lengthRatioSigma" }
        require(softmaxTemperature > 0f) { "softmaxTemperature must be > 0, was $softmaxTemperature" }
        require(maxResults >= 1) { "maxResults must be >= 1, was $maxResults" }
        require(maxCandidatesScored >= 1) { "maxCandidatesScored must be >= 1, was $maxCandidatesScored" }
        require(extremityNeighbors >= 1) { "extremityNeighbors must be >= 1, was $extremityNeighbors" }
        require(endpointInsetKw >= 0f) { "endpointInsetKw must be >= 0 (0 = no-op), was $endpointInsetKw" }
        require(locationTunnelHalfWidth >= 0) {
            "locationTunnelHalfWidth must be >= 0 (0 = strict alignment), was $locationTunnelHalfWidth"
        }
        require(directionPenaltyWeight >= 0f) {
            "directionPenaltyWeight must be >= 0 (0 = off), was $directionPenaltyWeight"
        }
        require(directionPenaltyCap >= 0f) { "directionPenaltyCap must be >= 0, was $directionPenaltyCap" }
        require(templateMemoCapacity >= 1) { "templateMemoCapacity must be >= 1, was $templateMemoCapacity" }
        require(indexCacheCapacity >= 1) { "indexCacheCapacity must be >= 1, was $indexCacheCapacity" }
        require(lengthRatioMin > 0f && lengthRatioMax > lengthRatioMin) {
            "length-ratio window invalid: [$lengthRatioMin, $lengthRatioMax]"
        }
        require(dtwBand == 0) {
            "DTW matching is not implemented; dtwBand must be 0 (reserved knob), was $dtwBand"
        }
    }

    /**
     * The effective k-nearest extremity-neighbor count for [layout]: widened to at
     * least 3 on dense layouts (`meanKeyWidth < denseLayoutKwThreshold`, ≳13 columns)
     * where noisy endpoints are the real recall risk. Single source of truth shared by
     * [GesturePreprocessor] (how many neighbors to compute) and [CandidatePruner]
     * (how many buckets to union) — they MUST agree or the widened bucket silently
     * never materializes.
     */
    fun effectiveExtremityNeighbors(layout: LayoutGeometry): Int =
        if (layout.meanKeyWidth < denseLayoutKwThreshold) {
            maxOf(3, extremityNeighbors)
        } else {
            extremityNeighbors
        }
}
