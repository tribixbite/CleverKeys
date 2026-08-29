package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ARC-083 — the CTC decode/dispatch seam: a transient decode failure must reach the GEOMETRIC
 * engine, not clear the suggestion bar.
 *
 * The real path is `CtcEngineAdapter.decodeAsync` → `InputCoordinator.performCtcSwipeTyping`'s
 * failure callback → `performGeometricSwipeTyping`, none of which loads in a pure JVM (ONNX,
 * `Context`, `Handler`). What IS testable — and what actually holds the behaviour — is the
 * classification the adapter delegates to [CtcDecodeDelivery]: given a decode body that throws,
 * which of the three callbacks fires. So the decode is a fake that throws on demand and the
 * three destinations are spies; "the geometric path was invoked" is `fellBack`, the exact
 * callback the dispatcher wires to `performGeometricSwipeTyping`.
 *
 * Before the fix there was no third destination at all: the adapter's catch posted
 * `PredictionResult(emptyList(), emptyList())` through the SUCCESS path, which the shared
 * pipeline renders identically to "no candidates". The wiring half is pinned by
 * `CoreImeHygieneDriftTest.aTransientCtcDecodeFailureFallsBackToGeometricRatherThanClearingTheBar`.
 */
class CtcDecodeDeliveryTest {

    /** The three destinations, recorded exactly as the adapter wires them. */
    private class Spy {
        var delivered: String? = null
        var fellBack: Throwable? = null
        var cancelled: Throwable? = null

        fun run(interrupted: Boolean = false, decode: () -> String) =
            CtcDecodeDelivery.deliver(
                decode = decode,
                onSuccess = { delivered = it },
                onFallback = { fellBack = it },
                onCancelled = { cancelled = it },
                interrupted = { interrupted },
            )
    }

    // ── The bug ARC-083 names ───────────────────────────────────────────────────────────

    @Test
    fun `a thrown decode falls back to geometric instead of delivering an empty slate`() {
        val spy = Spy()
        val boom = IllegalStateException("ORT session.run faulted")

        spy.run { throw boom }

        assertThat(spy.fellBack).isSameInstanceAs(boom)
        // The whole point: nothing reached the bar. A delivered empty slate is what used to
        // clear it, and downstream cannot tell that apart from "no candidates".
        assertThat(spy.delivered).isNull()
        assertThat(spy.cancelled).isNull()
    }

    @Test
    fun `unassembled decode inputs fall back too`() {
        val spy = Spy()

        spy.run { throw CtcDecodeDelivery.DecodeInputsUnavailable("ONNX session") }

        assertThat(spy.fellBack).isInstanceOf(CtcDecodeDelivery.DecodeInputsUnavailable::class.java)
        assertThat(spy.fellBack).hasMessageThat().contains("ONNX session")
        assertThat(spy.delivered).isNull()
    }

    @Test
    fun `a clean decode still delivers its slate`() {
        val spy = Spy()

        spy.run { "sydney" }

        assertThat(spy.delivered).isEqualTo("sydney")
        assertThat(spy.fellBack).isNull()
        assertThat(spy.cancelled).isNull()
    }

    // ── Cancellation is NOT a failure ───────────────────────────────────────────────────

    @Test
    fun `a superseded decode is dropped, never re-decoded geometrically`() {
        val spy = Spy()

        spy.run { throw InterruptedException() }

        assertThat(spy.cancelled).isInstanceOf(InterruptedException::class.java)
        // A geometric re-decode here would race the newer swipe's decode for the bar and could
        // commit the word the user already abandoned.
        assertThat(spy.fellBack).isNull()
        assertThat(spy.delivered).isNull()
    }

    @Test
    fun `a wrapped InterruptedException is still cancellation`() {
        val spy = Spy()

        spy.run { throw RuntimeException("decode aborted", InterruptedException()) }

        assertThat(spy.cancelled).isNotNull()
        assertThat(spy.fellBack).isNull()
    }

    @Test
    fun `an arbitrary exception on an INTERRUPTED thread is cancellation, not failure`() {
        val spy = Spy()

        // An interrupt landing inside a native ORT call surfaces as whatever that code throws.
        spy.run(interrupted = true) { throw RuntimeException("aborted mid-run") }

        assertThat(spy.cancelled).isNotNull()
        assertThat(spy.fellBack).isNull()
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val spy = Spy()

        spy.run { throw b }

        assertThat(spy.fellBack).isSameInstanceAs(b)
    }

    // ── Delivery is outside the guarded region ──────────────────────────────────────────

    @Test
    fun `an exception thrown by the delivery callback is not converted into a fallback`() {
        var fellBack = false
        val fromCallback = IllegalStateException("suggestion bar blew up")

        val thrown = runCatching {
            CtcDecodeDelivery.deliver(
                decode = { "word" },
                onSuccess = { throw fromCallback },
                onFallback = { fellBack = true },
            )
        }.exceptionOrNull()

        // It propagates exactly as it did before this seam existed; re-decoding the swipe
        // geometrically on top of an already-delivered slate would be a second commit path.
        assertThat(thrown).isSameInstanceAs(fromCallback)
        assertThat(fellBack).isFalse()
    }

    // ── The classifier itself ───────────────────────────────────────────────────────────

    @Test
    fun `dispositionOf maps the three outcomes`() {
        assertThat(CtcDecodeDelivery.dispositionOf(null, threadInterrupted = false))
            .isEqualTo(CtcDecodeDelivery.Disposition.DELIVER)
        // A clean decode delivers even if a cancel landed after the work finished — the
        // adapter's generation check is what decides whether that slate may still be posted.
        assertThat(CtcDecodeDelivery.dispositionOf(null, threadInterrupted = true))
            .isEqualTo(CtcDecodeDelivery.Disposition.DELIVER)
        assertThat(
            CtcDecodeDelivery.dispositionOf(InterruptedException(), threadInterrupted = false)
        ).isEqualTo(CtcDecodeDelivery.Disposition.DROP)
        assertThat(
            CtcDecodeDelivery.dispositionOf(RuntimeException("boom"), threadInterrupted = false)
        ).isEqualTo(CtcDecodeDelivery.Disposition.FALL_BACK)
    }
}
