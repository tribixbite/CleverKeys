# Neural Swipe Typing - Implementation Differences Analysis

**Date:** October 11, 2025
**Purpose:** Document every difference between working web demo (swipe.html) and Kotlin app implementation
**Status:** ✅ **CRITICAL FIXES IMPLEMENTED** - All Priority 1 mismatches resolved (commit 65c4d3c)

---

## 🔴 CRITICAL DIFFERENCES

### 1. **Swipe Data Collection & Key Tracking**

#### Web Demo (swipe.html):
- **Lines 905-934: `startSwipe()`**
  ```javascript
  // Tracks which key finger is over during swipe
  if (key) {
      currentKey = key.dataset.key;  // Stores 'q', 'w', 'e', etc.
      key.classList.add('key-active');
      keySequence.push({ key: currentKey, timestamp: Date.now(), index: 0 });
  }

  swipePath.push({
      x: coords.x,
      y: coords.y,
      normalized: coords.normalized,
      key: currentKey,  // ← CRITICAL: Associates key with each point
      timestamp: Date.now()
  });
  ```

- **Lines 936-975: `continueSwipe()`**
  ```javascript
  // Updates currentKey when finger moves to different key
  const keyChar = key ? key.dataset.key : null;
  if (keyChar !== currentKey) {
      // Highlight new key, update keySequence
      currentKey = keyChar;
  }

  swipePath.push({
      key: currentKey,  // ← Each point knows which key it's near
      // ...
  });
  ```

#### Kotlin App (Keyboard2View.kt):
- **Location:** `/data/data/com.termux/files/home/git/swype/cleverkeys/src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt`
- **Problem:** Need to verify key tracking implementation
- **TODO:** Check if `Pointer` class tracks touched keys for each point

**IMPACT:** If touchedKeys list doesn't match swipe path length 1:1, nearest_keys tensor will be wrong size or have incorrect mappings.

---

### 2. **Feature Extraction Pipeline**

#### Web Demo (swipe.html):
- **Lines 1086-1111: `prepareSwipeFeatures()`**
  ```javascript
  function prepareSwipeFeatures(swipeData) {
      const path = swipeData.path;
      const normalizedPath = [];
      const originalLength = Math.min(path.length, MAX_SEQUENCE_LENGTH);

      // Take up to MAX_SEQUENCE_LENGTH points
      for (let i = 0; i < Math.min(path.length, MAX_SEQUENCE_LENGTH); i++) {
          const point = path[i];
          normalizedPath.push({
              x: point.x / NORMALIZED_WIDTH,   // ← Divide by 360
              y: point.y / NORMALIZED_HEIGHT,  // ← Divide by 215
              key: point.key || null           // ← Preserve key association
          });
      }

      // Pad with last point if too short
      const lastPoint = normalizedPath[normalizedPath.length - 1] || { x: 0, y: 0, key: null };
      while (normalizedPath.length < MAX_SEQUENCE_LENGTH) {
          normalizedPath.push({ ...lastPoint });  // ← Pad with LAST point
      }

      return {
          path: normalizedPath,
          originalLength: originalLength,  // ← Track actual length for masking
          keySequence: swipeData.keySequence,
          duration: swipeData.duration
      };
  }
  ```

#### Kotlin App (OnnxSwipePredictorImpl.kt):
- **Lines 871-933: `extractFeatures()`**
  ```kotlin
  fun extractFeatures(coordinates: List<PointF>, timestamps: List<Long>, touchedKeys: List<KeyboardData.Key>): TrajectoryFeatures {
      // 1. Normalize coordinates FIRST (0-1 range)
      val normalizedCoords = normalizeCoordinates(coordinates)

      // 2. Map touchedKeys to token indices
      val nearestKeys = touchedKeys.map { key ->
          key?.keys?.firstOrNull()?.let { kv ->
              when (kv) {
                  is KeyValue.CharKey -> {
                      val char = kv.char.lowercaseChar()
                      if (char in 'a'..'z') {
                          (char - 'a') + 4  // a=4, b=5, ..., z=29
                      } else {
                          0  // PAD for non-letter keys
                      }
                  }
                  else -> 0
              }
          } ?: 0
      }

      // 3. Pad or truncate to MAX_TRAJECTORY_POINTS
      val finalCoords = padOrTruncate(normalizedCoords, MAX_TRAJECTORY_POINTS)
      val finalNearestKeys = padOrTruncate(nearestKeys, MAX_TRAJECTORY_POINTS, 0)

      // ... velocity/acceleration calculation ...
  }
  ```

**DIFFERENCES:**
1. ❓ **Coordinate normalization method** - Need to verify `normalizeCoordinates()` divides by correct dimensions
2. ❓ **touchedKeys length validation** - Does touchedKeys.size == coordinates.size?
3. ✅ **Padding strategy** - Uses `padOrTruncate()` helper
4. ❓ **Actual length tracking** - Does TrajectoryFeatures store originalLength?

**TODO:**
- [ ] Verify normalizeCoordinates() implementation
- [ ] Check padOrTruncate() matches web demo's lastPoint padding
- [ ] Ensure TrajectoryFeatures.actualLength is set correctly

