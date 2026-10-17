package tribixbite.cleverkeys

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Per-layout theme binding, end to end in the real IME (Pixel device report 2026-10-17: global
 * theme Cyberpunk, a layout bound to Light, switching onto it left the suggestion strip with no
 * visible text).
 *
 * Drives the actual service the way the device run did: the keyboard is shown, the layout is
 * switched by a space-bar swipe injected through the window while shown, text arrives as key
 * events to the focused app window (what `adb shell input text` sends), and the test inspects the bar
 * that is ON SCREEN — every attached [SuggestionBar] in the IME window — against the bar the
 * suggestion pipeline writes to. Each state is logged under [TAG] before it is asserted, so a
 * red run still says which bar showed what.
 */
@RunWith(AndroidJUnit4::class)
class LayoutThemeSuggestionBarInstrumentedTest {

    /** The IME must be allowed on screen despite the emulator's hardware keyboard (API 36+ images). */
    @get:org.junit.Rule
    val showImeWithHardKeyboard = ShowImeWithHardKeyboardRule()


    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

    private fun waitForShown(shown: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var matches = false
            instrumentation.runOnMainSync {
                matches = CleverKeysService.getInstance()?.isInputViewShown == shown
            }
            if (matches) return
            Thread.sleep(100L)
        }
        throw AssertionError("IME did not reach shown=$shown")
    }

    /** What one bar looks like, read on the main thread and returned as plain values. */
    data class BarState(
        val identity: Int,
        val shown: Boolean,
        val background: Int?,
        val chips: List<Pair<String, Int>>,
    )

    private fun keyboardIn(view: View): Keyboard2View? {
        if (view is Keyboard2View) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) keyboardIn(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun barsIn(view: View, out: MutableList<SuggestionBar>) {
        if (view is SuggestionBar) out.add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) barsIn(view.getChildAt(i), out)
    }

    private fun stateOf(bar: SuggestionBar): BarState = BarState(
        identity = System.identityHashCode(bar),
        shown = bar.isShown,
        background = (bar.background as? ColorDrawable)?.color,
        chips = (0 until bar.childCount).map { bar.getChildAt(it) }
            .filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }
            .map { it.text.toString() to it.currentTextColor },
    )

    /** (bars attached to the IME window, the bar SuggestionHandler writes to). */
    private fun snapshot(stage: String): Pair<List<BarState>, BarState?> {
        var shownBars: List<BarState> = emptyList()
        var pipelineBar: BarState? = null
        instrumentation.runOnMainSync {
            val svc = requireNotNull(CleverKeysService.getInstance())
            val found = mutableListOf<SuggestionBar>()
            svc.window?.window?.decorView?.let { barsIn(it, found) }
            shownBars = found.map(::stateOf)
            val handler = CleverKeysService::class.java.getDeclaredField("_suggestionHandler")
                .apply { isAccessible = true }.get(svc)
            val bar = SuggestionHandler::class.java.getDeclaredField("suggestionBar")
                .apply { isAccessible = true }.get(handler) as SuggestionBar?
            pipelineBar = bar?.let(::stateOf)
        }
        Log.i(TAG, "$stage shownBars=$shownBars pipelineBar=$pipelineBar " +
            "themeName=${Config.globalConfig().themeName} layout=${Config.globalConfig().get_current_layout()} " +
            "bindings=${Config.globalConfig().layout_themes} count=${Config.globalConfig().layouts.size}")
        return shownBars to pipelineBar
    }

    /**
     * The device gesture: a real swipe up on the space bar, injected at screen
     * coordinates so the event stream (and its UP after the view is replaced) goes
     * through the window exactly as a finger's would.
     */
    private fun swipeUpOnSpace() {
        val origin = IntArray(2)
        var space: tribixbite.cleverkeys.a11y.KeyboardGeometry.KeyBounds? = null
        instrumentation.runOnMainSync {
            val kb = requireNotNull(keyboardIn(requireNotNull(
                CleverKeysService.getInstance()?.window?.window?.decorView)))
            kb.getLocationOnScreen(origin)
            space = tribixbite.cleverkeys.a11y.KeyboardGeometry.computeKeyRects(
                requireNotNull(kb.getKeyboard()), requireNotNull(kb.geometryParams())
            ).first { it.kv.getKind() == KeyValue.Kind.Char && it.kv.getChar() == ' ' }.bounds
        }
        val bounds = requireNotNull(space)
        val x = origin[0] + (bounds.left + bounds.right) / 2f
        val y0 = origin[1] + (bounds.top + bounds.bottom) / 2f
        val h = (bounds.bottom - bounds.top) * 4f
        Log.i(TAG, "swipeUpOnSpace x=$x y0=$y0 keyHeight=${h / 4f}")
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float, t: Long) {
            val e = MotionEvent.obtain(down, t, action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(e, true)
            e.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, y0, down)
        for (i in 1..10) {
            Thread.sleep(20L)
            inject(MotionEvent.ACTION_MOVE, y0 - i * h * 0.03f, SystemClock.uptimeMillis())
        }
        inject(MotionEvent.ACTION_UP, y0 - h * 0.30f, SystemClock.uptimeMillis())
    }

    private fun hex(color: Int?) = color?.let { String.format("#%08X", it) } ?: "none"

    /**
     * The device run, step for step: Chrome on a data: textarea, text sent with `input text`,
     * the layout switched with a space-bar swipe. Diagnostic only — skipped where the image has
     * no Chrome.
     */
    @Test
    fun chromeTextareaDeviceSequence() {
        org.junit.Assume.assumeTrue("com.android.chrome" in shell("pm list packages com.android.chrome"))
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val saved = prefs.all.filterKeys { it in TOUCHED_KEYS }
        val originalIme = shell("settings get secure default_input_method")
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        try {
            shell("ime enable $component")
            shell("ime set $component")
            shell("sh -c 'echo \"_ --disable-fre --no-default-browser-check --no-first-run\" > /data/local/tmp/chrome-command-line'")
            shell("am set-debug-app --persistent com.android.chrome")
            shell("am start -a android.intent.action.VIEW -n com.android.chrome/com.google.android.apps.chrome.Main " +
                "-d data:text/html,%3Ctextarea%20style=%22width:95vw;height:60vh%22%3E%3C/textarea%3E")
            Thread.sleep(6_000L)
            shell("input tap 540 500")
            waitForShown(true)
            Thread.sleep(1_500L)
            prefs.edit().apply {
                putString("theme", "decorative_neon_cyberpunk")
                LayoutsPreference.saveToPreferences(this, listOf(
                    LayoutsPreference.NamedLayout("latn_qwerty_us"),
                    LayoutsPreference.NamedLayout("latn_qwerty_us_ios_style", theme = "light"),
                ))
                putInt("current_layout_portrait", 0)
                putInt("current_layout_landscape", 0)
                putBoolean("word_prediction_enabled", true)
            }.commit()
            Thread.sleep(2_000L)
            shell("input text hello"); Thread.sleep(2_000L); snapshot("chromeGlobal")
            repeat(5) { shell("input keyevent 67") }; Thread.sleep(1_000L)
            swipeUpOnSpace(); Thread.sleep(1_500L); snapshot("chromeAfterSwitch")
            shell("input text test"); Thread.sleep(2_000L); snapshot("chromeTypedTest")
            shell("input keyevent 67"); Thread.sleep(2_000L)
            val (bars, pipeline) = snapshot("chromeTes")
            repeat(4) { shell("input keyevent 67") }
            swipeUpOnSpace(); Thread.sleep(1_500L)
            shell("input text hello"); Thread.sleep(2_000L); snapshot("chromeBackToGlobal")
            val visible = bars.single { it.shown }
            assertEquals(visible.identity, pipeline?.identity)
            assertTrue("chips on the bound bar: $visible", visible.chips.isNotEmpty())
        } finally {
            shell("am clear-debug-app")
            shell("rm /data/local/tmp/chrome-command-line")
            shell("am force-stop com.android.chrome")
            prefs.edit().apply {
                TOUCHED_KEYS.forEach { remove(it) }
                saved.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }.commit()
            if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
        }
    }

    @Test
    fun boundLayoutSwitchedWhileShownShowsReadableSuggestionsOnTheVisibleBar() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val saved = prefs.all.filterKeys { it in TOUCHED_KEYS }
        val originalIme = shell("settings get secure default_input_method")
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        val wasEnabled = component in shell("ime list -s").lines()
        try {
            fun configure() {
                // Global Cyberpunk; layout 0 follows it, layout 1 is bound to the XML "light" theme.
                prefs.edit().apply {
                    putString("theme", "decorative_neon_cyberpunk")
                    LayoutsPreference.saveToPreferences(
                        this,
                        listOf(
                            LayoutsPreference.NamedLayout("latn_qwerty_us"),
                            LayoutsPreference.NamedLayout("latn_qwerty_us_ios_style", theme = "light"),
                        )
                    )
                    putInt("current_layout_portrait", 0)
                    putInt("current_layout_landscape", 0)
                    putBoolean("word_prediction_enabled", true)
                }.commit()
                Thread.sleep(1_500L)
            }
            shell("ime enable $component")
            shell("ime set $component")
            ActivityScenario.launch(ClipboardEditTestActivity::class.java).use { scenario ->
                lateinit var editor: EditText
                scenario.onActivity { activity ->
                    editor = EditText(activity)
                    activity.setContentView(editor)
                    editor.requestFocus()
                    activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                }
                fun show() {
                    scenario.onActivity { activity ->
                        editor.requestFocus()
                        activity.getSystemService(InputMethodManager::class.java)
                            .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                    }
                    waitForShown(true)
                    Thread.sleep(1_500L)
                }
                fun hide() {
                    scenario.onActivity { activity ->
                        activity.getSystemService(InputMethodManager::class.java)
                            .hideSoftInputFromWindow(editor.windowToken, 0)
                    }
                    waitForShown(false)
                }
                fun clear() {
                    scenario.onActivity {
                        editor.setText("")
                    }
                    Thread.sleep(800L)
                }
                fun typeAndRead(stage: String): Pair<List<BarState>, BarState?> {
                    instrumentation.sendStringSync("tes")
                    Thread.sleep(2_000L)
                    return snapshot(stage)
                }

                show()
                // Written while shown so the live service's preference listener applies it.
                configure()
                snapshot("configured")
                assertEquals(2, Config.globalConfig().layouts.size)
                assertEquals("decorative_neon_cyberpunk", Config.globalConfig().themeName)
                val (globalBars, _) = typeAndRead("global")
                clear()

                // Switch onto the Light-bound layout while the keyboard is shown.
                swipeUpOnSpace()
                Thread.sleep(1_500L)
                snapshot("afterSwitch")
                assertEquals("light", Config.globalConfig().themeName)
                val (switchedBars, switchedPipeline) = typeAndRead("switchedWhileShown")
                clear()

                // First show directly on the bound layout.
                hide(); show()
                val (reshownBars, reshownPipeline) = typeAndRead("shownOnBoundLayout")
                clear()

                // And back to the global theme.
                swipeUpOnSpace()
                Thread.sleep(1_500L)
                val (backBars, _) = typeAndRead("backToGlobal")

                for ((stage, bars, pipeline) in listOf(
                    Triple("switchedWhileShown", switchedBars, switchedPipeline),
                    Triple("shownOnBoundLayout", reshownBars, reshownPipeline),
                )) {
                    val visible = bars.filter { it.shown }
                    assertEquals("$stage: exactly one bar on screen: $bars", 1, visible.size)
                    val bar = visible.single()
                    assertEquals("$stage: the pipeline writes to the bar on screen",
                        bar.identity, pipeline?.identity)
                    assertTrue("$stage: the bar shows suggestions for 'tes': $bar", bar.chips.isNotEmpty())
                    // Light (colorLabel #000000 on colorKey #CCCCCC): the bound theme, not Cyberpunk.
                    assertEquals("$stage: bar background is Light's", 0xCCCCCC, (bar.background ?: 0) and 0xFFFFFF)
                    assertTrue("$stage: ordinary chips use Light's text colour: $bar",
                        bar.chips.drop(1).filter { !it.first.startsWith("+") }.all { it.second == 0xFF000000.toInt() })
                    for ((text, color) in bar.chips) {
                        assertTrue(
                            "$stage: chip '$text' colour ${hex(color)} must differ from the bar " +
                                "background ${hex(bar.background)}",
                            Color.rgb(Color.red(color), Color.green(color), Color.blue(color)) !=
                                bar.background?.let { Color.rgb(Color.red(it), Color.green(it), Color.blue(it)) }
                        )
                    }
                }
                for ((stage, bars) in listOf("global" to globalBars, "backToGlobal" to backBars)) {
                    assertTrue("$stage: suggestions visible: $bars",
                        bars.single { it.shown }.chips.isNotEmpty())
                }
            }
        } finally {
            prefs.edit().apply {
                TOUCHED_KEYS.forEach { remove(it) }
                saved.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }.commit()
            if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
            if (!wasEnabled) shell("ime disable $component")
        }
    }

    private companion object {
        const val TAG = "ThemeBarProbe"
        val TOUCHED_KEYS = setOf(
            "theme", LayoutsPreference.KEY, "current_layout_portrait",
            "current_layout_landscape", "word_prediction_enabled",
        )
    }
}
