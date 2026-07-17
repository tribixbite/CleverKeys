package tribixbite.cleverkeys.onnx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Pure JVM tests for the top-level [readModelBytes] helper in ModelLoader.kt.
 *
 * These lock down two behaviours:
 *  1. Full drain regardless of read granularity — regression for the truncation
 *     bug where the model was sized by available() + a single read loop, which
 *     silently truncated when a stream returned partial counts.
 *  2. Fail-fast on implausibly small streams (truncated / LFS-pointer / corrupt).
 */
class ModelLoaderReadBytesTest {

    /**
     * Stream that lies about [available] (returns 0) and dribbles content out at
     * most one byte per read() call — the pathological case that broke the old
     * available()-sized reader.
     */
    private class DribblingInputStream(data: ByteArray) : ByteArrayInputStream(data) {
        override fun available(): Int = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            // Deliver at most a single byte per call.
            return super.read(b, off, 1)
        }
    }

    @Test
    fun `reads full content even when stream dribbles one byte per read`() {
        // 200_000 bytes with a repeating, position-dependent pattern so any
        // truncation or reordering is detectable.
        val size = 200_000
        val original = ByteArray(size) { (it % 251).toByte() }
        val stream = DribblingInputStream(original)

        val result = readModelBytes(stream, "test://dribble")

        assertThat(result.size).isEqualTo(size)
        assertThat(result).isEqualTo(original)
    }

    @Test
    fun `throws with source in message when stream is too small`() {
        val tiny = ByteArray(10) { 1 }
        val stream = ByteArrayInputStream(tiny)

        val ex = try {
            readModelBytes(stream, "test://truncated-source")
            null
        } catch (e: IOException) {
            e
        }

        assertThat(ex).isNotNull()
        assertThat(ex!!.message).contains("test://truncated-source")
        assertThat(ex.message).contains("10 bytes")
    }

    @Test
    fun `succeeds at exactly the minimum size`() {
        val exact = ByteArray(MIN_MODEL_SIZE_BYTES) { (it % 251).toByte() }
        val stream = ByteArrayInputStream(exact)

        val result = readModelBytes(stream, "test://exact")

        assertThat(result.size).isEqualTo(MIN_MODEL_SIZE_BYTES)
        assertThat(result).isEqualTo(exact)
    }
}
