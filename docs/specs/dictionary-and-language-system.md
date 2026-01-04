# Dictionary and Multi-Language System Specification

## Feature Overview
**Feature Name**: Dictionary and Multi-Language System
**Priority**: P1 (High)
**Status**: Architecture Finalized (2026-01-04)
**Target Version**: v1.2.0

### Summary
This specification defines the architecture for managing dictionaries, handling multiple languages, and supporting dynamic language switching in CleverKeys. It unifies static dictionaries, user-defined words, and neural prediction models into a cohesive "Language Pack" system.

### Motivation
To support a global user base, CleverKeys must seamlessly handle multiple languages. The current system relies on assets baked into the APK. A more flexible system is needed to allow users to install, manage, and switch between languages without requiring app updates.

---

## 1. System Architecture

### 1.1 The Language Pack Concept
A "Language Pack" is a self-contained unit that provides support for a specific language locale (e.g., `en`, `fr`, `es-rMX`).

**Components of a Language Pack:**
1.  **Main Dictionary (`.bin`)**: Trie-based vocabulary with frequencies and accent mappings.
    *   *Path*: `dictionaries/{lang}_enhanced.bin`
2.  **Unigram Frequency List**: Top 1000 words for language detection.
    *   *Path*: `dictionaries/{lang}_unigrams.bin`
3.  **Neural Models (`.onnx`)**: Models for swipe prediction (Encoder + Decoder).
    *   *Path*: `models/swipe_encoder_{lang}.onnx` & `models/swipe_decoder_{lang}.onnx`
    *   *Note*: Initial release reuses English model for all Latin-script languages
4.  **Keyboard Layouts (`.xml`)**: Standard layouts for the language (e.g., QWERTY, AZERTY).
    *   *Path*: `layouts/{lang}_*.xml`
5.  **Metadata (`.json`)**: Pack version, name, author, license attribution.

### 1.2 Dictionary Layers
The prediction engine utilizes a tiered dictionary system to resolve word candidates.

*   **Layer 1: Main Dictionary (Read-Only)**
    *   Source: Language Pack asset.
    *   Content: tens of thousands of common words with frequency data.
    *   Management: Loaded via `MainDictionarySource`.

*   **Layer 2: User Dictionary (Read-Write)**
    *   Source: Android System `UserDictionary` content provider.
    *   Content: Words learned by other apps or added globally by the user.
    *   Management: Loaded via `UserDictionarySource`.

*   **Layer 3: Custom Dictionary (Read-Write)**
    *   Source: App-internal `SharedPreferences` (`user_dictionary`).
    *   Content: Words explicitly added by the user within CleverKeys or learned from typing.
    *   Management: Loaded via `CustomDictionarySource`.

*   **Layer 4: Disabled Words (Read-Write)**
    *   Source: App-internal `SharedPreferences`.
    *   Content: Words from the Main Dictionary that the user has explicitly blocked (e.g., offensive words or annoying auto-corrects).
    *   Management: Loaded via `DisabledDictionarySource`.

**Resolution Logic:**
1.  Candidates are gathered from Layers 1, 2, and 3.
2.  Any candidate present in Layer 4 is filtered out.
3.  Candidates are scored based on frequency, source priority (Custom > User > Main), and neural probability.

---

## 2. Accent Handling Architecture

### 2.1 The Core Constraint
The neural swipe model has a 26-letter vocabulary (a-z only). It cannot distinguish between `café` and `cafe` - both produce the identical swipe trajectory.

### 2.2 Normalization Strategy
**One-way normalization at dictionary build time:**

1.  For each canonical word (e.g., `café`), generate normalized form (`cafe`)
2.  Dictionary stores canonical form as the display word
3.  Lookup/matching uses normalized form as key
4.  NN output (`cafe`) maps to canonical display (`café`)

### 2.3 Data Structures

```kotlin
// Accent mapping: normalized → list of canonical forms
// Example: "cafe" → ["café"], "schon" → ["schon", "schön"]
data class AccentMapping(
    val normalized: String,
    val canonicalForms: List<String>,
    val frequencies: List<Int>  // Parallel to canonicalForms
)
```

### 2.4 Prefix Index Strategy
The prefix index is built on **normalized** words:
- User swipes "café" → trajectory matches "cafe" pattern
- Prefix lookup: `caf` → finds normalized candidates
- Each candidate maps to its accented canonical form

### 2.5 Touch Typing vs Swipe Typing
| Mode | Input | Lookup Key | Display |
|------|-------|------------|---------|
| Swipe | trajectory | normalized | canonical (accented) |
| Touch | typed chars | as-typed or normalized | canonical if match found |

---

## 3. Binary Dictionary Format (v2)

