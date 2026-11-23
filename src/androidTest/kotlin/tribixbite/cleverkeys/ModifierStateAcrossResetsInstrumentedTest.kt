package tribixbite.cleverkeys

import android.app.Activity
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * does not rotate for `user_rotation` skips the rotation cases (assumption), it does not pass them.
 */
@RunWith(AndroidJUnit4::class)
class ModifierStateAcrossResetsInstrumentedTest {

    @get:Rule
    val showImeWithHardKeyboard = ShowImeWithHardKeyboardRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private var originalIme = ""
    private var originalAutoRotate = ""
    private var originalUserRotation = ""
    private var rotated = false

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
        originalAutoRotate = shell("settings get system accelerometer_rotation")
        originalUserRotation = shell("settings get system user_rotation")
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        shell("ime enable $component")
        shell("ime set $component")
    }

    @After
    fun tearDown() {
        if (rotated) {
            restoreSystemSetting("user_rotation", originalUserRotation)
            restoreSystemSetting("accelerometer_rotation", originalAutoRotate)
            within(10_000L) { orientation() == Configuration.ORIENTATION_PORTRAIT }
        }
        if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
    }

    private fun restoreSystemSetting(key: String, value: String) {
        if (value == "null" || value.isBlank()) shell("settings delete system $key")
        else shell("settings put system $key $value")
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
        assumeTrue(
            "rotation needs portrait to start from (${orientation()})",
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        )
        ActivityScenario.launch(activity).use {
            waitFor("the keyboard") { shown() && onLetters() }
            instrumentation.runOnMainSync { service()!!.triggerKeyboardEvent(KeyValue.Event.CAPS_LOCK) }
            latchCtrlLikeAUser()
            waitFor("Ctrl latched and caps lock on before the rotation", 5_000L) {
                has(KeyValue.Modifier.CTRL) && keyboard()?.isShiftLocked() == true
            }

            val startedAt = SystemClock.uptimeMillis()
            rotated = true
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            val turned = within(10_000L) { orientation() == Configuration.ORIENTATION_LANDSCAPE }
            assumeTrue("this device did not rotate for user_rotation=1 (${state()})", turned)
            waitFor("the keyboard back after the rotation") { shown() && onLetters() }
            val carried = within(3_000L) {
                has(KeyValue.Modifier.CTRL) && keyboard()?.isShiftLocked() == true
            }
            Log.i(TAG, "${activity.simpleName}: carried=$carried after ${SystemClock.uptimeMillis() - startedAt} ms; ${state()}")
            assertTrue("Ctrl and caps lock must survive the rotation (${activity.simpleName}): ${state()}", carried)
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
    }
}
