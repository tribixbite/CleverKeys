package tribixbite.cleverkeys

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
 * Uses LinearLayout with gravity=bottom to ensure:
 * - Keyboard pinned to bottom
 * - ViewFlipper sits directly above keyboard
 * - No gap between content pane and keyboard
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
        val contentPaneContainer: FrameLayout,
        val scrollView: HorizontalScrollView,
        val viewFlipper: android.widget.ViewFlipper? = null
    )

    /**
     * Initialize suggestion bar and input view container.
     *
     * Creates a complete input view hierarchy:
     * - LinearLayout (vertical, gravity=bottom)
     *   - ViewFlipper (swaps between scrollView and contentPaneContainer)
     *     - HorizontalScrollView (scrollable suggestions) - index 0, shown by default
     *     - FrameLayout (content pane for clipboard/emoji) - index 1
     *   - Keyboard2View (added by caller)
     *
     * Using gravity=bottom ensures ViewFlipper sits directly above keyboard with no gap.
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
        // Create root LinearLayout with gravity=bottom
        // This ensures children (ViewFlipper + Keyboard) stack at the bottom with no gap
        val inputViewContainer = LinearLayout(context)
        inputViewContainer.orientation = LinearLayout.VERTICAL
        inputViewContainer.gravity = Gravity.BOTTOM
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
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.isFillViewport = true
        scrollView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Set suggestion bar to wrap_content width for scrolling
        val suggestionParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        suggestionBar.layoutParams = suggestionParams
        scrollView.addView(suggestionBar)

        // Create content pane container (for clipboard/emoji)
        val contentPaneContainer = FrameLayout(context)
        contentPaneContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Apply bottom padding for navigation bar on Android 15+
        ViewCompat.setOnApplyWindowInsetsListener(contentPaneContainer) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                navBarInsets.bottom
            )
            insets
        }

        // Calculate suggestion bar height
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

        // Set layout params for children inside ViewFlipper (match parent to fill flipper)
        val childParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        scrollView.layoutParams = childParams
        contentPaneContainer.layoutParams = FrameLayout.LayoutParams(childParams)

        // Add views to flipper - index 0 = scrollView, index 1 = contentPane
        viewFlipper.addView(scrollView)           // index 0 - shown by default
        viewFlipper.addView(contentPaneContainer) // index 1

        // Add ViewFlipper to container
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
     * @param context Application context
     * @param heightPercent Height as percentage of screen height (0-100)
     * @return Height in pixels
     */
    @JvmStatic
    fun calculateContentPaneHeight(context: Context, heightPercent: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return (screenHeight * heightPercent) / 100
    }

    /**
     * Switch to content pane mode - expand ViewFlipper height.
     *
     * @param viewFlipper The ViewFlipper to resize
     * @param height Height for content pane in pixels
     */
    @JvmStatic
    fun switchToContentPaneMode(viewFlipper: android.widget.ViewFlipper, height: Int) {
        android.util.Log.i("SuggestionBarInitializer", "switchToContentPaneMode: height=$height")
        val params = viewFlipper.layoutParams
        if (params != null) {
            params.height = height
            viewFlipper.layoutParams = params
        }
    }

    /**
     * Switch to suggestion bar mode - collapse ViewFlipper height.
     *
     * @param viewFlipper The ViewFlipper to resize
     * @param height Height for suggestion bar in pixels
     */
    @JvmStatic
    fun switchToSuggestionBarMode(viewFlipper: android.widget.ViewFlipper, height: Int) {
        android.util.Log.i("SuggestionBarInitializer", "switchToSuggestionBarMode: height=$height")
        val params = viewFlipper.layoutParams
        if (params != null) {
            params.height = height
            viewFlipper.layoutParams = params
        }
    }
}
