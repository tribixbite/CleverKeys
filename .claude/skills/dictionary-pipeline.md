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
(`OptimizedVocabulary` was DELETED with the neural engine, 2026-08-18/ADR-011 — do not cite it.)
| File | Loaded By | Purpose |
|------|-----------|---------|
| `{lang}_enhanced.bin` | `WordPredictor` + `DictionaryDataSource`, both via `BinaryDictionaryLoader` (langpack `files/langpacks/{code}/dictionary.bin` first, bundled asset second); the swipe engines parse CKDT via `swipe/geometric/CkdtDictionaryReader` (`CtcCkdtLexicon`, geometric adapter, installed-pack measurement) | Main vocabulary (CKDT V2 binary) |
| `{lang}_enhanced.json` | `WordPredictor` / `DictionaryDataSource` JSON fallback (then `.txt` as last resort) | Fallback if binary fails; also the CTC en lexicon (`EN_JSON` source) |
| `contractions.bin` | `ContractionManager.loadMappings()` | Fast contraction lookup |
| `contractions_non_paired.json` | `ContractionManager` (JSON fallback) | `dont→don't` mappings |
| `contraction_pairings.json` | `ContractionManager` (JSON fallback) | Possessive + contraction variants |
| `contractions_{lang}.json` | `ContractionManager.loadLanguageContractions()` | Per-language contractions |

### What the App Does NOT Load
| File | Status | Notes |
|------|--------|-------|
| `en_enhanced.txt` | Deleted | V1 word list. DELETED 2026-07-03 (was never loaded at runtime). |
| ~~`contraction_pairings_cleaned.json`~~ | Deleted | Cleaned subset, never referenced in code. DELETED (`030265ee`); absence re-confirmed 2026-09-01. |
| `contractions_en.json` | Redundant | Identical to `contractions_non_paired.json` |

## Build Commands

### All languages — one-pass evidence classifier (`build_wordlist.py`, 2026-07-20)
`scripts/build_en_wordlist.py` was RENAMED + generalized to
`scripts/build_wordlist.py` (`--lang <code>`, default en; `--lang en` is
bit-identical to the old script). Per-language knobs live in its `LANG_CONFIG`
table: script gate (latin/greek/cyrillic/hebrew — hebrew added by ARC-056,
`538a1633`), ed1 alphabet, oracle set, case policy (`en` / `de_nouns` /
`plain`), band boundaries, `--limit` size cap, carryover basis, MUST_INCLUDE
guards.
```bash
# Report mode (no files touched): classification counts + review artifacts
python3 scripts/build_wordlist.py --lang fr
# Regenerate <lang>_words.txt + <lang>_enhanced.bin (assets copy for bundled langs) + verify
python3 scripts/build_wordlist.py --lang fr --write
# EN extras: --eval / --eval-blind (held-out user-export coverage; EN-only data)
python3 scripts/build_wordlist.py --eval-blind
# First build of a NEVER-SHIPPED language: --bootstrap permits a missing
# carryover basis (normally a hard failure — a lost basis is a regression,
# not a bootstrap). Used for the ARC-056 uk/bg/mk/he first builds.
python3 scripts/build_wordlist.py --lang uk --write --bootstrap
# Full per-language pipeline (classifier → unigrams → langpack; the prefix-boost
# step is disabled for every language — see Pitfalls):
python3 scripts/build_all_languages.py --lang fr,de
```
Oracle tiers (configured-but-missing oracle = hard build failure):
- **Tier A** (spellcheckers + AOSP): en (hunspell en_US ×3 case forms + aspell
  en_GB + NLTK + pyspell), fr (hunspell fr_FR + aspell fr + pyspell), de
  (aspell de + pyspell, `case_policy=de_nouns` — cap-acceptance IS
  spell-validity), es (aspell es + pyspell), nl (hunspell nl_NL + pyspell),
  ru (hunspell ru_RU + pyspell)
- **Tier B** (pyspellchecker + AOSP): it, pt
- **Tier C** (AOSP-only band-2 oracle): sv, el, tr, he (sole oracle: AOSP
  LatinIME `iw` snapshot; no hunspell/aspell/pyspell he data exists here)
