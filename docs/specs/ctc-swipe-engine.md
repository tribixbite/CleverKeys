# Feature Specification: CTC Swipe Engine (`ctc` mode — WIRED, opt-in)

**Status (2026-08-08):** WIRED behind the opt-in Prediction Engine dropdown (default stays
`neural`). The CleverKeys-trained CTC encoder ships as `models/ctc_swipe_encoder.onnx`
(CleverKeys-ML `phaseM_kd_fresh_w1_s1234_fp16w`, 2.91 MB — TEST-VALIDATED on the shipping
configuration: en_enhanced STRIP trie at preset 0.9/4.0/0.25/0.25/0.9882 → test-2400
seed-mean 89.31/93.79/94.50 t1/3/5, beating FUTO's ceiling and our neural on every
stratum; UNSEALING_4). Integration per `CleverKeys-ML/ctc/APP_INTEGRATION_PLAN.md`
(commits 3b9dd666..d99dd41f, seam-audit fixes fb77b422): `OnnxCtcEmissionModel` +
`CtcEngineAdapter` + `SwipeEngineRouter.Mode.CTC` (QWERTY→CTC, other layouts→geometric
hedge) + `CtcSettingsActivity` (beam-width knob, default 100). v1 is en-only — non-English
QWERTY swipes under `ctc` mode fall through to the NEURAL flow (audit M1), so selecting
CTC never yields less coverage than `hybrid`. Per-language presets (ru λ 2.0 on CKDT
scale), the two-model ensemble, the rescorer, and contract-v2 remain future options
recorded in the plan.
**Package:** `tribixbite.cleverkeys.swipe.ctc` (`src/main/kotlin/.../swipe/ctc/`), with the
Android-side adapter at `swipe/CtcEngineAdapter.kt` + `swipe/OnnxCtcEmissionModel.kt`.
**Origin:** Track (ii) of `docs/audit/2026-08-06-futo-upgrade-plan.md`; algorithm ground
truth is the integration study `docs/audit/2026-08-06-futo-decoder-integration-study.md`
(cited "study §N") + the Python port `scripts/futo_decoder_{eval,ceiling}.py` and FUTO C++
`~/.cache/cleverkeys-test/swipe-library-src` (`resampler.cpp`, `beam_search.cpp`).

> **Reading guide.** The "As-Built" section below is the current-behavior reference.
> Later sections marked **SUPERSEDED (design-era)** describe the pre-wiring plan and are
> kept for history/rationale — where they conflict with As-Built, As-Built wins.

---

## As-Built (2026-08-08, commits 3b9dd666..743b58fa)

### Routing: mode × layout × language → engine

`swipe_engine_mode` (Settings → Swipe Typing → "Prediction Engine") selects a
`SwipeEngineRouter.Mode`; the router itself is **layout-only** (`SwipeEngineRouter.route`,
QWERTY-Latin gate = `Config.isSwipeTypingSupportedForLayout`). Language is runtime state
the router deliberately doesn't see — the `ctc` mode's language dimension is handled one
level up, in `InputCoordinator.performCtcSwipeTyping` (audit M1).

| `swipe_engine_mode` | QWERTY-Latin + English | QWERTY-Latin + other language | Non-QWERTY layout |
|---|---|---|---|
| `neural` (default) | NEURAL | NEURAL | none (no swipe) |
| `hybrid` | NEURAL | NEURAL | GEOMETRIC |
| `geometric` | GEOMETRIC | GEOMETRIC | GEOMETRIC |
| `ctc` | **CTC** | NEURAL (M1 fallthrough) | **CTC** if Latin script + all a–z present (gate widened 2026-08-15); GEOMETRIC otherwise, and GEOMETRIC for non-English there |

Net `ctc` semantics: CTC(en QWERTY) / neural(non-en QWERTY) / geometric(non-QWERTY) —
**never less coverage than `hybrid`**. The active language is read BEFORE dispatch
(`DictionaryManager.getCurrentLanguage()`, falling back to `config.primary_language`); a
non-English swipe takes `dispatchNeuralSwipeTyping`, the SAME flow `Engine.NEURAL` takes.
The adapter keeps its own en-gate (`CtcEngineAdapter.LANGUAGE == "en"` checked in both
`decodeAsync` and `warmUpAsync`) as defense-in-depth. Unknown/legacy pref values parse to
`NEURAL` (`Mode.fromPref`); the pref is case-canonicalized at read (`Config.kt` refresh,
audit L1), so an imported `"CTC"` behaves exactly like `"ctc"` in the router, provenance
tagging, and the settings UI.

