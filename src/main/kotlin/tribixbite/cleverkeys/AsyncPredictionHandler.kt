package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles swipe predictions asynchronously to prevent UI blocking.
 * Uses a dedicated thread for prediction processing and cancels
 * pending predictions when new input arrives.
 *
 * @since v1.32.896 - Added performance statistics tracking
 */
class AsyncPredictionHandler(
    private val neuralEngine: NeuralSwipeTypingEngine,
    private val context: Context
) {
    companion object {
        private const val TAG = "AsyncPredictionHandler"

        // Message types
        private const val MSG_PREDICT = 1
        private const val MSG_CANCEL_PENDING = 2
    }

    /**
     * Callback interface for prediction results
     */
    interface PredictionCallback {
        fun onPredictionsReady(predictions: List<String>, scores: List<Int>)
        fun onPredictionError(error: String)
    }

    private val workerThread: HandlerThread = HandlerThread("SwipePredictionWorker").apply { start() }
    private val workerHandler: Handler
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val requestId: AtomicInteger = AtomicInteger(0)
    private val perfStats: NeuralPerformanceStats = NeuralPerformanceStats.getInstance(context)

    @Volatile
    private var currentRequestId: Int = 0

    init {
        // Handler for worker thread
        workerHandler = object : Handler(workerThread.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_PREDICT -> handlePredictionRequest(msg)
                    MSG_CANCEL_PENDING -> {
                        // Just update the current request ID to cancel older requests
                        currentRequestId = msg.arg1
                    }
                }
            }
        }
    }

    /**
     * Request predictions for swipe input asynchronously
     */
    fun requestPredictions(input: SwipeInput, callback: PredictionCallback) {
        // Cancel any pending predictions
        val newRequestId = requestId.incrementAndGet()
        currentRequestId = newRequestId

        // Send cancel message first
        workerHandler.obtainMessage(MSG_CANCEL_PENDING, newRequestId, 0).sendToTarget()

        // Create prediction request
        val request = PredictionRequest(input, callback, newRequestId)
        val msg = workerHandler.obtainMessage(MSG_PREDICT, request)
        workerHandler.sendMessage(msg)

        Log.d(TAG, "Prediction requested (ID: $newRequestId)")
    }

    /**
     * Cancel all pending predictions
     */
    fun cancelPendingPredictions() {
        val newRequestId = requestId.incrementAndGet()
        currentRequestId = newRequestId
        workerHandler.obtainMessage(MSG_CANCEL_PENDING, newRequestId, 0).sendToTarget()
        workerHandler.removeMessages(MSG_PREDICT)

        Log.d(TAG, "All pending predictions cancelled")
    }

    /**
     * Handle prediction request on worker thread
     */
    private fun handlePredictionRequest(msg: Message) {
        val request = msg.obj as PredictionRequest

        // Check if this request has been cancelled
        if (request.requestId != currentRequestId) {
            Log.d(TAG, "Prediction cancelled (ID: ${request.requestId})")
            return
        }

        try {
            // Start timing
            val startTime = System.currentTimeMillis()

            // Perform prediction (this is the potentially blocking operation)
            val result = neuralEngine.predict(request.input)

            // Check again if cancelled during prediction
            if (request.requestId != currentRequestId) {
                Log.d(TAG, "Prediction cancelled after processing (ID: ${request.requestId})")
                return
            }

            // Extract words and scores directly (neural system uses integers)
            val words = result.words
            val scores = result.scores

            val duration = System.currentTimeMillis() - startTime
            val postTime = System.currentTimeMillis()
            Log.e(TAG, "⏱️ PREDICTION COMPLETED in ${duration}ms (ID: ${request.requestId})")

            // Record performance statistics
            perfStats.recordPrediction(duration)

            // Record model usage for versioning
            NeuralModelMetadata.getInstance(context).recordInferenceUsage()

            // Post results to main thread
            mainHandler.post {
                val callbackDelay = System.currentTimeMillis() - postTime
                Log.e(TAG, "⏱️ CALLBACK DELAY: ${callbackDelay}ms (time from post to run)")

                // Final check before delivering results
                if (request.requestId == currentRequestId) {
                    val callbackStartTime = System.currentTimeMillis()
                    request.callback.onPredictionsReady(words, scores)
                    val callbackDuration = System.currentTimeMillis() - callbackStartTime
                    Log.e(TAG, "⏱️ CALLBACK EXECUTION: ${callbackDuration}ms (onPredictionsReady)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Prediction error", e)

            // Post error to main thread
            mainHandler.post {
                if (request.requestId == currentRequestId) {
                    request.callback.onPredictionError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        cancelPendingPredictions()
        workerThread.quit()
    }

    /**
     * Container for prediction request data
     */
    private data class PredictionRequest(
        val input: SwipeInput,
        val callback: PredictionCallback,
        val requestId: Int
    )
}
