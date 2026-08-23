# Context rescoring — replay results (CTC primary, geometric secondary)

**Run date**: 2026-08-23 · **Harness**: `ContextRescoringReplayTest`
**Invocation**: `-PgeoFull=true -PreplayDecoys=10` · **Language**: en
**Engines**: **CTC (primary — the shipping default)** and geometric (secondary reference)
**Corpus**: Ubuntu Dialogue derived bigrams · **Traces**: local English pool, timestamp-filtered

> The filename keeps its original `2026-08-22` date because this is the same document, rewritten.
> **Every set of numbers published here before 2026-08-23 is retracted** — see §8.

---

## Verdict

**Do not flip `swipe_context_rescoring` on.** The pref stays default-OFF.

On **CTC — the engine that actually ships** — rescoring fixed 3 decodes and broke 24, a
promotion-error ratio of **8.0** against a ship bar of **< 0.20**, with a negative net Δtop-1. The
geometric arm over the identical 1,726-case sample fixed 35 and broke 1 (ratio **0.029**) and would
pass. **The two engines give opposite verdicts, and until today only the one that does not ship had
ever been measured.**

Two qualifications that must travel with that, because the raw ratio overstates its own evidence:

- **All 24 CTC breakages are ONE trace** — the word `tit`, broken under 24 different preceding
  contexts, every time `tit → to`. Counted in distinct traces the CTC ratio is 3 fixes : 1 broken
  trace = **0.33**. Still a failure, but not 8.0. §3.
- **The conclusion survives both counting conventions.** CTC fails the bar as cases (8.0) and as
  distinct traces (0.33); geometric passes as cases (0.029) and as distinct traces (0.063). No
  choice of unit flips a verdict — which is what makes it safe to act on.

What is **solidly established** is the benefit side: CTC's top-1 was already correct on **220 of
229** favourable cases, so its entire fix headroom is **9 cases (3.9 %)** and rescoring recovered 3
of them. What is **not established** is the breakage *rate*: the adversarial arm holds only 32
distinct exposed traces on CTC and 7 on geometric.

---

## 1. Numbers

```
corpus     : ubuntu_bigrams.json
             lang=en total=175092 usable=56923 (32.5%) promotable=22586
             STORED=10000  <-- the store cap discarded 46923 usable rows
trace pool : 4907 distinct words (timestamp-filtered — see TraceCorpusQuality)
pairable   : 27970 of 56923 usable bigrams have a trace for their second word
queryable  :   225 of those survived the store's 10000-entry cap
sampled    :   225 at random (seed 20260822), NOT the frequency-sorted head
sampling   : pairs=225 decoys=10 tracesPerWord=2
cases      :  1726 distinct (context, trace, arm)   [NOT "swipes" — see §6]
canvas     : 360.0x215.0 aspect=1.674

─── PRIMARY — CTC (default engine) ────────────────────────────────────────────
decoded    : 1726 usable slates (0 too short to reorder)
exposure   : favourable=223  adversarial=158        cases
             favourable=109/115  adversarial=32/595 DISTINCT TRACES
baseline   : engine top-1 ALREADY correct on 220/229 favourable, 1200/1497 adversarial

favourable : n=229   fixed=3   broken=0   wash=0  unchanged=226   Δtop1=+0.0131
adversarial: n=1497  fixed=0   broken=24  wash=0  unchanged=1473  Δtop1=-0.0160
COMBINED   : n=1726  fixed=3   broken=24  wash=0  unchanged=1699  Δtop1=-0.0122  errRatio=8.000

concentration: 3 fixes from 3 distinct traces / 3 words
               24 breaks from 1 DISTINCT TRACE / 1 word   (tit x24)
example FIXES : 'propt'+swipe(comes): comers->comes; 'gml'+swipe(express): empress->express;
                'hol'+swipe(don): soon->don
example BREAKS: 'mind-mapping'+swipe(tit): tit->to; 'dmg-file'+swipe(tit): tit->to;
                'competitor'+swipe(tit): tit->to;  ... (all 24 are this trace)

why rank 1 did or did not move, over all 381 exposed cases:
   evidence on engine top-1 only, nothing to promote :  231
   contender below R_MIN=0.5 x top-1 (un-promotable) :  123
   cleared ratio, failed strict evidence floors      :    0
   cleared BOTH rank-1 guards                        :   27      (= 3 fixed + 24 broken)

slate shape: runner-up/top-1 ratio median=0.254 p90=0.793
             415 of 1726 slates (24.0%) have a runner-up within the guard's factor of two
apostrophe gap: 0 cases lost to the missing contraction overlay

─── SECONDARY — geometric ─────────────────────────────────────────────────────
decoded    : 1723 usable slates (3 too short to reorder)
exposure   : favourable=192  adversarial=9        cases
             favourable=92/115  adversarial=7/592 DISTINCT TRACES
baseline   : engine top-1 ALREADY correct on 151/229 favourable, 793/1494 adversarial

favourable : n=229   fixed=35  broken=0   wash=0  unchanged=194   Δtop1=+0.1528
adversarial: n=1494  fixed=0   broken=1   wash=0  unchanged=1493  Δtop1=-0.0007
COMBINED   : n=1723  fixed=35  broken=1   wash=0  unchanged=1687  Δtop1=+0.0197  errRatio=0.029

concentration: 35 fixes from 16 distinct traces / 16 words
               1 break from 1 distinct trace / 1 word    (day x1: day->dat)

why rank 1 did or did not move, over all 201 exposed cases:
   evidence on engine top-1 only, nothing to promote :  154
   contender below R_MIN=0.5 x top-1 (un-promotable) :   11
   cleared ratio, failed strict evidence floors      :    0
   cleared BOTH rank-1 guards                        :   36      (= 35 fixed + 1 broken)

slate shape: runner-up/top-1 ratio median=0.717 p90=0.972
             1435 of 1723 slates (83.3%) have a runner-up within the guard's factor of two
apostrophe gap: 0 cases lost to the missing contraction overlay

ship bar   : Δtop1 > 0 AND breakages < 0.20 of fixes, on the PRIMARY arm
PRIMARY meets bar : FALSE
```

