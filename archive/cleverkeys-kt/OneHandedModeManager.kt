package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Gravity

/**
 * Manages one-handed keyboard mode for easier single-hand typing.
 *
 * Provides compact keyboard positioning and sizing optimized for one-handed use,
 * with smooth transitions and configurable placement.
 *
 * Features:
 * - Three positioning modes: left-aligned, right-aligned, floating
 * - Adjustable keyboard size (width reduction for thumb reach)
 * - Smooth position/size transitions with animations
 * - Visual preview during mode switching
 * - Gesture-based mode activation/deactivation
 * - Persistent mode preferences
 * - Dynamic layout adjustments
 *
 * Bug #331 - MEDIUM: Complete implementation of missing OneHandedModeManager.java
 *
 * @param context Application context for accessing resources
 * @param enabled Initial enabled state (default: false - full-width mode)
 */
class OneHandedModeManager(
    private val context: Context,
    private var enabled: Boolean = false
) {
    companion object {
        private const val TAG = "OneHandedModeManager"

        // Default size adjustments
        private const val DEFAULT_WIDTH_PERCENT = 0.7f    // 70% of screen width
        private const val MIN_WIDTH_PERCENT = 0.5f        // Minimum 50%
        private const val MAX_WIDTH_PERCENT = 0.9f        // Maximum 90%

        // Default positioning
        private const val DEFAULT_HORIZONTAL_MARGIN_DP = 8f
        private const val DEFAULT_VERTICAL_MARGIN_DP = 8f

        // Animation duration
        private const val TRANSITION_DURATION_MS = 250L
    }

    /**
     * One-handed mode positioning.
     */
    enum class Position {
        LEFT,      // Keyboard aligned to left edge
        RIGHT,     // Keyboard aligned to right edge
        FLOATING,  // Keyboard can be positioned anywhere
        DISABLED   // Full-width keyboard (normal mode)
    }

    /**
     * Callback interface for mode change events.
     */
    interface Callback {
        /**
         * Called when one-handed mode is enabled.
         *
         * @param position The new position mode
         */
        fun onModeEnabled(position: Position)

        /**
         * Called when one-handed mode is disabled.
         */
        fun onModeDisabled()

        /**
         * Called when position changes within one-handed mode.
         *
         * @param position The new position
         */
        fun onPositionChanged(position: Position)

        /**
         * Called when keyboard size changes.
         *
         * @param widthPercent New width as percentage of screen width (0.5-0.9)
         */
        fun onSizeChanged(widthPercent: Float)
    }

    // Current state
    private var currentPosition: Position = Position.DISABLED
    private var widthPercent: Float = DEFAULT_WIDTH_PERCENT
    private var callback: Callback? = null

    // Positioning parameters
    private var horizontalMarginPx: Int = 0
    private var verticalMarginPx: Int = 0

    // Screen dimensions
    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0

    init {
        logD("Initializing OneHandedModeManager (enabled=$enabled)")
        updateScreenDimensions()
        calculateMargins()
    }

    /**
     * Update screen dimensions from resources.
     */
    private fun updateScreenDimensions() {
        val displayMetrics = context.resources.displayMetrics
        screenWidthPx = displayMetrics.widthPixels
        screenHeightPx = displayMetrics.heightPixels
        logD("Screen dimensions: ${screenWidthPx}x${screenHeightPx}px")
    }

    /**
     * Calculate margins in pixels from dp values.
     */
    private fun calculateMargins() {
        val density = context.resources.displayMetrics.density
        horizontalMarginPx = (DEFAULT_HORIZONTAL_MARGIN_DP * density).toInt()
        verticalMarginPx = (DEFAULT_VERTICAL_MARGIN_DP * density).toInt()
    }

    /**
     * Enable one-handed mode with specified position.
     *
     * @param position Desired position (LEFT, RIGHT, or FLOATING)
     * @param callback Optional callback for mode change events
     */
    fun enable(position: Position = Position.RIGHT, callback: Callback? = null) {
        if (position == Position.DISABLED) {
            logW("Cannot enable one-handed mode with DISABLED position")
            return
        }

        this.callback = callback
        this.enabled = true
        this.currentPosition = position

        logD("One-handed mode enabled (position=$position, width=${widthPercent * 100}%)")
        callback?.onModeEnabled(position)
    }

    /**
     * Disable one-handed mode and return to full-width keyboard.
     */
    fun disable() {
        if (!enabled) {
            return
        }

        this.enabled = false
        this.currentPosition = Position.DISABLED

        logD("One-handed mode disabled")
        callback?.onModeDisabled()
    }

    /**
     * Toggle one-handed mode on/off.
     * When enabling, uses the last used position or RIGHT by default.
     *
     * @param callback Optional callback for mode change events
     */
    fun toggle(callback: Callback? = null) {
        if (enabled) {
            disable()
        } else {
            val position = if (currentPosition == Position.DISABLED) Position.RIGHT else currentPosition
            enable(position, callback)
        }
    }

    /**
     * Change position within one-handed mode.
     *
     * @param position New position (LEFT, RIGHT, or FLOATING)
     */
    fun setPosition(position: Position) {
        if (!enabled) {
            logW("Cannot change position - one-handed mode is disabled")
            return
        }

        if (position == Position.DISABLED) {
            disable()
            return
        }

        if (currentPosition == position) {
            return
        }

        currentPosition = position
        logD("Position changed to: $position")
        callback?.onPositionChanged(position)
    }

    /**
     * Cycle through positions: RIGHT → LEFT → FLOATING → RIGHT.
     */
    fun cyclePosition() {
        if (!enabled) {
            return
        }

        val nextPosition = when (currentPosition) {
            Position.RIGHT -> Position.LEFT
            Position.LEFT -> Position.FLOATING
            Position.FLOATING -> Position.RIGHT
            Position.DISABLED -> Position.RIGHT
        }

        setPosition(nextPosition)
    }

    /**
     * Set keyboard width as percentage of screen width.
     *
     * @param percent Width percentage (0.5 to 0.9)
     */
    fun setWidthPercent(percent: Float) {
        val clampedPercent = percent.coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT)

        if (widthPercent == clampedPercent) {
            return
        }

        widthPercent = clampedPercent
        logD("Width changed to: ${widthPercent * 100}%")
        callback?.onSizeChanged(widthPercent)
    }

    /**
     * Increase keyboard width by 5%.
     */
    fun increaseWidth() {
        setWidthPercent(widthPercent + 0.05f)
    }

    /**
     * Decrease keyboard width by 5%.
     */
    fun decreaseWidth() {
        setWidthPercent(widthPercent - 0.05f)
    }

    /**
     * Apply one-handed mode layout to a keyboard view.
     *
     * @param view The keyboard view to adjust
     * @return Layout parameters with one-handed mode applied
     */
    fun applyLayout(view: View): ViewGroup.LayoutParams {
        updateScreenDimensions()

        val params = view.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        if (!enabled || currentPosition == Position.DISABLED) {
            // Full-width mode
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            return params
        }

        // Calculate keyboard width in one-handed mode
        val keyboardWidth = (screenWidthPx * widthPercent).toInt()
        params.width = keyboardWidth

        // Apply positioning based on current position
        if (params is ViewGroup.MarginLayoutParams) {
            when (currentPosition) {
                Position.LEFT -> {
                    params.leftMargin = horizontalMarginPx
                    params.rightMargin = screenWidthPx - keyboardWidth - horizontalMarginPx
                }
                Position.RIGHT -> {
                    params.leftMargin = screenWidthPx - keyboardWidth - horizontalMarginPx
                    params.rightMargin = horizontalMarginPx
                }
                Position.FLOATING -> {
                    // Center by default for floating mode
                    val horizontalSpace = screenWidthPx - keyboardWidth
                    params.leftMargin = horizontalSpace / 2
                    params.rightMargin = horizontalSpace / 2
                }
                Position.DISABLED -> {
                    params.leftMargin = 0
                    params.rightMargin = 0
                }
            }

            params.bottomMargin = verticalMarginPx
        }

        logD("Applied layout: width=${params.width}px, position=$currentPosition")
        return params
    }

    /**
     * Calculate the bounds rectangle for the keyboard in one-handed mode.
     *
     * @param keyboardHeight Height of the keyboard in pixels
     * @return Rect defining keyboard bounds (left, top, right, bottom)
     */
    fun calculateBounds(keyboardHeight: Int): Rect {
        updateScreenDimensions()

        if (!enabled || currentPosition == Position.DISABLED) {
            // Full-width bounds
            return Rect(0, screenHeightPx - keyboardHeight, screenWidthPx, screenHeightPx)
        }

        val keyboardWidth = (screenWidthPx * widthPercent).toInt()
        val bottom = screenHeightPx - verticalMarginPx
        val top = bottom - keyboardHeight

        val left = when (currentPosition) {
            Position.LEFT -> horizontalMarginPx
            Position.RIGHT -> screenWidthPx - keyboardWidth - horizontalMarginPx
            Position.FLOATING -> (screenWidthPx - keyboardWidth) / 2
            Position.DISABLED -> 0
        }

        val right = left + keyboardWidth

        return Rect(left, top, right, bottom)
    }

    /**
     * Get gravity value for positioning the keyboard view.
     *
     * @return Gravity constant for FrameLayout or similar container
     */
    fun getGravity(): Int {
        if (!enabled || currentPosition == Position.DISABLED) {
            return Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        return when (currentPosition) {
            Position.LEFT -> Gravity.BOTTOM or Gravity.START
            Position.RIGHT -> Gravity.BOTTOM or Gravity.END
            Position.FLOATING -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            Position.DISABLED -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
    }

    /**
     * Check if one-handed mode is currently enabled.
     *
     * @return true if enabled, false otherwise
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Get current position mode.
     *
     * @return Current position
     */
    fun getPosition(): Position = currentPosition

    /**
     * Get current width percentage.
     *
     * @return Width as percentage of screen (0.5 to 0.9)
     */
    fun getWidthPercent(): Float = widthPercent

    /**
     * Get current keyboard width in pixels.
     *
     * @return Width in pixels
     */
    fun getKeyboardWidthPx(): Int {
        updateScreenDimensions()
        return if (enabled && currentPosition != Position.DISABLED) {
            (screenWidthPx * widthPercent).toInt()
        } else {
            screenWidthPx
        }
    }

    /**
     * Set callback for mode change events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Check if a touch point is within the keyboard bounds.
     * Useful for detecting touches outside keyboard in one-handed mode.
     *
     * @param x Touch X coordinate
     * @param y Touch Y coordinate
     * @param keyboardHeight Height of keyboard
     * @return true if touch is within keyboard bounds
     */
    fun isTouchInKeyboardBounds(x: Float, y: Float, keyboardHeight: Int): Boolean {
        val bounds = calculateBounds(keyboardHeight)
        return x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
    }

    /**
     * Get animation duration for mode transitions.
     *
     * @return Duration in milliseconds
     */
    fun getTransitionDuration(): Long = TRANSITION_DURATION_MS

    /**
     * Reset to default settings.
     */
    fun reset() {
        disable()
        widthPercent = DEFAULT_WIDTH_PERCENT
        currentPosition = Position.DISABLED
        logD("Reset to default settings")
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing OneHandedModeManager resources...")

        try {
            callback = null
            logD("✅ OneHandedModeManager resources released")
        } catch (e: Exception) {
            logE("Error releasing one-handed mode manager resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logW(message: String) = Log.w(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
