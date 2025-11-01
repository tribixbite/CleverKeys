package tribixbite.keyboard2

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles swipe predictions asynchronously to prevent UI blocking.
 * Uses Kotlin coroutines for prediction processing and cancels
 * pending predictions when new input arrives.
 *
 * Features:
 * - Dedicated coroutine dispatcher for predictions (Dispatchers.Default)
 * - Automatic cancellation of pending predictions
 * - Request ID tracking for result validation
 * - Main thread callback delivery
 * - Graceful error handling
 * - Performance timing
 *
 * Fix for Bug #275: AsyncPredictionHandler system missing (CATASTROPHIC)
 */
class AsyncPredictionHandler(
    private val neuralEngine: NeuralSwipeTypingEngine
) {

    companion object {
        private const val TAG = "AsyncPredictionHandler"
    }

    /**
     * Callback interface for prediction results
     */
    interface PredictionCallback {
        fun onPredictionsReady(predictions: List<String>, scores: List<Int>)
        fun onPredictionError(error: String)
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestId = AtomicInteger(0)
    private val currentRequestIdFlow = MutableStateFlow(0)

    // Channel for prediction requests (capacity 1 = only latest request)
    private val predictionChannel = Channel<PredictionRequest>(capacity = Channel.CONFLATED)

    init {
        // Start prediction processor coroutine
        coroutineScope.launch {
            for (request in predictionChannel) {
                processPredictionRequest(request)
            }
        }
    }

    /**
     * Request predictions for swipe input asynchronously
     */
    fun requestPredictions(input: SwipeInput, callback: PredictionCallback) {
        // Cancel any pending predictions
        val newRequestId = requestId.incrementAndGet()
        currentRequestIdFlow.value = newRequestId

        // Send prediction request (conflated channel drops older requests)
        val request = PredictionRequest(input, callback, newRequestId)
        coroutineScope.launch {
            predictionChannel.send(request)
        }

        Log.d(TAG, "Prediction requested (ID: $newRequestId)")
    }

    /**
     * Cancel all pending predictions
     */
    fun cancelPendingPredictions() {
        val newRequestId = requestId.incrementAndGet()
        currentRequestIdFlow.value = newRequestId
        // Channel is conflated, so pending requests are automatically dropped
        Log.d(TAG, "All pending predictions cancelled")
    }

    /**
     * Process prediction request on background thread
     */
    private suspend fun processPredictionRequest(request: PredictionRequest) {
        // Check if this request has been cancelled
        if (request.requestId != currentRequestIdFlow.value) {
            Log.d(TAG, "Prediction cancelled (ID: ${request.requestId})")
            return
        }

        try {
            // Start timing
            val startTime = System.currentTimeMillis()

            // Perform prediction on background dispatcher (this is the potentially blocking operation)
            val result = withContext(Dispatchers.Default) {
                neuralEngine.predict(request.input)
            }

            // Check again if cancelled during prediction
            if (request.requestId != currentRequestIdFlow.value) {
                Log.d(TAG, "Prediction cancelled after processing (ID: ${request.requestId})")
                return
            }

            // Extract words and scores from result
            val words = result.words
            val scores = result.scores

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Prediction completed in ${duration}ms (ID: ${request.requestId})")

            // Post results to main thread
            withContext(Dispatchers.Main) {
                // Final check before delivering results
                if (request.requestId == currentRequestIdFlow.value) {
                    request.callback.onPredictionsReady(words, scores)
                }
            }
        } catch (e: CancellationException) {
            // Coroutine was cancelled, this is normal
            Log.d(TAG, "Prediction cancelled via coroutine (ID: ${request.requestId})")
        } catch (e: Exception) {
            Log.e(TAG, "Prediction error", e)

            // Post error to main thread
            withContext(Dispatchers.Main) {
                if (request.requestId == currentRequestIdFlow.value) {
                    request.callback.onPredictionError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Get current request ID for debugging
     */
    fun getCurrentRequestId(): Int = currentRequestIdFlow.value

    /**
     * Get current request ID as StateFlow for observation
     */
    fun getCurrentRequestIdFlow(): StateFlow<Int> = currentRequestIdFlow

    /**
     * Get prediction statistics for debugging
     */
    fun getStats(): String {
        return "AsyncPredictionHandler: Current Request ID: ${currentRequestIdFlow.value}, " +
                "Total Requests: ${requestId.get()}"
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        cancelPendingPredictions()
        predictionChannel.close()
        coroutineScope.cancel()
        Log.d(TAG, "AsyncPredictionHandler shutdown complete")
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
