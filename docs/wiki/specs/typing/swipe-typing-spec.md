---
title: Swipe Typing - Technical Specification
user_guide: ../../typing/swipe-typing.md
status: implemented
version: v1.2.7
---

# Swipe Typing Technical Specification

## Overview

Neural swipe typing uses an ONNX transformer model with beam search to predict words from gesture paths.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Trajectory Processor | `SwipeTrajectoryProcessor.kt` | Convert touch points to key sequence |
| Neural Engine | `NeuralPredictionEngine.kt` | ONNX model inference |
| Beam Search | `BeamSearchDecoder.kt` | Find top-k word predictions |
| Vocabulary | `OptimizedVocabulary.kt` | Dictionary and trie lookup |
| Keyboard Grid | `KeyboardGrid.kt` | Map coordinates to keys |

## Architecture

```
Touch Events (Pointers.kt)
    ↓
SwipeTrajectoryProcessor
    ↓ (key sequence)
NeuralPredictionEngine
    ↓ (token probabilities)
BeamSearchDecoder
    ↓ (top-k candidates)
SuggestionHandler → UI
```

## Neural Model

| Property | Value |
|----------|-------|
| **Format** | ONNX Runtime Mobile |
| **Architecture** | Transformer encoder |
| **Input** | Key token sequence |
| **Output** | Probability distribution over vocabulary |
| **Size** | ~2 MB per language |

## Beam Search Configuration

From `Config.kt`:

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Beam Width** | `neural_beam_width` | 6 | 1-15 |
| **Max Length** | `neural_max_length` | 20 | 10-50 |
| **Prediction Count** | `prediction_count` | 3 | 1-5 |

## Key Methods

### SwipeTrajectoryProcessor.kt

```kotlin
// Line ~120: Convert swipe to tokens
fun processTrajectory(points: List<TouchPoint>): List<Int>

// Line ~180: Get nearest key for point
fun getNearestKeyToken(x: Float, y: Float): Int
```

### NeuralPredictionEngine.kt

```kotlin
// Line ~80: Run inference
suspend fun predict(tokens: IntArray): FloatArray

// Line ~150: Load ONNX model
fun loadModel(context: Context, language: String)
```

## Performance Metrics

Typical inference times:

| Device Tier | Inference Time |
|-------------|----------------|
| **High-end** | 15-25 ms |
| **Mid-range** | 30-50 ms |
| **Low-end** | 80-150 ms |

## Related Specifications

- [Neural Prediction](../../../specs/neural-prediction.md) - Full architecture details
- [Gesture System](../../../specs/gesture-system.md) - Touch event handling
- [Autocorrect Specification](autocorrect-spec.md)
