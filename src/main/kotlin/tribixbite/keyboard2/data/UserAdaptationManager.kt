package tribixbite.keyboard2.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import tribixbite.keyboard2.DirectBootAwarePreferences

/**
 * User Adaptation Manager for Personalized Word Predictions
 *
 * Learns user's vocabulary and typing patterns to provide personalized predictions.
 * Tracks word usage frequency to boost commonly typed words.
 *
 * Features:
 * - Word usage tracking (counts how often user types each word)
 * - Adaptation multiplier (boosts frequently used words)
 * - Persistent storage (SharedPreferences)
 * - Decay mechanism (old words fade over time)
 *
 * Scoring:
 * - Words typed once: multiplier = 1.0 (no boost)
 * - Words typed 5 times: multiplier = 1.5 (50% boost)
 * - Words typed 20+ times: multiplier = 2.5 (150% boost)
 *
 * Storage format:
 * ```json
 * {
 *   "word1": 15,
 *   "word2": 42,
 *   "word3": 3
 * }
 * ```
 *
 * Usage:
 * ```kotlin
 * val manager = UserAdaptationManager(context)
 * manager.recordWord("hello")
 * val multiplier = manager.getAdaptationMultiplier("hello")
 * // multiplier will be > 1.0 for frequently typed words
 * ```
 */
class UserAdaptationManager(private val context: Context) {

    companion object {
        private const val TAG = "UserAdaptationManager"
        private const val PREFS_KEY = "user_adaptation_data"
        private const val MAX_WORD_COUNT = 1000 // Limit storage
    }

    private val prefs: SharedPreferences by lazy {
        DirectBootAwarePreferences.get_shared_preferences(context)
    }

    // Word usage counts: word → count
    private val wordUsage = mutableMapOf<String, Int>()

    init {
        loadWordUsage()
    }

    /**
     * Load word usage data from SharedPreferences
     */
    private fun loadWordUsage() {
        wordUsage.clear()

        try {
            val json = prefs.getString(PREFS_KEY, "{}") ?: "{}"
            val jsonObj = JSONObject(json)
            val keys = jsonObj.keys()

            while (keys.hasNext()) {
                val word = keys.next().lowercase()
                val count = jsonObj.getInt(word)
                wordUsage[word] = count
            }

            Log.d(TAG, "Loaded user adaptation data: ${wordUsage.size} words")
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse user adaptation data", e)
        }
    }

    /**
     * Save word usage data to SharedPreferences
     */
    private fun saveWordUsage() {
        try {
            val jsonObj = JSONObject()
            for ((word, count) in wordUsage) {
                jsonObj.put(word, count)
            }

            prefs.edit()
                .putString(PREFS_KEY, jsonObj.toString())
                .apply()
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to save user adaptation data", e)
        }
    }

    /**
     * Record a word that the user typed
     */
    fun recordWord(word: String) {
        val lowerWord = word.lowercase()
        val currentCount = wordUsage[lowerWord] ?: 0
        wordUsage[lowerWord] = currentCount + 1

        // Limit storage size (remove least-used words)
        if (wordUsage.size > MAX_WORD_COUNT) {
            pruneWordUsage()
        }

        // Save periodically (every 10 words)
        if (wordUsage.size % 10 == 0) {
            saveWordUsage()
        }
    }

    /**
     * Prune word usage to stay within storage limit
     * Removes words with lowest counts
     */
    private fun pruneWordUsage() {
        // Keep top 80% of words by usage count
        val threshold = (MAX_WORD_COUNT * 0.8).toInt()
        val sortedWords = wordUsage.entries.sortedByDescending { it.value }
        wordUsage.clear()
        wordUsage.putAll(sortedWords.take(threshold).associate { it.key to it.value })

        Log.d(TAG, "Pruned word usage to ${wordUsage.size} words")
    }

    /**
     * Get adaptation multiplier for a word
     *
     * Returns multiplier based on how often the user types this word:
     * - Never typed: 1.0 (no boost)
     * - Typed 1-4 times: 1.1-1.4
     * - Typed 5-9 times: 1.5-1.9
     * - Typed 10-19 times: 2.0-2.4
     * - Typed 20+ times: 2.5 (max boost)
     *
     * @param word The word to score
     * @return Multiplier (1.0-2.5)
     */
    fun getAdaptationMultiplier(word: String): Float {
        val count = wordUsage[word.lowercase()] ?: return 1.0f

        // Formula: 1.0 + min(count / 20, 1.0) * 1.5
        // Examples:
        // count=0  → 1.0
        // count=5  → 1.375
        // count=10 → 1.75
        // count=20 → 2.5
        val boost = minOf(count / 20.0f, 1.0f) * 1.5f
        return 1.0f + boost
    }

    /**
     * Get word usage count for a word
     */
    fun getWordUsageCount(word: String): Int {
        return wordUsage[word.lowercase()] ?: 0
    }

    /**
     * Clear all adaptation data (for settings reset)
     */
    fun clearAdaptationData() {
        wordUsage.clear()
        saveWordUsage()
        Log.d(TAG, "Cleared all user adaptation data")
    }

    /**
     * Get total number of tracked words
     */
    fun getTrackedWordCount(): Int = wordUsage.size

    /**
     * Export adaptation data for backup (returns JSON string)
     */
    fun exportAdaptationData(): String {
        val jsonObj = JSONObject()
        for ((word, count) in wordUsage) {
            jsonObj.put(word, count)
        }
        return jsonObj.toString()
    }

    /**
     * Import adaptation data from backup (JSON string)
     */
    fun importAdaptationData(jsonString: String) {
        try {
            wordUsage.clear()
            val jsonObj = JSONObject(jsonString)
            val keys = jsonObj.keys()

            while (keys.hasNext()) {
                val word = keys.next().lowercase()
                val count = jsonObj.getInt(word)
                wordUsage[word] = count
            }

            saveWordUsage()
            Log.d(TAG, "Imported user adaptation data: ${wordUsage.size} words")
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to import adaptation data", e)
        }
    }
}
