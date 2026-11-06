# Kit 19: Bangla spelling-preserving dictionary, then geometry, then a model and pack

Todo item 19. Background is in `docs/guides/adding-a-new-language.md` §"Bangla path and current
limitations" (stages 1–3). The maintainer asked: *"can you use the bangla dictionary from a
subset of ../wmkeyboard?"*

**Short answer: yes, under conditions.**

* The usable subset is 20,627 words. Of these, 670 are WM Keyboard's own MIT seed and hand
  additions, and 19,957 are its selection from FrequencyWords 2018 bn.
* It must ship as CC BY-SA 4.0-derived data with both attributions, the same regime as
  `langpack-en-opensubtitles*.zip`.
* The 120 chat verb forms are left out: their filter corpus has no stated origin.
* CleverKeys' builder destroyed the spelling by default. The new opt-in `--keep-script-marks`
  fixes that, and verification is then clean.
* What is ready is **stage 1, the dictionary**. The RTX step (stage 3, a CTC model) is **not
  ready to run**. A geometry decision blocks it first (§5).

## 1. bn.txt facts (WM Keyboard `app/dictionaries-src/bn.txt`)

| fact | value |
|---|---|
| repo / commit | github.com/wasi-master/wmkeyboard; file last changed `a19072cf7d871986d2c7aa123f79367a756a817d` (checkout HEAD `ff324d30`) |
| size / sha256 | 491,228 B, 20,772 lines; `145c1f751f8244ed9f87b41a3be815c4a3a188ca3fe5ba6f78742690b31d6df9` |
| format | `word<space>integer` per line; `#` comments mark four blocks; no duplicates (20,765 distinct entries) |
| blocks | **seed** 640 (hand frequencies 120–9000) · **hand additions** 30 · **"Corpus expansion (OpenSubtitles 2018 frequency list), log-scaled 2600..1"** 19,975 · **chat verb forms** 120 (`150·ln(1+uses)`) |
| frequency info | relative ranks on three hand-made scales, not counts. The kit uses FrequencyWords' own counts instead (§2) |
| Unicode normalisation | 20,694 NFC, 71 not NFC: precomposed nukta letters U+09DC/09DD/09DF are composition exclusions, so NFC decomposes them. 2,560 words already use the decomposed nukta. **Mixed forms, so normalise to NFC.** |
| hasanta (virama U+09CD) | preserved: 8,868 viramas in 7,276 words (31 word-final). Conjuncts are spelled with the virama, as Unicode intends |
| khanda ta | written as U+09CE ৎ (69 words); no `ত্‍` ZWJ form |
| ZWJ / ZWNJ | **0 anywhere.** Spellings that need ZWJ, such as ra-phala after ra (`র‍্যা…`), are missing. Upstream tokenisation split them, leaving 18 fragments that start with `্য` (`্যাচেল` from `র‍্যাচেল`). The kit rejects those |
| quality | the OpenSubtitles block has subtitle-typo spellings that pass structural checks (`কিন্তুু` with a doubled u-sign beside `কিন্তু`). Native review is still a release gate (guide §Bangla) |

### Provenance and licence verdict

* **Seed + hand additions (670 words):** written by Wasi Master. They are covered by the repo's
  MIT licence (Copyright (c) 2026 Wasi Master). **Usable** with the MIT notice.
* **OpenSubtitles block (19,975 words):** every entry, after NFC, is a word of
  `hermitdave/FrequencyWords` `content/2018/bn/bn_50k.txt`. That file is at commit `cdd98029`
  (2019-02-13), sha256 `2fd6c08f…636e`, 50,000 lines; the block uses ranks up to 49,997.
  FrequencyWords' licence is *"MIT License for code. CC-by-sa-4.0 for content."* So this block
  is CC BY-SA 4.0 content. **WM Keyboard's MIT LICENSE cannot relicense it**, and nothing in
  bn.txt says so. This is exactly the MIT-repo-containing-CC-BY-SA case. WM Keyboard's About
  screen credits "FrequencyWords (OpenSubtitles)" only generally.
  * **Usable under CC BY-SA 4.0** with attribution to FrequencyWords, OPUS/OpenSubtitles and WM
    Keyboard's selection. CC BY-SA 4.0 adaptations may be distributed under GPLv3 (NOTICE
    "Word frequencies — overview"), exactly as `langpack-en-opensubtitles*.zip` already is.
* **Chat verb forms (120):** generated from the list's own roots. They were kept only where
  spellings in *"the romanized chat corpus"* reach them, and WM Keyboard's docs say only "a
  corpus of real Bangla chat". **Excluded:** the filter corpus's origin and licence are unstated.

## 2. The subset (stage 1): built and verified on 2026-11-04

