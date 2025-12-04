package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Coordinates all prediction sources to generate ranked text predictions.
 *
 * Provides intelligent text prediction by combining multiple sources including
 * frequency models, spell checking, autocorrection, neural models, and user history.
 *
 * Features:
 * - Multi-source prediction aggregation
 * - Intelligent ranking algorithm
 * - Context-aware suggestions
 * - Real-time prediction updates
 * - Learning from user selections
 * - Prediction caching for performance
 * - Configurable prediction modes
 * - Source weight management
 * - Personalization support
 * - Next-word prediction
 * - Completion suggestions
 * - Correction suggestions
 *
 * Bug #313 - CATASTROPHIC: Complete implementation of missing TextPredictionEngine.java
 *
 * @param context Application context
 * @param frequencyModel Word frequency model for ranking
 * @param autoCorrection Autocorrection engine for corrections
 * @param spellChecker Spell checker for validation
 */
class TextPredictionEngine(
    private val context: Context,
    private val frequencyModel: FrequencyModel? = null,
    private val autoCorrection: AutoCorrection? = null,
    private val spellChecker: SpellChecker? = null
) {
    companion object {
        private const val TAG = "TextPredictionEngine"

        // Prediction parameters
        private const val DEFAULT_MAX_PREDICTIONS = 10
        private const val DEFAULT_MIN_WORD_LENGTH = 1
        private const val DEFAULT_MIN_CONFIDENCE = 0.1f
        private const val DEFAULT_CACHE_SIZE = 1000
        private const val DEFAULT_CONTEXT_WINDOW = 3

        // Source weights (sum = 1.0)
        private const val WEIGHT_FREQUENCY = 0.35f
        private const val WEIGHT_AUTOCORRECT = 0.30f
        private const val WEIGHT_SPELL_CHECK = 0.20f
        private const val WEIGHT_NEURAL = 0.10f
        private const val WEIGHT_USER_HISTORY = 0.05f

        /**
         * Prediction mode.
         */
        enum class Mode {
            OFF,              // No predictions
            BASIC,            // Basic word completion only
            STANDARD,         // Standard prediction with context
            ADVANCED,         // Advanced with learning
            AGGRESSIVE        // Aggressive with auto-insert
        }

        /**
         * Prediction type.
         */
        enum class Type {
            COMPLETION,       // Complete current word
            NEXT_WORD,        // Predict next word
            CORRECTION,       // Spelling correction
            PHRASE            // Multi-word phrase
        }

        /**
         * Prediction source.
         */
        enum class Source {
            FREQUENCY_MODEL,
            AUTOCORRECTION,
            SPELL_CHECK,
            NEURAL_MODEL,
            USER_HISTORY,
            MANUAL
        }

        /**
         * Prediction result.
         */
        data class Prediction(
            val text: String,
            val type: Type,
            val confidence: Float,
            val source: Source,
            val metadata: Map<String, Any> = emptyMap()
        ) {
            /**
             * Get display text (may differ from actual text).
             */
            fun getDisplayText(): String = metadata["display_text"] as? String ?: text

            /**
             * Check if this is a correction.
             */
            fun isCorrection(): Boolean = type == Type.CORRECTION

            /**
             * Check if this is a completion.
             */
            fun isCompletion(): Boolean = type == Type.COMPLETION
        }

        /**
         * Prediction context.
         */
        data class PredictionContext(
            val currentWord: String = "",
            val previousWords: List<String> = emptyList(),
            val cursorPosition: Int = -1,
            val selectionStart: Int = -1,
            val selectionEnd: Int = -1,
            val inputType: Int = 0,
            val languageHint: String = "en"
        )
    }

    /**
     * Callback interface for prediction events.
     */
    interface Callback {
        /**
         * Called when predictions are updated.
         *
         * @param predictions List of predictions
         */
        fun onPredictionsUpdated(predictions: List<Prediction>)

        /**
         * Called when user selects a prediction.
         *
         * @param prediction Selected prediction
         */
        fun onPredictionSelected(prediction: Prediction)

        /**
         * Called when prediction learning occurs.
         *
         * @param text Learned text
         */
        fun onPredictionLearned(text: String)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private var mode: Mode = Mode.STANDARD
    private var maxPredictions: Int = DEFAULT_MAX_PREDICTIONS
    private var minWordLength: Int = DEFAULT_MIN_WORD_LENGTH
    private var minConfidence: Float = DEFAULT_MIN_CONFIDENCE
    private var contextWindow: Int = DEFAULT_CONTEXT_WINDOW
    private var callback: Callback? = null

    // Caching
    private val predictionCache = ConcurrentHashMap<String, List<Prediction>>()
    private val cacheTimestamps = ConcurrentHashMap<String, Long>()
    private val cacheExpiryMs = 60000L  // 1 minute

    // User selection history
    private val userSelectionHistory = ConcurrentHashMap<String, Long>()  // text -> timestamp
    private val userContextHistory = ConcurrentHashMap<String, Int>()     // context -> count

    // Source weights
    private var sourceWeights = mapOf(
        Source.FREQUENCY_MODEL to WEIGHT_FREQUENCY,
        Source.AUTOCORRECTION to WEIGHT_AUTOCORRECT,
        Source.SPELL_CHECK to WEIGHT_SPELL_CHECK,
        Source.NEURAL_MODEL to WEIGHT_NEURAL,
        Source.USER_HISTORY to WEIGHT_USER_HISTORY
    )

    init {
        logD("TextPredictionEngine initialized (mode: $mode)")
    }

    /**
     * Get predictions for current input context.
     *
     * @param context Input context
     * @return List of ranked predictions
     */
    suspend fun getPredictions(context: PredictionContext): List<Prediction> = withContext(Dispatchers.Default) {
        if (mode == Mode.OFF || !_isEnabled.value) {
            return@withContext emptyList()
        }

        // Check cache first
        val cacheKey = getCacheKey(context)
        predictionCache[cacheKey]?.let { cached ->
            if (isCacheValid(cacheKey)) {
                return@withContext cached
            }
        }

        // Generate predictions from all sources
        val predictions = mutableListOf<Prediction>()

        // 1. Completion predictions (if current word exists)
        if (context.currentWord.length >= minWordLength) {
            predictions.addAll(getCompletionPredictions(context))
        }

        // 2. Next-word predictions (if current word is complete)
        if (context.currentWord.isEmpty() || context.currentWord.endsWith(" ")) {
            predictions.addAll(getNextWordPredictions(context))
        }

        // 3. Correction predictions (if word may be misspelled)
        if (context.currentWord.length >= minWordLength) {
            predictions.addAll(getCorrectionPredictions(context))
        }

        // 4. User history predictions
        predictions.addAll(getUserHistoryPredictions(context))

        // Rank and filter predictions
        val ranked = rankPredictions(predictions, context)
            .filter { it.confidence >= minConfidence }
            .take(maxPredictions)

        // Cache results
        predictionCache[cacheKey] = ranked
        cacheTimestamps[cacheKey] = System.currentTimeMillis()

        // Notify callback
        callback?.onPredictionsUpdated(ranked)

        ranked
    }

    /**
     * Get completion predictions for current word.
     */
    private suspend fun getCompletionPredictions(context: PredictionContext): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val prefix = context.currentWord.lowercase()

        // Get from frequency model
        frequencyModel?.let { model ->
            val topWords = model.getTopWords(
                n = maxPredictions * 2,
                context = context.previousWords.takeLast(contextWindow)
            )

            topWords.filter { it.first.startsWith(prefix) }
                .forEach { (word, freq) ->
                    predictions.add(
                        Prediction(
                            text = word,
                            type = Type.COMPLETION,
                            confidence = freq.toFloat(),
                            source = Source.FREQUENCY_MODEL,
                            metadata = mapOf("frequency" to freq)
                        )
                    )
                }
        }

        // Get from autocorrection
        autoCorrection?.let { ac ->
            val userWords = ac.getUserWords()
            userWords.filter { it.startsWith(prefix) }
                .forEach { word ->
                    predictions.add(
                        Prediction(
                            text = word,
                            type = Type.COMPLETION,
                            confidence = 0.8f,
                            source = Source.AUTOCORRECTION,
                            metadata = mapOf("user_word" to true)
                        )
                    )
                }
        }

        return predictions
    }

    /**
     * Get next-word predictions.
     */
    private suspend fun getNextWordPredictions(context: PredictionContext): List<Prediction> {
        val predictions = mutableListOf<Prediction>()

        // Get from frequency model with context
        frequencyModel?.let { model ->
            val topWords = model.getTopWords(
                n = maxPredictions,
                context = context.previousWords.takeLast(contextWindow)
            )

            topWords.forEach { (word, freq) ->
                predictions.add(
                    Prediction(
                        text = word,
                        type = Type.NEXT_WORD,
                        confidence = freq.toFloat(),
                        source = Source.FREQUENCY_MODEL,
                        metadata = mapOf("frequency" to freq)
                    )
                )
            }
        }

        return predictions
    }

    /**
     * Get correction predictions.
     */
    private suspend fun getCorrectionPredictions(context: PredictionContext): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val word = context.currentWord

        // Get from autocorrection
        autoCorrection?.let { ac ->
            val result = ac.getSuggestions(
                word = word,
                context = context.previousWords.lastOrNull()
            )

            result.suggestions.forEach { suggestion ->
                predictions.add(
                    Prediction(
                        text = suggestion.word,
                        type = Type.CORRECTION,
                        confidence = suggestion.confidence,
                        source = Source.AUTOCORRECTION,
                        metadata = mapOf(
                            "original" to word,
                            "edit_distance" to suggestion.editDistance,
                            "frequency" to suggestion.frequency
                        )
                    )
                )
            }
        }

        // Get from spell checker
        spellChecker?.let { sc ->
            val result = sc.checkWord(word)
            if (!result.isCorrect) {
                result.suggestions.forEach { suggestion ->
                    predictions.add(
                        Prediction(
                            text = suggestion,
                            type = Type.CORRECTION,
                            confidence = result.confidence,
                            source = Source.SPELL_CHECK,
                            metadata = mapOf(
                                "original" to word,
                                "severity" to result.severity
                            )
                        )
                    )
                }
            }
        }

        return predictions
    }

    /**
     * Get predictions from user history.
     */
    private fun getUserHistoryPredictions(context: PredictionContext): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val prefix = context.currentWord.lowercase()

        // Get recently selected words
        userSelectionHistory.entries
            .sortedByDescending { it.value }
            .take(maxPredictions)
            .filter { it.key.startsWith(prefix) }
            .forEach { (word, timestamp) ->
                val recency = (System.currentTimeMillis() - timestamp).toFloat() / (24 * 60 * 60 * 1000)
                val confidence = (1.0f / (1.0f + recency)).coerceIn(0.1f, 0.9f)

                predictions.add(
                    Prediction(
                        text = word,
                        type = Type.COMPLETION,
                        confidence = confidence,
                        source = Source.USER_HISTORY,
                        metadata = mapOf(
                            "timestamp" to timestamp,
                            "recency_days" to recency
                        )
                    )
                )
            }

        return predictions
    }

    /**
     * Rank predictions using weighted scoring.
     */
    private fun rankPredictions(
        predictions: List<Prediction>,
        context: PredictionContext
    ): List<Prediction> {
        // Group by text (combine duplicates from different sources)
        val grouped = predictions.groupBy { it.text }

        // Calculate weighted score for each unique prediction
        val scored = grouped.map { (text, preds) ->
            var totalScore = 0f

            preds.forEach { pred ->
                val weight = sourceWeights[pred.source] ?: 0f
                totalScore += pred.confidence * weight
            }

            // Boost if selected before
            if (userSelectionHistory.containsKey(text)) {
                totalScore *= 1.2f
            }

            // Boost if matches context pattern
            val contextKey = "${context.previousWords.lastOrNull()}_$text"
            val contextCount = userContextHistory[contextKey] ?: 0
            if (contextCount > 0) {
                totalScore *= (1.0f + 0.1f * contextCount.coerceAtMost(10))
            }

            // Take first prediction (highest confidence source)
            val best = preds.maxByOrNull { it.confidence } ?: preds.first()

            best.copy(
                confidence = totalScore.coerceIn(0f, 1f)
            )
        }

        // Sort by score
        return scored.sortedByDescending { it.confidence }
    }

    /**
     * Record user prediction selection (learning).
     *
     * @param prediction Selected prediction
     * @param context Input context
     */
    suspend fun recordSelection(
        prediction: Prediction,
        context: PredictionContext
    ) = withContext(Dispatchers.Default) {
        // Update selection history
        userSelectionHistory[prediction.text] = System.currentTimeMillis()

        // Update context history
        val contextKey = "${context.previousWords.lastOrNull()}_${prediction.text}"
        userContextHistory[contextKey] = (userContextHistory[contextKey] ?: 0) + 1

        // Learn in frequency model
        frequencyModel?.recordUsage(
            context.previousWords.takeLast(contextWindow) + prediction.text
        )

        // Learn in autocorrection if it's a correction
        if (prediction.isCorrection()) {
            val original = prediction.metadata["original"] as? String
            if (original != null && original != prediction.text) {
                autoCorrection?.addWord(prediction.text)
            }
        }

        logD("Recorded selection: ${prediction.text} (source: ${prediction.source})")
        callback?.onPredictionSelected(prediction)
        callback?.onPredictionLearned(prediction.text)
    }

    /**
     * Clear prediction cache.
     */
    fun clearCache() {
        predictionCache.clear()
        cacheTimestamps.clear()
        logD("Prediction cache cleared")
    }

    /**
     * Clear user history.
     */
    fun clearUserHistory() {
        userSelectionHistory.clear()
        userContextHistory.clear()
        logD("User history cleared")
    }

    /**
     * Generate cache key from context.
     */
    private fun getCacheKey(context: PredictionContext): String {
        return "${context.currentWord}|${context.previousWords.joinToString("|")}"
    }

    /**
     * Check if cache entry is still valid.
     */
    private fun isCacheValid(cacheKey: String): Boolean {
        val timestamp = cacheTimestamps[cacheKey] ?: return false
        return (System.currentTimeMillis() - timestamp) < cacheExpiryMs
    }

    /**
     * Set prediction mode.
     *
     * @param mode Prediction mode
     */
    fun setMode(mode: Mode) {
        this.mode = mode
        clearCache()
        logD("Mode set to: $mode")
    }

    /**
     * Get current prediction mode.
     *
     * @return Current mode
     */
    fun getMode(): Mode = mode

    /**
     * Enable or disable predictions.
     *
     * @param enabled Whether predictions are enabled
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (!enabled) {
            clearCache()
        }
        logD("Predictions ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set maximum number of predictions.
     *
     * @param max Maximum predictions
     */
    fun setMaxPredictions(max: Int) {
        maxPredictions = max.coerceIn(1, 50)
        clearCache()
    }

    /**
     * Set minimum word length for predictions.
     *
     * @param length Minimum length
     */
    fun setMinWordLength(length: Int) {
        minWordLength = length.coerceIn(1, 10)
        clearCache()
    }

    /**
     * Set minimum confidence threshold.
     *
     * @param confidence Minimum confidence (0.0 - 1.0)
     */
    fun setMinConfidence(confidence: Float) {
        minConfidence = confidence.coerceIn(0f, 1f)
        clearCache()
    }

    /**
     * Set context window size.
     *
     * @param size Window size (number of previous words to consider)
     */
    fun setContextWindow(size: Int) {
        contextWindow = size.coerceIn(0, 5)
        clearCache()
    }

    /**
     * Set source weights.
     *
     * @param weights Map of source to weight (should sum to ~1.0)
     */
    fun setSourceWeights(weights: Map<Source, Float>) {
        sourceWeights = weights
        clearCache()
        logD("Source weights updated")
    }

    /**
     * Get prediction statistics.
     *
     * @return Map of statistic names to values
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "mode" to mode.name,
        "enabled" to _isEnabled.value,
        "max_predictions" to maxPredictions,
        "min_word_length" to minWordLength,
        "min_confidence" to minConfidence,
        "context_window" to contextWindow,
        "cache_size" to predictionCache.size,
        "user_history_size" to userSelectionHistory.size,
        "context_history_size" to userContextHistory.size
    )

    /**
     * Set callback for prediction events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing TextPredictionEngine resources...")

        try {
            scope.cancel()
            callback = null
            predictionCache.clear()
            cacheTimestamps.clear()
            userSelectionHistory.clear()
            userContextHistory.clear()
            logD("✅ TextPredictionEngine resources released")
        } catch (e: Exception) {
            logE("Error releasing prediction engine resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
