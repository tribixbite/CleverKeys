---
title: First Time Setup - Technical Specification
description: First-run flow (LauncherActivity enable/select), preference storage, verified defaults and language-pack import
user_guide: /wiki/getting-started/first-time-setup/
status: implemented
version: v2.0.0
---

# First Time Setup Technical Specification

## Overview

There is no setup wizard and no first-run flag. On a fresh install the launcher icon opens
`LauncherActivity`, which offers **Enable Keyboard**, **Select Keyboard** and gesture
calibration; everything else is configured from `SettingsActivity`. A preference that was
never written reads its value from `Config.Defaults`, so a new install is fully defined by
that object.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Settings Activity | `src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt` | Main configuration UI (collapsible sections and settings search) |
| Config | `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `object Defaults`, `object SettingsRanges`, preference keys |
| DirectBootAwarePreferences | `src/main/kotlin/tribixbite/cleverkeys/DirectBootAwarePreferences.kt` | On API 24+ the keyboard settings live in device-protected storage, readable before the first unlock so the storage password can be typed; nothing personal is stored there |
| Launcher | `src/main/kotlin/tribixbite/cleverkeys/activities/LauncherActivity.kt` | First-run Enable / Select Keyboard / Calibrate Gestures flow |
| Language Pack Manager | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt` | Import and install language packs (SAF file picker, no network) |

## First-Run Flow

```
App install
    ↓
Launcher icon → LauncherActivity
    ↓
Enable Keyboard  → system "On-screen keyboard" settings
    ↓
Select Keyboard  → system input-method picker (see below)
    ↓
(optional) Calibrate Gestures → ShortSwipeCalibrationActivity
    ↓
Keyboard usable; Settings reachable from the keyboard's gear key
```

The launcher checks the live enabled/selected state on every launch; nothing is stored to
mark the first run as done.

## Enable / Select Keyboard Flow (LauncherActivity)

The launcher wires **Enable Keyboard** to `launchKeyboardSettings()` and **Select Keyboard**
to `launchInputMethodPicker()` (`LauncherActivity.kt:118-119`). `Select Keyboard` never calls
`showInputMethodPicker()` in crash-prone states (UT-6, `LauncherActivity.kt:151-162`):

- CleverKeys **not yet enabled** as an input method → opens the system IME settings screen
  instead of the picker (`isCleverKeysEnabledCompat()` check → `launchKeyboardSettings()`).
- Enabled **and window focused** → shows the system input-method picker.
- Enabled but **window unfocused** at picker time → falls back to IME settings.

Rationale: `showInputMethodPicker()` displays a dialog owned by `system_server`; issuing it
while the IME isn't enabled or the window is unfocused can crash or no-op silently.

## Configuration Storage

| Storage | Where | Contents |
|---------|-------|----------|
| **Keyboard settings** | Default `SharedPreferences`; device-protected storage on API 24+ (`DirectBootAwarePreferences`) | Every user setting |
| **Swipe selection stats** | `neural_performance_stats` `SharedPreferences` (`SwipePerformanceStats.kt`; legacy file name) | Suggestion-selection statistics |
| **Privacy settings** | `privacy_settings` `SharedPreferences` (`PrivacyManager.kt`) | Local data-collection toggles |
| **Language packs** | `files/langpacks/<code>/` (`LanguagePackManager.kt`) | Imported dictionaries and unigrams |

## Default Values

Defaults live in `Config.kt` `object Defaults`; the values below were read from HEAD on
2026-10-23 (line numbers are omitted because they shift with every edit):

| Setting | Default |
|---------|---------|
| `THEME` | `"cleverkeysdark"` |
| `KEYBOARD_HEIGHT_PORTRAIT` / `KEYBOARD_HEIGHT_LANDSCAPE` | 27 / 40 (% of screen height; sliders 20–60) |
| `SHORT_GESTURE_MIN_DISTANCE` / `SHORT_GESTURE_MAX_DISTANCE` | 28 / 141 (% of key diagonal; max is the short/long boundary) |
| `AUTOCORRECT_ENABLED` | `true` |
| `LONGPRESS_TIMEOUT` | 600 ms |
| `SWIPE_ENGINE_MODE` | `"ctc"` |
| `CTC_BEAM_WIDTH` | 100 (slider 10–300) |
| `ONNX_XNNPACK_THREADS` | 2 |
| `HAPTIC_ENABLED` / `HAPTIC_SWIPE_COMPLETE` | `true` / `true` |
| `SMART_PUNCTUATION` | `true` |
| `DOUBLE_SPACE_TO_PERIOD` / `DOUBLE_SPACE_THRESHOLD` | `true` / 500 ms |
| `LANGUAGE_DETECTION_SENSITIVITY` | 0.6 |
| `CLIPBOARD_HISTORY_LIMIT` | `"0"` (unlimited) |
| `NEXT_WORD_PREDICTION_ENABLED` | `true` (since 2026-09-26; an explicit stored `false` is kept) |
| `ON_DEVICE_LEARNING_ENABLED` | `false` (fresh installs; upgrades from pre-2.0 are seeded `true` by the v4 migration) |

## Language Pack Import

CleverKeys has **no INTERNET permission** — language packs are never downloaded by the app. Flow:

1. Obtain a prebuilt `langpack-<lang>.zip` (repo `scripts/dictionaries/`, or the
   [langpacks release](https://github.com/tribixbite/CleverKeys/releases/tag/langpacks)) or
   build one with `scripts/build_langpack.py`
2. Settings → 🌐 Multi-Language → **Import Pack** (SAF file picker)
3. Pack contents (`manifest.json` + `dictionary.bin` + `unigrams.txt`, optional `NOTICE.txt`
   and swipe model) are installed under `files/langpacks/<code>/`
4. The language becomes selectable; installed packs are detected alongside bundled dictionaries

## Related Specifications

- [Installation Specification](installation-spec.md)
- [Settings System Architecture](../settings/settings-system-architecture-spec.md)
