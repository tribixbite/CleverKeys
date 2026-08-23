# CTC architecture and the multi-script question — the definitive guide

**Status**: reference. **Written**: 2026-08-18.
**App state described**: `9a6ffdd2` (post neural-engine removal; `ctc` is now the DEFAULT
`swipe_engine_mode`).
**Training-side source of truth**: `CleverKeys-ML` @ `ctc/` — `MODELS_TABLE.md`,
`PHASE_I_DATA.md` §4–§6, `PHASE_J.md` §6.9, `ALT_LAYOUT_EVAL.md`,
`YANDEX_LICENSE_RESEARCH.md`, `APP_INTEGRATION_AUDIT.md`.
A byte-identical mirror of this file lives at `CleverKeys-ML/ctc/ctc-architecture-and-multiscript-guide.md`.

This document exists to kill four recurring confusions permanently:

1. "The CTC model's alphabet is hardcoded a–z." — **The model has no alphabet at all.**
2. "Non-Latin needs a per-script model; is that tractable? No." — **Per-script models are
   needed and they cost ~30 minutes of GPU per seed.** Russian is done; the artifacts are
   named in §4.
3. "37 layouts don't declare a script." — **Two do not, and neither is a letter layout.**
   The real gap is three *mis-declared* or letter-incomplete layouts, listed in §3.
4. "Which ONNX?" — §5 is the inventory table.

---

## 1. How the architecture actually works

### 1.1 The head has 64 geometry-conditioned slots, not 26 letters

The exported graph's contract (`CleverKeys-ML/ctc/model.py`, and the Kotlin twin in
`swipe/ctc/`):

```
in : features    [1, 2, 64]  float32   x row, y row, resampled to 64 frames, both in [0,1]
     layout_keys [1, 64, 2]  float32   key centers in the model's [0,1] frame, pad = (0,0)
     layout_mask [1, 64]     bool      true for the first K real key slots
out: log_emissions [1, 32, 65] float32 log-softmaxed; blank is column 64
     coefficients  [1, 32, 64] float32 (phase-2 refinement input, unused in the app)
     lambda        [1, 32, 1]  float32 (per-frame positive gate)
```

`MAX_KEYS = 64`. The emission head is 65 wide: **64 key slots plus one blank**. There is no
letter anywhere in the graph. Emission column `c` means *"the key that the caller put in slot
`c` of `layout_keys`"* — nothing more. Scoring is
`key_logits = coefficients @ keyEmbed(layout_keys)ᵀ * lambda`, and `keyEmbed` is a function of
`(cx, cy)` plus a 64-dim cosine positional encoding of that same `(cx, cy)` — **never of the
slot index**. That is precisely why the slot-permutation augmentation works during training,
and it is why a 31-letter ЙЦУКЕН layout simply uses 31 of the 64 slots with no model change
(`PHASE_I_DATA.md` §4, measured, not assumed).

Two independent confirmations that the alphabet is *data*:

- the Russian encoder's ONNX is **1,142,727 bytes — byte-size-identical** to every other
  `resbn:80` artifact in the campaign, because the alphabet never enters the parameter count;