**Reproducibility**: four independent runs of this configuration produced byte-identical tallies
(`fixed=3 broken=24`, exposure `223/158`). The sample is seeded and the result is stable.

**Self-check**: for both engines, `cleared BOTH rank-1 guards` exactly equals `fixed + broken`
(CTC 27 = 3+24; geometric 36 = 35+1). The decomposition is computed from the shipped guard's own
public predicates, and it reconciles.

## 2. Why the two engines disagree

Not because the rescorer behaves differently — it is engine-agnostic and sits at
`SuggestionHandler.handleSwipePredictionResults`, which both engines pass through. The mechanism
converts opportunity into fixes at a *comparable* rate on both. What differs is the base rates.

| | CTC (primary) | geometric (secondary) |
|---|---|---|
| favourable cases where the engine was **already right** | 220/229 = **96.1 %** | 151/229 = 65.9 % |
| **fix headroom** (cases a fix was even possible on) | **9** | 78 |
| fixes achieved, as a share of that headroom | 3/9 = **33 %** | 35/78 = **45 %** |

**CTC is too accurate for this feature to help it much.** Its benefit ceiling on the favourable arm
is 3.9 % — the 9 cases in 229 it got wrong — and rescoring recovered 1.3 pt of that. Geometric had
78 repairable cases and recovered 15.3 pt. The mechanism is not worse on CTC; there is simply almost
nothing left for it to repair.

The second driver is **exposure**, and it is the larger of the two:

| | CTC | geometric |
|---|---|---|
| adversarial cases with context evidence in the slate | 158/1497 = **10.6 %** | 9/1494 = **0.60 %** |
| adversarial **distinct traces** with context evidence | 32/595 = **5.4 %** | 7/592 = **1.2 %** |
| contexts per exposed adversarial trace | **4.94** | 1.29 |

CTC's slate carries a learned continuation on ~4.5× as many distinct adversarial traces, and each
such trace is exposed under ~4× as many different contexts. The measured facts are the rates; the
likely explanation — offered as explanation, not measurement — is that the CTC beam is guided by a
lexicon frequency prior, so its alternates skew toward *common* words, and learned bigram
continuations are overwhelmingly common words. The geometric engine ranks by trajectory shape, which
surfaces rarer, more shape-faithful competitors the context store has never seen.

### The slate-shape result is the opposite of what was supposed to protect CTC

