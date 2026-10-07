package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.Log
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.text.format.Formatter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
// NOTE: framework Switch on purpose — the filter dialog inflates under the framework
// Theme_DeviceDefault_Dialog where SwitchCompat has no switchStyle and crashes at measure
// (StaticLayout NPE via makeLayout(null)). See res/layout/clipboard_filter_dialog.xml header.
import android.widget.Switch
import android.widget.TextView
import tribixbite.cleverkeys.clipboard.ClipboardBulkPlans
import tribixbite.cleverkeys.clipboard.ClipboardSelection
import tribixbite.cleverkeys.clipboard.ClipboardSelectionAction
import tribixbite.cleverkeys.clipboard.ClipboardSelectionHolder
import tribixbite.cleverkeys.theme.ThemeProvider
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Keeps a full entry row visible in short, wide clipboard panes. Measuring the
 * available width also handles split-screen and remeasurement after rotation;
 * device orientation alone does not describe the space the IME actually owns.
 */
class ClipboardPaneLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        fun pixels(dp: Int) = (dp * density).roundToInt()
        val wide = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            MeasureSpec.getSize(widthMeasureSpec) >= pixels(WIDE_MIN_DP)
        // Reserve room for the visible tabs, search and its action icons. Selection mode hides
        // the non-current tabs, which returns their width to counts, paging and selection
        // actions instead of starving the results beside a fixed-width search bar.
        val visibleTabs = TAB_IDS.count { findViewById<View?>(it)?.visibility == VISIBLE }
        findViewById<LinearLayout>(R.id.clipboard_controls).apply {
            val orientation = if (wide) HORIZONTAL else VERTICAL
            if (this.orientation != orientation) this.orientation = orientation
        }
        updateParams(R.id.clipboard_search_bar,
            width = if (wide) pixels(SEARCH_BASE_DP + TAB_DP * visibleTabs) else LayoutParams.MATCH_PARENT,
            height = pixels(if (wide) 48 else 40), weight = 0f)
        updateParams(R.id.clipboard_result_controls,
            width = if (wide) 0 else LayoutParams.MATCH_PARENT,
            height = LayoutParams.WRAP_CONTENT, weight = if (wide) 1f else 0f)
        // Narrow panes are tall: the selection actions take their own row. Wide panes are
        // short (landscape): they sit beside the results so a full entry row stays visible.
        updateParams(R.id.clipboard_selection_bar,
            width = if (wide) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
            height = pixels(48), weight = 0f)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Assign layout params only when they differ, so measuring never loops on requestLayout. */
    private fun updateParams(id: Int, width: Int, height: Int, weight: Float) {
        val view = findViewById<View?>(id) ?: return
        val params = view.layoutParams as LayoutParams
        if (params.width == width && params.height == height && params.weight == weight) return
        params.width = width
        params.height = height
        params.weight = weight
        view.layoutParams = params
    }

    private companion object {
        /** Panes at least this wide put search and results side by side. */
        const val WIDE_MIN_DP = 720
        /** Wide search bar without tab icons: search text plus its clear/regex/filter/close icons. */
        const val SEARCH_BASE_DP = 212
        /** Width of one tab icon (36dp ImageView). */
        const val TAB_DP = 36
        val TAB_IDS = intArrayOf(R.id.tab_history, R.id.tab_pinned, R.id.tab_todos)
    }
}

/**
 * Manages clipboard pane and clipboard history search functionality.
 *
 * This class centralizes the management of:
 * - Clipboard pane view lifecycle
 * - Clipboard search mode and search box
 * - Date filter dialog
 *
 * Responsibilities:
 * - Initialize and inflate clipboard pane views
 * - Manage clipboard search mode state
 * - Handle search text modification (append, delete, clear)
 * - Show and configure date filter dialog
 *
 * NOT included (remains in CleverKeysService):
 * - Content pane container management (shared with emoji pane)
 * - Input view switching and lifecycle
 * - Key event routing during search mode
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.349).
 */
