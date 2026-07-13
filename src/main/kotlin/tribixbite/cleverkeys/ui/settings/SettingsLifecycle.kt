package tribixbite.cleverkeys.ui.settings

import tribixbite.cleverkeys.SettingsActivity

/**
 * Lifecycle edge-case handlers for [SettingsActivity].
 *
 * Extracted from SettingsResetPresets.kt (2026-07-13) to keep reset/preset
 * logic separate from lifecycle-scoped failure paths.
 */

/**
 * Called when the encrypted SharedPreferences store is unavailable in direct-boot
 * mode (device locked after reboot).  Logs a warning and closes the activity so
 * the user is not left looking at a blank screen.
 */
internal fun SettingsActivity.fallbackEncrypted() {
    // Handle direct boot mode failure
    android.util.Log.w(SettingsActivity.TAG, "Settings unavailable in direct boot mode")
    finish()
}
