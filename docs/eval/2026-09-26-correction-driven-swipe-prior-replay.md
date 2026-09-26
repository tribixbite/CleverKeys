# Correction-driven swipe prior — replay results (CTC)

**Run date**: 2026-09-26 · **Harness**: `CorrectionPriorReplayTest` · **Primitive**:
`swipe/ctc/CtcLearnedPrior.kt` (`EvidenceMode.CORRECTIONS`) · **ONNX EP**: `xnnpack(2)`

> **PIN THE COMMIT WHEN QUOTING THESE.** All numbers were measured at `e41f9463`, the
> commit that adds the correction mode. With `CtcLearnedPrior.NONE` the decode is
> byte-identical. At that commit `CtcReplayEngineSmokeTest.slateShapeHasNotDrifted` reads
> **6/40 = 15.0 % (median 0.164), unchanged**. The earlier usage replay
> (`LearnedUnigramReplayTest`) reproduces its own doc exactly: Stage 0 4,106/4,557 correct,
> 246 oracle-fixable; confirm N_SAT 40: 16 fixed / 33 broken.

Predecessor: `docs/eval/2026-09-26-learned-unigram-swipe-replay.md`. That doc's usage-driven
prior failed because lifting every word the user typed broke about twice as many
untyped-target swipes as it fixed. Trigger: `docs/audit/2026-09-26-learning-system-audit.md`
report 2 (the user swipes `git` and gets `got` / `for`).

---

## Verdict

**Do not wire it yet. No correction-driven policy clears the ship bar in a way this corpus
can support.** Correction evidence is a large improvement over usage evidence, and the
remaining damage has a clear pattern. The trace pool, though, has too few words that repeat
to show the feature can pay for itself.

| ship bar (CONFIRM direction, r = 1.0) | required | UNIGRAM c(Y), best tune point (cMin 1, N_SAT 3, B_CAP 1.0) | PAIR c(X→Y), selected (cMin 2, N_SAT 3, B_CAP 1.0) | usage prior, for reference (N_SAT 40) |
|---|---|---|---|---|
| errRatio (broken / fixed) | < 0.20 | **0.44** ✗ (9 fixed / 4 broken) | 0.00 (1 / 0) — **one trace** | 2.06 ✗ (16 / 33) |
| Δtop-1 | > 0 | **+0.0022** ✓ | +0.0004 | −0.0075 ✗ |
| per-word blast ≤ 1 % of in-beam carriers | every lifted word | ✗ (4 words, each 1 break; e.g. `fox` 1/20, `ross` 1/1) | ✓ (0 breaks, 1 carrier) | — |
| same point, TUNE direction | — | 11 / 7, errRatio 0.64 ✗ | 3 / 0 — **1 word** (`too`) | 9 / 35 |
| git pin (ambiguous i/o flips ≤ 3 corrections) | pass | **pass** (1, 1, 1, 2 corrections) | not built in the primitive | pass (half the shapes) |
| `slateShapeHasNotDrifted` | unchanged | 6/40 = 15.0 %, unchanged | — | — |

- **UNIGRAM** (the pre-registered hypothesis) fails. No point clears the bar on tune at any
  correction rate. At the best point, Δtop-1 is positive in both directions, but errRatio is
  0.44–0.64 against a bar of < 0.20. At a 70 % correction rate the tune direction goes net
  negative (4 fixed / 6 broken).
- **PAIR-gated** (added as a second hypothesis before its numbers were seen, §4) formally
  clears the bar at cMin 2. That pass rests on **3 tune traces of one word** (`to → too`) and
  **1 confirm trace** (`butter → buyer`). One word is not evidence, so this is **not** a ship
  verdict. It shows only that the corpus cannot measure the precise variant.
- **What improved, with numbers.** At comparable points, breaks fall from 33–58 per half
  (usage) to 4–9 (correction). Every break falls on a target that carries **no** correction,
  and **zero** fall on corrected targets. Corrected targets score 9–11 fixed / 0 broken per
  direction. The prior helps exactly the words it should. Its cost is collateral on
  neighbouring words the user has never corrected to.

