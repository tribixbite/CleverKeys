package tribixbite.cleverkeys.personalization

import android.content.Context
import android.util.Log

/**
 * Personalization engine for adaptive word prediction.
 *
 * Coordinates user vocabulary tracking and personalized scoring to improve prediction
 * accuracy based on individual typing patterns. Integrates with WordPredictor to boost
 * predictions for frequently used words.
 *
 * Features:
 * - Automatic learning from user's typing behavior
 * - Frequency-based and recency-based scoring
 * - Privacy-preserving (all data stored locally)
 * - Configurable learning aggression levels
 * - Automatic cleanup of stale data
 *
 * Example:
 * ```kotlin
 * val engine = PersonalizationEngine(context)
 * engine.setEnabled(true)
 * engine.setLearningAggression(LearningAggression.BALANCED)
 *
 * // During typing:
 * engine.recordWordTyped("kotlin")
 *
 * // During prediction:
 * val boost = engine.getPersonalizationBoost("kotlin") // 0.0-4.0
 * ```
 */
class PersonalizationEngine(private val context: Context) {
    companion object {
        private const val TAG = "PersonalizationEngine"

        // SharedPreferences keys
        private const val PREFS_NAME = "personalization_settings"
        private const val PREF_ENABLED = "personalization_enabled"
        private const val PREF_AGGRESSION = "learning_aggression"

        // Boost multipliers based on aggression level
        private const val CONSERVATIVE_MULTIPLIER = 0.5f
        private const val BALANCED_MULTIPLIER = 1.0f
        private const val AGGRESSIVE_MULTIPLIER = 1.5f
    }

    /**
     * Learning aggression levels determine how strongly personalization affects predictions.
     *
     * - CONSERVATIVE: 50% of calculated boost (subtle personalization)
     * - BALANCED: 100% of calculated boost (recommended default)
     * - AGGRESSIVE: 150% of calculated boost (strong personalization)
     */
    enum class LearningAggression(val multiplier: Float) {
        CONSERVATIVE(CONSERVATIVE_MULTIPLIER),
        BALANCED(BALANCED_MULTIPLIER),
        AGGRESSIVE(AGGRESSIVE_MULTIPLIER)
    }

    // User vocabulary tracker
    private val vocabulary: UserVocabulary = UserVocabulary(context)

    // Settings
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var enabled: Boolean = prefs.getBoolean(PREF_ENABLED, true)
    private var aggression: LearningAggression = LearningAggression.valueOf(
        prefs.getString(PREF_AGGRESSION, LearningAggression.BALANCED.name)
            ?: LearningAggression.BALANCED.name
    )

    init {
        Log.d(TAG, "PersonalizationEngine initialized (enabled=$enabled, aggression=$aggression)")
    }

    /**
     * Enable or disable personalization.
     *
     * When disabled, no learning occurs and getPersonalizationBoost() returns 0.0.
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply()
        Log.d(TAG, "Personalization enabled=$enabled")
    }

    /**
     * Check if personalization is enabled.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Set learning aggression level.
     *
     * Controls how strongly personalization affects prediction scores.
     */
    fun setLearningAggression(aggression: LearningAggression) {
        this.aggression = aggression
        prefs.edit().putString(PREF_AGGRESSION, aggression.name).apply()
        Log.d(TAG, "Learning aggression set to $aggression")
    }

    /**
     * Get current learning aggression level.
     */
    fun getLearningAggression(): LearningAggression = aggression

    /**
     * Record that the user typed a word.
     *
     * If personalization is enabled, adds/updates the word in user vocabulary.
     * No-op if personalization is disabled.
     */
    fun recordWordTyped(word: String, timestamp: Long = System.currentTimeMillis()) {
        if (!enabled) {
            return // Learning disabled
        }

        if (word.isEmpty() || word.length < 2) {
            return // Skip very short words
        }

        vocabulary.recordWordUsage(word, timestamp)
    }

    /**
     * Record multiple words typed in sequence.
     *
     * Convenience method for batch recording.
     */
    fun recordWordsTyped(words: List<String>, timestamp: Long = System.currentTimeMillis()) {
        if (!enabled) {
            return
        }

        words.forEach { word ->
            recordWordTyped(word, timestamp)
        }
    }

    /**
     * Get personalization boost for a word.
     *
     * Returns:
     * - 0.0 if personalization is disabled
     * - 0.0 if word is not in user vocabulary
     * - 0.0-6.0 based on usage frequency, recency, and aggression level
     *
     * Boost calculation:
     * 1. Base boost = vocabulary.getPersonalizationBoost(word) // 0.0-4.0
     * 2. Adjusted boost = base boost * aggression.multiplier
     * 3. Return adjusted boost
     */
    fun getPersonalizationBoost(
        word: String,
        currentTime: Long = System.currentTimeMillis()
    ): Float {
        if (!enabled) {
            return 0.0f
        }

        val baseBoost = vocabulary.getPersonalizationBoost(word, currentTime)
        return baseBoost * aggression.multiplier
    }

    /**
     * Check if a word is in the user's vocabulary.
     */
    fun hasWord(word: String): Boolean {
        return vocabulary.hasWord(word)
    }

