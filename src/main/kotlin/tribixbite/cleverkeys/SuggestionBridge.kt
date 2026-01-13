package tribixbite.cleverkeys

import android.content.res.Resources
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.ml.SwipeMLData

/**
 * Bridge between CleverKeysService and SuggestionHandler for prediction operations.
 *
 * This class consolidates all suggestion/prediction delegation logic, handling:
 * - Input context gathering (InputConnection, EditorInfo, Resources)
 * - Prediction result handling
 * - Regular typing and backspace handling
 * - Suggestion selection with ML data collection
 *
 * The bridge pattern simplifies CleverKeysService by centralizing the coordination
 * between prediction components and reducing repetitive context-gathering code.
 *
 * This utility is extracted from CleverKeysService.java as part of Phase 4 refactoring
 * to reduce the main class size (v1.32.406).
 *
 * @since v1.32.406
 */
class SuggestionBridge(
    private val keyboard2: CleverKeysService,
    private val suggestionHandler: SuggestionHandler?,
    private val mlDataCollector: MLDataCollector,
    private val inputCoordinator: InputCoordinator,
    private val contextTracker: PredictionContextTracker,
    private val predictionCoordinator: PredictionCoordinator?,
    private val keyboardView: Keyboard2View
) {
    /**
     * Handle prediction results from async prediction handler.
     *
     * Gathers InputConnection, EditorInfo, and Resources from the keyboard service,
     * then delegates to SuggestionHandler.
     *
     * @param predictions List of predicted words
     * @param scores Confidence scores for each prediction
     */
    fun handlePredictionResults(predictions: List<String>, scores: List<Int>) {
        suggestionHandler?.let { handler ->
            val ic = keyboard2.currentInputConnection
            val editorInfo = keyboard2.currentInputEditorInfo
            val resources = keyboard2.resources
            handler.handlePredictionResults(predictions, scores, ic, editorInfo, resources)
        }
    }

    /**
     * Handle regular typing predictions (non-swipe).
     *
     * Gathers InputConnection and EditorInfo, then delegates to SuggestionHandler.
     *
     * @param text The typed text
     */
    fun handleRegularTyping(text: String) {
        suggestionHandler?.let { handler ->
            val ic = keyboard2.currentInputConnection
            val editorInfo = keyboard2.currentInputEditorInfo
            handler.handleRegularTyping(text, ic, editorInfo)
        }
    }

    /**
     * Handle backspace for prediction tracking.
     *
     * Simple delegation to SuggestionHandler.
     */
    fun handleBackspace() {
        suggestionHandler?.handleBackspace()
    }

    /**
     * Smart delete last word - deletes the last auto-inserted word or last typed word.
     *
     * Gathers InputConnection and EditorInfo, then delegates to SuggestionHandler.
     */
    fun handleDeleteLastWord() {
        suggestionHandler?.let { handler ->
            val ic = keyboard2.currentInputConnection
            val editorInfo = keyboard2.currentInputEditorInfo
            handler.handleDeleteLastWord(ic, editorInfo)
        }
    }

    /**
     * Called when user selects a suggestion from suggestion bar.
     *
     * Handles ML data collection for swipe predictions, then delegates to SuggestionHandler
     * with gathered input context.
     *
     * @param word The selected suggestion word
     */
    fun onSuggestionSelected(word: String) {
        // Trigger haptic feedback for prediction tap
        keyboardView.triggerHaptic(HapticEvent.PREDICTION_TAP)

        // Store ML data if this was a swipe prediction selection
        val isSwipeAutoInsert = contextTracker.wasLastInputSwipe()
        val currentSwipeData = inputCoordinator.getCurrentSwipeData()

        if (isSwipeAutoInsert && currentSwipeData != null &&
            predictionCoordinator?.getMlDataStore() != null) {
            mlDataCollector.collectAndStoreSwipeData(
                word,
                currentSwipeData,
                keyboardView.height,
                predictionCoordinator.getMlDataStore()
            )
        }

        // Reset swipe data after ML collection
        inputCoordinator.resetSwipeData()

        // Delegate to SuggestionHandler
        suggestionHandler?.let { handler ->
            val ic = keyboard2.currentInputConnection
            val editorInfo = keyboard2.currentInputEditorInfo
            val resources = keyboard2.resources
            handler.onSuggestionSelected(word, ic, editorInfo, resources)
        }
    }

    companion object {
        /**
         * Create a SuggestionBridge.
         *
         * @param keyboard2 The CleverKeysService service (for gathering input context)
         * @param suggestionHandler The suggestion handler (nullable)
         * @param mlDataCollector The ML data collector
         * @param inputCoordinator The input coordinator
         * @param contextTracker The prediction context tracker
         * @param predictionCoordinator The prediction coordinator
         * @param keyboardView The keyboard view
         * @return A new SuggestionBridge instance
         */
        @JvmStatic
        fun create(
            keyboard2: CleverKeysService,
            suggestionHandler: SuggestionHandler?,
            mlDataCollector: MLDataCollector,
            inputCoordinator: InputCoordinator,
            contextTracker: PredictionContextTracker,
            predictionCoordinator: PredictionCoordinator?,
            keyboardView: Keyboard2View
        ): SuggestionBridge {
            return SuggestionBridge(
                keyboard2,
                suggestionHandler,
                mlDataCollector,
                inputCoordinator,
                contextTracker,
                predictionCoordinator,
                keyboardView
            )
        }
    }
}
