package juloo.keyboard2

import ai.onnxruntime.*
import kotlinx.coroutines.*
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Sophisticated tensor memory management for ONNX operations
 * Kotlin implementation with memory pooling and automatic cleanup
 */
class TensorMemoryManager(private val ortEnvironment: OrtEnvironment) {
    
    companion object {
        private const val TAG = "TensorMemoryManager"
        private const val MAX_POOL_SIZE = 50
        private const val CLEANUP_INTERVAL_MS = 30_000L // 30 seconds
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Memory pools for different tensor types
    private val floatArrayPool = TensorPool<FloatArray>("FloatArray")
    private val longArrayPool = TensorPool<LongArray>("LongArray") 
    private val booleanArrayPool = TensorPool<BooleanArray>("BooleanArray")
    private val float2DArrayPool = TensorPool<Array<FloatArray>>("Float2D")
    private val boolean2DArrayPool = TensorPool<Array<BooleanArray>>("Boolean2D")
    
    // Active tensor tracking
    private val activeTensors = ConcurrentHashMap<Long, TensorInfo>()
    private val tensorIdCounter = AtomicLong(0)
    
    // Memory statistics
    private var totalTensorsCreated = 0L
    private var totalTensorsReused = 0L
    private var totalMemoryAllocated = 0L
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Tensor information for tracking
     */
    private data class TensorInfo(
        val id: Long,
        val type: String,
        val shape: LongArray,
        val sizeBytes: Long,
        val createdAt: Long,
        val tensor: OnnxTensor
    )
    
    /**
     * Generic tensor pool
     */
    private class TensorPool<T>(private val typeName: String) {
        private val pool = mutableListOf<PooledItem<T>>()
        private var hits = 0L
        private var misses = 0L
        
        data class PooledItem<T>(
            val item: T,
            val sizeBytes: Long,
            val lastUsed: Long
        )
        
        @Synchronized
        fun acquire(sizeBytes: Long, factory: () -> T): T {
            // Try to find compatible item in pool
            val index = pool.indexOfFirst { it.sizeBytes >= sizeBytes }
            
            return if (index >= 0) {
                val item = pool.removeAt(index)
                hits++
                android.util.Log.d("TensorPool", "$typeName pool hit: $hits/$misses (${(hits.toFloat() / (hits + misses) * 100).toInt()}%)")
                item.item
            } else {
                misses++
                val newItem = factory()
                android.util.Log.d("TensorPool", "$typeName pool miss: $hits/$misses")
                newItem
            }
        }
        
        @Synchronized
        fun release(item: T, sizeBytes: Long) {
            if (pool.size < MAX_POOL_SIZE) {
                pool.add(PooledItem(item, sizeBytes, System.currentTimeMillis()))
                
                // Sort by size for better matching
                pool.sortBy { it.sizeBytes }
            }
        }
        
        @Synchronized
        fun cleanup(maxAge: Long) {
            val cutoff = System.currentTimeMillis() - maxAge
            pool.removeAll { it.lastUsed < cutoff }
        }
        
        fun getStats(): PoolStats = PoolStats(typeName, pool.size, hits, misses)
    }
    
    /**
     * Create tensor with memory management
     */
    fun createManagedTensor(data: FloatArray, shape: LongArray): OnnxTensor {
        val sizeBytes = data.size * 4L // 4 bytes per float
        val managedData = floatArrayPool.acquire(sizeBytes) { FloatArray(data.size) }
        
        // Copy data to managed array
        System.arraycopy(data, 0, managedData, 0, data.size)
        
        val tensor = OnnxTensor.createTensor(ortEnvironment, java.nio.FloatBuffer.wrap(managedData), shape)
        trackTensor(tensor, "FloatArray", shape, sizeBytes)
        
        totalTensorsCreated++
        totalMemoryAllocated += sizeBytes
        
        return tensor
    }
    
    /**
     * Create batched tensor with memory pooling
     */
    fun createBatchedTensor(batchData: Array<FloatArray>, shape: LongArray): OnnxTensor {
        val sizeBytes = batchData.sumOf { it.size } * 4L
        val managedData = float2DArrayPool.acquire(sizeBytes) {
            Array(batchData.size) { FloatArray(batchData[0].size) }
        }
        
        // Copy batch data
        batchData.forEachIndexed { index, array ->
            System.arraycopy(array, 0, managedData[index], 0, array.size)
        }
        
        val tensor = OnnxTensor.createTensor(ortEnvironment, managedData)
        trackTensor(tensor, "BatchedFloat", shape, sizeBytes)
        
        return tensor
    }
    
    /**
     * Create boolean tensor with pooling
     */
    fun createBooleanTensor(data: Array<BooleanArray>): OnnxTensor {
        val sizeBytes = data.sumOf { it.size } * 1L // 1 byte per boolean
        val managedData = boolean2DArrayPool.acquire(sizeBytes) {
            Array(data.size) { BooleanArray(data[0].size) }
        }
        
        // Copy data
        data.forEachIndexed { index, array ->
            System.arraycopy(array, 0, managedData[index], 0, array.size)
        }
        
        val tensor = OnnxTensor.createTensor(ortEnvironment, managedData)
        trackTensor(tensor, "Boolean2D", longArrayOf(data.size.toLong(), data[0].size.toLong()), sizeBytes)
        
        return tensor
    }
    
    /**
     * Track active tensor for cleanup
     */
    fun trackTensor(tensor: OnnxTensor, type: String, shape: LongArray, sizeBytes: Long) {
        val id = tensorIdCounter.incrementAndGet()
        val info = TensorInfo(id, type, shape, sizeBytes, System.currentTimeMillis(), tensor)
        activeTensors[id] = info
    }
    
    /**
     * Release tensor and return memory to pool
     */
    fun releaseTensor(tensor: OnnxTensor) {
        // Find tensor info
        val tensorInfo = activeTensors.values.find { it.tensor === tensor }
        if (tensorInfo != null) {
            activeTensors.remove(tensorInfo.id)
            
            // Return to appropriate pool (simplified)
            try {
                tensor.close()
            } catch (e: Exception) {
                logE("Error closing tensor", e)
            }
        }
    }
    
    /**
     * Start periodic cleanup
     */
    private fun startPeriodicCleanup() {
        scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                performCleanup()
            }
        }
    }
    
    /**
     * Perform memory cleanup
     */
    private fun performCleanup() {
        val maxAge = 60_000L // 1 minute
        
        // Clean up pools
        floatArrayPool.cleanup(maxAge)
        longArrayPool.cleanup(maxAge)
        booleanArrayPool.cleanup(maxAge)
        float2DArrayPool.cleanup(maxAge)
        boolean2DArrayPool.cleanup(maxAge)
        
        // Clean up old active tensors
        val cutoff = System.currentTimeMillis() - maxAge
        val oldTensors = activeTensors.values.filter { it.createdAt < cutoff }
        
        oldTensors.forEach { tensorInfo ->
            logW("Cleaning up old tensor: ${tensorInfo.type} (${tensorInfo.sizeBytes} bytes)")
            try {
                tensorInfo.tensor.close()
                activeTensors.remove(tensorInfo.id)
            } catch (e: Exception) {
                logE("Error cleaning up tensor", e)
            }
        }
        
        logD("Memory cleanup: ${oldTensors.size} tensors cleaned, ${activeTensors.size} active")
    }
    
    /**
     * Get memory statistics
     */
    fun getMemoryStats(): MemoryStats {
        val totalActiveMemory = activeTensors.values.sumOf { it.sizeBytes }
        
        return MemoryStats(
            activeTensors = activeTensors.size,
            totalActiveMemoryBytes = totalActiveMemory,
            totalTensorsCreated = totalTensorsCreated,
            totalTensorsReused = totalTensorsReused,
            poolStats = listOf(
                floatArrayPool.getStats(),
                longArrayPool.getStats(),
                booleanArrayPool.getStats(),
                float2DArrayPool.getStats(),
                boolean2DArrayPool.getStats()
            )
        )
    }
    
    /**
     * Memory statistics
     */
    data class MemoryStats(
        val activeTensors: Int,
        val totalActiveMemoryBytes: Long,
        val totalTensorsCreated: Long,
        val totalTensorsReused: Long,
        val poolStats: List<PoolStats>
    )
    
    /**
     * Pool statistics
     */
    data class PoolStats(
        val typeName: String,
        val poolSize: Int,
        val hits: Long,
        val misses: Long
    ) {
        val hitRate: Float get() = if (hits + misses > 0) hits.toFloat() / (hits + misses) else 0f
    }
    
    /**
     * Cleanup all resources
     */
    fun cleanup() {
        scope.cancel()
        
        // Close all active tensors
        activeTensors.values.forEach { tensorInfo ->
            try {
                tensorInfo.tensor.close()
            } catch (e: Exception) {
                logE("Error closing tensor during cleanup", e)
            }
        }
        activeTensors.clear()
        
        logD("Tensor memory manager cleaned up")
    }
}