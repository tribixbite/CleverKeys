# ONNX Swipe-to-Text Decode Pipeline

**Technical Reference Documentation**
**Last Updated:** 2025-10-10
**Version:** 1.0 (Kotlin Implementation)

---

## 1. High-Level Overview

### Purpose
The ONNX decode pipeline translates a user's swipe gesture path into ranked word predictions using a neural transformer encoder-decoder architecture with beam search decoding.

### Architecture Diagram
```
┌──────────────────────────────────────────────────────────────────────────┐
│                        SWIPE PREDICTION PIPELINE                          │
└──────────────────────────────────────────────────────────────────────────┘

User Swipe Input (Touch Events)
         │
         ├── Raw Data: List<PointF> coordinates + timestamps
         │
         ▼
┌─────────────────────────────────────┐
│  Feature Extraction & Preprocessing │
│  (SwipeTrajectoryProcessor)         │
│  - Smoothing (moving average)       │
│  - Velocity calculation             │
│  - Acceleration calculation         │
│  - Coordinate normalization [0,1]   │
│  - Nearest key detection            │
│  - Padding to 150 points            │
└─────────────────────────────────────┘
         │
         ▼
    Input Tensors
    ├── trajectory_features: [1, 150, 6]
    ├── nearest_keys: [1, 150]
    └── src_mask: [1, 150]
         │
         ▼
┌─────────────────────────────────────┐
│      ONNX Encoder Model             │
│  (swipe_model_character_quant.onnx) │
│  - Transformer encoder layers       │
│  - Position encoding                │
│  - Self-attention mechanisms        │
└─────────────────────────────────────┘
         │
         ▼
    Memory Tensor: [1, 150, 256]
         │
         ▼
┌─────────────────────────────────────┐
│      Beam Search Decoder            │
│  (swipe_decoder_character_quant)    │
│  - Initialize with SOS token        │
│  - BATCHED inference (50-70% speedup)│
│  - Expand beams (beam_width=8)      │
│  - Track hypotheses by score        │
│  - Terminate on EOS or max_length   │
└─────────────────────────────────────┘
         │
         ▼
    Beam Candidates
    [{tokens: [2,5,8,12,3], score: -2.3}, ...]
         │
         ▼
┌─────────────────────────────────────┐
│      Post-Processing                │
│  - Token-to-character decoding      │
│  - Vocabulary filtering             │
│  - Confidence score conversion      │
│  - Ranking by score                 │
└─────────────────────────────────────┘
         │
         ▼
    Final Predictions
    [("hello", 950), ("hallo", 850), ("hells", 720)]
         │
         ▼
    UI Suggestion Bar
```

### Component Responsibilities

| Component | File | Responsibility |
|-----------|------|----------------|
| **Input Processing** | `SwipeInput.kt` | Encapsulate swipe data with computed properties |
| **Feature Extraction** | `OnnxSwipePredictorImpl.kt` (SwipeTrajectoryProcessor) | Transform raw coordinates into ML-ready features |
| **Encoder Inference** | `OnnxSwipePredictorImpl.kt` (runEncoder) | Generate memory representation from trajectory |
| **Beam Search** | `OnnxSwipePredictorImpl.kt` (runBeamSearch) | Decode characters using batched inference |
| **Tokenization** | `OnnxSwipePredictorImpl.kt` (SwipeTokenizer) | Convert tokens ↔ characters |
| **Vocabulary** | `OptimizedVocabulary.kt` | Filter predictions by dictionary |
| **Configuration** | `NeuralConfig.kt` | Manage beam_width, max_length, threshold |

---

## 2. Input Processing & Feature Extraction

### Raw Input Format
**Source:** Android touch events from keyboard view
**Data Structure:** `SwipeInput` data class

```kotlin
data class SwipeInput(
    val coordinates: List<PointF>,  // Screen coordinates (x, y) in pixels
    val timestamps: List<Long>,     // Milliseconds since epoch
    val touchedKeys: List<KeyboardData.Key>
)
```

**Example:**
```
coordinates: [(100.5, 250.3), (102.1, 251.8), ..., (450.2, 240.6)]
timestamps:  [1728567890123, 1728567890140, ..., 1728567890890]
points: 87 (typical swipe)
duration: ~750ms
```

