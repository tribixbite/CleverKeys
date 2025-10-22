#!/bin/bash
# Automated Activity Testing Script for CleverKeys Material 3 UI
# Tests all settings screens via ADB

set -e

PACKAGE="tribixbite.keyboard2.debug"
ACTIVITY_PACKAGE="tribixbite.keyboard2"
APK_PATH="build/outputs/apk/debug/tribixbite.keyboard2.debug.apk"

echo "════════════════════════════════════════════════════════════════"
echo "  CleverKeys Material 3 Activities - Automated Testing Suite"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Color codes for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

print_error() {
    echo -e "${RED}[✗]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[!]${NC} $1"
}

# Function to test activity launch
test_activity() {
    local activity_name=$1
    local display_name=$2

    echo ""
    echo "────────────────────────────────────────────────────────────────"
    print_status "Testing: ${display_name}"
    echo "────────────────────────────────────────────────────────────────"

    # Launch activity
    print_status "Launching ${activity_name}..."
    if adb shell am start -n "${PACKAGE}/${ACTIVITY_PACKAGE}${activity_name}" 2>&1 | grep -q "Error"; then
        print_error "Failed to launch ${display_name}"
        return 1
    fi

    print_success "Activity launched successfully"

    # Wait for activity to load
    sleep 2

    # Check if activity is in foreground
    current_activity=$(adb shell dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -1)
    if echo "$current_activity" | grep -q "$activity_name"; then
        print_success "Activity is in foreground"
    else
        print_warning "Activity may not be in foreground (check manually)"
    fi

    # Check for crashes
    if adb logcat -d -s AndroidRuntime:E | tail -20 | grep -q "FATAL EXCEPTION"; then
        print_error "Crash detected in logcat!"
        adb logcat -d -s AndroidRuntime:E | tail -20
        return 1
    else
        print_success "No crashes detected"
    fi

    # Take screenshot
    local screenshot_name="screenshot_$(echo $activity_name | tr '.' '_').png"
    print_status "Taking screenshot..."
    adb exec-out screencap -p > "test-screenshots/${screenshot_name}" 2>/dev/null || true
    if [ -f "test-screenshots/${screenshot_name}" ]; then
        print_success "Screenshot saved: test-screenshots/${screenshot_name}"
    fi

    # Give time to inspect
    sleep 3

    # Go back to close activity
    adb shell input keyevent KEYCODE_BACK
    sleep 1

    print_success "${display_name} test completed"
    return 0
}

# Create screenshots directory
mkdir -p test-screenshots

# Step 1: Build APK
echo ""
print_status "Step 1: Building APK..."
if ./gradlew assembleDebug 2>&1 | tail -5; then
    print_success "APK built successfully"
else
    print_error "Failed to build APK"
    exit 1
fi

# Check APK exists
if [ ! -f "$APK_PATH" ]; then
    print_error "APK not found at $APK_PATH"
    exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
print_success "APK ready (${APK_SIZE}): $APK_PATH"

# Step 2: Check device connection
echo ""
print_status "Step 2: Checking device connection..."
if ! adb devices | grep -q "device$"; then
    print_error "No device connected via ADB"
    print_warning "Please connect a device or start an emulator"
    exit 1
fi

DEVICE=$(adb devices | grep "device$" | head -1 | awk '{print $1}')
print_success "Device connected: $DEVICE"

# Step 3: Install APK
echo ""
print_status "Step 3: Installing APK..."
if adb install -r "$APK_PATH" 2>&1 | grep -q "Success"; then
    print_success "APK installed successfully"
else
    print_warning "Installation may have failed, but continuing..."
fi

# Step 4: Clear logcat
echo ""
print_status "Step 4: Clearing logcat..."
adb logcat -c
print_success "Logcat cleared"

# Step 5: Test each activity
echo ""
echo "════════════════════════════════════════════════════════════════"
echo "  Beginning Activity Tests"
echo "════════════════════════════════════════════════════════════════"

ACTIVITIES=(
    ".LauncherActivity:Launcher Activity"
    ".SettingsActivity:Main Settings (Material 3)"
    ".NeuralSettingsActivity:Neural Prediction Settings (Material 3)"
    ".neural.NeuralBrowserActivityM3:Neural Model Browser (Material 3)"
    ".SwipeCalibrationActivity:Swipe Calibration"
)

PASSED=0
FAILED=0

for activity_entry in "${ACTIVITIES[@]}"; do
    IFS=':' read -r activity_name display_name <<< "$activity_entry"
    if test_activity "$activity_name" "$display_name"; then
        ((PASSED++))
    else
        ((FAILED++))
    fi
done

# Summary
echo ""
echo "════════════════════════════════════════════════════════════════"
echo "  Test Summary"
echo "════════════════════════════════════════════════════════════════"
echo ""
print_success "Passed: $PASSED/${#ACTIVITIES[@]}"
if [ $FAILED -gt 0 ]; then
    print_error "Failed: $FAILED/${#ACTIVITIES[@]}"
else
    print_success "Failed: 0/${#ACTIVITIES[@]}"
fi
echo ""

if [ -d "test-screenshots" ] && [ "$(ls -A test-screenshots)" ]; then
    print_success "Screenshots saved in: test-screenshots/"
    ls -lh test-screenshots/
fi

echo ""
echo "════════════════════════════════════════════════════════════════"
echo "  Testing Complete!"
echo "════════════════════════════════════════════════════════════════"
echo ""

if [ $FAILED -eq 0 ]; then
    print_success "All tests passed! ✓"
    exit 0
else
    print_error "Some tests failed. Check output above for details."
    exit 1
fi
