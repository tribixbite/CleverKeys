# Neural Multilanguage Architecture Specification

## Feature Overview
**Feature Name**: Neural Swipe Prediction Multilanguage System
**Status**: Implemented (v1.1.85) - Language-Specific Beam Search Tries + Contractions
**Last Updated**: 2026-01-05

### Summary
This document describes the **actual** neural swipe prediction pipeline and how multilanguage support is integrated. It documents a critical architectural constraint that affects non-English language support.

---

## 1. Neural Prediction Pipeline

### 1.1 Complete Data Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SWIPE INPUT PROCESSING                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Touch Events → SwipeInput (coordinates, timestamps)                        │
│       │                                                                      │
│       ▼                                                                      │
│  SwipeTrajectoryProcessor.extractFeatures()                                  │
│       │                                                                      │
│       ▼                                                                      │
│  TrajectoryFeatures:                                                         │
│    - normalizedPoints: [x, y, vx, vy, ax, ay] × 250 timesteps               │
│    - nearestKeys: [token_idx] × 250 timesteps (4-29 = a-z)                  │
│    - actualLength: int                                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ONNX ENCODER                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Input:                                                                      │
│    - trajectory_features: [1, 250, 6]  (x, y, vx, vy, ax, ay)              │
│    - nearest_keys: [1, 250]             (token indices)                      │
│                                                                              │
│  Output:                                                                     │
│    - memory: [1, 250, 256]              (encoder hidden states)             │
│                                                                              │
│  Model: swipe_encoder_android.onnx (~2MB)                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    BEAM SEARCH DECODER (⚠️ CRITICAL CONSTRAINT)              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ⚠️ TRIE-GUIDED DECODING: Uses ENGLISH VocabularyTrie for path masking      │
│                                                                              │
│  Token Vocabulary (30 tokens):                                               │
│    - 0: PAD                                                                  │
│    - 1: UNK                                                                  │
│    - 2: SOS (start of sequence)                                             │
│    - 3: EOS (end of sequence)                                               │
│    - 4-29: a-z (lowercase letters)                                          │
│                                                                              │
│  Beam Search Process:                                                        │
│    1. Start with SOS token                                                   │
│    2. For each step:                                                         │
│       a. Decoder predicts logits for next token [1, 20, 30]                 │
│       b. applyTrieMasking() → MASKS tokens that would create invalid        │
│          prefixes according to ENGLISH vocabulary trie                       │
│       c. Select top-k tokens after masking                                   │
│       d. Continue until EOS or max_length                                    │
│                                                                              │
│  ⚠️ IMPLICATION: Beam search can ONLY produce valid ENGLISH words!          │
│     French words like "être" (normalized: "etre") are blocked because       │
│     "etre" is not in the English vocabulary trie.                           │
│                                                                              │
│  Output: List<BeamSearchCandidate(word, confidence, score)>                 │
│          (All words are lowercase a-z only)                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      POST-PROCESSING & FILTERING                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PredictionPostProcessor.process(candidates, swipeStats)                    │
│       │                                                                      │
│       ▼                                                                      │
│  OptimizedVocabulary.filterPredictions():                                    │
│                                                                              │
│  For each candidate word from beam search:                                   │
│                                                                              │
│    1. Validate format (a-z only)                                            │
│    2. Check prefix accuracy (starting letter matches swipe start)           │
│    3. Check disabled words (user-blocked words)                             │
│                                                                              │
│    4. ★ PRIMARY DICTIONARY LOOKUP (if non-English primary):                 │
│       └─ normalizedIndex.getPrimaryAccentedForm(word)                       │
│       └─ If found: displayWord = accented form (e.g., "café")               │
│       └─ Creates WordInfo with frequency from primary dict                  │
│                                                                              │
│    5. ★ ENGLISH FALLBACK (if _englishFallbackEnabled):                      │
│       └─ vocabulary[word] → English WordInfo                                │
│       └─ ONLY used if word NOT found in primary dict                        │
│                                                                              │
│    6. If word not in any dictionary: FILTERED OUT                           │
│                                                                              │
│    7. Calculate combined score:                                              │
│       score = nnConfidence × frequencyScore × tierBoost                     │
│                                                                              │
│  Output: FilteredPrediction(word, displayText, score, source)               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Key Files and Their Roles

### 2.1 Inference Pipeline

