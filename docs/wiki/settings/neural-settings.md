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

### Prediction Engine

Selects which decoder handles swipes (see [Swipe Typing](../typing/swipe-typing.md#choosing-a-prediction-engine) for the full per-layout/per-language table):

| Option | Behavior |
|--------|----------|
| **Neural** (default) | Transformer model on QWERTY layouts; no swipe on other layouts |
| **Hybrid** | Neural on QWERTY, geometric decoder on all other layouts |
| **Geometric** | Geometric decoder on every layout |
| **CTC** | CTC model on QWERTY for English; neural for other languages; geometric on non-QWERTY layouts |

With CTC selected, a **Full CTC Settings** button opens the CTC beam-width knob (default 100, range 10–300). The **Full Geometric Settings** button is available whenever a mode that can use the geometric engine is selected (Hybrid, Geometric, or CTC).

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

### Max Word Length

Maximum predicted word length in characters (default: 20). Longer words will be truncated.

## Full Neural Settings

For advanced tuning (batch processing, greedy search, ONNX threads, beam search parameters, inference tuning, and presets), tap the **Full Neural Settings** button at the bottom of the Neural Prediction section. This opens a dedicated activity with all neural parameters.

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
| **Prediction Engine** | Neural | Neural / Hybrid / Geometric / CTC |
| **Swipe on Password Fields** | Off | On/Off |
| **Beam Width** (neural) | 6 | 1-20 |
| **Confidence Threshold** | 0.01 | 0.01-0.5 |
| **Max Word Length** | 20 | 5-50 |
| **ONNX Threads** | 2 | 1-8 |
| **CTC Beam Width** (CTC engine) | 100 | 10-300 |

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
- **Architecture**: Encoder-decoder transformer
- **Input**: Normalized swipe coordinates (x, y, time)
- **Output**: Per-position letter probabilities
- **Decoding**: Beam search with vocabulary constraint

## Related Features

- [Swipe Typing](../typing/swipe-typing.md) - How to swipe type
- [Multi-Language](../layouts/multi-language.md) - Multi-language predictions
