---
title: Input Behavior Settings
description: Configure typing behavior and text processing
category: Settings
difficulty: intermediate
---

# Input Behavior Settings

Configure how CleverKeys processes your input, including capitalization, punctuation, and gesture behavior.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Control typing behavior |
| **Access** | Settings > Input (and related sections) |
| **Options** | Auto-cap, double-space, smart punct, gestures |

## Word Prediction Section

### Auto-Space After Suggestion

Automatically add a space after tapping a suggestion:

| Setting | Result |
|---------|--------|
| **Enabled** | "hello" → "hello " (space added) |
| **Disabled** | "hello" → "hello" (no space) |

### Capitalize I Words

Automatically capitalize "I" and its contractions:

| Words Affected |
|----------------|
| I, I'm, I'll, I'd, I've |

## Input Section

### Autocapitalization

Automatically capitalize letters after sentence-ending punctuation:

| Setting | Behavior |
|---------|----------|
| **Enabled** | Capitalize after . ! ? |
| **Disabled** | Never auto-capitalize |

### Smart Punctuation

Automatic punctuation formatting:

| Feature | Example |
|---------|---------|
| **Auto-space after punct** | "hello," → "hello, " |
| **Remove space before punct** | "hello ," → "hello," |

### Long Press Timeout

Time before long press activates:

| Duration | Use Case |
|----------|----------|
| **Shorter** | Fast access to long-press actions |
| **Longer** | Avoid accidental activation |

### Long Press Interval

Repeat rate when holding a key:

| Setting | Effect |
|---------|--------|
| **Shorter** | Faster key repeating |
| **Longer** | Slower key repeating |

### Double Tap Shift for Caps Lock

Double-tap shift key to enable caps lock mode.

## Gesture Tuning Section

### Double-Space to Period

Insert period and space when tapping space twice:

| Setting | Result |
|---------|--------|
| **Enabled** | "hello  " → "hello. " |
| **Disabled** | "hello  " → "hello  " |

### Double-Space Timing

Adjust the timing window for double-space detection.

### Swipe Distance Threshold

How far to swipe before recognizing a short swipe gesture:

| Level | Use Case |
|-------|----------|
| **Lower** | More sensitive, easier activation |
| **Higher** | Requires more intentional swipes |

## Tips and Tricks

- **Fast typing**: Lower thresholds and shorter timeouts
- **Precision**: Higher thresholds, longer timeouts
- **Error-prone**: Raise swipe distance threshold

> [!TIP]
> If you're getting accidental short swipes, increase the swipe distance threshold.

## Common Questions

### Q: Why isn't autocapitalization working?

A: Check if it's enabled in Settings > Input section. Some apps may override keyboard behavior.

### Q: How do I disable double-space period?

A: Settings > Gesture Tuning > Double-Space to Period > Off.

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - Gesture configuration
- [Accessibility](accessibility.md) - Haptic feedback settings
