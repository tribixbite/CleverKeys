# ⚠️ ONNX Model Update Required

**Issue:** 0 predictions returned due to tensor shape mismatch

## Root Cause
- **Kotlin code (Fix #31)**: Now sends 3D `nearest_keys` tensor [1, 150, 3]
- **Current ONNX models**: Expect 2D `nearest_keys` tensor [1, 150]
- **Model files dated**: September 14 (pre-3D format)

## Symptoms
```
✅ Neural engine initialized successfully
🧠 Neural prediction completed in 398ms
   Predictions: 0 candidates ❌
```

ONNX Runtime throws shape mismatch errors (caught silently), resulting in empty predictions.

## Solution

### Option 1: Re-export ONNX Models (Recommended)

1. **Run new Python export script** (1121 lines you pasted):
   ```bash
   cd <training_directory>
   python export_onnx_3d.py  # or whatever the new export script is called
   ```

2. **Replace model files** in `assets/models/`:
   ```bash
   cp new_swipe_model_character_quant.onnx assets/models/
   cp new_swipe_decoder_character_quant.onnx assets/models/
   ```

3. **Verify model inputs** match new format:
   - `nearest_keys`: [batch, sequence, 3] (3 nearest keys per point)
   - All other inputs unchanged

4. **Rebuild APK**:
   ```bash
   ./gradlew assembleDebug
   ./build-install.sh
   ```

### Option 2: Revert Kotlin to 2D (Keep Old Models)

If you prefer to keep existing models, revert Fix #31:
```bash
git revert f16c5bb
./gradlew assembleDebug
```

## Verification

After updating models, test predictions should work:
```
✅ Neural engine initialized successfully
🧠 Neural prediction completed in 398ms
   Predictions: 5 candidates ["university", "universities", "university's", ...]
✅ Correct prediction
```

## Files to Update
- `assets/models/swipe_model_character_quant.onnx` (encoder)
- `assets/models/swipe_decoder_character_quant.onnx` (decoder)

## Model Requirements
The new export script MUST generate models that accept:
- `trajectory`: [1, 150, 6] (x, y, vx, vy, ax, ay)
- `nearest_keys`: [1, 150, 3] ← **3D tensor, 3 keys per point**
- `source_mask`: [1, 150] (boolean mask)

All other model architecture unchanged.
