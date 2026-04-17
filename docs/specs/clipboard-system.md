# Clipboard System Specification

## Feature Overview
**Feature Name**: Clipboard System (History + Pinned + Todos + Media)
**Priority**: P0
**Status**: Complete (v4)
**Target Version**: v1.3.0

### Summary
Three-tab clipboard pane inside the IME with FTS4-powered search, tag-based filtering, per-entry todo workflow, inline editing, and media (images/video/PDF) support via content URI streaming.

### Motivation
Replace the legacy `clipboard_history` single-list with a triage-capable system:
- **History**: ephemeral, auto-captured, capped
- **Pinned**: curated reference copies, tagged
- **Todos**: actionable copies with status lifecycle (active / planned / completed)

Additionally, support non-text clipboard content (images, videos, GIFs, PDFs) and bypass Android's ~1MB Binder IPC limit for large text via `ContentResolver.openInputStream()`.

## Requirements

### Functional Requirements

1. **FR-1**: Capture system clipboard changes via `ClipboardManager.OnPrimaryClipChangedListener`
2. **FR-2**: Support text AND media content (`image/*`, `video/*`, `application/pdf`, `application/*`)
3. **FR-3**: Three tabs (HISTORY, PINNED, TODOS) with independent storage
4. **FR-4**: Per-entry actions: paste, pin, add-to-todo, edit, delete, tag, toggle status
5. **FR-5**: FTS4 full-text search (HISTORY) + regex mode via `.*` toggle
6. **FR-6**: Tag-based filtering (Any/All match modes) on Pinned + Todos
7. **FR-7**: Date-range filter on all tabs (Before/After)
8. **FR-8**: Status filter on Todos tab (Active/Planned/Completed)
9. **FR-9**: 100-item pagination when `filteredHistory.size > 100`
10. **FR-10**: Inline edit mode per entry with save/cancel/delete
11. **FR-11**: Inline tag panel for Pinned/Todos entries
12. **FR-12**: Export/import — dual format: JSON (text-only, lightweight) + ZIP (full backup with media)
13. **FR-13**: Paste media via `InputConnectionCompat.commitContent()` + FileProvider
14. **FR-14**: Cross-tab action feedback via tab icon pulse animation
15. **FR-15**: Password manager exclusion + media privacy gating

### Non-Functional Requirements

1. **NFR-1**: No ANR — all clipboard URI reads dispatched to `Dispatchers.IO`
2. **NFR-2**: Thumbnails stay under 10KB (SQLite CursorWindow 2MB safety)
3. **NFR-3**: Media files stored in app-private `filesDir/clipboard_media/{partition}/`, excluded from Auto Backup
4. **NFR-4**: Inline panels (no AlertDialog) to avoid IME window conflicts
5. **NFR-5**: Toast-free feedback inside IME (toasts render behind keyboard)
6. **NFR-6**: Operations reloading data must preserve page + expand state

### User Stories

- **As a** keyboard user, **I want** my recent copies in an accessible pane **so that** I don't leave my current app to retrieve them
- **As a** note-taker, **I want** to pin important snippets with tags **so that** I can recall them by topic
- **As a** task-tracker, **I want** to add clipboard content to a todo list **so that** my copies become actionable
- **As a** visual user, **I want** to copy images and paste them from my clipboard history **so that** media isn't silently dropped

## Technical Design

### Architecture

