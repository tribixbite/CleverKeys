package tribixbite.cleverkeys.stt

/**
 * When the keyboard may ask for the microphone, what a voice-key press does, and how the answer
 * to a request is read (pure; `MicPermissionGateTest`). The one policy behind the Settings switch,
 * the pack import and the keyboard's voice key (docs/specs/voice-input.md FR-6/FR-7).
 *
 * Maintainer rule (2026-10-29): RECORD_AUDIO is requested ONLY when the user turns voice input
 * on or imports a voice pack — never at install, never at first launch, never on a key press
 * while voice input is off. Once the user has opted in, a key press may ask again (they may
 * have revoked it in system settings).
 *
 * "Don't ask again" (Saga 2026-10-30): the app remembers a blocked answer
 * ([Facts.permissionBlocked]), but Android has no API that tells an app whether its prompt would
 * still show, and the remembered state goes stale when the user changes the permission in system
 * settings. So:
 *  - an explicit Settings action (the switch, a pack import) ALWAYS asks — when the prompt is
 *    really blocked Android answers "denied" at once without showing anything, and
 *    [afterRequest] reads that as [RequestOutcome.BLOCKED]: explain and offer the app's system
 *    settings page; the switch stays off until the permission is granted;
 *  - a key press trusts the remembered state (asking would flash an activity over the app the
 *    user is typing in only to be refused): it explains in the bar, and a second press opens the
 *    app's system settings page;
 *  - a granted permission always wins over the remembered state.
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
        // Explicit opt-in moments: ask Android whatever the app remembers (see the class doc).
        Trigger.SETTINGS_TOGGLE_ON -> if (facts.permissionGranted) Decision.PROCEED else Decision.REQUEST_PERMISSION
        // Importing a pack is an opt-in moment in its own right (the maintainer rule): ask for
        // the microphone then, so the first key press can start at once.
        Trigger.PACK_IMPORTED -> if (facts.permissionGranted) Decision.PROCEED else Decision.REQUEST_PERMISSION
        Trigger.KEY_PRESS -> when {
            !facts.voiceEnabled -> Decision.EXPLAIN_DISABLED
            facts.fieldRefused -> Decision.FIELD_REFUSED
            !facts.packInstalled -> Decision.NEED_PACK
            facts.permissionGranted -> Decision.PROCEED
            facts.permissionBlocked -> Decision.EXPLAIN_BLOCKED
            else -> Decision.REQUEST_PERMISSION
        }
    }

    /**
     * What the answer to a request means, for Settings and the keyboard's permission activity
     * alike.
     *
     * @property turnsVoiceOn the request was an opt-in (switch, import, key) and it was granted
     * @property blocked what to remember as [Facts.permissionBlocked]
     * @property offersAppSettings explain that Android will not ask again and offer
     *   App info → Permissions (the only place left to grant it)
     */
    enum class RequestOutcome(val turnsVoiceOn: Boolean, val blocked: Boolean, val offersAppSettings: Boolean) {
        GRANTED(turnsVoiceOn = true, blocked = false, offersAppSettings = false),

        /** Denied; Android will show the prompt again next time. */
        DENIED(turnsVoiceOn = false, blocked = false, offersAppSettings = false),

        /** Denied and no rationale: "don't ask again" (or a policy block) — the prompt is gone. */
        BLOCKED(turnsVoiceOn = false, blocked = true, offersAppSettings = true),
    }

    /**
     * Reads a request's answer. [showRationale] is `shouldShowRequestPermissionRationale` read
     * AFTER the answer: false after a denial means Android will not show the prompt again.
     */
    fun afterRequest(granted: Boolean, showRationale: Boolean): RequestOutcome = when {
        granted -> RequestOutcome.GRANTED
        showRationale -> RequestOutcome.DENIED
        else -> RequestOutcome.BLOCKED
    }

    /**
     * Back in Settings after "Open app settings": turn voice input on only when the user was
     * turning it on ([waitingToEnable], set when the blocked explanation sent them there) and the
     * permission is now granted. The switch never shows on while the microphone is unavailable.
     */
    fun turnOnAfterAppSettings(waitingToEnable: Boolean, granted: Boolean): Boolean = waitingToEnable && granted
}