- the app's own decode-side slice, `CtcEmissions.sliceFromHead(fullHead, frames, maxKeys,
  numLetters)`, takes `numLetters` as a *parameter* and copies columns `0 until numLetters`
  plus column `maxKeys` (the blank). It has no 26 in it.

### 1.2 Where the a–z actually lives

Three places, all app-side, none of them the model:

| Site | The a–z assumption | File |
|---|---|---|
| `CtcEngineAdapter.ALPHABET` | `CharArray(26) { 'a' + it }` — the only alphabet the adapter ever builds | `swipe/CtcEngineAdapter.kt:113` |
| `buildMappedLayout` | `FloatArray(26)` / `BooleanArray(26)`, `letterOf` accepts only `'a'..'z'`, and returns **null** unless all 26 are present | `swipe/CtcEngineAdapter.kt:266-301` |
| `SwipeEngineRouter.isLatinScript` | `script.equals("latin", ignoreCase = true)` | `swipe/SwipeEngineRouter.kt:118-119` |

Everything downstream is already script-generic and was written that way on purpose:

- `CtcLayout(alphabet: CharArray, keyCentersX, keyCentersY)` — any K, any chars, deduplicated;
- `CtcLexiconTrie(alphabet: CharArray)` — constructor bound is `alphabet.size <=
  CtcFeaturizer.MAX_KEYS` (64), i.e. the *emission head width*, not 26;
- `CtcTrieNode.addChild` — the child-array growth is **deliberately unclamped**. It used to be
  `minOf(chars.size * 2, MAX_CHILDREN=26)`, which saturates at 26 and throws
  `ArrayIndexOutOfBounds` on the 27th distinct child. That clamp is **already removed**
  (`swipe/ctc/CtcLexiconTrie.kt:86-104`, and the constant is gone rather than merely unused,
  `:123-130`). **A 31- or 33-letter Cyrillic trie is safe in the shipped code today.** This is
  the single most important piece of pre-work that is already done.

So: "the ALPHABET is hardcoded a–z" is true of *two constants in one adapter file* and false of
the model, the emissions type, the layout type and the trie.

### 1.3 The layout frame — and why it lines up across scripts

`buildMappedLayout` normalizes each letter's center against the **bounding box of the letter
keys only** (`left/top/right/bottom` accumulated over a–z rects), not against the keyboard
frame. Every trained geometry in the campaign lives in that same canonical letter-area frame —
including the Cyrillic conversion, where using a naive `y / height` instead would have squashed
the letter block to 46 % of the unit square and landed outside the measured affine-tolerance
envelope (`PHASE_I_DATA.md` §4). The frames already agree; a ru wiring does not have to invent
one.

### 1.4 Why ONE model serves ALL Latin a–z layouts, including custom user XML

Because key geometry is a model *input*, the encoder is arrangement-agnostic **by design**.
Training then makes that design real: the layout-resampling augmentation (`layout_aug.warp_path`)
re-anchors real human traces onto arbitrary a–z arrangements, so an arbitrary Latin layout is
in-distribution rather than merely "hopefully close".

The measured evidence for the shipped model (`phaseM_kd_fresh_w1_s1234_fp16w`,
`MODELS_TABLE.md:113`, in-dict top-1, en lexicon, 3 seeds):

| layout | top-1 | note |
|---|---|---|
| dvorak | **91.82** | never trained on as a named layout — held out |
| dvorak, app-geometry trie | **91.10** | the app's own 98 k trie footing |
| spanish | 89.53 | |
| azerty | 84.53 | |
| qwertz | 83.97 | |
| german | 81.30 | worst measured layout |

Two things make this credible rather than hopeful. First, `ALT_LAYOUT_EVAL.md` characterised
the displacement sensitivity and showed **earlier, weaker models visibly failing it** — ch128
scored dvorak **63.04**, `fast_resbn80` **67.28**. The ship model's 91.82 is therefore a
property of the training recipe, not of a forgiving benchmark. Second, six layouts spanning a
wide displacement range all hold.

**Honest tier note.** Colemak specifically, and arbitrary user XML generally, were **never
benchmarked**. They are covered *by design* (geometry input + arbitrary-arrangement
augmentation) and the floor is not measured — the worst *measured* layout is german at 81.30
against geometric's ~77 % top-1, so the expected-value case for routing them to CTC is strong,
but "Colemak ≥ geometric" is an inference, not a measurement. Say it that way.

**Do NOT quote 89.87 / 88.98 / 80.64 / 88.45 / 83.81 / 83.01.** Those are `sw2345`'s
(`MODELS_TABLE.md:139`) — a superseded Phase-J finalist that was **never decoded on test**. The
app's `src/main` was cleaned of them, and `CoreImeHygieneDriftTest` now bans them — but the
guard walks `src/main/kotlin` only, and `src/test/.../SwipeEngineRouterTest.kt:20` still quotes
them (see §6, checklist item 3).

---

## 2. The routing rule, stated once

```
swipe_engine_mode == "geometric"          -> GEOMETRIC
layout.script resolves to "latin"
    AND the layout exposes all 26 a-z as CENTRE key values   -> CTC
    AND the active language is in CtcLanguageSupport.SUPPORTED