```
┌────────────────────────────────────────────────────────────┐
│                   ClipboardManager                         │
│            (pane-level state: search, tag mode)            │
├────────────────────────────────────────────────────────────┤
│         ClipboardHistoryView (NonScrollListView)           │
│     • ClipboardEntriesAdapter (getView, edit mode)         │
│     • onItemAddedToTab callback → manager pulse            │
├────────────────────────────────────────────────────────────┤
│            ClipboardHistoryService (singleton)             │
│     • addCurrentClip (main thread metadata + IO stream)    │
│     • pinEntry / unpinEntry → Boolean (dedup)              │
│     • addToTodo / setTodoStatus / setTodoTags              │
│     • setPinnedTags / getAllPinnedTags                     │
├────────────────────────────────────────────────────────────┤
│               ClipboardMediaManager                        │
│     • saveMedia (ContentResolver + partitioned storage)    │
│     • generateThumbnail (BitmapFactory/MMR/PdfRenderer)    │
│     • cleanupOrphans, deleteMedia                          │
├────────────────────────────────────────────────────────────┤
│                 ClipboardDatabase (V4)                     │
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────┐  │
│  │ clipboard_     │  │ pinned_entries │  │ todo_entries │  │
│  │ history (FTS4) │  │ + tags TEXT    │  │ + tags TEXT  │  │
│  │ + mime_type    │  │ + mime_type    │  │ + status     │  │
│  │ + thumbnail    │  │ + thumbnail    │  │ + mime_type  │  │
│  │ + media_path   │  │ + media_path   │  │ + thumbnail  │  │
│  └────────────────┘  └────────────────┘  └──────────────┘  │
└────────────────────────────────────────────────────────────┘
```

### Component Breakdown

1. **`ClipboardManager.kt`**: Pane-level state (search mode, tag mode, current tab), tab switching, tab icon pulse animation, filter dialog orchestration.
2. **`ClipboardHistoryView.kt`**: Entry list (`BaseAdapter`), inline edit mode, expand/collapse state (keyed by timestamp), pagination, per-entry actions.
3. **`ClipboardHistoryService.kt`**: Singleton data layer. Handles `OnPrimaryClipChangedListener`, dispatches URI reads to IO, exposes CRUD with Boolean dedup return values.
4. **`ClipboardDatabase.kt`**: SQLite with FTS4 for history. V4 schema adds `mime_type`, `thumbnail_blob`, `media_path` to all three tables. Tags stored as JSON array in `tags` TEXT column.
5. **`ClipboardMediaManager.kt`**: File I/O for media. MIME-aware thumbnail generation. Partitioned storage (`{id/1000}`). Orphan cleanup.
6. **`ClipboardTagDialog.kt`** (`ClipboardTagPanel` object): Inline programmatic tag panel — chips, suggestions, new-tag EditText. FlowLayout inner class for chip wrapping.
7. **`ClipboardEntry.kt`** / **`TodoEntry.kt`**: Data classes with mime/thumbnail/media fields plus derived `isMedia`/`isImage`/`isVideo`/`isPdf` helpers.
8. **`ClipboardSettingsActivity.kt`**: User toggles — password manager exclusion, media enabled, text-only, max-media-size, storage stats.

### Data Structures

```kotlin
data class ClipboardEntry(
    val content: String,                     // text or filename for media
    val timestamp: Long,
    val mimeType: String = "text/plain",
    val thumbnailBlob: ByteArray? = null,    // ≤10KB for media
    val mediaPath: String? = null,           // filesDir-relative
    val tags: List<String> = emptyList(),    // parsed from JSON column
    val todoStatus: String? = null           // only set for TODOS tab entries
) {
    val isMedia: Boolean get() = mimeType != "text/plain"
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isPdf:   Boolean get() = mimeType == "application/pdf"
}

enum class ClipboardTab { HISTORY, PINNED, TODOS }

object TodoEntry {
    const val STATUS_ACTIVE    = "active"
    const val STATUS_PLANNED   = "planned"
    const val STATUS_COMPLETED = "completed"
}
```

### API / Interface Design

```kotlin
// ClipboardHistoryService — key methods
fun pinEntry(clip: String, timestamp: Long, mimeType: String = "text/plain",
             thumbnailBlob: ByteArray? = null, mediaPath: String? = null): Boolean
fun unpinEntry(clip: String)
fun addToTodo(clip: String, timestamp: Long, ...): Boolean
fun removeTodoEntry(clip: String)
fun setTodoStatus(clip: String, status: String)
fun setPinnedTags(clip: String, tags: List<String>): Boolean
fun setTodoTags(clip: String, tags: List<String>): Boolean
fun getAllPinnedTags(): Set<String>
fun getAllTodoTags(): Set<String>

// ClipboardHistoryView — view-layer callbacks
var onItemAddedToTab: ((ClipboardTab, pulseCount: Int) -> Unit)?
var onEditModeEntered: (() -> Unit)?
var onEditModeExited:  (() -> Unit)?
```

