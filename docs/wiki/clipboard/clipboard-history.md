---
title: Clipboard History
description: Access and manage previously copied text
category: Clipboard
difficulty: beginner
related_spec: ../specs/clipboard/clipboard-history-spec.md
---

# Clipboard History

CleverKeys maintains a history of text you've copied, making it easy to paste items from earlier.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Access previous clipboard items |
| **Access** | Long-press paste key or clipboard icon |
| **Capacity** | Up to 25 items |

## Accessing Clipboard History

### Method 1: Long-Press Paste

1. **Long-press** the paste key
2. Clipboard history panel opens
3. Tap any item to paste

### Method 2: Clipboard Icon

If clipboard icon is shown:

1. Tap the **clipboard icon** in toolbar
2. History panel opens
3. Tap item to paste

### Method 3: Command Palette

1. Open command palette
2. Type "clipboard" or "history"
3. Select **Clipboard History**

## Using Clipboard History

### Paste from History

1. Open clipboard history
2. **Tap** any item
3. Item is pasted at cursor position

### Copy Item Again

1. Open clipboard history
2. **Long-press** item
3. Select **Copy**
4. Item moves to top of history

### Delete Item

1. Open clipboard history
2. **Swipe left** on item
3. Or long-press and select **Delete**

## Pin Items

Keep important items from being removed:

### Pin an Item

1. Open clipboard history
2. **Long-press** item
3. Select **Pin**
4. Item shows pin icon

### Unpin Item

1. **Long-press** pinned item
2. Select **Unpin**

Pinned items:
- Stay at top of list
- Don't auto-expire
- Don't count toward limit

## Search History

Find items in your clipboard:

1. Open clipboard history
2. Tap **search icon**
3. Type search term
4. Matching items shown

## History Panel Layout

```
┌─────────────────────────────────────┐
│ Clipboard History           [🔍] [X]│
├─────────────────────────────────────┤
│ 📌 Pinned item text...              │ ← Pinned
│ 📌 Another pinned item...           │
├─────────────────────────────────────┤
│ Recently copied text here...        │ ← Recent
│ Another clipboard item...           │
│ More text from earlier...           │
│ ...                                 │
├─────────────────────────────────────┤
│ [Clear All]      [Settings]         │
└─────────────────────────────────────┘
```

## Privacy Features

### Auto-Expiry

Items automatically expire based on your settings:

| Setting | Behavior |
|---------|----------|
| **1 hour** | Items deleted after 1 hour |
| **24 hours** | Items deleted after 1 day |
| **7 days** | Items deleted after 1 week |
| **Never** | Items kept until manually deleted |

### Password Protection

CleverKeys automatically detects password fields:

| Behavior | Description |
|----------|-------------|
| **Don't save** | Password field text not saved to history |
| **Mask display** | Sensitive items show as "••••" |

### Incognito Mode

When incognito is active:

- Nothing saved to clipboard history
- Existing history still accessible
- New copies are temporary only

## Tips and Tricks

- **Pin frequently used**: Pin items you paste often
- **Clear sensitive data**: Regularly clear history with sensitive info
- **Search long history**: Use search for older items
- **Quick access**: Enable clipboard icon for one-tap access

> [!TIP]
> Double-tap the paste key as a shortcut to open clipboard history.

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Enable History** | Privacy | Turn history on/off |
| **History Size** | Clipboard | Maximum items |
| **Auto-Expiry** | Privacy | Automatic deletion |
| **Show Icon** | Toolbar | Clipboard icon visibility |
| **Password Detection** | Privacy | Detect password fields |

## Clear History

### Clear All Items

1. Open clipboard history
2. Tap **Clear All**
3. Confirm deletion

### Clear Unpinned Only

1. Long-press **Clear All**
2. Select **Clear Unpinned**
3. Pinned items preserved

## Common Questions

### Q: Why don't I see clipboard history?

A: Check that it's enabled in Settings > Privacy > Clipboard History.

### Q: Why wasn't my copied text saved?

A: It may have been from a password field, or incognito mode was active.

### Q: Can I recover deleted items?

A: No, deleted items cannot be recovered. Pin important items.

## Related Features

- [Text Selection](text-selection.md) - Select text efficiently
- [Shortcuts](shortcuts.md) - Keyboard shortcuts for clipboard
- [Privacy](../settings/privacy.md) - Privacy settings

## Technical Details

See [Clipboard History Technical Specification](../specs/clipboard/clipboard-history-spec.md).
