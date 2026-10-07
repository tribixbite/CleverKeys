package tribixbite.cleverkeys.clipboard

import tribixbite.cleverkeys.ClipboardDeleteSnapshot
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardTab

/**
 * A persistent, tab-scoped selection of clipboard rows for batch deletion.
 *
 * The user builds it from several searches and size filters: "select all matching" adds every
 * row of whatever list the caller filtered (all pages, not the visible one), individual toggles
 * and "deselect all matching" remove rows, and changing the search, filters or page never
 * touches it. Only an explicit exit, a tab switch, the pane closing or the keyboard hiding
 * end it — the owning view simply drops the instance.
 *
 * Identity is the database row id ([ClipboardEntry.rowId]) of the tab's own table, so the
 * selection is meaningless outside [tab]; rows without an id (never stored) cannot be
 * selected. Alongside each id the model keeps a 64-bit payload version (timestamp, content,
 * MIME type, media path). A row whose payload changed after it was selected — edited, or
 * re-captured with a new timestamp — is no longer the clipping the user chose: [reconcile]
 * drops it and [resolve] never returns it. Tag and todo-status edits do not change the
 * version, so curating a row does not silently deselect it.
 *
 * Memory: one boxed id and one boxed version per selected row, never the clipping content or
 * thumbnail, so the cost is bounded by the tab's row count rather than by payload sizes.
 * Not thread-safe; the owning view touches it on the main thread only.
 */
class ClipboardSelection(val tab: ClipboardTab) {

    /** How much of a list of matching rows is currently selected. */
    enum class Coverage { NONE, PARTIAL, ALL }

    // rowId -> payload version at selection time. LinkedHashMap keeps selection order stable
    // for deterministic snapshots; the values are primitives boxed as Long, never entries.
    private val versions = LinkedHashMap<Long, Long>()

    /** Number of selected rows. */
    val size: Int get() = versions.size

    /** Selected row ids in selection order (a copy; callers cannot mutate the model). */
    fun selectedIds(): Set<Long> = LinkedHashSet(versions.keys)

    /** Whether [entry], in its current payload version, is selected. */
    fun isSelected(entry: ClipboardEntry): Boolean =
        entry.rowId > 0 && versions[entry.rowId] == versionOf(entry)

    /**
     * Flip [entry]'s membership. Returns whether it is selected afterwards; an entry without
     * a database identity is never selectable. Toggling a changed row re-selects its current
     * version rather than removing a stale one.
     */
    fun toggle(entry: ClipboardEntry): Boolean {
        if (entry.rowId <= 0) return false
        return if (isSelected(entry)) {
            versions.remove(entry.rowId)
            false
        } else {
            versions[entry.rowId] = versionOf(entry)
            true
        }
    }

    /** Add every selectable row in [matching]; returns how many were newly selected. */
    fun selectAll(matching: Iterable<ClipboardEntry>): Int {
        var added = 0
        for (entry in matching) {
            if (entry.rowId <= 0 || isSelected(entry)) continue
            versions[entry.rowId] = versionOf(entry)
            added++
        }
        return added
    }

    /** Remove every row of [matching] from the selection; returns how many were removed. */
    fun deselectAll(matching: Iterable<ClipboardEntry>): Int {
        var removed = 0
        for (entry in matching) {
            if (entry.rowId > 0 && versions.remove(entry.rowId) != null) removed++
        }
        return removed
    }

    /** Deselect everything, including rows hidden by the current search or filters. */
    fun clear() = versions.clear()

    /** Selection coverage of [matching]; an empty list has nothing selected ([Coverage.NONE]). */
    fun coverage(matching: Collection<ClipboardEntry>): Coverage {
        var selected = 0
        var selectable = 0
        for (entry in matching) {
            if (entry.rowId <= 0) continue
            selectable++
            if (isSelected(entry)) selected++
        }
        return when {
            selected == 0 -> Coverage.NONE
            selected == selectable -> Coverage.ALL
            else -> Coverage.PARTIAL
        }
    }

    /**
     * Drop rows that are absent from [current] (the tab's complete freshly loaded rows) or
     * whose payload changed since selection. Returns how many were dropped.
     */
    fun reconcile(current: List<ClipboardEntry>): Int {
        if (versions.isEmpty()) return 0
        val live = HashMap<Long, Long>(current.size * 2)
        for (entry in current) if (entry.rowId > 0) live[entry.rowId] = versionOf(entry)
        val before = versions.size
        versions.entries.removeAll { (id, version) -> live[id] != version }
        return before - versions.size
    }

    /**
     * Freeze the confirmation scope: the rows of [current] that are selected and unchanged,
     * as the freshly loaded entries. The database transaction then compares each row against
     * exactly these values, so anything edited between this load and the deletion is skipped
     * as well ([tribixbite.cleverkeys.ClipboardDatabase.deleteSnapshot]).
     */
    fun resolve(current: List<ClipboardEntry>): ClipboardDeleteSnapshot =
        ClipboardDeleteSnapshot(tab, current.filter(::isSelected))

    private companion object {
        /** Payload version: timestamp, content, MIME type and media path; ignores tags/status. */
        fun versionOf(entry: ClipboardEntry): Long {
            var h = entry.timestamp
            h = h * 1_000_003L + entry.content.hashCode()
            h = h * 1_000_003L + entry.content.length
            h = h * 31L + entry.mimeType.hashCode()
            h = h * 31L + (entry.mediaPath?.hashCode() ?: 0)
            return h
        }
    }
}
