---
title: Special Characters - Technical Specification
user_guide: ../../typing/special-characters.md
status: implemented
version: v1.2.7
---

# Special Characters Technical Specification

## Overview

Special character access through long-press popups, short swipe subkeys, and symbol keyboard layers.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| KeyValue | `KeyValue.kt` | Character and action definitions |
| KeyModifier | `KeyModifier.kt` | Modifier state handling |
| Pointers | `Pointers.kt:400-600` | Long-press and swipe detection |
| KeyboardData | `KeyboardData.kt` | Layout and subkey definitions |
| KeyPopup | `KeyPopup.kt` | Long-press popup rendering |

## Subkey System

Each key can have up to 8 subkeys in cardinal and diagonal directions:

```kotlin
// KeyboardData.kt - Key definition structure
data class Key(
    val key0: KeyValue,      // Main key (tap)
    val key1: KeyValue?,     // North (swipe up)
    val key2: KeyValue?,     // North-East
    val key3: KeyValue?,     // East (swipe right)
    val key4: KeyValue?,     // South-East
    val key5: KeyValue?,     // South (swipe down)
    val key6: KeyValue?,     // South-West
    val key7: KeyValue?,     // West (swipe left)
    val key8: KeyValue?      // North-West
)
```

## Layout XML Format

Subkeys defined in layout XML files (`res/xml/`):

```xml
<!-- Example: latn_qwerty_us.xml -->
<key
    key0="e"
    key1="3"
    key2="ê"
    key3="ë"
    key4="ē"
    key5="é"
    key6="è"
    key7="€"
    key8="ę" />
```

## Long-Press Detection

```kotlin
// Pointers.kt:~450
private fun handleLongPress(ptr: Pointer) {
    if (ptr.flags and FLAG_P_LONG_PRESS == 0) {
        ptr.flags = ptr.flags or FLAG_P_LONG_PRESS
        val key = ptr.key
        if (key.hasSubkeys()) {
            showKeyPopup(key, ptr)
        }
    }
}
```

## Character Categories

| Category | Unicode Range | Example |
|----------|---------------|---------|
| **Latin Extended** | U+00C0-U+00FF | àáâãäå |
| **Latin Extended-A** | U+0100-U+017F | āăą |
| **Currency** | U+20A0-U+20CF | €£¥₹ |
| **Math Operators** | U+2200-U+22FF | ×÷±≠ |
| **General Punctuation** | U+2000-U+206F | —–… |

## Configuration

| Setting | Key | Default |
|---------|-----|---------|
| **Long-Press Timeout** | `longpress_timeout` | 400ms |
| **Short Gesture Min** | `short_gesture_min_distance` | 28% |
| **Short Gesture Max** | `short_gesture_max_distance` | 65% |

## Modifier Interaction

Special characters interact with shift/caps lock:

```kotlin
// KeyValue.kt
fun withModifiers(mods: Int): KeyValue {
    return when {
        mods and MOD_SHIFT != 0 -> this.uppercase()
        else -> this
    }
}
```

## Related Specifications

- [Gesture System](../../../specs/gesture-system.md) - Swipe detection
- [Short Swipes Specification](../gestures/short-swipes-spec.md) - Subkey activation
- [Layout System](../../../specs/layout-system.md) - XML format
