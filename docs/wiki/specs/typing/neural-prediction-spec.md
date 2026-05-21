---
title: Neural Prediction - Technical Specification
description: Deep architectural reference for the ONNX neural swipe prediction pipeline (trajectory → encoder → beam-search decoder → vocabulary filter)
status: implemented
version: v1.4.0
user_guide: ../../typing/swipe-typing.md
---

# Neural Prediction Technical Specification

## Overview

Pure ONNX neural transformer architecture for converting swipe gestures into ranked word predictions. This replaces legacy template-matching (CGR) with deep learning inference: trajectory → encoder → beam search decoder → vocabulary filter → predictions.

The runtime pipeline is composed of small, independently testable modules orchestrated by `SwipePredictorOrchestrator`. Each module wraps one phase of the pipeline behind an interface so the beam search engine can be exercised against fake decoders in pure JVM tests.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/onnx/SwipePredictorOrchestrator.kt` | `SwipePredictorOrchestrator` | Top-level orchestrator (singleton) wiring all components |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/EncoderWrapper.kt` | `EncoderWrapper` | Encoder ONNX session adapter |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/DecoderWrapper.kt` | `DecoderWrapper` | Decoder ONNX session adapter (`runDecoder`) |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/BeamSearchEngine.kt` | `BeamSearchEngine` | Beam search with trie guidance, adaptive pruning, length normalization |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/PredictionPostProcessor.kt` | `PredictionPostProcessor` | Score normalization, dedup, vocabulary filtering |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/MemoryPool.kt` | `MemoryPool` | Pre-allocated tensor / buffer pool |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/TensorFactory.kt` | `TensorFactory` | ONNX tensor construction helpers |
| `src/main/kotlin/tribixbite/cleverkeys/SwipeTokenizer.kt` | `SwipeTokenizer` | Token ↔ character mapping (loads `tokenizer_config.json`) |
| `src/main/kotlin/tribixbite/cleverkeys/SwipeTrajectoryProcessor.kt` | `SwipeTrajectoryProcessor` | Smoothing, velocity, normalization, nearest-key detection |
| `src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabulary.kt` | `OptimizedVocabulary` | Dictionary filtering + trie lookup |
| `src/main/kotlin/tribixbite/cleverkeys/LanguageDetector.kt` | `LanguageDetector` | Multi-language detection |
| `src/main/assets/models/swipe_encoder_android.onnx` | Encoder model | Quantized transformer encoder |
| `src/main/assets/models/swipe_decoder_android.onnx` | Decoder model | Quantized character-level decoder |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    SwipeInput (Touch Events)                 │
│  coordinates: List<PointF>, timestamps: List<Long>          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│           SwipeTrajectoryProcessor (Feature Extraction)      │
│  - smoothTrajectory() (moving average, window=3)            │
│  - calculateVelocities() (first derivative)                 │
│  - calculateAccelerations() (second derivative)             │
│  - normalizeCoordinates() [0,1]                             │
│  - detectNearestKeys() (character indices)                  │
│  - padOrTruncate(150 points)                                │
│                                                              │
│  Output: trajectory_features [1, 150, 6]                    │
│          nearest_keys [1, 150]                              │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              runEncoder() (Transformer Inference)            │
│  Input:  trajectory_features [1, 150, 6]                    │
│          nearest_keys [1, 150]                              │
│          src_mask [1, 150]                                  │
│  Output: memory [1, 150, 256]                               │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              runBeamSearch() (Character Decoding)            │
│  - Batched decoder inference (beam_width=6)                 │
│  - Expand hypotheses, track log-probabilities               │
│  - Terminate on EOS or max_length=20                        │
│  Output: List<Beam(tokens, score)>                          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              SwipeTokenizer (Token ↔ Character)              │
│  decode([2,8,5,12,12,15,3]) → "hello"                       │
│  Special tokens: SOS=2, EOS=3, PAD=0, UNK=1                 │
│  Vocabulary: a-z (4-29), space (30), ' (31), - (32)         │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│           OptimizedVocabulary (Dictionary Filter)            │
│  - Filter OOV predictions                                   │
│  - Rank by frequency + neural confidence                    │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     PredictionResult                         │
│  words: List<String>, scores: List<Int>, confidences        │
└─────────────────────────────────────────────────────────────┘
```

