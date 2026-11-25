package tribixbite.cleverkeys

/**
 * Reopens the content pane (emoji, clipboard, GIF) that was open at a rotation, once the keyboard
 * is back, and only when the SYSTEM closed it: the rotated app's new window finishes the old input
 * view, which closes the pane (`KeyboardReceiver.resetContentPaneState`), whether the platform
 * restores the keyboard itself or [RotationShowGuard] asks for it. A pane the user closed, or
 * switched away from, after the rotation stays closed (pre-tag review S3, 2026-11-25: until then
 * the service kept the pending pane until it reopened it, so a pane closed by hand came back with
 * the next show inside the guard's window).
 *
 * Pure and single-threaded; the service drives it from the main thread:
 * - [onRotation] at the orientation change, with the pane open then;
 * - [onInputViewFinishing] in `onFinishInputView`, BEFORE the pane is reset, with the pane open
 *   at that moment: still the rotation's pane means the system is closing it; none (or another)
 *   means the user closed or changed it first;
 * - [onUserInput] for a key touch or a hardware key: the user is acting on the keyboard as shown;
 * - [paneToReopen] when the window is shown again.
 *
 * @param P the pane's identity; the service uses the event that opens it (`KeyValue.Event`).
 */
class RotationPaneRestore<P : Any> {

    /** The pane open at the rotation, until it is reopened or given up. */
    private var pending: P? = null

    /** The input view finished with [pending] still open: the system closed it, not the user. */
    private var closedBySystem = false

    /** The pane waiting to be reopened, if any (for the service's fast path and its logs). */
    val pendingPane: P? get() = pending

    /**
     * The orientation changed with [openPane] open (null: none). [guardArmed] is the show guard's
     * state after the same change: a rotation it does not guard reopens nothing.
     */
    fun onRotation(openPane: P?, guardArmed: Boolean) {
        pending = if (guardArmed) openPane else null
        closedBySystem = false
    }

    /**
     * The input view is finishing with [openPane] open (null: none), before the pane is reset.
     * The rotation's pane still open: the system is closing it, so it may come back. Nothing open
     * (or another pane) before the system ever closed it: the user closed or changed it.
     */
    fun onInputViewFinishing(openPane: P?) {
        val p = pending ?: return
        when {
            openPane == p -> closedBySystem = true
            openPane != null || !closedBySystem -> cancel()
        }
    }

    /** The user touched a key or pressed a hardware key: never bring a pane back over that. */
    fun onUserInput() = cancel()

    /**
     * The window is shown again: the pane to reopen now, or null. [guardArmed] is the show guard's
     * state (past its window, or disarmed, nothing comes back) and [openPane] the pane open now.
     * Returns the pane at most once.
     */
    fun paneToReopen(guardArmed: Boolean, openPane: P?): P? {
        val p = pending ?: return null
        if (!guardArmed) {
            cancel()
            return null
        }
        if (openPane != null) {
            // Still open (the system kept the keyboard up): wait for a later close. Another pane
            // open: the user moved on.
            if (openPane != p) cancel()
            return null
        }
        // Closed, but never by the system (the input view did not finish with it open): the user
        // closed it.
        if (!closedBySystem) {
            cancel()
            return null
        }
        cancel()
        return p
    }

    /** Drops the pending pane (also the teardown). */
    fun cancel() {
        pending = null
        closedBySystem = false
    }
}
