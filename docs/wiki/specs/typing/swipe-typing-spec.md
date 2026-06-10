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

## Gesture Sampling Robustness

There is **no minimum-speed gate** on swipe typing — a slow swipe is not rejected for being slow. Word activation depends on registering ≥2 keys plus `swipe_min_distance` of path, which a slow-but-complete swipe satisfies just like a fast one (path length is bounded by key geometry, not by speed).

`ImprovedSwipeGestureRecognizer.addPoint` does, however, drop samples whose inter-sample gap exceeds `MAX_POINT_INTERVAL_MS` (500 ms). To keep a **mid-gesture pause** (a deliberate swiper holding still to aim) from permanently stalling the gesture, the recognizer re-anchors `_lastPointTime` to the resume timestamp when a long gap is seen, then resumes on the next sample. Without this re-anchor a single >500 ms gap left `_lastPointTime` stale, so every later sample's delta grew larger and the remainder of the swipe was dropped — the second key never registered and no word was produced.

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

| Setting | Key | Default | Range | Source |
|---------|-----|---------|-------|--------|
| **Beam Width** | `neural_beam_width` | 6 | 1-32 | `Config.kt:130`, validator `backup/SettingsValidation.kt` |
| **Max Length** | `neural_max_length` | 20 | 10-50 | `Config.kt` (NEURAL_MAX_LENGTH) |
| **Confidence Threshold** | `neural_confidence_threshold` | 0.01 | 0.0-1.0 | `Config.kt:132` (NEURAL_CONFIDENCE_THRESHOLD) |
| **ONNX Models** | bundled assets | — | — | `src/main/assets/models/swipe_{encoder,decoder}_android.onnx` |

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

- [Neural Prediction](neural-prediction-spec.md) - Deeper architectural reference (beam search algorithm, token mapping, model I/O shapes, memory pooling)
- [Gesture System Overview](../gestures/gesture-system-overview-spec.md) - Touch event routing and `hasLeftStartingKey` gatekeeper
- [Autocorrect Specification](autocorrect-spec.md)
