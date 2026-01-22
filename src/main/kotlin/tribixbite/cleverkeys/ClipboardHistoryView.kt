package tribixbite.cleverkeys

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * Clipboard tab types for the unified clipboard view.
 */
enum class ClipboardTab {
    HISTORY,  // Recent clipboard history (default)
    PINNED,   // Pinned items
    TODOS     // To-do items
}

class ClipboardHistoryView(ctx: Context, attrs: AttributeSet?) : NonScrollListView(ctx, attrs),
    ClipboardHistoryService.OnClipboardHistoryChange {

    private var history: List<ClipboardEntry> = emptyList()
    private var filteredHistory: List<ClipboardEntry> = emptyList()
    private var searchFilter = ""
    private val service: ClipboardHistoryService?
    private val clipboardAdapter: ClipboardEntriesAdapter

    // Current tab mode
    private var currentTab = ClipboardTab.HISTORY

    // Track expanded state: position -> isExpanded
    private val expandedStates = mutableMapOf<Int, Boolean>()

    // Date filter state
    private var dateFilterEnabled = false
    private var dateFilterBefore = false // true = before date, false = after date
    private var dateFilterTimestamp = 0L

    init {
        service = ClipboardHistoryService.get_service(ctx)
        clipboardAdapter = ClipboardEntriesAdapter()

        service?.let {
            it.setOnClipboardHistoryChange(this)
            history = it.clearExpiredAndGetHistory()
            filteredHistory = history
        }

        adapter = clipboardAdapter
    }

    /**
     * Switch to a different tab and update the displayed content.
     */
    fun setTab(tab: ClipboardTab) {
        currentTab = tab
        expandedStates.clear()  // Reset expanded states when switching tabs
        update_data()
    }

    /**
     * Get the current tab.
     */
    fun getCurrentTab(): ClipboardTab = currentTab

    /** Filter clipboard history by search text */
    fun setSearchFilter(filter: String?) {
        searchFilter = filter?.lowercase() ?: ""
        applyFilter()
    }

    private fun applyFilter() {
        // Apply both search and date filters
        val filtered = history.filter { entry ->
            // Apply search filter
            if (searchFilter.isNotEmpty() && !entry.content.lowercase().contains(searchFilter)) {
                return@filter false
            }

            // Apply date filter
            if (dateFilterEnabled) {
                if (dateFilterBefore) {
                    // Show entries before the selected date
                    if (entry.timestamp >= dateFilterTimestamp) {
                        return@filter false
                    }
                } else {
                    // Show entries after the selected date
                    if (entry.timestamp < dateFilterTimestamp) {
                        return@filter false
                    }
                }
            }

            true
        }

        // If no filters are active, show all history
        filteredHistory = if (searchFilter.isEmpty() && !dateFilterEnabled) {
            history
        } else {
            filtered
        }

        clipboardAdapter.notifyDataSetChanged()
        invalidate()
    }

    /**
     * Pin or unpin the entry at index [pos].
     */
    fun pin_entry(pos: Int) {
        val clip = filteredHistory[pos].content

        when (currentTab) {
            ClipboardTab.HISTORY -> {
                // Pin from history
                service?.setPinnedStatus(clip, true)
            }
            ClipboardTab.PINNED -> {
                // Unpin from pinned tab
                service?.setPinnedStatus(clip, false)
            }
            ClipboardTab.TODOS -> {
                // Pin todo item (can be both pinned and todo)
                service?.setPinnedStatus(clip, true)
            }
        }
        update_data()
    }

    /**
     * Add or remove entry from todos at index [pos].
     */
    fun todo_entry(pos: Int) {
        val clip = filteredHistory[pos].content
        val database = ClipboardDatabase.getInstance(context)

        when (currentTab) {
            ClipboardTab.HISTORY, ClipboardTab.PINNED -> {
                // Add to todos
                database.setTodoStatus(clip, true)
            }
            ClipboardTab.TODOS -> {
                // Remove from todos (mark as done)
                database.setTodoStatus(clip, false)
            }
        }
        update_data()
    }

    /** Delete the specified entry from clipboard history. */
    fun delete_entry(pos: Int) {
        val clip = filteredHistory[pos].content
        service?.removeHistoryEntry(clip)
        // Clear expanded state for deleted position
        expandedStates.remove(pos)
        update_data()
    }

    /** Send the specified entry to the editor. */
    fun paste_entry(pos: Int) {
        ClipboardHistoryService.paste(filteredHistory[pos].content)
    }

    override fun on_clipboard_history_change() {
        update_data()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility == VISIBLE) {
            update_data()
        }
    }

    private fun update_data() {
        val database = ClipboardDatabase.getInstance(context)

        // Load data based on current tab
        history = when (currentTab) {
            ClipboardTab.HISTORY -> service?.clearExpiredAndGetHistory() ?: emptyList()
            ClipboardTab.PINNED -> database.getPinnedEntries()
            ClipboardTab.TODOS -> database.getTodoEntries()
        }
        applyFilter() // Reapply current search filter
    }

    /** Date filter methods */
    fun isDateFilterEnabled(): Boolean = dateFilterEnabled

    fun isDateFilterBefore(): Boolean = dateFilterBefore

    fun getDateFilterTimestamp(): Long = dateFilterTimestamp

    fun setDateFilter(timestamp: Long, isBefore: Boolean) {
        dateFilterEnabled = true
        dateFilterTimestamp = timestamp
        dateFilterBefore = isBefore
        applyFilter()
    }

    fun clearDateFilter() {
        dateFilterEnabled = false
        dateFilterTimestamp = 0
        dateFilterBefore = false
        applyFilter()
    }

    inner class ClipboardEntriesAdapter : BaseAdapter() {
        override fun getCount(): Int = filteredHistory.size

        override fun getItem(pos: Int): Any = filteredHistory[pos]

        override fun getItemId(pos: Int): Long = filteredHistory[pos].hashCode().toLong()

        override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
            val view = v ?: View.inflate(context, R.layout.clipboard_history_entry, null)

            val entry = filteredHistory[pos]
            val text = entry.content
            val textView = view.findViewById<TextView>(R.id.clipboard_entry_text)
            val expandButton = view.findViewById<View>(R.id.clipboard_entry_expand)
            val pinButton = view.findViewById<View>(R.id.clipboard_entry_addpin)
            val todoButton = view.findViewById<View>(R.id.clipboard_entry_addtodo)

            // Set text with timestamp appended
            textView.text = entry.getFormattedText(context)

            // Check if text contains newlines (multi-line)
            val isMultiLine = text.contains("\n")
            val isExpanded = expandedStates[pos] == true

            // Set maxLines based on expanded state (applies to all entries)
            if (isExpanded) {
                textView.maxLines = Int.MAX_VALUE
                textView.ellipsize = null
            } else {
                textView.maxLines = 1
                textView.ellipsize = android.text.TextUtils.TruncateAt.END
            }

            // Show expand button only for multi-line entries
            if (isMultiLine) {
                expandButton.visibility = VISIBLE
                expandButton.rotation = if (isExpanded) 180f else 0f

                // Handle expand button click for multi-line entries
                expandButton.setOnClickListener {
                    expandedStates[pos] = !isExpanded
                    notifyDataSetChanged()
                }
            } else {
                expandButton.visibility = GONE
            }

            // Make text clickable to expand/collapse (all entries)
            textView.setOnClickListener {
                expandedStates[pos] = !isExpanded
                notifyDataSetChanged()
            }

            // Configure buttons based on current tab
            when (currentTab) {
                ClipboardTab.HISTORY -> {
                    pinButton.visibility = VISIBLE
                    todoButton.visibility = VISIBLE
                }
                ClipboardTab.PINNED -> {
                    // In pinned tab, pin button unpins
                    pinButton.visibility = VISIBLE
                    todoButton.visibility = VISIBLE
                }
                ClipboardTab.TODOS -> {
                    // In todos tab, todo button marks as done (removes from todos)
                    pinButton.visibility = VISIBLE
                    todoButton.visibility = VISIBLE
                }
            }

            pinButton.setOnClickListener {
                pin_entry(pos)
            }
            todoButton.setOnClickListener {
                todo_entry(pos)
            }
            view.findViewById<View>(R.id.clipboard_entry_paste).setOnClickListener {
                paste_entry(pos)
            }
            view.findViewById<View>(R.id.clipboard_entry_delete).setOnClickListener {
                delete_entry(pos)
            }

            return view
        }
    }
}
