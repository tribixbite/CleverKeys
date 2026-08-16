---
title: Swipe Typing
description: Draw paths through letters to type words
category: Typing
difficulty: beginner
featured: true
---

# Swipe Typing

Swipe typing lets you type words by drawing a continuous path through letters. CleverKeys ships several on-device prediction engines — a neural transformer (the default), a CTC model, and a geometric decoder — and you choose how they are combined with the Prediction Engine setting.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Type words faster by swiping |
| **Gesture** | Draw path through letters without lifting finger |
| **Engines** | Neural transformer (default), CTC, geometric — selectable in Settings |

## How It Works

Instead of tapping each letter:

1. **Touch the first letter** of your word
2. **Slide your finger** through each letter in order
3. **Lift your finger** at the last letter
4. The word appears in the text

For example, to type "hello":
- Touch **h** → slide to **e** → slide to **l** → slide to **l** → lift at **o**

## How to Use

### Step 1: Start on the First Letter

Touch and hold the first letter of your word. A trail appears showing your path.

### Step 2: Draw Through Letters

Without lifting your finger, slide through each letter in sequence. You don't need to be perfectly accurate - the AI predicts your intended word.

### Step 3: Lift to Complete

Lift your finger when you reach the last letter. The predicted word appears in your text.

### Step 4: Choose from Predictions

If the wrong word appears:
- Check the prediction bar for alternatives
- Tap the correct word to replace it

## Tips for Better Accuracy

- **Start and end precisely**: Begin and end on the correct letters
- **Hit key letters**: Pass through distinctive letters in the word
- **Maintain steady speed**: Don't rush or pause mid-word
- **Use longer swipes**: Longer words are easier to predict than short ones

> [!TIP]
> Double letters (like 'll' in 'hello') can be swiped in a small loop or just passed through once.

## Prediction Bar

After swiping, predictions appear in a horizontal row ordered by confidence (best match on the left). The first suggestion is auto-inserted and highlighted. Up to 6 alternatives may appear depending on beam width.

Tap any prediction to use it instead.

## Choosing a Prediction Engine

The **Prediction Engine** dropdown (Settings > Swipe Typing) selects which decoder handles your swipes. The right engine is picked automatically per swipe, based on your layout and language:

| Mode | QWERTY layout, English | QWERTY layout, other language | Non-QWERTY layout (Dvorak, Cyrillic, ...) |
|------|------------------------|-------------------------------|-------------------------------------------|
| **Neural** (default) | Neural | Neural | No swipe typing |
| **Hybrid** | Neural | Neural | Geometric |
| **Geometric** | Geometric | Geometric | Geometric |
| **CTC** | CTC | Neural | CTC on other Latin layouts (Dvorak, Colemak…) in English; Geometric otherwise |

- **Neural** — the transformer model swipe typing has always used. It is trained on QWERTY, so non-QWERTY layouts get no swipe typing in this mode.
- **Hybrid** — neural where it was trained (QWERTY), geometric everywhere else, so every layout has swipe typing.
- **Geometric** — a pure shape-matching decoder (no neural network) on all layouts. Useful for comparison and battery-lean decoding.
- **CTC** — a newer CleverKeys-trained model for English on QWERTY. In our benchmark on 2,400 held-out swipes it got the intended word right on the first try about 89% of the time, ahead of the neural engine (~75%) on the same set. It currently supports English only — swiping in another language automatically uses the neural engine instead, and non-QWERTY layouts use the geometric engine, so choosing CTC never gives you less coverage than Hybrid.

Whichever engine decodes a swipe, the results flow through the same suggestion bar, autocorrect, and contraction handling ("dont" is shown as "don't"). If you enable suggestion origin markers, each suggestion is tagged with the engine that actually produced it.

### CTC Settings

With the CTC engine selected, a **Full CTC Settings** button appears with one tuning knob:

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| **Beam Width** | 100 | 10–300 | How many word hypotheses the decoder keeps while tracing your swipe. 100 is the validated default; higher values cost CPU per swipe for marginal accuracy. |

The CTC scoring constants are calibrated offline and deliberately not user-tunable.

## Settings

Tune swipe typing in Settings > Swipe Typing:

| Setting | Description |
|---------|-------------|
| **Swipe Typing** | Enable/disable swipe input |
| **Prediction Engine** | Neural / Hybrid / Geometric / CTC (see above) |
| **Beam Width** (neural) | More candidates = more accurate but slower (default: 6, max: 20) |
| **Confidence Threshold** | Minimum confidence for predictions |
| **Max Word Length** | Maximum predicted word length in characters (default: 20) |
| **Backspace Undo Swipe** | Backspace deletes entire swiped word + auto-space (default: on) |

## Undoing a Swipe

If the wrong word was predicted after swiping:

### Quick Undo with Backspace

Press **backspace immediately** after swiping (before typing anything else). The entire swiped word and its trailing auto-space are deleted in one press, so you can swipe again.

This behavior is controlled by the **Backspace Undo Swipe** toggle in Settings > Word Prediction (enabled by default). When disabled, backspace deletes a single character as normal.

### Choose an Alternative

Check the prediction bar — alternative words are shown left-to-right by confidence. Tap any alternative to replace the auto-inserted word.

## When Swipe Typing Doesn't Work

Swipe typing may not activate when:
- Swipe typing is disabled in settings
- Typing in password fields (unless enabled in settings)
- The swipe is too short (detected as tap)
- No language pack is available

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - Quick access to subkeys
- [Autocorrect](autocorrect.md) - Fix mistakes automatically
