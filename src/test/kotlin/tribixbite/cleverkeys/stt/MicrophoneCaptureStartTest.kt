package tribixbite.cleverkeys.stt

import android.media.AudioRecord
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Review 2026-11-14 (Voice, low): [MicrophoneCapture.start] wrapped EVERY exception from the
 * recorder's construction in [MicUnavailableException], so the controller's
 * `catch (e: SecurityException)` (the "microphone permission missing" message) could never run
 * and a revoked permission was reported as a busy microphone. A [SecurityException] now reaches
 * the caller as itself; other failures are still "unavailable".
 *
 * Mock tier: `AudioRecord.getMinBufferSize` is mocked over the android.jar stub.
 */
class MicrophoneCaptureStartTest {

    @Before
    fun setUp() {
        mockkStatic(AudioRecord::class)
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 3_200
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun aSecurityExceptionReachesTheCaller() {
        val mic = MicrophoneCapture(16_000) { _, _ -> throw SecurityException("RECORD_AUDIO revoked") }
        val thrown = runCatching { mic.start() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun otherFailuresAreStillUnavailable() {
        val mic = MicrophoneCapture(16_000) { _, _ -> throw IllegalArgumentException("bad rate") }
        val thrown = runCatching { mic.start() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(MicUnavailableException::class.java)
    }
}
