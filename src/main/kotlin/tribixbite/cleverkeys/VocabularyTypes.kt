package tribixbite.cleverkeys

/**
 * Word information with frequency and tier for single-lookup optimization.
 */
data class WordInfo(
    @JvmField val frequency: Float,
    @JvmField val tier: Byte // 0=regular, 1=top5000, 2=common
)

/**
 * Input candidate word from the neural network.
 */
data class CandidateWord(
    @JvmField val word: String,
    @JvmField val confidence: Float
)

/**
 * Filtered prediction with combined scoring.
 */
data class FilteredPrediction(
    @JvmField val word: String,          // Word for insertion (apostrophe-free)
    @JvmField val displayText: String,   // Text for UI display (with apostrophes)
    @JvmField val score: Float,          // Combined confidence + frequency score
    @JvmField val confidence: Float,     // Original NN confidence
    @JvmField val frequency: Float,      // Word frequency
    @JvmField val source: String         // "common", "top5000", "vocabulary", "raw"
) {
    // Constructor matching the one in Java that defaults displayText to word
    constructor(word: String, score: Float, confidence: Float, frequency: Float, source: String) :
            this(word, word, score, confidence, frequency, source)
}

/**
 * Swipe statistics for length-based filtering.
 */
data class SwipeStats(
    @JvmField val expectedLength: Int,
    @JvmField val pathLength: Float,
    @JvmField val speed: Float,
    @JvmField val firstChar: Char, // First character of swipe path for prefix filtering
    @JvmField val lastChar: Char   // Last character of swipe path for contraction filtering
)

/**
 * Vocabulary statistics.
 */
data class VocabularyStats(
    @JvmField val totalWords: Int,
    @JvmField val commonWords: Int,
    @JvmField val top5000: Int,
    @JvmField val isLoaded: Boolean
)
