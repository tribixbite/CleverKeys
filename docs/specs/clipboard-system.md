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

**Superseded 2026-10-07:** the "Delete results" button became "Select"; deleting
filtered results is now Select → select all matching → Delete selected (next
section). The confirmation, transaction and media rules below are unchanged and
now serve that one deletion path.

Delete results applied to ALL matching pages in the CURRENT tab. With no search
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
emulator, never the maintainer's database. Row swipe-to-delete remains an independent open feature (#175).

## Persistent selection, batch deletion and bulk actions (2026-10-07)

Maintainer requests: build one deletion out of several searches and size filters,
deselect some, add more, and confirm once; then (same day) "fix the rotation reset and
make selected entries persistent until tap deselect all/cancel or complete action
(delete or add to todo or pinned). also make a merge button that merges and clean button
that removes trailing spaces and inserted newlines." Selection mode replaced the earlier
"Delete results" button; there is one deletion path, not two.

**Entering and the controls.** The result row's **Select** button enters selection
mode (long-press stays "copy to the OS clipboard" in normal mode, so it is not the
entry gesture). Rows then show a 48dp checkbox and hide their per-entry actions;
tapping the text, thumbnail or checkbox toggles the row, and long-press toggles
instead of copying. The selection bar holds five 48dp icon actions with content
descriptions and API 26+ tooltips:

| Action | Effect |
|---|---|
| Select/deselect all matching | A tri-state checkbox (none/partial/all of the current matches selected). Not all → adds every row matching the current search and filters on ALL pages; all → removes them. |
| Clear selection (deselect all) | Deselects everything, including rows the current search/filters hide. Stays in selection mode with 0 selected. |
| More actions (⋮) | A list of the tab's bulk actions: Add to Pinned (not on Pinned), Add to Todos (not on Todos), Merge, Clean. Disabled tabs are never offered as targets. |
| Delete selected | Confirmation dialog, then the transactional delete. |
| Exit selection | Ends selection mode and forgets the selection. |

More actions and Delete are disabled with nothing selected, while loading, editing or
while a bulk action runs. Five icons, not seven: one "more" list keeps the bar at
240dp, so it still fits beside the results in a 720dp landscape pane with the 48dp
entry viewport intact.

The live count ("N selected", plural, polite live region) is the first line of the
result summary, above the unchanged results/size line. Every action result is written
to the polite live-region feedback line, so TalkBack announces it.

**Persistence.** The selection survives search text, regex, size/date/private/tag/
status filter changes and paging. It is NOT a filter: rows hidden by the current
search stay selected and take part in every action. "All matching" always means the
full filtered list, never the visible page.

It also survives everything that recreates or hides the pane: closing and reopening the
pane, switching to the emoji/GIF pane and back, hiding the keyboard, rotation (the host
app restarts input, which finishes the input view and closes the pane), switching
fields or apps, and the theme-change rebuild of the pane. It ends ONLY on Exit
selection, a completed bulk action (Delete, Add to Pinned, Add to Todos, Merge, Clean),
or — the one exception — its tab being disabled in Settings, since those rows can no
longer be shown. Clear selection empties it but stays in selection mode.

*Where it lives.* `ClipboardSelectionHolder` (clipboard/ClipboardSelectionHolder.kt) is
owned by the `ClipboardManager` instance the keyboard service keeps for its lifetime;
every inflated `ClipboardHistoryView` attaches to it (`attachSelectionHolder`). It holds
the `ClipboardSelection` — row ids plus 64-bit payload versions, never content — so
keeping it across hours costs a few bytes per selected row. It is in memory only: if
Android kills the keyboard process the selection is lost (no persistence to disk, by
design — a selection is a short-lived working set, and ids would not survive a restore).

