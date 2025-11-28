package tribixbite.keyboard2.personalization

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * User vocabulary tracker for personalized word prediction.
 *
 * Maintains a personal dictionary of words the user frequently types, with usage statistics
 * that enable adaptive prediction boosting. All data is stored locally in SharedPreferences
 * for privacy preservation.
 *
 * Thread-safe for concurrent access during typing.
 *
 * Example:
 * ```kotlin
 * val vocabulary = UserVocabulary(context)
 * vocabulary.recordWordUsage("kotlin") // Learn from typing
 * val boost = vocabulary.getPersonalizationBoost("kotlin") // Get prediction boost
 * vocabulary.cleanupStaleWords() // Periodic maintenance
 * ```
 */
class UserVocabulary(private val context: Context) {
    companion object {
        private const val TAG = "UserVocabulary"
        private const val PREFS_NAME = "user_vocabulary"
        private const val PREFS_KEY_WORDS = "vocabulary_data"

        // Limits to prevent unbounded growth
        private const val MAX_VOCABULARY_SIZE = 5000
        private const val MIN_USAGE_THRESHOLD = 2 // Must use word 2+ times to keep it

        // Cleanup frequency
        private const val CLEANUP_INTERVAL_MS = 86400000L // 24 hours
    }

    // Primary storage: word → usage data
    private val vocabulary: ConcurrentHashMap<String, UserWordUsage> = ConcurrentHashMap()

    // Preferences for persistence
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Gson for JSON serialization
    private val gson = Gson()

    // Track last cleanup time
    private var lastCleanup: Long = System.currentTimeMillis()

    init {
        loadFromPreferences()
        Log.d(TAG, "UserVocabulary initialized with ${vocabulary.size} words")
    }

    /**
     * Record that the user typed a word.
     *
     * If the word is already tracked, increments usage count and updates timestamp.
     * If new, adds to vocabulary with initial usage count of 1.
     */
    fun recordWordUsage(word: String, timestamp: Long = System.currentTimeMillis()) {
        val normalized = UserWordUsage.normalizeWord(word)

        if (normalized.isEmpty() || normalized.length < 2) {
            return // Skip very short words
        }

        synchronized(this) {
            val existing = vocabulary[normalized]

            if (existing != null) {
                // Update existing entry
                vocabulary[normalized] = existing.recordNewUsage(timestamp)
            } else {
                // Add new entry
                if (vocabulary.size < MAX_VOCABULARY_SIZE) {
                    vocabulary[normalized] = UserWordUsage(
                        word = normalized,
                        usageCount = 1,
                        lastUsed = timestamp,
                        firstUsed = timestamp
                    )
                } else {
                    // At capacity, remove least valuable word before adding
                    removeLowestValueWord()
                    vocabulary[normalized] = UserWordUsage(
                        word = normalized,
                        usageCount = 1,
                        lastUsed = timestamp,
                        firstUsed = timestamp
                    )
                }
            }

            // Periodic cleanup (async, non-blocking)
            if (timestamp - lastCleanup > CLEANUP_INTERVAL_MS) {
                performCleanupAsync()
            }
        }

        // Save to preferences asynchronously
        saveToPreferencesAsync()
    }

    /**
     * Get personalization boost for a word.
     *
     * Returns:
     * - 0.0-4.0 for known words (based on frequency and recency)
     * - 0.0 for unknown words
     */
    fun getPersonalizationBoost(word: String, currentTime: Long = System.currentTimeMillis()): Float {
        val normalized = UserWordUsage.normalizeWord(word)
        val usage = vocabulary[normalized] ?: return 0.0f

        return usage.getPersonalizationBoost(currentTime)
    }

    /**
     * Check if a word is in the user's vocabulary.
     */
    fun hasWord(word: String): Boolean {
        val normalized = UserWordUsage.normalizeWord(word)
        return vocabulary.containsKey(normalized)
    }

    /**
     * Get usage statistics for a word.
     */
    fun getWordUsage(word: String): UserWordUsage? {
        val normalized = UserWordUsage.normalizeWord(word)
        return vocabulary[normalized]
    }

    /**
     * Get all words in vocabulary, sorted by personalization boost (descending).
     */
    fun getAllWords(): List<UserWordUsage> {
        val currentTime = System.currentTimeMillis()
        return vocabulary.values
            .sortedByDescending { it.getPersonalizationBoost(currentTime) }
    }

