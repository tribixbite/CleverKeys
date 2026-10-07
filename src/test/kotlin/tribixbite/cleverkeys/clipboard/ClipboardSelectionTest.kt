package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.TodoEntry

/**
 * The persistent clipboard selection model (maintainer request 2026-10-07: build a batch
 * deletion out of several searches and size filters, deselect some, add more).
 *
 * Pinned here, independent of any view:
 * - identity is the table row id plus a payload version, never a list position;
 * - "all matching" operations take whatever list the caller filtered (every page);
 * - the selection survives the caller changing that list (search/size/page changes);
 * - rows that vanished or whose payload changed are dropped on reconcile and never
 *   resolved for deletion, while metadata edits (tags, todo status) keep the row selected;
 * - the model retains ids and versions only — never clipping content.
 */
class ClipboardSelectionTest {

    private fun row(id: Long, content: String = "clip-$id", ts: Long = 1_000L + id,
                    tags: List<String> = emptyList(), status: String? = null) =
        ClipboardEntry(content, ts, tags = tags, todoStatus = status, rowId = id, sizeBytes = 10L * id)

    @Test
    fun toggleIsByRowIdentityAndIgnoresRowsWithoutDatabaseIdentity() {
        val selection = ClipboardSelection(ClipboardTab.HISTORY)
        val a = row(1)
        assertThat(selection.toggle(a)).isTrue()
        assertThat(selection.isSelected(a)).isTrue()
        // A different object carrying the same row and payload is the same selection.
        assertThat(selection.isSelected(row(1))).isTrue()
        assertThat(selection.toggle(row(1))).isFalse()
        assertThat(selection.size).isEqualTo(0)

        val unsaved = ClipboardEntry("never stored", 5L)
        assertThat(selection.toggle(unsaved)).isFalse()
        assertThat(selection.size).isEqualTo(0)
    }

    @Test
    fun selectionPersistsAcrossDifferentMatchingListsAndSupportsDeselection() {
        val all = (1L..300L).map { row(it) }
        val selection = ClipboardSelection(ClipboardTab.HISTORY)

        // Search 1 matches 205 rows across three 100-row pages.
        val firstSearch = all.take(205)
        assertThat(selection.selectAll(firstSearch)).isEqualTo(205)
        // Search 2 overlaps search 1 by 5 rows; only the 45 new ones are added.
        val secondSearch = all.subList(200, 250)
        assertThat(selection.selectAll(secondSearch)).isEqualTo(45)
        assertThat(selection.size).isEqualTo(250)
        // Deselect a single row, then every match of a third filter.
        selection.toggle(all[0])
        val thirdFilter = all.subList(240, 260)
        assertThat(selection.deselectAll(thirdFilter)).isEqualTo(10)
        assertThat(selection.size).isEqualTo(239)
        assertThat(selection.isSelected(all[0])).isFalse()
        assertThat(selection.isSelected(all[239])).isTrue()
        assertThat(selection.isSelected(all[240])).isFalse()
    }

    @Test
    fun coverageDescribesTheCurrentMatchesOnly() {
        val rows = (1L..4L).map { row(it) }
        val selection = ClipboardSelection(ClipboardTab.PINNED)
        assertThat(selection.coverage(rows)).isEqualTo(ClipboardSelection.Coverage.NONE)
        selection.toggle(rows[0])
        assertThat(selection.coverage(rows)).isEqualTo(ClipboardSelection.Coverage.PARTIAL)
        selection.selectAll(rows)
        assertThat(selection.coverage(rows)).isEqualTo(ClipboardSelection.Coverage.ALL)
        // A filter matching nothing has nothing to select.
        assertThat(selection.coverage(emptyList())).isEqualTo(ClipboardSelection.Coverage.NONE)
        // Rows outside the current matches do not make the matches partial.
        assertThat(selection.coverage(rows.take(2))).isEqualTo(ClipboardSelection.Coverage.ALL)
    }

    @Test
    fun reconcileDropsVanishedAndChangedRowsButKeepsMetadataEdits() {
        val selection = ClipboardSelection(ClipboardTab.TODOS)
        val kept = row(1, status = TodoEntry.STATUS_ACTIVE)
        val retagged = row(2, tags = listOf("a"))
        val edited = row(3, content = "before")
        val recopied = row(4, ts = 10L)
        val removed = row(5)
        selection.selectAll(listOf(kept, retagged, edited, recopied, removed))

        val reloaded = listOf(
            row(1, status = TodoEntry.STATUS_COMPLETED),
            row(2, tags = listOf("a", "b")),
            row(3, content = "after"),
            row(4, ts = 99L),
            row(6),
        )
        assertThat(selection.reconcile(reloaded)).isEqualTo(3)
        assertThat(selection.size).isEqualTo(2)
        assertThat(selection.isSelected(reloaded[0])).isTrue()
        assertThat(selection.isSelected(reloaded[1])).isTrue()
        assertThat(selection.isSelected(reloaded[2])).isFalse()
    }

    @Test
    fun resolveReturnsOnlyCurrentUnchangedRowsForThisTab() {
        val selection = ClipboardSelection(ClipboardTab.HISTORY)
        selection.selectAll(listOf(row(1), row(2, content = "old"), row(3)))
        val current = listOf(row(1), row(2, content = "new"), row(4))

        val snapshot = selection.resolve(current)

        assertThat(snapshot.tab).isEqualTo(ClipboardTab.HISTORY)
        assertThat(snapshot.entries.map { it.rowId }).containsExactly(1L)
        // The snapshot carries the freshly loaded rows, so the database guard compares
        // against what is on screen now rather than against the selection-time version.
        assertThat(snapshot.entries.single()).isSameInstanceAs(current[0])
        assertThat(snapshot.totalBytes).isEqualTo(10L)
    }

    @Test
    fun clearEmptiesTheSelection() {
        val selection = ClipboardSelection(ClipboardTab.HISTORY)
        selection.selectAll((1L..3L).map { row(it) })
        selection.clear()
        assertThat(selection.size).isEqualTo(0)
        assertThat(selection.resolve((1L..3L).map { row(it) }).entries).isEmpty()
    }

    @Test
    fun theModelRetainsIdsAndVersionsButNeverClippingContent() {
        // Memory bound: one id and one version per selected row. Holding entries would pin
        // every selected clipping (and its thumbnail) in the IME heap across searches.
        val forbidden = setOf(ClipboardEntry::class.java, String::class.java, ByteArray::class.java,
            List::class.java, Collection::class.java)
        for (field in ClipboardSelection::class.java.declaredFields) {
            assertThat(forbidden).doesNotContain(field.type)
        }
        val selection = ClipboardSelection(ClipboardTab.HISTORY)
        val huge = ClipboardEntry("x".repeat(100_000), 1, rowId = 7)
        selection.toggle(huge)
        assertThat(selection.selectedIds()).containsExactly(7L)
    }
}
