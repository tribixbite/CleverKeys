# FUTO Engine Integration — Architecture Decision Audit

**Date:** 2026-08-06
**Status:** DECISION RECOMMENDATION (design/analysis only; no code changed)
**Question:** Should CleverKeys add a FUTO-style CTC engine as a user-selectable swipe
engine, and if so HOW — which runtime, whose model weights, and in what product posture
(replace / route / fuse)?
**Inputs:** `docs/audit/2026-08-06-futo-decoder-integration-study.md` ("study"),
`docs/audit/2026-08-06-futo-upgrade-plan.md` ("plan"),
`docs/specs/ctc-swipe-engine.md` ("ctc spec"),
`docs/eval/2026-07-24-test2400-head2head.md` ("head2head"),
`docs/audit/remediation-plans/hybrid-engine-rank-fusion.md` ("fusion proposal"),
`swipe/SwipeEngineRouter.kt`, `onnx/SwipePredictorOrchestrator.kt`, `Config.kt`,
plus **primary-source license verification performed for this audit** (HF
`futo-org/futo-swipe` `LICENSE.md` full text + HF API license metadata, fetched
2026-08-06 — see §2).

---

## 0. Executive summary

**Yes — add a CTC engine, but powered by a model we train ourselves and export to ONNX,
not by FUTO's shipped `.pte` weights.** The committed `swipe/ctc/` module already
implements and parity-tests everything except the emission model (ctc spec: FR-1…FR-4
DONE, FR-5 BLOCKED), so "the engine" is 80% built; the decision is really about **where
the emissions come from**.

- **Runtime (§1):** `.pte`→ONNX conversion is a **dead end** — FUTO's HF repo contains
  only ExecuTorch `.pte` flatbuffers (verified file listing: `model_fp32.pte` ×2 +
  `context_lm.pte`; **no PyTorch checkpoints/safetensors**), and no ExecuTorch→ONNX
  lifter exists; the XNNPACK-delegated subgraphs are opaque payloads. That collapses the
  three options to: (b) ship ExecuTorch alongside onnxruntime, or (c) retrain-to-ONNX.
  **Recommend (c)** — it reuses the existing `onnxruntime-android:1.20.0` path
  (`build.gradle:38`) and the committed Kotlin decoder, keeps one inference runtime, and
  avoids the per-ABI gap (ExecuTorch's prebuilt AAR does not cover `armeabi-v7a`, which
  CleverKeys ships).
