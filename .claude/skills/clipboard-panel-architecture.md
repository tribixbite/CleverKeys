# Clipboard Panel Architecture Skill

Use this skill when working on clipboard tabs, entry rendering, inline editing, tag management, pagination, search/filter, or adding new clipboard features.

## Key Files

| File | Purpose |
|------|---------|
| `res/layout/clipboard_pane.xml` | Panel layout: search bar, tabs, content area, tag panel, pagination |
| `res/layout/clipboard_history_entry.xml` | Per-entry layout: text, thumbnail, button column (4 rows) |
| `ClipboardManager.kt` | Pane-level state: search, tag mode, edit lock UI, tab switching |
| `ClipboardHistoryView.kt` | Entry list: adapter, getView(), inline edit, expand states |
| `ClipboardHistoryService.kt` | Singleton data layer: CRUD, history/pinned/todo operations |
| `ClipboardDatabase.kt` | SQLite: schema V3, FTS4 search, tag JSON, todo status |
| `ClipboardEntry.kt` | Data class: content, timestamp, mimeType, tags, todoStatus |
| `ClipboardTagPanel.kt` | Inline tag management: chips, suggestions, new tag input |
| `KeyEventHandler.kt` | Key routing: tag > edit > search priority chain |
| `KeyboardReceiver.kt` | Bridge: IReceiver implementation delegating to ClipboardManager |

## Panel Layout Structure

```
clipboard_pane.xml (LinearLayout, VERTICAL, match_parent)
|
+-- Search bar row (LinearLayout, HORIZONTAL, 40dp, colorKey bg)
|   +-- tab_history (ImageView, 36dp, ic_tab_history)
|   +-- tab_pinned (ImageView, 36dp, ic_tab_pinned)
|   +-- tab_todos (ImageView, 36dp, ic_tab_todos)
|   +-- clipboard_search (TextView, weight=1, "Search...")
|   +-- clipboard_search_clear (ImageButton, 28dp, GONE)
|   +-- clipboard_regex_toggle (TextView, ".*", 28dp, GONE)
|   +-- clipboard_date_filter (ImageButton, 32dp)
|   +-- clipboard_close_button (ImageButton, 32dp)
|
+-- Divider (View, 1dp, clipboard_divider_color)
|
+-- Content ScrollView (id: clipboard_content_scroll, weight=1)
|   +-- ClipboardHistoryView (NonScrollListView)
|       +-- [entries rendered by ClipboardEntriesAdapter.getView()]
|
+-- Tag panel (id: clipboard_tag_panel, weight=1, GONE)
|   +-- Tag panel content (populated programmatically)
|
+-- Pagination bar (LinearLayout, 32dp, GONE)
    +-- page_prev, page_info, page_next
```

**Content area and tag panel are mutually exclusive** — when tag panel is VISIBLE, content scroll is GONE, and vice versa.

## Entry Layout Structure

```
clipboard_history_entry.xml (LinearLayout, HORIZONTAL)
|
+-- Thumbnail container (FrameLayout, 48dp, GONE for text)
|   +-- thumbnail (ImageView)
|   +-- play_badge (ImageView, GONE)
|
+-- Text content (TextView, style=clipboardEntry, weight=1)
+-- Edit field (EditText, GONE, inputType=none)
|
+-- Button column (LinearLayout, VERTICAL, right-aligned)
    +-- Row 1: Primary (expand, edit, paste) — always visible in normal mode
    +-- Row 2: Secondary (pin/unpin/todo/done/status/tags) — shown on expand
    +-- Row 3: Edit buttons (save, cancel) — shown during edit mode
    +-- Row 4: Delete row (delete) — shown during edit mode, isolated
```

## Three Tabs

| Tab | Backing Store | Extra Fields | Actions |
|-----|--------------|--------------|---------|
| HISTORY | `clipboard_history` FTS4 | — | pin, todo, delete (in edit) |
| PINNED | `pinned_entries` | tags (JSON) | unpin, todo, tags |
| TODOS | `todo_entries` | tags (JSON), status | done, status cycle, tags, delete (in edit) |

Tab switching: `ClipboardManager.switchToTab()` → `ClipboardHistoryView.setTab()` → `loadDataAsync()`

## Data Flow

```
User action (tap tab, type search, scroll page)
    ↓
ClipboardManager (state management)
    ↓
ClipboardHistoryView.loadDataAsync()
    ↓ (Dispatchers.IO)
ClipboardDatabase.get*Entries()
    ↓ (Dispatchers.Main)
history = entries
applyFilter() → applyPagination() → adapter.notifyDataSetChanged()
    ↓
getView() renders each entry with tab-aware button visibility
```

## Modal Modes (Mutual Exclusion)

The clipboard pane has three mutually exclusive modes. Only one can be active at a time.

### 1. Normal Mode (default)
- Entry list visible, search works, tabs switchable
- Tap entry text to expand → shows secondary buttons
- Tap paste/pin/todo/tags buttons for actions

