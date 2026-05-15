package tribixbite.cleverkeys.backup

/**
 * Single source of truth for SharedPreferences-backup validation rules.
 *
 * The legacy IO-driven import path AND the new pure plan builder both
 * call into here. Adding a rule in one place is enough — diverging means
 * the preview lies about what will land in prefs.
 *
 * Source of truth port: `BackupRestoreManager.validateIntPreference` +
 * `validateFloatPreference` + `validateStringPreference` +
 * `isInternalPreference` + `isFloatPreference`. Lines 750-1068 of
 * BackupRestoreManager.kt as of v1.4.0.
 */
object SettingsValidation {

    /**
     * Service-managed state that should never be imported or exported.
     * These are migration version markers + transient runtime state that
     * is meaningless across devices.
     *
     * `BackupRestoreManager.isInternalPreference` delegates here — this is
     * the single source of truth. Filtered from BOTH export (so backup
     * files never contain them) AND import (so legacy backups with these
     * keys are silently dropped).
     *
     * `SettingsDefaultsDriftTest` recognizes membership here as a valid
     * reason for a pref-read key to be absent from `SETTINGS_DEFAULTS`.
     */
    val INTERNAL_KEYS: Set<String> = setOf(
        "version",                    // Config migration version
        "current_layout_portrait",    // Runtime layout selection (per device)
        "current_layout_landscape",   // Runtime layout selection (per device)
        "margin_prefs_version",       // Margin-units migration version
        "need_migration",             // DirectBoot prefs-migration tracker
    )

    fun isInternalPreference(key: String): Boolean = key in INTERNAL_KEYS

    /**
     * Pref keys that the EXPORT used to seed as defaults but that no code
     * READS. Removing them from the live defaults map (and seeding logic)
     * means new backups don't contain them. But legacy backups in the
     * wild DO contain them — so the import plan builder filters these
     * out before the diff, so the user doesn't see a "(unset) → default"
     * noise row for a key that's about to be a no-op write into prefs
     * that nothing ever reads.
     *
     * The keys come from a historical state where `getAllDefaultPreferences()`
     * in BackupRestoreManager seeded both the "real" Config key (e.g.
     * `pref_enable_multilang`) AND a no-prefix duplicate (`enable_multilang`)
     * — and a few other cases of stale key names that survived migrations.
     */
    val DEPRECATED_KEYS: Set<String> = setOf(
        // Duplicates of Config's `pref_*` keys, never read:
        "enable_multilang",
        "primary_language",
        "auto_detect_language",
        "language_detection_sensitivity",
        // Renamed/superseded keys, never read at this name:
        "double_tap_lock_shift",          // Config reads `lock_double_tap`
        "autocorrect_min_frequency",      // Config reads `autocorrect_confidence_min_frequency`
        "keyboard_height_percent",        // Superseded by `keyboard_height`
        "extra_key_switch_greekmath",     // Never read; legacy
    )

    fun isDeprecatedPreference(key: String): Boolean = key in DEPRECATED_KEYS

    /**
     * Mirrors BackupRestoreManager.isFloatPreference (lines 976-1009).
     * Drives the IntV vs FloatV dispatch in
     * SettingsImportPlanBuilder.parsePrefValue — using JSON shape instead of
     * key membership corrupts integer-multiple float values (e.g.
     * swipe_trail_width=5.0). Verbatim port of the full when-block + the two
     * prefix patterns.
     */
    fun isFloatPreference(key: String): Boolean {
        if (key.startsWith("neural_prefix_boost_multiplier_") ||
            key.startsWith("neural_prefix_boost_max_")) return true
        return when (key) {
            // Character and UI sizing
            "character_size", "key_vertical_margin", "key_horizontal_margin", "custom_border_line_width",
            // Prediction weights
            "prediction_context_boost", "prediction_frequency_scale",
            // Auto-correction threshold
            "autocorrect_char_match_threshold",
            // Neural confidence threshold
            "neural_confidence_threshold",
            // Swipe typing boost parameters (SlideBarPreference floats)
            "swipe_rare_words_penalty", "swipe_common_words_boost", "swipe_top5000_boost",
            // Advanced gesture tuning floats
            "slider_speed_smoothing", "slider_speed_max",
            "swipe_min_distance", "swipe_min_key_distance", "swipe_noise_threshold",
            "swipe_high_velocity_threshold",
            // Neural beam search floats
            "neural_beam_score_gap", "neural_beam_prune_confidence", "neural_beam_alpha",
            "neural_temperature", "neural_frequency_weight",
            // Language detection
            "pref_language_detection_sensitivity",
            // Swipe trail appearance
            "swipe_trail_width", "swipe_trail_glow_radius",
            // Global prefix boost defaults (fallback)
            "neural_prefix_boost_multiplier", "neural_prefix_boost_max" -> true
            else -> false
        }
    }

