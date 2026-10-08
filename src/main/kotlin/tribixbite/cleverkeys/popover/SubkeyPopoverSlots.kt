package tribixbite.cleverkeys.popover

import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.SubLabelSizing
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * What one popover slot holds: the same thing a short swipe in that direction would do
 * (docs/specs/subkey-popover.md "Slots"), so the popover and short swipes can never disagree.
 */
sealed interface PopoverSlot {
    val direction: SwipeDirection

    /** The layout's subkey, already passed through the active modifiers. */
    data class Default(override val direction: SwipeDirection, val value: KeyValue) : PopoverSlot

    /**
     * The user's custom mapping. [hidesDefault]: the layout has a subkey in this slot that the
     * mapping overrides, so the edit screen can offer "Restore default".
     */
    data class Custom(
        override val direction: SwipeDirection,
        val mapping: ShortSwipeMapping,
        val hidesDefault: Boolean,
    ) : PopoverSlot

    /**
     * Nothing to do here. [hiddenDefault]: the slot is empty only because a `removed` mapping
     * blanks a layout subkey, so the assign screen can offer to bring it back.
     */
    data class Empty(override val direction: SwipeDirection, val hiddenDefault: Boolean) : PopoverSlot
}

/**
 * Text and font for drawing a slot; `null` label = draw the empty-slot affordance.
 * [sizeScale] is the [SubLabelSizing] factor the keyboard applies to the same label, so a
 * cell's glyph keeps the proportions it has on the key (smaller-font labels and emoji at 0.75).
 */
data class PopoverSlotLabel(val text: String?, val useKeyFont: Boolean, val sizeScale: Float = 1f)

object SubkeyPopoverSlots {

    /**
     * Resolve the eight slots of a key, in [SwipeDirection.clockwiseFromNorth] order.
     *
     * @param defaultAt the layout's subkey in a direction with the current modifiers applied
     *   (`modifyKey(key.keys[dir.subLabelIndex], mods)`), or null when there is none.
     * @param customAt the user's mapping for this key in a direction, or null. Always null for a
     *   key that cannot carry mappings (see `ShortSwipeCustomizationManager.isMappableKeyCode`).
     */
    fun resolve(
        defaultAt: (SwipeDirection) -> KeyValue?,
        customAt: (SwipeDirection) -> ShortSwipeMapping?,
    ): List<PopoverSlot> = SwipeDirection.clockwiseFromNorth().map { dir ->
        val default = defaultAt(dir)?.takeIf { isUsable(it) }
        val custom = customAt(dir)
        when {
            custom != null && custom.isRemoval -> PopoverSlot.Empty(dir, hiddenDefault = default != null)
            custom != null -> PopoverSlot.Custom(dir, custom, hidesDefault = default != null)
            default != null -> PopoverSlot.Default(dir, default)
            else -> PopoverSlot.Empty(dir, hiddenDefault = false)
        }
    }

    /**
     * Whether a layout value can fill a slot. Placeholders (`removed`, Fn placeholders that did
     * not resolve) render as nothing on the key, so the popover must not offer them either.
     */
    private fun isUsable(kv: KeyValue): Boolean =
        kv.getKind() != KeyValue.Kind.Placeholder && kv.getString().isNotEmpty()

    /** The label a slot is drawn with. */
    fun labelOf(slot: PopoverSlot): PopoverSlotLabel = when (slot) {
        is PopoverSlot.Default -> PopoverSlotLabel(
            slot.value.getString(), slot.value.hasFlagsAny(KeyValue.FLAG_KEY_FONT),
            SubLabelSizing.scaleFor(slot.value)
        )
        is PopoverSlot.Custom -> PopoverSlotLabel(
            // A mapping saved with a blank label still needs something to aim at.
            slot.mapping.displayText.ifEmpty { "•" }, slot.mapping.useKeyFont,
            SubLabelSizing.scaleFor(slot.mapping)
        )
        is PopoverSlot.Empty -> PopoverSlotLabel(null, false)
    }

    /** Whether resting on the slot for the dwell time opens the edit screen. */
    fun isEditable(slot: PopoverSlot): Boolean = slot !is PopoverSlot.Empty
}
