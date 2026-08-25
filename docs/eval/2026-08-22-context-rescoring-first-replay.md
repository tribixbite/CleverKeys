# Context rescoring — replay results (CTC primary, geometric secondary)

**Run date**: 2026-08-24 · **Measured at commit**: `6b3b8bb9` · **Harness**:
`ContextRescoringReplayTest`
**Invocation**: `-PgeoFull=true -PreplayDecoys=10 -PreplayCorpus={device|ubuntu}`
**Engines**: **CTC (primary — the shipping default)** and geometric (secondary reference)
**Corpora**: the maintainer's device export (no eviction) AND Ubuntu Dialogue derived bigrams
**ONNX EP**: `xnnpack(2)`, mirroring `ModelLoader`

> **PIN THE COMMIT WHEN QUOTING THESE.** The shipped CTC decode path is a moving target: `20d620f4`
> added `CtcFuzzyRescue` *after* the previous revision of this document was measured, and it
> changed the slate shape enough to invalidate that revision's central explanation. See §3.
>
> **Every set of numbers published here before 2026-08-24 is retracted** — see §8.

---

## Verdict

**Do not flip `swipe_context_rescoring` on.** The pref stays default-OFF.

On **CTC — the engine that actually ships** — the feature does not clear the bar on *either*
corpus, and **no point in the (WEIGHT, R_MIN) grid rescues it on either**:

| CTC @ `6b3b8bb9` | device export | Ubuntu |
|---|---|---|
| fixed / broken | **0 / 9** | **4 / 4** |
| promotion-error ratio (bar: < 0.20) | **∞** ✗ | **1.000** ✗ |
| net Δtop-1 (bar: > 0) | −0.0024 ✗ | **+0.0000** ✗ |
| tune/confirm sweep | **no grid point clears the bar** | **no grid point clears the bar** |

The geometric arm, over the identical samples, passes on both corpora *and* its tuned point
survives the held-out half — 35 fixes / 0 breaks (Ubuntu) and 22 / 0 (device), with confirm-half
`meetsBar=true` in both. **The two engines still give opposite verdicts, and the one that ships is
the failing one.**

The strongest single fact is on the device corpus — the only one whose activation rate is real,
because 6,589 pairs fit under `BigramStore`'s 10,000 cap with **zero eviction**: rescoring fixed
**nothing at all** (0 of 481 favourable cases) and broke 9.

---

## 1. Numbers

### 1.1 Device export — the corpus with no eviction

