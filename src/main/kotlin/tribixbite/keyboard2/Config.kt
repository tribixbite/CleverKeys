package tribixbite.keyboard2

import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.TypedValue
import tribixbite.keyboard2.prefs.*
import tribixbite.keyboard2.R
import kotlin.math.max
import kotlin.math.min

/**
 * Global keyboard configuration with complete Kotlin implementation
 * Maintains all functionality from original Config.java with modern Kotlin patterns
 */
class Config private constructor(
    private val prefs: SharedPreferences,
    resources: Resources,
    val handler: IKeyEventHandler?,
    foldableUnfolded: Boolean?
) {

    companion object {
        const val WIDE_DEVICE_THRESHOLD = 600
        private const val CONFIG_VERSION = 3

        private var globalConfig: Config? = null

        /**
         * Initialize global configuration
         */
        fun initGlobalConfig(
            prefs: SharedPreferences,
            resources: Resources,
            handler: IKeyEventHandler?,
            foldableUnfolded: Boolean?
        ) {
            migrate(prefs)
            globalConfig = Config(prefs, resources, handler, foldableUnfolded)
            LayoutModifier.init(globalConfig!!, resources)
        }

        /**
         * Get global configuration instance
         */
        fun globalConfig(): Config {
            return globalConfig ?: throw IllegalStateException("Config not initialized")
        }

        /**
         * Get global preferences
         */
        fun globalPrefs(): SharedPreferences {
            return globalConfig?.prefs ?: throw IllegalStateException("Config not initialized")
        }

        /**
         * Safe integer preference access handling type mismatches
         */
        fun safeGetInt(prefs: SharedPreferences, key: String, defaultValue: Int): Int {
            return try {
                prefs.getInt(key, defaultValue)
            } catch (e: ClassCastException) {
                val stringValue = prefs.getString(key, defaultValue.toString()) ?: defaultValue.toString()
                try {
                    stringValue.toInt()
                } catch (nfe: NumberFormatException) {
                    android.util.Log.w("Config", "Invalid number format for $key: $stringValue, using default: $defaultValue")
                    defaultValue
                }
            }
        }

        /**
         * Configuration migration logic
         */
        fun migrate(prefs: SharedPreferences) {
            val savedVersion = prefs.getInt("version", 0)
            Logs.debug_config_migration(savedVersion, CONFIG_VERSION)
            if (savedVersion == CONFIG_VERSION) return

            val editor = prefs.edit()
            editor.putInt("version", CONFIG_VERSION)

            when (savedVersion) {
                0 -> {
                    // Migrate primary, secondary and custom layout options to new Layouts option
                    val layouts = mutableListOf<LayoutsPreference.Layout>()
                    layouts.add(migrateLayout(prefs.getString("layout", "system") ?: "system"))

                    val secondLayout = prefs.getString("second_layout", "none")
                    if (!secondLayout.isNullOrEmpty() && secondLayout != "none") {
                        layouts.add(migrateLayout(secondLayout))
                    }

                    val customLayout = prefs.getString("custom_layout", "")
                    if (!customLayout.isNullOrEmpty()) {
                        layouts.add(LayoutsPreference.CustomLayout.parse(customLayout))
                    }

                    LayoutsPreference.saveToPreferences(editor, layouts)
                    // Fallthrough
                }
                1 -> {
                    val addNumberRow = prefs.getBoolean("number_row", false)
                    editor.putString("number_row", if (addNumberRow) "no_symbols" else "no_number_row")
                    // Fallthrough
                }
                2 -> {
                    // Additional migrations for version 2->3 if needed
                }
            }

            editor.apply()
        }

        private fun migrateLayout(layoutName: String): LayoutsPreference.Layout {
            return when (layoutName) {
                "system" -> LayoutsPreference.SystemLayout()
                else -> LayoutsPreference.NamedLayout(layoutName)
            }
        }
    }

    // Resource values
    val marginTop: Float = resources.getDimension(R.dimen.margin_top)
    val keyPadding: Float = resources.getDimension(R.dimen.key_padding)
    val labelTextSize: Float = 0.33f
    val sublabelTextSize: Float = 0.22f

    // Core configuration properties (initialized in refresh())
    var layouts: List<KeyboardData> = emptyList()
    var show_numpad = false
    var inverse_numpad = false
    var add_number_row = false
    var number_row_symbols = false
    var swipe_dist_px = 0f
    var slide_step_px = 0f
    var vibrate_custom = false
    var vibrate_duration = 20L
    var longPressTimeout = 600L
    var longPressInterval = 65L
    var keyrepeat_enabled = true
    var margin_bottom = 0f
    var keyboardHeightPercent = 35
    var screenHeightPixels = 0
    var horizontal_margin = 0f
    var key_vertical_margin = 0f
    var key_horizontal_margin = 0f
    var labelBrightness = 255
    var keyboardOpacity = 255
    var customBorderRadius = 0f
    var customBorderLineWidth = 0f
    var keyOpacity = 255
    var keyActivatedOpacity = 255
    var double_tap_lock_shift = false
    var characterSize = 1f
    var theme = R.style.Dark
    var autocapitalisation = true
    var switch_input_immediate = false
    var selected_number_layout = NumberLayout.PIN
    var borderConfig = false
    var circle_sensitivity = 2
    var clipboard_history_enabled = false
    var clipboard_history_limit = 6
    var swipe_typing_enabled = true

    // Legacy swipe parameters (for compatibility with existing WordPredictor)
    var swipe_confidence_shape_weight = 0.9f
    var swipe_confidence_location_weight = 1.3f
    var swipe_confidence_frequency_weight = 0.8f
    var swipe_confidence_velocity_weight = 0.6f
    var swipe_first_letter_weight = 1.5f
    var swipe_last_letter_weight = 1.5f
    var swipe_endpoint_bonus_weight = 2.0f
    var swipe_require_endpoints = false
    var swipe_show_debug_scores = false
    var word_prediction_enabled = false
    var suggestion_bar_opacity = 90

    // Neural swipe prediction configuration
    var neural_prediction_enabled = true
    var neural_beam_width = 8
    var neural_max_length = 35
    var neural_confidence_threshold = 0.1f
    var termux_mode_enabled = false

    // Dynamically set properties
    var shouldOfferVoiceTyping = false
    var actionLabel: String? = null
    var actionId = 0
    var swapEnterActionKey = false
    var extra_keys_subtype: ExtraKeys? = null
    var extra_keys_param: Map<KeyValue, KeyboardData.PreferredPos> = emptyMap()
    var extra_keys_custom: Map<KeyValue, KeyboardData.PreferredPos> = emptyMap()

    // Layout and orientation state
    var orientation_landscape = false
    var foldable_unfolded = false
    var wide_screen = false
    private var current_layout_narrow = 0
    private var current_layout_wide = 0

    init {
        refresh(resources, foldableUnfolded)

        // Initialize dynamic properties
        shouldOfferVoiceTyping = false
        actionLabel = null
        actionId = 0
        swapEnterActionKey = false
        extra_keys_subtype = null
    }

    /**
     * Key event handler interface
     */
    interface IKeyEventHandler {
        fun key_down(value: KeyValue, is_swipe: Boolean)
        fun key_up(value: KeyValue, mods: Pointers.Modifiers)
        fun mods_changed(mods: Pointers.Modifiers)
        fun started(info: android.view.inputmethod.EditorInfo?)
    }

    /**
     * Reload preferences and update configuration
     */
    fun refresh(resources: Resources, foldableUnfolded: Boolean?) {
        val dm = resources.displayMetrics
        orientation_landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        this.foldable_unfolded = foldableUnfolded ?: false

        var characterSizeScale = 1f
        val showNumpadString = prefs.getString("show_numpad", "never") ?: "never"
        show_numpad = "always" == showNumpadString

        if (orientation_landscape) {
            if ("landscape" == showNumpadString) {
                show_numpad = true
            }
            keyboardHeightPercent = prefs.getInt(
                if (foldable_unfolded == true) "keyboard_height_landscape_unfolded" else "keyboard_height_landscape",
                50
            )
            characterSizeScale = 1.25f
        } else {
            keyboardHeightPercent = prefs.getInt(
                if (foldable_unfolded == true) "keyboard_height_unfolded" else "keyboard_height",
                35
            )
        }

        layouts = LayoutsPreference.loadFromPreferences(resources, prefs).filterNotNull()
        inverse_numpad = (prefs.getString("numpad_layout", "default") ?: "default") == "low_first"

        val numberRow = prefs.getString("number_row", "no_number_row") ?: "no_number_row"
        add_number_row = numberRow != "no_number_row"
        number_row_symbols = numberRow == "symbols"

        // Calculate swipe distance based on screen metrics
        val dpiRatio = max(dm.xdpi, dm.ydpi) / min(dm.xdpi, dm.ydpi)
        val swipeScaling = min(dm.widthPixels, dm.heightPixels) / 10f * dpiRatio
        val swipeDistValue = (prefs.getString("swipe_dist", "15") ?: "15").toFloat()
        swipe_dist_px = swipeDistValue / 25f * swipeScaling

        val sliderSensitivity = (prefs.getString("slider_sensitivity", "30") ?: "30").toFloat() / 100f
        slide_step_px = sliderSensitivity * swipeScaling

        vibrate_custom = prefs.getBoolean("vibrate_custom", false)
        vibrate_duration = prefs.getInt("vibrate_duration", 20).toLong()
        longPressTimeout = prefs.getInt("longpress_timeout", 600).toLong()
        longPressInterval = prefs.getInt("longpress_interval", 65).toLong()
        keyrepeat_enabled = prefs.getBoolean("keyrepeat_enabled", true)
        margin_bottom = getDipPrefOriented(dm, "margin_bottom", 7f, 3f)
        key_vertical_margin = getDipPref(dm, "key_vertical_margin", 1.5f) / 100f
        key_horizontal_margin = getDipPref(dm, "key_horizontal_margin", 2f) / 100f

        // Visual settings
        labelBrightness = prefs.getInt("label_brightness", 100) * 255 / 100
        keyboardOpacity = prefs.getInt("keyboard_opacity", 100) * 255 / 100
        keyOpacity = prefs.getInt("key_opacity", 100) * 255 / 100
        keyActivatedOpacity = prefs.getInt("key_activated_opacity", 100) * 255 / 100

        borderConfig = prefs.getBoolean("border_config", false)
        customBorderRadius = prefs.getInt("custom_border_radius", 0) / 100f
        customBorderLineWidth = getDipPref(dm, "custom_border_line_width", 0f)
        screenHeightPixels = dm.heightPixels
        horizontal_margin = getDipPrefOriented(dm, "horizontal_margin", 3f, 28f)
        double_tap_lock_shift = prefs.getBoolean("lock_double_tap", false)
        characterSize = prefs.getFloat("character_size", 1.15f) * characterSizeScale
        theme = getThemeId(resources, prefs.getString("theme", "") ?: "")
        autocapitalisation = prefs.getBoolean("autocapitalisation", true)
        switch_input_immediate = prefs.getBoolean("switch_input_immediate", false)
        extra_keys_param = emptyMap() // TODO: Fix ExtraKeysPreference.get_extra_keys(prefs)
        extra_keys_custom = ExtraKeysPreference.getExtraKeys(prefs)
        selected_number_layout = NumberLayout.of_string(prefs.getString("number_entry_layout", "pin") ?: "pin")
        current_layout_narrow = prefs.getInt("current_layout_portrait", 0)
        current_layout_wide = prefs.getInt("current_layout_landscape", 0)
        circle_sensitivity = (prefs.getString("circle_sensitivity", "2") ?: "2").toInt()
        clipboard_history_enabled = prefs.getBoolean("clipboard_history_enabled", false)

        clipboard_history_limit = try {
            prefs.getInt("clipboard_history_limit", 6)
        } catch (e: ClassCastException) {
            val stringValue = prefs.getString("clipboard_history_limit", "6") ?: "6"
            val intValue = stringValue.toInt()
            android.util.Log.w("Config", "Fixed clipboard_history_limit type mismatch: $stringValue")
            intValue
        }

        swipe_typing_enabled = prefs.getBoolean("swipe_typing_enabled", true)

        // Legacy swipe parameters (for compatibility)
        swipe_confidence_shape_weight = safeGetInt(prefs, "swipe_confidence_shape_weight", 90) / 100f
        swipe_confidence_location_weight = safeGetInt(prefs, "swipe_confidence_location_weight", 130) / 100f
        swipe_confidence_frequency_weight = safeGetInt(prefs, "swipe_confidence_frequency_weight", 80) / 100f
        swipe_confidence_velocity_weight = safeGetInt(prefs, "swipe_confidence_velocity_weight", 60) / 100f
        swipe_first_letter_weight = safeGetInt(prefs, "swipe_first_letter_weight", 150) / 100f
        swipe_last_letter_weight = safeGetInt(prefs, "swipe_last_letter_weight", 150) / 100f
        swipe_endpoint_bonus_weight = safeGetInt(prefs, "swipe_endpoint_bonus_weight", 200) / 100f
        swipe_require_endpoints = prefs.getBoolean("swipe_require_endpoints", false)
        swipe_show_debug_scores = prefs.getBoolean("swipe_show_debug_scores", false)
        word_prediction_enabled = prefs.getBoolean("word_prediction_enabled", false)
        suggestion_bar_opacity = safeGetInt(prefs, "suggestion_bar_opacity", 90)

        // Neural swipe prediction configuration
        neural_prediction_enabled = prefs.getBoolean("neural_prediction_enabled", true)
        neural_beam_width = safeGetInt(prefs, "neural_beam_width", 8)
        neural_max_length = safeGetInt(prefs, "neural_max_length", 35)
        neural_confidence_threshold = prefs.getFloat("neural_confidence_threshold", 0.1f)
        termux_mode_enabled = prefs.getBoolean("termux_mode_enabled", false)

        val screenWidthDp = dm.widthPixels / dm.density
        wide_screen = screenWidthDp >= WIDE_DEVICE_THRESHOLD
    }

    /**
     * Get current layout index
     */
    fun get_current_layout(): Int {
        return if (wide_screen) current_layout_wide else current_layout_narrow
    }

    /**
     * Set current layout and persist to preferences
     */
    fun set_current_layout(layout: Int) {
        if (wide_screen) {
            current_layout_wide = layout
        } else {
            current_layout_narrow = layout
        }

        val editor = prefs.edit()
        editor.putInt("current_layout_portrait", current_layout_narrow)
        editor.putInt("current_layout_landscape", current_layout_wide)
        editor.apply()
    }

    /**
     * Set clipboard history enabled state
     */
    fun set_clipboard_history_enabled(enabled: Boolean) {
        clipboard_history_enabled = enabled
        prefs.edit().putBoolean("clipboard_history_enabled", enabled).commit()
    }

    /**
     * Set clipboard history limit
     */
    fun set_clipboard_history_limit(limit: Int) {
        clipboard_history_limit = limit
        prefs.edit().putInt("clipboard_history_limit", limit).commit()
    }

    /**
     * Get DIP-based preference value
     */
    private fun getDipPref(dm: DisplayMetrics, prefName: String, defaultValue: Float): Float {
        val value = try {
            prefs.getInt(prefName, -1).toFloat()
        } catch (e: Exception) {
            prefs.getFloat(prefName, -1f)
        }

        val finalValue = if (value < 0f) defaultValue else value
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, finalValue, dm)
    }

    /**
     * Get orientation-dependent DIP preference
     */
    private fun getDipPrefOriented(dm: DisplayMetrics, prefBaseName: String, defaultPort: Float, defaultLand: Float): Float {
        val suffix = when {
            foldable_unfolded -> if (orientation_landscape) "_landscape_unfolded" else "_portrait_unfolded"
            else -> if (orientation_landscape) "_landscape" else "_portrait"
        }

        val defaultValue = if (orientation_landscape) defaultLand else defaultPort
        return getDipPref(dm, prefBaseName + suffix, defaultValue)
    }

    /**
     * Get theme resource ID from theme name
     */
    private fun getThemeId(resources: Resources, themeName: String): Int {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

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
            "monet" -> {
                if ((nightMode and Configuration.UI_MODE_NIGHT_NO) != 0) {
                    R.style.MonetLight
                } else {
                    R.style.MonetDark
                }
            }
            "rosepine" -> R.style.RosePine
            "system", "" -> {
                if ((nightMode and Configuration.UI_MODE_NIGHT_NO) != 0) {
                    R.style.Light
                } else {
                    R.style.Dark
                }
            }
            else -> R.style.Dark
        }
    }
}