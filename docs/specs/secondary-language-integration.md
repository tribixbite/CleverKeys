# Secondary Language Integration

## Overview

Multi-language typing system that allows users to type in two languages simultaneously using a single QWERTY layout. Combines V2 binary dictionaries with accent normalization, unigram language detection, and language pack importing.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/AccentNormalizer.kt` | `AccentNormalizer` | Strips diacritical marks |
| `src/main/kotlin/tribixbite/cleverkeys/NormalizedPrefixIndex.kt` | `NormalizedPrefixIndex` | Accent-aware prefix lookup |
| `src/main/kotlin/tribixbite/cleverkeys/BinaryDictionaryLoader.kt` | V1/V2 loading | Dictionary loading |
| `src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabulary.kt` | Dual dictionaries | Primary + secondary management |
| `src/main/kotlin/tribixbite/cleverkeys/SuggestionRanker.kt` | Merging | Combines results with scoring |
| `src/main/kotlin/tribixbite/cleverkeys/UnigramLanguageDetector.kt` | Detection | Word-based language detection |
| `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt` | Pack import | Language pack storage |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Neural Swipe Predictor                     │
│  Outputs 26-letter words: "espanol", "cafe"                 │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    SuggestionRanker                          │
│  Merges candidates from primary + secondary dictionaries     │
└─────────────────────────────────────────────────────────────┘
        │                                    │
        ▼                                    ▼
┌───────────────────┐            ┌───────────────────┐
│ Primary Dict (EN) │            │ Secondary Dict(ES)│
│ NormalizedPrefix  │            │ NormalizedPrefix  │
│ Index             │            │ Index             │
└───────────────────┘            └───────────────────┘
        │                                    │
        └────────────────┬───────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                UnigramLanguageDetector                       │
│  Sliding window of 10 words, adjusts scoring multiplier     │
└─────────────────────────────────────────────────────────────┘
```

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| Enable Multi-Language | `pref_enable_multilang` | false | Master switch |
| Primary Language | `pref_primary_language` | en | Main language |
| Secondary Language | `pref_secondary_language` | none | Additional language |
| Auto-Detect Language | `pref_auto_detect_language` | true | Enable detection |
| Detection Sensitivity | `pref_language_detection_sensitivity` | 0.6 | Auto-switch threshold |

## Implementation Details

### V2 Binary Dictionary Format

```
Header (48 bytes):
  Magic:        4 bytes (0x54444B43 = "CKDT")
  Version:      4 bytes (2)
  Language:     4 bytes (e.g., "es\0\0")
  Word Count:   4 bytes
  Canonical Offset:   4 bytes
  Normalized Offset:  4 bytes
  Accent Map Offset:  4 bytes
  Reserved:     20 bytes

Canonical Section:
  For each word:
    Length:     2 bytes (uint16)
    Word:       UTF-8 bytes
    Rank:       1 byte (0-255, 0=most common)

Normalized Section:
  Count:        4 bytes (uint32)
  For each normalized form:
    Length:     2 bytes (uint16)
    Word:       UTF-8 bytes (accent-stripped)

Accent Map Section:
  For each normalized form:
    Count:      1 byte (number of canonical forms)
    Indices:    4 bytes each (uint32 index into canonical section)
```

### Dictionary Loading Flow

```kotlin
fun loadSecondaryDictionary(language: String): Boolean {
    // 1. Try loading from installed language packs first
    val packManager = LanguagePackManager.getInstance(context)
    val dictFile = packManager.getDictionaryPath(language)
    if (dictFile != null) {
        return BinaryDictionaryLoader.loadIntoNormalizedIndexFromFile(dictFile, index)
    }

    // 2. Fall back to bundled assets
    return BinaryDictionaryLoader.loadIntoNormalizedIndex(
        context, "dictionaries/${language}_enhanced.bin", index
    )
}
```

### Prediction Flow with Secondary Dictionary

```kotlin
fun getSuggestions(prefix: String, limit: Int): List<Candidate> {
    val candidates = mutableListOf<Candidate>()

    // Primary dictionary (with user words)
    candidates += primaryIndex.getByNormalizedPrefix(prefix)

    // Secondary dictionary (if loaded)
    secondaryIndex?.let { secondary ->
        val langMultiplier = vocabulary.currentLanguageMultiplier
        secondary.getByNormalizedPrefix(prefix).forEach { entry ->
            candidates += entry.copy(
                score = entry.confidence * langMultiplier
            )
        }
    }

    return candidates.sortedByDescending { it.score }.take(limit)
}
```

### Language Detection Algorithm

- Sliding window of 10 recent committed words
- 5000 top unigrams per language (EN, ES bundled)
- Dynamic multiplier adjusts secondary dictionary scoring:
  - Secondary > threshold: boost (1.1+)
  - Primary > threshold: penalty (0.85)
  - Balanced: neutral (1.0)

### Language Pack ZIP Format

```
langpack-fr.zip
├── manifest.json      # Metadata
├── dictionary.bin     # V2 binary dictionary
└── unigrams.txt       # Word frequency list (optional)
```

**manifest.json**:
```json
{
  "code": "fr",
  "name": "French",
  "version": 1,
  "author": "CleverKeys",
  "wordCount": 100000
}
```

### Import Flow

1. User downloads ZIP file externally
2. Settings → Multi-Language → Import Pack
3. File picker opens (Storage Access Framework)
4. LanguagePackManager validates and extracts to `files/langpacks/{code}/`
5. Language appears in Secondary Language dropdown

## Performance Considerations

| Aspect | Characteristic |
|--------|----------------|
| Memory | Secondary dictionary ~2-5MB in NormalizedPrefixIndex |
| Lookup | O(log n) prefix search per dictionary, merged linearly |
| Language Detection | O(1) per word (hash lookup in unigram set) |
| Pack Import | One-time extraction, ~1-2 seconds for 100k word pack |
