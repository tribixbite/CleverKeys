package tribixbite.cleverkeys

/**
 * Keeps the keyboard on screen across a rotation when the app's own relaunch hides it.
 *
 * Measured on the Pixel 8 Pro (Android 17, 2026-11-19) in the Google app's search box: rotating
 * with the keyboard up hid CleverKeys 8 times out of 8, Gboard 5 of 8 and SwiftKey 0 of 8. The
 * app recreates its activity on rotation and the new window starts with the IME not requested.
 * The system restores the keyboard for it (`SHOW_RESTORE_IME_VISIBILITY`) only when the IME's
 * own re-show after the configuration change (`RESET_NEW_CONFIGURATION`) comes after the old
 * window is gone: SwiftKey's came 115 ms after the rotation, CleverKeys' 4 ms, so the system
 * hid CleverKeys for the new window (`ISP_UPDATE_CONTROL_FOR_TARGET`) and never restored it.
 * The IME cannot choose when the platform re-shows it, so instead this asks to be shown again.
 *
 * State machine, pure and single-threaded (the service drives it from the main thread with
 * `SystemClock.uptimeMillis()`):
 * - [onOrientationChanged] arms the guard while the keyboard is on screen over an editor.
 * - [onWindowHidden] reports whether a hide inside the window should be undone: not one the user
 *   made (Back, the hide_keyboard key, the tile).
 * - [onStartInput] records that the app started input afresh (`restarting == false`): its window
 *   was recreated, the case the platform's restore misses. Nothing is shown without it. Discord,
 *   which keeps its window on rotation and hides the keyboard itself (`hide(ime())`), starts no
 *   input then, so its hide stands: on a wide layout (the Pixel at 360 dpi) it hides every
 *   keyboard and slides its channel list over the still-bound chat box in portrait. The one
 *   exception is a rotation with a pane open ([onOrientationChanged]'s `paneOpen`): at the S25U's
 *   1080x2340 / 420 dpi Discord kept its chat on screen and hid the keyboard only when it was
 *   taller than the new screen at the rotation (a pane open: 9 of 10 rotations to landscape;
 *   none of 20 without one, none of 5 with the pane closed just before; maintainer report
 *   2026-11-20). The IME learns of the rotation with the app, too late to be shorter, so that
 *   hide is undone like a relaunch's.
 * - [check] then runs every [RETRY_INTERVAL_MS] ([SHOW_SETTLE_MS] after a request) until it
 *   returns [Check.DONE]: it waits until the same app has an editor bound again and asks for a
 *   show at most [MAX_RESTORES] times.
 *
 * Never fighting the user or the app: the guard lives [WINDOW_MS] from the rotation, a user hide
 * or input ([onUserInput]) disarms it, input in another app cancels it, an app that kept its
 * window keeps its own hide (unless a pane was open), and it never shows the keyboard over the
 * platform's fallback connection (a window without an editor) unless the keyboard was over a
 * TYPE_NULL view to begin with (a terminal).
 */
class RotationShowGuard {

    /** One step of the service's retry loop; see [check]. */
    enum class Check {
        /** Ask the system to show the keyboard, then check again later. */
        REQUEST_SHOW,

        /** The app's editor is not bound yet: check again later. */
        WAIT,

        /** Nothing (more) to do: stop the loop. */
        DONE,
    }

    /** [paneOpen]: a content pane was open at the rotation (see the class comment). */
    private class Armed(
        val editor: EditorRef,
        val deadlineMs: Long,
        var restoresLeft: Int,
        val paneOpen: Boolean,
    ) {
        /** The app started input afresh since the rotation: its window was recreated. */
        var windowRecreated = false
    }

    private var armed: Armed? = null

    /** Whether a rotation is being guarded. */
    val isArmed: Boolean get() = armed != null

