package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for ClipboardDatabase.
 * Tests SQLite CRUD operations for clipboard entries, pin/todo management,
 * expiry cleanup, size limits, and export/import.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardDatabaseTest {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase

    // Expiry 1 hour from now (well within active range)
    private val futureExpiry = System.currentTimeMillis() + 3600_000L
    // Expiry in the past (already expired)
    private val pastExpiry = System.currentTimeMillis() - 3600_000L

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = ClipboardDatabase.getInstance(context)
        // Clear all entries (including pinned) for test isolation
        db.writableDatabase.delete("clipboard_entries", null, null)
    }

    @After
    fun cleanup() {
        db.writableDatabase.delete("clipboard_entries", null, null)
    }

    // =========================================================================
    // Basic add/retrieve tests
    // =========================================================================

    @Test
    fun testAddClipboardEntry() {
        val added = db.addClipboardEntry("Hello world", futureExpiry)
        assertTrue("Should add entry successfully", added)
    }

    @Test
    fun testAddAndRetrieveEntry() {
        db.addClipboardEntry("Test content", futureExpiry)
        val entries = db.getActiveClipboardEntries()
        assertEquals("Should have 1 active entry", 1, entries.size)
        assertEquals("Content should match", "Test content", entries[0].content)
    }

    @Test
    fun testAddMultipleEntries() {
        db.addClipboardEntry("First", futureExpiry)
        db.addClipboardEntry("Second", futureExpiry)
        db.addClipboardEntry("Third", futureExpiry)
        val entries = db.getActiveClipboardEntries()
        assertEquals("Should have 3 entries", 3, entries.size)
    }

    @Test
    fun testAddNullContent() {
        val result = db.addClipboardEntry(null, futureExpiry)
        assertFalse("Null content should return false", result)
    }

    @Test
    fun testAddBlankContent() {
        val result = db.addClipboardEntry("   ", futureExpiry)
        assertFalse("Blank content should return false", result)
    }

    @Test
    fun testAddEmptyContent() {
        val result = db.addClipboardEntry("", futureExpiry)
        assertFalse("Empty content should return false", result)
    }

    @Test
    fun testDuplicateEntryIgnored() {
        db.addClipboardEntry("Duplicate", futureExpiry)
        val result = db.addClipboardEntry("Duplicate", futureExpiry)
        assertFalse("Duplicate entry should be rejected", result)
        assertEquals("Should only have 1 entry", 1, db.getActiveClipboardEntries().size)
    }

    @Test
    fun testTrimmedContentStored() {
        db.addClipboardEntry("  trimmed  ", futureExpiry)
        val entries = db.getActiveClipboardEntries()
        assertEquals("Content should be trimmed", "trimmed", entries[0].content)
    }

    // =========================================================================
    // Expiry tests
    // =========================================================================

    @Test
    fun testExpiredEntriesNotActive() {
        db.addClipboardEntry("Expired", pastExpiry)
        val active = db.getActiveClipboardEntries()
        assertTrue("Expired entries should not appear in active list", active.isEmpty())
    }

    @Test
    fun testCleanupExpiredEntries() {
        db.addClipboardEntry("Expired", pastExpiry)
        db.addClipboardEntry("Active", futureExpiry)

        val cleaned = db.cleanupExpiredEntries()
        assertEquals("Should clean 1 expired entry", 1, cleaned)
        assertEquals("Should have 1 total entry left", 1, db.getTotalEntryCount())
    }

    @Test
    fun testCleanupExpiredEntriesPreservesPinned() {
        // Add a non-active entry with past expiry and pin it
        db.addClipboardEntry("Pinned expired", pastExpiry)
        db.setPinnedStatus("Pinned expired", true)

        val cleaned = db.cleanupExpiredEntries()
        assertEquals("Pinned entries should not be cleaned", 0, cleaned)
    }

    // =========================================================================
    // Remove tests
    // =========================================================================

    @Test
    fun testRemoveClipboardEntry() {
        db.addClipboardEntry("Remove me", futureExpiry)
        val removed = db.removeClipboardEntry("Remove me")
        assertTrue("Should remove entry", removed)
        assertEquals(0, db.getTotalEntryCount())
    }

    @Test
    fun testRemoveNonexistentEntry() {
        val removed = db.removeClipboardEntry("Nonexistent")
        assertFalse("Should not remove nonexistent entry", removed)
    }

    @Test
    fun testRemoveNullContent() {
        val removed = db.removeClipboardEntry(null)
        assertFalse("Should not remove null content", removed)
    }

    @Test
    fun testClearAllEntries() {
        db.addClipboardEntry("Entry 1", futureExpiry)
        db.addClipboardEntry("Entry 2", futureExpiry)
        db.addClipboardEntry("Pinned", futureExpiry)
        db.setPinnedStatus("Pinned", true)

        val result = db.clearAllEntries()
        assertTrue("clearAllEntries should succeed", result.isSuccess)
        // clearAllEntries keeps pinned entries
        assertEquals("Should keep pinned entries", 1, db.getTotalEntryCount())
    }

    // =========================================================================
    // Pin status tests
    // =========================================================================

    @Test
    fun testSetPinnedStatus() {
        db.addClipboardEntry("Pin me", futureExpiry)
        val pinned = db.setPinnedStatus("Pin me", true)
        assertTrue("Should pin entry", pinned)

        val pinnedEntries = db.getPinnedEntries()
        assertEquals("Should have 1 pinned entry", 1, pinnedEntries.size)
        assertEquals("Pin me", pinnedEntries[0].content)
    }

    @Test
    fun testUnpinEntry() {
        db.addClipboardEntry("Unpin me", futureExpiry)
        db.setPinnedStatus("Unpin me", true)
        db.setPinnedStatus("Unpin me", false)

        val pinnedEntries = db.getPinnedEntries()
        assertTrue("Should have no pinned entries", pinnedEntries.isEmpty())
    }

    @Test
    fun testPinnedEntryNotInActiveList() {
        db.addClipboardEntry("Pinned", futureExpiry)
        db.setPinnedStatus("Pinned", true)

        // getActiveClipboardEntries filters for is_pinned=0
        val active = db.getActiveClipboardEntries()
        assertTrue("Pinned entries should not appear in active list", active.isEmpty())
    }

    @Test
    fun testSetPinnedStatusNullContent() {
        val result = db.setPinnedStatus(null, true)
        assertFalse("Null content should return false", result)
    }

    // =========================================================================
    // Todo status tests
    // =========================================================================

    @Test
    fun testSetTodoStatus() {
        db.addClipboardEntry("Todo item", futureExpiry)
        val result = db.setTodoStatus("Todo item", true)
        assertTrue("Should set todo status", result)

        val todoEntries = db.getTodoEntries()
        assertEquals("Should have 1 todo entry", 1, todoEntries.size)
        assertEquals("Todo item", todoEntries[0].content)
    }

    @Test
    fun testClearTodoStatus() {
        db.addClipboardEntry("Not todo", futureExpiry)
        db.setTodoStatus("Not todo", true)
        db.setTodoStatus("Not todo", false)

        val todoEntries = db.getTodoEntries()
        assertTrue("Should have no todo entries", todoEntries.isEmpty())
    }

    @Test
    fun testSetTodoStatusNullContent() {
        val result = db.setTodoStatus(null, true)
        assertFalse("Null content should return false", result)
    }

    // =========================================================================
    // Count tests
    // =========================================================================

    @Test
    fun testGetTotalEntryCount() {
        db.addClipboardEntry("Entry 1", futureExpiry)
        db.addClipboardEntry("Entry 2", futureExpiry)
        assertEquals(2, db.getTotalEntryCount())
    }

    @Test
    fun testGetTotalEntryCountEmpty() {
        assertEquals(0, db.getTotalEntryCount())
    }

    @Test
    fun testGetActiveEntryCount() {
        db.addClipboardEntry("Active", futureExpiry)
        db.addClipboardEntry("Expired", pastExpiry)
        db.addClipboardEntry("Pinned", futureExpiry)
        db.setPinnedStatus("Pinned", true)

        val activeCount = db.getActiveEntryCount()
        // Active includes: non-expired + pinned
        assertEquals("Active count should include active + pinned", 2, activeCount)
    }

    // =========================================================================
    // Storage stats tests
    // =========================================================================

    @Test
    fun testGetStorageStats() {
        db.addClipboardEntry("Active entry", futureExpiry)
        db.addClipboardEntry("Pinned entry", futureExpiry)
        db.setPinnedStatus("Pinned entry", true)

        val stats = db.getStorageStats()
        assertEquals(2, stats.totalEntries)
        assertEquals(2, stats.activeEntries)
        assertEquals(1, stats.pinnedEntries)
        assertTrue("Total size should be > 0", stats.totalSizeBytes > 0)
    }

    @Test
    fun testGetDatabaseStats() {
        db.addClipboardEntry("Entry", futureExpiry)
        val result = db.getDatabaseStats()
        assertTrue("getDatabaseStats should succeed", result.isSuccess)
        val stats = result.getOrNull()!!
        assertEquals(1, stats["total_entries"])
    }

    // =========================================================================
    // Size limit tests
    // =========================================================================

    @Test
    fun testApplySizeLimitRemovesOldest() {
        for (i in 1..5) {
            db.addClipboardEntry("Entry $i", futureExpiry)
            Thread.sleep(10) // Ensure different timestamps
        }
        val removed = db.applySizeLimit(3)
        assertEquals("Should remove 2 oldest entries", 2, removed)
    }

    @Test
    fun testApplySizeLimitNoOpWhenUnderLimit() {
        db.addClipboardEntry("Entry 1", futureExpiry)
        db.addClipboardEntry("Entry 2", futureExpiry)
        val removed = db.applySizeLimit(10)
        assertEquals("Should remove nothing when under limit", 0, removed)
    }

    @Test
    fun testApplySizeLimitZero() {
        val removed = db.applySizeLimit(0)
        assertEquals(0, removed)
    }

    // =========================================================================
    // Export/Import tests
    // =========================================================================

    @Test
    fun testExportToJSON() {
        db.addClipboardEntry("Active 1", futureExpiry)
        db.addClipboardEntry("Pinned 1", futureExpiry)
        db.setPinnedStatus("Pinned 1", true)
        db.addClipboardEntry("Todo 1", futureExpiry)
        db.setTodoStatus("Todo 1", true)

        val json = db.exportToJSON()
        assertNotNull("Export should return JSON", json)
        assertTrue("Should have active_entries", json!!.has("active_entries"))
        assertTrue("Should have pinned_entries", json.has("pinned_entries"))
        assertTrue("Should have todo_entries", json.has("todo_entries"))
        assertEquals(2, json.getInt("export_version"))
    }

    @Test
    fun testExportEmptyDB() {
        val json = db.exportToJSON()
        assertNotNull(json)
        assertEquals(0, json!!.getInt("total_active"))
        assertEquals(0, json.getInt("total_pinned"))
        assertEquals(0, json.getInt("total_todo"))
    }

    @Test
    fun testImportFromJSON() {
        // Create export data
        db.addClipboardEntry("Export me", futureExpiry)
        val exported = db.exportToJSON()!!

        // Clear DB
        db.writableDatabase.delete("clipboard_entries", null, null)
        assertEquals(0, db.getTotalEntryCount())

        // Import
        val result = db.importFromJSON(exported)
        // result = [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped]
        assertTrue("Should import at least 1 entry", result[0] > 0 || result[1] > 0 || result[2] > 0)
    }

    @Test
    fun testImportSkipsDuplicates() {
        db.addClipboardEntry("Already here", futureExpiry)
        val exported = db.exportToJSON()!!

        // Import same data again
        val result = db.importFromJSON(exported)
        // All entries should be skipped as duplicates
        assertEquals("Duplicates should be skipped", 1, result[3])
    }

    @Test
    fun testExportImportRoundTrip() {
        db.addClipboardEntry("Active entry", futureExpiry)
        db.addClipboardEntry("Pinned entry", futureExpiry)
        db.setPinnedStatus("Pinned entry", true)
        db.addClipboardEntry("Todo entry", futureExpiry)
        db.setTodoStatus("Todo entry", true)

        val exported = db.exportToJSON()!!

        // Clear and reimport
        db.writableDatabase.delete("clipboard_entries", null, null)
        val result = db.importFromJSON(exported)

        assertEquals("Should import 1 active", 1, result[0])
        assertEquals("Should import 1 pinned", 1, result[1])
        assertEquals("Should import 1 todo", 1, result[2])
    }

    // =========================================================================
    // ClipboardEntry model tests
    // =========================================================================

    @Test
    fun testClipboardEntryFields() {
        val entry = ClipboardEntry("Test", System.currentTimeMillis())
        assertEquals("Test", entry.content)
        assertTrue(entry.timestamp > 0)
    }

    @Test
    fun testClipboardEntryRelativeTime() {
        val recentEntry = ClipboardEntry("Recent", System.currentTimeMillis())
        val relativeTime = recentEntry.getRelativeTime()
        assertNotNull(relativeTime)
        assertEquals("Just now", relativeTime)
    }

    @Test
    fun testClipboardEntryRelativeTimeMinutes() {
        val fiveMinAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        val entry = ClipboardEntry("Old", fiveMinAgo)
        val relativeTime = entry.getRelativeTime()
        assertTrue("Should show minutes", relativeTime.contains("m ago"))
    }

    @Test
    fun testClipboardEntryRelativeTimeHours() {
        val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000)
        val entry = ClipboardEntry("Older", twoHoursAgo)
        val relativeTime = entry.getRelativeTime()
        assertTrue("Should show hours", relativeTime.contains("h ago"))
    }

    @Test
    fun testClipboardEntryFormatDate() {
        val entry = ClipboardEntry("Test", System.currentTimeMillis())
        val formatted = entry.formatDate()
        assertNotNull(formatted)
        assertTrue("Should have month abbreviation", formatted.length >= 3)
    }

    @Test
    fun testClipboardEntryGetFormattedText() {
        val entry = ClipboardEntry("Test content", System.currentTimeMillis())
        val formatted = entry.getFormattedText(context)
        assertNotNull(formatted)
        assertTrue("Formatted text should contain content", formatted.toString().contains("Test content"))
    }

    // =========================================================================
    // Ordering tests
    // =========================================================================

    @Test
    fun testActiveEntriesOrderedByTimestampDesc() {
        db.addClipboardEntry("First", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("Second", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("Third", futureExpiry)

        val entries = db.getActiveClipboardEntries()
        assertEquals("Third", entries[0].content) // Most recent first
        assertEquals("Second", entries[1].content)
        assertEquals("First", entries[2].content)
    }
}
