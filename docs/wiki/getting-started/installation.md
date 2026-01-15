---
title: Installation
description: Download CleverKeys from F-Droid or GitHub
category: Getting Started
difficulty: beginner
related_spec: ../specs/getting-started/installation-spec.md
---

# Installation

CleverKeys is available for free from F-Droid and GitHub. Choose your preferred installation method below.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Install CleverKeys on your Android device |
| **Requirements** | Android 7.0+ (API 24) |
| **Size** | ~15 MB |

## Installation Methods

### Option 1: F-Droid (Recommended)

F-Droid is an open-source app store that provides verified, privacy-respecting apps.

1. Install the [F-Droid app](https://f-droid.org/) if you don't have it
2. Open F-Droid and search for "CleverKeys"
3. Tap **Install**
4. Once installed, proceed to [Enabling the Keyboard](enabling-keyboard.md)

> [!TIP]
> F-Droid automatically notifies you when updates are available.

### Option 2: GitHub Releases

Download the APK directly from GitHub:

1. Visit [CleverKeys Releases](https://github.com/tribixbite/CleverKeys/releases/latest)
2. Download `CleverKeys-vX.X.X.apk`
3. Open the downloaded file
4. If prompted, allow installation from unknown sources
5. Tap **Install**

### Option 3: Build from Source

For developers who want to build from source:

```bash
git clone https://github.com/tribixbite/CleverKeys.git
cd CleverKeys
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/`.

## After Installation

Once installed, you need to:

1. [Enable CleverKeys](enabling-keyboard.md) as your input method
2. [Complete first-time setup](first-time-setup.md)

## Troubleshooting

**"App not installed" error**
- Ensure you have enough storage space
- Uninstall any previous version first

**"Unknown sources" blocked**
- Go to Settings > Security > Enable "Unknown sources"
- Or: Settings > Apps > Special access > Install unknown apps

## Technical Details

See [Installation Technical Specification](../specs/getting-started/installation-spec.md).