anything else                                                -> GEOMETRIC
```

As implemented, this is three gates in dispatch order, each individually sufficient:

1. **Script** — `SwipeEngineRouter.route` returns `Engine.CTC` iff `isLatinScript(script)`.
   `script == null` is false, so an undeclared layout goes geometric.
2. **Language** — `InputCoordinator.performCtcSwipeTyping` reads the live language before
   dispatch; `CtcEngineAdapter.decodeAsync`/`warmUpAsync` re-check as defense in depth.
   `SUPPORTED` at `9a6ffdd2` is `en` (EN_JSON scale) and `fr, de, es, it, pt, sv` (CKDT scale);
   `it, pt, sv` are marked `PROVISIONAL`.
3. **Alphabet completeness** — `buildMappedLayout` returns null on the first missing a–z
   letter, `supportsLayout` returns false, and the dispatcher hands the swipe to geometric
   *before any CTC work starts*. No crash, no empty bar, no garbage decode.

**Russian cannot reach CTC in any state**, and that is correct, because no Russian model,
trie, or fixture is wired into the app. Gate 2 also covers the inverse case (`ru` language on a
QWERTY layout): geometric, not CTC.

### 2.1 The "undeclared script" question — measured, not assumed

Measured on `src/main/layouts/` at `9a6ffdd2` (86 XML files; this is the tree
`copyLayoutDefinitions` ships — `srcs/layouts/` is **not** referenced by any build task and is
divergent):

| bucket | count | consequence |
|---|---|---|
| `script="latin"` **and** a–z-complete | **46** | route CTC — the intended set |
| `script="latin"` but a–z-incomplete | **3** | router lets them past, the alphabet gate stops them → geometric |
| non-Latin declared (15 distinct scripts) | **35** | geometric at gate 1 |
| no `script` attribute at all | **2** | `numeric.xml`, `pin.xml` — not letter layouts, correctly geometric |

There is **no population of 37 undeclared layouts.** The three latin-declared-but-incomplete
files are:

| file | missing | verdict |
|---|---|---|
| `latn_qwerty_az.xml` | `w` | correct declaration, genuinely lacks `w` — geometric is right |
| `latn_qwerty_tly.xml` | `w` | same |
| **`grek_qwerty.xml`** | all 26 | **MIS-DECLARED — a live bug, see below** |

**`src/main/layouts/grek_qwerty.xml` declares `script="latin"`.** The sibling
`srcs/layouts/grek_qwerty.xml` was corrected to `script="greek"` in commit `6af11da7`
("closes neural-swipe allowlist leak") — **but that fix landed in the tree the build does not
read.** The shipped Greek layout is still tagged Latin, so it passes the router's script gate
and is caught only by the alphabet gate. User-visible impact today: none (it falls to geometric
either way). Correctness impact: the commit that claimed to close the leak did not close it for
the shipped layouts, and the script gate is being relied on as gate 1 of 3.

**So the rule is: what these layouts need is script declaration and verification, not new
models.** The actionable work is (a) fix `grek_qwerty.xml`'s attribute in
`src/main/layouts/`, (b) add a pure-JVM test that walks `src/main/layouts/*.xml` and asserts
`script="latin"` ⟺ a–z-complete (with `az`/`tly` as named exceptions), and (c) add the negative
test the audit's LOW-9 asks for: feed a ЙЦУКЕН `KeyboardData` to
`CtcEngineAdapter.supportsLayout` and assert false, so "Cyrillic can never reach CTC" rests on
more than the script string in the test suite.

---

## 3. Non-Latin: per-script models are required, and they are cheap

### 3.1 Both halves of the claim

**Required.** A model trained on Latin-arrangement geometry with English lexicon statistics does
not zero-shot another script. The geometry is a model input, but the *motor statistics* and the
*implicit character-transition prior the encoder learns* are trained. Do not route a non-Latin
layout at CTC and hope.

**Cheap.** The residual-transplant synthesis pipeline generalizes to **any** script given only
(i) a word list and (ii) the layout geometry. No data collection, no corpus licensing, no human
subjects. Measured cost for Russian: 94 k steps of `resbn:80` — well under an hour of a single
RTX 5080 — plus a few minutes to export and evaluate.

**Proven, not projected.** `phaseIB-ru-synth` was trained on 1,000,000 rows in which **no real
Cyrillic sample appears anywhere** (checkpoint selection ran on a synthetic val split too), and
it decodes **real** Russian swipes at in-dict top-1 **76.21** at E1, rising to **77.41** once
the λ term is put on the lexicon's actual frequency scale (§4). That is the same accuracy class
as the shipped geometric engine's cross-layout anchors (71–77).

### 3.2 The recipe, step by step

All tools live in `CleverKeys-ML/ctc/`.

1. **Vendor the geometry.** One JSON per layout: `{name, letters, letter_block_px, keys:[{letter, cx, cy, rx, ry}]}`
   with centers in the **canonical letter-area frame** (x over the grid width, y over the
   *letter-key block* — rows at cy 0.167 / 0.5 / 0.833). See `layouts/ru_jcuken_default.json`.
   Loads through the campaign's `load_layout`.
2. **Validate the frame before training anything.** Endpoint-proximity on a couple of thousand
   traces against the claimed geometry, **with a deliberately wrong geometry as a falsification
   control**. Russian measured start-hit 0.917 / end-hit 0.647 against a wrong-geometry control
   that collapsed to 0.008 / 0.004 (`PHASE_I_DATA.md` §4). Skipping this step is how a whole
   training run gets wasted on a squashed frame.
3. **Synthesize.** `cyrillic_synth.py` — despite the name it is the generic residual transplant.
   English human traces are the donor pool; donor match is purely structural (collapsed-polyline
   vertex count), the correspondence is geometric (`layout_aug.warp_path` verbatim through
   per-vertex virtual indices), and **letter identity never enters**. Words are drawn from the
   target script's lexicon with `weight = 255 − rank`.
4. **Train.** `train.py --layout <geometry.json> --cache <cache_dir> --beam-val-rows 0`. The
   `--beam-val-rows 0` matters: the in-training beam validator's vocab loader is a–z-hardcoded,
   so selection runs on greedy and the lexicon beam runs offline in `eval_cyrillic.py`. The rest
   of the recipe is untouched — `resbn:80:1,2,4,8`, embed_hid 96, 94 k steps, batch 256, lr 3e-3,
   coupled affine sampler, no layout-alt (single geometry).
5. **Export.** `export_onnx.py --ckpt ... --layout <geometry.json> --parity-features <cache>/val.npz`.
   The parity assertion runs on the **sliced contract view** and on **real traces on the real
   layout**; argmax agreement must be 100/100. Then `quantize_onnx.py --mode fp16w` for the ship
   bytes.
6. **Freeze a golden fixture.** `make_golden.py --vocab <script> --layout <geometry.json>
   --preset <the app preset>`. The fixture must be generated at the preset the app will actually
   ship, on the lexicon's real frequency scale, or it validates a ranking that never runs.
7. **Measure on real data if any exists** — as a held-out *eval-only* probe. Synthesis is the
   training story; real data is how you find out whether the synthesis worked.

### 3.3 What synthesis buys and what it costs

The paired arms answer this exactly. Same recipe, same eval, same 9,416 real rows:

| arm | training data | in-dict t1 (E1) | greedy |
|---|---|---|---|
| `phaseIB-ru-real` | 1 M **real** Yandex rows | 89.64 | 75.23 |
| `phaseIB-ru-synth` | 1 M **synthetic** rows, zero real | **76.21** | 37.07 |

Real data is worth ~13 top-1 points. The synth arm's greedy collapses to 37 (English-magnitude
start noise on a denser board), so a synth-trained ship **leans hard on its trie** — λ is worth
+7.6 t1 there versus +0.9 on the real arm. Budget for that: a synth-launched script needs a
good lexicon far more than a real-data one does.

---

## 4. Russian — DELIVERED, as the worked example

### 4.1 What is shippable and what is not

| model | in-dict t1 | status |
|---|---|---|
| `phaseIB-ru-real` (1 M real Yandex rows) | 89.64 | **LICENSE-BLOCKED FOREVER.** Not shippable in any form, at any time. |
| `phaseJ-joint` (single en+ru model) | 78.23 confirm-half @ λ 2.0 | **REJECTED.** Its data is license-clean, but it cost **−0.42 en top-1** against a 0.3 tolerance and was not adopted. Its ru lead over the bar-holder is +0.31 on the confirm half — inside one binomial SE (±0.64 at n = 4,240) — and it *loses* t3 and t5 on that same half. |
| **`phaseIB-ru-synth`** | **77.41** full-set / **77.92** confirm-half @ λ 2.0 | **THE SHIPPABLE ONE.** Trained purely on residual-transplant synthesis; zero Yandex rows anywhere in its pipeline. |

**The licensing line, stated exactly** (`YANDEX_LICENSE_RESEARCH.md`, 941 lines of it): the
Yandex Cup 2023 corpus has **no license grant anywhere** — not in the contest rules, the Cup
regulations, the Disk link, the solution repos, or the Kaggle mirror. Background terms are
restrictive: Yandex's services agreement authorises only personal non-commercial use, and the
corpus is a protected database under ГК РФ ст. 1334 (6 M rows ≫ the 10 k presumption; term to
~2039) whose ст. 1335.1 carve-outs cover research and education but **not a shipped product**.
Verdict: **research and held-out evaluation only; synthesis-only for anything that ships.**
`ru-synth` is trained synth-only and *evaluated* on Yandex rows — which is exactly the permitted
footing. `ru-real` is a research artifact and stays one.

### 4.2 The artifacts

Committed in `CleverKeys-ML/ctc/artifacts/`:

| file | bytes | sha256 |
|---|---|---|
| `ru_synth_ch80.onnx` (fp32) | 1,142,727 | `d78a9fb9f8e170595a7714220cf5fd9dfc2324935900aec6cb6d7a2ec1a36666` |
| `ru_synth_ch80_fp16w.onnx` (**ship bytes**) | 589,406 | `84ac284d4f0d0cb86061df9c557507e1489ab93a75b40885a4431976cee32469` |
| `ru_synth_ch80_fp16w_golden.json` (fixture) | 160,876 | `041c20722a957d1341108eb969dc677a123363011094ad05b36fdc1baa1050b0` |

Source checkpoint `~/ctc-train/ckpt/phaseIB-ru-synth/best.pt` — `resbn:80`, dil `1,2,4,8`,
embed_hid 96, feat_v1, `t_out` 32, 279,346 params, step 87,000 of a 94,000-step schedule,
greedy-selected. The fp32 re-export is **byte-identical** to the artifact the training run
produced in 2026-08-09, which is a free determinism check on the whole export path.

Export gates, all passed:

- BN fold: max |Δlog_emissions| **1.60e-04** on the sliced contract view (tolerance 5e-3);
- fp32 export parity vs torch, real traces on `ru_jcuken_default`: sliced **1.14e-04**, argmax
  **100/100** (tolerance 1e-3, and argmax is the binding gate);
- fp16w vs fp32, real traces: sliced **1.16e-01**, argmax **98/100**. This residue is large —
  larger than the ch192 ship model's 2.30e-02 — and it is **disclosed, not hidden**: the binding
  check is the decode, and the decode is unchanged (below).

### 4.3 Validation of the exported artifact

`eval_cyrillic.py`, layout `ru_jcuken_default`, lexicon `app` (the langpack-ru CKDT v2 50 k
trie), preset `1.05, 2.0, 0.2, 0.3734, 0.9882` = the app's `CtcScoringParams.tunedRuCkdt`
verbatim, beam 100. Probe = the untouched Yandex valid-10k, 9,416 default-grid rows,
**eval-only footing**.

| artifact | rows | decoded | in-dict t1 / t3 / t5 | all-rows t1 | greedy |
|---|---|---|---|---|---|
| fp32, confirm half `4708:9416` | 4,708 | 4,240 | **77.92** / 89.50 / 92.00 | 70.18 | 37.62 |
| fp16w, confirm half `4708:9416` | 4,708 | 4,240 | **77.92** / 89.50 / 92.00 | 70.18 | 37.71 |
| fp16w, tune half `0:4708` | 4,708 | 4,231 | 76.88 / 88.63 / 91.52 | 69.10 | 36.54 |
| fp16w, **full set** | 9,416 | 8,471 | **77.41** / 89.07 / 91.76 | 69.64 | 37.13 |

**The exported artifact reproduces the checkpoint's 77.92 confirm-half number exactly**
(`PHASE_J.md` §6.9, `MODEL_COMPARISON.md:469`), and the tune half lands at 76.88 against the
published 76.91 (−0.03). **fp16w is free at the decode**: identical t1/t3/t5 on the confirm
half despite the 1.16e-01 emission residue and two argmax flips per hundred — the two flips
move greedy by +0.09 and the beam not at all.

### 4.4 The preset, the trie and the layout

**Preset.** `tunedRuCkdt` = γ 1.05, **λ 2.0**, β 0.2, γ-prune 0.3734, β-prune 0.9882
(`swipe/ctc/CtcScoringParams.kt:205-210`). λ = 2.0 is not a Russian constant, it is a
**frequency-scale** constant: `LAMBDA_CKDT_SCALE`. The app's CKDT dictionaries store
`freq = 255 − rank`; the `en_enhanced.json` scale wants λ = 4.0. The ru sweep
(`PHASE_J.md` §6.9) and the Latin per-language sweep independently landed on 2.0 for the CKDT
scale from different bases, which is what makes it a property of the scale rather than of
either footing. **λ = 1.1 (E1) is under-tuned for ru and costs ~1.2 top-1** — every ru number
published before 2026-08-11 is at that footing.

**Trie / dictionary.** Russian is **not** a bundled asset. `src/main/assets/dictionaries/`
ships `en_enhanced.json` plus `en/de/es/fr/it/pt/sv_enhanced.bin`, all Latin. Russian exists
only as an **importable langpack**: `scripts/dictionaries/langpack-ru.zip` (533,916 B),
`manifest.json` = `{"code":"ru","name":"Russian","version":2,"wordCount":50000,"hasPrefixBoost":false}`,
`dictionary.bin` = 2,088,865 B, magic `CKDT` v2, lang `ru` — the same container as the bundled
`*_enhanced.bin`. `eval_cyrillic.build_trie` reads exactly this zip, so the campaign's ru
numbers are on the app's own lexicon, not a research one. Projection policy for both targets
and lexicon: lowercase, strip `-` and `'`, ё→е, ъ→ь, and **no Unicode NFD** — NFD decomposes
й into и + breve and silently destroys the alphabet.

**Layout.** `CleverKeys-ML/ctc/layouts/ru_jcuken_default.json` — 31 letters
(`абвгдежзийклмнопрстуфхцчшщыьэюя`, no ё, no ъ), generated from the corpus's own embedded grid,
frame-validated in §3.2 step 2. A 33-letter `ru_jcuken_extra.json` is vendored but **untrained**
(it covers 6.2 % of the corpus).

**MAX_CHILDREN.** Acknowledged and already handled: the trie's per-node child-array clamp at 26
would have thrown on the 27th distinct child, i.e. on the first real Cyrillic trie. It was
removed deliberately, with the constant deleted rather than left unused, and the real bound
moved to a constructor check against the emission head width
(`swipe/ctc/CtcLexiconTrie.kt:86-104, 123-130, 196-206`). **No trie work is blocking Russian.**

### 4.5 Evidence tier — say it exactly this way

> Russian CTC is **val-tier, single-seed, license-clean-synthesis-trained, Yandex-eval-only**.

Unpacked:

- **val-only, permanently.** No sealed Cyrillic test split has ever existed and none can be
  created from a corpus that cannot be licensed. The campaign's sealing discipline
  (test-2400, ledger reads) has **no Cyrillic counterpart** and never will. Russian numbers can
  never be called "test-validated".
- **single seed** (1234). Every other campaign bar is a seed-mean bar; this is not one.
- **no per-language preset sweep beyond λ.** γ, β and the prune constants are E1's, carried.
- **the eval corpus is Yandex.** Permitted (research/held-out eval), but it means the *only*
  real-Russian evidence for this model comes from a source whose data can never enter training
  or the APK.
- **no on-device measurement.** No latency number, no memory number, no instrumented run.

### 4.6 What still has to happen before ru could ship

Not blockers on the ML side — app-side wiring, listed so nobody thinks the export was the last
step:

1. Bundle or gate on the ru langpack (it is an import today, not an asset).
2. Teach `CtcEngineAdapter` to build a **per-script** `ALPHABET` + `buildMappedLayout`
   (the 26-sized arrays and the `'a'..'z'` filter in `letterOf`), keyed off the layout's script.
3. Add `ru` to `CtcLanguageSupport.SUPPORTED` with a **second model asset** and a
   language→model mapping — the adapter currently has exactly one `MODEL_ASSET`.
4. Ship the golden fixture and extend `CtcParityTest`'s fixture↔model↔preset triple to cover a
   second (model, fixture, preset) row.
5. Measure latency and memory on device. The ru model is *half* the ship model's bytes, so the
   expectation is favourable — but expectation is not measurement.

---

## 5. The model inventory — which ONNX is which

**Exactly one CTC ONNX ships in the APK.**

| artifact | ships? | bytes | sha256 | serves | tier |
|---|---|---|---|---|---|
| `src/main/assets/models/ctc_swipe_encoder.onnx` = `ctc/artifacts/phaseM_kd_fresh_w1_s1234_fp16w.onnx` | **YES — the only one** | 3,052,318 | `84718e6ebc8020176f27b9668e50922a765c96838307b640a8db9ab0549e88e5` | en + fr/de/es/it/pt/sv on any a–z-complete Latin layout | **test-validated**, both footings, every seed |
| `ctc/artifacts/ru_synth_ch80_fp16w.onnx` | no — exported 2026-08-18, not wired | 589,406 | `84ac284d4f0d0cb86061df9c557507e1489ab93a75b40885a4431976cee32469` | Russian ЙЦУКЕН (31-letter default grid) | **val-only**, single seed, synth-trained, Yandex-eval-only |
| `ctc/artifacts/ru_synth_ch80.onnx` (fp32 source) | no | 1,142,727 | `d78a9fb9…` | reproducibility / fixture regeneration | same |
| `src/androidTest/assets/ctc_bench/{ch192,ch128,fast_resbn80,fast_resbn72}_s1234.onnx` | no — androidTest only, 11.0 MB | — | — | **nothing.** Superseded Campaign-2 arch-comparison artifacts | historical |
| `phaseIB-ru-real` encoder (`cb8ece6b…`) | **NEVER** | 1,142,727 | — | — | license-blocked research artifact |
| `phaseJ-joint`, `sw2345`, `resbn192i`, `phaseL_*`, `phaseK_*`, `ch128/ch192`, `fast_resbn*` | no | — | — | — | superseded campaign arms |

Golden fixtures:

| fixture | pairs with | preset |
|---|---|---|
| `src/test/resources/ctc/ctc_golden.json` = `src/androidTest/assets/ctc/ctc_golden.json`, sha `2a449c4f2de19505131b396655ae01d3e3c325e40249446ff6e7a40c2b27559c`, 140,462 B | the shipped ONNX (`84718e6e…`, asserted in CI) | `tunedV2` = 0.9 / 4.0 / 0.25 / 0.25 / 0.9882 |
| `ctc/artifacts/ru_synth_ch80_fp16w_golden.json`, sha `041c2072…` | `ru_synth_ch80_fp16w.onnx` (`84ac284d…`) | `tunedRuCkdt` = 1.05 / 2.0 / 0.2 / 0.3734 / 0.9882 |

**No model change is pending.** Phase N is terminal (`CleverKeys-ML` @ `85c0c58`); its 91.25
headline is a different corpus, trie and preset and is explicitly not comparable. Anything you
find that says otherwise — in particular
`docs/audit/remediation-plans/ctc-integration-execution-brief.md`, which still reads
*"Q1 model choice: SUPERSEDED-PENDING — a new model is training"* and names four candidates —
is a pre-decision planning document that nobody bannered. **That file is the single likeliest
source of the "which ONNX?" question.**

---

## 6. Actionable checklist — the audit findings that survive at `9a6ffdd2`

Re-verified against this HEAD (full detail in `CleverKeys-ML/ctc/APP_INTEGRATION_AUDIT.md` §5,
"Re-verification"). Of 23 findings: 12 persist unchanged, 5 are partially addressed, 1
regressed, 0 are fully closed.

### 6.1 HIGH-1 — a latched ONNX-load failure kills swipe typing, and the refactor made it worse

**Persists, and its blast radius grew three ways.** `CtcEngineAdapter.modelOrNull()`
(`:145-177`) still retries three times then latches, and its log line still says
`" — ctc mode disabled this session"` — which is not what happens. Nothing disables the mode.
The decode degrades to `PredictionResult(emptyList(), emptyList())` (`:667-671`) and the shared
pipeline renders that as a cleared bar. The dispatch guard (`InputCoordinator.kt:690`) still
checks only `supportsLayout`. There is no `isModelPermanentlyUnavailable` accessor anywhere in
`src/main`.

What changed since the audit:

1. `Defaults.SWIPE_ENGINE_MODE` went `"neural"` → **`"ctc"`** (`Config.kt:300`). At audit time
   this hurt only opt-in users; now it hurts everyone on defaults.
2. `Mode.fromPref` maps `"neural"`, `"hybrid"` **and every unrecognised value** to `Mode.CTC`
   (`SwipeEngineRouter.kt:85-88`), so users who had explicitly chosen neural are migrated in.
3. Neural is deleted, so there is no second ML engine to land on. Three of the four gates hand
   off to geometric (language `:666-678`, layout `:690-696`, router `SwipeEngineRouter.kt:115`);
   **the model/lexicon gate is now the only way a swipe can reach no engine at all.**

The user's only recourse is finding the Prediction Engine dropdown unaided — and MEDIUM-7 (the
"this engine isn't serving you" card) regressed to *absent* when `NeuralPredictionSection.kt`
was deleted.

**Proposed diff.** The adapter half applies verbatim from the audit; the two `InputCoordinator`
hunks need re-anchoring only.

```diff
--- a/src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt
+++ b/src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt
@@
     /** Failed load attempts so far (audit L5: bounded retry, then latch). */
     private var modelLoadAttempts = 0

