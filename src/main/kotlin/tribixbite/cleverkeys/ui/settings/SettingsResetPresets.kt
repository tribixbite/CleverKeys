package tribixbite.cleverkeys.ui.settings

import android.widget.Toast
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity

/**
 * Get current swipe sensitivity preset based on current values
 */
internal fun SettingsActivity.getSwipeSensitivityPreset(): String {
        // Low: Higher thresholds = less sensitive
        val lowPreset = swipeMinDistance == 80f && swipeMinKeyDistance == 60f && swipeMinDwellTime == 30
        // Medium: Default values
        val mediumPreset = swipeMinDistance == 50f && swipeMinKeyDistance == 40f && swipeMinDwellTime == 15
        // High: Lower thresholds = more sensitive
        val highPreset = swipeMinDistance == 30f && swipeMinKeyDistance == 25f && swipeMinDwellTime == 5

        return when {
            lowPreset -> "Low"
            mediumPreset -> "Medium"
            highPreset -> "High"
            else -> "Custom"
        }
}

/**
 * Apply swipe sensitivity preset values
 */
internal fun SettingsActivity.applySwipeSensitivityPreset(preset: String) {
        when (preset) {
            "Low" -> {
                swipeMinDistance = 80f
                swipeMinKeyDistance = 60f
                swipeMinDwellTime = 30
            }
            "Medium" -> {
                swipeMinDistance = 50f
                swipeMinKeyDistance = 40f
                swipeMinDwellTime = 15
            }
            "High" -> {
                swipeMinDistance = 30f
                swipeMinKeyDistance = 25f
                swipeMinDwellTime = 5
            }
            "Custom" -> return // Don't change values
        }
        // Save the new values
        saveSetting("swipe_min_distance", swipeMinDistance)
        saveSetting("swipe_min_key_distance", swipeMinKeyDistance)
        saveSetting("swipe_min_dwell_time", swipeMinDwellTime)
}

internal fun SettingsActivity.resetAllSettings() {
        val _self = this  // capture extension receiver for use inside non-inline lambdas
        android.app.AlertDialog.Builder(_self)
            .setTitle(getString(R.string.settings_reset_dialog_title))
            .setMessage(getString(R.string.settings_reset_dialog_message))
            .setPositiveButton(getString(R.string.settings_reset_dialog_confirm)) { _, _ ->
                    // F-1/F-2 (2026-09-06): NEVER wholesale-clear the prefs file here.
                    // The shared file also holds the custom dictionary (custom_words_* /
                    // disabled_words_*), `layouts`, `extra_keys*`, per-language prefs,
                    // custom themes and every migration marker — the old clear-then-
                    // reseed destroyed all of it, and the lost `margin_prefs_version`
                    // re-ran the dp→percent migration over percent values on the next
                    // process start. The removal set and seed list live in the pure
                    // [SettingsResetPolicy]; SettingsResetPolicyTest pins both and
                    // bans whole-file clears repo-wide.
                    val editor = prefs.edit()
                    for (key in SettingsResetPolicy.keysToRemove(prefs.all.keys)) {
                        editor.remove(key)
                    }
                    for ((key, value) in SettingsResetPolicy.seedValues()) {
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Float -> editor.putFloat(key, value)
                            is String -> editor.putString(key, value)
                            is Long -> editor.putLong(key, value)
                        }
                    }
                    editor.apply()

                    // Reset UI state
                    loadCurrentSettings()

                    Toast.makeText(_self,
                        _self.getString(R.string.settings_toast_reset_success),
                        Toast.LENGTH_SHORT).show()

                    // Recreate activity to refresh UI
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
}

