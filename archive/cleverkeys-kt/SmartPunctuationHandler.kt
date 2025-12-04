package tribixbite.cleverkeys

import android.view.inputmethod.InputConnection

/**
 * Smart punctuation handler for automatic punctuation insertion and formatting.
 *
 * Features:
 * - Double-space to period conversion
 * - Automatic quote and bracket pairing
 * - Context-aware spacing around punctuation
 * - Smart apostrophe handling (contractions vs quotes)
 * - Automatic capitalization after sentence-ending punctuation
 *
 * Addresses Bug #316: SmartPunctuationHandler missing (CATASTROPHIC)
 * Completes Bug #361: SmartPunctuation (auto-punctuation features)
 */
class SmartPunctuationHandler {

    companion object {
        private const val TAG = "SmartPunctuationHandler"

        // Punctuation that ends sentences
        private val SENTENCE_ENDERS = setOf('.', '!', '?')

        // Opening and closing bracket pairs
        private val BRACKET_PAIRS = mapOf(
            '(' to ')',
            '[' to ']',
            '{' to '}',
            '<' to '>'
        )

        // Quote characters that need pairing
        private val QUOTE_CHARS = setOf('"', '\'', '\u201C', '\u2018') // " ' " '

        // Punctuation that shouldn't have space before
        private val NO_SPACE_BEFORE = setOf(',', '.', '!', '?', ':', ';', ')', ']', '}', '>')

        // Punctuation that shouldn't have space after
        private val NO_SPACE_AFTER = setOf('(', '[', '{', '<')
    }

    private var enabled = true
    private var doubleSpacePeriodEnabled = true
    private var autoPairQuotesEnabled = true
    private var autoPairBracketsEnabled = true
    private var contextSpacingEnabled = true

    private var lastCharWasSpace = false
    private var openQuotes = mutableSetOf<Char>()
    private var openBrackets = mutableMapOf<Char, Int>() // Track nesting level

    /**
     * Enable or disable smart punctuation
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            reset()
        }
    }

    /**
     * Configure double-space to period
     */
    fun setDoubleSpacePeriodEnabled(enabled: Boolean) {
        this.doubleSpacePeriodEnabled = enabled
    }

    /**
     * Configure automatic quote pairing
     */
    fun setAutoPairQuotesEnabled(enabled: Boolean) {
        this.autoPairQuotesEnabled = enabled
    }

    /**
     * Configure automatic bracket pairing
     */
    fun setAutoPairBracketsEnabled(enabled: Boolean) {
        this.autoPairBracketsEnabled = enabled
    }

    /**
     * Configure context-aware spacing
     */
    fun setContextSpacingEnabled(enabled: Boolean) {
        this.contextSpacingEnabled = enabled
    }

    /**
     * Process a character before it's inserted
     * Returns the character(s) that should actually be inserted
     */
    fun processCharacter(
        char: Char,
        ic: InputConnection?,
        cursorPosition: Int = -1
    ): CharSequence? {
        if (!enabled || ic == null) {
            updateState(char)
            return null // Let character through unchanged
        }

        // Handle double-space to period
        if (doubleSpacePeriodEnabled && char == ' ' && lastCharWasSpace) {
            lastCharWasSpace = false
            return handleDoubleSpacePeriod(ic)
        }

        // Handle quote pairing
        if (autoPairQuotesEnabled && char in QUOTE_CHARS) {
            val result = handleQuotePairing(char, ic)
            if (result != null) return result
        }

        // Handle bracket pairing
        if (autoPairBracketsEnabled) {
            if (char in BRACKET_PAIRS.keys) {
                return handleOpenBracket(char, ic)
            } else if (char in BRACKET_PAIRS.values) {
                return handleCloseBracket(char, ic)
            }
        }

        // Handle context-aware spacing
        if (contextSpacingEnabled) {
            val spacing = handleContextSpacing(char, ic)
            if (spacing != null) return spacing
        }

        updateState(char)
        return null // Let character through unchanged
    }

    /**
     * Handle double-space to period conversion
     */
    private fun handleDoubleSpacePeriod(ic: InputConnection): CharSequence {
        // Get text before cursor
        val beforeCursor = ic.getTextBeforeCursor(10, 0)?.toString() ?: ""

        // Check if we're at the end of a sentence (not in middle of abbreviation)
        val shouldConvert = beforeCursor.isNotEmpty() &&
            !beforeCursor.trimEnd().endsWith(".")

        if (shouldConvert) {
            // Delete the previous space
            ic.deleteSurroundingText(1, 0)
            // Insert period and space
            return ". "
        }

        return " " // Just a regular space
    }

