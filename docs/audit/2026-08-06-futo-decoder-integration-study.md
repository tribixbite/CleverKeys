# FUTO Neural Swipe Decoder — End-to-End Integration Study

**Date:** 2026-08-06
**Author:** repo-study pass (Opus 4.8)
**Purpose:** Distill EXACTLY how the FUTO Android keyboard runs its neural swipe
decoder, so we can (a) upgrade our OFFLINE test harness to match, and (b) plan a
FUTURE in-app "FUTO-style" engine. Study only — no app code was modified.

## Sources read (ground truth)

- **Decode library (C++):** `~/.cache/cleverkeys-test/swipe-library-src`
  (gitlab.futo.org/keyboard/swipe-library) — `src/{resampler,encoder,decoder,
  engine,beam_search,context_lm,trie}.cpp`, `include/swipe_decoder/*.hpp`,
  `jni/swipe_jni.cpp`, `models/scoring.json`, `models/layouts/*.json`,
  `targets/{infer_jsonl,eval_accuracy}.cpp`, `README.md`.
- **App integration (Kotlin/Java):** `~/git/futo-latinime`
  (gitlab.futo.org/keyboard/latinime, GitHub mirror android-keyboard) —
  `java/src/org/futo/inputmethod/latin/SwipeDecoderDictionary.kt`,
  `DictionaryFacilitatorImpl.java`, `BinaryDictionary.java`,
  `keyboard/KeyboardSwitcher.java`.
- **Models** ship from HuggingFace submodule `java/assets/futo-swipe`
  (`futo-org/futo-swipe`): encoder `honorable_sturgeon`, decoder `magic_macaw`,
  context LM `hungry_jellyfish`, `scoring.json`. The `.pte` binaries were not
  present locally (submodule), but every I/O shape is discovered at load from the
  model + `metadata.json`, and the ops manifest (`ops.txt`) is present.
- **Our side:** `tools/test_cli_predict.py`, `scripts/futo_decoder_{eval,ceiling}.py`,
  `docs/eval/futo-decoder-eval-notes.md`, `src/main/kotlin/tribixbite/cleverkeys/
  onnx/{SwipePredictorOrchestrator,BeamSearchEngine,OptimizedVocabulary}.kt`,
  `Config.kt`.

The single most important architectural fact: **FUTO is a non-autoregressive,
CTC-style per-frame key-emission model decoded by a trie-constrained CTC beam
search. Our model is an autoregressive transformer seq2seq that emits character
tokens.** They are different model families and the decode algorithms are not
interchangeable, but FUTO's decode strategy is portable and is what our offline
port already targets.

---

## 1. FUTO model architecture & runtime

Three ExecuTorch `.pte` models (XNNPACK-delegated), each with a sibling
`metadata.json` that names `kind`/`codename` and I/O dims.

### 1a. Encoder — `honorable_sturgeon` (universal, one per keyboard build)
`encoder.cpp:58-200` loads it via `executorch::extension::Module(path, Mmap)` +
`load_forward()`. Shapes are discovered from `method_meta("forward")` plus a dummy
forward (`encoder.cpp:96-191`):

- **Inputs (3):**
  - `features` `[1, 2, T]`, T=64 — interleaved resampled path `[x0..x63, y0..y63]`.
  - `layout_keys` `[1, max_keys, 2]`, max_keys=64 — key-center (cx,cy) in [0,1].
  - `layout_mask` `[1, max_keys]` bool — true for the K real keys, false for pad.
  - dtype fp32 or fp16 (auto-detected; fp16 staged through `fp16_utils`).
- **Outputs (3):**
  - `log_emissions` `[1, T', max_keys+1]`, T'≈T/2=32, width = K_max+1=65 — the
    per-frame log-distribution over keys **+ a CTC blank** at index `max_keys`
    (`encoder.cpp:173-191`, `engine.cpp:290-295`).
  - `coefficients` `[1, T', C]`, C=64 — DCT spatial coefficients (comment
    `layout.hpp:6-9` "DCT spatial head evaluates basis at key centers"; C = freq_x·freq_y).
  - `lambda` `[1, T', 1]` — a per-frame gate scalar.
- **Op manifest** (`models/honorable_sturgeon/ops.txt`): `atan2`, `cumsum`,
  `arange`, `split_with_sizes`, `_log_softmax`, `where`, `bitwise_not` — i.e. the
  path→velocity/angle featurization and the softmax happen **inside** the exported
  graph. The model is layout- and language-agnostic; the layout enters only as the
  key-center + mask tensors. (Architecture family per task brief: temporal
  convolutional / DFSMN encoder with a DCT spatial head.)

