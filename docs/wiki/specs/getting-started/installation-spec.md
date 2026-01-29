---
title: Installation - Technical Specification
user_guide: ../../getting-started/installation.md
status: implemented
version: v1.2.9
---

# Installation Technical Specification

## Overview

CleverKeys distribution through F-Droid, GitHub, and Obtainium with per-ABI APK builds.

## Source Location Reference

All facts in the [Installation wiki page](../../getting-started/installation.md) are sourced from:

| Fact | Source File | Line(s) | Value |
|------|------------|---------|-------|
| minSdk 21 | `build.gradle` | 94 | `minSdk 21` |
| targetSdk 34 | `build.gradle` | 95 | `targetSdkVersion 34` |
| compileSdk 34 | `build.gradle` | 88 | `compileSdk 34` |
| Version 1.2.9 | `build.gradle` | 60-62 | `VERSION_MAJOR=1, MINOR=2, PATCH=9` |
| Version code scheme | `build.gradle` | 65-67, 75 | `MAJOR*10000 + MINOR*100 + PATCH` |
| ABI codes | `build.gradle` | 79 | `armeabi-v7a:1, arm64-v8a:2, x86_64:3` |
| VIBRATE permission | `AndroidManifest.xml` | 5 | `android.permission.VIBRATE` |
| READ_USER_DICTIONARY | `AndroidManifest.xml` | 14 | `android.permission.READ_USER_DICTIONARY` |
| No INTERNET permission | `AndroidManifest.xml` | 7 | Comment confirms no internet |
| No storage permission | `AndroidManifest.xml` | 16-17 | SAF/scoped storage only |
| Application ID | `build.gradle` | 93 | `tribixbite.cleverkeys` |

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Manifest | `AndroidManifest.xml` | App metadata and permissions |
| Build Config | `build.gradle` | Version codes, signing, ABI splits |
| F-Droid Metadata | `fastlane/metadata/android/` | Store listing content |
| Release Workflow | `.github/workflows/release.yml` | Automated per-ABI APK builds |
| Release Notes | `RELEASE_NOTES.md` | Single source of truth for changelogs |

## Requirements

| Requirement | Value | Source |
|-------------|-------|--------|
| **Min SDK** | 21 (Android 5.0 Lollipop) | `build.gradle:94` |
| **Target SDK** | 34 (Android 14) | `build.gradle:95` |
| **APK Size** | ~8 MB per-ABI (arm64-v8a) | Build output |
| **Architectures** | arm64-v8a, armeabi-v7a, x86_64 (split APKs) | `build.gradle:79` |

## Version Scheme

| Field | Format | Example | Source |
|-------|--------|---------|--------|
| **versionName** | `MAJOR.MINOR.PATCH` | `1.2.9` | `build.gradle:76` |
| **versionCode** | `MAJOR*10000 + MINOR*100 + PATCH` | `10209` | `build.gradle:75` |
| **ABI versionCode** | `base*10 + abiCode` | `102092` (arm64) | `build.gradle:67,79` |

## Distribution Channels

| Channel | Method | Auto-Update |
|---------|--------|-------------|
| GitHub Releases | Manual APK download | No |
| Obtainium | GitHub release polling | Yes |
| F-Droid | Builds from source | Yes |

## Related Specifications

- [Setup Specification](setup-spec.md)
