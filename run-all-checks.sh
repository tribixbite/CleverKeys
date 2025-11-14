#!/data/data/com.termux/files/usr/bin/bash
#
# CleverKeys - Complete Verification Suite
# Runs all checking and testing tools in sequence
#

set -e

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

clear

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║              CleverKeys - Complete Verification Suite                      ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "This suite will run all verification and testing tools:"
echo ""
echo "  1. Status Check (check-keyboard-status.sh)"
echo "     → Verify installation, enablement, and activation"
echo ""
echo "  2. Diagnostic Scan (diagnose-issues.sh)"
echo "     → Comprehensive system check and log collection"
echo ""
echo "  3. Guided Testing (quick-test-guide.sh)"
echo "     → Interactive 5-test suite (only if keyboard is ready)"
echo ""
echo -e "${YELLOW}⏱️  Estimated time: 5-15 minutes depending on tests${NC}"
echo ""
read -p "Press ENTER to start, or Ctrl+C to cancel..."
clear

# Step 1: Status Check
echo "════════════════════════════════════════════════════════════════════════════"
echo "STEP 1/3: Running Status Check"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

if [ -f "./check-keyboard-status.sh" ]; then
    ./check-keyboard-status.sh
    STATUS_RESULT=$?
else
    echo -e "${RED}❌ ERROR: check-keyboard-status.sh not found${NC}"
    exit 1
fi

echo ""
read -p "Status check complete. Press ENTER to continue to diagnostics..."
clear

# Step 2: Diagnostic Scan
echo "════════════════════════════════════════════════════════════════════════════"
echo "STEP 2/3: Running Diagnostic Scan"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "This will collect system info, logs, and check for common issues."
echo "A diagnostic report will be saved for bug reporting if needed."
echo ""

if [ -f "./diagnose-issues.sh" ]; then
    ./diagnose-issues.sh
    DIAG_RESULT=$?
else
    echo -e "${RED}❌ ERROR: diagnose-issues.sh not found${NC}"
    exit 1
fi

echo ""
read -p "Diagnostics complete. Press ENTER to continue..."
clear

# Step 3: Guided Testing (conditional)
echo "════════════════════════════════════════════════════════════════════════════"
echo "STEP 3/3: Guided Testing"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "The guided test will walk you through 5 essential tests."
echo ""
echo -e "${YELLOW}⚠️  PREREQUISITE: CleverKeys must be enabled and active${NC}"
echo ""
echo "If CleverKeys is not yet enabled:"
echo "  • Skip this step (press 'n')"
echo "  • Enable keyboard in Settings"
echo "  • Run this script again or run quick-test-guide.sh directly"
echo ""
read -p "Is CleverKeys enabled and active? (y/n): " KEYBOARD_READY

if [[ "$KEYBOARD_READY" =~ ^[Yy]$ ]]; then
    clear
    if [ -f "./quick-test-guide.sh" ]; then
        ./quick-test-guide.sh
        TEST_RESULT=$?
    else
        echo -e "${RED}❌ ERROR: quick-test-guide.sh not found${NC}"
        exit 1
    fi
else
    echo ""
    echo -e "${YELLOW}⚠️  Skipping guided tests${NC}"
    echo ""
    echo "📝 Next steps:"
    echo "  1. Enable CleverKeys in Settings"
    echo "  2. Open a text app and select CleverKeys"
    echo "  3. Run: ./quick-test-guide.sh"
    TEST_RESULT=255  # Not run
fi

# Final Summary
clear
echo "════════════════════════════════════════════════════════════════════════════"
echo "COMPLETE VERIFICATION SUITE - SUMMARY"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

echo "Results:"
echo ""
echo "1. Status Check: "
if [ $STATUS_RESULT -eq 0 ]; then
    echo -e "   ${GREEN}✅ Completed successfully${NC}"
else
    echo -e "   ${YELLOW}⚠️  Completed with warnings${NC}"
fi

echo ""
echo "2. Diagnostic Scan: "
if [ $DIAG_RESULT -eq 0 ]; then
    echo -e "   ${GREEN}✅ Completed successfully${NC}"
    # Find the diagnostic report
    REPORT=$(ls -t cleverkeys-diagnostic-*.txt 2>/dev/null | head -1)
    if [ ! -z "$REPORT" ]; then
        echo "   📄 Report saved: $REPORT"
    fi
else
    echo -e "   ${YELLOW}⚠️  Completed with warnings${NC}"
fi

echo ""
echo "3. Guided Testing: "
if [ $TEST_RESULT -eq 255 ]; then
    echo -e "   ${YELLOW}⏭️  Skipped (keyboard not ready)${NC}"
elif [ $TEST_RESULT -eq 0 ]; then
    echo -e "   ${GREEN}✅ Completed${NC}"
else
    echo -e "   ${RED}❌ Completed with failures${NC}"
fi

echo ""
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

# Recommendations based on results
if [ $TEST_RESULT -eq 255 ]; then
    echo "📝 NEXT STEPS:"
    echo ""
    echo "Since guided testing was skipped:"
    echo "  1. Enable CleverKeys in Settings:"
    echo "     Settings → System → Languages & input → Manage keyboards"
    echo ""
    echo "  2. Open a text app and select CleverKeys from keyboard switcher"
    echo ""
    echo "  3. Run guided tests:"
    echo "     $ ./quick-test-guide.sh"
    echo ""
elif [ $TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}🎉 EXCELLENT! All checks and tests passed!${NC}"
    echo ""
    echo "CleverKeys is working correctly. You can now:"
    echo "  • Use it as your daily keyboard"
    echo "  • Try advanced features (see QUICK_REFERENCE.md)"
    echo "  • Run comprehensive tests (see MANUAL_TESTING_GUIDE.md)"
    echo ""
else
    echo -e "${YELLOW}⚠️  ISSUES DETECTED${NC}"
    echo ""
    echo "Some tests failed. Please:"
    echo "  1. Review the test results above"
    echo "  2. Check the diagnostic report for details"
    echo "  3. Try solutions suggested in the diagnostic"
    echo "  4. If problems persist, report a bug with the diagnostic file"
    echo ""
fi

echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📖 Documentation:"
echo "   • Quick Start: 00_START_HERE_FIRST.md"
echo "   • Cheat Sheet: QUICK_REFERENCE.md"
echo "   • Troubleshooting: INSTALLATION_STATUS.md"
echo "   • All Files: INDEX.md"
echo ""
echo "🛠️  Individual Tools:"
echo "   • Status only: ./check-keyboard-status.sh"
echo "   • Diagnostics only: ./diagnose-issues.sh"
echo "   • Testing only: ./quick-test-guide.sh"
echo "   • All together: ./run-all-checks.sh (this script)"
echo ""
echo "🐛 Found bugs? Report with diagnostic file!"
echo "✅ Everything works? Let me know!"
echo ""