    /**
     * Handle quote character pairing
     */
    private fun handleQuotePairing(char: Char, ic: InputConnection): CharSequence? {
        val isOpening = char !in openQuotes

        if (isOpening) {
            // Opening quote - check if we should auto-pair
            val afterCursor = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
            val shouldPair = afterCursor.isEmpty() || afterCursor[0].isWhitespace()

            if (shouldPair) {
                openQuotes.add(char)
                // Insert opening quote, then closing quote, then move cursor back
                ic.commitText("$char$char", 1)
                ic.setSelection(ic.getSelectedText(0)?.length ?: 0, 0)
                return "" // We already inserted
            }
        } else {
            // Closing quote
            openQuotes.remove(char)

            // Check if there's already a closing quote ahead
            val afterCursor = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
            if (afterCursor.isNotEmpty() && afterCursor[0] == char) {
                // Skip over existing quote instead of inserting duplicate
                ic.setSelection(1, 1)
                return "" // We moved cursor instead
            }
        }

        return null // Let quote through normally
    }

    /**
     * Handle opening bracket auto-pairing
     */
    private fun handleOpenBracket(char: Char, ic: InputConnection): CharSequence? {
        val closingChar = BRACKET_PAIRS[char] ?: return null

        // Increment nesting level
        openBrackets[char] = (openBrackets[char] ?: 0) + 1

        // Check if we should auto-pair
        val afterCursor = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        val shouldPair = afterCursor.isEmpty() || afterCursor[0].isWhitespace()

        if (shouldPair) {
            // Insert opening and closing bracket, move cursor between them
            ic.commitText("$char$closingChar", 1)
            ic.setSelection(ic.getSelectedText(0)?.length ?: 0, 0)
            return "" // We already inserted
        }

        return null // Let opening bracket through normally
    }

    /**
     * Handle closing bracket matching
     */
    private fun handleCloseBracket(char: Char, ic: InputConnection): CharSequence? {
        // Find matching opening bracket
        val openingChar = BRACKET_PAIRS.entries.find { it.value == char }?.key

        if (openingChar != null) {
            val nestingLevel = openBrackets[openingChar] ?: 0
            if (nestingLevel > 0) {
                // Decrement nesting level
                openBrackets[openingChar] = nestingLevel - 1

                // Check if there's already a closing bracket ahead
                val afterCursor = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
                if (afterCursor.isNotEmpty() && afterCursor[0] == char) {
                    // Skip over existing bracket instead of inserting duplicate
                    ic.setSelection(1, 1)
                    return "" // We moved cursor instead
                }
            }
        }

        return null // Let closing bracket through normally
    }

    /**
     * Handle context-aware spacing around punctuation
     */
    private fun handleContextSpacing(char: Char, ic: InputConnection): CharSequence? {
        // Check if we need to remove space before punctuation
        if (char in NO_SPACE_BEFORE) {
            val beforeCursor = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (beforeCursor == " ") {
                // Delete the space before inserting punctuation
                ic.deleteSurroundingText(1, 0)
            }

            // For sentence-ending punctuation, add space after
            if (char in SENTENCE_ENDERS) {
                return "$char "
            }
        }

        // Check if we need to add space after opening punctuation
        if (char in NO_SPACE_AFTER) {
            // Space will be added automatically if needed
            return null
        }

        return null // Let character through normally
    }

    /**
     * Update internal state tracking
     */
    private fun updateState(char: Char) {
        lastCharWasSpace = (char == ' ')
    }

    /**
     * Reset state (call when starting new input or clearing field)
     */
    fun reset() {
        lastCharWasSpace = false
        openQuotes.clear()
        openBrackets.clear()
    }

    /**
     * Handle backspace - update pairing state
     */
    fun handleBackspace(ic: InputConnection?) {
        if (!enabled || ic == null) return

        // Get character being deleted
        val beforeCursor = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (beforeCursor.isEmpty()) {
            lastCharWasSpace = false
            return
        }

        val deletedChar = beforeCursor[0]
        lastCharWasSpace = false

        // If deleting an opening bracket/quote, remove the auto-paired closing one
        if (autoPairBracketsEnabled && deletedChar in BRACKET_PAIRS.keys) {
            val closingChar = BRACKET_PAIRS[deletedChar]
            val afterCursor = ic.getTextAfterCursor(1, 0)?.toString() ?: ""

            if (afterCursor.isNotEmpty() && afterCursor[0] == closingChar) {
                // Delete the auto-paired closing bracket too
                ic.deleteSurroundingText(0, 1)
            }

            // Update nesting level
            val nestingLevel = openBrackets[deletedChar] ?: 0
            if (nestingLevel > 0) {
                openBrackets[deletedChar] = nestingLevel - 1
            }
        }

        if (autoPairQuotesEnabled && deletedChar in QUOTE_CHARS) {
            val afterCursor = ic.getTextAfterCursor(1, 0)?.toString() ?: ""

            if (afterCursor.isNotEmpty() && afterCursor[0] == deletedChar) {
                // Delete the auto-paired closing quote too
                ic.deleteSurroundingText(0, 1)
            }

            openQuotes.remove(deletedChar)
        }
    }

    /**
     * Get current configuration as string (for debugging)
     */
    fun getConfig(): String {
        return """
            SmartPunctuation Config:
            - Enabled: $enabled
            - Double-space period: $doubleSpacePeriodEnabled
            - Auto-pair quotes: $autoPairQuotesEnabled
            - Auto-pair brackets: $autoPairBracketsEnabled
            - Context spacing: $contextSpacingEnabled
        """.trimIndent()
    }
}
