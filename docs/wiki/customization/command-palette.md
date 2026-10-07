---
title: Command Palette
description: Searchable list of keyboard actions for customization
category: Customization
difficulty: intermediate
---

# Command Palette

The command palette is a searchable dialog that appears when customizing key actions, showing all available keyboard commands you can assign.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Browse and search all assignable key actions |
| **Access** | Opens automatically in Per-Key Customization |
| **Features** | Search, categories, 227 commands, text and template actions |

## Where It Appears

The command palette is **not** a standalone feature. It appears when you:

1. Go to **Per-Key Customization** (Settings > Activities section)
2. Select a key
3. Tap a swipe direction to assign an action
4. The command palette opens to let you choose an action

## Using the Command Palette

### Search

1. When the palette opens, a search bar appears at top
2. Type to filter commands in real-time
3. Matches title, description, and keywords

### Browse Categories

Commands are organized into categories:

| Category | Contents |
|----------|----------|
| **Text** | Copy, paste, select all, undo, redo |
| **Cursor** | Move cursor, select text, home, end |
| **Modifiers** | Shift, Ctrl, Alt, Meta |
| **Delete** | Backspace, delete word, delete line |
| **Events** | Switch layout, open settings, clipboard |
| **Characters** | Special characters and symbols |

### Select an Action

1. Tap any command to select it
2. The command is assigned to the selected swipe direction
3. Confirm the display label to save the assignment

## Clipboard and Minimize Commands

Search for **Clear system clipboard** (`clear_clipboard`) to assign an action that
empties Android’s current clipboard. Saved CleverKeys history, pins, todos, and media
are preserved. Use the clipboard panel’s **Select** mode and confirmed **Delete selected** action
to remove saved clippings instead.

**Minimize to Bar** (`minimize_bar`) leaves a thin restore strip. **Minimize to Floating
Button** (`minimize_fab`) leaves a restore button in the bottom corner. Tap either to
restore the keyboard. These actions require a currently active keyboard; they cannot
bring back a keyboard the system has already hidden.

## Suffix Commands and Dynamic Templates

The Editing category includes **Append 's** and **Append apostrophe**. They attach
an exact suffix to the last verified word; immediate Backspace removes the suffix
before deleting the word. A space you type right after finishing a word counts as that
word's separator (`James ` → `James's `). Selected ranges, later edits and unrelated
caret moves invalidate attachment.

Choose **Dynamic template** to insert clipboard text, wrap selected text, generate a
UUID or position the caret. Templates are explicit: ordinary Text Input keeps braces
literal. Both per-key and popover assignment use the same editor and label confirmation.
See [Per-Key Actions](per-key-actions.md) for token syntax and editor requirements.

## Available Commands

### Text Actions

| Command | Description |
|---------|-------------|
| `copy` | Copy selected text |
| `cut` | Cut selected text |
| `paste` | Paste from clipboard |
| `selectAll` | Select all text |
| `undo` | Undo last action |
| `redo` | Redo undone action |

### Cursor Navigation

| Command | Description |
|---------|-------------|
| `cursor_left` | Move cursor left |
| `cursor_right` | Move cursor right |
| `cursor_up` | Move cursor up |
| `cursor_down` | Move cursor down |
| `home` | Jump to line start |
| `end` | Jump to line end |
| `page_up` | Page up |
| `page_down` | Page down |

### Selection Actions

| Command | Description |
|---------|-------------|
| `selection_cursor_left` | Select left |
| `selection_cursor_right` | Select right |

### Delete Actions

| Command | Description |
|---------|-------------|
| `backspace` | Delete character before cursor |
| `delete` | Delete character after cursor |
| `delete_word` | Delete word before cursor |
| `forward_delete_word` | Delete word after cursor |

### Layout/Mode Actions

| Command | Description |
|---------|-------------|
| `switch_forward` | Next keyboard layout |
| `switch_backward` | Previous keyboard layout |
| `switch_clipboard` | Open clipboard history |
| `switch_emoji` | Open emoji keyboard |
| `switch_numeric` | Switch to number pad |

### Special Actions

| Command | Description |
|---------|-------------|
| `trackpoint_mode` | Enter TrackPoint navigation mode |
| `selection_delete_mode` | Enter selection-delete mode |
| `voice_typing` | Start voice input |
| `config` | Open settings |

## Tips for Customization

- **Search keywords**: Try "select", "delete", "cursor" to find related commands
- **Preview**: Command descriptions explain what each action does
- **Popular choices**: `copy`, `paste`, `home`, `end` are commonly assigned
- **Power user**: `trackpoint_mode` and `selection_delete_mode` enable advanced navigation

> [!TIP]
> The command palette shows 100+ commands. Use search to quickly find what you need.

## Common Questions

### Q: Can I access the command palette directly?

A: No, the command palette only appears when assigning actions in Per-Key Customization. It's a selection tool, not a standalone command runner.

### Q: Can I run commands without assigning them?

A: Most commands can be run via their assigned keys or default key combinations. For example, Ctrl+C for copy, Ctrl+V for paste.

### Q: How do I know what commands are available?

A: Open Per-Key Customization, select any key, and browse the full list in the command palette.

## Related Features

- [Per-Key Actions](per-key-actions.md) - Customize key swipe actions
- [Shortcuts](../clipboard/shortcuts.md) - Keyboard shortcuts
- [Extra Keys](extra-keys.md) - Add function key row
