package tribixbite.cleverkeys.ui.settings

import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.backup.NON_DEFAULTED_KEYS
import tribixbite.cleverkeys.backup.SETTINGS_DEFAULTS
import tribixbite.cleverkeys.backup.SettingsValidation

/**
 * The "Reset Settings" policy (audit F-1/F-2, 2026-09-06).
 *
 * The previous implementation wholesale-cleared the MAIN shared prefs file
 * and re-seeded ~45 settings keys. That file also holds the custom
 * dictionary (`custom_words_<lang>` / `disabled_words_<lang>`), the enabled
 * `layouts` list, `extra_keys` / `custom_extra_keys`, per-language prefs,
 * custom theme definitions, backup-encryption state, AND every migration
 * marker — `clear()` destroyed all of it, and losing `margin_prefs_version`
 * additionally re-ran the dp→percent margin migration over freshly-written
 * percent values on the next process start (F-2), mangling them.
 *
 * This object is the pure decision core: a reset REMOVES only classified
 * settings keys and re-seeds the explicit default list. Everything not in the
 * settings universe survives untouched. `SettingsResetPolicyTest` pins the
 * behavior and bans whole-file clears on the shared file repo-wide
 * (cross-cutting ratchet #7).
 */
object SettingsResetPolicy {

    /**
     * Engine-choice keys a full reset deliberately leaves alone — pre-existing
     * behavior carried over from the old body's G5 comment: `swipe_engine_mode`
     * and the geo_* knobs survive a preset reset, and `ctc_beam_width` follows
     * the geo_* precedent. Remove a key from this set if that decision changes.
     */
    val PRESERVED_SETTINGS_KEYS: Set<String> = setOf(
        "swipe_engine_mode",
        "ctc_beam_width",
        "geo_max_results",
        "geo_frequency_weight",
        "geo_endpoint_inset_kw",
    )

    /**
     * Keys a full reset may REMOVE, given every key currently present in the
     * prefs file: the classified settings universe (SETTINGS_DEFAULTS ∪
     * NON_DEFAULTED_KEYS ∪ the seed list) plus deprecated legacy keys (which
     * have no reader — removing them keeps them out of the next export),
     * minus the deliberately-preserved engine keys.
     *
     * INTERNAL_KEYS (migration markers, device-bound runtime state) are never
     * in the universe, so they always survive — that is the F-2 fix.
     * Dictionary keys (`custom_words_*` / `disabled_words_*`), `layouts` and
     * `extra_keys*` are not settings and always survive — that is the F-1 fix.
     */
    fun keysToRemove(existingKeys: Set<String>): Set<String> {
        val settingsUniverse = SETTINGS_DEFAULTS.keys + NON_DEFAULTED_KEYS + seedValues().keys
        return existingKeys.asSequence()
            .filter { it in settingsUniverse || SettingsValidation.isDeprecatedPreference(it) }
            .filterNot { it in PRESERVED_SETTINGS_KEYS }
            .toSet()
    }

