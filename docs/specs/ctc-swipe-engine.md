# Feature Specification: CTC Swipe Engine (`ctc` mode — WIRED, opt-in)

**Status (2026-08-15, final campaign state):** WIRED behind the opt-in Prediction Engine
dropdown (default stays `neural`). The CleverKeys-trained CTC encoder ships as
`models/ctc_swipe_encoder.onnx` = CleverKeys-ML **`phaseM_kd_fresh_w1_s1234_fp16w`**
(3,052,318 B / 2.91 MB, sha256 `84718e6e…549e88e5`), the Phase-M distilled single model —
a ch192 student distilled from the coupled pair. Integration per
`CleverKeys-ML/ctc/APP_INTEGRATION_PLAN.md` (commits 3b9dd666..743b58fa for the wiring,
fdfb0ea7/23599e51 for the parity gate and the O10 preset axis):
`OnnxCtcEmissionModel` + `CtcEngineAdapter` + `SwipeEngineRouter.Mode.CTC` (QWERTY→CTC,
other layouts→geometric hedge) + `CtcSettingsActivity` (beam-width knob, default 100).
**Package:** `tribixbite.cleverkeys.swipe.ctc` (`src/main/kotlin/.../swipe/ctc/`)
**Origin:** Track (ii) of `docs/audit/2026-08-06-futo-upgrade-plan.md`; algorithm ground
truth is the integration study `docs/audit/2026-08-06-futo-decoder-integration-study.md`
(cited "study §N") + the Python port `scripts/futo_decoder_{eval,ceiling}.py` and FUTO C++
`~/.cache/cleverkeys-test/swipe-library-src` (`resampler.cpp`, `beam_search.cpp`).

### Evidence tier of the shipped model — quote it with its limitations

The fourth and **final** unsealing of the sealed `test-2400` split
(`CleverKeys-ML/ctc/UNSEALING_4.md`, pre-registered and pushed at `b91f179` *before* any
decode; six decodes, one per (config, seed), no retries; ledger 3 → 4, **there is no
fifth**) put this model on both footings:

| footing | seed-mean t1/t3/t5/≤3/4+ | bar | Δ |
|---|---|---|---|
| **A** — AOSP STRIP 146,964 at benchmark preset E1 | 88.931 / 92.681 / 93.361 / 92.597 / 87.045 | FUTO published `84.83/91.04/92.08/89.57/82.40` | +4.10 / +1.64 / +1.28 / +3.03 / +4.64 |
| **B** — the SHIPPING footing: `en_enhanced` STRIP trie 98,081 at the app preset `0.9/4.0/0.25/0.25/0.9882` | **89.306 / 93.792 / 94.500 / 93.701 / 87.045** | trie-matched `84.92/91.54/92.96/89.57/82.52` | +4.39 / +2.25 / +1.54 / +4.13 / +4.53 |
| **equal footing** (both engines val-tuned, same rows/trie/beam/OOV rule) | same as A | `87.12/92.29/92.96/89.94/85.68` | +1.81 / +0.39 / +0.40 / +2.66 / +1.36 |

All five clear on the seed-mean **and on every individual seed** on all three, worst-seed
top-5 margin **+1.50** on the shipping footing. Exact paired two-sided McNemar on top-1
against FUTO's val-tuned per-row output resolved **3 of 3 seeds at p < 5e-4** (+45/+46/+39
rows) → the model is **TEST-VALIDATED** and holds a **qualified equal-footing win** — the
registered ceiling on that claim, *not* a general superiority claim.

**Two limitations travel with those numbers and must not be dropped when quoting them:**

1. **The equal-footing lead is bought entirely on the HWS corpus half.** Per-source top-1:
   FUTO's val-tuned engine **beats us by +0.38 on its own corpus half** (95.89 vs 95.51);
   our +1.81 aggregate comes from the HWS half (+4.05). What is demonstrated is better
   *coverage across two corpora*, not better decoding per se (`UNSEALING_4.md` §8.4).
2. **ch 192 keeps top-5 by 0.14** (93.50 vs our 93.361 on config A) — it is the one metric,
   of the five, on which an earlier model stays ahead, at 6.14 MB against our 2.91 MB.