| File | Purpose |
|------|---------|
| `SwipePredictorOrchestrator.kt` | Main orchestrator - coordinates all components |
| `SwipeTrajectoryProcessor.kt` | Extracts trajectory features from touch points |
| `EncoderWrapper.kt` | Wraps ONNX encoder model inference |
| `DecoderWrapper.kt` | Wraps ONNX decoder model inference |
| `BeamSearchEngine.kt` | Beam search with trie-guided decoding |
| `PredictionPostProcessor.kt` | Vocabulary filtering and score calculation |
| `OptimizedVocabulary.kt` | Dictionary management and word lookup |

### 2.2 Dictionary Files

| File | Location | Purpose |
|------|----------|---------|
| `en_enhanced.bin` | `assets/dictionaries/` | English vocabulary (~50k words) |
| `fr_enhanced.bin` | `assets/dictionaries/` | French vocabulary (25k words) |
| `{lang}_enhanced.bin` | `assets/dictionaries/` | Other bundled languages |
| `VocabularyTrie` | Memory (loaded from en_enhanced.bin) | **Beam search path constraint** |
| `NormalizedPrefixIndex` | Memory (loaded from primary dict) | Accent recovery lookups |

### 2.3 Configuration

| Preference Key | Default | Purpose |
|----------------|---------|---------|
| `pref_primary_language` | `"en"` | Primary language code |
| `pref_secondary_language` | `"none"` | Secondary language (bilingual) |
| `pref_enable_multilang` | `false` | Enable secondary language feature |

---

## 3. Dictionary Format (V2 Binary)

### 3.1 Header Structure (48 bytes)

```
Offset  Size  Field
------  ----  -----
0x00    4     Magic: 0x434B4454 ("CKDT")
0x04    4     Version: 2
0x08    4     Canonical word count
0x0C    4     Normalized word count
0x10    4     Accent map entry count
0x14    4     Flags (reserved)
0x18    4     Reserved
0x1C    4     Reserved
0x20    4     Canonical section offset
0x24    4     Normalized section offset
0x28    4     Accent map section offset
0x2C    4     Reserved
```

### 3.2 Canonical Section
- Format: `word\tfrequency_rank\n`
- Words stored in UTF-8 with accents (e.g., "café", "être")
- Frequency rank: 0-255 (0 = most common)

### 3.3 Normalized Section
- Format: `normalized_word\tbest_canonical_index\tbest_frequency_rank\n`
- Normalized words are accent-stripped lowercase (e.g., "cafe", "etre")

### 3.4 Accent Map Section
- Format: `normalized_word\tcanonical1:rank1,canonical2:rank2,...\n`
- Maps normalized → all canonical forms with their ranks
- Example: `cafe\tcafé:111,cafe:171`

---

## 4. Dictionary Generation Pipeline

### 4.1 Build Scripts

```
scripts/
├── build_all_languages.py    # Master build script
├── build_langpack.py         # Generates single language pack
├── get_wordlist.py           # Downloads from wordfreq library
└── dictionaries/
    ├── build_v2_binary.py    # V2 binary format generator
    └── langpack-{lang}.zip   # Distribution packages
```

### 4.2 Build Process

```bash
# 1. Generate word list with frequencies
python scripts/get_wordlist.py fr 25000

# 2. Build V2 binary dictionary
python scripts/dictionaries/build_v2_binary.py \
    --input fr_words_25000.txt \
    --output fr_enhanced.bin \
    --lang fr

# 3. Package as language pack
python scripts/build_langpack.py fr
```

### 4.3 Word Sources

| Source | Library | License | Notes |
|--------|---------|---------|-------|
| Word frequencies | `wordfreq` | MIT / CC BY-SA 4.0 | Primary source |
| Fallback lists | FrequencyWords | CC BY-SA 4.0 | OpenSubtitles-based |

---

## 5. Language Configuration Flow

### 5.1 Settings Change

```kotlin
// SettingsActivity.kt
fun onPrimaryLanguageChanged(lang: String) {
    saveSetting("pref_primary_language", lang)
    // Triggers orchestrator reload via broadcast
}

// SwipePredictorOrchestrator.kt
fun loadPrimaryDictionaryFromPrefs() {
    val primaryLang = prefs.getString("pref_primary_language", "en")
    val secondaryLang = prefs.getString("pref_secondary_language", "none")

    // Configure English fallback
    vocabulary.setPrimaryLanguageConfig(primaryLang, secondaryLang)

    // Load primary dictionary for non-English
    if (primaryLang != "en") {
        vocabulary.loadPrimaryDictionary(primaryLang)
    }
}
```

