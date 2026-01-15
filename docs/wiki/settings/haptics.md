---
title: Haptics Settings
description: Configure vibration feedback for keyboard actions
category: Settings
difficulty: beginner
related_spec: ../specs/settings/haptics-spec.md
---

# Haptics Settings

Configure vibration feedback for key presses, gestures, and special events.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Control vibration feedback |
| **Access** | Settings > Haptics |
| **Options** | Intensity, events, patterns |

## Haptic Feedback

### Master Toggle

| Setting | Effect |
|---------|--------|
| **On** | Haptics enabled |
| **Off** | All haptics disabled |

### Vibration Intensity

| Level | Description |
|-------|-------------|
| **Light** | Subtle feedback |
| **Medium** | Noticeable but not strong |
| **Strong** | Pronounced vibration |
| **System** | Uses system haptic settings |

## Per-Event Haptics

Configure vibration for specific events:

### Key Press

| Setting | When |
|---------|------|
| **Enabled** | Vibrate on every key tap |
| **Disabled** | No key press vibration |

### Special Keys

| Key Type | Default | Description |
|----------|---------|-------------|
| **Backspace** | On | Delete feedback |
| **Space** | Off | Space bar tap |
| **Enter** | On | Return/submit |
| **Shift** | On | Modifier activation |

### Gesture Events

| Gesture | Default | Description |
|---------|---------|-------------|
| **Short Swipe** | On | Subkey activation |
| **Long Press** | On | Long press detected |
| **Mode Activation** | Strong | TrackPoint/Selection mode |
| **Circle Gesture** | On | Undo/redo circle |

### Text Events

| Event | Default | Description |
|-------|---------|-------------|
| **Autocorrect** | Light | Word corrected |
| **Prediction Selected** | Light | Tap on prediction |
| **Word Completed** | Off | Swipe word finished |

## Haptic Patterns

Different vibration patterns for different events:

| Pattern | Used For | Feel |
|---------|----------|------|
| **Click** | Key press | Short, sharp |
| **Tick** | Subtle events | Very brief |
| **Heavy** | Mode changes | Longer, stronger |
| **Double** | Confirmations | Two quick pulses |

## OLED Protection

For OLED screens, some users prefer haptics to confirm key presses without visual feedback:

1. Enable haptics for all key events
2. Consider disabling key pop-up
3. Reduce key press animation

## Tips and Tricks

- **Battery saving**: Disable or reduce haptics intensity
- **Silent environments**: Disable haptics to avoid noise
- **Accessibility**: Haptics help confirm input without looking
- **Learning gestures**: Enable gesture haptics while learning

> [!TIP]
> Use "Strong" haptics for mode activations (TrackPoint, Selection) to clearly feel when modes engage.

## Haptic Events Reference

| Event | Setting Key | Description |
|-------|-------------|-------------|
| **Key Press** | `haptic_key` | Regular key tap |
| **Backspace** | `haptic_backspace` | Delete key |
| **Enter** | `haptic_enter` | Submit/return |
| **Space** | `haptic_space` | Spacebar |
| **Shift** | `haptic_shift` | Shift/caps |
| **Short Swipe** | `haptic_short_swipe` | Subkey access |
| **Long Press** | `haptic_long_press` | Hold gesture |
| **TrackPoint** | `haptic_trackpoint` | Mode activation |
| **Selection** | `haptic_selection` | Selection mode |
| **Circle** | `haptic_circle` | Circle gesture |
| **Autocorrect** | `haptic_autocorrect` | Word correction |
| **Prediction** | `haptic_prediction` | Tap prediction |

## Intensity Levels

| Level | Amplitude | Duration |
|-------|-----------|----------|
| **Off** | 0 | 0ms |
| **Light** | 50 | 10ms |
| **Medium** | 128 | 15ms |
| **Strong** | 255 | 25ms |

## Common Questions

### Q: Why don't I feel any haptics?

A: Check system haptics settings first, then CleverKeys haptics settings. Some devices require system haptics enabled.

### Q: Do haptics drain battery?

A: Yes, but minimally. If concerned, reduce intensity or disable for frequent events like key press.

### Q: Can I have different haptics for different layouts?

A: Haptic settings apply globally. Per-layout haptics may be added in future.

## Related Features

- [Appearance](appearance.md) - Visual feedback options
- [Accessibility](accessibility.md) - Alternative feedback
- [Input Behavior](input-behavior.md) - Touch sensitivity

## Technical Details

See [Haptics Technical Specification](../specs/settings/haptics-spec.md).
