---
title: Short Swipes - Technical Specification
user_guide: ../../gestures/short-swipes.md
status: implemented
version: v1.2.7
---

# Short Swipes Technical Specification

## Overview

Short swipe gesture detection for quick subkey access in 8 directions from any key.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Pointers | `Pointers.kt:200-400` | Swipe detection and direction |
| KeyboardData | `KeyboardData.kt` | Subkey definitions |
| Config | `Config.kt` | Distance thresholds |
| KeyValue | `KeyValue.kt` | Subkey values |

## Direction Calculation

```kotlin
// Pointers.kt:~280
fun getSwipeDirection(dx: Float, dy: Float): Int {
    val angle = atan2(-dy, dx) * 180 / PI  // 0° = East, 90° = North

    return when {
        angle >= -22.5 && angle < 22.5 -> DIRECTION_E      // East
        angle >= 22.5 && angle < 67.5 -> DIRECTION_NE     // North-East
        angle >= 67.5 && angle < 112.5 -> DIRECTION_N     // North
        angle >= 112.5 && angle < 157.5 -> DIRECTION_NW   // North-West
        angle >= 157.5 || angle < -157.5 -> DIRECTION_W   // West
        angle >= -157.5 && angle < -112.5 -> DIRECTION_SW // South-West
        angle >= -112.5 && angle < -67.5 -> DIRECTION_S   // South
        angle >= -67.5 && angle < -22.5 -> DIRECTION_SE   // South-East
        else -> DIRECTION_NONE
    }
}
```

## Direction Constants

```kotlin
// Pointers.kt
const val DIRECTION_NONE = 0
const val DIRECTION_N = 1    // key1
const val DIRECTION_NE = 2   // key2
const val DIRECTION_E = 3    // key3
const val DIRECTION_SE = 4   // key4
const val DIRECTION_S = 5    // key5
const val DIRECTION_SW = 6   // key6
const val DIRECTION_W = 7    // key7
const val DIRECTION_NW = 8   // key8
```

## Distance Thresholds

```kotlin
// Config.kt
object Defaults {
    const val SHORT_GESTURE_MIN_DISTANCE = 28  // % of key width
    const val SHORT_GESTURE_MAX_DISTANCE = 65  // % of key width
}
```

### Threshold Logic

```kotlin
// Pointers.kt:~320
fun detectShortSwipe(ptr: Pointer): Int {
    val dx = ptr.x - ptr.downX
    val dy = ptr.y - ptr.downY
    val distance = sqrt(dx * dx + dy * dy)

    val keyWidth = ptr.key.width
    val minDist = keyWidth * config.short_gesture_min_distance / 100f
    val maxDist = keyWidth * config.short_gesture_max_distance / 100f

    if (distance >= minDist && distance <= maxDist) {
        return getSwipeDirection(dx, dy)
    }
    return DIRECTION_NONE
}
```

## Subkey Resolution

```kotlin
// Pointers.kt:~350
fun getSubkeyForDirection(key: Key, direction: Int): KeyValue? {
    return when (direction) {
        DIRECTION_N -> key.key1
        DIRECTION_NE -> key.key2
        DIRECTION_E -> key.key3
        DIRECTION_SE -> key.key4
        DIRECTION_S -> key.key5
        DIRECTION_SW -> key.key6
        DIRECTION_W -> key.key7
        DIRECTION_NW -> key.key8
        else -> null
    }
}
```

## Visual Feedback

Trail drawing during swipe:

```kotlin
// Keyboard2View.kt
fun drawSwipeTrail(canvas: Canvas, points: List<PointF>) {
    trailPaint.strokeWidth = config.trail_width
    trailPaint.color = config.trail_color

    for (i in 1 until points.size) {
        canvas.drawLine(
            points[i-1].x, points[i-1].y,
            points[i].x, points[i].y,
            trailPaint
        )
    }
}
```

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Min Distance** | `short_gesture_min_distance` | 28% | 15-50 |
| **Max Distance** | `short_gesture_max_distance` | 65% | 40-80 |
| **Enable Short Swipes** | `short_swipes_enabled` | true | boolean |

## Calibration Activity

`ShortSwipeCalibrationActivity.kt` provides:

1. Visual tutorial with animated diagram
2. Slider controls for min/max distance
3. Interactive practice area
4. Real-time gesture type feedback

## Related Specifications

- [Gesture System](../../../specs/gesture-system.md) - Overall gesture handling
- [Special Characters Specification](../typing/special-characters-spec.md) - Subkey access
- [Per-Key Customization](../../../specs/short-swipe-customization.md) - Custom subkeys
