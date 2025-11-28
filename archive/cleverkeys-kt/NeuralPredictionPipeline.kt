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
    private val predictionCache = PredictionCache(maxSize = 20)

    // Pipeline state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isInitialized = false

    // Cache hit tracking
    private var cacheHits = 0
    private var cacheMisses = 0
    
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
     * Execute ONNX neural prediction with caching
     */
    private suspend fun executeOnnxNeuralPrediction(input: SwipeInput): PredictionResult {
        // Validate input for neural processing
        val validation = ErrorHandling.Validation.validateSwipeInput(input)
        validation.throwIfInvalid()

        if (!isInitialized) {
            throw ErrorHandling.CleverKeysException.NeuralEngineException("Neural engine not initialized")
        }

        // Check cache first
        val cachedResult = predictionCache.get(input.coordinates)
        if (cachedResult != null) {
            cacheHits++
            logD("Cache hit! ($cacheHits hits / $cacheMisses misses)")
            return cachedResult
        }

        // Cache miss - run ONNX inference
        cacheMisses++
        val result = neuralEngine.predictAsync(input)

        // Store in cache for future use
        predictionCache.put(input.coordinates, result)

        return result
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
     * Get cache statistics
     */
    fun getCacheStats(): String {
        val cacheStats = predictionCache.getStats()
        val hitRate = if ((cacheHits + cacheMisses) > 0) {
            (cacheHits * 100.0 / (cacheHits + cacheMisses))
        } else 0.0

        return "Cache: ${cacheStats.size}/${cacheStats.maxSize} entries, " +
               "Hit rate: %.1f%% ($cacheHits hits / $cacheMisses misses)".format(hitRate)
    }

    /**
     * Clear prediction cache
     */
    fun clearCache() {
        predictionCache.clear()
        cacheHits = 0
        cacheMisses = 0
    }

    /**
     * Cleanup pipeline - ONNX only.
     * Should be called from a coroutine context during shutdown.
     */
    suspend fun cleanup() {
        neuralEngine.cleanup()
        performanceProfiler.cleanup()
        predictionCache.clear()
        scope.cancel()
    }
}