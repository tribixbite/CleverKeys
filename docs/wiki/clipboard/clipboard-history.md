---
title: Clipboard History
description: Access and manage previously copied text
category: Clipboard
difficulty: beginner
---

# Clipboard History

CleverKeys maintains a history of text you've copied, making it easy to paste items from earlier.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Access previous clipboard items |
| **Access** | Swipe SW from Ctrl key, or add Clipboard to Extra Keys |
| **Capacity** | Configurable (default 25 items) |

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

1. Go to **Settings > Per-Key Customization**
2. Select any key
3. Assign `switch_clipboard` to a swipe direction

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
2. Tap the **delete button** on the item
3. Or swipe left and tap delete

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

## History Panel Layout

```
┌─────────────────────────────────────┐
│ Clipboard History              [X]  │
├─────────────────────────────────────┤
│ 📌 Pinned item text...         [🗑] │ ← Pinned
│ 📌 Another pinned item...      [🗑] │
├─────────────────────────────────────┤
│ Recently copied text here...   [🗑] │ ← Recent
│ Another clipboard item...      [🗑] │
│ More text from earlier...      [🗑] │
└─────────────────────────────────────┘
```

## Privacy Features

### Password Manager Exclusion

CleverKeys can exclude clipboard entries from password managers:

| Setting | Behavior |
|---------|----------|
| **Enabled** | Clips from 1Password, Bitwarden, etc. not saved |
| **Disabled** | All clips saved normally |

Supported apps include: 1Password, Bitwarden, LastPass, Dashlane, KeePass variants, and more.

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
| **Exclude Password Managers** | Clipboard section | Don't save from password apps |

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

A: Check that it's enabled in **Settings > Clipboard** section (expand it).

### Q: How do I access clipboard quickly?

A: Swipe NW from the Ctrl key, or add a clipboard key to your Extra Keys.

### Q: Why wasn't my copied text saved?

A: It may have been from a password field or a password manager app (if exclusion is enabled).

### Q: Can I recover deleted items?

A: No, deleted items cannot be recovered. Pin important items.

## Related Features

- [Text Selection](text-selection.md) - Select text efficiently
- [Shortcuts](shortcuts.md) - Keyboard shortcuts for clipboard
- [Privacy](../settings/privacy.md) - Privacy settings
