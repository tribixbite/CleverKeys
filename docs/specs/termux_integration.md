# Termux Integration

## Overview

CleverKeys provides gesture typing and cursor control within terminal emulators (Termux) through a hybrid input architecture. When the target package is `com.termux`, the keyboard switches from standard InputConnection APIs to raw key event simulation for reliable operation.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/InputCoordinator.kt` | `InputCoordinator` | Context detection, text commitment, deletion strategy |
| `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | `moveCursorFallback()` | Raw key event simulation for cursor movement |
| `src/main/kotlin/tribixbite/cleverkeys/NeuralSwipeTypingEngine.kt` | Swipe recognition | ONNX-based recognition (Play Services independent) |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `termux_mode_enabled` | Feature toggle |

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

### Termux Detection

```kotlin
// InputCoordinator.kt
fun isTermuxContext(): Boolean {
    val packageName = currentInputConnection?.editorInfo?.packageName
    return packageName == "com.termux" && Config.globalConfig().termux_mode_enabled
}
```

### Text Commitment

Both modes use `InputConnection.commitText()` for insertion (safe in terminals).

**Automatic spacing difference:**
- Standard apps: Auto-space after typed words
- Termux mode: Auto-spacing disabled to prevent double-space in command lines
- Exception: Swipe gestures still add trailing spaces for typing flow

### Word Deletion (Ctrl+W Hack)

When correcting a prediction, the keyboard needs to delete the previously inserted word:

```kotlin
// Standard App
inputConnection.deleteSurroundingText(wordLength, 0)

// Termux Mode
KeyEventHandler.send_key_down_up(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON)
```

**Why Ctrl+W works:**
- Bash, zsh, and most shells bind `^W` to `backward-kill-word`
- Deletes using shell's own internal logic
- Perfect synchronization with terminal buffer

### Cursor Movement (DPAD Fallback)

```kotlin
// KeyEventHandler.kt
fun moveCursor(direction: Int) {
    val conn = inputConnection ?: return

    if (isTermuxContext() || !canSetSelection(conn)) {
        moveCursorFallback(direction)  // Use arrow keys
    } else {
        // Standard: setSelection()
        val pos = getCursorPos(conn)
        conn.setSelection(pos.selectionEnd + direction, pos.selectionEnd + direction)
    }
}

private fun moveCursorFallback(direction: Int) {
    val keyCode = if (direction < 0) {
        KeyEvent.KEYCODE_DPAD_LEFT
    } else {
        KeyEvent.KEYCODE_DPAD_RIGHT
    }

    repeat(abs(direction)) {
        send_key_down_up(keyCode, 0)
    }
}
```

**Why DPAD works:**
- Simulates physical arrow key presses
- Correctly interpreted by terminal emulators
- Works in programs like Vim, Nano, Emacs

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
| `termux_mode_enabled` | Boolean | true | Enable Termux-specific input handling |

## Behavior Comparison

| Operation | Standard Apps | Termux Mode |
|-----------|---------------|-------------|
| Insert text | `commitText()` | `commitText()` |
| Delete word | `deleteSurroundingText()` | `Ctrl+W` key event |
| Move cursor | `setSelection()` | `DPAD_LEFT/RIGHT` events |
| Auto-space | Enabled | Disabled |
| Swipe space | Normal | Enabled (exception) |
