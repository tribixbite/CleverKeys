package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages personalization and learning from user typing patterns.
 *
 * Features:
 * - Track word usage frequency
 * - Learn new words automatically
 * - Adapt predictions based on user behavior
 * - Context-aware word suggestions using bigrams
 * - Periodic frequency decay to reduce old patterns
 * - Persistent storage with size limits
 *
 * Ported from Java to Kotlin with improvements.
 */
class PersonalizationManager(private val context: Context) {

    companion object {
        private const val TAG = "PersonalizationManager"
        private const val PREFS_NAME = "swipe_personalization"
        private const val WORD_FREQ_PREFIX = "freq_"
        private const val BIGRAM_PREFIX = "bigram_"
        private const val LAST_WORD_KEY = "last_word"

        // Learning parameters
        private const val MIN_WORD_LENGTH = 2
        private const val MAX_WORD_LENGTH = 20
        private const val FREQUENCY_INCREMENT = 10
        private const val MAX_FREQUENCY = 10000
        private const val BIGRAM_INCREMENT = 5
        private const val DECAY_FACTOR = 2 // Reduce old frequencies by half periodically

        // Storage limits
        private const val MAX_STORED_WORDS = 1000
        private const val MAX_STORED_BIGRAMS = 500
    }

    /**
     * Statistics about personalization data
     */
    data class PersonalizationStats(
        val totalWords: Int = 0,
        val totalBigrams: Int = 0,
        val mostFrequentWord: String = ""
    ) {
        override fun toString(): String {
            return "Words: $totalWords, Bigrams: $totalBigrams, Most frequent: $mostFrequentWord"
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val wordFrequencies = ConcurrentHashMap<String, Int>()
    private val bigrams = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private var lastWord = ""

    init {
        loadUserData()
    }

    /**
     * Record that a word was typed by the user
     */
    fun recordWordUsage(word: String?) {
        if (word == null || word.length < MIN_WORD_LENGTH || word.length > MAX_WORD_LENGTH) {
            return
        }

        val normalizedWord = word.lowercase().trim()

        // Update word frequency
        val currentFreq = wordFrequencies.getOrDefault(normalizedWord, 0)
        val newFreq = (currentFreq + FREQUENCY_INCREMENT).coerceAtMost(MAX_FREQUENCY)
        wordFrequencies[normalizedWord] = newFreq

        // Update bigram (word pair) frequency
        if (lastWord.isNotEmpty()) {
            val lastWordBigrams = bigrams.getOrPut(lastWord) { ConcurrentHashMap() }
            val bigramFreq = lastWordBigrams.getOrDefault(normalizedWord, 0)
            lastWordBigrams[normalizedWord] =
                (bigramFreq + BIGRAM_INCREMENT).coerceAtMost(MAX_FREQUENCY)
        }

        lastWord = normalizedWord

        // Save periodically (every 10 words)
        if (wordFrequencies.size % 10 == 0) {
            saveUserData()
        }
    }

    /**
     * Get personalized frequency for a word
     * @return Normalized frequency [0, 1]
     */
    fun getPersonalizedFrequency(word: String?): Float {
        if (word == null) return 0f

        val normalizedWord = word.lowercase()
        val freq = wordFrequencies[normalizedWord] ?: return 0f

        // Normalize to 0-1 range
        return freq.toFloat() / MAX_FREQUENCY
    }

    /**
     * Get next word predictions based on context
     * @param previousWord The previous word for context
     * @param maxPredictions Maximum number of predictions to return
     * @return Map of word to normalized frequency score [0, 1]
     */
    fun getNextWordPredictions(previousWord: String?, maxPredictions: Int): Map<String, Float> {
        if (previousWord.isNullOrEmpty()) {
            return emptyMap()
        }

        val normalizedPrev = previousWord.lowercase()
        val wordBigrams = bigrams[normalizedPrev] ?: return emptyMap()

        // Sort by frequency and take top predictions
        return wordBigrams.entries
            .sortedByDescending { it.value }
            .take(maxPredictions)
            .associate { (word, freq) ->
                word to (freq.toFloat() / MAX_FREQUENCY)
            }
    }

    /**
     * Boost scores for words based on personalization
     * @param word The word to score
     * @param baseScore The base prediction score
     * @return Adjusted score combining base score (70%) and personal frequency (30%)
     */
    fun adjustScoreWithPersonalization(word: String, baseScore: Float): Float {
        val personalFreq = getPersonalizedFrequency(word)

        // Combine base score with personal frequency
        // Give 30% weight to personalization
        return baseScore * 0.7f + personalFreq * 0.3f
    }

    /**
     * Check if user has typed this word before
     */
    fun isKnownWord(word: String): Boolean {
        return wordFrequencies.containsKey(word.lowercase())
    }

    /**
     * Clear all personalization data
     */
    fun clearPersonalizationData() {
        wordFrequencies.clear()
        bigrams.clear()
        lastWord = ""

        prefs.edit().clear().apply()
        Log.d(TAG, "Personalization data cleared")
    }

    /**
     * Apply decay to reduce influence of old words
     * Divides all frequencies by DECAY_FACTOR and removes words with frequency < 1
     */
    fun applyFrequencyDecay() {
        // Decay word frequencies
        val wordsToRemove = mutableListOf<String>()
        for ((word, freq) in wordFrequencies) {
            val newFreq = freq / DECAY_FACTOR
            if (newFreq > 0) {
                wordFrequencies[word] = newFreq
            } else {
                wordsToRemove.add(word)
            }
        }
        wordsToRemove.forEach { wordFrequencies.remove(it) }

        // Decay bigrams
        val bigramsToRemove = mutableListOf<String>()
        for ((firstWord, bigramMap) in bigrams) {
            val entriesToRemove = mutableListOf<String>()
            for ((secondWord, freq) in bigramMap) {
                val newFreq = freq / DECAY_FACTOR
                if (newFreq > 0) {
                    bigramMap[secondWord] = newFreq
                } else {
                    entriesToRemove.add(secondWord)
                }
            }
            entriesToRemove.forEach { bigramMap.remove(it) }

            // Remove empty bigram maps
            if (bigramMap.isEmpty()) {
                bigramsToRemove.add(firstWord)
            }
        }
        bigramsToRemove.forEach { bigrams.remove(it) }

        saveUserData()
        Log.d(TAG, "Frequency decay applied")
    }

    /**
     * Load user data from preferences
     */
    private fun loadUserData() {
        val allPrefs = prefs.all

        for ((key, value) in allPrefs) {
            when {
                key.startsWith(WORD_FREQ_PREFIX) -> {
                    val word = key.substring(WORD_FREQ_PREFIX.length)
                    val freq = value as? Int ?: continue
                    wordFrequencies[word] = freq
                }

                key.startsWith(BIGRAM_PREFIX) -> {
                    val bigramKey = key.substring(BIGRAM_PREFIX.length)
                    val parts = bigramKey.split("_", limit = 2)
                    if (parts.size == 2) {
                        val firstWord = parts[0]
                        val secondWord = parts[1]
                        val freq = value as? Int ?: continue

                        val bigramMap = bigrams.getOrPut(firstWord) { ConcurrentHashMap() }
                        bigramMap[secondWord] = freq
                    }
                }

                key == LAST_WORD_KEY -> {
                    lastWord = value as? String ?: ""
                }
            }
        }

        Log.d(TAG, "Loaded ${wordFrequencies.size} words, ${getTotalBigrams()} bigrams")
    }

    /**
     * Save user data to preferences
     * Limits storage to top 1000 words and 500 bigrams
     */
    private fun saveUserData() {
        val editor = prefs.edit()

        // Clear old data
        editor.clear()

        // Save word frequencies (only top 1000 to limit storage)
        wordFrequencies.entries
            .sortedByDescending { it.value }
            .take(MAX_STORED_WORDS)
            .forEach { (word, freq) ->
                editor.putInt(WORD_FREQ_PREFIX + word, freq)
            }

        // Save bigrams (only top 500 by frequency)
        var bigramCount = 0
        val allBigrams = mutableListOf<Triple<String, String, Int>>()

        for ((firstWord, bigramMap) in bigrams) {
            for ((secondWord, freq) in bigramMap) {
                allBigrams.add(Triple(firstWord, secondWord, freq))
            }
        }

        allBigrams
            .sortedByDescending { it.third }
            .take(MAX_STORED_BIGRAMS)
            .forEach { (firstWord, secondWord, freq) ->
                editor.putInt(BIGRAM_PREFIX + firstWord + "_" + secondWord, freq)
                bigramCount++
            }

        editor.putString(LAST_WORD_KEY, lastWord)
        editor.apply()

        Log.d(TAG, "Saved ${wordFrequencies.size} words (max $MAX_STORED_WORDS), " +
                   "$bigramCount bigrams (max $MAX_STORED_BIGRAMS)")
    }

    /**
     * Get total number of bigrams
     */
    private fun getTotalBigrams(): Int {
        return bigrams.values.sumOf { it.size }
    }

    /**
     * Get statistics about personalization data
     */
    fun getStats(): PersonalizationStats {
        val mostFrequentWord = wordFrequencies.maxByOrNull { it.value }?.key ?: ""

        return PersonalizationStats(
            totalWords = wordFrequencies.size,
            totalBigrams = getTotalBigrams(),
            mostFrequentWord = mostFrequentWord
        )
    }

    /**
     * Get detailed statistics for debugging
     */
    fun getDetailedStats(): String {
        val stats = getStats()
        return buildString {
            append("PersonalizationManager Statistics:\n")
            append("- Total words: ${stats.totalWords}\n")
            append("- Total bigrams: ${stats.totalBigrams}\n")
            append("- Most frequent word: '${stats.mostFrequentWord}'\n")
            if (stats.mostFrequentWord.isNotEmpty()) {
                val freq = wordFrequencies[stats.mostFrequentWord]
                append("- Most frequent frequency: $freq\n")
            }
            append("- Last word: '$lastWord'\n")
        }
    }
}