The encoder is run ONCE per swipe segment (`engine.cpp:predict_segment` 287-388).

### 1b. Decoder — `magic_macaw` (optional; per-layout + per-language)
`decoder.cpp`. A tiny per-frame **refinement head** (`ops.txt` = only
`native_layer_norm` + `_log_softmax`). Input `[1, T', (K+1)+C+1]` = per-frame
concat of `sliced_emissions | coefficients | lambda` (`engine.cpp:357-376`);
output refined `log_probs [1, T', K+1]`. It does NOT autoregress and does NOT see
the vocabulary — it just sharpens the emission distribution. Used only for
**single-finger** swipes (`engine.cpp:667` `use_decoder = (left+right)==1`) and
only when the on-screen layout matches its trained layout (fingerprint match, §3d).

### 1c. Context LM — `hungry_jellyfish` (optional; per-language)
`context_lm.cpp`. A two-tower next-word model used only for **reranking**, never
in the beam:
- `get_embeddings()` (a second PTE method) returns `exact_embed[T,E]`,
  `exact_bias[T]`, `hash_embed[B,E]`, `hash_bias[B]` — extracted once at load
  (`context_lm.cpp:222-306`).
- `forward(context_ids[1,L], context_hashes[1,L,K=2]) -> h[1,L,E]`; read position
  `L-1` as the context vector `h` (`context_lm.cpp:118-175`).
- Candidate score = `h·emb(word) + bias(word)`. In-vocab words use their exact
  row; OOV words hash via **wyhash** into B buckets, K=2 hashes summed
  (`context_lm.cpp:64-86, 178-212`). Vocab is a plain `vocab.txt` (one word per
  line, id = line+1, id 0 = `<OOV>`), with a lowercase fallback map.

### Runtime & threading
- ExecuTorch C++ runtime, `runtime_init()`, threadpool sized via
  `_unsafe_reset_threadpool(num_threads)` (`encoder.cpp:74-84`; config default
  `num_threads=1`, `engine.hpp:65`).
- Model load and any decoder/LM swap are **pinned to big cores** for the scope via
  `LoadOnBigCores` (`swipe_jni.cpp:45-62, 404-472` reads
  `cpuinfo_max_freq` to classify big/little).
- All per-inference buffers are **pre-allocated once** in `engine.cpp:484-498`
  and reused (no per-swipe allocation). Load mode is `Mmap`.
- `SwipeEngine` is explicitly **not thread-safe** (README:38); the app serializes
  with `BinaryDictionary.sTrieUsageLock`.

---

## 2. FUTO featurization (raw touch → encoder tensors)

Two-stage resample, in `engine.cpp:predict_segment` (287-352):

**Stage 1 — normalize to ~60 Hz** (`resampler.cpp:resample_to_60hz` 134-217).
Training data is ~60 Hz; higher-rate input is normalized down. Output count
`num_output = max(2, round(duration_ms / 16.667) + 1)`, target times
`linspace(0, duration, num_output)`, values by `lower_bound` segment lerp.
The **linspace** choice (vs the old `ceil()+1` constant-stride + endpoint clamp) is
a deliberate train/test-shift fix, documented `resampler.cpp:167-183`: it moved
encoder-argmax agreement with the Python training pipeline from ~63% → 100% of
swipe.futo.org samples (cost ~0.05pp coverage, ~0.17pp top-1).

**Stage 2 — fixed length T=64** (`resampler.cpp:resample_path_interleaved`
115-132 → `resample_path` 19-113). The 60 Hz path is resampled to exactly 64
points by **index-based** (uniform) interpolation and interleaved to `[x…,y…]`,
clamped to [0,1]. (Note: stage 2 passes an empty `t` (`engine.cpp:300`), so it is
index-uniform over the already-time-uniform 60 Hz series.)

**Layout inputs** (`engine.cpp:build_padded_layout_inputs` 41-58): key centers
written into slots `[0..K-1]` as `(cx,cy)`, rest `(0,0)`, mask true for real keys.
Mirrors the training exporter's `sample_layout_inputs()`.

**Coordinate frame:** everything normalized to [0,1]. The canonical en_qwerty
layout (`models/layouts/en_qwerty.json`) has `cx` spanning 0.05–0.95, `cy` at
{0.1667, 0.5, 0.8333} for the three rows. The app applies a `4/3` vertical aspect
correction so the swipe path lands in the same frame as the keys (§3c).

