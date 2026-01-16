# Memory Pool Optimization

## Overview

Enhanced memory pooling for ONNX inference to reduce allocations, prevent GC pauses during typing, and improve first-prediction latency through pre-warming.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/onnx/MemoryPool.kt` | `MemoryPool` | Buffer pooling for decoder |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/TensorPool.kt` | `TensorPool` | OnnxTensor wrapper pooling |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/EncoderOutputCache.kt` | `EncoderOutputCache` | LRU cache for encoder memory |

## Current Implementation

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

## Current Limitations

| Limitation | Impact |
|------------|--------|
| No OnnxTensor pooling | Native memory allocated every inference |
| No encoder output caching | Memory tensor recreated for same swipe |
| No buffer size tracking | Can't monitor or limit memory usage |
| No LRU eviction | Buffers grow but never shrink |
| Thread-unsafe | Problematic for async prediction |
| No warm-up | First prediction slower due to lazy allocation |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     TensorPool                               │
│  Pools OnnxTensor wrappers with reusable backing buffers    │
│  - IntBuffer tensors for token sequences                    │
│  - FloatBuffer tensors for features                         │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                  EncoderOutputCache                          │
│  LRU cache of encoder outputs keyed by trajectory hash      │
│  - Avoids re-encoding same swipe on multi-tap/correction    │
│  - Max 4 entries, 5-second staleness eviction               │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    MemoryBudget                              │
│  Tracks allocations and enforces limits                     │
│  - Prevents OOM on low-memory devices                       │
│  - Provides usage metrics for debugging                     │
└─────────────────────────────────────────────────────────────┘
```

## Implementation Details

### OnnxTensor Pooling

```kotlin
class TensorPool(private val ortEnv: OrtEnvironment) {
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

### Encoder Output Caching

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
    fun evictStale(maxAgeMs: Long = 5000L)
}
```

### Memory Budget Tracking

```kotlin
class MemoryBudget {
    private val currentUsageBytes = AtomicLong(0)
    private val maxBudgetBytes: Long

    fun allocate(bytes: Long): Boolean {
        if (currentUsageBytes.get() + bytes > maxBudgetBytes) {
            Log.w(TAG, "Memory budget exceeded")
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

### Thread-Safe Pool

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

### Warm-Up Protocol

```kotlin
class MemoryPoolWarmup {
    suspend fun warmup(pool: MemoryPool, config: NeuralConfig) {
        // Pre-allocate all pools
        pool.initializePreallocatedBuffers(
            maxBeams = config.beamWidth,
            decoderSeqLength = 20,
            vocabSize = 35
        )

        // Touch pages to avoid page faults on first use
        val buffers = pool.getPreallocBatchedTokens()
        buffers.forEach { arr -> arr.fill(0) }
    }
}
```

## Performance Targets

| Metric | Target |
|--------|--------|
| Allocation reduction | ≥50% per prediction |
| GC pause during typing | Reduced |
| Memory usage | Tracked and bounded |
| Memory leaks | None after 1000+ predictions |
| Thread safety | Verified |
| First prediction latency | ≥30% reduction |
