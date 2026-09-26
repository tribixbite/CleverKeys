# Learned-unigram swipe prior — replay results (CTC)

**Run date**: 2026-09-26 · **Harness**: `LearnedUnigramReplayTest` · **Primitive**:
`swipe/ctc/CtcLearnedPrior.kt` · **ONNX EP**: `xnnpack(2)`

> **PIN THE COMMIT WHEN QUOTING THESE.** Stage 0 was measured at `8d89720d` (decoder files
> unmodified). Stage 1 at `d0ae3a13` (the primitive commit; the decode under `CtcLearnedPrior.NONE` is
> byte-identical, and Stage 0 reproduced to the case at that commit). `CtcReplayEngineSmokeTest.slateShapeHasNotDrifted`
> guards the slate shape these numbers are a function of.

Trigger: learning-system audit `docs/audit/2026-09-26-learning-system-audit.md`, report 2 —
the user swipes `git` constantly and gets `got` / `for`, because the swipe ranker consumes no
learned data.

---

## Verdict

**Do not wire the learned-unigram prior as designed. No point in the (N_SAT, SEL_MARGIN) grid
clears the ship bar, on the tune half or the held-out half.**

| ship bar (confirm half) | required | best grid point (N_SAT 40, SEL 0) | design default (N_SAT 20) |
|---|---|---|---|
| P_real promotion-error ratio | < 0.20 | **2.06** ✗ (16 fixed / 33 broken) | 2.31 ✗ (16 / 37) |
| P_real Δtop-1 | > 0 | **−0.0075** ✗ | −0.0093 ✗ |
| P_synth-adv breaks / exposed distinct traces | ≤ 1 % | **6/348 = 1.7 %** ✗ | 10/348 = 2.9 % ✗ |
| git pin (straight rank 1, wobbled ≤ 2) | pass | pass | pass |
| `slateShapeHasNotDrifted` | unaffected | 6/40 = 15.0 %, unchanged | — |

The mechanism works where it is supposed to — a user who uses the target word gets it fixed
(P_synth-fav: 52–107 fixed traces per half, **zero** broken, by construction), and the audit's
own `git` case flips from `got` to `git` on every ambiguous trace measured at the design point
(§3; half of them at the committed safer point). What fails is
**collateral**: the real profile lifts 1,766 words, and when the user swipes a word they have
NOT used, a lifted neighbour takes rank 1. In P_real **most** breaks fall on a target the
profile does not contain — 33 of 37 on confirm at N_SAT 20, where that subgroup scores 0 fixed /
33 broken — and the adversarial arm breaks 1.3–5.0 % of exposed traces against a 1 % bar.

Chosen constants: **none were tuned** — nothing cleared the bar to select. `CtcLearnedPrior`
carries the least-damaging grid point (**N_SAT = 40, SEL_MARGIN = 0.0**, B_CAP = 2.5,
threshold 3, F_CEIL = 255) and says in its KDoc that it is not shippable as-is. The primitive
stays committed and unwired (`CtcLearnedPrior.NONE` everywhere; byte-identical decode).

---

## 1. Stage 0 — headroom (the kill switch)

Every usable trace of the local pool (`combined_english_swipes.jsonl.gz`, timestamp-filtered by
`TraceCorpusQuality`) decoded **once** with `topK = beamWidth = 100`, so the whole final beam is
visible. A learned prior is an additive final-score term: it can only fix an error whose target
is in the beam and trails by ≤ the target's maximum lift, and only break a correct decode whose
competitor is within ITS maximum lift. `maxLift = min(λ·ln(255/f), B_CAP) + SEL_MARGIN`,
λ = 4.0, B_CAP = 2.5.

