package tribixbite.cleverkeys.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.Pointers

/**
 * Roadmap §4.1 render-truth test: the theme editor's preview renders the REAL keyboard
 * ([tribixbite.cleverkeys.Keyboard2View]) with the edited scheme, a colour edit re-renders
 * key and background pixels, and the preview's latched/locked sample state never reaches
 * the IME handler (the settings activity shares the IME's process).
 */
@RunWith(AndroidJUnit4::class)
class ThemeKeyboardPreviewViewTest {

    private lateinit var context: Context
    private val handler = RecordingHandler()

    /** Counts every call the IME would receive; the preview must produce none. */
    private class RecordingHandler : Config.IKeyEventHandler {
        var calls = 0
        override fun key_down(key: KeyValue?, isSwipe: Boolean) { calls++ }
        override fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean) { calls++ }
        override fun mods_changed(mods: Pointers.Modifiers) { calls++ }
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("cleverkeys_theme_preview_test_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putInt("keyboard_opacity", 100)
            .putInt("key_opacity", 100)
            .putInt("key_activated_opacity", 100)
            // A bottom margin guarantees a strip of pure keyboard background to sample. The
            // values are already percentages: without margin_prefs_version Config's one-time
            // dp→percent migration rewrote 5 to 0% and the sampled row landed on a key
            // (ew-cli Pixel7/34, 2026-10-08).
            .putInt("margin_prefs_version", 1)
            .putInt("margin_bottom_portrait", 5)
            .putInt("margin_bottom_landscape", 5)
            .putBoolean("haptic_enabled", false)
            .commit()
        Config.initGlobalConfig(prefs, context.resources, handler, null)
        // ComposeKeyData is deliberately NOT initialised here: Theme Creator can be the
        // process's first component, and the preview must load the compose-key tables itself
        // (its latched-Shift sub-labels read them on every frame). Initialising them in setup
        // hid the cold-process crash that ew-cli caught on 28f7c45e.
    }

    private fun scheme(key: Long, background: Long): KeyboardColorScheme =
        darkKeyboardColorScheme().copy(
            keyDefault = ComposeColor(key),
            keyboardBackground = ComposeColor(background),
        )

    /** Render the hosted keyboard alone, at its natural size, into a software bitmap. */
    private fun render(preview: ThemeKeyboardPreviewView): Bitmap {
        lateinit var bitmap: Bitmap
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val width = context.resources.displayMetrics.widthPixels
            preview.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            preview.layout(0, 0, preview.measuredWidth, preview.measuredHeight)
            val kv = preview.keyboardViewForTest()
            assertTrue("keyboard must lay out", kv.width > 0 && kv.height > 0)
            bitmap = Bitmap.createBitmap(kv.width, kv.height, Bitmap.Config.ARGB_8888)
            kv.draw(Canvas(bitmap))
        }
        return bitmap
    }

    /** Most frequent colour in the rect around [cx],[cy] (labels/sublabels are minority pixels). */
    private fun modeColour(bitmap: Bitmap, cx: Int, cy: Int, half: Int): Int {
        val counts = HashMap<Int, Int>()
        for (x in (cx - half).coerceAtLeast(0)..(cx + half).coerceAtMost(bitmap.width - 1)) {
            for (y in (cy - half).coerceAtLeast(0)..(cy + half).coerceAtMost(bitmap.height - 1)) {
                val c = bitmap.getPixel(x, y)
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }!!.key
    }

    private fun keyCentre(preview: ThemeKeyboardPreviewView): Pair<Int, Int> {
        lateinit var centre: Pair<Int, Int>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val positions = preview.keyboardViewForTest().getRealKeyPositions()
            // A plain letter key that is neither the latched Shift nor a locked modifier.
            val p = positions['g'] ?: positions.values.first()
            centre = p.x.toInt() to p.y.toInt()
        }
        return centre
    }

    /**
     * The preview draws on a HARDWARE recording canvas — the canvas Compose's layer gives the
     * AndroidView in Theme Creator — in a process where the IME never ran. On 28f7c45e the
     * first such draw threw (compose-key tables not loaded) inside Compose's RenderNode
     * recording; the open recording then crashed the next frame with "Recording currently in
     * progress - missing #endRecording() call?".
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q) // RenderNode is API 29+
    fun drawsOnAHardwareCanvasInAColdProcess() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val preview = ThemeKeyboardPreviewView(context)
            preview.bind(scheme(0xFF204060, 0xFF102030), "color,colors,colorful")
            val width = context.resources.displayMetrics.widthPixels
            preview.measure(
                View.MeasureSpec.makeMeasureSpec(width * 4 / 5, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            )
            preview.layout(0, 0, preview.measuredWidth, preview.measuredHeight)
            assertTrue("preview must lay out", preview.width > 0 && preview.height > 0)
            val node = RenderNode("theme-preview-test")
            node.setPosition(0, 0, preview.width, preview.height)
            val canvas = node.beginRecording()
            try {
                assertTrue(canvas.isHardwareAccelerated)
                preview.draw(canvas)
            } finally {
                node.endRecording()
            }
            assertTrue("the recording must hold the drawn preview", node.hasDisplayList())
        }
    }

    @Test
    fun colourEditReRendersKeyAndBackgroundPixels() {
        lateinit var preview: ThemeKeyboardPreviewView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            preview = ThemeKeyboardPreviewView(context)
            preview.bind(scheme(0xFF204060, 0xFF102030), "color,colors,colorful")
        }
        val first = render(preview)
        val (cx, cy) = keyCentre(preview)
        val half = first.width / 40
        val bgX = first.width / 2
        val bgY = first.height - 3

        assertEquals("key fill = edited keyDefault", 0xFF204060.toInt(), modeColour(first, cx, cy, half))
        assertEquals("background = edited keyboardBackground", 0xFF102030.toInt(), first.getPixel(bgX, bgY))

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            preview.bind(scheme(0xFF6A2E8C, 0xFF0B3D1F), "color,colors,colorful")
        }
        val second = render(preview)
        assertEquals("key fill follows the edit", 0xFF6A2E8C.toInt(), modeColour(second, cx, cy, half))
        assertEquals("background follows the edit", 0xFF0B3D1F.toInt(), second.getPixel(bgX, bgY))
        assertNotEquals(first.getPixel(bgX, bgY), second.getPixel(bgX, bgY))
        assertNotNull(preview.currentTheme)
    }

    @Test
    fun previewIsReadOnlyAndNeverReachesTheImeHandler() {
        lateinit var preview: ThemeKeyboardPreviewView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            preview = ThemeKeyboardPreviewView(context)
            preview.bind(scheme(0xFF204060, 0xFF102030), "color,colors,colorful")
        }
        render(preview)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (cx, cy) = preview.keyboardViewForTest().getRealKeyPositions().values.first()
                .let { it.x to it.y }
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, cx, cy, 0)
            val up = MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, cx, cy, 0)
            preview.dispatchTouchEvent(down)
            preview.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            // Representative state is on screen (Shift latched) without any IME callback.
            assertFalse(preview.keyboardViewForTest().isShiftLocked())
            assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                preview.keyboardViewForTest().importantForAccessibility
            )
        }
        assertEquals("the preview must not call the IME handler", 0, handler.calls)
    }
}
