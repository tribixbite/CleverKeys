package juloo.keyboard2

import android.content.Context
import kotlinx.coroutines.*

/**
 * Word prediction engine for traditional typing and fallback
 * Kotlin implementation with coroutines and improved algorithms
 */
class WordPredictor(private val context: Context) {
    
    companion object {
        private const val TAG = "WordPredictor"
        private const val MAX_PREDICTIONS = 10
    }
    
    private val dictionary = mutableSetOf<String>()
    private val bigramModel = BigramModel(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * Initialize predictor
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            loadDictionary()
            bigramModel.initialize()
            true
        } catch (e: Exception) {
            logE("Failed to initialize word predictor", e)
            false
        }
    }
    
    /**
     * Predict words from partial input
     */
    suspend fun predictWords(partial: String): PredictionResult = withContext(Dispatchers.Default) {
        if (partial.isBlank()) return@withContext PredictionResult.empty
        
        val predictions = dictionary
            .filter { it.startsWith(partial.lowercase()) }
            .sortedBy { it.length }
            .take(MAX_PREDICTIONS)
        
        val scores = predictions.mapIndexed { index, _ -> 1000 - index * 100 }
        
        PredictionResult(predictions, scores)
    }
    
    /**
     * Predict words with context
     */
    suspend fun predictWordsWithContext(partial: String, context: List<String>): PredictionResult = withContext(Dispatchers.Default) {
        val basePredictions = predictWords(partial)
        
        if (context.isEmpty()) return@withContext basePredictions
        
        // Apply bigram scoring
        val contextualPredictions = basePredictions.words.map { word ->
            val bigramScore = bigramModel.getBigramProbability(context.lastOrNull() ?: "", word)
            word to (basePredictions.scores[basePredictions.words.indexOf(word)] * bigramScore).toInt()
        }.sortedByDescending { it.second }
        
        PredictionResult(
            contextualPredictions.map { it.first },
            contextualPredictions.map { it.second }
        )
    }
    
    /**
     * Load dictionary from assets
     */
    private suspend fun loadDictionary() = withContext(Dispatchers.IO) {
        try {
            context.assets.open("dictionaries/en.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val word = line.trim().lowercase()
                    if (word.isNotBlank() && word.all { it.isLetter() }) {
                        dictionary.add(word)
                    }
                }
            }
            logD("Loaded ${dictionary.size} words for prediction")
        } catch (e: Exception) {
            logE("Failed to load dictionary", e)
        }
    }
    
    /**
     * Add word to user dictionary
     */
    fun addUserWord(word: String) {
        if (word.isNotBlank() && word.all { it.isLetter() }) {
            dictionary.add(word.lowercase())
        }
    }
    
    /**
     * Check if word is in dictionary
     */
    fun isValidWord(word: String): Boolean {
        return dictionary.contains(word.lowercase())
    }
    
    /**
     * Get dictionary size
     */
    val dictionarySize: Int get() = dictionary.size
    
    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
    }
}

/**
 * Simple bigram model for context-aware predictions
 */
class BigramModel(private val context: Context) {
    
    private val bigramCounts = mutableMapOf<Pair<String, String>, Int>()
    private val unigramCounts = mutableMapOf<String, Int>()
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        // Load basic bigram data
        loadBasicBigrams()
        true
    }
    
    private fun loadBasicBigrams() {
        // Common bigrams with frequencies
        val commonBigrams = mapOf(
            "the" to listOf("end", "first", "last", "other", "same", "new"),
            "and" to listOf("the", "then", "now", "here", "there"),
            "for" to listOf("the", "a", "all", "some", "many"),
            "you" to listOf("are", "can", "will", "have", "know"),
            "that" to listOf("is", "was", "the", "this", "they")
        )
        
        commonBigrams.forEach { (first, seconds) ->
            seconds.forEachIndexed { index, second ->
                val count = 1000 - index * 100
                bigramCounts[first to second] = count
                unigramCounts[second] = unigramCounts.getOrDefault(second, 0) + count
            }
        }
    }
    
    fun getBigramProbability(firstWord: String, secondWord: String): Float {
        val bigramCount = bigramCounts[firstWord.lowercase() to secondWord.lowercase()] ?: 0
        val unigramCount = unigramCounts[secondWord.lowercase()] ?: 1
        
        return if (unigramCount > 0) {
            (bigramCount.toFloat() / unigramCount) + 0.1f // Add smoothing
        } else 0.1f
    }
}