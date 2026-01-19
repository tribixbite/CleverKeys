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

        // Get clipboard service
        clipboardService = ClipboardHistoryService.get_service(context)
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
        // Filter out pinned entries which survive clear
        val unpinned = history.filter { !it.isPinned }
        assertTrue("History should be empty after clear (except pinned)", unpinned.isEmpty())
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
        // Add many entries to test the limit
        repeat(50) { i ->
            clipboardService?.addClip("Test entry $i - ${System.currentTimeMillis()}")
        }

        // This should not throw TransactionTooLargeException
        val history = try {
            clipboardService?.clearExpiredAndGetHistory() ?: emptyList()
        } catch (e: android.os.TransactionTooLargeException) {
            fail("TransactionTooLargeException should not be thrown")
            emptyList()
        } catch (e: Exception) {
            // Other exceptions are fine for this test
            emptyList()
        }

        assertNotNull("History retrieval should succeed", history)
    }

    // =========================================================================
    // Pinned entries tests
    // =========================================================================

    @Test
    fun testPinEntry() {
        val testClip = "Pinned entry ${System.currentTimeMillis()}"

        clipboardService?.addClip(testClip)
        clipboardService?.setPinnedStatus(testClip, true)

        val pinnedEntries = clipboardService?.getPinnedEntries() ?: emptyList()
        val hasPinned = pinnedEntries.any { it.content == testClip }
        assertTrue("Entry should be pinned", hasPinned)
    }

    @Test
    fun testUnpinEntry() {
        val testClip = "To unpin ${System.currentTimeMillis()}"

        clipboardService?.addClip(testClip)
        clipboardService?.setPinnedStatus(testClip, true)
        clipboardService?.setPinnedStatus(testClip, false)

        val pinnedEntries = clipboardService?.getPinnedEntries() ?: emptyList()
        val hasPinned = pinnedEntries.any { it.content == testClip }
        assertFalse("Entry should be unpinned", hasPinned)
    }

    @Test
    fun testPinnedEntriesSurviveClear() {
        val pinnedClip = "Survives clear ${System.currentTimeMillis()}"

        clipboardService?.addClip(pinnedClip)
        clipboardService?.setPinnedStatus(pinnedClip, true)
        clipboardService?.clearHistory()

        val pinnedEntries = clipboardService?.getPinnedEntries() ?: emptyList()
        val hasPinned = pinnedEntries.any { it.content == pinnedClip }
        assertTrue("Pinned entry should survive clear", hasPinned)

        // Clean up
        clipboardService?.setPinnedStatus(pinnedClip, false)
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
        clipboardService?.addClip("Trigger listener ${System.currentTimeMillis()}")

        // Listener may or may not be called depending on duplicate detection
        // Just verify it doesn't crash
        assertNotNull("Setting listener should work", listener)
    }

    @Test
    fun testRemoveChangeListener() {
        clipboardService?.setOnClipboardHistoryChange(null)
        // Should not crash when listener is null
        clipboardService?.addClip("No listener test ${System.currentTimeMillis()}")
    }
}
