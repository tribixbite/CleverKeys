package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.stt.MicPermissionGate.Decision
import tribixbite.cleverkeys.stt.MicPermissionGate.Facts
import tribixbite.cleverkeys.stt.MicPermissionGate.Trigger

/**
 * [MicPermissionGate]: the maintainer rule (2026-10-29) that the microphone is requested only
 * when the user turns voice input on or imports a voice pack.
 */
class MicPermissionGateTest {

    private fun facts(enabled: Boolean = true, pack: Boolean = true, granted: Boolean = false, blocked: Boolean = false, refused: Boolean = false) =
        Facts(enabled, pack, granted, blocked, refused)

    @Test
    fun aKeyPressWithVoiceInputOffNeverPrompts() {
        // Every combination of the other facts: voice off → explain, never REQUEST_PERMISSION.
        for (pack in listOf(false, true)) for (granted in listOf(false, true)) for (blocked in listOf(false, true)) {
            assertWithMessage("pack=$pack granted=$granted blocked=$blocked")
                .that(MicPermissionGate.decide(Trigger.KEY_PRESS, facts(enabled = false, pack = pack, granted = granted, blocked = blocked)))
                .isEqualTo(Decision.EXPLAIN_DISABLED)
        }
    }

    @Test
    fun turningVoiceInputOnAsksOnceThenProceeds() {
        assertThat(MicPermissionGate.decide(Trigger.SETTINGS_TOGGLE_ON, facts(enabled = false))).isEqualTo(Decision.REQUEST_PERMISSION)
        assertThat(MicPermissionGate.decide(Trigger.SETTINGS_TOGGLE_ON, facts(granted = true))).isEqualTo(Decision.PROCEED)
        assertThat(MicPermissionGate.decide(Trigger.SETTINGS_TOGGLE_ON, facts(blocked = true))).isEqualTo(Decision.EXPLAIN_BLOCKED)
    }

    @Test
    fun importingAPackIsAnOptInMoment() {
        assertThat(MicPermissionGate.decide(Trigger.PACK_IMPORTED, facts(enabled = false))).isEqualTo(Decision.REQUEST_PERMISSION)
        assertThat(MicPermissionGate.decide(Trigger.PACK_IMPORTED, facts(granted = true))).isEqualTo(Decision.PROCEED)
    }

    @Test
    fun keyPressOrder() {
        assertThat(MicPermissionGate.decide(Trigger.KEY_PRESS, facts(refused = true, granted = true))).isEqualTo(Decision.FIELD_REFUSED)
        assertThat(MicPermissionGate.decide(Trigger.KEY_PRESS, facts(pack = false, granted = true))).isEqualTo(Decision.NEED_PACK)
        assertThat(MicPermissionGate.decide(Trigger.KEY_PRESS, facts(granted = true))).isEqualTo(Decision.PROCEED)
        // Opted in earlier, permission revoked in system settings since: ask again.
        assertThat(MicPermissionGate.decide(Trigger.KEY_PRESS, facts())).isEqualTo(Decision.REQUEST_PERMISSION)
        assertThat(MicPermissionGate.decide(Trigger.KEY_PRESS, facts(blocked = true))).isEqualTo(Decision.EXPLAIN_BLOCKED)
    }
}
