---
title: First Time Setup - Technical Specification
user_guide: ../../getting-started/first-time-setup.md
status: implemented
version: v1.2.7
---

# First Time Setup Technical Specification

## Overview

Initial keyboard configuration flow including language pack download and preference initialization.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Settings Activity | `SettingsActivity.kt` | Main configuration UI |
| Config | `Config.kt` | Default values and preference keys |
| DirectBootPrefs | `DirectBootAwarePreferences.kt` | Device-encrypted storage |
| Language Manager | `LanguagePackManager.kt` | Download and install language packs |

## Initialization Flow

```
App Install
    ↓
First keyboard launch
    ↓
Check isFirstRun preference
    ↓
Show setup wizard (if first run)
    ↓
Layout selection → Language pack download → Theme selection
    ↓
Mark isFirstRun = false
    ↓
Normal keyboard operation
```

## Configuration Storage

| Storage | File | Contents |
|---------|------|----------|
| **Preferences** | `shared_prefs/cleverkeys_prefs.xml` | User settings |
| **Device Protected** | `shared_prefs/neural_performance_stats.xml` | Stats (encrypted) |
| **Language Packs** | `files/language_packs/` | Downloaded ONNX models |

## Default Values

Key defaults from `Config.kt:Defaults`:

| Setting | Default | Line |
|---------|---------|------|
| `keyboard_height_portrait` | 30% | ~150 |
| `short_gesture_min_distance` | 28% | ~160 |
| `theme` | "cleverkeysdark" | ~140 |
| `autocorrect_enabled` | true | ~180 |

## Language Pack Download

Flow in `LanguagePackManager.kt`:

1. Check network connectivity
2. Fetch pack list from GitHub releases
3. Download ZIP containing model + dictionary
4. Extract to `language_packs/{lang}/`
5. Verify model loads successfully
6. Update preferences with available languages

## Related Specifications

- [Installation Specification](installation-spec.md)
- [Settings System](../../../specs/settings-system.md)
