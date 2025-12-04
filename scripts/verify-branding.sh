#!/bin/bash

# CleverKeys Branding Verification Script
# Created: November 21, 2025
# Purpose: Automated verification that branding appears on keyboard

set -e

echo "========================================="
echo "CleverKeys Branding Verification"
echo "========================================="
echo ""

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# Counters
PASSED=0
FAILED=0
WARNINGS=0

# Helper functions
pass() {
    echo -e "${GREEN}✓${NC} $1"
    ((PASSED++))
}

fail() {
    echo -e "${RED}✗${NC} $1"
    ((FAILED++))
}

warn() {
    echo -e "${YELLOW}⚠${NC} $1"
    ((WARNINGS++))
}

info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

section() {
    echo ""
    echo -e "${PURPLE}==>${NC} $1"
    echo ""
}

# 1. Check APK exists
section "1. Checking APK"

APK_PATH="build/outputs/apk/debug/tribixbite.keyboard2.apk"
if [ -f "$APK_PATH" ]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    pass "APK exists: $APK_PATH ($APK_SIZE)"
else
    fail "APK not found at: $APK_PATH"
    echo ""
    echo "Build the APK first with: ./gradlew assembleDebug"
    exit 1
fi

# 2. Check version_info.txt
section "2. Checking Version Info"

VERSION_FILE="build/generated-resources/raw/version_info.txt"
if [ -f "$VERSION_FILE" ]; then
    pass "version_info.txt exists"

    # Extract build number
    BUILD_NUMBER=$(grep "build_number=" "$VERSION_FILE" | cut -d'=' -f2)
    if [ -n "$BUILD_NUMBER" ]; then
        # Get last 4 digits
        LAST_FOUR="${BUILD_NUMBER: -4}"
        pass "Build number: $BUILD_NUMBER (displays as #$LAST_FOUR)"
        info "Expected branding: ${PURPLE}CleverKeys#$LAST_FOUR${NC}"
    else
        warn "Build number not found in version_info.txt"
    fi

    # Show build date
    BUILD_DATE=$(grep "build_date=" "$VERSION_FILE" | cut -d'=' -f2)
    if [ -n "$BUILD_DATE" ]; then
        info "Build date: $BUILD_DATE"
    fi
else
    fail "version_info.txt not found"
    warn "Branding might not work without version info"
fi

# 3. Check branding code exists in Keyboard2View.kt
section "3. Checking Branding Code"

KEYBOARD_VIEW="src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt"
if [ -f "$KEYBOARD_VIEW" ]; then
    pass "Keyboard2View.kt exists"

    # Check for branding function
    if grep -q "drawSpacebarBranding" "$KEYBOARD_VIEW"; then
        pass "drawSpacebarBranding() function found"
    else
        fail "drawSpacebarBranding() function NOT found"
        echo "  Branding code might be missing!"
    fi

    # Check for branding paint
    if grep -q "brandingPaint" "$KEYBOARD_VIEW"; then
        pass "brandingPaint defined"
    else
        fail "brandingPaint NOT defined"
    fi

    # Check for color #9B59B6 (jewel purple)
    if grep -q "9B59B6" "$KEYBOARD_VIEW"; then
        pass "Jewel purple color (#9B59B6) found"
    else
        warn "Jewel purple color not found (might use different format)"
    fi

    # Check for silver background #C0C0C0
    if grep -q "C0C0C0" "$KEYBOARD_VIEW"; then
        pass "Silver background (#C0C0C0) found"
    else
        warn "Silver background color not found (might use different format)"
    fi
else
    fail "Keyboard2View.kt not found"
fi

# 4. Check if ADB is connected
section "4. Checking ADB Connection"

if command -v adb &> /dev/null; then
    ADB_DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" | wc -l)
    if [ "$ADB_DEVICES" -gt 0 ]; then
        pass "ADB connected ($ADB_DEVICES device(s))"
        ADB_CONNECTED=true
    else
        warn "ADB not connected"
        info "Connect via: adb connect <device-ip>:5555"
        ADB_CONNECTED=false
    fi
else
    warn "ADB command not found"
    ADB_CONNECTED=false
fi

# 5. Check if APK is installed (if ADB connected)
section "5. Checking Installation"

if [ "$ADB_CONNECTED" = true ]; then
    INSTALLED=$(adb shell pm list packages | grep "tribixbite.keyboard2" | wc -l)
    if [ "$INSTALLED" -gt 0 ]; then
        pass "CleverKeys is installed on device"
        adb shell pm list packages | grep "tribixbite.keyboard2" | while read line; do
            info "  $line"
        done
    else
        warn "CleverKeys NOT installed on device"
        info "Install with: adb install -r $APK_PATH"
    fi
else
    info "Skipping installation check (ADB not connected)"
fi

# 6. Summary
section "Verification Summary"

echo ""
echo -e "${GREEN}Passed:${NC}   $PASSED"
echo -e "${RED}Failed:${NC}   $FAILED"
echo -e "${YELLOW}Warnings:${NC} $WARNINGS"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ All critical checks passed!${NC}"
    echo ""
    echo "Next steps:"
    echo "1. Install APK: adb install -r $APK_PATH"
    echo "2. Enable keyboard in Settings → Languages & input"
    echo "3. Open text field and switch to CleverKeys"
    echo "4. Verify branding: Look for 'CleverKeys#$LAST_FOUR' on spacebar"
    echo "5. Take screenshot for verification"
    echo ""
    echo "See BRANDING_VERIFICATION.md for detailed guide"
    exit 0
else
    echo -e "${RED}✗ Some checks failed!${NC}"
    echo ""
    echo "Fix the issues above before testing branding."
    exit 1
fi
