---
title: Common Issues - Technical Specification
user_guide: ../../troubleshooting/common-issues.md
status: implemented
version: v1.2.7
---

# Common Issues Technical Specification

## Overview

This specification documents the technical causes of common user issues and the diagnostic/resolution mechanisms implemented in CleverKeys.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| DiagnosticsManager | `DiagnosticsManager.kt` | System health checks |
| ErrorReporter | `ErrorReporter.kt` | Crash and error logging |
| ConfigValidator | `ConfigValidator.kt` | Settings validation |
| CompatibilityChecker | `CompatibilityChecker.kt` | App compatibility |

## Keyboard Visibility Issues

### Root Causes

```kotlin
// KeyboardService.kt
enum class KeyboardNotShowingCause {
    NOT_ENABLED,           // Not in system IME list
    NOT_DEFAULT,           // Not selected as default
    SERVICE_CRASHED,       // InputMethodService crashed
    VIEW_NOT_ATTACHED,     // KeyboardView not in window
    APP_BLOCKING,          // App blocking soft keyboard
    HARDWARE_KEYBOARD      // Physical keyboard connected
}
```

### Diagnostic Flow

```kotlin
// DiagnosticsManager.kt
fun diagnoseKeyboardNotShowing(): List<KeyboardNotShowingCause> {
    val causes = mutableListOf<KeyboardNotShowingCause>()

    // Check if enabled in system
    val imm = context.getSystemService(InputMethodManager::class.java)
    val enabledMethods = imm.enabledInputMethodList

    if (enabledMethods.none { it.packageName == context.packageName }) {
        causes.add(NOT_ENABLED)
    }

    // Check if default
    val defaultIme = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    )
    if (!defaultIme.startsWith(context.packageName)) {
        causes.add(NOT_DEFAULT)
    }

    // Check service state
    if (!KeyboardService.isRunning) {
        causes.add(SERVICE_CRASHED)
    }

    // Check view attachment
    if (KeyboardService.instance?.keyboardView?.isAttachedToWindow != true) {
        causes.add(VIEW_NOT_ATTACHED)
    }

    return causes
}
```

## Typing Issues

### Autocorrect Diagnostics

```kotlin
// DiagnosticsManager.kt
data class AutocorrectDiagnostics(
    val isEnabled: Boolean,
    val strength: AutocorrectStrength,
    val languagesEnabled: List<String>,
    val dictionaryLoaded: Boolean,
    val lastCorrectionTime: Long,
    val correctionAccuracyRate: Float
)

fun diagnoseAutocorrect(): AutocorrectDiagnostics {
    return AutocorrectDiagnostics(
        isEnabled = config.autocorrect_enabled,
        strength = config.autocorrect_strength,
        languagesEnabled = languageManager.enabledLanguages.map { it.code },
        dictionaryLoaded = dictionaryManager.isLoaded(),
        lastCorrectionTime = autocorrectEngine.lastCorrectionTime,
        correctionAccuracyRate = autocorrectEngine.calculateAccuracyRate()
    )
}
```

### Prediction Diagnostics

```kotlin
// DiagnosticsManager.kt
data class PredictionDiagnostics(
    val isEnabled: Boolean,
    val modelLoaded: Boolean,
    val vocabularySize: Int,
    val averagePredictionTimeMs: Long,
    val beamWidth: Int,
    val lastPredictionScore: Float
)

fun diagnosePredictions(): PredictionDiagnostics {
    val processor = swipeTrajectoryProcessor

    return PredictionDiagnostics(
        isEnabled = config.predictions_enabled,
        modelLoaded = processor.isModelLoaded(),
        vocabularySize = processor.getVocabularySize(),
        averagePredictionTimeMs = processor.getAveragePredictionTime(),
        beamWidth = config.beam_width,
        lastPredictionScore = processor.getLastPredictionScore()
    )
}
```

## Gesture Issues

### Gesture Diagnostics

```kotlin
// DiagnosticsManager.kt
data class GestureDiagnostics(
    val shortSwipeThreshold: Float,
    val longSwipeThreshold: Float,
    val longPressDelay: Long,
    val lastGestureType: String,
    val lastGestureDistance: Float,
    val lastGestureVelocity: Float,
    val lastGestureRecognized: Boolean
)

fun diagnoseGestures(): GestureDiagnostics {
    val pointers = Pointers.instance

    return GestureDiagnostics(
        shortSwipeThreshold = config.short_swipe_threshold,
        longSwipeThreshold = config.long_swipe_threshold,
        longPressDelay = config.long_press_delay,
        lastGestureType = pointers.lastGestureType.name,
        lastGestureDistance = pointers.lastGestureDistance,
        lastGestureVelocity = pointers.lastGestureVelocity,
        lastGestureRecognized = pointers.lastGestureRecognized
    )
}
```

