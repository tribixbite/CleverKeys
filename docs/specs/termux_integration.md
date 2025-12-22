# Termux Integration & Gesture Handling Spec

## Overview

CleverKeys distinguishes itself from other Android keyboards by providing robust gesture typing and cursor control even within terminal emulators like Termux. This is achieved through a hybrid input architecture that detects the application context and switches between standard Android InputConnection APIs and raw hardware key event simulation.

## Core Problem

Standard Android keyboards (Gboard, SwiftKey, etc.) rely on `InputConnection` methods like `setSelection(start, end)` and `deleteSurroundingText(before, after)` for gesture typing corrections and cursor movement.

**Why this fails in Termux:**
Terminal emulators maintain their own internal state/buffer which often desynchronizes from the Android `Editable` buffer exposed to the IME.
1.  **Deletion:** `deleteSurroundingText` often fails or behaves erratically because the terminal interprets the deletion request against a buffer that may include ANSI escape codes or prompt text that the IME cannot see.
2.  **Cursor:** `setSelection` requests are often ignored by terminals, which expect cursor movement via escape sequences or arrow key events.

## Solution: "Termux Mode"

CleverKeys implements a specific `Termux Mode` (controlled via `Config.termux_mode_enabled`) that alters input handling when the target package is `com.termux`.

### 1. Gesture Typing (Swipe)

The implementation resides primarily in `src/main/kotlin/tribixbite/cleverkeys/InputCoordinator.kt`.

#### Architecture
*   **Neural Engine:** A custom ONNX-based neural network (`NeuralSwipeTypingEngine.kt`) processes swipe paths locally. This decouples recognition from system text manipulation.
*   **Text Commitment:**
    *   **Insertion:** Uses `InputConnection.commitText()`, which acts like standard typing and is safe in terminals.
    *   **Spacing:** In Termux Mode, automatic spacing after normal typing is disabled to prevent "double space" issues in command lines. However, for swipe gestures, trailing spaces are intelligently added to maintain typing flow.

#### The "Ctrl+W" Hack for Deletion
When a user selects a different prediction or corrects a word, the keyboard needs to delete the previously inserted word.
*   **Standard App:** Calls `InputConnection.deleteSurroundingText(length, 0)`.
*   **Termux:** Detects `com.termux` context and instead simulates a **Ctrl+W** key event.
    *   **Code:** `KeyEventHandler.send_key_down_up(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON)`
    *   **Mechanism:** Most shells (bash, zsh) bind `^W` to `backward-kill-word`. This effectively deletes the last word using the shell's own internal logic, ensuring perfect synchronization.

### 2. Cursor Movement (Swipe Selection)

Cursor control is implemented as a continuous "slider" gesture on the spacebar, handled in `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt`.

#### Architecture
*   **Slider Logic:** The spacebar is defined as a `slider` key. Horizontal swipes generate `CURSOR_LEFT` or `CURSOR_RIGHT` commands based on pixel distance.

#### The "Fallback" Hack for Movement
*   **Standard App:** Calls `InputConnection.setSelection(index)`.
*   **Termux:** Triggers `moveCursorForceFallback` mode.
    *   **Code:** `moveCursorFallback(direction)`
    *   **Mechanism:** Instead of setting an absolute cursor index, it fires repeated `KeyEvent.KEYCODE_DPAD_LEFT` or `KEYCODE_DPAD_RIGHT` events.
    *   **Result:** This simulates physical arrow key presses, which are correctly interpreted by the terminal emulator (and programs running inside it like Vim, Nano, or Emacs) to move the cursor.

## Key Files

| File | Responsibility |
|------|----------------|
| `InputCoordinator.kt` | "Brain" that manages text commitment, detects Termux context, and switches between `deleteSurroundingText` and Ctrl+W. |
| `KeyEventHandler.kt` | Handles raw key event simulation, manages the `moveCursorForceFallback` logic, and executes slider gestures. |
| `NeuralSwipeTypingEngine.kt` | Standalone swipe recognition engine (ONNX) that works without Google Play Services. |