### 5.2 English Fallback Logic

```kotlin
// OptimizedVocabulary.kt
fun setPrimaryLanguageConfig(primary: String, secondary: String) {
    _englishFallbackEnabled = (primary == "en") || (secondary == "en")
    //
    // When Primary=French, Secondary=None:
    //   _englishFallbackEnabled = false
    //   → Only French dictionary used for validation
    //   → English words NOT in French dict are filtered out
    //
    // When Primary=French, Secondary=English:
    //   _englishFallbackEnabled = true
    //   → Both dictionaries used for validation
}
```

---

## 6. Critical Architecture Limitation

### 6.1 The Problem

**The beam search is constrained by the English vocabulary trie.**

```kotlin
// BeamSearchEngine.kt line 312
val allowed = vocabTrie.getAllowedNextChars(prefix)
// ...
if (!allowed.contains(c.lowercaseChar())) {
    logits[i] = Float.NEGATIVE_INFINITY  // Block this path
}
```

This means:
- Beam search can ONLY produce words that exist in English vocabulary
- French words not in English (e.g., "être" → "etre") are blocked
- Only French words that overlap with English can be predicted

### 6.2 Words That WILL Work

Words that exist in **both** English and French:
- "table" → "table" (same in both)
- "cafe" → "café" (English "cafe" maps to French "café")
- "hotel" → "hôtel"
- "menu" → "menu"

### 6.3 Words That WON'T Work

French words NOT in English vocabulary:
- "être" (normalized: "etre" - not English word)
- "français" (normalized: "francais" - not English word)
- "après" (normalized: "apres" - not English word)

### 6.4 Implemented Solution (v1.1.85)

**Language-Specific Beam Search Tries**

Each language dictionary generates its own trie from normalized words. When primary language changes, the active beam search trie is swapped:

```kotlin
// OptimizedVocabulary.kt
private val vocabularyTrie: VocabularyTrie        // English (always loaded)
private var activeBeamSearchTrie: VocabularyTrie  // Points to active language's trie

// loadPrimaryDictionary() - builds French trie from normalized words
val normalizedWords = index.getAllNormalizedWords()  // "etre", "cafe", "francais"...
val languageTrie = VocabularyTrie()
languageTrie.insertAll(normalizedWords)
activeBeamSearchTrie = languageTrie  // Beam search now uses French trie

// unloadPrimaryDictionary() - reset to English
activeBeamSearchTrie = vocabularyTrie
```

**Data Flow:**
```
French Dictionary: être, café, français, école...
         ↓ normalize (remove accents)
Normalized Forms: etre, cafe, francais, ecole...
         ↓ build trie
French Trie (activeBeamSearchTrie)
         ↓ beam search produces "etre"
Accent Map: etre → être
         ↓ post-processing
Output: être
```

**Benefits:**
- Clean separation: no mixing of English + French in single trie
- Beam search uses ONLY the primary language's vocabulary
- Memory efficient: separate tries, swap on language change
- Proper architecture: each language has its own constraint space

---

## 7. Prefix Boost Trie (Aho-Corasick)

### 7.1 Problem Statement

Even with language-specific vocabulary tries, the neural network is trained primarily on English text. This means the model's logit predictions may unfairly favor English-like character sequences over valid non-English patterns (e.g., "ão" in Portuguese, "ñ" normalized as "n" in Spanish).

### 7.2 Solution: Language-Specific Prefix Boosting

The prefix boost system applies logit adjustments during beam search to boost character sequences that are common in the target language but rare in English.

