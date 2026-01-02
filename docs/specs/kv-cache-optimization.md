# KV Cache Optimization for Neural Decoder

**Feature**: Key-Value Cache for Transformer Decoder Inference
**Status**: 🟡 PLANNED (Medium Priority Optimization)
**Priority**: P2 (Performance Enhancement)
**Date Created**: 2026-01-02
**Last Updated**: 2026-01-02

---

## 1. Overview

### Problem Statement

The current BeamSearchEngine runs a **full decoder forward pass** for each decoding step. For a beam width of 8 and max sequence length of 20, this means:

- **Current**: `8 beams × 20 steps × full_sequence_attention` = O(n³) operations
- **With KV Cache**: `8 beams × 20 steps × single_token_attention` = O(n²) operations

At each step, the decoder recomputes attention over the entire target sequence, even though previous positions haven't changed. This is wasteful because transformer attention is computed pairwise—positions already decoded don't need recomputation.

### Solution: KV Caching

Cache the **Key** and **Value** projections from:
1. **Decoder self-attention layers**: Grows incrementally per step
2. **Encoder-decoder cross-attention**: Computed once, reused every step

This reduces per-step decoder inference from O(seq_len²) to O(seq_len), yielding **30-50% latency reduction** in typical use.

### Expected Benefits

| Metric | Current | With KV Cache | Improvement |
|--------|---------|---------------|-------------|
| Decoder step time | ~3-5ms | ~1-2ms | 50-60% |
| Total beam search (20 steps) | ~60-100ms | ~30-50ms | 40-50% |
| Memory overhead | None | ~2MB per beam | Acceptable tradeoff |

---

## 2. Technical Design

### 2.1 Architecture Overview

```
CURRENT FLOW (No Cache):
┌─────────────────────────────────────────────────────────────┐
│ Step 1: target=[SOS]                                         │
│   → Full forward pass over 1 token                          │
│   → Compute K,V for position 0                              │
├─────────────────────────────────────────────────────────────┤
│ Step 2: target=[SOS, 'h']                                    │
│   → Full forward pass over 2 tokens                         │
│   → RECOMPUTE K,V for position 0 (wasteful!)               │
│   → Compute K,V for position 1                              │
├─────────────────────────────────────────────────────────────┤
│ Step N: target=[SOS, 'h', 'e', 'l', 'l', 'o']               │
│   → Full forward pass over N tokens                         │
│   → RECOMPUTE K,V for positions 0..N-1 (wasteful!)         │
└─────────────────────────────────────────────────────────────┘

WITH KV CACHE:
┌─────────────────────────────────────────────────────────────┐
│ Step 1: target=[SOS], cache=empty                           │
│   → Forward pass over 1 token                               │
│   → Store K,V for position 0 in cache                       │
├─────────────────────────────────────────────────────────────┤
│ Step 2: target=['h'] (only new token!), cache=[K0,V0]       │
│   → Forward pass over 1 token only                          │
│   → Use cached K,V for position 0                           │
│   → Compute and cache K,V for position 1                    │
├─────────────────────────────────────────────────────────────┤
│ Step N: target=[new_token], cache=[K0..N-1, V0..N-1]        │
│   → Forward pass over 1 token only                          │
│   → Attend to all cached K,V positions                      │
│   → Append new K,V to cache                                 │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Model Export Changes

The ONNX model must be exported with KV cache support. This requires changes to `model/export_character_model.py`:

#### Current Decoder Export

```python
# Current: Simple forward pass
class CharacterDecoder(nn.Module):
    def forward(self, memory, target_tokens):
        # Full attention over all target positions
        output = self.decoder(target_tokens, memory)
        return self.output_projection(output)
