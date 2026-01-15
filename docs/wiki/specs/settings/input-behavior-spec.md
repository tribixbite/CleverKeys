---
title: Input Behavior Settings - Technical Specification
user_guide: ../../settings/input-behavior.md
status: implemented
version: v1.2.7
---

# Input Behavior Settings Technical Specification

## Overview

The input behavior system controls text processing, auto-capitalization, punctuation handling, and gesture sensitivity.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| TextProcessor | `TextProcessor.kt` | Text transformations |
| CapitalizationHandler | `CapitalizationHandler.kt` | Auto-cap logic |
| PunctuationProcessor | `PunctuationProcessor.kt` | Smart punctuation |
| GestureConfig | `Config.kt` | Gesture thresholds |
| Pointers | `Pointers.kt` | Touch handling |

## Auto-Capitalization

### Capitalization Logic

```kotlin
// CapitalizationHandler.kt
class CapitalizationHandler(private val config: Config) {

    fun shouldCapitalize(ic: InputConnection): Boolean {
        if (config.auto_capitalize == AutoCapMode.OFF) return false

        val textBefore = ic.getTextBeforeCursor(2, 0) ?: return false

        return when (config.auto_capitalize) {
            AutoCapMode.SENTENCES -> {
                // Capitalize after sentence-ending punctuation
                val trimmed = textBefore.trim()
                trimmed.isEmpty() ||
                trimmed.lastOrNull() in listOf('.', '!', '?') ||
                trimmed.endsWith(". ") ||
                trimmed.endsWith("! ") ||
                trimmed.endsWith("? ")
            }
            AutoCapMode.WORDS -> {
                // Capitalize after any whitespace
                textBefore.lastOrNull()?.isWhitespace() == true
            }
            AutoCapMode.CHARACTERS -> true
            else -> false
        }
    }

    fun applyCapitalization(char: Char): Char {
        return if (shouldCapitalize()) char.uppercaseChar() else char
    }
}
```

### InputType Integration

```kotlin
// CapitalizationHandler.kt
fun getCapModeFromInputType(inputType: Int): AutoCapMode {
    // Respect app's capitalization hints
    return when {
        inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 ->
            AutoCapMode.CHARACTERS
        inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 ->
            AutoCapMode.WORDS
        inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 ->
            AutoCapMode.SENTENCES
        inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 ->
            AutoCapMode.OFF
        else -> config.auto_capitalize
    }
}
```

## Double-Space Period

```kotlin
// PunctuationProcessor.kt
class PunctuationProcessor {
    private var lastSpaceTime = 0L

    fun onSpace(ic: InputConnection): Boolean {
        if (!config.double_space_period) return false

        val now = System.currentTimeMillis()
        val timeSinceLastSpace = now - lastSpaceTime
        lastSpaceTime = now

        // Check if double-tap
        if (timeSinceLastSpace > DOUBLE_TAP_TIMEOUT) return false

        // Check context - don't add period after punctuation
        val before = ic.getTextBeforeCursor(2, 0) ?: return false
        if (before.isEmpty() || before.last().isPunctuation()) return false

        // Replace " " with ". "
        ic.deleteSurroundingText(1, 0)
        ic.commitText(". ", 1)

        // Next letter should be capitalized
        capitalizationHandler.forceNextCapital()

        return true
    }
}
```

## Smart Punctuation

```kotlin
// PunctuationProcessor.kt
fun processSmartPunctuation(char: Char, ic: InputConnection): String {
    if (!config.smart_punctuation) return char.toString()

    val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()

    return when {
        // Auto-space after punctuation
        char.isLetter() && before in listOf(',', '.', ';', ':') -> {
            " $char"
        }

        // Remove space before punctuation
        char.isPunctuation() && before == ' ' -> {
            ic.deleteSurroundingText(1, 0)
            char.toString()
        }

        // Smart quotes
        char == '"' && config.smart_quotes -> {
            if (before?.isWhitespace() != false) "\u201c" else "\u201d"
        }

        else -> char.toString()
    }
}
```

## Gesture Thresholds

### Swipe Detection