**Emission slicing** (`engine.cpp:344-352`): the encoder emits over K_max+1=65
classes; the engine slices to the active alphabet — copies keys `[0..K-1]` and
moves blank from index `max_keys` (65th slot) to index `K` — giving `sliced_emissions
[T', K+1]` fed to the beam.

---

## 3. FUTO decode (trie-constrained CTC beam search)

Core: `beam_search.cpp` (`TrieBeamSearch`). This is a **single-stream (or
two-stream) CTC beam over trie nodes** — the NN runs once; the beam is pure CPU
over the emission matrix.

### 3a. Hypothesis & transitions
`BeamHypothesis` (`beam_search.cpp:118-134`) = `{score, prune_score, trie_node,
left_be:1, right_be:1, left_idx, right_idx}` — a trie node cursor + per-stream
blank-ended bits + per-stream time cursors. Per time step, each hyp expands
(`advance_beam_search` 190-309) into three CTC moves against the current frame's
log-probs `probs_ts`:

- **A. Emit blank** → stay on node, set `blank_ended`; key = `(node<<1)|1`.
  `score += probs_ts[blank]` (`:214`).
- **B. Emit character** → for every trie child, advance to child node;
  `c = trie.get_char_idx(child)`; `score += probs_ts[c]`; key = `child<<1`
  (`:243-275`).
- **C. Emit same char, stay** (CTC repeat, only if `!blank_ended` and not root):
  `score += probs_ts[same_char]` (`:278-308`). Note (`:241-242`): unlike textbook
  CTC it does **not** require blank between distinct chars — a design choice for
  swipe where each key is passed once.

Dedup uses a custom open-addressing `FlatHashMap` keyed on `(node<<1)|blank_ended`
with a generation counter for O(1) per-step clear (`beam_search.cpp:40-112`); on
collision it keeps the **max score** (Viterbi, not sum).

### 3b. Pruning (length-aware) & top-k
Each step: if candidates > beam_width, `nth_element` on `prune_score` then truncate
(`beam_search.cpp:378-388`). Prune key (`length_prune_score` 140-150, active only in
single-stream mode): `prune_score = score / max(depth,1)^gamma_prune + beta_prune·depth`.
This length-normalizes DURING pruning so short and long words compete fairly inside
the beam — separate from the final ranking exponent.

### 3c. Final word scoring
When a beam node `is_word` (`decode_with_scores_multi` 417-454):
```
final_score = ctc_score / max(len,1)^gamma      (GNMT length norm)
            + weight · beta · len                (per-char length bonus)
            + lambda · log_frequency(node)       (freq bonus, AOSP 1..255 scale)
```
`weight` = the per-trie weight (§3e). Then `finish_decode` (456-486) sorts by
`final_score`, case-insensitive dedups, truncates to top_k. `log_frequency` comes
from the trie (`itrie.h:63-67`, "1..255 similar to AOSP dictionaries").

### 3d. Scoring params are chosen per active model combination
`scoring.json` (built into the lib, `engine.cpp:23-24`) keys parameter sets by the
loaded-model signature (`engine.cpp:158-206` `key_for_flag`), with graceful
fallback (drop decoder, then LM, then "fallback"):

| combo | gamma | lambda | beta | alpha | gamma_prune | beta_prune |
|---|---|---|---|---|---|---|
| fallback | 0.4056 | 0.0176 | 0.9866 | 1.0 | 0.4234 | 1.0382 |
| encoder only | 0.4056 | 0.0176 | 0.9866 | 0.0 | 0.4234 | 1.0382 |
| encoder+decoder | 0.5949 | 0.0134 | 0.7271 | 0.0 | 0.1902 | 1.2727 |
| encoder+contextlm | 0.0159 | 0.0219 | 3.0665 | 0.6459 | 0.2566 | 1.0054 |
| enc+dec+contextlm | 0.1126 | 0.0060 | 2.2138 | 0.6387 | 0.1902 | 1.2727 |

Note the tiny `lambda` (0.006–0.022): it multiplies `log_frequency` on a 1..255
scale, so the effective freq contribution is meaningful. `beta` grows large when
the LM is active (the LM handles ranking; beta just prevents short-word bias).

### 3e. Two-finger / multi-stream & LM rerank
`recognize_multi` (`engine.cpp:655-805`) supports two simultaneous swipes (left =
pointerId 0, right = pointerId 1). The beam advances left/right cursors
independently with a time-lag penalty `delta_ts = -(lag² / 600²)` (`beam_search.cpp
:202-203, 338-376`) to interleave the streams by timestamp. Decode runs once per
trie (`engine.cpp:741-756`), merging results across all tries with per-trie
`weight`.

