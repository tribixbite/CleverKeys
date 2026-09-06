package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.text.Spannable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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

/**
 * Maintainer-reported bug (2026-09, no GH issue): MEDIA clipboard entries had NO UI path to
 * deletion. The delete button lives in `clipboard_entry_delete_row`, which was VISIBLE only
 * during inline edit mode — and media rows cannot enter edit mode (`edit_entry` returns early
 * for `entry.isMedia`, by design: there is no inline editor for an image). Net effect: a media
 * row could be pinned, todo'd, tagged and pasted, but never deleted except by clearing the
 * whole history or waiting for TTL expiry.
 *
 * The fix keeps the pane's existing idiom — a row's extra actions live behind tap-expansion —
 * and, for media rows only, shows the delete row when the row is expanded (deletion needs no
 * edit precondition). Media rows also gain the expand chevron (previously
 * `isMultiLine && !entry.isMedia`-gated) so the expansion — and with it delete, provenance and
 * the secondary pin/todo/tag actions — is discoverable, and the thumbnail becomes a tap target
 * for the same toggle.
 *
 * These tests drive the REAL `ClipboardEntriesAdapter.getView` body (Objenesis + MockK spy, the
 * `ClipboardTabsAndPaneCloseTest` idiom) with a mocked recycled row view, then invoke the
 * captured click listeners against the real `delete_entry` routing.
 */
class ClipboardMediaDeleteAffordanceTest {

    private val objenesis = ObjenesisStd()

    private lateinit var service: ClipboardHistoryService
    private lateinit var view: ClipboardHistoryView
    private lateinit var adapter: android.widget.BaseAdapter

    // Row-view widget mocks, re-created per test
    private lateinit var textView: TextView
    private lateinit var editField: EditText
    private lateinit var expandButton: View
    private lateinit var editButton: View
    private lateinit var primaryButtons: LinearLayout
    private lateinit var editButtons: LinearLayout
    private lateinit var deleteRow: LinearLayout
    private lateinit var secondaryButtons: LinearLayout
    private lateinit var thumbnailContainer: FrameLayout
    private lateinit var thumbnailView: ImageView
    private lateinit var playBadge: ImageView
    private lateinit var privateBadge: TextView
    private lateinit var provenanceView: TextView
    private lateinit var deleteButton: View
    private lateinit var rowView: View
    private lateinit var parent: ViewGroup

    private val deleteClick = slot<View.OnClickListener>()
    private val thumbnailClick = slot<View.OnClickListener>()

    private val expandedStates = mutableMapOf<Long, Boolean>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        // getView reads Config.globalConfig() for the pinned/todo button gates. An
        // Objenesis Config (all fields at JVM defaults) is enough — the gates are not
        // under test here.
        setGlobalConfig(objenesis.newInstance(Config::class.java))

