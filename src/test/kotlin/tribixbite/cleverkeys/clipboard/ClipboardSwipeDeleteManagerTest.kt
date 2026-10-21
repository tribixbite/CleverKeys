package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardHistoryView
import tribixbite.cleverkeys.ClipboardManager
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.removeFromTab
import tribixbite.cleverkeys.clipboard.ClipboardPendingDeletion.CommitReason
import tribixbite.cleverkeys.clipboard.ClipboardPendingDeletion.Pending

/**
 * GH #175(2) wiring in the service-scoped ClipboardManager: the pending deletion lives here
 * (never on a view), the result row shows "Deleted" plus an Undo button for the window, Undo
 * restores, and the pane-level triggers — pane close / keyboard hide, pane invalidation,
 * service cleanup, tab switch, the tag panel, selection mode — commit the pending row
 * IMMEDIATELY. Real manager bodies, mocked views (Objenesis, the ClipboardTabsAndPaneClose idiom).
 */
class ClipboardSwipeDeleteManagerTest {

    private val objenesis = ObjenesisStd()
    private lateinit var mgr: ClipboardManager
    private lateinit var listView: ClipboardHistoryView
    private lateinit var feedback: TextView
    private lateinit var undo: Button
    private lateinit var pending: ClipboardPendingDeletion
    private val committed = mutableListOf<Pending>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        listView = mockk(relaxed = true)
        every { listView.isEditing() } returns false
        every { listView.isSelecting() } returns false
        every { listView.hasActiveFilters() } returns false
        every { listView.resultSummary() } returns (0 to 0L)
        every { listView.isResultsReady() } returns false
        feedback = mockk(relaxed = true)
        undo = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.clipboard_swipe_deleted) } returns "Deleted"

        mgr = objenesis.newInstance(ClipboardManager::class.java)
        mgr.setField("context", context)
        mgr.setField("clipboardHistoryView", listView)
        mgr.setField("bulkFeedback", feedback)
        mgr.setField("swipeUndoButton", undo)
        mgr.setField("tabHistory", mockk<ImageView>(relaxed = true))
        mgr.setField("tabPinned", mockk<ImageView>(relaxed = true))
        mgr.setField("tabTodos", mockk<ImageView>(relaxed = true))
        mgr.setField("currentTab", ClipboardTab.HISTORY)
        mgr.setField("tagMode", false)
        mgr.setField("config", objenesis.newInstance(Config::class.java))

        pending = ClipboardPendingDeletion(
            scheduler = { _, _ -> ClipboardPendingDeletion.Cancellable { } },
            commitAction = { committed += it },
        )
        mgr.setField("pendingDeletionField", pending)
        mgr.invokePrivate("wirePendingDeletion", pending)
    }

    @After
    fun teardown() = unmockkAll()

    private fun swipe(content: String = "row", tab: ClipboardTab = ClipboardTab.HISTORY) =
        pending.begin(tab, content)

    // ─────────────────────────────────────────────── feedback + undo

    @Test
    fun aSwipedRowShowsDeletedWithAnUndoButtonAndForwardsToTheView() {
        swipe()

        verify { feedback.text = "Deleted" }
        verify { feedback.visibility = View.VISIBLE }
        verify { undo.visibility = View.VISIBLE }
        verify { listView.onPendingDeletionChanged(ClipboardPendingDeletion.Change.HIDDEN, Pending(ClipboardTab.HISTORY, "row")) }
    }

    @Test
    fun undoRestoresAndHidesTheFeedback() {
        swipe()

        mgr.undoSwipeDelete()

        assertThat(pending.pending).isNull()
        assertThat(committed).isEmpty()
        verify { undo.visibility = View.GONE }
        verify { feedback.visibility = View.GONE }
        verify { listView.onPendingDeletionChanged(ClipboardPendingDeletion.Change.RESTORED, Pending(ClipboardTab.HISTORY, "row")) }
    }

    @Test
    fun theUndoButtonIsWiredToUndo() {
        val click = slot<View.OnClickListener>()
        every { undo.setOnClickListener(capture(click)) } returns Unit
        mgr.invokePrivate("bindSwipeUndoButton")
        swipe()

        click.captured.onClick(undo)

        assertThat(pending.pending).isNull()
        assertThat(committed).isEmpty()
    }

    @Test
    fun theWindowEndingHidesTheFeedback() {
        swipe()

        pending.commitNow(CommitReason.TIMEOUT)

        assertThat(committed).containsExactly(Pending(ClipboardTab.HISTORY, "row"))
        verify { undo.visibility = View.GONE }
    }

    // ─────────────────────────────────────────────── pane-level triggers

    @Test
    fun paneCloseAndKeyboardHideCommitImmediately() {
        swipe()

        mgr.resetSearchOnHide()

        assertThat(committed).hasSize(1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.PANE_HIDDEN)
    }

    @Test
    fun paneInvalidationCommitsImmediately() {
        swipe()

        mgr.invokePrivate("invalidatePane")

        assertThat(committed).hasSize(1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.PANE_INVALIDATED)
    }

    @Test
    fun serviceCleanupCommitsImmediately() {
        swipe()

        mgr.cleanup()

        assertThat(committed).hasSize(1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.PANE_INVALIDATED)
    }

    @Test
    fun aTabSwitchCommitsImmediately() {
        swipe()

        mgr.invokePrivate("switchToTab", ClipboardTab.PINNED)

        assertThat(committed).hasSize(1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.TAB_SWITCH)
    }

    @Test
    fun openingTheTagPanelCommitsImmediately() {
        swipe()

        mgr.invokePrivate("showTagPanel", ClipboardEntry(content = "other", timestamp = 1L), ClipboardTab.PINNED)

        assertThat(committed).hasSize(1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.TAG_PANEL)
    }

    @Test
    fun enteringSelectionModeCommitsImmediately() {
        swipe()

        mgr.invokePrivate("enterSelectionMode")

        assertThat(committed).hasSize(1)
        assertThat(pending.lastCommitReason).isEqualTo(CommitReason.SELECTION)
    }

    @Test
    fun theServiceCommitRoutesPerTabLikeTheEditModeDelete() {
        val service = io.mockk.spyk(objenesis.newInstance(ClipboardHistoryService::class.java))
        every { service.removeHistoryEntry(any()) } returns Unit
        every { service.unpinEntry(any()) } returns Unit
        every { service.removeFromTodo(any()) } returns Unit

        service.removeFromTab(ClipboardTab.HISTORY, "h")
        service.removeFromTab(ClipboardTab.PINNED, "p")
        service.removeFromTab(ClipboardTab.TODOS, "t")

        verify(exactly = 1) { service.removeHistoryEntry("h") }
        verify(exactly = 1) { service.unpinEntry("p") }
        verify(exactly = 1) { service.removeFromTodo("t") }
        verify(exactly = 0) { service.removeHistoryEntry("p") }
        verify(exactly = 0) { service.removeHistoryEntry("t") }
    }

    @Test
    fun thePendingStateIsOwnedByTheManagerNotTheView() {
        val viewFields = ClipboardHistoryView::class.java.declaredFields
            .filter { it.type == ClipboardPendingDeletion::class.java }
        val managerFields = ClipboardManager::class.java.declaredFields
            .filter { it.type == ClipboardPendingDeletion::class.java }
        assertWithMessage("the manager (service lifetime) creates and keeps it")
            .that(managerFields.map { it.name }).contains("pendingDeletionField")
        assertWithMessage("the view only holds a reference attached by the manager")
            .that(viewFields.map { it.name }).containsExactly("pendingDeletion")
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage("field '$name' not found on ${javaClass.simpleName}").that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }

    private fun Any.invokePrivate(name: String, vararg args: Any) {
        val method = javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }
        assertWithMessage("method '$name' not found on ${javaClass.simpleName}").that(method).isNotNull()
        method!!.isAccessible = true
        method.invoke(this, *args)
    }
}
