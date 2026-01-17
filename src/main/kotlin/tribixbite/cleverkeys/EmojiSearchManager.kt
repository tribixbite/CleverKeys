package tribixbite.cleverkeys

import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView

/**
 * Manages emoji search with a visible EditText input field.
 *
 * #41 v4: Complete redesign with proper search UI:
 * - Visible EditText for search input (user sees what they type)
 * - Clear button (X) to reset search
 * - "No results" message when search returns empty
 * - Focus-based routing (typing goes to EditText only when focused)
 * - Auto-detect word before cursor for initial query
 *
 * @since v1.2.10
 */
class EmojiSearchManager {

    private var searchInput: EditText? = null
    private var clearButton: ImageButton? = null
    private var closeButton: ImageButton? = null
    private var noResultsView: TextView? = null
    private var emojiGrid: EmojiGridView? = null
    private var groupButtonsBar: EmojiGroupButtonsBar? = null

    private var isInitialized = false
    private var textWatcher: TextWatcher? = null

    // #41 v5: Simple flag like ClipboardManager.searchMode - set true when pane open
    private var searchActive = false

    // Callback to close emoji pane and return to keyboard
    private var onCloseCallback: (() -> Unit)? = null

    /**
     * Initialize the search manager with views from the emoji pane.
     * Call this when the emoji pane is inflated.
     */
    fun initialize(emojiPane: ViewGroup) {
        searchInput = emojiPane.findViewById(R.id.emoji_search_input)
        clearButton = emojiPane.findViewById(R.id.emoji_search_clear)
        closeButton = emojiPane.findViewById(R.id.emoji_close_button)
        noResultsView = emojiPane.findViewById(R.id.emoji_no_results)
        emojiGrid = emojiPane.findViewById(R.id.emoji_grid)
        groupButtonsBar = emojiPane.findViewById(R.id.emoji_group_buttons)

        setupTextWatcher()
        setupClearButton()
        setupCloseButton()

        isInitialized = true
        Log.d(TAG, "EmojiSearchManager initialized")
    }

    /**
     * Set callback to close emoji pane and return to keyboard.
     */
    fun setOnCloseCallback(callback: () -> Unit) {
        onCloseCallback = callback
    }

