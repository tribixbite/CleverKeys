package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.keyboard2.ml.SwipeMLData
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Coordinates all text input operations including typing, backspace, word deletion,
 * swipe typing, and suggestion selection.
 *
 * This class centralizes input handling logic that was previously in CleverKeysService.java.
 * It manages:
 * - Regular typing with word predictions
 * - Autocorrection during typing
 * - Backspace and smart word deletion
 * - Swipe typing gesture recognition and prediction
 * - Suggestion selection and text insertion
 * - ML data collection for swipe training
 *
 * Dependencies:
 * - PredictionContextTracker: Tracks typing context
 * - PredictionCoordinator: Manages prediction engines
 * - ContractionManager: Handles contraction mappings
 * - SuggestionBar: Displays predictions to user
 * - Keyboard2View: For keyboard dimensions
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.350).
 */
class InputCoordinator(
    private val context: Context,
    private var config: Config,
    private val contextTracker: PredictionContextTracker,
    private val predictionCoordinator: PredictionCoordinator,
    private val contractionManager: ContractionManager,
    private var suggestionBar: SuggestionBar?,
    private val keyboardView: Keyboard2View,
    private val keyeventhandler: KeyEventHandler
) {
    companion object {
        private const val TAG = "InputCoordinator"
    }

    // Swipe ML data collection
    private var currentSwipeData: SwipeMLData? = null

    // v1.32.926: Track if shift was active when current swipe started (for ALL CAPS output)
    private var wasShiftActiveAtSwipeStart: Boolean = false

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
     * Updates suggestion bar reference.
     * Called when suggestion bar is created in onStartInputView.
     *
     * @param suggestionBar Suggestion bar instance
     */
    fun setSuggestionBar(suggestionBar: SuggestionBar?) {
        this.suggestionBar = suggestionBar
    }

    /**
     * Resets swipe data tracking.
     * Called when starting new input or switching apps.
     */
    fun resetSwipeData() {
        currentSwipeData = null
    }

    /**
     * Gets current swipe ML data for storage.
     * @return Current swipe data or null if no swipe in progress
     */
    fun getCurrentSwipeData(): SwipeMLData? = currentSwipeData

    /**
     * Handle prediction results from async swipe typing prediction.
     * Called when neural network predictions are ready.
     */
    fun handlePredictionResults(
        predictions: List<String>?,
        scores: List<Int>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources
    ) {
        val handleStartTime = System.currentTimeMillis()
        android.util.Log.e(TAG, "⏱️ HANDLE_PREDICTIONS START")

        if (predictions.isNullOrEmpty()) {
            suggestionBar?.clearSuggestions()
            android.util.Log.e(TAG, "⏱️ HANDLE_PREDICTIONS COMPLETE (empty): ${System.currentTimeMillis() - handleStartTime}ms")
            return
        }

        // Update suggestion bar
        suggestionBar?.let { bar ->
            val suggestionsStartTime = System.currentTimeMillis()
            bar.setShowDebugScores(config.swipe_show_debug_scores)
            bar.setSuggestionsWithScores(predictions, scores)
            android.util.Log.e(TAG, "⏱️ setSuggestionsWithScores: ${System.currentTimeMillis() - suggestionsStartTime}ms")

            // Auto-insert top prediction immediately after swipe completes
            bar.getTopSuggestion()?.takeIf { it.isNotEmpty() }?.let { topPrediction ->
                // If manual typing in progress, add space after it
                if (contextTracker.getCurrentWordLength() > 0 && ic != null) {
                    val spaceCommitTime = System.currentTimeMillis()
                    ic.commitText(" ", 1)
                    android.util.Log.e(TAG, "⏱️ commitText(space): ${System.currentTimeMillis() - spaceCommitTime}ms")
                    contextTracker.clearCurrentWord()
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.USER_TYPED_TAP)
                }

                // Clear tracking before selection to prevent deletion
                contextTracker.clearLastAutoInsertedWord()
                contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)

                // Insert the top prediction
                val insertStartTime = System.currentTimeMillis()
                onSuggestionSelected(topPrediction, ic, editorInfo, resources)
                val insertDuration = System.currentTimeMillis() - insertStartTime
                android.util.Log.e(TAG, "⏱️ onSuggestionSelected('$topPrediction'): ${insertDuration}ms")

                // Track as auto-inserted for replacement
                val cleanPrediction = topPrediction.replace("^raw:".toRegex(), "")
                contextTracker.setLastAutoInsertedWord(cleanPrediction)
                contextTracker.setLastCommitSource(PredictionSource.NEURAL_SWIPE)

                // Re-display suggestions after auto-insertion
                bar.setSuggestionsWithScores(predictions, scores)
            }
        }

        val handleDuration = System.currentTimeMillis() - handleStartTime
        android.util.Log.e(TAG, "⏱️ HANDLE_PREDICTIONS COMPLETE: ${handleDuration}ms")
    }

    /**
     * Updates context with a completed word.
     * Commits the word to context tracker and adds to word predictor.
     *
     * @param word Completed word to add to context
     */
    private fun updateContext(word: String) {
        if (word.isEmpty()) return

        // Use the current source from tracker, or UNKNOWN if not set
        val source = contextTracker.getLastCommitSource() ?: PredictionSource.UNKNOWN

        // Commit word to context tracker (not auto-inserted since this is manual update)
        contextTracker.commitWord(word, source, false)

        // Add word to WordPredictor for language detection
        predictionCoordinator.getWordPredictor()?.addWordToContext(word)
    }

    /**
     * Updates predictions for the current partial word being typed.
     * Uses contextual prediction with previous words.
     */
    private fun updatePredictionsForCurrentWord() {
        if (contextTracker.getCurrentWordLength() > 0) {
            val partial = contextTracker.getCurrentWord()

            // Copy context to be thread-safe
            val contextWords = ArrayList(contextTracker.getContextWords())

            // Cancel previous task if running
            currentPredictionTask?.cancel(true)

            // Submit new prediction task
            currentPredictionTask = predictionExecutor.submit {
                if (Thread.currentThread().isInterrupted) return@submit

                // Use contextual prediction (Heavy operation)
                val result = predictionCoordinator.getWordPredictor()
                    ?.predictWordsWithContext(partial, contextWords)

                if (Thread.currentThread().isInterrupted || result == null) return@submit

                // Post result to UI thread
                if (result.words.isNotEmpty()) {
                    suggestionBar?.post {
                        // Verify context hasn't changed drastically (optional, but good practice)
                        suggestionBar?.let { bar ->
                            bar.setShowDebugScores(config.swipe_show_debug_scores)
                            bar.setSuggestionsWithScores(result.words, result.scores)
                        }
                    }
                }
            }
        }
    }

    fun onSuggestionSelected(
        word: String?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources
    ) {
        // Null/empty check
        var processedWord = word?.trim() ?: return
        if (processedWord.isEmpty()) return

        // Check if this is a raw prediction (user explicitly selected neural network output)
        // Raw predictions should skip autocorrect
        val isRawPrediction = processedWord.startsWith("raw:")

        // Strip "raw:" prefix before processing (v1.33.7: fixed regex to match actual prefix format)
        // Prefix format: "raw:word" not " [raw:0.08]"
        processedWord = processedWord.replace("^raw:".toRegex(), "")

        // Check if this is a known contraction (already has apostrophes from displayText)
        // If it is, skip autocorrect to prevent fuzzy matching to wrong words
        // v1.32.341: Use ContractionManager for lookup
        val isKnownContraction = contractionManager.isKnownContraction(processedWord)

        // Skip autocorrect for:
        // 1. Known contractions (prevent fuzzy matching)
        // 2. Raw predictions (user explicitly selected this neural output)
        if (isKnownContraction) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("CleverKeysService", "KNOWN CONTRACTION: \"$processedWord\" - skipping autocorrect")
            }
        }
        if (isRawPrediction) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("CleverKeysService", "RAW PREDICTION: \"$processedWord\" - skipping autocorrect")
            }
        }

        if (!isKnownContraction && !isRawPrediction) {
            // v1.33.7: Final autocorrect - second chance autocorrect after beam search
            // Applies when user selects/auto-inserts a prediction (even if beam autocorrect was OFF)
            // Useful for correcting vocabulary misses
            // SKIP for known contractions and raw predictions
            if (config.swipe_final_autocorrect_enabled) {
                predictionCoordinator.getWordPredictor()?.autoCorrect(processedWord)?.let { correctedWord ->
                    // If autocorrect found a better match, use it
                    if (correctedWord != processedWord) {
                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                            android.util.Log.d("CleverKeysService", "FINAL AUTOCORRECT: \"$processedWord\" → \"$correctedWord\"")
                        }
                        processedWord = correctedWord
                    }
                }
            }
        }

        // Record user selection for adaptation learning
        predictionCoordinator.getAdaptationManager()?.recordSelection(processedWord.trim())

        // CRITICAL: Save swipe flag before resetting for use in spacing logic below
        val isSwipeAutoInsert = contextTracker.wasLastInputSwipe()

        // Store ML data if this was a swipe prediction selection
        if (isSwipeAutoInsert && currentSwipeData != null) {
            predictionCoordinator.getMlDataStore()?.let { dataStore ->
                // Create a new ML data object with the selected word
                val metrics = resources.displayMetrics
                val mlData = SwipeMLData(
                    processedWord, "user_selection",
                    metrics.widthPixels, metrics.heightPixels,
                    keyboardView.height
                )

                // Copy trace points from the temporary data
                currentSwipeData?.getTracePoints()?.forEach { point ->
                    // Add points with their original normalized values and timestamps
                    // Since they're already normalized, we need to denormalize then renormalize
                    // to ensure proper storage
                    val rawX = point.x * metrics.widthPixels
                    val rawY = point.y * metrics.heightPixels
                    // Reconstruct approximate timestamp (this is a limitation of the current design)
                    val timestamp = System.currentTimeMillis() - 1000 + point.tDeltaMs
                    mlData.addRawPoint(rawX, rawY, timestamp)
                }

                // Copy registered keys
                currentSwipeData?.getRegisteredKeys()?.forEach { key ->
                    mlData.addRegisteredKey(key)
                }

                // Store the ML data
                dataStore.storeSwipeData(mlData)
            }
        }

        // Reset swipe tracking
        contextTracker.setWasLastInputSwipe(false)
        currentSwipeData = null

        ic?.let { connection ->
            try {
                // Detect if we're in Termux for special handling
                val inTermuxApp = try {
                    editorInfo?.packageName == "com.termux"
                } catch (e: Exception) {
                    false
                }

                // CRITICAL: If we just auto-inserted a word from neural swipe, delete it for replacement
                // This allows user to tap a different prediction instead of appending
                // Only delete if the last commit was from neural swipe (not from other sources)
                val lastAutoInserted = contextTracker.getLastAutoInsertedWord()
                if (!lastAutoInserted.isNullOrEmpty() && contextTracker.getLastCommitSource() == PredictionSource.NEURAL_SWIPE) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("CleverKeysService", "REPLACE: Deleting auto-inserted word: '$lastAutoInserted'")
                    }

                    val deleteCount = lastAutoInserted.length + 1 // Word + trailing space

                    val deleteStartTime = System.currentTimeMillis()

                    // UNIFIED DELETION: Use InputConnection methods for ALL apps
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        val debugBefore = connection.getTextBeforeCursor(50, 0)
                        android.util.Log.d("CleverKeysService", "REPLACE: Text before cursor (50 chars): '$debugBefore'")
                        android.util.Log.d("CleverKeysService", "REPLACE: Delete count = $deleteCount")
                    }

                    // Delete the auto-inserted word and its space
                    connection.deleteSurroundingText(deleteCount, 0)

                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        val debugAfter = connection.getTextBeforeCursor(50, 0)
                        android.util.Log.d("CleverKeysService", "REPLACE: After deleting word, text before cursor: '$debugAfter'")
                    }

                    // Also need to check if there was a space added before it
                    val textBefore = connection.getTextBeforeCursor(1, 0)
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("CleverKeysService", "REPLACE: Checking for leading space, got: '$textBefore'")
                    }
                    if (textBefore?.isNotEmpty() == true && textBefore[0] == ' ') {
                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                            android.util.Log.d("CleverKeysService", "REPLACE: Deleting leading space")
                        }
                        // Delete the leading space too
                        connection.deleteSurroundingText(1, 0)

                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                            val debugFinal = connection.getTextBeforeCursor(50, 0)
                            android.util.Log.d("CleverKeysService", "REPLACE: After deleting leading space: '$debugFinal'")
                        }
                    }

                    val deleteDuration = System.currentTimeMillis() - deleteStartTime
                    android.util.Log.e(TAG, "⏱️ UNIFIED DELETE (was auto-inserted): ${deleteDuration}ms")

                    // Clear the tracking variables
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
                }
                // ALSO: If user is selecting a prediction during regular typing, delete the partial word
                // This handles typing "hel" then selecting "hello" - we need to delete "hel" first
                else if (contextTracker.getCurrentWordLength() > 0 && !isSwipeAutoInsert) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("CleverKeysService", "TYPING PREDICTION: Deleting partial word: '${contextTracker.getCurrentWord()}'")
                    }

                    val partialDeleteStart = System.currentTimeMillis()
                    // FIX: Use InputConnection for ALL apps (no more slow Termux backspaces)
                    connection.deleteSurroundingText(contextTracker.getCurrentWordLength(), 0)

                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        val debugAfter = connection.getTextBeforeCursor(50, 0)
                        android.util.Log.d("CleverKeysService", "TYPING PREDICTION: After deleting partial, text before cursor: '$debugAfter'")
                    }

                    val partialDeleteDuration = System.currentTimeMillis() - partialDeleteStart
                    android.util.Log.e(TAG, "⏱️ UNIFIED DELETE (partial word): ${partialDeleteDuration}ms")
                }

                // Add space before word if previous character isn't whitespace
                val needsSpaceBefore = try {
                    val textBefore = connection.getTextBeforeCursor(1, 0)
                    if (textBefore?.isNotEmpty() == true) {
                        val prevChar = textBefore[0]
                        // Add space if previous char is not whitespace and not punctuation start
                        !prevChar.isWhitespace() && prevChar != '(' && prevChar != '[' && prevChar != '{'
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }

                // v1.32.926: If shift was active when swipe started, convert entire word to UPPERCASE
                // This applies ONLY to swipe-auto-insertions (not manual candidate selections)
                if (wasShiftActiveAtSwipeStart && isSwipeAutoInsert) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("CleverKeysService", "SHIFT+SWIPE: Converting '$processedWord' to ALL CAPS")
                    }
                    processedWord = processedWord.uppercase(java.util.Locale.getDefault())
                }

                // Commit the selected word - use Termux mode if enabled
                val textToInsert = when {
                    config.termux_mode_enabled && !isSwipeAutoInsert -> {
                        // Termux mode (non-swipe): Insert word without automatic space for better terminal compatibility
                        (if (needsSpaceBefore) " " else "") + processedWord
                    }
                    else -> {
                        // Normal mode OR swipe in Termux: Insert word with space after (and before if needed)
                        // For swipe typing, we always add trailing spaces even in Termux mode for better UX
                        (if (needsSpaceBefore) " $processedWord " else "$processedWord ")
                    }
                }

                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    android.util.Log.d("CleverKeysService", "TERMUX/NORMAL MODE: textToInsert = '$textToInsert' (needsSpaceBefore=$needsSpaceBefore, isSwipe=$isSwipeAutoInsert)")
                    android.util.Log.d("CleverKeysService", "Committing text: '$textToInsert' (length=${textToInsert.length})")
                }

                val commitStartTime = System.currentTimeMillis()
                connection.commitText(textToInsert, 1)
                val commitDuration = System.currentTimeMillis() - commitStartTime
                android.util.Log.e(TAG, "⏱️ commitText('$textToInsert'): ${commitDuration}ms")

                // Notify auto-capitalization system about the inserted text
                // This ensures shift is enabled after sentence-ending punctuation (. ! ?)
                keyeventhandler.notifyTextTyped(textToInsert)

                // CRITICAL FIX: Clear shift state AFTER swipe word is committed
                // If shift was active when swipe started, we've already uppercased the word,
                // now we need to turn off the shift indicator for the NEXT word
                if (wasShiftActiveAtSwipeStart && isSwipeAutoInsert) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("CleverKeysService", "SHIFT+SWIPE: Clearing shift after word commit")
                    }
                    // Post to UI thread to ensure keyboard view update happens correctly
                    keyboardView.post {
                        keyboardView.clearLatchedModifiers()
                    }
                    wasShiftActiveAtSwipeStart = false // Reset for next swipe
                }

                // Track that this commit was from candidate selection (manual tap)
                // Note: Auto-insertions set this separately to NEURAL_SWIPE
                if (contextTracker.getLastCommitSource() != PredictionSource.NEURAL_SWIPE) {
                    contextTracker.setLastCommitSource(PredictionSource.CANDIDATE_SELECTION)
                }
            } catch (e: Exception) {
                // Silently catch exceptions
            }

            // Update context with the selected word
            updateContext(processedWord)

            // Clear current word
            // NOTE: Don't clear suggestions here - they're re-displayed after auto-insertion
            contextTracker.clearCurrentWord()
        }
    }

    /**
     * Handle regular typing predictions (non-swipe)
     */
    fun handleRegularTyping(text: String, ic: InputConnection?, editorInfo: EditorInfo?) {
        if (!config.word_prediction_enabled || predictionCoordinator.getWordPredictor() == null || suggestionBar == null) {
            return
        }

        // Track current word being typed
        when {
            text.length == 1 && text[0].isLetter() -> {
                contextTracker.appendToCurrentWord(text)
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

                    if (config.autocorrect_enabled && text == " " && !inTermuxApp) {
                        predictionCoordinator.getWordPredictor()?.autoCorrect(completedWord)?.let { correctedWord ->
                            // If correction was made, replace the typed word
                            if (correctedWord != completedWord && ic != null) {
                                // Delete the typed word + space (already committed)
                                ic.deleteSurroundingText(completedWord.length + 1, 0)

                                // Insert the corrected word WITH trailing space (normal apps only)
                                ic.commitText("$correctedWord ", 1)

                                // Update context with corrected word
                                updateContext(correctedWord)

                                // Clear current word
                                contextTracker.clearCurrentWord()

                                // Show corrected word as first suggestion for easy undo
                                suggestionBar?.let { bar ->
                                    val undoSuggestions = listOf(completedWord, correctedWord)
                                    val undoScores = listOf(0, 0)
                                    bar.setSuggestionsWithScores(undoSuggestions, undoScores)
                                }

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
     * Handle backspace for prediction tracking
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
     * Update predictions based on current partial word
     */
    fun handleDeleteLastWord(ic: InputConnection?, editorInfo: EditorInfo?) {
        ic ?: return

        // Check if we're in Termux - if so, use Ctrl+Backspace fallback
        val inTermux = try {
            editorInfo?.packageName == "com.termux"
        } catch (e: Exception) {
            android.util.Log.e("CleverKeysService", "DELETE_LAST_WORD: Error detecting Termux", e)
            false
        }

        // For Termux, use Ctrl+W key event which Termux handles correctly
        // Termux doesn't support InputConnection methods, but processes terminal control sequences
        if (inTermux) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("CleverKeysService", "DELETE_LAST_WORD: Using Ctrl+W (^W) for Termux")
            }
            // Send Ctrl+W which is the standard terminal "delete word backward" sequence
            keyeventhandler.send_key_down_up(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            // Clear tracking
            contextTracker.clearLastAutoInsertedWord()
            contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
            return
        }

        // First, try to delete the last auto-inserted word if it exists
        val lastAutoInserted = contextTracker.getLastAutoInsertedWord()
        if (!lastAutoInserted.isNullOrEmpty()) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("CleverKeysService", "DELETE_LAST_WORD: Deleting auto-inserted word: '$lastAutoInserted'")
            }

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
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("CleverKeysService", "DELETE_LAST_WORD: Deleted $deleteCount characters")
                    }

                    // Clear tracking
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
                    return
                }
            }

            // If verification failed, fall through to delete last word generically
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("CleverKeysService", "DELETE_LAST_WORD: Auto-inserted word verification failed, using generic delete")
            }
        }

        // Fallback: Delete the last word before cursor (generic approach)
        val textBefore = ic.getTextBeforeCursor(100, 0)
        if (textBefore.isNullOrEmpty()) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("CleverKeysService", "DELETE_LAST_WORD: No text before cursor")
            }
            return
        }

        val beforeStr = textBefore.toString()
        var cursorPos = beforeStr.length

        // Skip trailing whitespace
        while (cursorPos > 0 && beforeStr[cursorPos - 1].isWhitespace()) {
            cursorPos--
        }

        if (cursorPos == 0) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("CleverKeysService", "DELETE_LAST_WORD: Only whitespace before cursor")
            }
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
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("CleverKeysService", "DELETE_LAST_WORD: Refusing to delete $deleteCount characters (safety limit)")
            }
            deleteCount = 50
        }

        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            android.util.Log.d("CleverKeysService", "DELETE_LAST_WORD: Deleting last word (generic), count=$deleteCount")
        }
        ic.deleteSurroundingText(deleteCount, 0)

        // Clear tracking
        contextTracker.clearLastAutoInsertedWord()
        contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
    }

    /**
     * Calculate dynamic keyboard height based on user settings (like calibration page)
     * Supports orientation, foldable devices, and user height preferences
     */
    private fun calculateDynamicKeyboardHeight(): Float {
        return try {
            // Get screen dimensions
            val metrics = android.util.DisplayMetrics()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            wm.defaultDisplay.getMetrics(metrics)

            // Check foldable state
            val foldTracker = FoldStateTracker(context)
            val foldableUnfolded = foldTracker.isUnfolded()

            // Check orientation
            val isLandscape = context.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE

            // Get user height preference (same logic as calibration)
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val key = when {
                isLandscape && foldableUnfolded -> "keyboard_height_landscape_unfolded"
                isLandscape -> "keyboard_height_landscape"
                foldableUnfolded -> "keyboard_height_unfolded"
                else -> "keyboard_height"
            }
            val keyboardHeightPref = prefs.getInt(key, if (isLandscape) 50 else 35)

            // Calculate dynamic height
            val keyboardHeightPercent = keyboardHeightPref / 100.0f
            metrics.heightPixels * keyboardHeightPercent
        } catch (e: Exception) {
            // Fallback to view height
            keyboardView.height.toFloat()
        }
    }

    /**
     * Get user keyboard height percentage for logging
     */
    fun handleSwipeTyping(
        swipedKeys: List<KeyboardData.Key>,
        swipePath: List<android.graphics.PointF>?,
        timestamps: List<Long>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        wasShiftActive: Boolean = false  // v1.32.926: Track if shift was active when swipe started
    ) {
        // v1.32.926: Store shift state for ALL CAPS transformation in onSuggestionSelected
        wasShiftActiveAtSwipeStart = wasShiftActive

        // Clear auto-inserted word tracking when new swipe starts
        contextTracker.clearLastAutoInsertedWord()

        if (!config.swipe_typing_enabled) return

        // OPTIMIZATION v1.32.529: Ensure neural engine is loaded before first swipe
        // If not loaded in onCreate (rare edge case), lazy-load synchronously now
        predictionCoordinator.ensureNeuralEngineReady()

        if (predictionCoordinator.getNeuralEngine() == null) {
            // Fallback to word predictor if engine not initialized
            if (predictionCoordinator.getWordPredictor() == null) return

            // Ensure prediction engines are initialized (lazy initialization)
            predictionCoordinator.ensureInitialized()
        }

        // Mark that last input was a swipe for ML data collection
        contextTracker.setWasLastInputSwipe(true)

        // Prepare ML data (will be saved if user selects a prediction)
        val metrics = resources.displayMetrics
        currentSwipeData = SwipeMLData(
            "", "user_selection",
            metrics.widthPixels, metrics.heightPixels,
            keyboardView.height
        )

        // Add swipe path points with timestamps
        if (swipePath != null && timestamps != null && swipePath.size == timestamps.size) {
            swipePath.indices.forEach { i ->
                val point = swipePath[i]
                val timestamp = timestamps[i]
                currentSwipeData?.addRawPoint(point.x, point.y, timestamp)
            }
        }

        // Build key sequence from swiped keys for ML data ONLY
        // NOTE: This is gesture tracker's detection - neural network will recalculate independently
        val gestureTrackerKeys = StringBuilder()
        swipedKeys.forEach { key ->
            key.keys[0]?.let { kv ->
                if (kv.getKind() == KeyValue.Kind.Char) {
                    val c = kv.getChar()
                    gestureTrackerKeys.append(c)
                    // Add to ML data
                    currentSwipeData?.addRegisteredKey(c.toString())
                }
            }
        }

        if (!swipePath.isNullOrEmpty()) {
            // Create SwipeInput exactly like SwipeCalibrationActivity (empty swipedKeys)
            // This ensures neural system handles key detection internally for consistency
            // The neural network will recalculate keys from the full path without filtering
            val swipeInput = SwipeInput(
                swipePath,
                timestamps ?: emptyList(),
                emptyList() // Empty - neural recalculates keys
            )

            // UNIFIED PREDICTION STRATEGY: All predictions wait for gesture completion
            // This matches SwipeCalibrationActivity behavior and eliminates premature predictions

            // Cancel any pending predictions first
            predictionCoordinator.getAsyncPredictionHandler()?.cancelPendingPredictions()

            // Request predictions asynchronously - always done on gesture completion
            // which matches the calibration activity's ACTION_UP behavior
            predictionCoordinator.getAsyncPredictionHandler()?.let { handler ->
                handler.requestPredictions(swipeInput, object : AsyncPredictionHandler.PredictionCallback {
                    override fun onPredictionsReady(predictions: List<String>, scores: List<Int>) {
                        // Process predictions on UI thread
                        handlePredictionResults(predictions, scores, ic, editorInfo, resources)
                    }

                    override fun onPredictionError(error: String) {
                        // Clear suggestions on error
                        suggestionBar?.clearSuggestions()
                    }
                })
            } ?: run {
                // Fallback to synchronous prediction if async handler not available
                // Ensure engine is available before calling predict
                predictionCoordinator.getNeuralEngine()?.let { engine ->
                    val startTime = System.currentTimeMillis()
                    val result = engine.predict(swipeInput)
                    val predictionTime = System.currentTimeMillis() - startTime
                    val predictions = result.words

                    // Show suggestions in the bar
                    if (predictions.isNotEmpty()) {
                        suggestionBar?.let { bar ->
                            bar.setShowDebugScores(config.swipe_show_debug_scores)
                            bar.setSuggestionsWithScores(predictions, result.scores)
                        }
                    }
                }
            }
        }
    }
}
