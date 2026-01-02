# MemoryPool Optimization Spec

**Feature**: Enhanced Memory Pooling for ONNX Inference
**Status**: 🟡 PLANNED (Medium Priority Optimization)
**Priority**: P2 (Performance Enhancement)
**Date Created**: 2026-01-02
**Last Updated**: 2026-01-02

---

## 1. Current State

### Existing Implementation

The `MemoryPool` class (`onnx/MemoryPool.kt`) provides basic buffer pooling:

```kotlin
class MemoryPool {
    // Sequential decoder path
    private var pooledTokensByteBuffer: ByteBuffer?
    private var pooledTokensLongBuffer: LongBuffer?
    private var pooledMemoryArray: Array<Array<FloatArray>>?
    private var pooledSrcMaskArray: Array<BooleanArray>?

    // Batched decoder path
    private var preallocBatchedTokens: Array<IntArray>?
    private var preallocTokensByteBuffer: ByteBuffer?
    private var preallocTokensIntBuffer: IntBuffer?
    private var preallocSrcLengths: IntArray?
    private var preallocProbs: FloatArray?
}
```

### Current Limitations

1. **No OnnxTensor pooling**: `OnnxTensor.createTensor()` is called every inference, allocating native memory
2. **No encoder output caching**: Memory tensor recreated even for same swipe
3. **No buffer size tracking**: Can't monitor or limit memory usage
4. **No LRU eviction**: Buffers allocated but never shrunk
5. **Thread-unsafe**: Documented limitation, problematic for async prediction
6. **No warm-up**: First prediction slower due to lazy allocation

---

## 2. Proposed Enhancements

### 2.1 OnnxTensor Pooling

**Problem**: Each decoder step creates new OnnxTensor objects for inputs:
```kotlin
// Current: Allocates each time
val targetTokensTensor = OnnxTensor.createTensor(ortEnvironment,
    java.nio.IntBuffer.wrap(tgtTokens), longArrayOf(1, DECODER_SEQ_LEN.toLong()))
```

**Solution**: Pool tensor wrappers with reusable backing buffers:
```kotlin
class TensorPool(private val ortEnv: OrtEnvironment) {
    // Pool of pre-created tensors with reusable backing arrays
    private val intTensorPool = ArrayDeque<PooledIntTensor>()

    data class PooledIntTensor(
        val backingBuffer: IntArray,
        val directBuffer: ByteBuffer,
        val shape: LongArray,
        var tensor: OnnxTensor?
    )

    fun acquireIntTensor(shape: LongArray): PooledIntTensor {
        // Return from pool or create new
    }

    fun releaseIntTensor(tensor: PooledIntTensor) {
        tensor.tensor?.close()
        tensor.tensor = null
        intTensorPool.addLast(tensor)
    }
}
```

### 2.2 Encoder Output Caching

**Problem**: For multi-tap/correction scenarios, we re-encode the same swipe trajectory.

**Solution**: Cache encoder output with trajectory hash:
```kotlin
class EncoderOutputCache(private val maxEntries: Int = 4) {
    private val cache = LinkedHashMap<Int, CachedMemory>(maxEntries, 0.75f, true)

    data class CachedMemory(
        val trajectoryHash: Int,
        val memory: FloatArray,  // Flattened [1, 150, 256]
        val actualSrcLength: Int,
        val timestamp: Long
    )

    fun get(trajectoryHash: Int): CachedMemory?
    fun put(trajectoryHash: Int, memory: CachedMemory)

    // Evict entries older than 5 seconds
    fun evictStale(maxAgeMs: Long = 5000L)
}
```

### 2.3 Memory Budget Tracking

**Problem**: No visibility into memory usage, can't prevent OOM.

**Solution**: Track allocations and enforce budgets:
```kotlin
class MemoryBudget {
    private val currentUsageBytes = AtomicLong(0)
    private val maxBudgetBytes: Long

    fun allocate(bytes: Long): Boolean {
        if (currentUsageBytes.get() + bytes > maxBudgetBytes) {
            Log.w(TAG, "Memory budget exceeded: ${currentUsageBytes.get()} + $bytes > $maxBudgetBytes")
            return false
        }
        currentUsageBytes.addAndGet(bytes)
        return true
    }

    fun release(bytes: Long) {
        currentUsageBytes.addAndGet(-bytes)
    }

    fun usagePercent(): Float = currentUsageBytes.get().toFloat() / maxBudgetBytes
}
```

