package tribixbite.cleverkeys

import android.graphics.PointF
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Probabilistic key detection using Gaussian weighting based on distance from swipe path
 */
class ProbabilisticKeyDetector(
    private val keyboard: KeyboardData?,
    private val keyboardWidth: Float,
    private val keyboardHeight: Float
) {
    /**
     * Detect keys along a swipe path using probabilistic weighting
     */
    fun detectKeys(swipePath: List<PointF>?): List<KeyboardData.Key> {
        if (swipePath.isNullOrEmpty() || keyboard == null) {
            return emptyList()
        }

        // Calculate scale factors (pixels per unit)
        val scaleX = keyboardWidth / keyboard.keysWidth
        val scaleY = keyboardHeight / keyboard.keysHeight

        // Calculate probability map for all keys
        val keyProbabilities = mutableMapOf<KeyboardData.Key, Float>()

        // Process each point in the swipe path
        for (point in swipePath) {
            processPathPoint(point, keyProbabilities, scaleX, scaleY)
        }

        // Convert probabilities to ordered key sequence
        return extractKeySequence(keyProbabilities, swipePath)
    }

    /**
     * Process a single point on the swipe path
     */
    private fun processPathPoint(
        point: PointF, 
        keyProbabilities: MutableMap<KeyboardData.Key, Float>,
        scaleX: Float,
        scaleY: Float
    ) {
        // Find keys near this point
        val nearbyKeys = findNearbyKeys(point, scaleX, scaleY)

        // Calculate probability for each nearby key
        for (kwd in nearbyKeys) {
            val probability = calculateGaussianProbability(kwd.distance, kwd.key, scaleX)

            if (probability > MIN_PROBABILITY) {
                // Accumulate probability
                val currentProb = keyProbabilities.getOrDefault(kwd.key, 0f)
                keyProbabilities[kwd.key] = currentProb + probability
            }
        }
    }

    /**
     * Find keys within reasonable distance of a point
     */
    private fun findNearbyKeys(point: PointF, scaleX: Float, scaleY: Float): List<KeyWithDistance> {
        val nearbyKeys = mutableListOf<KeyWithDistance>()

        if (keyboard?.rows == null) {
            return nearbyKeys
        }

        // Check all keys (could be optimized with spatial indexing)
        var y = 0f
        for (row in keyboard.rows) {
            var x = 0f
            val rowHeight = row.height * scaleY

            for (key in row.keys) {
                val keyWidth = key.width * scaleX
                
                if (key == null || key.keys[0] == null) {
                    x += keyWidth
                    continue
                }

                // Check if alphabetic
                if (!isAlphabeticKey(key)) {
                    x += keyWidth
                    continue
                }

                // Calculate key center
                val keyCenterX = x + keyWidth / 2
                val keyCenterY = y + rowHeight / 2

                // Calculate distance from point to key center
                val dx = point.x - keyCenterX
                val dy = point.y - keyCenterY
                val distance = sqrt(dx * dx + dy * dy)

                // Only consider keys within 2x key width
                val maxDistance = max(keyWidth, rowHeight) * 2
                if (distance < maxDistance) {
                    nearbyKeys.add(KeyWithDistance(key, distance, keyWidth, rowHeight))
                }

                x += keyWidth
            }
            y += rowHeight
        }

        return nearbyKeys
    }

    /**
     * Calculate Gaussian probability based on distance
     */
    private fun calculateGaussianProbability(distance: Float, key: KeyboardData.Key, scaleX: Float): Float {
        // Estimate key size
        val keySize = scaleX // Use unit width as base size
        val sigma = keySize * SIGMA_FACTOR

        // Gaussian formula: exp(-(distance^2) / (2 * sigma^2))
        return exp(-(distance * distance) / (2 * sigma * sigma))
    }

    /**
     * Find the key at a specific coordinate
     */
    fun getKeyAt(x: Float, y: Float): KeyboardData.Key? {
        if (keyboard?.rows == null) {
            return null
        }

        // Calculate scale factors (pixels per unit)
        val scaleX = keyboardWidth / keyboard.keysWidth
        val scaleY = keyboardHeight / keyboard.keysHeight

        var currentY = 0f
        for (row in keyboard.rows) {
            val rowHeight = row.height * scaleY
            
            if (y >= currentY && y < currentY + rowHeight) {
                var currentX = 0f
                for (key in row.keys) {
                    val keyWidth = key.width * scaleX
                    
                    if (x >= currentX && x < currentX + keyWidth) {
                        return key
                    }
                    currentX += keyWidth
                }
            }
            currentY += rowHeight
        }
        return null
    }

    /**
     * Extract ordered key sequence from probability map
     */
    private fun extractKeySequence(
        keyProbabilities: Map<KeyboardData.Key, Float>,
        swipePath: List<PointF>
    ): List<KeyboardData.Key> {
        // Filter keys by probability threshold
        val candidates = keyProbabilities.entries
            .map { (key, prob) ->
                val normalizedProb = prob / swipePath.size
                KeyCandidate(key, normalizedProb)
            }
            .filter { it.probability > PROBABILITY_THRESHOLD }
            .toMutableList()

        // Sort by probability
        candidates.sortByDescending { it.probability }

        // Order keys by their appearance along the path
        return orderKeysByPath(candidates, swipePath)
    }

    /**
     * Order keys based on when they appear along the swipe path
     */
    private fun orderKeysByPath(
        candidates: MutableList<KeyCandidate>,
        swipePath: List<PointF>
    ): List<KeyboardData.Key> {
        // For each candidate, find its first strong appearance in the path
        for (candidate in candidates) {
            candidate.pathIndex = findKeyPathIndex(candidate.key, swipePath)
        }

        // Sort by path index
        candidates.sortBy { it.pathIndex }

        // Extract ordered keys
        return candidates
            .filter { it.pathIndex >= 0 }
            .map { it.key }
    }

    /**
     * Find where along the path a key most strongly appears
     */
    private fun findKeyPathIndex(key: KeyboardData.Key, swipePath: List<PointF>): Int {
        // This is simplified - would need key position information
        // For now, return middle of path
        return swipePath.size / 2
    }

    /**
     * Check if key is alphabetic
     */
    private fun isAlphabeticKey(key: KeyboardData.Key?): Boolean {
        val kv = key?.keys?.getOrNull(0)
        if (kv == null) {
            return false
        }

        if (kv.getKind() != KeyValue.Kind.Char) {
            return false
        }

        val c = kv.getChar()
        return c in 'a'..'z' || c in 'A'..'Z'
    }

    /**
     * Helper class for key with distance
     */
    private data class KeyWithDistance(
        val key: KeyboardData.Key,
        val distance: Float,
        val keyWidth: Float,
        val keyHeight: Float
    )

    /**
     * Helper class for key candidates
     */
    private data class KeyCandidate(
        val key: KeyboardData.Key,
        val probability: Float,
        var pathIndex: Int = -1
    )

    companion object {
        // Parameters for Gaussian probability
        private const val SIGMA_FACTOR = 0.5f // Key width/height multiplier for standard deviation
        private const val MIN_PROBABILITY = 0.01f // Minimum probability to consider a key
        private const val PROBABILITY_THRESHOLD = 0.3f // Minimum cumulative probability to register key

        /**
         * Apply Ramer-Douglas-Peucker algorithm for path simplification
         */
        @JvmStatic
        fun simplifyPath(points: List<PointF>?, epsilon: Float): List<PointF>? {
            if (points == null || points.size < 3) {
                return points
            }

            // Find point with maximum distance from line between first and last
            var maxDist = 0f
            var maxIndex = 0

            val first = points[0]
            val last = points[points.size - 1]

            for (i in 1 until points.size - 1) {
                val dist = perpendicularDistance(points[i], first, last)
                if (dist > maxDist) {
                    maxDist = dist
                    maxIndex = i
                }
            }

            // If max distance is greater than epsilon, recursively simplify
            return if (maxDist > epsilon) {
                // Recursive call
                val firstPart = simplifyPath(points.subList(0, maxIndex + 1), epsilon)
                val secondPart = simplifyPath(points.subList(maxIndex, points.size), epsilon)

                // Combine results
                val result = mutableListOf<PointF>()
                firstPart?.let { result.addAll(it.subList(0, it.size - 1)) }
                secondPart?.let { result.addAll(it) }
                result
            } else {
                // Return just the endpoints
                listOf(first, last)
            }
        }

        /**
         * Calculate perpendicular distance from point to line
         */
        @JvmStatic
        private fun perpendicularDistance(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
            var dx = lineEnd.x - lineStart.x
            var dy = lineEnd.y - lineStart.y

            if (dx == 0f && dy == 0f) {
                // Line start and end are the same
                dx = point.x - lineStart.x
                dy = point.y - lineStart.y
                return sqrt(dx * dx + dy * dy)
            }

            var t = ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy) / (dx * dx + dy * dy)
            t = max(0f, min(1f, t))

            val nearestX = lineStart.x + t * dx
            val nearestY = lineStart.y + t * dy

            dx = point.x - nearestX
            dy = point.y - nearestY

            return sqrt(dx * dx + dy * dy)
        }
    }
}
