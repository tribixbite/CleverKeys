package tribixbite.cleverkeys.customization

import android.content.Context
import tribixbite.cleverkeys.CleverKeysService
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * A key being customized and where its subkeys come from (docs/specs/subkey-customization-current.md
 * "Sources in the per-key dialog"): the per-key dialog's captured key and the subkey popover's
 * key resolve to it the same way.
 *
 * @property liveKey the key as the keyboard shows it (null when it could not be found)
 * @property definedKey the key as its definition declares it: the layout's own XML key when
 *   [layoutOwned], the shared bottom-row key, or [liveKey] when no definition is known
 * @property entryIndex / [entry] the `layouts` preference entry the key belongs to
 * @property row / [col] the key's position in that entry's XML
 * @property layoutName the entry's display name; [copyName] the name of the copy a LAYOUT edit
 *   would create (null when the entry is already a custom layout, edited in place)
 */
data class SubkeyKeyContext(
    val liveKey: KeyboardData.Key?,
    val rowHeight: Float,
    val definedKey: KeyboardData.Key?,
    val layoutOwned: Boolean,
    val entryIndex: Int,
    val entry: LayoutsPreference.Layout?,
    val row: Int,
    val col: Int,
    val layoutName: String?,
    val copyName: String?,
) {
    /**
     * The context after [newEntry] replaced the entry with [direction] edited: the dialog shows
     * the edit at once, before the keyboard has reloaded its layouts.
     */
    fun afterLayoutEdit(
        context: Context,
        editor: LayoutSubkeyEditor,
        newEntry: LayoutsPreference.Layout,
        direction: SwipeDirection,
    ): SubkeyKeyContext {
        val definition = editor.definition(newEntry)
        val newDefined = definition?.rows?.getOrNull(row)?.keys?.getOrNull(col) ?: definedKey
        val newValue = newDefined?.keys?.getOrNull(direction.subLabelIndex)
        return copy(
            liveKey = liveKey?.let { KeySubkeySources.withSubkey(it, direction, newValue) },
            definedKey = newDefined,
            entry = newEntry,
            layoutName = editor.displayName(newEntry),
            copyName = null,
        )
    }

    companion object {
        /**
         * The key whose runtime short-swipe code is [keyCode] (`ShortSwipeKeyCodes`), on the
         * keyboard's current layout, resolved as [resolve] does — the subkey popover knows its key
         * by code. With the keyboard not running, or the key not on the current layout, a context
         * without a live key or layout entry (every edit is a custom mapping).
         */
        fun resolveCode(context: Context, editor: LayoutSubkeyEditor, keyCode: String): SubkeyKeyContext =
            resolve(context, editor, CleverKeysService.findKeyByChar(keyCode))

        /** Resolve [liveKey] against the keyboard's current layout entry (see the class doc). */
        fun resolve(context: Context, editor: LayoutSubkeyEditor, liveKey: KeyboardData.Key?): SubkeyKeyContext {
            val rowHeight = liveKey?.let { CleverKeysService.getRowHeightForKey(it) } ?: 1.0f
            val main = liveKey?.keys?.getOrNull(0)
            val fallback = SubkeyKeyContext(liveKey, rowHeight, liveKey, false, -1, null, -1, -1, null, null)
            if (main == null) return fallback
            val snapshot = CleverKeysService.layoutSourceSnapshot()
            if (snapshot != null && !snapshot.paneShowing) {
                val entry = editor.entries().getOrNull(snapshot.entryIndex)
                // The System entry is the locale layout the keyboard resolved; others are read
                // from the entry itself so a copy edits exactly what is stored.
                val definition = when {
                    entry == null -> null
                    entry is LayoutsPreference.SystemLayout &&
                        snapshot.builtInName == null -> null
                    else -> editor.definition(entry)
                }
                val position = definition?.let { KeySubkeySources.locate(it, main) }
                if (entry != null && definition != null && position != null && editor.sourceXml(entry) != null) {
                    val (r, c) = position
                    return SubkeyKeyContext(
                        liveKey, rowHeight, definition.rows[r].keys[c], true, snapshot.entryIndex, entry, r, c,
                        editor.displayName(entry),
                        if (editor.needsCopy(entry)) editor.copyName(entry) else null,
                    )
                }
            }
            // Not the layout's own key: the shared bottom row (Space, Enter, Ctrl …) or a key the
            // keyboard adds (number row, Extra Keys). Its subkeys are edited by custom mappings.
            val bottom = try {
                KeyboardData.load_row(context.resources, R.xml.bottom_row)
            } catch (e: Exception) {
                null
            }
            val shared = bottom?.keys?.firstOrNull { it.keys.getOrNull(0) == main }
            return fallback.copy(definedKey = shared ?: liveKey)
        }
    }
}
