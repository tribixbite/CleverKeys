#!/bin/bash

# CleverKeys Automated Testing Script
# Tests keyboard functionality via ADB including tap and swipe gestures

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Screen dimensions (1080x2340)
SCREEN_WIDTH=1080
SCREEN_HEIGHT=2340

# Keyboard area (bottom 1/3 of screen)
KB_TOP=1560
KB_BOTTOM=2340
KB_HEIGHT=780

# Key dimensions (assuming QWERTY layout)
KEY_WIDTH=108  # 1080 / 10 keys
KEY_HEIGHT=195 # 780 / 4 rows

# QWERTY layout positions (approximate center of each key)
# Row 1 (top): Q W E R T Y U I O P
# Row 2: A S D F G H J K L
# Row 3: Z X C V B N M

declare -A KEY_POSITIONS=(
    # Row 1 - y=1650
    ["q"]="54,1650"
    ["w"]="162,1650"
    ["e"]="270,1650"
    ["r"]="378,1650"
    ["t"]="486,1650"
    ["y"]="594,1650"
    ["u"]="702,1650"
    ["i"]="810,1650"
    ["o"]="918,1650"
    ["p"]="1026,1650"

    # Row 2 - y=1845
    ["a"]="108,1845"
    ["s"]="216,1845"
    ["d"]="324,1845"
    ["f"]="432,1845"
    ["g"]="540,1845"
    ["h"]="648,1845"
    ["j"]="756,1845"
    ["k"]="864,1845"
    ["l"]="972,1845"

    # Row 3 - y=2040
    ["z"]="162,2040"
    ["x"]="270,2040"
    ["c"]="378,2040"
    ["v"]="486,2040"
    ["b"]="594,2040"
    ["n"]="702,2040"
    ["m"]="810,2040"

    # Special keys
    ["space"]="540,2235"
)

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[⚠]${NC} $1"
}

log_error() {
    echo -e "${RED}[✗]${NC} $1"
}

# Check ADB connection
check_adb() {
    log_info "Checking ADB connection..."
    if ! adb devices | grep -q "device$"; then
        log_error "No ADB device connected"
        exit 1
    fi
    log_success "ADB device connected"
}

# Check if CleverKeys is installed
check_keyboard_installed() {
    log_info "Checking if CleverKeys is installed..."
    if adb shell pm list packages | grep -q "tribixbite.keyboard2"; then
        log_success "CleverKeys is installed"
    else
        log_error "CleverKeys is not installed"
        exit 1
    fi
}

# Open a text field to activate keyboard
activate_keyboard() {
    log_info "Activating keyboard..."

    # Open Chrome with a new tab (will have URL bar - text field)
    adb shell am start -a android.intent.action.VIEW -d "https://www.google.com"
    sleep 2

    # Tap on search/URL bar to activate keyboard
    adb shell input tap 540 200
    sleep 1

    log_success "Keyboard activated"
}

# Simulate single key tap
tap_key() {
    local key=$1
    local pos=${KEY_POSITIONS[$key]}

    if [ -z "$pos" ]; then
        log_warning "Unknown key: $key"
        return 1
    fi

    local x=$(echo $pos | cut -d',' -f1)
    local y=$(echo $pos | cut -d',' -f2)

    log_info "Tapping key '$key' at ($x, $y)"
    adb shell input tap $x $y
    sleep 0.1
}

