package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Manages personalization and learning from user typing patterns
 * Features:
 * - Track word usage frequency
 * - Learn new words automatically
 * - Adapt predictions based on user behavior
 * - Context-aware word suggestions
 */
class PersonalizationManager(context: Context) {
    private val prefs: SharedPreferences
    private val wordFrequencies: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
    private val bigrams: ConcurrentHashMap<String, MutableMap<String, Int>> = ConcurrentHashMap()
    private var lastWord = ""

    init {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        val newFreq = min(currentFreq + FREQUENCY_INCREMENT, MAX_FREQUENCY)
        wordFrequencies[normalizedWord] = newFreq

        // Update bigram (word pair) frequency
        if (lastWord.isNotEmpty()) {
            val lastWordBigrams = bigrams.computeIfAbsent(lastWord) { ConcurrentHashMap() }

            val bigramFreq = lastWordBigrams.getOrDefault(normalizedWord, 0)
            lastWordBigrams[normalizedWord] = min(bigramFreq + BIGRAM_INCREMENT, MAX_FREQUENCY)
        }

        lastWord = normalizedWord

        // Save periodically (every 10 words)
        if (wordFrequencies.size % 10 == 0) {
            saveUserData()
        }
    }

    /**
     * Get personalized frequency for a word
     */
    fun getPersonalizedFrequency(word: String?): Float {
        if (word == null) return 0f

        val freq = wordFrequencies[word.lowercase()] ?: return 0f

        // Normalize to 0-1 range
        return freq.toFloat() / MAX_FREQUENCY
    }

    /**
     * Get next word predictions based on context
     */
    fun getNextWordPredictions(previousWord: String?, maxPredictions: Int): Map<String, Float> {
        if (previousWord.isNullOrEmpty()) {
            return emptyMap()
        }

        val normalizedPrevious = previousWord.lowercase()
        val bigramsForWord = bigrams[normalizedPrevious] ?: return emptyMap()

        // Sort by frequency and take top predictions
        return bigramsForWord.entries
            .sortedByDescending { it.value }
            .take(maxPredictions)
            .associate { (word, freq) ->
                word to (freq.toFloat() / MAX_FREQUENCY)
            }
    }

    /**
     * Boost scores for words based on personalization
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
     * Clear personalization data
     */
    fun clearPersonalizationData() {
        wordFrequencies.clear()
        bigrams.clear()
        lastWord = ""

        prefs.edit().apply {
            clear()
            apply()
        }
    }

    /**
     * Apply decay to reduce influence of old words
     */
    fun applyFrequencyDecay() {
        // Decay word frequencies — use explicit iterate+put/remove instead of
        // removeIf+setValue which throws UnsupportedOperationException on API 34
        // (ConcurrentHashMap.EntrySetView returns SimpleImmutableEntry)
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

        // Also decay bigrams
        for (bigramMap in bigrams.values) {
            val bigramsToRemove = mutableListOf<String>()
            for ((word, freq) in bigramMap) {
                val newFreq = freq / DECAY_FACTOR
                if (newFreq > 0) {
                    bigramMap[word] = newFreq
                } else {
                    bigramsToRemove.add(word)
                }
            }
            bigramsToRemove.forEach { bigramMap.remove(it) }
        }

        saveUserData()
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
                    val freq = value as Int
                    wordFrequencies[word] = freq
                }
                key.startsWith(BIGRAM_PREFIX) -> {
                    val bigramKey = key.substring(BIGRAM_PREFIX.length)
                    val parts = bigramKey.split("_", limit = 2)
                    if (parts.size == 2) {
                        val firstWord = parts[0]
                        val secondWord = parts[1]
                        val freq = value as Int

                        val bigramMap = bigrams.computeIfAbsent(firstWord) { ConcurrentHashMap() }
                        bigramMap[secondWord] = freq
                    }
                }
                key == LAST_WORD_KEY -> {
                    lastWord = value as String
                }
            }
        }
    }

    /**
     * Save user data to preferences
     */
    private fun saveUserData() {
        prefs.edit().apply {
            // Save word frequencies (only top 1000 to limit storage)
            wordFrequencies.entries
                .sortedByDescending { it.value }
                .take(1000)
                .forEach { (word, freq) ->
                    putInt(WORD_FREQ_PREFIX + word, freq)
                }

            // Save bigrams (only top 500)
            var bigramCount = 0
            for ((firstWord, bigramMap) in bigrams) {
                for ((secondWord, freq) in bigramMap) {
                    if (bigramCount++ >= 500) break

                    putInt(BIGRAM_PREFIX + firstWord + "_" + secondWord, freq)
                }
            }

            putString(LAST_WORD_KEY, lastWord)
            apply()
        }
    }

    /**
     * Get statistics about personalization data
     */
    fun getStats(): PersonalizationStats {
        val stats = PersonalizationStats()
        stats.totalWords = wordFrequencies.size
        stats.totalBigrams = bigrams.values.sumOf { it.size }

        if (wordFrequencies.isNotEmpty()) {
            stats.mostFrequentWord = wordFrequencies.maxByOrNull { it.value }?.key ?: ""
        }

        return stats
    }

    /**
     * Statistics about personalization data
     */
    data class PersonalizationStats(
        var totalWords: Int = 0,
        var totalBigrams: Int = 0,
        var mostFrequentWord: String = ""
    ) {
        override fun toString(): String {
            return "Words: $totalWords, Bigrams: $totalBigrams, Most frequent: $mostFrequentWord"
        }
    }

    companion object {
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
    }
}
