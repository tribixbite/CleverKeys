package tribixbite.cleverkeys

import androidx.annotation.StringRes
import tribixbite.cleverkeys.minimize.MinimizedStyle

/**
 * How the keyboard looked while it was last on screen: the full keyboard, or minimized
 * (gh #175) in [style].
 */
sealed class VisibleForm {
    object Full : VisibleForm()
    data class Minimized(val style: MinimizedStyle) : VisibleForm()
}

/**
 * Identity of the editor input is bound to, taken from the service's last `onStartInput`
 * EditorInfo. Only the fields the tile's decision needs, so the decision stays pure.
 *
 * @property typeNull `inputType == TYPE_NULL`: a terminal or remote-desktop view (blind
 *   fields), OR the platform's fallback connection for a window with no editor at all (Android
 *   14 binds one for the launcher; device report 2026-10-20). The two cannot be told apart from
 *   the EditorInfo, so a TYPE_NULL binding counts as an editor only when the keyboard was last
 *   shown over that same field ([isSameField]).
 */
data class EditorRef(val packageName: String?, val fieldId: Int, val typeNull: Boolean) {
    /** Same app and same view id: the field the keyboard was shown over. */
    fun isSameField(other: EditorRef?): Boolean =
        other != null && packageName == other.packageName && fieldId == other.fieldId
}

/**
 * The input window as the Quick Settings tile sees it, read from the live service
 * ([CleverKeysService.tileSnapshot]); `null` wherever this type is optional means the service is
 * not running in this process.
 *
 * @property windowVisible The IME window is on screen: the onWindowShown / onWindowHidden pair
 *   AND `isInputViewShown()`, so a stale flag on either side reads as hidden.
 * @property minimized The visible window shows the minimized view.
 * @property current The editor input is bound to now, or null when input has not started or
 *   has no live connection.
 * @property lastVisible The form the window had when it was last hidden; null before any hide.
 * @property lastEditor The editor the window was shown over when it was last hidden.
 * @property hiddenAgeMs How long ago the window was hidden; null while visible or never shown.
 * @property dismissedByUser The last hide was the user's own (Back, or the hide_keyboard key),
 *   not the shade's.
 */
data class TileWindowSnapshot(
    val windowVisible: Boolean,
    val minimized: Boolean,
    val current: EditorRef?,
    val lastVisible: VisibleForm?,
    val lastEditor: EditorRef?,
    val hiddenAgeMs: Long?,
    val dismissedByUser: Boolean,
)

/** What a tap on the Quick Settings tile does. */
enum class KeyboardTileAction {
    /** Open the system input-method picker (the tile's original and fallback action). */
    PICK_KEYBOARD,

    /** The full keyboard is on screen: minimize it. */
    MINIMIZE,

    /** The minimized keyboard is on screen: expand it. */
    RESTORE,

    /**
     * The shade hid a full keyboard a moment ago: bring it back minimized, i.e. the minimize the
     * user meant when they opened the shade over a shown keyboard.
     */
    SHOW_MINIMIZED,

    /** The shade hid a minimized keyboard a moment ago: bring it back full size. */
    SHOW_RESTORED,

    /** The keyboard was dismissed earlier (Back, or long ago) over a bound editor: show it. */
    SHOW,
}

/** The form a deferred show must land in; see [TileShowSession]. */
sealed class ShowTarget {
    object Full : ShowTarget()
    data class Minimized(val style: MinimizedStyle) : ShowTarget()
}

/**
 * The tile's pure decision table, kept free of Android types so every branch runs in the pure
 * JVM suite (the TileService callbacks themselves cannot run off-device; see
 * `KeyboardTileServiceTest`).
 *
 * Why "recently hidden" matters: on at least the Saga (Android 14) expanding the notification
 * shade takes window focus and the system hides the keyboard, and it does not come back when
 * the shade collapses. So by the time the user taps the tile the keyboard is already hidden. A
 * hide less than [RECENT_HIDE_MS] before the tap is read as the shade's, and the tap acts on the
 * form the keyboard had just before it (device report, 2026-10-20).
 *
 * Known limitation, web editors: a page's textarea in Chrome loses editor focus when the shade
 * opens (Chrome hides the IME itself, ORIGIN_CLIENT_HIDE_SOFT_INPUT). The deferred show does
 * land (SHOW_SOFT_INPUT_FROM_IME, onShown), but the system hides it ~0.3 s later with
 * HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR because the page no longer has a focused editor.
 * That is outside the IME's control: such fields need a tap after the shade closes. Native
 * editors (including Chrome's address bar) are unaffected (Saga, 2026-10-20).
 */
object KeyboardTileDecision {

    /**
     * A hide this recent is attributed to the shade opening. Long enough to pull the shade
     * down, find the tile and tap it; short enough that a keyboard dismissed with Back a while
     * ago comes back full size ([KeyboardTileAction.SHOW]) instead of minimized.
     */
    const val RECENT_HIDE_MS = 10_000L