Further caveats that travel with every test-2400 number: T3 contributor contamination, the
dedup defect, the ~12–14 pt FUTO/HWS internal spread, and the preset asymmetry on
published-bar comparisons (ours tuned, FUTO's published). The fp16w artifact that actually
ships was **not itself decoded** — fp32 was; fp16w ≡ fp32 to 0.00 on all five metrics at the
app footing on val (§2.2 of `UNSEALING_4.md`), so the numbers carry by measurement, not
assumption.

**Preset provenance (a disclosed gap, not a fitted result):** `0.9/4.0/0.25/0.25/0.9882`
was fitted on `resbn80g` and has **never been swept for this model family** on the app
trie. Config B validates it on the sealed split at that preset; it is not this model's own
optimum, which was never sought.

### The fixture-and-preset rule (why three things move together)

`MODEL_COMPARISON.md` §5.1: the shipped ONNX, the runtime preset, and the golden fixture
**always move together**. The fixture records its own `source_onnx_sha256` and `preset`;
shipping the model at one preset and the fixture at another makes the parity gate assert
against a configuration nothing runs. Current triple:

| corner | value |
|---|---|
| model asset | `src/main/assets/models/ctc_swipe_encoder.onnx` sha256 `84718e6ebc8020176f27b9668e50922a765c96838307b640a8db9ab0549e88e5` |
| fixture (both copies) | `src/test/resources/ctc/ctc_golden.json` + `src/androidTest/assets/ctc/ctc_golden.json`, byte-identical, sha256 `2a449c4f2de19505131b396655ae01d3e3c325e40249446ff6e7a40c2b27559c` (= ML `artifacts/phaseM_kd_fresh_w1_fp16w_golden.json`, regenerated 2026-08-14 at the **ship** preset — the first cut was generated at E1 and is superseded, `PHASE_M.md` §11.1) |
| runtime preset | `CtcScoringParams.tunedV2()` = `0.9 / 4.0 / 0.25 / 0.25 / 0.9882`, beam 100, top-4 |

All three corners are pinned by `CtcParityTest.fixture_model_and_shipPreset_travelTogether`
(pure JVM, no device: hashes the asset, compares the fixture preset term-by-term against
`tunedV2`, and asserts the two fixture copies are byte-identical). The device half —
"the artifact actually *produces* those emissions through ORT" — is
`CtcEmissionModelParityTest` (see Testing Strategy).

> **Scope note (superseded scope guard).** Earlier revisions of this spec carried a guard
> saying the module was dead code unreachable from the IME. That is **no longer true**: the
> mode is wired end-to-end and user-selectable. What remains out of scope here is listed
> under Non-Goals and "Max accuracy" below.

---

## Feature Overview

### Summary
A fourth swipe-decode engine in the pattern of `swipe/geometric/`: a **non-autoregressive
CTC trie-beam decoder** that consumes per-frame log-emissions from a CTC-emission encoder
and returns a scored candidate slate. The decode strategy (featurizer + trie + Viterbi CTC
beam) is portable today and is fully implemented + tested here; the model family that
produces the emissions is a hard fork (retrain/re-export) and is the sole blocker.

### Motivation
The measured levers (study §5a, plan "Framing"):
- FUTO's decisive **structural** advantage is CTC's one-NN-call decode: the beam is pure
  CPU, so FUTO affords beam 300 vs our autoregressive beam 6 (study §6 item 1). This is
  the source of its long-word advantage (4+ chars: 77.6% vs our 69.3%, study §5b).
- The single measured **accuracy** lever is the per-layout refinement head (`magic_macaw`):
  **+5.88 pt top-1** (study §5a). The beam algorithm itself was ≈neutral.
- Head-to-head is **stratified, not dominated** (FUTO leads long words, our transformer +
  freq rerank leads ≤3-char words), so the product posture is a *complement behind a
  router*, not an assumed replacement (plan Key open decision 3, O7).

Prototyping the portable half now (this module) de-risks the fork: the beam, featurizer,
and trie are validated against the offline port before any model investment.

---

## Requirements

### Functional Requirements
- **FR-1** Decode a `[frames][K+1]` log-emission matrix + a lexicon trie into a top-k word
  slate via FUTO's single-stream Viterbi trie CTC beam (3 transitions/frame:
  blank / advance-to-child / repeat-char; MAX-merge dedup; length-aware pruning; final
  `ctc/L^gamma + weight*beta*L + lambda*logFreq`). — **DONE** (`CtcBeamDecoder`).
