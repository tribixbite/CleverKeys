package tribixbite.cleverkeys.onnx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [EncoderWrapper.CloseOnceGuard].
 *
 * CloseOnceGuard has no Android or ONNX Runtime dependencies — it only wraps an
 * [AutoCloseable] and enforces close-once semantics — so it can be exercised
 * directly with a fake resource on the JVM.
 */
class CloseOnceGuardTest {

    /** Records how many times close() was invoked and can optionally throw. */
    private class FakeResource(
        private val throwOnClose: Exception? = null
    ) : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
            throwOnClose?.let { throw it }
        }
    }

    @Test
    fun `close closes the delegate exactly once`() {
        val resource = FakeResource()
        val guard = EncoderWrapper.CloseOnceGuard(resource)

        guard.close()

        assertThat(resource.closeCount).isEqualTo(1)
        assertThat(guard.isClosed).isTrue()
    }

    @Test
    fun `double close is idempotent`() {
        val resource = FakeResource()
        val guard = EncoderWrapper.CloseOnceGuard(resource)

        guard.close()
        guard.close()
        guard.close()

        // Delegate must still only be closed once despite repeated close() calls.
        assertThat(resource.closeCount).isEqualTo(1)
        assertThat(guard.isClosed).isTrue()
    }

    @Test
    fun `close swallows delegate exception and reports it via onError`() {
        val boom = IllegalStateException("boom")
        val resource = FakeResource(throwOnClose = boom)
        val reported = mutableListOf<Exception>()
        val guard = EncoderWrapper.CloseOnceGuard(resource) { e -> reported.add(e) }

        // Must NOT propagate — closing should never mask control flow.
        guard.close()

        assertThat(resource.closeCount).isEqualTo(1)
        assertThat(guard.isClosed).isTrue()
        assertThat(reported).containsExactly(boom)
    }

    @Test
    fun `null resource is closed safely`() {
        // No delegate and no onError → close() is a no-op that still marks closed.
        val guard = EncoderWrapper.CloseOnceGuard(null)

        guard.close()

        assertThat(guard.isClosed).isTrue()
    }

    @Test
    fun `isClosed is false before close`() {
        val guard = EncoderWrapper.CloseOnceGuard(FakeResource())

        assertThat(guard.isClosed).isFalse()
    }
}
