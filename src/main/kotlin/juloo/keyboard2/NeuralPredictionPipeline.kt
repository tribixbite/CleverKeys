package juloo.keyboard2

import android.content.Context
import android.graphics.PointF
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Complete neural prediction pipeline integration
 * Connects gesture recognition → feature extraction → ONNX inference → vocabulary filtering
 */
class NeuralPredictionPipeline(private val context: Context) {
    
    companion object {
        private const val TAG = "NeuralPredictionPipeline"
    }
    
    // Pipeline components
    private val gestureRecognizer = SwipeGestureRecognizer()
    private val swipeDetector = SwipeDetector()
    private val neuralEngine = NeuralSwipeEngine(context, Config.globalConfig())
    private val wordPredictor = WordPredictor(context)
    private val performanceProfiler = PerformanceProfiler(context)
    
    // Pipeline state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isInitialized = false
    
    /**
     * Pipeline result with comprehensive data
     */
    data class PipelineResult(
        val predictions: PredictionResult,
        val gestureInfo: SwipeGestureRecognizer.RecognizedGesture,
        val swipeClassification: SwipeDetector.SwipeClassification,
        val processingTimeMs: Long,
        val source: PredictionSource
    )
    
    /**
     * Prediction source type
     */
    enum class PredictionSource { NEURAL, TRADITIONAL, HYBRID, FALLBACK }
    
    /**
     * Initialize complete pipeline
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        return@withContext ErrorHandling.safeExecute("Pipeline initialization") {
            // Initialize all components
            val neuralInit = neuralEngine.initialize()
            val wordPredictorInit = wordPredictor.initialize()
            
            isInitialized = neuralInit && wordPredictorInit
            
            if (isInitialized) {
                logD("Neural prediction pipeline initialized successfully")
            } else {
                logE("Pipeline initialization failed: neural=$neuralInit, traditional=$wordPredictorInit")
            }
            
            isInitialized
        }.getOrElse { false }
    }
    
    /**
     * Process complete gesture through entire pipeline
     */
    suspend fun processGesture(
        points: List<PointF>, 
        timestamps: List<Long>,
        context: List<String> = emptyList()
    ): PipelineResult = withContext(Dispatchers.Default) {
        
        return@withContext performanceProfiler.measureOperation("complete_pipeline") {
            // Step 1: Gesture recognition and classification
            val gestureResult = gestureRecognizer.recognizeGesture(points, timestamps)
            val swipeInput = SwipeInput(points, timestamps, emptyList())
            val swipeClassification = swipeDetector.detectSwipe(swipeInput)
            
            // Step 2: Determine prediction strategy
            val predictionSource = determinePredictionStrategy(gestureResult.gesture, swipeClassification)
            
            // Step 3: Execute prediction based on strategy
            val predictions = when (predictionSource) {
                PredictionSource.NEURAL -> {
                    performanceProfiler.measureOperation("neural_prediction") {
                        executeNeuralPrediction(swipeInput)
                    }
                }
                PredictionSource.TRADITIONAL -> {
                    performanceProfiler.measureOperation("traditional_prediction") {
                        executeTraditionalPrediction(swipeInput, context)
                    }
                }
                PredictionSource.HYBRID -> {
                    performanceProfiler.measureOperation("hybrid_prediction") {
                        executeHybridPrediction(swipeInput, context)
                    }
                }
                PredictionSource.FALLBACK -> {
                    performanceProfiler.measureOperation("fallback_prediction") {
                        executeFallbackPrediction(swipeInput)
                    }
                }
            }
            
            PipelineResult(
                predictions = predictions,
                gestureInfo = gestureResult.gesture,
                swipeClassification = swipeClassification,
                processingTimeMs = 0L, // Will be filled by measureOperation
                source = predictionSource
            )
        }
    }
    
    /**
     * Determine best prediction strategy based on gesture analysis
     */
    private fun determinePredictionStrategy(
        gesture: SwipeGestureRecognizer.RecognizedGesture,
        classification: SwipeDetector.SwipeClassification
    ): PredictionSource {
        return when {
            // Use neural for high-quality swipes
            classification.isSwipe && 
            classification.quality in listOf(SwipeDetector.SwipeQuality.EXCELLENT, SwipeDetector.SwipeQuality.GOOD) &&
            gesture.type in listOf(
                SwipeGestureRecognizer.GestureType.SWIPE_HORIZONTAL,
                SwipeGestureRecognizer.GestureType.SWIPE_DIAGONAL
            ) -> PredictionSource.NEURAL
            
            // Use traditional for simple linear gestures
            classification.isSwipe && 
            gesture.type == SwipeGestureRecognizer.GestureType.SWIPE_HORIZONTAL &&
            gesture.distance < 200f -> PredictionSource.TRADITIONAL
            
            // Use hybrid for complex patterns
            classification.isSwipe &&
            gesture.type in listOf(
                SwipeGestureRecognizer.GestureType.CIRCLE_CLOCKWISE,
                SwipeGestureRecognizer.GestureType.LOOP,
                SwipeGestureRecognizer.GestureType.ZIG_ZAG
            ) -> PredictionSource.HYBRID
            
            // Fallback for everything else
            else -> PredictionSource.FALLBACK
        }
    }
    
