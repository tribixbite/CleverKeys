package tribixbite.keyboard2

/**
 * Gesture recognition system for directional touch input.
 *
 * Recognizes 4 gesture types:
 * - **Swipe**: Simple directional swipe
 * - **Roundtrip**: Swipe out and return to center
 * - **Circle**: Clockwise rotation
 * - **Anticircle**: Anticlockwise rotation
 *
 * Uses 16-direction quantization (0-15) for precise tracking.
 *
 * Usage:
 * ```kotlin
 * val gesture = Gesture(startingDirection = 0)
 * gesture.changedDirection(4)  // Returns true if state changed
 * val name = gesture.getGesture()  // Get recognized gesture
 * ```
 */
class Gesture(startingDirection: Int) {

    /** Current pointer direction (0-15) */
    var currentDir: Int = startingDirection
        private set

    /** Current gesture state */
    var state: State = State.Swiped
        private set

    /**
     * Gesture state machine states
     */
    enum class State {
        /** Gesture cancelled (rotation reversed) */
        Cancelled,

        /** Simple swipe in progress */
        Swiped,

        /** Clockwise rotation detected */
        RotatingClockwise,

        /** Anticlockwise rotation detected */
        RotatingAnticlockwise,

        /** Swipe gesture ended (pointer up) */
        EndedSwipe,

        /** Swipe ended by returning to center (roundtrip) */
        EndedCenter,

        /** Clockwise rotation ended */
        EndedClockwise,

        /** Anticlockwise rotation ended */
        EndedAnticlockwise
    }

    /**
     * Recognized gesture names
     */
    enum class Name {
        /** No gesture recognized */
        None,

        /** Simple directional swipe */
        Swipe,

        /** Swipe out and return to center */
        Roundtrip,

        /** Clockwise rotation */
        Circle,

        /** Anticlockwise rotation */
        Anticircle
    }

    companion object {
        /** Angle to travel before rotation starts (in direction units) */
        const val ROTATION_THRESHOLD = 2

        /** Number of direction quantization levels */
        private const val NUM_DIRECTIONS = 16

        /**
         * Calculate shortest angular difference between two directions.
         * Uses modulo arithmetic to find shortest circular path.
         *
         * @param d1 First direction (0-15)
         * @param d2 Second direction (0-15)
         * @return Positive for clockwise, negative for anticlockwise, 0 if equal
         */
        fun dirDiff(d1: Int, d2: Int): Int {
            if (d1 == d2) return 0

            val left = (d1 - d2 + NUM_DIRECTIONS) % NUM_DIRECTIONS
            val right = (d2 - d1 + NUM_DIRECTIONS) % NUM_DIRECTIONS

            return if (left < right) -left else right
        }
    }

    /**
     * Get currently recognized gesture.
     * May change when [changedDirection] returns true.
     *
     * @return Recognized gesture name
     */
    fun getGesture(): Name {
        return when (state) {
            State.Cancelled -> Name.None
            State.Swiped, State.EndedSwipe -> Name.Swipe
            State.EndedCenter -> Name.Roundtrip
            State.RotatingClockwise, State.EndedClockwise -> Name.Circle
            State.RotatingAnticlockwise, State.EndedAnticlockwise -> Name.Anticircle
        }
    }

    /**
     * Check if gesture is still in progress.
     *
     * @return true if pointer is still down and gesture active
     */
    fun isInProgress(): Boolean {
        return when (state) {
            State.Swiped,
            State.RotatingClockwise,
            State.RotatingAnticlockwise -> true
            else -> false
        }
    }

    /**
     * Get current direction (0-15).
     *
     * @return Current direction quadrant
     */
    fun currentDirection(): Int = currentDir

    /**
     * Pointer changed direction.
     *
     * State transitions:
     * - **Swiped** → **Rotating** if direction change exceeds circle_sensitivity
     * - **Rotating** → **Cancelled** if rotation reverses direction
     *
     * @param direction New direction (0-15)
     * @return true if gesture state changed and [getGesture] returns different value
     */
    fun changedDirection(direction: Int): Boolean {
        val d = dirDiff(currentDir, direction)
        val clockwise = d > 0

        return when (state) {
            State.Swiped -> {
                if (kotlin.math.abs(d) < Config.globalConfig().circle_sensitivity) {
                    false
                } else {
                    // Start rotation
                    state = if (clockwise) {
                        State.RotatingClockwise
                    } else {
                        State.RotatingAnticlockwise
                    }
                    currentDir = direction
                    true
                }
            }

            State.RotatingClockwise, State.RotatingAnticlockwise -> {
                currentDir = direction
                // Check if rotation reversed direction
                if ((state == State.RotatingClockwise) == clockwise) {
                    false  // Continue same rotation
                } else {
                    state = State.Cancelled  // Rotation reversed - cancel
                    true
                }
            }

            else -> false
        }
    }

    /**
     * Pointer moved back to center.
     *
     * State transitions:
     * - **Swiped** → **EndedCenter** (becomes Roundtrip gesture)
     * - **RotatingClockwise** → **EndedClockwise**
     * - **RotatingAnticlockwise** → **EndedAnticlockwise**
     *
     * @return true if [getGesture] will return different value
     */
    fun movedToCenter(): Boolean {
        return when (state) {
            State.Swiped -> {
                state = State.EndedCenter
                true  // Swipe becomes Roundtrip
            }
            State.RotatingClockwise -> {
                state = State.EndedClockwise
                false  // Still Circle
            }
            State.RotatingAnticlockwise -> {
                state = State.EndedAnticlockwise
                false  // Still Anticircle
            }
            else -> false
        }
    }

    /**
     * Pointer lifted up.
     * Transitions to ended state without changing gesture name.
     *
     * State transitions:
     * - **Swiped** → **EndedSwipe**
     * - **RotatingClockwise** → **EndedClockwise**
     * - **RotatingAnticlockwise** → **EndedAnticlockwise**
     */
    fun pointerUp() {
        state = when (state) {
            State.Swiped -> State.EndedSwipe
            State.RotatingClockwise -> State.EndedClockwise
            State.RotatingAnticlockwise -> State.EndedAnticlockwise
            else -> state
        }
    }
}
