# Geo-Swipe SLOPPY-Tier Fix — Research Synthesis (2026-07-20)

> Produced by a 4-lens Opus research round (prior ASK/Urik review notes, AnySoftKeyboard mining,
> Urik mining, live failure diagnosis) + synthesis. Full lens reports in session task wc8732i7s.

> **ERRATA + review outcome (2026-07-20, adversarial review of this doc + the in-flight fix):**
> 1. §0's "confirmed: pruneRecall … invoked only at CLEAN/TYPICAL" is **FALSE** —
>    `GeoAccuracyQwertyEnTest.sloppy_fullGrid_underGeoFull` already asserted SLOPPY
>    pruneRecall ≥ 0.90 (and Thresholds records 93.4/94.0, which §`ask`-5-1 itself quotes).
>    The genuine gap was only the two failing SMOKE layouts (Dvorak, weird_custom). Step 0
>    remains worthwhile; the "never measured" framing is corrected. Same error in §4's
>    "Instrumentation debt" line.
> 2. §1 Rank 1's "4→≤8 start×end combinations" understates: union ≤4 starts × ≤4 ends =
>    **16** pairs (36 on dense layouts at 3-nearest). Scored shortlist stays capped.
> 3. §1 Rank 1's CLEAN-safety claim ("inset anchor mostly duplicates the raw bucket") is
>    **wrong as stated** — a noiseless trace's inset point sits 0.20–0.40 kw into the path
>    and near key boundaries recruits the penultimate key's bucket; CLEAN/TYPICAL widening
>    is real and must be measured, not assumed.
> 4. Step-0 MEASURED weird recall = **80.2%** — inside the decision rule's unhandled
>    65–95% middle; post-inset decomposition shows weird is majority **scorer**-limited,
>    which per Rank 3's own pre-registered promotion rule elevates the direction/tangent
>    channel to must-try-before-any-floor-lowering. Course-correction issued to the
>    implementing agent accordingly.

# SYNTHESIS: Fixing the geometric-swipe SLOPPY top-3 floor on en/weird_custom (64.5%) and en/Dvorak (75.8%)

## 0. The one contradiction the reports must resolve first — and it decides everything

The four reports split into two camps on **what limits the two failing layouts**, and the split is the whole ballgame:

