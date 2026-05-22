---
title: Clipboard History - Technical Specification
user_guide: ../../clipboard/clipboard-history.md
status: implemented
version: v1.3.0
schema_version: v4
---

# Clipboard History Technical Specification

## Overview

The clipboard history system maintains a persistent store of copied content — text, images, videos, PDFs, and other media — with support for independent pinned and todo tables, search, auto-expiry, pagination, media thumbnails, and privacy protection.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| ClipboardDatabase | `ClipboardDatabase.kt` | SQLite storage, CRUD, migration, export/import |
| ClipboardHistoryService | `ClipboardHistoryService.kt` | System clipboard listener, IO dispatch, media capture |
| ClipboardHistoryView | `ClipboardHistoryView.kt` | UI panel, adapter, thumbnail rendering, paste |
| ClipboardMediaManager | `ClipboardMediaManager.kt` | Media file storage, thumbnails, cleanup |
| ClipboardManager | `ClipboardManager.kt` | Clipboard pane lifecycle, tab wiring, pagination |
| ClipboardEntry | `ClipboardEntry.kt` | Data model for clipboard items |
| PinnedEntry | `PinnedEntry.kt` | Data model for pinned items (COPY semantics) |
| TodoEntry | `TodoEntry.kt` | Data model for todo items (COPY semantics) |
| Config | `Config.kt` | Clipboard preferences and toggles |

## Data Models

### ClipboardEntry (v4)

```kotlin
// ClipboardEntry.kt
class ClipboardEntry(
    @JvmField val content: String,
    @JvmField val timestamp: Long,
    @JvmField val mimeType: String = MIME_TEXT_PLAIN,
    @JvmField val thumbnailBlob: ByteArray? = null,
    @JvmField val mediaPath: String? = null
) {
    val isMedia: Boolean get() = mimeType != MIME_TEXT_PLAIN
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isPdf: Boolean get() = mimeType == "application/pdf"
    val hasThumbnail: Boolean get() = thumbnailBlob != null

    companion object {
        const val MIME_TEXT_PLAIN = "text/plain"
    }
}
```

### PinnedEntry (v4)

```kotlin
// PinnedEntry.kt — independent table, COPY semantics from history
data class PinnedEntry(
    val content: String,
    val contentHash: String,
    val createdTimestamp: Long,
    val pinnedTimestamp: Long,
    val position: Double,       // REAL for drag-and-drop midpoint insertion
    val tags: List<String>,     // JSON array in TEXT column
    val mimeType: String = ClipboardEntry.MIME_TEXT_PLAIN,
    val thumbnailBlob: ByteArray? = null,
    val mediaPath: String? = null
)
```

### TodoEntry (v4)

```kotlin
// TodoEntry.kt — independent table, COPY semantics from history
data class TodoEntry(
    val content: String,
    val contentHash: String,
    val createdTimestamp: Long,
    val addedTimestamp: Long,
    val position: Double,
    val status: String,         // 'active' | 'planned' | 'completed'
    val tags: List<String>,
    val mimeType: String = ClipboardEntry.MIME_TEXT_PLAIN,
    val thumbnailBlob: ByteArray? = null,
    val mediaPath: String? = null
)
```

## Storage Schema (v4)

### clipboard_entries — history only

```sql
CREATE TABLE clipboard_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    content TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    expiry_timestamp INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    mime_type TEXT DEFAULT 'text/plain',     -- v4
    thumbnail_blob BLOB,                     -- v4: ≤10KB WebP thumbnail
    media_path TEXT                           -- v4: internal file path
);
CREATE INDEX idx_content_hash ON clipboard_entries(content_hash);
CREATE INDEX idx_timestamp ON clipboard_entries(timestamp DESC);
CREATE INDEX idx_expiry ON clipboard_entries(expiry_timestamp);
```

### pinned_entries — independent from history

