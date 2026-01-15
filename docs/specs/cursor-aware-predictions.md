# Cursor-Aware Predictions System Specification

**Status:** Implemented
**Version:** 1.1
**Author:** Claude Opus 4.5
**Date:** 2026-01-15

## Changelog

### v1.1 (2026-01-15)
- Fixed race condition: async prediction task could overwrite special prompts (autocorrect undo, add-to-dictionary)
- Added `specialPromptActive` flag to prevent prediction task from overwriting important UI states
- Fixed immediate cursor sync in `onSuggestionSelected` to handle debounce timing issue

---

## Problem Statement

When user moves cursor into middle of existing text (by tapping, cut/paste, arrow keys), predictions don't work because `PredictionContextTracker.currentWord` becomes stale. Additionally, selecting a prediction mid-word leaves word fragments behind.

## Root Cause

```
onUpdateSelection() --> only notifies Autocapitalisation
PredictionContextTracker --> no cursor sync, currentWord stays empty
onSuggestionSelected() --> deleteSurroundingText(n, 0) --> only deletes BEFORE cursor
```

---

## Solution Architecture

### Core Changes

1. Add `synchronizeWithCursor(ic: InputConnection)` to PredictionContextTracker
2. Track both **prefix** (before cursor) and **suffix** (after cursor) of current word
3. On cursor movement, read actual text from InputConnection and rebuild state
4. When selecting prediction mid-word, delete BOTH prefix AND suffix
5. Use `expectingSelectionUpdate` flag to skip programmatic cursor changes

---

## Multi-Language Support

### Space-Delimited Languages (Latin-based)

Standard word boundary detection using whitespace and punctuation.

```kotlin
private val WORD_BOUNDARIES = setOf(
    ' ', '\t', '\n', '\r',       // Whitespace
    '.', ',', ';', ':', '!', '?' // Sentence punctuation
)
```

### Languages WITHOUT Spaces (CJK)

Chinese, Japanese, Thai do NOT use spaces between words. The neural model outputs 26-letter English only.

**Decision:** Skip cursor sync for CJK scripts entirely.

```kotlin
fun isCJKScript(char: Char): Boolean {
    return Character.UnicodeScript.of(char.code) in setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI
    )
}
```

### RTL Languages (Arabic, Hebrew)

`InputConnection.getTextBeforeCursor()` and `getTextAfterCursor()` are LOGICAL, not visual. No special handling needed.

### German Compound Words

Treated as single word (no internal spaces). Example: "Donaudampfschifffahrtsgesellschaft"

---

## Contraction Handling

### Design Decision

Apostrophe WITHIN a word is NOT a word boundary:

| Word | Treatment |
|------|-----------|
| don't | Single word |
| it's | Single word |
| l'homme (French) | Single unit |
| dell'anno (Italian) | Single unit |

```kotlin
fun isApostrophePartOfWord(before: Char?, after: Char?): Boolean {
    return before?.isLetter() == true && after?.isLetter() == true
}
```

### French Elision Patterns

```kotlin
private val FRENCH_ELISION_PATTERNS = setOf(
    "l'", "d'", "qu'", "j'", "n'", "s'", "m'", "t'", "c'"
)
```

---

## Accent/Diacritic Handling

### Strategy

- **Normalized** matching for prediction lookup (cafe matches cafe)
- **Raw** character count for deletion (delete exactly 4 chars of "cafe")

```kotlin
val (normalizedPrefix, rawPrefix) = extractWordPrefix(beforeText, language)

// Use normalized for prediction
predictions = wordPredictor.predict(normalizedPrefix)

// Use raw for deletion
ic.deleteSurroundingText(rawPrefix.length, rawSuffix.length)
```

---

## Edge Cases

| Case | prefix | suffix | Behavior |
|------|--------|--------|----------|
| Cursor at end: `hello\|` | "hello" | "" | Normal predictions |
| Cursor mid-word: `hel\|lo` | "hel" | "lo" | Predictions for "hel", delete both on select |
| Cursor at start: `\|hello` | "" | "hello" | Clear predictions |
| Cursor after space: `hello \|` | "" | "" | Context-based next-word predictions |
| After emoji: `hi [wave] \|` | "" | "" | Reset prediction |
| Numbers: `test\|123` | "test" | "" | Numbers break word |
| URL field | - | - | Skip sync (input type) |
| Password field | - | - | Skip sync (input type) |

---

## Performance

### Debouncing

onUpdateSelection fires for every character during drag selection. Debounce sync with 100ms delay.

```kotlin
private const val SYNC_DEBOUNCE_MS = 100L

fun onCursorMoved(newPosition: Int, ic: InputConnection?) {
    pendingSyncRunnable?.let { syncHandler.removeCallbacks(it) }
    pendingSyncRunnable = Runnable { synchronizeWithCursor(ic) }
    syncHandler.postDelayed(pendingSyncRunnable!!, SYNC_DEBOUNCE_MS)
}
```

