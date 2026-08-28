# CTC context rescoring (bigram/trigram) and the decode-time tunables inventory

**Status**: plan + reference. **Written**: 2026-08-19, against `main` @ `31b289c0`.
**Architecture reference**: `docs/specs/ctc-architecture-and-multiscript-guide.md` (graph
contract), `docs/specs/ctc-swipe-engine.md` (as-built decoder).
Every claim below is cited against the code at this commit; where an older doc disagrees
with the code, the code wins.

Two parts:

- **Part 1** — a design for rescoring the CTC top-K slate with the learned bigram/trigram
  context LM, with an honest verdict on whether it is worth shipping.
- **Part 2** — the complete inventory of decode-time CTC parameters: what can be surfaced
  to users, in what form, and what is baked into the ONNX contract and cannot be.

---

# Part 1 — Bigram/trigram rescoring of the CTC top-K

## 0. Verdict first

**Worth building — as a bounded, privacy-gated, default-OFF re-ranker at the shared
pipeline seam. Not worth shipping on-by-default today**, because:

1. **The headroom is real but small.** The recoverable ceiling is the top-1↔top-5 gap:
   seed-mean 89.31 top-1 vs 94.50 top-5 on test-2400 (`docs/specs/ctc-swipe-engine.md:8`).
   Context rescoring can only fix errors where the intended word is already in the top-K
   but not top-1 — at most ~5 pt, and only the subset where learned context actually
   discriminates.
2. **The signal is sparse by design.** The stores cap at 10,000 n-grams per language
   (`BigramStore.kt:54`, `TrigramStore.kt:45`) with a seen-≥2× confidence floor
   (`BigramStore.kt:52`), and the M3 fix made frequency equal the number of times the
   user genuinely typed the pair (`contextaware/ContextModel.kt:131-158`). For most swipes,
   every candidate will have boost 1.0 and rescoring is a no-op.
3. **The failure mode is expensive.** The top-1 of a swipe slate is **auto-committed**
   (`SuggestionHandler.kt:534-561`). A wrong promotion is not a worse suggestion — it is
   wrong text in the editor.
4. **No existing harness can measure it** (§7): every replay corpus in the repo is
   context-free isolated words. Shipping the default ON would be an unmeasured ranking
   change, which this project does not do.

The plan below is therefore: build it correct and inert, measure it, and treat the
default flip as a separate evidence-gated decision. The decoder-level `alpha` slot
(`CtcScoringParams.kt:23-25,30`) is explicitly NOT the vehicle — see §1.

## 1. Where it hooks

### Decision: `SuggestionHandler.handleSwipePredictionResults`, engine slate only

Rescoring goes in **`SuggestionHandler.handleSwipePredictionResults`**
(`SuggestionHandler.kt:470`), immediately after the empty/password guards
(`:486-496`) and **before** case transform, possessive augmentation, and the auto-insert
block. The math itself lives in a new **pure-JVM object** (working name
`SwipeContextRescorer`, package root beside `NextWordPredictor`) so the policy is
unit-testable in `runPureTests`.

### Alternatives rejected, with reasons

| Site | Why not |
|---|---|
| Inside `CtcBeamDecoder` (consume `alpha` during the beam) | The decoder is pinned by the golden parity fixture (`ctc_golden.json`, `ctc-swipe-engine.md:461-474`); any in-beam term invalidates the fixture and the FUTO-port parity contract (`CtcBeamDecoder.kt:47-53`). It also puts learned-store reads inside a pure module that deliberately has none, and would rescore hypotheses the LM has no business pruning (partial words are not in the n-gram stores). |
| `CtcEngineAdapter` (after `decode`, in `applyDisplay`/`toPredictionResult`) | The adapter is the impurity boundary for **geometry/lexicon/model** concerns only (`CtcEngineAdapter.kt:42-93`); it has no access to `contextTracker`, no `EditorInfo`, and runs on the decode thread where the incognito-field and password gates are not visible. Wiring context into it duplicates gate logic that already exists once, in SuggestionHandler. It would also make the feature CTC-only for no reason. |
| A new stage between `InputCoordinator.handlePredictionResults` and the delegate | `handlePredictionResults` is deliberately a thin delegation (`InputCoordinator.kt:404-449`); WP9 step 6 deleted IC's divergent presentation path precisely so that ONE pipeline owns presentation and commit (`SuggestionHandler.kt:436-445`). A rescorer changes *which word auto-commits* — that is a presentation/commit concern and belongs to the pipeline owner. |

