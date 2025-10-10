# Testing Guide - ONNX Prediction Accuracy

## Overview

This guide explains how to verify that the ONNX neural prediction pipeline produces **accurate word predictions** (not gibberish like "ggeeeeee").

## What We're Testing

After the critical bug fixes, we need to verify:

1. ✅ **Accurate Predictions**: Top predictions are real words
2. ✅ **No Gibberish**: No repeated character patterns  
3. ✅ **Reasonable Scores**: Confidence scores 1-1000
4. ✅ **Target Words in Top-N**: Expected words in top 3-5

## Quick Start

```bash
# Build and install APK
./build-on-termux.sh

# Run accuracy tests on device
./test_onnx_accuracy.sh
```

## Test Suites

### 1. Android Instrumentation Tests ⭐ (Recommended)

**File:** `src/androidTest/kotlin/tribixbite/keyboard2/OnnxAccuracyTest.kt`

Tests 5 scenarios with real ONNX models:
- "hello" swipe → expects "hello" in top 3
- "test" swipe → expects "test" in top 5  
- "the" swipe → expects "the" in top 3
- No gibberish patterns
- Confidence scores reasonable

**Run:**
```bash
./test_onnx_accuracy.sh          # Automated
./gradlew connectedAndroidTest   # Manual
```

**Expected:**
```
🎯 Test: hello swipe
   Top prediction: 'hello'
   ✅ PERFECT: Got exact word
```

### 2. Runtime Test Suite

**File:** `src/main/kotlin/tribixbite/keyboard2/RuntimeTestSuite.kt`

Enhanced "Neural Engine Accuracy" test:
- Gibberish detection  
- Score validation
- Realistic "hello" swipe

### 3. CLI Simulation (Mock Only)

**Files:** `test_decoding.kt`, `run_decoding_test.sh`

Validates pipeline logic without real ONNX models.

## Success Criteria

✅ 5/5 tests pass
✅ Top predictions are real words
✅ No gibberish detected
✅ Scores in range 1-1000
✅ Execution < 200ms

## Troubleshooting

**Gibberish output ("ggeeeeee"):**
- Check Fix #6 applied (feature extraction)
- Verify normalize FIRST, simple deltas
- Check mask conventions (1=padded, 0=valid)

**Bad scores (>1000):**
- Check Fix #4 applied (log-softmax)
- Verify beam scoring uses log probabilities

See full documentation in TESTING.md (this file)
