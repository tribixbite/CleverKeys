# French contraction table: held-out confirmation (2026-11-25)

The shipped French table (`contraction_frequencies_fr.json`, 5,406 keys, values 76–204.5, sha256
`35a9094a…da273d`, last changed in `29e8f45ce`) was chosen through three arms, all judged on the
same 2,291 FUTO `swipe-5` azerty French rows (`2026-11-25-elision-own-frequency.md`, two arms;
`2026-11-25-contraction-table-cleanup.md`, the third). No row outside that set has been decoded
under it. This run is the confirmation on rows that took no part in the choice.

## Held-out set

FUTO `futo-org/swipe.futo.org`, config `swipe-5`, split `train`, `language = 'fr'` on the layouts
`qwerty` and `qwertz`. The decision used `layout = 'azerty'` only; a planning count of the split's
columns (words and sessions, no trace decoded) found 3,124 French rows, of which about 582 on
qwerty (510) and qwertz (72): 11 sessions, 357 distinct words, 28 elision rows over 27 distinct
targets. None of them has been fetched or decoded when this section is committed.

Row filters are those of the azerty sample (`scripts/fetch_futo_multilayout_sample.mjs`):
`dual_finger = 0`, at least 3 trace points, a word of letters and apostrophes, one row per
(session, word index, word). The row count after the filters is whatever it is; it is reported.

## Method

`scripts/research/elision/elision_table_ab.py fr`, given the held-out corpus and its layout
instead of the azerty default; nothing else differs from the gate runs of the two documents above:

- floor arm: the French lexicon (`fr_enhanced.bin`) with every alias key at the injection floor
  (rarest real word − 1), as `ctc_injection_ab.build_trie`;
- table arm: the same trie with each table key raised to its table value (raise only), as
  `CtcContractionKeys.inject`; the table is the shipped asset at HEAD, not a candidate;
- the shipped encoder (`ctc_swipe_encoder.onnx`), the app's CKDT preset (`ab.PRESET_CKDT`), beam
  100, the 26 a–z key centres of the official FUTO geometry for the row's layout
  (`futo_qwerty.json`, `futo_qwertz.json`; the committed copies are byte-identical to the
  dataset's `swipe-5/layouts` files, checked today);
- all rows that decode (a–z projectable, target in the trie); out-of-vocabulary and untypeable
  rows are counted and reported, not scored.

The gates are judged on the POOLED set (qwerty + qwertz rows together). The two layouts are also
reported separately, for information: 72 qwertz rows cannot carry a gate.

Units. A row is one human trace. A target is a distinct a–z projected word. For each target,
`d = hits(table) − hits(floor)` over all its decoded rows, where a hit is top-1 = target; the
target is IMPROVED if `d > 0` and REGRESSED if `d < 0`. An elision target is one whose corpus
word contains an apostrophe (`'` or `’`). Sessions, distinct targets and rows are reported side
by side for the set, for the changed rows and for the elision rows.

## Gates (pre-registered; written and committed before any held-out row was fetched)

- **H1** top-1(table) ≥ top-1(floor) (a tie passes);
- **H2** top-3(table) ≥ top-3(floor) − 0.20 pt;
- **H3** distinct improved targets ≥ distinct regressed targets, and regressed targets ≤ 2;
- **H4** on elision targets, row hits(table) ≥ row hits(floor).

Power. On azerty the table's effect was about 26 net rows of 2,180 decoded (+1.20 pt top-1). The
same rate on roughly 550 rows is about 6 net rows, and the elision rows number 28. This run can
confirm the DIRECTION of the effect on new writers and layouts; it cannot confirm its size, and a
pass must not be quoted as a second measurement of +1.2 pt.

Verdict rule, fixed now:

- all of H1–H4 pass: record "direction confirmed on N rows, magnitude unconfirmed"; the table
  stays as shipped;
- any of H1–H4 fails: French ships NO table (floor only), and
  `ContractionFrequencyAssetDriftTest` is changed to pin the absence. That change is the
  maintainer's to order; this run only reports the failure.

No retuning against these rows in either case: they are spent as a held-out set once decoded.