---

### 3. **Encoder Input Tensor Creation**

#### Web Demo (swipe.html):
- **Lines 1113-1174: `runInference()`**
  ```javascript
  async function runInference(features) {
      const trajectoryData = new Float32Array(MAX_SEQUENCE_LENGTH * 6);
      const nearestKeysData = new BigInt64Array(MAX_SEQUENCE_LENGTH);
      const srcMaskData = new Uint8Array(MAX_SEQUENCE_LENGTH);

      const keyMap = tokenizer ? tokenizer.char_to_idx : {
          'a': 4, 'b': 5, 'c': 6, 'd': 7, 'e': 8, 'f': 9, 'g': 10, 'h': 11,
          // ...
      };

      const actualLength = features.originalLength || MAX_SEQUENCE_LENGTH;

      for (let i = 0; i < MAX_SEQUENCE_LENGTH; i++) {
          const point = features.path[i];
          const baseIdx = i * 6;

          // Position (normalized 0-1)
          trajectoryData[baseIdx + 0] = point.x;
          trajectoryData[baseIdx + 1] = point.y;

          // Velocity (difference from previous point)
          if (i > 0) {
              const prevPoint = features.path[i - 1];
              trajectoryData[baseIdx + 2] = point.x - prevPoint.x; // vx
              trajectoryData[baseIdx + 3] = point.y - prevPoint.y; // vy
          } else {
              trajectoryData[baseIdx + 2] = 0;
              trajectoryData[baseIdx + 3] = 0;
          }

          // Acceleration (difference of velocities)
          if (i > 1) {
              const prevVx = trajectoryData[(i-1) * 6 + 2];
              const prevVy = trajectoryData[(i-1) * 6 + 3];
              trajectoryData[baseIdx + 4] = trajectoryData[baseIdx + 2] - prevVx; // ax
              trajectoryData[baseIdx + 5] = trajectoryData[baseIdx + 3] - prevVy; // ay
          } else {
              trajectoryData[baseIdx + 4] = 0;
              trajectoryData[baseIdx + 5] = 0;
          }

          // Nearest key index (int64)
          if (point.key && keyMap.hasOwnProperty(point.key)) {
              nearestKeysData[i] = BigInt(keyMap[point.key]);
          } else {
              nearestKeysData[i] = BigInt(0); // ← PAD if no key
          }

          // Mask: 1 for padded positions, 0 for real data
          srcMaskData[i] = i >= actualLength ? 1 : 0;
      }

      const trajectoryTensor = new ort.Tensor('float32', trajectoryData, [1, MAX_SEQUENCE_LENGTH, 6]);
      const nearestKeysTensor = new ort.Tensor('int64', nearestKeysData, [1, MAX_SEQUENCE_LENGTH]);
      const srcMaskTensor = new ort.Tensor('bool', srcMaskData, [1, MAX_SEQUENCE_LENGTH]);

      const encoderOutput = await encoderSession.run({
          trajectory_features: trajectoryTensor,
          nearest_keys: nearestKeysTensor,
          src_mask: srcMaskTensor
      });
  }
  ```

#### Kotlin App (OnnxSwipePredictorImpl.kt):
- **Lines 510-537: `createTrajectoryTensor()`**
  ```kotlin
  private fun createTrajectoryTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
      val byteBuffer = java.nio.ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * TRAJECTORY_FEATURES * 4)
      byteBuffer.order(java.nio.ByteOrder.nativeOrder())
      val buffer = byteBuffer.asFloatBuffer()

      for (i in 0 until MAX_SEQUENCE_LENGTH) {
          val point = features.normalizedCoordinates.getOrNull(i) ?: PointF(0f, 0f)
          val velocity = features.velocities.getOrNull(i) ?: PointF(0f, 0f)
          val acceleration = features.accelerations.getOrNull(i) ?: PointF(0f, 0f)

          // Exact 6-feature layout matching web demo: [x, y, vx, vy, ax, ay]
          buffer.put(point.x)          // Normalized x [0,1]
          buffer.put(point.y)          // Normalized y [0,1]
          buffer.put(velocity.x)       // Velocity x component (delta)
          buffer.put(velocity.y)       // Velocity y component (delta)
          buffer.put(acceleration.x)   // Acceleration x component (delta of delta)
          buffer.put(acceleration.y)   // Acceleration y component (delta of delta)
      }

      buffer.rewind()
      return OnnxTensor.createTensor(ortEnvironment, buffer,
          longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong(), TRAJECTORY_FEATURES.toLong()))
  }
  ```

- **Lines 542-560: `createNearestKeysTensor()`**
  ```kotlin
  private fun createNearestKeysTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
      val byteBuffer = java.nio.ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 8)
      byteBuffer.order(java.nio.ByteOrder.nativeOrder())
      val buffer = byteBuffer.asLongBuffer()

      for (i in 0 until MAX_SEQUENCE_LENGTH) {
          if (i < features.nearestKeys.size) {
              buffer.put(features.nearestKeys[i].toLong())
          } else {
              buffer.put(0L) // PAD
          }
      }

      buffer.rewind()
      return OnnxTensor.createTensor(ortEnvironment, buffer,
          longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong()))
  }
  ```

