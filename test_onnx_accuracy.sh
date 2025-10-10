#!/bin/bash
# Test ONNX prediction accuracy on device
# Run after APK installation

echo "🧪 ONNX Prediction Accuracy Test"
echo "=================================="
echo ""

APK_PATH="build/outputs/apk/debug/tribixbite.keyboard2.debug.apk"
PACKAGE="tribixbite.keyboard2"

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK not found at: $APK_PATH"
    echo "   Run: ./build-on-termux.sh first"
    exit 1
fi

echo "📦 APK found: $APK_PATH"
echo "   Size: $(du -h "$APK_PATH" | cut -f1)"
echo ""

# Check if device connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected"
    echo "   Connect device or start emulator"
    exit 1
fi

echo "📱 Device connected"
echo ""

# Install APK if not already installed
if ! adb shell pm list packages | grep -q "$PACKAGE"; then
    echo "📥 Installing APK..."
    adb install "$APK_PATH" 2>&1 | grep -v "Performing Streamed Install"

    if [ $? -ne 0 ]; then
        echo "❌ Installation failed"
        exit 1
    fi
    echo "✅ APK installed"
else
    echo "✅ APK already installed"
fi

echo ""
echo "🧪 Running ONNX accuracy tests..."
echo "=================================="
echo ""

# Run Android instrumentation tests
adb shell am instrument -w \
    -e class tribixbite.keyboard2.OnnxAccuracyTest \
    tribixbite.keyboard2.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | \
    tee test_output.txt

# Parse results
echo ""
echo "📊 Test Results Summary"
echo "======================="
echo ""

if grep -q "OK (5 tests)" test_output.txt; then
    echo "🎉 ALL TESTS PASSED (5/5)"
    echo ""

    # Extract key predictions
    echo "📝 Prediction Results:"
    grep "Test: hello swipe" test_output.txt -A2 | grep "Top prediction:"
    grep "Test: test swipe" test_output.txt -A2 | grep "Top prediction:"
    grep "Test: the swipe" test_output.txt -A2 | grep "Top prediction:"

    echo ""
    echo "✅ Neural prediction is working correctly!"
    echo "   • Predictions are actual words (not gibberish)"
    echo "   • Confidence scores are reasonable"
    echo "   • No repeated character patterns"
    echo ""

    rm -f test_output.txt
    exit 0

elif grep -q "FAILURES" test_output.txt; then
    echo "❌ TESTS FAILED"
    echo ""
    echo "Failed tests:"
    grep "FAILURE" test_output.txt
    echo ""
    echo "Possible issues:"
    echo "   • ONNX models not loaded correctly"
    echo "   • Feature extraction broken (check Fix #6)"
    echo "   • Mask conventions inverted (check Fix #6)"
    echo "   • Log-softmax not applied (check Fix #4)"
    echo ""
    echo "See test_output.txt for full details"
    exit 1
else
    echo "⚠️  Could not determine test results"
    echo "See test_output.txt for full output"
    exit 1
fi
