package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.HorizontalScrollView
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
import tribixbite.cleverkeys.theme.KeyboardLooks
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * A keyboard look's suggestion bar on the REAL [SuggestionBar] (2026-10-27, theme/LookBar.kt):
 * three equal slots in visual order with the best candidate (= the highlighted entry, what space
 * commits) in the centre, the typed word left of it (quoted on iOS-style), and a tap on any slot
 * commits that slot's own entry. Non-look themes keep the scrolling chips in ranking order.
 *
 * Also renders the showcase (bar above each look's own layout, light and dark) into the app's
 * external files dir `oct27-bar/` for visual review (pulled with ew-cli `--directories-to-pull`).
 */
@RunWith(AndroidJUnit4::class)
class LookSuggestionBarInstrumentedTest {

    private lateinit var context: Context

    private class NoopHandler : Config.IKeyEventHandler {
        override fun key_down(key: KeyValue?, isSwipe: Boolean) {}
        override fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean) {}
        override fun mods_changed(mods: Pointers.Modifiers) {}
    }

    /** Bar state of typing "tomorr": space corrects to "tomorrow" (moved first, highlighted). */
    private val words = listOf("tomorrow", "tomorrows", "tomorrowland", Suggestion.ExactAdd("tomorr").wire)
    private val scores = listOf(900, 500, 300, 0)

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("cleverkeys_look_bar_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putBoolean("swipe_typing_enabled", false)
            .putBoolean("haptic_enabled", false)
            .putInt("margin_left", 0)
            .putInt("margin_right", 0)
            .commit()
        Config.initGlobalConfig(prefs, context.resources, NoopHandler(), null)
        ComposeKeyData.initialize(context)
    }

    private fun lookTheme(id: String, light: Boolean): Theme =
        ThemeProvider.getInstance(context).lookTheme(KeyboardLooks.byId(id)!!, light)

    /** A bar inside a fill-viewport HorizontalScrollView, as KeyboardComponentGraph builds it. */
    private fun barIn(theme: Theme, widthPx: Int): Pair<HorizontalScrollView, SuggestionBar> {
        val bar = SuggestionBar(context, theme)
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(bar, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        }
        layout(scroll, widthPx, dp(40))
        return scroll to bar
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        // A second pass: the bar sizes its slots to the scroll view's laid-out width.
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    /** The visible suggestion TextViews, left to right. */
    private fun slots(bar: SuggestionBar): List<TextView> =
        (0 until bar.childCount).map { bar.getChildAt(it) }.filterIsInstance<TextView>()

    @Test
    fun iosStyleCentresTheCommitQuotesTheTypedWordAndEverySlotCommitsItsOwnEntry() {
        val committed = mutableListOf<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (scroll, bar) = barIn(lookTheme(KeyboardLooks.IOS, light = true), 1080)
            bar.setOnSuggestionSelectedListener { committed += it }
            bar.setSuggestionsWithScores(words, scores, null, 0, "tomorr")
            layout(scroll, 1080, dp(40))
            val visible = slots(bar)
            assertEquals(listOf("“tomorr”", "tomorrow", "tomorrows"), visible.map { it.text.toString() })
            assertEquals("the data keeps ranking order", words, bar.getCurrentSuggestions())
            val widths = visible.map { it.width }
            assertTrue("three equal slots across the bar: $widths", widths.all { kotlin.math.abs(it - widths[0]) <= 2 } && widths.sum() > 1080 * 0.9)
            assertEquals(android.graphics.Typeface.BOLD, visible[1].typeface.style and android.graphics.Typeface.BOLD)
            visible.forEach { it.performClick() }
        }
        assertEquals("left = the tap-to-add entry, centre = what space commits, right = the next",
            listOf(Suggestion.ExactAdd("tomorr").wire, "tomorrow", "tomorrows"), committed)
    }

    @Test
    fun gboardStyleShowsTheTypedWordUnquotedAndANullHighlightStillCentresTheTop() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (scroll, bar) = barIn(lookTheme(KeyboardLooks.GBOARD, light = false), 1080)
            bar.setSuggestionsWithScores(listOf("hello", "help", "helmet"), listOf(3, 2, 1), null, null, "hel")
            layout(scroll, 1080, dp(40))
            assertEquals(listOf("help", "hello", "helmet"), slots(bar).map { it.text.toString() })
            assertTrue("nothing highlighted: space keeps 'hel'", slots(bar).none { it.typeface?.isBold == true })
            bar.setSuggestionsWithScores(words, scores, null, 0, "tomorr")
            layout(scroll, 1080, dp(40))
            assertEquals(listOf("+tomorr", "tomorrow", "tomorrows"), slots(bar).map { it.text.toString() })
        }
    }

    @Test
    fun promptsAndNonLookThemesKeepTheScrollingChips() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (scroll, bar) = barIn(lookTheme(KeyboardLooks.SAMSUNG, light = true), 1080)
            val offer = listOf(Suggestion.PreferSwipeWord("wet").wire, Suggestion.DeclineSwipePreference("wet").wire)
            bar.setSuggestionsWithScores(offer, null)
            layout(scroll, 1080, dp(40))
            assertEquals(2, slots(bar).size)
            assertTrue(slots(bar)[0].text.toString().contains("wet"))

            val plain = ThemeProvider.getInstance(context).getTheme("cleverkeysdark")
            val (scroll2, bar2) = barIn(plain, 1080)
            bar2.setSuggestionsWithScores(words, scores, null, 0, "tomorr")
            layout(scroll2, 1080, dp(40))
            assertEquals("ranking order, all entries", listOf("tomorrow", "tomorrows", "tomorrowland", "+tomorr"),
                slots(bar2).map { it.text.toString() })
        }
    }

    /** Showcase PNGs: each look's bar over its own familiar-style layout, light and dark. */
    @Test
    fun rendersTheShowcase() {
        val out = File(context.getExternalFilesDir(null), "oct27-bar").apply { mkdirs() }
        val looks = listOf(
            Triple(KeyboardLooks.GBOARD, "latn_qwerty_us_gboard_style", "gboard"),
            Triple(KeyboardLooks.SWIFTKEY, "latn_qwerty_us_swiftkey_style", "swiftkey"),
            Triple(KeyboardLooks.IOS, "latn_qwerty_us_ios_style", "ios"),
            Triple(KeyboardLooks.SAMSUNG, "latn_qwerty_us_samsung_style", "samsung"),
        )
        val width = 1080
        for ((id, layoutName, name) in looks) for (light in listOf(true, false)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val theme = lookTheme(id, light)
                val (scroll, bar) = barIn(theme, width)
                bar.setSuggestionsWithScores(words, scores, null, 0, "tomorr")
                val keyboard = Keyboard2View(context).apply { enterThemePreviewMode() }
                val layout = LayoutsPreference.layoutOfString(context.resources, layoutName)
                assertNotNull(layoutName, layout)
                keyboard.setKeyboard(layout!!)
                keyboard.applyPreviewTheme(theme, theme.swipeTrailColor)
                val stage = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(theme.colorKeyboardBackground.takeIf { it != 0 } ?: Color.BLACK)
                    (scroll.parent as? android.view.ViewGroup)?.removeView(scroll)
                    addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
                    addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                for (pass in 0..1) {
                    stage.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
                    stage.layout(0, 0, stage.measuredWidth, stage.measuredHeight)
                }
                assertEquals("$name: three slots", 3, slots(bar).size)
                val bmp = Bitmap.createBitmap(stage.measuredWidth, stage.measuredHeight, Bitmap.Config.ARGB_8888)
                stage.draw(Canvas(bmp))
                FileOutputStream(File(out, "$name-${if (light) "light" else "dark"}.png")).use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
