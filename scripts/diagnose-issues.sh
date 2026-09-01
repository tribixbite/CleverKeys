#!/data/data/com.termux/files/usr/bin/bash
#
# CleverKeys Diagnostic Tool
# Comprehensive troubleshooting and log collection
#

set -e

# Help function
show_help() {
    cat << EOF
CleverKeys Diagnostic Tool

DESCRIPTION:
    Comprehensive diagnostic tool that collects system information, logs, and
    detects common issues. Generates a timestamped report file for troubleshooting.

USAGE:
    ./diagnose-issues.sh [OPTIONS]

OPTIONS:
    -h, --help      Show this help message and exit

EXAMPLES:
    ./diagnose-issues.sh              # Run full diagnostics
    ./diagnose-issues.sh --help       # Show this help

DIAGNOSTIC SECTIONS:
    1.  System Information (Android version, device, architecture)
    2.  APK Installation Status (package verification)
    3.  Build Information (version, last build time)
    4.  Keyboard Enablement (IME settings check)
    5.  Keyboard Activation (current IME check)
    6.  Permissions (required permissions status)
    7.  Recent Logs (last 100 lines filtered for CleverKeys)
    8.  Crash Detection (fatal errors in logs)
    9.  Storage Information (available space)
    10. Process Status (running processes)
    11. Common Issues Check (automated detection)

OUTPUT:
    Generates report file: cleverkeys-diagnostic-YYYYMMDD-HHMMSS.txt
    Report includes all diagnostic information for bug reporting.

EXIT CODES:
    0    Diagnostics completed successfully
    1    Diagnostics failed or errors encountered

NOTES:
    - Report files are automatically added to .gitignore
    - Safe to share reports (no sensitive personal information)
    - Include report when filing bug reports

EOF
    exit 0
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
    shift
done

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

REPORT_FILE="cleverkeys-diagnostic-$(date +%Y%m%d-%H%M%S).txt"

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                  CleverKeys - Diagnostic & Troubleshooting                 ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "This tool will:"
echo "  1. Check for common issues"
echo "  2. Collect relevant logs"
echo "  3. Verify system configuration"
echo "  4. Generate a diagnostic report"
echo ""
read -p "Press ENTER to start diagnostics..."
clear

exec > >(tee -a "$REPORT_FILE")
exec 2>&1

echo "════════════════════════════════════════════════════════════════════════════"
echo "CleverKeys Diagnostic Report"
echo "Generated: $(date)"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

# Section 1: System Information
echo "1. SYSTEM INFORMATION"
echo "────────────────────────────────────────────────────────────────────────────"
echo "Android Version:"
getprop ro.build.version.release
echo ""
echo "SDK Version:"
getprop ro.build.version.sdk
echo ""
echo "Device Model:"
getprop ro.product.model
echo ""
echo "Device Manufacturer:"
getprop ro.product.manufacturer
echo ""
echo "Available Memory:"
free -h
echo ""

# Section 2: APK Installation Status
echo "2. APK INSTALLATION STATUS"
echo "────────────────────────────────────────────────────────────────────────────"
if pm list packages | grep -q "tribixbite.cleverkeys.debug"; then
    echo -e "${GREEN}✅ INSTALLED${NC}"
    APK_PATH=$(pm path tribixbite.cleverkeys.debug | cut -d: -f2)
    echo "Package: tribixbite.cleverkeys.debug"
    echo "Path: $APK_PATH"

    if [ -f "$APK_PATH" ]; then
        echo "Size: $(du -h "$APK_PATH" | cut -f1)"
        echo "Last Modified: $(stat -c %y "$APK_PATH")"
    fi

    # Get app info
    echo ""
    echo "App Info:"
    dumpsys package tribixbite.cleverkeys.debug | grep -E "(versionName|versionCode|targetSdk|minSdk)" | head -4
else
    echo -e "${RED}❌ NOT INSTALLED${NC}"
    echo "CleverKeys APK is not installed on this device"
fi
echo ""

# Section 3: Permissions
echo "3. PERMISSIONS STATUS"
echo "────────────────────────────────────────────────────────────────────────────"
if pm list packages | grep -q "tribixbite.cleverkeys.debug"; then
    echo "Granted Permissions:"
    dumpsys package tribixbite.cleverkeys.debug | grep -A 20 "granted=true" | grep "android.permission" || echo "None found"
    echo ""
    echo "Requested Permissions:"
    dumpsys package tribixbite.cleverkeys.debug | grep "android.permission" | head -10 || echo "None found"
else
    echo "Cannot check permissions - APK not installed"
fi
echo ""

# Section 4: Keyboard Configuration
echo "4. KEYBOARD CONFIGURATION"
echo "────────────────────────────────────────────────────────────────────────────"
echo "Enabled Input Methods:"
ENABLED=$(settings get secure enabled_input_methods 2>/dev/null || echo "Permission denied")
if [[ "$ENABLED" == "Permission denied"* ]] || [[ -z "$ENABLED" ]]; then
    echo "⚠️  Cannot check (Termux permission limitation)"
    echo "Please verify manually in Settings"
else
    echo "$ENABLED" | tr ':' '\n' | grep -E "keyboard|input" || echo "None found"

    if echo "$ENABLED" | grep -q "tribixbite.cleverkeys"; then
        echo -e "\n${GREEN}✅ CleverKeys is in enabled keyboards list${NC}"
    else
        echo -e "\n${RED}❌ CleverKeys is NOT in enabled keyboards list${NC}"
    fi
fi
echo ""

echo "Current Default Keyboard:"
CURRENT=$(settings get secure default_input_method 2>/dev/null || echo "Permission denied")
if [[ "$CURRENT" == "Permission denied"* ]] || [[ -z "$CURRENT" ]]; then
    echo "⚠️  Cannot check (Termux permission limitation)"
else
    echo "$CURRENT"
    if echo "$CURRENT" | grep -q "tribixbite.cleverkeys"; then
        echo -e "${GREEN}✅ CleverKeys is currently active${NC}"
    else
        echo -e "${YELLOW}⚠️  CleverKeys is not the active keyboard${NC}"
    fi
fi
echo ""

# Section 5: Build Information
echo "5. BUILD INFORMATION"
echo "────────────────────────────────────────────────────────────────────────────"
if [ -f "build.gradle" ]; then
    echo "Project Build Configuration:"
    grep -E "(versionName|versionCode|targetSdk|minSdk|compileSdk)" build.gradle | head -10
else
    echo "⚠️  build.gradle not found (not in project directory)"
fi
echo ""

# Section 6: ONNX Models
echo "6. ONNX MODEL STATUS"
echo "────────────────────────────────────────────────────────────────────────────"
echo "Checking for ONNX model files..."
if [ -d "src/main/assets" ]; then
    find src/main/assets -name "*.onnx" -o -name "encoder.onnx" -o -name "decoder.onnx" 2>/dev/null || echo "No .onnx files found"
else
    echo "⚠️  assets directory not found"
fi
echo ""

# Section 7: Recent Logs
echo "7. RECENT APPLICATION LOGS (Last 100 lines)"
echo "────────────────────────────────────────────────────────────────────────────"
echo "Collecting logs related to CleverKeys..."
logcat -d -t 100 | grep -iE "(cleverkeys|tribixbite)" || echo "No recent logs found"
echo ""

# Section 8: Crash Logs
echo "8. CRASH DETECTION"
echo "────────────────────────────────────────────────────────────────────────────"
echo "Checking for crash logs..."
CRASHES=$(logcat -d | grep -i "FATAL" | grep -i "cleverkeys\|tribixbite" | tail -20)
if [ -z "$CRASHES" ]; then
    echo -e "${GREEN}✅ No crashes detected in recent logs${NC}"
else
    echo -e "${RED}❌ Crash logs found:${NC}"
    echo "$CRASHES"
fi
echo ""

# Section 9: Storage & Files
echo "9. STORAGE & FILE STATUS"
echo "────────────────────────────────────────────────────────────────────────────"
echo "APK File:"
# AGP names debug outputs "CleverKeys-v<versionName>-<abi>.apk" (build.gradle
# outputFileName), one per ABI split. Prefer arm64-v8a, else newest debug APK.
DEBUG_APK="$(ls -t build/outputs/apk/debug/CleverKeys-v*-arm64-v8a.apk build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
if [ -n "$DEBUG_APK" ]; then
    ls -lh "$DEBUG_APK"
else
    echo "⚠️  APK not found in build directory"
fi
echo ""

echo "Backup APK:"
if [ -f ~/storage/shared/CleverKeys-debug.apk ]; then
    ls -lh ~/storage/shared/CleverKeys-debug.apk
else
    echo "⚠️  Backup APK not found"
fi
echo ""

# Section 10: Common Issues Check
echo "10. COMMON ISSUES CHECK"
echo "────────────────────────────────────────────────────────────────────────────"

ISSUES_FOUND=0

# Check if APK is installed
if ! pm list packages | grep -q "tribixbite.cleverkeys.debug"; then
    echo -e "${RED}❌ ISSUE: APK not installed${NC}"
    echo "   Solution: Run ./gradlew assembleDebug && termux-open build/outputs/apk/debug/*.apk"
    ((ISSUES_FOUND++))
fi

# Check if enabled
if settings get secure enabled_input_methods 2>/dev/null | grep -q "tribixbite.cleverkeys"; then
    echo -e "${GREEN}✅ Keyboard is enabled${NC}"
else
    echo -e "${YELLOW}⚠️  POTENTIAL ISSUE: Cannot verify if keyboard is enabled${NC}"
    echo "   Solution: Open Settings → System → Languages & input → Manage keyboards"
    ((ISSUES_FOUND++))
fi

# Check if active
if settings get secure default_input_method 2>/dev/null | grep -q "tribixbite.cleverkeys"; then
    echo -e "${GREEN}✅ Keyboard is active${NC}"
else
    echo -e "${YELLOW}⚠️  POTENTIAL ISSUE: Keyboard may not be active${NC}"
    echo "   Solution: Open text app → Tap keyboard switcher → Select CleverKeys"
    ((ISSUES_FOUND++))
fi

# Check for crash logs
if logcat -d | grep -i "FATAL" | grep -iq "cleverkeys\|tribixbite"; then
    echo -e "${RED}❌ ISSUE: Crash logs detected${NC}"
    echo "   Solution: Check crash logs in Section 8 above"
    ((ISSUES_FOUND++))
fi

# Check ONNX models
if [ ! -d "src/main/assets" ] || ! find src/main/assets -name "*.onnx" 2>/dev/null | grep -q .; then
    echo -e "${YELLOW}⚠️  WARNING: ONNX model files may be missing${NC}"
    echo "   Impact: Swipe typing may not work"
    echo "   Note: This is a known limitation (documented in ASSET_FILES_NEEDED.md)"
fi

echo ""
echo "Total Issues Found: $ISSUES_FOUND"
echo ""

# Section 11: Summary & Recommendations
echo "════════════════════════════════════════════════════════════════════════════"
echo "DIAGNOSTIC SUMMARY"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

if [ $ISSUES_FOUND -eq 0 ]; then
    echo -e "${GREEN}✅ NO CRITICAL ISSUES DETECTED${NC}"
    echo ""
    echo "System appears to be configured correctly."
    echo "If you're experiencing problems:"
    echo "  1. Try restarting the app using the keyboard"
    echo "  2. Clear app data: Settings → Apps → CleverKeys → Clear Data"
    echo "  3. Reinstall the APK"
    echo "  4. Report the issue with this diagnostic report"
else
    echo -e "${YELLOW}⚠️  $ISSUES_FOUND POTENTIAL ISSUE(S) DETECTED${NC}"
    echo ""
    echo "Please review the issues listed in Section 10 above."
    echo "Follow the suggested solutions for each issue."
fi

echo ""
echo "════════════════════════════════════════════════════════════════════════════"
echo "REPORT SAVED TO: $REPORT_FILE"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📝 This report contains:"
echo "   • System information"
echo "   • Installation status"
echo "   • Permissions"
echo "   • Configuration"
echo "   • Recent logs"
echo "   • Crash detection"
echo "   • Common issues"
echo ""
echo "💡 To share this report:"
echo "   • Review it first: cat $REPORT_FILE"
echo "   • Copy to shared storage: cp $REPORT_FILE ~/storage/shared/"
echo "   • Share via: termux-share $REPORT_FILE"
echo ""
echo "🐛 When reporting a bug, attach this file!"
echo ""
