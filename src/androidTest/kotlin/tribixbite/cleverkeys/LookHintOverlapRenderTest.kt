package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Corner legends must never overlap (Pixel 2026-10-27, todo item 25 round 3): with "Show gesture
 * hints on familiar looks" on, the 1-wide emoji key of the Gboard-style layout and the comma key
 * of the Samsung-style layout carry a bottom-centre "ABC" (switch_back_clipboard) beside a
 * bottom-right "GIF" (switch_gif), and the two ran into each other.
 *
 * Measures what the REAL [Keyboard2View] paints: a [Canvas] subclass records every drawText with
 * its x, baseline, alignment, [Paint.measureText] and ink bounds at the size actually used,
 * and every pair of corner/edge labels (all text smaller than the frame's main labels) must have
 * disjoint bounds.
 */
@RunWith(AndroidJUnit4::class)
class LookHintOverlapRenderTest {

    private class NoopHandler : Config.IKeyEventHandler {
        override fun key_down(key: KeyValue?, isSwipe: Boolean) {}
        override fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean) {}
        override fun mods_changed(mods: Pointers.Modifiers) {}
    }

    /** One painted string's box in canvas pixels. */
    private data class Drawn(val text: String, val left: Float, val right: Float, val top: Float, val bottom: Float, val size: Float)

    /** Records the bounds of every string drawn (and still draws it). */
    private class BoundsCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val drawn = mutableListOf<Drawn>()

        private fun record(text: String, x: Float, y: Float, paint: Paint) {
            if (text.isBlank()) return
            val w = paint.measureText(text)
            val left = when (paint.textAlign) {
                Paint.Align.LEFT -> x
                Paint.Align.CENTER -> x - w / 2f
                Paint.Align.RIGHT -> x - w
                null -> x
            }
            // Vertical extent = the glyphs' ink (font-metric boxes include line spacing and would
            // flag a main label near a corner hint that does not actually touch it).
            val ink = android.graphics.Rect()
            paint.getTextBounds(text, 0, text.length, ink)
            drawn += Drawn(text, left, left + w, y + ink.top, y + ink.bottom, paint.textSize)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            record(text, x, y, paint); super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            record(text.substring(start, end), x, y, paint); super.drawText(text, start, end, x, y, paint)
        }

        override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            record(text.subSequence(start, end).toString(), x, y, paint); super.drawText(text, start, end, x, y, paint)
        }

        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
            record(String(text, index, count), x, y, paint); super.drawText(text, index, count, x, y, paint)
        }
    }

    private fun drawLayout(context: Context, theme: String, layoutName: String, widthPx: Int): List<Drawn> {
        val prefs = context.getSharedPreferences("cleverkeys_hint_overlap_test_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putString("theme", theme)
            .putBoolean("look_show_hints", true)
            .putBoolean("swipe_typing_enabled", false)
            .putBoolean("haptic_enabled", false)
            .putInt("margin_left", 0)
            .putInt("margin_right", 0)
            .commit()
        Config.initGlobalConfig(prefs, context.resources, NoopHandler(), null)
        ComposeKeyData.initialize(context)
        lateinit var canvas: BoundsCanvas
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = Keyboard2View(context)
            val layout = LayoutsPreference.layoutOfString(context.resources, layoutName)
            assertNotNull("$layoutName must load", layout)
            view.setKeyboard(layout!!)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            canvas = BoundsCanvas(Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888))
            view.draw(canvas)
        }
        return canvas.drawn
    }

    /** Pairs of small (legend) texts whose boxes intersect. */
    private fun overlaps(drawn: List<Drawn>): List<String> {
        val mainSize = drawn.maxOf { it.size }
        val legends = drawn.filter { it.size < mainSize * 0.8f }
        val out = mutableListOf<String>()
        for (i in legends.indices) for (j in i + 1 until legends.size) {
            val a = legends[i]
            val b = legends[j]
            val h = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val v = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (h > 0.5f && v > 0.5f) out += "'${a.text}' [${a.left}..${a.right}] × '${b.text}' [${b.left}..${b.right}]"
        }
        return out
    }

    private fun assertNoOverlap(theme: String, layoutName: String, widthPx: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val drawn = drawLayout(context, theme, layoutName, widthPx)
        assertTrue("$layoutName drew its legends", drawn.any { it.text == "GIF" } && drawn.any { it.text == "ABC" })
        val bad = overlaps(drawn)
        assertTrue("$layoutName @ $widthPx px under $theme: legends overlap: $bad", bad.isEmpty())
    }

    @Test
    fun gboardStyleEmojiKeyLegendsDoNotOverlap() {
        assertNoOverlap("look_gboard", "latn_qwerty_us_gboard_style", 1080)
        assertNoOverlap("look_gboard", "latn_qwerty_us_gboard_style", 720)
    }

    @Test
    fun samsungStyleCommaKeyLegendsDoNotOverlap() {
        assertNoOverlap("look_samsung", "latn_qwerty_us_samsung_style", 1080)
        assertNoOverlap("look_samsung", "latn_qwerty_us_samsung_style", 720)
    }
}