### Gesture Threshold Recommendations

```kotlin
// GestureAdvisor.kt
fun recommendGestureThresholds(userPatterns: GesturePatterns): ThresholdRecommendations {
    val avgSwipeDistance = userPatterns.averageSwipeDistance
    val avgSwipeVelocity = userPatterns.averageSwipeVelocity

    return ThresholdRecommendations(
        shortSwipe = (avgSwipeDistance * 0.4f).coerceIn(20f, 60f),
        longSwipe = (avgSwipeDistance * 0.8f).coerceIn(50f, 150f),
        longPressDelay = when {
            userPatterns.accidentalLongPressRate > 0.2f -> 600L
            userPatterns.missedLongPressRate > 0.2f -> 300L
            else -> 400L
        }
    )
}
```

## App Compatibility

### Compatibility Check

```kotlin
// CompatibilityChecker.kt
data class AppCompatibility(
    val packageName: String,
    val inputType: Int,
    val isPasswordField: Boolean,
    val supportsExtractedText: Boolean,
    val supportsCursorMovement: Boolean,
    val knownIssues: List<String>
)

fun checkAppCompatibility(packageName: String): AppCompatibility {
    val knownIssues = KNOWN_APP_ISSUES[packageName] ?: emptyList()

    val ic = currentInputConnection
    val inputType = currentInputEditorInfo?.inputType ?: 0

    return AppCompatibility(
        packageName = packageName,
        inputType = inputType,
        isPasswordField = isPasswordInput(inputType),
        supportsExtractedText = ic?.getExtractedText(
            ExtractedTextRequest(), 0
        ) != null,
        supportsCursorMovement = testCursorMovement(ic),
        knownIssues = knownIssues
    )
}

// Known problematic apps
private val KNOWN_APP_ISSUES = mapOf(
    "com.termux" to listOf("Requires Ctrl key for terminal"),
    "com.some.game" to listOf("Blocks soft keyboard in gameplay"),
    // ... more known issues
)
```

## Error Reporting

### Debug Export

```kotlin
// ErrorReporter.kt
fun exportDebugInfo(): String {
    val diagnostics = mapOf(
        "device" to getDeviceInfo(),
        "app" to getAppInfo(),
        "keyboard" to diagnoseKeyboardNotShowing(),
        "typing" to diagnoseAutocorrect(),
        "predictions" to diagnosePredictions(),
        "gestures" to diagnoseGestures(),
        "config" to config.toMap(),
        "recentErrors" to getRecentErrors()
    )

    return Json.encodeToString(diagnostics)
}

private fun getDeviceInfo(): Map<String, String> = mapOf(
    "model" to Build.MODEL,
    "manufacturer" to Build.MANUFACTURER,
    "android_version" to Build.VERSION.RELEASE,
    "sdk" to Build.VERSION.SDK_INT.toString(),
    "screen_density" to resources.displayMetrics.density.toString()
)
```

## Configuration Validation

```kotlin
// ConfigValidator.kt
fun validateConfiguration(): List<ConfigIssue> {
    val issues = mutableListOf<ConfigIssue>()

    // Check for conflicting settings
    if (config.haptic_enabled && !hasVibrator()) {
        issues.add(ConfigIssue.HAPTIC_NO_HARDWARE)
    }

    // Check for extreme values
    if (config.beam_width > 10) {
        issues.add(ConfigIssue.BEAM_WIDTH_TOO_HIGH)
    }

    // Check language/dictionary alignment
    config.enabled_languages.forEach { lang ->
        if (!dictionaryManager.hasDictionary(lang)) {
            issues.add(ConfigIssue.MISSING_DICTIONARY)
        }
    }

    return issues
}

enum class ConfigIssue {
    HAPTIC_NO_HARDWARE,
    BEAM_WIDTH_TOO_HIGH,
    MISSING_DICTIONARY,
    LAYOUT_NOT_FOUND,
    THEME_INVALID
}
```

## Related Specifications

- [Settings System](../../../specs/settings-system.md) - Configuration
- [Neural Prediction](../../../specs/neural-prediction.md) - Prediction system
- [Gesture System](../../../specs/gesture-system.md) - Gesture recognition
