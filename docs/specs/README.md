# CleverKeys Technical Specifications

Technical documentation for CleverKeys, an Android keyboard with neural swipe typing. These specs are designed for LLM coding agents to understand the codebase architecture and implementation details.

## Specification Index

### Core System

| Spec | Description |
|------|-------------|
| [Core Keyboard System](./core-keyboard-system.md) | InputMethodService, view hierarchy, key events, layout management |
| [Gesture System](./gesture-system.md) | Swipe detection, multi-touch, gesture classification |
| [Layout System](./layout-system.md) | XML layouts, ExtraKeys, key positioning |

### Neural Prediction

| Spec | Description |
|------|-------------|
| [Neural Prediction](./neural-prediction.md) | ONNX transformer, beam search, vocabulary |
| [Neural Multilanguage](./neural-multilanguage-architecture.md) | Multi-language swipe typing architecture |
| [Cursor-Aware Predictions](./cursor-aware-predictions.md) | Context-aware prediction enhancement |
| [KV-Cache Optimization](./kv-cache-optimization.md) | ONNX inference optimization |
| [Memory Pool Optimization](./memory-pool-optimization.md) | Memory management for neural inference |

### Dictionary & Language

| Spec | Description |
|------|-------------|
| [Dictionary System](./dictionary-and-language-system.md) | Word lookup, frequency ranking, user dictionary |
| [Secondary Language](./secondary-language-integration.md) | Multi-language typing, language detection |
| [Language-Specific Dictionary](./language-specific-dictionary-manager.md) | Per-language dictionary management |

### Customization

| Spec | Description |
|------|-------------|
| [Short Swipe Customization](./short-swipe-customization.md) | Per-key gesture customization, CommandRegistry |
| [Per-Layout Subkey](./per-layout-subkey-customization.md) | Layout-specific subkey configuration |
| [Subkey Customization](./subkey-customization-current.md) | Current subkey implementation details |
| [Profile System](./profile_system_restoration.md) | Settings backup/restore, layout import/export |

### Settings & Modes

| Spec | Description |
|------|-------------|
| [Settings System](./settings-system.md) | SharedPreferences, settings UI, configuration |
| [Settings-Layout Integration](./settings-layout-integration.md) | GUI mapping to layout system |
| [Cursor Navigation](./cursor-navigation-system.md) | Spacebar slider, arrow key navigation |
| [TrackPoint Mode](./trackpoint-navigation-mode.md) | Joystick-style cursor control |
| [Selection-Delete Mode](./selection-delete-mode.md) | Text selection via backspace gesture |
| [Password Field Mode](./password-field-mode.md) | Secure password input handling |

### Features

| Spec | Description |
|------|-------------|
| [Quick Settings Tile](./quick-settings-tile.md) | Android notification shade tile |
| [Clipboard Privacy](./clipboard-privacy.md) | Password manager exclusion |
| [Timestamp Keys](./timestamp-keys.md) | Date/time insertion keys |
| [Termux Integration](./termux_integration.md) | Terminal keyboard optimizations |

### Reference

| Spec | Description |
|------|-------------|
| [Architectural Decisions](./architectural-decisions.md) | ADRs for major design choices |
| [Performance Optimization](./performance-optimization.md) | Rendering, memory, ONNX performance |
| [SPEC_TEMPLATE.md](./SPEC_TEMPLATE.md) | Template for new specifications |

## Key Source Files

| File | Purpose |
|------|---------|
| `CleverKeysService.kt` | Main InputMethodService |
| `Keyboard2View.kt` | Custom view rendering |
| `Keyboard2.kt` | Key layout and state management |
| `KeyEventHandler.kt` | Key press processing |
| `InputConnectionManager.kt` | Text editing interface |
| `Pointers.kt` | Touch handling and gestures |
| `Config.kt` | All settings with defaults |
| `SettingsActivity.kt` | Settings UI (Jetpack Compose) |
| `KeyValue.kt` | Key definitions (300+ keys) |
| `SwipeTrajectoryProcessor.kt` | Neural swipe processing |
| `OptimizedVocabulary.kt` | Dictionary management |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    CleverKeysService                        │
│                  (InputMethodService)                       │
├─────────────────────────────────────────────────────────────┤
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────┐  │
│  │ Keyboard2View  │  │ KeyEventHandler│  │ InputConnection│ │
│  │ (Rendering)    │  │ (Processing)   │  │ Manager       │ │
│  └───────┬────────┘  └───────┬────────┘  └──────┬───────┘  │
│          │                   │                   │          │
│  ┌───────▼────────┐  ┌───────▼────────┐         │          │
│  │   Keyboard2    │  │   Pointers     │         │          │
│  │ (Layout/State) │  │ (Touch/Gesture)│         │          │
│  └───────┬────────┘  └───────┬────────┘         │          │
│          │                   │                   │          │
│  ┌───────▼───────────────────▼───────────────────▼───────┐  │
│  │                    Config                              │  │
│  │            (Settings, Preferences)                     │  │
│  └────────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│                  Neural Prediction Pipeline                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │   Swipe      │  │    ONNX      │  │  Vocabulary  │       │
│  │  Trajectory  │→ │  Inference   │→ │   Filter     │       │
│  │  Processor   │  │  (Encoder/   │  │ (Dictionary) │       │
│  │              │  │   Decoder)   │  │              │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

## Common Patterns

### Settings Access

```kotlin
// Read setting
val value = Config.globalConfig().settingName

// Save setting
Config.globalConfig().apply {
    settingName = newValue
    saveSetting("setting_key", newValue)
}
```

### Key Event Handling

```kotlin
// In KeyEventHandler
fun handleKeyPress(key: KeyValue): Boolean {
    return when (key.kind) {
        Kind.Char -> commitText(key.char.toString())
        Kind.String -> commitText(key.string)
        Kind.Modifier -> handleModifier(key)
        Kind.Event -> handleEvent(key.event)
        // ... other kinds
    }
}
```

### Gesture Classification

```kotlin
// In Pointers.kt
fun classifyGesture(pointer: Pointer): GestureType {
    return when {
        pointer.isSwipeTyping -> GestureType.SWIPE_TYPING
        pointer.isShortSwipe -> GestureType.SHORT_SWIPE
        pointer.isLongPress -> GestureType.LONG_PRESS
        else -> GestureType.TAP
    }
}
```

## Related Documentation

- [User Guide Wiki](../wiki/TABLE_OF_CONTENTS.md) - End-user documentation
- [CHANGELOG](../../CHANGELOG.md) - Version history
- [TODO](../../memory/todo.md) - Current tasks
