package tribixbite.cleverkeys.clipboard

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.ClipboardTab

/**
 * Service-scoped owner of the clipboard selection and of the confirmed bulk action running on it.
 *
 * The clipboard pane and its list view are recreated freely: a theme change re-inflates them,
 * rotation and app/field switches finish the input view and close the pane, and closing or
 * switching panes evicts it. The selection the user built must survive all of that and end ONLY
 * on Deselect all / Exit selection or a completed bulk action (maintainer request 2026-10-07).
 * So it lives here, in the one instance [tribixbite.cleverkeys.ClipboardManager] keeps for the
 * lifetime of the keyboard service, and every newly inflated
 * [tribixbite.cleverkeys.ClipboardHistoryView] attaches to it.
 *
 * Memory: the [ClipboardSelection] keeps row ids and 64-bit payload versions only — never
 * content — so holding it for a long time costs a few bytes per selected row. It is in memory
 * only: if Android kills the keyboard process the selection is lost (documented in the spec).
 *
 * A confirmed action ([runConfirmed]) runs on this holder's own scope, not a view's: once the
 * user confirms, a pane teardown, keyboard hide or view detach cannot cancel the transaction
 * before it starts or drop its completion (device report 2026-10-07: the confirmation was torn
 * down mid-tap and nothing was deleted).
 *
 * Main-thread confined except for the work lambda, which runs on [ioDispatcher].
 */
class ClipboardSelectionHolder(
    private val mainDispatcher: CoroutineDispatcher? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The active selection, or null outside selection mode. */
    var selection: ClipboardSelection? = null
        private set

    /** Whether a confirmed bulk action is executing; selection edits are refused meanwhile. */
    var running: Boolean = false
        private set

    /**
     * Invoked on the main thread after a confirmed action finishes (success or failure). The
     * list view currently attached sets it so the visible pane — which may not be the one that
     * started the action — refreshes its rows, count and chrome.
     */
    var observer: (() -> Unit)? = null

    /** Tab the selection belongs to (row ids are per table), or null when not selecting. */
    val tab: ClipboardTab? get() = selection?.tab

    // Created on first use: Dispatchers.Main is unavailable to JVM tests that never run actions.
    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + (mainDispatcher ?: Dispatchers.Main.immediate))
    }

    /**
     * Start (or keep) selection mode on [tab]. An existing selection on the same tab is kept
     * unchanged; one on another tab is replaced, since its row ids name another table.
     */
    fun start(tab: ClipboardTab): ClipboardSelection {
        selection?.takeIf { it.tab == tab }?.let { return it }
        return ClipboardSelection(tab).also { selection = it }
    }

    /** End selection mode, forgetting every selected row. */
    fun end() {
        selection = null
    }

    /**
     * Run a confirmed bulk action exactly once. [work] runs on [ioDispatcher] immediately and is
     * not tied to any view or pane lifecycle. On success the selection ends (the action completed
     * the user's batch); on failure it is kept for a retry. [finished] then [observer] run on the
     * main dispatcher. Returns false (and does nothing) while another action runs.
     */
    fun <T> runConfirmed(work: () -> Result<T>, finished: (Result<T>) -> Unit): Boolean {
        if (running) return false
        running = true
        scope.launch {
            val result = try {
                withContext(ioDispatcher) { work() }
            } catch (e: Exception) {
                Result.failure(e)
            }
            running = false
            if (result.isSuccess) end()
            finished(result)
            observer?.invoke()
        }
        return true
    }
}
