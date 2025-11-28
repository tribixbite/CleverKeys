package tribixbite.keyboard2.personalization

import android.util.Log

/**
 * Personalized scorer for adjusting prediction scores based on user behavior.
 *
 * Applies personalization boosts to word predictions, combining:
 * - Base prediction score (from neural model / dictionary)
 * - Personalization boost (from user vocabulary)
 * - Optional context boost (from N-gram model)
 *
 * Thread-safe for concurrent prediction scoring.
 *
 * Example:
 * ```kotlin
 * val scorer = PersonalizedScorer(personalizationEngine)
 *
 * // Score a single prediction
 * val baseScore = 0.75f
 * val personalizedScore = scorer.scoreWithPersonalization("kotlin", baseScore)
 * // Result: 0.75 * (1 + personalizationBoost) = higher score for frequently used words
 *
 * // Score multiple predictions
 * val predictions = listOf("kotlin", "java", "python")
 * val baseScores = listOf(0.75f, 0.70f, 0.65f)
 * val scores = scorer.scoreMultiple(predictions, baseScores)
 * ```
 */
class PersonalizedScorer(private val personalizationEngine: PersonalizationEngine) {
    companion object {
        private const val TAG = "PersonalizedScorer"

        // Scoring constants
        private const val MIN_BASE_SCORE = 0.01f
        private const val MAX_SCORE = 1.0f

        // Boost application modes
        private const val ADDITIVE_WEIGHT = 0.3f // For additive mode
        private const val MULTIPLICATIVE_MIN = 1.0f // For multiplicative mode
    }

    /**
     * Scoring mode determines how personalization boost is applied.
     *
     * - MULTIPLICATIVE: score * (1 + boost) - Amplifies existing predictions
     * - ADDITIVE: score + (boost * weight) - Adds fixed boost amount
     * - HYBRID: Combination of both (default, best performance)
     */
    enum class ScoringMode {
        MULTIPLICATIVE,
        ADDITIVE,
        HYBRID
    }

    private var scoringMode: ScoringMode = ScoringMode.HYBRID

    /**
     * Set scoring mode.
     */
    fun setScoringMode(mode: ScoringMode) {
        this.scoringMode = mode
        Log.d(TAG, "Scoring mode set to $mode")
    }

    /**
     * Get current scoring mode.
     */
    fun getScoringMode(): ScoringMode = scoringMode

    /**
     * Apply personalization boost to a prediction score.
     *
     * @param word The predicted word
     * @param baseScore Base prediction score (0.0-1.0)
     * @param currentTime Optional timestamp for recency calculation
     * @return Personalized score (0.0-1.0)
     */
    fun scoreWithPersonalization(
        word: String,
        baseScore: Float,
        currentTime: Long = System.currentTimeMillis()
    ): Float {
        if (baseScore < MIN_BASE_SCORE) {
            return baseScore // Too low to boost meaningfully
        }

        if (!personalizationEngine.isEnabled()) {
            return baseScore // Personalization disabled
        }

        val boost = personalizationEngine.getPersonalizationBoost(word, currentTime)

        if (boost == 0.0f) {
            return baseScore // No personalization data for this word
        }

        val personalizedScore = when (scoringMode) {
            ScoringMode.MULTIPLICATIVE -> applyMultiplicativeBoost(baseScore, boost)
            ScoringMode.ADDITIVE -> applyAdditiveBoost(baseScore, boost)
            ScoringMode.HYBRID -> applyHybridBoost(baseScore, boost)
        }

        return personalizedScore.coerceIn(MIN_BASE_SCORE, MAX_SCORE)
    }

    /**
     * Score multiple predictions with personalization.
     *
     * @param words List of predicted words
     * @param baseScores List of base scores (must match words.size)
     * @return List of personalized scores
     */
    fun scoreMultiple(
        words: List<String>,
        baseScores: List<Float>,
        currentTime: Long = System.currentTimeMillis()
    ): List<Float> {
        require(words.size == baseScores.size) {
            "Words and scores must have same size (${words.size} != ${baseScores.size})"
        }

        return words.zip(baseScores).map { (word, baseScore) ->
            scoreWithPersonalization(word, baseScore, currentTime)
        }
    }

