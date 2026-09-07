package tribixbite.cleverkeys.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import tribixbite.cleverkeys.BackupRestoreActivity
import tribixbite.cleverkeys.CtcSettingsActivity
import tribixbite.cleverkeys.DictionaryManagerActivity
import tribixbite.cleverkeys.ExtraKeysConfigActivity
import tribixbite.cleverkeys.LayoutManagerActivity
import tribixbite.cleverkeys.GeometricSettingsActivity
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.SwipeDebugActivity

internal fun SettingsActivity.openWikiInBrowser() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://tribixbite.github.io/CleverKeys/wiki"))
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Failed to open wiki", e)
        }
}

internal fun SettingsActivity.openGeometricSettings() {
        startActivity(Intent(this, GeometricSettingsActivity::class.java))
}

internal fun SettingsActivity.openCtcSettings() {
        startActivity(Intent(this, CtcSettingsActivity::class.java))
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

// F-9 (2026-09-06): openAutoCorrectionSettings() deleted together with the
// unreachable AutoCorrectionSettingsActivity it launched (zero callers; its
// slider ranges had drifted from the live AutoCorrectionSection AND the import
// validator — re-wiring it would have reproduced the F-4 round-trip-loss
// class). openShortSwipeCustomization() deleted for the same zero-caller
// reason — ShortSwipeCustomizationActivity itself stays reachable via the
// settings search index and ShortSwipeCalibrationActivity.
// SettingsSurfaceDriftTest pins that every open* helper here has a caller.

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
