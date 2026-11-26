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

`contractions_en.json` IS loaded (`ContractionManager.loadLanguageContractions("en")`, the tap
path's alias set) and is NOT a mirror of `contractions_non_paired.json`: 119 vs 120 keys at HEAD
(2026-10-23). `lets` is only in `contractions_en.json` (tap alias `lets` + space → `let's`; PAIRED
for swipe since 2026-10-07), `etoo` and `high-falutin` only in `non_paired`. See
`contraction-system.md` §6c.7 before touching either.

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
One-letter words survive only by carryover (`len(w)==1` keeps a word only if already shipped), so no non-en dictionary has any (it `è`, fr `à`, es `y`); adding them is a maintainer decision (it attempt failed the tap gate, `docs/eval/2026-11-19-italian-accent-spellings.md`). Curation files accept accented Latin since 2026-11-19 and refuse a malformed line (they used to skip it silently).
Curation: `scripts/dictionaries/<lang>/<lang>_allowlist.txt` (force-keep,
delete-to-exclude) and `<lang>_blocklist.txt` (force-drop; en's includes the
AutocorrectTest inputs tge/broight/questin/… which must NEVER become dict
words). Bands: rank<band conservative, band..top requires a positive oracle.
Guards hard-fail the build. Held-out eval: `en_user_export_eval.txt`
(gitignored — personal vocabulary).
Gotchas discovered 2026-07-20 (encoded in the script):
- wordfreq CASEFOLDS: German ß→ss. Since 2026-11-25 `build_wordlist.py` respells the de stream
  with `scripts/sharp_s.py` (`LangConfig.sharp_s` = the de_DE_frami oracle, pinned in
  `corpus_blend.FILE_HUNSPELL`, plus the de LM's Leipzig counts; split pairs carry a frequency
  column in `de_words.txt`); review artifact `cleverkeys-dictgen-de-sharp-s.txt`. The tap eval's
  population grows with a lexicon change: compare pairs with `STATIC_LM_EVAL_TARGETS_IN`. And Greek final
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
2. **Decode**: CTC and geometric decode letter-only surfaces, then apply their canonical display overlays.
3. **Present**: `SuggestionHandler` preserves paired collisions and confidence-sensitive possessive ordering; there is no unconditional +1000 swipe contraction boost. See `contraction-system.md` before modifying these rules.
4. **Per-language**: `loadLanguageContractions(langCode)` adds language-specific mappings (fr, it, etc.)

### Contraction Files Explained
- **`contractions_non_paired.json`** (119 entries): Direct mappings where the apostrophe-free form is NOT a real word. `dont→don't`, `cant→can't`, `im→i'm`
- **`contraction_pairings.json`** (10,637 lines): Mappings where the base IS a real word. `well→we'll`, `shell→she'll`. Includes every possessive (`aaron→aaron's`).
- ~~**`contraction_pairings_cleaned.json`**~~: real contractions only, no possessives. Never loaded; DELETED (`030265ee`).

### Adding a Contraction
1. Add to `contractions_non_paired.json` (swipe REPLACE set). Add it to `contractions_en.json`
   too only when the tap path should alias it; the two files differ by design (Pitfall 3)
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

## Runtime user-dictionary policies (pointers, October 2026)

The built dictionaries are half the story; the per-user layer has its own rules, each with a
pure test. Read these before touching custom-word frequency, swipe ranking of user words or the
add-to-dictionary offers:

- **Swipe priority** (Normal / High / Highest per custom word, `b2031f90`): a fixed CTC and
  geometric bonus for words the swipe already passed close to; tap prediction untouched. Costs
  per level in `docs/eval/2026-10-08-user-swipe-priority.md`; user docs
  `docs/wiki/typing/user-dictionary.md`.
- **Legacy frequency 100** (`051dca51`, `8f4bdf2b`): every pre-2.0 writer stored custom words at
  100 (the default is 255 since `4525eb9c`). `LegacyCustomWordFrequency` (pure) drives the
  Custom-tab offer, `CustomDictionarySource.raiseLegacyFrequencies` raises only words still at
  100, the dismissal is the internal per-language pref `legacy_custom_freq_offer_dismissed`
  (never exported), and `UserWordFrequency.liftedForPriority` / `restoredAfterLift` handle the
  add/edit dialog's lift to 255 on High/Highest and its restore on Normal.
  `DictImportPlanBuilder` still gives 100 to a backup entry that carries no frequency, which is
  why the offer can return after an import.
- **No dictionary offers in terminal or blind fields** (`fd3eec40`): the exact-add chip and the
  "Add to dictionary?" prompt are suppressed wherever the tracked word cannot be verified
  (`TerminalUtils.editorKind` ≠ TEXT). Termux offered `+foocbar` after a toolbar Ctrl+C.
- **Two frequency scales** (`ee32d912`; CLAUDE.md "tap predictor and swipe lexicon use different
  frequency scales"): the tap predictor loads `en_enhanced.bin` (rank byte, 0 = most common);
  `en_enhanced.json` is the CTC swipe lexicon on a compressed 134..255 scale, and 98,139 of
  98,140 words differ between them (`help`/`hello` are 39 ranks apart, not 20). Any JVM tap
  harness must load the `.bin` through `ShippedTapDictionary`; `ShippedTapDictionaryDriftTest`
  pins fixture == production load and forbids a tap harness built from the JSON. The autocorrect
  constant `DROPPED_DOUBLE_MARGIN_STEPS = 64` (DD-M) was tuned on the `.bin` scale — an M
  measured on the JSON (the first M=32) kept `helo` → `help` on phones.
- **Stored casing of a keyboard-added word** (2026-11-25): every IME add path (prompt, "+word",
  autocorrect undo, Prefer offer) stores `SuggestionHandler.userWordStorageForm(word)`, which is
  `UserWordCasing.storageForm`: a word whose only capital is its first letter, sitting where
  auto-capitalisation capitalises (a port of `TextUtils.getCapsMode` over the editor text read
  after the commit; setting on and a CAP_SENTENCES / CAP_WORDS field), is stored lowercase. The
  stored casing is restored on every prediction (`WordPredictor.applyUserWordCase`, #72), which
  is why `Parcero` typed at a field start came out capitalised mid-sentence. A sentence-start
  name or German noun is stored lowercase too (Shift cannot be attributed there); the bar's
  `shouldCapitalize` and the swipe commit's auto-cap still capitalise it at a sentence start.
  The confirmation and its undo use the stored form. Pinned by `DictionaryAddUndoTest`.

## Common Pitfalls

1. **`en_enhanced.txt` is NOT the source of truth** — it's vestigial V1. The real source is `scripts/dictionaries/en/en_words.txt` and the `.bin` is what ships.
2. **`build_langpack.py` requires `--input` or `--dict`** — it cannot generate words from nothing.
3. **`contractions_en.json` and `contractions_non_paired.json` are NOT mirrors** (119 vs 120 keys, 2026-10-23). `en.json` keeps the tap alias `lets` on purpose (`contraction-system.md` §6c.7); `non_paired` carries `etoo` and `high-falutin`. Both load; "sync them" would re-break `lets`.
4. **Prefix boosts are DEAD — do not regenerate them.** Their only consumer (the neural beam search) was deleted with the neural engine on 2026-08-18 (ADR-011); `src/main/assets/prefix_boosts/` no longer exists, and every `boost` flag in `build_all_languages.py` is `False` (2026-09-03) so a bulk rebuild cannot resurrect the tree. `LanguagePackManager` still ACCEPTS a pack-side `prefix_boost.bin` and copies it on import (so old packs install cleanly) but nothing reads it back.
5. **`build_all_languages.py` refuses to regenerate en in bulk runs** — the English dict is frozen at 98,140 words; rebuild it only deliberately via `build_wordlist.py --lang en --write`.

## Accent misspellings: audit every accented Latin lexicon after a rebuild (2026-11-19/20)

wordfreq counts unaccented web typing (`francais`, `tambien`, `nao`, `perche`) as words, and the
classifier can keep them beside the accented form. Since the CTC collision alternates (2026-11-18)
a swipe offers every form of a surface, so such a spelling shows up in the slate. it, fr, es, pt and
de were cleaned (`docs/eval/2026-11-19-italian-accent-spellings.md`,
`docs/eval/2026-11-20-accent-spellings.md`), sv on 2026-11-22
(`docs/eval/2026-11-22-swedish-accent-spellings.md`: 89 candidates, 33 dropped).

- Find candidates: `python3 scripts/accent_twin_audit.py --words scripts/dictionaries/<l>/<l>_words.txt
  --oracle <hunspell dict path> [--oracle ...] --out <tsv>`. Rejected lowercase AND Capitalised
  while a group twin is accepted (keeps names and German nouns out). Review by hand; the reviewed
  drops go to `<l>_blocklist.txt` as `word  # twin[/twin]`; pinned by `AccentSpellingDriftTest`.
- Oracles that worked: fr = Grammalecte **"toutes variantes"** (Debian `hunspell-fr-comprehensive`;
  Termux's `fr_FR` is "classique" and rejects the 1990 reform `apparaitre`/`aout`); es = es_ES +
  es_MX + **es_AR** (voseo); pt = pt_BR + pt_PT; de = de_DE_frami (LibreOffice
  `raw.githubusercontent.com/LibreOffice/dictionaries/master/<dir>/…`; sv under `sv_SE/dictionaries/`).
  The script exits non-zero when hunspell accepts nothing (a 404 page saved as `.dic`).
- Kept on review, every language: real colliding words (the oracle accepts them), English words the
  text uses as they are, abbreviations, compound fragments, names spelled their owner's way, and
  spellings that were standard before a reform (fr 1990, es pre-2010 `guión`, pt pre-2009 `idéia`).
- When a candidate is also an English word, measure instead of guessing (sv 2026-11-22): with
  wordfreq, estimate the share of its frequency that is the twin typed in ASCII (twin zipf minus the
  language's median gap for plain misspellings, sv −2.55) and the share that is English (en zipf
  minus the median en→target gap, sv −2.33 for function words, −1.26 for content words). Drop at
  ≥ 10% ASCII share (sv `over` 35%), keep when English dominates and the ASCII share is ~0
  (`are`, `lost`). Look up colloquial forms before keeping them (sv `äre`, `pöjk`, `gö`), and
  remember agency names and acronyms (sv `Brå` explains a zipf-4 `brå`).
- Build in a scratch copy (`git archive HEAD scripts src/main/assets/dictionaries src/main/assets/lm`),
  never the shared tree. `build_wordlist.py` needs the AOSP snapshot, removed from the tree for
  F-Droid: fetch `…/LatinIME/+/refs/heads/main/dictionaries/<code>_wordlist.combined.gz?format=TEXT`,
  base64 → gunzip → `word\tflags` rows SORTED, `gzip.compress(body, 9, mtime=0)`; it must match
  PROVENANCE.md's sha256. Traps: fr marks entries `not_a_word=true` (no `flags=`) → flag `nonword`;
  pt = pt_BR ∪ pt_PT with **BR flags winning**. Reproduce the shipped bin from HEAD inputs first; a
  difference is input drift (fr: contraction keys added after the last build) to explain, not ignore.
- Then re-run the audit on the rebuilt list (the size cap admits new words), regenerate elisions
  (`extract_apostrophe_words.py --lang`) and `build_contraction_collisions.py`, rebuild
  `langpack-<l>.zip` (`build_all_languages.py --lang <l>`), and rebuild `<l>.cklm` only if it names a
  dropped spelling, under a gate pre-registered in its `LangConfig` (the Italian G1–G6). Lexicon
  shapes are pinned in `CtcCkdtLexiconTest` and `CtcMultiLanguageInstrumentedTest.SHAPES`.
- G4a (`STATIC_LM_MODEL_DIR=<dir> STATIC_LM_EVAL_LANGS=<l> … StaticLmTapEvalTest -PgeoFull=true`):
  the dir needs the shipped `<l>.json` sidecar beside `<l>.cklm` (`git show HEAD:src/main/assets/lm/<l>.json`),
  or the test dies with FileNotFoundException in `StaticLmLanguageData.sidecar`.

## German ß and Swiss spelling (2026-11-25)

- The assets spell Germany's way (`straße`, `groß`; `scripts/sharp_s.py`). Do NOT build a second
  German list for Switzerland: `pref_german_swiss_spelling` respells every German lexicon at LOAD
  (`SwissSpelling`: tap map + prefix index, secondary index, CTC base pairs, geometric word array;
  next-word through lookup aliases). Spec: `docs/specs/dictionary-and-language-system.md`
  § Swiss German spelling.
- A new loader of a German word list must apply the fold too, BEFORE it merges the user's words,
  and a memoised one must key on `SwissSpelling.lexiconVariant` (`SwissSpellingWiringDriftTest`).
- After a German rebuild, `SwissSpellingFoldTest` re-reads `de_enhanced.bin` and `de.cklm` with the
  fold on: the a–z surfaces must not change, no display form may keep a ß.

## Arabic-script lookup fold and the Persian half-space (2026-10-23)

- `ArabicScriptFold` folds LOOKUP KEYS only (dictionary keys at load, the typed word at
  lookup; the user's text is never rewritten): `fa` ي ك ى → ی ک ی and ۀ ة ە → ه; `ckb` only
  ي ك → ی ک (ە is a Sorani letter); every other language nothing. Never آ or a hamza carrier.
  A pack builder for `fa` must apply the same table (`ArabicScriptFold.PERSIAN`).
- Dictionary words may contain U+200C between Arabic letters (`می‌شود`): they are single words.
  Autocorrect never adds, removes or moves a half-space; the other spelling is offered in the
  bar instead (`HalfSpaceVariants`). Details: `docs/specs/dictionary-and-language-system.md`.


## Indic spelling marks, Bengali ZWJ and the danda (2026-11-04 / 2026-11-06)

- An Indic vowel sign or virama on a letter of its script is a WORD CHARACTER (`WordChars`:
  Bengali, Devanagari, Gujarati, Kannada, Tamil, Sinhala — every script with a shipped layout).
  Dictionary keys for these scripts keep their marks (`build_dictionary.py --keep-script-marks`).
  A ZWJ inside a Bengali, Sinhala, Kannada or Devanagari word (`র‍্যাব`, `ශ්‍රී`, `दर्‍या`) is a word character too.
- `WordChars.isSentenceEnd` (`. ? ! । ॥`) is the one sentence-end set for next-word context and
  learning; `WordChars.isSpelledWithLetters` is the fuzzy-search / dropped-double "all letters"
  gate (identical to `all { isLetter() }` outside Indic marks and Bengali ZWJ).
  Details: `docs/specs/dictionary-and-language-system.md` § Indic spelling marks.

<!-- moved verbatim from CLAUDE.md on 2026-11-14 -->
## Tap predictor vs swipe lexicon frequency scales

The tap predictor (autocorrect, suggestion bar) loads `en_enhanced.bin` (rank 0..255, full
spread); the CTC swipe lexicon uses `en_enhanced.json` (compressed 134..255). They are NOT
`byte = 255 − rank`: 98,139/98,140 words differ (help 180 vs 218). Autocorrect harnesses that
read the JSON measured the wrong dictionary; DD-M's M=32 failed on the Pixel (2026-10-12).