- **FR-2** Featurize a normalized touch path into the encoder's `[2,64]` tensor via FUTO's
  two-stage resample (60 Hz linspace `round(dur/16.667)+1` → fixed-64, index-uniform,
  clamp [0,1]) + build the layout key-center/mask tensors, honoring the 4/3 vertical aspect
  contract. — **DONE** (`CtcFeaturizer`).
- **FR-3** Provide a lexicon trie over the active alphabet with per-word AOSP-scale
  (1..255) log-frequency and the `ITrie` accessors the beam needs, plus loaders that either
  skip or a-z-strip out-of-alphabet words. — **DONE** (`CtcLexiconTrie`).
- **FR-4** Expose a facade (`CtcSwipeDecoder`) that wires featurizer → emission model →
  beam in the one call shape a `ctc` engine mode would invoke. — **DONE** (seam), with the
  emission model itself **BLOCKED** (see FR-5).
- **FR-5** Obtain per-frame emissions from a CTC-emission encoder (+ optional refinement
  head). — **DONE (2026-08-08)**: `OnnxCtcEmissionModel` over the shipped
  `models/ctc_swipe_encoder.onnx` (refinement head not needed — the trained encoder beats
  all bars without it).
- **FR-6** Slot a `ctc` value into `swipe_engine_mode` so the selector routes qualifying
  swipes to this engine. — **DONE (2026-08-08)**: `Mode.CTC`/`Engine.CTC` wired end-to-end
  (router → `CtcEngineAdapter` → the unified suggestion pipeline), opt-in via the
  Prediction Engine dropdown.

### Non-Functional Requirements
- **NFR-1 (purity)** The core never touches Android or SharedPreferences — pure JVM,
  testable via `runPureTests` (matches `swipe/geometric/` NFR-3).
- **NFR-2 (parity)** Beam math runs in `Double`; emission values are read as `Float`
  (float32) then widened — mirroring the Python port so golden top-k words match exactly
  and scores match within libm tolerance.
- **NFR-3 (determinism)** Insertion-ordered dedup + stable descending prune sort reproduce
  the port's tie handling; decode is deterministic for a given input.
- **NFR-4 (frequency scale)** Log-frequency stays on the AOSP 1..255 log scale end-to-end
  (study H5); normalized `[0,1]` frequency would make `lambda` ~2 orders of magnitude too
  weak.

---

## Technical Design

### Architecture / Module skeleton
```
src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/
├── CtcScoringParams.kt   # scoring.json presets (encoderOnly / encoderDecoder / fallback)
├── CtcEmissions.kt       # [frames][K+1] log-emission value type + sliceFromHead()
├── CtcLayout.kt          # alphabet (emission-column order) + key centers
├── CtcLexiconTrie.kt     # trie + ITrie-style nodes + freq-map loaders
├── CtcFeaturizer.kt      # resampler.cpp port: 60Hz→fixed64, layout tensors, 4/3 aspect
├── CtcBeamDecoder.kt     # greedy CTC + single-stream Viterbi trie beam  (the core)
└── CtcSwipeDecoder.kt    # facade + CtcEmissionModel seam (the retrain-fork boundary)
```
Tests: `src/test/kotlin/tribixbite/cleverkeys/swipe/ctc/{CtcParityTest,CtcModuleTest}.kt`;
golden fixture `src/test/resources/ctc/ctc_golden.json` (regen:
`scratchpad/gen_ctc_golden.py`, imports the real port).

### Algorithm (port of `beam_search.cpp` / `futo_viterbi_beam`, study §3)
Per output frame, each hypothesis `(score, trieNode, blankEnded)` expands into three CTC
moves against that frame's log-probs, deduped by `(nodeId shl 1) or blankEnded` with a
**MAX** merge (Viterbi, not log-sum):
- **A. blank** — stay on node, set `blankEnded`; key `(id shl 1) or 1`, `+= p[blank]`.
- **B. advance** — for each trie child, move to it; key `childId shl 1`, `+= p[childChar]`.
- **C. repeat** — re-emit the node's own char, stay (only if `!blankEnded` and node ≠ root);
  key `id shl 1`, `+= p[nodeChar]`. Unlike textbook CTC there is no required blank between
  distinct chars (`beam_search.cpp:241-242`).

