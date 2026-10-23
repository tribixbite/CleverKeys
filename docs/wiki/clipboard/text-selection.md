---
title: Text Selection
description: Select text with the Selection-Delete gesture, Shift plus arrow keys, Ctrl+A, or commands assigned to key swipes
category: Clipboard
difficulty: intermediate
---

# Text Selection

CleverKeys selects text the way a hardware keyboard would: it sends Shift+arrow and Ctrl
key combinations, or the editor's own select-all/copy/cut actions. The app you are typing in
draws the selection and its handles, so what you can select depends on that app.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Select text to copy, cut, delete or replace |
| **Methods** | Selection-Delete gesture, Shift + arrow keys, Ctrl+A, assigned commands |
| **Where** | Any text field that reports its cursor to the keyboard (terminals and remote-desktop fields do not) |

## Method 1: Selection-Delete gesture

Short-swipe on backspace and keep your finger down. After a moment the keyboard vibrates
and selection mode starts: move your finger to extend the selection, lift it to delete what
is selected. **Settings > Gesture Tuning > Selection Movement** chooses between the
joystick style (default: further from the start point selects faster) and **Follow finger**
(one character per step of travel; moving back shrinks the selection, step size adjustable).

See [Selection Delete](../gestures/selection-delete.md) for the full gesture.

## Method 2: Shift + arrow keys

1. Tap **Shift** once (it stays latched for the next key; tap twice to lock it).
2. Use an arrow key: the navigation key between the spacebar and Enter has up, down, left
   and right on its swipe directions, you can add arrow keys through
   **Settings > Activities > Configure Extra Keys**, or hold the navigation key to enter
   [TrackPoint Mode](../gestures/trackpoint-mode.md).
3. With Shift active the arrows extend the selection instead of moving the cursor.

The spacebar cursor slider ([Cursor Navigation](../gestures/cursor-navigation.md)) moves the
cursor only; it does not select.

## Method 3: Select all

- Tap **Ctrl**, then **A** (Ctrl is sticky: tap it, then the letter).
- Or assign the **Select All** command (`selectAll`) to a key swipe in
  **Settings > Activities > Short Swipe Calibration > Customize Per-Key Actions**.

## Working with the selection

| Action | Ctrl shortcut | Assignable command |
|--------|---------------|--------------------|
| Copy | Ctrl+C | `copy` |
| Copy to CleverKeys only ([Private Copy](private-copy.md)) | — | `copy_private` |
| Cut | Ctrl+X | `cut` |
| Paste | Ctrl+V | `paste`, `pasteAsPlainText` |
| Share | — | `shareText` |
| Undo / Redo | Ctrl+Z / Ctrl+Y | `undo`, `redo` |
| Delete | Backspace | — |
| Replace | Type anything | — |

Whether a shortcut works is up to the editor: CleverKeys sends the key combination, and
most text fields implement these. The **Show Text Menu** command asks the editor to open its
own cut/copy/paste toolbar on the word at the cursor.

## Settings

| Setting | Section | Default |
|---------|---------|---------|
| **Selection Movement** | Gesture Tuning | Joystick (speed by distance) |
| **Step Size** | Gesture Tuning | 12 dp per character (follow-finger mode only) |

## Related Features

- [Selection Delete](../gestures/selection-delete.md) - The select-and-delete gesture
- [TrackPoint Mode](../gestures/trackpoint-mode.md) - Joystick cursor on the navigation key
- [Cursor Navigation](../gestures/cursor-navigation.md) - Spacebar cursor slider
- [Clipboard Shortcuts](shortcuts.md) - Copy, cut, paste, undo and redo
- [Clipboard History](clipboard-history.md) - Where copied text goes
