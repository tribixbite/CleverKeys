# Termux Integration

## Overview

CleverKeys provides gesture typing and cursor control within terminal emulators (Termux) through a hybrid input architecture. `TerminalUtils` supplies shared built-in and user-configured package detection for suggestion correction, undo, delete-word and paste. Text insertion still uses `commitText`; deletion uses terminal key events and paste commits clipboard text directly. Cursor movement has its own editor-capability fallback.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/TerminalUtils.kt` | `editorKind(editorInfo, customPackages)` → `EditorKind { TEXT, TERMINAL, REMOTE }` (`:73-110`), `isTerminalApp` | THE blind-field detector |
| `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | `isTerminalStyleEditor()` (`:643`), `handlePaste` / `pasteFromSystemClipboard`, `deleteBeforeCursor`, `moveCursorFallback()` (`:1496`) | deletion, paste, undo and cursor-movement routing for terminal-style editors |
| `src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt` | `isTerminalEditor`, `TerminalSuggestionPolicy` | no-rewrite suggestion/autocorrect policy |
| `src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt` | Swipe recognition | ONNX-based recognition (Play Services independent) |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `custom_terminal_packages` | Validated immutable package snapshot |
| same | `parseCustomPackages` | Shared custom-package parsing (settings, Config, backup validator) |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ TerminalUtils.editorKind(editorInfo, custom_terminal_packages)│
│   → TEXT | TERMINAL | REMOTE   (the one detector)           │
└─────────────────────────────────────────────────────────────┘
                            │
              ┌─────────────┴─────────────┐
              │                           │
              ▼                           ▼
