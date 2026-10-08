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

### Show Exact Typed Word

Offer the letters you actually typed as an extra suggestion when they aren't already a
dictionary word or a prediction. Tapping it adds the word to your dictionary. **Default: On.**

| Setting | Result |
|---------|--------|
| **Enabled** | Typing an unknown word (2+ letters) shows it as a tap-to-add suggestion |
| **Disabled** | Only dictionary predictions appear |

### Next-Word Prediction

Suggest the next word before you type a letter. **Default: On.** Uses a built-in phrase model
(for English, built from public text corpora), so it works even with Privacy & Data > Learn
From My Typing off; with learning and Context-Aware Predictions on, your own learned phrases
are added and ranked first.
See [Next-Word Prediction](../typing/next-word-prediction.md) for a full walkthrough.

### Context Source

Which phrase model boosts predictions while you type:

| Option | Behavior |
|--------|----------|
| **Both** (default) | Best of the built-in phrase model and your learned patterns |
| **Learned only** | Only your own learned phrase patterns |
| **Built-in only** | Only the shipped phrase model |

### Personalization Strength

How strongly your personal word usage boosts predictions, from 0.0 (off) to 2.0
(double strength). Default: 1.0.

### Learning & Data

Shows what the keyboard has learned on this device — per-language phrase-pattern counts
and word-usage stats — with **Browse phrases**, **Browse words**, **Forget phrases**, and
**Forget words** controls. Individual learned entries can be deleted from the browse
dialogs. Learned data is included in dictionary exports (Backup & Restore).

#### Max Learned Words

Caps how many words the personalization vocabulary keeps (1000–20000, default 5000).
When the vocabulary is full — or you lower the cap below the current word count — the
least-valuable words (rarely and least-recently used) are evicted first.

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

Time before a held key counts as a long press (200–1000 ms, default 600 ms). It also sets
how long you hold before the [Subkey Popover](../gestures/subkey-popover.md) opens:

| Duration | Use Case |
|----------|----------|
| **Shorter** | Fast access to long-press actions and the popover |
| **Longer** | Avoid accidental activation |

### Long Press Interval

Repeat rate when holding a key that repeats (25–200 ms, default 25 ms):

| Setting | Effect |
|---------|--------|
| **Shorter** | Faster key repeating |
| **Longer** | Slower key repeating |

> [!NOTE]
> Which keys repeat depends on **Key Repeat Enabled** and **Backspace Only Repeat** (on by
> default: only Backspace and navigation keys repeat). With the Subkey Popover on, holding a
> character key opens the popover instead of repeating it, whatever these settings say.

### Double Tap Shift for Caps Lock

Double-tap shift key to enable caps lock mode.

## Gesture Tuning Section

### Subkey Popover on Hold

Under **Hold for Subkeys**. When on, holding a character key shows its subkeys around your
finger; slide to one and let go to type it, or let go in the middle to cancel. See
[Subkey Popover](../gestures/subkey-popover.md).

| Setting | Default | Description |
|---------|---------|-------------|
| **Subkey popover on hold** | On for new installs, off after an upgrade | Turns the popover on or off. Replaces key repeat on character keys. |
| **Neutral zone width** | 60% | Width of the middle area where letting go does nothing, as % of the key width (20–150%) |
| **Neutral zone height** | 60% | Height of that area, as % of the key height (20–150%) |

The two sliders are shown only while the popover is on.

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
- [Subkey Popover](../gestures/subkey-popover.md) - Hold a key to pick its subkeys
- [Accessibility](accessibility.md) - Haptic feedback settings
- [Next-Word Prediction](../typing/next-word-prediction.md) - Next-word suggestions (built-in + learned)
- [Privacy Settings](privacy.md) - The Learn From My Typing master switch
