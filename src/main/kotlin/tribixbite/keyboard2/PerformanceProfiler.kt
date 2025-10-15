package tribixbite.keyboard2

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.system.measureTimeMillis

/**
 * Performance profiling system for neural prediction analysis
 * Kotlin implementation with reactive performance monitoring
 */
class PerformanceProfiler(private val context: Context) {
    
    companion object {
        private const val TAG = "PerformanceProfiler"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val performanceData = mutableListOf<PerformanceMetric>()
    private val metricsFlow = MutableSharedFlow<PerformanceMetric>()
    
    /**
     * Performance metric data
     */
    data class PerformanceMetric(
        val operation: String,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Performance statistics
     */
    data class PerformanceStats(
        val operation: String,
        val totalCalls: Int,
        val averageDurationMs: Double,
        val minDurationMs: Long,
        val maxDurationMs: Long,
        val last10Average: Double
    )
    
    /**
     * Measure operation performance
     */
    suspend fun <T> measureOperation(operation: String, metadata: Map<String, Any> = emptyMap(), block: suspend () -> T): T {
        val result: T
        val duration = measureTimeMillis {
            result = block()
        }
        
        val metric = PerformanceMetric(operation, duration, metadata = metadata)
        performanceData.add(metric)
        metricsFlow.emit(metric)
        
        // Keep only recent data (last 1000 metrics)
        if (performanceData.size > 1000) {
            performanceData.removeAt(0)
        }
        
        logD("$operation: ${duration}ms")
        return result
    }
    
    /**
     * Get performance statistics
     */
    fun getStats(operation: String): PerformanceStats? {
        val operationMetrics = performanceData.filter { it.operation == operation }
        if (operationMetrics.isEmpty()) return null
        
        val durations = operationMetrics.map { it.durationMs }
        val last10 = operationMetrics.takeLast(10).map { it.durationMs }
        
        return PerformanceStats(
            operation = operation,
            totalCalls = operationMetrics.size,
            averageDurationMs = durations.average(),
            minDurationMs = durations.minOrNull() ?: 0L,
            maxDurationMs = durations.maxOrNull() ?: 0L,
            last10Average = if (last10.isNotEmpty()) last10.average() else 0.0
        )
    }
    
    /**
     * Get all tracked operations
     */
    fun getAllOperations(): List<String> {
        return performanceData.map { it.operation }.distinct()
    }
    
    /**
     * Get performance metrics flow for real-time monitoring
     */
    fun getMetricsFlow(): Flow<PerformanceMetric> = metricsFlow.asSharedFlow()
    
    /**
     * Generate performance report
     */
    fun generateReport(): String {
        val operations = getAllOperations()
        return buildString {
            appendLine("📊 Performance Report")
            appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
            appendLine()
            
            operations.forEach { operation ->
                val stats = getStats(operation)
                if (stats != null) {
                    appendLine("🔧 $operation:")
                    appendLine("   Calls: ${stats.totalCalls}")
                    appendLine("   Average: %.2fms".format(stats.averageDurationMs))
                    appendLine("   Min/Max: ${stats.minDurationMs}ms / ${stats.maxDurationMs}ms")
                    appendLine("   Recent avg: %.2fms".format(stats.last10Average))
                    appendLine()
                }
            }
        }
    }
    
    /**
     * Clear performance data
     */
    fun clearData() {
        performanceData.clear()
        logD("Performance data cleared")
    }
    
    /**
     * Start continuous monitoring
     */
    fun startMonitoring(onMetric: (PerformanceMetric) -> Unit) {
        scope.launch {
            metricsFlow.collect { metric ->
                onMetric(metric)
            }
        }
    }
    
    /**
     * Export performance data
     */
    suspend fun exportData(): String = withContext(Dispatchers.Default) {
        val json = org.json.JSONArray()
        performanceData.forEach { metric ->
            val obj = org.json.JSONObject().apply {
                put("operation", metric.operation)
                put("duration_ms", metric.durationMs)
                put("timestamp", metric.timestamp)
                put("metadata", org.json.JSONObject(metric.metadata))
            }
            json.put(obj)
        }
        json.toString(2)
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
    }

    private fun logD(message: String) {
        android.util.Log.d(TAG, message)
    }
}