### 2.4 Thread-Safe Pool

**Problem**: Current MemoryPool is not thread-safe.

**Solution**: Use concurrent data structures or per-thread pools:
```kotlin
class ThreadSafeMemoryPool {
    // Option A: Per-thread pool (no contention)
    private val threadLocalPool = ThreadLocal.withInitial { LocalPool() }

    // Option B: Lock-striped concurrent pool
    private val stripes = Array(4) { ReentrantLock() }
    private val pools = Array(4) { PoolSegment() }

    fun acquire(type: BufferType): ByteBuffer {
        val stripe = Thread.currentThread().id % stripes.size
        stripes[stripe.toInt()].withLock {
            return pools[stripe.toInt()].acquire(type)
        }
    }
}
```

### 2.5 Warm-Up Protocol

**Problem**: First prediction is slow due to lazy allocation and JIT.

**Solution**: Pre-warm pools on keyboard initialization:
```kotlin
class MemoryPoolWarmup {
    suspend fun warmup(pool: MemoryPool, config: NeuralConfig) {
        // Pre-allocate all pools
        pool.initializePreallocatedBuffers(
            maxBeams = config.beamWidth,
            decoderSeqLength = 20,
            vocabSize = 35
        )
        pool.ensurePooledCapacity(
            newCapacity = config.beamWidth,
            maxSeqLength = 150,
            hiddenDim = 256
        )

        // Touch pages to avoid page faults on first use
        val buffers = pool.getPreallocBatchedTokens()
        buffers.forEach { arr -> arr.fill(0) }

        Log.d(TAG, "MemoryPool warmed up: ${pool.getPooledCapacity()} beams")
    }
}
```

---

## 3. Implementation Plan

### Phase 1: Memory Budget & Monitoring

**Time Estimate: 2-3 hours**

- [ ] Create `MemoryBudget` class with allocation tracking
- [ ] Integrate with existing MemoryPool
- [ ] Add debug logging for allocations
- [ ] Add memory usage to NeuralSettingsActivity (info display only)

### Phase 2: OnnxTensor Pooling

**Time Estimate: 4-6 hours**

- [ ] Create `TensorPool` class
- [ ] Implement acquire/release for IntBuffer-backed tensors
- [ ] Implement acquire/release for FloatBuffer-backed tensors
- [ ] Integrate into BeamSearchEngine
- [ ] Benchmark reduction in allocations

### Phase 3: Encoder Output Caching

**Time Estimate: 3-4 hours**

- [ ] Implement trajectory hashing (hash of coordinates + timestamps)
- [ ] Create `EncoderOutputCache` with LRU eviction
- [ ] Integrate into OnnxSwipePredictorImpl
- [ ] Add cache hit/miss logging
- [ ] Test multi-tap scenarios for cache effectiveness

### Phase 4: Thread Safety

**Time Estimate: 3-4 hours**

- [ ] Evaluate thread-local vs concurrent pool approach
- [ ] Implement chosen solution
- [ ] Add stress tests for concurrent access
- [ ] Verify no data races with ThreadSanitizer

### Phase 5: Warm-Up & Optimization

**Time Estimate: 2-3 hours**

- [ ] Add warm-up call in CleverKeysService.onCreate()
- [ ] Measure first-prediction latency before/after
- [ ] Profile and optimize hot allocation paths
- [ ] Document final memory footprint

---

## 4. Success Criteria

- [ ] Allocation count per prediction reduced by ≥50%
- [ ] GC pause time during typing reduced
- [ ] Memory usage tracked and bounded
- [ ] No memory leaks after 1000+ predictions
- [ ] Thread-safe operation verified
- [ ] First prediction latency reduced by ≥30%

---

## 5. References

- Current implementation: `src/main/kotlin/tribixbite/cleverkeys/onnx/MemoryPool.kt`
- ONNX Runtime memory management: https://onnxruntime.ai/docs/performance/tune-performance.html
- Android memory profiling: https://developer.android.com/studio/profile/memory-profiler

---

*— Opus 4.5*
