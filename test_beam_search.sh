#!/data/data/com.termux/files/usr/bin/bash

# Test beam search by monitoring logcat while triggering calibration
adb shell am force-stop tribixbite.keyboard2.debug
adb logcat -c
adb shell am start -n tribixbite.keyboard2.debug/tribixbite.keyboard2.SwipeCalibrationActivity &

# Wait for activity to start
sleep 3

echo "Monitoring logs for beam search debug output..."
echo "Please swipe a word on the calibration screen now."
echo ""

# Monitor logs in real-time
adb logcat -s OnnxSwipePredictor:D | grep -E "Loop iteration|Inside try|processBatchedBeams returned|Beam search complete"