class ClipboardManager(
    private val context: Context,
    private var config: Config
) {
    // #130: Active runtime theme (custom_/decorative_) cached at pane-build time.
    // Runtime themes have no XML style, so clipboard_pane.xml's ?attr/color*
    // resolve to the hardcoded base style (CleverKeysDark purple) instead of the
    // active theme — we apply the real colors programmatically, like Keyboard2View.
    private var runtimeTheme: Theme? = null

    // #130: Theme signature the cached pane was inflated + painted under. setConfig
    // compares it against the current config's signature and drops the pane on change,
    // so a theme switch (or an edit to the active custom theme's colors) while the IME
    // service lives is picked up on the next clipboard open instead of after restart.
    private var appliedThemeSignature: ClipboardPaneThemePolicy.ThemeSignature? = null

    // Clipboard views
    private var clipboardPane: ViewGroup? = null
    private var clipboardSearchBox: TextView? = null
    private var clipboardSearchClear: ImageButton? = null
    private var regexToggle: TextView? = null
    private var clipboardHistoryView: ClipboardHistoryView? = null

    private var selectButton: Button? = null
    private var resultSummary: TextView? = null
    private var bulkFeedback: TextView? = null
    private var bulkDialog: android.app.AlertDialog? = null

    // Selection mode (2026-10-07). The selection itself lives in this service-scoped holder so
    // it survives pane/view recreation, rotation, keyboard hide and field/app switches; every
    // inflated ClipboardHistoryView attaches to it. The views below only render it: the live
    // count, and the select/deselect-all-matching, clear, more-actions, delete and exit actions.
    private val selectionHolder = ClipboardSelectionHolder()
    private var selectionBar: View? = null
    private var selectionCount: TextView? = null
    private var selectMatchingButton: ImageButton? = null
    private var clearSelectionButton: ImageButton? = null
    private var deleteSelectedButton: ImageButton? = null
    private var selectionActionsButton: ImageButton? = null
    private var exitSelectionButton: ImageButton? = null

    // Tab buttons (ImageViews with vector drawable icons, tinted by colorLabel)
    private var tabHistory: ImageView? = null
    private var tabPinned: ImageView? = null
    private var tabTodos: ImageView? = null

    // Pagination views
    private var paginationBar: View? = null
    private var pagePrev: TextView? = null
    private var pageInfo: TextView? = null
    private var pageNext: TextView? = null

    // Filter button (calendar icon — tinted when filters active)
    private var filterButton: ImageView? = null

    // Content area views (mutually exclusive: content scroll vs tag panel)
    private var contentScroll: View? = null
    private var tagPanel: View? = null
    private var tagPanelContent: LinearLayout? = null

    // Current tab state
    private var currentTab = ClipboardTab.HISTORY

    // Close callback (set by CleverKeysService to hide clipboard pane)
    private var onCloseCallback: (() -> Unit)? = null

    // Search state
    private var searchMode = false
    // Original search box text color — saved at init to restore after regex error tint
    private var searchBoxDefaultTextColor: Int = 0

    // ─── Tag panel state (inline panel, replaces entry list when active) ───
    // Owns the tag EditText directly — key routing delegates here, not to ClipboardHistoryView
    private var tagMode = false
    private var tagEditText: EditText? = null

    // Edit mode state — delegates to ClipboardHistoryView for actual text manipulation

    /**
     * Gets or creates the clipboard pane view.
     * Performs lazy initialization on first call.
     *
     * @param layoutInflater LayoutInflater for inflating views
     * @return Clipboard pane ViewGroup
     */
    fun getClipboardPane(layoutInflater: LayoutInflater): ViewGroup {
        if (clipboardPane == null) {
            // Inflate clipboard pane layout with correct theme (v1.32.415: fix theme attribute resolution)
            val themedContext = ContextThemeWrapper(context, config.theme)
            clipboardPane = View.inflate(themedContext, R.layout.clipboard_pane, null) as ViewGroup

            // #130: for runtime themes the inflated ?attr/color* are the wrong
            // (base-style) colors — overwrite them with the active theme's colors
            // BEFORE we read searchBoxDefaultTextColor below.
            applyRuntimeThemeColors()

            // Get search box and history view references
            clipboardSearchBox = clipboardPane?.findViewById(R.id.clipboard_search)
            // Save the themed text color before any setTextColor() calls overwrite it
            searchBoxDefaultTextColor = clipboardSearchBox?.currentTextColor ?: 0
            clipboardHistoryView = clipboardPane?.findViewById(R.id.clipboard_history_view)
            // A re-inflated pane (theme change) resumes the selection the user built.
            clipboardHistoryView?.attachSelectionHolder(selectionHolder)

            // Set up search box click listener
            clipboardSearchBox?.setOnClickListener {
                // Block search activation during edit mode — edit takes priority
                if (isInEditMode()) return@setOnClickListener
                searchMode = true
                clipboardSearchBox?.hint = context.getString(R.string.clipboard_search_hint_typing)
                clipboardSearchBox?.requestFocus()
            }

            // Set up search clear button (X)
            clipboardSearchClear = clipboardPane?.findViewById(R.id.clipboard_search_clear)
            clipboardSearchClear?.setOnClickListener {
                clearSearch()
            }

            // Set up regex toggle button (.*)
            regexToggle = clipboardPane?.findViewById(R.id.clipboard_regex_toggle)
            regexToggle?.setOnClickListener {
                if (isInEditMode()) return@setOnClickListener
                val historyView = clipboardHistoryView ?: return@setOnClickListener
                val newState = !historyView.isRegexMode()
                historyView.setRegexMode(newState)
                updateRegexToggleVisual(newState)
                // Re-check error state after toggling mode
                updateSearchBoxErrorState(historyView.hasRegexError())
            }

            // Set up filter icon (opens unified filter dialog)
            filterButton = clipboardPane?.findViewById<ImageView>(R.id.clipboard_date_filter)
            filterButton?.setOnClickListener { v ->
                if (!isInEditMode()) showFilterDialog(v)
            }

            // Set up close button
            clipboardPane?.findViewById<ImageButton>(R.id.clipboard_close_button)?.setOnClickListener {
                onCloseCallback?.invoke()
            }

            // Set up tab buttons (ImageViews with vector icons)
            tabHistory = clipboardPane?.findViewById<ImageView>(R.id.tab_history)
            tabPinned = clipboardPane?.findViewById<ImageView>(R.id.tab_pinned)
            tabTodos = clipboardPane?.findViewById<ImageView>(R.id.tab_todos)

            // Selection mode hides the other tabs (a selection is scoped to one tab's rows);
            // the guard covers a tap that lands while the visibility change is pending.
            tabHistory?.setOnClickListener { if (canSwitchTabs()) switchToTab(ClipboardTab.HISTORY) }
            tabPinned?.setOnClickListener { if (canSwitchTabs()) switchToTab(ClipboardTab.PINNED) }
            tabTodos?.setOnClickListener { if (canSwitchTabs()) switchToTab(ClipboardTab.TODOS) }

            // Visual feedback: pulse target tab icon when item is added to another tab
            // pulseCount: 1 = success, 3 = duplicate (already exists)
            clipboardHistoryView?.onItemAddedToTab = { tab, pulseCount ->
                val target = when (tab) {
                    ClipboardTab.PINNED -> tabPinned
                    ClipboardTab.TODOS -> tabTodos
                    else -> null
                }
                target?.let { pulseTabIcon(it, pulseCount) }
            }

            selectButton = clipboardPane?.findViewById(R.id.clipboard_select)
            resultSummary = clipboardPane?.findViewById(R.id.clipboard_result_summary)
            bulkFeedback = clipboardPane?.findViewById(R.id.clipboard_bulk_feedback)
            selectionBar = clipboardPane?.findViewById(R.id.clipboard_selection_bar)
            selectionCount = clipboardPane?.findViewById(R.id.clipboard_selection_count)
            selectMatchingButton = clipboardPane?.findViewById(R.id.clipboard_select_matching)
            clearSelectionButton = clipboardPane?.findViewById(R.id.clipboard_selection_clear)
            deleteSelectedButton = clipboardPane?.findViewById(R.id.clipboard_delete_selected)
            selectionActionsButton = clipboardPane?.findViewById(R.id.clipboard_selection_actions)
            exitSelectionButton = clipboardPane?.findViewById(R.id.clipboard_selection_exit)
            selectButton?.setOnClickListener { enterSelectionMode() }
            selectMatchingButton?.setOnClickListener { toggleAllMatching() }
            clearSelectionButton?.setOnClickListener { clipboardHistoryView?.clearSelection() }
            deleteSelectedButton?.setOnClickListener { confirmDeleteSelected(it) }
            selectionActionsButton?.setOnClickListener { showSelectionActions(it) }
            // Icon-only actions: long-press tooltips (API 26+) carry the same names as TalkBack.
            clearSelectionButton?.let { describe(it, R.string.clipboard_selection_clear) }
            selectionActionsButton?.let { describe(it, R.string.clipboard_selection_actions) }
            deleteSelectedButton?.let { describe(it, R.string.clipboard_delete_selected) }
            exitSelectionButton?.let { describe(it, R.string.clipboard_selection_exit) }
            exitSelectionButton?.setOnClickListener { exitSelectionMode() }
            clipboardHistoryView?.onResultsChanged = { updateResultSummary() }
            updateResultSummary()

            // Apply tab visibility based on config toggles
            applyTabVisibility()

            // Set initial tab highlighting
            updateTabHighlighting()

            // Set up pagination controls
            paginationBar = clipboardPane?.findViewById(R.id.clipboard_pagination_bar)
            pagePrev = clipboardPane?.findViewById(R.id.clipboard_page_prev)
            pageInfo = clipboardPane?.findViewById(R.id.clipboard_page_info)
            pageNext = clipboardPane?.findViewById(R.id.clipboard_page_next)
            // ◀/▶ swap under RTL, where the bar lays "previous" out on the right.
            PanePagerArrows.apply(pagePrev, pageNext)

            pagePrev?.setOnClickListener {
                if (!isInEditMode()) clipboardHistoryView?.previousPage()
            }
            pageNext?.setOnClickListener {
                if (!isInEditMode()) clipboardHistoryView?.nextPage()
            }

            // Content area views for tag panel toggling
            contentScroll = clipboardPane?.findViewById(R.id.clipboard_content_scroll)
            tagPanel = clipboardPane?.findViewById(R.id.clipboard_tag_panel)
            tagPanelContent = clipboardPane?.findViewById(R.id.clipboard_tag_panel_content)

            // Edit mode: disable search input routing and visually dim non-edit controls.
            // Search filter text stays visible (clearing it would rebuild the list and
            // scroll the edited entry off-screen).
            clipboardHistoryView?.onEditModeEntered = {
                searchMode = false
                // Mutual exclusion: close tag panel when entering edit mode
                if (tagMode) hideTagPanel()
                setEditModeLockUI(true)
                updateResultSummary()
            }
            clipboardHistoryView?.onEditModeExited = {
                setEditModeLockUI(false)
                updateResultSummary()
            }

            // Tag panel: ClipboardHistoryView requests tag panel via callback
            clipboardHistoryView?.onTagPanelRequested = { entry, tab ->
                showTagPanel(entry, tab)
            }

            // Listen for pagination state changes
            clipboardHistoryView?.setOnPaginationChangeListener { needsPagination, currentPage, totalPages ->
                paginationBar?.visibility = if (needsPagination) View.VISIBLE else View.GONE
                pageInfo?.text = context.getString(R.string.page_indicator, currentPage, totalPages)
                pagePrev?.alpha = if (clipboardHistoryView?.hasPreviousPage() == true) 1.0f else 0.3f
                pageNext?.alpha = if (clipboardHistoryView?.hasNextPage() == true) 1.0f else 0.3f
            }
        }

        return clipboardPane!!
    }

    /**
     * Sets the callback to be invoked when close button is pressed.
     *
     * @param callback Callback to hide clipboard pane
     */
    fun setOnCloseCallback(callback: () -> Unit) {
        onCloseCallback = callback
    }

    /**
     * Switches to the specified tab and updates UI.
     *
     * @param tab Target tab to switch to
     */
    private fun switchToTab(tab: ClipboardTab) {
        if (currentTab == tab) return
        // Deletion feedback belongs to the previous tab's confirmed snapshot.
        bulkFeedback?.visibility = View.GONE

        // Cancel any in-progress edit or tag panel when switching tabs
        exitEditMode()
        if (tagMode) hideTagPanel()
        // A selection holds one tab's row ids; it (and any open confirmation) ends here.
        exitSelectionMode()
        currentTab = tab
        clipboardHistoryView?.setTab(tab)
        updateTabHighlighting()
        updateFilterIconTint()
        // Search persists across tabs — user can clear via X button
    }

    /**
     * Updates tab button highlighting based on current tab.
     * Active tab has full alpha (1.0), inactive tabs are dimmed (0.5).
     */
    private fun updateTabHighlighting() {
        val activeAlpha = 1.0f
        val inactiveAlpha = 0.5f

        tabHistory?.alpha = if (currentTab == ClipboardTab.HISTORY) activeAlpha else inactiveAlpha
        tabPinned?.alpha = if (currentTab == ClipboardTab.PINNED) activeAlpha else inactiveAlpha
        tabTodos?.alpha = if (currentTab == ClipboardTab.TODOS) activeAlpha else inactiveAlpha
    }

    /**
     * Scale-pulse a tab icon to give visual feedback.
     * Single pulse (count=1) = item added successfully.
     * Triple pulse (count=3) = duplicate, already exists in target tab.
     */
    private fun pulseTabIcon(icon: ImageView, count: Int = 1) {
        icon.animate().cancel()
        val restoreAlpha = if (currentTab == ClipboardTab.PINNED && icon == tabPinned ||
                               currentTab == ClipboardTab.TODOS && icon == tabTodos)
                               1.0f else 0.5f
        doPulse(icon, count, restoreAlpha)
    }

    /** Recursive single-pulse step: scales up then down, then chains remaining pulses. */
    private fun doPulse(icon: ImageView, remaining: Int, restoreAlpha: Float) {
        if (remaining <= 0) {
            // Final restore
            icon.animate().scaleX(1.0f).scaleY(1.0f).alpha(restoreAlpha).setDuration(150).start()
            return
        }
        icon.animate()
            .scaleX(1.5f).scaleY(1.5f)
            .alpha(1.0f)
            .setDuration(100)
            .withEndAction {
                icon.animate()
                    .scaleX(1.0f).scaleY(1.0f)
                    .setDuration(100)
                    .withEndAction { doPulse(icon, remaining - 1, restoreAlpha) }
                    .start()
            }
            .start()
    }

    /**
     * Gets the current active tab.
     *
     * @return Current ClipboardTab
     */
    fun getCurrentTab(): ClipboardTab = currentTab

    /**
     * Checks if clipboard search mode is active.
     *
     * @return true if in search mode
     */
    fun isInSearchMode(): Boolean = searchMode

    /**
     * Appends text to clipboard search box and updates filter.
     *
     * @param text Text to append
     */
    fun appendToSearch(text: String) {
        clipboardSearchBox?.let { searchBox ->
            clipboardHistoryView?.let { historyView ->
                // Append to current search text
                val current = searchBox.text
                val newText = current.toString() + text
                searchBox.text = newText

                // Update history view filter
                historyView.setSearchFilter(newText)

                // Show clear button when there's text
                updateSearchClearVisibility(newText)
                // Update error state for regex mode
                updateSearchBoxErrorState(historyView.hasRegexError())
            }
        }
    }

    /**
     * Deletes last character from clipboard search box and updates filter.
     */
    fun deleteFromSearch() {
        clipboardSearchBox?.let { searchBox ->
            clipboardHistoryView?.let { historyView ->
                val current = searchBox.text

                // Delete last character
                if (current.isNotEmpty()) {
                    val newText = current.subSequence(0, current.length - 1).toString()
                    searchBox.text = newText

                    // Update history view filter
                    historyView.setSearchFilter(newText)

                    // Hide clear button when search is empty
                    updateSearchClearVisibility(newText)
                    // Update error state for regex mode
                    updateSearchBoxErrorState(historyView.hasRegexError())
                }
            }
        }
    }

    /**
     * Clears clipboard search and exits search mode.
     */
    fun clearSearch() {
        searchMode = false
        clipboardSearchBox?.apply {
            text = ""
            hint = context.getString(R.string.clipboard_search_hint_tap)
        }
        clipboardHistoryView?.setSearchFilter("")
        clipboardHistoryView?.setRegexMode(false)
        updateRegexToggleVisual(false)
        updateSearchBoxErrorState(false)
        updateSearchClearVisibility("")
    }

    /**
     * Updates the visibility of the search clear (X) button.
     * Shown when search text is non-empty, hidden otherwise.
     */
    private fun updateSearchClearVisibility(searchText: String) {
        val visible = searchText.isNotEmpty()
        clipboardSearchClear?.visibility = if (visible) View.VISIBLE else View.GONE
        regexToggle?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Resets search state and tab when showing clipboard pane.
     * Clears any previous search, exits search mode, and returns to History tab.
     */
    fun resetSearchOnShow() {
        searchMode = false
        clipboardSearchBox?.apply {
            text = ""
            hint = context.getString(R.string.clipboard_search_hint_tap)
        }
        clipboardHistoryView?.setSearchFilter("")
        clipboardHistoryView?.setRegexMode(false)
        updateRegexToggleVisual(false)
        updateSearchBoxErrorState(false)
        updateSearchClearVisibility("")

        // Reopen on History — or, while a selection exists, on its tab, so the checkboxes, count
        // and selection bar come back (it survives pane close, rotation and keyboard hide). A
        // selection whose tab has since been disabled in Settings cannot be shown and ends.
        val selectionTab = selectionHolder.tab
        if (selectionTab != null && !isTabEnabled(selectionTab)) selectionHolder.end()
        val tab = selectionHolder.tab ?: ClipboardTab.HISTORY
        currentTab = tab
        clipboardHistoryView?.setTab(tab)
        updateTabHighlighting()
        updateFilterIconTint()
    }

    /**
     * Resets search state when hiding clipboard pane.
     * Exits search mode and clears search text.
     */
    fun resetSearchOnHide() {
        // Pane close, pane switch, keyboard hide and the input restart rotation causes
        // (onFinishInputView) all land here. The SELECTION survives (it is restored when the pane
        // reopens); only a pending dialog goes, since an IME-attached window cannot outlive the
        // pane it belongs to. IME dialogs never take window focus (ImeDialogWindowPolicy), so
        // showing or tapping one does not itself reach this path.
        dismissPendingDialog()
        searchMode = false
        clipboardSearchBox?.apply {
            text = ""
            hint = context.getString(R.string.clipboard_search_hint_tap)
        }
        updateSearchClearVisibility("")
        // Also exit edit mode and tag panel when hiding clipboard pane
        exitEditMode()
        if (tagMode) hideTagPanel()
    }

    // ─── Regex toggle visual helpers ───

    /** Update regex toggle button alpha: full brightness when active, dimmed when inactive */
    private fun updateRegexToggleVisual(active: Boolean) {
        regexToggle?.alpha = if (active) 1.0f else 0.4f
    }

    /** Tint search box text red when current regex pattern is invalid, restore themed color otherwise */
    private fun updateSearchBoxErrorState(hasError: Boolean) {
        clipboardSearchBox?.setTextColor(
            if (hasError) 0xFFFF6B6B.toInt() else searchBoxDefaultTextColor
        )
    }

    // ─── Tag panel mode (highest priority — inline panel replaces entry list) ───

    /** Whether the inline tag panel is open and accepting key input */
    fun isInTagMode(): Boolean = tagMode

    /** Insert typed text into the tag panel's EditText.
     *  Uses setText+setSelection pattern — EditText.append() and Editable.replace()
     *  don't reliably work when the IME owns the view (same as GIF/emoji search). */
    fun insertToTag(text: String) {
        val et = tagEditText ?: run {
            Log.w(TAG, "insertToTag: tagEditText is NULL (tagMode=$tagMode)")
            return
        }
        val current = et.text?.toString() ?: ""
        val newText = current + text
        et.setText(newText)
        et.setSelection(newText.length)
    }

    /** Handle backspace in the tag panel's EditText.
     *  Uses setText+setSelection pattern (same as GIF/emoji search backspace). */
    fun backspaceFromTag() {
        val et = tagEditText ?: return
        val current = et.text?.toString() ?: ""
        if (current.isNotEmpty()) {
            val newText = current.dropLast(1)
            et.setText(newText)
            et.setSelection(newText.length)
        }
    }

    /**
     * Show the inline tag panel for a clipboard entry.
     * Hides the entry list and populates the tag panel container.
     * Clears search mode to prevent state overlap (tag has highest routing priority
     * anyway, but keeping state machine clean avoids confusion).
     */
    private fun showTagPanel(entry: ClipboardEntry, tab: ClipboardTab) {
        // Ensure mutual exclusion with other modes before activating tag panel
        exitEditMode()
        clearSearch()

        val container = tagPanelContent
        if (container == null) { Log.e(TAG, "showTagPanel: tagPanelContent is NULL"); return }
        val ctx = clipboardPane?.context
        if (ctx == null) { Log.e(TAG, "showTagPanel: context is NULL"); return }
        val svc = ClipboardHistoryService.get_service(ctx)
        if (svc == null) { Log.e(TAG, "showTagPanel: service is NULL"); return }

        val editText = ClipboardTagPanel.populate(
            container = container,
            context = ctx,
            service = svc,
            tab = tab,
            entry = entry,
            onTagsChanged = {
                // Refresh the entry list in background so it's current when panel closes
                clipboardHistoryView?.reloadInBackground()
            },
            onClose = { hideTagPanel() }
        )

        if (editText == null) {
            Log.e(TAG, "showTagPanel: populate() returned null for tab=$tab")
            return
        }

        // Activate tag mode — must happen before visibility swap
        tagMode = true
        updateResultSummary()
        tagEditText = editText

        // Visual feedback: show what's being tagged in the search bar
        clipboardSearchBox?.let {
            it.text = context.getString(R.string.clipboard_tags_header, entry.content.take(30))
            it.hint = ""
        }
        // Swap visibility: hide entry list, show tag panel
        contentScroll?.visibility = View.GONE
        tagPanel?.visibility = View.VISIBLE
        paginationBar?.visibility = View.GONE

        // Lock UI controls to prevent conflicting actions while tagging
        setTagModeLockUI(true)
        Log.d(TAG, "showTagPanel: active for '${entry.content.take(20)}' tab=$tab")
    }

    /**
     * Hide the inline tag panel and restore the entry list.
     */
    fun hideTagPanel() {
        tagMode = false
        updateResultSummary()
        tagEditText = null
        tagPanelContent?.removeAllViews()

        // Unlock UI controls first
        setTagModeLockUI(false)

        // Restore search bar to default state (pane is still open, so use the "tap to search" hint)
        clipboardSearchBox?.apply {
            text = ""
            hint = context.getString(R.string.clipboard_search_hint_tap)
        }
        updateSearchClearVisibility("")
        // Swap visibility: show entry list, hide tag panel
        tagPanel?.visibility = View.GONE
        contentScroll?.visibility = View.VISIBLE
        // Reload data to reflect any tag changes and restore pagination
        clipboardHistoryView?.let { it.post { it.loadDataForce() } }
    }

    // ─── Edit mode delegation (parallels search mode) ───

    /** Whether the clipboard view is currently inline-editing an entry */
    fun isInEditMode(): Boolean = clipboardHistoryView?.isEditing() ?: false

    /**
     * Dims or restores non-edit UI controls during inline edit mode.
     * Clickability is enforced by guards in each click listener; this provides
     * visual feedback that search/tabs/pagination are temporarily disabled.
     */
    private fun setEditModeLockUI(locked: Boolean) {
        val dimAlpha = 0.3f
        val normalAlpha = 1.0f
        val alpha = if (locked) dimAlpha else normalAlpha

        clipboardSearchBox?.alpha = alpha
        clipboardSearchClear?.alpha = alpha
        // Regex toggle — respect active state when unlocking
        regexToggle?.alpha = if (locked) dimAlpha
            else if (clipboardHistoryView?.isRegexMode() == true) 1.0f else 0.4f
        // Tabs — restore proper active/inactive highlighting when unlocking
        if (locked) {
            tabHistory?.alpha = dimAlpha
            tabPinned?.alpha = dimAlpha
            tabTodos?.alpha = dimAlpha
        } else {
            updateTabHighlighting()
        }
    }

    /**
     * Dims or restores non-tag UI controls when the tag panel is open.
     * Provides visual feedback and prevents conflicting actions like switching tabs.
     * Parallels setEditModeLockUI() for the tag mode state.
     */
    private fun setTagModeLockUI(locked: Boolean) {
        val dimAlpha = 0.3f

        // Hide clear/regex buttons — they're irrelevant during tagging
        clipboardSearchClear?.visibility = if (locked) View.GONE else View.VISIBLE
        regexToggle?.visibility = if (locked) View.GONE else View.VISIBLE

        // Dim tabs to indicate they are disabled
        if (locked) {
            tabHistory?.alpha = dimAlpha
            tabPinned?.alpha = dimAlpha
            tabTodos?.alpha = dimAlpha
        } else {
            // On unlock, restore proper active/inactive highlighting
            updateTabHighlighting()
        }
        // Sync clear button visibility with current search state
        updateSearchClearVisibility(clipboardSearchBox?.text?.toString() ?: "")
    }

    /** Insert typed text at cursor position in the editing entry's EditText */
    fun insertToEdit(text: String) {
        clipboardHistoryView?.insertEditText(text)
    }

    /** Handle backspace in the editing entry's EditText */
    fun backspaceFromEdit() {
        clipboardHistoryView?.backspaceEditText()
    }

    /** Exit inline edit mode, discarding unsaved changes */
    fun exitEditMode() {
        clipboardHistoryView?.cancelEdit()
    }

    /** Paste system clipboard content into the editing entry's EditText */
    fun pasteToEdit() {
        clipboardHistoryView?.pasteToEditText()
    }

    /** Cut selected text from the editing entry's EditText to system clipboard */
    fun cutFromEdit() {
        clipboardHistoryView?.cutFromEditText()
    }

    /** Select all text in the editing entry's EditText */
    fun selectAllInEdit() {
        clipboardHistoryView?.selectAllEditText()
    }

    /** Dispatch a raw key event (arrow keys, Enter) to the editing EditText */
    fun dispatchKeyToEdit(keyCode: Int) {
        clipboardHistoryView?.dispatchKeyToEditText(keyCode)
    }

    // ─── Selection mode (2026-10-07) ───

    /** Whether the clipboard list is in selection mode. */
    fun isInSelectionMode(): Boolean = clipboardHistoryView?.isSelecting() == true

    private fun canSwitchTabs(): Boolean = !isInEditMode() && !tagMode && !isInSelectionMode()

    private fun enterSelectionMode() {
        if (tagMode || isInEditMode()) return
        // Feedback from a previous deletion describes a finished batch, not the new one.
        bulkFeedback?.visibility = View.GONE
        clipboardHistoryView?.startSelection()
    }

    /** End selection mode (explicit Exit, or a tab disabled in Settings); dismisses a pending dialog. */
    fun exitSelectionMode() {
        dismissPendingDialog()
        clipboardHistoryView?.endSelection()
    }

    /**
     * Dismiss a pending selection dialog (actions list or confirmation) without touching the
     * selection: used when the pane goes away (close, switch, keyboard hide, rebuild). A dialog
     * the user already confirmed has started its transaction on the holder; it still completes.
     */
    private fun dismissPendingDialog() {
        val dialog = bulkDialog ?: return
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Dismissing pending selection dialog: pane hidden")
        bulkDialog = null
        dialog.dismiss()
    }

    /** One toggle for both directions: deselect when every match is selected, else select all. */
    private fun toggleAllMatching() {
        val view = clipboardHistoryView ?: return
        if (view.matchingCoverage() == ClipboardSelection.Coverage.ALL) view.deselectAllMatching()
        else view.selectAllMatching()
    }

    private fun tabName(tab: ClipboardTab): String = context.getString(when (tab) {
        ClipboardTab.HISTORY -> R.string.clipboard_tab_history
        ClipboardTab.PINNED -> R.string.clipboard_tab_pinned
        ClipboardTab.TODOS -> R.string.clipboard_tab_todos
    })

    private fun isTabEnabled(tab: ClipboardTab): Boolean = when (tab) {
        ClipboardTab.HISTORY -> true
        ClipboardTab.PINNED -> config.clipboard_pinned_enabled
        ClipboardTab.TODOS -> config.clipboard_todo_enabled
    }

    /** Whether a selection dialog may open now (one at a time, never during edit or tags). */
    private fun canOpenSelectionDialog(): Boolean = !tagMode && !isInEditMode() && bulkDialog == null

    /**
     * Show [dialog] over the keyboard as THE pending selection dialog. Every selection dialog
     * goes through here so pane teardown can dismiss it and only one is ever open.
     */
    private fun showSelectionDialog(dialog: android.app.AlertDialog, anchor: View) {
        bulkDialog = dialog
        dialog.setOnDismissListener { if (bulkDialog === dialog) bulkDialog = null }
        Utils.show_dialog_on_ime(dialog, anchor.windowToken)
    }

    private fun dialogBuilder() =
        android.app.AlertDialog.Builder(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog))

    /**
     * A confirmation whose positive button runs [onConfirm] exactly once. The action starts its
     * transaction on the selection holder before the dialog finishes dismissing, so nothing
     * that tears the pane down afterwards can cancel it.
     */
    private fun confirm(anchor: View, title: CharSequence, message: CharSequence, actionLabel: Int, onConfirm: () -> Unit) {
        var confirmed = false
        val dialog = dialogBuilder()
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(actionLabel) { _, _ ->
                if (!confirmed) {
                    confirmed = true
                    onConfirm()
                }
            }.create()
        showSelectionDialog(dialog, anchor)
    }

    /** Polite live-region feedback in the result row (TalkBack announces it). */
    private fun showBulkFeedback(text: CharSequence) {
        bulkFeedback?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun plural(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)

    /** Join the non-empty sentences of a result message. */
    private fun sentences(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Confirm and delete the selection. The dialog states the exact frozen scope (count, tab,
     * combined size); confirming deletes those rows by identity in one transaction, skipping
     * any that changed after this dialog opened, and reports how many were deleted.
     */
    private fun confirmDeleteSelected(anchor: View) {
        if (!canOpenSelectionDialog()) return
        val historyView = clipboardHistoryView ?: return
        val snapshot = historyView.selectionSnapshot() ?: return
        val total = snapshot.entries.size
        confirm(anchor,
            context.resources.getQuantityString(R.plurals.clipboard_delete_selected_title, total, total),
            context.getString(R.string.clipboard_delete_selected_message, tabName(snapshot.tab),
                Formatter.formatShortFileSize(context, snapshot.totalBytes)),
            R.string.clipboard_delete_confirm_action) {
            historyView.deleteSnapshot(snapshot) { result ->
                showBulkFeedback(result.fold(
                    { context.resources.getQuantityString(R.plurals.clipboard_delete_selected_result, total, it, total) },
                    { context.getString(R.string.clipboard_delete_error) }))
            }
        }
    }

    /**
     * The selection's other bulk actions (2026-10-07): Add to Pinned, Add to Todos, Merge and
     * Clean, as a list dialog behind one 48dp "more" button so the bar still fits beside the
     * results in a compact landscape pane. Only actions that make sense for the tab are listed.
     */
    private fun showSelectionActions(anchor: View) {
        if (!canOpenSelectionDialog()) return
        val historyView = clipboardHistoryView ?: return
        val snapshot = historyView.selectionSnapshot() ?: return
        val actions = ClipboardBulkPlans.actionsFor(snapshot.tab, config.clipboard_pinned_enabled, config.clipboard_todo_enabled)
        val labels = actions.map { action ->
            context.getString(when (action) {
                ClipboardSelectionAction.ADD_TO_PINNED -> R.string.clipboard_action_add_to_pinned
                ClipboardSelectionAction.ADD_TO_TODOS -> R.string.clipboard_action_add_to_todos
                ClipboardSelectionAction.MERGE -> R.string.clipboard_action_merge
                ClipboardSelectionAction.CLEAN -> R.string.clipboard_action_clean
            })
        }.toTypedArray<CharSequence>()
        val dialog = dialogBuilder()
            .setTitle(plural(R.plurals.clipboard_selection_count, snapshot.entries.size))
            .setItems(labels) { _, which ->
                // The list dismisses itself after this returns; free the slot for what follows.
                bulkDialog = null
                when (actions[which]) {
                    ClipboardSelectionAction.ADD_TO_PINNED -> copySelection(ClipboardTab.PINNED, snapshot)
                    ClipboardSelectionAction.ADD_TO_TODOS -> copySelection(ClipboardTab.TODOS, snapshot)
                    ClipboardSelectionAction.MERGE -> confirmMerge(anchor, snapshot)
                    ClipboardSelectionAction.CLEAN -> confirmClean(anchor, snapshot)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showSelectionDialog(dialog, anchor)
    }

    /**
     * Add to Pinned / Add to Todos. Copying is non-destructive (COPY semantics; duplicates are
     * reported, not created), so the list choice itself is the confirmation. The target tab icon
     * pulses once when something was added, three times when everything was already there.
     */
    private fun copySelection(target: ClipboardTab, snapshot: ClipboardDeleteSnapshot) {
        val historyView = clipboardHistoryView ?: return
        historyView.copySelectionTo(target, snapshot) { result ->
            result.onSuccess { counts ->
                val added = if (target == ClipboardTab.PINNED) R.plurals.clipboard_added_to_pinned_result
                    else R.plurals.clipboard_added_to_todos_result
                showBulkFeedback(sentences(
                    plural(added, counts.added),
                    counts.alreadyPresent.takeIf { it > 0 }?.let { plural(R.plurals.clipboard_bulk_already_present, it) },
                    counts.failed.takeIf { it > 0 }?.let { plural(R.plurals.clipboard_bulk_failed, it) }))
                val icon = if (target == ClipboardTab.PINNED) tabPinned else tabTodos
                // Posted: the icon is hidden until the finished selection's chrome refreshes.
                icon?.post { pulseTabIcon(icon, if (counts.added > 0) 1 else 3) }
            }.onFailure { showBulkFeedback(context.getString(R.string.clipboard_bulk_action_error)) }
        }
    }

    /**
     * Merge: confirm with the count, size and a preview, then store one new History clipping.
     * Refused up front (selection kept) with fewer than two text clippings or over the size limit.
     */
    private fun confirmMerge(anchor: View, snapshot: ClipboardDeleteSnapshot) {
        val historyView = clipboardHistoryView ?: return
        val limitKb = config.clipboard_max_item_size_kb
        val mediaNote = { skipped: Int -> skipped.takeIf { it > 0 }?.let { plural(R.plurals.clipboard_bulk_media_skipped, it) } }
        when (val decision = ClipboardBulkPlans.planMerge(snapshot.entries, if (limitKb > 0) limitKb * 1024L else null)) {
            is ClipboardBulkPlans.MergeDecision.TooFewText ->
                showBulkFeedback(sentences(context.getString(R.string.clipboard_merge_too_few), mediaNote(decision.skippedMedia)))
            is ClipboardBulkPlans.MergeDecision.TooLarge ->
                showBulkFeedback(context.getString(R.string.clipboard_merge_too_large,
                    Formatter.formatShortFileSize(context, decision.bytes),
                    Formatter.formatShortFileSize(context, decision.limitBytes)))
            is ClipboardBulkPlans.MergeDecision.Ready -> {
                val plan = decision.plan
                if (!canOpenSelectionDialog()) return
                val message = sentences(
                    context.getString(R.string.clipboard_merge_message, Formatter.formatShortFileSize(context, plan.bytes)),
                    mediaNote(plan.skippedMedia),
                    if (plan.isPrivate) context.getString(R.string.clipboard_merge_private_note) else null,
                ) + "\n\n" + context.getString(R.string.clipboard_bulk_preview, ClipboardBulkPlans.preview(plan.text))
                confirm(anchor, plural(R.plurals.clipboard_merge_title, plan.sources), message, R.string.clipboard_merge_action) {
                    historyView.mergeSelection(plan) { result ->
                        showBulkFeedback(result.fold(
                            { sentences(plural(R.plurals.clipboard_merge_result, plan.sources), mediaNote(plan.skippedMedia)) },
                            { context.getString(R.string.clipboard_bulk_action_error) }))
                    }
                }
            }
        }
    }

    /**
     * Clean: confirm with the rule summary, the counts and a preview of the first change, then
     * edit the rows in place. With nothing to change it only reports so (selection kept).
     */
    private fun confirmClean(anchor: View, snapshot: ClipboardDeleteSnapshot) {
        val historyView = clipboardHistoryView ?: return
        val plan = ClipboardBulkPlans.planClean(snapshot.entries)
        val counts = { unchanged: Int, media: Int -> arrayOf(
            unchanged.takeIf { it > 0 }?.let { plural(R.plurals.clipboard_bulk_unchanged, it) },
            media.takeIf { it > 0 }?.let { plural(R.plurals.clipboard_bulk_media_skipped, it) }) }
        if (plan.edits.isEmpty()) {
            showBulkFeedback(sentences(context.getString(R.string.clipboard_clean_nothing), *counts(plan.unchanged, plan.skippedMedia)))
            return
        }
        if (!canOpenSelectionDialog()) return
        val message = sentences(context.getString(R.string.clipboard_clean_message, tabName(snapshot.tab)),
            *counts(plan.unchanged, plan.skippedMedia)) +
            "\n\n" + context.getString(R.string.clipboard_bulk_preview, ClipboardBulkPlans.preview(plan.edits.first().second))
        confirm(anchor, plural(R.plurals.clipboard_clean_title, plan.edits.size), message, R.string.clipboard_clean_action) {
            historyView.cleanSelection(snapshot.tab, plan) { result ->
                showBulkFeedback(result.fold({ done ->
                    sentences(plural(R.plurals.clipboard_clean_result, done.cleaned),
                        *counts(done.unchanged, done.skippedMedia),
                        done.failed.takeIf { it > 0 }?.let { plural(R.plurals.clipboard_bulk_failed, it) })
                }, { context.getString(R.string.clipboard_bulk_action_error) }))
            }
        }
    }

    private fun updateResultSummary() {
        val view = clipboardHistoryView ?: return
        val (count, bytes) = view.resultSummary()
        val ready = view.isResultsReady()
        resultSummary?.text = if (ready)
            context.getString(R.string.clipboard_results_summary, count, Formatter.formatShortFileSize(context, bytes))
        else context.getString(R.string.clipboard_results_loading)
        val selecting = view.isSelecting()
        selectButton?.apply {
            visibility = if (selecting) View.GONE else View.VISIBLE
            isEnabled = ready && !tagMode && !isInEditMode()
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
        selectionBar?.visibility = if (selecting) View.VISIBLE else View.GONE
        selectionCount?.visibility = if (selecting) View.VISIBLE else View.GONE
        updateTabIcons()
        if (!selecting) return

        val selected = view.selectedCount()
        selectionCount?.text = context.resources.getQuantityString(
            R.plurals.clipboard_selection_count, selected, selected)
        val coverage = view.matchingCoverage()
        selectMatchingButton?.apply {
            setImageResource(when (coverage) {
                ClipboardSelection.Coverage.ALL -> R.drawable.ic_check_box_checked
                ClipboardSelection.Coverage.PARTIAL -> R.drawable.ic_check_box_partial
                ClipboardSelection.Coverage.NONE -> R.drawable.ic_check_box_outline
            })
            describe(this, if (coverage == ClipboardSelection.Coverage.ALL)
                R.string.clipboard_deselect_all_matching else R.string.clipboard_select_all_matching)
            setEnabledVisual(this, ready && count > 0)
        }
        clearSelectionButton?.let { setEnabledVisual(it, ready && selected > 0) }
        val actionable = view.selectionSnapshot() != null && !tagMode
        deleteSelectedButton?.let { setEnabledVisual(it, actionable) }
        selectionActionsButton?.let { setEnabledVisual(it, actionable) }
        exitSelectionButton?.let { setEnabledVisual(it, ready) }
    }

    /** Content description plus (API 26+) a long-press tooltip for an icon-only action. */
    private fun describe(button: View, label: Int) {
        val text = context.getString(label)
        button.contentDescription = text
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) button.tooltipText = text
    }

    private fun setEnabledVisual(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /** Shows size, privacy, date and tab-specific status/tag filters. */
    // Framework Switch is required by Theme.DeviceDefault_Dialog; SwitchCompat
    // lacks the AppCompat switchStyle and crashes at first measure in this context.
    @android.annotation.SuppressLint("UseSwitchCompatOrMaterialCode")
    fun showFilterDialog(anchorView: View) {
        val themedContext = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog)
        val historyView = clipboardHistoryView ?: return
        val tab = currentTab

        val dialogView = LayoutInflater.from(themedContext).inflate(
            R.layout.clipboard_filter_dialog, null
        )

        val sizeMin = dialogView.findViewById<Spinner>(R.id.clipboard_size_min)
        val sizeMax = dialogView.findViewById<Spinner>(R.id.clipboard_size_max)
        val sizeError = dialogView.findViewById<View>(R.id.clipboard_size_error)
        val sizePresets = listOf(0L, 1_000L, 10_000L, 100_000L,
            1_000_000L, 10_000_000L, 100_000_000L)
        fun sizeLabels(first: Int) = sizePresets.mapIndexed { index, bytes ->
            if (index == 0) context.getString(first) else Formatter.formatShortFileSize(context, bytes)
        }
        fun configureSizeSpinner(spinner: Spinner, labels: List<String>) {
            spinner.adapter = ArrayAdapter(themedContext, android.R.layout.simple_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        configureSizeSpinner(sizeMin, sizeLabels(R.string.clipboard_filter_size_any))
        configureSizeSpinner(sizeMax, sizeLabels(R.string.clipboard_filter_size_unlimited))
        val (minBytes, maxBytes) = historyView.getSizeFilter()
        sizeMin.setSelection(sizePresets.indexOf(minBytes).coerceAtLeast(0))
        sizeMax.setSelection(sizePresets.indexOf(maxBytes ?: 0).coerceAtLeast(0))
        fun maximumBytes(): Long? = sizePresets[sizeMax.selectedItemPosition].takeIf { it > 0 }

        // ─── Private-only section (#156 — all tabs) ───
        val privateOnlySwitch = dialogView.findViewById<Switch>(R.id.filter_private_only)
        privateOnlySwitch.isChecked = historyView.isPrivateOnlyFilter()

        // ─── Date section (existing logic, unchanged) ───
        val enabledSwitch = dialogView.findViewById<Switch>(R.id.date_filter_enabled)
        val beforeRadio = dialogView.findViewById<RadioButton>(R.id.date_filter_before)
        val afterRadio = dialogView.findViewById<RadioButton>(R.id.date_filter_after)
        val datePicker = dialogView.findViewById<DatePicker>(R.id.date_picker)
        val modeContainer = dialogView.findViewById<View>(R.id.date_filter_mode_container)
        val pickerContainer = dialogView.findViewById<View>(R.id.date_picker_container)

        val isFilterEnabled = historyView.isDateFilterEnabled()
        val isBeforeMode = historyView.isDateFilterBefore()

        enabledSwitch.isChecked = isFilterEnabled
        if (isBeforeMode) beforeRadio.isChecked = true else afterRadio.isChecked = true
        modeContainer.visibility = if (isFilterEnabled) View.VISIBLE else View.GONE
        pickerContainer.visibility = if (isFilterEnabled) View.VISIBLE else View.GONE

        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            modeContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            pickerContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val cal = Calendar.getInstance()
        if (historyView.getDateFilterTimestamp() > 0) {
            cal.timeInMillis = historyView.getDateFilterTimestamp()
        }
        datePicker.updateDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))

        // ─── Status section (TODOS tab only) ───
        val statusSection = dialogView.findViewById<View>(R.id.filter_status_section)
        val statusDivider = dialogView.findViewById<View>(R.id.filter_divider_status)
        val cbActive = dialogView.findViewById<CheckBox>(R.id.filter_status_active)
        val cbPlanned = dialogView.findViewById<CheckBox>(R.id.filter_status_planned)
        val cbCompleted = dialogView.findViewById<CheckBox>(R.id.filter_status_completed)
        val statusHint = dialogView.findViewById<TextView>(R.id.filter_status_hint)

        val showStatus = tab == ClipboardTab.TODOS
        statusSection.visibility = if (showStatus) View.VISIBLE else View.GONE
        statusDivider.visibility = if (showStatus) View.VISIBLE else View.GONE

        if (showStatus) {
            val (activeOn, plannedOn, completedOn) = historyView.getStatusFilter()
            cbActive.isChecked = activeOn
            cbPlanned.isChecked = plannedOn
            cbCompleted.isChecked = completedOn
        }

        // ─── Tags section (PINNED + TODOS tabs) ───
        val tagsSection = dialogView.findViewById<View>(R.id.filter_tags_section)
        val tagsDivider = dialogView.findViewById<View>(R.id.filter_divider_tags)
        val tagContainer = dialogView.findViewById<LinearLayout>(R.id.filter_tags_container)
        val matchAllToggle = dialogView.findViewById<Switch>(R.id.filter_tags_match_all)
        val matchLabel = dialogView.findViewById<TextView>(R.id.filter_tags_match_label)
        val emptyHint = dialogView.findViewById<TextView>(R.id.filter_tags_empty_hint)

        val showTags = tab != ClipboardTab.HISTORY
        tagsSection.visibility = if (showTags) View.VISIBLE else View.GONE
        tagsDivider.visibility = if (showTags) View.VISIBLE else View.GONE

        // Collect tag checkboxes for reading on Apply
        val tagCheckboxes = mutableListOf<CheckBox>()

        if (showTags) {
            val svc = ClipboardHistoryService.get_service(context)
            val allTags = when (tab) {
                ClipboardTab.PINNED -> svc?.getAllPinnedTags() ?: emptySet()
                ClipboardTab.TODOS -> svc?.getAllTodoTags() ?: emptySet()
                else -> emptySet()
            }
            val selectedTags = historyView.getTagFilter()
            matchAllToggle.isChecked = historyView.isTagFilterMatchAll()

            // Update label to reflect current match mode
            matchLabel.text = tagMatchLabel(matchAllToggle.isChecked)
            matchAllToggle.setOnCheckedChangeListener { _, isChecked ->
                matchLabel.text = tagMatchLabel(isChecked)
            }

            if (allTags.isEmpty()) {
                // No tags exist yet — show hint, hide match toggle
                emptyHint.visibility = View.VISIBLE
                matchAllToggle.visibility = View.GONE
                matchLabel.visibility = View.GONE
            } else {
                emptyHint.visibility = View.GONE
                for (tag in allTags.sorted()) {
                    val cb = CheckBox(themedContext).apply {
                        text = tag
                        isChecked = tag in selectedTags
                        setTextColor(resolveThemeColor(themedContext,
                            R.attr.colorLabel, Color.WHITE))
                    }
                    tagContainer.addView(cb)
                    tagCheckboxes.add(cb)
                }
            }
        }

        // ─── Build dialog ───
        val dialog = android.app.AlertDialog.Builder(themedContext)
            .setTitle(R.string.clipboard_filter_dialog_title)
            .setView(dialogView)
            .create()

        val applyButton = dialogView.findViewById<Button>(R.id.date_filter_apply)

        // Both range and todo-status validation drive the same Apply guard.
        fun validateFilters() {
            val statusValid = !showStatus || cbActive.isChecked || cbPlanned.isChecked || cbCompleted.isChecked
            val maximum = maximumBytes()
            val sizeValid = maximum == null || maximum >= sizePresets[sizeMin.selectedItemPosition]
            applyButton.isEnabled = statusValid && sizeValid
            statusHint.visibility = if (statusValid) View.GONE else View.VISIBLE
            sizeError.visibility = if (sizeValid) View.GONE else View.VISIBLE
        }
        val sizeListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = validateFilters()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        sizeMin.onItemSelectedListener = sizeListener
        sizeMax.onItemSelectedListener = sizeListener
        if (showStatus) {
            val watcher = CompoundButton.OnCheckedChangeListener { _, _ -> validateFilters() }
            cbActive.setOnCheckedChangeListener(watcher)
            cbPlanned.setOnCheckedChangeListener(watcher)
            cbCompleted.setOnCheckedChangeListener(watcher)
        }
        validateFilters()

        // ─── Clear button — clears ALL filters ───
        dialogView.findViewById<View>(R.id.date_filter_clear).setOnClickListener {
            historyView.clearAllFilters()
            updateFilterIconTint()
            dialog.dismiss()
        }

        // ─── Cancel ───
        dialogView.findViewById<View>(R.id.date_filter_cancel).setOnClickListener {
            dialog.dismiss()
        }

        // ─── Apply button — reads all sections ───
        applyButton.setOnClickListener {
            historyView.setSizeFilter(sizePresets[sizeMin.selectedItemPosition], maximumBytes())
            // Private-only filter (#156 — all tabs)
            historyView.setPrivateOnlyFilter(privateOnlySwitch.isChecked)

            // Date filter
            val enabled = enabledSwitch.isChecked
            val isBefore = beforeRadio.isChecked
            if (enabled) {
                val selectedCal = Calendar.getInstance().apply {
                    set(datePicker.year, datePicker.month, datePicker.dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                historyView.setDateFilter(selectedCal.timeInMillis, isBefore)
            } else {
                historyView.clearDateFilter()
            }

            // Status filter (TODOS only)
            if (showStatus) {
                historyView.setStatusFilter(
                    cbActive.isChecked, cbPlanned.isChecked, cbCompleted.isChecked
                )
            }

            // Tag filter (PINNED/TODOS)
            if (showTags) {
                val selected = tagCheckboxes
                    .filter { it.isChecked }
                    .map { it.text.toString() }
                    .toSet()
                historyView.setTagFilter(selected, matchAllToggle.isChecked)
            }

            updateFilterIconTint()
            dialog.dismiss()
        }

        Utils.show_dialog_on_ime(dialog, anchorView.windowToken)
    }

    /**
     * Resolves a theme attribute to a color value with a fallback default.
     *
     * #130: For runtime themes (custom_/decorative_), `?attr/color*` resolves
     * against the hardcoded base style, NOT the active theme. Prefer the
     * runtime Theme object's colors in that case so programmatic color reads
     * (tag panel checkboxes, filter-icon tint, search-box default) match the
     * keyboard.
     */
    private fun resolveThemeColor(ctx: Context, attr: Int, defaultColor: Int): Int {
        val tv = TypedValue()
        val xmlResolved = if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else null
        return ClipboardPaneThemePolicy.effectiveColor(
            config.isRuntimeTheme(), runtimeThemeColor(attr) ?: 0, xmlResolved, defaultColor
        )
    }

    /**
     * #130: Maps a theme color attribute to the active runtime Theme's color.
     * Returns null for non-runtime themes (so the caller falls back to normal
     * `?attr/` resolution) or when the theme lacks that color (value 0).
     */
    private fun runtimeThemeColor(attr: Int): Int? {
        if (!config.isRuntimeTheme()) return null
        val theme = runtimeTheme
            ?: ThemeProvider.getInstance(context).getTheme(config.themeName).also { runtimeTheme = it }
        val c = when (attr) {
            R.attr.colorKeyboard -> theme.colorKeyboardBackground
            R.attr.colorKey -> theme.colorKey
            R.attr.colorLabel -> theme.labelColor
            R.attr.colorSubLabel -> theme.subLabelColor
            R.attr.colorLabelActivated -> theme.activatedColor
            else -> 0
        }
        return c.takeIf { it != 0 }
    }

    /**
     * #130: Applies the active runtime theme's colors to the inflated clipboard
     * pane chrome. No-op for built-in (XML-style) themes, which resolve
     * `?attr/color*` correctly on their own. Mirrors how Keyboard2View applies
     * runtime-theme colors programmatically. Caches the Theme in [runtimeTheme]
     * so subsequent [resolveThemeColor] calls (tag panel, filter icon) reuse it
     * without re-parsing (ThemeProvider.getTheme is not cached).
     */
    private fun applyRuntimeThemeColors() {
        if (!config.isRuntimeTheme()) {
            runtimeTheme = null
            appliedThemeSignature =
                ClipboardPaneThemePolicy.ThemeSignature(config.themeName, config.theme, null)
            return
        }
        val pane = clipboardPane ?: return
        val theme = ThemeProvider.getInstance(context).getTheme(config.themeName)
            .also { runtimeTheme = it }
        val bg = theme.colorKeyboardBackground
        val key = theme.colorKey
        val label = theme.labelColor
        val sub = theme.subLabelColor
        val colors = ClipboardPaneThemePolicy.RuntimeColors(
            bg, key, label, sub, theme.activatedColor
        )
        appliedThemeSignature =
            ClipboardPaneThemePolicy.ThemeSignature(config.themeName, config.theme, colors)
        // #130: the entry rows resolve their colors themselves (adapter-inflated under the
        // base XML style) — hand them the runtime colors so rows match the pane chrome.
        pane.findViewById<ClipboardHistoryView?>(R.id.clipboard_history_view)
            ?.runtimeColors = colors

        if (bg != 0) {
            pane.setBackgroundColor(bg)
            pane.findViewById<View?>(R.id.clipboard_tag_panel)?.setBackgroundColor(bg)
            pane.findViewById<View?>(R.id.clipboard_selection_bar)?.setBackgroundColor(bg)
        }
        if (key != 0) {
            pane.findViewById<View?>(R.id.clipboard_search_bar)?.setBackgroundColor(key)
            pane.findViewById<View?>(R.id.clipboard_pagination_bar)?.setBackgroundColor(key)
            pane.findViewById<View?>(R.id.clipboard_select)?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(key)
        }
        if (label != 0) {
            intArrayOf(
                R.id.tab_history, R.id.tab_pinned, R.id.tab_todos,
                R.id.clipboard_search_clear, R.id.clipboard_date_filter,
                R.id.clipboard_close_button, R.id.clipboard_select_matching,
                R.id.clipboard_selection_clear, R.id.clipboard_delete_selected,
                R.id.clipboard_selection_actions, R.id.clipboard_selection_exit
            ).forEach { id ->
                (pane.findViewById<View?>(id) as? ImageView)
                    ?.setColorFilter(label, PorterDuff.Mode.SRC_IN)
            }
            (pane.findViewById<TextView?>(R.id.clipboard_search))?.setTextColor(label)
            (pane.findViewById<TextView?>(R.id.clipboard_select))?.setTextColor(label)
            (pane.findViewById<TextView?>(R.id.clipboard_selection_count))?.setTextColor(label)
            (pane.findViewById<TextView?>(R.id.clipboard_result_summary))?.setTextColor(theme.subLabelColor)
            (pane.findViewById<TextView?>(R.id.clipboard_bulk_feedback))?.setTextColor(label)
            (pane.findViewById<TextView?>(R.id.clipboard_regex_toggle))?.setTextColor(label)
            (pane.findViewById<TextView?>(R.id.clipboard_page_prev))?.setTextColor(label)
            (pane.findViewById<TextView?>(R.id.clipboard_page_next))?.setTextColor(label)
        }
        if (sub != 0) {
            (pane.findViewById<TextView?>(R.id.clipboard_search))?.setHintTextColor(sub)
            (pane.findViewById<TextView?>(R.id.clipboard_page_info))?.setTextColor(sub)
        }
    }

    /** Label beside the filter dialog's tag match-mode switch ("Match: All" / "Match: Any"). */
    private fun tagMatchLabel(matchAll: Boolean): String = context.getString(
        if (matchAll) R.string.clipboard_filter_match_all else R.string.clipboard_filter_match_any
    )

    /**
     * Updates the filter button icon tint based on active filter state.
     * Tinted accent when filters active, normal label color otherwise.
     */
    private fun updateFilterIconTint() {
        val hasFilters = clipboardHistoryView?.hasActiveFilters() ?: false
        val ctx = clipboardPane?.context ?: return
        val activeColor = resolveThemeColor(ctx, R.attr.colorLabelActivated, 0xFF3399FF.toInt())
        val normalColor = resolveThemeColor(ctx, R.attr.colorLabel, Color.WHITE)
        filterButton?.setColorFilter(
            if (hasFilters) activeColor else normalColor,
            PorterDuff.Mode.SRC_IN
        )
    }

    /**
     * Updates configuration and re-applies tab visibility.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        config = newConfig
        // #130: a cached pane was inflated + painted under the previous theme signature.
        // If the theme identity — or, for runtime themes, its color values — changed,
        // drop the pane so the next open re-inflates under the new theme. Without this
        // the pane keeps the old colors until the keyboard process restarts.
        if (clipboardPane != null &&
            ClipboardPaneThemePolicy.needsRebuild(appliedThemeSignature, currentThemeSignature())
        ) {
            invalidatePane()
        }
        applyTabVisibility()
    }

    /**
     * #130: The current config's theme signature. For runtime themes the colors are read
     * fresh from [ThemeProvider] (not the [runtimeTheme] cache) so edits to the active
     * custom theme's colors are detected even though the theme name is unchanged.
     */
    private fun currentThemeSignature(): ClipboardPaneThemePolicy.ThemeSignature {
        val colors = if (config.isRuntimeTheme()) {
            val t = ThemeProvider.getInstance(context).getTheme(config.themeName)
            ClipboardPaneThemePolicy.RuntimeColors(
                t.colorKeyboardBackground, t.colorKey, t.labelColor, t.subLabelColor, t.activatedColor
            )
        } else null
        return ClipboardPaneThemePolicy.ThemeSignature(config.themeName, config.theme, colors)
    }

    /**
     * #130: Drops the cached pane and everything that references its child views, so the
     * next [getClipboardPane] re-inflates under the current theme. Unlike [cleanup] this
     * preserves [onCloseCallback] (wired once by KeyboardReceiver at service init) and
     * [currentTab] (the user's tab choice survives a theme change).
     */
    private fun invalidatePane() {
        exitEditMode()
        hideTagPanelSilent()
        // The pane is rebuilt under the new theme; the selection (service-scoped) is kept.
        dismissPendingDialog()
        selectButton = null
        resultSummary = null
        bulkFeedback = null
        selectionBar = null
        selectionCount = null
        selectMatchingButton = null
        clearSelectionButton = null
        deleteSelectedButton = null
        selectionActionsButton = null
        exitSelectionButton = null
        clipboardHistoryView?.onResultsChanged = null
        clipboardHistoryView?.onItemAddedToTab = null
        clipboardPane = null
        clipboardSearchBox = null
        clipboardSearchClear = null
        regexToggle = null
        clipboardHistoryView = null
        filterButton = null
        tabHistory = null
        tabPinned = null
        tabTodos = null
        paginationBar = null
        pagePrev = null
        pageInfo = null
        pageNext = null
        contentScroll = null
        tagPanel = null
        tagPanelContent = null
        searchMode = false
        runtimeTheme = null
        appliedThemeSignature = null
    }

    /**
     * Shows/hides pinned and todo tab buttons based on their individual config toggles.
     * Forces back to HISTORY tab if the current tab was just disabled.
     */
    private fun applyTabVisibility() {
        updateTabIcons()

        // Force back to HISTORY if sitting on a disabled tab
        if (currentTab == ClipboardTab.PINNED && !config.clipboard_pinned_enabled) {
            switchToTab(ClipboardTab.HISTORY)
        }
        if (currentTab == ClipboardTab.TODOS && !config.clipboard_todo_enabled) {
            switchToTab(ClipboardTab.HISTORY)
        }
    }

    /**
     * Tab icons honour the config toggles; in selection mode only the current tab's icon
     * stays (the selection is scoped to that tab), freeing width for the selection actions.
     */
    private fun updateTabIcons() {
        val selecting = isInSelectionMode()
        fun visible(tab: ClipboardTab, enabled: Boolean) =
            if (enabled && (!selecting || currentTab == tab)) View.VISIBLE else View.GONE
        tabHistory?.visibility = visible(ClipboardTab.HISTORY, true)
        tabPinned?.visibility = visible(ClipboardTab.PINNED, config.clipboard_pinned_enabled)
        tabTodos?.visibility = visible(ClipboardTab.TODOS, config.clipboard_todo_enabled)
    }

    /**
     * Cleans up resources.
     * Should be called during keyboard shutdown.
     */
    fun cleanup() {
        exitEditMode()
        hideTagPanelSilent()
        clipboardPane = null
        clipboardSearchBox = null
        clipboardSearchClear = null
        regexToggle = null
        // Theme changes call this too: views go, the service-scoped selection stays.
        dismissPendingDialog()
        selectButton = null
        resultSummary = null
        bulkFeedback = null
        selectionBar = null
        selectionCount = null
        selectMatchingButton = null
        clearSelectionButton = null
        deleteSelectedButton = null
        selectionActionsButton = null
        exitSelectionButton = null
        clipboardHistoryView?.onResultsChanged = null
        clipboardHistoryView?.onItemAddedToTab = null
        clipboardHistoryView = null
        filterButton = null
        tabHistory = null
        tabPinned = null
        tabTodos = null
        paginationBar = null
        pagePrev = null
        pageInfo = null
        pageNext = null
        contentScroll = null
        tagPanel = null
        tagPanelContent = null
        onCloseCallback = null
        searchMode = false
        tagMode = false
        updateResultSummary()
        tagEditText = null
        currentTab = ClipboardTab.HISTORY
        runtimeTheme = null
        appliedThemeSignature = null
    }

    /** Reset tag state without triggering data reload (used during cleanup) */
    private fun hideTagPanelSilent() {
        tagMode = false
        updateResultSummary()
        tagEditText = null
    }

    /**
     * Gets a debug string showing current state.
     * Useful for logging and troubleshooting.
     *
     * @return Human-readable state description
     */
    fun getDebugState(): String {
        return "ClipboardManager{clipboardPane=${if (clipboardPane != null) "initialized" else "null"}, searchMode=$searchMode}"
    }

    companion object {
        private const val TAG = "ClipboardManager"

        /** Alpha of a disabled pane action (matches the edit-lock dimming family). */
        private const val DISABLED_ALPHA = 0.4f
    }
}
