package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.theme.BarEmphasis
import tribixbite.cleverkeys.theme.KeyboardLooks
import tribixbite.cleverkeys.theme.LookBar
import tribixbite.cleverkeys.theme.ThemeColorRole
import tribixbite.cleverkeys.theme.ThemeKeyboardPreviewView
import tribixbite.cleverkeys.theme.ThemeProvider
import tribixbite.cleverkeys.theme.ThemeSpec
import tribixbite.cleverkeys.theme.darkKeyboardColorScheme

/**
 * The 2026-10-30 look polish drawn by the REAL renderer: Gboard-style's filled Shift (shifted,
 * Caps Lock), SwiftKey-style's comma / period legends, Samsung-style's function-key text, and the
 * Theme Creator preview bar arranged by a custom theme. Asserts the drawn differences and writes
 * PNGs to the app's external files dir `oct30-looks/` (pulled with ew-cli `--directories-to-pull`)
 * for comparison with the product references (research §13).
 */
@RunWith(AndroidJUnit4::class)
class LookKeyMarksShowcaseTest {

    private lateinit var context: Context
    private lateinit var out: File

    private class NoopHandler : Config.IKeyEventHandler {
        override fun key_down(key: KeyValue?, isSwipe: Boolean) {}
        override fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean) {}
        override fun mods_changed(mods: Pointers.Modifiers) {}
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("cleverkeys_look_marks_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putBoolean("swipe_typing_enabled", false)
            .putBoolean("haptic_enabled", false)
            .putInt("margin_left", 0)
            .putInt("margin_right", 0)
            .commit()
        Config.initGlobalConfig(prefs, context.resources, NoopHandler(), null)
        ComposeKeyData.initialize(context)
        out = File(context.getExternalFilesDir(null), "oct30-looks").apply { mkdirs() }
    }

    private fun lookTheme(id: String, light: Boolean): Theme =
        ThemeProvider.getInstance(context).lookTheme(KeyboardLooks.byId(id)!!, light)

    private fun layoutNamed(name: String): KeyboardData {
        val layout = LayoutsPreference.layoutOfString(context.resources, name)
            ?: context.resources.getIdentifier(name, "raw", context.packageName).takeIf { it != 0 }
                ?.let { KeyboardData.load(context.resources, it) }
        assertNotNull(name, layout)
        return LayoutModifier.modify_layout(layout!!)
    }

