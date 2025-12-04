package tribixbite.cleverkeys

import android.content.Context
import android.graphics.PointF
import android.util.Log
import tribixbite.cleverkeys.onnx.PredictionPostProcessor
import tribixbite.cleverkeys.onnx.SwipePredictorOrchestrator

/**
 * Neural swipe typing engine using ONNX transformer models
 * Replaces SwipeTypingEngine with neural prediction pipeline
 * Maintains same interface for seamless integration
 */
class NeuralSwipeTypingEngine(
    private val context: Context,
    private var config: Config
) {
    fun interface DebugLogger {
        fun log(message: String)
    }

    companion object {
        private const val TAG = "NeuralSwipeTypingEngine"
        // Build-time flag for verbose logging - set to false for production
        private const val VERBOSE_LOGGING = false
    }

    private val neuralPredictor: SwipePredictorOrchestrator

    // State tracking
    private var initialized = false

    // Debug logging callback
    private var debugLogger: DebugLogger? = null

    // Runtime debug logging control (from settings)
    private var verboseLoggingEnabled = false

    init {
        // OPTIMIZATION: Use singleton predictor with session persistence
        neuralPredictor = SwipePredictorOrchestrator.getInstance(context)

        // OPTIMIZATION: Start async model loading immediately for faster startup
        // Models will load in background while keyboard UI appears
        neuralPredictor.initializeAsync()

        Log.d(TAG, "NeuralSwipeTypingEngine created - using persistent singleton predictor")
    }

    /**
     * Initialize the engine and load models
     */
    fun initialize(): Boolean {
        if (initialized) {
            return true
        }

        return try {
            Log.d(TAG, "Initializing pure neural swipe engine...")

            // Propagate debug logger to predictor
            debugLogger?.let {
                neuralPredictor.setDebugLogger(it)
            }

            // Initialize neural predictor - MUST succeed or throw error
            // Note: Singleton may already be initialized, which is optimal for performance
            val neuralReady = neuralPredictor.initialize()

            if (!neuralReady) {
                throw RuntimeException("Failed to initialize ONNX neural models")
            }

            initialized = true

            Log.d(TAG, "Neural engine initialized successfully - pure neural mode")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize neural engine", e)
            // Pure neural - no fallback variables needed
            initialized = true
            false
        }
    }

    /**
     * Main prediction method - maintains compatibility with legacy interface
     *
     * PERFORMANCE: Debug logging is gated behind verboseLoggingEnabled to avoid
     * latency impact in production. Stack trace logging only runs when VERBOSE_LOGGING
     * build flag is true (disabled by default).
     */
    fun predict(input: SwipeInput): PredictionResult {
        // Stack trace logging for debugging - disabled in production builds
        if (VERBOSE_LOGGING && verboseLoggingEnabled) {
            Log.d(TAG, "🔥🔥🔥 NEURAL PREDICTION CALLED FROM:")
            for (element in Thread.currentThread().stackTrace) {
                if (element.className.contains("tribixbite.cleverkeys")) {
                    Log.d(TAG, "🔥   ${element.className}.${element.methodName}:${element.lineNumber}")
                }
            }
        }

        if (!initialized) {
            initialize()
        }

        // Verbose logging only when debug mode is enabled in settings
        if (verboseLoggingEnabled) {
            Log.d(TAG, "=== PURE NEURAL PREDICTION START ===")
            Log.d(TAG, "Input: keySeq=${input.keySequence}, pathLen=${"%.1f".format(input.pathLength)}, " +
                "duration=${"%.2f".format(input.duration)}s")
            Log.d(TAG, "Using PURE NEURAL prediction - no classification needed")
        }

        return try {
            val result = neuralPredictor.predict(input)

            if (result != null) {
                val words = result.words
                val scores = result.scores

                if (verboseLoggingEnabled) {
                    Log.d(TAG, "Neural prediction successful: ${words.size} candidates")
                }
                PredictionResult(words, scores)
            } else {
                throw RuntimeException("Neural prediction returned null result")
            }
        } catch (e: Exception) {
            // Error logging is always enabled - errors should always be visible
            Log.e(TAG, "Neural prediction failed", e)
            throw RuntimeException("Neural prediction failed: ${e.message}")
        }
    }

    /**
     * Set keyboard dimensions for coordinate mapping
     */
    fun setKeyboardDimensions(width: Float, height: Float) {
        neuralPredictor.setKeyboardDimensions(width, height)
        Log.d(TAG, "Set keyboard dimensions: ${"%.0f".format(width)}x${"%.0f".format(height)}")
    }

    /**
     * Set QWERTY area bounds for proper coordinate normalization.
     * This is critical for correct key detection - the model expects coordinates
     * normalized over just the QWERTY key area, not the full keyboard view.
     *
     * @param qwertyTop Y offset in pixels where QWERTY keys start
     * @param qwertyHeight Height in pixels of the QWERTY key area
     */
    fun setQwertyAreaBounds(qwertyTop: Float, qwertyHeight: Float) {
        neuralPredictor.setQwertyAreaBounds(qwertyTop, qwertyHeight)
        Log.d(TAG, "Set QWERTY area bounds: top=${"%.0f".format(qwertyTop)}, " +
            "height=${"%.0f".format(qwertyHeight)}")
    }

    /**
     * Set touch Y-offset compensation for fat finger effect.
     * Users typically touch above key centers; this offset compensates.
     *
     * @param offset Pixels to add to Y coordinate (positive = shift down)
     */
    fun setTouchYOffset(offset: Float) {
        neuralPredictor.setTouchYOffset(offset)
        Log.d(TAG, "Set touch Y-offset: ${"%.0f".format(offset)} pixels")
    }

    /**
     * Set real key positions for accurate coordinate mapping
     */
    fun setRealKeyPositions(realPositions: Map<Char, PointF>?) {
        neuralPredictor.setRealKeyPositions(realPositions)
        Log.d(TAG, "Set key positions: ${realPositions?.size ?: 0} keys")
    }

    /**
     * Update configuration
     */
    fun setConfig(config: Config) {
        this.config = config

        // Update verbose logging from settings
        verboseLoggingEnabled = config.swipe_debug_detailed_logging

        // Update neural predictor configuration
        neuralPredictor.setConfig(config)

        if (verboseLoggingEnabled) {
            Log.d(TAG, "Neural config updated (verbose logging: enabled)")
        }
    }

    /**
     * Check if neural prediction is available
     */
    fun isNeuralAvailable(): Boolean {
        return neuralPredictor.isAvailable()
    }

    /**
     * Get current prediction mode
     */
    fun getCurrentMode(): String {
        return if (isNeuralAvailable()) "neural" else "error"
    }

    /**
     * Set debug logger for detailed logging
     */
    fun setDebugLogger(logger: DebugLogger?) {
        debugLogger = logger
        debugLogger?.let {
            neuralPredictor.setDebugLogger(it)
        }
    }

    private fun logDebug(message: String) {
        debugLogger?.log(message)
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        neuralPredictor.cleanup()

        Log.d(TAG, "Neural swipe engine cleaned up")
    }
}
