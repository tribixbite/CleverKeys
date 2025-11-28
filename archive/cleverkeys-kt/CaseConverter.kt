package tribixbite.keyboard2

import android.view.inputmethod.InputConnection
import java.util.Locale

/**
 * Case conversion utility for text transformation.
 *
 * Features:
 * - Uppercase conversion (HELLO WORLD)
 * - Lowercase conversion (hello world)
 * - Title case conversion (Hello World)
 * - Sentence case conversion (Hello world)
 * - Camel case conversion (helloWorld)
 * - Snake case conversion (hello_world)
 * - Kebab case conversion (hello-world)
 * - Toggle case (hELLO wORLD)
 * - Smart selection-aware conversion
 * - Word boundary detection
 *
 * Addresses Bug #318: CaseConverter missing (HIGH)
 */
class CaseConverter {

    companion object {
        private const val TAG = "CaseConverter"
    }

    /**
     * Case transformation modes
     */
    enum class CaseMode {
        UPPERCASE,      // HELLO WORLD
        LOWERCASE,      // hello world
        TITLE_CASE,     // Hello World
        SENTENCE_CASE,  // Hello world
        CAMEL_CASE,     // helloWorld
        SNAKE_CASE,     // hello_world
        KEBAB_CASE,     // hello-world
        TOGGLE_CASE     // hELLO wORLD
    }

    /**
     * Convert text to specified case mode
     */
    fun convertText(text: String, mode: CaseMode, locale: Locale = Locale.getDefault()): String {
        if (text.isEmpty()) return text

        return when (mode) {
            CaseMode.UPPERCASE -> text.uppercase(locale)
            CaseMode.LOWERCASE -> text.lowercase(locale)
            CaseMode.TITLE_CASE -> toTitleCase(text, locale)
            CaseMode.SENTENCE_CASE -> toSentenceCase(text, locale)
            CaseMode.CAMEL_CASE -> toCamelCase(text, locale)
            CaseMode.SNAKE_CASE -> toSnakeCase(text, locale)
            CaseMode.KEBAB_CASE -> toKebabCase(text, locale)
            CaseMode.TOGGLE_CASE -> toggleCase(text, locale)
        }
    }

    /**
     * Convert selected text or current word in InputConnection
     * Returns true if conversion was successful
     */
    fun convertSelection(ic: InputConnection?, mode: CaseMode, locale: Locale = Locale.getDefault()): Boolean {
        if (ic == null) return false

        try {
            // First try to convert selected text
            val selectedText = ic.getSelectedText(0)?.toString()
            if (!selectedText.isNullOrEmpty()) {
                val converted = convertText(selectedText, mode, locale)
                ic.commitText(converted, 1)
                return true
            }

            // No selection - convert current word
            val beforeCursor = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
            val afterCursor = ic.getTextAfterCursor(100, 0)?.toString() ?: ""

            // Find word boundaries
            val wordBefore = extractWordBeforeCursor(beforeCursor)
            val wordAfter = extractWordAfterCursor(afterCursor)

            if (wordBefore.isEmpty() && wordAfter.isEmpty()) {
                return false // No word to convert
            }

            val fullWord = wordBefore + wordAfter
            val converted = convertText(fullWord, mode, locale)

            // Delete the word and insert converted version
            ic.deleteSurroundingText(wordBefore.length, wordAfter.length)
            ic.commitText(converted, 1)
            return true

        } catch (e: Exception) {
            logE("Failed to convert selection", e)
            return false
        }
    }

    /**
     * Convert to title case (capitalize first letter of each word)
     */
    private fun toTitleCase(text: String, locale: Locale): String {
        val words = text.split(Regex("\\s+"))
        return words.joinToString(" ") { word ->
            if (word.isEmpty()) {
                word
            } else {
                word.substring(0, 1).uppercase(locale) + word.substring(1).lowercase(locale)
            }
        }
    }

    /**
     * Convert to sentence case (capitalize first letter only)
     */
    private fun toSentenceCase(text: String, locale: Locale): String {
        if (text.isEmpty()) return text

        val sentences = text.split(Regex("([.!?]\\s+)"))
        return sentences.joinToString("") { sentence ->
            if (sentence.isEmpty()) {
                sentence
            } else {
                sentence.substring(0, 1).uppercase(locale) + sentence.substring(1).lowercase(locale)
            }
        }
    }

    /**
     * Convert to camelCase (first word lowercase, subsequent words capitalized)
     */
    private fun toCamelCase(text: String, locale: Locale): String {
        val words = text.split(Regex("[\\s_-]+"))
        if (words.isEmpty()) return text

        return words.mapIndexed { index, word ->
            if (index == 0) {
                word.lowercase(locale)
            } else if (word.isEmpty()) {
                word
            } else {
                word.substring(0, 1).uppercase(locale) + word.substring(1).lowercase(locale)
            }
        }.joinToString("")
    }

    /**
     * Convert to snake_case (lowercase with underscores)
     */
    private fun toSnakeCase(text: String, locale: Locale): String {
        // Handle camelCase and PascalCase
        val withSpaces = text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("[\\s-]+"), "_")