- **`failure` report**: the two failures have **different root causes**. weird_custom is *pruner-limited* (30% of true words never reach top-5 at SLOPPY — top-5 = 69.6 vs its own TYPICAL 97.1); Dvorak is *scorer-limited* (top-5 = 81.6, only 2.6pts under passing QWERTY's 84.2, but top-3 trails by 3.7 — a within-shortlist reordering signature). It calls this "two targeted fixes, not one."
- **`ask` + `urik` reports**: BOTH failures are **primarily pruner/recall-limited** — endpoint noise (σ_end=0.30 kw) crosses key boundaries, the true end key drops out of the 2-nearest bucket, the word is gone before scoring. `ask` §5: "your ceiling is set by the pruner, not the scorer, on these layouts." `urik` §6: widen to 4-/8-nearest, "attacks a recall/prune ceiling that scorer tuning cannot break."
- **`notes` report**: frames both as pre-registered OQ-1 (location tunnel, scorer) + OQ-3 (length norm) + bucket-widening, i.e. defers to whichever the harness shows.

**Resolution — the contradiction is real but UNTESTED, and the code confirms it is cheaply testable.** The `failure` report's own §2/§6 admits the decomposition is *inferred from top-K shape*, not measured: the SLOPPY test path calls only `harness.runGrid`, never `harness.pruneRecall` at SLOPPY (confirmed: `pruneRecall` is tier-parametric at `GeoAccuracyHarness.kt:188-193` but invoked only at CLEAN/TYPICAL — `GeoAccuracyQwertyEnTest.kt:77`, `GeoAccuracyJcukenRuTest.kt:71`). **All three of `failure`/`ask`/`urik` independently converge on the SAME first action: measure per-layout SLOPPY prune-recall before touching any scorer knob.** That is the synthesis: the mechanism (`GesturePreprocessor.kt:84-91` keys the extremity buckets off the raw/resampled endpoints, which at SLOPPY carry the full σ_end=0.30 kw offset — confirmed, the KDoc even says resampling preserves raw endpoints exactly) makes the pruner-loss hypothesis mechanically sound, and `pruneRecall(sample, SLOPPY, seeds)` is a one-test-file measurement that settles weird-custom vs Dvorak attribution definitively.

The reports do NOT actually conflict on the *fix ranking* — they conflict on *how much of each layout's gap is pruner vs scorer*, which the measurement resolves. Both fixes (endpoint-inset pruning, location tunnel) are on the table regardless; the measurement tells you which to apply where and whether one suffices.

---

## 1. RANKED FIX APPROACHES (best first)

### RANK 1 — Endpoint-inset / dual-anchor extremity bucketing (recall fix, weird_custom-targeted, likely helps Dvorak)

**What:** Before the nearest-key lookup, additionally derive endpoint anchors that back off the raw first/last resampled point by ~0.3–0.4 kw *along the path* (undoing overshoot + endpoint-Gaussian jitter), and **union** the nearest-key buckets of (raw endpoint) ∪ (inset endpoint). This is the `failure` report's §5 prescription ("union the 2-nearest of the raw endpoint AND the 2-nearest of a slightly cursor-inset point") and is a *targeted, noise-aware* version of `urik`'s "widen to 4-nearest / radius-based" (§6) and `ask`'s "start-tight/end-loose in the pruner" (§6-A).

- **Expected SLOPPY lift:** weird_custom top-3 the largest mover — if prune-recall is measured near ~65% (as `failure` §6 predicts), this is the *only* class of fix that can raise the ceiling; each recovered true word is a candidate top-3 slot. Plausibly recovers most of the 13.5pt gap to 0.78 on weird_custom; modest help on Dvorak (which `failure` says is majority-scorer, so smaller).
- **Risk to CLEAN/TYPICAL + NFR-1:** LOW-to-MODERATE. This is *union-widening only at the endpoints*, not a global 3×3 — the reports agree the global 3×3 was already tried in Phase-6 and regressed QWERTY TYPICAL top-3 to the 0.92 floor by diluting scoring (`GeoAccuracyThresholds.kt:70-71`, confirmed inline; `failure` §5). The inset anchor adds at most 2× the endpoint buckets (4→≤8 start×end combinations), bounded by `maxCandidatesScored`, so the scored-shortlist size and thus NFR-1 latency is capped by the existing best-K cap in `CandidatePruner.selectBest` — added cost is the extra bucket scans + a second `nearestKeys` call, sub-ms at 98k. CLEAN/TYPICAL true endpoints are near-exact, so the inset anchor mostly duplicates the raw bucket (no dilution) — this is why it is safer than unconditional widening.
- **Impl cost:** MODERATE. Touches `GesturePreprocessor.process` (compute inset anchors, add `startNearestInset`/`endNearestInset` to `ProcessedGesture`) and `CandidatePruner.prune` (union the extra buckets). Contract: preprocessor + pruner MUST agree on neighbor count (`GesturePreprocessor.kt:43` shares `config.effectiveExtremityNeighbors`) — the inset arrays ride the same k.
- **Evidence:** `failure` §3 (weird_custom top-5=69.6 → pruning fingerprint), §5 (mechanism: buckets key off noisy endpoints); confirmed at `GesturePreprocessor.kt:84-91`. `ask` §5-1 (SLOPPY whole-pruner recall 93.4 en / 94.0 ru → ~6% lost pre-score, higher on hostile layouts). `urik` §3 (Urik enrolls 8-nearest within 85px ≈ >1kw vs your 2). weird_custom's `scale="7"` row column-misalignment (`failure` §1, confirmed the layout has an irregular grid) makes 2-nearest less stable — directly the case inset-union protects.

### RANK 2 — SHARK2 location tunnel (OQ-1, Eqs. 4–6) — scorer fix, Dvorak-targeted

**What:** Replace/augment the strict index-aligned location term `d_loc = Σ α(i)·d_kw(uᵢ, tᵢ)` (`PathScorer.kt:180-186`) with a small local-window min: for each index i, `d_loc(i) = min over j∈[i−W, i+W] of d_kw(uᵢ, t_j)` (or the symmetric tunnel of Eqs. 4–6). This relaxes the mid-gesture penalty that SLOPPY per-point jitter (0.15 kw) inflates on tight same-row arcs — exactly Dvorak's failure regime (all five vowels adjacent on the home row → short words collapse to near-collinear ≲2kw paths where the shape channel is faded out at `PathScorer.kt:104-105` and disambiguation rests entirely on location).

- **Expected SLOPPY lift:** Dvorak top-3 the target — `failure` §5 argues this is the *right* Dvorak fix (its loss is short-word same-row *reordering*, top-5 healthy). Closes the 2.2pt gap plausibly. Little help on weird_custom's pruning loss.
- **Risk to CLEAN/TYPICAL + NFR-1:** MODERATE-to-HIGH. A tunnel min-over-window loosens location discrimination *everywhere*, risking CLEAN/TYPICAL top-1 (which relies on tight location on QWERTY). This is precisely why Floris/CleverKeys DROPPED it in v1 (spec OQ-1, `notes` report). Must be **bounded** (small W=1–2, only within the location term, α-weighted) and ablated. NFR-1: an O(N·W) inner loop vs O(N) — W=2 is 5× the location-channel cost, but location is a small fraction of per-candidate work; measure p95.
- **Impl cost:** MODERATE-HIGH — new scoring mode in `PathScorer.locationDistance`, config knob (`locationTunnelHalfWidth`, default 0 = current behavior), pin tests, full σ re-validation because it interacts with `locationSigma=0.50`.
- **Evidence:** `failure` §5 (Dvorak = scorer, short-row); `notes` (OQ-1 pre-registered, spec:452 trigger "location channel over-penalizes sloppy-but-correct mid-gesture arcs at SLOPPY"); `urik` §1 validates wide location tolerance works (Urik's √4000≈63px sigma tolerates a full-key miss). **Contradiction with `urik`:** `urik` §6-1 argues to first just *widen σ_l upward* (0.5→0.65–0.75) as a cheaper proxy before the full tunnel. But `GeoAccuracyThresholds.kt:72` explicitly records "raising σ_l HURTS SLOPPY top-5" was already measured — so **reject urik's σ_l-widening shortcut; it was tried and regressed.** The tunnel (a min, not a σ inflation) is structurally different: it relaxes the *penalty for mid-gesture drift* without widening the *tail everywhere*, which is why it can move numbers the σ sweep couldn't. This is also `ask`'s point (§6-B): "you have run out of magnitude knobs and need a new signal."

### RANK 3 — Direction/tangent channel (new scoring signal, `ask`-original)

**What:** Add a bounded per-segment direction penalty `Σ (1 − cos(θ_gesture,i, θ_template,i))` over the N=32 index-aligned segment tangents (or a subsampled ~8), in the log domain, capped like `cornerBonus`. Port of ASK's `1 + k·(1−cosθ)` (`GestureTypingDetector.java:480`, `DIRECTION_PENALTY_FACTOR=1.0`).

- **Expected SLOPPY lift:** Potentially the highest *scorer* lift on BOTH layouts, because it is a genuinely new channel, not a re-tune — `ask` §6-B: aggregate tangent direction is noise-*averaging* (zero-mean jitter cancels in the segment vector) whereas the positional shape sum is noise-*accumulating*. Directly attacks key-boundary-adjacent ambiguity where absolute geometry is swamped but approach angle survives. This is the strongest argument in any report for moving numbers a σ-sweep provably cannot (and `GeoAccuracyThresholds.kt` confirms the σ sweep saturated).
- **Risk:** MODERATE. New channel → new interaction with shape/location weighting; must be bounded and ablated to avoid regressing CLEAN. NFR-1: O(N) extra, cheap (tangents already implicit in resampled points).
- **Impl cost:** MODERATE — new term in `scoreVariant`, config weight, ablation harness pass.
- **Evidence:** `ask` §1, §5-2, §6-B (the report's flagship finding); the one signal CleverKeys lacks that a shipping competitor uses deliberately as a noise fix (ASK commit `a3506ec1a`). **Ranked below the tunnel** only because it is more speculative (unproven in this pipeline) and the `failure` decomposition points to specific per-layout mechanisms (pruning + location) that Rank 1+2 address more surgically. Promote to Rank 1-tier if the SLOPPY prune-recall measurement shows *both* layouts are scorer-limited (i.e. `failure`'s weird=pruning attribution is wrong).

### RANK 4 — Overshoot-free endpoint penalty asymmetry (cheap scorer tweak)

**What:** Clamp `endpointPenalty`'s end term to ~0 when the gesture extends *past* the last template key along the incoming direction (pure overshoot), per `ask` §6-D and `urik`'s undershoot>>overshoot asymmetry. Current `endpointPenalty` (`PathScorer.kt:210-217`) penalizes `dEnd` beyond `endNeighborRadius=1.1` quadratically and symmetrically in distance — it does NOT distinguish overshoot (should be free) from lateral miss (should cost). SLOPPY has overshoot p=0.6.

- **Expected lift:** SMALL but cheap and universal; helps any layout where legitimate overshoot is over-penalized.
- **Risk:** LOW. One-line-ish, bounded.
- **Impl cost:** LOW.
- **Evidence:** `ask` §2/§6-D (ASK makes overshoot ≈ free), `urik` §3 (undershoot expensive, overshoot cheap). Spec already has `endNeighborRadius=1.1 > startNeighborRadius=0.9` (confirmed `GeometricEngineConfig.kt:43-45`) — this extends that asymmetry to *direction-aware* clamping.

### RANK 5 (fallback, NOT a fix) — Per-layout floors — see §3.

### EXPLICITLY REJECTED (reports agree — do NOT do these):
- **Global 3×3 extremity widening** — Phase-6 measured it regresses QWERTY TYPICAL top-3 to the 0.92 floor (`GeoAccuracyThresholds.kt:70-71`; `failure` §5; `ask`/`urik` implicitly). Rank 1's *targeted endpoint-inset union* is the correct scoped version.
- **Raising σ_l globally** — measured to HURT SLOPPY top-5 (`GeoAccuracyThresholds.kt:72`); rejects `urik` §6-1's shortcut.
- **FUTO L^γ length-normalization (OQ-3)** — `failure` §5 + `urik` §6 agree it has *little leverage* on the exact failing words: Dvorak's failures are short-row words where the shape channel is already faded to ~0 (`PathScorer.kt:104-105`), so normalizing shape σ by length does nothing for them. Keep OQ-3 as a future item, not this fix. (This resolves the `notes` framing that pre-registered OQ-3 as co-equal — the failure geometry demotes it.)
- **ASK linear-frequency-into-raw-pixel blend** — `ask` §4 explicitly: your ordinal-log prior (`PathScorer.kt:113`) is strictly better-posed; do not import.
- **Corner handling / kappa investment** — `urik` §4: Urik ships with *zero* corner detection and works; your soft cornerBonus already exceeds it. Not the fix.
- **ASK 1-nearest hard start filter, MINIMUM_DISTANCE_FILTER, greedy variable-length walk, Urik ghost-swipe velocity/duration gates** — all narrower/scale-fragile/reject-mechanisms that would lower recall (`ask` §6 "do NOT port"; `urik` §2 Urik itself *reverted* the velocity gate at HEAD as too aggressive).

---

## 2. TOP RECOMMENDATION — concrete implementation sketch

**Do this in two ordered steps. Step 0 is mandatory and near-free; it decides whether Step 1a alone suffices or Step 1b is also needed.**

### Step 0 (MANDATORY FIRST) — Measure the SLOPPY prune-recall decomposition

Add ONE new `-PgeoFull`-gated test file (no engine/harness source edits; uses existing public APIs). Register it in `pureTestClasses` (the drift test requires it — MEMORY.md).

- **File:** `src/test/kotlin/tribixbite/cleverkeys/swipe/geometric/GeoSloppyPruneRecallTest.kt`
- **Body:** for each of {Dvorak, weird_custom, and QWERTY as control}, build `GeoAccuracyHarness(layout, dict, label)` (default config), then call `harness.pruneRecall(harness.stratifiedSample(2500), GeoTraceSynthesizer.Tier.SLOPPY, FULL_SEEDS)` and print. Optionally also `runGrid` on `sample.filter { it.lenStratum == SHORT_2_3 }` to confirm Dvorak's loss concentrates in short words.
- **Decision rule (from `failure` §6):**
  - weird_custom SLOPPY recall **≈ 65%** → pruning-dominated → **Step 1a is the fix**, and no scorer change can reach 0.78 without it.
  - weird_custom recall **≈ 95%** → scorer-dominated → `failure`'s attribution was wrong; skip 1a, go to Rank 3 (direction channel) which helps both.
  - Dvorak recall **≈ 95%** (as `failure` predicts, since its top-5 is healthy) → confirms Dvorak needs **Step 1b** (location tunnel), not pruning.

### Step 1a — Endpoint-inset dual-anchor bucketing (Rank 1)

**Files changed:**
1. `ProcessedGesture.kt` — add `startNearestInset: IntArray`, `endNearestInset: IntArray`.
2. `GesturePreprocessor.kt` (~line 84-91) — after the raw endpoint nearest-key lookup, compute an inset point along the path and its nearest keys:
   ```kotlin
   // Back off each endpoint along the path by insetKw to undo SLOPPY overshoot +
   // endpoint-Gaussian jitter before the nearest-key lookup. The nearest keys of the
   // raw AND inset endpoints are unioned in the pruner so a noisy endpoint that lands
   // on an adjacent key still enrolls the true word's bucket.
   val insetKw = config.endpointInsetKw            // new config, default 0.35f
   val (sx, sy) = pointAlongPath(resampled, fromStart = true,  distKw = insetKw, layout)
   val (ex, ey) = pointAlongPath(resampled, fromStart = false, distKw = insetKw, layout)
   val startNearestInset = layout.nearestKeys(sx, sy, k)
   val endNearestInset   = layout.nearestKeys(ex, ey, k)
   ```
   `pointAlongPath` walks the resampled polyline from the endpoint accumulating `dKw` until `insetKw` is reached (linear interp on the crossing segment). Default `endpointInsetKw = 0f` reproduces current behavior exactly (both inset arrays == raw arrays), so CLEAN/TYPICAL floors cannot move until the knob is turned up — a safe, ablatable default.
3. `CandidatePruner.prune` (~line 46-47) — union the inset buckets:
   ```kotlin
   val starts = unionDistinct(takeUpTo(gesture.startNearest, neighbors),
                              takeUpTo(gesture.startNearestInset, neighbors))
   val ends   = unionDistinct(takeUpTo(gesture.endNearest, neighbors),
                              takeUpTo(gesture.endNearestInset, neighbors))
   ```
   (dedupe start/end key ids so buckets stay disjoint — the existing "one bucket per word" invariant at `CandidatePruner.kt:49-52` holds because a word still lands in exactly one (firstKey,lastKey) bucket; we just probe more (s,e) pairs.)
4. `GeometricEngineConfig.kt` — add `val endpointInsetKw: Float = 0.0f` with a `require(endpointInsetKw >= 0f)`; document that it is a hostile-layout recall knob.

**Constants:** `endpointInsetKw` sweep {0.30, 0.35, 0.40} kw — the `failure` §5 range, matched to σ_end=0.30 kw + overshoot-0.4kw. Neighbor count stays 2 (do NOT combine with global 3×3).

**Tests/floors that move:** `GeoAccuracyWeirdLayoutTest.sloppy_underGeoFull` (target: top-3 ≥ 0.78) is the primary mover. `GeoAccuracyDvorakEnTest` may lift slightly. The new `GeoSloppyPruneRecallTest` recall for weird/Dvorak must rise. **Regression guards that must NOT move:** all CLEAN/TYPICAL top-1/3/5 on every layout, and QWERTY/JCUKEN SLOPPY (currently passing 79.5 / 88.0). Because default `endpointInsetKw=0` is a no-op, set it to the swept value ONLY after confirming green.

### Step 1b (conditional — only if Step 0 shows Dvorak scorer-limited) — Location tunnel (Rank 2)

- `GeometricEngineConfig.kt` — `val locationTunnelHalfWidth: Int = 0` (0 = current strict alignment).
- `PathScorer.locationDistance` (line 180-186) — when `W>0`, replace `d_kw(uᵢ, tᵢ)` with `min over j∈[max(0,i−W), min(n−1,i+W)] d_kw(uᵢ, t_j)`, still α-weighted. Sweep W∈{1,2}.
- Re-validate the full σ grid because the tunnel interacts with `locationSigma`.

### VALIDATION — the `-PgeoFull` grid

```bash
LD_PRELOAD=$PREFIX/lib/libtermux-exec.so ./gradlew runPureTests -PgeoFull \
  -PtestClass=GeoSloppyPruneRecallTest        # Step 0: measure
# then, per-layout after turning the knob:
./gradlew runPureTests -PgeoFull -PtestClass=GeoAccuracyWeirdLayoutTest
./gradlew runPureTests -PgeoFull -PtestClass=GeoAccuracyDvorakEnTest
# regression sweep — MUST stay green:
./gradlew runPureTests -PgeoFull -PtestClass=GeoAccuracyQwertyEnTest
./gradlew runPureTests -PgeoFull -PtestClass=GeoAccuracyJcukenRuTest
./gradlew runPureTests -PgeoFull -PtestClass=GeoAccuracyQwertzDeTest
./gradlew runPureTests -PgeoFull -PtestClass=GeoAccuracyAzertyFrTest
```
(Use `sh gradlew` or the `LD_PRELOAD` prefix per MEMORY.md shell-shim notes.) Acceptance: weird_custom + Dvorak SLOPPY top-3 ≥ 0.78 AND every currently-green CLEAN/TYPICAL/SLOPPY floor unchanged, AND NFR-1 p95 latency unchanged (the best-K cap in `selectBest` bounds scored-shortlist growth). Ablate: confirm `endpointInsetKw=0` reproduces today's numbers exactly (proves the change is opt-in and the default suite is untouched).

---

## 3. The cheap alternative — per-layout floors — when it is RIGHT vs papering over

Per-layout floors mean: document weird_custom SLOPPY top-3 = 0.64 and Dvorak = 0.75 as reality, lower those two floors, ship. `notes` calls this "review-endorsed" (Accepted deviations #1/#2 already lowered floors this way); `urik` §5 notes Urik itself ships Dvorak untuned and simply accepts the gap.

**RIGHT for weird_custom.** It is a *deliberately adversarial fixture* (`weird_custom.xml:2-8`, "hostile clearance case," `failure` §5, `notes` spec:537) with an irregular `scale="7"` column-misaligned grid that no real user types on. Its purpose is to prove the engine doesn't *crash* or collapse on pathological geometry, not to hit production accuracy. A documented lower floor (e.g. 0.64 with a comment citing the grid irregularity) is defensible IF Step 0 shows its loss is intrinsic pruning ambiguity that inset-bucketing can't recover. **However** — Rank-1 endpoint-inset is cheap, low-risk (opt-in default-off knob), and directly targets weird_custom's measured mechanism, so *attempt the fix first*; fall back to a documented floor only if the swept knob can't reach 0.78 without regressing another layout.

**WRONG for Dvorak.** Dvorak is a **real shipping layout** users actually select (`urik` confirms it as a first-class layout choice), and it misses by only **2.2 points** with a *healthy top-5 (81.6)* — meaning the true word is IN the shortlist and merely mis-ranked. That is a fixable scoring defect (Rank 2 tunnel), not an intrinsic ceiling. Lowering Dvorak's floor to 0.75 papers over a genuine, small, addressable ranking bug and normalizes shipping degraded swipe on a mainstream layout. `failure` §5 states this explicitly: per-layout floors "shouldn't be used for Dvorak, a real shipping layout only 2.2 pts short."

**Verdict:** per-layout floors are the right call for the adversarial fixture *as a documented fallback after the fix is attempted*, and the wrong call for Dvorak *unconditionally*. Do not use a single blanket "accept both" — that conflates a torture-test with a real layout.

---

## 4. Techniques worth adopting independently (future OQ items — one line each)

- **OQ-8 Direction/tangent channel** (`ask` §6-B): bounded `Σ(1−cosθ)` over index-aligned segment tangents — the one noise-*averaging* signal the engine lacks; adopt even if Rank-1 fixes the floors, as a general SLOPPY discriminant. `GestureTypingDetector.java:456-488`.
- **OQ-9 Direction-aware overshoot clamp** (`ask` §6-D, `urik` §3): make pure overshoot cost ~0 in `endpointPenalty` (currently symmetric quadratic, `PathScorer.kt:210-217`) — matches ASK's overshoot≈free / undershoot-expensive asymmetry; directly counters SLOPPY overshoot p=0.6.
- **OQ-10 Length-aware sequence-violation tolerance** (`urik` §1, L812-817): graded, word-length-scaled ordering slack (0/1 violations for len≤4/≥5) — a softer alternative to strict index alignment if reordering shows up in confusion pairs.
- **OQ-11 Reversal-count as a confidence signal** (`urik` §2): Urik throws away its `directionReversals` count; repurpose it as a per-decode quality/σ-bias signal instead of discarding — correlates with corner-cut/overshoot noise.
- **OQ-12 Curvature-weighted shape channel** (`ask` §6-C): weight shape-channel point errors by local curvature (concentrate signal at corners, starve straight-limb jitter) — ASK's decimation win ported into your fixed-resample shape sum; speculative, try only if direction channel underperforms.
- **Instrumentation debt** (`failure` §6, confirmed): the SLOPPY test path never calls `pruneRecall`/confusion-matrix at SLOPPY on any layout — add the Step-0 test permanently so future regressions surface the prune-vs-score split automatically, not by inference.

**Key file:line anchors used:** `GesturePreprocessor.kt:84-91` (buckets key off raw/resampled endpoints — the pruner-loss mechanism, CONFIRMED); `CandidatePruner.kt:45-65` (2×2 union, dense-widen at `:45`); `PathScorer.kt:104-105` (short-word shape fade → Dvorak scorer path), `:113` (ordinal-log prior — keep), `:180-186` (location channel — tunnel target), `:210-217` (symmetric endpoint penalty — overshoot-clamp target); `GeometricEngineConfig.kt:43-55` (start/end radius asymmetry, extremityNeighbors=2, denseLayoutKwThreshold=0.075); `GeoAccuracyThresholds.kt:70-72` (Phase-6: global 3×3 regresses TYPICAL, raising σ_l hurts SLOPPY — both rejected); `GeoAccuracyHarness.kt:188-214` (`pruneRecall` exists, tier-parametric, UNUSED at SLOPPY); `GeoAccuracyQwertyEnTest.kt:77`/`GeoAccuracyJcukenRuTest.kt:71` (recall only checked at CLEAN/TYPICAL). Report cross-refs: `failure` §2/§3/§5/§6; `ask` §5/§6-A/§6-B/§6-D; `urik` §1/§3/§6; `notes` OQ-1/OQ-3/spec:452-454,528-533.