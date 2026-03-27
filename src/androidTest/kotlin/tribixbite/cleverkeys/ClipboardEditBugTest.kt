package tribixbite.cleverkeys

import android.content.Context
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented regression tests for 3 inline edit bugs reported on device:
 *
 * Bug #1 — Search/edit conflict: Typing during edit routes to search field
 *          instead of edit field. Edit mode must disable search mode.
 *          Root cause: edit_entry() didn't exit search mode, and sendText()
 *          checked search before edit in the routing chain.
 *
 * Bug #2 — Cut causes edit drift: Cutting text during edit on the top history
 *          entry causes the edit to jump to the newly-added cut text. Edit must
 *          track by content identity (the original text), not list position.
 *          Root cause: editingPosition was an integer index that shifted when
 *          new entries were added to the list.
 *
 * Bug #3 — Empty text breaks typing: Deleting all text during edit causes
 *          subsequent typed characters to not appear. The edit field breaks.
 *          Root cause: selectionStart could return -1 or stale positions on
 *          empty EditText, and on_clipboard_history_change() triggered view
 *          recreation that overwrote in-progress edits with DB content.
 *
 * These tests exercise the actual ClipboardHistoryView methods using reflection
 * to set internal state where the adapter lifecycle can't be replicated in test.
 * They are designed to FAIL against the pre-fix code and PASS after the fix.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardEditBugTest {

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

    /** Get a private field value via reflection */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(obj: Any, fieldName: String): T? {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj) as? T
    }

    /** Set a private field value via reflection */
    private fun setField(obj: Any, fieldName: String, value: Any?) {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    // =========================================================================
    // Bug #1: Edit mode must disable search mode (mutual exclusion)
    //
    // Regression scenario: User opens clipboard, types in search box to filter
    // entries, then taps edit on a filtered result. Typed characters go to the
    // search box instead of the edit field, changing the filter and causing the
    // active edit to jump to a different entry.
    //
    // Fix: edit_entry() fires onEditModeEntered callback → ClipboardManager
    //      clears search mode. sendText() checks edit BEFORE search.
    // =========================================================================

    @Test
    fun bug1_editEntryFiresOnEditModeEnteredCallback() {
        db.addClipboardEntry("Editable text", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var callbackFired = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)
            chv.onEditModeEntered = { callbackFired = true }

            chv.edit_entry(0)
        }
        assertTrue(
            "onEditModeEntered callback must fire when entering edit mode " +
            "(used by ClipboardManager to clear search)",
            callbackFired
        )
    }

    @Test
    fun bug1_editModeActiveAfterEditEntry() {
        db.addClipboardEntry("Test entry", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var isEditing = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)

            chv.edit_entry(0)
            isEditing = chv.isEditing()
        }
        assertTrue("isEditing() must return true after edit_entry()", isEditing)
    }

    @Test
    fun bug1_editEntrySetsOriginalContent() {
        db.addClipboardEntry("My content", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var originalContent: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)

            chv.edit_entry(0)
            originalContent = getField(chv, "editingOriginalContent")
        }
        assertEquals(
            "editingOriginalContent must match the tapped entry's content",
            "My content", originalContent
        )
    }

    @Test
    fun bug1_editEntryOnMediaIsNoOp() {
        // Media entries cannot be edited inline — edit button should be hidden,
        // and edit_entry() should be a no-op safety guard
        val entries = listOf(
            ClipboardEntry(
                content = "photo.jpg",
                timestamp = System.currentTimeMillis(),
                mimeType = "image/jpeg",
                mediaPath = "media/photo.jpg"
            )
        )

        var isEditing = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)

            chv.edit_entry(0)
            isEditing = chv.isEditing()
        }
        assertFalse("Media entries must not be editable", isEditing)
    }

    // =========================================================================
    // Bug #2: Edit must track by content identity, not list position
    //
    // Regression scenario: User edits the top history entry, selects text and
    // cuts it. The cut puts text on the system clipboard → addCurrentClip()
    // adds a new entry at position 0 → all indices shift. With position-based
    // tracking, editingPosition=0 now points to the new cut text, not the
    // entry being edited.
    //
    // Fix: editingOriginalContent stores the entry's text content instead of
    //      its list position. getView() matches by content == originalContent.
    // =========================================================================

    @Test
    fun bug2_editingOriginalContentIsContentNotPosition() {
        // Add 2 entries: "Second" (newest, pos 0), "First" (oldest, pos 1)
        db.addClipboardEntry("First", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("Second", futureExpiry)
        val entries = db.getActiveClipboardEntries()
        assertEquals("Second", entries[0].content) // newest first
        assertEquals("First", entries[1].content)

        var originalContent: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)

            // Edit "First" at position 1
            chv.edit_entry(1)

            // The stored identity must be the CONTENT, not the position
            originalContent = getField(chv, "editingOriginalContent")
        }
        assertEquals(
            "editingOriginalContent must store content string, not be null or wrong",
            "First", originalContent
        )
    }

    @Test
    fun bug2_editingContentSurvivesListShift() {
        // Simulate the cut scenario: editing an entry, then a new entry appears
        // at the top of the list (shifting all positions)
        db.addClipboardEntry("First", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("Second", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var originalContentAfterShift: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)

            // Start editing "First" (at position 1)
            chv.edit_entry(1)
            assertEquals("First", getField<String>(chv, "editingOriginalContent"))

            // Simulate list shift: new "Cut text" entry added at position 0
            val shiftedEntries = listOf(
                ClipboardEntry(
                    content = "Cut text",
                    timestamp = System.currentTimeMillis() + 100
                )
            ) + entries
            setField(chv, "paginatedHistory", shiftedEntries)

            // "First" is now at position 2, not 1
            assertEquals("First", shiftedEntries[2].content)

            // editingOriginalContent must still be "First" — NOT shifted to "Second"
            originalContentAfterShift = getField(chv, "editingOriginalContent")
        }
        assertEquals(
            "Edit target must survive list shifts (content identity, not position)",
            "First", originalContentAfterShift
        )
    }

    @Test
    fun bug2_onClipboardHistoryChangeSuppressedDuringEdit() {
        db.addClipboardEntry("Edit me", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var isEditingAfterChange = false
        var originalContentAfterChange: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)

            // Enter edit mode
            chv.edit_entry(0)
            assertTrue("Precondition: should be editing", chv.isEditing())

            // Fire on_clipboard_history_change — must be suppressed during edit
            // to prevent view recreation that overwrites in-progress edits
            chv.on_clipboard_history_change()

            isEditingAfterChange = chv.isEditing()
            originalContentAfterChange = getField(chv, "editingOriginalContent")
        }
        assertTrue(
            "Edit mode must survive on_clipboard_history_change()",
            isEditingAfterChange
        )
        assertEquals(
            "editingOriginalContent must survive on_clipboard_history_change()",
            "Edit me", originalContentAfterChange
        )
    }

    @Test
    fun bug2_onWindowVisibilityChangeSuppressedDuringEdit() {
        // onWindowVisibilityChanged(VISIBLE) calls loadDataAsync() which triggers
        // notifyDataSetChanged() → getView() view recreation. Must be suppressed
        // during edit to prevent cursor reset and TextWatcher accumulation.
        db.addClipboardEntry("Window test", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var isEditingAfterVisChange = false
        var inProgressAfterVisChange: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)

            chv.edit_entry(0)
            // Simulate user typing (set in-progress text)
            setField(chv, "editingInProgressText", "Window test modified")

            // Fire onWindowVisibilityChanged via reflection (protected method)
            val method = ClipboardHistoryView::class.java.getDeclaredMethod(
                "onWindowVisibilityChanged", Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(chv, android.view.View.VISIBLE)

            isEditingAfterVisChange = chv.isEditing()
            inProgressAfterVisChange = getField(chv, "editingInProgressText")
        }
        assertTrue(
            "Edit mode must survive onWindowVisibilityChanged(VISIBLE)",
            isEditingAfterVisChange
        )
        assertEquals(
            "editingInProgressText must survive visibility change during edit",
            "Window test modified", inProgressAfterVisChange
        )
    }

    // =========================================================================
    // Bug #3: Empty EditText must accept inserted text
    //
    // Regression scenario: User taps edit, deletes all text with backspace,
    // then types new characters. Characters don't appear — the edit is broken.
    //
    // Root causes:
    //   a) selectionStart returns -1 or stale value on empty EditText
    //   b) on_clipboard_history_change() triggered by clipboard ops during edit
    //      causes loadDataAsync() → notifyDataSetChanged() → getView() which
    //      resets EditText to DB content, wiping in-progress edits
    //   c) No editingInProgressText field to survive view recreation
    //
    // Fix: cursor clamping via coerceIn(0, editable.length), suppress reload
    //      during edit, preserve text via editingInProgressText + TextWatcher.
    // =========================================================================

    @Test
    fun bug3_insertTextIntoEditField() {
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            val et = EditText(context)
            et.setText("Hello")
            et.setSelection(et.text.length)

            // Set up edit state (bypass adapter lifecycle — we're testing the method itself)
            setField(chv, "editingOriginalContent", "Hello")
            setField(chv, "editingEditText", et)

            chv.insertEditText(" World")
            result = et.text.toString()
        }
        assertEquals("insertEditText must append at cursor position", "Hello World", result)
    }

    @Test
    fun bug3_backspaceToEmptyThenInsert() {
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            val et = EditText(context)
            et.setText("abc")
            et.setSelection(et.text.length)

            setField(chv, "editingOriginalContent", "abc")
            setField(chv, "editingEditText", et)

            // Backspace 3 times to empty
            chv.backspaceEditText()
            chv.backspaceEditText()
            chv.backspaceEditText()
            assertEquals("Should be empty after 3 backspaces", "", et.text.toString())

            // Insert new text — THIS IS THE BUG #3 SCENARIO
            chv.insertEditText("X")
            result = et.text.toString()
        }
        assertEquals(
            "Must accept text after backspacing to empty",
            "X", result
        )
    }

    @Test
    fun bug3_backspaceOnEmptyIsNoOp() {
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            val et = EditText(context)
            et.setText("")

            setField(chv, "editingOriginalContent", "")
            setField(chv, "editingEditText", et)

            // Backspace on empty — must not crash or corrupt state
            chv.backspaceEditText()
            result = et.text.toString()
        }
        assertEquals("Backspace on empty must be no-op", "", result)
    }

    @Test
    fun bug3_multipleCharsAfterEmpty() {
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            val et = EditText(context)
            et.setText("Hi")
            et.setSelection(et.text.length)

            setField(chv, "editingOriginalContent", "Hi")
            setField(chv, "editingEditText", et)

            // Delete all
            chv.backspaceEditText()
            chv.backspaceEditText()

            // Type multiple characters one-by-one (simulating keyboard taps)
            chv.insertEditText("N")
            chv.insertEditText("e")
            chv.insertEditText("w")
            result = et.text.toString()
        }
        assertEquals(
            "Characters must accumulate after emptying",
            "New", result
        )
    }

    @Test
    fun bug3_editingInProgressTextPreserved() {
        // Tests that editingInProgressText is set during edit_entry() and survives
        var inProgressText: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)

            db.addClipboardEntry("Preserve me", futureExpiry)
            val entries = db.getActiveClipboardEntries()
            setField(chv, "paginatedHistory", entries)

            chv.edit_entry(0)
            inProgressText = getField(chv, "editingInProgressText")
        }
        assertEquals(
            "editingInProgressText must be initialized to entry content on edit_entry()",
            "Preserve me", inProgressText
        )
    }

    @Test
    fun bug3_editingInProgressTextSurvivesClipboardChange() {
        // The critical scenario: user is typing, clipboard changes fire,
        // but in-progress text must not be wiped
        var inProgressTextAfterChange: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            val et = EditText(context)
            et.setText("Modified by user")
            et.setSelection(et.text.length)

            setField(chv, "editingOriginalContent", "Original text")
            setField(chv, "editingInProgressText", "Modified by user")
            setField(chv, "editingEditText", et)

            // Clipboard change fires during edit — must be suppressed
            chv.on_clipboard_history_change()

            inProgressTextAfterChange = getField(chv, "editingInProgressText")
        }
        assertEquals(
            "editingInProgressText must survive on_clipboard_history_change()",
            "Modified by user", inProgressTextAfterChange
        )
    }

    // =========================================================================
    // Cancel / state cleanup
    // =========================================================================

    @Test
    fun cancelEditClearsAllState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            val et = EditText(context)
            et.setText("Editing")

            setField(chv, "editingOriginalContent", "Editing")
            setField(chv, "editingInProgressText", "Modified")
            setField(chv, "editingEditText", et)
            // Simulate a TextWatcher being attached (as getView would do)
            val watcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
            et.addTextChangedListener(watcher)
            setField(chv, "editingTextWatcher", watcher)

            assertTrue("Precondition: should be editing", chv.isEditing())

            chv.cancelEdit()

            assertFalse("Must not be editing after cancel", chv.isEditing())
            assertNull(
                "editingOriginalContent must be null after cancel",
                getField<String>(chv, "editingOriginalContent")
            )
            assertNull(
                "editingInProgressText must be null after cancel",
                getField<String>(chv, "editingInProgressText")
            )
            assertNull(
                "editingEditText must be null after cancel",
                getField<EditText>(chv, "editingEditText")
            )
            assertNull(
                "editingTextWatcher must be null after cancel",
                getField<android.text.TextWatcher>(chv, "editingTextWatcher")
            )
        }
    }

    @Test
    fun editEntryBoundsCheckPreventsOutOfBounds() {
        val entries = listOf(
            ClipboardEntry(content = "Only entry", timestamp = System.currentTimeMillis())
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)

            // Out-of-bounds position — must not crash, must not enter edit mode
            chv.edit_entry(-1)
            assertFalse("Negative position must not enter edit mode", chv.isEditing())

            chv.edit_entry(5)
            assertFalse("Position beyond list size must not enter edit mode", chv.isEditing())
        }
    }

    // =========================================================================
    // Integration: full edit lifecycle with text manipulation
    // =========================================================================

    @Test
    fun integration_fullEditLifecycle() {
        db.addClipboardEntry("Original text", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var editContent: String? = null
        var editTextFinal: String? = null
        var isEditingDuring = false
        var isEditingAfter = false

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(context, null)
            setField(chv, "paginatedHistory", entries)

            // 1. Enter edit mode
            chv.edit_entry(0)
            isEditingDuring = chv.isEditing()
            editContent = getField(chv, "editingOriginalContent")

            // 2. Set up EditText (simulating getView adapter callback)
            val et = EditText(context)
            et.setText("Original text")
            et.setSelection(et.text.length)
            setField(chv, "editingEditText", et)

            // 3. Type some text
            chv.insertEditText("!")
            assertEquals("Original text!", et.text.toString())

            // 4. Clipboard change during edit — must be suppressed
            chv.on_clipboard_history_change()
            assertTrue("Should still be editing after clipboard change", chv.isEditing())

            // 5. Backspace to remove the "!" and more
            chv.backspaceEditText() // removes "!"
            chv.backspaceEditText() // removes "t"
            assertEquals("Original tex", et.text.toString())

            // 6. Type replacement
            chv.insertEditText("t fixed")
            editTextFinal = et.text.toString()

            // 7. Cancel edit
            chv.cancelEdit()
            isEditingAfter = chv.isEditing()
        }

        assertTrue("Should be editing during lifecycle", isEditingDuring)
        assertEquals("editingOriginalContent should be entry content", "Original text", editContent)
        assertEquals("Final text after edits", "Original text fixed", editTextFinal)
        assertFalse("Should not be editing after cancel", isEditingAfter)
    }
}