```
corpus     : device_bigrams.json  total=6589 usable=642 (9.7%) promotable=603 STORED=6589
             nothing evicted: 6,589 < the 10,000 cap and no word1 exceeds the 20/word cap
pairable   :  262 of 642 usable        queryable: 262  (100% survival — not an artefact)
cases      : 3744 (context, trace, arm)          [NOT "swipes" — see §6]

─── PRIMARY — CTC ─────────────────────────────────────────────────────────────
exposure   : favourable=476  adversarial=452   cases
             favourable=171/176  adversarial=113/926   DISTINCT TRACES
             8 of the 452 adversarial-exposed have evidence ON their own target
             (favourable-in-fact, cannot break) -> honest break denominator 444
baseline   : engine top-1 ALREADY correct on 469/481 favourable, 2465/3263 adversarial

favourable : n=481   fixed=0  broken=0  wash=0   unchanged=481   Δtop1=+0.0000
adversarial: n=3263  fixed=0  broken=9  wash=12  unchanged=3242  Δtop1=-0.0028
COMBINED   : n=3744  fixed=0  broken=9  wash=12  Δtop1=-0.0024   errRatio=INFINITE

per-exposure: fixes 0/476 = 0.00 %   breaks 9/444 = 2.03 %  (S1-corrected denominator)
concentration: 0 fixes; 9 breaks from 3 distinct traces / 3 words (war x6, ins x2, rest x1)
examples    : 'there'+swipe(war): war->was; 'ita'+swipe(ins): ins->its; 'rew'+swipe(rest): rest->res

why rank 1 did or did not move, over 928 exposed cases:
   evidence on engine top-1 only, nothing to promote :  559
   contender below R_MIN=0.5 x top-1 (un-promotable) :  237
   cleared ratio, failed strict evidence floors      :    4   <-- NOT zero; see §4
   cleared BOTH rank-1 guards                        :  128
slate shape : runner-up/top-1 median=0.500 p90=0.847; 2024/3744 (54.1 %) within factor of two
apostrophe gap: 3 cases
SWEEP       : no grid point clears the ship bar on the tune half — nothing to confirm

─── SECONDARY — geometric ─────────────────────────────────────────────────────
exposure   : favourable=389  adversarial=32 cases; 143/176 and 26/919 distinct traces
baseline   : already correct on 324/481 favourable, 1673/3250 adversarial
favourable : n=481   fixed=22  broken=0  unchanged=459   Δtop1=+0.0457
adversarial: n=3250  fixed=0   broken=0  wash=1          Δtop1=+0.0000
COMBINED   : n=3731  fixed=22  broken=0  Δtop1=+0.0059   errRatio=0.000
per-exposure: fixes 22/389 = 5.66 %   breaks 0/25 = 0.00 %
concentration: 22 fixes from 11 distinct traces; 0 breaks
slate shape : median=0.720 p90=0.957; 3088/3731 (82.8 %) within factor of two
SWEEP       : SELECTED on tune W=1.00 R=0.50 (10 fixed, 0 broken)
              CONFIRM (held out): fixed=21 broken=0 errRatio=0.000  meetsBar=TRUE
```

### 1.2 Ubuntu Dialogue — the larger, store-capped corpus

```
corpus     : ubuntu_bigrams.json  total=175092 usable=56923 promotable=22586 STORED=10000
             <-- the store cap discarded 46,923 usable rows
trace pool :  2197 distinct words with a usable trace   [was misreported as 4,907; see §8 H9]
pairable   : 21392 of 56923       queryable: 133  (a store-cap artefact, NOT a device property)
cases      :  1891 (context, trace, arm)

─── PRIMARY — CTC ─────────────────────────────────────────────────────────────
exposure   : favourable=224 adversarial=118 cases; 110/115 and 36/673 distinct traces
             0 of the adversarial-exposed have evidence on their own target
baseline   : already correct on 220/229 favourable, 1350/1662 adversarial
favourable : n=229   fixed=4  broken=0  unchanged=225   Δtop1=+0.0175
adversarial: n=1662  fixed=0  broken=4  wash=29         Δtop1=-0.0024
COMBINED   : n=1891  fixed=4  broken=4  wash=29  Δtop1=+0.0000  errRatio=1.000
per-exposure: fixes 4/224 = 1.79 %   breaks 4/118 = 3.39 %
concentration: 4 fixes from 4 distinct traces; 4 breaks from 4 distinct traces
               (tuner, thy, war, ins — one each; NO concentration this time, cf. §3)
examples    : FIX 'self-help'+swipe(advice): addie->advice
              BREAK 'letme'+swipe(thy): thy->try; 'buts'+swipe(ins): ins->its
decomposition over 342 exposed: topOnly 245, belowRatio 58, floors 0, clearedBoth 39
slate shape : median=0.500 p90=0.777; 1031/1891 (54.5 %) within factor of two
SWEEP       : no grid point clears the ship bar on the tune half — nothing to confirm

─── SECONDARY — geometric ─────────────────────────────────────────────────────
favourable : n=229   fixed=35 broken=0  unchanged=194   Δtop1=+0.1528
adversarial: n=1639  fixed=0  broken=0  wash=1          Δtop1=+0.0000
COMBINED   : n=1868  fixed=35 broken=0  Δtop1=+0.0187   errRatio=0.000
per-exposure: fixes 35/192 = 18.23 %   breaks 0/3 = 0.00 %   (3 exposures bounds nothing)
concentration: 35 fixes from 16 distinct traces; 0 breaks
slate shape : median=0.717 p90=0.951; 1495/1868 (80.0 %) within factor of two
SWEEP       : SELECTED on tune W=0.50 R=0.50 (9 fixed, 0 broken)
              CONFIRM (held out): fixed=26 broken=0 errRatio=0.000  meetsBar=TRUE
```