```

#### Modified Decoder Export (KV Cache)

```python
class CharacterDecoderWithCache(nn.Module):
    def forward(
        self,
        memory,                    # [batch, src_len, d_model] - encoder output
        target_token,              # [batch, 1] - SINGLE new token only
        past_self_attn_k,          # [batch, n_layers, n_heads, past_len, head_dim]
        past_self_attn_v,          # [batch, n_layers, n_heads, past_len, head_dim]
        past_cross_attn_k,         # [batch, n_layers, n_heads, src_len, head_dim]
        past_cross_attn_v,         # [batch, n_layers, n_heads, src_len, head_dim]
    ):
        """
        Returns:
            logits: [batch, 1, vocab_size]
            new_self_attn_k: [batch, n_layers, n_heads, past_len+1, head_dim]
            new_self_attn_v: [batch, n_layers, n_heads, past_len+1, head_dim]
            cross_attn_k: Same as input (passed through for convenience)
            cross_attn_v: Same as input (passed through for convenience)
        """
        # Implementation details below
```

### 2.3 Cache Data Structures

```kotlin
/**
 * KV Cache state for a single beam during decoding.
 *
 * Architecture constants (from model config):
 * - d_model = 256
 * - n_decoder_layers = 4
 * - n_heads = 8
 * - head_dim = d_model / n_heads = 32
 * - max_seq_len = 20
 */
data class DecoderKVCache(
    // Self-attention cache: grows each step
    // Shape: [n_layers, n_heads, seq_len, head_dim] = [4, 8, <=20, 32]
    val selfAttnKeys: Array<Array<Array<FloatArray>>>,   // [4][8][seq][32]
    val selfAttnValues: Array<Array<Array<FloatArray>>>, // [4][8][seq][32]

    // Cross-attention cache: computed once from encoder memory
    // Shape: [n_layers, n_heads, src_len, head_dim] = [4, 8, 150, 32]
    val crossAttnKeys: Array<Array<Array<FloatArray>>>,  // [4][8][150][32]
    val crossAttnValues: Array<Array<Array<FloatArray>>> // [4][8][150][32]
) {
    companion object {
        const val N_LAYERS = 4
        const val N_HEADS = 8
        const val HEAD_DIM = 32
        const val SRC_LEN = 150
        const val MAX_TGT_LEN = 20

        /**
         * Memory estimate per beam:
         * Self-attention: 4 * 8 * 20 * 32 * 4 bytes * 2 (K+V) = 163 KB
         * Cross-attention: 4 * 8 * 150 * 32 * 4 bytes * 2 (K+V) = 1.23 MB
         * Total per beam: ~1.4 MB
         * For beam_width=8: ~11.2 MB total
         */
        fun estimateMemoryBytes(beamWidth: Int, currentSeqLen: Int): Long {
            val selfAttnBytes = N_LAYERS * N_HEADS * currentSeqLen * HEAD_DIM * 4L * 2
            val crossAttnBytes = N_LAYERS * N_HEADS * SRC_LEN * HEAD_DIM * 4L * 2
            return beamWidth * (selfAttnBytes + crossAttnBytes)
        }
    }

    /**
     * Create empty cache for new decoding session.
     */
    fun createEmpty(): DecoderKVCache {
        return DecoderKVCache(
            selfAttnKeys = Array(N_LAYERS) { Array(N_HEADS) { emptyArray() } },
            selfAttnValues = Array(N_LAYERS) { Array(N_HEADS) { emptyArray() } },
            crossAttnKeys = Array(N_LAYERS) { Array(N_HEADS) { emptyArray() } },
            crossAttnValues = Array(N_LAYERS) { Array(N_HEADS) { emptyArray() } }
        )
    }

    /**
     * Append new K,V to self-attention cache after a decoding step.
     */
    fun appendSelfAttention(
        newKeys: Array<Array<FloatArray>>,   // [n_layers][n_heads][head_dim]
        newValues: Array<Array<FloatArray>>  // [n_layers][n_heads][head_dim]
    ): DecoderKVCache {
        // Returns new cache with appended K,V
    }
}
```

### 2.4 Modified Beam Search Flow

```kotlin
class BeamSearchEngineWithCache(
    // ... existing parameters
    private val decoderWithCacheSession: OrtSession, // New model with cache inputs
) {

    fun searchWithCache(memory: OnnxTensor, actualSrcLength: Int): List<BeamSearchCandidate> {
        // Step 0: Compute cross-attention cache once
        val crossAttnCache = computeCrossAttentionCache(memory)

        // Initialize beams with empty self-attention cache
        val beams = mutableListOf(
            BeamStateWithCache(
                tokens = arrayListOf(SOS_IDX.toLong()),
                score = 0.0f,
                finished = false,
                selfAttnCache = DecoderKVCache.createEmpty(),
                crossAttnCache = crossAttnCache
            )
        )

        for (step in 0 until maxLength) {
            val activeBeams = beams.filter { !it.finished }
            if (activeBeams.isEmpty()) break

            val candidates = mutableListOf<BeamStateWithCache>()

            for (beam in activeBeams) {
                // Only pass the LATEST token, not full sequence!
                val latestToken = beam.tokens.last().toInt()

                // Run decoder with cache
                val (logits, newSelfAttnK, newSelfAttnV) = runDecoderWithCache(
                    memory = memory,
                    token = latestToken,
                    selfAttnKeys = beam.selfAttnCache.selfAttnKeys,
                    selfAttnValues = beam.selfAttnCache.selfAttnValues,
                    crossAttnKeys = beam.crossAttnCache.crossAttnKeys,
                    crossAttnValues = beam.crossAttnCache.crossAttnValues
                )

                // Update cache with new K,V
                val updatedCache = beam.selfAttnCache.appendSelfAttention(newSelfAttnK, newSelfAttnV)

                // Expand beam with top-k tokens
                val logProbs = logSoftmax(logits)
                val topIndices = getTopKIndices(logProbs, beamWidth)

                for (idx in topIndices) {
                    if (idx == EOS_IDX) {
                        candidates.add(beam.copyWithNewToken(idx, logProbs[idx], finished=true, updatedCache))
                    } else {
                        candidates.add(beam.copyWithNewToken(idx, logProbs[idx], finished=false, updatedCache))
                    }
                }
            }

            // Rank and prune (same as current implementation)
            // ...
        }

        return beams.mapNotNull { convertToCandidate(it) }
    }
}
```

---

## 3. Implementation Plan

### Phase 1: Model Export Modifications (Python)

**Time Estimate: 4-6 hours**

#### Task 1.1: Modify Decoder Architecture

```bash
# File: model/export_character_model.py
```

1. Create `CharacterDecoderWithCache` class
2. Modify attention layers to accept/return KV states
3. Add proper tensor shape handling for incremental decoding
4. Export new ONNX model: `swipe_decoder_with_cache.onnx`

#### Task 1.2: Export Script Updates

1. Add command-line flag: `--enable-kv-cache`
2. Export both models (with and without cache) for A/B testing
3. Verify ONNX model shapes match expected inputs

#### Task 1.3: Test Python Export

```python
# Verify shapes
import onnxruntime as ort
sess = ort.InferenceSession("swipe_decoder_with_cache.onnx")
for inp in sess.get_inputs():
    print(f"{inp.name}: {inp.shape}")
