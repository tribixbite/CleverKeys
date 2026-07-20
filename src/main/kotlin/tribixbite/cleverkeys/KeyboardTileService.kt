package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi

/**
 * Quick Settings tile for CleverKeys keyboard.
 *
 * Tapping the tile opens the input method picker, allowing users to quickly
 * switch to CleverKeys or see available keyboards.
 *
 * Requires Android 7.0 (API 24) or higher.
 *
 * @see <a href="https://github.com/Julow/Unexpected-Keyboard/issues/1113">Issue #1113</a>
 */
@RequiresApi(Build.VERSION_CODES.N)
class KeyboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    // Intent overload of startActivityAndCollapse is the only option below API 34;
    // the call is version-guarded (PendingIntent overload used on UPSIDE_DOWN_CAKE+).
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

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

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        // Check if CleverKeys is the current input method
        val currentIme = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        val isCleverKeysActive = currentIme?.contains(packageName) == true

        tile.state = if (isCleverKeysActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.contentDescription = if (isCleverKeysActive) {
            "CleverKeys is active. Tap to switch keyboard."
        } else {
            "Tap to switch to CleverKeys keyboard."
        }

        tile.updateTile()
    }
}
