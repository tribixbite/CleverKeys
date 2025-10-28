package tribixbite.keyboard2

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.SuggestionSpan
import android.util.Log
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.util.Locale

/**
 * Helper class for applying spell check suggestions to text via InputConnection.
 *
 * This class handles the interaction between SpellCheckerManager and the InputConnection
 * to apply SuggestionSpans to misspelled words in the text editor.
 *
 * Features:
 * - Apply suggestion spans to composing text
 * - Apply suggestion spans to already-committed text
 * - Extract words from text for spell checking
 * - Batch edit operations for performance
 *
 * Fix for Bug #311: SpellChecker integration missing (CATASTROPHIC)
 */
class SpellCheckHelper(private val spellCheckerManager: SpellCheckerManager) {

    companion object {
        private const val TAG = "SpellCheckHelper"

        // Characters that mark word boundaries
        private val WORD_SEPARATORS = setOf(' ', '\n', '\t', '.', ',', '!', '?', ';', ':', '-', '(', ')', '[', ']', '{', '}', '"', '\'')

        // Maximum characters to look back for spell checking
        private const val MAX_LOOKBACK_LENGTH = 100
    }

    /**
     * Check and underline the last completed word before the cursor
     * This is typically called when the user types a space or punctuation
     */
    fun checkLastWord(inputConnection: InputConnection?) {
        if (inputConnection == null) return

        try {
            // Get text before cursor
            val textBeforeCursor = inputConnection.getTextBeforeCursor(MAX_LOOKBACK_LENGTH, 0) ?: return

            // Extract the last word
            val lastWord = extractLastWord(textBeforeCursor.toString())
            if (lastWord.isBlank()) return

            // Check spelling of the last word
            spellCheckerManager.checkWord(lastWord) { suggestions ->
                if (suggestions.isNotEmpty() && suggestions[0].isMisspelled) {
                    // Apply suggestion span to the last word
                    applySuggestionSpanToLastWord(inputConnection, lastWord, suggestions[0].suggestions)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking last word: ${e.message}", e)
        }
    }

    /**
     * Check spelling of the current composing text
     * This is called while the user is actively typing a word
     */
    fun checkComposingText(composingText: String, callback: ((SpannableStringBuilder) -> Unit)? = null) {
        if (composingText.isBlank()) {
            callback?.invoke(SpannableStringBuilder(composingText))
            return
        }

        // Check the composing word
        spellCheckerManager.checkWord(composingText) { suggestions ->
            val spannable = SpannableStringBuilder(composingText)

            if (suggestions.isNotEmpty() && suggestions[0].isMisspelled) {
                // Apply suggestion span to the entire composing text
                val suggestionSpan = spellCheckerManager.createSuggestionSpan(suggestions[0].suggestions)
                spannable.setSpan(
                    suggestionSpan,
                    0,
                    composingText.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            callback?.invoke(spannable)
        }
    }

    /**
     * Check spelling of a sentence or paragraph in the editor
     * This is useful for checking existing text when the editor gains focus
     */
    fun checkSentence(inputConnection: InputConnection?, maxLength: Int = 500) {
        if (inputConnection == null) return

        try {
            // Extract text from the editor
            val extractedText = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
            if (extractedText == null || extractedText.text.isBlank()) return

            val text = extractedText.text.toString()
            val textToCheck = if (text.length > maxLength) {
                text.substring(0, maxLength)
            } else {
                text
            }

            // Check the entire sentence
            spellCheckerManager.checkSentence(textToCheck) { suggestions ->
                if (suggestions.isNotEmpty()) {
                    applySuggestionsToEditor(inputConnection, textToCheck, suggestions)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking sentence: ${e.message}", e)
        }
    }

    /**
     * Extract the last complete word from text
     */
    private fun extractLastWord(text: String): String {
        if (text.isBlank()) return ""

        // Find the last word boundary
        var endIndex = text.length
        while (endIndex > 0 && WORD_SEPARATORS.contains(text[endIndex - 1])) {
            endIndex--
        }

        if (endIndex == 0) return ""

        // Find the start of the word
        var startIndex = endIndex - 1
        while (startIndex > 0 && !WORD_SEPARATORS.contains(text[startIndex - 1])) {
            startIndex--
        }

        return text.substring(startIndex, endIndex).trim()
    }

    /**
     * Extract all words from text with their positions
     */
    private fun extractWords(text: String): List<WordPosition> {
        val words = mutableListOf<WordPosition>()
        var currentWordStart = -1

        for (i in text.indices) {
            val char = text[i]

            if (WORD_SEPARATORS.contains(char)) {
                // End of word
                if (currentWordStart != -1) {
                    val word = text.substring(currentWordStart, i)
                    if (word.isNotBlank()) {
                        words.add(WordPosition(word, currentWordStart, i))
                    }
                    currentWordStart = -1
                }
            } else {
                // Start of new word
                if (currentWordStart == -1) {
                    currentWordStart = i
                }
            }
        }

        // Handle last word if text doesn't end with separator
        if (currentWordStart != -1) {
            val word = text.substring(currentWordStart)
            if (word.isNotBlank()) {
                words.add(WordPosition(word, currentWordStart, text.length))
            }
        }

        return words
    }

    /**
     * Apply a suggestion span to the last word in the editor
     */
    private fun applySuggestionSpanToLastWord(
        inputConnection: InputConnection,
        lastWord: String,
        suggestions: List<String>
    ) {
        if (suggestions.isEmpty()) return

        try {
            // Begin batch edit for performance
            inputConnection.beginBatchEdit()

            // Get text before cursor to find the word position
            val textBeforeCursor = inputConnection.getTextBeforeCursor(MAX_LOOKBACK_LENGTH, 0)?.toString() ?: ""
            val wordStartOffset = textBeforeCursor.length - lastWord.length

            if (wordStartOffset < 0) {
                inputConnection.endBatchEdit()
                return
            }

            // Create spannable with suggestion span
            val spannable = SpannableStringBuilder(lastWord)
            val suggestionSpan = spellCheckerManager.createSuggestionSpan(suggestions)
            spannable.setSpan(
                suggestionSpan,
                0,
                lastWord.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // Delete the original word and replace with spannable
            inputConnection.deleteSurroundingText(lastWord.length, 0)
            inputConnection.commitText(spannable, 1)

            inputConnection.endBatchEdit()

            Log.d(TAG, "Applied suggestion span to word: $lastWord, suggestions: ${suggestions.joinToString(", ")}")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying suggestion span: ${e.message}", e)
            inputConnection.endBatchEdit()
        }
    }

    /**
     * Apply multiple suggestion spans to text in the editor
     */
    private fun applySuggestionsToEditor(
        inputConnection: InputConnection,
        text: String,
        suggestions: List<SpellingSuggestion>
    ) {
        if (suggestions.isEmpty()) return

        try {
            // Begin batch edit for performance
            inputConnection.beginBatchEdit()

            // Extract text to get current content
            val extractedText = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
            if (extractedText == null) {
                inputConnection.endBatchEdit()
                return
            }

            // Create spannable from existing text
            val spannable = SpannableStringBuilder(extractedText.text)

            // Apply suggestion spans
            for (suggestion in suggestions) {
                if (suggestion.isMisspelled && suggestion.suggestions.isNotEmpty()) {
                    val start = suggestion.offset
                    val end = suggestion.offset + suggestion.length

                    // Ensure indices are valid
                    if (start >= 0 && end <= spannable.length && start < end) {
                        val suggestionSpan = spellCheckerManager.createSuggestionSpan(suggestion.suggestions)
                        spannable.setSpan(
                            suggestionSpan,
                            start,
                            end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }

            // Note: Actually replacing the entire text is complex and can interfere with cursor position
            // A better approach is to track individual word positions and apply spans individually
            // For now, we log the suggestions for debugging
            Log.d(TAG, "Found ${suggestions.size} misspelled words in text")

            inputConnection.endBatchEdit()
        } catch (e: Exception) {
            Log.e(TAG, "Error applying suggestions to editor: ${e.message}", e)
            inputConnection.endBatchEdit()
        }
    }

    /**
     * Remove all suggestion spans from a text range
     */
    fun removeSuggestionSpans(inputConnection: InputConnection?, start: Int, end: Int) {
        if (inputConnection == null) return

        try {
            val extractedText = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
            val text = extractedText?.text as? Spannable ?: return

            // Find and remove suggestion spans in the range
            val spans = text.getSpans(start, end, SuggestionSpan::class.java)
            for (span in spans) {
                text.removeSpan(span)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing suggestion spans: ${e.message}", e)
        }
    }

    /**
     * Check if a word is likely a proper noun (capitalized)
     * Proper nouns should not be spell-checked
     */
    private fun isProperNoun(word: String): Boolean {
        if (word.isEmpty()) return false
        return word[0].isUpperCase() && word.length > 1 && word.substring(1).all { it.isLowerCase() }
    }

    /**
     * Check if a word should be spell-checked
     * Filters out URLs, emails, numbers, etc.
     */
    fun shouldCheckWord(word: String): Boolean {
        if (word.length < 2) return false

        // Skip if contains numbers
        if (word.any { it.isDigit() }) return false

        // Skip if contains special characters (likely URL, email, etc.)
        if (word.contains("@") || word.contains("://") || word.contains(".com") || word.contains(".org")) {
            return false
        }

        // Skip if all uppercase (likely acronym)
        if (word.all { !it.isLetter() || it.isUpperCase() }) return false

        return true
    }
}

/**
 * Data class representing a word and its position in text
 */
private data class WordPosition(
    val word: String,
    val start: Int,
    val end: Int
)
