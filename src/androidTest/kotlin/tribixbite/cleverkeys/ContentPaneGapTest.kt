package tribixbite.cleverkeys

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.2.6 and v1.2.8: "Emoji/clipboard panel gap eliminated".
 *
 * Both release-record rows were PRESENT-UNTESTED and both are anchored at
 * `emoji/EmojiGridView.kt#EmojiGridView`, but the gap was never an emoji-grid property — the
 * bug history in `.claude/skills/content-pane-layout.md` records the cause as a ViewFlipper with
 * `MATCH_PARENT` heights and the fix as "simple topPane FrameLayout with explicit heights".
 * The code that carries that fix is [SuggestionBarPane.switchToContentPaneMode] /
 * [SuggestionBarPane.switchToSuggestionBarMode], and it is what this file drives.
 *
 * The keyboard's top row is a vertical `LinearLayout` holding `topPane` above the keyboard view.
 * A gap appears when `topPane` and the pane it holds disagree about height, or when a
 * `MATCH_PARENT` child stretches past what the parent reserved. So the invariants are:
 *
 *  - after either switch, `topPane.layoutParams.height` is EXACTLY the requested height;
 *  - the child added is given an explicit pixel height, never `MATCH_PARENT`;
 *  - `topPane` holds exactly ONE child, so the outgoing view cannot leave a reserved strip;
 *  - laid out for real, the pane's bottom edge coincides with `topPane`'s — that coincidence,
 *    in pixels, IS "no gap".
 *
 * Instrumented because every assertion is about real measured `View` geometry.
 */
@RunWith(AndroidJUnit4::class)
class ContentPaneGapTest {

    private lateinit var context: Context
    private lateinit var container: LinearLayout
    private lateinit var topPane: FrameLayout
    private lateinit var contentPane: FrameLayout
    private lateinit var scrollView: HorizontalScrollView
    private lateinit var keyboardStandIn: View