**LM rerank** (`engine.cpp:764-799`): if a context LM is loaded, context is
non-empty, and `alpha≠0`, the beam first over-generates (`decode_top_k =
max(top_k,200)`), then `final_score += alpha · lm_score` per candidate, resorts,
and truncates to top_k — but **always preserves the CTC top-1** in the visible
slate (`:777-794`).

---

## 4. FUTO app integration (JNI → Kotlin → suggestion strip)

### 4a. JNI surface — `org.futo.ml.inference.SwipeDecoder`
`jni/swipe_jni.cpp` binds native methods on that class (Kotlin wrapper lives in the
`android-libs` submodule, not the latinime repo). Key entrypoints:
- `nativeInit(encoderPath, decoderPath, threads, beamWidth, topK, useExpansion,
  freqKey, lmModelPath, lmVocabPath)` → engine handle (`:66-102`).
- `nativeRecognize(handle, leftSegs, rightSegs, topK, context[], beamWidth,
  trieWeights[])` → `Result[]{word, finalScore, ctcScore, lmScore}` (`:104-205`).
  Each seg is a Java object with `float[] x,y,t` read via reflection (`:115-145`).
- `nativeSetMode(letters, cx[], cy[], trieHandles[long], decoderPath, lmModelPath,
  lmVocabPath)` — atomic layout/dict/decoder/LM swap (`:302-366`); three-state
  per field (null=keep, ""=unload, value=set). Trie handles are raw `ITrie*`
  reinterpreted from `long` (`:334-346`).
- `nativeSetCaseMapping(from[], to[])` injects the platform Unicode lowercase table
  (`:368-393`); `nativePinCores`, `nativeGetScoring/Timing/HasDecoder/HasLm`,
  `nativeDestroy`.

### 4b. Kotlin bridge — `SwipeDecoderDictionary.kt`
A `Dictionary("swipe")` subclass. Assets (copied from APK assets to
`codeCacheDir` at first use, `:274-301`):
- encoder `futo-swipe/honorable_sturgeon/model_fp32.pte`
- en decoder `futo-swipe/magic_macaw/model_fp32.pte`
- en LM `futo-swipe/hungry_jellyfish/context_lm.pte` + `vocab.txt`
- `futo-swipe/scoring.json`

Decoder is constructed with `beamWidth = highestBeam = 300`, `useExpansion=false`
(tries already hold expanded surface forms) (`:330-343`).

### 4c. Gesture → normalized points
AOSP batch-input plumbing produces `composedData.mInputPointers.gestureSegments`
(one per pointer). `getSuggestions` (`:387-521`) transforms each raw pixel point
(`:420-428`):
```
x = rawX / keyboardWidth  · sx + ox
y = min(1, rawY / keyboardHeight · (4/3) · sy + oy)
t = rawT − earliestTime          // ms since swipe start
```
`keyboardWidth = kb.mBaseWidth`, `keyboardHeight = kb.mBaseHeight −
padding.bottom`. `sx,sy,ox,oy` are the layout affine (§4d). Key centers use the
same map (`getKeyXY` `:43-51`: `xMid = (drawX+drawWidth/2)/mBaseWidth`,
`yMid = (y+verticalGap/2+height/2)·(1/(baseHeight−padBottom))·(4/3)`), then a
`yScale` so the lowest key row bottom = 1.0 (`:97`). This is the "make sure Q maps
to the center of the Q key" contract the README stresses (README:83-86).

### 4d. Layout resolution & the "special decoder"
`LayoutInfoForModel.buildLayoutInfo` (`:80-117`): keys are filtered to word
codepoints (non-digit), lowercased, sorted by codepoint, de-duplicated (the engine
forbids duplicate letters), joined into `letters`; guard rejects non-alphabet
keyboards or <6 letters. `SpecialDecoder.matchLayout` (`:170-229`) fingerprints the
on-screen geometry against a hard-coded canonical en_qwerty (`layoutXs/layoutYs`,
`:161-162`) within 0.1 deviation; on a match it enables the `magic_macaw` decoder
and computes the affine `(sx,sy,ox,oy)` that maps physical key positions into the
model's canonical frame. Non-matching layouts run encoder-only with an identity-ish
normalization. Two dev toggles gate decoder/LM (`:235-236`).

