package tribixbite.cleverkeys

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Maintainer soak bug 2026-10-25 (memory/todo.md item 8 b): editing a LONG clipboard entry
 * (seen on the To-do tab; History and Pinned share the same editor) looked broken — "no text
 * inserts". Reproduced on Saga 202 (release of 2026-10-21):
 *
 *  1. The editor row grows with its text (maxLines 50) inside the pane's ScrollView, but the
 *     edit field was never focused, so TextView never brought the caret into view. Once the
 *     entry is taller than the pane's visible list (about 7 lines on a portrait Saga, 2–3 in
 *     the ~120dp landscape pane) the caret starts at the end, below the viewport, and every
 *     typed character lands there unseen.
 *  2. Tapping the text to move the caret hid the keyboard and discarded the edit when the tap
 *     hit a word the spell checker had underlined: `inputType="none"` in XML is the "unset"
 *     value, so the EditText stayed TYPE_CLASS_TEXT with suggestions, and the spell checker's
 *     SuggestionsPopupWindow is focusable — it took window focus from the app, which then
 *     hid the keyboard (ImeTracker ORIGIN_CLIENT_HIDE_SOFT_INPUT, the same mechanism as the
 *     IME dialogs in [ImeDialogWindowPolicy]). Long entries carry many flagged words.
 *
 * Hosted in [ClipboardEditTestActivity] with a fixed-height ScrollView around the list, the
 * production pane's structure (ScrollView → ClipboardHistoryView).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardLongEntryEditTest {

    private lateinit var context: Context

    /** Twice the line count that fills [PANE_HEIGHT_PX], so the end starts off screen. */
    private val longEntry = (1..40).joinToString("\n") { "line $it of a long to-do entry" }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(obj: Any, fieldName: String): T? {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj) as? T
    }

    private class Hosted(
        val scenario: ActivityScenario<ClipboardEditTestActivity>,
        val chv: ClipboardHistoryView,
        val pane: ScrollView,
    )

    /** The list inside a [PANE_HEIGHT_PX] ScrollView, showing [entries] on [tab]. */
    private fun host(entries: List<ClipboardEntry>, tab: ClipboardTab): Hosted {
        val instr = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(ClipboardEditTestActivity::class.java)
        lateinit var chv: ClipboardHistoryView
        lateinit var pane: ScrollView
        scenario.onActivity { activity ->
            val themed = ContextThemeWrapper(activity, R.style.Dark)
            chv = ClipboardHistoryView(themed, null)
            setField(chv, "currentTab", tab)
            setField(chv, "paginatedHistory", entries)
            pane = ScrollView(themed)
            pane.addView(chv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            activity.container.addView(pane, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, PANE_HEIGHT_PX
            ))
        }
        instr.waitForIdleSync()
        instr.runOnMainSync { getField<BaseAdapter>(chv, "clipboardAdapter")!!.notifyDataSetChanged() }
        instr.waitForIdleSync()
        return Hosted(scenario, chv, pane)
    }

    /** The visible edit field (the view's own scrap-safe lookup). */
    private fun editField(chv: ClipboardHistoryView): EditText {
        val getter = ClipboardHistoryView::class.java.getDeclaredMethod("getActiveEditingEditText")
        getter.isAccessible = true
        return getter.invoke(chv) as EditText
    }

    /** Lets layout, the pre-draw caret scroll and any posted work run. */
    private fun settle() {
        val instr = InstrumentationRegistry.getInstrumentation()
        instr.waitForIdleSync()
        Thread.sleep(300)
        instr.waitForIdleSync()
    }

    /** True when the caret's whole line lies inside the pane's visible viewport. */
    private fun caretLineOnScreen(et: EditText, pane: ScrollView): Boolean {
        val layout = et.layout ?: return false
        val line = layout.getLineForOffset(et.selectionEnd.coerceAtLeast(0))
        val etLoc = IntArray(2).also { et.getLocationInWindow(it) }
        val paneLoc = IntArray(2).also { pane.getLocationInWindow(it) }
        val top = etLoc[1] + et.totalPaddingTop - et.scrollY + layout.getLineTop(line)
        val bottom = etLoc[1] + et.totalPaddingTop - et.scrollY + layout.getLineBottom(line)
        return top >= paneLoc[1] && bottom <= paneLoc[1] + pane.height
    }

    private fun assertCaretFollowsTyping(tab: ClipboardTab, status: String?) {
        val entry = ClipboardEntry(content = longEntry, timestamp = 1_700_000_000_000L, todoStatus = status)
        val hosted = host(listOf(entry), tab)
        val instr = InstrumentationRegistry.getInstrumentation()
        try {
            instr.runOnMainSync { hosted.chv.edit_entry(0) }
            settle()
            instr.runOnMainSync { hosted.chv.insertEditText("Z") }
            settle()

            lateinit var et: EditText
            instr.runOnMainSync { et = editField(hosted.chv) }
            assertTrue("precondition: the editor row is taller than the pane", et.height > hosted.pane.height)
            assertEquals("typed text lands at the end, where the caret starts", longEntry + "Z", et.text.toString())
            var onScreen = false
            instr.runOnMainSync { onScreen = caretLineOnScreen(et, hosted.pane) }
            assertTrue("the caret line (where the typed Z went) must be scrolled into the pane", onScreen)
            assertTrue("the editor holds focus, so its caret is drawn", et.isFocused)
        } finally {
            hosted.scenario.close()
        }
    }

    @Test
    fun typingIntoALongTodoEntryKeepsTheCaretOnScreen() =
        assertCaretFollowsTyping(ClipboardTab.TODOS, TodoEntry.STATUS_ACTIVE)

    @Test
    fun typingIntoALongHistoryEntryKeepsTheCaretOnScreen() =
        assertCaretFollowsTyping(ClipboardTab.HISTORY, null)

    @Test
    fun theEditorRunsNoSpellCheckWhoseFocusablePopupWouldHideTheKeyboard() {
        val entry = ClipboardEntry(content = longEntry, timestamp = 1_700_000_000_000L)
        val hosted = host(listOf(entry), ClipboardTab.PINNED)
        val instr = InstrumentationRegistry.getInstrumentation()
        try {
            instr.runOnMainSync { hosted.chv.edit_entry(0) }
            settle()
            instr.runOnMainSync {
                val et = editField(hosted.chv)
                // isSuggestionsEnabled gates the SpellChecker and its focusable SuggestionsPopupWindow.
                assertFalse("no spell checker / suggestion popup on the IME's own editor", et.isSuggestionsEnabled)
                // The keyboard IS the input method here; never ask the platform to show one.
                assertFalse(et.showSoftInputOnFocus)
            }
        } finally {
            hosted.scenario.close()
        }
    }

    private companion object {
        /** A short pane, as on a phone with the keyboard below it (~7 editor lines). */
        const val PANE_HEIGHT_PX = 500
    }
}
