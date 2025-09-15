package tribixbite.keyboard2

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
     * Cleanup pipeline - ONNX only
     */
    fun cleanup() {
        scope.cancel()
        neuralEngine.cleanup()
        performanceProfiler.cleanup()
    }
}