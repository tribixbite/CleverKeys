package tribixbite.cleverkeys.ui.settings.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A voice-pack import that outlives the Settings activity (review 2026-11-14, low). It ran in the
 * activity's `lifecycleScope` and its status lived in activity fields, so rotating during an
 * import cancelled the coroutine's resumption: the pack was installed, but "Importing…" vanished,
 * the result was never shown and the microphone was never asked for.
 *
 * The import now runs in a process-lifetime scope; the activity mirrors [state] while it is
 * started and handles [State.micRequestPending] once ([consumeMicRequest]). Pure coroutine code
 * (no Android classes), so it is unit-tested directly.
 */
internal class VoicePackImportTracker(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * @property inProgress an import is running
     * @property status the last import's message, or null
     * @property micRequestPending an import succeeded and the opt-in microphone request
     *   (MicPermissionGate PACK_IMPORTED) has not been made yet
     */
    data class State(
        val inProgress: Boolean = false,
        val status: VoiceImportStatus? = null,
        val micRequestPending: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Guards [abandoned] together with the import's completion. */
    private val lock = Any()

    /** Settings was left for good while an import ran: its result is not news on a later visit. */
    private var abandoned = false

    /**
     * Start an import. [import] runs on the tracker's dispatcher and returns the status to show
     * and whether it succeeded (a success asks for the microphone next). Ignored while another
     * import runs: the import button is disabled then, so a second start is a double tap.
     */
    fun start(importingMessage: String, import: suspend () -> Pair<VoiceImportStatus, Boolean>): Boolean {
        // update() may run its lambda again under contention, so the flag is set on every run.
        var started = false
        _state.update { current ->
            started = !current.inProgress
            if (started) State(inProgress = true, status = VoiceImportStatus.Ok(importingMessage)) else current
        }
        if (!started) return false
        scope.launch {
            try {
                val (status, succeeded) = import()
                synchronized(lock) {
                    // Abandoned: no message and no microphone request out of context later; the
                    // installed pack still shows in the list.
                    _state.value = if (abandoned) State()
                    else State(inProgress = false, status = status, micRequestPending = succeeded)
                    abandoned = false
                }
            } finally {
                // [import] reports failures as a status; anything it lets escape (an Error) must
                // still release the import button, or it stays disabled for the process.
                _state.update { if (it.inProgress) it.copy(inProgress = false, status = null) else it }
            }
        }
        return true
    }

    /** True once per successful import: the caller makes the microphone request now. */
    fun consumeMicRequest(): Boolean {
        var pending = false
        _state.update { current ->
            pending = current.micRequestPending
            if (pending) current.copy(micRequestPending = false) else current
        }
        return pending
    }

    /** Drop the shown message (a pack was removed). */
    fun clearStatus() {
        _state.update { if (it.inProgress) it else it.copy(status = null) }
    }

    /**
     * Settings closed for good (follow-up review 2026-11-15): forget the message and an unmade
     * microphone request, or mark a running import so its result is dropped. Otherwise the next
     * visit, maybe days later, showed "Imported…" and asked for the microphone out of context.
     */
    fun leave() {
        synchronized(lock) {
            if (_state.value.inProgress) abandoned = true else _state.value = State()
        }
    }

    /** A Settings activity is showing the import again: a running import's result is wanted. */
    fun attach() {
        synchronized(lock) { abandoned = false }
    }

    companion object {
        /** The one tracker for the process: Settings activities come and go around it. */
        val shared = VoicePackImportTracker()
    }
}
