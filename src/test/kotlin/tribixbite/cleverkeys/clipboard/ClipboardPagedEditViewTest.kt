package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
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
import tribixbite.cleverkeys.EditEntryResult
import tribixbite.cleverkeys.R

/**
 * Paged inline editing (todo 22, 2026-10-26; maintainer: "find a way to facilitate editing of
 * 20k+ char entries"). Drives the REAL `ClipboardEntriesAdapter.getView` and the view's edit
 * API on a 524,000-char entry: the editor is offered, the field binds only the current page,
 * the pager says "Page N of M", navigation keeps unsaved edits, and Save writes the spliced
 * full text in ONE update on History, Pinned and To-dos. A row that changed meanwhile is a
 * conflict that keeps the session. Short entries keep the single-page editor with no pager.
 */
class ClipboardPagedEditViewTest {

    private val objenesis = ObjenesisStd()
    private lateinit var service: ClipboardHistoryService
    private lateinit var view: ClipboardHistoryView
    private lateinit var adapter: android.widget.BaseAdapter
    private lateinit var context: Context
    private lateinit var rowView: View
    private lateinit var parent: ViewGroup
    private val widgets = mutableMapOf<Int, View>()
    private val fieldTexts = mutableListOf<String>()
    private val labels = mutableListOf<String>()
    private val watcher = slot<TextWatcher>()
    private val prevClick = slot<View.OnClickListener>()
    private val nextClick = slot<View.OnClickListener>()

