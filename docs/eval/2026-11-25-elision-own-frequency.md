# Elisions ranked by their own frequency (2026-11-25)

Maintainer 2026-11-25: "do all of it" (item 35's decision: rank elisions by how often the elision
itself occurs, the best arm of `2026-11-09-elision-base-frequency.md`). Found again on the Saga the
same day (synthetic straight-line swipes, r10): Italian `dellanno` → `dell'ano` first and
`dell'anno` second; `unaltra` → `un'alta` before `un'altra`; French `l'été` fourth.

## Gate (pre-registered 2026-11-25, written before the table or the app change was built)

The table: `scripts/build_elision_frequencies.py` writes `contraction_frequencies_<lang>.json` (alias
key → frequency on the lexicon's 1..255 scale) from the pinned Leipzig corpora of the static LMs
(fr `fra-fr_web_2013_300K`, it `ita-it_web-public_2019_300K`), with the 2026-11-09 calibration
(median lexicon frequency of the words whose corpus count lies in a log window around the
elision's count, MIN_N 25) and margin 0. The app injects a key at `max(floor, table[key])`.

French (human evidence: FUTO azerty, all 2,291 rows, the app's CKDT preset, beam 100; floor arm vs
table arm on today's assets):

- G1 t1(table) ≥ t1(floor) + 0.30 pt;
- G2 t3(table) ≥ t3(floor) − 0.10 pt;
- G3 regressions (distinct targets the floor got right and the table arm gets wrong) ≤ 3, and
  improvements ≥ 3 × regressions;
- G4 synthetic QWERTY `detre` (the maintainer's `d'être`): rank ≤ 2 in each of the three probe
  traces;
- G5 the German and Spanish decodes unchanged (no table for them; checked by the suites).

Italian (no human swipe corpus exists for it): the table may only ORDER elisions among themselves.
Every value lies strictly between the floor and the rarest real word (`floor < v < floor + 1`),
so every real Italian word still outranks every elision on frequency, the invariant the floor
was built for.

- G6 every Italian value in (floor, floor + 1), pinned by a drift test;
- G7 synthetic QWERTY probes: `dellanno` puts `dell'anno` above `dell'ano`, `unaltra` puts
  `un'altra` above `un'alta` (each in at least 2 of the 3 probe traces). If G7 fails, Italian
  ships no table.

Both: G8 the pure, mock and instrumented suites green.

## Result

**French: all pass; the table ships** (`contraction_frequencies_fr.json`, 11,068 keys, values 75–176.5,
floor 67; `scripts/research/elision/elision_table_ab.py fr`, all 2,291 human azerty rows, 2,180
decoded):

| gate | needed | result |
|---|---|---|
| G1 t1 | ≥ 84.47 | 84.17 → **85.09** (+0.92) |
| G2 t3 | ≥ 95.22 | 95.32 → **95.78** (+0.46) |
| G3 regressions / improvements | ≤ 3 / ≥ 3× | **2** (`donjon` → `d'union`, `laisse` → `l'aise`, the two of 2026-11-09) / **18** (`l'état`, `l'éducation`, `l'art`, `s'agit`, `d'honneur`, `l'île`, `d'après`, `l'équipe`, `jusqu'à`, `l'objectif`, …) |
| G4 `detre` | rank ≤ 2 in 3 traces | 9, 5, absent → **2, 2, 2** (behind `d'été`) |

Other probes: `dete` (d'été) 3, 2, 5 → 1, 1, 1; `lete` (l'été) 6, 4, 5 → 4, 1, 1; `lecole` stays 1.

**Second arm (chosen after the first result, judged by the same pre-registered thresholds; this
is what ships).** The first arm left `qu'il` behind `will`: `quil` is a word of the French list
(the classifier keeps contraction keys, at the wordfreq frequency of the apostrophe-free token,
96), so the injection skipped it although `qu'il` is written 5,601 times in the corpus (mapped
204.5). The second arm also RAISES (never lowers) a key the lexicon already holds: 201 such keys,
11,269 table keys in all. The shipped table (`contraction_frequencies_fr.json`) leaves out the
121 lexicon keys whose value is at or below their own lexicon frequency (`la` for `l'a`): under
raise-only they did nothing, so the shipped 11,148 keys decode exactly as the gated arm.

| gate | needed | result |
|---|---|---|
| G1 t1 | ≥ 84.47 | 84.17 → **85.55** (+1.38) |
| G2 t3 | ≥ 95.22 | 95.32 → **95.73** (+0.41) |
| G3 regressions / improvements | ≤ 3 / ≥ 3× | **2** (the same two) / **22** (the 18 above plus `l'est`, `l'histoire`, `s'est`, `s'y`) |
| G4 `detre` | rank ≤ 2 | **2, 2, 2** |

`quil` (synthetic QWERTY): 2, 2, 2 → 2, 1, 2 (behind `will` in two traces); `lete` 4, 1, 1.

## The other findings of the same swipe test

- English words in the non-English lists (`will`, `lee` in French): 260–450 per lexicon are
  written far more in English (en zipf ≥ 4, ≥ 1.5 above the language's own); in French 242 of
  them are rejected by the French oracle (`the`, `and`, `of`, `you`, `with`). NOT dropped: the
  2026-11-20 review kept English words the language's text uses as they are (`the`, `email`), and
  a French user typing an English phrase would lose them. The two cases seen on the Saga are
  elision ties the table now resolves (`l'été` first in 2 of 3 traces; `qu'il` in 1 of 3).
- `cos'è` off the bar: a PAIRED elision (its key `cose` is a word) has no frequency on the
  overlay's pairing scale, so it goes to the tail (`ContractionOverlay`, fr/it by design). Not
  changed: placing paired elisions beside their base needs the overlay's promotion rule on the
  CKDT scale and its own gate.
- `you're` / `they're` behind `your` / `there`: a first count said FUTO had no such target; it
  matched only the apostrophe spelling. The maintainer found `theyre` in the HF viewer: FUTO writes
  these words without the apostrophe (73 `youre`, 42 `theyre`, 61 `ive` across swipe-1/2/3). Measured
  and fixed the same day: `docs/eval/2026-11-25-english-contraction-frequencies.md`.

**Italian: G7 fails; no table ships.** The in-band table (values in (72, 73)) changed nothing:
`dellanno` stays 2nd behind `dellano` and `unaltra` 3rd behind `unalta` in all three traces; a
1-unit nudge is ~0.03 nats, below the path difference of a doubled letter. For information only (not
gated, not shipped): the FULL calibrated mapping, as for French, puts `dell'anno`, `un'altra` and
`l'anno` first in all three traces (`ELISION_TABLE=<candidate> elision_table_ab.py it`). Shipping it
needs human Italian swipe traces to rule out real-word losses, which no corpus here has.

G5/G8: the suites, below.
