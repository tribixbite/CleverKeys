package tribixbite.cleverkeys.contextaware

import android.content.Context

/**
 * Context-aware N-gram model for prediction enhancement.
 *
 * A thin, language-aware view over the process-wide [BigramStore] singleton
 * (2026-08-06 persistence fix). Each `WordPredictor` owns one ContextModel and
 * keeps [language] in sync with its active language; all reads/writes are
 * isolated to that language's learned data.
 *
 * Usage Example:
 * ```kotlin
 * val contextModel = ContextModel(context, "en")
 * contextModel.recordSequence(listOf("I", "want", "to"))  // Learn from typing
 *
 * // Later, when predicting after "want to"
 * val boost = contextModel.getContextBoost(
 *     candidateWord = "go",
 *     previousWords = listOf("I", "want", "to")
 * )
 * // boost might be 2.5x for likely phrase "I want to go"
 * ```
 *
 * Architecture:
 * - BigramStore: P(word2 | word1) — singleton, language-keyed, debounced persistence
 * - TrigramStore: P(word3 | word1, word2) [future implementation]
 * - Hybrid scoring: Prefer trigrams when available, else use bigrams
 */
class ContextModel internal constructor(
    private val bigramStore: BigramStore,
    language: String
) {
    companion object {
        // Boost multipliers for context probabilities
        private const val MAX_BOOST = 5.0f  // Maximum 5x boost for very likely words
        private const val MIN_BOOST = 1.0f  // No boost (neutral)
        private const val BOOST_EXPONENT = 2.0f  // Non-linear boost: boost = (1 + prob)^2

        // Context window sizes
        private const val BIGRAM_WINDOW = 1  // Use 1 previous word
        private const val TRIGRAM_WINDOW = 2  // Use 2 previous words (future)

        // Minimum probability thresholds
        private const val MIN_BIGRAM_PROB = 0.01f  // 1% minimum
        private const val MIN_TRIGRAM_PROB = 0.001f  // 0.1% minimum (future)
    }

    /**
     * Production constructor: language-keyed view over the singleton store.
     */
    constructor(context: Context, language: String = "en") :
        this(BigramStore.getInstance(context), language)

    /**
     * Active language for this model's view of the store. WordPredictor updates
     * this on language switch / auto-detection so learning and lookups stay
     * language-isolated.
     */
    @Volatile
    var language: String = BigramStore.normalizeLanguage(language)
        set(value) {
            field = BigramStore.normalizeLanguage(value)
        }

    // Trigram model (future implementation)
    // private val trigramStore: TrigramStore = TrigramStore(context)

    /**
     * Record a word sequence from user typing.
     * Extracts and records all bigrams (and trigrams in future) into the active
     * language's store. Persistence is handled by the store's debounced
     * write-back — call [save] at lifecycle boundaries to checkpoint explicitly.
     *
     * Example: ["I", "want", "to", "go"] records:
     * - Bigrams: (I, want), (want, to), (to, go)
     * - Trigrams: (I, want, to), (want, to, go)
     *
     * @param words List of words in order (should be normalized/lowercase)
     */
    fun recordSequence(words: List<String>) {
        if (words.size < 2) return  // Need at least 2 words for bigrams

        // Record bigrams
        for (i in 0 until words.size - 1) {
            bigramStore.recordBigram(language, words[i], words[i + 1])
        }

        // Future: Record trigrams
        // if (words.size >= 3) {
        //     for (i in 0 until words.size - 2) {
        //         trigramStore.recordTrigram(words[i], words[i + 1], words[i + 2])
        //     }
        // }
    }

    /**
     * Get context-based boost for a candidate word.
     *
     * Calculates a multiplier (≥1.0) based on how likely the candidate is given previous words.
     * Higher boost for more likely words in context.
     *
     * Algorithm:
     * 1. Try trigram if we have 2+ previous words (future)
     * 2. Fall back to bigram if we have 1+ previous word
     * 3. Return 1.0 (no boost) if no context available
     *
     * Boost formula: boost = (1 + probability)^BOOST_EXPONENT
     * - P=0.0 → boost=1.0 (no change)
     * - P=0.1 → boost=1.21
     * - P=0.5 → boost=2.25
     * - P=0.9 → boost=3.61
     * - P=1.0 → boost=4.0 (max realistic)
     *
     * @param candidateWord The word being predicted
     * @param previousWords List of previous words (most recent last)
     * @return Boost multiplier (1.0 to MAX_BOOST)
     */
    fun getContextBoost(candidateWord: String, previousWords: List<String>): Float {
        if (previousWords.isEmpty() || candidateWord.isEmpty()) {
            return MIN_BOOST
        }

        // Future: Try trigram first if we have 2+ previous words

        // Try bigram with most recent previous word
        val prevWord = previousWords.last()
        val bigramProb = bigramStore.getProbability(language, prevWord, candidateWord)

        if (bigramProb >= MIN_BIGRAM_PROB) {
            return calculateBoost(bigramProb)
        }

        return MIN_BOOST  // No context match
    }

    /**
     * Get top N predictions given context words.
     *
     * Returns candidate words ranked by context probability from the active
     * language's learned data. Used by next-word prediction (§4) and for showing
     * contextually relevant suggestions.
     *
     * @param previousWords List of previous words (most recent last)
     * @param maxResults Maximum number of predictions
     * @return List of candidate words with their context boost values
     */
    fun getTopPredictions(
        previousWords: List<String>,
        maxResults: Int = 10
    ): List<Pair<String, Float>> {
        if (previousWords.isEmpty()) return emptyList()

        // Future: Try trigram predictions first

        // Use bigram predictions
        val prevWord = previousWords.last()
        val bigramPredictions = bigramStore.getPredictions(language, prevWord, maxResults)

        return bigramPredictions.map { entry ->
            entry.word2 to calculateBoost(entry.probability)
        }
    }

    /**
     * Get ranked next-word candidates with raw learned statistics
     * (frequency + conditional probability) for filtering/floors.
     */
    fun getNextWordCandidates(
        previousWords: List<String>,
        maxResults: Int = 10
    ): List<BigramEntry> {
        if (previousWords.isEmpty()) return emptyList()
        return bigramStore.getPredictions(language, previousWords.last(), maxResults)
    }

    /**
     * Check if context model has data for a given previous word.
     *
     * @param previousWord The context word to check
     * @return True if we have predictions for this word
     */
    fun hasContextFor(previousWord: String): Boolean {
        return bigramStore.getAllBigrams(language, previousWord).isNotEmpty()
    }

    /**
     * Get the raw probability (not boost) for a word pair.
     *
     * @param previousWord Previous word
     * @param candidateWord Predicted word
     * @return Probability (0.0 to 1.0)
     */
    fun getProbability(previousWord: String, candidateWord: String): Float {
        return bigramStore.getProbability(language, previousWord, candidateWord)
    }

    /**
     * Clear this language's context data (for testing or user reset).
     */
    fun clear() {
        bigramStore.clear(language)
        // Future: trigramStore.clear(language)
    }

    /**
     * Checkpoint learned context data to persistent storage.
     *
     * NOT called per record — [recordSequence] only marks the store dirty and the
     * store's debounced persister writes back in the background. This method
     * requests an immediate asynchronous flush and is invoked from lifecycle
     * boundaries (input-view finish, predictor eviction, coordinator shutdown).
     * No-op when there are no unflushed changes.
     */
    fun save() {
        bigramStore.requestFlush()
        // Future: trigramStore.requestFlush()
    }

    /**
     * Synchronous flush for tests and hard-teardown paths.
     */
    fun saveBlocking() {
        bigramStore.flush()
    }

    /**
     * Get statistics about the context model for the active language.
     */
    data class ContextStats(
        val bigramStats: BigramStore.BigramStats,
        val totalContextWords: Int,
        val averageBoostPotential: Float
    )

    fun getStatistics(): ContextStats {
        val bigramStats = bigramStore.getStatistics(language)

        // Calculate average boost potential (average of all bigram probabilities)
        val allProbabilities = mutableListOf<Float>()
        for (word1 in bigramStats.topContextWords.map { it.first }) {
            val bigrams = bigramStore.getAllBigrams(language, word1)
            allProbabilities.addAll(bigrams.map { it.probability })
        }

        val avgProb = if (allProbabilities.isNotEmpty()) {
            allProbabilities.average().toFloat()
        } else 0f

        val avgBoost = calculateBoost(avgProb)

        return ContextStats(
            bigramStats = bigramStats,
            totalContextWords = bigramStats.uniqueContextWords,
            averageBoostPotential = avgBoost
        )
    }

    /**
     * Export the active language's context data as JSON.
     */
    fun exportToJson(): String {
        return bigramStore.exportToJson(language)
        // Future: Combine with trigramStore.exportToJson()
    }

    /**
     * Import context data for the active language from JSON.
     */
    fun importFromJson(jsonString: String) {
        bigramStore.importFromJson(language, jsonString)
        // Future: trigramStore.importFromJson(jsonString)
    }

    /**
     * Calculate boost multiplier from probability.
     *
     * Uses non-linear formula to amplify high probabilities:
     * boost = (1 + probability)^BOOST_EXPONENT
     *
     * Clamped between MIN_BOOST and MAX_BOOST.
     *
     * @param probability Value between 0.0 and 1.0
     * @return Boost multiplier between MIN_BOOST and MAX_BOOST
     */
    private fun calculateBoost(probability: Float): Float {
        if (probability <= 0f) return MIN_BOOST

        val rawBoost = Math.pow((1.0 + probability).toDouble(), BOOST_EXPONENT.toDouble()).toFloat()
        return rawBoost.coerceIn(MIN_BOOST, MAX_BOOST)
    }

    /**
     * Set minimum frequency threshold for bigrams.
     * Useful for filtering noise in production usage.
     *
     * @param minFrequency Minimum occurrence count (default: 2)
     */
    fun setMinimumFrequency(minFrequency: Int) {
        bigramStore.setMinimumFrequency(minFrequency)
    }
}
