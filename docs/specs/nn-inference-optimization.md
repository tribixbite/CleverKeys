# Neural Network Inference Optimization Specification

**Status**: Complete (3 of 4 optimizations - encoder caching deferred)
**Priority**: High (latency-sensitive)
**Last Updated**: 2026-01-26

---

## 1. Overview

This document outlines optimization opportunities for the ONNX neural network inference pipeline used in swipe typing prediction. The target is **<100ms end-to-end latency** from swipe gesture completion to word prediction display.

### Current Architecture

```
Swipe Gesture → SwipeTrajectoryProcessor → EncoderWrapper → BeamSearchEngine → Word Predictions
                     ↓                          ↓                 ↓
              Feature Extraction          ONNX Encoder      ONNX Decoder (per step)
              (~5-10ms)                   (~15-25ms)        (~10-20ms × N steps)
```

### Key Files

| File | Purpose |
|------|---------|
| `onnx/BeamSearchEngine.kt` | Beam search decoding with decoder calls |
| `onnx/EncoderWrapper.kt` | Encoder model inference |
| `onnx/DecoderWrapper.kt` | Decoder model inference |
| `onnx/TensorFactory.kt` | Tensor creation utilities |
| `onnx/SessionConfigurator.kt` | ONNX Runtime session options |
| `onnx/SwipePredictorOrchestrator.kt` | Pipeline coordinator |
| `SwipeTrajectoryProcessor.kt` | Feature extraction pipeline |

---

## 2. Optimization Opportunities

### 2.1 Tensor Reuse (High Impact) ⭐

**Problem**: Creating new `OnnxTensor` objects for every beam search step causes GC pressure and allocation overhead.

**Current Code** (BeamSearchEngine.kt:246-258):
```kotlin
// Created ONCE per step (good):
val actualSrcLengthTensor = OnnxTensor.createTensor(ortEnvironment, intArrayOf(actualSrcLength))

// Created PER BEAM (bad - 5 beams × 10 steps = 50 allocations):
for (beam in activeBeams) {
    val tgtTokens = IntArray(DECODER_SEQ_LEN) { PAD_IDX }  // NEW allocation
    // ... populate tokens ...
    val targetTokensTensor = OnnxTensor.createTensor(ortEnvironment,
        java.nio.IntBuffer.wrap(tgtTokens), longArrayOf(1, DECODER_SEQ_LEN.toLong()))
}
```

**Impact**: ~50 tensor allocations per prediction (5 beams × 10 steps)

---

### 2.2 Batched Decoding (High Impact) ⭐

**Problem**: Sequential processing of beams makes N separate ONNX inference calls per step.

**Current Code** (BeamSearchEngine.kt:139-140):
```kotlin
// SEQUENTIAL PROCESSING (current)
val nextBeams = processSequential(activeBeams, memory, actualSrcLength, step)
```

**Existing Infrastructure** (DecoderWrapper.kt:107-168):
```kotlin
// ALREADY EXISTS but NOT USED by BeamSearchEngine!
fun decodeBatched(
    memory: OnnxTensor,
    beamTokens: List<List<Long>>,
    actualSrcLength: Int,
    decoderSeqLength: Int,
    step: Int = 0
): DecoderResult
```

**Impact**: 5 beams × 10 steps = 50 decoder calls → 1 call per step = 10 decoder calls (5x reduction)

---

### 2.3 Encoder Caching (Medium Impact)

**Problem**: Encoder output (`memory` tensor) is computed fresh for every prediction, even for similar swipe patterns.

**Current State**: No caching between predictions.

**Potential**: Similar swipe patterns (same start/end keys, similar trajectory shape) could reuse encoder output.

---

### 2.4 XNNPACK Thread Configuration (Low-Medium Impact)

**Problem**: Fixed thread count (4) may not be optimal for all devices.

**Current Code** (SessionConfigurator.kt:84-90):
```kotlin
// HARDCODED to 4 threads
xnnOptions["intra_op_num_threads"] = "4"
sessionOptions.setIntraOpNumThreads(4)
```

**Solution**: Make configurable via user settings (see Section 4.4).

---

## 3. Detailed Implementation Plans

### 3.1 Tensor Reuse Implementation (Simplified)

**Goal**: Cache the `actual_src_length` tensor across beam search steps.

