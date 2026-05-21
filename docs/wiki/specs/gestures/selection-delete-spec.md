---
title: Selection Delete - Technical Specification
description: Backspace swipe-and-hold joystick that selects text and deletes it on release.
user_guide: ../../gestures/selection-delete.md
status: implemented
version: v1.4.0
---

# Selection Delete Technical Specification

## Overview

Selection-Delete Mode is a gesture that enables text selection by swiping and holding on the backspace key. When activated, horizontal finger movement selects characters (left/right), vertical movement selects lines (up/down), and releasing the finger deletes all selected text. This provides a single fluid gesture for rapid text correction.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Pointers | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | Selection mode state machine, `handleSelectionDeleteRepeat()` |
| KeyEventHandler | `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | Shift+Arrow key simulation |
| Config | `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | Vertical threshold/speed settings |
| SettingsActivity | `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` | Threshold and speed sliders |
| VibratorCompat | `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt` | `TRACKPOINT_ACTIVATE` haptic on mode entry |

## State Flag

```kotlin
// Pointers.kt:1716
const val FLAG_P_SELECTION_DELETE_MODE = 1 shl 11  // = 0x800 = 2048
```

## Architecture

```
User Input (backspace key short swipe + hold)
       |
       v
+------------------+
| onTouchDown()    | -- Defers backspace for gesture detection
+------------------+
       |
       v
+------------------+
| Short Swipe      | -- Detects initial swipe direction
| Detection        |
+------------------+
       |
       v (if hold detected after swipe)
+------------------+
| FLAG_P_SELECTION | -- Sets mode flag, records activation center
| _DELETE_MODE     |
+------------------+
       |
       v
+------------------+
| handleSelection  | -- Timer-based repeat, sends Shift+Arrow keys
| DeleteRepeat()   |
+------------------+
       |
       v (on finger release)
+------------------+
| Delete selected  | -- Sends DEL key to remove selection
| text             |
+------------------+
```

## Data Flow

