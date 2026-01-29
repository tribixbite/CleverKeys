---
title: Haptics Settings - Technical Specification
user_guide: ../../settings/haptics.md
status: implemented
version: v1.2.9
---

# Haptics Settings Technical Specification

## Source Location Reference

All facts in the [Haptics wiki page](../../settings/haptics.md) are sourced from:

| Fact | Source File | Line(s) | Value |
|------|------------|---------|-------|
| Master haptic toggle default | `Config.kt` | 63 | `HAPTIC_ENABLED = true` |
| Key press haptic default | `Config.kt` | 65 | `HAPTIC_KEY_PRESS = true` |
| Prediction tap haptic default | `Config.kt` | 66 | `HAPTIC_PREDICTION_TAP = true` |
| TrackPoint activate haptic default | `Config.kt` | 67 | `HAPTIC_TRACKPOINT_ACTIVATE = true` |
| Long press haptic default | `Config.kt` | 68 | `HAPTIC_LONG_PRESS = true` |
| Swipe complete haptic default (off) | `Config.kt` | 69 | `HAPTIC_SWIPE_COMPLETE = false` |
| VIBRATE permission required | `AndroidManifest.xml` | 5 | `android.permission.VIBRATE` |
| Settings UI section | `SettingsActivity.kt` | "Accessibility" section | Haptic toggles |

> **Note:** The code examples below are illustrative architecture diagrams, not verbatim source. Actual haptic logic is in `Pointers.kt` and `CleverKeysService.kt`.

## Overview

The haptic feedback system provides tactile feedback for key presses, gestures, and events using Android's vibration API with configurable intensity and patterns.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Haptic Config | `Config.kt` (lines 63-69) | Default haptic settings |
| Pointer Handler | `Pointers.kt` | Triggers haptic feedback on events |
| Keyboard Service | `CleverKeysService.kt` | Android Vibrator service access |
| Settings UI | `SettingsActivity.kt` | Accessibility section toggles |

## Haptic Events

### Event Types

```kotlin
// HapticManager.kt
enum class HapticEvent {
    KEY_PRESS,          // Regular key tap
    BACKSPACE,          // Delete key
    ENTER,              // Return/submit
    SPACE,              // Spacebar
    SHIFT,              // Shift/caps
    SHORT_SWIPE,        // Subkey activation
    LONG_PRESS,         // Hold detected
    TRACKPOINT_ACTIVATE, // TrackPoint mode start
    SELECTION_ACTIVATE, // Selection mode start
    CIRCLE_GESTURE,     // Undo/redo circle
    AUTOCORRECT,        // Word corrected
    PREDICTION_SELECT,  // Tap on prediction
    WORD_COMPLETE       // Swipe word finished
}
```

### Event Configuration

```kotlin
// Config.kt
data class HapticConfig(
    val masterEnabled: Boolean = true,
    val intensity: HapticIntensity = HapticIntensity.MEDIUM,

    // Per-event settings
    val keyPress: Boolean = true,
    val backspace: Boolean = true,
    val enter: Boolean = true,
    val space: Boolean = false,
    val shift: Boolean = true,
    val shortSwipe: Boolean = true,
    val longPress: Boolean = true,
    val trackpointActivate: Boolean = true,
    val selectionActivate: Boolean = true,
    val circleGesture: Boolean = true,
    val autocorrect: Boolean = true,
    val predictionSelect: Boolean = true,
    val wordComplete: Boolean = false
)
```

## Vibration Controller

### Hardware Interface

```kotlin
// VibrationController.kt
class VibrationController(context: Context) {
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(VibratorManager::class.java)
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun vibrate(effect: VibrationEffect) {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(effect)
    }

    fun cancel() {
        vibrator.cancel()
    }
}
```

### Intensity Levels

```kotlin
// HapticManager.kt
enum class HapticIntensity(val amplitude: Int, val durationMs: Long) {
    LIGHT(50, 10),
    MEDIUM(128, 15),
    STRONG(255, 25),
    SYSTEM(-1, -1)  // Use system default
}

private fun createEffect(intensity: HapticIntensity): VibrationEffect {
    return if (intensity == HapticIntensity.SYSTEM) {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    } else {
        VibrationEffect.createOneShot(intensity.durationMs, intensity.amplitude)
    }
}
```

## Haptic Patterns

### Pattern Definitions

```kotlin
// HapticPatterns.kt
object HapticPatterns {
    // Single click - key press
    val CLICK = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)

    // Subtle tick - minor events
    val TICK = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

    // Heavy click - mode changes
    val HEAVY = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)

    // Double click - confirmations
    val DOUBLE = VibrationEffect.createWaveform(
        longArrayOf(0, 15, 50, 15),  // timing
        intArrayOf(0, 128, 0, 128),   // amplitudes
        -1  // no repeat
    )

    // Ramp up - activation
    val RAMP_UP = VibrationEffect.createWaveform(
        longArrayOf(0, 10, 10, 10),
        intArrayOf(0, 64, 128, 200),
        -1
    )
}
```

### Pattern Selection