### 3.1 Format Overview
Move from HashMap to **Trie-based** format for:
- O(L) prefix lookups (L = key length)
- Memory efficiency for shared prefixes
- Natural prefix search without separate index

### 3.2 Binary Structure

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
│  - Reserved (4 bytes)                  │
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
│  - Only for words with accent variants │
└────────────────────────────────────────┘
```

### 3.3 Frequency Ranking
Store **rank** (0-255) instead of raw frequency:
- Rank 0 = most frequent word
- Rank 255 = least frequent
- Log-scaled quantization preserves relative ordering
- Saves 3 bytes per word vs 32-bit frequency

### 3.4 Build Pipeline

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ AOSP Word List  │────▶│ Frequency       │────▶│ Binary Dict     │
│ (CC BY 4.0)     │     │ Enrichment      │     │ Generator       │
└─────────────────┘     │ (wordfreq)      │     └─────────────────┘
                        └─────────────────┘              │
                                                         ▼
                                                 {lang}_enhanced.bin
```

---

## 4. Multi-Language Switching

### 4.1 Manual Switching
*   **Trigger**: User taps the "Globe" key or long-presses Spacebar.
*   **Action**: Cycles through the list of *Active Languages* enabled in Settings.
*   **Outcome**:
    *   The `MainDictionarySource` is hot-swapped to the new language.
    *   The `MultiLanguageManager` loads the corresponding ONNX models.
    *   The keyboard layout is updated (if a specific layout is linked to the language).

### 4.2 Auto-Switching (Polyglot Mode)

#### 4.2.1 Detection Algorithm
**Word-based unigram frequency model** (not character patterns):

1.  Each language pack ships top 1000 unigrams
2.  Maintain sliding window of last 5 committed words
3.  Score each word against active language unigram lists
4.  Track running score per language (exponentially decaying average)

#### 4.2.2 Switching Logic
Conservative threshold to prevent jitter:
- Switch only when dominant language score > 2x other language
- Require 2-3 consecutive words favoring new language
- Brief UI feedback on spacebar: `EN → ES`

```kotlin
data class LanguageScore(
    val language: String,
    var score: Float = 0f,
    var consecutiveHits: Int = 0
)

fun shouldSwitch(primary: LanguageScore, candidate: LanguageScore): Boolean {
    return candidate.score > primary.score * 2.0f &&
           candidate.consecutiveHits >= 2
}
```

---

## 5. Dual-Dictionary Mode (Secondary Language)

### 5.1 Use Case
Bilingual typing (e.g., English + Spanish) on single QWERTY layout without manual switching.

### 5.2 Architecture

```
┌─────────────────────────────────────────────────────┐
│                  SuggestionRanker                   │
│  ┌─────────────┐    ┌─────────────┐                │
│  │ Primary     │    │ Secondary   │                │
│  │ Dictionary  │    │ Dictionary  │                │
│  │ (English)   │    │ (Spanish)   │                │
│  └──────┬──────┘    └──────┬──────┘                │
│         │                  │                        │
│         ▼                  ▼                        │
│  ┌────────────────────────────────────────────┐    │
│  │         Unified Scoring Pipeline           │    │
│  │  score = nn_conf × dict_rank × lang_ctx    │    │
│  └────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────┘
```

### 5.3 Scoring Formula

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
    val secondaryPenalty = if (isPrimaryLang) 1.0f else 0.9f  // Configurable

    return nnConfidence * rankScore * langMultiplier * secondaryPenalty
}
```

### 5.4 Deduplication
When word exists in both dictionaries (e.g., "son"):
- Present only the entry with higher final score
- Language context score naturally favors the contextually appropriate language

---

## 6. Dictionary Sources and Licensing

### 6.1 Source Strategy

| Source | Use | License | Notes |
|--------|-----|---------|-------|
| AOSP Dictionaries | Word lists | CC BY 4.0 | 200+ languages |
| wordfreq | Frequency data | Apache 2.0 (code), CC BY-SA 4.0 (data) | Snapshot through 2021 |
| FrequencyWords | Alt frequency | MIT (code), CC BY-SA 4.0 (data) | From OpenSubtitles |

### 6.2 Licensing Compliance

**CC BY-SA 4.0 for dictionary assets is acceptable:**
- CleverKeys code remains Apache 2.0
- Dictionary `.bin` files are separate assets under CC BY-SA 4.0
- Proper attribution in `LICENSES.md` and pack metadata
- Attribution visible in Settings → About → Licenses

### 6.3 Build Pipeline

```python
# scripts/build_dictionary.py

