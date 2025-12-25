package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.ml.SwipeMLData
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Handles suggestion selection, prediction display, and text completion logic.
 *
 * This class centralizes all logic related to:
 * - Suggestion bar updates and auto-insertion
 * - Prediction results from neural/typing engines
 * - Autocorrect for typing and swipe predictions
 * - Context tracking updates
 * - Text replacement and deletion (Termux-aware)
 * - Regular typing prediction updates
 *
 * Responsibilities:
 * - Display predictions in suggestion bar
 * - Auto-insert top predictions after swipe
 * - Handle manual suggestion selection
 * - Apply autocorrect to typed/predicted words
 * - Manage word deletion and replacement
 * - Update context tracker with completed words
 * - Handle Termux mode special cases
 *
 * NOT included (remains in CleverKeysService):
 * - InputMethodService lifecycle methods
 * - View creation and inflation
 * - Configuration management
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.361).
 */
class SuggestionHandler(
    private val context: Context,
    private var config: Config,
    private val contextTracker: PredictionContextTracker,
    private val predictionCoordinator: PredictionCoordinator,
    private val contractionManager: ContractionManager,
    private val keyeventhandler: KeyEventHandler
) {
    companion object {
        private const val TAG = "SuggestionHandler"
    }

    /**
     * Interface for sending debug logs to SwipeDebugActivity.
     * Implemented by CleverKeysService to bridge to its sendDebugLog method.
     */
    interface DebugLogger {
        fun sendDebugLog(message: String)
    }

    // Non-final - updated after creation
    private var suggestionBar: SuggestionBar? = null

    // Debug mode for logging
    private var debugMode = false
    private var debugLogger: DebugLogger? = null

    // Async prediction execution
    private val predictionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentPredictionTask: Future<*>? = null

    /**
     * Updates configuration.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        config = newConfig
    }

    /**
     * Sets the suggestion bar reference.
     *
     * @param suggestionBar Suggestion bar for displaying predictions
     */
    fun setSuggestionBar(suggestionBar: SuggestionBar?) {
        this.suggestionBar = suggestionBar
    }

    /**
     * Sets debug mode and logger.
     *
     * @param enabled Whether debug mode is enabled
     * @param logger Debug logger implementation
     */
    fun setDebugMode(enabled: Boolean, logger: DebugLogger?) {
        debugMode = enabled
        debugLogger = logger
    }

    /**
     * Sends a debug log message if debug mode is enabled.
     */
    private fun sendDebugLog(message: String) {
        if (debugMode && debugLogger != null) {
            debugLogger?.sendDebugLog(message)
        }
    }

    /**
     * Handle prediction results from async prediction handler.
     * Displays predictions in suggestion bar and auto-inserts top prediction.
     *
     * @param predictions List of predicted words
     * @param scores Confidence scores for predictions
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for context
     * @param resources Resources for metrics
     */
    fun handlePredictionResults(
        predictions: List<String>,
        scores: List<Int>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources
    ) {
        // DEBUG: Log predictions received
        sendDebugLog("Predictions received: ${predictions.size}\n")
        if (predictions.isNotEmpty()) {
            predictions.take(5).forEachIndexed { i, pred ->
                val score = scores?.getOrNull(i) ?: 0
                sendDebugLog("  [${i + 1}] \"$pred\" (score: $score)\n")
            }
        }

        if (predictions.isEmpty()) {
            sendDebugLog("No predictions - clearing suggestions\n")
            suggestionBar?.clearSuggestions()
            return
        }

        // OPTIMIZATION v5 (perftodos5.md): Augment predictions with possessives
        // Generate possessive forms for top predictions and add them to the list
        val augmentedPredictions = predictions.toMutableList()
        val augmentedScores = (scores ?: emptyList()).toMutableList()
        augmentPredictionsWithPossessives(augmentedPredictions, augmentedScores)

        // Update suggestion bar (scores are already integers from neural system)
        suggestionBar?.let { bar ->
            bar.setShowDebugScores(config.swipe_show_debug_scores)
            bar.setSuggestionsWithScores(augmentedPredictions, augmentedScores)

            // Auto-insert top (highest scoring) prediction immediately after swipe completes
            // This enables rapid consecutive swiping without manual taps
            val topPrediction = bar.getTopSuggestion()
            if (!topPrediction.isNullOrEmpty()) {
                // If manual typing in progress, add space after it (don't re-commit the text!)
                if (contextTracker.getCurrentWordLength() > 0 && ic != null) {
                    sendDebugLog("Manual typing in progress before swipe: \"${contextTracker.getCurrentWord()}\"\n")

                    // IMPORTANT: Characters from manual typing are already committed via KeyEventHandler.send_text()
                    // _currentWord is just a tracking buffer - the text is already in the editor!
                    // We only need to add a space after the manually typed word and clear the tracking buffer
                    ic.commitText(" ", 1)
                    contextTracker.clearCurrentWord()

                    // Clear any previous auto-inserted word tracking since user was manually typing
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.USER_TYPED_TAP)
                }

                // DEBUG: Log auto-insertion
                sendDebugLog("Auto-inserting top prediction: \"$topPrediction\"\n")

                // CRITICAL: Clear auto-inserted tracking BEFORE calling onSuggestionSelected
                // This prevents the deletion logic from removing the previous auto-inserted word
                // For consecutive swipes, we want to APPEND words, not replace them
                contextTracker.clearLastAutoInsertedWord()
                contextTracker.setLastCommitSource(PredictionSource.UNKNOWN) // Temporarily clear

                // onSuggestionSelected handles spacing logic (no space if first text, space otherwise)
                onSuggestionSelected(topPrediction, ic, editorInfo, resources)

                // NOW track this as auto-inserted so tapping another suggestion will replace ONLY this word
                // CRITICAL: Strip "raw:" prefix BEFORE storing (v1.33.7: fixed regex to match actual prefix format)
                val cleanPrediction = topPrediction.replace(Regex("^raw:"), "")
                contextTracker.setLastAutoInsertedWord(cleanPrediction)
                contextTracker.setLastCommitSource(PredictionSource.NEURAL_SWIPE)

                // CRITICAL: Re-display suggestions after auto-insertion
                // User can still tap a different prediction if the auto-inserted one was wrong
                bar.setSuggestionsWithScores(predictions, scores ?: emptyList())

                sendDebugLog("Suggestions re-displayed for correction\n")
            }
        }
        sendDebugLog("========== SWIPE COMPLETE ==========\n\n")
    }

    /**
     * Called when user selects a suggestion from the suggestion bar.
     * Handles autocorrect, text replacement, and context updates.
     *
     * @param word Selected word
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for app detection
     * @param resources Resources for metrics
     */
    fun onSuggestionSelected(
        word: String?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources
    ) {
        // Null/empty check
        if (word.isNullOrBlank()) return

        var processedWord = word

        // Check if this is a raw prediction (user explicitly selected neural network output)
        // Raw predictions should skip autocorrect
        val isRawPrediction = processedWord.startsWith("raw:")

        // Strip "raw:" prefix before processing (v1.33.7: fixed regex to match actual prefix format)
        // Prefix format: "raw:word" not " [raw:0.08]"
        processedWord = processedWord.replace(Regex("^raw:"), "")

        // Check if this is a known contraction (already has apostrophes from displayText)
        // If it is, skip autocorrect to prevent fuzzy matching to wrong words
        val isKnownContraction = contractionManager.isKnownContraction(processedWord)

        // Skip autocorrect for:
        // 1. Known contractions (prevent fuzzy matching)
        // 2. Raw predictions (user explicitly selected this neural output)
        if (isKnownContraction || isRawPrediction) {
            if (isKnownContraction) {
                Log.d(TAG, "KNOWN CONTRACTION: \"$processedWord\" - skipping autocorrect")
            }
            if (isRawPrediction) {
                Log.d(TAG, "RAW PREDICTION: \"$processedWord\" - skipping autocorrect")
            }
        } else {
            // v1.33.7: Final autocorrect - second chance autocorrect after beam search
            // Applies when user selects/auto-inserts a prediction (even if beam autocorrect was OFF)
            // Useful for correcting vocabulary misses
            // SKIP for known contractions and raw predictions
            if (config.swipe_final_autocorrect_enabled && predictionCoordinator.getWordPredictor() != null) {
                val correctedWord = predictionCoordinator.getWordPredictor()?.autoCorrect(processedWord)

                // If autocorrect found a better match, use it
                if (correctedWord != null && correctedWord != processedWord) {
                    Log.d(TAG, "FINAL AUTOCORRECT: \"$processedWord\" → \"$correctedWord\"")
                    processedWord = correctedWord
                }
            }
        }

        // Record user selection for adaptation learning
        predictionCoordinator.getAdaptationManager()?.recordSelection(processedWord.trim())

        // CRITICAL: Save swipe flag before resetting for use in spacing logic below
        val isSwipeAutoInsert = contextTracker.wasLastInputSwipe()

        // Store ML data if this was a swipe prediction selection
        // Note: ML data collection is handled by InputCoordinator, not here
        // This handler only deals with suggestion selection logic

        // Reset swipe tracking
        contextTracker.setWasLastInputSwipe(false)

        ic?.let { inputConnection ->
            try {
                // Detect if we're in Termux for special handling
                val inTermuxApp = try {
                    editorInfo?.packageName == "com.termux"
                } catch (e: Exception) {
                    false
                }

                // IMPORTANT: _currentWord tracks typed characters, but they're already committed to input!
                // When typing normally (not swipe), each character is committed immediately via KeyEventHandler
                // So _currentWord is just for tracking - the text is already in the editor
                // We should NOT delete _currentWord characters here because:
                // 1. They're already committed and visible
                // 2. Swipe gesture detection happens AFTER typing completes
                // 3. User expects swipe to ADD a word, not delete what they typed
                //
                // Example bug scenario:
                // - User types "i" (committed to editor, _currentWord="i")
                // - User swipes "think" (without space after "i")
                // - Old code: deletes "i", adds " think " → result: " think " (lost the "i"!)
                // - New code: keeps "i", adds " think " → result: "i think " (correct!)
                //
                // The ONLY time we should delete is when replacing an auto-inserted prediction
                // (handled below via _lastAutoInsertedWord tracking)

                // CRITICAL: If we just auto-inserted a word from neural swipe, delete it for replacement
                // This allows user to tap a different prediction instead of appending
                // Only delete if the last commit was from neural swipe (not from other sources)
                if (!contextTracker.getLastAutoInsertedWord().isNullOrEmpty() &&
                    contextTracker.getLastCommitSource() == PredictionSource.NEURAL_SWIPE
                ) {
                    Log.d(TAG, "REPLACE: Deleting auto-inserted word: '${contextTracker.getLastAutoInsertedWord()}'")

                    var deleteCount = (contextTracker.getLastAutoInsertedWord()?.length ?: 0) + 1 // Word + trailing space
                    var deletedLeadingSpace = false

                    if (inTermuxApp) {
                        // TERMUX: Use backspace key events instead of InputConnection methods
                        // Termux doesn't support deleteSurroundingText properly
                        Log.d(TAG, "TERMUX: Using backspace key events to delete $deleteCount chars")

                        // Check if there's a leading space to delete
                        val textBefore = inputConnection.getTextBeforeCursor(1, 0)
                        if (textBefore != null && textBefore.isNotEmpty() && textBefore[0] == ' ') {
                            deleteCount++ // Include leading space
                            deletedLeadingSpace = true
                        }

                        // Send backspace key events
                        repeat(deleteCount) {
                            keyeventhandler.send_key_down_up(KeyEvent.KEYCODE_DEL, 0)
                        }
                    } else {
                        // NORMAL APPS: Use InputConnection methods
                        val debugBefore = inputConnection.getTextBeforeCursor(50, 0)
                        Log.d(TAG, "REPLACE: Text before cursor (50 chars): '$debugBefore'")
                        Log.d(TAG, "REPLACE: Delete count = $deleteCount")

                        // Delete the auto-inserted word and its space
                        inputConnection.deleteSurroundingText(deleteCount, 0)

                        val debugAfter = inputConnection.getTextBeforeCursor(50, 0)
                        Log.d(TAG, "REPLACE: After deleting word, text before cursor: '$debugAfter'")

                        // Also need to check if there was a space added before it
                        val textBefore = inputConnection.getTextBeforeCursor(1, 0)
                        Log.d(TAG, "REPLACE: Checking for leading space, got: '$textBefore'")
                        if (textBefore != null && textBefore.isNotEmpty() && textBefore[0] == ' ') {
                            Log.d(TAG, "REPLACE: Deleting leading space")
                            // Delete the leading space too
                            inputConnection.deleteSurroundingText(1, 0)

                            val debugFinal = inputConnection.getTextBeforeCursor(50, 0)
                            Log.d(TAG, "REPLACE: After deleting leading space: '$debugFinal'")
                        }
                    }

                    // Clear the tracking variables
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
                }
                // ALSO: If user is selecting a prediction during regular typing, delete the partial word
                // This handles typing "hel" then selecting "hello" - we need to delete "hel" first
                else if (contextTracker.getCurrentWordLength() > 0 && !isSwipeAutoInsert) {
                    Log.d(TAG, "TYPING PREDICTION: Deleting partial word: '${contextTracker.getCurrentWord()}'")

                    if (inTermuxApp) {
                        // TERMUX: Use backspace key events
                        Log.d(TAG, "TERMUX: Using backspace key events to delete ${contextTracker.getCurrentWordLength()} chars")
                        repeat(contextTracker.getCurrentWordLength()) {
                            keyeventhandler.send_key_down_up(KeyEvent.KEYCODE_DEL, 0)
                        }
                    } else {
                        // NORMAL APPS: Use InputConnection
                        inputConnection.deleteSurroundingText(contextTracker.getCurrentWordLength(), 0)

                        val debugAfter = inputConnection.getTextBeforeCursor(50, 0)
                        Log.d(TAG, "TYPING PREDICTION: After deleting partial, text before cursor: '$debugAfter'")
                    }
                }

                // Add space before word if previous character isn't whitespace
                val needsSpaceBefore = try {
                    val textBefore = inputConnection.getTextBeforeCursor(1, 0)
                    if (textBefore != null && textBefore.isNotEmpty()) {
                        val prevChar = textBefore[0]
                        // Add space if previous char is not whitespace and not punctuation start
                        !prevChar.isWhitespace() && prevChar != '(' && prevChar != '[' && prevChar != '{'
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    // If getTextBeforeCursor fails, assume we don't need space before
                    false
                }

                // Apply capitalization if user was typing with shift (first letter uppercase)
                val currentWord = contextTracker.getCurrentWord()
                val shouldCapitalize = currentWord.isNotEmpty() && currentWord[0].isUpperCase()
                val capitalizedWord = if (shouldCapitalize && processedWord.isNotEmpty()) {
                    processedWord.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
                    }
                } else {
                    processedWord
                }

                // Commit the selected word
                // Only skip trailing space if actually IN Termux app (not just termux_mode_enabled)
                val textToInsert = if (config.termux_mode_enabled && !isSwipeAutoInsert && inTermuxApp) {
                    // Termux app: Insert word without automatic space for terminal compatibility
                    if (needsSpaceBefore) " $capitalizedWord" else capitalizedWord.also {
                        Log.d(TAG, "TERMUX APP (non-swipe): textToInsert = '$it'")
                    }
                } else {
                    // Normal apps (even with Termux mode) or swipe: Insert word with space after
                    // This provides better touch typing experience
                    if (needsSpaceBefore) " $capitalizedWord " else "$capitalizedWord ".also {
                        Log.d(TAG, "NORMAL/SWIPE MODE: textToInsert = '$it' (needsSpaceBefore=$needsSpaceBefore, isSwipe=$isSwipeAutoInsert, capitalize=$shouldCapitalize)")
                    }
                }

                Log.d(TAG, "Committing text: '$textToInsert' (length=${textToInsert.length})")
                inputConnection.commitText(textToInsert, 1)

                // Track that this commit was from candidate selection (manual tap)
                // Note: Auto-insertions set this separately to NEURAL_SWIPE
                if (contextTracker.getLastCommitSource() != PredictionSource.NEURAL_SWIPE) {
                    contextTracker.setLastCommitSource(PredictionSource.CANDIDATE_SELECTION)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onSuggestionSelected", e)
            }

            // Update context with the selected word
            updateContext(processedWord)

            // Clear current word
            // NOTE: Don't clear suggestions here - they're re-displayed after auto-insertion
            contextTracker.clearCurrentWord()
        }
    }

    /**
     * Update context with a completed word.
     *
     * NOTE: This is a legacy helper method. New code should use
     * _contextTracker.commitWord() directly with appropriate PredictionSource.
     *
     * @param word Completed word to add to context
     */
    fun updateContext(word: String?) {
        if (word.isNullOrEmpty()) return

        // Use the current source from tracker, or UNKNOWN if not set
        val source = contextTracker.getLastCommitSource() ?: PredictionSource.UNKNOWN

        // Commit word to context tracker (not auto-inserted since this is manual update)
        contextTracker.commitWord(word, source, false)

        // Add word to WordPredictor for language detection
        predictionCoordinator.getWordPredictor()?.addWordToContext(word)
    }

    /**
     * Handle regular typing predictions (non-swipe).
     * Updates predictions as user types each character.
     *
     * @param text Text being typed
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for app detection
     */
    fun handleRegularTyping(text: String, ic: InputConnection?, editorInfo: EditorInfo?) {
        if (!config.word_prediction_enabled || predictionCoordinator.getWordPredictor() == null || suggestionBar == null) {
            return
        }

        // Track current word being typed
        when {
            text.length == 1 && text[0].isLetter() -> {
                contextTracker.appendToCurrentWord(text)
                // If just started a new word (first letter), clear auto-insert tracking
                // This prevents incorrectly deleting a previously swiped word when
                // user types a new word then taps a prediction
                if (contextTracker.getCurrentWordLength() == 1) {
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.USER_TYPED_TAP)
                }
                updatePredictionsForCurrentWord()
            }
            text.length == 1 && !text[0].isLetter() -> {
                // Any non-letter character - update context and reset current word

                // If we had a word being typed, add it to context before clearing
                if (contextTracker.getCurrentWordLength() > 0) {
                    val completedWord = contextTracker.getCurrentWord()

                    // Auto-correct the typed word if feature is enabled
                    // DISABLED in Termux app due to erratic behavior with terminal input
                    val inTermuxApp = try {
                        editorInfo?.packageName == "com.termux"
                    } catch (e: Exception) {
                        false
                    }

                    if (config.autocorrect_enabled && predictionCoordinator.getWordPredictor() != null &&
                        text == " " && !inTermuxApp) {
                        val correctedWord = predictionCoordinator.getWordPredictor()?.autoCorrect(completedWord)

                        // If correction was made, replace the typed word
                        if (correctedWord != null && correctedWord != completedWord) {
                            ic?.let { inputConnection ->
                                // At this point:
                                // - The typed word "thid" has been committed via KeyEventHandler.send_text()
                                // - The space " " has ALSO been committed via handle_text_typed(" ")
                                // - Editor contains "thid "
                                // - We need to delete both the word AND the space, then insert corrected word + space

                                // Delete the typed word + space (already committed)
                                inputConnection.deleteSurroundingText(completedWord.length + 1, 0)

                                // Insert the corrected word WITH trailing space (normal apps only)
                                inputConnection.commitText("$correctedWord ", 1)

                                // Update context with corrected word
                                updateContext(correctedWord)

                                // Clear current word
                                contextTracker.clearCurrentWord()

                                // Show corrected word as first suggestion for easy undo
                                suggestionBar?.setSuggestionsWithScores(
                                    listOf(completedWord, correctedWord), // Original word first for undo
                                    listOf(0, 0)
                                )

                                // Reset prediction state
                                predictionCoordinator.getWordPredictor()?.reset()

                                return // Skip normal text processing - we've handled everything
                            }
                        }
                    }

                    updateContext(completedWord)
                }

                // Reset current word
                contextTracker.clearCurrentWord()
                predictionCoordinator.getWordPredictor()?.reset()
                suggestionBar?.clearSuggestions()
            }
            text.length > 1 -> {
                // Multi-character input (paste, etc) - reset
                contextTracker.clearCurrentWord()
                predictionCoordinator.getWordPredictor()?.reset()
                suggestionBar?.clearSuggestions()
            }
        }
    }

    /**
     * Handle backspace for prediction tracking.
     * Updates predictions as user deletes characters.
     */
    fun handleBackspace() {
        if (contextTracker.getCurrentWordLength() > 0) {
            contextTracker.deleteLastChar()
            if (contextTracker.getCurrentWordLength() > 0) {
                updatePredictionsForCurrentWord()
            } else {
                suggestionBar?.clearSuggestions()
            }
        }
    }

    /**
     * Update predictions based on current partial word.
     */
    private fun updatePredictionsForCurrentWord() {
        if (contextTracker.getCurrentWordLength() > 0) {
            val partial = contextTracker.getCurrentWord()

            // Check if first letter is uppercase (user typed with Shift)
            val shouldCapitalize = partial.isNotEmpty() && partial[0].isUpperCase()

            // Copy context to be thread-safe
            val contextWords = contextTracker.getContextWords().toList()

            // Cancel previous task if running
            currentPredictionTask?.cancel(true)

            // Submit new prediction task
            currentPredictionTask = predictionExecutor.submit {
                if (Thread.currentThread().isInterrupted) return@submit

                // Use contextual prediction (Heavy operation)
                val result = predictionCoordinator.getWordPredictor()?.predictWordsWithContext(partial, contextWords)

                if (Thread.currentThread().isInterrupted || result == null) return@submit

                // Apply capitalization transformation if user started with uppercase
                val transformedWords = if (shouldCapitalize) {
                    result.words.map { word ->
                        word.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
                        }
                    }
                } else {
                    result.words
                }

                // Post result to UI thread
                if (transformedWords.isNotEmpty() && suggestionBar != null) {
                    suggestionBar?.post {
                        // Verify context hasn't changed drastically (optional, but good practice)
                        suggestionBar?.let { bar ->
                            bar.setShowDebugScores(config.swipe_show_debug_scores)
                            bar.setSuggestionsWithScores(transformedWords, result.scores)
                        }
                    }
                }
            }
        }
    }

    /**
     * Smart delete last word - deletes the last auto-inserted word or last typed word.
     * Handles edge cases to avoid deleting too much text.
     *
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for app detection
     */
    fun handleDeleteLastWord(ic: InputConnection?, editorInfo: EditorInfo?) {
        if (ic == null) return

        // Check if we're in Termux - if so, use Ctrl+Backspace fallback
        val inTermux = try {
            editorInfo?.packageName == "com.termux"
        } catch (e: Exception) {
            Log.e(TAG, "DELETE_LAST_WORD: Error detecting Termux", e)
            false
        }

        // For Termux, use Ctrl+W key event which Termux handles correctly
        // Termux doesn't support InputConnection methods, but processes terminal control sequences
        if (inTermux) {
            Log.d(TAG, "DELETE_LAST_WORD: Using Ctrl+W (^W) for Termux")
            // Send Ctrl+W which is the standard terminal "delete word backward" sequence
            keyeventhandler.send_key_down_up(
                KeyEvent.KEYCODE_W,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
            // Clear tracking
            contextTracker.clearLastAutoInsertedWord()
            contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
            return
        }

        // First, try to delete the last auto-inserted word if it exists
        val lastAutoInserted = contextTracker.getLastAutoInsertedWord()
        if (!lastAutoInserted.isNullOrEmpty()) {
            Log.d(TAG, "DELETE_LAST_WORD: Deleting auto-inserted word: '$lastAutoInserted'")

            // Get text before cursor to verify
            val textBefore = ic.getTextBeforeCursor(100, 0)
            if (textBefore != null) {
                val beforeStr = textBefore.toString()

                // Check if the last auto-inserted word is actually at the end
                // Account for trailing space that swipe words have
                val hasTrailingSpace = beforeStr.endsWith(" ")
                val lastWord = if (hasTrailingSpace) {
                    beforeStr.substring(0, beforeStr.length - 1).trim()
                } else {
                    beforeStr.trim()
                }

                // Find last word in the text
                val lastSpaceIdx = lastWord.lastIndexOf(' ')
                val actualLastWord = if (lastSpaceIdx >= 0) {
                    lastWord.substring(lastSpaceIdx + 1)
                } else {
                    lastWord
                }

                // Verify this matches our tracked word (case-insensitive to be safe)
                if (actualLastWord.equals(lastAutoInserted, ignoreCase = true)) {
                    // Delete the word + trailing space if present
                    var deleteCount = lastAutoInserted.length
                    if (hasTrailingSpace) deleteCount += 1

                    ic.deleteSurroundingText(deleteCount, 0)
                    Log.d(TAG, "DELETE_LAST_WORD: Deleted $deleteCount characters")

                    // Clear tracking
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
                    return
                }
            }

            // If verification failed, fall through to delete last word generically
            Log.d(TAG, "DELETE_LAST_WORD: Auto-inserted word verification failed, using generic delete")
        }

        // Fallback: Delete the last word before cursor (generic approach)
        val textBefore = ic.getTextBeforeCursor(100, 0)
        if (textBefore.isNullOrEmpty()) {
            Log.d(TAG, "DELETE_LAST_WORD: No text before cursor, falling back to Ctrl+Backspace")
            keyeventhandler.send_key_down_up(
                KeyEvent.KEYCODE_DEL,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
            return
        }

        val beforeStr = textBefore.toString()
        var cursorPos = beforeStr.length

        // Skip trailing whitespace
        while (cursorPos > 0 && beforeStr[cursorPos - 1].isWhitespace()) {
            cursorPos--
        }

        if (cursorPos == 0) {
            Log.d(TAG, "DELETE_LAST_WORD: Only whitespace before cursor")
            return
        }

        // Find the start of the last word
        var wordStart = cursorPos
        while (wordStart > 0 && !beforeStr[wordStart - 1].isWhitespace()) {
            wordStart--
        }

        // Calculate delete count (word + any trailing spaces we skipped)
        var deleteCount = beforeStr.length - wordStart

        // Safety check: don't delete more than 50 characters at once
        if (deleteCount > 50) {
            Log.d(TAG, "DELETE_LAST_WORD: Refusing to delete $deleteCount characters (safety limit)")
            deleteCount = 50
        }

        Log.d(TAG, "DELETE_LAST_WORD: Deleting last word (generic), count=$deleteCount")
        if (!ic.deleteSurroundingText(deleteCount, 0)) {
            Log.d(TAG, "DELETE_LAST_WORD: deleteSurroundingText failed, falling back to Ctrl+Backspace")
            keyeventhandler.send_key_down_up(
                KeyEvent.KEYCODE_DEL,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        }

        // Clear tracking
        contextTracker.clearLastAutoInsertedWord()
        contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
    }

    /**
     * Augment predictions with possessive forms.
     *
     * OPTIMIZATION v5 (perftodos5.md): Generate possessives dynamically instead of storing 1700+ entries.
     * For each top prediction (limit to first 3-5), generate possessive form if applicable.
     *
     * @param predictions List of predictions to augment (modified in-place)
     * @param scores List of scores corresponding to predictions (modified in-place)
     */
    private fun augmentPredictionsWithPossessives(predictions: MutableList<String>, scores: MutableList<Int>) {
        if (predictions.isEmpty()) return

        // Generate possessives for top 3 predictions only (avoid clutter)
        val limit = minOf(3, predictions.size)
        val possessivesToAdd = mutableListOf<String>()
        val possessiveScores = mutableListOf<Int>()

        for (i in 0 until limit) {
            val word = predictions[i]
            val possessive = contractionManager.generatePossessive(word)

            if (possessive != null) {
                // Don't add if possessive already exists in predictions
                val alreadyExists = predictions.any { it.equals(possessive, ignoreCase = true) }

                if (!alreadyExists) {
                    possessivesToAdd.add(possessive)
                    // Slightly lower score than base word (base word is more common)
                    val baseScore = scores.getOrElse(i) { 128 }
                    possessiveScores.add(baseScore - 10) // 10 points lower than base
                }
            }
        }

        // Add possessives to the end of predictions list
        if (possessivesToAdd.isNotEmpty()) {
            predictions.addAll(possessivesToAdd)
            scores.addAll(possessiveScores)

            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Added ${possessivesToAdd.size} possessive forms to predictions")
            }
        }
    }
}
