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
    const val SHORT_GESTURE_MIN_DISTANCE = 28   // % of key DIAGONAL (hypotenuse)
    const val SHORT_GESTURE_MAX_DISTANCE = 141  // % of key DIAGONAL; also the short/long boundary
}
```

Distances are measured from the touch-down point and compared against a percentage of the key **diagonal** (`getKeyHypotenuse`), not the width. `short_gesture_max_distance` is the single short/long boundary: at or below it a gesture is a short swipe, above it a long (neural word) swipe.

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

## No-Subkey Fallback to Word Swipe

When a short swipe resolves to **no accepted subkey** in its direction but the gesture is a word candidate — the recognizer registered ≥2 distinct letter keys (including a key registered on the **final** sample, via `promoteWordCandidacy()`) and ≥`swipe_min_distance` of path — and swipe typing is enabled on a char key, the gesture is committed as a **neural word swipe** instead of falling through to a first-letter tap (`Pointers.kt`, the `gestureValue == null` branch).

**Exact-direction vs ±1 fuzz.** Subkey lookup normally scans ±1 of the 16 direction slots (`getNearestKeyAtDirection`) so deliberate flicks are forgiving of angle. Word candidates, however, only accept the **exact-direction** slot (i=0): default layouts populate corners densely (`ne`=digits, `nw`=symbols), so with the fuzz a word-shaped gesture almost always found *something* — a "we" swipe tilted <22.5° up reached `ne="2"`, and a "the"-shaped gesture (end vector W) reached `nw="%"`. A deliberate corner flick computes the corner's own direction (e.g. 45° → dir 2 → exact `ne`) and still wins even when the flick crossed into the adjacent key; fuzz-only matches lose to the word. Non-candidates (single-key flicks, non-char keys such as backspace `nw=delete_last_word`) keep the full ±1 forgiveness.

This lets compact words swiped toward a direction with no sublabel (e.g. "we") still produce a word, **without** weakening overshoot protection: an overshoot toward an *assigned* subkey takes the subkey branch above and never reaches this fallback. The fallback is bounded to the sub-boundary short zone (it lives inside `distance <= maxDistance`, where `maxDistance` uses the same `short_gesture_max_distance` that gates `hasLeftStartingKey`), so it never competes with clearly-long swipes — those have already committed via the mid-move latch.

Intent ordering at the boundary, for a gesture that registered ≥2 keys below the boundary:

| Subkey assigned in direction? | Outcome |
|---|---|
| Yes (exact direction) | Short swipe → emit subkey (overshoot tolerated) |
| No (or ±1-fuzz only) | Word candidate → neural word swipe; otherwise first-letter tap |

**Return-trip rescue.** A word whose path returns near its start ("pop", "lol") ends with displacement *below* the short-swipe minimum and would fall through to a first-letter tap. The plain-tap fallthrough rescues it as a word when the gesture is a word candidate, lasted longer than `tap_duration_threshold`, and the end displacement is under half the traced path. Straight gestures (taps, overshoots, flicks) have displacement ≈ path and can never match; fast grazes fail the duration check. This applies whether or not short gestures are enabled.

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