### Preprocessing Steps

#### Step 1: Smoothing
**Algorithm:** Moving average with window size 3
**Purpose:** Reduce touch sensor noise and hand tremor

```kotlin
smoothTrajectory(coordinates: List<PointF>): List<PointF>
    coordinates.windowed(3, partialWindows = true)
        → average each window
        → return smoothed points
```

#### Step 2: Velocity Calculation (First Derivative)
**Formula:** `velocity = distance / time_delta`

```kotlin
calculateVelocities(coords, timestamps): List<Float>
    for each adjacent pair (p1, p2):
        dx = p2.x - p1.x
        dy = p2.y - p1.y
        distance = sqrt(dx² + dy²)
        time_delta = (t2 - t1) / 1000  // seconds
        velocity = distance / time_delta
```

**Physical Meaning:** Pixels per second finger velocity

#### Step 3: Acceleration Calculation (Second Derivative)
**Formula:** `acceleration = velocity_delta / time_delta`

```kotlin
calculateAccelerations(velocities, timestamps): List<Float>
    for each adjacent velocity pair (v1, v2):
        velocity_delta = v2 - v1
        time_delta = (t2 - t1) / 1000
        acceleration = velocity_delta / time_delta
```

**Physical Meaning:** Pixels/second² finger acceleration (captures swipe dynamics)

#### Step 4: Coordinate Normalization
**Normalization Range:** [0, 1] for both x and y
**Purpose:** Device-independent representation

```kotlin
normalizeCoordinates(coordinates): List<PointF>
    for each point:
        normalized_x = (point.x / keyboardWidth).coerceIn(0f, 1f)
        normalized_y = (point.y / keyboardHeight).coerceIn(0f, 1f)
```

**Critical:** Uses actual keyboard dimensions from `setKeyboardDimensions(width, height)`

#### Step 5: Nearest Key Detection
**Method:** Real key positions (when available) or QWERTY grid fallback

```kotlin
detectNearestKeys(coordinates): List<Int>
    for each point:
        if realKeyPositions available:
            find closest key by Euclidean distance
            convert character to token index (a=4, b=5, ..., z=29)
        else:
            use QWERTY grid detection with row offsets
```

**QWERTY Layout Mapping:**
```
Row 0: q w e r t y u i o p  (offset: 0.0)
Row 1: a s d f g h j k l    (offset: 0.05 - shifted right)
Row 2: z x c v b n m        (offset: 0.15 - shifted right)
```

#### Step 6: Padding/Truncation
**Fixed Sequence Length:** 150 points
**Padding Strategy:** Extend with last point or zeros if too short
**Truncation Strategy:** Take first 150 points if too long

```kotlin
padOrTruncate(list, targetSize=150, paddingValue):
    if list.size == targetSize: return list
    if list.size > targetSize: return list.take(150)
    else: return list + repeat(paddingValue, 150 - list.size)
```

### Final Tensor Shapes

#### Trajectory Features Tensor
**Name:** `trajectory_features`
**Shape:** `[1, 150, 6]`
**Data Type:** `float32`
**Format:** Direct FloatBuffer (native ByteOrder)

**Feature Layout (6 dimensions per point):**
```
[x, y, vx, vy, ax, ay]
 │  │  │   │   │   └── Acceleration Y component
 │  │  │   │   └────── Acceleration X component
 │  │  │   └────────── Velocity Y component (using magnitude)
 │  │  └────────────── Velocity X component
 │  └───────────────── Normalized Y coordinate [0,1]
 └──────────────────── Normalized X coordinate [0,1]
```

**Memory:** 3,600 floats × 4 bytes = 14.4 KB per swipe

#### Nearest Keys Tensor
**Name:** `nearest_keys`
**Shape:** `[1, 150]`
**Data Type:** `int64`
**Format:** Direct LongBuffer

**Token Encoding:**
```
0 = PAD (padding)
1 = UNK (unknown)
2 = SOS (start of sequence)
3 = EOS (end of sequence)
4-29 = 'a' through 'z' (26 letters)
```