**Background**: Initial implementation included pre-allocated IntArray buffers and Direct ByteBuffers
for target tokens. Performance analysis revealed JVM is highly optimized for small, short-lived
allocations (80-byte arrays). The overhead of Direct ByteBuffer management outweighed savings.

**Final Implementation** - only `cachedSrcLengthTensor` is reused:

```kotlin
class BeamSearchEngine(...) {
    // Cached actualSrcLength tensor (recreated only when length changes)
    // Saves ~15 OnnxTensor creations per prediction (one per step -> one per search)
    private var cachedSrcLength: Int = -1
    private var cachedSrcLengthTensor: OnnxTensor? = null

    fun search(...): List<BeamSearchCandidate> {
        try {
            // Main decoding loop...
        } finally {
            cleanup()  // Release native memory
        }
    }

    private fun processSequential(...) {
        // OPTIMIZATION: Reuse actualSrcLengthTensor if length unchanged
        if (actualSrcLength != cachedSrcLength) {
            cachedSrcLengthTensor?.close()
            cachedSrcLengthTensor = OnnxTensor.createTensor(ortEnvironment, intArrayOf(actualSrcLength))
            cachedSrcLength = actualSrcLength
        }

        for (beam in activeBeams) {
            // Simple allocation - JVM optimized for small arrays
            val tgtTokens = IntArray(DECODER_SEQ_LEN) { PAD_IDX }
            // ... populate and create tensor
        }
    }

    fun cleanup() {
        cachedSrcLengthTensor?.close()
        cachedSrcLengthTensor = null
        cachedSrcLength = -1
    }
}
```

**Why only srcLength caching?**
- `actual_src_length` is a native OnnxTensor wrapping a single int
- Created ~15 times per prediction (once per decoding step)
- Native tensor allocation has higher overhead than JVM arrays
- Target token IntArrays (80 bytes) are efficiently allocated by JVM

**Estimated Impact**: ~15 native tensor allocations saved per prediction

---

### 3.2 Batched Decoding Implementation

**Goal**: Use existing `DecoderWrapper.decodeBatched()` instead of sequential processing.

**Changes to BeamSearchEngine.kt**:

**Option A: Refactor to use DecoderWrapper** (Cleaner, but requires constructor change)

```kotlin
class BeamSearchEngine(
    private val decoderWrapper: DecoderWrapper,  // CHANGED: Use wrapper instead of raw session
    private val ortEnvironment: OrtEnvironment,
    // ... rest of params ...
) {
    // Remove: private val decoderSession: OrtSession
```

**Option B: Add batched processing alongside existing code** (Less invasive)

```kotlin
private fun processBatched(
    activeBeams: List<BeamState>,
    memory: OnnxTensor,
    actualSrcLength: Int,
    step: Int
): List<BeamState> {
    val newCandidates = ArrayList<BeamState>()
    val numBeams = activeBeams.size

    // 1. Prepare batched input tensor [numBeams, DECODER_SEQ_LEN]
    val batchedTokens = IntArray(numBeams * DECODER_SEQ_LEN)
    for (b in activeBeams.indices) {
        val beam = activeBeams[b]
        val len = min(beam.tokens.size, DECODER_SEQ_LEN)
        for (i in 0 until len) {
            batchedTokens[b * DECODER_SEQ_LEN + i] = beam.tokens[i].toInt()
        }
        // Rest is already PAD_IDX (0) from array initialization
    }

    val batchedShape = longArrayOf(numBeams.toLong(), DECODER_SEQ_LEN.toLong())
    val batchedTokensTensor = OnnxTensor.createTensor(
        ortEnvironment,
        java.nio.IntBuffer.wrap(batchedTokens),
        batchedShape
    )

    // 2. Create src_length tensor (broadcast model uses single value)
    val srcLengthTensor = OnnxTensor.createTensor(ortEnvironment, intArrayOf(actualSrcLength))

    try {
        // 3. Single decoder call for ALL beams
        val inputs = mapOf(
            "memory" to memory,
            "actual_src_length" to srcLengthTensor,
            "target_tokens" to batchedTokensTensor
        )

        val result = decoderSession.run(inputs)
        val logitsTensor = result.get(0) as OnnxTensor

        // 4. Extract logits [numBeams, DECODER_SEQ_LEN, vocabSize]
        @Suppress("UNCHECKED_CAST")
        val logits3D = logitsTensor.value as Array<Array<FloatArray>>

        // 5. Process each beam's logits
        for (b in activeBeams.indices) {
            val beam = activeBeams[b]
            val currentPos = beam.tokens.size - 1

            if (currentPos in 0 until DECODER_SEQ_LEN) {
                val logits = logits3D[b][currentPos]

                // Apply Trie Masking, Prefix Boosts, etc. (same as sequential)
                applyTrieMasking(beam, logits)
                val appliedBoosts = applyPrefixBoosts(beam, logits)
                val logProbs = logSoftmax(logits)
                val topIndices = getTopKIndices(logProbs, beamWidth)

                // Create new beam candidates (same as sequential)
                for (idx in topIndices) {
                    // ... same logic as processSequential ...
                }
            }
        }

        result.close()

    } finally {
        batchedTokensTensor.close()
        srcLengthTensor.close()
    }

    return newCandidates
}
```

