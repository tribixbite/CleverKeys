# Feature Specification: FUTO-style CTC Swipe Engine (`ctc` mode — DESIGN ONLY, NOT WIRED)

**Status:** Prototype landed (decode + featurize + trie, pure-JVM, tested). BLOCKED on a
CTC-emission model export before it can decode a real swipe or be exposed to users.
**Package:** `tribixbite.cleverkeys.swipe.ctc` (`src/main/kotlin/.../swipe/ctc/`)
**Origin:** Track (ii) of `docs/audit/2026-08-06-futo-upgrade-plan.md`; algorithm ground
truth is the integration study `docs/audit/2026-08-06-futo-decoder-integration-study.md`
(cited "study §N") + the Python port `scripts/futo_decoder_{eval,ceiling}.py` and FUTO C++
`~/.cache/cleverkeys-test/swipe-library-src` (`resampler.cpp`, `beam_search.cpp`).

> **Scope guard.** Nothing in this module is referenced from the IME. It is dead code
> reachable only by `runPureTests`. This spec describes how a future `ctc` value of the
> `swipe_engine_mode` pref *would* slot in — it does **not** authorize wiring it in. The
> mode stays inert/hidden until a CTC model export exists (Phase B).

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
  head). — **BLOCKED on retrain/re-export** (`CtcEmissionModel` has no production impl).
- **FR-6** Slot a `ctc` value into `swipe_engine_mode` so the selector routes qualifying
  swipes to this engine. — **DESIGN ONLY** (see "Engine-selector integration"); not wired.

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
| **Per-frame CTC emission encoder** (`honorable_sturgeon`-style) | **BLOCKED — retrain/re-export** | `CtcEmissionModel` (no prod impl) |
| Per-layout refinement head (`magic_macaw`, the +5.88 pt lever) | **BLOCKED — retrain (paired)** | future `CtcEmissionModel` decorator |
| Context-LM rerank (`hungry_jellyfish`, `alpha·lm`) | **BLOCKED — retrain (add-on)** | future reranker over `CtcCandidate` |
| ONNX-vs-ExecuTorch runtime decision (A3 spike) | **OPEN decision** | plan A3 |

Everything above the divider is decode-side and validated **now** against the offline port;
everything below needs a model that does not exist in this repo. This is why
`CtcEmissionModel` intentionally has **no production implementation** — supplying emissions
is the fork.

---

## Engine-selector integration (`swipe_engine_mode` — DESIGN, not wired)

Today `swipe_engine_mode` ∈ `{neural, hybrid, geometric}` (`Config.Defaults.SWIPE_ENGINE_MODE`,
read at `Config.kt:908`; parsed by `SwipeEngineRouter.Mode.fromPref`, routed by
`SwipeEngineRouter.route`). A future `ctc` mode slots in with these EXACT changes (do NOT
apply until Phase B lands a model):

1. **Pref value** — add `"ctc"` as a recognized `swipe_engine_mode` string. Keep it OFF the
   user-visible Settings → "Prediction Engine" selector until a model ships (so it is
   inert/hidden). `Config.Defaults.SWIPE_ENGINE_MODE` stays `"neural"`.
2. **Router** — extend `SwipeEngineRouter.Mode` with `CTC` and `fromPref("ctc") -> CTC`;
   add `Engine.CTC`. Routing table (mirrors the geometric layout gate):
   - `CTC` mode + QWERTY-Latin (or the layouts the CTC model was trained for) → `Engine.CTC`.
   - Because the CTC encoder is layout-parameterized (study D2), a mature model can serve
     **all** layouts — at which point `CTC` may also back a `hybrid`-style split
     (neural short words, ctc long words) per the O7 router verdict (plan B4).
3. **Engine construction** — where the IME builds the geometric engine, build a
   `CtcSwipeDecoder(model, layout, trie, CtcScoringParams.encoderDecoder(beamWidth=300,
   topK=4))`. `model` is the CTC `CtcEmissionModel` (the missing piece); `layout` is the
   on-screen `CtcLayout` (from the active `KeyboardData`); `trie` is a `CtcLexiconTrie`
   built from `en_enhanced.json` (+ user/custom words on the 1..255 log scale).
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

## Implementation Plan

