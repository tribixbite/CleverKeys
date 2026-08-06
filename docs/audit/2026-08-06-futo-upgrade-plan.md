# FUTO Upgrade Plan — Offline Harness Now, In-App Engine Later

**Date:** 2026-08-06
**Ground truth:** `docs/audit/2026-08-06-futo-decoder-integration-study.md` (cited as "study §N")
and `docs/eval/futo-decoder-eval-notes.md` ("notes").
**Scope:** PLAN ONLY. Track (i) = offline decoder/eval harness work we can do now.
Track (ii) = DESIGN for a future in-app "FUTO-style" CTC engine, selectable via the
existing `swipe_engine_mode` pref (`neural|hybrid|geometric` today) — **must NOT be
wired into the app under this plan**.

## Framing (what the measurements tell us)

- The single measured accuracy lever is the **`magic_macaw` per-layout refinement
  decoder: +5.88 pt top-1** (78.96 → 84.83 on our 2,400-row split; greedy-CTC
  43.96% → 69.12%). The optimized Viterbi beam itself was **≈neutral (−0.29 pt)**
  (study §5a). Beam-width tuning is explicitly low-value.
- The decisive **structural** advantage is CTC's one-NN-call decode: beam cost is
  pure CPU, so FUTO affords beam 300 vs our autoregressive beam 6 (study §6 item 1).
- Head-to-head is **stratified, not dominated**: FUTO leads long words (4+ chars:
  77.6% vs our 69.3%), our transformer + freq rerank leads short words (≤3: 88.3%
  vs 82.5%) (study §5b). This argues for the future engine being a *complement*
  (router/fusion candidate), not an assumed replacement.
- Our repro floor (79.25 enc-only / 84.83 enc+dec) sits well below FUTO's paper
  numbers (92.54 / 93.30) for three suspected reasons: harder 2,400-row subset of
  the 48,538-trace split, textbook-vs-production beam port, and no context LM
  (study §5a). Track (i) exists to remove each of those confounds before we make
  the Track (ii) investment decision.
