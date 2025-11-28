package tribixbite.keyboard2

/**
 * Data models for beam search algorithm
 * Extracted from OnnxSwipePredictor for better separation of concerns
 *
 * @since v1.32.432
 */

/**
 * Represents the state of a single beam in beam search
 * Tracks token sequence, cumulative score, and completion status
 */
data class BeamSearchState(
    val tokens: MutableList<Long>,
    var score: Float,
    var finished: Boolean
) {
    /**
     * Primary constructor with start token
     */
    constructor(startToken: Int, startScore: Float = 0.0f, isFinished: Boolean = false) : this(
        tokens = mutableListOf(startToken.toLong()),
        score = startScore,
        finished = isFinished
    )

    /**
     * Copy constructor for beam expansion
     */
    constructor(other: BeamSearchState) : this(
        tokens = other.tokens.toMutableList(),
        score = other.score,
        finished = other.finished
    )
}

/**
 * Helper class for sorting indices by their values
 * Used in top-k selection during beam search
 */
data class IndexValue(
    val index: Int,
    val value: Float
) : Comparable<IndexValue> {
    override fun compareTo(other: IndexValue): Int {
        // Sort in descending order (higher values first)
        return other.value.compareTo(this.value)
    }
}

/**
 * Final beam search candidate with decoded word and confidence
 * Represents a completed prediction ready for presentation
 */
data class BeamSearchCandidate(
    val word: String,
    val confidence: Float
) : Comparable<BeamSearchCandidate> {
    override fun compareTo(other: BeamSearchCandidate): Int {
        // Sort in descending order by confidence (higher confidence first)
        return other.confidence.compareTo(this.confidence)
    }
}