**Constants committed** (`CtcLearnedPrior` companion): `CORRECTION_MIN_COUNT = 1`,
`CORRECTION_N_SAT = 3`, `CORRECTION_B_CAP = 1.0`. This is the UNIGRAM point with the best
tune-direction net (+4). B_CAP 1.0, 1.5 and 2.5 tie on tune, and the pre-stated rule takes
the smaller cap. It is also the point with the smallest adversarial blast (§5). It passes
the git pin, but it is **not** a shippable tuning. The KDoc says so, and the default mode
stays unwired.

---

## 1. The simulation

- **Pool**: every usable trace of `combined_english_swipes.jsonl.gz` (4,557 traces, 2,197
  words; `TraceCorpusQuality`-filtered). Each trace is decoded **once** with
  `topK = beamWidth = 100`. Every policy is an exact re-rank of that final beam, because the
  prior is additive on the final score. **Fidelity**: on 60 exposed confirm traces, the
  re-rank's top-1 was asserted equal to a real `CtcBeamDecoder.decode(…, prior)`. It passed,
  including 1 case where top-1 changed.
- **Split**: the shared trace-hash rule (`LearnedPriorTracePool.Row.tuneHalf`, identical to
  the usage replay): tune 2,291 / confirm 2,266. Words in both halves: **372**, against 887
  tune-only and 938 confirm-only. That overlap bounds every fix below.
- **Phase A (history)**: decode one half with no prior. Each top-1 error whose target is a
  merged-lexicon word becomes a correction `c(target) += 1` and a pair `c(top1 → target) += 1`.
  It is kept with probability `r`. The per-trace coin is fixed, so r = 0.5 ⊂ 0.7 ⊂ 1.0.
  - Tune half: 205 errors (189 lexicon targets, 125 target in beam). Confirm half: 246
    (231 / 160).
  - Evidence at r = 1: **216 corrected words** (count histogram {1: 204, 2: 9, 3: 3}) from the
    confirm half, and **176** ({1: 166, 2: 8, 3: 1, 4: 1}) from the tune half.
  - Compare 1,766 lifted words for the usage prior. Correction evidence is about 8× sparser,
    and ~94 % of it is a single correction.
- **Phase B (future)**: decode the other half with phase A's evidence. Each trace is classified
  against the no-prior decode with `RescoringMetrics.classify`.
- **Directions**: **TUNE** = A on confirm → B on tune (the policy is selected here, at r = 1.0).
  **CONFIRM** = A on tune → B on confirm (the ship bar is judged here). Both are always reported.
- **Units**: each trace appears once per arm, so fixed/broken counts are **distinct traces**,
  with distinct words beside them (`11tr/8w`). **Blast** is per lifted word: breaks ÷ phase-B
  traces carrying it in-beam as a non-target.
- **Grid**: cMin ∈ {1, 2, 3} × N_SAT ∈ {2, 3, 5} × B_CAP ∈ {1.0, 1.5, 2.5}. B_CAP 0.5 and
  0.75 are informational only and never selected. Correction rate r ∈ {1.0, 0.7, 0.5}.
- **Selection rule, stated before looking**: among tune points that clear the bar (errRatio < 0.2,
  Δ > 0, worst per-word blast ≤ 1 %), take the maximum (fixed − broken). Ties go to the
  smaller B_CAP, then the larger cMin, then the larger N_SAT. If none clears, report the best
  tune net.

## 2. UNIGRAM c(Y) — both directions, r = 1.0, B_CAP 1.0

B_CAP 1.5 and 2.5 are identical to 1.0 at cMin ≥ 1 / N_SAT ≥ 3. Only the cMin 1 / N_SAT 2
confirm row gains one break at 1.5 and above.

