package tribixbite.keyboard2

import ai.onnxruntime.*
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

// Import constants from OnnxSwipePredictorImpl
private const val PAD_IDX = 0

/**
 * Batched memory tensor optimizer for CleverKeys GPU utilization
 * Implements true batched memory allocation for optimal GPU performance
 */
class BatchedMemoryOptimizer(private val ortEnvironment: OrtEnvironment) {

    companion object {
        private const val TAG = "BatchedMemoryOptimizer"
        private const val MAX_BATCH_SIZE = 16
        private const val MEMORY_TENSOR_SIZE = 150 * 512 // seq_length * hidden_size
    }

    // Pre-allocated memory tensor pools for different batch sizes
    private val batchedMemoryPools = mutableMapOf<Int, ConcurrentLinkedQueue<BatchedMemoryTensor>>()
    private val directBufferPool = ConcurrentLinkedQueue<ByteBuffer>()

    // Performance tracking
    private var batchAllocations = 0L
    private var poolHits = 0L
    private var memoryOptimizationSavings = 0L

    init {
        initializeMemoryPools()
    }

    /**
     * Batched memory tensor with GPU-optimized layout
     */
    class BatchedMemoryTensor(
        val tensor: OnnxTensor,
        val batchSize: Int,
        val buffer: ByteBuffer,
        var isInUse: Boolean = false
    )

    /**
     * Acquire batched memory tensor optimized for GPU processing
     */
    suspend fun acquireBatchedMemory(batchSize: Int): BatchedMemoryHandle = withContext(Dispatchers.Default) {
        batchAllocations++

        val pool = batchedMemoryPools.getOrPut(batchSize) { ConcurrentLinkedQueue() }
        val pooledMemory = pool.poll()

        return@withContext if (pooledMemory != null) {
            // Pool hit - reuse existing batched memory tensor
            poolHits++
            pooledMemory.isInUse = true

            logD("♻️ Batched memory pool HIT: batch_size=$batchSize")
            BatchedMemoryHandle(pooledMemory, this@BatchedMemoryOptimizer)

        } else {
            // Pool miss - create new optimized batched memory tensor
            val newBatchedMemory = createOptimizedBatchedMemory(batchSize)

            logD("🆕 Batched memory pool MISS: creating batch_size=$batchSize")
            BatchedMemoryHandle(newBatchedMemory, this@BatchedMemoryOptimizer)
        }
    }

    /**
     * Create GPU-optimized batched memory tensor
     */
    private fun createOptimizedBatchedMemory(batchSize: Int): BatchedMemoryTensor {
        val totalSize = batchSize * MEMORY_TENSOR_SIZE * 4 // 4 bytes per float
        val buffer = acquireDirectBuffer(totalSize)

        // Create tensor shape for batched memory: [batch_size, seq_length, hidden_size]
        val shape = longArrayOf(batchSize.toLong(), 150L, 512L)

        // Create ONNX tensor with GPU-optimized memory layout
        val tensor = OnnxTensor.createTensor(ortEnvironment, buffer.asFloatBuffer(), shape)

        return BatchedMemoryTensor(tensor, batchSize, buffer, false)
    }

    /**
     * Replicate single memory tensor into batched format for GPU efficiency
     */
    fun replicateMemoryForBatch(
        singleMemory: OnnxTensor,
        batchSize: Int,
        batchedMemoryHandle: BatchedMemoryHandle
    ) {
        val singleData = singleMemory.value as Array<FloatArray> // [seq_length, hidden_size]
        val batchedData = batchedMemoryHandle.tensor.value as Array<Array<FloatArray>> // [batch_size, seq_length, hidden_size]

        // Replicate single memory across all batch positions
        for (batchIndex in 0 until batchSize) {
            for (seqIndex in singleData.indices) {
                System.arraycopy(singleData[seqIndex], 0, batchedData[batchIndex][seqIndex], 0, singleData[seqIndex].size)
            }
        }

        logD("📋 Memory replicated for batch_size=$batchSize")
    }

    /**
     * Create optimized batched decoder inputs with proper GPU memory layout
     */
    suspend fun createBatchedDecoderInputs(
        batchSize: Int,
        activeBeams: List<OnnxSwipePredictorImpl.BeamSearchState>,
        singleMemory: OnnxTensor,
        srcMaskTensor: OnnxTensor
    ): Map<String, OnnxTensor> = withContext(Dispatchers.Default) {

        // Acquire batched memory tensor from pool
        val batchedMemoryHandle = acquireBatchedMemory(batchSize)

        try {
            // Replicate memory for all beams in batch
            replicateMemoryForBatch(singleMemory, batchSize, batchedMemoryHandle)

            // Create batched tokens and masks using tensor pool
            val tensorPool = OptimizedTensorPool.getInstance(ortEnvironment)

            return@withContext tensorPool.useTensor(longArrayOf(batchSize.toLong(), 20L), "long") { batchedTokens ->
                tensorPool.useTensor(longArrayOf(batchSize.toLong(), 20L), "boolean") { batchedMask ->

                    // Populate batched inputs efficiently
                    populateBatchedInputs(activeBeams, batchedTokens, batchedMask)

                    // Create batched source mask
                    val batchedSrcMask = createBatchedSourceMask(batchSize, srcMaskTensor)

                    mapOf(
                        "memory" to batchedMemoryHandle.tensor,
                        "target_tokens" to batchedTokens,
                        "target_mask" to batchedMask,
                        "src_mask" to batchedSrcMask
                    )
                }
            }

        } finally {
            // Memory handle will be cleaned up by caller
        }
    }

