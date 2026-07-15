---
title: Haptics Settings - Technical Specification
description: Per-event haptic feedback via VibratorCompat — system performHapticFeedback by default, opt-in custom vibration duration, and the #154 one-time migration.
user_guide: ../../settings/haptics.md
status: implemented
version: v1.5.0
---

# Haptics Settings Technical Specification

## Overview

Haptic feedback is centralized in `VibratorCompat`. By default, key presses use Android's `View.performHapticFeedback()` — the low-latency, OEM-tuned system effects — instead of driving the `Vibrator` service directly. A user who moves the **Vibration Duration** slider opts into the custom path (`vibrate_custom = true`), which vibrates for an explicit duration via the `Vibrator` API.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `VibratorCompat.vibrate` | `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt:60-97` | Single entry point: master gate → per-event gate → system-vs-custom dispatch |
| `HapticEvent` | `VibratorCompat.kt:21` | Enum: `KEY_PRESS`, `PREDICTION_TAP`, `TRACKPOINT_ACTIVATE`, `LONG_PRESS`, `SWIPE_COMPLETE` |
| Haptic defaults | `Config.kt:75-84` (`object Defaults`) | Compile-time defaults |
| `HapticsMigration` | `Config.kt:325-360` | Pure decision logic for the #154 one-time cleanup |
| `Config.migrateForcedVibrateCustom` | `Config.kt:1480-1502` | Pref wiring for the migration |
| Settings UI | `ui/settings/sections/AccessibilitySection.kt` | Master toggle, per-event switches, duration slider (slider write sets `vibrate_custom = true`) |

## Dispatch Logic

```kotlin
// VibratorCompat.kt:60-97
fun vibrate(v: View, config: Config, event: HapticEvent = HapticEvent.KEY_PRESS) {
    // Master toggle - when disabled, no haptic feedback at all
    if (!config.haptic_enabled) {
        return
    }

    // Check if this specific event type is enabled in app settings
    if (!isEventEnabled(config, event)) {
        return
    }

    // Use custom duration if enabled, otherwise use system haptics
    if (config.vibrate_custom && config.vibrate_duration > 0) {
        // User wants custom vibration - use Vibrator directly
        vibratorVibrate(v, config.vibrate_duration)
    } else {
        // Use system haptic feedback - low latency, OEM optimized
        val hapticConstant = getHapticConstant(event)
        val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING

        val performed = v.performHapticFeedback(hapticConstant, flags)

        // Fallback to manual vibration if performHapticFeedback fails
        if (!performed) {
            vibratorVibrate(v, getDefaultDuration(event))
        }
    }
}
```

Design points:

- **System path by default.** `vibrate_custom` defaults `false` (`Config.kt:72`), so keypresses hit `performHapticFeedback(KEYBOARD_TAP, ...)` — pre-loaded hardware effects with lower latency than a `Vibrator.vibrate()` round-trip (#154).
- **`FLAG_IGNORE_VIEW_SETTING or FLAG_IGNORE_GLOBAL_SETTING`** — the app's own per-event toggles are authoritative; users who disabled system-wide touch haptics can still opt into keyboard feedback.
- **Graceful fallback.** If the constant isn't supported (`performed == false`, e.g. older devices), a manual vibration of a per-event default duration fires instead (`VibratorCompat.kt:88-94`).
- **Per-event constants**: `KEY_PRESS` → `KEYBOARD_TAP` (API 27+, else `VIRTUAL_KEY`), `PREDICTION_TAP` → `TEXT_HANDLE_MOVE`, `TRACKPOINT_ACTIVATE` → `CLOCK_TICK`, `LONG_PRESS` → `LONG_PRESS`, `SWIPE_COMPLETE` → `GESTURE_END` (enum KDoc, `VibratorCompat.kt:21-32`; mapping in `getHapticConstant`, `VibratorCompat.kt:122`).

## The #154 One-Time Migration

Historically a settings-save bug wrote `vibrate_custom = true` whenever the master vibration toggle was saved, silently forcing every affected install onto the slower manual-vibration path. The forcing code is gone; `migrateForcedVibrateCustom` cleans up installs that still carry the bug-written flag:

```kotlin
// Config.kt:1480-1502
fun migrateForcedVibrateCustom(prefs: SharedPreferences) {
    if (prefs.getBoolean("vibrate_custom_migration_v1", false)) return

    val vibrateCustom = prefs.getBoolean("vibrate_custom", Defaults.VIBRATE_CUSTOM)
    val paramsAtDefaults =
        safeGetInt(prefs, "vibrate_duration", Defaults.VIBRATE_DURATION) == Defaults.VIBRATE_DURATION

    val e = prefs.edit()
    if (HapticsMigration.shouldClearForcedVibrateCustom(
            vibrateCustom = vibrateCustom,
            alreadyMigrated = false, // marker checked above
            paramsAtDefaults = paramsAtDefaults
        )
    ) {
        e.putBoolean("vibrate_custom", false)
    }
    // Always mark migrated so this never runs again.
    e.putBoolean("vibrate_custom_migration_v1", true)
    e.apply()
}
```

- Clears the flag ONLY when `vibrate_duration` is still at its compile-time default (20 ms) — a user who actually moved the slider plausibly chose custom mode and keeps it.
- The marker `vibrate_custom_migration_v1` (`HapticsMigration.MIGRATION_MARKER_KEY`, `Config.kt:338`) is persisted unconditionally, so the migration runs exactly once per install.
- Decision logic is a pure function (`HapticsMigration.shouldClearForcedVibrateCustom`) with JVM tests; the pref wiring is separate.

## Configuration

Real preference keys (read in `Config.refresh`, `Config.kt:704-713`):

| Setting | Key | Default | Source |
|---------|-----|---------|--------|
| **Master toggle** | `vibration_enabled` | `true` | `Config.kt:707`, default `HAPTIC_ENABLED` `Config.kt:75` |
| **Key press** | `haptic_key_press` | `true` | `Config.kt:709` / `Defaults:77` |
| **Suggestion tap** | `haptic_prediction_tap` | `true` | `Config.kt:710` / `Defaults:78` |
| **TrackPoint activate** | `haptic_trackpoint_activate` | `true` | `Config.kt:711` / `Defaults:79` |
| **Long press** | `haptic_long_press` | `true` | `Config.kt:712` / `Defaults:80` |
| **Swipe complete** | `haptic_swipe_complete` | `true` (since 2026-05: confirms word recognition; disable per-user if distracting) | `Config.kt:713` / `Defaults:84` |
| **Custom vibration mode** | `vibrate_custom` | `false` | `Config.kt:704` / `Defaults:72` |
| **Custom duration (ms)** | `vibrate_duration` | `20` | `Config.kt:705` / `Defaults:73` |

The Settings UI exposes the master toggle, the five per-event switches, and the duration slider in the **Accessibility** section; writing the slider sets `vibrate_custom = true` (`AccessibilitySection.kt`).

`android.permission.VIBRATE` is declared in `AndroidManifest.xml`.

## Related Specifications

- [Settings System Architecture](./settings-system-architecture-spec.md) - Preference storage and refresh
- [Input Behavior](./input-behavior-spec.md) - Key event routing that triggers haptics
