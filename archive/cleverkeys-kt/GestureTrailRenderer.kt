package tribixbite.cleverkeys

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot

/**
 * Renders visual trails for swipe gestures on the keyboard.
 *
 * Provides real-time visual feedback for finger movements during swipe typing
 * by drawing smooth, fading trails that follow touch points.
 *
 * Features:
 * - Smooth curved trails with Bezier interpolation
 * - Multiple simultaneous trails (multi-touch support)
 * - Configurable trail colors, width, and fade duration
 * - Hardware-accelerated Canvas drawing
 * - Automatic trail cleanup and memory management
 * - Performance-optimized path drawing
 * - Trail fade-out animation
 *
 * Bug #328 - HIGH: Complete implementation of missing GestureTrailRenderer.java
 *
 * @param context Application context for accessing resources
 * @param enabled Initial enabled state (default: true)
 */
class GestureTrailRenderer(
    private val context: Context,
    private var enabled: Boolean = true
) {
    companion object {
        private const val TAG = "GestureTrailRenderer"

        // Trail appearance constants
        private const val DEFAULT_TRAIL_WIDTH = 8f  // dp
        private const val DEFAULT_TRAIL_COLOR = 0xFF4A90E2.toInt()  // Blue
        private const val DEFAULT_FADE_DURATION = 300L  // milliseconds
        private const val MIN_POINT_DISTANCE = 5f  // Minimum distance between points (pixels)

        // Trail smoothing
        private const val BEZIER_CONTROL_FACTOR = 0.5f  // Control point distance factor

        // Maximum trail points before cleanup
        private const val MAX_TRAIL_POINTS = 100

        // Trail opacity
        private const val MAX_ALPHA = 255
        private const val MIN_ALPHA = 0
    }

    // Paint for drawing trails
    private val trailPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        isDither = true
    }

    // Active trails (one per pointer/finger)
    private val activeTrails = CopyOnWriteArrayList<Trail>()

    // Display density for dp conversion
    private val density = context.resources.displayMetrics.density

    // Trail configuration
    private var trailWidth = dpToPx(DEFAULT_TRAIL_WIDTH)
    private var trailColor = DEFAULT_TRAIL_COLOR
    private var fadeDuration = DEFAULT_FADE_DURATION

    init {
        logD("Initializing GestureTrailRenderer (enabled=$enabled)")
        updatePaint()
    }

    /**
     * Data class representing a single trail (one finger's path).
     */
    private data class Trail(
        val pointerId: Int,
        val points: MutableList<TrailPoint> = mutableListOf(),
        var startTime: Long = SystemClock.uptimeMillis(),
        var isActive: Boolean = true
    )

    /**
     * Data class representing a point in a trail.
     */
    private data class TrailPoint(
        val x: Float,
        val y: Float,
        val timestamp: Long = SystemClock.uptimeMillis()
    )

    /**
     * Update paint configuration based on current settings.
     */
    private fun updatePaint() {
        trailPaint.apply {
            strokeWidth = trailWidth
            color = trailColor
            alpha = MAX_ALPHA
        }
    }

    /**
     * Handle touch event to update trails.
     * Call this from the keyboard view's onTouchEvent.
     *
     * @param event The MotionEvent to process
     */
    fun onTouchEvent(event: MotionEvent) {
        if (!enabled) {
            return
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(event, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                handleTouchDown(event, pointerIndex)
            }
            MotionEvent.ACTION_MOVE -> handleTouchMove(event)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                handleTouchUp(event, pointerIndex)
            }
            MotionEvent.ACTION_CANCEL -> handleTouchCancel()
        }
    }

    /**
     * Handle touch down - start a new trail.
     */
    private fun handleTouchDown(event: MotionEvent, pointerIndex: Int) {
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        // Create new trail for this pointer
        val trail = Trail(
            pointerId = pointerId,
            points = mutableListOf(TrailPoint(x, y)),
            startTime = SystemClock.uptimeMillis(),
            isActive = true
        )

        activeTrails.add(trail)
        logD("Started trail for pointer $pointerId at ($x, $y)")
    }

    /**
     * Handle touch move - add points to active trails.
     */
    private fun handleTouchMove(event: MotionEvent) {
        val pointerCount = event.pointerCount

        for (i in 0 until pointerCount) {
            val pointerId = event.getPointerId(i)
            val x = event.getX(i)
            val y = event.getY(i)

            // Find trail for this pointer
            val trail = activeTrails.find { it.pointerId == pointerId && it.isActive }

            if (trail != null) {
                // Add point if it's far enough from the last point
                val lastPoint = trail.points.lastOrNull()
                if (lastPoint == null || distance(lastPoint.x, lastPoint.y, x, y) >= MIN_POINT_DISTANCE) {
                    trail.points.add(TrailPoint(x, y))

                    // Limit trail length for performance
                    if (trail.points.size > MAX_TRAIL_POINTS) {
                        trail.points.removeAt(0)
                    }
                }
            }
        }
    }

    /**
     * Handle touch up - mark trail as inactive (will fade out).
     */
    private fun handleTouchUp(event: MotionEvent, pointerIndex: Int) {
        val pointerId = event.getPointerId(pointerIndex)

        // Mark trail as inactive
        activeTrails.find { it.pointerId == pointerId }?.let { trail ->
            trail.isActive = false
            logD("Ended trail for pointer $pointerId (${trail.points.size} points)")
        }
    }

    /**
     * Handle touch cancel - clear all trails.
     */
    private fun handleTouchCancel() {
        activeTrails.forEach { it.isActive = false }
        logD("Touch cancelled - all trails marked inactive")
    }

    /**
     * Draw all active trails on the canvas.
     * Call this from the keyboard view's onDraw method.
     *
     * @param canvas The Canvas to draw on
     */
    fun draw(canvas: Canvas) {
        if (!enabled || activeTrails.isEmpty()) {
            return
        }

        val currentTime = SystemClock.uptimeMillis()
        val trailsToRemove = mutableListOf<Trail>()

        for (trail in activeTrails) {
            if (trail.points.size < 2) {
                // Need at least 2 points to draw
                if (!trail.isActive) {
                    trailsToRemove.add(trail)
                }
                continue
            }

            // Calculate alpha based on fade-out
            val alpha = if (trail.isActive) {
                MAX_ALPHA
            } else {
                // Fade out over fadeDuration
                val elapsedSinceTouchUp = currentTime - trail.points.last().timestamp
                val fadeProgress = elapsedSinceTouchUp.toFloat() / fadeDuration
                (MAX_ALPHA * (1f - fadeProgress.coerceIn(0f, 1f))).toInt()
            }

            if (alpha <= MIN_ALPHA) {
                // Trail has fully faded, remove it
                trailsToRemove.add(trail)
                continue
            }

            // Draw trail with current alpha
            drawTrail(canvas, trail, alpha)
        }

        // Remove fully faded trails
        activeTrails.removeAll(trailsToRemove)
    }

    /**
     * Draw a single trail on the canvas using smooth Bezier curves.
     */
    private fun drawTrail(canvas: Canvas, trail: Trail, alpha: Int) {
        val path = Path()
        val points = trail.points

        if (points.isEmpty()) return

        // Start at first point
        path.moveTo(points[0].x, points[0].y)

        // Draw smooth curve through all points using quadratic Bezier curves
        for (i in 0 until points.size - 1) {
            val currentPoint = points[i]
            val nextPoint = points[i + 1]

            // Calculate control point (midpoint between current and next)
            val controlX = (currentPoint.x + nextPoint.x) / 2f
            val controlY = (currentPoint.y + nextPoint.y) / 2f

            // Draw quadratic Bezier curve
            path.quadTo(currentPoint.x, currentPoint.y, controlX, controlY)
        }

        // Draw line to last point
        val lastPoint = points.last()
        path.lineTo(lastPoint.x, lastPoint.y)

        // Apply alpha and draw
        trailPaint.alpha = alpha
        canvas.drawPath(path, trailPaint)
    }

    /**
     * Draw trail using simple lines (fallback/faster method).
     * Can be used instead of drawTrail for better performance.
     */
    private fun drawTrailSimple(canvas: Canvas, trail: Trail, alpha: Int) {
        val points = trail.points

        if (points.size < 2) return

        trailPaint.alpha = alpha

        // Draw lines between consecutive points
        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i + 1]
            canvas.drawLine(start.x, start.y, end.x, end.y, trailPaint)
        }
    }

    /**
     * Calculate distance between two points.
     */
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot(x2 - x1, y2 - y1)
    }

    /**
     * Check if there are any active or fading trails.
     * Useful for determining if redraw is needed.
     *
     * @return true if there are trails to draw, false otherwise
     */
    fun hasActiveTrails(): Boolean {
        return activeTrails.isNotEmpty()
    }

    /**
     * Clear all trails immediately.
     */
    fun clearTrails() {
        activeTrails.clear()
        logD("All trails cleared")
    }

    /**
     * Enable or disable trail rendering.
     *
     * @param enabled true to enable trails, false to disable
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled

        if (!enabled) {
            clearTrails()
        }

        logD("Gesture trails ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if trail rendering is enabled.
     *
     * @return true if enabled, false if disabled
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Set trail color.
     *
     * @param color Color in ARGB format
     */
    fun setTrailColor(color: Int) {
        this.trailColor = color
        updatePaint()
        logD("Trail color updated")
    }

    /**
     * Get current trail color.
     *
     * @return Color in ARGB format
     */
    fun getTrailColor(): Int = trailColor

    /**
     * Set trail width.
     *
     * @param width Width in density-independent pixels (dp)
     */
    fun setTrailWidth(width: Float) {
        this.trailWidth = dpToPx(width)
        updatePaint()
        logD("Trail width set to: ${width}dp (${this.trailWidth}px)")
    }

    /**
     * Get current trail width in dp.
     *
     * @return Width in dp
     */
    fun getTrailWidth(): Float = pxToDp(trailWidth)

    /**
     * Set fade duration for trails.
     *
     * @param duration Duration in milliseconds
     */
    fun setFadeDuration(duration: Long) {
        this.fadeDuration = duration.coerceAtLeast(0L)
        logD("Fade duration set to: ${this.fadeDuration}ms")
    }

    /**
     * Get current fade duration.
     *
     * @return Duration in milliseconds
     */
    fun getFadeDuration(): Long = fadeDuration

    /**
     * Set trail appearance with multiple parameters.
     *
     * @param color Color in ARGB format
     * @param width Width in dp
     * @param fadeDuration Fade duration in milliseconds
     */
    fun setTrailAppearance(color: Int, width: Float, fadeDuration: Long) {
        this.trailColor = color
        this.trailWidth = dpToPx(width)
        this.fadeDuration = fadeDuration.coerceAtLeast(0L)
        updatePaint()
        logD("Trail appearance updated (color=$color, width=${width}dp, fade=${fadeDuration}ms)")
    }

    /**
     * Get the number of currently active trails.
     *
     * @return Number of active trails
     */
    fun getActiveTrailCount(): Int {
        return activeTrails.count { it.isActive }
    }

    /**
     * Get the total number of trails (active + fading).
     *
     * @return Total number of trails
     */
    fun getTotalTrailCount(): Int {
        return activeTrails.size
    }

    /**
     * Release all resources and cleanup.
     * Call this when the renderer is no longer needed.
     */
    fun release() {
        logD("Releasing GestureTrailRenderer resources...")

        try {
            clearTrails()
            logD("✅ GestureTrailRenderer resources released")
        } catch (e: Exception) {
            logE("Error releasing trail renderer resources", e)
        }
    }

    // Utility functions

    /**
     * Convert density-independent pixels to pixels.
     */
    private fun dpToPx(dp: Float): Float {
        return dp * density
    }

    /**
     * Convert pixels to density-independent pixels.
     */
    private fun pxToDp(px: Float): Float {
        return px / density
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