```sql
CREATE TABLE pinned_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    created_timestamp INTEGER NOT NULL,
    pinned_timestamp INTEGER NOT NULL,
    position REAL NOT NULL,                  -- drag-and-drop midpoint insertion
    tags TEXT DEFAULT '[]',                  -- JSON array
    mime_type TEXT DEFAULT 'text/plain',     -- v4
    thumbnail_blob BLOB,                     -- v4
    media_path TEXT                           -- v4
);
CREATE INDEX idx_pinned_hash ON pinned_entries(content_hash);
CREATE INDEX idx_pinned_pos ON pinned_entries(position ASC);
```

### todo_entries — independent from history

```sql
CREATE TABLE todo_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    created_timestamp INTEGER NOT NULL,
    added_timestamp INTEGER NOT NULL,
    position REAL NOT NULL,
    status TEXT DEFAULT 'active',            -- 'active' | 'planned' | 'completed'
    tags TEXT DEFAULT '[]',
    mime_type TEXT DEFAULT 'text/plain',     -- v4
    thumbnail_blob BLOB,                     -- v4
    media_path TEXT                           -- v4
);
CREATE INDEX idx_todo_hash ON todo_entries(content_hash);
CREATE INDEX idx_todo_pos ON todo_entries(position ASC);
CREATE INDEX idx_todo_status ON todo_entries(status);
```

### Migration Chain

| Version | Change | Strategy |
|---------|--------|----------|
| v1→v2 | Add `is_todo` column | ALTER TABLE ADD COLUMN |
| v2→v3 | Independent pinned/todo tables | CREATE-COPY-DROP-RENAME (pre-API 34 compat) |
| v3→v4 | Media columns (mime_type, thumbnail_blob, media_path) | ALTER TABLE ADD COLUMN × 9 |

All migrations are non-destructive. v2→v3 uses COPY semantics: pinned/todo entries are copied to new tables AND remain in history.

## Clipboard Monitoring

### System Clipboard Listener

```kotlin
// ClipboardHistoryService.kt
private fun addCurrentClip() {
    // 1. Check clipboard_history_enabled
    // 2. Check password manager exclusion (foreground app detection)
    // 3. Check Android 13+ IS_SENSITIVE flag
    // 4. For each ClipData item:
    //    - If item.text != null: addClip(text) on main thread
    //    - If item.uri != null: dispatch to Dispatchers.IO → processClipUri(uri)
}
```

### Content URI Processing (IO thread)

```kotlin
// ClipboardHistoryService.kt — runs on Dispatchers.IO
private fun processClipUri(uri: Uri) {
    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
    if (mimeType.startsWith("text/")) {
        // Stream text via ContentResolver (bypasses Binder ~1MB limit)
        val text = readTextFromUri(uri)
        if (text != null) addClip(text)
    } else {
        // Media: check text-only and media-enabled toggles
        if (cfg.clipboard_text_only || !cfg.clipboard_media_enabled) return
        val result = mediaManager.saveMedia(uri, mimeType, maxMediaBytes)
        addMediaClip(result.displayName, result.mimeType, result.thumbnailBlob, ...)
    }
}
```

### Media Settings Gating

| Check Point | Setting | Behavior When Disabled |
|-------------|---------|----------------------|
| Capture | `clipboard_media_enabled` | Media URIs silently dropped |
| Capture | `clipboard_text_only` | Media URIs silently dropped |
| Display | `clipboard_text_only` | Media entries filtered from all tabs |
| Paste | (always allowed) | Falls back to text if media paste unsupported |

## Media Storage Architecture

### ClipboardMediaManager

```
filesDir/
└── clipboard_media/
    ├── 000/          ← partition 0 (IDs 0-999)
    │   ├── a1b2c3.jpg
    │   └── d4e5f6.webp
    ├── 001/          ← partition 1 (IDs 1000-1999)
    └── ...
```

- **Partitioned storage**: `{id / 1000}/{sha256_hash}.{ext}` — avoids >1000 files per directory
- **Max media file**: configurable `clipboard_max_media_size_mb` (default 10MB, max 50MB)
- **External URIs copied immediately**: content:// URIs expire when clipboard changes

### Thumbnail Generation by MIME Type

