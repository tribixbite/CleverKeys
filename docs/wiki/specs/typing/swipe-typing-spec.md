---
title: Swipe Typing - Technical Specification
user_guide: ../../typing/swipe-typing.md
status: implemented
version: v1.2.7
---

# Swipe Typing Technical Specification

## Overview

Swipe typing routes each completed gesture to one of three decode engines — the neural ONNX transformer (default), the CTC trie-beam engine, or the geometric (SHARK2-style) engine — selected by the `swipe_engine_mode` preference plus the active layout and language. All engines feed the same downstream suggestion pipeline.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Engine Router | `swipe/SwipeEngineRouter.kt` | Mode + layout → engine selection (`Mode.NEURAL/HYBRID/GEOMETRIC/CTC`) |
| Trajectory Processor | `SwipeTrajectoryProcessor.kt` | Convert touch points to key sequence (neural path) |
| Neural Engine | `NeuralPredictionEngine.kt` | ONNX model inference |
| Beam Search | `BeamSearchDecoder.kt` | Find top-k word predictions (neural path) |
| CTC Adapter | `swipe/CtcEngineAdapter.kt` | CTC engine boundary: layout/trie/session memos, contraction display, warm-up |
| CTC Core | `swipe/ctc/` (`CtcBeamDecoder.kt`, `CtcFeaturizer.kt`, `CtcLexiconTrie.kt`, ...) | Pure-JVM CTC Viterbi trie beam over ONNX emissions |
| Geometric Adapter | `swipe/GeometricEngineAdapter.kt` | Geometric engine boundary (non-QWERTY layouts) |
| Vocabulary | `OptimizedVocabulary.kt` | Dictionary and trie lookup |
| Keyboard Grid | `KeyboardGrid.kt` | Map coordinates to keys |

## Architecture

```
Touch Events (Pointers.kt)
    ↓
InputCoordinator.handleSwipeTyping
    ↓ SwipeEngineRouter.route(layout, mode)
    ├─ NEURAL    → SwipeTrajectoryProcessor → NeuralPredictionEngine → BeamSearchDecoder
    ├─ CTC       → CtcEngineAdapter (en only; non-en falls through to the neural branch)
    ├─ GEOMETRIC → GeometricEngineAdapter
    └─ NONE      → no swipe typing (non-QWERTY layout in Neural mode)
    ↓ (top-k candidates, engine-relative scores)
SuggestionHandler.handleSwipePredictionResults → UI
```

## Engine Routing (`swipe_engine_mode`)

The router (`swipe/SwipeEngineRouter.kt`) is layout-only; the `ctc` mode's language gate lives in `InputCoordinator.performCtcSwipeTyping`, which reads the active dictionary language before dispatch and falls through to the neural flow for non-English:

| Mode | QWERTY-Latin + English | QWERTY-Latin + other language | Non-QWERTY layout |
|------|------------------------|-------------------------------|-------------------|
| `neural` (default) | NEURAL | NEURAL | NONE |
| `hybrid` | NEURAL | NEURAL | GEOMETRIC |
| `geometric` | GEOMETRIC | GEOMETRIC | GEOMETRIC |
| `ctc` | CTC | NEURAL | GEOMETRIC |

One engine owns each swipe end-to-end; scores are engine-relative and never compared across engines. Suggestion provenance tags the engine that actually decoded (`SuggestionProvenance.forRoutedEngine`) — e.g. a non-QWERTY swipe under `ctc` mode is tagged GEOMETRIC. The CTC engine maps contraction aliases to display forms ("dont" → "don't") inside its adapter before the shared pipeline. Full CTC engine internals: `docs/specs/ctc-swipe-engine.md` (engineering spec).

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
| **Prediction Engine** | `swipe_engine_mode` | `"neural"` | `neural` / `hybrid` / `geometric` / `ctc` (case-canonicalized at read) | `Config.kt` (SWIPE_ENGINE_MODE), `swipe/SwipeEngineRouter.kt` |
| **Beam Width** | `neural_beam_width` | 6 | 1-32 | `Config.kt:130`, validator `backup/SettingsValidation.kt` |
| **Max Length** | `neural_max_length` | 20 | 10-50 | `Config.kt` (NEURAL_MAX_LENGTH) |
| **Confidence Threshold** | `neural_confidence_threshold` | 0.01 | 0.0-1.0 | `Config.kt:132` (NEURAL_CONFIDENCE_THRESHOLD) |
| **CTC Beam Width** | `ctc_beam_width` | 100 | 10-300 (clamped at load and per decode) | `Config.kt` (CTC_BEAM_WIDTH), `CtcSettingsActivity.kt` |
| **ONNX Models** | bundled assets | — | — | `src/main/assets/models/swipe_{encoder,decoder}_android.onnx` (neural), `models/ctc_swipe_encoder.onnx` (CTC, 2.91 MB) |

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
