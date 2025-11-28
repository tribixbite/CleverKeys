package tribixbite.keyboard2

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/**
 * Handles prediction and swipe typing view setup in onStartInputView().
 *
 * This class encapsulates the complex logic for:
 * - Initializing prediction engines (lazy initialization)
 * - Setting up suggestion bar and view hierarchy
 * - Configuring neural engine dimensions
 * - Setting up GlobalLayoutListener for accurate coordinate mapping
 * - Cleaning up when predictions are disabled
 *
 * The setup handler pattern simplifies onStartInputView() by consolidating
 * all prediction-related view setup into a single operation.
 *
 * This utility is extracted from CleverKeysService.java as part of Phase 4 refactoring
 * to reduce the main class size (v1.32.400).
 *
 * @since v1.32.400
 */
class PredictionViewSetup(
    private val keyboard2: CleverKeysService,
    private val config: Config,
    private val keyboardView: Keyboard2View,
    private val predictionCoordinator: PredictionCoordinator?,
    private val inputCoordinator: InputCoordinator?,
    private val suggestionHandler: SuggestionHandler?,
    private val neuralLayoutHelper: NeuralLayoutHelper?,
    private val receiver: KeyboardReceiver?,
    private val emojiPane: ViewGroup?
) {
    /**
     * Result of prediction view setup.
     *
     * @property inputView The view to set as input view (container or keyboard view)
     * @property suggestionBar The created suggestion bar (null if predictions disabled)
     * @property inputViewContainer The input view container (null if predictions disabled)
     * @property contentPaneContainer The content pane container (null if predictions disabled)
     */
    data class SetupResult(
        val inputView: View,
        val suggestionBar: SuggestionBar?,
        val inputViewContainer: android.widget.LinearLayout?,
        val contentPaneContainer: android.widget.FrameLayout?
    )

    /**
     * Setup prediction views and components.
     *
     * Handles two scenarios:
     * 1. Predictions enabled: Initialize engines, create suggestion bar, setup dimensions
     * 2. Predictions disabled: Clean up and return keyboard view
     *
     * @param existingSuggestionBar The current suggestion bar (null if not yet created)
     * @param existingInputViewContainer The current input view container (null if not yet created)
     * @param existingContentPaneContainer The current content pane container (null if not yet created)
     * @return SetupResult containing the input view and created components
     */
    fun setupPredictionViews(
        existingSuggestionBar: SuggestionBar?,
        existingInputViewContainer: android.widget.LinearLayout?,
        existingContentPaneContainer: android.widget.FrameLayout?
    ): SetupResult {
        // Check if word prediction or swipe typing is enabled
        if (config.word_prediction_enabled || config.swipe_typing_enabled) {
            // CRITICAL FIX: Initialize prediction engines in background thread to avoid 3-second UI freeze
            // ONNX model loading takes 2.8-4.4s and MUST NOT block the main thread
            // OPTIMIZATION: Only spawn thread if neural engine not yet ready
            if (predictionCoordinator?.isSwipeTypingAvailable() == false) {
                Thread {
                    predictionCoordinator.ensureInitialized()
                }.start()
            }

            // Set keyboard dimensions for neural engine if available
            if (config.swipe_typing_enabled && predictionCoordinator != null) {
                val neuralEngine = predictionCoordinator.getNeuralEngine()
                if (neuralEngine != null) {
                    neuralEngine.setKeyboardDimensions(
                        keyboardView.getWidth().toFloat(),
                        keyboardView.getHeight().toFloat()
                    )
                    keyboardView.setSwipeTypingComponents(
                        predictionCoordinator.getWordPredictor(),
                        keyboard2
                    )
                }
            }

            // Create suggestion bar if needed
            var suggestionBar = existingSuggestionBar
            var inputViewContainer = existingInputViewContainer
            var contentPaneContainer = existingContentPaneContainer

            if (suggestionBar == null) {
                // Initialize suggestion bar and input view hierarchy
                val theme = keyboardView.getTheme()
                val result = SuggestionBarInitializer.initialize(
                    keyboard2,
                    theme,
                    config.suggestion_bar_opacity,
                    config.clipboard_pane_height_percent
                )

                inputViewContainer = result.inputViewContainer
                suggestionBar = result.suggestionBar
                contentPaneContainer = result.contentPaneContainer

                // Register suggestion selection listener
                suggestionBar?.setOnSuggestionSelectedListener(keyboard2)

                // Propagate suggestion bar and view references to managers
                val suggestionBarPropagator = SuggestionBarPropagator.create(
                    inputCoordinator,
                    suggestionHandler,
                    neuralLayoutHelper,
                    receiver
                )
                suggestionBarPropagator.propagateAll(
                    suggestionBar,
                    emojiPane,
                    contentPaneContainer
                )

                // CRITICAL FIX: Remove keyboardView from existing parent (e.g. Window)
                // before adding to new container to prevent IllegalStateException
                (keyboardView.parent as? android.view.ViewGroup)?.removeView(keyboardView)
                inputViewContainer?.addView(keyboardView)
            } else {
                // CRITICAL FIX: If views already exist, we MUST still propagate them to the receiver/managers
                // because the receiver/managers might have been recreated (e.g. onStartInputView)
                // while the views persisted.
                val suggestionBarPropagator = SuggestionBarPropagator.create(
                    inputCoordinator,
                    suggestionHandler,
                    neuralLayoutHelper,
                    receiver
                )
                suggestionBarPropagator.propagateAll(
                    suggestionBar,
                    emojiPane,
                    contentPaneContainer
                )
            }

            // Determine which view to use as input view
            val inputView = inputViewContainer ?: keyboardView

            // Set correct keyboard dimensions for CGR after view is laid out
            val neuralEngine = predictionCoordinator?.getNeuralEngine()
            if (neuralEngine != null) {
                // Helper to update layout dimensions and keys
                val updateNeuralLayout = {
                    if (keyboardView.width > 0 && keyboardView.height > 0) {
                        // Use dynamic keyboard dimensions
                        val keyboardWidth = keyboardView.width.toFloat()
                        val keyboardHeight = neuralLayoutHelper?.calculateDynamicKeyboardHeight()
                            ?: keyboardView.height.toFloat()

                        neuralEngine.setKeyboardDimensions(keyboardWidth, keyboardHeight)

                        // Set real key positions for accurate coordinate mapping
                        neuralLayoutHelper?.setNeuralKeyboardLayout()
                    }
                }

                // Try setting immediately if dimensions are already available
                // This ensures predictions work even if onGlobalLayout doesn't fire (e.g. view reuse)
                updateNeuralLayout()

                // Also add listener to catch layout completion or changes
                keyboardView.viewTreeObserver.addOnGlobalLayoutListener(
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            // Ensure we have valid dimensions
                            if (keyboardView.width > 0 && keyboardView.height > 0) {
                                updateNeuralLayout()

                                // Remove listener to avoid repeated calls
                                keyboardView.viewTreeObserver
                                    .removeOnGlobalLayoutListener(this)
                            }
                        }
                    }
                )
            }

            return SetupResult(inputView, suggestionBar, inputViewContainer, contentPaneContainer)
        } else {
            // Clean up if predictions are disabled
            return SetupResult(keyboardView, null, null, null)
        }
    }

    companion object {
        /**
         * Create a PredictionViewSetup.
         *
         * @param keyboard2 The CleverKeysService service
         * @param config The configuration
         * @param keyboardView The keyboard view
         * @param predictionCoordinator The prediction coordinator
         * @param inputCoordinator The input coordinator (nullable)
         * @param suggestionHandler The suggestion handler (nullable)
         * @param neuralLayoutHelper The neural layout helper (nullable)
         * @param receiver The keyboard receiver (nullable)
         * @param emojiPane The emoji pane (nullable)
         * @return A new PredictionViewSetup instance
         */
        @JvmStatic
        fun create(
            keyboard2: CleverKeysService,
            config: Config,
            keyboardView: Keyboard2View,
            predictionCoordinator: PredictionCoordinator?,
            inputCoordinator: InputCoordinator?,
            suggestionHandler: SuggestionHandler?,
            neuralLayoutHelper: NeuralLayoutHelper?,
            receiver: KeyboardReceiver?,
            emojiPane: ViewGroup?
        ): PredictionViewSetup {
            return PredictionViewSetup(
                keyboard2,
                config,
                keyboardView,
                predictionCoordinator,
                inputCoordinator,
                suggestionHandler,
                neuralLayoutHelper,
                receiver,
                emojiPane
            )
        }
    }
}
