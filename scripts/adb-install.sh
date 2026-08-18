#!/data/data/com.termux/files/usr/bin/bash
#
# CleverKeys ADB Installation Script
# Uses wireless ADB for automated APK installation on device
#

set -e

APK_PATH="build/outputs/apk/debug/tribixbite.keyboard2.debug.apk"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_FULL_PATH="$SCRIPT_DIR/$APK_PATH"

echo "========================================="
echo "CleverKeys ADB Install Script"
echo "========================================="
echo ""

# Check if APK exists
if [ ! -f "$APK_FULL_PATH" ]; then
    echo "❌ APK not found at: $APK_FULL_PATH"
    echo "   Run ./gradlew assembleDebug first"
    exit 1
fi

APK_SIZE=$(du -h "$APK_FULL_PATH" | cut -f1)
echo "✅ Found APK: $APK_SIZE"
echo ""

# Check if adb is installed
if ! command -v adb &> /dev/null; then
    echo "❌ ADB not installed"
    echo "   Install with: pkg install android-tools"
    exit 1
fi

# Check for ADB connection
ADB_DEVICES=$(adb devices | grep -v "List of devices" | grep -v "^$" | grep -v "daemon")

if [ -z "$ADB_DEVICES" ]; then
    echo "❌ No ADB device connected"
    echo ""
    echo "📱 Setup Wireless ADB (one-time):"
    echo "   1. Enable Developer Options:"
    echo "      Settings → About phone → Tap 'Build number' 7 times"
    echo ""
    echo "   2. Enable Wireless Debugging:"
    echo "      Settings → System → Developer options → Wireless debugging (ON)"
    echo ""
    echo "   3. Pair device (first time only):"
    echo "      - Tap 'Pair device with pairing code'"
    echo "      - Note IP:PORT and PAIRING_CODE"
    echo "      - Run: adb pair IP:PORT PAIRING_CODE"
    echo ""
    echo "   4. Connect to device:"
    echo "      - Note 'IP address & Port' from Wireless debugging screen"
    echo "      - Run: adb connect IP:PORT"
    echo ""
    echo "   5. Re-run this script"
    exit 1
fi

echo "✅ ADB device connected:"
echo "$ADB_DEVICES"
echo ""

# Install APK
echo "📦 Installing APK via ADB..."
if adb install -r "$APK_FULL_PATH"; then
    echo ""
    echo "✅ Installation successful!"
    echo ""
    echo "📱 Next steps:"
    echo "   1. Enable keyboard: Settings → Languages & input → Virtual keyboard"
    echo "   2. Activate CleverKeys in any text field"
    echo "   3. Test basic typing and swipe gestures"
    echo ""
    echo "🔍 Monitor logs:"
    echo "   adb logcat -s CleverKeys:* Keyboard2:* CtcEngineAdapter:* AndroidRuntime:E"
else
    echo ""
    echo "❌ Installation failed"
    echo "   Check ADB connection and try again"
    exit 1
fi

echo "========================================="
