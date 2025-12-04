package tribixbite.cleverkeys

import android.view.MotionEvent
import kotlin.math.sqrt

/**
 * Handles multi-touch gestures and simultaneous key presses.
 *
 * Features:
 * - Two-finger swipe gestures (left/right/up/down)
 * - Pinch-to-zoom detection
 * - Three-finger gestures
 * - Simultaneous key press detection
 * - Gesture recognition with velocity tracking
 * - Touch point tracking and management
 * - Gesture cancellation on invalid patterns
 *
 * Addresses Bug #323: MultiTouchHandler missing (HIGH)
 */
class MultiTouchHandler(
    private val callback: Callback
) {

    companion object {
        private const val TAG = "MultiTouchHandler"

        // Gesture detection thresholds
        private const val SWIPE_THRESHOLD = 100f  // Minimum distance for swipe (pixels)
        private const val SWIPE_VELOCITY_THRESHOLD = 200f  // Minimum velocity (pixels/second)
        private const val PINCH_THRESHOLD = 50f  // Minimum distance change for pinch
        private const val MAX_TOUCH_DURATION = 1000L  // Maximum duration for gesture (ms)
        private const val TOUCH_TIMEOUT = 300L  // Time window for simultaneous touches (ms)
    }

    /**
     * Callback interface for multi-touch events
     */
    interface Callback {
        /**
         * Called when two-finger swipe is detected
         * @param direction Swipe direction (LEFT, RIGHT, UP, DOWN)
         * @param velocity Swipe velocity in pixels/second
         */
        fun onTwoFingerSwipe(direction: SwipeDirection, velocity: Float)

        /**
         * Called when three-finger swipe is detected
         * @param direction Swipe direction
         */
        fun onThreeFingerSwipe(direction: SwipeDirection)

        /**
         * Called when pinch gesture is detected
         * @param scale Scale factor (< 1.0 = pinch in, > 1.0 = pinch out)
         */
        fun onPinchGesture(scale: Float)

        /**
         * Called when multiple keys are pressed simultaneously
         * @param touchCount Number of simultaneous touches
         */
        fun onSimultaneousKeyPress(touchCount: Int)

        /**
         * Request vibration feedback
         */
        fun performVibration()
    }

    /**
     * Swipe direction
     */
    enum class SwipeDirection {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    /**
     * Touch point data
     */
    private data class TouchPoint(
        val pointerId: Int,
        var x: Float,
        var y: Float,
        val startX: Float,
        val startY: Float,
        val startTime: Long
    )

    // Active touch points
    private val activeTouches = mutableMapOf<Int, TouchPoint>()
    private var gestureStartTime = 0L
    private var gestureInProgress = false
    private var gestureType: GestureType = GestureType.NONE

    // Pinch gesture state
    private var initialPinchDistance = 0f
    private var currentPinchDistance = 0f

    private enum class GestureType {
        NONE,
        TWO_FINGER_SWIPE,
        THREE_FINGER_SWIPE,
        PINCH,
        SIMULTANEOUS_PRESS
    }

    /**
     * Process touch event
     * @return true if event was handled by gesture recognition
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(event, 0)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
            MotionEvent.ACTION_MOVE -> handleTouchMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> handleTouchUp(event)
            MotionEvent.ACTION_CANCEL -> handleTouchCancel()
        }

        return gestureInProgress
    }

    /**
     * Handle initial touch down
     */
    private fun handleTouchDown(event: MotionEvent, pointerIndex: Int) {
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val currentTime = System.currentTimeMillis()

        // Check if this is part of a simultaneous press
        if (activeTouches.isEmpty()) {
            gestureStartTime = currentTime
        } else {
            // Multiple fingers down within timeout = simultaneous press
            val timeSinceStart = currentTime - gestureStartTime
            if (timeSinceStart < TOUCH_TIMEOUT) {
                gestureType = GestureType.SIMULTANEOUS_PRESS
            }
        }

        activeTouches[pointerId] = TouchPoint(
            pointerId = pointerId,
            x = x,
            y = y,
            startX = x,
            startY = y,
            startTime = currentTime
        )

        // Check for multi-finger gestures
        checkGestureStart()
    }

    /**
     * Handle additional pointer down
     */
    private fun handlePointerDown(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        handleTouchDown(event, pointerIndex)
    }

    /**
     * Handle touch movement
     */
    private fun handleTouchMove(event: MotionEvent) {
        if (!gestureInProgress) return

        // Update all active touch points
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            val touch = activeTouches[pointerId] ?: continue

            touch.x = event.getX(i)
            touch.y = event.getY(i)
        }

        // Process gesture based on type
        when (gestureType) {
            GestureType.TWO_FINGER_SWIPE -> processTwoFingerSwipe()
            GestureType.THREE_FINGER_SWIPE -> processThreeFingerSwipe()
            GestureType.PINCH -> processPinchGesture()
            else -> {}
        }
    }

    /**
     * Handle touch up
     */
    private fun handleTouchUp(event: MotionEvent) {
        val pointerIndex = if (event.actionMasked == MotionEvent.ACTION_UP) {
            0
        } else {
            event.actionIndex
        }
        val pointerId = event.getPointerId(pointerIndex)

        // Finalize gesture if all fingers lifted
        if (gestureInProgress && activeTouches.size <= 1) {
            finalizeGesture()
        }

        activeTouches.remove(pointerId)

        // Reset if no more touches
        if (activeTouches.isEmpty()) {
            reset()
        }
    }

    /**
     * Handle touch cancel
     */
    private fun handleTouchCancel() {
        reset()
    }

    /**
     * Check if multi-finger gesture should start
     */
    private fun checkGestureStart() {
        val touchCount = activeTouches.size

        when (touchCount) {
            2 -> {
                // Two fingers - could be swipe or pinch
                gestureInProgress = true
                gestureType = GestureType.TWO_FINGER_SWIPE
                initialPinchDistance = calculateDistance(
                    activeTouches.values.elementAt(0),
                    activeTouches.values.elementAt(1)
                )
            }
            3 -> {
                // Three fingers - swipe gesture
                gestureInProgress = true
                gestureType = GestureType.THREE_FINGER_SWIPE
            }
            in 2..10 -> {
                // Multiple simultaneous presses
                if (gestureType == GestureType.SIMULTANEOUS_PRESS) {
                    callback.onSimultaneousKeyPress(touchCount)
                    callback.performVibration()
                }
            }
        }
    }

    /**
     * Process two-finger swipe
     */
    private fun processTwoFingerSwipe() {
        if (activeTouches.size != 2) return

        val touches = activeTouches.values.toList()
        val touch1 = touches[0]
        val touch2 = touches[1]

        // Check if fingers moved together (not diverging = pinch)
        val currentDistance = calculateDistance(touch1, touch2)
        val distanceChange = kotlin.math.abs(currentDistance - initialPinchDistance)

        if (distanceChange > PINCH_THRESHOLD) {
            // Distance changed significantly - switch to pinch
            gestureType = GestureType.PINCH
            return
        }

        // Calculate average movement
        val avgDeltaX = ((touch1.x - touch1.startX) + (touch2.x - touch2.startX)) / 2
        val avgDeltaY = ((touch1.y - touch1.startY) + (touch2.y - touch2.startY)) / 2
        val distance = sqrt(avgDeltaX * avgDeltaX + avgDeltaY * avgDeltaY)

        // Check if swipe threshold met
        if (distance < SWIPE_THRESHOLD) return

        // Determine direction
        val direction = if (kotlin.math.abs(avgDeltaX) > kotlin.math.abs(avgDeltaY)) {
            if (avgDeltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
        } else {
            if (avgDeltaY > 0) SwipeDirection.DOWN else SwipeDirection.UP
        }

        // Calculate velocity
        val duration = System.currentTimeMillis() - gestureStartTime
        val velocity = (distance / duration) * 1000  // pixels/second

        if (velocity >= SWIPE_VELOCITY_THRESHOLD) {
            callback.onTwoFingerSwipe(direction, velocity)
            callback.performVibration()
            reset()
        }
    }

    /**
     * Process three-finger swipe
     */
    private fun processThreeFingerSwipe() {
        if (activeTouches.size != 3) return

        val touches = activeTouches.values.toList()

        // Calculate average movement
        var avgDeltaX = 0f
        var avgDeltaY = 0f
        for (touch in touches) {
            avgDeltaX += (touch.x - touch.startX)
            avgDeltaY += (touch.y - touch.startY)
        }
        avgDeltaX /= 3
        avgDeltaY /= 3

        val distance = sqrt(avgDeltaX * avgDeltaX + avgDeltaY * avgDeltaY)

        // Check if swipe threshold met
        if (distance < SWIPE_THRESHOLD) return

        // Determine direction
        val direction = if (kotlin.math.abs(avgDeltaX) > kotlin.math.abs(avgDeltaY)) {
            if (avgDeltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
        } else {
            if (avgDeltaY > 0) SwipeDirection.DOWN else SwipeDirection.UP
        }

        callback.onThreeFingerSwipe(direction)
        callback.performVibration()
        reset()
    }

    /**
     * Process pinch gesture
     */
    private fun processPinchGesture() {
        if (activeTouches.size != 2) return

        val touches = activeTouches.values.toList()
        currentPinchDistance = calculateDistance(touches[0], touches[1])

        val scale = currentPinchDistance / initialPinchDistance

        // Only trigger if scale change is significant
        if (kotlin.math.abs(scale - 1.0f) > 0.2f) {
            callback.onPinchGesture(scale)
            callback.performVibration()
            // Update initial distance for continuous pinch
            initialPinchDistance = currentPinchDistance
        }
    }

    /**
     * Finalize gesture when fingers lift
     */
    private fun finalizeGesture() {
        // Any final processing before resetting
        gestureInProgress = false
    }

    /**
     * Calculate distance between two touch points
     */
    private fun calculateDistance(touch1: TouchPoint, touch2: TouchPoint): Float {
        val dx = touch2.x - touch1.x
        val dy = touch2.y - touch1.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Reset gesture state
     */
    private fun reset() {
        activeTouches.clear()
        gestureInProgress = false
        gestureType = GestureType.NONE
        gestureStartTime = 0L
        initialPinchDistance = 0f
        currentPinchDistance = 0f
    }

    /**
     * Get current touch count
     */
    fun getTouchCount(): Int = activeTouches.size

    /**
     * Check if gesture is in progress
     */
    fun isGestureInProgress(): Boolean = gestureInProgress

    /**
     * Get current gesture type
     */
    fun getCurrentGestureType(): String = gestureType.name

    /**
     * Get statistics
     */
    fun getStats(): String {
        return """
            Multi-Touch Statistics:
            - Active touches: ${activeTouches.size}
            - Gesture in progress: $gestureInProgress
            - Gesture type: ${gestureType.name}
        """.trimIndent()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        reset()
    }
}