    /**
     * Returns null if the proposed value is valid for the key, or an
     * error message suitable for `SkippedKey.reason` if not.
     *
     * The rule set is exactly the union of the four `validate*Preference`
     * methods in BackupRestoreManager (lines 750-1068 as of v1.4.0). The
     * legacy validators are version-tolerant: unknown keys default to
     * accept (return true). The pure plan builder mirrors that — unknown
     * keys are accepted so this validator is purely a constraint guard.
     *
     * Type mismatch (e.g. PrefValue.Str passed for an Int-typed key) is
     * reported as an error string. The legacy IO path silently fell back
     * to the wrong validator for the JSON shape — this port is stricter.
     */
    fun validate(key: String, value: PrefValue): String? {
        return when (value) {
            is PrefValue.Bool -> validateBool(key, value.v)
            is PrefValue.IntV -> validateInt(key, value.v)
            is PrefValue.FloatV -> validateFloat(key, value.v)
            is PrefValue.Str -> validateString(key, value.v)
            is PrefValue.JsonBlob -> null   // JSON-blob prefs are validated by their own consumers (ListGroupPreference)
            is PrefValue.Unset -> "value is unset"
        }
    }

    /**
     * Bool prefs have no explicit range checks in the legacy validator;
     * any boolean key is accepted (else branch returned true). Reject
     * type-mismatch only when an int-allowlisted key receives a Bool.
     */
    private fun validateBool(key: String, @Suppress("UNUSED_PARAMETER") value: Boolean): String? {
        if (isIntKey(key)) return "expected Int, got Bool"
        if (isFloatPreference(key)) return "expected Float, got Bool"
        if (isStringValidatedKey(key)) return "expected String, got Bool"
        return null
    }

    /**
     * Verbatim port of BackupRestoreManager.validateIntPreference (lines 750-806).
     */
    private fun validateInt(key: String, value: Int): String? {
        if (isFloatPreference(key)) return "expected Float, got Int"
        val ok = when (key) {
            // Opacity values (0-100)
            "label_brightness", "keyboard_opacity", "key_opacity",
            "key_activated_opacity", "suggestion_bar_opacity" -> value in 0..100

            // Keyboard height percentages
            "keyboard_height", "keyboard_height_unfolded" -> value in 10..100
            "keyboard_height_landscape", "keyboard_height_landscape_unfolded" -> value in 20..65

            // Bottom margins (0-30% of screen height)
            "margin_bottom_portrait", "margin_bottom_landscape",
            "margin_bottom_portrait_unfolded", "margin_bottom_landscape_unfolded" -> value in 0..30

            // Left/right margins (0-45% of screen width each, capped at 90% total)
            "margin_left_portrait", "margin_left_landscape",
            "margin_left_portrait_unfolded", "margin_left_landscape_unfolded",
            "margin_right_portrait", "margin_right_landscape",
            "margin_right_portrait_unfolded", "margin_right_landscape_unfolded" -> value in 0..45

            // Legacy horizontal_margin (kept for backward compatibility, 0-200 dp)
            "horizontal_margin_portrait", "horizontal_margin_landscape",
            "horizontal_margin_portrait_unfolded", "horizontal_margin_landscape_unfolded" -> value in 0..200

            // Border radius (0-100%)
            "custom_border_radius" -> value in 0..100

            // Timing values (milliseconds)
            "vibrate_duration" -> value in 0..100
            "longpress_timeout" -> value in 50..2000
            "longpress_interval" -> value in 5..100

            // Short gesture distance (10-95% min, 50-200% max)
            "short_gesture_min_distance" -> value in 10..95
            "short_gesture_max_distance" -> value in 50..200

            // Neural network parameters
            "neural_beam_width" -> value in 1..32
            "neural_max_length" -> value in 10..100
            "neural_user_max_seq_length" -> value in 0..500
            "neural_adaptive_width_step" -> value in 3..20
            "neural_score_gap_step" -> value in 3..20
            "swipe_smoothing_window" -> value in 1..7

            // Auto-correction parameters
            "autocorrect_min_word_length" -> value in 2..5
            "autocorrect_confidence_min_frequency" -> value in 100..5000

            // Clipboard history limit (0 = unlimited)
            "clipboard_history_limit" -> value in 0..500

            // Circle sensitivity
            "circle_sensitivity" -> value in 1..5

            // Unknown integer preference - allow it (version-tolerant)
            else -> true
        }
        return if (ok) null else "out of range, got $value"
    }

