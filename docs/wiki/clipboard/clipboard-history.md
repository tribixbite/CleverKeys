---
title: Clipboard History
description: Access and manage previously copied text
category: Clipboard
difficulty: beginner
---

# Clipboard History

CleverKeys maintains a history of everything you've copied — text, images, videos, PDFs, and more — making it easy to paste items from earlier.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Access previous clipboard items (text + media) |
| **Access** | Swipe SW from Ctrl key, or add Clipboard to Extra Keys |
| **Capacity** | Configurable (default 50 items) |

## Accessing Clipboard History

### Method 1: Ctrl Key Swipe

1. Find the **Ctrl** key on the bottom row
2. **Swipe SW** (down-left) to activate `switch_clipboard`
3. Clipboard history panel opens
4. Tap any item to paste

### Method 2: Extra Keys

If you've added a clipboard key to Extra Keys:

1. Tap the **clipboard icon** in your extra keys row
2. History panel opens
3. Tap item to paste

### Method 3: Per-Key Customization

You can assign clipboard access to any key's short swipe:

1. Go to **Settings > Activities > Per-Key Customization**
2. Select any key
3. Assign `switch_clipboard` to a swipe direction

## Using Clipboard History

### Paste from History

1. Open clipboard history
2. **Tap** any item
3. Item is pasted at cursor position

### Delete Item

- **Text entries**: tap the **✏️ edit button** on the item — the delete row appears with the edit controls. Tap **🗑 delete** there.
- **Media entries**: media can't be edited inline, so tap the **expand chevron** instead — the delete row shows directly on the expanded entry.

Deleting from the Pinned or Todos tab removes the item from that tab only (the copies are independent).

### Long-Press to Copy

Copy an item to the system clipboard without pasting it into the current field:

1. Open clipboard history
2. **Long-press** any item's text
3. A "Copied to clipboard" toast confirms the action
4. The text is now on the system clipboard — paste it in any app

This is useful when you want to store text for use in another app without inserting it into the current text field.

## Pin Items

Keep important items from being removed:

### Pin an Item

1. Open clipboard history (History tab)
2. Tap the item to expand it
3. Tap the **📌 pin button**
4. Item is added to the Pinned tab

### Unpin Item

1. Switch to the **Pinned tab** (📌)
2. Expand the item and tap the **📌 pin button** to unpin
3. Item is removed from Pinned tab

Pinned items:
- Appear in the dedicated Pinned tab
- Don't auto-expire
- Don't count toward history limit

### Pinned Entry Shortcuts

You can bind pinned clipboard entries to swipe gestures using the `paste_pinned_1` through `paste_pinned_5` commands. This lets you insert frequently used pinned text with a single swipe, without opening the clipboard panel.

Entries are numbered by position (most-recently-pinned first):
- `paste_pinned_1` inserts your most recently pinned entry
- `paste_pinned_2` inserts the second, and so on up to `paste_pinned_5`

