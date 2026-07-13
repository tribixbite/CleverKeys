package tribixbite.cleverkeys.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import tribixbite.cleverkeys.AutoCorrectionSettingsActivity
import tribixbite.cleverkeys.BackupRestoreActivity
import tribixbite.cleverkeys.DictionaryManagerActivity
import tribixbite.cleverkeys.ExtraKeysConfigActivity
import tribixbite.cleverkeys.LayoutManagerActivity
import tribixbite.cleverkeys.NeuralSettingsActivity
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ShortSwipeCustomizationActivity
import tribixbite.cleverkeys.SwipeCalibrationActivity
import tribixbite.cleverkeys.SwipeDebugActivity

internal fun SettingsActivity.openWikiInBrowser() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://tribixbite.github.io/CleverKeys/wiki"))
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Failed to open wiki", e)
        }
}

internal fun SettingsActivity.openNeuralSettings() {
        startActivity(Intent(this, NeuralSettingsActivity::class.java))
}

internal fun SettingsActivity.openCalibration() {
        startActivity(Intent(this, SwipeCalibrationActivity::class.java))
}

internal fun SettingsActivity.openSwipeDebugActivity() {
        startActivity(Intent(this, SwipeDebugActivity::class.java))
}

internal fun SettingsActivity.openDictionaryManager() {
        // Launch our 4-tab Dictionary Manager (Active, Disabled, User, Custom)
        startActivity(Intent(this, DictionaryManagerActivity::class.java))
}

internal fun SettingsActivity.openLayoutManager() {
        startActivity(Intent(this, LayoutManagerActivity::class.java))
}

internal fun SettingsActivity.openExtraKeysConfig() {
        startActivity(Intent(this, ExtraKeysConfigActivity::class.java))
}

internal fun SettingsActivity.openShortSwipeCustomization() {
        startActivity(Intent(this, ShortSwipeCustomizationActivity::class.java))
}

internal fun SettingsActivity.openAutoCorrectionSettings() {
        startActivity(Intent(this, AutoCorrectionSettingsActivity::class.java))
}

// Clipboard settings now inline in main settings UI

internal fun SettingsActivity.openBackupRestore() {
        startActivity(Intent(this, BackupRestoreActivity::class.java))
}

internal fun SettingsActivity.openGitHubReleases() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://github.com/tribixbite/cleverkeys/releases")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
}
