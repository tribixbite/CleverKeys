# Release Process Skill

Use this skill when tagging, releasing, or preparing a new version of CleverKeys for GitHub and F-Droid distribution.

## Pre-Release Checklist

Before starting a release:
- [ ] All planned features/fixes are merged
- [ ] Tests pass: `./scripts/run-pure-tests.sh`
- [ ] Build succeeds: `./gradlew compileDebugKotlin`
- [ ] Manual testing completed
- [ ] `memory/todo.md` updated with completed items

## F-Droid API Queries

### Check Current Published Version

```bash
# Get current F-Droid version info
curl -s "https://f-droid.org/api/v1/packages/tribixbite.cleverkeys" | jq '.suggestedVersionCode, .suggestedVersionName'
```

Response shows:
- `suggestedVersionCode`: Latest version code (e.g., `102053` for v1.2.5 arm64)
- `suggestedVersionName`: Version string (e.g., `1.2.5`)

### Check Build Pipeline Status

```bash
# Current running builds
curl -s "https://f-droid.org/repo/status/running.json" | jq '.running[] | select(.appid == "tribixbite.cleverkeys")'

# Last completed build cycle
curl -s "https://f-droid.org/repo/status/build.json" | jq '.[] | select(.appid == "tribixbite.cleverkeys")'

# Last update check
curl -s "https://f-droid.org/repo/status/update.json" | jq '.[] | select(.appid == "tribixbite.cleverkeys")'
```

### Check F-Droid Metadata

```bash
# View CleverKeys metadata in fdroiddata
curl -s "https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/tribixbite.cleverkeys.yml"
```

## Version Code Scheme

### Base Version Code
```
versionCode = VERSION_MAJOR * 10000 + VERSION_MINOR * 100 + VERSION_PATCH
```

Examples:
| Version | Base versionCode |
|---------|------------------|
| v1.2.5  | 10205           |
| v1.2.6  | 10206           |
| v1.3.0  | 10300           |
| v2.0.0  | 20000           |

### ABI-Specific Version Codes
```
abiVersionCode = baseVersionCode * 10 + abiSuffix
```

| ABI         | Suffix | v1.2.5 Code | v1.2.6 Code |
|-------------|--------|-------------|-------------|
| armeabi-v7a | 1      | 102051      | 102061      |
| arm64-v8a   | 2      | 102052      | 102062      |
| x86_64      | 3      | 102053      | 102063      |

## Step-by-Step Release Process

### 1. Query Current State

```bash
# Current F-Droid version
curl -s "https://f-droid.org/api/v1/packages/tribixbite.cleverkeys" | jq -r '"F-Droid: v" + .suggestedVersionName + " (code " + (.suggestedVersionCode|tostring) + ")"'

# Current GitHub release
gh release list --limit 1

# Current git tag
git describe --tags --abbrev=0

# Current build.gradle version
grep "VERSION_" build.gradle | head -3
```

### 2. Determine New Version

Semantic versioning:
- **MAJOR**: Breaking changes, major rewrites
- **MINOR**: New features, backwards-compatible
- **PATCH**: Bug fixes, minor improvements

### 3. Update build.gradle

Edit `build.gradle` lines 58-60:
```gradle
ext.VERSION_MAJOR = 1
ext.VERSION_MINOR = 2
ext.VERSION_PATCH = 6   // <-- increment this
```

### 4. Write Fastlane Changelogs

Create THREE identical changelog files:

```bash
# Calculate version codes
NEW_VERSION="1.2.6"
BASE_CODE=10206  # MAJOR*10000 + MINOR*100 + PATCH

# File paths
CHANGELOG_DIR="fastlane/metadata/android/en-US/changelogs"
touch "$CHANGELOG_DIR/${BASE_CODE}1.txt"  # armv7
touch "$CHANGELOG_DIR/${BASE_CODE}2.txt"  # arm64
touch "$CHANGELOG_DIR/${BASE_CODE}3.txt"  # x86_64
```

### Changelog Format

```
vX.Y.Z - Short Title

LAYOUT NOTE: (optional - only for layout changes)
Description of layout changes if any.

NEW FEATURES:
• Feature 1 - Brief description
  - Detail line if needed

• Feature 2 - Brief description

FIXES:
• Fixed issue description (issue #XX)
• Fixed another issue

IMPROVEMENTS:
• Performance improvement description
• Code cleanup description
```

### Example Changelog

