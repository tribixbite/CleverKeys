package tribixbite.keyboard2

import android.util.Log

/**
 * High-performance vocabulary system for neural swipe predictions
 * Matches web demo's multi-level caching approach for optimal performance
 */
class NeuralVocabulary {
    companion object {
        private const val TAG = "NeuralVocabulary"
    }

    // Multi-level caching for O(1) lookups like web demo
    private val wordFreq: MutableMap<String, Float> = mutableMapOf()
    private val commonWords: MutableSet<String> = mutableSetOf()
    private val wordsByLength: MutableMap<Int, MutableSet<String>> = mutableMapOf()
    private val top5000: MutableSet<String> = mutableSetOf()
    private var isLoadedFlag = false

    // Performance caches
    private val validWordCache: MutableMap<String, Boolean> = mutableMapOf()
    private val minFreqByLength: MutableMap<Int, Float> = mutableMapOf()

    /**
     * Load vocabulary with multi-level caching like web demo
     */
    fun loadVocabulary(): Boolean {
        Log.d(TAG, "Loading vocabulary with multi-level caching...")

        // Force proper dictionary loading - no fallback vocabulary
        Log.e(TAG, "NeuralVocabulary disabled - using OptimizedVocabulary instead")

        // Build performance indexes
        buildPerformanceIndexes()

        isLoadedFlag = true
        Log.d(TAG, "Vocabulary loaded: ${wordFreq.size} words, ${wordsByLength.size} by length, " +
            "${commonWords.size} common")

        return true
    }

    /**
     * Ultra-fast word validation with caching
     */
    fun isValidWord(word: String): Boolean {
        if (!isLoadedFlag) return false

        // Check cache first (O(1))
        validWordCache[word]?.let { return it }

        // Fast path - check common words set (O(1))
        if (commonWords.contains(word)) {
            validWordCache[word] = true
            return true
        }

        // Check by length set (O(1))
        val wordsOfLength = wordsByLength[word.length]
        val valid = wordsOfLength != null && wordsOfLength.contains(word)

        // Cache result
        validWordCache[word] = valid
        return valid
    }

    /**
     * Get word frequency (cached)
     */
    fun getWordFrequency(word: String): Float {
        return wordFreq[word] ?: 0.0f
    }

    /**
     * Filter predictions like web demo
     */
    fun filterPredictions(predictions: List<String>): List<String> {
        if (!isLoadedFlag) return predictions

        return predictions.filter { isValidWord(it) }
    }

    private fun buildPerformanceIndexes() {
        // Build words by length index for O(1) length-based filtering
        for ((word, freq) in wordFreq) {
            val length = word.length
            val wordsOfLength = wordsByLength.getOrPut(length) { mutableSetOf() }
            wordsOfLength.add(word)

            // Track minimum frequency by length
            val currentMinFreq = minFreqByLength[length]
            if (currentMinFreq == null || freq < currentMinFreq) {
                minFreqByLength[length] = freq
            }
        }
    }

    fun isLoaded(): Boolean {
        return isLoadedFlag
    }

    fun getVocabularySize(): Int {
        return wordFreq.size
    }
}
