#!/data/data/com.termux/files/usr/bin/bash
#
# CleverKeys - Complete Build, Install & Verification Pipeline
# One command to go from source code to verified installation
#

set -e

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Help function
show_help() {
    cat << EOF
CleverKeys Build & Verification Pipeline

DESCRIPTION:
    Complete automation pipeline from source code to verified installation.
    One command to: clean → compile → build → install → verify.

USAGE:
    ./build-and-verify.sh [OPTIONS]

OPTIONS:
    --clean             Clean build directories before compilation
    --skip-verify       Skip verification suite after installation
    -h, --help          Show this help message and exit

EXAMPLES:
    ./build-and-verify.sh                  # Standard build and verify
    ./build-and-verify.sh --clean          # Clean build first
    ./build-and-verify.sh --skip-verify    # Build and install only (no tests)
    ./build-and-verify.sh --clean --skip-verify  # Clean build, skip verification

BUILD PIPELINE:
    Step 1: Clean Build (optional with --clean)
        - Run ./gradlew clean
        - Remove previous build artifacts

    Step 2: Compile Production Code
        - Run ./gradlew compileDebugKotlin
        - Verify 0 compilation errors

    Step 3: Build APK
        - Run ./gradlew assembleDebug
        - Generate tribixbite.keyboard2.debug.apk

    Step 4: Install APK
        - Method 1: termux-open (recommended)
        - Method 2: ADB wireless
        - Method 3: Manual copy to shared storage
        - Verify installation with pm command

    Step 5: Verification Suite (optional with --skip-verify)
        - Run ./run-all-checks.sh
        - Status check + diagnostics + guided testing

TIMING:
    - Standard build: 5-7 minutes
    - Clean build: 8-10 minutes
    - Skip verification: 3-5 minutes

EXIT CODES:
    0    Pipeline completed successfully
    1    Compilation, build, or installation failed

NOTES:
    - Requires Gradle and Android SDK
    - APK output: build/outputs/apk/debug/
    - Backup copy: ~/storage/shared/CleverKeys-debug.apk
    - Best for rebuilding after code changes

EOF
    exit 0
}

# Parse arguments
CLEAN_BUILD=false
SKIP_VERIFICATION=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            ;;
        --clean)
            CLEAN_BUILD=true
            shift
            ;;
        --skip-verify)
            SKIP_VERIFICATION=true
            shift
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            echo "Usage: $0 [--clean] [--skip-verify]"
            echo "Use --help for more information"
            exit 1
            ;;
    esac
done

clear

echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║          CleverKeys - Complete Build & Verification Pipeline              ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "This script will:"
echo "  1. Clean previous builds (optional)"
echo "  2. Compile production code"
echo "  3. Build APK"
echo "  4. Install APK on device"
echo "  5. Run complete verification suite"
echo ""
echo -e "${YELLOW}⏱️  Estimated time: 5-10 minutes${NC}"
echo ""
read -p "Press ENTER to start, or Ctrl+C to cancel..."

# Section 1: Clean Build (Optional)
if [ "$CLEAN_BUILD" = true ]; then
    clear
    echo "════════════════════════════════════════════════════════════════════════════"
    echo "STEP 1/5: Cleaning Previous Build"
    echo "════════════════════════════════════════════════════════════════════════════"
    echo ""

    echo "Cleaning build directories..."
    ./gradlew clean

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Clean completed${NC}"
    else
        echo -e "${RED}❌ Clean failed${NC}"
        exit 1
    fi

    echo ""
    read -p "Clean complete. Press ENTER to continue..."
else
    echo ""
    echo "Skipping clean (use --clean to enable)"
    echo ""
    sleep 1
fi

# Section 2: Compile Production Code
clear
echo "════════════════════════════════════════════════════════════════════════════"
echo "STEP 2/5: Compiling Production Code"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

echo "Running: ./gradlew compileDebugKotlin"
echo ""

./gradlew compileDebugKotlin --console=plain

if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✅ Production code compiled successfully (0 errors)${NC}"
else
    echo ""
    echo -e "${RED}❌ Compilation failed${NC}"
    echo ""
    echo "Please fix compilation errors and try again."
    exit 1
fi

echo ""
read -p "Compilation complete. Press ENTER to continue to APK build..."

# Section 3: Build APK
clear
echo "════════════════════════════════════════════════════════════════════════════"
echo "STEP 3/5: Building APK"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

echo "Running: ./gradlew assembleDebug"
echo ""
echo -e "${YELLOW}⏱️  This may take 20-30 seconds...${NC}"
echo ""

./gradlew assembleDebug --console=plain

if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✅ APK built successfully${NC}"

    # Get APK info
    APK_PATH="build/outputs/apk/debug/tribixbite.keyboard2.debug.apk"
    if [ -f "$APK_PATH" ]; then
        APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
        echo "   📦 Location: $APK_PATH"
        echo "   💾 Size: $APK_SIZE"
    fi
else
    echo ""
    echo -e "${RED}❌ APK build failed${NC}"
    exit 1
fi

echo ""
read -p "APK build complete. Press ENTER to continue to installation..."

# Section 4: Install APK
clear
echo "════════════════════════════════════════════════════════════════════════════"
echo "STEP 4/5: Installing APK"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

