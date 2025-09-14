package juloo.keyboard2

import android.graphics.PointF
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

/**
 * Complete swipe gesture recognizer with pattern detection
 * Kotlin implementation with advanced gesture analysis
 */
class SwipeGestureRecognizer {
    
    companion object {
        private const val TAG = "SwipeGestureRecognizer"
        private const val MIN_SWIPE_DISTANCE = 50f
        private const val MIN_SWIPE_VELOCITY = 100f
        private const val DIRECTION_THRESHOLD = PI / 8 // 22.5 degrees
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isRecognizing = false
    private val gestureHistory = mutableListOf<RecognizedGesture>()
    
    /**
     * Gesture types with pattern recognition
     */
    enum class GestureType {
        TAP, SWIPE_HORIZONTAL, SWIPE_VERTICAL, SWIPE_DIAGONAL,
        CIRCLE_CLOCKWISE, CIRCLE_COUNTERCLOCKWISE, 
        LOOP, ZIG_ZAG, COMPLEX_PATTERN
    }
    
    /**
     * Recognized gesture data
     */
    data class RecognizedGesture(
        val type: GestureType,
        val direction: Float, // Angle in radians
        val distance: Float,
        val duration: Float,
        val confidence: Float,
        val points: List<PointF>,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Gesture recognition result
     */
    data class GestureResult(
        val gesture: RecognizedGesture,
        val swipeInput: SwipeInput?,
        val shouldTriggerPrediction: Boolean
    )
    
    /**
     * Recognize gesture from point sequence
     */
    suspend fun recognizeGesture(points: List<PointF>, timestamps: List<Long>): GestureResult = withContext(Dispatchers.Default) {
        val swipeInput = SwipeInput(points, timestamps, emptyList())
        
        val gestureType = classifyGestureType(points)
        val direction = calculatePrimaryDirection(points)
        val confidence = calculateGestureConfidence(swipeInput, gestureType)
        
        val gesture = RecognizedGesture(
            type = gestureType,
            direction = direction,
            distance = swipeInput.pathLength,
            duration = swipeInput.duration,
            confidence = confidence,
            points = points
        )
        
        // Add to history
        gestureHistory.add(gesture)
        if (gestureHistory.size > 100) {
            gestureHistory.removeAt(0)
        }
        
        val shouldPredict = shouldTriggerPrediction(gesture)
        
        GestureResult(gesture, if (shouldPredict) swipeInput else null, shouldPredict)
    }
    
    /**
     * Classify gesture type based on pattern analysis
     */
    private fun classifyGestureType(points: List<PointF>): GestureType {
        if (points.size < 3) return GestureType.TAP
        
        val pathLength = points.pathLength()
        if (pathLength < MIN_SWIPE_DISTANCE) return GestureType.TAP
        
        // Check for circular gestures
        if (isCircularPattern(points)) {
            return if (isClockwise(points)) {
                GestureType.CIRCLE_CLOCKWISE
            } else {
                GestureType.CIRCLE_COUNTERCLOCKWISE
            }
        }
        
        // Check for loop patterns
        if (isLoopPattern(points)) {
            return GestureType.LOOP
        }
        
        // Check for zigzag
        if (isZigZagPattern(points)) {
            return GestureType.ZIG_ZAG
        }
        
        // Classify linear swipes
        val direction = calculatePrimaryDirection(points)
        return when {
            abs(cos(direction)) > 0.8f -> GestureType.SWIPE_HORIZONTAL
            abs(sin(direction)) > 0.8f -> GestureType.SWIPE_VERTICAL
            else -> GestureType.SWIPE_DIAGONAL
        }
    }
    
    /**
     * Calculate primary direction of gesture
     */
    private fun calculatePrimaryDirection(points: List<PointF>): Float {
        if (points.size < 2) return 0f
        
        val start = points.first()
        val end = points.last()
        return atan2(end.y - start.y, end.x - start.x)
    }
    
    /**
     * Check if gesture forms circular pattern
     */
    private fun isCircularPattern(points: List<PointF>): Boolean {
        if (points.size < 8) return false
        
        val center = PointF(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat()
        )
        
        val avgRadius = points.map { it.distanceTo(center) }.average()
        val radiusVariation = points.map { abs(it.distanceTo(center) - avgRadius) }.average()
        
        // Check if radius is consistent (circular)
        val radiusConsistency = 1f - (radiusVariation / avgRadius).coerceAtMost(1f)
        
        // Check if gesture covers significant angle
        val angles = points.map { atan2(it.y - center.y, it.x - center.x) }
        val angleRange = angles.maxOrNull()!! - angles.minOrNull()!!
        
        return radiusConsistency > 0.7f && angleRange > PI
    }
    
    /**
     * Check if circular gesture is clockwise
     */
    private fun isClockwise(points: List<PointF>): Boolean {
        if (points.size < 3) return false
        
        var angleSum = 0f
        for (i in 1 until points.size - 1) {
            val p1 = points[i - 1]
            val p2 = points[i]
            val p3 = points[i + 1]
            
            val angle1 = atan2(p2.y - p1.y, p2.x - p1.x)
            val angle2 = atan2(p3.y - p2.y, p3.x - p2.x)
            val angleDiff = angle2 - angle1
            
            // Normalize angle difference
            var normalizedDiff = angleDiff
            while (normalizedDiff > PI) normalizedDiff -= 2 * PI.toFloat()
            while (normalizedDiff < -PI) normalizedDiff += 2 * PI.toFloat()
            
            angleSum += normalizedDiff
        }
        
        return angleSum > 0
    }
    
    /**
     * Check for loop pattern (returns to starting area)
     */
    private fun isLoopPattern(points: List<PointF>): Boolean {
        if (points.size < 6) return false
        
        val start = points.first()
        val end = points.last()
        val startToEndDistance = start.distanceTo(end)
        val pathLength = points.pathLength()
        
        // Loop if end is close to start but path is long
        return startToEndDistance < 100f && pathLength > 200f
    }
    
    /**
     * Check for zigzag pattern
     */
    private fun isZigZagPattern(points: List<PointF>): Boolean {
        if (points.size < 6) return false
        
        val directionChanges = points.windowed(3).count { (p1, p2, p3) ->
            val angle1 = atan2(p2.y - p1.y, p2.x - p1.x)
            val angle2 = atan2(p3.y - p2.y, p3.x - p2.x)
            val angleDiff = abs(angle2 - angle1)
            angleDiff > PI / 3 // More than 60 degrees
        }
        
        return directionChanges >= 3
    }
    
    /**
     * Calculate gesture confidence
     */
    private fun calculateGestureConfidence(swipeInput: SwipeInput, gestureType: GestureType): Float {
        var confidence = swipeInput.swipeConfidence
        
        // Boost confidence based on gesture type clarity
        confidence += when (gestureType) {
            GestureType.CIRCLE_CLOCKWISE, GestureType.CIRCLE_COUNTERCLOCKWISE -> 0.2f
            GestureType.SWIPE_HORIZONTAL, GestureType.SWIPE_VERTICAL -> 0.15f
            GestureType.LOOP -> 0.1f
            GestureType.SWIPE_DIAGONAL -> 0.1f
            GestureType.ZIG_ZAG -> 0.05f
            GestureType.COMPLEX_PATTERN -> 0.0f
            GestureType.TAP -> -0.3f
        }
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * Determine if gesture should trigger prediction
     */
    private fun shouldTriggerPrediction(gesture: RecognizedGesture): Boolean {
        return when (gesture.type) {
            GestureType.SWIPE_HORIZONTAL, GestureType.SWIPE_VERTICAL, GestureType.SWIPE_DIAGONAL -> {
                gesture.confidence > 0.3f && gesture.distance > MIN_SWIPE_DISTANCE
            }
            GestureType.CIRCLE_CLOCKWISE, GestureType.CIRCLE_COUNTERCLOCKWISE -> {
                gesture.confidence > 0.5f
            }
            GestureType.LOOP, GestureType.ZIG_ZAG -> {
                gesture.confidence > 0.4f
            }
            GestureType.COMPLEX_PATTERN -> {
                gesture.confidence > 0.6f
            }
            GestureType.TAP -> false
        }
    }
    
    /**
     * Get gesture statistics
     */
    fun getGestureStats(): GestureStats {
        val typeCount = gestureHistory.groupingBy { it.type }.eachCount()
        val avgConfidence = gestureHistory.map { it.confidence }.average().toFloat()
        val avgDuration = gestureHistory.map { it.duration }.average().toFloat()
        
        return GestureStats(
            totalGestures = gestureHistory.size,
            typeDistribution = typeCount,
            averageConfidence = avgConfidence,
            averageDuration = avgDuration
        )
    }
    
    /**
     * Gesture statistics data
     */
    data class GestureStats(
        val totalGestures: Int,
        val typeDistribution: Map<GestureType, Int>,
        val averageConfidence: Float,
        val averageDuration: Float
    )
    
    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
    }
}