- **License (§2):** the assumed hard blocker is **softer than feared but still
  disqualifying for the main line**. Verified against the actual license text: FUTO
  Model Weights License 1.0 permits commercial use, modification, and redistribution —
  but demands a mandatory end-user **"powered by FUTO Swipe" visible notice** (material
  breach otherwise), is non-sublicensable, and is a custom non-SPDX/OSI/FSF license
  (`license: other` on HF) → **F-Droid inclusion risk** for a core-feature asset.
  Meanwhile the **training data is MIT** (`futo-org/swipe.futo.org`, verified via HF
  API; negatives corpus Apache-2.0) — so weights **we** train on that data are wholly
  ours, GPL-3.0-clean, F-Droid-clean. (Corrects two errors in prior docs: the fusion
  proposal's "MIT-licensed FUTO model" claim is wrong — the *dataset* is MIT, the
  *weights* are not; and the task-level assumption of CC-BY-SA data is wrong — it's MIT.)
- **Posture (§3):** at FUTO-*ceiling* quality the head-to-head is **near-domination, not
  complementarity**: ceiling wins or ties every stratum (short 89.57 vs our 89.45 —
  tie; long 82.40 vs 67.00). So a per-swipe length router adds ≈0 over a good CTC
  engine. What survives is **fusion headroom** (+6.6 pt oracle union even over the
  ceiling; +14.0 pt long-word union between our own two engines). **Recommend:
  replacement-grade CTC engine first, then a confidence-gated cascade (CTC first, fall
  back to neural on low confidence) — not always-on dual decode.**
- **Path (§4):** phased with hard gates: G1 finish Track-(i) decision numbers → G2
  training-feasibility spike on the local train-110k → G3 ONNX export + on-device
  latency → G4 refinement head (the measured +5.88 pt lever) → G5 wire `ctc` into
  `swipe_engine_mode` per the ctc spec's existing 5-step integration list.
- **Near-term independent win:** the neural+geo rank-fusion proposal (~2.5 days, no new
  model, no license issues) can chase part of the +14 pt long-word union while training
  work proceeds — but only if its *realized* (non-oracle) gain clears ~2 pt offline.

---

## 1. RUNTIME — ONNX re-export vs ExecuTorch-JNI vs retrain-to-ONNX

### 1a. Convert FUTO's `.pte` to ONNX — **infeasible, drop it**

Verified (HF API, 2026-08-06) the `futo-org/futo-swipe` repo contains **only**:

| File | Size |
|---|---|
| `honorable_sturgeon/model_fp32.pte` | 2.65 MB |
| `magic_macaw/model_fp32.pte` | 1.25 MB |
| `hungry_jellyfish/context_lm.pte` | 6.25 MB |
| `hungry_jellyfish/vocab.txt` | 285 KB |
| `scoring.json`, `metadata.json` ×3 | ~5 KB |

No `.pt`/`.safetensors`/`.ckpt` source checkpoints. A `.pte` is a serialized
ExecuTorch edge-dialect flatbuffer whose XNNPACK-delegated subgraphs are **opaque
delegate payloads** (study §1: all three models are "XNNPACK-delegated"); there is no
supported ExecuTorch→ONNX exporter, and lifting the graph by hand would mean reverse-
engineering the delegate blobs. The op manifests confirm the graphs *would* export
cleanly to ONNX **from PyTorch source** (`atan2, cumsum, arange, split_with_sizes,
_log_softmax, where, bitwise_not` for the encoder; `native_layer_norm + _log_softmax`
for magic_macaw — study §1a/§1b, all standard ONNX ops) — but we do not have the
source checkpoints. Conversion is only revivable if FUTO publishes checkpoints or
training code that reproduces them (open question OQ-3, §5).

This resolves plan A3's "does honorable_sturgeon export cleanly?" spike **negatively
for the conversion branch without further FUTO cooperation** — the plan's option (b)
is not available as stated.

### 1b. Ship ExecuTorch runtime + FUTO `.pte` in-app — feasible but costly

Less scary than the plan's "heavy new .so, new build surface" framing in one respect:
ExecuTorch publishes an official Android AAR (`org.pytorch:executorch-android`) with a
Java `Module` API, so **no NDK/CMake build is required** to run a `.pte` from Kotlin —
we would implement `CtcEmissionModel` (the ctc spec's seam) over the AAR exactly as we
would over onnxruntime, and feed the committed `CtcBeamDecoder`. FUTO's own C++
engine/beam (GPL-3.0, verified `~/.cache/cleverkeys-test/swipe-library-src/LICENSE`)
would NOT be needed — our Kotlin port replaces it.

Real costs remain:
- **A second inference runtime resident in every APK**: ExecuTorch runtime + XNNPACK
  ≈ 2–5 MB per ABI on top of onnxruntime's ~13 MB — permanently, since the existing
  neural/geo engines stay on onnxruntime.
- **ABI gap**: prebuilt ExecuTorch AARs target `arm64-v8a`/`x86_64`; CleverKeys ships
  `armeabi-v7a` APKs (`build/outputs/apk/release/CleverKeys-v1.5.0-armeabi-v7a.apk`).
  v7a would need a custom NDK build (the complexity we hoped to avoid) or a
  degraded no-CTC v7a variant with per-ABI feature divergence.
- **API churn**: ExecuTorch went 0.x→1.x recently (our eval pinned 1.2.0 in proot,
  eval notes "Phase 1"); a pre-consolidation dependency treadmill vs onnxruntime's
  stable API we already track.
- **Couples us to §2's license terms** — this option is the only one that ships FUTO's
  weights, so it inherits the attribution mandate and the F-Droid risk.
- `magic_macaw` (the +5.88 pt lever) is **en_qwerty-fingerprint-gated** (study §4d:
  0.1-deviation geometry match against a hard-coded canonical layout) — our layouts
  must match FUTO's canonical frame or we forfeit the ceiling and get the 79% floor.

### 1c. Train our own CTC model, export ONNX — **recommended**

Reuses everything we already own: `onnxruntime-android:1.20.0`
(`SwipePredictorOrchestrator.kt` loads `models/*.onnx` from assets, XNNPACK threads
already configurable via `onnx_xnnpack_threads`), the committed + parity-tested
`swipe/ctc/` decode module, and the eval harness that gives us exact accept/reject
numbers. One runtime, all ABIs onnxruntime already serves, our weights, our license,
our multi-language roadmap (train with layout-geometry inputs per study D2 and one
encoder serves every language pack — something FUTO's en-fingerprinted refinement
head does not give us anyway).

The model is small — the fusion proposal cites 635K params / 2.5 MB fp16; the fp32
`.pte` artifacts total 2.65 MB (encoder) + 1.25 MB (refinement). This is single-GPU,
hours-to-days training territory, not a research program. The genuine risk is the
**training recipe**, not compute: CTC loss over per-frame key emissions + blank,
layout-sampling augmentation, DCT spatial head, and paired refinement-head training
must be reproduced from the paper (arXiv 2606.25247) and, if public, FUTO's training
code. §4's G2 gate is designed to burn down exactly this risk before commitment.

**Verdict: (c) retrain-to-ONNX.** The plan's ONNX lean is *validated*, but the
reasoning sharpens: it is not merely preferable — option (a) is infeasible and option
(b) carries an ABI gap, a second runtime forever, and the weights-license coupling.
(b) survives only as an optional out-of-band experiment (§5, option B).

---

## 2. MODEL SOURCE + LICENSING — verified, with a go/no-go tree

### 2a. What the FUTO Model Weights License 1.0 actually says (primary source)

Full text fetched from `huggingface.co/futo-org/futo-swipe/raw/main/LICENSE.md`
(2026-08-06). Key verified terms:

- **Grant:** "non-exclusive, royalty-free, worldwide, **non-sublicensable,
  non-transferable** license to use, copy, distribute, make available, and prepare
  Derivative Models of the Weights **for any purpose**".
- **Commercial use allowed** — "including in commercial products and services,
  **provided that you display a visible notice to end users stating that the product
  is powered by 'FUTO Swipe' technology**… within the product's settings, about
  screen, or equivalent disclosure area. **Failure to include this notice is a
  material breach**."
- **Derivative Models** are defined broadly: fine-tunes, pruning, **and "any model
  trained in whole or in part on Model Outputs"** — i.e. distillation from FUTO's
  models also produces a FUTO-licensed derivative. Distributing derivatives requires
  passing the terms through + prominent derivation/modification notices.
- **Excluded materials:** "Inference code, source code, and **datasets** distributed
  alongside the Weights are governed by their own separate licenses."
- Patent-retaliation termination; 30-day cure; Texas governing law.

HF metadata confirms: `license: other`, `license_name: futo-model-weights-license-1.0`
— a custom license, not SPDX-listed, not OSI/FSF-approved.

### 2b. Assessment against GPL-3.0 + F-Droid

- **GPL-3.0 compatibility:** the weights are runtime-loaded data, not linked code —
  shipping them in the APK is defensible as GPLv3 §5 "mere aggregation", so strict
  license *incompatibility* does not automatically block distribution. But the
  mandatory end-user advertising clause is the same shape as the 4-clause-BSD
  advertising clause the FSF classifies as free-but-GPL-incompatible; anyone treating
  the model as part of the Program would have a conflict. Legally shippable under the
  aggregation reading; not clean.
- **F-Droid:** the app's core swipe feature would depend on an asset under a custom
  non-OSI license. Best case, reviewers accept it with the `NonFreeAssets`
  anti-feature flag; realistic case, a core-functionality ML blob under `license:
  other` draws an inclusion challenge (F-Droid has an active hard line on bundled ML
  models — mitigated here by the training data being genuinely open, but the weights
  license itself is the artifact under review). CleverKeys' own ONNX models set the
  precedent that *freely-licensed* bundled models are fine; a custom-licensed one is a
  different review. **Risk: medium-high, outcome not in our control.**
- **Product/UX cost:** a permanent "powered by FUTO Swipe" notice in a keyboard that
  is otherwise a from-scratch rewrite, plus pass-through license text, plus (if we
  ever fine-tune) derivative-model notices on anything we publish.
- **OFFLINE/eval use (what we do today):** unambiguously fine — "use, copy" for any
  purpose is granted, no distribution is occurring, and research citation ("FUTO Swipe
  Model") applies only if we publish results.

### 2c. The retrain path is license-clean — and better than the task assumed

Verified via HF API: **`futo-org/swipe.futo.org` (the swipe corpus our local
train-110,876/val-9,918/test-2,400 splits derive from) is MIT**, and
`futo-org/swipe-negatives` is Apache-2.0. Two prior-doc corrections follow:

1. The fusion proposal's "MIT-licensed FUTO model" (`hybrid-engine-rank-fusion.md:40`)
   conflates dataset and weights — the *model* is NOT MIT. Its strategic-alternative
   section should not be read as "we can just ship their model".
2. The working assumption that the training data is CC-BY-SA is wrong — it is MIT, so
   there is no share-alike/attribution-stacking question at all. Weights trained by us
   on MIT data are entirely ours to license (we would license them with the app,
   GPL-3.0 or a permissive dual), with a one-line MIT attribution for the corpus in
   `NOTICE`. The license section of the decision **cannot block the retrain path**.

The decode *algorithms* were ported from FUTO's GPL-3.0 `swipe-library` (verified) —
same license as CleverKeys, no issue for the committed `swipe/ctc/` module.

### 2d. Go/no-go tree

```
Ship FUTO's .pte weights in-app?
├─ Legally possible? YES (aggregation reading + mandatory attribution honored)
├─ Forces ExecuTorch runtime? YES (§1a: no ONNX conversion exists) → §1b costs
├─ F-Droid main-line safe? NOT ASSURED (custom license, core feature) → user's call
└─ VERDICT: NO for the F-Droid main line.
   ALLOWED (user opt-in) as an out-of-band experimental build (GitHub release
   variant) with the attribution notice — useful as a live benchmark, never default.

Retrain our own CTC model on the local FUTO train-110k?
├─ Data license? MIT (verified) → GO, unconditional
├─ Must avoid: initializing from / distilling FUTO's weights or Model Outputs
│  (either makes ours a "Derivative Model" and re-imports the FUTO license).
│  Train from scratch on the data only. Ceiling-eval outputs stay offline.
├─ Feasibility? small model (≲1M params), 1M-swipe MIT corpus + local splits,
│  exact target metrics from our own harness → GO, gated on G2 (§4)
├─ Who trains? Not on-device (Termux). Needs a CUDA box or rented GPU
│  (single consumer GPU, hours–days per run) → open question OQ-2
└─ VERDICT: GO — this is the model source.
```

---

## 3. REPLACE vs ROUTER-COMPLEMENT vs FUSION

The measured stratum table (head2head, same 2,400 held-out rows; val-9,918 corroborates):

| Engine | overall t1 | ≤3-char (n=815) | 4+-char (n=1585) |
|---|---|---|---|
| FUTO ceiling (enc+dec) | **84.83** | **89.57** | **82.40** |
| FUTO floor (enc-only) | 79.25 | 82.45 | 77.60 |
| our neural (beam 6) | 74.62 | 89.45 | 67.00 |
| our geo (SHARK2) | 67.50 | 69.33 | 66.56 |

Union (oracle) headroom: neural+geo union@1 = 84.75 overall / **+14.01 pt on 4+-char**
over our best single; all-engines union over the FUTO ceiling still adds **+6.62 pt**
overall (+7.26 long, +5.40 short).

### (a) Full replacement

The crucial reframe the older "our-neural-wins-short" narrative misses: it wins short
words only against FUTO's **floor**. Against the **ceiling** (with the refinement
head) the short-word race is a statistical tie (89.45 vs 89.57) and everything else is
a rout. A ceiling-quality CTC engine is therefore **~single-engine optimal**: a
per-swipe oracle choosing between it and our neural would recover only the residual
union (+5.4 short / +7.3 long — and an oracle is unattainable). Replacement maximizes
accuracy-per-complexity **if and only if our retrained model reaches
ceiling-equivalent quality** (i.e. G4's refinement head lands). At floor-equivalent
quality (~79%), replacement sacrifices 7 pt of short-word accuracy vs our neural —
unacceptable; that regime demands (b) or (c).

### (b) Length/confidence router (pick ONE engine per swipe)

Cheap (a length estimate from path geometry, or CTC top-1 confidence, gates the
choice) and it matches the current architecture's "a single engine owns each swipe
end-to-end" invariant (`SwipeEngineRouter` KDoc). But its value is regime-dependent:
vs a ceiling-quality CTC engine a length router gains ≈0 (short-word tie); vs a
floor-quality one it protects the 89.45% short-word accuracy (route ≤3-char → neural,
4+ → CTC: back-of-envelope ≈ 0.34·89.45 + 0.66·77.60 ≈ **81.6%** overall, +7 pt over
our neural alone, using the test-split stratum mix). **The router is the hedge for a
mediocre in-house model, not the end-state.**

### (c) Rank fusion (run BOTH, merge slates)

Highest ceiling (+6.6 pt oracle over even the ceiling engine) but: (i) oracle ≠
realized — head2head's own caveat; whether confidence signals separate the engines'
errors is untested; (ii) cost — our neural is autoregressive at 100–300 ms/swipe
(fusion proposal architecture diagram), so always-both roughly doubles worst-case
latency and battery per swipe; the CTC side is cheap (one NN call + CPU beam), the
transformer is not; (iii) complexity — cross-engine scores are numerically
incomparable (router KDoc; geo softmax×1000 vs neural confidences vs CTC
`ctc/L^γ+βL+λlogf`), so fusion must be rank-based (RRF per the fusion proposal) with
per-stratum tuned weights that are EN-QWERTY-only.

### Recommendation: staged — replacement-grade engine, then a confidence-gated cascade

1. **Build the CTC engine to replacement grade** (G2–G4). Quality bar: ≥ FUTO floor
   on the full split at G2 (go/no-go for the program), ≥ ~ceiling−1 pt after G4
   (go/no-go for making it a default candidate).
2. **Cascade, not always-both fusion:** decode with CTC first (fast); if its top-1
   confidence clears a tuned threshold, done (majority of swipes — one cheap decode).
   Below threshold, invoke the neural engine and rank-fuse the two slates (RRF). This
   captures a useful fraction of the +6.6 pt union at a bounded latency tax on only
   the hard swipes, and degrades gracefully to (b)'s router if fusion tuning
   disappoints. Gate: realized offline gain ≥ ~2 pt over CTC-alone on the stratum
   where it fires (the head2head verdict rule), else ship plain replacement.
3. **Independent near-term track:** the existing neural+geo RRF proposal
   (~2.5 days) needs no new model or license and attacks today's worst number
   (67.00% long-word). Run its offline validation now; ship only on the same
   realized-≥2 pt rule. It is superseded, not blocked, by the CTC engine later.

### 3.5 Interaction with `hybrid` mode; is `ctc` its own mode?

Today's modes (`Config.Defaults.SWIPE_ENGINE_MODE = "neural"`, `Config.kt:313/620/908`;
`SwipeEngineRouter.Mode.fromPref`) are **layout-routing** policies, not per-swipe
policies: `neural` = transformer on QWERTY else nothing; `hybrid` = transformer on
QWERTY else geometric; `geometric` = SHARK2 everywhere. "Hybrid" therefore does NOT
mean per-swipe blending — the KDoc explicitly defers rank-merge to phase-2.

- **Short term: `ctc` is its own opaque mode value**, exactly as the ctc spec's
  integration section already designs (add `"ctc"` to `fromPref`, `Engine.CTC`,
  hidden from the Settings selector until a model ships). This preserves the
  one-engine-per-swipe invariant, costs one enum branch, and is trivially reversible.
- **Medium term, if the layout-parameterized retrain lands:** the CTC engine serves
  *every* layout (study D2), which erodes `hybrid`'s reason to exist (geometric's
  role was "non-QWERTY coverage"). `hybrid`'s definition then naturally upgrades to
  "neural short-word strengths + CTC everywhere else", and the §3 cascade lives
  **inside** the CTC engine path (CTC → conditional neural fallback) rather than as a
  new mode. Do **not** build a separate "smarter router" mode enum now — mode
  proliferation (neural/hybrid/geometric/ctc/fused/…) is a settings-UX and test-matrix
  tax; fold intelligence into the engine path behind the existing pref, and keep
  `swipe_engine_mode` as the coarse user-facing policy. Backup/restore note: a new
  pref *value* (not key) is compatible with `SETTINGS_DEFAULTS` — unknown values
  already fall back to NEURAL in `fromPref` ("never crash the router on a corrupted
  pref").

---

## 4. INTEGRATION PATH

What exists vs what's missing (ctc spec's boundary table): decode
(`CtcBeamDecoder`), featurizer (`CtcFeaturizer`), lexicon trie (`CtcLexiconTrie`),
scoring presets, and the facade seam (`CtcSwipeDecoder`/`CtcEmissionModel`) are DONE
and golden-parity-tested against the Python port; **the only missing artifact is the
emission model** — and now, per §1/§2, its source is decided: our own, ONNX.

**Phase 0 — decision-grade numbers (Track (i) tail; no gates needed).**
Finish plan O4 (context-LM port), O5 (full 48,538-trace split), O7 (fusion/router
refresh). These convert the "our 2,400 is a harder subset" caveat (84.83 vs paper
93.30) into a solid target number for G2/G4 and produce the realized-fusion numbers
§3's cascade gate needs. Also run the pending neural/geo val-9,918 legs (head2head
notes them pending) so the stratum story is corroborated on 4× data for our engines
too.

