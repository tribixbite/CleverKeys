#!/bin/bash
# CleverKeys swipe prediction test runner
# Tests prediction pipeline using Android instrumentation tests

set -e

echo "=========================================
CleverKeys ONNX Test Runner
========================================="

# Check if device/emulator is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected"
    echo "   Please connect device or start emulator"
    exit 1
fi

echo "
📱 Connected device:"
adb devices

GRADLE_GUARD="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/gradle-guard.sh"

echo "
🏗️  Building and installing test APK..."
"$GRADLE_GUARD" assembleDebugAndroidTest

echo "
📦 Installing app and test APKs..."
"$GRADLE_GUARD" installDebugAndroidTest

echo "
🧪 Running ONNX prediction tests..."
echo "   Test package: tribixbite.keyboard2.test"
echo "   Test runner: androidx.test.runner.AndroidJUnitRunner"
echo ""

# Run all tests
adb shell am instrument -w \
    tribixbite.keyboard2.test/androidx.test.runner.AndroidJUnitRunner

echo "
✅ Test execution complete!"
echo ""
echo "📊 View full logs:"
echo "   adb logcat -s TestRunner:* OnnxSwipe:* SwipeTrajectory:*"
echo ""
echo "🔍 Run specific test:"
echo "   adb shell am instrument -w -e class tribixbite.keyboard2.OnnxPredictionTest#testSwipeHello \\"
echo "     tribixbite.keyboard2.test/androidx.test.runner.AndroidJUnitRunner"
