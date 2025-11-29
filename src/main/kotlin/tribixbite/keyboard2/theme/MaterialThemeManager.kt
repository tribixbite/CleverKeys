package tribixbite.keyboard2.theme

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central manager for Material 3 theming in CleverKeys.
 *
 * Responsibilities:
 * - Manage theme configuration (dark mode, dynamic color, custom settings)
 * - Provide ColorScheme (Material 3) and KeyboardColorScheme (keyboard-specific)
 * - Support dynamic color (Material You) on Android 12+
 * - Reactive theme updates via StateFlow
 * - Persistence of theme settings
 *
 * Usage:
 * ```kotlin
 * val themeManager = MaterialThemeManager(context)
 * val colorScheme = themeManager.getColorScheme(darkTheme)
 * val keyboardColors = themeManager.getKeyboardColorScheme(darkTheme)
 * ```
 */
class MaterialThemeManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Custom theme manager integration
    private val customThemeManager = CustomThemeManager(context)

    // Reactive theme configuration
    private val _themeConfig = MutableStateFlow(loadThemeConfig())
    val themeConfig: StateFlow<ThemeConfig> = _themeConfig.asStateFlow()

    // Selected theme ID
    private val _selectedThemeId = MutableStateFlow(loadSelectedThemeId())
    val selectedThemeId: StateFlow<String> = _selectedThemeId.asStateFlow()

    companion object {
        private const val PREFS_NAME = "keyboard_theme"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_KEY_BORDER_RADIUS = "key_border_radius"
        private const val KEY_ENABLE_ANIMATIONS = "enable_animations"
        private const val KEY_THEME_VARIANT = "theme_variant"
        private const val KEY_SELECTED_THEME_ID = "selected_theme_id"

        // Default values
        private const val DEFAULT_KEY_BORDER_RADIUS = 12f
        private const val DEFAULT_ENABLE_ANIMATIONS = true
        private const val DEFAULT_THEME_ID = "default"
    }

    /**
     * Get Material 3 ColorScheme based on theme configuration.
     *
     * Supports:
     * - Dynamic color (Material You) on Android 12+ when enabled
     * - Custom light/dark color schemes with CleverKeys branding
     * - Fallback to standard Material 3 colors
     *
     * @param darkTheme Whether to use dark theme
     * @return Material 3 ColorScheme
     */
    fun getColorScheme(darkTheme: Boolean): ColorScheme {
        val config = _themeConfig.value

        return when {
            // Dynamic color (Material You) - Android 12+
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && config.useDynamicColor -> {
                if (darkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            }

            // Custom CleverKeys branded colors
            darkTheme -> createCleverKeysDarkColorScheme()
            else -> createCleverKeysLightColorScheme()
        }
    }

    /**
     * Get keyboard-specific color scheme based on selected theme.
     *
     * If a custom or predefined theme is selected, returns that theme's colors.
     * Otherwise, generates colors from Material 3 ColorScheme.
     *
     * @param darkTheme Whether to use dark theme (ignored for predefined themes)
     * @return KeyboardColorScheme with all keyboard-specific colors
     */
    fun getKeyboardColorScheme(darkTheme: Boolean): KeyboardColorScheme {
        val selectedId = _selectedThemeId.value

        // Check if a predefined or custom theme is selected
        if (selectedId != DEFAULT_THEME_ID) {
            customThemeManager.getThemeByIdAny(selectedId)?.let { theme ->
                return theme.colorScheme
            }
        }

        // Fall back to Material-based colors
        val materialColors = getColorScheme(darkTheme)

        return keyboardColorSchemeFromMaterial(
            primary = materialColors.primary,
            secondary = materialColors.secondary,
            isDark = darkTheme
        )
    }

    /**
     * Select a theme by ID (predefined or custom).
     *
     * @param themeId ID of theme to select
     * @return true if theme exists and was selected, false otherwise
     */
    fun selectTheme(themeId: String): Boolean {
        // Verify theme exists
        if (themeId != DEFAULT_THEME_ID && customThemeManager.getThemeByIdAny(themeId) == null) {
            return false
        }

        _selectedThemeId.value = themeId
        saveSelectedThemeId(themeId)
        return true
    }

    /**
     * Get currently selected theme info.
     *
     * @return ThemeInfo if a specific theme is selected, null for default
     */
    fun getSelectedTheme(): ThemeInfo? {
        val selectedId = _selectedThemeId.value
        return if (selectedId == DEFAULT_THEME_ID) {
            null
        } else {
            customThemeManager.getThemeByIdAny(selectedId)
        }
    }

    /**
     * Get all available themes (predefined + custom).
     *
     * @return Map of category to themes
     */
    fun getAllThemes(): Map<ThemeCategory, List<ThemeInfo>> {
        return customThemeManager.getAllThemes()
    }

    /**
     * Get custom theme manager for direct access to custom theme operations.
     *
     * @return CustomThemeManager instance
     */
    fun getCustomThemeManager(): CustomThemeManager {
        return customThemeManager
    }

    /**
     * Update theme configuration and persist changes.
     *
     * Triggers reactive update via StateFlow so all UI components
     * can observe and respond to theme changes.
     *
     * @param config New theme configuration
     */
    fun updateTheme(config: ThemeConfig) {
        _themeConfig.value = config
        saveThemeConfig(config)
    }

    /**
     * Toggle dark mode on/off.
     */
    fun toggleDarkMode() {
        updateTheme(_themeConfig.value.copy(darkMode = !_themeConfig.value.darkMode))
    }

    /**
     * Toggle dynamic color (Material You) on/off.
     *
     * Only effective on Android 12+.
     */
    fun toggleDynamicColor() {
        updateTheme(_themeConfig.value.copy(useDynamicColor = !_themeConfig.value.useDynamicColor))
    }

    /**
     * Update key border radius (allows runtime customization).
     *
     * @param radius Corner radius in dp (typically 4-24dp)
     */
    fun setKeyBorderRadius(radius: Float) {
        updateTheme(_themeConfig.value.copy(keyBorderRadius = radius))
    }

    /**
     * Toggle animations on/off (accessibility/performance).
     */
    fun toggleAnimations() {
        updateTheme(_themeConfig.value.copy(enableAnimations = !_themeConfig.value.enableAnimations))
    }

    /**
     * Load theme configuration from SharedPreferences.
     */
    private fun loadThemeConfig(): ThemeConfig {
        return ThemeConfig(
            darkMode = prefs.getBoolean(KEY_DARK_MODE, false),
            useDynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            keyBorderRadius = prefs.getFloat(KEY_KEY_BORDER_RADIUS, DEFAULT_KEY_BORDER_RADIUS),
            enableAnimations = prefs.getBoolean(KEY_ENABLE_ANIMATIONS, DEFAULT_ENABLE_ANIMATIONS),
            themeVariant = ThemeVariant.valueOf(
                prefs.getString(KEY_THEME_VARIANT, ThemeVariant.DEFAULT.name)
                    ?: ThemeVariant.DEFAULT.name
            )
        )
    }

    /**
     * Save theme configuration to SharedPreferences.
     */
    private fun saveThemeConfig(config: ThemeConfig) {
        prefs.edit().apply {
            putBoolean(KEY_DARK_MODE, config.darkMode)
            putBoolean(KEY_DYNAMIC_COLOR, config.useDynamicColor)
            putFloat(KEY_KEY_BORDER_RADIUS, config.keyBorderRadius)
            putBoolean(KEY_ENABLE_ANIMATIONS, config.enableAnimations)
            putString(KEY_THEME_VARIANT, config.themeVariant.name)
            apply()
        }
    }

    /**
     * Load selected theme ID from SharedPreferences.
     */
    private fun loadSelectedThemeId(): String {
        return prefs.getString(KEY_SELECTED_THEME_ID, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID
    }

    /**
     * Save selected theme ID to SharedPreferences.
     */
    private fun saveSelectedThemeId(themeId: String) {
        prefs.edit()
            .putString(KEY_SELECTED_THEME_ID, themeId)
            .apply()
    }

    /**
     * Create CleverKeys branded light color scheme.
     */
    private fun createCleverKeysLightColorScheme(): ColorScheme {
        return lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF1976D2),       // Blue 700
            onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFBBDEFB), // Blue 100
            onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001D36),

            secondary = androidx.compose.ui.graphics.Color(0xFF424242),     // Grey 800
            onSecondary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E0E0), // Grey 300
            onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF1A1C1E),

            tertiary = androidx.compose.ui.graphics.Color(0xFF00ACC1),      // Cyan 600
            onTertiary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFB2EBF2), // Cyan 100
            onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF001F24),

            error = androidx.compose.ui.graphics.Color(0xFFD32F2F),         // Red 700
            onError = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            errorContainer = androidx.compose.ui.graphics.Color(0xFFFFCDD2),   // Red 100
            onErrorContainer = androidx.compose.ui.graphics.Color(0xFF410002),

            background = androidx.compose.ui.graphics.Color(0xFFFAFAFA),    // Grey 50
            onBackground = androidx.compose.ui.graphics.Color(0xFF1A1C1E),

            surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            onSurface = androidx.compose.ui.graphics.Color(0xFF1A1C1E),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF5F5F5),   // Grey 100
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF44474E),

            outline = androidx.compose.ui.graphics.Color(0xFFBDBDBD),          // Grey 400
            outlineVariant = androidx.compose.ui.graphics.Color(0xFFE0E0E0)    // Grey 300
        )
    }

    /**
     * Create CleverKeys branded dark color scheme.
     */
    private fun createCleverKeysDarkColorScheme(): ColorScheme {
        return darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF64B5F6),       // Blue 300
            onPrimary = androidx.compose.ui.graphics.Color(0xFF003258),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFF004A77),
            onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFBBDEFB),

            secondary = androidx.compose.ui.graphics.Color(0xFFB0B0B0),     // Grey 400
            onSecondary = androidx.compose.ui.graphics.Color(0xFF2E3134),
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFF44474E),
            onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E0E0),

            tertiary = androidx.compose.ui.graphics.Color(0xFF4DD0E1),      // Cyan 300
            onTertiary = androidx.compose.ui.graphics.Color(0xFF00363D),
            tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF004F58),
            onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFB2EBF2),

            error = androidx.compose.ui.graphics.Color(0xFFEF5350),         // Red 400
            onError = androidx.compose.ui.graphics.Color(0xFF690005),
            errorContainer = androidx.compose.ui.graphics.Color(0xFF93000A),
            onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFCDD2),

            background = androidx.compose.ui.graphics.Color(0xFF121212),    // Almost black
            onBackground = androidx.compose.ui.graphics.Color(0xFFE0E0E0),

            surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),       // Dark grey
            onSurface = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2C2C2C),
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC4C6CF),

            outline = androidx.compose.ui.graphics.Color(0xFF424242),          // Grey 800
            outlineVariant = androidx.compose.ui.graphics.Color(0xFF44474E)
        )
    }
}

/**
 * Theme configuration data class.
 *
 * Holds all user-customizable theme settings.
 */
data class ThemeConfig(
    val darkMode: Boolean = false,
    val useDynamicColor: Boolean = true,
    val keyBorderRadius: Float = 12f,
    val enableAnimations: Boolean = true,
    val themeVariant: ThemeVariant = ThemeVariant.DEFAULT
)

/**
 * Theme variant options - DEPRECATED.
 * Use ThemeCategory and PredefinedThemes instead.
 */
@Deprecated("Use ThemeCategory and theme selection from PredefinedThemes")
enum class ThemeVariant {
    DEFAULT,        // CleverKeys branded colors
    HIGH_CONTRAST,  // Enhanced contrast for accessibility
    COLORFUL,       // More vibrant colors
    MINIMAL         // Monochrome minimal theme
}

/**
 * Current selected theme ID storage.
 */
data class ThemeSelection(
    val themeId: String = "default",  // ID of selected theme (predefined or custom)
    val isDarkMode: Boolean = false    // Whether dark mode is enabled (for light/dark variants)
)