### The seam fixes (fb77b422 — audit H1/M1/M2)

- **H1 — contraction display.** The bundled `en_enhanced.json` has ZERO apostrophe words:
  contractions exist only as a–z aliases (`dont`, `im`, `theyd`), so a raw decode would
  present/commit "dont". `CtcEngineAdapter.applyContractionDisplay` overlays decoded alias
  surfaces with their apostrophe forms via the shared pure `swipe/ContractionOverlay`
  (paired-first keep+variant, real-word ordinal guard, junk-alias replace) using the en
  `ContractionManager` mappings + the merged-lexicon frequency ordinals
  (`CtcLexiconMerge.ordinals`) — exact parity with `GeometricEngineAdapter`'s duty. This
  happens IN the adapter, before the shared pipeline (the pipeline does not map aliases).
- **M1 — non-en neural fallback.** See the routing table above: `ctc` mode on a QWERTY
  layout with a non-English active language dispatches the neural flow instead of showing
  an empty bar.
- **M2 — engine-true provenance.** Suggestion origin markers/long-press sheets tag the
  ROUTED engine, not the configured mode: `SuggestionProvenance.forRoutedEngine(engine)`
  is threaded from `InputCoordinator` through `handleSwipePredictionResults`. A non-QWERTY
  swipe under `ctc` mode is tagged GEOMETRIC (it was decoded geometrically); an en-QWERTY
  swipe is tagged `SuggestionOrigin.CTC` ("CTC swipe (trie beam)", indigo marker
  `SuggestionBar.kt`). The old mode-keyed derivation (`forSwipeEngineMode`) remains only
  as the null-default for callers that don't thread an origin — it mislabeled hybrid's
  geometric swipes as NEURAL_BEAM and would have mislabeled ctc's.

### `CtcEngineAdapter` — the impurity boundary

`swipe/CtcEngineAdapter.kt` mirrors `GeometricEngineAdapter`'s duties for the `ctc` mode:

1. **Letter-box coordinate normalization.** `KeyboardData` → `CtcLayout` via
   `KeyboardGeometry.computeKeyRects`: the 26 a–z letter-key centers, normalized over the
   **letter-key bounding box** (the model's [0,1] frame — the shipped encoder was trained
   on paths normalized over the letter area with centers passed as `layout_keys`, NOT on
   FUTO's 4/3-aspect device frame; `CtcFeaturizer.normalizeRawY` is deliberately not used
   here). The raw `PointF` trace is normalized under the SAME letter-box affine. Layouts
   missing any a–z letter build no `CtcLayout` → empty result (unexpected behind the
   router's QWERTY gate).
2. **Lexicon.** Bundled `dictionaries/en_enhanced.json` only ({word: freq}, frequencies
   already on the AOSP-like 134..255 log scale the tuned λ=4.0 was fitted against —
   NFR-4), a–z-STRIPPED (`don't`→`dont`), merged with user custom words (freq clamped
   1..255; custom overrides disabled) minus disabled words (`CtcLexiconMerge.merge`,
   unit-tested). **Langpack swap is deliberately unsupported** (audit L2): an installed en
   langpack's CKDT `dictionary.bin` stores the INVERTED 255−rank scale λ was NOT fitted
   for — swapping the source requires its own λ validation round (plan §7.1). Known
   limitation: the CTC vocabulary can diverge from the en dictionary source the other
   engines see.
3. **Per-decode trie freshness.** The trie memo is keyed by a SHA-256 content-hash over
   (source id, custom-words JSON, disabled-words set), recomputed per `lexiconFor()` call
   — any user dictionary mutation rebuilds the trie on the next decode with no
   ContentObserver plumbing.
4. **ONNX session.** Loaded lazily on the decode thread via the existing `ModelLoader`
   (XNNPACK-first, `onnx_xnnpack_threads` pref coerced 1..8). **Bounded model-load retry**
   (audit L5): up to 3 failed attempts (cold-boot transients must not permanently disable
   ctc), then the failure latches off for the IME session (no per-swipe retry storm). On
   shutdown the ORT session is intentionally NOT closed (closing mid-`session.run` is UB
   in ORT; reclaimed at process death, same posture as the neural orchestrator).
5. **Decoder memo** keyed by (mapped layout, trie, beam width) — a beam-width change from
   settings swaps the memoized decoder on the next swipe, no engine rebuild or re-warm
   hook needed.
6. **Warm-up.** `warmUpAsync` front-loads session + trie + layout;
   `InputCoordinator.prewarmGeometricEngine()` (shared prewarm entry, called from
   `CleverKeysService.onStartInputView` — layout switches / rotation) routes to it when
   the router would pick CTC, so the first swipe decodes in warm-path time.

**Concurrency contract** (mirrors the geometric WP9-audit-M-2 shape, pinned by
`CoreImeHygieneDriftTest`): all engine-side state (session, layout/trie/decoder memos,
`ContractionManager`) is confined to the single background thread of a
`PredictionTaskRunner`. `decodeAsync` submits in the FOREGROUND slot (a new swipe cancels
the previous decode — last-swipe-wins — and any in-flight prewarm); `warmUpAsync` submits
in the BACKGROUND slot (supersedes an older prewarm, NEVER cancels a decode). Result
delivery is guarded by a monotonic decode generation (only the newest decode may post to
the main thread; re-checked on the main thread), and `performCtcSwipeTyping`'s callback
additionally applies the `isReplayInputStillCurrent` staleness guard so a late decode
cannot commit into a changed input field (audit M-2 parity).

**Output contract:** top-8 slate (`TOP_K = 8`; the bar renders ~5 and the pipeline
augments possessives), scores engine-relative softmax×1000 — never compared across
engines. Results feed the SAME single seam as neural/geometric:
`InputCoordinator.handlePredictionResults` → `SuggestionHandler.handleSwipePredictionResults`,
inheriting the password guard, possessive augmentation, shift/caps transform, and THE
commit engine. ML trace capture is tagged `SwipeMLData.ENGINE_CTC` + layout name so
exports stay separable per decoder (audit n-2 conventions).

### Shipped model + preset

- Asset `src/main/assets/models/ctc_swipe_encoder.onnx` — 2.91 MB fp16-weight CTC
  emission encoder, trained from scratch by the CleverKeys project (CleverKeys-ML `ctc/`,
  Phases E→M, `phaseM_kd_fresh_w1_s1234_fp16w`) on MIT-licensed corpora (FUTO
  swipe.futo.org + How-We-Swipe; no FUTO weights or model outputs — see repo `NOTICE`).
  Run via `OnnxCtcEmissionModel` (emission slice per `CtcEmissions.sliceFromHead`).
- Ship preset `CtcScoringParams.tunedV2`: γ=0.9, λ=4.0, β=0.25, α=0.0, γ_prune=0.25,
  β_prune=0.9882; beam width default 100 (`Defaults.CTC_BEAM_WIDTH`), adapter topK=8.
  Fitted offline on the app-trie footing; the published-preset control measured −2.3 pt
  top-1, which is why the scoring constants are not user-exposed.
- Validation (test-2400, seed-mean): **89.31 / 93.79 / 94.50** top-1/3/5
  (≤3-char 93.70, 4+ 87.05) — above FUTO's own decoder ceiling (84.83) and our neural
  (74.62) on every stratum; equal-footing McNemar 3/3 seeds p<5e-4. Evidence:
  `CleverKeys-ML/ctc/UNSEALING_4.md`; app-side cross-reference
  `docs/eval/2026-07-24-test2400-head2head.md` (addendum).

### Settings surface

| Control | Key | Default | Range | Where |
|---|---|---|---|---|
| Prediction Engine dropdown (Hybrid/Neural/Geometric/CTC) | `swipe_engine_mode` | `"neural"` | 4 values, case-canonicalized at read | `ui/settings/sections/NeuralPredictionSection.kt` |
| CTC Beam Width slider | `ctc_beam_width` | 100 | 10–300 (clamped at load AND per decode) | `CtcSettingsActivity.kt` |

- `CtcSettingsActivity` ("Full CTC Settings" button, shown only under `ctc` mode) exposes
  exactly ONE knob — commit-phase beam width — plus "Reset to Validated Default". The
  adapter re-reads `Config.globalConfig().ctc_beam_width` per decode, so changes apply on
  the next swipe.
- "Full Geometric Settings" stays visible under `ctc` mode too (the non-QWERTY hedge is
  geometric).
- Settings search: "CTC Settings" entry (keywords ctc/futo/swipe engine/beam/trie) is
  deliberately UNGATED by the current engine mode — gating made "ctc" unfindable exactly
  when the user is setting swipe up (`SettingsActivity.kt`).
- Backup & restore: `ctc_beam_width` is in `SETTINGS_DEFAULTS`
  (`backup/SettingsDefaults.kt`); `swipe_engine_mode` diffs case-insensitively at import
  (`SettingsImportPlanBuilder`, audit L1). Reset presets restore `ctc_beam_width` but
  deliberately leave `swipe_engine_mode` alone (engine choice, like the geo knobs'
  precedent, is not a "tuning" preset member — `SettingsResetPresets.kt`).

### Test inventory (as wired)

Pure JVM (`runPureTests`; registered + drift-checked by `TestRunnerListDriftTest`):

| Suite | Cases | What it pins |
|---|---|---|
| `swipe/ctc/CtcParityTest` | 2 | Golden parity vs the Python port: featurizer tensor bit-identical; beam top-k words identical, scores within 1e-4 |
| `swipe/ctc/CtcModuleTest` | 12 | Emissions slice, trie loaders, preset constants, featurizer branches, beam behavior, facade seam |
| `swipe/ctc/CtcLexiconMergeTest` | 10 | Merge policy: custom-first, 1..255 clamp, custom-overrides-disabled, case-folded dedupe, ordinals |
| `swipe/ctc/CtcContractionDisplayTest` | 7 | Alias→apostrophe display over the real merged-lexicon ordinals (H1) |
| `swipe/ContractionOverlayTest` | 12 | The shared pure overlay decision matrix (geometric + ctc twin duty) |
| `swipe/SwipeEngineRouterTest` | 15 | Routing table incl. `Mode.CTC` rows + `fromPref` canonicalization |
| `SuggestionProvenanceTest` | 12 | `forRoutedEngine` totality + origin labels (M2) |
| `ml/SwipeMLDataProvenanceTest` | 5 | `ENGINE_CTC`/layout tagging of ML captures (n-2) |
| `CoreImeHygieneDriftTest` | 10 (class total) | Source-scan pins incl. the CTC twins of the geometric pins: prewarm stays BACKGROUND slot, decode stays FOREGROUND, staleness guard present, M1 fallthrough present |
| `backup/SettingsImportPlanBuilderTest` | 34 | Incl. `swipe_engine_mode` case-insensitive diff cases |

Instrumented (ew-cli, Pixel7/API34 — all green on-device 2026-08-08):

| Suite | Cases | What it gates |
|---|---|---|
| `swipe/CtcEmissionModelParityTest` | 2 | The SHIPPED ONNX asset's on-device emissions/decodes match the golden fixture |
| `swipe/CtcLatencyGateTest` | 1 | Production-path decode budget: median < 150 ms / p90 < 250 ms (ModelLoader+XNNPACK, real `trieFor()` merge path, tunedV2 beam 100 topK 8, worst-case golden trace) |
| `swipe/ctc/CtcOnnxLatencyBenchmarkTest` | 2 | Loose-bound measurement harness (informational, not the gate) |

Remaining before any v1.6.0 tag: manual QA per plan §4.5 (first-swipe warmup, long-word
feel, non-QWERTY hedge, non-en neural fallback, don't/I'm display, provenance label,
thermals) — tag only on explicit user go. See `memory/todo.md` HANDOFF §B.

---

## Feature Overview

### Summary
A fourth swipe-decode engine in the pattern of `swipe/geometric/`: a **non-autoregressive
CTC trie-beam decoder** that consumes per-frame log-emissions from a CTC-emission encoder
and returns a scored candidate slate. The decode strategy (featurizer + trie + Viterbi CTC
beam) is pure JVM and fully implemented + tested here; the emissions come from the
CleverKeys-trained ONNX encoder above (the model was the last-landed piece — it was the
sole blocker during the design phase).

### Motivation
The measured levers (study §5a, plan "Framing"):
- FUTO's decisive **structural** advantage is CTC's one-NN-call decode: the beam is pure
  CPU, so FUTO affords beam 300 vs our autoregressive beam 6 (study §6 item 1). This is
  the source of its long-word advantage (4+ chars: 77.6% vs our 69.3%, study §5b).
- The single measured **accuracy** lever is the per-layout refinement head (`magic_macaw`):
  **+5.88 pt top-1** (study §5a). The beam algorithm itself was ≈neutral. (Outcome: the
  CleverKeys-trained encoder beat all bars WITHOUT a refinement head — it ships alone.)
- Head-to-head against FUTO's engines was **stratified, not dominated**, so the product
  posture is a *complement behind a router*, not an assumed replacement (plan Key open
  decision 3, O7). (Outcome: the trained encoder ended up leading every stratum, but the
  router posture shipped anyway — `ctc` is opt-in, default stays `neural`.)

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
  contract. — **DONE** (`CtcFeaturizer`). Note: the shipped model's runtime frame is the
  letter-box normalization done in `CtcEngineAdapter` (As-Built §1), not the 4/3 helper.
- **FR-3** Provide a lexicon trie over the active alphabet with per-word AOSP-scale
  (1..255) log-frequency and the `ITrie` accessors the beam needs, plus loaders that either
  skip or a-z-strip out-of-alphabet words. — **DONE** (`CtcLexiconTrie`).
- **FR-4** Expose a facade (`CtcSwipeDecoder`) that wires featurizer → emission model →
  beam in the one call shape a `ctc` engine mode would invoke. — **DONE**.
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
  weak. This is also why the runtime lexicon is pinned to the bundled `en_enhanced.json`
  (langpack CKDT stores an inverted scale — As-Built "Lexicon").

---

## Technical Design

### Architecture / Module skeleton
```
src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/
├── CtcScoringParams.kt   # scoring presets (tunedV2 SHIP preset + design-era presets)
├── CtcEmissions.kt       # [frames][K+1] log-emission value type + sliceFromHead()
├── CtcLayout.kt          # alphabet (emission-column order) + key centers
├── CtcLexiconTrie.kt     # trie + ITrie-style nodes + freq-map loaders
├── CtcLexiconMerge.kt    # bundled+custom−disabled merge policy + ordinals (H1 guard)
├── CtcFeaturizer.kt      # resampler.cpp port: 60Hz→fixed64, layout tensors, 4/3 aspect
├── CtcBeamDecoder.kt     # greedy CTC + single-stream Viterbi trie beam  (the core)
└── CtcSwipeDecoder.kt    # facade: featurizer → CtcEmissionModel → beam

src/main/kotlin/tribixbite/cleverkeys/swipe/
├── OnnxCtcEmissionModel.kt  # the production CtcEmissionModel (ONNX session)
├── CtcEngineAdapter.kt      # Android boundary (As-Built section above)
└── SwipeEngineRouter.kt     # Mode.CTC / Engine.CTC routing
```
Tests: see the As-Built test inventory. Golden fixture
`src/test/resources/ctc/ctc_golden.json` (regen: `scratchpad/gen_ctc_golden.py`, imports
the real port).

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
  apostrophes: `don't`→`dont` — the SHIPPING loader).