    /**
     * Verbatim port of BackupRestoreManager.validateFloatPreference (lines 812-858).
     */
    private fun validateFloat(key: String, value: Float): String? {
        if (key.startsWith("neural_prefix_boost_multiplier_")) {
            return if (value in 0.0f..3.0f) null else "out of range, got $value"
        }
        if (key.startsWith("neural_prefix_boost_max_")) {
            return if (value in 0.0f..10.0f) null else "out of range, got $value"
        }

        val ok = when (key) {
            // Character size (0.75-1.5)
            "character_size" -> value in 0.75f..1.5f

            // Margins (0-5%)
            "key_vertical_margin", "key_horizontal_margin" -> value in 0f..5f

            // Border line width (0-5 dp)
            "custom_border_line_width" -> value in 0f..5f

            // Prediction weights
            "prediction_context_boost" -> value in 0.5f..5.0f
            "prediction_frequency_scale" -> value in 100.0f..5000.0f

            // Auto-correction threshold
            "autocorrect_char_match_threshold" -> value in 0.5f..0.9f

            // Neural confidence threshold
            "neural_confidence_threshold" -> value in 0.0f..1.0f

            // Neural beam search parameters
            "neural_beam_score_gap" -> value in 0.0f..150.0f
            "neural_beam_prune_confidence" -> value in 0.0f..1.0f
            "neural_beam_alpha" -> value in 0.0f..10.0f
            "neural_temperature" -> value in 0.1f..3.0f
            "neural_frequency_weight" -> value in 0.0f..2.0f

            // Swipe typing boost parameters (0.0-2.0 range)
            "swipe_rare_words_penalty", "swipe_common_words_boost", "swipe_top5000_boost" -> value in 0.0f..2.0f

            // Global prefix boost defaults
            "neural_prefix_boost_multiplier" -> value in 0.0f..3.0f
            "neural_prefix_boost_max" -> value in 0.0f..10.0f

            // Unknown float preference - allow it (version-tolerant)
            else -> true
        }
        return if (ok) null else "out of range, got $value"
    }

    /**
     * Verbatim port of BackupRestoreManager.validateStringPreference (lines 1037-1068).
     * Adds a type-mismatch guard: if the key is in the int- or float-typed
     * allowlist, reject — the legacy IO path got away with this because Gson
     * tagged the JSON primitive as String long before reaching the int/float
     * validators, but the pure builder routes by PrefValue and must not
     * silently accept a stringly-typed value for a numeric pref.
     */
    private fun validateString(key: String, value: String?): String? {
        if (value == null) return "value is null"
        if (isIntKey(key)) return "expected Int, got String"
        if (isFloatPreference(key)) return "expected Float, got String"

        val ok = when (key) {
            // Theme values - relaxed validation for forward compatibility
            "theme" -> value.isNotEmpty()

            // Number row options
            "number_row" -> value.matches(Regex("no_number_row|no_symbols|symbols"))

            // Show numpad options
            "show_numpad" -> value.matches(Regex("never|always|landscape|[0-9]+"))

            // Numpad layout
            "numpad_layout" -> value.matches(Regex("high_first|low_first|default"))

            // Number entry layout
            "number_entry_layout" -> value.matches(Regex("pin|number"))

            // Circle sensitivity (string representation)
            "circle_sensitivity" -> value.matches(Regex("[1-5]"))

            // Slider sensitivity (string representation)
            "slider_sensitivity" -> value.matches(Regex("[0-9]+"))

            // Swipe distance (string representation)
            "swipe_dist" -> value.matches(Regex("[0-9]+(\\.[0-9]+)?"))

            // Unknown string preference - allow it (version-tolerant)
            else -> true
        }
        return if (ok) null else "invalid string value, got \"$value\""
    }

    /**
     * Helper: `true` if the key is in the int-validated allowlist (used for
     * type-mismatch detection when a Bool is passed for an int-allowlisted key).
     */
    private fun isIntKey(key: String): Boolean = when (key) {
        "label_brightness", "keyboard_opacity", "key_opacity",
        "key_activated_opacity", "suggestion_bar_opacity",
        "keyboard_height", "keyboard_height_unfolded",
        "keyboard_height_landscape", "keyboard_height_landscape_unfolded",
        "margin_bottom_portrait", "margin_bottom_landscape",
        "margin_bottom_portrait_unfolded", "margin_bottom_landscape_unfolded",
        "margin_left_portrait", "margin_left_landscape",
        "margin_left_portrait_unfolded", "margin_left_landscape_unfolded",
        "margin_right_portrait", "margin_right_landscape",
        "margin_right_portrait_unfolded", "margin_right_landscape_unfolded",
        "horizontal_margin_portrait", "horizontal_margin_landscape",
        "horizontal_margin_portrait_unfolded", "horizontal_margin_landscape_unfolded",
        "custom_border_radius",
        "vibrate_duration", "longpress_timeout", "longpress_interval",
        "short_gesture_min_distance", "short_gesture_max_distance",
        "neural_beam_width", "neural_max_length", "neural_user_max_seq_length",
        "neural_adaptive_width_step", "neural_score_gap_step", "swipe_smoothing_window",
        "autocorrect_min_word_length", "autocorrect_confidence_min_frequency",
        "clipboard_history_limit", "circle_sensitivity" -> true
        else -> false
    }

    /**
     * Helper: `true` if the key is in the string-validated allowlist (used for
     * type-mismatch detection).
     */
    private fun isStringValidatedKey(key: String): Boolean = when (key) {
        "theme", "number_row", "show_numpad", "numpad_layout",
        "number_entry_layout", "circle_sensitivity", "slider_sensitivity",
        "swipe_dist" -> true
        else -> false
    }
}
