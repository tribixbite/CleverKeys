package tribixbite.cleverkeys

import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import tribixbite.cleverkeys.prefs.CustomExtraKeysPreference
import tribixbite.cleverkeys.prefs.ExtraKeysPreference
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Single source of truth for all app default values.
 * Both Config.kt and SettingsActivity.kt should reference these constants.
 */
object Defaults {
    // Appearance
    const val THEME = "cleverkeysdark"
    const val KEYBOARD_HEIGHT_PORTRAIT = 28
    const val KEYBOARD_HEIGHT_LANDSCAPE = 50
    const val LABEL_BRIGHTNESS = 100
    const val KEYBOARD_OPACITY = 81
    const val KEY_OPACITY = 100
    const val KEY_ACTIVATED_OPACITY = 100
    const val CHARACTER_SIZE = 1.18f
    const val KEY_VERTICAL_MARGIN = 1.5f
    const val KEY_HORIZONTAL_MARGIN = 2.0f
    const val BORDER_CONFIG = false
    const val CUSTOM_BORDER_RADIUS = 0
    const val CUSTOM_BORDER_LINE_WIDTH = 0

    // Layout
    const val SHOW_NUMPAD = "never"
    const val NUMPAD_LAYOUT = "default"
    const val NUMBER_ROW = "no_number_row"
    const val NUMBER_ENTRY_LAYOUT = "pin"

    // Margin settings (percentages of screen dimension)
    // Bottom margin as % of screen height
    const val MARGIN_BOTTOM_PORTRAIT = 2      // ~2% of screen height
    const val MARGIN_BOTTOM_LANDSCAPE = 2     // ~2% of screen height
    // Left/Right margins as % of screen width
    const val MARGIN_LEFT_PORTRAIT = 1        // ~1% of screen width
    const val MARGIN_LEFT_LANDSCAPE = 5       // ~5% of screen width
    const val MARGIN_RIGHT_PORTRAIT = 1       // ~1% of screen width
    const val MARGIN_RIGHT_LANDSCAPE = 5      // ~5% of screen width

    // Legacy compatibility: old dp-based settings used these names
    // HORIZONTAL_MARGIN_PORTRAIT = 3dp, HORIZONTAL_MARGIN_LANDSCAPE = 28dp
    @Deprecated("Use MARGIN_LEFT_* and MARGIN_RIGHT_* instead")
    const val HORIZONTAL_MARGIN_PORTRAIT = 3
    @Deprecated("Use MARGIN_LEFT_* and MARGIN_RIGHT_* instead")
    const val HORIZONTAL_MARGIN_LANDSCAPE = 28

    // Input behavior
    const val VIBRATE_CUSTOM = false
    const val VIBRATE_DURATION = 20
    const val LONGPRESS_TIMEOUT = 600
    const val LONGPRESS_INTERVAL = 25
    const val KEYREPEAT_ENABLED = true
    const val DOUBLE_TAP_LOCK_SHIFT = true
    const val AUTOCAPITALISATION = true
    const val SWITCH_INPUT_IMMEDIATE = false
    const val SMART_PUNCTUATION = true

    // Gesture settings
    const val SWIPE_DIST = "23"
    const val SWIPE_DIST_FALLBACK = 23f
    const val SLIDER_SENSITIVITY = "30"
    const val CIRCLE_SENSITIVITY = "2"
    const val CIRCLE_SENSITIVITY_FALLBACK = 2
    const val TAP_DURATION_THRESHOLD = 150
    const val DOUBLE_SPACE_THRESHOLD = 500
    const val SWIPE_MIN_DISTANCE = 72f
    const val SWIPE_MIN_KEY_DISTANCE = 38f
    const val SWIPE_MIN_DWELL_TIME = 7
    const val SWIPE_NOISE_THRESHOLD = 1.26f
    const val SWIPE_HIGH_VELOCITY_THRESHOLD = 1000f
    const val SLIDER_SPEED_SMOOTHING = 0.6f  // Slightly more responsive smoothing
    const val SLIDER_SPEED_MAX = 6.0f  // Higher max for faster long-distance cursor movement

    // Short gestures
    const val SHORT_GESTURES_ENABLED = true
    const val SHORT_GESTURE_MIN_DISTANCE = 37
    const val SHORT_GESTURE_MAX_DISTANCE = 141

    // Swipe trail
    const val SWIPE_TRAIL_ENABLED = true
    const val SWIPE_TRAIL_EFFECT = "sparkle"
    const val SWIPE_TRAIL_COLOR = 0xFFC0C0C0.toInt() // Silver
    const val SWIPE_TRAIL_WIDTH = 8.0f
    const val SWIPE_TRAIL_GLOW_RADIUS = 6.0f

    // Neural prediction - Core parameters
    const val NEURAL_BEAM_WIDTH = 6
    const val NEURAL_MAX_LENGTH = 20            // Match model's max_word_length
    const val NEURAL_CONFIDENCE_THRESHOLD = 0.01f
    const val NEURAL_BATCH_BEAMS = false
    const val NEURAL_GREEDY_SEARCH = false

    // Neural prediction - Beam search tuning
    // NOTE: These MUST match the working defaults in BeamSearchEngine.kt
    const val NEURAL_BEAM_ALPHA = 1.0f          // Length penalty factor (1.0 = linear normalization)
    const val NEURAL_BEAM_PRUNE_CONFIDENCE = 0.8f  // Adaptive width pruning threshold
    const val NEURAL_BEAM_SCORE_GAP = 80.0f     // Early stopping score gap (high = search longer for long words)
    const val NEURAL_ADAPTIVE_WIDTH_STEP = 12   // Step when to start adaptive width pruning
    const val NEURAL_SCORE_GAP_STEP = 12        // Step when to start score gap early stopping
    const val NEURAL_TEMPERATURE = 1.0f         // Softmax temperature (lower = more confident)
    const val NEURAL_FREQUENCY_WEIGHT = 0.57f   // Vocab frequency weight in scoring (0=NN only, 2=heavy freq)
    const val SWIPE_SMOOTHING_WINDOW = 3        // Points for moving average smoothing (1 = disabled, 3 = optimal)

    // Language-specific prefix boost (for non-English primary languages)
    const val NEURAL_PREFIX_BOOST_MULTIPLIER = 1.0f  // Scaling factor for prefix boosts (0=disabled, 1=default)
    const val NEURAL_PREFIX_BOOST_MAX = 5.0f         // Maximum boost value per character (clamping)
    const val NEURAL_MAX_CUMULATIVE_BOOST = 15.0f    // Maximum total boost across all chars (prevents runaway)
    const val NEURAL_STRICT_START_CHAR = false       // If true, only keep beams matching first detected key

    const val NEURAL_RESAMPLING_MODE = "discard"
    const val NEURAL_USER_MAX_SEQ_LENGTH = 0

    // Word prediction
    const val SWIPE_TYPING_ENABLED = true
    const val WORD_PREDICTION_ENABLED = true
    const val SUGGESTION_BAR_OPACITY = 80
    const val CONTEXT_AWARE_PREDICTIONS_ENABLED = true
    const val PERSONALIZED_LEARNING_ENABLED = true
    const val LEARNING_AGGRESSION = "BALANCED"
    const val PREDICTION_CONTEXT_BOOST = 0.5f
    const val PREDICTION_FREQUENCY_SCALE = 100.0f

    // Autocorrect
    const val AUTOCORRECT_ENABLED = true
    const val AUTOCORRECT_MIN_WORD_LENGTH = 3
    const val AUTOCORRECT_CHAR_MATCH_THRESHOLD = 0.67f
    const val AUTOCORRECT_MIN_FREQUENCY = 100
    const val AUTOCORRECT_MAX_LENGTH_DIFF = 2
    const val AUTOCORRECT_PREFIX_LENGTH = 1
    const val AUTOCORRECT_MAX_BEAM_CANDIDATES = 3
    const val SWIPE_BEAM_AUTOCORRECT_ENABLED = true
    const val SWIPE_FINAL_AUTOCORRECT_ENABLED = true
    const val SWIPE_FUZZY_MATCH_MODE = "edit_distance"
    const val SWIPE_PREDICTION_SOURCE = 80
    const val SWIPE_COMMON_WORDS_BOOST = 1.0f
    const val SWIPE_TOP5000_BOOST = 1.0f
    const val SWIPE_RARE_WORDS_PENALTY = 1.0f

