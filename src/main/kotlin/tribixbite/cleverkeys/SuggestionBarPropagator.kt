package tribixbite.cleverkeys

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView

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
     */
    fun propagateSuggestionBar(suggestionBar: SuggestionBar) {
        inputCoordinator?.setSuggestionBar(suggestionBar)
        suggestionHandler?.setSuggestionBar(suggestionBar)
        neuralLayoutHelper?.setSuggestionBar(suggestionBar)
    }

    /**
     * Propagate view references to KeyboardReceiver.
     *
     * @param emojiPane The emoji pane view (nullable)
     * @param contentPaneContainer The content pane container for clipboard/emoji (nullable)
     * @param topPane The FrameLayout that holds either scrollView or contentPaneContainer
     * @param scrollView The HorizontalScrollView containing suggestion bar
     * @param suggestionBarHeight Height of suggestion bar in pixels
     * @param contentPaneHeight Height of content pane in pixels
     */
    fun propagateViewReferences(
        emojiPane: ViewGroup?,
        contentPaneContainer: FrameLayout?,
        topPane: FrameLayout? = null,
        scrollView: HorizontalScrollView? = null,
        suggestionBarHeight: Int = 0,
        contentPaneHeight: Int = 0
    ) {
        receiver?.setViewReferences(
            emojiPane,
            contentPaneContainer,
            topPane,
            scrollView,
            suggestionBarHeight,
            contentPaneHeight
        )
    }

    /**
     * Propagate both SuggestionBar and view references.
     */
    fun propagateAll(
        suggestionBar: SuggestionBar,
        emojiPane: ViewGroup?,
        contentPaneContainer: FrameLayout?,
        topPane: FrameLayout? = null,
        scrollView: HorizontalScrollView? = null,
        suggestionBarHeight: Int = 0,
        contentPaneHeight: Int = 0
    ) {
        propagateSuggestionBar(suggestionBar)
        propagateViewReferences(
            emojiPane,
            contentPaneContainer,
            topPane,
            scrollView,
            suggestionBarHeight,
            contentPaneHeight
        )
    }

    companion object {
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
