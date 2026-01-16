# Neural Multilanguage Architecture

## Overview

Neural swipe prediction pipeline with multilanguage support via language-specific beam search tries. The ONNX model outputs 26-letter sequences which are then validated against the active language's vocabulary trie and mapped to accented forms via dictionary lookup.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/onnx/SwipePredictorOrchestrator.kt` | Main orchestrator | Coordinates all components |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/BeamSearchEngine.kt` | `applyTrieMasking()` | Trie-guided decoding |
| `src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabulary.kt` | `activeBeamSearchTrie` | Language-specific trie swapping |
| `src/main/kotlin/tribixbite/cleverkeys/PredictionPostProcessor.kt` | `process()` | Vocabulary filtering, accent recovery |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/PrefixBoostTrie.kt` | Aho-Corasick trie | Language-specific logit boosting |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   SWIPE INPUT PROCESSING                     │
│  Touch Events → SwipeInput → TrajectoryFeatures             │
│  normalizedPoints: [x, y, vx, vy, ax, ay] × 250             │
│  nearestKeys: [token_idx] × 250 (4-29 = a-z)                │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                      ONNX ENCODER                            │
│  Input: trajectory_features [1, 250, 6], nearest_keys [1, 250] │
│  Output: memory [1, 250, 256]                                │
│  Model: swipe_encoder_android.onnx (~2MB)                   │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│           BEAM SEARCH DECODER (Language-Specific Trie)       │
│                                                              │
│  Token Vocabulary (30 tokens):                               │
│    0: PAD, 1: UNK, 2: SOS, 3: EOS, 4-29: a-z                │
│                                                              │
│  Trie Masking: activeBeamSearchTrie.getAllowedNextChars()   │
│    → Masks invalid prefixes according to current language   │
│                                                              │
│  Prefix Boosting: prefixBoostTrie.getBoost(state, char)     │
│    → Boosts common language-specific patterns               │
│                                                              │
│  Output: List<BeamSearchCandidate(word, confidence)>        │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   POST-PROCESSING                            │
│  1. Validate format (a-z only)                              │
│  2. Check prefix accuracy (starting letter)                 │
│  3. Primary dictionary lookup → accented form (café)        │
│  4. English fallback (if enabled)                           │
│  5. Calculate score: nnConfidence × frequencyScore × boost  │
│  Output: FilteredPrediction(word, displayText, score)       │
└─────────────────────────────────────────────────────────────┘
```

## Language-Specific Trie System

### Trie Swapping on Language Change

```kotlin
// OptimizedVocabulary.kt
private val vocabularyTrie: VocabularyTrie        // English (always loaded)
private var activeBeamSearchTrie: VocabularyTrie  // Points to active language

fun loadPrimaryDictionary(language: String) {
    // Build trie from normalized words: "etre", "cafe", "francais"...
    val normalizedWords = index.getAllNormalizedWords()
    val languageTrie = VocabularyTrie()
    languageTrie.insertAll(normalizedWords)
    activeBeamSearchTrie = languageTrie  // Beam search now uses this trie
}

fun unloadPrimaryDictionary() {
    activeBeamSearchTrie = vocabularyTrie  // Reset to English
}
```

### Data Flow for French

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

## V2 Binary Dictionary Format

### Header (48 bytes)

```
Offset  Size  Field
------  ----  -----
0x00    4     Magic: 0x434B4454 ("CKDT")
0x04    4     Version: 2
0x08    4     Canonical word count
0x0C    4     Normalized word count
0x10    4     Accent map entry count
0x14    4     Flags (reserved)
0x20    4     Canonical section offset
0x24    4     Normalized section offset
0x28    4     Accent map section offset
```

### Sections

| Section | Format | Purpose |
|---------|--------|---------|
| Canonical | `word\tfrequency_rank\n` | UTF-8 with accents (café) |
| Normalized | `word\tbest_index\tbest_rank\n` | Accent-stripped (cafe) |
| Accent Map | `normalized\tform1:rank,form2:rank\n` | cafe → café:111,cafe:171 |

## Prefix Boost Trie (Aho-Corasick)

Boosts language-specific character patterns during beam search:

```kotlin
// Applied during decoding
for (char in 'a'..'z') {
    val boost = prefixBoostTrie.getBoost(currentState, char)
    logits[char] += boost * multiplier
}
```

### Binary Format

```
Header (16 bytes):
  Magic: "PBST" (4 bytes)
  Version: 2 (4 bytes)
  NodeCount: int (4 bytes)
  EdgeCount: int (4 bytes)

Data:
  Node Offsets, Edge Keys, Edge Targets, Failure Links, Boost Values
```

### Asset Files

| File | Language | Purpose |
|------|----------|---------|
| `prefix_boosts/de.bin` | German | ß → ss, ü → u |
| `prefix_boosts/es.bin` | Spanish | ñ sequences, ll, rr |
| `prefix_boosts/fr.bin` | French | é, è, ê sequences |
| `prefix_boosts/pt.bin` | Portuguese | ã, ç sequences |

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| Primary Language | `pref_primary_language` | `"en"` | Main language |
| Secondary Language | `pref_secondary_language` | `"none"` | Additional language |
| Enable Multilang | `pref_enable_multilang` | false | Enable secondary |
| Boost Multiplier | `neural_prefix_boost_multiplier_{lang}` | 1.0 | Boost scale factor |
| Boost Max | `neural_prefix_boost_max_{lang}` | 2.0 | Maximum boost |

## Bundled Languages

| Language | Code | File | Words |
|----------|------|------|-------|
| English | en | en_enhanced.bin | ~50k |
| Spanish | es | es_enhanced.bin | ~236k |
| French | fr | fr_enhanced.bin | 25k |
| Portuguese | pt | pt_enhanced.bin | 25k |
| Italian | it | it_enhanced.bin | 25k |
| German | de | de_enhanced.bin | 25k |

## Contraction Support

Languages with apostrophe words have `contractions.json` in their language packs:

```json
{
  "cest": "c'est",
  "jai": "j'ai",
  "autos": "auto's"
}
```

| Language | Contractions |
|----------|--------------|
| French | 27,494 |
| Italian | 22,474 |
| English | 102 |
| Dutch | 118 |
| German | 4 |

## Performance

| Stage | Time | Notes |
|-------|------|-------|
| Feature extraction | 1-3ms | Trajectory processing |
| Encoder | 5-15ms | ONNX inference |
| Decoder (beam) | 20-80ms | Depends on word length, beam width |
| Post-processing | 1-5ms | Dictionary lookups |
| **Total** | **30-100ms** | End-to-end |

## Memory Usage

| Component | Size |
|-----------|------|
| ONNX Encoder | ~2MB |
| ONNX Decoder | ~4MB |
| English vocabulary | ~3MB |
| Primary dictionary | ~600KB |
| NormalizedPrefixIndex | ~500KB |
