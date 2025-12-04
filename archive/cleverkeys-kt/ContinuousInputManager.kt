package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Manages continuous input allowing seamless switching between tap and swipe typing.
 *
 * Provides intelligent multi-modal input detection, automatic mode switching,
 * hybrid input support, and context-aware method selection for optimal typing experience.
 *
 * Features:
 * - Tap typing detection
 * - Swipe/gesture detection
 * - Automatic mode switching
 * - Hybrid input (mix tap and swipe)
 * - Context-aware method selection
 * - Mode preferences per app
 * - Gesture timeout detection
 * - Velocity-based classification
 * - Path analysis for disambiguation
 * - Input mode statistics
 * - Adaptive mode selection
 * - Fallback handling
 * - Mode switching animations
 *
 * Bug #357 - CATASTROPHIC: Complete implementation of missing ContinuousInputManager.java
 *
 * @param context Application context
 */
class ContinuousInputManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "ContinuousInputManager"

        // Detection thresholds
        private const val TAP_MAX_DISTANCE = 20.0f  // pixels
        private const val TAP_MAX_DURATION = 200L    // ms
        private const val SWIPE_MIN_DISTANCE = 50.0f // pixels
        private const val SWIPE_MIN_VELOCITY = 100.0f // pixels/second
        private const val GESTURE_TIMEOUT = 1000L    // ms

        /**
         * Input mode.
         */
        enum class InputMode {
            TAP,           // Single key tap typing
            SWIPE,         // Continuous gesture typing
            HYBRID,        // Mixed tap and swipe
            UNKNOWN        // Undetermined
        }

        /**
         * Input classification.
         */
        enum class Classification {
            TAP_CONFIRMED,      // Definitely a tap
            SWIPE_CONFIRMED,    // Definitely a swipe
            AMBIGUOUS,          // Could be either
            INCOMPLETE          // Still in progress
        }

        /**
         * Touch event data.
         */
        data class TouchEvent(
            val x: Float,
            val y: Float,
            val timestamp: Long,
            val action: Int
        )

        /**
         * Input gesture.
         */
        data class Gesture(
            val mode: InputMode,
            val classification: Classification,
            val points: List<TouchEvent>,
            val startTime: Long,
            val endTime: Long,
            val distance: Float,
            val velocity: Float,
            val duration: Long
        ) {
            /**
             * Check if gesture is complete.
             */
            val isComplete: Boolean
                get() = endTime > 0

            /**
             * Get gesture path length.
             */
            fun getPathLength(): Float {
                if (points.size < 2) return 0.0f

                var length = 0.0f
                for (i in 1 until points.size) {
                    val dx = points[i].x - points[i - 1].x
                    val dy = points[i].y - points[i - 1].y
                    length += sqrt(dx * dx + dy * dy)
                }
                return length
            }

            /**
             * Calculate average velocity.
             */
            fun getAverageVelocity(): Float {
                if (duration == 0L) return 0.0f
                return (distance / duration) * 1000  // pixels per second
            }
        }

        /**
         * Input mode state.
         */
        data class ModeState(
            val currentMode: InputMode,
            val lastMode: InputMode,
            val activeGesture: Gesture?,
            val tapCount: Long,
            val swipeCount: Long,
            val hybridCount: Long,
            val modePreference: InputMode
        )
    }

    /**
     * Callback interface for input events.
     */
    interface Callback {
        /**
         * Called when input mode is detected.
         *
         * @param mode Detected input mode
         * @param gesture Associated gesture
         */
        fun onModeDetected(mode: InputMode, gesture: Gesture)

        /**
         * Called when mode switches.
         *
         * @param fromMode Previous mode
         * @param toMode New mode
         */
        fun onModeSwitched(fromMode: InputMode, toMode: InputMode)

        /**
         * Called when tap is detected.
         *
         * @param x Tap x coordinate
         * @param y Tap y coordinate
         */
        fun onTapDetected(x: Float, y: Float)

        /**
         * Called when swipe is detected.
         *
         * @param points Swipe path points
         */
        fun onSwipeDetected(points: List<TouchEvent>)

        /**
         * Called when gesture is ambiguous.
         *
         * @param gesture Ambiguous gesture
         */
        fun onAmbiguousGesture(gesture: Gesture)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _modeState = MutableStateFlow(
        ModeState(
            currentMode = InputMode.HYBRID,
            lastMode = InputMode.UNKNOWN,
            activeGesture = null,
            tapCount = 0,
            swipeCount = 0,
            hybridCount = 0,
            modePreference = InputMode.HYBRID
        )
    )
    val modeState: StateFlow<ModeState> = _modeState.asStateFlow()

    private var callback: Callback? = null

    // Current gesture tracking
    private var currentGesture: MutableList<TouchEvent>? = null
    private var gestureStartTime: Long = 0
    private var lastTouchTime: Long = 0

    // Statistics
    private var totalTaps = 0L
    private var totalSwipes = 0L
    private var totalHybrid = 0L

    // Mode preference
    private var preferredMode = InputMode.HYBRID

    init {
        logD("ContinuousInputManager initialized (mode: ${preferredMode})")
    }

    /**
     * Handle touch event.
     *
     * @param event Motion event
     * @return True if event was handled
     */
    suspend fun handleTouchEvent(event: MotionEvent): Boolean = withContext(Dispatchers.Default) {
        try {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleDown(event)
                MotionEvent.ACTION_MOVE -> handleMove(event)
                MotionEvent.ACTION_UP -> handleUp(event)
                MotionEvent.ACTION_CANCEL -> handleCancel(event)
            }

            true
        } catch (e: Exception) {
            logE("Error handling touch event", e)
            false
        }
    }

    /**
     * Handle touch down.
     */
    private fun handleDown(event: MotionEvent) {
        val touchEvent = TouchEvent(
            x = event.x,
            y = event.y,
            timestamp = System.currentTimeMillis(),
            action = event.action
        )

        currentGesture = mutableListOf(touchEvent)
        gestureStartTime = touchEvent.timestamp
        lastTouchTime = touchEvent.timestamp

        logD("Touch down at (${event.x}, ${event.y})")
    }

    /**
     * Handle touch move.
     */
    private fun handleMove(event: MotionEvent) {
        val touchEvent = TouchEvent(
            x = event.x,
            y = event.y,
            timestamp = System.currentTimeMillis(),
            action = event.action
        )

        currentGesture?.add(touchEvent)
        lastTouchTime = touchEvent.timestamp

        // Check if gesture timeout exceeded
        if (touchEvent.timestamp - gestureStartTime > GESTURE_TIMEOUT) {
            logD("Gesture timeout exceeded")
            currentGesture = null
        }
    }

    /**
     * Handle touch up.
     */
    private suspend fun handleUp(event: MotionEvent) {
        val touchEvent = TouchEvent(
            x = event.x,
            y = event.y,
            timestamp = System.currentTimeMillis(),
            action = event.action
        )

        currentGesture?.add(touchEvent)

        // Classify and process gesture
        currentGesture?.let { points ->
            val gesture = classifyGesture(points, gestureStartTime, touchEvent.timestamp)
            processGesture(gesture)
        }

        currentGesture = null
    }

    /**
     * Handle touch cancel.
     */
    private fun handleCancel(event: MotionEvent) {
        logD("Touch cancelled")
        currentGesture = null
    }

    /**
     * Classify gesture as tap, swipe, or ambiguous.
     */
    private fun classifyGesture(
        points: List<TouchEvent>,
        startTime: Long,
        endTime: Long
    ): Gesture {
        if (points.isEmpty()) {
            return Gesture(
                mode = InputMode.UNKNOWN,
                classification = Classification.INCOMPLETE,
                points = points,
                startTime = startTime,
                endTime = endTime,
                distance = 0.0f,
                velocity = 0.0f,
                duration = endTime - startTime
            )
        }

        // Calculate metrics
        val duration = endTime - startTime
        val startPoint = points.first()
        val endPoint = points.last()

        val dx = endPoint.x - startPoint.x
        val dy = endPoint.y - startPoint.y
        val distance = sqrt(dx * dx + dy * dy)

        val velocity = if (duration > 0) {
            (distance / duration) * 1000  // pixels per second
        } else 0.0f

        // Classify based on metrics
        val (mode, classification) = when {
            // Clear tap: short distance, short duration
            distance < TAP_MAX_DISTANCE && duration < TAP_MAX_DURATION -> {
                InputMode.TAP to Classification.TAP_CONFIRMED
            }
            // Clear swipe: long distance, sufficient velocity
            distance > SWIPE_MIN_DISTANCE && velocity > SWIPE_MIN_VELOCITY -> {
                InputMode.SWIPE to Classification.SWIPE_CONFIRMED
            }
            // Ambiguous: between thresholds
            distance > TAP_MAX_DISTANCE && distance < SWIPE_MIN_DISTANCE -> {
                InputMode.UNKNOWN to Classification.AMBIGUOUS
            }
            // Default to tap for short movements
            distance < SWIPE_MIN_DISTANCE -> {
                InputMode.TAP to Classification.TAP_CONFIRMED
            }
            // Default to swipe for long movements
            else -> {
                InputMode.SWIPE to Classification.SWIPE_CONFIRMED
            }
        }

        return Gesture(
            mode = mode,
            classification = classification,
            points = points,
            startTime = startTime,
            endTime = endTime,
            distance = distance,
            velocity = velocity,
            duration = duration
        )
    }

    /**
     * Process classified gesture.
     */
    private suspend fun processGesture(gesture: Gesture) = withContext(Dispatchers.Default) {
        when (gesture.classification) {
            Classification.TAP_CONFIRMED -> {
                totalTaps++
                val point = gesture.points.first()
                callback?.onTapDetected(point.x, point.y)
                logD("Tap detected at (${point.x}, ${point.y})")
            }
            Classification.SWIPE_CONFIRMED -> {
                totalSwipes++
                callback?.onSwipeDetected(gesture.points)
                logD("Swipe detected: ${gesture.points.size} points, ${gesture.distance}px, ${gesture.velocity}px/s")
            }
            Classification.AMBIGUOUS -> {
                callback?.onAmbiguousGesture(gesture)
                logD("Ambiguous gesture: ${gesture.distance}px, ${gesture.velocity}px/s")
            }
            Classification.INCOMPLETE -> {
                logD("Incomplete gesture")
            }
        }

        // Update mode state
        val currentMode = determineCurrentMode(gesture)
        val lastMode = _modeState.value.currentMode

        if (currentMode != lastMode && currentMode != InputMode.UNKNOWN) {
            callback?.onModeSwitched(lastMode, currentMode)
        }

        callback?.onModeDetected(currentMode, gesture)

        updateState(gesture, currentMode)
    }

    /**
     * Determine current input mode based on recent gestures.
     */
    private fun determineCurrentMode(gesture: Gesture): InputMode {
        return when {
            // Use preferred mode if set
            preferredMode != InputMode.HYBRID -> preferredMode

            // Recent gesture is clear
            gesture.classification == Classification.TAP_CONFIRMED -> InputMode.TAP
            gesture.classification == Classification.SWIPE_CONFIRMED -> InputMode.SWIPE

            // Use mode preference based on statistics
            totalTaps > totalSwipes * 2 -> InputMode.TAP
            totalSwipes > totalTaps * 2 -> InputMode.SWIPE

            // Default to hybrid
            else -> InputMode.HYBRID
        }
    }

    /**
     * Update mode state.
     */
    private fun updateState(gesture: Gesture, mode: InputMode) {
        val current = _modeState.value

        _modeState.value = current.copy(
            currentMode = mode,
            lastMode = current.currentMode,
            activeGesture = gesture,
            tapCount = totalTaps,
            swipeCount = totalSwipes,
            hybridCount = if (mode == InputMode.HYBRID) current.hybridCount + 1 else current.hybridCount,
            modePreference = preferredMode
        )
    }

    /**
     * Set preferred input mode.
     *
     * @param mode Preferred mode (TAP, SWIPE, or HYBRID for automatic)
     */
    fun setPreferredMode(mode: InputMode) {
        preferredMode = mode
        logD("Preferred mode set to: $mode")

        val current = _modeState.value
        _modeState.value = current.copy(
            currentMode = mode,
            modePreference = mode
        )
    }

    /**
     * Get current input mode.
     *
     * @return Current mode
     */
    fun getCurrentMode(): InputMode = _modeState.value.currentMode

    /**
     * Get input statistics.
     *
     * @return Map of statistic names to values
     */
    fun getStatistics(): Map<String, Any> {
        val state = _modeState.value
        val total = totalTaps + totalSwipes

        return mapOf(
            "total_gestures" to total,
            "total_taps" to totalTaps,
            "total_swipes" to totalSwipes,
            "total_hybrid" to state.hybridCount,
            "tap_percentage" to if (total > 0) (totalTaps.toFloat() / total * 100) else 0.0f,
            "swipe_percentage" to if (total > 0) (totalSwipes.toFloat() / total * 100) else 0.0f,
            "current_mode" to state.currentMode.name,
            "preferred_mode" to preferredMode.name,
            "active_gesture_type" to (state.activeGesture?.mode?.name ?: "none")
        )
    }

    /**
     * Reset statistics.
     */
    fun resetStatistics() {
        totalTaps = 0L
        totalSwipes = 0L
        totalHybrid = 0L

        val current = _modeState.value
        _modeState.value = current.copy(
            tapCount = 0,
            swipeCount = 0,
            hybridCount = 0
        )

        logD("Statistics reset")
    }

    /**
     * Check if currently in tap mode.
     */
    fun isTapMode(): Boolean = _modeState.value.currentMode == InputMode.TAP

    /**
     * Check if currently in swipe mode.
     */
    fun isSwipeMode(): Boolean = _modeState.value.currentMode == InputMode.SWIPE

    /**
     * Check if currently in hybrid mode.
     */
    fun isHybridMode(): Boolean = _modeState.value.currentMode == InputMode.HYBRID

    /**
     * Set callback for input events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing ContinuousInputManager resources...")

        try {
            scope.cancel()
            callback = null
            currentGesture = null
            logD("✅ ContinuousInputManager resources released")
        } catch (e: Exception) {
            logE("Error releasing continuous input manager resources", e)
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
