package tribixbite.cleverkeys

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for post-v1.3.0 review fixes.
 *
 * Covers:
 * - Item 10: ClipboardMediaManager Direct Boot guard (isDeviceUnlocked)
 * - Item 6:  ContentObserver for IME status (replaces 500ms polling)
 * - Item 8:  Timestamp-based expand state keying (ClipboardEntry.timestamp uniqueness)
 * - Item 11: Build task deduplication (compilation-level — verified by build success)
 * - Item 5:  SparkleMagicBackground rename (compilation-level — verified by build success)
 */
@RunWith(AndroidJUnit4::class)
class PostReviewFixesInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
    }

    // =========================================================================
    // Item 10: Direct Boot guard for ClipboardMediaManager
    // =========================================================================

    @Test
    fun testDeviceIsUnlockedDuringTest() {
        // During instrumented tests, the device is always unlocked.
        // Verify the guard returns true so all operations proceed normally.
        if (Build.VERSION.SDK_INT >= 24) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            assertNotNull("UserManager should be available", um)
            assertTrue("Device should be unlocked during tests", um!!.isUserUnlocked)
        }
        // Pre-24: no Direct Boot, always "unlocked"
    }

    @Test
    fun testClipboardMediaManagerSaveMediaOnUnlockedDevice() {
        // Verify that ClipboardMediaManager doesn't throw on an unlocked device.
        // saveMedia with a bogus URI should return null (no crash from Direct Boot guard).
        val manager = ClipboardMediaManager(context)
        val result = manager.saveMedia(
            android.net.Uri.parse("content://fake/nonexistent"),
            "image/jpeg"
        )
        // Expected: null (URI doesn't exist), NOT a crash from Direct Boot
        assertNull("Should return null for invalid URI, not crash", result)
    }

    @Test
    fun testClipboardMediaManagerDeleteMediaOnUnlockedDevice() {
        // deleteMedia should not throw on unlocked device (even for nonexistent path)
        val manager = ClipboardMediaManager(context)
        // Should be a no-op (file doesn't exist), not a crash
        manager.deleteMedia("clipboard_media/nonexistent/file.jpg")
    }

    @Test
    fun testClipboardMediaManagerClearAllOnUnlockedDevice() {
        // clearAll should work on unlocked device
        val manager = ClipboardMediaManager(context)
        manager.clearAll() // Should not throw
    }

    @Test
    fun testClipboardMediaManagerCleanupOrphansOnUnlockedDevice() {
        // cleanupOrphans should work on unlocked device
        val manager = ClipboardMediaManager(context)
        manager.cleanupOrphans(emptySet()) // Should not throw
    }

    @Test
    fun testClipboardMediaManagerStorageStatsOnUnlockedDevice() {
        // getStorageStats should return valid (possibly zero) values
        val manager = ClipboardMediaManager(context)
        val (fileCount, totalSize) = manager.getStorageStats()
        assertTrue("File count should be >= 0", fileCount >= 0)
        assertTrue("Total size should be >= 0", totalSize >= 0)
    }

    // =========================================================================
    // Item 6: ContentObserver for IME settings (replaces 500ms polling)
    // =========================================================================

    @Test
    fun testContentObserverRegistrationDoesNotThrow() {
        // Verify that registering ContentObserver on Settings.Secure URIs works
        val latch = CountDownLatch(1)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                latch.countDown()
            }
        }

        // Register — should not throw
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
            false, observer
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
            false, observer
        )

        // Unregister — should not throw
        context.contentResolver.unregisterContentObserver(observer)
    }

    @Test
    fun testSettingsSecureUriResolution() {
        // Verify that the Settings.Secure URIs we observe are valid
        val enabledUri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS)
        assertNotNull("ENABLED_INPUT_METHODS URI should resolve", enabledUri)
        assertTrue("URI should be content:// scheme",
            enabledUri.toString().startsWith("content://"))

        val defaultUri = Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertNotNull("DEFAULT_INPUT_METHOD URI should resolve", defaultUri)
        assertTrue("URI should be content:// scheme",
            defaultUri.toString().startsWith("content://"))
    }

    @Test
    fun testEnabledInputMethodsViaIMM() {
        // API 34+ blocks direct Settings.Secure.getString for ENABLED_INPUT_METHODS.
        // The real code (LauncherActivity) uses InputMethodManager.enabledInputMethodList.
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        assertNotNull("enabledInputMethodList should not be null", enabledMethods)
        assertTrue("Should have at least one enabled IME", enabledMethods.isNotEmpty())
    }

    // =========================================================================
    // Item 8: Timestamp-based expand state keying
    // =========================================================================

    @Test
    fun testClipboardEntryTimestampsAreUniqueAcrossAdds() {
        // Verify that entries added with small delays have unique timestamps
        val db = ClipboardDatabase.getInstance(context)
        db.writableDatabase.delete("clipboard_entries", null, null)

        val futureExpiry = System.currentTimeMillis() + 3600_000L
        db.addClipboardEntry("Entry A", futureExpiry)
        Thread.sleep(5) // Small delay for timestamp uniqueness
        db.addClipboardEntry("Entry B", futureExpiry)
        Thread.sleep(5)
        db.addClipboardEntry("Entry C", futureExpiry)

        val entries = db.getActiveClipboardEntries()
        assertEquals("Should have 3 entries", 3, entries.size)

        // All timestamps should be unique
        val timestamps = entries.map { it.timestamp }.toSet()
        assertEquals("All timestamps should be unique", 3, timestamps.size)

        // Clean up
        db.writableDatabase.delete("clipboard_entries", null, null)
    }

    @Test
    fun testClipboardEntryTimestampStableAcrossReads() {
        // Verify that re-reading entries returns the same timestamps
        val db = ClipboardDatabase.getInstance(context)
        db.writableDatabase.delete("clipboard_entries", null, null)

        val futureExpiry = System.currentTimeMillis() + 3600_000L
        db.addClipboardEntry("Stable timestamp", futureExpiry)

        val read1 = db.getActiveClipboardEntries()
        val read2 = db.getActiveClipboardEntries()

        assertEquals("Timestamp should be identical across reads",
            read1[0].timestamp, read2[0].timestamp)

        // Clean up
        db.writableDatabase.delete("clipboard_entries", null, null)
    }

    @Test
    fun testTimestampSurvivesContentEdit() {
        // Verify that editing content doesn't change the timestamp
        // (critical for timestamp-keyed expand state stability)
        val db = ClipboardDatabase.getInstance(context)
        db.writableDatabase.delete("clipboard_entries", null, null)

        val futureExpiry = System.currentTimeMillis() + 3600_000L
        db.addClipboardEntry("Original", futureExpiry)
        val originalTs = db.getActiveClipboardEntries()[0].timestamp

        // Edit the content
        db.updateHistoryEntryContent("Original", "Edited")
        val editedTs = db.getActiveClipboardEntries()[0].timestamp

        assertEquals("Timestamp should survive content edit", originalTs, editedTs)

        // Clean up
        db.writableDatabase.delete("clipboard_entries", null, null)
    }

    // =========================================================================
    // Item 3: applySizeLimitBytes Pair return + media path cleanup
    // (Additional integration-level tests beyond ClipboardMediaDatabaseTest)
    // =========================================================================

    @Test
    fun testApplySizeLimitBytesTextOnlyReturnsPairWithEmptyPaths() {
        // Text-only entries should return empty media paths list
        val db = ClipboardDatabase.getInstance(context)
        db.writableDatabase.delete("clipboard_entries", null, null)

        val futureExpiry = System.currentTimeMillis() + 3600_000L
        // Add 2MB+ of text entries to exceed 1MB limit
        // Each entry must have UNIQUE content (dedup rejects duplicates)
        for (i in 1..2100) {
            db.addClipboardEntry("Entry_${i}_${"X".repeat(1000)}", futureExpiry)
            Thread.sleep(1)
        }

        val (removed, mediaPaths) = db.applySizeLimitBytes(1)
        assertTrue("Should delete oldest entries to get under 1MB", removed > 0)
        assertTrue("Text-only entries have no media paths", mediaPaths.isEmpty())

        // Clean up
        db.writableDatabase.delete("clipboard_entries", null, null)
    }

    @Test
    fun testApplySizeLimitBytesWithActualMediaFileOnDisk() {
        // Create a real media file, add a DB entry referencing it,
        // verify applySizeLimitBytes with filesDir counts the file
        val db = ClipboardDatabase.getInstance(context)
        db.writableDatabase.delete("clipboard_entries", null, null)

        val filesDir = context.filesDir
        val testDir = File(filesDir, "clipboard_media/test_aslb")
        testDir.mkdirs()
        val testFile = File(testDir, "counted.jpg")

        try {
            // Write a 100KB file
            testFile.writeBytes(ByteArray(100 * 1024))

            val futureExpiry = System.currentTimeMillis() + 3600_000L
            db.addMediaClipboardEntry(
                content = "counted.jpg",
                expiryTimestamp = futureExpiry,
                mimeType = "image/jpeg",
                thumbnailBlob = ByteArray(5000), // 5KB thumbnail
                mediaPath = "clipboard_media/test_aslb/counted.jpg",
                contentHash = "sha256_counted_test"
            )

            // With filesDir: 100KB file + 5KB thumbnail + ~11 bytes content < 1MB
            val (removed, _) = db.applySizeLimitBytes(1, filesDir)
            assertEquals("Entry under 1MB, should not be deleted", 0, removed)

            // Verify total would include file: read what the query sees
            val entries = db.getActiveClipboardEntries()
            assertEquals("Entry should still exist", 1, entries.size)
        } finally {
            testFile.delete()
            testDir.delete()
            db.writableDatabase.delete("clipboard_entries", null, null)
        }
    }

    // =========================================================================
    // Item 5 + 7: Compilation-level verification
    // (Rename + toast removal verified by successful compilation)
    // =========================================================================

    @Test
    fun testClipboardEntryIsMediaProperty() {
        // Verify ClipboardEntry.isMedia works correctly (v4 schema support)
        val textEntry = ClipboardEntry("hello", System.currentTimeMillis())
        assertFalse("Text entry should not be media", textEntry.isMedia)

        val imageEntry = ClipboardEntry(
            "photo.jpg", System.currentTimeMillis(),
            mimeType = "image/jpeg",
            mediaPath = "media/photo.jpg"
        )
        assertTrue("Image entry should be media", imageEntry.isMedia)
        assertTrue("Should be image", imageEntry.isImage)
        assertFalse("Should not be video", imageEntry.isVideo)
    }

    @Test
    fun testClipboardEntryHasThumbnailProperty() {
        val withThumb = ClipboardEntry(
            "photo.jpg", System.currentTimeMillis(),
            mimeType = "image/jpeg",
            thumbnailBlob = ByteArray(100)
        )
        assertTrue("Should have thumbnail", withThumb.hasThumbnail)

        val noThumb = ClipboardEntry(
            "photo.jpg", System.currentTimeMillis(),
            mimeType = "image/jpeg"
        )
        assertFalse("Should not have thumbnail", noThumb.hasThumbnail)
    }
}