### State Management

- **Pane-level** (`ClipboardManager`): `currentTab`, `searchMode`, `tagMode`, `tagEditText`, filter state (`tagFilter`, `tagMatchAll`, `statusFilter*`, `dateFilter*`)
- **Entry-level** (`ClipboardHistoryView`): `editingOriginalContent` (non-null = editing), `editingInProgressText`, `expandedStates: Map<Long, Boolean>` (keyed by timestamp), `currentPage`, `paginatedHistory`
- **Persistence**: SQLite for data, SharedPreferences for settings. No in-memory-only state that would need rehydration.

### Modal Modes (Mutually Exclusive)

| Mode | State | Enter | Exit | Priority |
|------|-------|-------|------|----------|
| Tag | `ClipboardManager.tagMode: Boolean` | Tap tags button | Tap close | 1 (highest) |
| Edit | `ClipboardHistoryView.editingOriginalContent != null` | Tap edit button | Save/cancel | 2 |
| Search | `ClipboardManager.searchMode: Boolean` | Tap search box | Tap X | 3 (lowest) |

Routing priority matches the IME key-event chain (see `.claude/skills/ime-key-routing.md`).

## Implementation Plan

### Phase 1 (Complete): V2 → V3 schema
- Independent `pinned_entries` + `todo_entries` tables (COPY semantics)
- Drop boolean columns on `clipboard_history`
- Position as REAL for drag-and-drop midpoint insertion
- Tags (JSON) on pinned; status on todos

### Phase 2 (Complete): V3 → V4 schema
- ALTER TABLE ADD COLUMN `mime_type TEXT DEFAULT 'text/plain'` × 3 tables
- ALTER TABLE ADD COLUMN `thumbnail_blob BLOB` × 3 tables
- ALTER TABLE ADD COLUMN `media_path TEXT` × 3 tables
- Existing rows default to text/plain — zero-copy migration
- `ClipboardMediaManager` for file I/O and thumbnail generation
- `commitContent()` media paste via FileProvider
- Dual export (JSON + ZIP)
- Auto Backup exclusion for `clipboard_media/`

### Phase 3 (Complete): UX polish (v1.3.0)
- Switch `showText="true"` removed from filter Match-Any/All toggle (overflowed narrow IME panel) — replaced with dynamic TextView label
- Entry collapse prevented after pin/todo actions — `applyFilter(resetView=false)` preserves page + expand
- Tab icon pulse animation replaces invisible Toasts
- Triple-pulse for duplicates, single-pulse for success

## Testing Strategy

### Pure JVM Tests (`./gradlew runPureTests`)
- `ClipboardFixesJvmTest` (22) — slider mapping, expand state String keys, size limit guards
- `ClipboardPaginationTest` (20) — 100-item page boundary, page navigation
- `ClipboardDatabaseTest` additions — tags JSON round-trip, status transitions, media fields

### Instrumented Tests (`ew-cli`)
- Schema migration — V3 → V4 ALTER TABLE adds columns with correct defaults
- Media save/load round-trip — ContentResolver → filesDir → SQLite BLOB thumbnail
- commitContent — verify media reaches target app
- Import v2/v3 backup on v4 schema — backward compatibility
- Orphan cleanup — unreferenced media files deleted on startup