#### Source Mask Tensor
**Name:** `src_mask`
**Shape:** `[1, 150]`
**Data Type:** `boolean`
**Purpose:** Indicate valid vs padded positions

```
mask[i] = true  → position i is PADDED (ignore in attention)
mask[i] = false → position i is VALID (use in attention)
```

---

## 3. ONNX Model Architecture

### Model Files
**Location:** `assets/models/`

| File | Size | Purpose |
|------|------|---------|
| `swipe_model_character_quant.onnx` | 5.3 MB | Encoder (transformer) |
| `swipe_decoder_character_quant.onnx` | 7.2 MB | Decoder (transformer) |
| `tokenizer.json` | ~50 KB | Character-to-token mapping |

### Encoder Model

**Architecture:** Transformer encoder with position encoding
**Input Schema:**
```
Inputs:
  - trajectory_features: float32[1, 150, 6]
  - nearest_keys: int64[1, 150]
  - src_mask: bool[1, 150]

Outputs:
  - memory: float32[1, 150, 256]
```

**Internal Structure:**
- Embedding layer (trajectory features → 256-dim)
- Positional encoding (sinusoidal)
- 4× Transformer encoder layers
  - Multi-head self-attention (8 heads)
  - Feed-forward network (256 → 1024 → 256)
  - Layer normalization + residual connections

**Inference Time:** ~15-25ms on Android CPU

### Decoder Model

**Architecture:** Transformer decoder with cross-attention
**Input Schema:**
```
Inputs:
  - memory: float32[batch_size, 150, 256]  (from encoder)
  - target_tokens: int64[batch_size, seq_length]  (partial hypothesis)
  - src_mask: bool[1, 150]
  - target_mask: bool[batch_size, seq_length]

Outputs:
  - logits: float32[batch_size, seq_length, vocab_size=30]
```

**Internal Structure:**
- Token embedding layer (30 vocab → 256-dim)
- Positional encoding
- 4× Transformer decoder layers
  - Masked multi-head self-attention (8 heads)
  - Cross-attention to encoder memory
  - Feed-forward network (256 → 1024 → 256)
  - Layer normalization + residual connections
- Output projection (256 → vocab_size=30)

**Inference Time (batched):**
- Single beam: ~8-12ms
- 8 beams batched: ~25-35ms (vs 64-96ms sequential)
- **Speedup:** 50-70% with batched inference

### Quantization
**Type:** INT8 quantization (Post-training quantization)
**Benefits:**
- 4× smaller model size (5.3MB + 7.2MB vs ~50MB original)
- 2-3× faster inference
- Minimal accuracy loss (<2%)

**Execution Providers:**
- Default: CPU (ONNX Runtime 1.20.0)
- Not available: QNN (Qualcomm NPU), XNNPACK (mobile optimization)

---

## 4. Beam Search Decoder Pipeline

### Algorithm Overview

**Beam Search** is a heuristic search algorithm that explores the graph of possible output sequences by maintaining a fixed number (`beam_width`) of the most probable partial sequences at each decoding step.

### Pseudocode

```
function beamSearchDecode(memory, src_mask, beam_width, max_length):
    # Initialize
    beams = [BeamState(tokens=[SOS], score=0.0, finished=false)]
    finished_beams = []

    # Main decode loop
    for step in 0 to max_length:
        active_beams = beams.filter(not finished)

        if active_beams.isEmpty():
            break  # All beams finished

        # CRITICAL OPTIMIZATION: Process all active beams in single batch
        new_candidates = processBatchedBeams(active_beams, memory, src_mask)

        # Separate finished from still-active candidates
        new_finished = new_candidates.filter(finished)
        still_active = new_candidates.filter(not finished)

        finished_beams.addAll(new_finished)

        # Keep top beam_width active beams by score
        beams = still_active.sortedByDescending(score).take(beam_width)

        if beams.isEmpty() and finished_beams.isNotEmpty():
            break  # All beams completed

    # Return best hypotheses (finished + remaining active)
    return (finished_beams + beams).sortedByDescending(score)
```

### Data Structures

