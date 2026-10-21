package tribixbite.cleverkeys.clipboard

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkClass
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
import tribixbite.cleverkeys.removeFromTab
import tribixbite.cleverkeys.clipboard.ClipboardPendingDeletion.CommitReason
import tribixbite.cleverkeys.clipboard.ClipboardPendingDeletion.Pending

/**
 * GH #175(2) wiring in the list view: a swiped row is hidden from the results (and their
 * count) on its own tab, Undo brings it back, the real delete goes through the same per-tab
 * service path as the edit-mode delete, and the view's own triggers — tab switch, a search or
 * filter change, selection mode, edit mode, a bulk action — commit the pending row first.
 * Real ClipboardHistoryView instance (Objenesis + reflection, the StateGuards idiom).
 */
class ClipboardSwipeDeleteViewTest {

    private val objenesis = ObjenesisStd()
    private lateinit var service: ClipboardHistoryService
    private lateinit var view: ClipboardHistoryView
    private lateinit var pending: ClipboardPendingDeletion
    private val committed = mutableListOf<Pair<Pending, CommitReason?>>()

    /** The timer never fires on its own here; tests commit through the triggers. */
    private val idleScheduler = ClipboardPendingDeletion.Scheduler { _, _ -> ClipboardPendingDeletion.Cancellable { } }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        service = mockk(relaxed = true)
    }

    @After
    fun teardown() = unmockkAll()

    private fun entry(content: String, ts: Long = 1_700_000_000_000L) =
        ClipboardEntry(content = content, timestamp = ts)

    private fun buildView(entries: List<ClipboardEntry>, tab: ClipboardTab = ClipboardTab.HISTORY) {
        view = spyk(objenesis.newInstance(ClipboardHistoryView::class.java))
        every { view.context } returns mockk(relaxed = true)
        every { view.invalidate() } just runs
        view.setField("service", service)
        view.setField("history", entries)
        view.setField("filteredHistory", entries)
        view.setField("paginatedHistory", entries)
        view.setField("currentTab", tab)
        view.setField("searchFilter", "")
        view.setField("statusFilterActive", true)
        view.setField("dataReady", true)
        view.setField("expandedStates", mutableMapOf<Long, Boolean>())
        view.setField("tagFilterSelected", emptySet<String>())
        val cacheClass = Class.forName("tribixbite.cleverkeys.ClipboardHistoryView\$thumbnailCache\$1")
        view.setField("thumbnailCache", mockkClass(cacheClass.kotlin, relaxed = true))
        view.setField("animatedCache", mutableMapOf<Long, Any>())
        val adapterClass = Class.forName("tribixbite.cleverkeys.ClipboardHistoryView\$ClipboardEntriesAdapter")
        val adapter = spyk(objenesis.newInstance(adapterClass) as android.widget.BaseAdapter)
        every { adapter.notifyDataSetChanged() } just runs
        adapter.setField("this\$0", view)
        view.setField("clipboardAdapter", adapter)

        // The manager owns the pending deletion and forwards its changes to the view; model
        // that here with a commit action that runs the same per-tab service path.
        pending = ClipboardPendingDeletion(idleScheduler, commitAction = { p ->
            committed += p to null
            service.removeFromTab(p.tab, p.content)  // static dispatch onto the per-tab members
        })
        pending.listener = { change, p -> view.onPendingDeletionChanged(change, p) }
        view.attachPendingDeletion(pending)
    }

    private fun visibleContents(): List<String> =
        @Suppress("UNCHECKED_CAST")
        (view.getField("paginatedHistory") as List<ClipboardEntry>).map { it.content }

    // ─────────────────────────────────────────────── hide, undo, commit

    @Test
    fun aDeferredDeleteHidesTheRowAndItsCountImmediately() {
        buildView(listOf(entry("a", 3), entry("b", 2), entry("c", 1)))

        assertThat(view.deferDelete(entry("b", 2))).isTrue()

        assertThat(visibleContents()).containsExactly("a", "c").inOrder()
        assertWithMessage("the result summary counts what is shown").that(view.resultSummary().first).isEqualTo(2)
        verifyNothingDeleted()
    }

    @Test
    fun undoBringsTheRowBackWithoutTouchingTheDatabase() {
        buildView(listOf(entry("a", 2), entry("b", 1)))
        view.deferDelete(entry("b", 1))

        assertThat(pending.undo()).isTrue()

        assertThat(visibleContents()).containsExactly("a", "b").inOrder()
        verifyNothingDeleted()
    }

    @Test
    fun aReloadDuringTheWindowKeepsTheRowHidden() {
        buildView(listOf(entry("a", 2), entry("b", 1)))
        view.deferDelete(entry("b", 1))

        view.acceptLoadedHistory(listOf(entry("a", 2), entry("b", 1), entry("new", 3)))

        assertThat(visibleContents()).containsExactly("a", "new")
    }

    @Test
    fun committingDeletesThroughTheTabsServicePathAndKeepsTheRowGone() {
        for (tab in ClipboardTab.values()) {
            buildView(listOf(entry("keep", 2), entry("gone", 1)), tab)
            view.deferDelete(entry("gone", 1))

            pending.commitNow(CommitReason.PANE_HIDDEN)

            verifyDeleted(tab, "gone", times = 1)
            assertWithMessage("$tab: no flash back before the reload lands")
                .that(visibleContents()).containsExactly("keep")
        }
    }

    @Test
    fun aRowOnAnotherTabWithTheSameContentIsNotHidden() {
        buildView(listOf(entry("same", 1)), ClipboardTab.HISTORY)
        pending.begin(ClipboardTab.PINNED, "same")  // the listener re-filters the view

        assertThat(visibleContents()).containsExactly("same")
    }

    // ─────────────────────────────────────────────── the view's commit triggers

    @Test
    fun switchingTabCommitsThePendingRowFirst() {
        buildView(listOf(entry("x", 1)))
        view.deferDelete(entry("x", 1))
        // loadDataAsync needs a window scope; with none attached it is a no-op.

        view.setTab(ClipboardTab.PINNED)

        verifyDeleted(ClipboardTab.HISTORY, "x", times = 1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.TAB_SWITCH)
    }

    @Test
    fun aSearchOrFilterChangeCommitsThePendingRowFirst() {
        buildView(listOf(entry("x", 2), entry("y", 1)))
        view.deferDelete(entry("x", 2))

        view.setSearchFilter("y")

        verifyDeleted(ClipboardTab.HISTORY, "x", times = 1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.FILTER_CHANGE)

        view.deferDelete(entry("y", 1))
        view.setPrivateOnlyFilter(true)
        verifyDeleted(ClipboardTab.HISTORY, "y", times = 1)
    }

    @Test
    fun startingSelectionModeCommitsThePendingRowFirst() {
        buildView(listOf(entry("x", 1)))
        view.attachSelectionHolder(ClipboardSelectionHolder())
        view.deferDelete(entry("x", 1))

        assertThat(view.startSelection()).isTrue()

        verifyDeleted(ClipboardTab.HISTORY, "x", times = 1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.SELECTION)
    }

    @Test
    fun enteringEditModeCommitsThePendingRowFirst() {
        buildView(listOf(entry("x", 2), entry("edit me", 1)))
        view.deferDelete(entry("x", 2))

        view.edit_entry(0)  // the visible list is now ["edit me"]

        verifyDeleted(ClipboardTab.HISTORY, "x", times = 1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.EDIT)
        assertThat(view.isEditing()).isTrue()
    }

    @Test
    fun aBulkActionCommitsThePendingRowFirst() {
        buildView(listOf(entry("x", 1)))
        // A holder whose action never runs: only the commit-before-start ordering is pinned.
        val holder = mockk<ClipboardSelectionHolder>(relaxed = true)
        every { holder.running } returns false
        every { holder.selection } returns null
        every { holder.runConfirmed<Int>(any(), any()) } returns true
        view.setField("holder", holder)
        view.deferDelete(entry("x", 1))

        view.deleteSnapshot(tribixbite.cleverkeys.ClipboardDeleteSnapshot(ClipboardTab.HISTORY, emptyList())) {}

        verifyDeleted(ClipboardTab.HISTORY, "x", times = 1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.BULK_ACTION)
    }

    // ─────────────────────────────────────────────── refusals

    @Test
    fun deferredDeleteIsRefusedInSelectionAndEditMode() {
        buildView(listOf(entry("x", 2), entry("y", 1)))
        view.attachSelectionHolder(ClipboardSelectionHolder())
        view.startSelection()
        assertThat(view.deferDelete(entry("x", 2))).isFalse()
        view.endSelection()

        view.edit_entry(1)
        assertThat(view.deferDelete(entry("x", 2))).isFalse()
        assertThat(pending.pending).isNull()
    }

    @Test
    fun theEditModeDeleteUsesTheSameServicePath() {
        buildView(listOf(entry("x", 1)), ClipboardTab.TODOS)

        view.delete_entry(0)

        verifyDeleted(ClipboardTab.TODOS, "x", times = 1)
    }

    /** The per-tab service member the row's own delete uses ran [times] for [content]. */
    private fun verifyDeleted(tab: ClipboardTab, content: String, times: Int = 1) {
        when (tab) {
            ClipboardTab.HISTORY -> verify(exactly = times) { service.removeHistoryEntry(content) }
            ClipboardTab.PINNED -> verify(exactly = times) { service.unpinEntry(content) }
            ClipboardTab.TODOS -> verify(exactly = times) { service.removeFromTodo(content) }
        }
    }

    private fun verifyNothingDeleted() {
        verify(exactly = 0) { service.removeHistoryEntry(any()) }
        verify(exactly = 0) { service.unpinEntry(any()) }
        verify(exactly = 0) { service.removeFromTodo(any()) }
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage("field '$name' not found on ${javaClass.simpleName}").that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }

    private fun Any.getField(name: String): Any? {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage("field '$name' not found on ${javaClass.simpleName}").that(field).isNotNull()
        field!!.isAccessible = true
        return field.get(this)
    }
}
