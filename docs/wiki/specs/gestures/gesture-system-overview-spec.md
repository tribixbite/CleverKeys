---
title: Gesture System Overview - Technical Specification
description: Architectural overview of the multi-layered gesture recognition pipeline (short swipes, long swipes, circles, sliders) and the hasLeftStartingKey routing gatekeeper
status: implemented
version: v1.4.0
---

# Gesture System Overview

## Overview

CleverKeys implements a multi-layered gesture recognition system that handles four gesture types: short swipes (directional swipes within a key for sublabels), long swipes (gestures across keys for neural word prediction), circle/rotation gestures (for double letters), and slider gestures (continuous value adjustment). The `hasLeftStartingKey` flag is the central decision point that routes touches to the appropriate handler.

This spec is the system-level overview. Per-gesture behavior is documented in the linked specifications under "Related Specifications".

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `Pointers` | Touch event handling, gesture pipeline routing (~1789 lines) |
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `Pointers.Pointer` (internal class) | Per-pointer state including `hasLeftStartingKey` |
| `src/main/kotlin/tribixbite/cleverkeys/GestureClassifier.kt` | `GestureClassifier` | TAP vs SWIPE classification (65 lines) |
| `src/main/kotlin/tribixbite/cleverkeys/Gesture.kt` | `Gesture` | Circle/rotation state machine (141 lines) |
| `src/main/kotlin/tribixbite/cleverkeys/customization/SwipeDirection.kt` | `SwipeDirection` | 8-direction enum for short swipes |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | Gesture settings | Configuration thresholds |
| `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt` | `getKeyHypotenuse()` | Key dimension calculation |

## Architecture

```
Touch Events (Keyboard2View.onTouchEvent)
           │
           ▼
      Pointers.kt
           │
     ┌─────┴─────┐
     │           │
  onTouchMove  onTouchUp
     │           │
     ▼           ▼
┌─────────┐  ┌──────────────────┐
│ Track   │  │ GestureClassifier │
│ hasLeft │  │    .classify()    │
│ Starting│  └────────┬─────────┘
│ Key     │           │
└─────────┘    ┌──────┴──────┐
               │             │
          SWIPE           TAP
               │             │
               ▼             ▼
        Neural Predictor  Short Gesture
        (onSwipeEnd)      Handler
```

## Data Flow

### Touch Tracking

1. `onTouchDown`: Record start position, identify starting key
2. `onTouchMove`: Update position, check if left starting key
3. `onTouchUp`: Classify gesture, trigger appropriate handler

### The `hasLeftStartingKey` Gatekeeper

This boolean flag is the single decision point determining gesture type:

```kotlin
// Pointers.kt, onTouchMove handler
if (ptr.key != null && !ptr.hasLeftStartingKey) {
    val keyHypotenuse = _handler.getKeyHypotenuse(ptr.key)
    val maxAllowedDistance = keyHypotenuse * (config.short_gesture_max_distance / 100.0f)
    val distanceFromStart = sqrt((x - ptr.downX).pow(2) + (y - ptr.downY).pow(2))

    if (distanceFromStart > maxAllowedDistance) {
        ptr.hasLeftStartingKey = true  // Permanently set for this touch
    }
}
```

See `Pointers.kt:734-744` for the live implementation.

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `short_gesture_min_distance` | Int | 28 | Min displacement to trigger a short swipe, as % of key diagonal |
| `short_gesture_max_distance` | Int | 141 | **The single short/long boundary.** Max displacement-from-touch-down (% of key diagonal) a gesture may travel and still be a short swipe; beyond it the gesture is a long (neural word) swipe. Honored by **both** activation paths (see "Swipe Typing Activation"). The old "200 = disabled" label was never implemented in logic and has been retired — use the Enable Short Gestures toggle to disable short swipes. |
| `tap_duration_threshold` | Long | 150 | Max ms a gesture that already left the key may last and still be classified as a tap (touch-up path only) |
| `circle_gesture_enabled` | Boolean | true | Enable circle gestures for double letters |

> There is **no** `swipe_speed_threshold` / minimum-speed gate. (Older drafts of this spec listed one; it has never existed in code. Slow swipes are not rejected by speed — see the pause-recovery note in [Swipe Typing](../typing/swipe-typing-spec.md).)

