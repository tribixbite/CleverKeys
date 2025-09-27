package tribixbite.keyboard2

import android.graphics.PointF
import kotlin.math.*

/**
 * Encapsulates all data from a swipe gesture for prediction
 * Kotlin data class with computed properties and extension functions
 */
data class SwipeInput(
    val coordinates: List<PointF>,
    val timestamps: List<Long>,
    val touchedKeys: List<KeyboardData.Key>
) {
    // Computed properties with lazy initialization for performance
    val keySequence: String by lazy {
        buildString {
            touchedKeys.forEach { key ->
                key?.keys?.firstOrNull()?.let { kv ->
                    when (kv) {
                        is KeyValue.CharKey -> append(kv.char)
                        else -> { /* Skip non-character keys */ }
                    }
                }
            }
        }
    }
    
    val pathLength: Float by lazy { calculatePathLength() }
    val duration: Float by lazy { calculateDuration() }
    val directionChanges: Int by lazy { calculateDirectionChanges() }
    val velocityProfile: List<Float> by lazy { calculateVelocityProfile() }
    val averageVelocity: Float by lazy { if (duration > 0) pathLength / duration else 0f }
    val startPoint: PointF by lazy { coordinates.firstOrNull() ?: PointF(0f, 0f) }
    val endPoint: PointF by lazy { coordinates.lastOrNull() ?: PointF(0f, 0f) }
    val keyboardCoverage: Float by lazy { calculateKeyboardCoverage() }
    
    private fun calculatePathLength(): Float {
        return coordinates.zipWithNext { p1, p2 ->
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            sqrt(dx * dx + dy * dy)
        }.sum()
    }
    
    private fun calculateDuration(): Float {
        return if (timestamps.size < 2) 0f 
        else (timestamps.last() - timestamps.first()) / 1000f // seconds
    }
    
    private fun calculateDirectionChanges(): Int {
        if (coordinates.size < 3) return 0
        
        return coordinates.windowed(3).count { (p1, p2, p3) ->
            val angle1 = atan2(p2.y - p1.y, p2.x - p1.x)
            val angle2 = atan2(p3.y - p2.y, p3.x - p2.x)
            
            val angleDiff = abs(angle2 - angle1).let { diff ->
                if (diff > PI) 2 * PI - diff else diff
            }.toFloat()
            
            // Count as direction change if angle difference > 45 degrees
            angleDiff > (PI / 4).toFloat()
        }
    }
    
    private fun calculateVelocityProfile(): List<Float> {
        return coordinates.zip(timestamps).zipWithNext { (p1, t1), (p2, t2) ->
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val distance = sqrt(dx * dx + dy * dy)
            val timeDelta = (t2 - t1) / 1000f // seconds
            
            if (timeDelta > 0) distance / timeDelta else 0f
        }
    }
    
    private fun calculateKeyboardCoverage(): Float {
        if (coordinates.isEmpty()) return 0f
        
        val minX = coordinates.minOf { it.x }
        val maxX = coordinates.maxOf { it.x }
        val minY = coordinates.minOf { it.y }
        val maxY = coordinates.maxOf { it.y }
        
        val width = maxX - minX
        val height = maxY - minY
        
        // Coverage as diagonal of bounding box
        return sqrt(width * width + height * height)
    }
    
    /**
     * Check if this input represents a high-quality swipe
     */
    val isHighQualitySwipe: Boolean
        get() = pathLength > 100 &&
                duration in 0.1f..3.0f &&
                directionChanges >= 2 &&
                coordinates.isNotEmpty() &&
                timestamps.isNotEmpty()
    
    /**
     * Calculate confidence that this is a swipe vs regular typing
     */
    val swipeConfidence: Float
        get() {
            var confidence = 0f
            
            // Path length factor
            confidence += when {
                pathLength > 200 -> 0.3f
                pathLength > 100 -> 0.2f
                pathLength > 50 -> 0.1f
                else -> 0f
            }
            
            // Duration factor (swipes are typically 0.3-1.5 seconds)
            confidence += when {
                duration in 0.3f..1.5f -> 0.25f
                duration in 0.2f..2.0f -> 0.15f
                else -> 0f
            }
            
            // Direction changes
            confidence += when {
                directionChanges >= 3 -> 0.25f
                directionChanges >= 2 -> 0.15f
                else -> 0f
            }
            
            // Key sequence length
            confidence += when {
                keySequence.length > 6 -> 0.2f
                keySequence.length > 4 -> 0.1f
                else -> 0f
            }
            
            return confidence.coerceAtMost(1.0f)
        }
}