package tribixbite.cleverkeys

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.DatePicker
import android.widget.ImageButton
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
    private var clipboardHistoryView: ClipboardHistoryView? = null

    // Tab buttons
    private var tabHistory: TextView? = null
    private var tabPinned: TextView? = null
    private var tabTodos: TextView? = null

    // Pagination views
    private var paginationBar: View? = null
    private var pagePrev: TextView? = null
    private var pageInfo: TextView? = null
    private var pageNext: TextView? = null

    // Current tab state
    private var currentTab = ClipboardTab.HISTORY

    // Close callback (set by CleverKeysService to hide clipboard pane)
    private var onCloseCallback: (() -> Unit)? = null

    // Search state
    private var searchMode = false

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
            clipboardHistoryView = clipboardPane?.findViewById(R.id.clipboard_history_view)

            // Set up search box click listener
            clipboardSearchBox?.setOnClickListener {
                searchMode = true
                clipboardSearchBox?.hint = "Type on keyboard below..."
                clipboardSearchBox?.requestFocus()
            }

            // Set up date filter icon
            clipboardPane?.findViewById<View>(R.id.clipboard_date_filter)?.setOnClickListener { v ->
                showDateFilterDialog(v)
            }

            // Set up close button
            clipboardPane?.findViewById<ImageButton>(R.id.clipboard_close_button)?.setOnClickListener {
                onCloseCallback?.invoke()
            }

            // Set up tab buttons
            tabHistory = clipboardPane?.findViewById(R.id.tab_history)
            tabPinned = clipboardPane?.findViewById(R.id.tab_pinned)
            tabTodos = clipboardPane?.findViewById(R.id.tab_todos)

            tabHistory?.setOnClickListener { switchToTab(ClipboardTab.HISTORY) }
            tabPinned?.setOnClickListener { switchToTab(ClipboardTab.PINNED) }
            tabTodos?.setOnClickListener { switchToTab(ClipboardTab.TODOS) }

            // Set initial tab highlighting
            updateTabHighlighting()

            // Set up pagination controls
            paginationBar = clipboardPane?.findViewById(R.id.clipboard_pagination_bar)
            pagePrev = clipboardPane?.findViewById(R.id.clipboard_page_prev)
            pageInfo = clipboardPane?.findViewById(R.id.clipboard_page_info)
            pageNext = clipboardPane?.findViewById(R.id.clipboard_page_next)

            pagePrev?.setOnClickListener {
                clipboardHistoryView?.previousPage()
            }
            pageNext?.setOnClickListener {
                clipboardHistoryView?.nextPage()
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

        currentTab = tab
        clipboardHistoryView?.setTab(tab)
        updateTabHighlighting()

        // Clear search when switching tabs
        clearSearch()
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
     * Updates configuration.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        config = newConfig
    }

    /**
     * Cleans up resources.
     * Should be called during keyboard shutdown.
     */
    fun cleanup() {
        clipboardPane = null
        clipboardSearchBox = null
        clipboardHistoryView = null
        tabHistory = null
        tabPinned = null
        tabTodos = null
        paginationBar = null
        pagePrev = null
        pageInfo = null
        pageNext = null
        onCloseCallback = null
        searchMode = false
        currentTab = ClipboardTab.HISTORY
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
