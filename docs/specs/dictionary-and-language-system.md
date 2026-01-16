# Dictionary and Multi-Language System

## Overview

The dictionary system manages word lookup, frequency ranking, and multi-language support through a tiered architecture. It combines static dictionaries, user-defined words, and neural prediction models into a "Language Pack" system with automatic language detection for bilingual typing.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabularyImpl.kt` | `OptimizedVocabulary` | Dictionary lookup and filtering |
| `src/main/kotlin/tribixbite/cleverkeys/data/LanguageDetector.kt` | `LanguageDetector` | Word-based language detection |
| `src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt` | `WordPredictor` | Unified prediction pipeline |
| `assets/dictionaries/{lang}_enhanced.bin` | Binary dictionaries | Trie-based word storage |

## Architecture

### Language Pack Structure

Each language pack is a self-contained unit:

```
Language Pack ({lang})
├── dictionaries/{lang}_enhanced.bin    # Trie-based vocabulary
├── dictionaries/{lang}_unigrams.bin    # Top 1000 words for detection
├── models/swipe_encoder_{lang}.onnx    # Neural encoder
├── models/swipe_decoder_{lang}.onnx    # Neural decoder
├── layouts/{lang}_*.xml                # Keyboard layouts
└── metadata.json                       # Version, license info
```

### Dictionary Layers

```
┌─────────────────────────────────────────────────────────────┐
│                     SuggestionRanker                         │
│  Merges candidates from all layers with unified scoring      │
└─────────────────────────────────────────────────────────────┘
                            │
       ┌────────────────────┼────────────────────┐
       │                    │                    │
       ▼                    ▼                    ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Layer 1    │    │   Layer 2    │    │   Layer 3    │
│ Main Dict    │    │ User Dict    │    │ Custom Dict  │
│ (Read-Only)  │    │ (System)     │    │ (App-Local)  │
│              │    │              │    │              │
│ Language     │    │ Android      │    │ SharedPrefs  │
│ Pack Asset   │    │ UserDictionary│   │ user_dict    │
└──────────────┘    └──────────────┘    └──────────────┘
       │                    │                    │
       └────────────────────┼────────────────────┘
                            │
                            ▼
                   ┌──────────────┐
                   │   Layer 4    │
                   │ Disabled     │
                   │ Words Filter │
                   └──────────────┘
```

**Resolution Logic:**
1. Gather candidates from Layers 1, 2, 3
2. Filter out words in Layer 4 (disabled)
3. Score by frequency, source priority (Custom > User > Main), neural confidence

## Implementation Details

### Accent Handling

The neural model uses a 26-letter vocabulary (a-z only). Accented words are handled through normalization:

```kotlin
// Accent mapping: normalized → canonical forms
data class AccentMapping(
    val normalized: String,         // "cafe"
    val canonicalForms: List<String>, // ["café"]
    val frequencies: List<Int>
)

// Lookup flow:
// 1. User swipes "café" → trajectory matches "cafe" pattern
// 2. Prefix lookup: "caf" → finds normalized candidates
// 3. Each candidate maps to its accented canonical form
```

### Binary Dictionary Format (v2)

```
┌────────────────────────────────────────┐
│ HEADER (32 bytes)                      │
│  - Magic: "CKDICT" (6 bytes)           │
│  - Version: 2 (2 bytes)                │
│  - Language: "es" (4 bytes)            │
│  - Word Count (4 bytes)                │
│  - Trie Offset (4 bytes)               │
│  - Metadata Offset (4 bytes)           │
│  - Accent Map Offset (4 bytes)         │
├────────────────────────────────────────┤
│ TRIE DATA BLOCK                        │
│  - Compact trie of NORMALIZED words    │
│  - Terminal nodes store word_id        │
├────────────────────────────────────────┤
│ WORD METADATA BLOCK                    │
│  - Array indexed by word_id:           │
│    - Canonical string (UTF-8, varint)  │
│    - Frequency rank (UInt8, 0-255)     │
├────────────────────────────────────────┤
│ ACCENT MAP BLOCK (optional)            │
│  - normalized_word → [canonical_ids]   │
└────────────────────────────────────────┘
```

**Frequency Ranking:**
- Rank 0 = most frequent word
- Rank 255 = least frequent
- Log-scaled quantization preserves relative ordering

### Language Detection

Word-based unigram frequency model:

```kotlin
data class LanguageScore(
    val language: String,
    var score: Float = 0f,
    var consecutiveHits: Int = 0
)

