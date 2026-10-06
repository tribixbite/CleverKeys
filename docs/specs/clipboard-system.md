# Clipboard System Specification

## Feature Overview
**Feature Name**: Clipboard System (History + Pinned + Todos + Media)
**Priority**: P0
**Status**: Complete (v5 — `DATABASE_VERSION = 5`, verified `ClipboardDatabase.kt:1828` on 2026-08-21)
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
│                 ClipboardDatabase (V5)                     │
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
4. **`ClipboardDatabase.kt`**: SQLite with FTS4 for history (FTS4, NOT FTS5 — FTS5 is unavailable on Android). V4 added `mime_type`, `thumbnail_blob`, `media_path` to all three tables; tags are a JSON array in the `tags` TEXT column. **V5 (#156, private copy)** adds `is_private` (INTEGER NOT NULL DEFAULT 0) and `source_package` (TEXT) to all three tables via `ALTER TABLE ADD COLUMN` — non-destructive and O(1), existing rows default to not-private with no provenance. `is_private` is STICKY: once any copy marks a row private it stays private, and the dedupe merge ORs it while taking the most-recent non-null `source_package` (`migrateV4toV5`, `ClipboardDatabase.kt:244-258,287`).
5. **`ClipboardMediaManager.kt`**: File I/O for media. MIME-aware thumbnail generation. Partitioned storage (`{id/1000}`). Orphan cleanup.
6. **`ClipboardTagDialog.kt`** (`ClipboardTagPanel` object): Inline programmatic tag panel — chips, suggestions, new-tag EditText. FlowLayout inner class for chip wrapping.
7. **`ClipboardEntry.kt`** / **`TodoEntry.kt`**: Data classes with mime/thumbnail/media fields plus derived `isMedia`/`isImage`/`isVideo`/`isPdf` helpers.
8. **`ui/settings/sections/ClipboardSection.kt`**: User toggles — password manager exclusion, media enabled, text-only, max-media-size, storage stats. (The old standalone `ClipboardSettingsActivity.kt` listed here was never declared in the manifest and never launched; deleted 2026-08-28.)

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

## Size filtering and confirmed result deletion (2.0 development)

The existing filter dialog offers inclusive minimum and optional maximum payload
size presets (1/10/100 kB, 1/10/100 MB, with any-size/no-maximum defaults).
Size includes UTF-8 content, thumbnail bytes and actual saved media length. It
excludes database overhead and counts a shared media file in each clipping; the
combined result size is not a promise of reclaimed disk space. Missing files
contribute zero file bytes. File stat and payload measurement run on IO during
loading, not row rendering or interactive filter passes. Rows show age and size.

Size combines with search (including regex), date, tags, private-only and todo
status filters. Bounds persist across tab switches and reset with Clear filters.
Invalid ranges disable Apply, together with the existing todo-status guard.

Delete results applies to ALL matching pages in the CURRENT tab. With no search
or size filter it can delete that tab's full visible result set (Todos retains
its active-only default until the user enables the other statuses). A mandatory
confirmation names the tab, count and combined clipping size; Cancel writes
nothing. Deleting History does not remove pinned/todo copies. Deleting Pinned
or Todos removes only the matching copies in that tab. OS clipboard is untouched.

The confirmation captures immutable entry versions, not a live query. Existing
SQLite row IDs are carried through all three loaders without a schema migration.
An IO transaction checks exact content, tab timestamp, MIME, path, privacy, source,
thumbnail, tags and todo status by ID before deleting. New/replaced/edited rows
are excluded. Per-row parameterized statements avoid SQLite variable limits for
multi-page batches; failures roll back the transaction. Media cleanup runs after
commit only for paths no table still references; existing startup orphan cleanup
recovers a crash between database commit and file removal. The UI reports actual
deleted versus confirmed counts or an error, reloads and clamps pagination.
Deletion is disabled during loading, inline editing, tag mode and another deletion;
pane cleanup dismisses pending confirmation. All new strings have 21 translated
resource variants alongside English.