**Modify search() to use batched mode**:

```kotlin
fun search(memory: OnnxTensor, actualSrcLength: Int, useBatched: Boolean = false): List<BeamSearchCandidate> {
    // ... existing setup ...

    while (step < maxLength) {
        // ... existing beam filtering ...

        try {
            val startInf = System.nanoTime()

            // CHANGED: Use batched processing when enabled
            val nextBeams = if (useBatched && activeBeams.size > 1) {
                processBatched(activeBeams, memory, actualSrcLength, step)
            } else {
                processSequential(activeBeams, memory, actualSrcLength, step)
            }

            candidates.addAll(nextBeams)
            // ... rest unchanged ...
        }
    }
}
```

**Estimated Impact**: 5x reduction in decoder calls (50 → 10 per prediction)

---

### 3.3 Encoder Caching Implementation

**Goal**: Cache encoder output for similar swipe trajectories.

**Design Considerations**:
- **Key**: Hash of trajectory fingerprint (start/end keys, sampled waypoints, length)
- **Value**: Encoder memory tensor
- **Size**: LRU cache with ~10 entries (encoder output is ~200KB per entry)
- **Invalidation**: TTL-based (5 minutes) or explicit clear on keyboard resize

**New class: EncoderCache.kt**:

```kotlin
package tribixbite.cleverkeys.onnx

import ai.onnxruntime.OnnxTensor
import android.util.LruCache
import tribixbite.cleverkeys.SwipeTrajectoryProcessor

/**
 * LRU cache for encoder memory tensors.
 *
 * Caches encoder output based on trajectory fingerprint to avoid
 * re-encoding similar swipe patterns.
 */
class EncoderCache(
    maxEntries: Int = 10,
    private val ttlMs: Long = 5 * 60 * 1000 // 5 minutes
) {
    data class CacheEntry(
        val memory: OnnxTensor,
        val actualLength: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMs
    }

    private val cache = object : LruCache<Long, CacheEntry>(maxEntries) {
        override fun entryRemoved(
            evicted: Boolean,
            key: Long,
            oldValue: CacheEntry,
            newValue: CacheEntry?
        ) {
            // Close evicted tensor to prevent memory leak
            if (evicted) {
                oldValue.memory.close()
            }
        }
    }

    /**
     * Generate trajectory fingerprint hash.
     *
     * Uses: start key, end key, path length, 4 sampled waypoint keys
     */
    fun computeFingerprint(features: SwipeTrajectoryProcessor.TrajectoryFeatures): Long {
        val keys = features.nearestKeys
        if (keys.isEmpty()) return 0L

        var hash = 17L

        // Start key
        hash = hash * 31 + keys.first()

        // End key
        hash = hash * 31 + keys.last()

        // Path length (binned to 10-point buckets)
        hash = hash * 31 + (features.actualLength / 10)

        // 4 sampled waypoints (25%, 50%, 75%)
        val step = keys.size / 4
        if (step > 0) {
            for (i in 1..3) {
                hash = hash * 31 + keys.getOrElse(i * step) { 0 }
            }
        }

        return hash
    }

    fun get(fingerprint: Long): CacheEntry? {
        val entry = cache.get(fingerprint)
        if (entry != null && entry.isExpired()) {
            cache.remove(fingerprint)
            entry.memory.close()
            return null
        }
        return entry
    }

    fun put(fingerprint: Long, memory: OnnxTensor, actualLength: Int) {
        cache.put(fingerprint, CacheEntry(memory, actualLength))
    }

    fun clear() {
        // Close all cached tensors
        cache.snapshot().values.forEach { it.memory.close() }
        cache.evictAll()
    }

    fun stats(): String {
        return "EncoderCache: ${cache.size()}/${cache.maxSize()} entries"
    }
}
```

