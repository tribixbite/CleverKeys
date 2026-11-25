# Contraction frequency tables: three corrections from the pre-tag review (2026-11-25)

Pre-tag review `docs/audit/2026-11-25-pre-tag-review.md` S4, S5, S6, on the two tables shipped the
same day (`contraction_frequencies_en.json`, `docs/eval/2026-11-25-english-contraction-frequencies.md`;
`contraction_frequencies_fr.json`, `docs/eval/2026-11-25-elision-own-frequency.md`).

- **S4 (English):** 12 of the 93 keys take wordfreq's frequency of a TWO-TOKEN phrase: wordfreq
  splits `d'art` into `d` + `art`, `y'all` into `y` + `all`, so `zipf_frequency` estimates the
  phrase, not the word (`dart` 176 → 205, `yall` 170 → 204, `maam` 149 → 196, `oer` 153 → 188,
  `neer` 150 → 182, and the `d'…` loans `detat`, `doeuvre`, `dazur`, `dêtre`, `detre`, `darezzo`,
  `destaing` at 148–163). Correction: a display form that wordfreq does not keep as one token gets
  no table entry (the key keeps its lexicon value).
- **S5 (French):** 49 keys of the table are PAIRED keys (`contraction_pairs_fr.json`: the key is a
  word of its own, `dune` beside `d'une`). The overlay keeps the base word in a paired key's slot
  and puts the elision at the tail, so the raise boosts `dune` (99 → 204), not `d'une`.
  Correction: paired keys get no table entry. The replay scored a paired target's key as the
  elision, so part of the shipped +1.38 pt (`l'est`, shown as `lest`) was not a gain on the bar.
- **S6 (French):** an elision seen once or twice in the corpus calibrates to 75 (the median of the
  window's rarest words) and outranks the 7,098 real words at 68–74; 5,702 keys sit at 75,
  including junk (`l'x`, `l'ob`, `d'abc`). Correction: a key needs at least `MIN_COUNT = 3` corpus
  hits for a raise. Counts 1 and 2 both map to 75, the bottom of the calibration, so they carry
  "seen", not an order; from 3 on the calibration separates (3 → 76, 5 → 78, 10 → 85).

## Gates (pre-registered 2026-11-25, before any candidate table was built)

Baselines are the arms of the original gates, so each corrected table is held to the bar the
shipped one was gated on, and its difference from the shipped table is reported beside it.

English (candidate = shipped table minus the S4 keys; `short_word_ctc_eval.py dump --emu smooth`):

- E1 contraction targets (`you're`, `they're`, `I've`, `don't`, `futo_contractions.jsonl`, 1,202
  rows): top-1 ≥ pre-table baseline + 20 (the original G1);
- E2 controls (`your`, `there`, `were`): top-1 loss against the pre-table baseline ≤ 5;
- E3 FUTO swipe-1 test sample, every row: top-1 ≥ pre-table baseline − 0.10 pt, and distinct
  regressed targets ≤ 2 × distinct improved targets;
- E4 typing eval: unchanged by construction (typing reads `en_enhanced.bin`, not the table).

French (candidate = the corrected builder's table, S5 and S6 both; `elision_table_ab.py fr`, FUTO
azerty, all 2,291 rows, floor arm vs candidate):

- F1 t1(candidate) ≥ t1(floor) + 0.30 pt; F2 t3(candidate) ≥ t3(floor) − 0.10 pt;
- F3 regressions against the floor ≤ 3 and improvements ≥ 3 × regressions;
- F4 synthetic QWERTY `detre`: rank ≤ 2 in each of the three probe traces;
- for information, not gated: the same numbers for the shipped table, and the distinct targets the
  candidate loses or gains against it.

Both: the pure, mock and instrumented suites green. If a language's candidate fails, that
language ships the shipped table unchanged and the finding stays open.

## Result: both pass; both corrected tables ship

**English** (`contraction_frequencies_en.json`, 93 → 81 keys; the 12 keys keep their lexicon
values). The candidate decodes exactly as the shipped table on every one of the 5,202 human
traces (no row differs): the 12 keys never come up in these corpora, so the correction removes
a risk (`d'art` at 205 against `haven't` on a d-a-r-t-shaped swipe), not a measured loss.

| gate | needed | result |
|---|---|---|
| E1 contraction targets | ≥ +20 | **+41** (238 → 279 / 368; `they're` 4 → 22, `you're` 17 → 23, `I've` 36 → 46, `don't` 181 → 188) |
| E2 controls lost | ≤ 5 | **0** (`your` 230, `there` 315, `were` 239 / 834) |
| E3 swipe-1 test sample, 4,000 traces | t1 ≥ −0.10 pt | 91.83 → **91.83**, 0 regressions, 0 improvements |
| E4 typing | unchanged | by construction |

(The original English doc counted 1,199 decoded rows and `don't` 179 → 186 / 200; this replay
decoded all 1,202, hence the small difference in the `don't` row. Top-1 is the beam's first
candidate, `c[0]` in the dump; the dump's `g` field is the greedy CTC string.)

**French** (`contraction_frequencies_fr.json`, 11,148 → 5,406 keys, values 76–204.5, floor 67;
`ELISION_TABLE=<candidate> elision_table_ab.py fr`, all 2,291 human azerty rows, 2,180 decoded):

| gate | needed | result |
|---|---|---|
| F1 t1 | ≥ 84.47 | 84.17 → **85.37** (+1.20) |
| F2 t3 | ≥ 95.22 | 95.32 → **95.73** |
| F3 regressions / improvements | ≤ 3 / ≥ 3× | **2** (`donjon` → `d'union`, `laisse` → `l'aise`, the same two) / **21** |
| F4 `detre` | rank ≤ 2 in 3 traces | **2, 2, 2** (behind `d'été`) |

Against the shipped table (85.55 / 95.73, re-run the same day): −0.18 pt top-1, and the one
target lost is `l'est`, the paired key the bar shows as `lest` anyway. The probes are unchanged:
`dete` 1, 1, 1; `lete` 4, 1, 1; `quil` 2, 1, 2 (behind `will` in two traces); `lecole` 1.

Removed, 5,742 keys in all, none added and no value changed: the 49 paired keys (S5); 5,691
more with one or two corpus hits (S6; 5,702 keys sat at 75, 11 of them paired); and `labbé`,
`lépine`, keys with a letter outside a–z that the app's parser dropped anyway. 73 lexicon-native
keys remain raised (`quil`, `cest`, `nest`, `jai`, …). Correction to S6 as written above: `d'abc`
has 3 corpus hits and sat at 76, not 75, so it stays; `l'x` and `l'ob` (1 hit each) are gone. Built by `scripts/build_elision_frequencies.py --lang fr
--write` (`--check` compares the asset with a fresh build) and
`scripts/extract_apostrophe_words.py --en-alias-frequencies` (`--check` likewise).
`ContractionFrequencyAssetDriftTest` pins all three rules on the shipped files.

Not changed: placing a paired elision beside its base word (`d'une` next to `dune`) needs the
overlay's promotion rule on the CKDT scale and its own gate, as before.