    /** Package of the notification shade's own windows (SystemUI). */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /**
     * Whether [current] is an editor the keyboard can be shown for: any typed field, or a
     * TYPE_NULL binding only when it is the very field the keyboard was last shown over
     * ([lastEditor]); the launcher's fallback connection has neither.
     */
    fun isRealEditor(current: EditorRef?, lastEditor: EditorRef?): Boolean =
        current != null && (!current.typeNull || current.isSameField(lastEditor))

    /**
     * Whether the input bound now still belongs to the app the keyboard was hidden over: the
     * same package, nothing bound (the shade may unbind it), or the shade itself (SystemUI). Any
     * other package means the user went elsewhere (e.g. home), and a show would land there.
     */
    fun isSameAppOrShade(current: EditorRef?, lastEditor: EditorRef?): Boolean =
        current == null || current.packageName == SYSTEM_UI_PACKAGE ||
            (lastEditor != null && current.packageName == lastEditor.packageName)

    /**
     * The action for a tap.
     *
     * - Another keyboard is selected, or the service is not running: the picker, which is how
     *   users switch to CleverKeys.
     * - The window is on screen: minimize it, or restore it when minimized.
     * - Hidden less than [RECENT_HIDE_MS] ago by something other than the user, over an editor,
     *   with input still in that app or the shade ([isSameAppOrShade]): the opposite of the form
     *   it had (full → [KeyboardTileAction.SHOW_MINIMIZED], minimized →
     *   [KeyboardTileAction.SHOW_RESTORED]). The current binding need not be that editor:
     *   whether the shade unbinds it varies by device, and the show is retried until the app
     *   has focus again.
     * - Otherwise, over a real editor ([isRealEditor]): show it full size.
     * - Otherwise there is nothing to show (an IME can only show itself for a focused editor):
     *   the picker, which is still useful, and the subtitle says so before the tap.
     */
    fun actionFor(isCurrentIme: Boolean, window: TileWindowSnapshot?): KeyboardTileAction {
        if (!isCurrentIme || window == null) return KeyboardTileAction.PICK_KEYBOARD
        if (window.windowVisible) {
            return if (window.minimized) KeyboardTileAction.RESTORE else KeyboardTileAction.MINIMIZE
        }
        val age = window.hiddenAgeMs
        val shadeHide = age != null && age in 0..RECENT_HIDE_MS && !window.dismissedByUser &&
            window.lastEditor != null && isSameAppOrShade(window.current, window.lastEditor)
        if (shadeHide) {
            when (window.lastVisible) {
                VisibleForm.Full -> return KeyboardTileAction.SHOW_MINIMIZED
                is VisibleForm.Minimized -> return KeyboardTileAction.SHOW_RESTORED
                null -> Unit
            }
        }
        return if (isRealEditor(window.current, window.lastEditor)) {
            KeyboardTileAction.SHOW
        } else {
            KeyboardTileAction.PICK_KEYBOARD
        }
    }

    /**
     * The form a deferred show lands in for [action], or null when [action] is not a deferred
     * show. [minimizeStyle] is the style a minimize uses
     * ([tribixbite.cleverkeys.minimize.KeyboardMinimizer.lastStyle]).
     */
    fun showTargetFor(action: KeyboardTileAction, minimizeStyle: MinimizedStyle): ShowTarget? = when (action) {
        KeyboardTileAction.SHOW_MINIMIZED -> ShowTarget.Minimized(minimizeStyle)
        KeyboardTileAction.SHOW_RESTORED, KeyboardTileAction.SHOW -> ShowTarget.Full
        KeyboardTileAction.PICK_KEYBOARD, KeyboardTileAction.MINIMIZE, KeyboardTileAction.RESTORE -> null
    }

    /**
     * The tile subtitle (API 29+) naming what a tap does, in the user's terms: bringing back a
     * keyboard the shade hid reads as the minimize or restore the user asked for.
     */
    @StringRes
    fun subtitleFor(action: KeyboardTileAction): Int = when (action) {
        KeyboardTileAction.PICK_KEYBOARD -> R.string.tile_subtitle_switch
        KeyboardTileAction.MINIMIZE, KeyboardTileAction.SHOW_MINIMIZED -> R.string.tile_subtitle_minimize
        KeyboardTileAction.RESTORE, KeyboardTileAction.SHOW_RESTORED -> R.string.tile_subtitle_restore
        KeyboardTileAction.SHOW -> R.string.tile_subtitle_show
    }

    /**
     * TalkBack description for the tile; every resource takes the app name as `%1$s`. The
     * picker keeps the two original descriptions (active / inactive), so a user who has not
     * selected CleverKeys hears exactly what they heard before this change.
     */
    @StringRes
    fun contentDescriptionFor(isCurrentIme: Boolean, action: KeyboardTileAction): Int = when (action) {
        KeyboardTileAction.PICK_KEYBOARD ->
            if (isCurrentIme) R.string.tile_descr_active else R.string.tile_descr_inactive
        KeyboardTileAction.MINIMIZE, KeyboardTileAction.SHOW_MINIMIZED -> R.string.tile_descr_minimize
        KeyboardTileAction.RESTORE, KeyboardTileAction.SHOW_RESTORED -> R.string.tile_descr_restore
        KeyboardTileAction.SHOW -> R.string.tile_descr_show
    }
}

