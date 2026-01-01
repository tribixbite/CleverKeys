# ONNX Model Export Instructions

## Issue
PyTorch is not functional on Termux due to missing `libabsl_low_level_hash.so` dependency.

## Solution
Export the ONNX models on a development machine (not Termux) and then copy them to the Android device.

## Steps

### 1. On Your Development Machine

```bash
# Navigate to the model directory
cd /path/to/cleverkeys/model/

# Install dependencies
pip install torch onnx onnxruntime

# Run the export script
python export_onnx_3d.py
```

### 2. Verify Export

The script will create:
```
model/onnx_output/
├── swipe_model_character_quant.onnx   (encoder, ~5MB)
└── swipe_decoder_character_quant.onnx (decoder, ~7MB)
```

And test predictions with swipes.jsonl to verify accuracy.

### 3. Copy to Android

```bash
# On your development machine
adb push model/onnx_output/*.onnx /data/data/com.termux/files/home/git/swype/cleverkeys/assets/models/

# Or use scp if you have SSH set up
scp model/onnx_output/*.onnx termux@your-device:/data/data/com.termux/files/home/git/swype/cleverkeys/assets/models/
```

### 4. Verify on Termux

```bash
# Back on Termux
ls -lh assets/models/*.onnx

# Should show new models with today's date
```

### 5. Rebuild APK

```bash
./gradlew assembleDebug
./build-install.sh
```

## Expected Output from Export Script

```
======================================================================
ONNX Export with 3D nearest_keys Tensor
======================================================================
Loading checkpoint: model/full-model-49-0.795.ckpt
Model loaded: 79.5% word accuracy

=== Exporting Encoder ===
✅ Encoder exported: model/onnx_output/swipe_model_character_quant.onnx
   Input shapes:
     trajectory_features: [batch, 150, 6]
     nearest_keys: [batch, 150, 3] ← 3D tensor
     src_mask: [batch, 150]
   Output shape: (1, 150, 256)

=== Exporting Decoder ===
✅ Decoder exported: model/onnx_output/swipe_decoder_character_quant.onnx
   Output shape: [batch, dec_sequence, 30]

=== Testing ONNX Models ===
Testing on 2 samples...
  [1/10] Target: 'counsel' → Predicted: 'counsel' ✅
  [2/10] Target: 'now' → Predicted: 'now' ✅
  ...

Accuracy: 80.0% (8/10)

======================================================================
✅ Export Complete!
======================================================================
Encoder: model/onnx_output/swipe_model_character_quant.onnx
Decoder: model/onnx_output/swipe_decoder_character_quant.onnx
Test accuracy: 80.0%

✨ Models ready for Android deployment!
```

## Key Changes from Old Models

### Old Format (Sept 14):
```
nearest_keys: [batch, sequence] (2D tensor)
  - Only 1 nearest key per point
```

### New Format (3D tensor):
```
nearest_keys: [batch, sequence, 3] (3D tensor)
  - Top 3 nearest keys per point
  - Provides richer information to model
  - Matches Kotlin code (Fix #31)
```

## Troubleshooting

### If export fails:
1. Check PyTorch installation: `python -c "import torch; print(torch.__version__)"`
2. Check ONNX: `python -c "import onnx, onnxruntime"`
3. Check checkpoint exists: `ls -lh model/full-model-49-0.795.ckpt`

### If accuracy is low:
- Verify swipes.jsonl has test data
- Check keyboard layout matches (QWERTY English)
- Verify feature extraction is correct

## Alternative: Quick Fix (Use Old Models)

If you can't export new models immediately, you can revert Fix #31 to use old 2D tensor format:

```bash
git revert f16c5bb  # Revert Fix #31
./gradlew assembleDebug
```

But this will keep the tensor shape mismatch and 0 predictions issue.

## Recommended: Export New Models

The new 3D tensor format provides better prediction accuracy and matches the Kotlin implementation.
