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
dialogs. Each browse dialog lists everything learned, not just the top entries: type in its
search field to filter (capitals and accents are ignored, so `cafe` finds `café`); the count
above the list shows the total, or "12 of 1,234" while searching, and more rows load as you
scroll. Learned data is included in dictionary exports (Backup & Restore).

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
> default: only Backspace and arrow keys repeat, and keys with arrow corners such as space go
> into TrackPoint mode; volume and other key-event keys act once). With the Subkey Popover on, holding a
> character key opens the popover instead of repeating it, whatever these settings say.
> Holding Backspace still repeats (after the Long Press Timeout, every Long Press Interval).

### Delete One key=value Pair at a Time

On by default. In a link or any other text with `key=value` pairs joined by `&`
(`?id=42&utm_source=news`, `size=m&color=red`), **Delete word** (swipe left or up-left on
Backspace, or a Delete Word key) removes one pair per press, so tracking parameters come off a
long link one at a time and the link stays valid. Turn it off to delete those characters as an
ordinary word instead. See the [FAQ](../FAQ.md).

### Double Tap to Lock Shift, Ctrl and Fn

On by default. Tap Shift twice quickly for caps lock. Ctrl, Fn, Alt and Meta work the same way:
tap one twice quickly and it stays on for every key you type until you tap it again. The second
tap has to start within 400 ms of the first one's release; a slower second tap turns the key off,
as one tap on a key that is on always does. With this setting off, the second tap releases the key
at any speed.

With TalkBack, or when a key is activated through Switch Access or Voice Access, any second
activation locks: those cannot tap twice in under half a second.

Holding one of these keys:

| Subkey popover on hold | This setting | Holding the key |
|---|---|---|
| On | On | Shows its subkeys ([Subkey Popover](../gestures/subkey-popover.md)); a key with none locks |
| On | Off | Locks it |
| Off | either | Locks it |

With TalkBack on, holding always locks: the popover is not shown there.

Alt and Meta are not on the default layout: they are optional subkeys of Fn and Ctrl (turn them on
in Extra Keys), or keys you add in a custom layout.

Holding a modifier while you tap other keys with another finger applies it to those keys, as on a
hardware keyboard: hold Shift and tap `a` for `A`, hold Ctrl and tap `c` for Ctrl+C. The modifier
turns off when you lift it. A Shift that goes down just before the next letter (fast typing) works
the same way.

## Gesture Tuning Section

### Subkey Popover on Hold

Under **Hold for Subkeys**. When on, holding a character key shows its subkeys around your
finger; slide to one and let go to type it, or let go in the middle to cancel. See
[Subkey Popover](../gestures/subkey-popover.md).

| Setting | Default | Description |
|---------|---------|-------------|
| **Subkey popover on hold** | On; off after an upgrade only if holding a letter repeated it | Turns the popover on or off. Replaces key repeat on character keys. While Double Tap to Lock is on, it also replaces the locking hold of Shift, Ctrl, Fn, Alt and Meta. |
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