- `CtcFeaturizer.featurize(px,py,pt): FloatArray` (`[x0..x63,y0..y63]`),
  `buildPaddedLayout(layout)`, `normalizeRawX/Y` (4/3 aspect + affine).
- `CtcBeamDecoder.decode(emissions, trie, params): List<CtcCandidate>`, `greedy(...)`.
- `CtcSwipeDecoder(model, layout, trie, params).decode(px,py,pt)` — the end-to-end call.

---

## Design-era sections (kept for history)

> **SUPERSEDED (design-era).** Everything below this banner was written while the module
> was a dead-code prototype blocked on a model export. The model has since been trained
> and shipped, and the mode is wired (see As-Built). Statuses like "BLOCKED", "not
> wired", "no production implementation" in these sections are historical.

### The retrain/re-export boundary (as of 2026-08-06 — since resolved)

| Piece | Status then | Outcome |
|---|---|---|
| CTC Viterbi trie beam (3 transitions, MAX-merge, length-aware prune, final score) | DONE, tested | shipped (`CtcBeamDecoder`) |
| Featurizer (60 Hz linspace → fixed-64, [0,1], 4/3 aspect, key-centers tensor) | DONE, tested | shipped (`CtcFeaturizer`) |
| Lexicon trie (a-z, per-word 1..255 log-freq, ITrie accessors, loaders) | DONE, tested | shipped (`CtcLexiconTrie`) |
| `scoring.json` presets (encoder-only / encoder+decoder / fallback) | DONE, tested | superseded by the fitted `tunedV2` SHIP preset |
| Facade wiring featurizer → emissions → beam | DONE (seam) | shipped (`CtcSwipeDecoder`) |
| **Per-frame CTC emission encoder** | BLOCKED — retrain/re-export | **RESOLVED**: CleverKeys-trained encoder → `OnnxCtcEmissionModel` |
| Per-layout refinement head (`magic_macaw`, the +5.88 pt lever) | BLOCKED — retrain (paired) | **NOT NEEDED** — encoder alone beat all bars |
| Context-LM rerank (`hungry_jellyfish`, `alpha·lm`) | BLOCKED — retrain (add-on) | future option (plan) |
| ONNX-vs-ExecuTorch runtime decision (A3 spike) | OPEN decision | **DECIDED: ONNX** (existing runtime, no second engine) |

