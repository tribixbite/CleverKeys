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
    @JvmField var layouts: List<KeyboardData> = emptyList()
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
    @JvmField var margin_bottom = 0f
    @JvmField var keyboardHeightPercent = 0
    @JvmField var screenHeightPixels = 0
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
    @JvmField var neural_prediction_enabled = false
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

    // Neural model versioning and resampling
    @JvmField var neural_model_version: String? = null
    @JvmField var neural_use_quantized = false
    @JvmField var neural_user_max_seq_length = 0
    @JvmField var neural_resampling_mode: String? = null
    @JvmField var neural_custom_encoder_path: String? = null
    @JvmField var neural_custom_decoder_path: String? = null

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
        val show_numpad_s = safeGetString(_prefs, "show_numpad", "never")
        show_numpad = "always" == show_numpad_s
        
        if (orientation_landscape) {
            if ("landscape" == show_numpad_s) show_numpad = true
            keyboardHeightPercent = safeGetInt(
                _prefs,
                if (this.foldable_unfolded) "keyboard_height_landscape_unfolded" else "keyboard_height_landscape",
                50
            )
            characterSizeScale = 1.25f
        } else {
            keyboardHeightPercent = safeGetInt(
                _prefs,
                if (this.foldable_unfolded) "keyboard_height_unfolded" else "keyboard_height",
                27
            )
        }

        layouts = LayoutsPreference.load_from_preferences(res, _prefs).filterNotNull()
        inverse_numpad = safeGetString(_prefs, "numpad_layout", "default") == "low_first"
        
        val number_row = safeGetString(_prefs, "number_row", "no_number_row")
        add_number_row = number_row != "no_number_row"
        number_row_symbols = number_row == "symbols"

        val dpi_ratio = maxOf(dm.xdpi, dm.ydpi) / minOf(dm.xdpi, dm.ydpi)
        val swipe_scaling = minOf(dm.widthPixels, dm.heightPixels) / 10f * dpi_ratio
        val swipe_dist_value = safeGetString(_prefs, "swipe_dist", "15").toFloatOrNull() ?: 15f
        swipe_dist_px = swipe_dist_value / 25f * swipe_scaling

        val slider_sensitivity = (safeGetString(_prefs, "slider_sensitivity", "30").toFloatOrNull() ?: 30f) / 100f
        slide_step_px = slider_sensitivity * swipe_scaling

        vibrate_custom = _prefs.getBoolean("vibrate_custom", false)
        vibrate_duration = safeGetInt(_prefs, "vibrate_duration", 20).toLong()
        longPressTimeout = safeGetInt(_prefs, "longpress_timeout", 600).toLong()
        longPressInterval = safeGetInt(_prefs, "longpress_interval", 25).toLong()
        keyrepeat_enabled = _prefs.getBoolean("keyrepeat_enabled", true)
        margin_bottom = get_dip_pref_oriented(dm, "margin_bottom", 7f, 3f)
        key_vertical_margin = get_dip_pref(dm, "key_vertical_margin", 0.65f) / 100
        key_horizontal_margin = get_dip_pref(dm, "key_horizontal_margin", 0.7f) / 100

        labelBrightness = safeGetInt(_prefs, "label_brightness", 100) * 255 / 100
        keyboardOpacity = safeGetInt(_prefs, "keyboard_opacity", 81) * 255 / 100
        keyOpacity = safeGetInt(_prefs, "key_opacity", 100) * 255 / 100
        keyActivatedOpacity = safeGetInt(_prefs, "key_activated_opacity", 100) * 255 / 100

        borderConfig = _prefs.getBoolean("border_config", false)
        customBorderRadius = _prefs.getInt("custom_border_radius", 0) / 100f
        customBorderLineWidth = get_dip_pref(dm, "custom_border_line_width", 0f)
        screenHeightPixels = dm.heightPixels
        horizontal_margin = get_dip_pref_oriented(dm, "horizontal_margin", 3f, 28f)
        double_tap_lock_shift = _prefs.getBoolean("lock_double_tap", true)
        characterSize = safeGetFloat(_prefs, "character_size", 1.18f) * characterSizeScale
        themeName = safeGetString(_prefs, "theme", "cleverkeysdark")
        theme = getThemeId(res, themeName)
        autocapitalisation = _prefs.getBoolean("autocapitalisation", true)
        switch_input_immediate = _prefs.getBoolean("switch_input_immediate", false)
        extra_keys_param = ExtraKeysPreference.get_extra_keys(_prefs) ?: emptyMap()
        extra_keys_custom = CustomExtraKeysPreference.get(_prefs) ?: emptyMap()
        selected_number_layout = NumberLayout.of_string(safeGetString(_prefs, "number_entry_layout", "pin"))
        current_layout_narrow = safeGetInt(_prefs, "current_layout_portrait", 0)
        current_layout_wide = safeGetInt(_prefs, "current_layout_landscape", 0)
        circle_sensitivity = safeGetString(_prefs, "circle_sensitivity", "2").toIntOrNull() ?: 2
        clipboard_history_enabled = _prefs.getBoolean("clipboard_history_enabled", true)

        clipboard_history_limit = safeGetString(_prefs, "clipboard_history_limit", "0").toIntOrNull() ?: 0

        clipboard_pane_height_percent = safeGetInt(_prefs, "clipboard_pane_height_percent", 30).coerceIn(10, 50)

        clipboard_max_item_size_kb = safeGetString(_prefs, "clipboard_max_item_size_kb", "500").toIntOrNull() ?: 500

        clipboard_limit_type = safeGetString(_prefs, "clipboard_limit_type", "count")

        clipboard_size_limit_mb = safeGetString(_prefs, "clipboard_size_limit_mb", "10").toIntOrNull() ?: 10

        swipe_typing_enabled = _prefs.getBoolean("swipe_typing_enabled", true)
        swipe_show_debug_scores = _prefs.getBoolean("swipe_show_debug_scores", false)
        word_prediction_enabled = _prefs.getBoolean("word_prediction_enabled", true)
        suggestion_bar_opacity = safeGetInt(_prefs, "suggestion_bar_opacity", 80)

        prediction_context_boost = safeGetFloat(_prefs, "prediction_context_boost", 0.5f)
        prediction_frequency_scale = safeGetFloat(_prefs, "prediction_frequency_scale", 100.0f)
        context_aware_predictions_enabled = _prefs.getBoolean("context_aware_predictions_enabled", true)
        personalized_learning_enabled = _prefs.getBoolean("personalized_learning_enabled", true)
        learning_aggression = safeGetString(_prefs, "learning_aggression", "BALANCED")

        // Multi-language settings (Phase 8.3 & 8.4)
        enable_multilang = _prefs.getBoolean("pref_enable_multilang", false)
        primary_language = safeGetString(_prefs, "pref_primary_language", "en")
        auto_detect_language = _prefs.getBoolean("pref_auto_detect_language", true)
        // SlideBarPreference stores as Float (0.4-0.9), not Int
        language_detection_sensitivity = safeGetFloat(_prefs, "pref_language_detection_sensitivity", 0.6f)

        autocorrect_enabled = _prefs.getBoolean("autocorrect_enabled", true)
        autocorrect_min_word_length = safeGetInt(_prefs, "autocorrect_min_word_length", 3)
        autocorrect_char_match_threshold = safeGetFloat(_prefs, "autocorrect_char_match_threshold", 0.67f)
        autocorrect_confidence_min_frequency = safeGetInt(_prefs, "autocorrect_confidence_min_frequency", 100)

        autocorrect_max_length_diff = safeGetInt(_prefs, "autocorrect_max_length_diff", 2)
        autocorrect_prefix_length = safeGetInt(_prefs, "autocorrect_prefix_length", 1)
        autocorrect_max_beam_candidates = safeGetInt(_prefs, "autocorrect_max_beam_candidates", 3)

        swipe_beam_autocorrect_enabled = _prefs.getBoolean("swipe_beam_autocorrect_enabled", true)
        swipe_final_autocorrect_enabled = _prefs.getBoolean("swipe_final_autocorrect_enabled", true)
        swipe_fuzzy_match_mode = safeGetString(_prefs, "swipe_fuzzy_match_mode", "edit_distance")

        val predictionSource = safeGetInt(_prefs, "swipe_prediction_source", 80)
        swipe_confidence_weight = predictionSource / 100.0f
        swipe_frequency_weight = 1.0f - swipe_confidence_weight

        swipe_common_words_boost = safeGetFloat(_prefs, "swipe_common_words_boost", 1.0f)
        swipe_top5000_boost = safeGetFloat(_prefs, "swipe_top5000_boost", 1.0f)
        swipe_rare_words_penalty = safeGetFloat(_prefs, "swipe_rare_words_penalty", 1.0f)

        short_gestures_enabled = _prefs.getBoolean("short_gestures_enabled", true)
        short_gesture_min_distance = safeGetInt(_prefs, "short_gesture_min_distance", 40)
        short_gesture_max_distance = safeGetInt(_prefs, "short_gesture_max_distance", 200) // 200 = disabled

        // Gesture timing configuration
        tap_duration_threshold = safeGetInt(_prefs, "tap_duration_threshold", 150).toLong()
        double_space_threshold = safeGetInt(_prefs, "double_space_threshold", 500).toLong()
        smart_punctuation = _prefs.getBoolean("smart_punctuation", true)
        swipe_min_dwell_time = safeGetInt(_prefs, "swipe_min_dwell_time", 7).toLong()
        swipe_noise_threshold = safeGetFloat(_prefs, "swipe_noise_threshold", 1.26f)
        swipe_high_velocity_threshold = safeGetFloat(_prefs, "swipe_high_velocity_threshold", 1000.0f)
        swipe_min_distance = safeGetFloat(_prefs, "swipe_min_distance", 46.4f)
        swipe_min_key_distance = safeGetFloat(_prefs, "swipe_min_key_distance", 35.15f)

        // Slider speed configuration
        slider_speed_smoothing = safeGetFloat(_prefs, "slider_speed_smoothing", 0.54f)
        slider_speed_max = safeGetFloat(_prefs, "slider_speed_max", 4.0f)

        // Swipe trail appearance
        swipe_trail_enabled = _prefs.getBoolean("swipe_trail_enabled", true)
        swipe_trail_effect = safeGetString(_prefs, "swipe_trail_effect", "sparkle")
        swipe_trail_color = _prefs.getInt("swipe_trail_color", 0xFFC0C0C0.toInt()) // Silver
        swipe_trail_width = safeGetFloat(_prefs, "swipe_trail_width", 8.0f)
        swipe_trail_glow_radius = safeGetFloat(_prefs, "swipe_trail_glow_radius", 6.0f)

        neural_prediction_enabled = _prefs.getBoolean("neural_prediction_enabled", true)
        neural_beam_width = safeGetInt(_prefs, "neural_beam_width", 4)
        neural_max_length = safeGetInt(_prefs, "neural_max_length", 20)
        neural_confidence_threshold = safeGetFloat(_prefs, "neural_confidence_threshold", 0.01f)
        neural_batch_beams = _prefs.getBoolean("neural_batch_beams", false)
        neural_greedy_search = _prefs.getBoolean("neural_greedy_search", false)
        termux_mode_enabled = _prefs.getBoolean("termux_mode_enabled", true)
        swipe_debug_detailed_logging = _prefs.getBoolean("swipe_debug_detailed_logging", false)
        swipe_debug_show_raw_output = _prefs.getBoolean("swipe_debug_show_raw_output", true)
        swipe_show_raw_beam_predictions = _prefs.getBoolean("swipe_show_raw_beam_predictions", true)

        neural_beam_alpha = safeGetFloat(_prefs, "neural_beam_alpha", 1.0f)
        neural_beam_prune_confidence = safeGetFloat(_prefs, "neural_beam_prune_confidence", 0.17821783f)
        neural_beam_score_gap = safeGetFloat(_prefs, "neural_beam_score_gap", 20.0f)

        neural_model_version = safeGetString(_prefs, "neural_model_version", "v2")
        neural_use_quantized = _prefs.getBoolean("neural_use_quantized", true)
        neural_user_max_seq_length = safeGetInt(_prefs, "neural_user_max_seq_length", 0)
        neural_resampling_mode = safeGetString(_prefs, "neural_resampling_mode", "discard")

        // Use try-catch for optional nullable strings (custom encoder/decoder paths)
        neural_custom_encoder_path = try {
            _prefs.getString("neural_custom_encoder_uri", null)
                ?: _prefs.getString("neural_custom_encoder_path", null)
        } catch (e: ClassCastException) { null }

        neural_custom_decoder_path = try {
            _prefs.getString("neural_custom_decoder_uri", null)
                ?: _prefs.getString("neural_custom_decoder_path", null)
        } catch (e: ClassCastException) { null }

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
        fun key_up(key: KeyValue?, mods: Pointers.Modifiers)
        fun mods_changed(mods: Pointers.Modifiers)
    }

    companion object {
        const val WIDE_DEVICE_THRESHOLD = 600
        private const val CONFIG_VERSION = 3

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
            val floatPrefs = arrayOf(
                arrayOf("character_size", "1.18"),
                arrayOf("key_vertical_margin", "0.65"),
                arrayOf("key_horizontal_margin", "0.7"),
                arrayOf("custom_border_line_width", "0.0"),
                arrayOf("prediction_context_boost", "0.5"),
                arrayOf("prediction_frequency_scale", "100.0"),
                arrayOf("autocorrect_char_match_threshold", "0.67"),
                arrayOf("neural_confidence_threshold", "0.01"),
                arrayOf("neural_beam_alpha", "1.0"),
                arrayOf("neural_beam_prune_confidence", "0.03"),
                arrayOf("neural_beam_score_gap", "20.0"),
                arrayOf("swipe_rare_words_penalty", "1.0"),
                arrayOf("swipe_common_words_boost", "1.0"),
                arrayOf("swipe_top5000_boost", "1.0")
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
    }
}
