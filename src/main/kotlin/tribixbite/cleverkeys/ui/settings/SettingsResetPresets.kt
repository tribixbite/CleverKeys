package tribixbite.cleverkeys.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity

/**
 * Swipe sensitivity presets offered in Gesture Tuning.
 *
 * The preset is never persisted — it is derived from the three stored thresholds — so the
 * enum is the only identifier; [labelRes] is what the dropdown shows (localized). Keeping the
 * identifier separate from the label means switching the app language cannot break the
 * "which preset is active" match (it previously compared English label text).
 */
internal enum class SwipeSensitivityPreset(@StringRes val labelRes: Int) {
    LOW(R.string.settings_sensitivity_preset_low),
    MEDIUM(R.string.settings_sensitivity_preset_medium),
    HIGH(R.string.settings_sensitivity_preset_high),
    CUSTOM(R.string.settings_sensitivity_preset_custom),
}

/** The three thresholds a sensitivity preset sets: swipe distance (px), key distance (px), dwell (ms). */
internal data class SwipeThresholds(val minDistance: Float, val minKeyDistance: Float, val minDwellMs: Int)

/**
 * What each [SwipeSensitivityPreset] sets, and the sliders' ranges and steps (Gesture Tuning uses
 * these, so every preset value is a stop of its slider). Pure; pinned by SwipeSensitivityPresetsTest.
 *
 * Medium is the shipped defaults. It set 50 px / 40 px / 15 ms while the defaults are 72 / 15 / 7
 * (2026-11-24), so choosing it doubled the dwell, past the 8.3 ms frame gap of a 120 Hz screen,
 * which drops the second key of fast short swipes (`in`, `is`, `was`), and the defaults showed as
 * Custom. High's key distance was 25 px, above the 15 px default it should undercut; 15 is the
 * slider's minimum, so High now shares it.
 */
internal object SwipeSensitivityPresets {
    val MIN_DISTANCE_RANGE = 20f..100f
    const val MIN_DISTANCE_STEP = 1f
    val MIN_KEY_DISTANCE_RANGE = 15f..80f
    const val MIN_KEY_DISTANCE_STEP = 5f
    val MIN_DWELL_RANGE = 0f..50f
    const val MIN_DWELL_STEP = 1f

    /** The thresholds [preset] sets, or null for [SwipeSensitivityPreset.CUSTOM] (it sets none). */
    fun thresholds(preset: SwipeSensitivityPreset): SwipeThresholds? = when (preset) {
        // Higher thresholds: less sensitive.
        SwipeSensitivityPreset.LOW -> SwipeThresholds(80f, 60f, 30)
        SwipeSensitivityPreset.MEDIUM -> SwipeThresholds(
            Defaults.SWIPE_MIN_DISTANCE, Defaults.SWIPE_MIN_KEY_DISTANCE, Defaults.SWIPE_MIN_DWELL_TIME,
        )
        // Lower thresholds: more sensitive.
        SwipeSensitivityPreset.HIGH -> SwipeThresholds(30f, 15f, 5)
        SwipeSensitivityPreset.CUSTOM -> null
    }

    /** The preset whose thresholds are exactly [current], else [SwipeSensitivityPreset.CUSTOM]. */
    fun presetFor(current: SwipeThresholds): SwipeSensitivityPreset =
        SwipeSensitivityPreset.entries.firstOrNull { thresholds(it) == current } ?: SwipeSensitivityPreset.CUSTOM
}

/** The preset the current three thresholds match, else Custom. */
internal fun SettingsActivity.getSwipeSensitivityPreset(): SwipeSensitivityPreset =
    SwipeSensitivityPresets.presetFor(SwipeThresholds(swipeMinDistance, swipeMinKeyDistance, swipeMinDwellTime))

/** Set and save the three thresholds of [preset]; Custom changes nothing. */
internal fun SettingsActivity.applySwipeSensitivityPreset(preset: SwipeSensitivityPreset) {
    val t = SwipeSensitivityPresets.thresholds(preset) ?: return
    swipeMinDistance = t.minDistance
    swipeMinKeyDistance = t.minKeyDistance
    swipeMinDwellTime = t.minDwellMs
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