    /**
     * Get usage statistics for a word.
     */
    fun getWordUsage(word: String): UserWordUsage? {
        return vocabulary.getWordUsage(word)
    }

    /**
     * Get top N most frequently used words.
     */
    fun getTopWords(limit: Int = 100): List<UserWordUsage> {
        return vocabulary.getTopWords(limit)
    }

    /**
     * Get vocabulary size.
     */
    fun getVocabularySize(): Int {
        return vocabulary.size()
    }

    /**
     * Clean up stale words from vocabulary.
     *
     * Returns number of words removed.
     */
    fun cleanupStaleWords(): Int {
        return vocabulary.cleanupStaleWords()
    }

    /**
     * Clear all personalization data.
     *
     * Useful for user privacy reset or testing.
     */
    fun clearAllData() {
        vocabulary.clearAll()
        Log.d(TAG, "All personalization data cleared")
    }

    /**
     * Export personalization data to JSON.
     */
    fun exportData(): String {
        return vocabulary.exportToJson()
    }

    /**
     * Import personalization data from JSON.
     *
     * Returns number of words imported.
     */
    fun importData(json: String): Int {
        return vocabulary.importFromJson(json)
    }

    /**
     * Get statistics about personalization.
     */
    fun getStats(): PersonalizationStats {
        val vocabStats = vocabulary.getStats()

        return PersonalizationStats(
            enabled = enabled,
            aggression = aggression,
            vocabularySize = vocabStats.totalWords,
            averageUsageCount = vocabStats.averageUsageCount,
            mostUsedWord = vocabStats.mostUsedWord,
            recentlyUsedCount = vocabStats.recentlyUsedCount
        )
    }

    /**
     * Get detailed explanation of boost calculation for a word (for debugging).
     */
    fun explainBoost(word: String, currentTime: Long = System.currentTimeMillis()): BoostExplanation {
        if (!enabled) {
            return BoostExplanation(
                word = word,
                enabled = false,
                inVocabulary = false,
                usageCount = 0,
                frequencyScore = 0.0f,
                recencyScore = 0.0f,
                baseBoost = 0.0f,
                aggressionMultiplier = 0.0f,
                finalBoost = 0.0f,
                explanation = "Personalization is disabled"
            )
        }

        val usage = vocabulary.getWordUsage(word)

        if (usage == null) {
            return BoostExplanation(
                word = word,
                enabled = true,
                inVocabulary = false,
                usageCount = 0,
                frequencyScore = 0.0f,
                recencyScore = 0.0f,
                baseBoost = 0.0f,
                aggressionMultiplier = aggression.multiplier,
                finalBoost = 0.0f,
                explanation = "Word not in user vocabulary"
            )
        }

        val frequencyScore = usage.getFrequencyScore()
        val recencyScore = usage.getRecencyScore(currentTime)
        val baseBoost = usage.getPersonalizationBoost(currentTime)
        val finalBoost = baseBoost * aggression.multiplier

        val explanation = buildString {
            append("Used ${usage.usageCount} times\n")
            append("Frequency score: ${"%.2f".format(frequencyScore)}\n")
            append("Recency score: ${"%.2f".format(recencyScore)}\n")
            append("Base boost: ${"%.2f".format(baseBoost)}\n")
            append("Aggression: $aggression (${aggression.multiplier}x)\n")
            append("Final boost: ${"%.2f".format(finalBoost)}")
        }

        return BoostExplanation(
            word = word,
            enabled = true,
            inVocabulary = true,
            usageCount = usage.usageCount,
            frequencyScore = frequencyScore,
            recencyScore = recencyScore,
            baseBoost = baseBoost,
            aggressionMultiplier = aggression.multiplier,
            finalBoost = finalBoost,
            explanation = explanation
        )
    }
}

/**
 * Statistics about personalization engine.
 */
data class PersonalizationStats(
    val enabled: Boolean,
    val aggression: PersonalizationEngine.LearningAggression,
    val vocabularySize: Int,
    val averageUsageCount: Double,
    val mostUsedWord: UserWordUsage?,
    val recentlyUsedCount: Int
) {
    override fun toString(): String {
        return buildString {
            append("PersonalizationStats(\n")
            append("  enabled=$enabled\n")
            append("  aggression=$aggression\n")
            append("  vocabularySize=$vocabularySize\n")
            append("  averageUsageCount=${"%.1f".format(averageUsageCount)}\n")
            append("  mostUsedWord=${mostUsedWord?.word} (${mostUsedWord?.usageCount} uses)\n")
            append("  recentlyUsedCount=$recentlyUsedCount\n")
            append(")")
        }
    }
}

/**
 * Detailed explanation of boost calculation for debugging.
 */
data class BoostExplanation(
    val word: String,
    val enabled: Boolean,
    val inVocabulary: Boolean,
    val usageCount: Int,
    val frequencyScore: Float,
    val recencyScore: Float,
    val baseBoost: Float,
    val aggressionMultiplier: Float,
    val finalBoost: Float,
    val explanation: String
) {
    override fun toString(): String {
        return "BoostExplanation for '$word':\n$explanation"
    }
}
