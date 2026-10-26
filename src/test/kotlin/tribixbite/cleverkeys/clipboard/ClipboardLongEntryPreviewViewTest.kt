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
import io.mockk.mockkObject
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
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.TodoEntry

/**
 * Saga 2026-10-26 (todo 22 a): with ~0.5 MB entries, opening the clipboard pane or entering
 * Select mode blocked the keyboard's main thread 46–58 s, because every row bound the entry's
 * FULL text and the TextView line-broke all of it; a tap meanwhile was an ANR.
 *
 * Drives the REAL `ClipboardEntriesAdapter.getView` (the ClipboardMediaDeleteAffordanceTest
 * idiom) and captures the text each row binds: collapsed, expanded, Select-mode and To-do rows
 * of a 524,000-char entry must bind a bounded preview, an expanded cut row says so, paste still
 * sends the full text, and the inline editor is not offered for an entry it cannot show whole.
 */
class ClipboardLongEntryPreviewViewTest {

    private val objenesis = ObjenesisStd()
    private lateinit var service: ClipboardHistoryService
    private lateinit var view: ClipboardHistoryView
    private lateinit var adapter: android.widget.BaseAdapter
    private lateinit var context: Context
    private lateinit var rowView: View
    private lateinit var parent: ViewGroup
    private val widgets = mutableMapOf<Int, View>()
    private val expandedStates = mutableMapOf<Long, Boolean>()

    /** What each row passed as its displayed text (shown, note). */
    private val bound = mutableListOf<Pair<String, String?>>()

