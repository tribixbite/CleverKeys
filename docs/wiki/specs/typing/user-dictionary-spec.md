---
title: User Dictionary - Technical Specification
user_guide: ../../typing/user-dictionary.md
status: implemented
version: v1.2.7
---

# User Dictionary Technical Specification

## Overview

The user dictionary system stores custom words with their original capitalization and applies this case to both tap and swipe predictions. This enables proper nouns, brand names, and technical terms to appear with correct capitalization.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| WordPredictor | `WordPredictor.kt:68-382` | Stores and applies user word case |
| InputCoordinator | `InputCoordinator.kt:343-349` | Applies case to swipe predictions |
| PredictionContextTracker | `PredictionContextTracker.kt:436-447` | Preserves raw prefix case |
| SuggestionHandler | `SuggestionHandler.kt:617-626` | Applies case to tap selections |

## Architecture

```
User adds "Boston" to dictionary
         ↓
WordPredictor.loadCustomAndUserWords()
         ↓
userWordOriginalCase["boston"] = "Boston"
         ↓
[Later, when predicting...]
         ↓
Neural/tap prediction: "boston"
         ↓
applyUserWordCase("boston") → "Boston"
         ↓
Display with correct capitalization
```

## Data Structures

### User Word Case Map

```kotlin
// WordPredictor.kt:68
private val userWordOriginalCase: MutableMap<String, String> = mutableMapOf()
```

Stores mapping from lowercase word → original capitalization:
- Key: Lowercase normalized word (e.g., "boston")
- Value: Original user-entered form (e.g., "Boston")

### Raw Prefix Tracking

```kotlin
// PredictionContextTracker.kt:70-71
private var rawPrefixForDeletion: String = ""
private var rawSuffixForDeletion: String = ""
```

Preserves user's typed capitalization for add-to-dictionary flow.

## Key Code Patterns

### Loading Custom Words with Case Preservation

```kotlin
// WordPredictor.kt:1253-1263 (sync) and 1122-1131 (async)
val originalWord = keys.next()  // Keep original case
val lowerWord = originalWord.lowercase()
// ... add to prediction index ...
if (originalWord != lowerWord) {
    userWordOriginalCase[lowerWord] = originalWord
}
```

### Loading Android User Dictionary with Case Preservation

```kotlin
// WordPredictor.kt:1309-1318 (sync) and 1177-1186 (async)
val originalWord = it.getString(wordIndex)
val lowerWord = originalWord.lowercase()
targetMap[lowerWord] = frequency
loadedWords.add(lowerWord)
// v1.2.7: Preserve original case for proper nouns (Issue #72)
if (originalWord != lowerWord) {
    userWordOriginalCase[lowerWord] = originalWord
}
```

### Applying Case to Predictions

```kotlin
// WordPredictor.kt:370-372
fun applyUserWordCase(word: String): String {
    val lowerWord = word.lowercase()
    return userWordOriginalCase[lowerWord] ?: word
}
```

### Batch Application for Lists

```kotlin
// WordPredictor.kt:381-382
fun applyUserWordCaseToList(words: List<String>): List<String> {
    return words.map { applyUserWordCase(it) }
}
```

### Swipe Prediction Path (v1.2.7+)

```kotlin
// InputCoordinator.kt:343-349
// Apply user word case preservation BEFORE shift transformation
val casedPredictions = predictionCoordinator.getWordPredictor()
    ?.applyUserWordCaseToList(predictions) ?: predictions

// Then apply shift/caps-lock transformation
val transformedPredictions = casedPredictions.map { applyShiftTransformation(it) }
```

### Tap Selection Path

```kotlin
// SuggestionHandler.kt:617-626
val currentWord = contextTracker.getCurrentWord()
val shouldCapitalize = currentWord.isNotEmpty() && currentWord[0].isUpperCase()
val capitalizedWord = if (shouldCapitalize && processedWord.isNotEmpty()) {
    processedWord.replaceFirstChar { ... }
} else {
    processedWord
}
```

## Capitalization Priority Order

When determining final word capitalization:

1. **User dictionary case** - Checked first via `applyUserWordCase()`
2. **Shift/CapsLock state** - Applied via `applyShiftTransformation()`
3. **I-words** - Applied via `capitalizeIWord()`
4. **Default** - Word as-is from neural network (lowercase)

## Configuration

### Storage Location

Custom words stored in SharedPreferences:
- Key: `"custom_words_{language}"`
- Format: JSON object `{"word": frequency, ...}`

### Dictionary Manager Access

- Settings > Activities > Dictionary Manager
- Tabs: Active, Disabled, User, Custom

## State Management

### On Keyboard Start

```kotlin
// WordPredictor initialization
loadCustomAndUserWords()  // Populates userWordOriginalCase
```

### On Dictionary Reload

```kotlin
// WordPredictor.kt:247
userWordOriginalCase.clear()  // Reset before reloading
```

### On Word Addition

```kotlin
// Word added via prompt or Dictionary Manager
// Saved to SharedPreferences with original case
// userWordOriginalCase updated on next load
```

## Testing

### Unit Test Cases

1. `testUserWordCasePreservation_ProperNoun` - "Boston" stays "Boston"
2. `testUserWordCasePreservation_BrandName` - "iPhone" stays "iPhone"
3. `testUserWordCasePreservation_Acronym` - "API" stays "API"
4. `testSwipePrediction_AppliesUserCase` - Swipe path applies case

### Integration Test

```kotlin
// Add "Boston" to custom words
// Swipe pattern for "boston"
// Verify output is "Boston"
```

## Related Specifications

- [Autocorrect Spec](autocorrect-spec.md) - Spelling corrections
- [Swipe Typing Spec](swipe-typing-spec.md) - Neural prediction
- [Neural Prediction Spec](../../../specs/neural-prediction.md) - ONNX model

## Version History

| Version | Change |
|---------|--------|
| v1.2.7 | Added swipe prediction case preservation |
| v1.2.5 | Initial user word case preservation for tap |
| v1.2.0 | Basic custom dictionary support |
