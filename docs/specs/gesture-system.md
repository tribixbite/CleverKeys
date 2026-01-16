# Gesture Recognition System

## Overview

CleverKeys implements a multi-layered gesture recognition system that handles four gesture types: short swipes (directional swipes within a key for sublabels), long swipes (gestures across keys for neural word prediction), circle/rotation gestures (for double letters), and slider gestures (continuous value adjustment). The `hasLeftStartingKey` flag is the central decision point that routes touches to the appropriate handler.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `Pointers` | Touch event handling, gesture pipeline routing (~1100 lines) |
| `src/main/kotlin/tribixbite/cleverkeys/GestureClassifier.kt` | `GestureClassifier` | TAP vs SWIPE classification (65 lines) |
| `src/main/kotlin/tribixbite/cleverkeys/Gesture.kt` | `Gesture` | Circle/rotation state machine (141 lines) |
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

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `short_gesture_min_distance` | Int | 15 | Min pixels for short swipe detection |
| `short_gesture_max_distance` | Int | 50 | Max % of key hypotenuse for short swipe (above = long swipe) |
| `tap_duration_threshold` | Long | 200 | Max ms for tap vs swipe distinction |
| `swipe_speed_threshold` | Float | 0.4 | Min speed for swipe typing activation |
| `circle_gesture_enabled` | Boolean | true | Enable circle gestures for double letters |

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

### Swipe Typing Activation

Long swipes trigger neural prediction when finger leaves starting key:

```kotlin
private fun onTouchUp(ptr: Pointer) {
    val gestureType = gestureClassifier.classify(GestureData(
        hasLeftStartingKey = ptr.hasLeftStartingKey,
        totalDistance = ptr.totalDistance,
        timeElapsed = ptr.timeElapsed,
        keyWidth = getKeyWidth(ptr.key)
    ))

    when (gestureType) {
        GestureType.SWIPE -> {
            if (ptr.hasLeftStartingKey) {
                // Long swipe - neural prediction
                onSwipeEnd(ptr.swipePath)
            } else {
                // Short swipe - sublabel action
                val direction = calculateSwipeDirection(ptr.dx, ptr.dy)
                handleShortGesture(ptr, direction)
            }
        }
        GestureType.TAP -> {
            handleTap(ptr.key)
        }
    }
}
```

### Pointer State

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
