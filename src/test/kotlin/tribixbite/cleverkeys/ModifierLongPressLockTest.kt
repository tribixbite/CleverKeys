package tribixbite.cleverkeys

import android.os.Handler
import android.os.Message
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.lang.reflect.Field
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_FAKE
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_LATCHED
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_LOCKED
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.prefs.ConfigSnapshot

/**
 * A long press on a modifier locks it (caps lock for Shift), also when that modifier is already
 * latched. Maintainer device check 2026-11-23: at a sentence start, where auto-capitalisation
 * has latched Shift, a long press on Shift released it instead of locking it, so caps lock took
 * two long presses. The hold locked the pressing finger's pointer; its release then found the
 * latched Shift ([Pointers.onTouchUp] "already latched") and took both away. With "double tap
 * for caps lock" off, a Shift the user had tapped went the same way.
 *
 * Driven through the real [Pointers.onTouchDown], [Pointers.handleMessage] with the pointer's
 * long-press `what` (as the Handler delivers it) and [Pointers.onTouchUp]. Harness as in
 * [PointersSubkeyPopoverTest]: [Pointers] allocated without its constructor, collaborators set.
 */
class ModifierLongPressLockTest {

    private val objenesis = ObjenesisStd()
    private lateinit var pointers: Pointers
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var handler: RecordingHandler
    private lateinit var config: Config

    private val shift = KeyValue.getKeyByName("shift")
    private val ctrl = KeyValue.getKeyByName("ctrl")
    private val shiftKey = KeyboardData.Key.EMPTY.withKeyValue(0, shift)
    private val ctrlKey = KeyboardData.Key.EMPTY.withKeyValue(0, ctrl)

    /** The shipped defaults that matter here: double tap locks Shift, no word swipe or short swipes. */
    private fun snap(
        doubleTapLock: Boolean = true,
        swipeTyping: Boolean = false,
        shortGestures: Boolean = false,
    ): ConfigSnapshot = testConfigSnapshot(
        double_tap_lock_shift = doubleTapLock,
        swipe_typing_enabled = swipeTyping,
        short_gestures_enabled = shortGestures,
    )

    private fun useSnapshot(s: ConfigSnapshot) {
        every { config.snapshot } returns s
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        ptrs = ArrayList()
        handler = RecordingHandler()
        pointers = objenesis.newInstance(Pointers::class.java)
        config = mockk(relaxed = true)
        useSnapshot(snap())
        val recognizer = mockk<EnhancedSwipeGestureRecognizer>(relaxed = true)
        every { recognizer.isSwipeTyping() } returns false
        pointers.setField("_ptrs", ptrs)
        pointers.setField("_config", config)
        pointers.setField("_handler", handler)
        pointers.setField("_longpress_handler", mockk<Handler>(relaxed = true))
        pointers.setField("_swipeRecognizer", recognizer)
        pointers.setField("_gestureClassifier", GestureClassifier())
    }

    @After
    fun tearDown() = unmockkAll()

    // ------------------------------------------------------------------ drivers

    private fun press(key: KeyboardData.Key) = pointers.onTouchDown(50f, 50f, FINGER, key)

    /** The long-press timer of the finger's pointer fires, as the Handler would deliver it. */
    private fun holdPastTheLongPress() {
        val pressing = ptrs.single { it.pointerId == FINGER }
        pointers.handleMessage(message(pressing.timeoutWhat))
    }

    private fun release() = pointers.onTouchUp(FINGER)

    private fun tap(key: KeyboardData.Key) {
        press(key)
        release()
    }

    private fun longPress(key: KeyboardData.Key) {
        press(key)
        holdPastTheLongPress()
        release()
    }

    /** Auto-capitalisation at a sentence start (Keyboard2View.set_shift_state(true, false)). */
    private fun autocapLatchesShift() = pointers.set_fake_pointer_state(shiftKey, shift, latched = true, lock = false)

    private fun pointersFor(kv: KeyValue) = ptrs.filter { it.value == kv }

    private fun assertLockedByTheUser(kv: KeyValue) {
        val held = pointersFor(kv)
        assertWithMessage("one $kv pointer, not a lock beside a latch: ${held.map { "0x" + it.flags.toString(16) }}")
            .that(held).hasSize(1)
        val p = held.single()
        assertWithMessage("$kv latched and locked after the release")
            .that(p.flags and (FLAG_P_LATCHED or FLAG_P_LOCKED)).isEqualTo(FLAG_P_LATCHED or FLAG_P_LOCKED)
        assertWithMessage("the lock is the user's: auto-capitalisation must not take it back")
            .that(p.flags and FLAG_P_FAKE).isEqualTo(0)
        assertThat(p.pointerId).isEqualTo(-1)
        assertThat(pointers.getModifiers().has(kv.getModifier())).isTrue()
    }

