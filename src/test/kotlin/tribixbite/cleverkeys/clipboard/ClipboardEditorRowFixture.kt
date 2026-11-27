package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardHistoryView
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R

/**
 * One clipboard row bound by the REAL `ClipboardEntriesAdapter.getView`, with an edit field that
 * behaves like the platform's where the inline editor depends on it (mock tier, android.jar stubs):
 *
 *  - `setText` resets the selection to 0 first (TextView runs the movement method's
 *    `initialize`, which selects 0, before it notifies anyone) and then calls EVERY attached
 *    TextWatcher — TextView.setText always notifies, even when the text is unchanged;
 *  - `setSelection`, `selectAll` and `selectionStart` / `selectionEnd` share one selection;
 *  - watchers and view tags are really added, removed and read back.
 *
 * [render] is what the ListView does for the row whenever the adapter's data is reported
 * changed (`notifyDataSetChanged` is a no-op here): it binds the SAME row view again, so the
 * previous bind's watcher is still on the field — as when the ListView hands the row back from
 * its scrap heap for the same position.
 */
internal class ClipboardEditorRowFixture(
    entries: List<ClipboardEntry>,
    val service: ClipboardHistoryService = mockk(relaxed = true),
    tab: ClipboardTab = ClipboardTab.HISTORY,
    /** The field mock; a test may pass a mock of a subclass (e.g. ClipboardEditField). */
    val field: EditText = mockk(relaxed = true),
    val context: Context = mockk(relaxed = true),
) {
    private val objenesis = ObjenesisStd()
    val view: ClipboardHistoryView = spyk(objenesis.newInstance(ClipboardHistoryView::class.java))
    private val adapter: BaseAdapter
    val row: LinearLayout = mockk(relaxed = true)
    private val parent: ViewGroup = mockk(relaxed = true)
    val errorView: TextView = mockk(relaxed = true)

    /** The field's text, as the platform's Editable would hold it. */
    var text = ""
        private set
    var selectionStart = 0
        private set
    var selectionEnd = 0
        private set
    val watchers = mutableListOf<TextWatcher>()
    private val tags = mutableMapOf<Int, Any?>()

    /** Called on every selection change, as `TextView.onSelectionChanged` is. */
    var onSelectionChanged: ((Int, Int) -> Unit)? = null

    /** Whether the inline error line is on screen now (its last visibility set). */
    var errorShown = false
        private set

    init {
        setGlobalConfig(objenesis.newInstance(Config::class.java))
        every { view.context } returns context
        view.setField("service", service)
        view.setField("history", entries)
        view.setField("filteredHistory", entries)
        view.setField("paginatedHistory", entries)
        view.setField("expandedStates", mutableMapOf<Long, Boolean>())
        view.setField("currentTab", tab)
        view.setField("animatedCache", mutableMapOf<Long, Boolean>())
        view.setField("deleteActionDelegate", mockk<View.AccessibilityDelegate>(relaxed = true))
        val adapterClass = Class.forName("tribixbite.cleverkeys.ClipboardHistoryView\$ClipboardEntriesAdapter")
        adapter = spyk(objenesis.newInstance(adapterClass) as BaseAdapter)
        every { adapter.notifyDataSetChanged() } just runs
        adapter.setField("this\$0", view)
        view.setField("clipboardAdapter", adapter)
        every { view.reloadInBackground() } just runs
        every { view.parent } returns null

        wireField()
        wireRow()
    }

    /** The adapter binding the row at [pos] (first bind, or a re-bind after a data change). */
    fun render(pos: Int = 0): View = adapter.getView(pos, row, parent)

    /** What a tap or a handle drag does to the platform field: only its selection moves. */
    fun userPlacesCaret(start: Int, end: Int = start) = select(start, end)

    private fun select(start: Int, end: Int) {
        selectionStart = start.coerceIn(0, text.length)
        selectionEnd = end.coerceIn(0, text.length)
        onSelectionChanged?.invoke(selectionStart, selectionEnd)
    }

    private fun platformSetText(newText: String) {
        val old = text
        text = newText
        select(0, 0)  // ArrowKeyMovementMethod.initialize, before the watchers hear of it
        val editable = editableOf(newText)
        for (w in watchers.toList()) {
            w.beforeTextChanged(old, 0, old.length, newText.length)
            w.onTextChanged(newText, 0, old.length, newText.length)
            w.afterTextChanged(editable)
        }
    }

    private fun editableOf(value: String): Editable = mockk<Editable>(relaxed = true).also {
        every { it.toString() } returns value
        every { it.length } returns value.length
    }

    private fun wireField() {
        every { field.text } answers { editableOf(text) }
        every { field.setText(any<CharSequence>()) } answers { platformSetText(firstArg<CharSequence>().toString()) }
        every { field.selectionStart } answers { selectionStart }
        every { field.selectionEnd } answers { selectionEnd }
        every { field.setSelection(any<Int>()) } answers { select(firstArg(), firstArg()) }
        every { field.setSelection(any<Int>(), any<Int>()) } answers { select(firstArg(), secondArg()) }
        every { field.selectAll() } answers { select(0, text.length) }
        every { field.addTextChangedListener(any()) } answers { watchers += firstArg<TextWatcher>() }
        every { field.removeTextChangedListener(any()) } answers { watchers.remove(firstArg<TextWatcher>()); Unit }
        every { field.getTag(any<Int>()) } answers { tags[firstArg()] }
        every { field.setTag(any<Int>(), any()) } answers { tags[firstArg()] = secondArg<Any?>(); Unit }
        every { field.visibility } returns View.VISIBLE
        every { field.windowToken } returns mockk()
        // The field is a row of this list right now (ClipboardHistoryView.isInThisList).
        every { field.parent } returns row
        every { row.parent } returns view
    }

    private fun wireRow() {
        val typed = mutableMapOf<Int, View>(
            R.id.clipboard_entry_edit_field to field,
            R.id.clipboard_entry_edit_error to errorView,
        )
        for (id in intArrayOf(R.id.clipboard_entry_text, R.id.clipboard_entry_private_badge, R.id.clipboard_entry_provenance)) {
            typed[id] = mockk<TextView>(relaxed = true)
        }
        for (id in intArrayOf(
            R.id.clipboard_entry_primary_buttons, R.id.clipboard_entry_edit_buttons,
            R.id.clipboard_entry_delete_row, R.id.clipboard_entry_secondary_buttons, R.id.clipboard_entry_edit_pager,
        )) {
            typed[id] = mockk<LinearLayout>(relaxed = true)
        }
        typed[R.id.clipboard_entry_thumbnail_container] = mockk<FrameLayout>(relaxed = true)
        typed[R.id.clipboard_entry_thumbnail] = mockk<ImageView>(relaxed = true)
        typed[R.id.clipboard_entry_play_badge] = mockk<ImageView>(relaxed = true)
        typed[R.id.clipboard_entry_select] = mockk<CheckBox>(relaxed = true)
        every { row.findViewById<View>(any()) } answers {
            typed.getOrPut(firstArg()) { mockk<View>(relaxed = true) }
        }
        every { errorView.visibility = any() } answers { errorShown = firstArg<Int>() == View.VISIBLE }
        every { errorView.visibility } answers { if (errorShown) View.VISIBLE else View.GONE }
    }

    fun clear() = setGlobalConfig(null)

    companion object {
        /** A spied entry whose formatted texts need no resources (only normal rows use them). */
        fun entry(content: String, ts: Long = 1_700_000_000_000L): ClipboardEntry =
            spyk(ClipboardEntry(content = content, timestamp = ts)) {
                every { getFormattedText(any(), any(), any()) } returns mockk<Spannable>(relaxed = true)
                every { getFormattedTodoText(any(), any(), any(), any(), any()) } returns mockk<Spannable>(relaxed = true)
            }

        private fun setGlobalConfig(config: Config?) {
            val f = Config::class.java.getDeclaredField("_globalConfig")
            f.isAccessible = true
            f.set(null, config)
        }

        private fun Any.findField(name: String): java.lang.reflect.Field {
            var cls: Class<*>? = javaClass
            while (cls != null) {
                cls.declaredFields.firstOrNull { it.name == name }?.let { it.isAccessible = true; return it }
                cls = cls.superclass
            }
            throw AssertionError("field '$name' not found on ${javaClass.name} — renamed or removed; re-point this test")
        }

        fun Any.setField(name: String, value: Any?) = findField(name).set(this, value)

        fun Any.getField(name: String): Any? = findField(name).get(this)
    }
}
