# Cursor Navigation System

## Overview

CleverKeys provides two distinct cursor navigation mechanisms:

1. **Slider-based cursor** (spacebar) - Continuous movement scaled by distance and speed
2. **Arrow key navigation** (dedicated nav key) - Discrete single-character movement

Both are accessed via swipe gestures on bottom row keys.

## Layout Configuration

From `res/xml/bottom_row.xml`:

```xml
<!-- Spacebar: Slider-based cursor (continuous) -->
<key width="4.4" key0="space" key5="cursor_left" key6="cursor_right"
     key7="switch_forward" key8="switch_backward"/>

<!-- Navigation key: Arrow keys (discrete) -->
<key key0="loc compose" key5="left" key6="right" key7="up" key8="down"
     key1="loc home" key2="loc page_up" key3="loc end" key4="loc page_down"/>
```

**Swipe direction mapping:**
- `key5` = West (left swipe)
- `key6` = East (right swipe)
- `key7` = North (up swipe)
- `key8` = South (down swipe)

## Slider System (Spacebar Cursor)

### Key Types

```kotlin
// KeyValue.kt
enum class Slider(val symbol: String) {
    Cursor_left("\uE008"),
    Cursor_right("\uE006"),
    Cursor_up("\uE005"),
    Cursor_down("\uE007"),
    Selection_cursor_left("\uE008"),   // Extends selection leftward
    Selection_cursor_right("\uE006");  // Extends selection rightward
}
```

### Configuration Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `slider_sensitivity` | 30% | Pixels per cursor movement (lower = more sensitive) |
| `slider_speed_smoothing` | 0.6 | Exponential smoothing factor (0.0-1.0) |
| `slider_speed_max` | 6.0 | Maximum speed multiplier |
| `SLIDING_SPEED_VERTICAL_MULT` | 0.5 | Vertical movement reduction factor |

### The `swipe_scaling` Calculation

The slider system uses device-adaptive scaling to ensure consistent behavior across different screen sizes and DPIs.

```kotlin
// Config.kt lines 353-359
val dpi_ratio = maxOf(dm.xdpi, dm.ydpi) / minOf(dm.xdpi, dm.ydpi)
val swipe_scaling = minOf(dm.widthPixels, dm.heightPixels) / 10f * dpi_ratio

val slider_sensitivity = (prefs.getString("slider_sensitivity", "30").toFloat()) / 100f
slide_step_px = slider_sensitivity * swipe_scaling
```

**Breakdown:**

1. **`dpi_ratio`**: Compensates for non-square pixels
   - Square pixels: `dpi_ratio = 1.0`
   - Non-square (e.g., 440x400 DPI): `dpi_ratio = 1.1`

2. **`swipe_scaling`**: Base scaling from screen size
   - Uses smaller dimension (usually width in portrait)
   - Divides by 10 to get a reasonable base unit
   - Example: 1080px width → `swipe_scaling = 108`

3. **`slide_step_px`**: Final pixels-per-cursor-movement
   - Multiplies sensitivity (0.0-1.0) by swipe_scaling
   - 30% sensitivity on 1080px screen: `0.30 * 108 = 32.4px`

**Example calculations by device:**

| Device | Width | DPI Ratio | swipe_scaling | slide_step_px (30%) |
|--------|-------|-----------|---------------|---------------------|
| 1080p phone | 1080px | 1.0 | 108 | 32.4px |
| 1440p phone | 1440px | 1.0 | 144 | 43.2px |
| 720p phone | 720px | 1.0 | 72 | 21.6px |
| Tablet 800px | 800px | 1.0 | 80 | 24.0px |

### Sliding Algorithm

Located in `Pointers.kt`, the `Sliding` inner class:

```kotlin
inner class Sliding(
    x: Float, y: Float,
    val direction_x: Int,  // ±1 based on initial swipe direction
    val direction_y: Int,
    val slider: KeyValue.Slider
) {
    var d = 0f              // Accumulated fractional cursor movements
    var speed = 0.5f        // Current speed multiplier (0.5 - max)
    var last_move_ms: Long  // Timestamp for speed calculation
}
```

**Movement calculation:**

```kotlin
fun onTouchMove(ptr: Pointer, x: Float, y: Float) {
    val travelled = abs(x - last_x) + abs(y - last_y)

    // Accumulate distance with speed multiplier
    d += ((x - last_x) * speed * direction_x +
          (y - last_y) * speed * SLIDING_SPEED_VERTICAL_MULT * direction_y) /
         _config.slide_step_px

    // Send cursor event for each whole unit accumulated
    val d_ = d.toInt()
    if (d_ != 0) {
        d -= d_
        _handler.onPointerHold(KeyValue.sliderKey(slider, d_), ptr.modifiers)
    }

    update_speed(travelled, x, y)
}
```

