#!/data/data/com.termux/files/usr/bin/bash
# CleverKeys Production Readiness Verification
#
# Cheap, offline structural checks — "is this tree shaped like something we could
# ship?". It is NOT a test suite: correctness gates are `scripts/gradle-guard.sh
# runPureTests`, the ew-cli instrumented suite, and `lintVitalRelease`.
#
# Rewritten 2026-09-01 (ARC-098). Every assertion below was re-derived from live
# code; the previous version asserted against a `tribixbite/keyboard2/` source
# tree that never existed, a `DisabledWordsManager.kt` and a
# `loadDefaultKeyboardLayout()` that do not exist, a `performanceProfiler?.cleanup()`
# call replaced by CleanupHandler, four deleted root markdown files, and an ADR
# count of 7 when there are 11 — i.e. it could only ever report failure.

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

pass() {
    echo -e "${GREEN}✅ PASS${NC}: $1"
    pass_count=$((pass_count + 1))
    total_checks=$((total_checks + 1))
}

fail() {
    echo -e "${RED}❌ FAIL${NC}: $1"
    fail_count=$((fail_count + 1))
    total_checks=$((total_checks + 1))
}

warn() {
    echo -e "${YELLOW}⚠️  WARN${NC}: $1"
    total_checks=$((total_checks + 1))
}

echo "📦 1. APK BUILD VERIFICATION"
echo "----------------------------"

# AGP names outputs "CleverKeys-v<versionName>-<abi>.apk" (build.gradle
# outputFileName), one per ABI split — there is no single fixed filename.
RELEASE_APK="$(ls -t build/outputs/apk/release/CleverKeys-v*.apk 2>/dev/null | head -1)"
DEBUG_APK="$(ls -t build/outputs/apk/debug/CleverKeys-v*.apk 2>/dev/null | head -1)"
APK="${RELEASE_APK:-$DEBUG_APK}"

if [ -n "$APK" ]; then
    pass "APK exists: $APK ($(du -h "$APK" | cut -f1))"
else
    fail "No APK in build/outputs/apk/{release,debug}/ — run ./build-on-termux.sh"
fi

# APK payload: the shipped CTC encoder and the bundled English lexicon must be
# packaged. (Folded in from the deleted scripts/test-runtime.sh, whose version of
# this check still looked for the ADR-011-removed neural encoder/decoder pair.)
if [ -n "$APK" ] && command -v unzip >/dev/null 2>&1; then
    if unzip -l "$APK" | grep -q "assets/models/ctc_swipe_encoder.onnx"; then
        pass "CTC encoder packaged (assets/models/ctc_swipe_encoder.onnx)"
    else
        fail "CTC encoder missing from APK"
    fi

    if unzip -l "$APK" | grep -q "assets/dictionaries/en_enhanced.bin"; then
        pass "English lexicon packaged (assets/dictionaries/en_enhanced.bin)"
    else
        fail "English lexicon missing from APK"
    fi
else
    warn "Skipping APK payload check (no APK, or unzip unavailable)"
fi

echo ""
echo "📝 2. SOURCE CODE VERIFICATION"
echo "------------------------------"

# The R4 reorg moved *Activity.kt into activities/ WITHOUT changing packages, so
# these must be addressed by repo path, not by an `.activities.` FQCN.
CRITICAL_FILES=(
    "src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt"
    "src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt"
    "src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt"
    "src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt"
    "src/main/kotlin/tribixbite/cleverkeys/activities/DictionaryManagerActivity.kt"
    "src/main/kotlin/tribixbite/cleverkeys/swipe/SwipeEngineRouter.kt"
    "src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcBeamDecoder.kt"
)

for file in "${CRITICAL_FILES[@]}"; do
    if [ -f "$file" ]; then
        pass "$(basename "$file") exists ($(wc -l < "$file") lines)"
    else
        fail "$file missing"
    fi
done

echo ""
echo "🔍 3. WIRING VERIFICATION"
echo "--------------------------"

# Both swipe engines must be reachable from the router (ADR-011 left exactly two).
if grep -q "GEOMETRIC" src/main/kotlin/tribixbite/cleverkeys/swipe/SwipeEngineRouter.kt &&
   grep -q "CTC" src/main/kotlin/tribixbite/cleverkeys/swipe/SwipeEngineRouter.kt; then
    pass "SwipeEngineRouter exposes both engines (GEOMETRIC + CTC)"
else
    fail "SwipeEngineRouter does not expose both engines"
fi