### 4e. Lexicon / tries (reuses AOSP binary dictionaries)
`DictionaryFacilitatorImpl.updateSwipeLayoutAndDictsIfNeeded` (`:1101-1154`):
per `DictionaryGroup` (language) it builds ITrie handles from **MAIN + USER +
USER_HISTORY** dictionaries via `BinaryDictionary.getITrieHandleNative(dict,
letters, allowBadWords)` (`BinaryDictionary.java:225,660-665`) — i.e. the SAME
compiled AOSP dictionaries the tap path uses, wrapped in the `ITrie` vtable
(`itrie.h`), letters-aware so `get_char_idx` maps to the active layout. Each trie
carries a per-locale weight `mWeightForGesturingInLocale` (`:1084-1095`), passed as
`trieWeights` into `nativeRecognize`. `sTrieUsageLock` serializes decode vs. trie
mutation (`:462`). The built-in `Trie`/`load_trie_simple` (`trie.hpp:217-223`,
parses raw `.combined`) is the library's convenience path, NOT what the app uses.

### 4f. Two-phase decode (live preview vs commit)
`DictionaryFacilitatorImpl:880-901` calls the swipe dict with
`useHighBeam = (inputStyle == INPUT_STYLE_TAIL_BATCH)`:
- **In-progress** gesture (UPDATE_BATCH) → `useHighBeam=false` → beam **32**
  (single) / **64** (multi), `topK=1` — a fast live top-1 preview.
- **Gesture end** (TAIL_BATCH) → `useHighBeam=true` → beam **300**, `topK=4` — the
  final 4-word slate (`SwipeDecoderDictionary.kt:323-328, 453-460`).
Context = last 10 whitespace-split words of the current line (`:440-447`). Results
become `SuggestedWordInfo(KIND_CORRECTION, mOriginatesFromSwipeModel=true)` with
score `int(finalScore·1000 + 10000)` (`:511-518`), fed into the normal suggestion
strip; the first swipe suggestion also seeds emoji suggestions (`:895-896`).

### Reference offline harnesses (in the library itself)
`targets/infer_jsonl.cpp` (encoder+vocab+layout+JSONL → predictions, flags
`--beam-width/--top-k/--gamma/--lambda/--beta/--alpha/--decoder/--lm-model`) and
`targets/eval_accuracy.cpp` (binary swipe format → accuracy). These are the
ground-truth reference runners we can build and diff against.

---

## 5. Our approach (for contrast)

**Model family:** ONNX **autoregressive transformer seq2seq** (different from FUTO).
- **Encoder** `swipe_encoder_android.onnx`: `trajectory_features [b,250,6]`
  (x_norm, y_norm, vx, vy, ax, ay) + `nearest_keys [b,250] i32` +
  `actual_length [b] i32` → `encoder_output [b,250,256]`
  (`tools/test_cli_predict.py:11-12,138-178`). Input is trajectory features + a
  per-point nearest-key hint, resampled to ≤250 frames; the encoder is NOT
  layout-geometry-parameterized (26-key QWERTY baked into the CLI).
- **Decoder** `swipe_decoder_android.onnx`: `memory [1,enc_seq,256]` +
  `target_tokens [num_beams,20] i32` + `actual_src_length [1] i32` →
  `log_probs [num_beams,20,30]` (already log-softmaxed). It **autoregresses** over
  a **30-token** char vocabulary: PAD=0, UNK=1, SOS=2, EOS=3, a=4…z=29
  (`BeamSearchEngine.kt:54-57`); `DECODER_SEQ_LEN=20` (max word length). Decode
  starts from a single SOS beam and each step appends one char token; a beam
  **finishes when it emits EOS (idx 3)** (`BeamSearchEngine.kt:99, 334-340`).
  Broadcast decode `[num_beams,20]` gives the 6–8× batched speedup.
- **Beam search** (`onnx/BeamSearchEngine.kt`, `Config.kt:134-161`):
  per-beam pipeline = **trie masking → prefix boosts → log-softmax → top-K →
  expand**. Trie masking sets logits to −∞ for SOS/PAD always, EOS unless the
  prefix is a complete word, and any char not in `vocabTrie.getAllowedNextChars(prefix)`
  — so the token beam can never leave the lexicon. Score = accumulated **negative
  log-likelihood** (lower better). Length-normalized ranking divides by
  `normFactor = (5+len)^α / 6^α` with `α = NEURAL_BEAM_ALPHA = 1.4`. Defaults:
  `NEURAL_BEAM_WIDTH=6`, `NEURAL_MAX_LENGTH=20`, adaptive-width prune
  (`0.8` conf from step 12), score-gap early stop (`80` from step 12),
  temperature 1.0.
