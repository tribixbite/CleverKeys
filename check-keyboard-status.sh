#!/data/data/com.termux/files/usr/bin/bash

# Quick status checker for CleverKeys

echo "════════════════════════════════════════════════════════"
echo "  CleverKeys Status Check"
echo "════════════════════════════════════════════════════════"
echo

# Check if APK exists
if [ -f "build/outputs/apk/debug/tribixbite.keyboard2.debug.apk" ]; then
    APK_SIZE=$(du -h "build/outputs/apk/debug/tribixbite.keyboard2.debug.apk" | cut -f1)
    APK_DATE=$(stat -c '%y' "build/outputs/apk/debug/tribixbite.keyboard2.debug.apk" | cut -d' ' -f1,2 | cut -d'.' -f1)
    echo "✅ APK Ready: $APK_SIZE (built: $APK_DATE)"
else
    echo "❌ APK not found"
fi

# Try to check installation status via ADB
echo
echo "ADB Status:"
echo "───────────────────────────────────────────────────────"

# Check ADB devices
DEVICES=$(adb devices 2>&1 | grep -v "List of devices" | grep -E "device|offline" | wc -l)
if [ "$DEVICES" -gt 0 ]; then
    adb devices | grep -v "List of devices"
    
    # Try to get package info
    echo
    echo "Checking installed keyboards..."
    adb shell pm list packages 2>/dev/null | grep -E "tribixbite|juloo" | sed 's/package:/  /'
    
    echo
    echo "Current default keyboard:"
    CURRENT_IME=$(adb shell settings get secure default_input_method 2>/dev/null)
    if [ -n "$CURRENT_IME" ]; then
        echo "  $CURRENT_IME"
        if [[ "$CURRENT_IME" == *"tribixbite.keyboard2"* ]]; then
            echo "  ✅ CleverKeys is active!"
        else
            echo "  ℹ️  CleverKeys not active (using other keyboard)"
        fi
    fi
    
    # Check if CleverKeys service is running
    echo
    echo "CleverKeys process status:"
    CLEVERKEYS_PID=$(adb shell pidof tribixbite.keyboard2 2>/dev/null)
    if [ -n "$CLEVERKEYS_PID" ]; then
        echo "  ✅ Running (PID: $CLEVERKEYS_PID)"
    else
        echo "  ⚠️  Not running (service not started or not installed)"
    fi
else
    echo "  ⚠️  No ADB devices connected"
    echo "  (Cannot check installation status remotely)"
fi

echo
echo "════════════════════════════════════════════════════════"
echo "  Manual Check Steps:"
echo "════════════════════════════════════════════════════════"
echo "1. Open Settings → Languages & Input"
echo "2. Check if 'CleverKeys Neural Keyboard' is listed"
echo "3. If not, run: ./install-cleverkeys.sh"
echo "════════════════════════════════════════════════════════"
