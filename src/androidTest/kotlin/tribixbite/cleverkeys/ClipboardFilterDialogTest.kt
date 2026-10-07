package tribixbite.cleverkeys

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
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

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!onMain(condition)) {
            assertTrue("Clipboard UI did not settle before deadline", android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25)
        }
    }

    private fun dialog(manager: ClipboardManager, field: String): AlertDialog? =
        ClipboardManager::class.java.getDeclaredField(field).let {
            it.isAccessible = true
            it.get(manager) as AlertDialog?
        }

    /** IME dialogs deliberately lack focus, so use touch automation rather than Espresso. */
    private fun control(name: String): UiObject2 =
        requireNotNull(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .wait(Until.findObject(By.res(context.packageName, name)), 5_000)) { "Missing dialog control: $name" }

    private fun selectSize(name: String, bytes: Long) {
        control(name).click()
        val label = android.text.format.Formatter.formatShortFileSize(context, bytes)
        requireNotNull(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .wait(Until.findObject(By.text(label)), 5_000)) { "Missing size option: $label" }.click()
    }

    @Test
    fun sizeDialogRejectsInvertedBoundsAndCancelDoesNotApplyDraft() {
        var manager: ClipboardManager? = null
        try {
            ActivityScenario.launch(ClipboardEditTestActivity::class.java).use { scenario ->
                lateinit var pane: android.view.ViewGroup
                lateinit var history: ClipboardHistoryView
                scenario.onActivity { activity ->
                    manager = ClipboardManager(activity, Config.globalConfig())
                    pane = manager!!.getClipboardPane(activity.layoutInflater)
                    activity.setContentView(pane)
                    history = pane.findViewById(R.id.clipboard_history_view)
                    manager!!.showFilterDialog(pane)
                }
                selectSize("clipboard_size_min", 10_000)
                selectSize("clipboard_size_max", 1_000)
                assertFalse(control("date_filter_apply").isEnabled)
                control("date_filter_cancel").click()
                assertEquals(0L to null, onMain { history.getSizeFilter() })

                onMain { manager!!.showFilterDialog(pane) }
                selectSize("clipboard_size_min", 1_000)
                selectSize("clipboard_size_max", 10_000)
                assertTrue(control("date_filter_apply").isEnabled)
                control("date_filter_apply").click()
                assertEquals(1_000L to 10_000L, onMain { history.getSizeFilter() })
                onMain { history.setTab(ClipboardTab.PINNED) }
                assertEquals(1_000L to 10_000L, onMain { history.getSizeFilter() })
                onMain { history.clearAllFilters() }
                assertEquals(0L to null, onMain { history.getSizeFilter() })
                onMain { manager!!.cleanup() }
                manager = null
            }
        } finally {
            manager?.let { onMain(it::cleanup) }
        }
    }

    @Test
    fun confirmedSizeFilteredDeletionCoversAllPagesAndPreservesChangedRowsAndCopies() {
        val db = ClipboardDatabase.getInstance(context)
        val prefix = "ewgap-${java.util.UUID.randomUUID()}-"
        val expiry = System.currentTimeMillis() + 3600_000
        val large = (0 until 205).map { "$prefix$it-${"x".repeat(2_000)}" }
        large.forEach { assertTrue(db.addClipboardEntry(it, expiry)) }
        val small = prefix + "small"
        assertTrue(db.addClipboardEntry(small, expiry))
        assertTrue(db.pinEntry(large.first(), System.currentTimeMillis()))
        assertTrue(db.addTodoEntry(large.first(), System.currentTimeMillis()))
        var manager: ClipboardManager? = null
        try {
            ActivityScenario.launch(ClipboardEditTestActivity::class.java).use { scenario ->
                lateinit var pane: android.view.ViewGroup
                lateinit var history: ClipboardHistoryView
                scenario.onActivity { activity ->
                    manager = ClipboardManager(activity, Config.globalConfig())
                    pane = manager!!.getClipboardPane(activity.layoutInflater)
                    activity.setContentView(pane)
                    history = pane.findViewById(R.id.clipboard_history_view)
                    history.setSearchFilter(prefix)
                    history.setSizeFilter(1_000, null)
                }
                awaitCondition { history.isResultsReady() && history.resultSummary().first == 205 }
                // Filtered deletion goes through the selection: Select, then select all matching.
                onMain { pane.findViewById<View>(R.id.clipboard_select).performClick() }
                assertTrue(onMain { history.isSelecting() })
                onMain { pane.findViewById<View>(R.id.clipboard_select_matching).performClick() }
                assertEquals(205, onMain { history.selectedCount() })
                // The selection survives a search that hides it, and adds to it.
                onMain { history.setSearchFilter(small) }
                onMain { pane.findViewById<View>(R.id.clipboard_select_matching).performClick() }
                assertEquals(205, onMain { history.selectedCount() })  // the small clip fails the size filter
                onMain { history.setSearchFilter(prefix) }
                onMain { pane.findViewById<View>(R.id.clipboard_delete_selected).performClick() }
                assertEquals(205, db.getActiveClipboardEntries().count { it.content.startsWith(prefix) && it.content != small })
                onMain { requireNotNull(dialog(manager!!, "bulkDialog")).getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
                // AlertDialog dispatches button handling/dismissal through its Handler.
                // Wait for dismissal before trying to open the next confirmation.
                awaitCondition { dialog(manager!!, "bulkDialog") == null }
                assertEquals(206, db.getActiveClipboardEntries().count { it.content.startsWith(prefix) })
                assertEquals(205, onMain { history.selectedCount() })  // cancel keeps the selection

                onMain { pane.findViewById<View>(R.id.clipboard_delete_selected).performClick() }
                val added = prefix + "new-" + "x".repeat(2_000)
                val changed = prefix + "changed-" + "x".repeat(2_000)
                assertTrue(db.addClipboardEntry(added, expiry))
                assertEquals(EditEntryResult.Success, db.updateHistoryEntryContent(large.first(), changed))
                onMain { requireNotNull(dialog(manager!!, "bulkDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
                awaitCondition { history.isResultsReady() && history.resultSummary().first == 2 }
                assertEquals(setOf(small, added, changed), db.getActiveClipboardEntries()
                    .filter { it.content.startsWith(prefix) }.map { it.content }.toSet())
                assertTrue(db.getPinnedEntries().any { it.content == large.first() })
                assertTrue(db.getTodoEntries().any { it.content == large.first() })
                // A completed deletion ends selection mode.
                assertFalse(onMain { history.isSelecting() })
                onMain { manager!!.cleanup() }
                manager = null
            }
        } finally {
            manager?.let { onMain(it::cleanup) }
            for (table in listOf("clipboard_entries", "pinned_entries", "todo_entries")) {
                db.writableDatabase.delete(table, "content LIKE ?", arrayOf("$prefix%"))
            }
        }
    }

    /**
     * 2026-10-07 maintainer request: a selection survives the pane being hidden and rebuilt (the
     * keyboard-hide / rotation / theme paths all run resetSearchOnHide and/or cleanup), and the
     * new bulk actions complete against the real database: Add to Pinned (COPY, one transaction),
     * Merge (one new History clipping, oldest first) and Clean (in place). Each completed action
     * ends the selection. Also pins that IME dialogs are non-focusable (device report: a
     * focusable dialog let the host app hide the keyboard mid-tap).
     */
    @Test
    fun selectionSurvivesPaneRebuildAndBulkActionsCompleteOnTheDatabase() {
        val db = ClipboardDatabase.getInstance(context)
        val prefix = "ewsel-${java.util.UUID.randomUUID()}-"
        val expiry = System.currentTimeMillis() + 3600_000
        val first = prefix + "first line of a wrapped paragraph that goes on\nand ends here."
        val second = prefix + "second   "
        assertTrue(db.addClipboardEntry(first, expiry))
        android.os.SystemClock.sleep(5)
        assertTrue(db.addClipboardEntry(second, expiry))
        var manager: ClipboardManager? = null
        try {
            ActivityScenario.launch(ClipboardEditTestActivity::class.java).use { scenario ->
                lateinit var history: ClipboardHistoryView
                lateinit var pane: android.view.ViewGroup
                fun attachPane() {
                    scenario.onActivity { activity ->
                        pane = manager!!.getClipboardPane(activity.layoutInflater)
                        manager!!.resetSearchOnShow()
                        (pane.parent as? android.view.ViewGroup)?.removeView(pane)
                        activity.setContentView(pane)
                        history = pane.findViewById(R.id.clipboard_history_view)
                        history.setSearchFilter(prefix)
                    }
                    awaitCondition { history.isResultsReady() && history.resultSummary().first == 2 }
                }
                fun selectAll() {
                    onMain { pane.findViewById<View>(R.id.clipboard_select).performClick() }
                    onMain { pane.findViewById<View>(R.id.clipboard_select_matching).performClick() }
                    assertEquals(2, onMain { history.selectedCount() })
                }
                fun chooseAction(label: Int) {
                    onMain { pane.findViewById<View>(R.id.clipboard_selection_actions).performClick() }
                    val list = requireNotNull(onMain { dialog(manager!!, "bulkDialog") })
                    val flags = onMain { list.window!!.attributes.flags }
                    assertTrue("IME dialogs must not take window focus",
                        flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                    onMain {
                        val items = list.listView
                        val position = (0 until items.adapter.count).first {
                            items.adapter.getItem(it).toString() == context.getString(label)
                        }
                        items.performItemClick(null, position, position.toLong())
                    }
                }
                scenario.onActivity { activity -> manager = ClipboardManager(activity, Config.globalConfig()) }
                attachPane()
                selectAll()

                // Keyboard hide + theme rebuild: the views go, the selection stays.
                onMain { manager!!.resetSearchOnHide(); manager!!.cleanup() }
                attachPane()
                assertTrue(onMain { history.isSelecting() })
                assertEquals(2, onMain { history.selectedCount() })

                // Add to Pinned: copies both, ends the selection.
                chooseAction(R.string.clipboard_action_add_to_pinned)
                awaitCondition { !history.isSelecting() }
                assertEquals(setOf(first, second.trim()),
                    db.getPinnedEntries().map { it.content }.filter { it.startsWith(prefix) }.toSet())

                // Merge: one new History clipping, oldest first, one per line; originals stay.
                selectAll()
                chooseAction(R.string.clipboard_action_merge)
                awaitCondition { dialog(manager!!, "bulkDialog") != null }
                onMain { requireNotNull(dialog(manager!!, "bulkDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
                awaitCondition { !history.isSelecting() && history.isResultsReady() }
                val merged = first + "\n" + second.trim()
                assertTrue(db.getActiveClipboardEntries().any { it.content == merged })
                assertTrue(db.getActiveClipboardEntries().any { it.content == first })

                // Clean the original two in place (the merged row is excluded by the size filter).
                onMain { history.setSizeFilter(0, (first.length + 8).toLong()) }
                awaitCondition { history.resultSummary().first == 2 }
                selectAll()
                chooseAction(R.string.clipboard_action_clean)
                awaitCondition { dialog(manager!!, "bulkDialog") != null }
                onMain { requireNotNull(dialog(manager!!, "bulkDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
                awaitCondition { !history.isSelecting() && history.isResultsReady() }
                val cleaned = prefix + "first line of a wrapped paragraph that goes on and ends here."
                assertTrue(db.getActiveClipboardEntries().any { it.content == cleaned })
                // The pinned copy is a separate row and stays untouched.
                assertTrue(db.getPinnedEntries().any { it.content == first })
                onMain { manager!!.cleanup() }
                manager = null
            }
        } finally {
            manager?.let { onMain(it::cleanup) }
            for (table in listOf("clipboard_entries", "pinned_entries", "todo_entries")) {
                db.writableDatabase.delete(table, "content LIKE ?", arrayOf("$prefix%"))
            }
        }
    }

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