- **Known in-flight correction:** the eval lexicon port dropped apostrophe words
  instead of a-z-normalizing them (don't→dont), understating FUTO on contractions.
  Fixed 2026-08-06; the corrected proot re-run is pending. All numbers above are
  pre-correction and must be refreshed first.

---

## Track (i) — OFFLINE harness (do now; no app changes)

Files in play: `scripts/futo_decoder_eval.py`, `scripts/futo_decoder_ceiling.py`
(+ `*_metrics.py`, `run_futo100k_fixed*.sh`), `tools/test_cli_predict.py` (our ONNX
beam), proot Ubuntu 24.04 + ExecuTorch 1.2.0 venv (notes "Phase 1").

### O0. Land the corrected apostrophe-lexicon re-run — **S, low risk, no deps**
- **What/why:** Finish the pending proot re-run with the a-z-normalized lexicon;
  refresh every table in the eval notes (configs A/B/D, strata, head-to-head).
  Every downstream number and the Track (ii) go/no-go depend on this baseline
  being right.
- **Expected value:** Corrects an understatement of FUTO on contraction-bearing
  rows; magnitude unknown until run.
- **DoD:** `docs/eval/futo-decoder-eval-notes.md` updated with corrected
  t1/t3/t5 + per-stratum tables and a changelog line noting the lexicon fix;
  study §5a/§5b figures annotated or superseded.

### O1. Build FUTO's reference runner as independent ground truth (study H2) — **M, medium risk**
- **What/why:** Compile `targets/infer_jsonl.cpp` + `targets/eval_accuracy.cpp`
  from the FUTO library against the HF `futo-org/futo-swipe` models and
  `models/layouts/en_qwerty.json`, inside the existing proot Ubuntu env. Diff its
  output against our Python port on the same JSONL. This is the only way to catch
  resampler/coordinate/scoring drift we cannot see by porting blind, and it yields
  a true enc / enc+dec / enc+dec+LM triple from FUTO's own code.
- **Risk:** C++ build deps inside proot; the known proot stdout-hijack quirk
  (notes "proot exec quirk" — write results to a file, read from Termux side).
- **Deps:** none (parallel with O0).
- **Expected value:** Validates or invalidates our 79.25/84.83 repro; every later
  conclusion inherits this confidence.
- **DoD:** Reference runner produces per-row predictions on our 2,400-row split;
  a diff report (per-row agreement rate, aggregate t1/t3 deltas vs our port)
  checked into `docs/eval/`.

### O2. Resampler + encoder-argmax parity probe (study H4) — **S, low risk, deps: O1 for full parity**
- **What/why:** Assert `resample_to_60hz` in `futo_decoder_ceiling.py` implements
  `linspace(0, dur, round(dur/16.667)+1)` + lower_bound lerp — NOT ceil+clamp
  (the documented 63%→100% encoder-argmax fix, study §2 / H4). Add an automated
  encoder-argmax comparison against O1's runner as the fastest drift detector.
- **DoD:** A parity script reporting % identical argmax sequences per row;
  target ~100%; discrepancy list if not.

### O3. Frequency-scale audit (study H5) — **S, low risk, no deps**
- **What/why:** Confirm the port feeds AOSP-scale `log_frequency ∈ [1,255]` into
  `lambda·log_freq`, not our normalized `(raw−128)/127`. FUTO's
  `lambda ≈ 0.006–0.022` is calibrated to 1..255; a normalized scale silently
  makes lambda ~2 orders of magnitude too weak.
- **DoD:** One-line assertion/comment in `futo_decoder_eval.py` at the freq-read
  site + a before/after t1 check if a mismatch is found.

### O4. Port the `hungry_jellyfish` context LM (study H1) — **M/L, medium risk, deps: O0 (baseline), O1 (validation) helpful**
- **What/why:** The only unported FUTO model. Modular reranker:
  `final += alpha·lm_score` (alpha≈0.64), over-generate top-200, rescore,
  preserve CTC top-1. Port `get_embeddings` extraction, the wyhash-bucket OOV
  path (K=2), and the `h·emb + bias` scorer.
- **Expected value:** Closes part of the remaining gap to FUTO's paper 93.30
  (enc+dec is our 84.83 ceiling without it); doubles as the reference
  implementation for the Track (ii) D5 add-on.
- **Risk:** wyhash/OOV subtleties; validate row-by-row against O1's `--lm-model`
  path before trusting aggregates.
- **DoD:** Config E (enc+dec+LM) row added to the eval table on the 2,400 split,
  with per-row agreement vs the reference runner ≥ ~99%.

### O5. Full 48,538-trace split evaluation (study H3) — **M (mostly compute), low risk, deps: O0–O4**
- **What/why:** Our 2,400 rows are a possibly-noisier subset (notes: N=49,970 →
  48,538 after Table-7 filtering). Run the full split so our numbers are directly
  comparable to the paper's 92.54/93.30 and the short/long-word strata are
  statistically solid.
- **Risk:** proot wall-clock (hours); use the existing `run_futo100k_fixed_loop.sh`
  chunked/resumable pattern.
- **Expected value:** Distinguishes "our port is worse" from "our subset is
  harder" — the key input to the Track (ii) investment decision.
- **DoD:** Full-split t1/t3/t5 for enc-only, enc+dec, enc+dec+LM, plus per-length
  strata, in the eval notes.

### O6. Slate-semantics parity (study H6) — **S, low risk, deps: O4**
- **What/why:** Case-insensitive dedup + top-k as in `beam_search.cpp:456-486`;
  with LM, the over-generate/rescore/keep-CTC-top-1 slate behavior. Makes our
  top-1/top-4 comparable to a real FUTO suggestion strip.
- **DoD:** Eval metrics computed over the deduped slate; note any t3/t5 movement.

### O7. Refresh the router/fusion analysis on corrected + full data — **S/M, low risk, deps: O0, ideally O5**
- **What/why:** The stratified split (FUTO +8.3 pt on 4+ chars, ours +5.8 pt on
  ≤3 chars, study §5b) means an oracle or simple length/confidence-gated router
  may beat either engine alone. A fusion pass already exists (commit `86cff0c4`);
  re-run it on corrected numbers and quantify (a) oracle-fusion ceiling,
  (b) a realistic router (word-length or CTC-confidence gate).
- **Expected value:** Directly decides the Track (ii) product posture:
  replacement engine vs complementary engine behind a router.
- **DoD:** Fusion table (oracle + ≥1 realistic router) in the eval notes with a
  one-paragraph recommendation.

**Track (i) order:** O0 → {O1, O3 in parallel} → O2 → O4 → O6 → O5 → O7.
O0/O2/O3 are correctness gates; O1 is the trust anchor; O4/O5 produce the
decision-grade numbers; O7 converts them into a product recommendation.

---

## Track (ii) — FUTURE in-app "FUTO-style" engine (DESIGN ONLY — do not wire in)

Target shape: a fourth `swipe_engine_mode` value (e.g. `ctc`) alongside
`neural|hybrid|geometric`, implemented as a standalone module in the pattern of
`swipe/geometric/` (pure-JVM core, not wired until explicitly approved). The
decode strategy is portable today; the model family is the hard fork.

### Retrain/re-export boundary (the critical split, study §7 dependency note)

| Needs a NEW model (CTC retrain/re-export — hard fork) | Pure engine/plumbing (prototype now against the ported CTC decoder + FUTO's published models) |
|---|---|
| **P-R1** CTC per-frame emission encoder (D1) | **P-P1** Kotlin CTC Viterbi trie-beam engine (D1's decode half) |
| **P-R2** Layout-geometry parameterization `[K,2]`+mask (D2) | **P-P2** Featurizer: linspace 60 Hz → fixed-64, 4/3 aspect, key-centers tensor (D7) |
| **P-R3** Per-layout refinement head à la `magic_macaw` (D5) | **P-P3** Lexicon: trie surface over MAIN + user/custom words with per-source weights (D4) |
| **P-R4** Context LM à la `hungry_jellyfish` (D5 pairing) | **P-P4** Two-phase decode: preview beam 32/top-1, commit beam 300/top-4 (D3) |
| | **P-P5** Runtime hygiene: mmap, fp16+fp32 staging, pre-alloc buffers, big-core pinning (D6) |
| | **P-P6** Orchestrator/engine-selector interface parity (design) |

### Phase A — plumbing that de-risks the fork (prototypable without any retrain)

**A1. Kotlin CTC beam engine port — M/L, medium risk, deps: Track (i) O1/O2 (validated Python port as spec).**
Port `futo_decoder_eval.py`'s Viterbi trie CTC beam (3 transitions/frame
blank/advance/repeat, MAX-merge dedup, length-aware prune
`score/max(depth,1)^gamma_prune + beta_prune·depth`, final
`ctc/L^gamma + weight·beta·L + lambda·log_freq`, per-combo `scoring.json` params —
study §3) to a pure-JVM Kotlin module under e.g. `swipe/ctc/`, testable via
`runPureTests` with golden traces frozen from the Python port.
*DoD:* bit-identical (or within float tolerance) top-4 slate vs the Python port
on ≥500 golden rows; pure-JVM tests green; NOT referenced from the IME.

**A2. Kotlin featurizer — S/M, low risk, deps: A1 goldens.**
`round(dur/16.667)+1` linspace resample, fixed-64 interleave, [0,1] norm, 4/3
vertical aspect correction, key-centers `[1,64,2]` construction from our layout
model. Honor the coordinate-frame contract ("tapping Q must pass Q's center",
study D7) — this is where our current featurizer (QWERTY baked in,
`tools/test_cli_predict.py`) differs most.
*DoD:* encoder-argmax parity with the offline pipeline on golden traces.

**A3. Runtime decision spike: ExecuTorch vs ONNX re-export — M, HIGH decision risk, deps: none (can start immediately).**
FUTO ships ExecuTorch `.pte`; CleverKeys ships ONNX Runtime. Options:
(a) embed a second runtime (ExecuTorch C++/JNI — heavy, new .so, new build
surface); (b) convert/re-export the CTC models to ONNX (spike: does the
`honorable_sturgeon` graph export cleanly? magic_macaw is tiny —
layernorm+log_softmax — and should); (c) if we retrain anyway (P-R1), export
straight to ONNX and the question disappears.
*DoD:* a one-page decision memo with a working ONNX conversion PoC or a
documented blocker.

**A4. Lexicon trie surface — M, medium risk, deps: A1.**
CTC beam needs child-iteration + per-node frequency + terminal flags across
MAIN + user/custom words with per-source weights (study D4/§4f). Decide: extend
`OptimizedVocabulary` with a trie view, or a dedicated CTC trie built from
`en_enhanced.json` + user-dictionary deltas (with incremental add for learned
words). Keep freq on the 1..255 log scale end-to-end (O3 lesson).
*DoD:* trie module + pure tests; beam over it reproduces offline lexicon results.

**A5. Engine-selector + orchestrator interface design — S (design doc only), low risk.**
Define how `swipe/ctc/` plugs in next to `SwipePredictorOrchestrator`: same
input (gesture points + layout), same output (scored candidate slate feeding the
existing rerank/commit path in SuggestionHandler), config keys
(beam widths, gamma/beta/lambda), and the two-phase preview/commit hook points
(study §4f). Explicitly: no code lands in the IME under this plan.
*DoD:* a spec in `docs/specs/` (per SPEC_TEMPLATE) covering interface, config,
two-phase behavior, and fallback semantics.

**A6. Runtime hygiene backlog — S/M, low risk, independent.**
mmap model load, pre-allocated inference buffers, big-core pinning during model
load/swap (study D6). Note: several of these apply to our EXISTING ONNX engine
regardless of the CTC fork — file them as independent perf items.

### Phase B — the hard fork (retrain/re-export required; gated on decisions below)

**B1. CTC-emission model (P-R1 + P-R2) — L, high risk.**
Train (or fine-tune from FUTO's published training code, licensing permitting) a
per-frame key-emission encoder with layout geometry as input. This is the
prerequisite for beam-300 economics and the source of the long-word advantage
(4+: 77.6 vs 69.3, study §5b/D1). Requires training data (our corpora +
FUTO's public swipe data), training infra, and an export path per A3.
*DoD:* offline eval of the new model through the SAME harness (Track i) beats or
matches FUTO enc-only on the full split.

**B2. Refinement head (P-R3) — L (with B1's infra: M), high value.**
The measured +5.88 pt lever (study §5a/D5). Tiny head, but paired-training with
B1.
*DoD:* enc+refine delta reproduced (+≥4 pt over enc-only) on the full split.

**B3. Context LM rerank (P-R4) — M/L, medium risk, deps: O4 port as spec.**
Modular add-on (alpha·lm over top-200, keep CTC top-1); can also be evaluated as
an add-on to our EXISTING transformer slate before any CTC model exists — a
cheap cross-track experiment.

**B4. Router/complement integration — M, deps: O7 verdict.**
If O7 shows fusion beats either engine, the future selector story is
`neural (short) + ctc (long)` behind a gate rather than wholesale replacement.

---

## Recommended near-term sequence (next 3–5 actions)

1. **O0** — finish the corrected apostrophe-lexicon proot re-run; refresh all
   eval tables. (Everything else keys off this baseline.)
2. **O1 (+O2)** — build `infer_jsonl`/`eval_accuracy` in proot; run per-row
   parity + encoder-argmax probe against our Python port. **O3** freq-scale
   audit alongside (it's an hour of work).
3. **O4** — port `hungry_jellyfish`; produce the enc / enc+dec / enc+dec+LM
   triple on 2,400 rows, validated against O1.
4. **O5** — full 48,538-trace split run (chunked/resumable in proot).
5. **O7** — refreshed fusion/router analysis on the corrected full-split
   numbers → written go/no-go recommendation for Phase B, plus kick off **A3**
   (ExecuTorch-vs-ONNX spike) since it gates all in-app work and needs no
   model decisions.

## Key open decisions (user)

1. **Licensing/redistribution.** Can FUTO's published model weights
   (`futo-org/futo-swipe`) and any derived/fine-tuned weights legally ship in
   GPL-3.0 CleverKeys on F-Droid? FUTO's source-first licensing must be checked
   per-artifact (code vs weights vs training code). If NO → B1 retraining from
   permissible data is mandatory before any in-app ship; offline eval use
   remains fine.
2. **Runtime.** Second inference runtime (ExecuTorch JNI) vs ONNX re-export vs
   retrain-to-ONNX (A3). Recommendation: prefer ONNX to avoid a second runtime,
   pending the A3 spike.
3. **Product posture.** Replacement engine vs complement-behind-router — decide
   after O7's fusion numbers on the full split.
4. **Beam implementation language.** Pure-JVM Kotlin (testable via runPureTests,
   consistent with `swipe/geometric/`) vs C++/JNI (FUTO parity, faster beam-300).
   Recommendation: Kotlin first (A1), profile commit-phase beam 300 on-device
   later; only drop to JNI if it misses latency budget.
5. **Investment gate.** Commit to Phase B only after O5 confirms the FUTO
   architecture's advantage holds on the full split with the corrected lexicon
   and the LM ported (i.e., the 92–93% paper numbers are reproducible, not an
   artifact).

---
*Plan authored 2026-08-06; derives entirely from the integration study §1–§7 and
the eval notes. No app code was modified.*