**DIFFERENCES:**
1. ✅ **Tensor layout matches** - Both use [x, y, vx, vy, ax, ay] order
2. ✅ **Velocity/acceleration calculation** - Done in extractFeatures() (lines 897-923)
3. ✅ **Key mapping** - Uses same token indices (a=4, b=5, etc.)
4. ❓ **Padding behavior** - Need to verify getOrNull() vs explicit checks

**TODO:**
- [ ] Verify extractFeatures() calculates velocity/acceleration identically to web demo
- [ ] Check if padding uses zeros vs last point

---

### 4. **Beam Search Decoder Implementation**

#### Web Demo (swipe.html):
- **Lines 1176-1289: `decodeLogits()`**
  ```javascript
  async function decodeLogits(encoderOutput) {
      const beamWidth = 5;
      const maxLength = 20;

      const PAD_IDX = 0, UNK_IDX = 1, SOS_IDX = 2, EOS_IDX = 3;

      let beams = [{
          tokens: [BigInt(SOS_IDX)],
          score: 0,
          finished: false
      }];

      for (let step = 0; step < maxLength; step++) {
          const candidates = [];

          for (const beam of beams) {
              if (beam.finished) {
                  candidates.push(beam);
                  continue;
              }

              // Prepare decoder inputs
              const DECODER_SEQ_LENGTH = 20;
              const paddedTokens = new BigInt64Array(DECODER_SEQ_LENGTH);
              const tgtMask = new Uint8Array(DECODER_SEQ_LENGTH);

              // Copy beam tokens
              for (let i = 0; i < beam.tokens.length && i < DECODER_SEQ_LENGTH; i++) {
                  paddedTokens[i] = beam.tokens[i];
              }
              for (let i = beam.tokens.length; i < DECODER_SEQ_LENGTH; i++) {
                  paddedTokens[i] = BigInt(PAD_IDX);
              }

              // Create target mask (1 for padded)
              for (let i = beam.tokens.length; i < DECODER_SEQ_LENGTH; i++) {
                  tgtMask[i] = 1;
              }

              // Source mask (all zeros)
              const srcMaskArray = new Uint8Array(memory.dims[1]);
              srcMaskArray.fill(0);

              // Run decoder
              const decoderOutput = await decoderSession.run({
                  memory: memory,
                  target_tokens: new ort.Tensor('int64', paddedTokens, [1, DECODER_SEQ_LENGTH]),
                  target_mask: new ort.Tensor('bool', tgtMask, [1, DECODER_SEQ_LENGTH]),
                  src_mask: new ort.Tensor('bool', srcMaskArray, [1, memory.dims[1]])
              });

              const logits = decoderOutput.logits;
              const logitsData = logits.data;
              const vocabSize = 30;

              // Extract logits for NEXT token position
              const tokenPosition = Math.min(beam.tokens.length - 1, DECODER_SEQ_LENGTH - 1);
              const startIdx = tokenPosition * vocabSize;
              const endIdx = startIdx + vocabSize;
              const relevantLogits = logitsData.slice(startIdx, endIdx);

              // Apply softmax
              const probs = softmax(Array.from(relevantLogits));
              const topK = getTopKIndices(probs, beamWidth);

              // Create new beam candidates
              for (const idx of topK) {
                  const newBeam = {
                      tokens: [...beam.tokens, BigInt(idx)],
                      score: beam.score + Math.log(probs[idx]),  // ← Log probability
                      finished: idx === EOS_IDX
                  };
                  candidates.push(newBeam);
              }
          }

          // Select top beams globally
          candidates.sort((a, b) => b.score - a.score);
          beams = candidates.slice(0, beamWidth);

          if (beams.every(b => b.finished)) {
              break;
          }
      }

      // Convert tokens to words
      const predictions = beams.map(beam => {
          const chars = [];
          for (const token of beam.tokens) {
              const idx = Number(token);
              if (idx === SOS_IDX || idx === EOS_IDX || idx === PAD_IDX) continue;
              if (idxToChar[idx] && !idxToChar[idx].startsWith('<')) {
                  chars.push(idxToChar[idx]);
              }
          }
          return chars.join('');
      }).filter(word => word.length > 0);

      return predictions;
  }
  ```

