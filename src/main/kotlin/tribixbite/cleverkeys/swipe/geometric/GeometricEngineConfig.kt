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
    /** λ_f, frequency-prior weight: prior term = −λ_f·ln(1 + ordinalRank). §5 derivation. */
    val frequencyWeight: Float = 0.12f,
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
    /** Hard cap on how many candidates the scorer materializes/scores per swipe. */
    val maxCandidatesScored: Int = 800,
    /** k-nearest keys per gesture endpoint used for extremity bucketing (widened when dense). */
    val extremityNeighbors: Int = 2,
    /** kw below this (≳13 columns) ⇒ widen extremity buckets to 3-nearest per end. */
    val denseLayoutKwThreshold: Float = 0.075f,
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
