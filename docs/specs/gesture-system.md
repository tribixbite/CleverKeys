# Gesture Recognition System Specification

**Status**: Implemented
**Last Updated**: 2025-12-09

---

## 1. Overview

CleverKeys implements a multi-layered gesture recognition system that handles:

1. **Short Gestures**: Directional swipes within/near a key that trigger sublabel actions
2. **Long Swipes (Swipe Typing)**: Gestures across multiple keys that trigger neural word prediction
3. **Circle/Rotation Gestures**: Clockwise or counterclockwise rotation patterns
4. **Slider Gestures**: Continuous value adjustment (brightness, volume, etc.)

### Core Files

| File | Lines | Purpose |
|------|-------|---------|
| `Pointers.kt` | ~1100 | Touch event handling, gesture pipeline routing |
| `GestureClassifier.kt` | 65 | TAP vs SWIPE classification |
| `Gesture.kt` | 141 | Circle/rotation state machine |
| `Config.kt` | — | Gesture configuration options |

---

## 2. Gesture Pipeline Architecture

```
Touch Events (Keyboard2View)
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

---

## 3. The `hasLeftStartingKey` Gatekeeper

The `hasLeftStartingKey` boolean flag is the **single decision point** that determines whether a gesture becomes a long swipe (neural prediction) or remains eligible for short gesture handling.

### Setting the Flag (During MOVE)

```kotlin
// Pointers.kt, onTouchMove handler
if (ptr.key != null && !ptr.hasLeftStartingKey) {
    val keyHypotenuse = _handler.getKeyHypotenuse(ptr.key)
    val maxAllowedDistance = keyHypotenuse * (_config.short_gesture_max_distance / 100.0f)
    val distanceFromStart = sqrt((x - ptr.downX)² + (y - ptr.downY)²)

    if (distanceFromStart > maxAllowedDistance) {
        ptr.hasLeftStartingKey = true  // Permanently set for this touch
    }
}
```

### Key Dimension Calculation

All thresholds use actual device pixels computed at runtime:

```kotlin
// Keyboard2View.kt
override fun getKeyHypotenuse(key: KeyboardData.Key): Float {
    val tc = _tc ?: return 0f  // Theme.Computed with device-specific scaling

    // Find row height (normalized value from layout XML)
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
    val keyHeightPx = normalizedRowHeight * tc.row_height  // Device-specific
    val keyWidthPx = key.width * _keyWidth                 // Screen width / keys

    return sqrt(keyWidthPx² + keyHeightPx²)  // Diagonal in pixels
}
```

Where:
- `tc.row_height` = computed from device screen height and user's keyboard height percentage setting
- `_keyWidth` = `(screenWidth - margins) / keyboard.keysWidth`

---

## 4. GestureClassifier (TAP vs SWIPE)

The `GestureClassifier` provides unified classification on touch UP:

```kotlin
// GestureClassifier.kt
class GestureClassifier(private val context: Context) {