```
v1.2.6 - Emoji Panel Polish

NEW FEATURES:
• Emoji long-press tooltip - Shows emoji name above pressed cell
  - Works with all emoji including flags and emoticons
  - Auto-dismisses after 2.5 seconds

• Emoticons category - 119 text emoticons in emoji picker
  - Searchable via keywords (shrug, lenny, tableflip)
  - Length-based scaling for proper display

FIXES:
• Emoji/clipboard panel gap eliminated
• App-switch no longer causes empty panels
• Flag emoji names now show country (Japan vs Regional Indicator)
```

### 5. Commit Version Bump

```bash
# Stage changes
git add build.gradle fastlane/

# Commit with version in message
git commit -m "chore: bump version to v1.2.6

- Update VERSION_PATCH in build.gradle
- Add fastlane changelog for v1.2.6

-- claude-opus-4-5"
```

### 6. Create and Push Tag

```bash
# Create annotated tag
git tag -a v1.2.6 -m "v1.2.6 - Emoji Panel Polish"

# Push commit and tag
git push origin main
git push origin v1.2.6
```

### 7. Verify GitHub Release

GitHub Actions (`release.yml`) automatically:
1. Verifies tag matches build.gradle version
2. Builds per-ABI APKs (matching F-Droid exactly)
3. Signs with release keystore
4. Creates GitHub Release with changelog
5. Uploads APK artifacts

Check progress:
```bash
gh run list --workflow=release.yml --limit 1
gh run view <run-id>
```

### 8. Monitor F-Droid Build

F-Droid typically picks up new tags within 24-48 hours:

```bash
# Check if build started
curl -s "https://f-droid.org/repo/status/running.json" | jq '.running[] | select(.appid == "tribixbite.cleverkeys")'

# Check build result (after completion)
curl -s "https://f-droid.org/repo/status/build.json" | jq '.[] | select(.appid == "tribixbite.cleverkeys")'
```

## Changelog Generation Script

For generating changelog from commits since last tag:

```bash
#!/bin/bash
LAST_TAG=$(git describe --tags --abbrev=0 HEAD^)
NEW_TAG=$1

echo "v${NEW_TAG#v} - Title Here"
echo ""
echo "Changes since $LAST_TAG:"
echo ""

# Group commits by type
git log $LAST_TAG..HEAD --pretty=format:"• %s" | while read line; do
  if echo "$line" | grep -qi "feat\|add\|new"; then
    echo "NEW: $line"
  elif echo "$line" | grep -qi "fix"; then
    echo "FIX: $line"
  else
    echo "OTHER: $line"
  fi
done
```

## Key Files

| File | Purpose |
|------|---------|
| `build.gradle` | VERSION_MAJOR/MINOR/PATCH (lines 58-60) |
| `fastlane/metadata/android/en-US/changelogs/` | F-Droid/Fastlane changelogs |
| `.github/workflows/release.yml` | GitHub Actions release workflow |
| `memory/todo.md` | Track completed work for changelog |

## Troubleshooting

### Tag/Version Mismatch
If GitHub Actions fails with "VERSION MISMATCH":
1. Ensure build.gradle VERSION_MAJOR/MINOR/PATCH matches tag
2. Delete the tag: `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z`
3. Fix build.gradle, commit, re-tag

### F-Droid Not Picking Up Release
1. Check metadata file exists in fdroiddata repo
2. Verify tag is annotated (not lightweight)
3. Check F-Droid build logs at https://monitor.f-droid.org/

### Reproducibility Issues
F-Droid requires byte-identical builds. If verification fails:
1. Check `build.gradle` reproducibility settings
2. Verify Java 17, SDK 34, build-tools 34.0.0
3. See `.github/workflows/release.yml` for exact build steps

## API Reference

| Endpoint | Purpose |
|----------|---------|
| `https://f-droid.org/api/v1/packages/{id}` | Package version info |
| `https://f-droid.org/repo/status/running.json` | Currently building |
| `https://f-droid.org/repo/status/build.json` | Last build cycle |
| `https://f-droid.org/repo/status/update.json` | Last update check |
| `https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/{id}.yml` | App metadata |

## Related Documentation

- Spec: `docs/specs/suggestion-bar-content-pane.md`
- Build: `build.gradle` comments explain version scheme
- CI: `.github/workflows/release.yml`

## Release Cadence

- **Major releases** (X.0.0): Major rewrites, breaking changes
- **Minor releases** (X.Y.0): New features, significant improvements
- **Patch releases** (X.Y.Z): Bug fixes, polish, small improvements

Typical timeline:
- Development: variable
- GitHub Release: Immediate after tag push (~5 min build)
- F-Droid: 24-48 hours after tag (automated detection)
