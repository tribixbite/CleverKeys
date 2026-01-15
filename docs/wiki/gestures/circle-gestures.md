---
title: Circle Gestures
description: Circular swipes for quick actions
category: Gestures
difficulty: advanced
related_spec: ../specs/gestures/circle-gestures-spec.md
---

# Circle Gestures

Circle gestures allow you to perform actions like undo and redo by drawing circular patterns on the keyboard.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Quick access to undo/redo and other actions |
| **Gesture** | Draw a circle on the keyboard |
| **Direction** | Counter-clockwise = Undo, Clockwise = Redo |

## Current Status

> [!NOTE]
> Circle gestures are a planned feature. The core gesture detection infrastructure exists, but dedicated circle actions are not yet fully implemented in the current release.

## Planned Functionality

### Undo Gesture

1. Start anywhere on the keyboard
2. Draw a **counter-clockwise** circle
3. Last action is undone

### Redo Gesture

1. Start anywhere on the keyboard
2. Draw a **clockwise** circle
3. Last undone action is redone

## Alternative: Current Undo/Redo Access

Until circle gestures are implemented, use these methods:

### Method 1: Subkeys

- **Undo**: Short swipe on designated key (layout-dependent)
- **Redo**: Short swipe in opposite direction

### Method 2: Symbol Keyboard

1. Switch to symbol keyboard (?123)
2. Look for undo/redo buttons

### Method 3: Ctrl+Z / Ctrl+Y

If your layout supports modifiers:

1. Hold Ctrl modifier
2. Tap Z for Undo
3. Tap Y for Redo

## Gesture Detection Concepts

When implemented, circle detection will work as follows:

### Detection Algorithm

1. Track finger path as sequence of points
2. Calculate cumulative angle change
3. If total angle >= 360° (or -360°), circle detected
4. Direction determined by sign of angle sum

### Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| **Min Points** | 20 | Minimum touch points for valid circle |
| **Min Angle** | 300° | Minimum rotation for detection |
| **Max Time** | 1000ms | Maximum time to complete circle |
| **Min Radius** | 30px | Minimum circle size |

## Tips for When Implemented

- **Smooth motion**: Draw a fluid circle, not a polygon
- **Size matters**: Medium-sized circles work best
- **Speed**: Not too fast, not too slow
- **Complete the circle**: Ensure at least 300° rotation

## Related Features

- [Short Swipes](short-swipes.md) - Quick gesture access
- [Cursor Navigation](cursor-navigation.md) - Text navigation
- [Text Selection](../clipboard/text-selection.md) - Select and edit text

## Technical Details

See [Circle Gestures Technical Specification](../specs/gestures/circle-gestures-spec.md).
