package tribixbite.cleverkeys

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

    // OPTIMIZATION: Reusable scratch arrays to eliminate per-swipe allocations
    // These are sized for max sequence length (250) and reused across calls
    private var scratchDt = FloatArray(256)
    private var scratchVx = FloatArray(256)
    private var scratchVy = FloatArray(256)

    /**
     * Streaming feature calculation - writes directly to pooled TrajectoryPoints.
     * Eliminates 7 intermediate FloatArrays and List<FeaturePoint> allocation.
     *
     * @param normalizedCoords Input coordinates (normalized to [0,1])
     * @param timestamps Input timestamps in milliseconds
     * @param outPoints Output list - will be cleared and populated with pooled TrajectoryPoints
     */
    fun calculateFeaturesStreaming(
        normalizedCoords: List<PointF>,
        timestamps: List<Long>,
        outPoints: ArrayList<SwipeTrajectoryProcessor.TrajectoryPoint>
    ) {
        outPoints.clear()

        if (normalizedCoords.isEmpty()) {
            return
        }

        val n = normalizedCoords.size

        // Ensure scratch arrays are large enough
        if (n > scratchDt.size) {
            val newSize = (n * 1.5).toInt()
            scratchDt = FloatArray(newSize)
            scratchVx = FloatArray(newSize)
            scratchVy = FloatArray(newSize)
        }

        // Calculate dt (time differences) into scratch array
        scratchDt[0] = 1e-6f  // Minimum to avoid div by zero
        for (i in 1 until n) {
            scratchDt[i] = max((timestamps[i] - timestamps[i - 1]).toFloat(), 1e-6f)
        }

        // Calculate velocities into scratch arrays
        scratchVx[0] = 0f
        scratchVy[0] = 0f
        for (i in 1 until n) {
            scratchVx[i] = ((normalizedCoords[i].x - normalizedCoords[i - 1].x) / scratchDt[i]).coerceIn(-10f, 10f)
            scratchVy[i] = ((normalizedCoords[i].y - normalizedCoords[i - 1].y) / scratchDt[i]).coerceIn(-10f, 10f)
        }

        // First point: all zeros for velocity/acceleration
        val firstPoint = TrajectoryObjectPool.obtainTrajectoryPoint()
        firstPoint.x = normalizedCoords[0].x
        firstPoint.y = normalizedCoords[0].y
        firstPoint.vx = 0f
        firstPoint.vy = 0f
        firstPoint.ax = 0f
        firstPoint.ay = 0f
        outPoints.add(firstPoint)

        // Remaining points: calculate acceleration inline, no intermediate array needed
        for (i in 1 until n) {
            val point = TrajectoryObjectPool.obtainTrajectoryPoint()
            point.x = normalizedCoords[i].x
            point.y = normalizedCoords[i].y
            point.vx = scratchVx[i]
            point.vy = scratchVy[i]
            // Acceleration = velocity change / time change, clipped
            point.ax = ((scratchVx[i] - scratchVx[i - 1]) / scratchDt[i]).coerceIn(-10f, 10f)
            point.ay = ((scratchVy[i] - scratchVy[i - 1]) / scratchDt[i]).coerceIn(-10f, 10f)
            outPoints.add(point)
        }
    }

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
     * NOTE: Training data was collected from Android touch events with timestamps in
     * milliseconds. Keep dt in milliseconds to match training. The small velocity values
     * (0.001 range) are expected and match what the model was trained on.
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
        // NOTE: Training data was collected from Android touch events (milliseconds).
        // Keep as milliseconds - empirically produces better predictions than converting to seconds.
        val dt = FloatArray(n)
        dt[0] = 0f
        for (i in 1 until n) {
            dt[i] = (timestamps[i] - timestamps[i - 1]).toFloat()
        }

        // Ensure minimum dt to avoid division by zero
        // dt = np.maximum(dt, 1e-6) - 1 microsecond minimum (makes sense for seconds)
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