    private val huge = "abcdefghij".repeat(52_400)  // 524,000 chars, the Saga entry's length
    private val note = "NOTE"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        setGlobalConfig(objenesis.newInstance(Config::class.java))
        service = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.getString(R.string.clipboard_entry_preview_truncated, *anyVararg()) } returns note
        buildRow()
    }

    @After
    fun teardown() {
        setGlobalConfig(null)
        unmockkAll()
    }

    // ---------------------------------------------------------------- fixtures

    private fun entry(content: String, ts: Long = 1_700_000_000_000L, todoStatus: String? = null) =
        spyk(ClipboardEntry(content = content, timestamp = ts, todoStatus = todoStatus)) {
            every { getFormattedText(any(), any(), any()) } answers {
                bound += secondArg<String>() to thirdArg<String?>()
                mockk<Spannable>(relaxed = true)
            }
            every { getFormattedTodoText(any(), any(), any(), any(), any()) } answers {
                bound += thirdArg<String>() to arg<String?>(3)
                mockk<Spannable>(relaxed = true)
            }
        }

    private fun buildView(entries: List<ClipboardEntry>, tab: ClipboardTab = ClipboardTab.HISTORY) {
        view = spyk(objenesis.newInstance(ClipboardHistoryView::class.java))
        every { view.context } returns context
        view.setField("service", service)
        view.setField("history", entries)
        view.setField("filteredHistory", entries)
        view.setField("paginatedHistory", entries)
        view.setField("expandedStates", expandedStates)
        view.setField("currentTab", tab)
        view.setField("animatedCache", mutableMapOf<Long, Boolean>())
        view.setField("deleteActionDelegate", mockk<View.AccessibilityDelegate>(relaxed = true))
        val adapterClass = Class.forName("tribixbite.cleverkeys.ClipboardHistoryView\$ClipboardEntriesAdapter")
        adapter = spyk(objenesis.newInstance(adapterClass) as android.widget.BaseAdapter)
        every { adapter.notifyDataSetChanged() } just runs
        adapter.setField("this\$0", view)
        view.setField("clipboardAdapter", adapter)
    }

    private inline fun <reified T : View> widget(id: Int): T =
        mockk<T>(relaxed = true).also { widgets[id] = it }

    private fun buildRow() {
        parent = mockk(relaxed = true)
        rowView = mockk(relaxed = true)
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_text) } returns widget(R.id.clipboard_entry_text)
        every { rowView.findViewById<EditText>(R.id.clipboard_entry_edit_field) } returns widget(R.id.clipboard_entry_edit_field)
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_edit_error) } returns widget(R.id.clipboard_entry_edit_error)
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_private_badge) } returns widget(R.id.clipboard_entry_private_badge)
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_provenance) } returns widget(R.id.clipboard_entry_provenance)
        every { rowView.findViewById<CheckBox>(R.id.clipboard_entry_select) } returns widget(R.id.clipboard_entry_select)
        every { rowView.findViewById<FrameLayout>(R.id.clipboard_entry_thumbnail_container) } returns widget(R.id.clipboard_entry_thumbnail_container)
        every { rowView.findViewById<ImageView>(R.id.clipboard_entry_thumbnail) } returns widget(R.id.clipboard_entry_thumbnail)
        every { rowView.findViewById<ImageView>(R.id.clipboard_entry_play_badge) } returns widget(R.id.clipboard_entry_play_badge)
        for (id in intArrayOf(
            R.id.clipboard_entry_primary_buttons, R.id.clipboard_entry_edit_buttons,
            R.id.clipboard_entry_delete_row, R.id.clipboard_entry_secondary_buttons,
        )) {
            every { rowView.findViewById<LinearLayout>(id) } returns widget(id)
        }
        for (id in intArrayOf(
            R.id.clipboard_entry_expand, R.id.clipboard_entry_edit, R.id.clipboard_entry_paste,
            R.id.clipboard_entry_addpin, R.id.clipboard_entry_unpin, R.id.clipboard_entry_addtodo,
            R.id.clipboard_entry_done, R.id.clipboard_entry_status, R.id.clipboard_entry_tags,
            R.id.clipboard_entry_save, R.id.clipboard_entry_cancel, R.id.clipboard_entry_delete,
        )) {
            every { rowView.findViewById<View>(id) } returns widget<View>(id)
        }
    }

    private fun render(pos: Int = 0): View = adapter.getView(pos, rowView, parent)

    private fun lastBound(): Pair<String, String?> {
        assertWithMessage("the row must bind its text through the bounded formatter").that(bound).isNotEmpty()
        return bound.last()
    }

    // ---------------------------------------------------------------- bounded rows

    @Test
    fun aCollapsedRowOfAHugeEntryBindsABoundedPreview() {
        buildView(listOf(entry(huge)))

        render()

        val (shown, rowNote) = lastBound()
        assertThat(shown.length).isAtMost(ClipboardPreviewPolicy.COLLAPSED_MAX_CHARS + 1)
        assertThat(shown).isEqualTo(huge.substring(0, ClipboardPreviewPolicy.COLLAPSED_MAX_CHARS) + ClipboardPreviewPolicy.ELLIPSIS)
        assertWithMessage("a collapsed row carries no note").that(rowNote).isNull()
    }

    @Test
    fun anExpandedRowOfAHugeEntryIsBoundedAndSaysSo() {
        val e = entry(huge)
        expandedStates[e.timestamp] = true
        buildView(listOf(e))

        render()

        val (shown, rowNote) = lastBound()
        assertThat(shown.length).isAtMost(ClipboardPreviewPolicy.EXPANDED_MAX_CHARS + 1)
        assertThat(rowNote).isEqualTo(note)
        verify {
            context.getString(R.string.clipboard_entry_preview_truncated, *anyVararg())
        }
    }

    @Test
    fun aSelectModeRowOfAHugeEntryBindsABoundedPreview() {
        val e = entry(huge)
        expandedStates[e.timestamp] = true  // Select mode always renders rows collapsed
        buildView(listOf(e))
        view.setField("holder", ClipboardSelectionHolder().apply { start(ClipboardTab.HISTORY) })

        render()

        assertThat(lastBound().first.length).isAtMost(ClipboardPreviewPolicy.COLLAPSED_MAX_CHARS + 1)
    }

    @Test
    fun aTodoRowOfAHugeEntryBindsABoundedPreview() {
        for (expanded in listOf(false, true)) {
            bound.clear()
            val e = entry(huge, todoStatus = TodoEntry.STATUS_COMPLETED)
            expandedStates[e.timestamp] = expanded
            buildView(listOf(e), ClipboardTab.TODOS)

            render()

            val limit = if (expanded) ClipboardPreviewPolicy.EXPANDED_MAX_CHARS else ClipboardPreviewPolicy.COLLAPSED_MAX_CHARS
            assertWithMessage("expanded=$expanded").that(lastBound().first.length).isAtMost(limit + 1)
            assertWithMessage("expanded=$expanded").that(lastBound().second).isEqualTo(if (expanded) note else null)
        }
    }

    @Test
    fun aPinnedRowOfAHugeEntryBindsABoundedPreview() {
        buildView(listOf(entry(huge)), ClipboardTab.PINNED)

        render()

        assertThat(lastBound().first.length).isAtMost(ClipboardPreviewPolicy.COLLAPSED_MAX_CHARS + 1)
    }

    @Test
    fun anOrdinaryEntryIsShownWholeWithoutANote() {
        val e = entry("short clip\nsecond line")
        expandedStates[e.timestamp] = true
        buildView(listOf(e))

        render()

        assertThat(lastBound()).isEqualTo("short clip\nsecond line" to null)
    }

    // ---------------------------------------------------------------- full text where it counts

    @Test
    fun pasteStillSendsTheFullText() {
        mockkObject(ClipboardHistoryService.Companion)
        every { ClipboardHistoryService.paste(any()) } just runs
        buildView(listOf(entry(huge)))

        view.paste_entry(0)

        verify(exactly = 1) { ClipboardHistoryService.paste(huge) }
    }

    @Test
    fun theInlineEditorIsNotOfferedForAnEntryTooLongToShowWhole() {
        buildView(listOf(entry(huge)))

        render()
        view.edit_entry(0)

        verify { widgets.getValue(R.id.clipboard_entry_edit).visibility = View.GONE }
        verify(exactly = 0) { widgets.getValue(R.id.clipboard_entry_edit).visibility = View.VISIBLE }
        assertWithMessage("edit_entry must refuse an entry the editor cannot show whole")
            .that(view.getField("editingOriginalContent")).isNull()
    }

    @Test
    fun theInlineEditorIsStillOfferedForAnOrdinaryEntry() {
        buildView(listOf(entry("short clip")))

        render()

        verify { widgets.getValue(R.id.clipboard_entry_edit).visibility = View.VISIBLE }
    }

    // ---------------------------------------------------------------- helpers

    private fun setGlobalConfig(config: Config?) {
        val field = Config::class.java.getDeclaredField("_globalConfig")
        field.isAccessible = true
        field.set(null, config)
    }

    private fun Any.findField(name: String): java.lang.reflect.Field {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredFields.firstOrNull { it.name == name }?.let { it.isAccessible = true; return it }
            cls = cls.superclass
        }
        throw AssertionError("field '$name' not found on ${javaClass.name} — renamed or removed; re-point this test")
    }

    private fun Any.setField(name: String, value: Any?) = findField(name).set(this, value)

    private fun Any.getField(name: String): Any? = findField(name).get(this)
}
