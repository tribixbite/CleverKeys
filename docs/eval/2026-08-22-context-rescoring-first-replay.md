# Context rescoring — replay results (CTC primary, geometric secondary)

**Run date**: 2026-08-26 · **Measured at commit**: `27eb1a11` · **Harness**:
`ContextRescoringReplayTest`
**Invocation**: `-PgeoFull=true -PreplayDecoys=10 -PreplayCorpus={device|ubuntu}`
**Engines**: **CTC (primary — the shipping default)** and geometric (secondary reference)
**Corpora**: the maintainer's device export (no eviction) AND Ubuntu Dialogue derived bigrams
**ONNX EP**: `xnnpack(2)`, mirroring `ModelLoader`

> **PIN THE COMMIT WHEN QUOTING THESE.** The shipped CTC decode path has moved twice under this
> evaluation already (§3). `CtcReplayEngineSmokeTest.slateShapeHasNotDrifted` now fails the build
> when it moves again, so a stale document should no longer be able to sit here unnoticed.
>
> **Every set of numbers published here before 2026-08-26 is retracted** — see §8.

---

## Verdict

**Do not flip `swipe_context_rescoring` on.** The pref stays default-OFF.

On **CTC — the engine that actually ships** — the feature does not clear the bar on *either*
corpus, and **no point in the (WEIGHT, R_MIN) grid rescues it on either**:

| CTC @ `27eb1a11` | device export | Ubuntu |
|---|---|---|
| fixed / broken | **0 / 6** | **3 / 2** |
| promotion-error ratio (bar: < 0.20) | **∞** ✗ | **0.667** ✗ |
| net Δtop-1 (bar: > 0) | −0.0016 ✗ | +0.0005 ✓ |
| tune → confirm sweep | no grid point clears the bar on tune | selected W=0.50 R=0.50 → **confirm 1 fixed / 2 broken, `meetsBar=false`** |

The geometric arm, over identical samples, passes on both corpora *and* its tuned point survives
the held-out half — 35/0 (Ubuntu, confirm 26/0) and 22/0 (device, confirm 21/0), `meetsBar=true`
both times. **The two engines give opposite verdicts, and the one that ships is the failing one.**

The single strongest fact is on the device corpus — the only one whose activation rate is real,
because 6,589 pairs fit under `BigramStore`'s 10,000 cap with **zero eviction** (262 of 262 pairable
pairs queryable): rescoring fixed **nothing at all** (0 of 481 favourable cases) and broke 6.

Ubuntu's Δtop-1 is positive, so it clears one of the two gates — but the ship bar is **both**
conditions, and its error ratio of 0.667 is more than three times the 0.20 limit. Its own tuned
point then *lost* on held-out traces (2 fixed / 0 broken on tune → 1 fixed / 2 broken on confirm).

---

## 1. Numbers

### 1.1 Device export — the corpus with no eviction