+    /**
+     * True once the ONNX session has permanently failed to load for this adapter
+     * ([MAX_MODEL_LOAD_ATTEMPTS] exhausted). Read from the MAIN thread by the dispatcher,
+     * written from the decode thread by [modelOrNull] — hence `@Volatile`.
+     *
+     * The dispatcher MUST consult this before routing a swipe here: without a session the
+     * decode can only ever produce an empty slate, and an empty slate is indistinguishable
+     * from "no candidates" once it reaches the shared pipeline. Falling through to the
+     * geometric engine keeps swipe typing alive, which is the same coverage promise the
+     * layout and language gates already make.
+     */
+    @Volatile
+    private var modelPermanentlyUnavailable = false
+
+    /** See [modelPermanentlyUnavailable]. Safe to call from the main thread. */
+    fun isModelPermanentlyUnavailable(): Boolean = modelPermanentlyUnavailable
+
     private fun modelOrNull(): OnnxCtcEmissionModel? {
@@
         } catch (e: Exception) {
             modelLoadAttempts++
             val latched = modelLoadAttempts >= MAX_MODEL_LOAD_ATTEMPTS
+            if (latched) modelPermanentlyUnavailable = true
             Log.e(
                 TAG,
                 "CTC encoder load failed (attempt $modelLoadAttempts/$MAX_MODEL_LOAD_ATTEMPTS)" +
-                    if (latched) " — ctc mode disabled this session" else " — will retry",
+                    if (latched) " — falling through to the geometric engine for this session"
+                    else " — will retry",
                 e
             )
```

```diff
--- a/src/main/kotlin/tribixbite/cleverkeys/InputCoordinator.kt
+++ b/src/main/kotlin/tribixbite/cleverkeys/InputCoordinator.kt
@@ -686,7 +686,15 @@
         // The router gates on layout METADATA (Latin script); this layout may still lack an
         // a–z key, which yields no CtcLayout and would leave the bar empty. Geometric can
         // decode it, so hand the swipe over rather than degrade coverage. Memoized — the
         // decode below reuses this same geometry build.
-        if (!ctcAdapterOrCreate().supportsLayout(keyboard, params, frameW, frameH)) {
+        //
+        // Same reasoning for a permanently-failed ONNX session: after the bounded retry
+        // budget is spent the adapter can only ever return an empty slate, which the shared
+        // pipeline renders as a cleared bar. Since `ctc` became the DEFAULT mode and the
+        // neural engine was removed, this is the only remaining way a swipe reaches no
+        // engine at all — geometric still works, so use it.
+        val ctc = ctcAdapterOrCreate()
+        if (ctc.isModelPermanentlyUnavailable() ||
+            !ctc.supportsLayout(keyboard, params, frameW, frameH)
+        ) {
             performGeometricSwipeTyping(
```
(and `:702` `ctcAdapterOrCreate().decodeAsync(` → `ctc.decodeAsync(`).

```diff
@@ -753,7 +753,8 @@
                     val ctc = ctcAdapterOrCreate()
-                    val ctcServes = CtcEngineAdapter.supportsLanguage(language) &&
+                    val ctcServes = !ctc.isModelPermanentlyUnavailable() &&
+                        CtcEngineAdapter.supportsLanguage(language) &&
                         ctc.supportsLayout(keyboard, params, frameW, frameH)
```

**Constraint the original audit could not know about**: `CoreImeHygieneDriftTest` (`:208, 251-263,
286-294`) source-scans these blocks for the literal substrings
`CtcEngineAdapter.supportsLanguage(`, `supportsLayout(` and `performGeometricSwipeTyping`, and
asserts their relative index order. The diffs above preserve all three and their ordering — any
further reshaping must too.

### 6.2 The rest, ordered

| # | Status at `9a6ffdd2` | Action |
|---|---|---|
| **HIGH-1** | persists, escalated | the diff above. Only finding where a user loses working functionality. |
| **NEW-1** — `docs/specs/ctc-swipe-engine.md` untouched by the removal | persists | The spec for the now-**default** engine still says "opt-in", "default stays `neural`", "QWERTY→CTC", has a four-row routing table with `neural`/`hybrid` rows, and lists `it, pt, sv \| none \| none`. `git log a7d03bc8~1..HEAD -- docs/specs/ctc-swipe-engine.md` is **empty**. Rewrite it. This belongs beside MEDIUM-3 in the anti-confusion set. |
| **MEDIUM-3** — execution brief unbannered | persists | One paragraph. Highest confusion-removed-per-keystroke in the whole audit (see §5). |
| **HIGH-3** — "dead code / blocked on retrain / no production implementation" KDoc | persists verbatim, all four blocks (`CtcSwipeDecoder.kt:6-15, 35-39, 41`; `CtcEmissions.kt:12-16`; `CtcLayout.kt:12`) | Delete. §1 of this guide is the replacement text. These are the files a "does CTC actually run?" search lands on. |
| **HIGH-2** — `sw2345` misattribution | **fixed in `src/main`**; persists in `SwipeEngineRouterTest.kt:20`, `docs/eval/2026-08-15-ctc-per-language-lambda.md:101,112`, `docs/audit/2026-08-17-neural-vs-ctc-parity.md:619-623` (finding 13 unstruck) | Fix the four. **Widen `CoreImeHygieneDriftTest`'s scan (`:23`) beyond `src/main/kotlin`** — the guard was written because a KDoc rewrite reintroduced these numbers, and it cannot see the copy that is there today. |
| **NEW-2** — `grek_qwerty.xml` mis-tagged `script="latin"` in the shipped tree | new | §2.1. Plus the two tests named there. |
| **MEDIUM-8** — doc/UI language set | QWERTY claims fixed by the removal sweep; the **language set is now stale everywhere** after `9a6ffdd2` | `SUPPORTED` is 7 languages; `README.md:168,243`, `docs/ARCHITECTURE_MASTER.md:226`, `docs/wiki/layouts/multi-language.md:46`, `docs/wiki/specs/typing/swipe-typing-spec.md:41,61`, `docs/wiki/typing/swipe-typing.md:80`, `SwipeTypingSection.kt:41-42`, `memory/todo.md:260-262` and **all 22 `swipe_engine_mode_desc` strings** still say four. Also `ARCHITECTURE_MASTER.md:245` omits the CKDT `.bin` lexicon path and `:237` states λ as a single 4.0 when it is per-scale. |
| **MEDIUM-7** — no "CTC isn't serving your language" feedback | **regressed to absent** — the card died with `NeuralPredictionSection.kt`; `SwipeTypingSection.kt` has no equivalent | Add a card gated on `mode == "ctc" && language !in SUPPORTED`, naming the engine that will actually run. |
| **MEDIUM-5** — settings scope text | half fixed ("Latin layouts", QWERTY gone) | Still hardcoded English with no `stringResource`, no language list, and `:101`'s literal "100 is the validated default" is decoupled from `Defaults.CTC_BEAM_WIDTH`. |
| **MEDIUM-2** — three `settle = true` MemoryProbe marks on the decode thread | persists (`CtcEngineAdapter.kt:425, 429, 467`) | ~720 ms on the first CTC decode in any `LOCAL_BUILD=true` build — i.e. **every instrumented latency measurement is inflated**. Decide before anyone quotes a latency number. |
| **HIGH-4** — the fixture rule's behavioural half never runs | persists in full | No workflow runs `connectedAndroidTest`/`ew-cli`; `ui-testing.yml` is `adb install` + `dumpsys` greps only. `CtcParityTest.kt:38` still hardcodes the asset path instead of deriving it from `CtcEngineAdapter.MODEL_ASSET`; the preset pin still omits `beamWidth` (fixture 32 vs ship 100, 7-word lexicon). At minimum state the device-only caveat in the spec. |
| **MEDIUM-6** — import validation | persists, and **inverted** | `SettingsValidation.kt:97` still validates `neural_beam_width` — a pref of a deleted engine — while `ctc_beam_width` and `swipe_engine_mode` fall to `else -> true`. |
| **MEDIUM-1** — ORT session never closed | persists | `shutdown()` calls only `tasks.shutdown()`; `OnnxCtcEmissionModel.close()` has zero callers; ~3 MB native session leaks per `InputCoordinator` lifecycle. |
| **MEDIUM-4** — 11.0 MB of superseded ONNX in `androidTest`, one called "the ship candidate" | persists | Delete `ctc_bench/`, or add a README saying they are superseded arch-comparison artifacts, rename `fullDecodePath_ch128_beam100_tunedV2` (its constants are E1, not tunedV2), and fix `CtcBenchFixture.kt:9`'s rival fixture citation. |
| **MEDIUM-9** — stale `memory/todo.md` | 1 of 3 fixed | `:178-179` still says "*The CTC engines are demo-only*" — false since 2026-08-08. `:263-266` P2 item is done on both halves. The **Russian section is now correct** and leads with the honest framing. New staleness at `:260-262` (it/pt/sv called dead). |
| **LOW-1..L-4, L-6..L-10** | all persist | Cleanup; ride along with whatever touches those files. L-5's `Mode.NEURAL` contrast is **moot** (neural is gone); the `route` overload divergence itself remains, pinned only for the string form. |
| **NEW-3** | new, minor | `InputCoordinator.kt:525` KDoc still documents `beginSwipeCapture`'s `engine` param as `ENGINE_NEURAL`/`ENGINE_GEOMETRIC`; both call sites pass `ENGINE_CTC` or `ENGINE_GEOMETRIC`. (`SwipeMLData.ENGINE_NEURAL` is correctly retained for reading historical exports.) |

---

## 7. What NOT to do

1. **No Yandex data in any training run or any shipped artifact — ever.** Eval-only, held-out,
   research footing. If a model's training pipeline touched a Yandex row, it cannot ship, no
   matter how good it is. `ru-real` at 89.64 is the standing example: better than everything
   else and permanently unusable.
2. **No FUTO model weights and no FUTO model outputs** in anything we train or ship. The
   corpus and the decode-algorithm lineage are the permitted inheritance and `NOTICE:46-64`
   states that carefully and correctly. Do not "improve" that wording; do not add a FUTO
   teacher.
3. **Respect the evidence tiers in prose.** "test-validated" means decoded on a sealed split
   whose read was spent from the ledger. "val-only" means everything else. Russian is val-only
   permanently. A val-only finalist's alt-layout number must never be quoted as the ship
   model's — that is exactly how HIGH-2 happened, and how it happened *twice*.
4. **Never route a non-Latin script to CTC without all three of** a per-script model, a
   per-script trie on the app's own lexicon at the app's own frequency scale, and a golden
   fixture at the preset that will actually ship. Two of three is a silently wrong decode, not
   a partial feature.
5. **Do not quote a latency number measured in a `LOCAL_BUILD=true` build** until MEDIUM-2 is
   resolved — the settle probes add ~720 ms to the first decode.
6. **Do not "fix" `CtcEngineAdapter` to use `CtcFeaturizer.normalizeRawX/Y`.** The shipped
   encoder was trained on letter-box normalization, not FUTO's 4/3 device frame; the adapter
   warns about this at `:51` and the module's own normalizers are production-dead (LOW-3).
7. **Do not add a per-node cap back to the trie.** The `MAX_CHILDREN = 26` clamp was removed on
   purpose (§1.2); the real bound is the constructor's alphabet-vs-head-width check.
8. **Do not assume `srcs/layouts/` ships.** It does not; `src/main/layouts/` does. A fix applied
   to the wrong one looks landed and is not (§2.1).
