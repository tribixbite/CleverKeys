package tribixbite.cleverkeys.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale for CleverKeys keyboard - Material 3 compliant.
 *
 * Defines rounded corner radii for all keyboard components:
 * - Keys (medium rounded corners for comfortable touch)
 * - Suggestion chips (small rounded corners)
 * - Dialogs (large rounded corners)
 * - Cards (medium rounded corners)
 *
 * Material 3 shape tokens:
 * - extraSmall: 4.dp  (checkboxes, small chips)
 * - small: 8.dp       (buttons, suggestion chips)
 * - medium: 12.dp     (cards, keys)
 * - large: 16.dp      (dialogs, sheets)
 * - extraLarge: 28.dp (large cards, prominent surfaces)
 */
val KeyboardShapes = Shapes(
    // Extra small - tiny UI elements
    extraSmall = RoundedCornerShape(4.dp),

    // Small - buttons, chips, suggestion chips
    small = RoundedCornerShape(8.dp),

    // Medium - PRIMARY USE: keyboard keys, cards
    medium = RoundedCornerShape(12.dp),

    // Large - dialogs, bottom sheets
    large = RoundedCornerShape(16.dp),

    // Extra large - prominent surfaces
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Specific shapes for keyboard components.
 *
 * Provides semantic shape tokens for different keyboard elements.
 * Use these instead of hardcoded RoundedCornerShape values.
 */
object KeyShapes {
    /**
     * Shape for regular keyboard keys.
     * Medium rounded corners (12.dp) for comfortable touch targets.
     */
    val key = RoundedCornerShape(12.dp)

    /**
     * Shape for activated/pressed keys.
     * Same as regular keys for consistency.
     */
    val keyActivated = RoundedCornerShape(12.dp)

    /**
     * Shape for modifier keys (Shift, Ctrl, Alt).
     * Slightly more rounded (14.dp) to distinguish from regular keys.
     */
    val keyModifier = RoundedCornerShape(14.dp)

    /**
     * Shape for special keys (Enter, Backspace, Space).
     * More rounded (16.dp) to emphasize importance.
     */
    val keySpecial = RoundedCornerShape(16.dp)

    /**
     * Shape for suggestion chips in suggestion bar.
     * Small rounded corners (8.dp) for compact appearance.
     */
    val suggestionChip = RoundedCornerShape(8.dp)

    /**
     * Shape for emoji buttons.
     * Small rounded corners (8.dp) for grid alignment.
     */
    val emojiButton = RoundedCornerShape(8.dp)

    /**
     * Shape for clipboard history cards.
     * Medium rounded corners (12.dp) matching Material 3 cards.
     */
    val clipboardCard = RoundedCornerShape(12.dp)

    /**
     * Shape for dialogs (custom layout editor, settings).
     * Large rounded corners (16.dp) for prominent modals.
     */
    val dialog = RoundedCornerShape(16.dp)

    /**
     * Shape for floating keyboard container.
     * Extra large rounded corners (28.dp) for floating appearance.
     */
    val floatingKeyboard = RoundedCornerShape(28.dp)
}

/**
 * Get customizable key shape with adjustable corner radius.
 *
 * Allows runtime customization of key corner radius from user settings.
 *
 * @param radius Corner radius in dp (default: 12.dp)
 */
fun getKeyShape(radius: Float = 12f) = RoundedCornerShape(radius.dp)
