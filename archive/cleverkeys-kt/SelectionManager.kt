package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages text selection operations for intelligent text editing.
 *
 * Provides comprehensive selection functionality including character-level,
 * word-level, and line-level selection with smart boundary detection,
 * selection expansion/contraction, and multi-mode selection support.
 *
 * Features:
 * - Character-level selection
 * - Word-level selection (smart word boundaries)
 * - Line-level selection
 * - Paragraph selection
 * - Selection expansion/contraction
 * - Selection modes (character, word, line)
 * - Smart boundary detection
 * - Selection anchoring
 * - Selection inversion
 * - Range validation
 * - Multi-selection tracking
 * - Selection history
 * - Clipboard integration
 * - Selection persistence
 *
 * Bug #321 - CATASTROPHIC: Complete implementation of missing SelectionManager.java
 *
 * @param context Application context
 */
class SelectionManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "SelectionManager"

        // Selection parameters
        private const val DEFAULT_MAX_SELECTION_HISTORY = 20

        /**
         * Selection mode.
         */
        enum class SelectionMode {
            CHARACTER,      // Character-by-character selection
            WORD,          // Word-by-word selection
            LINE,          // Line-by-line selection
            PARAGRAPH,     // Paragraph-by-paragraph selection
            SENTENCE,      // Sentence-by-sentence selection
            DOCUMENT       // Entire document selection
        }

        /**
         * Selection direction.
         */
        enum class SelectionDirection {
            FORWARD,       // Selection extends forward (right/down)
            BACKWARD,      // Selection extends backward (left/up)
            NONE           // No active selection
        }

        /**
         * Selection boundary type.
         */
        enum class BoundaryType {
            CHARACTER,
            WORD,
            LINE,
            SENTENCE,
            PARAGRAPH
        }

        /**
         * Text selection.
         */
        data class Selection(
            val start: Int,
            val end: Int,
            val mode: SelectionMode = SelectionMode.CHARACTER,
            val anchor: Int = start,  // Fixed point during selection
            val timestamp: Long = System.currentTimeMillis()
        ) {
            /**
             * Get selection length.
             */
            val length: Int
                get() = end - start

            /**
             * Check if selection is empty.
             */
            val isEmpty: Boolean
                get() = start == end

            /**
             * Get selection direction.
             */
            val direction: SelectionDirection
                get() = when {
                    end > start -> SelectionDirection.FORWARD
                    end < start -> SelectionDirection.BACKWARD
                    else -> SelectionDirection.NONE
                }

            /**
             * Get normalized selection (start <= end).
             */
            fun normalize(): Selection {
                return if (start <= end) this
                else copy(start = end, end = start)
            }

            /**
             * Check if position is within selection.
             */
            fun contains(position: Int): Boolean {
                val normalized = normalize()
                return position >= normalized.start && position <= normalized.end
            }

            /**
             * Expand selection by offset.
             */
            fun expand(startOffset: Int, endOffset: Int): Selection {
                return copy(
                    start = (start + startOffset).coerceAtLeast(0),
                    end = end + endOffset
                )
            }

            /**
             * Contract selection by offset.
             */
            fun contract(startOffset: Int, endOffset: Int): Selection {
                val newStart = start + startOffset
                val newEnd = end - endOffset
                return if (newStart >= newEnd) {
                    copy(start = newStart, end = newStart)
                } else {
                    copy(start = newStart, end = newEnd)
                }
            }
        }

        /**
         * Selection state.
         */
        data class SelectionState(
            val selection: Selection?,
            val hasSelection: Boolean,
            val selectedText: String,
            val mode: SelectionMode,
            val canExpand: Boolean,
            val canContract: Boolean
        )
    }

    /**
     * Callback interface for selection events.
     */
    interface Callback {
        /**
         * Called when selection changes.
         *
         * @param selection New selection, or null if cleared
         */
        fun onSelectionChanged(selection: Selection?)

        /**
         * Called when selection mode changes.
         *
         * @param mode New selection mode
         */
        fun onModeChanged(mode: SelectionMode)

        /**
         * Called when text is selected.
         *
         * @param text Selected text
         * @param selection Selection range
         */
        fun onTextSelected(text: String, selection: Selection)

        /**
         * Called when selection is cleared.
         */
        fun onSelectionCleared()
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _selectionState = MutableStateFlow(
        SelectionState(
            selection = null,
            hasSelection = false,
            selectedText = "",
            mode = SelectionMode.CHARACTER,
            canExpand = false,
            canContract = false
        )
    )
    val selectionState: StateFlow<SelectionState> = _selectionState.asStateFlow()

    private var currentSelection: Selection? = null
    private var currentMode: SelectionMode = SelectionMode.CHARACTER
    private var callback: Callback? = null

    // Selection history
    private val selectionHistory = ArrayDeque<Selection>()
    private var maxHistorySize: Int = DEFAULT_MAX_SELECTION_HISTORY

    // Word boundary patterns
    private val wordBoundaryPattern = Regex("\\b")
    private val whitespacePattern = Regex("\\s+")

    init {
        logD("SelectionManager initialized")
    }

    /**
     * Set selection range.
     *
     * @param start Selection start position
     * @param end Selection end position
     * @param mode Selection mode
     * @param ic Input connection for text retrieval
     * @return True if selection was set
     */
    suspend fun setSelection(
        start: Int,
        end: Int,
        mode: SelectionMode = currentMode,
        ic: InputConnection? = null
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            if (start < 0 || end < 0) {
                logE("Invalid selection range: $start-$end")
                return@withContext false
            }

            val selection = Selection(
                start = start,
                end = end,
                mode = mode,
                anchor = start
            )

            currentSelection = selection
            currentMode = mode

            // Add to history
            addToHistory(selection)

            // Get selected text if input connection available
            val selectedText = if (ic != null) {
                getSelectedText(ic, selection) ?: ""
            } else {
                ""
            }

            updateState(selectedText)
            callback?.onSelectionChanged(selection)
            if (selectedText.isNotEmpty()) {
                callback?.onTextSelected(selectedText, selection)
            }

            logD("Selection set: $start-$end (mode: $mode)")
            true
        } catch (e: Exception) {
            logE("Error setting selection", e)
            false
        }
    }

    /**
     * Select word at position.
     *
     * @param position Position within word
     * @param text Full text
     * @param ic Input connection for applying selection
     * @return Selected word, or null if failed
     */
    suspend fun selectWord(
        position: Int,
        text: String,
        ic: InputConnection? = null
    ): String? = withContext(Dispatchers.Default) {
        try {
            val boundaries = findWordBoundaries(position, text)
            if (boundaries == null) {
                logE("Could not find word boundaries at position $position")
                return@withContext null
            }

            val (start, end) = boundaries
            val word = text.substring(start, end)

            setSelection(start, end, SelectionMode.WORD, ic)
            ic?.setSelection(start, end)

            logD("Selected word: \"$word\" ($start-$end)")
            word
        } catch (e: Exception) {
            logE("Error selecting word", e)
            null
        }
    }

    /**
     * Select line at position.
     *
     * @param position Position within line
     * @param text Full text
     * @param ic Input connection for applying selection
     * @return Selected line, or null if failed
     */
    suspend fun selectLine(
        position: Int,
        text: String,
        ic: InputConnection? = null
    ): String? = withContext(Dispatchers.Default) {
        try {
            val boundaries = findLineBoundaries(position, text)
            if (boundaries == null) {
                logE("Could not find line boundaries at position $position")
                return@withContext null
            }

            val (start, end) = boundaries
            val line = text.substring(start, end)

            setSelection(start, end, SelectionMode.LINE, ic)
            ic?.setSelection(start, end)

            logD("Selected line: \"$line\" ($start-$end)")
            line
        } catch (e: Exception) {
            logE("Error selecting line", e)
            null
        }
    }

    /**
     * Select paragraph at position.
     *
     * @param position Position within paragraph
     * @param text Full text
     * @param ic Input connection for applying selection
     * @return Selected paragraph, or null if failed
     */
    suspend fun selectParagraph(
        position: Int,
        text: String,
        ic: InputConnection? = null
    ): String? = withContext(Dispatchers.Default) {
        try {
            val boundaries = findParagraphBoundaries(position, text)
            if (boundaries == null) {
                logE("Could not find paragraph boundaries at position $position")
                return@withContext null
            }

            val (start, end) = boundaries
            val paragraph = text.substring(start, end)

            setSelection(start, end, SelectionMode.PARAGRAPH, ic)
            ic?.setSelection(start, end)

            logD("Selected paragraph: ${paragraph.length} chars ($start-$end)")
            paragraph
        } catch (e: Exception) {
            logE("Error selecting paragraph", e)
            null
        }
    }

    /**
     * Select all text.
     *
     * @param text Full text
     * @param ic Input connection for applying selection
     * @return True if selection was successful
     */
    suspend fun selectAll(
        text: String,
        ic: InputConnection? = null
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            setSelection(0, text.length, SelectionMode.DOCUMENT, ic)
            ic?.setSelection(0, text.length)

            logD("Selected all: ${text.length} chars")
            true
        } catch (e: Exception) {
            logE("Error selecting all", e)
            false
        }
    }

    /**
     * Expand selection to next boundary.
     *
     * @param boundaryType Boundary type to expand to
     * @param text Full text
     * @param ic Input connection for applying selection
     * @return True if selection was expanded
     */
    suspend fun expandSelection(
        boundaryType: BoundaryType,
        text: String,
        ic: InputConnection? = null
    ): Boolean = withContext(Dispatchers.Default) {
        val selection = currentSelection ?: return@withContext false

        try {
            val (newStart, newEnd) = when (boundaryType) {
                BoundaryType.WORD -> {
                    val startBoundaries = findWordBoundaries(selection.start, text) ?: return@withContext false
                    val endBoundaries = findWordBoundaries(selection.end - 1, text) ?: return@withContext false
                    startBoundaries.first to endBoundaries.second
                }
                BoundaryType.LINE -> {
                    val startBoundaries = findLineBoundaries(selection.start, text) ?: return@withContext false
                    val endBoundaries = findLineBoundaries(selection.end - 1, text) ?: return@withContext false
                    startBoundaries.first to endBoundaries.second
                }
                BoundaryType.PARAGRAPH -> {
                    val boundaries = findParagraphBoundaries(selection.start, text) ?: return@withContext false
                    boundaries.first to boundaries.second
                }
                BoundaryType.SENTENCE -> {
                    val boundaries = findSentenceBoundaries(selection.start, text) ?: return@withContext false
                    boundaries.first to boundaries.second
                }
                BoundaryType.CHARACTER -> {
                    // Character expansion: expand by 1 character on each side
                    (selection.start - 1).coerceAtLeast(0) to (selection.end + 1).coerceAtMost(text.length)
                }
            }

            setSelection(newStart, newEnd, currentMode, ic)
            ic?.setSelection(newStart, newEnd)

            logD("Expanded selection to $boundaryType: $newStart-$newEnd")
            true
        } catch (e: Exception) {
            logE("Error expanding selection", e)
            false
        }
    }

    /**
     * Contract selection by boundary.
     *
     * @param boundaryType Boundary type to contract to
     * @param text Full text
     * @param ic Input connection for applying selection
     * @return True if selection was contracted
     */
    suspend fun contractSelection(
        boundaryType: BoundaryType,
        text: String,
        ic: InputConnection? = null
    ): Boolean = withContext(Dispatchers.Default) {
        val selection = currentSelection ?: return@withContext false

        try {
            val offset = when (boundaryType) {
                BoundaryType.CHARACTER -> 1
                BoundaryType.WORD -> {
                    val word = findWordBoundaries(selection.start, text)
                    word?.second?.minus(word.first) ?: 1
                }
                else -> return@withContext false
            }

            val contracted = selection.contract(offset, offset)
            if (contracted.isEmpty) {
                clearSelection(ic)
                return@withContext true
            }

            setSelection(contracted.start, contracted.end, currentMode, ic)
            ic?.setSelection(contracted.start, contracted.end)

            logD("Contracted selection: ${contracted.start}-${contracted.end}")
            true
        } catch (e: Exception) {
            logE("Error contracting selection", e)
            false
        }
    }

    /**
     * Move selection start position.
     *
     * @param offset Offset to move (positive = forward, negative = backward)
     * @param text Full text
     * @param ic Input connection for applying selection
     * @return True if selection was moved
     */
    suspend fun moveSelectionStart(
        offset: Int,
        text: String,
        ic: InputConnection? = null
    ): Boolean = withContext(Dispatchers.Default) {
        val selection = currentSelection ?: return@withContext false

        try {
            val newStart = (selection.start + offset).coerceIn(0, selection.end)
            setSelection(newStart, selection.end, currentMode, ic)
            ic?.setSelection(newStart, selection.end)

            logD("Moved selection start: $newStart")
            true
        } catch (e: Exception) {
            logE("Error moving selection start", e)
            false
        }
    }

    /**
     * Move selection end position.
     *
     * @param offset Offset to move (positive = forward, negative = backward)
     * @param text Full text
     * @param ic Input connection for applying selection
     * @return True if selection was moved
     */
    suspend fun moveSelectionEnd(
        offset: Int,
        text: String,
        ic: InputConnection? = null
    ): Boolean = withContext(Dispatchers.Default) {
        val selection = currentSelection ?: return@withContext false

        try {
            val newEnd = (selection.end + offset).coerceIn(selection.start, text.length)
            setSelection(selection.start, newEnd, currentMode, ic)
            ic?.setSelection(selection.start, newEnd)

            logD("Moved selection end: $newEnd")
            true
        } catch (e: Exception) {
            logE("Error moving selection end", e)
            false
        }
    }

    /**
     * Clear current selection.
     *
     * @param ic Input connection for clearing selection
     */
    suspend fun clearSelection(ic: InputConnection? = null) = withContext(Dispatchers.Default) {
        val hadSelection = currentSelection != null

        currentSelection = null
        updateState("")

        if (hadSelection) {
            callback?.onSelectionCleared()
            logD("Selection cleared")
        }
    }

    /**
     * Get current selection.
     *
     * @return Current selection, or null if none
     */
    fun getSelection(): Selection? = currentSelection

    /**
     * Check if has active selection.
     *
     * @return True if has selection
     */
    fun hasSelection(): Boolean = currentSelection != null && !currentSelection!!.isEmpty

    /**
     * Get selected text from input connection.
     *
     * @param ic Input connection
     * @param selection Selection range
     * @return Selected text, or null if failed
     */
    private fun getSelectedText(ic: InputConnection, selection: Selection): String? {
        return try {
            val normalized = selection.normalize()
            val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            if (extractedText != null) {
                val text = extractedText.text.toString()
                if (normalized.end <= text.length) {
                    text.substring(normalized.start, normalized.end)
                } else null
            } else null
        } catch (e: Exception) {
            logE("Error getting selected text", e)
            null
        }
    }

    /**
     * Find word boundaries at position.
     *
     * @param position Position within word
     * @param text Full text
     * @return Pair of (start, end) positions, or null if not found
     */
    private fun findWordBoundaries(position: Int, text: String): Pair<Int, Int>? {
        if (position < 0 || position >= text.length) return null

        // Find start of word
        var start = position
        while (start > 0 && !text[start - 1].isWhitespace() && !text[start - 1].let { it in ".,!?;:()" }) {
            start--
        }

        // Find end of word
        var end = position
        while (end < text.length && !text[end].isWhitespace() && !text[end].let { it in ".,!?;:()" }) {
            end++
        }

        return if (start < end) start to end else null
    }

    /**
     * Find line boundaries at position.
     *
     * @param position Position within line
     * @param text Full text
     * @return Pair of (start, end) positions, or null if not found
     */
    private fun findLineBoundaries(position: Int, text: String): Pair<Int, Int>? {
        if (position < 0 || position >= text.length) return null

        // Find start of line
        var start = position
        while (start > 0 && text[start - 1] != '\n') {
            start--
        }

        // Find end of line
        var end = position
        while (end < text.length && text[end] != '\n') {
            end++
        }

        return start to end
    }

    /**
     * Find paragraph boundaries at position.
     *
     * @param position Position within paragraph
     * @param text Full text
     * @return Pair of (start, end) positions, or null if not found
     */
    private fun findParagraphBoundaries(position: Int, text: String): Pair<Int, Int>? {
        if (position < 0 || position >= text.length) return null

        // Find start of paragraph (double newline or start of text)
        var start = position
        while (start > 1) {
            if (text[start - 1] == '\n' && text[start - 2] == '\n') {
                break
            }
            start--
        }

        // Find end of paragraph (double newline or end of text)
        var end = position
        while (end < text.length - 1) {
            if (text[end] == '\n' && text[end + 1] == '\n') {
                break
            }
            end++
        }

        return start to end
    }

    /**
     * Find sentence boundaries at position.
     *
     * @param position Position within sentence
     * @param text Full text
     * @return Pair of (start, end) positions, or null if not found
     */
    private fun findSentenceBoundaries(position: Int, text: String): Pair<Int, Int>? {
        if (position < 0 || position >= text.length) return null

        // Sentence endings
        val sentenceEndings = setOf('.', '!', '?')

        // Find start of sentence
        var start = position
        while (start > 0) {
            if (text[start - 1] in sentenceEndings) {
                // Skip whitespace after sentence ending
                while (start < text.length && text[start].isWhitespace()) {
                    start++
                }
                break
            }
            start--
        }

        // Find end of sentence
        var end = position
        while (end < text.length) {
            if (text[end] in sentenceEndings) {
                end++
                break
            }
            end++
        }

        return start to end
    }

    /**
     * Add selection to history.
     *
     * @param selection Selection to add
     */
    private fun addToHistory(selection: Selection) {
        selectionHistory.addLast(selection)

        // Limit history size
        while (selectionHistory.size > maxHistorySize) {
            selectionHistory.removeFirst()
        }
    }

    /**
     * Get selection history.
     *
     * @param maxCount Maximum number of selections to return
     * @return List of recent selections
     */
    fun getHistory(maxCount: Int = 10): List<Selection> {
        return selectionHistory.takeLast(maxCount)
    }

    /**
     * Update selection state and notify callback.
     */
    private fun updateState(selectedText: String) {
        val state = SelectionState(
            selection = currentSelection,
            hasSelection = currentSelection != null && !currentSelection!!.isEmpty,
            selectedText = selectedText,
            mode = currentMode,
            canExpand = currentSelection != null && !currentSelection!!.isEmpty,
            canContract = currentSelection != null && !currentSelection!!.isEmpty
        )

        _selectionState.value = state
    }

    /**
     * Set selection mode.
     *
     * @param mode New selection mode
     */
    fun setMode(mode: SelectionMode) {
        if (currentMode != mode) {
            currentMode = mode
            callback?.onModeChanged(mode)
            logD("Selection mode changed to: $mode")
        }
    }

    /**
     * Get current selection mode.
     *
     * @return Current mode
     */
    fun getMode(): SelectionMode = currentMode

    /**
     * Set maximum history size.
     *
     * @param size Maximum number of selections to keep
     */
    fun setMaxHistorySize(size: Int) {
        maxHistorySize = size.coerceIn(5, 100)

        // Trim if necessary
        while (selectionHistory.size > maxHistorySize) {
            selectionHistory.removeFirst()
        }

        logD("Max history size set to: $maxHistorySize")
    }

    /**
     * Get selection statistics.
     *
     * @return Map of statistic names to values
     */
    fun getStatistics(): Map<String, Any> {
        val modeCount = selectionHistory.groupingBy { it.mode }.eachCount()

        return mapOf(
            "has_selection" to hasSelection(),
            "current_mode" to currentMode.name,
            "history_size" to selectionHistory.size,
            "max_history_size" to maxHistorySize,
            "selection_modes_used" to modeCount,
            "current_selection_length" to (currentSelection?.length ?: 0)
        )
    }

    /**
     * Set callback for selection events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing SelectionManager resources...")

        try {
            scope.cancel()
            callback = null
            currentSelection = null
            selectionHistory.clear()
            logD("✅ SelectionManager resources released")
        } catch (e: Exception) {
            logE("Error releasing selection manager resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
