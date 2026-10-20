package tribixbite.cleverkeys

import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import java.lang.reflect.Field

/**
 * GH #190 — backspace swipe+hold "Follow finger" selection mode.
 *
 * The historical selection-delete mode is a rate-controlled joystick: a repeat timer sends
 * Shift+arrows faster the further the finger is from the activation point. Follow-finger
 * mode makes the selection a function of finger POSITION instead: `trunc(dx / step)`
 * characters, recomputed on every move, so moving back shrinks the selection.
 *
 * Drives the REAL [Pointers] state machine (constructor-less instance with injected
 * collaborators — the same harness as [ReleaseClaimGestureModesTest]) through
 * `handleLongPress` (mode entry), the public `onTouchMove` and `onTouchUp`.
 *
 * Geometry: key diagonal 200 px, so the key-height approximation is 140 px and the vertical
 * dead zone at the 40 % threshold is 56 px. Follow-finger step is 20 px per character.
 */
class PointersFollowFingerSelectionTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: FakeHandler
    private lateinit var msgHandler: Handler
    private lateinit var config: Config
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer
    private lateinit var ptrs: ArrayList<Pointers.Pointer>

    /** Every `sendEmptyMessageDelayed(what, delay)` the code under test scheduled. */
    private val scheduled = mutableListOf<Pair<Int, Long>>()

    private val keyDiagonal = 200f
    private val stepPx = 20f

    private val backspace = KeyValue.getKeyByName("backspace")
    private val backspaceKey: KeyboardData.Key = KeyboardData.Key.EMPTY.withKeyValue(0, backspace)

    private fun noMods(): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    private fun snapshot(followFinger: Boolean): ConfigSnapshot = testConfigSnapshot(
        keyrepeat_enabled = true,
        swipe_typing_enabled = true,
        selection_delete_vertical_threshold = 40,
        selection_delete_vertical_speed = 0.4f,
        selection_delete_follow_finger = followFinger,
        selection_delete_step_px = stepPx,
        short_gesture_min_distance = PercentOfKey(25),
        swipe_dist_px = 30f,
        longPressTimeout = 600L
    )

    @Before
    fun setUp() {
        // Debug builds compile ENABLE_VERBOSE_LOGGING as true, so production paths log.
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        handler = FakeHandler(keyDiagonal)
        scheduled.clear()

        msgHandler = mockk(relaxed = true)
        every { msgHandler.sendEmptyMessageDelayed(any(), any()) } answers {
            scheduled += firstArg<Int>() to secondArg<Long>()
            true
        }

        config = mockk(relaxed = true)
        recognizer = mockk(relaxed = true)
        ptrs = ArrayList()

        pointers = allocate(Pointers::class.java)
        setField(pointers, "_handler", handler)
        setField(pointers, "_config", config)
        setField(pointers, "_longpress_handler", msgHandler)
        setField(pointers, "_ptrs", ptrs)
        setField(pointers, "_swipeRecognizer", recognizer)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ============================================================== entering the mode

    @Test
    fun `follow-finger entry selects nothing and arms no repeat timer`() {
        val ptr = enterSelectionMode(followFinger = true)

        assertThat(ptr.hasFlagsAny(Pointers.FLAG_P_SELECTION_DELETE_MODE)).isTrue()
        assertThat(ptr.hasFlagsAny(Pointers.FLAG_P_DEFERRED_DOWN)).isFalse()
        assertWithMessage("activation point is the finger position when the hold fired")
            .that(ptr.keyCenterX).isEqualTo(140f)
        assertThat(handler.flagEvents).containsExactly(HapticEvent.TRACKPOINT_ACTIVATE)
        assertWithMessage("no joystick timer: selection is driven by moves only")
            .that(scheduled).isEmpty()
        assertThat(handler.ups).isEmpty()
        assertThat(ptr.selectionStepsX).isEqualTo(0)
        assertThat(ptr.selectionStepsY).isEqualTo(0)
    }

    @Test
    fun `default speed mode entry still arms the joystick timer`() {
        val ptr = enterSelectionMode(followFinger = false)

        assertThat(ptr.hasFlagsAny(Pointers.FLAG_P_SELECTION_DELETE_MODE)).isTrue()
        assertThat(scheduled).containsExactly(
            ptr.selectionDeleteWhat to Pointers.TRACKPOINT_INITIAL_DELAY
        )
    }

    // ========================================================= proportional selection

    @Test
    fun `selection grows in proportion to horizontal travel`() {
        val ptr = enterSelectionMode(followFinger = true)

        // 45 px left of the activation point = 2 whole steps of 20 px.
        move(ptr, 140f - 45f)
        assertThat(arrows()).containsExactly(LEFT, LEFT)
        assertThat(ptr.selectionStepsX).isEqualTo(-2)

        // A further 60 px (105 px total) = 5 steps: only the 3 new ones are sent.
        handler.clear()
        move(ptr, 140f - 105f)
        assertThat(arrows()).containsExactly(LEFT, LEFT, LEFT)
        assertThat(ptr.selectionStepsX).isEqualTo(-5)

        // Moving within the same step sends nothing.
        handler.clear()
        move(ptr, 140f - 115f)
        assertThat(arrows()).isEmpty()

        assertWithMessage("every arrow is sent with Shift, which is what makes it select")
            .that(handler.ups.all { it.second.has(KeyValue.Modifier.SHIFT) }).isTrue()
        assertWithMessage("no timer is ever armed in follow-finger mode").that(scheduled).isEmpty()
    }

    @Test
    fun `moving back shrinks the selection and crossing the start selects the other way`() {
        val ptr = enterSelectionMode(followFinger = true)
        move(ptr, 140f - 105f) // 5 characters to the left
        handler.clear()

        // Back to 65 px from the start = 3 characters: two Shift+Right undo two of them.
        move(ptr, 140f - 65f)
        assertThat(arrows()).containsExactly(RIGHT, RIGHT)
        assertThat(ptr.selectionStepsX).isEqualTo(-3)

        // Back to the activation point: the whole selection is undone.
        handler.clear()
        move(ptr, 140f)
        assertThat(arrows()).containsExactly(RIGHT, RIGHT, RIGHT)
        assertThat(ptr.selectionStepsX).isEqualTo(0)

        // 30 px past it to the right = 1 character on the other side.
        handler.clear()
        move(ptr, 140f + 30f)
        assertThat(arrows()).containsExactly(RIGHT)
        assertThat(ptr.selectionStepsX).isEqualTo(1)
    }

    @Test
    fun `vertical travel selects lines beyond the dead zone and shrinks back`() {
        val ptr = enterSelectionMode(followFinger = true)

        // 30 px down is inside the 56 px vertical dead zone.
        move(ptr, 140f, 100f + 30f)
        assertThat(arrows()).isEmpty()

        // Leaving the dead zone selects one line; one more key height (140 px) a second.
        move(ptr, 140f, 100f + 60f)
        assertThat(arrows()).containsExactly(DOWN)
        handler.clear()
        move(ptr, 140f, 100f + 60f + 140f)
        assertThat(arrows()).containsExactly(DOWN)
        assertThat(ptr.selectionStepsY).isEqualTo(2)

        handler.clear()
        move(ptr, 140f, 100f)
        assertThat(arrows()).containsExactly(UP, UP)
        assertThat(ptr.selectionStepsY).isEqualTo(0)
    }

    // ============================================================== unchanged default

    @Test
    fun `default speed mode sends nothing on move - only its timer selects`() {
        val ptr = enterSelectionMode(followFinger = false)
        handler.clear()

        move(ptr, 140f - 105f)

        assertWithMessage("speed mode is position-independent between timer ticks")
            .that(arrows()).isEmpty()
        assertThat(ptr.selectionStepsX).isEqualTo(0)
        assertThat(ptr.hasFlagsAny(Pointers.FLAG_P_SELECTION_DELETE_MODE)).isTrue()

        // The joystick tick still selects at the distance-scaled rate: 105 px from the
        // activation point is past the 100 px radius, so the fastest delay.
        scheduled.clear()
        invokePrivate("handleSelectionDeleteRepeat", ptr)
        assertThat(arrows()).containsExactly(LEFT)
        assertThat(scheduled.single().second).isEqualTo(Pointers.TRACKPOINT_MIN_DELAY)
    }

    // ===================================================================== release

    @Test
    fun `release in follow-finger mode deletes the selection exactly like speed mode`() {
        for (followFinger in listOf(true, false)) {
            setUp()
            val ptr = enterSelectionMode(followFinger)
            move(ptr, 140f - 65f)
            handler.clear()

            pointers.onTouchUp(0)

            assertWithMessage("followFinger=$followFinger: one DEL deletes the selection")
                .that(handler.downs.map { it?.getKeyevent() }).containsExactly(KeyEvent.KEYCODE_DEL)
            assertThat(handler.ups.map { it.first?.getKeyevent() }).containsExactly(KeyEvent.KEYCODE_DEL)
            assertWithMessage("the pointer is retired").that(ptrs).doesNotContain(ptr)
            assertWithMessage("the highlight is cleared").that(handler.flagEvents.last()).isNull()
        }
    }

    // ============================================================== pure step math

    @Test
    fun `step math truncates toward zero and refuses a degenerate step`() {
        assertThat(Pointers.followFingerSteps(39.9f, 20f)).isEqualTo(1)
        assertThat(Pointers.followFingerSteps(-39.9f, 20f)).isEqualTo(-1)
        assertThat(Pointers.followFingerSteps(19.9f, 20f)).isEqualTo(0)
        assertThat(Pointers.followFingerSteps(100f, 0f)).isEqualTo(0)
        assertThat(Pointers.followFingerSteps(100f, Float.NaN)).isEqualTo(0)
        assertThat(Pointers.followFingerSteps(Float.NaN, 20f)).isEqualTo(0)

        assertThat(Pointers.followFingerLines(56f, 56f, 140f)).isEqualTo(0)
        assertThat(Pointers.followFingerLines(57f, 56f, 140f)).isEqualTo(1)
        assertThat(Pointers.followFingerLines(-197f, 56f, 140f)).isEqualTo(-2)
        assertThat(Pointers.followFingerLines(500f, 56f, 0f)).isEqualTo(0)
    }

    // ------------------------------------------------------------------------------ plumbing

    /**
     * A deferred backspace press whose finger moved 40 px right (past the 24 px short-swipe
     * minimum) when the long-press fired, i.e. a short swipe + hold.
     */
    private fun enterSelectionMode(followFinger: Boolean): Pointers.Pointer {
        val ptr = Pointers.Pointer(
            0, backspaceKey, backspace, 100f, 100f, noMods(),
            Pointers.FLAG_P_DEFERRED_DOWN, snapshot(followFinger)
        )
        ptrs.add(ptr)
        ptr.lastX = 140f
        ptr.lastY = 100f
        invokePrivate("handleLongPress", ptr)
        return ptr
    }

    private fun move(ptr: Pointers.Pointer, x: Float, y: Float = 100f) =
        pointers.onTouchMove(x, y, ptr.pointerId)

    /** Keyevent codes of the arrows released so far, in order. */
    private fun arrows(): List<Int?> = handler.ups.map { it.first?.getKeyevent() }

    private fun invokePrivate(name: String, ptr: Pointers.Pointer) {
        val method = Pointers::class.java.getDeclaredMethod(name, Pointers.Pointer::class.java)
        method.isAccessible = true
        method.invoke(pointers, ptr)
    }

    private companion object {
        const val LEFT = KeyEvent.KEYCODE_DPAD_LEFT
        const val RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
        const val UP = KeyEvent.KEYCODE_DPAD_UP
        const val DOWN = KeyEvent.KEYCODE_DPAD_DOWN
    }

    /** Records everything Pointers reports back to the keyboard view. */
    private class FakeHandler(private val hypotenuse: Float) : Pointers.IPointerEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<Pair<KeyValue?, Pointers.Modifiers>>()
        val flagEvents = mutableListOf<HapticEvent?>()

        fun clear() {
            downs.clear()
            ups.clear()
        }

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { downs += k }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k to mods }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) { flagEvents += hapticEvent }
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(
            x: Float, y: Float, key: KeyboardData.Key, tolerance: Float
        ): Boolean = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = hypotenuse
        override fun getKeyWidth(key: KeyboardData.Key): Float = 120f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) {}
    }

    // -- sun.misc.Unsafe seams (same idiom as ReleaseClaimGestureModesTest) --

    private fun unsafe(): Any {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val u = unsafe()
        return u.javaClass.getMethod("allocateInstance", Class::class.java).invoke(u, type) as T
    }

    /** Writes a `private val` field; Unsafe ignores both access and finality. */
    private fun setField(target: Any, name: String, value: Any?) {
        val u = unsafe()
        val field: Field = target.javaClass.getDeclaredField(name)
        val offset = u.javaClass
            .getMethod("objectFieldOffset", Field::class.java).invoke(u, field) as Long
        u.javaClass.getMethod(
            "putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java
        ).invoke(u, target, offset, value)
    }
}