**Integration in SwipePredictorOrchestrator.kt**:

```kotlin
class SwipePredictorOrchestrator private constructor(private val context: Context) {
    // ... existing fields ...

    // NEW: Encoder cache
    private val encoderCache = EncoderCache(maxEntries = 10)

    fun predict(input: SwipeInput): PredictionPostProcessor.Result {
        // ... existing feature extraction ...

        // NEW: Check encoder cache
        val fingerprint = encoderCache.computeFingerprint(features)
        val cachedEntry = encoderCache.get(fingerprint)

        val memory: OnnxTensor
        val encoderTime: Long

        if (cachedEntry != null) {
            // Cache hit - reuse encoder output
            memory = cachedEntry.memory
            encoderTime = 0L
            if (debugModeActive) {
                logDebug("⚡ Encoder CACHE HIT (fingerprint=$fingerprint)\n")
            }
        } else {
            // Cache miss - run encoder
            val encoderStartTime = System.currentTimeMillis()
            val encoderResult = encoderWrapper!!.encode(features)
            memory = encoderResult.memory
            encoderTime = System.currentTimeMillis() - encoderStartTime

            // Store in cache (don't cache if actualLength differs significantly)
            encoderCache.put(fingerprint, memory, features.actualLength)

            if (debugModeActive) {
                logDebug("⚡ Encoder: ${encoderTime}ms (cached for fingerprint=$fingerprint)\n")
            }
        }

        // ... rest of prediction (unchanged) ...
    }

    fun cleanup() {
        encoderCache.clear()  // NEW
        // ... existing cleanup ...
    }
}
```

**Estimated Impact**: ~20-30% latency reduction for repeated similar swipes

**Risk**: Tensor lifecycle management - must ensure cached tensors aren't closed while in use.

---

### 3.4 XNNPACK Threading as User Setting

**Goal**: Allow users to configure XNNPACK thread count based on their device.

**Step 1: Add to Defaults (Config.kt)**:

```kotlin
object Defaults {
    // ... existing defaults ...

    // ONNX Runtime settings
    const val ONNX_XNNPACK_THREADS = 2  // Default to 2 (good for most ARM devices)
}
```

**Step 2: Add to Config class (Config.kt)**:

```kotlin
class Config private constructor(prefs: SharedPreferences, res: Resources) {
    // ... existing fields ...

    @JvmField var onnx_xnnpack_threads = Defaults.ONNX_XNNPACK_THREADS

    private fun refresh(prefs: SharedPreferences, res: Resources) {
        // ... existing refresh code ...

        onnx_xnnpack_threads = prefs.getInt("onnx_xnnpack_threads", Defaults.ONNX_XNNPACK_THREADS)
    }
}
```

**Step 3: Modify SessionConfigurator.kt**:

```kotlin
object SessionConfigurator {
    private const val TAG = "SessionConfigurator"

    fun createOptimizedSessionOptions(
        context: Context?,
        sessionName: String,
        xnnpackThreads: Int = 2  // NEW parameter with default
    ): OrtSession.SessionOptions {
        // ... existing code ...
    }

    private fun tryEnableHardwareAcceleration(
        sessionOptions: OrtSession.SessionOptions,
        sessionName: String,
        xnnpackThreads: Int  // NEW parameter
    ) {
        // ... NNAPI and QNN attempts ...

        // Try XNNPACK
        try {
            val xnnOptions = HashMap<String, String>()
            xnnOptions["intra_op_num_threads"] = xnnpackThreads.toString()  // CHANGED
            sessionOptions.addXnnpack(xnnOptions)
            sessionOptions.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            sessionOptions.setIntraOpNumThreads(xnnpackThreads)  // CHANGED
            Log.i(TAG, "✅ XNNPACK enabled for $sessionName (threads=$xnnpackThreads)")
        } catch (e: Exception) {
            Log.w(TAG, "XNNPACK failed, using CPU", e)
        }
    }
}
```

**Step 4: Add to SettingsActivity.kt** (in Neural Settings section):

