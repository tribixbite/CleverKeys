package tribixbite.cleverkeys.personalization

/**
 * Data class representing usage statistics for a single word in the user's personal vocabulary.
 *
 * Tracks how frequently and recently a word has been used, enabling personalized prediction
 * boosts based on actual user behavior.
 *
 * Example:
 * ```kotlin
 * val usage = UserWordUsage(
 *     word = "kotlin",
 *     usageCount = 150,
 *     lastUsed = System.currentTimeMillis(),
 *     firstUsed = 1700000000000L
 * )
 * val recencyScore = usage.getRecencyScore() // Higher for recently used words
 * ```
 */
data class UserWordUsage(
    /** The word being tracked (normalized to lowercase) */
    val word: String,

    /** Total number of times this word has been typed */
    val usageCount: Int,

    /** Timestamp (ms) when word was last used */
    val lastUsed: Long,

    /** Timestamp (ms) when word was first recorded */
    val firstUsed: Long = lastUsed
) {
    companion object {
        // Recency decay: words used in last 7 days get full score, 30 days get 50%, 90 days get 10%
        private const val RECENCY_FULL_DAYS = 7
        private const val RECENCY_HALF_DAYS = 30
        private const val RECENCY_MIN_DAYS = 90

        private const val MILLIS_PER_DAY = 86400000L // 24 * 60 * 60 * 1000

        /**
         * Normalize a word for consistent tracking (lowercase, trim whitespace).
         */
        fun normalizeWord(word: String): String {
            return word.lowercase().trim()
        }

        /**
         * Calculate frequency score based on usage count.
         * Uses logarithmic scaling to prevent extreme boosts.
         *
         * Score range: 1.0 (1 use) to ~3.0 (1000+ uses)
         */
        fun calculateFrequencyScore(usageCount: Int): Float {
            if (usageCount <= 0) return 0f

            // log10(x+1) + 1.0 gives smooth curve:
            // 1 use → 1.0, 10 uses → 2.0, 100 uses → 3.0, 1000 uses → 4.0
            return Math.log10(usageCount.toDouble() + 1.0).toFloat() + 1.0f
        }
    }

    /**
     * Calculate recency score based on how recently the word was used.
     *
     * Returns:
     * - 1.0 if used within last 7 days
     * - 0.5-1.0 if used within last 30 days (linear decay)
     * - 0.1-0.5 if used within last 90 days (linear decay)
     * - 0.0 if older than 90 days
     */
    fun getRecencyScore(currentTime: Long = System.currentTimeMillis()): Float {
        val daysSinceLastUse = (currentTime - lastUsed) / MILLIS_PER_DAY

        return when {
            daysSinceLastUse < RECENCY_FULL_DAYS -> 1.0f
            daysSinceLastUse < RECENCY_HALF_DAYS -> {
                // Linear decay from 1.0 to 0.5 over 23 days
                val ratio = (daysSinceLastUse - RECENCY_FULL_DAYS).toFloat() /
                           (RECENCY_HALF_DAYS - RECENCY_FULL_DAYS).toFloat()
                1.0f - (ratio * 0.5f)
            }
            daysSinceLastUse < RECENCY_MIN_DAYS -> {
                // Linear decay from 0.5 to 0.1 over 60 days
                val ratio = (daysSinceLastUse - RECENCY_HALF_DAYS).toFloat() /
                           (RECENCY_MIN_DAYS - RECENCY_HALF_DAYS).toFloat()
                0.5f - (ratio * 0.4f)
            }
            else -> 0.0f // Too old, no boost
        }
    }

    /**
     * Calculate frequency score for this word's usage count.
     */
    fun getFrequencyScore(): Float {
        return calculateFrequencyScore(usageCount)
    }

    /**
     * Calculate combined personalization boost for this word.
     *
     * Combines frequency and recency scores:
     * - Frequency: 1.0-4.0 (logarithmic scale)
     * - Recency: 0.0-1.0 (time-based decay)
     * - Combined: frequency * recency (0.0-4.0 range)
     *
     * Examples:
     * - Frequently used recently: 3.0 * 1.0 = 3.0
     * - Frequently used 60 days ago: 3.0 * 0.3 = 0.9
     * - Rarely used recently: 1.5 * 1.0 = 1.5
     */
    fun getPersonalizationBoost(currentTime: Long = System.currentTimeMillis()): Float {
        val frequency = getFrequencyScore()
        val recency = getRecencyScore(currentTime)

        // Multiplicative combination: both matter
        return frequency * recency
    }

    /**
     * Check if this word usage is stale and should be cleaned up.
     *
     * A word is stale if:
     * - Last used more than 90 days ago, OR
     * - Only used once and more than 30 days old
     */
    fun isStale(currentTime: Long = System.currentTimeMillis()): Boolean {
        val daysSinceLastUse = (currentTime - lastUsed) / MILLIS_PER_DAY

        return when {
            daysSinceLastUse > RECENCY_MIN_DAYS -> true // Very old
            usageCount == 1 && daysSinceLastUse > RECENCY_HALF_DAYS -> true // One-time typo
            else -> false
        }
    }

    /**
     * Create updated usage entry after the word is typed again.
     */
    fun recordNewUsage(timestamp: Long = System.currentTimeMillis()): UserWordUsage {
        return copy(
            usageCount = usageCount + 1,
            lastUsed = timestamp
        )
    }

    override fun toString(): String {
        return "UserWordUsage(word='$word', count=$usageCount, lastUsed=${lastUsed}, " +
               "freqScore=${getFrequencyScore()}, recencyScore=${getRecencyScore()})"
    }
}