#### BeamSearchState
```kotlin
data class BeamSearchState(
    val tokens: MutableList<Long>,  // Token sequence [SOS, t1, t2, ..., EOS]
    var score: Float,               // Cumulative log probability
    var finished: Boolean           // true if ended with EOS token
)
```

**Example during decoding:**
```
Step 0: tokens=[2], score=0.0, finished=false           // SOS
Step 1: tokens=[2,8], score=-0.5, finished=false        // SOS, 'h'
Step 2: tokens=[2,8,5], score=-1.2, finished=false      // SOS, 'h', 'e'
Step 3: tokens=[2,8,5,12], score=-1.8, finished=false   // SOS, 'h', 'e', 'l'
Step 4: tokens=[2,8,5,12,12], score=-2.3, finished=false // SOS, 'h', 'e', 'l', 'l'
Step 5: tokens=[2,8,5,12,12,15], score=-2.8, finished=false // 'h','e','l','l','o'
Step 6: tokens=[2,8,5,12,12,15,3], score=-3.0, finished=true  // EOS
```

### Batched Inference Optimization

**Problem:** Sequential beam processing is slow (8ms × 8 beams = 64ms)
**Solution:** Process all active beams in a single batched inference call

#### Memory Tensor Expansion
**Challenge:** Encoder output is `[1, 150, 256]`, but decoder needs `[batch_size, 150, 256]`

```kotlin
function expandMemoryTensor(memory, batch_size):
    # Replicate single encoder output across batch dimension
    memory_data = memory.value as [1, 150, 256]
    expanded = Array(batch_size) { Array(150) { FloatArray(256) } }

    for b in 0 until batch_size:
        for s in 0 until 150:
            System.arraycopy(memory_data[0][s], 0, expanded[b][s], 0, 256)

    return OnnxTensor.createTensor(ortEnvironment, expanded)
```

#### Batched Tensor Creation
```kotlin
function processBatchedBeams(active_beams, memory, src_mask):
    batch_size = active_beams.size
    seq_length = 20  # Standard decoder sequence length

    # Expand memory tensor to match batch
    expanded_memory = expandMemoryTensor(memory, batch_size)

    # Create batched token and mask tensors using tensor pool
    batched_tokens = createBatchedTokens(active_beams, batch_size, seq_length)
    batched_mask = createBatchedMask(active_beams, batch_size, seq_length)

    # Single batched inference call
    decoder_inputs = {
        "memory": expanded_memory,
        "target_tokens": batched_tokens,    # [batch_size, seq_length]
        "target_mask": batched_mask,        # [batch_size, seq_length]
        "src_mask": src_mask
    }

    result = decoderSession.run(decoder_inputs)
    logits = result.get(0)  # [batch_size, seq_length, vocab_size]

    # Process results for each beam
    return expandBeamsFromBatchedLogits(logits, active_beams)
```

#### Tensor Pooling
**Optimization:** Reuse tensor buffers to eliminate allocation overhead

```kotlin
tensorPool.useTensor(batchedTokensShape, "long") { batchedTokensTensor ->
    tensorPool.useTensor(batchedMaskShape, "boolean") { batchedMaskTensor ->
        // Populate tensors directly in pool buffers
        populateBatchedTensors(active_beams, batchedTokensTensor, batchedMaskTensor)

        // Run inference
        val output = decoderSession.run(inputs)

        // Automatic cleanup on scope exit
    }
}
```

**Performance Impact:**
- Pool hit rate: 85-95%
- Additional speedup: 10-20% on top of batching

### Beam Expansion

For each active beam at position `i`, generate `beam_width` new candidates:

```kotlin
function expandBeamsFromBatchedLogits(logits, active_beams):
    new_candidates = []

    for beam_index in 0 until active_beams.size:
        beam = active_beams[beam_index]
        current_pos = beam.tokens.size - 1

        vocab_logits = logits[beam_index][current_pos]  # [vocab_size]
        top_k_tokens = getTopKIndices(vocab_logits, beam_width)

        for token_id in top_k_tokens:
            new_beam = BeamSearchState(beam)  # Copy constructor
            new_beam.tokens.add(token_id)
            new_beam.score += vocab_logits[token_id]  # Log probability

            if token_id == EOS_IDX:
                new_beam.finished = true

            new_candidates.add(new_beam)

    return new_candidates
```