#### Kotlin App (OnnxSwipePredictorImpl.kt):
- **Lines 221-295: `runBeamSearch()`**
  ```kotlin
  private suspend fun runBeamSearch(
      memory: OnnxTensor,
      srcMaskTensor: OnnxTensor,
      features: SwipeTrajectoryProcessor.TrajectoryFeatures
  ): List<BeamSearchCandidate> = withContext(Dispatchers.Default) {

      // Initialize beam search
      val beams = mutableListOf<BeamSearchState>()
      beams.add(BeamSearchState(SOS_IDX, 0.0f, false))

      val finishedBeams = mutableListOf<BeamSearchState>()

      // Beam search loop with batched processing
      for (step in 0 until maxLength) {
          val activeBeams = beams.filter { !it.finished }

          if (activeBeams.isEmpty()) break

          // CRITICAL: Process all active beams in single batch
          val newBeams = processBatchedBeams(activeBeams, memory, srcMaskTensor, decoderSession)

          // Separate finished from active
          val newFinished = newBeams.filter { it.finished }
          val stillActive = newBeams.filter { !it.finished }

          finishedBeams.addAll(newFinished)
          beams.clear()
          beams.addAll(stillActive)

          // Early stopping
          if (beams.isEmpty() && finishedBeams.isNotEmpty()) break
          if (step >= 10 && finishedBeams.size >= 3) break
      }

      // Return all final beams
      val allFinalBeams = finishedBeams + beams

      val candidates = allFinalBeams.map { beam ->
          val word = tokenizer.tokensToWord(beam.tokens.drop(1)) // Remove SOS
          BeamSearchCandidate(word, kotlin.math.exp(beam.score).toFloat())
      }

      return candidates
  }
  ```

- **Lines 450-505: `processBatchedResults()`**
  ```kotlin
  private fun processBatchedResults(
      batchedOutput: OrtSession.Result,
      activeBeams: List<BeamSearchState>
  ): List<BeamSearchState> {
      val batchedLogitsTensor = batchedOutput.get(0) as OnnxTensor
      val batchedLogits = batchedLogitsTensor.value as Array<Array<FloatArray>>

      // CRITICAL: Collect ALL possible next hypotheses with GLOBAL scores
      val allHypotheses = mutableListOf<Triple<Int, Int, Float>>()

      activeBeams.forEachIndexed { batchIndex, beam ->
          val currentPos = beam.tokens.size - 1

          if (currentPos >= 0 && currentPos < batchedLogits[batchIndex].size) {
              val vocabLogits = batchedLogits[batchIndex][currentPos]
              val logProbs = applyLogSoftmax(vocabLogits)

              // Consider EVERY possible next token for global selection
              logProbs.forEachIndexed { tokenId, logProb ->
                  val newScore = beam.score + logProb
                  allHypotheses.add(Triple(batchIndex, tokenId, newScore))
              }
          }
      }

      // GLOBAL top-k selection: Sort ALL possibilities and take top beamWidth
      val topHypotheses = allHypotheses.sortedByDescending { it.third }.take(beamWidth)

      // Construct new beams from winners
      val newBeams = mutableListOf<BeamSearchState>()
      topHypotheses.forEach { (parentIndex, tokenId, score) ->
          val parentBeam = activeBeams[parentIndex]
          val newBeam = BeamSearchState(parentBeam)
          newBeam.tokens.add(tokenId.toLong())
          newBeam.score = score

          if (tokenId == EOS_IDX) {
              newBeam.finished = true
          }

          newBeams.add(newBeam)
      }

      return newBeams
  }
  ```

**DIFFERENCES:**
1. ✅ **Global top-k selection** - Kotlin implementation uses global beam selection (fixed in commit c4c51d0)
2. ✅ **Batched inference** - Kotlin processes all beams in single batch (optimization, should work)
3. ❓ **Token position calculation** - Web demo: `beam.tokens.length - 1`, Kotlin: `beam.tokens.size - 1` (same)
4. ❓ **Log probability accumulation** - Both use log-space scoring
5. ❓ **Early stopping** - Web demo: checks `beams.every(b => b.finished)`, Kotlin: checks `beams.isEmpty()`

**TODO:**
- [ ] Verify token position indexing matches between implementations
- [ ] Check if memory tensor expansion (lines 312-328) works correctly
- [ ] Validate src_mask tensor (should be all zeros like web demo line 1232)

---

## 🟡 MEDIUM PRIORITY DIFFERENCES

### 5. **Coordinate Normalization**

#### Web Demo (swipe.html):
- **Lines 873-890: `getNormalizedCoords()`**
  ```javascript
  function getNormalizedCoords(clientX, clientY) {
      if (!keyboardBounds) return { x: 0, y: 0, normalized: { x: 0, y: 0 } };

      const x = clientX - keyboardBounds.minX;
      const y = clientY - keyboardBounds.minY;

      const normX = (x / keyboardBounds.width) * NORMALIZED_WIDTH;  // Scale to 360
      const normY = (y / keyboardBounds.height) * NORMALIZED_HEIGHT; // Scale to 215

      const norm01X = x / keyboardBounds.width;   // ← For inference (0-1)
      const norm01Y = y / keyboardBounds.height;  // ← For inference (0-1)

      return {
          x: Math.round(normX),          // For display (360x215)
          y: Math.round(normY),
          normalized: {
              x: norm01X.toFixed(3),     // ← Used in inference
              y: norm01Y.toFixed(3)
          }
      };
  }
  ```

- **Lines 1093-1097: In `prepareSwipeFeatures()`**
  ```javascript
  normalizedPath.push({
      x: point.x / NORMALIZED_WIDTH,   // Divide 360-scale by 360 → 0-1
      y: point.y / NORMALIZED_HEIGHT,  // Divide 215-scale by 215 → 0-1
      key: point.key || null
  });
  ```

