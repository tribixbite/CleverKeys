package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.ShortSwipeMapping

/**
 * Instrumented routing tests for [Pointers] — the short-swipe (subkey) vs
 * long-swipe (neural word) decision. Drives onTouchDown/Move/Up through a fake
 * [Pointers.IPointerEventHandler] with a controlled 3-key test layout and asserts
 * which output path fired (subkey output via onPointerUp vs word via onSwipeEnd).
 *
 * Test layout (single row, key 120w x 160h, diagonal = 200px):
 *   'a' x[0,120)  — has an EAST subkey "1" (keys[6])
 *   'b' x[120,240) — no subkeys
 *   'c' x[240,360) — no subkeys
 * With short_gesture_max_distance = 141%, the short/long boundary is
 * 200 * 1.41 = 282px of displacement-from-touch-down.
 *
 * Covers:
 *  - T1 overshoot toward an ASSIGNED subkey, displacement < boundary -> subkey (Commit 1 gate)
 *  - T2 compact word toward an EMPTY direction, displacement < boundary -> word (Commit 2 fallback)
 *  - T3 clearly-long swipe, displacement > boundary -> word (harness/control + Commit 1 no-regression)
 *  - T4 sub-boundary flick toward empty direction that is NOT a word candidate -> no word (Commit 2 guard)
 */
@RunWith(AndroidJUnit4::class)
class PointersGestureRoutingTest {

    private lateinit var context: Context
    private lateinit var config: Config
    private lateinit var handler: FakeHandler
    private lateinit var pointers: Pointers

    // --- test layout -------------------------------------------------------
    private val keyA = letterKey('a', eastSubkey = "1")
    private val keyB = letterKey('b')
    private val keyC = letterKey('c')

