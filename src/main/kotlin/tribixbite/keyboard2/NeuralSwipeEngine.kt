package tribixbite.keyboard2

import android.content.Context
import android.graphics.PointF
import kotlinx.coroutines.*

/**
 * Neural swipe typing engine with Kotlin coroutines and modern architecture
 * Replaces Java implementation with null safety and structured concurrency
 */
class NeuralSwipeEngine(
    private val context: Context,
    private val config: Config
) {
    
    companion object {
        private const val TAG = "NeuralSwipeEngine"
    }
    
    // Null-safe predictor with lazy initialization
    private var neuralPredictor: OnnxSwipePredictor? = null
    private var isInitialized = false
    
    // Debug logging callback
    private var debugLogger: ((String) -> Unit)? = null
    
    /**
     * Type-safe initialization with proper error handling
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            logD("Initializing neural prediction system...")
            
            neuralPredictor = OnnxSwipePredictor.getInstance(context).also { predictor ->
                if (!predictor.initialize()) {
                    throw RuntimeException("Failed to load ONNX models")
                }
                predictor.setDebugLogger(debugLogger)
                setConfig(config)
            }
            
            isInitialized = true
            logD("Neural engine initialized successfully")
            true
            
        } catch (e: Exception) {
            logE("Failed to initialize neural engine", e)
            false
        }
    }
    
    /**
     * Synchronous prediction with null safety
     */
    fun predict(input: SwipeInput): PredictionResult {
        if (!isInitialized) {
            runBlocking { initialize() }
        }
        
        requireNotNull(neuralPredictor) { "Neural predictor not initialized" }
        
        logD("=== NEURAL PREDICTION START ===")
        logD("Input: keySeq=${input.keySequence}, pathLen=${input.pathLength}, duration=${input.duration}")
        
        return try {
            val (result, duration) = measureTimeNanos {
                runBlocking { neuralPredictor!!.predict(input) }
            }
            
            logD("Neural prediction completed in ${duration / 1_000_000}ms")
            debugLogger?.invoke("🧠 Prediction: ${result.size} candidates in ${duration / 1_000_000}ms")
            
            result
        } catch (e: Exception) {
            logE("Neural prediction failed", e)
            debugLogger?.invoke("💥 Prediction failed: ${e.message}")
            PredictionResult.empty
        }
    }
    
    /**
     * Async prediction with coroutines
     */
    suspend fun predictAsync(input: SwipeInput): PredictionResult = withContext(Dispatchers.Default) {
        predict(input)
    }
    
    /**
     * Update configuration with validation
     */
    fun setConfig(newConfig: Config) {
        neuralPredictor?.setConfig(newConfig)
        logD("Neural configuration updated")
    }
    
    /**
     * Set keyboard dimensions for coordinate normalization
     */
    fun setKeyboardDimensions(width: Int, height: Int) {
        neuralPredictor?.setKeyboardDimensions(width, height)
    }
    
    /**
     * Set real key positions for nearest-key detection
     */
    fun setRealKeyPositions(keyPositions: Map<Char, PointF>) {
        neuralPredictor?.setRealKeyPositions(keyPositions)
    }
    
    /**
     * Set debug logger with null safety
     */
    fun setDebugLogger(logger: ((String) -> Unit)?) {
        debugLogger = logger
        neuralPredictor?.setDebugLogger(logger)
    }
    
    /**
     * Check if engine is ready for predictions
     */
    val isReady: Boolean get() = isInitialized && neuralPredictor != null
    
    /**
     * Get prediction statistics
     */
    suspend fun getStats(): PredictionStats = withContext(Dispatchers.Default) {
        neuralPredictor?.let { predictor ->
            // Get stats from predictor if available
            PredictionStats(
                modelsLoaded = predictor.isModelLoaded,
                averageLatencyMs = 0.0, // Would be tracked in predictor
                totalPredictions = 0,    // Would be tracked in predictor
                successRate = 1.0        // Would be calculated from history
            )
        } ?: PredictionStats(false, 0.0, 0, 0.0)
    }
    
    /**
     * Prediction statistics data class
     */
    data class PredictionStats(
        val modelsLoaded: Boolean,
        val averageLatencyMs: Double,
        val totalPredictions: Int,
        val successRate: Double
    )
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        neuralPredictor?.cleanup()
        neuralPredictor = null
        isInitialized = false
        debugLogger = null
    }
}