package tribixbite.keyboard2

import android.view.inputmethod.InputConnection

/**
 * Manages cursor movement and text selection operations.
 *
 * Features:
 * - Character-by-character navigation (left/right)
 * - Word-by-word navigation
 * - Line navigation (start/end)
 * - Document navigation (start/end)
 * - Text selection while moving
 * - Jump to specific positions
 * - Undo/redo cursor position history
 *
 * Addresses Bug #322: CursorMovementManager missing (HIGH)
 */
class CursorMovementManager {

    companion object {
        private const val TAG = "CursorMovementManager"

        // Word boundary characters
        private val WORD_SEPARATORS = setOf(
            ' ', '\n', '\t', '.', ',', ';', ':', '!', '?',
            '(', ')', '[', ']', '{', '}', '<', '>',
            '/', '\\', '|', '-', '_', '=', '+', '*', '&', '%', '$', '#', '@',
            '"', '\'', '`', '~'
        )

        // Maximum text to fetch for context
        private const val MAX_TEXT_BEFORE = 1000
        private const val MAX_TEXT_AFTER = 1000
    }

    /**
     * Movement direction
     */
    enum class Direction {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    /**
     * Movement unit
     */
    enum class Unit {
        CHARACTER,  // Single character
        WORD,       // Whole word
        LINE,       // To start/end of line
        DOCUMENT    // To start/end of document
    }

    // Cursor position history for undo/redo
    private val positionHistory = mutableListOf<Int>()
    private var historyIndex = -1
    private val maxHistorySize = 50

    /**
     * Move cursor by specified unit and direction
     * @param ic InputConnection
     * @param direction Direction to move
     * @param unit Movement unit
     * @param select Whether to extend selection while moving
     * @return true if movement was performed
     */
    fun moveCursor(
        ic: InputConnection?,
        direction: Direction,
        unit: Unit,
        select: Boolean = false
    ): Boolean {
        if (ic == null) return false

        return when (unit) {
            Unit.CHARACTER -> moveByCharacter(ic, direction, select)
            Unit.WORD -> moveByWord(ic, direction, select)
            Unit.LINE -> moveToLineEdge(ic, direction, select)
            Unit.DOCUMENT -> moveToDocumentEdge(ic, direction, select)
        }
    }

    /**
     * Move cursor by one character
     */
    private fun moveByCharacter(
        ic: InputConnection,
        direction: Direction,
        select: Boolean
    ): Boolean {
        return when (direction) {
            Direction.LEFT -> {
                if (select) {
                    // Extend selection left
                    ic.setSelection(getSelectionStart(ic) - 1, getSelectionEnd(ic))
                } else {
                    // Move cursor left (collapse selection)
                    val newPos = getCursorPosition(ic) - 1
                    if (newPos >= 0) {
                        ic.setSelection(newPos, newPos)
                        savePosition(newPos)
                    }
                }
                true
            }
            Direction.RIGHT -> {
                if (select) {
                    // Extend selection right
                    ic.setSelection(getSelectionStart(ic), getSelectionEnd(ic) + 1)
                } else {
                    // Move cursor right (collapse selection)
                    val newPos = getCursorPosition(ic) + 1
                    ic.setSelection(newPos, newPos)
                    savePosition(newPos)
                }
                true
            }
            else -> false // UP/DOWN not supported for character movement
        }
    }

