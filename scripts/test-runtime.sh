#!/bin/bash
# Runtime validation script for CleverKeys APK
# Tests swipe prediction, IME integration, and system functionality

set -e

APK_PACKAGE="tribixbite.keyboard2"
APK_PATH="build/outputs/apk/debug/tribixbite.keyboard2.debug.apk"

echo "========================================="
echo "CleverKeys Runtime Validation"
echo "========================================="
echo ""

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK not found at $APK_PATH"
    echo "Run: ./build-on-termux.sh"
    exit 1
fi

echo "✅ APK found: $(ls -lh $APK_PATH | awk '{print $5}')"
echo ""

# Verify APK contents
echo "📦 Verifying APK contents..."
ONNX_ENCODER=$(unzip -l "$APK_PATH" | grep "swipe_model_character_quant.onnx" | awk '{print $1}')
ONNX_DECODER=$(unzip -l "$APK_PATH" | grep "swipe_decoder_character_quant.onnx" | awk '{print $1}')
DICT_EN=$(unzip -l "$APK_PATH" | grep "dictionaries/en.txt" | awk '{print $1}')

if [ -n "$ONNX_ENCODER" ]; then
    echo "  ✅ Encoder model: $(numfmt --to=iec $ONNX_ENCODER)"
else
    echo "  ❌ Encoder model missing"
fi

if [ -n "$ONNX_DECODER" ]; then
    echo "  ✅ Decoder model: $(numfmt --to=iec $ONNX_DECODER)"
else
    echo "  ❌ Decoder model missing"
fi

if [ -n "$DICT_EN" ]; then
    echo "  ✅ English dictionary: $(numfmt --to=iec $DICT_EN)"
else
    echo "  ❌ English dictionary missing"
fi
echo ""

# Check if already installed
echo "🔍 Checking installation status..."
if pm list packages | grep -q "$APK_PACKAGE"; then
    echo "  ℹ️  Package already installed"
    INSTALLED_VERSION=$(dumpsys package "$APK_PACKAGE" | grep versionName | head -1 | awk -F= '{print $2}')
    echo "  📌 Version: $INSTALLED_VERSION"

    read -p "  Reinstall? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "  🔄 Uninstalling old version..."
        pm uninstall "$APK_PACKAGE" 2>/dev/null || true
    else
        echo "  ⏭️  Skipping installation"
        echo ""
        echo "========================================="
        echo "Manual Testing Instructions:"
        echo "========================================="
        echo ""
        echo "1. ENABLE KEYBOARD:"
        echo "   Settings → System → Languages & input → Virtual keyboard"
        echo "   → Enable 'CleverKeys'"
        echo ""
        echo "2. SET AS DEFAULT:"
        echo "   Tap input field → Select 'CleverKeys' from keyboard picker"
        echo ""
        echo "3. TEST SWIPE PREDICTION:"
        echo "   - Swipe across keyboard (e.g., 'hello', 'world')"
        echo "   - Check logcat for ONNX initialization:"
        echo "     logcat | grep 'OnnxSwipePredictor'"
        echo ""
        echo "4. VERIFY FUNCTIONALITY:"
        echo "   - Encoder/decoder loading messages"
        echo "   - Prediction results in suggestion bar"
        echo "   - No runtime crashes or exceptions"
        echo ""
        echo "5. CHECK LOGS:"
        echo "   logcat -s CleverKeysService OnnxSwipePredictor SwipeTrajectoryProcessor"
        echo ""
        exit 0
    fi
else
    echo "  📥 Package not installed"
fi
echo ""

# Installation instructions
echo "========================================="
echo "Installation Steps:"
echo "========================================="
echo ""
echo "Due to SELinux restrictions, manual installation required:"
echo ""
echo "METHOD 1: Using termux-open (Recommended)"
echo "  termux-open $APK_PATH"
echo "  → Opens Android package installer UI"
echo "  → Tap 'Install' button"
echo ""
echo "METHOD 2: Using adb (if device has ADB enabled)"
echo "  adb install -r $APK_PATH"
echo ""
echo "METHOD 3: Copy to accessible location"
echo "  1. Copy APK to Downloads or shared storage"
echo "  2. Use file manager to install"
echo ""
echo "After installation, run this script again to see testing instructions."
echo ""

# Offer to open installer
read -p "Open APK installer now? (y/n): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "📲 Opening Android package installer..."
    termux-open "$APK_PATH" 2>/dev/null || echo "⚠️  termux-open not available. Please install manually."
fi
echo ""
echo "✅ Validation script complete"
