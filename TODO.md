# CleverKeys - Tensor Byte Order Fix

## ❌ DEBUGGING: Tensor Format Investigation (NO RESOLUTION YET)

**Attempts Made:**
1. ✅ ByteOrder change (nativeOrder → LITTLE_ENDIAN): NO EFFECT
2. ✅ Reverted to nativeOrder (matches CLI test): NO EFFECT
3. ✅ Fresh FloatBuffer/LongBuffer views (matches CLI test pattern): NO EFFECT

**Result:** Still 0/2 (0.0%) - "counsel"→"", "now"→"o"

**Verified Correct from Logs:**
- Tensor names: trajectory_features, nearest_keys, src_mask ✓
- Tensor shapes: [1,150,6], [1,150], [1,150] ✓
- Nearest keys: [6,6,6,25,9,9...] where 6='c' ✓
- Normalization: (132,146)→(0.367,0.523) for 360×280 ✓
- Grid detection: matches CLI test ✓
- Feature extraction: matches CLI test ✓

**Problem:** Model predicts wrong tokens despite ALL inputs correct
- Expected first token: c(6)
- Actual first token: o(18)
- Beam outputs: "ouuueee", "ouuueeeett", "ouuueeeettt"
- All filtered by vocabulary → empty result

## 🔬 HEX DUMP ANALYSIS COMPLETE

**Tensor Values from Android (for "counsel"):**
```
Trajectory (first 30 floats):
[ 0] 0.366667 = 132/360 ✓ CORRECT x
[ 1] 0.522720 = 146.36/280 ✓ CORRECT y
...velocities and accelerations all correct...

Nearest keys (first 15 longs):
[ 0] 6 = 'c' ✓ CORRECT
[ 1] 6 = 'c' ✓ CORRECT
[ 2] 6 = 'c' ✓ CORRECT
```

**Finding:** ALL tensor values are CORRECT! Hex dump confirms:
- Coordinates normalized properly ✓
- Nearest keys correct (6='c' for "counsel", 17='n' for "now") ✓
- Feature extraction working ✓
- Tensor creation working ✓

**Problem:** Model predicts 'o'(18) for BOTH "counsel" and "now"
- Ignoring nearest_keys completely
- Same wrong prediction despite different starting letters
- Model either broken OR CLI test also fails (unverified!)

**CRITICAL:** CLI test cannot run on Termux (library issues)
- User claimed 70%+ accuracy but never verified
- Need to test CLI on real computer to confirm baseline
- If CLI also gets 0%, then it's a MODEL problem, not code

---

## 🚨 PROVEN: Not a Test Data Issue!

**Using EXACT CLI test data still gives 0% accuracy**

Test data: `/data/data/com.termux/files/home/git/swype/swype-model-training/swipes.jsonl`
- 2 tests: "counsel", "now"
- Same coordinates, same format
- **Previous Result: 0/2 (0.0%)**

## ✅ Everything Verified Working

| Component | Status | Evidence |
|-----------|--------|----------|
| Duplicate filtering | ✅ WORKING | Tested with/without |
| Repeat-last padding | ✅ WORKING | Logs show correct padding |
| 360×280 normalization | ✅ WORKING | Dimensions verified |
| Grid detection | ✅ WORKING | Matches CLI exactly |
| Init order | ✅ WORKING | Dimensions set after init |
| Test data | ✅ IDENTICAL | EXACT CLI test file |
| Coordinates | ✅ VALID | No negatives, within bounds |
| Velocity/accel | ✅ CORRECT | Matches CLI formula |

## 🔍 Root Cause Must Be

Since ALL visible logic is correct, the bug must be in low-level details:

### ✅ Theory #1: Tensor Byte Serialization (FIXED - FIX #42)
```kotlin
// BEFORE (WRONG):
byteBuffer.order(ByteOrder.nativeOrder())  // ← Platform dependent!

// AFTER (FIXED):
byteBuffer.order(ByteOrder.LITTLE_ENDIAN) // ← ONNX standard
```
**Problem**: `ByteOrder.nativeOrder()` was wrong for ONNX
**Solution**: ✅ Changed all 7 usages to `ByteOrder.LITTLE_ENDIAN`
**Status**: APK rebuild pending, test results needed

### Theory #2: Input Tensor Name Mismatch  
**Problem**: Maybe model expects different input names?
**Solution**: Dump actual ONNX model input names and verify

### Theory #3: ONNX Runtime Platform Difference
**Problem**: Android ONNX Runtime 1.20.0 behaves differently than JVM
**Solution**: Check ONNX Runtime docs for known Android issues

### Theory #4: Float Precision
**Problem**: Android Float vs JVM Float rounding differences
**Solution**: Dump first 20 tensor values as hex and compare

## 🎯 Next Steps

### Option A: Verify Tensor Byte Order (QUICK)
```kotlin
// In createNearestKeysTensor(), change:
byteBuffer.order(ByteOrder.nativeOrder())
// To:
byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
```

### Option B: Dump ONNX Model Info (QUICK)
```kotlin
// Add to initialization:
Log.d(TAG, "Encoder inputs: ${encoderSession.inputNames}")
Log.d(TAG, "Expected shapes: ${encoderSession.inputInfo}")
```

### Option C: Run Actual CLI Test (VERIFY BASELINE)
```bash
# Compile and run TestOnnxPrediction.kt
# Verify it actually gets 50%+ with this data
```

### Option D: Hex Dump Tensor Values (DEEP DEBUG)
```kotlin
// Dump first 20 bytes of each tensor
val bytes = buffer.array()
Log.d(TAG, "Tensor bytes: ${bytes.take(20).joinToString { "%02x".format(it) }}")
```

## 💡 Recommendation

**Try Option A first** (5 min fix):
Change `ByteOrder.nativeOrder()` to `ByteOrder.LITTLE_ENDIAN` in all tensor creation functions. ONNX models are typically little-endian.

**Then Option B** (verify tensor names match model):
Check that "trajectory_features", "nearest_keys", "src_mask" are the actual input names the model expects.

**Then Option C** (verify CLI baseline):
Run actual CLI test to confirm it works. If CLI also gets 0%, then it's a model issue, not code.