**Self-check**: on every arm, `cleared BOTH rank-1 guards` equals `fixed + broken` plus the
promotions the log-linear sum left in place. The decomposition is computed from the shipped guard's
own public predicates (`R_MIN`, `promotableToRankOne`), not a restatement of them.

## 2. Why the two engines disagree

The rescorer is engine-agnostic — it sits at `SuggestionHandler.handleSwipePredictionResults`,
which both engines pass through. What differs is the base rates.

| | CTC (device) | geometric (device) |
|---|---|---|
| favourable cases the engine got right unaided | 469/481 = **97.5 %** | 324/481 = 67.4 % |
| **fix headroom** | **12 cases** | 157 |
| fixes achieved | **0** | 22 |
| adversarial cases it had gotten right (what there is to lose) | 2465 | 1673 |
| broken | **9** | **0** |

**CTC is too accurate for this feature to help it, and now too exposed for it to be safe.** Its
benefit ceiling on the device corpus is 2.5 % of favourable cases (the 12 it got wrong) and it
captured **none** of them. Geometric had 157 repairable cases and captured 22.

## 3. What changed under this measurement — and why the old explanation is dead

The previous revision explained CTC's safety by **slate peakedness**: median runner-up/top-1 of
0.254, only ~24 % of slates with a runner-up inside the guard's factor of two, therefore most
promotions "arithmetically un-overturnable" (spec §5).

**That is no longer true of the shipping code.** Commit `20d620f4` added `CtcFuzzyRescue` and
`CtcEngineAdapter.applyFuzzyRescue`, which inserts a bounded dictionary match at rank 2 with a
synthesised score of `max(secondScore + 1, topScore / 2)`. Measured effect:

| slate shape, CTC | before `20d620f4` | at `6b3b8bb9` |
|---|---|---|
| median runner-up / top-1 | 0.254 | **0.500** |
| slates with runner-up inside the guard's factor of two | 24.0 % | **54.1 %** |

The smoke test shows it directly: `'what'` decoded `[what, whats, wheat] = [884, 28, 28]` before,
and `[what, wat, wha] = [884, 442, 441]` now. **The rescued candidate lands exactly on the
promotion threshold** — `442 >= 0.5 × 884`.

### A parity knife-edge in the shipped guard

`topScore / 2` is *integer* division, and the guard tests `scores[i] >= R_MIN * scores[0]` in
floating point. So:

- top-1 = 884 (even) → rescue scored 442, and `442 >= 442.0` → **promotable**
- top-1 = 913 (odd) → rescue scored 456, and `456 >= 456.5` → **blocked**

Whether context rescoring can overturn a fuzzy-rescued candidate currently depends on the
**parity of the top-1 score**. That is shipped behaviour, not a harness artefact, and it is worth a
look by whoever owns `applyFuzzyRescue` — it is exactly the knife-edge the audit's D3 rounding
finding warned about, one layer up.

### The concentration story is also obsolete

The previous revision's §3 was built on all 24 breakages being a single trace (`tit → to`). With
the H9 empty-bucket defect fixed (§8), the decoy pool changed and that pile-up disappeared: Ubuntu
now shows **4 breaks from 4 distinct traces**, device **9 from 3**. The *lesson* stands — always
report a count with its concentration — but the finding it produced does not.

## 4. The strict evidence floors DO bind — on real device data

The previous revision reported `cleared ratio, failed strict evidence floors = 0` on both engines
and concluded that rank-1 protection was "the ratio guard alone". **That generalised from one
corpus and was wrong.** On the device corpus the floors bind: **4** cases on CTC and **7** on
geometric were stopped by `promotableToRankOne` after clearing the ratio test.

