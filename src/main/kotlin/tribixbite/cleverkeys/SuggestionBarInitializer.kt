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
 * Simplified approach: No ViewFlipper. Just a topPane FrameLayout that contains
 * either the scrollView (suggestion bar) or contentPaneContainer (emoji/clipboard).
 * Views are swapped by removing/adding children.
 *
 * Hierarchy:
 * - inputViewContainer (LinearLayout, VERTICAL)
 *   - topPane (FrameLayout) - contains either scrollView OR contentPaneContainer
 *   - keyboardView (added by caller)
 *
 * @since v1.32.381
 */
object SuggestionBarInitializer {

    /**
     * Result of suggestion bar initialization.
     */
    data class InitializationResult(
        val inputViewContainer: LinearLayout,
        val suggestionBar: SuggestionBar,
        val contentPaneContainer: FrameLayout,
        val scrollView: HorizontalScrollView,
        val topPane: FrameLayout
    )

    /**
     * Initialize suggestion bar and input view container.
     */
    @JvmStatic
    fun initialize(
        context: Context,
        theme: Theme?,
        opacity: Int,
        clipboardPaneHeightPercent: Int
    ): InitializationResult {
        // Root container - NO gravity setting, just stack vertically
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
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.isFillViewport = true

        val suggestionParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        suggestionBar.layoutParams = suggestionParams
        scrollView.addView(suggestionBar)

        // Create content pane container (for clipboard/emoji)
        val contentPaneContainer = FrameLayout(context)

        // Calculate suggestion bar height
        val suggestionBarHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            40f,
            context.resources.displayMetrics
        ).toInt()

        // Create topPane - a simple FrameLayout that holds the current view
        val topPane = FrameLayout(context)
        topPane.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            suggestionBarHeight
        )

        // Set scrollView to fill topPane
        scrollView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Start with scrollView (suggestion bar)
        topPane.addView(scrollView)

        // Add topPane to container (keyboard view added by caller)
        inputViewContainer.addView(topPane)

        return InitializationResult(
            inputViewContainer,
            suggestionBar,
            contentPaneContainer,
            scrollView,
            topPane
        )
    }

    /**
     * Calculate content pane height in pixels.
     */
    @JvmStatic
    fun calculateContentPaneHeight(context: Context, heightPercent: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return (screenHeight * heightPercent) / 100
    }

    /**
     * Switch topPane to show content pane with specified height.
     */
    @JvmStatic
    fun switchToContentPaneMode(topPane: FrameLayout, contentPane: FrameLayout, scrollView: HorizontalScrollView, height: Int) {
        android.util.Log.i("SuggestionBarInitializer", "switchToContentPaneMode: height=$height, contentPane.childCount=${contentPane.childCount}")

        // Remove scrollView if present
        if (scrollView.parent == topPane) {
            topPane.removeView(scrollView)
        }

        // Resize topPane
        val params = topPane.layoutParams
        params.height = height
        topPane.layoutParams = params

        // Set contentPane with explicit height (not MATCH_PARENT)
        contentPane.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            height
        )

        // Add contentPane if not already added
        if (contentPane.parent != topPane) {
            if (contentPane.parent != null) {
                (contentPane.parent as? android.view.ViewGroup)?.removeView(contentPane)
            }
            topPane.addView(contentPane)
        }

        topPane.requestLayout()
        android.util.Log.i("SuggestionBarInitializer", "switchToContentPaneMode complete: topPane.childCount=${topPane.childCount}")
    }

    /**
     * Switch topPane to show suggestion bar with specified height.
     */
    @JvmStatic
    fun switchToSuggestionBarMode(topPane: FrameLayout, contentPane: FrameLayout, scrollView: HorizontalScrollView, height: Int) {
        android.util.Log.i("SuggestionBarInitializer", "switchToSuggestionBarMode: height=$height")

        // Remove contentPane if present
        if (contentPane.parent == topPane) {
            topPane.removeView(contentPane)
        }

        // Resize topPane
        val params = topPane.layoutParams
        params.height = height
        topPane.layoutParams = params

        // Set scrollView with explicit height
        scrollView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            height
        )

        // Add scrollView if not already added
        if (scrollView.parent != topPane) {
            if (scrollView.parent != null) {
                (scrollView.parent as? android.view.ViewGroup)?.removeView(scrollView)
            }
            topPane.addView(scrollView)
        }

        topPane.requestLayout()
        android.util.Log.i("SuggestionBarInitializer", "switchToSuggestionBarMode complete: topPane.childCount=${topPane.childCount}")
    }
}
