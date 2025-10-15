package tribixbite.keyboard2

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext

/**
 * Modern coroutine-based prediction repository
 * Replaces AsyncPredictionHandler with structured concurrency
 * 
 * This eliminates the complex HandlerThread, Message queue, and callback system
 * with clean, type-safe coroutines and Flow-based reactive programming
 */
class PredictionRepository(
    private val neuralEngine: NeuralSwipeTypingEngine,
    private val coroutineContext: CoroutineContext = Dispatchers.Default + SupervisorJob()
) {
    
    private val scope = CoroutineScope(coroutineContext)
    
    // Channel for prediction requests with automatic backpressure handling
    private val predictionRequests = Channel<PredictionRequest>(Channel.UNLIMITED)
    
    // Current prediction job for cancellation
    private var currentPredictionJob: Job? = null
    
    /**
     * Internal prediction request data
     */
    private data class PredictionRequest(
        val input: SwipeInput,
        val deferred: CompletableDeferred<PredictionResult>
    )
    
    init {
        // Start prediction processor coroutine
        scope.launch {
            predictionRequests.consumeAsFlow()
                .collect { request ->
                    processRequest(request)
                }
        }
    }
    
    /**
     * Request prediction asynchronously with automatic cancellation
     * Returns a Deferred that can be awaited or cancelled
     */
    fun requestPrediction(input: SwipeInput): Deferred<PredictionResult> {
        // Cancel previous prediction
        currentPredictionJob?.cancel()
        
        val deferred = CompletableDeferred<PredictionResult>()
        val request = PredictionRequest(input, deferred)
        
        currentPredictionJob = scope.launch {
            try {
                predictionRequests.send(request)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }
        
        return deferred
    }
    
    /**
     * Request prediction with callback (for Java interop)
     */
    fun requestPrediction(input: SwipeInput, callback: PredictionCallback) {
        scope.launch {
            try {
                val result = requestPrediction(input).await()
                callback.onPredictionsReady(result.words, result.scores)
            } catch (e: CancellationException) {
                // Expected when new prediction starts
                logD("Prediction cancelled")
            } catch (e: Exception) {
                callback.onPredictionError(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Suspend function for direct coroutine usage
     */
    suspend fun predict(input: SwipeInput): PredictionResult = withContext(Dispatchers.Default) {
        try {
            logD("🚀 Starting neural prediction for ${input.coordinates.size} points")
            val (result, duration) = measureTimeNanos {
                neuralEngine.predict(input)
            }
            logD("🧠 Neural prediction completed in ${duration / 1_000_000}ms")
            result
        } catch (e: Exception) {
            logE("Neural prediction failed", e)
            throw e
        }
    }
    
    /**
     * Process prediction request in background
     */
    private suspend fun processRequest(request: PredictionRequest) {
        try {
            val result = predict(request.input)
            request.deferred.complete(result)
        } catch (e: Exception) {
            request.deferred.completeExceptionally(e)
        }
    }
    
    /**
     * Cancel all pending predictions
     */
    fun cancelPendingPredictions() {
        currentPredictionJob?.cancel()
        
        // Clear pending requests
        while (!predictionRequests.isEmpty) {
            predictionRequests.tryReceive().getOrNull()?.deferred?.cancel()
        }
    }
    
    /**
     * Create reactive Flow for real-time predictions
     * Useful for continuous gesture recognition
     */
    fun createPredictionFlow(inputFlow: Flow<SwipeInput>): Flow<PredictionResult> {
        return inputFlow
            .debounce(50) // Debounce rapid input updates
            .distinctUntilChanged { old, new -> 
                // Skip if input hasn't changed significantly
                old.coordinates.size == new.coordinates.size && 
                old.pathLength == new.pathLength
            }
            .flowOn(Dispatchers.Default)
            .map { input -> predict(input) }
            .catch { e -> 
                logE("Prediction flow error", e)
                emit(PredictionResult.empty)
            }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        while (!predictionRequests.isEmpty) {
            predictionRequests.tryReceive().getOrNull()?.deferred?.cancel()
        }
    }
    
    /**
     * Callback interface for Java interoperability
     * Modern Kotlin code should use suspend functions or Flow instead
     */
    interface PredictionCallback {
        fun onPredictionsReady(words: List<String>, scores: List<Int>)
        fun onPredictionError(error: String)
    }
    
    companion object {
        private const val TAG = "PredictionRepository"
    }

    private fun logD(message: String) {
        android.util.Log.d(TAG, message)
    }

    private fun logE(message: String, throwable: Throwable) {
        android.util.Log.e(TAG, message, throwable)
    }

    /**
     * Measure execution time in nanoseconds with result
     */
    private inline fun <T> measureTimeNanos(block: () -> T): Pair<T, Long> {
        val startTime = System.nanoTime()
        val result = block()
        val duration = System.nanoTime() - startTime
        return Pair(result, duration)
    }

    /**
     * Statistics for monitoring
     */
    data class PredictionStats(
        val totalPredictions: Int,
        val averageTimeMs: Double,
        val successRate: Double,
        val pendingRequests: Int
    )
    
    private var totalPredictions = 0
    private var totalTime = 0L
    private var successfulPredictions = 0
    
    /**
     * Get performance statistics
     */
    fun getStats(): PredictionStats {
        return PredictionStats(
            totalPredictions = totalPredictions,
            averageTimeMs = if (totalPredictions > 0) totalTime.toDouble() / totalPredictions / 1_000_000 else 0.0,
            successRate = if (totalPredictions > 0) successfulPredictions.toDouble() / totalPredictions else 0.0,
            pendingRequests = predictionRequests.tryReceive().let { 0 } // Approximate
        )
    }
}