/**
 * The service's tile bookkeeping: what the window looked like when it was last hidden, and a
 * deferred show requested by the tile. Pure and single-threaded (the service drives it from the
 * main thread with `SystemClock.uptimeMillis()`), so the whole state machine is unit-tested.
 *
 * Deferred show: a tap with the keyboard hidden may not show it at once. The shade never takes
 * IME focus and `requestShowSelf` works under it (Saga 2026-10-20: shown 7 ms after the request),
 * but some apps hide their own keyboard while the shade is open or as it closes (Chrome:
 * `ORIGIN_CLIENT_HIDE_SOFT_INPUT`), so one request can be undone. No overlay permission is
 * involved (`docs/specs/keyboard-minimize.md`, "Overlay permission check"). [request] arms a show; the service then calls [tick] every [RETRY_INTERVAL_MS], asking the
 * system to show the keyboard each time [tick] returns [Tick.REQUEST_SHOW], until the window is
 * shown ([onWindowShown] returns the form to apply) or [RETRY_WINDOW_MS] passes.
 *
 * Not fighting the user: the request is one-shot (cleared by the first show), bound to the app
 * whose editor was bound at the tap ([onStartInput] with another package cancels it), and
 * cancelled when the user types ([onUserInput]), on teardown ([cancel]) and at its deadline. The
 * first show inside the window is treated as the requested one: `requestShowSelf` is
 * asynchronous with no token, so a show the user triggers by tapping the same app's field in
 * those seconds cannot be told apart, and lands in the form the tile asked for.
 */
class TileShowSession {

    /** One step of the retry loop; see [tick]. */
    enum class Tick {
        /** Nothing pending: stop the loop. */
        IDLE,

        /** Still pending: ask the system to show the keyboard and tick again later. */
        REQUEST_SHOW,

        /** The deadline passed with no show: the request is dropped; stop the loop. */
        EXPIRED,
    }

    private class Pending(val target: ShowTarget, val packageName: String?, val deadlineMs: Long)

    private var pending: Pending? = null

    /** The form the window had when it was last hidden; null before the first hide. */
    var lastVisible: VisibleForm? = null
        private set

    private var hiddenAtMs: Long? = null

    /** The editor the window was shown over when it was last hidden. */
    var lastEditor: EditorRef? = null
        private set

    /** The last hide was the user's own; see [noteUserDismiss]. */
    var dismissedByUser: Boolean = false
        private set

    /** Set just before a user-initiated hide; consumed by the next [onWindowHidden]. */
    private var userDismissPending = false

    /** The user is dismissing the keyboard (Back while shown, or the hide_keyboard key). */
    fun noteUserDismiss() {
        userDismissPending = true
    }

    /** Whether a deferred show is armed. */
    val hasPending: Boolean get() = pending != null

    /** The window is on screen; it is no longer "recently hidden". */
    fun onWindowVisible() {
        hiddenAtMs = null
        userDismissPending = false
    }

    /** The window was hidden at [nowMs] while showing [form] over [editor]. */
    fun onWindowHidden(form: VisibleForm, editor: EditorRef?, nowMs: Long) {
        lastVisible = form
        lastEditor = editor
        hiddenAtMs = nowMs
        dismissedByUser = userDismissPending
        userDismissPending = false
    }

    /** Milliseconds since the window was hidden, or null while visible / never hidden. */
    fun hiddenAgeMs(nowMs: Long): Long? = hiddenAtMs?.let { nowMs - it }

    /**
     * Arm a deferred show landing in [target] for the editor of [packageName] (null when no
     * editor is bound at the tap: any app's editor may take it). Replaces any earlier request.
     */
    fun request(target: ShowTarget, packageName: String?, nowMs: Long) {
        pending = Pending(target, packageName, nowMs + RETRY_WINDOW_MS)
    }

    /** One retry step at [nowMs]; [windowVisible] short-circuits to a stop. */
    fun tick(nowMs: Long, windowVisible: Boolean): Tick {
        val p = pending ?: return Tick.IDLE
        if (windowVisible) return Tick.IDLE
        if (nowMs > p.deadlineMs) {
            pending = null
            return Tick.EXPIRED
        }
        return Tick.REQUEST_SHOW
    }

    /** The window was shown: the form to apply, once, or null when nothing was pending. */
    fun onWindowShown(): ShowTarget? {
        onWindowVisible()
        val p = pending ?: return null
        pending = null
        return p.target
    }

    /** Input started for [packageName]: a request armed for another app is dropped. */
    fun onStartInput(packageName: String?) {
        val p = pending ?: return
        if (p.packageName != null && packageName != null && packageName != p.packageName) pending = null
    }

    /** The user typed or otherwise acted on the keyboard: never override them. */
    fun onUserInput() {
        pending = null
    }

    /** Teardown or a superseding action. */
    fun cancel() {
        pending = null
    }

    companion object {
        /** How often a deferred show asks the system again. */
        const val RETRY_INTERVAL_MS = 250L

        /** How long a deferred show waits for the app to regain focus after the shade. */
        const val RETRY_WINDOW_MS = 8_000L
    }
}
