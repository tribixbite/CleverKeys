package tribixbite.cleverkeys

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real keyboard keeps its Shift and Ctrl state across the resets that used to drop it
 * (maintainer report 2026-11-22; todo item 42). Every layout switch and every configuration
 * refresh resets the view ([Keyboard2View.reset]), and a rotation resets it two or three times.
 *
 *  1. Auto-capitalisation's Shift in an empty sentence-case field is back after 123+ then ABC
 *     (it was gone: lowercase letters).
 *  2. A latched Ctrl (latched the way a TalkBack user or a tap does: through Pointers) and caps
 *     lock survive a rotation, in an app that is recreated by the rotation
 *     ([ImeFieldTestActivity]: a new window, the keyboard starts input again) and in one that
 *     keeps its window ([ImeFieldKeepWindowTestActivity]: only the keyboard's own restart).
 *
 * Real IME, real field, real rotation (`user_rotation` with auto-rotate off). Leaves no trace: the
 * keyboard, the rotation settings and `show_ime_with_hard_keyboard` are restored. A device that
 * accepts `user_rotation` but does not turn FAILS the rotation cases; only one that does not keep
 * the setting, or does not start in portrait, skips them, logged at error level ([UserRotation]).
 */
@RunWith(AndroidJUnit4::class)
class ModifierStateAcrossResetsInstrumentedTest {

    @get:Rule
    val showImeWithHardKeyboard = ShowImeWithHardKeyboardRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private var originalIme = ""

    /** The real rotation and its restore (test-gap item 7: a rotation that did not happen fails). */
    private val rotation by lazy { UserRotation(::shell, ::orientation, ::within, TAG) }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

    /** Polls [condition] on the main thread; false at the deadline. */
    private fun within(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return true
            Thread.sleep(50L)
        }
        return false
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        if (!within(timeoutMs, condition)) throw AssertionError("timed out waiting for $what (${state()})")
    }

    private fun keyboardIn(view: View): Keyboard2View? {
        if (view is Keyboard2View) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) keyboardIn(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun service(): CleverKeysService? = CleverKeysService.getInstance()

    private fun keyboard(): Keyboard2View? = service()?.window?.window?.decorView?.let(::keyboardIn)

    /** The modifiers the live view applies to the next key ([Keyboard2View]'s `_mods`). */
    private fun mods(kb: Keyboard2View): Pointers.Modifiers =
        Keyboard2View::class.java.getDeclaredField("_mods").apply { isAccessible = true }.get(kb) as Pointers.Modifiers

    private fun shown(): Boolean =
        service()?.isInputViewShown == true && (keyboard()?.height ?: 0) > 0

    private fun has(modifier: KeyValue.Modifier): Boolean = keyboard()?.let { mods(it).has(modifier) } == true

    private fun onLetters(): Boolean =
        keyboard()?.getKeyboard()?.findKeyWithValue(KeyValue.makeCharKey('q')) != null

    private fun orientation(): Int = service()?.resources?.configuration?.orientation ?: Configuration.ORIENTATION_UNDEFINED

    /** Main-thread snapshot for failure messages. */
    private fun state(): String {
        var s = ""
        instrumentation.runOnMainSync {
            val kb = keyboard()
            s = "shown=${service()?.isInputViewShown} view=${kb != null} height=${kb?.height} " +
                "letters=${onLetters()} shift=${has(KeyValue.Modifier.SHIFT)} " +
                "shiftLocked=${kb?.isShiftLocked()} ctrl=${has(KeyValue.Modifier.CTRL)} orientation=${orientation()}"
        }
        return s
    }

    @Before
    fun setUp() {
        originalIme = shell("settings get secure default_input_method")
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        shell("ime enable $component")
        shell("ime set $component")
    }

    @After
    fun tearDown() {
        rotation.restore()
        if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
    }


    @Test
    fun autoCapitalisedShiftIsBackAfterTheNumberPane() {
        ActivityScenario.launch(ImeFieldTestActivity::class.java).use {
            waitFor("the keyboard with auto-capitalisation's Shift in the empty field") {
                shown() && onLetters() && has(KeyValue.Modifier.SHIFT)
            }
            instrumentation.runOnMainSync { service()!!.triggerKeyboardEvent(KeyValue.Event.SWITCH_NUMERIC) }
            waitFor("the number pane") { shown() && !onLetters() }
            assertTrue(
                "the number pane must not latch Shift (Shift+arrow selects): ${state()}",
                !within(500L) { has(KeyValue.Modifier.SHIFT) }
            )
            instrumentation.runOnMainSync { service()!!.triggerKeyboardEvent(KeyValue.Event.SWITCH_TEXT) }
            waitFor("the letters", 5_000L) { onLetters() }
            assertTrue(
                "Shift must be back at the sentence start after 123+ then ABC: ${state()}",
                within(2_000L) { has(KeyValue.Modifier.SHIFT) }
            )
        }
    }

    @Test
    fun ctrlAndCapsLockSurviveARotationThatRecreatesTheApp() =
        heldModifiersSurviveARotation(ImeFieldTestActivity::class.java)

    @Test
    fun ctrlAndCapsLockSurviveARotationInAnAppThatKeepsItsWindow() =
        heldModifiersSurviveARotation(ImeFieldKeepWindowTestActivity::class.java)

    private fun <A : Activity> heldModifiersSurviveARotation(activity: Class<A>) {
        rotation.requirePortrait()
        ActivityScenario.launch(activity).use {
            waitFor("the keyboard") { shown() && onLetters() }
            instrumentation.runOnMainSync { service()!!.triggerKeyboardEvent(KeyValue.Event.CAPS_LOCK) }
            latchCtrlLikeAUser()
            waitFor("Ctrl latched and caps lock on before the rotation", 5_000L) {
                has(KeyValue.Modifier.CTRL) && keyboard()?.isShiftLocked() == true
            }

            val startedAt = SystemClock.uptimeMillis()
            rotation.toLandscape { state() }
            waitFor("the keyboard back after the rotation") { shown() && onLetters() }
            val carried = within(3_000L) {
                has(KeyValue.Modifier.CTRL) && keyboard()?.isShiftLocked() == true
            }
            Log.i(TAG, "${activity.simpleName}: carried=$carried after ${SystemClock.uptimeMillis() - startedAt} ms; ${state()}")
            assertTrue("Ctrl and caps lock must survive the rotation (${activity.simpleName}): ${state()}", carried)
        }
    }

    // ------------------------------------------------------------------ theme and settings
    // Maintainer, 2026-11-23: a theme or settings change while typing dropped them as well.

    /** The keyboard shown in a field that keeps its window, with caps lock on and Ctrl latched. */
    private fun holdCtrlAndCapsLock() {
        waitFor("the keyboard") { shown() && onLetters() }
        instrumentation.runOnMainSync { service()!!.triggerKeyboardEvent(KeyValue.Event.CAPS_LOCK) }
        latchCtrlLikeAUser()
        waitFor("Ctrl latched and caps lock on", 5_000L) {
            has(KeyValue.Modifier.CTRL) && keyboard()?.isShiftLocked() == true
        }
    }

    private fun ctrlAndCapsLockOn(): Boolean = has(KeyValue.Modifier.CTRL) && keyboard()?.isShiftLocked() == true

    @Test
    fun ctrlAndCapsLockSurviveAThemeChange() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val savedTheme = prefs.getString("theme", null)
        try {
            ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use {
                holdCtrlAndCapsLock()
                var before: Keyboard2View? = null
                instrumentation.runOnMainSync { before = keyboard() }
                // Another theme: the keyboard view is rebuilt with it (CleverKeysService.onThemeChanged).
                prefs.edit().putString("theme", if (savedTheme == "dark") "light" else "dark").commit()
                waitFor("the keyboard rebuilt with the new theme") { keyboard().let { it != null && it !== before } && shown() }
                val kept = within(2_000L) { ctrlAndCapsLockOn() }
                assertTrue("Ctrl and caps lock must survive a theme change: ${state()}", kept)
            }
        } finally {
            if (savedTheme != null) prefs.edit().putString("theme", savedTheme).commit()
            else prefs.edit().remove("theme").commit()
        }
    }

    @Test
    fun ctrlAndCapsLockSurviveASettingsChange() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        try {
            ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use {
                holdCtrlAndCapsLock()
                // Any preference written refreshes the configuration (ConfigurationManager), which
                // resets the view, and shows the layout again (PreferenceUIUpdateHandler). A key of
                // the test's own, so no setting of the user's changes; removed below.
                prefs.edit().putBoolean(PROBE_KEY, true).commit()
                instrumentation.waitForIdleSync()
                assertTrue("Ctrl and caps lock must survive a settings change: ${state()}", within(1_000L) { ctrlAndCapsLockOn() })
                // Let the refresh land; a dropped modifier would not come back by itself.
                Thread.sleep(300L)
                assertTrue("Ctrl and caps lock must still be on after the refresh: ${state()}", within(500L) { ctrlAndCapsLockOn() })
            }
        } finally {
            prefs.edit().remove(PROBE_KEY).commit()
        }
    }

    @Test
    fun ctrlAndCapsLockSurviveTheSystemsDarkThemeSwitching() {
        val originalNight = shell("cmd uimode night").substringAfter(": ").trim()
        try {
            shell("cmd uimode night no")
            ActivityScenario.launch(ImeFieldTestActivity::class.java).use {
                holdCtrlAndCapsLock()
                // A configuration change that is not a rotation: the keyboard restarts for it and
                // this activity (uiMode not in its configChanges) is recreated, as most apps are.
                shell("cmd uimode night yes")
                waitFor("the keyboard back in dark mode") {
                    shown() && onLetters() &&
                        (service()!!.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                }
                val kept = within(3_000L) { ctrlAndCapsLockOn() }
                assertTrue("Ctrl and caps lock must survive the system switching to dark mode: ${state()}", kept)
            }
        } finally {
            if (originalNight in setOf("yes", "no", "auto")) shell("cmd uimode night $originalNight")
        }
    }

    // ------------------------------------------------------------------ double tap on Ctrl

    /** The live keyboard's state bits for [kv] ([Pointers.getKeyFlags]; -1: not held). Main thread. */
    private fun keyFlags(kv: KeyValue): Int {
        val kb = keyboard() ?: return -1
        val pointers = Keyboard2View::class.java.getDeclaredField("_pointers").apply { isAccessible = true }.get(kb) as Pointers
        return pointers.getKeyFlags(kv)
    }

    @Test
    fun doubleTappingCtrlLocksItAndATapReleasesIt() {
        // Maintainer 2026-11-23: "what happened to ctrl lock (pressing control twice, similar to
        // pressing shift twice = capslock)?" With the double-tap setting on (the default).
        val ctrl = KeyValue.getKeyByName("ctrl")
        ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use {
            waitFor("the keyboard") { shown() && onLetters() }
            latchCtrlLikeAUser()
            latchCtrlLikeAUser()
            assertTrue("two taps on Ctrl must lock it: ${state()}", within(2_000L) {
                has(KeyValue.Modifier.CTRL) && (keyFlags(ctrl) and Pointers.FLAG_P_LOCKED) != 0
            })
            latchCtrlLikeAUser()
            assertTrue("a tap on the locked Ctrl must release it: ${state()}", within(2_000L) { !has(KeyValue.Modifier.CTRL) })
        }
    }

    // ------------------------------------------------------------------ hold and double tap on Shift

    /**
     * Runs [body] with the subkey popover setting at [on] (`subkey_popover_enabled`; new installs
     * default to on, and holding Shift then opens its popover instead of locking it), restored after.
     */
    private fun withSubkeyPopover(on: Boolean, body: () -> Unit) {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val saved = if (prefs.contains(POPOVER_KEY)) prefs.getBoolean(POPOVER_KEY, true) else null
        try {
            prefs.edit().putBoolean(POPOVER_KEY, on).commit()
            instrumentation.waitForIdleSync()
            body()
        } finally {
            if (saved != null) prefs.edit().putBoolean(POPOVER_KEY, saved).commit()
            else prefs.edit().remove(POPOVER_KEY).commit()
        }
    }

    private fun launchAtASentenceStart(body: () -> Unit) {
        ActivityScenario.launch(ImeFieldTestActivity::class.java).use {
            waitFor("the keyboard with auto-capitalisation's Shift in the empty field") {
                shown() && onLetters() && has(KeyValue.Modifier.SHIFT) && keyboard()?.isShiftLocked() == false
            }
            body()
        }
    }

    private fun longPressTimeout(): Long {
        var ms = 0L
        instrumentation.runOnMainSync { ms = Config.globalConfig().snapshot.longPressTimeout }
        return ms
    }

    /** A real finger on the real Shift key ([touchKey]). */
    private fun touchShift(holdMs: Long, whileDown: () -> Unit = {}) =
        touchKey(context.getString(R.string.key_descr_shift), holdMs, whileDown)

    /**
     * A real finger on the key described [label]: down, [whileDown] after [holdMs], up. A touch
     * injected while the keyboard is still sliding in lands on the app below and never reaches the
     * keys (API 37 emulator, 2026-11-23: no Pointers event at all); that is lifted and tried again,
     * so a test fails on what the key does, never on where the touch went.
     */
    private fun touchKey(label: String, holdMs: Long, whileDown: () -> Unit = {}) {
        for (attempt in 1..3) {
            val (x, y) = keyCentreOnScreen(label)
            val downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, downTime, x, y)
            if (!within(500L) { fingerOnKeyboard() }) {
                touch(MotionEvent.ACTION_UP, downTime, x, y)
                Log.i(TAG, "touch attempt $attempt did not reach the keyboard at ($x, $y); ${state()}")
                Thread.sleep(SETTLE_MS)
                continue
            }
            if (holdMs > 0) Thread.sleep(holdMs)
            whileDown()
            touch(MotionEvent.ACTION_UP, downTime, x, y)
            return
        }
        throw AssertionError("no touch on $label reached the keyboard in 3 attempts: ${state()}")
    }

    /**
     * Two real fingers: one holds the key described [held] while the other taps [tapped], then the
     * first lifts (a chord, as Ctrl+C on a hardware keyboard). Retried as [touchKey] is.
     */
    private fun chord(held: String, tapped: String) {
        for (attempt in 1..3) {
            val first = keyCentreOnScreen(held)
            val second = keyCentreOnScreen(tapped)
            val downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, downTime, first.first, first.second)
            if (!within(500L) { fingerOnKeyboard() }) {
                touch(MotionEvent.ACTION_UP, downTime, first.first, first.second)
                Log.i(TAG, "chord attempt $attempt did not reach the keyboard; ${state()}")
                Thread.sleep(SETTLE_MS)
                continue
            }
            Thread.sleep(120L)
            touchSecondFinger(MotionEvent.ACTION_POINTER_DOWN, downTime, first, second)
            Thread.sleep(80L)
            touchSecondFinger(MotionEvent.ACTION_POINTER_UP, downTime, first, second)
            Thread.sleep(120L)
            touch(MotionEvent.ACTION_UP, downTime, first.first, first.second)
            return
        }
        throw AssertionError("no chord reached the keyboard in 3 attempts: ${state()}")
    }

    /** The second finger (pointer id 1, index 1) going down or up while the first stays at [first]. */
    private fun touchSecondFinger(action: Int, downTime: Long, first: Pair<Float, Float>, second: Pair<Float, Float>) {
        val properties = Array(2) { i ->
            MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = arrayOf(first, second).map { (x, y) ->
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        }.toTypedArray()
        val indexed = action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), indexed, 2, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertTrue("second finger $action not injected", instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    /**
     * The field's text. Main thread: call it inside [within] or [waitFor], which poll there
     * (runOnMainSync from the main thread throws, as its first version did on every API).
     */
    private fun fieldText(activity: ImeFieldTestActivity): String = activity.field.text.toString()

    /** Whether the subkey popover is open on the live keyboard (Keyboard2View `_subkeyPopover`). */
    private fun popoverOpen(): Boolean {
        val kb = keyboard() ?: return false
        return Keyboard2View::class.java.getDeclaredField("_subkeyPopover").apply { isAccessible = true }.get(kb) != null
    }

    @Test
    fun holdingShiftAtASentenceStartLocksIt() = withSubkeyPopover(on = false) {
        // With the popover off the hold still locks (a latched Shift included, 2026-11-23).
        launchAtASentenceStart {
            Thread.sleep(SETTLE_MS)
            touchShift(longPressTimeout() + 400L)
            val locked = within(2_000L) { has(KeyValue.Modifier.SHIFT) && keyboard()?.isShiftLocked() == true }
            assertTrue("a long press on Shift at a sentence start must turn caps lock on: ${state()}", locked)
        }
    }

    @Test
    fun holdingShiftOpensItsSubkeyPopover() = withSubkeyPopover(on = true) {
        // Maintainer 2026-11-23: "make it so holding ctrl and shift activate the subkey popup instead".
        launchAtASentenceStart {
            Thread.sleep(SETTLE_MS)
            var openDuringHold = false
            touchShift(longPressTimeout() + 400L) { openDuringHold = within(1_000L) { popoverOpen() } }
            assertTrue("holding Shift must open its subkey popover: ${state()}", openDuringHold)
            assertTrue("released in the middle: popover closed, nothing typed, the automatic Shift kept: ${state()}",
                within(2_000L) { !popoverOpen() && has(KeyValue.Modifier.SHIFT) && keyboard()?.isShiftLocked() == false })
        }
    }

    @Test
    fun aQuickDoubleTapOnShiftAtASentenceStartIsCapsLock() = withSubkeyPopover(on = true) {
        launchAtASentenceStart {
            Thread.sleep(SETTLE_MS)
            touchShift(holdMs = 0) // releases auto-capitalisation's Shift
            Thread.sleep(80L)
            touchShift(holdMs = 0)
            val locked = within(2_000L) { has(KeyValue.Modifier.SHIFT) && keyboard()?.isShiftLocked() == true }
            assertTrue("a quick double tap on the automatic Shift must turn caps lock on: ${state()}", locked)
        }
    }

    // ------------------------------------------------------------------ one rule for every modifier

    @Test
    fun holdingFnOpensItsSubkeyPopover() = withSubkeyPopover(on = true) {
        // Maintainer 2026-11-23: "shouldnt Fn key follow suit?" Released in the middle: nothing.
        ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use {
            waitFor("the keyboard") { shown() && onLetters() }
            Thread.sleep(SETTLE_MS)
            var openDuringHold = false
            touchKey(context.getString(R.string.key_descr_fn), longPressTimeout() + 400L) {
                openDuringHold = within(1_000L) { popoverOpen() }
            }
            assertTrue("holding Fn must open its subkey popover: ${state()}", openDuringHold)
            assertTrue("released in the middle: popover closed, Fn not left on: ${state()}",
                within(2_000L) { !popoverOpen() && !has(KeyValue.Modifier.FN) })
        }
    }

    @Test
    fun aQuickDoubleTapOnCtrlLocksIt() {
        val ctrl = KeyValue.getKeyByName("ctrl")
        val label = context.getString(R.string.key_descr_ctrl)
        ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use {
            waitFor("the keyboard") { shown() && onLetters() }
            Thread.sleep(SETTLE_MS)
            touchKey(label, holdMs = 0)
            Thread.sleep(80L)
            touchKey(label, holdMs = 0)
            assertTrue("a quick double tap on Ctrl must lock it: ${state()}", within(2_000L) {
                has(KeyValue.Modifier.CTRL) && (keyFlags(ctrl) and Pointers.FLAG_P_LOCKED) != 0
            })
            touchKey(label, holdMs = 0)
            assertTrue("a tap on the locked Ctrl must release it: ${state()}", within(2_000L) { !has(KeyValue.Modifier.CTRL) })
        }
    }

    @Test
    fun aSlowSecondTapOnCtrlReleasesIt() {
        // A second tap well after the first is no double tap: it turns Ctrl off, as it does in
        // other keyboards, where it used to lock it (Pointers.DOUBLE_TAP_LOCK_MS).
        val label = context.getString(R.string.key_descr_ctrl)
        ActivityScenario.launch(ImeFieldKeepWindowTestActivity::class.java).use {
            waitFor("the keyboard") { shown() && onLetters() }
            Thread.sleep(SETTLE_MS)
            touchKey(label, holdMs = 0)
            assertTrue("one tap on Ctrl latches it: ${state()}", within(2_000L) { has(KeyValue.Modifier.CTRL) })
            Thread.sleep(Pointers.DOUBLE_TAP_LOCK_MS + 800L)
            touchKey(label, holdMs = 0)
            assertTrue("a slow second tap on Ctrl must turn it off: ${state()}", within(2_000L) { !has(KeyValue.Modifier.CTRL) })
        }
    }

    @Test
    fun holdingShiftWhileTappingALetterTypesACapital() {
        // Maintainer 2026-11-23: holding a modifier while tapping another key applied nothing to
        // that first key. Shift held by one finger, a tapped by another, then b alone: "XAb".
        val shift = context.getString(R.string.key_descr_shift)
        ActivityScenario.launch(ImeFieldTestActivity::class.java).use { scenario ->
            lateinit var activity: ImeFieldTestActivity
            scenario.onActivity { activity = it }
            waitFor("the keyboard") { shown() && onLetters() }
            Thread.sleep(SETTLE_MS)
            touchKey("x", holdMs = 0) // a capital from the sentence start; the automatic Shift then lets go
            waitFor("X typed and Shift off") { fieldText(activity) == "X" && !has(KeyValue.Modifier.SHIFT) }
            chord(shift, "a")
            assertTrue("Shift must end with its finger: ${state()}", within(2_000L) { !has(KeyValue.Modifier.SHIFT) })
            touchKey("b", holdMs = 0)
            var text = ""
            val typed = within(3_000L) { text = fieldText(activity); text == "XAb" }
            assertTrue("held Shift + a must type A, and b after it b: got '$text' (${state()})", typed)
        }
    }

    /**
     * The centre on screen of the key described [label] (a letter's description is the letter,
     * whatever its case), from its accessibility node.
     */
    private fun keyCentreOnScreen(label: String): Pair<Float, Float> {
        var centre: Pair<Float, Float>? = null
        instrumentation.runOnMainSync {
            val kb = keyboard() ?: return@runOnMainSync
            val provider = kb.accessibilityNodeProvider ?: return@runOnMainSync
            for (id in kb.accessibilityVirtualViewIdsForTest()) {
                val node = provider.createAccessibilityNodeInfo(id) ?: continue
                if (!node.contentDescription?.toString().equals(label, ignoreCase = true)) continue
                val r = Rect().also(node::getBoundsInScreen)
                centre = r.exactCenterX() to r.exactCenterY()
                break
            }
        }
        return centre ?: throw AssertionError("no '$label' key on the keyboard (${state()})")
    }

    /** Whether a finger is on the keyboard now: a pointer bound to a touch, not a latch (main thread). */
    private fun fingerOnKeyboard(): Boolean {
        val kb = keyboard() ?: return false
        val pointers = Keyboard2View::class.java.getDeclaredField("_pointers").apply { isAccessible = true }.get(kb)
        val ptrs = pointers.javaClass.getDeclaredField("_ptrs").apply { isAccessible = true }.get(pointers) as List<*>
        return ptrs.any { p ->
            p != null && p.javaClass.getDeclaredField("pointerId").apply { isAccessible = true }.getInt(p) >= 0
        }
    }

    private fun touch(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue("touch $action not injected", instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    /** A tap on Ctrl through the keyboard's accessibility node: the real Pointers latch path. */
    private fun latchCtrlLikeAUser() {
        // Runs on the main thread: no state() (it posts to the main thread) in these messages.
        var problem: String? = null
        instrumentation.runOnMainSync {
            val kb = keyboard()
            val provider = kb?.accessibilityNodeProvider
            if (kb == null || provider == null) {
                problem = "no keyboard view or accessibility provider"
                return@runOnMainSync
            }
            val ctrlLabel = context.getString(R.string.key_descr_ctrl)
            val id = kb.accessibilityVirtualViewIdsForTest().firstOrNull {
                provider.createAccessibilityNodeInfo(it)?.contentDescription?.toString() == ctrlLabel
            }
            problem = when {
                id == null -> "no '$ctrlLabel' key on the keyboard"
                !provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null) -> "Ctrl did not take the click"
                else -> null
            }
        }
        problem?.let { throw AssertionError("$it (${state()})") }
    }

    private companion object {
        const val TAG = "ModifierStateTest"
        /** Written and removed by [ctrlAndCapsLockSurviveASettingsChange]; no setting reads it. */
        const val PROBE_KEY = "test_modifier_state_probe"
        /** [Config]'s `subkey_popover_enabled`. */
        const val POPOVER_KEY = "subkey_popover_enabled"
        /** Longer than the IME window's show animation (about 300 ms). */
        const val SETTLE_MS = 700L
    }
}
