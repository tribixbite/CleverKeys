package tribixbite.cleverkeys

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
                // Capture references for use in callback
                val coordRef = predictionCoordinator
                val helperRef = neuralLayoutHelper
                val viewRef = keyboardView
                Thread {
                    coordRef.ensureInitialized()
                    // FIX v1.1.81: After initialization, set up neural layout directly
                    // Don't rely on requestLayout() triggering OnGlobalLayoutListener
                    viewRef.post {
                        val engine = coordRef.getNeuralEngine()
                        if (engine != null && viewRef.width > 0 && viewRef.height > 0) {
                            val kbWidth = viewRef.width.toFloat()
                            val kbHeight = helperRef?.calculateDynamicKeyboardHeight()
                                ?: viewRef.height.toFloat()
                            engine.setKeyboardDimensions(kbWidth, kbHeight)
                            helperRef?.setNeuralKeyboardLayout()
                            android.util.Log.d("PredictionViewSetup",
                                "Neural layout setup complete after async init: ${kbWidth}x${kbHeight}")
                        }
                    }
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

            // Track scrollView for collapsing when emoji/clipboard pane is open
            var scrollView: android.view.View? = null

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
                scrollView = result.scrollView

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
                    scrollView,
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
                // Get scrollView from existing suggestionBar's parent
                scrollView = suggestionBar.parent as? android.view.View

                val suggestionBarPropagator = SuggestionBarPropagator.create(
                    inputCoordinator,
                    suggestionHandler,
                    neuralLayoutHelper,
                    receiver
                )
                suggestionBarPropagator.propagateAll(
                    suggestionBar,
                    scrollView,
                    emojiPane,
                    contentPaneContainer
                )
            }

            // Determine which view to use as input view
            val inputView = inputViewContainer ?: keyboardView

            // Set correct keyboard dimensions for CGR after view is laid out
            // FIX v1.1.81: On first app load, neural engine may still be initializing in background
            // Keep the layout listener active until BOTH conditions are met:
            // 1. Keyboard view has valid dimensions (layout complete)
            // 2. Neural engine is initialized and ready

            // Helper to update layout dimensions and keys - requires both conditions
            val updateNeuralLayout: () -> Boolean = updateFun@{
                val engine = predictionCoordinator?.getNeuralEngine()
                if (engine == null) {
                    // Engine not ready yet - keep listener active
                    return@updateFun false
                }
                if (keyboardView.width <= 0 || keyboardView.height <= 0) {
                    // Layout not ready yet - keep listener active
                    return@updateFun false
                }

                // Both conditions met - set up neural layout
                val keyboardWidth = keyboardView.width.toFloat()
                val keyboardHeight = neuralLayoutHelper?.calculateDynamicKeyboardHeight()
                    ?: keyboardView.height.toFloat()

                engine.setKeyboardDimensions(keyboardWidth, keyboardHeight)

                // Set real key positions for accurate coordinate mapping
                neuralLayoutHelper?.setNeuralKeyboardLayout()

                return@updateFun true // Success - can remove listener
            }

            // Try setting immediately if both conditions are already met
            // This ensures predictions work for subsequent input views (view reuse)
            val immediateSuccess = updateNeuralLayout()

            // Add listener to catch layout completion AND/OR engine initialization
            // Only remove when both conditions are satisfied
            if (!immediateSuccess) {
                keyboardView.viewTreeObserver.addOnGlobalLayoutListener(
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            // Try to set up neural layout - returns true when successful
                            if (updateNeuralLayout()) {
                                // Both conditions met - remove listener
                                keyboardView.viewTreeObserver
                                    .removeOnGlobalLayoutListener(this)
                            }
                            // If false, listener stays active to retry on next layout pass
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
