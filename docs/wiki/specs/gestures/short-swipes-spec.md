---
title: Short Swipes - Technical Specification
user_guide: /wiki/gestures/short-swipes/
status: implemented
version: v1.5.0
---

# Short Swipes Technical Specification

## Overview

Short swipe gesture detection for quick subkey access in 8 directions from any key.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Pointers | `Pointers.kt` (`onTouchMove` :1040, `getNearestKeyAtDirection` :852, touch-up short-gesture branch, `DIRECTION_TO_INDEX` :2437) | Swipe detection and direction |
| KeyboardData | `KeyboardData.kt` | Subkey definitions |
| Config | `Config.kt` | Distance thresholds |
| KeyValue | `KeyValue.kt` | Subkey values |

## Direction Calculation

Direction is a 16-sector index (22.5° slices, 0 = north, clockwise), computed in
`Pointers.onTouchMove` once the finger has travelled `swipe_dist_px` from the touch-down
point (`Pointers.kt:1047-1050`). There is no `getSwipeDirection()` and no `DIRECTION_*`
constant set:

```kotlin
if (dist >= snap.swipe_dist_px && ptr.gesture == null) {
    // Pointer moved significantly - check for special key activation FIRST
    val a = atan2(dy, dx) + Math.PI
    val direction = ((a * 8 / Math.PI).toInt() + 12) % 16
```

## Direction to Subkey Slot

The sector selects a layout slot through a lookup table; slot numbers are the layout XML's
`key1`…`key8` (`key1` NW, `key2` NE, `key3` SW, `key4` SE, `key5` W, `key6` E, `key7` N,
`key8` S):

```kotlin
// Pointers.kt:2437
val DIRECTION_TO_INDEX = intArrayOf(
    7, 2, 2, 6, 6, 4, 4, 8, 8, 3, 3, 5, 5, 1, 1, 7
//  n ne ne  e  e se se  s  s sw sw  w  w nw nw  n
//  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
)
```

`DIRECTION_TO_SWIPE_DIRECTION` (`Pointers.kt:2455`) maps the same 16 sectors onto the
8-value `SwipeDirection` enum that per-key customization stores.

## Distance Thresholds

```kotlin
// Config.kt
object Defaults {
    const val SHORT_GESTURE_MIN_DISTANCE = 28   // % of key DIAGONAL (hypotenuse)
    const val SHORT_GESTURE_MAX_DISTANCE = 141  // % of key DIAGONAL; also the short/long boundary
}
```

Distances are measured from the touch-down point and compared against a percentage of the key **diagonal** (`getKeyHypotenuse`), not the width. `short_gesture_max_distance` is the single short/long boundary: at or below it a gesture is a short swipe, above it a long (word) swipe.

### Threshold Logic

The live logic (Pointers.kt touch-up TAP branch) measures end displacement from the
touch-down point against the key **diagonal**, with an absolute cap on the minimum so
wide keys stay easy:

```kotlin
val distance = sqrt(dx * dx + dy * dy)            // dx,dy = lastX/Y - downX/Y
val keyHypotenuse = handler.getKeyHypotenuse(ptr.key)

// MIN: the easier of percentage and absolute. The absolute cap
// (swipe_dist_px * 0.8, from the legacy device-scaled "swipe_dist" pref) only
// wins on WIDE keys (>~1.3 units, e.g. backspace/shift/space) where the
// percentage of a large diagonal would demand uncomfortably long swipes.
// Ordinary letter keys always use the percentage.
val percentMin = keyHypotenuse * (config.short_gesture_min_distance / 100f)
val minDistance = min(percentMin, config.swipe_dist_px * 0.8f)

// MAX: the short/long boundary (same value that gates hasLeftStartingKey).
val maxDistance = keyHypotenuse * (config.short_gesture_max_distance / 100f)

if (distance >= minDistance && distance <= maxDistance) { /* short swipe */ }
```

### Short Swipes Only (GH #147, #87)

`short_swipes_only` (default `false`, `Defaults.SHORT_SWIPES_ONLY`, `Config.kt:138`; Settings >
Gesture Tuning > **Short Swipes Only**; commit 918dc322) removes the upper bound: every swipe of
at least the minimum distance types the subkey in its direction however long it is, a swipe
toward a direction with no subkey types nothing, and swipe typing is off while it is on. The
effective flag is `short_gestures_enabled && short_swipes_only` (`Config.kt:1332-1333`), so it
needs short gestures enabled; the Swipe Typing toggle and the max-distance slider show
"Off while Short Swipes Only is on" / "Not used while Short Swipes Only is on" descriptions
(`strings.xml:699-702`).

## Subkey Resolution

A sector with no subkey borrows from its two neighbours (±1 sector, about 67° in total), so
a slightly off-axis flick still finds the intended subkey. `modifyKey` is applied in the same
step so a key removed by the active modifiers counts as absent:

