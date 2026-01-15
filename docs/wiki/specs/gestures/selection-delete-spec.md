---
title: Selection Delete - Technical Specification
user_guide: ../../gestures/selection-delete.md
status: implemented
version: v1.2.7
---

# Selection Delete Technical Specification

## Overview

Backspace swipe-hold gesture that enables joystick-style text selection, deleting selected text on release.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Pointers | `Pointers.kt:1000-1200` | Selection mode state machine |
| KeyEventHandler | `KeyEventHandler.kt` | Shift+Arrow key simulation |
| Config | `Config.kt` | Vertical threshold/speed settings |

## State Flag

```kotlin
// Pointers.kt
const val FLAG_P_SELECTION_DELETE_MODE = 0x1000
```

## Activation Flow

```
Touch backspace
    ↓
Short swipe detected (any direction)
    ↓
Hold (don't release)
    ↓
After delay, set FLAG_P_SELECTION_DELETE_MODE
    ↓
Haptic feedback (CLOCK_TICK)
    ↓
Selection mode active
```

## Activation Code

```kotlin
// Pointers.kt:~1050
private fun checkSelectionDeleteActivation(ptr: Pointer) {
    if (ptr.key.value != KeyValue.Event(Event.BACKSPACE)) return
    if (ptr.flags and FLAG_P_SHORT_SWIPE == 0) return

    // Short swipe + hold detected
    handler.postDelayed({
        if (ptr.isDown && ptr.flags and FLAG_P_SELECTION_DELETE_MODE == 0) {
            ptr.flags = ptr.flags or FLAG_P_SELECTION_DELETE_MODE
            selectionDeleteCenter = PointF(ptr.x, ptr.y)
            triggerHaptic(HapticEvent.TRACKPOINT_ACTIVATE)
        }
    }, SELECTION_DELETE_ACTIVATION_DELAY)
}
```

## Joystick Movement

```kotlin
// Pointers.kt:~1100
private fun handleSelectionDeleteRepeat(ptr: Pointer) {
    val dx = ptr.x - selectionDeleteCenter.x
    val dy = ptr.y - selectionDeleteCenter.y

    val keyHeight = ptr.key.height
    val verticalThreshold = keyHeight * config.selection_delete_vertical_threshold / 100f
    val verticalSpeed = config.selection_delete_vertical_speed

    // Horizontal axis (character selection)
    if (abs(dx) > DEAD_ZONE) {
        val delay = calculateDelay(abs(dx))
        val keyCode = if (dx < 0)
            KeyEvent.KEYCODE_DPAD_LEFT
        else
            KeyEvent.KEYCODE_DPAD_RIGHT

        sendShiftArrow(keyCode)
        scheduleRepeat(delay)
    }

    // Vertical axis (line selection) - independent
    if (abs(dy) > verticalThreshold) {
        val delay = calculateDelay(abs(dy)) / verticalSpeed
        val keyCode = if (dy < 0)
            KeyEvent.KEYCODE_DPAD_UP
        else
            KeyEvent.KEYCODE_DPAD_DOWN

        sendShiftArrow(keyCode)
        scheduleRepeat(delay.toLong())
    }
}
```

## Shift+Arrow Simulation

```kotlin
// Pointers.kt:~1150
private fun sendShiftArrow(keyCode: Int) {
    // Create shift modifier
    val shiftMod = makeInternalModifier(Modifier.SHIFT)

    // Send key with shift held
    val ic = inputConnection ?: return
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

## Release and Delete

```kotlin
// Pointers.kt:~1180
private fun handleSelectionDeleteRelease(ptr: Pointer) {
    if (ptr.flags and FLAG_P_SELECTION_DELETE_MODE == 0) return

    // Delete selected text (simulates backspace on selection)
    val ic = inputConnection ?: return
    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))

    ptr.flags = ptr.flags and FLAG_P_SELECTION_DELETE_MODE.inv()
}
```

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Vertical Threshold** | `selection_delete_vertical_threshold` | 40% | 20-80 |
| **Vertical Speed** | `selection_delete_vertical_speed` | 0.4 | 0.1-1.0 |

## Speed Calculation

```kotlin
// Speed proportional to distance from center
fun calculateDelay(distance: Float): Long {
    val minDelay = 30L   // Fastest repeat
    val maxDelay = 200L  // Slowest repeat
    val maxDistance = keyWidth

    val normalized = (distance / maxDistance).coerceIn(0f, 1f)
    return (maxDelay - (normalized * (maxDelay - minDelay))).toLong()
}
```

## Related Specifications

- [TrackPoint Mode](trackpoint-mode-spec.md) - Similar joystick pattern
- [Cursor Navigation](cursor-navigation-spec.md) - Arrow key handling
- [Selection Delete Mode](../../../specs/selection-delete-mode.md) - Full spec
