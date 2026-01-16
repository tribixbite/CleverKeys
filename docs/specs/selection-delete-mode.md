# Selection-Delete Mode

## Overview

Selection-Delete Mode is a gesture that enables text selection by swiping and holding on the backspace key. When activated, horizontal finger movement selects characters (left/right), vertical movement selects lines (up/down), and releasing the finger deletes all selected text. This provides a single fluid gesture for rapid text correction.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `handleSelectionDeleteRepeat()` | Main selection logic, repeat handler |
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `FLAG_P_SELECTION_DELETE_MODE` | Mode state flag (value: 128) |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `selection_delete_vertical_threshold` | Vertical activation threshold |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `selection_delete_vertical_speed` | Vertical speed multiplier |
| `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` | Settings UI | Threshold and speed sliders |

## Architecture

```
User Input (backspace key short swipe + hold)
       |
       v
+------------------+
| onTouchDown()    | -- Defers backspace for gesture detection
+------------------+
       |
       v
+------------------+
| Short Swipe      | -- Detects initial swipe direction
| Detection        |
+------------------+
       |
       v (if hold detected after swipe)
+------------------+
| FLAG_P_SELECTION | -- Sets mode flag, records activation center
| _DELETE_MODE     |
+------------------+
       |
       v
+------------------+
| handleSelection  | -- Timer-based repeat, sends Shift+Arrow keys
| DeleteRepeat()   |
+------------------+
       |
       v (on finger release)
+------------------+
| Delete selected  | -- Sends DEL key to remove selection
| text             |
+------------------+
```

## Data Flow

1. **Activation**: Short swipe on backspace key, then hold (don't lift finger)
2. **Selection**: Finger movement from activation center determines direction
   - Horizontal (dx): Shift+Left or Shift+Right for character selection
   - Vertical (dy): Shift+Up or Shift+Down for line selection (if threshold met)
3. **Speed Scaling**: Further from center = faster selection rate
4. **Release**: Sends DEL key to delete all selected text

## Configuration

| Key | Type | Default | Range | Description |
|-----|------|---------|-------|-------------|
| `selection_delete_vertical_threshold` | Int | 40 | 20-80 | % of horizontal distance required for vertical selection |
| `selection_delete_vertical_speed` | Float | 0.4 | 0.1-1.0 | Speed multiplier for vertical selection |

## Public API

### Pointers.kt

```kotlin
// State flag constant
private const val FLAG_P_SELECTION_DELETE_MODE = 128

// Timer identifier for repeat handler
private val selectionDeleteWhat = Any()

// Main selection handler - called repeatedly while mode active
private fun handleSelectionDeleteRepeat(ptr: Pointer) {
    val dx = ptr.currentX - ptr.selectionCenterX
    val dy = ptr.currentY - ptr.selectionCenterY

    // Horizontal selection (character by character)
    if (abs(dx) > threshold) {
        val key = if (dx > 0) DPAD_RIGHT else DPAD_LEFT
        sendShiftArrow(key)
    }

    // Vertical selection (line by line) with configurable threshold
    if (abs(dy) > verticalThreshold) {
        val key = if (dy > 0) DPAD_DOWN else DPAD_UP
        sendShiftArrow(key, speedMultiplier = verticalSpeed)
    }
}

// Helper to send Shift+Arrow for selection
private fun sendShiftArrow(keyCode: Int, speedMultiplier: Float = 1.0f)

// Helper to add Shift modifier to key
private fun with_extra_mod(value: KeyValue, extra: Modifier): KeyValue
```

## Implementation Details

### Activation Sequence

1. User touches backspace key
2. User performs short swipe (any direction except center)
3. User holds finger without lifting
4. After `LONGPRESS_TIMEOUT` (600ms default), mode activates
5. `FLAG_P_SELECTION_DELETE_MODE` flag set on pointer
6. `selectionCenterX/Y` records activation point
7. Repeat timer starts calling `handleSelectionDeleteRepeat()`

### Bidirectional Movement

Selection direction changes dynamically based on current finger position relative to activation center:

```kotlin
// Direction determined each repeat cycle
val direction = when {
    dx > 0 -> DPAD_RIGHT  // Finger right of center
    dx < 0 -> DPAD_LEFT   // Finger left of center
    else -> null
}
```

This means:
- Move finger left → select backwards
- Move finger right → select forwards
- Return to center and go opposite direction → select opposite direction

### Independent Axis Handling

X and Y axes fire independently, allowing diagonal selection:

```kotlin
// Both axes checked independently each repeat
if (abs(dx) > horizontalThreshold) {
    sendShiftArrow(horizontalKey)
}
if (abs(dy) > verticalThreshold) {
    sendShiftArrow(verticalKey)
}
```

### Shift State Management

Selection requires holding Shift while sending arrow keys:

```kotlin
private fun sendShiftArrow(keyCode: Int) {
    // Create internal Shift modifier
    val shift = makeInternalModifier(Modifier.SHIFT)

    // Apply Shift to arrow key
    val arrowKey = KeyValue.getKeyByCode(keyCode)
    val shiftedArrow = with_extra_mod(arrowKey, Modifier.SHIFT)

    // Send the combined key event
    sendKeyEvent(shiftedArrow)
}
```

### Vertical Threshold Calculation

Vertical movement requires meeting a threshold relative to horizontal movement:

```kotlin
val verticalThreshold = config.selectionDeleteVerticalThreshold / 100f
val horizontalDistance = abs(dx)
val verticalDistance = abs(dy)

// Vertical only fires if it exceeds threshold percentage of horizontal
val shouldFireVertical = verticalDistance > (horizontalDistance * verticalThreshold)
```

### Error Handling

- Invalid threshold values: Clamped to 20-80%
- Invalid speed values: Clamped to 0.1-1.0
- No text to select: Selection commands sent but have no effect (harmless)
