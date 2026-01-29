---
title: First Time Setup - Technical Specification
user_guide: ../../getting-started/first-time-setup.md
status: implemented
version: v1.2.9
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

Key defaults from `Config.kt` `object Defaults` (line 18+):

| Setting | Default | Source (`Config.kt` line) |
|---------|---------|--------------------------|
| `THEME` | `"cleverkeysdark"` | 20 |
| `KEYBOARD_HEIGHT_PORTRAIT` | 30 | 21 |
| `KEYBOARD_HEIGHT_LANDSCAPE` | 40 | 22 |
| `SHORT_GESTURE_MIN_DISTANCE` | 28 | 99 |
| `SHORT_GESTURE_MAX_DISTANCE` | 141 | 100 |
| `AUTOCORRECT_ENABLED` | true | 153 |
| `LONGPRESS_TIMEOUT` | 600 | 70 |
| `NEURAL_BEAM_WIDTH` | 6 | 114 |
| `NEURAL_MAX_LENGTH` | 20 | 115 |
| `NEURAL_CONFIDENCE_THRESHOLD` | 0.01f | 116 |
| `ONNX_XNNPACK_THREADS` | 2 | 247 |
| `HAPTIC_ENABLED` | true | 63 |
| `HAPTIC_SWIPE_COMPLETE` | false | 69 |
| `SMART_PUNCTUATION` | true | 77 |
| `DOUBLE_SPACE_TO_PERIOD` | true | 86 |
| `DOUBLE_SPACE_THRESHOLD` | 500 | 87 |
| `LANGUAGE_DETECTION_SENSITIVITY` | 0.6f | 243 |
| `CLIPBOARD_HISTORY_LIMIT` | 50 | 176-177 |

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
