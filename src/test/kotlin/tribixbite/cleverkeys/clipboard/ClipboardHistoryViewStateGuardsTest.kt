package tribixbite.cleverkeys.clipboard

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardHistoryView
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.EditEntryResult

/**
 * 2026-09-06 comprehensive-audit fixes D-10, D-3 and D-7 — state-level guards on
 * ClipboardHistoryView, driven directly on a real instance (Objenesis + reflection,
 * the ClipboardMediaDeleteAffordanceTest idiom) without inflating any row views.
 *
 * D-10 — row-action handlers must bounds-guard `paginatedHistory[pos]`: an async reload
 *   (expiry cleanup, foreign delete, filter change) can shrink the paginated list between
 *   a row's render and its click dispatch; a tap on a formerly-last row then arrives with
 *   a stale out-of-range position. `edit_entry` always had the guard — pin/todo/delete/
 *   paste threw IndexOutOfBoundsException and crashed the IME.
 *
 * D-3 — `hasActiveFilters()` must report the TODOS status filter truthfully: the default
 *   state (active=T, planned=F, completed=F) HIDES planned/completed rows — applyFilter's
 *   own hasStatusFilter calls it a filter — yet hasActiveFilters() returned false for
 *   exactly that state, leaving the filter icon untinted while data was hidden (cycling a
 *   todo to planned made it vanish looking like deletion).
 *
 * D-7 — `save_edit()` on a non-Success result must KEEP the edit session: it used to
 *   Toast (invisible inside the IME window — see ime-visual-feedback.md) and then
 *   unconditionally cancelEdit(), silently discarding the user's in-progress edit.
 */
class ClipboardHistoryViewStateGuardsTest {

    private val objenesis = ObjenesisStd()