| cMin | N_SAT | dir | lifted words | fixed tr/w | broken tr/w | Δtop-1 | errRatio | worst blast | corrected-target f/b | uncorrected f/b |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2 | TUNE | 216 | 11/8 | 9/9 | +0.0009 | 0.82 | 1/1 | 11/0 | 0/9 |
| 1 | 2 | CONFIRM | 176 | 10/9 | 4/4 | +0.0026 | 0.40 | 1/1 | 10/0 | 0/4 |
| **1** | **3** | **TUNE** | 216 | **11/8** | **7/7** | +0.0017 | **0.64** | 1/1 | 11/0 | 0/7 |
| **1** | **3** | **CONFIRM** | 176 | **9/8** | **4/4** | +0.0022 | **0.44** | 1/1 | 9/0 | 0/4 |
| 1 | 5 | TUNE | 216 | 7/7 | 4/4 | +0.0013 | 0.57 | 1/1 | 7/0 | 0/4 |
| 1 | 5 | CONFIRM | 176 | 8/7 | 2/2 | +0.0026 | 0.25 | 1/1 | 8/0 | 0/2 |
| 2 | 2–3 | TUNE | 12 | 4/2 | 2/1 | +0.0009 | 0.50 | 2/81 = 2.5 % | 4/0 | 0/2 |
| 2 | 2–3 | CONFIRM | 10 | 3/3 | 0 | +0.0013 | 0.00 | — | 3/0 | 0/0 |
| 2 | 5 | TUNE | 12 | 2/2 | 2/1 | 0.0000 | 1.00 | 2/81 | 2/0 | 0/2 |
| 3 | any | TUNE | 3 | 1/1 | 2/1 | −0.0004 | 2.00 | 2/81 | 1/0 | 0/2 |
| 3 | any | CONFIRM | 2 | 0 | 0 | 0 | — | — | — | — |

Informational smaller cap, cMin 1 / N_SAT 3: B_CAP 0.5 gives TUNE 8/6 (0.75), CONFIRM
9/3 (0.33).

**Examples** (the chosen row): confirm fixes `did←dried`, `buyer←butter`, `ask←al`, `fox←food`,
`consistent←consists`, `tree←tee`, `this←things`, `get←feet`. Confirm breaks: `rosa→ross`,
`milf→mild`, `would→wood`, `fix→fox`. Tune breaks: `sucks→stocks`, `carol→carroll`,
`steady→stay`, `dish→did`, `tricks→trucks`, `lines→likes`, `soon→son`. **Every break is a
word the lifted word was never corrected FROM**: `fox` was corrected from `food` and then
took a `fix` swipe.

**Concentration.** Breaks are spread out: 4 broken traces are 4 distinct words and 4 distinct
lifting words. At cMin ≥ 2 the 2 tune breaks are **one** lifted word taking 2 of the 81
traces that carry it (2.5 %). The per-word blast bar is failed by words with only 1–20
carriers, where one break is already 5–100 %. A 1 % bar is not measurable at that carrier
count. Pooled, the chosen point breaks 7/1,252 (tune) and 4/754 (confirm) carrier-traces,
**0.53–0.56 %**.

### Correction-rate sensitivity (chosen UNIGRAM point)

| r | TUNE fixed/broken (errRatio, Δ) | CONFIRM fixed/broken (errRatio, Δ) |
|---|---|---|
| 1.0 | 11/7 (0.64, +0.0017) | 9/4 (0.44, +0.0022) |
| 0.7 | 4/6 (1.50, −0.0009) | 5/4 (0.80, +0.0004) |
| 0.5 | 2/4 (2.00, −0.0009) | 4/3 (0.75, +0.0004) |

Fixes fall faster than breaks as the rate drops. A missed correction removes a fix outright,
because the same word must err in both halves. The surviving lifts still hit neighbours.
At realistic rates the UNIGRAM rule is roughly break-even.

## 3. The git pin (audit case)

`CorrectionPriorReplayTest.gitFlipsAfterAtMostThreeCorrections`, default policy, evidence
`{git: k corrections}` and nothing else. The shapes are ambiguous middle keys between `i` and
`o` (`LearnedPriorTracePool.ambiguousGit`):

