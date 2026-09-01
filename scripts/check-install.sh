#!/data/data/com.termux/files/usr/bin/bash
# CleverKeys Installation Status Check
# Verifies app installation and helps with next steps

set -e

# Release applicationId is tribixbite.cleverkeys; the debug variant carries
# applicationIdSuffix '.debug' so both can be installed side by side.
# Override with PACKAGE_NAME=tribixbite.cleverkeys.debug to inspect a debug build.
PACKAGE_NAME="${PACKAGE_NAME:-tribixbite.cleverkeys}"
APP_NAME="CleverKeys"

echo "========================================="
echo "CleverKeys Installation Status Check"
echo "========================================="
echo ""

# Check if package is installed
if pm list packages | grep -q "^package:${PACKAGE_NAME}$"; then
    echo "✅ ${APP_NAME} is INSTALLED"
    echo ""

    # Get package info
    VERSION=$(dumpsys package ${PACKAGE_NAME} 2>/dev/null | grep "versionName" | head -1 | awk -F'=' '{print $2}')
    if [ -n "$VERSION" ]; then
        echo "📦 Version: $VERSION"
    fi

    # Check if it's enabled as an IME
    echo ""
    echo "🔍 Checking Input Method Service registration..."
    if ime list -s 2>/dev/null | grep -q "${PACKAGE_NAME}"; then
        echo "✅ Keyboard is registered as Input Method"

        # Check if it's enabled
        if ime list -a 2>/dev/null | grep -q "${PACKAGE_NAME}"; then
            echo "✅ Keyboard is ENABLED"

            # Check if it's the current IME
            if settings get secure default_input_method 2>/dev/null | grep -q "${PACKAGE_NAME}"; then
                echo "✅ Keyboard is ACTIVE (currently selected)"
                echo ""
                echo "🎉 Installation Complete & Keyboard Active!"
                echo ""
                echo "📋 Next Steps - Runtime Testing:"
                echo "  1. Open any app with text input (Messages, Notes, etc.)"
                echo "  2. Tap on a text field"
                echo "  3. Verify keyboard appears correctly"
                echo "  4. Test basic typing and layout switching"
                echo "  5. Test swipe gestures if swipe typing is enabled"
                echo ""
                echo "To view logs:"
                echo "  logcat -s CleverKeys:* Keyboard2:* CtcEngineAdapter:*"
            else
                echo "⚠️  Keyboard is enabled but NOT active"
                echo ""
                echo "📱 To set as active keyboard:"
                echo "  1. Tap on any text field"
                echo "  2. Tap keyboard switcher icon (bottom right)"
                echo "  3. Select 'CleverKeys'"
                echo ""
                echo "Or go to:"
                echo "  Settings → System → Languages & input"
                echo "  → On-screen keyboard → Select CleverKeys"
            fi
        else
            echo "⚠️  Keyboard is registered but NOT enabled"
            echo ""
            echo "📱 To enable the keyboard:"
            echo "  Settings → System → Languages & input"
            echo "  → Virtual keyboard → Manage keyboards"
            echo "  → Enable 'CleverKeys'"
        fi
    else
        echo "⚠️  Keyboard is NOT registered as Input Method"
        echo ""
        echo "This may indicate:"
        echo "  - App installed but IME service not initialized"
        echo "  - Manifest configuration issue"
        echo "  - Runtime crash preventing service registration"
        echo ""
        echo "Check logs for errors:"
        echo "  logcat -s AndroidRuntime:E"
    fi

    echo ""
    echo "📊 Quick Actions:"
    echo "  - Launch app:     am start -n ${PACKAGE_NAME}/.LauncherActivity"
    echo "  - Open settings:  am start -n ${PACKAGE_NAME}/.SettingsActivity"
    echo "  - View logs:      logcat -s CleverKeys:* | tail -50"
    echo "  - Uninstall:      pm uninstall ${PACKAGE_NAME}"

else
    echo "❌ ${APP_NAME} is NOT installed"
    echo ""
    echo "📋 Installation Steps:"
    echo "  1. Run: ./install.sh"
    echo "  2. Tap 'Install' in the Android Package Installer"
    echo "  3. Wait for installation to complete"
    echo "  4. Run this script again to verify"
    echo ""
    echo "APK Location:"
    echo "  build/outputs/apk/debug/CleverKeys-v*-arm64-v8a.apk"
fi

echo ""
echo "========================================="