### Configuration Parameters

**Accessible via `NeuralConfig.kt`:**

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `beam_width` | 8 | 1-16 | Number of hypotheses to track per step |
| `max_length` | 35 | 10-50 | Maximum characters to decode |
| `confidence_threshold` | 0.1 | 0.0-1.0 | Minimum confidence for predictions |

**Impact on Performance:**

| beam_width | Inference Time | Accuracy | Memory |
|------------|----------------|----------|--------|
| 1 | ~40ms | 70% | 5 MB |
| 4 | ~60ms | 82% | 8 MB |
| 8 | ~90ms | 87% | 12 MB |
| 16 | ~150ms | 89% | 20 MB |

**Recommended:** `beam_width=8` for optimal accuracy/speed tradeoff

---

## 5. Post-Processing

### Token-to-Character Decoding

**Tokenizer:** Character-level mapping

```kotlin
class SwipeTokenizer {
    fun tokensToWord(tokens: List<Long>): String {
        return buildString {
            tokens.forEach { token ->
                when (token) {
                    in 4..29 -> append(('a'.code + (token - 4).toInt()).toChar())
                    0, 1, 2, 3 -> { /* Skip special tokens */ }
                }
            }
        }
    }
}
```

**Example:**
```
tokens: [2, 8, 5, 12, 12, 15, 3]
        ↓  ↓  ↓   ↓   ↓   ↓  ↓
      SOS  h  e   l   l   o EOS
output: "hello"
```

### Vocabulary Filtering

**Purpose:** Ensure predictions are valid English words
**Implementation:** `OptimizedVocabulary.kt`

```kotlin
class OptimizedVocabulary {
    fun filterPredictions(
        candidates: List<CandidateWord>,
        swipeStats: SwipeStats
    ): List<FilteredPrediction> {

        return candidates.mapNotNull { candidate ->
            // Check if word exists in dictionary
            val inVocab = isValidWord(candidate.word)

            // Apply length heuristics
            val lengthMatch = abs(candidate.word.length - estimatedLength) <= 2

            // Apply frequency weighting
            val frequency = getWordFrequency(candidate.word)
            val adjustedScore = candidate.confidence * frequency

            if (inVocab && lengthMatch) {
                FilteredPrediction(candidate.word, adjustedScore)
            } else null
        }
        .sortedByDescending { it.score }
        .take(10)  // Top 10 predictions
    }
}
```

**Dictionary Sources:**
- `assets/dictionaries/en.txt` - Base English words (~100K)
- `assets/dictionaries/en_enhanced.txt` - Extended vocabulary (~50K)

### Confidence Score Conversion

**Neural score:** Log probability (negative, e.g., -3.2)
**UI score:** Integer 0-1000 for ranking

```kotlin
fun createPredictionResult(candidates: List<BeamSearchCandidate>): PredictionResult {
    val words = candidates.map { it.word }
    val scores = candidates.map {
        (exp(it.score) * 1000).toInt()  // Convert to 0-1000 range
    }

    return PredictionResult(words, scores)
}
```

**Example:**
```
Beam score: -2.3  →  exp(-2.3) = 0.10 → 100
Beam score: -3.5  →  exp(-3.5) = 0.03 → 30
Beam score: -1.8  →  exp(-1.8) = 0.17 → 170
```

### Final Prediction Format

**Data Class:** `PredictionResult`

```kotlin
data class PredictionResult(
    val words: List<String>,
    val scores: List<Int>
) {
    val topPrediction: String? = words.firstOrNull()
    val size: Int = words.size
    val predictions: List<Pair<String, Int>> = words.zip(scores)
}
```

**Example UI Display:**
```
Suggestion Bar:
┌────────┬────────┬────────┐
│ hello  │ hallo  │ hells  │
│  950   │  850   │  720   │
└────────┴────────┴────────┘
```

---

## 6. Performance & Operational Constraints

### Latency Benchmarks

**Device:** Mid-range Android (Snapdragon 778G)
**Environment:** Production release build