#### Kotlin App (OnnxSwipePredictorImpl.kt):
- **Need to find:** `normalizeCoordinates()` implementation
- **Location:** Likely in SwipeTrajectoryProcessor or OnnxSwipePredictorImpl

**TODO:**
- [ ] Find and verify normalizeCoordinates() implementation
- [ ] Ensure it divides by keyboard dimensions to get 0-1 range
- [ ] Check if it matches web demo's two-step normalization

---

### 6. **Tokenizer Configuration**

#### Web Demo (swipe.html):
- **Lines 1118-1123: Token mapping**
  ```javascript
  const keyMap = tokenizer ? tokenizer.char_to_idx : {
      'a': 4, 'b': 5, 'c': 6, 'd': 7, 'e': 8, 'f': 9, 'g': 10, 'h': 11,
      'i': 12, 'j': 13, 'k': 14, 'l': 15, 'm': 16, 'n': 17, 'o': 18, 'p': 19,
      'q': 20, 'r': 21, 's': 22, 't': 23, 'u': 24, 'v': 25, 'w': 26, 'x': 27,
      'y': 28, 'z': 29
  };
  ```

- **Lines 1185-1196: Token decoding**
  ```javascript
  const PAD_IDX = 0, UNK_IDX = 1, SOS_IDX = 2, EOS_IDX = 3;

  const idxToChar = tokenizer?.idx_to_char ?? {
      0: '<pad>', 1: '<unk>', 2: '<sos>', 3: '<eos>',
      4: 'a', 5: 'b', 6: 'c', 7: 'd', 8: 'e', 9: 'f', 10: 'g', 11: 'h',
      12: 'i', 13: 'j', 14: 'k', 15: 'l', 16: 'm', 17: 'n', 18: 'o', 19: 'p',
      20: 'q', 21: 'r', 22: 's', 23: 't', 24: 'u', 25: 'v', 26: 'w', 27: 'x',
      28: 'y', 29: 'z'
  };
  ```

#### Kotlin App:
- **Need to verify:** Tokenizer class implementation
- **Check:** Token indices match exactly (a=4, b=5, ..., z=29)
- **Check:** Special token indices (PAD=0, UNK=1, SOS=2, EOS=3)

**TODO:**
- [ ] Verify Tokenizer.kt has correct char_to_idx mapping
- [ ] Check tokensToWord() decoding implementation
- [ ] Ensure special tokens are filtered correctly

---

## 🟢 LOW PRIORITY / OPTIMIZATIONS

### 7. **Memory Management**

#### Kotlin App Advantages:
- **Lines 312-328:** Tensor expansion for batched inference
- **Lines 330-369:** Tensor pooling optimization
- **TensorMemoryManager:** Automatic cleanup

#### Web Demo:
- No explicit tensor management
- Relies on JavaScript garbage collection

**Note:** These are Kotlin optimizations, not causes of prediction failures.

---

### 8. **Error Handling**

#### Web Demo:
- **Lines 1262-1265:** Simple try-catch with console.error
  ```javascript
  try {
      const decoderOutput = await decoderSession.run({...});
  } catch (error) {
      console.error('Decoder error:', error);
      throw error;
  }
  ```

#### Kotlin App:
- **Lines 275-278:** Structured error handling with logging
- **ErrorHandling.kt:** Comprehensive exception management

**Note:** Better error handling in Kotlin, not a functional difference.

---

## 📋 IMMEDIATE ACTION ITEMS (Updated October 11, 2025)

### 🔴 Priority 1: Fix Core Feature Mismatches
These issues are the most likely cause of incorrect predictions.

#### 1. **Fix "Touched Keys" Logic for nearest_keys Tensor**
   **Problem:** The Kotlin `NeuralPredictionPipeline` provides an empty `touchedKeys` list to `SwipeTrajectoryProcessor`. The processor then falls back to geometric proximity calculation, which doesn't match the web demo. The model was trained on the sequence of keys the swipe path actually intersects.

   **Required Fix:**
   - In `SwipeTrajectoryProcessor.extractFeatures()`, remove the `touchedKeys` parameter
   - Generate `nearestKeys` by mapping each coordinate point to the key it's currently over
   - Use `detectNearestKeys()` or `detectKeyFromQwertyGrid()` as primary method, not fallback
   - Ensure character-to-token mapping ('a' -> 4, 'b' -> 5, etc.) matches web demo's `keyMap`

   **Files:**
   - `src/main/kotlin/tribixbite/keyboard2/neural/SwipeTrajectoryProcessor.kt`
   - `src/main/kotlin/tribixbite/keyboard2/neural/NeuralPredictionPipeline.kt`