### Phase A — plumbing (prototypable without any retrain) — **DONE in this module**
- **A1** Kotlin CTC Viterbi trie-beam (`CtcBeamDecoder`) — golden parity vs the Python port. ✅
- **A2** Kotlin featurizer (`CtcFeaturizer`) — encoder-tensor parity vs the port. ✅
- **A4** Lexicon trie surface (`CtcLexiconTrie`) — 1..255 log-freq, ITrie accessors, loaders. ✅
- **A5** Engine-selector interface design — this spec's "Engine-selector integration". ✅
- **A3** ONNX-vs-ExecuTorch runtime spike — **OPEN** (plan A3; no code here).
- **A6** Runtime hygiene backlog (mmap, pre-alloc, big-core pinning) — **OPEN** (plan A6).

### Phase B — the hard fork (retrain/re-export required) — **BLOCKED**
- **B1** CTC-emission encoder (per-frame key emissions + blank, layout-geometry input).
- **B2** Per-layout refinement head (`magic_macaw`-style; the +5.88 pt lever).
- **B3** Context-LM rerank (`hungry_jellyfish`-style; `alpha·lm` over top-200, keep CTC top-1).
- **B4** Router/complement integration (gated on the O7 fusion verdict).
Each B item is validated through the SAME offline harness (Track i) before any in-app ship,
and gated on the plan's Key open decisions (licensing, runtime, product posture, investment).

### Exact remaining work to make `ctc` live
1. Land a CTC-emission model + export path (B1) and implement a `CtcEmissionModel` (A3
   runtime decision). 2. Optionally add the refinement head (B2) and context-LM rerank (B3).
3. Build the runtime `CtcLexiconTrie` from `en_enhanced.json` + user/custom words (keep the
   1..255 log scale; incremental add for learned words). 4. Apply the "Engine-selector
   integration" changes (pref value, router mode, engine construction, two-phase params).
5. Only then expose `ctc` on the Settings selector. Until step 1, the module remains dead
   code reachable only by tests.

---

## Testing Strategy

### Golden-trace parity (`CtcParityTest`) — the core validation
`src/test/resources/ctc/ctc_golden.json` is frozen from the SAME Python port this module
ports (`scripts/futo_decoder_{eval,ceiling}.py`) via `scratchpad/gen_ctc_golden.py`. Cases:
- **Featurizer** (6 cases, exercising every resampler branch incl. single-point,
  zero-duration, non-uniform timestamps, long two-point): asserts the `[2,64]` tensor is
  **bit-identical** float32 to the port.
- **Beam** (6 cases: clear `cat`/`the`, an ambiguous `car/cat/cart/care` ranking under
  encoder+decoder params, two pruning-stress fields under narrow/wide beams, a random
  encoder-params field): asserts **identical greedy-CTC string, identical top-k words**
  (the ranking parity), and top-k final **scores within `1e-4`** (`Math.pow`/`ln` differ
  from the port's C-libm by ≤ ~1 ULP, so word order is the exact assertion, scores are a
  tolerance sanity).

### Unit tests (`CtcModuleTest`) — structural/behavioral coverage
Emission `sliceFromHead` (blank relocation), trie insert/contains/depth/charIdx +
skip-vs-strip loaders, `scoring.json` preset constants, featurizer shape/range + degenerate
branches + 4/3 aspect + padded-layout tensors, beam top-k/lexicon-constraint, and the
`CtcEmissionModel` facade seam (fake model proves the wiring — only the model is missing).

### Verification
`sh gradlew runPureTests -PtestClass=swipe.ctc.CtcParityTest` → OK (2 tests);
`-PtestClass=swipe.ctc.CtcModuleTest` → OK (12 tests). Both registered in `runPureTests`
(drift-checked by `TestRunnerListDriftTest`).

---

## Open Questions / Key Decisions (user-owned, from the plan)
1. **Licensing** — may FUTO's published weights (or fine-tunes) ship in GPL-3.0 CleverKeys
   on F-Droid? If not, B1 retraining from permissible data is mandatory before any in-app
   ship (offline eval use remains fine).
2. **Runtime** — second inference runtime (ExecuTorch JNI) vs ONNX re-export vs
   retrain-to-ONNX (A3). Recommendation: prefer ONNX to avoid a second runtime.
3. **Product posture** — replacement engine vs complement-behind-router; decide after O7's
   full-split fusion numbers.
4. **Beam language** — Kotlin (this module, testable) vs C++/JNI (FUTO parity, faster
   beam-300). Recommendation: Kotlin first; profile commit-phase beam 300 on-device, drop
   to JNI only if it misses the latency budget.

## Non-Goals
- No IME wiring, no Settings entry, no model bundling under this spec.
- No two-finger / multi-stream beam (FUTO's `recognize_multi`) — the port and this module
  are single-stream; multi-stream is a later extension of `CtcBeamDecoder`.
- No context-LM in the decode module (it is a modular reranker layered on `CtcCandidate`).
