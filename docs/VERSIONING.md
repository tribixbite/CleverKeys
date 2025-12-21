# CleverKeys Versioning System

## Overview

CleverKeys uses **semantic versioning** with automated releases through GitHub Actions and F-Droid.

## Version Format

```
vMAJOR.MINOR.PATCH
```

- **MAJOR**: Breaking changes or major features
- **MINOR**: New features, backward compatible
- **PATCH**: Bug fixes, backward compatible

Examples: `v1.0.0`, `v1.2.3`, `v2.0.0`

## VersionCode Calculation

Android requires a numeric `versionCode` that must increase with each release.

### Base VersionCode

```
baseCode = MAJOR * 10000 + MINOR * 100 + PATCH
```

| Version | Calculation | BaseCode |
|---------|-------------|----------|
| v1.0.0  | 1*10000 + 0*100 + 0 | 10000 |
| v1.2.0  | 1*10000 + 2*100 + 0 | 10200 |
| v1.2.3  | 1*10000 + 2*100 + 3 | 10203 |
| v2.0.0  | 2*10000 + 0*100 + 0 | 20000 |

### ABI VersionCode (for split APKs)

Each architecture gets a unique versionCode to allow F-Droid to serve the correct APK:

```
abiCode = baseCode * 10 + offset
```

| ABI | Offset | v1.0.0 Code |
|-----|--------|-------------|
| armeabi-v7a | +1 | 100001 |
| arm64-v8a | +2 | 100002 |
| x86_64 | +3 | 100003 |

## Release Workflow

### 1. Development Builds

Untagged commits produce development builds:
- **versionName**: `dev-{shortSha}` (e.g., `dev-a1b2c3d`)
- **versionCode**: `1`

### 2. Creating a Release

**IMPORTANT**: F-Droid's auto-update parser cannot evaluate expressions. The `versionCode` and `versionName` in `build.gradle` must be **simple literals**.

```bash
# 1. Ensure you're on main with all changes committed
git checkout main
git pull origin main

# 2. Update build.gradle with new version (REQUIRED for F-Droid auto-update)
#    Edit these lines in defaultConfig:
#      versionCode 10100   # MAJOR * 10000 + MINOR * 100 + PATCH
#      versionName "1.1.0"

# 3. Commit the version update
git add build.gradle
git commit -m "chore: bump version to 1.1.0"

# 4. Create annotated tag
git tag -a v1.1.0 -m "Release v1.1.0 - Description of changes"

# 5. Push commit and tag to trigger release
git push origin main
git push origin v1.1.0
```

### 3. Automated Release Pipeline

When a `v*` tag is pushed:

1. **GitHub Actions** (`release.yml`):
   - Builds signed APKs for all architectures
   - Creates GitHub Release with APKs attached
   - Generates changelog from commits

2. **F-Droid** (automatic):
   - Detects new tag via `UpdateCheckMode: Tags`
   - Builds APK from source
   - Publishes to F-Droid repository

## Distribution Channels

| Channel | How to Install | Update Method |
|---------|----------------|---------------|
| GitHub Releases | Download APK directly | Manual or via app |
| F-Droid | Install from F-Droid app | Automatic via F-Droid |

## Version Limits

The versioning scheme supports:
- **99 major versions** (0-99)
- **99 minor versions** per major (0-99)
- **99 patches** per minor (0-99)
- **3 ABI variants** per version

Maximum versionCode: `999999` (v99.99.99)

## Files

| File | Purpose |
|------|---------|
| `build.gradle` | Version detection from git tags |
| `.github/workflows/release.yml` | GitHub release automation |
| `~/git/fdroiddata-fork/metadata/tribixbite.cleverkeys.yml` | F-Droid build config (fork) |

## F-Droid Release Process

### Repository Structure
```
~/git/fdroiddata-fork/          # Your fork of gitlab.com/fdroid/fdroiddata
  metadata/tribixbite.cleverkeys.yml   # CleverKeys build metadata

~/git/cleverkeys/               # Main CleverKeys repo
  fastlane/metadata/android/en-US/
    changelogs/                 # Per-versionCode changelog files
    full_description.txt        # F-Droid app description
    short_description.txt       # F-Droid short description (max 80 chars)
    images/
      featureGraphic.jpg        # 1024x500 banner for F-Droid Latest tab
      phoneScreenshots/         # App screenshots
```

### F-Droid Metadata Format (tribixbite.cleverkeys.yml)

```yaml
Builds:
  - versionName: 1.1.71
    versionCode: 101711
    commit: 435152269b232a6c1187cac0b5008cd12d4f68ec   # Full commit hash
    gradle:
      - yes
    binary: https://github.com/tribixbite/CleverKeys/releases/download/v%v/CleverKeys-v%v-armeabi-v7a.apk
    prebuild: sed -i -e "s/include 'armeabi-v7a'.*/include 'armeabi-v7a'/" build.gradle

  # Repeat for arm64-v8a (101712) and x86_64 (101713)

CurrentVersion: 1.1.71
CurrentVersionCode: 101713
```

### Full Release Workflow

```bash
# 1. Update version in build.gradle
#    ext.VERSION_MAJOR = 1
#    ext.VERSION_MINOR = 1
#    ext.VERSION_PATCH = 72

# 2. Add changelogs (one per ABI)
#    fastlane/metadata/android/en-US/changelogs/101721.txt
#    fastlane/metadata/android/en-US/changelogs/101722.txt
#    fastlane/metadata/android/en-US/changelogs/101723.txt

# 3. Commit and tag
git add -A
git commit -m "v1.1.72: description of changes"
git tag v1.1.72
git push origin main
git push origin v1.1.72

# 4. Wait for GitHub Actions to build (monitor at github.com/tribixbite/CleverKeys/actions)

# 5. Get commit hash for F-Droid
git rev-parse v1.1.72

# 6. Update F-Droid metadata
cd ~/git/fdroiddata-fork
git fetch origin cleverkeys
git reset --hard origin/cleverkeys
# Edit metadata/tribixbite.cleverkeys.yml - add/replace build entries
git add metadata/tribixbite.cleverkeys.yml
git commit -m "Update CleverKeys to v1.1.72"
git push origin cleverkeys

# 7. Monitor F-Droid pipeline
curl -s "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/30449/pipelines" | jq '.[0]'
```

### F-Droid MR Information
- **MR #30449**: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/30449
- **Fork Branch**: `cleverkeys` on `gitlab.com/tribixbite/fdroiddata`
- **Upstream**: `gitlab.com/fdroid/fdroiddata`

### Notes
- F-Droid builds APKs from source and verifies against GitHub release binaries
- Only one version needs to be in the metadata (latest) for new app submissions
- After MR is merged, F-Droid auto-updates via `UpdateCheckMode: HTTP`

## Quick Reference

```bash
# Check current version
git describe --tags --match "v[0-9]*"

# List all versions
git tag -l "v*" --sort=-version:refname

# Create patch release (e.g., v1.0.0 -> v1.0.1)
git tag -a v1.0.1 -m "v1.0.1 - Bug fixes"
git push origin v1.0.1

# Create minor release (e.g., v1.0.1 -> v1.1.0)
git tag -a v1.1.0 -m "v1.1.0 - New features"
git push origin v1.1.0
```
