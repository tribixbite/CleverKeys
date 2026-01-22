---
title: Smart Punctuation - Technical Specification
user_guide: ../../typing/smart-punctuation.md
status: implemented
version: v1.2.8
---

# Smart Punctuation Technical Specification

## Overview

Smart punctuation automatically removes spaces before punctuation marks to attach them to the preceding word. As of v1.2.7, this behavior respects whether the space was auto-inserted (swipe/suggestion) vs manually typed. As of v1.2.8, sentence-ending punctuation (. ! ?) automatically adds a trailing space to enable autocapitalization for the next word.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| KeyEventHandler | `KeyEventHandler.kt:253-284` | Main smart punctuation logic |
| PredictionContextTracker | `PredictionContextTracker.kt:99-101` | Tracks auto-inserted space flag |
| KeyEventReceiverBridge | `KeyEventReceiverBridge.kt:117-124` | Bridge for flag access |
| InputCoordinator | `InputCoordinator.kt:739-742` | Sets flag on swipe auto-space |
| SuggestionHandler | `SuggestionHandler.kt:659-666` | Sets flag on suggestion auto-space |

## Architecture

```
User types punctuation
         ↓
KeyEventHandler.sendText()
         ↓
Check: isPunctChar && lastCharIsSpace && spaceWasAutoInserted?
         ↓
    Yes: deleteSurroundingText(1, 0) → attach punctuation
    No:  keep space → punctuation after space
         ↓
Clear lastSpaceWasAutoInserted flag (for next char)
```

## Data Flow

### Auto-Space Tracking

```kotlin
// PredictionContextTracker.kt:99-101
// v1.2.7: Track whether last space was auto-inserted (swipe/suggestion) vs manually typed
var lastSpaceWasAutoInserted: Boolean = false
```

### Setting Flag on Auto-Insert

```kotlin
// InputCoordinator.kt:739-742 (swipe path)
if (shouldAddTrailingSpace) {
    contextTracker.lastSpaceWasAutoInserted = true
}

// SuggestionHandler.kt:659-666 (suggestion tap path)
val addedTrailingSpace = !(!config.auto_space_after_suggestion && !isSwipeAutoInsert) &&
    !(config.termux_mode_enabled && !isSwipeAutoInsert && inTermuxApp) &&
    !hasSpaceAfter
if (addedTrailingSpace) {
    contextTracker.lastSpaceWasAutoInserted = true
}
```

### Checking and Clearing Flag

```kotlin
// KeyEventHandler.kt:260-284
val smartPuncEnabled = Config.globalConfig().smart_punctuation
val isPunctChar = isSmartPunctuationChar(char)
val isQuote = isQuoteChar(char)

if (smartPuncEnabled && (isPunctChar || isQuote)) {
    val textBefore = conn.getTextBeforeCursor(500, 0)
    val lastCharIsSpace = textBefore?.lastOrNull() == ' '
    val spaceWasAutoInserted = recv.wasLastSpaceAutoInserted()

    if (isPunctChar && lastCharIsSpace && spaceWasAutoInserted) {
        conn.deleteSurroundingText(1, 0)  // Remove space, attach punctuation
        // v1.2.8: For sentence-ending punct, add space after for autocap
        if (isSentenceEndingPunctuation(char)) {
            textToCommit = "$char "
            recv.setLastSpaceAutoInserted(true)
        }
    } else if (isQuote && lastCharIsSpace && spaceWasAutoInserted) {
        // Quote logic...
    }
}

// Clear flag AFTER check (for next character)
recv.setLastSpaceAutoInserted(false)
```

### Sentence-Ending Punctuation (v1.2.8)

```kotlin
// KeyEventHandler.kt:314-320
private fun isSentenceEndingPunctuation(c: Char): Boolean {
    return when (c) {
        '.', '!', '?' -> true
        else -> false
    }
}
```

When a sentence-ending punctuation mark is attached via smart punctuation, a space is automatically added after it. This enables Android's `getCursorCapsMode()` to detect the sentence boundary and trigger autocapitalization for the next word.

## Key Code Patterns

### Smart Punctuation Characters

```kotlin
// KeyEventHandler.kt:294-300
private fun isSmartPunctuationChar(c: Char): Boolean {
    return when (c) {
        '.', ',', '!', '?', ';', ':', ')', ']', '}' -> true
        '\'', '"' -> false  // Quotes handled separately
        else -> false
    }
}
```

### Quote Detection

```kotlin
// KeyEventHandler.kt:302-335
private fun isQuoteChar(c: Char): Boolean {
    return c == '\'' || c == '"'
}

private fun isLikelyApostrophe(quote: Char, textBefore: CharSequence?): Boolean {
    if (quote != '\'') return false
    if (textBefore.isNullOrEmpty()) return false
    val lastChar = textBefore.last()
    // After letter/digit = likely apostrophe (don't, 90's)
    return lastChar.isLetterOrDigit()
}

private fun isClosingQuote(quote: Char, textBefore: CharSequence?): Boolean {
    if (textBefore.isNullOrEmpty()) return false
    // Count matching quotes; odd = closing, even = opening
    val count = textBefore.count { it == quote }
    return count % 2 == 1
}
```

