package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for clipboard-pane icon theming (user report 2026-07-20, bug 4):
 * the three tab icons (History/Pinned/Todos) rendered UNTINTED — their vectors are
 * `android:fillColor="#000000"`, so they showed near-invisible black on dark themes and
 * mismatched the filter/close icons on the other side of the same header row.
 *
 * Root cause: the lint UseAppTint pass converted `android:tint` → `app:tint` on the 6 icon
 * views in res/layout/clipboard_pane.xml. But the pane is inflated by
 * `ClipboardManager.getClipboardPane` via `View.inflate(ContextThemeWrapper(imeService,
 * config.theme), …)` — a FRAMEWORK LayoutInflater without the AppCompat view factory, so the
 * elements inflate as plain ImageView/ImageButton, which silently IGNORE `app:tint` (only
 * AppCompatImageView reads it). `android:tint` is applied natively (imageTintList on API 21+),
 * which is why the icons were themed before that pass.
 *
 * The filter icon happened to still look right because `updateFilterIconTint()` applies a
 * programmatic `setColorFilter(resolveThemeColor(R.attr.colorLabel …))` on pane show — hence
 * the visible mismatch between the two sides.
 *
 * These tests inflate clipboard_pane.xml exactly like production (framework inflater, built-in
 * XML theme) and assert that BOTH sides — a tab icon and the filter/close icons — carry the
 * same tint, derived from the same theme attribute (?attr/colorLabel).
 *
 * FLAGGED: androidTest — run via ew-cli (Pixel7 API 34, debug APK, --use-orchestrator).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardPaneTintTest {

    private lateinit var context: Context
    private lateinit var themedContext: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        // Built-in XML theme — the exact case the app:tint regression broke (runtime custom_/
        // decorative_ themes were still tinted programmatically by applyRuntimeThemeColors).
        themedContext = ContextThemeWrapper(context, R.style.Dark)
    }

    /** Inflate the pane the way ClipboardManager.getClipboardPane does: framework inflater. */
    private fun inflatePane(): View {
        val result = AtomicReference<View>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result.set(View.inflate(themedContext, R.layout.clipboard_pane, null))
        }
        return result.get()
    }

    private fun resolvedColorLabel(): Int {
        val tv = TypedValue()
        assertTrue(
            "?attr/colorLabel must resolve in the built-in theme",
            themedContext.theme.resolveAttribute(R.attr.colorLabel, tv, true)
        )
        return tv.data
    }

    private fun tintOf(pane: View, id: Int, name: String): Int {
        val iv = pane.findViewById<ImageView>(id)
        assertNotNull("$name must exist in clipboard_pane", iv)
        val tint = iv.imageTintList
        assertNotNull(
            "$name must carry an XML tint after framework inflation — app:tint is IGNORED by " +
                "plain ImageView; the layout must use android:tint (see clipboard_pane.xml header)",
            tint
        )
        return tint!!.defaultColor
    }

    @Test
    fun tabIcons_areTinted_withThemeColorLabel() {
        val pane = inflatePane()
        val expected = resolvedColorLabel()
        assertEquals("History tab tint", expected, tintOf(pane, R.id.tab_history, "tab_history"))
        assertEquals("Pinned tab tint", expected, tintOf(pane, R.id.tab_pinned, "tab_pinned"))
        assertEquals("Todos tab tint", expected, tintOf(pane, R.id.tab_todos, "tab_todos"))
    }

    @Test
    fun tabIconTint_matchesFilterAndCloseIconTint() {
        val pane = inflatePane()
        val tabTint = tintOf(pane, R.id.tab_history, "tab_history")
        val filterTint = tintOf(pane, R.id.clipboard_date_filter, "clipboard_date_filter")
        val closeTint = tintOf(pane, R.id.clipboard_close_button, "clipboard_close_button")
        val clearTint = tintOf(pane, R.id.clipboard_search_clear, "clipboard_search_clear")
        assertEquals("tab icons must match the filter icon tint", filterTint, tabTint)
        assertEquals("tab icons must match the close icon tint", closeTint, tabTint)
        assertEquals("tab icons must match the search-clear icon tint", clearTint, tabTint)
    }

    // The "Delete results" button became "Select" (2026-10-07); the method name is kept.
    @Test
    fun deleteResults_usesKeyboardKeyColorAfterFrameworkInflation() {
        val keyColor = TypedValue()
        assertTrue(themedContext.theme.resolveAttribute(R.attr.colorKey, keyColor, true))
        val button = inflatePane().findViewById<View>(R.id.clipboard_select)
        assertNotNull("Select needs a themed background rather than the stock pale button", button.backgroundTintList)
        assertEquals(keyColor.data, button.backgroundTintList!!.defaultColor)
    }

    @Test
    fun compactLandscapePaneKeepsEntryViewportWithPaginationAndFeedback() {
        val pane = inflatePane()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val density = context.resources.displayMetrics.density
            fun pixels(dp: Int) = (dp * density).toInt()
            pane.findViewById<TextView>(R.id.clipboard_result_summary).text =
                context.getString(R.string.clipboard_results_summary, 3113, "1.1 MB")
            pane.findViewById<TextView>(R.id.clipboard_page_info).text = "1 / 32"
            val feedback = pane.findViewById<TextView>(R.id.clipboard_bulk_feedback)
            feedback.text = context.resources.getQuantityString(R.plurals.clipboard_delete_selected_result, 2, 2, 2)
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                pane.layoutDirection = direction
                for ((width, height) in listOf(890 to 120, 720 to 120, 400 to 300)) {
                    for (paginated in listOf(false, true)) {
                        for (showFeedback in listOf(false, true)) {
                            pane.findViewById<View>(R.id.clipboard_pagination_bar).visibility =
                                if (paginated) View.VISIBLE else View.GONE
                            feedback.visibility = if (showFeedback) View.VISIBLE else View.GONE
                            // A full action row must fit, not just a sliver of entry text.
                            // Reuse the pane at narrower widths to exercise rotation/split-screen.
                            pane.measure(View.MeasureSpec.makeMeasureSpec(pixels(width), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(pixels(height), View.MeasureSpec.EXACTLY))
                            pane.layout(0, 0, pane.measuredWidth, pane.measuredHeight)
                            val viewport = pane.findViewById<View>(R.id.clipboard_content_scroll)
                            assertTrue("Full entry row must fit: width=$width, direction=$direction, pagination=$paginated, feedback=$showFeedback",
                                viewport.height >= pixels(48))
                            val select = pane.findViewById<View>(R.id.clipboard_select)
                            val bounds = Rect(0, 0, select.width, select.height)
                            (pane as ViewGroup).offsetDescendantRectToMyCoords(select, bounds)
                            assertTrue("Select action must stay inside the pane", bounds.left >= 0 && bounds.right <= pane.width)
                            if (paginated) {
                                for (id in listOf(R.id.clipboard_page_prev, R.id.clipboard_page_next)) {
                                    val action = pane.findViewById<View>(id)
                                    assertTrue("Paging actions need 48dp targets", action.width >= pixels(48) && action.height >= pixels(48))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Selection mode (2026-10-07) adds a 48dp action strip: a third row in narrow panes, beside
     * the results in wide ones, where the non-current tab icons are hidden to pay for it. The
     * compact landscape pane must still show a full entry row, every action must be a 48dp
     * target inside the pane, and the selected count must not be squeezed to nothing.
     */
    @Test
    fun selectionModeKeepsEntryViewportCountAndActionTargetsInLandscape() {
        val pane = inflatePane()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val density = context.resources.displayMetrics.density
            fun pixels(dp: Int) = (dp * density).toInt()
            // Mirror ClipboardManager in selection mode on the History tab.
            pane.findViewById<View>(R.id.clipboard_select).visibility = View.GONE
            pane.findViewById<View>(R.id.clipboard_selection_bar).visibility = View.VISIBLE
            pane.findViewById<View>(R.id.tab_pinned).visibility = View.GONE
            pane.findViewById<View>(R.id.tab_todos).visibility = View.GONE
            val count = pane.findViewById<TextView>(R.id.clipboard_selection_count)
            count.visibility = View.VISIBLE
            count.text = context.resources.getQuantityString(R.plurals.clipboard_selection_count, 3113, 3113)
            pane.findViewById<TextView>(R.id.clipboard_result_summary).text =
                context.getString(R.string.clipboard_results_summary, 3113, "1.1 MB")
            pane.findViewById<TextView>(R.id.clipboard_page_info).text = "1 / 32"
            val actions = listOf(R.id.clipboard_select_matching, R.id.clipboard_selection_clear,
                R.id.clipboard_delete_selected, R.id.clipboard_selection_exit)
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                pane.layoutDirection = direction
                for ((width, height) in listOf(890 to 120, 720 to 120, 400 to 300)) {
                    for (paginated in listOf(false, true)) {
                        pane.findViewById<View>(R.id.clipboard_pagination_bar).visibility =
                            if (paginated) View.VISIBLE else View.GONE
                        pane.measure(View.MeasureSpec.makeMeasureSpec(pixels(width), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(pixels(height), View.MeasureSpec.EXACTLY))
                        pane.layout(0, 0, pane.measuredWidth, pane.measuredHeight)
                        val where = "width=$width, direction=$direction, pagination=$paginated"
                        assertTrue("Full entry row must fit in selection mode: $where",
                            pane.findViewById<View>(R.id.clipboard_content_scroll).height >= pixels(48))
                        for (id in actions + R.id.clipboard_selection_count) {
                            val view = pane.findViewById<View>(id)
                            val bounds = Rect(0, 0, view.width, view.height)
                            (pane as ViewGroup).offsetDescendantRectToMyCoords(view, bounds)
                            assertTrue("Selection control must stay inside the pane: $where",
                                bounds.left >= 0 && bounds.right <= pane.width)
                        }
                        for (id in actions) {
                            val action = pane.findViewById<View>(id)
                            assertTrue("Selection actions need 48dp targets: $where",
                                action.width >= pixels(48) && action.height >= pixels(48))
                        }
                        assertTrue("The selected count needs readable width: $where",
                            count.width >= pixels(56))
                    }
                }
            }
        }
    }
}