    /**
     * Move cursor by one word
     */
    private fun moveByWord(
        ic: InputConnection,
        direction: Direction,
        select: Boolean
    ): Boolean {
        val textBefore = ic.getTextBeforeCursor(MAX_TEXT_BEFORE, 0)?.toString() ?: ""
        val textAfter = ic.getTextAfterCursor(MAX_TEXT_AFTER, 0)?.toString() ?: ""

        return when (direction) {
            Direction.LEFT -> {
                val distance = findPreviousWordBoundary(textBefore)
                if (distance > 0) {
                    if (select) {
                        val start = getSelectionStart(ic) - distance
                        val end = getSelectionEnd(ic)
                        ic.setSelection(start, end)
                    } else {
                        val newPos = getCursorPosition(ic) - distance
                        ic.setSelection(newPos, newPos)
                        savePosition(newPos)
                    }
                    true
                } else {
                    false
                }
            }
            Direction.RIGHT -> {
                val distance = findNextWordBoundary(textAfter)
                if (distance > 0) {
                    if (select) {
                        val start = getSelectionStart(ic)
                        val end = getSelectionEnd(ic) + distance
                        ic.setSelection(start, end)
                    } else {
                        val newPos = getCursorPosition(ic) + distance
                        ic.setSelection(newPos, newPos)
                        savePosition(newPos)
                    }
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    /**
     * Move cursor to start or end of line
     */
    private fun moveToLineEdge(
        ic: InputConnection,
        direction: Direction,
        select: Boolean
    ): Boolean {
        val textBefore = ic.getTextBeforeCursor(MAX_TEXT_BEFORE, 0)?.toString() ?: ""
        val textAfter = ic.getTextAfterCursor(MAX_TEXT_AFTER, 0)?.toString() ?: ""

        return when (direction) {
            Direction.LEFT -> {
                // Move to start of line
                val newlineIndex = textBefore.lastIndexOf('\n')
                val distance = if (newlineIndex >= 0) {
                    textBefore.length - newlineIndex - 1
                } else {
                    textBefore.length
                }

                if (select) {
                    val start = getSelectionStart(ic) - distance
                    val end = getSelectionEnd(ic)
                    ic.setSelection(start, end)
                } else {
                    val newPos = getCursorPosition(ic) - distance
                    ic.setSelection(newPos, newPos)
                    savePosition(newPos)
                }
                true
            }
            Direction.RIGHT -> {
                // Move to end of line
                val newlineIndex = textAfter.indexOf('\n')
                val distance = if (newlineIndex >= 0) {
                    newlineIndex
                } else {
                    textAfter.length
                }

                if (select) {
                    val start = getSelectionStart(ic)
                    val end = getSelectionEnd(ic) + distance
                    ic.setSelection(start, end)
                } else {
                    val newPos = getCursorPosition(ic) + distance
                    ic.setSelection(newPos, newPos)
                    savePosition(newPos)
                }
                true
            }
            else -> false
        }
    }

    /**
     * Move cursor to start or end of document
     */
    private fun moveToDocumentEdge(
        ic: InputConnection,
        direction: Direction,
        select: Boolean
    ): Boolean {
        return when (direction) {
            Direction.LEFT -> {
                // Move to start of document
                if (select) {
                    ic.setSelection(0, getSelectionEnd(ic))
                } else {
                    ic.setSelection(0, 0)
                    savePosition(0)
                }
                true
            }
            Direction.RIGHT -> {
                // Move to end of document
                // Get all text to find document length
                val textBefore = ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.toString() ?: ""
                val textAfter = ic.getTextAfterCursor(Int.MAX_VALUE, 0)?.toString() ?: ""
                val endPos = textBefore.length + textAfter.length

                if (select) {
                    ic.setSelection(getSelectionStart(ic), endPos)
                } else {
                    ic.setSelection(endPos, endPos)
                    savePosition(endPos)
                }
                true
            }
            else -> false
        }
    }

    /**
     * Find previous word boundary
     * @return number of characters to move left
     */
    private fun findPreviousWordBoundary(textBefore: String): Int {
        if (textBefore.isEmpty()) return 0

        var pos = textBefore.length - 1

        // Skip trailing whitespace/separators
        while (pos >= 0 && textBefore[pos] in WORD_SEPARATORS) {
            pos--
        }

        // Skip word characters
        while (pos >= 0 && textBefore[pos] !in WORD_SEPARATORS) {
            pos--
        }

        return textBefore.length - pos - 1
    }

    /**
     * Find next word boundary
     * @return number of characters to move right
     */
    private fun findNextWordBoundary(textAfter: String): Int {
        if (textAfter.isEmpty()) return 0

        var pos = 0

        // Skip leading whitespace/separators
        while (pos < textAfter.length && textAfter[pos] in WORD_SEPARATORS) {
            pos++
        }

        // Skip word characters
        while (pos < textAfter.length && textAfter[pos] !in WORD_SEPARATORS) {
            pos++
        }

        return pos
    }

    /**
     * Get current cursor position
     */
    private fun getCursorPosition(ic: InputConnection): Int {
        val textBefore = ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.toString() ?: ""
        return textBefore.length
    }

    /**
     * Get selection start position
     */
    private fun getSelectionStart(ic: InputConnection): Int {
        // InputConnection doesn't expose selection directly
        // We approximate based on text before/after cursor
        val textBefore = ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.toString() ?: ""
        return textBefore.length
    }

    /**
     * Get selection end position
     */
    private fun getSelectionEnd(ic: InputConnection): Int {
        val selectedText = ic.getSelectedText(0)?.toString()
        return if (selectedText != null) {
            getSelectionStart(ic) + selectedText.length
        } else {
            getSelectionStart(ic)
        }
    }

    /**
     * Select all text
     */
    fun selectAll(ic: InputConnection?): Boolean {
        if (ic == null) return false

        ic.performContextMenuAction(android.R.id.selectAll)
        return true
    }

    /**
     * Select word at cursor
     */
    fun selectWord(ic: InputConnection?): Boolean {
        if (ic == null) return false

        val textBefore = ic.getTextBeforeCursor(MAX_TEXT_BEFORE, 0)?.toString() ?: ""
        val textAfter = ic.getTextAfterCursor(MAX_TEXT_AFTER, 0)?.toString() ?: ""

        // Find word boundaries
        val distanceBefore = findPreviousWordBoundary(textBefore)
        val distanceAfter = findNextWordBoundary(textAfter)

        // Set selection
        val start = getCursorPosition(ic) - distanceBefore
        val end = getCursorPosition(ic) + distanceAfter
        ic.setSelection(start, end)

        return true
    }

    /**
     * Select line at cursor
     */
    fun selectLine(ic: InputConnection?): Boolean {
        if (ic == null) return false

        val textBefore = ic.getTextBeforeCursor(MAX_TEXT_BEFORE, 0)?.toString() ?: ""
        val textAfter = ic.getTextAfterCursor(MAX_TEXT_AFTER, 0)?.toString() ?: ""

        // Find line boundaries
        val newlineIndexBefore = textBefore.lastIndexOf('\n')
        val newlineIndexAfter = textAfter.indexOf('\n')

        val distanceBefore = if (newlineIndexBefore >= 0) {
            textBefore.length - newlineIndexBefore - 1
        } else {
            textBefore.length
        }

        val distanceAfter = if (newlineIndexAfter >= 0) {
            newlineIndexAfter
        } else {
            textAfter.length
        }

        // Set selection
        val start = getCursorPosition(ic) - distanceBefore
        val end = getCursorPosition(ic) + distanceAfter
        ic.setSelection(start, end)

        return true
    }

    /**
     * Clear selection (collapse to cursor)
     */
    fun clearSelection(ic: InputConnection?): Boolean {
        if (ic == null) return false

        val cursorPos = getCursorPosition(ic)
        ic.setSelection(cursorPos, cursorPos)
        return true
    }

    /**
     * Jump to specific position
     */
    fun jumpToPosition(ic: InputConnection?, position: Int): Boolean {
        if (ic == null) return false

        ic.setSelection(position, position)
        savePosition(position)
        return true
    }

    /**
     * Save cursor position to history
     */
    private fun savePosition(position: Int) {
        // Remove any positions after current index (when jumping back and then making new movement)
        if (historyIndex < positionHistory.size - 1) {
            positionHistory.subList(historyIndex + 1, positionHistory.size).clear()
        }

        // Add new position
        positionHistory.add(position)
        historyIndex = positionHistory.size - 1

        // Limit history size
        if (positionHistory.size > maxHistorySize) {
            positionHistory.removeAt(0)
            historyIndex--
        }
    }

    /**
     * Undo cursor movement
     */
    fun undoCursorMovement(ic: InputConnection?): Boolean {
        if (ic == null || historyIndex <= 0) return false

        historyIndex--
        val previousPosition = positionHistory[historyIndex]
        ic.setSelection(previousPosition, previousPosition)
        return true
    }

    /**
     * Redo cursor movement
     */
    fun redoCursorMovement(ic: InputConnection?): Boolean {
        if (ic == null || historyIndex >= positionHistory.size - 1) return false

        historyIndex++
        val nextPosition = positionHistory[historyIndex]
        ic.setSelection(nextPosition, nextPosition)
        return true
    }

    /**
     * Clear cursor position history
     */
    fun clearHistory() {
        positionHistory.clear()
        historyIndex = -1
    }

    /**
     * Get cursor position statistics
     */
    fun getStats(): String {
        return """
            Cursor Movement Statistics:
            - History size: ${positionHistory.size}
            - Current index: $historyIndex
            - Can undo: ${historyIndex > 0}
            - Can redo: ${historyIndex < positionHistory.size - 1}
        """.trimIndent()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        clearHistory()
    }
}
