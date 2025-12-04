package tribixbite.cleverkeys

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    
    companion object {
        private const val TAG = "PredictionRepository"
        private const val MAX_PENDING_REQUESTS = 16 // Limit pending requests for backpressure
    }

    private val scope = CoroutineScope(coroutineContext)

    // Channel for prediction requests with bounded capacity for backpressure
    // Bug #194 fix: Changed from UNLIMITED to bounded capacity to prevent memory bloat
    private val predictionRequests = Channel<PredictionRequest>(MAX_PENDING_REQUESTS)

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
     * Bug #196 fix: Preserve exception type in error callback
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
                // Include exception type and message for better debugging
                val errorMessage = "${e::class.simpleName}: ${e.message ?: "Unknown error"}"
                logE("Prediction error: $errorMessage", e)
                callback.onPredictionError(errorMessage)
            }
        }
    }
    
    /**
     * Suspend function for direct coroutine usage
     * Bug #192 fix: Now tracks statistics
     */
    suspend fun predict(input: SwipeInput): PredictionResult = withContext(Dispatchers.Default) {
        totalPredictions.incrementAndGet()
        try {
            logD("🚀 Starting neural prediction for ${input.coordinates.size} points")
            val (result, duration) = measureTimeNanos {
                neuralEngine.predict(input)
            }
            totalTime.addAndGet(duration)
            successfulPredictions.incrementAndGet()
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
     * Bug #195 fix: Use completeExceptionally for Deferred cancellation
     */
    fun cancelPendingPredictions() {
        currentPredictionJob?.cancel()

        // Clear pending requests with proper Deferred cancellation
        while (!predictionRequests.isEmpty) {
            predictionRequests.tryReceive().getOrNull()?.deferred?.completeExceptionally(
                CancellationException("Prediction cancelled")
            )
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
     * Bug #195 fix: Use completeExceptionally for Deferred cancellation
     */
    fun cleanup() {
        scope.cancel()
        while (!predictionRequests.isEmpty) {
            predictionRequests.tryReceive().getOrNull()?.deferred?.completeExceptionally(
                CancellationException("Repository cleanup")
            )
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
        val successRate: Double
    )

    // Bug #191 fix: Thread-safe statistics with atomic operations
    private val totalPredictions = AtomicInteger(0)
    private val totalTime = AtomicLong(0L)
    private val successfulPredictions = AtomicInteger(0)
    private val statsLock = Any() // Bug #191 fix: Lock for multi-value atomic consistency

    /**
     * Get performance statistics
     * Bug #191 fix: Thread-safe read with synchronized block for atomic consistency
     * Bug #193 fix: Removed pendingRequests (was mutating channel)
     */
    fun getStats(): PredictionStats {
        // Synchronize to ensure all three reads happen atomically together
        // Prevents TOCTOU race where values become inconsistent mid-read
        return synchronized(statsLock) {
            val total = totalPredictions.get()
            val time = totalTime.get()
            val successful = successfulPredictions.get()

            PredictionStats(
                totalPredictions = total,
                averageTimeMs = if (total > 0) time.toDouble() / total / 1_000_000 else 0.0,
                successRate = if (total > 0) successful.toDouble() / total else 0.0
            )
        }
    }

    /**
     * Reset performance statistics
     * Bug #191 fix: Thread-safe reset with synchronized block for atomic consistency
     * Bug #192 fix: Add resetStats() to make statistics functional
     */
    fun resetStats() {
        // Synchronize to ensure all three resets happen atomically together
        // Prevents partial resets if prediction completes mid-reset
        synchronized(statsLock) {
            totalPredictions.set(0)
            totalTime.set(0L)
            successfulPredictions.set(0)
        }
        logD("Prediction statistics reset")
    }

    /**
     * Log current statistics for monitoring
     * Bug #192 fix: Add logStats() for real-time monitoring
     */
    fun logStats() {
        val stats = getStats()
        logD("""
            📊 Prediction Statistics:
               Total predictions: ${stats.totalPredictions}
               Average time: ${"%.2f".format(stats.averageTimeMs)}ms
               Success rate: ${"%.1f".format(stats.successRate * 100)}%
        """.trimIndent())
    }
}