- **Confidence + rerank:** `confidence = exp(−score / normFactor)`; drop below
  `confidenceThreshold`. Survivors reranked by `(confW·conf + freqW·freq)·boost`,
  where for English `confW=0.80`, `freqW=0.20` (`Config.kt:858-860`) and freq is
  further scaled by `NEURAL_FREQUENCY_WEIGHT=0.57`. A Levenshtein dict-fuzzy rescue
  runs if <3 survive. Prefix-boost trie + strict-start-char are **inert for en**
  (no `en.bin` asset).
- **Vocabulary:** `OptimizedVocabulary.kt` — flat `en_enhanced.json`
  `{word: 128..255}`, freq `= ((raw−128)/127).coerceAtLeast(0.001)`, rank tiers
  (<100 common, <3000 top3000), capped 150k words. Builds the `VocabularyTrie`
  that `BeamSearchEngine` masks against (`getAllowedNextChars` / `containsWord`).

**Existing FUTO port (offline) — already substantial.**
`scripts/futo_decoder_eval.py` + `scripts/futo_decoder_ceiling.py` are a faithful
Python port of FUTO's CTC decode: two-stage resampler (60 Hz → fixed 64), the
sliced `[T'=32, 27]` emissions, **greedy CTC** collapse, a **trie-constrained CTC
beam** (both a logaddexp variant and FUTO's MAX-merge single-stream **Viterbi**
beam with the three expansions blank/child-char/repeat-char and length-aware prune
`score/max(d,1)^gp + bp·d`), the per-word score `ctc/L^gamma + beta·len +
lambda·log_freq` with `scoring.json` params, AND it wires the optional
**`magic_macaw` refinement decoder** (input `[32,92]` = per-frame concat
emissions[27]+DCT coeffs[64]+λ[1], output refined `log_probs[32,27]` replacing
emissions). It does NOT yet port the `hungry_jellyfish` context LM.

### 5a. Measured results (`docs/eval/futo-decoder-eval-notes.md`)
Same 2,400-row held-out split (`test_hwsfuto.jsonl`), FUTO's 131,544-word AOSP
lexicon:

| Config | overall t1/t3/t5 | greedy-CTC t1 |
|---|---|---|
| A: enc-only, logaddexp CTC beam | 79.25 / 87.71 / 89.58 | 43.96% |
| B: enc-only, FUTO Viterbi beam | 78.96 / 88.17 / 90.12 | 43.96% |
| **D: enc + magic_macaw (ceiling)** | **84.83 / 91.04 / 92.08** | **69.12%** |

**Decomposition — the key finding:** the optimized Viterbi beam was **~neutral on
top-1** (B−A = −0.29 pt); **all the gain is the `magic_macaw` refinement decoder**
(D−B = **+5.88 pt**; greedy-CTC 43.96% → 69.12%). This corrected an earlier
hypothesis that the beam was the bigger lever. FUTO's own paper reports 92.54%
(enc-only) / 93.30% (enc+dec); our repro floor (78.96 / 84.83) is lower because
our 2,400 rows are a harder/noisier subset of their 48,538-trace split, our beam
port is textbook-vs-production, and we run no context LM.

### 5b. Head-to-head vs our shipped engine (notes:163-177)
| Engine | overall t1 | t3 | t5 | ≤3-char t1 | 4+-char t1 |
|---|---|---|---|---|---|
| FUTO encoder (this eval) | 79.25% | 87.71% | 89.58% | 82.45% | 77.60% |
| Our shipped ONNX neural (beam 6) | ~76.34% | 85.39% | 88.68% | **88.33%** | 69.28% |
| Our geometric SHARK2 | ~75.3% | — | — | — | — |

Cross-stratum split: **FUTO's TCN/CTC leads on long words** (4+: 77.6% vs 69.3% —
its layout-parameterized per-frame model + wide lexicon beam handle long
trajectories better), while **our transformer + freq rerank leads on short words**
(≤3: 88.3% vs 82.5%). (Our-neural anchor was a smaller in-vocab sample, so the
~3 pt top-1 gap is indicative, not exact.)

---

## 6. Side-by-side: FUTO vs. ours

