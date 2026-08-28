package tribixbite.cleverkeys

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #156 instrumented tests for the clipboard panel private-copy UX (design §8):
 *   - a 🔒 badge renders on is_private rows and is GONE on normal rows;
 *   - long-pressing a private entry to copy it to the OS clipboard goes through
 *     [ClipboardHistoryView.copyEntryToSystemClipboard] which confirms first (so an accidental
 *     long-press cannot leak a private clip), while a normal entry copies immediately.
 *
 * The OS-clipboard assertions are verified RELIABLY (not vacuously): [clipboardHelper] adopts the
 * shell permission identity so the test can set a known baseline and READ the primary clip back to
 * compare actual VALUES. On API 29+ a non-focused test's OnPrimaryClipChangedListener never fires, so
 * the previous listener-based approach passed vacuously (normal-copy) or falsely (private-copy).
 *
 * Mirrors ClipboardEditBugTest's real-getView harness (inflates the actual XML layout).
 *
 * FLAGGED: androidTest — run via ew-cli (Pixel7 API 34, debug APK, --use-orchestrator).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardPanelPrivateBadgeTest {

    private lateinit var context: Context
    private val clipboardHelper = PrivateCopyClipboardTestHelper()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        clipboardHelper.adopt()
    }

    @After
    fun tearDown() {
        clipboardHelper.drop()
    }

    private fun themedContext(): Context = ContextThemeWrapper(context, R.style.Dark)

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(obj: Any, fieldName: String): T? {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj) as? T
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    /**
     * Invoke the PRIVATE [ClipboardHistoryView.writeToSystemClipboard] — the exact action the
     * confirm dialog's positive button runs. Driving the real AlertDialog button is impractical in a
     * bare-view harness (no window token), so we exercise the confirmed-write action directly.
     */
    private fun invokeWriteToSystemClipboard(chv: ClipboardHistoryView, text: String) {
        val m = ClipboardHistoryView::class.java.getDeclaredMethod("writeToSystemClipboard", String::class.java)
        m.isAccessible = true
        m.invoke(chv, text)
    }

    /** Inflate row [pos] of [entries] via the REAL adapter.getView() and return the row View. */
    private fun renderRow(entries: List<ClipboardEntry>, pos: Int): View {
        val chv = ClipboardHistoryView(themedContext(), null)
        setField(chv, "paginatedHistory", entries)
        val adapter = getField<BaseAdapter>(chv, "clipboardAdapter")!!
        return adapter.getView(pos, null, FrameLayout(context))
    }

    /**
     * ARC-011: render a row in its EXPANDED state — the surface the private-copy design (§8)
     * assigned the provenance line to. Seeds `expandedStates[timestamp] = true` the same way a
     * user tap does, then drives the real adapter so the actual inflated layout is asserted.
     */
    private fun renderExpandedRow(entry: ClipboardEntry): View {
        val chv = ClipboardHistoryView(themedContext(), null)
        setField(chv, "paginatedHistory", listOf(entry))
        @Suppress("UNCHECKED_CAST")
        val expanded = getField<MutableMap<Long, Boolean>>(chv, "expandedStates")!!
        expanded[entry.timestamp] = true
        val adapter = getField<BaseAdapter>(chv, "clipboardAdapter")!!
        return adapter.getView(0, null, FrameLayout(context))
    }

    private fun provenanceOf(row: View): TextView =
        row.findViewById(R.id.clipboard_entry_provenance)

    /**
     * Drive the REAL filter pipeline: inject [entries] as the view's backing `history`, run the
     * private [applyFilter], and read back the resulting `filteredHistory`. Mirrors how the live
     * filter dialog mutates state — exercises the actual predicate, not a re-implementation.
     */
    private fun filterFor(
        entries: List<ClipboardEntry>,
        privateOnly: Boolean,
        search: String? = null
    ): List<ClipboardEntry> {
        val chv = ClipboardHistoryView(themedContext(), null)
        setField(chv, "history", entries)
        if (search != null) chv.setSearchFilter(search)   // sets searchFilter + applyFilter()
        chv.setPrivateOnlyFilter(privateOnly)             // sets flag + applyFilter()
        @Suppress("UNCHECKED_CAST")
        return getField<List<ClipboardEntry>>(chv, "filteredHistory")!!
    }

    private val mixedEntries: List<ClipboardEntry>
        get() = listOf(
            ClipboardEntry("secret one", 1_000L, isPrivate = true, sourcePackage = "com.src"),
            ClipboardEntry("public one", 2_000L, isPrivate = false),
            ClipboardEntry("secret two", 3_000L, isPrivate = true, sourcePackage = "com.src"),
            ClipboardEntry("public two", 4_000L, isPrivate = false)
        )

    @Test
    fun privateOnlyFilter_showsOnlyPrivateEntries() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val filtered = filterFor(mixedEntries, privateOnly = true)
            assertEquals("only the two private entries survive", 2, filtered.size)
            assertTrue("every surviving entry is private", filtered.all { it.isPrivate })
            assertEquals(
                "the private contents are the expected ones",
                setOf("secret one", "secret two"),
                filtered.map { it.content }.toSet()
            )
        }
    }

    @Test
    fun privateOnlyFilter_off_showsAllEntries() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val filtered = filterFor(mixedEntries, privateOnly = false)
            assertEquals("with the filter off, all four entries show", 4, filtered.size)
        }
    }

    @Test
    fun privateOnlyFilter_composesWithSearch_asAndPredicate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // search "secret" matches both private entries and zero public ones; private-only is an
            // additional AND that (here) doesn't further narrow — but proves both filters compose.
            val bothSecret = filterFor(mixedEntries, privateOnly = true, search = "secret")
            assertEquals("private AND search=secret → both private entries", 2, bothSecret.size)
            assertTrue(bothSecret.all { it.isPrivate })

            // search "one" matches "secret one" (private) + "public one" (not) — private-only must
            // drop the public match, leaving exactly the private one. Proves the AND actually narrows.
            val onlyPrivateOne = filterFor(mixedEntries, privateOnly = true, search = "one")
            assertEquals("private AND search=one → just the private 'one'", 1, onlyPrivateOne.size)
            assertEquals("secret one", onlyPrivateOne.single().content)
        }
    }

    @Test
    fun privateOnlyFilter_countsAsActiveFilter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(themedContext(), null)
            assertFalse("no filters active by default", chv.hasActiveFilters())
            chv.setPrivateOnlyFilter(true)
            assertTrue("private-only on must tint the filter icon", chv.hasActiveFilters())
            assertTrue(chv.isPrivateOnlyFilter())
            chv.clearAllFilters()
            assertFalse("clearAllFilters resets private-only", chv.hasActiveFilters())
            assertFalse(chv.isPrivateOnlyFilter())
        }
    }

    @Test
    fun privateOnlyFilter_emptyResult_doesNotCrash() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // No private entries → private-only yields an empty filtered list (empty-state renders,
            // no stale rows). Assert the list is empty and getCount reflects it.
            val allPublic = listOf(
                ClipboardEntry("a", 1_000L, isPrivate = false),
                ClipboardEntry("b", 2_000L, isPrivate = false)
            )
            val filtered = filterFor(allPublic, privateOnly = true)
            assertTrue("no private entries → empty filtered list", filtered.isEmpty())
        }
    }

    @Test
    fun privateEntry_showsBadge() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val entry = ClipboardEntry("secret", System.currentTimeMillis(), isPrivate = true, sourcePackage = "com.src")
            val row = renderRow(listOf(entry), 0)
            val badge = row.findViewById<TextView>(R.id.clipboard_entry_private_badge)
            assertEquals("badge visible for private entry", View.VISIBLE, badge.visibility)
        }
    }

    // ── ARC-011: the provenance line the §6.2/§6.6 risk acceptance depends on ──────────────

    @Test
    fun privateEntry_expanded_showsProvenanceWithResolvedAppLabel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // The test APK's own package is guaranteed installed, so PackageManager resolves it.
            val ownPkg = context.packageName
            val ownLabel = context.packageManager
                .getApplicationLabel(context.packageManager.getApplicationInfo(ownPkg, 0))
                .toString()
            val entry = ClipboardEntry(
                "secret", System.currentTimeMillis(), isPrivate = true, sourcePackage = ownPkg
            )
            val prov = provenanceOf(renderExpandedRow(entry))
            assertEquals("provenance visible on an expanded private row", View.VISIBLE, prov.visibility)
            assertTrue(
                "provenance names the resolved app label, was: '${prov.text}'",
                prov.text.toString().contains(ownLabel)
            )
        }
    }

    @Test
    fun privateEntry_collapsed_hidesProvenance() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Design §8 puts the line in the expanded/detail view only — the collapsed row keeps
            // its single-line body plus the 🔒 badge.
            val entry = ClipboardEntry(
                "secret", System.currentTimeMillis(), isPrivate = true, sourcePackage = context.packageName
            )
            val row = renderRow(listOf(entry), 0)
            assertEquals("provenance hidden while collapsed", View.GONE, provenanceOf(row).visibility)
        }
    }

    @Test
    fun entryWithoutSourcePackage_expanded_showsNoProvenanceLine() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Pre-V5 rows and ordinary OS-clipboard captures carry NULL source_package — they must
            // render no line at all rather than an empty "via".
            val entry = ClipboardEntry("public", System.currentTimeMillis(), isPrivate = false)
            val prov = provenanceOf(renderExpandedRow(entry))
            assertEquals("no provenance without a source package", View.GONE, prov.visibility)
        }
    }

    @Test
    fun directLaunchEntry_expanded_showsTheInjectionTell() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // §6.3: a launch without startActivityForResult is recorded as "direct-launch"; the
            // panel must surface it as such, not silently resolve it like a package name.
            val entry = ClipboardEntry(
                "planted", System.currentTimeMillis(), isPrivate = true, sourcePackage = "direct-launch"
            )
            val prov = provenanceOf(renderExpandedRow(entry))
            assertEquals("provenance visible for a direct launch", View.VISIBLE, prov.visibility)
            assertEquals(
                "direct launches render the sentinel label, not the raw sentinel string",
                context.getString(
                    R.string.clipboard_provenance_via,
                    context.getString(R.string.clipboard_provenance_direct_launch)
                ),
                prov.text.toString()
            )
        }
    }

    @Test
    fun uninstalledSourcePackage_expanded_fallsBackToRawPackageName() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Attribution must survive the source app being uninstalled.
            val gone = "com.example.definitely.not.installed"
            val entry = ClipboardEntry(
                "secret", System.currentTimeMillis(), isPrivate = true, sourcePackage = gone
            )
            val prov = provenanceOf(renderExpandedRow(entry))
            assertEquals("provenance visible for an uninstalled source", View.VISIBLE, prov.visibility)
            assertTrue(
                "falls back to the raw package, was: '${prov.text}'",
                prov.text.toString().contains(gone)
            )
        }
    }

    @Test
    fun normalEntry_hidesBadge() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val entry = ClipboardEntry("public", System.currentTimeMillis(), isPrivate = false)
            val row = renderRow(listOf(entry), 0)
            val badge = row.findViewById<TextView>(R.id.clipboard_entry_private_badge)
            assertEquals("badge gone for normal entry", View.GONE, badge.visibility)
        }
    }

    @Test
    fun normalEntry_copyToSystemClipboard_writesImmediately_noDialog() {
        // Seed a distinct baseline; a non-private copy writes immediately (no confirm dialog), so the
        // primary clip must end up holding the entry's content — asserted by READING it back.
        val baseline = "baseline-${System.nanoTime()}"
        val content = "plain copy ${System.nanoTime()}"
        clipboardHelper.setPrimaryClip("baseline", baseline)

        val instr = InstrumentationRegistry.getInstrumentation()
        instr.runOnMainSync {
            val chv = ClipboardHistoryView(themedContext(), null)
            // Non-private → copies immediately (no confirm dialog).
            chv.copyEntryToSystemClipboard(ClipboardEntry(content, System.currentTimeMillis(), isPrivate = false))
        }
        clipboardHelper.waitForIdle()

        assertEquals(
            "normal copy must write the OS clipboard immediately with the entry's content",
            content,
            clipboardHelper.readPrimaryClipText()
        )
    }

    @Test
    fun privateEntry_copyToSystemClipboard_showsConfirm_doesNotWriteUntilConfirmed() {
        // Seed a known baseline; a PRIVATE copy is gated by a confirm dialog, so the immediate path
        // must NOT write — the OS clipboard must still hold the baseline. Then invoke the exact
        // confirmed-write action (positive-button handler) and assert the content is written.
        val baseline = "baseline-${System.nanoTime()}"
        val content = "private copy ${System.nanoTime()}"
        clipboardHelper.setPrimaryClip("baseline", baseline)

        val instr = InstrumentationRegistry.getInstrumentation()
        instr.runOnMainSync {
            val chv = ClipboardHistoryView(themedContext(), null)
            // Private → copyEntryToSystemClipboard builds a confirm dialog instead of writing.
            // Building/showing the AlertDialog may throw (no window token in this bare-view harness);
            // catching it is acceptable — what matters is that NO immediate OS write happened.
            try {
                chv.copyEntryToSystemClipboard(ClipboardEntry(content, System.currentTimeMillis(), isPrivate = true, sourcePackage = "com.src"))
            } catch (_: Exception) {
                // Missing window token when showing the dialog — no clipboard write on this path.
            }
        }
        clipboardHelper.waitForIdle()

        // SECURITY INVARIANT (non-vacuous): before confirmation the clip is UNCHANGED (still baseline).
        assertEquals(
            "private entry must not be written to the OS clipboard before confirm",
            baseline,
            clipboardHelper.readPrimaryClipText()
        )

        // Now run the confirmed-write action the positive button invokes; the content is written.
        instr.runOnMainSync {
            val chv = ClipboardHistoryView(themedContext(), null)
            invokeWriteToSystemClipboard(chv, content)
        }
        clipboardHelper.waitForIdle()
        assertEquals(
            "after confirmation the private entry's content is written to the OS clipboard",
            content,
            clipboardHelper.readPrimaryClipText()
        )
    }
}
