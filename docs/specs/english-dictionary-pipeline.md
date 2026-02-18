# English Dictionary Build Pipeline

## Overview
**Feature Name**: Dictionary Generation & Quality Pipeline
**Status**: Complete (V3), quality improvements pending
**Last Updated**: 2026-02-17

### Summary
The English dictionary pipeline generates `en_enhanced.bin` (V2 binary format, 52,042 words) from multi-corpus word frequency data, with accent normalization and contraction support. The pipeline currently has no misspelling filter — all words are included purely by frequency rank.

### Motivation
A keyboard dictionary must contain only legitimate words. Misspellings in the dictionary become suggestions, undermining user trust. The contraction system must correctly map apostrophe-free swipe input to proper English contractions.

## Architecture

### Build Pipeline Flow

```
Source Corpora                    Merge & Curate              Binary Build
─────────────                    ──────────────              ────────────
en_norvig_50k.txt ──┐
  (Google web crawl) │
                     ├──→ scripts/dictionaries/en/    ──→ build_dictionary.py
en_opensubtitles_50k │      en_words.txt (52,042)          --use-wordfreq
  (movie subtitles)  │                                         │
                     │                                         ▼
en_wordfreq_words.txt┘                              en_enhanced.bin (V2 binary)
  (wordfreq lib 25k)                                  │
                                                      ├─→ src/main/assets/dictionaries/
                                                      └─→ scripts/dictionaries/en/
```

### Contraction Pipeline

```
Source                        Binary Build                     Runtime
──────                        ────────────                     ───────
contractions_non_paired.json ─┐
  (119 entries: dont→don't)   │
                              ├──→ generate_binary_contractions.py ──→ contractions.bin
contraction_pairings.json ────┘       │
  (10,637 lines: possessives          │
   + contraction variants)            │
                                      ├──→ ContractionManager.loadMappings()
Per-language:                         │      1. Try contractions.bin (fastest)
  contractions_en.json ───────────────┘      2. Fall back to JSON files
  contractions_fr.json                       3. loadLanguageContractions(langCode)
  contractions_sv.json
  ...
```

### Runtime Application

```
User swipes "cant"
    │
    ▼
Neural Network → ["cant", "can", "cat", ...]
    │
    ▼
InputCoordinator.handlePredictionResults() (line 255-259)
    │  contractionManager.getNonPairedMapping("cant") → "can't"
    ▼
SuggestionHandler.onSuggestionSelected() (line 1100-1114)
    │  Contraction key gets +1000 score boost
    ▼
Suggestion bar: ["can't", "can", "cat"]
```

## Key Files

### Dictionary Build Scripts

| Script | Purpose | Input | Output |
|--------|---------|-------|--------|
| `scripts/get_wordlist.py` | Extract words from wordfreq | `--lang en --count 50000` | `en_words.txt` |
| `scripts/build_dictionary.py` | Build V2 binary dict | Word list + `--use-wordfreq` | `{lang}_enhanced.bin` |
| `scripts/build_langpack.py` | Bundle dict + unigrams + prefix boosts + contractions | `--input` or `--dict` | `langpack-{lang}.zip` |
| `scripts/build_all_languages.py` | Batch build all bundled languages | (auto) | Multiple `.bin` files |
| `scripts/compute_prefix_boosts.py` | Aho-Corasick prefix trie | `--langs en` | `prefix_boosts/{lang}.bin` |

### Contraction Build Scripts

| Script | Purpose | Input | Output |
|--------|---------|-------|--------|
| `scripts/generate_binary_contractions.py` | JSON → binary contractions | `non_paired.json` + `pairings.json` | `contractions.bin` |
| `scripts/generate_apostrophe_words.py` | Multi-language contraction lists | Language rules | `contractions_{lang}.json` |
| `scripts/extract_apostrophe_words.py` | Extract apostrophe words from corpus | Corpus text | Apostrophe word list |

### Source Word Lists (in `scripts/`)

| File | Words | Format | Source |
|------|-------|--------|--------|
| `en_norvig_50k.txt` | 50,000 | `word<TAB>frequency` | Peter Norvig's Google Web Corpus |
| `en_opensubtitles_50k.txt` | 50,000 | `word<SPACE>frequency` | OpenSubtitles movie subtitles |
| `en_wordfreq_words.txt` | 25,000 | `word` (one per line) | wordfreq Python library |

### Curated Dictionary

| File | Words | Description |
|------|-------|-------------|
| `scripts/dictionaries/en/en_words.txt` | 52,042 | V3 curated merge of all sources |
| `scripts/dictionaries/en/en_enhanced.bin` | 52,042 | V2 binary built from en_words.txt |
| `src/main/assets/dictionaries/en_enhanced.bin` | 52,042 | **Shipped binary (identical to above)** |
| `src/main/assets/dictionaries/en_enhanced.txt` | 49,297 | **Vestigial V1 word list (NOT used at runtime)** |

### Contraction Assets (in `src/main/assets/dictionaries/`)

| File | Entries | Description |
|------|---------|-------------|
| `contractions_non_paired.json` | 119 | `dont→don't` style mappings (base) |
| `contractions_en.json` | 119 | **Identical** to `contractions_non_paired.json` |
| `contraction_pairings.json` | ~2,100 | Base word → possessive/contraction variants |
| `contraction_pairings_cleaned.json` | ~60 | Real contractions only (no possessives) |
| `contractions.bin` | ~2,219 | Binary format of non-paired + paired |