    // Clipboard
    const val CLIPBOARD_HISTORY_ENABLED = true
    const val CLIPBOARD_HISTORY_LIMIT = "0"
    const val CLIPBOARD_HISTORY_LIMIT_FALLBACK = 0
    const val CLIPBOARD_PANE_HEIGHT_PERCENT = 30
    const val CLIPBOARD_MAX_ITEM_SIZE_KB = "500"
    const val CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK = 500
    const val CLIPBOARD_LIMIT_TYPE = "count"
    const val CLIPBOARD_SIZE_LIMIT_MB = "10"
    const val CLIPBOARD_SIZE_LIMIT_MB_FALLBACK = 10

    // Multi-language
    const val ENABLE_MULTILANG = false
    const val PRIMARY_LANGUAGE = "en"
    const val AUTO_DETECT_LANGUAGE = true
    const val LANGUAGE_DETECTION_SENSITIVITY = 0.6f
    const val SECONDARY_PREDICTION_WEIGHT = 0.9f  // v1.1.94: Multiplier for secondary dictionary predictions

    // Debug
    const val DEBUG_ENABLED = false
    const val SWIPE_SHOW_DEBUG_SCORES = false
    const val SWIPE_DEBUG_DETAILED_LOGGING = false
    const val SWIPE_DEBUG_SHOW_RAW_OUTPUT = true
    const val SWIPE_SHOW_RAW_BEAM_PREDICTIONS = true
    const val TERMUX_MODE_ENABLED = true

    // Privacy
    const val PRIVACY_COLLECT_SWIPE = false
    const val PRIVACY_COLLECT_PERFORMANCE = false
    const val PRIVACY_COLLECT_ERRORS = false

    // Accessibility
    const val STICKY_KEYS_ENABLED = false
    const val STICKY_KEYS_TIMEOUT = 5000
    const val VOICE_GUIDANCE_ENABLED = false
}

/**
 * Neural prediction presets for different use cases.
 *
 * These presets trade off between speed and accuracy:
 * - SPEED: Fast predictions, lower beam width, earlier stopping
 * - BALANCED: Default configuration, good for most users
 * - ACCURACY: Thorough search, higher beam width, better for long/rare words
 */
enum class NeuralPreset(
    val displayName: String,
    val beamWidth: Int,
    val maxLength: Int,
    val confidenceThreshold: Float,
    val beamAlpha: Float,
    val beamPruneConfidence: Float,
    val beamScoreGap: Float,
    val adaptiveWidthStep: Int,
    val scoreGapStep: Int,
    val temperature: Float,
    val frequencyWeight: Float
) {
    SPEED(
        displayName = "Speed",
        beamWidth = 4,
        maxLength = 15,
        confidenceThreshold = 0.02f,
        beamAlpha = 0.8f,           // Slight length normalization
        beamPruneConfidence = 0.7f, // Earlier pruning
        beamScoreGap = 40.0f,       // Earlier stopping but still allows medium words
        adaptiveWidthStep = 8,      // Start pruning early
        scoreGapStep = 6,           // Start gap check early
        temperature = 1.0f,
        frequencyWeight = 1.2f      // Favor common words
    ),
    BALANCED(
        displayName = "Balanced",
        beamWidth = Defaults.NEURAL_BEAM_WIDTH,
        maxLength = Defaults.NEURAL_MAX_LENGTH,
        confidenceThreshold = Defaults.NEURAL_CONFIDENCE_THRESHOLD,
        beamAlpha = Defaults.NEURAL_BEAM_ALPHA,
        beamPruneConfidence = Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE,
        beamScoreGap = Defaults.NEURAL_BEAM_SCORE_GAP,
        adaptiveWidthStep = Defaults.NEURAL_ADAPTIVE_WIDTH_STEP,
        scoreGapStep = Defaults.NEURAL_SCORE_GAP_STEP,
        temperature = Defaults.NEURAL_TEMPERATURE,
        frequencyWeight = Defaults.NEURAL_FREQUENCY_WEIGHT
    ),
    ACCURACY(
        displayName = "Accuracy",
        beamWidth = 10,
        maxLength = 20,
        confidenceThreshold = 0.005f,  // Keep more candidates
        beamAlpha = 1.2f,              // Favor longer words slightly
        beamPruneConfidence = 0.9f,    // Later pruning
        beamScoreGap = 100.0f,         // Search thoroughly for long words
        adaptiveWidthStep = 15,        // Delay pruning
        scoreGapStep = 14,             // Delay gap check
        temperature = 0.9f,            // Slightly more confident
        frequencyWeight = 0.8f         // Less frequency bias, trust NN more
    );

    companion object {
        fun fromName(name: String): NeuralPreset? {
            return values().find { it.name.equals(name, ignoreCase = true) }
        }
    }
}

