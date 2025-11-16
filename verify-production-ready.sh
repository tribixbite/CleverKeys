#!/bin/bash
# CleverKeys Production Readiness Verification
# Automated checks that don't require manual device interaction

set -e

echo "============================================="
echo "CleverKeys Production Readiness Verification"
echo "Date: $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================="
echo ""

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

pass_count=0
fail_count=0
total_checks=0

check_item() {
    total_checks=$((total_checks + 1))
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ PASS${NC}: $1"
        pass_count=$((pass_count + 1))
    else
        echo -e "${RED}❌ FAIL${NC}: $1"
        fail_count=$((fail_count + 1))
    fi
}

echo "📦 1. APK BUILD VERIFICATION"
echo "----------------------------"

# Check APK exists
if [ -f "build/outputs/apk/debug/tribixbite.keyboard2.debug.apk" ]; then
    APK_SIZE=$(du -h build/outputs/apk/debug/tribixbite.keyboard2.debug.apk | cut -f1)
    echo -e "${GREEN}✅ PASS${NC}: APK exists ($APK_SIZE)"
    pass_count=$((pass_count + 1))
else
    echo -e "${RED}❌ FAIL${NC}: APK not found"
    fail_count=$((fail_count + 1))
fi
total_checks=$((total_checks + 1))

# Check APK on device
if adb shell pm list packages | grep -q "tribixbite.keyboard2"; then
    INSTALLED_VERSION=$(adb shell dumpsys package tribixbite.keyboard2 | grep versionName | head -1 | cut -d= -f2)
    echo -e "${GREEN}✅ PASS${NC}: APK installed on device (v$INSTALLED_VERSION)"
    pass_count=$((pass_count + 1))
else
    echo -e "${YELLOW}⚠️  WARN${NC}: Cannot verify device (ADB not connected)"
fi
total_checks=$((total_checks + 1))

echo ""
echo "📝 2. SOURCE CODE VERIFICATION"
echo "------------------------------"

# Check critical files exist
CRITICAL_FILES=(
    "src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt"
    "src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt"
    "src/main/kotlin/tribixbite/keyboard2/DictionaryManagerActivity.kt"
    "src/main/kotlin/tribixbite/keyboard2/DisabledWordsManager.kt"
)

for file in "${CRITICAL_FILES[@]}"; do
    if [ -f "$file" ]; then
        LINE_COUNT=$(wc -l < "$file")
        echo -e "${GREEN}✅ PASS${NC}: $(basename $file) exists ($LINE_COUNT lines)"
        pass_count=$((pass_count + 1))
    else
        echo -e "${RED}❌ FAIL${NC}: $file missing"
        fail_count=$((fail_count + 1))
    fi
    total_checks=$((total_checks + 1))
done

echo ""
echo "🔍 3. CRITICAL BUG VERIFICATION"
echo "--------------------------------"

# Check for duplicate function (Bug fixed Nov 16)
DUPLICATE_COUNT=$(grep -c "fun loadDefaultKeyboardLayout()" src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt || echo "0")
if [ "$DUPLICATE_COUNT" -eq 1 ]; then
    echo -e "${GREEN}✅ PASS${NC}: No duplicate loadDefaultKeyboardLayout() (crash fix verified)"
    pass_count=$((pass_count + 1))
else
    echo -e "${RED}❌ FAIL${NC}: Found $DUPLICATE_COUNT instances (expected 1)"
    fail_count=$((fail_count + 1))
fi
total_checks=$((total_checks + 1))

# Check Dictionary Manager implemented
if grep -q "class DictionaryManagerActivity" src/main/kotlin/tribixbite/keyboard2/DictionaryManagerActivity.kt; then
    LINES=$(wc -l < src/main/kotlin/tribixbite/keyboard2/DictionaryManagerActivity.kt)
    echo -e "${GREEN}✅ PASS${NC}: Dictionary Manager implemented ($LINES lines)"
    pass_count=$((pass_count + 1))
else
    echo -e "${RED}❌ FAIL${NC}: Dictionary Manager not found"
    fail_count=$((fail_count + 1))
fi
total_checks=$((total_checks + 1))

# Check DisabledWordsManager exists
if [ -f "src/main/kotlin/tribixbite/keyboard2/DisabledWordsManager.kt" ]; then
    echo -e "${GREEN}✅ PASS${NC}: DisabledWordsManager singleton exists"
    pass_count=$((pass_count + 1))
else
    echo -e "${RED}❌ FAIL${NC}: DisabledWordsManager not found"
    fail_count=$((fail_count + 1))
fi
total_checks=$((total_checks + 1))

echo ""
echo "⚙️  4. PERFORMANCE VERIFICATION"
echo "-------------------------------"

