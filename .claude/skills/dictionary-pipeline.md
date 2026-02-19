# Dictionary Pipeline Skill

Use this skill when building, modifying, or quality-checking dictionaries and contractions for CleverKeys.

## Quick Reference

### Spec & Architecture
- **Full spec**: `docs/specs/english-dictionary-pipeline.md`
- **Language system spec**: `docs/specs/dictionary-and-language-system.md`
- **Per-language storage**: `docs/specs/language-specific-dictionary-manager.md`

### Key Directories
- **Assets (shipped)**: `src/main/assets/dictionaries/`
- **Build scripts**: `scripts/`
- **Curated sources**: `scripts/dictionaries/{lang}/`
- **Contraction sources**: `src/main/assets/dictionaries/contractions_*.json`

## Dictionary Files

### What the App Loads at Runtime
| File | Loaded By | Purpose |
|------|-----------|---------|
| `{lang}_enhanced.bin` | `OptimizedVocabulary.loadPrimaryDictionary()` | Main vocabulary (V2 binary) |
| `{lang}_enhanced.json` | `OptimizedVocabulary.loadFromJSON()` | Fallback if binary fails |
| `contractions.bin` | `ContractionManager.loadMappings()` | Fast contraction lookup |
| `contractions_non_paired.json` | `ContractionManager` (JSON fallback) | `dont→don't` mappings |
| `contraction_pairings.json` | `ContractionManager` (JSON fallback) | Possessive + contraction variants |
| `contractions_{lang}.json` | `ContractionManager.loadLanguageContractions()` | Per-language contractions |

### What the App Does NOT Load
| File | Status | Notes |
|------|--------|-------|
| `en_enhanced.txt` | Vestigial | V1 word list, NOT used. Can be deleted. |
| `contraction_pairings_cleaned.json` | Unused | Cleaned subset, never referenced in code |
| `contractions_en.json` | Redundant | Identical to `contractions_non_paired.json` |

## Build Commands

### Rebuild a Dictionary
```bash
cd scripts/

# From wordfreq (any supported language)
python3 get_wordlist.py --lang {code} --output {code}_words.txt --count 50000
python3 build_dictionary.py --lang {code} --input {code}_words.txt --output ../src/main/assets/dictionaries/{code}_enhanced.bin --use-wordfreq

# From curated source (English V3)
python3 build_dictionary.py --lang en --input dictionaries/en/en_words.txt --output ../src/main/assets/dictionaries/en_enhanced.bin --use-wordfreq
```

### Rebuild Contractions
```bash
python3 generate_binary_contractions.py \
  ../src/main/assets/dictionaries/contractions_non_paired.json \
  ../src/main/assets/dictionaries/contraction_pairings.json \
  ../src/main/assets/dictionaries/contractions.bin
```

### Build Language Pack
```bash
# Two-step build from wordfreq
python3 get_wordlist.py --lang sv --output sv_words.txt --count 50000
python3 build_langpack.py --lang sv --name "Swedish" --input sv_words.txt --use-wordfreq --output langpack-sv.zip

# From pre-built binary
python3 build_langpack.py --lang sv --name "Swedish" --dict ../src/main/assets/dictionaries/sv_enhanced.bin --output langpack-sv.zip
```

### Inspect a Binary Dictionary
```python
import struct
with open('en_enhanced.bin', 'rb') as f:
    magic = struct.unpack('<I', f.read(4))[0]    # 0x54444B43 = "CKDT"
    version = struct.unpack('<I', f.read(4))[0]   # 2
    lang = f.read(4).decode().rstrip('\x00')       # "en"
    word_count = struct.unpack('<I', f.read(4))[0] # 52042
    canonical_offset = struct.unpack('<I', f.read(4))[0]
    f.seek(canonical_offset)
    for i in range(word_count):
        wlen = struct.unpack('<H', f.read(2))[0]
        word = f.read(wlen).decode('utf-8')
        rank = struct.unpack('<B', f.read(1))[0]
        # rank 0 = most common, 255 = least common
```

## Contraction System

