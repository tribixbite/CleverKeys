# Neural Multilanguage Architecture Specification

## Feature Overview
**Feature Name**: Neural Swipe Prediction Multilanguage System
**Status**: Implemented (v1.1.85) - Language-Specific Beam Search Tries
**Last Updated**: 2026-01-04

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

## 7. Bundled Languages

### 7.1 In APK Assets

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
- Dutch (nl)
- Indonesian (id)
- Malay (ms)
- Swahili (sw)
- Tagalog (tl)

---

## 8. Performance Characteristics

### 8.1 Typical Inference Timing

| Stage | Time (ms) | Notes |
|-------|-----------|-------|
| Feature extraction | 1-3 | Trajectory processing |
| Encoder | 5-15 | ONNX inference |
| Decoder (beam) | 20-80 | Depends on word length, beam width |
| Post-processing | 1-5 | Dictionary lookups |
| **Total** | **30-100** | End-to-end |

### 8.2 Memory Usage

| Component | Size | Notes |
|-----------|------|-------|
| ONNX Encoder | ~2MB | Loaded on init |
| ONNX Decoder | ~4MB | Loaded on init |
| English vocabulary | ~3MB | Always loaded (trie + HashMap) |
| Primary dictionary | ~600KB | Loaded when non-English |
| NormalizedPrefixIndex | ~500KB | For accent lookups |

---

## 9. Testing Checklist

### 9.1 Dictionary Verification (Verified 2026-01-04)
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

### 9.4 Edge Cases
- [ ] Words in both languages (e.g., "table") display correctly
- [ ] Short words (2-3 letters) work with accents
- [ ] Long words (10+ letters) complete successfully

---

## 10. Known Issues

1. ~~**French-only words blocked**: See Section 6 - architectural limitation~~ **RESOLVED** with language-specific tries
2. **Latency on long words**: Beam search timeout at 20 characters
3. **Memory pressure**: Loading multiple language dictionaries simultaneously

---

## 11. Future Work

1. ~~**Expand vocabulary trie** to include normalized forms~~ **DONE** - each language has its own trie
2. **Language-specific neural models** (currently all languages use English model)
3. **Adaptive trie** that learns from user's typing patterns
4. **Multi-script support** (Cyrillic, Arabic) requires new models
5. **Language pack download UI** for additional languages (NL, ID, MS, SW, TL)
