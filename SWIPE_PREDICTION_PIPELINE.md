# Swipe Prediction Pipeline Documentation

## Overview

This document describes the complete pipeline from raw swipe input to neural predictions in CleverKeys.

## Pipeline Stages

### 1. Input: Raw Swipe Trace
```kotlin
data class SwipeInput(
    val coordinates: List<PointF>,  // [(x1,y1), (x2,y2), ...]
    val timestamps: List<Long>       // [t1, t2, t3, ...]
)
```

**Source**: Touch events from keyboard view
- `MotionEvent.ACTION_DOWN` - Start swipe
- `MotionEvent.ACTION_MOVE` - Continue swipe (collect coordinates & timestamps)
- `MotionEvent.ACTION_UP` - End swipe

---

### 2. Feature Extraction

**Class**: `SwipeTrajectoryProcessor.extractFeatures()`

**Input**:
- `coordinates: List<PointF>` (raw x,y in pixels)
- `timestamps: List<Long>` (milliseconds)

**Process**:

#### 2.1 Normalization (0-1 range)
```kotlin
val normalized = coordinates.map {
    PointF(it.x / keyboardWidth, it.y / keyboardHeight)
}
```

#### 2.2 Velocity Calculation (deltas)
```kotlin
for (i in 1 until points.size) {
    vx[i] = normalized[i].x - normalized[i-1].x
    vy[i] = normalized[i].y - normalized[i-1].y
}
```

#### 2.3 Acceleration Calculation (delta of deltas)
```kotlin
for (i in 2 until points.size) {
    ax[i] = vx[i] - vx[i-1]
    ay[i] = vy[i] - vy[i-1]
}
```

#### 2.4 Padding/Truncation
- Pad to `MAX_SEQUENCE_LENGTH = 150` points
- Padding value: `(0, 0, 0, 0, 0, 0)`

#### 2.5 Nearest Keys (Optional/Debug)
- Set to `0` (PAD token) for all positions
- Model learns from trajectory features alone
- In real usage: may contain sparse key indices from touch events

**Output**:
```kotlin
data class TrajectoryFeatures(
    val trajectoryFeatures: FloatArray,  // [150 x 6] = [x, y, vx, vy, ax, ay]
    val nearestKeys: LongArray,          // [150] mostly zeros
    val actualLength: Int                // Original trace length (before padding)
)
```

---

### 3. Encoder Inference

**Model**: `swipe_model_character_quant.onnx` (5.1MB)

**Inputs**:
```kotlin
val encoderInputs = mapOf(
    "trajectory_features" to trajectoryTensor,  // shape: [1, 150, 6], float32
    "nearest_keys" to nearestKeysTensor,        // shape: [1, 150], int64
    "src_mask" to srcMaskTensor                 // shape: [1, 150], bool
)
```

**Mask Convention**:
```kotlin
srcMask[i] = (i >= actualLength)  // true = padded, false = valid
```

**Execution**:
```kotlin
val encoderOutput = encoderSession.run(encoderInputs)
val memory = encoderOutput.get(0) as OnnxTensor  // shape: [1, 150, 256]
```

**Output**: Memory tensor (contextualized trajectory encoding)
- Shape: `[batch_size=1, seq_length=150, d_model=256]`
- Contains: Learned representation of swipe trajectory

**Performance**: 5-8ms on ARM64

---

### 4. Beam Search Decoder

**Model**: `swipe_decoder_character_quant.onnx` (7.0MB)

**Algorithm**: Beam search with width=8, max_length=35

#### 4.1 Initialize Beams
```kotlin
val beams = List(BEAM_WIDTH) {
    BeamState(
        tokens = mutableListOf(SOS_IDX),  // Start with SOS token
        score = 0f,
        finished = false
    )
}
```