```kotlin
// Pointers.kt
private fun detectSwipe(ptr: Pointer): SwipeResult? {
    val dx = ptr.x - ptr.downX
    val dy = ptr.y - ptr.downY
    val distance = sqrt(dx * dx + dy * dy)
    val velocity = distance / (ptr.eventTime - ptr.downTime)

    // Threshold based on config
    val threshold = when (config.swipe_threshold) {
        SwipeThreshold.LOW -> keyWidth * 0.3f
        SwipeThreshold.NORMAL -> keyWidth * 0.5f
        SwipeThreshold.HIGH -> keyWidth * 0.7f
    }

    // Velocity requirement
    val minVelocity = when (config.swipe_speed) {
        SwipeSpeed.SLOW -> 0.3f
        SwipeSpeed.NORMAL -> 0.5f
        SwipeSpeed.FAST -> 0.8f
    }

    if (distance < threshold || velocity < minVelocity) return null

    return SwipeResult(
        direction = calculateDirection(dx, dy),
        distance = distance,
        velocity = velocity
    )
}
```

### Long Press Detection

```kotlin
// Pointers.kt
private val longPressRunnable = Runnable {
    if (currentPointer?.isDown == true) {
        onLongPress(currentPointer!!)
    }
}

private fun scheduleLongPress() {
    val delay = config.long_press_delay.toLong()
    handler.postDelayed(longPressRunnable, delay)
}
```

### Short Swipe Distance

```kotlin
// Pointers.kt
private fun detectShortSwipe(ptr: Pointer): ShortSwipeResult? {
    val dx = ptr.x - ptr.downX
    val dy = ptr.y - ptr.downY
    val distance = sqrt(dx * dx + dy * dy)

    val threshold = when (config.short_swipe_distance) {
        ShortSwipeDistance.SHORT -> keyWidth * 0.25f
        ShortSwipeDistance.NORMAL -> keyWidth * 0.4f
        ShortSwipeDistance.LONG -> keyWidth * 0.6f
    }

    if (distance < threshold) return null

    return ShortSwipeResult(
        direction = calculateDirection(dx, dy),
        distance = distance
    )
}
```

## Delete Behavior

```kotlin
// TextProcessor.kt
fun deleteBackward(ic: InputConnection) {
    when (config.delete_mode) {
        DeleteMode.CHARACTER -> {
            ic.deleteSurroundingText(1, 0)
        }
        DeleteMode.SELECTION_FIRST -> {
            val selection = ic.getSelectedText(0)
            if (selection?.isNotEmpty() == true) {
                ic.commitText("", 1)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }
    }
}

fun deleteWord(ic: InputConnection) {
    when (config.delete_word_mode) {
        DeleteWordMode.WHOLE_WORD -> {
            // Find word start
            val text = ic.getTextBeforeCursor(50, 0) ?: return
            val wordStart = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
            ic.deleteSurroundingText(text.length - wordStart - 1, 0)
        }
        DeleteWordMode.TO_BOUNDARY -> {
            // Delete to next boundary (space, punct)
            val text = ic.getTextBeforeCursor(50, 0) ?: return
            val boundary = text.indexOfLast { !it.isLetterOrDigit() }
            ic.deleteSurroundingText(text.length - boundary - 1, 0)
        }
    }
}
```

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Auto-Capitalize** | `auto_capitalize` | SENTENCES | Off/Sentences/Words/Characters |
| **Double-Space Period** | `double_space_period` | true | bool |
| **Smart Punctuation** | `smart_punctuation` | true | bool |
| **Smart Quotes** | `smart_quotes` | false | bool |
| **Long Press Delay** | `long_press_delay` | 400 | 200-1200ms |
| **Swipe Threshold** | `swipe_threshold` | NORMAL | Low/Normal/High |
| **Swipe Speed** | `swipe_speed` | NORMAL | Slow/Normal/Fast |
| **Short Swipe Distance** | `short_swipe_distance` | NORMAL | Short/Normal/Long |
| **Delete Mode** | `delete_mode` | CHARACTER | Character/SelectionFirst |
| **Delete Word Mode** | `delete_word_mode` | TO_BOUNDARY | WholeWord/ToBoundary |

## Related Specifications

- [Gesture System](../../../specs/gesture-system.md) - Gesture recognition
- [Settings System](../../../specs/settings-system.md) - Preferences
- [Autocorrect](../typing/autocorrect-spec.md) - Text correction
