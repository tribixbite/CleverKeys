package tribixbite.cleverkeys

import android.content.Context
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.customization.CustomShortSwipeExecutor
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * Instrumented tests for the paste_pinned_N clipboard commands and the
 * MAX_ACTION_LENGTH limit increase (100 -> 4096).
 *
 * Verifies:
 * - CommandRegistry contains paste_pinned_1..5 in CLIPBOARD category
 * - paste_pinned commands are searchable by keyword "pin"
 * - ShortSwipeMapping.MAX_ACTION_LENGTH == 4096
 * - Text actions longer than 100 chars (but under 4096) are accepted
 * - executePastePinned returns false (no crash) when pinned entries are missing
 * - executePastePinned inserts the correct pinned entry text
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class PastePinnedCommandTest {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase
    private lateinit var executor: CustomShortSwipeExecutor

    private val futureExpiry = System.currentTimeMillis() + 3600_000L

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = ClipboardDatabase.getInstance(context)
        executor = CustomShortSwipeExecutor(context)
        // Clear ALL tables for test isolation (v3 uses independent pinned/todo tables)
        db.writableDatabase.delete("clipboard_entries", null, null)
        db.writableDatabase.delete("pinned_entries", null, null)
        db.writableDatabase.delete("todo_entries", null, null)
    }

    @After
    fun cleanup() {
        db.writableDatabase.delete("clipboard_entries", null, null)
        db.writableDatabase.delete("pinned_entries", null, null)
        db.writableDatabase.delete("todo_entries", null, null)
    }

    // =========================================================================
    // CommandRegistry: paste_pinned commands exist and are categorized
    // =========================================================================

    @Test
    fun registryContainsPastePinned1Through5() {
        for (i in 1..5) {
            val cmd = CommandRegistry.getByName("paste_pinned_$i")
            assertNotNull("paste_pinned_$i should exist in CommandRegistry", cmd)
            assertEquals(
                "paste_pinned_$i should be in CLIPBOARD category",
                CommandRegistry.Category.CLIPBOARD,
                cmd!!.category
            )
        }
    }

    @Test
    fun pastePinnedCommandsHaveCorrectDisplayNames() {
        val expected = mapOf(
            "paste_pinned_1" to "Paste Pin #1",
            "paste_pinned_2" to "Paste Pin #2",
            "paste_pinned_3" to "Paste Pin #3",
            "paste_pinned_4" to "Paste Pin #4",
            "paste_pinned_5" to "Paste Pin #5"
        )
        expected.forEach { (name, displayName) ->
            val cmd = CommandRegistry.getByName(name)
            assertNotNull("$name should exist", cmd)
            assertEquals("$name display name", displayName, cmd!!.displayName)
        }
    }

    @Test
    fun searchByPinReturnsAllPinnedCommands() {
        val results = CommandRegistry.search("pin")
        val pinnedNames = results.map { it.name }.filter { it.startsWith("paste_pinned_") }
        assertEquals(
            "Searching 'pin' should return all 5 paste_pinned commands",
            5,
            pinnedNames.size
        )
        for (i in 1..5) {
            assertTrue(
                "paste_pinned_$i should appear in search results",
                pinnedNames.contains("paste_pinned_$i")
            )
        }
    }

    @Test
    fun searchByPinnedKeywordAlsoWorks() {
        val results = CommandRegistry.search("pinned")
        val pinnedNames = results.map { it.name }.filter { it.startsWith("paste_pinned_") }
        assertEquals(
            "Searching 'pinned' should return all 5 paste_pinned commands",
            5,
            pinnedNames.size
        )
    }

    // =========================================================================
    // ShortSwipeMapping: MAX_ACTION_LENGTH raised to 4096
    // =========================================================================

    @Test
    fun maxActionLengthIs4096() {
        assertEquals(
            "MAX_ACTION_LENGTH should be 4096",
            4096,
            ShortSwipeMapping.MAX_ACTION_LENGTH
        )
    }

    @Test
    fun textActionOver100CharsIsAccepted() {
        // Previously limited to 100 chars; now 4096 is the cap
        val longText = "a".repeat(200)
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.NE,
            displayText = "long",
            text = longText
        )
        assertEquals(
            "Action value should contain full 200-char text",
            200,
            mapping.actionValue.length
        )
    }

    @Test
    fun textActionAt4096CharsIsAccepted() {
        val maxText = "x".repeat(4096)
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "b",
            direction = SwipeDirection.NW,
            displayText = "max",
            text = maxText
        )
        assertEquals(4096, mapping.actionValue.length)
    }

    @Test
    fun textActionOver4096IsTruncated() {
        // The textInput factory method calls text.take(MAX_ACTION_LENGTH)
        val overText = "z".repeat(5000)
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "c",
            direction = SwipeDirection.SE,
            displayText = "over",
            text = overText
        )
        assertEquals(
            "Text beyond 4096 should be truncated by factory method",
            4096,
            mapping.actionValue.length
        )
    }

    // =========================================================================
    // executePastePinned: graceful failure with no pinned entries
    // =========================================================================

    @Test
    fun pastePinnedReturnsFlaseWhenNoPinnedEntries() {
        // DB is empty — no pinned entries
        val pinnedEntries = db.getPinnedEntries()
        assertTrue("Precondition: no pinned entries", pinnedEntries.isEmpty())

        // Build a mapping for paste_pinned_1
        val mapping = ShortSwipeMapping(
            keyCode = "a",
            direction = SwipeDirection.NE,
            displayText = "P1",
            actionType = ActionType.COMMAND,
            actionValue = "paste_pinned_1"
        )

        // Create a real InputConnection via EditText on the main thread
        var result = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editText = EditText(context)
            val ic = BaseInputConnection(editText, true)
            result = executor.execute(mapping, ic, null)
        }
        assertFalse("Should return false when no pinned entries exist", result)
    }

    @Test
    fun pastePinnedReturnsFlaseWhenIndexExceedsPinCount() {
        // Pin 2 entries but request #3
        db.addClipboardEntry("Pin A", futureExpiry)
        db.pinEntry("Pin A")
        db.addClipboardEntry("Pin B", futureExpiry)
        db.pinEntry("Pin B")
        assertEquals("Precondition: 2 pinned entries", 2, db.getPinnedEntries().size)

        val mapping = ShortSwipeMapping(
            keyCode = "a",
            direction = SwipeDirection.NE,
            displayText = "P3",
            actionType = ActionType.COMMAND,
            actionValue = "paste_pinned_3"
        )

        var result = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editText = EditText(context)
            val ic = BaseInputConnection(editText, true)
            result = executor.execute(mapping, ic, null)
        }
        assertFalse("Should return false when index exceeds pinned count", result)
    }

    @Test
    fun pastePinnedDoesNotCrashForAllIndices() {
        // No pinned entries — confirm none of paste_pinned_1..5 throws
        for (i in 1..5) {
            val mapping = ShortSwipeMapping(
                keyCode = "a",
                direction = SwipeDirection.NE,
                displayText = "P$i",
                actionType = ActionType.COMMAND,
                actionValue = "paste_pinned_$i"
            )

            var threw = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try {
                    val editText = EditText(context)
                    val ic = BaseInputConnection(editText, true)
                    executor.execute(mapping, ic, null)
                } catch (e: Exception) {
                    threw = true
                }
            }
            assertFalse("paste_pinned_$i should not crash when no pins exist", threw)
        }
    }

    // =========================================================================
    // executePastePinned: successful insertion when entries exist
    // =========================================================================

    @Test
    fun pastePinnedInsertFirstPinnedEntry() {
        // Pin an entry and verify paste_pinned_1 inserts its content
        db.addClipboardEntry("Hello from pin", futureExpiry)
        db.pinEntry("Hello from pin")

        val mapping = ShortSwipeMapping(
            keyCode = "a",
            direction = SwipeDirection.NE,
            displayText = "P1",
            actionType = ActionType.COMMAND,
            actionValue = "paste_pinned_1"
        )

        var result = false
        var insertedText = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editText = EditText(context)
            val editorInfo = EditorInfo()
            // Use EditText's own InputConnection so commitText writes to its buffer
            val ic = editText.onCreateInputConnection(editorInfo)!!
            result = executor.execute(mapping, ic, editorInfo)
            insertedText = editText.text.toString()
        }
        assertTrue("Should return true on successful paste", result)
        assertEquals("Inserted text should match pinned content", "Hello from pin", insertedText)
    }

    @Test
    fun pastePinnedInsertCorrectEntryByIndex() {
        // Pin 3 entries. getPinnedEntries returns oldest first (timestamp ASC).
        db.addClipboardEntry("First pin", futureExpiry)
        db.pinEntry("First pin")
        Thread.sleep(50) // Ensure distinct timestamps
        db.addClipboardEntry("Second pin", futureExpiry)
        db.pinEntry("Second pin")
        Thread.sleep(50)
        db.addClipboardEntry("Third pin", futureExpiry)
        db.pinEntry("Third pin")

        // Oldest first ordering: First, Second, Third
        val pinned = db.getPinnedEntries()
        assertEquals("First pin", pinned[0].content)
        assertEquals("Second pin", pinned[1].content)
        assertEquals("Third pin", pinned[2].content)

        // paste_pinned_2 should insert the 2nd entry ("Second pin")
        val mapping = ShortSwipeMapping(
            keyCode = "a",
            direction = SwipeDirection.NE,
            displayText = "P2",
            actionType = ActionType.COMMAND,
            actionValue = "paste_pinned_2"
        )

        var insertedText = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editText = EditText(context)
            val editorInfo = EditorInfo()
            val ic = editText.onCreateInputConnection(editorInfo)!!
            executor.execute(mapping, ic, editorInfo)
            insertedText = editText.text.toString()
        }
        assertEquals("paste_pinned_2 should insert the 2nd pinned entry", "Second pin", insertedText)
    }

    @Test
    fun pastePinnedNullInputConnectionReturnsFalse() {
        db.addClipboardEntry("Has pin", futureExpiry)
        db.pinEntry("Has pin")

        val mapping = ShortSwipeMapping(
            keyCode = "a",
            direction = SwipeDirection.NE,
            displayText = "P1",
            actionType = ActionType.COMMAND,
            actionValue = "paste_pinned_1"
        )

        // null InputConnection should be handled gracefully
        val result = executor.execute(mapping, null, null)
        assertFalse("Should return false with null InputConnection", result)
    }
}
