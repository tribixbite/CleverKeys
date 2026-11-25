# English contraction aliases at the contraction's own frequency (2026-11-25)

Todo item 8: `they're`/`you're` lose to `there`/`your` on a swipe. The maintainer pointed out
(2026-11-25) that the FUTO corpus does hold these words, written without the apostrophe
(`theyre`, `Youre`, `Ive`): a case-insensitive count found 73 `youre`, 42 `theyre`, 61 `ive`,
214 `dont` across swipe-1/2/3 (the earlier "none" came from an exact, apostrophe-only match).

## Measured on 1,202 human traces (shipped decode, `short_word_ctc_eval.py dump --emu smooth`)

| target | top-1 today | top-3 | lost mostly to |
|---|---|---|---|
| `you're` | 17 / 66 | 54 | `your` 43 |
| `they're` | 4 / 39 | 30 | `there` 22, `their` 10 |
| `I've` | 36 / 61 | 52 | `ice` 11, `one` 7 |
| `don't` | 181 / 202 | | |
| controls `your` / `there` / `were` | 230 / 257, 315 / 331, 239 / 246 | | |

Cause: `en_enhanced.json` holds each alias key at the frequency of its apostrophe-free TOKEN
(`youre` zipf 3.86 → 182), while the contraction itself is written ~100× more often (`you're` 5.87).
`your` is 233. The beam's frequency term therefore argues against every pronoun contraction.

## Gate (pre-registered 2026-11-25, before the candidate was built)

Arm: every English REPLACE alias key (`contractions_non_paired.json`) that is a word of
`en_enhanced.json` and whose display form has an apostrophe (possessives excluded) takes
`max(current, fit(zipf(display)))`, where `fit` is `extract_apostrophe_words.ZipfByteFit`, the
isotonic zipf→byte fit the pairing frequencies already use. Nothing else changes.

- G1 contraction targets (`you're`, `they're`, `I've`, `don't`, 368 traces): top-1 ≥ baseline + 20;
- G2 controls (`your`, `there`, `were`, 834 traces): top-1 loss ≤ 5;
- G3 the FUTO swipe-1 test sample (`futo_swipe1_test_sample.jsonl.gz`, every row): top-1 ≥
  baseline − 0.10 pt, and distinct regressed targets ≤ 2 × distinct improved targets;
- G4 `StaticLmTapEvalTest` en (typing): every gated lm_static cell ≥ baseline − 0.10 pt;
- G5 the pure, mock and instrumented suites green.

## Result: all pass; shipped

93 alias keys raised in the SWIPE TRIE (`youre` 182 → 220, `theyre` 175 → 213, `ive` 185 → 217,
`dont` 199 → 226, `im` 198 → 226, `cant` 191 → 219); `your` 233 and `there` 229 unchanged. The gate
measured the raise made in `en_enhanced.json`; it ships instead as `contraction_frequencies_en.json`,
applied by `CtcContractionKeys.inject` (raise only), which gives the beam the same trie values.

| gate | needed | result |
|---|---|---|
| G1 contraction targets | ≥ +20 | **+41** (1,199 decoded): `they're` 4 → 22 / 39, `you're` 17 → 23 / 66, `I've` 36 → 46 / 61, `don't` 179 → 186 / 200 |
| G2 controls lost | ≤ 5 | **0** (`your` 229, `there` 315, `were` 239 unchanged) |
| G3 swipe-1 test sample, 4,000 traces | t1 ≥ −0.10 pt | 91.83 → **91.83**, 0 regressions, 0 improvements |
| G4 typing eval en | every cell ≥ −0.10 pt | **±0.00** in all 16 (typing reads `en_enhanced.bin`, unchanged) |

The improvements are `there` → `they're` (15), `your` → `you're` (6), `ice`/`one`/`ie` → `I've`
(10), `done`/`font` → `don't` (7). `you're` still loses to `your` in 43 of 66 traces: `your` is the
more frequent word (233 against 220), which the decoder is right to weigh.

Why not in `en_enhanced.json`: the first build raised the lexicon itself and the pure suite went
red in 8 tests. `ContractionOverlay`'s real-word guard reads an alias key's frequency ORDINAL in the
lexicon (a junk alias ranks ≥ 1200 and is shown as its contraction); raised, `dont` ranked 67 and the
bar showed `dont` beside `don't`, and the isotonic fit moved `we'd` behind `wed`. The replay scores
the decoded spelling, so it could not see either. Keeping the lexicon as it was and raising only the
trie leaves both untouched (the suites are green).

Built by `scripts/extract_apostrophe_words.py --en-alias-frequencies` (`--check` compares);
`--en-pairing-frequencies --check` stays up to date. `BundledContractionDataTest` pins the table and
that `en_enhanced.json` keeps the token values; `ContractionFrequencyAssetDriftTest` checks every key
is an alias key and every lexicon key is raised above its own value.
Traces: `futo_contractions.jsonl` (1,202 rows from HF `futo-org/swipe.futo.org` swipe-1/2/3,
fetched by full-text search case-insensitive and apostrophe-free, controls by exact filter).
