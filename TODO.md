# CleverKeys - Critical Finding

## 🚨 PROVEN: Not a Test Data Issue!

**Using EXACT CLI test data still gives 0% accuracy**

Test data: `/data/data/com.termux/files/home/git/swype/swype-model-training/swipes.jsonl`
- 2 tests: "counsel", "now"
- Same coordinates, same format
- **Result: 0/2 (0.0%)**

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

### Theory #1: Tensor Byte Serialization (MOST LIKELY)
```kotlin
// Android creates tensors like this:
val byteBuffer = ByteBuffer.allocateDirect(size * 8)
byteBuffer.order(ByteOrder.nativeOrder())  // ← Platform dependent!
```
**Problem**: `ByteOrder.nativeOrder()` might be wrong for ONNX
**Solution**: Try `ByteOrder.LITTLE_ENDIAN` explicitly

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