| Stage | Time (ms) | % of Total |
|-------|-----------|------------|
| Feature Extraction | 5-8 | 8% |
| Encoder Inference | 15-25 | 25% |
| Beam Search (8 beams, batched) | 50-70 | 62% |
| Post-Processing | 3-5 | 5% |
| **Total Pipeline** | **80-110** | 100% |

**Target:** < 200ms for real-time user experience
**Achieved:** 80-110ms average (2-3× faster than Java baseline)

### Memory Usage

| Component | Memory |
|-----------|--------|
| Encoder Model (loaded) | 5.5 MB |
| Decoder Model (loaded) | 7.5 MB |
| Vocabulary Dictionary | 2.5 MB |
| Tensor Pool Buffers | 3-5 MB |
| Runtime Overhead | 5 MB |
| **Total Peak** | **25-30 MB** |

**Memory Optimization:**
- Tensor pooling reduces allocations by 50-70%
- Direct buffers for zero-copy tensor creation
- Reusable beam state arrays

### Throughput

**Sequential Processing:**
- Single prediction: ~90ms
- Throughput: ~11 predictions/second

**Batch Processing (future optimization):**
- 4 predictions batched: ~180ms
- Throughput: ~22 predictions/second

### Limitations

#### Maximum Swipe Length
**Hard Limit:** 150 trajectory points
**Reason:** Fixed encoder sequence length
**Workaround:** Resample long swipes to fit 150 points

#### Vocabulary Size
**Current:** ~150,000 English words
**Out-of-vocabulary:** Model can still predict, but vocabulary filtering rejects

#### Language Support
**Current:** English only
**Extension:** Requires retraining model with multilingual data

#### Special Characters
**Current:** Lowercase a-z only
**Missing:** Numbers, punctuation, uppercase
**Workaround:** Post-processing capitalization heuristics

### Error Handling

#### Model Loading Failures
```kotlin
suspend fun initialize(): Boolean {
    try {
        encoderSession = loadModel("swipe_model_character_quant.onnx")
        decoderSession = loadModel("swipe_decoder_character_quant.onnx")
        return true
    } catch (e: Exception) {
        logE("Failed to initialize ONNX predictor", e)
        return false
    }
}
```

#### Inference Failures
```kotlin
suspend fun predict(input: SwipeInput): PredictionResult {
    return try {
        // ... pipeline execution
    } catch (e: Exception) {
        logE("Neural prediction failed", e)
        PredictionResult.empty  // Graceful degradation
    }
}
```

#### Invalid Input Handling
```kotlin
val isHighQualitySwipe: Boolean
    get() = pathLength > 100 &&
            duration in 0.1f..3.0f &&
            directionChanges >= 2
```

---

## 7. Configuration & Tuning

### User-Accessible Settings

**Location:** Settings → Neural Prediction → Advanced

| Setting | UI Control | Default | Impact |
|---------|------------|---------|--------|
| Enable Neural Prediction | Toggle | ON | Enable/disable entire pipeline |
| Beam Width | Slider (1-16) | 8 | Accuracy vs speed tradeoff |
| Max Word Length | Slider (10-50) | 35 | Maximum characters decoded |
| Confidence Threshold | Slider (0.0-1.0) | 0.1 | Filter low-confidence predictions |

### Configuration Storage

**Implementation:** `NeuralConfig.kt` with property delegation

```kotlin
class NeuralConfig(private val prefs: SharedPreferences) {
    var beamWidth: Int by IntPreference("neural_beam_width", 8)
    var maxLength: Int by IntPreference("neural_max_length", 35)
    var confidenceThreshold: Float by FloatPreference("neural_confidence_threshold", 0.1f)
}
```

**Persistence:** Android SharedPreferences (XML file)
**Propagation:** Reactive updates via `setConfig()` calls

### Calibration Playground

**UI:** SwipeCalibrationActivity → "🎮 Playground" button

**Features:**
- Live adjustment of beam_width, max_length, threshold
- Immediate application to next prediction
- Performance metrics display
- Export training data for model improvements

---

## 8. Debugging & Diagnostics

### Debug Logging

**Enable:** `neuralEngine.setDebugLogger { message -> ... }`