```
traces          : 4557 usable, 2197 distinct words (one decode each — traces = cases here)
top-1 correct   : 4106 (90.10 %)      errors: 451 (9.90 %), 394 distinct words
target IN beam  :  285 (6.25 %)       in-beam deficit median 0.52 (p25 0.22, p75 1.05) nats
ORACLE-FIXABLE  :  217 (4.76 %)  unigram lift only
                   246 (5.40 %)  + SEL_MARGIN 0.5   — 220 distinct words
AT-RISK correct : 1546 (33.93 %) any candidate within its unigram max lift
                  2379 (52.21 %) + SEL_MARGIN 0.5   — 1638 distinct words
                  2128 (46.70 %) runner-up only, + SEL
kill switch     : 5.40 % ≥ 1.5 %  -> PROCEED
wall clock      : 202 s (load average ~12 on 4 cores — not a latency figure)
```

**Reading.** Headroom is real (5.4 % of traces are fixable by an oracle that knows the target),
and it is mostly *cheap* headroom: half the in-beam deficits are under 0.52 nats. But the damage
surface is ten times larger — half of all correct decodes have a competitor that *could* be
lifted past them. The prior is therefore safe only to the extent real profiles lift targets far
more often than they lift competitors. Stage 1 measures exactly that ratio.

## 2. Stage 1 — profile replay

### 2.1 Setup

- **One decode per trace** (the 4,557 of §1, `topK = beamWidth`), then every profile and grid
  point is a pure re-rank — the bonus is additive on the final score, so this is exact.
  **Fidelity check:** for the first 60 traces the re-rank's top-1 is asserted equal to a real
  `CtcBeamDecoder.decode(…, prior)` under P_real; it passed.
- **Split** by trace hash (`id.hashCode() % 2`), the context-rescoring convention: tune 2,291
  traces, confirm 2,266. Selection on tune only.
- **Unit**: every trace appears once per arm, so fixed/broken counts ARE distinct traces;
  distinct words are reported beside them. The adversarial arm has up to 3 decoys per trace, so
  its raw cases (6,858) are inflated — its bar is quoted in **distinct exposed traces**.
- **Profiles**
  - **P_real** — the maintainer's `user_vocabulary` export (5,000 entries; 1,766 at
    n_eff ≥ 3 with recency evaluated at the export instant). **No manual selections** — the
    export does not carry `SelectionHistory`, so SEL_MARGIN is inert on this arm.
    Contaminated by audit W1 (auto-inserts counted as usage) — it inflates words the engine
    already prefers, so it cannot manufacture fixes, but it can also lift wrong words.
  - **P_synth-fav** — `{target: n}` for usage n ∈ {3, 6, 20} and selections m ∈ {1, 2, 6}.
  - **P_synth-adv** — `{c: 20 uses}` and `{c: 7 selections}` (n_eff 21) for c ∈
    `neighboursOf(target)` (same first letter, length ±1, seeded shuffle; 3 per trace).
    Exposed = c is in the final beam.
  - **adv runner-up** (informational, not in the bar) — `{runner-up: 20 uses}`: the user
    heavily uses exactly the word the engine's runner-up is. The worst case.
- **Selection rule** (stated before looking): among tune points clearing the bar, max P_real
  (fixed − broken), then max synthetic fixes, then the less aggressive point.

### 2.2 Results — tune half (2,291 traces)

