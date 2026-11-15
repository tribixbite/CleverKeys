package tribixbite.cleverkeys.ui.settings.io

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Test

/**
 * Review 2026-11-14 (low): what the open microphone request is for (turning voice input on, or
 * the follow-up to a pack import) and the App info round trip lived only in process globals.
 * Android can kill Settings while its permission prompt or App info is in front; the recreated
 * activity then received "granted" with no purpose and left voice input off. Both now go into
 * the activity's saved state.
 *
 * The globals are private to SettingsVoiceHandlers.kt, so the test reads them back the way the
 * activity does: save, wipe (a new process), restore, save again and compare.
 */
class VoicePermissionStateTest {

    /** The values a test Bundle holds (android.jar's Bundle is a stub). */
    private val stored = java.util.IdentityHashMap<Bundle, MutableMap<String, Any?>>()

    /** A Bundle backed by a map. */
    private fun bundle(): Bundle {
        val values = mutableMapOf<String, Any?>()
        val b = mockk<Bundle>(relaxed = true) {
            every { putString(any(), any()) } answers { values[firstArg()] = secondArg<String?>() }
            every { putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>() }
            every { getString(any()) } answers { values[firstArg()] as? String }
            every { getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
        }
        stored[b] = values
        return b
    }

    /** What the activity would save right now. */
    private fun snapshot(): Map<String, Any?> {
        val out = bundle()
        saveVoicePermissionState(out)
        return stored.getValue(out)
    }

    @After
    fun tearDown() = restoreVoicePermissionState(bundle())  // back to "nothing pending"

    @Test
    fun aPendingRequestSurvivesANewProcess() {
        // An earlier Settings instance set these and saved them as Android killed it.
        val saved = bundle().apply {
            putString("voice_pending_mic_purpose", "AFTER_IMPORT")
            putBoolean("voice_waiting_after_app_settings", true)
        }
        restoreVoicePermissionState(bundle())  // the new process starts with nothing
        assertThat(snapshot()["voice_pending_mic_purpose"]).isNull()

        restoreVoicePermissionState(saved)

        val now = snapshot()
        assertThat(now["voice_pending_mic_purpose"]).isEqualTo("AFTER_IMPORT")
        assertThat(now["voice_waiting_after_app_settings"]).isEqualTo(true)
    }

    @Test
    fun anUnknownStoredPurposeIsDropped() {
        val saved = bundle().apply { putString("voice_pending_mic_purpose", "SOMETHING_ELSE") }
        restoreVoicePermissionState(saved)
        assertThat(snapshot()["voice_pending_mic_purpose"]).isNull()
    }
}