Pruning to `beamWidth` uses the **length-aware** key
`score / max(depth,1)^gammaPrune + betaPrune*depth`, distinct from the final length norm.
Complete-word nodes score `ctc/max(len,1)^gamma + beta*len + lambda*logFreq`, dedup by
surface form (max), truncate to `topK`.

### Data structures / API
- `CtcEmissions(values: FloatArray, frames, numClasses)` — row-major `[frames][K+1]`, blank
  last. `sliceFromHead(fullHead, frames, maxKeys, numLetters)` reproduces
  `engine.cpp::predict_segment`'s slice (blank relocated from column `maxKeys` → `numLetters`).
- `CtcLexiconTrie(alphabet: CharArray)` — `insert(word, freq)`, `contains`, `charIndexOf`;
  `CtcTrieNode` exposes `id / charIdx / depth / isWord / logFreq / children / word()`.
  Loaders `loadFromFrequencyMap` (skip non-alphabet) / `loadStrippingNonAlphabet` (a-z-strip
  apostrophes: `don't`→`dont`).
- `CtcFeaturizer.featurize(px,py,pt): FloatArray` (`[x0..x63,y0..y63]`),
  `buildPaddedLayout(layout)`, `normalizeRawX/Y` (4/3 aspect + affine).
- `CtcBeamDecoder.decode(emissions, trie, params): List<CtcCandidate>`, `greedy(...)`.
- `CtcSwipeDecoder(model, layout, trie, params).decode(px,py,pt)` — the end-to-end call.

### The retrain/re-export boundary (DONE vs BLOCKED)

| Piece | Status | Where |
|---|---|---|
| CTC Viterbi trie beam (3 transitions, MAX-merge, length-aware prune, final score) | **DONE, tested** | `CtcBeamDecoder` |
| Featurizer (60 Hz linspace → fixed-64, [0,1], 4/3 aspect, key-centers tensor) | **DONE, tested** | `CtcFeaturizer` |
| Lexicon trie (a-z, per-word 1..255 log-freq, ITrie accessors, loaders) | **DONE, tested** | `CtcLexiconTrie` |
| `scoring.json` presets (encoder-only / encoder+decoder / fallback) | **DONE, tested** | `CtcScoringParams` |
| Facade wiring featurizer → emissions → beam | **DONE (seam)** | `CtcSwipeDecoder` |
| Per-language preset axis (`presetFor`, ru λ 2.0) | **DONE** (axis only; en gate unchanged) | `CtcScoringParams` |
| **Per-frame CTC emission encoder** | **DONE (2026-08-08)** — trained in CleverKeys-ML (Phases A→M), shipped as `phaseM_kd_fresh_w1_s1234_fp16w` | `OnnxCtcEmissionModel` |
| Per-layout refinement head (`magic_macaw`, the +5.88 pt lever) | **NOT NEEDED** — the trained encoder clears every bar without it | — |
| Two-member "max accuracy" pair (per-frame prob averaging) | **FUTURE-OPTIONAL** — val-only evidence, +1.5 MB (section above) | future `CtcEmissionModel` decorator |
| Context-LM rerank (`hungry_jellyfish`, `alpha·lm`) | **NOT SCHEDULED** — a 21.8 KB rescorer is measured as a small t1/t5/4+ lever, not a ≤3 lever | future reranker over `CtcCandidate` |
| ONNX-vs-ExecuTorch runtime decision (A3 spike) | **RESOLVED: ONNX** (no second runtime) | `ModelLoader` / ORT |

The fork the original spec was blocked on is closed: the emission model exists, is bundled,
and is test-validated. What remains open is a menu of *optional* accuracy add-ons, each with
its evidence tier stated in the row above.

---

## Engine-selector integration (`swipe_engine_mode` — WIRED as described)

`swipe_engine_mode` ∈ `{neural, hybrid, geometric, ctc}` (`Config.Defaults.SWIPE_ENGINE_MODE`
stays `neural`; parsed by `SwipeEngineRouter.Mode.fromPref`, routed by
`SwipeEngineRouter.route`). The `ctc` mode landed with exactly the changes below; they are
kept in imperative form as the record of what was applied:

1. **Pref value** — add `"ctc"` as a recognized `swipe_engine_mode` string. Keep it OFF the
   user-visible Settings → "Prediction Engine" selector until a model ships (so it is
   inert/hidden). `Config.Defaults.SWIPE_ENGINE_MODE` stays `"neural"`.
