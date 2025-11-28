package tribixbite.keyboard2

import android.graphics.PointF
import kotlin.math.max
import kotlin.math.min

/**
 * Calculates trajectory features for neural swipe typing.
 *
 * CRITICAL: Must match Python training code exactly!
 * Features: [x, y, vx, vy, ax, ay] where:
 * - x, y: normalized position [0, 1]
 * - vx, vy: velocity = position_change / time_change
 * - ax, ay: acceleration = velocity_change / time_change
 *
 * All velocities and accelerations are clipped to [-10, 10].
 *
 * Created: v1.32.471 - Extracted from SwipeTrajectoryProcessor for correctness
 */
object TrajectoryFeatureCalculator {

    /**
     * Single trajectory point with all 6 features.
     */
    data class FeaturePoint(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val ax: Float,
        val ay: Float
    )

    /**
     * Calculate trajectory features from normalized coordinates and timestamps.
     *
     * MATCHES PYTHON EXACTLY:
     * ```python
     * dt = np.diff(ts, prepend=ts[0])
     * dt = np.maximum(dt, 1e-6)
     * vx[1:] = np.diff(xs) / dt[1:]
     * vy[1:] = np.diff(ys) / dt[1:]
     * ax[1:] = np.diff(vx) / dt[1:]
     * ay[1:] = np.diff(vy) / dt[1:]
     * vx, vy = np.clip(vx, -10, 10), np.clip(vy, -10, 10)
     * ax, ay = np.clip(ax, -10, 10), np.clip(ay, -10, 10)
     * ```
     *
     * @param normalizedCoords Coordinates normalized to [0, 1]
     * @param timestamps Timestamps in milliseconds
     * @return List of feature points
     */
    fun calculateFeatures(
        normalizedCoords: List<PointF>,
        timestamps: List<Long>
    ): List<FeaturePoint> {
        if (normalizedCoords.isEmpty()) {
            return emptyList()
        }

        val n = normalizedCoords.size

        // Extract x and y arrays
        val xs = FloatArray(n) { normalizedCoords[it].x }
        val ys = FloatArray(n) { normalizedCoords[it].y }

        // Calculate dt (time differences)
        // dt = np.diff(ts, prepend=ts[0]) means dt[0] = 0, dt[i] = ts[i] - ts[i-1]
        val dt = FloatArray(n)
        dt[0] = 0f
        for (i in 1 until n) {
            dt[i] = (timestamps[i] - timestamps[i - 1]).toFloat()
        }

        // Ensure minimum dt to avoid division by zero
        // dt = np.maximum(dt, 1e-6)
        for (i in 0 until n) {
            dt[i] = max(dt[i], 1e-6f)
        }

        // Calculate velocities
        // vx[0] = 0, vx[i] = (xs[i] - xs[i-1]) / dt[i]
        val vx = FloatArray(n)
        val vy = FloatArray(n)
        vx[0] = 0f
        vy[0] = 0f
        for (i in 1 until n) {
            vx[i] = (xs[i] - xs[i - 1]) / dt[i]
            vy[i] = (ys[i] - ys[i - 1]) / dt[i]
        }

        // Calculate accelerations
        // ax[0] = 0, ax[i] = (vx[i] - vx[i-1]) / dt[i]
        val ax = FloatArray(n)
        val ay = FloatArray(n)
        ax[0] = 0f
        ay[0] = 0f
        for (i in 1 until n) {
            ax[i] = (vx[i] - vx[i - 1]) / dt[i]
            ay[i] = (vy[i] - vy[i - 1]) / dt[i]
        }

        // Clip to [-10, 10]
        for (i in 0 until n) {
            vx[i] = vx[i].coerceIn(-10f, 10f)
            vy[i] = vy[i].coerceIn(-10f, 10f)
            ax[i] = ax[i].coerceIn(-10f, 10f)
            ay[i] = ay[i].coerceIn(-10f, 10f)
        }

        // Build feature points
        return List(n) { i ->
            FeaturePoint(
                x = xs[i],
                y = ys[i],
                vx = vx[i],
                vy = vy[i],
                ax = ax[i],
                ay = ay[i]
            )
        }
    }

    /**
     * Calculate features without timestamps (uses index as time proxy).
     * This is a fallback when timestamps are not available.
     *
     * NOTE: This produces different results than the Python training!
     * Only use if timestamps are truly unavailable.
     */
    fun calculateFeaturesWithoutTimestamps(
        normalizedCoords: List<PointF>
    ): List<FeaturePoint> {
        // Generate synthetic timestamps (1ms per point)
        val timestamps = List(normalizedCoords.size) { it.toLong() }
        return calculateFeatures(normalizedCoords, timestamps)
    }

    /**
     * Pad or truncate features to target length.
     *
     * @param features Input feature points
     * @param targetLength Target sequence length
     * @return Padded/truncated features and actual length
     */
    fun padOrTruncate(
        features: List<FeaturePoint>,
        targetLength: Int
    ): Pair<List<FeaturePoint>, Int> {
        val actualLength = min(features.size, targetLength)

        val result = if (features.size > targetLength) {
            // Truncate
            features.take(targetLength)
        } else if (features.size < targetLength) {
            // Pad with zeros
            val padded = features.toMutableList()
            val zeroPadding = FeaturePoint(0f, 0f, 0f, 0f, 0f, 0f)
            repeat(targetLength - features.size) {
                padded.add(zeroPadding)
            }
            padded
        } else {
            features
        }

        return Pair(result, actualLength)
    }

    /**
     * Convert features to flat float array for ONNX tensor.
     * Shape: [seq_len, 6]
     */
    fun toFloatArray(features: List<FeaturePoint>): FloatArray {
        val result = FloatArray(features.size * 6)
        for (i in features.indices) {
            val f = features[i]
            val offset = i * 6
            result[offset] = f.x
            result[offset + 1] = f.y
            result[offset + 2] = f.vx
            result[offset + 3] = f.vy
            result[offset + 4] = f.ax
            result[offset + 5] = f.ay
        }
        return result
    }
}