### Runtime Kotlin Classes

| Class | File | Purpose |
|-------|------|---------|
| `ContractionManager` | `ContractionManager.kt` | Loads and queries contraction mappings |
| `BinaryContractionLoader` | `BinaryContractionLoader.kt` | Fast binary contraction parser |
| `OptimizedVocabulary` | `OptimizedVocabulary.kt` | Dictionary loading (`{lang}_enhanced.bin`) |
| `BinaryDictionaryLoader` | `BinaryDictionaryLoader.kt` | V2 binary dictionary parser |
| `InputCoordinator` | `InputCoordinator.kt:255-259` | Applies contraction transform to predictions |
| `SuggestionHandler` | `SuggestionHandler.kt:1100-1114` | Contraction score boost + transform |

## Dictionary History

| Version | Commit | Words | Changes |
|---------|--------|-------|---------|
| V1 | `bda21299` | 49,297 | Upstream Unexpected-Keyboard word list |
| V2 | `e9fcc7da` | 50,000 | Norvig Web Corpus langpack |
| **V3** | `674fc32a` | **52,042** | Merged V1 + 2,763 from wordfreq, removed 15 typos + 3 offensive |

### V3 Curation Details (commit `674fc32a`)
- **Base**: V1 (49,297 words)
- **Added**: 2,763 high-value words from wordfreq not in V1
- **Removed (15 typos)**: dissapointed, recieved, thier, definately, seperately, occured, arguement, independant, neccessary, occurence, persistant, refered, succesful, accomodate, occassion
- **Removed (3 offensive)**: retard, retarded, shemale
- **Preserved**: Single letters, contractions without apostrophe, possessives, custom words (tribixbite, cleverkeys)
- **Missed**: teh, wich, hav still present in V3

## Known Quality Issues

### No Misspelling Filter
The pipeline has **no automated spell-checking step**. Words are included purely by frequency rank from wordfreq/corpus sources. Common misspellings that appear frequently on the internet make it into the dictionary.

### Known Remaining Misspellings in V3 (en_enhanced.bin)
From spot-check of the 52,042 shipped words:
- `teh` (rank 177) — "the" typo
- `wich` (rank 187) — "which" typo
- `hav` (rank 184) — "have" typo
- `nite` (rank 179) — informal for "night"
- `lite` (rank 159) — informal for "light" (but also a valid word: "lite beer")
- `dat` (rank 158) — informal for "that" (but also valid: data abbreviation)
- `wat` (rank 162) — informal for "what"

### Duplicate Contraction Files
`contractions_en.json` and `contractions_non_paired.json` are byte-identical. The per-language file (`contractions_en.json`) is loaded by `loadLanguageContractions("en")`, while the base file (`contractions_non_paired.json`) is loaded by `loadMappings()`. Both end up in the same `nonPairedContractions` map so this is harmless duplication.

### Vestigial Files
- `src/main/assets/dictionaries/en_enhanced.txt` — V1 word list (49,297 words). NOT loaded at runtime (app uses `.bin`). Contains more misspellings than V3. Should be deleted or replaced with V3 source.
- `src/main/assets/dictionaries/en_enhanced.json` — JSON export of dictionary. Used only by `OptimizedVocabulary.loadFromJSON()` as fallback if binary loading fails.

## Build Commands

### Rebuild English Dictionary from Scratch
```bash
cd scripts/

# 1. Generate word list from wordfreq
python3 get_wordlist.py --lang en --output en_words.txt --count 50000

# 2. Build binary dictionary
python3 build_dictionary.py --lang en --input en_words.txt --output en_enhanced.bin --use-wordfreq

# 3. Copy to assets
cp en_enhanced.bin ../src/main/assets/dictionaries/
```

### Rebuild from Curated V3 Source
```bash
cd scripts/

# Build from the curated word list (preferred — preserves manual curation)
python3 build_dictionary.py --lang en --input dictionaries/en/en_words.txt --output ../src/main/assets/dictionaries/en_enhanced.bin --use-wordfreq
```

### Rebuild Contractions
```bash
cd scripts/

# Regenerate binary contractions from JSON sources
python3 generate_binary_contractions.py \
  ../src/main/assets/dictionaries/contractions_non_paired.json \
  ../src/main/assets/dictionaries/contraction_pairings.json \
  ../src/main/assets/dictionaries/contractions.bin
```

### Build Language Pack
```bash
cd scripts/

# Full language pack (dict + unigrams + prefix boosts + contractions)
python3 get_wordlist.py --lang fr --output fr_words.txt --count 50000
python3 build_langpack.py --lang fr --name "French" --input fr_words.txt --use-wordfreq --output langpack-fr.zip
```

## Testing

### Existing Tests
- `ConfigDefaultsTest` — dictionary-related config defaults
- `IssueRegressionTest` — issue regression config checks
- `ContractionManagerTest` (instrumented) — binary/JSON loading, lookup, possessives

### Missing Tests
- No tests for `build_dictionary.py`, `get_wordlist.py`, or `build_langpack.py`
- No misspelling detection or dictionary quality tests
- No round-trip test (build → load → verify word count)

---

**Created**: 2026-02-17
**Owner**: tribixbite