### Engine-selector integration (the design that was applied)

The design called for: (1) a `"ctc"` pref value, (2) `Mode.CTC`/`Engine.CTC` in the
router, (3) engine construction beside the geometric engine, (4) output into the single
`SuggestionHandler.handleSwipePredictionResults` seam, (5) two-phase decode (preview
beam 32 / commit beam 300). Items 1–4 shipped essentially as designed (see As-Built for
the deltas: opt-in dropdown instead of hidden pref; commit beam default 100, not 300;
`tunedV2` instead of `encoderDecoder` params; language fallthrough added). Item 5
(during-gesture preview decode) was NOT implemented — v1 decodes at gesture end only.

The design note that "a mature model can serve ALL layouts" (the encoder is
layout-parameterized, study D2) remains a future option; the shipped v1 gate is
QWERTY-Latin + English.

### Implementation plan (historical)

- **Phase A** (plumbing, no retrain): A1 beam ✅, A2 featurizer ✅, A4 trie ✅, A5 selector
  design ✅; A3 runtime spike → resolved as ONNX; A6 runtime-hygiene backlog (mmap,
  pre-alloc, big-core pinning) → superseded by the shipped adapter's memo/warm-up design +
  the instrumented latency gate.
- **Phase B** (the hard fork): B1 CTC-emission encoder → DONE (CleverKeys-ML, Phases E→M);
  B2 refinement head → not needed; B3 context-LM rerank → future option; B4
  router/complement integration → shipped as the opt-in `ctc` mode.

