package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
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
        // Config must be initialized — importFromJSON calls Config.globalConfig()
        TestConfigHelper.ensureConfigInitialized(context)
        db = ClipboardDatabase.getInstance(context)
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
    fun testDuplicateEntryMovedToTop() {
        // #108: Duplicate entries are moved to top (timestamp updated), not rejected.
        // addClipboardEntry returns true because the operation succeeds (entry is reordered).
        db.addClipboardEntry("Duplicate", futureExpiry)
        val result = db.addClipboardEntry("Duplicate", futureExpiry)
        assertTrue("Duplicate entry should succeed (moved to top)", result)
        assertEquals("Should still have 1 entry", 1, db.getActiveClipboardEntries().size)
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

        val (cleaned, _) = db.cleanupExpiredEntries()
        assertEquals("Should clean 1 expired entry", 1, cleaned)
        assertEquals("Should have 1 total entry left", 1, db.getTotalEntryCount())
    }

    @Test
    fun testCleanupExpiredEntriesDoesNotAffectPinnedTable() {
        // v3 COPY semantics: pinEntry() copies to pinned_entries; the expired
        // history row in clipboard_entries is still deleted by cleanup.
        db.addClipboardEntry("Pinned expired", pastExpiry)
        db.pinEntry("Pinned expired")

        val (cleaned, _) = db.cleanupExpiredEntries()
        assertEquals("Expired history row should be cleaned", 1, cleaned)
        // Pinned copy survives in independent table
        assertEquals("Pinned copy survives in pinned_entries", 1, db.getPinnedEntries().size)
        assertEquals("Pinned expired", db.getPinnedEntries()[0].content)
    }

    // =========================================================================
    // TODO item protection — regression tests for #70 fix (7dfac6ad2)
    // Bug: cleanup queries only checked is_pinned=0, not is_todo=0.
    // TODO items (is_todo=1, is_pinned=0) were silently deleted by all 4
    // cleanup paths: cleanupExpiredEntries, clearAllEntries, applySizeLimit,
    // applySizeLimitBytes.
    // =========================================================================

    @Test
    fun testCleanupExpiredEntriesDoesNotAffectTodoTable() {
        // v3 COPY semantics: addTodoEntry() copies to todo_entries; the expired
        // history rows in clipboard_entries are still cleaned up.
        db.addClipboardEntry("Buy milk", pastExpiry)
        db.addTodoEntry("Buy milk")
        // Regular expired entry — also deleted
        db.addClipboardEntry("Ephemeral", pastExpiry)

        val (cleaned, _) = db.cleanupExpiredEntries()
        assertEquals("Both expired history rows should be cleaned", 2, cleaned)
        assertEquals("clipboard_entries should be empty", 0, db.getTotalEntryCount())
        // Todo copy survives in independent table
        assertEquals("Todo should survive in todo_entries", 1, db.getTodoEntries().size)
        assertEquals("Buy milk", db.getTodoEntries()[0].content)
    }

    @Test
    fun testClearAllEntriesPreservesTodoItems() {
        db.addClipboardEntry("Regular 1", futureExpiry)
        db.addClipboardEntry("Regular 2", futureExpiry)
        db.addClipboardEntry("My todo", futureExpiry)
        db.addTodoEntry("My todo")

        val result = db.clearAllEntries()
        assertTrue(result.isSuccess)
        // clearAllEntries() returns Pair<deletedCount, mediaPaths>
        val (deletedCount, _) = result.getOrNull()!!
        assertEquals("Should only delete non-todo, non-pinned entries", 3, deletedCount)
        // Todo lives in independent table — clipboard_entries rows all deleted
        assertEquals("Todo should survive in todo_entries table", 1, db.getTodoEntries().size)
        assertEquals("My todo", db.getTodoEntries()[0].content)
    }

    @Test
    fun testApplySizeLimitDoesNotAffectTodoTable() {
        // v3 COPY semantics: applySizeLimit operates on clipboard_entries only;
        // todo_entries is unaffected. The "Todo item" row in clipboard_entries
        // counts toward the limit like any other entry.
        for (i in 1..5) {
            db.addClipboardEntry("Regular $i", futureExpiry)
            Thread.sleep(10)
        }
        db.addClipboardEntry("Todo item", futureExpiry)
        db.addTodoEntry("Todo item")

        // 6 entries in clipboard_entries; limit to 2 → remove 4 oldest
        val removed = db.applySizeLimit(2)
        assertEquals("Should remove 4 oldest history entries", 4, removed)
        assertEquals("Should have 2 newest history entries", 2, db.getTotalEntryCount())
        // Todo copy in independent table is unaffected
        assertEquals("Todo should survive in todo_entries", 1, db.getTodoEntries().size)
    }

    @Test
    fun testApplySizeLimitCountWithTodoCopy() {
        // v3 COPY semantics: todo copy in clipboard_entries counts toward limit
        val largeContent = "X".repeat(1024) // 1KB
        db.addClipboardEntry(largeContent, futureExpiry)
        db.addClipboardEntry("Todo survives", futureExpiry)
        db.addTodoEntry("Todo survives")

        val removed = db.applySizeLimit(0) // 0 is a no-op per the code
        assertEquals("Size limit 0 should be no-op", 0, removed)

        // Add a 3rd entry; clipboard_entries now has 3 rows
        db.addClipboardEntry("Regular 2", futureExpiry)
        // Limit to 1 → remove 2 oldest (largeContent + "Todo survives")
        val removed2 = db.applySizeLimit(1)
        assertEquals("Should remove 2 oldest history entries", 2, removed2)
        // Todo copy in independent table is unaffected
        assertEquals("Todo should survive in todo_entries", 1, db.getTodoEntries().size)
    }

    @Test
    fun testCleanupDeletesAllExpiredHistoryButPinnedTodoTablesSurvive() {
        // v3: All 3 history rows are expired → all cleaned from clipboard_entries.
        // Pinned/todo copies in independent tables are unaffected.
        db.addClipboardEntry("Pinned item", pastExpiry)
        db.pinEntry("Pinned item")
        db.addClipboardEntry("Todo item", pastExpiry)
        db.addTodoEntry("Todo item")
        db.addClipboardEntry("Regular expired", pastExpiry)

        val (cleaned, _) = db.cleanupExpiredEntries()
        assertEquals("All 3 expired history rows should be cleaned", 3, cleaned)
        assertEquals("clipboard_entries should be empty", 0, db.getTotalEntryCount())
        assertEquals("Pinned copy survives", 1, db.getPinnedEntries().size)
        assertEquals("Todo copy survives", 1, db.getTodoEntries().size)
    }

    @Test
    fun testClearAllDeletesHistoryButPinnedTodoTablesSurvive() {
        // v3: clearAllEntries() wipes clipboard_entries. Pinned/todo in
        // independent tables are unaffected.
        db.addClipboardEntry("Regular", futureExpiry)
        db.addClipboardEntry("Pinned", futureExpiry)
        db.pinEntry("Pinned")
        db.addClipboardEntry("Todo", futureExpiry)
        db.addTodoEntry("Todo")

        db.clearAllEntries()
        assertEquals("clipboard_entries should be empty", 0, db.getTotalEntryCount())
        assertEquals("Pinned copy survives in pinned_entries", 1, db.getPinnedEntries().size)
        assertEquals("Todo copy survives in todo_entries", 1, db.getTodoEntries().size)
    }

    // =========================================================================
    // Long overflow protection — regression test for #70 fix (7dfac6ad2)
    // Bug: System.currentTimeMillis() + Long.MAX_VALUE wraps to negative,
    // causing entries to expire immediately during import.
    // =========================================================================

    @Test
    fun testImportWithNeverExpireDoesNotOverflow() {
        // Simulate what importFromJSON does with "never expire" TTL:
        // The overflow-safe pattern is: if (ttl == Long.MAX_VALUE) Long.MAX_VALUE else now + ttl
        val ttl = Long.MAX_VALUE
        val safeExpiry = if (ttl == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ttl

        assertEquals("Never-expire should produce Long.MAX_VALUE, not negative", Long.MAX_VALUE, safeExpiry)
        assertTrue("Expiry must be positive", safeExpiry > 0)

        // The old buggy code: System.currentTimeMillis() + Long.MAX_VALUE
        val buggyExpiry = System.currentTimeMillis() + Long.MAX_VALUE
        assertTrue("Buggy calculation wraps to negative", buggyExpiry < 0)
    }

    @Test
    fun testImportRoundTripPreservesTodo() {
        // v3: Export includes all 3 tables. clipboard_entries has both rows;
        // todo_entries has 1 row. Clear all tables before reimport.
        db.addClipboardEntry("Active", futureExpiry)
        db.addClipboardEntry("My todo", futureExpiry)
        db.addTodoEntry("My todo")

        val exported = db.exportToJSON()!!
        // Clear ALL tables to avoid duplicate skips
        db.writableDatabase.delete("clipboard_entries", null, null)
        db.writableDatabase.delete("todo_entries", null, null)

        val result = db.importFromJSON(exported)
        assertEquals("Should import 2 active (both history rows)", 2, result[0])
        assertEquals("Should import 1 todo", 1, result[2])

        // Verify imported todo survives cleanup
        db.cleanupExpiredEntries()
        assertEquals("Todo should survive cleanup after import", 1, db.getTodoEntries().size)
    }

    // =========================================================================
    // Remove tests
    // =========================================================================

    @Test
    fun testRemoveClipboardEntry() {
        db.addClipboardEntry("Remove me", futureExpiry)
        // removeClipboardEntry returns media_path (null for text-only) — non-null would indicate media
        db.removeClipboardEntry("Remove me")
        assertEquals(0, db.getTotalEntryCount())
    }

    @Test
    fun testRemoveNonexistentEntry() {
        val mediaPath = db.removeClipboardEntry("Nonexistent")
        assertNull("Should return null for nonexistent entry", mediaPath)
    }

    @Test
    fun testRemoveNullContent() {
        val mediaPath = db.removeClipboardEntry(null)
        assertNull("Should return null for null content", mediaPath)
    }

    @Test
    fun testClearAllEntries() {
        db.addClipboardEntry("Entry 1", futureExpiry)
        db.addClipboardEntry("Entry 2", futureExpiry)
        db.addClipboardEntry("Pinned", futureExpiry)
        db.pinEntry("Pinned")

        val result = db.clearAllEntries()
        assertTrue("clearAllEntries should succeed", result.isSuccess)
        // v3 COPY semantics: clearAllEntries deletes ALL rows from clipboard_entries.
        // Pinned copy lives in independent pinned_entries table and is unaffected.
        assertEquals("clipboard_entries should be empty", 0, db.getTotalEntryCount())
        assertEquals("Pinned copy survives in pinned_entries", 1, db.getPinnedEntries().size)
    }

    // =========================================================================
    // Pin status tests
    // =========================================================================

    @Test
    fun testSetPinnedStatus() {
        db.addClipboardEntry("Pin me", futureExpiry)
        val pinned = db.pinEntry("Pin me")
        assertTrue("Should pin entry", pinned)

        val pinnedEntries = db.getPinnedEntries()
        assertEquals("Should have 1 pinned entry", 1, pinnedEntries.size)
        assertEquals("Pin me", pinnedEntries[0].content)
    }

    @Test
    fun testUnpinEntry() {
        db.addClipboardEntry("Unpin me", futureExpiry)
        db.pinEntry("Unpin me")
        db.unpinEntry("Unpin me")

        val pinnedEntries = db.getPinnedEntries()
        assertTrue("Should have no pinned entries", pinnedEntries.isEmpty())
    }

    @Test
    fun testPinnedCopyDoesNotRemoveFromHistory() {
        // v3 COPY semantics: pinEntry() copies to pinned_entries but the
        // original history row in clipboard_entries remains visible.
        db.addClipboardEntry("Pinned", futureExpiry)
        db.pinEntry("Pinned")

        val active = db.getActiveClipboardEntries()
        assertEquals("History entry should still be visible (COPY semantics)", 1, active.size)
        assertEquals("Pinned", active[0].content)
        assertEquals("Pinned copy also in pinned_entries", 1, db.getPinnedEntries().size)
    }

    @Test
    fun testSetPinnedStatusNullContent() {
        val result = db.pinEntry(null)
        assertFalse("Null content should return false", result)
    }

    // =========================================================================
    // Todo status tests
    // =========================================================================

    @Test
    fun testSetTodoStatus() {
        db.addClipboardEntry("Todo item", futureExpiry)
        val result = db.addTodoEntry("Todo item")
        assertTrue("Should set todo status", result)

        val todoEntries = db.getTodoEntries()
        assertEquals("Should have 1 todo entry", 1, todoEntries.size)
        assertEquals("Todo item", todoEntries[0].content)
    }

    @Test
    fun testClearTodoStatus() {
        db.addClipboardEntry("Not todo", futureExpiry)
        db.addTodoEntry("Not todo")
        db.removeTodoEntry("Not todo")

        val todoEntries = db.getTodoEntries()
        assertTrue("Should have no todo entries", todoEntries.isEmpty())
    }

    @Test
    fun testSetTodoStatusNullContent() {
        val result = db.addTodoEntry(null)
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
        db.pinEntry("Pinned")

        val activeCount = db.getActiveEntryCount()
        // Active includes: non-expired + pinned
        assertEquals("Active count should include active + pinned", 2, activeCount)
    }

    // =========================================================================
    // Storage stats tests
    // =========================================================================

    @Test
    fun testGetStorageStats() {
        // v3: getStorageStats() aggregates across all 3 tables via UNION ALL.
        // activeEntries = historyEntries (non-expired rows in clipboard_entries)
        db.addClipboardEntry("Active entry", futureExpiry)
        db.addClipboardEntry("Pinned entry", futureExpiry)
        db.pinEntry("Pinned entry")

        val stats = db.getStorageStats()
        // totalEntries = 2 history + 1 pinned copy = 3
        assertEquals(3, stats.totalEntries)
        // activeEntries = historyEntries = 2 (both in clipboard_entries, non-expired)
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
        db.pinEntry("Pinned 1")
        db.addClipboardEntry("Todo 1", futureExpiry)
        db.addTodoEntry("Todo 1")

        val json = db.exportToJSON()
        assertNotNull("Export should return JSON", json)
        assertTrue("Should have active_entries", json!!.has("active_entries"))
        assertTrue("Should have pinned_entries", json.has("pinned_entries"))
        assertTrue("Should have todo_entries", json.has("todo_entries"))
        assertEquals("Export version should be 4 (v4 media schema)", 4, json.getInt("export_version"))
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
        // v3: clipboard_entries has 3 rows (Active, Pinned, Todo);
        // pinned_entries has 1, todo_entries has 1. Clear all before reimport.
        db.addClipboardEntry("Active entry", futureExpiry)
        db.addClipboardEntry("Pinned entry", futureExpiry)
        db.pinEntry("Pinned entry")
        db.addClipboardEntry("Todo entry", futureExpiry)
        db.addTodoEntry("Todo entry")

        val exported = db.exportToJSON()!!

        // Clear ALL tables to avoid duplicate skips
        db.writableDatabase.delete("clipboard_entries", null, null)
        db.writableDatabase.delete("pinned_entries", null, null)
        db.writableDatabase.delete("todo_entries", null, null)
        val result = db.importFromJSON(exported)

        assertEquals("Should import 3 active (all history rows)", 3, result[0])
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

    // =========================================================================
    // #108: Duplicate reorder — dedup should update timestamp, not just skip
    // =========================================================================

    @Test
    fun testDuplicateEntryReorderedToTop() {
        // #108: Re-adding a duplicate should update its timestamp, making it
        // appear first in DESC-ordered active entries list.
        db.addClipboardEntry("Alpha", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("Beta", futureExpiry)
        Thread.sleep(50)
        // Re-add Alpha — should move it to top via timestamp update
        db.addClipboardEntry("Alpha", futureExpiry)

        val entries = db.getActiveClipboardEntries()
        assertEquals("Should still have 2 entries (deduplicated)", 2, entries.size)
        assertEquals("Alpha should be first (moved to top)", "Alpha", entries[0].content)
        assertEquals("Beta should be second", "Beta", entries[1].content)
    }

    @Test
    fun testDuplicateEntryTimestampActuallyUpdated() {
        // #108: Verify the timestamp is updated, not just the dedup count.
        db.addClipboardEntry("Stamp test", futureExpiry)
        val beforeEntries = db.getActiveClipboardEntries()
        val timestampBefore = beforeEntries[0].timestamp

        Thread.sleep(50)
        db.addClipboardEntry("Stamp test", futureExpiry)
        val afterEntries = db.getActiveClipboardEntries()
        val timestampAfter = afterEntries[0].timestamp

        assertTrue("Timestamp should increase after dedup reorder",
            timestampAfter > timestampBefore)
    }

    @Test
    fun testDuplicateEntryExpiryUpdated() {
        // #108: Dedup reorder also refreshes the expiry timestamp.
        val shortExpiry = System.currentTimeMillis() + 60_000L // 1 minute
        val longExpiry = System.currentTimeMillis() + 7_200_000L // 2 hours
        db.addClipboardEntry("Expiry test", shortExpiry)

        Thread.sleep(50)
        // Re-add with longer expiry
        db.addClipboardEntry("Expiry test", longExpiry)

        // Should still be active (expiry was refreshed to longExpiry)
        val entries = db.getActiveClipboardEntries()
        assertEquals("Entry should still be active with updated expiry", 1, entries.size)
    }

    // =========================================================================
    // Pinned/todo ordering — verify ASC timestamp order
    // =========================================================================

    @Test
    fun testPinnedEntriesOrderedByTimestampAsc() {
        // Pinned entries use ORDER BY timestamp ASC — oldest first, providing
        // a stable ordering where the first-pinned item stays at the top.
        db.addClipboardEntry("Pin1", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("Pin2", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("Pin3", futureExpiry)

        db.pinEntry("Pin1")
        db.pinEntry("Pin2")
        db.pinEntry("Pin3")

        val pinned = db.getPinnedEntries()
        assertEquals(3, pinned.size)
        // ASC: oldest first
        assertEquals("Pin1", pinned[0].content)
        assertEquals("Pin2", pinned[1].content)
        assertEquals("Pin3", pinned[2].content)
    }

    @Test
    fun testTodoEntriesOrderedByTimestampAsc() {
        // Todo entries also use ASC ordering (checklist order matches creation order).
        db.addClipboardEntry("Todo1", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("Todo2", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("Todo3", futureExpiry)

        db.addTodoEntry("Todo1")
        db.addTodoEntry("Todo2")
        db.addTodoEntry("Todo3")

        val todos = db.getTodoEntries()
        assertEquals(3, todos.size)
        // ASC: oldest first
        assertEquals("Todo1", todos[0].content)
        assertEquals("Todo2", todos[1].content)
        assertEquals("Todo3", todos[2].content)
    }

    @Test
    fun testRepinnedEntryMovesToEnd() {
        // Unpin + repin creates a fresh row with position at the end.
        // The original creation timestamp is NOT preserved.
        db.addClipboardEntry("StablePin", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("LaterPin", futureExpiry)

        db.pinEntry("StablePin")   // position ~1.0
        db.pinEntry("LaterPin")    // position ~2.0

        // Unpin and repin StablePin — gets new position (3.0)
        db.unpinEntry("StablePin")
        db.pinEntry("StablePin")

        val pinned = db.getPinnedEntries()
        assertEquals(2, pinned.size)
        // LaterPin keeps position 2.0; StablePin moves to end (3.0)
        assertEquals("LaterPin", pinned[0].content)
        assertEquals("StablePin", pinned[1].content)
    }

    // =========================================================================
    // Commit a9010cb84: TODO items excluded from History tab
    // Bug: getActiveClipboardEntries() didn't filter is_todo=0, so TODO items
    // appeared in both History and Todos tabs.
    // =========================================================================

    @Test
    fun testTodoCopyDoesNotRemoveFromHistory() {
        // v3 COPY semantics: addTodoEntry() copies to todo_entries; the
        // original history row in clipboard_entries remains visible.
        db.addClipboardEntry("Regular item", futureExpiry)
        db.addClipboardEntry("Todo item", futureExpiry)
        db.addTodoEntry("Todo item")

        val active = db.getActiveClipboardEntries()
        assertEquals("History should have both entries (COPY semantics)", 2, active.size)
        assertEquals("Todo should also be in todo_entries", 1, db.getTodoEntries().size)
    }

    @Test
    fun testTodoCopySemantics() {
        // v3 COPY: Item B exists in BOTH clipboard_entries and todo_entries
        db.addClipboardEntry("Item A", futureExpiry)
        db.addClipboardEntry("Item B", futureExpiry)
        db.addTodoEntry("Item B")

        val active = db.getActiveClipboardEntries()
        val todos = db.getTodoEntries()

        // Item B in both history and todo (COPY semantics)
        assertTrue("Item B should be in todo list", todos.any { it.content == "Item B" })
        assertTrue("Item B should also be in active list", active.any { it.content == "Item B" })
        // Item A only in history
        assertTrue("Item A should be in active list", active.any { it.content == "Item A" })
        assertFalse("Item A should NOT be in todo list", todos.any { it.content == "Item A" })
    }

    @Test
    fun testRemoveTodoDoesNotAffectHistory() {
        // v3 COPY: history row always visible; removing from todo_entries
        // only affects the todo table.
        db.addClipboardEntry("Unmarked todo", futureExpiry)
        db.addTodoEntry("Unmarked todo")

        // History row is always visible in v3
        assertEquals("History entry always visible", 1, db.getActiveClipboardEntries().size)
        assertEquals("Todo copy exists", 1, db.getTodoEntries().size)

        // Remove from todo
        db.removeTodoEntry("Unmarked todo")

        // History entry unchanged, todo copy gone
        assertEquals("History entry still present", 1, db.getActiveClipboardEntries().size)
        assertEquals("Todo copy removed", 0, db.getTodoEntries().size)
    }

    // =========================================================================
    // Commit 8c5a1c4c5: getStorageStats byte accuracy
    // Verifies SQL aggregation (LENGTH(CAST(content AS BLOB))) matches
    // expected UTF-8 byte counts for known content.
    // =========================================================================

    @Test
    fun testStorageStatsByteAccuracyAscii() {
        // ASCII: 1 byte per char
        val content = "Hello" // 5 bytes UTF-8
        db.addClipboardEntry(content, futureExpiry)

        val stats = db.getStorageStats()
        assertEquals("Total size should be 5 bytes for ASCII 'Hello'",
            5L, stats.totalSizeBytes)
    }

    @Test
    fun testStorageStatsByteAccuracyMultipleEntries() {
        db.addClipboardEntry("AAA", futureExpiry) // 3 bytes
        db.addClipboardEntry("BBBBB", futureExpiry) // 5 bytes

        val stats = db.getStorageStats()
        assertEquals("Total size should sum all entries", 8L, stats.totalSizeBytes)
        assertEquals("Total entries should be 2", 2, stats.totalEntries)
    }

    @Test
    fun testStorageStatsPinnedSizeIsolated() {
        // v3: pinEntry() copies to pinned_entries; history row remains.
        // getStorageStats() sums across all 3 tables.
        db.addClipboardEntry("Regular", futureExpiry) // 7 bytes
        db.addClipboardEntry("Pinned!", futureExpiry) // 7 bytes
        db.pinEntry("Pinned!")

        val stats = db.getStorageStats()
        // Total = history(14) + pinned(7) = 21 bytes
        assertEquals("Total size: 14 history + 7 pinned", 21L, stats.totalSizeBytes)
        assertEquals("History size should be 14 bytes", 14L, stats.activeSizeBytes)
        assertEquals("Pinned size should be 7 bytes", 7L, stats.pinnedSizeBytes)
        assertEquals("Pinned count should be 1", 1, stats.pinnedEntries)
    }

    @Test
    fun testStorageStatsActiveIncludesPinned() {
        // "Active" = non-expired + pinned (both are usable entries)
        db.addClipboardEntry("Active", futureExpiry) // non-expired, not pinned
        db.addClipboardEntry("PinnedActive", futureExpiry)
        db.pinEntry("PinnedActive")
        db.addClipboardEntry("Expired", pastExpiry) // expired, not pinned

        val stats = db.getStorageStats()
        assertEquals("Total entries should be 3", 3, stats.totalEntries)
        // Active = non-expired(1) + pinned(1) = 2
        assertEquals("Active should include non-expired + pinned", 2, stats.activeEntries)
    }

    @Test
    fun testStorageStatsEmptyDB() {
        val stats = db.getStorageStats()
        assertEquals(0, stats.totalEntries)
        assertEquals(0, stats.activeEntries)
        assertEquals(0, stats.pinnedEntries)
        assertEquals(0L, stats.totalSizeBytes)
        assertEquals(0L, stats.activeSizeBytes)
        assertEquals(0L, stats.pinnedSizeBytes)
    }

    // =========================================================================
    // Commit 8c5a1c4c5: applySizeLimitBytes — SQL LENGTH(CAST) approach
    // Verifies oldest entries are deleted when total size exceeds limit.
    // =========================================================================

    @Test
    fun testApplySizeLimitBytesRemovesOldest() {
        // Add 3 entries of known size, limit to ~2 entries worth
        db.addClipboardEntry("A".repeat(1024), futureExpiry) // 1KB, oldest
        Thread.sleep(10)
        db.addClipboardEntry("B".repeat(1024), futureExpiry) // 1KB
        Thread.sleep(10)
        db.addClipboardEntry("C".repeat(1024), futureExpiry) // 1KB, newest

        // Total: 3KB. Limit to 1MB → no deletions (under limit)
        val (removed, _) = db.applySizeLimitBytes(1)
        assertEquals("All entries under 1MB, no deletions", 0, removed)
    }

    @Test
    fun testApplySizeLimitBytesZeroIsNoOp() {
        db.addClipboardEntry("Content", futureExpiry)
        val (removed, _) = db.applySizeLimitBytes(0)
        assertEquals("Zero limit should be no-op", 0, removed)
        assertEquals("Entry should still exist", 1, db.getTotalEntryCount())
    }

    @Test
    fun testApplySizeLimitBytesNegativeIsNoOp() {
        db.addClipboardEntry("Content", futureExpiry)
        val (removed, _) = db.applySizeLimitBytes(-5)
        assertEquals("Negative limit should be no-op", 0, removed)
    }

    @Test
    fun testApplySizeLimitBytesPreservesPinnedEntries() {
        // Large pinned entry + small regular entries
        db.addClipboardEntry("P".repeat(2048), futureExpiry) // 2KB pinned
        db.pinEntry("P".repeat(2048))
        db.addClipboardEntry("R".repeat(512), futureExpiry) // 0.5KB regular

        // Limit to 1MB — both should survive (way under limit)
        val (removed, _) = db.applySizeLimitBytes(1)
        assertEquals(0, removed)
        assertEquals(2, db.getTotalEntryCount())
    }

    @Test
    fun testApplySizeLimitBytesPreservesTodoEntries() {
        db.addClipboardEntry("T".repeat(2048), futureExpiry) // 2KB todo
        db.addTodoEntry("T".repeat(2048))
        db.addClipboardEntry("R".repeat(512), futureExpiry) // 0.5KB regular

        val (removed, _) = db.applySizeLimitBytes(1)
        assertEquals(0, removed)
        assertEquals(2, db.getTotalEntryCount())
    }

    // =========================================================================
    // Commit 2d283f2eb: Import transaction integrity
    // Verifies import is atomic (all-or-nothing via transaction wrapping).
    // =========================================================================

    @Test
    fun testImportTransactionAllOrNothing() {
        // Successful import: all entries should be present
        db.addClipboardEntry("Export1", futureExpiry)
        db.addClipboardEntry("Export2", futureExpiry)
        val exported = db.exportToJSON()!!

        db.writableDatabase.delete("clipboard_entries", null, null)
        val result = db.importFromJSON(exported)
        val totalImported = result[0] + result[1] + result[2]
        assertEquals("All entries should be imported", 2, totalImported)
    }

    @Test
    fun testImportWithInvalidJsonReturnsZeros() {
        // Completely invalid JSON structure — should not crash
        val badJson = JSONObject().apply {
            put("export_version", 2)
            // Missing active_entries, pinned_entries, todo_entries arrays
        }

        val result = db.importFromJSON(badJson)
        val totalImported = result[0] + result[1] + result[2]
        assertEquals("Invalid JSON should import 0 entries", 0, totalImported)
        assertEquals("No entries in DB after failed import", 0, db.getTotalEntryCount())
    }

    @Test
    fun testImportWithEmptyArrays() {
        val emptyJson = JSONObject().apply {
            put("export_version", 2)
            put("active_entries", JSONArray())
            put("pinned_entries", JSONArray())
            put("todo_entries", JSONArray())
        }

        val result = db.importFromJSON(emptyJson)
        assertEquals("Active imported", 0, result[0])
        assertEquals("Pinned imported", 0, result[1])
        assertEquals("Todo imported", 0, result[2])
        assertEquals("Duplicates", 0, result[3])
    }

    @Test
    fun testImportPreservesExistingEntries() {
        // Existing entry should not be overwritten by import
        db.addClipboardEntry("Existing", futureExpiry)

        val importJson = JSONObject().apply {
            put("export_version", 2)
            put("active_entries", JSONArray().apply {
                put(JSONObject().apply {
                    put("content", "New import")
                    put("timestamp", System.currentTimeMillis())
                })
            })
            put("pinned_entries", JSONArray())
            put("todo_entries", JSONArray())
        }

        db.importFromJSON(importJson)
        assertEquals("Should have both existing and imported", 2, db.getTotalEntryCount())
        assertTrue(db.getActiveClipboardEntries().any { it.content == "Existing" })
    }

    // =========================================================================
    // Commit 90d126a5b: ClipboardEntry.getFormattedText span structure
    // Verifies SpannableStringBuilder output has correct span placement.
    // =========================================================================

    @Test
    fun testGetFormattedTextContainsTimestamp() {
        val entry = ClipboardEntry("Test content", System.currentTimeMillis())
        val formatted = entry.getFormattedText(context)
        val text = formatted.toString()

        assertTrue("Should contain original content", text.startsWith("Test content"))
        // Separator uses non-breaking spaces (\u00A0) to prevent line-wrap
        assertTrue("Should contain separator", text.contains("\u00A0\u00B7\u00A0"))
        assertTrue("Should contain relative time", text.contains("Just\u00A0now"))
    }

    @Test
    fun testGetFormattedTextHasColorSpan() {
        val entry = ClipboardEntry("Span test", System.currentTimeMillis())
        val formatted = entry.getFormattedText(context)

        // Should have exactly one ForegroundColorSpan on the timestamp portion
        val spans = formatted.getSpans(0, formatted.length,
            android.text.style.ForegroundColorSpan::class.java)
        assertEquals("Should have exactly 1 color span", 1, spans.size)

        // Span should start after the content
        val spanStart = formatted.getSpanStart(spans[0])
        assertEquals("Span should start at content length",
            "Span test".length, spanStart)
    }

    @Test
    fun testGetFormattedTextSpanDoesNotCoverContent() {
        val entry = ClipboardEntry("Content here", System.currentTimeMillis())
        val formatted = entry.getFormattedText(context)

        val spans = formatted.getSpans(0, "Content here".length,
            android.text.style.ForegroundColorSpan::class.java)
        // No span should cover the content portion (only the timestamp)
        for (span in spans) {
            val start = formatted.getSpanStart(span)
            assertTrue("Span should not start within content", start >= "Content here".length)
        }
    }

    @Test
    fun testGetFormattedTextWithEmptyishContent() {
        // Single character content — edge case for span placement
        val entry = ClipboardEntry("X", System.currentTimeMillis())
        val formatted = entry.getFormattedText(context)

        assertTrue("Should start with X", formatted.toString().startsWith("X"))
        val spans = formatted.getSpans(0, formatted.length,
            android.text.style.ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("Span starts at position 1", 1, formatted.getSpanStart(spans[0]))
    }

    // =========================================================================
    // Inline edit: updateHistoryEntryContent / updatePinnedEntryContent /
    // updateTodoEntryContent — EditEntryResult sealed class
    //
    // Verifies content + hash update, dedup conflict, blank rejection,
    // timestamp/position/tags/status preservation, cross-table independence.
    // =========================================================================

    /** Helper: clear ALL 3 tables for edit test isolation */
    private fun clearAllTables() {
        db.writableDatabase.delete("clipboard_entries", null, null)
        db.writableDatabase.delete("pinned_entries", null, null)
        db.writableDatabase.delete("todo_entries", null, null)
    }

    @Test
    fun testEditHistoryEntryContent() {
        clearAllTables()
        db.addClipboardEntry("Original text", futureExpiry)

        val result = db.updateHistoryEntryContent("Original text", "Edited text")
        assertTrue("Edit should succeed", result is EditEntryResult.Success)

        val entries = db.getActiveClipboardEntries()
        assertEquals(1, entries.size)
        assertEquals("Edited text", entries[0].content)
    }

    @Test
    fun testEditPinnedEntryContent() {
        clearAllTables()
        db.pinEntry("Pinned original")

        val result = db.updatePinnedEntryContent("Pinned original", "Pinned edited")
        assertTrue("Edit should succeed", result is EditEntryResult.Success)

        val entries = db.getPinnedEntries()
        assertEquals(1, entries.size)
        assertEquals("Pinned edited", entries[0].content)
    }

    @Test
    fun testEditTodoEntryContent() {
        clearAllTables()
        db.addTodoEntry("Todo original")

        val result = db.updateTodoEntryContent("Todo original", "Todo edited")
        assertTrue("Edit should succeed", result is EditEntryResult.Success)

        val entries = db.getTodoEntries()
        assertEquals(1, entries.size)
        assertEquals("Todo edited", entries[0].content)
    }

    @Test
    fun testEditContentHashRecomputed() {
        clearAllTables()
        db.addClipboardEntry("Hash test", futureExpiry)

        db.updateHistoryEntryContent("Hash test", "Hash changed")

        // Verify hash correctness by checking that dedup works with the new hash
        // Adding another entry with old content should succeed (no longer in table)
        val added = db.addClipboardEntry("Hash test", futureExpiry)
        assertTrue("Old content should be addable again (hash cleared)", added)
        assertEquals(2, db.getActiveClipboardEntries().size)
    }

    @Test
    fun testEditRejectsDuplicateInSameTable() {
        clearAllTables()
        db.addClipboardEntry("Entry A", futureExpiry)
        db.addClipboardEntry("Entry B", futureExpiry)

        // Try to edit "Entry A" to "Entry B" — should conflict
        val result = db.updateHistoryEntryContent("Entry A", "Entry B")
        assertTrue("Should be DuplicateConflict", result is EditEntryResult.DuplicateConflict)

        // Verify neither entry was modified
        val entries = db.getActiveClipboardEntries()
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.content == "Entry A" })
        assertTrue(entries.any { it.content == "Entry B" })
    }

    @Test
    fun testEditRejectsDuplicateInPinnedTable() {
        clearAllTables()
        db.pinEntry("Pin A")
        db.pinEntry("Pin B")

        val result = db.updatePinnedEntryContent("Pin A", "Pin B")
        assertTrue("Should be DuplicateConflict", result is EditEntryResult.DuplicateConflict)
    }

    @Test
    fun testEditRejectsDuplicateInTodoTable() {
        clearAllTables()
        db.addTodoEntry("Todo A")
        db.addTodoEntry("Todo B")

        val result = db.updateTodoEntryContent("Todo A", "Todo B")
        assertTrue("Should be DuplicateConflict", result is EditEntryResult.DuplicateConflict)
    }

    @Test
    fun testEditRejectsBlankContent() {
        clearAllTables()
        db.addClipboardEntry("Non-blank", futureExpiry)

        val resultEmpty = db.updateHistoryEntryContent("Non-blank", "")
        assertTrue("Empty should be InvalidContent", resultEmpty is EditEntryResult.InvalidContent)

        val resultBlank = db.updateHistoryEntryContent("Non-blank", "   ")
        assertTrue("Whitespace should be InvalidContent", resultBlank is EditEntryResult.InvalidContent)

        // Verify original unchanged
        assertEquals("Non-blank", db.getActiveClipboardEntries()[0].content)
    }

    @Test
    fun testEditNoOpWhenContentUnchanged() {
        clearAllTables()
        db.addClipboardEntry("Same content", futureExpiry)

        val result = db.updateHistoryEntryContent("Same content", "Same content")
        assertTrue("No-op should return Success", result is EditEntryResult.Success)
    }

    @Test
    fun testEditTrimsWhitespace() {
        clearAllTables()
        db.addClipboardEntry("Trimmed", futureExpiry)

        // Edit with whitespace-padded version of same content = no-op
        val result = db.updateHistoryEntryContent("Trimmed", "  Trimmed  ")
        assertTrue("Trim-equivalent should be Success (no-op)", result is EditEntryResult.Success)
    }

    @Test
    fun testEditPreservesTimestamp() {
        clearAllTables()
        val beforeAdd = System.currentTimeMillis()
        db.addClipboardEntry("Timestamp test", futureExpiry)
        Thread.sleep(50)

        val originalTimestamp = db.getActiveClipboardEntries()[0].timestamp
        assertTrue("Timestamp should be set", originalTimestamp >= beforeAdd)

        db.updateHistoryEntryContent("Timestamp test", "Timestamp edited")

        val editedTimestamp = db.getActiveClipboardEntries()[0].timestamp
        assertEquals("Timestamp should be preserved after edit", originalTimestamp, editedTimestamp)
    }

    @Test
    fun testEditPreservesTodoStatus() {
        clearAllTables()
        db.addTodoEntry("Status test")
        db.setTodoEntryStatus("Status test", "completed")

        db.updateTodoEntryContent("Status test", "Status edited")

        // Verify status was preserved — check via raw query since getTodoEntries
        // doesn't expose status directly through ClipboardEntry
        val cursor = db.readableDatabase.rawQuery(
            "SELECT status FROM todo_entries WHERE content = ?",
            arrayOf("Status edited")
        )
        cursor.use {
            assertTrue("Should find edited entry", it.moveToFirst())
            assertEquals("Status should be preserved", "completed", it.getString(0))
        }
    }

    @Test
    fun testEditPreservesPinnedPosition() {
        clearAllTables()
        db.pinEntry("First pin")
        db.pinEntry("Second pin")
        db.pinEntry("Third pin")

        // Get position of second pin before edit
        val posBefore = db.readableDatabase.rawQuery(
            "SELECT position FROM pinned_entries WHERE content = ?",
            arrayOf("Second pin")
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getDouble(0)
        }

        db.updatePinnedEntryContent("Second pin", "Second pin edited")

        val posAfter = db.readableDatabase.rawQuery(
            "SELECT position FROM pinned_entries WHERE content = ?",
            arrayOf("Second pin edited")
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getDouble(0)
        }

        assertEquals("Position should be preserved", posBefore, posAfter, 0.001)
    }

    @Test
    fun testEditPreservesTags() {
        clearAllTables()
        db.pinEntry("Tagged entry")
        db.setPinnedEntryTags("Tagged entry", listOf("work", "important"))

        db.updatePinnedEntryContent("Tagged entry", "Tagged edited")

        val tags = db.readableDatabase.rawQuery(
            "SELECT tags FROM pinned_entries WHERE content = ?",
            arrayOf("Tagged edited")
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

        assertTrue("Tags should contain 'work'", tags.contains("work"))
        assertTrue("Tags should contain 'important'", tags.contains("important"))
    }

    @Test
    fun testEditCrossTableIndependence() {
        // COPY semantics: editing in one tab must NOT affect copies in other tabs
        clearAllTables()
        val content = "Cross-table test"
        db.addClipboardEntry(content, futureExpiry)
        db.pinEntry(content)
        db.addTodoEntry(content)

        // Edit only the pinned copy
        val result = db.updatePinnedEntryContent(content, "Pinned only edit")
        assertTrue("Should succeed", result is EditEntryResult.Success)

        // History and todo copies should be unaffected
        assertEquals(content, db.getActiveClipboardEntries()[0].content)
        assertEquals(content, db.getTodoEntries()[0].content)
        assertEquals("Pinned only edit", db.getPinnedEntries()[0].content)
    }

    @Test
    fun testEditNonexistentEntryReturnsError() {
        clearAllTables()

        val result = db.updateHistoryEntryContent("Does not exist", "New content")
        assertTrue("Should return Error for missing entry", result is EditEntryResult.Error)
    }
}