    /**
     * Create batched source mask for GPU optimization
     */
    private fun createBatchedSourceMask(batchSize: Int, singleSrcMask: OnnxTensor): OnnxTensor {
        val singleMaskData = singleSrcMask.value as Array<BooleanArray> // [1, seq_length]
        val batchedMaskData = Array(batchSize) { Array(1) { singleMaskData[0].clone() } }

        return OnnxTensor.createTensor(ortEnvironment, batchedMaskData)
    }

    /**
     * Efficiently populate batched input tensors
     */
    private fun populateBatchedInputs(
        activeBeams: List<OnnxSwipePredictorImpl.BeamSearchState>,
        batchedTokens: OnnxTensor,
        batchedMask: OnnxTensor
    ) {
        val tokensData = batchedTokens.value as Array<LongArray>
        val maskData = batchedMask.value as Array<BooleanArray>

        activeBeams.forEachIndexed { batchIndex, beam ->
            // Fill tokens and mask arrays directly
            val tokensArray = tokensData[batchIndex]
            val maskArray = maskData[batchIndex]

            beam.tokens.forEachIndexed { seqIndex, token ->
                if (seqIndex < tokensArray.size) {
                    tokensArray[seqIndex] = token
                    maskArray[seqIndex] = true
                }
            }

            // Pad remaining positions
            for (i in beam.tokens.size until tokensArray.size) {
                tokensArray[i] = PAD_IDX.toLong()
                maskArray[i] = false
            }
        }
    }

    /**
     * Release batched memory back to pool
     */
    suspend fun releaseBatchedMemory(handle: BatchedMemoryHandle) {
        val batchedMemory = handle.batchedMemory
        batchedMemory.isInUse = false

        val pool = batchedMemoryPools[batchedMemory.batchSize]
        if (pool?.size ?: 0 < MAX_BATCH_SIZE) {
            pool?.offer(batchedMemory)
            logD("♻️ Batched memory returned to pool: batch_size=${batchedMemory.batchSize}")
        } else {
            // Pool full - cleanup
            batchedMemory.tensor.close()
            releaseDirectBuffer(batchedMemory.buffer)
            logD("🗑️ Batched memory disposed: batch_size=${batchedMemory.batchSize}")
        }
    }

    /**
     * Initialize memory pools with common batch sizes
     */
    private fun initializeMemoryPools() {
        val commonBatchSizes = listOf(1, 2, 4, 8, 16)

        commonBatchSizes.forEach { batchSize ->
            batchedMemoryPools[batchSize] = ConcurrentLinkedQueue()

            // Pre-allocate 2 batched memory tensors per size
            repeat(2) {
                val batchedMemory = createOptimizedBatchedMemory(batchSize)
                batchedMemoryPools[batchSize]?.offer(batchedMemory)
            }
        }

        // Pre-allocate direct buffers
        repeat(8) {
            val buffer = ByteBuffer.allocateDirect(MAX_BATCH_SIZE * MEMORY_TENSOR_SIZE * 4)
                .order(ByteOrder.nativeOrder())
            directBufferPool.offer(buffer)
        }

        logD("Batched memory pools initialized for batch sizes: ${commonBatchSizes}")
    }

    private fun acquireDirectBuffer(sizeBytes: Int): ByteBuffer {
        return directBufferPool.poll()?.also { buffer ->
            if (buffer.capacity() >= sizeBytes) {
                buffer.clear()
                return buffer
            } else {
                // Buffer too small, return to pool and create new
                directBufferPool.offer(buffer)
            }
        } ?: ByteBuffer.allocateDirect(sizeBytes).order(ByteOrder.nativeOrder())
    }

    private fun releaseDirectBuffer(buffer: ByteBuffer) {
        buffer.clear()
        if (directBufferPool.size < 16) { // Limit pool size
            directBufferPool.offer(buffer)
        }
    }

    /**
     * Get memory optimization statistics
     */
    fun getMemoryStats(): MemoryOptimizationStats {
        val hitRate = if (batchAllocations > 0) {
            (poolHits.toFloat() / batchAllocations.toFloat()) * 100
        } else 0f

        return MemoryOptimizationStats(
            totalBatchAllocations = batchAllocations,
            poolHits = poolHits,
            hitRate = hitRate,
            activePools = batchedMemoryPools.size,
            memoryOptimizationSavings = memoryOptimizationSavings
        )
    }

    /**
     * Cleanup all memory pools
     */
    suspend fun cleanup() = withContext(Dispatchers.Default) {
        logD("🧹 Cleaning up batched memory pools...")

        batchedMemoryPools.values.forEach { pool ->
            while (pool.isNotEmpty()) {
                val batchedMemory = pool.poll()
                batchedMemory?.tensor?.close()
                batchedMemory?.buffer?.let { releaseDirectBuffer(it) }
            }
        }
        batchedMemoryPools.clear()

        // Clear direct buffer pool
        directBufferPool.clear()

        val stats = getMemoryStats()
        logD("Final memory optimization stats: ${stats.hitRate}% hit rate, ${stats.memoryOptimizationSavings}MB saved")
    }

    /**
     * Memory optimization statistics
     */
    data class MemoryOptimizationStats(
        val totalBatchAllocations: Long,
        val poolHits: Long,
        val hitRate: Float,
        val activePools: Int,
        val memoryOptimizationSavings: Long
    )

    private fun logD(message: String) {
        android.util.Log.d(TAG, message)
    }
}

/**
 * Handle for batched memory tensor with automatic cleanup
 */
class BatchedMemoryHandle(
    internal val batchedMemory: BatchedMemoryOptimizer.BatchedMemoryTensor,
    private val optimizer: BatchedMemoryOptimizer
) : AutoCloseable {

    val tensor: OnnxTensor get() = batchedMemory.tensor

    override fun close() {
        kotlinx.coroutines.runBlocking {
            optimizer.releaseBatchedMemory(this@BatchedMemoryHandle)
        }
    }
}