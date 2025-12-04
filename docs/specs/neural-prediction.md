# Neural Swipe Prediction System Specification

**Feature**: ONNX Transformer-Based Swipe-to-Text Prediction
**Status**: 🟢 IMPLEMENTED (P0 bugs resolved, P1-P2 remaining)
**Priority**: P0 (Core functionality)
**Assignee**: N/A
**Date Created**: 2025-10-20
**Last Updated**: 2025-12-04

---

## TODOs

### ✅ RESOLVED - Critical Systems (P0)

All critical systems are fully implemented and verified as of 2025-12-04:

| Bug # | Issue | Resolution | Status |
|-------|-------|------------|--------|
| #257 | LanguageDetector missing | Implemented in `data/LanguageDetector.kt` (313 lines) | ✅ FIXED |
| #259 | NgramModel missing | Implemented in `NgramModel.kt` (350 lines) | ✅ FIXED |
| #262 | WordPredictor missing | Implemented in `WordPredictor.kt` (782 lines) | ✅ FIXED |
| #263 | UserAdaptationManager missing | Implemented in `data/UserAdaptationManager.kt` (291 lines) | ✅ FIXED |
| #273 | Training data lost on close | SQLite database implementation | ✅ FIXED |
| #274 | ML training system | External pipeline by design (ADR-003) | ✅ ARCHITECTURAL |
| #275 | Async prediction | Kotlin coroutines (ADR-004) | ✅ ARCHITECTURAL |
| #276 | Advanced gesture analysis | Neural network auto-learns features (ADR-005) | ✅ ARCHITECTURAL |

**Initialization Order Bug** (2025-11-14): Fixed race condition in CleverKeysService.kt where WordPredictor was initialized before its dependencies.

### ⚠️ Outstanding Issues (P1-P2)

| Bug # | Issue | File | Impact | Est. Time |
|-------|-------|------|--------|-----------|
| #270 | Time delta calculation | SwipeMLData.kt | Training timestamps may be wrong | 1 hour |
| #271 | Consecutive duplicate filtering | SwipeMLData.kt | Noisy training data | 1 hour |
| #277 | Multi-language expansion | OptimizedVocabularyImpl.kt | Only English fully tested | 8-12 hours/language |

---

## 1. Feature Overview

### Purpose
Pure ONNX neural transformer architecture for converting swipe gestures into ranked word predictions. This is a **complete architectural replacement** of the original CGR (Continuous Gesture Recognition) system.

### Key Advantages Over Legacy CGR
- **Modern ML**: Transformer encoder-decoder vs. template matching
- **Automatic Learning**: Neural networks learn features from data
- **Better Accuracy**: Deep learning vs. statistical heuristics
- **Scalability**: Can improve with more training data
- **Simplicity**: 2000+ lines of Java CGR code replaced with ONNX inference

### Architecture Comparison

**Original (Java - CGR System)**:
```
Swipe → Manual Feature Engineering (40+ features) →
Template Matching → Dictionary Lookup →
Statistical Scoring → Predictions
```

**Modern (Kotlin - ONNX System)**:
```
Swipe → Feature Extraction (6 features) →
Transformer Encoder → Beam Search Decoder →
Vocabulary Filter → Predictions
```

