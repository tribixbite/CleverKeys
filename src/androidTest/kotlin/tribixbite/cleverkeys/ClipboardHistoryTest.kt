package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for ClipboardHistoryService.
 * Tests clipboard history functionality including the TransactionTooLargeException fix (Issue #71).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardHistoryTest {

    private lateinit var context: Context
    private var clipboardService: ClipboardHistoryService? = null

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Initialize Config for testing
        TestConfigHelper.ensureConfigInitialized(context)

        // Get clipboard service. Fail ALL tests loudly if it is unavailable —
        // every test body uses `clipboardService?.`, so a null service would
        // otherwise turn the whole class into silent no-op passes (ARC-044).
        clipboardService = ClipboardHistoryService.get_service(context)
        assertNotNull("ClipboardHistoryService must be available for this suite", clipboardService)
        // addClip() silently no-ops when history is disabled — assert the gate
        // is open so the add/remove tests below actually exercise storage.
        assertTrue(
            "clipboard_history_enabled must be on for this suite to be meaningful",
            Config.globalConfig().clipboard_history_enabled
        )
    }

    @After
    fun cleanup() {
        // Clear test data
        clipboardService?.clearHistory()
    }

    // =========================================================================
    // Basic service tests
    // =========================================================================

    @Test
    fun testServiceInitialization() {
        assertNotNull("ClipboardHistoryService should be initialized", clipboardService)
        assertSame(
            "Setup's service must be the process-wide singleton",
            clipboardService, ClipboardHistoryService.get_service(context)
        )
    }

    @Test
    fun testServiceSingleton() {
        val service1 = ClipboardHistoryService.get_service(context)
        val service2 = ClipboardHistoryService.get_service(context)
        assertSame("Service should be singleton", service1, service2)
    }

    // =========================================================================
    // Clipboard history operations tests
    // =========================================================================

    @Test
    fun testAddClipEntry() {
        val testClip = "Test clipboard entry ${System.currentTimeMillis()}"

        clipboardService?.addClip(testClip)

        val history = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        val hasTestClip = history.any { it.content == testClip }
        assertTrue("Clipboard should contain added entry", hasTestClip)
    }

    @Test
    fun testAddEmptyClipRejected() {
        val initialHistory = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        val initialSize = initialHistory.size

        clipboardService?.addClip("")
        clipboardService?.addClip("   ") // Whitespace only
        clipboardService?.addClip(null)

        val afterHistory = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        assertEquals("Empty clips should not be added", initialSize, afterHistory.size)
    }

    @Test
    fun testRemoveClipEntry() {
        val testClip = "To be removed ${System.currentTimeMillis()}"

        clipboardService?.addClip(testClip)
        clipboardService?.removeHistoryEntry(testClip)

        val history = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        val hasTestClip = history.any { it.content == testClip }
        assertFalse("Removed entry should not be in history", hasTestClip)
    }

    @Test
    fun testClearHistory() {
        clipboardService?.addClip("Test entry 1")
        clipboardService?.addClip("Test entry 2")
        clipboardService?.clearHistory()

        val history = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        val pinnedEntries = clipboardService?.getPinnedEntries() ?: emptyList()
        // History should be empty or only contain pinned entries after clear
        assertTrue("History should be empty after clear (except pinned)",
            history.isEmpty() || history.size == pinnedEntries.size)
    }

    // =========================================================================
    // Issue #71: TransactionTooLargeException fix tests
    // =========================================================================

    @Test
    fun testHistoryLimitedTo100Entries() {
        // The fix limits display to MAX_DISPLAY_ENTRIES (100)
        val history = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        assertTrue("History should be limited to prevent TransactionTooLargeException",
            history.size <= 100)
    }

    @Test
    fun testLargeClipboardDoesNotCrash() {
        // Add many entries to test the limit. Unique content per entry so the
        // consecutive-duplicate filter cannot swallow any of them.
        val marker = System.currentTimeMillis()
        repeat(50) { i ->
            clipboardService?.addClip("Test entry $i - $marker")
        }

        // Must not throw TransactionTooLargeException (Issue #71) — and other
        // exceptions are failures too, not something to swallow.
        val history = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()

        assertTrue(
            "All 50 added entries must be retrievable (got ${history.size})",
            history.count { it.content.endsWith("- $marker") } == 50
        )
        assertTrue(
            "History must stay capped at 100 entries (Issue #71), got ${history.size}",
            history.size <= 100
        )
    }

    // =========================================================================
    // Pinned entries tests
    // =========================================================================

    @Test
    fun testPinEntry() {
        val testClip = "Pinned entry ${System.currentTimeMillis()}"

        clipboardService?.addClip(testClip)
        clipboardService?.pinEntry(testClip)

        val pinnedEntries = clipboardService?.getPinnedEntries() ?: emptyList()
        val hasPinned = pinnedEntries.any { it.content == testClip }
        assertTrue("Entry should be pinned", hasPinned)
    }

    @Test
    fun testUnpinEntry() {
        val testClip = "To unpin ${System.currentTimeMillis()}"

        clipboardService?.addClip(testClip)
        clipboardService?.pinEntry(testClip)
        clipboardService?.unpinEntry(testClip)

        val pinnedEntries = clipboardService?.getPinnedEntries() ?: emptyList()
        val hasPinned = pinnedEntries.any { it.content == testClip }
        assertFalse("Entry should be unpinned", hasPinned)
    }

    @Test
    fun testPinnedEntriesSurviveClear() {
        val pinnedClip = "Survives clear ${System.currentTimeMillis()}"

        clipboardService?.addClip(pinnedClip)
        clipboardService?.pinEntry(pinnedClip)
        clipboardService?.clearHistory()

        val pinnedEntries = clipboardService?.getPinnedEntries() ?: emptyList()
        val hasPinned = pinnedEntries.any { it.content == pinnedClip }
        assertTrue("Pinned entry should survive clear", hasPinned)

        // Clean up
        clipboardService?.unpinEntry(pinnedClip)
        clipboardService?.removeHistoryEntry(pinnedClip)
    }

    // =========================================================================
    // Storage stats tests
    // =========================================================================

    @Test
    fun testGetStorageStats() {
        clipboardService?.addClip("Stats test entry")

        val stats = clipboardService?.getStorageStats()
        assertNotNull("Storage stats should not be null", stats)
        assertTrue("Stats should contain entry count", stats?.contains("entries") == true)
    }

    // =========================================================================
    // Clipboard status tests
    // =========================================================================

    @Test
    fun testGetClipboardStatus() {
        val status = clipboardService?.getClipboardStatus()
        assertNotNull("Clipboard status should not be null", status)
        assertTrue("Status should be a non-empty string", status?.isNotEmpty() == true)
    }

    // =========================================================================
    // Size limit tests
    // =========================================================================

    @Test
    fun testLargeClipSizeCheck() {
        // Test that very large clips are handled (size limit check in addClip)
        val largeClip = "x".repeat(100) // 100 bytes - should be fine

        try {
            clipboardService?.addClip(largeClip)
            // Should not throw
        } catch (e: Exception) {
            fail("Adding normal-sized clip should not throw: ${e.message}")
        }
    }

    // =========================================================================
    // Change listener tests
    // =========================================================================

    @Test
    fun testSetChangeListener() {
        var listenerCalled = false
        val listener = ClipboardHistoryService.OnClipboardHistoryChange {
            listenerCalled = true
        }

        clipboardService?.setOnClipboardHistoryChange(listener)
        // Unique content, so the consecutive-duplicate filter cannot apply —
        // a successful insert MUST notify the registered listener.
        clipboardService?.addClip("Trigger listener ${System.currentTimeMillis()}")

        assertTrue("Change listener must fire when a unique clip is added", listenerCalled)

        // Clean up listener
        clipboardService?.setOnClipboardHistoryChange(null)
    }

    @Test
    fun testRemoveChangeListener() {
        clipboardService?.setOnClipboardHistoryChange(null)
        // Should not crash when listener is null
        clipboardService?.addClip("No listener test ${System.currentTimeMillis()}")
    }

    // =========================================================================
    // Inline edit: editEntryContent() — service-layer routing + validation
    //
    // Tests the public API that routes to the correct table, validates size
    // limits, and fires the change listener on success.
    // =========================================================================

    /** Helper: clear all 3 tables for edit test isolation */
    private fun clearAllTablesForEdit() {
        val db = ClipboardDatabase.getInstance(context)
        db.writableDatabase.delete("clipboard_entries", null, null)
        db.writableDatabase.delete("pinned_entries", null, null)
        db.writableDatabase.delete("todo_entries", null, null)
    }

    @Test
    fun testEditHistoryViaService() {
        clearAllTablesForEdit()
        clipboardService?.addClip("Service edit test")
        Thread.sleep(50)

        val result = clipboardService?.editEntryContent(
            "Service edit test", "Service edited", ClipboardTab.HISTORY
        )
        assertTrue("Should succeed", result is EditEntryResult.Success)

        val entries = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        assertTrue("Should contain edited content", entries.any { it.content == "Service edited" })
        assertFalse("Should not contain original", entries.any { it.content == "Service edit test" })
    }

    @Test
    fun testEditPinnedViaService() {
        clearAllTablesForEdit()
        clipboardService?.pinEntry("Pin service test")

        val result = clipboardService?.editEntryContent(
            "Pin service test", "Pin service edited", ClipboardTab.PINNED
        )
        assertTrue("Should succeed", result is EditEntryResult.Success)

        val pinned = clipboardService?.getPinnedEntries() ?: emptyList()
        assertTrue(pinned.any { it.content == "Pin service edited" })
    }

    @Test
    fun testEditTodoViaService() {
        clearAllTablesForEdit()
        clipboardService?.addToTodo("Todo service test")

        val result = clipboardService?.editEntryContent(
            "Todo service test", "Todo service edited", ClipboardTab.TODOS
        )
        assertTrue("Should succeed", result is EditEntryResult.Success)

        // Service doesn't expose getTodoEntries() — use database directly
        val db = ClipboardDatabase.getInstance(context)
        val todos = db.getTodoEntries()
        assertTrue(todos.any { it.content == "Todo service edited" })
    }

    @Test
    fun testEditNoOpWhenUnchanged() {
        clearAllTablesForEdit()
        clipboardService?.addClip("Unchanged content")

        val result = clipboardService?.editEntryContent(
            "Unchanged content", "Unchanged content", ClipboardTab.HISTORY
        )
        assertTrue("No-op should return Success", result is EditEntryResult.Success)
    }

    @Test
    fun testEditWhitespaceOnlyChangePersists() {
        clearAllTablesForEdit()
        clipboardService?.addClip("Trim test")
        Thread.sleep(50)

        // UT-4: a whitespace-only change is a real edit — it must be written to the
        // DB verbatim, not silently swallowed by a trimmed-equality no-op guard.
        val result = clipboardService?.editEntryContent(
            "Trim test", "Trim test\n", ClipboardTab.HISTORY
        )
        assertTrue("Whitespace-only edit should succeed", result is EditEntryResult.Success)

        val entries = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        assertTrue("Edited content with trailing newline must be stored verbatim",
            entries.any { it.content == "Trim test\n" })
        assertFalse("Original un-newlined content should be gone",
            entries.any { it.content == "Trim test" })
    }

    @Test
    fun testEditFiresChangeListener() {
        clearAllTablesForEdit()
        clipboardService?.addClip("Listener test")

        var listenerFired = false
        clipboardService?.setOnClipboardHistoryChange(
            ClipboardHistoryService.OnClipboardHistoryChange { listenerFired = true }
        )

        clipboardService?.editEntryContent(
            "Listener test", "Listener edited", ClipboardTab.HISTORY
        )

        assertTrue("Change listener should fire on successful edit", listenerFired)

        // Clean up listener
        clipboardService?.setOnClipboardHistoryChange(null)
    }

    @Test
    fun testEditDoesNotFireListenerOnNoOp() {
        clearAllTablesForEdit()
        clipboardService?.addClip("No-op listener")

        var listenerFired = false
        clipboardService?.setOnClipboardHistoryChange(
            ClipboardHistoryService.OnClipboardHistoryChange { listenerFired = true }
        )

        clipboardService?.editEntryContent(
            "No-op listener", "No-op listener", ClipboardTab.HISTORY
        )

        assertFalse("Listener should NOT fire on no-op", listenerFired)

        clipboardService?.setOnClipboardHistoryChange(null)
    }

    @Test
    fun testEditDuplicateViaService() {
        clearAllTablesForEdit()
        clipboardService?.addClip("Entry X")
        clipboardService?.addClip("Entry Y")

        val result = clipboardService?.editEntryContent(
            "Entry X", "Entry Y", ClipboardTab.HISTORY
        )
        assertTrue("Should be DuplicateConflict", result is EditEntryResult.DuplicateConflict)
    }

    @Test
    fun testEditCrossTabIndependence() {
        clearAllTablesForEdit()
        val content = "Cross-tab via service"
        clipboardService?.addClip(content)
        clipboardService?.pinEntry(content)
        clipboardService?.addToTodo(content)

        // Edit only the todo copy
        clipboardService?.editEntryContent(content, "Todo only edit", ClipboardTab.TODOS)

        // History and pinned should be unaffected
        val history = clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        val pinned = clipboardService?.getPinnedEntries() ?: emptyList()
        val db = ClipboardDatabase.getInstance(context)
        val todos = db.getTodoEntries()

        assertTrue(history.any { it.content == content })
        assertTrue(pinned.any { it.content == content })
        assertTrue(todos.any { it.content == "Todo only edit" })
        assertFalse(todos.any { it.content == content })
    }
}
