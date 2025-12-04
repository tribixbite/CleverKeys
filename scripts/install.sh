#!/data/data/com.termux/files/usr/bin/bash
# CleverKeys APK Installation Script
# Automatically installs the APK using multiple methods

set -e

APK_PATH="build/outputs/apk/debug/tribixbite.keyboard2.debug.apk"
PACKAGE_NAME="tribixbite.keyboard2"

echo "========================================="
echo "CleverKeys Auto-Install Script"
echo "========================================="
echo ""

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK not found at: $APK_PATH"
    echo ""
    echo "Build the APK first with:"
    echo "  ./gradlew assembleDebug"
    echo "  or"
    echo "  ./build-on-termux.sh"
    exit 1
fi

APK_SIZE=$(ls -lh "$APK_PATH" | awk '{print $5}')
echo "✅ Found APK: $APK_SIZE"
echo ""

# Method 1: Try termux-open (most reliable)
echo "Method 1: Using termux-open (Android Package Installer)..."
if command -v termux-open &>/dev/null; then
    echo "  Opening Android package installer..."
    termux-open "$APK_PATH" 2>/dev/null && {
        echo "  ✅ Package installer opened!"
        echo ""
        echo "📱 Complete installation in the Android UI:"
        echo "  1. Tap 'Install' button"
        echo "  2. Wait for installation to complete"
        echo "  3. Enable CleverKeys in Settings → Languages & input"
        exit 0
    }
    echo "  ⚠️  termux-open failed, trying next method..."
else
    echo "  ⚠️  termux-open not available"
fi
echo ""

# Method 2: Try ADB local
echo "Method 2: Using local ADB (if device has ADB enabled)..."
if command -v adb &>/dev/null; then
    # Check for connected devices
    DEVICES=$(adb devices 2>/dev/null | grep -v "List" | grep "device$" | wc -l)

    if [ "$DEVICES" -gt 0 ]; then
        echo "  📱 Found $DEVICES connected device(s)"
        echo "  Uninstalling old version..."
        adb uninstall "$PACKAGE_NAME" 2>/dev/null || echo "  (No previous version found)"

        echo "  Installing new APK..."
        if adb install -r "$APK_PATH" 2>&1 | grep -q "Success"; then
            echo "  ✅ APK installed successfully via ADB!"
            echo ""
            echo "🎉 Installation complete!"
            echo ""
            echo "To enable the keyboard:"
            echo "  Settings → System → Languages & input → Virtual keyboard"
            echo "  → Enable 'CleverKeys'"
            exit 0
        else
            echo "  ⚠️  ADB install failed, trying next method..."
        fi
    else
        echo "  ⚠️  No ADB devices connected"
        echo "  Enable USB debugging or wireless ADB to use this method"
    fi
else
    echo "  ⚠️  ADB not installed (install: pkg install android-tools)"
fi
echo ""

# Method 3: Copy to accessible location for manual install
echo "Method 3: Copy to /sdcard for manual installation..."
SDCARD_PATH="/sdcard/Download/cleverkeys-debug.apk"

if cp "$APK_PATH" "$SDCARD_PATH" 2>/dev/null; then
    echo "  ✅ APK copied to: $SDCARD_PATH"
    echo ""
    echo "📱 Manual installation steps:"
    echo "  1. Open your file manager app"
    echo "  2. Navigate to Downloads folder"
    echo "  3. Tap 'cleverkeys-debug.apk'"
    echo "  4. Tap 'Install'"
    echo ""

    # Try to open the file manager
    if command -v termux-open &>/dev/null; then
        echo "Opening file manager..."
        termux-open "$SDCARD_PATH" 2>/dev/null || true
    fi
    exit 0
else
    echo "  ⚠️  Cannot write to /sdcard/Download"
    echo "  Storage permission may be needed"
fi
echo ""

# Method 4: Try shared storage
echo "Method 4: Copy to Termux shared storage..."
TERMUX_STORAGE="$HOME/storage/downloads/cleverkeys-debug.apk"

# Setup storage access if needed
if [ ! -d "$HOME/storage" ]; then
    echo "  Setting up Termux storage access..."
    termux-setup-storage 2>/dev/null || true
    sleep 2
fi

if [ -d "$HOME/storage/downloads" ]; then
    if cp "$APK_PATH" "$TERMUX_STORAGE" 2>/dev/null; then
        echo "  ✅ APK copied to: ~/storage/downloads/cleverkeys-debug.apk"
        echo ""
        echo "📱 Manual installation:"
        echo "  1. Open Downloads in your file manager"
        echo "  2. Tap 'cleverkeys-debug.apk'"
        echo "  3. Install the app"
        echo ""

        if command -v termux-open &>/dev/null; then
            termux-open "$TERMUX_STORAGE" 2>/dev/null || true
        fi
        exit 0
    else
        echo "  ⚠️  Failed to copy to Termux storage"
    fi
else
    echo "  ⚠️  Termux storage not accessible"
    echo "  Run: termux-setup-storage"
fi
echo ""

# All methods failed
echo "========================================="
echo "❌ Automatic installation failed"
echo "========================================="
echo ""
echo "Manual installation required:"
echo ""
echo "1. Share APK via Termux:"
echo "   termux-open $APK_PATH"
echo ""
echo "2. Or use ADB from PC:"
echo "   adb install $APK_PATH"
echo ""
echo "3. Or copy manually:"
echo "   cp $APK_PATH /sdcard/Download/"
echo "   (Then install from file manager)"
echo ""
exit 1
