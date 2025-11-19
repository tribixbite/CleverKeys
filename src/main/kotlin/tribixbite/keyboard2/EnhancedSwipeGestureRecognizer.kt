package tribixbite.keyboard2

import android.graphics.PointF

/**
 * Enhanced swipe gesture recognizer for neural prediction
 * Tracks touch trajectory and provides swipe input for ONNX processing
 */
class EnhancedSwipeGestureRecognizer {

    private val trajectory = mutableListOf<PointF>()
    private val timestamps = mutableListOf<Long>()
    private var startTime: Long = 0
    private var isTracking = false
    private var minSwipeTypingDistance = 50f  // Configurable threshold

    /**
     * Set minimum distance required before swipe typing mode activates
     */
    fun setMinSwipeTypingDistance(distance: Float) {
        minSwipeTypingDistance = distance
    }

    /**
     * Start tracking a new swipe gesture
     */
    fun startTracking(x: Float, y: Float) {
        trajectory.clear()
        timestamps.clear()
        startTime = System.currentTimeMillis()
        isTracking = true
        addPoint(x, y)
    }

    /**
     * Add a point to the current trajectory
     */
    fun addPoint(x: Float, y: Float) {
        if (isTracking) {
            trajectory.add(PointF(x, y))
            timestamps.add(System.currentTimeMillis() - startTime)
        }
    }

    /**
     * Stop tracking and finalize the gesture
     */
    fun stopTracking() {
        isTracking = false
    }

    /**
     * Get the complete swipe input for neural processing
     */
    fun getSwipeInput(): SwipeInput? {
        if (trajectory.size < 2) return null

        return SwipeInput(
            coordinates = trajectory.toList(),
            timestamps = timestamps.toList(),
            touchedKeys = emptyList() // Will be populated by keyboard logic
        )
    }

    /**
     * Check if currently tracking a gesture
     */
    fun isTracking(): Boolean = isTracking

    /**
     * Get current trajectory length
     */
    fun getTrajectoryLength(): Int = trajectory.size

    /**
     * Clear all tracking data
     */
    fun clear() {
        trajectory.clear()
        timestamps.clear()
        isTracking = false
        startTime = 0
    }

    /**
     * Start swipe - alias for startTracking
     */
    fun startSwipe(x: Float, y: Float, key: KeyboardData.Key?) {
        startTracking(x, y)
    }

    /**
     * Check if currently swipe typing
     * Requires minimum distance to distinguish from short direction gestures
     */
    fun isSwipeTyping(): Boolean {
        if (!isTracking || trajectory.size < 3) return false

        // Calculate total distance traveled
        var totalDistance = 0f
        for (i in 1 until trajectory.size) {
            val dx = trajectory[i].x - trajectory[i - 1].x
            val dy = trajectory[i].y - trajectory[i - 1].y
            totalDistance += kotlin.math.sqrt(dx * dx + dy * dy)
        }

        // Require minimum distance to consider it swipe typing (not just a direction gesture)
        // This threshold allows short direction gestures to work
        return totalDistance > minSwipeTypingDistance
    }

    /**
     * Reset - alias for clear
     */
    fun reset() {
        clear()
    }
}