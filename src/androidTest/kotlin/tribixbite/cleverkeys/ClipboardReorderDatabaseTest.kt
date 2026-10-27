package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.clipboard.ClipboardReorderPolicy

/**
 * Reordering Pinned and To-dos rows against real SQLite (2026-10-27): one midpoint UPDATE,
 * reload order, status and tags untouched, the renumbering once the gap is too small, stale
 * requests refused, and the order surviving a backup export → import round trip.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardReorderDatabaseTest {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        db = ClipboardDatabase.getInstance(context)
        clearTables()
    }

    @After
    fun cleanup() = clearTables()

    private fun clearTables() {
        db.writableDatabase.delete("clipboard_entries", null, null)
        db.writableDatabase.delete("pinned_entries", null, null)
        db.writableDatabase.delete("todo_entries", null, null)
    }

    private fun todos(): List<ClipboardEntry> = db.getTodoEntries()
    private fun todo(content: String) = todos().first { it.content == content }
    private fun positions(table: String): Map<String, Double> =
        db.readableDatabase.rawQuery("SELECT content, position FROM $table", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getDouble(1)) }
        }

    @Test
    fun aMoveWritesTheMidpointAndTheReloadShowsTheNewOrder() {
        for (s in listOf("a", "b", "c", "d")) assertTrue(db.addTodoEntry(s))
        val before = positions("todo_entries")

        assertTrue(db.moveEntry(ClipboardTab.TODOS, todo("d").rowId, todo("a").rowId, todo("b").rowId))

        assertEquals(listOf("a", "d", "b", "c"), todos().map { it.content })
        val after = positions("todo_entries")
        assertEquals((before.getValue("a") + before.getValue("b")) / 2, after.getValue("d"), 0.0)
        for (s in listOf("a", "b", "c")) assertEquals("only the moved row is written", before.getValue(s), after.getValue(s), 0.0)
    }

    @Test
    fun aMoveKeepsStatusAndTags() {
        assertTrue(db.addTodoEntry("a"))
        assertTrue(db.addTodoEntry("b"))
        assertTrue(db.setTodoEntryStatus("b", TodoEntry.STATUS_PLANNED))
        assertTrue(db.setTodoEntryTags("b", listOf("work")))

        assertTrue(db.moveEntry(ClipboardTab.TODOS, todo("b").rowId, null, todo("a").rowId))

        val moved = todos().first()
        assertEquals("b", moved.content)
        assertEquals(TodoEntry.STATUS_PLANNED, moved.todoStatus)
        assertEquals(listOf("work"), moved.tags)
    }

    @Test
    fun pinnedRowsMoveToo() {
        for (s in listOf("x", "y", "z")) assertTrue(db.pinEntry(s))
        val rows = db.getPinnedEntries()

        assertTrue(db.moveEntry(ClipboardTab.PINNED, rows[0].rowId, rows[2].rowId, null))

        assertEquals(listOf("y", "z", "x"), db.getPinnedEntries().map { it.content })
    }

    @Test
    fun aTooSmallGapRenumbersTheTableInItsNewOrder() {
        for (s in listOf("a", "b", "c")) assertTrue(db.addTodoEntry(s))
        val tiny = ClipboardReorderPolicy.MIN_GAP / 4
        db.writableDatabase.execSQL("UPDATE todo_entries SET position = ? WHERE content = 'b'", arrayOf<Any>(1.0 + tiny))
        db.writableDatabase.execSQL("UPDATE todo_entries SET position = 1.0 WHERE content = 'a'")

        assertTrue(db.moveEntry(ClipboardTab.TODOS, todo("c").rowId, todo("a").rowId, todo("b").rowId))

        assertEquals(listOf("a", "c", "b"), todos().map { it.content })
        assertEquals(mapOf("a" to 1.0, "c" to 2.0, "b" to 3.0), positions("todo_entries"))
    }

    @Test
    fun staleOrHistoryRequestsWriteNothing() {
        assertTrue(db.addTodoEntry("a"))
        assertTrue(db.addTodoEntry("b"))
        val before = positions("todo_entries")

        assertFalse(db.moveEntry(ClipboardTab.TODOS, 999_999L, todo("a").rowId, null))
        assertFalse(db.moveEntry(ClipboardTab.TODOS, todo("a").rowId, 999_999L, null))
        assertFalse(db.moveEntry(ClipboardTab.TODOS, todo("a").rowId, null, todo("b").rowId))  // already first
        assertFalse(db.moveEntry(ClipboardTab.HISTORY, todo("a").rowId, null, todo("b").rowId))

        assertEquals(before, positions("todo_entries"))
    }

    @Test
    fun theOrderSurvivesABackupRoundTrip() {
        for (s in listOf("t1", "t2", "t3")) assertTrue(db.addTodoEntry(s))
        for (s in listOf("p1", "p2", "p3")) assertTrue(db.pinEntry(s))
        assertTrue(db.moveEntry(ClipboardTab.TODOS, todo("t3").rowId, null, todo("t1").rowId))
        val pins = db.getPinnedEntries()
        assertTrue(db.moveEntry(ClipboardTab.PINNED, pins[0].rowId, pins[1].rowId, pins[2].rowId))
        val todoOrder = todos().map { it.content }
        val pinOrder = db.getPinnedEntries().map { it.content }
        assertEquals(listOf("t3", "t1", "t2"), todoOrder)
        assertEquals(listOf("p2", "p1", "p3"), pinOrder)

        val backup = db.exportToJSON()!!
        clearTables()
        db.importFromJSON(backup)

        assertEquals(todoOrder, todos().map { it.content })
        assertEquals(pinOrder, db.getPinnedEntries().map { it.content })
    }
}
