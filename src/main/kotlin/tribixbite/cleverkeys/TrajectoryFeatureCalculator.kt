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
     * Calculate features with zero allocation (callback based).
     *
     * @param normalizedCoords Coordinates normalized to [0, 1]
     * @param timestamps Timestamps in milliseconds
     * @param onPointCalculated Callback receiving calculated features for each point
     */
    inline fun calculateFeatures(
        normalizedCoords: List<PointF>,
        timestamps: List<Long>,
        onPointCalculated: (x: Float, y: Float, vx: Float, vy: Float, ax: Float, ay: Float) -> Unit
    ) {
        val n = normalizedCoords.size
        if (n == 0) return

        var prevX = normalizedCoords[0].x
        var prevY = normalizedCoords[0].y
        var prevT = timestamps[0]
        
        var prevVx = 0f
        var prevVy = 0f
        
        // Emit first point (v=0, a=0)
        onPointCalculated(prevX, prevY, 0f, 0f, 0f, 0f)

        for (i in 1 until n) {
            val currX = normalizedCoords[i].x
            val currY = normalizedCoords[i].y
            val currT = timestamps[i]
            
            // Calculate dt (ms)
            val dtRaw = (currT - prevT).toFloat()
            val dt = max(dtRaw, 1e-6f) // Avoid div by zero
            
            // Calculate velocity
            var vx = (currX - prevX) / dt
            var vy = (currY - prevY) / dt
            
            // Calculate acceleration
            var ax = (vx - prevVx) / dt
            var ay = (vy - prevVy) / dt
            
            // Clip
            vx = vx.coerceIn(-10f, 10f)
            vy = vy.coerceIn(-10f, 10f)
            ax = ax.coerceIn(-10f, 10f)
            ay = ay.coerceIn(-10f, 10f)
            
            // Emit
            onPointCalculated(currX, currY, vx, vy, ax, ay)
            
            // Update state
            prevX = currX
            prevY = currY
            prevT = currT
            prevVx = vx
            prevVy = vy
        }
    }

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
     * Legacy method: returns new List<FeaturePoint>. Use callback version for zero allocation.
     */
    fun calculateFeatures(
        normalizedCoords: List<PointF>,
        timestamps: List<Long>
    ): List<FeaturePoint> {
        val result = ArrayList<FeaturePoint>(normalizedCoords.size)
        calculateFeatures(normalizedCoords, timestamps) { x, y, vx, vy, ax, ay ->
            result.add(FeaturePoint(x, y, vx, vy, ax, ay))
        }
        return result
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
