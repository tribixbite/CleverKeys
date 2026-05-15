package tribixbite.cleverkeys.backup

import tribixbite.cleverkeys.Defaults

/**
 * Effective compile-time defaults for every preference key the app reads.
 *
 * # Contract
 *
 * Used by `SettingsImportPlanBuilder.fromJson` to suppress preview rows
 * where the imported value equals what the user would experience anyway.
 * On a fresh install the prefs map is empty — without this map, every key
 * in the import file appears as ADDED (`current=Unset → proposed=X`),
 * including keys whose proposed value equals the default the user
 * already has in effect.
 *
 * # Three-bucket classification
 *
 * Every pref key read in `src/main/kotlin/` must fall into exactly one of:
 *
 *   1. **`SETTINGS_DEFAULTS`** (this map) — keys with a known compile-time
 *      default value. The import diff compares against this default and
 *      skips rows where `proposed == default`. Rows that DO change show
 *      the default as `current` so the dialog renders a real before/after.
 *
 *   2. **`NON_DEFAULTED_KEYS`** (below) — keys whose runtime default is
 *      literally `null` (e.g. optional URIs). These intentionally fall
 *      through to `current=PrefValue.Unset` in the preview.
 *
 *   3. **`SettingsValidation.INTERNAL_KEYS`** — migration-version markers
 *      and runtime-device state that should never be imported or exported.
 *      Filtered before the diff even runs.
 *
 * `SettingsDefaultsDriftTest` walks the source tree and asserts every
 * pref-read key is classified. **If that test fails, classify the key
 * into one of the three buckets** — do not just suppress the failure.
 *
 * # Type discipline
 *
 *   - `PrefValue.IntV(...)` for keys read via `getInt` / `safeGetInt`.
 *   - `PrefValue.FloatV(...)` for keys read via `getFloat`/`safeGetFloat`.
 *   - `PrefValue.Str(...)` for keys read via `getString`/`safeGetString`,
 *     INCLUDING numeric-looking strings like `"50"` (some sliders store
 *     stringly-typed values for backwards compat with legacy XML prefs).
 */
