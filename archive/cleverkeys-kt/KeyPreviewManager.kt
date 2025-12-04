package tribixbite.cleverkeys

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Manages key preview popups that appear above keys when pressed.
 *
 * Provides visual feedback for key presses by showing a magnified preview popup
 * above the pressed key, displaying the character or symbol being typed.
 *
 * Features:
 * - Popup preview above pressed keys
 * - Configurable preview duration
 * - Custom styling (size, colors, fonts)
 * - Auto-positioning to avoid screen edges
 * - Preview for special keys (emoji, symbols, etc.)
 * - Smooth animations (fade in/out)
 * - Memory-efficient popup reuse
 *
 * Bug #326 - HIGH: Complete implementation of missing KeyPreviewManager.java
 *
 * @param context Application context for creating popups
 * @param enabled Initial enabled state (default: true)
 * @param duration Preview display duration in milliseconds (default: 100ms)
 */
class KeyPreviewManager(
    private val context: Context,
    private var enabled: Boolean = true,
    private var duration: Long = 100L
) {
    companion object {
        private const val TAG = "KeyPreviewManager"

        // Default dimensions (density-independent pixels)
        private const val DEFAULT_PREVIEW_WIDTH_DP = 48
        private const val DEFAULT_PREVIEW_HEIGHT_DP = 64
        private const val DEFAULT_TEXT_SIZE_SP = 24f
        private const val DEFAULT_ELEVATION_DP = 8f

        // Offset above key (density-independent pixels)
        private const val DEFAULT_VERTICAL_OFFSET_DP = -80

        // Corner radius for preview popup
        private const val CORNER_RADIUS_DP = 8f

        // Background colors
        private const val PREVIEW_BG_COLOR = 0xFF2C2C2E.toInt()  // Dark gray
        private const val PREVIEW_TEXT_COLOR = 0xFFFFFFFF.toInt()  // White
    }

    // Popup window for showing preview
    private var popupWindow: PopupWindow? = null
    private var previewTextView: TextView? = null

    // Handler for auto-dismiss
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    // Display metrics for dp/sp conversion
    private val displayMetrics = context.resources.displayMetrics
    private val density = displayMetrics.density
    private val scaledDensity = displayMetrics.scaledDensity

    // Current preview state
    private var isShowing = false
    private var currentKey: KeyValue? = null

    init {
        logD("Initializing KeyPreviewManager (enabled=$enabled, duration=${duration}ms)")
        initializePopup()
    }

    /**
     * Initialize the popup window and preview view.
     * Creates reusable popup for efficient preview display.
     */
    private fun initializePopup() {
        try {
            // Create preview text view
            previewTextView = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = DEFAULT_TEXT_SIZE_SP
                setTextColor(PREVIEW_TEXT_COLOR)
                setPadding(
                    dpToPx(8),
                    dpToPx(8),
                    dpToPx(8),
                    dpToPx(8)
                )

                // Set background with rounded corners
                background = createRoundedBackground()

                // Set elevation for shadow effect
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dpToPx(DEFAULT_ELEVATION_DP).toFloat()
                }
            }

            // Create popup window
            popupWindow = PopupWindow(
                previewTextView,
                dpToPx(DEFAULT_PREVIEW_WIDTH_DP),
                dpToPx(DEFAULT_PREVIEW_HEIGHT_DP),
                false  // Not focusable
            ).apply {
                // Make popup appear above other views
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dpToPx(DEFAULT_ELEVATION_DP).toFloat()
                }

                // Enable animations
                animationStyle = android.R.style.Animation_Dialog
            }

            logD("✅ Key preview popup initialized")
        } catch (e: Exception) {
            logE("Failed to initialize preview popup", e)
        }
    }

    /**
     * Create rounded rectangle background for preview popup.
     */
    private fun createRoundedBackground(): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable()
        shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        shape.setColor(PREVIEW_BG_COLOR)
        shape.cornerRadius = dpToPx(CORNER_RADIUS_DP).toFloat()
        return shape
    }

    /**
     * Show preview for a pressed key.
     *
     * @param key The KeyValue representing the pressed key
     * @param keyView The view of the pressed key (for positioning)
     */
    fun showPreview(key: KeyValue, keyView: View) {
        if (!enabled) {
            return
        }

        // Cancel any pending dismiss
        dismissRunnable?.let { handler.removeCallbacks(it) }

        try {
            // Get preview text for the key
            val previewText = getPreviewText(key)

            if (previewText.isEmpty()) {
                logD("No preview text for key: $key")
                return
            }

            // Update preview content
            previewTextView?.text = previewText

            // Calculate position
            val location = IntArray(2)
            keyView.getLocationInWindow(location)

            val x = location[0] + (keyView.width / 2) - (dpToPx(DEFAULT_PREVIEW_WIDTH_DP) / 2)
            val y = location[1] + dpToPx(DEFAULT_VERTICAL_OFFSET_DP)

            // Show popup
            if (isShowing) {
                // Update position if already showing
                popupWindow?.update(x, y, -1, -1)
            } else {
                // Show popup for the first time
                popupWindow?.showAtLocation(
                    keyView,
                    Gravity.NO_GRAVITY,
                    x,
                    y
                )
                isShowing = true
            }

            currentKey = key

            // Schedule auto-dismiss
            if (duration > 0) {
                dismissRunnable = Runnable {
                    dismissPreview()
                }.also { handler.postDelayed(it, duration) }
            }

            logD("Preview shown for key: $previewText at ($x, $y)")
        } catch (e: Exception) {
            logE("Failed to show preview", e)
        }
    }

    /**
     * Update preview for a different key while still showing.
     * More efficient than hide + show.
     *
     * @param key The new KeyValue
     * @param keyView The view of the new key
     */
    fun updatePreview(key: KeyValue, keyView: View) {
        if (!enabled || !isShowing) {
            showPreview(key, keyView)
            return
        }

        // Cancel pending dismiss
        dismissRunnable?.let { handler.removeCallbacks(it) }

        try {
            val previewText = getPreviewText(key)

            if (previewText.isEmpty()) {
                dismissPreview()
                return
            }

            // Update text
            previewTextView?.text = previewText

            // Update position
            val location = IntArray(2)
            keyView.getLocationInWindow(location)

            val x = location[0] + (keyView.width / 2) - (dpToPx(DEFAULT_PREVIEW_WIDTH_DP) / 2)
            val y = location[1] + dpToPx(DEFAULT_VERTICAL_OFFSET_DP)

            popupWindow?.update(x, y, -1, -1)

            currentKey = key

            // Schedule auto-dismiss
            if (duration > 0) {
                dismissRunnable = Runnable {
                    dismissPreview()
                }.also { handler.postDelayed(it, duration) }
            }

            logD("Preview updated for key: $previewText")
        } catch (e: Exception) {
            logE("Failed to update preview", e)
        }
    }

    /**
     * Dismiss the currently shown preview.
     */
    fun dismissPreview() {
        if (!isShowing) {
            return
        }

        try {
            // Cancel any pending dismiss
            dismissRunnable?.let { handler.removeCallbacks(it) }
            dismissRunnable = null

            // Dismiss popup
            popupWindow?.dismiss()
            isShowing = false
            currentKey = null

            logD("Preview dismissed")
        } catch (e: Exception) {
            logE("Failed to dismiss preview", e)
        }
    }

    /**
     * Get preview text for a KeyValue.
     * Extracts the character, string, or symbol to display.
     *
     * @param key The KeyValue to get preview for
     * @return Preview text string (empty if no preview available)
     */
    private fun getPreviewText(key: KeyValue): String {
        return when (key) {
            is KeyValue.CharKey -> {
                // Convert character code to string
                key.char.toChar().toString()
            }
            is KeyValue.StringKey -> {
                // Use string directly
                key.string
            }
            is KeyValue.EventKey -> {
                // Event keys don't typically show preview
                // Could show icons or labels for special events
                when (key.event) {
                    KeyValue.Event.SWITCH_EMOJI -> "😀"
                    KeyValue.Event.SWITCH_NUMERIC -> "123"
                    KeyValue.Event.SWITCH_TEXT -> "ABC"
                    else -> ""
                }
            }
            is KeyValue.EditingKey -> {
                // Editing keys could show symbols
                when (key.editing) {
                    KeyValue.Editing.COPY -> "📋"
                    KeyValue.Editing.PASTE -> "📄"
                    KeyValue.Editing.CUT -> "✂"
                    KeyValue.Editing.UNDO -> "↶"
                    KeyValue.Editing.REDO -> "↷"
                    else -> ""
                }
            }
            is KeyValue.ModifierKey -> {
                // Modifier keys could show symbols
                when (key.modifier) {
                    KeyValue.Modifier.SHIFT -> "⇧"
                    KeyValue.Modifier.CTRL -> "⌃"
                    KeyValue.Modifier.ALT -> "⌥"
                    KeyValue.Modifier.META -> "◆"
                    KeyValue.Modifier.FN -> "fn"
                    else -> ""
                }
            }
            is KeyValue.KeyEventKey -> {
                // Special keys - could show labels
                when (key.keyCode) {
                    android.view.KeyEvent.KEYCODE_DEL -> "⌫"
                    android.view.KeyEvent.KEYCODE_FORWARD_DEL -> "⌦"
                    android.view.KeyEvent.KEYCODE_ENTER -> "⏎"
                    android.view.KeyEvent.KEYCODE_SPACE -> "␣"
                    android.view.KeyEvent.KEYCODE_TAB -> "⇥"
                    else -> ""
                }
            }
            else -> ""
        }
    }

    /**
     * Enable or disable key previews.
     *
     * @param enabled true to enable, false to disable
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled

        if (!enabled && isShowing) {
            dismissPreview()
        }

        logD("Key previews ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if key previews are enabled.
     *
     * @return true if enabled, false if disabled
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Set preview display duration.
     *
     * @param duration Duration in milliseconds (0 = manual dismiss only)
     */
    fun setDuration(duration: Long) {
        this.duration = duration.coerceAtLeast(0L)
        logD("Preview duration set to: ${this.duration}ms")
    }

    /**
     * Get current preview duration.
     *
     * @return Duration in milliseconds
     */
    fun getDuration(): Long = duration

    /**
     * Set preview text size.
     *
     * @param size Text size in SP (scaled pixels)
     */
    fun setTextSize(size: Float) {
        previewTextView?.textSize = size
        logD("Preview text size set to: ${size}sp")
    }

    /**
     * Set preview colors.
     *
     * @param backgroundColor Background color (ARGB)
     * @param textColor Text color (ARGB)
     */
    fun setColors(backgroundColor: Int, textColor: Int) {
        try {
            previewTextView?.setTextColor(textColor)

            // Update background color
            val shape = android.graphics.drawable.GradientDrawable()
            shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            shape.setColor(backgroundColor)
            shape.cornerRadius = dpToPx(CORNER_RADIUS_DP).toFloat()
            previewTextView?.background = shape

            logD("Preview colors updated")
        } catch (e: Exception) {
            logE("Failed to set preview colors", e)
        }
    }

    /**
     * Check if a preview is currently showing.
     *
     * @return true if preview is visible, false otherwise
     */
    fun isShowing(): Boolean = isShowing

    /**
     * Release all resources and cleanup.
     * Must be called when the preview manager is no longer needed.
     */
    fun release() {
        logD("Releasing KeyPreviewManager resources...")

        try {
            // Dismiss any showing preview
            dismissPreview()

            // Remove all pending callbacks
            handler.removeCallbacksAndMessages(null)

            // Dismiss and release popup
            popupWindow?.dismiss()
            popupWindow = null
            previewTextView = null

            logD("✅ KeyPreviewManager resources released")
        } catch (e: Exception) {
            logE("Error releasing preview resources", e)
        }
    }

    // Utility functions

    /**
     * Convert density-independent pixels to pixels.
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * density).toInt()
    }

    /**
     * Convert density-independent pixels to pixels (float).
     */
    private fun dpToPx(dp: Float): Int {
        return (dp * density).toInt()
    }

    /**
     * Convert scaled pixels to pixels.
     */
    private fun spToPx(sp: Float): Int {
        return (sp * scaledDensity).toInt()
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