    /**
     * Set up text change listener for search input.
     */
    private fun setupTextWatcher() {
        textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                onSearchQueryChanged(query)
            }
        }
        searchInput?.addTextChangedListener(textWatcher)
    }

    /**
     * Set up clear button click listener.
     */
    private fun setupClearButton() {
        clearButton?.setOnClickListener {
            clearSearch()
        }
    }

    /**
     * Set up close button to dismiss emoji pane.
     */
    private fun setupCloseButton() {
        closeButton?.setOnClickListener {
            Log.d(TAG, "Close button clicked")
            onCloseCallback?.invoke()
        }
    }

    /**
     * Handle search query changes.
     */
    private fun onSearchQueryChanged(query: String) {
        Log.d(TAG, "Search query changed: '$query'")

        // Show/hide clear button
        clearButton?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

        if (query.isBlank()) {
            // Empty query - show last used emojis
            showGrid()
            emojiGrid?.setEmojiGroup(EmojiGridView.GROUP_LAST_USE)
        } else {
            // Perform search
            val results = emojiGrid?.searchEmojis(query)

            // Show/hide no results message
            if (results == 0) {
                showNoResults(query)
            } else {
                showGrid()
            }
        }
    }

    /**
     * Show the emoji grid, hide no results message.
     */
    private fun showGrid() {
        emojiGrid?.visibility = View.VISIBLE
        noResultsView?.visibility = View.GONE
    }

    /**
     * Show "no results" message, hide emoji grid.
     */
    private fun showNoResults(query: String) {
        emojiGrid?.visibility = View.GONE
        noResultsView?.text = "No emoji found for \"$query\""
        noResultsView?.visibility = View.VISIBLE
    }

    /**
     * Clear the search and reset to default state.
     */
    fun clearSearch() {
        searchInput?.setText("")
        searchInput?.clearFocus()
        clearButton?.visibility = View.GONE
        showGrid()
        emojiGrid?.setEmojiGroup(EmojiGridView.GROUP_LAST_USE)
        Log.d(TAG, "Search cleared")
    }

    /**
     * Called when emoji pane is opened.
     * Optionally pre-fills search with detected word before cursor.
     * #41 v5: Sets searchActive flag for routing
     */
    fun onPaneOpened(initialQuery: String? = null) {
        if (!isInitialized) {
            Log.w(TAG, "onPaneOpened called before initialization")
            return
        }

        // #41 v6: Activate search routing (like ClipboardManager.searchMode)
        searchActive = true
        Log.d(TAG, "Search active = true, searchInput=$searchInput")

        // Reset state
        showGrid()
        noResultsView?.visibility = View.GONE

        // #41 v7: ALWAYS focus search input when pane opens so typing goes there
        searchInput?.requestFocus()

        if (!initialQuery.isNullOrBlank()) {
            // Pre-fill search with detected word
            searchInput?.setText(initialQuery)
            searchInput?.setSelection(initialQuery.length)
            Log.d(TAG, "Pane opened with initial query: '$initialQuery'")
        } else {
            // No initial query - show last used emojis
            searchInput?.setText("")
            clearButton?.visibility = View.GONE
            emojiGrid?.setEmojiGroup(EmojiGridView.GROUP_LAST_USE)
            Log.d(TAG, "Pane opened (no initial query)")
        }
    }

    /**
     * Called when emoji pane is closed.
     * #41 v5: Clears searchActive flag for routing
     */
    fun onPaneClosed() {
        // #41 v5: Deactivate search routing (like ClipboardManager.searchMode)
        searchActive = false
        Log.d(TAG, "Search active = false")

        // Clear search state when pane closes
        searchInput?.setText("")
        searchInput?.clearFocus()
        clearButton?.visibility = View.GONE
        showGrid()
        Log.d(TAG, "Pane closed, search reset")
    }

    /**
     * Called when a category button is tapped.
     * Clears the search to show the selected category.
     */
    fun onCategorySelected() {
        if (searchInput?.text?.isNotEmpty() == true) {
            searchInput?.setText("")
            clearButton?.visibility = View.GONE
            showGrid()
            Log.d(TAG, "Category selected, search cleared")
        }
    }

    /**
     * Called when an emoji is selected from the grid.
     * #41 v8: Temporarily deactivates search routing so emoji is inserted into app, not search bar.
     * Must be called BEFORE the emoji key_up event is processed.
     */
    fun onEmojiSelected() {
        // Deactivate routing so the emoji goes to InputConnection, not search
        searchActive = false
        Log.d(TAG, "Emoji selected, searchActive temporarily disabled")
    }

    /**
     * Called after emoji selection to re-enable search routing.
     * #41 v8: Re-activates search routing after emoji is inserted.
     */
    fun onEmojiInserted() {
        // Re-enable routing for continued searching
        if (isInitialized) {
            searchActive = true
            Log.d(TAG, "Emoji inserted, searchActive re-enabled")
        }
    }

    /**
     * Check if emoji search is active.
     * Used to determine if keyboard input should be routed to search.
     * #41 v6: Simple flag like ClipboardManager.searchMode
     */
    fun isEmojiPaneOpen(): Boolean {
        Log.d(TAG, "isEmojiPaneOpen() called, searchActive=$searchActive")
        return searchActive
    }

    /**
     * Append text to the search query.
     * Called from KeyEventHandler when emoji pane is open.
     * This programmatically updates the EditText since IME can't type into its own views.
     */
    fun appendToSearch(text: String) {
        val input = searchInput ?: return
        val current = input.text?.toString() ?: ""
        val newText = current + text
        input.setText(newText)
        input.setSelection(newText.length)
        Log.d(TAG, "Appended to search: '$text' -> '$newText'")
    }

    /**
     * Handle backspace in search.
     * Called from KeyEventHandler when emoji pane is open.
     */
    fun backspaceSearch() {
        val input = searchInput ?: return
        val current = input.text?.toString() ?: ""
        if (current.isNotEmpty()) {
            val newText = current.dropLast(1)
            input.setText(newText)
            input.setSelection(newText.length)
            Log.d(TAG, "Backspace in search: '$current' -> '$newText'")
        }
    }

    /**
     * Request focus on search input.
     */
    fun focusSearch() {
        searchInput?.requestFocus()
    }

    /**
     * Clear focus from search input.
     */
    fun unfocusSearch() {
        searchInput?.clearFocus()
    }

    /**
     * Extracts the word immediately before cursor (for auto-detecting search query).
     */
    fun extractWordBeforeCursor(textBeforeCursor: CharSequence?): String? {
        if (textBeforeCursor.isNullOrEmpty()) return null

        val text = textBeforeCursor.toString()

        // If ends with space or symbol, no word to extract
        val lastChar = text.lastOrNull() ?: return null
        if (lastChar.isWhitespace() || !lastChar.isLetterOrDigit()) return null

        // Find the start of the last word
        var wordStart = text.length - 1
        while (wordStart > 0 && text[wordStart - 1].isLetterOrDigit()) {
            wordStart--
        }

        val word = text.substring(wordStart)
        return if (word.isNotBlank()) word else null
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        textWatcher?.let { searchInput?.removeTextChangedListener(it) }
        textWatcher = null
        searchInput = null
        clearButton = null
        closeButton = null
        noResultsView = null
        emojiGrid = null
        groupButtonsBar = null
        onCloseCallback = null
        isInitialized = false
        Log.d(TAG, "EmojiSearchManager cleaned up")
    }

    companion object {
        private const val TAG = "EmojiSearchManager"
    }
}