internal val SETTINGS_DEFAULTS: Map<String, PrefValue> = mapOf(
    // ── Appearance ────────────────────────────────────────────────────
    "theme" to PrefValue.Str(Defaults.THEME),
    "keyboard_height" to PrefValue.IntV(Defaults.KEYBOARD_HEIGHT_PORTRAIT),
    "keyboard_height_landscape" to PrefValue.IntV(Defaults.KEYBOARD_HEIGHT_LANDSCAPE),
    "label_brightness" to PrefValue.IntV(Defaults.LABEL_BRIGHTNESS),
    "keyboard_opacity" to PrefValue.IntV(Defaults.KEYBOARD_OPACITY),
    "key_opacity" to PrefValue.IntV(Defaults.KEY_OPACITY),
    "key_activated_opacity" to PrefValue.IntV(Defaults.KEY_ACTIVATED_OPACITY),
    "character_size" to PrefValue.FloatV(Defaults.CHARACTER_SIZE),
    "key_vertical_margin" to PrefValue.FloatV(Defaults.KEY_VERTICAL_MARGIN),
    "key_horizontal_margin" to PrefValue.FloatV(Defaults.KEY_HORIZONTAL_MARGIN),
    "border_config" to PrefValue.Bool(Defaults.BORDER_CONFIG),
    "custom_border_radius" to PrefValue.IntV(Defaults.CUSTOM_BORDER_RADIUS),
    "custom_border_line_width" to PrefValue.FloatV(Defaults.CUSTOM_BORDER_LINE_WIDTH.toFloat()),

    // ── Margins (percent of screen dimension) ─────────────────────────
    "margin_bottom_portrait" to PrefValue.IntV(Defaults.MARGIN_BOTTOM_PORTRAIT),
    "margin_bottom_landscape" to PrefValue.IntV(Defaults.MARGIN_BOTTOM_LANDSCAPE),
    "margin_left_portrait" to PrefValue.IntV(Defaults.MARGIN_LEFT_PORTRAIT),
    "margin_left_landscape" to PrefValue.IntV(Defaults.MARGIN_LEFT_LANDSCAPE),
    "margin_right_portrait" to PrefValue.IntV(Defaults.MARGIN_RIGHT_PORTRAIT),
    "margin_right_landscape" to PrefValue.IntV(Defaults.MARGIN_RIGHT_LANDSCAPE),

    // ── Layout ────────────────────────────────────────────────────────
    "show_numpad" to PrefValue.Str(Defaults.SHOW_NUMPAD),
    "numpad_layout" to PrefValue.Str(Defaults.NUMPAD_LAYOUT),
    "number_row" to PrefValue.Str(Defaults.NUMBER_ROW),
    "number_entry_layout" to PrefValue.Str(Defaults.NUMBER_ENTRY_LAYOUT),
    "scale_numpad_height" to PrefValue.Bool(Defaults.SCALE_NUMPAD_HEIGHT),

    // ── Input behavior ───────────────────────────────────────────────
    "vibrate_custom" to PrefValue.Bool(Defaults.VIBRATE_CUSTOM),
    "vibrate_duration" to PrefValue.IntV(Defaults.VIBRATE_DURATION),
    "vibration_enabled" to PrefValue.Bool(Defaults.HAPTIC_ENABLED),
    "haptic_key_press" to PrefValue.Bool(Defaults.HAPTIC_KEY_PRESS),
    "haptic_prediction_tap" to PrefValue.Bool(Defaults.HAPTIC_PREDICTION_TAP),
    "haptic_trackpoint_activate" to PrefValue.Bool(Defaults.HAPTIC_TRACKPOINT_ACTIVATE),
    "haptic_long_press" to PrefValue.Bool(Defaults.HAPTIC_LONG_PRESS),
    "haptic_swipe_complete" to PrefValue.Bool(Defaults.HAPTIC_SWIPE_COMPLETE),
    "longpress_timeout" to PrefValue.IntV(Defaults.LONGPRESS_TIMEOUT),
    "longpress_interval" to PrefValue.IntV(Defaults.LONGPRESS_INTERVAL),
    "keyrepeat_enabled" to PrefValue.Bool(Defaults.KEYREPEAT_ENABLED),
    "keyrepeat_backspace_only" to PrefValue.Bool(Defaults.KEYREPEAT_BACKSPACE_ONLY),
    "lock_double_tap" to PrefValue.Bool(Defaults.DOUBLE_TAP_LOCK_SHIFT),
    "autocapitalisation" to PrefValue.Bool(Defaults.AUTOCAPITALISATION),
    "switch_input_immediate" to PrefValue.Bool(Defaults.SWITCH_INPUT_IMMEDIATE),
    "smart_punctuation" to PrefValue.Bool(Defaults.SMART_PUNCTUATION),
    "tap_duration_threshold" to PrefValue.IntV(Defaults.TAP_DURATION_THRESHOLD),

    // ── Gesture ───────────────────────────────────────────────────────
    "swipe_dist" to PrefValue.Str(Defaults.SWIPE_DIST),
    "slider_sensitivity" to PrefValue.Str(Defaults.SLIDER_SENSITIVITY),
    "circle_sensitivity" to PrefValue.Str(Defaults.CIRCLE_SENSITIVITY),
    "double_space_to_period" to PrefValue.Bool(Defaults.DOUBLE_SPACE_TO_PERIOD),
    "double_space_threshold" to PrefValue.IntV(Defaults.DOUBLE_SPACE_THRESHOLD),
    "swipe_min_distance" to PrefValue.FloatV(Defaults.SWIPE_MIN_DISTANCE),
    "swipe_min_key_distance" to PrefValue.FloatV(Defaults.SWIPE_MIN_KEY_DISTANCE),
    "swipe_min_dwell_time" to PrefValue.IntV(Defaults.SWIPE_MIN_DWELL_TIME),
    "swipe_noise_threshold" to PrefValue.FloatV(Defaults.SWIPE_NOISE_THRESHOLD),
    "swipe_high_velocity_threshold" to PrefValue.FloatV(Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD),
    "finger_occlusion_offset" to PrefValue.FloatV(Defaults.FINGER_OCCLUSION_OFFSET),
    "slider_speed_smoothing" to PrefValue.FloatV(Defaults.SLIDER_SPEED_SMOOTHING),
    "slider_speed_max" to PrefValue.FloatV(Defaults.SLIDER_SPEED_MAX),

    // ── Short gestures ───────────────────────────────────────────────
    "short_gestures_enabled" to PrefValue.Bool(Defaults.SHORT_GESTURES_ENABLED),
    "short_gesture_min_distance" to PrefValue.IntV(Defaults.SHORT_GESTURE_MIN_DISTANCE),
    "short_gesture_max_distance" to PrefValue.IntV(Defaults.SHORT_GESTURE_MAX_DISTANCE),

    // ── Selection-delete ─────────────────────────────────────────────
    "selection_delete_vertical_threshold" to PrefValue.IntV(Defaults.SELECTION_DELETE_VERTICAL_THRESHOLD),
    "selection_delete_vertical_speed" to PrefValue.FloatV(Defaults.SELECTION_DELETE_VERTICAL_SPEED),

    // ── Swipe trail ──────────────────────────────────────────────────
    "swipe_trail_enabled" to PrefValue.Bool(Defaults.SWIPE_TRAIL_ENABLED),
    "swipe_trail_effect" to PrefValue.Str(Defaults.SWIPE_TRAIL_EFFECT),
    "swipe_trail_color" to PrefValue.IntV(Defaults.SWIPE_TRAIL_COLOR),
    "swipe_trail_width" to PrefValue.FloatV(Defaults.SWIPE_TRAIL_WIDTH),
    "swipe_trail_glow_radius" to PrefValue.FloatV(Defaults.SWIPE_TRAIL_GLOW_RADIUS),

    // ── Neural prediction ────────────────────────────────────────────
    "neural_beam_width" to PrefValue.IntV(Defaults.NEURAL_BEAM_WIDTH),
    "neural_max_length" to PrefValue.IntV(Defaults.NEURAL_MAX_LENGTH),
    "neural_confidence_threshold" to PrefValue.FloatV(Defaults.NEURAL_CONFIDENCE_THRESHOLD),
    "neural_batch_beams" to PrefValue.Bool(Defaults.NEURAL_BATCH_BEAMS),
    "neural_greedy_search" to PrefValue.Bool(Defaults.NEURAL_GREEDY_SEARCH),
    "neural_beam_alpha" to PrefValue.FloatV(Defaults.NEURAL_BEAM_ALPHA),
    "neural_beam_prune_confidence" to PrefValue.FloatV(Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE),
    "neural_beam_score_gap" to PrefValue.FloatV(Defaults.NEURAL_BEAM_SCORE_GAP),
    "neural_adaptive_width_step" to PrefValue.IntV(Defaults.NEURAL_ADAPTIVE_WIDTH_STEP),
    "neural_score_gap_step" to PrefValue.IntV(Defaults.NEURAL_SCORE_GAP_STEP),
    "neural_temperature" to PrefValue.FloatV(Defaults.NEURAL_TEMPERATURE),
    "neural_frequency_weight" to PrefValue.FloatV(Defaults.NEURAL_FREQUENCY_WEIGHT),
    "neural_prefix_boost_multiplier" to PrefValue.FloatV(Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER),
    "neural_prefix_boost_max" to PrefValue.FloatV(Defaults.NEURAL_PREFIX_BOOST_MAX),
    "neural_max_cumulative_boost" to PrefValue.FloatV(Defaults.NEURAL_MAX_CUMULATIVE_BOOST),
    "neural_strict_start_char" to PrefValue.Bool(Defaults.NEURAL_STRICT_START_CHAR),
    "neural_resampling_mode" to PrefValue.Str(Defaults.NEURAL_RESAMPLING_MODE),
    "neural_user_max_seq_length" to PrefValue.IntV(Defaults.NEURAL_USER_MAX_SEQ_LENGTH),
    "swipe_smoothing_window" to PrefValue.IntV(Defaults.SWIPE_SMOOTHING_WINDOW),
    "onnx_xnnpack_threads" to PrefValue.IntV(Defaults.ONNX_XNNPACK_THREADS),

    // ── Word prediction ──────────────────────────────────────────────
    "swipe_typing_enabled" to PrefValue.Bool(Defaults.SWIPE_TYPING_ENABLED),
    "swipe_on_password_fields" to PrefValue.Bool(Defaults.SWIPE_ON_PASSWORD_FIELDS),
    "word_prediction_enabled" to PrefValue.Bool(Defaults.WORD_PREDICTION_ENABLED),
    "suggestion_bar_opacity" to PrefValue.IntV(Defaults.SUGGESTION_BAR_OPACITY),
    "show_exact_typed_word" to PrefValue.Bool(Defaults.SHOW_EXACT_TYPED_WORD),
    "context_aware_predictions_enabled" to PrefValue.Bool(Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED),
    "personalized_learning_enabled" to PrefValue.Bool(Defaults.PERSONALIZED_LEARNING_ENABLED),
    "learning_aggression" to PrefValue.Str(Defaults.LEARNING_AGGRESSION),
    "prediction_context_boost" to PrefValue.FloatV(Defaults.PREDICTION_CONTEXT_BOOST),
    "prediction_frequency_scale" to PrefValue.FloatV(Defaults.PREDICTION_FREQUENCY_SCALE),
    "auto_space_after_suggestion" to PrefValue.Bool(Defaults.AUTO_SPACE_AFTER_SUGGESTION),
    "auto_space_before_suggestion" to PrefValue.Bool(Defaults.AUTO_SPACE_BEFORE_SUGGESTION),
    "backspace_undo_swipe" to PrefValue.Bool(Defaults.BACKSPACE_UNDO_SWIPE),
    "backspace_undo_autocorrect" to PrefValue.Bool(Defaults.BACKSPACE_UNDO_AUTOCORRECT),

    // ── Autocorrect ──────────────────────────────────────────────────
    "autocorrect_enabled" to PrefValue.Bool(Defaults.AUTOCORRECT_ENABLED),
    "autocorrect_min_word_length" to PrefValue.IntV(Defaults.AUTOCORRECT_MIN_WORD_LENGTH),
    "autocorrect_char_match_threshold" to PrefValue.FloatV(Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD),
    "autocorrect_confidence_min_frequency" to PrefValue.IntV(Defaults.AUTOCORRECT_MIN_FREQUENCY),
    "swipe_top5000_boost" to PrefValue.FloatV(Defaults.SWIPE_TOP5000_BOOST),
    "autocorrect_max_length_diff" to PrefValue.IntV(Defaults.AUTOCORRECT_MAX_LENGTH_DIFF),
    "autocorrect_prefix_length" to PrefValue.IntV(Defaults.AUTOCORRECT_PREFIX_LENGTH),
    "autocorrect_max_beam_candidates" to PrefValue.IntV(Defaults.AUTOCORRECT_MAX_BEAM_CANDIDATES),
    "swipe_beam_autocorrect_enabled" to PrefValue.Bool(Defaults.SWIPE_BEAM_AUTOCORRECT_ENABLED),
    "swipe_final_autocorrect_enabled" to PrefValue.Bool(Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED),
    "autocapitalize_i_words" to PrefValue.Bool(Defaults.AUTOCAPITALIZE_I_WORDS),
    "swipe_fuzzy_match_mode" to PrefValue.Str(Defaults.SWIPE_FUZZY_MATCH_MODE),
    "swipe_prediction_source" to PrefValue.IntV(Defaults.SWIPE_PREDICTION_SOURCE),
    "swipe_common_words_boost" to PrefValue.FloatV(Defaults.SWIPE_COMMON_WORDS_BOOST),
    "swipe_rare_words_penalty" to PrefValue.FloatV(Defaults.SWIPE_RARE_WORDS_PENALTY),

    // ── Multi-language ──────────────────────────────────────────────
    "pref_enable_multilang" to PrefValue.Bool(Defaults.ENABLE_MULTILANG),
    "pref_primary_language" to PrefValue.Str(Defaults.PRIMARY_LANGUAGE),
    "pref_auto_detect_language" to PrefValue.Bool(Defaults.AUTO_DETECT_LANGUAGE),
    "pref_language_detection_sensitivity" to PrefValue.FloatV(Defaults.LANGUAGE_DETECTION_SENSITIVITY),
    "pref_secondary_prediction_weight" to PrefValue.FloatV(Defaults.SECONDARY_PREDICTION_WEIGHT),

    // ── CGR calibration (touch-input model parameters, literal defaults) ─
    // Reads in SettingsActivity use literal int defaults; values are user-
    // tunable via the swipe-calibration UI. Importing across devices is
    // valid (user prefers their tuned settings).
    "cgr_beta" to PrefValue.IntV(400),
    "cgr_e_sigma" to PrefValue.IntV(120),
    "cgr_kappa" to PrefValue.IntV(25),
    "cgr_lambda" to PrefValue.IntV(65),
    "cgr_length_filter" to PrefValue.IntV(70),
    "has_visited_calibration" to PrefValue.Bool(false),

    // ── Pin / number-entry layout ────────────────────────────────────
    "pin_entry_enabled" to PrefValue.Bool(true),

    // ── Clipboard ────────────────────────────────────────────────────
    "clipboard_history_enabled" to PrefValue.Bool(Defaults.CLIPBOARD_HISTORY_ENABLED),
    "clipboard_history_limit" to PrefValue.Str(Defaults.CLIPBOARD_HISTORY_LIMIT),
    "clipboard_history_duration" to PrefValue.Str(Defaults.CLIPBOARD_HISTORY_DURATION),
    "clipboard_pane_height_percent" to PrefValue.IntV(Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT),
    "clipboard_max_item_size_kb" to PrefValue.Str(Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB),
    "clipboard_limit_type" to PrefValue.Str(Defaults.CLIPBOARD_LIMIT_TYPE),
    "clipboard_size_limit_mb" to PrefValue.Str(Defaults.CLIPBOARD_SIZE_LIMIT_MB),
    "clipboard_pinned_rows" to PrefValue.Str("100"),
    "clipboard_exclude_password_managers" to PrefValue.Bool(Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS),
    "clipboard_respect_sensitive_flag" to PrefValue.Bool(Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG),
    // URL-sanitization (Chunk 4) — defaults are literal `false` at the read sites in SettingsActivity.
    "clipboard_sanitize_links_enabled" to PrefValue.Bool(false),
    "clipboard_embed_enrich_enabled" to PrefValue.Bool(false),
    "clipboard_custom_rules_enabled" to PrefValue.Bool(false),
    // Clipboard tab visibility — defaults literal `true` per SettingsActivity reads.
    "clipboard_text_only" to PrefValue.Bool(false),
    "clipboard_pinned_enabled" to PrefValue.Bool(true),
    "clipboard_todo_enabled" to PrefValue.Bool(true),
    "clipboard_media_enabled" to PrefValue.Bool(true),
    "clipboard_max_media_size_mb" to PrefValue.IntV(10),

    // ── GIF panel ────────────────────────────────────────────────────
    "gif_enabled" to PrefValue.Bool(Defaults.GIF_ENABLED),
    "gif_thumbnail_columns" to PrefValue.IntV(Defaults.GIF_THUMBNAIL_COLUMNS),

    // ── Privacy / debug / accessibility ──────────────────────────────
    "debug_enabled" to PrefValue.Bool(Defaults.DEBUG_ENABLED),
    "privacy_collect_swipe" to PrefValue.Bool(Defaults.PRIVACY_COLLECT_SWIPE),
    "privacy_collect_performance" to PrefValue.Bool(Defaults.PRIVACY_COLLECT_PERFORMANCE),
    "privacy_collect_errors" to PrefValue.Bool(Defaults.PRIVACY_COLLECT_ERRORS),
    "sticky_keys_enabled" to PrefValue.Bool(Defaults.STICKY_KEYS_ENABLED),
    "sticky_keys_timeout" to PrefValue.IntV(Defaults.STICKY_KEYS_TIMEOUT),
    "sticky_keys_timeout_ms" to PrefValue.IntV(Defaults.STICKY_KEYS_TIMEOUT),  // SettingsActivity duplicate
    "voice_guidance_enabled" to PrefValue.Bool(Defaults.VOICE_GUIDANCE_ENABLED),

    // ── Presets (string-valued UI selections) ────────────────────────
    "neural_preset" to PrefValue.Str("custom"),
    "swipe_correction_preset" to PrefValue.Str("balanced"),

    // ── Misc / runtime ───────────────────────────────────────────────
    "termux_mode_enabled" to PrefValue.Bool(Defaults.TERMUX_MODE_ENABLED),
    "swipe_debug_detailed_logging" to PrefValue.Bool(Defaults.SWIPE_DEBUG_DETAILED_LOGGING),
    "swipe_debug_show_raw_output" to PrefValue.Bool(Defaults.SWIPE_DEBUG_SHOW_RAW_OUTPUT),
    "swipe_show_debug_scores" to PrefValue.Bool(Defaults.SWIPE_SHOW_DEBUG_SCORES),
    "swipe_show_raw_beam_predictions" to PrefValue.Bool(Defaults.SWIPE_SHOW_RAW_BEAM_PREDICTIONS),
)