    private fun letterKey(c: Char, eastSubkey: String? = null): KeyboardData.Key {
        var k = KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.makeCharKey(c))
        if (eastSubkey != null) k = k.withKeyValue(6, KeyValue.makeStringKey(eastSubkey)) // index 6 = East
        return k
    }

    /** Geometric key lookup mirroring the real view's getKeyAtPosition. */
    private fun keyAt(x: Float, y: Float): KeyboardData.Key? = when {
        y < 0f || y >= 160f -> null
        x >= 0f && x < 120f -> keyA
        x >= 120f && x < 240f -> keyB
        x >= 240f && x < 360f -> keyC
        else -> null
    }

    /** Records which output path each gesture took; feeds keys to the recognizer on move. */
    private inner class FakeHandler : Pointers.IPointerEventHandler {
        val downValues = mutableListOf<KeyValue?>()
        val upValues = mutableListOf<KeyValue?>()
        var swipeEndCount = 0
        var customCount = 0

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { downValues.add(k) }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { upValues.add(k) }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) {}
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {
            recognizer.addPoint(x, y, keyAt(x, y))
        }
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) { swipeEndCount++ }
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = keyAt(x, y) == key
        override fun isPointWithinKeyWithTolerance(x: Float, y: Float, key: KeyboardData.Key, tolerance: Float): Boolean =
            keyAt(x, y) == key
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 200f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 120f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) { customCount++ }
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        config = Config.globalConfig()
        // Deterministic thresholds regardless of device metrics.
        config.short_gestures_enabled = true
        config.swipe_typing_enabled = true
        config.short_gesture_min_distance = 28
        config.short_gesture_max_distance = 141
        config.swipe_min_distance = 72f
        config.swipe_min_key_distance = 15f
        config.swipe_noise_threshold = 1.26f
        config.swipe_min_dwell_time = 7L
        config.swipe_high_velocity_threshold = 1000f
        config.swipe_smoothing_window = 1
        config.tap_duration_threshold = 150L
        config.keyrepeat_enabled = false
        config.swipe_dist_px = 100f // -> short-gesture minDistance = min(56, 80) = 56px (percentage wins)

        handler = FakeHandler()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            pointers = Pointers(handler, config, context)
        }
    }

    /** Drive a full gesture on the main thread, sleeping between samples so the
     *  recognizer's wall-clock timing (System.currentTimeMillis) advances. */
    private fun drive(downKey: KeyboardData.Key, downX: Float, downY: Float, moves: List<Pair<Float, Float>>) {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync { pointers.onTouchDown(downX, downY, 0, downKey) }
        for ((mx, my) in moves) {
            Thread.sleep(12)
            inst.runOnMainSync { pointers.onTouchMove(mx, my, 0) }
        }
        Thread.sleep(12)
        inst.runOnMainSync { pointers.onTouchUp(0) }
    }

    /** Straight horizontal samples (inclusive of end), ~20px apart. */
    private fun hMoves(fromX: Float, toX: Float, y: Float): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        var x = fromX
        val step = if (toX >= fromX) 20f else -20f
        while ((step > 0 && x < toX) || (step < 0 && x > toX)) {
            x += step
            out.add(Pair(x, y))
        }
        if (out.isEmpty() || out.last().first != toX) out.add(Pair(toX, y))
        return out
    }

    // =========================================================================

    /** T1: overshoot toward an assigned subkey (displacement 130 < 282) -> emit subkey "1", no word. */
    @Test
    fun overshoot_towardAssignedSubkey_emitsSubkey_notWord() {
        drive(keyA, 60f, 80f, hMoves(60f, 190f, 80f)) // 'a' east into 'b', ~130px
        assertEquals("must not commit a word swipe", 0, handler.swipeEndCount)
        assertTrue(
            "must emit the East subkey \"1\"",
            handler.upValues.any { it?.getString() == "1" }
        )
    }

    /** T2: compact word toward an empty direction (displacement 130 < 282) -> word fallback. */
    @Test
    fun compactWord_towardEmptyDirection_committsWord() {
        drive(keyB, 180f, 80f, hMoves(180f, 310f, 80f)) // 'b' east into 'c', ~130px, no 'b' subkey
        assertEquals("compact word toward an empty direction must commit a word", 1, handler.swipeEndCount)
        assertTrue("must not have emitted a subkey", handler.upValues.none { it?.getString() == "1" })
    }

    /** T3 (control): clearly-long swipe (displacement 290 > 282) -> word. Validates the harness wires word output. */
    @Test
    fun longSwipe_pastBoundary_committsWord() {
        drive(keyA, 60f, 80f, hMoves(60f, 350f, 80f)) // 'a' -> 'b' -> 'c', ~290px
        assertEquals("a clearly-long swipe must commit a word", 1, handler.swipeEndCount)
    }

    /** T4 (guard): sub-boundary flick toward an empty direction that is NOT a word candidate -> no word. */
    @Test
    fun shortFlick_emptyDirection_notWordCandidate_noWord() {
        // Vertical flick that stays within 'b' (one key) -> isSwipeTyping() never true.
        drive(keyB, 180f, 80f, listOf(180f to 65f, 180f to 50f, 180f to 35f, 180f to 20f, 180f to 10f))
        assertEquals("a non-word flick must never commit a word", 0, handler.swipeEndCount)
    }

    /** T5: a word swipe interrupted by a >MAX_POINT_INTERVAL_MS pause (a slow/deliberate
     *  swiper holding still mid-gesture) must still commit. Pre-fix, the resume point and all
     *  points after it were permanently dropped because _lastPointTime was never re-anchored,
     *  so the second key never registered and no word was produced. */
    @Test
    fun slowSwipe_withMidGesturePause_stillCommitsWord() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync { pointers.onTouchDown(180f, 80f, 0, keyB) }
        Thread.sleep(12); inst.runOnMainSync { pointers.onTouchMove(200f, 80f, 0) } // still 'b'
        Thread.sleep(12); inst.runOnMainSync { pointers.onTouchMove(220f, 80f, 0) } // still 'b'
        Thread.sleep(600); inst.runOnMainSync { pointers.onTouchMove(245f, 80f, 0) } // >500ms pause, resume into 'c'
        Thread.sleep(12); inst.runOnMainSync { pointers.onTouchMove(270f, 80f, 0) }
        Thread.sleep(12); inst.runOnMainSync { pointers.onTouchMove(300f, 80f, 0) }
        Thread.sleep(12); inst.runOnMainSync { pointers.onTouchUp(0) }
        assertEquals("a word swipe interrupted by a >500ms pause must still commit", 1, handler.swipeEndCount)
    }
}
