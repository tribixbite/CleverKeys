package tribixbite.cleverkeys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

    // ─── Inline edit state ───
    // Position of the entry currently being edited (-1 = not editing)
    private var editingPosition: Int = -1
    // Original content before editing (used for database update + rollback)
    private var editingOriginalContent: String? = null
    // Reference to the active EditText widget for key routing (insertEditText/backspaceEditText)
    private var editingEditText: EditText? = null

    // Coroutine scope tied to window attach/detach lifecycle (IME has no ViewLifecycleOwner)
    private var viewScope: CoroutineScope? = null
    // Current async load job — cancelled on new load to prevent stale data
    private var loadJob: Job? = null

    companion object {
        const val ITEMS_PER_PAGE = 100

        /** Map MIME type to a fallback drawable icon when no thumbnail BLOB is available */
        fun getMimeTypeIcon(mimeType: String): Int = when {
            mimeType.startsWith("image/") -> R.drawable.ic_media_image
            mimeType.startsWith("video/") -> R.drawable.ic_media_video
            mimeType == "application/pdf" -> R.drawable.ic_media_pdf
            else -> R.drawable.ic_media_file
        }
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
        // Cancel any in-progress edit before switching tabs
        if (isEditing()) {
            editingPosition = -1
            editingOriginalContent = null
            editingEditText = null
        }
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
     * Carries media fields (mimeType, thumbnailBlob, mediaPath) through COPY semantics.
     */
    fun pin_entry(pos: Int) {
        val entry = paginatedHistory[pos]

        when (currentTab) {
            ClipboardTab.HISTORY -> {
                // Pin from history (COPY — history entry stays)
                service?.pinEntry(entry.content, entry.timestamp,
                    entry.mimeType, entry.thumbnailBlob, entry.mediaPath)
            }
            ClipboardTab.PINNED -> {
                // Unpin from pinned tab
                service?.unpinEntry(entry.content)
            }
            ClipboardTab.TODOS -> {
                // Pin todo item (can be both pinned and todo)
                service?.pinEntry(entry.content, entry.timestamp,
                    entry.mimeType, entry.thumbnailBlob, entry.mediaPath)
            }
        }
        loadDataAsync()
    }

    /**
     * Add or remove entry from todos at index [pos] (position in current page).
     * Carries media fields through COPY semantics.
     */
    fun todo_entry(pos: Int) {
        val entry = paginatedHistory[pos]

        when (currentTab) {
            ClipboardTab.HISTORY, ClipboardTab.PINNED -> {
                // Add to todos (COPY — original entry stays)
                service?.addToTodo(entry.content, entry.timestamp,
                    entry.mimeType, entry.thumbnailBlob, entry.mediaPath)
            }
            ClipboardTab.TODOS -> {
                // Remove from todos
                service?.removeFromTodo(entry.content)
            }
        }
        loadDataAsync()
    }

    /** Delete the specified entry from the current tab's backing store (position in current page). */
    fun delete_entry(pos: Int) {
        val clip = paginatedHistory[pos].content
        when (currentTab) {
            ClipboardTab.HISTORY -> service?.removeHistoryEntry(clip)
            ClipboardTab.PINNED -> service?.unpinEntry(clip)
            ClipboardTab.TODOS -> service?.removeFromTodo(clip)
        }
        expandedStates.remove(clip)
        loadDataAsync()
    }

    // ─── Inline edit mode ───

    /** Whether an entry is currently being edited inline */
    fun isEditing(): Boolean = editingPosition >= 0

    /** Enter inline edit mode for the entry at [pos] (position in current page) */
    fun edit_entry(pos: Int) {
        if (pos < 0 || pos >= paginatedHistory.size) return
        val entry = paginatedHistory[pos]
        // Media entries cannot be edited inline
        if (entry.isMedia) return

        editingPosition = pos
        editingOriginalContent = entry.content
        // Re-render to show EditText + save/cancel buttons for this entry
        clipboardAdapter.notifyDataSetChanged()
    }

    /** Commit the edited content to the database and exit edit mode */
    fun save_edit() {
        val oldContent = editingOriginalContent ?: return
        val newContent = editingEditText?.text?.toString() ?: return

        val result = service?.editEntryContent(oldContent, newContent, currentTab)
        when (result) {
            is EditEntryResult.Success -> {
                // Migrate expandedStates key if content changed
                val trimmedNew = newContent.trim()
                if (oldContent.trim() != trimmedNew) {
                    val wasExpanded = expandedStates.remove(oldContent)
                    if (wasExpanded == true) expandedStates[trimmedNew] = true
                }
            }
            is EditEntryResult.DuplicateConflict ->
                Toast.makeText(context, R.string.clipboard_edit_duplicate, Toast.LENGTH_SHORT).show()
            is EditEntryResult.InvalidContent ->
                Toast.makeText(context, R.string.clipboard_edit_invalid, Toast.LENGTH_SHORT).show()
            is EditEntryResult.Error ->
                Toast.makeText(context, R.string.clipboard_edit_error, Toast.LENGTH_SHORT).show()
            null ->
                Toast.makeText(context, R.string.clipboard_edit_error, Toast.LENGTH_SHORT).show()
        }
        cancelEdit()
    }

    /** Discard edits and exit edit mode */
    fun cancelEdit() {
        if (editingPosition < 0) return
        editingPosition = -1
        editingOriginalContent = null
        editingEditText = null
        clipboardAdapter.notifyDataSetChanged()
    }

    /** Insert text at cursor position in the active edit field (called by key routing) */
    fun insertEditText(text: String) {
        editingEditText?.let { et ->
            val start = et.selectionStart.coerceAtLeast(0)
            val end = et.selectionEnd.coerceAtLeast(start)
            et.text.replace(minOf(start, end), maxOf(start, end), text)
        }
    }

    /** Handle backspace in the active edit field (called by key routing) */
    fun backspaceEditText() {
        editingEditText?.let { et ->
            val start = et.selectionStart
            val end = et.selectionEnd
            if (start != end) {
                // Delete selection
                et.text.delete(minOf(start, end), maxOf(start, end))
            } else if (start > 0) {
                // Delete single char before cursor
                et.text.delete(start - 1, start)
            }
        }
    }

    /** Paste system clipboard content into the active edit field */
    fun pasteToEditText() {
        editingEditText?.let { et ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val pasteText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: return
            val start = et.selectionStart.coerceAtLeast(0)
            val end = et.selectionEnd.coerceAtLeast(start)
            et.text.replace(minOf(start, end), maxOf(start, end), pasteText)
        }
    }

    /** Cut selected text from the edit field to system clipboard */
    fun cutFromEditText() {
        editingEditText?.let { et ->
            val start = et.selectionStart
            val end = et.selectionEnd
            if (start != end) {
                val selected = et.text.substring(minOf(start, end), maxOf(start, end))
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("CleverKeys", selected))
                et.text.delete(minOf(start, end), maxOf(start, end))
            }
        }
    }

    /** Select all text in the active edit field */
    fun selectAllEditText() {
        editingEditText?.selectAll()
    }

    /** Send the specified entry to the editor (position in current page). */
    fun paste_entry(pos: Int) {
        val entry = paginatedHistory[pos]
        if (entry.isMedia && entry.mediaPath != null) {
            // Media entry — use commitContent to send to target app
            val success = ClipboardHistoryService.pasteMedia(entry.mimeType, entry.mediaPath)
            if (!success) {
                Toast.makeText(context, "Cannot paste media here", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Text entry — use standard commitText path
            ClipboardHistoryService.paste(entry.content)
        }
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
            var entries = withContext(Dispatchers.IO) {
                val database = ClipboardDatabase.getInstance(context)
                when (currentTab) {
                    ClipboardTab.HISTORY -> service?.clearExpiredAndGetHistory() ?: emptyList()
                    ClipboardTab.PINNED -> database.getPinnedEntries()
                    ClipboardTab.TODOS -> database.getTodoEntries()
                }
            }
            // Filter out media entries when text-only mode is active
            if (Config.globalConfig().clipboard_text_only) {
                entries = entries.filter { !it.isMedia }
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
            val editField = view.findViewById<EditText>(R.id.clipboard_entry_edit_field)
            val expandButton = view.findViewById<View>(R.id.clipboard_entry_expand)
            val editButton = view.findViewById<View>(R.id.clipboard_entry_edit)
            val pinButton = view.findViewById<View>(R.id.clipboard_entry_addpin)
            val todoButton = view.findViewById<View>(R.id.clipboard_entry_addtodo)
            val normalButtons = view.findViewById<LinearLayout>(R.id.clipboard_entry_normal_buttons)
            val editButtons = view.findViewById<LinearLayout>(R.id.clipboard_entry_edit_buttons)
            val thumbnailContainer = view.findViewById<FrameLayout>(R.id.clipboard_entry_thumbnail_container)
            val thumbnailView = view.findViewById<ImageView>(R.id.clipboard_entry_thumbnail)
            val playBadge = view.findViewById<ImageView>(R.id.clipboard_entry_play_badge)

            val isEditingThis = (pos == editingPosition)

            // ── Edit mode: show EditText + save/cancel, hide normal UI ──
            if (isEditingThis) {
                textView.visibility = GONE
                editField.visibility = VISIBLE
                normalButtons.visibility = GONE
                editButtons.visibility = VISIBLE
                thumbnailContainer.visibility = GONE
                playBadge.visibility = GONE

                // Pre-fill with current content, cursor at end
                editField.setText(text)
                editField.setSelection(editField.text.length)
                // Store reference for key routing (insertEditText/backspaceEditText)
                editingEditText = editField

                // Save button
                view.findViewById<View>(R.id.clipboard_entry_save).setOnClickListener {
                    save_edit()
                }
                // Cancel button
                view.findViewById<View>(R.id.clipboard_entry_cancel).setOnClickListener {
                    cancelEdit()
                }

                return view
            }

            // ── Normal mode: standard rendering ──
            textView.visibility = VISIBLE
            editField.visibility = GONE
            normalButtons.visibility = VISIBLE
            editButtons.visibility = GONE

            // ── Media thumbnail rendering ──
            if (entry.isMedia) {
                thumbnailContainer.visibility = VISIBLE
                if (entry.hasThumbnail) {
                    // Decode thumbnail from BLOB — small (≤10KB WebP), no file I/O
                    val bitmap = BitmapFactory.decodeByteArray(
                        entry.thumbnailBlob, 0, entry.thumbnailBlob!!.size
                    )
                    thumbnailView.setImageBitmap(bitmap)
                    thumbnailView.scaleType = ImageView.ScaleType.CENTER_CROP
                } else {
                    // No thumbnail — show MIME-type fallback icon
                    thumbnailView.setImageResource(getMimeTypeIcon(entry.mimeType))
                    thumbnailView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                // Show play badge for animated media (GIF, animated WebP)
                val isAnimated = entry.mimeType == "image/gif" ||
                    (entry.mimeType == "image/webp" && entry.mediaPath != null)
                playBadge.visibility = if (isAnimated) VISIBLE else GONE

                // For media entries, show MIME label + filename instead of content body
                textView.text = entry.getFormattedText(context)
            } else {
                thumbnailContainer.visibility = GONE
                playBadge.visibility = GONE
                // Set text with timestamp appended
                textView.text = entry.getFormattedText(context)
            }

            // Check if text contains newlines (multi-line) — applies to text entries
            val isMultiLine = text.contains("\n")
            val isExpanded = expandedStates[text] == true

            // Set maxLines based on expanded state (applies to all entries)
            if (isExpanded) {
                textView.maxLines = Int.MAX_VALUE
                textView.ellipsize = null
            } else {
                textView.maxLines = if (entry.isMedia) 2 else 1
                textView.ellipsize = android.text.TextUtils.TruncateAt.END
            }

            // Show expand button only for multi-line text entries
            if (isMultiLine && !entry.isMedia) {
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

            // Show edit button for text entries only (media entries cannot be edited inline)
            editButton.visibility = if (!entry.isMedia) VISIBLE else GONE
            editButton.setOnClickListener {
                edit_entry(pos)
            }

            // Make text clickable to expand/collapse (text entries only)
            textView.setOnClickListener {
                if (!entry.isMedia) {
                    expandedStates[text] = !isExpanded
                    notifyDataSetChanged()
                }
            }

            // Long-press copies entry text to system clipboard
            textView.setOnLongClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CleverKeys", text))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                true
            }

            // Show pin/todo buttons only when their respective tabs are enabled
            val cfg = Config.globalConfig()
            pinButton.visibility = if (cfg.clipboard_pinned_enabled) VISIBLE else GONE
            todoButton.visibility = if (cfg.clipboard_todo_enabled) VISIBLE else GONE

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
