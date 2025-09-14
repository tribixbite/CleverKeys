package juloo.keyboard2

import android.content.SharedPreferences
import android.content.res.Resources

/**
 * Global keyboard configuration with Kotlin improvements
 * Simplified version focusing on essential features
 */
class Config private constructor() {
    
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
    
    // Display settings
    var orientation_landscape = false
    var wide_screen = false
    
    companion object {
        private var globalConfig: Config? = null
        
        /**
         * Initialize global configuration
         */
        fun initGlobalConfig(
            prefs: SharedPreferences, 
            resources: Resources, 
            extraKeys: Any?, 
            orientation: Boolean
        ) {
            globalConfig = Config().apply {
                loadFromPreferences(prefs)
            }
        }
        
        /**
         * Get global configuration instance
         */
        fun globalConfig(): Config {
            return globalConfig ?: throw IllegalStateException("Config not initialized")
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