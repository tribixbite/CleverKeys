package tribixbite.cleverkeys.ui.settings.io

import kotlinx.coroutines.CancellationException
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
 * An import (or other file operation) started from Settings that must outlive the activity that
 * started it.
 *
 * Settings is recreated on every rotation (no `configChanges`), and its `lifecycleScope` is
 * cancelled with the old instance. An import running there was cancelled half way: a GIF pack
 * could be left half-installed, and the backup, clipboard and dictionary imports finished their
 * writes but reported failure and skipped their follow-up (pre-tag review 2026-11-25 S2; the
 * voice-pack import, review 2026-11-14, was the first to move out).
 *
 * The work runs in a process-lifetime scope instead. What it produces is split in two:
 * - [State.status] ([S]) is a message the screen shows for as long as it is relevant (the
 *   voice section's "Imported…"); every activity instance reads the same value;
 * - [State.event] ([E]) is a follow-up that must happen ONCE, on whichever activity is in front
 *   when the work ends (a result dialog, a reload of the shown settings, the microphone
 *   request). The activity takes it with [consumeEvent].
 *
 * Settings activities register with [attach] and [detach]. When the last one leaves for good
 * (finishing, not a rotation) an unconsumed event and the message are dropped, and a running
 * operation's result is dropped when it ends: its side effects belong in the work itself, which
 * runs to completion either way. Counting owners rather than keeping one flag fixes an ordering
 * fault (review 2026-11-25 §4): a finishing activity's onDestroy can run AFTER the next
 * Settings' onCreate, and the old single flag then marked the new one's import abandoned.
 *
 * Pure coroutine code (no Android classes), unit-tested directly (`ImportTrackerTest`).
 *
 * @param S the shown status, or [Unit] when the screen keeps its own
 * @param E the one-shot follow-up
 */
internal open class ImportTracker<S : Any, E : Any>(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * @property inProgress an operation is running
     * @property status the message to show, or null
     * @property events the follow-ups not yet taken by an activity, oldest first
     */
    data class State<S : Any, E : Any>(
        val inProgress: Boolean = false,
        val status: S? = null,
        val events: List<E> = emptyList(),
    ) {
        /** The oldest follow-up not yet taken, or null. */
        val event: E? get() = events.firstOrNull()
    }

    /** What a finished operation leaves: the [status] to show and the [event] to deliver. */
    data class Outcome<S : Any, E : Any>(val status: S? = null, val event: E? = null)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(State<S, E>())
    val state: StateFlow<State<S, E>> = _state.asStateFlow()

    /** Guards [owners] and [abandoned] together with an operation's completion. */
    private val lock = Any()

    /** The Settings activities alive now (identity: a recreated activity is a new owner). */
    private val owners = HashSet<Any>()

    /** Settings was left for good while an operation ran: its result is not news later. */
    private var abandoned = false

    /**
     * Start an operation, showing [progress] while it runs. [work] runs on the tracker's
     * dispatcher and returns its [Outcome]. Ignored (false) while another operation runs: the
     * screens disable their buttons then, so a second start is a double tap or a second shared
     * file arriving mid-import.
     */
    fun start(progress: S?, work: suspend () -> Outcome<S, E>): Boolean {
        // update() may run its lambda again under contention, so the flag is set on every run.
        var started = false
        _state.update { current ->
            started = !current.inProgress
            // A follow-up not yet taken stays (Fable audit D2): an import that finished while
            // Settings was stopped, then a second share arriving before onStart, lost the first
            // result when this replaced the whole state.
            if (started) State(inProgress = true, status = progress, events = current.events) else current
        }
        if (!started) return false
        scope.launch {
            try {
                val outcome = work()
                synchronized(lock) {
                    _state.update { current ->
                        if (abandoned) State()
                        else State(inProgress = false, status = outcome.status, events = current.events + listOfNotNull(outcome.event))
                    }
                    abandoned = false
                }
            } finally {
                // [work] reports failures in its Outcome; anything it lets escape (an Error, a
                // cancellation of its own) must still release the buttons, or they stay disabled
                // for the life of the process.
                _state.update { if (it.inProgress) it.copy(inProgress = false, status = null) else it }
            }
        }
        return true
    }

    /**
     * The oldest pending follow-up, once: the caller runs it now. Null when there is none. A next
     * one, if any, is the new state's [State.event], so a collector takes them in turn.
     */
    fun consumeEvent(): E? {
        var taken: E? = null
        _state.update { current ->
            taken = current.event
            if (taken != null) current.copy(events = current.events.drop(1)) else current
        }
        return taken
    }

    /** Drop the shown message (what it named was removed); a running operation keeps its own. */
    fun clearStatus() {
        _state.update { if (it.inProgress) it else it.copy(status = null) }
    }

    /** [owner] (a Settings activity) shows this tracker's state: a running result is wanted. */
    fun attach(owner: Any) {
        synchronized(lock) {
            owners += owner
            abandoned = false
        }
    }

    /**
     * [owner] is destroyed. [finishing] is false for a recreation (rotation), whose successor
     * attaches next; true when the user left it. When no owner is left after a finishing one,
     * Settings was closed for good: forget the message and an untaken follow-up, or mark a
     * running operation so its result is dropped. Otherwise the next visit, maybe days later,
     * showed an old "Imported…" and ran its follow-up out of context.
     */
    fun detach(owner: Any, finishing: Boolean) {
        synchronized(lock) {
            owners -= owner
            if (!finishing || owners.isNotEmpty()) return
            if (_state.value.inProgress) abandoned = true else _state.value = State()
        }
    }
}

/**
 * Run [work] and map an [Exception] it throws to [onFailure]'s outcome, but never a
 * [CancellationException]: a cancelled operation did not fail, and reporting one as "Unknown
 * error" (what `catch (e: Exception)` did in the old activity-scoped imports) told the user an
 * import that had completed had failed. The cancellation propagates to the caller instead.
 */
internal inline fun <T> catchingFailures(
    work: () -> T,
    onFailure: (Exception) -> T,
): T = try {
    work()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onFailure(e)
}
