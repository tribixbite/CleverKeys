package tribixbite.keyboard2

import android.util.Log

/**
 * High-performance vocabulary system for neural swipe predictions.
 * Matches web demo's multi-level caching approach for optimal performance.
 *
 * Features:
 * - Multi-level caching (word frequency, common words, words by length)
 * - O(1) word validation with cache
 * - O(1) frequency lookups
 * - Length-based word indexing
 * - Top 5000 word tracking
 * - Performance-optimized for neural predictions
 *
 * Caching Strategy:
 * 1. Valid word cache: HashMap<String, Boolean> for instant validation
 * 2. Common words set: HashSet for top frequent words
 * 3. Words by length: HashMap<Int, Set<String>> for length filtering
 * 4. Top 5000 set: HashSet for common vocabulary
 * 5. Min frequency by length: HashMap<Int, Float> for threshold checks
 *
 * Ported from Java to Kotlin with improvements.
 */
class NeuralVocabulary {

    companion object {
        private const val TAG = "NeuralVocabulary"
    }

    // Multi-level caching for O(1) lookups like web demo
    private val wordFreq = mutableMapOf<String, Float>()
    private val commonWords = mutableSetOf<String>()
    private val wordsByLength = mutableMapOf<Int, MutableSet<String>>()
    private val top5000 = mutableSetOf<String>()

    // Performance caches
    private val validWordCache = mutableMapOf<String, Boolean>()
    private val minFreqByLength = mutableMapOf<Int, Float>()

    private var isLoaded = false

    /**
     * Load vocabulary with multi-level caching like web demo
     *
     * @param dictionary Map of words to frequencies
     * @return true if loaded successfully
     */
    fun loadVocabulary(dictionary: Map<String, Int>): Boolean {
        Log.d(TAG, "Loading vocabulary with multi-level caching...")

        // Clear existing data
        wordFreq.clear()
        commonWords.clear()
        wordsByLength.clear()
        top5000.clear()
        validWordCache.clear()
        minFreqByLength.clear()

        // Load word frequencies (convert Int to Float)
        dictionary.forEach { (word, freq) ->
            wordFreq[word] = freq.toFloat()
        }

        // Build performance indexes
        buildPerformanceIndexes()

        isLoaded = true
        Log.d(
            TAG, "Vocabulary loaded: ${wordFreq.size} words, " +
                "${wordsByLength.size} by length, ${commonWords.size} common"
        )

        return true
    }

    /**
     * Load vocabulary with Float frequencies directly
     *
     * @param dictionary Map of words to Float frequencies
     * @return true if loaded successfully
     */
    fun loadVocabularyWithFloats(dictionary: Map<String, Float>): Boolean {
        Log.d(TAG, "Loading vocabulary with multi-level caching...")

        // Clear existing data
        wordFreq.clear()
        commonWords.clear()
        wordsByLength.clear()
        top5000.clear()
        validWordCache.clear()
        minFreqByLength.clear()

        // Load word frequencies
        wordFreq.putAll(dictionary)

        // Build performance indexes
        buildPerformanceIndexes()

        isLoaded = true
        Log.d(
            TAG, "Vocabulary loaded: ${wordFreq.size} words, " +
                "${wordsByLength.size} by length, ${commonWords.size} common"
        )

        return true
    }

    /**
     * Ultra-fast word validation with caching
     *
     * Time complexity: O(1) after first lookup
     *
     * @param word Word to validate
     * @return true if word exists in vocabulary
     */
    fun isValidWord(word: String): Boolean {
        if (!isLoaded) return false

        // Check cache first (O(1))
        validWordCache[word]?.let { return it }

        // Fast path - check common words set (O(1))
        if (commonWords.contains(word)) {
            validWordCache[word] = true
            return true
        }

        // Check by length set (O(1))
        val wordsOfLength = wordsByLength[word.length]
        val valid = wordsOfLength?.contains(word) ?: false

        // Cache result
        validWordCache[word] = valid
        return valid
    }

