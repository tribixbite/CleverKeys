#!/bin/bash
# Run pure JVM tests locally using proot-distro Ubuntu
# These tests don't require Robolectric/Android emulator
#
# Usage: ./scripts/run-pure-tests.sh [TestClassName]
# Example: ./scripts/run-pure-tests.sh AccentNormalizerTest
#          ./scripts/run-pure-tests.sh  # runs all pure tests

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check proot-distro
if ! command -v proot-distro &>/dev/null; then
    echo -e "${RED}Error: proot-distro not found. Install with: pkg install proot-distro${NC}"
    exit 1
fi

# Ensure classes are compiled
echo -e "${YELLOW}Compiling test classes...${NC}"
cd "$PROJECT_DIR"
./gradlew compileDebugUnitTestKotlin -Pandroid.aapt2FromMavenOverride="/data/data/com.termux/files/usr/bin/aapt2" --no-daemon -q 2>/dev/null

if [ $? -ne 0 ]; then
    echo -e "${RED}Compilation failed${NC}"
    exit 1
fi

# List of pure JVM test classes (no Robolectric dependencies)
PURE_TESTS=(
    "tribixbite.cleverkeys.AccentNormalizerTest"
    "tribixbite.cleverkeys.VocabularyTrieTest"
)

# If specific test given, use that
if [ -n "$1" ]; then
    PURE_TESTS=("tribixbite.cleverkeys.$1")
fi

echo -e "${YELLOW}Running pure JVM tests in proot Ubuntu...${NC}"
echo ""

# Run tests in proot
proot-distro login ubuntu --shared-tmp --bind /data/data/com.termux/files/home:/home/termux -- bash -c "
JUNIT=/root/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar
HAMCREST=\$(find /root/.gradle/caches -name 'hamcrest-core-*.jar' 2>/dev/null | head -1)
TRUTH=\$(find /root/.gradle/caches -name 'truth-1.1.5.jar' 2>/dev/null | head -1)
GUAVA=\$(find /root/.gradle/caches -name 'guava-32.0.1-android.jar' 2>/dev/null | head -1)
FAILACCESS=\$(find /root/.gradle/caches -name 'failureaccess-*.jar' 2>/dev/null | head -1)
KOTLIN_STDLIB=\$(find /root/.gradle/caches -name 'kotlin-stdlib-2.0.0.jar' 2>/dev/null | head -1)
CLASSES=/home/termux/git/swype/cleverkeys/build/tmp/kotlin-classes/debug
TEST_CLASSES=/home/termux/git/swype/cleverkeys/build/tmp/kotlin-classes/debugUnitTest

CP=\"\$CLASSES:\$TEST_CLASSES:\$JUNIT:\$HAMCREST:\$TRUTH:\$GUAVA:\$FAILACCESS:\$KOTLIN_STDLIB\"

TESTS=\"${PURE_TESTS[*]}\"
TOTAL_PASSED=0
TOTAL_FAILED=0

for TEST in \$TESTS; do
    echo \"=== Running \$TEST ===\"
    OUTPUT=\$(java -cp \"\$CP\" org.junit.runner.JUnitCore \$TEST 2>&1)
    echo \"\$OUTPUT\" | grep -E '(OK|FAILURES|Time:|Tests run:)'

    if echo \"\$OUTPUT\" | grep -q 'OK'; then
        PASSED=\$(echo \"\$OUTPUT\" | grep -oP '\\(\\K[0-9]+(?= tests\\))')
        TOTAL_PASSED=\$((TOTAL_PASSED + PASSED))
    else
        FAILED=\$(echo \"\$OUTPUT\" | grep -oP 'Failures: \\K[0-9]+')
        TOTAL_FAILED=\$((TOTAL_FAILED + FAILED))
    fi
    echo ''
done

echo '================================'
echo \"Total: \$TOTAL_PASSED passed, \$TOTAL_FAILED failed\"
echo '================================'

if [ \$TOTAL_FAILED -gt 0 ]; then
    exit 1
fi
" 2>&1 | grep -v "Warning: CPU doesn't support" | grep -v "proot warning"

if [ ${PIPESTATUS[0]} -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
else
    echo -e "${RED}Some tests failed${NC}"
    exit 1
fi