**GATE G1 — program go/no-go (user):** full-split numbers confirm the CTC
architecture's advantage is real (not a subset artifact) AND user accepts the
training-infra cost (OQ-2). If no → stop; fall back to the neural+geo fusion track
only.

**Phase 1 — training feasibility spike (the retrain gate).**
Locate/assess FUTO's training code (OQ-3); reproduce a from-scratch CTC emission
encoder (layout-geometry-parameterized, study D2) on the local train-110k; evaluate
through the SAME harness on val-9,918.
**GATE G2:** from-scratch top-1 within ~2 pt of FUTO floor (≈77–79%) on val. Miss by
a lot → the recipe, not the idea, is the problem; iterate or stop. (No FUTO weights
or outputs anywhere in the training loop — §2d derivative trap.)

**Phase 2 — export + on-device runtime.**
Export the trained model to ONNX; implement `CtcEmissionModel` over onnxruntime
(mirroring `SwipePredictorOrchestrator`'s session/XNNPACK setup); wire the offline
harness to the ONNX artifact to prove export parity (encoder-argmax probe, plan O2
pattern).
**GATE G3:** on-device commit-phase decode (encoder + beam 300 over
`en_enhanced`-derived trie) inside latency budget (target: ≤ our current neural
~100–300 ms; expected far under — one NN call + pure-CPU beam). If the Kotlin beam
misses budget, only then consider JNI (plan decision 4; Kotlin-first stands).

**Phase 3 — the accuracy lever.**
Train the paired refinement head (magic_macaw-analogue; +5.88 pt measured) and
optionally the context-LM reranker (modular, layers on `CtcCandidate`; O4's port is
the reference implementation).
**GATE G4:** enc+refine reproduces ≥ +4 pt over our enc-only on the full split (plan
B2 DoD). This gate decides §3's posture: pass → replacement-grade, cascade optional;
fail → the engine ships as long-word complement behind the §3b router instead.

**Phase 4 — wire `ctc` mode.**
Execute the ctc spec's existing 5-step list verbatim: pref value (hidden), router
`Mode.CTC`/`Engine.CTC`, engine construction (`CtcSwipeDecoder(model, layout, trie,
encoderDecoder(beamWidth=300, topK=4))`), output into the SAME
`SuggestionHandler.handleSwipePredictionResults` seam, two-phase preview(32/1)–
commit(300/4) decode. Ship dark → power-user visible → default-candidate only after
a beta cycle. Every step is additive and reversible (mode falls back to NEURAL).

**Phase 5 (conditional).** Cascade fusion inside the CTC path per §3, gated on
realized-≥2 pt offline evidence.

---

## 5. Decision matrix + recommendation

Options: **A** status quo (neural/hybrid/geometric only) · **B** ship FUTO `.pte` +
ExecuTorch AAR · **C** convert `.pte`→ONNX · **D** retrain own CTC → ONNX → committed
Kotlin decoder (with optional cascade) · **E** neural+geo rank fusion only (no new
model).

| Criterion | A: status quo | B: FUTO .pte + ExecuTorch | C: .pte→ONNX | D: retrain→ONNX | E: neural+geo fusion |
|---|---|---|---|---|---|
| Accuracy (overall t1, measured/projected) | 74.62 | **84.8** (ceiling, measured offline) | n/a | ~79 at G2 → **~84–85** after G4; +cascade toward 91 union | 74.6→ up to 84.75 oracle; realized share unknown |
| Long-word t1 (the weakness) | 67.00 | **82.40** | n/a | ~77 → **~82** | toward 81 union ceiling, partial |
| Effort | none | M (AAR + attribution + v7a problem) | — (infeasible) | **L** (training recipe is the risk; engine 80% done) | **S** (~2.5 days, offline tuning first) |
| APK size delta | 0 | +~10 MB models (LM incl.) + 2–5 MB runtime/ABI; v7a gap | — | +~3–4 MB models, **no new runtime** | 0 |
| License / F-Droid | clean | legal-but-encumbered: mandatory "powered by FUTO Swipe" notice, custom license, **F-Droid risk** | — | **clean** (MIT data → our weights) | clean |
| Maintenance | — | two runtimes forever, ExecuTorch churn, upstream weight cadence | — | one runtime; we own model + recipe (new competency to maintain) | one more tuned component (EN-QWERTY-only weights) |
| Reversibility | — | high (mode off, drop dep) — but license notice while shipped | — | high (hidden mode value, additive) | high (pref-gated) |

**Top-line recommendation: D, sequenced by §4's gates, with E as a cheap parallel
hedge and B available only as an explicitly opt-in, non-F-Droid experimental build if
the user wants a live benchmark before G2 completes. C is closed.** D is the only
option that simultaneously fixes the measured long-word gap, keeps one inference
runtime, keeps F-Droid/GPL clean, unlocks multi-language layouts via the
geometry-parameterized encoder, and consumes the already-committed `swipe/ctc/`
module as-is.

### Open questions the user must answer

1. **OQ-1 (posture confirm):** accept "replacement-grade CTC + confidence cascade"
   as the target posture (§3), i.e. our neural becomes the fallback, not the primary,
   once G4 passes?
2. **OQ-2 (training infra):** who/where trains — is a CUDA GPU (owned or rented,
   single-consumer-GPU scale, hours–days/run) available and budgeted? This is the
   only true external dependency of option D.
3. **OQ-3 (training code):** is FUTO's *training* code published (gitlab.futo.org
   keyboard group / paper artifacts), and under what license? Public training code
   collapses G2's recipe risk from L to M. (One targeted check; the decode library is
   GPL-3.0 so precedent is good.)
4. **OQ-4 (interim variant):** do we want option B as a GitHub-release-only
   experimental variant (attribution notice + license pass-through included) while
   D trains — or is dual-variant maintenance not worth a live benchmark we can
   already measure offline?
5. **OQ-5 (fusion budget):** for the cascade/E-track, what per-swipe latency tax is
   acceptable on low-confidence swipes (the neural fallback costs 100–300 ms)?
6. **OQ-6 (F-Droid posture, only if B is ever considered for main line):** are we
   willing to open an inclusion discussion with F-Droid about a custom-licensed model
   asset? (Recommended answer: moot — take D.)

---
*Analysis only; no code modified, nothing committed. License findings verified
against primary sources (HF LICENSE.md full text + HF API metadata) on 2026-08-06 —
they correct the "MIT model" claim in `hybrid-engine-rank-fusion.md:40` and the
CC-BY-SA data assumption; both should be annotated if those docs are next touched.*
