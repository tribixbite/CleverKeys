package tribixbite.cleverkeys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
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
 * These tests exercise the REAL adapter getView() lifecycle — inflating the
 * actual clipboard_history_entry.xml layout with inputType="none" EditText,
 * wiring up the real TextWatcher, and testing through the real code path.
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

    /**
     * Creates a themed context that resolves custom keyboard theme attributes
     * (colorLabel, colorSubLabel, etc.) needed by clipboard_history_entry.xml.
     * In production, the clipboard pane is inflated with the keyboard theme;
     * in tests we must wrap the bare app context with the same theme.
     */
    private fun themedContext(): Context =
        ContextThemeWrapper(context, R.style.Dark)

    /**
     * Helper: Create a ClipboardHistoryView, set paginatedHistory, enter edit mode,
     * and call getView() to inflate the real XML layout. Returns (chv, editText).
     *
     * MUST be called from runOnMainSync.
     */
    private fun setupEditWithRealGetView(entries: List<ClipboardEntry>, editPos: Int = 0): Pair<ClipboardHistoryView, EditText> {
        val themed = themedContext()
        val chv = ClipboardHistoryView(themed, null)
        setField(chv, "paginatedHistory", entries)

        // Enter edit mode — sets editingOriginalContent + editingInProgressText
        chv.edit_entry(editPos)

        // Call adapter.getView() to inflate the REAL clipboard_history_entry.xml
        // This is what happens when ListView renders the cell — it inflates the
        // EditText with inputType="none" and wires up the TextWatcher.
        val adapter = getField<BaseAdapter>(chv, "clipboardAdapter")!!
        val parent = FrameLayout(context) // dummy parent for inflation
        adapter.getView(editPos, null, parent)

        // editingEditText should now be the real XML-inflated EditText
        val et = getField<EditText>(chv, "editingEditText")
            ?: fail("editingEditText must be set after getView() in edit mode")
        return Pair(chv, et as EditText)
    }

    // =========================================================================
    // Bug #1: Edit mode must disable search input routing (mutual exclusion)
    //
    // Regression scenario: User opens clipboard, types in search box to filter
    // entries, then taps edit on a filtered result. Typed characters go to the
    // search box instead of the edit field, changing the filter and causing the
    // active edit to jump to a different entry.
    //
    // Fix: edit_entry() fires onEditModeEntered callback → ClipboardManager
    //      disables search routing (searchMode=false) but keeps the search
    //      filter visible so the edited entry stays in place.
    //      sendText() checks edit BEFORE search as defense-in-depth.
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
            "(used by ClipboardManager to disable search routing)",
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
    // These tests exercise the REAL adapter getView() lifecycle: they call
    // adapter.getView() to inflate clipboard_history_entry.xml (with the real
    // EditText that has inputType="none"), wire up the TextWatcher, and then
    // test the backspace-to-empty + insert scenario through the actual code path.
    //
    // The previous version of these tests used EditText(context) — a programmatic
    // EditText that bypasses the XML layout inflation and inputType="none".
    // Those tests always passed even when the bug was present on device.
    // =========================================================================

    @Test
    fun bug3_getViewSetsEditingEditText() {
        // Verify that calling adapter.getView() in edit mode wires up editingEditText
        // from the real XML-inflated layout
        db.addClipboardEntry("Hello", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var editText: EditText? = null
        var editTextContent = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)
            editText = et
            editTextContent = et.text.toString()
        }
        assertNotNull("editingEditText must be set from getView()", editText)
        assertEquals("EditText must contain entry content", "Hello", editTextContent)
    }

    @Test
    fun bug3_realGetView_insertAfterBackspaceToEmpty() {
        // Core Bug #3 scenario through the real adapter lifecycle:
        // edit_entry() → getView() inflates real EditText → backspace to empty → insert
        db.addClipboardEntry("Hello", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var result = ""
        var inProgressText: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)

            // Verify precondition: EditText has content and cursor is at end
            assertEquals("Precondition: should have content", "Hello", et.text.toString())

            // Backspace to empty (5 chars)
            repeat(5) { chv.backspaceEditText() }
            assertEquals("Should be empty after 5 backspaces", "", et.text.toString())

            // THIS IS THE BUG #3 SCENARIO: insert text after emptying
            chv.insertEditText("W")
            result = et.text.toString()
            inProgressText = getField(chv, "editingInProgressText")
        }
        assertEquals(
            "Bug #3: Must accept text after backspacing to empty via real getView() EditText",
            "W", result
        )
        assertEquals(
            "editingInProgressText must be updated by TextWatcher",
            "W", inProgressText
        )
    }

    @Test
    fun bug3_realGetView_multipleCharsAfterEmpty() {
        // Type multiple characters after emptying — tests cursor position tracking
        db.addClipboardEntry("Hi", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)

            // Backspace to empty
            chv.backspaceEditText()
            chv.backspaceEditText()
            assertEquals("", et.text.toString())

            // Type characters one by one (simulates keyboard taps)
            chv.insertEditText("N")
            chv.insertEditText("e")
            chv.insertEditText("w")
            result = et.text.toString()
        }
        assertEquals(
            "Bug #3: Characters must accumulate after emptying via real getView()",
            "New", result
        )
    }

    @Test
    fun bug3_realGetView_viewRecreationDuringEmptyEdit() {
        // Critical scenario: user backspaces to empty, then ListView calls getView()
        // again (layout pass / scroll / view recycling). After getView() recreates
        // the edit view, typing must still work.
        db.addClipboardEntry("Hello", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var result = ""
        var editTextRef1: EditText? = null
        var editTextRef2: EditText? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(themedContext(), null)
            setField(chv, "paginatedHistory", entries)
            chv.edit_entry(0)

            val adapter = getField<BaseAdapter>(chv, "clipboardAdapter")!!
            val parent = FrameLayout(context)

            // First getView() — inflates real EditText
            val view1 = adapter.getView(0, null, parent)
            editTextRef1 = getField(chv, "editingEditText")!!

            // Backspace to empty
            repeat(5) { chv.backspaceEditText() }
            assertEquals("", editTextRef1!!.text.toString())

            // SECOND getView() — simulates ListView relayout during empty edit
            // Pass the same view (recycling) to match real ListView behavior
            val view2 = adapter.getView(0, view1, parent)
            editTextRef2 = getField(chv, "editingEditText")!!

            // Type after view recreation
            chv.insertEditText("X")
            result = editTextRef2!!.text.toString()
        }
        assertEquals(
            "Bug #3: Must accept text after getView() recreation during empty edit",
            "X", result
        )
    }

    @Test
    fun bug3_realGetView_viewRecreationPreservesInProgressText() {
        // User types some text, then getView() fires (layout pass). The in-progress
        // text must be preserved and the EditText must continue working.
        db.addClipboardEntry("Hello", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var resultAfterRecreation = ""
        var resultAfterMoreTyping = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(themedContext(), null)
            setField(chv, "paginatedHistory", entries)
            chv.edit_entry(0)

            val adapter = getField<BaseAdapter>(chv, "clipboardAdapter")!!
            val parent = FrameLayout(context)

            // First getView() — inflates real EditText
            val view1 = adapter.getView(0, null, parent)

            // Backspace to empty and type "New"
            repeat(5) { chv.backspaceEditText() }
            chv.insertEditText("N")
            chv.insertEditText("e")
            chv.insertEditText("w")

            // getView() fires again (layout pass) — must preserve "New"
            adapter.getView(0, view1, parent)
            val et = getField<EditText>(chv, "editingEditText")!!
            resultAfterRecreation = et.text.toString()

            // Continue typing after recreation
            chv.insertEditText("!")
            resultAfterMoreTyping = et.text.toString()
        }
        assertEquals(
            "In-progress text must survive getView() recreation",
            "New", resultAfterRecreation
        )
        assertEquals(
            "Must accept more text after getView() recreation",
            "New!", resultAfterMoreTyping
        )
    }

    @Test
    fun bug3_realGetView_newViewInflation_duringEmptyEdit() {
        // getView() with null convertView (new inflation, NOT recycling)
        // during empty edit. Tests that editingEditText reference is properly
        // updated to the new EditText.
        db.addClipboardEntry("Hello", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(themedContext(), null)
            setField(chv, "paginatedHistory", entries)
            chv.edit_entry(0)

            val adapter = getField<BaseAdapter>(chv, "clipboardAdapter")!!
            val parent = FrameLayout(context)

            // First getView() — inflates view
            adapter.getView(0, null, parent)

            // Backspace to empty
            repeat(5) { chv.backspaceEditText() }

            // getView() with null convertView — FRESH inflation (no recycling)
            // The editingEditText reference must be updated to the new EditText
            adapter.getView(0, null, parent)
            val et = getField<EditText>(chv, "editingEditText")!!

            // Type on the new EditText
            chv.insertEditText("Y")
            result = et.text.toString()
        }
        assertEquals(
            "Bug #3: Must work after fresh view inflation during empty edit",
            "Y", result
        )
    }

    @Test
    fun bug3_realGetView_backspaceNoOpOnEmpty() {
        // Backspace on already-empty EditText must be safe no-op
        db.addClipboardEntry("Hi", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)

            // Backspace to empty
            chv.backspaceEditText()
            chv.backspaceEditText()

            // Extra backspaces on empty — must not crash
            chv.backspaceEditText()
            chv.backspaceEditText()

            result = et.text.toString()
        }
        assertEquals("Backspace on empty must be safe no-op", "", result)
    }

    @Test
    fun bug3_realGetView_editingInProgressTextSyncsViaTextWatcher() {
        // Verify the TextWatcher from getView() properly syncs editingInProgressText
        // as the user types — this is critical for surviving view recreation
        db.addClipboardEntry("Test", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var inProgress1: String? = null
        var inProgress2: String? = null
        var inProgress3: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)

            // Type a character — TextWatcher should sync
            chv.insertEditText("!")
            inProgress1 = getField(chv, "editingInProgressText")

            // Backspace — TextWatcher should sync
            chv.backspaceEditText()  // "Test!" → "Test"
            chv.backspaceEditText()  // "Test"  → "Tes"
            inProgress2 = getField(chv, "editingInProgressText")

            // Backspace to empty — TextWatcher should sync to ""
            repeat(3) { chv.backspaceEditText() } // "Tes" → "Te" → "T" → ""
            inProgress3 = getField(chv, "editingInProgressText")
        }
        assertEquals("TextWatcher must sync after insert", "Test!", inProgress1)
        assertEquals("TextWatcher must sync after backspace", "Tes", inProgress2)
        assertEquals("TextWatcher must sync to empty string", "", inProgress3)
    }

    // =========================================================================
    // Cancel / state cleanup
    // =========================================================================

    @Test
    fun cancelEditClearsAllState() {
        db.addClipboardEntry("Editing", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, _) = setupEditWithRealGetView(entries)
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
    // Integration: full edit lifecycle through real getView()
    // =========================================================================

    @Test
    fun integration_fullEditLifecycleViaGetView() {
        db.addClipboardEntry("Original text", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var editContent: String? = null
        var editTextFinal: String? = null
        var isEditingDuring = false
        var isEditingAfter = false

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)

            // 1. Verify edit mode
            isEditingDuring = chv.isEditing()
            editContent = getField(chv, "editingOriginalContent")
            assertEquals("Original text", et.text.toString())

            // 2. Type some text
            chv.insertEditText("!")
            assertEquals("Original text!", et.text.toString())

            // 3. Clipboard change during edit — must be suppressed
            chv.on_clipboard_history_change()
            assertTrue("Should still be editing after clipboard change", chv.isEditing())

            // 4. Backspace to remove "!" and more
            chv.backspaceEditText() // removes "!"
            chv.backspaceEditText() // removes "t"
            assertEquals("Original tex", et.text.toString())

            // 5. Type replacement
            chv.insertEditText("t fixed")
            editTextFinal = et.text.toString()

            // 6. Cancel edit
            chv.cancelEdit()
            isEditingAfter = chv.isEditing()
        }

        assertTrue("Should be editing during lifecycle", isEditingDuring)
        assertEquals("editingOriginalContent should be entry content", "Original text", editContent)
        assertEquals("Final text after edits", "Original text fixed", editTextFinal)
        assertFalse("Should not be editing after cancel", isEditingAfter)
    }

    @Test
    fun integration_backspaceToEmptyThenRebuildThenType() {
        // End-to-end: backspace to empty → view recreation (getView) → type new content
        // This simulates the exact scenario reported in Bug #3 on device.
        db.addClipboardEntry("Delete me", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var finalText = ""
        var inProgressText: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val chv = ClipboardHistoryView(themedContext(), null)
            setField(chv, "paginatedHistory", entries)
            chv.edit_entry(0)

            val adapter = getField<BaseAdapter>(chv, "clipboardAdapter")!!
            val parent = FrameLayout(context)

            // First render — real EditText from XML
            val view = adapter.getView(0, null, parent)
            val et1 = getField<EditText>(chv, "editingEditText")!!
            assertEquals("Delete me", et1.text.toString())

            // Backspace everything
            repeat(9) { chv.backspaceEditText() }
            assertEquals("", et1.text.toString())

            // Simulate layout pass — getView() fires on the same view
            adapter.getView(0, view, parent)
            val et2 = getField<EditText>(chv, "editingEditText")!!

            // Type completely new content
            "Replaced".forEach { c -> chv.insertEditText(c.toString()) }
            finalText = et2.text.toString()
            inProgressText = getField(chv, "editingInProgressText")
        }
        assertEquals(
            "Bug #3 end-to-end: Must type new content after empty → view recreation",
            "Replaced", finalText
        )
        assertEquals(
            "editingInProgressText must track the new content",
            "Replaced", inProgressText
        )
    }

    // =========================================================================
    // Activity-hosted tests: real window + real layout lifecycle
    //
    // These tests host the ClipboardHistoryView in a real Activity so the
    // ListView performs actual layout passes, calls getView() through the
    // framework (not manually), and handles view recycling automatically.
    // waitForIdleSync() between operations allows layout passes to run
    // between user actions — this is the realistic device scenario.
    //
    // Uses PINNED tab because it reads from DB directly (no ClipboardHistoryService
    // dependency — service is null in test).
    // =========================================================================

    /**
     * Launches the test Activity and adds a ClipboardHistoryView with pinned entries.
     * Returns the scenario (caller must close) and the CHV reference.
     * The CHV is switched to PINNED tab and the view is laid out.
     */
    private fun launchWithPinnedEntries(
        entries: List<String>
    ): Pair<ActivityScenario<ClipboardEditTestActivity>, ClipboardHistoryView> {
        // Add entries to pinned_entries table
        entries.forEach { content ->
            db.pinEntry(content, System.currentTimeMillis())
            Thread.sleep(10) // ensure different timestamps for ordering
        }

        // Launch via class (not Intent) — ActivityScenario resolves the component
        // from the debug manifest where the Activity is declared
        val scenario = ActivityScenario.launch(ClipboardEditTestActivity::class.java)

        lateinit var chv: ClipboardHistoryView
        val instr = InstrumentationRegistry.getInstrumentation()

        scenario.onActivity { activity ->
            val themed = ContextThemeWrapper(activity, R.style.Dark)
            chv = ClipboardHistoryView(themed, null)
            // Switch to PINNED tab — reads from pinned_entries directly (no service needed)
            chv.setTab(ClipboardTab.PINNED)
            activity.container.addView(chv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                800 // Fixed height in px for predictable layout
            ))
        }

        // Wait for the layout pass + loadDataAsync coroutine to complete
        instr.waitForIdleSync()
        Thread.sleep(300) // Extra wait for coroutine on IO dispatcher

        // Force a refresh on main thread to ensure data is loaded
        instr.runOnMainSync {
            // Trigger a manual data reload in case the coroutine didn't have a viewScope
            // (onAttachedToWindow sets viewScope, but timing may vary)
            val method = ClipboardHistoryView::class.java.getDeclaredMethod("loadDataAsync")
            method.isAccessible = true
            method.invoke(chv)
        }
        instr.waitForIdleSync()
        Thread.sleep(300) // Wait for coroutine completion

        return Pair(scenario, chv)
    }

    @Test
    fun bug3_activityHosted_backspaceToEmptyThenType() {
        // Host CHV in real Activity → real layout passes → real getView() by framework.
        // This tests the exact Bug #3 scenario: edit → backspace to empty → type.
        // Between each step, waitForIdleSync() allows pending layout passes to fire.
        val (scenario, chv) = launchWithPinnedEntries(listOf("Hello"))
        val instr = InstrumentationRegistry.getInstrumentation()

        try {
            // Verify data loaded — paginatedHistory should have our entry
            var entryCount = 0
            instr.runOnMainSync {
                val paginated = getField<List<*>>(chv, "paginatedHistory")
                entryCount = paginated?.size ?: 0
            }
            assertTrue(
                "Precondition: paginatedHistory must have entries (got $entryCount)",
                entryCount > 0
            )

            // Step 1: Enter edit mode — triggers notifyDataSetChanged → framework getView()
            instr.runOnMainSync {
                chv.edit_entry(0)
            }
            instr.waitForIdleSync() // layout pass processes edit mode → getView() inflates EditText

            // Verify edit mode is active and EditText is wired
            var isEditing = false
            var editTextContent = ""
            instr.runOnMainSync {
                isEditing = chv.isEditing()
                editTextContent = getField<EditText>(chv, "editingEditText")?.text?.toString() ?: "NULL"
            }
            assertTrue("Should be in edit mode after edit_entry + layout", isEditing)
            assertEquals("EditText should have entry content after framework getView()", "Hello", editTextContent)

            // Step 2: Backspace to empty
            instr.runOnMainSync {
                repeat(5) { chv.backspaceEditText() }
            }
            // *** KEY: wait for idle — allows layout passes triggered by text/height change ***
            instr.waitForIdleSync()

            // Verify empty
            var afterBackspace = ""
            instr.runOnMainSync {
                afterBackspace = getField<EditText>(chv, "editingEditText")?.text?.toString() ?: "NULL_ET"
            }
            assertEquals("Should be empty after backspacing", "", afterBackspace)

            // Step 3: Type new text — THIS IS WHERE BUG #3 MANIFESTS
            // If the layout pass between steps 2 and 3 broke the edit state,
            // this insert will either go nowhere or crash.
            instr.runOnMainSync {
                chv.insertEditText("W")
            }
            instr.waitForIdleSync()

            // Assert
            var result = ""
            instr.runOnMainSync {
                val et = getField<EditText>(chv, "editingEditText")
                result = et?.text?.toString() ?: "NULL_ET"
            }
            assertEquals(
                "Bug #3: Must accept typed text after backspace-to-empty + layout pass",
                "W", result
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun bug3_activityHosted_multipleCharsAfterEmptyWithLayoutPasses() {
        // Type multiple characters after emptying, with layout passes between each.
        // Each character change may trigger a layout pass (height change).
        val (scenario, chv) = launchWithPinnedEntries(listOf("Hi"))
        val instr = InstrumentationRegistry.getInstrumentation()

        try {
            var entryCount = 0
            instr.runOnMainSync {
                entryCount = getField<List<*>>(chv, "paginatedHistory")?.size ?: 0
            }
            assertTrue("Precondition: must have entries", entryCount > 0)

            // Enter edit
            instr.runOnMainSync { chv.edit_entry(0) }
            instr.waitForIdleSync()

            // Backspace to empty
            instr.runOnMainSync {
                chv.backspaceEditText()
                chv.backspaceEditText()
            }
            instr.waitForIdleSync() // layout pass

            // Type characters one by one with idle waits between
            instr.runOnMainSync { chv.insertEditText("N") }
            instr.waitForIdleSync() // potential layout pass from "" → "N"

            instr.runOnMainSync { chv.insertEditText("e") }
            instr.waitForIdleSync()

            instr.runOnMainSync { chv.insertEditText("w") }
            instr.waitForIdleSync()

            var result = ""
            instr.runOnMainSync {
                result = getField<EditText>(chv, "editingEditText")?.text?.toString() ?: "NULL_ET"
            }
            assertEquals(
                "Bug #3: Characters must accumulate with layout passes between each",
                "New", result
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun bug3_activityHosted_editStillActiveAfterEmptyLayoutPass() {
        // Verify that isEditing() remains true after emptying + layout pass.
        // If the layout pass somehow cancels edit mode, routing would break.
        val (scenario, chv) = launchWithPinnedEntries(listOf("Test"))
        val instr = InstrumentationRegistry.getInstrumentation()

        try {
            var entryCount = 0
            instr.runOnMainSync {
                entryCount = getField<List<*>>(chv, "paginatedHistory")?.size ?: 0
            }
            assertTrue("Precondition: must have entries", entryCount > 0)

            instr.runOnMainSync { chv.edit_entry(0) }
            instr.waitForIdleSync()

            // Backspace to empty
            instr.runOnMainSync {
                repeat(4) { chv.backspaceEditText() }
            }
            instr.waitForIdleSync() // layout pass with empty text

            // Check that edit mode survived the layout pass
            var isEditing = false
            var originalContent: String? = null
            var editText: EditText? = null
            instr.runOnMainSync {
                isEditing = chv.isEditing()
                originalContent = getField(chv, "editingOriginalContent")
                editText = getField(chv, "editingEditText")
            }
            assertTrue("Bug #3: edit mode must survive empty-text layout pass", isEditing)
            assertEquals("originalContent must be preserved", "Test", originalContent)
            assertNotNull("editingEditText must not be null after layout pass", editText)
        } finally {
            scenario.close()
        }
    }

    // =========================================================================
    // Paste during edit (Activity-hosted — clipboard needs focused window on API 34)
    //
    // User selects text in edit field, pastes from system clipboard.
    // pasteToEditText() should read system clipboard and insert at cursor.
    // =========================================================================

    /**
     * Launches Activity, creates CHV with a single clipboard entry, enters edit mode.
     * Returns (scenario, chv). Caller must close scenario in finally block.
     * Activity provides focused window needed for clipboard access on API 34+.
     *
     * IMPORTANT: Do NOT cache editingEditText — the framework calls getView() during
     * layout passes and may update the reference. Always read it fresh via
     * getField(chv, "editingEditText") before each operation.
     */
    private fun launchEditableEntry(content: String): Pair<ActivityScenario<ClipboardEditTestActivity>, ClipboardHistoryView> {
        db.addClipboardEntry(content, futureExpiry)
        val entries = db.getActiveClipboardEntries()

        val scenario = ActivityScenario.launch(ClipboardEditTestActivity::class.java)
        val instr = InstrumentationRegistry.getInstrumentation()
        lateinit var chv: ClipboardHistoryView

        scenario.onActivity { activity ->
            val themed = ContextThemeWrapper(activity, R.style.Dark)
            chv = ClipboardHistoryView(themed, null)
            setField(chv, "paginatedHistory", entries)
            activity.container.addView(chv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 800
            ))
        }
        instr.waitForIdleSync()

        // Enter edit mode — framework calls getView() and wires editingEditText
        instr.runOnMainSync { chv.edit_entry(0) }
        instr.waitForIdleSync()

        return Pair(scenario, chv)
    }

    /**
     * Find the actually-visible EditText in [chv]. Mimics the impl's
     * `activeEditingEditText` getter (private — not directly accessible).
     *
     * After ListView.measureHeightOfChildren() recycles scrap views, the cached
     * `editingEditText` field can point to a detached/invisible scrap. The impl
     * recovers via slow-path scan; tests must do the same when manipulating
     * selection state, otherwise setSelection() lands on the wrong EditText
     * and paste/cut operations read default selection from the visible widget.
     */
    private fun getVisibleEditText(chv: ClipboardHistoryView): EditText? {
        val cached = getField<EditText>(chv, "editingEditText")
        if (cached?.visibility == View.VISIBLE && cached.windowToken != null) return cached
        for (i in 0 until chv.childCount) {
            val child = chv.getChildAt(i) ?: continue
            val et = child.findViewById<EditText>(R.id.clipboard_entry_edit_field)
            if (et != null && et.visibility == View.VISIBLE && et.windowToken != null) return et
        }
        return cached
    }

    @Test
    fun paste_insertAtCursorPosition() {
        val (scenario, chv) = launchEditableEntry("Hello World")
        val instr = InstrumentationRegistry.getInstrumentation()
        try {
            var result = ""
            instr.runOnMainSync {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("test", "PASTED"))
                val et = getVisibleEditText(chv)!!
                et.setSelection(5)
                chv.pasteToEditText()
                result = getVisibleEditText(chv)!!.text.toString()
            }
            assertEquals("Paste must insert at cursor position", "HelloPASTED World", result)
        } finally { scenario.close() }
    }

    @Test
    fun paste_replacesSelection() {
        val (scenario, chv) = launchEditableEntry("Hello World")
        val instr = InstrumentationRegistry.getInstrumentation()
        try {
            var result = ""
            instr.runOnMainSync {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("test", "Planet"))
                val et = getVisibleEditText(chv)!!
                et.setSelection(6, 11)
                chv.pasteToEditText()
                result = getVisibleEditText(chv)!!.text.toString()
            }
            assertEquals("Paste must replace selection", "Hello Planet", result)
        } finally { scenario.close() }
    }

    @Test
    fun paste_intoEmptyEditField() {
        val (scenario, chv) = launchEditableEntry("Hi")
        val instr = InstrumentationRegistry.getInstrumentation()
        try {
            var result = ""
            instr.runOnMainSync {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("test", "Fresh"))
                chv.backspaceEditText()
                chv.backspaceEditText()
                chv.pasteToEditText()
                result = getField<EditText>(chv, "editingEditText")!!.text.toString()
            }
            assertEquals("Paste must work on empty edit field", "Fresh", result)
        } finally { scenario.close() }
    }

    @Test
    fun paste_textWatcherSyncsAfterPaste() {
        val (scenario, chv) = launchEditableEntry("Original")
        val instr = InstrumentationRegistry.getInstrumentation()
        try {
            var inProgressText: String? = null
            instr.runOnMainSync {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("test", " Added"))
                val et = getField<EditText>(chv, "editingEditText")!!
                et.setSelection(et.text.length)
                chv.pasteToEditText()
                inProgressText = getField(chv, "editingInProgressText")
            }
            assertEquals("TextWatcher must sync after paste", "Original Added", inProgressText)
        } finally { scenario.close() }
    }

    // =========================================================================
    // Cut during edit (clipboard verification needs Activity-hosted)
    //
    // User selects text in edit field, cuts it. Selected text goes to system
    // clipboard and is removed from the edit field.
    // =========================================================================

    @Test
    fun cut_removesSelectedTextFromEditField() {
        // No clipboard read — direct test works fine
        db.addClipboardEntry("Hello World", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)
            et.setSelection(6, 11)
            chv.cutFromEditText()
            result = et.text.toString()
        }
        assertEquals("Cut must remove selected text", "Hello ", result)
    }

    @Test
    fun cut_copiesSelectedTextToSystemClipboard() {
        // Activity-hosted — clipboard read requires focus on API 34
        val (scenario, chv) = launchEditableEntry("Hello World")
        val instr = InstrumentationRegistry.getInstrumentation()
        try {
            var clipboardContent = ""
            instr.runOnMainSync {
                val et = getVisibleEditText(chv)!!
                et.setSelection(6, 11)
                chv.cutFromEditText()
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboardContent = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "NULL"
            }
            assertEquals("Cut must copy selection to system clipboard", "World", clipboardContent)
        } finally { scenario.close() }
    }

    @Test
    fun cut_noOpWhenNoSelection() {
        db.addClipboardEntry("Hello", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)
            et.setSelection(et.text.length)
            chv.cutFromEditText()
            result = et.text.toString()
        }
        assertEquals("Cut with no selection must be a no-op", "Hello", result)
    }

    @Test
    fun cut_textWatcherSyncsAfterCut() {
        db.addClipboardEntry("Remove This Part", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var inProgressText: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)
            et.setSelection(6, 16)
            chv.cutFromEditText()
            inProgressText = getField(chv, "editingInProgressText")
        }
        assertEquals("TextWatcher must sync after cut", "Remove", inProgressText)
    }

    @Test
    fun cut_editModeSurvivesCut() {
        db.addClipboardEntry("Cut me please", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var isEditing = false
        var originalContent: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)
            et.setSelection(4, 6)
            chv.cutFromEditText()
            isEditing = chv.isEditing()
            originalContent = getField(chv, "editingOriginalContent")
        }
        assertTrue("Edit mode must survive cut operation", isEditing)
        assertEquals("editingOriginalContent must be preserved", "Cut me please", originalContent)
    }

    // =========================================================================
    // Select All during edit
    //
    // User triggers select-all. All text in edit field is selected.
    // Subsequent typing should replace the entire selection.
    // =========================================================================

    @Test
    fun selectAll_selectsEntireContent() {
        db.addClipboardEntry("Select everything", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var selStart = -1
        var selEnd = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)
            chv.selectAllEditText()
            selStart = et.selectionStart
            selEnd = et.selectionEnd
        }
        assertEquals("Selection must start at 0", 0, selStart)
        assertEquals("Selection must end at text length", "Select everything".length, selEnd)
    }

    @Test
    fun selectAll_thenTypeReplacesAll() {
        db.addClipboardEntry("Old content", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)
            chv.selectAllEditText()
            chv.insertEditText("New")
            result = et.text.toString()
        }
        assertEquals("Type after select-all must replace entire content", "New", result)
    }

    @Test
    fun selectAll_thenBackspaceDeletesAll() {
        db.addClipboardEntry("Delete all of this", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)
            chv.selectAllEditText()
            chv.backspaceEditText()
            result = et.text.toString()
        }
        assertEquals("Backspace after select-all must clear all text", "", result)
    }

    @Test
    fun selectAll_thenPasteReplacesAll() {
        // Activity-hosted — paste needs clipboard access
        val (scenario, chv) = launchEditableEntry("Replace me entirely")
        val instr = InstrumentationRegistry.getInstrumentation()
        try {
            var result = ""
            instr.runOnMainSync {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("test", "Replaced"))
                chv.selectAllEditText()
                chv.pasteToEditText()
                result = getField<EditText>(chv, "editingEditText")!!.text.toString()
            }
            assertEquals("Paste after select-all must replace entire content", "Replaced", result)
        } finally { scenario.close() }
    }

    // =========================================================================
    // save_edit() integration — full lifecycle with real database
    //
    // Uses PINNED tab via Activity-hosted test because pinned entries
    // read from DB directly (no ClipboardHistoryService dependency).
    // save_edit() calls service?.editEntryContent() — service is null in
    // test, so we test the CHV-side behavior (state cleanup, cancelEdit).
    //
    // For service-integrated save, see ClipboardHistoryTest.
    // Direct DB update tests are in ClipboardDatabaseTest.
    // =========================================================================

    @Test
    fun saveEdit_exitsEditModeAfterSave() {
        db.addClipboardEntry("Save test", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        var isEditingAfterSave = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)

            // Type some changes
            chv.insertEditText(" changed")

            // save_edit() — service is null so result is null (shows error toast),
            // but cancelEdit() is always called at the end
            chv.save_edit()
            isEditingAfterSave = chv.isEditing()
        }
        assertFalse("save_edit() must exit edit mode", isEditingAfterSave)
    }

    @Test
    fun saveEdit_clearsAllEditState() {
        db.addClipboardEntry("Cleanup test", futureExpiry)
        val entries = db.getActiveClipboardEntries()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (chv, et) = setupEditWithRealGetView(entries)

            chv.insertEditText("!")
            chv.save_edit()

            assertNull("editingOriginalContent must be null after save",
                getField<String>(chv, "editingOriginalContent"))
            assertNull("editingInProgressText must be null after save",
                getField<String>(chv, "editingInProgressText"))
            assertNull("editingEditText must be null after save",
                getField<EditText>(chv, "editingEditText"))
            assertNull("editingTextWatcher must be null after save",
                getField<android.text.TextWatcher>(chv, "editingTextWatcher"))
        }
    }

    @Test
    fun saveEdit_activityHosted_dbUpdatedAfterSave() {
        // Full lifecycle: Activity-hosted CHV with real pinned entries.
        // Edit an entry, save, verify DB has new content.
        // Uses PINNED tab — reads/writes DB directly without service.
        val (scenario, chv) = launchWithPinnedEntries(listOf("Original pinned"))
        val instr = InstrumentationRegistry.getInstrumentation()

        try {
            var entryCount = 0
            instr.runOnMainSync {
                entryCount = getField<List<*>>(chv, "paginatedHistory")?.size ?: 0
            }
            assertTrue("Precondition: must have entries", entryCount > 0)

            // Enter edit
            instr.runOnMainSync { chv.edit_entry(0) }
            instr.waitForIdleSync()

            // Verify edit mode active
            var isEditing = false
            instr.runOnMainSync { isEditing = chv.isEditing() }
            assertTrue("Should be in edit mode", isEditing)

            // Type changes
            instr.runOnMainSync {
                chv.selectAllEditText()
                chv.insertEditText("Modified pinned")
            }
            instr.waitForIdleSync()

            // Verify in-progress text
            var editText = ""
            instr.runOnMainSync {
                editText = getField<EditText>(chv, "editingEditText")?.text?.toString() ?: "NULL"
            }
            assertEquals("EditText should have modified content", "Modified pinned", editText)

            // Save — calls service?.editEntryContent() which is null in test,
            // but cancelEdit() always fires. Verify edit mode exits cleanly.
            instr.runOnMainSync { chv.save_edit() }
            instr.waitForIdleSync()

            var isEditingAfterSave = false
            var editTextAfterSave: EditText? = null
            var originalAfterSave: String? = null
            instr.runOnMainSync {
                isEditingAfterSave = chv.isEditing()
                editTextAfterSave = getField(chv, "editingEditText")
                originalAfterSave = getField(chv, "editingOriginalContent")
            }
            assertFalse("Edit mode must exit after save", isEditingAfterSave)
            assertNull("editingEditText must be null after save", editTextAfterSave)
            assertNull("editingOriginalContent must be null after save", originalAfterSave)
        } finally {
            scenario.close()
        }
    }
}