### 2. Edit Mode (per-entry, in ClipboardHistoryView)
- **State**: `editingOriginalContent: String?` (non-null = active)
- **Enter**: Tap edit button → `edit_entry(pos)`
- **Exit**: Save/cancel → `cancelEdit()` / `save_edit()`
- **UI lock**: `onEditModeEntered` callback dims tabs, search, pagination
- **Key routing**: `isClipboardEditMode() → insertToClipboardEdit()` (tab-agnostic — same chain for history, pinned, todos)
- Search mode cleared on enter. Tab switching blocked.
- **Text manipulation**: All methods use `activeEditingEditText` (dynamic property with visibility + windowToken validation + child view fallback) — see `ime-key-routing.md` "ListView Scrap View Architecture"
- **View recycling**: getView() sets up cursor, click listeners, TextWatcher on EVERY call (not just first render). Only the `editingEditText` reference cache is guarded.
- **State tracking**: `editingInProgressText` is synced directly in text manipulation methods AND via TextWatcher (belt-and-suspenders). `save_edit()` reads from `editingInProgressText` (not EditText widget).

### 3. Tag Mode (pane-level, in ClipboardManager)
- **State**: `tagMode: Boolean` + `tagEditText: EditText?`
- **Enter**: Tap tags button → `showTagPanel(entry, tab)`
- **Exit**: Tap close → `hideTagPanel()`
- **UI**: Content scroll GONE, tag panel VISIBLE
- **Key routing**: `isClipboardTagMode() → insertToClipboardTag()`
- Highest priority in key routing chain (before edit and search).

## Expand/Collapse State

- Tracked by **content string** (not position): `expandedStates: MutableMap<String, Boolean>`
- Single tap on text/chevron toggles expansion
- Expanded state shows: full text (maxLines=MAX_VALUE) + secondary buttons
- Collapsed state: single line (maxLines=1) + no secondary buttons
- States cleared on tab switch or page change

## Pagination

- 100 items per page (`ITEMS_PER_PAGE`)
- Pagination bar shows when filteredHistory.size > 100
- `currentPage` index, `applyPagination()` slices filteredHistory
- Page change clears expand states

## Search and Filter Pipeline

```
searchFilter (String) + regexMode (Boolean) + dateFilter (timestamp + direction)
    ↓
applyFilter() — filters full history → filteredHistory
    ↓
applyPagination() — slices filteredHistory → paginatedHistory
    ↓
adapter.notifyDataSetChanged()
```

Regex uses `expandGlobShorthand()` for `*`/`?` glob support.

## Adding New Entry Buttons

1. Add `<View>` in `clipboard_history_entry.xml` inside the appropriate row
2. Add `findViewById` in `ClipboardHistoryView.getView()`
3. Set visibility in the tab-aware `when (currentTab)` block
4. Add click handler with `if (!isEditing())` guard
5. Implement the action (call service method + `loadDataAsync()`)

## Adding New Inline Panels

Follow the tag panel pattern:

1. Add a container in `clipboard_pane.xml` (GONE, weight=1)
2. Add mode state in `ClipboardManager.kt` (boolean + view refs)
3. Add show/hide methods that toggle visibility with content scroll
4. Wire key routing if the panel has text input (see `ime-key-routing.md`)
5. Add callback from `ClipboardHistoryView` to `ClipboardManager`
6. Clean up in `ClipboardManager.cleanup()`

## Todo Status System

Three states cycle: `active` → `planned` → `completed` → `active`

| Status | Visual | Alpha |
|--------|--------|-------|
| active | (no prefix) | 1.0 |
| planned | `[plan] ` prefix | 0.7 |
| completed | `[done] ` prefix + strikethrough | 0.4 |

Done button toggles between active ↔ completed directly.
Status button cycles through all three states.

## Tag System

- Tags stored as JSON array in TEXT column: `["tag1","tag2"]`
- Operations via `ClipboardHistoryService`: `setPinnedTags()`, `setTodoTags()`, `getAllPinnedTags()`, `getAllTodoTags()`
- Tag chips use `FlowLayout` (custom ViewGroup) for horizontal wrapping
- Current tags shown as removable chips (tap × to remove)
- Suggestion chips show unused tags from other entries (tap + to add)
- New tag input at bottom with Add button

## Common Gotchas

- **COPY semantics**: Pinning/todoing copies the entry (independent from history). Changes to one don't affect the other.
- **Content-based identity**: Edit/expand states track by content string, not list position. Position changes when entries are added/deleted/filtered.
- **Async loading**: `loadDataAsync()` cancels previous load job. Rapid tab switches won't cause stale data overwrites.
- **Edit suppression**: `on_clipboard_history_change()` and `onWindowVisibilityChanged()` return early during edit mode to prevent view recreation that would reset the EditText.
- **Non-breaking spaces**: Timestamps use `\u00A0` to prevent wrapping mid-unit (e.g., "2\u00A0min\u00A0ago").
- **View recycling**: `getView()` receives recycled views. Always reset ALL visibility states — never assume a view starts in a particular state.
- **ListView scrap views**: When EditText height changes (e.g., newline inserted), ListView calls `getView()` with invisible scrap views for measurement. NEVER cache references to scrap view widgets without a guard. See `ime-key-routing.md` "ListView Scrap View Architecture" for the required pattern.
- **FTS4**: The project uses FTS4 (not FTS5) because FTS5 is unavailable on all Android builds.

## Related Skills
- `ime-key-routing.md` — How key events reach inline editors
- `content-pane-layout.md` — View hierarchy and pane switching
