# Clipboard Todo System Skill

Use this skill when touching todo entries: status cycle, done-button behavior, visual rendering, filter integration, or extending the todo system.

## Conceptual Model

A **todo** is a *copy* of a clipboard entry (text or media) with added workflow state:
- `status`: one of `active` / `planned` / `completed` (default `active`)
- `tags`: JSON array (shared system with pinned — see `clipboard-tag-system.md`)
- `completedAt`: timestamp when status last entered `completed` (nullable)

**COPY semantics:** When a user taps "add to todo" on a history or pinned entry, the content is duplicated into `todo_entries`. Mutations on the original history/pinned entry do NOT propagate. Deleting the history entry does NOT remove the todo.

## Data Model

### `TodoEntry.kt` constants

```kotlin
const val STATUS_ACTIVE = "active"
const val STATUS_PLANNED = "planned"
const val STATUS_COMPLETED = "completed"
val VALID_STATUSES = setOf(STATUS_ACTIVE, STATUS_PLANNED, STATUS_COMPLETED)
```

Stored as a TEXT column, not an enum ordinal — forward compat and inspection-friendly via SQL.

### Status derived helpers

```kotlin
val isCompleted: Boolean get() = status == STATUS_COMPLETED
val isPlanned:   Boolean get() = status == STATUS_PLANNED
val isActive:    Boolean get() = status == STATUS_ACTIVE
```

### Display helper

`TodoEntry.getFormattedText()` returns a `SpannableString`:
- `[done] ` prefix (for completed) or `[plan] ` prefix (for planned), no prefix for active
- The prefix is styled with a span so it can be dimmed/tinted differently from the body
- No strikethrough span here — strikethrough is applied on the TextView in `getView()` (keeps the raw string plain for search + filter)

## Status Cycle

### Cycle Button (status icon, row 2)

Cycles through all three states forward:
`active → planned → completed → active`

Implementation in `ClipboardHistoryView.cycleTodoStatus(entry)`:

```kotlin
val next = when (entry.todoStatus) {
    TodoEntry.STATUS_ACTIVE    -> TodoEntry.STATUS_PLANNED
    TodoEntry.STATUS_PLANNED   -> TodoEntry.STATUS_COMPLETED
    TodoEntry.STATUS_COMPLETED -> TodoEntry.STATUS_ACTIVE
    else                       -> TodoEntry.STATUS_ACTIVE
}
service?.setTodoStatus(entry.content, next)
loadDataAsync()
```

The `else` branch defends against unknown status values (e.g., future schema additions).

### Done Button (quick toggle)

The Done button (row 2) toggles directly between `active ↔ completed`, **skipping `planned`**:

```kotlin
val newStatus = if (entry.todoStatus == TodoEntry.STATUS_COMPLETED)
    TodoEntry.STATUS_ACTIVE
else
    TodoEntry.STATUS_COMPLETED
service?.setTodoStatus(entry.content, newStatus)
```

**Rationale:** Users typically want "mark done" as a single tap. Three-state cycling is discoverable but slow for the common case. Two buttons serve two workflows: cycle for triage, done for completion.

## Visual Rendering (in `getView()` for TODOS tab)

| Status | Prefix | Alpha | Strikethrough |
|--------|--------|-------|---------------|
| active | (none) | 1.0 | no |
| planned | `[plan] ` | 0.7 | no |
| completed | `[done] ` | 0.4 | yes |

**Alpha** applied on the whole row view, so the button column dims along with the text — signalling the entry is less actionable.

**Strikethrough** applied via `Paint.STRIKE_THRU_TEXT_FLAG` on the text view's `paintFlags`. Not stored in the data, so re-rendering the entry elsewhere (e.g., history search) shows plain text.

**Prefix color:** styled with a span in `getFormattedText()` using theme attr `colorSubLabel` so it reads as metadata rather than content.

## Feedback for Add-to-Todo Action