Choosing the SuggestionHandler seam also buys three things for free:

- **Engine neutrality.** Both engines emit engine-relative softmax×1000 slates into this
  seam (`CtcEngineAdapter.kt:772-781`; router contract `SwipeEngineRouter.kt:41-42`).
  Rescoring *within one slate* never compares scores across engines, so the "NEVER
  compared across engines" contract is untouched, and geometric-decoded swipes (non-CTC
  languages, letter-incomplete layouts, dead-session fallthrough —
  `InputCoordinator.kt:690-733`) inherit the feature identically.
- **The context source is already here.** `contextTracker.getContextWords()` is what the
  next-word path in this same class consumes (`SuggestionHandler.kt:682`), with the same
  normalization the learn path used — keys match by construction.
- **Every gate is already here** (§3).

Operate on the **engine portion of the slate only**: rescore `predictions`/`scores` before
the possessive augment appends entries (`SuggestionHandler.kt:512-518`), so appended
possessives and later next-word appends are never reordered by context.

## 2. Score combination

### The quantities

- CTC candidate scores arriving at the seam are a softmax over final beam scores × 1000,
  rounded to ints in 0..1000 (`CtcEngineAdapter.kt:772-781`) — a within-slate posterior
  proxy. They are engine-relative and meaningless across engines; within one slate their
  *ratios* are meaningful, which is exactly what rescoring needs.
- The context signal is `ContextModel.getContextBoost(word, previousWords)` — trigram
  preferred, bigram backoff, `boost = (1 + p)^2` clamped to [1.0, 5.0]
  (`contextaware/ContextModel.kt:201-230, 405-410`), computed from **confident**
  probabilities only (the store floors are inside `getConfidentProbability`).

### The combination: log-linear, within the slate

```
adjusted_i = ln(max(score_i, 1)) + W * ln(boost_i)
```

re-sorted by `adjusted_i` **descending, stable, input-index tiebreak**. Properties:

- `boost_i = 1.0` for every candidate (no learned data) ⇒ `ln(boost_i) = 0` for all ⇒
  `adjusted` ordering is the input ordering under the stable sort. **A user who has
  learned nothing gets the identical ranking with no special-casing** — this is the
  structural answer to "a default that changes ranking for a user who has learned nothing
  is a bug". It is additionally enforced twice more: the master gate (§3) means the
  rescorer is not even invoked, and the feature pref defaults OFF.
- The context term is bounded by construction: `W * ln(5.0) ≈ 1.61·W` nats. With the
  proposed `W = 0.5` starting point, context can close at most a ~2.2× score ratio —
  a 900-vs-350 slate is re-rankable only with a near-max boost; a 950-vs-20 slate never.

**Rank fusion is rejected** because it discards the score margins, and the margins are
the safety mechanism: a peaked slate (top-1 at 900/1000) must be far harder to overturn
than a flat one. Linear-probability interpolation (`p' = (1-w)p_ctc + w·p_ctx`) is
rejected because the two quantities are not on a common scale — `boost` is not a
probability over the slate — and because it is not identity-preserving at empty stores
without special-casing.

### Where the weight lives, and the default

- **Feature pref**: `swipe_context_rescoring` (Boolean, **default `false`**), in `Config`
  + `Defaults` + `backup/SettingsDefaults.kt` + `backup/SettingsValidation.kt`, following
  `ctc_beam_width`'s pattern (`Config.kt:595,877`, `SettingsDefaults.kt:243`,
  `SettingsValidation.kt:247`).
- **Weight `W`**: a named constant in `SwipeContextRescorer` (start 0.5, to be fitted by
  the §7 harness). NOT a user-facing raw knob — same rationale as the scoring constants
  in Part 2. If tuning access is ever wanted, expose a bounded offset later, not now.

## 3. The privacy gate (non-negotiable)

The rescorer is invoked from `handleSwipePredictionResults` **only when ALL of these
pass**, evaluated at the call site exactly as the read-path precedents do:

1. `LearningGate.canUseLearnedContext(config.on_device_learning_enabled,
   config.context_aware_predictions_enabled)` (`LearningGate.kt:96-97`) — the same
   master+feature read gate `WordPredictor.calculateUnifiedScore` applies before touching
   `getContextBoost` (`WordPredictor.kt:1967-1975`), fail-closed on null config (M2).