    /**
     * Score predictions and return sorted results.
     *
     * @param predictions Map of word → base score
     * @param topK Number of top predictions to return
     * @return List of (word, personalizedScore) pairs, sorted by score descending
     */
    fun scoreAndRank(
        predictions: Map<String, Float>,
        topK: Int = 10,
        currentTime: Long = System.currentTimeMillis()
    ): List<Pair<String, Float>> {
        val scored = predictions.map { (word, baseScore) ->
            word to scoreWithPersonalization(word, baseScore, currentTime)
        }

        return scored
            .sortedByDescending { it.second }
            .take(topK)
    }

    /**
     * Apply multiplicative boost: score * (1 + boost).
     *
     * Amplifies existing predictions proportionally.
     * Example: 0.75 * (1 + 2.0) = 2.25 → clamped to 1.0
     */
    private fun applyMultiplicativeBoost(baseScore: Float, boost: Float): Float {
        val multiplier = MULTIPLICATIVE_MIN + boost
        return baseScore * multiplier
    }

    /**
     * Apply additive boost: score + (boost * weight).
     *
     * Adds fixed boost amount weighted by ADDITIVE_WEIGHT.
     * Example: 0.75 + (2.0 * 0.3) = 1.35 → clamped to 1.0
     */
    private fun applyAdditiveBoost(baseScore: Float, boost: Float): Float {
        return baseScore + (boost * ADDITIVE_WEIGHT)
    }

    /**
     * Apply hybrid boost: geometric mean of multiplicative and additive.
     *
     * Combines benefits of both approaches:
     * - Multiplicative: Amplifies strong predictions
     * - Additive: Boosts weak predictions
     *
     * Formula: sqrt(multiplicative * additive)
     */
    private fun applyHybridBoost(baseScore: Float, boost: Float): Float {
        val multiplicative = applyMultiplicativeBoost(baseScore, boost)
        val additive = applyAdditiveBoost(baseScore, boost)

        // Geometric mean
        return Math.sqrt((multiplicative * additive).toDouble()).toFloat()
    }

    /**
     * Calculate effective boost multiplier for a word.
     *
     * Returns how much the score would be multiplied by personalization.
     * Useful for debugging and analytics.
     */
    fun getEffectiveMultiplier(
        word: String,
        baseScore: Float,
        currentTime: Long = System.currentTimeMillis()
    ): Float {
        if (baseScore < MIN_BASE_SCORE || !personalizationEngine.isEnabled()) {
            return 1.0f
        }

        val personalizedScore = scoreWithPersonalization(word, baseScore, currentTime)
        return if (baseScore > 0) personalizedScore / baseScore else 1.0f
    }

    /**
     * Get detailed scoring explanation for debugging.
     */
    fun explainScoring(
        word: String,
        baseScore: Float,
        currentTime: Long = System.currentTimeMillis()
    ): ScoringExplanation {
        val boost = personalizationEngine.getPersonalizationBoost(word, currentTime)
        val personalizedScore = scoreWithPersonalization(word, baseScore, currentTime)
        val multiplier = getEffectiveMultiplier(word, baseScore, currentTime)

        val boostExplanation = personalizationEngine.explainBoost(word, currentTime)

        val explanation = buildString {
            append("Scoring for '$word':\n")
            append("  Mode: $scoringMode\n")
            append("  Base score: ${"%.3f".format(baseScore)}\n")
            append("  Personalization boost: ${"%.2f".format(boost)}\n")
            append("  Personalized score: ${"%.3f".format(personalizedScore)}\n")
            append("  Effective multiplier: ${"%.2f".format(multiplier)}x\n\n")
            append("Boost details:\n")
            append(boostExplanation.explanation)
        }

        return ScoringExplanation(
            word = word,
            baseScore = baseScore,
            personalizationBoost = boost,
            personalizedScore = personalizedScore,
            effectiveMultiplier = multiplier,
            scoringMode = scoringMode,
            explanation = explanation
        )
    }
}

/**
 * Detailed scoring explanation for debugging.
 */
data class ScoringExplanation(
    val word: String,
    val baseScore: Float,
    val personalizationBoost: Float,
    val personalizedScore: Float,
    val effectiveMultiplier: Float,
    val scoringMode: PersonalizedScorer.ScoringMode,
    val explanation: String
) {
    override fun toString(): String = explanation
}
