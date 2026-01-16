---
title: Input Behavior Settings
description: Configure typing behavior and text processing
category: Settings
difficulty: intermediate
related_spec: ../specs/settings/input-behavior-spec.md
---

# Input Behavior Settings

Configure how CleverKeys processes your input, including auto-capitalization, punctuation, and special behaviors.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Control typing behavior |
| **Access** | Settings > Input |
| **Options** | Auto-cap, double-space, smart punct |

## Auto-Capitalization

Automatically capitalize letters:

| Setting | Behavior |
|---------|----------|
| **Off** | Never auto-capitalize |
| **Sentences** | Capitalize after . ! ? |
| **Words** | Capitalize each word (for names) |
| **Characters** | All uppercase |

### How Auto-Cap Works

```
Sentence mode:
"hello world. " → next letter capitalized
"what?! " → next letter capitalized
```

## Double-Space Period

Insert period and space when tapping space twice:

| Setting | Result |
|---------|--------|
| **Enabled** | "hello  " → "hello. " |
| **Disabled** | "hello  " → "hello  " |

### Behavior Details

- Works after letters, not punctuation
- Adds period and capitalizes next word
- Can be disabled per-app

## Smart Punctuation

Automatic punctuation formatting:

| Feature | Example |
|---------|---------|
| **Auto-space after punct** | "hello," → "hello, " |
| **Remove space before punct** | "hello ," → "hello," |
| **Smart quotes** | "test" → "test" |

## Delete Behavior

### Backspace Mode

| Option | Behavior |
|--------|----------|
| **Character** | Delete one character |
| **Selection** | Delete selection first |

### Delete Word

| Setting | Effect |
|---------|--------|
| **Whole Word** | Delete entire word |
| **To Word Boundary** | Delete to space/punctuation |

## Swipe Sensitivity

### Swipe Threshold

How far to swipe before registering:

| Level | Use Case |
|-------|----------|
| **Low** | Very sensitive, easy activation |
| **Normal** | Balanced |
| **High** | Requires intentional swipe |

### Swipe Speed

Minimum swipe velocity:

| Setting | Effect |
|---------|--------|
| **Slow** | Accept slow swipes |
| **Normal** | Standard speed requirement |
| **Fast** | Require quick swipes |

## Gesture Tuning

### Long Press Delay

Time before long press activates:

| Duration | Use Case |
|----------|----------|
| **Short (200ms)** | Fast access |
| **Normal (600ms)** | Balanced |
| **Long (600ms)** | Avoid accidental activation |

### Short Swipe Distance

How far to swipe for subkey:

| Distance | Effect |
|----------|--------|
| **Short** | Easy activation, may be accidental |
| **Normal** | Balanced |
| **Long** | Requires intentional swipe |

## Cursor Control

### Cursor Movement Speed

In trackpoint/cursor mode:

| Speed | Behavior |
|-------|----------|
| **Slow** | Precise movement |
| **Normal** | Balanced |
| **Fast** | Quick navigation |

### Snap to Words

| Setting | Effect |
|---------|--------|
| **Enabled** | Cursor snaps to word boundaries |
| **Disabled** | Free movement |

## Tips and Tricks

- **Fast typing**: Lower thresholds and delays
- **Precision**: Higher thresholds, longer delays
- **One-handed**: Increase sensitivity
- **Error-prone**: Raise thresholds

> [!TIP]
> If you're getting accidental swipes, increase the swipe threshold.

## All Input Settings

| Setting | Location | Default |
|---------|----------|---------|
| **Auto-Capitalize** | Input | Sentences |
| **Double-Space Period** | Input | On |
| **Smart Punctuation** | Input | On |
| **Long Press Delay** | Input | 600ms |
| **Swipe Threshold** | Input | Normal |
| **Short Swipe Distance** | Gestures | Normal |

## Common Questions

### Q: Why isn't auto-capitalize working?

A: Check if it's enabled in Settings > Input. Some apps override keyboard behavior.

### Q: How do I disable double-space period?

A: Settings > Input > Double-Space Period > Off.

### Q: Can I have different settings per app?

A: Some settings (like auto-cap) respect app hints. Full per-app settings may be added in future.

## Related Features

- [Autocorrect](../typing/autocorrect.md) - Text correction
- [Gestures](../gestures/short-swipes.md) - Gesture configuration
- [Accessibility](accessibility.md) - Touch accommodation

## Technical Details

See [Input Behavior Technical Specification](../specs/settings/input-behavior-spec.md).
