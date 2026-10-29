package tribixbite.cleverkeys.stt

/**
 * When the keyboard may ask for the microphone, and what a voice-key press does
 * (pure; `MicPermissionGateTest`).
 *
 * Maintainer rule (2026-10-29): RECORD_AUDIO is requested ONLY when the user turns voice input
 * on or imports a voice pack — never at install, never at first launch, never on a key press
 * while voice input is off. Once the user has opted in, a key press may ask again (they may
 * have revoked it in system settings), unless Android stopped showing the prompt ("don't ask
 * again"), in which case only the system settings page can grant it.
 */
object MicPermissionGate {

    /** The moment the decision is taken. */
    enum class Trigger {
        /** Settings → Voice input switch turned ON. */
        SETTINGS_TOGGLE_ON,

        /** A voice pack was imported successfully. */
        PACK_IMPORTED,

        /** The voice_input key/command was pressed in the keyboard. */
        KEY_PRESS,
    }

    /** The facts the decision reads. */
    data class Facts(
        val voiceEnabled: Boolean,
        val packInstalled: Boolean,
        val permissionGranted: Boolean,
        /** The last request was denied with "don't ask again" (the prompt no longer shows). */
        val permissionBlocked: Boolean,
        /** The focused field refuses dictation (password). KEY_PRESS only. */
        val fieldRefused: Boolean = false,
    )

    enum class Decision {
        /** Everything is in place: start listening (KEY_PRESS) / keep the switch on. */
        PROCEED,

        /** Show the system permission prompt (through the transparent activity). */
        REQUEST_PERMISSION,

        /** The prompt cannot be shown any more: explain and offer the app's system settings. */
        EXPLAIN_BLOCKED,

        /** Key pressed with voice input off: explain where to turn it on. Never prompts. */
        EXPLAIN_DISABLED,

        /** Voice input on but no pack installed: point at the import. */
        NEED_PACK,

        /** The field refuses dictation (password); say so, do nothing else. */
        FIELD_REFUSED,
    }

    fun decide(trigger: Trigger, facts: Facts): Decision = when (trigger) {
        Trigger.SETTINGS_TOGGLE_ON -> permissionStep(facts)
        // Importing a pack is an opt-in moment in its own right (the maintainer rule): ask for
        // the microphone then, so the first key press can start at once.
        Trigger.PACK_IMPORTED -> permissionStep(facts)
        Trigger.KEY_PRESS -> when {
            !facts.voiceEnabled -> Decision.EXPLAIN_DISABLED
            facts.fieldRefused -> Decision.FIELD_REFUSED
            !facts.packInstalled -> Decision.NEED_PACK
            else -> permissionStep(facts)
        }
    }

    private fun permissionStep(facts: Facts): Decision = when {
        facts.permissionGranted -> Decision.PROCEED
        facts.permissionBlocked -> Decision.EXPLAIN_BLOCKED
        else -> Decision.REQUEST_PERMISSION
    }
}