| N_SAT | SEL | P_real fixed/broken | errRatio | Δtop-1 | used-target f/b | unused-target f/b | adv use20 broken / exposed traces | adv sel7 | fav use3 / use20 / sel6 fixed |
|---|---|---|---|---|---|---|---|---|---|
| 10 | 0 | 11 / 58 | 5.27 | −0.0205 | 11 / 5 | 0 / 53 | 9/378 = 2.4 % | 9 (2.4 %) | 69 / 95 / 95 |
| 10 | 0.25 | 11 / 58 | 5.27 | −0.0205 | 11 / 5 | 0 / 53 | 2.4 % | 13 (3.4 %) | 69 / 95 / 105 |
| 10 | 0.5 | 11 / 58 | 5.27 | −0.0205 | 11 / 5 | 0 / 53 | 2.4 % | 19 (5.0 %) | 69 / 95 / 107 |
| 20 | 0 | 9 / 46 | 5.11 | −0.0162 | 9 / 4 | 0 / 42 | 9/378 = 2.4 % | 9 (2.4 %) | 61 / 95 / 93 |
| 20 | 0.25 | 9 / 46 | 5.11 | −0.0162 | 9 / 4 | 0 / 42 | 2.4 % | 13 (3.4 %) | 61 / 95 / 104 |
| 20 | 0.5 | 9 / 46 | 5.11 | −0.0162 | 9 / 4 | 0 / 42 | 2.4 % | 19 (5.0 %) | 61 / 95 / 107 |
| 40 | 0 | 9 / 35 | 3.89 | −0.0113 | 9 / 4 | 0 / 31 | 5/378 = 1.3 % | 5 (1.3 %) | 52 / 87 / 86 |
| 40 | 0.25 | 9 / 35 | 3.89 | −0.0113 | 9 / 4 | 0 / 31 | 1.3 % | 10 (2.6 %) | 52 / 87 / 96 |
| 40 | 0.5 | 9 / 35 | 3.89 | −0.0113 | 9 / 4 | 0 / 31 | 1.3 % | 15 (4.0 %) | 52 / 87 / 106 |

`SELECTED: none — no grid point clears the ship bar on the tune half.` P_real exposure: 1,934
cases / 1,928 traces (84 % of traces carry at least one lifted candidate). Fixed words ≈ fixed
traces (e.g. N_SAT 20: 9 traces / 7 words fixed, 46 traces / 46 words broken) — the breaks are
**not** concentrated on a few words; they are spread across the unused vocabulary.

### 2.3 Results — confirm half (2,266 traces; reported, not selected)

| N_SAT | SEL | P_real fixed/broken | errRatio | Δtop-1 | used-target f/b | unused-target f/b | adv use20 | adv sel7 |
|---|---|---|---|---|---|---|---|---|
| 10 | 0 | 17 / 45 | 2.65 | −0.0124 | 17 / 2 | 0 / 43 | 10/348 = 2.9 % | 10 (2.9 %) |
| 20 | 0 | 16 / 37 | 2.31 | −0.0093 | 16 / 4 | 0 / 33 | 10/348 = 2.9 % | 10 (2.9 %) |
| 40 | 0 | 16 / 33 | 2.06 | −0.0075 | 16 / 5 | 0 / 28 | 6/348 = 1.7 % | 6 (1.7 %) |

(SEL 0.25/0.5 rows are identical on P_real and worse on adv sel7: 18–22 breaks at N_SAT ≤ 20,
12–20 at N_SAT 40.) The held-out half agrees with tune in direction on every arm.

Worst-case adversary (runner-up used 20×): breaks **29.8 %** of exposed traces at N_SAT ≤ 20
and **22.6 %** at N_SAT 40 on tune — i.e. if the user heavily uses the exact word competing
with their swipe, the lift toward the ceiling decides it a quarter of the time. That is what
"lift used words toward the ceiling" means arithmetically, and the adversarial arm measures a
diluted version of it.

## 3. The audit's own case (`git` vs `got`)

`LearnedUnigramReplayTest.gitPinUnderTheShippedConstants`, profile `{git: 6 selections,
got: 98 uses}`:

```
straight      git rank NONE=1 prior=1   (clean synthetic shapes: NONE already wins)
wobble 0.02   git rank NONE=1 prior=1
wobble 0.035  git rank NONE=1 prior=1
i/o = 0.30    NONE [got, git, hit]  -> prior [git, got, hit]
i/o = 0.40    NONE [got, git, hit]  -> prior [git, got, hit]
i/o = 0.50    NONE [got, git, fit]  -> prior [git, got, fit]
i/o = 0.60    NONE [got, git, for]  -> prior [git, got, for]
```
(measured with the design's N_SAT 20 / SEL 0.25. At the committed least-damaging point,
N_SAT 40 / SEL 0, the pin assertions still pass but the ambiguous flip survives only at
i/o 0.30 and 0.40; at 0.50 and 0.60 `got` stays first — the safer setting gives back half of the
reported case.)

