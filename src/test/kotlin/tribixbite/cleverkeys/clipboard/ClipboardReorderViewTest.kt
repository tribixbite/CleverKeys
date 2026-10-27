package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.text.Spannable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
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
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.TodoEntry
import tribixbite.cleverkeys.clipboard.ClipboardReorderPolicy.Availability
import tribixbite.cleverkeys.clipboard.ClipboardReorderPolicy.MoveAction

/**
 * Reordering Pinned and To-dos rows in the list view (2026-10-27): a move names the VISIBLE
 * neighbours to the service, the list shows the new order at once, moves wait while a search
 * or non-status filter is active, there is no handle on History, in Select mode or while
 * editing, and every row carries the TalkBack "Move up / Move down / Move to top" actions.
 * Real ClipboardHistoryView instance (Objenesis + reflection, the StateGuards idiom).
 */
class ClipboardReorderViewTest {

    private val objenesis = ObjenesisStd()
    private lateinit var service: ClipboardHistoryService
    private lateinit var view: ClipboardHistoryView
    private lateinit var adapter: android.widget.BaseAdapter
    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        setGlobalConfig(objenesis.newInstance(Config::class.java))
        service = mockk(relaxed = true)
        every { service.moveInTab(any(), any(), any(), any()) } returns true
        context = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        setGlobalConfig(null)
        unmockkAll()
    }

    private fun todo(content: String, id: Long, status: String = TodoEntry.STATUS_ACTIVE) =
        ClipboardEntry(content = content, timestamp = 1_700_000_000_000L + id, todoStatus = status, rowId = id)

    private fun pin(content: String, id: Long) =
        ClipboardEntry(content = content, timestamp = 1_700_000_000_000L + id, rowId = id)

    private fun buildView(entries: List<ClipboardEntry>, tab: ClipboardTab = ClipboardTab.TODOS) {
        view = spyk(objenesis.newInstance(ClipboardHistoryView::class.java))
        every { view.context } returns context
        every { view.invalidate() } just runs
        every { view.announceForAccessibility(any()) } just runs
        view.setField("service", service)
        view.setField("history", entries)
        view.setField("filteredHistory", entries)
        view.setField("paginatedHistory", entries)
        view.setField("currentTab", tab)
        view.setField("searchFilter", "")
        view.setField("statusFilterActive", true)
        view.setField("statusFilterPlanned", true)
        view.setField("statusFilterCompleted", true)
        view.setField("dataReady", true)
        view.setField("expandedStates", mutableMapOf<Long, Boolean>())
        view.setField("tagFilterSelected", emptySet<String>())
        val cacheClass = Class.forName("tribixbite.cleverkeys.ClipboardHistoryView\$thumbnailCache\$1")
        view.setField("thumbnailCache", mockkClass(cacheClass.kotlin, relaxed = true))
        view.setField("animatedCache", mutableMapOf<Long, Boolean>())
        view.setField("deleteActionDelegate", mockk<View.AccessibilityDelegate>(relaxed = true))
        val adapterClass = Class.forName("tribixbite.cleverkeys.ClipboardHistoryView\$ClipboardEntriesAdapter")
        adapter = spyk(objenesis.newInstance(adapterClass) as android.widget.BaseAdapter)
        every { adapter.notifyDataSetChanged() } just runs
        adapter.setField("this\$0", view)
        view.setField("clipboardAdapter", adapter)
    }

    private fun visible(): List<String> =
        @Suppress("UNCHECKED_CAST")
        (view.getField("paginatedHistory") as List<ClipboardEntry>).map { it.content }

    // ─────────────────────────────────────────────── moves

    @Test
    fun aMoveNamesTheVisibleNeighboursAndShowsTheNewOrderAtOnce() {
        val a = todo("a", 1); val b = todo("b", 2); val c = todo("c", 3)
        buildView(listOf(a, b, c))

        assertThat(view.moveEntryTo(c, 0)).isTrue()

        verify(exactly = 1) { service.moveInTab(ClipboardTab.TODOS, c, null, a) }
        assertThat(visible()).containsExactly("c", "a", "b").inOrder()

        assertThat(view.moveEntryTo(c, 1)).isTrue()
        verify(exactly = 1) { service.moveInTab(ClipboardTab.TODOS, c, a, b) }
        assertThat(visible()).containsExactly("a", "c", "b").inOrder()
    }

    @Test
    fun pinnedRowsMoveThroughTheSamePath() {
        val a = pin("a", 1); val b = pin("b", 2)
        buildView(listOf(a, b), ClipboardTab.PINNED)

        assertThat(view.moveEntryTo(a, 1)).isTrue()

        verify(exactly = 1) { service.moveInTab(ClipboardTab.PINNED, a, b, null) }
        assertThat(visible()).containsExactly("b", "a").inOrder()
    }

    @Test
    fun withTheDefaultActiveOnlyFilterMovesUseTheActiveNeighboursAndKeepStatuses() {
        val a = todo("a", 1); val p = todo("p", 2, TodoEntry.STATUS_PLANNED); val b = todo("b", 3)
        buildView(listOf(a, p, b))
        view.setStatusFilter(active = true, planned = false, completed = false)
        assertThat(visible()).containsExactly("a", "b").inOrder()
        assertWithMessage("the status filter does not block reordering")
            .that(view.reorderAvailability()).isEqualTo(Availability.AVAILABLE)

        assertThat(view.moveEntryTo(b, 0)).isTrue()

        verify(exactly = 1) { service.moveInTab(ClipboardTab.TODOS, b, null, a) }
        verify(exactly = 0) { service.setTodoStatus(any(), any()) }
        assertThat(visible()).containsExactly("b", "a").inOrder()
        view.setStatusFilter(active = true, planned = true, completed = true)
        assertWithMessage("the hidden planned row kept its place after a")
            .that(visible()).containsExactly("b", "a", "p").inOrder()
    }

    @Test
    fun aRefusedMoveLeavesTheOrderAlone() {
        every { service.moveInTab(any(), any(), any(), any()) } returns false
        val a = todo("a", 1); val b = todo("b", 2)
        buildView(listOf(a, b))

        assertThat(view.moveEntryTo(b, 0)).isFalse()

        assertThat(visible()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun droppingARowWhereItAlreadyIsWritesNothing() {
        val a = todo("a", 1); val b = todo("b", 2)
        buildView(listOf(a, b))

        assertThat(view.moveEntryTo(a, 0)).isFalse()

        verify(exactly = 0) { service.moveInTab(any(), any(), any(), any()) }
    }

    // ─────────────────────────────────────────────── when reordering is offered

    @Test
    fun aSearchOrANonStatusFilterPausesReordering() {
        val a = todo("apple", 1); val b = todo("banana", 2); val c = todo("cherry", 3)
        buildView(listOf(a, b, c))

        view.setSearchFilter("an")
        assertThat(view.reorderAvailability()).isEqualTo(Availability.FILTERED)
        assertThat(view.moveEntryTo(b, 0)).isFalse()
        view.setSearchFilter("")

        view.setTagFilter(setOf("work"), matchAll = false)
        assertThat(view.reorderAvailability()).isEqualTo(Availability.FILTERED)
        view.setTagFilter(emptySet(), matchAll = false)

        view.setPrivateOnlyFilter(true)
        assertThat(view.reorderAvailability()).isEqualTo(Availability.FILTERED)
        view.setPrivateOnlyFilter(false)

        view.setDateFilter(1L, isBefore = false)
        assertThat(view.reorderAvailability()).isEqualTo(Availability.FILTERED)
        view.clearDateFilter()

        view.setSizeFilter(1L, null)
        assertThat(view.reorderAvailability()).isEqualTo(Availability.FILTERED)
        view.setSizeFilter(0L, null)

        assertThat(view.reorderAvailability()).isEqualTo(Availability.AVAILABLE)
        verify(exactly = 0) { service.moveInTab(any(), any(), any(), any()) }
    }

    @Test
    fun historySelectionAndEditModeHaveNoReordering() {
        buildView(listOf(pin("a", 1), pin("b", 2)), ClipboardTab.HISTORY)
        assertThat(view.reorderAvailability()).isEqualTo(Availability.HIDDEN)
        assertThat(view.moveEntryTo(pin("b", 2), 0)).isFalse()

        val a = todo("a", 1); val b = todo("b", 2)
        buildView(listOf(a, b))
        view.attachSelectionHolder(ClipboardSelectionHolder())
        assertThat(view.startSelection()).isTrue()
        assertThat(view.reorderAvailability()).isEqualTo(Availability.HIDDEN)
        assertThat(view.moveEntryTo(b, 0)).isFalse()
        view.endSelection()

        view.edit_entry(0)
        assertThat(view.reorderAvailability()).isEqualTo(Availability.HIDDEN)
        assertThat(view.moveEntryTo(b, 0)).isFalse()
        verify(exactly = 0) { service.moveInTab(any(), any(), any(), any()) }
    }

    // ─────────────────────────────────────────────── TalkBack actions

    @Test
    fun rowsOfferTheMovesTheirPlaceAllowsAndTheyWork() {
        val a = todo("a", 1); val b = todo("b", 2); val c = todo("c", 3)
        buildView(listOf(a, b, c))

        assertThat(view.moveActionsFor(a)).containsExactly(MoveAction.DOWN)
        assertThat(view.moveActionsFor(c)).containsExactly(MoveAction.UP, MoveAction.TOP).inOrder()

        assertThat(view.performMoveAction(c, MoveAction.TOP)).isTrue()
        verify { service.moveInTab(ClipboardTab.TODOS, c, null, a) }
        assertThat(visible()).containsExactly("c", "a", "b").inOrder()

        assertThat(view.performMoveAction(c, MoveAction.DOWN)).isTrue()
        verify { service.moveInTab(ClipboardTab.TODOS, c, a, b) }
        assertThat(visible()).containsExactly("a", "c", "b").inOrder()

        assertThat(view.performMoveAction(c, MoveAction.UP)).isTrue()
        assertThat(visible()).containsExactly("c", "a", "b").inOrder()
        verify(atLeast = 1) { view.announceForAccessibility(any()) }
    }

    @Test
    fun noMoveActionsWhileFilteredOrSelecting() {
        val a = todo("a", 1); val b = todo("b", 2)
        buildView(listOf(a, b))
        view.setSearchFilter("a")
        assertThat(view.moveActionsFor(a)).isEmpty()
        view.setSearchFilter("")

        view.attachSelectionHolder(ClipboardSelectionHolder())
        view.startSelection()
        assertThat(view.moveActionsFor(a)).isEmpty()
        assertThat(view.performMoveAction(b, MoveAction.UP)).isFalse()
    }

    @Test
    fun theAccessibilityActionIdsMapToTheMoves() {
        buildView(listOf(todo("a", 1), todo("b", 2), todo("c", 3)))
        val host = mockk<View>(relaxed = true)
        every { host.getTag(R.id.clipboard_entry_drag_handle) } returns todo("b", 2)
        every { host.getTag(R.id.clipboard_entry_delete) } returns null

        val ids = view.rowAccessibilityActions(host).map { it.first }

        assertThat(ids).containsExactly(R.id.clipboard_entry_move_up, R.id.clipboard_entry_move_down).inOrder()
        assertThat(ClipboardHistoryView.moveActionForId(R.id.clipboard_entry_move_top)).isEqualTo(MoveAction.TOP)
        assertThat(ClipboardHistoryView.moveActionForId(R.id.clipboard_entry_delete)).isNull()
    }

    // ─────────────────────────────────────────────── the manager's hint line

    @Test
    fun theFilterHintLeavesTheResultRowOnceReorderingIsAvailableAgain() {
        // Saga 2026-10-27: after clearing the search, "Clear the search and filters to reorder"
        // stayed in the result row although the handle worked again.
        val list = mockk<ClipboardHistoryView>(relaxed = true)
        every { list.isSelecting() } returns false
        every { list.resultSummary() } returns (0 to 0L)
        every { list.reorderAvailability() } returns Availability.FILTERED
        every { context.getString(R.string.clipboard_reorder_unavailable) } returns "hint"
        val mgr = objenesis.newInstance(tribixbite.cleverkeys.ClipboardManager::class.java)
        mgr.setField("context", context)
        mgr.setField("clipboardHistoryView", list)
        mgr.setField("bulkFeedback", mockk<TextView>(relaxed = true))
        mgr.setField("tabHistory", mockk<ImageView>(relaxed = true))
        mgr.setField("tabPinned", mockk<ImageView>(relaxed = true))
        mgr.setField("tabTodos", mockk<ImageView>(relaxed = true))
        mgr.setField("currentTab", ClipboardTab.TODOS)
        mgr.setField("tagMode", false)
        mgr.setField("config", objenesis.newInstance(Config::class.java))
        val update = mgr.javaClass.getDeclaredMethod("updateResultSummary").apply { isAccessible = true }

        mgr.showReorderUnavailableHint()
        update.invoke(mgr)
        assertWithMessage("still filtered: the hint stays").that(mgr.bulkFeedbackText()?.toString()).isEqualTo("hint")

        every { list.reorderAvailability() } returns Availability.AVAILABLE
        update.invoke(mgr)
        assertThat(mgr.bulkFeedbackText()).isNull()
    }

    // ─────────────────────────────────────────────── the drag handle in getView

    @Test
    fun theHandleIsShownDimmedOrHiddenToMatchAvailability() {
        val a = todo("a", 1); val b = todo("b", 2)

        buildView(listOf(a, b))
        val shown = renderHandle()
        verify { shown.visibility = View.VISIBLE }
        verify { shown.alpha = 1f }

        buildView(listOf(a, b))
        view.setSearchFilter("a")
        val dimmed = renderHandle()
        verify { dimmed.visibility = View.VISIBLE }
        verify { dimmed.alpha = ClipboardHistoryView.REORDER_HANDLE_DIMMED_ALPHA }

        buildView(listOf(a, b))
        view.attachSelectionHolder(ClipboardSelectionHolder())
        view.startSelection()
        val selecting = renderHandle()
        verify { selecting.visibility = View.GONE }

        buildView(listOf(pin("a", 1)), ClipboardTab.HISTORY)
        val history = renderHandle()
        verify { history.visibility = View.GONE }
    }

    /** Bind row 0 into a relaxed row whose widgets are mocks; returns the handle mock. */
    private fun renderHandle(): View {
        val row = mockk<View>(relaxed = true)
        val handle = mockk<View>(relaxed = true)
        every { row.findViewById<View>(R.id.clipboard_entry_drag_handle) } returns handle
        every { row.findViewById<View?>(R.id.clipboard_entry_drag_handle) } returns handle
        every { row.findViewById<TextView>(R.id.clipboard_entry_text) } returns mockk(relaxed = true)
        every { row.findViewById<EditText>(R.id.clipboard_entry_edit_field) } returns mockk(relaxed = true)
        every { row.findViewById<TextView>(R.id.clipboard_entry_edit_error) } returns mockk(relaxed = true)
        every { row.findViewById<TextView>(R.id.clipboard_entry_private_badge) } returns mockk(relaxed = true)
        every { row.findViewById<TextView>(R.id.clipboard_entry_provenance) } returns mockk(relaxed = true)
        every { row.findViewById<CheckBox>(R.id.clipboard_entry_select) } returns mockk(relaxed = true)
        every { row.findViewById<FrameLayout>(R.id.clipboard_entry_thumbnail_container) } returns mockk(relaxed = true)
        every { row.findViewById<ImageView>(R.id.clipboard_entry_thumbnail) } returns mockk(relaxed = true)
        every { row.findViewById<ImageView>(R.id.clipboard_entry_play_badge) } returns mockk(relaxed = true)
        for (id in intArrayOf(
            R.id.clipboard_entry_primary_buttons, R.id.clipboard_entry_edit_buttons,
            R.id.clipboard_entry_delete_row, R.id.clipboard_entry_secondary_buttons,
        )) {
            every { row.findViewById<LinearLayout>(id) } returns mockk(relaxed = true)
        }
        @Suppress("UNCHECKED_CAST")
        val entries = view.getField("paginatedHistory") as List<ClipboardEntry>
        val spied = entries.map { e ->
            spyk(e) {
                every { getFormattedText(any(), any(), any()) } returns mockk<Spannable>(relaxed = true)
                every { getFormattedTodoText(any(), any(), any(), any(), any()) } returns mockk<Spannable>(relaxed = true)
            }
        }
        view.setField("paginatedHistory", spied)
        val tag = slot<Any>()
        every { handle.setTag(R.id.clipboard_entry_drag_handle, capture(tag)) } just runs
        adapter.getView(0, row, mockk<ViewGroup>(relaxed = true))
        return handle
    }

    private fun setGlobalConfig(value: Config?) {
        val field = Config::class.java.getDeclaredField("_globalConfig")
        field.isAccessible = true
        field.set(null, value)
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
            ?: javaClass.superclass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage("field '$name' not found on ${javaClass.simpleName}").that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }

    private fun Any.getField(name: String): Any? {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
            ?: javaClass.superclass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage("field '$name' not found on ${javaClass.simpleName}").that(field).isNotNull()
        field!!.isAccessible = true
        return field.get(this)
    }
}
