# Secondary Language Integration Specification

## Feature Overview
**Feature Name**: Secondary Language Integration (Multilanguage Mode)
**Priority**: P1 (High)
**Status**: ✅ COMPLETE (v1.1.84)
**Implementation Date**: 2026-01-04

### Summary
This feature allows users to type in multiple languages simultaneously using a single QWERTY layout. The system combines:
- **V2 Binary Dictionaries** with accent normalization (e.g., "espanol" → "español")
- **Unigram Language Detection** for automatic language context switching
- **Language Packs** for importing additional languages without internet

### Motivation
Many users type in two languages interchangeably. Rather than switching layouts, the prediction engine suggests words from *both* languages at once, using the primary language's neural model with a secondary dictionary for validation and accent mapping.

---

## Architecture Overview

### Phase 1: Accent Normalization (v1.1.80)
- **AccentNormalizer**: Maps 26-letter NN output to accented canonical forms
- **NormalizedPrefixIndex**: Fast prefix lookup with accent mapping
- **BinaryDictionaryLoader V2**: Loads dictionaries with accent data

### Phase 2: Multi-Dictionary Support (v1.1.81)
- **SuggestionRanker**: Merges results from primary + secondary dictionaries
- **OptimizedVocabulary**: Manages dual dictionary loading
- **Settings UI**: Secondary language picker in Multi-Language section

### Phase 3: Language Detection (v1.1.81)
- **UnigramLanguageDetector**: Word-based detection using frequency analysis
- Sliding window of 10 recent words
- 5000 top unigrams per language (EN, ES bundled)

### Phase 4: Auto-Switching (v1.1.82)
- Dynamic language multiplier adjusts secondary dictionary scoring
- Configurable detection sensitivity (0.4-0.9)
- Multiplier formula:
  - Secondary > threshold: boost (1.1+)
  - Primary > threshold: penalty (0.85)
  - Balanced: neutral (1.0)

### Phase 5: Language Packs (v1.1.84)
- **LanguagePackManager**: Import/validate/store ZIP packs
- **ZIP Format**: manifest.json + dictionary.bin + unigrams.txt
- No internet permission needed (Storage Access Framework)

---

## Technical Components

### 1. V2 Binary Dictionary Format

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

### 2. Key Classes

```
src/main/kotlin/tribixbite/cleverkeys/
├── AccentNormalizer.kt          # Strips diacritical marks
├── NormalizedPrefixIndex.kt     # Accent-aware prefix lookup
├── BinaryDictionaryLoader.kt    # V1/V2 dictionary loading
├── OptimizedVocabulary.kt       # Dual dictionary management
├── SuggestionRanker.kt          # Merges primary + secondary results
├── UnigramLanguageDetector.kt   # Word-based language detection
└── langpack/
    └── LanguagePackManager.kt   # Language pack import/storage
```

### 3. Dictionary Loading Flow

```kotlin
// OptimizedVocabulary.loadSecondaryDictionary()
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

### 4. Prediction Flow with Secondary Dictionary

```kotlin
// SuggestionRanker.getSuggestions()
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

---

## Language Pack System

### ZIP File Structure
```
langpack-fr.zip
├── manifest.json      # Metadata
├── dictionary.bin     # V2 binary dictionary
└── unigrams.txt       # Word frequency list (optional)
```

### manifest.json
```json
{
  "code": "fr",
  "name": "French",
  "version": 1,
  "author": "CleverKeys",
  "wordCount": 100000
}
```

### Build Scripts
```bash
# Generate word list from wordfreq
python3 scripts/get_wordlist.py --lang fr --output fr_words.txt --count 100000

# Build language pack ZIP
python3 scripts/build_langpack.py \
    --lang fr \
    --name "French" \
    --input fr_words.txt \
    --output langpack-fr.zip \
    --use-wordfreq
```

### Import Flow
1. User downloads ZIP file externally (browser, file transfer)
2. Settings → Multi-Language → Import Pack
3. File picker opens (Storage Access Framework)
4. LanguagePackManager validates and extracts to `files/langpacks/{code}/`
5. Language appears in Secondary Language dropdown

---

## Settings UI

**Location**: Settings → Multi-Language

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| Enable Multi-Language | pref_enable_multilang | false | Master switch |
| Primary Language | pref_primary_language | en | Main language |
| Secondary Language | pref_secondary_language | none | Additional language |
| Auto-Detect Language | pref_auto_detect_language | true | Enable detection |
| Detection Sensitivity | pref_language_detection_sensitivity | 0.6 | Auto-switch threshold |

**Language Packs Section**:
- "Import Pack" button → file picker
- "Manage" button → view/delete installed packs
- Shows installed pack count

---

## Performance Considerations

1. **Memory**: Secondary dictionary loads into separate NormalizedPrefixIndex (~2-5MB)
2. **Lookup**: O(log n) prefix search in each dictionary, merged linearly
3. **Language Detection**: O(1) per word (hash lookup in unigram set)
4. **Pack Import**: One-time extraction, ~1-2 seconds for 100k word pack

---

## Testing Checklist

- [x] Load bundled Spanish dictionary (es_enhanced.bin)
- [x] Accent normalization: "espanol" → "español"
- [x] Secondary dictionary appears in suggestions
- [x] Language multiplier adjusts based on context
- [ ] Import French language pack from ZIP
- [ ] Secondary language dropdown shows imported packs
- [ ] Delete language pack via Manage dialog

---

## Future Enhancements

1. **Language Pack Repository**: Optional online catalog (requires INTERNET permission)
2. **Auto-Download**: Detect device locale and offer relevant packs
3. **Multiple Secondary Languages**: Support 2+ secondary dictionaries
4. **Per-App Language**: Remember language preference per input field