*Reopening.* `resetSearchOnShow` reopens on History, or — while a selection exists — on
the selection's tab, so the checkboxes, the "N selected" count and the selection bar come
back. The search text is cleared as before (the selection does not depend on it). The
first load reconciles the selection: rows deleted, expired, re-captured or edited in the
meantime drop out (version-stamp rules below). A load for a different tab never
reconciles it (a re-inflated view briefly loads History before it is pointed at the
selection's tab; reconciling Pinned ids against History rows would empty it).

*Hiding.* `resetSearchOnHide` (pane close, pane switch, keyboard hide, input restart)
keeps the selection and dismisses only a pending dialog — an IME-attached window cannot
outlive the pane it belongs to. A dialog the user already confirmed has started its
action; it still completes.

**Tab behaviour (decision).** A selection belongs to the tab it started in (row ids are
per table) and the other tab icons stay hidden while it exists, so one selection, one
count and one set of actions are always in view; switching tabs requires Exit first.
Chosen over per-tab selections because those would leave invisible selections behind on
other tabs and make "N selected" and the actions ambiguous; the cost (exit to change
tab) is one tap, and the pane reopens on the selection's tab so it is never lost from
view. All three tabs support selection and every bulk action. Selection cannot start
during inline edit; edit, tags and paste are not offered on selection rows.

**Identity and stale rows.** `ClipboardSelection` stores the database row id plus a
64-bit payload version (timestamp, content, MIME, media path) per selected row.
Every completed load of the selection's tab reconciles: rows that vanished (deleted,
expired, re-captured with a new timestamp) or whose payload changed (edited) are dropped
from the selection and the count updates. Tag and todo-status edits keep the row selected.

**Frozen scope.** Every action resolves the selection against the tab's complete loaded
rows into a frozen `ClipboardDeleteSnapshot` (selected, unchanged rows) when its dialog
opens; the dialog states that scope and the action acts on exactly it.

**Delete selected.** The dialog states the count, tab and combined size. Confirming runs
the unchanged `ClipboardDatabase.deleteSnapshot` transaction: each row is re-checked by
id against the loaded version (content, timestamp, MIME, path, privacy, source, thumbnail,
tags, status), so anything changed after the dialog opened is skipped; any invalid
identity rolls the whole batch back. Post-commit media cleanup removes only files no table
still references. Feedback reports "Deleted X of N selected clippings."

**Add to Pinned / Add to Todos.** COPY semantics, exactly like the per-row pin and todo
buttons: `ClipboardHistoryService.copyEntriesTo` calls the same per-entry inserts
(`ClipboardDatabase.pinEntry` / `addTodoEntry`) for every row inside ONE transaction
(`runInTransaction`). Text and media rows are both copied (those tables hold MIME type,
thumbnail and media path); the privacy marker and provenance travel. Rows the target
already holds are counted as "already there", not duplicated; an insert the database
refuses is counted as failed. The originals stay. Copying is non-destructive, so choosing
the action in the list is the confirmation. Feedback: "Added X clippings to Pinned. Y
were already there." and the target tab icon pulses (once if anything was added, three
times if everything was already there).

**Merge.** Creates ONE new History clipping from the selected text clippings. Order:
oldest first by the timestamp each row shows (capture time in History, pin time in
Pinned, add time in Todos), ties by row id — independent of selection order, search and
page, so a selection always merges the same way. Joined by a single newline; each
clipping's text is kept verbatim. Media clippings are skipped and counted. Fewer than
two text clippings → refused with a message (selection kept). The result must fit the
per-clipping size limit (`clipboard_max_item_size_kb`, UTF-8 bytes; 0 = unlimited) or it
is refused with both sizes (selection kept). If any source is private the merged clipping
is private (merging must not strip protection); its provenance is empty. The
confirmation shows the count, size, skipped media, the privacy note and a one-line
preview (line breaks shown as ↵). The originals are kept. Stored via
`ClipboardHistoryService.addMergedClip` (works even when OS-clipboard monitoring is off —
it is an explicit user action), followed by the normal history limits, like any capture.

