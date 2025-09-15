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
    
    // Pipeline components - ONNX-only neural prediction (no CGR)
    private val neuralEngine = NeuralSwipeEngine(context, Config.globalConfig())
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
     * Prediction source type - ONNX neural only, no fallbacks
     */
    enum class PredictionSource { NEURAL }
    
    /**
     * Initialize complete pipeline
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        return@withContext ErrorHandling.safeExecute("Pipeline initialization") {
            // Initialize ONNX neural engine only
            val neuralInit = neuralEngine.initialize()

            isInitialized = neuralInit

            if (isInitialized) {
                logD("ONNX neural prediction pipeline initialized successfully")
            } else {
                logE("ONNX pipeline initialization failed")
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
        
        return@withContext performanceProfiler.measureOperation("onnx_neural_pipeline") {
            // Create SwipeInput for ONNX processing
            val swipeInput = SwipeInput(points, timestamps, emptyList())

            // ONNX-only prediction - no CGR, no traditional methods, no fallbacks
            val predictions = performanceProfiler.measureOperation("onnx_neural_prediction") {
                executeOnnxNeuralPrediction(swipeInput)
            }

            // ONNX-only result
            PipelineResult(
                predictions = predictions,
                gestureInfo = createBasicGestureInfo(swipeInput),
                swipeClassification = createBasicSwipeClassification(swipeInput),
                processingTimeMs = 0L, // Will be filled by measureOperation
                source = PredictionSource.NEURAL
            )
        }
    }
    
    /**
     * Execute ONNX neural prediction only
     */
    private suspend fun executeOnnxNeuralPrediction(input: SwipeInput): PredictionResult {
        // Validate input for neural processing
        val validation = ErrorHandling.Validation.validateSwipeInput(input)
        validation.throwIfInvalid()

        return if (isInitialized) {
            neuralEngine.predictAsync(input)
        } else {
            throw ErrorHandling.CleverKeysException.NeuralEngineException("Neural engine not initialized")
        }
    }
    
    /**
     * Create basic gesture info for ONNX pipeline
     */
    private fun createBasicGestureInfo(input: SwipeInput): SwipeGestureRecognizer.RecognizedGesture {
        return SwipeGestureRecognizer.RecognizedGesture(
            type = SwipeGestureRecognizer.GestureType.SWIPE_HORIZONTAL, // Simplified for ONNX-only
            direction = 0f,
            distance = input.pathLength,
            duration = input.duration,
            confidence = input.swipeConfidence,
            points = input.coordinates
        )
    }

    /**
     * Create basic swipe classification for ONNX pipeline
     */
    private fun createBasicSwipeClassification(input: SwipeInput): SwipeDetector.SwipeClassification {
        return SwipeDetector.SwipeClassification(
            isSwipe = input.pathLength > 50f && input.duration > 0.1f,
            confidence = input.swipeConfidence,
            reason = "ONNX neural processing",
            quality = if (input.swipeConfidence > 0.7f) SwipeDetector.SwipeQuality.EXCELLENT
                     else if (input.swipeConfidence > 0.5f) SwipeDetector.SwipeQuality.GOOD
                     else SwipeDetector.SwipeQuality.FAIR
        )
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