## Configuration

Defaults live in `Defaults` (Config.kt:130-132). Validator clamps are in `backup/SettingsValidation.kt`.

| Key | Type | Default | Valid Range | Description |
|-----|------|---------|-------------|-------------|
| `neural_beam_width` | Int | 6 | 1..32 | Beam search width (more = better quality, slower) |
| `neural_max_length` | Int | 20 | — | Maximum word length in characters (must match model export) |
| `neural_confidence_threshold` | Float | 0.01 | — | Minimum confidence to keep a candidate during beam search |

## Implementation Details

### Feature Extraction Pipeline

```kotlin
// Input: SwipeInput
val rawCoords = swipeInput.coordinates      // [(100,250), (102,251), ...]
val timestamps = swipeInput.timestamps      // [1728567890123, 1728567890140, ...]

// Step 1: Smoothing (moving average, window=3)
val smoothed = smoothTrajectory(rawCoords)

// Step 2: Velocity (first derivative)
val velocities = calculateVelocities(smoothed, timestamps)
// Formula: velocity = distance / time_delta (pixels/sec)

// Step 3: Acceleration (second derivative)
val accelerations = calculateAccelerations(velocities, timestamps)

// Step 4: Normalization [0,1]
val normalized = normalizeCoordinates(smoothed)
// normalized_x = x / keyboardWidth, normalized_y = y / keyboardHeight

// Step 5: Nearest key detection
val nearestKeys = detectNearestKeys(normalized)
// Returns character indices: a=4, b=5, ..., z=29

// Step 6: Padding to 150 points
val (features, keys, mask) = padOrTruncate(
    normalized, velocities, accelerations, nearestKeys, 150
)

// Output tensors:
// trajectory_features: [1, 150, 6] (x, y, vx, vy, ax, ay)
// nearest_keys: [1, 150] (character indices)
// src_mask: [1, 150] (attention mask - 1=real, 0=padding)
```

### Beam Search Algorithm

```kotlin
// Initialize beams with SOS token
var beams = listOf(Beam(tokens=[SOS], score=0.0))

for (step in 0 until max_length) {
    // BATCHED inference: all beams in single model call
    val batchSize = beams.size
    val inputIds = beams.map { it.tokens }.toBatchTensor()  // [batch, seq_len]

    // Run decoder model
    val logits = runDecoder(memory, inputIds)  // [batch, vocab_size]

    // Expand each beam
    val newBeams = mutableListOf<Beam>()
    for ((beamIdx, beam) in beams.withIndex()) {
        val topK = logits[beamIdx].topK(beam_width)

        for ((tokenIdx, logProb) in topK) {
            if (tokenIdx == EOS) {
                finishedBeams.add(beam.copy(score = beam.score + logProb))
            } else {
                newBeams.add(Beam(
                    tokens = beam.tokens + tokenIdx,
                    score = beam.score + logProb
                ))
            }
        }
    }

    // Keep top beam_width beams by score
    beams = newBeams.sortedByDescending { it.score }.take(beam_width)

    if (beams.isEmpty()) break
}

return finishedBeams.sortedByDescending { it.score }
```

`BeamSearchEngine` additionally implements (see `BeamSearchEngine.kt:26-48`):

- **Trie-guided decoding** — logit masking against `VocabularyTrie` so only in-vocabulary prefixes can extend
- **Adaptive width pruning** — narrows the beam after step 12 if the top hypothesis dominates (`adaptiveWidthConfidence=0.8f`)
- **Score-gap early stopping** — terminates after step 10 if the score gap to the top beam exceeds `scoreGapThreshold=8.0f`
- **Length-normalized scoring** — `lengthPenaltyAlpha=1.0f`
- **Language-specific prefix boost** — Aho-Corasick `PrefixBoostTrie` rewards prefixes of the active language's frequent words
- **Strict start char** — optionally constrains beams to the first detected key character

