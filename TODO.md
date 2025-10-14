# CleverKeys Development TODO

## 🎉 MILESTONE: Automated Testing Infrastructure Complete!

**TestActivity fully functional - can now iterate rapidly without manual testing:**
```bash
adb shell am start -n tribixbite.keyboard2.debug/tribixbite.keyboard2.TestActivity
adb logcat -d -s TEST:I  # View results
```

## ✅ All Pipeline Fixes Implemented & Verified

| Fix | Status | Verification |
|-----|--------|--------------|
| #35 | ✅ WORKING | Duplicate filtering - tested with/without, not the cause |
| #36 | ✅ WORKING | Repeat-last padding - logs show correct padding |
| #37 | ✅ WORKING | 360×280 normalization - dimensions verified |
| #39 | ✅ WORKING | CLI grid detection - staggered QWERTY implemented |
| #40 | ✅ WORKING | Init order - dimensions set after initialize() |
| #41 | ✅ WORKING | Tensor validation - all checks pass |

## ❌ CRITICAL: 0/10 Accuracy Despite All Fixes

**Current Test Results:**
```
[1/10] 'what' → 't' ❌ (nearest: w,w,w - correct!)
[2/10] 'boolean' → '' ❌ (empty - EOS first)
[3/10] 'not' → 't' ❌ (nearest: n,n - correct!)
[4-9] → '' ❌ (all empty)
[10/10] 'could' → 'o' ❌
Result: 0/10 (0.0%)
```

## 🔬 Systematic Analysis Complete

**VERIFIED IDENTICAL TO CLI TEST:**
- ✅ No duplicate filtering (tested both ways)
- ✅ Repeat-last padding for nearest_keys
- ✅ Repeat-last padding for coordinates
- ✅ 360×280 normalization dimensions
- ✅ Staggered QWERTY grid detection
- ✅ Velocity = curr - prev
- ✅ Acceleration = curr_vel - prev_vel
- ✅ First point: v=0, a=0
- ✅ Second point: a=0
- ✅ 2D nearest_keys tensor [batch, sequence]

**WHAT'S DIFFERENT (Cannot Test):**
- ❓ ONNX Runtime version (Android 1.20.0 vs CLI ?)
- ❓ Test data source (CLI uses different file?)
- ❓ ONNX session configuration
- ❓ Beam search implementation differences
- ❓ Decoder initialization

## 🤔 Theories on Root Cause

### Theory #1: Test Data Mismatch
- Test data in assets/swipes.jsonl may not match CLI test data
- Coordinates might be from different keyboard layout
- Need to verify what file CLI test actually uses

### Theory #2: ONNX Runtime Behavioral Difference
- Android ONNX Runtime 1.20.0 may behave differently than JVM version
- Tensor creation might have platform-specific quirks
- Float precision differences?

### Theory #3: Model Ignoring nearest_keys
- Model predicts 't' when nearest_keys show 'w'
- Suggests model isn't using nearest_keys input at all
- Maybe input name mismatch? ("nearest_keys" vs something else)

### Theory #4: Hidden Bug in Tensor Creation
- All logging shows correct values
- But actual tensor bytes might be wrong
- ByteBuffer endianness issue?

## 📋 Next Steps

### Option A: Verify CLI Test Baseline
```bash
# Run actual CLI test to confirm it works
cd /data/data/com.termux/files/home/git/swype/cleverkeys
# Need to compile and run TestOnnxPrediction.kt
# Verify it actually gets 50%+ accuracy
```

### Option B: Deep Debug ONNX Inputs
- Add tensor value dumps (first 20 elements)
- Compare exact byte values between CLI and Android
- Check if tensor names match ONNX model expectations

### Option C: Test Different Data
- Create minimal test case (single swipe of "hello")
- Generate synthetic perfect swipe data
- Test with web demo's exact test data format

### Option D: Ask User
- What file does CLI test use?
- What's the actual CLI test accuracy?
- Can you share working test data file?

## 💡 Recommendation

**Most likely issue:** Test data format or source mismatch

**Next step:** Get exact test data file that CLI test uses and verify it achieves stated 50%+ accuracy. Then use that EXACT data in TestActivity.

**Alternative:** Create synthetic "perfect" test data (straight line swipe for "hello") to eliminate data quality as variable.
