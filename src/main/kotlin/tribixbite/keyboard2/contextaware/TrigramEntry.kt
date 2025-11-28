package tribixbite.keyboard2.contextaware

/**
 * Represents a trigram (three-word sequence) with frequency and probability data.
 *
 * Used for enhanced context-aware prediction using two words of context.
 * Trigrams provide stronger context than bigrams for better prediction accuracy.
 *
 * Example: "I want to" → "go" is more likely than just "to" → "go"
 *          "New York" → "City" is very likely
 *
 * @property word1 The first word in the sequence (two words back)
 * @property word2 The second word in the sequence (one word back)
 * @property word3 The third word in the sequence (predicted word)
 * @property frequency How many times this sequence has been observed
 * @property probability P(word3 | word1, word2) = frequency(word1,word2,word3) / frequency(word1,word2)
 */
data class TrigramEntry(
    val word1: String,
    val word2: String,
    val word3: String,
    val frequency: Int,
    val probability: Float
) {
    companion object {
        /**
         * Calculate probability for a trigram given its frequency and the prefix's total frequency.
         *
         * P(word3 | word1, word2) = count(word1, word2, word3) / count(word1, word2)
         *
         * @param trigramFrequency Number of times (word1, word2, word3) appears together
         * @param prefixTotalFrequency Total number of times (word1, word2) appears (with any word3)
         * @return Probability between 0.0 and 1.0
         */
        fun calculateProbability(trigramFrequency: Int, prefixTotalFrequency: Int): Float {
            if (prefixTotalFrequency == 0) return 0f
            return trigramFrequency.toFloat() / prefixTotalFrequency.toFloat()
        }

        /**
         * Normalize a word for trigram matching.
         * Converts to lowercase and trims whitespace for consistent lookup.
         *
         * @param word The word to normalize
         * @return Normalized word
         */
        fun normalizeWord(word: String): String {
            return word.lowercase().trim()
        }
    }

    /**
     * Check if this trigram matches a given word sequence.
     * Uses normalized (lowercase) comparison.
     *
     * @param prev2Word Two words back
     * @param prev1Word One word back (previous word)
     * @param nextWord Predicted word
     * @return True if all three words match (case-insensitive)
     */
    fun matches(prev2Word: String, prev1Word: String, nextWord: String): Boolean {
        return normalizeWord(word1) == normalizeWord(prev2Word) &&
               normalizeWord(word2) == normalizeWord(prev1Word) &&
               normalizeWord(word3) == normalizeWord(nextWord)
    }

    /**
     * Create a human-readable string representation.
     * Format: "word1 word2 → word3 (freq: X, prob: Y%)"
     */
    override fun toString(): String {
        return "$word1 $word2 → $word3 (freq: $frequency, prob: ${(probability * 100).toInt()}%)"
    }
}