## Public API

### GestureClassifier

```kotlin
class GestureClassifier(private val context: Context) {

    enum class GestureType { TAP, SWIPE }

    data class GestureData(
        val hasLeftStartingKey: Boolean,
        val totalDistance: Float,
        val timeElapsed: Long,
        val keyWidth: Float
    )

    fun classify(gesture: GestureData): GestureType {
        val minSwipeDistance = gesture.keyWidth / 2.0f

        return if (gesture.hasLeftStartingKey &&
                   (gesture.totalDistance >= minSwipeDistance ||
                    gesture.timeElapsed > maxTapDurationMs)) {
            GestureType.SWIPE
        } else {
            GestureType.TAP
        }
    }
}
```

### Pointers Touch Handlers

```kotlin
class Pointers(
    private val handler: Handler,
    private val config: Config
) {
    // Called from Keyboard2View.onTouchEvent
    fun onTouchEvent(event: MotionEvent): Boolean

    // Internal handlers
    private fun onTouchDown(ptr: Pointer, x: Float, y: Float)
    private fun onTouchMove(ptr: Pointer, x: Float, y: Float)
    private fun onTouchUp(ptr: Pointer)

    // Gesture routing
    private fun handleShortGesture(ptr: Pointer, direction: SwipeDirection)
    private fun handleSwipeTyping(ptr: Pointer)
}
```

## Implementation Details

### Gesture Classification Logic

| hasLeftStartingKey | Distance | Time | Result |
|--------------------|----------|------|--------|
| FALSE | any | any | TAP |
| TRUE | < keyWidth/2 | <= tap_duration | TAP |
| TRUE | >= keyWidth/2 | any | SWIPE |
| TRUE | any | > tap_duration | SWIPE |

### Key Dimension Calculation

All thresholds use actual device pixels computed at runtime:

```kotlin
// Keyboard2View.kt
override fun getKeyHypotenuse(key: KeyboardData.Key): Float {
    val tc = themeComputed ?: return 0f

    // Find row height from layout
    var normalizedRowHeight = 0f
    for (row in keyboard.rows) {
        for (k in row.keys) {
            if (k == key) {
                normalizedRowHeight = row.height
                break
            }
        }
    }

    // Convert to actual pixels
    val keyHeightPx = normalizedRowHeight * tc.row_height
    val keyWidthPx = key.width * keyWidth

    return sqrt(keyWidthPx.pow(2) + keyHeightPx.pow(2))  // Diagonal in pixels
}
```

### Short Swipe Direction Detection

Direction calculated from delta between start and end positions:

```kotlin
private fun calculateSwipeDirection(dx: Float, dy: Float): SwipeDirection {
    val angle = atan2(-dy.toDouble(), dx.toDouble())  // Negative Y because screen coords
    val degrees = Math.toDegrees(angle)

    return when {
        degrees in -22.5..22.5 -> SwipeDirection.E
        degrees in 22.5..67.5 -> SwipeDirection.NE
        degrees in 67.5..112.5 -> SwipeDirection.N
        degrees in 112.5..157.5 -> SwipeDirection.NW
        degrees > 157.5 || degrees < -157.5 -> SwipeDirection.W
        degrees in -157.5..-112.5 -> SwipeDirection.SW
        degrees in -112.5..-67.5 -> SwipeDirection.S
        degrees in -67.5..-22.5 -> SwipeDirection.SE
        else -> SwipeDirection.E
    }
}
```

### Circle Gesture Detection

Circle gestures detected via rotation accumulation:

```kotlin
class Gesture {
    private var totalRotation: Float = 0f
    private var lastAngle: Float = 0f

    fun addPoint(x: Float, y: Float, centerX: Float, centerY: Float) {
        val currentAngle = atan2(y - centerY, x - centerX)
        val delta = normalizeAngle(currentAngle - lastAngle)
        totalRotation += delta
        lastAngle = currentAngle
    }

    fun isCircleComplete(): Boolean {
        return abs(totalRotation) >= 2 * PI  // Full circle (360 degrees)
    }

    fun getDirection(): CircleDirection {
        return if (totalRotation > 0) CircleDirection.CLOCKWISE
               else CircleDirection.COUNTER_CLOCKWISE
    }
}
```