```kotlin
// Pointers.kt:841-879 (comments abridged)
private fun getKeyAtDirection(k: KeyboardData.Key, direction: Int): KeyValue? {
    return k.keys[DIRECTION_TO_INDEX[direction]]
}

private fun getNearestKeyAtDirection(ptr: Pointer, direction: Int): KeyValue? {
    // [i] is [0, -1, +1], scanning ~19% of the circle's area (3 directions = 67.5°)
    var i = 0
    while (i > -2) {  // Changed from -3 to -2 (±1 range)
        val d = (direction + i + 16) % 16
        val k = _handler.modifyKey(getKeyAtDirection(ptr.key, d), ptr.modifiers)
        if (k != null) {
            if (k.getKind() == KeyValue.Kind.Slider && abs(i) >= 2) {
                i = (i.inv() shr 31) - i
                continue
            }
            return k
        }
        i = (i.inv() shr 31) - i
    }
    return null
}
```

## No-Subkey Fallback to Word Swipe

When a short swipe resolves to **no accepted subkey** in its direction but the gesture is a word candidate — the recognizer registered ≥2 distinct letter keys (including a key registered on the **final** sample, via `promoteWordCandidacy()`) and ≥`swipe_min_distance` of path — and swipe typing is enabled on a char key, the gesture is committed as a **word swipe** instead of falling through to a first-letter tap (`Pointers.kt`, the `gestureValue == null` branch).

**Exact-direction vs ±1 fuzz.** Subkey lookup normally scans ±1 of the 16 direction slots (`getNearestKeyAtDirection`) so deliberate flicks are forgiving of angle. Word candidates, however, only accept the **exact-direction** slot (i=0): default layouts populate corners densely (`ne`=digits, `nw`=symbols), so with the fuzz a word-shaped gesture almost always found *something* — a "we" swipe tilted <22.5° up reached `ne="2"`, and a "the"-shaped gesture (end vector W) reached `nw="%"`. A deliberate corner flick computes the corner's own direction (e.g. 45° → dir 2 → exact `ne`) and still wins even when the flick crossed into the adjacent key; fuzz-only matches lose to the word. Non-candidates (single-key flicks, non-char keys such as backspace `nw=delete_last_word`) keep the full ±1 forgiveness.

This lets compact words swiped toward a direction with no sublabel (e.g. "we") still produce a word, **without** weakening overshoot protection: an overshoot toward an *assigned* subkey takes the subkey branch above and never reaches this fallback. The fallback is bounded to the sub-boundary short zone (it lives inside `distance <= maxDistance`, where `maxDistance` uses the same `short_gesture_max_distance` that gates `hasLeftStartingKey`), so it never competes with clearly-long swipes — those have already committed via the mid-move latch.

Intent ordering at the boundary, for a gesture that registered ≥2 keys below the boundary:

| Subkey assigned in direction? | Outcome |
|---|---|
| Yes (exact direction) | Short swipe → emit subkey (overshoot tolerated) |
| No (or ±1-fuzz only) | Word candidate → word swipe; otherwise first-letter tap |

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
| **Min Distance** | `short_gesture_min_distance` | 28 (% of key diagonal) | 10-60 (`Config.kt:119`) |
| **Max Distance** (short/long boundary) | `short_gesture_max_distance` | 141 (% of key diagonal) | 50-200 (`Config.kt:120`) |
| **Enable Short Swipes** | `short_gestures_enabled` | true | boolean (`Config.kt:118`) |

`short_gesture_max_distance` is the short/long boundary: displacement at or below it is a short swipe (subkey); beyond it the gesture continues as a word swipe. The old "200 = disabled" UI label was never implemented and has been retired (`Config.kt:571`). Both distances are stored as `PercentOfKey` — a value class that makes raw-pixel vs percent confusion uncompilable (`Config.kt:570-571`).

## Calibration Activity

`ShortSwipeCalibrationActivity.kt` provides:

1. Visual tutorial with animated diagram
2. Slider controls for min distance and the short/long boundary — same `%`-of-key-diagonal units and ranges (10–60 / 50–200) as the Settings sliders; saving calls `Config.refresh()` so the engine picks the values up immediately
3. Interactive practice area — measured displacement is converted to % through a representative key diagonal (width = narrow-side/10, height from the configured keyboard height over 4 rows) so feedback matches the engine's interpretation; the real engine uses the touched key's actual diagonal
4. Real-time gesture type feedback (TAP / SHORT SWIPE / LONG SWIPE → word)

## Related Specifications

- [Gesture System](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/gesture-system.md) - Overall gesture handling
- [Special Characters Specification](../typing/special-characters-spec.md) - Subkey access
- [Per-Key Customization](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/short-swipe-customization.md) - Custom subkeys
