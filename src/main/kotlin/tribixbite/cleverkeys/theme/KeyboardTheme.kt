package tribixbite.cleverkeys.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Legacy alias for the canonical [CleverKeysTheme] wrapper.
 *
 * Retained so the many existing `KeyboardTheme { … }` call sites keep working; all
 * theming logic now lives in [CleverKeysTheme] (single source of truth). Prefer
 * [CleverKeysTheme] for new code.
 *
 * Provides complete theming for all keyboard components:
 * - Material 3 ColorScheme (standard Material colors)
 * - KeyboardColorScheme (keyboard-specific colors)
 * - Typography (keyboard-optimized text styles)
 * - Shapes (rounded corners for keys, chips, dialogs)
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
) = CleverKeysTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)

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
