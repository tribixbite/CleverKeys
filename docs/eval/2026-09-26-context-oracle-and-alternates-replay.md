# Context in swipe ranking: oracle upper bound and alternates-only mode

**Run date**: 2026-09-26 · **Measured tree**: commit `197c61a4` exactly (a clean detached worktree;
see §5) · **Harness**: `ContextRescoringReplayTest`
**Invocation**: `-PgeoFull=true -PreplayDecoys=10 -PreplayCorpus={device|ubuntu}`
**Engines**: CTC (primary, the shipping default) and geometric (secondary)
**Corpora**: device export (no eviction) and Ubuntu Dialogue derived bigrams · **ONNX EP**: `xnnpack(2)`

> **Pin the commit when you quote these numbers.** This follows the discipline of
> `2026-08-22-context-rescoring-first-replay.md`: tune and confirm halves split by trace hash,
> fixed and broken counted separately, and distinct-trace counts reported next to case counts.
> The replay's original arms reproduce that document's figures to the case at this commit
> (device CTC 0 fixed / 6 broken, Ubuntu CTC 3 / 2; §4), so the harness did not drift.

---

## Question

The maintainer wants context to affect swipe ranking beyond the learned word pairs. A design
review guessed the earlier failure was **structural**: CTC is already right where context is
informative, and its errors are on rare words where an n-gram model has little data. Before
building a larger LM, we ran two experiments that add no model bytes:

1. **Oracle**: what is the most that *any* context model could gain at rank 1 under the shipped
   guards?
2. **Alternates-only**: does real learned context help when it can only reorder slots 2 and
   below, so it can never change the auto-committed word?

## Verdicts

| | Result | Verdict |
|---|---|---|
| **1. Guarded oracle**, CTC, full trace pool, **confirm half** | **Δtop-1 = +4.67 pt** (63 of 1,349 distinct traces) | Above the +1.0 pt rule. **Rank-1 context is NOT permanently closed.** The rank-1 headroom exists, and the shipped guards leave most of it reachable. |
| 1. Unguarded oracle (absolute ceiling), same population | +8.30 pt (112 / 1,349) | — |
| **2. Alternates-only**, CTC, confirm, W=0.5 | **Δ(target in slots 1–3) = +0.00 pt on both corpora.** On CTC, not one case of 3,744 (device) or 1,891 (Ubuntu) entered or left the top 3, at any weight | **FAIL.** No production change is proposed. |
| 2. Alternates-only, geometric (secondary), confirm | +0.40 pt device, +0.39 pt Ubuntu | FAIL (under +1 pt) |
| Rank-1 invariant in alternates mode | 0 rank-1 changes in every cell, on both engines and both corpora (asserted) | holds by construction |

### What the two results mean together

The design review's hypothesis is **half right**, and the wrong half matters:

- **Wrong: "no headroom."** A perfect context model would fix about 5 points of CTC top-1 even
  with `R_MIN` intact. The ceiling is not the obstacle.
- **Wrong, as far as this lexicon can show: "errors are on rare words."** CTC's errors are only
  slightly rarer than its successes. On the shipped lexicon frequency byte, the median is 188
  for oracle-fixable misses and 195 for correct decodes (§1.3). Only 30 of the 151
  target-absent misses are out-of-lexicon.
- **Right: learned pairs do not reach the misses.** On the device corpus the real learned
  context was present on 172 of 176 favourable distinct traces, but CTC had already decoded 469
  of the 481 favourable cases correctly. The learned pairs sit on words CTC already gets right.
  The favourable arm holds only 12 of the misses (in cases, not distinct traces), so most of the
  pool's 135 oracle-fixable traces lie outside what the learned pairs cover. The per-trace
  overlap was not computed. Alternates-only
  confirms this one level down: on CTC, real evidence never lands on a candidate that could
  cross the slot-3 boundary.