```
corpus     : device_bigrams.json  total=6589 usable=642 (9.7%) promotable=603 STORED=6589
             nothing evicted: 6,589 < the 10,000 cap, no word1 exceeds the 20/word cap
trace pool :  2197 distinct words with a usable trace
pairable   :   262 of 642 usable      queryable: 262  (100 % survival — not an artefact)
cases      :  3744 (context, trace, arm)          [NOT "swipes" — see §6]

─── PRIMARY — CTC ─────────────────────────────────────────────────────────────
exposure   : favourable=477  adversarial=444   cases
             favourable=172/176  adversarial=107/926   DISTINCT TRACES
             8 of the 444 adversarial-exposed have evidence ON their own target
             (favourable-in-fact, cannot break) -> honest break denominator 436
baseline   : engine top-1 ALREADY correct on 469/481 favourable, 2465/3263 adversarial

favourable : n=481   fixed=0  broken=0  wash=0   unchanged=481   Δtop1=+0.0000
adversarial: n=3263  fixed=0  broken=6  wash=10  unchanged=3247  Δtop1=-0.0018
COMBINED   : n=3744  fixed=0  broken=6  wash=10  Δtop1=-0.0016   errRatio=INFINITE

per-exposure: fixes 0/477 = 0.00 %   breaks 6/436 = 1.38 %  (S1-corrected denominator)
concentration: 0 fixes; 6 breaks from 1 DISTINCT TRACE / 1 word (war x6) — see §3
examples    : 'there'+swipe(war): war->was; 'is'+swipe(war): war->was; ... (all 6 are this trace)

why rank 1 did or did not move, over 921 exposed cases:
   evidence on engine top-1 only, nothing to promote :  561
   contender below R_MIN=0.5 x top-1 (un-promotable) :  255
   cleared ratio, failed strict evidence floors      :    2   <-- NOT zero; see §4
   cleared BOTH rank-1 guards                        :  103
slate shape : runner-up/top-1 median=0.261 p90=0.847; 965/3744 (25.8 %) within factor of two
apostrophe gap: 2 cases
SWEEP       : no grid point clears the ship bar on the tune half — nothing to confirm

─── SECONDARY — geometric ─────────────────────────────────────────────────────
exposure   : favourable=389  adversarial=32 cases
baseline   : already correct on 324/481 favourable, 1673/3250 adversarial
favourable : n=481   fixed=22  broken=0  unchanged=459   Δtop1=+0.0457
adversarial: n=3250  fixed=0   broken=0  wash=1          Δtop1=+0.0000
COMBINED   : n=3731  fixed=22  broken=0  Δtop1=+0.0059   errRatio=0.000
per-exposure: fixes 22/389 = 5.66 %   breaks 0/25 = 0.00 %
cleared ratio, failed strict evidence floors: 7   <-- see §4
concentration: 22 fixes from 11 distinct traces; 0 breaks
slate shape : median=0.720 p90=0.957; 3088/3731 (82.8 %) within factor of two
SWEEP       : SELECTED on tune W=1.00 R=0.50 (10 fixed, 0 broken)
              CONFIRM (held out): fixed=21 broken=0 errRatio=0.000  meetsBar=TRUE
```

### 1.2 Ubuntu Dialogue — the larger, store-capped corpus

```
corpus     : ubuntu_bigrams.json  total=175092 usable=56923 promotable=22586 STORED=10000
             <-- the store cap discarded 46,923 usable rows
trace pool :  2197 distinct words with a usable trace   [was misreported as 4,907; §8 H9]
pairable   : 21392 of 56923       queryable: 133  (a store-cap artefact, NOT a device property)
cases      :  1891 (context, trace, arm)

─── PRIMARY — CTC ─────────────────────────────────────────────────────────────
exposure   : favourable=223 adversarial=119 cases; 109/115 and 35/673 distinct traces
             0 of the adversarial-exposed have evidence on their own target
baseline   : already correct on 220/229 favourable, 1350/1662 adversarial
favourable : n=229   fixed=3  broken=0  unchanged=226   Δtop1=+0.0131
adversarial: n=1662  fixed=0  broken=2  wash=26         Δtop1=-0.0012
COMBINED   : n=1891  fixed=3  broken=2  wash=26  Δtop1=+0.0005  errRatio=0.667
per-exposure: fixes 3/223 = 1.35 %   breaks 2/119 = 1.68 %
concentration: 3 fixes from 3 distinct traces; 2 breaks from 2 distinct traces (tuner, war)
decomposition over 342 exposed: topOnly 245, belowRatio 66, floors 0, clearedBoth 31
slate shape : median=0.244 p90=0.777; 422/1891 (22.3 %) within factor of two
SWEEP       : SELECTED on tune W=0.50 R=0.50 (2 fixed, 0 broken)
              CONFIRM (held out): fixed=1 broken=2 errRatio=2.000  meetsBar=FALSE

─── SECONDARY — geometric ─────────────────────────────────────────────────────
exposure   : favourable=192 adversarial=3 cases; 92/115 and 3/665 distinct traces
baseline   : already correct on 151/229 favourable, 936/1639 adversarial
favourable : n=229   fixed=35 broken=0  unchanged=194   Δtop1=+0.1528
adversarial: n=1639  fixed=0  broken=0  wash=1          Δtop1=+0.0000
COMBINED   : n=1868  fixed=35 broken=0  Δtop1=+0.0187   errRatio=0.000
per-exposure: fixes 35/192 = 18.23 %   breaks 0/3 = 0.00 %   (3 exposures bounds nothing)
concentration: 35 fixes from 16 distinct traces; 0 breaks
slate shape : median=0.717 p90=0.951; 1495/1868 (80.0 %) within factor of two
SWEEP       : SELECTED on tune W=0.50 R=0.50 (9 fixed, 0 broken)
              CONFIRM (held out): fixed=26 broken=0 errRatio=0.000  meetsBar=TRUE
```

