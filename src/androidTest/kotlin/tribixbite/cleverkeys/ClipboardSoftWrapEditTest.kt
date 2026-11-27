package tribixbite.cleverkeys

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * Maintainer 2026-11-26, the inline clipboard editor on a URL that wraps mid-word: edit
 * `https://github.com/rustdesk/rustdesk-server` into `https://github.com/tribixbite/rustdesk-server`
 * (the first `rustdesk`, the org segment). Real layout, real taps (UiDevice), real History rows:
 *
 *  1. "tapping the end of a line puts the cursor BEFORE the last character of that line" —
 *     the platform never maps a tap to a soft-wrapped line's end offset (Layout clamps to the
 *     start of the last character); [ClipboardEditField] maps a tap past its midpoint there.
 *  2. Up / down (arrow keys, a vertical slider) moved by LOGICAL line: in a URL without line
 *     breaks, up went to the very start and down to the very end. They move one visual line,
 *     as in an app's field (Selection.moveUp/moveDown over the field's Layout).
 *  3. "the entry cant be saved - the checkmark does nothing" — a refused save's reason never
 *     reached the screen (the row re-bind hid it); a real tap on Save either saves or shows why.
 *
 * The pane is sized so line 0 ends inside `rustdesk` (`https://github.com/rust` | `desk/…`), as
 * on a ~360–412 dp phone pane. Hosted in [ClipboardEditTestActivity] (ScrollView → list, the
 * production pane's structure).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardSoftWrapEditTest {

    private lateinit var context: Context
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val url = "https://github.com/rustdesk/rustdesk-server"
    private val edited = "https://github.com/tribixbite/rustdesk-server"
    private val org = url.indexOf("rustdesk")

    @Before
    fun setup() {
        context = instrumentation.targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    private fun setField(obj: Any, name: String, value: Any?) {
        val f = obj::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(obj, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(obj: Any, name: String): T? {
        val f = obj::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(obj) as? T
    }

    private class Hosted(
        val scenario: ActivityScenario<ClipboardEditTestActivity>,
        val chv: ClipboardHistoryView,
        val pane: ScrollView,
    )

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        instrumentation.waitForIdleSync()
    }

    /** The list in a [PANE_HEIGHT_PX] ScrollView showing [entries] on History, editing row 0. */
    private fun hostEditing(entries: List<ClipboardEntry>): Hosted {
        val scenario = ActivityScenario.launch(ClipboardEditTestActivity::class.java)
        lateinit var chv: ClipboardHistoryView
        lateinit var pane: ScrollView
        scenario.onActivity { activity ->
            val themed = ContextThemeWrapper(activity, R.style.Dark)
            chv = ClipboardHistoryView(themed, null)
            setField(chv, "currentTab", ClipboardTab.HISTORY)
            pane = ScrollView(themed)
            pane.addView(chv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            activity.container.addView(pane, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, PANE_HEIGHT_PX))
        }
        settle()  // the view's own load replaces the rows on attach; show the test rows after it
        instrumentation.runOnMainSync {
            setField(chv, "paginatedHistory", entries)
            getField<BaseAdapter>(chv, "clipboardAdapter")!!.notifyDataSetChanged()
        }
        settle()
        instrumentation.runOnMainSync { chv.edit_entry(0) }
        settle()
        return Hosted(scenario, chv, pane)
    }

    private fun editField(chv: ClipboardHistoryView): EditText {
        val getter = ClipboardHistoryView::class.java.getDeclaredMethod("getActiveEditingEditText")
        getter.isAccessible = true
        return getter.invoke(chv) as EditText
    }

    /**
     * Narrow the pane until the field's line 0 is `https://github.com/rust`: content width between
     * the widths of that prefix and of the prefix plus `d`. Returns the field.
     */
    private fun wrapInsideTheOrg(hosted: Hosted): EditText {
        var delta = 0
        var target = 0
        instrumentation.runOnMainSync {
            val et = editField(hosted.chv)
            val content = et.width - et.totalPaddingLeft - et.totalPaddingRight
            delta = hosted.pane.width - content
            val shorter = et.paint.measureText(url, 0, org + 4)
            val longer = et.paint.measureText(url, 0, org + 5)
            target = ceil((shorter + longer) / 2f).toInt()
            hosted.pane.layoutParams = hosted.pane.layoutParams.apply { width = target + delta }
        }
        settle()
        lateinit var et: EditText
        instrumentation.runOnMainSync { et = editField(hosted.chv) }
        val layout = et.layout
        assertNotNull("the field is laid out", layout)
        assertTrue("precondition: the URL wraps (${layout.lineCount} lines)", layout.lineCount >= 2)
        val end = layout.getLineEnd(0)
        assertTrue(
            "precondition: line 0 ends inside the first rustdesk, got \"${url.substring(0, end)}\"",
            end in (org + 1) until (org + "rustdesk".length),
        )
        return et
    }

    /** A real touch at the field's local content position ([x], line [line]'s middle). */
    private fun tapInField(et: EditText, x: Float, line: Int) {
        var sx = 0
        var sy = 0
        instrumentation.runOnMainSync {
            val loc = IntArray(2).also { et.getLocationOnScreen(it) }
            val layout = et.layout
            sx = (loc[0] + et.totalPaddingLeft - et.scrollX + x).toInt()
            sy = loc[1] + et.totalPaddingTop - et.scrollY + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
        }
        assertTrue(UiDevice.getInstance(instrumentation).click(sx, sy))
        settle()
        Thread.sleep(400)  // past the double-tap timeout, so a later tap is not a double tap
    }

    private fun tapCentreOf(id: Int, hosted: Hosted) {
        var x = 0
        var y = 0
        instrumentation.runOnMainSync {
            val button = hosted.chv.getChildAt(0).findViewById<View>(id)
            assertTrue("precondition: ${context.resources.getResourceEntryName(id)} is shown", button.isShown)
            val loc = IntArray(2).also { button.getLocationOnScreen(it) }
            x = loc[0] + button.width / 2
            y = loc[1] + button.height / 2
        }
        assertTrue(UiDevice.getInstance(instrumentation).click(x, y))
        settle()
    }

    @Test
    fun aTapAtTheEndOfAWrappedLinePutsTheCaretAfterItsLastCharacter() {
        val hosted = hostEditing(listOf(ClipboardEntry(content = url, timestamp = 1_700_000_000_000L)))
        try {
            val et = wrapInsideTheOrg(hosted)
            val layout = et.layout
            val lineEnd = layout.getLineEnd(0)

            // Just inside the visible end of line 0: on the last glyph's trailing half.
            tapInField(et, layout.getLineRight(0) - 2f, 0)

            var caret = -1
            instrumentation.runOnMainSync { caret = et.selectionStart }
            assertEquals(
                "a tap at the end of \"${url.substring(0, lineEnd)}\" goes after its last character",
                lineEnd, caret,
            )
            // And typing there edits after that character: Backspace removes the tapped one.
            instrumentation.runOnMainSync { hosted.chv.backspaceEditText() }
            assertEquals(url.removeRange(lineEnd - 1, lineEnd), et.text.toString())
        } finally {
            hosted.scenario.close()
        }
    }

    @Test
    fun aTapInsideAWrappedLineStillLandsWhereTapped() {
        val hosted = hostEditing(listOf(ClipboardEntry(content = url, timestamp = 1_700_000_000_000L)))
        try {
            val et = wrapInsideTheOrg(hosted)
            val layout = et.layout
            // Between the 2nd and 3rd character of "github" on line 0.
            val target = url.indexOf("github") + 2
            val x = (layout.getPrimaryHorizontal(target) + layout.getPrimaryHorizontal(target - 1)) / 2f + 1f

            tapInField(et, x, 0)

            var caret = -1
            instrumentation.runOnMainSync { caret = et.selectionStart }
            assertEquals(target, caret)
        } finally {
            hosted.scenario.close()
        }
    }

    @Test
    fun upAndDownMoveOneVisualLine() {
        val hosted = hostEditing(listOf(ClipboardEntry(content = url, timestamp = 1_700_000_000_000L)))
        try {
            val et = wrapInsideTheOrg(hosted)
            val layout = et.layout
            val onLine1 = layout.getLineStart(1) + 3
            instrumentation.runOnMainSync { et.setSelection(onLine1) }

            instrumentation.runOnMainSync { hosted.chv.dispatchKeyToEditText(KeyEvent.KEYCODE_DPAD_UP) }
            var caret = -1
            instrumentation.runOnMainSync { caret = et.selectionStart }
            assertEquals("up lands on line 0, not at the text's start", 0, layout.getLineForOffset(caret))
            assertNotEquals("up from the 4th character of line 1 is not offset 0", 0, caret)

            instrumentation.runOnMainSync { hosted.chv.dispatchKeyToEditText(KeyEvent.KEYCODE_DPAD_DOWN) }
            instrumentation.runOnMainSync { caret = et.selectionStart }
            assertEquals("down returns to line 1, not the text's end", onLine1, caret)
        } finally {
            hosted.scenario.close()
        }
    }

    @Test
    fun aRealTapOnSaveAfterEditingMidUrlSavesTheEdit() {
        val history = requireNotNull(ClipboardHistoryService.get_service(context)) { "no clipboard service" }
        instrumentation.runOnMainSync {
            history.removeHistoryEntry(edited)
            history.addClip(url)
        }
        val stamp = requireNotNull(history.historyEntryTimestamp(url)) { "the URL row was not stored" }
        val hosted = hostEditing(listOf(ClipboardEntry(content = url, timestamp = stamp)))
        try {
            val et = wrapInsideTheOrg(hosted)
            // A real tap puts the caret on line 1 (mid-URL), then the org segment is replaced.
            tapInField(et, et.layout.getPrimaryHorizontal(et.layout.getLineStart(1) + 2), 1)
            instrumentation.runOnMainSync { hosted.chv.replaceEditRange(org, org + "rustdesk".length, "tribixbite") }
            assertEquals(edited, et.text.toString())

            tapCentreOf(R.id.clipboard_entry_save, hosted)

            var editing = true
            instrumentation.runOnMainSync { editing = hosted.chv.isEditing() }
            assertFalse("a tap on Save with the caret mid-URL saves and leaves edit mode", editing)
            assertNotNull("the History row now holds the edit", history.historyEntryTimestamp(edited))
        } finally {
            hosted.scenario.close()
            instrumentation.runOnMainSync {
                history.removeHistoryEntry(url)
                history.removeHistoryEntry(edited)
            }
        }
    }

    @Test
    fun aRealTapOnSaveThatTheHistoryRefusesShowsWhy() {
        val history = requireNotNull(ClipboardHistoryService.get_service(context)) { "no clipboard service" }
        instrumentation.runOnMainSync {
            history.addClip(edited)  // the fork's URL is already a History row
            history.addClip(url)
        }
        val stamp = requireNotNull(history.historyEntryTimestamp(url)) { "the URL row was not stored" }
        val hosted = hostEditing(listOf(ClipboardEntry(content = url, timestamp = stamp)))
        try {
            wrapInsideTheOrg(hosted)
            instrumentation.runOnMainSync { hosted.chv.replaceEditRange(org, org + "rustdesk".length, "tribixbite") }

            tapCentreOf(R.id.clipboard_entry_save, hosted)

            var editing = false
            var shown = false
            var message = ""
            instrumentation.runOnMainSync {
                editing = hosted.chv.isEditing()
                val error = hosted.chv.getChildAt(0).findViewById<TextView>(R.id.clipboard_entry_edit_error)
                shown = error.isShown
                message = error.text.toString()
            }
            assertTrue("a refused save keeps the edit open", editing)
            assertTrue("the checkmark must not look dead: the reason is on screen", shown)
            assertEquals(context.getString(R.string.clipboard_edit_duplicate), message)
        } finally {
            hosted.scenario.close()
            instrumentation.runOnMainSync {
                history.removeHistoryEntry(url)
                history.removeHistoryEntry(edited)
            }
        }
    }

    private companion object {
        /** A phone pane above the keyboard. */
        const val PANE_HEIGHT_PX = 500
    }
}
