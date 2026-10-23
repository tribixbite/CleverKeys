---
title: Clipboard Shortcuts
description: Copy, cut, paste, select all, undo and redo with the Ctrl key or with commands assigned to key swipes
category: Clipboard
difficulty: beginner
---

# Clipboard Shortcuts

Use Ctrl key combinations, or assign clipboard commands to key swipes, for fast copy, cut,
paste, select-all, undo and redo.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Fast clipboard operations |
| **Methods** | Ctrl key combinations; commands assigned to short swipes; extra keys |
| **Setup** | Ctrl is on the default bottom row; commands are assigned in Per-Key Customization |

## Ctrl key combinations

The default bottom row has a **Ctrl** key at the bottom left. Ctrl is sticky: tap it once,
then tap the letter. It is not held down like on a computer.

| Shortcut | Action |
|----------|--------|
| **Ctrl+C** | Copy selected text |
| **Ctrl+X** | Cut selected text |
| **Ctrl+V** | Paste |
| **Ctrl+A** | Select all |
| **Ctrl+Z** | Undo |
| **Ctrl+Y** or **Ctrl+Shift+Z** | Redo |

CleverKeys sends the key combination; the app decides what to do with it. Most text fields
implement these shortcuts, terminals generally do not (see "Terminal apps" below).

If your layout has no Ctrl key, enable it under **Settings > Activities > Configure Extra Keys**.

## Swipes on the Ctrl key

The swipe directions of the default Ctrl key switch panels and modifiers; they are not
clipboard actions:

| Direction | Key | What it does |
|-----------|-----|--------------|
| **Southwest** | `switch_clipboard` | Opens the [clipboard panel](clipboard-history.md) |
| **Southeast** | `switch_numeric` | Numbers and symbols |
| **Northwest** | `switch_greekmath` | Greek and math symbols |
| **Northeast** | `meta` | Meta modifier |

The clipboard switch and the other `loc` keys on Ctrl appear only while the matching extra
key is enabled (**Settings > Activities > Configure Extra Keys**); the clipboard key is on by
default.

## Clipboard commands on any key

Assign a command to a short swipe in **Settings > Activities > Short Swipe Calibration >
Customize Per-Key Actions**: tap a key, tap a direction, and pick **Command** in the
[command palette](../customization/command-palette.md). The clipboard and editing commands are:

| Command | Palette name | Action |
|---------|--------------|--------|
| `copy` | Copy | Copy the selection |
| `cut` | Cut | Cut the selection |
| `paste` | Paste | Paste the system clipboard |
| `pasteAsPlainText` | Paste as plain text | Paste without formatting |
| `selectAll` | Select all | Select the whole field |
| `shareText` | Share text | Send the selection to the share sheet |
| `copy_private` | Private copy | Copy to CleverKeys only, never the OS clipboard ([Private Copy](private-copy.md)) |
| `undo` / `redo` | Undo / Redo | Ask the editor to undo or redo |
| `paste_pinned_1` … `paste_pinned_5` | Paste Pin #1–#5 | Insert a pinned clipboard entry by position |
| `clear_clipboard` | Clear system clipboard | Empty Android's clipboard (saved history stays) |
| `switch_clipboard` | Clipboard | Open the clipboard panel |

Example setup:

| Key | Direction | Action |
|-----|-----------|--------|
| **Shift** | NE | Copy |
| **Shift** | SE | Paste |
| **Shift** | SW | Cut |

See [Per-Key Actions](../customization/per-key-actions.md) for the editor and the other
action types.

## Paste from clipboard history

Open the clipboard panel (southwest swipe on Ctrl, or the Clipboard extra key) and tap an
entry's paste button. Tapping a text row expands its actions. Pinned entries can also be
pasted directly with the `paste_pinned_N` commands above.

## Terminal apps

In Termux and any app listed under **Settings > Advanced > Custom terminal packages**, the
**Paste** command and the paste key insert the clipboard text directly, because terminals
implement neither the standard paste action nor Ctrl+V through the keyboard. Ctrl+C and
Ctrl+V typed as shortcuts reach the terminal as the key combinations themselves.

## Clear the System Clipboard

Assign **Clear system clipboard** (`clear_clipboard`) through Per-Key Customization,
or enable its extra key. It empties Android's clipboard and preserves saved history,
pins, todos, and media. To delete stored clippings, use **Select** in the
clipboard panel, select the clippings (or all matching results), and confirm **Delete selected**.

## Common Questions

### Q: Why isn't Ctrl+C working?

A: First tap Ctrl, then tap C. They are not simultaneous presses. If the field still does
nothing, the app does not handle the shortcut; use its own copy menu or the `copy` command.

### Q: Can I hold Ctrl like on a computer?

A: The modifier is sticky: tap once and it applies to the next key. No need to hold.

### Q: Which is faster, Ctrl combinations or assigned commands?

A: An assigned command is a single swipe; a Ctrl combination is two taps. Assign the
commands you use most.

## Related Features

- [Extra Keys](../customization/extra-keys.md) - Add Ctrl, arrows and the Clipboard key
- [Per-Key Actions](../customization/per-key-actions.md) - Assign commands to swipes
- [Text Selection](text-selection.md) - Selecting the text to copy
- [Clipboard History](clipboard-history.md) - Access history
- [Private Copy](private-copy.md) - Copy without touching the OS clipboard