## 2. Why the two engines disagree

The rescorer is engine-agnostic — it sits at `SuggestionHandler.handleSwipePredictionResults`,
which both engines pass through. What differs is the base rates.

| | CTC (device) | geometric (device) |
|---|---|---|
| favourable cases the engine got right unaided | 469/481 = **97.5 %** | 324/481 = 67.4 % |
| **fix headroom** | **12 cases** | 157 |
| fixes achieved | **0** | 22 |
| adversarial cases it had gotten right (what there is to lose) | 2465 | 1673 |
| broken | **6** | **0** |

**CTC is too accurate for this feature to help it.** Its benefit ceiling on the device corpus is
2.5 % of favourable cases — the 12 it got wrong — and it captured none of them. Geometric had 157
repairable cases and captured 22. The mechanism is not worse on CTC; there is almost nothing left
for it to repair, while the damage surface is 2,465 already-correct decodes.

## 3. The decode path moved twice under this evaluation

This is the most important methodological fact in the document, and the reason §9's canary exists.

| slate shape, CTC device arm | median ratio | within guard's 2× |
|---|---|---|
| before `20d620f4` | 0.261 | 25.8 % |
| `20d620f4` — fuzzy rescue inserted at **rank two**, scored `topScore / 2` | **0.500** | **54.1 %** |
| `c83d6ff2` — rescue moved **below** the beam (CK-150-025) | **0.261** | **25.8 %** |

The middle row was published by the previous revision of this document as though it described a
stable decoder. It did not. `20d620f4` roughly doubled the share of slates the rank-1 guard would
permit a promotion into, and the previous revision's central explanation — that CTC is protected
because its slates are peaked and runners-up are "arithmetically un-overturnable" — was false of
shipping code for as long as that commit stood.

`c83d6ff2` reverted it exactly: both corpora returned to their pre-`20d620f4` figures to the case
(Ubuntu 3 fixed / 2 broken; device 0 / 6). It also fixed a defect neither this evaluation nor its
adversarial audit caught: the old clamp **inverted** when top-1 and the runner-up were close —
`[800, 800]` produced a rescued score of 801, *above rank one*. The audit compared the replay's copy
of that logic against the shipped copy, found them identical, and called it faithful. Checking that
a mirror matches is not checking that the thing it mirrors is correct.

Two structural improvements came out of it, and both are worth keeping:

- **The hand-copy is gone.** `CtcFuzzyRescue.mergeIntoBeam` is one pure function called by both
  `CtcEngineAdapter` and `CtcReplayEngine`, so that class of drift cannot recur.
- **The drift is now a red build** — §9.

### The concentration story is corpus-specific, not general

