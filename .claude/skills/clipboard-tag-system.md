# Clipboard Tag System Skill

Use this skill when adding/changing tag-related features: tag storage, the inline tag panel UI, tag-based filtering, or extending tags to new tabs.

## Storage Model

Tags are stored as a **JSON array in a TEXT column** on the `pinned_entries` and `todo_entries` tables. The `clipboard_history` (FTS4) table does **not** have tags — history is ephemeral and positional; tags are a property of curated entries only.

```sql
-- Column definition (applies to pinned_entries and todo_entries)
tags TEXT DEFAULT '[]'
```

**Canonical form:** `["tag1","tag2"]` (JSON). Empty = `[]`, never `null`.

**Why JSON in TEXT instead of a join table:**
- Typical entry has 0-3 tags; join overhead is disproportionate
- Tags are always read together with the entry (no partial reads)
- Schema simplicity — no migration needed to add tags to todo_entries in V4
- Full-text queries across tags work via SQL `LIKE '%"query"%'` scans (acceptable for small clipboard size)

**Parsing:** `ClipboardEntry.tags: List<String>` is populated by the database layer from the JSON column via a lightweight split (no full JSON parser — tags are whitespace-safe lowercase tokens).

## Tag Normalization

All tags are **lowercased and trimmed** at insert time:

```kotlin
val newTag = newTagInput.text.toString().trim().lowercase()
```

This guarantees:
- `"Work"` and `"work"` collapse to the same tag
- Leading/trailing whitespace never creates phantom tags
- Tag-match filtering can use simple equality without case folding

## Tag Panel UI (`ClipboardTagDialog.kt`)

