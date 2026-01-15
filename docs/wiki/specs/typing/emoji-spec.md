---
title: Emoji - Technical Specification
user_guide: ../../typing/emoji.md
status: implemented
version: v1.2.7
---

# Emoji Technical Specification

## Overview

Emoji keyboard implementation using Android's EmojiCompat library with category navigation and recent history.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| EmojiGridView | `EmojiGridView.kt` | Emoji grid rendering |
| EmojiCategory | `EmojiCategory.kt` | Category definitions |
| EmojiData | `EmojiData.kt` | Emoji database |
| KeyValue | `KeyValue.kt` | Emoji as KeyValue.Char |
| SharedPreferences | - | Recent emoji storage |

## Emoji Categories

```kotlin
// EmojiCategory.kt
enum class EmojiCategory(val icon: Int, val label: String) {
    RECENT(R.drawable.ic_recent, "Recent"),
    SMILEYS(R.drawable.ic_smiley, "Smileys"),
    PEOPLE(R.drawable.ic_people, "People"),
    ANIMALS(R.drawable.ic_animals, "Animals"),
    FOOD(R.drawable.ic_food, "Food"),
    ACTIVITIES(R.drawable.ic_activities, "Activities"),
    TRAVEL(R.drawable.ic_travel, "Travel"),
    OBJECTS(R.drawable.ic_objects, "Objects"),
    SYMBOLS(R.drawable.ic_symbols, "Symbols"),
    FLAGS(R.drawable.ic_flags, "Flags")
}
```

## Recent Emoji Storage

```kotlin
// Storage format in SharedPreferences
// Key: "recent_emoji"
// Value: JSON array of recent emoji codepoints
// ["😀", "🎉", "❤️", "👍", ...]
// Max size: 50 entries (configurable)
```

## Emoji Insertion

```kotlin
// EmojiGridView.kt:~120
fun onEmojiSelected(emoji: String) {
    // Add to recent
    addToRecent(emoji)

    // Send to input connection
    val ic = inputConnection ?: return
    ic.commitText(emoji, 1)

    // Trigger haptic
    triggerHaptic(HapticEvent.KEY_PRESS)
}
```

## Skin Tone Variants

Emoji with skin tone support use Unicode modifiers:

| Modifier | Unicode | Skin Tone |
|----------|---------|-----------|
| 🏻 | U+1F3FB | Light |
| 🏼 | U+1F3FC | Medium-Light |
| 🏽 | U+1F3FD | Medium |
| 🏾 | U+1F3FE | Medium-Dark |
| 🏿 | U+1F3FF | Dark |

```kotlin
// Stored preference per base emoji
// Key: "emoji_skin_tone_{codepoint}"
// Value: modifier codepoint or null
```

## Layout Integration

Emoji key defined in layout XML:

```xml
<!-- bottom_row.xml -->
<key key0="loc emoji" ... />
```

KeyValue type: `KeyValue.Event(Event.SWITCH_EMOJI)`

## Configuration

| Setting | Key | Default |
|---------|-----|---------|
| **Recent Count** | `emoji_recent_count` | 50 |
| **Show Skin Variants** | `emoji_skin_variants` | true |
| **Default Category** | `emoji_default_category` | RECENT |

## EmojiCompat Integration

Uses AndroidX EmojiCompat for consistent rendering:

```kotlin
// Application.kt
EmojiCompat.init(
    BundledEmojiCompatConfig(this)
        .setReplaceAll(false)
)
```

## Related Specifications

- [Special Characters Specification](special-characters-spec.md)
- [Layout System](../../../specs/layout-system.md)