/**
 * Per-language pref keys whose name is `<base>_<lang>` (e.g.
 * `neural_prefix_boost_multiplier_fr`, `custom_words_en`). One default
 * applies to every language variant. Lookup: if a key matches a prefix
 * here, the corresponding `PrefValue` is used as the effective default.
 *
 * Prefix MUST end with `_` so the language code can't accidentally be a
 * full match (e.g. `neural_prefix_boost_multiplier` itself is a separate
 * entry in SETTINGS_DEFAULTS — only `neural_prefix_boost_multiplier_<x>`
 * matches the per-language pattern).
 */
internal val PATTERN_DEFAULTS: Map<String, PrefValue> = mapOf(
    "neural_prefix_boost_multiplier_" to PrefValue.FloatV(Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER),
    "neural_prefix_boost_max_" to PrefValue.FloatV(Defaults.NEURAL_PREFIX_BOOST_MAX),
)

/**
 * Resolve a key's effective default via either the literal map or the
 * pattern map. Used by `SettingsImportPlanBuilder` to decide whether a
 * key represents a real change vs. a no-op against the default.
 */
internal fun lookupDefault(key: String): PrefValue? {
    SETTINGS_DEFAULTS[key]?.let { return it }
    for ((prefix, value) in PATTERN_DEFAULTS) {
        if (key.startsWith(prefix)) return value
    }
    return null
}

/**
 * Keys whose runtime default is literally `null` (read site passes `null`
 * to `prefs.getString`). Importing such a key with a non-null proposed
 * value is a real change — but we cannot express "the absence of a value"
 * as a `PrefValue` variant other than `Unset`. Listing them here:
 *
 *   1. Documents the intentional fall-through to `Unset` in the preview
 *      (the dialog will render "(unset) → value", which is correct here —
 *      there really is no prior value to compare against).
 *   2. Allows `SettingsDefaultsDriftTest` to recognize these as classified
 *      so the test doesn't flag them as missing-from-`SETTINGS_DEFAULTS`.
 */
internal val NON_DEFAULTED_KEYS: Set<String> = setOf(
    "clipboard_custom_rules_uri",      // SAF URI for user-supplied URL rules
    "pref_primary_language_alt",       // Optional alt-primary multi-lang slot
    "pref_secondary_language",         // Optional secondary multi-lang slot
    "pref_secondary_language_alt",     // Optional alt-secondary multi-lang slot
)
