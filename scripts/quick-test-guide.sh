#!/data/data/com.termux/files/usr/bin/bash
#
# CleverKeys Quick Test Guide
# Interactive guide for 5 essential tests
#

set -e

# Help function
show_help() {
    cat << EOF
CleverKeys Quick Test Guide

DESCRIPTION:
    Interactive guide through 5 essential tests to verify CleverKeys functionality.
    Tests: Basic typing, predictions, swipe, autocorrect, design.

USAGE:
    ./quick-test-guide.sh [OPTIONS]

OPTIONS:
    -h, --help      Show this help message and exit

EXAMPLES:
    ./quick-test-guide.sh              # Run interactive test guide
    ./quick-test-guide.sh --help       # Show this help

PREREQUISITE:
    CleverKeys must be enabled and selected as the active keyboard.
    Open any text app and select CleverKeys before running this script.

TESTS COVERED:
    1. Basic Typing - Type "hello world"
    2. Predictions - Type "th" and check suggestions
    3. Swipe Typing - Swipe h→e→l→l→o
    4. Autocorrect - Type "teh " and check correction
    5. Design - Verify Material 3 theme

EXIT CODES:
    0    All tests passed
    1    Some tests failed

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

clear

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                    CleverKeys - Quick Test Guide (5 tests)                 ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "This guide will walk you through 5 essential tests to verify CleverKeys works."
echo ""
echo -e "${YELLOW}⚠️  PREREQUISITE:${NC} CleverKeys must be enabled and selected as active keyboard"
echo ""
read -p "Press ENTER when you have CleverKeys active in a text app..."
clear

# Test 1: Basic Typing
echo "════════════════════════════════════════════════════════════════════════════"
echo -e "${CYAN}TEST 1/5: Basic Typing${NC}"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📝 TASK: Tap individual keys to type 'hello world'"
echo ""
echo "🎯 EXPECTED:"
echo "   • Characters should appear as you tap"
echo "   • Suggestion bar at top should show predictions"
echo "   • Keyboard should feel responsive"
echo ""
echo "🧪 ACTION: Go to your text app and type: hello world"
echo ""
read -p "Did characters appear correctly? (y/n): " test1

if [[ "$test1" =~ ^[Yy]$ ]]; then
    echo -e "${GREEN}✅ TEST 1 PASSED${NC}"
    TESTS_PASSED=1
else
    echo -e "${RED}❌ TEST 1 FAILED${NC}"
    echo "   🐛 BUG: Characters not appearing"
    echo "   📝 Note this for bug report"
    TESTS_PASSED=0
fi

echo ""
read -p "Press ENTER to continue to Test 2..."
clear

# Test 2: Predictions
echo "════════════════════════════════════════════════════════════════════════════"
echo -e "${CYAN}TEST 2/5: Word Predictions${NC}"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📝 TASK: Type just 'th' and observe suggestion bar"
echo ""
echo "🎯 EXPECTED:"
echo "   • Suggestion bar should show: 'the', 'that', 'this' (or similar)"
echo "   • Predictions should update as you type"
echo "   • Tapping a suggestion should insert that word"
echo ""
echo "🧪 ACTION: Type: th"
echo "   Then look at suggestion bar at top of keyboard"
echo ""
read -p "Did you see relevant predictions like 'the', 'that', 'this'? (y/n): " test2

if [[ "$test2" =~ ^[Yy]$ ]]; then
    echo -e "${GREEN}✅ TEST 2 PASSED${NC}"
    ((TESTS_PASSED++))
else
    echo -e "${RED}❌ TEST 2 FAILED${NC}"
    echo "   🐛 BUG: Predictions not showing or incorrect"
    echo "   📝 Note this for bug report"
fi

echo ""
read -p "Press ENTER to continue to Test 3..."
clear

# Test 3: Swipe Typing
echo "════════════════════════════════════════════════════════════════════════════"
echo -e "${CYAN}TEST 3/5: Swipe Typing (CTC Prediction)${NC}"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📝 TASK: Swipe your finger to spell 'hello'"
echo ""
echo "🎯 EXPECTED:"
echo "   • Visual trail should follow your finger"
echo "   • Word 'hello' should appear when you lift finger"
echo "   • May take 1-2 seconds on first swipe (ONNX model loading)"
echo ""
echo "🧪 ACTION: Place finger on 'h', swipe through 'e', 'l', 'l', 'o', then lift"
echo "   Keep finger down during entire swipe"
echo ""
echo -e "${YELLOW}💡 TIP: Swipe smoothly without lifting finger${NC}"
echo ""
read -p "Did 'hello' appear when you finished swiping? (y/n): " test3

if [[ "$test3" =~ ^[Yy]$ ]]; then
    echo -e "${GREEN}✅ TEST 3 PASSED${NC}"
    ((TESTS_PASSED++))
else
    echo -e "${RED}❌ TEST 3 FAILED${NC}"
    echo "   🐛 BUG: Swipe typing not working"
    read -p "   Did you see a visual trail following your finger? (y/n): " trail
    if [[ ! "$trail" =~ ^[Yy]$ ]]; then
        echo "   📝 Note: No visual trail - gesture detection may be broken"
    else
        echo "   📝 Note: Trail present but no prediction - ONNX model issue"
    fi
fi

echo ""
read -p "Press ENTER to continue to Test 4..."
clear

# Test 4: Autocorrection
echo "════════════════════════════════════════════════════════════════════════════"
echo -e "${CYAN}TEST 4/5: Autocorrection${NC}"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📝 TASK: Type a common typo 'teh' followed by space"
echo ""
echo "🎯 EXPECTED:"
echo "   • When you press space, 'teh' should autocorrect to 'the'"
echo "   • Correction should happen automatically"
echo ""
echo "🧪 ACTION: Type: teh "
echo "   (Make sure to add a space after 'teh')"
echo ""
echo -e "${YELLOW}💡 TIP: The space triggers autocorrection${NC}"
echo ""
read -p "Did 'teh' autocorrect to 'the' when you pressed space? (y/n): " test4

if [[ "$test4" =~ ^[Yy]$ ]]; then
    echo -e "${GREEN}✅ TEST 4 PASSED${NC}"
    ((TESTS_PASSED++))
else
    echo -e "${RED}❌ TEST 4 FAILED${NC}"
    echo "   🐛 BUG: Autocorrection not working"
    echo "   📝 Note this for bug report"
fi

echo ""
read -p "Press ENTER to continue to Test 5 (final test)..."
clear

# Test 5: Material 3 Design
echo "════════════════════════════════════════════════════════════════════════════"
echo -e "${CYAN}TEST 5/5: Material 3 UI Design${NC}"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📝 TASK: Observe keyboard appearance and animations"
echo ""
echo "🎯 EXPECTED:"
echo "   • Keys should have rounded corners (Material 3 style)"
echo "   • Smooth animations when pressing keys"
echo "   • Clear visual feedback on key press"
echo "   • Modern, clean appearance"
echo ""
echo "🧪 ACTION: Look at the keyboard and press a few keys"
echo "   Observe the design and animations"
echo ""
read -p "Does the keyboard have a modern design with rounded corners and smooth animations? (y/n): " test5

if [[ "$test5" =~ ^[Yy]$ ]]; then
    echo -e "${GREEN}✅ TEST 5 PASSED${NC}"
    ((TESTS_PASSED++))
else
    echo -e "${RED}❌ TEST 5 FAILED${NC}"
    echo "   🐛 BUG: UI doesn't match Material 3 design"
    echo "   📝 Note this for bug report"
fi

echo ""
read -p "Press ENTER to see test results..."
clear

# Final Results
echo "════════════════════════════════════════════════════════════════════════════"
echo "                             TEST RESULTS"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo -e "Tests Passed: ${TESTS_PASSED}/5"
echo ""

if [ $TESTS_PASSED -eq 5 ]; then
    echo -e "${GREEN}🎉 PERFECT! All tests passed!${NC}"
    echo ""
    echo "CleverKeys is working correctly. You can now:"
    echo "   • Use it as your daily keyboard"
    echo "   • Try advanced features (see QUICK_REFERENCE.md)"
    echo "   • Run comprehensive tests (see MANUAL_TESTING_GUIDE.md)"
    echo ""
    echo "Report back: 'It works! All 5 tests passed!'"
elif [ $TESTS_PASSED -ge 3 ]; then
    echo -e "${YELLOW}⚠️  MOSTLY WORKING (${TESTS_PASSED}/5 passed)${NC}"
    echo ""
    echo "Some features are working, but there are issues."
    echo ""
    echo "📝 NEXT STEPS:"
    echo "   1. Review which tests failed"
    echo "   2. Try them again (sometimes first attempt has issues)"
    echo "   3. If still failing, report bugs with details"
    echo ""
    echo "Report format:"
    echo "   'Results: ${TESTS_PASSED}/5 tests passed'"
    echo "   'Failed: [list failed test numbers]'"
    echo "   'Details: [what happened]'"
elif [ $TESTS_PASSED -ge 1 ]; then
    echo -e "${RED}❌ MAJOR ISSUES (${TESTS_PASSED}/5 passed)${NC}"
    echo ""
    echo "CleverKeys has significant problems that need fixing."
    echo ""
    echo "📝 REPORT BUGS:"
    echo "   Copy test results and describe what happened:"
    echo "   'Results: ${TESTS_PASSED}/5 tests passed'"
    echo "   'Failed: [list failed test numbers and what happened]'"
else
    echo -e "${RED}❌ CRITICAL FAILURE (0/5 passed)${NC}"
    echo ""
    echo "CleverKeys is not functioning at all."
    echo ""
    echo "📝 EMERGENCY DEBUG:"
    echo "   1. Verify keyboard is actually enabled (not just selected)"
    echo "   2. Try restarting the app using the keyboard"
    echo "   3. Check logcat for errors: logcat | grep CleverKeys"
    echo "   4. Report critical bug with full details"
fi

echo ""
echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📖 Resources:"
echo "   • Feature guide: QUICK_REFERENCE.md"
echo "   • Full testing: MANUAL_TESTING_GUIDE.md"
echo "   • Troubleshooting: INSTALLATION_STATUS.md"
echo "   • All docs: INDEX.md"
echo ""
echo "🐛 Found bugs? Report them!"
echo "✅ Everything works? Let me know!"
echo ""
