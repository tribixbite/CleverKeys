package tribixbite.cleverkeys

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

/**
 * MockK JVM tests for the cold-start swipe replay guard (F4).
 *
 * When the neural engine is not yet ready on the first swipe, the commit is deferred until init
 * settles (possibly seconds later). By then the focused input field may have changed; committing
 * this swipe's word into the new/closed field would corrupt unrelated text. The guard compares
 * the captured [InputConnection]/[EditorInfo] against the live ones by reference identity — a
 * field switch replaces both — and drops the replay on mismatch.
 *
 * Exercises the pure companion helper [InputCoordinator.isReplayInputStillCurrent] directly, so
 * no heavy InputCoordinator (Keyboard2View etc.) construction is needed. Uses relaxed mocks only
 * as distinct reference identities; no android methods are invoked.
 */
class InputCoordinatorReplayGuardTest {

    private fun ic(): InputConnection = mockk(relaxed = true)
    private fun editor(): EditorInfo = mockk(relaxed = true)

    @Test
    fun `replay allowed when captured input still current`() {
        val capturedIc = ic()
        val capturedEditor = editor()

        val ok = InputCoordinator.isReplayInputStillCurrent(
            capturedIc = capturedIc,
            capturedEditor = capturedEditor,
            liveIc = capturedIc,       // same instances → same field
            liveEditor = capturedEditor,
            hasLiveInput = true,
        )
        assertThat(ok).isTrue()
    }

    @Test
    fun `replay dropped when input connection changed`() {
        val capturedIc = ic()
        val capturedEditor = editor()

        val ok = InputCoordinator.isReplayInputStillCurrent(
            capturedIc = capturedIc,
            capturedEditor = capturedEditor,
            liveIc = ic(),             // DIFFERENT connection → field switched
            liveEditor = capturedEditor,
            hasLiveInput = true,
        )
        assertThat(ok).isFalse()
    }

    @Test
    fun `replay dropped when editor info changed`() {
        val capturedIc = ic()

        val ok = InputCoordinator.isReplayInputStillCurrent(
            capturedIc = capturedIc,
            capturedEditor = editor(),
            liveIc = capturedIc,
            liveEditor = editor(),     // DIFFERENT editor info → field switched
            hasLiveInput = true,
        )
        assertThat(ok).isFalse()
    }

    @Test
    fun `replay dropped when live connection is null (field torn down)`() {
        val capturedIc = ic()

        val ok = InputCoordinator.isReplayInputStillCurrent(
            capturedIc = capturedIc,
            capturedEditor = editor(),
            liveIc = null,             // no focused field now
            liveEditor = null,
            hasLiveInput = true,
        )
        assertThat(ok).isFalse()
    }

    @Test
    fun `replay dropped when captured connection was null`() {
        val ok = InputCoordinator.isReplayInputStillCurrent(
            capturedIc = null,         // nothing to commit into
            capturedEditor = editor(),
            liveIc = ic(),
            liveEditor = editor(),
            hasLiveInput = true,
        )
        assertThat(ok).isFalse()
    }

    // --- Fallback path: no live provider wired (hasLiveInput = false). Best-effort guard only
    // requires a non-null captured connection. ---

    @Test
    fun `without live provider replay allowed if captured connection non-null`() {
        val ok = InputCoordinator.isReplayInputStillCurrent(
            capturedIc = ic(),
            capturedEditor = editor(),
            liveIc = null,
            liveEditor = null,
            hasLiveInput = false,
        )
        assertThat(ok).isTrue()
    }

    @Test
    fun `without live provider replay dropped if captured connection null`() {
        val ok = InputCoordinator.isReplayInputStillCurrent(
            capturedIc = null,
            capturedEditor = null,
            liveIc = null,
            liveEditor = null,
            hasLiveInput = false,
        )
        assertThat(ok).isFalse()
    }
}
