# Neural Swipe Prediction System

## Overview

Pure ONNX neural transformer architecture for converting swipe gestures into ranked word predictions. This replaces legacy template-matching (CGR) with deep learning inference: trajectory → encoder → beam search decoder → vocabulary filter → predictions.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/OnnxSwipePredictorImpl.kt` | `OnnxSwipePredictorImpl` | Core prediction pipeline (~1500 lines) |
| `src/main/kotlin/tribixbite/cleverkeys/SwipeTrajectoryProcessor.kt` | Feature extraction | Smoothing, velocity, normalization |
| `src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabularyImpl.kt` | `OptimizedVocabulary` | Dictionary filtering |
| `src/main/kotlin/tribixbite/cleverkeys/data/LanguageDetector.kt` | `LanguageDetector` | Multi-language detection |
| `assets/models/swipe_model_character_quant.onnx` | Encoder model | ~4MB quantized |
| `assets/models/swipe_decoder_character_quant.onnx` | Decoder model | ~4MB quantized |

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
│  - Batched decoder inference (beam_width=8)                 │
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

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `neural_beam_width` | Int | 6 | Beam search width (more = better quality, slower) |
| `neural_max_length` | Int | 20 | Maximum word length in characters |
| `neural_confidence_threshold` | Float | 0.3 | Minimum confidence to show prediction |

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

### Token Mapping

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

**Encoder Model** (`swipe_model_character_quant.onnx`):
- Type: Transformer encoder (6 layers, 8 attention heads)
- Input 1: `trajectory_features` [batch, 150, 6]
- Input 2: `nearest_keys` [batch, 150]
- Input 3: `src_mask` [batch, 150]
- Output: `memory` [batch, 150, 256]
- Size: ~4MB (INT8 quantized)

**Decoder Model** (`swipe_decoder_character_quant.onnx`):
- Type: Transformer decoder (character-level)
- Input 1: `memory` [batch, 150, 256]
- Input 2: `tgt_input_ids` [batch, seq_len]
- Output: `logits` [batch, seq_len, 35]
- Vocabulary: 35 tokens (special + a-z + punctuation)
- Size: ~4MB (INT8 quantized)

### Performance Characteristics

| Operation | Target | Method |
|-----------|--------|--------|
| Encoder inference | < 30ms | INT8 quantization |
| Decoder inference | < 50ms | Batched beam search |
| Total latency | < 100ms | Memory pooling |

### Memory Optimization

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
