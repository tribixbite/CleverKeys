package tribixbite.cleverkeys.ui.settings.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * A voice-pack import that outlives the Settings activity (review 2026-11-14, low). It ran in the
 * activity's `lifecycleScope` and its status lived in activity fields, so rotating during an
 * import cancelled the coroutine's resumption: the pack was installed, but "Importing…" vanished,
 * the result was never shown and the microphone was never asked for.
 *
 * The generic [ImportTracker] (extracted 2026-11-25 for the other Settings imports) with the
 * voice section's vocabulary: the status is the line under the Import button, and the one-shot
 * follow-up is the opt-in microphone request (MicPermissionGate PACK_IMPORTED) after a
 * successful import, made once from whichever Settings activity is in front ([consumeMicRequest]).
 */
internal class VoicePackImportTracker(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImportTracker<VoiceImportStatus, Unit>(dispatcher) {

    /**
     * Start an import. [import] runs on the tracker's dispatcher and returns the status to show
     * and whether it succeeded (a success asks for the microphone next). Ignored while another
     * import runs: the import button is disabled then, so a second start is a double tap.
     */
    fun start(importingMessage: String, import: suspend () -> Pair<VoiceImportStatus, Boolean>): Boolean =
        start(VoiceImportStatus.Ok(importingMessage)) {
            val (status, succeeded) = import()
            Outcome(status, if (succeeded) Unit else null)
        }

    /** True once per successful import: the caller makes the microphone request now. */
    fun consumeMicRequest(): Boolean = consumeEvent() != null

    companion object {
        /** The one tracker for the process: Settings activities come and go around it. */
        val shared = VoicePackImportTracker()
    }
}

/** A successful import whose microphone request has not been made yet. */
internal val ImportTracker.State<VoiceImportStatus, Unit>.micRequestPending: Boolean
    get() = event != null
