package tribixbite.keyboard2

import android.graphics.PointF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * LEGACY: Continuous Swipe Gesture Recognizer Integration
 *
 * ⚠️ DEPRECATED: This class uses the OLD CGR (Continuous Gesture Recognizer) library
 * which has been replaced with pure ONNX neural prediction. It is kept for reference
 * only and is NOT used in the active codebase.
 *
 * Use instead:
 * - neural/OnnxSwipePredictorImpl.kt for predictions
 * - SwipeDetector.kt for gesture detection
 *
 * Based on Main.lua usage patterns, this class integrates the CGR library
 * with Android touch handling for swipe typing recognition.
 *
 * Features:
 * - Background thread processing for non-blocking recognition
 * - Prediction throttling to prevent UI lag
 * - Real-time gesture callbacks
 * - Result confidence checking
 * - Template management
 * - Thread-safe operations
 *
 * Performance Optimizations:
 * - Uses HandlerThread for background processing
 * - Throttles predictions to 100ms intervals
 * - Pre-allocated result lists
 * - Atomic flags for thread synchronization
 *
 * Usage:
 * ```kotlin
 * val recognizer = ContinuousSwipeGestureRecognizer()
 * recognizer.setOnGesturePredictionListener(object : OnGesturePredictionListener {
 *     override fun onGesturePrediction(predictions: List<Result>) { }
 *     override fun onGestureComplete(finalPredictions: List<Result>) { }
 *     override fun onGestureCleared() { }
 * })
 * recognizer.setTemplateSet(wordTemplates)
 *
 * // In touch handler:
 * recognizer.onTouchBegan(x, y)
 * recognizer.onTouchMoved(x, y)
 * recognizer.onTouchEnded(x, y)
 * ```
 *
 * Ported from Java to Kotlin with improvements.
 */
@Deprecated(
    message = "CGR library replaced with ONNX neural prediction. Use neural/OnnxSwipePredictorImpl.kt instead.",
    level = DeprecationLevel.WARNING
)
class ContinuousSwipeGestureRecognizer {

    companion object {
        private const val TAG = "ContinuousSwipeGestureRecognizer"

        /** Minimum prediction frequency in milliseconds */
        private const val PREDICTION_THROTTLE_MS = 100L
    }

    /**
     * Callback interface for real-time predictions
     */
    interface OnGesturePredictionListener {
        /**
         * Called during gesture for real-time predictions (currently disabled for performance)
         */
        fun onGesturePrediction(predictions: List<ContinuousGestureRecognizer.Result>)

        /**
         * Called when gesture completes with final predictions
         */
        fun onGestureComplete(finalPredictions: List<ContinuousGestureRecognizer.Result>)

        /**
         * Called when gesture is cleared
         */
        fun onGestureCleared()
    }

    private val cgr = ContinuousGestureRecognizer()
    private val gesturePointsList = mutableListOf<ContinuousGestureRecognizer.Point>()
    private val results = mutableListOf<ContinuousGestureRecognizer.Result>() // Pre-allocated results list
    private var newTouch = false
    private var gestureActive = false
    private var minPointsForPrediction = 4 // Start predictions after 4 points (lowered for short swipes)

    // Performance optimization fields
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognitionInProgress = AtomicBoolean(false)
    private var lastPredictionTime = 0L

    private var predictionListener: OnGesturePredictionListener? = null

    init {
        // Initialize background processing
        backgroundThread = HandlerThread("CGR-Recognition").apply {
            start()
        }
        backgroundHandler = backgroundThread?.looper?.let { Handler(it) }

        // Don't initialize with directional templates - they cause issues with FIXED_POINT_COUNT
        // Templates will be set later when word templates are loaded
    }

    /**
     * Set the prediction listener for real-time callbacks
     */
    fun setOnGesturePredictionListener(listener: OnGesturePredictionListener) {
        predictionListener = listener
    }

    /**
     * Set template set for recognition
     */
    fun setTemplateSet(templates: List<ContinuousGestureRecognizer.Template>) {
        cgr.setTemplateSet(templates)
    }

    /**
     * Handle touch begin event (equivalent to CurrentTouch.state == BEGAN)
     */
    fun onTouchBegan(x: Float, y: Float) {
        gesturePointsList.clear()
        gesturePointsList.add(ContinuousGestureRecognizer.Point(x.toDouble(), y.toDouble()))
        newTouch = true
        gestureActive = true

        // Clear any existing predictions
        predictionListener?.onGestureCleared()
    }

    /**
     * Handle touch move event (equivalent to CurrentTouch.state == MOVING)
     *
     * OPTIMIZED: Uses throttling and background processing to prevent UI lag
     */
    fun onTouchMoved(x: Float, y: Float) {
        if (!gestureActive) return

        gesturePointsList.add(ContinuousGestureRecognizer.Point(x.toDouble(), y.toDouble()))

        // DISABLED: Real-time predictions during swipe (causes performance issues)
        // Only predict at swipe completion to prevent memory/performance overhead

        Log.d(TAG, "Touch move recorded (real-time prediction disabled for performance)")
    }