### Double-Space to Period

```kotlin
// KeyEventHandler.kt:236-249
if (config.double_space_to_period && !isKeyRepeat &&
    text.length == 1 && text[0] == ' ' && lastTypedChar == ' ' &&
    (currentTime - lastTypedTimestamp) < doubleSpaceThresholdMs) {
    val textBefore = conn.getTextBeforeCursor(2, 0)
    val charBeforeSpace = textBefore?.getOrNull(0)
    if (charBeforeSpace != null && charBeforeSpace.isLetterOrDigit()) {
        conn.deleteSurroundingText(1, 0)
        textToCommit = ". "
        lastTypedChar = '.'
    }
}
```

## State Machine

```
                    ┌─────────────────┐
                    │  flag = false   │ (initial state)
                    └────────┬────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                   │
         ▼                   ▼                   ▼
   ┌───────────┐      ┌───────────┐      ┌───────────┐
   │   Swipe   │      │ Tap Sugg  │      │ Type Char │
   │   Word    │      │  estion   │      │  Manually │
   └─────┬─────┘      └─────┬─────┘      └─────┬─────┘
         │                   │                   │
         │ auto-space        │ auto-space        │ no change
         │ flag=true         │ flag=true         │ flag cleared
         ▼                   ▼                   ▼
   ┌─────────────────────────────────────────────────┐
   │              Next Character Typed               │
   └───────────────────────┬─────────────────────────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │  Punct   │ │  Quote   │ │  Other   │
        │  Char    │ │  Char    │ │  Char    │
        └────┬─────┘ └────┬─────┘ └────┬─────┘
             │            │            │
             ▼            ▼            ▼
      if flag=true:  if flag=true   no action
      delete space   & closing:
                     delete space
             │            │            │
             └────────────┼────────────┘
                          │
                          ▼
                    flag = false
```

## Configuration

| Setting | Config Key | Default | Type |
|---------|------------|---------|------|
| Smart Punctuation | `smart_punctuation` | `true` | Boolean |
| Double Space Period | `double_space_to_period` | `true` | Boolean |
| Double Space Threshold | `double_space_threshold` | `500` | Int (ms) |

### Config.kt References

```kotlin
// Config.kt defaults
const val SMART_PUNCTUATION = true           // Line 77
const val DOUBLE_SPACE_TO_PERIOD = true      // Line 86
const val DOUBLE_SPACE_THRESHOLD = 500       // Line 87

// Config.kt variables
@JvmField var smart_punctuation = true       // Line 462
@JvmField var double_space_to_period = true  // Line 460
@JvmField var double_space_threshold = 500   // Line 461
```

## IReceiver Interface

```kotlin
// KeyEventHandler.kt:639-641
interface IReceiver {
    // ... other methods ...
    fun wasLastSpaceAutoInserted(): Boolean = false
    fun setLastSpaceAutoInserted(value: Boolean) {}
}
```

### Bridge Implementation

```kotlin
// KeyEventReceiverBridge.kt:117-124
override fun wasLastSpaceAutoInserted(): Boolean {
    return contextTracker?.lastSpaceWasAutoInserted ?: false
}

override fun setLastSpaceAutoInserted(value: Boolean) {
    contextTracker?.lastSpaceWasAutoInserted = value
}
```

## Testing

### Test Cases

1. `testSmartPunct_SwipeThenPunct` - Swipe word → "." → attaches
2. `testSmartPunct_SwipeThenSpaceThenPunct` - Swipe → space → "." → keeps space
3. `testSmartPunct_TypeThenPunct` - Type word → space → "." → keeps space
4. `testSmartPunct_QuoteClosing` - Swipe → `"` (odd count) → attaches
5. `testSmartPunct_QuoteOpening` - Swipe → `"` (even count) → keeps space
6. `testSmartPunct_Apostrophe` - Type "don" → `'` → keeps (contraction)

### Integration Test Flow

```kotlin
// Type "Hello" then space then ":"
// Expected: "Hello :" (space preserved)

// Swipe "Hello" (auto-space) then ":"
// Expected: "Hello:" (space removed)

// Swipe "Hello" then manual space then ":"
// Expected: "Hello :" (space preserved)
```

## Related Specifications

- [User Dictionary Spec](user-dictionary-spec.md) - Case preservation
- [Autocorrect Spec](autocorrect-spec.md) - Spelling correction
- [Swipe Typing Spec](swipe-typing-spec.md) - Gesture input

## Version History

| Version | Change |
|---------|--------|
| v1.2.8 | Auto-space after sentence-ending punctuation for autocap integration |
| v1.2.7 | Added auto-inserted space tracking for manual space preservation |
| v1.2.0 | Added quote handling (opening vs closing detection) |
| v1.1.0 | Initial smart punctuation implementation |
