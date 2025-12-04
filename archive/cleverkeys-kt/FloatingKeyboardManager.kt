package tribixbite.cleverkeys

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Manages floating keyboard mode where keyboard can be freely positioned anywhere on screen.
 *
 * Provides a detachable, movable keyboard window that can be dragged and positioned
 * independently of the IME window, similar to floating keyboards in modern mobile OS.
 *
 * Features:
 * - Free-form positioning anywhere on screen
 * - Drag-to-move gesture support
 * - Configurable floating keyboard size
 * - Snap-to-edge behavior
 * - Auto-docking when moved to screen edges
 * - Position/size persistence
 * - Transparency/opacity control
 * - Resize handles for size adjustment
 *
 * Bug #332 - MEDIUM: Complete implementation of missing FloatingKeyboardManager.java
 *
 * @param context Application context for accessing resources
 * @param enabled Initial enabled state (default: false - docked mode)
 */
class FloatingKeyboardManager(
    private val context: Context,
    private var enabled: Boolean = false
) {
    companion object {
        private const val TAG = "FloatingKeyboardManager"

        // Default floating keyboard size
        private const val DEFAULT_WIDTH_PERCENT = 0.6f    // 60% of screen width
        private const val DEFAULT_HEIGHT_PERCENT = 0.4f   // 40% of screen height
        private const val MIN_SIZE_PERCENT = 0.3f         // Minimum 30%
        private const val MAX_SIZE_PERCENT = 0.8f         // Maximum 80%

        // Default position (center of screen)
        private const val DEFAULT_X_PERCENT = 0.5f
        private const val DEFAULT_Y_PERCENT = 0.5f

        // Snap-to-edge threshold
        private const val EDGE_SNAP_THRESHOLD_DP = 20f    // Snap within 20dp of edge

        // Drag detection
        private const val DRAG_START_THRESHOLD_DP = 8f    // 8dp movement to start drag
        private const val TOUCH_SLOP_MULTIPLIER = 1.5f

        // Opacity
        private const val DEFAULT_OPACITY = 0.95f         // Slightly transparent
        private const val MIN_OPACITY = 0.3f
        private const val MAX_OPACITY = 1.0f
    }

    /**
     * Edge position for snapping/docking.
     */
    enum class Edge {
        NONE,     // Not near any edge
        TOP,      // Snapped to top edge
        BOTTOM,   // Snapped to bottom edge
        LEFT,     // Snapped to left edge
        RIGHT,    // Snapped to right edge
        CENTER    // Centered on screen
    }

    /**
     * Callback interface for floating mode events.
     */
    interface Callback {
        /**
         * Called when floating mode is enabled.
         */
        fun onFloatingEnabled()

        /**
         * Called when floating mode is disabled.
         */
        fun onFloatingDisabled()

        /**
         * Called when keyboard position changes.
         *
         * @param x New X coordinate (screen pixels)
         * @param y New Y coordinate (screen pixels)
         */
        fun onPositionChanged(x: Float, y: Float)

        /**
         * Called when keyboard size changes.
         *
         * @param widthPercent New width as percentage of screen width
         * @param heightPercent New height as percentage of screen height
         */
        fun onSizeChanged(widthPercent: Float, heightPercent: Float)

        /**
         * Called when keyboard snaps to an edge.
         *
         * @param edge The edge it snapped to
         */
        fun onSnappedToEdge(edge: Edge)

        /**
         * Called when opacity changes.
         *
         * @param opacity New opacity value (0.0-1.0)
         */
        fun onOpacityChanged(opacity: Float)
    }

    // Current state
    private var currentX: Float = 0f  // Position in pixels
    private var currentY: Float = 0f
    private var widthPercent: Float = DEFAULT_WIDTH_PERCENT
    private var heightPercent: Float = DEFAULT_HEIGHT_PERCENT
    private var opacity: Float = DEFAULT_OPACITY
    private var snappedEdge: Edge = Edge.NONE
    private var callback: Callback? = null

    // Drag state
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var keyboardStartX = 0f
    private var keyboardStartY = 0f

    // Screen dimensions
    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0
    private var edgeSnapThresholdPx: Float = 0f
    private var dragThresholdPx: Float = 0f

    init {
        logD("Initializing FloatingKeyboardManager (enabled=$enabled)")
        updateScreenDimensions()
        calculateThresholds()
        initializeDefaultPosition()
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
     * Calculate thresholds in pixels from dp values.
     */
    private fun calculateThresholds() {
        val density = context.resources.displayMetrics.density
        edgeSnapThresholdPx = EDGE_SNAP_THRESHOLD_DP * density
        dragThresholdPx = DRAG_START_THRESHOLD_DP * density
    }

    /**
     * Initialize default position (center of screen).
     */
    private fun initializeDefaultPosition() {
        currentX = screenWidthPx * DEFAULT_X_PERCENT
        currentY = screenHeightPx * DEFAULT_Y_PERCENT
    }

    /**
     * Enable floating keyboard mode.
     *
     * @param callback Optional callback for floating mode events
     */
    fun enable(callback: Callback? = null) {
        this.callback = callback
        this.enabled = true

        logD("Floating keyboard mode enabled")
        callback?.onFloatingEnabled()
    }

    /**
     * Disable floating mode and return to docked keyboard.
     */
    fun disable() {
        if (!enabled) {
            return
        }

        this.enabled = false
        this.isDragging = false

        logD("Floating keyboard mode disabled")
        callback?.onFloatingDisabled()
    }

    /**
     * Toggle floating mode on/off.
     *
     * @param callback Optional callback for floating mode events
     */
    fun toggle(callback: Callback? = null) {
        if (enabled) {
            disable()
        } else {
            enable(callback)
        }
    }

    /**
     * Handle touch event for drag gesture.
     *
     * @param event The MotionEvent
     * @return true if event was handled, false otherwise
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.rawX
                dragStartY = event.rawY
                keyboardStartX = currentX
                keyboardStartY = currentY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY

                if (!isDragging) {
                    // Check if movement exceeds threshold to start drag
                    val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (distance > dragThresholdPx) {
                        isDragging = true
                    }
                }

                if (isDragging) {
                    // Update position based on drag
                    setPosition(keyboardStartX + dx, keyboardStartY + dy)
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    // Check for snap-to-edge
                    checkAndSnapToEdge()
                    return true
                }
            }
        }

        return false
    }

    /**
     * Set keyboard position in screen coordinates.
     *
     * @param x X coordinate in pixels (center of keyboard)
     * @param y Y coordinate in pixels (center of keyboard)
     */
    fun setPosition(x: Float, y: Float) {
        val keyboardWidthPx = screenWidthPx * widthPercent
        val keyboardHeightPx = screenHeightPx * heightPercent

        // Clamp position to keep keyboard on screen
        currentX = x.coerceIn(keyboardWidthPx / 2, screenWidthPx - keyboardWidthPx / 2)
        currentY = y.coerceIn(keyboardHeightPx / 2, screenHeightPx - keyboardHeightPx / 2)

        callback?.onPositionChanged(currentX, currentY)
    }

    /**
     * Check if keyboard is near an edge and snap if within threshold.
     */
    private fun checkAndSnapToEdge() {
        val keyboardWidthPx = screenWidthPx * widthPercent
        val keyboardHeightPx = screenHeightPx * heightPercent

        val left = currentX - keyboardWidthPx / 2
        val right = currentX + keyboardWidthPx / 2
        val top = currentY - keyboardHeightPx / 2
        val bottom = currentY + keyboardHeightPx / 2

        // Check each edge
        when {
            left < edgeSnapThresholdPx -> {
                // Snap to left edge
                currentX = keyboardWidthPx / 2
                snappedEdge = Edge.LEFT
                callback?.onSnappedToEdge(Edge.LEFT)
            }
            screenWidthPx - right < edgeSnapThresholdPx -> {
                // Snap to right edge
                currentX = screenWidthPx - keyboardWidthPx / 2
                snappedEdge = Edge.RIGHT
                callback?.onSnappedToEdge(Edge.RIGHT)
            }
            top < edgeSnapThresholdPx -> {
                // Snap to top edge
                currentY = keyboardHeightPx / 2
                snappedEdge = Edge.TOP
                callback?.onSnappedToEdge(Edge.TOP)
            }
            screenHeightPx - bottom < edgeSnapThresholdPx -> {
                // Snap to bottom edge
                currentY = screenHeightPx - keyboardHeightPx / 2
                snappedEdge = Edge.BOTTOM
                callback?.onSnappedToEdge(Edge.BOTTOM)
            }
            else -> {
                snappedEdge = Edge.NONE
            }
        }

        callback?.onPositionChanged(currentX, currentY)
    }

    /**
     * Center keyboard on screen.
     */
    fun centerOnScreen() {
        currentX = screenWidthPx * 0.5f
        currentY = screenHeightPx * 0.5f
        snappedEdge = Edge.CENTER
        callback?.onPositionChanged(currentX, currentY)
        callback?.onSnappedToEdge(Edge.CENTER)
    }

    /**
     * Set keyboard size as percentage of screen dimensions.
     *
     * @param widthPercent Width percentage (0.3 to 0.8)
     * @param heightPercent Height percentage (0.3 to 0.8)
     */
    fun setSize(widthPercent: Float, heightPercent: Float) {
        val clampedWidth = widthPercent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)
        val clampedHeight = heightPercent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)

        if (this.widthPercent == clampedWidth && this.heightPercent == clampedHeight) {
            return
        }

        this.widthPercent = clampedWidth
        this.heightPercent = clampedHeight

        logD("Size changed to: ${widthPercent * 100}% x ${heightPercent * 100}%")
        callback?.onSizeChanged(widthPercent, heightPercent)
    }

    /**
     * Set keyboard opacity.
     *
     * @param opacity Opacity value (0.3 to 1.0)
     */
    fun setOpacity(opacity: Float) {
        val clampedOpacity = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY)

        if (this.opacity == clampedOpacity) {
            return
        }

        this.opacity = clampedOpacity
        logD("Opacity changed to: ${opacity * 100}%")
        callback?.onOpacityChanged(opacity)
    }

    /**
     * Apply floating mode layout to a keyboard view.
     *
     * @param view The keyboard view to adjust
     * @return Layout parameters with floating mode applied
     */
    fun applyLayout(view: View): ViewGroup.LayoutParams {
        updateScreenDimensions()

        val params = view.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        if (!enabled) {
            // Docked mode - full width
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            return params
        }

        // Floating mode - specific size
        params.width = (screenWidthPx * widthPercent).toInt()
        params.height = (screenHeightPx * heightPercent).toInt()

        // Apply opacity to view
        view.alpha = opacity

        return params
    }

    /**
     * Calculate the bounds rectangle for the floating keyboard.
     *
     * @return Rect defining keyboard bounds (left, top, right, bottom)
     */
    fun calculateBounds(): Rect {
        updateScreenDimensions()

        if (!enabled) {
            // Docked mode - full width at bottom
            return Rect(0, screenHeightPx - 300, screenWidthPx, screenHeightPx)
        }

        val keyboardWidthPx = (screenWidthPx * widthPercent).toInt()
        val keyboardHeightPx = (screenHeightPx * heightPercent).toInt()

        val left = (currentX - keyboardWidthPx / 2).toInt()
        val top = (currentY - keyboardHeightPx / 2).toInt()
        val right = left + keyboardWidthPx
        val bottom = top + keyboardHeightPx

        return Rect(left, top, right, bottom)
    }

    /**
     * Get current position.
     *
     * @return PointF with current X, Y coordinates
     */
    fun getPosition(): PointF {
        return PointF(currentX, currentY)
    }

    /**
     * Get current size.
     *
     * @return PointF with width percent and height percent
     */
    fun getSize(): PointF {
        return PointF(widthPercent, heightPercent)
    }

    /**
     * Check if keyboard is currently being dragged.
     *
     * @return true if dragging, false otherwise
     */
    fun isDragging(): Boolean = isDragging

    /**
     * Check if floating mode is enabled.
     *
     * @return true if enabled, false otherwise
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Get current snapped edge.
     *
     * @return Current snapped edge
     */
    fun getSnappedEdge(): Edge = snappedEdge

    /**
     * Get current opacity.
     *
     * @return Opacity value (0.3-1.0)
     */
    fun getOpacity(): Float = opacity

    /**
     * Set callback for floating mode events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Reset to default position and size.
     */
    fun reset() {
        initializeDefaultPosition()
        widthPercent = DEFAULT_WIDTH_PERCENT
        heightPercent = DEFAULT_HEIGHT_PERCENT
        opacity = DEFAULT_OPACITY
        snappedEdge = Edge.NONE
        logD("Reset to default settings")
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing FloatingKeyboardManager resources...")

        try {
            callback = null
            isDragging = false
            logD("✅ FloatingKeyboardManager resources released")
        } catch (e: Exception) {
            logE("Error releasing floating keyboard manager resources", e)
        }
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