CTC's slates are far **more** peaked — runner-up/top-1 median 0.254 vs geometric's 0.717, with only
24.0 % of slates having a runner-up inside the guard's factor of two against geometric's 83.3 %. So
the rank-1 ratio guard blocks *more* promotions on CTC (123 of its 381 exposed cases died on the
ratio test, against 11 of geometric's 201), and CTC still broke more. Peakedness was the mechanism
expected to make CTC safe — spec §5: "a confidently decoded swipe is arithmetically
un-overturnable". It does work. It is simply swamped by the exposure difference.

### The spec predicted this ceiling, and was right

`docs/specs/ctc-context-rescoring-and-tunables.md` §0.1 argued, before any of this was built, that
"the headroom is real but small — the recoverable ceiling is the top-1↔top-5 gap: seed-mean 89.31
top-1 vs 94.50 top-5 on test-2400 — **at most ~5 pt**, and only the subset where learned context
actually discriminates."

Measured CTC fix headroom: **3.9 %**, of which rescoring recovered 1.3 pt. The prediction was
correct including its qualifier. What §0 did not anticipate is that the *cost* side would be this
large on the same engine.

## 3. Concentration — read before quoting the 8.0

**All 24 CTC breakages are one trace of one word**: `tit`, decoded correctly as `tit`, promoted to
`to` under 24 different preceding contexts. Geometric's single break is likewise one trace
(`day → dat`). The fixes are more distributed: CTC's 3 fixes come from 3 distinct traces, geometric's
35 from 16.

**Why the multiplicity happens.** Adversarial decoys are near-neighbours of the learned continuation
`w2`. Because `to` is the continuation of *many* sampled pairs, its shape-neighbour `tit` gets drawn
as a decoy under many different contexts, and every one of those contexts has evidence for `to`. So
the **magnitude** of 24 is set by the decoy design, not by nature.

**But the failure mode is real and general**, and it is the most transferable finding here: *an
ultra-common learned continuation will be promoted onto any shape-confusable trace whose top-1 sits
within the guard's factor of two.* A user who swipes such a word after any of the many words that
precede `to` gets it wrong **every time**. That is worse than a random 24 scattered errors, not
better — it is a systematic, repeatable error on a specific word.

**What follows for the units.** Both counts are legitimate and they answer different questions:

| | CTC | geometric | bar |
|---|---|---|---|
| fixes : breaks, per **case** (closest to what a user experiences) | 3 : 24 = **8.00** ✗ | 35 : 1 = **0.029** ✓ | < 0.20 |
| fixes : breaks, per **distinct trace** (independent evidence) | 3 : 1 = **0.33** ✗ | 16 : 1 = **0.063** ✓ | < 0.20 |

Per-case is closer to lived experience, because the multiplicity reflects a real property. Per-trace
is the honest measure of how much *independent* evidence exists. **CTC fails on both; geometric
passes on both.** Quote the pair, never the 8.0 alone.

## 4. A guard that is currently doing no work

`cleared ratio, failed strict evidence floors` is **0 on both engines**. Every candidate that
cleared the score-ratio guard also cleared the strict `NextWordPredictor` floors, so
`promotableToRankOne` never once changed an outcome in 1,726 cases.

That is a design finding, not a defect: rank-1 protection today is **the ratio guard alone**. The
floors are vacuous *on this corpus* because `BigramStore` keeps its 10,000 highest-probability
pairs, and those pass `MIN_LEARNED_FREQUENCY`/`MIN_LEARNED_PROBABILITY` by construction. On a real
device with a thin, mostly-hapax store — the maintainer's export has 642 usable pairs of 6,589 —
they could bind. Nothing here says they are unnecessary; it says this run did not test them.

## 5. What is a sampling choice and what is not

**Sampling-dependent — do not quote as a real-world expectation:**

- `COMBINED`, its `Δtop1`, and `errRatio=8.000`. All three are functions of the
  favourable:adversarial mix (229:1497 here), set by `-PreplayDecoys=10`. Raising the decoy count
  mechanically adds breakages to the combined tally.
- The break-even ratios the harness prints (~56 favourable-exposed cases per adversarial-exposed
  case for CTC, ~3 for geometric). Arithmetic on the per-exposure rates; inherits their assumptions.
- The **magnitude** of the 24 (§3), though not its existence.

**Ratio-independent — these carry the verdict:**

- CTC's fix headroom is 9 cases in 229 (96.1 % already correct). Rescoring cannot recover more than
  3.9 % of favourable cases on this engine however the arms are mixed.
- The **direction** of the trade reverses between engines, under both counting units (§3). No arm
  ratio and no choice of unit changes a sign.
- The failure mode itself: ultra-common continuations promoted onto confusable traces.

**The number that would settle it is not measured here and cannot be**: how often real typing
presents a favourable-exposed case versus an adversarial-exposed one. Only on-device shadow mode
(spec §7.2) can supply it.

## 6. Units — read this before quoting any figure

`n` counts **(context, trace, arm) cases**, not swipes. The same physical trace appears under
several different preceding words, and those are separate experiments because context is the
independent variable. A denominator described as "swipes" would be wrong, and an earlier revision of
this document made exactly that error (§8, H3). Where a count is concentrated on few traces, §3
gives the distinct-trace figure alongside it.

## 7. Limitations that must travel with these numbers

1. **Neither adversarial arm can bound a breakage rate.** CTC has 32 distinct exposed adversarial
   traces, geometric 7, and each broke exactly one. This is the binding statistical limit, and it is
   tighter than the case counts (158 and 9) make it look.
2. **The favourable/adversarial ratio is a sampling choice**, not a measured fact about typing (§5).
3. **Adversarial decoys are selected for confusability** — same first letter, length ±1 — so the
   arm deliberately over-represents the damage surface. It answers "when a confusable competitor
   exists, what happens", not "how often does that situation arise".
4. **The two engines use their own shipping lexicons** — CTC decodes against `en_enhanced.json`
   (98,140 entries → 97,959 a–z surfaces after projection), geometric against the shipped CKDT
   English binary. They do not share a candidate set. This is the right confound to keep: the
   comparison is between shipping configurations, not between decoders in a vacuum.
5. **`CtcEngineAdapter`'s display overlays are not applied** (they need an Android `Context`), so
   CTC slates carry a–z surfaces. Measured cost: **0 cases** — the trace pool contains no apostrophe
   words at all, so no target is affected and no evidence lookup was lost to `dont` vs `don't`.
6. **The trace filter is a heuristic and 14 rows beat it.** Of the 4,064 pre-resampled 128-point
   rows, 14 carry a monotonic advancing third column and pass `hasUsableTimestamps` (0.16 % of the
   corpus). Recorded so the residual is known rather than found later; tightening it would mean
   rejecting length-128 outright, which is wrong in general.
7. **Bigrams only.** Trigrams are excluded from the app's export and not derived from the corpus, so
   the sharper trigram branch was never exercised. Measured gain is a floor — but note that on CTC
   the binding constraint is the 3.9 % headroom, which trigrams cannot enlarge.
8. **The store cap does most of the filtering.** 27,970 pairable bigrams became 225 queryable
   because `BigramStore` caps a language at 10,000 and the corpus is 175,092 rows. That survival
   rate is a **harness artefact, not a device property** — on a real device the 10,000 stored pairs
   are the user's own most-probable ones.
9. **`WEIGHT` untuned** — the design's starting 0.5, no tune/confirm split. §9.
10. **Corpus register.** Ubuntu Dialogue is typed IRC chat — real typos and shorthand, tech-support
    topic. Its top bigrams match the maintainer's own device data (`in→the`, `i→don't`, `want→to`).
11. **One corpus, one language.**

## 8. Retractions — what was published here and why it was wrong

**All previously published numbers in this document are retracted.** Do not cite "29 fixed",
"51 fixed", "19 % fix rate", "21 % when it fires", or "inert on 97 % of swipes" from any earlier
revision. The sequence of headline numbers this document has carried:

**29 fixes → 2 fixes → 51 fixes → (all retracted) → 3 fixes / 24 breaks on CTC.**

| # | Error | Effect | Fix |
|---|---|---|---|
| H1 | `take(1500)` applied to a **frequency-sorted** corpus file — a size cap that was silently a *selection* of the strongest pairs. A `Random(SEED)` sat unused three lines away, with a KDoc claiming it made runs reproducible. | measured only the extreme head | shuffle with the seed, actually use it |
| H2 | Hapax rows dropped **before** seeding, shrinking the denominator `BigramStore` divides by. | probabilities roughly doubled (`i→don't` 0.084 vs 0.046 true) | emit the tail; the store's floor ignores it for scoring but counts it in p(w2\|w1) |
| H3 | Denominator labelled "swipes" when it was context-trace cases | wrong unit | label correctly (§6) |
| H4 | One global `withEvidence` counter | safety denominator had to be inferred across two runs | per-arm counters |
| **H5** | **47.1 % of the local trace corpus is not raw traces.** Applying `hasUsableTimestamps`' exact rule to all 8,607 rows with ≥3 points: 4,557 pass, **4,050 fail — and every one of the 4,050 has exactly 128 points** (a single distinct length across the whole rejected set), i.e. pre-resampled by another pipeline with a third column that is not a timestamp. They decode to confident nonsense (`boolean`→"gh", `ensure`→"we"), land in `unchanged`, and pad the denominator. **Present in every run published before 2026-08-23, the geometric numbers included.** | inflated denominators; understated rates | `TraceCorpusQuality.hasUsableTimestamps`; CTC smoke test went 19/40 → 38/40 |
| **H6** | **`neighboursOf` took the first N matches in corpus-file order** — H1's mistake one level down, a size cap that was silently a selection of whichever neighbours appear early in the file. | adversarial arm was an arbitrary, unreproducible slice | shuffle candidates with a per-word seed before truncating |
| **H7** | **The geometric engine was measured and the default engine was not**, and the result was published with the limitation noted in prose while the headline read "benefit looks real". A caveat is not a substitute for the measurement. | the published verdict was the opposite of the one the default engine supports | CTC is now the primary arm; the harness **skips** rather than reporting geometric-only |
| **H8** | **Outcome counts were reported without their concentration.** A raw "24 breakages" cannot distinguish 24 unlucky words from one word caught under 24 contexts — it was the latter — and the exposure denominators were inflated by the same multiplicity, so numerator and denominator were not in the same unit. | overstated both the harm and the statistical power behind it | distinct-trace counts for outcomes AND exposure; §3 |

Two further errors were made *while fixing* H1–H4, recorded because they are easy to repeat:

- **Over-correcting H3**: deduping by *trace* deleted the context dimension — the independent
  variable. Distinct contexts on the same trace are distinct experiments.
- **Missing the store cap entirely** until the corrected numbers became implausible (2 activations
  in 725 cases). A real effect does not vanish that completely; the implausibility was the signal
  that the instrument, not the feature, was broken.

**The lesson H7 and H8 add**: this harness has now produced numbers that looked fine and were not
**six separate times**. Not one was caught by a run failing — every one was caught by an
implausibility, an adversarial check, or a deliberately added denominator. A decoder that returns
plausible nonsense, a harness that measures the wrong arm, and a count without its concentration all
fail *silently*. That is why the CTC smoke test, the per-arm decomposition, and the distinct-trace
counts exist, and why none of them should be removed as redundant.

## 9. What would make this decisive

1. **On-device shadow mode (spec §7.2)** — now the *only* remaining question that matters. It is the
   sole way to measure the real favourable:adversarial exposure ratio, which §5 shows the verdict
   hinges on. Compute the would-be reranking, do not apply it, count agreement.
2. **A `WEIGHT` / `R_MIN` tune-and-confirm split on CTC specifically.** The 27 CTC promotions that
   cleared both guards produced 3 fixes and 24 breaks. Raising `R_MIN` should cut breaks faster than
   fixes: the fixes cluster where the engine was genuinely uncertain, while the `tit → to` breakage
   is a confident decode overturned by a common prior. This is the one parameter change with a
   plausible path to a positive CTC result.
3. **An adversarial arm powered in DISTINCT TRACES, not cases** (§7.1). Thirty-two exposed traces
   with one break bounds nothing. Selecting decoys for trace diversity rather than re-testing the
   same trace under many contexts would buy real power at the same decode cost.
4. **A device-corpus arm** (the maintainer's 642 usable pairs) — small enough to fit under the 10k
   cap with no eviction, so its activation rate would be meaningful rather than an artefact (§7.8).

## 10. Reproduce

```sh
python3 scripts/build_ubuntu_bigrams.py \
  --archive ~/.cache/cleverkeys-corpora/ubuntu_dialogs.tgz \
  --out ~/.cache/cleverkeys-corpora/ubuntu_bigrams.json

sh gradlew runPureTests -PtestClass=swipe.ContextRescoringReplayTest \
  -PgeoFull=true -PreplayDecoys=10
```

Runtime ~22 min, of which ~21 is **seeding**: `LearnedBigramCorpus.seed` deliberately replays
`recordBigram` once per observation (1,285,947 calls for this corpus) so the store's real floors,
caps and probability recomputation apply exactly as on a device, and `BigramStore.pruneIfNeeded`
runs a full 10k sort-and-rebuild on every call past the cap. The replay loop itself is ~80 s for
both engines; both arms run in one process so the seeding cost is paid once.

`-PreplayPairs`, `-PreplayDecoys` and `-PreplayTraces` scale the sample; the report prints whichever
values it ran with, so every figure carries its own sample size.

Neither corpus is committed: one is a person's typing record, the other a 552 MB third-party corpus
with no stated licence. The ONNX natives come from `extractOrtNative`, which unpacks the **bionic**
arm64 `.so` from the `onnxruntime-android` AAR — the `onnxruntime` JAR's own glibc-linked native
cannot load on Termux, which is why the CTC arm was previously believed to need an instrumented run.
