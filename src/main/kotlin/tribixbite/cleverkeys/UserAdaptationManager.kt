package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.min

/**
 * Manages user adaptation by tracking word selection history and adjusting
 * word frequencies based on user preferences.
 */
class UserAdaptationManager private constructor(context: Context) {
    private val prefs: SharedPreferences
    private val selectionCounts: MutableMap<String, Int> = mutableMapOf()
    private var totalSelections: Int = 0
    private var isEnabled: Boolean = true

    init {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSelectionHistory()
        checkForPeriodicReset()
    }

    /**
     * Record that a word was selected by the user
     */
    fun recordSelection(word: String?) {
        if (!isEnabled || word.isNullOrBlank()) return

        val normalizedWord = word.lowercase().trim()

        // Increment selection count
        val currentCount = selectionCounts.getOrDefault(normalizedWord, 0)
        selectionCounts[normalizedWord] = currentCount + 1
        totalSelections++

        // Limit the number of tracked words to prevent unbounded growth
        if (selectionCounts.size > MAX_TRACKED_WORDS) {
            pruneOldSelections()
        }

        // Save to persistent storage periodically (every 10 selections)
        if (totalSelections % 10 == 0) {
            saveSelectionHistory()
        }

        Log.d(TAG, "Recorded selection: '$normalizedWord' (count: ${currentCount + 1}, total: $totalSelections)")
    }

    /**
     * Get the adaptation multiplier for a word based on selection history
     * Returns 1.0 for no adaptation, >1.0 for frequently selected words
     */
    fun getAdaptationMultiplier(word: String?): Float {
        if (!isEnabled || word == null || totalSelections < MIN_SELECTIONS_FOR_ADAPTATION) {
            return 1.0f
        }

        val normalizedWord = word.lowercase().trim()
        val selectionCount = selectionCounts.getOrDefault(normalizedWord, 0)

        if (selectionCount == 0) {
            return 1.0f
        }

        // Calculate relative frequency (0 to 1)
        val relativeFrequency = selectionCount.toFloat() / totalSelections

        // Apply adaptation strength to boost frequently selected words
        // Words selected often get up to 30% boost (with default ADAPTATION_STRENGTH)
        var multiplier = 1.0f + (relativeFrequency * ADAPTATION_STRENGTH * 10.0f)

        // Cap the maximum boost to prevent any single word from dominating
        multiplier = min(multiplier, 2.0f)

        return multiplier
    }

    /**
     * Get selection count for a specific word
     */
    fun getSelectionCount(word: String?): Int {
        if (word == null) return 0
        return selectionCounts.getOrDefault(word.lowercase().trim(), 0)
    }

    /**
     * Get total number of selections recorded
     */
    fun getTotalSelections(): Int = totalSelections

    /**
     * Get number of unique words being tracked
     */
    fun getTrackedWordCount(): Int = selectionCounts.size

    /**
     * Enable or disable user adaptation
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Log.d(TAG, "User adaptation ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if user adaptation is enabled
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * Reset all adaptation data
     */
    fun resetAdaptation() {
        selectionCounts.clear()
        totalSelections = 0

        // Clear from persistent storage
        prefs.edit().apply {
            clear()
            putLong(KEY_LAST_RESET, System.currentTimeMillis())
            apply()
        }

        Log.d(TAG, "User adaptation data reset")
    }

    /**
     * Get adaptation statistics for debugging
     */
    fun getAdaptationStats(): String {
        if (!isEnabled) {
            return "User adaptation disabled"
        }

        val stats = StringBuilder()
        stats.append("User Adaptation Stats:\n")
        stats.append("- Total selections: $totalSelections\n")
        stats.append("- Unique words tracked: ${selectionCounts.size}\n")
        stats.append("- Adaptation active: ${if (totalSelections >= MIN_SELECTIONS_FOR_ADAPTATION) "Yes" else "No"}\n")

        if (totalSelections >= MIN_SELECTIONS_FOR_ADAPTATION) {
            stats.append("\nTop 10 most selected words:\n")
            selectionCounts.entries
                .sortedByDescending { it.value }
                .take(10)
                .forEach { (word, count) ->
                    val multiplier = getAdaptationMultiplier(word)
                    stats.append("- $word: $count selections (${"%.2f".format(multiplier)}x boost)\n")
                }
        }

        return stats.toString()
    }

    /**
     * Load selection history from persistent storage
     */
    private fun loadSelectionHistory() {
        totalSelections = prefs.getInt(KEY_TOTAL_SELECTIONS, 0)

        // Load individual word counts
        val allPrefs = prefs.all
        for ((key, value) in allPrefs) {
            if (key.startsWith(KEY_WORD_SELECTIONS)) {
                val word = key.substring(KEY_WORD_SELECTIONS.length)
                if (value is Int) {
                    selectionCounts[word] = value
                }
            }
        }

        Log.d(TAG, "Loaded adaptation data: $totalSelections total selections, ${selectionCounts.size} unique words")
    }

    /**
     * Save selection history to persistent storage
     */
    private fun saveSelectionHistory() {
        prefs.edit().apply {
            putInt(KEY_TOTAL_SELECTIONS, totalSelections)

            // Save individual word counts
            for ((word, count) in selectionCounts) {
                putInt(KEY_WORD_SELECTIONS + word, count)
            }

            apply()
        }

        Log.d(TAG, "Saved adaptation data to persistent storage")
    }

    /**
     * Remove least frequently selected words to prevent unbounded growth
     */
    private fun pruneOldSelections() {
        // Find the minimum selection count threshold (remove bottom 20%)
        val targetSize = (MAX_TRACKED_WORDS * 0.8).toInt()
        val originalSize = selectionCounts.size

        val wordsToRemove = selectionCounts.entries
            .sortedBy { it.value }
            .take(selectionCounts.size - targetSize)
            .map { it.key }

        wordsToRemove.forEach { selectionCounts.remove(it) }

        Log.d(TAG, "Pruned selection data from $originalSize to ${selectionCounts.size} words")
    }

    /**
     * Check if it's time for a periodic reset to prevent stale data
     */
    private fun checkForPeriodicReset() {
        val lastReset = prefs.getLong(KEY_LAST_RESET, System.currentTimeMillis())
        val timeSinceReset = System.currentTimeMillis() - lastReset

        if (timeSinceReset > RESET_PERIOD_MS) {
            Log.d(TAG, "Performing periodic reset of adaptation data (30 days elapsed)")
            resetAdaptation()
        }
    }

    /**
     * Cleanup method to be called when the system is destroyed
     */
    fun cleanup() {
        saveSelectionHistory()
    }

    companion object {
        private const val TAG = "UserAdaptationManager"
        private const val PREFS_NAME = "user_adaptation"
        private const val KEY_WORD_SELECTIONS = "word_selections_"
        private const val KEY_TOTAL_SELECTIONS = "total_selections"
        private const val KEY_LAST_RESET = "last_reset"

        // Configuration constants
        private const val MIN_SELECTIONS_FOR_ADAPTATION = 5
        private const val MAX_TRACKED_WORDS = 1000
        private const val ADAPTATION_STRENGTH = 0.3f // How much to boost frequently selected words
        private const val RESET_PERIOD_MS = 30L * 24L * 60L * 60L * 1000L // 30 days

        @Volatile
        private var instance: UserAdaptationManager? = null

        @JvmStatic
        fun getInstance(context: Context): UserAdaptationManager {
            return instance ?: synchronized(this) {
                instance ?: UserAdaptationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
