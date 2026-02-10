package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM unit tests for Gesture.kt state machine and direction math.
 *
 * Covers:
 * - dirDiff(): shortest-path modular direction difference (0..15 ring)
 * - State initialization and get_gesture() mapping
 * - is_in_progress() for each state
 * - moved_to_center() state transitions
 * - pointer_up() state transitions
 * - ROTATION_THRESHOLD constant
 *
 * NOTE: changed_direction() is NOT tested here because it calls
 * Config.globalConfig().circle_sensitivity, which requires Android
 * SharedPreferences initialization. That path needs Robolectric or
 * an instrumented test.
 */
class GestureTest {

    // =========================================================================
    // A. dirDiff() — modular shortest-path on 0..15 ring
    // =========================================================================

    @Test
    fun `dirDiff of same direction is zero`() {
        for (d in 0..15) {
            assertThat(Gesture.dirDiff(d, d)).isEqualTo(0)
        }
    }

    @Test
    fun `dirDiff of adjacent clockwise directions is positive`() {
        // Moving from 0 to 1 is a small clockwise step (+1)
        assertThat(Gesture.dirDiff(0, 1)).isEqualTo(1)
        assertThat(Gesture.dirDiff(3, 4)).isEqualTo(1)
        assertThat(Gesture.dirDiff(14, 15)).isEqualTo(1)
    }

    @Test
    fun `dirDiff of adjacent anticlockwise directions is negative`() {
        // Moving from 1 to 0 is a small anticlockwise step (-1)
        assertThat(Gesture.dirDiff(1, 0)).isEqualTo(-1)
        assertThat(Gesture.dirDiff(4, 3)).isEqualTo(-1)
        assertThat(Gesture.dirDiff(15, 14)).isEqualTo(-1)
    }

    @Test
    fun `dirDiff wraps around ring boundary clockwise`() {
        // From 15 to 0 should be +1 (clockwise wrap)
        assertThat(Gesture.dirDiff(15, 0)).isEqualTo(1)
        // From 14 to 0 should be +2
        assertThat(Gesture.dirDiff(14, 0)).isEqualTo(2)
    }

    @Test
    fun `dirDiff wraps around ring boundary anticlockwise`() {
        // From 0 to 15 should be -1 (anticlockwise wrap)
        assertThat(Gesture.dirDiff(0, 15)).isEqualTo(-1)
        // From 0 to 14 should be -2
        assertThat(Gesture.dirDiff(0, 14)).isEqualTo(-2)
    }

    @Test
    fun `dirDiff picks shortest path for half-ring distance`() {
        // At exactly 8 apart (half ring), the algorithm prefers the positive (right) path
        // left = (0 - 8 + 16) % 16 = 8
        // right = (8 - 0 + 16) % 16 = 8
        // left < right is false (equal), so returns right = 8
        assertThat(Gesture.dirDiff(0, 8)).isEqualTo(8)
    }

    @Test
    fun `dirDiff at distance 7 returns shortest path`() {
        // From 0 to 7: left=(0-7+16)%16=9, right=(7-0+16)%16=7
        // left(9) < right(7) is false, so returns right=7
        assertThat(Gesture.dirDiff(0, 7)).isEqualTo(7)
        // From 0 to 9: left=(0-9+16)%16=7, right=(9-0+16)%16=9
        // left(7) < right(9) is true, so returns -left=-7
        assertThat(Gesture.dirDiff(0, 9)).isEqualTo(-7)
    }

    @Test
    fun `dirDiff is antisymmetric`() {
        // dirDiff(a,b) should equal -dirDiff(b,a) for most cases
        for (a in 0..15) {
            for (b in 0..15) {
                if (a == b) continue
                val ab = Gesture.dirDiff(a, b)
                val ba = Gesture.dirDiff(b, a)
                // At distance 8 (half ring), both return 8, so antisymmetry breaks
                if (kotlin.math.abs(ab) != 8) {
                    assertThat(ab).isEqualTo(-ba)
                }
            }
        }
    }

    @Test
    fun `dirDiff result magnitude never exceeds 8`() {
        for (a in 0..15) {
            for (b in 0..15) {
                val diff = Gesture.dirDiff(a, b)
                assertThat(kotlin.math.abs(diff)).isAtMost(8)
            }
        }
    }

    @Test
    fun `dirDiff specific examples across ring`() {
        // Quarter turns
        assertThat(Gesture.dirDiff(0, 4)).isEqualTo(4)
        assertThat(Gesture.dirDiff(4, 0)).isEqualTo(-4)
        assertThat(Gesture.dirDiff(4, 8)).isEqualTo(4)
        assertThat(Gesture.dirDiff(8, 12)).isEqualTo(4)
        assertThat(Gesture.dirDiff(12, 0)).isEqualTo(4)

        // Small steps at various positions
        assertThat(Gesture.dirDiff(10, 12)).isEqualTo(2)
        assertThat(Gesture.dirDiff(12, 10)).isEqualTo(-2)
    }

    // =========================================================================
    // B. Initial state and get_gesture()
    // =========================================================================

    @Test
    fun `new gesture starts in Swiped state`() {
        val g = Gesture(0)
        assertThat(g.state).isEqualTo(Gesture.State.Swiped)
    }

    @Test
    fun `new gesture preserves starting direction`() {
        val g = Gesture(7)
        assertThat(g.currentDir).isEqualTo(7)
        assertThat(g.current_direction()).isEqualTo(7)
    }

