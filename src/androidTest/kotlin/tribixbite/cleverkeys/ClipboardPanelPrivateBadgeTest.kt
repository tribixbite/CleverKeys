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

    @Test
    fun privateEntry_showsBadge() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val entry = ClipboardEntry("secret", System.currentTimeMillis(), isPrivate = true, sourcePackage = "com.src")
            val row = renderRow(listOf(entry), 0)
            val badge = row.findViewById<TextView>(R.id.clipboard_entry_private_badge)
            assertEquals("badge visible for private entry", View.VISIBLE, badge.visibility)
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