To set this up, go to **Settings > Activities > Per-Key Customization**, choose a key and direction, select **Command**, and search for "paste_pinned". See [Per-Key Actions](../customization/per-key-actions.md#pinned-clipboard-actions) for full details.

## Todo Items

Mark clipboard items as to-do reminders:

### Add to Todos

1. Open clipboard (History or Pinned tab)
2. Expand the item and tap the **✓ todo button**
3. Item is added to the Todos tab

### Mark as Done

1. Switch to the **Todos tab** (✓)
2. Expand the item and tap the **✓ todo button** to remove it from todos
3. Item is removed from Todos tab (still in history)

Todo items:
- Appear in the dedicated Todos tab
- Can also be pinned (both flags independent)
- Useful for quick reference or follow-up

## Media Clipboard

CleverKeys automatically captures images, videos, PDFs, and other files you copy to the clipboard.

### Supported Media Types

| Type | Examples | Thumbnail |
|------|----------|-----------|
| **Images** | JPEG, PNG, WebP, GIF | Photo preview |
| **Animated** | Animated GIF, animated WebP | First frame + play badge |
| **Videos** | MP4, QuickTime | Video frame preview |
| **PDFs** | PDF documents | First page preview |
| **Other files** | ZIP, documents | MIME-type icon |

### How Media Clipboard Works

1. **Copy** an image/video/file in any app (Gallery, Chrome, Files, etc.)
2. CleverKeys captures it automatically in the background
3. A **thumbnail** appears in clipboard history
4. **Tap** the media entry to paste it into the current app (via `commitContent`)

### Media Paste

- Media is pasted via Android's `commitContent` API — the receiving app must support it
- Most messaging apps (Signal, Telegram, WhatsApp) and text editors support image paste
- If the app doesn't support media paste, a "Cannot paste media here" message appears
- **Long-press** a media entry to copy the media URI to the system clipboard
- **Expand** a media entry (chevron) for the extra actions — pin, todo, tags, and **🗑 delete**. The stored media file is removed from disk once no tab still references it

### Media Settings

| Setting | Description | Default |
|---------|-------------|---------|
| **Save Media Entries** | Enable/disable media capture | On |
| **Text Only** | Hide all media, show only text entries | Off |
| **Max Media Size** | Skip copied media larger than this size (1-50 MB) | 10 MB |

To access: **Settings > Clipboard section** (expand it) or **Settings > Activities > Clipboard Settings**.

> [!TIP]
> If you only want text in your clipboard and find media entries distracting, enable **Text Only**. It hides existing media entries from the panel and stops new media from being captured.

## Tab System

The clipboard pane organizes items into three tabs:

| Tab | Icon | Description |
|-----|------|-------------|
| **History** | 📋 | Recent clipboard history (default) |
| **Pinned** | 📌 | Items you've pinned for quick access |
| **Todos** | ✓ | Items marked as to-do reminders |

### Switching Tabs

1. Open clipboard pane
2. Tap the tab icon (📋, 📌, or ✓) in the header row
3. Active tab is fully visible (alpha 1.0), inactive tabs are dimmed (alpha 0.5)

### Item Actions by Tab

| Action | History Tab | Pinned Tab | Todos Tab |
|--------|-------------|------------|-----------|
| **Pin button** | Pins item | Unpins item | Pins item |
| **Todo button** | Adds to todos | Adds to todos | Removes from todos |
| **Delete** | Removes from history | Removes from Pinned | Removes from Todos |
| **Paste** | Pastes to editor | Pastes to editor | Pastes to editor |
| **Long-press** | Copies to system clipboard | Copies to system clipboard | Copies to system clipboard |

Pin, todo, and tag buttons appear when an entry is expanded (tap the entry or its chevron). Delete lives behind the ✏️ edit button for text entries and behind expansion for media entries (see [Delete Item](#delete-item)).

## Pagination

For large clipboard histories (>100 items), pagination improves performance:

- Items are displayed 100 per page
- Pagination bar appears at the bottom when needed
- Shows current page / total pages (e.g., "1 / 6")
- ◀ and ▶ buttons navigate between pages
- **Search still searches ALL items** across all pages

```
┌─────────────────────────────────────┐
│ [◀]         3 / 6              [▶]  │
└─────────────────────────────────────┘
```

## History Panel Layout

```
┌─────────────────────────────────────┐
│ 📋 📌 ✓  [Search...]  🔽  [▼]      │ ← Tabs + Search + Filter + Close
├─────────────────────────────────────┤
│ Recently copied text here...  [✏️ ▼]│ ← Text entry (edit + expand)
│ [thumb] photo.jpg · 2h ago      [▼]│ ← Image entry with thumbnail
│   └ expanded: [📌] [✓] [🏷] [🗑]    │ ← Actions shown on expansion
│ [▶vid] clip.mp4 · Yesterday     [▼]│ ← Video entry with play badge
│ Another clipboard item...     [✏️ ▼]│ ← Text entry
├─────────────────────────────────────┤
│ [◀]         1 / 3              [▶]  │ ← Pagination (if >100 items)
└─────────────────────────────────────┘
```

## Privacy Features

### Sensitive Flag (the reliable protection)

On Android 13+, password managers mark copied credentials with the system
`IS_SENSITIVE` flag. With **Respect Sensitive Flag** enabled (the default),
CleverKeys never stores those clips. This works regardless of which password
manager you use.

### Password Manager Exclusion (best effort)

**Exclude Password Managers** is a best-effort extra layer: it skips a copy only
when Android reveals that a known password manager is in the foreground. That is
rarely possible without usage access — which CleverKeys never requests — so don't
rely on it alone; the sensitive-content flag above is the dependable mechanism.

### Password Field Detection

CleverKeys automatically detects password fields:

| Behavior | Description |
|----------|-------------|
| **Don't save** | Password field text not saved to history |
| **Mask display** | Sensitive items may show masked |

## Tips and Tricks

- **Pin frequently used**: Pin items you paste often
- **Clear sensitive data**: Regularly clear history with sensitive info
- **Quick access**: Add clipboard to Extra Keys for one-tap access
- **Customize access**: Assign clipboard to any key via Per-Key Customization

> [!TIP]
> The Ctrl key's SW subkey is `switch_clipboard` by default on most layouts.

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Enable History** | Clipboard section | Turn history on/off |
| **History Size** | Clipboard section | Maximum items to keep |
| **History Duration** | Clipboard section | Auto-expiry (default: never) |
| **Max Item Size** | Clipboard section | Per-item text size limit (64-1024 KB) |
| **Save Media Entries** | Clipboard section | Enable/disable media capture |
| **Text Only** | Clipboard section | Hide media, show only text |
| **Max Media Size** | Clipboard section | Skip media larger than this (1-50 MB) |
| **Pinned Tab** | Clipboard section | Show/hide the Pinned tab |
| **Todo Tab** | Clipboard section | Show/hide the Todos tab |
| **Exclude Password Managers** | Clipboard section | Best-effort skip of copies from known password managers (rarely detectable) |
| **Respect Sensitive Flag** | Clipboard section | Honor Android 13+ IS_SENSITIVE (the reliable protection) |

## Clear History

To clear clipboard history, use the export/import features in Settings > Backup & Restore, or delete items individually (see [Delete Item](#delete-item)).

## Common Questions

### Q: Why don't I see clipboard history?

A: Check that it's enabled in **Settings > Clipboard** section (expand it).

### Q: How do I access clipboard quickly?

A: Swipe SW (southwest/down-left) on the Ctrl key, or add a clipboard key to your Extra Keys.

### Q: Why wasn't my copied text saved?

A: It may have been from a password field, or marked sensitive by a password manager (Android 13+ `IS_SENSITIVE` flag, skipped by default).

### Q: Can I recover deleted items?

A: No, deleted items cannot be recovered. Pin important items.

### Q: Why don't I see images in my clipboard?

A: Check that **Save Media Entries** is enabled and **Text Only** is off in **Settings > Clipboard section**, and that the file is under your **Max Media Size** limit. The source app must also provide a content URI when copying (most apps do).

### Q: Can I paste an image from clipboard into a messaging app?

A: Yes — tap the image entry in clipboard history. It uses Android's `commitContent` API. The receiving app must support media input (most modern messaging apps do).

## Related Features

- [Text Selection](text-selection.md) - Select text efficiently
- [Shortcuts](shortcuts.md) - Keyboard shortcuts for clipboard
- [Privacy](../settings/privacy.md) - Privacy settings