```bash
# inputs (pinned; the script refuses any other bytes)
#   ../wmkeyboard/app/dictionaries-src/bn.txt                        sha256 145c1f75…6df9
#   https://raw.githubusercontent.com/hermitdave/FrequencyWords/
#       cdd980291e6cd9ab0e5b3b78b0fe1d1f64385c23/content/2018/bn/bn_50k.txt  sha256 2fd6c08f…636e
OUT=<scratch dir, never src/main/assets>
python3 ml/rtx-kits/19-bangla-pack/build_bn_subset.py --bn ../wmkeyboard/app/dictionaries-src/bn.txt \
  --freqwords bn_50k.txt --out $OUT
python3 scripts/build_dictionary.py --lang bn --keep-script-marks \
  --input $OUT/bn_wordlist.tsv --output $OUT/dictionary.bin
python3 ml/rtx-kits/19-bangla-pack/verify_bn_dict.py --dict $OUT/dictionary.bin --wordlist $OUT/bn_wordlist.tsv
head -5000 $OUT/bn_wordlist.tsv | cut -f1 > $OUT/unigrams.txt
python3 scripts/build_langpack.py --lang bn --name "Bangla" --output $OUT/langpack-bn.zip \
  --dict $OUT/dictionary.bin --unigrams $OUT/unigrams.txt \
  --data-source frequencywords --data-source wmkeyboard
```

**Measured results (phone, 2026-11-04):**

* **Subset:** 20,627 words: seed 640, hand 30, opensub 19,957.
* **Rejected:** 120 chat forms and 18 fragments that start with a sign. 59 words were changed by
  NFC. 14 seed/hand words are absent from bn_50k, and their count is floored to 2.
  `bn_wordlist.tsv` sha256 `71d73326…fef89`.
* **The existing builder, default mode, is NOT spelling-preserving.** `build_dictionary.py`
  strips every category-Mn code point from lookup keys. On Bangla that is the hasanta, the
  u/uu/vocalic-r signs, nukta and candrabindu. The runtime `AccentNormalizer` strips only
  U+0300–U+036F. Results:
  * 11,416 of 20,627 keys lost marks, so no runtime lookup can match them;
  * 556 conflation groups, 1,181 words: `করে/কুরে/ক্রে`, `তার/তাঁর`, `ফল/ফুল/ফ্লু`,
    `কিন্ত/কিন্তু`.
* **With `--keep-script-marks`** (new, opt-in, default off): the builder strips exactly the
  runtime's mark set.
  * Round trip: 0 missing.
  * Key parity with the runtime: 0 mismatches.
  * Lost marks: 0. Conflations: 0.
  * VERDICT: spelling-preserving. `dictionary.bin` sha256 `118f326e…90e2`, 20,627 canonical /
    20,627 keys.
  * Without the flag, the output is byte-identical to before (checked on this input). Every
    existing pack therefore builds unchanged.
* **Scratch pack** `langpack-bn.zip` (never published): 4 members. Its manifest licence is
  GPL-3.0-only and the attribution credits FrequencyWords, OpenSubtitles and WM Keyboard. Its
  NOTICE.txt carries the full FrequencyWords block and the full WM Keyboard MIT text (new
  `wmkeyboard` data source in `scripts/build_langpack.py`). The build is deterministic apart from
  the file name printed in NOTICE.txt.

**To publish (maintainer approval required; langpacks release):** add a row to the top-level
NOTICE "Word frequencies — overview" table:
`langpack-bn.zip   FrequencyWords (OpenSubtitles) + WM Keyboard seed words (MIT)`.
Also add the WM Keyboard MIT block, and complete the guide §6 checklist.

## 3. Stage-1 blockers that remain in the APP (not done here)

* **Word boundaries: FIXED 2026-11-04 (maintainer approved).** `WordChars` now treats a Bengali
  combining mark (vowel sign, hasanta, nukta, candrabindu, anusvara, visarga) on a Bengali letter
  as a word character (`isAttachedSpellingMarkAt`, `isSpellingMarkWordCharAfterCursor`), following
  WM Keyboard's rule (letters, digits and combining marks are one word). Wired into the typing
  path (`SuggestionHandler.handleRegularTyping`: `আম` + `ি` composes `আমি`), the editor partial
  scan, `editorEndsWithWholeToken`, `TextActionPolicy.wordAtCursor`, the next-word tokenizer,
  `isWordCharAt` (emoji search, swipe-correction joining, double-space period) and
  `UserJoinerPreference.joinerFree`. Cursor sync, `EditorTextNavigation` and
  `VerifiedSuffixEdit` were already mark-aware. Tests: `BanglaSpellingMarkBoundaryTest` (pure),
  `BanglaSpellingMarkTypingTest` (mock, real handler + predictor over `bn` keys).
  Remainder DONE 2026-11-06 (maintainer asked to finish it; WM Keyboard read at `18f277375`):
  * DONE: the danda `।` (and the double danda `॥`) is a sentence boundary for next-word context
    AND learning, through ONE predicate `WordChars.isSentenceEnd`
    (`NextWordPredictor.contextFromEditorText` and `SuggestionHandler` → `onSentenceBoundary`),
    matching WM Keyboard's `SENTENCE_ENDERS` (`. ! ? ।`). Autocapitalisation is unchanged
    (no letter case in the Indic scripts);
  * DONE: typo-tolerant prefix search and the step-1.45 dropped-double gate accept attached
    marks (`WordChars.isSpelledWithLetters`), and step 1.4 collapses a doubled sign (`আমিি` →
    `আমি`); a one-key typo of `কুকুর` gets a fuzzy suggestion (mock test). TODO: a doubled
    consonant is a conjunct in Bangla (`ক্ক`), which step 1.45 does not model;
  * DONE: the double-space period fires after a word that ends in a mark (`আমি` + two spaces →
    `আমি. `). It inserts `. `, not `।`: WM Keyboard's `doubleSpacePeriod` rule also commits
    `. ` in every script;
  * DONE: a ZWJ inside a Bengali word (`র‍্যাব`) is a word character (strict both-sides rule,
    cursor-edge rule for a just-typed ZWJ, Bengali only), wired into every mark call site plus
    `EditorTextNavigation`, `VerifiedSuffixEdit` and `UserJoinerPreference.joinerFree`. Sinhala
    and Kannada joined the ZWJ rule the same day (both layouts carry a ZWJ key; Unicode spells
    the rakaransaya/yansaya as virama + ZWJ, `ශ්‍රී`); Devanagari's eyelash ra stays a TODO;
  * DONE: the danda and double danda pull back over an automatic space (`আমি ` + `।` →
    `আমি।`, `SmartAutoSpace` closing set), as WM Keyboard's auto-space does;
  * DONE: Devanagari, Gujarati, Kannada, Tamil and Sinhala (every Indic script with a shipped
    layout) are in `WordChars.SPELLING_MARK_SCRIPTS`, one real word each in
    `IndicSpellingMarkBoundaryTest`. No Gurmukhi/Oriya/Telugu/Malayalam layout ships.
