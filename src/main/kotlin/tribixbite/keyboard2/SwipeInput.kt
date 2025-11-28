package tribixbite.keyboard2

import android.graphics.PointF
import kotlin.math.*

/**
 * Encapsulates all data from a swipe gesture for prediction
 */
class SwipeInput(
    coordinates: List<PointF>,
    timestamps: List<Long>,
    touchedKeys: List<KeyboardData.Key?>
) {
    @JvmField val coordinates: List<PointF> = coordinates.toList()
    @JvmField val timestamps: List<Long> = timestamps.toList()
    @JvmField val touchedKeys: List<KeyboardData.Key?> = touchedKeys.toList()
    @JvmField val keySequence: String
    @JvmField val pathLength: Float
    @JvmField val duration: Float
    @JvmField val directionChanges: Int
    @JvmField val averageVelocity: Float
    @JvmField val velocityProfile: List<Float>
    @JvmField val startPoint: PointF
    @JvmField val endPoint: PointF
    @JvmField val keyboardCoverage: Float

    init {
        // Build key sequence
        keySequence = buildString {
            for (key in touchedKeys) {
                val kv = key?.keys?.getOrNull(0)
                if (kv != null) {
                    if (kv.getKind() == KeyValue.Kind.Char) {
                        append(kv.getChar())
                    }
                }
            }
        }

        // Calculate metrics
        pathLength = calculatePathLength()
        duration = calculateDuration()
        directionChanges = calculateDirectionChanges()
        velocityProfile = calculateVelocityProfile()
        averageVelocity = calculateAverageVelocity()
        startPoint = if (this.coordinates.isEmpty()) PointF(0f, 0f) else this.coordinates[0]
        endPoint = if (this.coordinates.isEmpty()) PointF(0f, 0f) else this.coordinates[this.coordinates.size - 1]
        keyboardCoverage = calculateKeyboardCoverage()
    }

    private fun calculatePathLength(): Float {
        var length = 0f
        for (i in 1 until coordinates.size) {
            val p1 = coordinates[i - 1]
            val p2 = coordinates[i]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            length += sqrt(dx * dx + dy * dy)
        }
        return length
    }

    private fun calculateDuration(): Float {
        if (timestamps.size < 2) return 0f
        return (timestamps[timestamps.size - 1] - timestamps[0]) / 1000.0f // in seconds
    }

    private fun calculateDirectionChanges(): Int {
        if (coordinates.size < 3) return 0

        var changes = 0

        for (i in 2 until coordinates.size) {
            val p1 = coordinates[i - 2]
            val p2 = coordinates[i - 1]
            val p3 = coordinates[i]

            val angle1 = atan2(p2.y - p1.y, p2.x - p1.x)
            val angle2 = atan2(p3.y - p2.y, p3.x - p2.x)

            var angleDiff = abs(angle2 - angle1)
            if (angleDiff > PI) {
                angleDiff = (2 * PI - angleDiff).toFloat()
            }

            // Count as direction change if angle difference > 45 degrees
            if (angleDiff > PI / 4) {
                changes++
            }
        }

        return changes
    }

    private fun calculateVelocityProfile(): List<Float> {
        val velocities = mutableListOf<Float>()

        for (i in 1 until min(coordinates.size, timestamps.size)) {
            val p1 = coordinates[i - 1]
            val p2 = coordinates[i]
            val t1 = timestamps[i - 1]
            val t2 = timestamps[i]

            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val distance = sqrt(dx * dx + dy * dy)
            val timeDelta = (t2 - t1) / 1000.0f // in seconds

            if (timeDelta > 0) {
                velocities.add(distance / timeDelta)
            }
        }

        return velocities
    }

    private fun calculateAverageVelocity(): Float {
        return if (duration > 0) pathLength / duration else 0f
    }

    private fun calculateKeyboardCoverage(): Float {
        if (coordinates.isEmpty()) return 0f

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (p in coordinates) {
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
        }

        val width = maxX - minX
        val height = maxY - minY

        // Approximate coverage as ratio of bounding box diagonal to expected keyboard size
        // This is a rough estimate - should be calibrated based on actual keyboard dimensions
        return sqrt(width * width + height * height)
    }

    /**
     * Check if this input represents a high-quality swipe
     */
    fun isHighQualitySwipe(): Boolean {
        return pathLength > 100 && // Minimum path length
            duration > 0.1f && // Minimum duration
            duration < 3.0f && // Maximum duration
            directionChanges >= 2 && // Has some complexity
            coordinates.isNotEmpty() &&
            timestamps.isNotEmpty()
    }

    /**
     * Calculate confidence that this is a swipe vs regular typing
     */
    fun getSwipeConfidence(): Float {
        var confidence = 0f

        // Path length factor (longer = more likely swipe)
        confidence += when {
            pathLength > 200 -> 0.3f
            pathLength > 100 -> 0.2f
            pathLength > 50 -> 0.1f
            else -> 0f
        }

        // Duration factor (swipes are typically 0.3-1.5 seconds)
        confidence += when {
            duration > 0.3f && duration < 1.5f -> 0.25f
            duration > 0.2f && duration < 2.0f -> 0.15f
            else -> 0f
        }

        // Direction changes (swipes have multiple direction changes)
        confidence += when {
            directionChanges >= 3 -> 0.25f
            directionChanges >= 2 -> 0.15f
            else -> 0f
        }

        // Key sequence length (swipes touch many keys)
        confidence += when {
            keySequence.length > 6 -> 0.2f
            keySequence.length > 4 -> 0.1f
            else -> 0f
        }

        return min(1.0f, confidence)
    }
}
