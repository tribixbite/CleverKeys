# ONNX Model Placeholder

## Missing Models:
- `swipe_encoder.onnx` - Transformer encoder (trajectory → memory states)
- `swipe_decoder.onnx` - Transformer decoder (memory → word predictions)

## Model Requirements:

### Encoder Model:
**Input:**
- `trajectory_features`: [1, 100, 6] float32 (x,y,vx,vy,ax,ay)
- `nearest_keys`: [1, 100] int64 (nearest key indices)  
- `src_mask`: [1, 100] bool (padding mask)

**Output:**
- `memory`: [1, seq_len, hidden_dim] float32 (memory states for decoder)

### Decoder Model:
**Input:**
- `memory`: [1, seq_len, hidden_dim] float32 (from encoder)
- `target_tokens`: [1, 20] int64 (target sequence)
- `target_mask`: [1, 20] bool (target padding mask)
- `src_mask`: [1, seq_len] bool (source padding mask)

**Output:**
- `logits`: [1, 20, 41] float32 (vocabulary probabilities)

## System Behavior:
Without models, the system will throw runtime exceptions during prediction.
This is intentional - no fallback systems are provided.

## Model Training:
Use the web demo training pipeline to generate these models and convert to ONNX format.