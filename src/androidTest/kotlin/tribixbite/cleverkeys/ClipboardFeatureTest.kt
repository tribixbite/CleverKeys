package tribixbite.cleverkeys

import android.content.ClipData
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
 * Instrumented tests for clipboard features not covered by existing test suites:
 * - Tag system (set/get tags on pinned + todo entries, getAllTags, JSON parsing)
 * - Todo status transitions (active → planned → completed, invalid status rejection)
 * - Text-only export mode (media entries skipped, media_skipped count)
 * - Export/import roundtrip for tags + status + media metadata
 * - Long-press media copy bug (#BUG: copies URI text instead of actual image/file)
 * - Config toggle behavior (clipboard_text_only, clipboard_pinned_enabled, clipboard_todo_enabled)
 * - COPY semantics edge cases (pinned/todo independence from history)
 */
@RunWith(AndroidJUnit4::class)
class ClipboardFeatureTest {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase

    private val futureExpiry = System.currentTimeMillis() + 3600_000L
    // Minimal valid WebP thumbnail (26 bytes)
    private val testThumbnail = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x12, 0x00, 0x00, 0x00,
        0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4C,
        0x05, 0x00, 0x00, 0x00, 0x2F, 0x00, 0x00, 0x00, 0x00
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
    // Tag system — setPinnedEntryTags / setTodoEntryTags
    // =========================================================================

    @Test
    fun tags_setPinnedEntryTags() {
        db.pinEntry("Tagged pin")

        val result = db.setPinnedEntryTags("Tagged pin", listOf("work", "urgent"))
        assertTrue("Should set tags successfully", result)

        // Verify via full entry retrieval
        val entries = db.getPinnedEntriesFull()
        assertEquals(1, entries.size)
        assertEquals(listOf("work", "urgent"), entries[0].tags)
    }

    @Test
    fun tags_setTodoEntryTags() {
        db.addTodoEntry("Tagged todo")

        val result = db.setTodoEntryTags("Tagged todo", listOf("shopping", "low-priority"))
        assertTrue("Should set tags successfully", result)

        val entries = db.getTodoEntriesFull()
        assertEquals(1, entries.size)
        assertEquals(listOf("shopping", "low-priority"), entries[0].tags)
    }

    @Test
    fun tags_clearTagsWithEmptyList() {
        db.pinEntry("Has tags")
        db.setPinnedEntryTags("Has tags", listOf("temp"))

        // Clear by setting empty list
        db.setPinnedEntryTags("Has tags", emptyList())

        val entries = db.getPinnedEntriesFull()
        assertTrue("Tags should be empty after clear", entries[0].tags.isEmpty())
    }

    @Test
    fun tags_updateExistingTags() {
        db.pinEntry("Update tags")
        db.setPinnedEntryTags("Update tags", listOf("old1", "old2"))

        // Replace with new tags
        db.setPinnedEntryTags("Update tags", listOf("new1", "new2", "new3"))

        val entries = db.getPinnedEntriesFull()
        assertEquals(listOf("new1", "new2", "new3"), entries[0].tags)
    }

    @Test
    fun tags_setTagsOnNonexistentEntryReturnsFalse() {
        val result = db.setPinnedEntryTags("Does not exist", listOf("tag"))
        assertFalse("Should return false for nonexistent entry", result)
    }

    @Test
    fun tags_multipleEntriesWithDifferentTags() {
        db.pinEntry("Pin A")
        db.pinEntry("Pin B")
        db.setPinnedEntryTags("Pin A", listOf("work"))
        db.setPinnedEntryTags("Pin B", listOf("personal"))

        val entries = db.getPinnedEntriesFull()
        // Ordered by position ASC — Pin A first, then Pin B
        assertEquals(listOf("work"), entries[0].tags)
        assertEquals(listOf("personal"), entries[1].tags)
    }

    // =========================================================================
    // Tag aggregation — getAllPinnedTags / getAllTodoTags
    // =========================================================================

    @Test
    fun tags_getAllPinnedTagsDeduplicatesAcrossEntries() {
        db.pinEntry("Pin 1")
        db.pinEntry("Pin 2")
        db.pinEntry("Pin 3")
        db.setPinnedEntryTags("Pin 1", listOf("work", "urgent"))
        db.setPinnedEntryTags("Pin 2", listOf("work", "personal"))
        db.setPinnedEntryTags("Pin 3", listOf("urgent"))

        val allTags = db.getAllPinnedTags()
        assertEquals("Should deduplicate across entries", setOf("work", "urgent", "personal"), allTags)
    }

    @Test
    fun tags_getAllTodoTagsDeduplicates() {
        db.addTodoEntry("Todo 1")
        db.addTodoEntry("Todo 2")
        db.setTodoEntryTags("Todo 1", listOf("shopping", "groceries"))
        db.setTodoEntryTags("Todo 2", listOf("shopping", "errands"))

        val allTags = db.getAllTodoTags()
        assertEquals(setOf("shopping", "groceries", "errands"), allTags)
    }

    @Test
    fun tags_getAllTagsEmptyWhenNoEntries() {
        assertTrue("Pinned tags should be empty", db.getAllPinnedTags().isEmpty())
        assertTrue("Todo tags should be empty", db.getAllTodoTags().isEmpty())
    }

    @Test
    fun tags_getAllTagsEmptyWhenNoTagsSet() {
        db.pinEntry("No tags")
        db.addTodoEntry("No tags either")

        // Default tags is "[]" — should return empty set
        assertTrue("Pinned tags should be empty", db.getAllPinnedTags().isEmpty())
        assertTrue("Todo tags should be empty", db.getAllTodoTags().isEmpty())
    }

    // =========================================================================
    // Tag JSON parsing — PinnedEntry.tagsFromJson / TodoEntry.tagsFromJson
    // =========================================================================

    @Test
    fun tags_parseJsonNull() {
        assertEquals(emptyList<String>(), PinnedEntry.tagsFromJson(null))
        assertEquals(emptyList<String>(), TodoEntry.tagsFromJson(null))
    }

    @Test
    fun tags_parseJsonEmptyString() {
        assertEquals(emptyList<String>(), PinnedEntry.tagsFromJson(""))
        assertEquals(emptyList<String>(), TodoEntry.tagsFromJson(""))
    }

    @Test
    fun tags_parseJsonEmptyArray() {
        assertEquals(emptyList<String>(), PinnedEntry.tagsFromJson("[]"))
        assertEquals(emptyList<String>(), TodoEntry.tagsFromJson("[]"))
    }

    @Test
    fun tags_parseJsonValidArray() {
        val json = """["alpha","beta","gamma"]"""
        assertEquals(listOf("alpha", "beta", "gamma"), PinnedEntry.tagsFromJson(json))
        assertEquals(listOf("alpha", "beta", "gamma"), TodoEntry.tagsFromJson(json))
    }

    @Test
    fun tags_parseJsonMalformedGraceful() {
        // Malformed JSON should not crash — returns empty list
        assertEquals(emptyList<String>(), PinnedEntry.tagsFromJson("{invalid"))
        assertEquals(emptyList<String>(), TodoEntry.tagsFromJson("not json at all"))
    }

    @Test
    fun tags_serializeRoundTrip() {
        val entry = PinnedEntry(
            id = 1, content = "test", contentHash = "hash",
            createdTimestamp = 0, pinnedTimestamp = 0,
            position = 1.0, tags = listOf("a", "b", "c")
        )
        val json = entry.tagsToJson()
        val parsed = PinnedEntry.tagsFromJson(json)
        assertEquals(listOf("a", "b", "c"), parsed)
    }

    // =========================================================================
    // Todo status transitions
    // =========================================================================

    @Test
    fun todoStatus_activeToCompleted() {
        db.addTodoEntry("Complete me")

        val result = db.setTodoEntryStatus("Complete me", TodoEntry.STATUS_COMPLETED)
        assertTrue("Should update status", result)

        val entries = db.getTodoEntriesFull()
        assertEquals(TodoEntry.STATUS_COMPLETED, entries[0].status)
        assertTrue(entries[0].isCompleted)
    }

    @Test
    fun todoStatus_activeToPlanned() {
        db.addTodoEntry("Plan me")

        db.setTodoEntryStatus("Plan me", TodoEntry.STATUS_PLANNED)

        val entries = db.getTodoEntriesFull()
        assertEquals(TodoEntry.STATUS_PLANNED, entries[0].status)
        assertTrue(entries[0].isPlanned)
    }

    @Test
    fun todoStatus_completedBackToActive() {
        db.addTodoEntry("Reactivate me")
        db.setTodoEntryStatus("Reactivate me", TodoEntry.STATUS_COMPLETED)

        // Transition back to active
        db.setTodoEntryStatus("Reactivate me", TodoEntry.STATUS_ACTIVE)

        val entries = db.getTodoEntriesFull()
        assertEquals(TodoEntry.STATUS_ACTIVE, entries[0].status)
        assertTrue(entries[0].isActive)
        assertFalse(entries[0].isCompleted)
    }

    @Test
    fun todoStatus_invalidStatusRejected() {
        db.addTodoEntry("Invalid status")

        val result = db.setTodoEntryStatus("Invalid status", "invalid_status")
        assertFalse("Invalid status should be rejected", result)

        // Entry should keep original status
        val entries = db.getTodoEntriesFull()
        assertEquals(TodoEntry.STATUS_ACTIVE, entries[0].status)
    }

    @Test
    fun todoStatus_setOnNonexistentReturnsFalse() {
        val result = db.setTodoEntryStatus("Ghost entry", TodoEntry.STATUS_COMPLETED)
        assertFalse("Should return false for nonexistent entry", result)
    }

    @Test
    fun todoStatus_nullContentReturnsFalse() {
        val result = db.setTodoEntryStatus(null, TodoEntry.STATUS_COMPLETED)
        assertFalse("Null content should return false", result)
    }

    @Test
    fun todoStatus_validStatusesSet() {
        // Verify all valid statuses are recognized
        assertEquals(
            setOf("active", "planned", "completed"),
            TodoEntry.VALID_STATUSES
        )
    }

    // =========================================================================
    // Text-only export mode — exportToJSON(textOnly=true)
    // =========================================================================

    @Test
    fun export_textOnlySkipsMediaEntries() {
        db.addClipboardEntry("Text entry", futureExpiry)
        db.addMediaClipboardEntry(
            content = "photo.jpg",
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/photo.jpg",
            contentHash = "sha256_photo"
        )

        val json = db.exportToJSON(textOnly = true)
        assertNotNull(json)

        val activeEntries = json!!.getJSONArray("active_entries")
        assertEquals("Should only include text entry", 1, activeEntries.length())
        assertEquals("Text entry", activeEntries.getJSONObject(0).getString("content"))
        assertEquals("Should count 1 skipped media", 1, json.getInt("media_skipped"))
    }

    @Test
    fun export_textOnlySkipsMediaInPinned() {
        db.pinEntry("Text pin")
        db.pinEntry(
            content = "image_pin.png",
            mimeType = "image/png",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/image_pin.png"
        )

        val json = db.exportToJSON(textOnly = true)!!
        val pinnedEntries = json.getJSONArray("pinned_entries")
        assertEquals("Should only include text pin", 1, pinnedEntries.length())
        assertEquals("Text pin", pinnedEntries.getJSONObject(0).getString("content"))
    }

    @Test
    fun export_textOnlySkipsMediaInTodo() {
        db.addTodoEntry("Text todo")
        db.addTodoEntry(
            content = "video_todo.mp4",
            mimeType = "video/mp4",
            mediaPath = "media/video_todo.mp4"
        )

        val json = db.exportToJSON(textOnly = true)!!
        val todoEntries = json.getJSONArray("todo_entries")
        assertEquals("Should only include text todo", 1, todoEntries.length())
        assertEquals("Text todo", todoEntries.getJSONObject(0).getString("content"))
    }

    @Test
    fun export_fullModeIncludesMediaMetadata() {
        db.addMediaClipboardEntry(
            content = "photo.jpg",
            expiryTimestamp = futureExpiry,
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/photo.jpg",
            contentHash = "sha256_full"
        )

        val json = db.exportToJSON(textOnly = false)!!
        val activeEntries = json.getJSONArray("active_entries")
        assertEquals(1, activeEntries.length())
        val entry = activeEntries.getJSONObject(0)
        assertEquals("image/jpeg", entry.getString("mime_type"))
        assertEquals("media/photo.jpg", entry.getString("media_path"))
        assertFalse("Should not have media_skipped key", json.has("media_skipped"))
    }

    @Test
    fun export_textOnlyCountsAllSkippedMedia() {
        // Add 3 media entries across all tables
        db.addMediaClipboardEntry("img1.jpg", futureExpiry, "image/jpeg", null, "media/img1.jpg", "h1")
        db.addMediaClipboardEntry("img2.png", futureExpiry, "image/png", null, "media/img2.png", "h2")
        db.pinEntry("pinned_img.gif", mimeType = "image/gif", mediaPath = "media/pinned.gif")
        db.addTodoEntry("todo_vid.mp4", mimeType = "video/mp4", mediaPath = "media/vid.mp4")

        val json = db.exportToJSON(textOnly = true)!!
        assertEquals("Should count all 4 skipped media", 4, json.getInt("media_skipped"))
        assertEquals(0, json.getInt("total_active"))
        assertEquals(0, json.getInt("total_pinned"))
        assertEquals(0, json.getInt("total_todo"))
    }

    // =========================================================================
    // Export/import roundtrip — tags and status preservation
    // =========================================================================

    @Test
    fun exportImport_tagsPreservedInPinned() {
        db.pinEntry("Tagged pin")
        db.setPinnedEntryTags("Tagged pin", listOf("work", "important"))

        val exported = db.exportToJSON()!!

        // Verify tags are in the JSON
        val pinnedArray = exported.getJSONArray("pinned_entries")
        val tagsJson = pinnedArray.getJSONObject(0).getString("tags")
        assertTrue("Export should contain tags", tagsJson.contains("work"))

        // Reimport
        clearAllTables()
        val result = db.importFromJSON(exported)
        assertEquals("Should import 1 pinned", 1, result[1])

        // Verify tags survive roundtrip
        val entries = db.getPinnedEntriesFull()
        assertEquals(1, entries.size)
        assertTrue("Tags should contain 'work'", entries[0].tags.contains("work"))
        assertTrue("Tags should contain 'important'", entries[0].tags.contains("important"))
    }

    @Test
    fun exportImport_tagsPreservedInTodo() {
        db.addTodoEntry("Tagged todo")
        db.setTodoEntryTags("Tagged todo", listOf("groceries"))

        val exported = db.exportToJSON()!!
        clearAllTables()
        db.importFromJSON(exported)

        val entries = db.getTodoEntriesFull()
        assertEquals(1, entries.size)
        assertTrue("Tags should survive roundtrip", entries[0].tags.contains("groceries"))
    }

    @Test
    fun exportImport_todoStatusPreserved() {
        db.addTodoEntry("Completed task")
        db.setTodoEntryStatus("Completed task", TodoEntry.STATUS_COMPLETED)
        db.addTodoEntry("Planned task")
        db.setTodoEntryStatus("Planned task", TodoEntry.STATUS_PLANNED)

        val exported = db.exportToJSON()!!
        clearAllTables()
        db.importFromJSON(exported)

        val entries = db.getTodoEntriesFull()
        assertEquals(2, entries.size)

        // Find entries by content (order may vary)
        val completed = entries.find { it.content == "Completed task" }!!
        val planned = entries.find { it.content == "Planned task" }!!
        assertEquals(TodoEntry.STATUS_COMPLETED, completed.status)
        assertEquals(TodoEntry.STATUS_PLANNED, planned.status)
    }

    @Test
    fun exportImport_mediaMetadataPreserved() {
        db.addMediaClipboardEntry(
            content = "roundtrip.png",
            expiryTimestamp = futureExpiry,
            mimeType = "image/png",
            thumbnailBlob = null,
            mediaPath = "media/roundtrip.png",
            contentHash = "sha256_roundtrip"
        )

        val exported = db.exportToJSON(textOnly = false)!!
        clearAllTables()
        db.importFromJSON(exported)

        val entries = db.getActiveClipboardEntries()
        assertEquals(1, entries.size)
        assertEquals("image/png", entries[0].mimeType)
        assertEquals("media/roundtrip.png", entries[0].mediaPath)
        assertTrue(entries[0].isMedia)
    }

    @Test
    fun exportImport_positionPreservedInPinned() {
        // Pin 3 entries — positions should be 1.0, 2.0, 3.0
        db.pinEntry("First")
        db.pinEntry("Second")
        db.pinEntry("Third")

        val exported = db.exportToJSON()!!
        clearAllTables()
        db.importFromJSON(exported)

        val entries = db.getPinnedEntriesFull()
        assertEquals(3, entries.size)
        // Positions imported from JSON, order preserved
        assertTrue("Positions should be ascending",
            entries[0].position < entries[1].position &&
            entries[1].position < entries[2].position)
    }

    // =========================================================================
    // COPY semantics edge cases — independent tables
    // =========================================================================

    @Test
    fun copySemantics_editPinnedDoesNotAffectHistory() {
        val content = "Cross-table edit"
        db.addClipboardEntry(content, futureExpiry)
        db.pinEntry(content)

        // Edit pinned copy
        db.updatePinnedEntryContent(content, "Edited pinned version")

        // History copy should be unchanged
        assertEquals(content, db.getActiveClipboardEntries()[0].content)
        assertEquals("Edited pinned version", db.getPinnedEntries()[0].content)
    }

    @Test
    fun copySemantics_editTodoDoesNotAffectPinned() {
        val content = "In both tables"
        db.pinEntry(content)
        db.addTodoEntry(content)

        // Edit todo copy
        db.updateTodoEntryContent(content, "Todo version")

        // Pinned copy should be unchanged
        assertEquals(content, db.getPinnedEntries()[0].content)
        assertEquals("Todo version", db.getTodoEntries()[0].content)
    }

    @Test
    fun copySemantics_deleteFromHistoryDoesNotAffectPinnedOrTodo() {
        val content = "Delete test"
        db.addClipboardEntry(content, futureExpiry)
        db.pinEntry(content)
        db.addTodoEntry(content)

        db.removeClipboardEntry(content)

        assertTrue("History should be empty", db.getActiveClipboardEntries().isEmpty())
        assertEquals("Pinned should survive", 1, db.getPinnedEntries().size)
        assertEquals("Todo should survive", 1, db.getTodoEntries().size)
    }

    @Test
    fun copySemantics_tagsIndependentBetweenPinnedAndTodo() {
        val content = "Shared content"
        db.pinEntry(content)
        db.addTodoEntry(content)

        db.setPinnedEntryTags(content, listOf("pinned-tag"))
        db.setTodoEntryTags(content, listOf("todo-tag"))

        val pinnedTags = db.getPinnedEntriesFull()[0].tags
        val todoTags = db.getTodoEntriesFull()[0].tags

        assertEquals(listOf("pinned-tag"), pinnedTags)
        assertEquals(listOf("todo-tag"), todoTags)
    }

    // =========================================================================
    // Long-press media copy bug
    // #BUG: ClipboardHistoryView.getView() line 670-676 always uses
    // ClipData.newPlainText() even for media entries. For images/video,
    // should use ClipData.newUri() with a content:// URI so the receiving
    // app can access the actual file data.
    //
    // This test DOCUMENTS the bug by verifying the INCORRECT behavior.
    // When fixed, update this test to assert the correct behavior.
    // =========================================================================

    @Test
    fun bug_longPressMediaEntryCopiesPlainTextInsteadOfUri() {
        // Simulate what ClipboardHistoryView does on long-press:
        // It always calls ClipData.newPlainText("CleverKeys", text)
        // even when the entry is a media entry (image, video, etc.)
        //
        // Note: We verify the ClipData object directly rather than reading back
        // from the system clipboard, because API 34 restricts clipboard read access
        // for background processes (the test orchestrator has no window focus).

        val mediaEntry = ClipboardEntry(
            content = "photo_abc123.jpg",
            timestamp = System.currentTimeMillis(),
            mimeType = "image/jpeg",
            thumbnailBlob = testThumbnail,
            mediaPath = "media/photo_abc123.jpg"
        )
        assertTrue("Entry should be media", mediaEntry.isMedia)
        assertTrue("Entry should be image", mediaEntry.isImage)

        // This is what the current code does (ClipboardHistoryView.kt line 673):
        val clip = ClipData.newPlainText("CleverKeys", mediaEntry.content)

        // Verify the bug: ClipData is plain text containing just the filename,
        // NOT a URI pointing to the actual image file
        assertEquals("Should have 1 item", 1, clip.itemCount)

        val item = clip.getItemAt(0)
        // BUG: The clip only has the filename as plain text
        assertEquals("photo_abc123.jpg", item.text.toString())
        // BUG: No URI is set — pasting into an app that supports images gets
        // the text "photo_abc123.jpg" instead of the actual image
        assertNull("BUG: URI should not be null for media entries, but it is", item.uri)

        // BUG: MIME type is text/plain, not image/jpeg
        val mimeType = clip.description.getMimeType(0)
        assertEquals("BUG: MIME type should be image/jpeg for image entries",
            "text/plain", mimeType)

        // When this bug is fixed, the corrected behavior should be:
        // val mediaUri = FileProvider.getUriForFile(context, AUTHORITY, File(mediaDir, entry.mediaPath))
        // val clip = ClipData.newUri(context.contentResolver, "CleverKeys", mediaUri)
        // Then: assertNotNull(item.uri) and assertEquals("image/jpeg", mimeType)
    }

    @Test
    fun longPress_textEntryCopiesCorrectly() {
        // Verify that plain text entries ARE copied correctly (not a bug).
        // We verify the ClipData object directly rather than reading back
        // from the system clipboard (API 34 clipboard read restriction).
        val textEntry = ClipboardEntry(
            content = "Hello world",
            timestamp = System.currentTimeMillis()
        )
        assertFalse("Entry should not be media", textEntry.isMedia)

        val clip = ClipData.newPlainText("CleverKeys", textEntry.content)
        assertEquals("Hello world", clip.getItemAt(0).text.toString())
        assertEquals("text/plain", clip.description.getMimeType(0))
    }

    // =========================================================================
    // Config toggle behavior — clipboard_text_only mode
    // =========================================================================

    @Test
    fun textOnlyMode_mediaEntryModelDetection() {
        // Verify ClipboardEntry.isMedia correctly identifies media vs text
        val textEntry = ClipboardEntry("Plain text", System.currentTimeMillis())
        assertFalse(textEntry.isMedia)
        assertEquals(ClipboardEntry.MIME_TEXT_PLAIN, textEntry.mimeType)

        val imageEntry = ClipboardEntry("img.jpg", System.currentTimeMillis(),
            mimeType = "image/jpeg", mediaPath = "media/img.jpg")
        assertTrue(imageEntry.isMedia)
        assertTrue(imageEntry.isImage)
        assertFalse(imageEntry.isVideo)

        val videoEntry = ClipboardEntry("vid.mp4", System.currentTimeMillis(),
            mimeType = "video/mp4", mediaPath = "media/vid.mp4")
        assertTrue(videoEntry.isMedia)
        assertFalse(videoEntry.isImage)
        assertTrue(videoEntry.isVideo)

        val pdfEntry = ClipboardEntry("doc.pdf", System.currentTimeMillis(),
            mimeType = "application/pdf", mediaPath = "media/doc.pdf")
        assertTrue(pdfEntry.isMedia)
        assertTrue(pdfEntry.isPdf)
        assertFalse(pdfEntry.isImage)
    }

    @Test
    fun textOnlyMode_filterRemovesMediaEntries() {
        // Simulate what loadDataAsync does when clipboard_text_only is true:
        // entries = entries.filter { !it.isMedia }
        val entries = listOf(
            ClipboardEntry("Text 1", System.currentTimeMillis()),
            ClipboardEntry("photo.jpg", System.currentTimeMillis(),
                mimeType = "image/jpeg", mediaPath = "media/photo.jpg"),
            ClipboardEntry("Text 2", System.currentTimeMillis()),
            ClipboardEntry("video.mp4", System.currentTimeMillis(),
                mimeType = "video/mp4", mediaPath = "media/video.mp4")
        )

        val textOnly = entries.filter { !it.isMedia }
        assertEquals("Should keep only text entries", 2, textOnly.size)
        assertEquals("Text 1", textOnly[0].content)
        assertEquals("Text 2", textOnly[1].content)
    }

    // =========================================================================
    // Config toggle behavior — pin/todo button visibility
    // =========================================================================

    @Test
    fun toggles_pinnedDisabledDoesNotAffectDatabase() {
        // Even when pinned tab is disabled in UI, the database still works
        // (toggle only affects view visibility, not data operations)
        db.pinEntry("Still works")
        assertEquals("Pinned entry should exist regardless of toggle", 1, db.getPinnedEntries().size)
    }

    @Test
    fun toggles_todoDisabledDoesNotAffectDatabase() {
        db.addTodoEntry("Still works")
        assertEquals("Todo entry should exist regardless of toggle", 1, db.getTodoEntries().size)
    }

    // =========================================================================
    // ClipboardEntry model edge cases
    // =========================================================================

    @Test
    fun model_hasThumbnailCorrect() {
        val withThumb = ClipboardEntry("img.jpg", System.currentTimeMillis(),
            mimeType = "image/jpeg", thumbnailBlob = testThumbnail)
        assertTrue(withThumb.hasThumbnail)

        val noThumb = ClipboardEntry("img.jpg", System.currentTimeMillis(),
            mimeType = "image/jpeg", thumbnailBlob = null)
        assertFalse(noThumb.hasThumbnail)
    }

    @Test
    fun model_defaultMimeTypeIsTextPlain() {
        val entry = ClipboardEntry("text", System.currentTimeMillis())
        assertEquals(ClipboardEntry.MIME_TEXT_PLAIN, entry.mimeType)
        assertFalse(entry.isMedia)
    }

    // =========================================================================
    // TodoEntry model — status helpers
    // =========================================================================

    @Test
    fun todoEntry_statusHelpers() {
        val active = TodoEntry(
            id = 1, content = "active", contentHash = "h", createdTimestamp = 0,
            addedTimestamp = 0, position = 1.0, status = TodoEntry.STATUS_ACTIVE
        )
        assertTrue(active.isActive)
        assertFalse(active.isPlanned)
        assertFalse(active.isCompleted)

        val planned = active.copy(status = TodoEntry.STATUS_PLANNED)
        assertFalse(planned.isActive)
        assertTrue(planned.isPlanned)
        assertFalse(planned.isCompleted)

        val completed = active.copy(status = TodoEntry.STATUS_COMPLETED)
        assertFalse(completed.isActive)
        assertFalse(completed.isPlanned)
        assertTrue(completed.isCompleted)
    }

    // =========================================================================
    // PinnedEntry model — REAL position for midpoint insertion
    // =========================================================================

    @Test
    fun pinnedEntry_positionIsMidpointInsertable() {
        db.pinEntry("First")   // position ~1.0
        db.pinEntry("Second")  // position ~2.0
        db.pinEntry("Third")   // position ~3.0

        val entries = db.getPinnedEntriesFull()
        assertEquals(3, entries.size)

        // Verify positions allow midpoint insertion
        val pos1 = entries[0].position
        val pos2 = entries[1].position
        val midpoint = (pos1 + pos2) / 2.0

        assertTrue("Midpoint $midpoint should be between pos1=$pos1 and pos2=$pos2",
            midpoint > pos1 && midpoint < pos2)
    }

    // =========================================================================
    // Export version — v4 format
    // =========================================================================

    @Test
    fun export_versionIs4() {
        val json = db.exportToJSON()!!
        assertEquals("Export version should be 4", 4, json.getInt("export_version"))
    }

    @Test
    fun export_hasExportDate() {
        val json = db.exportToJSON()!!
        assertTrue("Should have export_date", json.has("export_date"))
        val date = json.getString("export_date")
        // Date format: "yyyy-MM-dd HH:mm:ss"
        assertTrue("Date should contain year", date.contains("202"))
    }

    // =========================================================================
    // Import backward compatibility — v2 format
    // =========================================================================

    @Test
    fun import_v2FormatHandledGracefully() {
        // v2 format: no separate created_timestamp/pinned_timestamp, no position, no tags
        val v2Json = JSONObject().apply {
            put("export_version", 2)
            put("active_entries", JSONArray().apply {
                put(JSONObject().apply {
                    put("content", "v2 entry")
                    put("timestamp", System.currentTimeMillis())
                })
            })
            put("pinned_entries", JSONArray().apply {
                put(JSONObject().apply {
                    put("content", "v2 pin")
                    put("timestamp", System.currentTimeMillis())
                })
            })
            put("todo_entries", JSONArray().apply {
                put(JSONObject().apply {
                    put("content", "v2 todo")
                    put("timestamp", System.currentTimeMillis())
                })
            })
        }

        val result = db.importFromJSON(v2Json)
        assertEquals("Should import 1 active", 1, result[0])
        assertEquals("Should import 1 pinned", 1, result[1])
        assertEquals("Should import 1 todo", 1, result[2])

        // v2 imports should get default tags=[]
        val pinned = db.getPinnedEntriesFull()
        assertTrue("v2 import should have empty tags", pinned[0].tags.isEmpty())

        // v2 imports should get default status=active
        val todos = db.getTodoEntriesFull()
        assertEquals(TodoEntry.STATUS_ACTIVE, todos[0].status)
    }

    // =========================================================================
    // Media reference tracking across tables
    // =========================================================================

    @Test
    fun mediaRefTracking_allReferencedPathsCollected() {
        db.addMediaClipboardEntry("h1.jpg", futureExpiry, "image/jpeg", null, "media/h1.jpg", "sha1")
        db.pinEntry("p1.png", mimeType = "image/png", mediaPath = "media/p1.png")
        db.addTodoEntry("t1.mp4", mimeType = "video/mp4", mediaPath = "media/t1.mp4")

        val allPaths = db.getAllReferencedMediaPaths()
        assertEquals(setOf("media/h1.jpg", "media/p1.png", "media/t1.mp4"), allPaths)
    }

    @Test
    fun mediaRefTracking_textEntriesNotIncluded() {
        db.addClipboardEntry("Just text", futureExpiry)
        db.pinEntry("Text pin")

        val allPaths = db.getAllReferencedMediaPaths()
        assertTrue("Text entries should not have media paths", allPaths.isEmpty())
    }

    @Test
    fun mediaRefTracking_duplicatePathsDeduped() {
        // Same media_path pinned and in history (COPY semantics)
        db.addMediaClipboardEntry("img.jpg", futureExpiry, "image/jpeg", null, "media/shared.jpg", "sha_shared")
        db.pinEntry("img.jpg", mimeType = "image/jpeg", mediaPath = "media/shared.jpg")

        val allPaths = db.getAllReferencedMediaPaths()
        assertEquals("Duplicate paths should be deduped", 1, allPaths.size)
        assertTrue(allPaths.contains("media/shared.jpg"))
    }

    // =========================================================================
    // Export JSON structure completeness
    // =========================================================================

    @Test
    fun export_pinnedEntryIncludesAllFields() {
        db.pinEntry("Full export test")
        db.setPinnedEntryTags("Full export test", listOf("tag1"))

        val json = db.exportToJSON()!!
        val entry = json.getJSONArray("pinned_entries").getJSONObject(0)

        assertTrue("Should have content", entry.has("content"))
        assertTrue("Should have content_hash", entry.has("content_hash"))
        assertTrue("Should have created_timestamp", entry.has("created_timestamp"))
        assertTrue("Should have pinned_timestamp", entry.has("pinned_timestamp"))
        assertTrue("Should have position", entry.has("position"))
        assertTrue("Should have tags", entry.has("tags"))
        // v2 compat key
        assertTrue("Should have timestamp (v2 compat)", entry.has("timestamp"))

        assertEquals("Full export test", entry.getString("content"))
        val tags = entry.getString("tags")
        assertTrue("Tags should contain tag1", tags.contains("tag1"))
    }

    @Test
    fun export_todoEntryIncludesStatusAndTags() {
        db.addTodoEntry("Status export")
        db.setTodoEntryStatus("Status export", TodoEntry.STATUS_PLANNED)
        db.setTodoEntryTags("Status export", listOf("urgent"))

        val json = db.exportToJSON()!!
        val entry = json.getJSONArray("todo_entries").getJSONObject(0)

        assertTrue("Should have status", entry.has("status"))
        assertEquals("planned", entry.getString("status"))
        assertTrue("Tags should contain urgent", entry.getString("tags").contains("urgent"))
        assertTrue("Should have added_timestamp", entry.has("added_timestamp"))
        assertTrue("Should have position", entry.has("position"))
    }
}
