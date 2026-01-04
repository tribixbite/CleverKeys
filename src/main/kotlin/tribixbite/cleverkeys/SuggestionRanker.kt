package tribixbite.cleverkeys

import android.util.Log

/**
 * Unified scoring and ranking for multi-dictionary suggestions.
 *
 * Merges candidates from primary and secondary language dictionaries,
 * applying neural confidence, frequency ranks, and language context
 * to produce a single ranked suggestion list.
 *
 * ## Scoring Formula
 * ```
 * score = nnConfidence × rankScore × langMultiplier × sourcePriority
 * ```
 *
 * Where:
 * - nnConfidence: Raw confidence from ONNX model (0.0-1.0)
 * - rankScore: 1.0 - (frequencyRank / 255), higher for common words
 * - langMultiplier: Language context score (0.0-1.0)
 * - sourcePriority: Custom > User > Secondary > Main
 *
 * ## Deduplication
 * When the same word exists in both dictionaries (e.g., "son" in EN+ES),
 * only the entry with the higher final score is kept.
 *
 * @since v1.2.0 - Multilanguage support
 */
class SuggestionRanker(
    private val config: Config? = null
) {
    companion object {
        private const val TAG = "SuggestionRanker"

        // Default weights
        private const val DEFAULT_SECONDARY_PENALTY = 0.9f
        private const val DEFAULT_NN_WEIGHT = 0.6f
        private const val DEFAULT_FREQ_WEIGHT = 0.3f
        private const val DEFAULT_CONTEXT_WEIGHT = 0.1f
    }

    /**
     * Source priority for word candidates.
     * Higher priority sources get score boost.
     */
    enum class WordSource(val priority: Float) {
        CUSTOM(1.2f),      // User-added words (highest priority)
        USER(1.1f),        // Android UserDictionary
        SECONDARY(0.9f),   // Secondary language dictionary
        MAIN(1.0f)         // Primary language dictionary
    }

    /**
     * A word candidate with all scoring components.
     */
    data class Candidate(
        val word: String,              // Display form (may have accents)
        val normalized: String,        // Lookup key (accent-stripped)
        val frequencyRank: Int,        // 0-255, lower = more common
        val source: WordSource,        // Where this word came from
        val nnConfidence: Float = 0f,  // Neural network confidence (0-1)
        val languageCode: String = "en" // Language this word belongs to
    ) {
        /**
         * Calculate the final score for ranking.
         */
        fun calculateScore(
            languageContext: Float,
            secondaryPenalty: Float = DEFAULT_SECONDARY_PENALTY
        ): Float {
            // Frequency rank to score (0=most common → 1.0, 255=least → ~0.0)
            val rankScore = 1.0f - (frequencyRank / 255f)

            // Language multiplier
            val langMultiplier = when (source) {
                WordSource.SECONDARY -> languageContext * secondaryPenalty
                else -> 1.0f
            }

            // Source priority boost
            val sourcePriority = source.priority

            // Combined score
            // NN confidence has most weight, then frequency, then language context
            val nnWeight = DEFAULT_NN_WEIGHT
            val freqWeight = DEFAULT_FREQ_WEIGHT
            val contextWeight = DEFAULT_CONTEXT_WEIGHT

            val baseScore = (nnConfidence * nnWeight) +
                           (rankScore * freqWeight) +
                           (langMultiplier * contextWeight)

            return baseScore * sourcePriority
        }
    }

    /**
     * Ranked suggestion result.
     */
    data class RankedSuggestion(
        val word: String,
        val score: Float,
        val source: WordSource,
        val languageCode: String
    )

    // Configurable parameters
    private var secondaryPenalty: Float = DEFAULT_SECONDARY_PENALTY
    private var primaryLanguageContext: Float = 1.0f
    private var secondaryLanguageContext: Float = 0.5f

    /**
     * Set the secondary language penalty.
     *
     * @param penalty Multiplier for secondary language scores (0.0-1.0)
     */
    fun setSecondaryPenalty(penalty: Float) {
        secondaryPenalty = penalty.coerceIn(0.1f, 1.0f)
    }

    /**
     * Set language context scores based on detection.
     *
     * @param primaryContext Score for primary language (0.0-1.0)
     * @param secondaryContext Score for secondary language (0.0-1.0)
     */
    fun setLanguageContext(primaryContext: Float, secondaryContext: Float) {
        primaryLanguageContext = primaryContext.coerceIn(0.0f, 1.0f)
        secondaryLanguageContext = secondaryContext.coerceIn(0.0f, 1.0f)
    }

    /**
     * Rank and merge candidates from multiple sources.
     *
     * @param primaryCandidates Candidates from primary language dictionary
     * @param secondaryCandidates Candidates from secondary language dictionary
     * @param maxResults Maximum number of suggestions to return
     * @return Ranked list of suggestions
     */
    fun rankAndMerge(
        primaryCandidates: List<Candidate>,
        secondaryCandidates: List<Candidate>,
        maxResults: Int = 5
    ): List<RankedSuggestion> {
        // Score all candidates
        val scoredPrimary = primaryCandidates.map { candidate ->
            val score = candidate.calculateScore(primaryLanguageContext, 1.0f)
            RankedSuggestion(candidate.word, score, candidate.source, candidate.languageCode)
        }

        val scoredSecondary = secondaryCandidates.map { candidate ->
            val score = candidate.calculateScore(secondaryLanguageContext, secondaryPenalty)
            RankedSuggestion(candidate.word, score, candidate.source, candidate.languageCode)
        }

        // Merge and deduplicate
        val allCandidates = (scoredPrimary + scoredSecondary).toMutableList()

        // Deduplicate by normalized form (keep highest score)
        val deduped = mutableMapOf<String, RankedSuggestion>()
        for (suggestion in allCandidates) {
            val normalized = AccentNormalizer.normalize(suggestion.word)
            val existing = deduped[normalized]
            if (existing == null || suggestion.score > existing.score) {
                deduped[normalized] = suggestion
            }
        }

        // Sort by score and take top results
        return deduped.values
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    /**
     * Rank candidates from a single dictionary.
     *
     * @param candidates List of candidates with NN confidence scores
     * @param maxResults Maximum number of suggestions to return
     * @return Ranked list of suggestions
     */
    fun rank(
        candidates: List<Candidate>,
        maxResults: Int = 5
    ): List<RankedSuggestion> {
        return candidates
            .map { candidate ->
                val langContext = when (candidate.source) {
                    WordSource.SECONDARY -> secondaryLanguageContext
                    else -> primaryLanguageContext
                }
                val penalty = when (candidate.source) {
                    WordSource.SECONDARY -> secondaryPenalty
                    else -> 1.0f
                }
                val score = candidate.calculateScore(langContext, penalty)
                RankedSuggestion(candidate.word, score, candidate.source, candidate.languageCode)
            }
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    /**
     * Create candidates from NormalizedPrefixIndex lookup results.
     *
     * @param results Lookup results from NormalizedPrefixIndex
     * @param source Word source (MAIN, SECONDARY, etc.)
     * @param languageCode Language code for these words
     * @param nnConfidences Optional map of normalized word → NN confidence
     * @return List of candidates ready for ranking
     */
    fun createCandidatesFromLookup(
        results: List<NormalizedPrefixIndex.LookupResult>,
        source: WordSource,
        languageCode: String,
        nnConfidences: Map<String, Float> = emptyMap()
    ): List<Candidate> {
        return results.map { result ->
            Candidate(
                word = result.bestCanonical,
                normalized = result.normalized,
                frequencyRank = result.bestFrequencyRank,
                source = source,
                nnConfidence = nnConfidences[result.normalized] ?: 0.5f,
                languageCode = languageCode
            )
        }
    }

    /**
     * Boost candidates that match a prefix more closely.
     *
     * Gives higher scores to candidates where the typed prefix
     * covers more of the total word (shorter completions preferred).
     *
     * @param candidates List of candidates
     * @param typedPrefix The prefix the user typed
     * @return Candidates with adjusted NN confidence scores
     */
    fun applyPrefixBoost(
        candidates: List<Candidate>,
        typedPrefix: String
    ): List<Candidate> {
        val normalizedPrefix = AccentNormalizer.normalize(typedPrefix)
        val prefixLen = normalizedPrefix.length

        return candidates.map { candidate ->
            // Boost based on completion ratio
            val wordLen = candidate.normalized.length
            val completionRatio = if (wordLen > 0) prefixLen.toFloat() / wordLen else 0f

            // Blend original confidence with prefix match boost
            val boostedConfidence = candidate.nnConfidence * (0.7f + 0.3f * completionRatio)

            candidate.copy(nnConfidence = boostedConfidence)
        }
    }
}
