---
title: First Time Setup - Technical Specification
user_guide: ../../getting-started/first-time-setup.md
status: implemented
version: v1.5.0
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
| Launcher | `LauncherActivity.kt` | First-run Enable/Select Keyboard flow |
| Language Manager | `langpack/LanguagePackManager.kt` | Import and install language packs (SAF, no network) |

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
| **Language Packs** | `files/langpacks/` | Imported dictionaries + unigrams (`LanguagePackManager.kt:29`) |

## Default Values

Key defaults from `Config.kt` `object Defaults` (line 18+):

| Setting | Default | Source (`Config.kt` line) |
|---------|---------|--------------------------|
| `THEME` | `"cleverkeysdark"` | 20 |
| `KEYBOARD_HEIGHT_PORTRAIT` | 27 | 23 |
| `KEYBOARD_HEIGHT_LANDSCAPE` | 40 | 24 |
| `SHORT_GESTURE_MIN_DISTANCE` | 28 (% of key diagonal) | 119 |
| `SHORT_GESTURE_MAX_DISTANCE` | 141 (% of key diagonal; short/long boundary) | 120 |
| `AUTOCORRECT_ENABLED` | true | 176 |
| `LONGPRESS_TIMEOUT` | 600 | 85 |
| `NEURAL_BEAM_WIDTH` | 6 | 134 |
| `NEURAL_MAX_LENGTH` | 20 | 135 |
| `NEURAL_CONFIDENCE_THRESHOLD` | 0.01f | 136 |
| `ONNX_XNNPACK_THREADS` | 2 | 299 |
| `HAPTIC_ENABLED` | true | 75 |
| `HAPTIC_SWIPE_COMPLETE` | true | 84 |
| `SMART_PUNCTUATION` | true | 95 |
| `DOUBLE_SPACE_TO_PERIOD` | true | 104 |
| `DOUBLE_SPACE_THRESHOLD` | 500 | 105 |
| `LANGUAGE_DETECTION_SENSITIVITY` | 0.6f | 295 |
| `CLIPBOARD_HISTORY_LIMIT` | "0" (unlimited) | 215 |

## Enable / Select Keyboard Flow (LauncherActivity)

The in-app launcher offers **Enable Keyboard** and **Select Keyboard** actions (`LauncherActivity.kt:110-111`). `Select Keyboard` never calls `showInputMethodPicker()` in crash-prone states (UT-6, `LauncherActivity.kt:131-164`):

- CleverKeys **not yet enabled** as an input method → opens the system IME settings screen instead of the picker (`isCleverKeysEnabledCompat()` check → `launchKeyboardSettings()`).
- Enabled **and window focused** → shows the system input-method picker.
- Enabled but **window unfocused** at picker time → falls back to IME settings.

Rationale: `showInputMethodPicker()` displays a dialog owned by `system_server`; issuing it while the IME isn't enabled or the window is unfocused can crash or no-op silently.

## Language Pack Import

CleverKeys has **no INTERNET permission** — language packs are never downloaded by the app. Flow:

1. Obtain a prebuilt `langpack-<lang>.zip` (repo `scripts/dictionaries/`) or build one with `scripts/build_langpack.py`
2. Settings → 🌐 Multi-Language → **Import Pack** (SAF file picker)
3. Pack contents (manifest.json + dictionary.bin + unigrams.txt) are installed under `files/langpacks/`
4. The language becomes selectable; installed packs are detected alongside bundled dictionaries

## Related Specifications

- [Installation Specification](installation-spec.md)
- [Settings System](../../../specs/settings-system.md)
