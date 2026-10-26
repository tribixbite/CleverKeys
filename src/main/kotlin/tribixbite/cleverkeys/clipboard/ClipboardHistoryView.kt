package tribixbite.cleverkeys

import android.app.AlertDialog
import android.content.ClipData
// NOTE: android.content.ClipboardManager is imported with alias to avoid shadowing by
// tribixbite.cleverkeys.ClipboardManager (same-package takes priority in Kotlin resolution).
import android.content.ClipboardManager as SystemClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
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
import tribixbite.cleverkeys.clipboard.ClipboardBulkPlans
import tribixbite.cleverkeys.clipboard.ClipboardCleanResult
import tribixbite.cleverkeys.clipboard.ClipboardCopyResult
import tribixbite.cleverkeys.clipboard.ClipboardPendingDeletion
import tribixbite.cleverkeys.clipboard.ClipboardEditPages
import tribixbite.cleverkeys.clipboard.ClipboardPreviewPolicy
import tribixbite.cleverkeys.clipboard.ClipboardSwipePolicy
import tribixbite.cleverkeys.clipboard.ClipboardSwipeTracker
import tribixbite.cleverkeys.clipboard.ClipboardProvenance
import tribixbite.cleverkeys.clipboard.ClipboardSelection
import tribixbite.cleverkeys.clipboard.ClipboardSelectionHolder
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

    // Callback for visual feedback when an item is added to another tab (pulse animation).
    // Second param: pulse count (1 = success, 3 = duplicate/already exists)
    var onItemAddedToTab: ((ClipboardTab, Int) -> Unit)? = null

    // #130: runtime theme colors (custom_/decorative_ themes), pushed by ClipboardManager
    // when it paints the pane chrome. Entry rows are adapter-inflated under the base XML
    // style, whose ?attr/color* values are wrong for runtime themes — getView overrides
    // row colors from here. Null for XML-style themes (the rows' own ?attr resolution is
    // already correct) and nulls again when the pane is re-inflated after a theme change.
    var runtimeColors: ClipboardPaneThemePolicy.RuntimeColors? = null

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

    // D-6: memo for the play badge's animation check, keyed by entry timestamp. The real
    // detector (ClipboardMediaManager.isAnimated) reads ~30 header bytes per file — cheap,
    // but getView runs per scroll frame, so resolve once per row. Cleared with the
    // thumbnail cache on tab switch.
    private val animatedCache = mutableMapOf<Long, Boolean>()

    // #156 / ARC-011: memo for PackageManager label lookups behind the provenance line.
    // getView() runs per scroll frame, and getApplicationInfo() is a binder round-trip; the
    // distinct source-package set is tiny (apps the user privately copies from), so an
    // unbounded map is bounded in practice. A null VALUE is a cached "not installed" — hence
    // containsKey rather than getOrPut, which would re-query on every frame for that case.
    private val appLabelCache = mutableMapOf<String, String?>()

    /**
     * Resolve a package name to its user-visible app label, or `null` when the app is not
     * installed (the caller then shows the raw package name so attribution survives uninstall).
     *
     * API 30+ package-visibility filtering makes `NameNotFoundException` also mean "installed but
     * not visible to us". We deliberately do NOT widen the manifest `<queries>` (let alone request
     * QUERY_ALL_PACKAGES) to close that: a keyboard enumerating installed apps to prettify a label
     * is a worse trade than the raw-package fallback, and the two packages that actually reach
     * this path are ones the platform already makes visible — the app that launched our
     * PROCESS_TEXT activity (entry point B) and the app whose editor the IME is bound to
     * (entry point A). Either way the entry stays attributable, which is what design §6.3 needs.
     */
    private fun resolveAppLabel(pkg: String): String? {
        if (appLabelCache.containsKey(pkg)) return appLabelCache[pkg]
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        appLabelCache[pkg] = label
        return label
    }

    /**
     * The provenance text for [entry], or `null` when the row has no provenance to show
     * (pre-V5 rows and ordinary OS-clipboard captures carry a null `source_package`).
     */
    private fun provenanceText(entry: ClipboardEntry): String? {
        val label = ClipboardProvenance.label(
            sourcePackage = entry.sourcePackage,
            directLaunchLabel = context.getString(R.string.clipboard_provenance_direct_launch),
            resolveAppLabel = ::resolveAppLabel,
        ) ?: return null
        return context.getString(R.string.clipboard_provenance_via, label)
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

    // #156 Private-only filter — when true, only entries with isPrivate (🔒) are shown.
    // Available on all three tabs (COPY semantics propagate is_private to pinned/todo entries).
    // Like the date filter, it persists across tab switches (private entries can live on any tab);
    // it is only reset via clearAllFilters().
    private var privateOnlyFilter = false

    private var sizeMinimum = 0L
    private var sizeMaximum: Long? = null
    private var dataReady = false
    var onResultsChanged: (() -> Unit)? = null

    /** Current inclusive payload-size bounds; kept across tabs, reset by Clear filters. */
    fun getSizeFilter(): Pair<Long, Long?> = sizeMinimum to sizeMaximum

    fun setSizeFilter(minimum: Long, maximum: Long?) {
        require(minimum >= 0 && (maximum == null || maximum >= minimum))
        sizeMinimum = minimum
        sizeMaximum = maximum
        applyFilter()
    }

    fun isResultsReady(): Boolean = dataReady && !bulkRunning
    fun resultSummary(): Pair<Int, Long> = filteredHistory.size to filteredHistory.sumOf { it.sizeBytes }

    // ─── Persistent selection (2026-10-07) ───
    // The selection lives in a ClipboardSelectionHolder owned by the service-scoped
    // ClipboardManager, which attaches it to every newly inflated view: it survives search,
    // filter and page changes, view/pane recreation (theme change, rotation, keyboard hide,
    // field and app switches) and ends ONLY on Deselect all/Exit selection or a completed bulk
    // action. Scoped to one tab (row ids are per table). Holds row ids and versions only.
    // Nullable + lazily created so Objenesis-built test views and a standalone view still work.
    private var holder: ClipboardSelectionHolder? = null
    private val selectionHolder: ClipboardSelectionHolder
        get() = holder ?: ClipboardSelectionHolder().also { holder = it }
    private val selection: ClipboardSelection? get() = holder?.selection
    private val holderObserver: () -> Unit = {
        selectionChanged()
        loadDataAsync()
    }

    /** A confirmed bulk action (delete, copy, merge, clean) is executing on the holder. */
    private val bulkRunning: Boolean get() = holder?.running == true

    /**
     * Share the service-scoped selection [newHolder]. Called by ClipboardManager right after
     * inflating the pane, so a recreated view comes back in selection mode with the same rows.
     */
    fun attachSelectionHolder(newHolder: ClipboardSelectionHolder) {
        if (holder === newHolder) return
        holder?.let { if (it.observer === holderObserver) it.observer = null }
        holder = newHolder
        if (viewScope != null) newHolder.observer = holderObserver  // attached to a window
        selectionChanged()
    }

    /** Tab the active selection belongs to, or null when not selecting. */
    fun selectionTab(): ClipboardTab? = selection?.tab

    /** Whether selection mode is active. */
    fun isSelecting(): Boolean = selection != null

    /** Number of selected rows in the current tab (0 outside selection mode). */
    fun selectedCount(): Int = selection?.size ?: 0

    /** Whether [entry] is selected in its current version. */
    fun isEntrySelected(entry: ClipboardEntry): Boolean = selection?.isSelected(entry) == true

    /**
     * Enter selection mode for the current tab. Refused while an entry is being edited or a
     * bulk action runs. Expanded rows collapse: selection rows show a checkbox, not actions.
     */
    fun startSelection(): Boolean {
        if (isEditing() || bulkRunning) return false
        commitPendingDeletion(ClipboardPendingDeletion.CommitReason.SELECTION)
        if (selection?.tab != currentTab) {
            selectionHolder.start(currentTab)
            expandedStates.clear()
            selectionChanged()
        }
        return true
    }

    /** Leave selection mode, forgetting every selected row. No-op when not selecting. */
    fun endSelection() {
        if (selection == null) return
        selectionHolder.end()
        selectionChanged()
    }

    /** Toggle one row by identity (never by list position, which reloads can shift). */
    fun toggleSelection(entry: ClipboardEntry) {
        val current = selection ?: return
        if (bulkRunning) return
        current.toggle(entry)
        selectionChanged()
    }

    /**
     * Add every row matching the current search and filters, on ALL pages. Returns the number
     * newly selected; 0 while results are still loading or a bulk action runs, because the
     * matching list would not yet describe what the user sees.
     */
    fun selectAllMatching(): Int {
        val current = selection ?: return 0
        if (!dataReady || bulkRunning) return 0
        val added = current.selectAll(filteredHistory)
        selectionChanged()
        return added
    }

    /** Remove every row matching the current search and filters (all pages) from the selection. */
    fun deselectAllMatching(): Int {
        val current = selection ?: return 0
        if (bulkRunning) return 0
        val removed = current.deselectAll(filteredHistory)
        selectionChanged()
        return removed
    }

    /** Deselect everything, including rows the current search or filters hide. */
    fun clearSelection() {
        val current = selection ?: return
        if (bulkRunning) return
        current.clear()
        selectionChanged()
    }

    /** How much of the current matching list (all pages) is selected. */
    fun matchingCoverage(): ClipboardSelection.Coverage =
        selection?.coverage(filteredHistory) ?: ClipboardSelection.Coverage.NONE

    /**
     * The confirmation scope for every bulk action: the selected rows of the tab's complete
     * loaded data that are unchanged since selection, regardless of the current search, filters
     * or page. Null while loading, editing, running or when nothing selected still exists.
     */
    fun selectionSnapshot(): ClipboardDeleteSnapshot? {
        val current = selection ?: return null
        if (!dataReady || bulkRunning || isEditing() || current.tab != currentTab) return null
        return current.resolve(history).takeIf { it.entries.isNotEmpty() }
    }

    private fun selectionChanged() {
        clipboardAdapter.notifyDataSetChanged()
        onResultsChanged?.invoke()
    }

    /**
     * Run a confirmed bulk action on the holder: the work starts on IO at once and is not tied
     * to this view, so a keyboard hide or pane teardown right after confirming can neither
     * cancel it nor lose its result. Success ends selection mode; a failure keeps it.
     */
    private fun <T> runBulk(work: () -> Result<T>, completed: (Result<T>) -> Unit) {
        if (bulkRunning || isEditing()) return
        commitPendingDeletion(ClipboardPendingDeletion.CommitReason.BULK_ACTION)
        if (selectionHolder.runConfirmed(work, completed)) onResultsChanged?.invoke()
    }

    private fun unavailable(): Result<Nothing> = Result.failure(IllegalStateException("Clipboard unavailable"))

    /** Delete the frozen [snapshot] by row identity in one transaction. */
    fun deleteSnapshot(snapshot: ClipboardDeleteSnapshot, completed: (Result<Int>) -> Unit) {
        val svc = service
        runBulk({ svc?.deleteSnapshot(snapshot) ?: unavailable() }, completed)
    }

    /** Add to Pinned / Add to Todos: copy the frozen [snapshot] rows into [target]. */
    fun copySelectionTo(target: ClipboardTab, snapshot: ClipboardDeleteSnapshot, completed: (Result<ClipboardCopyResult>) -> Unit) {
        val svc = service
        runBulk({ svc?.copyEntriesTo(target, snapshot.entries) ?: unavailable() }, completed)
    }

    /** Merge: store the confirmed [plan] as one new History clipping. */
    fun mergeSelection(plan: ClipboardBulkPlans.MergePlan, completed: (Result<Unit>) -> Unit) {
        val svc = service
        runBulk({ svc?.addMergedClip(plan) ?: unavailable() }, completed)
    }

    /** Clean: apply the confirmed [plan]'s edits in place to [tab]'s rows. */
    fun cleanSelection(tab: ClipboardTab, plan: ClipboardBulkPlans.CleanPlan, completed: (Result<ClipboardCleanResult>) -> Unit) {
        val svc = service
        runBulk({ svc?.applyClean(tab, plan) ?: unavailable() }, completed)
    }

    // ─── Swipe to delete (GH #175 part 2, 2026-10-21) ───
    // A swiped row is hidden at once and deleted when the undo window ends or something changes
    // what the user is looking at. The pending row lives in the service-scoped ClipboardManager
    // (ClipboardPendingDeletion), which attaches it here like the selection holder; this view
    // only hides the row, commits it on its own triggers (tab switch, search/filter change,
    // selection, edit, bulk action) and renders the gesture. Nullable: Objenesis-built test
    // views and a standalone view have none, and then rows are deleted at once (no undo).
    private var pendingDeletion: ClipboardPendingDeletion? = null

    /** Share the manager's pending deletion. Called right after the pane is inflated. */
    fun attachPendingDeletion(newPending: ClipboardPendingDeletion) {
        pendingDeletion = newPending
    }

    /** Whether [entry] of the current tab is hidden by a pending deletion. */
    private fun isPendingDeletion(entry: ClipboardEntry): Boolean =
        pendingDeletion?.hides(currentTab, entry.content) == true

    /** Delete the pending row now because of [reason]; a no-op when nothing is pending. */
    private fun commitPendingDeletion(reason: ClipboardPendingDeletion.CommitReason) {
        pendingDeletion?.commitNow(reason)
    }

    /**
     * Swipe-to-delete and the accessibility "Delete" action: hide [entry] from the current tab
     * and offer Undo, deleting it for real later (ClipboardPendingDeletion). Refused in
     * selection mode, during an edit and while a bulk action runs. Without an attached pending
     * deletion the row is deleted at once through the same path. Returns whether it was taken.
     */
    fun deferDelete(entry: ClipboardEntry): Boolean {
        if (!ClipboardSwipePolicy.deleteActionAllowed(
                selecting = selection != null,
                editing = isEditing(),
                // The tag panel replaces this list (content scroll GONE), so no row is reachable.
                tagMode = false,
                bulkRunning = bulkRunning,
            )
        ) return false
        val pending = pendingDeletion
        if (pending == null) {
            service?.removeFromTab(currentTab, entry.content)
            expandedStates.remove(entry.timestamp)
            loadDataAsync()
        } else {
            expandedStates.remove(entry.timestamp)
            pending.begin(currentTab, entry.content)
        }
        return true
    }

    /**
     * The manager forwards every pending-deletion change here. Hidden or restored rows only
     * need a refilter (page and expand state kept). A committed row is also dropped from the
     * loaded rows at once, so it does not flash back before the reload the delete triggers.
     */
    fun onPendingDeletionChanged(change: ClipboardPendingDeletion.Change, pending: ClipboardPendingDeletion.Pending) {
        if (change == ClipboardPendingDeletion.Change.COMMITTED && pending.tab == currentTab) {
            history = history.filterNot { it.content == pending.content }
        }
        applyFilter(resetView = false)
    }

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
    // #156: whether the entry currently being edited is private (is_private). Set in edit_entry(),
    // cleared in cancelEdit(). Gates cutFromEditText() so cutting a private entry's selection can't
    // silently leak its plaintext to the OS clipboard — it routes through the same
    // confirm-before-expose dialog copyEntryToSystemClipboard uses.
    private var editingIsPrivate: Boolean = false
    // D-7: string resource of the current save_edit failure, rendered inline in the edit row
    // (Toasts are invisible in IME context — ime-visual-feedback.md). Non-null only while an
    // edit session is showing an error; cleared on text change, cancel, and successful save.
    private var editingErrorRes: Int? = null
    // Cursor position preserved across view recycling — updated after every text op
    private var editingCursorPosition: Int? = null
    // Reference to the active EditText widget for key routing (insertEditText/backspaceEditText)
    private var editingEditText: EditText? = null
    // TextWatcher reference — tracked to prevent accumulation on view recycling.
    // Removed from old EditText before adding to new one in getView().
    private var editingTextWatcher: android.text.TextWatcher? = null
    // Undo/redo for the edit session (Ctrl+Z / Ctrl+Y / the undo & redo keys). Our own history
    // because every routed edit is a setText(), which wipes the platform's (InlineEditHistory).
    // Exists only while a session is active (created in edit_entry, dropped in cancelEdit), so a
    // private entry's text never outlives its edit and no memory is held between sessions.
    private var editingHistory: InlineEditHistory? = null
    // Identifies the edit session for swipe ownership (InlineSwipeSession): a word swiped in
    // one session is never rewritten in a later one that happens to hold the same text.
    // A page change starts a new one too (the field then holds other text).
    private var editingSession = 0L
    // Paged editing (todo 22, 2026-10-26): non-null while editing an entry too long to edit
    // whole. The field (and editingInProgressText) then hold only the current page; the
    // session keeps the working copy with the other pages' edits. Save splices the page in
    // and writes the full text once. Null for entries that fit one page (unchanged editor).
    private var editingPages: ClipboardEditPages.Session? = null
    // TalkBack labels for the pager buttons' long-press (first / last page). Created on first
    // paged bind; Objenesis-built test views inject their own (the stub constructor throws).
    private var pageFirstDelegate: View.AccessibilityDelegate? = null
    private var pageLastDelegate: View.AccessibilityDelegate? = null

    // Dynamic lookup for the editing EditText — resilient to stale references from
    // ListView scrap view measurement passes. When Enter inserts a newline, the EditText
    // height changes → ListView.measureHeightOfChildren() calls getView() with a SCRAP
    // view → if that scrap steals editingEditText, all subsequent key routing goes to an
    // invisible detached view. This property detects stale references via windowToken
    // (null = detached) and falls back to scanning live children for the visible EditText.
    private val activeEditingEditText: EditText?
        get() {
            // Not in edit mode — no searching needed, avoid unnecessary child iteration
            if (!isEditing()) return null

            // Fast path: current reference is still attached, visible, and valid.
            // CRITICAL: Must check visibility == VISIBLE, not just windowToken.
            // When loadDataAsync() completes during edit mode, notifyDataSetChanged()
            // redistributes scrap views. The cached editField may get recycled to a
            // different list position (visibility=GONE) while a NEW editField becomes
            // the visible edit widget. Without the visibility check, setText() goes to
            // the invisible recycled view — user sees nothing.
            // The window token alone cannot tell: ListView takes recycled rows out with
            // detachViewFromParent, which keeps it, so a detached copy of the editor still
            // looked live. Typed text then went into that copy (and was saved) while the editor
            // on screen never changed (To-dos tab, Saga 2026-10-25). Require the row to be in
            // this list now.
            editingEditText?.let { et ->
                if (et.visibility == VISIBLE && et.windowToken != null && isInThisList(et)) return et
            }
            // Slow path: reference is stale, recycled, or detached. Search live children.
            for (i in 0 until childCount) {
                val child = getChildAt(i) ?: continue
                val et = child.findViewById<EditText>(R.id.clipboard_entry_edit_field)
                if (et != null && et.visibility == VISIBLE && et.windowToken != null) {
                    editingEditText = et
                    return et
                }
            }
            // No attached editing view found — return cached reference (callers null-check)
            return editingEditText
        }

    /** True while [view] sits in a row that is a child of this list (not a detached recycled row). */
    private fun isInThisList(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

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

        /** Characters of a clipping named in its selection checkbox's accessible label. */
        private const val SELECTION_LABEL_CHARS = 80

        /** Alpha of the label-colour tint behind a selected row (0x33 ≈ 20%). */
        private const val SELECTED_ROW_ALPHA = 0x33

        /** VelocityTracker units: pixels per second. */
        private const val VELOCITY_UNITS_PER_SECOND = 1000

        /** Duration of the spring-back of a swipe that did not commit. */
        private const val SPRING_BACK_MS = 150L

        /** #130: every clipboardEntryButton in clipboard_history_entry.xml (tinted via colorLabel). */
        private val ENTRY_BUTTON_IDS = intArrayOf(
            R.id.clipboard_entry_expand, R.id.clipboard_entry_edit, R.id.clipboard_entry_paste,
            R.id.clipboard_entry_addpin, R.id.clipboard_entry_unpin, R.id.clipboard_entry_addtodo,
            R.id.clipboard_entry_done, R.id.clipboard_entry_status, R.id.clipboard_entry_tags,
            R.id.clipboard_entry_save, R.id.clipboard_entry_cancel, R.id.clipboard_entry_delete,
            R.id.clipboard_entry_edit_page_prev, R.id.clipboard_entry_edit_page_next,
        )

        /** clipboard_history_entry.xml's android:maxLines for the edit field (single-page edits). */
        private const val EDIT_FIELD_MAX_LINES = 50

        /** Pager row: 32dp buttons + 4dp top margin (clipboard_history_entry.xml). */
        private const val PAGER_ROW_DP = 36

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
        // This view is now the one a finishing bulk action refreshes.
        holder?.observer = holderObserver
        loadDataAsync()
    }

    override fun onDetachedFromWindow() {
        // A gesture cannot survive the window; the pending row itself lives in the manager.
        abandonRowSwipe(springBack = false)
        // Unregister to break singleton→view reference and stop receiving callbacks
        service?.setOnClipboardHistoryChange(null)
        holder?.let { if (it.observer === holderObserver) it.observer = null }
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
        // A row swiped on the tab being left is deleted now: its undo offer belongs to that tab.
        commitPendingDeletion(ClipboardPendingDeletion.CommitReason.TAB_SWITCH)
        // Cancel any in-progress edit before switching tabs (safety — tab clicks
        // are guarded in ClipboardManager, but direct callers like resetSearchOnShow need this)
        cancelEdit()
        // A selection names one table's rows: switching to ANOTHER tab ends it; re-targeting the
        // selection's own tab (pane reopen) keeps it.
        if (selection != null && selection?.tab != tab) endSelection()
        currentTab = tab
        expandedStates.clear()
        thumbnailCache.evictAll()
        animatedCache.clear()  // D-6: same lifecycle as the thumbnail cache
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

    private fun applyFilter(resetView: Boolean = true) {
        // resetView=true means the search or filter criteria changed: the results the undo
        // offer described are gone, so a swiped row is deleted now (reloads pass false).
        if (resetView) commitPendingDeletion(ClipboardPendingDeletion.CommitReason.FILTER_CHANGE)
        // A row awaiting its deferred delete is hidden from every result and count.
        val source = if (pendingDeletion?.pending?.tab == currentTab) history.filterNot(::isPendingDeletion) else history

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
        val filtered = source.filter { entry ->
            if (!ClipboardSizePolicy.matches(entry.sizeBytes, sizeMinimum, sizeMaximum)) return@filter false
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

            // Private-only filter (#156 — all tabs). Additional AND predicate: keep only 🔒 entries.
            if (privateOnlyFilter && !entry.isPrivate) {
                return@filter false
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
            tagFilterSelected.isEmpty() && !hasStatusFilter && !privateOnlyFilter && sizeMinimum == 0L && sizeMaximum == null) {
            source
        } else {
            filtered
        }

        // Reset to first page when filter changes (but not on data reloads)
        if (resetView) {
            currentPage = 0
        }
        applyPagination(clearExpandedStates = resetView)
    }

    private fun applyPagination(clearExpandedStates: Boolean = false) {
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

        // Only clear expanded states on tab/page/filter change — not on action reloads
        // (pin, todo, delete). This prevents entries from collapsing after an action.
        if (clearExpandedStates) {
            expandedStates.clear()
        }
        // Prune expanded states for entries no longer visible on this page
        val visibleTimestamps = paginatedHistory.map { it.timestamp }.toSet()
        expandedStates.keys.retainAll(visibleTimestamps)
        thumbnailCache.evictAll()
        animatedCache.keys.retainAll(visibleTimestamps)  // D-6: drop rows that left the page

        // Notify listener about pagination state
        onPaginationChangeListener?.invoke(
            totalItems > ITEMS_PER_PAGE,
            currentPage + 1,  // 1-indexed for display
            totalPages
        )

        clipboardAdapter.notifyDataSetChanged()
        onResultsChanged?.invoke()
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
            applyPagination(clearExpandedStates = true)
        }
    }

    /** Go to next page */
    fun nextPage() {
        if (currentPage < getTotalPages() - 1) {
            currentPage++
            applyPagination(clearExpandedStates = true)
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
        // D-10: an async reload can shrink the paginated list between a row's render and
        // its click dispatch — a stale position must be a no-op, not an IME crash.
        val entry = paginatedHistory.getOrNull(pos) ?: return

        when (currentTab) {
            ClipboardTab.HISTORY -> {
                // Pin from history (COPY — history entry stays)
                val svc = service ?: return
                val added = svc.pinEntry(entry.content, entry.timestamp,
                    entry.mimeType, entry.thumbnailBlob, entry.mediaPath)
                onItemAddedToTab?.invoke(ClipboardTab.PINNED, if (added) 1 else 3)
            }
            ClipboardTab.PINNED -> {
                // Unpin from pinned tab
                service?.unpinEntry(entry.content)
            }
            ClipboardTab.TODOS -> {
                // Pin todo item (can be both pinned and todo)
                val svc = service ?: return
                val added = svc.pinEntry(entry.content, entry.timestamp,
                    entry.mimeType, entry.thumbnailBlob, entry.mediaPath)
                onItemAddedToTab?.invoke(ClipboardTab.PINNED, if (added) 1 else 3)
            }
        }
        loadDataAsync()
    }

    /**
     * Add or remove entry from todos at index [pos] (position in current page).
     * Carries media fields through COPY semantics.
     */
    fun todo_entry(pos: Int) {
        // D-10: bounds-guard stale click positions (see pin_entry).
        val entry = paginatedHistory.getOrNull(pos) ?: return

        when (currentTab) {
            ClipboardTab.HISTORY, ClipboardTab.PINNED -> {
                // Add to todos (COPY — original entry stays)
                val svc = service ?: return
                val added = svc.addToTodo(entry.content, entry.timestamp,
                    entry.mimeType, entry.thumbnailBlob, entry.mediaPath)
                // Single pulse = added, triple pulse = already exists
                onItemAddedToTab?.invoke(ClipboardTab.TODOS, if (added) 1 else 3)
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
        // D-10: bounds-guard stale click positions (see pin_entry).
        val entry = paginatedHistory.getOrNull(pos) ?: return
        // Always exit edit mode when deleting — the entry being edited is removed,
        // and list positions shift. Without this, isEditing() stays true and blocks all UI.
        if (isEditing()) {
            cancelEdit()
        }
        // Same per-tab service path as the swipe's deferred delete (media files cleaned up there).
        service?.removeFromTab(currentTab, entry.content)
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
        // Media entries cannot be edited inline. Entries too long to edit whole are edited a
        // page at a time (ClipboardEditPages, todo 22): the field never holds the whole text.
        if (entry.isMedia) return
        // Edit mode locks the pane; a swiped row is deleted before it starts.
        commitPendingDeletion(ClipboardPendingDeletion.CommitReason.EDIT)

        // Bug #1 fix: clear search mode before entering edit (mutual exclusion)
        onEditModeEntered?.invoke()

        editingOriginalContent = entry.content
        val pages = if (ClipboardEditPages.needsPaging(entry.content)) ClipboardEditPages.Session(entry.content) else null
        editingPages = pages
        if (pages != null) {
            editingInProgressText = pages.pageText()
            editingCursorPosition = 0  // a paged entry opens at the top of page 1
        } else {
            editingInProgressText = entry.content
            editingCursorPosition = entry.content.length
        }
        editingHistory = InlineEditHistory()
        editingSession++
        // #156: remember the entry's privacy so cutFromEditText() can gate the OS-clipboard write.
        editingIsPrivate = entry.isPrivate
        // Re-render to show EditText + save/cancel buttons for this entry
        clipboardAdapter.notifyDataSetChanged()
    }

    /** Commit the edited content to the database and exit edit mode */
    fun save_edit() {
        val oldContent = editingOriginalContent ?: return
        // editingInProgressText is guaranteed non-null during active edit (set in edit_entry,
        // updated by TextWatcher + direct sync in text manipulation methods).
        val fieldText = editingInProgressText ?: return
        // Paged: splice the current page into the working copy; ONE update of the full text.
        // The update is keyed on oldContent (the entry as opened), so a row changed meanwhile
        // is refused (EntryChanged) instead of overwritten.
        val newContent = editingPages?.fullText(fieldText) ?: fieldText

        when (service?.editEntryContent(oldContent, newContent, currentTab)) {
            is EditEntryResult.Success -> {
                // Timestamp key doesn't change when content is edited — no migration needed
                cancelEdit()
            }
            // D-7: every failure keeps the edit session ALIVE. The old path Toasted (invisible
            // inside the IME window — ime-visual-feedback.md) and then unconditionally
            // cancelEdit()ed, silently discarding the user's in-progress edit. Instead the
            // error renders inline in the edit row and the user can fix or deliberately cancel.
            is EditEntryResult.DuplicateConflict -> showEditError(R.string.clipboard_edit_duplicate)
            is EditEntryResult.InvalidContent -> showEditError(R.string.clipboard_edit_invalid)
            is EditEntryResult.EntryChanged -> showEditError(R.string.clipboard_edit_changed)
            is EditEntryResult.Error, null -> showEditError(R.string.clipboard_edit_error)
        }
    }

    /** D-7: surface a save_edit failure inline (clipboard_entry_edit_error) without leaving edit mode. */
    private fun showEditError(messageRes: Int) {
        editingErrorRes = messageRes
        clipboardAdapter.notifyDataSetChanged()
    }

    /** Discard edits and exit edit mode */
    fun cancelEdit() {
        if (editingOriginalContent == null) return
        editingOriginalContent = null
        editingInProgressText = null
        editingPages = null
        editingHistory = null
        editingCursorPosition = null
        editingIsPrivate = false
        editingErrorRes = null
        // Remove TextWatcher via tag-based cleanup (matches getView tag management).
        // Also try field-based removal as belt-and-suspenders.
        editingEditText?.let { et ->
            @Suppress("UNCHECKED_CAST")
            (et.getTag(R.id.clipboard_entry_edit_field) as? android.text.TextWatcher)?.let {
                et.removeTextChangedListener(it)
                et.setTag(R.id.clipboard_entry_edit_field, null)
            }
            editingTextWatcher?.let { et.removeTextChangedListener(it) }
        }
        editingTextWatcher = null
        editingEditText = null
        // Notify ClipboardManager to re-enable search/tabs/pagination UI
        onEditModeExited?.invoke()
        // Reload data to pick up any changes that were suppressed during edit
        loadDataAsync()
    }

    // ── Paged editing (todo 22, 2026-10-26) ──────────────────────────────
    // Every move first splices the field's text into the session's working copy, so unsaved
    // edits are kept across pages (no prompt); Save writes them all, Cancel drops them all.

    /** Show the next page of a paged edit (no-op on the last page or when not paged). */
    fun editPageNext() = moveEditPage(caretAtEnd = false) { s, t -> s.next(t) }

    /** Show the previous page; its caret goes to the end, next to where the user came from. */
    fun editPagePrevious() = moveEditPage(caretAtEnd = true) { s, t -> s.previous(t) }

    fun editPageFirst() = moveEditPage(caretAtEnd = false) { s, t -> s.first(t) }

    fun editPageLast() = moveEditPage(caretAtEnd = false) { s, t -> s.last(t) }

    /**
     * Apply [move] to the paged session with the field's current text, then bind the new
     * page. The undo history and swipe ownership belong to one page's text, so both restart.
     */
    private inline fun moveEditPage(caretAtEnd: Boolean, move: (ClipboardEditPages.Session, String) -> Boolean) {
        val pages = editingPages ?: return
        val fieldText = editingInProgressText ?: return
        if (!move(pages, fieldText)) return
        val page = pages.pageText()
        editingInProgressText = page
        editingCursorPosition = if (caretAtEnd) page.length else 0
        editingHistory = InlineEditHistory()
        editingSession++
        editingErrorRes = null
        clipboardAdapter.notifyDataSetChanged()
    }

    /** "Page N of M" and prev/next state for the row being edited; GONE unless paged. */
    private fun bindEditPager(row: View) {
        val pager = row.findViewById<View?>(R.id.clipboard_entry_edit_pager) ?: return
        val pages = editingPages
        if (pages == null) {
            pager.visibility = GONE
            return
        }
        pager.visibility = VISIBLE
        (row.findViewById<View?>(R.id.clipboard_entry_edit_page_label) as? TextView)?.text =
            context.getString(R.string.clipboard_edit_page, pages.pageIndex + 1, pages.pageCount)
        val prev = row.findViewById<View?>(R.id.clipboard_entry_edit_page_prev)
        val next = row.findViewById<View?>(R.id.clipboard_entry_edit_page_next)
        val hasPrev = pages.pageIndex > 0
        val hasNext = pages.pageIndex < pages.pageCount - 1
        prev?.apply {
            isEnabled = hasPrev
            alpha = if (hasPrev) 1.0f else 0.3f
            setOnClickListener { editPagePrevious() }
            setOnLongClickListener { editPageFirst(); true }
            accessibilityDelegate = pageFirstDelegate
                ?: PageJumpDelegate(R.string.clipboard_edit_page_first).also { pageFirstDelegate = it }
        }
        next?.apply {
            isEnabled = hasNext
            alpha = if (hasNext) 1.0f else 0.3f
            setOnClickListener { editPageNext() }
            setOnLongClickListener { editPageLast(); true }
            accessibilityDelegate = pageLastDelegate
                ?: PageJumpDelegate(R.string.clipboard_edit_page_last).also { pageLastDelegate = it }
        }
    }

    /**
     * A paged field is capped to the pane's visible height minus the pager row, so the pager
     * and Save/Cancel stay on screen and the field scrolls its own text. Otherwise the pane's
     * ScrollView, on focusing a field taller than itself, aligns the field's top with its own
     * and scrolls the pager out of sight (Saga 2026-10-26). Single-page edits keep the XML
     * line cap ([EDIT_FIELD_MAX_LINES]); recycled rows are reset to it.
     */
    private fun fitEditFieldHeight(editField: EditText) {
        if (editingPages == null) {
            editField.maxLines = EDIT_FIELD_MAX_LINES
            return
        }
        val viewport = contentViewportHeight()
        if (viewport <= 0) return  // not laid out yet; the next bind fits it
        val pagerRow = (PAGER_ROW_DP * resources.displayMetrics.density).toInt()
        editField.maxHeight = (viewport - pagerRow).coerceAtLeast(editField.lineHeight * 2)
    }

    /** Inner height of the pane's ScrollView holding this list, or 0 when there is none. */
    private fun contentViewportHeight(): Int {
        var p = parent
        while (p != null) {
            if (p is android.widget.ScrollView) return p.height - p.paddingTop - p.paddingBottom
            p = p.parent
        }
        return 0
    }

    /** Names a pager button's long-press for TalkBack ("First page" / "Last page"). */
    private inner class PageJumpDelegate(private val labelRes: Int) : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, context.getString(labelRes)))
        }
    }

    // ── IME-owned EditText manipulation ──────────────────────────────────
    // Android IMEs cannot use editable.replace()/editable.delete()/dispatchKeyEvent()
    // on their own EditText views — those APIs are unreliable when the IME owns the view.
    // All methods use setText() + setSelection() pattern (same as tag/search modes).

    /** Insert text at cursor/selection in the active edit field (called by key routing).
     *  Trusts et.selectionStart/End first (reliable with textMultiLine inputType),
     *  falls back to editingCursorPosition only for view-recycling recovery. */
    fun insertEditText(text: String) = replaceEditSelection(text, InlineEditHistory.EditKind.TYPING)

    /** Replace the selection (or insert at the caret) with [text], recorded as a [kind] edit. */
    private fun replaceEditSelection(text: String, kind: InlineEditHistory.EditKind) {
        activeEditingEditText?.let { et ->
            val before = editSnapshotOf(et)
            val oldText = et.text.toString()
            // Trust EditText selection first (reliable regardless of inputType), tracked cursor as fallback
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
            // Direct sync — resilient to missing TextWatcher (e.g., recycled view without guard entry)
            editingInProgressText = newText
            editingHistory?.record(before, InlineEditHistory.Snapshot(newText, newCursorPos, newCursorPos), kind)
        }
    }

    /**
     * The edit field's text and selection for swipe typing (InlineSwipeSession), or null when
     * no entry is being edited or no field is attached.
     */
    fun editFieldState(): InlineFieldState? {
        val et = activeEditingEditText ?: return null
        val snapshot = editSnapshotOf(et)
        return InlineFieldState(
            snapshot.text, snapshot.selectionStart, snapshot.selectionEnd, editingSession
        )
    }

    /**
     * Replace [start, end) of the edit field with [text] and put the caret after it — a swiped
     * word, its bar replacement or its backspace undo. Recorded as its own step in the editor's
     * undo history (a swiped word is one deliberate edit, unlike merged per-letter typing).
     * False when no field is attached or the range is outside the text.
     */
    fun replaceEditRange(start: Int, end: Int, text: String): Boolean {
        val et = activeEditingEditText ?: return false
        val before = editSnapshotOf(et)
        if (start < 0 || start > end || end > before.text.length) return false
        val newText = before.text.substring(0, start) + text + before.text.substring(end)
        val caret = start + text.length
        et.setText(newText)
        et.setSelection(caret)
        editingCursorPosition = caret
        // Direct sync — resilient to missing TextWatcher (see replaceEditSelection)
        editingInProgressText = newText
        editingHistory?.record(before, InlineEditHistory.Snapshot(newText, caret, caret), InlineEditHistory.EditKind.OTHER)
        return true
    }

    /** Handle backspace in the active edit field (called by key routing).
     *  If selection exists, deletes selected range. Otherwise deletes char before cursor. */
    fun backspaceEditText() {
        activeEditingEditText?.let { et ->
            val oldText = et.text.toString()
            if (oldText.isEmpty()) return
            val before = editSnapshotOf(et)

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

            val caret = newCursorPos.coerceIn(0, newText.length)
            et.setText(newText)
            et.setSelection(caret)
            editingCursorPosition = caret
            editingInProgressText = newText
            // Deleting a selection is its own step; consecutive single-char backspaces merge.
            val kind = if (hasSelection) InlineEditHistory.EditKind.OTHER else InlineEditHistory.EditKind.DELETION
            editingHistory?.record(before, InlineEditHistory.Snapshot(newText, caret, caret), kind)
        }
    }

    /** Paste system clipboard content into the active edit field */
    fun pasteToEditText() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? SystemClipboardManager
        val pasteText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        replaceEditSelection(pasteText, InlineEditHistory.EditKind.OTHER)
    }

    /** Cut selected text from the edit field to system clipboard.
     *  Requires active selection (e.g., from selectAll) — no-op if no selection.
     *
     *  #156 privacy gate: when editing a PRIVATE (is_private) entry, the cut selection must NOT be
     *  written to the OS clipboard silently — that would leak plaintext to the foreground app and
     *  other clipboard managers, defeating private copy. Instead the OS-clipboard write is routed
     *  through the SAME confirm-before-expose dialog copyEntryToSystemClipboard() uses. The text
     *  removal from the edit field always happens (cut semantics are preserved regardless of the
     *  clipboard decision); only the OS write is deferred behind the user's confirmation. Non-private
     *  entries keep the immediate, unchanged copy-to-OS-clipboard behavior. */
    fun cutFromEditText() {
        activeEditingEditText?.let { et ->
            val before = editSnapshotOf(et)
            val lo = minOf(before.selectionStart, before.selectionEnd)
            val hi = maxOf(before.selectionStart, before.selectionEnd)
            if (lo == hi) return

            exposeEditSelection(before.text.substring(lo, hi))

            // Text removal is unconditional — cut still removes the selection from the field
            // whether or not the private-entry OS-clipboard write is confirmed.
            val newText = before.text.removeRange(lo, hi)
            et.setText(newText)
            et.setSelection(lo)
            editingCursorPosition = lo
            editingInProgressText = newText
            editingHistory?.record(before, InlineEditHistory.Snapshot(newText, lo, lo), InlineEditHistory.EditKind.OTHER)
        }
    }

    /**
     * Copy the edit field's selection to the system clipboard (Ctrl+C / the copy key). No-op
     * without a selection, like any text field. It is an ordinary copy: the clipboard listener
     * records it as a new history entry exactly as it does a copy made in any app (and as the
     * editor's cut already did). Same #156 privacy gate as [cutFromEditText].
     */
    fun copyFromEditText() {
        activeEditingEditText?.let { et ->
            val snapshot = editSnapshotOf(et)
            val lo = minOf(snapshot.selectionStart, snapshot.selectionEnd)
            val hi = maxOf(snapshot.selectionStart, snapshot.selectionEnd)
            if (lo != hi) exposeEditSelection(snapshot.text.substring(lo, hi))
        }
    }

    /** Undo the last edit in the active edit field (Ctrl+Z / the undo key). */
    fun undoEditText() = restoreEditStep { editingHistory?.undo(it) }

    /** Redo the last undone edit in the active edit field (Ctrl+Shift+Z / Ctrl+Y / the redo key). */
    fun redoEditText() = restoreEditStep { editingHistory?.redo(it) }

    /** Apply the snapshot [step] returns for the field's current state; no-op when it has none. */
    private inline fun restoreEditStep(step: (InlineEditHistory.Snapshot) -> InlineEditHistory.Snapshot?) {
        activeEditingEditText?.let { et ->
            val target = step(editSnapshotOf(et)) ?: return
            val start = target.selectionStart.coerceIn(0, target.text.length)
            val end = target.selectionEnd.coerceIn(0, target.text.length)
            et.setText(target.text)
            et.setSelection(start, end)
            editingCursorPosition = end
            editingInProgressText = target.text
        }
    }

    /**
     * The field's text and selection, clamped to the text. A detached/recycled EditText can
     * report -1; the tracked cursor is the fallback, as in the edit methods above.
     */
    private fun editSnapshotOf(et: EditText): InlineEditHistory.Snapshot {
        val text = et.text.toString()
        val fallback = (editingCursorPosition ?: text.length).coerceIn(0, text.length)
        val start = et.selectionStart.let { if (it >= 0) it.coerceIn(0, text.length) else fallback }
        val end = et.selectionEnd.let { if (it >= 0) it.coerceIn(0, text.length) else start }
        return InlineEditHistory.Snapshot(text, start, end)
    }

    /**
     * Put [selected] (cut or copied from the edit field) on the OS clipboard. #156 privacy gate:
     * when editing a PRIVATE (is_private) entry the write must NOT happen silently — that would
     * leak plaintext to the foreground app and other clipboard managers, defeating private copy —
     * so it goes through the same confirm-before-expose dialog as [copyEntryToSystemClipboard].
     * Non-private entries are written immediately.
     */
    private fun exposeEditSelection(selected: String) {
        if (editingIsPrivate) {
            confirmPrivateExposure(selected)
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? SystemClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("CleverKeys", selected))
        }
    }

    /**
     * Word moves, word selection and select word / line in the active edit field — the same
     * [tribixbite.cleverkeys.EditorTextNavigation] rules the app-facing commands use, over the
     * whole text (the IME owns it). A selection change is not a text edit: nothing is recorded
     * in the undo history. False when there is no field or no word to select.
     */
    fun navigateEditText(command: tribixbite.cleverkeys.EditorTextNavigation.Command): Boolean {
        val et = activeEditingEditText ?: return false
        val snapshot = editSnapshotOf(et)
        val window = tribixbite.cleverkeys.EditorTextWindow.ofWholeText(snapshot.text, snapshot.selectionStart, snapshot.selectionEnd)
        val plan = tribixbite.cleverkeys.EditorTextNavigation.plan(command, window)
            as? tribixbite.cleverkeys.EditorTextNavigation.Plan.Select ?: return false
        et.setSelection(plan.start, plan.end)
        editingCursorPosition = plan.end
        return true
    }

    /** Select all text in the active edit field */
    fun selectAllEditText() {
        activeEditingEditText?.let { et ->
            et.selectAll()
            // Track end-of-text so view recycling has a safe fallback position
            editingCursorPosition = et.text.length
        }
    }

    /** Handle arrow keys, Enter, Home, End in the active edit field.
     *  Manual cursor manipulation — dispatchKeyEvent() is a no-op on IME-owned EditText. */
    fun dispatchKeyToEditText(keyCode: Int) {
        activeEditingEditText?.let { et ->
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
        // D-10: bounds-guard stale click positions (see pin_entry).
        val entry = paginatedHistory.getOrNull(pos) ?: return
        if (entry.isMedia && entry.mediaPath != null) {
            // Media entry — use commitContent to send to target app. The private marker
            // rides along so the handler can refuse the system-clipboard fallback (ARC-001).
            val success = ClipboardHistoryService.pasteMedia(
                entry.mimeType, entry.mediaPath, entry.isPrivate
            )
            if (!success) {
                val msg = if (entry.isPrivate) {
                    R.string.clipboard_paste_media_private_refused
                } else {
                    R.string.clipboard_paste_media_unsupported
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } else {
            // Text entry — use standard commitText path
            ClipboardHistoryService.paste(entry.content)
        }
    }

    /**
     * #156 read-path gating (design §5.5): move a stored entry's text onto the OS clipboard.
     * For a NON-private entry this copies immediately. For a PRIVATE entry it asks first — copying
     * a privately-copied clip to the system clipboard exposes it to the foreground app and other
     * clipboard managers, which is exactly what private copy exists to prevent. The confirm dialog
     * doubles as the discoverability moment for what "private" means. Single owner of the
     * confirm-if-private branch so any future "copy entry" affordance inherits the rule.
     */
    fun copyEntryToSystemClipboard(entry: ClipboardEntry) {
        if (entry.isPrivate) {
            confirmPrivateExposure(entry.content)
        } else {
            writeToSystemClipboard(entry.content)
        }
    }

    /** Ask before putting private [text] on the OS clipboard; writes it only on confirmation. */
    private fun confirmPrivateExposure(text: String) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.private_copy_expose_dialog_title)
            .setMessage(R.string.private_copy_expose_dialog_message)
            .setPositiveButton(R.string.private_copy_expose_dialog_confirm) { _, _ ->
                writeToSystemClipboard(text)
            }
            .setNegativeButton(R.string.private_copy_expose_dialog_cancel, null)
            .create()
        Utils.show_dialog_on_ime(dialog, windowToken)
    }

    /** Actually place [text] on the OS clipboard + confirm to the user. Only reached after the
     *  private-entry confirmation (or directly for non-private entries). */
    private fun writeToSystemClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as SystemClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CleverKeys", text))
        Toast.makeText(context, R.string.common_copied_to_clipboard, Toast.LENGTH_SHORT).show()
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
        // Also the guard that makes listener callbacks from a running action's IO thread inert.
        if (bulkRunning) return
        dataReady = false
        onResultsChanged?.invoke()
        loadJob?.cancel()
        val loadingTab = currentTab
        loadJob = viewScope?.launch {
            var entries = withContext(Dispatchers.IO) {
                val database = ClipboardDatabase.getInstance(context)
                when (loadingTab) {
                    ClipboardTab.HISTORY -> service?.clearExpiredAndGetHistory() ?: emptyList()
                    ClipboardTab.PINNED -> database.getPinnedEntries()
                    ClipboardTab.TODOS -> database.getTodoEntries()
                }.map { service?.measureEntry(it) ?: it }
            }
            // Filter out media entries when text-only mode is active
            if (Config.globalConfig().clipboard_text_only) {
                entries = entries.filter { !it.isMedia }
            }
            acceptLoadedHistory(entries, loadingTab)
        }
    }

    /**
     * Main-thread completion of a load: atomically replace the tab's rows, drop selected rows
     * that vanished or changed, and refilter without resetting page or expand state. Rows of
     * [loadedTab] only reconcile a selection of that same tab, and a load for a tab the view
     * has already left is discarded.
     */
    @androidx.annotation.VisibleForTesting
    internal fun acceptLoadedHistory(entries: List<ClipboardEntry>, loadedTab: ClipboardTab = currentTab) {
        if (loadedTab != currentTab) return
        history = entries
        dataReady = true
        selection?.takeIf { it.tab == loadedTab }?.reconcile(entries)
        // resetView=false: preserve page position and expand states on data reload
        applyFilter(resetView = false)
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

    // ─── Private-only filter methods (#156 — all tabs) ───

    /** Enable/disable the private-only filter (show only 🔒 entries). */
    fun setPrivateOnlyFilter(enabled: Boolean) {
        privateOnlyFilter = enabled
        applyFilter()
    }

    /** Whether the private-only filter is currently active. */
    fun isPrivateOnlyFilter(): Boolean = privateOnlyFilter

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

    /** Clear all filters (date + private-only + tags + status) and reset to defaults */
    fun clearAllFilters() {
        dateFilterEnabled = false
        dateFilterTimestamp = 0
        dateFilterBefore = false
        sizeMinimum = 0L
        sizeMaximum = null
        privateOnlyFilter = false
        tagFilterSelected = emptySet()
        tagFilterMatchAll = false
        statusFilterActive = true
        statusFilterPlanned = false
        statusFilterCompleted = false
        applyFilter()
    }

    /** Whether any filter is active (non-default state) — used for filter icon tinting */
    fun hasActiveFilters(): Boolean {
        if (sizeMinimum > 0 || sizeMaximum != null) return true
        if (dateFilterEnabled) return true
        if (privateOnlyFilter) return true
        if (tagFilterSelected.isNotEmpty()) return true
        // D-3: the status filter is "active" whenever any status is hidden — INCLUDING the
        // default active-only state. applyFilter's hasStatusFilter uses the same predicate
        // ("the default state IS a filter": it drops planned/completed rows), and the icon
        // tint must agree or a status-cycled todo vanishes with no indicator.
        if (currentTab == ClipboardTab.TODOS) {
            if (!(statusFilterActive && statusFilterPlanned && statusFilterCompleted)) return true
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

    // ─── Swipe-to-delete gesture (GH #175 part 2) ───
    // Observed in dispatchTouchEvent, so it sees every event whether a row's text/button or the
    // list itself took the DOWN. Nothing changes until ClipboardSwipePolicy classifies the drag
    // as horizontal past the touch slop: taps, long-presses and vertical scrolling are exactly
    // as before. Once horizontal, the children get a CANCEL (no click or long-press fires), the
    // parent ScrollView is told not to intercept, and the row follows the finger.
    private var swipeTracker: ClipboardSwipeTracker? = null
    private var swipeVelocity: VelocityTracker? = null
    private var swipeRow: View? = null
    private var swipeEntry: ClipboardEntry? = null
    private var swipeClaimed = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginRowSwipe(ev)
            MotionEvent.ACTION_POINTER_DOWN ->
                // A second finger is a pinch or a mistake: a claimed swipe springs back.
                if (swipeTracker?.active == true) {
                    val claimed = swipeClaimed
                    abandonRowSwipe(springBack = true)
                    if (claimed) return true
                }
            MotionEvent.ACTION_MOVE -> if (moveRowSwipe(ev)) return true
            MotionEvent.ACTION_UP -> if (swipeClaimed) {
                finishRowSwipe(ev)
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (swipeClaimed) {
                abandonRowSwipe(springBack = true)
                return true
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            abandonRowSwipe(springBack = false)
        }
        return handled
    }

    /** Start tracking a possible swipe on the row under the finger, if a swipe may start now. */
    private fun beginRowSwipe(ev: MotionEvent) {
        abandonRowSwipe(springBack = false)
        val allowed = ClipboardSwipePolicy.swipeAllowed(
            settingEnabled = Config.globalConfig().clipboard_swipe_delete_enabled,
            selecting = selection != null,
            editing = isEditing(),
            tagMode = false,  // the tag panel replaces this list; no row is reachable
            bulkRunning = bulkRunning,
        )
        if (!allowed) return
        val pos = pointToPosition(ev.x.toInt(), ev.y.toInt())
        if (pos == INVALID_POSITION) return
        val entry = paginatedHistory.getOrNull(pos) ?: return
        val row = getChildAt(pos - firstVisiblePosition) ?: return
        val tracker = swipeTracker ?: ClipboardSwipeTracker(
            touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            flingVelocity = ClipboardSwipePolicy.flingVelocityPx(resources.displayMetrics.density),
        ).also { swipeTracker = it }
        tracker.down(ev.rawX, ev.rawY)
        swipeRow = row
        swipeEntry = entry
        swipeVelocity = (swipeVelocity ?: VelocityTracker.obtain()).also {
            it.clear()
            it.addMovement(ev)
        }
    }

    /** Feed a move; returns true when the move belongs to a claimed swipe (consumed here). */
    private fun moveRowSwipe(ev: MotionEvent): Boolean {
        val tracker = swipeTracker?.takeIf { it.active } ?: return false
        val row = swipeRow ?: return false
        swipeVelocity?.addMovement(ev)
        if (tracker.move(ev.rawX, ev.rawY)) {
            swipeClaimed = true
            // From now on neither the ScrollView nor any ancestor may steal the gesture.
            parent?.requestDisallowInterceptTouchEvent(true)
            // Cancel the children's pending click / long-press / pressed state.
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (swipeClaimed) {
            row.translationX = tracker.dx
            row.alpha = ClipboardSwipePolicy.alpha(tracker.dx, row.width.toFloat())
            return true
        }
        // Classified vertical: stop watching so the scroll proceeds untouched.
        if (tracker.axis == ClipboardSwipePolicy.Axis.VERTICAL) abandonRowSwipe(springBack = false)
        return false
    }

    /** Release of a claimed swipe: commit past the threshold or on a fling, else spring back. */
    private fun finishRowSwipe(ev: MotionEvent) {
        val tracker = swipeTracker
        val row = swipeRow
        val entry = swipeEntry
        val velocity = swipeVelocity
        var velocityX = 0f
        if (velocity != null) {
            velocity.addMovement(ev)
            velocity.computeCurrentVelocity(VELOCITY_UNITS_PER_SECOND)
            velocityX = velocity.xVelocity
        }
        val commit = tracker != null && row != null &&
            tracker.release(velocityX, row.width.toFloat())
        // The row is hidden by the refilter; getView resets the recycled row's offset.
        if (commit && entry != null && deferDelete(entry)) {
            resetRowSwipeState()
        } else {
            abandonRowSwipe(springBack = true)
        }
    }

    /** Drop the gesture; a claimed row animates back into place when [springBack]. */
    private fun abandonRowSwipe(springBack: Boolean) {
        val row = swipeRow
        if (springBack && swipeClaimed && row != null) {
            row.animate().translationX(0f).alpha(1f).setDuration(SPRING_BACK_MS).start()
        }
        swipeTracker?.cancel()
        resetRowSwipeState()
    }

    private fun resetRowSwipeState() {
        swipeRow = null
        swipeEntry = null
        swipeClaimed = false
        swipeVelocity?.recycle()
        swipeVelocity = null
    }

    /**
     * TalkBack equivalent of the swipe: a "Delete" custom action on the row's text, which is the
     * row's focus stop, through the same deferred path (Undo appears in the result row). Not
     * gated by the swipe setting: that setting prevents accidental swipes, and a custom action
     * is always deliberate. One delegate per list, shared by every row; the row's entry rides in
     * the text view's tag, rebound on every getView, so a recycled row never acts on another
     * entry. [entry] null removes the action (edit and selection rows have their own deletes).
     */
    private fun bindDeleteAccessibilityAction(textView: TextView, entry: ClipboardEntry?) {
        textView.setTag(R.id.clipboard_entry_delete, entry)
        textView.accessibilityDelegate = if (entry == null) null
            else deleteActionDelegate ?: DeleteActionDelegate().also { deleteActionDelegate = it }
    }

    // Created on first bind (Objenesis-built test views inject their own).
    private var deleteActionDelegate: View.AccessibilityDelegate? = null

    /** Adds the "Delete" custom action and runs [deferDelete] for the host row's entry. */
    private inner class DeleteActionDelegate : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                R.id.clipboard_entry_delete, context.getString(R.string.common_delete)))
        }

        override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
            val entry = host.getTag(R.id.clipboard_entry_delete) as? ClipboardEntry
            return if (action == R.id.clipboard_entry_delete && entry != null) deferDelete(entry)
            else super.performAccessibilityAction(host, action, args)
        }
    }

    /**
     * #130: paints one entry row with the active runtime theme's colors. Rows inflate
     * under the base XML style (wrong ?attr values for runtime themes), so the label
     * color is applied to the entry text and every action button's background tint
     * (the buttons are plain Views styled with `?attr/colorLabel` backgroundTint), and
     * the sub-label color to the provenance line. No-op when no runtime theme is active.
     */
    private fun applyRuntimeRowColors(row: View, textView: TextView, provenanceView: TextView) {
        val rc = runtimeColors ?: return
        if (rc.label != 0) {
            textView.setTextColor(rc.label)
            val tint = android.content.res.ColorStateList.valueOf(rc.label)
            for (id in ENTRY_BUTTON_IDS) {
                row.findViewById<View?>(id)?.backgroundTintList = tint
            }
        }
        if (rc.subLabel != 0) {
            provenanceView.setTextColor(rc.subLabel)
            (row.findViewById<View?>(R.id.clipboard_entry_edit_page_label) as? TextView)?.setTextColor(rc.subLabel)
        }
    }

    /**
     * Render one row in selection mode. Every tap target on the row toggles the same identity
     * (the entry object captured here, never a list position). The checkbox carries the
     * accessible name and checked state, so the text is hidden from TalkBack to avoid a
     * second, action-less focus stop for the same clipping.
     */
    private fun bindSelectionRow(
        row: View,
        entry: ClipboardEntry,
        textView: TextView,
        selectBox: CheckBox,
        thumbnailContainer: View,
    ) {
        val checked = isEntrySelected(entry)
        selectBox.visibility = VISIBLE
        selectBox.isChecked = checked
        selectBox.contentDescription =
            context.getString(
                R.string.clipboard_select_entry_descr,
                ClipboardPreviewPolicy.cut(entry.content, SELECTION_LABEL_CHARS, maxLines = 1).text
            )
        runtimeColors?.label?.takeIf { it != 0 }?.let {
            selectBox.buttonTintList = android.content.res.ColorStateList.valueOf(it)
        }
        val toggle = View.OnClickListener { toggleSelection(entry) }
        selectBox.setOnClickListener(toggle)
        textView.setOnClickListener(toggle)
        thumbnailContainer.setOnClickListener(toggle)
        // Long-press copies in normal mode; in selection mode it must not leak a clip to the
        // OS clipboard by accident, so it toggles like a tap.
        textView.setOnLongClickListener { toggleSelection(entry); true }
        textView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        textView.maxLines = if (entry.isMedia) 2 else 1
        textView.ellipsize = android.text.TextUtils.TruncateAt.END
        // Selected rows are tinted as well as checked, so the state is not colour-only
        // or checkbox-only.
        row.setBackgroundColor(if (checked) selectedRowColor() else android.graphics.Color.TRANSPARENT)
    }

    /** Return a (possibly recycled) row to its non-selection state. */
    private fun resetSelectionRow(row: View, textView: TextView, selectBox: CheckBox) {
        selectBox.visibility = GONE
        selectBox.setOnClickListener(null)
        textView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        row.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    /** "Showing the first N of M characters…" for an expanded row whose preview was cut. */
    private fun truncationNote(content: String, preview: ClipboardPreviewPolicy.Preview): String {
        val (shown, total) = ClipboardPreviewPolicy.codePointCounts(content, preview)
        val numbers = java.text.NumberFormat.getIntegerInstance()
        return context.getString(
            R.string.clipboard_entry_preview_truncated, numbers.format(shown), numbers.format(total)
        )
    }

    /** Translucent label colour (runtime theme aware) behind selected rows. */
    private fun selectedRowColor(): Int {
        val typed = android.util.TypedValue()
        val xml = if (context.theme.resolveAttribute(R.attr.colorLabel, typed, true)) typed.data else null
        val label = ClipboardPaneThemePolicy.effectiveColor(
            isRuntimeTheme = runtimeColors != null,
            runtimeColor = runtimeColors?.label ?: 0,
            xmlResolved = xml,
            fallback = android.graphics.Color.WHITE
        )
        return androidx.core.graphics.ColorUtils.setAlphaComponent(label, SELECTED_ROW_ALPHA)
    }

    inner class ClipboardEntriesAdapter : BaseAdapter() {
        override fun getCount(): Int = paginatedHistory.size

        override fun getItem(pos: Int): Any = paginatedHistory[pos]

        override fun getItemId(pos: Int): Long = paginatedHistory[pos].hashCode().toLong()

        override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
            val view = v ?: View.inflate(context, R.layout.clipboard_history_entry, null)
            // A recycled row may carry a swipe offset or a running spring-back: rebind upright,
            // except the row the finger is dragging right now (its next move re-applies it).
            if (view !== swipeRow || !swipeClaimed) {
                view.animate().cancel()
                view.translationX = 0f
                view.alpha = 1f
            }

            val entry = paginatedHistory[pos]
            val text = entry.content
            val textView = view.findViewById<TextView>(R.id.clipboard_entry_text)
            val editField = view.findViewById<EditText>(R.id.clipboard_entry_edit_field)
            // D-7: inline save_edit failure line (Toasts are invisible in IME context)
            val editErrorView = view.findViewById<TextView>(R.id.clipboard_entry_edit_error)
            val expandButton = view.findViewById<View>(R.id.clipboard_entry_expand)
            val editButton = view.findViewById<View>(R.id.clipboard_entry_edit)
            val primaryButtons = view.findViewById<LinearLayout>(R.id.clipboard_entry_primary_buttons)
            val editButtons = view.findViewById<LinearLayout>(R.id.clipboard_entry_edit_buttons)
            val deleteRow = view.findViewById<LinearLayout>(R.id.clipboard_entry_delete_row)
            val secondaryButtons = view.findViewById<LinearLayout>(R.id.clipboard_entry_secondary_buttons)
            val thumbnailContainer = view.findViewById<FrameLayout>(R.id.clipboard_entry_thumbnail_container)
            val thumbnailView = view.findViewById<ImageView>(R.id.clipboard_entry_thumbnail)
            val playBadge = view.findViewById<ImageView>(R.id.clipboard_entry_play_badge)
            // #156: 🔒 badge — VISIBLE only for entries copied privately (never on the OS clipboard).
            val privateBadge = view.findViewById<TextView>(R.id.clipboard_entry_private_badge)
            // #156 / ARC-011: "Private copy · via <app>" — expanded rows only (design §8).
            val provenanceView = view.findViewById<TextView>(R.id.clipboard_entry_provenance)

            // Secondary row buttons (shown on tap-expand, tab-specific)
            val pinButton = view.findViewById<View>(R.id.clipboard_entry_addpin)
            val unpinButton = view.findViewById<View>(R.id.clipboard_entry_unpin)
            val todoButton = view.findViewById<View>(R.id.clipboard_entry_addtodo)
            val doneButton = view.findViewById<View>(R.id.clipboard_entry_done)
            val statusButton = view.findViewById<View>(R.id.clipboard_entry_status)
            val tagsButton = view.findViewById<View>(R.id.clipboard_entry_tags)
            // Delete is in the edit_buttons row (only visible during edit mode)
            val deleteButton = view.findViewById<View>(R.id.clipboard_entry_delete)
            // Selection-mode checkbox (GONE outside selection mode)
            val selectBox = view.findViewById<CheckBox>(R.id.clipboard_entry_select)

            // #130: under a runtime theme, override the row's base-style ?attr colors
            // (entry text + button tints) with the active theme's — same colors the pane
            // chrome and the keyboard itself use. Runs on every getView so recycled rows
            // are correct too. No-op for XML-style themes (runtimeColors == null).
            applyRuntimeRowColors(view, textView, provenanceView)

            // Bug #2 fix: match by content identity, not list position — survives list shifts
            val isEditingThis = editingOriginalContent != null && entry.content == editingOriginalContent

            // Rows bind a bounded preview, never the full text (Saga 2026-10-26, todo 22 a):
            // a TextView line-breaks ALL of its text even when it shows one ellipsized line, so
            // ~0.5 MB entries blocked the main thread ~50 s per bind (pane open, Select mode)
            // and a tap meanwhile was an ANR. Select mode always shows rows collapsed. Paste,
            // copy and the editor still use entry.content.
            val isExpanded = selection == null && expandedStates[entry.timestamp] == true
            val preview = ClipboardPreviewPolicy.forRow(text, isExpanded)
            val previewNote = if (isExpanded && preview.truncated) truncationNote(text, preview) else null

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
                privateBadge.visibility = GONE  // #156: hidden during edit
                provenanceView.visibility = GONE  // ARC-011: hidden during edit
                // Edit and selection are mutually exclusive; reset recycled selection rows.
                resetSelectionRow(view, textView, selectBox)
                bindDeleteAccessibilityAction(textView, null)  // edit mode has its own delete

                // D-7: render any pending save failure inline; cleared when the user types.
                val errRes = editingErrorRes
                if (errRes != null) {
                    editErrorView.setText(errRes)
                    editErrorView.visibility = VISIBLE
                } else {
                    editErrorView.visibility = GONE
                }

                // Paged edit: "Page N of M" + prev/next above the field (GONE otherwise).
                bindEditPager(view)
                fitEditFieldHeight(editField)

                // Bug #3 fix: use in-progress text (not DB content) to survive view recreation.
                // Always set text+visibility — needed for correct height during measurement.
                // Never the full text of a paged entry: only its current page (todo 22).
                val displayText = editingInProgressText ?: text
                editField.setText(displayText)

                // Restore cursor after setText (which resets to 0). Must run on EVERY
                // getView call, not just first render — ListView re-getViews after height
                // changes and the cursor would jump to 0 without this.
                val cursorPos = editingCursorPosition ?: displayText.length
                editField.setSelection(cursorPos.coerceIn(0, displayText.length))

                // Save button state — reflects current content on every render
                val saveButton = view.findViewById<View>(R.id.clipboard_entry_save)
                // A paged entry may be saved with a blank page when other pages hold text
                // (othersBlank is computed per page move, not per keystroke).
                val othersHaveText = editingPages?.othersBlank == false
                val canSaveNow = othersHaveText || displayText.isNotBlank()
                saveButton.isEnabled = canSaveNow
                saveButton.alpha = if (canSaveNow) 1.0f else 0.3f

                // Click listeners: setOnClickListener replaces previous, no accumulation.
                // Must be set on every getView because ListView may provide a recycled view
                // with different button widget instances than the initial render.
                saveButton.setOnClickListener { save_edit() }
                view.findViewById<View>(R.id.clipboard_entry_cancel).setOnClickListener { cancelEdit() }
                deleteButton.setOnClickListener { delete_entry(pos) }

                // TextWatcher: tag-based management prevents accumulation on view recycling.
                // Remove any existing watcher from THIS editField (via tag), then add fresh one.
                @Suppress("UNCHECKED_CAST")
                (editField.getTag(R.id.clipboard_entry_edit_field) as? android.text.TextWatcher)?.let {
                    editField.removeTextChangedListener(it)
                }
                val watcher = object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        editingInProgressText = s?.toString()
                        // D-7: the user is addressing the failed save — retire the error line.
                        if (editingErrorRes != null) {
                            editingErrorRes = null
                            editErrorView.visibility = GONE
                        }
                        // NOTE: Do NOT update editingCursorPosition here.
                        // setText() resets cursor to 0 before our setSelection() corrects it.
                        // The TextWatcher fires between those two calls, capturing the wrong
                        // position (0). Cursor is tracked explicitly in insertEditText/backspace/etc.
                        val canSave = othersHaveText || !s.isNullOrBlank()
                        saveButton.isEnabled = canSave
                        saveButton.alpha = if (canSave) 1.0f else 0.3f
                    }
                }
                editField.addTextChangedListener(watcher)
                editField.setTag(R.id.clipboard_entry_edit_field, watcher)
                editingTextWatcher = watcher

                // CRITICAL: Only cache editingEditText on first real render.
                // ListView.measureHeightOfChildren() calls getView() with SCRAP views for
                // height calculation after a newline changes the EditText height.
                // activeEditingEditText property recovers from stale scrap references,
                // but preventing the theft in the first place is the primary defense.
                if (editingEditText == null) {
                    editingEditText = editField
                }

                // Long entries (soak bug 2026-10-25): the row grows with its text inside the
                // pane's ScrollView, and TextView only scrolls its caret into view
                // (bringPointIntoView → requestRectangleOnScreen → the ScrollView) and draws it
                // while it has focus. Unfocused, a caret past the pane's bottom took every typed
                // character out of sight. View focus inside the IME window does not take window
                // focus from the app, so the keyboard stays up (tapping the field already focused
                // it on device). Posted, so a scrap measurement view (never attached) is skipped.
                // The keyboard is the input method: focusing must not ask the platform for one
                // (showSoftInputOnFocus has no public XML attribute).
                editField.showSoftInputOnFocus = false
                editField.post {
                    if (editField === activeEditingEditText && !editField.isFocused) editField.requestFocus()
                }

                return view
            }

            // ── Normal mode: standard rendering ──
            textView.visibility = VISIBLE
            editField.visibility = GONE
            view.findViewById<View?>(R.id.clipboard_entry_edit_pager)?.visibility = GONE  // recycled edit rows
            editErrorView.visibility = GONE  // D-7: recycled edit rows must not leak the error line
            primaryButtons.visibility = VISIBLE
            editButtons.visibility = GONE
            deleteRow.visibility = GONE
            // #156: 🔒 badge on entries copied privately (marker travels through pin/todo copies).
            privateBadge.visibility = if (entry.isPrivate) VISIBLE else GONE

            // Clean up TextWatcher from recycled edit views. If this view was previously
            // used for editing (scrap or real), the watcher's lambda captures saveButton
            // and adapter context — remove it to prevent minor memory retention.
            @Suppress("UNCHECKED_CAST")
            (editField.getTag(R.id.clipboard_entry_edit_field) as? android.text.TextWatcher)?.let {
                editField.removeTextChangedListener(it)
                editField.setTag(R.id.clipboard_entry_edit_field, null)
            }

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
                // D-6: play badge for media that is ACTUALLY animated, per the header-parsing
                // detector (memoized per row). The old heuristic — `gif || (webp && mediaPath
                // != null)` — badged every WebP, because mediaPath is set for all saved media.
                val mime = entry.mimeType
                val isAnimated = if (mime == "image/gif" || mime == "image/webp") {
                    entry.mediaPath?.let { path ->
                        animatedCache.getOrPut(entry.timestamp) {
                            service?.isMediaAnimated(path, mime) ?: false
                        }
                    } ?: false
                } else {
                    false
                }
                playBadge.visibility = if (isAnimated) VISIBLE else GONE

                // For media entries, show MIME label + filename instead of content body
                textView.text = entry.getFormattedText(context, preview.text, previewNote)
            } else {
                thumbnailContainer.visibility = GONE
                playBadge.visibility = GONE

                // ── Todo status visual indicator (prefix + strikethrough) ──
                if (currentTab == ClipboardTab.TODOS && entry.todoStatus != null) {
                    val prefix = TodoEntry.statusPrefix(entry.todoStatus, context.resources)
                    // Strikethrough on the shown text when completed; the metadata suffix and
                    // any truncation note dimmed. #130: the effective sub-label color is the
                    // runtime theme's value when one is active (the ?attr below resolves to the
                    // base style for runtime themes), else the theme attr, else a system fallback.
                    textView.text = entry.getFormattedTodoText(context, prefix, preview.text, previewNote) {
                        val typed = android.util.TypedValue()
                        context.theme.resolveAttribute(R.attr.colorSubLabel, typed, true)
                        ClipboardPaneThemePolicy.effectiveColor(
                            isRuntimeTheme = runtimeColors != null,
                            runtimeColor = runtimeColors?.subLabel ?: 0,
                            xmlResolved = typed.data.takeIf { it != 0 },
                            fallback = androidx.core.content.ContextCompat.getColor(
                                context, android.R.color.secondary_text_dark
                            )
                        )
                    }
                } else {
                    textView.text = entry.getFormattedText(context, preview.text, previewNote)
                }
            }

            // ── Selection mode: checkbox + whole-row toggle, no per-entry actions ──
            if (selection != null) {
                bindSelectionRow(view, entry, textView, selectBox, thumbnailContainer)
                bindDeleteAccessibilityAction(textView, null)  // bulk delete only in selection mode
                primaryButtons.visibility = GONE
                secondaryButtons.visibility = GONE
                deleteRow.visibility = GONE
                provenanceView.visibility = GONE
                return view
            }
            resetSelectionRow(view, textView, selectBox)
            // ── Swipe-to-delete's accessibility equivalent (GH #175 part 2) ──
            bindDeleteAccessibilityAction(textView, entry)

            // ── Expand state: single tap toggles text expansion + secondary row ──
            val isMultiLine = text.contains("\n")

            // Set maxLines based on expanded state (applies to all entries)
            if (isExpanded) {
                textView.maxLines = Int.MAX_VALUE
                textView.ellipsize = null
            } else {
                textView.maxLines = if (entry.isMedia) 2 else 1
                textView.ellipsize = android.text.TextUtils.TruncateAt.END
            }

            // Expand chevron shown for multi-line text AND media rows — rotates as visual
            // indicator. Media rows keep delete/provenance/secondary actions behind
            // expansion, so the chevron is the visible cue that the row expands at all.
            if (isMultiLine || entry.isMedia) {
                expandButton.visibility = VISIBLE
                expandButton.rotation = if (isExpanded) 180f else 0f
            } else {
                expandButton.visibility = GONE
            }

            // Show edit button for text entries only (media entries cannot be edited inline).
            // Entries too long to edit whole open a page at a time (ClipboardEditPages).
            editButton.visibility = if (!entry.isMedia) VISIBLE else GONE
            editButton.setOnClickListener {
                if (!isEditing()) edit_entry(pos)
            }

            // ── Secondary buttons: VISIBLE when expanded, GONE otherwise ──
            secondaryButtons.visibility = if (isExpanded && !isEditingThis) VISIBLE else GONE

            // ── Media delete affordance (maintainer report 2026-09, no GH issue) ──
            // Media rows cannot enter edit mode (edit_entry returns early for isMedia) —
            // and edit mode was the ONLY surface exposing the delete row, leaving media
            // entries undeletable from the UI. Deletion needs no edit precondition, so an
            // expanded media row shows the delete row directly. delete_entry routes per-tab
            // (history remove / unpin / un-todo) and the service deletes the on-disk media
            // file iff no other tab's COPY still references it. Text rows keep the existing
            // idiom: delete stays behind edit mode's deliberate friction.
            if (entry.isMedia) {
                deleteRow.visibility = if (isExpanded) VISIBLE else GONE
                deleteButton.setOnClickListener {
                    if (!isEditing()) delete_entry(pos)
                }
            }

            // ── #156 / ARC-011: provenance line, expanded rows only ──
            // The private-copy threat review (§6.2/§6.3/§6.6) accepted the content-injection
            // risk of the exported PROCESS_TEXT activity BECAUSE the entry detail names the
            // app that produced the row. Rendering it here is what makes that acceptance true.
            // Null source_package (pre-V5 rows, ordinary OS-clipboard captures) → no line at all.
            val provenance = if (isExpanded) provenanceText(entry) else null
            if (provenance != null) {
                provenanceView.text = provenance
                provenanceView.visibility = VISIBLE
            } else {
                provenanceView.visibility = GONE
            }

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

            // The thumbnail is a media row's natural tap target — same toggle as the text.
            // Harmless for text rows (the container is GONE and receives no taps).
            thumbnailContainer.setOnClickListener {
                expandedStates[entry.timestamp] = !isExpanded
                notifyDataSetChanged()
            }

            // Long-press copies entry text to system clipboard.
            // #156: routed through copyEntryToSystemClipboard, which confirms first for private
            // entries so the user can't accidentally leak a privately-copied clip to the OS.
            textView.setOnLongClickListener {
                if (isEditing()) return@setOnLongClickListener true  // Block during edit
                copyEntryToSystemClipboard(entry)
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
