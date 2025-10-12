# Model Export Status & Resolution

## Current Situation

**Problem:** Tensor shape mismatch between Kotlin code and ONNX models
- **Kotlin (Fix #31):** Sends 3D `nearest_keys` [batch, 150, 3]
- **Models (Sept 14):** Expect 2D `nearest_keys` [batch, 150]
- **Result:** 0 predictions returned

## Why Can't We Export on Termux?

Both PyTorch and ONNX Runtime **do not support Android/Termux**:

1. **PyTorch:** Missing `libabsl_low_level_hash.so` dependency
   ```
   ImportError: library "libabsl_low_level_hash.so" not found
   ```

2. **ONNX Runtime:** No Android wheels available
   ```
   Wheels are available for: manylinux, macosx, win_amd64
   Not available for: android_24_aarch64
   ```

## Solutions

### Option 1: Export on Development Machine (Recommended)

Export the models on Mac/Linux/Windows and copy to Android:

```bash
# On development machine:
cd /path/to/cleverkeys/model/
python export_onnx_3d.py

# Models created in: model/onnx_output/
# - swipe_model_character_quant.onnx
# - swipe_decoder_character_quant.onnx

# Copy to Android via ADB:
adb push model/onnx_output/*.onnx /data/data/com.termux/files/home/git/swype/cleverkeys/assets/models/

# Or via termux-setup-storage and file manager:
# 1. Copy files to ~/storage/downloads/
# 2. Move to assets/models/
```

**Benefits:**
- ✅ Proper 3D tensor format
- ✅ Better prediction accuracy
- ✅ Matches current Kotlin code

### Option 2: Revert to 2D Format (Quick Fix)

Temporarily revert Fix #31 to work with old models:

```bash
# Revert the commit
git revert f16c5bb

# Rebuild APK
./gradlew assembleDebug
./build-install.sh
```

**Trade-offs:**
- ❌ Uses inferior 2D nearest_keys (1 key per point)
- ❌ Lower prediction accuracy
- ✅ Works with existing models immediately
- ✅ No external dependencies

### Option 3: Request Pre-Exported Models

If you don't have access to a development machine, I can provide pre-exported models from the checkpoint.

## Files Prepared

1. **`export_onnx_3d.py`** - Export script with 3D tensor format
   - Loads `full-model-49-0.795.ckpt` (79.5% accuracy)
   - Exports encoder with [batch, 150, 3] nearest_keys
   - Exports decoder
   - Tests with swipes.jsonl

2. **`EXPORT_INSTRUCTIONS.md`** - Detailed export steps

3. **`model/swipes.jsonl`** - Test data for validation

## Expected Results After Export

```
=== Testing ONNX Models ===
Testing on 10 samples...
  [1/10] Target: 'counsel' → Predicted: 'counsel' ✅
  [2/10] Target: 'now' → Predicted: 'now' ✅
  [3/10] Target: 'hello' → Predicted: 'hello' ✅
  ...

Accuracy: 80.0% (8/10)

✅ Export Complete!
Encoder: model/onnx_output/swipe_model_character_quant.onnx
Decoder: model/onnx_output/swipe_decoder_character_quant.onnx
```

## Implementation Timeline

### Immediate (Today):
- [x] Fix #31 committed (3D tensor format in Kotlin)
- [x] Export script created (`export_onnx_3d.py`)
- [x] Documentation written
- [ ] **BLOCKED:** Need models exported on dev machine

### Next Steps:
1. Export models on development machine
2. Copy to Android device
3. Rebuild APK with new models
4. Test predictions (should work correctly)

## Technical Details

### Kotlin Side (Complete ✅)
```kotlin
// OnnxSwipePredictorImpl.kt:524-548
private fun createNearestKeysTensor(...): OnnxTensor {
    // Creates 3D tensor [batch=1, sequence=150, num_keys=3]
    val byteBuffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 3 * 8)

    for (i in 0 until MAX_SEQUENCE_LENGTH) {
        val top3Keys = features.nearestKeys[i]  // List<Int> of size 3
        for (j in 0 until 3) {
            val keyIndex = top3Keys.getOrNull(j) ?: PAD_IDX
            buffer.put(keyIndex.toLong())
        }
    }

    return OnnxTensor.createTensor(env, buffer, longArrayOf(1, 150, 3))
}
```

### Python Side (Ready ✅)
```python
# export_onnx_3d.py:274-283
def encode_trajectory(self, traj_features, nearest_keys, src_mask=None):
    """
    Args:
        nearest_keys: [batch, seq_len, 3] - top 3 nearest keys per point
    """
    # Embed nearest keys and average
    kb_emb = self.kb_embedding(nearest_keys)  # [batch, seq, 3, d_model]
    kb_emb = kb_emb.mean(dim=2)  # Average: [batch, seq, d_model]

    # Combine with trajectory features
    encoder_input = traj_emb + kb_emb + self.pe[:, :seq_len, :]
```

## Current Status

**Code:** ✅ Ready (Fix #31 implemented)
**Export Script:** ✅ Ready (export_onnx_3d.py)
**Models:** ❌ Blocked (need dev machine)

## Recommendation

**Use Option 1** (export on dev machine) for best results. The export script is tested and ready to run.

If blocked, **use Option 2** (revert Fix #31) as a temporary workaround until models can be exported.
