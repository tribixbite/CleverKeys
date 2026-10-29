---
title: Accessibility Settings
description: Vibration feedback settings and TalkBack use
category: Settings
difficulty: beginner
---

# Accessibility Settings

The Accessibility section of Settings holds the vibration (haptic) settings. CleverKeys has
no key-press sound option.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Vibration feedback |
| **Access** | Settings > Accessibility |
| **Features** | Master vibration switch, system or custom vibration, five haptic events |

## Vibration Settings

### Master Vibration Toggle

Enable/disable all haptic feedback:

| Setting | Effect |
|---------|--------|
| **Enabled** | Vibration feedback active |
| **Disabled** | No vibration feedback |

### Use System Vibration

On by default. Key presses use your phone's built-in haptic pattern, which has the lowest
latency. Turn it off to set your own vibration length.

### Custom Duration (optional)

Active only while **Use System Vibration** is off:

| Range | Description |
|-------|-------------|
| **5–100 ms** (5 ms steps) | Length of each vibration |
| **Default: 20 ms** | Brief, subtle feedback |

## Haptic Feedback Triggers

Configure which actions trigger haptic feedback:

| Trigger | Description |
|---------|-------------|
| **Key Press** | Vibrate on each key tap |
| **Suggestion Tap** | Vibrate when selecting a prediction |
| **TrackPoint** | Vibrate when entering trackpoint mode |
| **Long Press** | Vibrate on long press actions |
| **Swipe Complete** | Vibrate when swipe word is inserted |

Each trigger can be independently enabled/disabled.

## Tips

- **Battery conscious**: Disable vibration to save battery
- **Silent typing**: Turn off **Vibration Feedback**
- **Subtle feedback**: Turn off **Use System Vibration** and set a short duration (5–15 ms)
- **Strong feedback**: Turn off **Use System Vibration** and set a longer duration (40–50 ms)

## TalkBack

With TalkBack on, explore the keyboard by touch: each key announces its name (letters, Shift,
Backspace, Ctrl, Space …) and a double-tap presses it. Shift and Caps Lock announce whether they
are on.

TalkBack uses swipes for its own navigation, so the keyboard never sees a short swipe while it is
on. Every short swipe of a key is offered instead in TalkBack's **Actions** menu for that key:
put focus on the key, open the menu (swipe up then right, or a three-finger tap, depending on
your TalkBack gesture settings) and choose the action. Each entry names what the swipe does and
its direction, for example "Clipboard manager (Southwest)" on Ctrl, which opens the clipboard
pane. The menu lists exactly what a swipe would do at that moment: your custom short-swipe
mappings replace the layout's subkeys, a direction set to "removed" is left out, a latched Shift
applies, and nothing is listed while **Short gestures** is turned off.

[Voice input](../typing/voice-input.md) announces when listening starts, stops or is cancelled.

The subkey popover (hold a key) does not open under TalkBack; its slots are the same short
swipes, so the Actions menu covers them.

## System Integration

CleverKeys respects Android's system-wide accessibility settings where applicable. TalkBack
itself is turned on in Android's accessibility settings.

## Related Features

- [Appearance](appearance.md) - Keyboard height and visual settings
- [Input Behavior](input-behavior.md) - Touch and gesture settings
