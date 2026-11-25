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

## Result

(Filled in after the runs.)