The floors are vacuous when the store holds 10,000 near-maximal-probability pairs (Ubuntu, where
the count really is 0) and load-bearing on a real, thin, mostly-hapax store. Keeping them is
vindicated; the earlier conclusion is retracted.

## 5. What is a sampling choice and what is not

**Sampling-dependent — do not quote as a real-world expectation:** `COMBINED`, its `Δtop1` and
`errRatio`; the printed break-even ratios. All are functions of the favourable:adversarial mix,
which `-PreplayDecoys` sets.

**Ratio-independent — these carry the verdict:**

- CTC's fix headroom is 12 of 481 on device data, and it captured **zero**.
- The **direction** reverses between engines on both corpora, under both counting units.
- **No grid point clears the bar on either corpus.** A parameter sweep is not a sampling choice.

**Still not measurable here**: the real favourable:adversarial exposure ratio. Only on-device
shadow mode (spec §7.2) can supply it.

## 6. Units — read this before quoting any figure

`n` counts **(context, trace, arm) cases**, not swipes; the same trace appears under several
contexts and those are separate experiments because context is the independent variable. Every
outcome count is reported beside its **distinct-trace** count, because a count without its
concentration cannot distinguish N independent failures from one repeated N times (§8 H8).

## 7. Limitations that must travel with these numbers

1. **The decode path is a moving target.** These numbers are pinned to `6b3b8bb9`. A change to the
   beam, the lexicon, or `applyFuzzyRescue` invalidates them — as `20d620f4` already did once.
2. **Neither adversarial arm bounds a breakage rate.** Device CTC: 113 distinct exposed adversarial
   traces, 3 broke. Ubuntu geometric: **3** exposed traces. That is the binding statistical limit.
3. **Adversarial decoys are selected for confusability**, so the arm over-represents the damage
   surface by construction. It answers "when a confusable competitor exists, what happens".
4. **The two engines use their own shipping lexicons** — CTC the EN_JSON strip-loaded
   `en_enhanced.json`, geometric the CKDT binary. They do not share a candidate set. This is the
   right confound to keep: the comparison is between shipping configurations.
5. **`CtcEngineAdapter`'s display overlays are not applied** (they need an Android `Context`).
   Measured cost: 0 cases (Ubuntu), 3 cases (device). Contraction alias keys and fuzzy rescue ARE
   now mirrored in the replay; see `CtcReplayEngine`'s exhaustive fidelity list.
6. **The trace filter is a heuristic**: 14 of the 4,064 pre-resampled 128-point rows carry a
   monotonic third column and pass it (0.16 % residual).
7. **Bigrams only** — trigrams are excluded from the export, so measured gain is a floor. On CTC
   the binding constraint is the headroom, which trigrams cannot enlarge.
8. **Ubuntu's 133/21,392 survival rate is a store-cap artefact**, not a device property. The device
   corpus exists precisely to avoid that confound.
9. **One language.**

## 8. Retractions — what was published here and why it was wrong

**All numbers published before 2026-08-24 are retracted.** Do not cite "29 fixed", "51 fixed",
"3 fixed / 24 broken", "errRatio 8.0", "21 % when it fires", or "inert on 97 % of swipes".

Headline sequence this document has carried:
**29 → 2 → 51 → 3 fixes/24 breaks → (all retracted) → 0/9 device, 4/4 Ubuntu.**