    /**
     * Handle touch end event (equivalent to CurrentTouch.state == ENDED)
     *
     * OPTIMIZED: Uses background processing for final recognition
     */
    fun onTouchEnded(x: Float, y: Float) {
        if (!gestureActive) return

        gesturePointsList.add(ContinuousGestureRecognizer.Point(x.toDouble(), y.toDouble()))

        if (newTouch) {
            newTouch = false

            // ALWAYS perform final recognition on background thread - guarantee prediction
            if (gesturePointsList.size >= 2) { // Need at least 2 points for recognition
                val finalPointsCopy = ArrayList(gesturePointsList)

                // Clear any pending background tasks to prioritize final results
                backgroundHandler?.removeCallbacksAndMessages(null)

                backgroundHandler?.post {
                    try {
                        val finalResults = cgr.recognize(finalPointsCopy)

                        // ALWAYS notify with results (even if empty) to guarantee callback
                        mainHandler.post {
                            // Store results for persistence
                            results.clear()
                            if (finalResults != null) {
                                results.addAll(finalResults)
                            }

                            // ALWAYS notify listener - guarantee prediction shown after swipe
                            val listener = predictionListener
                            if (listener != null) {
                                if (finalResults != null && finalResults.isNotEmpty()) {
                                    listener.onGestureComplete(finalResults)
                                    Log.d(TAG, "Final prediction delivered: ${finalResults.size} results")
                                } else {
                                    // Even if no good results, still notify (may show fallback)
                                    listener.onGestureComplete(emptyList())
                                    Log.d(TAG, "No final predictions available")
                                }
                            }

                            // Debug logging (like CGR_printResults in Lua)
                            if (finalResults != null && finalResults.isNotEmpty()) {
                                printResults(finalResults)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Recognition error on end: ${e.message}")
                        // Still notify listener even on error to guarantee callback
                        mainHandler.post {
                            predictionListener?.onGestureComplete(emptyList())
                        }
                    }
                }
            }
        }

        gestureActive = false
    }

    /**
     * Check if gesture is currently active
     */
    fun isGestureActive(): Boolean = gestureActive

    /**
     * Get current gesture points for visualization
     */
    fun getCurrentGesturePoints(): List<PointF> {
        return gesturePointsList.map { PointF(it.x.toFloat(), it.y.toFloat()) }
    }

    /**
     * Get the last recognition results (for persistence)
     */
    fun getLastResults(): List<ContinuousGestureRecognizer.Result> {
        return ArrayList(results)
    }

    /**
     * Get the best prediction from last results
     */
    fun getBestPrediction(): ContinuousGestureRecognizer.Result? {
        return results.firstOrNull() // Results are sorted by probability
    }

    /**
     * Clear stored results (called on space/punctuation)
     */
    fun clearResults() {
        results.clear()
        predictionListener?.onGestureCleared()
    }

    /**
     * Set minimum points required before starting predictions
     */
    fun setMinPointsForPrediction(minPoints: Int) {
        minPointsForPrediction = max(2, minPoints)
    }

    /**
     * Print results for debugging (equivalent to CGR_printResults in Lua)
     */
    private fun printResults(resultList: List<ContinuousGestureRecognizer.Result>) {
        for (result in resultList) {
            Log.d(TAG, "Result: ${result.template.id} : ${result.prob}")
        }
    }

    /**
     * Check results quality (equivalent to CGR_checkResults in Lua).
     * Returns true if the best result is confident enough.
     */
    fun isResultConfident(): Boolean {
        if (results.size < 2) return false

        val r1 = results[0]
        val r2 = results[1]

        val similarity = (r2.prob / r1.prob) * r2.prob

        return when {
            r1.prob > 0.7 -> {
                if (similarity < 95) {
                    Log.d(TAG, "CHECK: Using: ${r1.template.id} : ${r1.prob}")
                    true
                } else {
                    Log.d(TAG, "CHECK: First two probabilities too close to call")
                    false
                }
            }
            else -> {
                Log.d(TAG, "CHECK: Probability not high enough (<0.7), discarding user input")
                false
            }
        }
    }

    /**
     * Reset the recognizer state
     */
    fun reset() {
        gesturePointsList.clear()
        results.clear()
        gestureActive = false
        newTouch = false
        lastPredictionTime = 0

        predictionListener?.onGestureCleared()
    }

    /**
     * Clean up background thread (call when done with recognizer)
     */
    fun cleanup() {
        backgroundThread?.let { thread ->
            thread.quitSafely()
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            backgroundThread = null
            backgroundHandler = null
        }
    }
}