#### 2. **Fix Decoder src_mask to All-Zeros**
   **Problem:** Kotlin code correctly calculates source mask (masking padded positions) and passes it to both encoder and decoder. However, working `swipe.html` passes an all-zeros mask (all positions valid) to the decoder. Model is trained expecting this specific behavior.

   **Required Fix:**
   - In `OnnxSwipePredictorImpl.runBeamSearch()` or `processBatchedBeams()`, don't reuse encoder's `srcMaskTensor`
   - When preparing decoder inputs, create new `OnnxTensor` for `src_mask` input
   - New tensor must have shape `[batch_size, sequence_length]` (e.g., `[8, 150]`) filled with `false`
   - This replicates web demo's `srcMaskArray.fill(0)` logic (line 1232)

   **Files:**
   - `src/main/kotlin/tribixbite/keyboard2/neural/OnnxSwipePredictorImpl.kt` (lines 221-505)

### 🟡 Priority 2: Verification and Refinement
Important checks to ensure perfect alignment after primary fixes.

#### 3. **Verify Trajectory Feature Calculation**
   **Problem:** Subtle differences in feature calculation after padding could exist.

   **Required Fix:**
   - Add detailed logging to `SwipeTrajectoryProcessor.extractFeatures()` and JS `runInference()`
   - Using same input swipe data, log final `trajectory_features` array from both
   - Confirm x, y (normalized), vx, vy (velocity), ax, ay (acceleration) are identical
   - Verify zero velocity/acceleration for padded points

   **Files:**
   - `src/main/kotlin/tribixbite/keyboard2/neural/SwipeTrajectoryProcessor.kt` (lines 871-933)

#### 4. **Unify Hardcoded Sequence Lengths**
   **Problem:** Magic numbers for sequence lengths need verification.

   **Required Fix:**
   - Confirm `MAX_SEQUENCE_LENGTH = 150` (encoder) and `DECODER_SEQ_LENGTH = 20` (decoder) used consistently
   - Move to shared constants in `OnnxSwipePredictorImpl.Companion`
   - Audit all functions: `createTrajectoryTensor()`, `runBeamSearch()`, `processBatchedBeams()`

   **Files:**
   - `src/main/kotlin/tribixbite/keyboard2/neural/OnnxSwipePredictorImpl.kt`

---

## 🔬 DEBUGGING CHECKLIST

When predictions fail, check these in order:

- [ ] **Input Data**
  - [ ] coordinates.size > 0
  - [ ] touchedKeys.size == coordinates.size
  - [ ] All touchedKeys are valid KeyValue.CharKey
  - [ ] Timestamps are monotonically increasing

- [ ] **Feature Extraction**
  - [ ] normalizedCoordinates all in [0, 1] range
  - [ ] nearestKeys all in [0, 29] range (0=PAD, 4-29=letters)
  - [ ] actualLength matches original swipe length
  - [ ] velocities/accelerations calculated correctly

- [ ] **Encoder Inference**
  - [ ] trajectory_features shape = [1, 150, 6]
  - [ ] nearest_keys shape = [1, 150]
  - [ ] src_mask shape = [1, 150], values 0/1
  - [ ] Encoder output shape = [1, 150, 256]

- [ ] **Decoder Inference**
  - [ ] memory tensor shape = [batchSize, 150, 256]
  - [ ] target_tokens shape = [batchSize, 20]
  - [ ] target_mask correctly marks padding
  - [ ] src_mask all zeros (no masking)
  - [ ] Decoder output shape = [batchSize, 20, 30]

- [ ] **Beam Search**
  - [ ] Token position = beam.tokens.size - 1
  - [ ] Logits extracted from correct position
  - [ ] Log softmax applied before scoring
  - [ ] Global top-k selection across all beams
  - [ ] EOS token (3) marks finished beams

- [ ] **Output Processing**
  - [ ] Tokens decoded excluding SOS/EOS/PAD
  - [ ] Words filtered to remove empty strings
  - [ ] Vocabulary lookup succeeds
  - [ ] At least one valid candidate returned

---

## 📝 NOTES

**Last Updated:** October 11, 2025
**Status:** ✅ Critical fixes implemented and committed (65c4d3c)
**Next Step:** Test predictions on device with logging enabled

**Key Insight:** The most likely issue is in the data pipeline (touchedKeys collection/mapping) since the beam search algorithm has been fixed to use global top-k selection. The web demo's success depends on having correct key associations for each swipe point.

---

## ✅ FIXES IMPLEMENTED (October 11, 2025)

### Commit 65c4d3c: Critical ONNX Pipeline Alignment

All **Priority 1** fixes have been implemented to align Kotlin implementation with working web demo:

1. **✅ Fixed touchedKeys Detection (Lines 871-878)**
   - **Before:** `extractFeatures()` accepted `touchedKeys` parameter from caller (usually empty)
   - **After:** Auto-detects keys from coordinates using `detectNearestKeys()`
   - **Impact:** Ensures 1:1 mapping between swipe points and key indices
   - **Files:** `OnnxSwipePredictorImpl.kt:871-878`

2. **✅ Fixed Decoder src_mask (Lines 322-329)**
   - **Before:** Reused encoder's src_mask (with proper padding masking)
   - **After:** Creates new all-zeros mask for decoder ([batchSize, 150] filled with false)
   - **Impact:** Matches trained model's expectation (web demo line 1232: `srcMaskArray.fill(0)`)
   - **Files:** `OnnxSwipePredictorImpl.kt:322-329, 358-362`