# Expected:
# memory: [batch, 150, 256]
# target_token: [batch, 1]
# past_self_attn_k: [batch, 4, 8, ?, 32]  # ? = dynamic past length
# past_self_attn_v: [batch, 4, 8, ?, 32]
# past_cross_attn_k: [batch, 4, 8, 150, 32]
# past_cross_attn_v: [batch, 4, 8, 150, 32]
```

### Phase 2: Android Runtime Implementation (Kotlin)

**Time Estimate: 6-8 hours**

#### Task 2.1: Create DecoderKVCache Class

```bash
# File: src/main/kotlin/tribixbite/cleverkeys/onnx/DecoderKVCache.kt
```

1. Implement data class with cache arrays
2. Add memory estimation methods
3. Add append/copy operations for beam branching

#### Task 2.2: Create BeamSearchEngineWithCache

```bash
# File: src/main/kotlin/tribixbite/cleverkeys/onnx/BeamSearchEngineWithCache.kt
```

1. Copy existing BeamSearchEngine as base
2. Modify BeamState to include cache reference
3. Implement `runDecoderWithCache()` method
4. Handle ONNX tensor creation for cache inputs
5. Manage cache lifecycle (allocation, copying for beam splits)

#### Task 2.3: Integration with OnnxSwipePredictor

```bash
# File: src/main/kotlin/tribixbite/cleverkeys/onnx/OnnxSwipePredictorImpl.kt
```

1. Add config flag: `neural_use_kv_cache: Boolean`
2. Load either cached or non-cached decoder model
3. Route to appropriate beam search engine
4. Add fallback to non-cached if memory pressure detected

### Phase 3: Testing & Optimization

**Time Estimate: 4-6 hours**

#### Task 3.1: Unit Tests

```bash
# File: src/test/kotlin/tribixbite/cleverkeys/onnx/DecoderKVCacheTest.kt
```

1. Test cache append operations
2. Test memory estimation accuracy
3. Test beam branching with cache copying
4. Verify numerical equivalence with non-cached decoder

#### Task 3.2: Integration Tests

1. Compare predictions: cached vs non-cached (must match exactly)
2. Benchmark latency improvements
3. Profile memory usage on various devices
4. Test edge cases: max length, early termination, all beams finish

#### Task 3.3: Performance Profiling

```kotlin
// Add timing instrumentation
val startCache = System.nanoTime()
val result = searchWithCache(memory, srcLen)
val cacheTime = (System.nanoTime() - startCache) / 1_000_000