```
i/o 0.30  NONE [got, git, hit] -> git rank 1 after 1 correction
i/o 0.40  NONE [got, git, hit] -> git rank 1 after 1 correction
i/o 0.50  NONE [got, git, fit] -> git rank 1 after 1 correction
i/o 0.60  NONE [got, git, for] -> git rank 1 after 2 corrections
```

This passes at B_CAP 1.0 (and at 1.5, where it was first measured). The usage prior at its
committed point flipped only the 0.30 and 0.40 shapes. Here the reported case needs 1–2
corrections.

## 4. PAIR-gated c(X→Y) — the precise variant

Rule: lift Y **only in a beam whose no-prior top-1 is X**, where the user previously replaced
an auto-inserted X with Y. In other words, fire only when the decoder is about to repeat the
mistake the user already corrected. This arm was added after the UNIGRAM results were in,
but before any PAIR number was seen. It uses the same selection rule, applied separately.

| cMin | N_SAT | dir | lifted pairs | fixed tr/w | broken tr/w | errRatio | worst blast |
|---|---|---|---|---|---|---|---|
| 1 | 2 | TUNE | 224 | 7/4 | 3/3 | 0.43 | 1/1 |
| 1 | 2 | CONFIRM | 183 | 2/2 | 0 | 0.00 | — |
| 1 | 3 | TUNE | 224 | 6/3 | 3/3 | 0.50 | 1/1 |
| 1 | 3 | CONFIRM | 183 | 2/2 | 0 | 0.00 | — |
| **2** | **3** | **TUNE** (selected) | 5 | **3/1** | 0 | 0.00 | — |
| **2** | **3** | **CONFIRM** | 4 | **1/1** | 0 | 0.00 | — |
| 3 | any | both | 2 | 0 | 0 | — | — |

At r = 0.7 and 0.5 the selected point fixes **nothing** in either direction. The cMin 2
"pass" is `too←to` ×3 (tune) and `buyer←butter` ×1 (confirm): **2 distinct words** in
total. Read raw, that row says "0 broken, bar met". Read by distinct units, it says there
is no evidence either way.

The precise variant, measured where the pool does have data (§5b), is not free either: it
breaks the correct swipes of any user who genuinely types BOTH words of a pair.

## 5. Adversarial arms

**(a) Wrong corrections.** A changed-mind bar tap on a CORRECT decode ("corrects" to the
runner-up), at noise q. Evaluated at the reported point of each arm, r = 1.0:

| arm | q | TUNE fixed/broken | CONFIRM fixed/broken | new blast |
|---|---|---|---|---|
| UNIGRAM | 0.02 | 11/8 | 10/8 (errRatio 0.80) | `god` 2/18, `her` 1/49 |
| UNIGRAM | 0.05 | 11/10 | 10/11 (−0.0004) | `god` 2/18, `definetly` 1/3 |
| PAIR | 0.02 | 3/0 | 1/2 (errRatio 2.0) | `god` 2/12 |
| PAIR | 0.05 | 3/0 | 1/2 | `god` 2/12 |

Even 2 % noise turns the best UNIGRAM confirm row from 9/4 into 10/8. Correction evidence
is only high-precision if the recording path excludes changed-mind taps. That constrains
the wiring (§7).

**(b) Single-word blast radius — "what if the user's one correction were this word".** The
default policy lifts each eligible word alone at a saturated count (c = 3) over **every**
trace that carries it as a non-target (whole pool):

- **UNIGRAM**: 1,438 words sit in ≥ 20 beams. **119** of them break ≥ 1 trace and **108**
  exceed 1 % of their carriers. Pooled: **145 / 63,477 = 0.23 %**.
  - Worst ratios: `sue` 4/32, `son` 4/37, `god` 4/38, `pr` 2/20, `toes` 2/20, `seem` 2/20,
    `gone` 2/27, `gold` 2/28, `had` 4/70.
  - Most breaks: `god`, `had`, `son`, `sue`, `haas` (4 each).
  - The biggest hubs (`tha`, `tou`, `yoe`, 200+ carriers) break ~0: rare junk candidates
    trail by far more than 1 nat.
  - At B_CAP 1.5 the same arm gives 186 words over 1 % (257/63,477 pooled). This is why the
    committed cap is 1.0.
