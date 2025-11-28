package tribixbite.keyboard2

import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Utility functions for managing IME window and view layout parameters.
 *
 * This object centralizes logic for:
 * - Window layout height management
 * - View layout height management
 * - View gravity management (LinearLayout and FrameLayout)
 * - Edge-to-edge display configuration (API 35+)
 * - Fullscreen mode layout adjustments
 *
 * Responsibilities:
 * - Update window layout parameters dynamically
 * - Handle display cutout modes for modern Android versions
 * - Manage input area positioning and sizing
 * - Apply gravity to view layouts
 *
 * NOT included (remains in CleverKeysService):
 * - InputMethodService window access (getWindow())
 * - Fullscreen mode detection (isFullscreenMode())
 * - Edge-to-edge configuration policy decisions
 *
 * This utility is extracted from CleverKeysService.java for better code organization,
 * testability, and to demonstrate Kotlin usage (v1.32.375).
 *
 * @since v1.32.375
 */
object WindowLayoutUtils {

    /**
     * Updates the height of a window's layout parameters if different from current value.
     *
     * @param window The window to update
     * @param layoutHeight The desired height (e.g., MATCH_PARENT, WRAP_CONTENT, or specific dp)
     */
    @JvmStatic
    fun updateLayoutHeightOf(window: Window, layoutHeight: Int) {
        val params = window.attributes
        if (params != null && params.height != layoutHeight) {
            params.height = layoutHeight
            window.attributes = params
        }
    }

    /**
     * Updates the height of a view's layout parameters if different from current value.
     *
     * @param view The view to update
     * @param layoutHeight The desired height (e.g., MATCH_PARENT, WRAP_CONTENT, or specific dp)
     */
    @JvmStatic
    fun updateLayoutHeightOf(view: View, layoutHeight: Int) {
        val params = view.layoutParams
        if (params != null && params.height != layoutHeight) {
            params.height = layoutHeight
            view.layoutParams = params
        }
    }

    /**
     * Updates the gravity of a view's layout parameters if different from current value.
     * Supports LinearLayout.LayoutParams and FrameLayout.LayoutParams.
     *
     * @param view The view to update
     * @param layoutGravity The desired gravity (e.g., Gravity.BOTTOM, Gravity.CENTER)
     */
    @JvmStatic
    fun updateLayoutGravityOf(view: View, layoutGravity: Int) {
        when (val lp = view.layoutParams) {
            is LinearLayout.LayoutParams -> {
                if (lp.gravity != layoutGravity) {
                    lp.gravity = layoutGravity
                    view.layoutParams = lp
                }
            }
            is FrameLayout.LayoutParams -> {
                if (lp.gravity != layoutGravity) {
                    lp.gravity = layoutGravity
                    view.layoutParams = lp
                }
            }
        }
    }

    /**
     * Configures window for edge-to-edge display on Android 35+.
     * Sets display cutout mode and allows drawing behind system bars.
     *
     * Note: Only applies configuration on API 35+. APIs 30-34 have visual artifacts
     * with edge-to-edge enabled, so we skip configuration on those versions.
     *
     * @param window The window to configure
     */
    @JvmStatic
    fun configureEdgeToEdge(window: Window) {
        if (Build.VERSION.SDK_INT >= 35) {
            val wattrs = window.attributes
            wattrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Allow drawing behind system bars
            wattrs.setFitInsetsTypes(0)
            window.setDecorFitsSystemWindows(false)
        }
    }

    /**
     * Updates soft input window layout parameters for IME.
     * Configures edge-to-edge display, window height, input area height, and gravity.
     *
     * @param window The IME window
     * @param inputArea The input area view (typically found via android.R.id.inputArea)
     * @param isFullscreen Whether the IME is in fullscreen mode
     */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun updateSoftInputWindowLayoutParams(
        window: Window,
        inputArea: View,
        isFullscreen: Boolean
    ) {
        // Configure edge-to-edge for API 35+
        configureEdgeToEdge(window)

        // Set window to match parent height
        updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT)

        // Set input area parent height based on fullscreen mode
        val inputAreaParent = inputArea.parent as? View
        inputAreaParent?.let {
            val height = if (isFullscreen) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            updateLayoutHeightOf(it, height)
            updateLayoutGravityOf(it, Gravity.BOTTOM)
        }
    }
}
