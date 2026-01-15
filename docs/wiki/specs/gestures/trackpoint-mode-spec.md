---
title: TrackPoint Mode - Technical Specification
user_guide: ../../gestures/trackpoint-mode.md
status: implemented
version: v1.2.7
---

# TrackPoint Mode Technical Specification

## Overview

TrackPoint mode provides joystick-style cursor control by holding navigation keys, with speed proportional to finger distance from center.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Pointer Handling | `Pointers.kt:1200-1400` | TrackPoint state machine |
| Haptic Feedback | `VibratorCompat.kt` | Activation feedback |
| Key Handler | `KeyEventHandler.kt` | Cursor movement events |

## State Machine

```
IDLE
  ↓ (touch nav key)
TOUCH_DOWN
  ↓ (long press timeout)
TRACKPOINT_ACTIVE ←→ (move finger → cursor moves)
  ↓ (lift finger)
IDLE
```

## Flag

```kotlin
// Pointers.kt
const val FLAG_P_TRACKPOINT_MODE = 0x800
```

## Activation Flow

1. User touches nav key (arrow or compose with nav subkeys)
2. Timer starts for long-press threshold
3. If finger moves < 30px before timeout: continue to activation
4. On timeout: set FLAG_P_TRACKPOINT_MODE, trigger haptic
5. Center position = current finger position

## Joystick Calculation

```kotlin
// Pointers.kt:~1280
fun handleTrackPointMove(ptr: Pointer) {
    val dx = ptr.x - trackPointCenter.x
    val dy = ptr.y - trackPointCenter.y

    val deadZone = 15f // pixels
    val maxDistance = keyWidth // typically ~80px

    // X axis
    if (abs(dx) > deadZone) {
        val speed = mapSpeed(abs(dx), deadZone, maxDistance)
        val delay = interpolateDelay(speed) // 200ms → 30ms
        if (dx < 0) sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
        else sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    // Y axis (independent)
    if (abs(dy) > deadZone) {
        // Similar calculation for up/down
    }
}
```

## Speed Interpolation

| Distance | Delay | Resulting Speed |
|----------|-------|-----------------|
| Dead zone (15px) | 200ms | 5 chars/sec |
| Mid (40px) | 100ms | 10 chars/sec |
| Edge (80px) | 30ms | 33 chars/sec |

## Configuration

| Setting | Key | Default |
|---------|-----|---------|
| **Long Press Timeout** | `longpress_timeout` | 400ms |
| **TrackPoint Haptic** | `haptic_trackpoint_activate` | true |

## Haptic Events

Uses `HapticEvent.TRACKPOINT_ACTIVATE`:
- Effect: `VibrationEffect.EFFECT_CLOCK_TICK`
- Fallback: 30ms vibration at amplitude 80

## Related Specifications

- [Selection Delete Mode](../../../specs/selection-delete-mode.md) - Similar joystick pattern
- [Cursor Navigation](../../../specs/cursor-navigation-system.md) - Regular cursor movement
- [Gesture System](../../../specs/gesture-system.md) - Overall gesture handling
