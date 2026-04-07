package tribixbite.cleverkeys

import android.content.ClipData
// NOTE: android.content.ClipboardManager is imported with alias to avoid shadowing by
// tribixbite.cleverkeys.ClipboardManager (same-package takes priority in Kotlin resolution).
import android.content.ClipboardManager as SystemClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.util.LruCache
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
import java.util.regex.PatternSyntaxException

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
    private var regexMode = false
    private var searchRegexError = false
    private val service: ClipboardHistoryService?
    private val clipboardAdapter: ClipboardEntriesAdapter

    // Current tab mode
    private var currentTab = ClipboardTab.HISTORY

    // Track expanded state: entry timestamp -> isExpanded (survives reorder/delete)
    // Uses timestamp (Long) as key instead of full content string to avoid duplicating
    // large clipboard entries in memory just for expand/collapse tracking.
    private val expandedStates = mutableMapOf<Long, Boolean>()

    // LRU cache for decoded media thumbnails. Keyed by entry timestamp.
    // 80×80 ARGB_8888 = ~25KB per bitmap. 30 entries ≈ 750KB — well within limits.
    // Avoids re-decoding BitmapFactory.decodeByteArray on every getView() scroll.
    private val thumbnailCache = object : LruCache<Long, Bitmap>(30) {
        override fun sizeOf(key: Long, value: Bitmap): Int = 1  // count-based, not byte-based
    }

    // Date filter state
    private var dateFilterEnabled = false
    private var dateFilterBefore = false // true = before date, false = after date
    private var dateFilterTimestamp = 0L

    // Tag filter — entry must have at least one (OR) or all (AND) selected tags. Empty = show all.
    private var tagFilterSelected: Set<String> = emptySet()
    private var tagFilterMatchAll = false  // false=OR (any tag), true=AND (all tags)

    // Todo status filter — entry status must be in enabled set. Defaults: active only.
    private var statusFilterActive = true
    private var statusFilterPlanned = false
    private var statusFilterCompleted = false

    // Pagination state
    private var currentPage = 0
    private var onPaginationChangeListener: ((needsPagination: Boolean, currentPage: Int, totalPages: Int) -> Unit)? = null

    // ─── Inline edit state ───
    // Original content before editing — used as identity key to find entry across list rebuilds.
    // Non-null means edit mode is active. Replaces position-based tracking (Bug #2).
    private var editingOriginalContent: String? = null
    // In-progress text that the user is typing — preserved across view recreation (Bug #3).
    // Initialized to DB content on edit_entry(), updated by TextWatcher.
    private var editingInProgressText: String? = null
    // Cursor position preserved across view recycling — updated after every text op
    private var editingCursorPosition: Int? = null
    // Reference to the active EditText widget for key routing (insertEditText/backspaceEditText)
    private var editingEditText: EditText? = null
    // TextWatcher reference — tracked to prevent accumulation on view recycling.
    // Removed from old EditText before adding to new one in getView().
    private var editingTextWatcher: android.text.TextWatcher? = null
    // Callbacks for edit mode transitions — used by ClipboardManager to lock/unlock UI controls
    var onEditModeEntered: (() -> Unit)? = null
    var onEditModeExited: (() -> Unit)? = null

    // ─── Tag panel callback ───
    // Invoked when user taps tags button — ClipboardManager shows inline tag panel
    var onTagPanelRequested: ((entry: ClipboardEntry, tab: ClipboardTab) -> Unit)? = null

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

        /** Delegates to [ClipboardSearchUtils.expandGlobShorthand] */
        fun expandGlobShorthand(query: String): String =
            ClipboardSearchUtils.expandGlobShorthand(query)
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
        // Cancel any in-progress edit before switching tabs (safety — tab clicks
        // are guarded in ClipboardManager, but direct callers like resetSearchOnShow need this)
        cancelEdit()
        currentTab = tab
        expandedStates.clear()
        thumbnailCache.evictAll()
        // Reset tag/status filters on tab switch — tags are tab-specific
        // NOTE: searchFilter, regexMode, dateFilter* are NOT reset — they persist across tabs
        tagFilterSelected = emptySet()
        tagFilterMatchAll = false
        statusFilterActive = true
        statusFilterPlanned = false
        statusFilterCompleted = false
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

    /** Toggle regex search mode — when enabled, search filter is interpreted as regex */
    fun setRegexMode(enabled: Boolean) {
        regexMode = enabled
        applyFilter()
    }

    /** Whether regex search mode is currently active */
    fun isRegexMode(): Boolean = regexMode

    /** Whether the current regex pattern has a syntax error */
    fun hasRegexError(): Boolean = searchRegexError

    private fun applyFilter() {
        // Pre-compile regex once per filter pass (not per entry)
        val compiledRegex: Regex? = if (regexMode && searchFilter.isNotEmpty()) {
            try {
                val pattern = expandGlobShorthand(searchFilter)
                searchRegexError = false
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (e: PatternSyntaxException) {
                searchRegexError = true
                null  // invalid regex → match nothing
            }
        } else {
            searchRegexError = false
            null
        }

        // Apply both search and date filters (searches ALL items)
        val filtered = history.filter { entry ->
            // Apply search filter
            if (searchFilter.isNotEmpty()) {
                if (regexMode) {
                    // Regex mode: use compiled pattern or reject all on syntax error
                    if (compiledRegex == null || !compiledRegex.containsMatchIn(entry.content)) {
                        return@filter false
                    }
                } else {
                    // Plain text mode: case-insensitive substring match
                    if (!entry.content.lowercase().contains(searchFilter)) {
                        return@filter false
                    }
                }
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

            // Tag filter (PINNED/TODOS tabs — when tags are selected)
            if (tagFilterSelected.isNotEmpty()) {
                if (entry.tags.isEmpty()) {
                    // Entries with no tags don't match any tag filter
                    return@filter false
                }
                val matches = if (tagFilterMatchAll) {
                    tagFilterSelected.all { it in entry.tags }  // AND: must have all
                } else {
                    entry.tags.any { it in tagFilterSelected }  // OR: any match
                }
                if (!matches) return@filter false
            }

            // Status filter (TODOS tab only — filter by active/planned/completed)
            if (currentTab == ClipboardTab.TODOS) {
                val status = entry.todoStatus ?: TodoEntry.STATUS_ACTIVE
                val passes = when (status) {
                    TodoEntry.STATUS_ACTIVE -> statusFilterActive
                    TodoEntry.STATUS_PLANNED -> statusFilterPlanned
                    TodoEntry.STATUS_COMPLETED -> statusFilterCompleted
                    else -> statusFilterActive  // unknown status treated as active
                }
                if (!passes) return@filter false
            }

            true
        }

        // If no filters are active, show all history (skip filter pass optimization)
        // Status filter is active when not ALL three statuses are enabled (truly "show all").
        // The default state (only active=true) IS a filter — it hides planned/completed items.
        val hasStatusFilter = currentTab == ClipboardTab.TODOS &&
            !(statusFilterActive && statusFilterPlanned && statusFilterCompleted)
        filteredHistory = if (searchFilter.isEmpty() && !dateFilterEnabled &&
            tagFilterSelected.isEmpty() && !hasStatusFilter) {
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

        // Clear UI state when page changes
        expandedStates.clear()
        thumbnailCache.evictAll()

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
        val entry = paginatedHistory[pos]
        // Always exit edit mode when deleting — the entry being edited is removed,
        // and list positions shift. Without this, isEditing() stays true and blocks all UI.
        if (isEditing()) {
            cancelEdit()
        }
        when (currentTab) {
            ClipboardTab.HISTORY -> service?.removeHistoryEntry(entry.content)
            ClipboardTab.PINNED -> service?.unpinEntry(entry.content)
            ClipboardTab.TODOS -> service?.removeFromTodo(entry.content)
        }
        expandedStates.remove(entry.timestamp)
        loadDataAsync()
    }

    // ─── Inline edit mode ───

    /** Whether an entry is currently being edited inline */
    fun isEditing(): Boolean = editingOriginalContent != null

    /** Enter inline edit mode for the entry at [pos] (position in current page) */
    fun edit_entry(pos: Int) {
        if (pos < 0 || pos >= paginatedHistory.size) return
        val entry = paginatedHistory[pos]
        // Media entries cannot be edited inline
        if (entry.isMedia) return

        // Bug #1 fix: clear search mode before entering edit (mutual exclusion)
        onEditModeEntered?.invoke()

        editingOriginalContent = entry.content
        editingInProgressText = entry.content
        editingCursorPosition = entry.content.length
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
                // Timestamp key doesn't change when content is edited — no migration needed
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
        if (editingOriginalContent == null) return
        editingOriginalContent = null
        editingInProgressText = null
        editingCursorPosition = null
        // Remove TextWatcher before dropping reference to prevent stale callbacks
        editingTextWatcher?.let { editingEditText?.removeTextChangedListener(it) }
        editingTextWatcher = null
        editingEditText = null
        // Notify ClipboardManager to re-enable search/tabs/pagination UI
        onEditModeExited?.invoke()
        // Reload data to pick up any changes that were suppressed during edit
        loadDataAsync()
    }

    // ── IME-owned EditText manipulation ──────────────────────────────────
    // Android IMEs cannot use editable.replace()/editable.delete()/dispatchKeyEvent()
    // on their own EditText views — those APIs are unreliable when the IME owns the view.
    // All methods use setText() + setSelection() pattern (same as tag/search modes).

    /** Insert text at cursor/selection in the active edit field (called by key routing).
     *  Trusts et.selectionStart/End first (reliable with textMultiLine inputType),
     *  falls back to editingCursorPosition only for view-recycling recovery. */
    fun insertEditText(text: String) {
        editingEditText?.let { et ->
            val oldText = et.text.toString()
            // Trust EditText selection first (reliable with textMultiLine), tracked cursor as fallback
            val selStart = et.selectionStart
            val selEnd = et.selectionEnd
            val hasValidSelection = selStart >= 0 && selEnd >= 0
            val lo = if (hasValidSelection) minOf(selStart, selEnd) else
                (editingCursorPosition ?: oldText.length)
            val hi = if (hasValidSelection) maxOf(selStart, selEnd) else lo
            val safeLo = lo.coerceIn(0, oldText.length)
            val safeHi = hi.coerceIn(0, oldText.length)

            // Replace selection range (or insert at cursor when lo==hi)
            val newText = oldText.substring(0, safeLo) + text + oldText.substring(safeHi)
            val newCursorPos = (safeLo + text.length).coerceIn(0, newText.length)

            et.setText(newText)
            et.setSelection(newCursorPos)
            editingCursorPosition = newCursorPos
        }
    }

    /** Handle backspace in the active edit field (called by key routing).
     *  If selection exists, deletes selected range. Otherwise deletes char before cursor. */
    fun backspaceEditText() {
        editingEditText?.let { et ->
            val oldText = et.text.toString()
            if (oldText.isEmpty()) return

            // Trust EditText selection first, tracked cursor as fallback
            val selStart = et.selectionStart
            val selEnd = et.selectionEnd
            val hasSelection = selStart >= 0 && selEnd >= 0 && selStart != selEnd

            val newText: String
            val newCursorPos: Int

            if (hasSelection) {
                // Delete selected range (e.g., after selectAll)
                val lo = minOf(selStart, selEnd).coerceIn(0, oldText.length)
                val hi = maxOf(selStart, selEnd).coerceIn(0, oldText.length)
                newText = oldText.removeRange(lo, hi)
                newCursorPos = lo.coerceIn(0, newText.length)
            } else {
                // No selection — delete character before cursor
                val cursor = (if (selStart >= 0) selStart else
                    (editingCursorPosition ?: oldText.length)).coerceIn(0, oldText.length)
                if (cursor <= 0) return
                newText = oldText.removeRange(cursor - 1, cursor)
                newCursorPos = cursor - 1
            }

            et.setText(newText)
            et.setSelection(newCursorPos.coerceIn(0, newText.length))
            editingCursorPosition = newCursorPos.coerceIn(0, newText.length)
        }
    }

    /** Paste system clipboard content into the active edit field */
    fun pasteToEditText() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? SystemClipboardManager
        val pasteText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        insertEditText(pasteText)
    }

    /** Cut selected text from the edit field to system clipboard.
     *  Requires active selection (e.g., from selectAll) — no-op if no selection. */
    fun cutFromEditText() {
        editingEditText?.let { et ->
            val oldText = et.text.toString()
            val selStart = et.selectionStart
            val selEnd = et.selectionEnd

            if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
                val lo = minOf(selStart, selEnd).coerceIn(0, oldText.length)
                val hi = maxOf(selStart, selEnd).coerceIn(0, oldText.length)
                val selected = oldText.substring(lo, hi)

                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? SystemClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("CleverKeys", selected))

                val newText = oldText.removeRange(lo, hi)
                et.setText(newText)
                et.setSelection(lo.coerceIn(0, newText.length))
                editingCursorPosition = lo.coerceIn(0, newText.length)
            }
        }
    }

    /** Select all text in the active edit field */
    fun selectAllEditText() {
        editingEditText?.let { et ->
            et.selectAll()
            // Track end-of-text so view recycling has a safe fallback position
            editingCursorPosition = et.text.length
        }
    }

    /** Handle arrow keys, Enter, Home, End in the active edit field.
     *  Manual cursor manipulation — dispatchKeyEvent() is a no-op on IME-owned EditText. */
    fun dispatchKeyToEditText(keyCode: Int) {
        editingEditText?.let { et ->
            val text = et.text.toString()
            // Trust EditText selection first (reliable with textMultiLine), tracked cursor as fallback
            val selStart = et.selectionStart
            val selEnd = et.selectionEnd
            val hasSelection = selStart >= 0 && selEnd >= 0 && selStart != selEnd
            val cursorPos = if (selStart >= 0) selStart.coerceIn(0, text.length) else
                (editingCursorPosition ?: text.length).coerceIn(0, text.length)
            val start = if (hasSelection) selStart.coerceIn(0, text.length) else cursorPos
            val end = if (hasSelection) selEnd.coerceIn(0, text.length) else cursorPos

            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // Collapse selection to start, or move left by 1
                    val newPos = if (hasSelection) minOf(start, end) else (start - 1).coerceAtLeast(0)
                    et.setSelection(newPos)
                    editingCursorPosition = newPos
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Collapse selection to end, or move right by 1
                    val newPos = if (hasSelection) maxOf(start, end) else (end + 1).coerceAtMost(text.length)
                    et.setSelection(newPos)
                    editingCursorPosition = newPos
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    // Use cursor position (collapse selection if any)
                    val pos = if (hasSelection) minOf(start, end) else start
                    val lineStart = text.lastIndexOf('\n', pos - 1) + 1
                    if (lineStart > 0) {
                        val prevLineEnd = lineStart - 1
                        val prevLineStart = text.lastIndexOf('\n', prevLineEnd - 1) + 1
                        val offset = pos - lineStart
                        val newPos = (prevLineStart + offset).coerceAtMost(prevLineEnd)
                        et.setSelection(newPos)
                        editingCursorPosition = newPos
                    } else {
                        et.setSelection(0)
                        editingCursorPosition = 0
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Use cursor position (collapse selection if any)
                    val pos = if (hasSelection) maxOf(start, end) else start
                    var lineEnd = text.indexOf('\n', pos)
                    if (lineEnd != -1) {
                        val lineStart = text.lastIndexOf('\n', pos - 1) + 1
                        val offset = pos - lineStart
                        val nextLineStart = lineEnd + 1
                        var nextLineEnd = text.indexOf('\n', nextLineStart)
                        if (nextLineEnd == -1) nextLineEnd = text.length
                        val newPos = (nextLineStart + offset).coerceAtMost(nextLineEnd)
                        et.setSelection(newPos)
                        editingCursorPosition = newPos
                    } else {
                        et.setSelection(text.length)
                        editingCursorPosition = text.length
                    }
                }
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    insertEditText("\n")
                }
                android.view.KeyEvent.KEYCODE_MOVE_HOME -> {
                    val pos = if (hasSelection) minOf(start, end) else start
                    val lineStart = text.lastIndexOf('\n', pos - 1) + 1
                    et.setSelection(lineStart)
                    editingCursorPosition = lineStart
                }
                android.view.KeyEvent.KEYCODE_MOVE_END -> {
                    val pos = if (hasSelection) maxOf(start, end) else start
                    var lineEnd = text.indexOf('\n', pos)
                    if (lineEnd == -1) lineEnd = text.length
                    et.setSelection(lineEnd)
                    editingCursorPosition = lineEnd
                }
            }
        }
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
        // Suppress reload during inline edit to prevent view recreation that would
        // overwrite the user's in-progress text. Deferred reload fires on cancelEdit/save_edit.
        if (isEditing()) return
        // Skip if not attached — onAttachedToWindow will load fresh data
        if (viewScope != null) {
            loadDataAsync()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility == VISIBLE) {
            // Suppress reload during edit — same guard as on_clipboard_history_change().
            // Window visibility changes during keyboard redraw would otherwise trigger
            // view recreation that resets the EditText cursor and accumulates TextWatchers.
            if (!isEditing()) {
                loadDataAsync()
            }
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

    /**
     * Reload data without updating the UI — used by tag panel to keep backing data fresh
     * so the entry list is current when the panel closes.
     */
    fun reloadInBackground() {
        loadDataAsync()
    }

    /**
     * Force a full data reload and UI update — used by ClipboardManager when tag panel closes.
     * Public wrapper for loadDataAsync().
     */
    fun loadDataForce() {
        loadDataAsync()
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

    // ─── Tag filter methods ───

    /** Set which tags to filter by and whether to match all (AND) or any (OR) */
    fun setTagFilter(selected: Set<String>, matchAll: Boolean) {
        tagFilterSelected = selected
        tagFilterMatchAll = matchAll
        applyFilter()
    }

    /** Currently selected tag filter set */
    fun getTagFilter(): Set<String> = tagFilterSelected

    /** Whether tag filter uses AND (true) or OR (false) semantics */
    fun isTagFilterMatchAll(): Boolean = tagFilterMatchAll

    // ─── Status filter methods (TODOS tab) ───

    /** Set which todo statuses are visible */
    fun setStatusFilter(active: Boolean, planned: Boolean, completed: Boolean) {
        statusFilterActive = active
        statusFilterPlanned = planned
        statusFilterCompleted = completed
        applyFilter()
    }

    /** Current status filter state: (active, planned, completed) */
    fun getStatusFilter(): Triple<Boolean, Boolean, Boolean> =
        Triple(statusFilterActive, statusFilterPlanned, statusFilterCompleted)

    // ─── Combined filter operations ───

    /** Clear all filters (date + tags + status) and reset to defaults */
    fun clearAllFilters() {
        dateFilterEnabled = false
        dateFilterTimestamp = 0
        dateFilterBefore = false
        tagFilterSelected = emptySet()
        tagFilterMatchAll = false
        statusFilterActive = true
        statusFilterPlanned = false
        statusFilterCompleted = false
        applyFilter()
    }

    /** Whether any filter is active (non-default state) — used for filter icon tinting */
    fun hasActiveFilters(): Boolean {
        if (dateFilterEnabled) return true
        if (tagFilterSelected.isNotEmpty()) return true
        // Status filter is "active" when not at defaults (only Active checked)
        if (currentTab == ClipboardTab.TODOS) {
            if (!statusFilterActive || statusFilterPlanned || statusFilterCompleted) return true
        }
        return false
    }

    /**
     * Cycle todo status: active → planned → completed → active.
     * Updates via service (which notifies DB + triggers UI refresh).
     */
    private fun cycleTodoStatus(entry: ClipboardEntry) {
        val next = when (entry.todoStatus) {
            TodoEntry.STATUS_ACTIVE -> TodoEntry.STATUS_PLANNED
            TodoEntry.STATUS_PLANNED -> TodoEntry.STATUS_COMPLETED
            TodoEntry.STATUS_COMPLETED -> TodoEntry.STATUS_ACTIVE
            else -> TodoEntry.STATUS_ACTIVE
        }
        service?.setTodoStatus(entry.content, next)
        loadDataAsync()
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
            val primaryButtons = view.findViewById<LinearLayout>(R.id.clipboard_entry_primary_buttons)
            val editButtons = view.findViewById<LinearLayout>(R.id.clipboard_entry_edit_buttons)
            val deleteRow = view.findViewById<LinearLayout>(R.id.clipboard_entry_delete_row)
            val secondaryButtons = view.findViewById<LinearLayout>(R.id.clipboard_entry_secondary_buttons)
            val thumbnailContainer = view.findViewById<FrameLayout>(R.id.clipboard_entry_thumbnail_container)
            val thumbnailView = view.findViewById<ImageView>(R.id.clipboard_entry_thumbnail)
            val playBadge = view.findViewById<ImageView>(R.id.clipboard_entry_play_badge)

            // Secondary row buttons (shown on tap-expand, tab-specific)
            val pinButton = view.findViewById<View>(R.id.clipboard_entry_addpin)
            val unpinButton = view.findViewById<View>(R.id.clipboard_entry_unpin)
            val todoButton = view.findViewById<View>(R.id.clipboard_entry_addtodo)
            val doneButton = view.findViewById<View>(R.id.clipboard_entry_done)
            val statusButton = view.findViewById<View>(R.id.clipboard_entry_status)
            val tagsButton = view.findViewById<View>(R.id.clipboard_entry_tags)
            // Delete is in the edit_buttons row (only visible during edit mode)
            val deleteButton = view.findViewById<View>(R.id.clipboard_entry_delete)

            // Bug #2 fix: match by content identity, not list position — survives list shifts
            val isEditingThis = editingOriginalContent != null && entry.content == editingOriginalContent

            // ── Edit mode: show EditText + save/cancel, hide normal UI ──
            if (isEditingThis) {
                textView.visibility = GONE
                editField.visibility = VISIBLE
                primaryButtons.visibility = GONE
                editButtons.visibility = VISIBLE
                deleteRow.visibility = VISIBLE
                secondaryButtons.visibility = GONE
                thumbnailContainer.visibility = GONE
                playBadge.visibility = GONE

                // Bug #3 fix: use in-progress text (not DB content) to survive view recreation
                val displayText = editingInProgressText ?: text
                // Capture cursor BEFORE setText — setText() resets cursor to 0 and
                // would corrupt editingCursorPosition if the TextWatcher tracked it
                val cursorPos = editingCursorPosition ?: displayText.length
                editField.setText(displayText)
                editField.setSelection(cursorPos.coerceIn(0, displayText.length))
                // Store reference for key routing (insertEditText/backspaceEditText).
                // Remove old TextWatcher before adding new one to prevent accumulation
                // when getView() is called multiple times (view recycling).
                editingTextWatcher?.let { oldWatcher ->
                    editingEditText?.removeTextChangedListener(oldWatcher)
                }
                editingEditText = editField

                // Save button — disabled when content is blank to prevent confusing error toast
                val saveButton = view.findViewById<View>(R.id.clipboard_entry_save)
                saveButton.isEnabled = displayText.isNotBlank()
                saveButton.alpha = if (displayText.isNotBlank()) 1.0f else 0.3f

                // Sync editingInProgressText via TextWatcher so it survives view recreation
                val watcher = object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        editingInProgressText = s?.toString()
                        // NOTE: Do NOT update editingCursorPosition here.
                        // setText() resets cursor to 0 before our setSelection() corrects it.
                        // The TextWatcher fires between those two calls, capturing the wrong
                        // position (0). Cursor is tracked explicitly in insertEditText/backspace/etc.
                        // Disable save when content is blank
                        val canSave = !s.isNullOrBlank()
                        saveButton.isEnabled = canSave
                        saveButton.alpha = if (canSave) 1.0f else 0.3f
                    }
                }
                editField.addTextChangedListener(watcher)
                editingTextWatcher = watcher

                saveButton.setOnClickListener {
                    save_edit()
                }
                // Cancel button
                view.findViewById<View>(R.id.clipboard_entry_cancel).setOnClickListener {
                    cancelEdit()
                }
                // Delete button — gated behind edit mode for safety
                deleteButton.setOnClickListener {
                    delete_entry(pos)
                }

                return view
            }

            // ── Normal mode: standard rendering ──
            textView.visibility = VISIBLE
            editField.visibility = GONE
            primaryButtons.visibility = VISIBLE
            editButtons.visibility = GONE
            deleteRow.visibility = GONE

            // ── Media thumbnail rendering ──
            if (entry.isMedia) {
                thumbnailContainer.visibility = VISIBLE
                if (entry.hasThumbnail) {
                    // Use LRU cache to avoid re-decoding on every scroll
                    val bitmap = thumbnailCache.get(entry.timestamp)
                        ?: BitmapFactory.decodeByteArray(
                            entry.thumbnailBlob, 0, entry.thumbnailBlob!!.size
                        )?.also { thumbnailCache.put(entry.timestamp, it) }
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

                // ── Todo status visual indicator (prefix + strikethrough) ──
                if (currentTab == ClipboardTab.TODOS && entry.todoStatus != null) {
                    val prefix = when (entry.todoStatus) {
                        TodoEntry.STATUS_COMPLETED -> "[done] "
                        TodoEntry.STATUS_PLANNED -> "[plan] "
                        else -> ""
                    }
                    // Non-breaking spaces so time suffix never wraps mid-unit
                    val timeStr = "\u00A0\u00B7\u00A0${entry.getRelativeTime().replace(' ', '\u00A0')}"
                    val spannable = android.text.SpannableStringBuilder(prefix + entry.content).append(timeStr)

                    // Strikethrough the content portion (not prefix or timestamp) for completed
                    if (entry.todoStatus == TodoEntry.STATUS_COMPLETED) {
                        spannable.setSpan(
                            android.text.style.StrikethroughSpan(),
                            prefix.length,
                            prefix.length + entry.content.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    // Dim timestamp suffix
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(
                            androidx.core.content.ContextCompat.getColor(context, android.R.color.secondary_text_dark)
                        ),
                        prefix.length + entry.content.length,
                        spannable.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    textView.text = spannable
                } else {
                    textView.text = entry.getFormattedText(context)
                }
            }

            // ── Expand state: single tap toggles text expansion + secondary row ──
            val isMultiLine = text.contains("\n")
            val isExpanded = expandedStates[entry.timestamp] == true

            // Set maxLines based on expanded state (applies to all entries)
            if (isExpanded) {
                textView.maxLines = Int.MAX_VALUE
                textView.ellipsize = null
            } else {
                textView.maxLines = if (entry.isMedia) 2 else 1
                textView.ellipsize = android.text.TextUtils.TruncateAt.END
            }

            // Expand chevron shown for multi-line text — rotates as visual indicator
            if (isMultiLine && !entry.isMedia) {
                expandButton.visibility = VISIBLE
                expandButton.rotation = if (isExpanded) 180f else 0f
            } else {
                expandButton.visibility = GONE
            }

            // Show edit button for text entries only (media entries cannot be edited inline)
            editButton.visibility = if (!entry.isMedia) VISIBLE else GONE
            editButton.setOnClickListener {
                if (!isEditing()) edit_entry(pos)
            }

            // ── Secondary buttons: VISIBLE when expanded, GONE otherwise ──
            secondaryButtons.visibility = if (isExpanded && !isEditingThis) VISIBLE else GONE

            // ── Tab-aware button visibility in secondary row ──
            // Delete is NOT here — it's gated behind edit mode in the edit_buttons row
            val cfg = Config.globalConfig()
            when (currentTab) {
                ClipboardTab.HISTORY -> {
                    // History: pin (if enabled), todo (if enabled)
                    pinButton.visibility = if (cfg.clipboard_pinned_enabled) VISIBLE else GONE
                    unpinButton.visibility = GONE
                    todoButton.visibility = if (cfg.clipboard_todo_enabled) VISIBLE else GONE
                    doneButton.visibility = GONE
                    statusButton.visibility = GONE
                    tagsButton.visibility = GONE
                }
                ClipboardTab.PINNED -> {
                    // Pinned: unpin, todo (if enabled), tags
                    pinButton.visibility = GONE
                    unpinButton.visibility = VISIBLE
                    todoButton.visibility = if (cfg.clipboard_todo_enabled) VISIBLE else GONE
                    doneButton.visibility = GONE
                    statusButton.visibility = GONE
                    tagsButton.visibility = VISIBLE
                }
                ClipboardTab.TODOS -> {
                    // Todos: done (mark completed), status cycle, tags
                    pinButton.visibility = GONE
                    unpinButton.visibility = GONE
                    todoButton.visibility = GONE
                    doneButton.visibility = VISIBLE
                    statusButton.visibility = VISIBLE
                    tagsButton.visibility = VISIBLE
                    // Status button alpha reflects current state
                    statusButton.alpha = when (entry.todoStatus) {
                        TodoEntry.STATUS_ACTIVE -> 1.0f
                        TodoEntry.STATUS_PLANNED -> 0.7f
                        TodoEntry.STATUS_COMPLETED -> 0.4f
                        else -> 1.0f
                    }
                }
            }

            // ── Click handlers ──

            // Tap text to toggle expand/collapse (all entries, not just multi-line)
            textView.setOnClickListener {
                expandedStates[entry.timestamp] = !isExpanded
                notifyDataSetChanged()
            }

            // Expand chevron also toggles
            expandButton.setOnClickListener {
                expandedStates[entry.timestamp] = !isExpanded
                notifyDataSetChanged()
            }

            // Long-press copies entry text to system clipboard
            textView.setOnLongClickListener {
                if (isEditing()) return@setOnLongClickListener true  // Block during edit
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as SystemClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CleverKeys", text))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                true
            }

            // Primary row: paste
            view.findViewById<View>(R.id.clipboard_entry_paste).setOnClickListener {
                if (!isEditing()) paste_entry(pos)
            }

            // Secondary row: all guarded against edit mode
            pinButton.setOnClickListener {
                if (!isEditing()) pin_entry(pos)
            }
            unpinButton.setOnClickListener {
                // On PINNED tab, pin_entry() already unpins
                if (!isEditing()) pin_entry(pos)
            }
            todoButton.setOnClickListener {
                if (!isEditing()) todo_entry(pos)
            }
            doneButton.setOnClickListener {
                if (!isEditing()) {
                    // Toggle between active and completed
                    val newStatus = if (entry.todoStatus == TodoEntry.STATUS_COMPLETED)
                        TodoEntry.STATUS_ACTIVE else TodoEntry.STATUS_COMPLETED
                    service?.setTodoStatus(entry.content, newStatus)
                    loadDataAsync()
                }
            }
            statusButton.setOnClickListener {
                if (!isEditing()) cycleTodoStatus(entry)
            }
            tagsButton.setOnClickListener {
                if (!isEditing()) {
                    onTagPanelRequested?.invoke(entry, currentTab)
                }
            }

            return view
        }
    }
}