2. `fieldAllowsPersonalizedLearning` — the M5 incognito-field flag SuggestionHandler
   already tracks and feeds to `NextWordPredictor.shouldShow`
   (`SuggestionHandler.kt:691`, `LearningGate.kt:59-60`). An
   `IME_FLAG_NO_PERSONALIZED_LEARNING` field must not have its ranking personalized
   either — same contract as next-word.
3. `swipe_context_rescoring == true` (the feature pref).
4. The password guard has already returned by this point (`SuggestionHandler.kt:486-491`),
   so password fields are covered structurally.

**Gate fails ⇒ the rescorer function is not called and the original `predictions` /
`scores` list references flow onward untouched.** Not "rescored with weight 0" — not
invoked. That is what makes the learning-OFF output byte-identical to today.

**How it is tested:**

- Pure JVM: `SwipeContextRescorerTest` asserts (a) empty-boost identity — output list
  `equals` AND is the same ordering as input for arbitrary slates; (b) the gate
  short-circuit at the caller returns the *same object references* (no copy, no re-sort).
- Extend `OnDeviceLearningPrivacyTest` (named in `LearningGate.kt:23-26`): with the
  master gate off, drive a synthetic slate through the SuggestionHandler entry and assert
  the bar receives the engine's ordering verbatim, with learned stores deliberately
  populated (proves the gate, not just emptiness).

## 4. Cold start and sparsity

Learned stores start empty and stay sparse (10k cap/language, seen-≥2× floor,
newest-n-gram-only recording — see §0.2). Threshold decisions:

- **Reuse the store floors implicitly, by calling `getContextBoost`.** It already routes
  through `getConfidentProbability` (frequency ≥ `DEFAULT_MIN_FREQUENCY = 2`,
  `BigramStore.kt:52`, `TrigramStore.kt:43`) and applies `MIN_TRIGRAM_PROB = 0.001` /
  `MIN_BIGRAM_PROB = 0.01` (`ContextModel.kt:69-70`). Do not duplicate these numbers.
- **Do NOT apply `NextWordPredictor.MIN_LEARNED_PROBABILITY = 0.05` to general in-slate
  reordering.** That floor (`NextWordPredictor.kt:30-31`) is calibrated for *generating
  suggestions from nothing*, where an empty bar is the acceptable outcome and precision
  is everything. Rescoring only nudges candidates the emission evidence already produced,
  so the store's own floors suffice for moves below rank 1.
- **DO apply the stricter next-word floors to any promotion into rank 1** (frequency ≥
  `MIN_LEARNED_FREQUENCY` AND probability ≥ `MIN_LEARNED_PROBABILITY`, referenced from
  `NextWordPredictor`, never re-declared). Rationale: a rank-1 promotion is a
  context-driven **commit**. Next-word candidates at this confidence still require a user
  tap; here nothing is tapped — the bar for silently writing the word must be at least as
  high.

This requires the rescorer to receive raw `(frequency, probability, fromTrigram)` per
candidate, not only the boost — i.e. a lookup shaped like `ContextContinuation`
(`ContextModel.kt:15-20`) per slate word, via a small gated accessor on `WordPredictor`
(mirroring `getNextWordCandidates`, `SuggestionHandler.kt:698`) so the M2 fail-closed
config gating stays in the one class that owns it.

**One real cold-start hazard**: the first store access for a language lazily loads the
persisted n-gram blobs, which caused first-swipe jank when done inline
(`SuggestionHandler.kt:621-626`, the L3 note). The rescorer runs on the main thread (§5),
so it must **never trigger a synchronous blob load**: if the language's store is not yet
loaded, skip rescoring for that swipe (return the input unchanged) rather than block.
The next-word append path, which runs on the executor moments later, performs the warm
load exactly as it does today.

## 5. Latency budget

- **Where it runs**: on the main thread inside `handleSwipePredictionResults`, i.e. after
  the decode has already delivered (decode budget: median < 150 ms / p90 < 250 ms at beam
  100, `swipe/CtcLatencyGateTest`, `ctc-swipe-engine.md:538`).