    private val huge = "abcdefghij".repeat(52_400)  // 524,000 chars, no breaks → 20,000-char pages
    private val pageSize = ClipboardEditPages.PAGE_MAX_CHARS
    private val pageCount = (huge.length + pageSize - 1) / pageSize

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
        every { context.getString(R.string.clipboard_edit_page, *anyVararg()) } answers {
            @Suppress("UNCHECKED_CAST")
            val args = (it.invocation.args[1] as Array<Any?>)
            "Page ${args[0]} of ${args[1]}"
        }
        every { context.getString(R.string.clipboard_entry_preview_truncated, *anyVararg()) } returns "NOTE"
        buildRow()
    }

    @After
    fun teardown() {
        setGlobalConfig(null)
        unmockkAll()
    }

    // ---------------------------------------------------------------- fixtures

    private fun entry(content: String, ts: Long = 1_700_000_000_000L) =
        spyk(ClipboardEntry(content = content, timestamp = ts)) {
            every { getFormattedText(any(), any(), any()) } returns mockk<Spannable>(relaxed = true)
            every { getFormattedTodoText(any(), any(), any(), any(), any()) } returns mockk<Spannable>(relaxed = true)
        }

    private fun buildView(entries: List<ClipboardEntry>, tab: ClipboardTab = ClipboardTab.HISTORY) {
        view = spyk(objenesis.newInstance(ClipboardHistoryView::class.java))
        every { view.context } returns context
        view.setField("service", service)
        view.setField("history", entries)
        view.setField("filteredHistory", entries)
        view.setField("paginatedHistory", entries)
        view.setField("expandedStates", mutableMapOf<Long, Boolean>())
        view.setField("currentTab", tab)
        view.setField("animatedCache", mutableMapOf<Long, Boolean>())
        view.setField("deleteActionDelegate", mockk<View.AccessibilityDelegate>(relaxed = true))
        view.setField("pageFirstDelegate", mockk<View.AccessibilityDelegate>(relaxed = true))
        view.setField("pageLastDelegate", mockk<View.AccessibilityDelegate>(relaxed = true))
        val adapterClass = Class.forName("tribixbite.cleverkeys.ClipboardHistoryView\$ClipboardEntriesAdapter")
        adapter = spyk(objenesis.newInstance(adapterClass) as android.widget.BaseAdapter)
        every { adapter.notifyDataSetChanged() } just runs
        adapter.setField("this\$0", view)
        view.setField("clipboardAdapter", adapter)
        every { view.reloadInBackground() } just runs
        every { view.parent } returns null  // no pane ScrollView unless a test attaches one
    }

    private inline fun <reified T : View> widget(id: Int): T =
        mockk<T>(relaxed = true).also { widgets[id] = it }

    private fun buildRow() {
        parent = mockk(relaxed = true)
        rowView = mockk(relaxed = true)
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_text) } returns widget(R.id.clipboard_entry_text)
        val field = widget<EditText>(R.id.clipboard_entry_edit_field)
        every { rowView.findViewById<EditText>(R.id.clipboard_entry_edit_field) } returns field
        every { field.setText(any<CharSequence>()) } answers { fieldTexts += firstArg<CharSequence>().toString() }
        every { field.addTextChangedListener(capture(watcher)) } just runs
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_edit_error) } returns widget(R.id.clipboard_entry_edit_error)
        val label = widget<TextView>(R.id.clipboard_entry_edit_page_label)
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_edit_page_label) } returns label
        every { label.text = any() } answers { labels += firstArg<CharSequence>().toString() }
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_private_badge) } returns widget(R.id.clipboard_entry_private_badge)
        every { rowView.findViewById<TextView>(R.id.clipboard_entry_provenance) } returns widget(R.id.clipboard_entry_provenance)
        every { rowView.findViewById<CheckBox>(R.id.clipboard_entry_select) } returns widget(R.id.clipboard_entry_select)
        every { rowView.findViewById<FrameLayout>(R.id.clipboard_entry_thumbnail_container) } returns widget(R.id.clipboard_entry_thumbnail_container)
        every { rowView.findViewById<ImageView>(R.id.clipboard_entry_thumbnail) } returns widget(R.id.clipboard_entry_thumbnail)
        every { rowView.findViewById<ImageView>(R.id.clipboard_entry_play_badge) } returns widget(R.id.clipboard_entry_play_badge)
        for (id in intArrayOf(
            R.id.clipboard_entry_primary_buttons, R.id.clipboard_entry_edit_buttons,
            R.id.clipboard_entry_delete_row, R.id.clipboard_entry_secondary_buttons,
            R.id.clipboard_entry_edit_pager,
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
        val prev = widget<View>(R.id.clipboard_entry_edit_page_prev)
        val next = widget<View>(R.id.clipboard_entry_edit_page_next)
        every { rowView.findViewById<View>(R.id.clipboard_entry_edit_page_prev) } returns prev
        every { rowView.findViewById<View>(R.id.clipboard_entry_edit_page_next) } returns next
        every { prev.setOnClickListener(capture(prevClick)) } just runs
        every { next.setOnClickListener(capture(nextClick)) } just runs
    }

    private fun render(pos: Int = 0): View = adapter.getView(pos, rowView, parent)

    /** What the user's typing does: the field's watcher reports its new text. */
    private fun type(newPageText: String) {
        val editable = mockk<Editable>(relaxed = true)
        every { editable.toString() } returns newPageText
        watcher.captured.afterTextChanged(editable)
    }

    private fun page(i: Int, text: String = huge) =
        text.substring(i * pageSize, minOf(text.length, (i + 1) * pageSize))

    // ---------------------------------------------------------------- tests

    @Test
    fun theEditorIsOfferedForAHugeEntry() {
        buildView(listOf(entry(huge)))

        render()

        verify { widgets.getValue(R.id.clipboard_entry_edit).visibility = View.VISIBLE }
    }

    @Test
    fun editingAHugeEntryBindsOnlyItsFirstPageAndShowsThePager() {
        buildView(listOf(entry(huge)))

        view.edit_entry(0)
        render()

        assertWithMessage("the field must hold one page, never the whole entry")
            .that(fieldTexts.last()).isEqualTo(page(0))
        assertThat(fieldTexts.maxOf { it.length }).isAtMost(pageSize)
        verify { widgets.getValue(R.id.clipboard_entry_edit_pager).visibility = View.VISIBLE }
        assertThat(labels.last()).isEqualTo("Page 1 of $pageCount")
        verify { widgets.getValue(R.id.clipboard_entry_edit_page_prev).isEnabled = false }
        verify { widgets.getValue(R.id.clipboard_entry_edit_page_next).isEnabled = true }
    }

    @Test
    fun saveWritesTheSplicedFullTextOnceOnEveryTab() {
        for (tab in ClipboardTab.values()) {
            fieldTexts.clear(); labels.clear()
            buildRow()
            service = mockk(relaxed = true)  // calls are counted per tab
            buildView(listOf(entry(huge)), tab)
            every { service.editEntryContent(any(), any(), any()) } returns EditEntryResult.Success

            view.edit_entry(0)
            render()
            type("FIRST" + page(0))             // unsaved edit on page 1
            nextClick.captured.onClick(widgets.getValue(R.id.clipboard_entry_edit_page_next))
            render()
            // page 1 grew past the limit: its last 5 chars became page 2
            assertWithMessage("$tab: next binds the overflow").that(fieldTexts.last()).isEqualTo(page(0).takeLast(5))
            assertThat(labels.last()).isEqualTo("Page 2 of ${pageCount + 1}")
            nextClick.captured.onClick(widgets.getValue(R.id.clipboard_entry_edit_page_next))
            render()
            assertWithMessage("$tab: then the old page 2").that(fieldTexts.last()).isEqualTo(page(1))
            assertThat(labels.last()).isEqualTo("Page 3 of ${pageCount + 1}")
            type(page(1).replaceFirst("abc", "MID"))  // unsaved edit on that page
            prevClick.captured.onClick(widgets.getValue(R.id.clipboard_entry_edit_page_prev))
            render()
            prevClick.captured.onClick(widgets.getValue(R.id.clipboard_entry_edit_page_prev))
            render()
            assertWithMessage("$tab: the edit on page 1 survived navigation").that(fieldTexts.last())
                .isEqualTo("FIRST" + page(0).substring(0, pageSize - 5))

            view.save_edit()

            val expected = "FIRST" + page(0) + page(1).replaceFirst("abc", "MID") + huge.substring(2 * pageSize)
            verify(exactly = 1) { service.editEntryContent(any(), any(), any()) }
            verify(exactly = 1) { service.editEntryContent(huge, expected, tab) }
            assertWithMessage("$tab: a successful save ends the session").that(view.isEditing()).isFalse()
        }
    }

    @Test
    fun anEntryThatChangedMeanwhileIsAConflictThatKeepsTheSession() {
        buildView(listOf(entry(huge)))
        every { service.editEntryContent(any(), any(), any()) } returns EditEntryResult.EntryChanged

        view.edit_entry(0)
        render()
        type("X" + page(0))
        view.save_edit()

        assertThat(view.isEditing()).isTrue()
        assertThat(view.getField("editingErrorRes")).isEqualTo(R.string.clipboard_edit_changed)
        assertWithMessage("the unsaved edit is kept").that(view.getField("editingInProgressText"))
            .isEqualTo("X" + page(0))
    }

    @Test
    fun aShortEntryKeepsTheSinglePageEditorWithoutAPager() {
        buildView(listOf(entry("short clip")))
        every { service.editEntryContent(any(), any(), any()) } returns EditEntryResult.Success

        view.edit_entry(0)
        render()
        type("short clip, edited")
        view.save_edit()

        assertThat(fieldTexts.last()).isEqualTo("short clip")
        verify { widgets.getValue(R.id.clipboard_entry_edit_pager).visibility = View.GONE }
        verify(exactly = 0) { widgets.getValue(R.id.clipboard_entry_edit_pager).visibility = View.VISIBLE }
        verify(exactly = 1) { service.editEntryContent("short clip", "short clip, edited", ClipboardTab.HISTORY) }
    }

    @Test
    fun theLastPageIsReachableAndNextStopsThere() {
        buildView(listOf(entry(huge)))

        view.edit_entry(0)
        render()
        view.editPageLast()
        render()

        assertThat(fieldTexts.last()).isEqualTo(huge.substring((pageCount - 1) * pageSize))
        assertThat(labels.last()).isEqualTo("Page $pageCount of $pageCount")
        verify { widgets.getValue(R.id.clipboard_entry_edit_page_next).isEnabled = false }
        view.editPageNext()
        assertThat(view.getField("editingInProgressText")).isEqualTo(huge.substring((pageCount - 1) * pageSize))
    }

    @Test
    fun aPagedFieldIsCappedToThePaneSoThePagerStaysVisible() {
        buildView(listOf(entry(huge)))
        val pane = mockk<android.widget.ScrollView>(relaxed = true)
        every { pane.height } returns 1_000
        every { pane.paddingTop } returns 10
        every { pane.paddingBottom } returns 10
        every { pane.parent } returns null
        every { view.parent } returns pane
        val metrics = objenesis.newInstance(android.util.DisplayMetrics::class.java).apply { density = 2f }
        val res = mockk<android.content.res.Resources>(relaxed = true)
        every { res.displayMetrics } returns metrics
        every { view.resources } returns res

        view.edit_entry(0)
        render()

        // 1,000 − 20 padding − 36 dp × 2 (pager row)
        verify { (widgets.getValue(R.id.clipboard_entry_edit_field) as EditText).maxHeight = 908 }
    }

    @Test
    fun aSinglePageFieldKeepsItsLineCap() {
        buildView(listOf(entry("short clip")))

        view.edit_entry(0)
        render()

        verify { (widgets.getValue(R.id.clipboard_entry_edit_field) as EditText).maxLines = 50 }
        verify(exactly = 0) { (widgets.getValue(R.id.clipboard_entry_edit_field) as EditText).maxHeight = any() }
    }

    @Test
    fun aNormalRowHidesThePager() {
        buildView(listOf(entry(huge)))

        render()

        verify { widgets.getValue(R.id.clipboard_entry_edit_pager).visibility = View.GONE }
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
