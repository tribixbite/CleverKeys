---
title: Neural Prediction Settings
description: Configure AI-powered swipe predictions
category: Settings
difficulty: advanced
---

# Neural Prediction Settings

Fine-tune the neural network-based swipe typing predictions for accuracy and performance.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Optimize neural predictions |
| **Access** | Scroll to **Neural Prediction** section in Settings |
| **Key Setting** | Beam Width controls accuracy vs speed |

## Settings Location

In **Settings**, scroll to the **Neural Prediction** section (collapsible). All neural prediction settings are here.

## Understanding Neural Predictions

CleverKeys uses an ONNX neural network to predict words from swipe gestures. The model processes your swipe trajectory and outputs probability distributions for each letter position.

## Key Settings

### Beam Width

The most important setting for prediction quality. Controls how many parallel word candidates the decoder tracks:

| Width | Effect |
|-------|--------|
| **3-4** | Faster, may miss less common words |
| **6** | Balanced (default) |
| **8-10** | More thorough search, finds rare words |

> [!NOTE]
> Higher beam width = more word candidates explored = better accuracy but slightly slower.

### Show Suggestions

Toggle the prediction/suggestion bar above the keyboard:

| Setting | Effect |
|---------|--------|
| **On** | Shows word predictions above keyboard |
| **Off** | Hides prediction bar (more screen space) |

### Autocorrect from Beam

When enabled, uses neural predictions for autocorrection:

| Setting | Effect |
|---------|--------|
| **On** | Neural model helps with tap-typing corrections |
| **Off** | Only dictionary-based autocorrect |

## Advanced Settings

### Confidence Threshold

Minimum score for a prediction to be shown (default: 0.01):

- Lower = more suggestions, some may be weak
- Higher = only confident predictions shown

### Frequency Weight

How much word frequency influences scoring (default: 0.57):

| Weight | Effect |
|--------|--------|
| **0.0** | Neural model only |
| **0.5** | Balanced |
| **1.0+** | Favor common words heavily |

### Length Penalty (Beam Alpha)

Normalizes scores by word length (default: 1.0):

- Lower = favors shorter words
- Higher = allows longer words to compete

## Neural Profiles

CleverKeys includes preset profiles optimizing for different use cases:

| Profile | Beam Width | Use Case |
|---------|------------|----------|
| **Speed** | 4 | Fast typing, acceptable accuracy |
| **Balanced** | 6 | Default experience |
| **Accuracy** | 8+ | Best predictions, slower |

Access via the Neural Prediction section settings.

## Tips and Tricks

- **Accuracy issues**: Increase beam width from 6 to 8 or 10
- **Slow predictions**: Reduce beam width to 4-5
- **Missing words**: Check that multi-language is configured correctly
- **Long words wrong**: Increase beam width or adjust beam alpha

> [!TIP]
> Start with the default beam width of 6. Only adjust if you notice specific issues.

## All Neural Settings

| Setting | Default | Range/Options |
|---------|---------|---------------|
| **Beam Width** | 6 | 3-10 |
| **Show Suggestions** | On | On/Off |
| **Confidence Threshold** | 0.01 | 0.01-0.5 |
| **Frequency Weight** | 0.57 | 0.0-2.0 |
| **Beam Alpha** | 1.0 | 0.5-2.0 |
| **Beam Autocorrect** | On | On/Off |

## Common Questions

### Q: Why are predictions slow?

A: Reduce beam width from 6 to 4-5. Also check if multiple languages are enabled, which increases processing.

### Q: Why does it suggest wrong words?

A: Try increasing beam width for more thorough search. Also ensure your primary language is set correctly in Multi-Language section.

### Q: How do I add words to the dictionary?

A: Type the word and tap it in predictions, or long-press a word and select "Add to dictionary" if supported.

### Q: Can I reset neural settings?

A: Go to Settings and look for reset options, or use Backup & Restore to reset to defaults.

## Technical Details

The neural model is:
- **Format**: ONNX (Open Neural Network Exchange)
- **Architecture**: Encoder-only transformer
- **Input**: Normalized swipe coordinates (x, y, time)
- **Output**: Per-position letter probabilities
- **Decoding**: Beam search with vocabulary constraint

## Related Features

- [Swipe Typing](../typing/swipe-typing.md) - How to swipe type
- [Autocorrect](../typing/autocorrect.md) - Text correction
- [Multi-Language](../layouts/multi-language.md) - Multi-language predictions
