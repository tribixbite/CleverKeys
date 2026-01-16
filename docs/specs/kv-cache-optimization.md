# KV Cache Optimization

## Overview

Key-Value cache optimization for transformer decoder inference. Caches K and V projections from previous decoding steps to avoid recomputation, reducing per-step complexity from O(seq_len²) to O(seq_len).

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/onnx/DecoderKVCache.kt` | `DecoderKVCache` | Cache data structure |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/BeamSearchEngineWithCache.kt` | `searchWithCache()` | Cached beam search |
| `model/export_character_model.py` | `CharacterDecoderWithCache` | ONNX export with cache I/O |

## Architecture

```
WITHOUT KV CACHE (Current):
┌─────────────────────────────────────────────────────────────┐
│ Step 1: target=[SOS]                                        │
│   → Full forward pass over 1 token                          │
├─────────────────────────────────────────────────────────────┤
│ Step 2: target=[SOS, 'h']                                   │
│   → Full forward pass over 2 tokens                         │
│   → RECOMPUTE K,V for position 0 (wasteful!)                │
├─────────────────────────────────────────────────────────────┤
│ Step N: target=[SOS, 'h', 'e', 'l', 'l', 'o']               │
│   → Full forward pass over N tokens                         │
│   → RECOMPUTE K,V for positions 0..N-1 (wasteful!)          │
└─────────────────────────────────────────────────────────────┘

WITH KV CACHE:
┌─────────────────────────────────────────────────────────────┐
│ Step 1: target=[SOS], cache=empty                           │
│   → Forward pass over 1 token                               │
│   → Store K,V for position 0 in cache                       │
├─────────────────────────────────────────────────────────────┤
│ Step 2: target=['h'] (only new token!), cache=[K0,V0]       │
│   → Forward pass over 1 token only                          │
│   → Attend to cached K,V + compute new K,V                  │
├─────────────────────────────────────────────────────────────┤
│ Step N: target=[new_token], cache=[K0..N-1, V0..N-1]        │
│   → Forward pass over 1 token only                          │
│   → Append new K,V to cache                                 │
└─────────────────────────────────────────────────────────────┘
```

## Performance Targets

| Metric | Current | With KV Cache | Improvement |
|--------|---------|---------------|-------------|
| Decoder step time | ~3-5ms | ~1-2ms | 50-60% |
| Total beam search (20 steps) | ~60-100ms | ~30-50ms | 40-50% |
| Memory overhead | None | ~2MB per beam | Acceptable |

## Implementation Details

### Cache Data Structure

```kotlin
data class DecoderKVCache(
    // Self-attention cache: grows each step
    // Shape: [n_layers, n_heads, seq_len, head_dim] = [4, 8, <=20, 32]
    val selfAttnKeys: Array<Array<Array<FloatArray>>>,
    val selfAttnValues: Array<Array<Array<FloatArray>>>,

    // Cross-attention cache: computed once from encoder memory
    // Shape: [n_layers, n_heads, src_len, head_dim] = [4, 8, 150, 32]
    val crossAttnKeys: Array<Array<Array<FloatArray>>>,
    val crossAttnValues: Array<Array<Array<FloatArray>>>
) {
    companion object {
        const val N_LAYERS = 4
        const val N_HEADS = 8
        const val HEAD_DIM = 32
        const val SRC_LEN = 150
        const val MAX_TGT_LEN = 20
    }
}
```

### Memory Estimate

```
Self-attention: 4 × 8 × 20 × 32 × 4 bytes × 2 (K+V) = 163 KB
Cross-attention: 4 × 8 × 150 × 32 × 4 bytes × 2 (K+V) = 1.23 MB
Total per beam: ~1.4 MB
For beam_width=8: ~11.2 MB total
```

### Modified Beam Search Flow

```kotlin
fun searchWithCache(memory: OnnxTensor, actualSrcLength: Int): List<BeamSearchCandidate> {
    // Step 0: Compute cross-attention cache once
    val crossAttnCache = computeCrossAttentionCache(memory)

    // Initialize beams with empty self-attention cache
    val beams = mutableListOf(
        BeamStateWithCache(
            tokens = arrayListOf(SOS_IDX.toLong()),
            score = 0.0f,
            selfAttnCache = DecoderKVCache.createEmpty(),
            crossAttnCache = crossAttnCache
        )
    )

    for (step in 0 until maxLength) {
        for (beam in activeBeams) {
            // Only pass the LATEST token, not full sequence!
            val (logits, newK, newV) = runDecoderWithCache(
                token = beam.tokens.last(),
                selfAttnCache = beam.selfAttnCache,
                crossAttnCache = beam.crossAttnCache
            )

            // Update cache with new K,V
            val updatedCache = beam.selfAttnCache.appendSelfAttention(newK, newV)
            // ... expand beam with top-k tokens
        }
    }
}
```

### ONNX Model Export Changes

```python
class CharacterDecoderWithCache(nn.Module):
    def forward(
        self,
        memory,                    # [batch, src_len, d_model]
        target_token,              # [batch, 1] - SINGLE new token
        past_self_attn_k,          # [batch, n_layers, n_heads, past_len, head_dim]
        past_self_attn_v,          # [batch, n_layers, n_heads, past_len, head_dim]
        past_cross_attn_k,         # [batch, n_layers, n_heads, src_len, head_dim]
        past_cross_attn_v          # [batch, n_layers, n_heads, src_len, head_dim]
    ):
        """
        Returns:
            logits: [batch, 1, vocab_size]
            new_self_attn_k: [batch, n_layers, n_heads, past_len+1, head_dim]
            new_self_attn_v: [batch, n_layers, n_heads, past_len+1, head_dim]
        """
```

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| Enable KV Cache | `neural_use_kv_cache` | false | Master toggle |

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Memory pressure (~11MB for beam_width=8) | Monitor available memory, disable on low-memory devices |
| Numerical divergence | Unit tests comparing cached vs non-cached outputs |
| ONNX dynamic shapes | Use opset >= 13, test multiple input lengths |
| Beam branching memory | Share cross-attention cache, copy-on-write for self-attention |
