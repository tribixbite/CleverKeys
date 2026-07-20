package tribixbite.cleverkeys

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
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
}
