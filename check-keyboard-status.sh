#!/data/data/com.termux/files/usr/bin/bash
#
# CleverKeys Status Checker
# Verifies installation and provides next steps
#

set -e

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                    CleverKeys - Installation Status                        ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check 1: APK Installation
echo "1. Checking APK installation..."
if pm list packages | grep -q "tribixbite.keyboard2.debug"; then
    echo -e "   ${GREEN}✅ INSTALLED${NC}: tribixbite.keyboard2.debug"

    # Get APK path
    APK_PATH=$(pm path tribixbite.keyboard2.debug | cut -d: -f2)
    echo "   📦 Location: $APK_PATH"

    # Get APK size
    if [ -f "$APK_PATH" ]; then
        SIZE=$(du -h "$APK_PATH" | cut -f1)
        echo "   💾 Size: $SIZE"
    fi
else
    echo -e "   ${RED}❌ NOT INSTALLED${NC}"
    echo "   ⚠️  Run: ./gradlew assembleDebug && termux-open build/outputs/apk/debug/*.apk"
    exit 1
fi

echo ""

# Check 2: Keyboard Enablement (attempt to check)
echo "2. Checking keyboard status..."
echo "   ℹ️  Checking if keyboard is enabled requires Settings access"
echo "   ℹ️  I'll attempt to check via ime list..."

# Try to get enabled IMEs
ENABLED_IMES=$(settings get secure enabled_input_methods 2>/dev/null || echo "Permission denied")

if [[ "$ENABLED_IMES" == *"tribixbite.keyboard2"* ]]; then
    echo -e "   ${GREEN}✅ ENABLED${NC}: CleverKeys is in enabled keyboards list"
elif [[ "$ENABLED_IMES" == "Permission denied"* ]]; then
    echo -e "   ${YELLOW}⚠️  UNKNOWN${NC}: Cannot check (Termux permission limitation)"
    echo "   📝 You must verify manually in Settings"
else
    echo -e "   ${RED}❌ NOT ENABLED${NC}: CleverKeys not in enabled keyboards list"
    echo "   📝 Enable it in: Settings → System → Languages & input → Manage keyboards"
fi

echo ""

# Check 3: Active Keyboard
echo "3. Checking if keyboard is active..."
CURRENT_IME=$(settings get secure default_input_method 2>/dev/null || echo "Permission denied")

if [[ "$CURRENT_IME" == *"tribixbite.keyboard2"* ]]; then
    echo -e "   ${GREEN}✅ ACTIVE${NC}: CleverKeys is currently selected"
    echo "   🎉 You're ready to test!"
elif [[ "$CURRENT_IME" == "Permission denied"* ]]; then
    echo -e "   ${YELLOW}⚠️  UNKNOWN${NC}: Cannot check (Termux permission limitation)"
    echo "   📝 You must verify manually by opening a text app"
else
    echo -e "   ${YELLOW}⚠️  NOT ACTIVE${NC}: Current keyboard: $CURRENT_IME"
    echo "   📝 Switch keyboards: Open text app → Tap keyboard switcher (⌨️) → Select CleverKeys"
fi

echo ""
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

# Summary and Next Steps
echo "📋 SUMMARY:"
echo ""

if pm list packages | grep -q "tribixbite.keyboard2.debug"; then
    if [[ "$ENABLED_IMES" == *"tribixbite.keyboard2"* ]]; then
        if [[ "$CURRENT_IME" == *"tribixbite.keyboard2"* ]]; then
            echo -e "${GREEN}🎉 ALL SET!${NC} CleverKeys is installed, enabled, and active."
            echo ""
            echo "🧪 RUN QUICK TESTS:"
            echo "   1. Open any text app (Messages, Notes, etc.)"
            echo "   2. Type: hello world"
            echo "   3. Type: th (check predictions)"
            echo "   4. Swipe: h→e→l→l→o"
            echo "   5. Type: teh  (check autocorrect)"
            echo ""
            echo "📖 See: 00_START_HERE_FIRST.md for detailed testing"
        else
            echo -e "${YELLOW}⚠️  ALMOST READY${NC} - CleverKeys is installed and enabled"
            echo ""
            echo "📝 NEXT STEPS:"
            echo "   1. Open any text app"
            echo "   2. Tap a text field"
            echo "   3. Tap keyboard switcher icon (⌨️)"
            echo "   4. Select 'CleverKeys (Debug)'"
            echo "   5. Run quick tests (see above)"
        fi
    else
        echo -e "${YELLOW}⚠️  NOT ENABLED YET${NC} - CleverKeys is installed but not enabled"
        echo ""
        echo "📝 NEXT STEPS:"
        echo "   1. Open Settings app"
        echo "   2. Go to: System → Languages & input → Manage keyboards"
        echo "   3. Toggle 'CleverKeys (Debug)' to ON"
        echo "   4. Accept any permission requests"
        echo "   5. Then run this script again to verify"
    fi
else
    echo -e "${RED}❌ NOT INSTALLED${NC}"
    echo ""
    echo "📝 INSTALLATION STEPS:"
    echo "   1. Run: ./gradlew assembleDebug"
    echo "   2. Run: termux-open build/outputs/apk/debug/*.apk"
    echo "   3. Install the APK"
    echo "   4. Then run this script again to verify"
fi

echo ""
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📖 Documentation:"
echo "   • Quick Start: 00_START_HERE_FIRST.md"
echo "   • Cheat Sheet: QUICK_REFERENCE.md"
echo "   • Full Guide: MANUAL_TESTING_GUIDE.md"
echo "   • All Files: INDEX.md"
echo ""
echo "🐛 Found a bug? Report it with details!"
echo "✅ Everything works? Let me know: 'It works!'"
echo ""
