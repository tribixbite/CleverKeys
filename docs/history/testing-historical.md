# CleverKeys Testing Guide

## 🧪 Testing Options

### Option 1: Android Instrumentation Tests (Recommended)

Test the complete ONNX neural prediction pipeline using actual source files with Android context.

```bash
# Run all tests
./run-tests.sh

# Or use Gradle directly
./gradlew connectedAndroidTest
```

**Test Cases:**
- `testSwipeHello` - Basic swipe prediction
- `testSwipeWorld` - Multi-direction swipe
- `testSwipeTest` - Short word swipe
- `testSwipeValues` - **Validates Fix #30** (real key positions)
- `testBeamSearchDiversity` - **Validates Fix #29** (no beam collapse)
- `testKeyboardDimensions`, `testNeuralConfig`

**View Logs:**
```bash
adb logcat -s TestRunner:* OnnxSwipe:* SwipeTrajectory:*
```

---

### Option 2: On-Device Testing (User-Driven)

1. Install APK: `./build-install.sh`
2. Enable keyboard in Settings
3. Test swipe gestures: "hello", "world", "test", "values"

**Check Logs:**
```bash
adb logcat | grep "OnnxSwipe\|SwipeTrajectory"

# ✅ Look for correct nearest_keys (not [25,25,25...])
# ✅ Look for real word predictions (not "ttt", "lll")
```

---

## 🔍 Validation Checklist

### Fix #30 (Real Key Positions)
- [ ] Real key positions logged: "🎹 Real key positions updated: 26 keys"
- [ ] nearest_keys show correct sequence
- [ ] Predictions are real English words

### Fix #29 (Beam Search Diversity)
- [ ] Predictions are diverse (3-5 unique words)
- [ ] No repetitive tokens like "ttt", "rt"
- [ ] Top prediction usually matches intended word

---

**Last Updated:** October 11, 2025
