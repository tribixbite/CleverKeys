package tribixbite.cleverkeys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var paginatedHistory: List<ClipboardEntry> = emptyList()
    private var searchFilter = ""
    private val service: ClipboardHistoryService?
    private val clipboardAdapter: ClipboardEntriesAdapter

    // Current tab mode
    private var currentTab = ClipboardTab.HISTORY

    // Track expanded state: content string -> isExpanded (survives reorder/delete)
    private val expandedStates = mutableMapOf<String, Boolean>()

    // Date filter state
    private var dateFilterEnabled = false
    private var dateFilterBefore = false // true = before date, false = after date
    private var dateFilterTimestamp = 0L

    // Pagination state
    private var currentPage = 0
    private var onPaginationChangeListener: ((needsPagination: Boolean, currentPage: Int, totalPages: Int) -> Unit)? = null

    // Coroutine scope tied to window attach/detach lifecycle (IME has no ViewLifecycleOwner)
    private var viewScope: CoroutineScope? = null
    // Current async load job — cancelled on new load to prevent stale data
    private var loadJob: Job? = null

    companion object {
        const val ITEMS_PER_PAGE = 100
    }

    init {
        service = ClipboardHistoryService.get_service(ctx)
        clipboardAdapter = ClipboardEntriesAdapter()
        // Start with empty data — actual load deferred to onAttachedToWindow (async, off UI thread)
        history = emptyList()
        filteredHistory = emptyList()
        adapter = clipboardAdapter
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // Register listener here (not init) to prevent singleton→view memory leak
        service?.setOnClipboardHistoryChange(this)
        loadDataAsync()
    }

    override fun onDetachedFromWindow() {
        // Unregister to break singleton→view reference and stop receiving callbacks
        service?.setOnClipboardHistoryChange(null)
        loadJob?.cancel()
        loadJob = null
        viewScope?.cancel()
        viewScope = null
        super.onDetachedFromWindow()
    }

    /**
     * Switch to a different tab and update the displayed content.
     */
    fun setTab(tab: ClipboardTab) {
        currentTab = tab
        expandedStates.clear()  // Reset expanded states when switching tabs
        loadDataAsync()
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
        // Apply both search and date filters (searches ALL items)
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

        // Reset to first page when filter changes
        currentPage = 0
        applyPagination()
    }

    private fun applyPagination() {
        val totalItems = filteredHistory.size
        val totalPages = getTotalPages()

        // Ensure current page is valid
        if (currentPage >= totalPages) {
            currentPage = maxOf(0, totalPages - 1)
        }

        // Apply pagination only if more than ITEMS_PER_PAGE
        paginatedHistory = if (totalItems > ITEMS_PER_PAGE) {
            val startIndex = currentPage * ITEMS_PER_PAGE
            val endIndex = minOf(startIndex + ITEMS_PER_PAGE, totalItems)
            filteredHistory.subList(startIndex, endIndex)
        } else {
            filteredHistory
        }

        // Clear expanded states when page changes
        expandedStates.clear()

        // Notify listener about pagination state
        onPaginationChangeListener?.invoke(
            totalItems > ITEMS_PER_PAGE,
            currentPage + 1,  // 1-indexed for display
            totalPages
        )

        clipboardAdapter.notifyDataSetChanged()
        invalidate()
    }

    /** Set listener for pagination state changes */
    fun setOnPaginationChangeListener(listener: (needsPagination: Boolean, currentPage: Int, totalPages: Int) -> Unit) {
        onPaginationChangeListener = listener
    }

    /** Get total number of pages */
    fun getTotalPages(): Int {
        val total = filteredHistory.size
        return if (total <= ITEMS_PER_PAGE) 1 else (total + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
    }

    /** Go to previous page */
    fun previousPage() {
        if (currentPage > 0) {
            currentPage--
            applyPagination()
        }
    }

    /** Go to next page */
    fun nextPage() {
        if (currentPage < getTotalPages() - 1) {
            currentPage++
            applyPagination()
        }
    }

    /** Check if there's a previous page */
    fun hasPreviousPage(): Boolean = currentPage > 0

    /** Check if there's a next page */
    fun hasNextPage(): Boolean = currentPage < getTotalPages() - 1

    /**
     * Pin or unpin the entry at index [pos] (position in current page).
     */
    fun pin_entry(pos: Int) {
        val clip = paginatedHistory[pos].content

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
        loadDataAsync()
    }

    /**
     * Add or remove entry from todos at index [pos] (position in current page).
     */
    fun todo_entry(pos: Int) {
        val clip = paginatedHistory[pos].content
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
        loadDataAsync()
    }

    /** Delete the specified entry from clipboard history (position in current page). */
    fun delete_entry(pos: Int) {
        val clip = paginatedHistory[pos].content
        service?.removeHistoryEntry(clip)
        // Clear expanded state for deleted entry
        expandedStates.remove(clip)
        loadDataAsync()
    }

    /** Send the specified entry to the editor (position in current page). */
    fun paste_entry(pos: Int) {
        ClipboardHistoryService.paste(paginatedHistory[pos].content)
    }

    override fun on_clipboard_history_change() {
        // Skip if not attached — onAttachedToWindow will load fresh data
        if (viewScope != null) {
            loadDataAsync()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility == VISIBLE) {
            loadDataAsync()
        }
    }

    /**
     * Loads clipboard data asynchronously off the UI thread.
     * Cancels any in-flight load to prevent stale data from a previous request
     * overwriting fresher results (e.g., rapid tab switching).
     *
     * #71: This was previously synchronous in update_data(), causing 2-3s UI freezes
     * with large clipboard histories.
     */
    private fun loadDataAsync() {
        loadJob?.cancel()
        loadJob = viewScope?.launch {
            val entries = withContext(Dispatchers.IO) {
                val database = ClipboardDatabase.getInstance(context)
                when (currentTab) {
                    ClipboardTab.HISTORY -> service?.clearExpiredAndGetHistory() ?: emptyList()
                    ClipboardTab.PINNED -> database.getPinnedEntries()
                    ClipboardTab.TODOS -> database.getTodoEntries()
                }
            }
            // Back on Main thread — atomic reference replacement
            history = entries
            applyFilter()
        }
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
        override fun getCount(): Int = paginatedHistory.size

        override fun getItem(pos: Int): Any = paginatedHistory[pos]

        override fun getItemId(pos: Int): Long = paginatedHistory[pos].hashCode().toLong()

        override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
            val view = v ?: View.inflate(context, R.layout.clipboard_history_entry, null)

            val entry = paginatedHistory[pos]
            val text = entry.content
            val textView = view.findViewById<TextView>(R.id.clipboard_entry_text)
            val expandButton = view.findViewById<View>(R.id.clipboard_entry_expand)
            val pinButton = view.findViewById<View>(R.id.clipboard_entry_addpin)
            val todoButton = view.findViewById<View>(R.id.clipboard_entry_addtodo)

            // Set text with timestamp appended
            textView.text = entry.getFormattedText(context)

            // Check if text contains newlines (multi-line)
            val isMultiLine = text.contains("\n")
            val isExpanded = expandedStates[text] == true

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
                    expandedStates[text] = !isExpanded
                    notifyDataSetChanged()
                }
            } else {
                expandButton.visibility = GONE
            }

            // Make text clickable to expand/collapse (all entries)
            textView.setOnClickListener {
                expandedStates[text] = !isExpanded
                notifyDataSetChanged()
            }

            // Long-press copies entry text to system clipboard
            textView.setOnLongClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CleverKeys", text))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                true
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
