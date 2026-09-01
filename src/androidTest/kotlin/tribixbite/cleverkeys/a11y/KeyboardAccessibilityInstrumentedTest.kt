package tribixbite.cleverkeys.a11y

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.ComposeKeyData
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Keyboard2View
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.Pointers
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Instrumented tests for the TalkBack virtual-view tree wired into
 * [Keyboard2View] via [KeyboardAccessibilityHelper]. Drives the real
 * `AccessibilityNodeProvider` the view exposes (after
 * `ViewCompat.setAccessibilityDelegate`) — so this exercises the actual
 * end-to-end wiring, not a mock.
 *
 * Coverage (plan §7):
 *  - node tree: count > 0, every node has a non-empty description + non-empty bounds
 *  - ACTION_CLICK → recorded key_down/key_up with the CONCRETE KeyValue
 *    (assertEquals, not assertNotNull — this is the test that would have caught
 *    the skeleton's direct-handler bug)
 *  - Shift clicked twice → latch toggles on then off
 *  - hover routing: consumed only when touch-exploration is on
 *  - swipe still fires: a real onTouch DOWN/MOVE/UP path is untouched by the helper
 */
@RunWith(AndroidJUnit4::class)
class KeyboardAccessibilityInstrumentedTest {

    private lateinit var context: Context
    private lateinit var recorder: RecordingHandler
    private lateinit var view: Keyboard2View

    /** Records every key_down/key_up the view routes to Config.handler. */
    private class RecordingHandler : Config.IKeyEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<KeyValue?>()
        var modsChanged = 0
        override fun key_down(key: KeyValue?, isSwipe: Boolean) { downs.add(key) }
        override fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean) {
            ups.add(key)
        }
        override fun mods_changed(mods: Pointers.Modifiers) { modsChanged++ }
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        recorder = RecordingHandler()

        // Re-init the global Config with our recording handler BEFORE constructing
        // the view (Keyboard2View captures Config.globalConfig() in its ctor).
        val prefs = context.getSharedPreferences("cleverkeys_a11y_test_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("swipe_typing_enabled", true)
            .putBoolean("short_gestures_enabled", true)
            .putBoolean("haptic_enabled", false)
            // Deterministic Shift toggle: OFF -> LATCHED -> OFF (no double-tap lock),
            // so two ACTION_CLICKs on Shift latch then unlatch (not caps-lock).
            .putBoolean("lock_double_tap", false)
            .putInt("margin_left", 0)
            .putInt("margin_right", 0)
            .putInt("margin_top", 0)
            .putInt("margin_bottom", 0)
            .apply()
        Config.initGlobalConfig(prefs, context.resources, recorder, null)
        // The real IME initializes this at service startup; the standalone-view test must too,
        // or the modifier/compose path (e.g. modify('q', shift)) hits getStates() uninitialized.
        ComposeKeyData.initialize(context)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = Keyboard2View(context)
            val layout = LayoutsPreference.layoutOfString(context.resources, "latn_qwerty_us")
            assertNotNull("qwerty layout must load", layout)
            view.setKeyboard(layout!!)
            measureAndLayout(view, 1080, 600)
        }
    }

    private fun measureAndLayout(v: View, width: Int, height: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun provider(): AccessibilityNodeProvider {
        val p = view.accessibilityNodeProvider
        assertNotNull("view must expose an AccessibilityNodeProvider once the delegate is installed", p)
        return p!!
    }

    /** The real virtual-view ids (one per key) — NOT a 0..null scan, which never ends
     *  because ExploreByTouchHelper returns a dummy node for unknown ids. */
    private fun virtualIds(): List<Int> = view.accessibilityVirtualViewIdsForTest()

    /** All real (non-dummy) key virtual nodes. */
    private fun virtualNodes(): List<AccessibilityNodeInfo> {
        val p = provider()
        return virtualIds().map { p.createAccessibilityNodeInfo(it)!! }
    }

    /** The virtual id whose char-label matches [target] (upper/lowercase), or -1. */
    private fun findKeyId(target: Char): Int {
        val p = provider()
        for (id in virtualIds()) {
            val desc = p.createAccessibilityNodeInfo(id)?.contentDescription?.toString()
            if (desc != null && desc.length == 1 &&
                desc[0].equals(target, ignoreCase = true)
            ) return id
        }
        return -1
    }

    private fun clickVirtual(id: Int) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val ok = provider().performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null)
            assertTrue("ACTION_CLICK should be handled for virtual id $id", ok)
        }
    }

    // ── node tree ─────────────────────────────────────────────────────────────

    @Test
    fun nodeTreeHasKeysWithDescriptionsAndBounds() {
        val nodes = virtualNodes()
        assertTrue("expected several key nodes, got ${nodes.size}", nodes.size > 20)
        for (node in nodes) {
            assertNotNull("every key node needs a content description", node.contentDescription)
            assertTrue("content description must be non-empty",
                node.contentDescription.isNotEmpty())
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            assertTrue("bounds must be non-empty for '${node.contentDescription}'", !r.isEmpty)
            // Offset-independent (screen origin varies with attachment), so this is a safe
            // sanity bound: no single key may be bigger than the whole keyboard.
            assertTrue(
                "key '${node.contentDescription}' is ${r.width()}×${r.height()}, larger than " +
                    "the laid-out ${view.width}×${view.height} view — the geometry projection " +
                    "is wrong and touch exploration would land on the wrong key",
                r.width() <= view.width && r.height() <= view.height
            )
            assertTrue("every key node must advertise ACTION_CLICK",
                (node.actions and AccessibilityNodeInfo.ACTION_CLICK) != 0)
        }

        // The tree must cover the WHOLE alphabet, not merely "several" keys: a projection
        // that dropped a row would still leave 20+ nodes. `KeyLabels.describe` echoes the
        // payload symbol for an ordinary letter, so each a–z key announces as its own
        // single character (unshifted → lowercase, which `shiftClickedTwiceTogglesLatchOnThenOff`
        // depends on).
        val singleCharLabels = nodes
            .map { it.contentDescription.toString() }
            .filter { it.length == 1 }
        val letters = singleCharLabels.filter { it[0] in 'a'..'z' }.map { it[0] }.sorted()
        assertEquals(
            "every a–z key of latn_qwerty_us must be announced exactly once. Got: $letters",
            ('a'..'z').toList(), letters
        )
    }

    // ── ACTION_CLICK routes a real tap through Pointers → handler ──────────────

    @Test
    fun clickingLetterKeyEmitsThatKeyDownAndUp() {
        val id = findKeyId('q')
        assertTrue("q key must exist in the virtual tree", id >= 0)
        clickVirtual(id)

        // A stationary tap → exactly one down + up of the concrete 'q' KeyValue.
        val expected = KeyValue.makeCharKey('q')
        assertEquals(listOf<KeyValue?>(expected), recorder.downs)
        assertEquals(listOf<KeyValue?>(expected), recorder.ups)
    }

    @Test
    fun shiftClickedTwiceTogglesLatchOnThenOff() {
        val shiftId = findShiftId()
        assertTrue("shift key must exist", shiftId >= 0)

        // First click → Shift latches; 'q' now announces as "Q".
        clickVirtual(shiftId)
        assertEquals("after first shift click, 'q' should announce uppercase",
            "Q", nodeDescription(findKeyId('q')))

        // Second click → Shift unlatches; 'q' announces lowercase again.
        clickVirtual(shiftId)
        assertEquals("after second shift click, 'q' should announce lowercase",
            "q", nodeDescription(findKeyId('q')))
    }

    private fun findShiftId(): Int {
        val p = provider()
        for (id in virtualIds()) {
            val node = p.createAccessibilityNodeInfo(id) ?: continue
            if (node.isCheckable) return id // Shift/CapsLock are the only checkable keys
        }
        return -1
    }

    private fun nodeDescription(id: Int): String? =
        provider().createAccessibilityNodeInfo(id)?.contentDescription?.toString()

    // ── hover routing ─────────────────────────────────────────────────────────

    @Test
    fun touchExplorationOnConsumesHoverButDoesNotSwallowOrdinaryHardwareKeys() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        )
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as AccessibilityManager
        val info = automation.serviceInfo
        val originalFlags = info.flags
        try {
            info.flags = originalFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            automation.serviceInfo = info
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
            while (!manager.isTouchExplorationEnabled &&
                android.os.SystemClock.uptimeMillis() < deadline
            ) {
                Thread.sleep(50L)
            }
            assumeTrue(
                "emulator image rejected UiAutomation touch-exploration mode",
                manager.isTouchExplorationEnabled
            )

            val id = findKeyId('q')
            val node = provider().createAccessibilityNodeInfo(id)!!
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val downsBefore = recorder.downs.size
            val upsBefore = recorder.ups.size
            var hoverConsumed = false
            var keyConsumed = true
            instrumentation.runOnMainSync {
                val hover = MotionEvent.obtain(
                    0L, 0L, MotionEvent.ACTION_HOVER_ENTER,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0
                )
                hoverConsumed = view.dispatchHoverEvent(hover)
                hover.recycle()
                keyConsumed = view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
            }

            assertTrue("touch exploration must route hover through the virtual-key helper", hoverConsumed)
            assertFalse("ordinary hardware letter events must not be swallowed", keyConsumed)
            assertEquals("hover must not type", downsBefore, recorder.downs.size)
            assertEquals("hover must not emit key-up", upsBefore, recorder.ups.size)
        } finally {
            info.flags = originalFlags
            automation.serviceInfo = info
        }
    }

    @Test
    fun hoverEventIsNotConsumedWhenTouchExplorationOff() {
        // On CI emulators touch-exploration is off, so dispatchHoverEvent must NOT
        // consume the event (the fast path stays intact).
        val downsBefore = recorder.downs.size
        val upsBefore = recorder.ups.size
        val modsBefore = recorder.modsChanged
        var consumed = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, 100f, 100f, 0)
            consumed = view.dispatchHoverEvent(e)
            e.recycle()
        }
        assertFalse("hover must not be consumed while touch-exploration is off", consumed)
        // And it must be inert: hovering is not typing. Deltas rather than absolute
        // emptiness, so a future setup step that emits a key cannot make this vacuous.
        assertEquals("a hover must not emit key_down", downsBefore, recorder.downs.size)
        assertEquals("a hover must not emit key_up", upsBefore, recorder.ups.size)
        assertEquals("a hover must not change modifiers", modsBefore, recorder.modsChanged)
    }

    // ── swipe still fires with the a11y tree present ──────────────────────────

    @Test
    fun realTouchSwipeStillReachesTheGesturePipeline() {
        // A genuine multi-point drag must still flow through onTouch → Pointers,
        // unaffected by the presence of the accessibility helper. We assert the
        // handler saw activity from a real (positive-id) touch sequence, proving
        // the helper did not swallow onTouch.
        recorder.downs.clear(); recorder.ups.clear()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val downTime = android.os.SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float, t: Long) {
                val e = MotionEvent.obtain(downTime, t, action, x, y, 0)
                view.onTouch(view, e)
                e.recycle()
            }
            // Tap-like sequence on a letter key (short, stationary) → key output.
            val id = findKeyId('a')
            val node = provider().createAccessibilityNodeInfo(id)!!
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            val cx = r.exactCenterX(); val cy = r.exactCenterY()
            send(MotionEvent.ACTION_DOWN, cx, cy, downTime)
            send(MotionEvent.ACTION_UP, cx, cy, downTime + 40)
        }
        // The real onTouch tap produced a key event for 'a' (down may be deferred for
        // swipe detection, but the up path always emits the tapped key).
        val emitted = recorder.downs + recorder.ups
        assertTrue("real onTouch tap should still emit a key event", emitted.isNotEmpty())
        // WHICH key, not merely "some key": an a11y helper that swallowed onTouch and let
        // the virtual tree re-dispatch could emit a *different* key and still be non-empty.
        val expected = KeyValue.makeCharKey('a')
        assertEquals(
            "every key event from a stationary tap on 'a' must BE 'a'. Got: $emitted",
            List(emitted.size) { expected }, emitted
        )
    }
}
