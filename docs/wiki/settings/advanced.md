---
title: Advanced Settings
description: Terminal and remote-desktop app handling, the default-keyboard reminder, debug logging and suggestion origin markers
category: Settings
difficulty: advanced
---

# Advanced Settings

The **Advanced** section comes near the end of CleverKeys Settings, after Privacy. Most people never need
it. It holds terminal and remote-desktop handling and a few diagnostic options.

## Quick Summary

| What | Description |
|------|-------------|
| **Access** | Settings > Advanced (expand the section) |
| **Main use** | Make CleverKeys treat an extra app as a terminal (or a remote screen) |
| **Also here** | Default keyboard reminder, debug logging, suggestion origin markers |

## Terminal Apps

Terminal emulators handle text differently from ordinary text fields: they do not let a
keyboard read what is already on the line, and most ignore Android's paste action. When
CleverKeys detects a terminal, it changes a few behaviours.

### What Changes in a Terminal or Remote Screen

Remote-desktop and VNC apps have the same problem as terminals: what you type goes to
another screen, and the app's text field on the phone is "blind". CleverKeys cannot read
it back or change text in it (RustDesk, for example, forwards only how much longer or
shorter its hidden field got). These apps get the terminal behaviour below, with one
difference for **Delete last word**.

| Behaviour | Ordinary apps | Terminal apps | Remote-desktop apps |
|-----------|---------------|---------------|---------------------|
| **Paste** and **Paste as plain text** (key, short swipe or popover slot) | Android paste action | Types the current clipboard text directly | Types the phone's clipboard text directly |
| **Delete last word** | Deletes the word in the field | Sends Ctrl+W, so the shell deletes the word itself | Sends Ctrl+Backspace (Ctrl+W would close a remote window) |
| **Autocorrect while typing** | Follows your autocorrect settings | Never rewrites a word (`ls` stays `ls`) | Never rewrites a word |
| **Capitalizing "i"** | Follows your setting | Never rewrites a word | Never rewrites a word |
| **Autocorrect after a swipe** | Follows your settings | Skipped; the recognised word is inserted as is | Skipped |
| **Word suggestions** | Shown | Shown for the word you are typing; tapping one deletes it with Backspace key presses and types the suggestion | Same as terminals |
| **Backspace undo of a swipe** | Removes the word | Removes the word with one Backspace key press per character | Same as terminals |
| **Clear field** | Selects all and deletes | Does nothing (the keyboard cannot see the text) | Does nothing |
| **Append 's / '** | Adds the suffix to the last word | Not available | Not available |
| **Automatic spaces** | Your preference | Your preference (not changed by terminal detection) | Your preference |

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

### Which Apps Count as Terminals or Remote Screens

Remote screens detected automatically:

- RustDesk (`com.carriez.flutter_hbb`), for both remote desktops and its remote terminal
- Any app whose text field tells Android it is not a text editor (input type "null"), such
  as the VNC viewers AVNC and bVNC

Terminals detected automatically:

- Termux and apps whose package name contains `termux`
- ConnectBot, JuiceSSH, Termius, Android Terminal Emulator, Better Terminal Pro and the
  Android Linux terminal (`com.android.virtualization.terminal`)
- `com.rbrq.terminal`, `com.rk.terminal` and apps whose package name contains `anotherterm`
- Apps whose package name ends in `.terminal` or contains `.terminal.` or `.terminalemulator`

### Custom Terminal Packages

If your terminal, SSH or remote-desktop app is not detected, add it here. Apps on this list
get the terminal behaviour, including Ctrl+W for **Delete last word**. That suits a remote
shell (add RustDesk here if you mostly use it to control a terminal) but not a remote
desktop, where Ctrl+W closes the current window.

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
