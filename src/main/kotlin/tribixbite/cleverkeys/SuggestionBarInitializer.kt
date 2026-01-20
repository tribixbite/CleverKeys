package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
     * @property viewFlipper The ViewFlipper that swaps between scrollView and contentPaneContainer
     */
    data class InitializationResult(
        val inputViewContainer: LinearLayout,
        val suggestionBar: SuggestionBar,
        val contentPaneContainer: android.widget.FrameLayout,
        val scrollView: HorizontalScrollView,
        val viewFlipper: android.widget.ViewFlipper? = null
    )

    /**
     * Initialize suggestion bar and input view container.
     *
     * Creates a complete input view hierarchy:
     * - LinearLayout (vertical orientation)
     *   - ViewFlipper (swaps between scrollView and contentPaneContainer)
     *     - HorizontalScrollView (scrollable suggestions) - index 0, shown by default
     *     - FrameLayout (content pane for clipboard/emoji) - index 1
     *   - Keyboard2View (added by caller)
     *
     * The ViewFlipper handles the swap cleanly without gaps.
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
        // Ensure transparent background to avoid white bar issues on OEM devices
        inputViewContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

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
        scrollView.isFillViewport = true // Stretch content to fill viewport when smaller
        scrollView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Set suggestion bar to wrap_content width for scrolling
        val suggestionParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        suggestionBar.layoutParams = suggestionParams

        scrollView.addView(suggestionBar)

        // Create content pane container (for clipboard/emoji)
        val contentPaneContainer = android.widget.FrameLayout(context)
        contentPaneContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // FIX #1131: Apply bottom padding for navigation bar on Android 15+
        // This prevents clipboard/emoji pane content from being obscured by the nav bar
        ViewCompat.setOnApplyWindowInsetsListener(contentPaneContainer) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            // Apply bottom padding to keep content above nav bar
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                navBarInsets.bottom
            )
            insets
        }

        // Calculate heights
        val screenHeight = context.resources.displayMetrics.heightPixels
        val paneHeight = (screenHeight * clipboardPaneHeightPercent) / 100
        val suggestionBarHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            40f,
            context.resources.displayMetrics
        ).toInt()

        // Create ViewFlipper to swap between scrollView and contentPaneContainer
        val viewFlipper = android.widget.ViewFlipper(context)
        viewFlipper.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            suggestionBarHeight // Start with suggestion bar height
        )
        viewFlipper.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Set layout params for children inside ViewFlipper
        val scrollLayoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        scrollView.layoutParams = scrollLayoutParams

        val contentLayoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        contentPaneContainer.layoutParams = contentLayoutParams

        // Add views to flipper - index 0 = scrollView, index 1 = contentPane
        viewFlipper.addView(scrollView)       // index 0 - shown by default
        viewFlipper.addView(contentPaneContainer) // index 1

        inputViewContainer.addView(viewFlipper)

        // Note: Keyboard view is added by caller after this method returns

        return InitializationResult(
            inputViewContainer,
            suggestionBar,
            contentPaneContainer,
            scrollView,
            viewFlipper
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
