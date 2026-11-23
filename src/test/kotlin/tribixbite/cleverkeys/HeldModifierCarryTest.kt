package tribixbite.cleverkeys

import android.os.Handler
import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.lang.reflect.Field
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_DOUBLE_TAP_LOCK
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_FAKE
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_LATCHABLE
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_LATCHED
import tribixbite.cleverkeys.Pointers.Companion.FLAG_P_LOCKED

/**
 * Maintainer report 2026-11-22: a latched or locked Shift or Ctrl was gone after a rotation, and
 * (todo item 42) auto-capitalisation's Shift was gone after a trip to the number pane.
 * [Keyboard2View.reset] — every layout switch, every configuration refresh, a rotation's input
 * restart — drops every modifier.
 *
 * Pinned here, mock tier:
 *  - [Pointers.heldModifiers] reports exactly what is latched or locked (not a key still under a
 *    finger), and [Pointers.restoreHeldModifiers] puts it back on the NEW layout's keys with the
 *    same state: a user's latch stays the user's (a tap on it behaves as before), a lock stays a
 *    lock, and a modifier the new layout has no key for is dropped (it could never be released).
 *  - [Keyboard2View.restoreHeldModifiers] finds those keys on the layout it shows now and redraws
 *    once; a finger on the keyboard tells the service the user is typing (which ends a rotation's
 *    carry); a theme preview never does.
 *
 * Harness as in [PointersSubkeyPopoverTest] (Pointers allocated without its constructor) and
 * [Keyboard2ViewContinuousLifecycleTest] (a real view allocated without its constructor, spied).
 */
class HeldModifierCarryTest {

    private val objenesis = ObjenesisStd()
    private lateinit var pointers: Pointers
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var config: Config

    private val shift = KeyValue.getKeyByName("shift")
    private val ctrl = KeyValue.getKeyByName("ctrl")
    private val letterA = KeyValue.makeCharKey('a')

    /** The keys of the layout shown before the rotation, and of the one built after it. */
    private val shiftBefore = KeyboardData.Key.EMPTY.withKeyValue(0, shift)
    private val ctrlBefore = KeyboardData.Key.EMPTY.withKeyValue(0, ctrl)
    private val aBefore = KeyboardData.Key.EMPTY.withKeyValue(0, letterA)
    private val shiftAfter = KeyboardData.Key.EMPTY.withKeyValue(0, shift)
    private val ctrlAfter = KeyboardData.Key.EMPTY.withKeyValue(0, ctrl)
    private val keysAfter = mapOf(shift to shiftAfter, ctrl to ctrlAfter)