| MIME Type | Method | Output |
|-----------|--------|--------|
| `image/*` (static) | `BitmapFactory` + `inSampleSize` | 80×80 WebP ≤10KB |
| `image/gif`, `image/webp` (animated) | `BitmapFactory` first frame | 80×80 WebP ≤10KB |
| `video/*` | `MediaMetadataRetriever.getFrameAtTime(0)` | 80×80 WebP ≤10KB |
| `application/pdf` | `PdfRenderer` first page | 80×80 WebP ≤10KB |
| Unknown | No thumbnail | UI shows MIME-type icon |

- Thumbnails ≤10KB stored as BLOB in SQLite (CursorWindow is 2MB; 10KB × 200 rows = safe)
- Animated detection: RIFF header VP8X chunk for WebP, NETSCAPE2.0 block for GIF

### Orphan Cleanup

`cleanupOrphans()` runs on service startup and after import:
1. Query all `media_path` values from all 3 tables
2. Scan `clipboard_media/` directories
3. Delete files not referenced by any table
4. Remove empty partition directories

## Tab System

| Tab | Enum | Data Source | Query |
|-----|------|-------------|-------|
| History | `ClipboardTab.HISTORY` | `clipboard_entries` | `clearExpiredAndGetHistory()` |
| Pinned | `ClipboardTab.PINNED` | `pinned_entries` | `getPinnedEntries()` ORDER BY position |
| Todos | `ClipboardTab.TODOS` | `todo_entries` | `getTodoEntries()` ORDER BY position |

### Pin/Todo Semantics (COPY, not MOVE)

- **Pin from History**: COPIES content to `pinned_entries`. History entry stays (subject to expiry).
- **Todo from History**: COPIES content to `todo_entries`. History entry stays.
- Re-copying same text does NOT affect pinned/todo copies.
- Deleting from history does NOT affect pinned/todo copies.
- Media fields (mimeType, thumbnailBlob, mediaPath) are carried on pin/todo.

### Tab Data Loading

```kotlin
// ClipboardHistoryView.kt — async off UI thread
private fun loadDataAsync() {
    loadJob?.cancel()
    loadJob = viewScope?.launch {
        val entries = withContext(Dispatchers.IO) {
            when (currentTab) {
                ClipboardTab.HISTORY -> service?.clearExpiredAndGetHistory() ?: emptyList()
                ClipboardTab.PINNED -> database.getPinnedEntries()
                ClipboardTab.TODOS -> database.getTodoEntries()
            }
        }
        // Filter out media entries when text-only mode active
        if (Config.globalConfig().clipboard_text_only) {
            entries = entries.filter { !it.isMedia }
        }
        history = entries
        applyFilter()
    }
}
```

## Pagination

- 100 items per page (`ITEMS_PER_PAGE = 100`)
- Search filters ALL items before pagination
- Pagination bar shows `currentPage / totalPages` with ◀ ▶ navigation

## View Rendering

### Adapter (ClipboardEntriesAdapter)

```kotlin
override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
    val entry = paginatedHistory[pos]
    if (entry.isMedia) {
        // Show thumbnail container (48×48dp)
        if (entry.hasThumbnail) {
            // Decode BLOB to bitmap — no file I/O on UI thread
            val bitmap = BitmapFactory.decodeByteArray(entry.thumbnailBlob, 0, ...)
            thumbnailView.setImageBitmap(bitmap)
        } else {
            // Fallback: MIME-type icon (ic_media_image, ic_media_video, ic_media_pdf, ic_media_file)
            thumbnailView.setImageResource(getMimeTypeIcon(entry.mimeType))
        }
        // Animated badge for GIF/animated WebP
        playBadge.visibility = if (isAnimated) VISIBLE else GONE
    }
    // Text entry: show formatted text with relative timestamp ("2h ago")
}
```

### Paste

```kotlin
fun paste_entry(pos: Int) {
    val entry = paginatedHistory[pos]
    if (entry.isMedia && entry.mediaPath != null) {
        // Media paste via commitContent (API 25+) — uses FileProvider URI
        val success = ClipboardHistoryService.pasteMedia(entry.mimeType, entry.mediaPath)
        if (!success) Toast("Cannot paste media here")
    } else {
        ClipboardHistoryService.paste(entry.content)  // Text paste
    }
}
```