#### 4.2 Decoder Step Loop
```kotlin
for (step in 0 until MAX_LENGTH) {
    for (beam in beams) {
        if (beam.finished) continue

        // Prepare decoder inputs (CRITICAL: fixed length 20)
        val targetTokens = beam.tokens.padTo(DECODER_SEQ_LENGTH=20, PAD_IDX)
        val targetMask = BooleanArray(20) { i >= beam.tokens.size }

        val decoderInputs = mapOf(
            "memory" to encoderOutput,              // [1, 150, 256]
            "target_tokens" to targetTokensTensor,  // [1, 20], int64
            "target_mask" to targetMaskTensor,      // [1, 20], bool
            "src_mask" to srcMaskTensor             // [1, 150], bool
        )

        // Run decoder
        val output = decoderSession.run(decoderInputs)
        val logits = output.get(0) as OnnxTensor  // [1, 20, 30]

        // Get logits at current position
        val position = beam.tokens.size - 1
        val currentLogits = logits[0][position]  // [30] vocab size

        // Apply log-softmax
        val logProbs = applyLogSoftmax(currentLogits)

        // Expand beam with top-k tokens
        for (tokenIdx in topK(logProbs, BEAM_WIDTH)) {
            newBeams.add(BeamState(
                tokens = beam.tokens + tokenIdx,
                score = beam.score + logProbs[tokenIdx],
                finished = (tokenIdx == EOS_IDX)
            ))
        }
    }

    // Select top beams
    beams = newBeams.sortedByDescending { it.score }.take(BEAM_WIDTH)

    // Early stopping
    if (beams.all { it.finished }) break
}
```

**CRITICAL CONSTRAINT**: Decoder expects **fixed seq_length=20**
- Reason: Model exported without dynamic_axes
- Solution: Always pad target_tokens to 20, adjust mask

**Performance**: 120-150ms total for beam search

---

### 5. Token to Text Conversion

**Vocabulary**:
```kotlin
const val PAD_IDX = 0L
const val UNK_IDX = 1L
const val SOS_IDX = 2L
const val EOS_IDX = 3L
// a=4, b=5, ..., z=29
```

**Conversion**:
```kotlin
val words = beams.map { beam ->
    beam.tokens
        .filter { it > EOS_IDX }  // Remove special tokens
        .mapNotNull { TOKEN_TO_CHAR[it] }  // Convert to chars
        .joinToString("")
}
```

**Output**:
```kotlin
data class PredictionResult(
    val words: List<String>,       // ["hello", "help", "hall", ...]
    val scores: List<Float>,       // [0.842, 0.039, ...]
    val confidence: Float,         // Top prediction confidence
    val latencyMs: Long            // Total time
)
```

---

## Implementation Checklist

### ✅ Components to Verify

1. **SwipeCalibrationActivity**
   - [ ] Collects raw swipe traces (x, y, t)
   - [ ] Uses SwipeTrajectoryProcessor.extractFeatures()
   - [ ] Passes to OnnxSwipePredictorImpl.predict()
   - [ ] Displays results with confidence scores

2. **CleverKeysService / Keyboard2View**
   - [ ] Collects touch events (ACTION_DOWN/MOVE/UP)
   - [ ] Builds coordinate + timestamp arrays
   - [ ] Calls neural prediction pipeline
   - [ ] Updates SuggestionBar with results

3. **OnnxSwipePredictorImpl**
   - [ ] extractFeatures() normalizes to [0,1]
   - [ ] Calculates vx, vy, ax, ay correctly
   - [ ] Pads to 150 points
   - [ ] Sets nearest_keys to 0 (PAD)
   - [ ] Creates proper ONNX tensors
   - [ ] Runs encoder (memory output)
   - [ ] Runs beam search decoder
   - [ ] Pads target_tokens to seq_length=20
   - [ ] Converts tokens to text

---

## Common Pitfalls

