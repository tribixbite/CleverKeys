# Cursor Navigation System

## Overview

CleverKeys provides two distinct cursor navigation mechanisms: slider-based cursor (spacebar) for continuous movement scaled by distance and speed, and arrow key navigation (dedicated nav key) for discrete single-character movement. Both are accessed via swipe gestures on bottom row keys.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `Sliding` inner class | Touch tracking and movement calculation |
| `src/main/kotlin/tribixbite/cleverkeys/KeyValue.kt` | `Slider` enum | Cursor key definitions |
| `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | `handleSlider()`, `moveCursor()` | Cursor movement execution |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `swipe_scaling`, `slide_step_px` | Configuration and scaling |
| `res/xml/bottom_row.xml` | Key layout | Swipe direction mappings |

## Architecture

### Layout Configuration

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

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `slider_sensitivity` | Int | 30 | % - Pixels per cursor movement (lower = more sensitive) |
| `slider_speed_smoothing` | Float | 0.6 | Exponential smoothing factor (0.0-1.0) |
| `slider_speed_max` | Float | 6.0 | Maximum speed multiplier |
| `SLIDING_SPEED_VERTICAL_MULT` | Float | 0.5 | Vertical movement reduction factor |

## Implementation Details

### Slider Key Types

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

### The `swipe_scaling` Calculation

Device-adaptive scaling ensures consistent behavior across different screen sizes:

```kotlin
// Config.kt
val dpi_ratio = maxOf(dm.xdpi, dm.ydpi) / minOf(dm.xdpi, dm.ydpi)
val swipe_scaling = minOf(dm.widthPixels, dm.heightPixels) / 10f * dpi_ratio

val slider_sensitivity = prefs.getString("slider_sensitivity", "30").toFloat() / 100f
slide_step_px = slider_sensitivity * swipe_scaling
```

**Example calculations by device:**

| Device | Width | DPI Ratio | swipe_scaling | slide_step_px (30%) |
|--------|-------|-----------|---------------|---------------------|
| 1080p phone | 1080px | 1.0 | 108 | 32.4px |
| 1440p phone | 1440px | 1.0 | 144 | 43.2px |
| 720p phone | 720px | 1.0 | 72 | 21.6px |

### Sliding Algorithm

Located in `Pointers.kt`:

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

fun update_speed(travelled: Float, x: Float, y: Float) {
    val now = System.currentTimeMillis()
    val instant_speed = min(slider_speed_max, travelled / (now - last_move_ms) + 1f)
    speed = speed + (instant_speed - speed) * slider_speed_smoothing
    last_move_ms = now
}
```

### Swipe Behavior Examples

| Swipe Type | Distance | Time | Speed | Cursor Moves |
|------------|----------|------|-------|--------------|
| Short, slow | 50px | 200ms | ~1.0 | 1-2 positions |
| Long, slow | 200px | 800ms | ~1.2 | 7-8 positions |
| Fast | 150px | 75ms | ~2.5 | 11-12 positions |
| Very fast | 200px | 40ms | ~4.5 (capped at 6) | 27-28 positions |

### Arrow Key Navigation

Discrete movement via DPAD key events:

```kotlin
// KeyValue.kt
"up" -> keyeventKey(0xE005, KeyEvent.KEYCODE_DPAD_UP, 0)
"right" -> keyeventKey(0xE006, KeyEvent.KEYCODE_DPAD_RIGHT, FLAG_SMALLER_FONT)
"down" -> keyeventKey(0xE007, KeyEvent.KEYCODE_DPAD_DOWN, 0)
"left" -> keyeventKey(0xE008, KeyEvent.KEYCODE_DPAD_LEFT, FLAG_SMALLER_FONT)
```

Each swipe triggers exactly ONE key event - no distance or speed scaling.

### Comparison Table

| Feature | Spacebar Slider | Nav Key Arrows |
|---------|-----------------|----------------|
| **Key values** | `cursor_left`, `cursor_right` | `left`, `right`, `up`, `down` |
| **Movement type** | Continuous (fractional accumulation) | Discrete (one per swipe) |
| **Speed-sensitive** | Yes (1x to 6x multiplier) | No |
| **Distance-sensitive** | Yes (proportional) | No |
| **Output method** | `InputConnection.setSelection()` | `KeyEvent.KEYCODE_DPAD_*` |
| **Use case** | Quick navigation through text | Precise single-char movement |

### Cursor Movement Execution

```kotlin
// KeyEventHandler.kt
private fun handleSlider(s: KeyValue.Slider, r: Int, keyDown: Boolean) {
    when (s) {
        Slider.Cursor_left -> moveCursor(-r)
        Slider.Cursor_right -> moveCursor(r)
        Slider.Cursor_up -> moveCursorVertical(-r)
        Slider.Cursor_down -> moveCursorVertical(r)
        Slider.Selection_cursor_left -> moveCursorSel(r, true, keyDown)
        Slider.Selection_cursor_right -> moveCursorSel(r, false, keyDown)
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
