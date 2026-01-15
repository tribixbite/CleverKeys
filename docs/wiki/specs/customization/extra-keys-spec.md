---
title: Extra Keys - Technical Specification
user_guide: ../../customization/extra-keys.md
status: implemented
version: v1.2.7
---

# Extra Keys Technical Specification

## Overview

Extra keys are additional keys placed on the bottom row alongside the spacebar, providing quick access to modifiers (Ctrl, Alt), navigation keys (Tab, Escape), and function keys.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| ExtraKeysManager | `ExtraKeysManager.kt` | Key definitions and ordering |
| BottomRowLayout | `KeyboardView.kt:800-900` | Layout calculation |
| Config | `Config.kt` | User preferences |
| KeyValue | `KeyValue.kt` | Key action definitions |

## Data Model

### Extra Key Definition

```kotlin
// ExtraKeysManager.kt
enum class ExtraKey(
    val displayLabel: String,
    val keyValue: KeyValue,
    val defaultPosition: Position
) {
    CTRL("Ctrl", KeyValue.Modifier(Modifier.CTRL), Position.LEFT),
    ALT("Alt", KeyValue.Modifier(Modifier.ALT), Position.LEFT),
    META("Meta", KeyValue.Modifier(Modifier.META), Position.LEFT),
    TAB("Tab", KeyValue.Event(Event.TAB), Position.RIGHT),
    ESCAPE("Esc", KeyValue.Event(Event.ESCAPE), Position.RIGHT),
    FN("Fn", KeyValue.Modifier(Modifier.FN), Position.RIGHT),
    ARROWS("←→", KeyValue.Special(Special.ARROWS), Position.RIGHT)
}

enum class Position { LEFT, RIGHT }
```

### Configuration Storage

```kotlin
// Config.kt
// Stored as comma-separated list
// Example: "CTRL,ALT|TAB,ESCAPE"
// Format: left_keys|right_keys

val extra_keys_left: String = "CTRL,ALT"
val extra_keys_right: String = "TAB"
```

## Layout Calculation

### Space Distribution

```kotlin
// KeyboardView.kt:~850
private fun calculateBottomRowLayout(): BottomRowLayout {
    val leftKeys = config.extra_keys_left.split(",").filter { it.isNotEmpty() }
    val rightKeys = config.extra_keys_right.split(",").filter { it.isNotEmpty() }

    val totalExtraKeys = leftKeys.size + rightKeys.size

    // Reserve minimum spacebar width
    val minSpacebarWidth = keyWidth * 3
    val availableWidth = rowWidth - minSpacebarWidth

    // Each extra key gets equal width
    val extraKeyWidth = if (totalExtraKeys > 0) {
        min(keyWidth, availableWidth / totalExtraKeys)
    } else 0f

    val usedByExtras = extraKeyWidth * totalExtraKeys
    val spacebarWidth = rowWidth - usedByExtras

    return BottomRowLayout(
        leftKeys = leftKeys.map { ExtraKey.valueOf(it) },
        rightKeys = rightKeys.map { ExtraKey.valueOf(it) },
        extraKeyWidth = extraKeyWidth,
        spacebarWidth = spacebarWidth
    )
}
```

### Visual Layout

```
┌──────────────────────────────────────────────┐
│ [Ctrl][Alt]   [     Spacebar     ]   [Tab]   │
│   ↑     ↑              ↑               ↑     │
│  left  left         center          right    │
└──────────────────────────────────────────────┘
```

## Modifier Key Behavior

```kotlin
// Pointers.kt:~700
private fun handleModifierKey(key: ExtraKey, isDown: Boolean) {
    when (key) {
        ExtraKey.CTRL -> {
            if (isDown) {
                activeModifiers = activeModifiers or Modifier.CTRL
                // Visual feedback - key stays highlighted
                invalidateKey(key)
            } else {
                // Modifier released on next key press
            }
        }
        // Similar for ALT, META, FN
    }
}

// Modifiers are "sticky" until next key press
private fun handleKeyPress(keyValue: KeyValue) {
    val modifiedValue = applyModifiers(keyValue, activeModifiers)
    sendKeyValue(modifiedValue)

    // Clear modifiers after use
    activeModifiers = 0
    invalidateModifierKeys()
}
```

## Extra Key Subkeys

Extra keys also support subkeys via short swipe:

```kotlin
// ExtraKeysManager.kt
val extraKeySubkeys = mapOf(
    ExtraKey.CTRL to mapOf(
        Direction.N to KeyValue.Event(Event.SELECT_ALL),  // Ctrl+A
        Direction.E to KeyValue.Event(Event.COPY),        // Ctrl+C
        Direction.W to KeyValue.Event(Event.CUT),         // Ctrl+X
        Direction.S to KeyValue.Event(Event.PASTE)        // Ctrl+V
    ),
    ExtraKey.TAB to mapOf(
        Direction.W to KeyValue.Event(Event.SHIFT_TAB)    // Shift+Tab
    )
)
```

## Impact on Spacebar

| Extra Keys | Spacebar Width |
|------------|----------------|
| 0 | 100% (maximum) |
| 1 | ~85% |
| 2 | ~70% |
| 3 | ~60% |
| 4 | ~50% |
| 5+ | ~40% (minimum) |

## Arrow Keys Mode

```kotlin
// ExtraKeysManager.kt
// When ARROWS extra key is added, it creates a 4-key group
private fun expandArrowsKey(): List<ExtraKey> {
    return listOf(
        ExtraKey.ARROW_LEFT,
        ExtraKey.ARROW_UP,
        ExtraKey.ARROW_DOWN,
        ExtraKey.ARROW_RIGHT
    )
}
```

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Left Extra Keys** | `extra_keys_left` | "" | Any combination |
| **Right Extra Keys** | `extra_keys_right` | "" | Any combination |
| **Max Extra Keys** | - | 6 | Hard limit |

## Related Specifications

- [Per-Key Actions](per-key-actions-spec.md) - Subkey customization
- [Layout System](../../../specs/layout-system.md) - Keyboard layout
- [Gesture System](../../../specs/gesture-system.md) - Short swipes on extra keys