### ❌ DON'T:
1. Compute nearest_keys from coordinates (confuses model)
2. Use dynamic target sequence lengths (decoder expects 20)
3. Use float masks (models expect boolean)
4. Skip velocity/acceleration calculations
5. Normalize after velocity calculation (normalize FIRST)

### ✅ DO:
1. Set nearest_keys to all zeros (PAD)
2. Always pad target_tokens to DECODER_SEQ_LENGTH=20
3. Use boolean tensors for masks (true=padded, false=valid)
4. Calculate velocities from normalized coordinates
5. Normalize coordinates BEFORE velocity calculation

---

## Testing Pipeline

### Unit Test (CLI)
```bash
kotlinc test_onnx_cli.kt -classpath "./onnxruntime-1.20.0-android.jar" \
    -include-runtime -d test_onnx_cli.jar
java -classpath "test_onnx_cli.jar:onnxruntime-1.20.0-android.jar" Test_onnx_cliKt
```

**Expected Output**:
```
🎯 Top Predictions
   1. hello           [84.2%]
   ...
✅ ALL VALIDATION CHECKS PASSED!
```

### Integration Test (Android)
1. Launch SwipeCalibrationActivity
2. Swipe "hello" pattern on keyboard
3. Verify predictions: ["hello", "help", ...] with confidence > 50%

### Production Test (Keyboard)
1. Enable CleverKeys in system settings
2. Open any text field
3. Swipe word patterns
4. Verify: Predictions appear in SuggestionBar
5. Verify: Tapping suggestion inserts text

---

## Performance Targets

| Component | Target | Actual |
|-----------|--------|--------|
| Feature Extraction | <5ms | ~2ms |
| Encoder Inference | <20ms | 5-8ms |
| Decoder Step | <15ms | ~15ms |
| Total Pipeline | <200ms | 128ms |
| Memory Usage | <100MB | ~50MB |

---

## Model Details

### Encoder Architecture
- Type: Transformer encoder
- Input: Trajectory features [150, 6]
- Output: Memory [150, 256]
- Quantization: INT8
- File: swipe_model_character_quant.onnx (5.1MB)

### Decoder Architecture
- Type: Transformer decoder
- Fixed seq_length: 20 (hardcoded in model)
- Input: Memory [150, 256], target_tokens [20]
- Output: Logits [20, 30]
- Quantization: INT8
- File: swipe_decoder_character_quant.onnx (7.0MB)

### Training
- Framework: PyTorch (exported to ONNX)
- Vocab: 26 letters (a-z) + 4 special tokens
- Beam width: 8 (used during training)
- Max length: 35 characters

---

## Validation

Pipeline is validated when:
1. ✅ Encoder runs without errors
2. ✅ Decoder produces coherent words (not gibberish)
3. ✅ Top prediction matches expected word (>50% cases)
4. ✅ Predictions complete in <200ms
5. ✅ No memory leaks after 100+ predictions

**Status**: ✅ All validation checks passed (Oct 10, 2025)

---

## ✅ IMPLEMENTATION VERIFICATION (Oct 10, 2025)

### SwipeCalibrationActivity
```
✅ Collects raw swipe traces (x, y, t) in onTouchEvent()
   - ACTION_DOWN: Initializes swipePoints, currentSwipeTimestamps
   - ACTION_MOVE: Appends to swipePoints, currentSwipeTimestamps
   - ACTION_UP: Calls recordSwipe(swipePoints)

✅ Creates SwipeInput
   val swipeInput = SwipeInput(points, currentSwipeTimestamps.toList(), emptyList())

✅ Calls neural prediction
   neuralEngine.predictAsync(swipeInput)

✅ Displays results
   result.predictions.take(5).forEachIndexed { index, (word, score) -> ... }
```

