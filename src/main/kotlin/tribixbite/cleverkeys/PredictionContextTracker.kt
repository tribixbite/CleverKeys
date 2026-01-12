package tribixbite.cleverkeys

/**
 * Tracks typing context for word predictions.
 *
 * Maintains state about:
 * - Current partial word being typed
 * - Previous words for context (n-gram support)
 * - Whether last input was a swipe or tap
 * - Last auto-inserted word (for smart deletion)
 * - Source of last committed text (for context-aware deletion)
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.342).
 */
class PredictionContextTracker {
    companion object {
        private const val TAG = "PredictionContextTracker"

        // Maximum number of previous words to track for context
        private const val MAX_CONTEXT_WORDS = 2
    }

    // Current partial word being typed (not yet committed to input)
    // Example: User types "hel" → currentWord = "hel"
    private val currentWord = StringBuilder()

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
        contextWords.clear()
        wasLastInputSwipeFlag = false
        lastAutoInsertedWord = null
        lastCommitSource = PredictionSource.UNKNOWN
        lastAutocorrectOriginalWord = null
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
        return "PredictionContextTracker{currentWord='${getCurrentWord()}', contextWords=$contextWords, " +
            "wasSwipe=$wasLastInputSwipeFlag, lastAutoInsert='$lastAutoInsertedWord', lastSource=$lastCommitSource, " +
            "autocorrectOriginal='$lastAutocorrectOriginalWord'}"
    }
}
