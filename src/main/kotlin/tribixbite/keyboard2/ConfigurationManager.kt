package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Sophisticated configuration management with reactive persistence
 * Kotlin implementation with automatic sync and validation
 */
class ConfigurationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ConfigurationManager"
        private const val CONFIG_VERSION = 4
        private const val MIGRATION_PREF_KEY = "config_version"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
    
    // Configuration state flows for reactive updates
    private val configChanges = MutableSharedFlow<ConfigChange>()
    private val migrationFlow = MutableSharedFlow<MigrationResult>()
    
    /**
     * Configuration change event
     */
    data class ConfigChange(
        val key: String,
        val oldValue: Any?,
        val newValue: Any?,
        val source: String
    )
    
    /**
     * Migration result
     */
    data class MigrationResult(
        val fromVersion: Int,
        val toVersion: Int,
        val migratedKeys: List<String>,
        val success: Boolean
    )
    
    /**
     * Initialize configuration system with migration
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentVersion = prefs.getInt(MIGRATION_PREF_KEY, 0)
            
            if (currentVersion < CONFIG_VERSION) {
                logD("Migrating configuration: $currentVersion → $CONFIG_VERSION")
                val migrationResult = performMigration(currentVersion, CONFIG_VERSION)
                migrationFlow.emit(migrationResult)
                
                if (!migrationResult.success) {
                    logE("Configuration migration failed")
                    return@withContext false
                }
            }
            
            // Start configuration monitoring
            startConfigurationMonitoring()
            
            logD("Configuration manager initialized successfully")
            true
        } catch (e: Exception) {
            logE("Configuration initialization failed", e)
            false
        }
    }
    
    /**
     * Perform configuration migration
     */
    private suspend fun performMigration(fromVersion: Int, toVersion: Int): MigrationResult = withContext(Dispatchers.IO) {
        val migratedKeys = mutableListOf<String>()
        
        try {
            val editor = prefs.edit()
            
            when (fromVersion) {
                0 -> {
                    // Initial migration - set defaults
                    migrateToVersion1(editor, migratedKeys)
                }
                1 -> {
                    // Neural prediction features added
                    migrateToVersion2(editor, migratedKeys)
                }
                2 -> {
                    // Advanced gesture recognition
                    migrateToVersion3(editor, migratedKeys)
                }
                3 -> {
                    // Performance optimizations
                    migrateToVersion4(editor, migratedKeys)
                }
            }
            
            // Update version
            editor.putInt(MIGRATION_PREF_KEY, toVersion)
            editor.apply()
            
            logD("Migration completed: ${migratedKeys.size} keys migrated")
            MigrationResult(fromVersion, toVersion, migratedKeys, true)
            
        } catch (e: Exception) {
            logE("Migration failed", e)
            MigrationResult(fromVersion, toVersion, migratedKeys, false)
        }
    }
    
    /**
     * Migration to version 1
     */
    private fun migrateToVersion1(editor: SharedPreferences.Editor, migratedKeys: MutableList<String>) {
        // Set initial defaults
        editor.putBoolean("swipe_typing_enabled", true)
        editor.putBoolean("neural_prediction_enabled", true)
        editor.putInt("neural_beam_width", 8)
        editor.putInt("neural_max_length", 35)
        editor.putFloat("neural_confidence_threshold", 0.1f)
        
        migratedKeys.addAll(listOf(
            "swipe_typing_enabled", "neural_prediction_enabled", 
            "neural_beam_width", "neural_max_length", "neural_confidence_threshold"
        ))
    }
    
    /**
     * Migration to version 2
     */
    private fun migrateToVersion2(editor: SharedPreferences.Editor, migratedKeys: MutableList<String>) {
        // Add gesture recognition settings
        editor.putBoolean("advanced_gesture_recognition", true)
        editor.putBoolean("continuous_prediction", false)
        editor.putInt("prediction_update_interval", 100)
        
        migratedKeys.addAll(listOf(
            "advanced_gesture_recognition", "continuous_prediction", "prediction_update_interval"
        ))
    }
    
    /**
     * Migration to version 3
     */
    private fun migrateToVersion3(editor: SharedPreferences.Editor, migratedKeys: MutableList<String>) {
        // Add performance settings
        editor.putBoolean("performance_monitoring", false)
        editor.putBoolean("batched_inference", true)
        editor.putInt("tensor_memory_pool_size", 20)
        
        migratedKeys.addAll(listOf(
            "performance_monitoring", "batched_inference", "tensor_memory_pool_size"
        ))
    }
    
    /**
     * Migration to version 4
     */
    private fun migrateToVersion4(editor: SharedPreferences.Editor, migratedKeys: MutableList<String>) {
        // Add advanced features
        editor.putBoolean("accessibility_enhanced", true)
        editor.putBoolean("voice_input_integration", false)
        editor.putString("prediction_strategy", "hybrid")
        
        migratedKeys.addAll(listOf(
            "accessibility_enhanced", "voice_input_integration", "prediction_strategy"
        ))
    }
    
    /**
     * Start monitoring configuration changes
     */
    private fun startConfigurationMonitoring() {
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            scope.launch {
                handleConfigurationChange(key)
            }
        }
    }
    
    /**
     * Handle configuration change
     */
    private suspend fun handleConfigurationChange(key: String?) {
        if (key == null) return
        
        try {
            // Get old and new values for comparison
            val newValue = getPreferenceValue(key)
            
            // Emit configuration change event
            configChanges.emit(ConfigChange(key, null, newValue, "user"))
            
            // Handle specific configuration changes with actual propagation
            when (key) {
                "neural_beam_width", "neural_max_length", "neural_confidence_threshold" -> {
                    // Propagate neural configuration changes to active engine
                    notifyNeuralConfigChange(key, newValue)
                }
                "theme" -> {
                    // Propagate theme changes to all UI components
                    notifyThemeChange()
                }
                "keyboard_height", "keyboard_height_landscape" -> {
                    // Propagate layout changes to keyboard views
                    notifyLayoutChange(key, newValue)
                }
                "swipe_typing_enabled" -> {
                    // Propagate neural prediction state changes
                    notifyNeuralStateChange(newValue as? Boolean ?: false)
                }
            }
            
        } catch (e: Exception) {
            logE("Error handling configuration change for key: $key", e)
        }
    }
    
    /**
     * Get preference value by key
     */
    private fun getPreferenceValue(key: String): Any? {
        return try {
            when {
                key.endsWith("_enabled") -> prefs.getBoolean(key, false)
                key.startsWith("neural_") && (key.endsWith("_width") || key.endsWith("_length")) -> prefs.getInt(key, 0)
                key.contains("threshold") || key.contains("confidence") -> prefs.getFloat(key, 0f)
                else -> prefs.getString(key, "")
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // Component registry for propagation
    private val neuralEngineInstances = mutableListOf<NeuralSwipeEngine>()
    private val keyboardViewInstances = mutableListOf<Keyboard2View>()
    private val uiComponentInstances = mutableListOf<android.view.View>()

    /**
     * Register neural engine for configuration updates
     */
    fun registerNeuralEngine(engine: NeuralSwipeEngine) {
        neuralEngineInstances.add(engine)
    }

    /**
     * Register keyboard view for configuration updates
     */
    fun registerKeyboardView(view: Keyboard2View) {
        keyboardViewInstances.add(view)
    }

    /**
     * Register UI component for theme updates
     */
    fun registerUIComponent(view: android.view.View) {
        uiComponentInstances.add(view)
    }

    /**
     * Notify neural engine of configuration changes
     */
    private suspend fun notifyNeuralConfigChange(key: String, newValue: Any?) {
        logD("Neural configuration changed: $key = $newValue")

        neuralEngineInstances.forEach { engine ->
            try {
                // Update engine configuration
                val config = Config.globalConfig()
                engine.setConfig(config)
                logD("Updated neural engine configuration for $key")
            } catch (e: Exception) {
                logE("Failed to update neural engine configuration", e)
            }
        }
    }

    /**
     * Notify theme change to all UI components
     */
    private suspend fun notifyThemeChange() {
        logD("Theme configuration changed - updating all UI components")

        val theme = Theme.getSystemThemeData(context)

        // Update keyboard views
        keyboardViewInstances.forEach { view: Keyboard2View ->
            try {
                view.invalidate()  // Request redraw with new theme
                logD("Updated keyboard view theme")
            } catch (e: Exception) {
                logE("Failed to update keyboard view theme", e)
            }
        }

        // Update all registered UI components
        uiComponentInstances.forEach { view: android.view.View ->
            try {
                // Apply theme to view
                applyThemeToView(view, theme)
                logD("Updated UI component theme")
            } catch (e: Exception) {
                logE("Failed to update UI component theme", e)
            }
        }
    }

    /**
     * Apply theme properties to a view
     */
    private fun applyThemeToView(view: android.view.View, theme: Theme.ThemeData) {
        // Apply basic theme properties
        view.setBackgroundColor(theme.backgroundColor)

        // Apply theme recursively to child views if it's a ViewGroup
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)

                when (child) {
                    is android.widget.TextView -> {
                        child.setTextColor(theme.labelColor)
                        child.setTypeface(Theme.getKeyFont(context))
                    }
                    is android.widget.Button -> {
                        child.setTextColor(theme.labelColor)
                        child.setBackgroundColor(theme.keyColor)
                    }
                    is android.widget.EditText -> {
                        child.setTextColor(theme.labelColor)
                        child.setHintTextColor(theme.suggestionTextColor)
                    }
                }

                // Recursively apply to nested ViewGroups
                if (child is android.view.ViewGroup) {
                    applyThemeToView(child, theme)
                }
            }
        }

        // Request invalidate to redraw with new theme
        view.invalidate()
    }

    /**
     * Notify layout change to keyboard views
     */
    private suspend fun notifyLayoutChange(key: String, newValue: Any?) {
        logD("Layout configuration changed: $key = $newValue")

        keyboardViewInstances.forEach { view: Keyboard2View ->
            try {
                view.requestLayout()
                logD("Updated keyboard view layout for $key")
            } catch (e: Exception) {
                logE("Failed to update keyboard view layout", e)
            }
        }
    }

    /**
     * Notify neural prediction state change
     */
    private suspend fun notifyNeuralStateChange(enabled: Boolean) {
        logD("Neural prediction state changed: $enabled")

        if (enabled) {
            // Initialize neural engines if not already done
            neuralEngineInstances.forEach { engine ->
                try {
                    if (!engine.isReady) {
                        engine.initialize()
                        logD("Initialized neural engine due to state change")
                    }
                } catch (e: Exception) {
                    logE("Failed to initialize neural engine", e)
                }
            }
        } else {
            // Cleanup neural engines if disabled
            neuralEngineInstances.forEach { engine ->
                try {
                    engine.cleanup()
                    logD("Cleaned up neural engine due to state change")
                } catch (e: Exception) {
                    logE("Failed to cleanup neural engine", e)
                }
            }
        }
    }
    
    /**
     * Get configuration changes flow
     */
    fun getConfigChangesFlow(): Flow<ConfigChange> = configChanges.asSharedFlow()
    
    /**
     * Get migration results flow
     */
    fun getMigrationFlow(): Flow<MigrationResult> = migrationFlow.asSharedFlow()
    
    /**
     * Export configuration
     */
    suspend fun exportConfiguration(): String = withContext(Dispatchers.IO) {
        val config = mutableMapOf<String, Any?>()
        
        prefs.all.forEach { (key, value) ->
            config[key] = value
        }
        
        // Convert to JSON
        val json = org.json.JSONObject()
        config.forEach { (key, value) ->
            json.put(key, value)
        }
        
        json.toString(2)
    }
    
    /**
     * Import configuration
     */
    suspend fun importConfiguration(configJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = org.json.JSONObject(configJson)
            val editor = prefs.edit()
            
            json.keys().forEach { key ->
                val value = json.get(key)
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                }
            }
            
            editor.apply()
            logD("Configuration imported successfully")
            true
        } catch (e: Exception) {
            logE("Configuration import failed", e)
            false
        }
    }
    
    /**
     * Reset configuration to defaults
     */
    suspend fun resetToDefaults(): Boolean = withContext(Dispatchers.IO) {
        try {
            val editor = prefs.edit()
            editor.clear()
            
            // Perform fresh migration to set defaults
            migrateToVersion1(editor, mutableListOf())
            migrateToVersion2(editor, mutableListOf())
            migrateToVersion3(editor, mutableListOf())
            migrateToVersion4(editor, mutableListOf())
            
            editor.putInt(MIGRATION_PREF_KEY, CONFIG_VERSION)
            editor.apply()
            
            logD("Configuration reset to defaults")
            true
        } catch (e: Exception) {
            logE("Failed to reset configuration", e)
            false
        }
    }
    
    /**
     * Validate current configuration
     */
    fun validateConfiguration(): ErrorHandling.ValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate neural settings
        val beamWidth = prefs.getInt("neural_beam_width", 8)
        if (beamWidth !in 1..16) {
            errors.add("Invalid beam width: $beamWidth")
        }
        
        val maxLength = prefs.getInt("neural_max_length", 35)
        if (maxLength !in 10..50) {
            errors.add("Invalid max length: $maxLength")
        }
        
        val threshold = prefs.getFloat("neural_confidence_threshold", 0.1f)
        if (threshold !in 0f..1f) {
            errors.add("Invalid confidence threshold: $threshold")
        }
        
        return ErrorHandling.ValidationResult(errors.isEmpty(), errors)
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
    }
}