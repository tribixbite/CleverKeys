package tribixbite.cleverkeys

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso UI tests for the clipboard inline edit feature.
 *
 * These tests tap ACTUAL buttons (edit, save, cancel) via Espresso and verify
 * the view state transitions (visibility, content) through the real UI framework.
 * Typing is done via direct method calls (insertEditText/backspaceEditText)
 * because we ARE the keyboard — Espresso typeText() doesn't work with
 * inputType="none" EditTexts.
 *
 * All tests are Activity-hosted: the ClipboardEditTestActivity provides a real
 * window, layout lifecycle, and focused window for clipboard access.
 * Uses clipboard_entries table (HISTORY tab isn't available without service,
 * so we set paginatedHistory directly via reflection).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardEditEspressoTest {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase
    private val futureExpiry = System.currentTimeMillis() + 3600_000L

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        db = ClipboardDatabase.getInstance(context)
        clearAllTables()
    }

    @After
    fun cleanup() {
        clearAllTables()
    }

    private fun clearAllTables() {
        db.writableDatabase.delete("clipboard_entries", null, null)
        db.writableDatabase.delete("pinned_entries", null, null)
        db.writableDatabase.delete("todo_entries", null, null)
    }

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
     * Launches Activity with CHV containing given entries, sets an ID on the CHV
     * for Espresso matching, and waits for layout to complete.
     */
    private fun launchWithEntries(entries: List<String>): Pair<ActivityScenario<ClipboardEditTestActivity>, ClipboardHistoryView> {
        // Add entries to clipboard DB (newest first in the returned list)
        entries.forEach { content ->
            db.addClipboardEntry(content, futureExpiry)
            Thread.sleep(10)
        }
        val clipEntries = db.getActiveClipboardEntries()

        val scenario = ActivityScenario.launch(ClipboardEditTestActivity::class.java)
        val instr = InstrumentationRegistry.getInstrumentation()
        lateinit var chv: ClipboardHistoryView

        scenario.onActivity { activity ->
            val themed = ContextThemeWrapper(activity, R.style.Dark)
            chv = ClipboardHistoryView(themed, null)
            // Set a known ID for Espresso matching
            chv.id = R.id.clipboard_history_view
            setField(chv, "paginatedHistory", clipEntries)
            activity.container.addView(chv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        instr.waitForIdleSync()

        // Force adapter refresh so getView() renders all items
        instr.runOnMainSync {
            val adapter = getField<android.widget.BaseAdapter>(chv, "clipboardAdapter")!!
            adapter.notifyDataSetChanged()
        }
        instr.waitForIdleSync()

        return Pair(scenario, chv)
    }

    // =========================================================================
    // Edit button tap → enter edit mode
    // =========================================================================

    @Test
    fun tapEditButton_showsEditFieldAndButtons() {
        val (scenario, chv) = launchWithEntries(listOf("Editable text"))
        try {
            // Tap the edit button on the first list item
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_edit))
                .perform(click())

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            // Verify edit mode is active
            var isEditing = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                isEditing = chv.isEditing()
            }
            assertTrue("isEditing() must be true after tapping edit button", isEditing)

            // Verify edit UI: EditText visible, TextView gone, edit buttons visible
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_edit_field))
                .check(matches(isDisplayed()))

            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_edit_buttons))
                .check(matches(isDisplayed()))

            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_primary_buttons))
                .check(matches(not(isDisplayed())))

        } finally { scenario.close() }
    }

    @Test
    fun tapEditButton_editFieldHasEntryContent() {
        val (scenario, chv) = launchWithEntries(listOf("Check this content"))
        try {
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_edit))
                .perform(click())

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            // Verify EditText has the entry content
            var editContent = ""
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editContent = getField<EditText>(chv, "editingEditText")?.text?.toString() ?: "NULL"
            }
            assertEquals("EditText must contain entry content", "Check this content", editContent)

        } finally { scenario.close() }
    }

    // =========================================================================
    // Cancel button tap → exit edit mode
    // =========================================================================

    @Test
    fun tapCancelButton_exitsEditMode() {
        val (scenario, chv) = launchWithEntries(listOf("Cancel test"))
        try {
            // Enter edit mode
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_edit))
                .perform(click())

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            // Type something (direct call — we ARE the keyboard)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                chv.insertEditText(" modified")
            }

            // Tap cancel button
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_cancel))
                .perform(click())

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            // Verify edit mode exited
            var isEditing = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                isEditing = chv.isEditing()
            }
            assertFalse("Must not be editing after cancel", isEditing)

            // Verify normal UI restored: text visible, edit field gone
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_text))
                .check(matches(isDisplayed()))

            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_primary_buttons))
                .check(matches(isDisplayed()))

        } finally { scenario.close() }
    }

    // =========================================================================
    // Full edit lifecycle: tap edit → type → tap save
    // =========================================================================

    @Test
    fun fullLifecycle_tapEdit_type_tapSave() {
        val (scenario, chv) = launchWithEntries(listOf("Original text"))
        try {
            // Tap edit
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_edit))
                .perform(click())

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            // Type changes via direct method (production IME path)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                chv.selectAllEditText()
                chv.insertEditText("Modified text")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            // Verify in-progress text before save
            var editContent = ""
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editContent = getField<EditText>(chv, "editingEditText")?.text?.toString() ?: "NULL"
            }
            assertEquals("EditText should show typed content", "Modified text", editContent)

            // Tap save
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_save))
                .perform(click())

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            // Verify edit mode exited
            var isEditing = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                isEditing = chv.isEditing()
            }
            assertFalse("Must not be editing after save", isEditing)

        } finally { scenario.close() }
    }

    // =========================================================================
    // Edit button hidden for media entries
    // =========================================================================

    @Test
    fun editButton_hiddenForMediaEntries() {
        // Add a media entry — edit button must be GONE
        // Direct getView() check: onAttachedToWindow triggers loadDataAsync which
        // resets paginatedHistory, so we must set data AFTER the async load completes
        db.writableDatabase.delete("clipboard_entries", null, null)
        val mediaEntry = ClipboardEntry(
            content = "photo.jpg",
            timestamp = System.currentTimeMillis(),
            mimeType = "image/jpeg",
            mediaPath = "media/photo.jpg"
        )

        val scenario = ActivityScenario.launch(ClipboardEditTestActivity::class.java)
        val instr = InstrumentationRegistry.getInstrumentation()
        lateinit var chv: ClipboardHistoryView

        scenario.onActivity { activity ->
            val themed = ContextThemeWrapper(activity, R.style.Dark)
            chv = ClipboardHistoryView(themed, null)
            chv.id = R.id.clipboard_history_view
            activity.container.addView(chv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 800
            ))
        }
        // Wait for onAttachedToWindow → loadDataAsync to complete
        instr.waitForIdleSync()
        Thread.sleep(200)  // allow coroutine to settle
        instr.waitForIdleSync()

        // Now set data AFTER async load — this won't be overwritten
        instr.runOnMainSync {
            val mediaList = listOf(mediaEntry)
            setField(chv, "history", mediaList)
            setField(chv, "filteredHistory", mediaList)
            setField(chv, "paginatedHistory", mediaList)
            val adapter = getField<android.widget.BaseAdapter>(chv, "clipboardAdapter")!!
            adapter.notifyDataSetChanged()
        }
        instr.waitForIdleSync()

        try {
            // Render the item view and check edit button visibility directly
            var editButtonVisibility = View.VISIBLE
            instr.runOnMainSync {
                val adapter = getField<android.widget.BaseAdapter>(chv, "clipboardAdapter")!!
                val itemView = adapter.getView(0, null, chv)
                val editBtn = itemView.findViewById<View>(R.id.clipboard_entry_edit)
                editButtonVisibility = editBtn.visibility
            }
            // Edit button must be GONE for media entries
            assertEquals("Edit button must be GONE for media entries",
                View.GONE, editButtonVisibility)

        } finally { scenario.close() }
    }

    // =========================================================================
    // Backspace to empty → type — full Espresso lifecycle
    // =========================================================================

    @Test
    fun tapEdit_backspaceToEmpty_typeNewContent() {
        val (scenario, chv) = launchWithEntries(listOf("Hi"))
        val instr = InstrumentationRegistry.getInstrumentation()
        try {
            // Tap edit button via Espresso
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_edit))
                .perform(click())

            instr.waitForIdleSync()

            // Backspace to empty via direct calls (IME path)
            instr.runOnMainSync {
                chv.backspaceEditText()
                chv.backspaceEditText()
            }
            instr.waitForIdleSync()

            // Verify empty
            var textAfterBackspace = ""
            instr.runOnMainSync {
                textAfterBackspace = getField<EditText>(chv, "editingEditText")?.text?.toString() ?: "NULL"
            }
            assertEquals("Should be empty after backspace", "", textAfterBackspace)

            // Type new content
            instr.runOnMainSync {
                "New".forEach { c -> chv.insertEditText(c.toString()) }
            }
            instr.waitForIdleSync()

            // Verify new content appears
            var result = ""
            instr.runOnMainSync {
                result = getField<EditText>(chv, "editingEditText")?.text?.toString() ?: "NULL"
            }
            assertEquals("Must accept typed text after empty", "New", result)

            // Tap cancel to exit
            onData(anything())
                .inAdapterView(withId(R.id.clipboard_history_view))
                .atPosition(0)
                .onChildView(withId(R.id.clipboard_entry_cancel))
                .perform(click())

            instr.waitForIdleSync()

            assertFalse("Should exit edit mode", chv.isEditing())

        } finally { scenario.close() }
    }
}