The revision before last built a section on all 24 breakages being one trace (`tit → to`). That
pile-up was an artefact of defect H9 (§8). At HEAD the device arm still concentrates — **6 breaks,
1 trace (`war → was`)** — but Ubuntu does not: **2 breaks, 2 distinct traces**. So concentration is
a property of the corpus and sample, not a law. The *lesson* stands unconditionally: report a count
with its concentration, or a reader cannot tell N failures from one failure repeated N times.

## 4. The strict evidence floors DO bind — on real device data

An earlier revision reported `cleared ratio, failed strict evidence floors = 0` on both engines and
concluded rank-1 protection was "the ratio guard alone". **That generalised from one corpus and was
wrong.** On the device corpus the floors bind: **2** cases on CTC and **7** on geometric were
stopped by `promotableToRankOne` after clearing the ratio test. On Ubuntu the count really is 0,
because its stored 10,000 are near-maximal-probability pairs that pass the floors by construction.

Vacuous on a saturated store, load-bearing on a real thin one. Keeping them is vindicated.

## 5. What is a sampling choice and what is not

**Sampling-dependent — do not quote as a real-world expectation:** `COMBINED`, its `Δtop1` and
`errRatio`; the printed break-even ratios. All are functions of the favourable:adversarial mix,
which `-PreplayDecoys` sets.

**Ratio-independent — these carry the verdict:**

- CTC's fix headroom is 12 of 481 on device data, and it captured **zero**.
- The **direction** reverses between engines on both corpora.
- **No grid point clears the bar on device, and Ubuntu's best point loses on held-out traces.** A
  parameter sweep with a held-out half is not a sampling choice.

**Still not measurable here**: the real favourable:adversarial exposure ratio. Only on-device shadow
mode (spec §7.2) can supply it.

## 6. Units — read this before quoting any figure

`n` counts **(context, trace, arm) cases**, not swipes; the same trace appears under several
contexts and those are separate experiments because context is the independent variable. Every
outcome count is reported beside its **distinct-trace** count (§8 H8).

## 7. Limitations that must travel with these numbers

1. **The decode path is a moving target.** Pinned to `27eb1a11`. §9's canary now fails the build on
   drift, but a red canary means *re-baseline*, not "the decoder is broken".
2. **The adversarial arm cannot be powered by sampling harder — measured, not assumed.** See §9.1.
   The exposable trace population is nearly exhausted at ~111 distinct traces, of which exactly
   **one** breaks. Ubuntu geometric is worse: **3** exposed traces total. This is the binding
   statistical limit, and `-PreplayMaxCtx` does not lift it.
3. **Adversarial decoys are selected for confusability**, so the arm over-represents the damage
   surface by construction.
4. **The two engines use their own shipping lexicons** — CTC the EN_JSON strip-loaded
   `en_enhanced.json`, geometric the CKDT binary. The right confound to keep: the comparison is
   between shipping configurations.
5. **`CtcEngineAdapter`'s display overlays are not applied** (they need an Android `Context`).
   Measured cost: 2 cases (device), 0 (Ubuntu). Contraction aliases and fuzzy rescue ARE mirrored —
   the latter by calling the shipped function, not a copy.
6. **The trace filter is a heuristic**: 14 of the 4,064 pre-resampled 128-point rows pass it
   (0.16 % residual).
7. **Bigrams only** — trigrams are excluded from the export, so measured gain is a floor. On CTC the
   binding constraint is the 12-case headroom, which trigrams cannot enlarge.
8. **Ubuntu's 133/21,392 survival rate is a store-cap artefact.** The device corpus exists to avoid
   that confound.
9. **One language.**

## 8. Retractions — what was published here and why it was wrong

**All numbers published before 2026-08-26 are retracted.** Do not cite "29 fixed", "51 fixed",
"3 fixed / 24 broken", "errRatio 8.0", "0 / 9", "21 % when it fires", or "inert on 97 % of swipes".

Headline sequence: **29 → 2 → 51 → 3/24 → 0/9 device + 4/4 Ubuntu → (all retracted) →
0/6 device, 3/2 Ubuntu.**

