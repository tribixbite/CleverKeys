package tribixbite.keyboard2

import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Neural prediction configuration with Kotlin property delegation
 * Automatically syncs with SharedPreferences for persistence
 */
class NeuralConfig(private val prefs: SharedPreferences) {
    
    // Preference delegation for automatic persistence
    var neuralPredictionEnabled: Boolean by BooleanPreference("neural_prediction_enabled", true)
    var beamWidth: Int by IntPreference("neural_beam_width", 8)
    var maxLength: Int by IntPreference("neural_max_length", 35)
    var confidenceThreshold: Float by FloatPreference("neural_confidence_threshold", 0.1f)
    
    // Validation ranges
    val beamWidthRange = 1..16
    val maxLengthRange = 10..50
    val confidenceRange = 0.0f..1.0f
    
    /**
     * Validate and clamp values to acceptable ranges
     */
    fun validate() {
        beamWidth = beamWidth.coerceIn(beamWidthRange)
        maxLength = maxLength.coerceIn(maxLengthRange)  
        confidenceThreshold = confidenceThreshold.coerceIn(confidenceRange)
    }
    
    /**
     * Reset to defaults
     */
    fun resetToDefaults() {
        neuralPredictionEnabled = true
        beamWidth = 8
        maxLength = 35
        confidenceThreshold = 0.1f
    }
    
    /**
     * Copy current settings
     */
    fun copy(): NeuralConfig {
        val copy = NeuralConfig(prefs)
        copy.neuralPredictionEnabled = this.neuralPredictionEnabled
        copy.beamWidth = this.beamWidth
        copy.maxLength = this.maxLength
        copy.confidenceThreshold = this.confidenceThreshold
        return copy
    }
    
    // Property delegation classes for automatic SharedPreferences sync
    private inner class BooleanPreference(
        private val key: String, 
        private val defaultValue: Boolean
    ) : ReadWriteProperty<Any?, Boolean> {
        
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
            return prefs.getBoolean(key, defaultValue)
        }
        
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }
    
    private inner class IntPreference(
        private val key: String,
        private val defaultValue: Int
    ) : ReadWriteProperty<Any?, Int> {
        
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            return prefs.getInt(key, defaultValue)
        }
        
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }
    }
    
    private inner class FloatPreference(
        private val key: String,
        private val defaultValue: Float
    ) : ReadWriteProperty<Any?, Float> {
        
        override fun getValue(thisRef: Any?, property: KProperty<*>): Float {
            return prefs.getFloat(key, defaultValue)
        }
        
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
            prefs.edit().putFloat(key, value).apply()
        }
    }
}