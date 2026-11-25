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
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
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
        setField(pointers, "_config", mockk<Config>(relaxed = true))
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

    // ------------------------------------------------------------------ harness

    /** Records what [Keyboard2View]'s `updateFlags` would draw at each redrawing callback. */
    private class RecordingHandler(private val mods: () -> Pointers.Modifiers) : Pointers.IPointerEventHandler {
        val ups = mutableListOf<KeyValue?>()
        val drawn = mutableListOf<Pointers.Modifiers>()

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { drawn += mods() }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k; drawn += this.mods() }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) { drawn += mods() }
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(x: Float, y: Float, key: KeyboardData.Key, tolerance: Float) = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 200f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 120f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) {}
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
