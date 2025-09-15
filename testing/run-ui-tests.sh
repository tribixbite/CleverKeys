#!/bin/bash
# Comprehensive UI and performance testing script for CleverKeys
# This script runs all automated tests and generates reports

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_ROOT/test-results"
SCREENSHOTS_DIR="$OUTPUT_DIR/screenshots"
BASELINE_DIR="$PROJECT_ROOT/testing/baseline-screenshots"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🧪 CleverKeys Automated Testing Suite${NC}"
echo "========================================"

# Create output directories
mkdir -p "$OUTPUT_DIR"
mkdir -p "$SCREENSHOTS_DIR"
mkdir -p "$BASELINE_DIR"

# Function to log with timestamp
log() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# Function to check if ADB device is connected
check_device() {
    if ! command -v adb &> /dev/null; then
        echo -e "${RED}❌ ADB not found. Please install Android SDK.${NC}"
        exit 1
    fi

    DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
    if [ "$DEVICE_COUNT" -eq 0 ]; then
        echo -e "${YELLOW}⚠️  No Android devices connected. Starting emulator...${NC}"
        start_emulator
    else
        echo -e "${GREEN}✅ Android device connected${NC}"
    fi
}

# Function to start Android emulator
start_emulator() {
    if command -v emulator &> /dev/null; then
        log "Starting Android emulator..."
        emulator -avd test_avd -no-audio -no-window &

        # Wait for emulator to be ready
        log "Waiting for emulator to boot..."
        adb wait-for-device

        # Wait for boot completion
        while [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; do
            sleep 2
        done

        log "Emulator ready"
    else
        echo -e "${RED}❌ No emulator available. Please connect a device or set up an emulator.${NC}"
        exit 1
    fi
}

# Function to build APKs
build_apks() {
    log "🔨 Building APKs..."

    cd "$PROJECT_ROOT"

    # Build debug APK
    ./gradlew assembleDebug --stacktrace

    # Build test APK
    ./gradlew assembleDebugAndroidTest --stacktrace

    # Build benchmark APK (if available)
    if ./gradlew tasks | grep -q "assembleBenchmark"; then
        ./gradlew assembleBenchmark --stacktrace
    fi

    echo -e "${GREEN}✅ APKs built successfully${NC}"
}

# Function to install APKs
install_apks() {
    log "📱 Installing APKs..."

    # Uninstall existing versions
    adb uninstall tribixbite.keyboard2.debug 2>/dev/null || true
    adb uninstall tribixbite.keyboard2.debug.test 2>/dev/null || true

    # Install main APK
    adb install "$PROJECT_ROOT/build/outputs/apk/debug/tribixbite.keyboard2.debug.apk"

    # Install test APK
    adb install "$PROJECT_ROOT/build/outputs/apk/androidTest/debug/tribixbite.keyboard2.debug-androidTest.apk"

    echo -e "${GREEN}✅ APKs installed${NC}"
}

# Function to setup keyboard
setup_keyboard() {
    log "⌨️  Setting up CleverKeys..."

    # Enable CleverKeys as input method
    adb shell ime enable tribixbite.keyboard2/.CleverKeysService
    adb shell ime set tribixbite.keyboard2/.CleverKeysService

    # Disable animations for testing
    adb shell settings put global window_animation_scale 0
    adb shell settings put global transition_animation_scale 0
    adb shell settings put global animator_duration_scale 0

    echo -e "${GREEN}✅ CleverKeys configured${NC}"
}

# Function to run performance tests
run_performance_tests() {
    log "⚡ Running performance tests..."

    adb shell am instrument -w -r \
        -e class 'tribixbite.keyboard2.test.PerformanceTestSuite' \
        tribixbite.keyboard2.debug.test/androidx.test.runner.AndroidJUnitRunner \
        > "$OUTPUT_DIR/performance_results.txt"

    echo -e "${GREEN}✅ Performance tests completed${NC}"
}

# Function to run UI responsiveness tests
run_ui_tests() {
    log "🖱️  Running UI responsiveness tests..."

    adb shell am instrument -w -r \
        -e class 'tribixbite.keyboard2.test.UIResponsivenessTest' \
        tribixbite.keyboard2.debug.test/androidx.test.runner.AndroidJUnitRunner \
        > "$OUTPUT_DIR/ui_results.txt"

    echo -e "${GREEN}✅ UI tests completed${NC}"
}

# Function to run visual regression tests
run_visual_tests() {
    log "📸 Running visual regression tests..."

    # Create screenshots directory on device
    adb shell mkdir -p /sdcard/screenshots

    # Run visual regression test suite
    adb shell am instrument -w -r \
        -e class 'tribixbite.keyboard2.test.VisualRegressionTestSuite' \
        tribixbite.keyboard2.debug.test/androidx.test.runner.AndroidJUnitRunner \
        > "$OUTPUT_DIR/visual_results.txt"

    # Pull screenshots from device
    adb pull /sdcard/screenshots "$SCREENSHOTS_DIR/"

    echo -e "${GREEN}✅ Visual tests completed${NC}"
}

# Function to compare screenshots
compare_screenshots() {
    log "🔍 Comparing screenshots..."

    if [ ! -d "$BASELINE_DIR" ] || [ -z "$(ls -A "$BASELINE_DIR")" ]; then
        log "No baseline screenshots found. Current screenshots will be saved as baseline."
        cp -r "$SCREENSHOTS_DIR"/* "$BASELINE_DIR/"
        echo -e "${YELLOW}⚠️  Baseline screenshots created. Re-run tests to perform comparison.${NC}"
        return
    fi

    # Install Python dependencies if needed
    if ! python3 -c "import cv2" 2>/dev/null; then
        log "Installing Python dependencies..."
        pip3 install -r "$SCRIPT_DIR/requirements.txt"
    fi

    # Run screenshot comparison
    python3 "$SCRIPT_DIR/screenshot-comparison.py" \
        --baseline "$BASELINE_DIR" \
        --current "$SCREENSHOTS_DIR" \
        --output "$OUTPUT_DIR/visual-regression" \
        --fail-on-diff

    echo -e "${GREEN}✅ Screenshot comparison completed${NC}"
}

# Function to run accessibility tests
run_accessibility_tests() {
    log "♿ Running accessibility tests..."

    # Enable accessibility features
    adb shell settings put secure high_text_contrast_enabled 1
    adb shell settings put system font_scale 1.3

    adb shell am instrument -w -r \
        -e class 'tribixbite.keyboard2.test.AccessibilityTestSuite' \
        tribixbite.keyboard2.debug.test/androidx.test.runner.AndroidJUnitRunner \
        > "$OUTPUT_DIR/accessibility_results.txt"

    # Reset accessibility settings
    adb shell settings put secure high_text_contrast_enabled 0
    adb shell settings put system font_scale 1.0

    echo -e "${GREEN}✅ Accessibility tests completed${NC}"
}

# Function to generate comprehensive report
generate_report() {
    log "📊 Generating test report..."

    REPORT_FILE="$OUTPUT_DIR/test_report.html"

    cat > "$REPORT_FILE" << EOF
<!DOCTYPE html>
<html>
<head>
    <title>CleverKeys Test Report</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; }
        .header { background: #2196F3; color: white; padding: 20px; border-radius: 8px; }
        .section { margin: 20px 0; padding: 15px; border: 1px solid #ddd; border-radius: 5px; }
        .pass { border-left: 5px solid #4CAF50; }
        .fail { border-left: 5px solid #f44336; }
        .warning { border-left: 5px solid #ff9800; }
        pre { background: #f5f5f5; padding: 10px; border-radius: 3px; overflow-x: auto; }
        .metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; }
        .metric { background: #f9f9f9; padding: 15px; border-radius: 5px; text-align: center; }
    </style>
</head>
<body>
    <div class="header">
        <h1>🧪 CleverKeys Automated Test Report</h1>
        <p>Generated on $(date)</p>
    </div>

    <div class="section">
        <h2>📊 Test Summary</h2>
        <div class="metrics">
            <div class="metric">
                <h3>Performance Tests</h3>
                <p>$(grep -c "PASSED\|FAILED" "$OUTPUT_DIR/performance_results.txt" 2>/dev/null || echo "0") tests run</p>
            </div>
            <div class="metric">
                <h3>UI Tests</h3>
                <p>$(grep -c "PASSED\|FAILED" "$OUTPUT_DIR/ui_results.txt" 2>/dev/null || echo "0") tests run</p>
            </div>
            <div class="metric">
                <h3>Visual Tests</h3>
                <p>$(ls "$SCREENSHOTS_DIR"/*.png 2>/dev/null | wc -l || echo "0") screenshots captured</p>
            </div>
            <div class="metric">
                <h3>Accessibility Tests</h3>
                <p>$(grep -c "PASSED\|FAILED" "$OUTPUT_DIR/accessibility_results.txt" 2>/dev/null || echo "0") tests run</p>
            </div>
        </div>
    </div>

    <div class="section">
        <h2>⚡ Performance Results</h2>
        <pre>$(cat "$OUTPUT_DIR/performance_results.txt" 2>/dev/null || echo "No performance results available")</pre>
    </div>

    <div class="section">
        <h2>🖱️ UI Responsiveness Results</h2>
        <pre>$(cat "$OUTPUT_DIR/ui_results.txt" 2>/dev/null || echo "No UI results available")</pre>
    </div>

    <div class="section">
        <h2>♿ Accessibility Results</h2>
        <pre>$(cat "$OUTPUT_DIR/accessibility_results.txt" 2>/dev/null || echo "No accessibility results available")</pre>
    </div>

    <div class="section">
        <h2>📁 Test Artifacts</h2>
        <ul>
            <li><a href="visual-regression/visual_regression_report.html">Visual Regression Report</a></li>
            <li><a href="screenshots/">Screenshots Directory</a></li>
            <li><a href="performance_results.txt">Raw Performance Data</a></li>
            <li><a href="ui_results.txt">Raw UI Test Data</a></li>
        </ul>
    </div>
</body>
</html>
EOF

    echo -e "${GREEN}✅ Test report generated: $REPORT_FILE${NC}"
}

# Function to cleanup
cleanup() {
    log "🧹 Cleaning up..."

    # Re-enable animations
    adb shell settings put global window_animation_scale 1
    adb shell settings put global transition_animation_scale 1
    adb shell settings put global animator_duration_scale 1

    # Clean up device
    adb shell rm -rf /sdcard/screenshots

    echo -e "${GREEN}✅ Cleanup completed${NC}"
}

# Main execution
main() {
    log "Starting CleverKeys automated testing..."

    # Check prerequisites
    check_device

    # Build and install
    build_apks
    install_apks
    setup_keyboard

    # Run test suites
    run_performance_tests
    run_ui_tests
    run_visual_tests
    run_accessibility_tests

    # Process results
    compare_screenshots
    generate_report

    # Cleanup
    cleanup

    echo -e "${GREEN}🎉 All tests completed successfully!${NC}"
    echo -e "${BLUE}📊 View results: $OUTPUT_DIR/test_report.html${NC}"
}

# Handle command line arguments
case "${1:-}" in
    "performance")
        check_device && build_apks && install_apks && setup_keyboard && run_performance_tests
        ;;
    "ui")
        check_device && build_apks && install_apks && setup_keyboard && run_ui_tests
        ;;
    "visual")
        check_device && build_apks && install_apks && setup_keyboard && run_visual_tests && compare_screenshots
        ;;
    "accessibility")
        check_device && build_apks && install_apks && setup_keyboard && run_accessibility_tests
        ;;
    "clean")
        cleanup
        ;;
    *)
        main
        ;;
esac