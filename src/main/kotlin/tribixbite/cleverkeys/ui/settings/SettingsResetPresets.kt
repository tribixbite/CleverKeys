package tribixbite.cleverkeys.ui.settings

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.Defaults
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
        lifecycleScope.launch {
            android.app.AlertDialog.Builder(_self)
                .setTitle(getString(R.string.settings_reset_dialog_title))
                .setMessage(getString(R.string.settings_reset_dialog_message))
                .setPositiveButton(getString(R.string.settings_reset_dialog_confirm)) { _, _ ->
                    // Reset all settings using Defaults constants
                    val editor = prefs.edit()
                    editor.clear()

                    // Appearance - use Defaults constants
                    editor.putString("theme", Defaults.THEME)
                    editor.putInt("keyboard_height", Defaults.KEYBOARD_HEIGHT_PORTRAIT)
                    editor.putInt("keyboard_height_landscape", Defaults.KEYBOARD_HEIGHT_LANDSCAPE)
                    editor.putInt("label_brightness", Defaults.LABEL_BRIGHTNESS)
                    editor.putInt("keyboard_opacity", Defaults.KEYBOARD_OPACITY)
                    editor.putInt("key_opacity", Defaults.KEY_OPACITY)
                    editor.putFloat("character_size", Defaults.CHARACTER_SIZE)

                    // Margins - use Defaults constants (0% bottom, 1% sides)
                    editor.putInt("margin_bottom_portrait", Defaults.MARGIN_BOTTOM_PORTRAIT)
                    editor.putInt("margin_bottom_landscape", Defaults.MARGIN_BOTTOM_LANDSCAPE)
                    editor.putInt("margin_left_portrait", Defaults.MARGIN_LEFT_PORTRAIT)
                    editor.putInt("margin_left_landscape", Defaults.MARGIN_LEFT_LANDSCAPE)
                    editor.putInt("margin_right_portrait", Defaults.MARGIN_RIGHT_PORTRAIT)
                    editor.putInt("margin_right_landscape", Defaults.MARGIN_RIGHT_LANDSCAPE)

                    // Short gesture settings - use Defaults constants
                    editor.putBoolean("short_gestures_enabled", Defaults.SHORT_GESTURES_ENABLED)
                    editor.putInt("short_gesture_min_distance", Defaults.SHORT_GESTURE_MIN_DISTANCE)
                    editor.putInt("short_gesture_max_distance", Defaults.SHORT_GESTURE_MAX_DISTANCE)

                    // Neural prediction - BALANCED profile (using Defaults constants)
                    editor.putInt("neural_beam_width", Defaults.NEURAL_BEAM_WIDTH)
                    editor.putInt("neural_max_length", Defaults.NEURAL_MAX_LENGTH)
                    editor.putFloat("neural_confidence_threshold", Defaults.NEURAL_CONFIDENCE_THRESHOLD)
                    editor.putFloat("neural_beam_alpha", Defaults.NEURAL_BEAM_ALPHA)
                    editor.putFloat("neural_beam_prune_confidence", Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE)
                    editor.putFloat("neural_beam_score_gap", Defaults.NEURAL_BEAM_SCORE_GAP)
                    editor.putInt("neural_adaptive_width_step", Defaults.NEURAL_ADAPTIVE_WIDTH_STEP)
                    editor.putInt("neural_score_gap_step", Defaults.NEURAL_SCORE_GAP_STEP)
                    editor.putFloat("neural_temperature", Defaults.NEURAL_TEMPERATURE)
                    editor.putFloat("neural_frequency_weight", Defaults.NEURAL_FREQUENCY_WEIGHT)
                    editor.putInt("swipe_smoothing_window", Defaults.SWIPE_SMOOTHING_WINDOW)
                    editor.putBoolean("neural_batch_beams", Defaults.NEURAL_BATCH_BEAMS)
                    editor.putBoolean("neural_greedy_search", Defaults.NEURAL_GREEDY_SEARCH)
                    editor.putInt("onnx_xnnpack_threads", Defaults.ONNX_XNNPACK_THREADS)

                    // Input behavior
                    editor.putBoolean("vibrate_custom", Defaults.VIBRATE_CUSTOM)
                    editor.putInt("vibrate_duration", Defaults.VIBRATE_DURATION)
                    editor.putInt("longpress_timeout", Defaults.LONGPRESS_TIMEOUT)
                    editor.putBoolean("keyrepeat_enabled", Defaults.KEYREPEAT_ENABLED)
                    editor.putBoolean("autocapitalisation", Defaults.AUTOCAPITALISATION)
                    editor.putBoolean("autocapitalize_i_words", Defaults.AUTOCAPITALIZE_I_WORDS)
                    editor.putBoolean("double_space_to_period", Defaults.DOUBLE_SPACE_TO_PERIOD)
                    editor.putBoolean("smart_punctuation", Defaults.SMART_PUNCTUATION)
                    editor.putInt("tap_duration_threshold", Defaults.TAP_DURATION_THRESHOLD)

                    // Haptic feedback
                    editor.putBoolean("haptic_key_press", Defaults.HAPTIC_KEY_PRESS)
                    editor.putBoolean("haptic_prediction_tap", Defaults.HAPTIC_PREDICTION_TAP)
                    editor.putBoolean("haptic_trackpoint_activate", Defaults.HAPTIC_TRACKPOINT_ACTIVATE)
                    editor.putBoolean("haptic_long_press", Defaults.HAPTIC_LONG_PRESS)
                    editor.putBoolean("haptic_swipe_complete", Defaults.HAPTIC_SWIPE_COMPLETE)

                    // Word prediction
                    editor.putBoolean("swipe_typing_enabled", Defaults.SWIPE_TYPING_ENABLED)
                    editor.putBoolean("word_prediction_enabled", Defaults.WORD_PREDICTION_ENABLED)
                    editor.putInt("suggestion_bar_opacity", Defaults.SUGGESTION_BAR_OPACITY)
                    editor.putBoolean("context_aware_predictions_enabled", Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED)
                    editor.putBoolean("personalized_learning_enabled", Defaults.PERSONALIZED_LEARNING_ENABLED)

                    // Autocorrect
                    editor.putBoolean("autocorrect_enabled", Defaults.AUTOCORRECT_ENABLED)
                    editor.putBoolean("swipe_beam_autocorrect_enabled", Defaults.SWIPE_BEAM_AUTOCORRECT_ENABLED)
                    editor.putBoolean("swipe_final_autocorrect_enabled", Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)
                    editor.putString("swipe_fuzzy_match_mode", Defaults.SWIPE_FUZZY_MATCH_MODE)

                    // Swipe trail
                    editor.putBoolean("swipe_trail_enabled", Defaults.SWIPE_TRAIL_ENABLED)
                    editor.putString("swipe_trail_effect", Defaults.SWIPE_TRAIL_EFFECT)

                    // Clipboard — all settings in sync with ClipboardSettingsActivity reset
                    editor.putBoolean("clipboard_history_enabled", Defaults.CLIPBOARD_HISTORY_ENABLED)
                    editor.putInt("clipboard_pane_height_percent", Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT)
                    editor.putInt("clipboard_history_limit", Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK)
                    editor.putString("clipboard_history_duration", Defaults.CLIPBOARD_HISTORY_DURATION)
                    editor.putString("clipboard_max_item_size_kb", Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB)
                    editor.putString("clipboard_limit_type", Defaults.CLIPBOARD_LIMIT_TYPE)
                    editor.putString("clipboard_size_limit_mb", Defaults.CLIPBOARD_SIZE_LIMIT_MB)
                    editor.putBoolean("clipboard_exclude_password_managers", Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS)
                    editor.putBoolean("clipboard_respect_sensitive_flag", Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG)
                    editor.putBoolean("clipboard_text_only", false)
                    editor.putBoolean("clipboard_pinned_enabled", true)
                    editor.putBoolean("clipboard_todo_enabled", true)

                    // Accessibility
                    editor.putBoolean("sticky_keys_enabled", Defaults.STICKY_KEYS_ENABLED)
                    editor.putInt("sticky_keys_timeout", Defaults.STICKY_KEYS_TIMEOUT)
                    editor.putBoolean("voice_guidance_enabled", Defaults.VOICE_GUIDANCE_ENABLED)

                    // Debug (off by default)
                    editor.putBoolean("debug_enabled", Defaults.DEBUG_ENABLED)
                    editor.putBoolean("termux_mode_enabled", Defaults.TERMUX_MODE_ENABLED)

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
}

internal fun SettingsActivity.fallbackEncrypted() {
        // Handle direct boot mode failure
        android.util.Log.w(SettingsActivity.TAG, "Settings unavailable in direct boot mode")
        finish()
}
