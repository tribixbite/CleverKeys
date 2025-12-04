package tribixbite.cleverkeys

import android.content.Context
import kotlinx.coroutines.*

/**
 * Complete optimized vocabulary implementation
 * Kotlin version with full functionality from Java original
 */
class OptimizedVocabularyImpl(
    private val context: Context,
    private val config: VocabularyConfig = VocabularyConfig()
) {

    companion object {
        private const val TAG = "OptimizedVocabulary"
    }

    /**
     * Configuration for vocabulary tuning
     * Bug #173 fix: Make hardcoded limits configurable
     * Bug #171 fix: Add OOV handling configuration
     */
    data class VocabularyConfig(
        val maxWords: Int = 150_000,
        val commonWordsCount: Int = 100,
        val top5000Count: Int = 5000,
        val frequencyBoostMultiplier: Float = 1000f,
        val commonWordBoost: Float = 2.0f,
        val top5000Boost: Float = 1.5f,
        val longWordLengthThreshold: Int = 12,
        val longWordPenalty: Float = 0.5f,
        val swipePathLengthDivisor: Float = 50f,
        val typingSpeedMultiplier: Float = 0.15f,
        val oovPenalty: Float = 0.3f, // Penalty for out-of-vocabulary words (vs filtering them out)
        val oovMinConfidence: Float = 0.5f // Minimum neural confidence to keep OOV predictions
    )
    
    // Vocabulary data structures
    private val wordFrequencies = mutableMapOf<String, Float>()
    private val commonWords = mutableSetOf<String>()
    private val top5000 = mutableSetOf<String>()
    private val wordsByLength = mutableMapOf<Int, MutableSet<String>>()
    private val minFrequencyByLength = mutableMapOf<Int, Float>()
    private var isLoaded = false
    
    /**
     * Load vocabulary from assets with frequency data
     * Bug #172 fix: Return false on failure instead of throwing RuntimeException
     */
    suspend fun loadVocabulary(): Boolean = withContext(Dispatchers.IO) {
        try {
            logD("Loading optimized vocabulary from assets...")

            loadWordFrequencies()
            createFastPathSets()
            initializeFrequencyThresholds()
            createLengthBasedLookup()

            isLoaded = true
            logD("Vocabulary loaded: ${wordFrequencies.size} total words, ${commonWords.size} common, ${top5000.size} top5000")
            true
        } catch (e: Exception) {
            logE("Failed to load vocabulary - returning false for graceful degradation", e)
            isLoaded = false
            false // Return false instead of throwing exception
        }
    }
    
    /**
     * Load word frequencies from dictionary
     */
    private suspend fun loadWordFrequencies() = withContext(Dispatchers.IO) {
        context.assets.open("dictionaries/en.txt").bufferedReader().useLines { lines ->
            var wordCount = 0
            lines.forEach { line ->
                val word = line.trim().lowercase()
                if (word.isNotBlank() && word.all { it.isLetter() }) {
                    val frequency = 1.0f / (wordCount + 1.0f)
                    wordFrequencies[word] = frequency
                    wordCount++

                    if (wordCount >= config.maxWords) return@forEach // Limit for memory
                }
            }
        }
        
        // Load enhanced dictionary
        try {
            context.assets.open("dictionaries/en_enhanced.txt").bufferedReader().useLines { lines ->
                var wordCount = wordFrequencies.size
                lines.forEach { line ->
                    val word = line.trim().lowercase()
                    if (word.isNotBlank() && word.all { it.isLetter() } && !wordFrequencies.containsKey(word)) {
                        val frequency = 1.0f / (wordCount + 1.0f)
                        wordFrequencies[word] = frequency
                        wordCount++
                    }
                }
            }
        } catch (e: Exception) {
            logW("Enhanced dictionary not available: ${e.message}")
        }
    }
    
    /**
     * Create fast-path sets for performance
     */
    private fun createFastPathSets() {
        val sortedWords = wordFrequencies.toList().sortedByDescending { it.second }

        // Top words are common
        commonWords.addAll(sortedWords.take(config.commonWordsCount).map { it.first })

        // Top N most frequent
        top5000.addAll(sortedWords.take(config.top5000Count).map { it.first })
    }
    
    /**
     * Initialize frequency thresholds by word length
     */
    private fun initializeFrequencyThresholds() {
        wordFrequencies.forEach { (word, frequency) ->
            val length = word.length
            val currentMin = minFrequencyByLength[length]
            if (currentMin == null || frequency < currentMin) {
                minFrequencyByLength[length] = frequency
            }
        }
    }
    
    /**
     * Create length-based lookup for fast filtering
     */
    private fun createLengthBasedLookup() {
        wordFrequencies.forEach { (word, _) ->
            wordsByLength.getOrPut(word.length) { mutableSetOf() }.add(word)
        }
    }
    
    /**
     * Filter and rank neural predictions
     * Bug #171 fix: Keep OOV predictions with penalty instead of filtering them out
     */
    fun filterPredictions(rawPredictions: List<CandidateWord>, swipeStats: SwipeStats): List<FilteredPrediction> {
        if (!isLoaded) {
            logW("Vocabulary not loaded, returning raw predictions")
            return rawPredictions.map { FilteredPrediction(it.word, it.confidence) }
        }

        return rawPredictions.mapNotNull { candidate ->
            val word = candidate.word.lowercase()
            val frequency = wordFrequencies[word]

            // Handle OOV (out-of-vocabulary) words
            val vocabularyScore = if (frequency != null) {
                // In-vocabulary: apply frequency-based scoring
                calculateVocabularyScore(word, frequency)
            } else {
                // OOV: keep if neural confidence is high enough, apply penalty
                if (candidate.confidence < config.oovMinConfidence) {
                    return@mapNotNull null // Filter out low-confidence OOV words
                }
                config.oovPenalty // Penalty for OOV (default 0.3 = 70% reduction)
            }

            // Calculate combined score
            val contextScore = calculateContextScore(word, swipeStats)
            val combinedScore = candidate.confidence * vocabularyScore * contextScore

            FilteredPrediction(word, combinedScore)
        }.sortedByDescending { it.score }
    }
    
    /**
     * Calculate vocabulary-based scoring
     */
    private fun calculateVocabularyScore(word: String, frequency: Float): Float {
        var score = 1.0f

        // Frequency boost
        score *= (frequency * config.frequencyBoostMultiplier + 1.0f)

        // Common word boost
        if (word in commonWords) {
            score *= config.commonWordBoost
        }

        // Top N boost
        if (word in top5000) {
            score *= config.top5000Boost
        }

        // Length penalty for very long words
        if (word.length > config.longWordLengthThreshold) {
            score *= config.longWordPenalty
        }

        return score
    }
    
    /**
     * Calculate context-based scoring
     */
    private fun calculateContextScore(word: String, swipeStats: SwipeStats): Float {
        var score = 1.0f

        // Length vs swipe path correlation
        val expectedLength = swipeStats.pathLength / config.swipePathLengthDivisor
        val lengthDiff = kotlin.math.abs(word.length - expectedLength)
        score *= kotlin.math.max(0.5f, 1.0f - lengthDiff * 0.1f)

        // Duration correlation
        val expectedDuration = word.length * config.typingSpeedMultiplier
        val durationDiff = kotlin.math.abs(swipeStats.duration - expectedDuration)
        score *= kotlin.math.max(0.7f, 1.0f - durationDiff * 0.2f)

        return score
    }
    
    /**
     * Check if word is valid
     */
    fun isValidWord(word: String): Boolean {
        return wordFrequencies.containsKey(word.lowercase())
    }
    
    /**
     * Get word frequency
     */
    fun getWordFrequency(word: String): Float {
        return wordFrequencies[word.lowercase()] ?: 0f
    }
    
    /**
     * Get vocabulary statistics
     */
    fun getStats(): VocabularyStats {
        return VocabularyStats(
            totalWords = wordFrequencies.size,
            commonWords = commonWords.size,
            top5000Words = top5000.size,
            averageLength = if (wordFrequencies.isNotEmpty()) {
                wordFrequencies.keys.map { it.length }.average().toFloat()
            } else 0f
        )
    }
    
    fun isLoaded(): Boolean = isLoaded

    // Logging functions
    private fun logD(message: String) {
        android.util.Log.d(TAG, message)
    }

    private fun logE(message: String, throwable: Throwable) {
        android.util.Log.e(TAG, message, throwable)
    }

    private fun logW(message: String) {
        android.util.Log.w(TAG, message)
    }

    /**
     * Data classes for predictions and stats
     */
    data class CandidateWord(val word: String, val confidence: Float)
    data class FilteredPrediction(val word: String, val score: Float)
    data class SwipeStats(val pathLength: Float, val duration: Float, val straightnessRatio: Float)
    data class VocabularyStats(
        val totalWords: Int,
        val commonWords: Int, 
        val top5000Words: Int,
        val averageLength: Float
    )
}