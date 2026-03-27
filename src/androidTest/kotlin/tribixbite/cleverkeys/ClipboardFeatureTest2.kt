package tribixbite.cleverkeys

import android.content.Context
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
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
 * Instrumented tests for clipboard features — batch 2.
 *
 * Covers gaps from batch 1:
 * - getFormattedText() span verification (TodoEntry, PinnedEntry, ClipboardEntry)
 * - toClipboardEntry() data conversion (PinnedEntry, TodoEntry)
 * - getRelativeTime() boundary edge cases (all 3 entry types)
 * - formatDate() consistency
 * - PinnedEntry/TodoEntry isMedia detection
 * - tagsToJson() for TodoEntry
 * - Export with empty database
 * - Import deduplication behavior
 * - StorageStats accuracy
 * - Edit entry dedup conflict and blank rejection at DB level
 * - Import with unknown extra fields (forward compat)
 * - Export/import with Unicode content
 */
@RunWith(AndroidJUnit4::class)
class ClipboardFeatureTest2 {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase

    private val futureExpiry = System.currentTimeMillis() + 3600_000L
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
    // TodoEntry.getFormattedText() — span verification
    // =========================================================================

    @Test
    fun formattedText_todoActiveNoPrefix() {
        val now = System.currentTimeMillis()
        val entry = TodoEntry(
            id = 1, content = "Buy milk", contentHash = "h",
            createdTimestamp = now, addedTimestamp = now,
            position = 1.0, status = TodoEntry.STATUS_ACTIVE
        )

        val spannable = entry.getFormattedText(context)
        val text = spannable.toString()

        // Active entries have no prefix — content starts at index 0
        assertTrue("Should start with content", text.startsWith("Buy milk"))
        assertTrue("Should contain time suffix", text.contains(" · "))

        // No strikethrough span for active entries
        val strikes = spannable.getSpans(0, spannable.length, StrikethroughSpan::class.java)
        assertEquals("Active entries should have no strikethrough", 0, strikes.size)

        // Should have ForegroundColorSpan on the time portion
        val colorSpans = spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java)
        assertEquals("Should have 1 color span for time", 1, colorSpans.size)
        // Color span starts after content
        val colorStart = spannable.getSpanStart(colorSpans[0])
        assertEquals("Color span should start at content length", "Buy milk".length, colorStart)
    }

    @Test
    fun formattedText_todoCompletedHasStrikethrough() {
        val now = System.currentTimeMillis()
        val entry = TodoEntry(
            id = 1, content = "Done task", contentHash = "h",
            createdTimestamp = now, addedTimestamp = now,
            position = 1.0, status = TodoEntry.STATUS_COMPLETED
        )

        val spannable = entry.getFormattedText(context)
        val text = spannable.toString()

        // Completed: "[done] Done task · Just now"
        assertTrue("Should have [done] prefix", text.startsWith("[done] "))

        // Strikethrough covers content only, NOT the prefix or time
        val strikes = spannable.getSpans(0, spannable.length, StrikethroughSpan::class.java)
        assertEquals("Completed should have 1 strikethrough", 1, strikes.size)

        val strikeStart = spannable.getSpanStart(strikes[0])
        val strikeEnd = spannable.getSpanEnd(strikes[0])
        // Prefix is "[done] " (7 chars), content is "Done task" (9 chars)
        assertEquals("Strikethrough starts after prefix", "[done] ".length, strikeStart)
        assertEquals("Strikethrough ends at content end",
            "[done] ".length + "Done task".length, strikeEnd)
    }

    @Test
    fun formattedText_todoPlannedHasPlanPrefix() {
        val now = System.currentTimeMillis()
        val entry = TodoEntry(
            id = 1, content = "Future item", contentHash = "h",
            createdTimestamp = now, addedTimestamp = now,
            position = 1.0, status = TodoEntry.STATUS_PLANNED
        )

        val spannable = entry.getFormattedText(context)
        val text = spannable.toString()

        assertTrue("Should have [plan] prefix", text.startsWith("[plan] "))
        // Planned items should NOT have strikethrough
        val strikes = spannable.getSpans(0, spannable.length, StrikethroughSpan::class.java)
        assertEquals("Planned should have no strikethrough", 0, strikes.size)
    }

    // =========================================================================
    // PinnedEntry.getFormattedText() — span verification
    // =========================================================================

    @Test
    fun formattedText_pinnedHasColorSpanOnTime() {
        val now = System.currentTimeMillis()
        val entry = PinnedEntry(
            id = 1, content = "Pinned note", contentHash = "h",
            createdTimestamp = now, pinnedTimestamp = now,
            position = 1.0
        )

        val spannable = entry.getFormattedText(context)
        val text = spannable.toString()

        assertTrue("Should start with content", text.startsWith("Pinned note"))
        assertTrue("Should contain time separator", text.contains(" · "))

        // No strikethrough (pinned entries never have it)
        val strikes = spannable.getSpans(0, spannable.length, StrikethroughSpan::class.java)
        assertEquals("Pinned should have no strikethrough", 0, strikes.size)

        // Color span on time portion
        val colorSpans = spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java)
        assertEquals(1, colorSpans.size)
        val colorStart = spannable.getSpanStart(colorSpans[0])
        assertEquals("Pinned note".length, colorStart)
    }

    // =========================================================================
    // ClipboardEntry.getFormattedText() — span verification
    // =========================================================================

    @Test
    fun formattedText_clipboardEntryHasColorSpanOnTime() {
        val now = System.currentTimeMillis()
        val entry = ClipboardEntry("Some text", now)

        val spannable = entry.getFormattedText(context)
        val text = spannable.toString()

        assertTrue(text.startsWith("Some text"))
        assertTrue(text.contains(" · Just now"))

        val colorSpans = spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java)
        assertEquals(1, colorSpans.size)
        assertEquals("Some text".length, spannable.getSpanStart(colorSpans[0]))
    }

    // =========================================================================
    // getRelativeTime() boundary edge cases
    // =========================================================================

    @Test
    fun relativeTime_justNow_under60Seconds() {
        val now = System.currentTimeMillis()
        // 0 seconds ago
        assertEquals("Just now", ClipboardEntry("t", now).getRelativeTime())
        // 30 seconds ago
        assertEquals("Just now", ClipboardEntry("t", now - 30_000).getRelativeTime())
        // 59 seconds ago
        assertEquals("Just now", ClipboardEntry("t", now - 59_000).getRelativeTime())
    }

    @Test
    fun relativeTime_minutesBoundary() {
        val now = System.currentTimeMillis()
        // Exactly 60 seconds → "1m ago"
        assertEquals("1m ago", ClipboardEntry("t", now - 60_000).getRelativeTime())
        // 90 seconds → "1m ago" (integer division: 90/60=1)
        assertEquals("1m ago", ClipboardEntry("t", now - 90_000).getRelativeTime())
        // 59 minutes ago
        assertEquals("59m ago", ClipboardEntry("t", now - 59 * 60_000).getRelativeTime())
    }

    @Test
    fun relativeTime_hoursBoundary() {
        val now = System.currentTimeMillis()
        // Exactly 60 minutes → "1h ago"
        assertEquals("1h ago", ClipboardEntry("t", now - 60 * 60_000).getRelativeTime())
        // 23 hours ago
        assertEquals("23h ago", ClipboardEntry("t", now - 23 * 3600_000L).getRelativeTime())
    }

    @Test
    fun relativeTime_yesterdayBoundary() {
        val now = System.currentTimeMillis()
        // Exactly 24 hours → "Yesterday"
        assertEquals("Yesterday", ClipboardEntry("t", now - 24 * 3600_000L).getRelativeTime())
        // 47 hours → still "Yesterday" (days=1)
        assertEquals("Yesterday", ClipboardEntry("t", now - 47 * 3600_000L).getRelativeTime())
    }

    @Test
    fun relativeTime_daysBoundary() {
        val now = System.currentTimeMillis()
        // 2 days → "2d ago"
        assertEquals("2d ago", ClipboardEntry("t", now - 2 * 86400_000L).getRelativeTime())
        // 6 days → "6d ago"
        assertEquals("6d ago", ClipboardEntry("t", now - 6 * 86400_000L).getRelativeTime())
    }

    @Test
    fun relativeTime_weekOrOlderUsesDate() {
        val now = System.currentTimeMillis()
        // 7 days → falls through to date format
        val result = ClipboardEntry("t", now - 7 * 86400_000L).getRelativeTime()
        // Should NOT be "7d ago" — should be formatted date like "Mar 20"
        assertFalse("7+ days should not use 'd ago' format", result.endsWith("d ago"))
        // Should contain a month abbreviation
        assertTrue("Should be date format", result.matches(Regex("\\w{3} \\d{1,2}")))
    }

    @Test
    fun relativeTime_todoEntryUsesAddedTimestamp() {
        val now = System.currentTimeMillis()
        val entry = TodoEntry(
            id = 1, content = "t", contentHash = "h",
            createdTimestamp = now - 86400_000L, // created 1 day ago (irrelevant)
            addedTimestamp = now - 120_000L,      // added 2 minutes ago (this one matters)
            position = 1.0
        )
        assertEquals("2m ago", entry.getRelativeTime())
    }

    @Test
    fun relativeTime_pinnedEntryUsesPinnedTimestamp() {
        val now = System.currentTimeMillis()
        val entry = PinnedEntry(
            id = 1, content = "t", contentHash = "h",
            createdTimestamp = now - 86400_000L,  // created 1 day ago (irrelevant)
            pinnedTimestamp = now - 3600_000L,     // pinned 1 hour ago (this one matters)
            position = 1.0
        )
        assertEquals("1h ago", entry.getRelativeTime())
    }

    // =========================================================================
    // toClipboardEntry() data conversion
    // =========================================================================

    @Test
    fun toClipboardEntry_pinnedPreservesFields() {
        val pinned = PinnedEntry(
            id = 99, content = "Important note", contentHash = "abc",
            createdTimestamp = 1000L, pinnedTimestamp = 2000L,
            position = 5.5, tags = listOf("work"),
            mimeType = "image/png", thumbnailBlob = testThumbnail,
            mediaPath = "media/photo.png"
        )

        val clip = pinned.toClipboardEntry()
        assertEquals("Content should transfer", "Important note", clip.content)
        assertEquals("Uses pinnedTimestamp as timestamp", 2000L, clip.timestamp)
        assertEquals("MIME type transfers", "image/png", clip.mimeType)
        assertNotNull("Thumbnail transfers", clip.thumbnailBlob)
        assertEquals("Media path transfers", "media/photo.png", clip.mediaPath)
        assertTrue("Should be media", clip.isMedia)
        assertTrue("Should be image", clip.isImage)
    }

    @Test
    fun toClipboardEntry_todoPreservesFields() {
        val todo = TodoEntry(
            id = 42, content = "Fix bug", contentHash = "xyz",
            createdTimestamp = 1000L, addedTimestamp = 3000L,
            position = 2.0, status = TodoEntry.STATUS_PLANNED,
            tags = listOf("dev"), mimeType = "video/mp4",
            mediaPath = "media/clip.mp4"
        )

        val clip = todo.toClipboardEntry()
        assertEquals("Fix bug", clip.content)
        assertEquals("Uses addedTimestamp as timestamp", 3000L, clip.timestamp)
        assertEquals("video/mp4", clip.mimeType)
        assertEquals("media/clip.mp4", clip.mediaPath)
        assertTrue(clip.isVideo)
        // Note: status and tags are NOT transferred (ClipboardEntry has neither)
    }

    @Test
    fun toClipboardEntry_textEntryDefaultMime() {
        val pinned = PinnedEntry(
            id = 1, content = "Plain text", contentHash = "h",
            createdTimestamp = 0, pinnedTimestamp = 0,
            position = 1.0
        )

        val clip = pinned.toClipboardEntry()
        assertEquals(ClipboardEntry.MIME_TEXT_PLAIN, clip.mimeType)
        assertFalse(clip.isMedia)
        assertNull(clip.thumbnailBlob)
        assertNull(clip.mediaPath)
    }

    // =========================================================================
    // PinnedEntry/TodoEntry isMedia detection
    // =========================================================================

    @Test
    fun pinnedEntry_isMediaDetection() {
        val text = PinnedEntry(
            id = 1, content = "text", contentHash = "h",
            createdTimestamp = 0, pinnedTimestamp = 0, position = 1.0
        )
        assertFalse("Text pinned entry should not be media", text.isMedia)

        val media = PinnedEntry(
            id = 2, content = "img.jpg", contentHash = "h2",
            createdTimestamp = 0, pinnedTimestamp = 0, position = 2.0,
            mimeType = "image/jpeg", mediaPath = "media/img.jpg"
        )
        assertTrue("Image pinned entry should be media", media.isMedia)
    }

    @Test
    fun todoEntry_isMediaDetection() {
        val text = TodoEntry(
            id = 1, content = "text", contentHash = "h",
            createdTimestamp = 0, addedTimestamp = 0, position = 1.0
        )
        assertFalse("Text todo should not be media", text.isMedia)

        val media = TodoEntry(
            id = 2, content = "vid.mp4", contentHash = "h2",
            createdTimestamp = 0, addedTimestamp = 0, position = 2.0,
            mimeType = "video/mp4", mediaPath = "media/vid.mp4"
        )
        assertTrue("Video todo should be media", media.isMedia)
    }

    // =========================================================================
    // TodoEntry.tagsToJson() roundtrip
    // =========================================================================

    @Test
    fun todoEntry_tagsToJsonRoundTrip() {
        val entry = TodoEntry(
            id = 1, content = "t", contentHash = "h",
            createdTimestamp = 0, addedTimestamp = 0,
            position = 1.0, tags = listOf("groceries", "weekend")
        )

        val json = entry.tagsToJson()
        val parsed = TodoEntry.tagsFromJson(json)
        assertEquals(listOf("groceries", "weekend"), parsed)
    }

    @Test
    fun todoEntry_emptyTagsToJson() {
        val entry = TodoEntry(
            id = 1, content = "t", contentHash = "h",
            createdTimestamp = 0, addedTimestamp = 0,
            position = 1.0, tags = emptyList()
        )

        val json = entry.tagsToJson()
        assertEquals("[]", json)
        assertEquals(emptyList<String>(), TodoEntry.tagsFromJson(json))
    }

    // =========================================================================
    // formatDate() consistency
    // =========================================================================

    @Test
    fun formatDate_allThreeTypesConsistent() {
        // Same timestamp should produce the same date string across all 3 types
        val ts = 1711500000000L // ~March 2024

        val clipDate = ClipboardEntry("t", ts).formatDate()
        val pinnedDate = PinnedEntry(
            id = 1, content = "t", contentHash = "h",
            createdTimestamp = ts, pinnedTimestamp = ts, position = 1.0
        ).formatDate()
        val todoDate = TodoEntry(
            id = 1, content = "t", contentHash = "h",
            createdTimestamp = ts, addedTimestamp = ts, position = 1.0
        ).formatDate()

        // All should use "MMM d" format — e.g., "Mar 27"
        assertEquals("Pinned should match Clipboard format", clipDate, pinnedDate)
        assertEquals("Todo should match Clipboard format", clipDate, todoDate)
        // Verify it's a valid date format
        assertTrue("Should be month-day format", clipDate.matches(Regex("\\w{3} \\d{1,2}")))
    }

    // =========================================================================
    // Export with empty database
    // =========================================================================

    @Test
    fun export_emptyDatabaseReturnsValidJson() {
        val json = db.exportToJSON()!!

        assertEquals(4, json.getInt("export_version"))
        assertTrue(json.has("export_date"))
        assertEquals(0, json.getJSONArray("active_entries").length())
        assertEquals(0, json.getJSONArray("pinned_entries").length())
        assertEquals(0, json.getJSONArray("todo_entries").length())
        assertEquals(0, json.getInt("total_active"))
        assertEquals(0, json.getInt("total_pinned"))
        assertEquals(0, json.getInt("total_todo"))
    }

    @Test
    fun export_emptyDatabaseTextOnlyNoMediaSkipped() {
        val json = db.exportToJSON(textOnly = true)!!
        // media_skipped key only added when mediaSkipped > 0 (ClipboardDatabase.kt:1214)
        // Empty DB has nothing to skip, so key is absent
        assertFalse("media_skipped should not be present when 0", json.has("media_skipped"))
    }

    // =========================================================================
    // Import deduplication — duplicate content rejected
    // =========================================================================

    @Test
    fun import_duplicateHistoryEntriesSkipped() {
        db.addClipboardEntry("Already here", futureExpiry)

        val importJson = JSONObject().apply {
            put("export_version", 4)
            put("active_entries", JSONArray().apply {
                put(JSONObject().apply {
                    put("content", "Already here")
                    put("timestamp", System.currentTimeMillis())
                })
                put(JSONObject().apply {
                    put("content", "New entry")
                    put("timestamp", System.currentTimeMillis())
                })
            })
            put("pinned_entries", JSONArray())
            put("todo_entries", JSONArray())
        }

        db.importFromJSON(importJson)

        val entries = db.getActiveClipboardEntries()
        // "Already here" should be deduped, "New entry" imported
        assertEquals(2, entries.size)
        val contents = entries.map { it.content }.toSet()
        assertTrue(contents.contains("Already here"))
        assertTrue(contents.contains("New entry"))
    }

    @Test
    fun import_duplicatePinnedEntriesSkipped() {
        db.pinEntry("Existing pin")

        val importJson = JSONObject().apply {
            put("export_version", 4)
            put("active_entries", JSONArray())
            put("pinned_entries", JSONArray().apply {
                put(JSONObject().apply {
                    put("content", "Existing pin")
                    put("timestamp", System.currentTimeMillis())
                    put("position", 1.0)
                })
                put(JSONObject().apply {
                    put("content", "New pin")
                    put("timestamp", System.currentTimeMillis())
                    put("position", 2.0)
                })
            })
            put("todo_entries", JSONArray())
        }

        db.importFromJSON(importJson)

        // Should have both but not a duplicate
        val pinned = db.getPinnedEntries()
        assertEquals(2, pinned.size)
    }

    // =========================================================================
    // StorageStats accuracy
    // =========================================================================

    @Test
    fun storageStats_emptyDatabase() {
        val stats = db.getStorageStats()
        assertEquals(0, stats.historyEntries)
        assertEquals(0, stats.pinnedEntries)
        assertEquals(0, stats.todoEntries)
        assertEquals(0, stats.totalEntries)
        assertEquals(0L, stats.totalSizeBytes)
    }

    @Test
    fun storageStats_countsAllTables() {
        db.addClipboardEntry("History 1", futureExpiry)
        db.addClipboardEntry("History 2", futureExpiry)
        db.pinEntry("Pinned 1")
        db.addTodoEntry("Todo 1")
        db.addTodoEntry("Todo 2")
        db.addTodoEntry("Todo 3")

        val stats = db.getStorageStats()
        assertEquals("Should count 2 history", 2, stats.historyEntries)
        assertEquals("Should count 1 pinned", 1, stats.pinnedEntries)
        assertEquals("Should count 3 todo", 3, stats.todoEntries)
        assertEquals("Total should be 6", 6, stats.totalEntries)
        assertTrue("Size should be > 0", stats.totalSizeBytes > 0)
    }

    @Test
    fun storageStats_sizeIncreasesWithContent() {
        db.addClipboardEntry("Small", futureExpiry)
        val smallStats = db.getStorageStats()

        db.addClipboardEntry("A".repeat(10000), futureExpiry)
        val largeStats = db.getStorageStats()

        assertTrue("Adding large entry should increase size",
            largeStats.historySizeBytes > smallStats.historySizeBytes)
    }

    @Test
    fun storageStats_expiredEntriesExcluded() {
        // Add an expired entry
        val pastExpiry = System.currentTimeMillis() - 1000L
        db.addClipboardEntry("Expired", pastExpiry)
        db.addClipboardEntry("Active", futureExpiry)

        val stats = db.getStorageStats()
        // getStorageStats only counts non-expired entries
        assertEquals("Expired should not be counted", 1, stats.historyEntries)
    }

    // =========================================================================
    // Edit entry — dedup conflict at DB level
    // =========================================================================

    @Test
    fun editEntry_duplicateConflictInHistory() {
        db.addClipboardEntry("Entry A", futureExpiry)
        db.addClipboardEntry("Entry B", futureExpiry)

        // Try to edit "Entry A" to "Entry B" — should conflict
        val result = db.updateHistoryEntryContent("Entry A", "Entry B")
        assertEquals(EditEntryResult.DuplicateConflict, result)

        // Original entries unchanged
        val entries = db.getActiveClipboardEntries()
        assertEquals(2, entries.size)
    }

    @Test
    fun editEntry_duplicateConflictInPinned() {
        db.pinEntry("Pin A")
        db.pinEntry("Pin B")

        val result = db.updatePinnedEntryContent("Pin A", "Pin B")
        assertEquals(EditEntryResult.DuplicateConflict, result)
    }

    @Test
    fun editEntry_duplicateConflictInTodo() {
        db.addTodoEntry("Todo A")
        db.addTodoEntry("Todo B")

        val result = db.updateTodoEntryContent("Todo A", "Todo B")
        assertEquals(EditEntryResult.DuplicateConflict, result)
    }

    @Test
    fun editEntry_blankContentRejected() {
        db.addClipboardEntry("Valid content", futureExpiry)

        val result = db.updateHistoryEntryContent("Valid content", "   ")
        assertEquals(EditEntryResult.InvalidContent, result)

        // Original unchanged
        assertEquals("Valid content", db.getActiveClipboardEntries()[0].content)
    }

    @Test
    fun editEntry_emptyStringRejected() {
        db.pinEntry("Has content")

        val result = db.updatePinnedEntryContent("Has content", "")
        assertEquals(EditEntryResult.InvalidContent, result)
    }

    @Test
    fun editEntry_sameContentIsNoop() {
        db.addClipboardEntry("No change", futureExpiry)

        // Edit to same content (after trim) should succeed silently
        val result = db.updateHistoryEntryContent("No change", "No change")
        assertEquals(EditEntryResult.Success, result)
    }

    @Test
    fun editEntry_whitespaceTrimmingApplied() {
        db.addClipboardEntry("Trim me", futureExpiry)

        // Content with leading/trailing spaces should be trimmed
        val result = db.updateHistoryEntryContent("Trim me", "  Trimmed  ")
        assertEquals(EditEntryResult.Success, result)

        val entries = db.getActiveClipboardEntries()
        assertEquals("Trimmed", entries[0].content)
    }

    @Test
    fun editEntry_hashUpdatedAfterEdit() {
        db.addClipboardEntry("Original", futureExpiry)
        val originalHash = db.getActiveClipboardEntries()[0].let {
            // Read content_hash from raw query since ClipboardEntry doesn't expose it
            val cursor = db.readableDatabase.rawQuery(
                "SELECT content_hash FROM clipboard_entries WHERE content = ?",
                arrayOf("Original")
            )
            cursor.moveToFirst()
            val hash = cursor.getString(0)
            cursor.close()
            hash
        }

        db.updateHistoryEntryContent("Original", "Modified")

        val newHash = db.readableDatabase.rawQuery(
            "SELECT content_hash FROM clipboard_entries WHERE content = ?",
            arrayOf("Modified")
        ).let { cursor ->
            cursor.moveToFirst()
            val hash = cursor.getString(0)
            cursor.close()
            hash
        }

        assertNotEquals("Hash should change after edit", originalHash, newHash)
    }

    // =========================================================================
    // Edit entry — preserves other fields (timestamp, tags, status, position)
    // =========================================================================

    @Test
    fun editEntry_pinnedPreservesTagsAndPosition() {
        db.pinEntry("Edit tags test")
        db.setPinnedEntryTags("Edit tags test", listOf("important"))

        val before = db.getPinnedEntriesFull()[0]
        val originalPosition = before.position
        val originalTags = before.tags

        db.updatePinnedEntryContent("Edit tags test", "Edited content")

        val after = db.getPinnedEntriesFull()[0]
        assertEquals("Edited content", after.content)
        assertEquals("Tags should survive edit", originalTags, after.tags)
        assertEquals("Position should survive edit", originalPosition, after.position, 0.001)
    }

    @Test
    fun editEntry_todoPreservesStatusAndTags() {
        db.addTodoEntry("Edit status test")
        db.setTodoEntryStatus("Edit status test", TodoEntry.STATUS_COMPLETED)
        db.setTodoEntryTags("Edit status test", listOf("done"))

        db.updateTodoEntryContent("Edit status test", "Edited todo")

        val after = db.getTodoEntriesFull()[0]
        assertEquals("Edited todo", after.content)
        assertEquals("Status should survive edit", TodoEntry.STATUS_COMPLETED, after.status)
        assertEquals("Tags should survive edit", listOf("done"), after.tags)
    }

    // =========================================================================
    // Import with Unicode content
    // =========================================================================

    @Test
    fun importExport_unicodeContentPreserved() {
        val unicodeContent = "Hello 世界 🌍 café résumé Ñoño"
        db.addClipboardEntry(unicodeContent, futureExpiry)
        db.pinEntry("日本語テスト")
        db.addTodoEntry("Ελληνικά τεστ")

        val exported = db.exportToJSON()!!
        clearAllTables()
        db.importFromJSON(exported)

        val history = db.getActiveClipboardEntries()
        assertEquals(unicodeContent, history[0].content)

        val pinned = db.getPinnedEntries()
        assertEquals("日本語テスト", pinned[0].content)

        val todo = db.getTodoEntries()
        assertEquals("Ελληνικά τεστ", todo[0].content)
    }

    @Test
    fun importExport_unicodeTagsPreserved() {
        db.pinEntry("Tagged")
        db.setPinnedEntryTags("Tagged", listOf("工作", "重要", "café"))

        val exported = db.exportToJSON()!!
        clearAllTables()
        db.importFromJSON(exported)

        val pinned = db.getPinnedEntriesFull()
        assertEquals(listOf("工作", "重要", "café"), pinned[0].tags)
    }

    // =========================================================================
    // Import with unknown/extra fields (forward compatibility)
    // =========================================================================

    @Test
    fun import_extraFieldsIgnoredGracefully() {
        val jsonWithExtras = JSONObject().apply {
            put("export_version", 4)
            put("unknown_field", "should be ignored")
            put("active_entries", JSONArray().apply {
                put(JSONObject().apply {
                    put("content", "Has extras")
                    put("timestamp", System.currentTimeMillis())
                    put("future_field", 42)
                    put("another_unknown", "ignored")
                })
            })
            put("pinned_entries", JSONArray())
            put("todo_entries", JSONArray())
        }

        // Should not throw
        val result = db.importFromJSON(jsonWithExtras)
        assertEquals("Should import 1 active", 1, result[0])

        val entries = db.getActiveClipboardEntries()
        assertEquals("Has extras", entries[0].content)
    }

    // =========================================================================
    // Export counts match actual data
    // =========================================================================

    @Test
    fun export_totalCountsMatchArrayLengths() {
        db.addClipboardEntry("H1", futureExpiry)
        db.addClipboardEntry("H2", futureExpiry)
        db.pinEntry("P1")
        db.addTodoEntry("T1")
        db.addTodoEntry("T2")
        db.addTodoEntry("T3")

        val json = db.exportToJSON()!!

        assertEquals(json.getJSONArray("active_entries").length(), json.getInt("total_active"))
        assertEquals(json.getJSONArray("pinned_entries").length(), json.getInt("total_pinned"))
        assertEquals(json.getJSONArray("todo_entries").length(), json.getInt("total_todo"))

        assertEquals(2, json.getInt("total_active"))
        assertEquals(1, json.getInt("total_pinned"))
        assertEquals(3, json.getInt("total_todo"))
    }

    // =========================================================================
    // Database entry ordering
    // =========================================================================

    @Test
    fun historyEntries_orderedByTimestampDesc() {
        // Add with explicit small delays to ensure different timestamps
        db.addClipboardEntry("First", futureExpiry)
        Thread.sleep(10)
        db.addClipboardEntry("Second", futureExpiry)
        Thread.sleep(10)
        db.addClipboardEntry("Third", futureExpiry)

        val entries = db.getActiveClipboardEntries()
        // Most recent first
        assertEquals("Third", entries[0].content)
        assertEquals("Second", entries[1].content)
        assertEquals("First", entries[2].content)
    }

    @Test
    fun pinnedEntries_orderedByPositionAsc() {
        db.pinEntry("A")  // position ~1.0
        db.pinEntry("B")  // position ~2.0
        db.pinEntry("C")  // position ~3.0

        val entries = db.getPinnedEntriesFull()
        assertEquals("A", entries[0].content)
        assertEquals("B", entries[1].content)
        assertEquals("C", entries[2].content)
        assertTrue(entries[0].position < entries[1].position)
        assertTrue(entries[1].position < entries[2].position)
    }

    @Test
    fun todoEntries_orderedByPositionAsc() {
        db.addTodoEntry("First")
        db.addTodoEntry("Second")
        db.addTodoEntry("Third")

        val entries = db.getTodoEntriesFull()
        assertEquals("First", entries[0].content)
        assertEquals("Second", entries[1].content)
        assertEquals("Third", entries[2].content)
    }

    // =========================================================================
    // Large content handling
    // =========================================================================

    @Test
    fun largeContent_storedAndRetrievedIntact() {
        // 50KB of content
        val largeContent = "X".repeat(50_000)
        db.addClipboardEntry(largeContent, futureExpiry)

        val entries = db.getActiveClipboardEntries()
        assertEquals(1, entries.size)
        assertEquals(50_000, entries[0].content.length)
        assertEquals(largeContent, entries[0].content)
    }

    @Test
    fun largeContent_pinnedAndRetrievedIntact() {
        val largeContent = "P".repeat(50_000)
        db.pinEntry(largeContent)

        val entries = db.getPinnedEntries()
        assertEquals(1, entries.size)
        assertEquals(largeContent, entries[0].content)
    }
}