    /**
     * Get top N most personalized words.
     */
    fun getTopWords(limit: Int = 100): List<UserWordUsage> {
        return getAllWords().take(limit)
    }

    /**
     * Get vocabulary size.
     */
    fun size(): Int = vocabulary.size

    /**
     * Remove stale words from vocabulary.
     *
     * A word is stale if:
     * - Last used more than 90 days ago, OR
     * - Only used once and more than 30 days old
     */
    fun cleanupStaleWords(currentTime: Long = System.currentTimeMillis()): Int {
        var removedCount = 0

        synchronized(this) {
            val staleWords = vocabulary.values.filter { it.isStale(currentTime) }

            staleWords.forEach { usage ->
                vocabulary.remove(usage.word)
                removedCount++
            }

            lastCleanup = currentTime
        }

        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount stale words")
            saveToPreferencesAsync()
        }

        return removedCount
    }

    /**
     * Remove words below minimum usage threshold.
     */
    private fun removeLowestValueWord() {
        val currentTime = System.currentTimeMillis()

        // Find word with lowest personalization boost
        val lowestValueWord = vocabulary.values.minByOrNull {
            it.getPersonalizationBoost(currentTime)
        }

        lowestValueWord?.let {
            vocabulary.remove(it.word)
            Log.d(TAG, "Removed low-value word: ${it.word} (boost=${it.getPersonalizationBoost(currentTime)})")
        }
    }

    /**
     * Clear all vocabulary data (user reset).
     */
    fun clearAll() {
        synchronized(this) {
            vocabulary.clear()
            lastCleanup = System.currentTimeMillis()
        }

        prefs.edit().clear().apply()
        Log.d(TAG, "User vocabulary cleared")
    }

    /**
     * Export vocabulary to JSON string.
     */
    fun exportToJson(): String {
        val data = getAllWords()
        return gson.toJson(data)
    }

    /**
     * Import vocabulary from JSON string.
     */
    fun importFromJson(json: String): Int {
        return try {
            val type = object : TypeToken<List<UserWordUsage>>() {}.type
            val imported: List<UserWordUsage> = gson.fromJson(json, type)

            synchronized(this) {
                vocabulary.clear()

                imported.forEach { usage ->
                    if (vocabulary.size < MAX_VOCABULARY_SIZE) {
                        vocabulary[usage.word] = usage
                    }
                }
            }

            saveToPreferencesAsync()
            Log.d(TAG, "Imported ${vocabulary.size} words from JSON")
            vocabulary.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import vocabulary from JSON", e)
            0
        }
    }

    /**
     * Load vocabulary from SharedPreferences.
     */
    private fun loadFromPreferences() {
        val json = prefs.getString(PREFS_KEY_WORDS, null) ?: return

        try {
            val type = object : TypeToken<List<UserWordUsage>>() {}.type
            val loaded: List<UserWordUsage> = gson.fromJson(json, type)

            loaded.forEach { usage ->
                vocabulary[usage.word] = usage
            }

            Log.d(TAG, "Loaded ${vocabulary.size} words from SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocabulary from SharedPreferences", e)
        }
    }

    /**
     * Save vocabulary to SharedPreferences (async, non-blocking).
     */
    private fun saveToPreferencesAsync() {
        thread {
            try {
                val data = getAllWords()
                val json = gson.toJson(data)

                prefs.edit().putString(PREFS_KEY_WORDS, json).apply()
                Log.d(TAG, "Saved ${data.size} words to SharedPreferences")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save vocabulary to SharedPreferences", e)
            }
        }
    }

    /**
     * Perform cleanup asynchronously.
     */
    private fun performCleanupAsync() {
        thread {
            cleanupStaleWords()
        }
    }

    /**
     * Get statistics about the vocabulary.
     */
    fun getStats(): VocabularyStats {
        val currentTime = System.currentTimeMillis()
        val allWords = vocabulary.values.toList()

        return VocabularyStats(
            totalWords = allWords.size,
            averageUsageCount = allWords.map { it.usageCount }.average(),
            mostUsedWord = allWords.maxByOrNull { it.usageCount },
            recentlyUsedCount = allWords.count {
                (currentTime - it.lastUsed) < 7 * 86400000L // Last 7 days
            }
        )
    }
}

/**
 * Statistics about user vocabulary.
 */
data class VocabularyStats(
    val totalWords: Int,
    val averageUsageCount: Double,
    val mostUsedWord: UserWordUsage?,
    val recentlyUsedCount: Int
)