    private val noMods = Pointers.Modifiers.EMPTY
    private val snap = testConfigSnapshot(double_tap_lock_shift = true)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }

        ptrs = ArrayList()
        pointers = objenesis.newInstance(Pointers::class.java)
        config = mockk(relaxed = true)
        every { config.snapshot } returns snap
        pointers.setField("_ptrs", ptrs)
        pointers.setField("_config", config)
        pointers.setField("_handler", mockk<Pointers.IPointerEventHandler>(relaxed = true))
        pointers.setField("_longpress_handler", mockk<Handler>(relaxed = true))
    }

    @After
    fun tearDown() = unmockkAll()

    /** The state Pointers.onTouchUp leaves for a modifier the user tapped (pointerId −1). */
    private fun userLatched(key: KeyboardData.Key, kv: KeyValue, locked: Boolean = false) {
        var flags = pointers.pointer_flags_of_kv(kv, snap) or FLAG_P_LATCHED
        if (locked) flags = (flags and FLAG_P_DOUBLE_TAP_LOCK.inv()) or FLAG_P_LOCKED
        ptrs.add(Pointers.Pointer(-1, key, kv, 0f, 0f, noMods, flags, snap))
    }

    private fun pointerFor(kv: KeyValue): Pointers.Pointer? = ptrs.singleOrNull { it.value == kv }

    private fun carryAcrossAReset(): Int {
        val held = pointers.heldModifiers()
        pointers.clear() // what Keyboard2View.reset does
        return pointers.restoreHeldModifiers(held) { keysAfter[it] }
    }

    // ------------------------------------------------------------------ Pointers

    @Test
    fun aLatchedCtrlAndACapsLockComeBackOnTheNewLayoutsKeys() {
        userLatched(ctrlBefore, ctrl)
        userLatched(shiftBefore, shift, locked = true)

        assertThat(carryAcrossAReset()).isEqualTo(2)

        val mods = pointers.getModifiers()
        assertThat(mods.has(KeyValue.Modifier.CTRL)).isTrue()
        assertThat(mods.has(KeyValue.Modifier.SHIFT)).isTrue()
        assertWithMessage("bound to the new layout's keys, so the keys draw latched and take taps")
            .that(pointerFor(ctrl)!!.key).isSameInstanceAs(ctrlAfter)
        assertThat(pointerFor(shift)!!.key).isSameInstanceAs(shiftAfter)
        assertThat(ptrs.map { it.pointerId }).containsExactly(-1, -1)
    }

    @Test
    fun aUsersLatchStaysTheUsersAndALockStaysALock() {
        userLatched(ctrlBefore, ctrl)
        userLatched(shiftBefore, shift, locked = true)
        carryAcrossAReset()

        val ctrlPtr = pointerFor(ctrl)!!
        assertWithMessage("not auto-capitalisation's: a tap on it toggles as the user's own latch")
            .that(ctrlPtr.flags and FLAG_P_FAKE).isEqualTo(0)
        assertThat(ctrlPtr.flags and (FLAG_P_LATCHED or FLAG_P_LATCHABLE))
            .isEqualTo(FLAG_P_LATCHED or FLAG_P_LATCHABLE)
        assertThat(ctrlPtr.flags and FLAG_P_LOCKED).isEqualTo(0)

        val shiftPtr = pointerFor(shift)!!
        assertThat(shiftPtr.flags and FLAG_P_LOCKED).isEqualTo(FLAG_P_LOCKED)
        assertWithMessage("a locked Shift must unlock on the next tap, not lock again")
            .that(shiftPtr.flags and FLAG_P_DOUBLE_TAP_LOCK).isEqualTo(0)
    }

    @Test
    fun aCapsLockFromTheCapsLockKeyStaysAsItWas() {
        // KeyboardReceiver's CAPS_LOCK event sets a fake LOCKED Shift (set_shift_state(true, true)).
        pointers.add_fake_pointer(shiftBefore, shift, locked = true)
        carryAcrossAReset()
        val shiftPtr = pointerFor(shift)!!
        assertThat(shiftPtr.flags and (FLAG_P_FAKE or FLAG_P_LOCKED or FLAG_P_LATCHED))
            .isEqualTo(FLAG_P_FAKE or FLAG_P_LOCKED or FLAG_P_LATCHED)
    }

    @Test
    fun aKeyStillUnderAFingerIsNotHeld() {
        ptrs.add(Pointers.Pointer(0, aBefore, letterA, 10f, 10f, noMods, 0, snap))
        // A modifier pressed but not yet released is not latched yet.
        ptrs.add(Pointers.Pointer(1, ctrlBefore, ctrl, 10f, 10f, noMods, FLAG_P_LATCHABLE, snap))
        assertThat(pointers.heldModifiers()).isEmpty()
    }

    @Test
    fun aModifierTheNewLayoutHasNoKeyForIsDropped() {
        userLatched(ctrlBefore, ctrl)
        userLatched(shiftBefore, shift)
        val held = pointers.heldModifiers()
        pointers.clear()
        val restored = pointers.restoreHeldModifiers(held) { if (it == shift) shiftAfter else null }
        assertThat(restored).isEqualTo(1)
        assertThat(pointers.getModifiers().has(KeyValue.Modifier.CTRL)).isFalse()
        assertThat(pointers.getModifiers().has(KeyValue.Modifier.SHIFT)).isTrue()
    }

    @Test
    fun theUsersShiftReplacesAnAutocapShiftAlreadyBack() {
        userLatched(shiftBefore, shift, locked = true)
        val held = pointers.heldModifiers()
        pointers.clear()
        // Auto-capitalisation ran first after the reset and latched its own Shift.
        pointers.add_fake_pointer(shiftAfter, shift, locked = false)

        assertThat(pointers.restoreHeldModifiers(held) { keysAfter[it] }).isEqualTo(1)

        val shifts = ptrs.filter { it.value == shift }
        assertThat(shifts).hasSize(1)
        assertThat(shifts.single().flags and (FLAG_P_FAKE or FLAG_P_LOCKED)).isEqualTo(FLAG_P_LOCKED)
    }

    @Test
    fun aModifierStillHeldIsNotDoubled() {
        userLatched(ctrlBefore, ctrl)
        val held = pointers.heldModifiers()
        pointers.clear()
        userLatched(ctrlAfter, ctrl)
        assertThat(pointers.restoreHeldModifiers(held) { keysAfter[it] }).isEqualTo(0)
        assertThat(ptrs.count { it.value == ctrl }).isEqualTo(1)
    }

    // ------------------------------------------------------------------ Keyboard2View

    private fun view(service: CleverKeysService?, preview: Boolean = false): Keyboard2View {
        val view = spyk(objenesis.newInstance(Keyboard2View::class.java))
        every { view.invalidate() } just runs
        every { view.requestLayout() } just runs
        every { view.cancelContinuousSwipe() } just runs
        every { view.onPointerFlagsChanged(any()) } just runs
        every { view.background } returns null // android.jar stub otherwise (onTouch reads it)
        view.setField("_pointers", pointers)
        view.setField("_keyboard2", service)
        view.setField("_previewMode", preview)
        return view
    }

    @Test
    fun aFingerOnTheKeyboardTellsTheServiceTheUserIsTyping() {
        // Ends the rotation's carry (RotationModifierCarry.onUserInput): a later restart must not
        // bring back a Ctrl the user has used since.
        val service = mockk<CleverKeysService>(relaxed = true)
        val down = mockk<android.view.MotionEvent>(relaxed = true)
        every { down.actionMasked } returns android.view.MotionEvent.ACTION_DOWN
        val view = view(service)
        view.onTouch(view, down)
        verify(exactly = 1) { service.onKeyboardTouched() }
    }

    @Test
    fun aThemePreviewTellsNobody() {
        val service = mockk<CleverKeysService>(relaxed = true)
        val down = mockk<android.view.MotionEvent>(relaxed = true)
        every { down.actionMasked } returns android.view.MotionEvent.ACTION_DOWN
        val view = view(service, preview = true)
        view.onTouch(view, down)
        verify(exactly = 0) { service.onKeyboardTouched() }
    }

    @Test
    fun theViewPutsHeldModifiersBackOnItsCurrentLayoutAndRedrawsOnce() {
        val layout = KeyboardData.load_string_exn(
            """<keyboard bottom_row="false"><row><key c="shift"/><key c="a"/><key c="ctrl"/></row></keyboard>"""
        )
        userLatched(ctrlBefore, ctrl)
        userLatched(shiftBefore, shift, locked = true)
        val held = pointers.heldModifiers()
        pointers.clear()
        val view = view(mockk(relaxed = true))
        view.setField("_keyboard", layout)

        view.restoreHeldModifiers(held)

        assertThat(pointers.getModifiers().has(KeyValue.Modifier.CTRL)).isTrue()
        assertThat(pointerFor(ctrl)!!.key).isSameInstanceAs(layout.findKeyWithValue(ctrl))
        assertThat(pointerFor(shift)!!.key).isSameInstanceAs(layout.findKeyWithValue(shift))
        verify(exactly = 1) { view.onPointerFlagsChanged(null) }
    }

    @Test
    fun theViewDoesNotRedrawWhenNothingCameBack() {
        val view = view(mockk(relaxed = true))
        view.setField("_keyboard", KeyboardData.load_string_exn(
            """<keyboard bottom_row="false"><row><key c="a"/></row></keyboard>"""
        ))
        userLatched(ctrlBefore, ctrl)
        val held = pointers.heldModifiers()
        pointers.clear()
        view.restoreHeldModifiers(held)
        assertThat(ptrs).isEmpty()
        verify(exactly = 0) { view.onPointerFlagsChanged(any()) }
    }

    /** Sets a declared field anywhere up the class chain (a spy is a subclass of the view). */
    private fun Any.setField(name: String, value: Any?) {
        var type: Class<*>? = javaClass
        var field: Field? = null
        while (type != null && field == null) {
            field = type.declaredFields.firstOrNull { it.name == name }
            type = type.superclass
        }
        requireNotNull(field) { "no field $name on ${javaClass.name}" }.apply { isAccessible = true }.set(this, value)
    }
}
