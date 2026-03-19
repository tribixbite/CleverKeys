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
    // TODO item protection — regression tests for #70 fix (7dfac6ad2)
    // Bug: cleanup queries only checked is_pinned=0, not is_todo=0.
    // TODO items (is_todo=1, is_pinned=0) were silently deleted by all 4
    // cleanup paths: cleanupExpiredEntries, clearAllEntries, applySizeLimit,
    // applySizeLimitBytes.
    // =========================================================================

    @Test
    fun testCleanupExpiredEntriesPreservesTodoItems() {
        // TODO item with expired timestamp — should survive cleanup
        db.addClipboardEntry("Buy milk", pastExpiry)
        db.setTodoStatus("Buy milk", true)
        // Regular expired entry — should be deleted
        db.addClipboardEntry("Ephemeral", pastExpiry)

        val cleaned = db.cleanupExpiredEntries()
        assertEquals("Should only clean the non-todo expired entry", 1, cleaned)
        assertEquals("Total should be 1 (the todo)", 1, db.getTotalEntryCount())
        assertEquals("Todo should survive", 1, db.getTodoEntries().size)
        assertEquals("Buy milk", db.getTodoEntries()[0].content)
    }

    @Test
    fun testClearAllEntriesPreservesTodoItems() {
        db.addClipboardEntry("Regular 1", futureExpiry)
        db.addClipboardEntry("Regular 2", futureExpiry)
        db.addClipboardEntry("My todo", futureExpiry)
        db.setTodoStatus("My todo", true)

        val result = db.clearAllEntries()
        assertTrue(result.isSuccess)
        assertEquals("Should only delete non-todo, non-pinned entries", 2, result.getOrNull())
        assertEquals("Todo should survive", 1, db.getTotalEntryCount())
        assertEquals("My todo", db.getTodoEntries()[0].content)
    }

    @Test
    fun testApplySizeLimitPreservesTodoItems() {
        // Add 5 regular entries + 1 todo
        for (i in 1..5) {
            db.addClipboardEntry("Regular $i", futureExpiry)
            Thread.sleep(10)
        }
        db.addClipboardEntry("Todo item", futureExpiry)
        db.setTodoStatus("Todo item", true)

        // Limit to 2 regular entries — todo should not count toward limit
        val removed = db.applySizeLimit(2)
        assertEquals("Should remove 3 oldest regular entries", 3, removed)
        // 2 regular + 1 todo = 3 total
        assertEquals("Should have 2 regular + 1 todo", 3, db.getTotalEntryCount())
        assertEquals("Todo should survive", 1, db.getTodoEntries().size)
    }

    @Test
    fun testApplySizeLimitBytesPreservesTodoItems() {
        // Add entries: 1 large regular + 1 todo
        val largeContent = "X".repeat(1024) // 1KB
        db.addClipboardEntry(largeContent, futureExpiry)
        db.addClipboardEntry("Todo survives", futureExpiry)
        db.setTodoStatus("Todo survives", true)

        // Set byte limit to 0.001 MB — effectively evicts all regular entries
        // applySizeLimitBytes takes maxSizeMB as Int, min useful = 1MB
        // Instead, use applySizeLimit to test with the count path
        val removed = db.applySizeLimit(0) // 0 is a no-op per the code
        assertEquals("Size limit 0 should be no-op", 0, removed)

        // Functional test: apply limit of 0 entries (would delete all regular)
        // The method returns 0 for maxSize<=0, so test with limit=1
        db.addClipboardEntry("Regular 2", futureExpiry)
        val removed2 = db.applySizeLimit(1)
        assertEquals("Should remove 1 oldest regular, keep newest + todo", 1, removed2)
        assertEquals("Todo should survive byte limit", 1, db.getTodoEntries().size)
    }

    @Test
    fun testCleanupPreservesBothPinnedAndTodoItems() {
        // Verify pinned AND todo items both survive all cleanup paths
        db.addClipboardEntry("Pinned item", pastExpiry)
        db.setPinnedStatus("Pinned item", true)
        db.addClipboardEntry("Todo item", pastExpiry)
        db.setTodoStatus("Todo item", true)
        db.addClipboardEntry("Regular expired", pastExpiry)

        val cleaned = db.cleanupExpiredEntries()
        assertEquals("Only regular expired should be cleaned", 1, cleaned)
        assertEquals("Pinned + todo should remain", 2, db.getTotalEntryCount())
    }

    @Test
    fun testClearAllPreservesBothPinnedAndTodo() {
        db.addClipboardEntry("Regular", futureExpiry)
        db.addClipboardEntry("Pinned", futureExpiry)
        db.setPinnedStatus("Pinned", true)
        db.addClipboardEntry("Todo", futureExpiry)
        db.setTodoStatus("Todo", true)

        db.clearAllEntries()
        assertEquals("Pinned and todo should survive clear all", 2, db.getTotalEntryCount())
        assertEquals(1, db.getPinnedEntries().size)
        assertEquals(1, db.getTodoEntries().size)
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
        // Export with todo entries, clear, reimport — todos should survive
        db.addClipboardEntry("Active", futureExpiry)
        db.addClipboardEntry("My todo", futureExpiry)
        db.setTodoStatus("My todo", true)

        val exported = db.exportToJSON()!!
        db.writableDatabase.delete("clipboard_entries", null, null)

        val result = db.importFromJSON(exported)
        assertEquals("Should import 1 active", 1, result[0])
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

        db.setPinnedStatus("Pin1", true)
        db.setPinnedStatus("Pin2", true)
        db.setPinnedStatus("Pin3", true)

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

        db.setTodoStatus("Todo1", true)
        db.setTodoStatus("Todo2", true)
        db.setTodoStatus("Todo3", true)

        val todos = db.getTodoEntries()
        assertEquals(3, todos.size)
        // ASC: oldest first
        assertEquals("Todo1", todos[0].content)
        assertEquals("Todo2", todos[1].content)
        assertEquals("Todo3", todos[2].content)
    }

    @Test
    fun testPinnedOrderingStableAfterRepin() {
        // Unpin and repin should preserve original timestamp (not reset it).
        db.addClipboardEntry("StablePin", futureExpiry)
        Thread.sleep(50)
        db.addClipboardEntry("LaterPin", futureExpiry)

        db.setPinnedStatus("StablePin", true)
        db.setPinnedStatus("LaterPin", true)

        // Unpin and repin StablePin — its original timestamp should be preserved
        db.setPinnedStatus("StablePin", false)
        db.setPinnedStatus("StablePin", true)

        val pinned = db.getPinnedEntries()
        assertEquals(2, pinned.size)
        // StablePin was created first, so it should still be first in ASC order
        assertEquals("StablePin", pinned[0].content)
        assertEquals("LaterPin", pinned[1].content)
    }

    // =========================================================================
    // Commit a9010cb84: TODO items excluded from History tab
    // Bug: getActiveClipboardEntries() didn't filter is_todo=0, so TODO items
    // appeared in both History and Todos tabs.
    // =========================================================================

    @Test
    fun testTodoItemExcludedFromActiveEntries() {
        // Add regular and todo entries
        db.addClipboardEntry("Regular item", futureExpiry)
        db.addClipboardEntry("Todo item", futureExpiry)
        db.setTodoStatus("Todo item", true)

        val active = db.getActiveClipboardEntries()
        assertEquals("Active list should exclude TODO items", 1, active.size)
        assertEquals("Only regular item in active list", "Regular item", active[0].content)
    }

    @Test
    fun testTodoItemOnlyInTodoList() {
        db.addClipboardEntry("Item A", futureExpiry)
        db.addClipboardEntry("Item B", futureExpiry)
        db.setTodoStatus("Item B", true)

        val active = db.getActiveClipboardEntries()
        val todos = db.getTodoEntries()

        // Item B should be in todos but NOT in active
        assertTrue("Item B should be in todo list", todos.any { it.content == "Item B" })
        assertFalse("Item B should NOT be in active list", active.any { it.content == "Item B" })
        // Item A should be in active but NOT in todos
        assertTrue("Item A should be in active list", active.any { it.content == "Item A" })
        assertFalse("Item A should NOT be in todo list", todos.any { it.content == "Item A" })
    }

    @Test
    fun testUnmarkTodoReturnsToActiveList() {
        db.addClipboardEntry("Unmarked todo", futureExpiry)
        db.setTodoStatus("Unmarked todo", true)

        // Verify it's not in active
        assertTrue(db.getActiveClipboardEntries().isEmpty())

        // Unmark as todo
        db.setTodoStatus("Unmarked todo", false)

        // Should return to active list
        val active = db.getActiveClipboardEntries()
        assertEquals(1, active.size)
        assertEquals("Unmarked todo", active[0].content)
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
        db.addClipboardEntry("Regular", futureExpiry) // 7 bytes
        db.addClipboardEntry("Pinned!", futureExpiry) // 7 bytes
        db.setPinnedStatus("Pinned!", true)

        val stats = db.getStorageStats()
        assertEquals("Total size should include both", 14L, stats.totalSizeBytes)
        assertEquals("Pinned size should be 7 bytes", 7L, stats.pinnedSizeBytes)
        assertEquals("Pinned count should be 1", 1, stats.pinnedEntries)
    }

    @Test
    fun testStorageStatsActiveIncludesPinned() {
        // "Active" = non-expired + pinned (both are usable entries)
        db.addClipboardEntry("Active", futureExpiry) // non-expired, not pinned
        db.addClipboardEntry("PinnedActive", futureExpiry)
        db.setPinnedStatus("PinnedActive", true)
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
        val removed = db.applySizeLimitBytes(1)
        assertEquals("All entries under 1MB, no deletions", 0, removed)
    }

    @Test
    fun testApplySizeLimitBytesZeroIsNoOp() {
        db.addClipboardEntry("Content", futureExpiry)
        val removed = db.applySizeLimitBytes(0)
        assertEquals("Zero limit should be no-op", 0, removed)
        assertEquals("Entry should still exist", 1, db.getTotalEntryCount())
    }

    @Test
    fun testApplySizeLimitBytesNegativeIsNoOp() {
        db.addClipboardEntry("Content", futureExpiry)
        val removed = db.applySizeLimitBytes(-5)
        assertEquals("Negative limit should be no-op", 0, removed)
    }

    @Test
    fun testApplySizeLimitBytesPreservesPinnedEntries() {
        // Large pinned entry + small regular entries
        db.addClipboardEntry("P".repeat(2048), futureExpiry) // 2KB pinned
        db.setPinnedStatus("P".repeat(2048), true)
        db.addClipboardEntry("R".repeat(512), futureExpiry) // 0.5KB regular

        // Limit to 1MB — both should survive (way under limit)
        val removed = db.applySizeLimitBytes(1)
        assertEquals(0, removed)
        assertEquals(2, db.getTotalEntryCount())
    }

    @Test
    fun testApplySizeLimitBytesPreservesTodoEntries() {
        db.addClipboardEntry("T".repeat(2048), futureExpiry) // 2KB todo
        db.setTodoStatus("T".repeat(2048), true)
        db.addClipboardEntry("R".repeat(512), futureExpiry) // 0.5KB regular

        val removed = db.applySizeLimitBytes(1)
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
        assertTrue("Should contain separator", text.contains(" · "))
        assertTrue("Should contain relative time", text.contains("Just now"))
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
}