So bigger context is not ruled out by headroom. It is ruled out **for this data source**. A
model with broader coverage (a shipped general-English n-gram, not the user's learned pairs)
could in principle reach the misses. This harness **cannot tell how much of the +4.67 pt such a
model would capture, or what it would break**, because the oracle has no false positives by
construction (§3).

---

## 1. Experiment 1: oracle context model

**Definition.** Every slate word that equals the target gets `boost = MAX_BOOST (5.0)`,
`frequency = Int.MAX_VALUE`, and `probability = 1.0`, which clears the strict rank-1 floors. All
other words get `Evidence.NONE`. The result goes through the **shipped**
`SwipeContextRescorer.rescoreOrder` with the shipped `WEIGHT = 0.5` and `R_MIN = 0.5`. The
unguarded ceiling is "the target is anywhere in the top 8, so it becomes rank 1."

**Unit: distinct traces.** The oracle ignores context, so one trace under N contexts is the same
experiment repeated N times. The decision population is the **full usable trace pool** (2,713
traces of 2,197 words). It is the least-selected population available: the replay's own sample
is chosen for bigram pairing and decoy confusability. The rule was fixed in code
(`ORACLE_DECISION_POINTS`, full pool, confirm half) before any number was seen.

### 1.1 CTC, full pool (decision population)

```
            n      baseline top-1   target absent   target in 2..8   UNGUARDED   GUARDED   guarded, W unbounded   broken
all      2713     2347 (86.51 %)          151             215          +7.92 pt   +4.98 pt        +4.98 pt            0
tune     1364     1177 (86.29 %)           84             103          +7.55 pt   +5.28 pt        +5.28 pt            0
CONFIRM  1349     1170 (86.73 %)           67             112          +8.30 pt   +4.67 pt        +4.67 pt            0
```

This is identical in both invocations, since it is context-free and the decode is deterministic.
That serves as a reproducibility check.

**`R_MIN` binds; `WEIGHT` does not.** The guarded figure equals the "W unbounded" figure (W=100)
exactly. With max boost at W=0.5 the context term is 0.5·ln 5 = 0.80 nats, more than
ln 2 = 0.69 nats, so any target within `R_MIN` of top-1 is promotable. Across all 2,713 traces,
**80 misses have the target in the slate but below `R_MIN × top-1`**. The guard excludes them
from any context model, however good. That gap (+7.92 → +4.98 pt) is the price of the
auto-commit protection. Loosening `R_MIN` is not proposed, because nothing here measures what
it would break.

### 1.2 Replay populations (corroboration, not the decision)

| population (distinct traces) | n | baseline | UNGUARDED confirm | GUARDED confirm |
|---|---|---|---|---|
| CTC, device replay sample | 1,009 (confirm 523) | 84.34 % | +12.05 pt | **+6.88 pt** |
| CTC, Ubuntu replay sample | 739 (confirm 370) | 86.06 % | +11.35 pt | **+6.49 pt** |
| geometric, device replay sample | 1,009 (confirm 523) | 57.68 % | +22.18 pt | +18.36 pt |
| geometric, Ubuntu replay sample | 739 (confirm 370) | 57.24 % | +22.70 pt | +18.92 pt |

The replay samples show *more* oracle headroom than the pool. That is expected: the decoys are
chosen to be confusable. Every population agrees that the guarded oracle is well above +1 pt.

### 1.3 Where CTC's errors live (full pool)

```
                           n    median freq byte   p25   not-in-lexicon
engine right            2347        195.0          186         0
wrong, oracle-fixable    135        188.0          181         0
wrong, below R_MIN        80        188.0          181         0
wrong, target absent     151        189.0          180        30
```

Misses sit a few steps lower on the lexicon's frequency byte. That is not the heavy rare-word
concentration the design review assumed. *Caveat:* the pool is **word-uniform** (up to 2 traces
per word), not token-weighted. It over-represents rare words compared with real typing, so the
headline rates are not real-world top-1 rates. The within-pool comparison still holds.

## 2. Experiment 2: alternates-only mode

**Definition.** This uses the shipped rescorer with `rMin = Double.POSITIVE_INFINITY` and **real**
learned evidence. `applyRankOneGuard`'s test `scores[i] >= rMin * top` is false for every finite
score, so the engine's top-1 is restored on every call and context reorders ranks 2..K only.
This works through the rescorer's existing parameter, so **`src/main` was not changed**. The
metric is `RescoringMetrics.classifyTopK(k = 3)`: did the target ENTER or LEAVE slots 1–3? The
bar is Δ ≥ +1 pt on confirm **and** zero rank-1 changes of any kind (`meetsAlternatesBar`).
W=0.5 is the pre-registered point. A {0.25, 0.5, 1.0} sweep is selected on tune and read on
confirm. Unit: (context, trace, arm) cases, with distinct traces alongside.

### 2.1 CTC (primary)

```
device   favourable  n=481  entered=0 left=0  stayedIn=476  stayedOut=5    [176 distinct traces]
         adversarial n=3263 entered=0 left=0  stayedIn=2899 stayedOut=364  [926 distinct traces]
         CONFIRM     n=2021 entered=0 left=0  Δ=+0.00 pt  target in top-3: 89.46 % -> 89.46 %
         sweep: W=0.25/0.50/1.00 all 0 in / 0 out on tune; confirm Δ=+0.00          meetsBar=false
ubuntu   favourable  n=229  entered=0 left=0  stayedIn=223  stayedOut=6    [115 distinct traces]
         adversarial n=1662 entered=0 left=0  stayedIn=1527 stayedOut=135  [673 distinct traces]
         CONFIRM     n=1038 entered=0 left=0  Δ=+0.00 pt  target in top-3: 91.62 % -> 91.62 %
         sweep: all 0 / 0; confirm Δ=+0.00                                            meetsBar=false
rank-1 changes: 0 in every cell (asserted)
```

Nothing moved across the slot-3 boundary, at any weight, on either corpus. Favourable targets are
already in the top 3 in 476 of 481 cases (device) and 223 of 229 (Ubuntu), so there is almost
nothing to lift. And no learned evidence on a slot-4+ candidate was ever strong enough to push a
top-3 target out. The mode is **safe and inert** on CTC. It can't hurt, and it doesn't help.

### 2.2 Geometric (secondary)

```
device   CONFIRM n=2010  entered=8 (4 distinct traces) left=0  Δ=+0.40 pt   69.80 % -> 70.20 %
         selected W=1.00 on tune -> confirm Δ=+0.60 pt (12 in / 0 out)                meetsBar=false
ubuntu   CONFIRM n=1017  entered=4 (2 distinct traces) left=0  Δ=+0.39 pt   76.40 % -> 76.79 %
         selected W=0.50 on tune -> confirm Δ=+0.39 pt                                 meetsBar=false
```

Geometric moves the right way with zero losses, but it stays under the bar on both corpora and
depends on 2–4 distinct traces (`has` recurs across contexts among the device entries).

### 2.3 Production change

**None proposed.** The alternates-only bar fails on the primary engine, which ships, by the
widest possible margin (Δ = 0 exactly). A `swipe_context_rescoring = off | alternates` mode
would change nothing measurable for CTC users.

## 3. What these experiments cannot say

1. **The oracle has no false positives.** A real model also puts probability on competitors, so
   it both captures less than +4.67 pt and can break correct decodes. The earlier eval's
   adversarial arm is still the only break measurement. The oracle bounds the gain, not the
   trade-off.
2. **No sentence context for the trace corpus.** Whether a general-English LM would predict the
   135 oracle-fixable targets from their real preceding words cannot be measured here. The traces
   are isolated words.
3. **The word-uniform pool** (§1.3) inflates the rare-word share compared with real typing.
4. **One language (`en`); bigrams only**, as in the earlier eval.
5. **Display overlays not applied** (they need an Android `Context`), as before. The apostrophe
   gap is 2 cases on device and 0 on Ubuntu.

## 4. Replay arms at this commit (reproduction check)

These match `2026-08-22-context-rescoring-first-replay.md` §1 to the case:

- **Device CTC**: exposure 477 / 444 (172/176 and 107/926 distinct traces). Baseline 469/481 and
  2465/3263. **0 fixed / 6 broken, 1 distinct trace (`war`)**. Geometric: 22 / 0 from 11 traces.
- **Ubuntu CTC**: 133 queryable of 21,392 pairable. **3 fixed / 2 broken, 3 and 2 distinct
  traces**. Sweep selected W=0.50 R=0.50, confirm 1 / 2 `meetsBar=false`.

One difference from that document: on **device CTC** at this commit, grid point W=1.00 R=0.50
clears the tune half (2 fixed / 0 broken). The earlier document says no point cleared tune.
The selected point then **fails confirm, 0 fixed / 7 broken**, so the verdict is unchanged. The
cause was not investigated.

## 5. Methodology notes

- **The measurement ran in an isolated worktree at `197c61a4`**, not the shared checkout. At run
  time the shared tree held other agents' *uncommitted* edits to `CtcBeamDecoder.kt`,
  `CtcSwipeDecoder.kt` and a new `CtcLearnedPrior.kt`, all on the decode path. Measuring there
  would have repeated H11 (a decoder changing under the measurement) and produced numbers tied
  to no commit. `git diff 197c61a4` in the worktree was empty. **If those decoder changes land,
  these numbers are stale:** re-run both invocations.
- Load average was about 11–12 on 4 cores throughout. Wall-clock figures (292 s device replay,
  about 68 s full-pool pass, 23 m 45 s Ubuntu total) are not performance data. Every count is
  deterministic.
- New harness pieces: `RescoringMetrics.classifyTopK`, `TopKTally`, `meetsAlternatesBar`,
  `oracleEvidence` (unit-tested in `RescoringMetricsTest`, which failed first and was committed
  green in `4571b251`). The oracle and alternates arms plus the full-pool pass are in
  `ContextRescoringReplayTest` (`197c61a4`). Two structural invariants are asserted, not just
  reported: zero rank-1 changes in alternates mode, and zero oracle breaks.

## 6. Reproduce

```sh
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.ContextRescoringReplayTest \
  -PgeoFull=true -PreplayCorpus=device -PreplayDecoys=10      # ~7 min on a loaded box
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.ContextRescoringReplayTest \
  -PgeoFull=true -PreplayCorpus=ubuntu -PreplayDecoys=10      # ~24 min (seeding dominates)
```

The corpora stay local and uncommitted: `~/.cache/cleverkeys-corpora/{device,ubuntu}_bigrams.json`
and `~/.cache/cleverkeys-test/combined_english_swipes.jsonl.gz`. This document quotes aggregates
only.
