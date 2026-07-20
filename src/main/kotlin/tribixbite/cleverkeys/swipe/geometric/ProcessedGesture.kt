package tribixbite.cleverkeys.swipe.geometric

/**
 * The output of [GesturePreprocessor] and the input contract shared by the
 * pruner and scorer.
 *
 * Defined in Phase 1 (with the rest of the geometry core) so that later phases —
 * in particular the Phase-3 pruner tests — can construct a [ProcessedGesture]
 * directly without depending on the Phase-4 preprocessor implementation.
 *
 * All coordinates are NORMALIZED [0,1]² (see the Coordinate contract). The
 * resampled polyline is stored interleaved to avoid per-point object allocation
 * on the hot path.
 *
 * @param points 2N interleaved normalized coords: `[u0, v0, u1, v1, …]`, length
 *   `2 * config.resamplePoints`.
 * @param pathLengthKw total arc length of the resampled polyline in kw units.
 * @param bbox axis-aligned bounding box as `[minU, minV, maxU, maxV]`.
 * @param cornerIndices resampled-point indices flagged as corners (turn angle
 *   ≥ `cornerAngleThresholdDeg`); a SOFT scoring feature only, never a hard filter.
 * @param startNearest k nearest key ids to the FIRST raw trace point, ascending
 *   distance (extremity-bucket lookup).
 * @param endNearest k nearest key ids to the LAST raw trace point, ascending
 *   distance.
 */
class ProcessedGesture(
    val points: FloatArray,
    val pathLengthKw: Float,
    val bbox: FloatArray,
    val cornerIndices: IntArray,
    val startNearest: IntArray,
    val endNearest: IntArray,
) {
    /** Number of resampled points N (== points.size / 2). */
    val pointCount: Int get() = points.size / 2
}
