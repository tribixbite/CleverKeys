package tribixbite.cleverkeys.customization

import android.content.SharedPreferences
import android.content.res.Resources
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.Utils
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.prefs.ListGroupPreference

/**
 * Changes a subkey that a layout's OWN XML defines (docs/specs/subkey-customization-current.md
 * "Layout subkeys and modified copies").
 *
 * A built-in layout (a named entry, or the System entry's locale layout) is never changed: the
 * first edit replaces its entry in the `layouts` preference with a MODIFIED COPY — a custom layout
 * holding the built-in XML with the edit applied, renamed "<name> (modified)", carrying the
 * entry's language, theme and key-geometry choices and its [LayoutsPreference.CustomLayout.origin]
 * so Layout Manager can revert it. Later edits, and edits on any other custom layout, change that
 * custom layout's XML directly. The keyboard reloads the `layouts` preference when it changes.
 *
 * @param resolveSystemName the built-in layout name the System entry shows now (null if unknown).
 */
class LayoutSubkeyEditor(
    private val prefs: SharedPreferences,
    private val res: Resources,
    private val resolveSystemName: () -> String? = { null },
) {

    /** Result of [apply]. */
    sealed interface Outcome {
        /** Saved; [copyCreated] when a built-in layout was copied first. */
        data class Saved(val entry: LayoutsPreference.CustomLayout, val copyCreated: Boolean) : Outcome

        /** Nothing saved: [reason] says why (stale entry, unknown source, invalid result). */
        data class Failed(val reason: String) : Outcome
    }

    /** The `layouts` preference entries. */
    fun entries(): List<LayoutsPreference.Layout> =
        ListGroupPreference.loadFromPreferences(
            LayoutsPreference.KEY, prefs, LayoutsPreference.DEFAULT, LayoutsPreference.SERIALIZER
        ) ?: LayoutsPreference.DEFAULT

    /** Whether editing a subkey of [entry] creates a copy first (it is a built-in layout). */
    fun needsCopy(entry: LayoutsPreference.Layout): Boolean = entry !is LayoutsPreference.CustomLayout

    /** The built-in layout name behind [entry] (System → the locale layout), or null. */
    fun builtInName(entry: LayoutsPreference.Layout): String? = when (entry) {
        is LayoutsPreference.NamedLayout -> entry.name
        is LayoutsPreference.SystemLayout -> resolveSystemName()
        else -> null
    }

    /** The parsed layout [entry] defines, or null when it cannot be resolved. */
    fun definition(entry: LayoutsPreference.Layout): KeyboardData? = when (entry) {
        is LayoutsPreference.CustomLayout -> entry.parsed
        else -> builtInName(entry)?.let { LayoutsPreference.layoutOfString(res, it) }
    }

    /** The XML text [entry] defines, or null when it cannot be read. */
    fun sourceXml(entry: LayoutsPreference.Layout): String? = when (entry) {
        is LayoutsPreference.CustomLayout -> entry.xml
        else -> builtInName(entry)?.let { name ->
            val id = LayoutsPreference.layoutIdOfName(res, name)
            if (id <= 0) null else runCatching { res.openRawResource(id).use { Utils.read_all_utf8(it) } }.getOrNull()
        }
    }

    /** The name Layout Manager shows for [entry] ("QWERTY (US)"). */
    fun displayName(entry: LayoutsPreference.Layout): String = when (entry) {
        is LayoutsPreference.CustomLayout -> entry.parsed?.name?.takeIf { it.isNotBlank() }
            ?: res.getString(R.string.layout_manager_custom_layout_name)
        else -> builtInName(entry)?.let(::builtInDisplayName)
            ?: res.getString(R.string.layout_manager_system_layout_name)
    }

    /** The display name of built-in layout [name] (`pref_layout_entries`), or [name] itself. */
    fun builtInDisplayName(name: String): String {
        val i = LayoutsPreference.getLayoutNames(res).indexOf(name)
        val names = res.getStringArray(R.array.pref_layout_entries)
        return if (i in names.indices) names[i] else name
    }

    /** The name a copy of [entry] gets: "QWERTY (US) (modified)". */
    fun copyName(entry: LayoutsPreference.Layout): String =
        res.getString(R.string.subkey_layout_copy_name, displayName(entry))

    /** Same stored entry: compared in stored form (a parsed layout has no value equality). */
    fun sameEntry(a: LayoutsPreference.Layout, b: LayoutsPreference.Layout): Boolean =
        LayoutsPreference.SERIALIZER.saveItem(a).toString() == LayoutsPreference.SERIALIZER.saveItem(b).toString()

    /**
     * Set (or, with a null [value], remove) the [direction] subkey of key ([rowIndex], [keyIndex])
     * in layout entry [entryIndex], copying a built-in layout first.
     *
     * @param expected the entry the caller resolved the key against; if the preference changed in
     *   between (Layout Manager, a backup restore), nothing is written.
     */
    fun apply(
        entryIndex: Int,
        expected: LayoutsPreference.Layout,
        rowIndex: Int,
        keyIndex: Int,
        direction: SwipeDirection,
        value: String?,
    ): Outcome {
        val entries = entries().toMutableList()
        val entry = entries.getOrNull(entryIndex)
        if (entry == null || !sameEntry(entry, expected)) return Outcome.Failed("layout list changed")
        val xml = sourceXml(entry) ?: return Outcome.Failed("layout source unavailable")
        val edited = try {
            val withSubkey = LayoutSubkeyXml.setSubkey(xml, rowIndex, keyIndex, direction, value)
            if (needsCopy(entry)) LayoutSubkeyXml.rename(withSubkey, copyName(entry)) else withSubkey
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        // Never store a layout the keyboard cannot load.
        try {
            KeyboardData.load_string_exn(edited)
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        val updated = when (entry) {
            is LayoutsPreference.CustomLayout ->
                LayoutsPreference.CustomLayout.parse(edited, entry.language, entry.theme, entry.keyGeometry, entry.origin)
            is LayoutsPreference.SystemLayout ->
                LayoutsPreference.CustomLayout.parse(
                    edited, entry.language, entry.theme, entry.keyGeometry,
                    LayoutsPreference.CustomLayout.ORIGIN_SYSTEM
                )
            is LayoutsPreference.NamedLayout ->
                LayoutsPreference.CustomLayout.parse(edited, entry.language, entry.theme, entry.keyGeometry, entry.name)
            else -> return Outcome.Failed("unknown layout entry")
        }
        entries[entryIndex] = updated
        val editor = prefs.edit()
        LayoutsPreference.saveToPreferences(editor, entries)
        // commit(): the dialog re-reads the entry right after and the keyboard must see it.
        if (!editor.commit()) return Outcome.Failed("preferences not saved")
        return Outcome.Saved(updated, copyCreated = needsCopy(entry))
    }
}
