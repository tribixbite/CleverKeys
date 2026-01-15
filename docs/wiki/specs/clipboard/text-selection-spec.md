---
title: Text Selection - Technical Specification
user_guide: ../../clipboard/text-selection.md
status: implemented
version: v1.2.7
---

# Text Selection Technical Specification

## Overview

Text selection integrates with Selection-Delete mode, TrackPoint navigation, and modifier key shortcuts to provide efficient text selection capabilities.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| SelectionHandler | `SelectionHandler.kt` | Selection coordination |
| Pointers | `Pointers.kt:1000-1200` | Selection-delete gesture |
| TrackPointController | `TrackPointController.kt` | Cursor navigation |
| ModifierHandler | `ModifierHandler.kt` | Shift selection |
| Config | `Config.kt` | Selection preferences |

## Selection Methods

### Selection-Delete Mode

```kotlin
// Pointers.kt:~1100
private fun handleSelectionDeleteMovement(ptr: Pointer) {
    val dx = ptr.x - selectionDeleteCenter.x
    val dy = ptr.y - selectionDeleteCenter.y

    // Horizontal selection (characters)
    if (abs(dx) > DEAD_ZONE) {
        val direction = if (dx < 0)
            KeyEvent.KEYCODE_DPAD_LEFT
        else
            KeyEvent.KEYCODE_DPAD_RIGHT

        sendShiftArrow(direction)
        scheduleRepeat(calculateDelay(abs(dx)))
    }

    // Vertical selection (lines)
    val verticalThreshold = keyHeight * config.selection_vertical_threshold / 100f
    if (abs(dy) > verticalThreshold) {
        val direction = if (dy < 0)
            KeyEvent.KEYCODE_DPAD_UP
        else
            KeyEvent.KEYCODE_DPAD_DOWN

        sendShiftArrow(direction)
        scheduleRepeat(calculateDelay(abs(dy)) / config.selection_vertical_speed)
    }
}

private fun sendShiftArrow(keyCode: Int) {
    val ic = inputConnection ?: return

    // Send Shift+Arrow to select
    ic.sendKeyEvent(KeyEvent(
        SystemClock.uptimeMillis(),
        SystemClock.uptimeMillis(),
        KeyEvent.ACTION_DOWN,
        keyCode,
        0,
        KeyEvent.META_SHIFT_ON
    ))
    ic.sendKeyEvent(KeyEvent(
        SystemClock.uptimeMillis(),
        SystemClock.uptimeMillis(),
        KeyEvent.ACTION_UP,
        keyCode,
        0,
        KeyEvent.META_SHIFT_ON
    ))
}
```

### TrackPoint with Shift

```kotlin
// TrackPointController.kt
private fun handleTrackPointMovement(dx: Float, dy: Float) {
    val isShiftHeld = modifierHandler.isShiftActive()

    // Calculate direction
    val direction = calculatePrimaryDirection(dx, dy)

    if (isShiftHeld) {
        // Selection mode - send Shift+Arrow
        sendShiftArrow(directionToKeyCode(direction))
    } else {
        // Cursor mode - send plain Arrow
        sendArrowKey(directionToKeyCode(direction))
    }
}
```

### Modifier Key Selection

```kotlin
// ModifierHandler.kt
class ModifierHandler {
    private var activeModifiers = 0

    fun onShiftPressed() {
        activeModifiers = activeModifiers or META_SHIFT_ON
    }

    fun onShiftReleased() {
        activeModifiers = activeModifiers and META_SHIFT_ON.inv()
    }

    fun isShiftActive(): Boolean {
        return activeModifiers and META_SHIFT_ON != 0
    }

    fun applyModifiersToKeyEvent(keyCode: Int): KeyEvent {
        return KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            activeModifiers
        )
    }
}
```

## Selection Actions

### Select All

```kotlin
// SelectionHandler.kt
fun selectAll() {
    val ic = inputConnection ?: return

    // Method 1: InputConnection API
    ic.performContextMenuAction(android.R.id.selectAll)

    // Fallback: Send Ctrl+A
    // ic.sendKeyEvent(KeyEvent(ACTION_DOWN, KEYCODE_A, 0, META_CTRL_ON))
}
```

### Word Selection

```kotlin
// SelectionHandler.kt
fun selectWord() {
    val ic = inputConnection ?: return

    // Get text around cursor
    val before = ic.getTextBeforeCursor(50, 0) ?: return
    val after = ic.getTextAfterCursor(50, 0) ?: ""

    // Find word boundaries
    val wordStart = before.indexOfLast { !it.isLetterOrDigit() } + 1
    val wordEnd = after.indexOfFirst { !it.isLetterOrDigit() }.let {
        if (it < 0) after.length else it
    }

    // Select the word
    ic.setSelection(
        ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.length?.minus(before.length - wordStart) ?: 0,
        (ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.length ?: 0) + wordEnd
    )
}
```

## Speed Calculation

```kotlin
// SelectionHandler.kt
private fun calculateRepeatDelay(distance: Float): Long {
    // Proportional to distance from center
    val maxDistance = keyWidth
    val normalized = (distance / maxDistance).coerceIn(0f, 1f)

    // Inverse relationship: further = faster
    val minDelay = 30L   // Fastest (far from center)
    val maxDelay = 200L  // Slowest (close to center)

    return (maxDelay - (normalized * (maxDelay - minDelay))).toLong()
}
```

## Selection State

```kotlin
// SelectionHandler.kt
data class SelectionState(
    val hasSelection: Boolean,
    val selectionStart: Int,
    val selectionEnd: Int,
    val selectedText: String?
)

fun getSelectionState(): SelectionState {
    val ic = inputConnection ?: return SelectionState(false, 0, 0, null)

    val selected = ic.getSelectedText(0)
    val hasSelection = selected?.isNotEmpty() == true

    return SelectionState(
        hasSelection = hasSelection,
        selectionStart = -1,  // Would need to track
        selectionEnd = -1,
        selectedText = selected?.toString()
    )
}
```

## Clipboard Integration

```kotlin
// SelectionHandler.kt
fun copySelection() {
    inputConnection?.performContextMenuAction(android.R.id.copy)
}

fun cutSelection() {
    inputConnection?.performContextMenuAction(android.R.id.cut)
}

fun deleteSelection() {
    val ic = inputConnection ?: return
    val selected = ic.getSelectedText(0)
    if (selected?.isNotEmpty() == true) {
        ic.commitText("", 1)  // Replace selection with empty
    }
}
```

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Vertical Threshold** | `selection_vertical_threshold` | 40 | 20-80 (% of key height) |
| **Vertical Speed** | `selection_vertical_speed` | 0.4 | 0.1-1.0 |
| **Selection Haptics** | `haptic_selection` | true | bool |
| **Dead Zone** | `selection_dead_zone` | 10 | dp |

## Related Specifications

- [Selection Delete Mode](../gestures/selection-delete-spec.md) - Gesture details
- [TrackPoint Mode](../gestures/trackpoint-mode-spec.md) - Cursor navigation
- [Clipboard History](clipboard-history-spec.md) - Copy integration
