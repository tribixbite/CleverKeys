package tribixbite.cleverkeys

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.theme.EmbeddedThemes
import tribixbite.cleverkeys.theme.ThemeColorRole

/**
 * The worked example layout (androidTest assets layouts/harbour.xml — the wiki's
 * docs/wiki/layouts/examples/harbour.xml) in the REAL keyboard: the user's global theme is
 * Cyberpunk, a space-bar swipe switches onto the example, and the keyboard view on screen must
 * draw the layout's own `<theme>` (key colour, light or dark variant by the system mode) and its
 * per-row / per-key fills — read back from the rendered pixels at the keys' hit-test cells —
 * then a swipe back restores the global theme. Leaves no trace (preferences and IME restored).
 */
@RunWith(AndroidJUnit4::class)
class LayoutXmlThemeKeyboardInstrumentedTest {

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

    private fun keyboard(): Keyboard2View =
        requireNotNull(keyboardIn(requireNotNull(CleverKeysService.getInstance()?.window?.window?.decorView)))

    /** A real swipe up on the space bar, injected at screen coordinates. */
    private fun swipeUpOnSpace() {
        val origin = IntArray(2)
        var space: KeyboardGeometry.KeyBounds? = null
        instrumentation.runOnMainSync {
            val kb = keyboard()
            kb.getLocationOnScreen(origin)
            space = KeyboardGeometry.computeKeyRects(requireNotNull(kb.getKeyboard()), requireNotNull(kb.geometryParams()))
                .first { it.kv.getKind() == KeyValue.Kind.Char && it.kv.getChar() == ' ' }.bounds
        }
        val b = requireNotNull(space)
        val x = origin[0] + (b.left + b.right) / 2f
        val y0 = origin[1] + (b.top + b.bottom) / 2f
        val h = (b.bottom - b.top) * 4f
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

    /** The rendered colour 15 % into the key whose centre value is [c], at its vertical centre. */
    private fun fillOf(bitmap: Bitmap, rects: List<KeyboardGeometry.KeyRect>, match: (KeyValue) -> Boolean): Int {
        val r = rects.first { match(it.kv) }.bounds
        val px = (r.left + (r.right - r.left) * 0.15f).toInt()
        val py = ((r.top + r.bottom) / 2f).toInt()
        return bitmap.getPixel(px, py)
    }

    private fun near(a: Int, b: Int, tolerance: Int = 6): Boolean =
        (0..16 step 8).all { kotlin.math.abs(((a ushr it) and 0xFF) - ((b ushr it) and 0xFF)) <= tolerance }

    private fun hex(c: Int) = String.format("#%08X", c)

    @Test
    fun theExampleLayoutDrawsItsOwnThemeAndKeyStylesAndSwitchingBackRestoresTheGlobalTheme() {
        val xml = instrumentation.context.assets.open("layouts/harbour.xml").bufferedReader().readText()
        val spec = requireNotNull(KeyboardData.load_string(xml)?.embedded_theme)
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val keys = setOf("theme", LayoutsPreference.KEY, "current_layout_portrait", "current_layout_landscape")
        val saved = prefs.all.filterKeys { it in keys }
        val originalIme = shell("settings get secure default_input_method")
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        try {
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
                scenario.onActivity { activity ->
                    activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
                waitFor("keyboard shown") { CleverKeysService.getInstance()?.isInputViewShown == true }
                prefs.edit().apply {
                    putString("theme", "decorative_neon_cyberpunk")
                    LayoutsPreference.saveToPreferences(this, listOf(
                        LayoutsPreference.NamedLayout("latn_qwerty_us"),
                        LayoutsPreference.CustomLayout.parse(xml),
                    ))
                    putInt("current_layout_portrait", 0)
                    putInt("current_layout_landscape", 0)
                }.commit()
                Thread.sleep(1_500L)
                assertEquals("decorative_neon_cyberpunk", Config.globalConfig().themeName)

                swipeUpOnSpace()
                waitFor("the example layout's theme") { Config.globalConfig().layout_embedded_theme != null }
                Thread.sleep(1_000L)
                val themeName = Config.globalConfig().themeName
                assertEquals(EmbeddedThemes.Parsed(spec, "decorative_neon_cyberpunk"), EmbeddedThemes.parse(themeName))

                val light = Config.isSystemLight(context.resources)
                val expectedKey = (if (light) spec.colorsFor(true) else spec.colorsFor(false)).getValue(ThemeColorRole.KEY)
                var bitmap: Bitmap? = null
                var rects: List<KeyboardGeometry.KeyRect> = emptyList()
                var colorKey = 0
                instrumentation.runOnMainSync {
                    val kb = keyboard()
                    colorKey = kb.getTheme().colorKey
                    rects = KeyboardGeometry.computeKeyRects(requireNotNull(kb.getKeyboard()), requireNotNull(kb.geometryParams()))
                    bitmap = Bitmap.createBitmap(kb.width, kb.height, Bitmap.Config.ARGB_8888).also { kb.draw(Canvas(it)) }
                }
                Log.i(TAG, "themeName=$themeName light=$light colorKey=${hex(colorKey)}")
                assertTrue("theme key colour ${hex(colorKey)} ≈ ${hex(expectedKey)}", near(colorKey, expectedKey, 1))
                val bmp = requireNotNull(bitmap)
                val g = fillOf(bmp, rects) { it.getKind() == KeyValue.Kind.Char && it.getChar() == 'g' }
                val z = fillOf(bmp, rects) { it.getKind() == KeyValue.Kind.Char && it.getChar() == 'z' }
                val shift = fillOf(bmp, rects) { it.getKind() == KeyValue.Kind.Modifier }
                Log.i(TAG, "g=${hex(g)} z=${hex(z)} shift=${hex(shift)}")
                assertTrue("an unstyled key draws the theme's key colour: ${hex(g)}", near(g, expectedKey))
                assertTrue("<row key_background> reaches its keys: ${hex(z)}", near(z, 0xFF3E6A93.toInt()))
                assertTrue("<key key_background> beats its row's: ${hex(shift)}", near(shift, 0xFF1F6FB2.toInt()))

                swipeUpOnSpace()
                waitFor("back to the global theme") { Config.globalConfig().themeName == "decorative_neon_cyberpunk" }
                waitFor("the global theme's keyboard view") { keyboard().getTheme().colorKey != colorKey }
            }
        } finally {
            prefs.edit().apply {
                keys.forEach { remove(it) }
                saved.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                    }
                }
            }.commit()
            if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
        }
    }

    private companion object {
        const val TAG = "LayoutXmlThemeKeyboardTest"
    }
}
