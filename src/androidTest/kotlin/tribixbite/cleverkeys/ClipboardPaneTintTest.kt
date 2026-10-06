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

    @Test
    fun deleteResults_usesKeyboardKeyColorAfterFrameworkInflation() {
        val keyColor = TypedValue()
        assertTrue(themedContext.theme.resolveAttribute(R.attr.colorKey, keyColor, true))
        val button = inflatePane().findViewById<View>(R.id.clipboard_delete_results)
        assertNotNull("Delete results needs a themed background rather than the stock pale button", button.backgroundTintList)
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
            feedback.text = context.getString(R.string.clipboard_delete_result, 2, 2)
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
                            val delete = pane.findViewById<View>(R.id.clipboard_delete_results)
                            val bounds = Rect(0, 0, delete.width, delete.height)
                            (pane as ViewGroup).offsetDescendantRectToMyCoords(delete, bounds)
                            assertTrue("Delete action must stay inside the pane", bounds.left >= 0 && bounds.right <= pane.width)
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
}