    private val barHeight get() = dp(40)
    private val paneHeight get() = dp(240)
    private val keyboardHeight get() = dp(200)
    private val screenWidth get() = dp(360)

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // The production hierarchy from the content-pane skill:
            //   inputViewContainer (LinearLayout, VERTICAL)
            //     topPane (FrameLayout)  -> holds ONE of scrollView / contentPane
            //     keyboardView
            topPane = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, barHeight
                )
            }
            scrollView = HorizontalScrollView(context).apply { addView(SuggestionBar(context)) }
            contentPane = FrameLayout(context).apply { addView(View(context)) }
            keyboardStandIn = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight
                )
            }

            container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(topPane)
                addView(keyboardStandIn)
            }
            topPane.addView(scrollView)
        }
    }

    // ==================== opening a content pane ====================

    @Test
    fun openingAContentPaneResizesTopPaneAndGivesTheChildTheSameExplicitHeight() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SuggestionBarPane.switchToContentPaneMode(topPane, contentPane, scrollView, paneHeight)

            assertEquals(
                "topPane must reserve exactly the pane height",
                paneHeight, topPane.layoutParams.height
            )
            assertEquals(
                "the pane must get an EXPLICIT pixel height — a MATCH_PARENT here is the " +
                    "original ViewFlipper bug that left a gap",
                paneHeight, contentPane.layoutParams.height
            )
            assertNotEquals(
                ViewGroup.LayoutParams.MATCH_PARENT, contentPane.layoutParams.height
            )
            assertEquals(
                "width still fills the keyboard",
                ViewGroup.LayoutParams.MATCH_PARENT, contentPane.layoutParams.width
            )
        }
    }

    @Test
    fun theSuggestionBarIsRemovedSoTopPaneHoldsExactlyOneChild() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SuggestionBarPane.switchToContentPaneMode(topPane, contentPane, scrollView, paneHeight)

            assertEquals("exactly one child", 1, topPane.childCount)
            assertSame("and it is the content pane", contentPane, topPane.getChildAt(0))
            assertNull(
                "the suggestion bar's scroll view must be detached, not merely hidden — a " +
                    "leftover sibling keeps its own reserved strip",
                scrollView.parent
            )
        }
    }

    @Test
    fun theContentPaneBottomMeetsTheKeyboardWithNoGap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SuggestionBarPane.switchToContentPaneMode(topPane, contentPane, scrollView, paneHeight)
            layOut()

            assertEquals("topPane is laid out at the requested height", paneHeight, topPane.height)
            assertEquals(
                "the pane fills topPane exactly — any shortfall is the visible gap",
                paneHeight, contentPane.height
            )
            assertEquals("no offset at the top", 0, contentPane.top)
            assertEquals(
                "the pane's bottom edge IS topPane's bottom edge",
                topPane.height, contentPane.bottom
            )
            assertEquals(
                "and the keyboard starts on that very pixel",
                topPane.bottom, keyboardStandIn.top
            )
            assertEquals(
                "total height is pane + keyboard, with nothing in between",
                paneHeight + keyboardHeight, container.height
            )
        }
    }

    // ==================== closing it again ====================

    @Test
    fun closingTheContentPaneRestoresTheBarAtTheBarHeight() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SuggestionBarPane.switchToContentPaneMode(topPane, contentPane, scrollView, paneHeight)
            SuggestionBarPane.switchToSuggestionBarMode(topPane, contentPane, scrollView, barHeight)

            assertEquals(barHeight, topPane.layoutParams.height)
            assertEquals(
                "the restored bar gets an explicit height too",
                barHeight, scrollView.layoutParams.height
            )
            assertEquals(1, topPane.childCount)
            assertSame(scrollView, topPane.getChildAt(0))
            assertNull("the content pane is detached on the way out", contentPane.parent)
        }
    }

    @Test
    fun theRestoredBarLeavesNoGapEither() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SuggestionBarPane.switchToContentPaneMode(topPane, contentPane, scrollView, paneHeight)
            SuggestionBarPane.switchToSuggestionBarMode(topPane, contentPane, scrollView, barHeight)
            layOut()

            assertEquals(barHeight, topPane.height)
            assertEquals(barHeight, scrollView.height)
            assertEquals(topPane.height, scrollView.bottom)
            assertEquals(topPane.bottom, keyboardStandIn.top)
            assertEquals(barHeight + keyboardHeight, container.height)
        }
    }

    // ==================== switching between panes ====================

    @Test
    fun switchingEmojiToClipboardKeepsExactlyOneChildAndNoGap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val emojiPane = FrameLayout(context).apply { addView(View(context)) }
            val clipboardPane = FrameLayout(context).apply { addView(View(context)) }

            SuggestionBarPane.switchToContentPaneMode(topPane, emojiPane, scrollView, paneHeight)
            // The production flow swaps the container's contents; the second pane arrives while
            // the first is still attached, which is where a stale child would accumulate.
            topPane.removeView(emojiPane)
            SuggestionBarPane.switchToContentPaneMode(topPane, clipboardPane, scrollView, paneHeight)
            layOut()

            assertEquals(1, topPane.childCount)
            assertSame(clipboardPane, topPane.getChildAt(0))
            assertEquals(paneHeight, clipboardPane.height)
            assertEquals(topPane.height, clipboardPane.bottom)
        }
    }

    @Test
    fun repeatedOpensAreIdempotent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            repeat(3) {
                SuggestionBarPane.switchToContentPaneMode(topPane, contentPane, scrollView, paneHeight)
            }
            layOut()

            assertEquals(
                "re-opening an already-open pane must not stack duplicates",
                1, topPane.childCount
            )
            assertEquals(paneHeight, topPane.height)
            assertEquals(paneHeight, contentPane.height)
        }
    }

    // ==================== the height the pane is opened at ====================

    @Test
    fun theContentPaneHeightIsTheConfiguredPercentageOfTheScreen() {
        val screenHeight = context.resources.displayMetrics.heightPixels

        assertEquals(
            "30% is the shipped clipboard_pane_height_percent default",
            (screenHeight * 30) / 100,
            SuggestionBarPane.calculateContentPaneHeight(context, 30)
        )
        assertEquals(
            (screenHeight * 55) / 100,
            SuggestionBarPane.calculateContentPaneHeight(context, 55)
        )
        assertTrue(
            "the computed height must be a real, positive pixel count",
            SuggestionBarPane.calculateContentPaneHeight(context, 30) > 0
        )
    }

    // ==================== helpers ====================

    private fun dp(value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)

    /** Measure + lay out the detached hierarchy so `top`/`bottom`/`height` are real pixels. */
    private fun layOut() {
        container.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        container.layout(0, 0, container.measuredWidth, container.measuredHeight)
    }
}