**Key Log Points:**
```
🔄 Loading ONNX transformer models...
📥 Encoder model data loaded: 5452839 bytes
✅ Encoder session created successfully
🚀 Starting neural prediction for 87 points
🔍 Starting beam search decoder...
🚀 BATCHED INFERENCE: Using optimized batch processing
📊 Decoder output shape: [8, 20, 30]
🚀 TENSOR-POOLED INFERENCE: 28ms for 8 beams
🧠 Neural prediction completed: 8 candidates
```

### Performance Monitoring

**Metrics Collection:**
```kotlin
val (result, predTime) = measureTimeNanos {
    neuralEngine.predictAsync(swipeInput)
}
predictionTimes.add(predTime)
```

**Benchmark Display:**
```
📊 Neural Performance: 87.3% accuracy, 92.5ms avg prediction time
```

### Validation Tests

**Pipeline Validation:** `validateCompletePipeline()`

```kotlin
private suspend fun validateCompletePipeline() {
    // Create test input
    val testPoints = listOf(PointF(100f, 200f), ...)
    val testInput = SwipeInput(testPoints, timestamps, emptyList())

    // Test feature extraction
    val features = trajectoryProcessor.extractFeatures(...)

    // Test encoder inference
    val encoderResult = runEncoder(features)

    // Test one decoder step
    val candidates = processBatchedBeams(beams, memory, srcMask, session)

    logDebug("✅ Complete pipeline validation successful")
}
```

**Run:** Automatically on first initialization

---

## 9. Technical Decisions & Rationale

### Why Transformer Architecture?
- **Attention mechanisms** capture long-range dependencies in swipe paths
- **Encoder-decoder** naturally models sequence-to-sequence task
- **State-of-the-art** accuracy for text prediction

### Why Character-Level Tokenization?
- **Vocabulary-independent** - can predict any word
- **Smaller model** - 30 tokens vs 50K+ word embeddings
- **Handles typos** naturally

### Why Beam Search?
- **Better accuracy** than greedy decoding (87% vs 70%)
- **Configurable** tradeoff between speed and quality
- **Standard** for sequence generation tasks

### Why Batched Inference?
- **50-70% speedup** vs sequential processing
- **Same accuracy** as sequential
- **Memory efficient** with tensor pooling

### Why INT8 Quantization?
- **4× smaller** models (critical for mobile)
- **2-3× faster** inference
- **Minimal accuracy loss** (<2%)

### Why No Hardware Acceleration?
- **QNN/XNNPACK unavailable** in ONNX Runtime 1.20.0
- **CPU sufficient** for 80-110ms latency target
- **Future optimization** when newer ONNX Runtime available

---

## 10. Future Improvements

### Short-Term
- [ ] Implement dynamic beam width based on swipe quality
- [ ] Add personalized vocabulary (user history)
- [ ] Optimize tensor pooling for lower memory

### Medium-Term
- [ ] Support numbers and punctuation
- [ ] Multi-language model training
- [ ] On-device model fine-tuning

### Long-Term
- [ ] Transformer upgrade to modern architecture (GPT-style)
- [ ] Hardware acceleration (GPU/NPU)
- [ ] Real-time streaming inference (predict before swipe ends)

---

## 11. References

**Code Files:**
- `OnnxSwipePredictorImpl.kt` - Main implementation
- `SwipeInput.kt` - Input data structure
- `NeuralConfig.kt` - Configuration management
- `SwipeCalibrationActivity.kt` - Testing and calibration UI

**Model Files:**
- `assets/models/swipe_model_character_quant.onnx`
- `assets/models/swipe_decoder_character_quant.onnx`
- `assets/models/tokenizer.json`

**External Documentation:**
- ONNX Runtime: https://onnxruntime.ai/docs/
- Transformer Architecture: "Attention Is All You Need" (Vaswani et al., 2017)
- Beam Search: "Speech Recognition with Weighted Finite-State Transducers" (Mohri et al., 2008)

---

**Document Maintenance:**
- Update when adding new features to pipeline
- Benchmark numbers should be refreshed on major changes
- Configuration section must match actual NeuralConfig.kt

**Contact:** See project documentation for maintainers
