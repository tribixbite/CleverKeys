#!/bin/bash
# Quick installation script for minimal CleverKeys APK
# Run this when device reconnects

set -e

echo "🔧 Installing Minimal CleverKeys APK..."
echo ""

# Check device connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No device connected. Please connect device via ADB."
    exit 1
fi

echo "✅ Device connected"

# Install APK
echo "📦 Installing APK..."
adb install -r build/outputs/apk/debug/tribixbite.keyboard2.debug.apk

# Enable IME
echo "⚙️ Enabling CleverKeys IME..."
adb shell ime enable tribixbite.keyboard2.debug/tribixbite.keyboard2.CleverKeysService

# Set as default
echo "🎯 Setting as default keyboard..."
adb shell ime set tribixbite.keyboard2.debug/tribixbite.keyboard2.CleverKeysService

# Clear logs
echo "🗑️ Clearing old logs..."
adb logcat -c

echo ""
echo "✅ Installation complete!"
echo ""
echo "📝 Next steps:"
echo "1. Open any app with text input (SMS, Chrome, etc.)"
echo "2. Tap a text field to trigger keyboard"
echo "3. Watch logs with: adb logcat -s CleverKeys:V"
echo ""
echo "🔍 Expected success logs:"
echo "   D CleverKeys: 🔧 CleverKeys starting (MINIMAL MODE)..."
echo "   D CleverKeys: ✅ Lifecycle initialized"
echo "   D CleverKeys: ✅ Configuration loaded"
echo "   D CleverKeys: ✅ Default layout loaded"
echo "   D CleverKeys: ✅ Minimal initialization complete"
echo ""
echo "📸 Capture success:"
echo "   adb shell screencap -p > ~/storage/shared/DCIM/Screenshots/cleverkeys_minimal_works.png"
echo ""