### IPC Optimization

- Single call for each direction (not iterative)
- Request 50 chars max (sufficient for most words)
- Cache last synced state to detect if sync needed

---

## Modified PredictionContextTracker

```kotlin
class PredictionContextTracker {
    // NEW fields
    private val currentWordSuffix = StringBuilder()
    private var rawPrefixForDeletion: String = ""
    private var rawSuffixForDeletion: String = ""

    @Volatile
    var expectingSelectionUpdate = false

    fun synchronizeWithCursor(
        ic: InputConnection?,
        language: String = "en",
        editorInfo: EditorInfo? = null
    ) {
        ic ?: return
        if (!shouldSyncForInputType(editorInfo)) return
        if (isCJKLanguage(language)) return

        val beforeText = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
        val afterText = ic.getTextAfterCursor(50, 0)?.toString() ?: ""

        val (prefix, rawPrefix) = extractWordPrefix(beforeText, language)
        val (suffix, rawSuffix) = extractWordSuffix(afterText, language)

        currentWord.clear()
        currentWord.append(prefix)
        currentWordSuffix.clear()
        currentWordSuffix.append(suffix)
        rawPrefixForDeletion = rawPrefix
        rawSuffixForDeletion = rawSuffix

        clearAutocorrectTracking()
    }

    fun getCharsToDeleteForPrediction(): Pair<Int, Int> {
        return Pair(rawPrefixForDeletion.length, rawSuffixForDeletion.length)
    }

    private fun isWordChar(char: Char, language: String, text: String, pos: Int): Boolean {
        if (char.isLetter()) return true
        if (char == '\'') {
            val before = text.getOrNull(pos - 1)
            val after = text.getOrNull(pos + 1)
            return before?.isLetter() == true && after?.isLetter() == true
        }
        return false
    }
}
```

---

## Modified InputCoordinator.onSuggestionSelected

```kotlin
fun onSuggestionSelected(word: String?, ic: InputConnection?, ...) {
    ic?.let { connection ->
        val (prefixDelete, suffixDelete) = contextTracker.getCharsToDeleteForPrediction()

        if (prefixDelete > 0 || suffixDelete > 0) {
            contextTracker.expectingSelectionUpdate = true
            connection.deleteSurroundingText(prefixDelete, suffixDelete)
        }

        // ... existing insertion logic ...
    }
}
```

---

## Modified CleverKeysService.onUpdateSelection

```kotlin
override fun onUpdateSelection(
    oldSelStart: Int, oldSelEnd: Int,
    newSelStart: Int, newSelEnd: Int,
    candidatesStart: Int, candidatesEnd: Int
) {
    super.onUpdateSelection(...)
    _keyeventhandler.selection_updated(oldSelStart, newSelStart)

    if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd)) {
        _keyboardView.set_selection_state(newSelStart != newSelEnd)
    }

    // NEW: Trigger cursor sync
    if (newSelStart == newSelEnd && oldSelStart != newSelStart) {
        _inputCoordinator.onCursorMoved(
            newPosition = newSelStart,
            ic = currentInputConnection,
            language = _config?.primary_language ?: "en",
            editorInfo = currentInputEditorInfo
        )
    }
}
```

---

## Files to Modify

| File | Changes |
|------|---------|
| PredictionContextTracker.kt | Add suffix, sync method, raw text preservation |
| InputCoordinator.kt | Modify onSuggestionSelected for dual-side deletion |
| CleverKeysService.kt | Extend onUpdateSelection to trigger sync |

---

## Testing Matrix

| Category | Test Case | Expected Result |
|----------|-----------|-----------------|
| Basic | Cursor mid-word "hel\|lo" | prefix="hel", suffix="lo" |
| Contraction | Mid "don'\|t" | prefix="don'", suffix="t", full="don't" |
| Accents | "caf\|e" (cafe) | Normalized lookup, raw deletion |
| French | "l'ho\|mme" | Single unit treatment |
| Emoji | After "hi [wave] \|" | Reset prediction |
| Numbers | "test\|123" | prefix="test", suffix="" |
| URL field | Any | Skip sync |
| Rapid drag | 100 positions | Debounced to 1 sync |
| CJK text | Any | Skip sync |

---

## Implementation Order

1. Add suffix field and getters to PredictionContextTracker
2. Implement synchronizeWithCursor() method
3. Add isWordChar() with apostrophe handling
4. Add expectingSelectionUpdate flag
5. Modify onUpdateSelection to call sync
6. Modify onSuggestionSelected to delete both sides
7. Add debouncing
8. Test all edge cases

---

## Rollback Safety

- Set `expectingSelectionUpdate = true` always to disable sync
- Make `getSuffixLength()` return 0 to preserve old behavior
- Original end-of-word typing unchanged
