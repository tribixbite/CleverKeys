#!/bin/bash
# CleverKeys Release Preparation Script
# Usage: ./scripts/prepare-release.sh [patch|minor|major]
#
# Prerequisites:
#   1. Edit RELEASE_NOTES.md with your changelog content
#   2. Run this script to bump version and sync changelogs
#
# This script prepares a release but DOES NOT push.
# User must explicitly confirm push with: git push && git push origin vX.Y.Z

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Parse arguments
BUMP_TYPE="${1:-patch}"

# Check RELEASE_NOTES.md exists
RELEASE_NOTES="$PROJECT_DIR/RELEASE_NOTES.md"
if [ ! -f "$RELEASE_NOTES" ]; then
    echo -e "${RED}Error: RELEASE_NOTES.md not found${NC}"
    echo ""
    echo "Before running this script:"
    echo "  1. Create/edit RELEASE_NOTES.md with your changelog"
    echo "  2. Run: ./scripts/prepare-release.sh $BUMP_TYPE"
    echo ""
    echo "Example RELEASE_NOTES.md:"
    echo "---"
    echo "vX.Y.Z - Short Title"
    echo ""
    echo "New Features:"
    echo "- Feature description"
    echo ""
    echo "Fixes:"
    echo "- Bug fix description"
    echo "---"
    exit 1
fi

# Read current version from build.gradle
GRADLE_FILE="$PROJECT_DIR/build.gradle"
MAJOR=$(grep "^ext.VERSION_MAJOR = " "$GRADLE_FILE" | sed 's/.*= //')
MINOR=$(grep "^ext.VERSION_MINOR = " "$GRADLE_FILE" | sed 's/.*= //')
PATCH=$(grep "^ext.VERSION_PATCH = " "$GRADLE_FILE" | sed 's/.*= //')

echo -e "${CYAN}Current version: v$MAJOR.$MINOR.$PATCH${NC}"

# Calculate new version
case "$BUMP_TYPE" in
    major)
        MAJOR=$((MAJOR + 1))
        MINOR=0
        PATCH=0
        ;;
    minor)
        MINOR=$((MINOR + 1))
        PATCH=0
        ;;
    patch)
        PATCH=$((PATCH + 1))
        ;;
    *)
        echo -e "${RED}Invalid bump type: $BUMP_TYPE${NC}"
        echo "Usage: $0 [patch|minor|major]"
        exit 1
        ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH"
echo -e "${GREEN}New version: v$NEW_VERSION${NC}"

# Calculate versionCodes for each ABI
BASE_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))
CODE_ARMV7=$((BASE_CODE * 10 + 1))
CODE_ARM64=$((BASE_CODE * 10 + 2))
CODE_X86_64=$((BASE_CODE * 10 + 3))

echo -e "${YELLOW}VersionCodes: armv7=$CODE_ARMV7, arm64=$CODE_ARM64, x86_64=$CODE_X86_64${NC}"

# Update build.gradle
sed -i "s/ext.VERSION_MAJOR = .*/ext.VERSION_MAJOR = $MAJOR/" "$GRADLE_FILE"
sed -i "s/ext.VERSION_MINOR = .*/ext.VERSION_MINOR = $MINOR/" "$GRADLE_FILE"
sed -i "s/ext.VERSION_PATCH = .*/ext.VERSION_PATCH = $PATCH/" "$GRADLE_FILE"

echo -e "${GREEN}Updated build.gradle${NC}"

# Sync changelog using unified script
echo ""
echo -e "${YELLOW}Syncing changelog from RELEASE_NOTES.md...${NC}"
"$SCRIPT_DIR/sync-changelog.sh" "$NEW_VERSION"

# Test compilation
echo ""
echo -e "${YELLOW}Testing compilation...${NC}"
cd "$PROJECT_DIR"
if ./gradlew compileDebugKotlin 2>&1 | tail -5; then
    echo -e "${GREEN}Compilation successful${NC}"
else
    echo -e "${RED}Compilation failed - aborting${NC}"
    exit 1
fi

# Show RELEASE_NOTES.md content
echo ""
echo -e "${CYAN}=== RELEASE_NOTES.md ===${NC}"
cat "$RELEASE_NOTES"
echo -e "${CYAN}========================${NC}"

# Show git status
echo ""
echo -e "${YELLOW}Changes to commit:${NC}"
git status --short

# Stage changes
git add "$GRADLE_FILE" "$RELEASE_NOTES" fastlane/metadata/android/en-US/changelogs/

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Release v$NEW_VERSION prepared!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}Next steps (REQUIRES USER CONFIRMATION):${NC}"
echo ""
echo "  1. Review changes:"
echo "     git diff --staged"
echo ""
echo "  2. Commit and tag:"
echo "     git commit -m \"release: v$NEW_VERSION\""
echo "     git tag -a v$NEW_VERSION -m \"v$NEW_VERSION\""
echo ""
echo "  3. Push (AFTER TESTING):"
echo "     git push && git push origin v$NEW_VERSION"
echo ""
echo -e "${CYAN}GitHub Actions will:${NC}"
echo "  - Build APKs for all ABIs"
echo "  - Create GitHub Release with RELEASE_NOTES.md content"
echo "  - Upload signed APKs"
echo ""
echo -e "${CYAN}F-Droid will:${NC}"
echo "  - Auto-detect new tag within 24-48 hours"
echo "  - Build and publish using fastlane changelogs"
echo ""
echo -e "${RED}DO NOT push without explicit user confirmation!${NC}"