| Dimension | FUTO (honorable_sturgeon + magic_macaw + hungry_jellyfish) | Ours (ONNX transformer) |
|---|---|---|
| Model family | Non-autoregressive **CTC per-frame key emissions** | **Autoregressive** char-token seq2seq |
| Runtime | ExecuTorch C++ (XNNPACK), mmap, fp16/fp32, big-core pinned | ONNX Runtime (Kotlin/JVM + wasm demo) |
| NN calls per decode | **1** encoder (+1 tiny refine) — beam is pure CPU | **O(steps·beams)** decoder forwards (autoregressive) |
| Feasible beam width | **300** (final), 32/64 (live) | **6** (default) — bounded by per-step NN cost |
| Encoder input | raw path `[2,64]` **+ key-center geometry `[64,2]` + mask** | trajectory feats `[250,6]` **+ nearest-key hint `[250]`** |
| Layout handling | Geometry is a model input → **one universal encoder, any layout/lang** | 26-key QWERTY baked into featurizer/CLI |
| Resampling | 60 Hz linspace → fixed 64, index-uniform | resample to ≤250 (`discard` mode) |
| Decode constraint | trie-constrained CTC (Viterbi w/ blank collapse) | vocab-trie **logit masking** over token beam |
| Length norm | `ctc/L^gamma` + `beta·L`, gamma tuned per-combo (0.11–0.59); **separate** length-aware pruning key | GNMT `alpha=1.4` length penalty |
| Freq | `lambda·log_freq` (AOSP 1..255), lambda≈0.006–0.022 | `0.57·norm_freq` in rerank |
| Context LM | Yes — `hungry_jellyfish` reranks (`alpha≈0.64`), over-generate top-200 then rescore, preserve CTC top-1 | **None** |
| Multi-finger | Yes — two-stream beam w/ time-lag penalty | No |
| Lexicon | **AOSP MAIN+USER+USER_HISTORY** tries, per-locale weights, live | OptimizedVocabulary (en_enhanced) |
| Live preview | Two-phase (beam 32 top-1 during, beam 300 top-4 at end) | single-phase |
| Timestamps | Used (60 Hz normalize + two-finger lag) | **corrupt in our corpus → position-only** beats velocity (53% vs 29%, `test_cli_predict.py:35-37`) |

**Where matching FUTO would help us most:**
1. **Decode cost/quality tradeoff.** FUTO's one-shot-NN + CPU-beam lets it run
   beam 300; our autoregressive decoder caps practical beam at ~6. This is the
   single biggest structural gap (accuracy headroom + latency).
2. **Layout-parameterized encoder.** FUTO feeds key geometry as a tensor, so one
   encoder serves every layout/language; ours hard-codes QWERTY.
3. **Length-aware pruning** distinct from final length-norm — FUTO prunes the beam
   on a normalized key so long words survive; we only apply alpha at the end.
4. **Context LM rerank** — a cheap accuracy win FUTO has and we don't.
5. **Reuse of the live AOSP dictionaries** (user + history) with per-locale
   weights, rather than a static vocab.

---

## 7. Prioritized upgrade opportunities

### (i) OFFLINE harness — do now (no app changes)

Our Python port (`futo_decoder_eval.py`/`futo_decoder_ceiling.py`) already
replicates the two-stage resampler, the sliced emissions, the Viterbi trie CTC
beam with length-aware pruning, the per-combo `scoring.json` params, and the
`magic_macaw` refinement decoder (§5). The measured lever is the **refinement
decoder** (+5.88 pt), not the beam (≈neutral). So harness work should chase the
remaining accuracy levers and an independent ground-truth check — NOT beam tuning.

**H1 (high). Port the `hungry_jellyfish` context LM into the eval.**
The only unported model. It is a modular reranker: `final += alpha·lm_score`
(alpha≈0.64), over-generate top-200 then rescore, preserve CTC top-1
(`engine.cpp:764-799`, `context_lm.cpp`). It closes part of the gap to FUTO's
paper numbers and is a candidate accuracy add-on for our own engine (§ii D5). Port
`get_embeddings` extraction, the wyhash-bucket OOV path (K=2), and the
`h·emb+bias` scorer.

**H2 (high). Build FUTO's own reference runner as independent ground truth.**
`targets/infer_jsonl.cpp` (encoder+vocab+layout+JSONL, flags
`--beam-width/--top-k/--gamma/--lambda/--beta/--alpha/--decoder/--lm-model`) and
`targets/eval_accuracy.cpp`, built against the HF `futo-org/futo-swipe` models +
`models/layouts/en_qwerty.json`. Diffing it against our Python port on the same
JSONL validates the port itself (catches resampler/coordinate drift we can't see
by porting blind) and yields a true enc/enc+dec/enc+dec+LM triple.

**H3 (high). Run the full 48,538-trace split, not just our 2,400 subset.**
The 78.96/84.83 repro floor is partly a hard-subset artifact vs FUTO's paper
92.54/93.30. Evaluate on the full split so our numbers are comparable and so the
per-stratum (short vs long word) story is statistically solid.

