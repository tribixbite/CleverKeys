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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import tribixbite.cleverkeys.swipe.KeyLetter
import java.lang.reflect.Field

/**
 * Swipe typing may only START on a letter key (Seeker report, 2026-10-07).
 *
 * ## The bug
 *
 * A swipe-LEFT that started on Backspace and travelled across `m`/`n`/`b` inserted "mb".
 * Backspace is not a Char key, so it never qualified for swipe typing — but it IS a
 * short-gesture key, so [Pointers.onTouchMove] collected its path for the touch-up
 * short-swipe decision. The recognizer registers every LETTER the finger crosses (the
 * start key is skipped only because it is not alphabetic), so after two letters it
 * reported `isSwipeTyping()`, and the move-time latch checked only `swipe_typing_enabled`,
 * the recognizer and `hasLeftStartingKey` — never the key the gesture started on. The
 * latched FLAG_P_SWIPE_TYPING then sent the release straight to `onSwipeEnd`.
 *
 * ## The contract pinned here
 *
 * Every word-swipe route (move-time latch, touch-up SWIPE classification, word-candidate
 * subkey resolution, return-trip rescue) requires the gesture to start on a key whose
 * centre value is a single letter ([KeyLetter.startsWordSwipe] — the same predicate the
 * recognizer uses for registering keys). Backspace, Shift, Enter, Space, Ctrl, Fn,
 * punctuation and digits keep their own short-swipe/tap handling. A letter start still
 * latches exactly as before; a gesture that STARTS on a letter and later crosses the
 * spacebar (continuous multi-word swipe) is unaffected because the start key is a letter.
 *
 * Harness idiom: [PointersShortSwipeCustomOverrideTest] (Unsafe-allocated [Pointers],
 * injected collaborators, the REAL [Pointers.onTouchMove]/[Pointers.onTouchUp]).
 */
class PointersSwipeStartKeyTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer

    /** Long word-shaped path the mocked recognizer reports (well past half a key width). */
    private val longPath = listOf(PointF(400f, 100f), PointF(300f, 100f), PointF(200f, 100f), PointF(100f, 100f))

    private fun key(value: KeyValue): KeyboardData.Key = KeyboardData.Key.EMPTY.withKeyValue(0, value)

    private fun named(name: String): KeyboardData.Key = key(requireNotNull(KeyValue.getKeyByName(name)) { name })

    /** Every non-letter key class a swipe can begin on. */
    private val nonLetterStarts: List<Pair<String, KeyboardData.Key>> by lazy {
        listOf(
            "backspace" to named("backspace"),
            "delete" to named("delete"),
            "shift" to named("shift"),
            "enter" to named("enter"),
            "space" to named("space"),
            "ctrl" to named("ctrl"),
            "fn" to named("fn"),
            "tab" to named("tab"),
            "period" to key(KeyValue.makeCharKey('.')),
            "apostrophe" to key(KeyValue.makeCharKey('\'')),
            "digit" to key(KeyValue.makeCharKey('1')),
        )
    }

    private val letterM = key(KeyValue.makeCharKey('m'))

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
        // The recognizer has seen a full word path over letters (m, n, b): exactly what it
        // reports when a gesture that began on backspace crosses the bottom letter row.
        recognizer = mockk(relaxed = true)
        every { recognizer.getSwipePath() } returns longPath
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
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /** Down at (400,100), move 300 px left (past the 200 px key boundary), release. */
    private fun swipeLeftAcrossLetters(start: KeyboardData.Key): Pointers.Pointer {
        handler.reset()
        ptrs.clear()
        val value = requireNotNull(start.keys[0])
        val s = snap()
        val ptr = Pointers.Pointer(0, start, value, 400f, 100f, noMods(), pointers.pointer_flags_of_kv(value, s), s)
        ptrs.add(ptr)
        pointers.onTouchMove(250f, 100f, 0)
        pointers.onTouchMove(100f, 100f, 0)
        return ptr
    }

    @Test
    fun letterStart_stillLatchesAndCommitsAWord() {
        val ptr = swipeLeftAcrossLetters(letterM)
        assertTrue("a letter-start gesture must latch swipe typing", ptr.hasFlagsAny(Pointers.FLAG_P_SWIPE_TYPING))
        pointers.onTouchUp(0)
        assertEquals("exactly one word swipe", 1, handler.swipeEndCount)
    }

    @Test
    fun nonLetterStart_neverLatchesSwipeTypingOnMove() {
        for ((name, start) in nonLetterStarts) {
            val ptr = swipeLeftAcrossLetters(start)
            assertFalse(
                "$name: a gesture starting on a non-letter key must not latch swipe typing",
                ptr.hasFlagsAny(Pointers.FLAG_P_SWIPE_TYPING)
            )
        }
    }

    @Test
    fun nonLetterStart_neverReachesTheWordDecoderOnRelease() {
        for ((name, start) in nonLetterStarts) {
            swipeLeftAcrossLetters(start)
            pointers.onTouchUp(0)
            assertEquals("$name: release must not commit a swiped word", 0, handler.swipeEndCount)
        }
    }

    /**
     * The touch-up classifier route on its own (no move-time latch): a pointer already
     * outside its key with a long recorded path is classified SWIPE, and only a letter
     * start may forward that to the word decoder.
     */
    @Test
    fun touchUpSwipeClassification_requiresLetterStart() {
        for ((name, start) in nonLetterStarts + ("m" to letterM)) {
            handler.reset()
            ptrs.clear()
            val value = requireNotNull(start.keys[0])
            val s = snap()
            val ptr = Pointers.Pointer(0, start, value, 400f, 100f, noMods(), pointers.pointer_flags_of_kv(value, s), s)
            ptr.lastX = 100f
            ptr.hasLeftStartingKey = true
            ptrs.add(ptr)
            pointers.onTouchUp(0)
            val expected = if (start === letterM) 1 else 0
            assertEquals("$name: word decoder calls", expected, handler.swipeEndCount)
        }
    }

    @Test
    fun startPredicate_acceptsOnlySingleLetterCentres() {
        assertTrue(KeyLetter.startsWordSwipe(letterM))
        assertTrue(KeyLetter.startsWordSwipe(key(KeyValue.makeStringKey("é"))))
        assertFalse(KeyLetter.startsWordSwipe(null))
        for ((name, start) in nonLetterStarts) {
            assertFalse("$name must not start swipe typing", KeyLetter.startsWordSwipe(start))
        }
    }

    // ------------------------------------------------------------------ harness

    private class RecordingHandler : Pointers.IPointerEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<KeyValue?>()
        var swipeEndCount = 0

        fun reset() { downs.clear(); ups.clear(); swipeEndCount = 0 }

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { downs += k }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) {}
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
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
