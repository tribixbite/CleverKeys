package tribixbite.cleverkeys

import androidx.annotation.StringRes

/**
 * Identity of the editor input is bound to, taken from the service's last `onStartInput`
 * EditorInfo. Only the fields the tile's decision needs, so the decision stays pure.
 *
 * @property typeNull `inputType == TYPE_NULL`: a terminal or remote-desktop view (blind fields),
 *   or the platform's fallback connection for a window with no editor at all (Android 14 binds
 *   one for the launcher). Kept for the verbose tile log.
 */
data class EditorRef(val packageName: String?, val fieldId: Int, val typeNull: Boolean)

/**
 * The input window as the Quick Settings tile sees it, read from the live service
 * ([CleverKeysService.tileSnapshot]); `null` wherever this type is optional means the service is
 * not running in this process.
 *
 * @property windowVisible The IME window is on screen (full or minimized): the onWindowShown /
 *   onWindowHidden pair AND `isInputViewShown()`, so a stale flag on either side reads as hidden.
 * @property current The editor input is bound to now, or null when input has not started or
 *   has no live connection.
 * @property lastEditor The editor the window was shown over when it was last hidden; null
 *   before any hide.
 * @property hiddenAgeMs How long ago the window was hidden; null while visible or never shown.
 * @property dismissedByUser The last hide was the user's own (Back, the hide_keyboard key or a
 *   tile tap), not the shade's.
 */
data class TileWindowSnapshot(
    val windowVisible: Boolean,
    val current: EditorRef?,
    val lastEditor: EditorRef?,
    val hiddenAgeMs: Long?,
    val dismissedByUser: Boolean,
)

/** What a tap on the Quick Settings tile does. */
enum class KeyboardTileAction {
    /**
     * Another keyboard is selected (or CleverKeys is not running): open the system input-method
     * picker, the only way an app can offer the switch.
     */
    PICK_KEYBOARD,

    /**
     * The keyboard is on screen, or was until the shade opened over it: hide it. The tile is a
     * show/hide toggle of the keyboard as the user saw it before pulling the shade down.
     */
    HIDE,

    /** The keyboard is hidden: show it, full size (asked for even with no editor focused). */
    SHOW,
}

/**
 * The tile's pure decision table, kept free of Android types so every branch runs in the pure
 * JVM suite (the TileService callbacks themselves cannot run off-device; see
 * `KeyboardTileServiceTest`).
 *
 * Why "recently hidden" matters: on at least the Saga (Android 14) expanding the notification
 * shade hides the keyboard, and it does not come back when the shade collapses. So by the time
 * the user taps the tile a keyboard they saw on screen is already hidden. A hide less than
 * [RECENT_HIDE_MS] before the tap, not made by the user, is read as the shade's, and the tap
 * acts on the keyboard as it was before the shade: it was showing, so the tap hides it (keeps it
 * hidden) rather than bringing it straight back (device report, 2026-10-20).
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
     * down, find the tile and tap it; short enough that a keyboard hidden a while ago (or by
     * leaving the app) is shown again by the next tap.
     */
    const val RECENT_HIDE_MS = 10_000L

    /** Package of the notification shade's own windows (SystemUI). */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /**
     * Whether the input bound now still belongs to the app the keyboard was hidden over: the
     * same package, nothing bound (the shade may unbind it), or the shade itself (SystemUI). Any
     * other package means the user went elsewhere (e.g. home), so the hide was not the shade's.
     */
    fun isSameAppOrShade(current: EditorRef?, lastEditor: EditorRef?): Boolean =
        current == null || current.packageName == SYSTEM_UI_PACKAGE ||
            (lastEditor != null && current.packageName == lastEditor.packageName)

    /**
     * Whether the keyboard was on screen just before the shade opened: hidden less than
     * [RECENT_HIDE_MS] ago, not by the user, over an editor, with input still in that app or
     * the shade ([isSameAppOrShade]).
     */
    fun wasShownBeforeTheShade(window: TileWindowSnapshot): Boolean {
        val age = window.hiddenAgeMs ?: return false
        return age in 0..RECENT_HIDE_MS && !window.dismissedByUser &&
            window.lastEditor != null && isSameAppOrShade(window.current, window.lastEditor)
    }

    /**
     * The action for a tap.
     *
     * - Another keyboard is selected, or the service is not running: the picker, which is how
     *   users switch to CleverKeys.
     * - The window is on screen (full or minimized), or was until the shade opened
     *   ([wasShownBeforeTheShade]): hide it.
     * - Otherwise: show it, even with no editor focused (maintainer 2026-11-11: "it's supposed to
     *   do so anyway"); the system decides whether it can. It never falls back to the picker.
     */
    fun actionFor(isCurrentIme: Boolean, window: TileWindowSnapshot?): KeyboardTileAction = when {
        !isCurrentIme || window == null -> KeyboardTileAction.PICK_KEYBOARD
        window.windowVisible || wasShownBeforeTheShade(window) -> KeyboardTileAction.HIDE
        else -> KeyboardTileAction.SHOW
    }

    /**
     * The tile subtitle (API 29+): "Toggle" for both keyboard actions. The label is read when the
     * shade opens, and it once said "Show" over a keyboard that was on screen (the tap then hid
     * it, rightly; maintainer 2026-11-11: "it should say toggle instead of show/hide or be
     * accurate"), so it names the toggle rather than guess the state.
     */
    @StringRes
    fun subtitleFor(action: KeyboardTileAction): Int = when (action) {
        KeyboardTileAction.PICK_KEYBOARD -> R.string.tile_subtitle_switch
        KeyboardTileAction.HIDE, KeyboardTileAction.SHOW -> R.string.tile_subtitle_toggle
    }

    /**
     * TalkBack description for the tile; every resource takes the app name as `%1$s`. The
     * picker keeps the two original descriptions (active / inactive), so a user who has not
     * selected CleverKeys hears exactly what they heard before.
     */
    @StringRes
    fun contentDescriptionFor(isCurrentIme: Boolean, action: KeyboardTileAction): Int = when (action) {
        KeyboardTileAction.PICK_KEYBOARD ->
            if (isCurrentIme) R.string.tile_descr_active else R.string.tile_descr_inactive
        KeyboardTileAction.HIDE, KeyboardTileAction.SHOW -> R.string.tile_descr_toggle
    }
}