The design's pin shapes (straight / wobbled) are **not discriminating**: the shipped decoder
already gets `git` right on them. The reported failure is an *ambiguous middle key*, so the
harness also aims the middle point between `i` and `o`: NONE gives `got` at every fraction up
to 0.6-toward-`o`, and the prior flips each to `git`. The feature fixes the reported case; it
just breaks more unrelated swipes than it fixes while doing so.

## 4. Why it fails, and what the numbers point at

1. **The damage is on words the user has never typed.** Across all nine points and both halves,
   the "target unused" subgroup has **0 fixes** and 28–53 breaks. A ceiling-lift makes every
   one of 1,766 used words "as common as `the`", and a swipe of an unused rarer neighbour then
   loses to it. The design's saturation is exactly what makes that happen after few uses.
2. **On words the user HAS typed, the trade is mixed, not clean**: used-target errRatio 0.44
   (tune) / 0.25 (confirm) at N_SAT 20 — fixes outnumber breaks but not by 5×.
3. **Traffic weighting does not rescue it — an inference, not a measurement.** 91.6 % of the
   export's usage *tokens* are on eligible words, so real typing is dominated by the used
   group. Re-weighting the N_SAT 20 rates by that share (used: 25 fixed / 8 broken over 2,315;
   unused: 0 / 75 over 2,242) gives ≈ 0.99 % fixes vs ≈ 0.60 % breaks per swipe —
   errRatio ≈ 0.6, still three times the bar. And the trace pool's within-group word mix is
   not the user's, so treat this as an order of magnitude only.
4. **SEL_MARGIN only ever hurts on these arms**: P_real has no selections, so a margin cannot
   help it, while every non-zero value doubled or tripled adversarial breaks.

Directions this points at (NOT built, NOT measured — recorded for the next attempt):
- a **relative** lift gated on *the competitor being unused* ("prefer a used word over an
  unused one") rather than an absolute ceiling-lift of every used word;
- a much smaller B_CAP (the used-target breaks sit at small deficits — §1's median in-beam
  deficit is 0.52 nats, so a cap near that captures most fixable headroom);
- counting **manual selections only** (audit W1/W2 first), which is the evidence the audit
  actually says is trustworthy — and which this export cannot supply, so it needs an on-device
  export that includes `SelectionHistory` before it can be measured.

## 5. Limitations that must travel with these numbers

1. **The trace pool is not the maintainer's typing.** It is other people's swipes of 2,197
   words; P_real is applied to them as if the maintainer had swiped them. The "target used /
   unused" split exists because of this.
2. **P_real is W1-contaminated** and has **no selections**.
3. **One language (en), one lexicon scale.** The CKDT languages (λ = 2.0) are unmeasured.
4. **`CtcEngineAdapter`'s display overlays are not applied** (contraction rewriting); targets
   here are all a–z (0 non-a–z targets in the pool).
5. **Adversarial decoys are selected for confusability** (first letter, length ±1), so the arm
   over-represents the damage surface by construction; it is also only 378 / 348 exposed
   traces per half — a 1 % bar is 3–4 traces.
6. **Wall-clock figures are not latency**: load average 12–14 on 4 cores throughout.

## 6. Reproduce

```sh
# Stage 0 + Stage 1 + the git pin (~7 min decode at load 12; needs the two local corpora)
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.LearnedUnigramReplayTest -PgeoFull=true
# pins only (no corpora needed; ORT natives only)
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.LearnedUnigramReplayTest
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.ctc.CtcLearnedPriorTest
```

Corpora (never committed): `~/.cache/cleverkeys-test/combined_english_swipes.jsonl.gz` and
`~/.cache/cleverkeys-corpora/device_bigrams.json` (`user_vocabulary`). This document quotes only
aggregates from the latter; examples are drawn from the synthetic arms and the audit's `git`.