    /** [layout] drawn with [theme] at 1080 px, after [prepare] set key states. */
    private fun render(theme: Theme, layout: KeyboardData, prepare: (Keyboard2View) -> Unit = {}): Bitmap {
        lateinit var bmp: Bitmap
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val keyboard = Keyboard2View(context).apply { enterThemePreviewMode() }
            keyboard.setKeyboard(layout)
            keyboard.applyPreviewTheme(theme, theme.swipeTrailColor)
            prepare(keyboard)
            bmp = draw(keyboard, theme.colorKeyboardBackground)
        }
        return bmp
    }

    private fun draw(view: View, background: Int): Bitmap {
        val stage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background.takeIf { it != 0 } ?: Color.BLACK)
            addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        for (pass in 0..1) {
            stage.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
            stage.layout(0, 0, stage.measuredWidth, stage.measuredHeight)
        }
        val bmp = Bitmap.createBitmap(stage.measuredWidth, stage.measuredHeight, Bitmap.Config.ARGB_8888)
        stage.draw(Canvas(bmp))
        return bmp
    }

    private fun save(bmp: Bitmap, name: String) {
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Pixels within ±[tol] per channel of [color]. */
    private fun count(bmp: Bitmap, color: Int, tol: Int = 24): Int {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px.count { p ->
            kotlin.math.abs(Color.red(p) - Color.red(color)) <= tol &&
                kotlin.math.abs(Color.green(p) - Color.green(color)) <= tol &&
                kotlin.math.abs(Color.blue(p) - Color.blue(color)) <= tol
        }
    }

    private fun mode(light: Boolean) = if (light) "light" else "dark"

    @Test
    fun gboardStyleFillsShiftWhileShiftedAndInCapsLock() {
        val layout = layoutNamed("latn_qwerty_us_gboard_style")
        for (light in listOf(true, false)) {
            val theme = lookTheme(KeyboardLooks.GBOARD, light)
            val rest = render(theme, layout)
            val shifted = render(theme, layout) { it.set_shift_state(true, false) }
            val locked = render(theme, layout) { it.set_shift_state(true, true) }
            save(rest, "gboard-shift-rest-${mode(light)}")
            save(shifted, "gboard-shift-shifted-${mode(light)}")
            save(locked, "gboard-shift-capslock-${mode(light)}")
            // Shift is the left key of the third row: the filled arrow inks far more of it than
            // the outline glyph does.
            val restInk = shiftInk(rest)
            val shiftedInk = shiftInk(shifted)
            val lockedInk = shiftInk(locked)
            assertTrue("${mode(light)}: filled arrow ($restInk -> $shiftedInk)", shiftedInk > restInk * 3 / 2)
            assertTrue("${mode(light)}: Caps Lock arrow and bar ($restInk -> $lockedInk)", lockedInk > restInk * 3 / 2)
        }
    }

    /**
     * Ink pixels of the Shift key's cell (left 14 % of the width, the third of four rows): pixels
     * whose luminance is more than 60 away from the cell's median (the key fill).
     */
    private fun shiftInk(bmp: Bitmap): Int {
        val x0 = (bmp.width * 0.03f).toInt()
        val x1 = (bmp.width * 0.12f).toInt()
        val y0 = (bmp.height * 0.53f).toInt()
        val y1 = (bmp.height * 0.72f).toInt()
        val lum = ArrayList<Int>()
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = bmp.getPixel(x, y)
            lum.add((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000)
        }
        val median = lum.sorted()[lum.size / 2]
        return lum.count { kotlin.math.abs(it - median) > 60 }
    }

    @Test
    fun swiftkeyStyleDrawsTheMicAndPunctuationLegends() {
        val config = Config.globalConfig()
        val offered = config.shouldOfferVoiceTyping
        try {
            for (light in listOf(true, false)) {
                val theme = lookTheme(KeyboardLooks.SWIFTKEY, light)
                // A phone with a voice input method: the comma carries voice typing (`loc voice_typing`).
                config.shouldOfferVoiceTyping = true
                val withVoice = render(theme, layoutNamed("latn_qwerty_us_swiftkey_style"))
                save(withVoice, "swiftkey-legends-${mode(light)}")
                // None: the layout drops the voice key, and the mic legend with it.
                config.shouldOfferVoiceTyping = false
                val noVoice = render(theme, layoutNamed("latn_qwerty_us_swiftkey_style"))
                save(noVoice, "swiftkey-legends-novoice-${mode(light)}")
                val hint = theme.subLabelColor
                val inkWith = count(withVoice, hint, tol = 6)
                val inkWithout = count(noVoice, hint, tol = 6)
                assertTrue("${mode(light)}: ,!? drawn in the hint colour ($inkWithout)", inkWithout > 0)
                assertTrue("${mode(light)}: the mic adds hint ink ($inkWithout -> $inkWith)", inkWith > inkWithout)
            }
        } finally {
            config.shouldOfferVoiceTyping = offered
        }
        assertEquals(KeyboardLooks.swiftkeyLook.keyLegends, lookTheme(KeyboardLooks.SWIFTKEY, true).lookKeyLegends)
    }

    @Test
    fun samsungStyleFunctionKeyText() {
        for (light in listOf(true, false)) {
            val theme = lookTheme(KeyboardLooks.SAMSUNG, light)
            save(render(theme, layoutNamed("latn_qwerty_us_samsung_style")), "samsung-letters-${mode(light)}")
            save(render(theme, layoutNamed("symbols_samsung_1")), "samsung-symbols1-${mode(light)}")
        }
    }

    /** The Theme Creator preview redraws its bar with a custom theme's arrangement. */
    @Test
    fun themeCreatorPreviewShowsTheBarArrangement() {
        val variants = listOf(
            "creator-bar-chips" to ThemeSpec(),
            "creator-bar-ios" to ThemeSpec(
                bar = LookBar(slots = 3, quoteTyped = true),
                colors = mapOf(ThemeColorRole.BAR_SEPARATOR to 0x66FFFFFF),
            ),
            "creator-bar-swiftkey" to ThemeSpec(
                bar = LookBar(slots = 3, emphasis = BarEmphasis.CENTRE),
                colors = mapOf(ThemeColorRole.BAR_SEPARATOR to 0),
            ),
            "creator-bar-five" to ThemeSpec(bar = LookBar(slots = 5, centreBest = false)),
        )
        for ((name, ext) in variants) {
            lateinit var texts: List<String>
            lateinit var bmp: Bitmap
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val preview = ThemeKeyboardPreviewView(context)
                preview.bind(darkKeyboardColorScheme(), "color,colors,colorful", ext.takeIf { !it.isEmpty })
                bmp = draw(preview, Color.BLACK)
                texts = textViews(preview).filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }.map { it.text.toString() }
            }
            save(bmp, name)
            when (name) {
                "creator-bar-chips" -> assertEquals(listOf("color", "colors", "colorful"), texts)
                "creator-bar-ios" -> assertEquals(listOf("“colors”", "color", "colorful"), texts)
                "creator-bar-swiftkey" -> assertEquals(listOf("colors", "color", "colorful"), texts)
                "creator-bar-five" -> assertEquals(listOf("color", "colors", "colorful"), texts)
            }
        }
    }

    private fun textViews(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textViews(v.getChildAt(it)) }
        else -> emptyList()
    }

    private companion object {
        const val WIDTH = 1080
    }
}
