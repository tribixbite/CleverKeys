package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages user adaptation by tracking word selection history and adjusting
 * word frequencies based on user preferences.
 *
 * Features:
 * - Persistent storage via SharedPreferences
 * - Adaptive frequency boosting (up to 2x for frequently selected words)
 * - Automatic pruning to prevent unbounded growth
 * - Periodic reset (30 days) to prevent stale data
 * - Thread-safe concurrent access
 *
 * Fix for Bug #312: FrequencyModel missing (CATASTROPHIC)
 */
class UserAdaptationManager private constructor(context: Context) {

    companion object {
        private const val TAG = "UserAdaptationManager"
        private const val PREFS_NAME = "user_adaptation"
        private const val KEY_WORD_SELECTIONS = "word_selections_"
        private const val KEY_TOTAL_SELECTIONS = "total_selections"
        private const val KEY_LAST_RESET = "last_reset"

        // Configuration constants
        private const val MIN_SELECTIONS_FOR_ADAPTATION = 5
        private const val MAX_TRACKED_WORDS = 1000
        private const val ADAPTATION_STRENGTH = 0.3f  // How much to boost frequently selected words
        private const val RESET_PERIOD_MS = 30L * 24L * 60L * 60L * 1000L  // 30 days

        @Volatile
        private var instance: UserAdaptationManager? = null

        fun getInstance(context: Context): UserAdaptationManager {
            return instance ?: synchronized(this) {
                instance ?: UserAdaptationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val selectionCounts = ConcurrentHashMap<String, Int>()
    private var totalSelections = 0
    private var isEnabled = true

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        loadSelectionHistory()
        checkForPeriodicReset()
    }

    /**
     * Record that a word was selected by the user
     * Thread-safe and asynchronous
     */
    fun recordSelection(word: String) {
        if (!isEnabled || word.isBlank()) return

        val normalizedWord = word.lowercase().trim()

        // Increment selection count (thread-safe with ConcurrentHashMap)
        val currentCount = selectionCounts.getOrDefault(normalizedWord, 0)
        selectionCounts[normalizedWord] = currentCount + 1
        totalSelections++

        // Limit the number of tracked words to prevent unbounded growth
        if (selectionCounts.size > MAX_TRACKED_WORDS) {
            pruneOldSelections()
        }

        // Save to persistent storage periodically (every 10 selections)
        if (totalSelections % 10 == 0) {
            scope.launch {
                saveSelectionHistory()
            }
        }

        Log.d(TAG, "Recorded selection: '$normalizedWord' (count: ${currentCount + 1}, total: $totalSelections)")
    }

    /**
     * Get the adaptation multiplier for a word based on selection history
     * Returns 1.0 for no adaptation, >1.0 for frequently selected words
     */
    fun getAdaptationMultiplier(word: String): Float {
        if (!isEnabled || totalSelections < MIN_SELECTIONS_FOR_ADAPTATION) {
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
        multiplier = multiplier.coerceAtMost(2.0f)

        return multiplier
    }

    /**
     * Get selection count for a specific word
     */
    fun getSelectionCount(word: String): Int {
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
                    stats.append("- $word: $count selections (${String.format("%.2fx", multiplier)} boost)\n")
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
            if (key.startsWith(KEY_WORD_SELECTIONS) && value is Int) {
                val word = key.substring(KEY_WORD_SELECTIONS.length)
                selectionCounts[word] = value
            }
        }

        Log.d(TAG, "Loaded adaptation data: $totalSelections total selections, ${selectionCounts.size} unique words")
    }

    /**
     * Save selection history to persistent storage
     * Async operation to avoid blocking UI
     */
    private suspend fun saveSelectionHistory() = withContext(Dispatchers.IO) {
        val editor = prefs.edit()
        editor.putInt(KEY_TOTAL_SELECTIONS, totalSelections)

        // Save individual word counts
        for ((word, count) in selectionCounts) {
            editor.putInt(KEY_WORD_SELECTIONS + word, count)
        }

        editor.apply()
        Log.d(TAG, "Saved adaptation data to persistent storage")
    }

    /**
     * Remove least frequently selected words to prevent unbounded growth
     * Removes bottom 20% when max capacity is reached
     */
    private fun pruneOldSelections() {
        val targetSize = (MAX_TRACKED_WORDS * 0.8).toInt()
        val originalSize = selectionCounts.size

        // Get words sorted by selection count (ascending)
        val wordsToRemove = selectionCounts.entries
            .sortedBy { it.value }
            .take(selectionCounts.size - targetSize)
            .map { it.key }

        // Remove the least frequently selected words
        wordsToRemove.forEach { word ->
            selectionCounts.remove(word)
            prefs.edit().remove(KEY_WORD_SELECTIONS + word).apply()
        }

        Log.d(TAG, "Pruned selection data from $originalSize to ${selectionCounts.size} words")
    }

    /**
     * Check if it's time for a periodic reset to prevent stale data
     * Resets every 30 days automatically
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
     * Ensures all data is persisted before shutdown
     */
    fun cleanup() {
        runBlocking {
            saveSelectionHistory()
        }
        scope.cancel()
    }

    /**
     * Get top N most selected words for debugging/analytics
     */
    fun getTopSelectedWords(limit: Int = 10): List<Pair<String, Int>> {
        return selectionCounts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    /**
     * Check if a word has been selected by the user
     */
    fun hasUserSelected(word: String): Boolean {
        return selectionCounts.containsKey(word.lowercase().trim())
    }

    /**
     * Get relative frequency of a word (0.0 to 1.0)
     */
    fun getRelativeFrequency(word: String): Float {
        if (totalSelections == 0) return 0f
        val count = getSelectionCount(word)
        return count.toFloat() / totalSelections
    }
}
