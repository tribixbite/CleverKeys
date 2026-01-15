---
title: Installation - Technical Specification
user_guide: ../../getting-started/installation.md
status: implemented
version: v1.2.7
---

# Installation Technical Specification

## Overview

CleverKeys distribution through F-Droid and GitHub with APK signing and verification.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Manifest | `AndroidManifest.xml` | App metadata and permissions |
| Build Config | `build.gradle.kts` | Version codes, signing config |
| F-Droid Metadata | `fastlane/metadata/android/` | Store listing content |
| Release Workflow | `.github/workflows/release.yml` | Automated APK builds |

## Requirements

| Requirement | Value |
|-------------|-------|
| **Min SDK** | 24 (Android 7.0) |
| **Target SDK** | 34 (Android 14) |
| **APK Size** | ~15 MB |
| **Architecture** | Universal (arm64-v8a, armeabi-v7a, x86_64) |

## APK Signing

### Debug Builds
- Uses auto-generated debug keystore
- Path: `~/.android/debug.keystore`

### Release Builds
- Signed with release keystore (GitHub Secrets)
- SHA-256 fingerprint published on GitHub releases

## F-Droid Integration

F-Droid builds from source using their infrastructure:

```yaml
# metadata/en-US/full_description.txt
CleverKeys is a neural swipe keyboard...

# metadata/en-US/changelogs/{versionCode}.txt
- Feature 1
- Bug fix 2
```

## Version Scheme

| Field | Format | Example |
|-------|--------|---------|
| **versionName** | `MAJOR.MINOR.PATCH` | `1.2.7` |
| **versionCode** | Integer, incrementing | `127` |

## Related Specifications

- [Setup Specification](setup-spec.md)