### Swipe Typing Activation — two paths, one boundary

A letter-key gesture can be promoted to a neural word swipe through **two** code paths. Both are gated on the same single displacement boundary (`hasLeftStartingKey`, i.e. `short_gesture_max_distance`), so they agree on where short ends and long begins.

**Path A — mid-move latch** (`Pointers.kt:816-828`). While the finger moves, points feed `ImprovedSwipeGestureRecognizer`. When the recognizer reports `isSwipeTyping()` (>= 2 distinct letter keys **and** `swipe_min_distance` of accumulated path) **and** the finger has crossed the boundary (`ptr.hasLeftStartingKey`), the pointer latches `FLAG_P_SWIPE_TYPING`. Touch-up then completes the word via `onSwipeEnd` (`Pointers.kt:163-168`).

```kotlin
// Pointers.kt onTouchMove, after path collection
if (_swipeRecognizer.isSwipeTyping() && ptr.hasLeftStartingKey) {
    ptr.flags = ptr.flags or FLAG_P_SWIPE_TYPING   // commit to a word
    stopLongPress(ptr)
}
```

The `&& ptr.hasLeftStartingKey` conjunct is the fix for the **overshoot bug**: `isSwipeTyping()` alone can be satisfied at ~half a key-width when a short directional swipe overshoots into an adjacent letter, which previously committed a word mid-gesture and bypassed the short/long boundary. Gating on the boundary keeps sub-threshold overshoots out of Path A so they reach the touch-up short-gesture decision.

**Path B — touch-up classifier** (`Pointers.kt:289-343`), reached only when Path A did **not** latch. `GestureClassifier.classify()` (which also requires `hasLeftStartingKey`) decides TAP vs SWIPE; SWIPE on a char key calls `onSwipeEnd`, TAP falls to the short-gesture handler. In the short-gesture handler, a sub-boundary gesture that resolves to **no exact-direction subkey** but is a word candidate falls back to a neural word swipe — word candidates do not accept ±1-fuzzed corner matches (see [Short Swipes → No-Subkey Fallback](short-swipes-spec.md#no-subkey-fallback-to-word-swipe)).

Because both paths require `hasLeftStartingKey`, `short_gesture_max_distance` is the one knob that moves the short/long boundary for the whole system. (The no-subkey fallback is the deliberate sub-boundary exception — an intent signal, not a second threshold.)

`swipe_typing_enabled` gates **every** word route: the mid-move latch, Path B's classifier (a SWIPE classification is demoted to TAP when swipe typing is off — the gesture commits the starting key instead of silently dying in `onSwipeEnd`), and the no-subkey fallback (via the word-candidate check).

### Pointer State

The per-pointer state lives in the internal `Pointers.Pointer` class (`Pointers.kt:1389`). The conceptual shape:

```kotlin
data class Pointer(
    var key: KeyboardData.Key?,       // Starting key
    var downX: Float,                  // Initial touch X
    var downY: Float,                  // Initial touch Y
    var currentX: Float,               // Current X
    var currentY: Float,               // Current Y
    var downTime: Long,                // Touch start time
    var hasLeftStartingKey: Boolean,   // The gatekeeper flag
    var swipePath: MutableList<Point>, // Path for neural prediction
    var flags: Int                     // State flags (trackpoint, selection-delete, etc.)
)
```

## Related Specifications

- [Short Swipes](short-swipes-spec.md) - Per-key 8-direction sublabel gestures
- [Circle Gestures](circle-gestures-spec.md) - Rotation-based double-letter input
- [Cursor Navigation](cursor-navigation-spec.md) - Spacebar swipe cursor movement
- [Selection / Delete](selection-delete-spec.md) - Backspace swipe word-delete and selection
- [Trackpoint Mode](trackpoint-mode-spec.md) - Continuous cursor control
- [Neural Prediction](../typing/neural-prediction-spec.md) - The long-swipe consumer
- [Swipe Typing](../typing/swipe-typing-spec.md) - User-facing swipe typing feature
