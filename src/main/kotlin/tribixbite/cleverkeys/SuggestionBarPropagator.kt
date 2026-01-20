package tribixbite.cleverkeys

import android.view.ViewGroup

/**
 * Propagates SuggestionBar and view references to keyboard managers.
 *
 * This class centralizes the distribution of SuggestionBar and view references
 * to managers that need them:
 * - InputCoordinator: Needs SuggestionBar for prediction display
 * - SuggestionHandler: Needs SuggestionBar for suggestion management
 * - NeuralLayoutHelper: Needs SuggestionBar for neural predictions
 * - KeyboardReceiver: Needs emoji/clipboard pane references
 *
 * The propagator pattern simplifies onStartInputView() by consolidating
 * all reference updates into a single operation.
 *
 * This utility is extracted from CleverKeysService.java for better code organization
 * and testability (v1.32.394).
 *
 * @since v1.32.394
 */
class SuggestionBarPropagator(
    private val inputCoordinator: InputCoordinator?,
    private val suggestionHandler: SuggestionHandler?,
    private val neuralLayoutHelper: NeuralLayoutHelper?,
    private val receiver: KeyboardReceiver?
) {
    /**
     * Propagate SuggestionBar reference to all managers.
     *
     * Sets the SuggestionBar reference on managers that need it for
     * displaying predictions and suggestions.
     *
     * @param suggestionBar The SuggestionBar instance to propagate
     */
    fun propagateSuggestionBar(suggestionBar: SuggestionBar) {
        inputCoordinator?.setSuggestionBar(suggestionBar)
        suggestionHandler?.setSuggestionBar(suggestionBar)
        neuralLayoutHelper?.setSuggestionBar(suggestionBar)
    }

    /**
     * Propagate view references to KeyboardReceiver.
     *
     * Sets emoji pane and content pane container references on the receiver
     * for managing special view visibility.
     *
     * @param emojiPane The emoji pane view (nullable)
     * @param contentPaneContainer The content pane container for clipboard/emoji (nullable)
     */
    fun propagateViewReferences(emojiPane: ViewGroup?, contentPaneContainer: ViewGroup?) {
        receiver?.setViewReferences(emojiPane, contentPaneContainer)
    }

    /**
     * Propagate both SuggestionBar and view references.
     *
     * Convenience method to propagate all references in one call.
     *
     * @param suggestionBar The SuggestionBar instance to propagate
     * @param emojiPane The emoji pane view (nullable)
     * @param contentPaneContainer The content pane container (nullable)
     */
    fun propagateAll(
        suggestionBar: SuggestionBar,
        emojiPane: ViewGroup?,
        contentPaneContainer: ViewGroup?
    ) {
        propagateSuggestionBar(suggestionBar)
        propagateViewReferences(emojiPane, contentPaneContainer)
    }

    companion object {
        /**
         * Create a SuggestionBarPropagator.
         *
         * @param inputCoordinator The InputCoordinator (nullable)
         * @param suggestionHandler The SuggestionHandler (nullable)
         * @param neuralLayoutHelper The NeuralLayoutHelper (nullable)
         * @param receiver The KeyboardReceiver (nullable)
         * @return A new SuggestionBarPropagator instance
         */
        @JvmStatic
        fun create(
            inputCoordinator: InputCoordinator?,
            suggestionHandler: SuggestionHandler?,
            neuralLayoutHelper: NeuralLayoutHelper?,
            receiver: KeyboardReceiver?
        ): SuggestionBarPropagator {
            return SuggestionBarPropagator(
                inputCoordinator,
                suggestionHandler,
                neuralLayoutHelper,
                receiver
            )
        }
    }
}
