package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Message
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.popover.PopoverSlot
import tribixbite.cleverkeys.popover.SubkeyAssignRequest
import tribixbite.cleverkeys.popover.SubkeyPopoverMetrics
import tribixbite.cleverkeys.popover.SubkeyPopoverState
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import java.io.File
import java.lang.reflect.Field

/**
 * The hold-then-select subkey popover's gesture routing in [Pointers]
 * (docs/specs/subkey-popover.md "Behaviour").
 *
 * Harness as in [PointersShortSwipeCustomOverrideTest]: [Pointers] allocated without its
 * constructor, collaborators injected, a REAL [ShortSwipeCustomizationManager] in a temp dir.
 * The hold is fired through the real [Pointers.handleMessage] routing with the pointer's
 * long-press `what`, exactly as the Handler would deliver it.
 *
 * Geometry used throughout: cells 100 × 150 px in a 1000 × 600 px view, finger resting at
 * (500, 300) — the grid centre, unclamped. One cell right and one up (600, 150) is NE.
 */
class PointersSubkeyPopoverTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var manager: ShortSwipeCustomizationManager
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var tmpDir: File

    /** `e`: NE default "3", N default "é"; every other slot empty. */
    private val keyE = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeCharKey('e'))
        .withKeyValue(SwipeDirection.NE.subLabelIndex, KeyValue.makeStringKey("3"))
        .withKeyValue(SwipeDirection.N.subLabelIndex, KeyValue.makeCharKey('é'))

    private fun noMods(): Pointers.Modifiers = Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    private fun snap(popover: Boolean = true, keyRepeat: Boolean = true, swipeTyping: Boolean = false): ConfigSnapshot =
        testConfigSnapshot(
            keyrepeat_enabled = keyRepeat,
            short_gestures_enabled = true,
            swipe_typing_enabled = swipeTyping,
            subkey_popover_enabled = popover,
            subkey_popover_neutral_width = 60,
            subkey_popover_neutral_height = 60,
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
        tmpDir = File(tmpRoot, "subkey-popover-${System.nanoTime()}").apply { mkdirs() }
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tmpDir
        manager = ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)

        handler = RecordingHandler()
        val recognizer = mockk<EnhancedSwipeGestureRecognizer>(relaxed = true)
        every { recognizer.isSwipeTyping() } returns false
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

    // ------------------------------------------------------------------ drivers

    /** Press [key] at ([x], [y]) and let the long-press timer fire. */
    private fun hold(
        key: KeyboardData.Key = keyE, x: Float = 500f, y: Float = 300f,
        snap: ConfigSnapshot = snap(), flags: Int = 0,
    ): Pointers.Pointer {
        val ptr = Pointers.Pointer(0, key, key.keys[0], x, y, noMods(), flags, snap)
        ptr.timeoutWhat = LONG_PRESS_WHAT
        ptrs.add(ptr)
        pointers.handleMessage(message(LONG_PRESS_WHAT))
        return ptr
    }

    private fun moveTo(x: Float, y: Float) = pointers.onTouchMove(x, y, 0)
    private fun release() = pointers.onTouchUp(0)

    private fun putMapping(mapping: ShortSwipeMapping) =
        runBlocking { manager.importFromMappings(listOf(mapping), merge = true) }

    private fun typed(s: String) = handler.ups.any { it?.getString() == s }

    // ------------------------------------------------------------------ opening

    @Test
    fun hold_opensThePopover_insteadOfKeyRepeat() {
        val ptr = hold()

        val state = handler.shown
        assertNotNull("holding a letter must open the popover", state)
        assertTrue(ptr.hasFlagsAny(Pointers.FLAG_P_POPOVER_MODE))
        assertEquals("no key repeat while the popover owns the hold", 0, handler.holds.size)
        assertTrue("nothing is typed on open", handler.ups.isEmpty())
        assertEquals(PopoverSlot.Default(SwipeDirection.NE, KeyValue.makeStringKey("3")), state!!.slot(SwipeDirection.NE))
        assertTrue(state.slot(SwipeDirection.SW) is PopoverSlot.Empty)
    }

    @Test
    fun popoverDisabled_holdStillRepeats() {
        hold(snap = snap(popover = false))

        assertNull(handler.shown)
        assertEquals("key repeat is untouched when the popover is off", 1, handler.holds.size)
    }

    @Test
    fun backspace_keepsItsOwnHold() {
        val backspace = KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.getKeyByName("backspace"))
        hold(key = backspace)

        assertNull("backspace repeat/selection must not be replaced", handler.shown)
    }

    @Test
    fun movedBeforeTheHold_isASwipe_notAPopover() {
        val ptr = Pointers.Pointer(0, keyE, keyE.keys[0], 500f, 300f, noMods(), 0, snap())
        ptr.lastX = 540f  // 40 px: past the 15 px stillness rule
        ptr.timeoutWhat = LONG_PRESS_WHAT
        ptrs.add(ptr)
        pointers.handleMessage(message(LONG_PRESS_WHAT))

        assertNull(handler.shown)
    }

    // ------------------------------------------------------------------ release

    @Test
    fun releaseInTheNeutralZone_typesNothing() {
        hold()
        moveTo(520f, 320f)  // inside the 60 % × 60 % neutral rectangle (±30 × ±45 px)
        release()

        assertTrue(handler.ups.isEmpty())
        assertTrue(handler.customs.isEmpty())
        assertTrue(handler.assignRequests.isEmpty())
        assertTrue("the popover closes on release", handler.dismissed)
        assertTrue(ptrs.isEmpty())
    }

    @Test
    fun releaseOnADefaultSlot_typesTheSubkey() {
        hold()
        moveTo(600f, 150f)  // one cell right, one up: NE
        release()

        assertTrue("NE default \"3\" must be typed", typed("3"))
        assertEquals(1, handler.ups.size)
    }

    @Test
    fun aCustomMapping_winsItsSlot_andExecutes() {
        putMapping(ShortSwipeMapping.textInput("e", SwipeDirection.NE, "€", "€"))
        hold()
        moveTo(600f, 150f)
        release()

        assertEquals("€", handler.customs.single().actionValue)
        assertFalse("the overridden default must not be typed", typed("3"))
    }

    /**
     * A slot mapped to a dead key (or modifier) must latch it for the next key, the way a
     * layout's own dead-key subkey does. It used to reach onCustomShortSwipe, which had no
     * handler for the Modifier kind and did nothing (CommandRouting, 2026-10-01).
     */
    @Test
    fun aCustomDeadKeyMapping_latchesInsteadOfGoingToTheExecutor() {
        putMapping(ShortSwipeMapping("e", SwipeDirection.NE, "´", ActionType.COMMAND, "accent_aigu"))
        hold()
        moveTo(600f, 150f)
        release()

        assertTrue("a dead key never reaches the executor", handler.customs.isEmpty())
        val latched = ptrs.singleOrNull { it.hasFlagsAny(Pointers.FLAG_P_LATCHED) }
        assertNotNull("the dead key waits, latched, for the next key", latched)
        assertEquals(KeyValue.getKeyByName("accent_aigu"), latched!!.value)
    }

    @Test
    fun releaseOnAnEmptySlot_asksForAnAssignment() {
        hold()
        moveTo(400f, 450f)  // SW, empty
        release()

        val request = handler.assignRequests.single()
        assertEquals(SubkeyAssignRequest.Mode.ASSIGN, request.mode)
        assertEquals("e", request.keyCode)
        assertEquals(SwipeDirection.SW, request.direction)
        assertFalse(request.hasDefault)
        assertTrue(handler.ups.isEmpty())
    }

    @Test
    fun aRemovedDefault_isEmpty_andItsAssignKnowsTheDefaultExists() {
        putMapping(ShortSwipeMapping.removal("e", SwipeDirection.NE))
        hold()
        moveTo(600f, 150f)
        release()

        val request = handler.assignRequests.single()
        assertEquals(SubkeyAssignRequest.Mode.ASSIGN, request.mode)
        assertTrue("assign can offer Restore default", request.hasDefault)
        assertTrue(handler.customs.isEmpty())
        assertFalse(typed("3"))
    }

    // ------------------------------------------------------------------ dwell

    @Test
    fun restingOnAnAssignedSlot_opensItsEditor_andTheReleaseIsInert() {
        val ptr = hold()
        moveTo(600f, 150f)
        val dwellWhat = ptr.popover!!.dwellWhat
        assertTrue("a dwell timer must be armed on an assigned slot", dwellWhat >= 0)

        pointers.handleMessage(message(dwellWhat))

        val request = handler.assignRequests.single()
        assertEquals(SubkeyAssignRequest.Mode.EDIT, request.mode)
        assertEquals(SwipeDirection.NE, request.direction)
        assertTrue(request.hasDefault)
        assertFalse(request.isCustom)
        assertTrue(handler.dismissed)

        moveTo(400f, 450f)  // wandering after the editor opened changes nothing
        release()
        assertTrue("the press was spent on the editor", handler.ups.isEmpty())
        assertEquals(1, handler.assignRequests.size)
        assertTrue(ptrs.isEmpty())
    }

    @Test
    fun anEmptySlot_armsNoDwellTimer() {
        val ptr = hold()
        moveTo(400f, 450f)

        assertEquals(-1, ptr.popover!!.dwellWhat)
    }

    // ------------------------------------------------------------------ clamped grid

    @Test
    fun aTopRowKey_clampsTheGridInside_andNeedsMovementToSelect() {
        val ptr = hold(y = 40f)  // grid centre clamps down to y = 225: the finger rests over N
        val state = ptr.popover!!
        assertEquals(225f, state.centreY)

        release()
        assertTrue("an unmoved release selects nothing even over a slot", handler.ups.isEmpty())
    }

    @Test
    fun aTopRowKey_afterMoving_selectsWhatIsDrawnUnderTheFinger() {
        hold(y = 40f)
        moveTo(500f, 60f)  // 20 px > the arming distance; drawn N cell spans y 0..150
        release()

        assertTrue("N default \"é\" must be typed", typed("é"))
    }

    // ------------------------------------------------------------------ harness

    private class RecordingHandler : Pointers.IPointerEventHandler {
        val ups = mutableListOf<KeyValue?>()
        val holds = mutableListOf<KeyValue>()
        val customs = mutableListOf<ShortSwipeMapping>()
        val assignRequests = mutableListOf<SubkeyAssignRequest>()
        var shown: SubkeyPopoverState? = null
        var dismissed = false

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) {}
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) {}
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) { holds += k }
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(x: Float, y: Float, key: KeyboardData.Key, tolerance: Float) = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 180f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 100f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) { customs += mapping }

        override fun subkeyPopoverMetrics(key: KeyboardData.Key) = SubkeyPopoverMetrics(100f, 150f, 1000f, 600f)
        override fun onSubkeyPopoverShow(state: SubkeyPopoverState) { shown = state }
        override fun onSubkeyPopoverDismiss() { dismissed = true }
        override fun onSubkeyAssignRequested(request: SubkeyAssignRequest) { assignRequests += request }
    }

    private companion object {
        const val LONG_PRESS_WHAT = 7_000
    }

    /** A [Message] with only `what` set; android.jar's constructor is a stub, so allocate raw. */
    private fun message(what: Int): Message =
        allocate(Message::class.java).also { m ->
            Message::class.java.getField("what").setInt(m, what)
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
}
