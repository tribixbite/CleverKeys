package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages user adaptation by tracking word selection history and adjusting
 * word frequencies based on user preferences.
 *
 * Thin Android wrapper (M7, review 2026-08-06) around the pure-JVM
 * [SelectionHistory] core, which owns the counting, multiplier math, bounded
 * pruning, and concurrency contracts (unit-tested in `SelectionHistoryTest`).
 * This class owns only the SharedPreferences persistence and the periodic
 * 30-day reset.
 *
 * Retention contract: [SelectionHistory.snapshotForPersist] reports the words
 * pruned since the last save; [saveSelectionHistory] DELETES their
 * `word_selections_<word>` keys so pruned selections no longer persist forever
 * and resurrect on the next load.
 *
 * Privacy: writes are gated by `LearningGate.canLearnAdaptation` at the call
 * site (`SuggestionHandler.onSuggestionSelected`); reads are gated by
 * `LearningGate.canUseAdaptation` in `WordPredictor` AND belt-and-braces via
 * [setEnabled], which `WordPredictor.setConfig` keeps synced to the master
 * `on_device_learning_enabled` gate (H3).
 */
class UserAdaptationManager private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val history = SelectionHistory(
        maxTrackedWords = MAX_TRACKED_WORDS,
        minSelectionsForAdaptation = MIN_SELECTIONS_FOR_ADAPTATION,
        adaptationStrength = ADAPTATION_STRENGTH
    )

    init {
        loadSelectionHistory()
        checkForPeriodicReset()
    }

    /**
     * Record that a word was selected by the user. No-op while disabled.
     * Persists every [SelectionHistory.SAVE_EVERY_N_SELECTIONS] selections and
     * immediately after a capacity prune (so pruned keys are deleted promptly).
     */
    fun recordSelection(word: String?) {
        if (history.recordSelection(word)) {
            saveSelectionHistory()
        }
    }

    /**
     * Get the adaptation multiplier for a word based on selection history.
     * Returns 1.0 for no adaptation (including while disabled — H3 read gate),
     * >1.0 for frequently selected words.
     */
    fun getAdaptationMultiplier(word: String?): Float = history.multiplierFor(word)

    /** Get selection count for a specific word. */
    fun getSelectionCount(word: String?): Int = history.selectionCount(word)

    /** Get total number of selections recorded. */
    fun getTotalSelections(): Int = history.totalSelections()

    /** Get number of unique words being tracked. */
    fun getTrackedWordCount(): Int = history.trackedWordCount()

    /**
     * Enable or disable user adaptation. Disabled ⇒ recording AND the
     * multiplier read are inert (synced from the master learning gate by
     * `WordPredictor.setConfig`).
     */
    fun setEnabled(enabled: Boolean) {
        if (history.enabled != enabled) {
            history.enabled = enabled
            Log.d(TAG, "User adaptation ${if (enabled) "enabled" else "disabled"}")
        }
    }

    /** Check if user adaptation is enabled. */
    fun isEnabled(): Boolean = history.enabled

    /** Reset all adaptation data (in RAM and persisted). */
    fun resetAdaptation() {
        history.reset()

        prefs.edit().apply {
            clear()
            putLong(KEY_LAST_RESET, System.currentTimeMillis())
            apply()
        }

        Log.d(TAG, "User adaptation data reset")
    }

    /** Get adaptation statistics for debugging. */
    fun getAdaptationStats(): String {
        if (!history.enabled) {
            return "User adaptation disabled"
        }

        val total = history.totalSelections()
        val stats = StringBuilder()
        stats.append("User Adaptation Stats:\n")
        stats.append("- Total selections: $total\n")
        stats.append("- Unique words tracked: ${history.trackedWordCount()}\n")
        stats.append("- Adaptation active: ${if (total >= MIN_SELECTIONS_FOR_ADAPTATION) "Yes" else "No"}\n")

        if (total >= MIN_SELECTIONS_FOR_ADAPTATION) {
            stats.append("\nTop 10 most selected words:\n")
            history.topWords(10).forEach { (word, count) ->
                val multiplier = history.multiplierFor(word)
                stats.append("- $word: $count selections (${"%.2f".format(multiplier)}x boost)\n")
            }
        }

        return stats.toString()
    }

    /** Load selection history from persistent storage. */
    private fun loadSelectionHistory() {
        val total = prefs.getInt(KEY_TOTAL_SELECTIONS, 0)

        val counts = mutableMapOf<String, Int>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_WORD_SELECTIONS) && value is Int) {
                counts[key.substring(KEY_WORD_SELECTIONS.length)] = value
            }
        }
        history.load(counts, total)

        Log.d(TAG, "Loaded adaptation data: $total total selections, ${counts.size} unique words")
    }

    /**
     * Save selection history to persistent storage: prune-removals are DELETED
     * first (bounded retention, M7), then current counts are written.
     */
    private fun saveSelectionHistory() {
        val snapshot = history.snapshotForPersist()
        prefs.edit().apply {
            putInt(KEY_TOTAL_SELECTIONS, snapshot.total)

            // Remove pruned words' keys BEFORE the writes so a word re-selected
            // mid-snapshot (present in both sets) ends up written, not deleted.
            for (word in snapshot.removals) {
                remove(KEY_WORD_SELECTIONS + word)
            }
            for ((word, count) in snapshot.counts) {
                putInt(KEY_WORD_SELECTIONS + word, count)
            }

            apply()
        }

        Log.d(TAG, "Saved adaptation data (${snapshot.counts.size} words, ${snapshot.removals.size} pruned keys removed)")
    }

    /** Check if it's time for a periodic reset to prevent stale data. */
    private fun checkForPeriodicReset() {
        val lastReset = prefs.getLong(KEY_LAST_RESET, System.currentTimeMillis())
        val timeSinceReset = System.currentTimeMillis() - lastReset

        if (timeSinceReset > RESET_PERIOD_MS) {
            Log.d(TAG, "Performing periodic reset of adaptation data (30 days elapsed)")
            resetAdaptation()
        }
    }

    /** Cleanup method to be called when the system is destroyed. */
    fun cleanup() {
        saveSelectionHistory()
    }

    companion object {
        private const val TAG = "UserAdaptationManager"
        private const val PREFS_NAME = "user_adaptation"
        private const val KEY_WORD_SELECTIONS = "word_selections_"
        private const val KEY_TOTAL_SELECTIONS = "total_selections"
        private const val KEY_LAST_RESET = "last_reset"

        // Configuration constants (thresholds live in SelectionHistory defaults)
        private const val MIN_SELECTIONS_FOR_ADAPTATION =
            SelectionHistory.DEFAULT_MIN_SELECTIONS_FOR_ADAPTATION
        private const val MAX_TRACKED_WORDS = SelectionHistory.DEFAULT_MAX_TRACKED_WORDS
        private const val ADAPTATION_STRENGTH = SelectionHistory.DEFAULT_ADAPTATION_STRENGTH
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
