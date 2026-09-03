package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Render-truth test for the #171 overlay defect: when a custom short-swipe mapping
 * occupies a sublabel slot, [Keyboard2View.onDraw] must draw ONLY the custom glyph
 * there — not the layout's default glyph underneath it (the reporter's screenshot
 * shows "!" painted on top of the still-visible default "~" on `q`'s NW corner).
 *
 * Drives the REAL view: a standalone [Keyboard2View] (same fixture as
 * `KeyboardAccessibilityInstrumentedTest`), the qwerty layout (whose `q` carries the
 * only "~" in the layout, at nw), the real singleton
 * [ShortSwipeCustomizationManager], and a bitmap-backed [RecordingCanvas] whose
 * drawText overloads record every string the frame paints.
 */
@RunWith(AndroidJUnit4::class)
class Keyboard2ViewCustomMappingRenderTest {

    private lateinit var context: Context
    private lateinit var view: Keyboard2View
    private lateinit var manager: ShortSwipeCustomizationManager

    /** "±" appears nowhere in latn_qwerty_us, so its draw count isolates the custom glyph. */
    private val customGlyph = "±"

    private class NoopHandler : Config.IKeyEventHandler {
        override fun key_down(key: KeyValue?, isSwipe: Boolean) {}
        override fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean) {}
        override fun mods_changed(mods: Pointers.Modifiers) {}
    }

    /** Records every string any drawText overload paints; still really draws (software canvas). */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val texts = mutableListOf<String>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += text
            super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += text.substring(start, end)
            super.drawText(text, start, end, x, y, paint)
        }

        override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += text.subSequence(start, end).toString()
            super.drawText(text, start, end, x, y, paint)
        }

        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
            texts += String(text, index, count)
            super.drawText(text, index, count, x, y, paint)
        }
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = ShortSwipeCustomizationManager.getInstance(context)
        runBlocking { manager.loadMappings() }
        // The fixture owns q:NW for the duration of the test.
        runBlocking { manager.removeMapping("q", SwipeDirection.NW) }

        val prefs = context.getSharedPreferences("cleverkeys_render_test_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("swipe_typing_enabled", false)
            .putBoolean("short_gestures_enabled", true)
            .putBoolean("haptic_enabled", false)
            .putInt("margin_left", 0)
            .putInt("margin_right", 0)
            .putInt("margin_top", 0)
            .putInt("margin_bottom", 0)
            .apply()
        Config.initGlobalConfig(prefs, context.resources, NoopHandler(), null)
        ComposeKeyData.initialize(context)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = Keyboard2View(context)
            val layout = LayoutsPreference.layoutOfString(context.resources, "latn_qwerty_us")
            assertNotNull("qwerty layout must load", layout)
            view.setKeyboard(layout!!)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
    }

    @After
    fun tearDown() {
        runBlocking { manager.removeMapping("q", SwipeDirection.NW) }
    }

    private fun drawFrame(): List<String> {
        val canvas = RecordingCanvas(Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.draw(canvas) }
        return canvas.texts
    }

    /** Fixture sanity: with no custom mapping, the default "~" is painted exactly once. */
    @Test
    fun withoutCustomMapping_defaultNwGlyphIsDrawn() {
        val texts = drawFrame()
        assertEquals("the qwerty default nw \"~\" on q must be drawn", 1, texts.count { it == "~" })
        assertEquals("the custom glyph must not appear yet", 0, texts.count { it == customGlyph })
    }

    /**
     * #171 (render half): with a custom mapping on q:NW, the frame must contain the
     * custom glyph and must NOT contain the overridden default "~" — the two were
     * previously painted overlaid in the same slot.
     */
    @Test
    fun customMapping_replacesDefaultGlyph_notOverlaid() {
        runBlocking {
            manager.setMapping(ShortSwipeMapping.textInput("q", SwipeDirection.NW, customGlyph, "!"))
        }

        val texts = drawFrame()

        assertEquals("the custom glyph must be drawn once", 1, texts.count { it == customGlyph })
        assertEquals(
            "#171: the overridden default \"~\" must NOT be drawn under the custom glyph",
            0, texts.count { it == "~" }
        )
    }

    /** Only the covered slot is hidden: q's OTHER defaults (sw "`") keep rendering. */
    @Test
    fun customMapping_hidesOnlyItsOwnSlot() {
        runBlocking {
            manager.setMapping(ShortSwipeMapping.textInput("q", SwipeDirection.NW, customGlyph, "!"))
        }

        val texts = drawFrame()

        assertTrue(
            "q's uncovered sw default \"`\" must still be drawn",
            texts.count { it == "`" } >= 1
        )
        assertEquals("the custom glyph must be drawn once", 1, texts.count { it == customGlyph })
    }
}