    /**
     * Get word frequency (cached)
     *
     * @param word Word to look up
     * @return Frequency value, or 0.0f if not found
     */
    fun getWordFrequency(word: String): Float {
        return wordFreq[word] ?: 0.0f
    }

    /**
     * Filter predictions to only valid vocabulary words
     *
     * @param predictions List of prediction candidates
     * @return Filtered list containing only valid words
     */
    fun filterPredictions(predictions: List<String>): List<String> {
        if (!isLoaded) return predictions

        return predictions.filter { isValidWord(it) }
    }

    /**
     * Get all words of a specific length
     *
     * @param length Word length
     * @return Set of words with that length
     */
    fun getWordsByLength(length: Int): Set<String> {
        return wordsByLength[length]?.toSet() ?: emptySet()
    }

    /**
     * Get minimum frequency for words of a specific length
     *
     * @param length Word length
     * @return Minimum frequency, or 0.0f if no words of that length
     */
    fun getMinFrequencyByLength(length: Int): Float {
        return minFreqByLength[length] ?: 0.0f
    }

    /**
     * Check if word is in top 5000 most common words
     *
     * @param word Word to check
     * @return true if in top 5000
     */
    fun isTopWord(word: String): Boolean {
        return top5000.contains(word)
    }

    /**
     * Build performance indexes for O(1) lookups
     */
    private fun buildPerformanceIndexes() {
        // Sort words by frequency to identify common words and top 5000
        val sortedByFreq = wordFreq.entries
            .sortedByDescending { it.value }

        // Top 5000 words
        sortedByFreq.take(5000).forEach { (word, _) ->
            top5000.add(word)
        }

        // Top 1000 as "common words"
        sortedByFreq.take(1000).forEach { (word, _) ->
            commonWords.add(word)
        }

        // Build words by length index for O(1) length-based filtering
        for ((word, freq) in wordFreq) {
            val length = word.length

            // Add to length index
            wordsByLength.getOrPut(length) { mutableSetOf() }.add(word)

            // Track minimum frequency by length
            val currentMinFreq = minFreqByLength[length]
            if (currentMinFreq == null || freq < currentMinFreq) {
                minFreqByLength[length] = freq
            }
        }
    }

    /**
     * Check if vocabulary is loaded
     */
    fun isLoaded(): Boolean = isLoaded

    /**
     * Get total vocabulary size
     */
    fun getVocabularySize(): Int = wordFreq.size

    /**
     * Get number of unique word lengths
     */
    fun getLengthVariety(): Int = wordsByLength.size

    /**
     * Get statistics about the vocabulary
     */
    fun getStats(): String {
        return buildString {
            append("NeuralVocabulary Statistics:\n")
            append("- Total words: ${wordFreq.size}\n")
            append("- Common words (top 1000): ${commonWords.size}\n")
            append("- Top 5000: ${top5000.size}\n")
            append("- Unique lengths: ${wordsByLength.size}\n")
            append("- Cached validations: ${validWordCache.size}\n")
            append("- Is loaded: $isLoaded\n")

            if (wordsByLength.isNotEmpty()) {
                val lengths = wordsByLength.keys.sorted()
                append("- Length range: ${lengths.first()}-${lengths.last()} chars\n")

                val lengthCounts = wordsByLength.map { (len, words) -> len to words.size }
                    .sortedByDescending { it.second }
                    .take(5)

                append("- Most common lengths:\n")
                lengthCounts.forEach { (len, count) ->
                    append("    $len chars: $count words\n")
                }
            }
        }
    }

    /**
     * Clear all caches (useful for memory management)
     */
    fun clearCaches() {
        validWordCache.clear()
        Log.d(TAG, "Cleared validation cache")
    }

    /**
     * Get cache hit statistics
     *
     * @return Percentage of lookups that were cached
     */
    fun getCacheHitRate(): Float {
        if (validWordCache.isEmpty()) return 0.0f
        // This is simplified - a full implementation would track hits vs misses
        return validWordCache.size.toFloat() / wordFreq.size.coerceAtLeast(1)
    }
}