    private val maxTapDurationMs: Long
        get() = Config.globalConfig().tap_duration_threshold

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

### Classification Logic

| hasLeftStartingKey | Distance | Time | Result |
|--------------------|----------|------|--------|
| FALSE | any | any | TAP |
| TRUE | < keyWidth/2 | <= tap_duration | TAP |
| TRUE | >= keyWidth/2 | any | SWIPE |
| TRUE | any | > tap_duration | SWIPE |

---

## 5. Short Gesture Detection

When classified as TAP, short gesture detection checks if the movement qualifies as a directional swipe within the key:

```kotlin
// Pointers.kt, onTouchUp handler
if (_config.short_gestures_enabled && !ptr.hasLeftStartingKey) {
    val dx = ptr.lastX - ptr.downX
    val dy = ptr.lastY - ptr.downY
    val distance = sqrt(dx * dx + dy * dy)

    val keyHypotenuse = _handler.getKeyHypotenuse(ptr.key)
    val minDistance = keyHypotenuse * (_config.short_gesture_min_distance / 100.0f)

    if (distance >= minDistance) {
        // Calculate 16-direction (0-15)
        val angle = atan2(dy, dx) + Math.PI
        val direction = ((angle * 8 / Math.PI).toInt() + 12) % 16

        // Map to 8-direction for sublabel lookup
        val gestureValue = getNearestKeyAtDirection(ptr, direction)
        if (gestureValue != null) {
            _handler.onPointerDown(gestureValue, false)
            _handler.onPointerUp(gestureValue, ptr.modifiers)
            return  // Exit - gesture handled
        }
    }
}
// Fall through to regular TAP handling
```

### Direction Mapping

16-direction to key position mapping:

| Direction | Angle Range | Key Position |
|-----------|-------------|--------------|
| 0 | 348.75° - 11.25° | East (E) |
| 2 | 33.75° - 56.25° | Southeast (SE) |
| 4 | 78.75° - 101.25° | South (S) |
| 6 | 123.75° - 146.25° | Southwest (SW) |
| 8 | 168.75° - 191.25° | West (W) |
| 10 | 213.75° - 236.25° | Northwest (NW) |
| 12 | 258.75° - 281.25° | North (N) |
| 14 | 303.75° - 326.25° | Northeast (NE) |

---

## 6. Circle/Rotation Gesture State Machine

The `Gesture.kt` class implements a state machine for detecting rotation patterns, primarily used for Slider activation:

### States

```kotlin
enum class State {
    Cancelled,              // Gesture was cancelled (rotation reversed)
    Swiped,                 // Initial swipe, no rotation detected yet
    Rotating_clockwise,     // Clockwise rotation in progress
    Rotating_anticlockwise, // Counter-clockwise rotation in progress
    Ended_swipe,            // Simple swipe completed
    Ended_center,           // Roundtrip (swipe out and back)
    Ended_clockwise,        // Clockwise circle completed
    Ended_anticlockwise     // Counter-clockwise circle completed
}

enum class Name {
    None,        // Cancelled
    Swipe,       // Simple directional swipe
    Roundtrip,   // Swipe out and return to center
    Circle,      // Clockwise rotation
    Anticircle   // Counter-clockwise rotation
}
```

### Direction Difference Algorithm

```kotlin
// Find shortest path between two directions on 16-point circle
fun dirDiff(d1: Int, d2: Int): Int {
    val n = 16
    if (d1 == d2) return 0
    val left = (d1 - d2 + n) % n
    val right = (d2 - d1 + n) % n
    return if (left < right) -left else right
}
```

Key insight: Uses modular arithmetic for circular distance:
- Direction 1 → 15: diff = +2 (wraps around, not -14)
- Direction 15 → 1: diff = -2 (wraps around, not +14)

### State Transitions

```
Touch Down (direction D)
        │
        ▼
   [Swiped, dir=D]
        │
Direction change detected?
(|dirDiff| >= circle_sensitivity)
        │
   ┌────┴────┐
   NO        YES
   │         │
   │    ┌────┴────┐
   │    CW       CCW
   │    │         │
   │    ▼         ▼
   │ [Rotating   [Rotating
   │  _clockwise] _anticlockwise]
   │    │         │
   │    └────┬────┘
   │         │
   │    Rotation reversed?
   │    ┌────┴────┐
   │    YES       NO
   │    │         │
   │    ▼         │
   │ [Cancelled]  │
   │              │
   └──────┬──────┘
          │
     Touch Up
          │
   Return to center?
   ┌──────┴──────┐
   YES           NO
   │             │
   ▼             ▼
[Ended_center] [Ended_* based on state]
   │             │
   ▼             ▼
Roundtrip    Swipe/Circle/Anticircle
```

---

## 7. Configuration Options

### Config.kt Settings

| Setting | Type | Default | Range | Description |
|---------|------|---------|-------|-------------|
| `short_gestures_enabled` | Boolean | true | — | Enable short gesture detection |
| `short_gesture_min_distance` | Int | 40 | 10-95% | Minimum travel to trigger (% of key diagonal) |
| `short_gesture_max_distance` | Int | 200 | 50-200% | Maximum travel before becoming long swipe |
| `tap_duration_threshold` | Long | 150 | 50-500ms | Maximum duration for TAP classification |
| `swipe_typing_enabled` | Boolean | true | — | Enable neural swipe prediction |
| `circle_sensitivity` | Int | 2 | 1-8 | Minimum direction change for rotation detection |
| `swipe_dist_px` | Float | varies | — | Minimum distance for Slider activation |

### Threshold Behavior

| max_distance | Effect |
|--------------|--------|
| 50% | Very strict - must stay within half key diagonal |
| 100% | Standard - can travel one full key diagonal |
| 150% | Lenient - 1.5× key diagonal allowed |
| 200% | Effectively disabled - very permissive |

---

## 8. Pipeline Mutual Exclusivity

The gesture pipelines are mutually exclusive by design:

```
hasLeftStartingKey = TRUE
    → GestureClassifier returns SWIPE (if conditions met)
    → onSwipeEnd() called
    → return (exit early)
    → Short gesture code NEVER executes

hasLeftStartingKey = FALSE
    → GestureClassifier returns TAP
    → Short gesture check runs
    → Requires !ptr.hasLeftStartingKey (satisfied)
    → Short gesture may trigger OR fall through to regular TAP
```

**Guarantee**: Both pipelines cannot trigger for the same touch event because `hasLeftStartingKey` is a single boolean that gates both paths.

---

## 9. Integration Points

### Keyboard2View.kt
- Captures raw touch events
- Calls `Pointers.onTouchDown/Move/Up`
- Implements `getKeyHypotenuse()` for threshold calculation

### KeyEventHandler.kt
- Receives gesture results via `onPointerDown/Up`
- Executes key actions based on gesture direction

### IPointerEventHandler Interface
```kotlin
interface IPointerEventHandler {
    fun onPointerDown(value: KeyValue, isSwipe: Boolean)
    fun onPointerUp(value: KeyValue, mods: Modifiers)
    fun onSwipeMove(x: Float, y: Float, recognizer: SwipeRecognizer)
    fun onSwipeEnd(recognizer: SwipeRecognizer)
    fun getKeyHypotenuse(key: KeyboardData.Key): Float
    fun getKeyWidth(key: KeyboardData.Key): Float
    // ...
}
```

---

## 10. Performance Characteristics

| Operation | Complexity | Latency |
|-----------|------------|---------|
| Direction calculation | O(1) | < 0.1ms |
| GestureClassifier.classify() | O(1) | < 0.1ms |
| hasLeftStartingKey check | O(1) | < 0.1ms |
| Short gesture direction lookup | O(n) | < 1ms |
| State machine transition | O(1) | < 0.1ms |

All operations avoid heap allocations in the hot path.

---

## 11. Related Specifications

- [Short Swipe Customization](short-swipe-customization.md) - User-defined gesture mappings
- [Neural Prediction](neural-prediction.md) - Swipe typing word prediction
- [Settings System](settings-system.md) - Configuration UI
