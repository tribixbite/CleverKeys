---
title: Per-Key Actions - Technical Specification
user_guide: ../../customization/per-key-actions.md
status: implemented
version: v1.2.7
---

# Per-Key Actions Technical Specification

## Overview

Per-key action customization allows users to define custom characters, actions, or macros for each of the 8 swipe directions on any key. This system integrates with the short swipe gesture recognizer.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| SubkeyManager | `SubkeyManager.kt:1-150` | Manages subkey definitions |
| CustomizationStore | `CustomizationStore.kt` | Persistence layer |
| SubkeyEditor | `SubkeyEditorActivity.kt` | Visual editor UI |
| ShortSwipeHandler | `Pointers.kt:400-600` | Gesture detection |
| Config | `Config.kt` | Default subkey mappings |

## Data Model

### Subkey Definition

```kotlin
// SubkeyManager.kt
data class SubkeyDefinition(
    val keyCode: Int,           // Parent key code
    val direction: Direction,   // N, NE, E, SE, S, SW, W, NW
    val action: SubkeyAction    // Character, action, or macro
)

enum class Direction {
    N, NE, E, SE, S, SW, W, NW
}

sealed class SubkeyAction {
    data class Character(val char: String) : SubkeyAction()
    data class KeyAction(val event: Event) : SubkeyAction()
    data class Macro(val sequence: List<SubkeyAction>) : SubkeyAction()
}
```

### Storage Format

```kotlin
// CustomizationStore.kt
// Stored as JSON in SharedPreferences
{
    "customizations": {
        "key_q": {
            "N": {"type": "char", "value": "1"},
            "NE": {"type": "char", "value": "!"},
            "E": {"type": "action", "value": "TAB"},
            ...
        }
    }
}
```

## Direction Mapping

```
    NW(-1,-1)  N(0,-1)  NE(1,-1)
           \    |    /
    W(-1,0) -- KEY -- E(1,0)
           /    |    \
    SW(-1,1)  S(0,1)  SE(1,1)
```

### Direction Calculation

```kotlin
// Pointers.kt:~450
fun calculateDirection(dx: Float, dy: Float): Direction {
    val angle = atan2(dy, dx)
    val degrees = Math.toDegrees(angle.toDouble())

    return when {
        degrees in -22.5..22.5 -> Direction.E
        degrees in 22.5..67.5 -> Direction.SE
        degrees in 67.5..112.5 -> Direction.S
        degrees in 112.5..157.5 -> Direction.SW
        degrees > 157.5 || degrees < -157.5 -> Direction.W
        degrees in -157.5..-112.5 -> Direction.NW
        degrees in -112.5..-67.5 -> Direction.N
        degrees in -67.5..-22.5 -> Direction.NE
        else -> Direction.E
    }
}
```

## Customization Flow

```
User taps key in editor
        ↓
SubkeyEditorActivity loads current config
        ↓
User selects direction
        ↓
Action picker dialog shows options
        ↓
User selects character/action
        ↓
CustomizationStore.save(keyCode, direction, action)
        ↓
SubkeyManager reloads definitions
        ↓
Keyboard uses new mapping
```

## Activation Integration

```kotlin
// Pointers.kt:~500
private fun handleShortSwipe(ptr: Pointer, direction: Direction) {
    val key = ptr.key

    // Check for custom subkey first
    val customAction = subkeyManager.getCustomAction(key.keyCode, direction)
    if (customAction != null) {
        executeSubkeyAction(customAction)
        return
    }

    // Fall back to default subkey
    val defaultSubkey = key.getSubkey(direction)
    if (defaultSubkey != null) {
        sendKeyValue(defaultSubkey)
    }
}
```

## Built-in Actions

| Action | Event Code | Description |
|--------|------------|-------------|
| **Delete Word** | `Event.DELETE_WORD` | Delete previous word |
| **Cursor Left** | `Event.CURSOR_LEFT` | Move cursor left |
| **Cursor Right** | `Event.CURSOR_RIGHT` | Move cursor right |
| **Home** | `Event.HOME` | Jump to line start |
| **End** | `Event.END` | Jump to line end |
| **Tab** | `Event.TAB` | Insert tab |
| **Escape** | `Event.ESCAPE` | Send escape |
| **Undo** | `Event.UNDO` | Undo action |
| **Redo** | `Event.REDO` | Redo action |
| **Copy** | `Event.COPY` | Copy selection |
| **Cut** | `Event.CUT` | Cut selection |
| **Paste** | `Event.PASTE` | Paste clipboard |
| **Select All** | `Event.SELECT_ALL` | Select all text |

## Profile Integration

```kotlin
// ProfileManager.kt
fun exportCustomizations(): Map<String, Any> {
    return mapOf(
        "version" to 1,
        "customizations" to customizationStore.getAllCustomizations()
    )
}

fun importCustomizations(data: Map<String, Any>) {
    val customizations = data["customizations"] as? Map<String, Any> ?: return
    customizationStore.importAll(customizations)
    subkeyManager.reload()
}
```

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| **Customizations Enabled** | `pref_custom_subkeys_enabled` | true | Enable custom subkeys |
| **Per-Layout** | `pref_custom_per_layout` | false | Separate per layout |

## Related Specifications

- [Short Swipe Customization](../../../specs/short-swipe-customization.md) - Full customization system
- [Gesture System](../../../specs/gesture-system.md) - Direction detection
- [Profile System](../../../specs/profile_system_restoration.md) - Import/export
