#!/data/data/com.termux/files/usr/bin/bash

# Post-Installation Testing Script for CleverKeys

echo "═══════════════════════════════════════════════════════════════"
echo "  CleverKeys Post-Installation Testing"
echo "═══════════════════════════════════════════════════════════════"
echo

# Check if we can connect to ADB
echo "1. Checking ADB connection..."
ADB_STATUS=$(adb devices 2>&1 | grep -E "device$" | head -1)
if [ -n "$ADB_STATUS" ]; then
    echo "   ✅ ADB connected"
    ADB_AVAILABLE=true
else
    echo "   ⚠️  ADB offline - manual testing required"
    ADB_AVAILABLE=false
fi
echo

# Check if CleverKeys is installed
echo "2. Checking CleverKeys installation..."
if [ "$ADB_AVAILABLE" = true ]; then
    PACKAGE_CHECK=$(adb shell pm list packages | grep tribixbite.keyboard2)
    if [ -n "$PACKAGE_CHECK" ]; then
        echo "   ✅ CleverKeys installed: $PACKAGE_CHECK"
        
        # Get version info
        VERSION=$(adb shell dumpsys package tribixbite.keyboard2 | grep versionName | head -1)
        echo "   Version: $VERSION"
    else
        echo "   ❌ CleverKeys not installed"
        echo "   Please install from Downloads/cleverkeys.apk"
        exit 1
    fi
else
    echo "   ℹ️  Cannot verify (ADB offline)"
fi
echo

# Check if CleverKeys is enabled
echo "3. Checking if CleverKeys is enabled..."
if [ "$ADB_AVAILABLE" = true ]; then
    ENABLED=$(adb shell settings get secure enabled_input_methods | grep tribixbite.keyboard2)
    if [ -n "$ENABLED" ]; then
        echo "   ✅ CleverKeys is enabled"
    else
        echo "   ⚠️  CleverKeys not enabled"
        echo "   Go to: Settings → Languages & Input → On-screen keyboard"
    fi
else
    echo "   ℹ️  Cannot verify (ADB offline)"
fi
echo

# Check if CleverKeys is active
echo "4. Checking if CleverKeys is active..."
if [ "$ADB_AVAILABLE" = true ]; then
    CURRENT_IME=$(adb shell settings get secure default_input_method)
    echo "   Current keyboard: $CURRENT_IME"
    
    if [[ "$CURRENT_IME" == *"tribixbite.keyboard2"* ]]; then
        echo "   ✅ CleverKeys IS ACTIVE!"
        CLEVERKEYS_ACTIVE=true
    else
        echo "   ⚠️  CleverKeys not active (using other keyboard)"
        echo "   Switch via: Open app → Tap text field → Keyboard icon → CleverKeys"
        CLEVERKEYS_ACTIVE=false
    fi
else
    echo "   ℹ️  Cannot verify (ADB offline)"
    CLEVERKEYS_ACTIVE=false
fi
echo

# Check if CleverKeys process is running
echo "5. Checking CleverKeys process..."
if [ "$ADB_AVAILABLE" = true ]; then
    PID=$(adb shell pidof tribixbite.keyboard2)
    if [ -n "$PID" ]; then
        echo "   ✅ CleverKeys running (PID: $PID)"
        
        # Get memory usage
        MEM=$(adb shell dumpsys meminfo tribixbite.keyboard2 | grep "TOTAL PSS" | awk '{print $3}')
        echo "   Memory: ${MEM}KB"
    else
        echo "   ⚠️  CleverKeys not running"
        if [ "$CLEVERKEYS_ACTIVE" = true ]; then
            echo "   ⚠️  WARNING: Active but not running - may be crashed!"
        fi
    fi
else
    echo "   ℹ️  Cannot verify (ADB offline)"
fi
echo

# Check recent logs
echo "6. Checking recent logs..."
if [ "$ADB_AVAILABLE" = true ]; then
    echo "   Recent CleverKeys logs:"
    adb logcat -d -s CleverKeys:* CleverKeysService:* AndroidRuntime:E | tail -20 | sed 's/^/   /'
else
    echo "   ℹ️  Cannot check logs (ADB offline)"
fi
echo

# Summary
echo "═══════════════════════════════════════════════════════════════"
echo "  SUMMARY"
echo "═══════════════════════════════════════════════════════════════"

if [ "$ADB_AVAILABLE" = true ]; then
    if [ "$CLEVERKEYS_ACTIVE" = true ] && [ -n "$PID" ]; then
        echo "  ✅ CleverKeys is installed, enabled, active, and running!"
        echo
        echo "  Next: Test keyboard functionality"
        echo "  • Open any app with text input"
        echo "  • Verify keys work"
        echo "  • Test swipes, predictions, etc."
        echo "  • Report: 'keyboard works!' or describe issues"
    else
        echo "  ⚠️  CleverKeys installed but not fully functional"
        echo
        echo "  Action needed:"
        if [ "$CLEVERKEYS_ACTIVE" = false ]; then
            echo "  • Switch to CleverKeys keyboard"
        fi
        if [ -z "$PID" ] && [ "$CLEVERKEYS_ACTIVE" = true ]; then
            echo "  • Check logs - keyboard may have crashed"
        fi
    fi
else
    echo "  ℹ️  Manual testing required (ADB offline)"
    echo
    echo "  Please verify:"
    echo "  1. CleverKeys appears in Settings → Languages & Input"
    echo "  2. You can switch to CleverKeys"
    echo "  3. Keyboard appears when you tap text fields"
    echo "  4. Keys respond to taps"
    echo
    echo "  Then report: 'keyboard works!' or describe issues"
fi

echo "═══════════════════════════════════════════════════════════════"
