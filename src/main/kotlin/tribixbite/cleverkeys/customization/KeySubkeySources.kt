package tribixbite.cleverkeys.customization

import androidx.annotation.StringRes
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.R

/**
 * Where a key's subkey in one direction comes from, as the per-key customization dialog shows it
 * (docs/specs/subkey-customization-current.md "Sources in the per-key dialog").
 */
enum class SubkeySource(@StringRes val labelRes: Int) {
    /** Defined by the current layout's own XML: editing it writes a modified copy of the layout. */
    LAYOUT(R.string.subkey_source_layout),

    /** Defined by a row every layout shares (the bottom row) or a pane; edited by a custom mapping. */
    SHARED(R.string.subkey_source_shared),

    /** Placed by Settings → Extra Keys, not by the key's definition. */
    EXTRA_KEY(R.string.subkey_source_extra_key),

    /** Defined by the layout but switched off (an Extra Keys `loc` subkey that is unchecked). */
    HIDDEN(R.string.subkey_source_hidden),

    /** A custom short swipe (global, every layout); it wins over whatever else is here. */
    CUSTOM(R.string.subkey_source_custom),

    /** A custom `removed` mapping blanks the slot. */
    REMOVED(R.string.subkey_source_removed),

    /** Nothing here. */
    NONE(R.string.subkey_slot_empty),
}

/**
 * One direction of a key in the per-key dialog.
 *
 * @property definedValue the value the key's definition carries here: the layout's own XML when
 *   [layoutOwned], else the shared row's. Null when the definition has nothing here.
 * @property shownValue what the keyboard shows here when no custom mapping applies (after Extra
 *   Keys and the keyboard's own adjustments); null when nothing.
 * @property custom the user's custom mapping here (including a `removed` blank), or null.
 * @property layoutOwned whether [definedValue] belongs to the current layout's own XML.
 */
data class SubkeySlot(
    val direction: SwipeDirection,
    val definedValue: KeyValue?,
    val shownValue: KeyValue?,
    val custom: ShortSwipeMapping?,
    val layoutOwned: Boolean,
) {
    /** The source of what a swipe this way does now (the EFFECTIVE entry). */
    val source: SubkeySource
        get() = when {
            custom?.isRemoval == true -> SubkeySource.REMOVED
            custom != null -> SubkeySource.CUSTOM
            shownValue == null -> if (definedValue != null) SubkeySource.HIDDEN else SubkeySource.NONE
            definedValue == null -> SubkeySource.EXTRA_KEY
            layoutOwned -> SubkeySource.LAYOUT
            else -> SubkeySource.SHARED
        }

    /**
     * The value a custom mapping (or a removal) shadows here: what the slot would do without it.
     * Null when there is no custom mapping or nothing underneath it.
     */
    val shadowedValue: KeyValue?
        get() = if (custom == null) null else shownValue ?: definedValue

    /**
     * Whether editing or removing this slot changes the LAYOUT (its own XML) rather than adding
     * a global custom mapping: the effective entry is the layout's own subkey (shown or hidden).
     */
    val editsLayout: Boolean
        get() = custom == null && layoutOwned && definedValue != null
}

/** Builds the eight [SubkeySlot]s of a key (pure; `KeySubkeySourcesTest`). */
object KeySubkeySources {

    /** Directions in the order the dialog lists them: clockwise from north. */
    val LIST_ORDER: List<SwipeDirection> = listOf(
        SwipeDirection.N, SwipeDirection.NE, SwipeDirection.E, SwipeDirection.SE,
        SwipeDirection.S, SwipeDirection.SW, SwipeDirection.W, SwipeDirection.NW,
    )

    /**
     * @param definedKey the key as its definition declares it (the layout's own XML key when
     *   [layoutOwned], the shared bottom-row key otherwise), or null when unknown.
     * @param liveKey the key as the keyboard shows it (Extra Keys added, `loc` subkeys filtered),
     *   or null when unknown (then the definition is taken as shown).
     * @param customs the user's custom mappings for this key, by direction.
     */
    fun resolve(
        definedKey: KeyboardData.Key?,
        layoutOwned: Boolean,
        liveKey: KeyboardData.Key?,
        customs: Map<SwipeDirection, ShortSwipeMapping>,
    ): List<SubkeySlot> = LIST_ORDER.map { dir ->
        val defined = definedKey?.keys?.getOrNull(dir.subLabelIndex)
        val shown = if (liveKey != null) liveKey.keys.getOrNull(dir.subLabelIndex) else defined
        SubkeySlot(dir, defined, shown, customs[dir], layoutOwned && definedKey != null)
    }

    /**
     * Position of the key whose main value is [main] in [layout] (row, key), or null. The first
     * match wins: a layout with two identical keys edits the first one.
     */
    fun locate(layout: KeyboardData, main: KeyValue?): Pair<Int, Int>? {
        if (main == null) return null
        layout.rows.forEachIndexed { r, row ->
            row.keys.forEachIndexed { c, key -> if (key.keys.getOrNull(0) == main) return r to c }
        }
        return null
    }

    /**
     * [liveKey] with its [direction] subkey replaced by [value]: what the keyboard will show once
     * it reloads an edited layout, so the dialog reflects a layout edit at once.
     */
    fun withSubkey(liveKey: KeyboardData.Key, direction: SwipeDirection, value: KeyValue?): KeyboardData.Key {
        val keys = liveKey.keys.toMutableList()
        while (keys.size <= direction.subLabelIndex) keys.add(null)
        keys[direction.subLabelIndex] = value
        return liveKey.copy(keys = keys)
    }
}
