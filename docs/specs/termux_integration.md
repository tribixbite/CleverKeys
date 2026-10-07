# Termux Integration

## Overview

CleverKeys provides gesture typing and cursor control within terminal emulators (Termux) through a hybrid input architecture. `TerminalUtils` supplies shared built-in and user-configured package detection for suggestion correction, undo, delete-word and paste. Text insertion still uses `commitText`; deletion uses terminal key events and paste commits clipboard text directly. Cursor movement has its own editor-capability fallback.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/InputCoordinator.kt` | `InputCoordinator` | Context detection, text commitment, deletion strategy |
| `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | `moveCursorFallback()` | Raw key event simulation for cursor movement |
| `src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt` | Swipe recognition | ONNX-based recognition (Play Services independent) |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `custom_terminal_packages` | Validated immutable package snapshot |
| `src/main/kotlin/tribixbite/cleverkeys/TerminalUtils.kt` | `parseCustomPackages`, `isTerminalApp` | Shared package parsing and detection |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      InputCoordinator                        │
│  Detects target package, routes to appropriate handler       │
└─────────────────────────────────────────────────────────────┘
                            │
              ┌─────────────┴─────────────┐
              │                           │
              ▼                           ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│     Standard Apps       │   │      Termux Mode        │
│                         │   │                         │
│ • deleteSurrounding     │   │ • Ctrl+W (delete word)  │
│ • setSelection()        │   │ • DPAD arrow keys       │
│ • commitText()          │   │ • commitText()          │
└─────────────────────────┘   └─────────────────────────┘
```

## The Problem

Standard Android keyboards rely on `InputConnection` methods:
- `setSelection(start, end)` for cursor positioning
- `deleteSurroundingText(before, after)` for deletion

**Why this fails in Termux:**
1. Terminal emulators maintain their own internal buffer
2. Buffer often desynchronizes from Android's `Editable` exposed to IME
3. `deleteSurroundingText` fails against buffers with ANSI escape codes or prompt text
4. `setSelection` is ignored by terminals expecting escape sequences or arrow keys

## Implementation Details

### Terminal Detection and Custom Packages

`SuggestionHandler.isTerminalEditor` passes its Config snapshot to
`TerminalUtils.isTerminalApp(editorInfo, customPackages)`. Regular and custom paste
pass the current global Config snapshot to the same predicate. Defaults preserve
known package and ecosystem heuristics; custom additions match exact package names
with case preserved. Null editor/package information yields false.

**Advanced → Custom terminal packages** accepts comma/newline-separated app IDs.
The shared parser trims whitespace and deduplicates, requires at least two dotted
ASCII identifier segments (each starts with a letter), and enforces 100 distinct
IDs, 255 characters per ID and 32,768 characters per draft. No wildcards or installed-app
scan are used. Any invalid entry rejects the whole draft; Save is disabled and shows
an error. Cancel/back changes nothing. Empty Save removes custom matches while
preserving built-in detection. Dialog and draft survive configuration changes.

`custom_terminal_packages` is stored as a canonical newline-separated String,
empty by default. `Config.refresh` parses once into an immutable, volatile snapshot;
invalid legacy/corrupt values fall back to no additions. Normal preference notifications
refresh live IME routing, and a settings Save publishes immediately in the same process.
The backup import validator uses the same parser and rejects invalid values/types.
Defaults/export/reset classify this key; search includes the translated button title,
terminal/package/SSH keywords and exact scroll target.

This setting changes the shared correction/deletion/paste decision; it does not
change automatic spacing or force cursor positioning. The older "Terminal Mode"
switch was removed on 2026-10-07: nothing read it (its last consumer went with the
unified pipeline), and it did not show the Ctrl/Meta/PageUp/Down keys its label
promised. `termux_mode_enabled` is now a `SettingsValidation.DEPRECATED_KEYS`
tombstone so old backups do not re-import it (`DeadPlumbingDriftTest`).

### Text Commitment

Both modes use `InputConnection.commitText()` for insertion (safe in terminals).

**Automatic spacing:** suggestion spaces follow the existing user preferences;
terminal detection does not automatically suppress them.

### Word Deletion and Correction

The explicit delete-last-word command uses the terminal’s Ctrl+W behavior:

```kotlin
// Standard App
inputConnection.deleteSurroundingText(wordLength, 0)

// Termux Mode
KeyEventHandler.send_key_down_up(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON)
```

Suggestion replacement and swipe undo use repeated native Backspace events for
terminal targets, rather than document deletion APIs. They do not blindly issue
Ctrl+W for every replacement.

**Why Ctrl+W works for delete-last-word:**
- Bash, zsh, and most shells bind `^W` to `backward-kill-word`
- Deletes using shell's own internal logic
- The shell/line editor owns the deletion semantics

### Cursor Movement (DPAD Fallback)

`KeyEventHandler.moveCursor` has its own selection/capability fallback. The
`moveCursorForceFallback` flag handles password variations and Godot editors;
otherwise the handler attempts selection updates where possible and falls back
to directional key events. Custom terminal packages do not force this flag.
Programs inside a terminal can interpret those key events differently, so check
cursor movement in the actual shell/editor instead of assuming package detection
proves every cursor operation.

### Slider Gesture (Spacebar Cursor)

The spacebar acts as a slider for cursor control:

```kotlin
// Horizontal swipe on spacebar generates CURSOR_LEFT/CURSOR_RIGHT
// In Termux mode, these trigger moveCursorFallback()
// Each pixel-distance unit fires one DPAD key event
```

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `custom_terminal_packages` | String | empty | Add exact custom package matches to shared terminal routing |

### Paste Operation

Terminal apps often do not implement `performContextMenuAction(paste)`, and Ctrl+V
is not reliably intercepted through the IME. Regular paste (`KeyEventHandler`) and
custom paste (`CustomShortSwipeExecutor`) therefore use the shared package predicate:
terminal targets read the system clip, coerce its first item to text and call
`commitText`; ordinary editors retain the Android context-menu paste operation.
Null/empty clipboard data does not insert text. Each external terminal still needs
its own device check; package detection alone cannot prove editor behavior.

## Behavior Comparison

| Operation | Standard Apps | Termux Mode |
|-----------|---------------|-------------|
| Insert text | `commitText()` | `commitText()` |
| Delete word | `deleteSurroundingText()` | `Ctrl+W` key event |
| Move cursor | Selection/capability fallback | Selection/capability fallback; target-editor validation required |
| Paste | `performContextMenuAction(paste)` | Clipboard text via `commitText()` |
| Auto-space | User preference | User preference |
| Swipe space | Normal | Enabled (exception) |