- **Tier D** (no oracles, band == top, negatives-only): id, ms, tl, plus
  uk/bg/mk — Tier D by circumstance, not design (probed 2026-09-01: no AOSP
  dict upstream, no packaged hunspell/aspell, no pyspellchecker data; the
  cross-Cyrillic foreign-dominance filter is the load-bearing negative)
- **sw**: NOT ported (no wordfreq data) — corpus-file pipeline via
  `scripts/sw_words.txt`, orchestrated by `build_all_languages.py`.
EN-only elements (documented omissions elsewhere): BRITISH_RULES, NLTK,
VALID_SEED_KEEP, held-out eval, flat-JSON asset fallback.
Case forms: UPPER−Cap−lower = true initialisms (raw UPPER acceptance leaks
every proper noun); `plain` languages use Cap−lower as name-rescue evidence.
Curation: `scripts/dictionaries/<lang>/<lang>_allowlist.txt` (force-keep,
delete-to-exclude) and `<lang>_blocklist.txt` (force-drop; en's includes the
AutocorrectTest inputs tge/broight/questin/… which must NEVER become dict
words). Bands: rank<band conservative, band..top requires a positive oracle.
Guards hard-fail the build. Held-out eval: `en_user_export_eval.txt`
(gitignored — personal vocabulary).
Gotchas discovered 2026-07-20 (encoded in the script):
- wordfreq CASEFOLDS: German ß→ss (dict ships strasse/grösse) and Greek final
  sigma ς→σ (whole el stream + shipped pack are σ-final; the AOSP el oracle is
  remapped to match — see `load_aosp`).
- fr/it contraction maps are ~22–26k elision-expansion tables → demoted to
  positive-evidence-only (`FUNC_FORCEKEEP_MAX`); small maps (en/nl/de) still
  force-keep.
- `build_dictionary.py`'s junk blocklist is ENGLISH-ONLY ("hav" is Swedish
  'sea', "teh" is Indonesian 'tea') — gated on `--lang en`.

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
    word_count = struct.unpack('<I', f.read(4))[0] # 98140
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
- ~~**`contraction_pairings_cleaned.json`**~~: real contractions only, no possessives. Never loaded; DELETED (`030265ee`).

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
en (98,140), es (50k), fr/de/it/pt/sv (40k each) — dictionaries only (prefix
boosts are dead; see Pitfalls). Note: 98,140 is the dictionary record count
(all distinct, verified); the CTC a–z trie holds 98,122 distinct SURFACES
after the strip-policy dedupe (`CtcBundledLexiconEmitBudgetTest`) — different
numbers measuring different things, both correct.

### Langpacks via `build_all_languages.py` (scripts/dictionaries/langpack-*.zip)
es fr de it pt sv (40–50k) · nl tr (40k) · ru (50k) · el (~39.9k survivors)
· id/ms/tl (~26–29k survivors) · sw (20k, corpus list)
· ARC-056 (2026-09-01): uk (50k) · bg (~35.0k survivors) · mk (50k) · he (50k)

### Available via wordfreq (user-buildable, add a LANG_CONFIG entry)
50+ languages including ar, bn, cs, da, fi, hi, hu, ja, ko, pl, ro, vi, zh
(bg/he/uk graduated to configured languages with ARC-056)

## Common Pitfalls

1. **`en_enhanced.txt` is NOT the source of truth** — it's vestigial V1. The real source is `scripts/dictionaries/en/en_words.txt` and the `.bin` is what ships.
2. **`build_langpack.py` requires `--input` or `--dict`** — it cannot generate words from nothing.
3. **`contractions_en.json` must match `contractions_non_paired.json`** — they're currently identical and both get loaded (harmless duplication but must stay in sync).
4. **Prefix boosts are DEAD — do not regenerate them.** Their only consumer (the neural beam search) was deleted with the neural engine on 2026-08-18 (ADR-011); `src/main/assets/prefix_boosts/` no longer exists, and every `boost` flag in `build_all_languages.py` is `False` (2026-09-03) so a bulk rebuild cannot resurrect the tree. `LanguagePackManager` still ACCEPTS a pack-side `prefix_boost.bin` and copies it on import (so old packs install cleanly) but nothing reads it back.
5. **`build_all_languages.py` refuses to regenerate en in bulk runs** — the English dict is frozen at 98,140 words; rebuild it only deliberately via `build_wordlist.py --lang en --write`.
