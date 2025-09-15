package tribixbite.keyboard2

/**
 * Result container for word predictions with scores
 * Used by both legacy and neural prediction systems
 * 
 * Kotlin data class with convenience methods and null safety
 */
data class PredictionResult(
    val words: List<String>,
    val scores: List<Int> // Scores as integers (0-1000 range)
) {
    /**
     * Check if result has any predictions
     */
    val isEmpty: Boolean get() = words.isEmpty()
    
    /**
     * Number of predictions
     */
    val size: Int get() = words.size
    
    /**
     * Get top prediction safely
     */
    val topPrediction: String? get() = words.firstOrNull()
    
    /**
     * Get top score safely  
     */
    val topScore: Int? get() = scores.firstOrNull()
    
    /**
     * Get prediction by index safely
     */
    fun getPredictionAt(index: Int): String? = words.getOrNull(index)
    
    /**
     * Get score by index safely
     */
    fun getScoreAt(index: Int): Int? = scores.getOrNull(index)
    
    /**
     * Create prediction pairs for easy iteration
     */
    val predictions: List<Pair<String, Int>>
        get() = words.zip(scores)
    
    /**
     * Filter predictions by minimum score
     */
    fun filterByScore(minScore: Int): PredictionResult {
        val filtered = predictions.filter { it.second >= minScore }
        return PredictionResult(
            words = filtered.map { it.first },
            scores = filtered.map { it.second }
        )
    }
    
    /**
     * Take top N predictions
     */
    fun take(n: Int): PredictionResult = PredictionResult(
        words = words.take(n),
        scores = scores.take(n)
    )
    
    companion object {
        /**
         * Empty result for error cases
         */
        val empty = PredictionResult(emptyList(), emptyList())
    }
}