package tribixbite.cleverkeys

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.text.Normalizer

/**
 * Tracks typing context for word predictions.
 *
 * Maintains state about:
 * - Current partial word being typed (prefix before cursor)
 * - Current word suffix (after cursor, for mid-word editing)
 * - Previous words for context (n-gram support)
 * - Whether last input was a swipe or tap
 * - Last auto-inserted word (for smart deletion)
 * - Source of last committed text (for context-aware deletion)
 *
 * v1.2.6: Added cursor-aware prediction support for mid-word editing.
 * When cursor moves within existing text, synchronizeWithCursor() reads
 * the actual text and rebuilds prefix/suffix state for proper prediction
 * and deletion behavior.
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.342).
 */
class PredictionContextTracker {
    companion object {
        private const val TAG = "PredictionContextTracker"

        // Maximum number of previous words to track for context
        private const val MAX_CONTEXT_WORDS = 2

        // Maximum chars to read from InputConnection for word detection
        private const val MAX_TEXT_READ = 50

        // CJK Unicode scripts that don't use space-based word boundaries
        private val CJK_SCRIPTS = setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.HANGUL
        )

        // Word boundary characters (not including apostrophe, which is context-dependent)
        private val WORD_BOUNDARIES = setOf(
            ' ', '\t', '\n', '\r',           // Whitespace
            '.', ',', ';', ':', '!', '?',    // Sentence punctuation
            '(', ')', '[', ']', '{', '}',    // Brackets
            '"', '"', '"', '„', '«', '»',    // Quotation marks
            '-', '—', '–',                   // Dashes (word-breaking)
            '/', '\\', '@', '#', '$', '%',   // Symbols
            '&', '*', '+', '=', '<', '>',    // Math/logic
            '|', '~', '`', '^'               // Technical
        )
    }

    // Current partial word being typed (not yet committed to input)
    // Example: User types "hel" → currentWord = "hel"
    // v1.2.6: This is the PREFIX (chars before cursor)
    private val currentWord = StringBuilder()

    // v1.2.6: Word suffix (chars after cursor when editing mid-word)
    // Example: User moves cursor to "hel|lo" → suffix = "lo"
    private val currentWordSuffix = StringBuilder()

    // v1.2.6: Raw text for deletion (preserves accents/diacritics for accurate char count)
    // Normalized text is used for prediction lookup, raw for deletion
    private var rawPrefixForDeletion: String = ""
    private var rawSuffixForDeletion: String = ""

    // v1.2.6: Flag to skip cursor sync during programmatic text changes
    // Set to true before deleteSurroundingText/commitText, reset after onUpdateSelection
    @Volatile
    var expectingSelectionUpdate = false

    // v1.2.6: Track if current prefix/suffix came from cursor sync (vs typing)
    // Used to determine deletion behavior in onSuggestionSelected
    private var wasSyncedFromCursor = false

    // Previous completed words for context (n-gram prediction)
    // Example: ["the", "quick"] for predicting next word
    // Limited to MAX_CONTEXT_WORDS (currently 2) for bigram support
    private val contextWords = mutableListOf<String>()

    // Track if last input was a swipe gesture (vs tap typing)
    // Used for context-aware deletion and prediction selection
    private var wasLastInputSwipeFlag = false

    // Last word that was auto-inserted by prediction system
    // Used for smart deletion: if user taps suggestion, we can delete it cleanly
    private var lastAutoInsertedWord: String? = null

    // Source of last committed text (swipe, typing, candidate selection, etc.)
    // Used for context-aware deletion behavior
    private var lastCommitSource = PredictionSource.UNKNOWN

    // Original word that was autocorrected (for undo functionality)
    // When user types "subkeys" and it's autocorrected to "surveys",
    // this stores "subkeys" so tapping it can replace "surveys" with "subkeys"
    private var lastAutocorrectOriginalWord: String? = null

    /**
     * Appends text to the current partial word.
     * Used when user types individual characters.
     *
     * @param text Text to append (usually single character)
     *
     * Example:
     * - appendToCurrentWord("h") → currentWord = "h"
     * - appendToCurrentWord("e") → currentWord = "he"
     * - appendToCurrentWord("l") → currentWord = "hel"
     */
    fun appendToCurrentWord(text: String) {
        currentWord.append(text)
    }

    /**
     * Gets the current partial word being typed.
     *
     * @return Current word string (never null, may be empty)
     */
    fun getCurrentWord(): String {
        return currentWord.toString()
    }

    /**
     * Gets the length of current partial word.
     * Useful for checking if user is currently typing.
     *
     * @return Number of characters in current word
     */
    fun getCurrentWordLength(): Int {
        return currentWord.length
    }

    /**
     * Clears the current partial word.
     * Called when word is completed or prediction is selected.
     */
    fun clearCurrentWord() {
        currentWord.setLength(0)
    }

    /**
     * Commits a completed word and updates context.
     *
     * This method:
     * 1. Adds word to context history (for n-gram predictions)
     * 2. Maintains max context size (removes oldest if needed)
     * 3. Clears current partial word
     * 4. Tracks the source and auto-insert status
     *
     * @param word Completed word to commit
     * @param source Source of the word (swipe, typing, candidate, etc.)
     * @param autoInserted Whether this word was auto-inserted by prediction
     */
    fun commitWord(word: String, source: PredictionSource, autoInserted: Boolean) {
        // Update context for n-gram predictions
        contextWords.add(word.lowercase())

        // Maintain max context size (oldest words removed first)
        while (contextWords.size > MAX_CONTEXT_WORDS) {
            contextWords.removeAt(0)
        }

        // Clear current word (it's now committed)
        clearCurrentWord()

        // Track for smart deletion
        lastCommitSource = source
        lastAutoInsertedWord = if (autoInserted) word else null
    }

    /**
     * Gets the context words for prediction.
     * Returns a copy to prevent external modification.
     *
     * @return List of previous words (max MAX_CONTEXT_WORDS)
     */
    fun getContextWords(): List<String> {
        return contextWords.toList()
    }

    /**
     * Sets whether the last input was a swipe gesture.
     *
     * @param wasSwipe true if last input was swipe, false if tap typing
     */
    fun setWasLastInputSwipe(wasSwipe: Boolean) {
        wasLastInputSwipeFlag = wasSwipe
    }

    /**
     * Checks if the last input was a swipe gesture.
     *
     * @return true if last input was swipe, false if tap typing
     */
    fun wasLastInputSwipe(): Boolean {
        return wasLastInputSwipeFlag
    }

    /**
     * Gets the last auto-inserted word.
     * Used for smart deletion: if user taps backspace after auto-insert,
     * we can delete the entire word + space.
     *
     * @return Last auto-inserted word, or null if none
     */
    fun getLastAutoInsertedWord(): String? {
        return lastAutoInsertedWord
    }

    /**
     * Clears the last auto-inserted word tracking.
     * Called after word is deleted or new input begins.
     */
    fun clearLastAutoInsertedWord() {
        lastAutoInsertedWord = null
    }

    /**
     * Sets the last auto-inserted word.
     * Used in special cases where auto-insertion happens outside commitWord().
     *
     * @param word The word that was auto-inserted
     */
    fun setLastAutoInsertedWord(word: String) {
        lastAutoInsertedWord = word
    }

    /**
     * Gets the source of the last committed text.
     *
     * @return PredictionSource enum value
     */
    fun getLastCommitSource(): PredictionSource {
        return lastCommitSource
    }

    /**
     * Sets the source of the last committed text.
     *
     * @param source PredictionSource enum value
     */
    fun setLastCommitSource(source: PredictionSource) {
        lastCommitSource = source
    }

    /**
     * Gets the original word before autocorrect was applied.
     * Used for autocorrect undo: when user taps the original word in suggestions,
     * we can detect it and replace the corrected word.
     *
     * @return Original word before autocorrect, or null if no autocorrect occurred
     */
    fun getLastAutocorrectOriginalWord(): String? {
        return lastAutocorrectOriginalWord
    }

    /**
     * Sets the original word before autocorrect.
     * Called when autocorrect replaces a typed word.
     *
     * @param word The original word that was autocorrected
     */
    fun setLastAutocorrectOriginalWord(word: String?) {
        lastAutocorrectOriginalWord = word
    }

    /**
     * Clears autocorrect tracking.
     * Called when autocorrect undo is performed or new word is started.
     */
    fun clearAutocorrectTracking() {
        lastAutocorrectOriginalWord = null
    }

    /**
     * Clears all tracking state.
     * Useful for resetting state when switching input fields.
     */
    fun clearAll() {
        clearCurrentWord()
        clearCurrentWordSuffix()
        contextWords.clear()
        wasLastInputSwipeFlag = false
        lastAutoInsertedWord = null
        lastCommitSource = PredictionSource.UNKNOWN
        lastAutocorrectOriginalWord = null
        rawPrefixForDeletion = ""
        rawSuffixForDeletion = ""
        wasSyncedFromCursor = false
        expectingSelectionUpdate = false
    }

    /**
     * Deletes the last character from the current word.
     * Used when user taps backspace during typing.
     * Does nothing if current word is empty.
     */
    fun deleteLastChar() {
        if (currentWord.isNotEmpty()) {
            currentWord.deleteCharAt(currentWord.length - 1)
        }
    }

    /**
     * Gets a debug string showing current state.
     * Useful for logging and troubleshooting.
     *
     * @return Human-readable state description
     */
    fun getDebugState(): String {
        return "PredictionContextTracker{prefix='${getCurrentWord()}', suffix='${getCurrentWordSuffix()}', " +
            "contextWords=$contextWords, wasSwipe=$wasLastInputSwipeFlag, " +
            "lastAutoInsert='$lastAutoInsertedWord', lastSource=$lastCommitSource, " +
            "autocorrectOriginal='$lastAutocorrectOriginalWord', wasSynced=$wasSyncedFromCursor}"
    }

    // ==================== v1.2.6: Cursor-Aware Prediction Methods ====================

    /**
     * Gets the current word suffix (chars after cursor when editing mid-word).
     *
     * @return Suffix string (may be empty if cursor at end of word)
     */
    fun getCurrentWordSuffix(): String {
        return currentWordSuffix.toString()
    }

    /**
     * Gets the length of current word suffix.
     *
     * @return Number of characters after cursor in current word
     */
    fun getCurrentWordSuffixLength(): Int {
        return currentWordSuffix.length
    }

    /**
     * Clears the current word suffix.
     * Called when prediction is selected or word is completed.
     */
    fun clearCurrentWordSuffix() {
        currentWordSuffix.setLength(0)
    }

    /**
     * Returns the number of characters to delete for prediction selection.
     * When cursor is mid-word, returns both prefix and suffix lengths.
     *
     * @return Pair of (beforeCursor, afterCursor) delete counts
     */
    fun getCharsToDeleteForPrediction(): Pair<Int, Int> {
        return Pair(rawPrefixForDeletion.length, rawSuffixForDeletion.length)
    }

    /**
     * Checks if current state was synchronized from cursor position.
     * Used to determine if we need dual-side deletion in onSuggestionSelected.
     *
     * @return true if prefix/suffix came from cursor sync, false if from typing
     */
    fun wasSyncedFromCursor(): Boolean {
        return wasSyncedFromCursor
    }

    /**
     * Synchronizes prediction context with actual cursor position in input field.
     * Called when cursor moves (tap, arrow keys, cut/paste) to read the word
     * surrounding the new cursor position.
     *
     * This method:
     * 1. Reads text before and after cursor from InputConnection
     * 2. Extracts the word prefix (before cursor) and suffix (after cursor)
     * 3. Updates currentWord and currentWordSuffix
     * 4. Tracks raw text for accurate deletion character counts
     *
     * @param ic InputConnection to read text from
     * @param language Primary language code (for CJK detection)
     * @param editorInfo Editor info for input type checks
     */
    fun synchronizeWithCursor(
        ic: InputConnection?,
        language: String = "en",
        editorInfo: EditorInfo? = null
    ) {
        ic ?: return

        // Skip sync during programmatic text changes
        if (expectingSelectionUpdate) {
            expectingSelectionUpdate = false
            return
        }

        // Skip for input types where prediction is inappropriate
        if (!shouldSyncForInputType(editorInfo)) return

        // Skip for CJK text (no space-based word boundaries)
        if (isCJKLanguage(language)) return

        val beforeText = ic.getTextBeforeCursor(MAX_TEXT_READ, 0)?.toString() ?: ""
        val afterText = ic.getTextAfterCursor(MAX_TEXT_READ, 0)?.toString() ?: ""

        // Check if we're in CJK text based on surrounding chars
        if (containsCJKCharacters(beforeText) || containsCJKCharacters(afterText)) {
            clearCurrentWord()
            clearCurrentWordSuffix()
            rawPrefixForDeletion = ""
            rawSuffixForDeletion = ""
            wasSyncedFromCursor = false
            return
        }

        // Extract word prefix (before cursor)
        val (normalizedPrefix, rawPrefix) = extractWordPrefix(beforeText)
        // Extract word suffix (after cursor)
        val (normalizedSuffix, rawSuffix) = extractWordSuffix(afterText)

        // Update state
        currentWord.clear()
        currentWord.append(normalizedPrefix)
        currentWordSuffix.clear()
        currentWordSuffix.append(normalizedSuffix)
        rawPrefixForDeletion = rawPrefix
        rawSuffixForDeletion = rawSuffix
        wasSyncedFromCursor = true

        // NOTE: Do NOT clear autocorrect tracking here!
        // Autocorrect tracking must persist after cursor moves (e.g., after space insertion)
        // so the suggestion bar can still show the original word for undo/add-to-dictionary.
        // Autocorrect tracking is cleared separately when user starts typing a new word.

        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            android.util.Log.d(TAG, "synchronizeWithCursor: prefix='$normalizedPrefix', suffix='$normalizedSuffix', " +
                "rawPrefix='$rawPrefix', rawSuffix='$rawSuffix'")
        }
    }

    /**
     * Resets the cursor sync flag.
     * Called when user starts typing normally (not from cursor movement).
     */
    fun resetCursorSyncState() {
        wasSyncedFromCursor = false
        rawSuffixForDeletion = ""
        currentWordSuffix.setLength(0)
    }

    /**
     * Extracts the word prefix from text before cursor.
     * Walks backwards from end of text until a word boundary is found.
     *
     * @param beforeText Text before cursor
     * @return Pair of (normalizedPrefix, rawPrefix) - normalized for lookup, raw for deletion
     */
    private fun extractWordPrefix(beforeText: String): Pair<String, String> {
        if (beforeText.isEmpty()) return Pair("", "")

        var startIndex = beforeText.length
        for (i in beforeText.length - 1 downTo 0) {
            val char = beforeText[i]
            if (isWordChar(char, beforeText, i)) {
                startIndex = i
            } else {
                break
            }
        }

        val rawPrefix = beforeText.substring(startIndex)
        val normalizedPrefix = normalizeForPrediction(rawPrefix)
        return Pair(normalizedPrefix, rawPrefix)
    }

    /**
     * Extracts the word suffix from text after cursor.
     * Walks forwards from start of text until a word boundary is found.
     *
     * @param afterText Text after cursor
     * @return Pair of (normalizedSuffix, rawSuffix) - normalized for lookup, raw for deletion
     */
    private fun extractWordSuffix(afterText: String): Pair<String, String> {
        if (afterText.isEmpty()) return Pair("", "")

        var endIndex = 0
        for (i in afterText.indices) {
            val char = afterText[i]
            if (isWordChar(char, afterText, i)) {
                endIndex = i + 1
            } else {
                break
            }
        }

        val rawSuffix = afterText.substring(0, endIndex)
        val normalizedSuffix = normalizeForPrediction(rawSuffix)
        return Pair(normalizedSuffix, rawSuffix)
    }

    /**
     * Checks if a character is part of a word (not a boundary).
     * Handles apostrophes specially: they're word chars only when between letters.
     *
     * @param char Character to check
     * @param text Full text for context (apostrophe checking)
     * @param pos Position of char in text
     * @return true if char is part of a word
     */
    private fun isWordChar(char: Char, text: String, pos: Int): Boolean {
        // Letters are always word characters
        if (char.isLetter()) return true

        // Digits break words (test|123 → prefix="test", suffix="")
        if (char.isDigit()) return false

        // Apostrophe is word char only when between letters (for contractions)
        // Examples: don't, l'homme, dell'anno
        // Check straight apostrophe (') and curly quotes (' U+2019, ' U+2018)
        if (char == '\'' || char == '\u2019' || char == '\u2018') {
            val before = text.getOrNull(pos - 1)
            val after = text.getOrNull(pos + 1)
            return before?.isLetter() == true && after?.isLetter() == true
        }

        // Check against explicit word boundaries
        return char !in WORD_BOUNDARIES && !char.isWhitespace()
    }

    /**
     * Normalizes text for prediction lookup.
     * Removes diacritics/accents so "café" matches "cafe" in dictionary.
     *
     * @param text Text to normalize
     * @return Normalized text (lowercase, no diacritics)
     */
    private fun normalizeForPrediction(text: String): String {
        // NFD decomposition separates base chars from combining diacritics
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        // Remove combining diacritical marks (Unicode category Mn)
        return normalized.replace(Regex("\\p{Mn}"), "").lowercase()
    }

    /**
     * Checks if sync should be skipped for this input type.
     * Skip for passwords, URLs, emails, and other non-predictable fields.
     *
     * @param editorInfo Editor info with input type
     * @return true if sync is appropriate, false to skip
     */
    private fun shouldSyncForInputType(editorInfo: EditorInfo?): Boolean {
        editorInfo ?: return true

        val inputType = editorInfo.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        // Skip password fields
        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return false

                // Skip URL/email (special character patterns)
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return false
            }
        }

        // Skip number input
        if (inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE) {
            return false
        }

        return true
    }

    /**
     * Checks if language uses CJK script (no space-based word boundaries).
     *
     * @param language Language code (e.g., "zh", "ja", "ko", "th")
     * @return true if language is CJK/Thai
     */
    private fun isCJKLanguage(language: String): Boolean {
        val langLower = language.lowercase()
        return langLower.startsWith("zh") ||  // Chinese
               langLower.startsWith("ja") ||  // Japanese
               langLower.startsWith("ko") ||  // Korean
               langLower.startsWith("th")     // Thai
    }

    /**
     * Checks if text contains CJK characters.
     * Used as secondary check even when language setting is non-CJK.
     *
     * @param text Text to check
     * @return true if text contains CJK characters
     */
    private fun containsCJKCharacters(text: String): Boolean {
        return text.any { char ->
            try {
                Character.UnicodeScript.of(char.code) in CJK_SCRIPTS
            } catch (e: Exception) {
                false
            }
        }
    }
}
