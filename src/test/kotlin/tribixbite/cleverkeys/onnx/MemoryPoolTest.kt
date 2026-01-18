package tribixbite.cleverkeys.onnx

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.nio.ByteOrder

/**
 * Pure JVM tests for MemoryPool buffer management.
 */
class MemoryPoolTest {

    private lateinit var pool: MemoryPool

    @Before
    fun setUp() {
        pool = MemoryPool()
    }

    // =========================================================================
    // Pre-allocated buffer tests
    // =========================================================================

    @Test
    fun `initializePreallocatedBuffers creates correct sizes`() {
        pool.initializePreallocatedBuffers(
            maxBeams = 8,
            decoderSeqLength = 20,
            vocabSize = 1000
        )

        val tokens = pool.getPreallocBatchedTokens()
        assertThat(tokens).hasLength(8)
        assertThat(tokens[0]).hasLength(20)

        val srcLengths = pool.getPreallocSrcLengths()
        assertThat(srcLengths).hasLength(8)

        val probs = pool.getPreallocProbs()
        assertThat(probs).hasLength(1000)
    }

    @Test
    fun `preallocated ByteBuffer has correct capacity`() {
        pool.initializePreallocatedBuffers(
            maxBeams = 8,
            decoderSeqLength = 20,
            vocabSize = 1000
        )

        val buffer = pool.getPreallocTokensByteBuffer()
        // 8 beams * 20 tokens * 4 bytes per int = 640 bytes
        assertThat(buffer.capacity()).isEqualTo(8 * 20 * 4)
        assertThat(buffer.order()).isEqualTo(ByteOrder.nativeOrder())
    }

    @Test
    fun `preallocated IntBuffer has correct capacity`() {
        pool.initializePreallocatedBuffers(
            maxBeams = 8,
            decoderSeqLength = 20,
            vocabSize = 1000
        )

        val intBuffer = pool.getPreallocTokensIntBuffer()
        // 8 beams * 20 tokens = 160 ints
        assertThat(intBuffer.capacity()).isEqualTo(8 * 20)
    }

    @Test(expected = IllegalStateException::class)
    fun `getPreallocBatchedTokens throws if not initialized`() {
        pool.getPreallocBatchedTokens()
    }

    @Test(expected = IllegalStateException::class)
    fun `getPreallocTokensByteBuffer throws if not initialized`() {
        pool.getPreallocTokensByteBuffer()
    }

    @Test(expected = IllegalStateException::class)
    fun `getPreallocTokensIntBuffer throws if not initialized`() {
        pool.getPreallocTokensIntBuffer()
    }

    @Test(expected = IllegalStateException::class)
    fun `getPreallocSrcLengths throws if not initialized`() {
        pool.getPreallocSrcLengths()
    }

    @Test(expected = IllegalStateException::class)
    fun `getPreallocProbs throws if not initialized`() {
        pool.getPreallocProbs()
    }

    // =========================================================================
    // Pooled buffer tests
    // =========================================================================

    @Test
    fun `ensurePooledCapacity creates correct sizes`() {
        pool.ensurePooledCapacity(
            newCapacity = 16,
            maxSeqLength = 100,
            hiddenDim = 256
        )

        assertThat(pool.getPooledCapacity()).isEqualTo(16)

        val longBuffer = pool.getPooledTokensLongBuffer()
        assertThat(longBuffer.capacity()).isEqualTo(16 * 100)

        val memoryArray = pool.getPooledMemoryArray()
        assertThat(memoryArray).hasLength(16)
        assertThat(memoryArray[0]).hasLength(100)
        assertThat(memoryArray[0][0]).hasLength(256)

        val srcMaskArray = pool.getPooledSrcMaskArray()
        assertThat(srcMaskArray).hasLength(16)
        assertThat(srcMaskArray[0]).hasLength(100)
    }

    @Test
    fun `ensurePooledCapacity is idempotent for same capacity`() {
        pool.ensurePooledCapacity(16, 100, 256)
        val firstBuffer = pool.getPooledTokensLongBuffer()

        pool.ensurePooledCapacity(16, 100, 256)
        val secondBuffer = pool.getPooledTokensLongBuffer()

        assertThat(firstBuffer).isSameInstanceAs(secondBuffer)
    }