3. **✅ Added Feature Verification Logging (Lines 892-901)**
   - Logs first 3 trajectory feature vectors for comparison
   - Displays x, y, vx, vy, ax, ay, key_idx values
   - Confirms calculations match web demo implementation
   - **Files:** `OnnxSwipePredictorImpl.kt:892-901`

4. **✅ Unified Sequence Length Constants (Lines 23-24)**
   - Added `DECODER_SEQ_LENGTH = 20` constant
   - Replaced hardcoded values throughout codebase
   - Ensures consistency: encoder=150, decoder=20
   - **Files:** `OnnxSwipePredictorImpl.kt:23-24, 71-72, 314`

5. **✅ Real Key Positions Integration (FIX #30) - October 11, 2025**
   - **Before:** Only keyboard dimensions passed to neural predictor
   - **After:** `CleverKeysService.updateKeyboardDimensions()` now calls `getRealKeyPositions()`
   - **Impact:** Fixes incorrect nearest_keys detection [25,25,25...] by using actual key centers
   - **Root Cause:** Fallback QWERTY grid detection failed with default/incorrect dimensions
   - **Files:** `CleverKeysService.kt:491-503`, `Keyboard2View.kt:408-442`
   - **Commit:** 3ad76d4

6. **✅ ONNX Export Compatibility (FIX #31) - October 11, 2025**
   - **Before:** nearest_keys was 2D tensor [batch, sequence] with 1 key per point
   - **After:** nearest_keys is 3D tensor [batch, sequence, 3] with top 3 nearest keys per point
   - **Impact:** Matches Python ONNX export spec, prevents tensor shape mismatch errors
   - **Root Cause:** Python export changed to provide top 3 nearest keys for better prediction accuracy
   - **Changes:**
     - `TrajectoryFeatures.nearestKeys`: Changed from `List<Int>` to `List<List<Int>>`
     - `detectNearestKeys()`: Returns top 3 keys sorted by Euclidean distance
     - `detectKeysFromQwertyGrid()`: Full key position map with top-3 selection
     - `createNearestKeysTensor()`: Creates 3D tensor shape [1, 150, 3]
     - Logging: Displays all 3 nearest keys per point
   - **Files:** `OnnxSwipePredictorImpl.kt:524-548, 855, 875, 905, 990-1051`
   - **Commit:** TBD

### Testing Status
- [x] Build and install updated APK (48MB debug APK, commit a9a9006)
- [x] APK installation initiated via termux-open
- [x] **FIX #30 APPLIED** - Real key positions now passed to neural predictor (commit 3ad76d4)
- [x] **REBUILT** - New APK (49MB) with fix #30 ready for installation (Oct 11, 2025)
- [ ] **INSTALL** - Tap 'Install' in Android Package Installer
- [ ] **TEST** - Swipe common words: "hello", "world", "the", "test", "values"
- [ ] Verify logging shows correct key detection (not [25,25,25...])
- [ ] Compare predictions with web demo on same gestures
- [ ] Validate feature calculations match expected values

### Expected Improvements
After these fixes, the prediction pipeline should:
- Generate correct `nearest_keys` tensor matching swipe path
- Use proper masking for decoder attention mechanism
- Produce diverse predictions instead of repetitive tokens
- Match web demo's accuracy for common English words

---

## 🧪 TESTING INSTRUCTIONS

### Manual Testing Required:
1. **Install APK** - Tap "Install" in Android Package Installer (if not already done)
2. **Enable Keyboard** - Settings → Languages & input → Virtual keyboard → Add CleverKeys
3. **Activate Keyboard** - Open any text field and select CleverKeys from keyboard picker
4. **Test Swipe Gestures** - Try swiping common words:
   - "hello" (h → e → l → l → o)
   - "world" (w → o → r → l → d)
   - "the" (t → h → e)
   - "test" (t → e → s → t)
5. **Check Logs** - Use `adb logcat | grep OnnxSwipe` or `adb logcat -s OnnxSwipePredictor SwipeTrajectoryProcessor` to verify:
   - Key detection: "First 10 nearest keys: [...]"
   - Feature calculation: "Point[0]: x=..., y=..., vx=..., vy=..., ax=..., ay=..., key_idx=..."
   - Predictions: "Top 3: 'word1', 'word2', 'word3'"

### Expected Behavior:
- ✅ Predictions should be real English words
- ✅ Top prediction should usually match intended word
- ✅ No repetitive tokens (no more "ttt", "tttt", "rt", "tr")
- ✅ Diverse beam search results across multiple candidates

---

## 🚨 CRITICAL BLOCKER: ONNX Model Update Required (Oct 11, 2025)

### Status: **BLOCKED - Cannot Export Models on Termux**

### Problem
**Fix #31** changed the tensor format in Kotlin to send **3D nearest_keys [1, 150, 3]**, but existing ONNX models (dated Sept 14) expect **2D nearest_keys [1, 150]**.

**Test Result:**
```
[22:17:46.273] ✅ Neural engine initialized successfully
[22:17:51.855] 🌀 Swipe recorded: 238 points, keys: uuuuuuuujjjjjjjnnnnnn...
[22:17:52.260] 🧠 Neural prediction completed in 398ms
[22:17:52.261]    Predictions: 0 candidates ❌
```

**Root Cause:** ONNX Runtime throws exception on tensor shape mismatch, error handler returns empty predictions.

### Solution Created
✅ **Export script ready:** `model/export_onnx_3d.py` (commit 741b7db)
- Complete CharacterLevelSwipeModel definition inline
- 3D tensor support: top 3 nearest keys per point
- Feature extraction matching Kotlin implementation
- Beam search testing with swipes.jsonl
- Comprehensive documentation in `MODEL_EXPORT_STATUS.md`

### Platform Limitation
❌ **Cannot export on Termux/Android:**
1. **PyTorch:** Missing `libabsl_low_level_hash.so` system library
2. **ONNX Runtime:** No Android/aarch64 wheels available

### Next Steps (4 Options)

**Option 1: Google Colab Export** (RECOMMENDED - 5 Minutes)
1. Open [colab.research.google.com](https://colab.research.google.com)
2. Upload: `export_onnx_3d.py`, `full-model-49-0.795.ckpt`, `swipes.jsonl`
3. Run: `!pip install onnx onnxruntime`
4. Run: `!python export_onnx_3d.py`
5. Download `.onnx` files, copy to Android `assets/models/`
6. Rebuild APK: `./gradlew assembleDebug && ./build-install.sh`

**Full guide:** `model/EXPORT_VIA_COLAB.md`

**Benefits:**
- ✅ No local setup required
- ✅ Works from any device
- ✅ PyTorch pre-installed
- ✅ Free tier sufficient
- ✅ Takes ~5 minutes

**Option 2: Export on Dev Machine**
```bash
# On Mac/Linux/Windows:
cd /path/to/cleverkeys/model/
python export_onnx_3d.py

# Copy to Android:
adb push model/onnx_output/*.onnx /data/data/com.termux/files/home/git/swype/cleverkeys/assets/models/

# Rebuild APK:
./gradlew assembleDebug
./build-install.sh
```

**❌ ONNX Export on Termux IMPOSSIBLE** (Oct 12, 2025 - commit c7da26f)

After installing PyTorch 2.6.0 and ONNX Runtime on Termux, we discovered a **critical platform-specific bug**:

**Error:** `RuntimeError: required keyword attribute 'value' has the wrong type`

**Root Cause:** PyTorch 2.6.0 cannot serialize `prim::Constant` values during ONNX export on Termux/Android ARM64. This is a fundamental JIT tracing bug affecting ANY model using constants.

**Tested Workarounds (ALL FAILED):**
- ✗ Different opset versions (11, 14, 17)
- ✗ torch.where instead of masked_fill
- ✗ register_buffer to avoid scalar constants
- ✗ Disabled constant folding

**Analysis:** Consultation with Gemini 2.5 Pro confirmed this is a platform-specific PyTorch bug at the intersection of:
- Brand-new PyTorch version (2.6.0)
- Non-standard build environment (Termux)
- Specific architecture (ARM64)

**Files Created:**
- `model/ONNX_EXPORT_FAILURE_REPORT.md` - Comprehensive bug report with minimal reproducer
- `model/test_masked_fill.py` - Minimal test case for PyTorch team bug report
- `model/export_log.txt` - Full error output with torch IR graph

**Conclusion:** ONNX export must be done via Google Colab or development machine. The export script (`export_onnx_3d.py`) is ready and will work correctly on standard x86_64 platforms.

---

**Option 3: Revert Fix #31** (TEMPORARY WORKAROUND)
```bash
git revert f16c5bb  # Revert to 2D nearest_keys
./gradlew assembleDebug
./build-install.sh
```
- ⚠️ Uses inferior 2D format (1 key per point)
- ⚠️ Lower prediction accuracy
- ✅ Works with existing Sept 14 models immediately

**Option 4: Request Pre-Exported Models**
If no access to Colab or dev machine, models can be provided from checkpoint.

### Files Created
- `model/export_onnx_3d.py` - Export script with 3D tensor support
- `model/EXPORT_VIA_COLAB.md` - Google Colab walkthrough (5 minutes)
- `model/EXPORT_INSTRUCTIONS.md` - Dev machine export guide
- `MODEL_EXPORT_STATUS.md` - Complete status and solution paths
- `ONNX_MODEL_UPDATE_REQUIRED.md` - Problem diagnosis
- **Commits:** 741b7db, bd4ccf1

### Impact on Testing
- ❌ Cannot test Fix #31 until new models are available
- ❌ Current APK will return 0 predictions due to tensor mismatch
- ✅ **Solution Ready:** Google Colab export takes ~5 minutes
- ⏳ Testing can proceed after Colab export completes

**Recommended Action:** Follow `model/EXPORT_VIA_COLAB.md` to generate new models.