┌─────────────────────────┐   ┌─────────────────────────────────┐
│ TEXT (ordinary editor)  │   │ TERMINAL / REMOTE (blind field) │
│ KeyEventHandler:        │   │ KeyEventHandler:                │
│ • deleteSurroundingText │   │ • KEYCODE_DEL × n               │
│ • performContextMenu    │   │ • Ctrl+W (TERMINAL) /           │
│   Action(paste)         │   │   Ctrl+Backspace (REMOTE)       │
│ • setSelection()        │   │ • paste = commit OS clip text   │
│ SuggestionHandler:      │   │ • DPAD arrows (moveCursorFallback)│
│ • full autocorrect      │   │ SuggestionHandler:              │
│                         │   │ • no rewrite; taps append only  │
└─────────────────────────┘   └─────────────────────────────────┘
```

(`InputCoordinator.kt` still exists but owns swipe-decode requests, trace capture and
cursor-sync bookkeeping — not terminal detection or commits; see its class KDoc.)

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

### Blind text fields (remote desktop / TYPE_NULL), 2026-10-13

A BLIND field forwards what the IME writes to another screen and cannot be read back or
edited in place. `TerminalUtils.editorKind(editorInfo, customPackages)` is the one
detector and returns `TEXT`, `TERMINAL` or `REMOTE`:

| Order | Signal | Kind |
|------:|--------|------|
| 1 | package in the user's custom terminal list (exact) | `TERMINAL` |
| 2 | built-in terminal package / Termux, anotherterm, `.terminal` heuristics | `TERMINAL` |
| 3 | built-in remote-input package (`com.carriez.flutter_hbb`, RustDesk) | `REMOTE` |
| 4 | `inputType == InputType.TYPE_NULL` (AVNC, bVNC, any non-text editor) | `REMOTE` |
| — | otherwise | `TEXT` |

`isTerminalApp` = kind is not `TEXT`; every no-rewrite branch asks it, so `REMOTE` gets the
whole terminal policy: no space autocorrect, no I-capitalization, no swipe-final
autocorrect, typed-word suggestion taps that append and never delete
(`TerminalSuggestionPolicy`, see Typed-Word Tracking), swipe-word replacement and
backspace-undo by `KEYCODE_DEL` × n (`KeyEventHandler.deleteBeforeCursor`, also used by the
smart-punctuation and double-space swallows), paste and paste-as-plain-text commit the system clip, `clear` and
the `'s`/`'` suffix commands are refused, the typed word ends on non-text keys, cursor
sync (`PredictionContextTracker.synchronizeWithCursor`) and the #78 editor scan never read
the field. Only delete-last-word differs: `TERMINAL` sends Ctrl+W, `REMOTE` Ctrl+Backspace
(Ctrl+W closes a remote window or tab).

**Why (RustDesk source, master `0b5a3d74`, `flutter/lib/mobile/pages/remote_page.dart`):**
the remote-desktop view's hidden multiline field starts as `'1' * 1024` (L28), is not reset
while typing, and is forwarded as a LENGTH-ONLY diff (L328-L372): longer → the tail is
typed remotely; shorter → ONE `VK_BACK` whatever the count; equal → nothing. So
`deleteSurroundingText(n)` + `commitText(fix)` garbles the remote (`teh` → `tehthe `), a
readback scan reads the 1024 placeholders as a word, and `KEYCODE_DEL` key events (routed
through `RawKeyFocusScope`) are the only exact deletion. Its remote-terminal view (xterm
4.0.0) resets an empty field after every commit, so there `deleteSurroundingText` does
nothing at all. `performContextMenuAction(paste)` works in the desktop view but committed
clip text works in both; a Ctrl+V key event would paste the REMOTE clipboard (RustDesk
syncs the phone clip only when its app resumes).

**Not done, deliberately:** no readback probe (an empty ordinary field also reads "", so a
probe would misclassify every new message box); remote packages are listed only when their
input code was read. Users add other apps through the custom terminal list, which gives
`TERMINAL` semantics (Ctrl+W) — right for RustDesk used mainly as a remote shell.
Tests: `BlindFieldPolicyTest` (RustDesk length-diff model; asserts what the remote
receives), `TerminalUtilsTest`.

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

Swipe-word replacement and swipe undo use repeated native Backspace events for
terminal targets, rather than document deletion APIs. They do not blindly issue
Ctrl+W for every replacement. Typed-word suggestion taps delete nothing in terminals
(Typed-Word Tracking below).

**Why Ctrl+W works for delete-last-word:**
- Bash, zsh, and most shells bind `^W` to `backward-kill-word`
- Deletes using shell's own internal logic
- The shell/line editor owns the deletion semantics

### Typed-Word Tracking and Suggestions

A terminal exposes no readable text buffer (`getTextBeforeCursor` returns "") and sends no
selection updates, so every editor-sync path reads nothing. The keystroke tracker in
`PredictionContextTracker` is the only record of the word being typed, and the suggestion bar,
a tapped suggestion (which appends what follows it, never deletes) and swipe undo all act on it.

- **Letters extend it; Backspace shrinks it**; an emptied word clears the bar and cancels its
  queued prediction.
- **Any non-text key ends it** (2026-10-07): Enter, Tab, Esc, arrows, Home/End, Page keys,
  Ctrl/Alt chords (Ctrl+C, Ctrl+U, Ctrl+W), the IME action, cursor sliders and editing
  commands. `KeyEventHandler` reports them through `IReceiver.handle_non_text_input`;
  `SuggestionHandler.handleNonTextInput` resets the tracker, swipe/autocorrect undo targets,
  the queued prediction and the bar, for terminals only (ordinary editors re-sync from the
  selection callback that follows). Delete-last-word (Ctrl+W) does the same. Before this,
  `ls` Enter `cd` tracked `lscd`.
- **A prediction is painted only for the word it was computed for**; one that finishes after
  the tracked word changed is dropped (all editors).
- **No autocorrect rewrites in terminals**: neither the space-time typed-word autocorrect nor
  the swipe final autocorrect runs (`ls` is never committed as `is`).
- **A suggestion tap never deletes** (2026-10-15, `TerminalSuggestionPolicy`): the bar offers
  only words that EXTEND the tracked word (case-insensitive prefix), shown with the letters
  the user typed (`HEl` → `HEllo`); a tap commits only the remainder (`hel` + `hello` →
  `lo `). A word that would need a deletion — a correction, a contraction such as `dont` →
  `don't`, a chip left over from a changed word — is refused and nothing is sent; with no
  extension the bar is cleared. `+word` (exact add) adds the tracked word to the dictionary
  and commits only a space. Applies to `TERMINAL` and `REMOTE` editors; ordinary editors are
  unchanged.