2. **Router** — extend `SwipeEngineRouter.Mode` with `CTC` and `fromPref("ctc") -> CTC`;
   add `Engine.CTC`. Routing table (mirrors the geometric layout gate):
   - `CTC` mode + QWERTY-Latin (or the layouts the CTC model was trained for) → `Engine.CTC`.
   - Because the CTC encoder is layout-parameterized (study D2), a mature model can serve
     **all** layouts — at which point `CTC` may also back a `hybrid`-style split
     (neural short words, ctc long words) per the O7 router verdict (plan B4).
3. **Engine construction** — `CtcEngineAdapter.decoderFor` builds
   `CtcSwipeDecoder(model, layout, trie, CtcScoringParams.presetFor(language, beamWidth,
   topK=8))`, memoized on `(layout, trie, beamWidth, language)`. `model` is
   `OnnxCtcEmissionModel` over the bundled asset; `layout` is the on-screen `CtcLayout`
   (a–z key centers from the active `KeyboardData`, normalized over the LETTER-KEY
   bounding box); `trie` is a `CtcLexiconTrie` built from `en_enhanced.json` + user/custom
   words − disabled words, a–z-stripped, on the 134..255 log scale.
4. **Output contract** — `List<CtcCandidate>` maps to the same scored candidate slate the
   geometric engine emits, feeding the SAME single seam
   `SuggestionHandler.handleSwipePredictionResults` (via `InputCoordinator`), so `ctc`
   inherits the password guard, possessive augmentation, shift/caps transform, and the
   commit engine with zero engine-specific presentation code (matches
   `SwipeEngineRouter`'s KDoc contract). Scores are engine-relative — never compared across
   engines.
5. **Two-phase decode (study §4f / D3)** — preview during-gesture with
   `CtcScoringParams(beamWidth=32, topK=1)`, commit at gesture-end with `beamWidth=300,
   topK=4`. Both call `CtcBeamDecoder.decode`; only the params differ.

### Where emissions would come from (the fork)
A `CtcEmissionModel` implementation would run the CTC encoder (`honorable_sturgeon`-style)
— either FUTO's published `.pte` via an ExecuTorch JNI runtime, or a re-exported/retrained
ONNX model run through CleverKeys' existing ONNX Runtime (plan A3 decides). It receives the
featurizer's `[2,64]` tensor + `PaddedLayout`, runs the encoder (+ optional `magic_macaw`
refinement), slices via `CtcEmissions.sliceFromHead`, and returns the active-alphabet
emissions. Until such a model + export exists, this interface is unimplemented and the whole
mode stays inert.

---

## Per-language decode preset (integration-plan **O10**) — IMPLEMENTED (axis only)

**The decode preset is a property of the (emission model, lexicon frequency SCALE) pair,
not of the model alone**, and the app serves more than one scale. λ multiplies a raw
log-frequency, so a lexicon whose frequency bytes span a narrow range needs a
proportionally larger λ for frequency to carry comparable weight.

| language | preset | scale it is fitted to | evidence |
|---|---|---|---|
| `en` (and every unrecognized tag) | `tunedV2` — `0.9 / 4.0 / 0.25 / 0.25 / 0.9882` | bundled `en_enhanced.json`, compressed **134..255** byte scores | test-validated (config B above) |
| `ru` | `tunedRuCkdt` — E1 with **λ 1.1 → 2.0**, i.e. `1.05 / 2.0 / 0.2 / 0.3734 / 0.9882` | langpack CKDT v2, **`255 − rank`** | **val-only**, `CleverKeys-ML/ctc/PHASE_J.md` §6.9 |

The ru sweep (λ ∈ {1.1, 2.0, 3.0, 4.0}, tuned on ru val rows `0:4708`, confirmed on the
untouched `4708:9416`, run symmetrically over **both** ru models) puts the optimum at
**2.0** on every half of both models: 75.73 / 76.70 → **76.91 / 77.92** in-dict top-1 for
the shippable synth-only model, ≈ **+1.2 pt**. It is a decode constant, not a model
property — it lifted the joint challenger by the same order, which is why the ML campaign
records it as model-independent and why every previously published Cyrillic figure
(including the 76.21 bar) is **under-tuned**; the honest expectation is ≈ 77.4.

**What landed here is the axis, and only the axis:**

