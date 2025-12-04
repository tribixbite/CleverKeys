package tribixbite.cleverkeys

import kotlin.math.abs

class Gesture(startingDirection: Int) {
    /** The pointer direction that caused the last state change.
     * Integer from 0 to 15 (included). */
    var currentDir: Int = startingDirection
        private set

    var state: State = State.Swiped
        private set

    enum class State {
        Cancelled,
        Swiped,
        Rotating_clockwise,
        Rotating_anticlockwise,
        Ended_swipe,
        Ended_center,
        Ended_clockwise,
        Ended_anticlockwise
    }

    enum class Name {
        None,
        Swipe,
        Roundtrip,
        Circle,
        Anticircle
    }

    /**
     * Return the currently recognized gesture. Return [null] if no gesture is
     * recognized. Might change everytime [changed_direction] return [true].
     */
    fun get_gesture(): Name {
        return when (state) {
            State.Cancelled -> Name.None
            State.Swiped, State.Ended_swipe -> Name.Swipe
            State.Ended_center -> Name.Roundtrip
            State.Rotating_clockwise, State.Ended_clockwise -> Name.Circle
            State.Rotating_anticlockwise, State.Ended_anticlockwise -> Name.Anticircle
        }
    }

    fun is_in_progress(): Boolean {
        return when (state) {
            State.Swiped, State.Rotating_clockwise, State.Rotating_anticlockwise -> true
            else -> false
        }
    }

    fun current_direction(): Int = currentDir

    /**
     * The pointer changed direction. Return [true] if the gesture changed
     * state and [get_gesture] return a different value.
     */
    fun changed_direction(direction: Int): Boolean {
        val d = dirDiff(currentDir, direction)
        val clockwise = d > 0
        return when (state) {
            State.Swiped -> {
                if (abs(d) < Config.globalConfig().circle_sensitivity) {
                    return false
                }
                // Start a rotation
                state = if (clockwise) {
                    State.Rotating_clockwise
                } else {
                    State.Rotating_anticlockwise
                }
                currentDir = direction
                true
            }
            // Check that rotation is not reversing
            State.Rotating_clockwise, State.Rotating_anticlockwise -> {
                currentDir = direction
                if ((state == State.Rotating_clockwise) == clockwise) {
                    return false
                }
                state = State.Cancelled
                true
            }
            else -> false
        }
    }

    /**
     * Return [true] if [get_gesture] will return a different value.
     */
    fun moved_to_center(): Boolean {
        return when (state) {
            State.Swiped -> {
                state = State.Ended_center
                true
            }
            State.Rotating_clockwise -> {
                state = State.Ended_clockwise
                false
            }
            State.Rotating_anticlockwise -> {
                state = State.Ended_anticlockwise
                false
            }
            else -> false
        }
    }

    /**
     * Will not change the gesture state.
     */
    fun pointer_up() {
        state = when (state) {
            State.Swiped -> State.Ended_swipe
            State.Rotating_clockwise -> State.Ended_clockwise
            State.Rotating_anticlockwise -> State.Ended_anticlockwise
            else -> state
        }
    }

    companion object {
        /** Angle to travel before a rotation gesture starts. A threshold too low
         * would be too easy to reach while doing back and forth gestures, as the
         * quadrants are very small. In the same unit as [currentDir] */
        const val ROTATION_THRESHOLD = 2

        @JvmStatic
        fun dirDiff(d1: Int, d2: Int): Int {
            val n = 16
            // Shortest-path in modulo arithmetic
            if (d1 == d2) {
                return 0
            }
            val left = (d1 - d2 + n) % n
            val right = (d2 - d1 + n) % n
            return if (left < right) -left else right
        }
    }
}