- **Budget: ≤ 1 ms, expected single-digit microseconds.** The work is: for K ≤ 8
  candidates (`TOP_K = 8`, `CtcEngineAdapter.kt:124`; geometric slates are the same order
  of size), one trigram + one bigram in-RAM `HashMap` lookup each (stores are in-memory
  maps, hard-capped at 10k entries per language — `BigramStore.kt:53-54`,
  `TrigramStore.kt:44-45`), 8 `ln()` calls, and a stable sort of ≤ 8 elements. There is
  no I/O on the path (persistence is a debounced background write-back,
  `BigramStore.kt:31`), no allocation beyond one small array, and the no-learned-data
  fast path (all boosts 1.0) can return the input list without sorting.
- The §4 "skip if not loaded" rule removes the only way this path could ever block.

## 6. Correctness risk — what bounds the damage

Rescoring can promote a plausible-in-context wrong word. Bounds, in order of force:

1. **Rank-1 displacement guard** (the auto-commit protection): a candidate may take
   rank 1 only if `score_i ≥ R_MIN * score_top` with `R_MIN = 0.5` (i.e. the engine
   itself considered it within a factor of 2 — ≤ 0.69 nats behind), AND it passes the
   §4 strict floors. A confidently-decoded swipe (top-1 at 900/1000, runner-up at 40)
   is arithmetically un-overturnable regardless of boosts.
2. **The boost ceiling**: `W * ln(MAX_BOOST) ≈ 0.8` nats at `W = 0.5` — context can
   never outvote strong emission evidence, only break near-ties.
3. **Below rank 1, reordering is cheap**: ranks 2..K are bar alternates the user may tap;
   a suboptimal alternate ordering costs nothing that today's ordering doesn't already.
   No cap is needed there beyond the boost ceiling.
4. **Display-form key match**: rescoring runs AFTER the adapter's display overlays, so
   slate words are already `"don't"`/`"café"` (`CtcEngineAdapter.kt:577-667`); store keys
   are committed words lowercased with internal apostrophes kept
   (`NextWordPredictor.kt:146-149`). Lookups use `word.lowercase()`; the step-1 tests
   must include an apostrophe-form and an accented-form case to pin this.
5. **Provenance**: a rescoring-promoted top-1 should carry a distinguishable
   `SuggestionMeta` note (the Task B plumbing exists — `SuggestionHandler.kt:520-527`,
   `NextWordPredictor.provenanceNote`) so a misbehaving promotion is diagnosable from the
   long-press sheet instead of being invisible.

## 7. How it would be measured

**What exists cannot measure this.** All current accuracy evidence is context-free
isolated words:

- the FUTO replay corpus runs (`scripts/run_futo100k_fixed.sh`, the per-language λ sweep
  in `docs/eval/2026-08-15-ctc-per-language-lambda.md`) — single `{word, trace}` rows;
- the local combined-corpus replay (`scripts/build_local_corpus_replay.mjs`) — emits
  `{word, w, h, pts}` lines, by design (`:31-40` of the script);
- the golden fixture (`ctc_golden.json`) — decoder parity, not accuracy;
- `SwipeMLData` device traces — word + trajectory + layout, no preceding-sentence text.

So evidence has to be built:

1. **Offline context replay (the primary gate).** A pure-JVM harness that (a) takes a
   sentence corpus, (b) replays its prefix through `ContextModel.recordCommit` to
   populate real stores with the real floors, (c) for each target word draws a matching
   human trace from the FUTO pool (the geo replay tests already demonstrate the
   trace-keyed loading pattern), decodes it through the shipping adapter path, and
   (d) applies the rescorer. Metrics: Δtop-1, and separately the **promotion-error rate**
   (correct engine top-1 demoted by context). This extends `runPureTests` and follows the
   tune/confirm-split discipline of the λ sweep.
2. **On-device shadow mode (the confirmation gate).** Behind the same §3 gates, compute
   the would-be reranking, do NOT apply it, and count agreement: how often the user's
   actual correction tap (choosing an alternate over the auto-insert) matches what
   rescoring would have promoted, vs. how often rescoring would have displaced a top-1
   the user kept. Counts only — no text content — persisted like
   `SwipePerformanceStats`.
3. **Ship-on-default bar**: net top-1 gain on (1) with promotion errors introduced well
   under errors fixed (proposed: < 20 % of fixes), corroborated by (2) not contradicting
   it. Until then the pref stays default-OFF.

## 8. Trigrams — worth it?

**Yes, trivially — because they are already there and already fused.** This is not a
"bigrams now, trigrams later" decision:

