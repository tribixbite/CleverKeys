---
title: Command Palette
description: Searchable list of keyboard actions for customization
category: Customization
difficulty: intermediate
---

# Command Palette

The command palette is a searchable dialog that appears when you assign an action to a subkey slot. It lists every keyboard command you can assign, plus custom text, dynamic templates, intents and timestamps.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Browse and search all assignable key actions |
| **Access** | Per-Key Customization, or the [Subkey Popover](../gestures/subkey-popover.md) assign/edit screen |
| **Features** | Search, 21 categories, 228 commands, four quick actions |

## Where It Appears

The command palette is **not** a standalone feature. It opens when you:

1. Go to **Settings > Activities > Customize Per-Key Actions**, tap a key, then tap a
   direction; or
2. Hold a key with the [Subkey Popover](../gestures/subkey-popover.md) on and let go on an
   empty slot (assign), or rest on an assigned slot for 3 seconds and choose **Edit** or
   **Reassign**.

The title row shows the key and direction you are assigning.

### Editing an Existing Mapping

If the direction already has one of your actions, the palette opens straight into that
action's own editor, filled in: the text editor for custom text or a template, the intent
editor, the timestamp pattern dialog, or the label step for a command. Your existing label
is kept. Back out of the editor to reach the full list and pick something else.

## Using the Command Palette

### Search

1. When the palette opens, a search bar appears at top
2. Type to filter commands in real-time
3. Matches title, description, and keywords

### Quick Actions

The first row of the list holds four tiles that are not commands from the catalogue:

| Tile | What it creates |
|------|-----------------|
| **Dynamic template** | Text with tokens for clipboard, selection, UUID or caret position (see [Per-Key Actions](per-key-actions.md)) |
| **Custom Text** | Any text, up to 4096 characters |
| **Send Intent** | An Android intent (activity, service or broadcast) |
| **Timestamp** | The current date/time in a pattern you choose (see [Timestamp Keys](timestamp-keys.md)) |

The row scrolls away with the list. While you search, only tiles whose name matches stay.

### Browse Categories

The 228 commands are grouped into these categories, in this order. Category headers stay
at the top while their commands scroll.

| Category | Commands | Examples |
|----------|---------:|----------|
| **Clipboard** | 13 | `copy`, `copy_private`, `paste`, `cut`, `selectAll`, `pasteAsPlainText`, `shareText`, `clear_clipboard`, `paste_pinned_1`–`5` |
| **Editing** | 6 | `undo`, `redo`, `clear`, `autofill`, `append_possessive`, `append_apostrophe` |
| **Cursor Movement** | 8 | `cursor_left`, `cursor_right`, `cursor_up`, `cursor_down` |
| **Navigation** | 6 | `home`, `end`, `doc_home`, `doc_end`, `page_up`, `page_down` |
| **Selection** | 4 | `selection_cursor_left`, `selection_cursor_right`, `selection_cancel`, `selection_mode` |
| **Delete** | 5 | `backspace`, `delete`, `delete_word`, `forward_delete_word`, `delete_last_word` |
| **Keyboard Events** | 19 | `config`, `switch_forward`, `switch_backward`, `switch_clipboard`, `switch_emoji`, `switch_numeric`, `voice_typing`, `minimize_bar`, `minimize_fab`, `hide_keyboard` |
| **Modifiers** | 5 | `shift`, `ctrl`, `alt`, `meta`, `fn` |
| **Function Keys** | 12 | `f1`–`f12` |
| **Special Keys** | 9 | `esc`, `enter`, `tab`, `menu`, `insert`, `compose`, `removed` |
| **Media Controls** | 12 | `media_play_pause`, `media_next`, `volume_up`, `volume_mute` |
| **System & Apps** | 10 | `brightness_up`, `zoom_in`, `search`, `calculator`, `calendar`, `contacts` |
| **Spaces & Formatting** | 10 | `space`, `nbsp`, `zwj`, `zwnj`, `lrm`, `rlm` |
| **Diacritics** | 44 | `accent_aigu`, `accent_grave`, `accent_circonflexe`, `accent_tilde` |
| **Slavonic Diacritics** | 10 | combining marks such as `combining_pokrytie` |
| **Arabic Diacritics** | 14 | combining marks such as `combining_shaddah`, `combining_sukun` |
| **Hebrew Marks** | 20 | `qamats`, `patah`, `sheva`, `dagesh` |
| **Text Input** | 8 | bracket pairs such as `b(`, `b[`, `b{` |
| **Language** | 2 | `primaryLangToggle`, `secondaryLangToggle` |
| **Text Actions** | 3 | `textAssist`, `replaceText`, `showTextMenu` |
| **Timestamps** | 8 | `timestamp_date`, `timestamp_time`, `timestamp_iso` |

### Select an Action

1. Tap any command to select it
2. The command is assigned to the selected swipe direction
3. Confirm the display label to save the assignment

## Clipboard, Hide and Minimize Commands

Search for **Clear system clipboard** (`clear_clipboard`) to assign an action that
empties Android’s current clipboard. Saved CleverKeys history, pins, todos, and media
are preserved. Use the clipboard panel’s **Select** mode and confirmed **Delete selected** action
to remove saved clippings instead.

Three Keyboard Events commands shrink or close the keyboard:

| Command | Effect | To get the keyboard back |
|---------|--------|--------------------------|
| **Minimize to Bar** (`minimize_bar`) | A thin full-width strip replaces the keyboard; the app resizes to sit above it | Tap the strip |
| **Minimize to Floating Button** (`minimize_fab`) | A round button replaces the keyboard, at the bottom right (bottom left with a right-to-left language); the app gets the whole screen and touches outside the button reach the app | Tap the button |
| **Hide Keyboard** (`hide_keyboard`) | Closes the keyboard | Tap a text field |

A minimized keyboard returns to full size the next time it is shown after being hidden.
The minimize commands cannot bring back a keyboard the system has already hidden.

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

The table under [Browse Categories](#browse-categories) lists every category with
examples. To see the full list, open the palette and scroll, or search by name,
description or keyword.

## Tips for Customization

- **Search keywords**: Try "select", "delete", "cursor" to find related commands
- **Preview**: Command descriptions explain what each action does
- **Popular choices**: `copy`, `paste`, `home`, `end` are commonly assigned
- **Power user**: `selection_mode`, `doc_home`/`doc_end` and the function keys help in editors and terminals

> [!TIP]
> The palette lists 228 commands. Use search to find one quickly.

## Common Questions

### Q: Can I access the command palette directly?

A: No. It appears only when assigning or editing an action, from Per-Key Customization or the Subkey Popover. It is a selection tool, not a standalone command runner.

### Q: Can I run commands without assigning them?

A: Most commands can be run via their assigned keys or default key combinations. For example, Ctrl+C for copy, Ctrl+V for paste.

### Q: How do I know what commands are available?

A: Open Per-Key Customization, select any key and direction, and browse the full list in the command palette.

## Related Features

- [Per-Key Actions](per-key-actions.md) - Customize key swipe actions
- [Subkey Popover](../gestures/subkey-popover.md) - Assign and edit slots by holding a key
- [Shortcuts](../clipboard/shortcuts.md) - Keyboard shortcuts
- [Extra Keys](extra-keys.md) - Add function key row
