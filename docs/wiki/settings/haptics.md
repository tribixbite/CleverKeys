---
title: Haptics Settings
description: Configure vibration feedback for keyboard actions
category: Settings
difficulty: beginner
---

# Haptics Settings

Configure vibration feedback for key presses, gestures, and special events.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Control vibration feedback |
| **Access** | Scroll to **Haptics** section in Settings |
| **Events** | 5 configurable haptic events |

## Settings Location

In **Settings**, scroll to the **Haptics** section (collapsible). All haptic settings are here.

## Master Settings

### Haptic Feedback Toggle

| Setting | Effect |
|---------|--------|
| **On** | Haptics enabled for selected events |
| **Off** | All haptics disabled |

### Haptic Feedback Duration

Control vibration intensity via duration:

| Duration | Feel |
|----------|------|
| **Short** | Subtle, brief feedback |
| **Medium** | Noticeable tactile response |
| **Long** | Pronounced, strong vibration |

## Per-Event Haptics

CleverKeys supports 5 specific haptic events:

| Event | Setting Key | Default | Description |
|-------|-------------|---------|-------------|
| **Key Press** | `haptic_key_press` | On | Vibrate on every key tap |
| **Prediction Tap** | `haptic_prediction_tap` | On | Vibrate when tapping a suggestion |
| **TrackPoint Activate** | `haptic_trackpoint_activate` | On | Vibrate when entering navigation mode |
| **Long Press** | `haptic_long_press` | On | Vibrate when long-press detected |
| **Swipe Complete** | `haptic_swipe_complete` | Off | Vibrate when swipe word completes |

> [!NOTE]
> Swipe Complete is disabled by default as it can be distracting during rapid typing.

## Configuring Haptics

### Enable/Disable Individual Events

Each of the 5 events can be toggled independently:

1. Open **Settings**
2. Scroll to **Haptics** section
3. Toggle individual event switches

### Adjust Intensity

1. In **Haptics** section
2. Find **Haptic Feedback Duration** slider
3. Adjust for desired intensity

## Recommended Settings

### For Learning Gestures

| Event | Setting |
|-------|---------|
| Key Press | On |
| Long Press | On |
| TrackPoint Activate | On |
| Swipe Complete | On |

Strong feedback helps confirm when gestures are recognized.

### For Silent/Battery Saving

| Event | Setting |
|-------|---------|
| All events | Off |

Or reduce duration to minimum.

### For Swipe Typing Focus

| Event | Setting |
|-------|---------|
| Key Press | Off |
| Prediction Tap | On |
| Swipe Complete | On |

Feedback on completions, not every key.

## Tips and Tricks

- **TrackPoint feedback**: Keep enabled to feel when navigation mode activates
- **Battery**: Haptics use minimal power; intensity makes little difference
- **Silent mode**: System silent mode doesn't affect keyboard haptics
- **No vibration?**: Check Android system settings for vibration permissions

> [!TIP]
> Enable TrackPoint Activate haptic to clearly feel when you enter navigation mode with the navigation keys.

## Common Questions

### Q: Why don't I feel any haptics?

A: Check these in order:
1. Master haptic toggle is On
2. Specific event toggle is On
3. Duration is not at minimum
4. Android system haptics/vibration is enabled
5. Device vibration motor is working (test in another app)

### Q: Do haptics drain battery?

A: Minimally. The vibration motor uses very little power compared to the screen.

### Q: Can I have different haptics per key?

A: No, haptic settings apply to event types (all key presses, all predictions, etc.), not individual keys.

### Q: Why is Swipe Complete off by default?

A: It vibrates after every swipe word, which many users find distracting during continuous typing. Enable it if you want confirmation that swipes are recognized.

## Technical Details

| Setting | Preference Key | Default |
|---------|----------------|---------|
| Key Press | `haptic_key_press` | `true` |
| Prediction Tap | `haptic_prediction_tap` | `true` |
| TrackPoint Activate | `haptic_trackpoint_activate` | `true` |
| Long Press | `haptic_long_press` | `true` |
| Swipe Complete | `haptic_swipe_complete` | `false` |
| Custom Duration | `vibrate_duration` | System default |

## Related Features

- [Appearance](appearance.md) - Visual feedback options
- [TrackPoint Mode](../gestures/trackpoint-mode.md) - Navigation mode
- [Swipe Typing](../typing/swipe-typing.md) - Gesture typing