Verified: 2,739 pure / 938 mock tests; all four new SQLite tests pass on isolated
Pixel7/API34 (205-row batch, version guards, tab/shared-media scope and rollback).
Minified release build and release lint pass; ARM64 signature, alignment and ZIP
integrity verified. Logs: `build/clipboard-bulk-{tests,ew,release}.log`.

Oct 6 Seeker checks pass for the verified APK: search isolates three private fixtures,
confirmation reports count/size, Cancel preserves them, minimum 1 kB leaves two,
and min 10 kB / max 1 kB shows an error and disables Apply. Found and fixed faint
Delete results text: background now follows colorKey, with explicit disabled dimming.
Kotlin/resource compilation and 15 focused clipboard state tests pass.

Oct 6 continued: the `7b763f5e...` APK passes on-device contrast, size-filtered
two-entry deletion, empty-result disablement and independent pinned/todo retention
after deleting all three history fixtures. The landscape filter fits with reachable
buttons. Landscape also exposed zero entry space when separate pagination/feedback
rows consume the short pane; pagination and feedback now share the result row.
Tab changes hide old deletion feedback. Summary/title use count-neutral labels,
translated by Gemini 3.8 in all 22 locales with placeholder validation.

The first compact layout still left only 31dp in Seeker landscape and clipped entry
text. `ClipboardPaneLayout` now measures actual available width: at 720dp or wider,
search/tab controls and result/paging/deletion controls share one horizontal row;
narrow panes retain two rows. Summary and feedback have bounded line counts (the
full text remains available to accessibility). The regression now requires a full
48dp entry viewport, across 720/890dp landscape and 400dp portrait, LTR/RTL,
paging and feedback states, including remeasurement of the same view.

The initial compact-viewport regression fails against the old APK and passes against
the revised layout. All four UI tests pass on isolated Pixel7/API34, including
framework-inflated button tint. Final Kotlin/resource compilation and 2,739 pure
tests pass. Full mock runs each passed 937/938 but failed different unchanged
timing checks (predictor latency, then asynchronous adaptation persistence); both
classes pass in isolation without relaxed limits. These runs are not a clean full
suite result; exact evidence and remaining checks are in `memory/todo.md`.

Reconnected cleanup deleted both remaining synthetic pinned/todo copies and
restored the private-copy toolbar OFF. Rotation 0/0, original IME and expanded
Quick Settings focus were read back; scratch UI XML was removed. No personal
clipping was selected for deletion, and the OS clipboard was never replaced.
The intermediate minified APK (`5bcb5cfc...`) passes the count-neutral title,
landscape confirmation/cancel/deletion and feedback-tab clearing checks. Its final
synthetic history fixture was removed and rotation/focus restored. The stronger 48dp viewport test now
passes all 24 width/direction/paging/feedback combinations;
all four UI tests pass on Pixel7/API34 (`a7874daa-ec83-4810-bd3e-8bf93564cf93`).
The intermediate APK fails the full-row requirement (`33a8b2cc-2d9f-4860-b127-a1005032c9f7`).
Kotlin/resource/app/test assembly and all 15 clipboard state guards pass.

Final responsive minified APK: release build/lint, signature v2, alignment and ZIP
CRC pass; Seeker installed SHA matches
`211798434811ca8e47d740e5b095ce7ae7ee38b52dc9b65b6c235c57b79d0526`.
Actual landscape shows the full clipping row and its edit/send actions. Portrait
paging fits after rotation; next/previous work across the real 32-page history,
keeping the total at 3,113 after deleting the test fixture. Confirmation, Cancel,
single-fixture deletion, empty-result disablement and tab-feedback clearing pass.
The nine tab/close host tests pass too. All synthetic records are removed; toolbar,
rotation, IME, empty test fields, scratch files and original focus are restored.
Manual follow-through remains for everyday media/filter combinations, native-language
review, enlarged text/accessibility and very short narrow split-screen panes.

SQLite transaction coverage runs only on an isolated
emulator, never the maintainer's database. A separate clear-OS-clipboard command and row
swipe-to-delete remain independent open features (#168 / #175).
