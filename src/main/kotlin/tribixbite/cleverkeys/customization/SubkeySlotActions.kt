package tribixbite.cleverkeys.customization

import android.content.Context
import android.widget.Toast
import tribixbite.cleverkeys.R

/**
 * Carries out [SubkeyEditPolicy]'s decisions for one key — the single implementation behind
 * both screens that edit subkeys (the per-key dialog in Settings → Short Swipe Customization and
 * the subkey popover's assign/edit screen), so they cannot drift apart again
 * (docs/specs/subkey-customization-current.md "One policy for both screens").
 *
 * Layout writes go through [layoutEditor] (a built-in layout is copied first); custom mappings
 * through [ShortSwipeAssignment]. Each outcome is confirmed with a toast.
 */
class SubkeySlotActions(
    private val context: Context,
    private val manager: ShortSwipeCustomizationManager,
    val layoutEditor: LayoutSubkeyEditor,
) {

    /**
     * Store the palette's [selection] in [direction] of [keyCode], into the layout or as a custom
     * mapping as [target] says ([SubkeyEditPolicy.save]).
     *
     * @return the key context after a layout write (the screen shows the edit at once), or null
     *   when the layout was not written.
     */
    suspend fun save(
        key: SubkeyKeyContext?,
        keyCode: String,
        direction: SwipeDirection,
        target: SlotEditTarget,
        selection: MappingSelection,
    ): SubkeyKeyContext? {
        val mapping = ShortSwipeMapping(
            keyCode = keyCode,
            direction = direction,
            displayText = selection.displayLabel,
            actionType = selection.actionType,
            actionValue = selection.actionValue,
            useKeyFont = selection.useKeyFont,
        )
        val layoutValue = XmlAttributeMapper.layoutValue(mapping)
        when (SubkeyEditPolicy.save(target, layoutValue)) {
            SubkeyEditPolicy.Save.LAYOUT -> return writeLayout(key, direction, layoutValue)
            SubkeyEditPolicy.Save.GLOBAL_INSTEAD_OF_LAYOUT -> {
                // An action a layout cannot hold (a name-only command): a custom short swipe instead.
                Toast.makeText(context, R.string.subkey_layout_toast_not_layout, Toast.LENGTH_LONG).show()
                ShortSwipeAssignment.apply(context, manager, keyCode, direction, selection)
            }
            SubkeyEditPolicy.Save.GLOBAL -> ShortSwipeAssignment.apply(context, manager, keyCode, direction, selection)
        }
        return null
    }

    /**
     * Remove [slot] of [keyCode]: from the layout ([SlotEditTarget.LAYOUT]) or with a custom
     * `removed` blank / deletion ([SubkeyEditPolicy.globalRemoval]).
     *
     * @return the key context after a layout write, or null.
     */
    suspend fun remove(key: SubkeyKeyContext?, keyCode: String, slot: SubkeySlot, target: SlotEditTarget): SubkeyKeyContext? {
        if (target == SlotEditTarget.LAYOUT) return writeLayout(key, slot.direction, null)
        val blank = SubkeyEditPolicy.globalRemoval(slot) == SubkeyEditPolicy.GlobalRemoval.BLANK
        ShortSwipeAssignment.remove(context, manager, keyCode, slot.direction, hasDefault = blank)
        return null
    }

    /** Drop [slot]'s custom mapping ([SubkeyEditPolicy.restore]); nothing without one. */
    suspend fun restore(keyCode: String, slot: SubkeySlot) {
        when (SubkeyEditPolicy.restore(slot)) {
            SubkeyEditPolicy.Restore.RESTORE -> ShortSwipeAssignment.restoreDefault(context, manager, keyCode, slot.direction)
            SubkeyEditPolicy.Restore.DELETE -> manager.removeMapping(keyCode, slot.direction)
            null -> {}
        }
    }

    /**
     * Set (or with a null [value], remove) [direction] of [key] in its layout entry, copying a
     * built-in layout first; toasts the outcome.
     */
    private fun writeLayout(key: SubkeyKeyContext?, direction: SwipeDirection, value: String?): SubkeyKeyContext? {
        val entry = key?.entry
        if (key == null || entry == null) return failed("layout source unavailable")
        return when (val outcome = layoutEditor.apply(key.entryIndex, entry, key.row, key.col, direction, value)) {
            is LayoutSubkeyEditor.Outcome.Saved -> {
                val name = layoutEditor.displayName(outcome.entry)
                Toast.makeText(
                    context,
                    context.getString(
                        if (outcome.copyCreated) R.string.subkey_layout_toast_copied else R.string.subkey_layout_toast_saved,
                        name
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                key.afterLayoutEdit(context, layoutEditor, outcome.entry, direction)
            }
            is LayoutSubkeyEditor.Outcome.Failed -> failed(outcome.reason)
        }
    }

    private fun failed(reason: String): SubkeyKeyContext? {
        Toast.makeText(context, context.getString(R.string.subkey_layout_toast_failed, reason), Toast.LENGTH_LONG).show()
        return null
    }
}
