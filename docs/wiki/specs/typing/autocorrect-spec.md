---
title: Autocorrect - Technical Specification
user_guide: ../../typing/autocorrect.md
status: implemented
version: v1.2.7
---

# Autocorrect Technical Specification

## Overview

Autocorrect system that checks typed words against dictionary and suggests corrections based on edit distance similarity.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| WordPredictor | `WordPredictor.kt` | Dictionary lookup and suggestions |
| SuggestionHandler | `SuggestionHandler.kt:300-450` | Autocorrect logic |
| DictionaryManager | `DictionaryManager.kt` | User dictionary management |
| PredictionContextTracker | `PredictionContextTracker.kt` | Track autocorrect state for undo |

## Autocorrect Flow

```
User types word + space
        ↓
SuggestionHandler.onWordCompleted()
        ↓
WordPredictor.isInDictionary(word)?
    ├── Yes → No correction
    └── No → findSimilarWords(word)
                ↓
        Match found with similarity >= threshold?
            ├── Yes → Apply correction, store original for undo
            └── No → Show "Add to dictionary?" prompt
```

## Similarity Algorithm

Edit distance (Levenshtein) with threshold:

```kotlin
// WordPredictor.kt:~350
fun findSimilarWord(input: String): String? {
    val threshold = config.autocorrect_threshold // default: 0.66

    for ((word, freq) in dictionary) {
        val similarity = 1.0 - (editDistance(input, word).toDouble() /
                                max(input.length, word.length))
        if (similarity >= threshold) {
            return word
        }
    }
    return null
}
```

## Undo Mechanism

State tracked in PredictionContextTracker:

```kotlin
// PredictionContextTracker.kt
var lastAutocorrectOriginalWord: String? = null
var lastAutocorrectReplacementWord: String? = null
var lastAutocorrectPosition: Int = -1

fun trackAutocorrect(original: String, replacement: String, position: Int) {
    lastAutocorrectOriginalWord = original
    lastAutocorrectReplacementWord = replacement
    lastAutocorrectPosition = position
}

fun clearAutocorrectTracking() {
    lastAutocorrectOriginalWord = null
    lastAutocorrectReplacementWord = null
    lastAutocorrectPosition = -1
}
```

## Undo Flow

```kotlin
// SuggestionHandler.kt:~400
fun handleAutocorrectUndo(ic: InputConnection, originalWord: String) {
    val replacement = contextTracker.lastAutocorrectReplacementWord ?: return

    // Delete the autocorrected word + trailing space
    ic.deleteSurroundingText(replacement.length + 1, 0)

    // Insert original word + space
    ic.commitText("$originalWord ", 1)

    // Add to user dictionary to prevent future corrections
    dictionaryManager.addCustomWord(originalWord, config.primary_language)

    // Clear tracking
    contextTracker.clearAutocorrectTracking()

    // Show confirmation
    showTemporaryMessage("Added '$originalWord' to dictionary")
}
```

## Add to Dictionary Prompt

When unknown word is not autocorrected:

```kotlin
// SuggestionHandler.kt:~450
fun checkUnknownWord(word: String) {
    if (!wordPredictor.isInDictionary(word) &&
        !wasAutocorrected() &&
        word.length >= 2) {
        // Show prompt in suggestion bar
        // Format: "dict_add:{word}" prefix
        showDictionaryPrompt(word)
    }
}
```

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Autocorrect Enabled** | `autocorrect_enabled` | true | boolean |
| **Autocorrect Threshold** | `autocorrect_threshold` | 0.66 | 0.5-0.9 |
| **Min Word Length** | `autocorrect_min_length` | 2 | 1-5 |

## Manual Selection Skip

When user explicitly taps a prediction, skip autocorrect:

```kotlin
// SuggestionHandler.kt:~250
fun onSuggestionSelected(
    suggestion: String,
    isManualSelection: Boolean  // v1.2.7: Added parameter
) {
    if (isManualSelection) {
        // User explicitly chose this - don't second-guess
        commitSuggestion(suggestion, skipAutocorrect = true)
    } else {
        // Auto-selected (space pressed) - apply autocorrect
        commitSuggestion(suggestion, skipAutocorrect = false)
    }
}
```

## Related Specifications

- [Swipe Typing Specification](swipe-typing-spec.md) - Neural predictions
- [Dictionary System](../../../specs/dictionary-and-language-system.md) - Word storage
