---
title: Cursor Navigation
description: Move cursor with spacebar or arrow keys
category: Gestures
difficulty: intermediate
related_spec: ../specs/gestures/cursor-navigation-spec.md
---

# Cursor Navigation

Move the text cursor precisely using the spacebar slider or dedicated arrow keys without lifting your fingers from the keyboard.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Position cursor in text without tapping screen |
| **Methods** | Spacebar slider, arrow keys, TrackPoint mode |
| **Precision** | Character-by-character or continuous |

## Method 1: Spacebar Slider

The most intuitive way to move the cursor:

### How to Use

1. **Touch the spacebar** and hold
2. **Slide left or right** while holding
3. Cursor moves in that direction
4. **Release** when positioned

### Features

- **Proportional speed**: Faster slide = faster cursor movement
- **Visual feedback**: Trail shows movement direction
- **Immediate**: No delay, cursor moves as you slide

> [!TIP]
> Short slides move one character at a time. Longer slides accelerate the cursor.

## Method 2: Arrow Keys

For precise single-character movement:

### Accessing Arrow Keys

Arrow keys are typically subkeys on the compose key (bottom row):

1. **Short swipe** on compose key in direction:
   - North-East: Up arrow ↑
   - South-East: Down arrow ↓
   - East: Right arrow →
   - West: Left arrow ←

2. Or **long-press** compose key to see arrow popup

### Single Movement

- **Short swipe + release**: Move one character/line
- **Tap arrow key**: Move one character/line

### Repeated Movement

- **Short swipe + hold**: Cursor repeats with acceleration
- Initial delay, then speeds up

## Method 3: TrackPoint Mode

For continuous joystick-style control:

1. **Hold arrow key** past long-press timeout
2. Feel haptic activation
3. **Move finger** in any direction
4. Cursor moves continuously
5. **Release** to stop

See [TrackPoint Mode](trackpoint-mode.md) for full details.

## Navigation Shortcuts

| Gesture | Action |
|---------|--------|
| **Slide spacebar left** | Move cursor left |
| **Slide spacebar right** | Move cursor right |
| **Arrow key tap** | Move one character/line |
| **Arrow key hold** | Repeat movement |
| **TrackPoint mode** | Continuous movement |

## Home/End Navigation

Jump to line start or end:

| Key | Location | Action |
|-----|----------|--------|
| **Home** | Subkey on 'A' (NW) | Jump to line start |
| **End** | Subkey on 'A' (SW) | Jump to line end |

Access via short swipe on 'A' key in the respective direction.

## Word-by-Word Navigation

Move by whole words instead of characters:

1. **Hold Ctrl** (if available as modifier)
2. **Press arrow key**
3. Cursor jumps to next/previous word boundary

Or use the dedicated word navigation subkeys if your layout includes them.

## Tips and Tricks

- **Precision**: Tap text field to position roughly, then fine-tune with spacebar
- **Speed**: TrackPoint mode is fastest for large movements
- **Accuracy**: Arrow keys are most precise for small adjustments
- **Muscle memory**: Practice spacebar sliding - it becomes natural quickly

> [!NOTE]
> Cursor navigation works in all text fields, but some apps may handle arrow keys differently.

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Spacebar Slider** | Input Behavior | Enable/disable spacebar cursor |
| **Slider Sensitivity** | Gesture Tuning | Speed of cursor movement |
| **Long-Press Timeout** | Input Behavior | Time for TrackPoint activation |

## Common Questions

### Q: Why doesn't spacebar slider work?
A: Check Settings > Input Behavior > Spacebar Slider is enabled. Some apps may intercept touch events.

### Q: How do I select text while moving cursor?
A: Use [Selection Delete](selection-delete.md) mode or hold Shift while using arrow keys.

### Q: Can I move cursor vertically?
A: Yes, use up/down arrow keys or TrackPoint mode for vertical movement.

## Related Features

- [TrackPoint Mode](trackpoint-mode.md) - Joystick cursor control
- [Selection Delete](selection-delete.md) - Select text with cursor
- [Text Selection](../clipboard/text-selection.md) - Copy and paste

## Technical Details

See [Cursor Navigation Technical Specification](../specs/gestures/cursor-navigation-spec.md).