* `CtcScoringParams.presetFor(language, beamWidth, topK)` — the language → preset table,
  primary-subtag matching, case-insensitive, defaulting to `tunedV2` (a working English
  decode is a better failure mode for an impossible tag than a crash).
* `CtcScoringParams.tunedRuCkdt(...)` — the λ = 2.0 preset, registered so the constant is
  not lost.
* `language` is now part of `CtcEngineAdapter`'s decoder memo key, so a dictionary-language
  switch rebuilds the decoder instead of silently reusing the previous language's λ.

**What did NOT change, deliberately:** the en-only gate. `CtcEngineAdapter.LANGUAGE = "en"`
and `InputCoordinator.performCtcSwipeTyping`'s upstream fall-through to the neural flow both
stay exactly as they are (pinned by `CoreImeHygieneDriftTest`). Relaxing O5 needs a bundled
**Cyrillic encoder** (none exists — nothing in `CleverKeys-ML/ctc/artifacts/` is ru) *and* a
trie source on the CKDT scale; it is not a preset problem. No new preference was added — the
axis is keyed off the language the adapter already receives.

**Caveat that travels with λ:** no evaluation in the campaign included a **user dictionary**,
and λ multiplies the frequency term, so a larger λ amplifies top-of-scale injected
competitors. λ = 2.0 must be re-confirmed with user-dictionary entries present before any ru
path ships (plan §7.1/§7.3).

---

## "Max accuracy" pair mode — FUTURE-OPTIONAL, documented, **not implemented**

`CleverKeys-ML/ctc/MODEL_COMPARISON.md` and `PHASE_M.md` §11.2 record a second ship option
(**option A**) that the app deliberately did not take. It is written down here so the choice
stays visible rather than being rediscovered.

### What it is

`v2pair-s1234`: the **two members of one coupled training run**, run as two ONNX sessions,
with their per-frame emission probabilities **averaged before the beam** (probability space,
one averaged log-emission matrix into the existing `CtcBeamDecoder` — *not* a fusion of two
candidate lists). Members ship at different numeric formats because that was measured free:

| member | artifact | bytes |
|---|---|---|
| A | `phaseL_v2pair_s1234_a_int8w.onnx` | 1,554,355 |
| B | `phaseL_v2pair_s1234_b_fp16w.onnx` | 3,052,318 |
| | **total** | **4,606,673 (4.39 MB)** |

