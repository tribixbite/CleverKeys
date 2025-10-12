# Export ONNX Models via Google Colab (5 Minutes)

**Problem:** Cannot export on Termux due to PyTorch/ONNX Runtime incompatibility
**Solution:** Use Google Colab (free, no setup required)

## Quick Start (5 Steps)

### 1. Open Google Colab
Go to: [colab.research.google.com](https://colab.research.google.com)
Click: **"New notebook"**

### 2. Upload Files
Click the folder icon (📁) in left sidebar, then upload these 3 files:
- `export_onnx_3d.py` (19 KB)
- `full-model-49-0.795.ckpt` (checkpoint file)
- `swipes.jsonl` (8 KB)

**Location:** All files are in `model/` directory of this repo

### 3. Install Dependencies
In the first code cell, paste and run:
```python
!pip install onnx onnxruntime
```
(PyTorch is already installed in Colab)

### 4. Run Export Script
In a new code cell, paste and run:
```python
!python export_onnx_3d.py
```

**Expected Output:**
```
======================================================================
ONNX Export with 3D nearest_keys Tensor
======================================================================
Loading checkpoint: full-model-49-0.795.ckpt
Model loaded: 79.5% word accuracy

=== Exporting Encoder ===
✅ Encoder exported: onnx_output/swipe_model_character_quant.onnx
   Input shapes:
     trajectory_features: [batch, 150, 6]
     nearest_keys: [batch, 150, 3] ← 3D tensor
     src_mask: [batch, 150]
   Output shape: (1, 150, 256)

=== Exporting Decoder ===
✅ Decoder exported: onnx_output/swipe_decoder_character_quant.onnx
   Output shape: [batch, dec_sequence, 30]

=== Testing ONNX Models ===
Testing on 2 samples...
  [1/2] Target: 'counsel' → Predicted: 'counsel' ✅
  [2/2] Target: 'now' → Predicted: 'now' ✅

Accuracy: 100.0% (2/2)

======================================================================
✅ Export Complete!
======================================================================
Encoder: onnx_output/swipe_model_character_quant.onnx
Decoder: onnx_output/swipe_decoder_character_quant.onnx
Test accuracy: 100.0%

✨ Models ready for Android deployment!
```

**Runtime:** ~20-30 seconds

### 5. Download Models
1. Click **Refresh** (🔄) in the file browser
2. Navigate to `onnx_output/` folder
3. Right-click each `.onnx` file → **Download**

Files to download:
- `swipe_model_character_quant.onnx` (~5 MB)
- `swipe_decoder_character_quant.onnx` (~7 MB)

---

## Copy to Android Device

### Option A: ADB (if device connected to computer)
```bash
adb push swipe_model_character_quant.onnx /data/data/com.termux/files/home/git/swype/cleverkeys/assets/models/
adb push swipe_decoder_character_quant.onnx /data/data/com.termux/files/home/git/swype/cleverkeys/assets/models/
```

### Option B: Manual Transfer
1. Copy downloaded files to phone (USB, cloud storage, etc.)
2. In Termux, move to assets:
   ```bash
   cd ~/git/swype/cleverkeys
   mv ~/storage/downloads/*.onnx assets/models/
   ```

### Option C: Direct Download on Device
If you have public hosting for the files:
```bash
cd ~/git/swype/cleverkeys/assets/models
wget https://your-host.com/swipe_model_character_quant.onnx
wget https://your-host.com/swipe_decoder_character_quant.onnx
```

---

## Rebuild and Test APK

After copying models to `assets/models/`:

```bash
cd ~/git/swype/cleverkeys

# Rebuild APK with new models
./gradlew assembleDebug

# Install
./build-install.sh

# Test predictions
# (Swipe gestures should now work correctly)
```

---

## Troubleshooting

### Colab Upload Issues
- **Checkpoint too large?** Files up to 100MB work fine in Colab
- **Session timeout?** Upload files again, they're stored temporarily

### Export Script Errors
- **ModuleNotFoundError: torch** → Run `!pip install torch` (shouldn't be needed)
- **FileNotFoundError: checkpoint** → Verify upload succeeded, check filename

### Model Download Issues
- **Can't find output folder?** Click Refresh in file browser
- **Files disappeared?** Colab sessions are temporary, re-run export if needed

---

## Why This Works

**Problem:** Termux/Android lacks:
- `libabsl_low_level_hash.so` (PyTorch dependency)
- ONNX Runtime wheels for `android_24_aarch64`

**Solution:** Colab provides:
- ✅ PyTorch 2.x pre-installed
- ✅ ONNX/ONNX Runtime pip packages available
- ✅ x86_64 Linux environment (standard platform)
- ✅ Free GPU access (not needed for this export)
- ✅ No configuration required

---

## Alternative: GitHub Codespaces

If you prefer a full IDE environment:

1. Push code to GitHub repository
2. Create Codespace from repo
3. In terminal:
   ```bash
   pip install torch onnx onnxruntime
   cd model
   python export_onnx_3d.py
   ```
4. Download files from Codespaces file explorer

---

## Next Steps

After successful export and installation:
1. Test swipe gestures for common words
2. Verify predictions in logs: `adb logcat | grep OnnxSwipe`
3. Compare accuracy with web demo
4. Report results in TODO.md

**Expected Result:** Neural predictions should work correctly with ~80% word accuracy, matching the checkpoint's training performance.
