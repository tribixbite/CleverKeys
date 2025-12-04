package tribixbite.cleverkeys.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Color scheme for CleverKeys keyboard - Material 3 compliant.
 *
 * Provides semantic color tokens for all keyboard components:
 * - Key colors (default, activated, locked, modifier, special)
 * - Label colors (primary, secondary, tertiary)
 * - Border colors (default, activated)
 * - Interactive colors (swipe trail, ripple)
 * - Suggestion colors (text, background, high confidence)
 * - Background colors (keyboard, surface)
 *
 * This replaces hardcoded Color.WHITE, Color.BLACK, etc. with semantic tokens
 * that adapt to light/dark themes and Material You dynamic colors.
 */
@Immutable
data class KeyboardColorScheme(
    // Key colors - background colors for keyboard keys
    val keyDefault: Color,          // Default key background
    val keyActivated: Color,        // Key background when pressed
    val keyLocked: Color,           // Key background when locked (Shift, Ctrl)
    val keyModifier: Color,         // Modifier key background (Shift, Ctrl, Alt)
    val keySpecial: Color,          // Special key background (Enter, Backspace)

    // Label colors - text on keys
    val keyLabel: Color,            // Primary label on key
    val keySubLabel: Color,         // Sub-label (shifted character)
    val keySecondaryLabel: Color,   // Secondary label (long-press hint)

    // Border colors - key borders
    val keyBorder: Color,           // Default key border
    val keyBorderActivated: Color,  // Key border when pressed

    // Interactive colors - feedback during gestures
    val swipeTrail: Color,          // Color of swipe trail during gesture typing
    val ripple: Color,              // Ripple effect color on key press

    // Suggestion colors - suggestion bar
    val suggestionText: Color,              // Suggestion text color
    val suggestionBackground: Color,        // Suggestion chip background
    val suggestionHighConfidence: Color,    // Icon color for high confidence (>80%)

    // Background colors - keyboard container
    val keyboardBackground: Color,  // Keyboard container background
    val keyboardSurface: Color      // Surface color for elevated elements
)

/**
 * Light theme keyboard color scheme.
 *
 * Uses bright backgrounds with dark text, subtle borders, and vibrant accents.
 * Designed for daylight/bright environment use.
 *
 * @param primary Primary accent color (from Material theme or dynamic color)
 * @param secondary Secondary accent color
 */
fun lightKeyboardColorScheme(
    primary: Color = Color(0xFF1976D2),    // Blue 700
    secondary: Color = Color(0xFF424242)   // Grey 800
): KeyboardColorScheme = KeyboardColorScheme(
    // Keys - light grey background
    keyDefault = Color(0xFFF5F5F5),         // Grey 100
    keyActivated = Color(0xFFE0E0E0),       // Grey 300 - darker when pressed
    keyLocked = primary.copy(alpha = 0.2f), // Tinted with primary
    keyModifier = primary.copy(alpha = 0.1f),
    keySpecial = secondary.copy(alpha = 0.1f),

    // Labels - dark text on light background
    keyLabel = Color(0xFF212121),           // Grey 900 - high contrast
    keySubLabel = Color(0xFF757575),        // Grey 600 - medium contrast
    keySecondaryLabel = Color(0xFF9E9E9E),  // Grey 500 - low contrast

    // Borders - subtle outline
    keyBorder = Color(0xFFBDBDBD),          // Grey 400
    keyBorderActivated = primary,            // Primary color when pressed

    // Interactive - vibrant feedback
    swipeTrail = primary.copy(alpha = 0.6f), // Semi-transparent primary
    ripple = primary.copy(alpha = 0.3f),     // Subtle ripple

    // Suggestions - clean surface
    suggestionText = Color(0xFF212121),      // Grey 900
    suggestionBackground = Color(0xFFFFFFFF), // Pure white
    suggestionHighConfidence = primary,       // Primary for high confidence

    // Background - off-white for depth
    keyboardBackground = Color(0xFFEEEEEE),  // Grey 200
    keyboardSurface = Color(0xFFFFFFFF)      // Pure white
)

/**
 * Dark theme keyboard color scheme.
 *
 * Uses dark backgrounds with light text, subtle borders, and vibrant accents.
 * Designed for low-light/night environment use with reduced eye strain.
 *
 * @param primary Primary accent color (from Material theme or dynamic color)
 * @param secondary Secondary accent color
 */
fun darkKeyboardColorScheme(
    primary: Color = Color(0xFF64B5F6),    // Blue 300 (lighter for dark theme)
    secondary: Color = Color(0xFFB0B0B0)   // Grey 400
): KeyboardColorScheme = KeyboardColorScheme(
    // Keys - dark grey background
    keyDefault = Color(0xFF2C2C2C),         // Dark grey
    keyActivated = Color(0xFF3A3A3A),       // Lighter grey when pressed
    keyLocked = primary.copy(alpha = 0.2f), // Tinted with primary
    keyModifier = primary.copy(alpha = 0.15f),
    keySpecial = secondary.copy(alpha = 0.15f),

    // Labels - light text on dark background
    keyLabel = Color(0xFFE0E0E0),           // Grey 300 - high contrast
    keySubLabel = Color(0xFFB0B0B0),        // Grey 400 - medium contrast
    keySecondaryLabel = Color(0xFF808080),  // Grey 500 - low contrast

    // Borders - subtle outline
    keyBorder = Color(0xFF424242),          // Grey 800
    keyBorderActivated = primary,            // Primary color when pressed

    // Interactive - vibrant feedback (higher alpha for dark theme)
    swipeTrail = primary.copy(alpha = 0.7f), // More visible on dark
    ripple = primary.copy(alpha = 0.4f),     // More visible on dark

    // Suggestions - dark surface
    suggestionText = Color(0xFFE0E0E0),      // Grey 300
    suggestionBackground = Color(0xFF2C2C2C), // Dark grey
    suggestionHighConfidence = primary,       // Primary for high confidence

    // Background - very dark for depth
    keyboardBackground = Color(0xFF1E1E1E),  // Almost black
    keyboardSurface = Color(0xFF2C2C2C)      // Dark grey
)

/**
 * Generate keyboard color scheme from Material 3 color scheme.
 *
 * Extracts primary/secondary colors from Material theme and generates
 * keyboard-specific colors that harmonize with the app theme.
 *
 * This is used for dynamic color (Material You) integration.
 */
fun keyboardColorSchemeFromMaterial(
    primary: Color,
    secondary: Color,
    isDark: Boolean
): KeyboardColorScheme {
    return if (isDark) {
        darkKeyboardColorScheme(primary, secondary)
    } else {
        lightKeyboardColorScheme(primary, secondary)
    }
}