```kotlin
// HapticManager.kt
private fun getPatternForEvent(event: HapticEvent): VibrationEffect {
    return when (event) {
        HapticEvent.KEY_PRESS -> HapticPatterns.CLICK
        HapticEvent.BACKSPACE -> HapticPatterns.TICK
        HapticEvent.ENTER -> HapticPatterns.HEAVY
        HapticEvent.SHIFT -> HapticPatterns.TICK
        HapticEvent.SHORT_SWIPE -> HapticPatterns.TICK
        HapticEvent.LONG_PRESS -> HapticPatterns.HEAVY
        HapticEvent.TRACKPOINT_ACTIVATE -> HapticPatterns.RAMP_UP
        HapticEvent.SELECTION_ACTIVATE -> HapticPatterns.RAMP_UP
        HapticEvent.CIRCLE_GESTURE -> HapticPatterns.DOUBLE
        HapticEvent.AUTOCORRECT -> HapticPatterns.TICK
        HapticEvent.PREDICTION_SELECT -> HapticPatterns.TICK
        HapticEvent.WORD_COMPLETE -> HapticPatterns.CLICK
        else -> HapticPatterns.CLICK
    }
}
```

## Haptic Manager

### Trigger Logic

```kotlin
// HapticManager.kt
class HapticManager(
    private val config: Config,
    private val controller: VibrationController
) {
    fun trigger(event: HapticEvent) {
        if (!config.haptic.masterEnabled) return
        if (!isEventEnabled(event)) return

        val pattern = getPatternForEvent(event)
        val effect = applyIntensity(pattern, config.haptic.intensity)

        controller.vibrate(effect)
    }

    private fun isEventEnabled(event: HapticEvent): Boolean {
        return when (event) {
            HapticEvent.KEY_PRESS -> config.haptic.keyPress
            HapticEvent.BACKSPACE -> config.haptic.backspace
            HapticEvent.ENTER -> config.haptic.enter
            HapticEvent.SPACE -> config.haptic.space
            HapticEvent.SHIFT -> config.haptic.shift
            HapticEvent.SHORT_SWIPE -> config.haptic.shortSwipe
            HapticEvent.LONG_PRESS -> config.haptic.longPress
            HapticEvent.TRACKPOINT_ACTIVATE -> config.haptic.trackpointActivate
            HapticEvent.SELECTION_ACTIVATE -> config.haptic.selectionActivate
            HapticEvent.CIRCLE_GESTURE -> config.haptic.circleGesture
            HapticEvent.AUTOCORRECT -> config.haptic.autocorrect
            HapticEvent.PREDICTION_SELECT -> config.haptic.predictionSelect
            HapticEvent.WORD_COMPLETE -> config.haptic.wordComplete
        }
    }

    private fun applyIntensity(
        pattern: VibrationEffect,
        intensity: HapticIntensity
    ): VibrationEffect {
        if (intensity == HapticIntensity.SYSTEM) return pattern

        // Scale amplitude based on intensity
        return VibrationEffect.createOneShot(
            intensity.durationMs,
            intensity.amplitude
        )
    }
}
```

## Integration Points

### Keyboard View

```kotlin
// KeyboardView.kt
private fun onKeyDown(key: Key) {
    val event = when {
        key.isBackspace -> HapticEvent.BACKSPACE
        key.isEnter -> HapticEvent.ENTER
        key.isSpace -> HapticEvent.SPACE
        key.isShift -> HapticEvent.SHIFT
        else -> HapticEvent.KEY_PRESS
    }
    hapticManager.trigger(event)
}
```

### Gesture Handler

```kotlin
// Pointers.kt
private fun onShortSwipe(direction: Direction) {
    hapticManager.trigger(HapticEvent.SHORT_SWIPE)
    // ... process swipe
}

private fun onTrackpointActivate() {
    hapticManager.trigger(HapticEvent.TRACKPOINT_ACTIVATE)
    // ... activate mode
}
```

## Configuration

| Setting | Key | Default | Options |
|---------|-----|---------|---------|
| **Master Enable** | `haptic_enabled` | true | bool |
| **Intensity** | `haptic_intensity` | MEDIUM | Light/Medium/Strong/System |
| **Key Press** | `haptic_key_press` | true | bool |
| **Backspace** | `haptic_backspace` | true | bool |
| **Enter** | `haptic_enter` | true | bool |
| **Space** | `haptic_space` | false | bool |
| **Shift** | `haptic_shift` | true | bool |
| **Short Swipe** | `haptic_short_swipe` | true | bool |
| **Long Press** | `haptic_long_press` | true | bool |
| **TrackPoint** | `haptic_trackpoint` | true | bool |
| **Selection** | `haptic_selection` | true | bool |
| **Circle** | `haptic_circle` | true | bool |
| **Autocorrect** | `haptic_autocorrect` | true | bool |
| **Prediction** | `haptic_prediction` | true | bool |
| **Word Complete** | `haptic_word_complete` | false | bool |

## Related Specifications

- [Gesture System](../../../specs/gesture-system.md) - Gesture events
- [Settings System](../../../specs/settings-system.md) - Preferences
