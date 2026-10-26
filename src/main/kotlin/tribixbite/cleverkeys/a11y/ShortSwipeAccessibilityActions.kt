package tribixbite.cleverkeys.a11y

import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.popover.PopoverSlot

/**
 * TalkBack custom actions that stand in for a key's short swipes.
 *
 * Under explore-by-touch TalkBack consumes every swipe, so per-key short swipes (Ctrl
 * south-west = clipboard pane, corner subkeys such as paste) never reach the keyboard
 * (maintainer report 2026-10-26). Each non-empty short-swipe slot of a key is therefore
 * offered as an `AccessibilityActionCompat` on that key's virtual node, which TalkBack lists
 * in its Actions menu.
 *
 * The slots come from `Pointers.accessibilityShortSwipeSlots` — the same resolution a real
 * release runs (custom mapping before the layout default, `removed` blanks the slot), and the
 * same eight slots the subkey popover shows — so the menu can never offer something a swipe
 * would not do. This object only names them; it is Android-free (strings via an injected
 * `(Int) -> String`) so it is unit-tested on the JVM.
 */
object ShortSwipeAccessibilityActions {

    /** One menu entry: perform the short swipe toward [direction]; [label] is what TalkBack reads. */
    data class Action(val direction: SwipeDirection, val label: String)

    /** Longest custom TEXT label read out in full; a longer macro is cut with an ellipsis. */
    private const val MAX_TEXT_LABEL = 60

    /**
     * The actions for [slots] (one per non-empty slot, in the slots' clockwise-from-north
     * order). Each label is "<what it does> (<direction>)" via `a11y_short_swipe_action`, so
     * two slots doing the same thing stay distinguishable.
     */
    fun actionsFor(slots: List<PopoverSlot>, getString: (Int) -> String): List<Action> {
        val format = getString(R.string.a11y_short_swipe_action)
        return slots.mapNotNull { slot ->
            val target = describeSlot(slot, getString) ?: return@mapNotNull null
            Action(slot.direction, String.format(format, target, getString(slot.direction.displayNameRes)))
        }
    }

    /** What [slot] does, as spoken text; null for an empty slot (nothing to offer). */
    fun describeSlot(slot: PopoverSlot, getString: (Int) -> String): String? = when (slot) {
        is PopoverSlot.Default -> KeyLabels.describe(slot.value, getString)
        is PopoverSlot.Custom -> describeMapping(slot.mapping, getString)
        is PopoverSlot.Empty -> null
    }

    /**
     * Spoken name of a user's custom short-swipe [mapping]. Prefers the localized catalogue or
     * key name over the mapping's on-key label, which is at most four characters and often a
     * key-font glyph that TTS cannot read.
     */
    fun describeMapping(mapping: ShortSwipeMapping, getString: (Int) -> String): String {
        val fallback = mapping.displayText.takeIf { it.isNotBlank() && !mapping.useKeyFont }
        return when (mapping.actionType) {
            ActionType.COMMAND -> {
                CommandRegistry.getByName(mapping.actionValue)?.let { getString(it.nameRes) }
                    ?: KeyValue.getSpecialKeyByName(mapping.actionValue)?.let { KeyLabels.describe(it, getString) }
                    ?: fallback
                    ?: mapping.actionValue
            }
            ActionType.TEXT -> {
                val text = mapping.actionValue
                when {
                    // One character reads like a key: " " is "Space", not silence.
                    text.length == 1 -> KeyLabels.describe(KeyValue.makeCharKey(text[0]), getString)
                    text.isBlank() -> fallback ?: getString(mapping.actionType.displayNameRes)
                    text.length > MAX_TEXT_LABEL -> text.take(MAX_TEXT_LABEL) + "…"
                    else -> text
                }
            }
            ActionType.KEY_EVENT -> mapping.getKeyEventCode()
                ?.let { KeyLabels.describe(KeyValue.keyeventKey(mapping.displayText, it, 0), getString) }
                ?: fallback
                ?: getString(mapping.actionType.displayNameRes)
            // An intent is named by the label the user gave it; a timestamp or template by its kind.
            ActionType.INTENT -> fallback ?: getString(mapping.actionType.displayNameRes)
            ActionType.TIMESTAMP,
            ActionType.TEMPLATE -> getString(mapping.actionType.displayNameRes)
        }
    }

    /**
     * Accessibility action id for a short swipe toward [direction]. Resource ids
     * (`res/values/values.xml`), as app-defined accessibility actions must be, so they never
     * collide with the framework's standard action ids.
     */
    fun actionId(direction: SwipeDirection): Int = when (direction) {
        SwipeDirection.N -> R.id.a11y_short_swipe_n
        SwipeDirection.NE -> R.id.a11y_short_swipe_ne
        SwipeDirection.E -> R.id.a11y_short_swipe_e
        SwipeDirection.SE -> R.id.a11y_short_swipe_se
        SwipeDirection.S -> R.id.a11y_short_swipe_s
        SwipeDirection.SW -> R.id.a11y_short_swipe_sw
        SwipeDirection.W -> R.id.a11y_short_swipe_w
        SwipeDirection.NW -> R.id.a11y_short_swipe_nw
    }

    /** The direction whose [actionId] is [id], or null for any other action (e.g. ACTION_CLICK). */
    fun directionOf(id: Int): SwipeDirection? = SwipeDirection.entries.firstOrNull { actionId(it) == id }
}
