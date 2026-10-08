---
title: Advanced Settings
description: Terminal app handling, the default-keyboard reminder, debug logging and suggestion origin markers
category: Settings
difficulty: advanced
---

# Advanced Settings

The **Advanced** section comes near the end of CleverKeys Settings, after Privacy. Most people never need
it. It holds terminal-app handling and a few diagnostic options.

## Quick Summary

| What | Description |
|------|-------------|
| **Access** | Settings > Advanced (expand the section) |
| **Main use** | Make CleverKeys treat an extra app as a terminal |
| **Also here** | Default keyboard reminder, debug logging, suggestion origin markers |

## Terminal Apps

Terminal emulators handle text differently from ordinary text fields: they do not let a
keyboard read what is already on the line, and most ignore Android's paste action. When
CleverKeys detects a terminal, it changes a few behaviours.

### What Changes in a Terminal

| Behaviour | Ordinary apps | Terminal apps |
|-----------|---------------|---------------|
| **Paste** (key, short swipe or popover slot) | Android paste action | Types the current clipboard text directly |
| **Delete last word** | Deletes the word in the field | Sends Ctrl+W, so the shell deletes the word itself |
| **Autocorrect while typing** | Follows your autocorrect settings | Never rewrites a word (`ls` stays `ls`) |
| **Autocorrect after a swipe** | Follows your settings | Skipped; the recognised word is inserted as is |
| **Word suggestions** | Shown | Shown for the word you are typing; tapping one replaces that word |
| **Automatic spaces** | Your preference | Your preference (not changed by terminal detection) |

Because the keyboard cannot read the terminal's line, it keeps track of the word you are
typing from your own keystrokes:

- Letters extend the word and Backspace shortens it. When the word is empty, the
  suggestion bar clears.
- Any key that is not text ends the word: Enter, Tab, Esc, the arrow keys, Home/End,
  Page Up/Down, Ctrl or Alt combinations (such as Ctrl+C), the Enter/action key, cursor
  slides on the spacebar and editing commands. So typing `ls`, Enter, `cd` gives
  suggestions for `cd`, not `lscd`.

> [!NOTE]
> Text that CleverKeys did not type is invisible to it: shell tab completion, history
> recall (Up arrow), a redrawn prompt, or backspacing past the start of the word you typed.
> The next non-text key resets the tracking.

### Which Apps Count as Terminals

These are detected automatically:

- Termux and apps whose package name contains `termux`
- ConnectBot, JuiceSSH, Termius, Android Terminal Emulator, Better Terminal Pro and the
  Android Linux terminal (`com.android.virtualization.terminal`)
- `com.rbrq.terminal`, `com.rk.terminal` and apps whose package name contains `anotherterm`
- Apps whose package name ends in `.terminal` or contains `.terminal.` or `.terminalemulator`

### Custom Terminal Packages

If your terminal or SSH app is not detected, add it here:

1. Open **Settings > Advanced**
2. Tap **Custom terminal packages**
3. Enter the app's exact package name (for example `com.example.ssh`), one per line or
   separated by commas
4. Tap **Save**

Rules:

| Rule | Limit |
|------|-------|
| Matching | Exact and case-sensitive; no wildcards |
| Format | At least two dot-separated parts, each starting with a letter (letters, digits and `_` only) |
| Count | Up to 100 different package names |
| Length | Up to 255 characters per name, 32,768 characters for the whole list |

If any entry is invalid, **Save** is disabled and the error names the entry; nothing is
saved until the whole list is valid. **Cancel** changes nothing. Saving an empty list
removes your additions; built-in detection always stays on. The count below the button
shows how many custom packages are saved. The list is included in backups.

To find an app's package name, open its page in Android Settings > Apps, or look at its
Play Store or F-Droid address (`id=` or the last part of the URL).

> [!TIP]
> Adding an app here only changes the behaviours in the table above. It does not add
> terminal keys (Ctrl, Esc, Tab, arrows); use [Extra Keys](../customization/extra-keys.md)
> for those.

## Other Advanced Settings

| Setting | Default | Description |
|---------|---------|-------------|
| **Default keyboard reminder** | On | Reminds you once per boot to set CleverKeys as the default keyboard. Turn off to never ask again. |
| **Debug Information** | Off | Shows detailed logging and performance metrics |
| **Swipe Debug Log** | Off | Real-time analysis of swipe gestures (needs logcat). When on, **Detailed Logging** adds verbose trace information and **Open Debug Log** opens the log screen. |
| **Suggestion Origin Markers** | Off | Adds a coloured dot to each suggestion showing which engine produced it. Long-press any suggestion for full details. |

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - Paste and other actions in terminals
- [Per-Key Actions](../customization/per-key-actions.md) - Terminal-aware paste
- [Next-Word Prediction](../typing/next-word-prediction.md) - Suggestion origin markers
- [Enabling the Keyboard](../getting-started/enabling-keyboard.md) - The default keyboard reminder