**Why taps may not delete — what Termux lets the IME observe** (termux-app master
[`8629e632`](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java)):

| Signal an IME could use | Termux behaviour |
|---|---|
| `getTextBeforeCursor` / `getExtractedText` | `onCreateInputConnection` returns a `BaseInputConnection` whose `commitText` sends the text to the terminal and then clears its `Editable`: reads return "" (the screen and the shell's line are never exposed) |
| `onUpdateSelection` | `TerminalView` never calls `InputMethodManager.updateSelection`; no cursor callbacks |
| `deleteSurroundingText(n, 0)` | sent as `n` `KEYCODE_DEL` key events, like the IME's own key events |
| extra-keys toolbar (TAB, ESC, arrows, HOME/END …) | handled inside Termux and written to the terminal; the IME receives nothing |
| toolbar CTRL / ALT | applied INSIDE `commitText`: `sendTextToTerminal` → `inputCodePoint`, which reads `mClient.readControlKey()`. The IME's `commitText("c")` succeeds exactly like a typed letter while the shell receives ^C — **undetectable from the IME** |
| shell completion, history recall, prompt redraw, `^C` | changes only the terminal screen; nothing reaches the IME |

So nothing observable bounds what is still on the line, and "delete the tracked word's
length" can remove text that is not the word. Device evidence (Saga, CleverKeys release
`7aea1ab8`, Termux 0.118.3, 2026-10-15, screenshots `build/oct15-saga/04`–`14`):

- **D2**: `foo`, Termux's toolbar CTRL, CleverKeys `c` → the terminal shows `foo^C` and a
  fresh prompt, while the bar shows `food | foods | flock | foochow` — predictions for
  `fooc`, so the IME saw a typed `c`. (CleverKeys' own Ctrl+C is a key event and would have
  cleared the bar.) Typing `bar` tracked `foocbar` (`foolhardy | foodborne | +foocbar`);
  tapping `foodborne` sent seven Backspaces at a three-letter line and left `foodborne `.
- **D4**: `ec`, then TAB → the shell completed `echo `, but the bar still showed
  `ec | economy | economic | echo` — unchanged, so the IME saw no key (Termux's toolbar ⇥;
  CleverKeys' own Tab ends the word and clears the bar, `TerminalTypedWordTrackingTest`).
  `x` then tracked `ecx` over a line ending `echo x`; a tap on `except` would have deleted
  `o x` and left `echexcept `.

Rejected alternatives: a deletion budget of "characters CleverKeys committed since the last
boundary" (every character in D2 was a CleverKeys commit — Termux altered it after
acceptance); a time-based boundary (guesswork either way); readback probes (Termux reads ""
always). Appending can be wrong when the tracker is stale (extra letters after the line), but
it is visible and undone with Backspace; a deletion destroys text the user cannot see coming.

**Residual risk, deliberately kept:** replacing a swipe-committed word from the bar and the
swipe backspace-undo still send `KEYCODE_DEL` for that one atomic commit (`word` + space). If
a Termux toolbar key or CTRL acts between the swipe and the tap, those Backspaces can reach
other text. The window is one gesture wide, versus every keystroke of a typed word.

Known limit: text the keyboard did not type (shell tab completion, history recall, a redrawn
prompt, a backspace past the tracked word, Termux's toolbar keys) is invisible to the tracker;
the next CleverKeys non-text key resets it. Until then the bar may offer extensions of a word
that is no longer on the line; a tap then appends, never deletes.

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
| Typed-word autocorrect | Setting | Never |
| Word ends on non-text key | Via selection re-sync | Enter/Tab/Esc/arrows/Ctrl chords reset it |
| Swipe space | Normal | Enabled (exception) |
