---
title: TrackPoint Mode - Technical Specification
description: Joystick-style cursor control activated by holding a navigation key.
user_guide: ../../gestures/trackpoint-mode.md
status: implemented
version: v1.4.0
---

# TrackPoint Mode Technical Specification

## Overview

TrackPoint Navigation Mode provides joystick-style cursor control by holding arrow/nav keys (without initial swipe movement). When activated, finger movement in any direction moves the cursor proportionally, with speed scaling based on distance from the activation point. Supports diagonal motion (X and Y axes fire independently in the same repeat cycle) for fluid text navigation.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Pointers | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | TrackPoint state machine, `handleTrackPointRepeat()` |
| Haptic Feedback | `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt` | `TRACKPOINT_ACTIVATE` haptic on mode entry |
| Key Handler | `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | Routes the synthesised nav-key events |
| Config | `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `keyrepeat_enabled`, `longPressTimeout`, haptic toggle |

## State Flag

```kotlin
// Pointers.kt:1713
const val FLAG_P_TRACKPOINT_MODE = 1 shl 10  // = 0x400 = 1024
```

## State Machine

```
IDLE
  ↓ (touch nav key)
TOUCH_DOWN (FLAG_P_DEFERRED_DOWN set, long-press timer armed)
  ↓ (long-press timer fires AND key has nav subkeys)
TRACKPOINT_ACTIVE ←→ (move finger → cursor moves via independent X/Y axes)
  ↓ (lift finger)
IDLE
```

## Architecture

```
User Input (arrow key touch + hold without movement)
       |
       v
+------------------+
| onTouchDown()    | -- Records initial touch position
+------------------+
       |
       v (no initial movement + hold time exceeded)
+------------------+
| TrackPoint Mode  | -- FLAG_P_TRACKPOINT_MODE activated
| Activation       | -- TRACKPOINT_ACTIVATE haptic feedback
+------------------+
       |
       v
+------------------+
| handleTrackPoint | -- Repeating handler tracks position
| Repeat()         | -- Reads ptr.lastX/lastY each tick
+------------------+
       |
       v
+------------------+
| Cursor Movement  | -- Sends nav subkey (key5..key8) via onPointer{Down,Up}
| Handler          | -- Diagonals = both X and Y subkeys fire
+------------------+
```

## Activation Code

Note: unlike a separate "movement threshold" gate, the current implementation **always** enters TrackPoint mode when long-press fires on a key that has navigation subkeys — the user can move the finger to a comfortable position first, then hold.

```kotlin
// Pointers.kt:1182 (inside handleLongPress)
if (_config.keyrepeat_enabled && hasNavSubkeys) {
    ptr.flags = (ptr.flags and FLAG_P_DEFERRED_DOWN.inv()) or FLAG_P_TRACKPOINT_MODE
    // Store CURRENT finger position as joystick center (not initial touch point)
    // This allows user to move finger to comfortable position before TrackPoint activates
    ptr.keyCenterX = ptr.lastX
    ptr.keyCenterY = ptr.lastY
    // Vibrate to indicate TrackPoint mode activation
    _handler.onPointerFlagsChanged(HapticEvent.TRACKPOINT_ACTIVATE)
    // Start joystick repeat timer - will continuously check finger position
    startTrackPointRepeat(ptr)
    return
}
```

## Joystick Repeat Handler

```kotlin
// Pointers.kt:1083 - handleTrackPointRepeat()
private fun handleTrackPointRepeat(ptr: Pointer) {
    if (!ptr.hasFlagsAny(FLAG_P_TRACKPOINT_MODE)) {
        return
    }

    // Calculate X and Y distances from key center independently
    val dx = ptr.lastX - ptr.keyCenterX
    val dy = ptr.lastY - ptr.keyCenterY
    val absDx = abs(dx)
    val absDy = abs(dy)

    // Get key size for normalization
    val keyHypotenuse = _handler.getKeyHypotenuse(ptr.key)
    val maxDistance = keyHypotenuse * 0.5f  // Half of diagonal is edge from center

    var moved = false
    var maxNormalizedDist = 0f

    // Check horizontal movement (X axis)
    if (absDx > TRACKPOINT_DEAD_ZONE) {
        val key = ptr.key
        val horizKey = if (dx > 0) {
            // Moving right - key[6] is East
            _handler.modifyKey(key.keys[6], ptr.modifiers)
        } else {
            // Moving left - key[5] is West
            _handler.modifyKey(key.keys[5], ptr.modifiers)
        }

        if (horizKey != null && isNavigationKey(horizKey)) {
            _handler.onPointerDown(horizKey, false)
            _handler.onPointerUp(horizKey, ptr.modifiers)
            moved = true
            maxNormalizedDist = maxOf(maxNormalizedDist, min(absDx / maxDistance, 1.0f))
        }
    }

    // Check vertical movement (Y axis)
    if (absDy > TRACKPOINT_DEAD_ZONE) {
        val key = ptr.key
        val vertKey = if (dy > 0) {
            // Moving down - key[8] is South
            _handler.modifyKey(key.keys[8], ptr.modifiers)
        } else {
            // Moving up - key[7] is North
            _handler.modifyKey(key.keys[7], ptr.modifiers)
        }

        if (vertKey != null && isNavigationKey(vertKey)) {
            _handler.onPointerDown(vertKey, false)
            _handler.onPointerUp(vertKey, ptr.modifiers)
            moved = true
            maxNormalizedDist = maxOf(maxNormalizedDist, min(absDy / maxDistance, 1.0f))
        }
    }

    // Calculate repeat delay based on maximum displacement on either axis
    val repeatDelay = if (moved) {
        // Delay ranges from TRACKPOINT_MAX_DELAY (at dead zone) to TRACKPOINT_MIN_DELAY (at edge)
        (TRACKPOINT_MAX_DELAY - (maxNormalizedDist * (TRACKPOINT_MAX_DELAY - TRACKPOINT_MIN_DELAY))).toLong()
    } else {
        // Finger is in dead zone - check again after a short delay
        TRACKPOINT_MAX_DELAY
    }

    val what = uniqueTimeoutWhat++
    ptr.trackpointWhat = what
    _longpress_handler.sendEmptyMessageDelayed(what, repeatDelay)
}
```

