package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences

/**
 * Helper to initialize Config for instrumented tests.
 * Provides a real Config instance without requiring the full keyboard service.
 */
object TestConfigHelper {

    private var initialized = false

    /**
     * Initialize Config.globalConfig() for testing.
     * Safe to call multiple times - only initializes once.
     *
     * @return true if Config is now available, false if initialization failed
     */
    @Synchronized
    fun ensureConfigInitialized(context: Context): Boolean {
        if (initialized) return true

        return try {
            // Check if already initialized by another test
            try {
                Config.globalConfig()
                initialized = true
                return true
            } catch (e: NullPointerException) {
                // Not initialized yet, continue
            }

            // Create test SharedPreferences
            val prefs = context.getSharedPreferences(
                "cleverkeys_test_prefs",
                Context.MODE_PRIVATE
            )

            // Initialize with defaults
            initializeDefaultPrefs(prefs)

            // Initialize Config
            Config.initGlobalConfig(
                prefs = prefs,
                res = context.resources,
                handler = null,  // No key event handler needed for tests
                foldableUnfolded = null
            )

            initialized = true
            true
        } catch (e: Exception) {
            android.util.Log.e("TestConfigHelper", "Failed to initialize Config", e)
            false
        }
    }

    /**
     * Set default preferences to ensure Config works correctly.
     */
    private fun initializeDefaultPrefs(prefs: SharedPreferences) {
        prefs.edit().apply {
            // Essential defaults
            putBoolean("swipe_typing_enabled", true)
            putBoolean("haptic_enabled", true)
            putBoolean("autocapitalisation", true)
            putBoolean("autocorrect_enabled", true)
            putBoolean("word_prediction_enabled", true)
            putBoolean("swipe_trail_enabled", true)

            // Swipe settings
            putInt("swipe_min_distance", 20)
            putFloat("swipe_trail_width", 8f)

            // Neural settings
            putInt("neural_beam_width", 5)
            putInt("neural_max_length", 15)
            putFloat("neural_confidence_threshold", 0.3f)

            // Tap settings
            putInt("tap_duration_threshold", 200)

            // Longpress settings
            putInt("longpress_timeout", 300)
            putInt("longpress_interval", 100)

            // Layout settings
            putInt("keyboard_height_percent", 35)
            putInt("margin_top", 0)
            putInt("margin_bottom", 0)
            putInt("margin_left", 0)
            putInt("margin_right", 0)

            // Language
            putString("primary_language", "en")
            putBoolean("auto_detect_language", false)

            // Theme
            putInt("theme", 0)

            // Short gestures
            putBoolean("short_gestures_enabled", true)
            putInt("short_gesture_min_distance", 10)

            // Suggestion bar
            putInt("suggestion_bar_opacity", 100)

            apply()
        }
    }

    /**
     * Get Config if available, null otherwise.
     */
    fun getConfigOrNull(context: Context): Config? {
        return if (ensureConfigInitialized(context)) {
            try {
                Config.globalConfig()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
}