- `TrigramStore` exists, is populated on every commit alongside bigrams
  (`ContextModel.recordCommit`, `ContextModel.kt:151-158`), capped at 10 continuations
  per two-word prefix and 10k per language (`TrigramStore.kt:44-45`), deliberately
  excluded from backup because it relearns quickly (`ContextModel.kt:380-385`).
- The trigram-preferred/bigram-backoff policy is already implemented and floor-guarded
  inside `getContextBoost` (`ContextModel.kt:212-229`) and `getNextWordCandidates`.

Consuming the context signal through `ContextModel` therefore gets trigram sharpness for
**zero additional state, storage, or plumbing**. What is NOT justified is any
trigram-specific machinery in the rescorer — separate weights, separate floors, a longer
context window. One `W`, one backoff policy, owned where it already lives. If the §7
harness ever shows trigram-sourced boosts misbehaving independently, `fromTrigram` is
already carried per continuation and a split weight can be added then — with evidence.

## Ordered implementation steps (each compilable and committable alone)

| # | Step | Contents | Gate before commit |
|---|---|---|---|
| 1 | Pure rescorer | `SwipeContextRescorer` (log-linear math, stable sort, rank-1 guard, strict-floor promotion rule, empty-boost identity) + `SwipeContextRescorerTest` incl. apostrophe/accent key cases | `runPureTests` green; identity property tests pass |
| 2 | Gated boost accessor | `WordPredictor` method returning per-word `ContextContinuation?` for a candidate list, M2 fail-closed gating inside, non-loading fast path (§4) | `runPureTests`; no new store-load call on the main-thread path (code review + test with unloaded store) |
| 3 | Pref plumbing | `swipe_context_rescoring` default `false` in `Config`/`Defaults`/`SettingsDefaults`/`SettingsValidation`; settings UI toggle in the swipe section, copy stating it uses learned data | compile; backup-defaults drift test green |
| 4 | Seam wiring | Invoke from `handleSwipePredictionResults` behind §3 gates, engine-slate-only, before augment; provenance meta for promoted top-1; extend `OnDeviceLearningPrivacyTest` with the byte-identical assertion | full `runPureTests` + privacy test; manual smoke with pref off = today's behavior |
| 5 | Offline context replay harness | §7.1 script + JVM test, tune `W`/`R_MIN` on a tune half, confirm on held-out half; results doc under `docs/eval/` | measured Δtop-1 + promotion-error rate published |
| 6 | (Evidence-gated, separate decision) | Shadow counters; then a default flip proposal citing §7.3 — a release decision, not part of this plan | user approval |

Steps 1–4 ship a feature that is OFF and inert; nothing before step 6 changes any
user's ranking without their explicit opt-in.

---

# Part 2 — Every surfaceable CTC parameter

Scoring formulas the constants feed (`CtcBeamDecoder.kt:162-163, 178-182`):

```
prune_i  = score / max(depth,1)^gammaPrune + betaPrune * depth       (per frame, per hyp)
final_w  = ctc / max(len,1)^gamma + beta * len + lambda * ln(freq)   (per complete word)
```

`freq` is on the AOSP-like 1..255 scale, stored as `ln(freq + 1e-10)` at trie load
(`CtcLexiconTrie.kt:154, 182-183`). The ship preset is `CtcScoringParams.tunedV2`
(γ 0.9, λ 4.0, β 0.25, α 0.0, γ_prune 0.25, β_prune 0.9882 — `CtcScoringParams.kt:103-108`)
with **λ selected per lexicon scale** by `presetFor` (`:155-165`): 4.0 for
`en_enhanced.json`'s compressed 134..255 byte scale (`LAMBDA_EN_JSON_SCALE`, `:114`),
2.0 for CKDT `255 − rank` (`LAMBDA_CKDT_SCALE`, `:129`, two independent sweeps).

**The governing precedent, measured**: `CtcSettingsActivity` deliberately exposes ONE
knob because "the published-preset control measured −2.3 pt top-1"
(`CtcSettingsActivity.kt:40-43`) — i.e. running this model at a *plausible, published,
professionally-tuned* alternative constant set costs 2.3 points, silently. A user-set raw
constant has no feedback path at all: accuracy degrades with no error, no log, no
attribution. Hence the column "raw or offset": anything corpus-fitted may only ever be
exposed as a **bounded offset/multiplier around the `presetFor` default**, so "reset"
is always the measured operating point and the excursion range is pre-bounded.

