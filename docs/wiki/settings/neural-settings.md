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

### Swipe Typing

Master toggle to enable/disable swipe input:

| Setting | Effect |
|---------|--------|
| **On** | Swipe typing enabled |
| **Off** | Swipe gestures disabled |

### Swipe on Password Fields

Allow swipe typing in password fields:

| Setting | Effect |
|---------|--------|
| **On** | Swipe works in password fields |
| **Off** | Only tap typing in password fields (default) |

### Beam Width

The most important setting for prediction quality. Controls how many parallel word candidates the decoder tracks:

| Width | Effect |
|-------|--------|
| **3-4** | Faster, may miss less common words |
| **6** | Balanced (default) |
| **8-12** | More thorough search, finds rare words |
| **16-20** | Maximum accuracy, slower |

> [!NOTE]
> Higher beam width = more word candidates explored = better accuracy but slightly slower. Range: 1-20.

### Confidence Threshold

Minimum score for a prediction to be shown:

- Lower = more suggestions, some may be weak
- Higher = only confident predictions shown

### Max Sequence Length

Maximum number of swipe sample points to process. Affects long word handling.

## Advanced Settings

Expand the Advanced subsection for additional tuning:

### ONNX Threads

Number of CPU threads for XNNPACK neural inference. Default: 2 (optimal for most ARM devices). Range: 1-8.

## Tips and Tricks

- **Accuracy issues**: Increase beam width from 6 to 8 or 10
- **Slow predictions**: Reduce beam width to 4-5
- **Missing words**: Check that multi-language is configured correctly
- **Long words wrong**: Increase beam width

> [!TIP]
> Start with the default beam width of 6. Only adjust if you notice specific issues.

## All Neural Settings

| Setting | Default | Range/Options |
|---------|---------|---------------|
| **Swipe Typing** | On | On/Off |
| **Swipe on Password Fields** | Off | On/Off |
| **Beam Width** | 6 | 1-20 |
| **Confidence Threshold** | 0.01 | 0.01-0.5 |
| **Max Sequence Length** | 20 | 5-50 |
| **ONNX Threads** | 2 | 1-8 |

## Common Questions

### Q: Why are predictions slow?

A: Reduce beam width from 6 to 4-5. Also check if multiple languages are enabled, which increases processing.

### Q: Why does it suggest wrong words?

A: Try increasing beam width for more thorough search. Also ensure your primary language is set correctly in Multi-Language section.

### Q: How do I add words to the dictionary?

A: Type the word and tap it in predictions to add it to your personal dictionary.

### Q: Can I reset neural settings?

A: Use Settings > Backup & Restore to reset to defaults.

## Technical Details

The neural model is:
- **Format**: ONNX (Open Neural Network Exchange)
- **Architecture**: Encoder-only transformer
- **Input**: Normalized swipe coordinates (x, y, time)
- **Output**: Per-position letter probabilities
- **Decoding**: Beam search with vocabulary constraint

## Related Features

- [Swipe Typing](../typing/swipe-typing.md) - How to swipe type
- [Multi-Language](../layouts/multi-language.md) - Multi-language predictions
