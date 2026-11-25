package tribixbite.cleverkeys

import android.graphics.PointF
import android.os.Handler
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import java.lang.reflect.Field

/**
 * Pixel 2026-11-24 (release r7): in an empty field (auto-capitalisation's Shift latched), a swipe
 * up on Fn opened the GIF pane and left every key drawn in Fn's layer (€ for e, ₹ for r) though
 * nothing was typed with Fn; the next key press redrew them. The subkey's release ran
 * [Pointers.clearLatched], which removed the latched Shift, took "latchable" off the still-pressed
 * Fn (so it counted as a held modifier) and redrew; the Fn pointer was removed after that with
 * no redraw. Without a latched Shift there was no redraw at that point, so the labels stayed right.
 *
 * The view's last word after a subkey swipe is drawn from the pointers that remain.
 *
 * Harness as [PointersSwipeStartKeyTest]; the handler snapshots [Pointers.getModifiers] at every
 * callback that redraws in [Keyboard2View] (`updateFlags`), so the last snapshot is what is drawn.
 */
class PointersSubkeySwipeRedrawTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var ptrs: ArrayList<Pointers.Pointer>

    private val gif = KeyValue.getKeyByName("switch_gif")
    private val fnKey = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("fn"))
        .withKeyValue(7, gif) // N, as on the bottom row
    private val shiftKey = KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.getKeyByName("shift"))

    private fun noMods(): Pointers.Modifiers = Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    private fun snap(): ConfigSnapshot = testConfigSnapshot(
        short_gestures_enabled = true,
        swipe_typing_enabled = true,
        short_gesture_min_distance = PercentOfKey(25),
        short_gesture_max_distance = PercentOfKey(100),
        swipe_dist_px = 100f,
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        val recognizer = mockk<EnhancedSwipeGestureRecognizer>(relaxed = true)
        // A short upward flick on one key: no word.
        every { recognizer.getSwipePath() } returns listOf(PointF(400f, 300f), PointF(400f, 262f), PointF(400f, 224f))
        every { recognizer.isSwipeTyping() } returns false
        every { recognizer.promoteWordCandidacy() } returns false

        ptrs = ArrayList()
        pointers = allocate(Pointers::class.java)
        handler = RecordingHandler { pointers.getModifiers() }
        setField(pointers, "_handler", handler)
        // Auto-capitalisation's fake Shift captures the configuration current when it is added.
        val config = mockk<Config>(relaxed = true)
        every { config.snapshot } returns snap()
        setField(pointers, "_config", config)
        setField(pointers, "_longpress_handler", mockk<Handler>(relaxed = true))
        setField(pointers, "_ptrs", ptrs)
        setField(pointers, "_swipeRecognizer", recognizer)
        setField(pointers, "_gestureClassifier", GestureClassifier())
        val noMappings = mockk<ShortSwipeCustomizationManager>(relaxed = true)
        every { noMappings.getMapping(any(), any()) } returns null
        setField(pointers, "_customSwipeManager", noMappings)
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    /** Swipe up on Fn with [shiftLatched] or not; returns the modifiers last drawn. */
    private fun swipeFnUp(shiftLatched: Boolean): Pointers.Modifiers {
        val s = snap()
        if (shiftLatched) {
            val shift = requireNotNull(shiftKey.keys[0])
            ptrs.add(Pointers.Pointer(-1, shiftKey, shift, 0f, 0f, noMods(),
                pointers.pointer_flags_of_kv(shift, s) or Pointers.FLAG_P_LATCHED, s))
        }
        val fn = requireNotNull(fnKey.keys[0])
        ptrs.add(Pointers.Pointer(0, fnKey, fn, 400f, 300f, noMods(), pointers.pointer_flags_of_kv(fn, s), s))
        pointers.onTouchMove(400f, 262f, 0)
        pointers.onTouchMove(400f, 224f, 0)
        pointers.onTouchUp(0)
        assertTrue("the swipe emitted the GIF subkey", handler.ups.contains(gif))
        return handler.drawn.last()
    }

    @Test
    fun withAutocapsShiftLatched_theKeysAreDrawnWithoutFnAfterTheSwipe() {
        val drawn = swipeFnUp(shiftLatched = true)

        assertEquals("no modifier is drawn once Fn is lifted (Shift went with the subkey)", 0, drawn.size())
        assertTrue(ptrs.isEmpty())
    }

    @Test
    fun withNothingLatched_theKeysAreDrawnWithoutFn() {
        assertEquals(0, swipeFnUp(shiftLatched = false).size())
    }

    // ------------------------------------------- a subkey whose action latches Shift again

    /*
     * Saga 2026-11-25 (release r8): in an empty field, a swipe up on space to the next layout gave
     * lowercase letters. The layout switch asks auto-capitalisation again
     * ([KeyboardReceiver.showTextLayout]), which latched Shift while the subkey was being emitted;
     * the release then cleared the latched modifiers, that new Shift with them. A tap spends the
     * one-shot modifiers before its key acts ([Pointers.onTouchUp]), so its action's Shift stays.
     */

    private val switchForward = KeyValue.getKeyByName("switch_forward")
    private val spaceKey = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("space"))
        .withKeyValue(7, switchForward) // N, as on the bottom row with two or more layouts

    /** What the service does when the editor asks for a capital: latch Shift as a fake pointer. */
    private fun autocapLatchesShift() =
        pointers.set_fake_pointer_state(shiftKey, requireNotNull(shiftKey.keys[0]), latched = true, lock = false)

    /** Swipe up on space; returns the modifiers last drawn. */
    private fun swipeSpaceUp(shiftLatchedBefore: Boolean): Pointers.Modifiers {
        val s = snap()
        if (shiftLatchedBefore) autocapLatchesShift()
        val space = requireNotNull(spaceKey.keys[0])
        ptrs.add(Pointers.Pointer(0, spaceKey, space, 400f, 300f, noMods(), pointers.pointer_flags_of_kv(space, s), s))
        pointers.onTouchMove(400f, 262f, 0)
        pointers.onTouchMove(400f, 224f, 0)
        pointers.onTouchUp(0)
        return handler.drawn.last()
    }

    private fun Pointers.Modifiers.hasShift() = has(KeyValue.Modifier.SHIFT)

    @Test
    fun aLayoutSwitchBySwipe_keepsTheShiftAutoCapitalisationLatchedForIt() {
        handler.onUp = { if (it == switchForward) autocapLatchesShift() }

        val drawn = swipeSpaceUp(shiftLatchedBefore = false)

        assertTrue("the swipe emitted the layout switch", handler.ups.contains(switchForward))
        assertTrue("Shift stays latched after the switch", pointers.getModifiers().hasShift())
        assertTrue("and the keys are drawn shifted", drawn.hasShift())
    }

    @Test
    fun aLayoutSwitchBySwipe_atASentenceStart_endsWithShiftLatched() {
        // The Saga case: the empty field's Shift was latched before the swipe, and the switch
        // latched it again for the new layout.
        handler.onUp = { if (it == switchForward) autocapLatchesShift() }

        val drawn = swipeSpaceUp(shiftLatchedBefore = true)

        assertTrue(pointers.getModifiers().hasShift())
        assertTrue(drawn.hasShift())
    }

    @Test
    fun aSubkeyWhoseActionLatchesNothing_stillSpendsTheLatchedShift() {
        val drawn = swipeSpaceUp(shiftLatchedBefore = true)

        assertTrue(handler.ups.contains(switchForward))
        assertEquals("the one-shot Shift went with the subkey", 0, pointers.getModifiers().size())
        assertEquals(0, drawn.size())
    }

    @Test
    fun aCustomShortSwipe_keepsTheShiftItsCommandLatched() {
        // A user's own mapping to the next layout runs through onCustomShortSwipe.
        val mapping = ShortSwipeMapping("space", SwipeDirection.N, "⇥", ActionType.COMMAND, "switch_forward")
        val manager = mockk<ShortSwipeCustomizationManager>(relaxed = true)
        every { manager.getMapping(any(), any()) } returns null
        every { manager.getMapping(any(), SwipeDirection.N) } returns mapping
        setField(pointers, "_customSwipeManager", manager)
        handler.onUp = { if (it == switchForward) autocapLatchesShift() }
        handler.onCustom = { if (it == mapping) autocapLatchesShift() }

        val drawn = swipeSpaceUp(shiftLatchedBefore = true)

        assertTrue(pointers.getModifiers().hasShift())
        assertTrue(drawn.hasShift())
    }

    // ------------------------------------------------------------------ harness

    /** Records what [Keyboard2View]'s `updateFlags` would draw at each redrawing callback. */
    private class RecordingHandler(private val mods: () -> Pointers.Modifiers) : Pointers.IPointerEventHandler {
        val ups = mutableListOf<KeyValue?>()
        val drawn = mutableListOf<Pointers.Modifiers>()
        /** The key's action, as the service would run it at this callback. */
        var onUp: (KeyValue?) -> Unit = {}
        var onCustom: (ShortSwipeMapping) -> Unit = {}

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { drawn += mods() }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k; onUp(k); drawn += this.mods() }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) { drawn += mods() }
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(x: Float, y: Float, key: KeyboardData.Key, tolerance: Float) = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 200f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 120f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) { onCustom(mapping); drawn += mods() }
    }

    private fun unsafe(): Any {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        f.isAccessible = true
        return f.get(null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val u = unsafe()
        return u.javaClass.getMethod("allocateInstance", Class::class.java).invoke(u, type) as T
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var cls: Class<*>? = target.javaClass
        var field: Field? = null
        while (cls != null && field == null) {
            field = cls.declaredFields.firstOrNull { it.name == name }
            cls = cls.superclass
        }
        requireNotNull(field) { "field $name" }.apply { isAccessible = true }.set(target, value)
    }
}