## Export/Import (v4 dual format)

### JSON Export (text-only)

```json
{
    "export_version": 4,
    "export_date": "2026-03-26 12:00:00",
    "active_entries": [
        { "content": "text here", "timestamp": 1711468800000, "expiry_timestamp": ...,
          "mime_type": "text/plain" }
    ],
    "pinned_entries": [
        { "content": "pinned text", "content_hash": "...", "created_timestamp": ...,
          "pinned_timestamp": ..., "position": 1.0, "tags": "[]", "mime_type": "text/plain" }
    ],
    "todo_entries": [
        { "content": "todo text", "content_hash": "...", "created_timestamp": ...,
          "added_timestamp": ..., "position": 1.0, "status": "active", "tags": "[]",
          "mime_type": "text/plain" }
    ]
}
```

### ZIP Export (full backup with media)

```
clipboard_backup.zip
├── clipboard_data.json    ← Same JSON structure with media metadata
└── clipboard_media/       ← Raw media files by content hash
    ├── a1b2c3.jpg
    └── d4e5f6.mp4
```

### Import Compatibility

| Source Format | Target Schema | Behavior |
|--------------|---------------|----------|
| v2 JSON | v4 | Entries get `mime_type='text/plain'`, no media fields |
| v3 JSON | v4 | Entries get `mime_type='text/plain'`, no media fields |
| v4 JSON | v4 | Text entries imported, media entries skipped (no files) |
| v4 ZIP | v4 | Media extracted, thumbnails regenerated, full restore |

### Full Backup ZIP

Clipboard data is also included in the new one-click **Full Backup** ZIP (`cleverkeys_full_backup_<date>.zip`), alongside settings and dictionaries. The clipboard section uses the same `clipboard_data.json` + `clipboard_media/` layout described above and the same `ClipboardDatabase.exportToJSON` / `importFromJSON` round-trip — duplicates are skipped on import. See the [Backup & Restore user guide](../../troubleshooting/backup-restore.md) for the ZIP layout, manifest format, and forward-compat rules.

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| Enable History | `clipboard_history_enabled` | true | bool |
| History Limit | `clipboard_history_limit` | 50 | count or size-based |
| History Duration | `clipboard_history_duration` | -1 (never) | minutes, -1=never |
| Max Item Size (text) | `clipboard_max_item_size_kb` | 256 | 64-1024 KB |
| Text-Only Mode | `clipboard_text_only` | false | bool — hides all media |
| Media Clipboard | `clipboard_media_enabled` | true | bool — enables media capture |
| Max Media Size | `clipboard_max_media_size_mb` | 10 | 1-50 MB |
| Pinned Tab | `clipboard_pinned_enabled` | true | bool — show/hide tab |
| Todo Tab | `clipboard_todo_enabled` | true | bool — show/hide tab |
| Exclude PWMs | `clipboard_exclude_password_managers` | true | bool |
| Sensitive Flag | `clipboard_respect_sensitive_flag` | true | bool (Android 13+) |
| Pane Height | `clipboard_pane_height_percent` | 45 | 20-80% |

## Privacy and access control

Privacy controls — password-manager exclusion, the Android 13+ `IS_SENSITIVE` flag, and media gating — are documented in [Clipboard Privacy](privacy-spec.md). Summary:

- Clipboard media excluded from Android Auto Backup (`backup_rules.xml`, `data_extraction_rules.xml`)
- Password manager exclusion via foreground app detection
- Android 13+ IS_SENSITIVE flag respected
- No INTERNET permission — all processing is local
- Media files stored in app-private `filesDir` (not accessible to other apps)

See [Clipboard Privacy](privacy-spec.md) for the full exclusion list, foreground-app detection code path, and media-gating logic.

## Related Specifications

- [Clipboard Privacy](privacy-spec.md) — password manager exclusion, IS_SENSITIVE handling, media gating
- [Text Selection](text-selection-spec.md) - Selection integration
