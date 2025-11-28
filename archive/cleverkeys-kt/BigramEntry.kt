package tribixbite.keyboard2.contextaware

/**
 * Represents a bigram (word pair) with frequency and probability data.
 *
 * Used for context-aware prediction: given a previous word, predict likely next words.
 *
 * Example: "I" → "am" has high probability in English
 *          "the" → "cat" is more likely than "the" → "airplane"
 *
 * @property word1 The first word in the pair (context/previous word)
 * @property word2 The second word in the pair (predicted word)
 * @property frequency How many times this pair has been observed
 * @property probability P(word2 | word1) = frequency(word1,word2) / frequency(word1)
 */
data class BigramEntry(
    val word1: String,
    val word2: String,
    val frequency: Int,
    val probability: Float
) {
    companion object {
        /**
         * Calculate probability for a bigram given its frequency and the first word's total frequency.
         *
         * P(word2 | word1) = count(word1, word2) / count(word1)
         *
         * @param bigramFrequency Number of times (word1, word2) appears together
         * @param word1TotalFrequency Total number of times word1 appears (with any word2)
         * @return Probability between 0.0 and 1.0
         */
        fun calculateProbability(bigramFrequency: Int, word1TotalFrequency: Int): Float {
            if (word1TotalFrequency == 0) return 0f
            return bigramFrequency.toFloat() / word1TotalFrequency.toFloat()
        }

        /**
         * Normalize a word for bigram matching.
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
     * Check if this bigram matches a given word pair.
     * Uses normalized (lowercase) comparison.
     *
     * @param prevWord Previous word to match
     * @param nextWord Next word to match
     * @return True if both words match (case-insensitive)
     */
    fun matches(prevWord: String, nextWord: String): Boolean {
        return normalizeWord(word1) == normalizeWord(prevWord) &&
               normalizeWord(word2) == normalizeWord(nextWord)
    }

    /**
     * Create a human-readable string representation.
     * Format: "word1 → word2 (freq: X, prob: Y%)"
     */
    override fun toString(): String {
        return "$word1 → $word2 (freq: $frequency, prob: ${(probability * 100).toInt()}%)"
    }
}
