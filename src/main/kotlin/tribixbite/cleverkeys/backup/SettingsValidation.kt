package tribixbite.cleverkeys.backup

/**
 * Single source of truth for SharedPreferences-backup validation rules.
 *
 * The legacy IO-driven import path AND the new pure plan builder both
 * call into here. Adding a rule in one place is enough — diverging means
 * the preview lies about what will land in prefs.
 *
 * Source of truth port: BackupRestoreManager.isFloatPreference (lines
 * 976-1009 of BackupRestoreManager.kt as of v1.4.0). Subsequent tasks
 * (1.6) extend with INTERNAL_KEYS, isInternalPreference, and validate().
 */
object SettingsValidation {

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
}