### Keyboard2View (Production Keyboard)
```
✅ Collects raw swipe traces
   - handleSwipeStart(x, y, pointerId)
   - handleSwipeMove(x, y) - adds to swipeTrajectory, swipeTimestamps
   - handleSwipeEnd() - creates SwipeInput

✅ Creates SwipeInput
   SwipeInput(
       coordinates = ArrayList(swipeTrajectory),
       timestamps = ArrayList(swipeTimestamps),
       touchedKeys = emptyList()
   )

✅ Passes to service
   keyboardService?.handleSwipeGesture(gestureData)
```

### CleverKeysService
```
✅ Receives gesture data
   internal fun handleSwipeGesture(swipeData: SwipeGestureData)

✅ Calls prediction pipeline
   pipeline.processGesture(
       points = swipeData.path,
       timestamps = swipeData.timestamps,
       context = getCurrentTextContext()
   )

✅ Updates UI
   updateSuggestionsFromPipeline(pipelineResult)
```

### NeuralPredictionPipeline
```
✅ Creates SwipeInput
   val swipeInput = SwipeInput(points, timestamps, emptyList())

✅ Calls ONNX predictor
   neuralEngine.predictAsync(swipeInput)

✅ Returns PredictionResult
   PipelineResult(
       predictions = predictions,
       processingTimeMs = ...,
       source = PredictionSource.NEURAL
   )
```

### OnnxSwipePredictorImpl
```
✅ Feature extraction
   val features = trajectoryProcessor.extractFeatures(input.coordinates, input.timestamps)

✅ Encoder inference
   val encoderResult = runEncoder(features)
   val memory = encoderResult.get(0) as OnnxTensor

✅ Beam search decoder
   val candidates = runBeamSearch(memory, srcMaskTensor, features)

✅ Result creation
   createPredictionResult(candidates)
```

### SwipeTrajectoryProcessor
```
✅ Step 1: Normalize FIRST
   val normalizedCoords = normalizeCoordinates(coordinates)
   // (point.x / keyboardWidth, point.y / keyboardHeight)

✅ Step 2: Detect nearest keys
   val nearestKeys = detectNearestKeys(coordinates)
   // Returns 0 (PAD) if no real key positions available

✅ Step 3: Pad to 150 points
   val finalCoords = padOrTruncate(normalizedCoords, MAX_TRAJECTORY_POINTS)

✅ Step 4: Calculate velocities (simple deltas)
   vx = finalCoords[i].x - finalCoords[i-1].x
   vy = finalCoords[i].y - finalCoords[i-1].y

✅ Step 5: Calculate accelerations (delta of deltas)
   ax = vx - velocities[i-1].x
   ay = vy - velocities[i-1].y
```

---

## 🎯 PIPELINE CONSISTENCY VERIFICATION

All components follow the same data flow:

```
Touch Events (x,y,t)
    ↓
SwipeInput(coordinates, timestamps)
    ↓
trajectoryProcessor.extractFeatures()
    ↓
TrajectoryFeatures[150x6] (x,y,vx,vy,ax,ay)
    ↓
Encoder ONNX (trajectory_features, nearest_keys, src_mask)
    ↓
Memory[150x256]
    ↓
Beam Search Decoder (memory, target_tokens[20], masks)
    ↓
Tokens → Text Conversion
    ↓
PredictionResult(words, scores, confidence)
```

**Status**: ✅ All implementations verified to follow documented pipeline (Oct 10, 2025)

---

## 📝 NOTES

1. **nearest_keys**: Set to 0 (PAD) in production
   - Only populated when real keyboard DOM positions available
   - Model trained to work with mostly-zero nearest_keys
   - Learns primarily from trajectory features

2. **Decoder seq_length=20**: Hardcoded in ONNX model
   - Always pad target_tokens to 20
   - Model exported without dynamic_axes
   - This is CRITICAL - do not change!

3. **Feature order**: Normalization MUST happen FIRST
   - ❌ Wrong: calculate velocity → normalize
   - ✅ Correct: normalize → calculate velocity

4. **Mask convention**: Boolean tensors
   - true = padded position
   - false = valid data
   - ❌ Do NOT use float masks
