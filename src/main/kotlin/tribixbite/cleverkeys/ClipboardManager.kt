package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar

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
    // Clipboard views
    private var clipboardPane: ViewGroup? = null
    private var clipboardSearchBox: TextView? = null
    private var clipboardSearchClear: ImageButton? = null
    private var regexToggle: TextView? = null
    private var clipboardHistoryView: ClipboardHistoryView? = null

    // Tab buttons (ImageViews with vector drawable icons, tinted by colorLabel)
    private var tabHistory: ImageView? = null
    private var tabPinned: ImageView? = null
    private var tabTodos: ImageView? = null

    // Pagination views
    private var paginationBar: View? = null
    private var pagePrev: TextView? = null
    private var pageInfo: TextView? = null
    private var pageNext: TextView? = null

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

            // Get search box and history view references
            clipboardSearchBox = clipboardPane?.findViewById(R.id.clipboard_search)
            // Save the themed text color before any setTextColor() calls overwrite it
            searchBoxDefaultTextColor = clipboardSearchBox?.currentTextColor ?: 0
            clipboardHistoryView = clipboardPane?.findViewById(R.id.clipboard_history_view)

            // Set up search box click listener
            clipboardSearchBox?.setOnClickListener {
                // Block search activation during edit mode — edit takes priority
                if (isInEditMode()) return@setOnClickListener
                searchMode = true
                clipboardSearchBox?.hint = "Type on keyboard below..."
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

            // Set up date filter icon
            clipboardPane?.findViewById<View>(R.id.clipboard_date_filter)?.setOnClickListener { v ->
                if (!isInEditMode()) showDateFilterDialog(v)
            }

            // Set up close button
            clipboardPane?.findViewById<ImageButton>(R.id.clipboard_close_button)?.setOnClickListener {
                onCloseCallback?.invoke()
            }

            // Set up tab buttons (ImageViews with vector icons)
            tabHistory = clipboardPane?.findViewById<ImageView>(R.id.tab_history)
            tabPinned = clipboardPane?.findViewById<ImageView>(R.id.tab_pinned)
            tabTodos = clipboardPane?.findViewById<ImageView>(R.id.tab_todos)

            tabHistory?.setOnClickListener { if (!isInEditMode() && !tagMode) switchToTab(ClipboardTab.HISTORY) }
            tabPinned?.setOnClickListener { if (!isInEditMode() && !tagMode) switchToTab(ClipboardTab.PINNED) }
            tabTodos?.setOnClickListener { if (!isInEditMode() && !tagMode) switchToTab(ClipboardTab.TODOS) }

            // Apply tab visibility based on config toggles
            applyTabVisibility()

            // Set initial tab highlighting
            updateTabHighlighting()

            // Set up pagination controls
            paginationBar = clipboardPane?.findViewById(R.id.clipboard_pagination_bar)
            pagePrev = clipboardPane?.findViewById(R.id.clipboard_page_prev)
            pageInfo = clipboardPane?.findViewById(R.id.clipboard_page_info)
            pageNext = clipboardPane?.findViewById(R.id.clipboard_page_next)

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
            }
            clipboardHistoryView?.onEditModeExited = {
                setEditModeLockUI(false)
            }

            // Tag panel: ClipboardHistoryView requests tag panel via callback
            clipboardHistoryView?.onTagPanelRequested = { entry, tab ->
                // TODO: remove diagnostic toast after tag panel input is verified working
                Toast.makeText(context, "Tag callback: tab=$tab", Toast.LENGTH_SHORT).show()
                showTagPanel(entry, tab)
            }

            // Listen for pagination state changes
            clipboardHistoryView?.setOnPaginationChangeListener { needsPagination, currentPage, totalPages ->
                paginationBar?.visibility = if (needsPagination) View.VISIBLE else View.GONE
                pageInfo?.text = "$currentPage / $totalPages"
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

        // Cancel any in-progress edit or tag panel when switching tabs
        exitEditMode()
        if (tagMode) hideTagPanel()
        currentTab = tab
        clipboardHistoryView?.setTab(tab)
        updateTabHighlighting()
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
            hint = "Tap to search..."
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
            hint = "Tap to search..."
        }
        clipboardHistoryView?.setSearchFilter("")
        clipboardHistoryView?.setRegexMode(false)
        updateRegexToggleVisual(false)
        updateSearchBoxErrorState(false)
        updateSearchClearVisibility("")

        // Reset to History tab when showing pane
        currentTab = ClipboardTab.HISTORY
        clipboardHistoryView?.setTab(ClipboardTab.HISTORY)
        updateTabHighlighting()
    }

    /**
     * Resets search state when hiding clipboard pane.
     * Exits search mode and clears search text.
     */
    fun resetSearchOnHide() {
        searchMode = false
        clipboardSearchBox?.apply {
            text = ""
            hint = "Tap to search..."
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
        // TODO: remove diagnostic log after tag panel input is verified working
        Log.d(TAG, "insertToTag: '$text' → '$newText'")
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
        tagEditText = editText

        // Visual feedback: show what's being tagged in the search bar
        clipboardSearchBox?.let {
            it.text = "Tags: ${entry.content.take(30)}"
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
        tagEditText = null
        tagPanelContent?.removeAllViews()

        // Unlock UI controls first
        setTagModeLockUI(false)

        // Restore search bar to default state (pane is still open, so use the "tap to search" hint)
        clipboardSearchBox?.apply {
            text = ""
            hint = "Tap to search..."
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

    /**
     * Shows the date filter dialog for filtering clipboard entries by date.
     *
     * @param anchorView View to anchor the dialog window token
     */
    fun showDateFilterDialog(anchorView: View) {
        // Use dark theme for dialog to match keyboard theme
        val themedContext = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog)

        val dialogView = LayoutInflater.from(themedContext).inflate(
            R.layout.clipboard_date_filter_dialog, null
        )

        val enabledSwitch = dialogView.findViewById<Switch>(R.id.date_filter_enabled)
        val modeGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.date_filter_mode)
        val beforeRadio = dialogView.findViewById<RadioButton>(R.id.date_filter_before)
        val afterRadio = dialogView.findViewById<RadioButton>(R.id.date_filter_after)
        val datePicker = dialogView.findViewById<DatePicker>(R.id.date_picker)
        val modeContainer = dialogView.findViewById<View>(R.id.date_filter_mode_container)
        val pickerContainer = dialogView.findViewById<View>(R.id.date_picker_container)

        // Get current filter state from ClipboardHistoryView
        val isFilterEnabled = clipboardHistoryView?.isDateFilterEnabled() ?: false
        val isBeforeMode = clipboardHistoryView?.isDateFilterBefore() ?: false

        enabledSwitch.isChecked = isFilterEnabled
        if (isBeforeMode) {
            beforeRadio.isChecked = true
        } else {
            afterRadio.isChecked = true
        }

        // Set initial visibility based on enabled state
        modeContainer.visibility = if (isFilterEnabled) View.VISIBLE else View.GONE
        pickerContainer.visibility = if (isFilterEnabled) View.VISIBLE else View.GONE

        // Toggle visibility when enable switch changes
        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            modeContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            pickerContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Get current filter date or default to today
        val cal = Calendar.getInstance()
        clipboardHistoryView?.let { historyView ->
            if (historyView.getDateFilterTimestamp() > 0) {
                cal.timeInMillis = historyView.getDateFilterTimestamp()
            }
        }
        datePicker.updateDate(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )

        val dialog = android.app.AlertDialog.Builder(themedContext)
            .setTitle("Filter by Date")
            .setView(dialogView)
            .create()

        // Set up button click handlers
        dialogView.findViewById<View>(R.id.date_filter_clear).setOnClickListener {
            clipboardHistoryView?.clearDateFilter()
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.date_filter_cancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.date_filter_apply).setOnClickListener {
            clipboardHistoryView?.let { historyView ->
                val enabled = enabledSwitch.isChecked
                val isBefore = beforeRadio.isChecked

                if (enabled) {
                    // Get selected date at start of day (00:00:00)
                    val selectedCal = Calendar.getInstance().apply {
                        set(datePicker.year, datePicker.month, datePicker.dayOfMonth, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val timestamp = selectedCal.timeInMillis

                    historyView.setDateFilter(timestamp, isBefore)
                } else {
                    historyView.clearDateFilter()
                }
            }
            dialog.dismiss()
        }

        Utils.show_dialog_on_ime(dialog, anchorView.windowToken)
    }

    /**
     * Updates configuration and re-applies tab visibility.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        config = newConfig
        applyTabVisibility()
    }

    /**
     * Shows/hides pinned and todo tab buttons based on their individual config toggles.
     * Forces back to HISTORY tab if the current tab was just disabled.
     */
    private fun applyTabVisibility() {
        tabPinned?.visibility = if (config.clipboard_pinned_enabled) View.VISIBLE else View.GONE
        tabTodos?.visibility = if (config.clipboard_todo_enabled) View.VISIBLE else View.GONE

        // Force back to HISTORY if sitting on a disabled tab
        if (currentTab == ClipboardTab.PINNED && !config.clipboard_pinned_enabled) {
            switchToTab(ClipboardTab.HISTORY)
        }
        if (currentTab == ClipboardTab.TODOS && !config.clipboard_todo_enabled) {
            switchToTab(ClipboardTab.HISTORY)
        }
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
        clipboardHistoryView = null
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
        tagEditText = null
        currentTab = ClipboardTab.HISTORY
    }

    /** Reset tag state without triggering data reload (used during cleanup) */
    private fun hideTagPanelSilent() {
        tagMode = false
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
    }
}
