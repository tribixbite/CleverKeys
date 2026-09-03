package tribixbite.cleverkeys

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Three release-record rows about the suggestion bar's own chrome, all PRESENT-UNTESTED.
 *
 * | version | note | anchor |
 * |---|---|---|
 * | v1.0.7  | "Fixed suggestion bar collapse when empty" | `SuggestionBar.kt#alwaysVisible` |
 * | v1.1.79 | "Eye toggle to show/hide password text in suggestion bar" | `SuggestionBar.kt#SuggestionBar` |
 * | v1.1.79 | "Scrollable password display with fixed icon position" | `SuggestionBar.kt#SuggestionBar` |
 *
 * Instrumented rather than JVM because every one of these is a real `View` fact: measured
 * geometry, a resolved `RelativeLayout` child arrangement, a click dispatched through
 * `performClick()`, and a `TextView`'s rendered text. `SuggestionBar` extends `LinearLayout`
 * and builds its password UI
 * from live `ImageView` / `HorizontalScrollView` instances, none of which exist under the
 * android.jar stubs `runMockTests` uses. Sits next to [SuggestionBarAutofillTest], which already
 * covers the #109 padding half of password mode.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionBarVisibilityAndPasswordTest {

    private lateinit var context: Context
    private lateinit var bar: SuggestionBar

    /** The text the fake field currently holds; the bar reads it through the provider. */
    private var fieldText = ""

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bar = SuggestionBar(context)
        }
    }

    // ==================== v1.0.7: the bar does not collapse when empty ====================

    @Test
    fun emptySuggestionsLeaveTheBarVisible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bar.setSuggestions(listOf("alpha", "beta"))
            assertEquals("bar with content is visible", View.VISIBLE, bar.visibility)

            bar.setSuggestions(emptyList())

            // The v1.0.7 fix: an empty prediction set must NOT collapse the bar, or the whole
            // keyboard jumps up and down by the bar's height on every keystroke that produces
            // no candidates.
            assertEquals(
                "empty suggestions must leave the bar VISIBLE (alwaysVisible)",
                View.VISIBLE, bar.visibility
            )
        }
    }

    @Test
    fun clearingSuggestionsLeavesTheBarVisible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bar.setSuggestions(listOf("alpha"))
            bar.clearSuggestions()

            assertEquals(
                "clearSuggestions() must not collapse the bar either",
                View.VISIBLE, bar.visibility
            )
            assertEquals("and it really is empty", 0, bar.childCount)
        }
    }

    @Test
    fun aNullSuggestionListLeavesTheBarVisible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bar.setSuggestions(listOf("alpha"))
            bar.setSuggestions(null)

            assertEquals(View.VISIBLE, bar.visibility)
        }
    }

    @Test
    fun anEmptyBarStillOccupiesItsRowRatherThanDisappearing() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bar.setSuggestions(emptyList())
            layOutBar(widthPx = 720)

            // GONE would remove the bar from layout entirely and the keyboard would jump up by
            // its height; VISIBLE with a real measured height is the fixed behaviour. (The row
            // height itself is imposed by the parent topPane, not by the bar's content, which
            // is why this asserts occupancy rather than content-independent height.)
            assertEquals(View.VISIBLE, bar.visibility)
            assertTrue("an empty but visible bar still measures a row", bar.measuredHeight > 0)
            assertEquals("and it genuinely holds no suggestions", 0, bar.childCount)
        }
    }

    // ==================== v1.1.79: the eye toggle ====================

    @Test
    fun passwordModeShowsAnEyeToggleWithAnAccessibleLabel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bar.setPasswordMode(true)

            val eye = eyeToggle()
            assertEquals(
                "the toggle must be labelled for TalkBack",
                "Toggle password visibility", eye.contentDescription?.toString()
            )
            assertTrue("and it must be clickable", eye.isClickable)
            assertEquals("36dp square", dp(36), eye.layoutParams.width)
            assertEquals("36dp square", dp(36), eye.layoutParams.height)
        }
    }

    @Test
    fun theEyeToggleSwitchesBetweenBulletsAndThePlaintextPassword() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fieldText = "hunter2"
            bar.setInputConnectionProvider(fakeField())
            bar.setPasswordMode(true)
            bar.syncPasswordWithField()

            val display = passwordTextView()
            assertEquals(
                "before the toggle the password is masked, one bullet per character",
                "●●●●●●●", display.text.toString()
            )

            eyeToggle().performClick()
            assertEquals(
                "one tap reveals the real text — this is the whole v1.1.79 feature",
                "hunter2", display.text.toString()
            )

            eyeToggle().performClick()
            assertEquals(
                "a second tap hides it again",
                "●●●●●●●", display.text.toString()
            )
        }
    }

    @Test
    fun theEyeIconItselfChangesWithTheState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fieldText = "abc"
            bar.setInputConnectionProvider(fakeField())
            bar.setPasswordMode(true)

            val hidden = eyeToggle().drawable
            eyeToggle().performClick()
            val shown = eyeToggle().drawable

            // Without this the control would silently lie about which state it is in.
            assertNotEquals(
                "the crossed-out and open eye drawables must differ",
                hidden.constantState, shown.constantState
            )
        }
    }

    @Test
    fun revealingRereadsTheFieldSoAnEditMadeElsewhereIsShown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fieldText = "old"
            bar.setInputConnectionProvider(fakeField())
            bar.setPasswordMode(true)
            bar.syncPasswordWithField()
            assertEquals("●●●", passwordTextView().text.toString())

            // The user moved the cursor and retyped in the app itself; the bar keeps no
            // authoritative copy, it re-reads the InputConnection on every toggle.
            fieldText = "brand-new"

            eyeToggle().performClick()
            assertEquals("brand-new", passwordTextView().text.toString())
        }
    }

    @Test
    fun leavingPasswordModeTearsTheToggleDownAndForgetsTheText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fieldText = "secret"
            bar.setInputConnectionProvider(fakeField())
            bar.setPasswordMode(true)
            bar.syncPasswordWithField()
            eyeToggle().performClick()
            assertEquals("secret", passwordTextView().text.toString())

            bar.setPasswordMode(false)

            assertTrue("password mode is off", !bar.isInPasswordMode())
            assertEquals("every password view is removed from the bar", 0, bar.childCount)

            // Re-entering must start MASKED, never carry the previous reveal over.
            bar.setPasswordMode(true)
            bar.syncPasswordWithField()
            assertEquals("●●●●●●", passwordTextView().text.toString())
        }
    }

    @Test
    fun anEmptyPasswordRendersAsEmptyRatherThanAStrayBullet() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fieldText = ""
            bar.setInputConnectionProvider(fakeField())
            bar.setPasswordMode(true)
            bar.syncPasswordWithField()

            assertEquals("", passwordTextView().text.toString())
            assertEquals(
                "the row stays laid out so the bar does not resize as typing starts",
                View.VISIBLE, passwordTextView().visibility
            )
        }
    }

    // ============ v1.1.79: scrollable display, fixed icon position ============

    @Test
    fun theDisplayIsInAHorizontalScrollViewBoundedByTheIcon() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fieldText = "abcdef"
            bar.setInputConnectionProvider(fakeField())
            bar.setPasswordMode(true)
            bar.syncPasswordWithField()
            layOutBar(widthPx = 720)

            val container = passwordContainer()
            val eye = eyeToggle()
            val scroller = horizontalScrollView()

            // Structure: one RelativeLayout, icon added first so the scroll view can be
            // constrained against its id.
            assertEquals("the icon is added before the scroller", 0, container.indexOfChild(eye))
            assertEquals("both live in the same RelativeLayout", 1, container.indexOfChild(scroller))

            // Asserted as resolved GEOMETRY rather than as the RelativeLayout rule array:
            // RelativeLayout rewrites START/END verbs into LEFT/RIGHT while resolving layout
            // direction, so the rule array is an implementation detail while the pixels are
            // the claim.
            assertEquals(
                "the icon is pinned to the end of the bar, one 8dp margin in from the edge",
                container.width - dp(8), eye.right
            )
            assertEquals("and is its 36dp width", dp(36), eye.right - eye.left)
            assertEquals(
                "the scroll view starts at the left edge",
                0, scroller.left
            )
            assertTrue(
                "the scroll view must END at or before the icon — that boundary is what stops " +
                    "long password text from pushing the icon off screen " +
                    "(scroller.right=${scroller.right}, eye.left=${eye.left})",
                scroller.right <= eye.left
            )
            assertTrue(
                "and the display must actually be inside a HorizontalScrollView",
                passwordTextView().parent.let { it is ViewGroup && it.parent === scroller }
            )
        }
    }

    @Test
    fun aLongPasswordScrollsInsteadOfMovingTheIcon() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fieldText = "a".repeat(4)
            bar.setInputConnectionProvider(fakeField())
            bar.setPasswordMode(true)
            bar.syncPasswordWithField()
            layOutBar(widthPx = 720)
            val iconLeftWhenShort = eyeToggle().left

            fieldText = "a".repeat(200)
            bar.syncPasswordWithField()
            layOutBar(widthPx = 720)
            val iconLeftWhenLong = eyeToggle().left

            assertEquals(
                "a 200-character password must not shift the eye icon by a single pixel — " +
                    "that is exactly what 'fixed icon position' means",
                iconLeftWhenShort, iconLeftWhenLong
            )

            val scroller = horizontalScrollView()
            assertTrue(
                "and the text must overflow the scroll view (so it scrolls) rather than " +
                    "stretching it: content ${scroller.getChildAt(0).measuredWidth} vs " +
                    "viewport ${scroller.measuredWidth}",
                scroller.getChildAt(0).measuredWidth > scroller.measuredWidth
            )
        }
    }

    @Test
    fun aShortPasswordDoesNotOverflowTheViewport() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fieldText = "ab"
            bar.setInputConnectionProvider(fakeField())
            bar.setPasswordMode(true)
            bar.syncPasswordWithField()
            layOutBar(widthPx = 720)

            val scroller = horizontalScrollView()
            assertTrue(
                "fillViewport keeps a short password centred rather than left-hugging",
                scroller.isFillViewport
            )
            assertTrue(
                "nothing to scroll for two characters",
                scroller.getChildAt(0).measuredWidth <= scroller.measuredWidth
            )
        }
    }

    // ==================== helpers ====================

    /**
     * The SAME rounding `SuggestionBar.dpToPx` uses. `kotlin.math.round` rounds ties to EVEN,
     * `Math.round` rounds ties up — at Pixel 7's 2.625 density, 36dp is exactly 94.5px and the
     * two disagree (94 vs 95). Replicating the production expression is what makes the size
     * assertion exact rather than approximately right.
     */
    private fun dp(value: Int): Int =
        kotlin.math.round(value * context.resources.displayMetrics.density).toInt()

    private fun fakeField(): SuggestionBar.InputConnectionProvider =
        SuggestionBar.InputConnectionProvider {
            object : android.view.inputmethod.BaseInputConnection(bar, false) {
                override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = fieldText
                override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            }
        }

    private fun passwordContainer(): RelativeLayout =
        bar.getChildAt(0) as RelativeLayout

    private fun eyeToggle(): ImageView =
        passwordContainer().children().filterIsInstance<ImageView>().first()

    private fun horizontalScrollView(): HorizontalScrollView =
        passwordContainer().children().filterIsInstance<HorizontalScrollView>().first()

    private fun passwordTextView(): TextView {
        val wrapper = horizontalScrollView().getChildAt(0) as ViewGroup
        return wrapper.getChildAt(0) as TextView
    }

    private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    /** Measure + lay out the detached bar so child `left`/`measuredWidth` are real. */
    private fun layOutBar(widthPx: Int) {
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(40), View.MeasureSpec.EXACTLY)
        )
        bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
    }
}
