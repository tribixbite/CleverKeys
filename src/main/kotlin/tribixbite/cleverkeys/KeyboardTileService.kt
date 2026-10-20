package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes

/**
 * Quick Settings tile for CleverKeys keyboard.
 *
 * A tap acts on the keyboard ([KeyboardTileDecision.actionFor] holds the table):
 * - another keyboard is selected: opens the input-method picker (how users switch to
 *   CleverKeys; the tile's original behaviour);
 * - the keyboard is on screen (possible where the shade does not take focus, or with
 *   `cmd statusbar click-tile`): minimizes it, to the style last used by a minimize command
 *   ([tribixbite.cleverkeys.minimize.KeyboardMinimizer.lastStyle], the floating button
 *   before any), or restores it when minimized;
 * - the keyboard was hidden a moment ago: on the Saga (Android 14) opening the shade itself
 *   hides the keyboard, so this is the usual case. The tap acts on the form it had just
 *   before: a full keyboard comes back minimized, a minimized one full size. The show is
 *   deferred ([CleverKeysService.showFromTile]): the system ignores it while the shade holds
 *   focus, so the service retries until the app is focused again after the shade closes;
 * - hidden for longer over a bound editor: shows the full keyboard the same way;
 * - hidden with no editor: opens the picker. An IME can only show itself for a focused
 *   editor, so nothing could appear; a Toast would sit under the open shade, and the subtitle
 *   ("Switch keyboard") already says what the tap will do.
 *
 * The tile reaches the running IME through [CleverKeysService.getInstance], the process-local
 * reference the service sets in onCreate and clears in onDestroy. The tile keeps no reference
 * of its own between callbacks, so it cannot retain a destroyed IME.
 *
 * **The shade stays open after a keyboard action.** The only public collapse is
 * [startActivityAndCollapse], which starts an activity: that takes window focus from the app,
 * whose editor then loses the keyboard the tap was meant to act on. [showDialog] collapses the
 * panel too, and also takes focus. `StatusBarManager.collapsePanels` is hidden API, needs the
 * `EXPAND_STATUS_BAR` permission and is blocked for apps targeting Android 12+. So the change
 * happens behind the shade (or, for a deferred show, right after it closes). The picker path is unchanged: the picker is a system dialog
 * shown over the shade, as before.
 *
 * Requires Android 7.0 (API 24) or higher; the subtitle needs Android 10 (API 29).
 *
 * @see <a href="https://github.com/Julow/Unexpected-Keyboard/issues/1113">Issue #1113</a>
 */
@RequiresApi(Build.VERSION_CODES.N)
class KeyboardTileService : TileService() {

    /** Posts the delayed tile refreshes after a tap. Main thread, like every callback here. */
    private val refreshHandler = Handler(Looper.getMainLooper())

    /** Re-reads the tile while the shade shows it; posted after a tap (see [onClick]). */
    private val refreshTile = Runnable { if (listening) updateTileState() }

    /** True between onStartListening and onStopListening, the only time the tile is live. */
    private var listening = false

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        updateTileState()
    }

    override fun onStopListening() {
        listening = false
        refreshHandler.removeCallbacks(refreshTile)
        super.onStopListening()
    }

    // Intent overload of startActivityAndCollapse is the only option below API 34;
    // the call is version-guarded (PendingIntent overload used on UPSIDE_DOWN_CAKE+).
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

        val service = CleverKeysService.getInstance()
        when (val action = KeyboardTileDecision.actionFor(isCleverKeysCurrent(), service?.tileSnapshot())) {
            KeyboardTileAction.MINIMIZE -> service?.minimizeFromTile()
            KeyboardTileAction.RESTORE -> service?.restoreFromTile()
            KeyboardTileAction.SHOW_MINIMIZED,
            KeyboardTileAction.SHOW_RESTORED,
            KeyboardTileAction.SHOW -> service?.showFromTile(action)
            KeyboardTileAction.PICK_KEYBOARD -> {
                // Show input method picker dialog
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                if (imm != null) {
                    imm.showInputMethodPicker()
                } else {
                    // Fallback: open keyboard settings
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    // TileService#startActivityAndCollapse(Intent) is deprecated and throws
                    // UnsupportedOperationException on API 34+ (UPSIDE_DOWN_CAKE); use the
                    // PendingIntent overload there and keep the Intent path for older APIs.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pendingIntent = PendingIntent.getActivity(
                            this,
                            0,
                            intent,
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        startActivityAndCollapse(pendingIntent)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(intent)
                    }
                }
            }
        }
        scheduleTileRefresh()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    /**
     * Minimize and restore swap the input view at once, but showing goes through the system
     * and lands a frame or more later, so the tile is re-read twice rather than guessed.
     */
    private fun scheduleTileRefresh() {
        refreshHandler.removeCallbacks(refreshTile)
        for (delay in TILE_REFRESH_DELAYS_MS) refreshHandler.postDelayed(refreshTile, delay)
    }

    /** Whether the system's selected IME is CleverKeys; see [tileStateFor]. */
    private fun isCleverKeysCurrent(): Boolean {
        val currentIme = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return tileStateFor(currentIme, packageName) == Tile.STATE_ACTIVE
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        val isCurrent = isCleverKeysCurrent()
        val action = KeyboardTileDecision.actionFor(
            isCurrent,
            CleverKeysService.getInstance()?.tileSnapshot()
        )
        val appName = getString(R.string.app_name)

        tile.state = if (isCurrent) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = appName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(KeyboardTileDecision.subtitleFor(action))
        }
        tile.contentDescription = getString(
            KeyboardTileDecision.contentDescriptionFor(isCurrent, action),
            appName
        )

        tile.updateTile()
    }

    companion object {
        /** When the tile re-reads the keyboard after a tap (see [scheduleTileRefresh]). */
        private val TILE_REFRESH_DELAYS_MS = longArrayOf(300L, 1_000L)

        /**
         * Tile state for the system's currently selected IME id (`<package>/<service>`).
         *
         * Package-substring, deliberately: the tile only reports "this device is typing with
         * CleverKeys", so any of our IME services (or the `.debug` variant on a developer's
         * device) counts. That is a weaker test than [IMEStatusHelper.isDefaultIME], which
         * must match the exact component before it decides whether to nag the user.
         *
         * Extracted from [updateTileState] so it can be pinned without a live TileService
         * (its lifecycle callbacks all begin with a `super` call into an unimplementable
         * android.jar stub). Behaviour is byte-for-byte the previous inline expression.
         */
        internal fun tileStateFor(currentIme: String?, packageName: String): Int =
            if (currentIme?.contains(packageName) == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

        /**
         * TalkBack description resource for a tile in [state] when a tap opens the picker; see
         * [tileStateFor] and [KeyboardTileDecision.contentDescriptionFor], which also covers
         * the keyboard actions. Both strings take the app name (`R.string.app_name`) as
         * `%1$s`; the caller resolves them so this stays free of a Context and pinnable in a
         * pure-JVM test.
         */
        @StringRes
        internal fun tileContentDescriptionFor(state: Int): Int =
            KeyboardTileDecision.contentDescriptionFor(
                isCurrentIme = state == Tile.STATE_ACTIVE,
                action = KeyboardTileAction.PICK_KEYBOARD,
            )
    }
}