## A. Decode-time parameters — the inventory

| Parameter | What it does | Current value / provenance | User-tunable? | Raw or offset | Notes |
|---|---|---|---|---|---|
| `ctc_beam_width` | Hypotheses kept per frame after length-aware pruning. Bigger = more recall for long/sloppy swipes, more CPU per frame. | **100** (`Defaults`/`Config.kt:321,595`). Every campaign number was decoded at 100 (`CtcScoringParams.kt:98-100`); FUTO ships 300. Read per decode, clamped `coerceIn(10,300)` (`CtcEngineAdapter.kt:741`), validated 10..300 on import (`SettingsValidation.kt:247`), slider-clamped on load (`CtcSettingsActivity.kt:240-241`). | **Yes — already exposed** (CTC Settings slider). | Raw, bounded — acceptable because the quantity is monotone-ish and self-explaining (accuracy vs battery), and the default is the validated point. | The 10..300 clamp is a guard, not a promise: only 100 is validated; note in UI copy that <100 trades accuracy for speed *unmeasured*. |
| `topK` | Truncation of the final sorted word list; the slate handed to the pipeline. | Hard-coded **8** (`CtcEngineAdapter.kt:124`). Costless at decode time (truncates the final sort only); bar renders ~5; pipeline appends possessives/next-word after. | **No — keep internal.** | n/a | No accuracy lever (top-1 unchanged by construction); >8 is documented noise (`:117-123`). Would become relevant only if Part 1's rescorer someday wanted a deeper slate — that is a code change with a measurement, not a knob. |
| `gamma` | GNMT length-normalization exponent on the raw CTC path score. Higher = long words penalized less by accumulation. | **0.9**, corpus-fitted (`tunedV2`), test-validated on test-2400 (`CtcScoringParams.kt:80-97`). Language-invariant per the λ sweep (`:152-153`). | **Not recommended.** Interacts with β and the emission scale; no user-legible semantic. | If ever: bounded offset ±0.2 in a developer section, never raw. | The E1↔tunedV2 footing disagreement (γ 0.9 vs 1.05, `:167-180`) shows γ is footing-specific — a raw value is meaningless without its co-fitted friends. |
| `lambda` | Weight of the frequency prior vs path evidence. Higher = common words win more; lower = trust the finger. | **4.0 (en JSON scale) / 2.0 (CKDT scale)** via `presetFor` (`:114,129,155-165`); per-language eval `docs/eval/2026-08-15-ctc-per-language-lambda.md`. | **The only scoring constant with a defensible user semantic** ("prefer common words" ↔ "prefer exact path"), but still fitted. | **Offset only**: a bounded multiplier (e.g. ×0.5..×2.0) applied to `presetFor`'s λ — NEVER a raw value, because the correct raw λ **changes 2× with the lexicon asset**; a stored raw λ silently breaks on any future lexicon-source change (this exact λ-scale coupling is why the en langpack swap is unsupported, `CtcEngineAdapter.kt:390-397`). | Known open caveat: λ amplifies top-of-scale user custom words (clamped to 255, ranked ahead of equals — `CtcLexiconMerge.kt:13,46,61`); the ru plan requires re-confirming λ with user dictionaries present (`CtcScoringParams.kt:197-200`). A user-raised λ multiplier makes that worse — bound the upper end tightly. |
| `beta` | Per-character length bonus in the final score. | **0.25**, corpus-fitted, language-invariant. | No — same class as γ. | Offset only if ever. | |
| `alpha` | Context-LM rerank weight — **carried for preset fidelity, UNUSED by the decoder** (`CtcScoringParams.kt:23-25,30`; no term in `CtcBeamDecoder`). | 0.0 in every reachable preset. | **No — it is a placebo.** Exposing a knob wired to nothing is worse than exposing nothing. | n/a | Part 1 deliberately does NOT route through α: the rescorer lives at the pipeline seam, not in the beam (§1). α stays a historical FUTO-preset field. |
| `gammaPrune` | Length-aware pruning exponent (which hypotheses survive each frame). Wrong values silently drop the correct path before scoring. | **0.25**, corpus-fitted. `gammaPrune == betaPrune == 0` flips the decoder into raw-score pruning (`CtcBeamDecoder.kt:96-98`) — a mode change, not a tuning. | **No.** Failure is invisible (recall loss with no signal); interacts with beamWidth. | No. | |
| `betaPrune` | Length-aware pruning per-depth bonus. | **0.9882**, corpus-fitted. | **No** — same as γ_prune. | No. | |
| `finger_occlusion_offset` | Signed % of one key row added to raw touch Y before the engine normalizes it — fingertip-occlusion compensation. | **0** (`Config.kt:148,606`), validated −25..25 (`SettingsValidation.kt:250`), applied at BOTH swipe adapters' ingest through the shared `FingerOcclusion.yShiftPx` (`CtcEngineAdapter.decodeAsync`, `GeometricEngineAdapter.decodeAsync` — the geometric half landed 2026-08-28, ARC-005). | **Yes — already exposed**, default-off. | **Already an offset knob — this is the template.** Signed, bounded, unit tied to geometry (% of a key row), default = do nothing. | Deliberately did NOT inherit the removed neural engine's 12.5 %. |
| `onnx_xnnpack_threads` | ORT session thread count for the encoder. | Default per `Defaults.ONNX_XNNPACK_THREADS`, clamped 1..8 (`CtcEngineAdapter.kt:192-196`, `CtcSettingsActivity.kt:242-243`). | **Yes — already exposed** (CTC Settings). | Raw, bounded — latency/battery only, no accuracy effect. | Session-level, applied on next session build. |
| `swipe_engine_mode` | Routes ctc vs geometric per layout/language (`InputCoordinator.kt:526-537`). | `"ctc"` default since 2026-08-18. | **Yes — already exposed** (dropdown). | Enum. | Listed for completeness; routing, not scoring. |
| Contraction-injection frequency floor | Frequency for injected alias keys (`dabaissement`) so pseudo-words are reachable but get zero frequency bonus (`ln(1+1e-10) ≈ 0`). | `INJECTED_FREQUENCY = CtcLexiconMerge.MIN_FREQ = 1.0` (`CtcContractionKeys.kt:53`, `CtcLexiconMerge.kt:26`). | **No — it is a policy invariant, not a tuning.** Raising it re-creates the deleted `OptimizedVocabulary` bug (boosted pseudo-words outranking real vocabulary — `CtcContractionKeys.kt:22-33`); lowering it is impossible (floor of the scale). | n/a | Unit-tested in `CtcContractionKeysTest`. |
| Custom-word frequency clamp | User custom words merged at freq clamped 1..255, custom-first ordering (`CtcLexiconMerge.kt:13,26-27,46`). | Scale property (λ is fitted against 1..255). | **No** as a knob; users already control the *input* frequency per word via the dictionary UI. | n/a | Values >255 in prefs clamp silently — by design. |
| a–z projection policy | CKDT lexicons: NFD → strip combining marks, expand `ß/œ/æ/ø`, then require a–z; canonical form kept for display. | Fixed policy; changed during the 1.6 audit remediation. | **No.** Projection changes invalidate language evidence. | n/a | Refresh the per-language evaluation before release; this is not a setting. |
| Display overlay behaviour | Accents first, then contraction aliases, real-word-ordinal-guarded (`CtcEngineAdapter.kt:577-667`; order rationale `:593-594`). | Fixed composition order; per-language mapping files are data. | **No toggle.** The *data* is already user-influenced (custom/disabled words rebuild the trie via the content-hash version, `:62-64`); the *order* is a correctness invariant. | n/a | |
| The `coerceIn(10,300)` clamp itself | Defense-in-depth on the pref read (`CtcEngineAdapter.kt:741`), mirrored in import validation and the settings slider. | 10..300. | **No** — the clamp is the container for the knob, not a knob. | n/a | Keep the three sites in agreement if the range ever changes (adapter, `SettingsValidation.kt:247`, `CtcSettingsActivity.kt:241`). |

