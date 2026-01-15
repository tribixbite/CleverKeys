---
title: Cursor Navigation - Technical Specification
user_guide: ../../gestures/cursor-navigation.md
status: implemented
version: v1.2.7
---

# Cursor Navigation Technical Specification

## Overview

Cursor navigation through spacebar slider gesture and arrow key handling with repeat acceleration.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Pointers | `Pointers.kt:600-800` | Spacebar slider detection |
| KeyEventHandler | `KeyEventHandler.kt` | Arrow key event dispatch |
| InputCoordinator | `InputCoordinator.kt` | Cursor position tracking |
| Config | `Config.kt` | Slider sensitivity settings |

## Spacebar Slider

### Detection Logic

```kotlin
// Pointers.kt:~650
private fun handleSpacebarSlide(ptr: Pointer) {
    if (ptr.key.value != KeyValue.Event(Event.SPACE)) return

    val dx = ptr.x - ptr.downX
    val threshold = config.spacebar_slider_threshold

    if (abs(dx) > threshold) {
        val direction = if (dx > 0) DIRECTION_RIGHT else DIRECTION_LEFT
        val steps = (abs(dx) / threshold).toInt()

        for (i in 0 until steps) {
            sendCursorMove(direction)
        }

        // Reset baseline for continuous sliding
        ptr.downX = ptr.x
    }
}
```

### Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Slider Enabled** | `spacebar_slider_enabled` | true | boolean |
| **Slider Threshold** | `spacebar_slider_threshold` | 20px | 10-50 |
| **Slider Sensitivity** | `spacebar_slider_sensitivity` | 1.0 | 0.5-2.0 |

## Arrow Key Handling

### Single Press

```kotlin
// KeyEventHandler.kt:~300
fun handleArrowKey(direction: Direction) {
    val keyCode = when (direction) {
        Direction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        Direction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        Direction.UP -> KeyEvent.KEYCODE_DPAD_UP
        Direction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
    }
    sendKeyEvent(keyCode)
}
```

### Repeat with Acceleration

```kotlin
// Pointers.kt:~700
private fun startArrowRepeat(ptr: Pointer, direction: Direction) {
    ptr.flags = ptr.flags or FLAG_P_NAV_HOLD_REPEAT

    var delay = config.key_repeat_initial_delay // 400ms
    val minDelay = config.key_repeat_min_delay   // 30ms
    val acceleration = 0.8f

    handler.postDelayed({
        if (ptr.flags and FLAG_P_NAV_HOLD_REPEAT != 0) {
            handleArrowKey(direction)
            delay = max(minDelay, (delay * acceleration).toLong())
            scheduleNextRepeat(delay)
        }
    }, delay)
}
```

## Key Events Sent

| Direction | KeyEvent | Android Constant |
|-----------|----------|------------------|
| **Left** | DPAD_LEFT | 21 |
| **Right** | DPAD_RIGHT | 22 |
| **Up** | DPAD_UP | 19 |
| **Down** | DPAD_DOWN | 20 |
| **Home** | MOVE_HOME | 122 |
| **End** | MOVE_END | 123 |

## InputConnection Methods

```kotlin
// Alternative to key events for precise control
ic.setSelection(newPosition, newPosition)  // Move cursor
ic.getTextBeforeCursor(n, 0)               // Get context
ic.getTextAfterCursor(n, 0)                // Get context
```

## Word Navigation

With Ctrl modifier:

```kotlin
// KeyEventHandler.kt:~350
fun handleWordNavigation(direction: Direction, ic: InputConnection) {
    val text = when (direction) {
        Direction.LEFT -> ic.getTextBeforeCursor(100, 0)
        Direction.RIGHT -> ic.getTextAfterCursor(100, 0)
    } ?: return

    val boundary = findWordBoundary(text, direction)
    // Move cursor by boundary offset
}
```

## Related Specifications

- [TrackPoint Mode](trackpoint-mode-spec.md) - Continuous cursor movement
- [Selection Delete](selection-delete-spec.md) - Cursor with selection
- [Cursor Navigation System](../../../specs/cursor-navigation-system.md) - Full system spec
