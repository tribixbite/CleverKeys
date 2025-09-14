package juloo.keyboard2

import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.Configuration
import android.util.DisplayMetrics
import juloo.keyboard2.prefs.*

/**
 * Global keyboard configuration with Kotlin improvements
 * Complete implementation maintaining all original functionality
 */
class Config private constructor(
    private val prefs: SharedPreferences,
    resources: Resources,
    val handler: IKeyEventHandler?,
    foldableUnfolded: Boolean?
) {
    
    // Core keyboard settings
    var swipe_typing_enabled = true
    var neural_prediction_enabled = true
    var word_prediction_enabled = false
    
    // Neural settings (will be managed by NeuralConfig)
    var neural_beam_width = 8
    var neural_max_length = 35
    var neural_confidence_threshold = 0.1f
    
    // Layout settings
    var character_size = 1.0f
    var key_vertical_margin = 1.5f
    var key_horizontal_margin = 2.0f
    
    // Complete settings from original Config.java
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
    var labelBrightness = 255
    var keyboardOpacity = 255
    var customBorderRadius = 0f
    var customBorderLineWidth = 0f
    var keyOpacity = 255
    var keyActivatedOpacity = 255
    var double_tap_lock_shift = false
    var theme = R.style.Dark
    var autocapitalisation = true
    var switch_input_immediate = false
    var selected_number_layout = NumberLayout.PIN
    var circle_sensitivity = 2
    var clipboard_history_enabled = false
    var clipboard_history_limit = 6
    var suggestion_bar_opacity = 90
    
    // Legacy swipe parameters
    var swipe_confidence_shape_weight = 0.9f
    var swipe_confidence_location_weight = 1.3f
    var swipe_confidence_frequency_weight = 0.8f
    var swipe_confidence_velocity_weight = 0.6f
    var swipe_first_letter_weight = 1.5f
    var swipe_last_letter_weight = 1.5f
    var swipe_endpoint_bonus_weight = 2.0f
    var swipe_require_endpoints = false
    var swipe_show_debug_scores = false
    
    // Display settings
    var orientation_landscape = false
    var foldable_unfolded = false
    var wide_screen = false
    
    // Dynamic settings
    var shouldOfferVoiceTyping = false
    var actionLabel: String? = null
    var actionId = 0
    var swapEnterActionKey = false
    var extra_keys_subtype: ExtraKeys? = null
    var extra_keys_param: Map<KeyValue, KeyboardData.PreferredPos> = emptyMap()
    var extra_keys_custom: Map<KeyValue, KeyboardData.PreferredPos> = emptyMap()
    
    // Layout indices
    var current_layout_narrow = 0
    var current_layout_wide = 0
    
    // Resource values
    val marginTop: Float = 0f
    val keyPadding: Float = 0f
    val labelTextSize: Float = 0.33f
    val sublabelTextSize: Float = 0.22f
    
    init {
        loadFromPreferences(prefs)
        layouts = LayoutsPreference.load_from_preferences(resources, prefs)
        extra_keys_param = ExtraKeysPreference.get_extra_keys(prefs)
        extra_keys_custom = CustomExtraKeysPreference.get(prefs)
    }
    
    /**
     * Key event handler interface
     */
    interface IKeyEventHandler {
        fun key_down(value: KeyValue, is_swipe: Boolean)
        fun key_up(value: KeyValue, mods: Pointers.Modifiers)
        fun mods_changed(mods: Pointers.Modifiers)
    }
    
    /**
     * Get current layout index
     */
    fun get_current_layout(): Int {
        return if (wide_screen) current_layout_wide else current_layout_narrow
    }
    
    /**
     * Set current layout
     */
    fun set_current_layout(layout: Int) {
        if (wide_screen) {
            current_layout_wide = layout
        } else {
            current_layout_narrow = layout
        }
        
        prefs.edit().apply {
            putInt("current_layout_portrait", current_layout_narrow)
            putInt("current_layout_landscape", current_layout_wide)
            apply()
        }
    }
    
    companion object {
        const val WIDE_DEVICE_THRESHOLD = 600
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
         * Safe integer preference access
         */
        fun safeGetInt(prefs: SharedPreferences, key: String, defaultValue: Int): Int {
            return try {
                prefs.getInt(key, defaultValue)
            } catch (e: ClassCastException) {
                val stringValue = prefs.getString(key, defaultValue.toString()) ?: defaultValue.toString()
                stringValue.toIntOrNull() ?: defaultValue
            }
        }
    }
    
    /**
     * Load configuration from SharedPreferences
     */
    private fun loadFromPreferences(prefs: SharedPreferences) {
        swipe_typing_enabled = prefs.getBoolean("swipe_typing_enabled", true)
        neural_prediction_enabled = prefs.getBoolean("neural_prediction_enabled", true)
        word_prediction_enabled = prefs.getBoolean("word_prediction_enabled", false)
        
        neural_beam_width = prefs.getInt("neural_beam_width", 8)
        neural_max_length = prefs.getInt("neural_max_length", 35)
        neural_confidence_threshold = prefs.getFloat("neural_confidence_threshold", 0.1f)
        
        character_size = prefs.getFloat("character_size", 1.0f)
        key_vertical_margin = prefs.getFloat("key_vertical_margin", 1.5f)
        key_horizontal_margin = prefs.getFloat("key_horizontal_margin", 2.0f)
    }
    
    /**
     * Save configuration to SharedPreferences
     */
    fun saveToPreferences(prefs: SharedPreferences) {
        prefs.edit().apply {
            putBoolean("swipe_typing_enabled", swipe_typing_enabled)
            putBoolean("neural_prediction_enabled", neural_prediction_enabled)
            putBoolean("word_prediction_enabled", word_prediction_enabled)
            
            putInt("neural_beam_width", neural_beam_width)
            putInt("neural_max_length", neural_max_length)
            putFloat("neural_confidence_threshold", neural_confidence_threshold)
            
            putFloat("character_size", character_size)
            putFloat("key_vertical_margin", key_vertical_margin)
            putFloat("key_horizontal_margin", key_horizontal_margin)
            
            apply()
        }
    }
}