    /**
     * The orientation changed at [nowMs]. Arms the guard when the keyboard was on screen
     * ([windowVisible]) over [editor]; otherwise any earlier guard is dropped, so a rotation
     * with the keyboard hidden never brings it up. [paneOpen] is true when a content pane was
     * open on the keyboard.
     */
    fun onOrientationChanged(windowVisible: Boolean, editor: EditorRef?, nowMs: Long, paneOpen: Boolean = false) {
        armed = if (windowVisible && editor != null) {
            Armed(editor, nowMs + WINDOW_MS, MAX_RESTORES, paneOpen)
        } else {
            null
        }
    }

    /**
     * The window was hidden at [nowMs]; [byUser] is true for the user's own hide. Returns true
     * when the service should start the [check] loop. A user hide disarms the guard.
     */
    fun onWindowHidden(byUser: Boolean, nowMs: Long): Boolean {
        val a = armed ?: return false
        if (byUser || nowMs > a.deadlineMs) {
            armed = null
            return false
        }
        return a.restoresLeft > 0
    }

    /**
     * Input started for [packageName]; [restarting] is `onStartInput`'s flag (true for a
     * `restartInput` on the same editor and for the IME's own reset after the configuration
     * change). A fresh start in the guarded app marks its window recreated; input in another
     * app cancels the guard.
     */
    fun onStartInput(packageName: String?, restarting: Boolean) {
        val a = armed ?: return
        if (packageName != a.editor.packageName) {
            armed = null
            return
        }
        if (!restarting) a.windowRecreated = true
    }

    /**
     * One step at [nowMs]: [windowVisible] is the IME window's state and [current] the editor
     * input is bound to now (null while input has not started).
     */
    fun check(nowMs: Long, windowVisible: Boolean, current: EditorRef?): Check {
        val a = armed ?: return Check.DONE
        if (nowMs > a.deadlineMs) {
            armed = null
            return Check.DONE
        }
        // Shown again (by the system's restore or an earlier request): stay armed for a later
        // hide inside the window, but this loop is finished.
        if (windowVisible) return Check.DONE
        if (current == null) return Check.WAIT
        if (current.packageName != a.editor.packageName) {
            armed = null
            return Check.DONE
        }
        // The platform's fallback connection is not the app's editor yet (or the app moved to
        // a screen without one): wait, and give up at the deadline.
        if (current.typeNull && !a.editor.typeNull) return Check.WAIT
        // The app kept its window: its hide is its own (see the class comment), unless a pane
        // made the keyboard too tall for the new screen. Wait in case the fresh start is still on
        // its way, and give up at the deadline.
        if (!a.windowRecreated && !a.paneOpen) return Check.WAIT
        if (a.restoresLeft <= 0) return Check.DONE
        // In a kept window one request only: on Discord's wide layout (the Pixel at 360 dpi) the
        // app leaves the chat box and hides again at once, and that hide must stand.
        a.restoresLeft = if (a.windowRecreated) a.restoresLeft - 1 else 0
        return Check.REQUEST_SHOW
    }

    /** The user typed or otherwise acted: never override them. */
    fun onUserInput() {
        armed = null
    }

    /** Teardown. */
    fun cancel() {
        armed = null
    }

    companion object {
        /**
         * How long after the rotation a hide is still the rotation's. The app hides within
         * ~0.4 s (Google app ~150 ms, Discord ~370 ms on the Pixel 8 Pro); the rest covers a
         * slow relaunch.
         */
        const val WINDOW_MS = 2_000L

        /** How often the loop checks while it waits for the app's editor. */
        const val RETRY_INTERVAL_MS = 100L

        /**
         * The wait after a show request before the next check, so a request still in flight is
         * not counted as failed and repeated.
         */
        const val SHOW_SETTLE_MS = 300L

        /**
         * Show requests per rotation. Two: the app may hide once more as its relaunch settles;
         * a third hide is the app's choice and stands.
         */
        const val MAX_RESTORES = 2
    }
}