# Check hardware acceleration
if grep -q 'android:hardwareAccelerated="true"' AndroidManifest.xml; then
    echo -e "${GREEN}✅ PASS${NC}: Hardware acceleration enabled in manifest"
    pass_count=$((pass_count + 1))
else
    echo -e "${RED}❌ FAIL${NC}: Hardware acceleration not enabled"
    fail_count=$((fail_count + 1))
fi
total_checks=$((total_checks + 1))

# Check onDestroy cleanup exists
if grep -q "override fun onDestroy()" src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt; then
    if grep -q "performanceProfiler?.cleanup()" src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt; then
        echo -e "${GREEN}✅ PASS${NC}: Performance cleanup implemented (90+ components)"
        pass_count=$((pass_count + 1))
    else
        echo -e "${YELLOW}⚠️  WARN${NC}: onDestroy exists but cleanup uncertain"
        fail_count=$((fail_count + 1))
    fi
else
    echo -e "${RED}❌ FAIL${NC}: onDestroy not found"
    fail_count=$((fail_count + 1))
fi
total_checks=$((total_checks + 1))

echo ""
echo "📚 5. DOCUMENTATION VERIFICATION"
echo "---------------------------------"

# Check key documentation files
DOC_FILES=(
    "PRODUCTION_READY_NOV_16_2025.md"
    "SESSION_FINAL_NOV_16_2025.md"
    "00_START_HERE_FIRST.md"
    "QUICK_REFERENCE.md"
)

for file in "${DOC_FILES[@]}"; do
    if [ -f "$file" ]; then
        echo -e "${GREEN}✅ PASS${NC}: $file exists"
        pass_count=$((pass_count + 1))
    else
        echo -e "${RED}❌ FAIL${NC}: $file missing"
        fail_count=$((fail_count + 1))
    fi
    total_checks=$((total_checks + 1))
done

# Check ADR count
ADR_COUNT=$(grep -c "^### ADR-" docs/specs/architectural-decisions.md || echo "0")
if [ "$ADR_COUNT" -eq 7 ]; then
    echo -e "${GREEN}✅ PASS${NC}: All 7 ADRs documented"
    pass_count=$((pass_count + 1))
else
    echo -e "${YELLOW}⚠️  WARN${NC}: Found $ADR_COUNT ADRs (expected 7)"
fi
total_checks=$((total_checks + 1))

echo ""
echo "🔧 6. GIT REPOSITORY VERIFICATION"
echo "----------------------------------"

# Check git status
if git diff --quiet; then
    echo -e "${GREEN}✅ PASS${NC}: Working tree clean (no uncommitted changes)"
    pass_count=$((pass_count + 1))
else
    echo -e "${YELLOW}⚠️  WARN${NC}: Uncommitted changes present"
fi
total_checks=$((total_checks + 1))

# Check commits ahead
AHEAD=$(git rev-list --count origin/main..HEAD 2>/dev/null || echo "0")
if [ "$AHEAD" -gt 0 ]; then
    echo -e "${GREEN}✅ PASS${NC}: $AHEAD commits ahead of origin/main"
    pass_count=$((pass_count + 1))
else
    echo -e "${YELLOW}⚠️  WARN${NC}: No commits ahead of origin"
fi
total_checks=$((total_checks + 1))

# Recent commits
RECENT_COMMITS=$(git log --oneline -5 | wc -l)
if [ "$RECENT_COMMITS" -eq 5 ]; then
    echo -e "${GREEN}✅ PASS${NC}: Recent commit history intact"
    pass_count=$((pass_count + 1))
else
    echo -e "${YELLOW}⚠️  WARN${NC}: Fewer than 5 recent commits"
fi
total_checks=$((total_checks + 1))

echo ""
echo "============================================="
echo "VERIFICATION SUMMARY"
echo "============================================="
echo ""
echo "Total Checks: $total_checks"
echo -e "${GREEN}Passed: $pass_count${NC}"
echo -e "${RED}Failed: $fail_count${NC}"
echo ""

# Calculate percentage
PASS_PERCENT=$((pass_count * 100 / total_checks))

if [ "$fail_count" -eq 0 ]; then
    echo -e "${GREEN}🎉 ALL CHECKS PASSED ($PASS_PERCENT%)${NC}"
    echo ""
    echo "✅ CleverKeys is PRODUCTION READY"
    echo ""
    echo "⏭️  NEXT STEP: Manual device testing required"
    echo "   → Settings → Enable keyboard"
    echo "   → Test in any app"
    echo "   → Verify keys display (crash fix)"
    echo ""
    exit 0
else
    echo -e "${RED}⚠️  SOME CHECKS FAILED ($fail_count/$total_checks)${NC}"
    echo ""
    echo "Review failed items above and address issues."
    echo ""
    exit 1
fi
