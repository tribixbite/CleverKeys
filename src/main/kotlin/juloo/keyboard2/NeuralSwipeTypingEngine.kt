package juloo.keyboard2

import android.content.Context
import android.graphics.PointF
import kotlinx.coroutines.*

/**
 * Neural swipe typing engine - Kotlin implementation
 * Maintains full functionality of Java version with modern patterns
 */
class NeuralSwipeTypingEngine(
    private val context: Context,
    private val config: Config
) {
    
    companion object {
        private const val TAG = "NeuralSwipeTypingEngine"
    }
    
    private var neuralPredictor: OnnxSwipePredictor? = null
    private var initialized = false
    private var debugLogger: ((String) -> Unit)? = null
    
    /**
     * Initialize the neural prediction system
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            logD("Initializing neural prediction system...")
            
            neuralPredictor = OnnxSwipePredictor.getInstance(context)
            val success = neuralPredictor?.initialize() ?: false
            
            if (success) {
                neuralPredictor?.setDebugLogger(debugLogger)
                setConfig(config)
                initialized = true
                logD("Neural engine initialized successfully")
            } else {
                logE("Failed to initialize ONNX predictor")
            }
            
            success
        } catch (e: Exception) {
            logE("Neural engine initialization failed", e)
            false
        }
    }
    
    /**
     * Predict words from swipe input
     */
    fun predict(input: SwipeInput): PredictionResult {
        if (!initialized) {
            runBlocking { initialize() }
        }
        
        val predictor = neuralPredictor ?: return PredictionResult.empty
        
        logD("=== NEURAL PREDICTION START ===")
        logD("Input: keySeq=${input.keySequence}, pathLen=${input.pathLength}, duration=${input.duration}")
        
        return try {
            predictor.predict(input)
        } catch (e: Exception) {
            logE("Neural prediction failed", e)
            debugLogger?.invoke("💥 Prediction failed: ${e.message}")
            PredictionResult.empty
        }
    }
    
    /**
     * Async prediction
     */
    suspend fun predictAsync(input: SwipeInput): PredictionResult = withContext(Dispatchers.Default) {
        predict(input)
    }
    
    /**
     * Update configuration
     */
    fun setConfig(newConfig: Config) {
        neuralPredictor?.setConfig(newConfig)
    }
    
    /**
     * Set keyboard dimensions
     */
    fun setKeyboardDimensions(width: Int, height: Int) {
        neuralPredictor?.setKeyboardDimensions(width, height)
    }
    
    /**
     * Set real key positions
     */
    fun setRealKeyPositions(keyPositions: Map<Char, PointF>) {
        neuralPredictor?.setRealKeyPositions(keyPositions)
    }
    
    /**
     * Set debug logger
     */
    fun setDebugLogger(logger: ((String) -> Unit)?) {
        debugLogger = logger
        neuralPredictor?.setDebugLogger(logger)
    }
    
    /**
     * Check if ready
     */
    val isReady: Boolean get() = initialized && neuralPredictor != null
    
    /**
     * Cleanup
     */
    fun cleanup() {
        neuralPredictor?.cleanup()
        neuralPredictor = null
        initialized = false
    }
}