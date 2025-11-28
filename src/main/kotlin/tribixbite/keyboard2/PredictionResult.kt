package tribixbite.keyboard2

/**
 * Result container for word predictions with scores
 * Used by both legacy and neural prediction systems
 */
data class PredictionResult(
    @JvmField val words: List<String>,
    @JvmField val scores: List<Int> // Scores as integers (0-1000 range)
)
