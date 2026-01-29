---
title: Swipe Typing
description: Draw paths through letters to type words
category: Typing
difficulty: beginner
featured: true
---

# Swipe Typing

Swipe typing lets you type words by drawing a continuous path through letters. CleverKeys uses a neural AI model to predict words from your swipe patterns.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Type words faster by swiping |
| **Gesture** | Draw path through letters without lifting finger |
| **AI Model** | ONNX transformer neural network |

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

## Settings

Tune swipe typing in Settings > Neural Prediction:

| Setting | Description |
|---------|-------------|
| **Swipe Typing** | Enable/disable swipe input |
| **Beam Width** | More candidates = more accurate but slower (default: 6, max: 20) |
| **Confidence Threshold** | Minimum confidence for predictions |
| **Max Word Length** | Maximum predicted word length in characters (default: 20) |

## When Swipe Typing Doesn't Work

Swipe typing may not activate when:
- Swipe typing is disabled in settings
- Typing in password fields (unless enabled in settings)
- The swipe is too short (detected as tap)
- No language pack is available

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - Quick access to subkeys
- [Autocorrect](autocorrect.md) - Fix mistakes automatically
