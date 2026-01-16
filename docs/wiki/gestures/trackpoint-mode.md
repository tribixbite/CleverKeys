---
title: TrackPoint Mode
description: Joystick-style cursor control on navigation keys
category: Gestures
difficulty: advanced
related_spec: ../specs/gestures/trackpoint-mode-spec.md
---

# TrackPoint Mode

TrackPoint mode turns your arrow keys into a virtual joystick for continuous cursor movement. Hold the navigation key and move your finger to control the cursor like a laptop TrackPoint.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Precise, continuous cursor movement |
| **Activation** | Hold any arrow key past long-press timeout |
| **Movement** | Move finger in desired direction |

## How It Works

When you hold an arrow key:

1. After the long-press timeout (~600ms), TrackPoint mode activates
2. A distinct haptic pulse indicates activation
3. Your finger position becomes the "center" of the joystick
4. Moving your finger away from center moves the cursor
5. The further from center, the faster the cursor moves

## How to Use

### Step 1: Access Navigation Keys

The navigation keys (arrows) are typically accessed via:
- Short swipe on the compose key (bottom row)
- Or directly if you've added them to your layout

### Step 2: Hold the Arrow Key

Touch and hold any arrow key. Wait for the haptic pulse indicating TrackPoint activation.

### Step 3: Move Your Finger

Without lifting, move your finger:

| Direction | Cursor Movement |
|-----------|-----------------|
| **Up** | Cursor moves up |
| **Down** | Cursor moves down |
| **Left** | Cursor moves left |
| **Right** | Cursor moves right |
| **Diagonal** | Both directions simultaneously |

### Step 4: Release

Lift your finger to exit TrackPoint mode.

## Speed Control

Cursor speed depends on finger distance from center:

```
           Slow
            |
   Slow ----+---- Fast
            |
           Fast
```

- **Near center**: Dead zone (no movement)
- **Edge of key**: Medium speed
- **Beyond key**: Maximum speed

## Tips and Tricks

- **Precise positioning**: Stay near center for fine control
- **Quick jumps**: Move to edge of key for fast movement
- **Diagonal movement**: X and Y axes work independently
- **Center reset**: Your finger position when activated becomes the center

> [!TIP]
> The center point is set where your finger is when TrackPoint activates, not the key center.

## Difference from Regular Arrow Keys

| Feature | Regular | TrackPoint |
|---------|---------|------------|
| **Activation** | Short swipe or tap | Long hold |
| **Movement** | One step per input | Continuous |
| **Speed** | Fixed | Variable (distance-based) |
| **Direction** | One at a time | Any, including diagonal |

## Haptic Feedback

TrackPoint mode uses distinct haptic patterns:

| Event | Feedback |
|-------|----------|
| **Activation** | Clock tick pattern |
| **Each cursor move** | Light tick (optional) |
| **Exit** | None |

Configure in Settings > Haptics > TrackPoint Activate.

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Long Press Timeout** | Input Behavior | Time to activate TrackPoint |
| **TrackPoint Haptic** | Haptics | Enable/disable activation feedback |

## Related Features

- [Cursor Navigation](cursor-navigation.md) - Basic cursor movement
- [Selection Delete](selection-delete.md) - Similar joystick for text selection
- [Short Swipes](short-swipes.md) - Quick access to arrow keys

## Technical Details

See [TrackPoint Mode Technical Specification](../specs/gestures/trackpoint-mode-spec.md) for implementation details including:
- FLAG_P_TRACKPOINT_MODE flag
- Joystick dead zone calculation
- Speed interpolation algorithm
