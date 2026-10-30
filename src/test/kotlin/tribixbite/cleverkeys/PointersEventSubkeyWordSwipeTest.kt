package tribixbite.cleverkeys

import android.graphics.PointF
import android.os.Handler
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import java.lang.reflect.Field

/**
 * Event subkeys on LETTER keys must not hijack a word swipe (maintainer report 2026-10-30).
 *
 * ## The bug
 *
 * The default layout puts the `voice_typing` extra key in a free corner of a letter key (the
 * maintainer's `a`, south-east). [Pointers.onTouchMove] fired an Event subkey EAGERLY as soon
 * as the finger had moved `swipe_dist_px` toward it — before swipe typing could latch — so
 * every word swiped from `a` down-right ("acid", "ax…") became a voice-typing press. With no
 * system voice IME that press did nothing visible; a slower swipe let the long-press timer
 * turn it into `voice_typing_chooser`, whose dialog ("No voice typing app installed") opened
 * over the keyboard. The recognizer was never reset on that path, so the half-swiped trail
 * stayed drawn (screenshots 6/7: frozen trail, dialog).
 *
 * ## The contract pinned here
 *
 *  - A gesture that may be a word swipe (letter start, swipe typing on, not "short swipes
 *    only") never fires an Event subkey on MOVE: the release decides, with the same
 *    short/long boundary every character subkey uses.
 *  - A deliberate short flick toward that subkey still triggers it on release.
 *  - Where the eager path does fire (a non-letter key such as space), the recognizer is reset
 *    and the rest of the gesture collects no path, so no trail can outlive the gesture.
 *  - A first finger down drops any pointer whose release never arrived (a stale press would
 *    keep its key drawn pressed — the stuck key-press preview — and turn every later touch
 *    into a "second finger").
 *
 * Harness idiom: [PointersSwipeStartKeyTest] (Unsafe-allocated [Pointers], the REAL
 * onTouchMove/onTouchUp).
 */
class PointersEventSubkeyWordSwipeTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer

    private val voiceTyping = requireNotNull(KeyValue.getKeyByName("voice_typing"))
    private val switchForward = requireNotNull(KeyValue.getKeyByName("switch_forward"))

    /** `a` with the voice key south-east (sublabel index 4), as the extra-key placement puts it. */
    private val letterA = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeCharKey('a'))
        .withKeyValue(4, voiceTyping)

    /** Space with an Event subkey east (index 6): the eager path's intended use. */
    private val space = KeyboardData.Key.EMPTY
        .withKeyValue(0, requireNotNull(KeyValue.getKeyByName("space")))
        .withKeyValue(6, switchForward)

    private fun noMods(): Pointers.Modifiers = Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    private fun snap(): ConfigSnapshot = testConfigSnapshot(
        short_gestures_enabled = true,
        swipe_typing_enabled = true,
        short_gesture_min_distance = PercentOfKey(25),
        short_gesture_max_distance = PercentOfKey(100),
        swipe_dist_px = 100f
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        handler = RecordingHandler()
        recognizer = mockk(relaxed = true)
        every { recognizer.getSwipePath() } returns listOf(PointF(400f, 100f), PointF(550f, 250f), PointF(700f, 400f))
        every { recognizer.isSwipeTyping() } returns true
        every { recognizer.promoteWordCandidacy() } returns true

        ptrs = ArrayList()
        pointers = allocate(Pointers::class.java)
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
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun press(key: KeyboardData.Key, s: ConfigSnapshot = snap()): Pointers.Pointer {
        val value = requireNotNull(key.keys[0])
        val ptr = Pointers.Pointer(0, key, value, 400f, 100f, noMods(), pointers.pointer_flags_of_kv(value, s), s)
        ptrs.add(ptr)
        return ptr
    }

    /** "acid": from `a` down-right toward its voice corner and on across the letters. */
    @Test
    fun wordSwipeFromLetter_towardItsVoiceCorner_staysAWord() {
        val ptr = press(letterA)
        pointers.onTouchMove(550f, 250f, 0)
        pointers.onTouchMove(700f, 400f, 0)
        assertFalse("no voice key fired mid-swipe", voiceTyping in handler.downs)
        assertTrue("the gesture latched swipe typing", ptr.hasFlagsAny(Pointers.FLAG_P_SWIPE_TYPING))
        pointers.onTouchUp(0)
        assertFalse("no voice key on release", voiceTyping in handler.ups)
        assertEquals("exactly one word", 1, handler.swipeEndCount)
    }

    /** A deliberate short flick into the corner still presses the voice key, on release. */
    @Test
    fun shortFlickOnLetter_towardItsVoiceCorner_stillPressesIt() {
        every { recognizer.isSwipeTyping() } returns false
        every { recognizer.promoteWordCandidacy() } returns false
        press(letterA)
        pointers.onTouchMove(480f, 180f, 0) // 113 px: past the 50 px minimum, inside the 200 px key
        assertFalse("nothing fires before the release decides", voiceTyping in handler.downs)
        pointers.onTouchUp(0)
        assertEquals(listOf<KeyValue?>(voiceTyping), handler.ups.filter { it == voiceTyping })
        assertEquals(0, handler.swipeEndCount)
    }

    /** With "short swipes only" there is no word swipe to protect: the eager path is kept. */
    @Test
    fun shortSwipesOnly_keepsTheImmediateEventSubkey() {
        val s = testConfigSnapshot(
            short_gestures_enabled = true,
            swipe_typing_enabled = true,
            short_swipes_only = true,
            short_gesture_min_distance = PercentOfKey(25),
            short_gesture_max_distance = PercentOfKey(100),
            swipe_dist_px = 100f
        )
        press(letterA, s)
        pointers.onTouchMove(550f, 250f, 0)
        assertTrue(voiceTyping in handler.downs)
    }

    /** The eager path (space → switch_forward) leaves no swipe trail behind. */
    @Test
    fun eagerEventSubkey_resetsTheRecognizer_andStopsCollectingThePath() {
        press(space)
        pointers.onTouchMove(550f, 100f, 0) // east, toward switch_forward
        assertTrue("the space-bar event still fires at once", switchForward in handler.downs)
        verify { recognizer.reset() }
        val movesBefore = handler.swipeMoves
        pointers.onTouchMove(700f, 100f, 0)
        assertEquals("no path after the gesture became an event", movesBefore, handler.swipeMoves)
        pointers.onTouchUp(0)
        assertEquals(listOf<KeyValue?>(switchForward), handler.ups)
        assertEquals(0, handler.swipeEndCount)
    }

    /** A first finger down drops a press whose release never arrived, not latched modifiers. */
    @Test
    fun firstTouchDown_dropsStalePresses_keepsLatchedModifiers() {
        val stale = press(letterA)
        val shift = requireNotNull(KeyValue.getKeyByName("shift"))
        val s = snap()
        val latched = Pointers.Pointer(
            -1, KeyboardData.Key.EMPTY.withKeyValue(0, shift), shift, 0f, 0f, noMods(),
            pointers.pointer_flags_of_kv(shift, s) or Pointers.FLAG_P_LATCHED, s
        )
        ptrs.add(latched)
        assertTrue(pointers.dropStalePointers())
        assertFalse("the stale press is gone", stale in ptrs)
        assertTrue("the latched Shift stays", latched in ptrs)
        assertFalse("nothing left to drop", pointers.dropStalePointers())
    }

    // ------------------------------------------------------------------ harness

    private class RecordingHandler : Pointers.IPointerEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<KeyValue?>()
        var swipeEndCount = 0
        var swipeMoves = 0

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { downs += k }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) {}
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) { swipeMoves++ }
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) { swipeEndCount++ }
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(
            x: Float, y: Float, key: KeyboardData.Key, tolerance: Float
        ): Boolean = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 200f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 120f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) {}
    }

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