        return withSpaces.lowercase(locale)
    }

    /**
     * Convert to kebab-case (lowercase with hyphens)
     */
    private fun toKebabCase(text: String, locale: Locale): String {
        // Handle camelCase and PascalCase
        val withSpaces = text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("[\\s_]+"), "-")

        return withSpaces.lowercase(locale)
    }

    /**
     * Toggle case (invert each character's case)
     */
    private fun toggleCase(text: String, locale: Locale): String {
        return text.map { char ->
            when {
                char.isUpperCase() -> char.lowercase(locale)
                char.isLowerCase() -> char.uppercase(locale)
                else -> char.toString()
            }
        }.joinToString("")
    }

    /**
     * Extract word before cursor (letters, digits, and underscores)
     */
    private fun extractWordBeforeCursor(textBefore: String): String {
        val word = StringBuilder()
        for (i in textBefore.length - 1 downTo 0) {
            val char = textBefore[i]
            if (char.isLetterOrDigit() || char == '_' || char == '-') {
                word.insert(0, char)
            } else {
                break
            }
        }
        return word.toString()
    }

    /**
     * Extract word after cursor (letters, digits, and underscores)
     */
    private fun extractWordAfterCursor(textAfter: String): String {
        val word = StringBuilder()
        for (char in textAfter) {
            if (char.isLetterOrDigit() || char == '_' || char == '-') {
                word.append(char)
            } else {
                break
            }
        }
        return word.toString()
    }

    /**
     * Detect likely case mode of text
     * Useful for cycle-through case conversion
     */
    fun detectCaseMode(text: String): CaseMode? {
        if (text.isEmpty()) return null

        return when {
            text == text.uppercase() -> CaseMode.UPPERCASE
            text == text.lowercase() -> CaseMode.LOWERCASE
            text.contains('_') && text == text.lowercase() -> CaseMode.SNAKE_CASE
            text.contains('-') && text == text.lowercase() -> CaseMode.KEBAB_CASE
            isCamelCase(text) -> CaseMode.CAMEL_CASE
            isTitleCase(text) -> CaseMode.TITLE_CASE
            isSentenceCase(text) -> CaseMode.SENTENCE_CASE
            else -> null
        }
    }

    /**
     * Check if text is in camelCase format
     */
    private fun isCamelCase(text: String): Boolean {
        if (text.isEmpty()) return false
        return text[0].isLowerCase() && text.any { it.isUpperCase() } && !text.contains(' ')
    }

    /**
     * Check if text is in Title Case format
     */
    private fun isTitleCase(text: String): Boolean {
        val words = text.split(Regex("\\s+"))
        return words.all { word ->
            word.isEmpty() || (word[0].isUpperCase() && word.substring(1).all { it.isLowerCase() })
        }
    }

    /**
     * Check if text is in Sentence case format
     */
    private fun isSentenceCase(text: String): Boolean {
        if (text.isEmpty()) return false
        return text[0].isUpperCase() && text.substring(1).all { !it.isUpperCase() || !it.isLetter() }
    }

    /**
     * Cycle to next case mode
     * Useful for repeated case conversion actions
     */
    fun getNextCaseMode(currentMode: CaseMode?): CaseMode {
        return when (currentMode) {
            null, CaseMode.LOWERCASE -> CaseMode.UPPERCASE
            CaseMode.UPPERCASE -> CaseMode.TITLE_CASE
            CaseMode.TITLE_CASE -> CaseMode.SENTENCE_CASE
            CaseMode.SENTENCE_CASE -> CaseMode.CAMEL_CASE
            CaseMode.CAMEL_CASE -> CaseMode.SNAKE_CASE
            CaseMode.SNAKE_CASE -> CaseMode.KEBAB_CASE
            CaseMode.KEBAB_CASE -> CaseMode.TOGGLE_CASE
            CaseMode.TOGGLE_CASE -> CaseMode.LOWERCASE
        }
    }

    /**
     * Apply case conversion with cycle-through behavior
     * Each call cycles to the next case mode
     */
    fun cycleCaseConversion(ic: InputConnection?, locale: Locale = Locale.getDefault()): Boolean {
        if (ic == null) return false

        try {
            // Get current text
            val selectedText = ic.getSelectedText(0)?.toString()
            val text = if (!selectedText.isNullOrEmpty()) {
                selectedText
            } else {
                val beforeCursor = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
                val afterCursor = ic.getTextAfterCursor(100, 0)?.toString() ?: ""
                extractWordBeforeCursor(beforeCursor) + extractWordAfterCursor(afterCursor)
            }

            if (text.isEmpty()) return false

            // Detect current mode and get next
            val currentMode = detectCaseMode(text)
            val nextMode = getNextCaseMode(currentMode)

            // Apply conversion
            return convertSelection(ic, nextMode, locale)

        } catch (e: Exception) {
            logE("Failed to cycle case conversion", e)
            return false
        }
    }

    private fun logE(message: String, throwable: Throwable? = null) {
        android.util.Log.e(TAG, message, throwable)
    }
}
