package tribixbite.cleverkeys

import android.graphics.PointF
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Prunes candidate words for swipe typing based on extremities.
 * Based on FlorisBoard's pruning approach.
 */
class SwipePruner(private val dictionary: Map<String, Int>) {
    companion object {
        private const val TAG = "SwipePruner"

        // Distance threshold for considering a key "close" to a point (in normalized units)
        private const val KEY_PROXIMITY_THRESHOLD = 0.15f

        // Number of closest keys to consider for start/end points
        private const val N_CLOSEST_KEYS = 2
    }

    // Map of first-last letter pairs to words
    private val extremityMap: MutableMap<String, MutableList<String>> = mutableMapOf()

    init {
        buildExtremityMap()
    }

    /**
     * Build a map of first-last letter pairs to words for fast lookup
     */
    private fun buildExtremityMap() {
        for (word in dictionary.keys) {
            if (word.length < 2) continue

            val first = word[0]
            val last = word[word.length - 1]
            val key = "$first$last"

            extremityMap.getOrPut(key) { mutableListOf() }.add(word)
        }

        Log.d(TAG, "Built extremity map with ${extremityMap.size} unique pairs")
    }

    /**
     * Find candidate words based on the start and end points of a swipe gesture.
     * This significantly reduces the search space for DTW/prediction algorithms.
     */
    fun pruneByExtremities(
        swipePath: List<PointF>,
        touchedKeys: List<KeyboardData.Key>
    ): List<String> {
        if (swipePath.size < 2 || touchedKeys.isEmpty()) {
            return dictionary.keys.toList()
        }

        // Get start and end points
        val startPoint = swipePath[0]
        val endPoint = swipePath[swipePath.size - 1]

        // Find the closest keys to start and end points
        val startKeys = findClosestKeys(startPoint, touchedKeys, N_CLOSEST_KEYS)
        val endKeys = findClosestKeys(endPoint, touchedKeys, N_CLOSEST_KEYS)

        // Build candidate list from all combinations
        val candidates = mutableListOf<String>()
        for (startKey in startKeys) {
            for (endKey in endKeys) {
                val extremityKey = "$startKey$endKey"
                extremityMap[extremityKey]?.let { candidates.addAll(it) }
            }
        }

        // If no candidates found with extremities, be less strict
        if (candidates.isEmpty()) {
            Log.d(TAG, "No candidates with extremities, falling back to touched keys")
            // Fall back to using first and last touched keys
            if (touchedKeys.isNotEmpty()) {
                val firstKey = touchedKeys[0]
                val lastKey = touchedKeys[touchedKeys.size - 1]

                val firstKv = firstKey.keys.getOrNull(0)
                val lastKv = lastKey.keys.getOrNull(0)
                if (firstKv != null && lastKv != null) {
                    val first = firstKv.getString().lowercase()[0]
                    val last = lastKv.getString().lowercase()[0]
                    val extremityKey = "$first$last"
                    extremityMap[extremityKey]?.let { candidates.addAll(it) }
                }
            }
        }

        Log.d(TAG, "Pruned to ${candidates.size} candidates from ${dictionary.size}")

        return if (candidates.isEmpty()) dictionary.keys.toList() else candidates
    }

    /**
     * Find the N closest keys to a given point
     * Since we don't have key positions, use the touched keys list
     */
    private fun findClosestKeys(
        point: PointF,
        keys: List<KeyboardData.Key>,
        n: Int
    ): List<Char> {
        val result = mutableListOf<Char>()

        // Since we don't have key positions in KeyboardData.Key,
        // we'll use the first and last touched keys as approximation
        // This is a simplified approach - ideally we'd have access to key bounds

        for (key in keys) {
            val kv = key.keys.getOrNull(0)
            if (kv == null || !isAlphabeticKey(kv)) continue

            val keyChar = kv.getString().lowercase()[0]
            if (!result.contains(keyChar)) {
                result.add(keyChar)
            }

            if (result.size >= n) break
        }

        return result
    }

    /**
     * Calculate distance between two points
     */
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Check if a key value represents an alphabetic character
     */
    private fun isAlphabeticKey(kv: KeyValue?): Boolean {
        if (kv == null) return false

        return when (kv.getKind()) {
            KeyValue.Kind.Char -> {
                val c = kv.getChar()
                c in 'a'..'z' || c in 'A'..'Z'
            }
            KeyValue.Kind.String -> {
                val s = kv.getString()
                s.length == 1 && (s[0] in 'a'..'z' || s[0] in 'A'..'Z')
            }
            else -> false
        }
    }

    /**
     * Simple class to hold key-distance pairs
     */
    private data class KeyDistance(
        val key: Char,
        val distance: Float
    )

    /**
     * Prune candidates by path length similarity.
     * Words that are too different in length from the swipe path are removed.
     */
    fun pruneByLength(
        swipePath: List<PointF>,
        candidates: List<String>,
        keyWidth: Float,
        lengthThreshold: Float
    ): List<String> {
        if (swipePath.size < 2) return candidates

        // Calculate total swipe path length
        var pathLength = 0f
        for (i in 1 until swipePath.size) {
            val p1 = swipePath[i - 1]
            val p2 = swipePath[i]
            pathLength += distance(p1.x, p1.y, p2.x, p2.y)
        }

        val filtered = candidates.filter { word ->
            // Estimate ideal path length for this word
            // Approximate as (word.length() - 1) * average key distance
            val idealLength = (word.length - 1) * keyWidth * 0.8f

            // Check if within threshold
            abs(pathLength - idealLength) < lengthThreshold * keyWidth
        }

        Log.d(TAG, "Length pruning: ${candidates.size} -> ${filtered.size}")

        return if (filtered.isEmpty()) candidates else filtered
    }
}
