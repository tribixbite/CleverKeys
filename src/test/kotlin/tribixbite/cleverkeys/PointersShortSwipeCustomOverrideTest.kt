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
 * Custom short-swipe mapping DISPATCH guards for [Pointers.onTouchUp] — GitHub #171, #145, #83.
 *
 * ## #171 — a custom mapping must ALWAYS shadow the default it replaces
 *
 * The short-swipe resolution computes a 16-bin direction from the release vector. The
 * DEFAULT subkey lookup ([Pointers.getNearestKeyAtDirection]) forgives up to ±1 bin of
 * angle noise, but the CUSTOM mapping lookup consulted only the exact bin. A NW flick on
 * `q` (default nw=`~`, custom nw=`!`) that lands in the adjacent W or N bin therefore
 * missed the custom mapping while the fuzz still reached the *default* `~` at the very
 * slot the user overrode — "sometimes types ~, sometimes !", decided by a few degrees of
 * angle. The contract pinned here: at every fuzzed bin, the user's custom mapping for
 * that bin's direction is consulted BEFORE the layout's default subkey, so the fuzz can
 * never resurrect a shadowed default. Nearest bin still wins overall (an exact-direction
 * default beats a neighbor-bin custom — see [defaultSubkey_exactDirection_beatsCustomAtNeighborOffset]).
 *
 * ## #145 — custom mappings are independent of swipe typing (regression pin)
 *
 * Reported against v1.4.0: per-key gestures died after a reboot when swipe typing was
 * disabled. Root cause was the untethered FLAG_P_SWIPE_TYPING latch, fixed by 5e7fdcb7
 * ("swipe_typing_enabled gates every word-swipe route", v1.5.0). The pin here proves the
 * requested end state at HEAD: after a COLD initialization (fresh manager instance loading
 * the mappings file from disk, exactly what an IME cold start does) a customized
 * short-swipe resolves with `swipe_typing_enabled = false`.
 *
 * ## #83 — the short/long boundary is authoritative (BY-DESIGN pin)
 *
 * `short_gesture_max_distance` IS the configurable boundary: at/below it a gesture is a
 * short swipe (direction keys / custom mappings honored, pinned by the flick tests);
 * beyond it the gesture belongs to the word-swipe machinery, which with swipe typing
 * disabled deliberately degrades to a tap of the starting key (5e7fdcb7, instrumented
 * T9). The pin proves a beyond-boundary gesture aimed at an ASSIGNED subkey direction
 * still taps the starting key — the remedy for wanting longer direction swipes is the
 * setting, not a routing change.
 *
 * ## Harness
 *
 * Same idiom as [ReleaseClaimGestureModesTest]: [Pointers] is allocated without running
 * its constructor (`sun.misc.Unsafe`), collaborators are injected, and the REAL public
 * [Pointers.onTouchUp] is driven with a hand-built [Pointers.Pointer] whose release
 * vector encodes the direction bin under test. The [ShortSwipeCustomizationManager] is a
 * REAL instance (private-constructor reflection; its file lives in a per-test temp dir),
 * so the production `getMapping` lookup chain is exercised end to end.
 */
class PointersShortSwipeCustomOverrideTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var manager: ShortSwipeCustomizationManager
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer
    private lateinit var tmpDir: File
    private lateinit var context: Context

    // ------------------------------------------------------------------ fixtures

    /** `q` with the stock top-row default: nw (index 1) = "~". */
    private val keyQ = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeCharKey('q'))
        .withKeyValue(1, KeyValue.makeStringKey("~"))

    /** `q` with BOTH a nw default "~" and a w (index 5) default "`". */
    private val keyQWithW = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeCharKey('q'))
        .withKeyValue(1, KeyValue.makeStringKey("~"))
        .withKeyValue(5, KeyValue.makeStringKey("`"))

    /** `a` with an e (index 6) default "1". */
    private val keyA = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeCharKey('a'))
        .withKeyValue(6, KeyValue.makeStringKey("1"))

    private fun noMods(): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    /**
     * Key diagonal is 200 px (see [RecordingHandler.getKeyHypotenuse]), so with the
     * values below: min = min(25% * 200, 100 * 0.8) = 50 px, max = 100% * 200 = 200 px.
     */
    private fun snap(swipeTyping: Boolean = false): ConfigSnapshot = testConfigSnapshot(
        short_gestures_enabled = true,
        swipe_typing_enabled = swipeTyping,
        short_gesture_min_distance = PercentOfKey(25),
        short_gesture_max_distance = PercentOfKey(100),
        swipe_dist_px = 100f
    )

    @Before
    fun setUp() {
        // Debug unit-test builds compile ENABLE_VERBOSE_LOGGING as true; the production
        // paths call android.util.Log whose android.jar stub throws.
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        // Termux: /tmp is absent and java.io.tmpdir may point at it — probe the candidates
        // and take the first writable root (#145's cold-load test needs REAL file IO).
        val tmpRoot = sequenceOf(
            System.getProperty("java.io.tmpdir"),
            System.getenv("TMPDIR"),
            "build/tmp"
        ).filterNotNull().map(::File).first { it.isDirectory && it.canWrite() || it.mkdirs() }
        tmpDir = File(tmpRoot, "short-swipe-override-${System.nanoTime()}").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.filesDir } returns tmpDir
        manager = newManagerInstance(context)

        handler = RecordingHandler()
        recognizer = mockk(relaxed = true)
        every { recognizer.getSwipePath() } returns emptyList()
        every { recognizer.isSwipeTyping() } returns false
        every { recognizer.promoteWordCandidacy() } returns false

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

    /** Release a short swipe whose displacement from touch-down is (dx, dy). */
    private fun flick(
        key: KeyboardData.Key,
        dx: Float,
        dy: Float,
        snap: ConfigSnapshot,
        flags: Int = 0,
        hasLeftStartingKey: Boolean = false
    ) {
        val ptr = Pointers.Pointer(0, key, key.keys[0], 100f, 100f, noMods(), flags, snap)
        ptr.lastX = 100f + dx
        ptr.lastY = 100f + dy
        ptr.hasLeftStartingKey = hasLeftStartingKey
        ptrs.add(ptr)
        pointers.onTouchUp(0)
    }

    private fun putMapping(mapping: ShortSwipeMapping) =
        runBlocking { manager.importFromMappings(listOf(mapping), merge = true) }

    private fun nwBang() = ShortSwipeMapping.textInput("q", SwipeDirection.NW, "!", "!")

    private fun emitted(s: String): Boolean = handler.ups.any { it?.getString() == s }

    // =========================================================================
    // #171 — custom mapping vs default sublabel dispatch
    // =========================================================================

    /** Exact NW bin (dir 13): the custom mapping fires and the default "~" never does. */
    @Test
    fun customMapping_exactNwBin_firesCustom_notDefault() {
        putMapping(nwBang())

        flick(keyQ, dx = -40f, dy = -40f, snap = snap()) // 45° up-left, dist 56.6 -> dir 13 (NW)

        assertEquals("custom mapping must execute exactly once", 1, handler.customs.size)
        assertEquals("!", handler.customs.single().actionValue)
        assertTrue("the shadowed default \"~\" must not be emitted", !emitted("~"))
    }

    /**
     * #171 core repro, W boundary bin (dir 12): the release vector is within the ±1-bin
     * forgiveness of NW. The exact W bin has no default and no mapping; the fuzz reaches
     * the nw SLOT — where the user's custom "!" must win, not the overridden default "~".
     */
    @Test
    fun customMapping_wBoundaryBin_mustWinOverFuzzedDefault() {
        putMapping(nwBang())

        // Angle mid-bin of dir 12: dx = -58.85, dy = -11.7 (r = 60 px).
        flick(keyQ, dx = -58.85f, dy = -11.7f, snap = snap())

        assertEquals(
            "the custom NW mapping must shadow the default at the fuzzed nw slot",
            1, handler.customs.size
        )
        assertTrue("the overridden default \"~\" must never fire", !emitted("~"))
    }

    /** #171 core repro, N boundary bin (dir 15): mirror of the W-bin case. */
    @Test
    fun customMapping_nBoundaryBin_mustWinOverFuzzedDefault() {
        putMapping(nwBang())

        // Angle mid-bin of dir 15: dx = -11.7, dy = -58.85 (r = 60 px).
        flick(keyQ, dx = -11.7f, dy = -58.85f, snap = snap())

        assertEquals(
            "the custom NW mapping must shadow the default at the fuzzed nw slot",
            1, handler.customs.size
        )
        assertTrue("the overridden default \"~\" must never fire", !emitted("~"))
    }

    /**
     * Contract guard: nearest bin still wins. A flick in the exact W bin on a key whose W
     * slot HAS a default emits that default — a custom mapping one bin away must not
     * out-rank an exact-direction default.
     */
    @Test
    fun defaultSubkey_exactDirection_beatsCustomAtNeighborOffset() {
        putMapping(nwBang())

        flick(keyQWithW, dx = -58.85f, dy = -11.7f, snap = snap()) // exact dir 12 (W)

        assertEquals("no custom mapping may fire for the W-bin flick", 0, handler.customs.size)
        assertTrue("the exact-direction default \"`\" must be emitted", emitted("`"))
        assertTrue("the NW default stays shadowed", !emitted("~"))
    }

    // =========================================================================
    // #145 — independence from swipe typing after a cold init
    // =========================================================================

    /**
     * A mapping persisted by one manager instance must resolve through a FRESH instance
     * that cold-loads the file from disk (the IME-restart path), with swipe typing
     * DISABLED for the whole gesture. Pins the #145 end state (root cause fixed by
     * 5e7fdcb7 — the FLAG_P_SWIPE_TYPING latch is gated on the setting since v1.5.0).
     */
    @Test
    fun customMapping_resolvesAfterColdInitFromDisk_swipeTypingDisabled() {
        putMapping(nwBang()) // persists to tmpDir via the first instance

        val coldManager = newManagerInstance(context)
        runBlocking { coldManager.loadMappings() }
        setField(pointers, "_customSwipeManager", coldManager)

        flick(keyQ, dx = -40f, dy = -40f, snap = snap(swipeTyping = false))

        assertEquals(
            "a cold-loaded custom mapping must resolve with swipe typing disabled",
            1, handler.customs.size
        )
        assertEquals("!", handler.customs.single().actionValue)
    }

    // =========================================================================
    // #83 — the short/long boundary is authoritative (BY-DESIGN pin)
    // =========================================================================

    /**
     * A gesture beyond `short_gesture_max_distance` (250 px > 200 px boundary, so
     * `hasLeftStartingKey` is latched by the move path) is NOT a short swipe even when it
     * points straight at an assigned subkey, and with swipe typing disabled it degrades
     * to a tap of the starting key (5e7fdcb7 / instrumented T9). Raising the setting is
     * the supported way to lengthen direction swipes.
     */
    @Test
    fun beyondMaxBoundary_swipeTypingDisabled_tapsStartingKey() {
        flick(
            keyA, dx = 250f, dy = 0f, snap = snap(swipeTyping = false),
            flags = Pointers.FLAG_P_DEFERRED_DOWN, hasLeftStartingKey = true
        )

        assertEquals("no custom mapping exists, none may fire", 0, handler.customs.size)
        assertTrue("the assigned E subkey \"1\" must NOT fire beyond the boundary", !emitted("1"))
        assertTrue(
            "the starting key must be committed as a tap",
            handler.ups.any { it?.getChar() == 'a' }
        )
    }

    // ------------------------------------------------------------------ harness

    /** Records everything Pointers reports back to the keyboard view. */
    private class RecordingHandler : Pointers.IPointerEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<KeyValue?>()
        val customs = mutableListOf<ShortSwipeMapping>()
        var swipeEndCount = 0

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
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) { customs += mapping }
    }

    /** Real manager via its private constructor — the singleton stays untouched. */
    private fun newManagerInstance(ctx: Context): ShortSwipeCustomizationManager =
        ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(ctx)

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
