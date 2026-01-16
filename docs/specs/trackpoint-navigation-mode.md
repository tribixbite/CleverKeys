# TrackPoint Navigation Mode

## Overview

TrackPoint Navigation Mode provides joystick-style cursor control by holding arrow keys (without initial swipe movement). When activated, finger movement in any direction moves the cursor proportionally, with speed scaling based on distance from the activation point. Supports all 8 directions including diagonals for fluid text navigation.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `handleTrackPointRepeat()` | Main cursor movement logic |
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `FLAG_P_TRACKPOINT_MODE` | Mode state flag (value: 64) |
| `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt` | `CLOCK_TICK` pattern | Distinct haptic on activation |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | Haptic toggle | TrackPoint haptic setting |

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
| Activation       | -- CLOCK_TICK haptic feedback
+------------------+
       |
       v
+------------------+
| handleTrackPoint | -- Repeating handler tracks position
| Repeat()         | -- Calculates direction and distance
+------------------+
       |
       v
+------------------+
| Cursor Movement  | -- Sends DPAD_* key events
| Handler          | -- Supports all 8 directions
+------------------+
```

## Data Flow

1. **Activation**: Hold arrow key without initial swipe movement
2. **Detection**: After `LONGPRESS_TIMEOUT` with <30px movement, mode activates
3. **Tracking**: Repeat handler reads current finger position
4. **Direction**: Calculate angle from activation point to current position
5. **Speed**: Distance from center determines repeat rate
6. **Output**: Appropriate DPAD_* key events sent to input connection

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `trackpoint_haptic_enabled` | Boolean | true | Enable CLOCK_TICK on mode activation |
| `short_gesture_min_distance` | Int | 15 | Pixels before short swipe detection (30px for nav keys) |

## Public API

### Pointers.kt

```kotlin
// State flag constant
private const val FLAG_P_TRACKPOINT_MODE = 64

// Timer identifier for repeat handler
private val trackpointWhat = Any()

// Main TrackPoint handler - called repeatedly while mode active
private fun handleTrackPointRepeat(ptr: Pointer) {
    val dx = ptr.currentX - ptr.activationX
    val dy = ptr.currentY - ptr.activationY

    // Calculate 8-direction from delta
    val direction = getTrackPointDirection(dx, dy)

    // Send cursor movement(s)
    sendCursorMovement(direction)
}

// Get direction enum from delta coordinates
private fun getTrackPointDirection(dx: Float, dy: Float): TrackPointDirection

// Send arrow key event(s) for direction
private fun sendCursorMovement(direction: TrackPointDirection)

// Trigger activation haptic
private fun triggerTrackPointHaptic()
```

### Direction Enumeration

```kotlin
enum class TrackPointDirection {
    NORTH,      // Up only
    NORTHEAST,  // Up + Right
    EAST,       // Right only
    SOUTHEAST,  // Down + Right
    SOUTH,      // Down only
    SOUTHWEST,  // Down + Left
    WEST,       // Left only
    NORTHWEST   // Up + Left
}
```

## Implementation Details

### Activation vs Short Swipe

TrackPoint mode activates when:
1. Arrow key touched
2. Finger moved <30px from initial position (nav keys use 30px, others use 15px)
3. Hold time exceeds `LONGPRESS_TIMEOUT` (600ms)

If finger moves >30px before timeout, it's a short swipe (single cursor move).

```kotlin
// Nav keys have increased tolerance to allow hold activation
val isNavKey = key.kind in listOf(Kind.Arrow_Up, Kind.Arrow_Down, Kind.Arrow_Left, Kind.Arrow_Right)
val movementThreshold = if (isNavKey) 30 else 15

if (distance < movementThreshold && holdTime > LONGPRESS_TIMEOUT) {
    activateTrackPointMode(ptr)
}
```

### Direction Calculation

Direction determined by angle from activation point:

```kotlin
private fun getTrackPointDirection(dx: Float, dy: Float): TrackPointDirection {
    val angle = atan2(dy.toDouble(), dx.toDouble())
    val degrees = Math.toDegrees(angle)

    // Map angle to 8 sectors (45 degrees each)
    return when {
        degrees in -22.5..22.5 -> EAST
        degrees in 22.5..67.5 -> SOUTHEAST
        degrees in 67.5..112.5 -> SOUTH
        degrees in 112.5..157.5 -> SOUTHWEST
        degrees > 157.5 || degrees < -157.5 -> WEST
        degrees in -157.5..-112.5 -> NORTHWEST
        degrees in -112.5..-67.5 -> NORTH
        degrees in -67.5..-22.5 -> NORTHEAST
        else -> EAST // fallback
    }
}
```

### Diagonal Movement

Diagonal directions send two key events:

```kotlin
private fun sendCursorMovement(direction: TrackPointDirection) {
    when (direction) {
        NORTH -> sendArrowKey(KEYCODE_DPAD_UP)
        NORTHEAST -> {
            sendArrowKey(KEYCODE_DPAD_UP)
            sendArrowKey(KEYCODE_DPAD_RIGHT)
        }
        EAST -> sendArrowKey(KEYCODE_DPAD_RIGHT)
        SOUTHEAST -> {
            sendArrowKey(KEYCODE_DPAD_DOWN)
            sendArrowKey(KEYCODE_DPAD_RIGHT)
        }
        SOUTH -> sendArrowKey(KEYCODE_DPAD_DOWN)
        SOUTHWEST -> {
            sendArrowKey(KEYCODE_DPAD_DOWN)
            sendArrowKey(KEYCODE_DPAD_LEFT)
        }
        WEST -> sendArrowKey(KEYCODE_DPAD_LEFT)
        NORTHWEST -> {
            sendArrowKey(KEYCODE_DPAD_UP)
            sendArrowKey(KEYCODE_DPAD_LEFT)
        }
    }
}
```

### Speed Scaling

Cursor movement speed scales with finger distance from activation center:

```kotlin
val distance = sqrt(dx * dx + dy * dy)
val repeatDelay = when {
    distance < 50 -> 200   // Slow (5 moves/sec)
    distance < 100 -> 100  // Medium (10 moves/sec)
    distance < 150 -> 50   // Fast (20 moves/sec)
    else -> 25             // Very fast (40 moves/sec)
}
handler.postDelayed(trackpointRunnable, repeatDelay)
```

### Haptic Feedback

Distinct CLOCK_TICK pattern on activation:

```kotlin
private fun triggerTrackPointHaptic() {
    if (config.trackpointHapticEnabled) {
        VibratorCompat.vibrate(HapticEvent.CLOCK_TICK)
    }
}
```

CLOCK_TICK provides a subtle, distinct feel different from normal key press vibration.

### Nav Key Exclusion from Gesture Collection

Arrow keys are excluded from short gesture path collection to prevent accidental gesture triggering:

```kotlin
private fun shouldCollectGesturePath(key: KeyValue): Boolean {
    // Nav keys excluded - they have TrackPoint mode instead
    if (key.kind in listOf(Kind.Arrow_Up, Kind.Arrow_Down, Kind.Arrow_Left, Kind.Arrow_Right)) {
        return false
    }
    return true
}
```

### Error Handling

- No navigation target: Cursor movement commands sent but have no effect
- Haptic unavailable: Mode still functions, just without feedback
- Rapid re-activation: Debounced to prevent haptic spam (min 200ms between activations)
