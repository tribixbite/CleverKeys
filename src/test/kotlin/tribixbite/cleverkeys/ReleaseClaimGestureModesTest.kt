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
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import java.lang.reflect.Field

/**
 * Release-record guards for the three [Pointers] gesture modes.
 *
 * | version | published note |
 * |---|---|
 * | v1.2.4 / v1.2.5 | "TrackPoint mode — hold nav key to enter joystick cursor control" |
 * | v1.2.4 | "Diagonal movement support and speed scaling with distance from centre" |
 * | v1.2.4 / v1.2.5 | "Selection-delete mode — short swipe + hold backspace to select then delete text" |
 * | v1.2.8 | "Swipe capitalization — captures the shift state at swipe START" |
 *
 * ## How this drives real production code
 *
 * [Pointers] cannot be constructed off-device: its field initializers build an
 * `android.os.Handler`, a `ShortSwipeCustomizationManager` and a coroutine scope, and
 * `android.jar`'s stubs throw `RuntimeException("Stub!")`. So the instance is allocated
 * WITHOUT running its constructor (`sun.misc.Unsafe`, the same escape hatch
 * `VibratorCompatTest` and `DirectBootManagerTest` already use for `Build.VERSION.SDK_INT`),
 * its collaborators are injected, and the REAL private methods are invoked reflectively.
 * Nothing about the decision logic is re-implemented here.
 *
 * The test keyboard uses a key with a 200 px diagonal, so the TrackPoint normalisation
 * distance (`hypotenuse * 0.5`) is exactly 100 px and the delay arithmetic is exact.
 */
class ReleaseClaimGestureModesTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: FakeHandler
    private lateinit var msgHandler: Handler
    private lateinit var config: Config
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer
    private lateinit var ptrs: ArrayList<Pointers.Pointer>

    /** Every `sendEmptyMessageDelayed(what, delay)` the code under test scheduled. */
    private val scheduled = mutableListOf<Pair<Int, Long>>()

    /** Key diagonal reported to Pointers; halves to a 100 px joystick radius. */
    private val keyDiagonal = 200f

    // ------------------------------------------------------------------------------- fixtures

    private val navUp = KeyValue.keyeventKey(0xE005, KeyEvent.KEYCODE_DPAD_UP, 0)
    private val navRight = KeyValue.keyeventKey(0xE006, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
    private val navDown = KeyValue.keyeventKey(0xE007, KeyEvent.KEYCODE_DPAD_DOWN, 0)
    private val navLeft = KeyValue.keyeventKey(0xE008, KeyEvent.KEYCODE_DPAD_LEFT, 0)

    /** A key whose centre is not a nav key but which carries the four arrow sub-keys. */
    private val navKey: KeyboardData.Key = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("compose"))
        .withKeyValue(5, navLeft)
        .withKeyValue(6, navRight)
        .withKeyValue(7, navUp)
        .withKeyValue(8, navDown)

    private val letterKey: KeyboardData.Key =
        KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.makeCharKey('a'))

    private val backspaceKey: KeyboardData.Key =
        KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.getKeyByName("backspace"))

    private fun noMods(): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    private fun snapshot(
        keyrepeatEnabled: Boolean = true,
        swipeTypingEnabled: Boolean = true,
        verticalThreshold: Int = 40,
        verticalSpeed: Float = 0.4f
    ): ConfigSnapshot = testConfigSnapshot(
        keyrepeat_enabled = keyrepeatEnabled,
        swipe_typing_enabled = swipeTypingEnabled,
        selection_delete_vertical_threshold = verticalThreshold,
        selection_delete_vertical_speed = verticalSpeed,
        short_gesture_min_distance = PercentOfKey(25),
        swipe_dist_px = 30f,
        longPressTimeout = 600L
    )

    private fun pointer(
        key: KeyboardData.Key,
        value: KeyValue?,
        flags: Int,
        snap: ConfigSnapshot = snapshot(),
        downX: Float = 100f,
        downY: Float = 100f
    ) = Pointers.Pointer(0, key, value, downX, downY, noMods(), flags, snap)

    @Before
    fun setUp() {
        // Debug builds compile ENABLE_VERBOSE_LOGGING as true, so the production paths below
        // really do call android.util.Log, whose android.jar stub throws.
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

    // ============================================== v1.2.4/v1.2.5 — entering TrackPoint mode

    @Test
    fun `holding a key with nav sub-keys enters TrackPoint mode`() {
        val ptr = pointer(navKey, KeyValue.getKeyByName("compose"), Pointers.FLAG_P_DEFERRED_DOWN)
        // The finger drifted before the long press fired; TrackPoint still activates.
        ptr.lastX = 150f
        ptr.lastY = 120f

        invokeLongPress(ptr)

        assertWithMessage("TrackPoint mode flag set")
            .that(ptr.hasFlagsAny(Pointers.FLAG_P_TRACKPOINT_MODE)).isTrue()
        assertWithMessage("the deferred-down flag is consumed, so no character is emitted")
            .that(ptr.hasFlagsAny(Pointers.FLAG_P_DEFERRED_DOWN)).isFalse()

        assertWithMessage("the joystick centres on the CURRENT finger position, not the touch-down")
            .that(ptr.keyCenterX).isEqualTo(150f)
        assertThat(ptr.keyCenterY).isEqualTo(120f)

        assertWithMessage("activation is announced by a distinct haptic")
            .that(handler.flagEvents).containsExactly(HapticEvent.TRACKPOINT_ACTIVATE)
        assertWithMessage("no key output on activation")
            .that(handler.downs).isEmpty()

        assertThat(scheduled).containsExactly(ptr.trackpointWhat to Pointers.TRACKPOINT_INITIAL_DELAY)
    }

    @Test
    fun `a key without nav sub-keys never enters TrackPoint mode`() {
        val ptr = pointer(letterKey, KeyValue.makeCharKey('a'), Pointers.FLAG_P_DEFERRED_DOWN)

        invokeLongPress(ptr)

        assertThat(ptr.hasFlagsAny(Pointers.FLAG_P_TRACKPOINT_MODE)).isFalse()
        assertThat(handler.flagEvents).isEmpty()
    }

    @Test
    fun `TrackPoint mode respects the key-repeat setting`() {
        val ptr = pointer(
            navKey, KeyValue.getKeyByName("compose"), Pointers.FLAG_P_DEFERRED_DOWN,
            snap = snapshot(keyrepeatEnabled = false)
        )

        invokeLongPress(ptr)

        assertWithMessage("key repeat off means no joystick either")
            .that(ptr.hasFlagsAny(Pointers.FLAG_P_TRACKPOINT_MODE)).isFalse()
        assertThat(scheduled).isEmpty()
    }

    // ========================== v1.2.4 — diagonal movement and speed scaling from the centre

    @Test
    fun `a diagonal finger position fires both axes in one repeat`() {
        val ptr = trackPointPointer(dx = 80f, dy = -60f)

        invokeTrackPointRepeat(ptr)

        assertWithMessage(
            "v1.2.4 'Diagonal movement support': a north-east finger must emit RIGHT and UP, " +
                "not just the dominant axis"
        ).that(handler.downs.map { it?.getKeyevent() })
            .containsExactly(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP).inOrder()
        assertThat(handler.ups.map { it.first?.getKeyevent() })
            .containsExactly(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP).inOrder()
    }

    @Test
    fun `each quadrant picks the matching pair of nav sub-keys`() {
        val cases = mapOf(
            (80f to 60f) to listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN),
            (-80f to 60f) to listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN),
            (-80f to -60f) to listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP),
            (80f to -60f) to listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP)
        )
        for ((offset, expected) in cases) {
            setUp()
            val ptr = trackPointPointer(offset.first, offset.second)
            invokeTrackPointRepeat(ptr)
            assertWithMessage("finger at (${offset.first}, ${offset.second})")
                .that(handler.downs.map { it?.getKeyevent() })
                .containsExactlyElementsIn(expected).inOrder()
        }
    }

    @Test
    fun `only the axis that left the dead zone fires`() {
        // Horizontal past the dead zone, vertical inside it.
        val ptr = trackPointPointer(dx = 80f, dy = Pointers.TRACKPOINT_DEAD_ZONE - 1f)

        invokeTrackPointRepeat(ptr)

        assertThat(handler.downs.map { it?.getKeyevent() })
            .containsExactly(KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    @Test
    fun `a finger inside the dead zone moves nothing and polls at the slowest rate`() {
        val ptr = trackPointPointer(dx = 10f, dy = 10f)

        invokeTrackPointRepeat(ptr)

        assertThat(handler.downs).isEmpty()
        assertThat(handler.ups).isEmpty()
        assertThat(scheduled.single().second).isEqualTo(Pointers.TRACKPOINT_MAX_DELAY)
    }

    @Test
    fun `repeat delay scales from the dead zone down to the key edge`() {
        // maxDistance = hypotenuse * 0.5 = 100 px, so the normalised displacement is dx/100.
        // delay = MAX - normalised * (MAX - MIN) = 200 - n * 170.
        val measured = listOf(20f, 50f, 100f, 400f).map { dx ->
            setUp()
            val ptr = trackPointPointer(dx = dx, dy = 0f)
            invokeTrackPointRepeat(ptr)
            dx to scheduled.single().second
        }.toMap()

        assertThat(measured[20f]).isEqualTo(166L)   // 200 - 0.2 * 170
        assertThat(measured[50f]).isEqualTo(115L)   // 200 - 0.5 * 170
        assertWithMessage("at the key edge the joystick runs at its fastest")
            .that(measured[100f]).isEqualTo(Pointers.TRACKPOINT_MIN_DELAY)
        assertWithMessage("past the edge the speed is clamped, never inverted")
            .that(measured[400f]).isEqualTo(Pointers.TRACKPOINT_MIN_DELAY)

        assertWithMessage("v1.2.4 'speed scaling with distance from centre' is monotonic")
            .that(listOf(measured[20f]!!, measured[50f]!!, measured[100f]!!))
            .isInStrictOrder(Comparator<Long> { a, b -> b.compareTo(a) })
    }

    @Test
    fun `the diagonal repeat rate follows the further axis`() {
        // dx = 30 (n=0.3), dy = -90 (n=0.9) → the faster of the two wins.
        val ptr = trackPointPointer(dx = 30f, dy = -90f)

        invokeTrackPointRepeat(ptr)

        assertThat(scheduled.single().second).isEqualTo(47L) // 200 - 0.9 * 170 = 47
    }

    @Test
    fun `a repeat that is not in TrackPoint mode does nothing`() {
        val ptr = pointer(navKey, KeyValue.getKeyByName("compose"), 0)
        ptr.lastX = 300f

        invokeTrackPointRepeat(ptr)

        assertThat(handler.downs).isEmpty()
        assertWithMessage("a stale repeat message must not re-arm the timer")
            .that(scheduled).isEmpty()
    }

    // ================================ v1.2.4/v1.2.5 — selection-delete mode on backspace

    @Test
    fun `a short swipe then hold on backspace enters selection-delete mode`() {
        val ptr = pointer(
            backspaceKey, KeyValue.getKeyByName("backspace"), Pointers.FLAG_P_DEFERRED_DOWN
        )
        // shortGestureMinDistancePx = min(200 * 25%, 30 * 0.8) = 24 px; 40 px clears it.
        ptr.lastX = 140f
        ptr.lastY = 100f

        invokeLongPress(ptr)

        assertThat(ptr.hasFlagsAny(Pointers.FLAG_P_SELECTION_DELETE_MODE)).isTrue()
        assertThat(ptr.hasFlagsAny(Pointers.FLAG_P_DEFERRED_DOWN)).isFalse()
        assertWithMessage("a rightward swipe selects to the right").that(ptr.selectionDirection).isEqualTo(1)
        assertThat(ptr.keyCenterX).isEqualTo(140f)
        assertThat(handler.flagEvents).containsExactly(HapticEvent.TRACKPOINT_ACTIVATE)
        assertThat(scheduled).containsExactly(
            ptr.selectionDeleteWhat to Pointers.TRACKPOINT_INITIAL_DELAY
        )
        assertWithMessage("no backspace is emitted while selecting")
            .that(handler.holds).isEmpty()
    }

    @Test
    fun `a leftward short swipe on backspace selects to the left`() {
        val ptr = pointer(
            backspaceKey, KeyValue.getKeyByName("backspace"), Pointers.FLAG_P_DEFERRED_DOWN
        )
        ptr.lastX = 60f

        invokeLongPress(ptr)

        assertThat(ptr.hasFlagsAny(Pointers.FLAG_P_SELECTION_DELETE_MODE)).isTrue()
        assertThat(ptr.selectionDirection).isEqualTo(-1)
    }

    @Test
    fun `holding backspace without a swipe still key-repeats`() {
        val ptr = pointer(
            backspaceKey, KeyValue.getKeyByName("backspace"), Pointers.FLAG_P_DEFERRED_DOWN
        )
        ptr.lastX = 105f // 5 px — below the 24 px short-gesture threshold

        invokeLongPress(ptr)

        assertWithMessage("plain hold must stay ordinary key repeat, not selection mode")
            .that(ptr.hasFlagsAny(Pointers.FLAG_P_SELECTION_DELETE_MODE)).isFalse()
        assertThat(handler.holds.map { it.getKeyevent() }).containsExactly(KeyEvent.KEYCODE_DEL)
        assertThat(scheduled.single().second).isEqualTo(ptr.snap.longPressInterval)
    }

    @Test
    fun `selection-delete extends the selection with shifted arrows`() {
        val ptr = selectionDeletePointer(dx = 80f, dy = 0f)

        invokeSelectionDeleteRepeat(ptr)

        assertThat(handler.ups).hasSize(1)
        val (key, mods) = handler.ups.single()
        assertThat(key?.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertWithMessage("the arrow is sent WITH shift, which is what turns it into a selection")
            .that(mods.has(KeyValue.Modifier.SHIFT)).isTrue()
    }

    @Test
    fun `the vertical dead zone is wider than the horizontal one`() {
        // verticalDeadZone = hypotenuse * 0.7 * 40% = 56 px, vs. the 15 px horizontal dead zone.
        val shallow = selectionDeletePointer(dx = 0f, dy = 30f)
        invokeSelectionDeleteRepeat(shallow)
        assertWithMessage("30 px of vertical drift must not select whole lines")
            .that(handler.ups).isEmpty()

        setUp()
        val deep = selectionDeletePointer(dx = 0f, dy = 80f)
        invokeSelectionDeleteRepeat(deep)
        assertThat(handler.ups.single().first?.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_DPAD_DOWN)
    }

    @Test
    fun `selection-delete supports diagonal selection`() {
        val ptr = selectionDeletePointer(dx = 80f, dy = -80f)

        invokeSelectionDeleteRepeat(ptr)

        assertThat(handler.ups.map { it.first?.getKeyevent() })
            .containsExactly(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP).inOrder()
        for ((_, mods) in handler.ups) {
            assertThat(mods.has(KeyValue.Modifier.SHIFT)).isTrue()
        }
    }

    @Test
    fun `a repeat that is not in selection-delete mode does nothing`() {
        val ptr = pointer(backspaceKey, KeyValue.getKeyByName("backspace"), 0)
        ptr.lastX = 300f

        invokeSelectionDeleteRepeat(ptr)

        assertThat(handler.ups).isEmpty()
        assertThat(scheduled).isEmpty()
    }

    // ================================= v1.2.8 — shift state captured at the START of a swipe

    @Test
    fun `a latched shift is captured when the swipe begins`() {
        latchShift()
        every { config.snapshot } returns snapshot()
        handler.shiftLocked = false

        pointers.onTouchDown(50f, 60f, 0, letterKey)

        verify(exactly = 1) {
            recognizer.startSwipe(50f, 60f, letterKey, true, false)
        }
    }

    @Test
    fun `caps lock is captured when the swipe begins`() {
        every { config.snapshot } returns snapshot()
        handler.shiftLocked = true

        pointers.onTouchDown(50f, 60f, 0, letterKey)

        verify(exactly = 1) {
            recognizer.startSwipe(50f, 60f, letterKey, false, true)
        }
    }

    @Test
    fun `an unshifted swipe captures neither`() {
        every { config.snapshot } returns snapshot()
        handler.shiftLocked = false

        pointers.onTouchDown(50f, 60f, 0, letterKey)

        verify(exactly = 1) {
            recognizer.startSwipe(50f, 60f, letterKey, false, false)
        }
    }

    @Test
    fun `the capture happens at touch-down, before any movement is seen`() {
        latchShift()
        every { config.snapshot } returns snapshot()

        pointers.onTouchDown(50f, 60f, 0, letterKey)

        // v1.2.8: shift is read HERE, while autocap-after-period still holds it. Reading it
        // at swipe END would miss it, because committing the word clears the latch.
        verify(exactly = 1) { recognizer.startSwipe(50f, 60f, letterKey, true, false) }
        assertWithMessage("the captured state belongs to the gesture's first event")
            .that(ptrs.last().downX).isEqualTo(50f)
    }

    @Test
    fun `no swipe is started when swipe typing and short gestures are both off`() {
        every { config.snapshot } returns testConfigSnapshot(
            swipe_typing_enabled = false, short_gestures_enabled = false
        )

        pointers.onTouchDown(50f, 60f, 0, letterKey)

        verify(exactly = 0) { recognizer.startSwipe(any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------------ plumbing

    /** A pointer already in TrackPoint mode, with the finger [dx]/[dy] from the joystick centre. */
    private fun trackPointPointer(dx: Float, dy: Float): Pointers.Pointer {
        val ptr = pointer(
            navKey, KeyValue.getKeyByName("compose"), Pointers.FLAG_P_TRACKPOINT_MODE
        )
        ptr.keyCenterX = 100f
        ptr.keyCenterY = 100f
        ptr.lastX = 100f + dx
        ptr.lastY = 100f + dy
        return ptr
    }

    /** A pointer already in selection-delete mode, [dx]/[dy] from the activation centre. */
    private fun selectionDeletePointer(dx: Float, dy: Float): Pointers.Pointer {
        val ptr = pointer(
            backspaceKey, KeyValue.getKeyByName("backspace"),
            Pointers.FLAG_P_SELECTION_DELETE_MODE
        )
        ptr.keyCenterX = 100f
        ptr.keyCenterY = 100f
        ptr.lastX = 100f + dx
        ptr.lastY = 100f + dy
        return ptr
    }

    /** Put a latched Shift in the pointer list, as a real latch would. */
    private fun latchShift() {
        val shift = KeyValue.getKeyByName("shift")
        val latched = Pointers.Pointer(
            -1, KeyboardData.Key.EMPTY.withKeyValue(0, shift), shift, 0f, 0f, noMods(),
            Pointers.FLAG_P_LATCHABLE or Pointers.FLAG_P_LATCHED, snapshot()
        )
        ptrs.add(latched)
    }

    private fun invokeLongPress(ptr: Pointers.Pointer) = invokePrivate("handleLongPress", ptr)
    private fun invokeTrackPointRepeat(ptr: Pointers.Pointer) =
        invokePrivate("handleTrackPointRepeat", ptr)
    private fun invokeSelectionDeleteRepeat(ptr: Pointers.Pointer) =
        invokePrivate("handleSelectionDeleteRepeat", ptr)

    private fun invokePrivate(name: String, ptr: Pointers.Pointer) {
        val method = Pointers::class.java
            .getDeclaredMethod(name, Pointers.Pointer::class.java)
        method.isAccessible = true
        method.invoke(pointers, ptr)
    }

    /** Records everything Pointers reports back to the keyboard view. */
    private class FakeHandler(private val hypotenuse: Float) : Pointers.IPointerEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<Pair<KeyValue?, Pointers.Modifiers>>()
        val holds = mutableListOf<KeyValue>()
        val flagEvents = mutableListOf<HapticEvent?>()
        var shiftLocked = false

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { downs += k }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k to mods }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) { flagEvents += hapticEvent }
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) { holds += k }
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun isShiftLocked(): Boolean = shiftLocked
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(
            x: Float, y: Float, key: KeyboardData.Key, tolerance: Float
        ): Boolean = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = hypotenuse
        override fun getKeyWidth(key: KeyboardData.Key): Float = 120f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) {}
    }

    // -- sun.misc.Unsafe seams (same idiom as VibratorCompatTest / DirectBootManagerTest) --

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