### Token Mapping

The tokenizer is data-driven: `SwipeTokenizer.loadFromAssets()` reads `assets/models/tokenizer_config.json` and populates the bidirectional maps. The default layout matches the model export:

```kotlin
val CHAR_MAP = mapOf(
    4 to 'a', 5 to 'b', 6 to 'c', 7 to 'd', 8 to 'e',
    9 to 'f', 10 to 'g', 11 to 'h', 12 to 'i', 13 to 'j',
    14 to 'k', 15 to 'l', 16 to 'm', 17 to 'n', 18 to 'o',
    19 to 'p', 20 to 'q', 21 to 'r', 22 to 's', 23 to 't',
    24 to 'u', 25 to 'v', 26 to 'w', 27 to 'x', 28 to 'y',
    29 to 'z', 30 to ' ', 31 to '\'', 32 to '-'
)

// Special tokens
const val PAD = 0
const val UNK = 1
const val SOS = 2
const val EOS = 3

fun decode(tokens: List<Int>): String {
    return tokens
        .filter { it !in listOf(SOS, EOS, PAD, UNK) }
        .mapNotNull { CHAR_MAP[it] }
        .joinToString("")
}

// Example:
// tokens: [2, 8, 5, 12, 12, 15, 3]  (SOS, h, e, l, l, o, EOS)
// decoded: "hello"
```

### Model Architecture

**Encoder Model** (`swipe_encoder_android.onnx`):
- Type: Transformer encoder
- Input 1: `trajectory_features` [batch, 250, 6] (model export ceiling — see `MODEL_MAX_SEQUENCE_LENGTH` in `SwipePredictorOrchestrator.kt:40`)
- Input 2: `nearest_keys` [batch, 250]
- Input 3: `src_mask` [batch, 250]
- Output: `memory` [batch, 250, hidden_dim]
- Size: ~4MB (quantized)

**Decoder Model** (`swipe_decoder_android.onnx`):
- Type: Transformer decoder (character-level)
- Input 1: `memory` [batch, src_len, hidden_dim]
- Input 2: `tgt_input_ids` [batch, seq_len]
- Output: `logits` [batch, seq_len, vocab_size]
- Vocabulary: tokens for special + a-z + punctuation, loaded from `tokenizer_config.json`
- Decoder sequence length is fixed at 20 by export (see `BeamSearchEngine.kt:60`)
- Size: ~4MB (quantized)

> **Note**: The encoder ONNX graph has its `trajectory_features` input shape baked in at export time (`max_seq_length=250` per `model_config.json`). Feeding a longer tensor causes `ORT_INVALID_ARGUMENT` (issue #136). Any user-supplied override is clamped to this ceiling by `SwipePredictorOrchestrator`.

### Performance Characteristics

| Operation | Target | Method |
|-----------|--------|--------|
| Encoder inference | < 30ms | Quantization |
| Decoder inference | < 50ms | Batched beam search |
| Total latency | < 100ms | Memory pooling |

### Memory Optimization

Pre-allocated buffers in `MemoryPool` avoid per-inference allocation:

```kotlin
// OptimizedTensorPool prevents allocation during inference
class OptimizedTensorPool {
    private val featureBuffer = FloatArray(150 * 6)
    private val keyBuffer = LongArray(150)
    private val maskBuffer = FloatArray(150)

    fun getFeatureTensor(): OnnxTensor {
        return OnnxTensor.createTensor(env, featureBuffer, shape)
    }
}
```

## Related Specifications

- [Swipe Typing](swipe-typing-spec.md) - User-facing swipe typing feature spec
- [Autocorrect](autocorrect-spec.md) - Post-prediction correction layer
- [User Dictionary](user-dictionary-spec.md) - Vocabulary customization
- [Gesture System Overview](../gestures/gesture-system-overview-spec.md) - Touch routing pipeline that feeds neural prediction
