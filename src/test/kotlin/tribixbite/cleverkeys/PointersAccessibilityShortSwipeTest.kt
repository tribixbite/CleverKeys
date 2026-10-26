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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.popover.PopoverSlot
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import java.io.File
import java.lang.reflect.Field

/**
 * TalkBack short-swipe actions on [Pointers] (maintainer report 2026-10-26: explore-by-touch
 * consumes swipes, so the clipboard pane — Ctrl swipe south-west — was unreachable).
 *
 * [Pointers.accessibilityShortSwipeSlots] lists what each direction of a key does, and
 * [Pointers.performAccessibilityShortSwipe] runs one. The contract pinned here: an action does
 * EXACTLY what the matching real short swipe does — the same resolution (custom mapping before
 * the layout default, `removed` blanks the slot) and the same dispatch (`emitSubkeyValue` /
 * `executeCustomShortSwipe`), observed as the identical handler event sequence a real flick
 * released through [Pointers.onTouchUp] records. The short-gestures setting gates both.
 *
 * Harness: the [PointersShortSwipeCustomOverrideTest] idiom — [Pointers] allocated without
 * its constructor, collaborators injected, a REAL [ShortSwipeCustomizationManager] whose file
 * lives in a per-test temp dir.
 */
class PointersAccessibilityShortSwipeTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var manager: ShortSwipeCustomizationManager
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var config: Config
    private lateinit var tmpDir: File

    /** The bottom-row Ctrl key (res/xml/bottom_row.xml): sw = switch_clipboard, se = switch_numeric. */
    private val keyCtrl = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("ctrl"))
        .withKeyValue(3, KeyValue.getKeyByName("switch_clipboard"))
        .withKeyValue(4, KeyValue.getKeyByName("switch_numeric"))

    /** `q` with nw (index 1) = "~" and ne (index 2) = "the". */
    private val keyQ = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeCharKey('q'))
        .withKeyValue(1, KeyValue.makeStringKey("~"))
        .withKeyValue(2, KeyValue.makeStringKey("the"))

    private fun noMods(): Pointers.Modifiers = Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    private fun snap(shortGestures: Boolean = true): ConfigSnapshot = testConfigSnapshot(
        short_gestures_enabled = shortGestures,
        swipe_typing_enabled = false,
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

        val tmpRoot = sequenceOf(System.getProperty("java.io.tmpdir"), System.getenv("TMPDIR"), "build/tmp")
            .filterNotNull().map(::File).first { it.isDirectory && it.canWrite() || it.mkdirs() }
        tmpDir = File(tmpRoot, "a11y-short-swipe-${System.nanoTime()}").apply { mkdirs() }
        val context: Context = mockk(relaxed = true)
        every { context.filesDir } returns tmpDir
        manager = ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)

        handler = RecordingHandler()
        val recognizer: EnhancedSwipeGestureRecognizer = mockk(relaxed = true)
        every { recognizer.getSwipePath() } returns emptyList()
        every { recognizer.isSwipeTyping() } returns false
        every { recognizer.promoteWordCandidacy() } returns false

        config = mockk(relaxed = true)
        every { config.snapshot } returns snap()

        ptrs = ArrayList()
        pointers = allocate(Pointers::class.java)
        setField(pointers, "_handler", handler)
        setField(pointers, "_config", config)
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

    private fun putMapping(mapping: ShortSwipeMapping) =
        runBlocking { manager.importFromMappings(listOf(mapping), merge = true) }

    private fun perform(key: KeyboardData.Key, dir: SwipeDirection): Boolean =
        pointers.performAccessibilityShortSwipe(key, dir, A11Y_ID, 100f, 100f)

    /** Release a real short swipe on [key] with displacement (dx, dy) through onTouchUp. */
    private fun flick(key: KeyboardData.Key, dx: Float, dy: Float) {
        val ptr = Pointers.Pointer(0, key, key.keys[0], 100f, 100f, noMods(), 0, snap())
        ptr.lastX = 100f + dx
        ptr.lastY = 100f + dy
        ptrs.add(ptr)
        pointers.onTouchUp(0)
    }

    private fun latchShift() {
        val shift = KeyValue.getKeyByName("shift")
        val flags = pointers.pointer_flags_of_kv(shift, snap()) or Pointers.FLAG_P_LATCHED
        ptrs.add(Pointers.Pointer(-1, KeyboardData.Key.EMPTY.withKeyValue(0, shift), shift, 0f, 0f, noMods(), flags, snap()))
    }

    // ---------------------------------------------------------------- listing

    @Test
    fun ctrlListsTheClipboardPaneInItsSouthwestSlot() {
        val slots = pointers.accessibilityShortSwipeSlots(keyCtrl)
        val sw = slots.single { it.direction == SwipeDirection.SW }
        assertTrue("sw must be the layout default", sw is PopoverSlot.Default)
        assertEquals(KeyValue.getKeyByName("switch_clipboard"), (sw as PopoverSlot.Default).value)
    }

    @Test
    fun shortGesturesOff_listsNothingAndPerformsNothing() {
        every { config.snapshot } returns snap(shortGestures = false)
        assertTrue(pointers.accessibilityShortSwipeSlots(keyCtrl).all { it is PopoverSlot.Empty })
        assertFalse(perform(keyCtrl, SwipeDirection.SW))
        assertTrue(handler.downs.isEmpty() && handler.ups.isEmpty())
    }

    // ---------------------------------------------------------------- perform

    @Test
    fun ctrlSouthwestAction_sendsSwitchClipboardThroughTheKeyPipeline() {
        assertTrue(perform(keyCtrl, SwipeDirection.SW))
        val clip = KeyValue.getKeyByName("switch_clipboard")
        assertEquals(listOf(clip), handler.downs)
        assertEquals(listOf(clip), handler.ups)
        assertTrue("the Ctrl key itself is never pressed", handler.downs.none { it == keyCtrl.keys[0] })
        assertTrue("no pointer is left behind", ptrs.isEmpty())
    }

    @Test
    fun emptyDirection_performsNothing() {
        assertFalse(perform(keyCtrl, SwipeDirection.E))
        assertTrue(handler.downs.isEmpty() && handler.ups.isEmpty() && handler.customs.isEmpty())
    }

    @Test
    fun defaultSubkey_matchesARealFlickEventForEvent() {
        flick(keyQ, dx = -40f, dy = -40f) // NW
        val real = handler.snapshot()
        handler.clear()

        assertTrue(perform(keyQ, SwipeDirection.NW))
        assertEquals(real, handler.snapshot())
        assertEquals(listOf<KeyValue?>(KeyValue.makeStringKey("~")), handler.ups)
    }

    @Test
    fun customMapping_shadowsTheDefault_likeARealFlick() {
        putMapping(ShortSwipeMapping.textInput("q", SwipeDirection.NW, "!", "!"))
        flick(keyQ, dx = -40f, dy = -40f)
        val real = handler.snapshot()
        handler.clear()

        assertTrue(perform(keyQ, SwipeDirection.NW))
        assertEquals(real, handler.snapshot())
        assertTrue("the overridden default must not fire", handler.ups.none { it?.getString() == "~" })
        assertEquals(1, handler.ups.count { it?.getString() == "!" })
    }

    @Test
    fun executorCommandMapping_reachesOnCustomShortSwipe_likeARealFlick() {
        // paste_pinned_1 dispatches by name through the executor, not the key pipeline.
        val pinned = ShortSwipeMapping("q", SwipeDirection.NW, "📌1", ActionType.COMMAND, "paste_pinned_1")
        putMapping(pinned)
        flick(keyQ, dx = -40f, dy = -40f)
        val real = handler.snapshot()
        handler.clear()

        assertTrue(perform(keyQ, SwipeDirection.NW))
        assertEquals(real, handler.snapshot())
        assertEquals(listOf(pinned), handler.customs)
    }

    @Test
    fun removedMapping_blanksTheSlot() {
        putMapping(ShortSwipeMapping.removal("q", SwipeDirection.NW))
        assertTrue(pointers.accessibilityShortSwipeSlots(keyQ).single { it.direction == SwipeDirection.NW } is PopoverSlot.Empty)
        assertFalse(perform(keyQ, SwipeDirection.NW))
        assertTrue(handler.ups.isEmpty() && handler.customs.isEmpty())
    }

    @Test
    fun latchedShift_appliesToTheSwipe_andIsClearedAfterwards() {
        latchShift()
        assertTrue(perform(keyQ, SwipeDirection.NW))
        assertEquals(listOf<KeyValue?>(KeyValue.makeStringKey("~")), handler.ups)
        // The swipe carries the latched Shift, exactly like a finger that went down while it
        // was latched (onTouchDown captures getModifiers), and clears it as every short swipe does.
        assertTrue("the latched Shift reaches the emitted key", handler.upHadShift.single())
        assertEquals("a short swipe clears the latched Shift", 0, pointers.getModifiers().size())
    }

    // ---------------------------------------------------------------- harness

    private class RecordingHandler : Pointers.IPointerEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<KeyValue?>()
        val customs = mutableListOf<ShortSwipeMapping>()
        val upHadShift = mutableListOf<Boolean>()

        fun snapshot() = Triple(downs.toList(), ups.toList(), customs.toList())
        fun clear() { downs.clear(); ups.clear(); customs.clear(); upHadShift.clear() }

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { downs += k }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) {
            ups += k
            upHadShift += mods.has(KeyValue.Modifier.SHIFT)
        }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) {}
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(x: Float, y: Float, key: KeyboardData.Key, tolerance: Float): Boolean = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 200f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 120f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) { customs += mapping }
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
        val offset = u.javaClass.getMethod("objectFieldOffset", Field::class.java).invoke(u, field) as Long
        u.javaClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(u, target, offset, value)
    }

    private companion object {
        /** Mirrors Keyboard2View's reserved accessibility pointer id. */
        const val A11Y_ID = -2
    }
}