| # | Error | Effect | Fix |
|---|---|---|---|
| H1 | `take(1500)` on a **frequency-sorted** file — a size cap that was silently a *selection*. | measured only the extreme head | shuffle with the seed |
| H2 | Hapax rows dropped **before** seeding, shrinking the store's denominator | probabilities ~doubled | emit the tail |
| H3 | Denominator labelled "swipes" when it was context-trace cases | wrong unit | label correctly (§6) |
| H4 | One global `withEvidence` counter | safety denominator inferred across runs | per-arm counters |
| H5 | **47.1 % of the trace corpus is not raw traces** — 4,050 of 8,607 rows fail `hasUsableTimestamps`, and every one has exactly 128 points (pre-resampled elsewhere). Decoded to confident nonsense and padded denominators in every run before 2026-08-23. | inflated denominators | `TraceCorpusQuality` filter; smoke test 19/40 → 38/40 |
| H6 | `neighboursOf` took the first N matches in **corpus-file order** — H1 one level down | arbitrary adversarial slice | seeded per-word shuffle |
| H7 | **The geometric engine was measured and the default engine was not**, published with the limitation in prose while the headline read "benefit looks real" | published verdict was the opposite of the default engine's | CTC is the primary arm; the harness *skips* rather than reporting geometric-only |
| H8 | **Counts reported without their concentration**, and the exposure denominators inflated by the same multiplicity | overstated harm AND the power behind it | distinct-trace counts on outcomes and exposure |
| **H9** | **`loadTraces` created each word's bucket BEFORE filtering** (`getOrPut` then filter), so every distinct word in the file kept a key even when all its traces were rejected. Pool printed as 4,907 when only **2,197** words have a usable trace; `pairable` counted bigrams with zero traces; and `neighboursOf` drew decoys from those phantom keys, which contributed nothing — **so the adversarial arm silently ran below its requested decoy count**. | two inflated published denominators; **and the `tit`×24 pile-up that §3 was built on** | create the bucket only after a row survives every filter |
| **H10** | **A false fidelity claim**: the harness built its English trie with `CtcAzProjection` (accent-folding, the CKDT branch) while shipping uses `loadStrippingNonAlphabet` with none. 148 surfaces reachable only in shipping, 26 only in the harness, 45 at different frequencies. Also: truncating scores instead of rounding, and a different ONNX execution provider. | none measurable on these outcome words — but exactly the class of silent divergence H1–H9 belong to | shipped strip loader; `roundToInt().coerceIn`; XNNPACK-first mirroring `ModelLoader`; `build()` now rejects non-`en` |
| **H11** | **The shipped decoder changed under the measurement.** `20d620f4` added fuzzy rescue after the previous revision was measured, moving the median runner-up ratio 0.254 → 0.500 and invalidating that revision's entire "peakedness protects CTC" explanation. | the published *explanation* was false of shipping code, even where the verdict held | numbers are now pinned to a commit (§7.1) |
| **H12** | **Generalising "the strict floors never bind" from one corpus.** True on Ubuntu, false on device data (4 and 7 cases). | a guard was described as vacuous when it is load-bearing | §4 |

**The lesson.** This harness has now produced numbers that looked fine and were not **eight**
separate times. Not one was caught by a run failing. Every one was caught by an implausibility, an
adversarial audit, or a denominator added on purpose. Three distinct silent-failure modes are now
on record: a decoder that returns plausible nonsense, a harness that measures the wrong arm, and a
count without its concentration. None of the counters that catch them should be removed as
redundant.

## 9. What would make this decisive

1. **On-device shadow mode (spec §7.2)** — the only remaining question that matters, and the only
   way to measure the real exposure ratio (§5).
2. **Review `applyFuzzyRescue`'s interaction with `R_MIN`** (§3). Placing a synthesised candidate at
   exactly `topScore / 2` puts it on the guard's threshold, with the outcome decided by integer
   parity. Whether or not rescoring ships, that is worth deciding deliberately.
3. **An adversarial arm powered in DISTINCT TRACES.** `-PreplayMaxCtx=N` caps contexts per
   adversarial trace and converts multiplicity into diversity at the same decode cost; it has not
   yet been run.
4. **A second language.** Everything here is `en`.

## 10. Reproduce

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
The ONNX natives come from `extractOrtNative`, which unpacks the **bionic** arm64 `.so` from the
`onnxruntime-android` AAR — the `onnxruntime` JAR's own glibc-linked native cannot load on Termux,
which is why the CTC arm was once believed to need an instrumented run.