1. **Activation**: Short swipe on backspace key, then hold (don't lift finger)
2. **Selection**: Finger movement from activation center determines direction
   - Horizontal (dx): Shift+Left or Shift+Right for character selection
   - Vertical (dy): Shift+Up or Shift+Down for line selection (if threshold met)
3. **Speed Scaling**: Further from center = faster selection rate
4. **Release**: Sends DEL key to delete all selected text

## Activation Flow

```
Touch backspace
    ↓
Short swipe detected (any direction)
    ↓
Hold (don't release)
    ↓
After long-press timeout, set FLAG_P_SELECTION_DELETE_MODE
    ↓
Haptic feedback (TRACKPOINT_ACTIVATE)
    ↓
Selection mode active
```

## Activation Code

```kotlin
// Pointers.kt:1199 (inside handleLongPress)
val isBackspace = isBackspaceKey(ptr.value)
if (_config.keyrepeat_enabled && isBackspace) {
    if (movementDist >= _config.short_gesture_min_distance) {
        // User did a short swipe then held - enter selection-delete mode
        // Determine direction based on horizontal movement (left/right selection)
        val direction = if (dx > 0) 1 else -1  // 1 = right, -1 = left
        ptr.flags = (ptr.flags and FLAG_P_DEFERRED_DOWN.inv()) or FLAG_P_SELECTION_DELETE_MODE
        ptr.selectionDirection = direction
        // Store current position as reference for speed calculation
        ptr.keyCenterX = ptr.lastX
        ptr.keyCenterY = ptr.lastY
        // Vibrate to indicate selection mode activation
        _handler.onPointerFlagsChanged(HapticEvent.TRACKPOINT_ACTIVATE)
        // Start selection repeat timer
        startSelectionDeleteRepeat(ptr)
        return
    }
    // No significant movement - fall through to normal key repeat handling below
}
```

## Joystick Movement

```kotlin
// Pointers.kt:979 - handleSelectionDeleteRepeat()
private fun handleSelectionDeleteRepeat(ptr: Pointer) {
    if (!ptr.hasFlagsAny(FLAG_P_SELECTION_DELETE_MODE)) {
        return
    }

    // Calculate X and Y distances from activation center (like TrackPoint)
    val dx = ptr.lastX - ptr.keyCenterX
    val dy = ptr.lastY - ptr.keyCenterY
    val absDx = abs(dx)
    val absDy = abs(dy)

    // Get key dimensions for thresholds
    val keyHypotenuse = _handler.getKeyHypotenuse(ptr.key)
    // Approximate key height from hypotenuse (typical keyboard keys are wider than tall)
    val keyHeight = keyHypotenuse * 0.7f
    val maxDistance = keyHypotenuse * 0.5f

    // Vertical dead zone is configurable (% of key height)
    val verticalDeadZone = keyHeight * (_config.selection_delete_vertical_threshold / 100.0f)

    // Create modifiers with SHIFT for selection
    val shiftMod = KeyValue.makeInternalModifier(KeyValue.Modifier.SHIFT)
    val shiftedMods = ptr.modifiers.with_extra_mod(shiftMod)

    // Check horizontal movement (X axis) - select left/right
    // Uses standard TrackPoint dead zone for responsive horizontal selection
    if (absDx > TRACKPOINT_DEAD_ZONE) {
        val arrowKeyCode = if (dx > 0) {
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        } else {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT
        }
        // Symbol codes from KeyValue.kt: left=0xE008, right=0xE006
        val symbolCode = if (dx > 0) 0xE006 else 0xE008
        val arrowKey = KeyValue.keyeventKey(symbolCode, arrowKeyCode, 0)
        _handler.onPointerDown(arrowKey, false)
        _handler.onPointerUp(arrowKey, shiftedMods)
    }

    // Check vertical movement (Y axis) - select up/down (line-by-line)
    // Uses larger configurable dead zone to prevent accidental line selection
    if (absDy > verticalDeadZone) {
        val arrowKeyCode = if (dy > 0) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN
        } else {
            android.view.KeyEvent.KEYCODE_DPAD_UP
        }
        val symbolCode = if (dy > 0) 0xE007 else 0xE005
        val arrowKey = KeyValue.keyeventKey(symbolCode, arrowKeyCode, 0)
        _handler.onPointerDown(arrowKey, false)
        _handler.onPointerUp(arrowKey, shiftedMods)
    }
}
```

## Vertical Threshold Calculation

The vertical activation threshold is computed against the **key height**, not the horizontal distance — this prevents accidental line selection while still allowing diagonal multi-axis movement.

```kotlin
// Pointers.kt:997
val verticalDeadZone = keyHeight * (_config.selection_delete_vertical_threshold / 100.0f)
```

This means a configured value of `40` requires the finger to travel 40% of the key height vertically before line selection begins.

## Independent Axis Handling

X and Y axes fire independently within the same repeat cycle, allowing diagonal selection. Both are checked each repeat tick using their own dead zones (X uses `TRACKPOINT_DEAD_ZONE`, Y uses `verticalDeadZone` derived from config).

## Bidirectional Movement

Selection direction is determined each repeat cycle from the sign of `dx`/`dy` relative to the activation center stored at mode entry:

- Move finger left of center → Shift+Left
- Move finger right of center → Shift+Right
- Return to opposite side → selection shrinks/extends the other way

## Repeat-Delay Calculation

```kotlin
// Pointers.kt:1052
val repeatDelay = when {
    movedHorizontal && movedVertical -> {
        // Both axes moved - use faster of the two (horizontal dominates)
        val horizDelay = TRACKPOINT_MAX_DELAY - (maxHorizNormalizedDist * (TRACKPOINT_MAX_DELAY - TRACKPOINT_MIN_DELAY))
        val vertDelay = (TRACKPOINT_MAX_DELAY - (maxVertNormalizedDist * (TRACKPOINT_MAX_DELAY - TRACKPOINT_MIN_DELAY))) / _config.selection_delete_vertical_speed
        minOf(horizDelay, vertDelay).toLong()
    }
    movedHorizontal -> {
        // Horizontal only - standard speed
        (TRACKPOINT_MAX_DELAY - (maxHorizNormalizedDist * (TRACKPOINT_MAX_DELAY - TRACKPOINT_MIN_DELAY))).toLong()
    }
    movedVertical -> {
        // Vertical only - slower speed using multiplier (lower multiplier = slower)
        ((TRACKPOINT_MAX_DELAY - (maxVertNormalizedDist * (TRACKPOINT_MAX_DELAY - TRACKPOINT_MIN_DELAY))) / _config.selection_delete_vertical_speed).toLong()
    }
    else -> {
        // Finger is in dead zone - check again after a short delay
        TRACKPOINT_MAX_DELAY
    }
}
```

`TRACKPOINT_MIN_DELAY = 30L` (Pointers.kt:1728) and `TRACKPOINT_MAX_DELAY = 200L` (Pointers.kt:1731) define the fastest and slowest repeat rates.

## Configuration

| Setting | Key | Default | Range | Description |
|---------|-----|---------|-------|-------------|
| **Vertical Threshold** | `selection_delete_vertical_threshold` | 40 | 20-80 | % of key height required for vertical selection |
| **Vertical Speed** | `selection_delete_vertical_speed` | 0.4 | 0.1-1.0 | Speed multiplier for vertical selection |

Defaults are declared in `Config.kt:526-527`:

```kotlin
@JvmField var selection_delete_vertical_threshold = 40  // % of key height to trigger vertical selection
@JvmField var selection_delete_vertical_speed = 0.4f    // Speed multiplier for vertical (0.1-1.0)
```

## Shift+Arrow Simulation

Selection requires holding Shift while sending arrow keys. The handler builds an internal SHIFT modifier and merges it into the pointer's modifier set before posting the arrow keyevent:

```kotlin
// Pointers.kt:1000
val shiftMod = KeyValue.makeInternalModifier(KeyValue.Modifier.SHIFT)
val shiftedMods = ptr.modifiers.with_extra_mod(shiftMod)
// ... shiftedMods is passed to _handler.onPointerUp(arrowKey, shiftedMods)
```

## Release and Delete

When the finger lifts while `FLAG_P_SELECTION_DELETE_MODE` is set, the pending selection is committed and a DEL key event removes the selected text via the active `InputConnection`.

## Error Handling

- Invalid threshold values: clamped to 20-80% by the settings UI
- Invalid speed values: clamped to 0.1-1.0
- No text to select: arrow key commands are sent but have no visible effect (harmless)

## Related Specifications

- [TrackPoint Mode](trackpoint-mode-spec.md) - Similar joystick pattern on nav keys
- [Cursor Navigation](cursor-navigation-spec.md) - Arrow key and slider handling