    @Test
    fun `ensurePooledCapacity skips if already sufficient`() {
        pool.ensurePooledCapacity(32, 100, 256)
        val firstBuffer = pool.getPooledTokensLongBuffer()

        // Request smaller capacity - should not reallocate
        pool.ensurePooledCapacity(16, 100, 256)
        val secondBuffer = pool.getPooledTokensLongBuffer()

        assertThat(firstBuffer).isSameInstanceAs(secondBuffer)
        assertThat(pool.getPooledCapacity()).isEqualTo(32)
    }

    @Test
    fun `ensurePooledCapacity expands for larger capacity`() {
        pool.ensurePooledCapacity(8, 100, 256)
        assertThat(pool.getPooledCapacity()).isEqualTo(8)

        pool.ensurePooledCapacity(16, 100, 256)
        assertThat(pool.getPooledCapacity()).isEqualTo(16)
    }

    @Test(expected = IllegalStateException::class)
    fun `getPooledTokensLongBuffer throws if not initialized`() {
        pool.getPooledTokensLongBuffer()
    }

    @Test(expected = IllegalStateException::class)
    fun `getPooledMemoryArray throws if not initialized`() {
        pool.getPooledMemoryArray()
    }

    @Test(expected = IllegalStateException::class)
    fun `getPooledSrcMaskArray throws if not initialized`() {
        pool.getPooledSrcMaskArray()
    }

    // =========================================================================
    // Release tests
    // =========================================================================

    @Test
    fun `release clears preallocated buffers`() {
        pool.initializePreallocatedBuffers(8, 20, 1000)
        pool.release()

        assertThat(pool.getPooledCapacity()).isEqualTo(0)
    }

    @Test(expected = IllegalStateException::class)
    fun `release makes preallocated buffers inaccessible`() {
        pool.initializePreallocatedBuffers(8, 20, 1000)
        pool.release()

        pool.getPreallocBatchedTokens()
    }

    @Test(expected = IllegalStateException::class)
    fun `release makes pooled buffers inaccessible`() {
        pool.ensurePooledCapacity(16, 100, 256)
        pool.release()

        pool.getPooledTokensLongBuffer()
    }

    @Test
    fun `release is idempotent`() {
        pool.initializePreallocatedBuffers(8, 20, 1000)
        pool.ensurePooledCapacity(16, 100, 256)

        pool.release()
        pool.release() // Should not throw

        assertThat(pool.getPooledCapacity()).isEqualTo(0)
    }

    // =========================================================================
    // Integration tests
    // =========================================================================

    @Test
    fun `can reinitialize after release`() {
        pool.initializePreallocatedBuffers(8, 20, 1000)
        pool.release()

        pool.initializePreallocatedBuffers(4, 10, 500)

        val tokens = pool.getPreallocBatchedTokens()
        assertThat(tokens).hasLength(4)
        assertThat(tokens[0]).hasLength(10)
    }

    @Test
    fun `both buffer types can coexist`() {
        pool.initializePreallocatedBuffers(8, 20, 1000)
        pool.ensurePooledCapacity(16, 100, 256)

        // Both should be accessible
        assertThat(pool.getPreallocBatchedTokens()).hasLength(8)
        assertThat(pool.getPooledMemoryArray()).hasLength(16)
    }

    @Test
    fun `buffers can be written to and read from`() {
        pool.initializePreallocatedBuffers(2, 5, 100)

        val tokens = pool.getPreallocBatchedTokens()
        tokens[0][0] = 42
        tokens[1][4] = 99

        // Values persist
        assertThat(pool.getPreallocBatchedTokens()[0][0]).isEqualTo(42)
        assertThat(pool.getPreallocBatchedTokens()[1][4]).isEqualTo(99)
    }

    @Test
    fun `probs array can be used for softmax output`() {
        pool.initializePreallocatedBuffers(1, 10, 27)

        val probs = pool.getPreallocProbs()
        // Simulate softmax output
        for (i in probs.indices) {
            probs[i] = 1f / 27f
        }

        // Values persist
        assertThat(pool.getPreallocProbs()[0]).isWithin(0.001f).of(1f / 27f)
        assertThat(pool.getPreallocProbs().sum()).isWithin(0.001f).of(1f)
    }
}
