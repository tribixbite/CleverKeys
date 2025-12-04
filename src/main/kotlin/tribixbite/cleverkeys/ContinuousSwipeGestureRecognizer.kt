package tribixbite.cleverkeys

import android.graphics.PointF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Continuous Swipe Gesture Recognizer Integration
 *
 * Based on Main.lua usage patterns, this class integrates the CGR library
 * with Android touch handling for swipe typing recognition.
 *
 * PERFORMANCE OPTIMIZED: Uses background thread and throttling to prevent UI lag
 */
class ContinuousSwipeGestureRecognizer {
    private val cgr: ContinuousGestureRecognizer = ContinuousGestureRecognizer()
    private val gesturePointsList: MutableList<ContinuousGestureRecognizer.Point> = mutableListOf()
    private val results: MutableList<ContinuousGestureRecognizer.Result> = mutableListOf()
    private var newTouch = false
    private var gestureActive = false
    private var minPointsForPrediction = 4 // Start predictions after 4 points (lowered for short swipes)

    // Performance optimization fields
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var mainHandler: Handler? = null
    private val recognitionInProgress = AtomicBoolean(false)
    private var lastPredictionTime = 0L

    // Callback interface for real-time predictions
    fun interface OnGesturePredictionListener {
        fun onGesturePrediction(predictions: List<ContinuousGestureRecognizer.Result>)

        fun onGestureComplete(finalPredictions: List<ContinuousGestureRecognizer.Result>) {
            onGesturePrediction(finalPredictions)
        }

        fun onGestureCleared() {}
    }

    private var predictionListener: OnGesturePredictionListener? = null

    init {
        // Initialize background processing
        backgroundThread = HandlerThread("CGR-Recognition").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        mainHandler = Handler(Looper.getMainLooper())

        // Don't initialize with directional templates - they cause issues with FIXED_POINT_COUNT
        // Templates will be set later when word templates are loaded
        // cgr.setTemplateSet(ContinuousGestureRecognizer.createDirectionalTemplates())
    }

    /**
     * Set the prediction listener for real-time callbacks
     */
    fun setOnGesturePredictionListener(listener: OnGesturePredictionListener?) {
        this.predictionListener = listener
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
     * OPTIMIZED: Uses throttling and background processing to prevent UI lag
     */
    fun onTouchMoved(x: Float, y: Float) {
        if (!gestureActive) return

        gesturePointsList.add(ContinuousGestureRecognizer.Point(x.toDouble(), y.toDouble()))

        // Throttle predictions to reasonable frequency (record all events but predict sparingly)
        val now = System.currentTimeMillis()

        // DISABLED: Real-time predictions during swipe (causes performance issues)
        // Only predict at swipe completion to prevent memory/performance overhead

        // COMMENTED OUT FOR PERFORMANCE:
        // val shouldPredict = gesturePointsList.size >= minPointsForPrediction &&
        //     now - lastPredictionTime > PREDICTION_THROTTLE_MS
        //
        // if (shouldPredict) {
        //   lastPredictionTime = now
        //
        //   // Create copy of points for background processing
        //   val pointsCopy = gesturePointsList.toList()
        //
        //   // Run recognition on background thread
        //   backgroundHandler?.post {
        //     try {
        //       val currentResults = cgr.recognize(pointsCopy)
        //
        //       // Post results back to main thread
        //       if (!currentResults.isNullOrEmpty() && predictionListener != null) {
        //         mainHandler?.post {
        //           predictionListener?.onGesturePrediction(currentResults)
        //         }
        //       }
        //     } catch (e: Exception) {
        //       Log.w(TAG, "Recognition error during move: ${e.message}")
        //     }
        //   }
        // }

        Log.d(TAG, "Touch move recorded (real-time prediction disabled for performance)")
    }

    /**
     * Handle touch end event (equivalent to CurrentTouch.state == ENDED)
     * OPTIMIZED: Uses background processing for final recognition
     */
    fun onTouchEnded(x: Float, y: Float) {
        if (!gestureActive) return

        gesturePointsList.add(ContinuousGestureRecognizer.Point(x.toDouble(), y.toDouble()))

        if (newTouch) {
            newTouch = false

            // ALWAYS perform final recognition on background thread - guarantee prediction
            if (gesturePointsList.size >= 2) { // Need at least 2 points for recognition
                val finalPointsCopy = gesturePointsList.toList()

                // Clear any pending background tasks to prioritize final results
                backgroundHandler?.removeCallbacksAndMessages(null)

                backgroundHandler?.post {
                    try {
                        val finalResults = cgr.recognize(finalPointsCopy)

                        // ALWAYS notify with results (even if empty) to guarantee callback
                        mainHandler?.post {
                            // Store results for persistence
                            results.clear()
                            finalResults?.let { results.addAll(it) }

                            // ALWAYS notify listener - guarantee prediction shown after swipe
                            predictionListener?.let { listener ->
                                if (!finalResults.isNullOrEmpty()) {
                                    listener.onGestureComplete(finalResults)
                                    Log.d(TAG, "Final prediction delivered: ${finalResults.size} results")
                                } else {
                                    // Even if no good results, still notify (may show fallback)
                                    listener.onGestureComplete(emptyList())
                                    Log.d(TAG, "No final predictions available")
                                }
                            }

                            // Debug logging (like CGR_printResults in Lua)
                            if (!finalResults.isNullOrEmpty()) {
                                printResults(finalResults)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Recognition error on end: ${e.message}")
                        // Still notify listener even on error to guarantee callback
                        mainHandler?.post {
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
        return results.toList()
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
        this.minPointsForPrediction = max(2, minPoints)
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
     * Check results quality (equivalent to CGR_checkResults in Lua)
     * Returns true if the best result is confident enough
     */
    fun isResultConfident(): Boolean {
        if (results.size < 2) return false

        val r1 = results[0]
        val r2 = results[1]

        val similarity = (r2.prob / r1.prob) * r2.prob

        return if (r1.prob > 0.7) {
            if (similarity < 95) {
                Log.d(TAG, "CHECK: Using: ${r1.template.id} : ${r1.prob}")
                true
            } else {
                Log.d(TAG, "CHECK: First two probabilities too close to call")
                false
            }
        } else {
            Log.d(TAG, "CHECK: Probability not high enough (<0.7), discarding user input")
            false
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

    companion object {
        private const val TAG = "ContinuousSwipeGestureRecognizer"
        private const val PREDICTION_THROTTLE_MS = 100L // Reasonable prediction frequency
    }
}