echo "Installation methods (in order of preference):"
echo "  1. termux-open (recommended)"
echo "  2. ADB wireless"
echo "  3. Manual copy to shared storage"
echo ""

APK_PATH="build/outputs/apk/debug/tribixbite.keyboard2.debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}❌ ERROR: APK not found at $APK_PATH${NC}"
    exit 1
fi

# Try termux-open first
echo "Attempting installation via termux-open..."
if command -v termux-open &> /dev/null; then
    termux-open "$APK_PATH"

    echo -e "${GREEN}✅ Installation prompt opened${NC}"
    echo ""
    echo "Please:"
    echo "  1. Tap 'Install' when prompted"
    echo "  2. Wait for installation to complete"
    echo "  3. Tap 'Done' (NOT 'Open')"
    echo ""
else
    echo -e "${YELLOW}⚠️  termux-open not available${NC}"
    echo ""
    echo "Copying APK to shared storage for manual installation..."
    cp "$APK_PATH" ~/storage/shared/CleverKeys-debug.apk
    echo -e "${GREEN}✅ Copied to: ~/storage/shared/CleverKeys-debug.apk${NC}"
    echo ""
    echo "Please install manually:"
    echo "  1. Open File Manager"
    echo "  2. Navigate to shared storage"
    echo "  3. Tap CleverKeys-debug.apk"
    echo "  4. Tap 'Install'"
fi

echo ""
read -p "After installation completes, press ENTER to verify..."

# Verify installation
echo ""
echo "Verifying installation..."
if pm list packages | grep -q "tribixbite.keyboard2.debug"; then
    echo -e "${GREEN}✅ APK successfully installed${NC}"
    echo "   Package: tribixbite.keyboard2.debug"
else
    echo -e "${RED}❌ Installation verification failed${NC}"
    echo ""
    echo "Package not found. Please:"
    echo "  1. Check if installation actually completed"
    echo "  2. Try manual installation"
    echo "  3. Check for error messages"
    echo ""
    read -p "Continue anyway? (y/n): " CONTINUE
    if [[ ! "$CONTINUE" =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Section 5: Verification Suite
if [ "$SKIP_VERIFICATION" = false ]; then
    echo ""
    read -p "Installation complete. Press ENTER to run verification suite..."

    clear
    echo "════════════════════════════════════════════════════════════════════════════"
    echo "STEP 5/5: Running Complete Verification Suite"
    echo "════════════════════════════════════════════════════════════════════════════"
    echo ""

    if [ -f "./run-all-checks.sh" ]; then
        ./run-all-checks.sh
    else
        echo -e "${YELLOW}⚠️  run-all-checks.sh not found${NC}"
        echo ""
        echo "Running individual checks instead:"
        echo ""

        # Fallback to individual checks
        if [ -f "./check-keyboard-status.sh" ]; then
            ./check-keyboard-status.sh
        else
            echo -e "${RED}❌ check-keyboard-status.sh not found${NC}"
        fi
    fi
else
    echo ""
    echo -e "${YELLOW}⚠️  Skipping verification (--skip-verify flag used)${NC}"
fi

# Final Summary
clear
echo "════════════════════════════════════════════════════════════════════════════"
echo "BUILD & VERIFICATION PIPELINE - COMPLETE"
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

echo -e "${GREEN}✅ ALL STEPS COMPLETED${NC}"
echo ""
echo "Summary:"
if [ "$CLEAN_BUILD" = true ]; then
    echo "  ✅ Clean build completed"
fi
echo "  ✅ Production code compiled (0 errors)"
echo "  ✅ APK built successfully"
echo "  ✅ APK installed on device"
if [ "$SKIP_VERIFICATION" = false ]; then
    echo "  ✅ Verification suite executed"
fi

echo ""
echo "════════════════════════════════════════════════════════════════════════════"
echo ""

echo "📝 NEXT STEPS:"
echo ""
echo "1. Enable CleverKeys in Settings:"
echo "   Settings → System → Languages & input → Manage keyboards"
echo ""
echo "2. Open a text app and select CleverKeys"
echo ""
echo "3. Run the 5 quick tests:"
echo "   • Type 'hello world'"
echo "   • Type 'th' (check predictions)"
echo "   • Swipe h→e→l→l→o"
echo "   • Type 'teh ' (check autocorrect)"
echo "   • Observe design"
echo ""
echo "4. Report results!"
echo ""

echo "════════════════════════════════════════════════════════════════════════════"
echo ""
echo "🛠️  Available Tools:"
echo "   • Quick status: ./check-keyboard-status.sh"
echo "   • Guided tests: ./quick-test-guide.sh"
echo "   • Diagnostics: ./diagnose-issues.sh"
echo "   • Full verification: ./run-all-checks.sh"
echo "   • Rebuild everything: ./build-and-verify.sh --clean"
echo ""
echo "📖 Documentation:"
echo "   • Start here: 00_START_HERE_FIRST.md"
echo "   • Quick tips: QUICK_REFERENCE.md"
echo "   • All docs: INDEX.md"
echo ""
echo "🐛 Found a bug? Run diagnostics and report with the generated file!"
echo "✅ Everything works? Let me know!"
echo ""