**Clean.** In place, through the inline editor's own path (`editEntryContent`: blank and
size validation, per-table update, duplicate guard), so only the copies in the current
tab change; pinned/todo copies of the same text are separate rows and stay untouched.
Media rows are skipped; rows the transform leaves identical are reported as unchanged; a
row edited or removed since the confirmation, or whose cleaned text would duplicate
another row of the tab, is reported as failed. Each row's edit is its own transaction
(the edit helper cannot be nested — it ends its transaction early on a duplicate). The
confirmation shows the count that will change, the unchanged/media counts and a preview
of the first change; with nothing to change only a message is shown (selection kept).

The transform (`ClipboardTextCleaner`, Android-free, `ClipboardTextCleanerTest`):

1. CRLF, lone CR, NEL and U+2028 become `\n`; U+2029 becomes a blank line.
2. Trailing whitespace is removed from every line and the end of the text: spaces, tabs,
   no-break and other Unicode spaces (U+00A0, U+2000–U+200A, U+202F, U+205F, U+3000) and
   U+FEFF. Leading whitespace is never touched.
3. Preformatted text keeps every line break: a line indented by a tab or 2+ spaces, a
   code fence, a line ending in `{`, `;` or `\` or starting with `}`, `//`, a block-comment
   opener, `#!`, `#include`, `<?`, `$ `, `>>> ` or a markup tag.
4. Otherwise a single line break is "inserted" — and replaced — only when neither
   neighbour is blank (blank lines are paragraph breaks), neither line is structural
   (bullet, numbered/lettered/roman item, checkbox, Markdown heading, quote, table row,
   horizontal rule), the line before is long (≥ 20 characters and ≥ 60% of the longest
   prose line, so addresses, poems, greetings and paragraph-final lines keep their
   break) and no URL touches the break.
5. A replaced break becomes one space (the next line's leading whitespace is dropped, so
   no double spaces), except: letter + hyphen at the line end followed by a lowercase
   letter is a word hyphenated by the line break and is rejoined without the hyphen
   (`exam-\nple` → `example`); the hyphen is kept, with no space, when the token already
   contains a hyphen (`state-of-\nthe-art`) or the next line starts with an uppercase
   letter or digit (`Jean-\nPaul`, `COVID-\n19`); a soft hyphen is always dropped; and
   Han/Hiragana/Katakana wraps join with no space.

Known limit: a compound that breaks at its own hyphen before a lowercase word
(`well-\nknown`) loses the hyphen. The transform is idempotent.

**Confirmed actions finish.** `ClipboardSelectionHolder.runConfirmed` runs the confirmed
work on IO on the holder's own scope — not the view's — so a keyboard hide, pane switch or
view detach right after confirming can neither cancel the transaction before it starts nor
drop its completion; the view attached at completion is refreshed through the holder's
observer. One action at a time; selection edits are refused while it runs. Success ends
selection mode; a failure (rolled back, or thrown) keeps the selection for a retry and
reports "Could not finish. Your selection is kept."

**IME dialogs never take window focus (device report, same day).** On the Saga (Android
14) typing in Chrome, tapping the Delete confirmation gave the dialog window input focus;
Chrome lost window focus and requested `HIDE_SOFT_INPUT` ~30 ms later (ImeTracker
`ORIGIN_CLIENT_HIDE_SOFT_INPUT`), `onFinishInputView` ran, and the dialog — a child of the
keyboard window — was dismissed before its button handler ran: nothing was deleted and
the keyboard closed. The filter dialog, whose controls take focus when shown, closed by
itself the same way. `Utils.show_dialog_on_ime` now applies `ImeDialogWindowPolicy`:
`FLAG_NOT_FOCUSABLE` (no `FLAG_ALT_FOCUSABLE_IM`), so the app's editor keeps focus and
the window may not use the input method; and `FLAG_WATCH_OUTSIDE_TOUCH`, because a
non-focusable window is not touch-modal — a touch outside (e.g. on the keyboard) cancels
the dialog instead of leaving it up. Widgets inside such dialogs must not open their own
focusable popups, so the filter dialog's size choosers are `ImeDialogSpinner`s (a
single-choice list in another non-focusable IME dialog, keyed to the keyboard's window
token — a sub-window cannot parent another). The selection itself no longer depends on
any of this: it survives the input view finishing.

