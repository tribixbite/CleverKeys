package tribixbite.keyboard2

/**
 * Listener interface for configuration changes.
 *
 * Components that need to respond to config changes should implement this interface
 * and register with ConfigurationManager.
 *
 * This interface is part of the observer pattern used to decouple config management
 * from config propagation (v1.32.345).
 */
interface ConfigChangeListener {
    /**
     * Called when the configuration has been refreshed.
     *
     * @param newConfig The updated Config instance
     */
    fun onConfigChanged(newConfig: Config)

    /**
     * Called when the theme has changed.
     * This is a special case that requires re-creating views.
     *
     * @param oldTheme Previous theme ID
     * @param newTheme New theme ID
     */
    fun onThemeChanged(oldTheme: Int, newTheme: Int)
}
