package tribixbite.cleverkeys.customization

import android.content.Context
import android.widget.Toast
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.shortSwipeActionDescription

/**
 * Save, remove and restore one (key, direction) slot. The single implementation behind both
 * places a slot is edited, so they cannot drift apart:
 * - `ShortSwipeCustomizationActivity` (Settings → Customize Per-Key Actions);
 * - `SubkeyAssignActivity` (the subkey popover's assign/edit screen).
 */
object ShortSwipeAssignment {

    /** Store the palette's [selection] for [keyCode]/[direction] and confirm it with a toast. */
    suspend fun apply(
        context: Context,
        manager: ShortSwipeCustomizationManager,
        keyCode: String,
        direction: SwipeDirection,
        selection: MappingSelection,
    ): ShortSwipeMapping {
        val mapping = ShortSwipeMapping(
            keyCode = keyCode,
            direction = direction,
            displayText = selection.displayLabel,  // User-customized label
            actionType = selection.actionType,
            actionValue = selection.actionValue,   // Actual action/command
            useKeyFont = selection.useKeyFont      // Use icon font if applicable
        )
        manager.setMapping(mapping)
        val actionDesc = shortSwipeActionDescription(context, selection.actionType, selection.actionValue)
        Toast.makeText(
            context,
            context.getString(
                R.string.short_swipe_toast_mapped,
                context.getString(direction.displayNameRes),
                selection.displayLabel,
                actionDesc
            ),
            Toast.LENGTH_SHORT
        ).show()
        return mapping
    }

    /**
     * Blank the slot. When the layout has a subkey there ([hasDefault]) a `removed` mapping
     * hides it (the only way to remove a default); otherwise the custom mapping is deleted.
     */
    suspend fun remove(
        context: Context,
        manager: ShortSwipeCustomizationManager,
        keyCode: String,
        direction: SwipeDirection,
        hasDefault: Boolean,
    ) {
        if (hasDefault) manager.setMapping(ShortSwipeMapping.removal(keyCode, direction))
        else manager.removeMapping(keyCode, direction)
        Toast.makeText(
            context,
            context.getString(R.string.subkey_toast_removed, context.getString(direction.displayNameRes), keyCode.uppercase()),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Drop the custom mapping so the layout's own subkey shows again. */
    suspend fun restoreDefault(
        context: Context,
        manager: ShortSwipeCustomizationManager,
        keyCode: String,
        direction: SwipeDirection,
    ) {
        manager.removeMapping(keyCode, direction)
        Toast.makeText(
            context,
            context.getString(R.string.subkey_toast_restored, context.getString(direction.displayNameRes), keyCode.uppercase()),
            Toast.LENGTH_SHORT
        ).show()
    }
}