val startNoCache = System.nanoTime()
val resultNoCache = search(memory, srcLen)
val noCacheTime = (System.nanoTime() - startNoCache) / 1_000_000

Log.d("KVCache", "With cache: ${cacheTime}ms, Without: ${noCacheTime}ms")
```

### Phase 4: Configuration & UI

**Time Estimate: 2-3 hours**

#### Task 4.1: Add Config Options

```kotlin
// Config.kt additions
object Defaults {
    const val NEURAL_USE_KV_CACHE = true  // Enable by default when stable
}

// Runtime config
@JvmField var neural_use_kv_cache = false
```

#### Task 4.2: UI Toggle (Optional)

Add to NeuralSettingsActivity under "Advanced" section:
- Switch: "Use KV Cache"
- Description: "Cache decoder attention for faster inference. May use more memory."

---

## 4. Risks & Mitigations

### Risk 1: Memory Pressure

**Risk**: KV cache adds ~11MB per prediction for beam_width=8
**Mitigation**:
- Monitor available memory before enabling
- Add config to disable on low-memory devices
- Pool and reuse cache buffers across predictions

### Risk 2: Numerical Divergence

**Risk**: Floating-point differences between cached and non-cached paths
**Mitigation**:
- Extensive unit tests comparing outputs
- Use same precision (FP32) for cache storage
- Validate first 100 predictions match exactly

### Risk 3: Model Export Complexity

**Risk**: ONNX dynamic shapes for past_length are tricky
**Mitigation**:
- Use ONNX opset >= 13 for better dynamic shape support
- Test export on multiple input lengths
- Fall back to non-cached model if export fails

### Risk 4: Beam Branching Memory

**Risk**: Each beam split requires cache copy
**Mitigation**:
- Use copy-on-write semantics where possible
- Consider shared encoder cross-attention cache (computed once)
- Profile actual memory patterns

---

## 5. Success Criteria

- [ ] Decoder with KV cache exports successfully to ONNX
- [ ] Predictions match non-cached decoder exactly (numerical equivalence)
- [ ] Latency reduced by ≥30% on target devices
- [ ] Memory overhead ≤15MB for beam_width=8
- [ ] All existing tests pass
- [ ] No regression in prediction accuracy

---

## 6. References

- [HuggingFace KV Cache Implementation](https://huggingface.co/docs/transformers/kv_cache)
- [ONNX Dynamic Shapes](https://onnxruntime.ai/docs/tutorials/dynamic-shape.html)
- [Efficient Inference with KV Cache](https://arxiv.org/abs/2211.05102)
- CleverKeys Neural Prediction Spec: `docs/specs/neural-prediction.md`

---

*— Opus 4.5*