* `build_wordlist.py`'s evidence classifier rejects marked words (`isalpha()`) and has no `bn`
  row. This kit bypasses the classifier, which is a deliberate difference from the standard
  pipeline, stated here. A `bn` classifier row needs a Bengali-script validator.
  `build_bn_subset.reject_reason` is the structural part of one.
* Native-speaker sentinels and round-trip/prefix fixtures (guide §Bangla stage 1).

## 4. ARC-061 (`ctc_golden` fixture)

Fixed ML-side in CleverKeys-ML `7343355`: `make_golden.py` emits a basename-only `source_onnx`.
The app's `ctc_golden.json` and the five `*_synth_v3_ch80_fp16w_golden.json` still embed
absolute `/home/will/...` paths. They can change only when each is regenerated (the byte-identity
and sha-tie rule), so nothing in this kit edits them.

* A future Bangla fixture is born clean.
* The English one is regenerated by kit 17's success path.
* The others would be regenerated with their next model change.

## 5. Stage 2/3 (geometry, then the RTX model): blocked on a layout decision

CleverKeys-ML's `script_registry.py` invariant 1 says *the swipe alphabet is the layout's
CENTRE-key letters*. Corner (flick) letters are excluded, and words needing them are rejected.
Applied to the two bundled layouts and this subset:

| layout | centre code points | words typeable from centre keys only | token mass | most-needed missing |
|---|---|---|---|---|
| `beng_national.xml` | 24 | 6,431 (31.2 %) | 39.7 % | ল, ই, ছ, শ, ভ, ী, আ, খ |
| `beng_provat.xml` | 27 | 3,536 (17.1 %) | 32.8 % | ে, ্ (hasanta), ই, ছ, ভ, খ, অ, ফ |

A Bangla CTC model on either layout would therefore reject 60–83 % of the vocabulary. That is
before the open questions of how marks and conjuncts project: guide stage 2, `LayoutProjection`
dropping Mn, and Mc signs on gesture nodes. **Decisions needed before any RTX training:**

1. **Layout.** Choose one of:
   * a swipe-capable Bangla layout, with every letter and sign used in swiping on a centre key;
   * or a defined corner-letter projection rule that the encoder can separate.
2. **Token inventory.** Which code points are CTC classes: independent vowels, consonants,
   vowel signs, hasanta, nukta, candrabindu and anusvara. Also decide whether a conjunct is
   emitted as C+virama+C.
3. **Training data.** There is no Bangla human swipe corpus. FUTO has none, and none is on this
   machine. A model would be synthesis-trained (`script_synth.py` / `synth_v3.py`, as for
   ru/el/uk/bg/mk/he) and would enter at the `PROVISIONAL` tier. Real held-out Bangla traces
   would have to be collected (app Swipe Playground export, §3 of kit 17) before any accuracy
   claim.

Once 1–3 are decided, the RTX run follows the existing multi-script recipe (`PHASE_O`/`PHASE_Q`
in CleverKeys-ML): add a `ScriptSpec(code="bn", …)` with this kit's `bn_wordlist.tsv` as its
lexicon on the CKDT `255 − rank` scale, generate synth, train ch80, produce the fp16w export and
`make_golden.py`, then add the app `CtcScriptSupport` row with a sha pin. That kit cannot be
written meaningfully before the layout decision (TODO, maintainer).

## 6. What to send back / decide

* A decision on §5 items 1–2.
* ~~Approval for the §3 WordChars change.~~ Approved and done 2026-11-04.
* Approval to publish `langpack-bn.zip` once the §3 blockers are closed.