Against the shipped single model that is **+1,554,355 B ≈ +1.5 MB per ABI** and a second
ORT session (encoder 1.79 ms vs the single model's 0.83 ms class).

### Why the recipe is trustworthy (the provenance that matters)

Pair compatibility here is **trained in, not gated for**: the two members are trained
together with a KL coupling term (`--pair-weight`, coupling weight **0.3**, confirmed
interior-optimal on a four-point sweep). Six of six coupled pairs passed the label-free
≥ 95 % per-frame agreement gate at **98.05–98.33 %**; the identical `--pair-weight 0`
control finished at 92.09 % and its averaged mix **collapsed to greedy 29.10** (its
individual members were 72.6 / 71.8). This is the distinction from the older `mix2-i8f16`
"card", which hit similar numbers as a one-off draw whose recipe demonstrably **did not**
reproduce. The pair reproduces by construction — and it is also the **teacher** the shipped
model was distilled from, so shipping the pair is not a different bet, it is the undistilled
version of the same one.

### Its evidence tier — **val-only, permanently**

| claim | tier |
|---|---|
| 11 of 11 campaign bars on **all five seeds** (five-seed mean margins +0.12 … +2.76) — the only configuration in the campaign to do so | **val + alt-layout only** |
| val 88.86 / 92.82 / 93.59 / **91.56** / 87.46; dvorak 92.88, dvorak-app 92.59, azerty 84.11, qwertz 84.41, german 82.26, spanish 89.76 | **val + alt-layout only** |
| anything on test-2400 | **none — it was never decoded and never will be** |

The seal is spent: four unsealings, no fifth, by pre-registration. The pair was
*deliberately* not decoded (`UNSEALING_4.md` §1) because only one model ships. So the pair
is more accurate **on val** (s1234 t1 88.90 vs 88.62) with **deeper seed evidence** (5 vs 3),
while the shipped single model is the one with **sealed-split evidence**. Choosing the pair
means trading an evidence tier for a few tenths — an accuracy-first call, and the ML
campaign's own recommendation was **B, the single model**.

### What implementing it would touch

1. `CtcEngineAdapter` — a second `MODEL_ASSET` + a second `OnnxCtcEmissionModel`, both
   under the existing bounded-retry/latch logic; warm-up and teardown cover both.
2. A new averaging decorator at the `CtcEmissionModel` seam (the `CtcSwipeDecoder`
   constructor arg): run both sessions, exponentiate, mean, re-log, hand one
   `CtcEmissions` to the beam. The beam, featurizer, trie and preset are untouched.
3. **A regenerated golden fixture** from the pair configuration at whatever preset ships —
   the fixture-and-preset rule is not optional. The fixture format already stores
   `source_onnx_sha256` as an **array**, so a two-member fixture fits without a schema
   change; `CtcParityTest.fixture_model_and_shipPreset_travelTogether` would extend to hash
   both assets.
4. The app-trie preset would need its own answer: `tunedV2` was fitted on `resbn80g` and
   validated on the *single* model; nothing validates it on averaged emissions.
5. Re-run both device gates — `CtcEmissionModelParityTest` and the latency gate (budget
   roughly doubles on the encoder leg; the beam, which dominates, is unchanged).
6. APK **+~1.5 MB per ABI**, plus a second resident session's memory and a longer cold warm-up.

**Verdict recorded here: not now.** Revisit only if field feedback says the last few tenths
matter more than the sealed-split evidence tier and the size, and never without regenerating
the fixture in the same change.

---

## Implementation Plan

### Phase A — plumbing (prototypable without any retrain) — **DONE in this module**
- **A1** Kotlin CTC Viterbi trie-beam (`CtcBeamDecoder`) — golden parity vs the Python port. ✅
- **A2** Kotlin featurizer (`CtcFeaturizer`) — encoder-tensor parity vs the port. ✅
- **A4** Lexicon trie surface (`CtcLexiconTrie`) — 1..255 log-freq, ITrie accessors, loaders. ✅
- **A5** Engine-selector interface design — this spec's "Engine-selector integration". ✅
- **A3** ONNX-vs-ExecuTorch runtime spike — **RESOLVED: ONNX** (retrained to ONNX; no second runtime). ✅
- **A6** Runtime hygiene backlog (mmap, pre-alloc, big-core pinning) — **OPEN** (plan A6).

### Phase B — the hard fork (retrain/re-export) — **DONE for B1/B4**
- **B1** CTC-emission encoder (per-frame key emissions + blank, layout-geometry input) —
  **DONE**: trained in the CleverKeys-ML `ctc/` campaign (Phases A→M), shipped as
  `phaseM_kd_fresh_w1_s1234_fp16w`, test-validated (see Status). ✅
- **B2** Per-layout refinement head (`magic_macaw`-style) — **DROPPED as unnecessary**: the
  trained encoder clears every published, trie-matched and equal-footing bar without it.
- **B3** Context-LM rerank (`hungry_jellyfish`-style) — **NOT SCHEDULED** (the measured
  21.8 KB rescorer is a small t1/t5/4+ lever, and not the ≤3 lever the campaign wanted).
- **B4** Router integration — **DONE**: `Mode.CTC` + `Engine.CTC`, QWERTY→CTC with a
  geometric hedge on other layouts and a neural fall-through for non-en. ✅

### Remaining work on `ctc`
1. Manual on-device QA before any release tag (first-swipe warm-up, long-word feel,
   non-QWERTY hedge, non-en fall-through, contraction display, provenance label, thermals).
2. Optional, recorded, not scheduled: the "max accuracy" pair (section above), the 21.8 KB
   rescorer, contract-v2 `T′ = 64`, two-phase preview decode (plan O7), user-dictionary
   alpha-boost with a cap (plan §7.3).
3. Relaxing the en-only gate (plan O5) — needs a Cyrillic encoder and a CKDT-scale trie
   source; the preset half of it is already done (O10, above).
4. Whether `ctc` should ever become the default engine (plan O6) — after a beta cycle and
   field feedback, not before.

---

## Testing Strategy

### Golden-trace parity (`CtcParityTest`) — the core validation
`src/test/resources/ctc/ctc_golden.json` is the SHIP-model fixture, generated by
CleverKeys-ML `ctc/make_golden.py` from `phaseM_kd_fresh_w1_s1234_fp16w` at the ship preset
(the earlier hand-built `scratchpad/gen_ctc_golden.py` fixture from the pure Python port is
superseded). Cases:
- **Featurizer** (6 cases, exercising every resampler branch incl. single-point,
  zero-duration, non-uniform timestamps, long two-point): asserts the `[2,64]` tensor is
  **bit-identical** float32 to the port.
- **Beam** (4 cases — `model_{cat,the,hello,keyboard}`, real emissions dumped from the ship
  artifact at the ship preset, beam 32 / top-4): asserts **identical greedy-CTC string,
  identical top-k words** (the ranking parity), and top-k final **scores within `1e-4`**
  (`Math.pow`/`ln` differ from the port's C-libm by ≤ ~1 ULP, so word order is the exact
  assertion, scores are a tolerance sanity).
- **The fixture-and-preset rule** (`fixture_model_and_shipPreset_travelTogether`): the
  fixture's `preset` equals `CtcScoringParams.tunedV2()` term by term, every beam case uses
  that preset, `sha256(src/main/assets/models/ctc_swipe_encoder.onnx)` equals the fixture's
  `source_onnx_sha256`, and the `androidTest` fixture copy is byte-identical to the
  resources copy. Pure JVM — this gate runs without a device.

### Unit tests (`CtcModuleTest`) — structural/behavioral coverage
Emission `sliceFromHead` (blank relocation), trie insert/contains/depth/charIdx +
skip-vs-strip loaders, `scoring.json` + `tunedV2` preset constants, the per-language preset
axis (`presetFor` / `tunedRuCkdt`), featurizer shape/range + degenerate branches + 4/3
aspect + padded-layout tensors, beam top-k/lexicon-constraint, and the `CtcEmissionModel`
facade seam.

### On-device gates (`androidTest`, ew-cli)
- `CtcEmissionModelParityTest` — the bundled ONNX, run through `OnnxCtcEmissionModel` on the
  plain ORT CPU EP, must reproduce the fixture's features→emissions→top-k chain
  (`EMISSION_TOL` 2e-3, `SCORE_TOL` 1e-3).
- `CtcLatencyGateTest` — end-to-end decode latency on the production trie.
- Run: `ew-cli … --test-targets "class tribixbite.cleverkeys.swipe.CtcEmissionModelParityTest"
  "class tribixbite.cleverkeys.swipe.CtcLatencyGateTest"` (see
  `.claude/skills/ew-cli-testing.md`; not runnable on the WSL checkout — no `ew-cli`, no
  `EW_API_TOKEN` there).

### Verification
`./gradlew runPureTests -PtestClass=swipe.ctc.CtcParityTest` → OK (3 tests);
`-PtestClass=swipe.ctc.CtcModuleTest` → OK (13 tests). Both registered in `runPureTests`
(drift-checked by `TestRunnerListDriftTest`). Full suite green at **1909** pure tests
(2026-08-15).

---

## Open Questions / Key Decisions (user-owned, from the plan)
1. **Licensing** — **RESOLVED**: no FUTO weights ship. The bundled encoder was trained from
   scratch in the CleverKeys-ML `ctc/` campaign; FUTO's published model was used for offline
   comparison only.
2. **Runtime** — **RESOLVED: ONNX** via the existing `ModelLoader`/ORT. No ExecuTorch JNI, no
   second inference runtime.
3. **Product posture** — **OPEN**: `ctc` ships as an opt-in fourth engine, default stays
   `neural`. Whether it becomes the default (or backs a hybrid split) is plan O6/O7, after a
   beta cycle.
4. **Beam language** — **RESOLVED: Kotlin.** The on-device latency gate passes at beam 100;
   no C++/JNI beam is needed.
5. **"Max accuracy" pair** — **OPEN, recommended NO for now** (section above): +1.5 MB and a
   second session for a val-only accuracy tier.

## Non-Goals
- Not the default engine — `ctc` is opt-in and `SWIPE_ENGINE_MODE` stays `neural`.
- No two-finger / multi-stream beam (FUTO's `recognize_multi`) — the port and this module
  are single-stream; multi-stream is a later extension of `CtcBeamDecoder`.
- No context-LM in the decode module (it is a modular reranker layered on `CtcCandidate`).
- No second ONNX session: the "max accuracy" pair is documented, not implemented.
- No non-English CTC decode: the en-only gate stays until a Cyrillic (or other) encoder and
  a scale-matched trie source exist.
