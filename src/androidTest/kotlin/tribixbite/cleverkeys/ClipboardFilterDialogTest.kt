package tribixbite.cleverkeys

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the clipboard filter dialog (funnel icon in the clipboard pane).
 *
 * BUG 1 (user report 2026-07-20): pressing the filter button crashed the IME process.
 * Root cause: the lint wave-1 commit converted the dialog's three `<Switch>` elements to
 * `<androidx.appcompat.widget.SwitchCompat>`, but `ClipboardManager.showFilterDialog` inflates
 * this layout with `ContextThemeWrapper(imeService, android.R.style.Theme_DeviceDefault_Dialog)`
 * — a FRAMEWORK theme with no AppCompat `?attr/switchStyle`. Without a default style,
 * SwitchCompat's constructor falls back to `showText = true` with null `textOn`/`textOff`, so the
 * first measure pass hits `makeLayout(null)` → `StaticLayout` NPE:
 *
 *   java.lang.NullPointerException: Attempt to invoke interface method
 *     'int java.lang.CharSequence.length()' on a null object reference
 *   at android.text.StaticLayout.<init>(StaticLayout.java:654)
 *   at androidx.appcompat.widget.SwitchCompat.makeLayout(SwitchCompat.java:995)
 *   at androidx.appcompat.widget.SwitchCompat.onMeasure(SwitchCompat.java:916)
 *   ... at com.android.internal.widget.AlertDialogLayout.onMeasure ...
 *
 * (captured verbatim from the device crash buffer, 07-20 08:42, Process: tribixbite.cleverkeys).
 * Fix: the dialog uses framework `<Switch>` (styled by Theme.DeviceDefault) — see the header
 * comment in res/layout/clipboard_filter_dialog.xml.
 *
 * BUG 2 (same report): "no private clippings section" — the #156 'Private only' filter lives in
 * this dialog, so the measure crash masked it entirely. The tests below pin both: the dialog
 * must inflate AND measure under the exact production theme, and the private-only toggle must
 * exist, be visible on every tab (its section is unconditional), and drive the entry query.
 *
 * FLAGGED: androidTest — run via ew-cli (Pixel7 API 34, debug APK, --use-orchestrator).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardFilterDialogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    /** Mirrors ClipboardManager.showFilterDialog's themed context EXACTLY (framework dialog theme). */
    private fun productionDialogContext(): Context =
        ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog)

    /**
     * Inflate + measure the dialog content on the main thread, the same two steps the real
     * AlertDialog performs when shown. Any Throwable is captured and rethrown as a test failure
     * (instead of crashing the main looper) so the pre-fix NPE reads as a clean red test.
     */
    private fun inflateAndMeasureFilterDialog(): View {
        val result = AtomicReference<View>()
        val error = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                val v = LayoutInflater.from(productionDialogContext())
                    .inflate(R.layout.clipboard_filter_dialog, null)
                // Same pass AlertDialogLayout drives on show() — this is where the pre-fix
                // SwitchCompat NPE fired.
                v.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.AT_MOST)
                )
                result.set(v)
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        error.get()?.let {
            throw AssertionError(
                "clipboard_filter_dialog crashed during inflate/measure under the production " +
                    "dialog theme (Theme_DeviceDefault_Dialog) — this is the filter-button crash: $it",
                it
            )
        }
        return result.get()
    }

    // ── Bug 1: the filter-button crash ───────────────────────────────────────

    @Test
    fun filterDialog_inflatesAndMeasures_underProductionDialogTheme() {
        val v = inflateAndMeasureFilterDialog()
        assertTrue("dialog content must measure to a non-zero size", v.measuredHeight > 0)
    }

    // ── Bug 2: 'Private only' filter present + wired ─────────────────────────

    @Test
    fun privateOnlySwitch_existsIsVisible_andToggles() {
        val v = inflateAndMeasureFilterDialog()
        val privateOnly = v.findViewById<CompoundButton>(R.id.filter_private_only)
        assertNotNull("'Private only' toggle (#156) must exist in the filter dialog", privateOnly)
        // The private-only section is unconditional (available on ALL tabs) — nothing in
        // showFilterDialog's tab-gating hides it, so it must inflate VISIBLE.
        assertEquals(View.VISIBLE, privateOnly.visibility)
        assertFalse("default state is off", privateOnly.isChecked)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { privateOnly.isChecked = true }
        assertTrue("toggle must be switchable", privateOnly.isChecked)
    }

    @Test
    fun privateOnlyFilter_togglesEntryQuery_onHistoryView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var chv: ClipboardHistoryView
        instrumentation.runOnMainSync {
            chv = ClipboardHistoryView(ContextThemeWrapper(context, R.style.Dark), null)
            // Same call the dialog's Apply button makes (ClipboardManager.showFilterDialog).
            chv.setPrivateOnlyFilter(true)
        }
        assertTrue("query flag must flip on", chv.isPrivateOnlyFilter())
        assertTrue(
            "private-only must count as an active filter (drives the funnel icon tint)",
            chv.hasActiveFilters()
        )
        instrumentation.runOnMainSync { chv.clearAllFilters() }
        assertFalse("Clear must reset the private-only filter", chv.isPrivateOnlyFilter())
        assertFalse(chv.hasActiveFilters())
    }
}
