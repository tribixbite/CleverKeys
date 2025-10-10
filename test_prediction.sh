#!/data/data/com.termux/files/usr/bin/bash

echo "=== Testing beam search with instrumentation ==="

# Clear logs
adb logcat -c

# Launch the app
adb shell am start -n tribixbite.keyboard2.debug/tribixbite.keyboard2.LauncherActivity

sleep 3

# Trigger a test prediction via broadcast (if implemented)
# Or directly test via calibration activity
adb shell am broadcast -a tribixbite.keyboard2.TEST_PREDICTION --es word "test" 2>/dev/null || echo "Broadcast not available"

sleep 2

# Check for any recent prediction logs
echo ""
echo "=== Recent beam search logs ===" 
adb logcat -d -s OnnxSwipePredictor:D | grep -E "⏩ Loop|🔄 Beam|🚦 About|🔵 Inside|🎯 Beam search complete" | tail -30