    private lateinit var service: ClipboardHistoryService
    private lateinit var view: ClipboardHistoryView

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        service = mockk(relaxed = true)
    }

    @After
    fun teardown() = unmockkAll()

    private fun buildView(
        entries: List<ClipboardEntry> = emptyList(),
        tab: ClipboardTab = ClipboardTab.HISTORY,
    ) {
        view = spyk(objenesis.newInstance(ClipboardHistoryView::class.java))
        every { view.context } returns mockk(relaxed = true)
        view.setField("service", service)
        view.setField("paginatedHistory", entries)
        view.setField("currentTab", tab)
        // Objenesis skips initializers — restore the collection fields the code paths touch.
        view.setField("expandedStates", mutableMapOf<Long, Boolean>())
        view.setField("tagFilterSelected", emptySet<String>())
        // A real inner adapter whose notifyDataSetChanged is stubbed (the BaseAdapter
        // observable was never constructed under Objenesis).
        val adapterClass = Class.forName(
            "tribixbite.cleverkeys.ClipboardHistoryView\$ClipboardEntriesAdapter"
        )
        val adapter = spyk(objenesis.newInstance(adapterClass) as android.widget.BaseAdapter)
        every { adapter.notifyDataSetChanged() } just runs
        adapter.setField("this\$0", view)
        view.setField("clipboardAdapter", adapter)
    }

    private fun entry(content: String, ts: Long = 1700000000000L) =
        ClipboardEntry(content = content, timestamp = ts)

    // ─────────────────────────────────────────────── D-10: stale-position clicks

    @Test
    fun pinEntryWithStalePositionReturnsCleanlyWithoutServiceCall() {
        buildView(entries = listOf(entry("only")), tab = ClipboardTab.HISTORY)

        view.pin_entry(1)  // list shrank to 1 between render and click dispatch

        verify(exactly = 0) { service.pinEntry(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { service.unpinEntry(any()) }
    }

    @Test
    fun todoEntryWithStalePositionReturnsCleanlyWithoutServiceCall() {
        buildView(entries = listOf(entry("only")), tab = ClipboardTab.HISTORY)

        view.todo_entry(1)

        verify(exactly = 0) { service.addToTodo(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { service.removeFromTodo(any()) }
    }

    @Test
    fun deleteEntryWithStalePositionReturnsCleanlyWithoutServiceCall() {
        buildView(entries = listOf(entry("only")), tab = ClipboardTab.HISTORY)

        view.delete_entry(1)

        verify(exactly = 0) { service.removeHistoryEntry(any()) }
        verify(exactly = 0) { service.unpinEntry(any()) }
        verify(exactly = 0) { service.removeFromTodo(any()) }
    }

    @Test
    fun pasteEntryWithStalePositionReturnsCleanly() {
        buildView(entries = emptyList(), tab = ClipboardTab.HISTORY)

        // Red today: IndexOutOfBoundsException from paginatedHistory[0].
        view.paste_entry(0)
    }

    @Test
    fun deleteEntryWithValidPositionStillRoutesToTheService() {
        // Guard must not swallow legitimate clicks.
        buildView(entries = listOf(entry("victim")), tab = ClipboardTab.HISTORY)

        view.delete_entry(0)

        verify(exactly = 1) { service.removeHistoryEntry("victim") }
    }

    // ────────────────────────────────── D-3: status-filter tint tells the truth

    private fun buildTodoViewWithStatusFilter(active: Boolean, planned: Boolean, completed: Boolean) {
        buildView(tab = ClipboardTab.TODOS)
        view.setField("statusFilterActive", active)
        view.setField("statusFilterPlanned", planned)
        view.setField("statusFilterCompleted", completed)
    }

    @Test
    fun defaultActiveOnlyStatusStateReportsAnActiveFilter() {
        // The default (active-only) state hides planned/completed rows — applyFilter's own
        // hasStatusFilter treats it as a filter, so the icon tint must agree.
        buildTodoViewWithStatusFilter(active = true, planned = false, completed = false)

        assertWithMessage(
            "active-only hides planned/completed todos; hasActiveFilters() must say so " +
                "or the untinted icon makes a status-cycled todo look deleted"
        ).that(view.hasActiveFilters()).isTrue()
    }

    @Test
    fun allThreeStatusesEnabledReportsNoFilter() {
        buildTodoViewWithStatusFilter(active = true, planned = true, completed = true)

        assertThat(view.hasActiveFilters()).isFalse()
    }

    @Test
    fun anyHiddenStatusReportsAnActiveFilter() {
        // Every non-show-all combination hides rows and must tint.
        for (mask in 0..6) {  // 7 == all three shown == the no-filter case
            buildTodoViewWithStatusFilter(
                active = mask and 4 != 0,
                planned = mask and 2 != 0,
                completed = mask and 1 != 0,
            )
            assertWithMessage("status combination mask=$mask hides at least one status")
                .that(view.hasActiveFilters()).isTrue()
        }
    }

    @Test
    fun statusStateNeverTintsOutsideTheTodosTab() {
        buildView(tab = ClipboardTab.HISTORY)
        view.setField("statusFilterActive", true)
        view.setField("statusFilterPlanned", false)
        view.setField("statusFilterCompleted", false)

        assertThat(view.hasActiveFilters()).isFalse()
    }

    // ──────────────────────── D-7: failed save keeps the edit session (no Toast)

    private fun buildEditingView(result: EditEntryResult?) {
        buildView(entries = listOf(entry("old")), tab = ClipboardTab.HISTORY)
        view.setField("editingOriginalContent", "old")
        view.setField("editingInProgressText", "new")
        every { service.editEntryContent("old", "new", ClipboardTab.HISTORY) } returns
            (result ?: EditEntryResult.Error("boom"))
    }

    @Test
    fun saveEditOnDuplicateConflictKeepsTheEditSessionOpen() {
        buildEditingView(EditEntryResult.DuplicateConflict)

        view.save_edit()

        assertWithMessage(
            "DuplicateConflict must not discard the user's in-progress edit — the old " +
                "path Toasted (invisible in the IME window) then cancelEdit()ed"
        ).that(view.isEditing()).isTrue()
    }

    @Test
    fun saveEditOnInvalidContentKeepsTheEditSessionOpen() {
        buildEditingView(EditEntryResult.InvalidContent)

        view.save_edit()

        assertThat(view.isEditing()).isTrue()
    }

    @Test
    fun saveEditOnDbErrorKeepsTheEditSessionOpen() {
        buildEditingView(EditEntryResult.Error("disk full"))

        view.save_edit()

        assertThat(view.isEditing()).isTrue()
    }

    @Test
    fun saveEditOnSuccessExitsEditMode() {
        buildEditingView(EditEntryResult.Success)

        view.save_edit()

        assertThat(view.isEditing()).isFalse()
    }

    // ------------------------------------------------------------------ helpers

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError(
            "field '$name' not found on ${javaClass.name} — renamed or removed; re-point this test"
        )
    }
}