// Detection algorithm:
// 1. Each language pack ships top 1000 unigrams
// 2. Maintain sliding window of last 5 committed words
// 3. Score each word against active language unigram lists
// 4. Track running score per language (exponentially decaying)

fun shouldSwitch(primary: LanguageScore, candidate: LanguageScore): Boolean {
    // Conservative threshold to prevent jitter
    return candidate.score > primary.score * 2.0f &&
           candidate.consecutiveHits >= 2
}
```

### Dual-Dictionary Mode

For bilingual typing (e.g., English + Spanish) without manual switching:

```kotlin
fun calculateUnifiedScore(
    word: String,
    nnConfidence: Float,      // From ONNX model
    dictionaryRank: Int,      // 0-255, lower = more common
    languageContext: Float,   // 0.0-1.0, from detector
    isPrimaryLang: Boolean
): Float {
    val rankScore = 1.0f - (dictionaryRank / 255f)
    val langMultiplier = if (isPrimaryLang) 1.0f else languageContext
    val secondaryPenalty = if (isPrimaryLang) 1.0f else 0.9f

    return nnConfidence * rankScore * langMultiplier * secondaryPenalty
}
```

**Deduplication:** When word exists in both dictionaries, present only entry with higher final score.

### Manual Language Switching

```kotlin
// Triggered by Globe key or long-press Spacebar
fun switchLanguage(newLang: String) {
    // 1. Hot-swap MainDictionarySource
    dictionaryManager.loadMainDictionary(newLang)

    // 2. Load corresponding ONNX models
    multiLanguageManager.loadModels(newLang)

    // 3. Update keyboard layout if linked
    keyboardManager.switchLayout(newLang)
}
```

### Data Structures

```kotlin
data class DictionaryWord(
    val canonical: String,      // Display form with accents
    val normalized: String,     // Lookup key without accents
    val frequencyRank: Int,     // 0-255, lower = more common
    val source: WordSource,     // MAIN, USER, CUSTOM, SECONDARY
    var enabled: Boolean = true
)

enum class WordSource {
    MAIN,       // Primary language pack
    SECONDARY,  // Secondary language pack
    USER,       // Android UserDictionary
    CUSTOM      // App SharedPreferences
}

data class LanguageState(
    val primary: String,
    val secondary: String?,
    val detectedContext: String,
    val confidence: Float
)
```

### Key Classes

| Class | Purpose |
|-------|---------|
| `DictionaryManager` | Singleton holding active WordPredictor instances, handles language lifecycle |
| `MultiLanguageManager` | Manages ONNX sessions, handles auto-detection logic |
| `SuggestionRanker` | Merges candidates from multiple dictionaries with unified scoring |
| `UnigramLanguageDetector` | Word-based language detection using frequency lists |
| `AccentNormalizer` | Unicode normalization (NFD) + accent stripping |

### Performance Considerations

| Aspect | Strategy |
|--------|----------|
| Trie lookups | O(L) where L = key length, <5ms |
| Memory mapping | `MappedByteBuffer` for large dictionaries |
| Async loading | Language switching on `Dispatchers.IO` |
| Lazy init | Secondary dictionary loaded only when enabled |
| Memory cleanup | Inactive ONNX sessions unloaded after 60s |
| Unigram cache | ~100KB per language kept in memory |
