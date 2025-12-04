package tribixbite.cleverkeys

import android.content.Context
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * Utility class for initializing the suggestion bar and input view container.
 *
 * This class centralizes logic for:
 * - Creating suggestion bar with theme support
 * - Wrapping suggestion bar in scrollable container
 * - Creating content pane container for clipboard/emoji
 * - Assembling complete input view hierarchy
 *
 * Responsibilities:
 * - Create SuggestionBar with appropriate theme
 * - Configure suggestion bar layout and opacity
 * - Create scrollable wrapper for suggestions
 * - Create content pane container with configured height
 * - Assemble LinearLayout with proper hierarchy
 *
 * NOT included (remains in CleverKeysService):
 * - Registering suggestion selected listener
 * - Propagating suggestion bar reference to managers
 * - Setting input view on InputMethodService
 *
 * This utility is extracted from CleverKeysService.java for better code organization
 * and testability (v1.32.381).
 *
 * @since v1.32.381
 */
object SuggestionBarInitializer {

    /**
     * Result of suggestion bar initialization.
     *
     * @property inputViewContainer The root LinearLayout containing all views
     * @property suggestionBar The created SuggestionBar instance
     * @property contentPaneContainer The FrameLayout for clipboard/emoji panes
     * @property scrollView The HorizontalScrollView wrapping the suggestion bar
     */
    data class InitializationResult(
        val inputViewContainer: LinearLayout,
        val suggestionBar: SuggestionBar,
        val contentPaneContainer: android.widget.FrameLayout,
        val scrollView: HorizontalScrollView
    )

    /**
     * Initialize suggestion bar and input view container.
     *
     * Creates a complete input view hierarchy:
     * - LinearLayout (vertical orientation)
     *   - HorizontalScrollView (scrollable suggestions)
     *     - SuggestionBar
     *   - FrameLayout (content pane for clipboard/emoji, initially hidden)
     *   - Keyboard2View (added by caller)
     *
     * @param context Application context
     * @param theme Theme for suggestion bar styling (may be null)
     * @param opacity Suggestion bar opacity (0 - 100)
     * @param clipboardPaneHeightPercent Height of content pane as percentage of screen height
     * @return InitializationResult containing all created views
     */
    @JvmStatic
    fun initialize(
        context: Context,
        theme: Theme?,
        opacity: Int,
        clipboardPaneHeightPercent: Int
    ): InitializationResult {
        // Create root container
        val inputViewContainer = LinearLayout(context)
        inputViewContainer.orientation = LinearLayout.VERTICAL

        // Create suggestion bar with theme
        val suggestionBar = if (theme != null) {
            SuggestionBar(context, theme)
        } else {
            SuggestionBar(context)
        }
        suggestionBar.setOpacity(opacity)

        // Wrap suggestion bar in horizontal scroll view
        val scrollView = HorizontalScrollView(context)
        scrollView.isHorizontalScrollBarEnabled = false // Hide scrollbar
        scrollView.isFillViewport = false // Don't stretch content

        // Set scroll view layout params (40dp height)
        val scrollParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                40f,
                context.resources.displayMetrics
            ).toInt()
        )
        scrollView.layoutParams = scrollParams

        // Set suggestion bar to wrap_content width for scrolling
        val suggestionParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        suggestionBar.layoutParams = suggestionParams

        scrollView.addView(suggestionBar)
        inputViewContainer.addView(scrollView)

        // Create content pane container (for clipboard/emoji)
        // Stays hidden until user opens clipboard or emoji pane
        val contentPaneContainer = android.widget.FrameLayout(context)
        val screenHeight = context.resources.displayMetrics.heightPixels
        val paneHeight = (screenHeight * clipboardPaneHeightPercent) / 100
        contentPaneContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            paneHeight
        )
        contentPaneContainer.visibility = android.view.View.GONE // Hidden by default
        inputViewContainer.addView(contentPaneContainer)

        // Note: Keyboard view is added by caller after this method returns

        return InitializationResult(
            inputViewContainer,
            suggestionBar,
            contentPaneContainer,
            scrollView
        )
    }

    /**
     * Calculate content pane height in pixels.
     *
     * Helper method to calculate content pane height based on screen height
     * and configured percentage.
     *
     * @param context Application context
     * @param heightPercent Height as percentage of screen height (0-100)
     * @return Height in pixels
     */
    @JvmStatic
    fun calculateContentPaneHeight(context: Context, heightPercent: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return (screenHeight * heightPercent) / 100
    }
}