```
┌────────────────────────────────────────────────────────────────┐
│                    BEAM SEARCH WITH PREFIX BOOST               │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  For each beam candidate at each decoding step:                │
│                                                                │
│  1. Get decoder logits for next token [30 values]              │
│  2. Apply trie masking (invalid prefixes → -∞)                 │
│  3. ★ Apply prefix boosts from PrefixBoostTrie:                │
│       for each char 'a'-'z':                                   │
│         boost = trie.getBoost(currentState, char)              │
│         logits[char] += boost * multiplier                     │
│  4. Select top-k candidates                                    │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### 7.3 Aho-Corasick Trie Implementation

The `PrefixBoostTrie` uses an Aho-Corasick automaton for O(1) amortized state transitions and boost lookups.

**File**: `src/main/kotlin/tribixbite/cleverkeys/onnx/PrefixBoostTrie.kt`

**Binary Format (Version 2 - Sparse)**:
```
Header (16 bytes):
  - Magic: "PBST" (4 bytes)
  - Version: 2 (4 bytes)
  - NodeCount: int (4 bytes)
  - EdgeCount: int (4 bytes)

Data Sections:
  - Node Offsets: (NodeCount + 1) × 4 bytes
  - Edge Keys: EdgeCount × 1 byte (char indices 0-25)
  - Edge Targets: EdgeCount × 4 bytes
  - Failure Links: NodeCount × 4 bytes
  - Boost Values: NodeCount × 4 bytes (floats)