When a user taps "add to todo" from History or Pinned tab, the entry is **not** visually moved to the Todos tab (user stays on their current tab). Feedback comes from the **tab icon pulse** pattern (see `ime-visual-feedback.md`):

```kotlin
// In todo_entry(entry)
val added = service.addToTodo(entry.content, entry.timestamp, ...)
onItemAddedToTab?.invoke(ClipboardTab.TODOS, if (added) 1 else 3)
```

- Single pulse (count=1): first-time add succeeded
- Triple pulse (count=3): duplicate — entry already exists in `todo_entries`

**Service contract:** `ClipboardHistoryService.addToTodo()` returns `Boolean`:
- `true` — new row inserted
- `false` — duplicate (matched by content hash), no DB change

Without this Boolean contract, the view can't distinguish the two cases and would pulse once always.

## Filter Integration

The filter dialog shows a **Status filter** section **only on the TODOS tab**. Three checkboxes:
- [ ] Active
- [ ] Planned
- [ ] Completed

At least one must be checked (Apply button is disabled when all three are unchecked — prevents the empty-result state).

### Filter evaluation in `applyFilter()`

```kotlin
if (statusFilterActive || statusFilterPlanned || statusFilterCompleted) {
    val show = when (entry.todoStatus) {
        TodoEntry.STATUS_ACTIVE    -> statusFilterActive
        TodoEntry.STATUS_PLANNED   -> statusFilterPlanned
        TodoEntry.STATUS_COMPLETED -> statusFilterCompleted
        else                       -> true  // unknown statuses pass
    }
    if (!show) return@filter false
}
```

**Default filter state:** on first open, all three are checked (show everything). User must explicitly narrow. The filter icon in the search bar is tinted `colorLabelActivated` when any status is unchecked (i.e., active filtering).

## Common Operations

### Add a fourth status (e.g., `blocked`)

1. Add `const val STATUS_BLOCKED = "blocked"` in `TodoEntry.kt`
2. Add to `VALID_STATUSES` set
3. Extend `cycleTodoStatus()` cycle — decide insertion point (before or after `completed`)
4. Add visual case: prefix, alpha, strikethrough rule in `getView()`
5. Add a filter checkbox + state boolean in `ClipboardManager.kt` + dialog XML
6. Extend `applyFilter()` status check

### Rename "planned" to "deferred"

**Don't rename the constant** — the DB stores it as TEXT and existing rows would break. Instead:
- Keep constant value `"planned"` as-is (or add migration)
- Change UI label in `getFormattedText()` prefix and filter dialog only

## Common Gotchas

- **Done button vs Status button confusion:** Both rely on `entry.todoStatus`, but Done is a toggle (binary) and Status cycles (ternary). Users often tap wrong first; that's fine — another tap fixes it. Don't consolidate them.
- **`setTodoStatus()` uses content as key:** Like tags, status mutations key on content. If two todos have identical content (shouldn't happen post-dedup in `addToTodo`), both rows change. Dedup at add-time prevents this.
- **`loadDataAsync()` after status change:** Required to pick up the new status and re-filter. The callback chain (`applyFilter(resetView=false)`) preserves page + expand state so the user's place is kept.
- **Strikethrough is view-only:** Clipboard search / FTS match content without `[done] ` prefix. Don't add the prefix to indexed content — it'd break text searches.
- **`completedAt` is set by the DB layer:** When status transitions TO `completed`, `ClipboardDatabase.setTodoStatus()` sets `completed_at = now()`. When transitioning away, it clears to NULL. Don't set this from the service/view.
- **Expand state survives status change:** Expand map is keyed by timestamp, so toggling status on an expanded entry keeps it expanded after reload.

## Related Skills
- `clipboard-panel-architecture.md` — 3-tab layout, button column structure
- `clipboard-tag-system.md` — todos use the same tag system as pinned
- `ime-visual-feedback.md` — tab pulse on add-to-todo
- `ime-key-routing.md` — edit mode on todos reuses the same priority chain
