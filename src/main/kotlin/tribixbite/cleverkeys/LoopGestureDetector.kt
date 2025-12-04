package tribixbite.cleverkeys

import android.graphics.PointF
import android.util.Log
import kotlin.math.*

/**
 * Detects loop gestures in swipe paths for repeated letters.
 * A loop is detected when the path curves back on itself near a key,
 * indicating the user wants to type that letter multiple times.
 *
 * Based on analysis of gestures for words like "hello", "book", "coffee", etc.
 */
class LoopGestureDetector(
    private val keyWidth: Float,
    private val keyHeight: Float
) {
    /**
     * Represents a detected loop in the swipe path
     */
    data class Loop(
        val startIndex: Int,
        val endIndex: Int,
        val center: PointF,
        val radius: Float,
        val totalAngle: Float,
        val associatedKey: Char
    ) {
        fun isClockwise(): Boolean = totalAngle > 0

        fun getRepeatCount(): Int {
            // Estimate repeat count based on loop completeness
            // Full loop (360°) = 2 occurrences of the letter
            // Half loop (180°) = might be intentional curve, ignore
            val absAngle = abs(totalAngle)
            return when {
                absAngle >= 520.0f -> 3 // 1.5 loops
                absAngle >= 340.0f -> 2 // Full loop
                else -> 1 // Partial loop, treat as single occurrence
            }
        }
    }

    /**
     * Detect all loops in a swipe path
     *
     * @param swipePath The complete swipe path
     * @param touchedKeys Keys that were touched during the swipe
     * @return List of detected loops
     */
    fun detectLoops(swipePath: List<PointF>, touchedKeys: List<KeyboardData.Key>): List<Loop> {
        val detectedLoops = mutableListOf<Loop>()

        if (swipePath.size < MIN_LOOP_POINTS * 2) {
            return detectedLoops // Not enough points to form a loop
        }

        // Scan through the path looking for loop patterns
        var i = MIN_LOOP_POINTS
        while (i < swipePath.size - MIN_LOOP_POINTS) {
            val loop = detectLoopAtPoint(swipePath, i, touchedKeys)
            if (loop != null) {
                detectedLoops.add(loop)
                // Skip past this loop to avoid duplicate detection
                i = loop.endIndex
            }
            i++
        }

        Log.d(TAG, "Detected ${detectedLoops.size} loops in swipe path")
        return detectedLoops
    }

    /**
     * Try to detect a loop starting around a specific point
     */
    private fun detectLoopAtPoint(
        path: List<PointF>,
        centerIndex: Int,
        keys: List<KeyboardData.Key>
    ): Loop? {
        // Look for points that come back close to the starting point
        val centerPoint = path[centerIndex]

        // Search forward for a point that comes back close
        val closureIndex = ((centerIndex + MIN_LOOP_POINTS) until min(centerIndex + 50, path.size))
            .firstOrNull { j ->
                distance(centerPoint, path[j]) < CLOSURE_THRESHOLD
            } ?: return null // No loop closure found

        // Extract the potential loop segment
        val loopSegment = path.subList(centerIndex, closureIndex + 1)

        // Calculate loop properties
        val loopCenter = calculateCenter(loopSegment)
        val avgRadius = calculateAverageRadius(loopSegment, loopCenter)
        val totalAngle = calculateTotalAngle(loopSegment, loopCenter)

        // Validate loop properties
        if (!isValidLoop(avgRadius, totalAngle)) {
            return null
        }

        // Find the associated key (closest key to loop center)
        val associatedKey = findClosestKey(loopCenter, keys)

        return Loop(centerIndex, closureIndex, loopCenter, avgRadius, totalAngle, associatedKey)
    }

    /**
     * Calculate the geometric center of a set of points
     */
    private fun calculateCenter(points: List<PointF>): PointF {
        val sumX = points.sumOf { it.x.toDouble() }.toFloat()
        val sumY = points.sumOf { it.y.toDouble() }.toFloat()
        return PointF(sumX / points.size, sumY / points.size)
    }

    /**
     * Calculate average radius from center to all points
     */
    private fun calculateAverageRadius(points: List<PointF>, center: PointF): Float {
        return points.map { distance(it, center) }.average().toFloat()
    }

    /**
     * Calculate total angle traversed around the center
     * Positive = clockwise, Negative = counter-clockwise
     */
    private fun calculateTotalAngle(points: List<PointF>, center: PointF): Float {
        if (points.size < 3) return 0f

        var totalAngle = 0.0

        for (i in 1 until points.size) {
            val p1 = points[i - 1]
            val p2 = points[i]

            // Calculate angles from center
            val angle1 = atan2(p1.y - center.y, p1.x - center.x)
            val angle2 = atan2(p2.y - center.y, p2.x - center.x)

            // Calculate angle difference
            var angleDiff = angle2 - angle1

            // Normalize to [-π, π]
            while (angleDiff > PI) angleDiff -= (2 * PI).toFloat()
            while (angleDiff < -PI) angleDiff += (2 * PI).toFloat()

            totalAngle += angleDiff
        }

        // Convert to degrees
        return Math.toDegrees(totalAngle).toFloat()
    }

    /**
     * Validate if the detected pattern is a valid loop
     */
    private fun isValidLoop(radius: Float, totalAngle: Float): Boolean {
        // Check radius bounds
        if (radius < MIN_LOOP_RADIUS) return false

        val maxRadius = min(keyWidth, keyHeight) * MAX_LOOP_RADIUS_FACTOR
        if (radius > maxRadius) return false

        // Check angle (must complete most of a circle)
        val absAngle = abs(totalAngle)
        if (absAngle < MIN_LOOP_ANGLE || absAngle > MAX_LOOP_ANGLE) return false

        return true
    }

    /**
     * Find the closest key to a point
     */
    private fun findClosestKey(point: PointF, keys: List<KeyboardData.Key>): Char {
        // This is simplified - in practice would need actual key positions
        // For now, return the most recent key
        if (keys.isNotEmpty()) {
            val lastKey = keys.last()
            val kv = lastKey.keys.getOrNull(0)
            if (kv?.getKind() == KeyValue.Kind.Char) {
                return kv.getChar()
            }
        }
        return ' '
    }

    /**
     * Calculate Euclidean distance between two points
     */
    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Apply loop detection results to modify the recognized key sequence
     *
     * @param keySequence Original key sequence
     * @param loops Detected loops
     * @param swipePath Original swipe path
     * @return Modified key sequence with repeated letters
     */
    fun applyLoops(keySequence: String, loops: List<Loop>, swipePath: List<PointF>): String {
        if (loops.isEmpty()) return keySequence

        val result = StringBuilder()
        var sequenceIndex = 0
        var pathIndex = 0

        for (loop in loops) {
            // Add characters up to the loop
            while (pathIndex < loop.startIndex && sequenceIndex < keySequence.length) {
                result.append(keySequence[sequenceIndex])
                sequenceIndex++
                pathIndex += swipePath.size / keySequence.length // Approximate
            }

            // Add the looped character multiple times
            if (loop.associatedKey != ' ') {
                val repeatCount = loop.getRepeatCount()
                repeat(repeatCount) {
                    result.append(loop.associatedKey)
                }
                // Skip past the single occurrence in the original sequence
                if (sequenceIndex < keySequence.length &&
                    keySequence[sequenceIndex] == loop.associatedKey
                ) {
                    sequenceIndex++
                }
            }

            pathIndex = loop.endIndex
        }

        // Add remaining characters
        while (sequenceIndex < keySequence.length) {
            result.append(keySequence[sequenceIndex])
            sequenceIndex++
        }

        return result.toString()
    }

    /**
     * Detect if a specific word pattern contains expected loops
     * Useful for validating known words with repeated letters
     */
    fun matchesLoopPattern(word: String, detectedLoops: List<Loop>): Boolean {
        // Find repeated letters in the word
        val repeatPositions = (1 until word.length).filter { i ->
            word[i] == word[i - 1]
        }

        // Check if we have loops at approximately the right positions
        // This is a simplified check - could be made more sophisticated
        return detectedLoops.size >= repeatPositions.size
    }

    companion object {
        private const val TAG = "LoopGestureDetector"

        // Minimum angle change to consider a loop (in degrees)
        private const val MIN_LOOP_ANGLE = 270.0f

        // Maximum angle change for a loop (can't exceed 360 + tolerance)
        private const val MAX_LOOP_ANGLE = 450.0f

        // Minimum radius for a valid loop (in pixels)
        private const val MIN_LOOP_RADIUS = 15.0f

        // Maximum radius for a valid loop (relative to key size)
        private const val MAX_LOOP_RADIUS_FACTOR = 1.5f

        // Minimum points needed to form a loop
        private const val MIN_LOOP_POINTS = 8

        // Distance threshold to consider points "close" (for loop closure)
        private const val CLOSURE_THRESHOLD = 30.0f
    }
}