| # | Error | Effect | Fix |
|---|---|---|---|
| H1 | `take(1500)` on a **frequency-sorted** file — a size cap that was silently a *selection* | measured only the extreme head | shuffle with the seed |
| H2 | Hapax rows dropped **before** seeding | probabilities ~doubled | emit the tail |
| H3 | Denominator labelled "swipes" when it was context-trace cases | wrong unit | label correctly (§6) |
| H4 | One global `withEvidence` counter | safety denominator inferred across runs | per-arm counters |
| H5 | **47.1 % of the trace corpus is not raw traces** — 4,050 of 8,607 rows fail `hasUsableTimestamps`, every one exactly 128 points | inflated denominators | `TraceCorpusQuality`; smoke 19/40 → 38/40 |
| H6 | `neighboursOf` took the first N in **corpus-file order** — H1 one level down | arbitrary adversarial slice | seeded per-word shuffle |
| H7 | **The geometric engine was measured and the default engine was not** | published verdict was the opposite of the default engine's | CTC is primary; the harness *skips* rather than reporting geometric-only |
| H8 | **Counts without their concentration**, and exposure denominators inflated by the same multiplicity | overstated harm AND its power | distinct-trace counts on outcomes and exposure |
| H9 | **`loadTraces` created each word's bucket BEFORE filtering**, so every distinct word kept a key even when all its traces were rejected. Pool printed 4,907 vs **2,197** real; `pairable` counted zero-trace bigrams; `neighboursOf` drew decoys from phantom keys that contributed nothing | two inflated denominators, a silently undersized adversarial arm, **and the `tit`×24 pile-up a whole section rested on** | create the bucket only after a row survives every filter |
| H10 | **A false fidelity claim** — the harness built its English trie with `CtcAzProjection` (accent-folding) while shipping uses `loadStrippingNonAlphabet`; plus truncated scores and a different ONNX EP | none measurable on these outcome words, but exactly the class H1–H9 belong to | shipped strip loader; `roundToInt().coerceIn`; XNNPACK-first |
| H11 | **The shipped decoder changed under the measurement** — `20d620f4`, then `c83d6ff2` reverting it | the published *explanation* was false of shipping code | numbers pinned to a commit; **drift canary** (§9) |
| H12 | **Generalising "the strict floors never bind" from one corpus** | a load-bearing guard described as vacuous | §4 |
| **H13** | **Auditing a mirror instead of the thing mirrored.** The adversarial audit verified the replay's fuzzy-rescue copy matched `CtcEngineAdapter` exactly and passed it. The shipped logic was itself defective — its clamp inverted at `[800, 800]`, scoring a rescue *above* rank one. Fidelity was confirmed; correctness was never asked. | a real product defect sat unflagged through a dedicated audit | fixed in `c83d6ff2` by its owner; recorded here so the audit question changes from "does it match?" to "is it right?" |

**The lesson.** This harness has produced numbers that looked fine and were not **nine** separate
times, and not once did a run fail to signal it. Four distinct silent-failure modes are now on
record: a decoder returning plausible nonsense; a harness measuring the wrong arm; a count without
its concentration; and a dependency changing underneath a published result. Every counter and
canary that catches one of these should be treated as load-bearing, not redundant.

## 9. The drift canary

`CtcReplayEngineSmokeTest.slateShapeHasNotDrifted` pins the fraction of slates whose runner-up sits
at or above `R_MIN × top-1` — the rank-1 guard's own precondition, and the quantity this entire
evaluation is a function of. Measured **6/40 = 0.150** at `27eb1a11` (median ratio 0.164), band
±0.10. For scale, `20d620f4` moved the comparable figure to ~0.54.

A failure does **not** mean the decoder is broken. It means these numbers are stale: re-run the
replay, re-baseline this document, then update the constant *with the commit that moved it*.