    @Test
    fun `get_gesture returns Swipe for initial Swiped state`() {
        val g = Gesture(0)
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)
    }

    // =========================================================================
    // C. is_in_progress()
    // =========================================================================

    @Test
    fun `is_in_progress returns true for Swiped state`() {
        val g = Gesture(0)
        assertThat(g.is_in_progress()).isTrue()
    }

    @Test
    fun `is_in_progress returns false after pointer_up from Swiped`() {
        val g = Gesture(0)
        g.pointer_up()
        assertThat(g.is_in_progress()).isFalse()
    }

    @Test
    fun `is_in_progress returns false after moved_to_center from Swiped`() {
        val g = Gesture(0)
        g.moved_to_center()
        assertThat(g.is_in_progress()).isFalse()
    }

    // =========================================================================
    // D. moved_to_center() transitions
    // =========================================================================

    @Test
    fun `moved_to_center from Swiped transitions to Ended_center and returns true`() {
        val g = Gesture(0)
        val changed = g.moved_to_center()
        assertThat(changed).isTrue()
        assertThat(g.state).isEqualTo(Gesture.State.Ended_center)
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Roundtrip)
    }

    @Test
    fun `moved_to_center from Ended_center returns false`() {
        val g = Gesture(0)
        g.moved_to_center() // Swiped -> Ended_center
        val changed = g.moved_to_center() // Already in Ended state
        assertThat(changed).isFalse()
        assertThat(g.state).isEqualTo(Gesture.State.Ended_center)
    }

    // =========================================================================
    // E. pointer_up() transitions
    // =========================================================================

    @Test
    fun `pointer_up from Swiped transitions to Ended_swipe`() {
        val g = Gesture(0)
        g.pointer_up()
        assertThat(g.state).isEqualTo(Gesture.State.Ended_swipe)
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)
    }

    @Test
    fun `pointer_up from Ended_swipe stays in Ended_swipe`() {
        val g = Gesture(0)
        g.pointer_up()
        g.pointer_up() // Second call should be no-op
        assertThat(g.state).isEqualTo(Gesture.State.Ended_swipe)
    }

    @Test
    fun `pointer_up from Ended_center stays in Ended_center`() {
        val g = Gesture(0)
        g.moved_to_center()
        g.pointer_up()
        assertThat(g.state).isEqualTo(Gesture.State.Ended_center)
    }

    // =========================================================================
    // F. get_gesture() state-to-name mapping exhaustive test
    // =========================================================================

    @Test
    fun `get_gesture maps Swiped to Swipe`() {
        val g = Gesture(0)
        // Initial state is Swiped
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)
    }

    @Test
    fun `get_gesture maps Ended_swipe to Swipe`() {
        val g = Gesture(0)
        g.pointer_up()
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)
    }

    @Test
    fun `get_gesture maps Ended_center to Roundtrip`() {
        val g = Gesture(0)
        g.moved_to_center()
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Roundtrip)
    }

    // =========================================================================
    // G. Edge cases
    // =========================================================================

    @Test
    fun `gesture with direction 0 works correctly`() {
        val g = Gesture(0)
        assertThat(g.currentDir).isEqualTo(0)
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)
    }

    @Test
    fun `gesture with direction 15 works correctly`() {
        val g = Gesture(15)
        assertThat(g.currentDir).isEqualTo(15)
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)
    }

    @Test
    fun `gesture with each direction 0 to 15 initializes correctly`() {
        for (d in 0..15) {
            val g = Gesture(d)
            assertThat(g.currentDir).isEqualTo(d)
            assertThat(g.state).isEqualTo(Gesture.State.Swiped)
            assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)
            assertThat(g.is_in_progress()).isTrue()
        }
    }

    @Test
    fun `ROTATION_THRESHOLD constant is 2`() {
        assertThat(Gesture.ROTATION_THRESHOLD).isEqualTo(2)
    }

    @Test
    fun `full lifecycle - swipe then release`() {
        val g = Gesture(3)
        // Start in Swiped
        assertThat(g.is_in_progress()).isTrue()
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)

        // Pointer lifts up
        g.pointer_up()
        assertThat(g.is_in_progress()).isFalse()
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)
        assertThat(g.state).isEqualTo(Gesture.State.Ended_swipe)
    }

    @Test
    fun `full lifecycle - swipe then return to center`() {
        val g = Gesture(10)
        // Start in Swiped
        assertThat(g.is_in_progress()).isTrue()

        // Return to center = roundtrip gesture
        val changed = g.moved_to_center()
        assertThat(changed).isTrue()
        assertThat(g.is_in_progress()).isFalse()
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Roundtrip)
        assertThat(g.state).isEqualTo(Gesture.State.Ended_center)
    }

    // =========================================================================
    // H. State enum coverage
    // =========================================================================

    @Test
    fun `State enum has all 8 expected values`() {
        val states = Gesture.State.values()
        assertThat(states).hasLength(8)
        assertThat(states.map { it.name }).containsExactly(
            "Cancelled",
            "Swiped",
            "Rotating_clockwise",
            "Rotating_anticlockwise",
            "Ended_swipe",
            "Ended_center",
            "Ended_clockwise",
            "Ended_anticlockwise"
        )
    }

    @Test
    fun `Name enum has all 5 expected values`() {
        val names = Gesture.Name.values()
        assertThat(names).hasLength(5)
        assertThat(names.map { it.name }).containsExactly(
            "None",
            "Swipe",
            "Roundtrip",
            "Circle",
            "Anticircle"
        )
    }
}