### Current Status (Updated: 2025-11-14)
- **Core Pipeline**: ✅ COMPLETE (encoder + decoder + beam search)
- **Feature Extraction**: ✅ COMPLETE (smoothing, velocity, acceleration)
- **Tokenization**: ✅ COMPLETE (character-level)
- **WordPredictor System**: ✅ COMPLETE (dictionary, bigram, language detection, user adaptation)
- **Vocabulary**: ⚠️ PARTIAL (English only, framework ready for multi-language)
- **Training Data**: ✅ COMPLETE (SQLite persistence - Bug #273 FIXED)
- **Multi-Language**: ⚠️ FRAMEWORK READY (LanguageDetector implemented, assets needed)
- **User Adaptation**: ✅ COMPLETE (SharedPreferences-based learning)

---

## 2. Requirements

### Functional Requirements

**FR-1: Swipe Input Processing**
- ✅ Capture touch coordinates (x, y) and timestamps
- ✅ Smooth trajectory (moving average, window=3)
- ✅ Calculate velocity (first derivative)
- ✅ Calculate acceleration (second derivative)
- ✅ Normalize coordinates [0,1] (device-independent)
- ✅ Detect nearest keys (real positions or QWERTY grid)
- ✅ Pad/truncate to 150 points (fixed sequence length)

**FR-2: ONNX Encoder Inference**
- ✅ Input: trajectory_features [1, 150, 6], nearest_keys [1, 150]
- ✅ Model: swipe_model_character_quant.onnx (quantized)
- ✅ Output: memory tensor [1, 150, 256] (encoder representation)
- ✅ Transformer architecture with self-attention

**FR-3: Beam Search Decoder**
- ✅ Batched inference (50-70% speedup vs sequential)
- ✅ Beam width: 8 (configurable)
- ✅ Max length: 20 characters
- ✅ SOS/EOS token handling
- ✅ Score tracking (log probabilities)
- ✅ Early termination on EOS

**FR-4: Post-Processing**
- ✅ Token-to-character decoding
- ✅ Vocabulary filtering (dictionary lookup)
- ✅ Confidence score conversion (0-1000 scale)
- ✅ Ranking by score (descending)

**FR-5: Training Data Collection** (COMPLETE)
- ✅ Persistent storage via SQLite (Bug #273 - FIXED)
- ⚠️ Time delta calculation needs verification (Bug #270)
- ⚠️ Consecutive duplicates filtering needs verification (Bug #271)

**FR-6: Multi-Language Support** (FRAMEWORK READY)
- ✅ Language detection implemented (Bug #257 - FIXED)
- ⚠️ Per-language models need assets (Bug #277)
- ⚠️ User dictionaries framework ready, assets needed

**FR-7: User Adaptation** (COMPLETE)
- ✅ Personalization manager implemented (Bug #263 - FIXED)
- ✅ Frequency tracking via SharedPreferences
- ✅ User-specific corrections supported

### Non-Functional Requirements

**NFR-1: Performance**
- ✅ Encoder inference: < 30ms (achieved with quantization)
- ✅ Decoder inference: < 50ms (batched beam search)
- ✅ Total latency: < 100ms (end-to-end)
- ✅ Memory pooling (OptimizedTensorPool)
- ✅ GPU batching (BatchedMemoryOptimizer)

**NFR-2: Accuracy**
- ⚠️ Top-1 accuracy: 65-75% (can improve with more training data)
- ⚠️ Top-3 accuracy: 85-90% (needs vocabulary improvement)
- ⚠️ Multi-language: Framework ready, needs asset files

**NFR-3: Resource Usage**
- ✅ Model size: ~8MB (quantized from ~30MB)
- ✅ Memory pooling prevents leaks
- ✅ Training data persisted to SQLite (Bug #273 FIXED)

---

## 3. Technical Design

### System Architecture

```
┌───────────────────────────────────────────────────────────┐
│                    USER INTERACTION                        │
└───────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────┐
│              SwipeDetector.kt (Touch Events)               │
│  - ACTION_DOWN: Start gesture                             │
│  - ACTION_MOVE: Collect points                            │
│  - ACTION_UP: Trigger prediction                          │
└───────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────┐
│         SwipeInput.kt (Data Encapsulation)                 │
│  data class SwipeInput(                                    │
│    coordinates: List<PointF>,                              │
│    timestamps: List<Long>,                                 │
│    touchedKeys: List<Key>                                  │
│  )                                                         │
└───────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────┐
│      OnnxSwipePredictorImpl.kt (Core Pipeline)             │
│  ┌─────────────────────────────────────────────────┐      │
│  │ SwipeTrajectoryProcessor (Feature Extraction)   │      │
│  │  - smoothTrajectory()                           │      │
│  │  - calculateVelocities()                        │      │
│  │  - calculateAccelerations()                     │      │
│  │  - normalizeCoordinates()                       │      │
│  │  - detectNearestKeys()                          │      │
│  │  - padOrTruncate(150 points)                    │      │
│  └─────────────────────────────────────────────────┘      │
│                         │                                  │
│                         ▼                                  │
│  ┌─────────────────────────────────────────────────┐      │
│  │ runEncoder() (Transformer Inference)            │      │
│  │  - Create input tensors [1,150,6], [1,150]     │      │
│  │  - Run ONNX encoder model                       │      │
│  │  - Output: memory [1,150,256]                   │      │
│  └─────────────────────────────────────────────────┘      │
│                         │                                  │
│                         ▼                                  │
│  ┌─────────────────────────────────────────────────┐      │
│  │ runBeamSearch() (Character Decoding)            │      │
│  │  - Initialize beams with SOS token              │      │
│  │  - BATCHED decoder inference (beam_width=8)     │      │
│  │  - Expand hypotheses, track scores              │      │
│  │  - Terminate on EOS or max_length               │      │
│  │  - Return top N candidates                      │      │
│  └─────────────────────────────────────────────────┘      │
│                         │                                  │
│                         ▼                                  │
│  ┌─────────────────────────────────────────────────┐      │
│  │ SwipeTokenizer (Token ↔ Character)              │      │
│  │  - decode([2,5,8,12,3]) → "hello"              │      │
│  │  - Special tokens: SOS=2, EOS=3, PAD=0, UNK=1   │      │
│  └─────────────────────────────────────────────────┘      │
└───────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────┐
│    OptimizedVocabularyImpl.kt (Dictionary Filter)         │
│  - Check words against dictionary (English only)          │
│  - Filter OOV (out-of-vocabulary) predictions             │
│  - Bug #277: Multi-language support missing               │
└───────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────┐
│         PredictionResult.kt (Output Format)                │
│  data class PredictionResult(                              │
│    words: List<String>,        // ["hello", "hallo"]      │
│    scores: List<Int>,          // [950, 850]              │
│    confidences: List<Float>    // [0.95, 0.85]            │
│  )                                                         │
└───────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────┐
│            SuggestionBar.kt (UI Display)                   │
│  - Show top 3 predictions                                 │
│  - Highlight by confidence color                          │
│  - Handle user selection                                  │
└───────────────────────────────────────────────────────────┘
```

### Critical Data Flows

**1. Feature Extraction Pipeline**:
```kotlin
// Input: SwipeInput
val rawCoords = swipeInput.coordinates          // [(100,250), (102,251), ...]
val timestamps = swipeInput.timestamps          // [1728567890123, 1728567890140, ...]

// Step 1: Smoothing (moving average, window=3)
val smoothed = smoothTrajectory(rawCoords)      // Reduce noise

// Step 2: Velocity (first derivative)
val velocities = calculateVelocities(smoothed, timestamps)
// Formula: velocity = distance / time_delta (pixels/sec)

// Step 3: Acceleration (second derivative)
val accelerations = calculateAccelerations(velocities, timestamps)
// Formula: accel = velocity_delta / time_delta (pixels/sec²)

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

**2. Beam Search Algorithm**:
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
        val topK = logits[beamIdx].topK(beam_width)  // Get top K tokens

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

    // Early termination if all beams finished
    if (beams.isEmpty()) break
}

// Return best finished beams
return finishedBeams.sortedByDescending { it.score }
```

**3. Token-to-Character Decoding**:
```kotlin
// Token indices → Characters
val CHAR_MAP = mapOf(
    4 to 'a', 5 to 'b', 6 to 'c', ..., 29 to 'z',
    30 to ' ', 31 to '\'', 32 to '-'
)

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
- **Type**: Transformer encoder
- **Input 1**: `trajectory_features` [batch, 150, 6]
  - Features: (x, y, velocity_x, velocity_y, accel_x, accel_y)
- **Input 2**: `nearest_keys` [batch, 150]
  - Character indices detected under each point
- **Input 3**: `src_mask` [batch, 150]
  - Attention mask (1=real point, 0=padding)
- **Output**: `memory` [batch, 150, 256]
  - Encoded representation of swipe trajectory
- **Size**: ~4MB (quantized INT8)
- **Layers**: 6 transformer encoder layers
- **Attention**: Multi-head self-attention (8 heads)

**Decoder Model** (`swipe_decoder_character_quant.onnx`):
- **Type**: Transformer decoder (character-level)
- **Input 1**: `memory` [batch, 150, 256] (from encoder)
- **Input 2**: `tgt_input_ids` [batch, seq_len] (partial sequence)
- **Output**: `logits` [batch, seq_len, vocab_size=35]
  - Next character probabilities
- **Size**: ~4MB (quantized INT8)
- **Vocabulary**: 35 tokens (SOS, EOS, PAD, UNK, a-z, space, ', -)
- **Max Length**: 20 characters

---

## 4. Implementation Plan

### Phase 1: Critical Bug Fixes (2-3 days)
**Priority: P0 - Fix data loss and training bugs**

1. **Bug #273: Persistent Training Data**
   - Create SQLite database schema
   - Migrate SwipeMLDataStore to use Room/SQLite
   - Implement batch insert for performance
   - Add data export/import functionality
   - Time: 4-6 hours

2. **Bug #270: Time Delta Calculation**
   - Fix addRawPoint() timestamp logic
   - Use proper millisecond differences
   - Time: 1 hour

3. **Bug #271: Consecutive Duplicate Filtering**
   - Add logic to skip duplicate keys in sequence
   - Preserve only direction changes
   - Time: 1 hour

### Phase 2: Multi-Language Support (1-2 weeks)
**Priority: P1 - Enable multiple languages**

1. **Bug #277: Multi-Language Infrastructure**
   - Add language detection (Bug #257 - 313 lines to port)
   - Per-language ONNX models
   - Per-language vocabularies
   - User dictionary support
   - Language switcher UI
   - Time: 8-12 hours implementation + model training

### Phase 3: User Adaptation (2-3 weeks)
**Priority: P1 - Personalization**

1. **Bug #263: UserAdaptationManager**
   - Port UserAdaptationManager.java (291 lines)
   - Frequency tracking
   - User-specific corrections
   - Personalized scoring adjustments
   - Time: 12-16 hours

### Phase 4: Training Infrastructure (4-6 weeks - External)
**Priority: P0 - Enable model improvements**

1. **Bug #274: ML Training System (External)**
   - Python/PyTorch training pipeline
   - Data preprocessing scripts
   - Model architecture definition
   - Training loop with validation
   - ONNX export scripts
   - Time: 2-3 weeks (full infrastructure)
   - Note: This is INTENTIONAL external training (ADR-003)

---

## 5. Testing Strategy

### Unit Tests

**Feature Extraction Tests**:
```kotlin
@Test
fun `smoothTrajectory reduces noise`() {
    val noisy = listOf(
        PointF(100f, 100f),
        PointF(105f, 102f),  // Noise spike
        PointF(102f, 101f)
    )
    val smoothed = smoothTrajectory(noisy)
    assertTrue(smoothed[1].x < noisy[1].x)  // Spike reduced
}

@Test
fun `velocity calculation is correct`() {
    val coords = listOf(PointF(0f, 0f), PointF(100f, 0f))
    val timestamps = listOf(0L, 1000L)  // 1 second apart
    val velocities = calculateVelocities(coords, timestamps)
    assertEquals(100f, velocities[0], 0.1f)  // 100 pixels/sec
}

@Test
fun `padding extends to 150 points`() {
    val coords = List(50) { PointF(it.toFloat(), 0f) }
    val (features, _, mask) = padOrTruncate(coords, ..., 150)
    assertEquals(150, features.size)
    assertEquals(50, mask.count { it == 1 })  // 50 real, 100 padded
}
```

**Beam Search Tests**:
```kotlin
@Test
fun `beam search returns top N candidates`() {
    val memory = createMockMemory()
    val beams = runBeamSearch(memory, beamWidth=8, maxLength=10)
    assertTrue(beams.size <= 8)
    assertTrue(beams[0].score >= beams[1].score)  // Sorted by score
}

@Test
fun `beam search terminates on EOS`() {
    val memory = createMockMemory()
    val beams = runBeamSearch(memory, beamWidth=1, maxLength=20)
    assertTrue(beams[0].tokens.last() == EOS || beams[0].tokens.size == 20)
}
```

**Tokenization Tests**:
```kotlin
@Test
fun `decode converts tokens to string`() {
    val tokens = listOf(SOS, 8, 5, 12, 12, 15, EOS)  // "hello"
    val decoded = SwipeTokenizer.decode(tokens)
    assertEquals("hello", decoded)
}

@Test
fun `decode filters special tokens`() {
    val tokens = listOf(SOS, PAD, 8, UNK, EOS)
    val decoded = SwipeTokenizer.decode(tokens)
    assertEquals("h", decoded)  // Only 'h' remains
}
```

### Integration Tests

**End-to-End Pipeline**:
```kotlin
@Test
fun `full pipeline produces predictions`() {
    val swipeInput = SwipeInput(
        coordinates = createMockSwipe(),  // Simulate "hello" swipe
        timestamps = createMockTimestamps(),
        touchedKeys = emptyList()
    )

    val predictions = onnxPredictor.predict(swipeInput)

    assertTrue(predictions.words.isNotEmpty())
    assertTrue(predictions.words[0] in listOf("hello", "hallo", "hell"))
    assertTrue(predictions.scores[0] > predictions.scores[1])
}
```

### Manual Testing Checklist

- [ ] Swipe "hello" → predicts "hello" in top 3
- [ ] Swipe with noise → smoothing reduces jitter
- [ ] Very short swipe (< 10 points) → pads correctly
- [ ] Very long swipe (> 200 points) → truncates to 150
- [ ] Fast swipe → velocity/acceleration calculated
- [ ] Slow swipe → low velocity values
- [ ] Multi-language model (if implemented) → correct language detected
- [ ] User dictionary (if implemented) → custom words appear

---

## 6. Success Criteria

### Functional Success
- ✅ Encoder inference < 30ms
- ✅ Decoder inference < 50ms (batched)
- ✅ Total latency < 100ms
- ✅ Training data persists across sessions (Bug #273 FIXED - SQLite)
- ⚠️ Multi-language support (Bug #277 - framework ready, assets needed)
- ✅ User adaptation (Bug #263 FIXED)

### Technical Success
- ✅ ONNX models load and run
- ✅ Beam search produces ranked candidates
- ✅ Vocabulary filtering works
- ✅ Memory pooling prevents leaks
- ⚠️ Top-3 accuracy ≥ 85% (can improve with more training data)

### User Experience Success
- ✅ Predictions appear quickly (< 100ms)
- ⚠️ Top prediction usually correct (65-75% currently)
- ✅ Learns from user corrections (UserAdaptationManager implemented)
- ⚠️ Multi-language switching (framework ready, needs assets)

---

## 7. References

### Documentation
- **Technical Reference**: `docs/ONNX_DECODE_PIPELINE.md` (28,319 bytes)
- **Architectural Decisions**: `docs/specs/architectural-decisions.md` (ADR-001 to ADR-006)
- **Review Status**: `docs/COMPLETE_REVIEW_STATUS.md` (Files 41-50, 57-100)

### Source Files
- **Core Pipeline**: `OnnxSwipePredictorImpl.kt` (~1500 lines)
- **Feature Extraction**: SwipeTrajectoryProcessor (embedded)
- **Beam Search**: runBeamSearch() method
- **Tokenization**: SwipeTokenizer (embedded)
- **Vocabulary**: `OptimizedVocabularyImpl.kt`
- **Training Data**: `SwipeMLData.kt`, `SwipeMLDataStore.kt`

### Models
- **Encoder**: `assets/models/swipe_model_character_quant.onnx` (~4MB)
- **Decoder**: `assets/models/swipe_decoder_character_quant.onnx` (~4MB)
- **Vocabulary**: `assets/models/vocabulary.txt` (English words)

### Bug Reports

| Bug # | Description | Status |
|-------|-------------|--------|
| #257 | LanguageDetector missing | ✅ FIXED |
| #259 | NgramModel missing | ✅ FIXED |
| #262 | WordPredictor integration | ✅ ARCHITECTURAL (works alongside ONNX) |
| #263 | UserAdaptationManager missing | ✅ FIXED |
| #270 | Time delta calculation | ⚠️ Needs verification |
| #271 | Consecutive duplicates filter | ⚠️ Needs verification |
| #273 | Training data persistence | ✅ FIXED (SQLite) |
| #274 | ML training external | ✅ ARCHITECTURAL (by design) |
| #275 | Async prediction | ✅ ARCHITECTURAL (coroutines) |
| #276 | Trace analyzer | ✅ ARCHITECTURAL (neural features) |
| #277 | Multi-language expansion | ⚠️ Framework ready, needs assets |

---

## 8. Notes

### Why ONNX Over CGR
- **ADR-001**: Pure ONNX neural prediction (intentional replacement)
- **ADR-002**: Template generation → neural training
- **ADR-003**: External ML training (Python/PyTorch)
- **ADR-004**: Coroutines over HandlerThread
- **ADR-005**: Neural feature learning (40+ features → 6 features)

### Implementation Complexity
- **Core Pipeline**: ✅ COMPLETE (high complexity, well-implemented)
- **Data Persistence**: ✅ COMPLETE (SQLite implementation)
- **Multi-Language**: ⚠️ FRAMEWORK READY (needs asset files for each language)
- **User Adaptation**: ✅ COMPLETE (SharedPreferences-based learning)

### Future Enhancements
1. On-device fine-tuning (federated learning)
2. Multilingual single model (instead of per-language)
3. Subword tokenization (BPE) for better vocabulary coverage
4. Contextual predictions (previous words)
5. Emoji/symbol prediction
6. Voice-to-swipe hybrid input

---

**Last Updated**: 2025-12-04
**Status**: ✅ Core complete, all P0 bugs resolved
**Priority**: P1-P2 remaining (time delta calculation, duplicate filtering, multi-language expansion)