## B. NOT surfaceable — baked into the ONNX graph and its input contract

These are export-time properties of `models/ctc_swipe_encoder.onnx`. No preference can
change them; changing any of them means retraining or re-exporting a model
(architecture guide §1.1, the graph contract at `ctc-architecture-and-multiscript-guide.md:30-37`):

| Baked-in property | Value | Consequence |
|---|---|---|
| Key-slot capacity | **64 slots + 1 blank** emission head (`MAX_KEYS = 64`, `CtcFeaturizer.kt:26`; head width 65). The model has no alphabet — column *c* means "the key placed in slot *c*" (guide §1.1). | Alphabets up to 64 keys fit ANY future script with no model change (`CtcLexiconTrie` bound is `alphabet.size <= 64`, `CtcLexiconTrie.kt:197-199`); a 65th key cannot exist without re-export. |
| Input path length | `features [1, 2, 64]` — exactly 64 resampled points, x row then y row (`RESAMPLE_LENGTH = 64`, `CtcFeaturizer.kt:23`). | Longer/shorter gestures are resampled, never truncated in time — but the resample is part of the training distribution (below). |
| Emission frames | **32 output frames** — `log_emissions [1, 32, 65]` (guide `:34`; "~T/2 frames for a T=64 input", `CtcEmissions.kt:8-9`). | **This imposes the word-length ceiling — see below.** |
| Resampling | Two-stage, training-matched: ~60 Hz time-uniform lerp (`HZ_INTERVAL_MS = 1000/60`), then index-uniform to 64 with [0,1] clip (`CtcFeaturizer.kt:58-121`), float64 intermediates for golden parity. | Any "smarter" resampling puts inputs out of distribution. Not a knob; not even a code-level free choice. |
| Coordinate frame | Letter-key bounding-box normalization — NOT the keyboard frame, NOT FUTO's 4/3 device frame (`CtcEngineAdapter.kt:48-52`; guide §1.3). `VERTICAL_ASPECT` exists in the featurizer for the FUTO-frame helpers but the adapter must not use it (`:51`). | The trained geometries all live in this frame; a frame change is an accuracy cliff (the Cyrillic conversion measured it, guide `:82-89`). |
| Head semantics | log-softmaxed emissions; blank at column 64, relocated to `numLetters` by `sliceFromHead` (`CtcEmissions.kt:54-90`). | The slice is a contract port of FUTO's `predict_segment`; not configurable. |
| λ-scale ↔ lexicon-asset coupling | Not in the graph, but contract-adjacent: λ is calibrated to the *frequency scale of the bundled asset* (`CtcScoringParams.kt:131-141`). | Why the en langpack CKDT swap is unsupported (`CtcEngineAdapter.kt:390-397`) and why any exposed λ control must be an offset (table A). |

