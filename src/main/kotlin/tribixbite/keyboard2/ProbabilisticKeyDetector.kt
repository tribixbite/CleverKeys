package tribixbite.keyboard2

import android.graphics.PointF
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Probabilistic key detection using Gaussian weighting based on distance from swipe path.
 *
 * Features:
 * - Gaussian probability distribution for key proximity
 * - Cumulative probability tracking across path
 * - Alphabetic key filtering
 * - Path-ordered key sequence extraction
 * - Ramer-Douglas-Peucker path simplification
 *
 * Algorithm:
 * 1. For each point on swipe path, calculate Gaussian probability for nearby keys
 * 2. Accumulate probabilities across entire path
 * 3. Filter keys by probability threshold
 * 4. Order keys by appearance along path
 *
 * Probability Formula:
 * P(key | point) = exp(-(distance^2) / (2 * sigma^2))
 * where sigma = key_size * SIGMA_FACTOR
 *
 * Performance:
 * - O(N * K) where N = path points, K = keyboard keys
 * - Could be optimized with spatial indexing (quadtree/grid)
 *
 * Ported from Java to Kotlin with improvements.
 */
class ProbabilisticKeyDetector(
    private val keyboard: KeyboardData,
    private val keyboardWidth: Float,
    private val keyboardHeight: Float
) {

    companion object {
        /** Key width/height multiplier for standard deviation */
        private const val SIGMA_FACTOR = 0.5f

        /** Minimum probability to consider a key */
        private const val MIN_PROBABILITY = 0.01f

        /** Minimum cumulative probability to register key */
        private const val PROBABILITY_THRESHOLD = 0.3f

        /**
         * Apply Ramer-Douglas-Peucker algorithm for path simplification
         *
         * @param points Original path points
         * @param epsilon Maximum perpendicular distance threshold
         * @return Simplified path with fewer points
         */
        @JvmStatic
        fun simplifyPath(points: List<PointF>, epsilon: Float): List<PointF> {
            if (points.size < 3) {
                return points
            }

            // Find point with maximum distance from line between first and last
            var maxDist = 0f
            var maxIndex = 0

            val first = points.first()
            val last = points.last()

            for (i in 1 until points.size - 1) {
                val dist = perpendicularDistance(points[i], first, last)
                if (dist > maxDist) {
                    maxDist = dist
                    maxIndex = i
                }
            }

            // If max distance > epsilon, recursively simplify
            return if (maxDist > epsilon) {
                // Recursive call
                val firstPart = simplifyPath(points.subList(0, maxIndex + 1), epsilon)
                val secondPart = simplifyPath(points.subList(maxIndex, points.size), epsilon)

                // Combine results (avoid duplicate middle point)
                firstPart.dropLast(1) + secondPart
            } else {
                // Return just the endpoints
                listOf(first, last)
            }
        }

        /**
         * Calculate perpendicular distance from point to line segment
         *
         * @param point Point to measure from
         * @param lineStart Line segment start
         * @param lineEnd Line segment end
         * @return Perpendicular distance
         */
        @JvmStatic
        private fun perpendicularDistance(
            point: PointF,
            lineStart: PointF,
            lineEnd: PointF
        ): Float {
            var dx = lineEnd.x - lineStart.x
            var dy = lineEnd.y - lineStart.y

            // Handle degenerate case (line start == line end)
            if (dx == 0f && dy == 0f) {
                dx = point.x - lineStart.x
                dy = point.y - lineStart.y
                return sqrt(dx * dx + dy * dy)
            }

            // Calculate projection parameter t
            var t = ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy) / (dx * dx + dy * dy)
            t = max(0f, min(1f, t))

            // Find nearest point on line segment
            val nearestX = lineStart.x + t * dx
            val nearestY = lineStart.y + t * dy

            // Calculate distance to nearest point
            dx = point.x - nearestX
            dy = point.y - nearestY

            return sqrt(dx * dx + dy * dy)
        }
    }

    /**
     * Detect keys along a swipe path using probabilistic weighting
     *
     * @param swipePath List of points along the swipe
     * @return Ordered list of detected keys
     */
    fun detectKeys(swipePath: List<PointF>): List<KeyboardData.Key> {
        if (swipePath.isEmpty()) {
            return emptyList()
        }

        // Calculate probability map for all keys
        val keyProbabilities = mutableMapOf<KeyboardData.Key, Float>()

        // Process each point in the swipe path
        for (point in swipePath) {
            processPathPoint(point, keyProbabilities)
        }

        // Convert probabilities to ordered key sequence
        return extractKeySequence(keyProbabilities, swipePath)
    }

    /**
     * Process a single point on the swipe path
     */
    private fun processPathPoint(
        point: PointF,
        keyProbabilities: MutableMap<KeyboardData.Key, Float>
    ) {
        // Find keys near this point
        val nearbyKeys = findNearbyKeys(point)

        // Calculate probability for each nearby key
        for (kwd in nearbyKeys) {
            val probability = calculateGaussianProbability(kwd.distance, kwd.key)

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
    private fun findNearbyKeys(point: PointF): List<KeyWithDistance> {
        val nearbyKeys = mutableListOf<KeyWithDistance>()
        val rows = keyboard.rows ?: return nearbyKeys

        var y = 0f

        for (row in rows) {
            var x = 0f
            val rowHeight = row.height * keyboardHeight

            for (key in row.keys) {
                if (key?.keys?.firstOrNull() == null) {
                    x += (key?.width ?: 0f) * keyboardWidth
                    continue
                }

                // Check if alphabetic
                if (!isAlphabeticKey(key)) {
                    x += key.width * keyboardWidth
                    continue
                }

                val keyWidth = key.width * keyboardWidth

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
     *
     * Formula: exp(-(distance^2) / (2 * sigma^2))
     */
    private fun calculateGaussianProbability(distance: Float, key: KeyboardData.Key): Float {
        // Estimate key size (approximate for QWERTY)
        val keySize = keyboardWidth / 10
        val sigma = keySize * SIGMA_FACTOR

        // Gaussian formula
        return exp(-(distance * distance) / (2 * sigma * sigma))
    }

    /**
     * Extract ordered key sequence from probability map
     */
    private fun extractKeySequence(
        keyProbabilities: Map<KeyboardData.Key, Float>,
        swipePath: List<PointF>
    ): List<KeyboardData.Key> {
        // Filter keys by probability threshold
        val candidates = keyProbabilities.mapNotNull { (key, prob) ->
            val normalizedProb = prob / swipePath.size
            if (normalizedProb > PROBABILITY_THRESHOLD) {
                KeyCandidate(key, normalizedProb)
            } else {
                null
            }
        }.toMutableList()

        // Sort by probability (descending)
        candidates.sortByDescending { it.probability }

        // Order keys by their appearance along the path
        return orderKeysByPath(candidates, swipePath)
    }

    /**
     * Order keys based on when they appear along the swipe path
     */
    private fun orderKeysByPath(
        candidates: List<KeyCandidate>,
        swipePath: List<PointF>
    ): List<KeyboardData.Key> {
        // For each candidate, find its first strong appearance in the path
        candidates.forEach { candidate ->
            candidate.pathIndex = findKeyPathIndex(candidate.key, swipePath)
        }

        // Sort by path index
        return candidates
            .filter { it.pathIndex >= 0 }
            .sortedBy { it.pathIndex }
            .map { it.key }
    }

    /**
     * Find where along the path a key most strongly appears
     *
     * TODO: This is simplified - should use actual key position information
     */
    private fun findKeyPathIndex(key: KeyboardData.Key, swipePath: List<PointF>): Int {
        // Simplified: return middle of path
        // In production, would calculate closest path segment to key center
        return swipePath.size / 2
    }

    /**
     * Check if key is alphabetic
     */
    private fun isAlphabeticKey(key: KeyboardData.Key): Boolean {
        val kv = key.keys.firstOrNull() ?: return false

        // Check if it's a CharKey
        if (kv !is KeyValue.CharKey) {
            return false
        }

        val c = kv.char
        return c in 'a'..'z' || c in 'A'..'Z'
    }

    // ====== Helper Classes ======

    /**
     * Helper class for key with distance information
     */
    private data class KeyWithDistance(
        val key: KeyboardData.Key,
        val distance: Float,
        val keyWidth: Float,
        val keyHeight: Float
    )

    /**
     * Helper class for key candidates with probability
     */
    private data class KeyCandidate(
        val key: KeyboardData.Key,
        val probability: Float,
        var pathIndex: Int = -1
    )
}