```

**Performance Characteristics**:
- `getNextState()`: O(k) where k = avg children per node (~4)
- `getBoost()`: O(k) linear scan over sorted edges
- Zero heap allocations during lookup (thread-safe local refs)
- Memory-mapped file doesn't use Java heap

### 7.4 BeamState Tracking

Each beam candidate carries a `boostState: Int` representing the current position in the prefix boost trie:

```kotlin
data class BeamState(
    val tokens: ShortArray,
    val score: Float,
    val length: Int,
    val boostState: Int = 0  // Current state in PrefixBoostTrie
)
```

When extending a beam with character `c`, the boost state advances:
```kotlin
newBoostState = prefixBoostTrie.getNextState(beam.boostState, c)
```

### 7.5 User Configuration

Per-language prefix boost settings are stored in SharedPreferences:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `neural_prefix_boost_multiplier_{lang}` | Float | 1.0 | Scale factor for boost values |
| `neural_prefix_boost_max_{lang}` | Float | 2.0 | Maximum absolute boost applied |

**Fallback Logic** (Config.kt):
1. Try per-language key: `neural_prefix_boost_multiplier_fr`
2. Fall back to global key: `neural_prefix_boost_multiplier`
3. Fall back to default: `Defaults.NEURAL_PREFIX_BOOST_MULTIPLIER`

**UI Location**: Settings → Multi-Language → Prefix Boost Strength / Max Boost

### 7.6 Asset Files

Prefix boost tries are bundled as binary assets:

| File | Language | Description |
|------|----------|-------------|
| `prefix_boosts/de.bin` | German | Boosts for ß → ss, ü → u, etc. |
| `prefix_boosts/es.bin` | Spanish | Boosts for ñ sequences, ll, rr |
| `prefix_boosts/fr.bin` | French | Boosts for é, è, ê sequences |
| `prefix_boosts/it.bin` | Italian | Boosts for Italian patterns |
| `prefix_boosts/pt.bin` | Portuguese | Boosts for ã, ç sequences |

English (`en`) does not need prefix boosts (model already trained on English).

### 7.7 Thread Safety

The `PrefixBoostTrie` class is thread-safe:
- `loadFromAssets()` and `unload()` are `@Synchronized`
- Read methods (`getNextState()`, `getBoost()`) capture local buffer references to prevent NPE during concurrent unload

### 7.8 Import/Export

Per-language prefix boost settings are included in profile backup/restore:
- `BackupRestoreManager.isFloatPreference()` recognizes `neural_prefix_boost_*` patterns
- Validation ensures values are within valid ranges (0-3 for multiplier, 0-10 for max)

---

## 8. Bundled Languages

### 8.1 In APK Assets

| Language | Code | File | Words | Accented |
|----------|------|------|-------|----------|
| English | en | en_enhanced.bin | ~50k | 0 |
| Spanish | es | es_enhanced.bin | ~236k | ~15k |
| French | fr | fr_enhanced.bin | 25k | ~6.7k |
| Portuguese | pt | pt_enhanced.bin | 25k | ~5k |
| Italian | it | it_enhanced.bin | 25k | ~4k |
| German | de | de_enhanced.bin | 25k | ~3k |

### 7.2 Downloadable Language Packs

Located in `scripts/dictionaries/langpack-{lang}.zip`:

| Language | Code | File | Words | Contractions |
|----------|------|------|-------|--------------|
| Dutch | nl | langpack-nl.zip | 20k | 118 (auto's, zo'n) |
| Indonesian | id | langpack-id.zip | 20k | 0 |
| Malay | ms | langpack-ms.zip | 20k | 0 |
| Swahili | sw | langpack-sw.zip | 20k | 0 |
| Tagalog | tl | langpack-tl.zip | 20k | 0 |

### 7.3 Language Pack File Format

Each `.zip` language pack contains:

```
langpack-{lang}.zip
├── manifest.json      # Metadata: code, name, version, wordCount
├── dictionary.bin     # V2 binary dictionary with accent normalization
├── unigrams.txt       # Top 5000 words for language detection
└── contractions.json  # Optional: apostrophe word mappings
```

### 7.4 Contractions (Apostrophe Words)

Some languages use apostrophes for contractions or grammatical constructs. The `contractions.json` file maps apostrophe-free forms to their canonical apostrophe forms:

```json
{
  "cest": "c'est",
  "jai": "j'ai",
  "autos": "auto's"  // Dutch plural
}
```

**Language-specific apostrophe usage:**

| Language | Apostrophe Use | Examples |
|----------|----------------|----------|
| French | Contractions | c'est, j'ai, l'homme, d'accord |
| Italian | Elisions | l'uomo, un'amica, dell'arte |
| Dutch | Plurals + Contractions | auto's, foto's, zo'n, z'n |
| English | Contractions + Possessives | don't, it's, John's |
| German | Colloquial only | wie's, gibt's |
| Spanish | Rare | None commonly used |
| Portuguese | Rare | None commonly used |
| Indonesian | Not used | - |
| Malay | Not used | - |
| Swahili | Not used | - |
| Tagalog | Not used | - |

**Contraction counts by language (bundled):**
- French: 27,494 mappings
- Italian: 22,474 mappings
- English: 102 mappings
- Dutch: 118 mappings (including plurals)
- German: 4 mappings
- Spanish: 0
- Portuguese: 0

---

## 9. Performance Characteristics

### 9.1 Typical Inference Timing

| Stage | Time (ms) | Notes |
|-------|-----------|-------|
| Feature extraction | 1-3 | Trajectory processing |
| Encoder | 5-15 | ONNX inference |
| Decoder (beam) | 20-80 | Depends on word length, beam width |
| Post-processing | 1-5 | Dictionary lookups |
| **Total** | **30-100** | End-to-end |

### 9.2 Memory Usage

| Component | Size | Notes |
|-----------|------|-------|
| ONNX Encoder | ~2MB | Loaded on init |
| ONNX Decoder | ~4MB | Loaded on init |
| English vocabulary | ~3MB | Always loaded (trie + HashMap) |
| Primary dictionary | ~600KB | Loaded when non-English |
| NormalizedPrefixIndex | ~500KB | For accent lookups |

---

## 10. Testing Checklist

### 10.1 Dictionary Verification (Verified 2026-01-04)
- [x] FR: V2 format, 25k canonical, 23.7k normalized (être, café, français ✓)
- [x] ES: V2 format, 236k canonical, 223k normalized (niño, español, años ✓)
- [x] PT: V2 format, 25k canonical, 24.5k normalized (você, não, também ✓)
- [x] IT: V2 format, 25k canonical, 24.8k normalized (perché, più, città ✓)
- [x] DE: V2 format, 25k canonical, 24.8k normalized (für, über, größe ✓)

### 9.2 Basic Functionality
- [x] Language-specific beam search trie architecture implemented
- [x] Primary dictionary loads trie from normalized words
- [x] activeBeamSearchTrie swaps on language change
- [ ] Swipe "hello" with Primary=English → "hello"
- [ ] Swipe "cafe" with Primary=French → "café"
- [ ] Swipe "etre" with Primary=French → "être"

### 9.3 English Fallback
- [x] Code verified: `_englishFallbackEnabled = (primary == "en") || (secondary == "en")`
- [ ] Primary=French, Secondary=None → English words filtered out
- [ ] Primary=French, Secondary=English → English words allowed

### 10.4 Edge Cases
- [ ] Words in both languages (e.g., "table") display correctly
- [ ] Short words (2-3 letters) work with accents
- [ ] Long words (10+ letters) complete successfully

---

## 11. Known Issues

1. ~~**French-only words blocked**: See Section 6 - architectural limitation~~ **RESOLVED** with language-specific tries
2. **Latency on long words**: Beam search timeout at 20 characters
3. **Memory pressure**: Loading multiple language dictionaries simultaneously
4. **English words appear in non-English mode** - See Section 11.1

### 11.1 English Words in Non-English Mode

**Issue**: Some English words still appear in predictions when Primary=French (or other non-English) and Secondary=None.

**Root Causes** (NOT bugs - intentional behavior):

1. **English contractions loaded as fallback** (~100 words)
   ```kotlin
   // OptimizedVocabulary.kt loadContractionMappings()
   if (_primaryLanguageCode != "en") {
       loadLanguageContractions("en")  // Loads dont, cant, wont, etc.
   }
   ```
   These ~100 English contraction keys are added to `nonPairedContractions`, then to the language trie. This allows "dont" → "don't" to work even in French mode.

2. **Word overlap between languages** (thousands of words)
   Many words exist in BOTH English and the target language:
   - French: table, menu, hotel, simple, possible, important, service...
   - These are legitimate French words that happen to also be English words
   - The French dictionary contains them, so they're valid predictions

3. **Custom words are language-agnostic**
   User-added custom words are not filtered by language.

**What IS filtered** (working correctly):
- The 50,000-word English vocabulary is NOT used when `_englishFallbackEnabled = false`
- English words that don't exist in the target language dictionary are filtered out
- The beam search trie only contains target language words + contractions

**What appears as "English"**:
- ~100 English contraction keys (intentional fallback)
- ~1000+ cognates/loanwords that exist in both languages (legitimate target language words)

**Potential future improvements**:
- Option to disable English contraction fallback per-language
- Separate contraction loading by language (no fallback)
- Visual indicator for cognate/shared words

### 11.2 CRITICAL BUG: English Words Predicted in French-Only Mode

**Symptom**: User types in French-only mode (Primary=French, Secondary=None) but all predictions are English words like "every", "word", "this", "was", "done".

**Status**: Under investigation

**Expected behavior**:
- Beam search should use French trie (only French normalized words)
- English words NOT in French dictionary should be unpredictable
- Only words that exist in French dictionary should appear

**Possible root causes** (to investigate):

1. **French trie not being set as active**
   - `loadPrimaryDictionary()` may not be called when language changes
   - `activeBeamSearchTrie` may still point to English trie
   - Check: Does `reloadPrimaryDictionary()` get called on language change?

2. **Keyboard not initialized when language changes**
   - `CleverKeysService.onSharedPreferenceChanged()` returns early if `!::_layoutBridge.isInitialized`
   - If user changes language before using keyboard, reload is skipped
   - Fix needed: Reload on keyboard init if pending language change

3. **French dictionary loading failure**
   - `BinaryDictionaryLoader.loadIntoNormalizedIndex()` might fail silently
   - If load fails, `activeBeamSearchTrie` stays as English trie
   - Check: Verify `fr_enhanced.bin` loads correctly

4. **Race condition**
   - Predictions requested before dictionary reload completes
   - BeamSearchEngine created with old trie reference
   - Fix needed: Block predictions during language switch

**Diagnostic steps**:
1. Enable debug logging in OptimizedVocabulary
2. Log when `loadPrimaryDictionary()` is called and what trie is set
3. Log what trie BeamSearchEngine receives
4. Verify French dictionary contains expected words (not English words)

**Code locations to check**:
- `CleverKeysService.kt:747-771` - onSharedPreferenceChanged handler
- `PreferenceUIUpdateHandler.kt:102-126` - reloadLanguageDictionaryIfNeeded
- `SwipePredictorOrchestrator.kt:488-490` - reloadPrimaryDictionary
- `OptimizedVocabulary.kt:1111-1175` - loadPrimaryDictionary
- `OptimizedVocabulary.kt:1166` - activeBeamSearchTrie assignment

---

## 12. Future Work

1. ~~**Expand vocabulary trie** to include normalized forms~~ **DONE** - each language has its own trie
2. **Language-specific neural models** (currently all languages use English model)
3. **Adaptive trie** that learns from user's typing patterns
4. **Multi-script support** (Cyrillic, Arabic) requires new models
5. **Language pack download UI** for additional languages (NL, ID, MS, SW, TL)
