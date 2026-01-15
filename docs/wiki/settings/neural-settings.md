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
| **Access** | Settings > Neural/Predictions |
| **Options** | Beam width, thresholds, vocabulary |

## Understanding Neural Predictions

CleverKeys uses an ONNX neural network to predict words from swipe gestures. These settings control how the network processes your input.

## Prediction Settings

### Prediction Count

How many suggestions to show:

| Count | Trade-off |
|-------|-----------|
| **3** | Faster, less choice |
| **5** | Balanced |
| **7** | More options, slightly slower |

### Beam Width

Controls prediction search breadth:

| Width | Effect |
|-------|--------|
| **3** | Faster, may miss some words |
| **5** | Balanced (default) |
| **8** | More thorough, slightly slower |
| **10** | Most accurate, slower |

> [!NOTE]
> Higher beam width finds more word candidates but uses more processing time.

## Threshold Settings

### Minimum Score

Predictions below this score are filtered:

| Threshold | Effect |
|-----------|--------|
| **Low (0.1)** | More suggestions, some poor |
| **Medium (0.3)** | Balanced filtering |
| **High (0.5)** | Only confident predictions |

### Distance Threshold

Maximum gesture-to-key distance:

| Setting | Effect |
|---------|--------|
| **Loose** | Accept approximate swipes |
| **Normal** | Standard tolerance |
| **Strict** | Require precise swipes |

## Vocabulary Settings

### Personal Words

| Setting | Description |
|---------|-------------|
| **Include Personal** | Add your words to predictions |
| **Personal Priority** | Boost score for personal words |

### Technical Terms

| Setting | Description |
|---------|-------------|
| **Enable Tech Vocab** | Include programming terms |
| **Camelcase** | Support camelCase words |

## Performance Settings

### Processing Mode

| Mode | Description |
|------|-------------|
| **Balanced** | Good accuracy and speed |
| **Performance** | Faster, reduced accuracy |
| **Quality** | Best accuracy, slower |

### Background Processing

| Setting | Effect |
|---------|--------|
| **Enabled** | Pre-process during pauses |
| **Disabled** | Process only on demand |

## Learning Settings

### Adaptive Learning

| Setting | Description |
|---------|-------------|
| **Enabled** | Learn from your typing |
| **Disabled** | Use base model only |

### Learn Rate

| Rate | Effect |
|------|--------|
| **Slow** | Gradual adaptation |
| **Normal** | Balanced learning |
| **Fast** | Quick adaptation |

## Tips and Tricks

- **Accuracy issues**: Increase beam width
- **Slow predictions**: Reduce beam width, enable performance mode
- **Missing words**: Lower minimum score threshold
- **Personal words**: Add frequently used terms to dictionary

> [!TIP]
> If predictions feel "off," try resetting neural settings to defaults and gradually adjusting.

## All Neural Settings

| Setting | Default | Range |
|---------|---------|-------|
| **Prediction Count** | 5 | 3-7 |
| **Beam Width** | 5 | 3-10 |
| **Min Score** | 0.3 | 0.1-0.7 |
| **Distance Threshold** | Normal | Loose/Normal/Strict |
| **Personal Priority** | 1.2x | 1.0-2.0x |
| **Processing Mode** | Balanced | Performance/Balanced/Quality |

## Advanced Diagnostics

### Debug Mode

Enable to see prediction scores:

1. Settings > Neural > Show Debug Info
2. Predictions show score values
3. Helps understand prediction ranking

### Reset Neural Data

Start fresh:

1. Settings > Neural > Reset
2. Clears learned patterns
3. Returns to base model

## Common Questions

### Q: Why are predictions slow?

A: Reduce beam width or switch to Performance mode. Also check if many languages are enabled.

### Q: Why does it suggest wrong words?

A: Increase beam width for more thorough search, or adjust distance threshold for your swipe style.

### Q: How do I make it learn my vocabulary?

A: Enable Adaptive Learning and add words to personal dictionary. They'll be prioritized.

## Related Features

- [Swipe Typing](../typing/swipe-typing.md) - How to swipe type
- [Autocorrect](../typing/autocorrect.md) - Text correction
- [Multi-Language](../layouts/multi-language.md) - Multi-language predictions
