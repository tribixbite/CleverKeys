package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #156 instrumented test for the in-IME "Private copy" editing action (entry point A).
 *
 * The KeyEventHandler / Keyboard2View dispatch reads the current selection via
 * InputConnection.getSelectedText and delegates to [ClipboardHistoryService.privateCopy]. This test
 * exercises that store primitive against the REAL service (works in androidTest — no android.jar
 * stubs) and asserts the two invariants: (1) the selection is stored PRIVATELY; (2) the OS primary
 * clip is NEVER written by the private copy.
 *
 * The OS-clipboard invariant is verified RELIABLY (not vacuously): [clipboardHelper] adopts the shell
 * permission identity so the test can set a known baseline BEFORE the private copy and READ the
 * primary clip back afterward, asserting it still equals the baseline. This is a genuine security
 * assertion — it would FAIL if the private copy leaked the selection to the OS clipboard. (The prior
 * OnPrimaryClipChangedListener approach passed vacuously on API 29+, where a non-focused test's
 * listener never fires.)
 *
 * FLAGGED: androidTest — run via ew-cli (Pixel7 API 34, debug APK, --use-orchestrator).
 */
@RunWith(AndroidJUnit4::class)
class PrivateCopyEditingKeyTest {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase
    private val clipboardHelper = PrivateCopyClipboardTestHelper()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        db = ClipboardDatabase.getInstance(context)
        db.writableDatabase.delete("clipboard_entries", null, null)
        // Shell identity → clipboard reads/writes work despite the non-focused test process.
        clipboardHelper.adopt()
    }

    @After
    fun tearDown() {
        db.writableDatabase.delete("clipboard_entries", null, null)
        clipboardHelper.drop()
    }

    @Test
    fun privateCopy_storesSelectionPrivately_andDoesNotTouchOsClipboard() {
        // Seed a known baseline on the OS clipboard. If the private copy leaks, this value changes.
        val baseline = "os-clipboard-baseline-${System.nanoTime()}"
        clipboardHelper.setPrimaryClip("baseline", baseline)
        assertEquals("baseline must be set before the private copy", baseline, clipboardHelper.readPrimaryClipText())

        // Simulates the dispatch after getSelectedText(0) returns "selected phrase".
        val stored = ClipboardHistoryService.privateCopy(context, "selected phrase", "com.target.editor")
        assertTrue(stored)

        val entry = db.getActiveClipboardEntries().firstOrNull { it.content == "selected phrase" }
        assertNotNull("selection stored", entry)
        assertTrue("stored privately", entry!!.isPrivate)
        assertEquals("com.target.editor", entry.sourcePackage)

        // SECURITY INVARIANT (non-vacuous): the OS primary clip still equals the baseline — the
        // private copy did NOT write "selected phrase" (or anything else) to the OS clipboard.
        clipboardHelper.waitForIdle()
        assertEquals(
            "private copy must not write the OS clipboard — it must still hold the baseline",
            baseline,
            clipboardHelper.readPrimaryClipText()
        )
    }

    @Test
    fun privateCopy_emptySelection_notStored() {
        // Mirrors the "No text selected" branch: null/empty selection stores nothing, returns false.
        assertFalse(ClipboardHistoryService.privateCopy(context, "", "com.x"))
        assertFalse(ClipboardHistoryService.privateCopy(context, null, "com.x"))
        assertEquals(0, db.getActiveClipboardEntries().size)
    }
}