Misnomer: the file is named `Dialog` but the object is `ClipboardTagPanel` — it builds an **inline panel**, not an AlertDialog (see `ime-key-routing.md` for why dialogs don't work in IME).

**Entry point:** `ClipboardTagPanel.populate(container, context, service, tab, entry, onTagsChanged, onClose)`

**Layout produced** (programmatic, inside `clipboard_tag_panel_content: LinearLayout`):

```
┌─────────────────────────────────────────────┐
│ Tags: <40-char preview>...        [X close] │
├─────────────────────────────────────────────┤
│ Current tags                                │
│ [tag1 ×] [tag2 ×]                           │  ← FlowLayout (wraps)
├─────────────────────────────────────────────┤
│ Add from existing                           │
│ [+ other] [+ ideas]                         │  ← unused tags from siblings
├─────────────────────────────────────────────┤
│ [ New tag...                    ] [ Add ]   │  ← EditText + button
└─────────────────────────────────────────────┘
```

**FlowLayout** (inner class): a minimal custom `ViewGroup` that wraps chips horizontally. Cheaper than importing Flexbox.

**Chip styling:**
- Current tag chips: `"<tag>  ×"` format, `labelColor` text, full-opacity bg
- Suggestion chips: `"+ <tag>"` format, `subLabelColor` text, 70% alpha bg
- Background: rounded `GradientDrawable` (12dp corner radius, `colorKey` as base)

## Key Routing Integration

The new-tag `EditText` receives typed characters via the IME key-routing chain (see `ime-key-routing.md`):

```
Key press → KeyEventHandler.sendText()
    → recv.isClipboardTagMode()          // from ClipboardManager.tagMode
    → recv.insertToClipboardTag(text)    // delegates to ClipboardManager.insertToTag()
    → et.setText(current + text); et.setSelection(newText.length)
```

**Mandatory:** `inputType = InputType.TYPE_NULL` on the EditText. Without it, Android tries to show its own soft keyboard (which IS us — infinite recursion).

**Tag mode has highest priority** in the routing chain (before edit mode, search mode, emoji/GIF). It's modal — tapping the tag button while in edit mode is blocked by `if (!isEditing())` guards.

## Tab-Awareness

| Tab | Has tags? | Service method |
|-----|-----------|----------------|
| HISTORY | No | — |
| PINNED | Yes | `service.setPinnedTags(content, tags)` |
| TODOS | Yes | `service.setTodoTags(content, tags)` |

The tag button is only rendered in row 2 (secondary buttons) for pinned/todos tabs. `ClipboardTagPanel.populate()` returns `null` for HISTORY (refuses to build).

## Saving Flow

Every tag mutation (add/remove/suggestion-tap/new-tag) follows:

1. Mutate local `currentTags: MutableList<String>`
2. Call `saveTags(service, tab, content, currentTags)` — routes to the correct service method based on tab
3. Call `onTagsChanged()` — triggers `ClipboardHistoryView.loadDataAsync()` to reflect changes in entry list
4. Call `refreshChips()` — rebuilds the chip containers with the new state

**Content-based identity:** `saveTags()` passes `entry.content` as the key. Tag mutations don't re-order entries (position stays put), but if the entry is deleted elsewhere, the save is a no-op.

## Tag Filtering

Filter dialog (`clipboard_filter_dialog.xml`) shows a checkbox per tag returned from `service.getAllPinnedTags()` or `getAllTodoTags()`. The user selects tags and a Match mode (Any/All).

**Filter evaluation** (in `ClipboardHistoryView.applyFilter`):
```kotlin
if (tagFilter.isNotEmpty()) {
    val entryTags = entry.tags.toSet()
    val match = if (tagMatchAll)
        tagFilter.all { it in entryTags }       // ALL selected tags must be on entry
    else
        tagFilter.any { it in entryTags }       // ANY selected tag is enough
    if (!match) return@filter false
}
```

**Match-mode label UX:** The Switch has NO `showText="true"` — on narrow IME panels the track's minimum width overflows. Instead, a sibling TextView `filter_tags_match_label` shows `"Match: Any"` / `"Match: All"` and is updated via `OnCheckedChangeListener`. See `clipboard-panel-architecture.md` "Common Gotchas" for history.

## Common Operations

### Add a new tag-carrying tab
1. Add `tags TEXT DEFAULT '[]'` to the table DDL in `ClipboardDatabase.onCreate()`
2. Add migration step in the next `onUpgrade()` version
3. Add `setXxxTags`/`getAllXxxTags` methods on `ClipboardHistoryService`
4. Extend `ClipboardTagPanel.populate()`'s `when (tab)` switch to include the new tab
5. Extend `saveTags()`'s `when (tab)` switch
6. Add the tag button to the tab-aware `when (currentTab)` block in `getView()`
7. Extend filter dialog to show tag section for the new tab

### Extend with tag colors
Currently tags are plain text. To add color:
1. Add `tagColors TEXT DEFAULT '{}'` column (JSON map: tagName → hex color)
2. Apply color to chip background in `createChip()` via `GradientDrawable.setColor()`
3. Add a color picker in the `Add` flow

## Common Gotchas

- **Don't assume FTS:** Tags are NOT indexed by FTS4. Tag searches are linear scans of the tags column — fine for <10k entries, would need a join table if that grew.
- **JSON fragility:** The tags column uses simple JSON array syntax. Don't insert tags containing `"` or `,` — the trim/lowercase guard should prevent this, but explicitly reject them in new UIs.
- **Filter tag set is cached:** `getAllPinnedTags()` / `getAllTodoTags()` scan both tables every call. Rebuild the filter dialog on re-open rather than caching in the Manager.
- **Tag mode blocks tab switching:** When `tagMode == true`, all tab click handlers return early. This is intentional — the tag panel's "Current tags" reflects one entry; switching tabs mid-edit would lose state.

## Related Skills
- `clipboard-panel-architecture.md` — where the tag panel sits in the overall pane
- `ime-key-routing.md` — how typed characters reach the new-tag EditText
- `ime-visual-feedback.md` — why the Add-button errors use inline state instead of Toast
- `clipboard-todo-system.md` — todos also use tags; filter integration is parallel