def build_language_pack(lang: str):
    # 1. Load AOSP word list
    words = load_aosp_wordlist(f"aosp/{lang}.txt")

    # 2. Enrich with frequency data
    for word in words:
        freq = wordfreq.word_frequency(word, lang)
        word.frequency = freq or DEFAULT_LOW_FREQ

    # 3. Normalize for accent mapping
    normalized = {}
    for word in words:
        norm = normalize_accents(word.text)
        normalized.setdefault(norm, []).append(word)

    # 4. Build trie on normalized keys
    trie = build_compact_trie(normalized.keys())

    # 5. Generate binary format
    write_binary_dict(lang, trie, words, normalized)
```

---

## 7. Implementation Plan

### Phase 1: Foundation (v1.2.0)
- [ ] Implement accent normalization in WordPredictor
- [ ] Create `NormalizedPrefixIndex` that maps normalized → canonical
- [ ] Update `BinaryDictionaryLoader` for v2 format
- [ ] Build script: `scripts/build_dictionary.py`

### Phase 2: Multi-Dictionary (v1.2.1)
- [ ] Implement `SuggestionRanker` for unified scoring
- [ ] Add secondary dictionary slot in `DictionaryManager`
- [ ] UI: Settings → Languages → Secondary Language import

### Phase 3: Language Detection (v1.2.2)
- [ ] Implement word-based `UnigramLanguageDetector`
- [ ] Ship unigram lists for bundled languages
- [ ] Auto-switching with configurable sensitivity

### Phase 4: Language Packs (v1.3.0)
- [ ] Language Pack ZIP format spec
- [ ] Download manager for on-demand languages
- [ ] UI: Language Store in Settings

---

## 8. Import and Management Workflows

### 8.1 Installing New Languages
*   **Mechanism**: "Language Store" in Settings.
*   **Source**:
    *   *Bundled*: Common languages included in APK assets.
    *   *Downloadable*: Hosted on GitHub Releases or a dedicated CDN.
*   **Process**:
    1.  User selects language.
    2.  App downloads ZIP bundle.
    3.  Files are extracted to app-private storage (`files/languages/{lang}/`).
    4.  `DictionaryManager` registers the new language availability.

### 8.2 Dictionary Import/Export
*   **Scope**: Custom Dictionary (Layer 3) and Disabled Words (Layer 4).
*   **Format**: JSON.
*   **Import Logic**:
    *   Read JSON.
    *   For each word: Check against existing Custom Dictionary.
    *   If new, add to `SharedPreferences`.
    *   *Crucial*: Do NOT write to Android System `UserDictionary` during bulk import to avoid pollution and permission issues.

### 8.3 Custom Word Management
*   **UI**: `DictionaryManagerActivity` (Jetpack Compose).
*   **Tabs**:
    *   *Custom*: View/Edit/Delete words in Layer 3.
    *   *User*: View words in Layer 2 (Read-only or System Intent to edit).
    *   *Disabled*: View/Re-enable words in Layer 4.
    *   *Secondary*: View/manage secondary language dictionary.
*   **Interaction**: Swipe-to-delete, Undo support, Search filter.

---

## 9. Data Structures

### 9.1 Core Classes

```kotlin
// Representation of a word in the aggregation pipeline
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

// Language detection state
data class LanguageState(
    val primary: String,
    val secondary: String?,
    val detectedContext: String,
    val confidence: Float
)
```

### 9.2 Key Classes
*   **`DictionaryManager`**: Singleton. Holds references to active `WordPredictor` instances. Handles language lifecycle.
*   **`MultiLanguageManager`**: Manages ONNX sessions. Handles auto-detection logic.
*   **`SuggestionRanker`**: Merges candidates from multiple dictionaries with unified scoring.
*   **`UnigramLanguageDetector`**: Word-based language detection using frequency lists.
*   **`AccentNormalizer`**: Unicode normalization (NFD) + accent stripping.
*   **`BackupRestoreManager`**: Handles JSON serialization for Import/Export.

---

## 10. Performance Considerations

*   **Binary Dictionaries**: Trie-based `.bin` format for O(L) lookups (<5ms for any word).
*   **Memory-Mapped I/O**: Use `MappedByteBuffer` for large dictionaries to avoid heap pressure.
*   **Async Loading**: Language switching happens on `Dispatchers.IO`.
*   **Lazy Initialization**: Secondary dictionary loaded only when feature enabled.
*   **Memory Management**: Inactive language models (ONNX sessions) unloaded after 60s timeout.
*   **Unigram Cache**: Language detection unigram lists kept in memory (~100KB per language).

---

## 11. Future Roadmap
*   **v1.3**: Cloud sync for Custom Dictionaries.
*   **v1.4**: Language-specific neural models (fine-tuned on each language).
*   **v1.5**: User-generated Language Packs tool.
*   **v2.0**: Multi-script support (Cyrillic, Arabic, CJK).
