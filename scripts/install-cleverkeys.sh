#!/data/data/com.termux/files/usr/bin/bash

# CleverKeys Installation Helper
# This script helps install and switch to CleverKeys keyboard

APK="build/outputs/apk/debug/tribixbite.keyboard2.apk"

echo "════════════════════════════════════════════════════════"
echo "  CleverKeys Installation & Setup Helper"
echo "════════════════════════════════════════════════════════"
echo

# Check if APK exists
if [ ! -f "$APK" ]; then
    echo "❌ APK not found at: $APK"
    echo "   Run ./gradlew assembleDebug first"
    exit 1
fi

APK_SIZE=$(du -h "$APK" | cut -f1)
echo "✅ APK found: $APK ($APK_SIZE)"
echo

# Method 1: termux-open (opens Android installer)
echo "Method 1: Installing via Android System Installer"
echo "───────────────────────────────────────────────────────"
echo "Opening APK with system installer..."
termux-open "$APK"
echo "✅ Installation prompt should appear on screen"
echo

# Wait a moment
sleep 2

# Method 2: ADB (if available)
echo "Method 2: Checking ADB connection..."
echo "───────────────────────────────────────────────────────"
ADB_DEVICE=$(adb devices | grep -E "192.168|device$" | head -1 | awk '{print $1}')

if [ -n "$ADB_DEVICE" ] && [ "$ADB_DEVICE" != "offline" ]; then
    echo "✅ ADB device found: $ADB_DEVICE"
    echo "Installing via ADB..."
    adb install -r "$APK" && echo "✅ Installed via ADB"
    
    echo
    echo "Switching to CleverKeys..."
    adb shell ime set tribixbite.keyboard2/.CleverKeysService
    
    echo
    echo "Current keyboard:"
    adb shell settings get secure default_input_method
else
    echo "⚠️  ADB not available (device offline or not connected)"
    echo "   Use Method 1 (system installer) instead"
fi

echo
echo "════════════════════════════════════════════════════════"
echo "  Next Steps:"
echo "════════════════════════════════════════════════════════"
echo "1. If installation prompt appeared, tap 'Install'"
echo "2. Open any app with text input"
echo "3. Tap keyboard icon in navigation bar"
echo "4. Select 'CleverKeys Neural Keyboard'"
echo
echo "Or manually enable in:"
echo "Settings → System → Languages & Input → On-screen keyboard"
echo "════════════════════════════════════════════════════════"