### How It Works
1. **Load**: `ContractionManager.loadMappings()` loads `contractions.bin` (or JSON fallback)
2. **Transform**: `InputCoordinator.kt:255-259` maps swipe predictions through `getNonPairedMapping()`
3. **Boost**: `SuggestionHandler.kt:1100-1114` adds +1000 score to contraction matches
4. **Per-language**: `loadLanguageContractions(langCode)` adds language-specific mappings (fr, it, etc.)

### Contraction Files Explained
- **`contractions_non_paired.json`** (119 entries): Direct mappings where the apostrophe-free form is NOT a real word. `dont→don't`, `cant→can't`, `im→i'm`
- **`contraction_pairings.json`** (10,637 lines): Mappings where the base IS a real word. `well→we'll`, `shell→she'll`. Includes every possessive (`aaron→aaron's`).
- **`contraction_pairings_cleaned.json`** (345 lines): Real contractions only, no possessives. NOT currently loaded.

### Adding a Contraction
1. Add to `contractions_non_paired.json` (and `contractions_en.json` — they must stay in sync)
2. Rebuild binary: `python3 generate_binary_contractions.py ...`
3. Copy `contractions.bin` to assets

## Modifying the English Dictionary

### Adding Words
1. Add to `scripts/dictionaries/en/en_words.txt` (sorted alphabetically)
2. Rebuild: `python3 build_dictionary.py --lang en --input dictionaries/en/en_words.txt --output ../src/main/assets/dictionaries/en_enhanced.bin --use-wordfreq`
3. Verify word count matches

### Removing Words (Misspellings, Offensive)
1. Remove from `scripts/dictionaries/en/en_words.txt`
2. Rebuild binary (same command as above)
3. Document removals in commit message and spec

### Run Misspelling Detection
```bash
cd scripts/
python3 detect_misspellings.py                    # default: 4+ char, gap >= 1.5
python3 detect_misspellings.py --min-gap 2.0      # stricter (fewer results)
python3 detect_misspellings.py --min-len 3        # include 3-char words
# Output: scripts/misspelling_review.txt
```
Dependencies: `pip install wordfreq pyspellchecker nltk metaphone`
Pipeline: whitelist(NLTK+pyspell+hunspell+British+contractions+possessives) → edit-distance-1 → zipf gap → foreign-language filter.
See `docs/specs/english-dictionary-pipeline.md` "Misspelling Detection Pipeline" section.

## Source Corpora

| Source | File | Words | Format | Quality |
|--------|------|-------|--------|---------|
| Norvig Web Corpus | `en_norvig_50k.txt` | 50k | `word\tfreq` | Contains internet misspellings |
| OpenSubtitles | `en_opensubtitles_50k.txt` | 50k | `word freq` | Contains slang/informal |
| wordfreq library | `en_wordfreq_words.txt` | 25k | `word` per line | Aggregated, most reliable |

## Supported Languages

### Bundled in App
en, es, fr, de, it, pt, sv (dictionaries + prefix boosts)

### Available via `build_all_languages.py`
en, es, fr, pt, it, de, nl, id, ms, tl, sw

### Available via wordfreq (user-buildable)
50+ languages including ar, bg, bn, cs, da, el, fi, he, hi, hu, ja, ko, pl, ro, ru, tr, uk, vi, zh

## Common Pitfalls

1. **`en_enhanced.txt` is NOT the source of truth** — it's vestigial V1. The real source is `scripts/dictionaries/en/en_words.txt` and the `.bin` is what ships.
2. **`build_langpack.py` requires `--input` or `--dict`** — it cannot generate words from nothing.
3. **`contractions_en.json` must match `contractions_non_paired.json`** — they're currently identical and both get loaded (harmless duplication but must stay in sync).
4. **`compute_prefix_boosts.py` looks for `{lang}_enhanced.bin` in `src/main/assets/dictionaries/`** — the dictionary must be in assets before prefix boosts can be generated.
5. **Swedish is NOT in `build_all_languages.py`'s SUPPORTED_LANGUAGES** — must be built manually via `build_langpack.py`.
