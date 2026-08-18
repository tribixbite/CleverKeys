package tribixbite.cleverkeys

/**
 * Result container for word predictions with scores
 * Used by both the tap and swipe prediction paths
 */
data class PredictionResult(
    @JvmField val words: List<String>,
    @JvmField val scores: List<Int> // Scores as integers (0-1000 range)
)