### The ~32-character word-length ceiling, explained

The beam consumes **one emission frame per move** (`CtcBeamDecoder.kt:103-151`).
Advancing the trie by one letter costs at least one frame (move B), and there are exactly
**32 frames**. Therefore no word longer than 32 letters can ever complete —
`node.depth ≤ 32` is a hard arithmetic bound regardless of beam width, λ, or anything

else in Part A. In practice the bound is lower:

- **Doubled letters cost extra.** The decoder itself permits a same-letter advance on
  consecutive frames (move B never checks `blankEnded`; only move C — the *stay* repeat —
  does, `CtcBeamDecoder.kt:120-141`, and the port note says blank is not required between
  *distinct* characters, `:37-39`). But the encoder was trained with standard CTC loss,
  whose convention separates a doubled letter's two emissions with a blank — so the
  emission mass for `l·l` on adjacent frames without an intervening blank is trained to
  be low. Each doubled letter effectively costs ≥1 extra frame; "bookkeeper" (3 doubles)
  spends ~13 frames on 10 letters.
- Blank frames at gesture start/end and during slow inter-key travel consume more of the
  32.

**The failure is silent today.** Neither `CtcLexiconMerge.merge` nor `CtcLexiconTrie.insert`
has any length check — a 40-character user custom word merges fine, occupies trie nodes,
appears in the dictionary UI, and is simply **never decodable by the CTC engine**: the
beam cannot reach its terminal node within 32 frames, so it never appears in a slate, and
nothing warns anyone. (The bundled `en_enhanced.json` vocabulary is safely inside the
bound; the exposure is user custom words and future langpack lexicons.)

**Recommendation (follow-up, not part of the Part 1 plan):** when a custom word is added
for a CTC-served language (`CtcLanguageSupport`), warn above a conservative threshold —
letters + doubled-letter count > ~28 — that the word will only be reachable by tap/prefix
prediction and the geometric engine, not by CTC swipe. The check is pure arithmetic on
the word; it belongs in the dictionary-manager add path, not the decoder.

---

*Document authored against `main` @ `31b289c0`; no code was changed. Part 1 step 6 (any
default flip) and the Part 2 word-length warning are explicitly out of scope for this
document's commit.*