### Manual (User) Verification
- Copy image from Chrome → verify thumbnail in clipboard panel
- Copy >1MB text from a text/* content-URI app → verify streaming succeeds
- Pin/todo action → verify tab pulse (single for new, triple for duplicate)
- Filter dialog Match:Any/All toggle — no overlap on narrow panels, label updates

## Dependencies

### Internal
- `CleverKeysService.kt` — hosts the IME; owns the clipboard pane lifecycle
- `SuggestionBarInitializer.kt` — swaps the content pane into `topPane`
- `KeyEventHandler.kt` + `KeyEventReceiverBridge.kt` — key routing for tag/edit/search modes
- `FileProvider` (AndroidManifest) — media paste via `commitContent()`

### External
- SQLite FTS4 (NOT FTS5 — unavailable on all Android builds)
- Android `MediaMetadataRetriever` (video thumbnails)
- Android `PdfRenderer` (PDF thumbnails, API 21+)
- `InputConnectionCompat` (androidx.core, for commitContent)

### Breaking Changes
- [x] V2 → V3 was breaking (schema rebuild)
- [ ] V3 → V4 is NOT breaking (ALTER TABLE ADD COLUMN with defaults)

## Security Considerations

- **Password manager exclusion**: Configurable `PASSWORD_MANAGER_PACKAGES` set (see `clipboard-privacy.md`)
- **Android 13+ IS_SENSITIVE flag**: Honored via `clipboard_respect_sensitive_flag`
- **Media files**: App-private `filesDir` with `MODE_PRIVATE`, FileProvider grants per-paste
- **Auto Backup exclusion**: `clipboard_media/` excluded from `backup_rules.xml` + `data_extraction_rules.xml` to prevent Google Drive leakage
- **External content URIs**: Copied to internal `filesDir` immediately — URI read permission expires when clipboard changes
- **Binder limit**: Raw text >1MB still fails at `getPrimaryClip()` itself — caught as `TransactionTooLargeException`

## Error Handling

| Scenario | Handling |
|----------|----------|
| `TransactionTooLargeException` on `getPrimaryClip()` | Log warning, drop clip |
| `SecurityException` on password-protected PDF | Catch, fall back to MIME icon |
| Corrupted video (native SIGSEGV risk) | Internal paths only — never pass external URIs to `MediaMetadataRetriever` |
| `commitContent()` not supported by target | Fall back to system clipboard with `FLAG_GRANT_READ_URI_PERMISSION` |
| Thumbnail generation fails | Store null — UI shows MIME-type icon |
| File/DB sync divergence | Orphan reconciliation on service start and post-import |
| EditText lost during view recycling | `activeEditingEditText` dynamic property: visibility + windowToken check + child-view fallback |

## Documentation Updates
- [x] `.claude/skills/clipboard-panel-architecture.md` — high-level pane structure
- [x] `.claude/skills/clipboard-tag-system.md` — tag storage + UI (new)
- [x] `.claude/skills/clipboard-todo-system.md` — status cycle + rendering (new)
- [x] `.claude/skills/ime-visual-feedback.md` — tab pulse pattern (new)
- [x] `.claude/skills/ime-key-routing.md` — key routing chain, tag/edit/search priority
- [x] `docs/specs/clipboard-privacy.md` — password manager + media privacy
- [x] `docs/specs/clipboard-system.md` — this document
- [x] `memory/MEMORY.md` — V4 schema notes, IME visual-feedback gotchas

## Success Metrics

- Zero ANR events on `onPrimaryClipChanged()` under large-text + large-media workloads
- Schema migration V3 → V4 succeeds on fresh install AND on existing V3 DBs (zero data loss)
- Media capture drops no content when source app provides a content URI
- Tab pulse visible for every cross-tab action (user can tell success from duplicate without reading text)
- Filter dialog renders without layout overlap on all common IME heights (narrow panels included)

## Open Questions

1. Should history entries support tags? Currently only pinned/todos do. Adding tags to history would blur the ephemeral/curated boundary.
2. Should "add to todo" from a pinned entry default to the pinned entry's current tags? Currently todos start with empty tags.
3. Drag-and-drop ordering within Pinned — `position REAL` column is in place but the UI isn't wired yet.

## Future Enhancements

- **Tag colors**: Per-tag color in `tags` JSON map, rendered in chips. Deferred — requires picker UI and migration.
- **Cloud sync**: Not planned — local-first is intentional for privacy.
- **Rich media preview**: Full-screen viewer on long-press for images/video/PDF. Deferred — current thumbnail strategy is sufficient for recognition.
- **Smart deduping**: Similar-but-not-identical text (e.g., trailing whitespace differences). Currently strict content-hash match.

---

**Created**: 2026-04-17
**Last Updated**: 2026-04-17
**Related Skills**: `clipboard-panel-architecture`, `clipboard-tag-system`, `clipboard-todo-system`, `ime-visual-feedback`, `ime-key-routing`