It needs the never-committed local trace pool, so it skips on a fresh checkout. That is a real
limitation, accepted because the evaluation is written on a machine that has the corpus — the canary
guards the place the stale numbers would actually be quoted.

## 9.1 The power run — a negative result worth keeping

The previous revision proposed capping contexts per adversarial trace (`-PreplayMaxCtx=N`) to
"convert multiplicity into diversity at the same decode cost", and named it the remedy for the
harness's binding statistical limit. **It was run on 2026-08-26 and it does not work.**

```
device corpus, CTC        decoys=10 maxCtx=off   decoys=30 maxCtx=2
distinct adversarial traces        926                   1615   (+689)
  ...of those, EXPOSED             107                    111   (+4)
exposure per distinct trace      11.6 %                  6.9 %
adversarial exposed cases          444                    145   (multiplicity removed)
breaks                        6, from 1 trace        2, from 1 trace
```

Tripling the decoy budget bought 689 more distinct adversarial traces and **four** more exposed
ones. The bottleneck was never multiplicity: it is that a trace is only exposed when its slate
happens to contain a learned continuation of the context word, and the set of trace-pool words for
which that is true — against this corpus — is **small and now nearly enumerated at ~111**.

So the arm cannot be powered by sampling harder, and the honest statement of the safety evidence is:
**1 of ~111 exposed distinct adversarial traces breaks (~0.9 %)**, which is at least now a rate in a
coherent unit rather than "6 breaks" that were one trace counted six times.

What *would* add power is a different decoy rule: draw decoys confusable with **any hub
continuation** — the function words (`the`, `to`, `a`, `and`, `it`) that are learned continuations
of a large share of preceding words — rather than only with this pair's own `word2`. That targets
the damage surface directly instead of sampling around it. Not built; recorded so the next attempt
does not repeat this one.

## 10. What would make this decisive — OVERTAKEN BY DECISION

> **2026-08-26, maintainer: the investigation is CLOSED.** `swipe_context_rescoring` stays
> default-OFF permanently and shadow mode will not be built. The learned-context data's consumer
> is **next-word prediction** (tap-to-accept; audited and gate-hardened the same day —
> `b9355be1`, `ececaa73`) rather than swipe rescoring (silent auto-insert). The list below is
> retained only for anyone who ever reopens the question.

1. **On-device shadow mode (spec §7.2)** — the only lever this document identified as remaining.
   §9.1 closes off the offline route to more adversarial power, and §5 already showed the
   exposure ratio is not measurable here.
2. **A hub-confusable decoy rule** (§9.1) if an offline safety bound is still wanted.
3. **A second language.** Everything here is `en`.

## 11. Reproduce

```sh
# device arm — seeds in seconds (7,815 recordBigram calls), no eviction
sh gradlew runPureTests -PtestClass=swipe.ContextRescoringReplayTest \
  -PgeoFull=true -PreplayCorpus=device -PreplayDecoys=10

# ubuntu arm — ~21 min of seeding (1,285,947 recordBigram calls)
sh gradlew runPureTests -PtestClass=swipe.ContextRescoringReplayTest \
  -PgeoFull=true -PreplayCorpus=ubuntu -PreplayDecoys=10
```

`LearnedBigramCorpus.seed` deliberately replays `recordBigram` once per observation so the store's
real floors, caps and probability recomputation apply exactly as on a device;
`BigramStore.pruneIfNeeded` then runs a full 10k sort-and-rebuild on every call past the cap, which
is the entire Ubuntu seeding cost. Both engines run in one process so it is paid once.

Knobs: `-PreplayPairs`, `-PreplayDecoys`, `-PreplayTraces`, `-PreplayMaxCtx`, `-PreplayCorpus`. The
report prints whichever values it ran with, so every figure carries its own sample size.

Neither corpus is committed: one is a person's typing record, the other a 552 MB third-party corpus
with no stated licence. Place them at `~/.cache/cleverkeys-corpora/{device,ubuntu}_bigrams.json`.