# ADR-011 deleted the neural engine's implementation files. Assert file absence
# rather than grepping for the name — surviving KDoc that says "extracted from
# OnnxSwipePredictor" is provenance, not residue. Semantic ADR-011 invariants are
# pinned by DeadPlumbingDriftTest, not by this script.
NEURAL_RESIDUE=0
for f in \
    src/main/kotlin/tribixbite/cleverkeys/OnnxSwipePredictorImpl.kt \
    src/main/kotlin/tribixbite/cleverkeys/onnx/BeamSearchEngine.kt \
    src/main/kotlin/tribixbite/cleverkeys/onnx/DecoderWrapper.kt \
    src/main/kotlin/tribixbite/cleverkeys/onnx/EncoderWrapper.kt
do
    [ -f "$f" ] && { echo "   residue: $f"; NEURAL_RESIDUE=1; }
done
if [ "$NEURAL_RESIDUE" -eq 0 ]; then
    pass "Neural-engine implementation files absent (ADR-011)"
else
    fail "Neural-engine implementation files present (ADR-011 removed them)"
fi

# Service teardown must exist and route through CleanupHandler.
if grep -q "override fun onDestroy()" src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt; then
    if grep -q "CleanupHandler" src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt; then
        pass "Service teardown wired (onDestroy → CleanupHandler)"
    else
        fail "onDestroy exists but does not call CleanupHandler"
    fi
else
    fail "CleverKeysService.onDestroy not found"
fi

echo ""
echo "⚙️  4. MANIFEST VERIFICATION"
echo "-------------------------------"

if grep -q 'android:hardwareAccelerated="true"' AndroidManifest.xml; then
    pass "Hardware acceleration enabled in manifest"
else
    fail "Hardware acceleration not enabled"
fi

# No INTERNET permission — a hard product invariant (GIF/dict packs are SAF imports).
if grep -q 'android.permission.INTERNET' AndroidManifest.xml; then
    fail "INTERNET permission present — the app must stay offline"
else
    pass "No INTERNET permission (offline invariant holds)"
fi

if grep -q 'tribixbite.cleverkeys.CleverKeysService' AndroidManifest.xml; then
    pass "IME service declared (tribixbite.cleverkeys.CleverKeysService)"
else
    fail "IME service not declared in manifest"
fi

echo ""
echo "📚 5. DOCUMENTATION VERIFICATION"
echo "---------------------------------"

DOC_FILES=(
    "README.md"
    "CONTRIBUTING.md"
    "CLAUDE.md"
    "docs/TABLE_OF_CONTENTS.md"
    "docs/specs/architectural-decisions.md"
    "memory/HANDOFF.md"
)

for file in "${DOC_FILES[@]}"; do
    if [ -f "$file" ]; then
        pass "$file exists"
    else
        fail "$file missing"
    fi
done

# ADR count is informational — it grows. Only an unreadable/empty file is a failure.
ADR_COUNT=$(grep -c "^## ADR-" docs/specs/architectural-decisions.md || echo "0")
if [ "$ADR_COUNT" -gt 0 ]; then
    pass "$ADR_COUNT ADRs documented"
else
    fail "No ADRs parsed from docs/specs/architectural-decisions.md"
fi

echo ""
echo "🔧 6. GIT REPOSITORY VERIFICATION"
echo "----------------------------------"

if git diff --quiet; then
    pass "Working tree clean (no unstaged changes)"
else
    warn "Uncommitted changes present"
fi

if [ -z "$(git ls-files --others --exclude-standard)" ]; then
    pass "No untracked files"
else
    warn "Untracked files present: $(git ls-files --others --exclude-standard | wc -l)"
fi

echo ""
echo "============================================="
echo "VERIFICATION SUMMARY"
echo "============================================="
echo ""
echo "Total Checks: $total_checks"
echo -e "${GREEN}Passed: $pass_count${NC}"
echo -e "${RED}Failed: $fail_count${NC}"
echo ""

if [ "$fail_count" -eq 0 ]; then
    echo -e "${GREEN}🎉 ALL STRUCTURAL CHECKS PASSED${NC}"
    echo ""
    echo "⏭️  These are structural checks only. Before shipping, still run:"
    echo "   scripts/gradle-guard.sh runPureTests"
    echo "   scripts/gradle-guard.sh lintVitalRelease"
    echo "   the ew-cli instrumented suite"
    echo ""
    exit 0
else
    echo -e "${RED}⚠️  SOME CHECKS FAILED ($fail_count/$total_checks)${NC}"
    echo ""
    echo "Review failed items above and address issues."
    echo ""
    exit 1
fi
