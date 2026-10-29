package tribixbite.cleverkeys.stt

/** The voice controller's lifecycle states (docs/specs/voice-input.md "State Management"). */
enum class VoiceState {
    /** Not listening; the microphone is closed. */
    IDLE,

    /** The user asked to listen; the engine is loading / the recorder is opening. */
    PREPARING,

    /** Recording and recognising. */
    LISTENING,

    /** Stop was requested: the recorder is closed, the last audio is being decoded. */
    FINISHING,
}

/** Inputs to [VoiceSessionMachine]. */
sealed interface VoiceEvent {
    /** The key/command was pressed. */
    data object Toggle : VoiceEvent

    /** The engine is loaded and the recorder is open. */
    data object Ready : VoiceEvent

    /** A partial hypothesis arrived (LISTENING only). */
    data class Partial(val hypothesis: SpeechHypothesis) : VoiceEvent

    /** The final hypothesis arrived after a stop. */
    data class Final(val hypothesis: SpeechHypothesis) : VoiceEvent

    /** The bar's ✕, keyboard hidden, service destroyed: drop the composing tail. */
    data object Cancel : VoiceEvent

    /**
     * Another field took the input. The keyboard's connection already belongs to the new
     * field, and the old one finished its own composition on blur (Chrome and EditText both
     * keep the underlined words), so nothing may be written: [VoiceAction.Release].
     */
    data object FieldChanged : VoiceEvent

    /** Load, capture or inference failed. */
    data class Failed(val reason: VoiceFailure) : VoiceEvent
}

/** Why a session ended without a final result. Rendered by the controller. */
enum class VoiceFailure { NO_PACK, PACK_UNREADABLE, ENGINE_ERROR, MIC_UNAVAILABLE, PERMISSION_MISSING, FIELD_REFUSED }

/** What the controller must do after a transition (side effects stay out of the machine). */
sealed interface VoiceAction {
    /** Load the engine and open the recorder. */
    data object Prepare : VoiceAction

    /** Start feeding recorded audio to the session. */
    data object StartStreaming : VoiceAction

    /** Close the recorder, flush the buffered audio, ask for the final hypothesis. */
    data object Finish : VoiceAction

    /** Apply [hypothesis] to the field. */
    data class Apply(val hypothesis: SpeechHypothesis) : VoiceAction

    /** Stop everything and drop the composing tail (cancel). */
    data object Abort : VoiceAction

    /**
     * Stop everything WITHOUT any text operation (field change): a clear sent now would reach
     * the new field and `setComposingText("")` would replace its selection (Saga 2026-10-29).
     */
    data object Release : VoiceAction

    /** Session over (final applied): release the recorder/session, hide the indicator. */
    data object Done : VoiceAction

    /** Tell the user why the session ended, then behave like [Abort]. */
    data class Report(val reason: VoiceFailure) : VoiceAction
}

/**
 * The pure state machine behind voice input (`VoiceSessionMachineTest`).
 *
 * ```
 * IDLE ─Toggle→ PREPARING ─Ready→ LISTENING ─Toggle→ FINISHING ─Final→ IDLE (Apply, Done)
 *   any state ─Cancel→ IDLE (Abort)          any active state ─Failed→ IDLE (Report)
 *   any state ─FieldChanged→ IDLE (Release: no text operation)
 * PREPARING ─Toggle→ IDLE (Abort): a second tap while loading is "never mind".
 * ```
 * Events that do not fit the current state (a partial after cancel, a late Ready) are
 * ignored: the worker thread can deliver them after the user already moved on.
 */
class VoiceSessionMachine {

    var state: VoiceState = VoiceState.IDLE
        private set

    /** Feed [event]; returns the actions to perform, in order (empty = ignored). */
    fun on(event: VoiceEvent): List<VoiceAction> = when (event) {
        VoiceEvent.Toggle -> when (state) {
            VoiceState.IDLE -> move(VoiceState.PREPARING, VoiceAction.Prepare)
            VoiceState.PREPARING -> move(VoiceState.IDLE, VoiceAction.Abort)
            VoiceState.LISTENING -> move(VoiceState.FINISHING, VoiceAction.Finish)
            VoiceState.FINISHING -> emptyList() // already stopping; the final result is on its way
        }
        VoiceEvent.Ready ->
            if (state == VoiceState.PREPARING) move(VoiceState.LISTENING, VoiceAction.StartStreaming) else emptyList()
        is VoiceEvent.Partial ->
            if (state == VoiceState.LISTENING) listOf(VoiceAction.Apply(event.hypothesis)) else emptyList()
        is VoiceEvent.Final ->
            if (state == VoiceState.FINISHING) {
                move(VoiceState.IDLE, VoiceAction.Apply(event.hypothesis), VoiceAction.Done)
            } else {
                emptyList()
            }
        VoiceEvent.Cancel ->
            if (state == VoiceState.IDLE) emptyList() else move(VoiceState.IDLE, VoiceAction.Abort)
        VoiceEvent.FieldChanged ->
            if (state == VoiceState.IDLE) emptyList() else move(VoiceState.IDLE, VoiceAction.Release)
        is VoiceEvent.Failed ->
            if (state == VoiceState.IDLE) emptyList() else move(VoiceState.IDLE, VoiceAction.Report(event.reason))
    }

    /** True while the microphone may be open (PREPARING/LISTENING) or decoding (FINISHING). */
    val isActive: Boolean get() = state != VoiceState.IDLE

    private fun move(to: VoiceState, vararg actions: VoiceAction): List<VoiceAction> {
        state = to
        return actions.toList()
    }
}
