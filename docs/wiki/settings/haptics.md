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
| **Access** | Scroll to **Accessibility** section in Settings |
| **Events** | 5 configurable haptic events |

## Settings Location

In **Settings**, scroll to the **Accessibility** section (collapsible). All haptic/vibration settings are here.

## Master Settings

### Vibration Toggle

| Setting | Effect |
|---------|--------|
| **On** | Haptics enabled for selected events |
| **Off** | All haptics disabled |

### Use System Vibration vs Custom Duration

By default the **Use System Vibration** switch is on: key presses use Android's built-in
haptic pattern, the same low-latency, OEM-tuned effect the stock keyboard uses. It feels
snappier than a manual vibration because the effect is pre-loaded by the hardware.

Turn the switch off to use a **custom vibration** instead, and set its length with the
**Custom Duration (optional)** slider (5–100 ms in 5 ms steps, 20 ms by default). The slider
is dimmed while the switch is on; the stored length applies again as soon as you turn the
switch off. Turning the switch back on is the way back to the system pattern.

| Setting | Feel |
|---------|------|
| **Use System Vibration on** (default) | System haptic — fast, subtle, OEM-tuned |
| **Short custom** (5–15 ms) | Subtle, brief feedback |
| **Medium custom** (20–35 ms) | Noticeable tactile response |
| **Long custom** (40–100 ms) | Pronounced, strong vibration |

> [!NOTE]
> A bug in older versions could silently switch installs to custom-duration mode with no way
> back. Current versions run a one-time cleanup (installs that never changed the duration
> return to the system pattern) and the **Use System Vibration** switch is the explicit way
> back for everyone else.

## Per-Event Haptics

CleverKeys supports 5 specific haptic events:

| Event | Default | Description |
|-------|---------|-------------|
| **Key Press** | On | Vibrate on every key tap |
| **Suggestion Tap** | On | Vibrate when tapping a suggestion |
| **TrackPoint Activate** | On | Vibrate when entering navigation mode |
| **Long Press** | On | Vibrate when long-press detected |
| **Swipe Complete** | On | Vibrate when swipe word completes |

> [!NOTE]
> Swipe Complete confirms that a swiped word was recognized — helpful while learning the gesture. Turn it off here if it feels distracting during rapid typing.

## Configuring Haptics

### Enable/Disable Individual Events

Each of the 5 events can be toggled independently:

1. Open **Settings**
2. Scroll to **Accessibility** section
3. Toggle individual event switches

### Adjust Intensity

1. In the **Accessibility** section, turn off **Use System Vibration**
2. Set **Custom Duration (optional)** between 5 and 100 ms
3. Turn **Use System Vibration** back on whenever you want the system pattern again

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
| Suggestion Tap | On |
| Swipe Complete | On |

Feedback on completions, not every key.

## Tips and Tricks

- **TrackPoint feedback**: Keep enabled to feel when navigation mode activates
- **Battery**: Haptics use minimal power; intensity makes little difference
- **Silent mode**: System silent mode doesn't affect keyboard haptics
- **No vibration?**: Check Android system settings for vibration permissions

> [!TIP]
> Enable TrackPoint Activate haptic to clearly feel when you enter navigation mode.

## Common Questions

### Q: Why don't I feel any haptics?

A: Check these in order:
1. Master Vibration toggle is On
2. Specific event toggle is On
3. If **Use System Vibration** is off, the custom duration is not at its 5 ms minimum
4. Android system haptics/vibration is enabled
5. Device vibration motor is working (test in another app)

### Q: Do haptics drain battery?

A: Minimally. The vibration motor uses very little power compared to the screen.

### Q: Can I have different haptics per key?

A: No, haptic settings apply to event types (all key presses, all predictions, etc.), not individual keys.

### Q: Why does my keyboard vibrate after every swiped word?

A: That's the Swipe Complete haptic — it confirms the word was recognized, which is especially useful while learning swipe typing. If you find it distracting, turn off **Swipe Complete** in the Accessibility section.

## Related Features

- [Appearance](appearance.md) - Visual settings
- [TrackPoint Mode](../gestures/trackpoint-mode.md) - Navigation mode
- [Swipe Typing](../typing/swipe-typing.md) - Gesture typing
