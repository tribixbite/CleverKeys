package tribixbite.cleverkeys

import android.util.Log

/**
 * Manages emoji search mode and query state.
 *
 * #41: Redesigned emoji search to use the suggestion bar instead of separate EditText.
 * Features:
 * - Auto-detects word before cursor when opening emoji pane
 * - Shows "Type to search" or "Search results for '[query]':" in suggestion bar
 * - Routes keyboard input to search query when in search mode
 * - Fuzzy matching with lowercase normalization
 *
 * This class follows the same pattern as ClipboardManager for search functionality.
 *
 * @since v1.33.0
 */
class EmojiSearchManager(
    private val suggestionBarProvider: () -> SuggestionBar?,
    private val emojiGridProvider: () -> EmojiGridView?
) {
    // Search state
    private var searchMode = false
    private var searchQuery = StringBuilder()

    /**
     * Checks if emoji search mode is active.
     * When active, keyboard input is routed to the search query.
     */
    fun isInSearchMode(): Boolean = searchMode

    /**
     * Gets the current search query.
     */
    fun getSearchQuery(): String = searchQuery.toString()

    /**
     * Enters emoji search mode with an optional initial query.
     * Called when emoji pane is opened (SWITCH_EMOJI).
     *
     * @param initialQuery Optional initial search query (auto-detected word before cursor)
     */
    fun enterSearchMode(initialQuery: String? = null) {
        searchMode = true
        searchQuery.clear()

        if (!initialQuery.isNullOrBlank()) {
            // Start with pre-detected query from text before cursor
            searchQuery.append(initialQuery)
            updateSearchDisplay()
            performSearch(initialQuery)
            Log.d(TAG, "Entered emoji search with initial query: '$initialQuery'")
        } else {
            // No initial query - show "Type to search" prompt
            showTypeToSearchPrompt()
            // Show last used emojis when no query
            emojiGridProvider()?.setEmojiGroup(EmojiGridView.GROUP_LAST_USE)
            Log.d(TAG, "Entered emoji search (no initial query)")
        }
    }

    /**
     * Exits emoji search mode and clears query.
     */
    fun exitSearchMode() {
        searchMode = false
        searchQuery.clear()
        // Clear the suggestion bar emoji search status
        suggestionBarProvider()?.clearEmojiSearchStatus()
        Log.d(TAG, "Exited emoji search mode")
    }

    /**
     * Appends text to the search query.
     * Called when user types while emoji pane is visible.
     */
    fun appendToSearch(text: String) {
        if (!searchMode) return

        searchQuery.append(text)
        updateSearchDisplay()
        performSearch(searchQuery.toString())
        Log.d(TAG, "Appended to emoji search: '$text', query now: '$searchQuery'")
    }

    /**
     * Deletes last character from search query.
     * Called when user presses backspace while emoji pane is visible.
     */
    fun deleteFromSearch() {
        if (!searchMode) return

        if (searchQuery.isNotEmpty()) {
            searchQuery.deleteCharAt(searchQuery.length - 1)
            updateSearchDisplay()

            if (searchQuery.isEmpty()) {
                // Show "Type to search" when query becomes empty
                showTypeToSearchPrompt()
                emojiGridProvider()?.setEmojiGroup(EmojiGridView.GROUP_LAST_USE)
            } else {
                performSearch(searchQuery.toString())
            }
            Log.d(TAG, "Deleted from emoji search, query now: '$searchQuery'")
        }
    }

    /**
     * Clears search query and exits search mode.
     */
    fun clearSearch() {
        searchQuery.clear()
        exitSearchMode()
    }

    /**
     * Shows "Type to search" prompt in the suggestion bar.
     */
    private fun showTypeToSearchPrompt() {
        suggestionBarProvider()?.showEmojiSearchStatus("Type to search emoji...")
    }

    /**
     * Updates suggestion bar to show current search status.
     */
    private fun updateSearchDisplay() {
        if (searchQuery.isEmpty()) {
            showTypeToSearchPrompt()
        } else {
            suggestionBarProvider()?.showEmojiSearchStatus("Search: \"${searchQuery}\"")
        }
    }

    /**
     * Performs emoji search and updates the grid.
     */
    private fun performSearch(query: String) {
        emojiGridProvider()?.searchEmojis(query)
    }

    /**
     * Extracts the word immediately before cursor (for auto-detecting search query).
     * Returns null if:
     * - Text ends with whitespace/symbol
     * - No text before cursor
     * - Text contains only whitespace
     *
     * @param textBeforeCursor Text retrieved from InputConnection.getTextBeforeCursor()
     * @return Word to use as initial search query, or null
     */
    fun extractWordBeforeCursor(textBeforeCursor: CharSequence?): String? {
        if (textBeforeCursor.isNullOrEmpty()) return null

        val text = textBeforeCursor.toString()

        // If ends with space or symbol, no word to extract
        val lastChar = text.lastOrNull() ?: return null
        if (lastChar.isWhitespace() || !lastChar.isLetterOrDigit()) return null

        // Find the start of the last word (go back until whitespace or start)
        var wordStart = text.length - 1
        while (wordStart > 0 && text[wordStart - 1].isLetterOrDigit()) {
            wordStart--
        }

        val word = text.substring(wordStart)
        return if (word.isNotBlank()) word else null
    }

    companion object {
        private const val TAG = "EmojiSearchManager"
    }
}
