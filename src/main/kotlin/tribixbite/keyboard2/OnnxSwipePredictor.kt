package tribixbite.keyboard2

import android.content.Context
import android.graphics.PointF

/**
 * ONNX Swipe Predictor - Kotlin stub for neural prediction
 * This maintains the interface but implementation would need full ONNX integration
 */
class OnnxSwipePredictor private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "OnnxSwipePredictor"
        private var instance: OnnxSwipePredictor? = null

        fun getInstance(context: Context): OnnxSwipePredictor {
            return instance ?: synchronized(this) {
                instance ?: OnnxSwipePredictor(context).also { instance = it }
            }
        }
    }

    var isModelLoaded = false
        private set
    
    /**
     * Initialize ONNX models
     */
    suspend fun initialize(): Boolean {
        return realPredictor.initialize().also {
            isModelLoaded = it
        }
    }
    
    /**
     * Predict words from swipe input
     */
    suspend fun predict(input: SwipeInput): PredictionResult {
        return realPredictor.predict(input)
    }
    
    // Delegate to real implementation
    private val realPredictor by lazy { OnnxSwipePredictorImpl.getInstance(context) }

    /**
     * Set configuration from NeuralConfig
     */
    fun setConfig(neuralConfig: NeuralConfig) {
        realPredictor.setConfig(neuralConfig)
    }

    /**
     * Set configuration from Config (fallback)
     */
    fun setConfig(config: Config) {
        realPredictor.setConfig(config)
    }
    
    /**
     * Set keyboard dimensions
     */
    fun setKeyboardDimensions(width: Int, height: Int) {
        realPredictor.setKeyboardDimensions(width, height)
    }
    
    /**
     * Set real key positions
     */
    fun setRealKeyPositions(keyPositions: Map<Char, PointF>) {
        realPredictor.setRealKeyPositions(keyPositions)
    }
    
    /**
     * Set debug logger
     */
    fun setDebugLogger(logger: ((String) -> Unit)?) {
        realPredictor.setDebugLogger(logger)
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        realPredictor.cleanup()
        isModelLoaded = false
    }
}