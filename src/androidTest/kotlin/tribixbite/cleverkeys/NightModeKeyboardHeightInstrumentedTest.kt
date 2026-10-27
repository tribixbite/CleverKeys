package tribixbite.cleverkeys

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.theme.KeyboardLooks

/**
 * Keyboard height across system night-mode switches (todo items 25 and 27, 2026-10-27: on the
 * Pixel the keyboard came back ~10 % shorter after a switch; on the Saga the IME window once came
 * up one navigation-bar height short with the bottom row under the bar).
 *
 * A theme that follows the system (a keyboard look) re-inflates the keyboard view on every
 * switch (`ConfigurationManager.onThemeChanged`). After each switch, with the keyboard shown, the
 * new view must have the same row height, the same measured height and the same nav-bar bottom
 * inset as before the first switch, and its key area must end above the navigation bar. Every
 * observation is logged (tag [TAG]) so a failure says which input moved. Leaves no trace: the
 * night mode, the theme preference and the IME are restored.
 */
@RunWith(AndroidJUnit4::class)
class NightModeKeyboardHeightInstrumentedTest {

    @get:org.junit.Rule
    val showImeWithHardKeyboard = ShowImeWithHardKeyboardRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            Thread.sleep(100L)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun keyboardIn(view: View): Keyboard2View? {
        if (view is Keyboard2View) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) keyboardIn(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun keyboard(): Keyboard2View? =
        CleverKeysService.getInstance()?.window?.window?.decorView?.let(::keyboardIn)

    private fun intField(view: Keyboard2View, name: String): Int =
        Keyboard2View::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(view)

    /** What decides the keyboard's height, read on the main thread from the live view. */
    private data class Observation(
        val rowHeight: Float,
        val viewHeight: Int,
        val insetsBottom: Int,
        val screenHeightPixels: Int,
        val keysBottomOnScreen: Int,
        val navBarTopOnScreen: Int,
        val detail: String,
    )

    private fun observe(label: String): Observation {
        var o: Observation? = null
        instrumentation.runOnMainSync {
            val kb = requireNotNull(keyboard()) { "no keyboard view" }
            val service = requireNotNull(CleverKeysService.getInstance())
            val origin = IntArray(2).also { kb.getLocationOnScreen(it) }
            val insetsBottom = intField(kb, "_insets_bottom")
            val rowHeight = requireNotNull(kb.geometryParams()).rowHeight
            val keys = requireNotNull(kb.getKeyboard())
            val keysBottom = origin[1] + (Config.globalConfig().snapshot.marginTop + rowHeight * keys.keysHeight).toInt()
            val wm = context.getSystemService(WindowManager::class.java)
            val displayHeight = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height()
                else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.heightPixels
            val navBottom = kb.rootWindowInsets?.let { wi ->
                if (Build.VERSION.SDK_INT >= 30) wi.getInsets(WindowInsets.Type.navigationBars()).bottom
                else @Suppress("DEPRECATION") wi.systemWindowInsetBottom
            } ?: 0
            val detail = "$label: rowHeight=$rowHeight viewHeight=${kb.height} measured=${kb.measuredHeight} " +
                "insetsBottom=$insetsBottom screenHeightPixels=${Config.globalConfig().screenHeightPixels} " +
                "serviceDm=${service.resources.displayMetrics.heightPixels} appDm=${context.resources.displayMetrics.heightPixels} " +
                "display=$displayHeight navBottom=$navBottom viewTop=${origin[1]} keysBottom=$keysBottom " +
                "theme=${Config.globalConfig().themeName} light=${Config.isSystemLight(service.resources)}"
            Log.i(TAG, detail)
            o = Observation(rowHeight, kb.height, insetsBottom, Config.globalConfig().screenHeightPixels,
                keysBottom, displayHeight - navBottom, detail)
        }
        return requireNotNull(o)
    }

    @Test
    fun theKeyboardKeepsItsHeightAndStaysAboveTheNavBarAcrossNightModeSwitches() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val savedTheme = prefs.getString("theme", null)
        val originalIme = shell("settings get secure default_input_method")
        val originalNight = shell("cmd uimode night").substringAfter(": ").trim()
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        try {
            shell("cmd uimode night no")
            shell("ime enable $component")
            shell("ime set $component")
            prefs.edit().putString("theme", KeyboardLooks.GBOARD).commit()
            ActivityScenario.launch(ClipboardEditTestActivity::class.java).use { scenario ->
                // A night-mode switch RECREATES the activity (uiMode is not in its configChanges),
                // so the field is rebuilt and focused again after every switch, as a user's app
                // would restore it; the keyboard hides and shows again in between.
                fun focusField() {
                    lateinit var editor: EditText
                    scenario.onActivity { activity ->
                        editor = EditText(activity)
                        activity.setContentView(editor)
                        editor.requestFocus()
                        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    }
                    scenario.onActivity { activity ->
                        activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                focusField()
                waitFor("keyboard shown") { CleverKeysService.getInstance()?.isInputViewShown == true && (keyboard()?.height ?: 0) > 0 }
                Thread.sleep(1_500L)
                val before = observe("before")
                assertTrue("keys end above the nav bar before any switch: ${before.detail}",
                    before.keysBottomOnScreen <= before.navBarTopOnScreen + 1)

                val failures = mutableListOf<String>()
                for ((i, mode) in listOf("yes", "no", "yes", "no").withIndex()) {
                    val old = keyboard()
                    shell("cmd uimode night $mode")
                    waitFor("view rebuilt after night $mode") { keyboard().let { it != null && it !== old } }
                    Thread.sleep(1_000L)
                    focusField()
                    waitFor("keyboard shown after night $mode") {
                        CleverKeysService.getInstance()?.isInputViewShown == true && (keyboard()?.height ?: 0) > 0
                    }
                    // Let the IME window settle (inset dispatch, relayout) before reading.
                    Thread.sleep(1_500L)
                    val now = observe("switch ${i + 1} night=$mode")
                    if (now.rowHeight != before.rowHeight) failures += "row height ${before.rowHeight} → ${now.rowHeight}"
                    if (now.viewHeight != before.viewHeight) failures += "view height ${before.viewHeight} → ${now.viewHeight}"
                    if (now.insetsBottom != before.insetsBottom) failures += "nav inset ${before.insetsBottom} → ${now.insetsBottom}"
                    if (now.keysBottomOnScreen > now.navBarTopOnScreen + 1) failures += "keys end at ${now.keysBottomOnScreen}, nav bar starts at ${now.navBarTopOnScreen}"
                    if (failures.isNotEmpty()) throw AssertionError("after switch ${i + 1} (night $mode): $failures\nbefore: ${before.detail}\nnow: ${now.detail}")
                }
                assertEquals(before.screenHeightPixels, Config.globalConfig().screenHeightPixels)
            }
        } finally {
            if (savedTheme != null) prefs.edit().putString("theme", savedTheme).commit()
            else prefs.edit().remove("theme").commit()
            if (originalNight in setOf("yes", "no", "auto")) shell("cmd uimode night $originalNight")
            if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
        }
    }

    private companion object {
        const val TAG = "NightModeHeightTest"
    }
}