class Config private constructor(
    private val _prefs: SharedPreferences,
    res: Resources,
    @JvmField val handler: IKeyEventHandler?,
    foldableUnfolded: Boolean?
) {
    // From resources
    @JvmField val marginTop: Float = res.getDimension(R.dimen.margin_top)
    @JvmField val keyPadding: Float = res.getDimension(R.dimen.key_padding)
    @JvmField val labelTextSize: Float = 0.33f
    @JvmField val sublabelTextSize: Float = 0.22f

    // From preferences
    // Nullable list preserves indices - null entries represent SystemLayout (use localeTextLayout)
    @JvmField var layouts: List<KeyboardData?> = emptyList()
    @JvmField var show_numpad = false
    @JvmField var inverse_numpad = false
    @JvmField var add_number_row = false
    @JvmField var number_row_symbols = false
    @JvmField var swipe_dist_px = 0f
    @JvmField var slide_step_px = 0f
    @JvmField var vibrate_custom = false
    @JvmField var vibrate_duration = 0L
    @JvmField var longPressTimeout = 0L
    @JvmField var longPressInterval = 0L
    @JvmField var keyrepeat_enabled = false
    @JvmField var margin_bottom = 0f  // In pixels (calculated from % of screen height)
    @JvmField var margin_left = 0f    // In pixels (calculated from % of screen width)
    @JvmField var margin_right = 0f   // In pixels (calculated from % of screen width)
    @JvmField var keyboardHeightPercent = 0
    @JvmField var screenHeightPixels = 0
    @JvmField var screenWidthPixels = 0
    @Deprecated("Use margin_left and margin_right instead")
    @JvmField var horizontal_margin = 0f
    @JvmField var key_vertical_margin = 0f
    @JvmField var key_horizontal_margin = 0f
    @JvmField var labelBrightness = 0
    @JvmField var keyboardOpacity = 0
    @JvmField var customBorderRadius = 0f
    @JvmField var customBorderLineWidth = 0f
    @JvmField var keyOpacity = 0
    @JvmField var keyActivatedOpacity = 0
    @JvmField var double_tap_lock_shift = false
    @JvmField var characterSize = 0f
    @JvmField var theme = 0
    @JvmField var themeName: String = "" // Raw theme name for runtime theme detection
    @JvmField var autocapitalisation = false
    @JvmField var switch_input_immediate = false
    @JvmField var selected_number_layout: NumberLayout? = null
    @JvmField var borderConfig = false
    @JvmField var circle_sensitivity = 0
    @JvmField var clipboard_history_enabled = false
    @JvmField var clipboard_history_limit = 0
    @JvmField var clipboard_pane_height_percent = 0
    @JvmField var clipboard_max_item_size_kb = 0
    @JvmField var clipboard_limit_type: String? = null
    @JvmField var clipboard_size_limit_mb = 0
    @JvmField var swipe_typing_enabled = true  // Default to enabled for CleverKeys
    @JvmField var swipe_show_debug_scores = false
    @JvmField var word_prediction_enabled = false
    @JvmField var suggestion_bar_opacity = 0

    // Word prediction scoring weights
    @JvmField var prediction_context_boost = 0f
    @JvmField var prediction_frequency_scale = 0f
    @JvmField var context_aware_predictions_enabled = false // Phase 7.1: Dynamic N-gram learning
    @JvmField var personalized_learning_enabled = false // Phase 7.2: Personalized word frequency learning
    @JvmField var learning_aggression = "BALANCED" // Phase 7.2: Learning aggression level

    // Multi-language support (Phase 8.3 & 8.4)
    @JvmField var enable_multilang = false // Phase 8.3: Enable multi-language support
    @JvmField var primary_language = "en" // Phase 8.3: Primary language (default)
    @JvmField var auto_detect_language = true // Phase 8.3: Auto-detect language from context
    @JvmField var language_detection_sensitivity = 0.6f // Phase 8.3: Detection sensitivity (0.0-1.0)
    @JvmField var secondary_prediction_weight = Defaults.SECONDARY_PREDICTION_WEIGHT // v1.1.94: Weight for secondary predictions

    // Auto-correction settings
    @JvmField var autocorrect_enabled = false
    @JvmField var autocorrect_min_word_length = 0
    @JvmField var autocorrect_char_match_threshold = 0f
    @JvmField var autocorrect_confidence_min_frequency = 0

    // Fuzzy matching configuration
    @JvmField var autocorrect_max_length_diff = 0
    @JvmField var autocorrect_prefix_length = 0
    @JvmField var autocorrect_max_beam_candidates = 0

    // Swipe scoring weights
    @JvmField var swipe_confidence_weight = 0f
    @JvmField var swipe_frequency_weight = 0f
    @JvmField var swipe_common_words_boost = 0f
    @JvmField var swipe_top5000_boost = 0f
    @JvmField var swipe_rare_words_penalty = 0f

    // Swipe autocorrect configuration
    @JvmField var swipe_beam_autocorrect_enabled = false
    @JvmField var swipe_final_autocorrect_enabled = false
    @JvmField var swipe_fuzzy_match_mode: String? = null

    // Short gesture configuration
    @JvmField var short_gestures_enabled = false
    @JvmField var short_gesture_min_distance = 0
    @JvmField var short_gesture_max_distance = 100 // Max distance as % of key diagonal (50-200, 200=disabled)

    // Gesture timing configuration (exposed hardcoded constants)
    @JvmField var tap_duration_threshold = 150L // Max duration for a tap gesture (ms)
    @JvmField var double_space_threshold = 500L // Max time between spaces for period replacement (ms)
    @JvmField var smart_punctuation = true // Attach punctuation to end of last word (no space before)
    @JvmField var swipe_min_dwell_time = 10L // Min time to register a key during swipe (ms)
    @JvmField var swipe_noise_threshold = 2.0f // Min distance to register movement (pixels)
    @JvmField var swipe_high_velocity_threshold = 1000.0f // Velocity threshold for fast swipes (px/sec)
    @JvmField var swipe_min_distance = 50.0f // Minimum total distance to recognize a swipe (pixels)
    @JvmField var swipe_min_key_distance = 40.0f // Minimum distance between keys during swipe (pixels)

    // Slider speed configuration
    @JvmField var slider_speed_smoothing = 0.7f // Smoothing factor for slider speed (0.0-1.0)
    @JvmField var slider_speed_max = 4.0f // Maximum slider speed multiplier

    // Swipe trail appearance
    @JvmField var swipe_trail_enabled = true // Show swipe trail during gesture
    @JvmField var swipe_trail_effect = "glow" // Trail effect: none, solid, glow, rainbow, fade
    @JvmField var swipe_trail_color = 0xFF9B59B6.toInt() // Trail color (default: jewel purple)
    @JvmField var swipe_trail_width = 8.0f // Trail stroke width in dp
    @JvmField var swipe_trail_glow_radius = 6.0f // Glow effect radius in dp (smaller = crisper)

    // Neural swipe prediction configuration
    @JvmField var neural_beam_width = 0
    @JvmField var neural_max_length = 0
    @JvmField var neural_confidence_threshold = 0f
    @JvmField var neural_batch_beams = false
    @JvmField var neural_greedy_search = false
    @JvmField var swipe_debug_detailed_logging = false
    @JvmField var swipe_debug_show_raw_output = false
    @JvmField var swipe_show_raw_beam_predictions = false
    @JvmField var termux_mode_enabled = false

    // Beam search tuning
    @JvmField var neural_beam_alpha = 0f
    @JvmField var neural_beam_prune_confidence = 0f
    @JvmField var neural_beam_score_gap = 0f
    @JvmField var neural_adaptive_width_step = 0
    @JvmField var neural_score_gap_step = 0
    @JvmField var neural_temperature = 0f
    @JvmField var neural_frequency_weight = 0f
    @JvmField var swipe_smoothing_window = 0

    // Neural model resampling
    @JvmField var neural_user_max_seq_length = 0
    @JvmField var neural_resampling_mode: String? = null
    // NOTE: Custom encoder/decoder paths removed - feature not implemented

    // Language-specific prefix boost (for non-English primary languages)
    @JvmField var neural_prefix_boost_multiplier = Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER
    @JvmField var neural_prefix_boost_max = Defaults.NEURAL_PREFIX_BOOST_MAX
    @JvmField var neural_max_cumulative_boost = Defaults.NEURAL_MAX_CUMULATIVE_BOOST
    @JvmField var neural_strict_start_char = Defaults.NEURAL_STRICT_START_CHAR

    // Dynamically set
    @JvmField var shouldOfferVoiceTyping = false
    @JvmField var actionLabel: String? = null
    @JvmField var actionId = 0
    @JvmField var swapEnterActionKey = false
    @JvmField var extra_keys_subtype: ExtraKeys? = null
    @JvmField var extra_keys_param: Map<KeyValue, KeyboardData.PreferredPos> = emptyMap()
    @JvmField var extra_keys_custom: Map<KeyValue, KeyboardData.PreferredPos> = emptyMap()

    @JvmField var orientation_landscape = false
    @JvmField var foldable_unfolded = false
    @JvmField var wide_screen = false
    @JvmField var version = 0

    private var current_layout_narrow = 0
    private var current_layout_wide = 0

    init {
        repairCorruptedFloatPreferences(_prefs)
        refresh(res, foldableUnfolded)
    }

    fun refresh(res: Resources, foldableUnfolded: Boolean?) {
        version++
        val dm = res.displayMetrics
        orientation_landscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        this.foldable_unfolded = foldableUnfolded ?: false

        var characterSizeScale = 1f
        val show_numpad_s = safeGetString(_prefs, "show_numpad", Defaults.SHOW_NUMPAD)
        show_numpad = "always" == show_numpad_s

        if (orientation_landscape) {
            if ("landscape" == show_numpad_s) show_numpad = true
            keyboardHeightPercent = safeGetInt(
                _prefs,
                if (this.foldable_unfolded) "keyboard_height_landscape_unfolded" else "keyboard_height_landscape",
                Defaults.KEYBOARD_HEIGHT_LANDSCAPE
            )
            characterSizeScale = 1.25f
        } else {
            keyboardHeightPercent = safeGetInt(
                _prefs,
                if (this.foldable_unfolded) "keyboard_height_unfolded" else "keyboard_height",
                Defaults.KEYBOARD_HEIGHT_PORTRAIT
            )
        }

        // Keep nulls - they represent SystemLayout entries (resolved to localeTextLayout at runtime)
        layouts = LayoutsPreference.load_from_preferences(res, _prefs)
        inverse_numpad = safeGetString(_prefs, "numpad_layout", Defaults.NUMPAD_LAYOUT) == "low_first"

        val number_row = safeGetString(_prefs, "number_row", Defaults.NUMBER_ROW)
        add_number_row = number_row != "no_number_row"
        number_row_symbols = number_row == "symbols"

        val dpi_ratio = maxOf(dm.xdpi, dm.ydpi) / minOf(dm.xdpi, dm.ydpi)
        val swipe_scaling = minOf(dm.widthPixels, dm.heightPixels) / 10f * dpi_ratio
        val swipe_dist_value = safeGetString(_prefs, "swipe_dist", Defaults.SWIPE_DIST).toFloatOrNull() ?: Defaults.SWIPE_DIST_FALLBACK
        swipe_dist_px = swipe_dist_value / 25f * swipe_scaling

        val slider_sensitivity = (safeGetString(_prefs, "slider_sensitivity", Defaults.SLIDER_SENSITIVITY).toFloatOrNull() ?: 30f) / 100f
        slide_step_px = slider_sensitivity * swipe_scaling

        vibrate_custom = _prefs.getBoolean("vibrate_custom", Defaults.VIBRATE_CUSTOM)
        vibrate_duration = safeGetInt(_prefs, "vibrate_duration", Defaults.VIBRATE_DURATION).toLong()
        longPressTimeout = safeGetInt(_prefs, "longpress_timeout", Defaults.LONGPRESS_TIMEOUT).toLong()
        longPressInterval = safeGetInt(_prefs, "longpress_interval", Defaults.LONGPRESS_INTERVAL).toLong()
        keyrepeat_enabled = _prefs.getBoolean("keyrepeat_enabled", Defaults.KEYREPEAT_ENABLED)

        // Screen dimensions for percentage calculations
        screenHeightPixels = dm.heightPixels
        screenWidthPixels = dm.widthPixels

        // Margin settings (percentage of screen dimensions)
        margin_bottom = get_percent_pref_oriented_height(
            "margin_bottom",
            Defaults.MARGIN_BOTTOM_PORTRAIT,
            Defaults.MARGIN_BOTTOM_LANDSCAPE
        )
        margin_left = get_percent_pref_oriented_width(
            "margin_left",
            Defaults.MARGIN_LEFT_PORTRAIT,
            Defaults.MARGIN_LEFT_LANDSCAPE
        )
        margin_right = get_percent_pref_oriented_width(
            "margin_right",
            Defaults.MARGIN_RIGHT_PORTRAIT,
            Defaults.MARGIN_RIGHT_LANDSCAPE
        )
        // Legacy fallback for horizontal_margin (used by Theme.kt)
        @Suppress("DEPRECATION")
        horizontal_margin = (margin_left + margin_right) / 2

        key_vertical_margin = get_dip_pref(dm, "key_vertical_margin", Defaults.KEY_VERTICAL_MARGIN) / 100
        key_horizontal_margin = get_dip_pref(dm, "key_horizontal_margin", Defaults.KEY_HORIZONTAL_MARGIN) / 100

        labelBrightness = safeGetInt(_prefs, "label_brightness", Defaults.LABEL_BRIGHTNESS) * 255 / 100
        keyboardOpacity = safeGetInt(_prefs, "keyboard_opacity", Defaults.KEYBOARD_OPACITY) * 255 / 100
        keyOpacity = safeGetInt(_prefs, "key_opacity", Defaults.KEY_OPACITY) * 255 / 100
        keyActivatedOpacity = safeGetInt(_prefs, "key_activated_opacity", Defaults.KEY_ACTIVATED_OPACITY) * 255 / 100

        borderConfig = _prefs.getBoolean("border_config", Defaults.BORDER_CONFIG)
        customBorderRadius = _prefs.getInt("custom_border_radius", Defaults.CUSTOM_BORDER_RADIUS) / 100f
        customBorderLineWidth = get_dip_pref(dm, "custom_border_line_width", Defaults.CUSTOM_BORDER_LINE_WIDTH.toFloat())
        double_tap_lock_shift = _prefs.getBoolean("lock_double_tap", Defaults.DOUBLE_TAP_LOCK_SHIFT)
        characterSize = safeGetFloat(_prefs, "character_size", Defaults.CHARACTER_SIZE) * characterSizeScale
        themeName = safeGetString(_prefs, "theme", Defaults.THEME)
        theme = getThemeId(res, themeName)
        autocapitalisation = _prefs.getBoolean("autocapitalisation", Defaults.AUTOCAPITALISATION)
        switch_input_immediate = _prefs.getBoolean("switch_input_immediate", Defaults.SWITCH_INPUT_IMMEDIATE)
        extra_keys_param = ExtraKeysPreference.get_extra_keys(_prefs) ?: emptyMap()
        extra_keys_custom = CustomExtraKeysPreference.get(_prefs) ?: emptyMap()
        selected_number_layout = NumberLayout.of_string(safeGetString(_prefs, "number_entry_layout", Defaults.NUMBER_ENTRY_LAYOUT))
        current_layout_narrow = safeGetInt(_prefs, "current_layout_portrait", 0)
        current_layout_wide = safeGetInt(_prefs, "current_layout_landscape", 0)
        circle_sensitivity = safeGetString(_prefs, "circle_sensitivity", Defaults.CIRCLE_SENSITIVITY).toIntOrNull() ?: Defaults.CIRCLE_SENSITIVITY_FALLBACK
        clipboard_history_enabled = _prefs.getBoolean("clipboard_history_enabled", Defaults.CLIPBOARD_HISTORY_ENABLED)

        clipboard_history_limit = safeGetString(_prefs, "clipboard_history_limit", Defaults.CLIPBOARD_HISTORY_LIMIT).toIntOrNull() ?: Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK

        clipboard_pane_height_percent = safeGetInt(_prefs, "clipboard_pane_height_percent", Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT).coerceIn(10, 50)

        clipboard_max_item_size_kb = safeGetString(_prefs, "clipboard_max_item_size_kb", Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB).toIntOrNull() ?: Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB_FALLBACK

        clipboard_limit_type = safeGetString(_prefs, "clipboard_limit_type", Defaults.CLIPBOARD_LIMIT_TYPE)

        clipboard_size_limit_mb = safeGetString(_prefs, "clipboard_size_limit_mb", Defaults.CLIPBOARD_SIZE_LIMIT_MB).toIntOrNull() ?: Defaults.CLIPBOARD_SIZE_LIMIT_MB_FALLBACK

        swipe_typing_enabled = _prefs.getBoolean("swipe_typing_enabled", Defaults.SWIPE_TYPING_ENABLED)
        swipe_show_debug_scores = _prefs.getBoolean("swipe_show_debug_scores", Defaults.SWIPE_SHOW_DEBUG_SCORES)
        word_prediction_enabled = _prefs.getBoolean("word_prediction_enabled", Defaults.WORD_PREDICTION_ENABLED)
        suggestion_bar_opacity = safeGetInt(_prefs, "suggestion_bar_opacity", Defaults.SUGGESTION_BAR_OPACITY)

        prediction_context_boost = safeGetFloat(_prefs, "prediction_context_boost", Defaults.PREDICTION_CONTEXT_BOOST)
        prediction_frequency_scale = safeGetFloat(_prefs, "prediction_frequency_scale", Defaults.PREDICTION_FREQUENCY_SCALE)
        context_aware_predictions_enabled = _prefs.getBoolean("context_aware_predictions_enabled", Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED)
        personalized_learning_enabled = _prefs.getBoolean("personalized_learning_enabled", Defaults.PERSONALIZED_LEARNING_ENABLED)
        learning_aggression = safeGetString(_prefs, "learning_aggression", Defaults.LEARNING_AGGRESSION)

        // Multi-language settings (Phase 8.3 & 8.4)
        enable_multilang = _prefs.getBoolean("pref_enable_multilang", Defaults.ENABLE_MULTILANG)
        primary_language = safeGetString(_prefs, "pref_primary_language", Defaults.PRIMARY_LANGUAGE)
        auto_detect_language = _prefs.getBoolean("pref_auto_detect_language", Defaults.AUTO_DETECT_LANGUAGE)
        // SlideBarPreference stores as Float (0.4-0.9), not Int
        language_detection_sensitivity = safeGetFloat(_prefs, "pref_language_detection_sensitivity", Defaults.LANGUAGE_DETECTION_SENSITIVITY)
        // v1.1.94: Secondary prediction weight (0.5-1.5)
        secondary_prediction_weight = safeGetFloat(_prefs, "pref_secondary_prediction_weight", Defaults.SECONDARY_PREDICTION_WEIGHT)

        autocorrect_enabled = _prefs.getBoolean("autocorrect_enabled", Defaults.AUTOCORRECT_ENABLED)
        autocorrect_min_word_length = safeGetInt(_prefs, "autocorrect_min_word_length", Defaults.AUTOCORRECT_MIN_WORD_LENGTH)
        autocorrect_char_match_threshold = safeGetFloat(_prefs, "autocorrect_char_match_threshold", Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD)
        autocorrect_confidence_min_frequency = safeGetInt(_prefs, "autocorrect_confidence_min_frequency", Defaults.AUTOCORRECT_MIN_FREQUENCY)

        autocorrect_max_length_diff = safeGetInt(_prefs, "autocorrect_max_length_diff", Defaults.AUTOCORRECT_MAX_LENGTH_DIFF)
        autocorrect_prefix_length = safeGetInt(_prefs, "autocorrect_prefix_length", Defaults.AUTOCORRECT_PREFIX_LENGTH)
        autocorrect_max_beam_candidates = safeGetInt(_prefs, "autocorrect_max_beam_candidates", Defaults.AUTOCORRECT_MAX_BEAM_CANDIDATES)

        swipe_beam_autocorrect_enabled = _prefs.getBoolean("swipe_beam_autocorrect_enabled", Defaults.SWIPE_BEAM_AUTOCORRECT_ENABLED)
        swipe_final_autocorrect_enabled = _prefs.getBoolean("swipe_final_autocorrect_enabled", Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED)
        swipe_fuzzy_match_mode = safeGetString(_prefs, "swipe_fuzzy_match_mode", Defaults.SWIPE_FUZZY_MATCH_MODE)

        val predictionSource = safeGetInt(_prefs, "swipe_prediction_source", Defaults.SWIPE_PREDICTION_SOURCE)
        swipe_confidence_weight = predictionSource / 100.0f
        swipe_frequency_weight = 1.0f - swipe_confidence_weight

        swipe_common_words_boost = safeGetFloat(_prefs, "swipe_common_words_boost", Defaults.SWIPE_COMMON_WORDS_BOOST)
        swipe_top5000_boost = safeGetFloat(_prefs, "swipe_top5000_boost", Defaults.SWIPE_TOP5000_BOOST)
        swipe_rare_words_penalty = safeGetFloat(_prefs, "swipe_rare_words_penalty", Defaults.SWIPE_RARE_WORDS_PENALTY)

        short_gestures_enabled = _prefs.getBoolean("short_gestures_enabled", Defaults.SHORT_GESTURES_ENABLED)
        short_gesture_min_distance = safeGetInt(_prefs, "short_gesture_min_distance", Defaults.SHORT_GESTURE_MIN_DISTANCE)
        short_gesture_max_distance = safeGetInt(_prefs, "short_gesture_max_distance", Defaults.SHORT_GESTURE_MAX_DISTANCE)

        // Gesture timing configuration
        tap_duration_threshold = safeGetInt(_prefs, "tap_duration_threshold", Defaults.TAP_DURATION_THRESHOLD).toLong()
        double_space_threshold = safeGetInt(_prefs, "double_space_threshold", Defaults.DOUBLE_SPACE_THRESHOLD).toLong()
        smart_punctuation = _prefs.getBoolean("smart_punctuation", Defaults.SMART_PUNCTUATION)
        swipe_min_dwell_time = safeGetInt(_prefs, "swipe_min_dwell_time", Defaults.SWIPE_MIN_DWELL_TIME).toLong()
        swipe_noise_threshold = safeGetFloat(_prefs, "swipe_noise_threshold", Defaults.SWIPE_NOISE_THRESHOLD)
        swipe_high_velocity_threshold = safeGetFloat(_prefs, "swipe_high_velocity_threshold", Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD)
        swipe_min_distance = safeGetFloat(_prefs, "swipe_min_distance", Defaults.SWIPE_MIN_DISTANCE)
        swipe_min_key_distance = safeGetFloat(_prefs, "swipe_min_key_distance", Defaults.SWIPE_MIN_KEY_DISTANCE)

        // Slider speed configuration
        slider_speed_smoothing = safeGetFloat(_prefs, "slider_speed_smoothing", Defaults.SLIDER_SPEED_SMOOTHING)
        slider_speed_max = safeGetFloat(_prefs, "slider_speed_max", Defaults.SLIDER_SPEED_MAX)

        // Swipe trail appearance
        swipe_trail_enabled = _prefs.getBoolean("swipe_trail_enabled", Defaults.SWIPE_TRAIL_ENABLED)
        swipe_trail_effect = safeGetString(_prefs, "swipe_trail_effect", Defaults.SWIPE_TRAIL_EFFECT)
        swipe_trail_color = _prefs.getInt("swipe_trail_color", Defaults.SWIPE_TRAIL_COLOR)
        swipe_trail_width = safeGetFloat(_prefs, "swipe_trail_width", Defaults.SWIPE_TRAIL_WIDTH)
        swipe_trail_glow_radius = safeGetFloat(_prefs, "swipe_trail_glow_radius", Defaults.SWIPE_TRAIL_GLOW_RADIUS)

        neural_beam_width = safeGetInt(_prefs, "neural_beam_width", Defaults.NEURAL_BEAM_WIDTH)
        neural_max_length = safeGetInt(_prefs, "neural_max_length", Defaults.NEURAL_MAX_LENGTH)
        neural_confidence_threshold = safeGetFloat(_prefs, "neural_confidence_threshold", Defaults.NEURAL_CONFIDENCE_THRESHOLD)
        neural_batch_beams = _prefs.getBoolean("neural_batch_beams", Defaults.NEURAL_BATCH_BEAMS)
        neural_greedy_search = _prefs.getBoolean("neural_greedy_search", Defaults.NEURAL_GREEDY_SEARCH)
        termux_mode_enabled = _prefs.getBoolean("termux_mode_enabled", Defaults.TERMUX_MODE_ENABLED)
        swipe_debug_detailed_logging = _prefs.getBoolean("swipe_debug_detailed_logging", Defaults.SWIPE_DEBUG_DETAILED_LOGGING)
        swipe_debug_show_raw_output = _prefs.getBoolean("swipe_debug_show_raw_output", Defaults.SWIPE_DEBUG_SHOW_RAW_OUTPUT)
        swipe_show_raw_beam_predictions = _prefs.getBoolean("swipe_show_raw_beam_predictions", Defaults.SWIPE_SHOW_RAW_BEAM_PREDICTIONS)

        neural_beam_alpha = safeGetFloat(_prefs, "neural_beam_alpha", Defaults.NEURAL_BEAM_ALPHA)
        neural_beam_prune_confidence = safeGetFloat(_prefs, "neural_beam_prune_confidence", Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE)
        neural_beam_score_gap = safeGetFloat(_prefs, "neural_beam_score_gap", Defaults.NEURAL_BEAM_SCORE_GAP)
        neural_adaptive_width_step = safeGetInt(_prefs, "neural_adaptive_width_step", Defaults.NEURAL_ADAPTIVE_WIDTH_STEP)
        neural_score_gap_step = safeGetInt(_prefs, "neural_score_gap_step", Defaults.NEURAL_SCORE_GAP_STEP)
        neural_temperature = safeGetFloat(_prefs, "neural_temperature", Defaults.NEURAL_TEMPERATURE)
        neural_frequency_weight = safeGetFloat(_prefs, "neural_frequency_weight", Defaults.NEURAL_FREQUENCY_WEIGHT)
        swipe_smoothing_window = safeGetInt(_prefs, "swipe_smoothing_window", Defaults.SWIPE_SMOOTHING_WINDOW)

        neural_user_max_seq_length = safeGetInt(_prefs, "neural_user_max_seq_length", Defaults.NEURAL_USER_MAX_SEQ_LENGTH)
        neural_resampling_mode = safeGetString(_prefs, "neural_resampling_mode", Defaults.NEURAL_RESAMPLING_MODE)

        // Language-specific prefix boost settings (per-language keys)
        // Try per-language key first, fall back to global default
        neural_prefix_boost_multiplier = safeGetFloat(_prefs, "neural_prefix_boost_multiplier_$primary_language",
            safeGetFloat(_prefs, "neural_prefix_boost_multiplier", Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER))
        neural_prefix_boost_max = safeGetFloat(_prefs, "neural_prefix_boost_max_$primary_language",
            safeGetFloat(_prefs, "neural_prefix_boost_max", Defaults.NEURAL_PREFIX_BOOST_MAX))
        neural_max_cumulative_boost = safeGetFloat(_prefs, "neural_max_cumulative_boost",
            Defaults.NEURAL_MAX_CUMULATIVE_BOOST)
        neural_strict_start_char = _prefs.getBoolean("neural_strict_start_char",
            Defaults.NEURAL_STRICT_START_CHAR)

        val screen_width_dp = dm.widthPixels / dm.density
        wide_screen = screen_width_dp >= WIDE_DEVICE_THRESHOLD
    }

    fun get_current_layout(): Int {
        return if (wide_screen) current_layout_wide else current_layout_narrow
    }

    fun set_current_layout(l: Int) {
        if (wide_screen) {
            current_layout_wide = l
        } else {
            current_layout_narrow = l
        }
        _prefs.edit().apply {
            putInt("current_layout_portrait", current_layout_narrow)
            putInt("current_layout_landscape", current_layout_wide)
            apply()
        }
    }

    fun set_clipboard_history_enabled(e: Boolean) {
        clipboard_history_enabled = e
        _prefs.edit().putBoolean("clipboard_history_enabled", e).commit()
    }

    fun set_clipboard_history_limit(limit: Int) {
        clipboard_history_limit = limit
        _prefs.edit().putInt("clipboard_history_limit", limit).commit()
    }

    fun set_clipboard_pane_height_percent(percent: Int) {
        clipboard_pane_height_percent = percent.coerceIn(10, 50)
        _prefs.edit().putInt("clipboard_pane_height_percent", clipboard_pane_height_percent).commit()
    }

    private fun get_dip_pref(dm: DisplayMetrics, pref_name: String, def: Float): Float {
        var value = try {
            _prefs.getInt(pref_name, -1).toFloat()
        } catch (e: Exception) {
            try {
                _prefs.getFloat(pref_name, -1f)
            } catch (e2: Exception) {
                try {
                    _prefs.getString(pref_name, def.toString())?.toFloat() ?: -1f
                } catch (e3: Exception) {
                    -1f
                }
            }
        }
        if (value < 0f) value = def
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, dm)
    }

    private fun get_dip_pref_oriented(
        dm: DisplayMetrics,
        pref_base_name: String,
        def_port: Float,
        def_land: Float
    ): Float {
        val suffix = when {
            foldable_unfolded && orientation_landscape -> "_landscape_unfolded"
            foldable_unfolded -> "_portrait_unfolded"
            orientation_landscape -> "_landscape"
            else -> "_portrait"
        }
        val def = if (orientation_landscape) def_land else def_port
        return get_dip_pref(dm, pref_base_name + suffix, def)
    }

    /**
     * Get a percentage preference and convert to pixels based on screen width.
     * Used for horizontal margins (left/right).
     */
    private fun get_percent_pref_oriented_width(
        pref_base_name: String,
        def_port: Int,
        def_land: Int
    ): Float {
        val suffix = when {
            foldable_unfolded && orientation_landscape -> "_landscape_unfolded"
            foldable_unfolded -> "_portrait_unfolded"
            orientation_landscape -> "_landscape"
            else -> "_portrait"
        }
        val def = if (orientation_landscape) def_land else def_port
        val percent = safeGetInt(_prefs, pref_base_name + suffix, def).coerceIn(0, 45)
        return screenWidthPixels * percent / 100f
    }

    /**
     * Get a percentage preference and convert to pixels based on screen height.
     * Used for vertical margins (bottom).
     */
    private fun get_percent_pref_oriented_height(
        pref_base_name: String,
        def_port: Int,
        def_land: Int
    ): Float {
        val suffix = when {
            foldable_unfolded && orientation_landscape -> "_landscape_unfolded"
            foldable_unfolded -> "_portrait_unfolded"
            orientation_landscape -> "_landscape"
            else -> "_portrait"
        }
        val def = if (orientation_landscape) def_land else def_port
        val percent = safeGetInt(_prefs, pref_base_name + suffix, def).coerceIn(0, 30)
        return screenHeightPixels * percent / 100f
    }

    private fun getThemeId(res: Resources, theme_name: String): Int {
        val night_mode = res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return when {
            // Runtime themes (decorative/custom) use base theme for ContextThemeWrapper
            // The actual colors come from KeyboardColorScheme via ThemeProvider
            theme_name.startsWith("decorative_") -> R.style.CleverKeysDark
            theme_name.startsWith("custom_") -> R.style.CleverKeysDark

            // Built-in XML themes
            theme_name == "light" -> R.style.Light
            theme_name == "black" -> R.style.Black
            theme_name == "altblack" -> R.style.AltBlack
            theme_name == "dark" -> R.style.Dark
            theme_name == "white" -> R.style.White
            theme_name == "epaper" -> R.style.ePaper
            theme_name == "desert" -> R.style.Desert
            theme_name == "jungle" -> R.style.Jungle
            theme_name == "monetlight" -> R.style.MonetLight
            theme_name == "monetdark" -> R.style.MonetDark
            theme_name == "monet" -> {
                if (night_mode and Configuration.UI_MODE_NIGHT_NO != 0)
                    R.style.MonetLight
                else
                    R.style.MonetDark
            }
            theme_name == "rosepine" -> R.style.RosePine
            theme_name == "everforestlight" -> R.style.EverforestLight
            theme_name == "cobalt" -> R.style.Cobalt
            theme_name == "pine" -> R.style.Pine
            theme_name == "epaperblack" -> R.style.ePaperBlack
            theme_name == "jewel" -> R.style.Jewel
            theme_name == "cleverkeysdark" -> R.style.CleverKeysDark
            theme_name == "cleverkeyslight" -> R.style.CleverKeysLight
            else -> {
                // Default to CleverKeys Dark theme
                if (theme_name.isEmpty()) {
                    R.style.CleverKeysDark
                } else if (night_mode and Configuration.UI_MODE_NIGHT_NO != 0)
                    R.style.Light
                else
                    R.style.Dark
            }
        }
    }

    /**
     * Check if current theme is a runtime theme (decorative or custom).
     * Runtime themes use KeyboardColorScheme instead of XML attributes.
     */
    fun isRuntimeTheme(): Boolean {
        return themeName.startsWith("decorative_") || themeName.startsWith("custom_")
    }

    interface IKeyEventHandler {
        fun key_down(key: KeyValue?, isSwipe: Boolean)
        fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean = false)
        fun mods_changed(mods: Pointers.Modifiers)
    }

    companion object {
        const val WIDE_DEVICE_THRESHOLD = 600
        private const val CONFIG_VERSION = 3
        private const val MARGIN_PREFS_VERSION = 1  // For dp→percentage migration

        @Volatile
        private var _globalConfig: Config? = null

        @JvmStatic
        fun initGlobalConfig(
            prefs: SharedPreferences,
            res: Resources,
            handler: IKeyEventHandler?,
            foldableUnfolded: Boolean?
        ) {
            migrate(prefs)
            migrateMarginPrefs(prefs, res.displayMetrics)
            val config = Config(prefs, res, handler, foldableUnfolded)
            _globalConfig = config
            LayoutModifier.init(config, res)
        }

        @JvmStatic
        fun globalConfig(): Config = _globalConfig!!

        @JvmStatic
        fun globalPrefs(): SharedPreferences = _globalConfig!!._prefs

        @JvmStatic
        fun safeGetInt(prefs: SharedPreferences, key: String, defaultValue: Int): Int {
            return try {
                prefs.getInt(key, defaultValue)
            } catch (e: ClassCastException) {
                // Value might be stored as Float (from JSON import) or String
                try {
                    // Try Float first - JSON numbers are often stored as Float
                    val floatValue = prefs.getFloat(key, Float.MIN_VALUE)
                    if (floatValue == Float.MIN_VALUE) defaultValue else floatValue.toInt()
                } catch (e2: ClassCastException) {
                    // Try String
                    try {
                        val stringValue = prefs.getString(key, null)
                        stringValue?.toIntOrNull() ?: defaultValue
                    } catch (e3: Exception) {
                        Log.w("Config", "Corrupted int preference $key, using default: $defaultValue")
                        defaultValue
                    }
                }
            }
        }

        @JvmStatic
        fun repairCorruptedFloatPreferences(prefs: SharedPreferences) {
            // NOTE: These defaults MUST match Defaults.* constants for consistency
            val floatPrefs = arrayOf(
                arrayOf("character_size", "${Defaults.CHARACTER_SIZE}"),
                arrayOf("key_vertical_margin", "${Defaults.KEY_VERTICAL_MARGIN}"),
                arrayOf("key_horizontal_margin", "${Defaults.KEY_HORIZONTAL_MARGIN}"),
                arrayOf("custom_border_line_width", "${Defaults.CUSTOM_BORDER_LINE_WIDTH}"),
                arrayOf("prediction_context_boost", "${Defaults.PREDICTION_CONTEXT_BOOST}"),
                arrayOf("prediction_frequency_scale", "${Defaults.PREDICTION_FREQUENCY_SCALE}"),
                arrayOf("autocorrect_char_match_threshold", "${Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD}"),
                arrayOf("neural_confidence_threshold", "${Defaults.NEURAL_CONFIDENCE_THRESHOLD}"),
                arrayOf("neural_beam_alpha", "${Defaults.NEURAL_BEAM_ALPHA}"),
                arrayOf("neural_beam_prune_confidence", "${Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE}"),
                arrayOf("neural_beam_score_gap", "${Defaults.NEURAL_BEAM_SCORE_GAP}"),
                arrayOf("neural_temperature", "${Defaults.NEURAL_TEMPERATURE}"),
                arrayOf("neural_frequency_weight", "${Defaults.NEURAL_FREQUENCY_WEIGHT}"),
                arrayOf("swipe_rare_words_penalty", "${Defaults.SWIPE_RARE_WORDS_PENALTY}"),
                arrayOf("swipe_common_words_boost", "${Defaults.SWIPE_COMMON_WORDS_BOOST}"),
                arrayOf("swipe_top5000_boost", "${Defaults.SWIPE_TOP5000_BOOST}")
            )

            val editor = prefs.edit()
            var needsCommit = false

            for (pref in floatPrefs) {
                val key = pref[0]
                val defaultValue = pref[1].toFloat()

                try {
                    prefs.getFloat(key, defaultValue)
                } catch (e: ClassCastException) {
                    try {
                        val intValue = prefs.getInt(key, defaultValue.toInt())
                        val floatValue = intValue.toFloat()
                        editor.putFloat(key, floatValue)
                        needsCommit = true
                        Log.w("Config", "Repaired corrupted preference $key: int $intValue → float $floatValue")
                    } catch (e2: ClassCastException) {
                        try {
                            val stringValue = prefs.getString(key, defaultValue.toString()) ?: defaultValue.toString()
                            val floatValue = stringValue.toFloat()
                            editor.putFloat(key, floatValue)
                            needsCommit = true
                            Log.w("Config", "Repaired corrupted preference $key: string \"$stringValue\" → float $floatValue")
                        } catch (e3: Exception) {
                            editor.putFloat(key, defaultValue)
                            needsCommit = true
                            Log.w("Config", "Reset corrupted preference $key to default: $defaultValue")
                        }
                    }
                }
            }

            if (needsCommit) {
                editor.apply()
                Log.i("Config", "Applied preference repairs")
            }
        }

        @JvmStatic
        fun safeGetFloat(prefs: SharedPreferences, key: String, defaultValue: Float): Float {
            return try {
                prefs.getFloat(key, defaultValue)
            } catch (e: ClassCastException) {
                // Value might be stored as Int, String, or Boolean
                try {
                    val intValue = prefs.getInt(key, Int.MIN_VALUE)
                    if (intValue == Int.MIN_VALUE) defaultValue else {
                        Log.w("Config", "Float preference $key was stored as int: $intValue")
                        intValue.toFloat()
                    }
                } catch (e2: ClassCastException) {
                    try {
                        val stringValue = prefs.getString(key, null)
                        if (stringValue == null) {
                            defaultValue
                        } else {
                            val parsed = stringValue.toFloatOrNull()
                            if (parsed != null) {
                                Log.w("Config", "Float preference $key was stored as string: $stringValue")
                                parsed
                            } else {
                                defaultValue
                            }
                        }
                    } catch (e3: Exception) {
                        Log.w("Config", "Corrupted float preference $key, using default: $defaultValue")
                        defaultValue
                    }
                }
            }
        }

        /**
         * Safely get a Boolean preference, handling ClassCastException when value is stored as String or Int.
         * This is critical for config import where types may be mismatched.
         */
        @JvmStatic
        fun safeGetBoolean(prefs: SharedPreferences, key: String, defaultValue: Boolean): Boolean {
            return try {
                prefs.getBoolean(key, defaultValue)
            } catch (e: ClassCastException) {
                // Value might be stored as String or Int
                try {
                    val stringVal = prefs.getString(key, null)
                    when (stringVal?.lowercase()) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> defaultValue
                    }
                } catch (e2: ClassCastException) {
                    try {
                        prefs.getInt(key, -1).let {
                            when (it) {
                                1 -> true
                                0 -> false
                                else -> defaultValue
                            }
                        }
                    } catch (e3: Exception) {
                        Log.w("Config", "Corrupted boolean preference $key, using default: $defaultValue")
                        defaultValue
                    }
                } catch (e2: Exception) {
                    Log.w("Config", "Error reading boolean preference $key, using default: $defaultValue")
                    defaultValue
                }
            }
        }

        /**
         * Safely get a String preference, handling ClassCastException when value is stored as Int, Float, or Boolean.
         * This is critical for config import where numeric strings may be stored as integers.
         */
        @JvmStatic
        fun safeGetString(prefs: SharedPreferences, key: String, defaultValue: String): String {
            return try {
                prefs.getString(key, defaultValue) ?: defaultValue
            } catch (e: ClassCastException) {
                // Value might be stored as Int, Float, or Boolean (e.g., from config import)
                try {
                    val intValue = prefs.getInt(key, Int.MIN_VALUE)
                    if (intValue == Int.MIN_VALUE) {
                        defaultValue
                    } else {
                        Log.w("Config", "String preference $key was stored as int: $intValue")
                        intValue.toString()
                    }
                } catch (e2: ClassCastException) {
                    // Try Float
                    try {
                        val floatValue = prefs.getFloat(key, Float.MIN_VALUE)
                        if (floatValue == Float.MIN_VALUE) {
                            defaultValue
                        } else {
                            Log.w("Config", "String preference $key was stored as float: $floatValue")
                            floatValue.toString()
                        }
                    } catch (e3: ClassCastException) {
                        // Try Boolean
                        try {
                            val boolValue = prefs.getBoolean(key, false)
                            Log.w("Config", "String preference $key was stored as boolean: $boolValue")
                            boolValue.toString()
                        } catch (e4: Exception) {
                            Log.w("Config", "Corrupted string preference $key, using default: $defaultValue")
                            defaultValue
                        }
                    } catch (e3: Exception) {
                        Log.w("Config", "Error reading string preference $key, using default: $defaultValue")
                        defaultValue
                    }
                } catch (e2: Exception) {
                    Log.w("Config", "Error reading preference $key, using default: $defaultValue")
                    defaultValue
                }
            }
        }

        /**
         * Get the Android style resource ID for a theme name.
         * Used by ThemeProvider to load built-in XML themes.
         *
         * @param themeName Theme name string (e.g., "dark", "rosepine", "cobalt")
         * @return The R.style.* resource ID
         */
        @JvmStatic
        fun getThemeStyleId(themeName: String): Int {
            return when (themeName) {
                "light" -> R.style.Light
                "black" -> R.style.Black
                "altblack" -> R.style.AltBlack
                "dark" -> R.style.Dark
                "white" -> R.style.White
                "epaper" -> R.style.ePaper
                "desert" -> R.style.Desert
                "jungle" -> R.style.Jungle
                "monetlight" -> R.style.MonetLight
                "monetdark" -> R.style.MonetDark
                "monet" -> R.style.MonetDark // Default to dark for monet
                "rosepine" -> R.style.RosePine
                "everforestlight" -> R.style.EverforestLight
                "cobalt" -> R.style.Cobalt
                "pine" -> R.style.Pine
                "epaperblack" -> R.style.ePaperBlack
                "jewel" -> R.style.Jewel
                "cleverkeysdark" -> R.style.CleverKeysDark
                "cleverkeyslight" -> R.style.CleverKeysLight
                else -> R.style.CleverKeysDark // Default theme
            }
        }

        @JvmStatic
        fun migrate(prefs: SharedPreferences) {
            val saved_version = prefs.getInt("version", 0)
            Logs.debug_config_migration(saved_version, CONFIG_VERSION)
            if (saved_version == CONFIG_VERSION) return

            val e = prefs.edit()
            e.putInt("version", CONFIG_VERSION)

            when (saved_version) {
                0 -> {
                    val l = mutableListOf<LayoutsPreference.Layout>()
                    l.add(migrate_layout(safeGetString(prefs, "layout", "system")))
                    val snd_layout = safeGetString(prefs, "second_layout", "none")
                    if (snd_layout != "none")
                        l.add(migrate_layout(snd_layout))
                    val custom_layout = safeGetString(prefs, "custom_layout", "")
                    if (custom_layout.isNotEmpty())
                        l.add(LayoutsPreference.CustomLayout.parse(custom_layout))
                    LayoutsPreference.save_to_preferences(e, l)
                    // Fallthrough to case 1
                    val add_number_row = prefs.getBoolean("number_row", false)
                    e.putString("number_row", if (add_number_row) "no_symbols" else "no_number_row")
                    // Fallthrough to case 2
                    if (!prefs.contains("number_entry_layout")) {
                        e.putString("number_entry_layout", if (prefs.getBoolean("pin_entry_enabled", true)) "pin" else "number")
                    }
                }
                1 -> {
                    val add_number_row = prefs.getBoolean("number_row", false)
                    e.putString("number_row", if (add_number_row) "no_symbols" else "no_number_row")
                    // Fallthrough to case 2
                    if (!prefs.contains("number_entry_layout")) {
                        e.putString("number_entry_layout", if (prefs.getBoolean("pin_entry_enabled", true)) "pin" else "number")
                    }
                }
                2 -> {
                    if (!prefs.contains("number_entry_layout")) {
                        e.putString("number_entry_layout", if (prefs.getBoolean("pin_entry_enabled", true)) "pin" else "number")
                    }
                }
            }
            e.apply()
        }

        private fun migrate_layout(name: String?): LayoutsPreference.Layout {
            return if (name == null || name == "system")
                LayoutsPreference.SystemLayout()
            else
                LayoutsPreference.NamedLayout(name)
        }

        /**
         * Migrate margin preferences from old dp-based values to percentage-based.
         * This runs on every startup but only performs migration once (tracked by margin_prefs_version).
         *
         * Old system: values in dp (e.g., 14dp for bottom margin)
         * New system: values as % of screen dimension (e.g., 2% for bottom margin)
         *
         * Without this, Android Auto-Backup restores old dp values which get
         * interpreted as percentages (14dp becomes 14%, way too large).
         */
        @JvmStatic
        private fun migrateMarginPrefs(prefs: SharedPreferences, dm: DisplayMetrics) {
            val savedVersion = prefs.getInt("margin_prefs_version", 0)
            if (savedVersion >= MARGIN_PREFS_VERSION) return

            Log.i("Config", "Migrating margin preferences from dp to percentage (version $savedVersion → $MARGIN_PREFS_VERSION)")

            val editor = prefs.edit()
            val density = dm.density
            val screenWidth = dm.widthPixels
            val screenHeight = dm.heightPixels

            // Migrate horizontal margins (old: horizontal_margin_*, new: margin_left_* + margin_right_*)
            val horizontalKeys = listOf(
                "horizontal_margin_portrait" to listOf("margin_left_portrait", "margin_right_portrait"),
                "horizontal_margin_landscape" to listOf("margin_left_landscape", "margin_right_landscape"),
                "horizontal_margin_portrait_unfolded" to listOf("margin_left_portrait_unfolded", "margin_right_portrait_unfolded"),
                "horizontal_margin_landscape_unfolded" to listOf("margin_left_landscape_unfolded", "margin_right_landscape_unfolded")
            )

            for ((oldKey, newKeys) in horizontalKeys) {
                if (prefs.contains(oldKey)) {
                    try {
                        val dpValue = safeGetInt(prefs, oldKey, 0)
                        val pixelValue = dpValue * density
                        val percentValue = ((pixelValue / screenWidth) * 100).toInt().coerceIn(0, 45)
                        for (newKey in newKeys) {
                            editor.putInt(newKey, percentValue)
                        }
                        editor.remove(oldKey)
                        Log.i("Config", "Migrated $oldKey: ${dpValue}dp → $percentValue%")
                    } catch (e: Exception) {
                        Log.w("Config", "Failed to migrate $oldKey", e)
                    }
                }
            }

            // Migrate bottom margins - ALL existing values are assumed to be dp
            // (no threshold check - prefs_version flag tells us if migration happened)
            val bottomKeys = listOf(
                "margin_bottom_portrait" to Defaults.MARGIN_BOTTOM_PORTRAIT,
                "margin_bottom_landscape" to Defaults.MARGIN_BOTTOM_LANDSCAPE,
                "margin_bottom_portrait_unfolded" to Defaults.MARGIN_BOTTOM_PORTRAIT,
                "margin_bottom_landscape_unfolded" to Defaults.MARGIN_BOTTOM_LANDSCAPE
            )

            for ((key, defaultPercent) in bottomKeys) {
                if (prefs.contains(key)) {
                    try {
                        val oldValue = safeGetInt(prefs, key, defaultPercent)
                        // Convert dp to percentage of screen height
                        val pixelValue = oldValue * density
                        val percentValue = ((pixelValue / screenHeight) * 100).toInt().coerceIn(0, 80)
                        editor.putInt(key, percentValue)
                        Log.i("Config", "Migrated $key: ${oldValue}dp → $percentValue%")
                    } catch (e: Exception) {
                        Log.w("Config", "Failed to migrate $key", e)
                    }
                }
            }

            // Migrate left/right margins if they exist with dp values
            val leftRightKeys = listOf(
                "margin_left_portrait" to Defaults.MARGIN_LEFT_PORTRAIT,
                "margin_left_landscape" to Defaults.MARGIN_LEFT_LANDSCAPE,
                "margin_left_portrait_unfolded" to Defaults.MARGIN_LEFT_PORTRAIT,
                "margin_left_landscape_unfolded" to Defaults.MARGIN_LEFT_LANDSCAPE,
                "margin_right_portrait" to Defaults.MARGIN_RIGHT_PORTRAIT,
                "margin_right_landscape" to Defaults.MARGIN_RIGHT_LANDSCAPE,
                "margin_right_portrait_unfolded" to Defaults.MARGIN_RIGHT_PORTRAIT,
                "margin_right_landscape_unfolded" to Defaults.MARGIN_RIGHT_LANDSCAPE
            )

            for ((key, defaultPercent) in leftRightKeys) {
                if (prefs.contains(key)) {
                    try {
                        val oldValue = safeGetInt(prefs, key, defaultPercent)
                        // Convert dp to percentage of screen width
                        val pixelValue = oldValue * density
                        val percentValue = ((pixelValue / screenWidth) * 100).toInt().coerceIn(0, 45)
                        editor.putInt(key, percentValue)
                        Log.i("Config", "Migrated $key: ${oldValue}dp → $percentValue%")
                    } catch (e: Exception) {
                        Log.w("Config", "Failed to migrate $key", e)
                    }
                }
            }

            // Mark migration complete
            editor.putInt("margin_prefs_version", MARGIN_PREFS_VERSION)
            editor.apply()
            Log.i("Config", "Margin preference migration complete")
        }
    }
}
