package tribixbite.cleverkeys

import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.graphics.drawable.ColorDrawable
import android.graphics.Color

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

/**
 * One system-bar inset read. Left/right/bottom only — the IME window never fits the top
 * (status-bar) inset, so it is deliberately absent rather than carried and ignored.
 */
data class SystemBarInsets(val left: Int, val right: Int, val bottom: Int)

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
     * Configures window for edge-to-edge display.
     * Sets display cutout mode and allows drawing behind system bars.
     *
     * Extended to support API 29+ to fix white bar issues on OEM devices.
     * Previously only applied on API 35+, but this caused visual artifacts
     * on some OEM devices with API 29-34.
     *
     * @param window The window to configure
     */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun configureEdgeToEdge(window: Window) {
        // Every branch that mutates the LayoutParams must write them back via
        // Window.setAttributes: getAttributes() returns the live params object,
        // and in-place mutation only reaches the WindowManager when the window
        // is FIRST added. On an already-showing IME window the write-back is
        // what dispatches onWindowAttributesChanged → ViewRootImpl relayout —
        // without it, the per-onStartInputView re-application of cutout mode +
        // fitInsetsTypes(0) silently no-ops, so a system-side reset was never
        // repaired until window recreation (issue #167, Android 15 nav-bar meld).

        // API 35+: Full edge-to-edge support
        if (Build.VERSION.SDK_INT >= 35) {
            val wattrs = window.attributes
            wattrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Allow drawing behind system bars
            wattrs.setFitInsetsTypes(0)
            window.attributes = wattrs
            window.setDecorFitsSystemWindows(false)
        }
        // API 30-34: Basic edge-to-edge support to avoid OEM scrim issues
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wattrs = window.attributes
            wattrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = wattrs
            window.setDecorFitsSystemWindows(false)
        }
        // API 29: Limited edge-to-edge support
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wattrs = window.attributes
            wattrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = wattrs
        }

        // Clear any background on the decor view and window that might cause white bar
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * The ONE API ladder for reading system-bar insets out of a [WindowInsets] (#167
     * residual). Keyboard2View previously kept two hand-rolled copies that disagreed on
     * API 30+ — its onApplyWindowInsets read `systemBars() | displayCutout()` while its
     * onMeasure recovery read `systemBars()` only, so a recovered value could differ from
     * the value the real dispatch later served. Both sites now delegate here.
     *
     * - API 30+: `getInsets(systemBars | displayCutout)` — the cutout term matters because
     *   the window opts into `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` on 35+ (see
     *   [configureEdgeToEdge]) and must keep keys out of the notch in landscape.
     * - API 29: `systemWindowInsets` (deprecated but the only aggregate on Q).
     * - API 21-28: the per-side deprecated getters (`getSystemWindowInsets()` unavailable).
     */
    @JvmStatic
    fun readSystemBarInsets(wi: WindowInsets): SystemBarInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wi.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return SystemBarInsets(insets.left, insets.right, insets.bottom)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val insets = wi.systemWindowInsets
            return SystemBarInsets(insets.left, insets.right, insets.bottom)
        }
        @Suppress("DEPRECATION")
        return SystemBarInsets(
            wi.systemWindowInsetLeft,
            wi.systemWindowInsetRight,
            wi.systemWindowInsetBottom
        )
    }

    /**
     * Configuration/navigation-mode change re-derivation (#167 residual — the staleness
     * half). A nav-mode switch (3-button bar → gesture pill) or rotation can change the
     * system-bar insets while the IME window survives; a cached inset with only
     * `== 0`-guarded recoveries then serves the stale bottom value (last keyboard row
     * melded into, or floating above, the nav bar) until full window recreation.
     *
     * This helper takes no cached value at all — it re-derives from the live root window
     * so a stale cache structurally cannot be served — and ALWAYS follows up with
     * [View.requestApplyInsets]: `rootWindowInsets` can itself lag mid-configuration-change,
     * and the fresh dispatch through onApplyWindowInsets is the authoritative corrector.
     *
     * @return the synchronously re-derived insets, or null when none are readable yet
     *         (detached root / pre-API-23) — the requested dispatch covers that case.
     */
    @JvmStatic
    fun refreshSystemBarInsets(view: View): SystemBarInsets? {
        val fresh = if (Build.VERSION.SDK_INT >= 23) {
            view.rootWindowInsets?.let { readSystemBarInsets(it) }
        } else {
            null
        }
        view.requestApplyInsets()
        return fresh
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

        // Set window to WRAP_CONTENT to avoid white bar artifacts during animation
        // MATCH_PARENT causes the window to be full screen, exposing empty space
        updateLayoutHeightOf(window, ViewGroup.LayoutParams.WRAP_CONTENT)

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
            // Clear any background that might cause white bar on OEM devices
            it.setBackgroundColor(Color.TRANSPARENT)
        }

        // Also clear inputArea background
        inputArea.setBackgroundColor(Color.TRANSPARENT)
    }
}