    /**
     * The explicit key → default seed a reset writes after the removals.
     * Same list (keys, values, and stored types) the old body `put` after
     * `clear()`, so reset semantics are unchanged for actual settings.
     *
     * ARC-052 still applies: never seed a `SettingsValidation.DEPRECATED_KEYS`
     * member from here (`swipe_fuzzy_match_mode` history) — the policy test
     * asserts it.
     */
    fun seedValues(): Map<String, Any> = buildMap {
        // Appearance
        put("theme", Defaults.THEME)
        put("keyboard_height", Defaults.KEYBOARD_HEIGHT_PORTRAIT)
        put("keyboard_height_landscape", Defaults.KEYBOARD_HEIGHT_LANDSCAPE)
        put("label_brightness", Defaults.LABEL_BRIGHTNESS)
        put("keyboard_opacity", Defaults.KEYBOARD_OPACITY)
        put("key_opacity", Defaults.KEY_OPACITY)
        put("character_size", Defaults.CHARACTER_SIZE)

        // Margins (0% bottom, 1% sides)
        put("margin_bottom_portrait", Defaults.MARGIN_BOTTOM_PORTRAIT)
        put("margin_bottom_landscape", Defaults.MARGIN_BOTTOM_LANDSCAPE)
        put("margin_left_portrait", Defaults.MARGIN_LEFT_PORTRAIT)
        put("margin_left_landscape", Defaults.MARGIN_LEFT_LANDSCAPE)
        put("margin_right_portrait", Defaults.MARGIN_RIGHT_PORTRAIT)
        put("margin_right_landscape", Defaults.MARGIN_RIGHT_LANDSCAPE)

        // Short gestures
        put("short_gestures_enabled", Defaults.SHORT_GESTURES_ENABLED)
        put("short_gesture_min_distance", Defaults.SHORT_GESTURE_MIN_DISTANCE)
        put("short_gesture_max_distance", Defaults.SHORT_GESTURE_MAX_DISTANCE)

        // Swipe decoding (engine-mode / geo_* / ctc_beam_width are PRESERVED, see above)
        put("swipe_smoothing_window", Defaults.SWIPE_SMOOTHING_WINDOW)
        put("onnx_xnnpack_threads", Defaults.ONNX_XNNPACK_THREADS)

        // Input behavior
        put("vibrate_custom", Defaults.VIBRATE_CUSTOM)
        put("vibrate_duration", Defaults.VIBRATE_DURATION)
        put("longpress_timeout", Defaults.LONGPRESS_TIMEOUT)
        put("keyrepeat_enabled", Defaults.KEYREPEAT_ENABLED)
        put("autocapitalisation", Defaults.AUTOCAPITALISATION)
        put("autocapitalize_i_words", Defaults.AUTOCAPITALIZE_I_WORDS)
        put("double_space_to_period", Defaults.DOUBLE_SPACE_TO_PERIOD)
        put("smart_punctuation", Defaults.SMART_PUNCTUATION)
        put("tap_duration_threshold", Defaults.TAP_DURATION_THRESHOLD)

        // Haptic feedback
        put("haptic_key_press", Defaults.HAPTIC_KEY_PRESS)
        put("haptic_prediction_tap", Defaults.HAPTIC_PREDICTION_TAP)
        put("haptic_trackpoint_activate", Defaults.HAPTIC_TRACKPOINT_ACTIVATE)
        put("haptic_long_press", Defaults.HAPTIC_LONG_PRESS)
        put("haptic_swipe_complete", Defaults.HAPTIC_SWIPE_COMPLETE)

        // Word prediction
        put("swipe_typing_enabled", Defaults.SWIPE_TYPING_ENABLED)
        put("word_prediction_enabled", Defaults.WORD_PREDICTION_ENABLED)
        put("suggestion_bar_opacity", Defaults.SUGGESTION_BAR_OPACITY)
        put("context_aware_predictions_enabled", Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED)
        put("personalized_learning_enabled", Defaults.PERSONALIZED_LEARNING_ENABLED)

        // Autocorrect (ARC-052: swipe_fuzzy_match_mode must never be seeded)
        put("autocorrect_enabled", Defaults.AUTOCORRECT_ENABLED)
        put("swipe_final_autocorrect_enabled", Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)

        // Swipe trail
        put("swipe_trail_enabled", Defaults.SWIPE_TRAIL_ENABLED)
        put("swipe_trail_effect", Defaults.SWIPE_TRAIL_EFFECT)

        // Clipboard — keys kept in sync with ui/settings/sections/ClipboardSection.kt
        put("clipboard_history_enabled", Defaults.CLIPBOARD_HISTORY_ENABLED)
        put("clipboard_pane_height_percent", Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT)
        put("clipboard_history_limit", Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK)
        put("clipboard_history_duration", Defaults.CLIPBOARD_HISTORY_DURATION)
        put("clipboard_max_item_size_kb", Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB)
        put("clipboard_limit_type", Defaults.CLIPBOARD_LIMIT_TYPE)
        put("clipboard_size_limit_mb", Defaults.CLIPBOARD_SIZE_LIMIT_MB)
        put("clipboard_exclude_password_managers", Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS)
        put("clipboard_respect_sensitive_flag", Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG)
        put("clipboard_text_only", false)
        put("clipboard_pinned_enabled", true)
        put("clipboard_todo_enabled", true)

        // Debug (off by default)
        put("debug_enabled", Defaults.DEBUG_ENABLED)
        put("termux_mode_enabled", Defaults.TERMUX_MODE_ENABLED)
    }
}
