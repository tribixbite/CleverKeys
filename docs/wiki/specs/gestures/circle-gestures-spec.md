---
title: Circle Gestures - Technical Specification
user_guide: ../../gestures/circle-gestures.md
status: planned
version: v1.3.0 (planned)
---

# Circle Gestures Technical Specification

## Overview

Planned feature for detecting circular finger movements to trigger undo/redo actions.

## Current Status

> [!NOTE]
> Circle gesture detection is not yet implemented. This spec documents the planned design.

## Planned Components

| Component | File | Purpose |
|-----------|------|---------|
| CircleDetector | `CircleDetector.kt` (planned) | Analyze touch path for circles |
| Pointers | `Pointers.kt` | Integration point |
| GestureConfig | `Config.kt` | Circle detection parameters |

## Detection Algorithm (Planned)

### Point Collection

```kotlin
// Collect touch points during swipe
data class TouchPoint(val x: Float, val y: Float, val time: Long)
val path: MutableList<TouchPoint> = mutableListOf()
```

### Angle Calculation

```kotlin
// Calculate cumulative angle change
fun calculateTotalAngle(points: List<TouchPoint>): Float {
    var totalAngle = 0f

    for (i in 2 until points.size) {
        val v1 = Vector(points[i-1].x - points[i-2].x,
                       points[i-1].y - points[i-2].y)
        val v2 = Vector(points[i].x - points[i-1].x,
                       points[i].y - points[i-1].y)

        totalAngle += angleBetween(v1, v2)
    }

    return totalAngle
}
```

### Circle Detection

```kotlin
// Detect complete circle
fun detectCircle(points: List<TouchPoint>): CircleResult? {
    if (points.size < MIN_POINTS) return null

    val totalAngle = calculateTotalAngle(points)

    return when {
        totalAngle >= 300f -> CircleResult.CLOCKWISE      // Redo
        totalAngle <= -300f -> CircleResult.COUNTER_CW    // Undo
        else -> null
    }
}
```

## Planned Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Circle Gestures** | `circle_gestures_enabled` | true | boolean |
| **Min Angle** | `circle_min_angle` | 300° | 270-360 |
| **Max Time** | `circle_max_time` | 1000ms | 500-2000 |
| **Min Radius** | `circle_min_radius` | 30px | 20-50 |

## Action Mapping (Planned)

| Circle Direction | Default Action | Customizable |
|-----------------|----------------|--------------|
| **Clockwise** | Redo (Ctrl+Y) | Yes |
| **Counter-clockwise** | Undo (Ctrl+Z) | Yes |

## Integration Points

### Pointers.kt

```kotlin
// In onTouchMove, if swipe mode not yet determined
if (!isSwipeTyping && !isShortSwipe) {
    circleDetector.addPoint(ptr.x, ptr.y, currentTime)

    val result = circleDetector.detectCircle()
    if (result != null) {
        handleCircleGesture(result)
        return
    }
}
```

### KeyEventHandler.kt

```kotlin
fun handleCircleGesture(result: CircleResult) {
    when (result) {
        CircleResult.CLOCKWISE -> sendKeyCombo(CTRL, 'Y')
        CircleResult.COUNTER_CW -> sendKeyCombo(CTRL, 'Z')
    }
}
```

## Challenges

1. **Disambiguation**: Must not interfere with swipe typing
2. **Performance**: Real-time angle calculation during touch
3. **User feedback**: Visual indication of circle progress
4. **Accessibility**: Alternative access for users who can't draw circles

## Related Specifications

- [Gesture System](../../../specs/gesture-system.md) - Overall gesture architecture
- [Short Swipes Specification](short-swipes-spec.md) - Existing gesture patterns