**Speed calculation:**

```kotlin
fun update_speed(travelled: Float, x: Float, y: Float) {
    val now = System.currentTimeMillis()
    val instant_speed = min(slider_speed_max, travelled / (now - last_move_ms) + 1f)
    speed = speed + (instant_speed - speed) * slider_speed_smoothing
    last_move_ms = now
}
```

### Swipe Behavior Examples

**Short, slow swipe** (50px in 200ms):
```
instant_speed = min(6.0, 50/200 + 1) = 1.25
speed ≈ 1.0 (starts at 0.5, smoothed toward 1.25)
cursor_moves = 50 * 1.0 / 32.4 ≈ 1-2 positions
```

**Long, slow swipe** (200px in 800ms):
```
instant_speed = min(6.0, 200/800 + 1) = 1.25
speed ≈ 1.2 (smoothed)
cursor_moves = 200 * 1.2 / 32.4 ≈ 7-8 positions
```

**Fast swipe** (150px in 75ms):
```
instant_speed = min(6.0, 150/75 + 1) = 3.0
speed ≈ 2.5 (smoothed from previous)
cursor_moves = 150 * 2.5 / 32.4 ≈ 11-12 positions
```

**Very fast swipe** (200px in 40ms):
```
instant_speed = min(6.0, 200/40 + 1) = 6.0 (capped)
speed ≈ 4.5 (smoothed)
cursor_moves = 200 * 4.5 / 32.4 ≈ 27-28 positions
```

## Arrow Key Navigation (Dedicated Nav Key)

### Key Types

```kotlin
// KeyValue.kt - creates DPAD key events
"up" -> keyeventKey(0xE005, KeyEvent.KEYCODE_DPAD_UP, 0)
"right" -> keyeventKey(0xE006, KeyEvent.KEYCODE_DPAD_RIGHT, FLAG_SMALLER_FONT)
"down" -> keyeventKey(0xE007, KeyEvent.KEYCODE_DPAD_DOWN, 0)
"left" -> keyeventKey(0xE008, KeyEvent.KEYCODE_DPAD_LEFT, FLAG_SMALLER_FONT)
```

### Behavior

- Each swipe triggers exactly **ONE** key event
- No distance or speed scaling
- Consistent, predictable movement
- Works in all text fields and apps

## Comparison Table

| Feature | Spacebar Slider | Nav Key Arrows |
|---------|-----------------|----------------|
| **Key values** | `cursor_left`, `cursor_right` | `left`, `right`, `up`, `down` |
| **Movement type** | Continuous (fractional accumulation) | Discrete (one per swipe) |
| **Speed-sensitive** | Yes (1x to 6x multiplier) | No |
| **Distance-sensitive** | Yes (proportional) | No |
| **Output method** | `InputConnection.setSelection()` | `KeyEvent.KEYCODE_DPAD_*` |
| **Use case** | Quick navigation through text | Precise single-char movement |
| **Selection support** | Yes (`selection_cursor_*` variants) | With Shift modifier |

## Cursor Movement Execution

```kotlin
// KeyEventHandler.kt
private fun handleSlider(s: KeyValue.Slider, r: Int, keyDown: Boolean) {
    when (s) {
        KeyValue.Slider.Cursor_left -> moveCursor(-r)
        KeyValue.Slider.Cursor_right -> moveCursor(r)
        KeyValue.Slider.Cursor_up -> moveCursorVertical(-r)
        KeyValue.Slider.Cursor_down -> moveCursorVertical(r)
        KeyValue.Slider.Selection_cursor_left -> moveCursorSel(r, true, keyDown)
        KeyValue.Slider.Selection_cursor_right -> moveCursorSel(r, false, keyDown)
    }
}

private fun moveCursor(d: Int) {
    val conn = recv.getCurrentInputConnection() ?: return
    val et = getCursorPos(conn)

    if (et != null && canSetSelection(conn)) {
        var selEnd = et.selectionEnd + d
        var selStart = if ((metaState and KeyEvent.META_SHIFT_ON) == 0) selEnd else et.selectionStart
        conn.setSelection(selStart, selEnd)
    } else {
        moveCursorFallback(d)  // Send arrow key events
    }
}
```

## Settings UI

Users can configure slider behavior via Settings → Gestures:

- **Slider Sensitivity** (1-100%): Lower = more cursor movement per pixel
- **Speed Smoothing** (0.1-0.95): Higher = smoother but less responsive
- **Maximum Speed** (1.0-10.0): Cap on speed multiplier

## Files

- `Pointers.kt` - Sliding class and touch handling
- `KeyValue.kt` - Slider enum and key definitions
- `KeyEventHandler.kt` - Cursor movement execution
- `Config.kt` - Configuration and swipe_scaling calculation
- `res/xml/bottom_row.xml` - Key layout definitions
