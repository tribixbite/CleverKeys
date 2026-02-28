package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.Log

/**
 * Manages keyboard configuration and notifies listeners of changes.
 *
 * This class centralizes configuration management and uses the observer pattern
 * to decouple config refresh from config propagation. Components that need to
 * respond to config changes should implement ConfigChangeListener.
 *
 * Responsibilities:
 * - Own and manage the Config instance
 * - Own and manage the FoldStateTracker
 * - Listen to SharedPreferences changes
 * - Refresh config when needed
 * - Notify registered listeners of changes
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.345).
 */
class ConfigurationManager(
    private val context: Context,
    private val config: Config,
    private val foldStateTracker: FoldStateTracker
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val listeners = mutableListOf<ConfigChangeListener>()

    init {
        // Set up fold state callback to refresh config when device is folded/unfolded
        foldStateTracker.setChangedCallback {
            refresh(context.resources)
        }
    }

    /**
     * Gets the current Config instance.
     *
     * @return Current Config object (never null)
     */
    fun getConfig(): Config = config

    /**
     * Gets the FoldStateTracker instance.
     *
     * @return FoldStateTracker for monitoring device fold state
     */
    fun getFoldStateTracker(): FoldStateTracker = foldStateTracker

    /**
     * Registers a listener for config changes.
     *
     * @param listener ConfigChangeListener to be notified of changes
     */
    fun registerConfigChangeListener(listener: ConfigChangeListener?) {
        if (listener != null && listener !in listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @param listener ConfigChangeListener to remove
     */
    fun unregisterConfigChangeListener(listener: ConfigChangeListener?) {
        listeners.remove(listener)
    }

    /**
     * Refreshes the configuration from SharedPreferences.
     *
     * This method:
     * 1. Captures the previous theme
     * 2. Refreshes the Config object
     * 3. Notifies all registered listeners
     * 4. Sends theme change notification if theme changed
     *
     * @param res Resources for config loading
     */
    fun refresh(res: Resources) {
        val prevTheme = config.theme
        val prevThemeName = config.themeName

        // Refresh config from SharedPreferences
        try {
            config.refresh(res, foldStateTracker.isUnfolded())
        } catch (t: Throwable) {
            Log.e(TAG, "Config refresh failed", t)
            return // Don't notify listeners if config refresh itself failed
        }

        // Notify listeners of config change
        // Catch Throwable (not just Exception) to prevent OOM/Error from killing IME process
        for (listener in listeners) {
            try {
                listener.onConfigChanged(config)
            } catch (t: Throwable) {
                Log.e(TAG, "Config listener ${listener.javaClass.simpleName} failed", t)
            }
        }

        // Special notification for theme changes (requires view recreation)
        // Check both theme (style ID) and themeName (for runtime themes that share base style)
        val themeChanged = prevTheme != config.theme || prevThemeName != config.themeName
        if (themeChanged) {
            for (listener in listeners) {
                try {
                    listener.onThemeChanged(prevTheme, config.theme)
                } catch (t: Throwable) {
                    Log.e(TAG, "Theme change listener ${listener.javaClass.simpleName} failed", t)
                }
            }
        }
    }

    /**
     * Called when a SharedPreference changes.
     *
     * This is the entry point for all preference changes. It triggers
     * a config refresh which will notify all registered listeners.
     *
     * @param prefs SharedPreferences that changed
     * @param key Key of the preference that changed (may be null)
     */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        // Refresh config and notify listeners
        try {
            refresh(context.resources)
        } catch (t: Throwable) {
            Log.e(TAG, "onSharedPreferenceChanged failed for key=$key", t)
        }
    }

    /**
     * Gets a debug string showing current state.
     * Useful for logging and troubleshooting.
     *
     * @return Human-readable state description
     */
    fun getDebugState(): String {
        return "ConfigurationManager{theme=${config.theme}, listeners=${listeners.size}, isUnfolded=${foldStateTracker.isUnfolded()}}"
    }

    companion object {
        private const val TAG = "ConfigurationManager"
    }
}
