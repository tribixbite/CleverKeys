package tribixbite.cleverkeys.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Main theme composable for CleverKeys - Material 3 compliant.
 *
 * Provides complete theming for all keyboard components:
 * - Material 3 ColorScheme (standard Material colors)
 * - KeyboardColorScheme (keyboard-specific colors)
 * - Typography (keyboard-optimized text styles)
 * - Shapes (rounded corners for keys, chips, dialogs)
 *
 * Features:
 * - Dynamic color support (Material You) on Android 12+
 * - Dark/light theme switching
 * - Reactive theme updates
 * - Custom keyboard color tokens
 *
 * Usage:
 * ```kotlin
 * KeyboardTheme(darkTheme = isSystemInDarkTheme()) {
 *     // Your keyboard UI components
 *     SuggestionBar(...)
 *     Keyboard2View(...)
 * }
 * ```
 *
 * Accessing keyboard colors:
 * ```kotlin
 * val keyboardColors = LocalKeyboardColorScheme.current
 * Surface(color = keyboardColors.keyDefault) { ... }
 * ```
 *
 * @param darkTheme Whether to use dark theme (default: system preference)
 * @param dynamicColor Whether to use dynamic color (Material You) if available
 * @param content Composable content to theme
 */
@Composable
fun KeyboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Initialize theme manager (singleton per context)
    val themeManager = MaterialThemeManager(context)

    // Update theme config if dynamic color preference changed
    if (themeManager.themeConfig.value.useDynamicColor != dynamicColor) {
        themeManager.updateTheme(
            themeManager.themeConfig.value.copy(useDynamicColor = dynamicColor)
        )
    }

    // Get Material 3 color scheme
    val colorScheme = themeManager.getColorScheme(darkTheme)

    // Get keyboard-specific color scheme
    val keyboardColorScheme = themeManager.getKeyboardColorScheme(darkTheme)

    // Provide keyboard colors via CompositionLocal
    CompositionLocalProvider(LocalKeyboardColorScheme provides keyboardColorScheme) {
        // Apply Material 3 theme
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KeyboardTypography,
            shapes = KeyboardShapes,
            content = content
        )
    }
}

/**
 * CompositionLocal for keyboard-specific colors.
 *
 * Provides KeyboardColorScheme to all composables in the hierarchy.
 * Access via: `val colors = LocalKeyboardColorScheme.current`
 *
 * This allows keyboard components to use semantic color tokens like:
 * - `colors.keyDefault` instead of `Color(0xFFF5F5F5)`
 * - `colors.swipeTrail` instead of hardcoded trail color
 * - `colors.suggestionText` instead of `Color.WHITE`
 */
val LocalKeyboardColorScheme = staticCompositionLocalOf {
    // Default to light theme if not provided
    lightKeyboardColorScheme()
}

/**
 * Helper to get current keyboard color scheme in Composables.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun MyKeyboardComponent() {
 *     val colors = keyboardColors()
 *     Surface(color = colors.keyDefault) { ... }
 * }
 * ```
 */
@Composable
fun keyboardColors(): KeyboardColorScheme = LocalKeyboardColorScheme.current

/**
 * Preview theme wrapper for Compose previews.
 *
 * Use in @Preview functions to show components with proper theming:
 * ```kotlin
 * @Preview
 * @Composable
 * fun SuggestionBarPreview() {
 *     KeyboardThemePreview {
 *         SuggestionBar(...)
 *     }
 * }
 * ```
 */
@Composable
fun KeyboardThemePreview(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    KeyboardTheme(darkTheme = darkTheme, dynamicColor = false, content = content)
}
