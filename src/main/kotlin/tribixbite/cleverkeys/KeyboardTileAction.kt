package tribixbite.cleverkeys

import androidx.annotation.StringRes

/**
 * What the running CleverKeys input window looks like, as the Quick Settings tile sees it.
 *
 * Read from the live service ([CleverKeysService.tileWindowState]); `null` wherever this type
 * is optional means the service is not running in this process.
 */
enum class ImeWindowState {
    /** The full keyboard is on screen. */
    SHOWN,

    /** The keyboard is on screen as the minimized bar or floating button (gh #175). */
    MINIMIZED,

    /**
     * No keyboard on screen, but an editor is bound (input started with a live connection):
     * the IME may ask to show itself.
     */
    HIDDEN_WITH_EDITOR,

    /** No keyboard on screen and no editor to type into: nothing can be shown. */
    NO_EDITOR,
}

/** What a tap on the Quick Settings tile does. */
enum class KeyboardTileAction {
    /** Open the system input-method picker (the tile's original and fallback action). */
    PICK_KEYBOARD,

    /** Collapse the shown keyboard to its minimized form. */
    MINIMIZE,

    /** Expand the minimized keyboard back to full size. */
    RESTORE,

    /** Ask the system to show the hidden keyboard for the bound editor. */
    SHOW,
}

/**
 * The tile's pure decision table, kept free of Android types so every branch runs in the pure
 * JVM suite (the TileService callbacks themselves cannot run off-device; see
 * `KeyboardTileServiceTest`).
 */
object KeyboardTileDecision {

    /**
     * The action for a tap.
     *
     * - Another keyboard is selected: the picker, which is how users switch to CleverKeys.
     * - CleverKeys is selected and the keyboard shows: minimize it; minimized: restore it.
     * - Hidden with a bound editor: show it.
     * - Hidden with no editor, or the service is not running ([window] null): the picker. An
     *   IME can only show itself for a focused editor (`requestShowSelf` is ignored without a
     *   client), so there is nothing to show; the picker is still a useful action, and the
     *   tile's subtitle says so before the tap.
     */
    fun actionFor(isCurrentIme: Boolean, window: ImeWindowState?): KeyboardTileAction =
        if (!isCurrentIme) {
            KeyboardTileAction.PICK_KEYBOARD
        } else {
            when (window) {
                ImeWindowState.SHOWN -> KeyboardTileAction.MINIMIZE
                ImeWindowState.MINIMIZED -> KeyboardTileAction.RESTORE
                ImeWindowState.HIDDEN_WITH_EDITOR -> KeyboardTileAction.SHOW
                ImeWindowState.NO_EDITOR, null -> KeyboardTileAction.PICK_KEYBOARD
            }
        }

    /** The tile subtitle (API 29+) naming what a tap does next. */
    @StringRes
    fun subtitleFor(action: KeyboardTileAction): Int = when (action) {
        KeyboardTileAction.PICK_KEYBOARD -> R.string.tile_subtitle_switch
        KeyboardTileAction.MINIMIZE -> R.string.tile_subtitle_minimize
        KeyboardTileAction.RESTORE -> R.string.tile_subtitle_restore
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
        KeyboardTileAction.MINIMIZE -> R.string.tile_descr_minimize
        KeyboardTileAction.RESTORE -> R.string.tile_descr_restore
        KeyboardTileAction.SHOW -> R.string.tile_descr_show
    }

    /**
     * The window state from the service's raw signals. [inputViewShown] wins: a minimized
     * keyboard is still the shown input view. Otherwise an editor counts only when input has
     * started AND a connection exists — `requestShowSelf` needs both to do anything.
     */
    fun windowStateFor(
        inputViewShown: Boolean,
        minimized: Boolean,
        inputStarted: Boolean,
        hasConnection: Boolean,
    ): ImeWindowState = when {
        inputViewShown && minimized -> ImeWindowState.MINIMIZED
        inputViewShown -> ImeWindowState.SHOWN
        inputStarted && hasConnection -> ImeWindowState.HIDDEN_WITH_EDITOR
        else -> ImeWindowState.NO_EDITOR
    }
}
