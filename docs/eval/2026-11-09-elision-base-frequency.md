# Elision keys at their base word's frequency — measured, NOT shipped (2026-11-09)

**Question.** After `9d4003b3` re-keyed the accented French/Italian elisions (`detre` → `d'être`),
the maintainer's word `d'être` was still not offered on a Saga (QWERTY, French primary): the bar
showed `Dette, De, Fête, D'ère, D'été…`. Why, and does ranking an elision by its base word fix it
without costing real words?

**Decision needed (maintainer).** Every margin tried trades real-word regressions for surfaced
elisions; none is free. The change is kept out of the tree as
[`2026-11-09-elision-base-frequency.patch`](2026-11-09-elision-base-frequency.patch)
(applies to `CtcContractionKeys`, `ContractionManager`, `CtcEngineAdapter`, with pure tests).

## Why `d'être` loses at the floor

All 25,792 fr alias keys enter the CTC trie at `CtcContractionKeys.derivedFloor` (fr 68). `être`
is 144, so `d'être` starts `λ·ln(144/68) = 2 × 0.75 ≈ 1.5` nats behind its own base. The trace
d-e-t-r-e runs back along the top row (e → t → r → e), where the encoder gives pass-through letters
little emission, so the shorter floor keys `dete` (`d'été`), `dere` (`d'ère`) and the real `dette`
outscore it. Synthetic QWERTY traces through the shipped encoder at the app preset
(`PRESET_CKDT`, beam 100), three speeds/dwells:

| key | floor rank | note |
|---|---|---|
| `detre` | 9, 5, absent | the maintainer's word |
| `etre` | 2, 1, 2 | `être` alone is fine (`été` competes) |
| `lecole` | 1, 1, 1 | an unambiguous trace surfaces at the floor |
| `dete` | 3, 2, 5 | |

On the Saga the same: `l'école`, `c'est` first; `l'été`, `d'âme` offered; `d'être` absent
(build `9d4003b3`, `build/nov09-saga144/20-*`, `21-*`, `22-*`).

## The arm: `freq(base) − margin`

An alias key whose display form is an elision `X'base` with `base` a real lexicon word enters at
`max(floor, freq(base) − margin)`; every other key keeps the floor (CKDT lexicons only; en JSON
untouched). Replay: `scratchpad` scripts built on `scripts/ctc_injection_ab.py` (same trie
construction, shipped assets, FUTO human azerty corpus, 2,291 traces / 2,179 decoded), scored by
correctness — a regression is a trace the floor got right and the arm gets wrong.

| margin | keys raised | t1 floor → arm | t3 | regressions | improvements | `detre` rank |
|---|---|---|---|---|---|---|
| 1 (400 traces) | 9,705 | 82.68 → 82.94 | 96.06 → 96.33 | 2 (`quatre`→`qu'autre`, `l'intentionnalité`→`l'internationale`) | 3 | 2, 2, 2 |
| 15 | 7,067 | 84.26 → 84.76 | 95.32 → 95.50 | 6 (`laisse`→`l'aise`, `meilleure`→`d'ailleurs`, `densité`→`d'encore`, `donjon`→`d'union`, `jasper`→`j'adore`, `d'utilité`→`d'utile`) | 16 | 3, 2, 2 |
| 30 | 4,629 | 84.26 → 84.67 | 95.32 → 95.50 | 3 (`laisse`, `densité`, `donjon`) | 10 | 3, 2, 4 |
| 50 | 2,297 | 84.26 → 84.40 | 95.32 → 95.41 | 1 (`donjon`) | 4 | 4, 2, 7 |

Margin 15 on the first 400 traces showed 0 regressions; the full corpus found 6. A 400-trace
sample is too small to clear a ranking change.

## What the shipped data change did measure clean

`scripts/ctc_injection_ab.py --corpus azerty --rows 400` with the `9d4003b3` data (25,792 injected
keys at the floor vs at 1.0): **0 regressions**, 6 improvements (`l'église`, `l'aurait`,
`s'apparente`, `l'armement`, `s'ensuit`, …), t1 81.10 → 82.68. This closes the "ranking replay
not run" gap of todo item 35.

## Options for the maintainer

1. Keep the floor (status quo): no real word ever loses; `d'être` needs a cleaner trace; adding it to the
   user dictionary may lift it through the user-word path (not tested for elisions).
2. Ship a margin (15: +0.50 t1, 6 real words occasionally lose to an elision; 50: +0.14, 1).
3. A narrower rule, unmeasured: raise only elisions of the top-N most frequent bases, or only
   one-letter clitics (`d'` `l'` `j'` `n'` `s'` `c'` `m'` `t'`) — needs its own replay.