**H4 (medium). Verify the linspace resampler detail.**
Confirm `futo_decoder_ceiling.py`'s `resample_to_60hz` uses
`linspace(0,dur, round(dur/16.667)+1)` + lower_bound lerp, NOT `ceil()+1`
constant-stride + endpoint clamp — the documented 63%→100% encoder-argmax fix
(`resampler.cpp:167-183`). Add an encoder-argmax parity probe against the H2
reference runner as the fastest mismatch detector.

**H5 (medium). Keep the frequency scale on AOSP `log_frequency∈[1,255]`.**
FUTO's `lambda≈0.006–0.022` is calibrated against 1..255 log-freq
(`itrie.h:63-67`), NOT our normalized `(raw−128)/127`. Confirm the port feeds
1..255-scale log-freq so the `scoring.json` params are meaningful; a normalized
scale makes `lambda` ~2 orders of magnitude too small.

**H6 (low). Match slate semantics for apples-to-apples top-k.**
Case-insensitive dedup + top-k (`beam_search.cpp:456-486`); with the LM,
the over-generate/rescore/keep-CTC-top-1 behavior. Ensures our top-1/top-4 are
comparable to a real FUTO slate. (Beam-width sweeps are explicitly low value —
measured ≈neutral.)

### (ii) FUTURE in-app "FUTO-style" engine — design notes (NOT to wire in now)

Order reflects the measured levers (§5a): the per-frame refinement decoder is the
accuracy win; the CTC topology is the enabler that makes wide beams affordable; the
beam algorithm itself is ~neutral.

**D1. Adopt the non-autoregressive CTC decode topology.** The decisive structural
advantage is decoupling NN cost from beam width: run the encoder ONCE, then a
pure-CPU trie-constrained CTC beam. This is what makes beam 300 affordable and
removes our autoregressive per-step decoder forwards (our current cap is beam 6).
It requires a **different exported model** (per-frame key emissions + blank), i.e.
a retrain/re-export in the CTC-emission architecture, not a wrapper over our
current transformer. This is the prerequisite for everything below, and is where
FUTO's long-word advantage (4+ chars: 77.6% vs our 69.3%) originates.

**D2. Parameterize the encoder by layout geometry.** Feed key centers `[K,2]` +
mask as inputs (FUTO `honorable_sturgeon`) so one encoder serves all layouts and
languages — directly enabling our multi-language packs without per-layout models.

**D3. Two-phase beam in the IME.** Mirror FUTO's live-preview (beam 32, top-1) vs
commit (beam 300, top-4) split keyed on batch input style, for responsive gliding
without paying the full beam on every intermediate frame.

**D4. Trie over the live AOSP-style dictionaries with per-source weights.** Decode
against MAIN + USER + USER_HISTORY tries simultaneously with weights, via an
`ITrie`-style vtable, so swipe benefits from learned/user words like the tap path.
Our `OptimizedVocabulary` would need an incremental/user-word trie surface.

**D5. Per-layout refinement decoder — the measured accuracy lever.** The
`magic_macaw` refine head (tiny: only layernorm + log_softmax) consumes per-frame
`emissions|coeffs|λ` and replaces the emissions before the beam. In our own eval
it delivered the entire +5.88 pt top-1 gain (greedy-CTC 43.96%→69.12%, §5a) while
the beam was neutral. If we go CTC (D1), exporting a paired refinement head per
layout/language is the single highest-value accuracy add-on. Pair it with the
`hungry_jellyfish`-style context LM rerank (`alpha≈0.64`, over-generate top-200
then rescore, keep CTC top-1) — a second modular add-on that plugs onto the CTC
core without touching the beam.

**D6. Runtime hygiene worth copying regardless of model family:** mmap model load,
fp16 weights with fp32 staging, pre-allocated reusable inference buffers, and
big-core pinning during model load/swap (`swipe_jni.cpp:45-62`, `engine.cpp:484-498`).

**D7. Coordinate-frame contract.** If we move to a geometry-parameterized encoder,
replicate FUTO's `4/3` vertical aspect correction + per-device affine
(`SwipeDecoderDictionary.kt:43-55, 194-224`) so on-screen key centers land exactly
where the model expects — the README's explicit "tapping Q must pass Q's center"
warning (README:83-86).

**Dependency note:** D1/D2 need a model retrain/re-export in the CTC-emission
architecture; D3/D4/D6/D7 are engine/plumbing changes that could be prototyped
against a ported CTC decoder even before a new model exists.
