package tribixbite.cleverkeys.ui

/**
 * Represents a single word suggestion for the suggestion bar.
 *
 * Contains the suggested word, confidence score, and metadata about
 * where the suggestion came from (neural network, dictionary, user history).
 *
 * This data class replaces plain String suggestions with rich metadata
 * for better UX (confidence indicators, smart ordering, etc.).
 */
data class Suggestion(
    /**
     * The suggested word text.
     */
    val word: String,

    /**
     * Confidence score (0.0 to 1.0).
     *
     * Higher confidence suggestions are shown with special indicators:
     * - > 0.8: High confidence icon (star)
     * - > 0.6: Medium confidence
     * - < 0.6: Low confidence (may be hidden)
     */
    val confidence: Float,

    /**
     * Source of this suggestion.
     */
    val source: PredictionSource = PredictionSource.NEURAL,

    /**
     * Frequency score from user history (if available).
     *
     * Higher frequency words are prioritized in ranking.
     * 0 means no frequency data available.
     */
    val frequency: Int = 0,

    /**
     * Whether this word was explicitly added to user dictionary.
     */
    val isUserWord: Boolean = false
) {
    /**
     * Display priority for ranking suggestions.
     *
     * Combines confidence and frequency:
     * - High confidence + high frequency = highest priority
     * - User words get bonus priority
     */
    val priority: Float
        get() = confidence * 0.7f + (frequency / 100f) * 0.3f +
                (if (isUserWord) 0.1f else 0.0f)

    /**
     * Whether this suggestion should show a high-confidence indicator.
     */
    val isHighConfidence: Boolean
        get() = confidence > 0.8f

    companion object {
        /**
         * Create simple suggestion from word and confidence.
         */
        fun simple(word: String, confidence: Float = 0.5f) = Suggestion(
            word = word,
            confidence = confidence
        )

        /**
         * Create suggestions from list of words with equal confidence.
         */
        fun fromWords(words: List<String>, confidence: Float = 0.5f) = words.map {
            simple(it, confidence)
        }
    }
}

/**
 * Source of a prediction/suggestion.
 *
 * Used to track where suggestions come from for analytics and debugging.
 */
enum class PredictionSource {
    /**
     * From ONNX neural network prediction.
     */
    NEURAL,

    /**
     * From dictionary lookup/completion.
     */
    DICTIONARY,

    /**
     * From user's typing history/frequency model.
     */
    USER,

    /**
     * From autocorrection engine.
     */
    AUTOCORRECT,

    /**
     * From spell checker.
     */
    SPELLCHECK
}
