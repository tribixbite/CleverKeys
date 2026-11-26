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

## Result: H1–H4 pass. Direction confirmed on 521 rows, magnitude unconfirmed

Added after the pre-registration (`d4f063c1f`); the text above is unchanged.

Harness check first: the azerty default of `elision_table_ab.py fr`, re-run after the script gained
its `--corpus` argument, reproduces the cleanup document exactly (2,180 decoded; floor 84.17 /
95.32, table 85.37 / 95.73; the same 2 regressions and 21 improvements).

Fetched with `node scripts/fetch_futo_multilayout_sample.mjs --pairs qwerty:fr,qwertz:fr`; the
committed geometries matched the dataset's. Run with
`elision_table_ab.py fr --corpus fr_qwerty:qwerty --corpus fr_qwertz:qwertz`.

| set | rows in split | kept by the filters | decoded | OOV / untypeable | distinct targets | sessions |
|---|---|---|---|---|---|---|
| qwerty | 510 | 477 (13 under 3 points, 1 bad word, 19 duplicates) | 455 | 21 / 1 | 289 | 9 |
| qwertz | 72 | 70 (1 bad word, 1 duplicate) | 66 | 4 / 0 | 57 | 2 |
| pooled | 582 | 547 | 521 | 25 / 1 | 327 | 11 |

Gates, on the pooled set:

| gate | needed | result |
|---|---|---|
| H1 top-1 | table ≥ floor | 457 → **464** of 521 (87.72 → 89.06, +7 rows) |
| H2 top-3 | ≥ floor − 0.20 pt | 497 → **497** of 521 (95.39 → 95.39) |
| H3 targets | improved ≥ regressed, regressed ≤ 2 | **6** improved / **0** regressed |
| H4 elision rows | hits(table) ≥ hits(floor) | 17 → **24** of 25 rows (24 distinct targets, 10 sessions); top-3 23 → 24 |

Per layout, for information: qwerty top-1 405 → 411 of 455 (89.01 → 90.33), top-3 437 → 437,
5 improved / 0 regressed, elision rows 14 → 20 of 21; qwertz top-1 52 → 53 of 66 (78.79 →
80.30), top-3 60 → 60, 1 improved / 0 regressed, elision rows 3 → 4 of 4.

Concentration. The table changed the top-1 of 7 rows, over 6 distinct targets and 5 of the 11
sessions: `l'art` twice (was `larry`), `jusqu'à` (was `jusque`), `d'autres` (was `faire`), `l'ont`
(was `long`), `l'acteur` (was `lecture`), `n'était` (was `mettait`). All 7 are elision rows; none
of the 496 decoded rows with a non-elision target changed its top-1 in either direction, so the
regression side saw no event at all here (on azerty it was 2 targets in 2,180 rows; at that rate
about 0.5 was expected, so 0 does not show the rate is lower). Two of the six targets, `l'art`
and `jusqu'à`, are also among the 21 azerty improvements: the same words, written by other
people on other layouts. Six of the seven changed rows moved the target from rank 2 or 3 to
rank 1, which is why top-3 is flat: `d'autres` came from outside the top 3, and one real-word
row left it (`lavoir`, second behind `labour` under the floor, fourth under the table behind
the raised `l'amour` and `l'avoue`; top-1 was wrong in both arms, so H3 does not count it).

Verdict, by the rule fixed above: **direction confirmed on 521 rows (547 kept, 327 distinct
targets, 11 sessions), magnitude unconfirmed.** The table stays as shipped; no fallback. The net
of +7 rows is in line with the ≈6 the azerty rate predicted, and it rests on 6 words from 5
writers. These rows are now spent as a held-out set.