- **PAIR**: for 4,106 correct traces, every in-beam eligible Y is a hypothetical saturated
  pair (top-1 → Y).
  - **779 (18.97 %)** of correct traces have at least one Y that such a pair would promote.
  - Pooled over (X, Y) carriers: **1,334 / 160,199 = 0.83 %**. 108 of 4,812 pairs with
    ≥ 5 carriers exceed 1 %.
  - Worst pairs: `ken→keen` 3/5, `holiday→holliday` 3/5, `soon→son` 4/7,
    `cleared→clearer` 2/5, `class→classes` 2/5, `playing→paying` 2/6, `all→al` 2/10.
  - Plainly: a user who once replaced `soon` with `son` and also genuinely swipes `soon`
    gets about half of those `soon` swipes broken. The git/got user is exactly this case.

## 6. Why it still fails, and what the numbers point at

1. **The collateral mechanism survives, at 1/8 the volume.** A lifted Y wins any beam where
   it trails by < ~1 nat, including beams for words the user never corrected. The UNIGRAM
   rule cannot tell "this is the `food`-shaped swipe that should be `fox`" from "this is a
   `fix` swipe". The PAIR rule can, but only by trusting that X is never intended.
2. **The corpus is the binding constraint, not only the rule.** Fixes need the same word to
   err in both halves. With ~2 traces per word, 372 shared words and 94 % single corrections,
   the pool offers **≤ 11 fixable traces per direction**. Clearing errRatio < 0.2 then allows
   ≤ 2 breaks, and the per-word 1 % bar needs ≥ 100 carriers per lifted word, which almost
   none have. The real user types `git` 92 times. This pool cannot simulate that
   repetition, which is the regime where correction evidence should pay off.
3. **Counter-evidence is missing from both rules.** Every time the user swipes X and KEEPS it
   while Y was in the beam, that is evidence against the pair X→Y. Neither arm uses it, and
   the §5b pair blast is exactly the cost of ignoring it.

Directions (NOT built, NOT measured):
- **Pair evidence with counter-evidence**: `lift(Y | top1 = X)` scaled by
  `c(X→Y) / (c(X→Y) + kept(X | Y in beam))`, so a user who types both words self-limits.
  It needs the decoder to expose the base top-1 at final scoring (a two-pass final loop in
  `CtcBeamDecoder`, cheap: ≤ beamWidth). It also needs a store keyed by pairs.
- **A per-user replay corpus**: on-device logging of (trace features, auto-inserted X,
  kept/replaced) under the learning gate, so the bar can be measured on repetition that
  actually exists. That is the only way to measure this family honestly.

## 7. Limitations that must travel with these numbers

1. **The trace pool is not one user.** Its repetition structure (~2 traces/word) understates
   fixes for a real user and makes the per-word 1 % bar unmeasurable for most words.
2. **Corrections are simulated as "every lexicon-target error, kept with probability r"**,
   with changed-mind noise as a separate arm. A real user corrects selectively, e.g. more
   often on long words, and retypes rather than re-swipes. That behaviour is not modelled.
3. **No decay was measured.** The pool has no time axis, so `WordEvidence.corrections` is a
   plain count here. Decay is left to the wiring (§8) and is unevaluated.
4. **One language (en), one lexicon scale (λ = 4.0).** The CKDT languages (λ = 2.0) are
   unmeasured.
5. **`CtcEngineAdapter` display overlays (contractions) are not applied.** All targets are a–z.
6. **Wall clock is not latency.** Load average was 10–12 on 4 cores; the decode took about
   200 s.

## 8. Reproduce

```sh
# full replay (both arms, both directions, adversarial) + the git pin (~4 min decode)
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.CorrectionPriorReplayTest -PgeoFull=true
# pin only (ORT natives, no corpus)
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.CorrectionPriorReplayTest
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.ctc.CtcLearnedPriorTest
```

The corpus is never committed: `~/.cache/cleverkeys-test/combined_english_swipes.jsonl.gz`.
