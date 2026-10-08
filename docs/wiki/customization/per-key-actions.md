---
title: Per-Key Actions
description: Customize what each swipe direction does
category: Customization
difficulty: advanced
related_spec: ../specs/customization/per-key-actions-spec.md
---

# Per-Key Actions

Customize what happens when you swipe in each of the 8 directions from any key. Assign characters, actions, or macros to each swipe direction.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Customize subkey actions per direction |
| **Access** | Settings > Activities > Customize Per-Key Actions, or hold a key with the [Subkey Popover](../gestures/subkey-popover.md) on |
| **Directions** | 8 directions (N, NE, E, SE, S, SW, W, NW) |

## Understanding Subkeys

Each key has up to 8 subkey positions:

```
    NW   N   NE
      \  |  /
   W -- KEY -- E
      /  |  \
    SW   S   SE
```

When you short swipe in a direction, the corresponding subkey is activated. With the
[Subkey Popover](../gestures/subkey-popover.md) on, holding the key shows the same eight
slots around your finger.

## How to Customize

### From Settings

1. Open CleverKeys Settings (gear icon)
2. In the **Activities** section, tap **Customize Per-Key Actions**
3. The screen shows your actual keyboard. **Tap the key** you want to customize; a
   magnified key opens with its 8 directions and any existing mappings
4. **Tap a direction**. The [Command Palette](command-palette.md) opens:
   - for an empty direction, pick a command, or one of the quick actions (Dynamic
     template, Custom Text, Send Intent, Timestamp);
   - for a direction you already customized, the palette opens in that action's editor,
     filled in. Back out of the editor to choose a different action
5. Confirm the label shown on the key. The mapping is saved immediately

The list of your custom mappings on the key screen lets you delete one; the reset button
in the top bar clears all of them at once, without asking for confirmation.

### From the Keyboard (Subkey Popover)

With **Subkey popover on hold** turned on (Settings > Gesture Tuning):

1. **Hold** the key until the popover opens
2. To add an action, slide to an empty slot (shown as **+**) and let go. The command
   palette opens for that key and direction
3. To change an action, rest your finger on its slot for 3 seconds. The edit screen offers
   **Edit**, **Reassign**, **Restore default** (when your action covers a layout subkey)
   and **Remove**

Both routes edit the same mappings. See [Subkey Popover](../gestures/subkey-popover.md).

## Available Actions

| Action | Description |
|--------|-------------|
| **Characters** | Any Unicode character |
| **Delete Word** | Delete previous word |
| **Cursor Left/Right** | Move cursor |
| **Home/End** | Jump to line start/end |
| **Tab** | Insert tab character |
| **Escape** | Send escape key |
| **Undo/Redo** | Undo or redo action |
| **Copy/Cut/Paste** | Clipboard operations (terminal-aware — see below) |
| **Select All** | Select all text |
| **Append 's / Append apostrophe** | Attach an exact suffix to the last verified word; Backspace undoes only that suffix |
| **Dynamic template** | Expand clipboard, selection, UUID or a caret marker (see below) |
| **Timestamp** | Insert current date/time using a SimpleDateFormat pattern (see below) |

### Timestamp Action

