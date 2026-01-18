#!/bin/bash
# CleverKeys Release Preparation Script
# Usage: ./scripts/prepare-release.sh [patch|minor|major] "Changelog message"
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
NC='\033[0m' # No Color

# Parse arguments
BUMP_TYPE="${1:-patch}"
CHANGELOG_MSG="$2"

if [ -z "$CHANGELOG_MSG" ]; then
    echo -e "${RED}Error: Changelog message required${NC}"
    echo "Usage: $0 [patch|minor|major] \"Changelog message\""
    exit 1
fi

# Read current version from build.gradle
GRADLE_FILE="$PROJECT_DIR/build.gradle"
MAJOR=$(grep "ext.VERSION_MAJOR" "$GRADLE_FILE" | sed 's/.*= //')
MINOR=$(grep "ext.VERSION_MINOR" "$GRADLE_FILE" | sed 's/.*= //')
PATCH=$(grep "ext.VERSION_PATCH" "$GRADLE_FILE" | sed 's/.*= //')

echo -e "${GREEN}Current version: v$MAJOR.$MINOR.$PATCH${NC}"

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
        exit 1
        ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH"
echo -e "${GREEN}New version: v$NEW_VERSION${NC}"

# Calculate versionCodes for each ABI
BASE_CODE=$((MAJOR * 100000 + MINOR * 1000 + PATCH * 10))
CODE_ARMV7=$((BASE_CODE + 1))
CODE_ARM64=$((BASE_CODE + 2))
CODE_X86_64=$((BASE_CODE + 3))

echo -e "${YELLOW}VersionCodes: armv7=$CODE_ARMV7, arm64=$CODE_ARM64, x86_64=$CODE_X86_64${NC}"

# Update build.gradle
sed -i "s/ext.VERSION_MAJOR = .*/ext.VERSION_MAJOR = $MAJOR/" "$GRADLE_FILE"
sed -i "s/ext.VERSION_MINOR = .*/ext.VERSION_MINOR = $MINOR/" "$GRADLE_FILE"
sed -i "s/ext.VERSION_PATCH = .*/ext.VERSION_PATCH = $PATCH/" "$GRADLE_FILE"

echo -e "${GREEN}Updated build.gradle${NC}"

# Create fastlane changelogs
CHANGELOG_DIR="$PROJECT_DIR/fastlane/metadata/android/en-US/changelogs"
mkdir -p "$CHANGELOG_DIR"

for CODE in $CODE_ARMV7 $CODE_ARM64 $CODE_X86_64; do
    echo "$CHANGELOG_MSG" > "$CHANGELOG_DIR/$CODE.txt"
done

echo -e "${GREEN}Created changelogs in fastlane/metadata/android/en-US/changelogs/${NC}"
echo "  - $CODE_ARMV7.txt"
echo "  - $CODE_ARM64.txt"
echo "  - $CODE_X86_64.txt"

# Test compilation
echo -e "${YELLOW}Testing compilation...${NC}"
cd "$PROJECT_DIR"
if ./gradlew compileDebugKotlin 2>&1 | tail -5; then
    echo -e "${GREEN}Compilation successful${NC}"
else
    echo -e "${RED}Compilation failed - aborting${NC}"
    exit 1
fi

# Show git status
echo ""
echo -e "${YELLOW}Changes to commit:${NC}"
git status --short

# Stage changes
git add "$GRADLE_FILE" "$CHANGELOG_DIR"

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
echo "     git tag v$NEW_VERSION"
echo ""
echo "  3. Push (AFTER TESTING):"
echo "     git push && git push origin v$NEW_VERSION"
echo ""
echo -e "${RED}DO NOT push without explicit user confirmation!${NC}"
