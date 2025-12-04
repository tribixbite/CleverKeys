package tribixbite.cleverkeys.onnx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * Memory pool for pre-allocated tensor buffers to reduce GC pressure during inference.
 *
 * This class manages reusable buffers for:
 * - Token sequences (for decoder input)
 * - Memory replication arrays (for non-broadcast models)
 * - Source masks (for padding masks)
 * - Probability arrays (for softmax output)
 *
 * All buffers are allocated once and reused across multiple predictions to minimize
 * object allocation and garbage collection overhead during the hot path.
 *
 * Thread Safety: This class is NOT thread-safe. Should be used from a single thread.
 */
class MemoryPool {

    // Pooled buffers for legacy (non-batch) decoder path
    private var pooledTokensByteBuffer: ByteBuffer? = null
    private var pooledTokensLongBuffer: LongBuffer? = null
    private var pooledMemoryArray: Array<Array<FloatArray>>? = null
    private var pooledSrcMaskArray: Array<BooleanArray>? = null
    private var pooledBufferMaxBeams: Int = 0

    // Pre-allocated buffers for batched decoder path
    private var preallocBatchedTokens: Array<IntArray>? = null
    private var preallocTokensByteBuffer: ByteBuffer? = null
    private var preallocTokensIntBuffer: IntBuffer? = null
    private var preallocSrcLengths: IntArray? = null
    private var preallocProbs: FloatArray? = null

    /**
     * Initialize pre-allocated buffers for batched beam search.
     *
     * @param maxBeams Maximum number of beams
     * @param decoderSeqLength Fixed decoder sequence length (e.g., 20)
     * @param vocabSize Vocabulary size for probability arrays
     */
    fun initializePreallocatedBuffers(
        maxBeams: Int,
        decoderSeqLength: Int,
        vocabSize: Int
    ) {
        // Pre-allocate batched token arrays [beam_width, decoder_seq_length]
        preallocBatchedTokens = Array(maxBeams) { IntArray(decoderSeqLength) }

        // Pre-allocate source length array [beam_width]
        preallocSrcLengths = IntArray(maxBeams)

        // Pre-allocate probability array [vocab_size]
        preallocProbs = FloatArray(vocabSize)

        // Pre-allocate direct ByteBuffer for ONNX (more efficient than heap arrays)
        val tokensByteBufferSize = maxBeams * decoderSeqLength * Int.SIZE_BYTES
        preallocTokensByteBuffer = ByteBuffer.allocateDirect(tokensByteBufferSize).apply {
            order(ByteOrder.nativeOrder())
        }
        preallocTokensIntBuffer = preallocTokensByteBuffer?.asIntBuffer()
    }

    /**
     * Initialize pooled buffers for sequential (non-batched) decoder path.
     *
     * @param newCapacity New beam capacity
     * @param maxSeqLength Maximum sequence length
     * @param hiddenDim Model hidden dimension
     */
    fun ensurePooledCapacity(newCapacity: Int, maxSeqLength: Int, hiddenDim: Int) {
        if (newCapacity <= pooledBufferMaxBeams) {
            return // Already have sufficient capacity
        }

        // Allocate token buffer
        val tokensByteBufferSize = newCapacity * maxSeqLength * Long.SIZE_BYTES
        pooledTokensByteBuffer = ByteBuffer.allocateDirect(tokensByteBufferSize).apply {
            order(ByteOrder.nativeOrder())
        }
        pooledTokensLongBuffer = pooledTokensByteBuffer?.asLongBuffer()

        // Allocate memory replication array
        pooledMemoryArray = Array(newCapacity) {
            Array(maxSeqLength) { FloatArray(hiddenDim) }
        }

        // Allocate source mask array
        pooledSrcMaskArray = Array(newCapacity) { BooleanArray(maxSeqLength) }

        pooledBufferMaxBeams = newCapacity
    }

    /**
     * Get pre-allocated batched token arrays.
     * Must call initializePreallocatedBuffers() first.
     */
    fun getPreallocBatchedTokens(): Array<IntArray> {
        return preallocBatchedTokens
            ?: throw IllegalStateException("Pre-allocated buffers not initialized")
    }

    /**
     * Get pre-allocated tokens ByteBuffer for ONNX.
     * Must call initializePreallocatedBuffers() first.
     */
    fun getPreallocTokensByteBuffer(): ByteBuffer {
        return preallocTokensByteBuffer
            ?: throw IllegalStateException("Pre-allocated buffers not initialized")
    }

    /**
     * Get pre-allocated tokens IntBuffer view.
     * Must call initializePreallocatedBuffers() first.
     */
    fun getPreallocTokensIntBuffer(): IntBuffer {
        return preallocTokensIntBuffer
            ?: throw IllegalStateException("Pre-allocated buffers not initialized")
    }

    /**
     * Get pre-allocated source length array.
     * Must call initializePreallocatedBuffers() first.
     */
    fun getPreallocSrcLengths(): IntArray {
        return preallocSrcLengths
            ?: throw IllegalStateException("Pre-allocated buffers not initialized")
    }

    /**
     * Get pre-allocated probability array for softmax.
     * Must call initializePreallocatedBuffers() first.
     */
    fun getPreallocProbs(): FloatArray {
        return preallocProbs
            ?: throw IllegalStateException("Pre-allocated buffers not initialized")
    }

    /**
     * Get pooled tokens LongBuffer.
     * Must call ensurePooledCapacity() first.
     */
    fun getPooledTokensLongBuffer(): LongBuffer {
        return pooledTokensLongBuffer
            ?: throw IllegalStateException("Pooled buffers not initialized")
    }

    /**
     * Get pooled memory replication array.
     * Must call ensurePooledCapacity() first.
     */
    fun getPooledMemoryArray(): Array<Array<FloatArray>> {
        return pooledMemoryArray
            ?: throw IllegalStateException("Pooled buffers not initialized")
    }

    /**
     * Get pooled source mask array.
     * Must call ensurePooledCapacity() first.
     */
    fun getPooledSrcMaskArray(): Array<BooleanArray> {
        return pooledSrcMaskArray
            ?: throw IllegalStateException("Pooled buffers not initialized")
    }

    /**
     * Get current pooled buffer capacity (max beams).
     */
    fun getPooledCapacity(): Int = pooledBufferMaxBeams

    /**
     * Release all allocated buffers.
     * Call this when the predictor is being shut down.
     */
    fun release() {
        pooledTokensByteBuffer = null
        pooledTokensLongBuffer = null
        pooledMemoryArray = null
        pooledSrcMaskArray = null
        pooledBufferMaxBeams = 0

        preallocBatchedTokens = null
        preallocTokensByteBuffer = null
        preallocTokensIntBuffer = null
        preallocSrcLengths = null
        preallocProbs = null
    }
}
