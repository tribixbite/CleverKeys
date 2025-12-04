package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log

/**
 * Manages dark mode theme for keyboard UI.
 *
 * Provides automatic dark mode detection from system settings, manual theme control,
 * and smooth theme transitions with customizable color schemes.
 *
 * Features:
 * - Automatic dark mode detection from system settings
 * - Manual theme override (light/dark/auto)
 * - Multiple dark theme variants (pure black, dark gray, blue-tinted)
 * - Smooth theme transition animations
 * - Custom color scheme support
 * - OLED-optimized pure black mode
 * - Battery-saving considerations
 *
 * Bug #334 - MEDIUM: Complete implementation of missing DarkModeManager.java
 *
 * @param context Application context for accessing resources
 */
class DarkModeManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "DarkModeManager"

        // Color scheme presets - Light Mode
        private const val LIGHT_BACKGROUND = 0xFFF5F5F5.toInt()       // Very light gray
        private const val LIGHT_KEY_BACKGROUND = 0xFFFFFFFF.toInt()   // White
        private const val LIGHT_KEY_PRESSED = 0xFFE0E0E0.toInt()      // Light gray
        private const val LIGHT_TEXT_PRIMARY = 0xFF000000.toInt()     // Black
        private const val LIGHT_TEXT_SECONDARY = 0xFF666666.toInt()   // Dark gray
        private const val LIGHT_ACCENT = 0xFF2196F3.toInt()           // Material Blue

        // Color scheme presets - Dark Mode
        private const val DARK_BACKGROUND = 0xFF212121.toInt()        // Dark gray
        private const val DARK_KEY_BACKGROUND = 0xFF424242.toInt()    // Medium gray
        private const val DARK_KEY_PRESSED = 0xFF616161.toInt()       // Light gray
        private const val DARK_TEXT_PRIMARY = 0xFFFFFFFF.toInt()      // White
        private const val DARK_TEXT_SECONDARY = 0xFFBDBDBD.toInt()    // Light gray
        private const val DARK_ACCENT = 0xFF64B5F6.toInt()            // Light blue

        // Color scheme presets - Pure Black (OLED)
        private const val BLACK_BACKGROUND = 0xFF000000.toInt()       // Pure black
        private const val BLACK_KEY_BACKGROUND = 0xFF1A1A1A.toInt()   // Very dark gray
        private const val BLACK_KEY_PRESSED = 0xFF333333.toInt()      // Dark gray
        private const val BLACK_TEXT_PRIMARY = 0xFFFFFFFF.toInt()     // White
        private const val BLACK_TEXT_SECONDARY = 0xFF999999.toInt()   // Medium gray
        private const val BLACK_ACCENT = 0xFF82B1FF.toInt()           // Light blue

        // Color scheme presets - Blue Tinted Dark
        private const val BLUE_BACKGROUND = 0xFF0D1117.toInt()        // Very dark blue-gray
        private const val BLUE_KEY_BACKGROUND = 0xFF161B22.toInt()    // Dark blue-gray
        private const val BLUE_KEY_PRESSED = 0xFF21262D.toInt()       // Medium blue-gray
        private const val BLUE_TEXT_PRIMARY = 0xFFC9D1D9.toInt()      // Light blue-gray
        private const val BLUE_TEXT_SECONDARY = 0xFF8B949E.toInt()    // Medium blue-gray
        private const val BLUE_ACCENT = 0xFF58A6FF.toInt()            // Bright blue

        // Transition animation duration
        private const val TRANSITION_DURATION_MS = 200L
    }

    /**
     * Theme mode setting.
     */
    enum class ThemeMode {
        LIGHT,      // Always light theme
        DARK,       // Always dark theme
        AUTO        // Follow system setting
    }

    /**
     * Dark theme variant.
     */
    enum class DarkVariant {
        STANDARD,   // Standard dark gray
        PURE_BLACK, // Pure black for OLED
        BLUE_TINT   // Blue-tinted dark
    }

    /**
     * Color scheme for keyboard theming.
     */
    data class ColorScheme(
        val background: Int,
        val keyBackground: Int,
        val keyPressed: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int
    )

    /**
     * Callback interface for theme change events.
     */
    interface Callback {
        /**
         * Called when theme changes.
         *
         * @param isDark true if dark theme is now active
         * @param colorScheme New color scheme
         */
        fun onThemeChanged(isDark: Boolean, colorScheme: ColorScheme)

        /**
         * Called when theme mode changes.
         *
         * @param mode New theme mode
         */
        fun onThemeModeChanged(mode: ThemeMode)

        /**
         * Called when dark variant changes.
         *
         * @param variant New dark variant
         */
        fun onDarkVariantChanged(variant: DarkVariant)
    }

    // Current state
    private var themeMode: ThemeMode = ThemeMode.AUTO
    private var darkVariant: DarkVariant = DarkVariant.STANDARD
    private var currentColorScheme: ColorScheme = getLightColorScheme()
    private var callback: Callback? = null

    // System dark mode detection
    private var systemDarkMode: Boolean = false

    init {
        logD("Initializing DarkModeManager")
        detectSystemTheme()
        updateTheme()
    }

    /**
     * Detect current system dark mode setting.
     */
    private fun detectSystemTheme() {
        val currentNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        systemDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        logD("System dark mode: $systemDarkMode")
    }

    /**
     * Update current theme based on mode and system settings.
     */
    private fun updateTheme() {
        val shouldUseDark = when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.AUTO -> systemDarkMode
        }

        currentColorScheme = if (shouldUseDark) {
            getDarkColorScheme(darkVariant)
        } else {
            getLightColorScheme()
        }

        logD("Theme updated: dark=$shouldUseDark, variant=$darkVariant")
    }

    /**
     * Get light theme color scheme.
     */
    private fun getLightColorScheme(): ColorScheme {
        return ColorScheme(
            background = LIGHT_BACKGROUND,
            keyBackground = LIGHT_KEY_BACKGROUND,
            keyPressed = LIGHT_KEY_PRESSED,
            textPrimary = LIGHT_TEXT_PRIMARY,
            textSecondary = LIGHT_TEXT_SECONDARY,
            accent = LIGHT_ACCENT
        )
    }

    /**
     * Get dark theme color scheme with specified variant.
     */
    private fun getDarkColorScheme(variant: DarkVariant): ColorScheme {
        return when (variant) {
            DarkVariant.STANDARD -> ColorScheme(
                background = DARK_BACKGROUND,
                keyBackground = DARK_KEY_BACKGROUND,
                keyPressed = DARK_KEY_PRESSED,
                textPrimary = DARK_TEXT_PRIMARY,
                textSecondary = DARK_TEXT_SECONDARY,
                accent = DARK_ACCENT
            )
            DarkVariant.PURE_BLACK -> ColorScheme(
                background = BLACK_BACKGROUND,
                keyBackground = BLACK_KEY_BACKGROUND,
                keyPressed = BLACK_KEY_PRESSED,
                textPrimary = BLACK_TEXT_PRIMARY,
                textSecondary = BLACK_TEXT_SECONDARY,
                accent = BLACK_ACCENT
            )
            DarkVariant.BLUE_TINT -> ColorScheme(
                background = BLUE_BACKGROUND,
                keyBackground = BLUE_KEY_BACKGROUND,
                keyPressed = BLUE_KEY_PRESSED,
                textPrimary = BLUE_TEXT_PRIMARY,
                textSecondary = BLUE_TEXT_SECONDARY,
                accent = BLUE_ACCENT
            )
        }
    }

    /**
     * Set theme mode.
     *
     * @param mode Theme mode to use
     */
    fun setThemeMode(mode: ThemeMode) {
        if (themeMode == mode) {
            return
        }

        themeMode = mode
        updateTheme()

        logD("Theme mode changed to: $mode")
        callback?.onThemeModeChanged(mode)
        callback?.onThemeChanged(isDarkMode(), currentColorScheme)
    }

    /**
     * Set dark theme variant.
     *
     * @param variant Dark theme variant
     */
    fun setDarkVariant(variant: DarkVariant) {
        if (darkVariant == variant) {
            return
        }

        darkVariant = variant
        updateTheme()

        logD("Dark variant changed to: $variant")
        callback?.onDarkVariantChanged(variant)

        if (isDarkMode()) {
            callback?.onThemeChanged(true, currentColorScheme)
        }
    }

    /**
     * Cycle through theme modes: AUTO → LIGHT → DARK → AUTO.
     */
    fun cycleThemeMode() {
        val nextMode = when (themeMode) {
            ThemeMode.AUTO -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.AUTO
        }
        setThemeMode(nextMode)
    }

    /**
     * Cycle through dark variants: STANDARD → PURE_BLACK → BLUE_TINT → STANDARD.
     */
    fun cycleDarkVariant() {
        val nextVariant = when (darkVariant) {
            DarkVariant.STANDARD -> DarkVariant.PURE_BLACK
            DarkVariant.PURE_BLACK -> DarkVariant.BLUE_TINT
            DarkVariant.BLUE_TINT -> DarkVariant.STANDARD
        }
        setDarkVariant(nextVariant)
    }

    /**
     * Set custom color scheme.
     *
     * @param colorScheme Custom color scheme to apply
     */
    fun setCustomColorScheme(colorScheme: ColorScheme) {
        currentColorScheme = colorScheme
        logD("Custom color scheme applied")
        callback?.onThemeChanged(isDarkMode(), currentColorScheme)
    }

    /**
     * Handle system configuration change (e.g., system dark mode toggled).
     *
     * @param newConfig New configuration
     */
    fun onConfigurationChanged(newConfig: Configuration) {
        val oldSystemDarkMode = systemDarkMode
        detectSystemTheme()

        if (systemDarkMode != oldSystemDarkMode && themeMode == ThemeMode.AUTO) {
            updateTheme()
            logD("System dark mode changed: $oldSystemDarkMode → $systemDarkMode")
            callback?.onThemeChanged(isDarkMode(), currentColorScheme)
        }
    }

    /**
     * Check if dark mode is currently active.
     *
     * @return true if dark theme is active
     */
    fun isDarkMode(): Boolean {
        return when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.AUTO -> systemDarkMode
        }
    }

    /**
     * Get current theme mode.
     *
     * @return Current theme mode
     */
    fun getThemeMode(): ThemeMode = themeMode

    /**
     * Get current dark variant.
     *
     * @return Current dark variant
     */
    fun getDarkVariant(): DarkVariant = darkVariant

    /**
     * Get current color scheme.
     *
     * @return Current color scheme
     */
    fun getColorScheme(): ColorScheme = currentColorScheme

    /**
     * Get background color for current theme.
     *
     * @return Background color
     */
    fun getBackgroundColor(): Int = currentColorScheme.background

    /**
     * Get key background color for current theme.
     *
     * @return Key background color
     */
    fun getKeyBackgroundColor(): Int = currentColorScheme.keyBackground

    /**
     * Get key pressed color for current theme.
     *
     * @return Key pressed color
     */
    fun getKeyPressedColor(): Int = currentColorScheme.keyPressed

    /**
     * Get primary text color for current theme.
     *
     * @return Primary text color
     */
    fun getTextPrimaryColor(): Int = currentColorScheme.textPrimary

    /**
     * Get secondary text color for current theme.
     *
     * @return Secondary text color
     */
    fun getTextSecondaryColor(): Int = currentColorScheme.textSecondary

    /**
     * Get accent color for current theme.
     *
     * @return Accent color
     */
    fun getAccentColor(): Int = currentColorScheme.accent

    /**
     * Calculate contrasting color for text on given background.
     *
     * @param backgroundColor Background color
     * @return Contrasting text color (black or white)
     */
    fun getContrastingTextColor(backgroundColor: Int): Int {
        // Calculate relative luminance
        val r = Color.red(backgroundColor) / 255.0
        val g = Color.green(backgroundColor) / 255.0
        val b = Color.blue(backgroundColor) / 255.0

        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b

        // Return white for dark backgrounds, black for light backgrounds
        return if (luminance < 0.5) Color.WHITE else Color.BLACK
    }

    /**
     * Blend two colors with given ratio.
     *
     * @param color1 First color
     * @param color2 Second color
     * @param ratio Blend ratio (0.0 = color1, 1.0 = color2)
     * @return Blended color
     */
    fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val clampedRatio = ratio.coerceIn(0f, 1f)
        val inverseRatio = 1f - clampedRatio

        val r = (Color.red(color1) * inverseRatio + Color.red(color2) * clampedRatio).toInt()
        val g = (Color.green(color1) * inverseRatio + Color.green(color2) * clampedRatio).toInt()
        val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * clampedRatio).toInt()
        val a = (Color.alpha(color1) * inverseRatio + Color.alpha(color2) * clampedRatio).toInt()

        return Color.argb(a, r, g, b)
    }

    /**
     * Get transition animation duration.
     *
     * @return Duration in milliseconds
     */
    fun getTransitionDuration(): Long = TRANSITION_DURATION_MS

    /**
     * Set callback for theme change events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Reset to default settings.
     */
    fun reset() {
        themeMode = ThemeMode.AUTO
        darkVariant = DarkVariant.STANDARD
        detectSystemTheme()
        updateTheme()
        logD("Reset to default settings")
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing DarkModeManager resources...")

        try {
            callback = null
            logD("✅ DarkModeManager resources released")
        } catch (e: Exception) {
            logE("Error releasing dark mode manager resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
