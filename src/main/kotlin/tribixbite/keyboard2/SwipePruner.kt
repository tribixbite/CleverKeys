package tribixbite.keyboard2

import android.graphics.PointF
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Prunes candidate words for swipe typing based on extremities.
 * Based on FlorisBoard's pruning approach.
 *
 * Features:
 * - Extremity-based pruning (first-last letter pairs)
 * - Path length-based filtering
 * - Fast lookup using HashMap
 * - Fallback strategies when no exact matches
 *
 * This significantly reduces the search space for DTW/prediction algorithms,
 * improving performance by 10-100x for large dictionaries.
 *
 * Ported from Java to Kotlin with improvements.
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
    private val extremityMap = mutableMapOf<String, MutableList<String>>()

    init {
        buildExtremityMap()
    }

    /**
     * Build a map of first-last letter pairs to words for fast lookup
     *
     * Example:
     * "hello" → "ho" → ["hello"]
     * "help" → "hp" → ["help"]
     * "the" → "te" → ["the"]
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
     *
     * @param swipePath The full swipe path
     * @param touchedChars Characters touched along the swipe (in order)
     * @return Pruned list of candidate words
     */
    fun pruneByExtremities(swipePath: List<PointF>, touchedChars: List<Char>): List<String> {
        if (swipePath.size < 2 || touchedChars.isEmpty()) {
            return dictionary.keys.toList()
        }

        // Get start and end characters (use first N_CLOSEST_KEYS from each end)
        val startKeys = touchedChars.take(N_CLOSEST_KEYS).distinct()
        val endKeys = touchedChars.takeLast(N_CLOSEST_KEYS).distinct()

        // Build candidate list from all combinations
        val candidates = mutableListOf<String>()
        for (startKey in startKeys) {
            for (endKey in endKeys) {
                val extremityKey = "$startKey$endKey"
                extremityMap[extremityKey]?.let { words ->
                    candidates.addAll(words)
                }
            }
        }

        // If no candidates found with extremities, be less strict
        if (candidates.isEmpty() && touchedChars.isNotEmpty()) {
            Log.d(TAG, "No candidates with extremities, falling back to first/last touched")

            val first = touchedChars.first()
            val last = touchedChars.last()
            val extremityKey = "$first$last"

            extremityMap[extremityKey]?.let { words ->
                candidates.addAll(words)
            }
        }

        val result = if (candidates.isEmpty()) {
            dictionary.keys.toList()
        } else {
            candidates.distinct()
        }

        Log.d(TAG, "Pruned to ${result.size} candidates from ${dictionary.size}")
        return result
    }

    /**
     * Simplified version that takes start and end characters directly
     *
     * @param startChar First character of swipe
     * @param endChar Last character of swipe
     * @return Pruned list of candidate words
     */
    fun pruneByExtremities(startChar: Char, endChar: Char): List<String> {
        val extremityKey = "$startChar$endChar"
        return extremityMap[extremityKey]?.toList() ?: dictionary.keys.toList()
    }

    /**
     * Prune candidates by path length similarity.
     * Words that are too different in length from the swipe path are removed.
     *
     * @param swipePath The swipe path
     * @param candidates Current candidate list
     * @param keyWidth Average key width (in pixels)
     * @param lengthThreshold Tolerance factor (default 3.0 = 3x key width)
     * @return Filtered candidate list
     */
    fun pruneByLength(
        swipePath: List<PointF>,
        candidates: List<String>,
        keyWidth: Float,
        lengthThreshold: Float = 3.0f
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
            // Approximate as (word.length - 1) * average key distance
            val idealLength = (word.length - 1) * keyWidth * 0.8f

            // Check if within threshold
            abs(pathLength - idealLength) < lengthThreshold * keyWidth
        }

        Log.d(TAG, "Length pruning: ${candidates.size} -> ${filtered.size}")

        return if (filtered.isEmpty()) candidates else filtered
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
     * Get all words that start with a specific character
     */
    fun getWordsStartingWith(c: Char): List<String> {
        return dictionary.keys.filter { it.isNotEmpty() && it[0] == c }
    }

    /**
     * Get all words that end with a specific character
     */
    fun getWordsEndingWith(c: Char): List<String> {
        return dictionary.keys.filter { it.isNotEmpty() && it[it.length - 1] == c }
    }

    /**
     * Get all unique first-last letter pairs in dictionary
     */
    fun getExtremityPairs(): Set<String> {
        return extremityMap.keys
    }

    /**
     * Get statistics about the pruner
     */
    fun getStats(): String {
        return buildString {
            append("SwipePruner Statistics:\n")
            append("- Dictionary size: ${dictionary.size}\n")
            append("- Extremity pairs: ${extremityMap.size}\n")
            append("- Avg words per pair: ${dictionary.size.toFloat() / extremityMap.size.coerceAtLeast(1)}\n")

            val pairCounts = extremityMap.values.map { it.size }.sorted()
            if (pairCounts.isNotEmpty()) {
                append("- Min words per pair: ${pairCounts.first()}\n")
                append("- Max words per pair: ${pairCounts.last()}\n")
                append("- Median words per pair: ${pairCounts[pairCounts.size / 2]}\n")
            }
        }
    }

    /**
     * Estimate pruning efficiency for a given extremity pair
     *
     * @param startChar First character
     * @param endChar Last character
     * @return Reduction ratio (e.g., 0.1 means 90% reduction)
     */
    fun estimatePruningEfficiency(startChar: Char, endChar: Char): Float {
        val extremityKey = "$startChar$endChar"
        val candidateCount = extremityMap[extremityKey]?.size ?: dictionary.size
        return candidateCount.toFloat() / dictionary.size
    }
}