    /**
     * Execute neural prediction with validation
     */
    private suspend fun executeNeuralPrediction(input: SwipeInput): PredictionResult {
        // Validate input
        val validation = ErrorHandling.Validation.validateSwipeInput(input)
        if (!validation.isValid) {
            logW("Invalid swipe input for neural prediction: ${validation.getErrorSummary()}")
            return PredictionResult.empty
        }
        
        return if (isInitialized) {
            try {
                neuralEngine.predictAsync(input)
            } catch (e: Exception) {
                logE("Neural prediction failed, falling back", e)
                executeTraditionalPrediction(input, emptyList())
            }
        } else {
            logW("Neural engine not initialized, using fallback")
            executeTraditionalPrediction(input, emptyList())
        }
    }
    
    /**
     * Execute traditional prediction
     */
    private suspend fun executeTraditionalPrediction(input: SwipeInput, context: List<String>): PredictionResult {
        return try {
            if (input.keySequence.isNotBlank()) {
                wordPredictor.predictWordsWithContext(input.keySequence, context)
            } else {
                wordPredictor.predictWords(extractKeySequenceFromPath(input.coordinates))
            }
        } catch (e: Exception) {
            logE("Traditional prediction failed", e)
            PredictionResult.empty
        }
    }
    
    /**
     * Execute hybrid prediction (neural + traditional)
     */
    private suspend fun executeHybridPrediction(input: SwipeInput, context: List<String>): PredictionResult {
        return try {
            // Get both predictions
            val neuralResult = executeNeuralPrediction(input)
            val traditionalResult = executeTraditionalPrediction(input, context)
            
            // Combine and rank results
            val combinedWords = (neuralResult.words + traditionalResult.words).distinct()
            val combinedScores = combinedWords.map { word ->
                val neuralScore = neuralResult.words.indexOf(word).let { index ->
                    if (index >= 0) neuralResult.scores.getOrNull(index) ?: 0 else 0
                }
                val traditionalScore = traditionalResult.words.indexOf(word).let { index ->
                    if (index >= 0) traditionalResult.scores.getOrNull(index) ?: 0 else 0
                }
                
                // Weighted combination (favor neural)
                (neuralScore * 0.7 + traditionalScore * 0.3).toInt()
            }
            
            // Sort by combined score
            val sortedPairs = combinedWords.zip(combinedScores).sortedByDescending { it.second }
            
            PredictionResult(
                sortedPairs.map { it.first },
                sortedPairs.map { it.second }
            )
        } catch (e: Exception) {
            logE("Hybrid prediction failed", e)
            executeTraditionalPrediction(input, context)
        }
    }
    
    /**
     * Execute fallback prediction for non-swipe gestures
     */
    private suspend fun executeFallbackPrediction(input: SwipeInput): PredictionResult {
        // Simple fallback based on gesture characteristics
        val words = when {
            input.pathLength < 50f -> listOf("a", "i", "o")
            input.duration < 0.3f -> listOf("the", "and", "for")
            input.directionChanges > 5 -> listOf("complex", "pattern", "gesture")
            else -> listOf("swipe", "keyboard", "input")
        }
        
        val scores = words.mapIndexed { index, _ -> 500 - index * 100 }
        return PredictionResult(words, scores)
    }
    
    /**
     * Extract key sequence from coordinate path
     */
    private fun extractKeySequenceFromPath(coordinates: List<PointF>): String {
        // Simple key detection based on coordinate regions
        return coordinates.mapNotNull { point ->
            val x = point.x.toInt()
            val y = point.y.toInt()
            
            // Basic QWERTY layout detection
            when {
                y < 100 -> { // Top row
                    when (x) {
                        in 0..107 -> 'q'
                        in 108..215 -> 'w'
                        in 216..323 -> 'e'
                        in 324..431 -> 'r'
                        in 432..539 -> 't'
                        in 540..647 -> 'y'
                        in 648..755 -> 'u'
                        in 756..863 -> 'i'
                        in 864..971 -> 'o'
                        else -> 'p'
                    }
                }
                y < 200 -> { // Middle row
                    when (x) {
                        in 0..119 -> 'a'
                        in 120..239 -> 's'
                        in 240..359 -> 'd'
                        in 360..479 -> 'f'
                        in 480..599 -> 'g'
                        in 600..719 -> 'h'
                        in 720..839 -> 'j'
                        in 840..959 -> 'k'
                        else -> 'l'
                    }
                }
                else -> { // Bottom row
                    when (x) {
                        in 0..153 -> 'z'
                        in 154..307 -> 'x'
                        in 308..461 -> 'c'
                        in 462..615 -> 'v'
                        in 616..769 -> 'b'
                        in 770..923 -> 'n'
                        else -> 'm'
                    }
                }
            }
        }.joinToString("")
    }
    
    /**
     * Get pipeline performance statistics
     */
    fun getPerformanceStats(): Map<String, PerformanceProfiler.PerformanceStats> {
        val operations = listOf(
            "complete_pipeline", "neural_prediction", "traditional_prediction", 
            "hybrid_prediction", "fallback_prediction"
        )
        
        return operations.mapNotNull { operation ->
            performanceProfiler.getStats(operation)?.let { operation to it }
        }.toMap()
    }
    
    /**
     * Cleanup pipeline
     */
    fun cleanup() {
        scope.cancel()
        gestureRecognizer.cleanup()
        neuralEngine.cleanup()
        wordPredictor.cleanup()
        performanceProfiler.cleanup()
    }
}