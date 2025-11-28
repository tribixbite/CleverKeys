package tribixbite.keyboard2.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography scale for CleverKeys keyboard - Material 3 compliant.
 *
 * Defines text styles for all keyboard components:
 * - Key labels (large, readable for touch typing)
 * - Sub-labels (smaller shifted characters)
 * - Suggestion text (medium size in suggestion bar)
 * - Dialog text (settings, popups)
 *
 * Uses larger sizes than standard Material 3 for better touch target legibility.
 */

/**
 * Keyboard font family.
 *
 * Currently uses system default font.
 * TODO: Load special_font.ttf from assets for better character rendering.
 */
private val keyboardFontFamily = FontFamily.Default

/**
 * Keyboard typography scale - optimized for touch typing.
 *
 * Larger than standard Material 3 typography for better legibility
 * on keyboard keys (typically 20-30sp for main labels).
 */
val KeyboardTypography = Typography(
    // Display - used for large headers in settings/dialogs
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // Headline - used for section headers
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // Title - PRIMARY USE: Key labels on keyboard
    titleLarge = TextStyle(
        fontFamily = keyboardFontFamily,  // Use special keyboard font
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,                 // Large enough for touch typing
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = keyboardFontFamily,  // Use special keyboard font
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,                 // Secondary key labels
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = keyboardFontFamily,  // Use special keyboard font
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,                 // Sub-labels on keys
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // Body - used for suggestion bar, dialogs, settings
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,                 // Suggestion text
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,                 // Body text in dialogs
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,                 // Small descriptive text
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // Label - used for buttons, chips, small UI elements
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,                 // Button text
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,                 // Chip text
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,                 // Small labels
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * Get text style for key label based on key type.
 *
 * Different key types use different text styles:
 * - Regular keys (letters, numbers): titleLarge (22sp)
 * - Modifier keys (Shift, Ctrl): titleMedium (18sp)
 * - Special keys (Enter, Space): titleMedium (18sp)
 * - Sub-labels (shifted characters): titleSmall (14sp)
 */
object KeyboardTextStyles {
    val keyLabel = KeyboardTypography.titleLarge      // 22sp - main character
    val keySubLabel = KeyboardTypography.titleSmall   // 14sp - shifted character
    val modifierKey = KeyboardTypography.titleMedium  // 18sp - Shift, Ctrl, Alt
    val specialKey = KeyboardTypography.titleMedium   // 18sp - Enter, Backspace
    val suggestion = KeyboardTypography.bodyLarge     // 16sp - suggestion text
    val dialogTitle = KeyboardTypography.headlineSmall // 24sp - dialog headers
    val dialogBody = KeyboardTypography.bodyMedium    // 14sp - dialog text
}