        service = mockk(relaxed = true)
        buildRowViewMocks()
    }

    @After
    fun teardown() {
        setGlobalConfig(null)
        unmockkAll()
    }

    // ------------------------------------------------------------------ fixtures

    private fun mediaEntry(
        content: String = "IMG_1234.png",
        timestamp: Long = 1700000000123L,
        mime: String = "image/png",
    ): ClipboardEntry = spyk(
        ClipboardEntry(content = content, timestamp = timestamp, mimeType = mime)
    ) { every { getFormattedText(any()) } returns mockk<Spannable>(relaxed = true) }

    private fun textEntry(
        content: String = "plain text entry",
        timestamp: Long = 1700000000456L,
    ): ClipboardEntry = spyk(
        ClipboardEntry(content = content, timestamp = timestamp)
    ) { every { getFormattedText(any()) } returns mockk<Spannable>(relaxed = true) }

    /**
     * A real ClipboardHistoryView (constructor skipped) with only the fields getView /
     * delete_entry touch. `context` is stubbed on the spy because the android.jar stub
     * View.getContext() throws.
     */
    private fun buildView(
        entries: List<ClipboardEntry>,
        tab: ClipboardTab,
        editingContent: String? = null,
    ) {
        view = spyk(objenesis.newInstance(ClipboardHistoryView::class.java))
        every { view.context } returns mockk<Context>(relaxed = true)
        view.setField("service", service)
        view.setField("paginatedHistory", entries)
        view.setField("expandedStates", expandedStates)
        view.setField("currentTab", tab)
        view.setField("editingOriginalContent", editingContent)

        val adapterClass = Class.forName(
            "tribixbite.cleverkeys.ClipboardHistoryView\$ClipboardEntriesAdapter"
        )
        adapter = spyk(
            objenesis.newInstance(adapterClass) as android.widget.BaseAdapter,
            recordPrivateCalls = true
        )
        every { adapter.notifyDataSetChanged() } just runs
        adapter.setField("this\$0", view)
    }

    private fun buildRowViewMocks() {
        textView = mockk(relaxed = true)
        editField = mockk(relaxed = true)
        expandButton = mockk(relaxed = true)
        editButton = mockk(relaxed = true)
        primaryButtons = mockk(relaxed = true)
        editButtons = mockk(relaxed = true)
        deleteRow = mockk(relaxed = true)
        secondaryButtons = mockk(relaxed = true)
        thumbnailContainer = mockk(relaxed = true)
        thumbnailView = mockk(relaxed = true)
        playBadge = mockk(relaxed = true)
        privateBadge = mockk(relaxed = true)
        provenanceView = mockk(relaxed = true)
        deleteButton = mockk(relaxed = true)
        parent = mockk(relaxed = true)

        every { deleteButton.setOnClickListener(capture(deleteClick)) } just runs
        every { thumbnailContainer.setOnClickListener(capture(thumbnailClick)) } just runs

        rowView = mockk(relaxed = true)
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_text) } returns textView
        every { rowView.findViewById<EditText>(R.id.clipboard_entry_edit_field) } returns editField
        every { rowView.findViewById<View>(R.id.clipboard_entry_expand) } returns expandButton
        every { rowView.findViewById<View>(R.id.clipboard_entry_edit) } returns editButton
        every { rowView.findViewById<LinearLayout>(R.id.clipboard_entry_primary_buttons) } returns primaryButtons
        every { rowView.findViewById<LinearLayout>(R.id.clipboard_entry_edit_buttons) } returns editButtons
        every { rowView.findViewById<LinearLayout>(R.id.clipboard_entry_delete_row) } returns deleteRow
        every { rowView.findViewById<LinearLayout>(R.id.clipboard_entry_secondary_buttons) } returns secondaryButtons
        every { rowView.findViewById<FrameLayout>(R.id.clipboard_entry_thumbnail_container) } returns thumbnailContainer
        every { rowView.findViewById<ImageView>(R.id.clipboard_entry_thumbnail) } returns thumbnailView
        every { rowView.findViewById<ImageView>(R.id.clipboard_entry_play_badge) } returns playBadge
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_private_badge) } returns privateBadge
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_provenance) } returns provenanceView
        every { rowView.findViewById<View>(R.id.clipboard_entry_delete) } returns deleteButton
        // Remaining action buttons: plain relaxed mocks, no capture needed
        for (id in intArrayOf(
            R.id.clipboard_entry_paste, R.id.clipboard_entry_addpin, R.id.clipboard_entry_unpin,
            R.id.clipboard_entry_addtodo, R.id.clipboard_entry_done, R.id.clipboard_entry_status,
            R.id.clipboard_entry_tags, R.id.clipboard_entry_save, R.id.clipboard_entry_cancel,
        )) {
            every { rowView.findViewById<View>(id) } returns mockk<View>(relaxed = true)
        }
    }

    private fun render(pos: Int = 0): View = adapter.getView(pos, rowView, parent)

    // -------------------------------------------------- the reported bug: media delete

    @Test
    fun expandedMediaRowShowsDeleteAndTapDeletesFromHistory() {
        val entry = mediaEntry()
        expandedStates[entry.timestamp] = true
        buildView(listOf(entry), ClipboardTab.HISTORY)

        render()

        verify { deleteRow.visibility = View.VISIBLE }
        assertWithMessage(
            "an expanded media row must wire the delete button — media rows cannot reach " +
                "edit mode, the only other surface that exposes delete"
        ).that(deleteClick.isCaptured).isTrue()

        deleteClick.captured.onClick(deleteButton)

        verify(exactly = 1) { service.removeHistoryEntry(entry.content) }
    }

    @Test
    fun collapsedMediaRowKeepsDeleteHidden() {
        val entry = mediaEntry()
        buildView(listOf(entry), ClipboardTab.HISTORY)

        render()

        verify { deleteRow.visibility = View.GONE }
        verify(exactly = 0) { deleteRow.visibility = View.VISIBLE }
    }

    @Test
    fun mediaRowShowsTheExpandChevron() {
        // Discoverability half of the fix: without a chevron the media row gives no visual
        // cue that it expands at all, so the delete affordance stays effectively hidden.
        val entry = mediaEntry()
        buildView(listOf(entry), ClipboardTab.HISTORY)

        render()

        verify { expandButton.visibility = View.VISIBLE }
        verify(exactly = 0) { expandButton.visibility = View.GONE }
    }

    @Test
    fun thumbnailTapTogglesExpansion() {
        val entry = mediaEntry()
        buildView(listOf(entry), ClipboardTab.HISTORY)

        render()

        assertWithMessage("the thumbnail — the media row's natural tap target — must toggle expansion")
            .that(thumbnailClick.isCaptured).isTrue()
        thumbnailClick.captured.onClick(thumbnailContainer)

        assertThat(expandedStates[entry.timestamp]).isTrue()
        verify { adapter.notifyDataSetChanged() }
    }

    // ------------------------------------------- per-tab routing of the media delete

    @Test
    fun expandedMediaRowOnPinnedTabDeleteUnpins() {
        val entry = mediaEntry()
        expandedStates[entry.timestamp] = true
        buildView(listOf(entry), ClipboardTab.PINNED)

        render()
        deleteClick.captured.onClick(deleteButton)

        verify(exactly = 1) { service.unpinEntry(entry.content) }
        verify(exactly = 0) { service.removeHistoryEntry(any()) }
    }

    @Test
    fun expandedMediaRowOnTodosTabDeleteRemovesTodo() {
        val entry = spyk(
            ClipboardEntry(
                content = "clip.mp4", timestamp = 1700000000789L,
                mimeType = "video/mp4", todoStatus = TodoEntry.STATUS_ACTIVE,
            )
        ) { every { getFormattedText(any()) } returns mockk<Spannable>(relaxed = true) }
        expandedStates[entry.timestamp] = true
        buildView(listOf(entry), ClipboardTab.TODOS)

        render()
        deleteClick.captured.onClick(deleteButton)

        verify(exactly = 1) { service.removeFromTodo(entry.content) }
    }

    // ----------------------------------------------- text rows: existing idiom unchanged

    @Test
    fun expandedTextRowKeepsDeleteBehindEditMode() {
        // Text rows already have a delete path (edit mode); the media fix must not leak the
        // delete row into ordinary expanded text rows.
        val entry = textEntry()
        expandedStates[entry.timestamp] = true
        buildView(listOf(entry), ClipboardTab.HISTORY)

        render()

        verify { deleteRow.visibility = View.GONE }
        verify(exactly = 0) { deleteRow.visibility = View.VISIBLE }
        assertWithMessage("normal-mode text rows must not wire the delete button")
            .that(deleteClick.isCaptured).isFalse()
    }

    @Test
    fun editModeDeletePathIsUnchanged() {
        val entry = textEntry()
        buildView(listOf(entry), ClipboardTab.HISTORY, editingContent = entry.content)

        render()

        verify { deleteRow.visibility = View.VISIBLE }
        deleteClick.captured.onClick(deleteButton)
        verify(exactly = 1) { service.removeHistoryEntry(entry.content) }
    }

    // ------------------------------------------------------------------ helpers

    private fun setGlobalConfig(config: Config?) {
        val field = Config::class.java.getDeclaredField("_globalConfig")
        field.isAccessible = true
        field.set(null, config)
    }

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