```kotlin
// In the neural prediction settings section:
SearchableSetting(
    "ONNX Thread Count",
    listOf("performance", "threads", "xnnpack", "cpu"),
    { settings ->
        val current = _config.onnx_xnnpack_threads
        val threadCounts = listOf(1, 2, 4, 6, 8)
        val displayValues = threadCounts.map {
            if (it == 2) "$it threads (Recommended)" else "$it threads"
        }
        showListDialog(
            "ONNX Inference Threads",
            "Number of CPU threads for neural inference. Lower values reduce battery usage, higher values may improve speed on multi-core devices.",
            displayValues.toTypedArray(),
            threadCounts.indexOf(current).coerceAtLeast(0)
        ) { index ->
            _prefs.edit().putInt("onnx_xnnpack_threads", threadCounts[index]).apply()
            Toast.makeText(this, "Restart app for thread change to take effect", Toast.LENGTH_LONG).show()
        }
    }
),
```

**Note**: Session options are set at model load time, so changes require app restart or explicit session recreation.

**Estimated Impact**: Variable (device-dependent). 2 threads often optimal for ARM.

---

## 4. Implementation Priority

| Optimization | Impact | Effort | Priority | Status |
|-------------|--------|--------|----------|--------|
| **Batched Decoding** | High (5x fewer decoder calls) | Medium | 1 | ✅ Implemented - disabled by default |
| **Tensor Reuse** | Medium (15 allocations saved) | Low | 2 | ✅ Simplified - srcLength only |
| **XNNPACK Threads Setting** | Low-Medium | Low | 3 | ✅ Implemented with UI slider (1-8 threads) |
| **Encoder Caching** | Medium (cache hits only) | Medium | 4 | ⏸️ Deferred (complexity vs. benefit)

**Implementation Notes:**
- **Batched Decoding**: Uses broadcast-enabled model (memory [1,...] broadcasts to [N,...])
- **Tensor Reuse**: IntArray/ByteBuffer pre-allocation reverted (JVM optimized for small allocations)
- **XNNPACK UI**: Settings slider + backup/export support + reset-to-defaults profile
- **Memory Safety**: try-finally ensures cleanup() called after every search()

---

## 5. Testing Strategy

### 5.1 Performance Benchmarks

```kotlin
// In SwipePredictionTest.kt or new benchmark test
@Test
fun benchmarkInferenceLatency() {
    val orchestrator = SwipePredictorOrchestrator.getInstance(context)
    orchestrator.initialize()

    val testInputs = loadTestSwipeInputs() // Various lengths/patterns

    val times = mutableListOf<Long>()
    repeat(100) {
        val input = testInputs.random()
        val start = System.nanoTime()
        orchestrator.predict(input)
        times.add((System.nanoTime() - start) / 1_000_000)
    }

    Log.i("Benchmark", "Latency: min=${times.min()}ms, avg=${times.average()}ms, max=${times.max()}ms")
    Log.i("Benchmark", "P50=${times.sorted()[50]}ms, P95=${times.sorted()[95]}ms")
}
```

### 5.2 Prediction Quality Diff Test

```kotlin
@Test
fun verifyOptimizationDoesNotChangePredictions() {
    // Compare sequential vs batched predictions on same inputs
    val sequentialResults = runPredictions(useBatched = false)
    val batchedResults = runPredictions(useBatched = true)

    assertEquals(sequentialResults, batchedResults, "Batched mode changed predictions!")
}
```

### 5.3 Memory Profiling

- Use Android Profiler to compare GC events before/after tensor reuse
- Monitor OnnxTensor allocations via allocation tracking

---

## 6. Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Batched decoding changes predictions | Diff test (5.2) validates equivalence |
| Tensor reuse causes stale data | Clear/fill arrays at start of each use |
| Encoder cache returns stale result | Fingerprint includes trajectory shape, TTL expiry |
| XNNPACK thread change degrades performance | Default to safe value (2), user override |
| Cached tensor closed while in use | Reference counting or defensive copy |

---

## 7. Future Considerations

1. **FP16 Quantization**: Convert models to FP16 for ~2x speedup on supported hardware
2. **Dynamic Batching**: Adjust batch size based on available beams
3. **Async Encoding**: Start encoding while user is still swiping
4. **NNAPI Caching**: Leverage NNAPI compilation caching for faster cold starts