/**
 * The service's tile bookkeeping: when and over which editor the window was last hidden, whose
 * hide it was, and a deferred show requested by the tile. Pure and single-threaded (the service
 * drives it from the main thread with `SystemClock.uptimeMillis()`), so the whole state machine
 * is unit-tested.
 *
 * Deferred show: a tap with the keyboard hidden may not show it at once. The shade never takes
 * IME focus and `requestShowSelf` works under it (Saga 2026-10-20: shown 7 ms after the request),
 * but some apps hide their own keyboard while the shade is open or as it closes (Chrome:
 * `ORIGIN_CLIENT_HIDE_SOFT_INPUT`), so one request can be undone. No overlay permission is involved
 * (`docs/specs/keyboard-minimize.md`, "Overlay permission check"). [request] arms a show; the
 * service then calls [tick] every [RETRY_INTERVAL_MS], asking the system to show the keyboard
 * each time [tick] returns [Tick.REQUEST_SHOW], until the window is shown ([onWindowShown]
 * reports that the show was the requested one) or [RETRY_WINDOW_MS] passes.
 *
 * Not fighting the user: the request is one-shot (cleared by the first show), bound to the app
 * whose editor was bound at the tap ([onStartInput] with another package cancels it), and
 * cancelled when the user types ([onUserInput]), on teardown ([cancel]) and at its deadline. The
 * first show inside the window is treated as the requested one: `requestShowSelf` is
 * asynchronous with no token, so a show the user triggers by tapping the same app's field in
 * those seconds cannot be told apart.
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

    private class Pending(val packageName: String?, val deadlineMs: Long)

    private var pending: Pending? = null

    private var hiddenAtMs: Long? = null

    /** The editor the window was shown over when it was last hidden. */
    var lastEditor: EditorRef? = null
        private set

    /** The last hide was the user's own; see [noteUserDismiss] and [markHiddenByUser]. */
    var dismissedByUser: Boolean = false
        private set

    /** Set just before a user-initiated hide; consumed by the next [onWindowHidden]. */
    private var userDismissPending = false

    /** The user is dismissing the keyboard (Back while shown, the hide_keyboard key, the tile). */
    fun noteUserDismiss() {
        userDismissPending = true
    }

    /**
     * The tile asked to hide a keyboard the shade had already hidden: the hide now counts as
     * the user's, so the next tap shows it instead of reading the same hide as the shade's again.
     * No effect while the window is visible (there is no hide to re-attribute).
     */
    fun markHiddenByUser() {
        if (hiddenAtMs != null) dismissedByUser = true
    }

    /** Whether a deferred show is armed. */
    val hasPending: Boolean get() = pending != null

    /** The window is on screen; it is no longer "recently hidden". */
    fun onWindowVisible() {
        hiddenAtMs = null
        userDismissPending = false
    }

    /** The window was hidden at [nowMs] while showing over [editor]. */
    fun onWindowHidden(editor: EditorRef?, nowMs: Long) {
        lastEditor = editor
        hiddenAtMs = nowMs
        dismissedByUser = userDismissPending
        userDismissPending = false
    }

    /** Milliseconds since the window was hidden, or null while visible / never hidden. */
    fun hiddenAgeMs(nowMs: Long): Long? = hiddenAtMs?.let { nowMs - it }

    /**
     * Arm a deferred show for the editor of [packageName] (null when no editor is known at the
     * tap: any app's editor may take it). Replaces any earlier request.
     */
    fun request(packageName: String?, nowMs: Long) {
        pending = Pending(packageName, nowMs + RETRY_WINDOW_MS)
    }

    /**
     * One retry step at [nowMs]; [windowVisible] short-circuits to a stop. A request goes out
     * whether or not an editor has focus (maintainer 2026-11-11: the tile must show the keyboard
     * "in an app without text input … anyway"); the system decides whether it can show.
     */
    fun tick(nowMs: Long, windowVisible: Boolean): Tick {
        val p = pending ?: return Tick.IDLE
        if (windowVisible) return Tick.IDLE
        if (nowMs > p.deadlineMs) {
            pending = null
            return Tick.EXPIRED
        }
        return Tick.REQUEST_SHOW
    }

    /** The window was shown: true, once, when it was the tile's requested show. */
    fun onWindowShown(): Boolean {
        onWindowVisible()
        val requested = pending != null
        pending = null
        return requested
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

        /** How long a deferred show keeps asking while the app regains focus after the shade. */
        const val RETRY_WINDOW_MS = 8_000L
    }
}
