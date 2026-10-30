package tribixbite.cleverkeys.customization

/** Where an edit of a slot goes: the layout's own XML (copying a built-in first) or a custom mapping. */
enum class SlotEditTarget { LAYOUT, GLOBAL }

/**
 * Where a change, removal or restore of one subkey slot goes — the ONE rule behind both screens
 * that edit subkeys (docs/specs/subkey-customization-current.md "One policy for both screens"):
 * Settings → Short Swipe Customization's per-key dialog (`KeyCustomizationDialog`) and the subkey
 * popover's assign/edit screen (`SubkeyAssignActivity`). [SubkeySlotActions] carries the decisions
 * out. Pure, so `SubkeyEditPolicyTest` pins it on the JVM.
 */
object SubkeyEditPolicy {

    /** How an edit or removal of a slot proceeds. */
    enum class Route {
        /** The slot is a BUILT-IN layout's own subkey: ask "Create copy" / "Change in all layouts". */
        ASK_COPY,

        /** The slot is a custom layout's own subkey (a copy, an imported layout): edit its XML. */
        LAYOUT,

        /** Anything else: a global custom mapping (or a `removed` blank). */
        GLOBAL,
    }

    /** How a chosen action is stored. */
    enum class Save {
        LAYOUT,
        GLOBAL,

        /** The layout was the target but cannot hold the action: a custom mapping, with a toast. */
        GLOBAL_INSTEAD_OF_LAYOUT,
    }

    /** How a GLOBAL removal is stored. */
    enum class GlobalRemoval {
        /** A `removed` mapping hides what the keyboard shows there. */
        BLANK,

        /** Nothing is shown underneath: the custom mapping is simply deleted. */
        DELETE,
    }

    /** What "restore" does to a slot with a custom mapping. */
    enum class Restore {
        /** Drop the mapping; the subkey it hid shows again ("restored"). */
        RESTORE,

        /** Drop the mapping; nothing was underneath, so the slot empties. */
        DELETE,
    }

    /**
     * Route an edit or removal of [slot]. [copyNeeded]: the slot's layout entry is a built-in
     * layout, which is never changed — its first edit makes "<name> (modified)"
     * (`LayoutSubkeyEditor.needsCopy`).
     */
    fun route(slot: SubkeySlot, copyNeeded: Boolean): Route = when {
        !slot.editsLayout -> Route.GLOBAL
        copyNeeded -> Route.ASK_COPY
        else -> Route.LAYOUT
    }

    /** The target the copy question's answer picks: "Create copy" or "Change in all layouts". */
    fun targetAfterCopyQuestion(createCopy: Boolean): SlotEditTarget =
        if (createCopy) SlotEditTarget.LAYOUT else SlotEditTarget.GLOBAL

    /**
     * How an action chosen for [target] is stored. [layoutValue] is the action as a layout value
     * (`XmlAttributeMapper.layoutValue`), null when no layout can hold it.
     */
    fun save(target: SlotEditTarget, layoutValue: String?): Save = when {
        target == SlotEditTarget.GLOBAL -> Save.GLOBAL
        layoutValue != null -> Save.LAYOUT
        else -> Save.GLOBAL_INSTEAD_OF_LAYOUT
    }

    /** How a GLOBAL removal of [slot] is stored: blank what is shown there, else delete. */
    fun globalRemoval(slot: SubkeySlot): GlobalRemoval =
        if (slot.shownValue != null) GlobalRemoval.BLANK else GlobalRemoval.DELETE

    /** What restoring [slot] does, or null when it has no custom mapping (nothing to restore). */
    fun restore(slot: SubkeySlot): Restore? = when {
        slot.custom == null -> null
        slot.shadowedValue != null -> Restore.RESTORE
        else -> Restore.DELETE
    }
}