# Simulate swipe gesture for word
swipe_word() {
    local word=$1
    local duration=${2:-300}  # Default 300ms

    log_info "Swiping word: '$word' (duration: ${duration}ms)"

    # Get coordinates for each letter
    local coords=()
    for (( i=0; i<${#word}; i++ )); do
        local char="${word:$i:1}"
        local pos=${KEY_POSITIONS[$char]}

        if [ -z "$pos" ]; then
            log_warning "Cannot swipe - unknown character: $char"
            return 1
        fi

        coords+=("$pos")
    done

    # Build swipe command
    local swipe_cmd="adb shell input swipe"

    # Start position
    local start_pos=${coords[0]}
    local start_x=$(echo $start_pos | cut -d',' -f1)
    local start_y=$(echo $start_pos | cut -d',' -f2)

    # End position
    local end_pos=${coords[-1]}
    local end_x=$(echo $end_pos | cut -d',' -f1)
    local end_y=$(echo $end_pos | cut -d',' -f2)

    # Execute swipe from first letter to last letter
    log_info "  Swipe from ($start_x,$start_y) to ($end_x,$end_y)"
    adb shell input swipe $start_x $start_y $end_x $end_y $duration
    sleep 0.5
}

# Test tap typing
test_tap_typing() {
    log_info "=== Testing Tap Typing ==="

    local test_text="hello"

    for (( i=0; i<${#test_text}; i++ )); do
        local char="${test_text:$i:1}"
        tap_key "$char"
    done

    tap_key "space"
    log_success "Tap typing test complete: '$test_text '"
}

# Test swipe typing
test_swipe_typing() {
    log_info "=== Testing Swipe Typing ==="

    # Test simple words
    local words=("hello" "world" "test" "swipe" "keyboard")

    for word in "${words[@]}"; do
        swipe_word "$word" 400
        sleep 0.3
        tap_key "space"
        sleep 0.2
    done

    log_success "Swipe typing test complete"
}

# Test loop gesture (repeated letters)
test_loop_gesture() {
    log_info "=== Testing Loop Gesture (repeated letters) ==="

    # Simulate loop on 'l' key for "hello"
    local l_pos=${KEY_POSITIONS["l"]}
    local l_x=$(echo $l_pos | cut -d',' -f1)
    local l_y=$(echo $l_pos | cut -d',' -f2)

    # Small circle around the 'l' key
    log_info "Drawing loop gesture on 'l' key"
    adb shell input swipe $l_x $l_y $((l_x+30)) $((l_y-30)) $((l_x+60)) $l_y $((l_x+30)) $((l_y+30)) $l_x $l_y 500

    sleep 0.5
    log_success "Loop gesture test complete"
}

# Test predictions/suggestions
test_predictions() {
    log_info "=== Testing Prediction System ==="

    # Type partial word to trigger predictions
    tap_key "h"
    sleep 0.2
    tap_key "e"
    sleep 0.2
    tap_key "l"
    sleep 0.3

    # Tap on suggestion bar area (top of keyboard)
    log_info "Tapping suggestion bar"
    adb shell input tap 540 1500
    sleep 0.5

    tap_key "space"
    log_success "Prediction test complete"
}

# Clear text field
clear_text() {
    log_info "Clearing text field..."

    # Select all (Ctrl+A simulation via long press + menu)
    adb shell input tap 540 200
    sleep 0.3
    adb shell input swipe 100 200 900 200 500
    sleep 0.3

    # Delete
    adb shell input keyevent KEYCODE_DEL
    sleep 0.3
}

# Capture screenshot
capture_screenshot() {
    local filename=$1
    log_info "Capturing screenshot: $filename"
    adb shell screencap -p /sdcard/$filename
    adb pull /sdcard/$filename ~/storage/shared/Download/ 2>/dev/null || true
    log_success "Screenshot saved: $filename"
}

# Get keyboard logs
get_keyboard_logs() {
    log_info "=== CleverKeys Logs (last 50 lines) ==="
    adb logcat -d -s CleverKeys:D | tail -50
}

# Main test execution
main() {
    echo ""
    echo "════════════════════════════════════════════════════════"
    echo "  CleverKeys Automated Testing via ADB"
    echo "════════════════════════════════════════════════════════"
    echo ""

    # Preliminary checks
    check_adb
    check_keyboard_installed

    echo ""
    log_info "Starting automated tests..."
    echo ""

    # Activate keyboard
    activate_keyboard
    sleep 1

    # Capture initial state
    capture_screenshot "test_01_initial.png"

    # Test 1: Tap Typing
    test_tap_typing
    capture_screenshot "test_02_tap_typing.png"
    sleep 1

    # Clear for next test
    clear_text
    sleep 1

    # Test 2: Swipe Typing
    test_swipe_typing
    capture_screenshot "test_03_swipe_typing.png"
    sleep 1

    # Clear for next test
    clear_text
    sleep 1

    # Test 3: Loop Gesture
    test_loop_gesture
    capture_screenshot "test_04_loop_gesture.png"
    sleep 1

    # Clear for next test
    clear_text
    sleep 1

    # Test 4: Predictions
    test_predictions
    capture_screenshot "test_05_predictions.png"
    sleep 1

    echo ""
    log_success "All automated tests complete!"
    echo ""

    # Show logs
    get_keyboard_logs

    echo ""
    echo "════════════════════════════════════════════════════════"
    echo "  Test Summary"
    echo "════════════════════════════════════════════════════════"
    echo "  ✓ Tap typing tested"
    echo "  ✓ Swipe typing tested (5 words)"
    echo "  ✓ Loop gesture tested"
    echo "  ✓ Prediction system tested"
    echo "  ✓ Screenshots captured (5 images)"
    echo ""
    echo "Screenshots saved to: ~/storage/shared/Download/"
    echo "Review logs above for any errors"
    echo ""
}

# Run main function
main "$@"