### Open questions (historical — all resolved)

1. **Licensing** — resolved by training from scratch on MIT-licensed corpora (FUTO corpus
   + How-We-Swipe); no FUTO weights/outputs used. See `NOTICE`.
2. **Runtime** — ONNX (no second inference runtime).
3. **Product posture** — complement behind the router, opt-in; default stays neural.
4. **Beam language** — Kotlin; the instrumented latency gate (median <150 ms at beam 100)
   confirms no JNI drop needed.

### Non-goals (v1, still true)

- No two-finger / multi-stream beam (FUTO's `recognize_multi`) — single-stream only.
- No context-LM in the decode module (a modular reranker over `CtcCandidate` remains a
  recorded future option).
- No during-gesture preview decode (commit-phase only).
- No langpack-backed lexicon (λ-scale constraint, As-Built "Lexicon").

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

### Unit + instrumented coverage
See the As-Built test inventory for the full wired-mode suite (module/merge/contraction/
router/provenance/hygiene pure tests; on-device model parity + latency gate).

### Verification
`sh gradlew runPureTests -PtestClass=swipe.ctc.CtcParityTest` → OK; full `runPureTests`
1907 green post-seam-fix (fb77b422). Instrumented: full ew-cli sweep green on-device
2026-08-08 (see `memory/todo.md` HANDOFF §B for the gate evidence).
