package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.ml.SwipeMLData
import tribixbite.cleverkeys.onnx.SwipePredictorOrchestrator
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

    // v1.2.6: Flag to prevent async prediction task from overwriting special prompts
    // (autocorrect undo, add-to-dictionary)
    @Volatile
    private var specialPromptActive = false

    // Password mode tracking
    private var isPasswordMode = false

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
     * Sets password mode.
     * When enabled, predictions are disabled and password text is tracked.
     *
     * @param enabled Whether password mode is enabled
     */
    fun setPasswordMode(enabled: Boolean) {
        isPasswordMode = enabled
        if (enabled) {
            // Clear predictions when entering password mode
            suggestionBar?.clearSuggestions()
        }
        Log.d(TAG, "Password mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if currently in password mode.
     */
    fun isInPasswordMode(): Boolean = isPasswordMode

    /**
     * Handle a character typed in password field.
     * Syncs with actual field content to handle all edge cases.
     *
     * @param char The character that was typed
     */
    fun handlePasswordChar(char: Char) {
        if (!isPasswordMode) return
        // Sync with field to handle any edge cases (autocomplete, etc.)
        suggestionBar?.syncPasswordWithField()
    }

    /**
     * Handle a string typed in password field.
     * Syncs with actual field content to handle all edge cases.
     *
     * @param text The text that was typed
     */
    fun handlePasswordText(text: String) {
        if (!isPasswordMode) return
        // Sync with field to handle paste, autocomplete, etc.
        suggestionBar?.syncPasswordWithField()
    }

    /**
     * Handle backspace in password field.
     * Syncs with actual field content to handle select-all+delete, etc.
     */
    fun handlePasswordBackspace() {
        if (!isPasswordMode) return
        // Sync with field - handles select-all+delete, cursor position changes, etc.
        suggestionBar?.syncPasswordWithField()
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
        // Skip predictions in password mode
        if (isPasswordMode) {
            return
        }

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
     * @param isManualSelection True if user explicitly tapped a suggestion (skip final autocorrect),
     *                          false for auto-insert after swipe (final autocorrect may apply)
     */
    fun onSuggestionSelected(
        word: String?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        isManualSelection: Boolean = false
    ) {
        // Null/empty check
        if (word.isNullOrBlank()) return

        // Check if this is a "Add to dictionary?" tap (dict_add: prefix)
        if (word.startsWith("dict_add:")) {
            val wordToAdd = word.removePrefix("dict_add:")
            handleAddToDictionary(wordToAdd)
            return
        }

        // Check if this is an autocorrect undo (user tapped the original word after autocorrect)
        val lastAutocorrectOriginal = contextTracker.getLastAutocorrectOriginalWord()
        if (contextTracker.getLastCommitSource() == PredictionSource.AUTOCORRECT &&
            lastAutocorrectOriginal != null &&
            word.equals(lastAutocorrectOriginal, ignoreCase = true)
        ) {
            handleAutocorrectUndo(word, lastAutocorrectOriginal, ic, editorInfo)
            return
        }

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
        // 3. Manual selections (user explicitly tapped a neural prediction - issue #63 fix)
        if (isKnownContraction || isRawPrediction || isManualSelection) {
            if (isKnownContraction) {
                Log.d(TAG, "KNOWN CONTRACTION: \"$processedWord\" - skipping autocorrect")
            }
            if (isRawPrediction) {
                Log.d(TAG, "RAW PREDICTION: \"$processedWord\" - skipping autocorrect")
            }
            if (isManualSelection) {
                Log.d(TAG, "MANUAL SELECTION: \"$processedWord\" - skipping autocorrect (user chose this word)")
            }
        } else {
            // v1.33.7: Final autocorrect - second chance autocorrect after beam search
            // Applies when auto-inserting a prediction (even if beam autocorrect was OFF)
            // Useful for correcting vocabulary misses
            // SKIP for known contractions, raw predictions, and manual selections
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
                // v1.2.6: Also handles cursor mid-word - need to delete BOTH prefix AND suffix
                else if (contextTracker.getCurrentWordLength() > 0 && !isSwipeAutoInsert) {
                    // v1.2.6 FIX: Do immediate cursor sync to get accurate prefix/suffix
                    // The debounced sync may not have completed yet
                    contextTracker.synchronizeWithCursor(
                        inputConnection,
                        config.primary_language,
                        editorInfo
                    )

                    val (prefixDelete, suffixDelete) = contextTracker.getCharsToDeleteForPrediction()
                    Log.d(TAG, "TYPING PREDICTION: Deleting partial word - prefix=$prefixDelete, suffix=$suffixDelete")

                    if (inTermuxApp) {
                        // TERMUX: Use backspace key events
                        // First delete suffix (move right then backspace), then delete prefix
                        if (suffixDelete > 0) {
                            // Move cursor to end of word
                            repeat(suffixDelete) {
                                keyeventhandler.send_key_down_up(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
                            }
                        }
                        // Delete entire word (prefix + suffix)
                        repeat(prefixDelete + suffixDelete) {
                            keyeventhandler.send_key_down_up(KeyEvent.KEYCODE_DEL, 0)
                        }
                    } else {
                        // NORMAL APPS: Use InputConnection with both prefix AND suffix deletion
                        inputConnection.deleteSurroundingText(prefixDelete, suffixDelete)

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
     * Handle "Add to dictionary?" tap: add the word to user dictionary.
     * Does not modify the input text since the word is already committed.
     *
     * @param wordToAdd The word to add to dictionary
     */
    private fun handleAddToDictionary(wordToAdd: String) {
        if (wordToAdd.isEmpty()) {
            Log.w(TAG, "ADD TO DICTIONARY: Empty word, ignoring")
            return
        }

        Log.d(TAG, "ADD TO DICTIONARY: Adding '$wordToAdd'")

        // Add to user dictionary
        predictionCoordinator.getDictionaryManager()?.addUserWord(wordToAdd)

        // Refresh dictionary so word appears in predictions immediately
        predictionCoordinator.refreshCustomWords()

        // Clear tracking
        contextTracker.clearAutocorrectTracking()
        // v1.2.6: Clear special prompt flag
        specialPromptActive = false

        // Show confirmation message (clearAfter=true so bar clears instead of restoring prompt)
        suggestionBar?.showTemporaryMessage("Added '$wordToAdd' to dictionary", 2000L, clearAfter = true)
    }

    /**
     * Handle autocorrect undo: replace the autocorrected word with the original.
     * Also adds the original word to dictionary so it won't be autocorrected again.
     *
     * @param tappedWord The word the user tapped (original word before autocorrect)
     * @param originalWord The original word that was autocorrected (for logging)
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for app detection
     */
    private fun handleAutocorrectUndo(
        tappedWord: String,
        originalWord: String,
        ic: InputConnection?,
        editorInfo: EditorInfo?
    ) {
        val correctedWord = contextTracker.getLastAutoInsertedWord()
        if (correctedWord.isNullOrEmpty()) {
            Log.w(TAG, "AUTOCORRECT UNDO: No corrected word tracked, falling back to normal selection")
            return
        }

        Log.d(TAG, "AUTOCORRECT UNDO: Replacing '$correctedWord' with '$tappedWord'")

        ic?.let { inputConnection ->
            // Detect Termux
            val inTermuxApp = try {
                editorInfo?.packageName == "com.termux"
            } catch (e: Exception) {
                false
            }

            // Delete the autocorrected word + trailing space
            val deleteCount = correctedWord.length + 1 // Word + space

            if (inTermuxApp) {
                // Termux: Use backspace key events
                repeat(deleteCount) {
                    keyeventhandler.send_key_down_up(android.view.KeyEvent.KEYCODE_DEL, 0)
                }
            } else {
                inputConnection.deleteSurroundingText(deleteCount, 0)
            }

            // Insert the original word with trailing space
            inputConnection.commitText("$tappedWord ", 1)

            // Update context with the original word
            updateContext(tappedWord)

            // Add to user dictionary so it won't be autocorrected again
            predictionCoordinator.getDictionaryManager()?.addUserWord(tappedWord)
            Log.d(TAG, "AUTOCORRECT UNDO: Added '$tappedWord' to user dictionary")

            // Refresh dictionary so word appears in predictions immediately
            predictionCoordinator.refreshCustomWords()

            // Clear autocorrect tracking
            contextTracker.clearAutocorrectTracking()
            contextTracker.clearLastAutoInsertedWord()
            contextTracker.setLastCommitSource(PredictionSource.CANDIDATE_SELECTION)
            // v1.2.6: Clear special prompt flag
            specialPromptActive = false

            // Show confirmation message (clearAfter=true so bar clears instead of restoring prompt)
            suggestionBar?.showTemporaryMessage("Added '$tappedWord' to dictionary", 2000L, clearAfter = true)

            // Clear suggestions after brief delay (message will auto-clear)
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

        // Track word for multi-language detection
        try {
            SwipePredictorOrchestrator.getInstance(context).trackCommittedWord(word)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to track word for language detection", e)
        }
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
        // Handle password mode: update password display, skip predictions
        if (isPasswordMode) {
            handlePasswordText(text)
            return
        }

        if (!config.word_prediction_enabled || predictionCoordinator.getWordPredictor() == null || suggestionBar == null) {
            return
        }

        // Track current word being typed
        when {
            text.length == 1 && text[0].isLetter() -> {
                contextTracker.appendToCurrentWord(text)
                // If just started a new word (first letter), clear auto-insert and autocorrect tracking
                // This prevents incorrectly deleting a previously swiped word when
                // user types a new word then taps a prediction
                if (contextTracker.getCurrentWordLength() == 1) {
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.clearAutocorrectTracking()
                    contextTracker.setLastCommitSource(PredictionSource.USER_TYPED_TAP)
                    // v1.2.6: Clear special prompt flag - user is typing a new word
                    specialPromptActive = false
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

                                // Track autocorrect state for undo functionality
                                // When user taps original word in suggestions, we can detect and replace
                                contextTracker.setLastAutoInsertedWord(correctedWord)
                                contextTracker.setLastCommitSource(PredictionSource.AUTOCORRECT)
                                contextTracker.setLastAutocorrectOriginalWord(completedWord)

                                Log.d(TAG, "AUTOCORRECT: '$completedWord' → '$correctedWord' (tracking for undo)")

                                // v1.2.6 FIX: Cancel pending prediction task and set flag to prevent overwriting
                                currentPredictionTask?.cancel(true)
                                specialPromptActive = true

                                // Show original word as first suggestion for easy undo
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

                    // Check if this word is NOT in dictionary - offer to add it
                    // Only prompt if:
                    // 1. Word was just completed with space (text == " ")
                    // 2. Word is at least 3 characters (avoid prompts for short words)
                    // 3. Word is not in dictionary
                    if (text == " " && completedWord.length >= 3) {
                        val wordPredictor = predictionCoordinator.getWordPredictor()
                        val isInDictionary = wordPredictor?.isInDictionary(completedWord) ?: true
                        val isUserWord = predictionCoordinator.getDictionaryManager()?.isUserWord(completedWord) ?: false

                        if (!isInDictionary && !isUserWord) {
                            // v1.2.6 FIX: Cancel pending prediction task and set flag to prevent overwriting
                            currentPredictionTask?.cancel(true)
                            specialPromptActive = true

                            // Store word for add-to-dictionary handling
                            contextTracker.setLastAutocorrectOriginalWord(completedWord)
                            contextTracker.setLastCommitSource(PredictionSource.USER_TYPED_TAP)

                            // Show "Add to dictionary?" prompt with special prefix
                            suggestionBar?.setSuggestionsWithScores(
                                listOf("dict_add:$completedWord"),
                                listOf(0)
                            )

                            Log.d(TAG, "UNKNOWN WORD: '$completedWord' - showing add to dictionary prompt")

                            // Skip clearing suggestions below
                            contextTracker.clearCurrentWord()
                            predictionCoordinator.getWordPredictor()?.reset()
                            return
                        }
                    }
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
        // Handle password mode: update password display
        if (isPasswordMode) {
            handlePasswordBackspace()
            return
        }

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

                // v1.2.0: Apply contraction transformation (e.g., "dont" -> "don't")
                // Check if the typed partial matches a contraction key
                val contractionWords = mutableListOf<String>()
                val contractionScores = mutableListOf<Int>()

                // Check if the exact partial is a contraction key
                val contractionMapping = contractionManager.getNonPairedMapping(partial)
                if (contractionMapping != null) {
                    // Add contraction as first suggestion with high score
                    contractionWords.add(contractionMapping)
                    contractionScores.add(result.scores.firstOrNull()?.plus(1000) ?: 10000)
                }

                // Merge contraction with predictions (contraction first, then original predictions)
                val mergedWords = contractionWords + result.words.filter { it.lowercase() != contractionMapping?.lowercase() }
                val mergedScores = contractionScores + result.scores.take(result.words.size - contractionWords.size.coerceAtMost(1))

                // Apply capitalization transformation if user started with uppercase
                val transformedWords = if (shouldCapitalize) {
                    mergedWords.map { word ->
                        word.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
                        }
                    }
                } else {
                    mergedWords
                }

                // Post result to UI thread
                // v1.2.6 FIX: Check if task was cancelled or special prompt is active
                if (transformedWords.isNotEmpty() && suggestionBar != null &&
                    !Thread.currentThread().isInterrupted && !specialPromptActive) {
                    suggestionBar?.post {
                        // v1.2.6: Skip if special prompt became active while queued
                        if (specialPromptActive) return@post

                        // Verify context hasn't changed drastically (optional, but good practice)
                        suggestionBar?.let { bar ->
                            bar.setShowDebugScores(config.swipe_show_debug_scores)
                            // v1.2.0: Use merged scores that include contraction scores
                            bar.setSuggestionsWithScores(transformedWords, mergedScores)
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
