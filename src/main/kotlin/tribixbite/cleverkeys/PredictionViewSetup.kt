package tribixbite.cleverkeys

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Handles prediction and swipe typing view setup in onStartInputView().
 *
 * This class encapsulates the complex logic for:
 * - Initializing prediction engines (lazy initialization)
 * - Setting up suggestion bar and view hierarchy
 * - Configuring keyboard dimensions for the swipe pipeline
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
    private val keyboardDimensionsHelper: KeyboardDimensionsHelper?,
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
     * @property topPane The topPane FrameLayout (null if predictions disabled)
     * @property scrollView The scrollView with suggestion bar (null if predictions disabled)
     */
    data class SetupResult(
        val inputView: View,
        val suggestionBar: SuggestionBar?,
        val inputViewContainer: LinearLayout?,
        val contentPaneContainer: android.widget.FrameLayout?,
        val topPane: android.widget.FrameLayout?,
        val scrollView: android.widget.HorizontalScrollView?
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
        existingInputViewContainer: LinearLayout?,
        existingContentPaneContainer: android.widget.FrameLayout?,
        existingTopPane: android.widget.FrameLayout?,
        existingScrollView: android.widget.HorizontalScrollView?
    ): SetupResult {
        // Check if word prediction or swipe typing is enabled
        if (config.word_prediction_enabled || config.swipe_typing_enabled) {
            // Re-wire the view's predictor handle whenever a prediction-capable input view is
            // built. `setSwipeTypingComponents` is null-tolerant and idempotent; the initial
            // (unconditional) wiring happens in PredictionInitializer.
            if (predictionCoordinator != null) {
                keyboardView.setSwipeTypingComponents(
                    predictionCoordinator.getWordPredictor(),
                    keyboard2
                )
            }

            // Create suggestion bar if needed
            var suggestionBar = existingSuggestionBar
            var inputViewContainer: LinearLayout? = existingInputViewContainer
            var contentPaneContainer = existingContentPaneContainer
            // CRITICAL: Use existing references - don't try to extract from hierarchy
            // because topPane's child changes between scrollView and contentPaneContainer
            var topPane: android.widget.FrameLayout? = existingTopPane
            var scrollView: android.widget.HorizontalScrollView? = existingScrollView

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
                topPane = result.topPane
                scrollView = result.scrollView

                // Register suggestion selection listener
                suggestionBar?.setOnSuggestionSelectedListener(keyboard2)

                // Calculate heights for topPane resizing
                val suggestionBarHeight = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP,
                    40f,
                    keyboard2.resources.displayMetrics
                ).toInt()
                val contentPaneHeight = SuggestionBarInitializer.calculateContentPaneHeight(
                    keyboard2,
                    config.clipboard_pane_height_percent
                )

                // Propagate suggestion bar and view references to managers
                val suggestionBarPropagator = SuggestionBarPropagator.create(
                    inputCoordinator,
                    suggestionHandler,
                    keyboardDimensionsHelper,
                    receiver
                )
                suggestionBarPropagator.propagateAll(
                    suggestionBar,
                    emojiPane,
                    contentPaneContainer,
                    result.topPane,
                    result.scrollView,
                    suggestionBarHeight,
                    contentPaneHeight
                )

                // CRITICAL FIX: Remove keyboardView from existing parent (e.g. Window)
                // before adding to new container to prevent IllegalStateException
                (keyboardView.parent as? android.view.ViewGroup)?.removeView(keyboardView)
                // Add keyboard with wrap_content height
                val keyboardParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                inputViewContainer?.addView(keyboardView, keyboardParams)
            } else {
                // CRITICAL FIX: If views already exist, we MUST still propagate them to the receiver/managers
                // because the receiver/managers might have been recreated (e.g. onStartInputView)
                // while the views persisted.
                // NOTE: topPane and scrollView are now passed in as parameters, not extracted from hierarchy
                // This fixes the bug where scrollView becomes null when content pane is showing

                // Calculate heights for topPane resizing
                val suggestionBarHeight = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP,
                    40f,
                    keyboard2.resources.displayMetrics
                ).toInt()
                val contentPaneHeight = SuggestionBarInitializer.calculateContentPaneHeight(
                    keyboard2,
                    config.clipboard_pane_height_percent
                )

                android.util.Log.i("PredictionViewSetup", "Else branch: topPane=$topPane, scrollView=$scrollView, suggestionBarHeight=$suggestionBarHeight, contentPaneHeight=$contentPaneHeight")

                val suggestionBarPropagator = SuggestionBarPropagator.create(
                    inputCoordinator,
                    suggestionHandler,
                    keyboardDimensionsHelper,
                    receiver
                )
                suggestionBarPropagator.propagateAll(
                    suggestionBar,
                    emojiPane,
                    contentPaneContainer,
                    topPane,
                    scrollView,
                    suggestionBarHeight,
                    contentPaneHeight
                )
            }

            // Determine which view to use as input view
            val inputView = inputViewContainer ?: keyboardView

            // #148: if this container was first built while predictions were disabled, the
            // suggestion strip is collapsed to 0 height — restore it now (only in suggestion
            // mode; a showing content pane owns the height until it closes).
            topPane?.let { top ->
                if (scrollView?.parent == top && top.layoutParams != null &&
                    top.layoutParams.height != suggestionBarStripHeight()
                ) {
                    top.layoutParams.height = suggestionBarStripHeight()
                    top.requestLayout()
                }
            }

            // topPane and scrollView are now tracked throughout the method
            return SetupResult(inputView, suggestionBar, inputViewContainer, contentPaneContainer, topPane, scrollView)
        } else {
            // #148 (ARC-002): predictions disabled must NOT collapse the whole container to the
            // bare keyboard view. With no contentPaneContainer, every pane opener
            // (KeyboardReceiver SWITCH_EMOJI/SWITCH_CLIPBOARD/SWITCH_GIF) takes its
            // `setInputView(pane)` fallback and REPLACES the keyboard until the pane closes —
            // deterministic on every device, not the "HyperOS-specific" mystery #148 was filed
            // as. Build the same hierarchy with the suggestion strip collapsed to 0 height so
            // panes overlay above a still-visible keyboard exactly as in the enabled mode.
            var suggestionBar = existingSuggestionBar
            var inputViewContainer: LinearLayout? = existingInputViewContainer
            var contentPaneContainer = existingContentPaneContainer
            var topPane: android.widget.FrameLayout? = existingTopPane
            var scrollView: android.widget.HorizontalScrollView? = existingScrollView

            if (suggestionBar == null) {
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
                topPane = result.topPane
                scrollView = result.scrollView
                suggestionBar.setOnSuggestionSelectedListener(keyboard2)

                (keyboardView.parent as? android.view.ViewGroup)?.removeView(keyboardView)
                inputViewContainer.addView(
                    keyboardView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            // Propagate with suggestionBarHeight = 0: hideContentPane restores the strip to
            // this stored height, so pane close returns to a 0-height (invisible) strip
            // rather than an empty 40 dp suggestion row.
            val contentPaneHeight = SuggestionBarInitializer.calculateContentPaneHeight(
                keyboard2,
                config.clipboard_pane_height_percent
            )
            SuggestionBarPropagator.create(
                inputCoordinator,
                suggestionHandler,
                keyboardDimensionsHelper,
                receiver
            ).propagateAll(
                suggestionBar,
                emojiPane,
                contentPaneContainer,
                topPane,
                scrollView,
                0,
                contentPaneHeight
            )

            // Collapse the strip now unless a content pane currently owns topPane.
            topPane?.let { top ->
                if (scrollView?.parent == top && top.layoutParams != null &&
                    top.layoutParams.height != 0
                ) {
                    top.layoutParams.height = 0
                    top.requestLayout()
                }
            }

            val inputView: View = inputViewContainer ?: keyboardView
            return SetupResult(inputView, suggestionBar, inputViewContainer, contentPaneContainer, topPane, scrollView)
        }
    }

    /** The suggestion strip's height in enabled mode (the 40 dp row topPane starts with). */
    private fun suggestionBarStripHeight(): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP,
        40f,
        keyboard2.resources.displayMetrics
    ).toInt()

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
         * @param keyboardDimensionsHelper The keyboard-dimensions helper (nullable)
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
            keyboardDimensionsHelper: KeyboardDimensionsHelper?,
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
                keyboardDimensionsHelper,
                receiver,
                emojiPane
            )
        }
    }
}
