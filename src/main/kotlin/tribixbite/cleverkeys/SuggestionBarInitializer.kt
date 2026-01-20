package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Utility class for initializing the suggestion bar and input view container.
 *
 * This class centralizes logic for:
 * - Creating suggestion bar with theme support
 * - Wrapping suggestion bar in scrollable container
 * - Creating content pane container for clipboard/emoji
 * - Assembling complete input view hierarchy using ConstraintLayout
 *
 * Uses ConstraintLayout to ensure:
 * - Keyboard pinned to bottom
 * - ViewFlipper fills space above keyboard up to max height
 * - No gap between content pane and keyboard
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

    // View IDs for ConstraintLayout (generated once at class load)
    private val VIEW_FLIPPER_ID: Int by lazy { View.generateViewId() }
    private val KEYBOARD_VIEW_ID: Int by lazy { View.generateViewId() }

    /**
     * Get the keyboard view ID for constraint references.
     */
    @JvmStatic
    fun getKeyboardViewId(): Int = KEYBOARD_VIEW_ID

    /**
     * Get the ViewFlipper ID for constraint references.
     */
    @JvmStatic
    fun getViewFlipperId(): Int = VIEW_FLIPPER_ID

    /**
     * Result of suggestion bar initialization.
     *
     * @property inputViewContainer The root ConstraintLayout containing all views
     * @property suggestionBar The created SuggestionBar instance
     * @property contentPaneContainer The FrameLayout for clipboard/emoji panes
     * @property scrollView The HorizontalScrollView wrapping the suggestion bar
     * @property viewFlipper The ViewFlipper that swaps between scrollView and contentPaneContainer
     * @property keyboardViewId The ID to use when adding keyboard view
     */
    data class InitializationResult(
        val inputViewContainer: ConstraintLayout,
        val suggestionBar: SuggestionBar,
        val contentPaneContainer: android.widget.FrameLayout,
        val scrollView: HorizontalScrollView,
        val viewFlipper: android.widget.ViewFlipper? = null,
        val keyboardViewId: Int = KEYBOARD_VIEW_ID
    )

    /**
     * Initialize suggestion bar and input view container.
     *
     * Creates a complete input view hierarchy using ConstraintLayout:
     * - ConstraintLayout (root)
     *   - ViewFlipper (swaps between scrollView and contentPaneContainer)
     *     - HorizontalScrollView (scrollable suggestions) - index 0, shown by default
     *     - FrameLayout (content pane for clipboard/emoji) - index 1
     *   - Keyboard2View (added by caller, pinned to bottom)
     *
     * ConstraintLayout ensures:
     * - Keyboard is always pinned to bottom
     * - ViewFlipper fills space above keyboard with configurable max height
     * - No gap between content pane and keyboard
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
        // Create root ConstraintLayout container
        val inputViewContainer = ConstraintLayout(context)
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
        val suggestionParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        suggestionBar.layoutParams = suggestionParams
        scrollView.addView(suggestionBar)

        // Create content pane container (for clipboard/emoji)
        val contentPaneContainer = android.widget.FrameLayout(context)
        contentPaneContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // FIX #1131: Apply bottom padding for navigation bar on Android 15+
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
        viewFlipper.id = VIEW_FLIPPER_ID
        viewFlipper.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Set layout params for children inside ViewFlipper (match parent)
        val childParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        scrollView.layoutParams = childParams
        contentPaneContainer.layoutParams = android.widget.FrameLayout.LayoutParams(childParams)

        // Add views to flipper - index 0 = scrollView, index 1 = contentPane
        viewFlipper.addView(scrollView)           // index 0 - shown by default
        viewFlipper.addView(contentPaneContainer) // index 1

        // Add ViewFlipper to container (keyboard added by caller)
        inputViewContainer.addView(viewFlipper)

        // Set up initial constraints (suggestion bar mode - fixed height, bottom of parent)
        val constraintSet = ConstraintSet()
        constraintSet.clone(inputViewContainer)

        // ViewFlipper: width=match_parent, height=suggestionBarHeight, pinned to bottom
        constraintSet.constrainWidth(VIEW_FLIPPER_ID, ConstraintSet.MATCH_CONSTRAINT)
        constraintSet.constrainHeight(VIEW_FLIPPER_ID, suggestionBarHeight)
        constraintSet.connect(VIEW_FLIPPER_ID, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraintSet.connect(VIEW_FLIPPER_ID, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        constraintSet.connect(VIEW_FLIPPER_ID, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

        constraintSet.applyTo(inputViewContainer)

        // Note: Keyboard view is added by caller using keyboardViewId
        // When keyboard is added, constraints should be updated to pin keyboard to bottom
        // and ViewFlipper to sit above keyboard

        return InitializationResult(
            inputViewContainer,
            suggestionBar,
            contentPaneContainer,
            scrollView,
            viewFlipper,
            KEYBOARD_VIEW_ID
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

    /**
     * Add keyboard view to container with proper constraints.
     *
     * Sets up ConstraintLayout constraints:
     * - Keyboard pinned to bottom of parent
     * - ViewFlipper sits above keyboard
     *
     * @param container The ConstraintLayout container
     * @param keyboardView The keyboard view to add
     * @param suggestionBarHeight Height of suggestion bar in pixels
     */
    @JvmStatic
    fun addKeyboardWithConstraints(
        container: ConstraintLayout,
        keyboardView: View,
        suggestionBarHeight: Int
    ) {
        // Set keyboard ID for constraint references
        keyboardView.id = KEYBOARD_VIEW_ID

        // Add keyboard to container
        container.addView(keyboardView)

        // Set up constraints
        val constraintSet = ConstraintSet()
        constraintSet.clone(container)

        // Keyboard: width=match_parent, height=wrap_content, pinned to bottom
        constraintSet.constrainWidth(KEYBOARD_VIEW_ID, ConstraintSet.MATCH_CONSTRAINT)
        constraintSet.constrainHeight(KEYBOARD_VIEW_ID, ConstraintSet.WRAP_CONTENT)
        constraintSet.connect(KEYBOARD_VIEW_ID, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraintSet.connect(KEYBOARD_VIEW_ID, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        constraintSet.connect(KEYBOARD_VIEW_ID, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

        // ViewFlipper: sits above keyboard with fixed height (suggestion bar mode)
        constraintSet.clear(VIEW_FLIPPER_ID, ConstraintSet.BOTTOM)
        constraintSet.connect(VIEW_FLIPPER_ID, ConstraintSet.BOTTOM, KEYBOARD_VIEW_ID, ConstraintSet.TOP)
        constraintSet.constrainHeight(VIEW_FLIPPER_ID, suggestionBarHeight)

        constraintSet.applyTo(container)
    }

    /**
     * Switch to content pane mode - expand ViewFlipper to fill space above keyboard.
     *
     * Uses fixed height based on user setting. ViewFlipper sits directly above keyboard.
     *
     * @param container The ConstraintLayout container
     * @param maxHeight Height for content pane in pixels
     */
    @JvmStatic
    fun switchToContentPaneMode(container: ConstraintLayout, maxHeight: Int) {
        android.util.Log.i("SuggestionBarInitializer", "switchToContentPaneMode: maxHeight=$maxHeight, VIEW_FLIPPER_ID=$VIEW_FLIPPER_ID, KEYBOARD_VIEW_ID=$KEYBOARD_VIEW_ID")

        val constraintSet = ConstraintSet()
        constraintSet.clone(container)

        // ViewFlipper: fixed height, constrained to sit above keyboard
        // Use fixed height (user's configured percentage of screen)
        constraintSet.constrainHeight(VIEW_FLIPPER_ID, maxHeight)
        // Keep bottom connected to keyboard top (no gap)
        constraintSet.connect(VIEW_FLIPPER_ID, ConstraintSet.BOTTOM, KEYBOARD_VIEW_ID, ConstraintSet.TOP)
        // Clear top constraint - we want it to "hang" from keyboard, not stretch from top
        constraintSet.clear(VIEW_FLIPPER_ID, ConstraintSet.TOP)

        constraintSet.applyTo(container)

        android.util.Log.i("SuggestionBarInitializer", "switchToContentPaneMode: constraints applied")
    }

    /**
     * Switch to suggestion bar mode - collapse ViewFlipper to fixed height.
     *
     * @param container The ConstraintLayout container
     * @param suggestionBarHeight Fixed height for suggestion bar in pixels
     */
    @JvmStatic
    fun switchToSuggestionBarMode(container: ConstraintLayout, suggestionBarHeight: Int) {
        android.util.Log.i("SuggestionBarInitializer", "switchToSuggestionBarMode: suggestionBarHeight=$suggestionBarHeight")

        val constraintSet = ConstraintSet()
        constraintSet.clone(container)

        // ViewFlipper: fixed height, constrained to sit above keyboard
        constraintSet.constrainHeight(VIEW_FLIPPER_ID, suggestionBarHeight)
        // Keep bottom connected to keyboard
        constraintSet.connect(VIEW_FLIPPER_ID, ConstraintSet.BOTTOM, KEYBOARD_VIEW_ID, ConstraintSet.TOP)
        // Clear top constraint
        constraintSet.clear(VIEW_FLIPPER_ID, ConstraintSet.TOP)

        constraintSet.applyTo(container)

        android.util.Log.i("SuggestionBarInitializer", "switchToSuggestionBarMode: constraints applied")
    }
}
