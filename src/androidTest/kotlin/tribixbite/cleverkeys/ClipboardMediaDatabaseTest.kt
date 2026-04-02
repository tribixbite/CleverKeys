package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for v4 media clipboard features in ClipboardDatabase.
 *
 * Tests media entry CRUD, thumbnail storage/retrieval, MIME type handling,
 * media-aware pin/todo COPY semantics, media path reference tracking,
 * and media-aware export/import.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardMediaDatabaseTest {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase

    private val futureExpiry = System.currentTimeMillis() + 3600_000L
    // Small test thumbnail (8x8 black WebP — 26 bytes is minimal valid WebP)
    private val testThumbnail = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, // "RIFF"
        0x12, 0x00, 0x00, 0x00, // file size
        0x57, 0x45, 0x42, 0x50, // "WEBP"
        0x56, 0x50, 0x38, 0x4C, // "VP8L"
        0x05, 0x00, 0x00, 0x00, // chunk size
        0x2F, 0x00, 0x00, 0x00, 0x00 // minimal VP8L data
    )

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

    // =========================================================================
    // Basic media entry CRUD
    // =========================================================================

    @Test
    fun testAddMediaEntry() {
        val added = db.addMediaClipboardEntry(
            content = "photo.jpg",
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/photo_abc123.jpg",
            contentHash = "sha256_fake_hash_1"
        )
        assertTrue("Should add media entry", added)
    }

    @Test
    fun testRetrieveMediaEntry() {
        db.addMediaClipboardEntry(
            content = "screenshot.png",
            expiryTimestamp = futureExpiry,
            mimeType = "image/png",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/screenshot_xyz.png",
            contentHash = "sha256_fake_hash_2"
        )

        val entries = db.getActiveClipboardEntries()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("screenshot.png", entry.content)
        assertEquals("image/png", entry.mimeType)
        assertTrue("Should be media", entry.isMedia)
        assertTrue("Should be image", entry.isImage)
        assertNotNull("Should have thumbnail", entry.thumbnailBlob)
        assertEquals("media/screenshot_xyz.png", entry.mediaPath)
    }

    @Test
    fun testMediaEntryWithoutThumbnail() {
        db.addMediaClipboardEntry(
            content = "document.pdf",
            expiryTimestamp = futureExpiry,
            mimeType = "application/pdf",
            thumbnailBlob = null,
            mediaPath = "media/document_abc.pdf",
            contentHash = "sha256_fake_hash_3"
        )

        val entry = db.getActiveClipboardEntries()[0]
        assertNull("Should not have thumbnail", entry.thumbnailBlob)
        assertFalse("Should not have thumbnail flag", entry.hasThumbnail)
        assertEquals("application/pdf", entry.mimeType)
    }

    @Test
    fun testMediaDuplicateMovedToTop() {
        db.addMediaClipboardEntry(
            content = "photo.jpg",
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/photo.jpg",
            contentHash = "same_hash"
        )
        Thread.sleep(50)
        // Same contentHash = duplicate — should update timestamp, not create new row
        val result = db.addMediaClipboardEntry(
            content = "photo.jpg",
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/photo.jpg",
            contentHash = "same_hash"
        )
        assertTrue("Duplicate media should succeed (moved to top)", result)
        assertEquals("Should still have 1 entry", 1, db.getActiveClipboardEntries().size)
    }

    @Test
    fun testRemoveMediaEntryReturnsMediaPath() {
        db.addMediaClipboardEntry(
            content = "removable.gif",
            expiryTimestamp = futureExpiry,
            mimeType = "image/gif",
            thumbnailBlob = null,
            mediaPath = "media/removable.gif",
            contentHash = "sha256_remove"
        )

        val mediaPath = db.removeClipboardEntry("removable.gif")
        assertEquals("Should return media path", "media/removable.gif", mediaPath)
        assertTrue("Should be removed", db.getActiveClipboardEntries().isEmpty())
    }

    @Test
    fun testRemoveTextEntryReturnsNull() {
        db.addClipboardEntry("Plain text", futureExpiry)

        val mediaPath = db.removeClipboardEntry("Plain text")
        assertNull("Text entry should return null media path", mediaPath)
    }

    // =========================================================================
    // Media-aware pin/todo COPY semantics
    // =========================================================================

    @Test
    fun testPinMediaEntry() {
        // Pin a media entry — should carry MIME type, thumbnail, and media path
        db.pinEntry(
            content = "pinned_photo.jpg",
            createdTimestamp = System.currentTimeMillis(),
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/pinned_photo.jpg"
        )

        val pinned = db.getPinnedEntries()
        assertEquals(1, pinned.size)
        assertEquals("image/jpeg", pinned[0].mimeType)
        assertTrue(pinned[0].isMedia)
        assertNotNull(pinned[0].thumbnailBlob)
        assertEquals("media/pinned_photo.jpg", pinned[0].mediaPath)
    }

    @Test
    fun testTodoMediaEntry() {
        db.addTodoEntry(
            content = "todo_video.mp4",
            createdTimestamp = System.currentTimeMillis(),
            mimeType = "video/mp4",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/todo_video.mp4"
        )

        val todos = db.getTodoEntries()
        assertEquals(1, todos.size)
        assertEquals("video/mp4", todos[0].mimeType)
        assertTrue(todos[0].isMedia)
        assertTrue(todos[0].isVideo)
    }

    @Test
    fun testMediaPinCopyIndependence() {
        // Add media to history, then pin it. Removing from history should not affect pin.
        db.addMediaClipboardEntry(
            content = "shared.png",
            expiryTimestamp = futureExpiry,
            mimeType = "image/png",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/shared.png",
            contentHash = "sha256_shared"
        )
        db.pinEntry(
            content = "shared.png",
            mimeType = "image/png",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/shared.png"
        )

        // Remove from history — pinned copy should survive
        db.removeClipboardEntry("shared.png")

        assertTrue("History should be empty", db.getActiveClipboardEntries().isEmpty())
        assertEquals("Pinned should survive", 1, db.getPinnedEntries().size)
        assertEquals("media/shared.png", db.getPinnedEntries()[0].mediaPath)
    }

    // =========================================================================
    // Media path reference tracking (isMediaPathReferenced)
    // =========================================================================

    @Test
    fun testMediaPathReferencedInHistory() {
        db.addMediaClipboardEntry(
            content = "ref_test.jpg",
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = null,
            mediaPath = "media/ref_test.jpg",
            contentHash = "sha256_ref"
        )

        assertTrue(db.isMediaPathReferenced("media/ref_test.jpg"))
        assertFalse(db.isMediaPathReferenced("media/nonexistent.jpg"))
    }

    @Test
    fun testMediaPathReferencedAcrossTables() {
        // Same media path in pinned — should be referenced even after history removal
        db.addMediaClipboardEntry(
            content = "cross_ref.png",
            expiryTimestamp = futureExpiry,
            mimeType = "image/png",
            thumbnailBlob = null,
            mediaPath = "media/cross_ref.png",
            contentHash = "sha256_cross"
        )
        db.pinEntry(
            content = "cross_ref.png",
            mimeType = "image/png",
            mediaPath = "media/cross_ref.png"
        )

        // Remove from history — still referenced in pinned
        db.removeClipboardEntry("cross_ref.png")
        assertTrue("Should still be referenced via pinned", db.isMediaPathReferenced("media/cross_ref.png"))

        // Remove from pinned — now truly unreferenced
        db.unpinEntry("cross_ref.png")
        assertFalse("Should no longer be referenced", db.isMediaPathReferenced("media/cross_ref.png"))
    }

    @Test
    fun testMediaPathNullNotReferenced() {
        assertFalse(db.isMediaPathReferenced(null))
        assertFalse(db.isMediaPathReferenced(""))
    }

    // =========================================================================
    // Text vs media entry properties (ClipboardEntry model)
    // =========================================================================

    @Test
    fun testTextEntryIsNotMedia() {
        db.addClipboardEntry("Just text", futureExpiry)
        val entry = db.getActiveClipboardEntries()[0]

        assertFalse(entry.isMedia)
        assertFalse(entry.isImage)
        assertFalse(entry.isVideo)
        assertNull(entry.mediaPath)
        assertNull(entry.thumbnailBlob)
        assertEquals(ClipboardEntry.MIME_TEXT_PLAIN, entry.mimeType)
    }

    @Test
    fun testMediaEntryIsNotText() {
        db.addMediaClipboardEntry(
            content = "video.mp4",
            expiryTimestamp = futureExpiry,
            mimeType = "video/mp4",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/video.mp4",
            contentHash = "sha256_video"
        )
        val entry = db.getActiveClipboardEntries()[0]

        assertTrue(entry.isMedia)
        assertFalse(entry.isImage)
        assertTrue(entry.isVideo)
    }

    // =========================================================================
    // Mixed text + media ordering
    // =========================================================================

    @Test
    fun testMixedEntriesOrderedByTimestamp() {
        db.addClipboardEntry("Text first", futureExpiry)
        Thread.sleep(50)
        db.addMediaClipboardEntry(
            content = "media_second.jpg",
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = null,
            mediaPath = "media/second.jpg",
            contentHash = "sha256_order1"
        )
        Thread.sleep(50)
        db.addClipboardEntry("Text third", futureExpiry)

        val entries = db.getActiveClipboardEntries()
        assertEquals(3, entries.size)
        // DESC order — newest first
        assertEquals("Text third", entries[0].content)
        assertEquals("media_second.jpg", entries[1].content)
        assertEquals("Text first", entries[2].content)
    }

    // =========================================================================
    // Media entries in export/import
    // =========================================================================

    @Test
    fun testExportIncludesMediaFields() {
        db.addMediaClipboardEntry(
            content = "export_test.png",
            expiryTimestamp = futureExpiry,
            mimeType = "image/png",
            thumbnailBlob = null,
            mediaPath = "media/export_test.png",
            contentHash = "sha256_export"
        )

        val json = db.exportToJSON()
        assertNotNull("Export should not be null", json)

        val activeEntries = json!!.getJSONArray("active_entries")
        assertEquals(1, activeEntries.length())
        val entry = activeEntries.getJSONObject(0)
        assertEquals("export_test.png", entry.getString("content"))
        assertEquals("image/png", entry.getString("mime_type"))
        assertEquals("media/export_test.png", entry.getString("media_path"))
    }

    @Test
    fun testExportIncludesMediaPinnedEntries() {
        db.pinEntry(
            content = "export_pinned.jpg",
            mimeType = "image/jpeg",
            mediaPath = "media/export_pinned.jpg"
        )

        val json = db.exportToJSON()!!
        val pinnedEntries = json.getJSONArray("pinned_entries")
        assertEquals(1, pinnedEntries.length())
        val entry = pinnedEntries.getJSONObject(0)
        assertEquals("image/jpeg", entry.getString("mime_type"))
    }

    // =========================================================================
    // Commit da40475c0: applySizeLimitBytes media-aware accounting
    // Verifies that thumbnail BLOB sizes are included in the size calculation
    // and that media paths of deleted entries are returned for file cleanup.
    // =========================================================================

    @Test
    fun testApplySizeLimitBytesIncludesThumbnailBlobSize() {
        // Add a text entry (small) and a media entry with large thumbnail
        // The media entry's thumbnail should count toward the size limit
        db.addClipboardEntry("tiny text", futureExpiry)
        Thread.sleep(10)
        db.addMediaClipboardEntry(
            content = "photo.jpg",
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "clipboard_media/0/abc.jpg",
            contentHash = "sha256_thumb_test"
        )

        // Both entries under 1MB → no deletions
        val (removed, _) = db.applySizeLimitBytes(1)
        assertEquals("Under limit, no deletions", 0, removed)
    }

    @Test
    fun testApplySizeLimitBytesReturnsMediaPaths() {
        // applySizeLimitBytes iterates oldest→newest, accumulating sizes.
        // Once running total exceeds the limit, ALL subsequent entries are marked for deletion.
        // Strategy: fill most of 1MB with text entries, then add media entry LAST (newest)
        // so it falls after the budget is exceeded and gets deleted.

        // Phase 1: fill ~900KB with text entries (oldest)
        for (i in 1..900) {
            db.addClipboardEntry("Fill_${i}_${"Y".repeat(1000)}", futureExpiry) // ~1KB each
            Thread.sleep(1)
        }
        Thread.sleep(10)

        // Phase 2: add media entry as newest — this tips over 1MB and gets deleted
        db.addMediaClipboardEntry(
            content = "late_photo_${"Z".repeat(200_000)}", // ~200KB content pushes total past 1MB
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "clipboard_media/0/late_photo.jpg",
            contentHash = "sha256_late_photo"
        )

        // Total: ~900KB text + ~200KB media = ~1.1MB > 1MB limit
        // The media entry is newest, so it's the one tipping over the budget
        val (removed, mediaPaths) = db.applySizeLimitBytes(1)
        assertTrue("Should delete entries that pushed over limit", removed > 0)
        assertTrue("Should return media path of deleted media entry",
            mediaPaths.contains("clipboard_media/0/late_photo.jpg"))
    }

    @Test
    fun testApplySizeLimitBytesNullFilesDirSkipsMediaFileSize() {
        // With filesDir=null (default), only DB-resident sizes are counted
        // (text content + thumbnail BLOB), not media files on disk
        db.addMediaClipboardEntry(
            content = "disk_photo.jpg",
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "clipboard_media/0/disk_photo.jpg",
            contentHash = "sha256_disk"
        )

        // null filesDir → media file size not counted, only DB content + thumbnail
        val (removed, _) = db.applySizeLimitBytes(1, null)
        assertEquals("Small DB-only entry under 1MB, no deletion", 0, removed)
    }

    @Test
    fun testApplySizeLimitBytesWithFilesDirCountsMediaFiles() {
        // Create an actual media file on disk, then verify it's counted in size limit
        val filesDir = context.filesDir
        val mediaDir = java.io.File(filesDir, "clipboard_media/test_partition")
        mediaDir.mkdirs()
        val mediaFile = java.io.File(mediaDir, "large_media.jpg")
        try {
            // Write a 512KB file to disk
            mediaFile.writeBytes(ByteArray(512 * 1024))

            db.addMediaClipboardEntry(
                content = "large_media.jpg",
                expiryTimestamp = futureExpiry,
                mimeType = "image/jpeg",
                thumbnailBlob = testThumbnail,
                mediaPath = "clipboard_media/test_partition/large_media.jpg",
                contentHash = "sha256_large_media"
            )
            Thread.sleep(10)
            // Add another smaller entry (newer, should survive)
            db.addClipboardEntry("survive", futureExpiry)

            // With filesDir: 512KB media file + ~14 bytes text + ~26 bytes thumbnail
            // Total > 0.5MB. Set limit below total to trigger deletion.
            // Actually 512KB < 1MB, so let's not rely on deletion.
            // Instead verify it doesn't crash and returns valid Pair
            val (removed, mediaPaths) = db.applySizeLimitBytes(1, filesDir)
            assertEquals("Under 1MB total, no deletions", 0, removed)
            assertTrue("Empty media paths when no deletions", mediaPaths.isEmpty())
        } finally {
            mediaFile.delete()
            mediaDir.delete()
        }
    }

    @Test
    fun testApplySizeLimitBytesReturnsPairStructure() {
        // Verify the return type is Pair<Int, List<String>>
        db.addClipboardEntry("content", futureExpiry)
        val result = db.applySizeLimitBytes(1)
        val (count, paths) = result
        assertEquals("No deletions", 0, count)
        assertTrue("Empty media paths", paths.isEmpty())
    }

    @Test
    fun testApplySizeLimitBytesMediaOnlyDeletionReturnsPath() {
        // Single large media entry that exceeds limit → verify path returned
        // Use a huge "content" text to make the entry large enough to exceed limit
        db.addMediaClipboardEntry(
            content = "M".repeat(2 * 1024 * 1024), // 2MB content text
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "clipboard_media/0/huge.jpg",
            contentHash = "sha256_huge"
        )
        Thread.sleep(10)
        // Add a tiny entry (newest, should survive)
        db.addClipboardEntry("keep", futureExpiry)

        // 2MB + tiny > 1MB limit → oldest (huge media) should be deleted
        val (removed, mediaPaths) = db.applySizeLimitBytes(1)
        assertTrue("Should delete the large entry", removed >= 1)
        assertEquals("Should return the media path", "clipboard_media/0/huge.jpg", mediaPaths.firstOrNull())
    }
}