## Subkey Direction Mapping

Direction is **not** computed from an angle/octant lookup; it's the sign of `dx` and `dy` independently. Each axis triggers the corresponding subkey on the touched key:

| Axis sign | Subkey index | Conceptual direction |
|-----------|--------------|----------------------|
| `dx > 0`  | `key.keys[6]` | East / right |
| `dx < 0`  | `key.keys[5]` | West / left |
| `dy > 0`  | `key.keys[8]` | South / down |
| `dy < 0`  | `key.keys[7]` | North / up |

Diagonals are produced naturally: if both `absDx > TRACKPOINT_DEAD_ZONE` and `absDy > TRACKPOINT_DEAD_ZONE`, both subkeys fire in the same repeat tick.

## Speed Scaling

Repeat delay is linearly interpolated from `TRACKPOINT_MAX_DELAY` at the dead-zone edge to `TRACKPOINT_MIN_DELAY` at the key edge, using the **maximum** normalized displacement across the two axes:

```kotlin
// Pointers.kt:1147
(TRACKPOINT_MAX_DELAY - (maxNormalizedDist * (TRACKPOINT_MAX_DELAY - TRACKPOINT_MIN_DELAY))).toLong()
```

| Position | Delay | Resulting Speed |
|----------|-------|-----------------|
| Just outside dead zone | 200ms | ~5 events/sec |
| Mid-key | ~115ms | ~9 events/sec |
| At key edge | 30ms | ~33 events/sec |

## TrackPoint Constants

```kotlin
// Pointers.kt:1718-1731
const val TRACKPOINT_MOVEMENT_THRESHOLD = 15f  // Min movement (px) to trigger nav event
const val TRACKPOINT_DEAD_ZONE = 15f           // No movement if finger within this distance
const val TRACKPOINT_INITIAL_DELAY = 50L       // Initial delay before first repeat
const val TRACKPOINT_MIN_DELAY = 30L           // Fastest repeat (finger at edge)
const val TRACKPOINT_MAX_DELAY = 200L          // Slowest repeat (finger just outside dead zone)
```

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `keyrepeat_enabled` | Boolean | true | Master toggle for TrackPoint / key-repeat / selection-delete |
| `longpress_timeout` | Int | 600 | Hold duration (ms) before long-press fires and TrackPoint engages |
| `haptic_trackpoint_activate` | Boolean | true | Trigger haptic when entering TrackPoint or selection-delete mode |
| `short_gesture_min_distance` | Int | 15 | Pixels of movement that classify a touch as a short swipe (used by selection-delete; TrackPoint itself does not gate on this) |

## Haptic Feedback

Entry into TrackPoint mode triggers `HapticEvent.TRACKPOINT_ACTIVATE`, gated by `config.haptic_trackpoint_activate`:

```kotlin
// VibratorCompat.kt:105 + 143 + 176
HapticEvent.TRACKPOINT_ACTIVATE -> config.haptic_trackpoint_activate
HapticEvent.TRACKPOINT_ACTIVATE -> { /* VibrationEffect.EFFECT_CLOCK_TICK */ }
HapticEvent.TRACKPOINT_ACTIVATE -> 15L  // legacy fallback amplitude duration (ms)
```

`EFFECT_CLOCK_TICK` provides a subtle, distinct feel different from normal key-press vibration.

## Nav Key Exclusion from Swipe Typing

Arrow / nav subkeys are excluded from short-gesture path collection so that holding them activates TrackPoint instead of triggering an unintended swipe gesture. The activation gate at `Pointers.kt:1182` checks `hasNavigationSubkeys(ptr)` — keys without nav subkeys never enter this branch.

## Error Handling

- No navigation target: cursor movement commands are sent but have no visible effect
- Haptic unavailable: mode still functions, just without feedback
- `modifyKey` returns null or non-navigation key: that axis is skipped this tick (no spurious typing)

## Related Specifications

- [Selection Delete Mode](selection-delete-spec.md) - Similar joystick pattern on backspace
- [Cursor Navigation](cursor-navigation-spec.md) - Regular spacebar-slider cursor movement
