#!/bin/bash
# CleverKeys Changelog Sync Script
# Syncs RELEASE_NOTES.md to fastlane changelogs for F-Droid and GitHub releases
#
# Usage: ./scripts/sync-changelog.sh [version]
# If version not provided, reads from build.gradle

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Source: Single source of truth for release notes
RELEASE_NOTES="$PROJECT_DIR/RELEASE_NOTES.md"

if [ ! -f "$RELEASE_NOTES" ]; then
    echo "Error: RELEASE_NOTES.md not found"
    echo "Create it with the changelog content before running this script"
    exit 1
fi

# Get version from argument or build.gradle
if [ -n "$1" ]; then
    VERSION="$1"
else
    GRADLE_FILE="$PROJECT_DIR/build.gradle"
    MAJOR=$(grep "^ext.VERSION_MAJOR = " "$GRADLE_FILE" | sed 's/.*= //')
    MINOR=$(grep "^ext.VERSION_MINOR = " "$GRADLE_FILE" | sed 's/.*= //')
    PATCH=$(grep "^ext.VERSION_PATCH = " "$GRADLE_FILE" | sed 's/.*= //')
    VERSION="$MAJOR.$MINOR.$PATCH"
fi

echo -e "${GREEN}Syncing changelog for v$VERSION${NC}"

# Calculate version codes (MAJOR*10000 + MINOR*100 + PATCH, then *10 + ABI suffix)
MAJOR=$(echo $VERSION | cut -d. -f1)
MINOR=$(echo $VERSION | cut -d. -f2)
PATCH=$(echo $VERSION | cut -d. -f3)
BASE_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

CODE_ARMV7=$((BASE_CODE * 10 + 1))
CODE_ARM64=$((BASE_CODE * 10 + 2))
CODE_X86_64=$((BASE_CODE * 10 + 3))

echo -e "${YELLOW}Version codes: armv7=$CODE_ARMV7, arm64=$CODE_ARM64, x86_64=$CODE_X86_64${NC}"

# Create fastlane changelog directory
CHANGELOG_DIR="$PROJECT_DIR/fastlane/metadata/android/en-US/changelogs"
mkdir -p "$CHANGELOG_DIR"

# Copy RELEASE_NOTES.md to each ABI-specific changelog
for CODE in $CODE_ARMV7 $CODE_ARM64 $CODE_X86_64; do
    cp "$RELEASE_NOTES" "$CHANGELOG_DIR/$CODE.txt"
    echo -e "${GREEN}Created: $CHANGELOG_DIR/$CODE.txt${NC}"
done

# Also create base versionCode version (for fallback)
cp "$RELEASE_NOTES" "$CHANGELOG_DIR/$BASE_CODE.txt"
echo -e "${GREEN}Created: $CHANGELOG_DIR/$BASE_CODE.txt (fallback)${NC}"

echo ""
echo -e "${GREEN}Changelog synced!${NC}"
echo ""
echo "Files created:"
echo "  - RELEASE_NOTES.md (source of truth)"
echo "  - fastlane/metadata/android/en-US/changelogs/${CODE_ARMV7}.txt"
echo "  - fastlane/metadata/android/en-US/changelogs/${CODE_ARM64}.txt"
echo "  - fastlane/metadata/android/en-US/changelogs/${CODE_X86_64}.txt"
echo ""
echo "GitHub Actions will use these for the release page."
echo "F-Droid will use these for the app listing."
