package tribixbite.cleverkeys.swipe.geometric

import tribixbite.cleverkeys.PredictionResult

/**
 * A single raw touch trace plus the layout + dictionary it should be decoded
 * against.
 *
 * @param points the raw trace, key-area-local pixels (see [TracePoint]).
 * @param keyAreaWidthPx width in pixels of the frame `points` are measured in
 *   (used to normalize `u = x / keyAreaWidthPx`).
 * @param keyAreaHeightPx height in pixels of that frame.
 * @param layout the geometry to match against.
 * @param dictionary the ranked word store to decode into.
 */
data class GeometricSwipeRequest(
    val points: List<TracePoint>,
    val keyAreaWidthPx: Float,
    val keyAreaHeightPx: Float,
    val layout: LayoutGeometry,
    val dictionary: GeometricDictionary,
)

/**
 * Result of [SwipeDecodingEngine.warmUp] — how much of the dictionary is typeable
 * on the given layout, and whether the layout is effectively dead.
 *
 * @param typeableFraction fraction of dictionary words that projected to a
 *   template on this layout (FR-4).
 * @param typeableWordCount absolute count of typeable words.
 * @param buildMillis wall-clock time spent building the Tier-A index (diagnostic).
 * @param deadLayout true iff [typeableFraction] < `config.deadLayoutCoverageThreshold`
 *   — the index is flagged so routing/tests can detect a silently-dead layout.
 */
class WarmUpResult(
    val typeableFraction: Float,
    val typeableWordCount: Int,
    val buildMillis: Long,
    val deadLayout: Boolean,
)

/**
 * The router seam for the geometric swipe decoder.
 *
 * Mirrors the FINAL candidate shape of the ONNX path: the deleted orchestrator
 * `onnx/SwipePredictorOrchestrator.predict` returns the richer
 * `PredictionPostProcessor.Result`; this engine emits the reduced
 * [PredictionResult], which is what that Result reduces to for the suggestion bar.
 * A future WP9 router adapts between the two.
 *
 * **SCORES ARE ENGINE-RELATIVE** (fixed-temperature softmax posterior × 1000) —
 * they are NOT calibrated against the CTC decoder's scores and MUST NOT be compared
 * across engines by any router.
 *
 * **Thread-safety**: all methods are safe to call from any thread. Cache mutation
 * is internally synchronized; `decode()` snapshots the active index and scores
 * lock-free (the Tier-B template memo is a synchronized LRU). A `decode` racing a
 * `warmUp` for the same key either observes the previous index or blocks briefly
 * on the synchronous-fallback path. The core never spawns threads and never reads
 * preferences.
 */
interface SwipeDecodingEngine {
    /**
     * Decode [request] synchronously into ranked candidates. Allocation-light;
     * the future adapter wraps this in `withContext(Dispatchers.Default)`.
     * Returns an empty [PredictionResult] (never throws) on degenerate input
     * (< 3 points, zero-length trace, dead layout, empty dictionary).
     */
    fun decode(request: GeometricSwipeRequest): PredictionResult

    /**
     * Build (or refresh) the Tier-A template index for `(layout, dictionary)` off
     * the hot path and report coverage. Idempotent: a second call for an unchanged
     * `(fingerprint, language, version)` is a cheap no-op returning the cached
     * coverage.
     */
    fun warmUp(layout: LayoutGeometry, dictionary: GeometricDictionary): WarmUpResult

    /** Evict the cached index for `(layoutFingerprint, language)` (all versions). */
    fun evict(layoutFingerprint: String, language: String)
}