**Layout.** Narrow (tall) panes give the selection bar its own 48dp row. Wide panes
(≥720dp, i.e. landscape) put it beside the results; the hidden tab icons return
their width (the wide search bar is 212dp + 36dp per visible tab), so the count,
paging and the five actions fit at 720dp with the 48dp entry viewport intact
(`ClipboardPaneTintTest#selectionModeKeepsEntryViewportCountAndActionTargetsInLandscape`).

**Invariants (tests).** `ClipboardSelectionTest` (model: identity, persistence across
lists, coverage, reconcile, resolve, no content retained); `ClipboardSelectionHolderTest`
(start/keep/replace, confirmed-action lifecycle, failure keeps the selection, completion
reaches the currently attached view); `ClipboardHistoryViewStateGuardsTest` (all pages,
persistence across search/size/page, survival across view recreation, other-tab loads do
not reconcile, reload pruning, tab switch/exit, edit exclusion, bulk runners);
`ClipboardTabsAndPaneCloseTest` (hide keeps the selection and dismisses a pending dialog,
reopen restores the tab, disabled tab ends it, rebuild keeps it, explicit exit ends it);
`ClipboardBulkPlansTest` (merge order/joining/media/privacy/size limit, clean plan,
offered actions); `ClipboardBulkServiceTest` (one transaction, duplicate vs failure
counts, merge storage, in-place clean through the edit path); `ClipboardTextCleanerTest`;
`ImeDialogWindowTest` (window flags); `ClipboardMediaDeleteAffordanceTest` (real `getView`
selection row); native: `ClipboardDatabaseTest#selectionResolvedAfterReloadDeletesUnchangedRowsByIdentityAndKeepsCopies`,
the `ClipboardFilterDialogTest` 205-row delete flow and
`ClipboardFilterDialogTest#selectionSurvivesPaneRebuildAndBulkActionsCompleteOnTheDatabase`.
Device-only: whether a given host app still hides the keyboard for other reasons, real
rotation in apps that recreate their activity, and TalkBack wording.

### Clear system clipboard command (#168, 2026-10-06)

`clear_clipboard` is an opt-in command in the Clipboard category, assignable to a
short swipe, popover slot or extra key. It clears Android’s current clipboard and
shows success/failure in the suggestion bar. It never deletes saved history, pinned
entries, todos or their media, and never edits the target field or inline clipboard
editor. No confirmation is shown for this explicitly assigned single-item action;
Batch deletion (Delete selected) continues to require confirmation.

The shared platform operation uses `clearPrimaryClip()` on API 28+; API 21–27
replace the current clip with one empty plain-text item. Existing empty-text
ingestion rejects that fallback. The command does not read the clip, initialize
the history singleton, or require an InputConnection. Missing services, profile
restrictions and Binder/OEM failures produce failure feedback. Older apps checking
only `hasPrimaryClip()` may still see an empty item on API 21–27. See the
[Android ClipboardManager reference](https://developer.android.com/reference/android/content/ClipboardManager#clearPrimaryClip()).

# TODO: native-speaker review of the new 22-locale command/feedback wording.

Validation: core host suites 2,740 pure / 946 mock; final Android checks 6/6 include
real clipboard clearing with unchanged saved rows/media references, extra-key ID/title
search, correct recycled labels and opt-in state. The UI regression fails on the frozen
older APK. The minified Seeker build exposes 225 commands and the correct extra-key
row; no binding was saved and the device’s Android clipboard was preserved. The
maintainer should exercise the assigned command using a disposable copied clip.

### Native filter and deletion follow-up (October 6)

`ClipboardFilterDialogTest` now operates the actual nonfocusable IME filter dialog
through UIAutomator: invalid range, Cancel, valid Apply, tab persistence and Clear all
filters. Its confirmed-delete case creates 205 matching rows across multiple pages,
cancels once, then verifies immutable confirmation against a new row and an edited
row while preserving independent pinned/todo copies. Fixtures use unique prefixes
and remove only their own data. Full cloud evidence: [testing strategy](testing-strategy.md).