    // ------------------------------------------------------------------ the fix

    @Test
    fun holdingShiftOverAutoCapitalisationsShiftLocksIt() {
        autocapLatchesShift()
        longPress(shiftKey)
        assertLockedByTheUser(shift)
    }

    @Test
    fun theLockShowsWhileTheFingerIsStillDown() {
        autocapLatchesShift()
        press(shiftKey)
        holdPastTheLongPress()
        // Keyboard2View.isShiftLocked and the Shift key's drawing read the first Shift pointer.
        assertWithMessage("Shift drawn locked during the hold, not still auto-capitalisation's latch")
            .that(pointers.getKeyFlags(shift) and FLAG_P_LOCKED).isEqualTo(FLAG_P_LOCKED)
        assertThat(handler.haptics).contains(HapticEvent.LONG_PRESS)
        release()
        assertLockedByTheUser(shift)
    }

    @Test
    fun holdingAShiftTheUserTappedLocksItWithDoubleTapLockOff() {
        useSnapshot(snap(doubleTapLock = false))
        tap(shiftKey)
        assertThat(pointersFor(shift).single().flags and FLAG_P_LATCHED).isEqualTo(FLAG_P_LATCHED)
        longPress(shiftKey)
        assertLockedByTheUser(shift)
    }

    @Test
    fun holdingALatchedCtrlLocksIt() {
        tap(ctrlKey)
        longPress(ctrlKey)
        assertLockedByTheUser(ctrl)
    }

    @Test
    fun autoCapitalisationLeavesTheLockAlone() {
        autocapLatchesShift()
        longPress(shiftKey)
        // The next letters make auto-capitalisation drop its Shift, and a new sentence latch it.
        pointers.set_fake_pointer_state(shiftKey, shift, latched = false, lock = false)
        assertLockedByTheUser(shift)
        pointers.set_fake_pointer_state(shiftKey, shift, latched = true, lock = false)
        assertLockedByTheUser(shift)
    }

    @Test
    fun withWordSwipesAndShortSwipesOnTheHoldStillLocks() {
        useSnapshot(snap(swipeTyping = true, shortGestures = true))
        autocapLatchesShift()
        longPress(shiftKey)
        assertLockedByTheUser(shift)
    }

    // ------------------------------------------------------------------ unchanged

    @Test
    fun holdingShiftWithNothingLatchedLocksIt() {
        longPress(shiftKey)
        assertLockedByTheUser(shift)
    }

    @Test
    fun aTapOnAutoCapitalisationsShiftStillReleasesIt() {
        autocapLatchesShift()
        tap(shiftKey)
        assertThat(pointersFor(shift)).isEmpty()
        assertThat(pointers.getModifiers().has(KeyValue.Modifier.SHIFT)).isFalse()
    }

    @Test
    fun aTapOnALockedShiftUnlocksIt() {
        longPress(shiftKey)
        tap(shiftKey)
        assertThat(pointersFor(shift)).isEmpty()
    }

    @Test
    fun doubleTappingShiftStillLocksIt() {
        tap(shiftKey)
        tap(shiftKey)
        assertLockedByTheUser(shift)
    }

    // ------------------------------------------------------------------ harness

    private class RecordingHandler : Pointers.IPointerEventHandler {
        val haptics = mutableListOf<HapticEvent>()
        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) {}
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) {}
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) { hapticEvent?.let { haptics += it } }
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(x: Float, y: Float, key: KeyboardData.Key, tolerance: Float) = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 180f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 100f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) {}
    }

    /** A [Message] with only `what` set; android.jar's constructor is a stub, so allocate raw. */
    private fun message(what: Int): Message =
        objenesis.newInstance(Message::class.java).also { Message::class.java.getField("what").setInt(it, what) }

    /** Sets a declared field anywhere up the class chain. */
    private fun Any.setField(name: String, value: Any?) {
        var type: Class<*>? = javaClass
        var field: Field? = null
        while (type != null && field == null) {
            field = type.declaredFields.firstOrNull { it.name == name }
            type = type.superclass
        }
        requireNotNull(field) { "no field $name on ${javaClass.name}" }.apply { isAccessible = true }.set(this, value)
    }

    private companion object {
        const val FINGER = 0
    }
}
