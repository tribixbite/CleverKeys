package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ARC-072 slice 1 — the payoff, stated as behaviour: the two leaf hot-path classes are now
 * driven entirely by a hand-built [tribixbite.cleverkeys.prefs.ConfigSnapshot], with no
 * `Config.initGlobalConfig` anywhere in the process.
 *
 * Before this slice neither class could be tested off-device for its config-dependent
 * behaviour: `Gesture.changed_direction()` reached for `Config.globalConfig()` (which is why
 * `GestureTest` documented it as untestable and skipped it entirely), and
 * `GestureClassifier` needed both a `Context` and an initialised global. Both decisions are
 * now pure functions of their inputs plus a captured snapshot — so the assertions below pin
 * the *decision flip*: identical gesture data, one snapshot field changed, opposite outcome.
 */
class ConfigSnapshotBehaviorTest {

    // =========================================================================
    // Gesture — rotation onset is gated by snapshot.circle_sensitivity
    // =========================================================================

    @Test
    fun `rotation does not start below the snapshot circle sensitivity`() {
        val g = Gesture(0, testConfigSnapshot(circle_sensitivity = 3))

        // dirDiff(0, 2) = +2, below the 3-step threshold.
        assertThat(g.changed_direction(2)).isFalse()
        assertThat(g.state).isEqualTo(Gesture.State.Swiped)
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Swipe)
        // Sub-threshold travel must not advance the reference direction either, or the
        // threshold could be walked past one unnoticed step at a time.
        assertThat(g.current_direction()).isEqualTo(0)
    }

    @Test
    fun `rotation starts once travel reaches the snapshot circle sensitivity`() {
        val g = Gesture(0, testConfigSnapshot(circle_sensitivity = 3))

        // dirDiff(0, 3) = +3, exactly at the threshold ("< sensitivity" is the reject test).
        assertThat(g.changed_direction(3)).isTrue()
        assertThat(g.state).isEqualTo(Gesture.State.Rotating_clockwise)
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Circle)
        assertThat(g.current_direction()).isEqualTo(3)
        assertThat(g.is_in_progress()).isTrue()
    }

    @Test
    fun `the same direction change flips outcome with the snapshot sensitivity`() {
        // Identical input, two snapshots — this is the property the read-model buys.
        val sensitive = Gesture(0, testConfigSnapshot(circle_sensitivity = 2))
        val dull = Gesture(0, testConfigSnapshot(circle_sensitivity = 3))

        assertThat(sensitive.changed_direction(2)).isTrue()
        assertThat(sensitive.get_gesture()).isEqualTo(Gesture.Name.Circle)

        assertThat(dull.changed_direction(2)).isFalse()
        assertThat(dull.get_gesture()).isEqualTo(Gesture.Name.Swipe)
    }

    @Test
    fun `anticlockwise travel starts an anticircle`() {
        val g = Gesture(8, testConfigSnapshot(circle_sensitivity = 2))

        // dirDiff(8, 5) = -3: shortest path is anticlockwise, magnitude 3 >= 2.
        assertThat(g.changed_direction(5)).isTrue()
        assertThat(g.state).isEqualTo(Gesture.State.Rotating_anticlockwise)
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.Anticircle)
        assertThat(g.current_direction()).isEqualTo(5)
    }

    @Test
    fun `reversing an in-progress rotation cancels the gesture`() {
        val g = Gesture(0, testConfigSnapshot(circle_sensitivity = 1))

        assertThat(g.changed_direction(4)).isTrue()             // clockwise rotation begins
        assertThat(g.state).isEqualTo(Gesture.State.Rotating_clockwise)

        assertThat(g.changed_direction(2)).isTrue()             // reverses: -2
        assertThat(g.state).isEqualTo(Gesture.State.Cancelled)
        assertThat(g.get_gesture()).isEqualTo(Gesture.Name.None)
        assertThat(g.is_in_progress()).isFalse()
    }

    @Test
    fun `a snapshot captured per gesture is immune to later config changes`() {
        // The whole point of capture-at-start: two gestures alive at once, each holding the
        // configuration it began with. A refresh between them changes the second only.
        val before = testConfigSnapshot(circle_sensitivity = 5)
        val after = before.copy(circle_sensitivity = 1)

        val started = Gesture(0, before)
        val startedAfterRefresh = Gesture(0, after)

        assertThat(started.changed_direction(2)).isFalse()      // 2 < 5, still no rotation
        assertThat(startedAfterRefresh.changed_direction(2)).isTrue()
    }

    // =========================================================================
    // GestureClassifier — TAP/SWIPE decision from the snapshot alone
    // =========================================================================

    private val classifier = GestureClassifier()

    private fun data(
        hasLeftStartingKey: Boolean = true,
        totalDistance: Float = 0f,
        timeElapsed: Long = 0L,
        keyWidth: Float = 100f
    ) = GestureClassifier.GestureData(hasLeftStartingKey, totalDistance, timeElapsed, keyWidth)

    @Test
    fun `long press becomes a swipe only above the snapshot tap duration`() {
        val slow = data(totalDistance = 10f, timeElapsed = 200L)   // 10px, well under 50px

        assertThat(classifier.classify(slow, testConfigSnapshot(tap_duration_threshold = 150L)))
            .isEqualTo(GestureClassifier.GestureType.SWIPE)
        assertThat(classifier.classify(slow, testConfigSnapshot(tap_duration_threshold = 500L)))
            .isEqualTo(GestureClassifier.GestureType.TAP)
    }

    @Test
    fun `duration exactly at the threshold is still a tap`() {
        // The predicate is strictly greater-than; pin the boundary so a refactor can't
        // silently turn every 150ms press into a swipe.
        val atThreshold = data(totalDistance = 10f, timeElapsed = 150L)
        assertThat(classifier.classify(atThreshold, testConfigSnapshot(tap_duration_threshold = 150L)))
            .isEqualTo(GestureClassifier.GestureType.TAP)
    }

    @Test
    fun `distance threshold is half the key width`() {
        val snap = testConfigSnapshot(tap_duration_threshold = 150L)

        assertThat(classifier.classify(data(totalDistance = 50f, keyWidth = 100f), snap))
            .isEqualTo(GestureClassifier.GestureType.SWIPE)
        assertThat(classifier.classify(data(totalDistance = 49.9f, keyWidth = 100f), snap))
            .isEqualTo(GestureClassifier.GestureType.TAP)
        // Same displacement, wider key: no longer half a key, so no longer a swipe.
        assertThat(classifier.classify(data(totalDistance = 50f, keyWidth = 500f), snap))
            .isEqualTo(GestureClassifier.GestureType.TAP)
    }

    @Test
    fun `a gesture that never left its key is a tap however long or far`() {
        val snap = testConfigSnapshot(tap_duration_threshold = 150L)
        val stayed = data(hasLeftStartingKey = false, totalDistance = 500f, timeElapsed = 5000L)
        assertThat(classifier.classify(stayed, snap)).isEqualTo(GestureClassifier.GestureType.TAP)
    }

    // =========================================================================
    // Slice 2 — the short-swipe thresholds a Pointer decides against, and the
    // capture semantics that make them stable for a gesture already in flight.
    // =========================================================================

    /**
     * The min threshold is `min(percent-of-diagonal, swipe_dist_px * 0.8)`: the percentage
     * wins on ordinary keys, the absolute cap wins on wide ones (backspace/shift/space),
     * which is what stops a wide key from demanding an uncomfortably long swipe.
     */
    @Test
    fun `short swipe minimum takes the percentage until the wide-key cap bites`() {
        val snap = testConfigSnapshot(
            short_gesture_min_distance = PercentOfKey(28),
            swipe_dist_px = 100f // cap = 80px
        )

        // Ordinary key: 28% of a 200px diagonal = 56px, below the 80px cap.
        assertThat(snap.shortGestureMinDistancePx(200f)).isWithin(0.01f).of(56f)
        // Wide key: 28% of a 400px diagonal would be 112px — the cap wins.
        assertThat(snap.shortGestureMinDistancePx(400f)).isWithin(0.01f).of(80f)
    }

    @Test
    fun `an unset swipe distance leaves the percentage uncapped`() {
        // swipe_dist_px == 0 means "no absolute threshold configured"; the cap must not
        // then collapse to 0 and classify every micro-jitter as a short swipe.
        val snap = testConfigSnapshot(
            short_gesture_min_distance = PercentOfKey(28),
            swipe_dist_px = 0f
        )
        assertThat(snap.shortGestureMinDistancePx(400f)).isWithin(0.01f).of(112f)
    }

    @Test
    fun `the max threshold is the percent of the key diagonal`() {
        val snap = testConfigSnapshot(short_gesture_max_distance = PercentOfKey(141))
        assertThat(snap.shortGestureMaxDistancePx(200f)).isWithin(0.01f).of(282f)
    }

    /**
     * The capture property, stated as behaviour on the decision itself.
     *
     * `Pointers` cannot be constructed off-device (it needs an `android.os.Handler`, a
     * `Context` and the `ShortSwipeCustomizationManager` singleton), so this asserts the
     * property at the level where the decision is actually made: a `ConfigSnapshot` held by
     * an in-flight gesture keeps answering with the configuration it captured, no matter
     * what a later `Config.refresh()` publishes. `Pointer.snap` is a `val` assigned at
     * pointer-down — `ConfigSnapshotRatchetTest` pins that shape, and this pins that the
     * shape is worth having: the two configurations genuinely disagree about the same
     * gesture.
     */
    @Test
    fun `a captured snapshot keeps deciding by the configuration it captured`() {
        val atPointerDown = testConfigSnapshot(
            short_gesture_min_distance = PercentOfKey(28),
            short_gesture_max_distance = PercentOfKey(141),
            swipe_dist_px = 100f
        )
        // What a settings change publishes mid-gesture: short swipes now need much more
        // travel and give up much sooner.
        val publishedMidGesture = testConfigSnapshot(
            short_gesture_min_distance = PercentOfKey(60),
            short_gesture_max_distance = PercentOfKey(70),
            swipe_dist_px = 400f
        )

        val diagonal = 200f
        val travelled = 100f // between 56 and 282: a short swipe under the captured config

        // The gesture in flight commits a short swipe...
        assertThat(travelled).isAtLeast(atPointerDown.shortGestureMinDistancePx(diagonal))
        assertThat(travelled).isAtMost(atPointerDown.shortGestureMaxDistancePx(diagonal))

        // ...even though the configuration that landed mid-gesture would have rejected it
        // as too short (min 120px) — proving the two answers really differ, so holding the
        // captured reference is what keeps the gesture coherent rather than a no-op.
        assertThat(travelled).isLessThan(publishedMidGesture.shortGestureMinDistancePx(diagonal))

        // And the captured value object cannot have been mutated under its holder.
        assertThat(atPointerDown.shortGestureMinDistancePx(diagonal)).isWithin(0.01f).of(56f)
    }

    // =========================================================================
    // The testability proof itself
    // =========================================================================

    @Test
    fun `neither class needs the global Config to be initialised`() {
        val snap = testConfigSnapshot(circle_sensitivity = 2, tap_duration_threshold = 150L)

        Gesture(0, snap).changed_direction(4)
        classifier.classify(data(totalDistance = 80f, timeElapsed = 40L), snap)

        // Nothing above reached for the mutable global — if either class still did, this
        // whole test class would have thrown a "Config not initialized" NPE instead.
        assertThat(Config.globalConfigOrNull()).isNull()
    }
}
