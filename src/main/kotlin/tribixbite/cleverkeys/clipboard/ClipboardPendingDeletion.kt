package tribixbite.cleverkeys.clipboard

import tribixbite.cleverkeys.ClipboardTab

/**
 * GH #175(2): the one clipboard row a swipe (or the TalkBack "Delete" action) has removed but
 * not yet deleted, so Undo needs no database restore path.
 *
 * [begin] hides the row at once; the real delete ([commitAction], the same per-tab service
 * path the edit-mode delete uses, so media files are cleaned up as today) runs when the
 * [UNDO_WINDOW_MS] window ends, or immediately through [commitNow] when anything changes what
 * the user is looking at: the pane closes or is rebuilt, the keyboard hides, the tab, search
 * or filters change, selection mode, edit mode, the tag panel or a bulk action starts, or
 * another row is swiped ([CommitReason]). [undo] un-hides it and writes nothing.
 *
 * Owned by the service-scoped [tribixbite.cleverkeys.ClipboardManager], never by a view:
 * views are rebuilt on theme change, and the state must outlive them until it commits.
 * Memory only — if Android kills the keyboard process inside the window, the hide is lost and
 * the entry simply stays (nothing was deleted yet). That is the accepted failure mode.
 *
 * Identity is the tab plus the row's content: content is the key every per-tab delete uses
 * (`removeHistoryEntry` / `unpinEntry` / `removeFromTodo`), and Pinned and Todos are
 * independent COPY tables, so the same text on another tab is a different row and stays.
 *
 * Main-thread confined. Pure (no Android types) so the state machine is unit-tested with a
 * fake [Scheduler]; the manager supplies a main-looper one.
 */
class ClipboardPendingDeletion(
    private val scheduler: Scheduler,
    private val commitAction: (Pending) -> Unit,
    private val windowMs: Long = UNDO_WINDOW_MS,
) {
    /** Runs [action] once after [delayMs] unless cancelled. */
    fun interface Scheduler {
        fun schedule(delayMs: Long, action: () -> Unit): Cancellable
    }

    /** Handle to a scheduled timeout. */
    fun interface Cancellable {
        fun cancel()
    }

    /** The hidden row: its tab and content (the per-tab delete key). */
    data class Pending(val tab: ClipboardTab, val content: String)

    /** What happened to the pending row, reported to [listener]. */
    enum class Change {
        /** Swiped: the row is hidden and Undo is offered. */
        HIDDEN,
        /** Undone (or the delete failed): the row is visible again. */
        RESTORED,
        /** Deleted for real: the row stays gone. */
        COMMITTED,
    }

    /** Why a pending row was deleted (logged; pinned by tests). */
    enum class CommitReason {
        TIMEOUT,
        NEXT_SWIPE,
        PANE_HIDDEN,
        PANE_INVALIDATED,
        TAB_SWITCH,
        FILTER_CHANGE,
        SELECTION,
        EDIT,
        TAG_PANEL,
        BULK_ACTION,
    }

    /** Notified after every state change, on the main thread. */
    var listener: ((Change, Pending) -> Unit)? = null

    /** The row currently hidden, or null. */
    var pending: Pending? = null
        private set

    /** Reason of the most recent commit, or null if none yet. */
    var lastCommitReason: CommitReason? = null
        private set

    private var timer: Cancellable? = null

    /** Whether the row with [content] on [tab] is hidden. */
    fun hides(tab: ClipboardTab, content: String): Boolean {
        val p = pending ?: return false
        return p.tab == tab && p.content == content
    }

    /**
     * Hide the row with [content] on [tab] and arm the undo window. A row already pending is
     * committed first ([CommitReason.NEXT_SWIPE]): only the latest swipe can be undone.
     */
    fun begin(tab: ClipboardTab, content: String): Pending {
        commitNow(CommitReason.NEXT_SWIPE)
        val p = Pending(tab, content)
        pending = p
        // Identity check: a timer that survived a cancel race can never commit a LATER row.
        timer = scheduler.schedule(windowMs) { if (pending === p) commitNow(CommitReason.TIMEOUT) }
        listener?.invoke(Change.HIDDEN, p)
        return p
    }

    /** Un-hide the pending row without writing anything. False when nothing is pending. */
    fun undo(): Boolean {
        val p = pending ?: return false
        clear()
        listener?.invoke(Change.RESTORED, p)
        return true
    }

    /**
     * Delete the pending row now. False (and nothing happens) when nothing is pending. The
     * state is cleared BEFORE the delete runs, so a re-entrant trigger from the delete's own
     * change notification finds nothing pending. A delete that throws leaves the entry in the
     * database, so the row is reported [Change.RESTORED] rather than hidden forever.
     */
    fun commitNow(reason: CommitReason): Boolean {
        val p = pending ?: return false
        clear()
        lastCommitReason = reason
        val deleted = try {
            commitAction(p)
            true
        } catch (e: Exception) {
            lastError = e
            false
        }
        listener?.invoke(if (deleted) Change.COMMITTED else Change.RESTORED, p)
        return true
    }

    /** The failure of the most recent commit, if it threw (for logging by the owner). */
    var lastError: Exception? = null
        private set

    private fun clear() {
        timer?.cancel()
        timer = null
        pending = null
    }

    companion object {
        /** How long "Deleted · Undo" is offered before the delete runs. */
        const val UNDO_WINDOW_MS = 5_000L
    }
}