The **Timestamp** action type inserts the current date and/or time, formatted via a [SimpleDateFormat](https://developer.android.com/reference/java/text/SimpleDateFormat) pattern. The pattern is the action value; at swipe time the current `Date` is formatted with the system default locale and the result is committed to the text field.

| Pattern | Example output |
|---------|----------------|
| `yyyy-MM-dd HH:mm` | `2026-05-22 14:30` |
| `yyyy-MM-dd` | `2026-05-22` |
| `HH:mm` | `14:30` |
| `yyyy-MM-dd'T'HH:mm:ssXXX` | `2026-05-22T14:30:00+00:00` (ISO 8601) |

Any valid SimpleDateFormat pattern works. The Command Palette dialog also offers a few preset chips covering the common date/time formats.

## Dynamic Templates (development build)

Choose **Dynamic template** in the command palette, or enable that option in the
custom-text editor. The per-key screen and popover assignment use the same editor.
Existing **Custom text** mappings remain literal, including text like `{uuid}`.

| Token | Result |
|-------|--------|
| `{clipboard}` | Current plain clipboard text; media/URI clips are refused |
| `{selection}` | Selected text, or empty text at a confirmed collapsed caret |
| `{uuid}` | One UUIDv4 per invocation; repeated tokens share that UUID |
| `{cursor}` | One caret marker, removed from the inserted text |

For example, `[{selection}]({cursor})` wraps a selected label and leaves the caret
inside the parentheses. `{{uuid}}` inserts the literal text `{uuid}`. Tokens inside
clipboard or selected text are inserted literally; they are not expanded again.

Templates are limited to 4,096 UTF-16 units, and expanded text to 65,536. Unknown
tokens, unmatched braces, duplicate caret markers and unavailable token inputs are
refused before editing. The editor must provide selection/content readback; password
fields and active clipboard, emoji or GIF editors refuse these actions. Assignment
previews never read your clipboard or selection. Template text is not automatically
added to personalized vocabulary.

If an app accepts the text but refuses caret movement, the text may remain inserted
and the action reports failure. CleverKeys does not retry or erase an uncertain edit.
An app can partially accept an edit; inspect the field before invoking it again.

Custom-layout syntax: `{}:template:'[{selection}]({cursor})'`, or
`:template symbol='{}':'[{selection}]({cursor})'`. XML export preserves the explicit
TEMPLATE type, quotes and backslashes; mappings and backups retain the same type.

## Common Customizations

### Adding Frequently Used Symbols

Place symbols you use often in easy-to-reach positions:

| Key | Direction | Suggestion |
|-----|-----------|------------|
| **e** | South | @ (email) |
| **s** | East | $ (currency) |
| **p** | North | % (percent) |

### Programming Shortcuts

For developers:

| Key | Direction | Action |
|-----|-----------|--------|
| **Shift** | NW | Escape |
| **Shift** | SE | Tab |
| **a** | NW | Home |
| **a** | SW | End |

### Navigation Optimization

Put navigation actions where you can reach them:

| Key | Direction | Action |
|-----|-----------|--------|
| **Backspace** | West | Delete Word |
| **Space** | Subkeys | Cursor movement |

### Pinned Clipboard Actions

Insert pinned clipboard entries directly via swipe gestures, without opening the clipboard panel. Five positional commands are available:

| Command | Description |
|---------|-------------|
| `paste_pinned_1` | Insert 1st pinned clipboard entry |
| `paste_pinned_2` | Insert 2nd pinned clipboard entry |
| `paste_pinned_3` | Insert 3rd pinned clipboard entry |
| `paste_pinned_4` | Insert 4th pinned clipboard entry |
| `paste_pinned_5` | Insert 5th pinned clipboard entry |

Pinned entries are ordered **most-recently-pinned first**. If you request an index that exceeds the number of pinned items (e.g., `paste_pinned_3` with only 2 pins), a toast notification tells you how many are available instead of inserting anything.

To set up:

1. Go to **Settings > Activities > Customize Per-Key Actions** and tap a key and direction,
   or let go on an empty slot of the [Subkey Popover](../gestures/subkey-popover.md)
2. In the command palette, search for "paste_pinned" or browse the **Clipboard** category
3. Select the desired slot (1-5)

> [!TIP]
> Pin your most-used text snippets (email signature, address, code boilerplate) and bind them to swipe directions for instant insertion.

### Terminal-Aware Actions

**Paste** adapts when you type in a terminal app such as Termux:

| Action | Standard Apps | Terminal Apps |
|--------|---------------|---------------|
| **Paste** | Android paste action | Types the current clipboard text directly |
| **Copy** | Android copy action | Same |
| **Cut** | Android cut action | Same |

Terminals usually ignore Android's paste action, so CleverKeys inserts the clipboard text
itself. This is automatic for known terminal apps; add others under **Settings > Advanced >
Custom terminal packages** (see [Advanced Settings](../settings/advanced.md)).

### Custom Text Input

When using the **Text Input** action type, you can enter up to **4096 characters** per action. This is useful for long templates, code snippets, or multi-line text blocks.

The text input field includes a **paste button** (icon in the trailing position) for pasting from the system clipboard. This is provided because Compose dialogs do not always receive paste commands from the IME reliably.

## Tips and Tricks

- **Start small**: Customize a few keys first, learn them, then add more
- **Muscle memory**: Keep frequently used actions in consistent positions
- **Backup**: Export your customizations before making major changes
- **Per-layout**: Customizations can be layout-specific

> [!TIP]
> Consider your typing patterns. If you frequently type certain symbols, put them in easy-to-swipe positions.

## Resetting Customizations

To restore defaults:

1. Go to **Settings > Activities > Backup & Restore**
2. Use the restore function to reset to default configuration

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Customize Per-Key Actions** | Activities section | Visual subkey editor |
| **Subkey popover on hold** | Gesture Tuning | Assign and edit slots from the keyboard |
| **Backup & Restore** | Activities section | Save/restore customizations |

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - How to trigger subkeys
- [Backup & Restore](../troubleshooting/backup-restore.md) - Save customizations
- [Extra Keys](extra-keys.md) - Add custom keys to bottom row

## Technical Details

See [Per-Key Actions Technical Specification](../specs/customization/per-key-actions-spec.md).

## Explicit Apostrophe Suffix Commands (development build)

Assign **Append 's** or **Append apostrophe** to a short swipe, popover slot, ordinary
layout key or Extra Keys. After `James`, Append 's produces `James's`; after `parents`,
Append apostrophe produces `parents'`. The command makes the spelling explicit and
does not infer singular/plural grammar. Literal apostrophe flicks and TEXT snippets
continue to use their existing behavior.

The command requires the exact last word and caret position to remain verified.
It attaches before that word's single owned trailing space, or inserts no space when
the original commit was spaceless. It refuses selected ranges, password fields,
inline editors and stale editor/word ownership. Observed caret movement, changing
fields or preferences, typing another character, or invoking a duplicate suffix
invalidates attachment. Android does not identify which operation caused a selection
callback: an identical manual move during the brief owned-callback window can be
ambiguous, so cross-editor manual testing remains necessary.

Immediately press Backspace to undo only the suffix, preserving the base word and
its spacing. A refused or partial undo consumes that key press so whole-word swipe
undo cannot delete the word underneath it. An uncertain editor write can leave text
or a selection changed; there is no blind retry or compensating deletion.

Personalized learning replaces the exact owned commit with the full suffixed word.
Undo uses a fresh identity to replace it back; fragments like `s` are not separately
learned. If a learning receipt expires after the text edit, the text remains and
learning context is cleared rather than inventing another observation. Owned counts
can be reversed; old timestamps and vocabulary evictions are not restored.
