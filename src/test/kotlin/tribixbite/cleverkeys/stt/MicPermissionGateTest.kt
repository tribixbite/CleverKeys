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
    }

    /**
     * Saga 2026-10-30: the app remembered "don't ask again" after the system state had changed, so
     * the switch neither prompted nor responded and the user was stuck. An explicit Settings action
     * asks Android every time: when the prompt is really blocked Android answers "denied" at once
     * without showing anything, and [MicPermissionGate.afterRequest] then explains.
     */
    @Test
    fun anExplicitSettingsActionAsksEvenWhenTheRememberedStateSaysBlocked() {
        assertThat(MicPermissionGate.decide(Trigger.SETTINGS_TOGGLE_ON, facts(enabled = false, blocked = true))).isEqualTo(Decision.REQUEST_PERMISSION)
        assertThat(MicPermissionGate.decide(Trigger.PACK_IMPORTED, facts(enabled = false, blocked = true))).isEqualTo(Decision.REQUEST_PERMISSION)
        // Granted wins over a stale remembered block.
        assertThat(MicPermissionGate.decide(Trigger.SETTINGS_TOGGLE_ON, facts(granted = true, blocked = true))).isEqualTo(Decision.PROCEED)
        assertThat(MicPermissionGate.decide(Trigger.KEY_PRESS, facts(granted = true, blocked = true))).isEqualTo(Decision.PROCEED)
    }

    /** One reading of the system prompt's answer, for Settings and the keyboard's activity alike. */
    @Test
    fun theAnswerOfARequestIsReadOneWay() {
        // Granted: voice input turns on, the remembered block is cleared, nothing to explain.
        val granted = MicPermissionGate.afterRequest(granted = true, showRationale = false)
        assertThat(granted).isEqualTo(MicPermissionGate.RequestOutcome.GRANTED)
        assertThat(granted.turnsVoiceOn).isTrue()
        assertThat(granted.blocked).isFalse()
        assertThat(granted.offersAppSettings).isFalse()
        // Denied, Android would ask again: voice stays off, no app-settings detour.
        val denied = MicPermissionGate.afterRequest(granted = false, showRationale = true)
        assertThat(denied).isEqualTo(MicPermissionGate.RequestOutcome.DENIED)
        assertThat(denied.turnsVoiceOn).isFalse()
        assertThat(denied.blocked).isFalse()
        assertThat(denied.offersAppSettings).isFalse()
        // Denied and no rationale: Android will not show the prompt again — explain and offer
        // App info → Permissions.
        val blocked = MicPermissionGate.afterRequest(granted = false, showRationale = false)
        assertThat(blocked).isEqualTo(MicPermissionGate.RequestOutcome.BLOCKED)
        assertThat(blocked.turnsVoiceOn).isFalse()
        assertThat(blocked.blocked).isTrue()
        assertThat(blocked.offersAppSettings).isTrue()
    }

    /** "Open app settings" from a blocked switch: back in Settings, the switch follows the real state. */
    @Test
    fun comingBackFromAppSettingsTurnsVoiceOnOnlyWhenGranted() {
        assertThat(MicPermissionGate.turnOnAfterAppSettings(waitingToEnable = true, granted = true)).isTrue()
        assertThat(MicPermissionGate.turnOnAfterAppSettings(waitingToEnable = true, granted = false)).isFalse()
        // Nobody asked to turn it on (the user only looked): granting there does not flip the switch.
        assertThat(MicPermissionGate.turnOnAfterAppSettings(waitingToEnable = false, granted = true)).isFalse()
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
