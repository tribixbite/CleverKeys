#!/bin/bash
# CleverKeys Theory Testing Script
# Run this manually to test all 4 theories

echo "╔══════════════════════════════════════════════════════════════════╗"
echo "║                                                                  ║"
echo "║            CleverKeys Theory Testing Script                      ║"
echo "║                                                                  ║"
echo "╚══════════════════════════════════════════════════════════════════╝"
echo ""

DOWNLOAD_DIR="/storage/emulated/0/Download"

theories=(
    "CleverKeys_TEST_NO_DEBUG_SUFFIX.apk:Theory #1 (70% confidence):Remove .debug suffix"
    "CleverKeys_THEORY2_NO_DIRECTBOOT.apk:Theory #2 (40% confidence):Remove directBootAware"
    "CleverKeys_THEORY3_WITH_PROGUARD.apk:Theory #3 (20% confidence):ProGuard keep rules"
    "CleverKeys_THEORY4_MULTIDEX.apk:Theory #4 (15% confidence):Explicit MultiDex"
)

echo "📱 Testing 4 theories progressively..."
echo ""

for i in "${!theories[@]}"; do
    IFS=: read -r apk_name theory_name description <<< "${theories[$i]}"
    
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "📦 $theory_name - $description"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    # Check if APK exists
    if [ ! -f "$DOWNLOAD_DIR/$apk_name" ]; then
        echo "❌ ERROR: APK not found: $DOWNLOAD_DIR/$apk_name"
        echo ""
        continue
    fi
    
    echo "✅ APK found: $apk_name"
    echo ""
    
    # Try ADB installation
    if adb devices | grep -q "device$"; then
        echo "📲 Attempting ADB installation..."
        if adb install -r "$DOWNLOAD_DIR/$apk_name" 2>&1 | grep -q "Success"; then
            echo "✅ Installation successful via ADB"
            echo ""
            echo "📝 Now test manually:"
            echo "   1. Settings → Languages & Input"
            echo "   2. Enable 'Minimal Test Keyboard'"
            echo "   3. Open any app → Tap text field"
            echo "   4. Does keyboard appear?"
            echo ""
            read -p "Did the keyboard appear? (yes/no): " result
            
            if [ "$result" = "yes" ] || [ "$result" = "y" ]; then
                echo ""
                echo "╔══════════════════════════════════════════════════════════════════╗"
                echo "║                                                                  ║"
                echo "║                   🎉 SUCCESS! 🎉                                 ║"
                echo "║                                                                  ║"
                echo "║              $theory_name WORKS!                                 ║"
                echo "║                                                                  ║"
                echo "╚══════════════════════════════════════════════════════════════════╝"
                echo ""
                echo "📋 Report this result to Claude:"
                echo "   $theory_name: YES"
                exit 0
            else
                echo "❌ $theory_name: FAILED"
                echo "   Uninstalling and moving to next theory..."
                adb uninstall tribixbite.keyboard2 2>/dev/null
            fi
        else
            echo "❌ ADB installation failed"
        fi
    else
        echo "⚠️  No ADB device connected"
        echo "📝 Please install manually:"
        echo "   1. Open Files app"
        echo "   2. Navigate to Downloads"
        echo "   3. Tap: $apk_name"
        echo "   4. Install"
        echo "   5. Enable 'Minimal Test Keyboard' in Settings"
        echo "   6. Test in any text field"
        echo ""
        read -p "Did the keyboard appear? (yes/no/skip): " result
        
        if [ "$result" = "yes" ] || [ "$result" = "y" ]; then
            echo ""
            echo "╔══════════════════════════════════════════════════════════════════╗"
            echo "║                                                                  ║"
            echo "║                   🎉 SUCCESS! 🎉                                 ║"
            echo "║                                                                  ║"
            echo "║              $theory_name WORKS!                                 ║"
            echo "║                                                                  ║"
            echo "╚══════════════════════════════════════════════════════════════════╝"
            exit 0
        elif [ "$result" = "skip" ] || [ "$result" = "s" ]; then
            echo "⏭️  Skipping to next theory..."
        else
            echo "❌ $theory_name: FAILED"
        fi
    fi
    
    echo ""
done

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "⚠️  ALL 4 THEORIES FAILED"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "📋 Report to Claude:"
echo "   All theories failed - need deeper investigation"
echo ""
echo "Next steps:"
echo "   1. Capture logcat during keyboard enable attempt"
echo "   2. Install original Unexpected-Keyboard to verify device"
echo "   3. Check system logs for InputMethodManagerService"
