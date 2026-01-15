---
title: Selection Delete
description: Select and delete text with backspace gesture
category: Gestures
difficulty: advanced
related_spec: ../specs/gestures/selection-delete-spec.md
---

# Selection Delete

Selection Delete mode lets you select text by holding backspace and moving your finger like a joystick. When you release, the selected text is deleted.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Quickly select and delete text |
| **Activation** | Short swipe + hold on backspace |
| **Movement** | Joystick-style text selection |

## How It Works

1. Do a short swipe on backspace and hold
2. Move your finger to select text (like TrackPoint)
3. Release to delete the selected text

The gesture combines selection and deletion in one fluid motion.

## How to Use

### Step 1: Short Swipe on Backspace

Touch backspace and do a small swipe in any direction, then **hold** without lifting.

### Step 2: Selection Mode Activates

After holding briefly, selection mode activates. You'll feel a haptic pulse.

### Step 3: Move to Select

Move your finger in the direction you want to select:

| Direction | Selection |
|-----------|-----------|
| **Left** | Select characters to the left |
| **Right** | Select characters to the right |
| **Up** | Select lines above |
| **Down** | Select lines below |

### Step 4: Release to Delete

Lift your finger. All selected text is deleted.

> [!WARNING]
> This action cannot be easily undone. Select carefully!

## Bidirectional Selection

You can change direction while selecting:

1. Start selecting left
2. Move finger right to reduce selection
3. Move further right to select in the other direction

The selection follows your finger movement continuously.

## Vertical Selection

Moving up/down selects by lines:

| Setting | Description |
|---------|-------------|
| **Vertical Threshold** | How much vertical movement triggers line selection |
| **Vertical Speed** | How fast line selection moves |

Configure in Settings > Gesture Tuning > Selection-Delete Mode.

## Speed Control

Like TrackPoint, speed depends on distance:

- **Near center**: Slow selection (precise)
- **Far from center**: Fast selection
- **Vertical**: Slower than horizontal (configurable)

## Tips and Tricks

- **Small deletions**: Don't move far, quick release
- **Large deletions**: Swipe to edge for fast selection
- **Change direction**: Move opposite way to deselect
- **Cancel**: Move back to center and release (selects nothing)

> [!TIP]
> Practice with non-important text first. The gesture takes some getting used to.

## Difference from Regular Backspace

| Action | Regular Backspace | Selection Delete |
|--------|-------------------|------------------|
| **Gesture** | Tap or hold | Short swipe + hold |
| **Deletes** | One char / word at a time | Selected region at once |
| **Direction** | Backwards only | Any direction |
| **Speed** | Fixed repeat rate | Variable |

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Vertical Threshold** | Gesture Tuning | % of key height for vertical activation |
| **Vertical Speed** | Gesture Tuning | Speed multiplier for line selection |

Default values:
- Vertical Threshold: 40%
- Vertical Speed: 0.4x (slower than horizontal)

## Related Features

- [TrackPoint Mode](trackpoint-mode.md) - Similar joystick for cursor
- [Cursor Navigation](cursor-navigation.md) - Basic cursor movement
- [Short Swipes](short-swipes.md) - Regular short swipe on backspace deletes word

## Technical Details

See [Selection Delete Technical Specification](../specs/gestures/selection-delete-spec.md) for implementation details including:
- FLAG_P_SELECTION_DELETE_MODE flag
- Shift+Arrow key simulation
- Axis tracking algorithm
