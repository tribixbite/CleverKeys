package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import java.io.File
import java.lang.reflect.Field

/**
 * "Short swipes only" mode ([ConfigSnapshot.short_swipes_only]) — GitHub #147 and #87.
 *
 * Both reporters could not hit the band between the short-gesture minimum and the short/long
 * boundary (`short_gesture_max_distance`) reliably: past the boundary a gesture became a word
 * swipe (#147) or, with swipe typing off, a tap of the starting key (#87). With the mode ON,
 * every gesture of at least the minimum distance resolves to the subkey in its direction,
 * however long it is, and never to a word swipe or a tap of the starting key. With it OFF the
 * boundary behaves exactly as before ([PointersShortSwipeCustomOverrideTest]'s #83 pin).
 *
 * Geometry (see [RecordingHandler.getKeyHypotenuse]): key diagonal 200 px, so
 * min = min(25% × 200, 100 × 0.8) = 50 px and max = 100% × 200 = 200 px. "Long" gestures
 * below are 250 px with `hasLeftStartingKey` latched, as the move path does past 200 px.
 *
 * Harness: the same `sun.misc.Unsafe` idiom as [PointersShortSwipeCustomOverrideTest] — the
 * REAL [Pointers.onTouchUp] is driven with a hand-built [Pointers.Pointer].
 */
class PointersShortSwipesOnlyTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var manager: ShortSwipeCustomizationManager
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer
    private lateinit var tmpDir: File

    /** `a` with a single e (index 6) subkey "1". */
    private val keyA = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeCharKey('a'))
        .withKeyValue(6, KeyValue.makeStringKey("1"))

    /** `q` with a nw (index 1) subkey "~". */
    private val keyQ = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeCharKey('q'))
        .withKeyValue(1, KeyValue.makeStringKey("~"))

    /** `z` with no subkey in any direction. */
    private val keyZ = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeCharKey('z'))

    private fun noMods(): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    /**
     * [shortSwipesOnly] mirrors what `Config.refresh` publishes: the mode forces
     * swipe_typing_enabled off, unless a test deliberately builds the unfolded combination.
     * `tap_duration_threshold = -1` makes every long gesture classify as SWIPE (any elapsed
     * time exceeds it), so a test can tell "the classifier said SWIPE but the mode kept it a
     * short swipe" from "the classifier said TAP".
     */
    private fun snap(
        shortSwipesOnly: Boolean,
        swipeTyping: Boolean = !shortSwipesOnly,
    ): ConfigSnapshot = testConfigSnapshot(
        short_gestures_enabled = true,
        short_swipes_only = shortSwipesOnly,
        swipe_typing_enabled = swipeTyping,
        short_gesture_min_distance = PercentOfKey(25),
        short_gesture_max_distance = PercentOfKey(100),
        swipe_dist_px = 100f,
        tap_duration_threshold = -1L,
    )

    @Before
    fun setUp() {
        // Debug unit-test builds compile ENABLE_VERBOSE_LOGGING as true; android.util.Log's
        // android.jar stub throws.
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        // Termux: /tmp is absent and java.io.tmpdir may point at it; take the first writable root.
        val tmpRoot = sequenceOf(
            System.getProperty("java.io.tmpdir"),
            System.getenv("TMPDIR"),
            "build/tmp"
        ).filterNotNull().map(::File).first { it.isDirectory && it.canWrite() || it.mkdirs() }
        tmpDir = File(tmpRoot, "short-swipes-only-${System.nanoTime()}").apply { mkdirs() }
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tmpDir
        manager = ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)

        handler = RecordingHandler()
        // A word-shaped recognizer: it reports a multi-key swipe and a word candidate, so any
        // route that COULD become a word swipe will.
        recognizer = mockk(relaxed = true)
        every { recognizer.getSwipePath() } returns emptyList()
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
        setField(pointers, "_customSwipeManager", manager)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        tmpDir.deleteRecursively()
    }

    /** Release a gesture whose displacement from touch-down is (dx, dy). */
    private fun release(
        key: KeyboardData.Key,
        dx: Float,
        dy: Float,
        snap: ConfigSnapshot,
        hasLeftStartingKey: Boolean,
    ) {
        val ptr = Pointers.Pointer(0, key, key.keys[0], 100f, 100f, noMods(), 0, snap)
        ptr.lastX = 100f + dx
        ptr.lastY = 100f + dy
        ptr.hasLeftStartingKey = hasLeftStartingKey
        ptrs.add(ptr)
        pointers.onTouchUp(0)
    }

    private fun typed(): List<String?> = handler.ups.map { it?.getString() }

    // =========================================================================
    // ON — a long displacement resolves to the subkey
    // =========================================================================

    /** #87: swipe typing off, 250 px east on `a` types its e subkey, not `a`. */
    @Test
    fun on_longDisplacement_typesTheDirectionsSubkey_notATapOfTheStartKey() {
        release(keyA, dx = 250f, dy = 0f, snap = snap(shortSwipesOnly = true), hasLeftStartingKey = true)

        assertEquals(listOf("1"), typed())
        assertEquals("no word swipe may end", 0, handler.swipeEndCount)
    }

    /**
     * #147: even a snapshot that still says swipe typing is ON (the classifier returns SWIPE
     * and the recognizer reports a word candidate) never routes to the word decoder while the
     * mode is on — the long east gesture is the e subkey.
     */
    @Test
    fun on_wordShapedLongGesture_isNeverAWordSwipe() {
        release(
            keyA, dx = 250f, dy = 0f,
            snap = snap(shortSwipesOnly = true, swipeTyping = true), hasLeftStartingKey = true
        )

        assertEquals("the word decoder must not run", 0, handler.swipeEndCount)
        assertEquals(listOf("1"), typed())
    }

    /** A long gesture still honours a user custom mapping for its direction. */
    @Test
    fun on_longDisplacement_firesTheCustomMappingForItsDirection() {
        runBlocking {
            manager.importFromMappings(
                listOf(ShortSwipeMapping.textInput("q", SwipeDirection.NW, "!", "!")), merge = true
            )
        }

        release(keyQ, dx = -200f, dy = -200f, snap = snap(shortSwipesOnly = true), hasLeftStartingKey = true)

        assertEquals("the custom NW mapping wins over the default and over a tap", listOf("!"), typed())
    }

    /**
     * A long swipe toward a direction where the key has nothing types nothing: typing the
     * starting key there is the "long swipe becomes a tap" behaviour the mode removes.
     */
    @Test
    fun on_longSwipeTowardAnEmptyDirection_typesNothing() {
        release(keyA, dx = -250f, dy = 0f, snap = snap(shortSwipesOnly = true), hasLeftStartingKey = true)

        assertTrue("nothing may be typed, got ${typed()}", handler.ups.isEmpty())
        assertEquals(0, handler.swipeEndCount)
        assertTrue("the pointer is retired", ptrs.isEmpty())
    }

    /** A key with no short swipe at all keeps its tap, so a sloppy press is not dropped. */
    @Test
    fun on_keyWithoutAnySubkey_keepsItsTap() {
        release(keyZ, dx = 250f, dy = 0f, snap = snap(shortSwipesOnly = true), hasLeftStartingKey = true)

        assertEquals(listOf("z"), typed())
    }

    // =========================================================================
    // ON — the minimum distance still applies
    // =========================================================================

    /** Below the 50 px minimum the press is a tap of `a`, mode or not. */
    @Test
    fun on_belowTheMinimumDistance_isStillATap() {
        release(keyA, dx = 40f, dy = 0f, snap = snap(shortSwipesOnly = true), hasLeftStartingKey = false)

        assertEquals(listOf("a"), typed())
    }

    /** Just past the minimum is the subkey, exactly as an ordinary short swipe. */
    @Test
    fun on_justPastTheMinimumDistance_isTheSubkey() {
        release(keyA, dx = 55f, dy = 0f, snap = snap(shortSwipesOnly = true), hasLeftStartingKey = false)

        assertEquals(listOf("1"), typed())
    }

    // =========================================================================
    // OFF — behaviour unchanged
    // =========================================================================

    /** OFF, swipe typing off: past the boundary the gesture still taps `a` (the #83 pin). */
    @Test
    fun off_beyondTheBoundary_swipeTypingOff_tapsTheStartingKey() {
        release(
            keyA, dx = 250f, dy = 0f,
            snap = snap(shortSwipesOnly = false, swipeTyping = false), hasLeftStartingKey = true
        )

        assertEquals(listOf("a"), typed())
        assertTrue("the subkey must not fire beyond the boundary", "1" !in typed())
    }

    /** OFF, swipe typing on: past the boundary the word-shaped gesture is a word swipe. */
    @Test
    fun off_beyondTheBoundary_swipeTypingOn_isAWordSwipe() {
        release(keyA, dx = 250f, dy = 0f, snap = snap(shortSwipesOnly = false), hasLeftStartingKey = true)

        assertEquals(1, handler.swipeEndCount)
        assertTrue("nothing is typed key by key", handler.ups.isEmpty())
    }

    /** OFF: inside the boundary a short swipe is the subkey, as before. */
    @Test
    fun off_insideTheBoundary_isTheSubkey() {
        every { recognizer.promoteWordCandidacy() } returns false
        release(
            keyA, dx = 150f, dy = 0f,
            snap = snap(shortSwipesOnly = false, swipeTyping = false), hasLeftStartingKey = false
        )

        assertEquals(listOf("1"), typed())
    }

    // ------------------------------------------------------------------ harness

    /** Records everything Pointers reports back to the keyboard view. */
    private class RecordingHandler : Pointers.IPointerEventHandler {
        val ups = mutableListOf<KeyValue?>()
        var swipeEndCount = 0

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) {}
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

    // -- sun.misc.Unsafe seams (same idiom as PointersShortSwipeCustomOverrideTest) --

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
