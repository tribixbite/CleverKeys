# Dictionary and Multi-Language System

## Overview

The dictionary system manages word lookup, frequency ranking, and multi-language support through a tiered architecture. It combines static dictionaries, user-defined words, and the swipe decoders' lexicons into a "Language Pack" system with automatic language detection for bilingual typing.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt` | `DictionaryManager` | Per-language dictionary loading/coordination (`OptimizedVocabulary`/`OptimizedVocabularyImpl` were deleted with the neural engine, 2026-08-18 — ADR-011; the swipe engines' lexicons are now `swipe/ctc/CtcLexiconTrie`+`CtcCkdtLexicon`+`CtcLexiconMerge` and `GeometricEngineAdapter`'s merge) |
| `src/main/kotlin/tribixbite/cleverkeys/LanguageDetector.kt` | `LanguageDetector` | Word-based language detection |
| `src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt` | `WordPredictor` | Unified prediction pipeline |
| `src/main/assets/dictionaries/{lang}_enhanced.bin` | Binary dictionaries | Trie-based word storage |

## Architecture

### Language Pack Structure

An imported ZIP contains `manifest.json`, V2 `dictionary.bin`, optional
`unigrams.txt`, `contractions.json`, `prefix_boost.bin`, `model.onnx` and `NOTICE.txt`.
Installed members live under `files/langpacks/{code}/`. Models are optional;
the shipped registry must also approve their hash before ONNX loading.

### Import and loader bounds (GH #184)

- Maximum canonical dictionary count: **100,000**, read from the binary header,
  never trusted from manifest metadata. Larger packs are refused with a localized
  message; no vocabulary is silently truncated. The bundled English dictionary has
  98,140 entries and published packs normally have 50,000.
- Maximum decompressed dictionary/other member: **16 MiB**. Model: **8 MiB**.
  Manifest and NOTICE: **64 KiB** each. Entire archive: **64 MiB**, at most **64 entries**.
- Extraction streams bounded chunks, rejects traversal and duplicate flattened basenames,
  and accepts benign wrapper folders. Validation happens before the staged install swap;
  preflight and staging-copy failures preserve the existing pack and clean scratch data.
  The final swap currently deletes the old directory before renaming staging.
  <!-- TODO: retain/recover the old directory when the final rename fails. -->
- Tap, geometric and CTC dictionary readers share byte/count limits, so previously
  installed oversized dictionaries also fail loading before count-sized allocation.
  These limits bound inputs; they do not guarantee arbitrary bilingual heap usage.
- TODO: verify refusal, retained old pack and continued typing on the connected Seeker;
  broader malformed-record validation remains separate from the count/byte guard.

### Dictionary Layers

```
┌─────────────────────────────────────────────────────────────┐
│        WordPredictor.predictInternal (inline merge)          │
│  All layers load into ONE dictionary map; candidates are     │
│  scored by calculateUnifiedScore (custom/user words are      │
│  calibrated onto the base scale — UserWordFrequency)         │
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
3. Score by frequency, source priority (Custom > User > Main), decoder confidence

## Implementation Details

### Accent Handling

The Latin CTC route projects supported accented spellings onto its a–z alphabet while
preserving canonical words for display. Script-specific CTC encoders use their own
token inventories, and geometric decoding follows supported layout nodes. A blanket
mark-stripping policy is unsuitable for Bangla: vowel signs and conjuncts need explicit
representation. See `docs/guides/adding-a-new-language.md` for the staged Bangla path.

Latin accent lookup:

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

CKDT uses a **48-byte little-endian header**: magic `CKDT` (uint32),
version 2 (uint32), four-byte language code, canonical word count (uint32),
then canonical/normalized/accent-map section offsets and reserved fields.
Canonical records contain a uint16 UTF-8 byte length, word bytes, and uint8
frequency rank. See `BinaryDictionaryLoader.kt` and `scripts/build_dictionary.py`
for normalized/accent-map records.

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
| `WordPredictor` (inline) | Merges primary + secondary candidates in `predictInternal` via `calculateUnifiedScore` × `secondary_prediction_weight` (the standalone `SuggestionRanker` had zero production callers post-ADR-011 and was deleted 2026-09-06 — audit C-6) |
| `LanguageDetector` | Word/character-pattern language detection (the unigram-frequency `UnigramLanguageDetector` was deleted 2026-08-28 — ARC-006, write-only after `OptimizedVocabulary` went) |
| `AccentNormalizer` | Unicode normalization (NFD) + accent stripping |

### Dictionary lifecycle and memory

PredictionCoordinator owns the serving WordPredictor. DictionaryManager does not
cache additional predictors. The predictor has a primary dictionary/prefix index
and an optional secondary NormalizedPrefixIndex. A vocabulary bound limits each
index, but each remains substantial; see the measured EN+IT stages in
[`2026-09-10-memory-oom-root-cause.md`](../audit/2026-09-10-memory-oom-root-cause.md).

AsyncDictionaryLoader uses one process-wide worker and two replaceable request
slots per owner, primary and secondary. Replacing or cancelling a request removes
its queued task and main-handler callbacks. Token checks also reject callbacks
already dequeued before cancellation. Secondary disable invalidates its slot
without cancelling primary loading. A synchronous primary reload invalidates the
pending asynchronous primary request before replacing its maps.

Primary dictionary, prefix index, casing, custom-word, shadowed-frequency and
contraction metadata build privately. The current request publishes them on main;
obsolete work cannot modify the serving language's metadata. Secondary indexes
also build privately and publish on main. Binary and overlay loops cooperate
with interruption, and expected cancellation does not trigger fallback loading.
Individual I/O/native calls are not guaranteed to stop immediately.

PredictionCoordinator.shutdown checkpoints learning, then calls the predictor's
terminal shutdown. This cancels both load slots, stops dictionary observation,
and replaces large dictionary references with empty state. It never shuts down
the shared executor or clears a worker's privately owned maps. Production lifecycle
and publication calls run on the main thread. Later load/observation requests on
the retired predictor are ignored.

Swipe dictionaries are separately owned by their adapters, with bounded,
content-versioned language memos. InputCoordinator detaches those adapters during
teardown. CtcEngineAdapter closes its ONNX sessions only after the worker has
terminated; its existing 250 ms timeout avoids closing a session beneath native
inference. See [`ctc-swipe-engine.md`](ctc-swipe-engine.md) for that contract.
