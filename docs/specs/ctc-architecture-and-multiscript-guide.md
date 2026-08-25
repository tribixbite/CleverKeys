# CTC architecture and the multi-script question — the definitive guide

**Status**: reference. **Written**: 2026-08-18. **Revised**: 2026-08-20 (**generator v3 /
generation 4**, the sealed upper bound, and the app at `d717bda7`).
**App state described**: `d717bda7` — `ctc` is the DEFAULT `swipe_engine_mode`, seven served
languages, the neural engine deleted, and the 2026-08-20 remediation wave landed.
**Training-side source of truth**: `CleverKeys-ML` @ `ctc/` — `MODELS_TABLE.md` §4.17,
**`PHASE_Q.md` (v3 generator, §7.7 artifact registry; §9.7 the λ sweep)**,
`PHASE_P.md` (v2 generator, §6.1/§8.4), `PHASE_O.md`, `PHASE_I_DATA.md` §4–§6,
`PHASE_J.md` §6.9, `ALT_LAYOUT_EVAL.md`, `YANDEX_LICENSE_RESEARCH.md`,
`APP_INTEGRATION_AUDIT.md`.

> **Mirror status (app repo).** This is the app-repo copy, refreshed 2026-08-25 by copying
> `CleverKeys-ML/ctc/ctc-architecture-and-multiscript-guide.md` verbatim (closing
> `APP_WIRING_CHECKLIST.md` §3 — the copy was two model generations stale). The ML copy remains
> the authority for training-side content; on divergence, re-copy rather than merge.
>
> **App-state addendum — the app has moved past the `d717bda7` state described below.** At app
> HEAD `9f3b6b94` (2026-08-25, post the 2026-08-23 remediation wave and its verification,
> `docs/audit/2026-08-25-remediation-verification.md`):
>
> - The curated emulator gate (`.github/scripts/emulator-ci.sh:124`) now runs **five** classes
>   (CTC multi-language, geometric oracle, crash guard, a11y, Keystore) — still **not**
>   `CtcEmissionModelParityTest`, so §5's "CI checks the header sha, not the emission matrices"
>   remains true.
> - `CtcFuzzyRescue` (bounded main-dictionary rescue) now runs inside every per-lexicon decode
>   (`CtcEngineAdapter.decodeLexicon`). Its index filter is `'a'..'z'`-only — a wiring hazard
>   for non-Latin lexicons (see the 2026-08-25 wiring plan §A6).
> - Secondary-language dual decode exists: one encoder pass, per-language beam decode,
>   rank-only merge (`swipe/ctc/CtcRankMerger`), a two-slot language-keyed trie LRU.
> - `CtcAzProjection` gained deterministic expansions `ß→ss, œ→oe, æ→ae, ø→o`; the fr shape is
>   now 40,000 records / 0 untypeable / 37,958 surfaces / 2,042 collisions (the §2/§3 counts
>   below predate this).
> - Version metadata is 1.6.0 with drift tests pinning `CtcLanguageSupport.SUPPORTED` =
>   en/fr/de/es/it/pt/sv — adding a script language will trip `ReleaseMetadataDriftTest` by
>   design.
>
> App-side wiring work is planned in `docs/plans/2026-08-25-ctc-multiscript-wiring-plan.md`.

This document exists to kill four recurring confusions permanently:

1. "The CTC model's alphabet is hardcoded a–z." — **The model has no alphabet at all.**
2. "Non-Latin needs a per-script model; is that tractable? No." — **Per-script models are
   needed and they cost ~30 minutes of GPU per seed.** Russian is done; the artifacts are
   named in §4.
3. "37 layouts don't declare a script." — **Two do not, and neither is a letter layout.**
   The real gap was three *mis-declared* or letter-incomplete layouts; one of the three was a
   genuine bug and is now fixed and guarded (§2.1).
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
   `SUPPORTED` at `d717bda7` is `en` (EN_JSON scale) and `fr, de, es, it, pt, sv` (CKDT scale);
   `it, pt, sv` are marked `PROVISIONAL`.
3. **Alphabet completeness** — `buildMappedLayout` returns null on the first missing a–z
   letter, `supportsLayout` returns false, and the dispatcher hands the swipe to geometric
   *before any CTC work starts*. No crash, no empty bar, no garbage decode.

A **fourth** condition joined these on 2026-08-20 (`ad18e0e3`, closing audit HIGH-1): if the
ONNX session has permanently failed to load, `CtcEngineAdapter.isModelPermanentlyUnavailable()`
is true and both the dispatcher (`InputCoordinator.kt:725-733`) and the prewarm (`:793-796`)
route to geometric. Before that fix a dead session produced an empty slate, which the shared
pipeline rendered as a cleared bar — indistinguishable from a bad gesture. It matters for
multi-script work because **per-script routing removes gate 1**, so the remaining gates carry
more weight than they did.

**Russian cannot reach CTC in any state**, and that is correct, because no Russian model,
trie, or fixture is wired into the app. Gate 2 also covers the inverse case (`ru` language on a
QWERTY layout): geometric, not CTC.

### 2.1 The "undeclared script" question — measured, not assumed

Measured on `src/main/layouts/` at `d717bda7` (86 XML files; this is the tree
`copyLayoutDefinitions` ships — `srcs/layouts/` is **not** referenced by any build task and has
been divergent before):

| bucket | count | consequence |
|---|---|---|
| `script="latin"` **and** a–z-complete | **46** | route CTC — the intended set |
| `script="latin"` but a–z-incomplete | **2** | router lets them past, the alphabet gate stops them → geometric |
| non-Latin declared (15 distinct scripts) | **36** | geometric at gate 1 |
| no `script` attribute at all | **2** | `numeric.xml`, `pin.xml` — not letter layouts, correctly geometric |

There is **no population of 37 undeclared layouts.** The two latin-declared-but-incomplete files
are `latn_qwerty_az.xml` and `latn_qwerty_tly.xml`, both genuinely lacking `w`, both correctly
declared — geometric is the right answer for them.

**The third one was a real bug and is now fixed.** `src/main/layouts/grek_qwerty.xml` declared
`script="latin"` while its sibling `srcs/layouts/grek_qwerty.xml` had been corrected to `greek`
in `6af11da7` ("closes neural-swipe allowlist leak") — a tree no build task reads, so the fix
looked landed and was not. `6f30d60f` (2026-08-20) corrected the shipped file; both copies now
read `script="greek"` and are byte-identical.

It is also **guarded**, which is the part worth keeping. `LayoutScriptDeclarationTest` walks the
real shipped tree (`File("src/main/layouts")`) and asserts `script="latin"` ⟺ a–z-complete
**bidirectionally** — it catches a Greek layout tagged Latin *and* an a–z-complete layout denied
CTC for no reason — with `latn_qwerty_az` / `latn_qwerty_tly` as named exceptions, plus a by-name
pin on Greek and a pin on the no-script set. Writing it turned up something worth recording:
**the layout tree uses two schemas for the centre key value**, `<key c="q" ne="1"/>` and
`<key key0="p" key1="…"/>`, and both are live. Matching only one makes ~40 layouts look like they
contain no letters, and the header comment in every layout file documents `key0` while
`latn_qwerty_us` uses `c` — so the comment is not a reliable guide.

**Still owed** (audit LOW-9, half done): the router-level negative exists
(`SwipeEngineRouterTest` pins "the Greek QWERTY trap: script wins over the QWERTY-shaped name"),
but nothing asserts `CtcEngineAdapter.supportsLayout(...) == false` for a Cyrillic or Greek
`KeyboardData`. Gate 3 is untested in the negative direction — which becomes load-bearing the
moment per-script routing removes gate 1.

---

## 3. Non-Latin: per-script models are required, and they are cheap

### 3.1 Both halves of the claim

**Required.** A model trained on Latin-arrangement geometry with English lexicon statistics does
not zero-shot another script. The geometry is a model input, but the *motor statistics* and the
*implicit character-transition prior the encoder learns* are trained. Do not route a non-Latin
layout at CTC and hope.

**Cheap.** The synthesis pipeline generalizes to **any** script given only (i) a word list and
(ii) the layout geometry. No data collection, no corpus licensing, no human subjects. Measured
cost for Russian: 94 k steps of `resbn:80` — well under an hour of a single RTX 5080 — plus a few
minutes to export and evaluate. Generation 4 adds a one-off generator training (~70 min) and
~30 min of sampling per million-row cache, both amortized across every script (the generator's
conditioning is pure geometry, so one MIT-trained generator serves all six).

**Proven, not projected.** `phaseQ-ru-v3` was trained on 1,000,000 rows in which **no real
Cyrillic sample appears anywhere** (checkpoint selection ran on a synthetic val split too), and
it decodes **real** Russian swipes at in-dict top-1 **85.07** with greedy **65.66** (§4.3). Its
v2 predecessor read 79.73 / 56.12 and its v1 predecessor 77.41 / 37.07 on the same rows; the
+5.34 is paired at p = 5.4e-53. All three are above the shipped geometric engine's cross-layout
anchors (71–77), and 85.07 is within **3.6 points of a model trained on a million real Russian
swipes** (§3.3).

**And the cheaper option first, because this is where the accuracy actually is.** The shipped
**English** model, zero-shot on real Russian with nothing but the right layout and the right
trie, reads **76.32** in-dict top-1 (`PHASE_O.md` §2.1). The purpose-built generation-4 ru model
adds **+8.75** on top of that — generation 2 added only +3.41, so the model asset now carries
materially more of the total than the "wiring first" staging argument assumed when it was
written. So of the ~85 points available, the app wiring — per-script alphabet,
routing, trie, projection — delivers 76.3 of them **before any model ships**, and the model
delivers the last three. If the work has to be staged, stage the wiring first; a script with the
wiring and no model is already at the geometric engine's level or above.

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
   training run gets wasted on a squashed frame. Endpoint statistics are necessary and **badly
   insufficient**: they are the *least* discriminative view a real-vs-synthetic classifier has
   (0.663 against the speed profile's 0.904), which is exactly why v1 passed its only gate while
   carrying a KS-0.60 speed defect. Gate on the kinematics too — `synth_gap_audit.py --stage
   gates` is the standing battery, and its cheapest high-value metric is **lag-1 speed
   autocorrelation**, the largest single-statistic real-vs-synth gap found anywhere.
3. **Synthesize.** `script_synth.py --code <script>` — the generic residual transplant, at
   **generator v2** (`cyrillic_synth.py` remains as the historical record of the ru v1 run).
   English human traces are the donor pool; the correspondence is geometric
   (`layout_aug.warp_path` verbatim through per-vertex virtual indices) and **letter identity
   never enters**. Five stages, all measured into place by `SYNTH_V2_DESIGN.md` /
   `SYNTH_V2_RESEARCH_AUDIT.md` and gated in `PHASE_P.md`:
   * **S0 word draw = wordfreq token mass**, not `255 − rank`. The rank scale is a 255:1
     compression of frequencies that span 10⁵:1, and it produced a corpus with **3.3 % ≤3-letter
     words against real usage's 35.6 %**. Accumulate each wordfreq *token's* mass into **its
     projection** — querying `word_frequency` with the projected form returns zero for 90 % of
     the Greek pack, because the projection strips accents and restores final ς.
   * **S1/S2 geometry-matched donor draw**: index by (vertex count, log polyline length), take
     k = 16 reservoir candidates and pick the one minimising `Σ_seg |log(L_dst/L_src)|`. Takes
     the per-segment stretch p95 from 3.63 to 1.72.
   * **S4 vertex-aligned per-segment re-timing** at α = 0.5. v1 let the arc remap scale the
     donor's sample spacing by the segment length ratio, which multiplies the implicit speed
     profile by that ratio and never re-times it — the single largest measured defect
     (step_cv KS 0.60, peak per-step speed 3.2× real). Copy the donor's *within-segment*
     arc-progress so its dwells land on the **target's** vertices, and reallocate the sample
     budget across segments by `m_k ∝ n_k·ρ_k^α`. α is not tuned: the within-trace regression of
     per-segment time share on ideal-length share reads 0.460 (HWS) / 0.493 (FUTO) / 0.447 (real
     ru), an isochrony invariant that transfers across scripts, so it is fit on MIT English.
   * **S5 acquisition-bandwidth matching**: predict a duration from the donor's own
     (`T_target = T_donor·(L_dst/L_src)^0.262`, the exponent fit on MIT English only) and
     re-featurize through the real 60 Hz chain. Half the residual "synthetic traces are jagged"
     gap is a sampling artefact — fast target-script traces are *upsampled* to 64 points while
     slower English donors are *downsampled* and keep their jitter — not motor behaviour.
   * **Never fit any generator parameter against the validator's statistics.** Tempo, duration,
     start-dwell and the length mix are all fit on MIT English and only *checked* on Russian.
   `--generator v1` reproduces the old mechanism bit-exactly for paired ablations.
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
7. **Check the lexicon against the 32-frame budget.** The exported encoder emits a fixed
   `log_emissions [1,32,65]` and a CTC path spends one frame per character **plus a separating
   blank between two identical adjacent characters**, so a word is decodable iff
   `length + adjacent-duplicate-pairs ≤ 32`. The app added `CtcDecodableLength` and a
   custom-word warning for this on 2026-08-20 (`2d080c7d`), with a test asserting every 20+
   character word in `en_enhanced.json` clears it. **No script lexicon has been checked** — the
   v2 word draw is wordfreq token mass with no length ceiling, and Greek and Ukrainian both
   carry long inflected forms. It is one loop over the trie's word list; run it before training,
   because a word over budget is silently unemittable rather than an error.
8. **Measure on real data if any exists** — as a held-out *eval-only* probe. Synthesis is the
   training story; real data is how you find out whether the synthesis worked.

### 3.3 SYNTH v3 — the generator is a learned model now, not a transplant

**Generation 4 (`*_synth_v3_ch80*`) is what to deploy.** Full record `PHASE_Q.md`;
this is the section that matters if you are wiring bytes rather than training.

**What the generator is.** SYNTH v3 is a **conditional rectified-flow (OT flow-matching)
model over the residual field** of a swipe: for a target word it builds the ideal
arc-uniform polyline `R` on that script's layout, and learns the conditional density of
`x₁ = (P − R)/σ` — everything the trace *is* beyond its ideal geometry: dwell, overshoot,
corner-cutting, jitter, tempo shape, the 60 Hz acquisition signature. 1.94 M params, a
1-D dilated residual conv net over the 64-sample axis with FiLM-injected time embedding,
32 Euler steps at sampling. Conditioning is **pure geometry** (polyline, tangent, arc
position, vertex distance, turn angle, length), so cross-script transfer is by
construction — the same property the v2 warp had, without the donors.

Three consequences that matter downstream:

* **there are no donors at generation time.** v2's whole scaffolding — donor draw, warp,
  re-timing, bandwidth — is gone; a conditional density *given* geometry has nothing to
  re-time. What survives from v2 is the draw policy (`script_synth.token_mass`, the
  wordfreq draw), the npz schema and the split seeds, so every downstream driver consumes
  v3 caches unchanged;
* **one repair round was spent and is on the record** (`PHASE_Q.md` §7.1a): a continuous
  flow emits exact zero-length steps with probability zero, and real featurized traces are
  full of them (a stationary finger emits identical samples). The fix is an **acquisition
  imprint** at sampling time — a duration drawn from the generator's own corpus' law,
  re-featurized through the real 60 Hz chain, then a dwell snap with ε fit so generated
  `dup_frac` matches the training bank's own. No target-script statistic enters it;
* **throughput regressed**, 1,141 CPU rows/s (v2) → 541 GPU rows/s (v3, 32 NFE). Offline
  only, ~30 min per million-row cache.

**What it bought, on the only real probe that exists.** Same 8,471 in-dict Yandex rows,
CKDT preset, per-row paired:

| arm | training data | in-dict t1 | greedy |
|---|---|---|---|
| `phaseIB-ru-real` re-decoded at this preset | 1 M **real** Yandex rows (unshippable) | 88.69 | 75.23 |
| **`phaseQ-ru-v3`** — generator **v3** | 1 M synthetic rows, zero real | **85.07** | **65.66** |
| `phaseP-ru-v2full` — generator **v2** | 1 M synthetic rows, zero real | 79.73 | 56.12 |
| `phaseIB-ru-synth` — generator **v1** | 1 M synthetic rows, zero real | 77.42 | 37.07 |

v3 vs v2 is **+5.34 real top-1** (exact McNemar p = 5.4e-53) and **+9.54 greedy**, five
times the pre-registered +1.0 ship bar, with both length strata significant (≤3 +3.38,
≥4 +6.57) — and the ≤3 stratum clears the 86.4 corollary v2 itself missed.

**The one-line decomposition, and why it inverts the Phase-P ledger.** A sealed research
twin — the same architecture trained on 1 M *real* Russian swipes, permanently unshippable
(§0 below) — puts the upper bound at **U = 85.95**:

> Of the 8.96-point v2→ceiling gap: the **English-trained** learned generator closes
> **5.34** (86 % of the 6.22 that any generator of this family could reach), in-domain
> target-script data would add only **0.89** (and **0.31, p = 0.47** on ≥4-letter words —
> indistinguishable), and **generation itself still costs 2.74**.

Phase P priced the donor **population** as the dominant unreachable residual; that was
true *for a transplant*, and a conditional density fixes most of it from English data
alone. **Real data is now worth ~3.6 top-1 points, not ~10 and not ~13** — and the binding
constraint on synthetic-data quality is generator fidelity, not data domain. Anyone
budgeting "collect target-script swipes" should price it against 0.89, not against the old
10-point gap.

**The licence seal — non-negotiable, and the reason the number above is quotable at all.**
The twin generator, its samples, its decoder, its onnx and its dumps are Yandex-derived.
They carry a `RESEARCH_ONLY` suffix, live under `~/ctc-train/research_only/`, are untracked,
and never enter `ctc/artifacts/`, the registry, `exports/`, an app asset or a donor bank;
`synth_v3.py` enforces the path prefix mechanically when the corpus is flagged
`--research-yandex` and stamps the licence into every provenance blob. `YANDEX_LICENSE_RESEARCH.md`
§8.1 draws the line at the training-pipeline boundary: the ст. 1335.1 научные limb covers
local research training for **measurement**, and covers nothing that ships, because every
available permission theory is non-commercial and GPL-3.0 is not. **Nothing in
`ctc/artifacts/` derives from Yandex**, and there is no laundering path — a shipping v3
retrains from MIT data.

**Evidence tiers, unchanged in kind by v3.** ru is real-validated (Yandex eval-only,
val-tier permanently — no Cyrillic test split exists or can); the other five remain
**synthesis-holdout-only, calibrated against ru rather than measured on their own script**.
What v3 changed there is the *margin* against the fixed English zero-shot controls on the
same rows, which **widened** on every script (el +6.11 → +7.01, uk +5.09 → +13.02,
bg +5.47 → +10.05, mk +5.23 → +5.00, he +7.92 → +16.05): the v3 distribution is
simultaneously easier for its own model and harder for an English zero-shot, which is the
direction ru's real probe independently verified. Levels are generator-relative and are
still not accuracy figures for those languages.

**λ was swept and is unchanged** (`PHASE_Q.md` §9.7): on the ru probe's tune half, in-dict
t1 is **monotone decreasing** in λ across {1.1 … 4.0}, so the optimum is off-grid *below*
E1's 1.1 and the pre-registered interior-optimum rule refused adoption. `tunedRuCkdt` stays
at λ = 2.0, and its **measured, unconfirmed shortfall is −0.63 t1** on that half. The
mechanism is the one Phase P predicted in words: λ = 2.0 was fitted to a greedy-37 model
whose beam did the work, and a greedy-66 model wants the lexicon prior turned down.

#### 3.3a What generation 1→2 established, kept because the numbers are still cited

v2 (residual transplant from English donors) was +2.31 real top-1 over v1 (p = 2.6e-09) and
+19.05 real greedy. v1's greedy of 37 was read as "English-magnitude start noise on a denser
board"; it was mostly a speed profile that made synthetic traces 90 % separable from real
ones. The gain landed on long words (≥4: +4.22, p = 3.6e-17). The English→English control —
same generator, same script, disjoint halves of one corpus — closed 84 % of the gap to a
measured 0.50 floor while the Russian arm closed 36 %, which is what priced the donor
population as a transplant's unreachable term. **All of that stands as measured; §3.3 is why
it is no longer the binding term.**

---

## 4. Russian — DELIVERED, as the worked example

### 4.1 What is shippable and what is not

| model | in-dict t1 | status |
|---|---|---|
| `phaseIB-ru-real` (1 M real Yandex rows) | 89.64 (88.69 re-decoded at the CKDT preset) | **LICENSE-BLOCKED FOREVER.** Not shippable in any form, at any time. The ceiling, quotable only as a ceiling. |
| `phaseQ-ru-yxgen_RESEARCH_ONLY` (v3 twin trained on 1 M real Yandex rows) | 85.95 | **SEALED, PERMANENTLY UNSHIPPABLE.** Not an artifact — a *measurement*: the upper bound U on what any generator of this family could reach with in-domain data (§3.3). Untracked, `RESEARCH_ONLY`-marked, never in `ctc/artifacts/`. |
| `phaseJ-joint` (single en+ru model) | 78.23 confirm-half @ λ 2.0 | **REJECTED.** Its data is license-clean, but it cost **−0.42 en top-1** against a 0.3 tolerance and was not adopted. Its ru lead over the bar-holder is +0.31 on the confirm half — inside one binomial SE (±0.64 at n = 4,240) — and it *loses* t3 and t5 on that same half. |
| `phaseIB-ru-synth` = `ru_synth_ch80` (generator v1) | 77.41 full-set / 77.92 confirm-half @ λ 2.0 | **SUPERSEDED.** |
| `phaseP-ru-v2full` = `ru_synth_v2_ch80` (generator v2, full donor pool) | 79.73 full-set @ λ 2.0, greedy 56.12 | **SUPERSEDED** by generation 4 at matched everything — same recipe, same seed, same rows, only the training distribution differs. |
| **`phaseQ-ru-v3`** = **`ru_synth_v3_ch80`** (generator **v3**, learned) | **85.07** full-set @ λ 2.0, greedy **65.66** | **THE SHIPPABLE ONE.** Trained purely on the learned MIT-data generator; zero Yandex rows anywhere in its pipeline. G5-Q PASS against a pre-registered ≥ 80.73 bar, cleared five times over (+5.34, p = 5.4e-53). |
| the shipped **English** model, zero-shot on ru | 76.32 | not a Russian model at all — the baseline the wiring alone reaches (§3.1). |

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

Committed in `CleverKeys-ML/ctc/artifacts/`. **Generation 4 (`*_synth_v3_ch80*`) supersedes
every earlier generation for deployment on all six scripts** (`PHASE_Q.md` §7.7); the older
bytes stay because every published number was measured on them.

Generation 4 — **what to wire**, all six scripts:

| file | bytes | sha256 |
|---|---|---|
| **`ru_synth_v3_ch80.onnx`** (fp32) | 1,142,727 | `b4ad3aab1a7d15dc94c6e69a459991f76e95e2828a12abe1594a377c80e52ac0` |
| **`ru_synth_v3_ch80_fp16w.onnx`** (**ship bytes**) | 589,406 | `8fffa75c722eb61e9e8c80d919fbca3e73eb698ebe3e3909cb766b3b8489962c` |
| **`ru_synth_v3_ch80_fp16w_golden.json`** (fixture) | 160,384 | `2e8de3c5a15e5874366f44f725aeec2eb72befd89b503d4b24b8b4a8d82fdde5` |
| `el_synth_v3_ch80.onnx` / `_fp16w.onnx` / `_fp16w_golden.json` | 1,142,727 / 589,406 / 144,427 | `abc86626d34c287beee2ac1b1a67795763a01a15407d6a7e2dae3522ac4bb2c8` / `7083794c501566f411b1f81495ba1f7f3df273c3eb58f6ee635caf168a4f8c3d` / `d08d5501961e971db2ca120f6ee868b7b67ed37e34b6412dddbc7f7116de5753` |
| `uk_synth_v3_ch80.onnx` / `_fp16w.onnx` / `_fp16w_golden.json` | 1,142,727 / 589,406 / 155,068 | `7fe52e7dd3f76c03fa92bfb575ad6fa3948ed58af22d21ca6c6823c106d7bb82` / `af9959a8954961eec117808371937cb26152c82a82cad0fc6a0ac06fd695db76` / `93602db1200a3b37ef11570d4f4ee3afdad2a45b0ca4f857a784728cdbb5cc98` |
| `bg_synth_v3_ch80.onnx` / `_fp16w.onnx` / `_fp16w_golden.json` | 1,142,727 / 589,406 / 154,835 | `c41e9ed8e7a014e85f95705eff7ddef494b3cd4be5d5633e4dfc5078e0849bb3` / `119d42f70cc763336f9a86efdc5ae4f562ba4a28179c2d386026bef674c039a7` / `f776ea03ab675ff6b741a3297c4f88b11f7af2cb183ce7b2604f082ed8420b9d` |
| `mk_synth_v3_ch80.onnx` / `_fp16w.onnx` / `_fp16w_golden.json` | 1,142,727 / 589,406 / 160,674 | `812909e9ee9fb1b9b8a2bb39a668594528c071a4e50b840c4f02b28a2e4560f1` / `4e371d967bf24f260eb539848ead7860f56dc904f6bfc74235879b76e81ae022` / `015c9bae7e25a97b0ac8bd6062bb58376caaa3aca99c138d0d531ff1887e0ccf` |
| `he_synth_v3_ch80.onnx` / `_fp16w.onnx` / `_fp16w_golden.json` | 1,142,727 / 589,406 / 140,129 | `e79357b95cd0f6707970f46c85bdabcc0d0fbd43c104e03e71965b7716b65c7a` / `a382371363653fbe7c806482035aa9e27968b9c098591910d24f9f1ba43212c7` / `b29a99f4ac2c4f82547d040131ea48771f2791817287de6e3f9ec52fc9758ad9` |

Superseded ru generations, kept for the numbers measured on them:

| file | bytes | sha256 |
|---|---|---|
| `ru_synth_v2_ch80.onnx` (fp32, generator v2) | 1,142,727 | `763190f9bc9854a3183f10d7dba7d8e1de1c101812b5958ee9bdbb403b93089b` |
| `ru_synth_v3_ch80_fp16w.onnx` | 589,406 | `9004befb6ff07b744c65d3c13481539e758ebe10d4f47cbeffe68d39d12b0e52` |
| `ru_synth_v2_ch80_fp16w_golden.json` | 160,282 | `a5ed2b9f62843d085779f5ab7457e6608f5c47e8994c224146ebdaf32fcdb82d` |
| `ru_synth_ch80.onnx` (fp32, generator v1) | 1,142,727 | `d78a9fb9f8e170595a7714220cf5fd9dfc2324935900aec6cb6d7a2ec1a36666` |
| `ru_synth_ch80_fp16w.onnx` | 589,406 | `84ac284d4f0d0cb86061df9c557507e1489ab93a75b40885a4431976cee32469` |
| `ru_synth_ch80_fp16w_golden.json` | 160,876 | `041c20722a957d1341108eb969dc677a123363011094ad05b36fdc1baa1050b0` |

The v3 source checkpoint is `~/ctc-train/ckpt/phaseQ-ru-v3/best.pt`. **The app-side contract is
unchanged across all four generations**: same alphabet string, same slot order, same preset, same
fixture shape, same 1,142,727 / 589,406 byte graphs — v3 changes the *training distribution*, not
the contract, so swapping generations is a model-and-fixture swap and nothing else. The five
other scripts now have four generations each (`*_synth_ch80*` v1 → `*_synth_v2_ch80*` →
`*_synth_v2full_ch80*` → `*_synth_v3_ch80*`); ru has three, because it was always full-pool.

Architecture, identical across every generation: `resbn:80`, dil `1,2,4,8`, embed_hid 96,
feat_v1, `t_out` 32, 279,346 params, 94,000-step schedule, batch 256, lr 3e-3, wd 0.01,
warmup 1,000, coupled affine sampler, no layout-alt, greedy checkpoint selection, seed 1234.
The v1 fp32 re-export was **byte-identical** to the artifact its 2026-08-09 training run
produced — a free determinism check on the whole export path.

Export gates for the **generation-4 bytes**, all passed (`PHASE_Q.md` §7.7):

- every fp32 export cleared at the **default 1e-3** tolerance with **100/100 argmax** on the
  sliced contract view; ru read **7.63e-05**, and he's 3.57e-04 sits inside the historical
  envelope — **the v2-era he parity flag does not recur in this generation**;
- fp16w decode cost is **≤ 0.01 t1 on every script** (ru 85.07 → 85.08). The large sliced
  emission residue that generation 2 disclosed is a property of fp16 weight rounding, not of a
  generation; the binding check is and remains the decode.

(The v2 numbers, for anyone reading a Phase-P document: BN fold 1.20e-04, fp32 sliced 9.92e-05
argmax 100/100, fp16w sliced 1.06e-01 argmax 93/100, decode cost +0.02. The v1 numbers: BN fold
1.60e-04, fp32 sliced 1.14e-04 argmax 100/100, fp16w sliced 1.16e-01 argmax 98/100.)

### 4.3 Validation of the exported artifact

`eval_cyrillic.py` / `eval_script.py`, layout `ru_jcuken_default`, lexicon `app` (the langpack-ru
CKDT v2 50 k trie), preset `1.05, 2.0, 0.2, 0.3734, 0.9882` = the app's
`CtcScoringParams.tunedRuCkdt` verbatim, beam 100. Probe = the untouched Yandex valid-10k, all
9,416 default-grid rows, 8,471 in-dict, **eval-only footing**.

The generation-4 gate (`PHASE_Q.md` §7.3, G5-Q — the only gate that decided shipping), with
every earlier generation on the identical rows:

| arm | training cache | in-dict t1 | ≤3 | ≥4 | greedy | t3 | t5 |
|---|---|---|---|---|---|---|---|
| `ru_synth_ch80` — the v1 baseline | v1, full donor pool | 77.42 | 86.47 | 71.70 | 37.07 | 89.06 | 91.76 |
| `phaseP-ru-v2full` = `ru_synth_v2_ch80` | v2, full donor pool | 79.73 | 85.77 | 75.92 | 56.12 | 90.77 | 93.26 |
| **`phaseQ-ru-v3` = `ru_synth_v3_ch80` — SHIP** | **v3 learned**, `cache_ru_v3` | **85.07** | **89.15** | **82.49** | **65.66** | **93.35** | **95.16** |
| *`phaseQ-ru-yxgen_RESEARCH_ONLY`* — the sealed twin | *v3 trained on 1 M real Yandex rows* | *85.95* | *90.95* | *82.79* | *69.72* | *93.74* | *95.37* |
| *`phaseIB-ru-real`* re-decoded at this preset | *1 M real Yandex rows* | *88.69* | *93.90* | *85.39* | *75.23* | *95.28* | *96.82* |

| paired comparison (exact McNemar, n = 8,471) | Δ t1 | p |
|---|---|---|
| **v3 vs v2full** | **+5.34** | **5.4e-53** |
| v3 vs v2full, greedy | +9.54 | 1.4e-100 |
| v3 vs v2full, ≤3 stratum | +3.38 | 1.5e-11 |
| v3 vs v2full, ≥4 stratum | +6.57 | 8.8e-44 |
| U (sealed twin) vs v3 — *the whole in-domain-data premium* | *+0.89* | *0.0025* |
| U vs v3 on ≥4-letter words | *+0.31* | *0.47 — indistinguishable* |
| ceiling vs U — *what generation itself costs* | *+2.74* | *5.6e-23* |

**G5-Q PASS** — 85.07 against a pre-registered bar of 80.73, cleared five times over. The ≤3
corollary v2 **missed** (85.77 against 86.4) is now **cleared at 89.15**. Two qualifications
carried from `PHASE_Q.md` §7.2/§7.6 and not rounded away: the generator battery **missed 2 of
13 instruments** — step_cv 0.165 against a 0.15 bar and MLP-speed 0.7640 against v2's 0.7412 —
both on the *same* axis, the speed-marginal texture of a model whose tempo is English, and the
§2 proceed-rule deviation that miss triggered is disclosed rather than laundered. And the
UCL₉₅ ≤ 0.60 separability target is **still not met**: v3 is distinguishable from real Russian.

**Export gates** for the v3 bytes: fp32-vs-torch **7.63e-05** with argmax **100/100** on real
traces at the default 1e-3 tolerance, and the fp16w decode costs **+0.01 t1** (85.07 → 85.08).

Earlier generations, for anyone reading an older document: v2 fp16w full set 79.75; v1 fp16w
full set **77.41** / 89.07 / 91.76, confirm half 77.92, tune half 76.88.

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
- **three seeds** (1234/4321/7777) as of the Phase-Q closing round — the ru path is
  finally on the same seed-mean footing as every other campaign bar. Real-probe
  **85.30 ± 0.207**, and the shipped s1234 bytes are the *lowest* of the three
  (`PHASE_Q.md` §10.2). What is still single-seed: the **generator** itself, the
  synthesis caches, the sealed upper bound U, and the real-trained ceiling.
- **no per-language preset sweep beyond λ**, and **λ = 2.0 is now measured to be off-peak**.
  It was tuned in `PHASE_J.md` §6.9 against a *weak-emission* model (greedy 37); the generation-4
  model reads greedy 66 and leans on the prior far less. `PHASE_Q.md` §9.7 swept
  {1.1, 1.5, 2.0, 2.5, 3.0, 4.0} on the probe's tune half and found t1 **monotone decreasing** —
  the optimum is off-grid *below* 1.1, so the pre-registered interior-optimum rule refused
  adoption and the confirm half was not spent. The shipped constant is unchanged and carries a
  **measured, unconfirmed −0.63 t1 shortfall**. γ, β and the prune constants are E1's, carried.
- **the eval corpus is Yandex.** Permitted (research/held-out eval), but it means the *only*
  real-Russian evidence for this model comes from a source whose data can never enter training
  or the APK.
- **no on-device measurement.** No latency number, no memory number, no instrumented run.

### 4.6 What still has to happen before ru could ship

Not blockers on the ML side — app-side wiring, listed so nobody thinks the export was the last
step. Status is against the app at `d717bda7`; the ordered, actionable form of this list lives in
`APP_WIRING_CHECKLIST.md`.

| # | change | status at `d717bda7` |
|---|---|---|
| 1 | bundle or gate on the ru langpack (an import today, not an asset) | **open** — `scripts/dictionaries/langpack-ru.zip`, not in `assets/dictionaries/` |
| 2 | per-script `ALPHABET` + `buildMappedLayout` in `CtcEngineAdapter` — today `letterOf` filters `'a'..'z'` and the arrays are `FloatArray(26)`/`BooleanArray(26)` | **open** |
| 3 | per-language model asset — `CtcEngineAdapter.MODEL_ASSET` is one constant | **open** |
| 4 | `ru` in `CtcLanguageSupport.SUPPORTED`, and a script gate that admits `script="cyrillic"` | **open** |
| 5 | make `tunedRuCkdt` reachable — `presetFor` branches on `LexiconSource` and can never return it | **open** |
| 6 | ship the golden fixture and extend `CtcParityTest`'s fixture↔model↔preset triple to a second row | **open** |
| 7 | trie width — the `MAX_CHILDREN = 26` clamp | **done** (`d671d19e`); the bound is now a constructor check against the emission-head width |
| 8 | `CtcLayout` generic over `alphabet: CharArray` | **done** |
| 9 | measure latency and memory on device | **open**, but now *possible*: the settle-probe inflation (audit MEDIUM-2) was removed in `716f7be9`, so a `LOCAL_BUILD=true` measurement is no longer ~720 ms high |

The ru model is *half* the shipped English model's bytes, so the latency expectation is
favourable — expectation is not measurement.

### 4.7 The other five scripts — the per-script wiring table, refreshed

Supersedes `PHASE_O.md` §3.2/§3.5 (generation 1) and this section's own Phase-P revision
(generation 2/3). **Wire `*_synth_v3_ch80_fp16w*` — generation 4 — for all six scripts**; hashes
in §4.2, derivation in `PHASE_Q.md` §7.7.

| script | layout XML (`src/main/layouts/`) | K | alphabet / slot order (codepoint-sorted — **this IS the app's array**) | ship bytes | lexicon | preset |
|---|---|---|---|---|---|---|
| **ru** | `cyrl_jcuken_ru.xml` | 31 | `абвгдежзийклмнопрстуфхцчшщыьэюя` | `ru_synth_v3_ch80_fp16w.onnx` | `langpack-ru.zip` — exists, importable today | `tunedRuCkdt` |
| **el** | `grek_qwerty.xml` (now correctly `script="greek"`) | 25 | `αβγδεζηθικλμνξοπρςστυφχψω` | `el_synth_v3_ch80_fp16w.onnx` | `langpack-el.zip` — exists, **needs the full el projection**, not just the ς repair (§7 item 9) | same numbers as `tunedRuCkdt` |
| **uk** | `cyrl_jcuken_uk.xml` | 31 | `абвгдежзийклмнопрстуфхцчшщьюяєі` | `uk_synth_v3_ch80_fp16w.onnx` | **must be built** (`build_wordlist.py --lang uk`) | same |
| **bg** | `cyrl_ueishsht.xml` | 30 | `абвгдежзийклмнопрстуфхцчшщъьюя` | `bg_synth_v3_ch80_fp16w.onnx` | **must be built** | same |
| **mk** | `cyrl_lynyertdz_mk.xml` | 31 | `абвгдежзиклмнопрстуфхцчшѓѕјљњќџ` | `mk_synth_v3_ch80_fp16w.onnx` | **must be built** | same |
| **he** | `hebr_1_il.xml` | 27 | `אבגדהוזחטיךכלםמןנסעףפץצקרשת` | `he_synth_v3_ch80_fp16w.onnx` | **must be built**, and `build_wordlist._is_script_word` needs a new `hebrew` branch (0x0590–0x05FF) | same |

**Preset: there is no per-script preset.** All six use γ 1.05 / **λ 2.0** / β 0.2 / γp 0.3734 /
βp 0.9882. λ = 2.0 is a **frequency-scale** constant (`LAMBDA_CKDT_SCALE`), not a Russian one —
every one of these lexicons is on the CKDT `255 − rank` scale, the same scale `fr/de/es/it/pt/sv`
already run at in production. The only preset that differs is `en`, whose `en_enhanced.json`
scale wants λ = 4.0. So `presetFor` needs to become reachable for `tunedRuCkdt`, not to grow six
new presets.

**Projection rules the app must mirror** (`PHASE_O.md` §3.4 — applied to the lexicon **and** to
anything compared against a decode):

* **all scripts** — lowercase; strip `- ' ’ ʼ ‘ \``.
* **el, he** — NFD, drop combining marks (`Mn`), NFC. Safe because no letter's identity depends
  on a mark here: Greek accents/diaeresis and Hebrew niqqud are not keys.
* **ru, bg, mk** — **no NFD** (it decomposes й into и + breve and destroys the alphabet).
  Character folds instead: ru ё→е, ъ→ь; bg ѝ→и; mk ѐ→е, ѝ→и.
* **el only** — *after* mark stripping, word-final `σ` → `ς`.
* **uk** — no folds; words containing ї or ґ are **rejected as untypeable** (4.03 % of the
  vocabulary). Serving them needs the corner-alias path, which is a different input mode.

**Evidence tier for these five — say it exactly this way** (`PHASE_O.md` §2.7, carried forward
through `PHASE_P.md` §5.1 and §8.6):

> Greek, Ukrainian, Bulgarian, Macedonian and Hebrew CTC are **synthesis-trained,
> synthesis-holdout-only, three-seed, and calibrated against Russian rather than measured on
> their own script.**

*(The closing round replicated all five at seeds 4321/7777: holdout seed-means el 92.19 ± 0.070,
uk 89.12 ± 0.266, bg 86.91 ± 0.180, mk 91.56 ± 0.121, he 80.43 ± 0.238, every EN-control margin
intact, no anomaly, shipped bytes unchanged — `PHASE_Q.md` §10.1. **Replication tightens a
generator-relative number; it does not make it a language accuracy figure**, so the sentence
above is otherwise unchanged.)*

Their generation-4 holdout numbers — el 92.12, uk 88.96, bg 86.76, mk 91.55, he 80.69 — are
**not accuracy figures for those languages**, and the point is sharper for v3 than it was for
v2: a v3 holdout is generated by v3, from the same weights, with only fresh noise and a fresh
word draw — there is not even a donor split left to be disjoint in. Phase O proved this class of
probe inverts model comparisons on capacity and on λ; Phase P §8 made it three, adding donor
footing. What the holdouts establish is a *margin against a fixed control*, and those margins
**widened** in generation 4 — el +7.01, uk +13.02, bg +10.05, mk +5.00, he +16.05 against the
3×-capacity English zero-shot, from P6's +5.1 … +7.9, where on the v1 holdouts every script
**lost**. The v3 distribution is simultaneously easier for its own model and harder for an
English zero-shot: the texture moved toward the target scripts, which is the direction ru's real
probe independently verified at +5.34. Permuted-geometry falsification still collapses every
script to ~0.00. **Never quote the levels as if they were ru's 85.07.**

**he's history flag does not apply to its ship bytes, and generation 4 did not revive it.** The
generation-2 `he_synth_v2_ch80` fp32 export needed `--parity-tol 2e-3` (sliced residue 1.16e-03
against a historical 0.8e-4…7.6e-4 envelope, argmax 100/100 on both probes) and is **flagged in
the registry**. Generation 3 (`he_synth_v2full_ch80`) needed no relaxation at 4.04e-04, and
**generation 4 (`he_synth_v3_ch80`) cleared at 3.57e-04 with argmax 100/100** — inside the
historical envelope, no flag. The flag stays on the P4 bytes because that exceedance was real;
it does **not** carry to the bytes you would wire.

---

## 5. The model inventory — which ONNX is which

**Exactly one CTC ONNX ships in the APK.**

| artifact | ships? | bytes | sha256 | serves | tier |
|---|---|---|---|---|---|
| `src/main/assets/models/ctc_swipe_encoder.onnx` = `ctc/artifacts/phaseM_kd_fresh_w1_s1234_fp16w.onnx` | **YES — the only one** | 3,052,318 | `84718e6ebc8020176f27b9668e50922a765c96838307b640a8db9ab0549e88e5` | en + fr/de/es/it/pt/sv on any a–z-complete Latin layout | **test-validated**, both footings, every seed |
| `ctc/artifacts/ru_synth_v3_ch80_fp16w.onnx` | no — **the ru ship candidate**, not wired | 589,406 | `8fffa75c722eb61e9e8c80d919fbca3e73eb698ebe3e3909cb766b3b8489962c` | Russian ЙЦУКЕН (31-letter default grid) | **val-only**, generator-**v3** synth-trained, Yandex-eval-only |
| `ctc/artifacts/{el,uk,bg,mk,he}_synth_v3_ch80_fp16w.onnx` | no — **the five ship candidates**, not wired | 589,406 each | §4.2 / `PHASE_Q.md` §7.7 | Greek, Ukrainian, Bulgarian, Macedonian, Hebrew | **synthesis-holdout-only**, calibrated against ru rather than measured |
| `ctc/artifacts/{ru,el,uk,bg,mk,he}_synth_ch80*` (generation 1) | no — **superseded** | — | `PHASE_O.md` §2.6 | — | kept because every pre-Phase-P number was measured on them |
| `ctc/artifacts/{ru,el,uk,bg,mk,he}_synth_v2_ch80*` (generation 2) | no — **superseded** | — | `PHASE_P.md` §6.1 | — | kept because `PHASE_P.md` §5 was measured on them; `he_synth_v2_ch80` carries a parity flag no later generation revives |
| `ctc/artifacts/{el,uk,bg,mk,he}_synth_v2full_ch80*` (generation 3) | no — **superseded** | — | `PHASE_P.md` §8.4 | — | kept because `PHASE_P.md` §8 was measured on them |
| `src/androidTest/assets/ctc_bench/{ch192,ch128,fast_resbn80,fast_resbn72}_s1234.onnx` | no — androidTest only, 11.0 MB | — | — | **nothing.** Superseded Campaign-2 arch-comparison artifacts, one of them still labelled "the ship candidate" in the benchmark's KDoc (audit MEDIUM-4, open) | historical |
| `phaseIB-ru-real` encoder (`cb8ece6b…`) | **NEVER** | 1,142,727 | — | — | license-blocked research artifact |
| `phaseJ-joint`, `sw2345`, `resbn192i`, `phaseL_*`, `phaseK_*`, `ch128/ch192`, `fast_resbn*` | no | — | — | — | superseded campaign arms |

**Four generations exist and all four stay in the registry**, which is deliberate and is the
commonest source of a wrong hash: `*_synth_ch80*` (v1, Phase O), `*_synth_v2_ch80*` (v2, Phase P
§5), `*_synth_v2full_ch80*` (v2 on the full donor pool, Phase P §8 — five scripts only, ru was
always full-pool), `*_synth_v3_ch80*` (**v3, the learned generator, Phase Q §7.7**). **Deploy
`*_synth_v3_ch80_fp16w*` — the newest generation exists for all six scripts.** The older
generations are kept only because published numbers were measured on them.

**Phase Q closed, and generation 4 is what it produced.** It ran two twin generators on
separated licence tracks — a shipping track fitted only to MIT data (FUTO t3 + HWS), whose
outputs trained every `*_synth_v3_ch80*` artifact above, and a **sealed research track** fitted
to Yandex residuals whose generator weights, samples, decoder, onnx and dumps are permanently
unshippable, carry a `RESEARCH_ONLY` suffix, live untracked under `~/ctc-train/research_only/`,
and never enter `ctc/artifacts/`, the registry, `exports/`, an app asset or a donor bank. The
sealed track exists to produce **one number** — the upper bound U = 85.95 (§3.3) — and produces
no bytes. The operative rule for anyone wiring bytes is unchanged, and it is the seal's
enforcement surface: **if it is not in `ctc/artifacts/`, it is not wirable.**

Golden fixtures:

| fixture | pairs with | preset |
|---|---|---|
| `src/test/resources/ctc/ctc_golden.json` = `src/androidTest/assets/ctc/ctc_golden.json`, sha `2a449c4f2de19505131b396655ae01d3e3c325e40249446ff6e7a40c2b27559c`, 140,462 B | the shipped ONNX (`84718e6e…`) — the **header sha** is asserted in CI, the **emission matrices are not**; see `APP_INTEGRATION_AUDIT.md` §6.2 | `tunedV2` = 0.9 / 4.0 / 0.25 / 0.25 / 0.9882 |
| `ctc/artifacts/ru_synth_v3_ch80_fp16w_golden.json`, 160,384 B, sha `2e8de3c5a15e…` | `ru_synth_v3_ch80_fp16w.onnx` (`8fffa75c…`) | `tunedRuCkdt` = 1.05 / 2.0 / 0.2 / 0.3734 / 0.9882 |
| `ctc/artifacts/{el,uk,bg,mk,he}_synth_v3_ch80_fp16w_golden.json` | the matching v3 fp16w bytes (§4.2) | same `tunedRuCkdt` numbers |

Every script fixture is 10 cases (5 pure-featurizer branch probes, 1 word-path featurizer case,
4 model-backed beam cases) at the same preset — the same shape as the shipped en fixture, so
`CtcParityTest` grows a **row**, not a new mechanism.

**No model change is pending.** Phase N is terminal (`CleverKeys-ML` @ `85c0c58`); its 91.25
headline is a different corpus, trie and preset and is explicitly not comparable. Anything you
find that says otherwise — in particular
`docs/audit/remediation-plans/ctc-integration-execution-brief.md`, which still reads
*"Q1 model choice: SUPERSEDED-PENDING — a new model is training"* and names four candidates —
is a pre-decision planning document that nobody bannered. **That file is the single likeliest
source of the "which ONNX?" question.**

---

## 6. Audit findings — status at `d717bda7`

The full record is `APP_INTEGRATION_AUDIT.md`: §2 the original findings, §5 the re-verification at
`9a6ffdd2`, **§6 the second re-verification at `d717bda7`**. The ordered, actionable form for the
app agent is `APP_WIRING_CHECKLIST.md`. This section is the summary only, so it cannot drift into
a third competing task list.

**Of the 23 original findings plus the 4 added in §5: 15 CLOSED, 6 PARTIAL, 6 OPEN, 0 regressed.**

**Closed in the 2026-08-20 wave** — HIGH-1 (a latched ONNX-load failure now falls through to
geometric on both dispatch and prewarm, `ad18e0e3`; the diff this guide proposed applied
essentially verbatim), HIGH-3 (the dead-code KDoc is gone from all three files), MEDIUM-1 (the ORT
session is closed at teardown, after the decode thread is confirmed dead), MEDIUM-2 (**the settle
probes are gone — latency numbers from a `LOCAL_BUILD=true` build are quotable again**), MEDIUM-7
(the "this engine isn't serving your language" card is back, and formats its list from
`CtcLanguageSupport.SUPPORTED.keys` so it cannot drift), MEDIUM-8 (seven languages everywhere,
including all 22 locale strings), MEDIUM-9, NEW-1 (the spec rewritten around `ctc`-as-default),
NEW-2 (the `grek_qwerty` script tag, plus `LayoutScriptDeclarationTest`), NEW-4.

**Still open, and the short list is now genuinely short:**

| # | item | why it is still here |
|---|---|---|
| 1 | **MEDIUM-3** — `docs/audit/remediation-plans/ctc-integration-execution-brief.md` has no superseded banner; `:86` still says *"Q1 model choice: SUPERSEDED-PENDING — a new model is training"*, `:74` still *"Default engine stays `neural`"* | One paragraph. Unchanged across all three audit passes, and now the **only** anti-confusion finding left. It is the single likeliest source of the "which ONNX?" question. |
| 2 | **HIGH-4's residue** — CI now runs instrumented tests (real, green on API 21/29/34), but the curated class list omits `CtcEmissionModelParityTest` | So the fixture's *header* sha is checked on every push and its *emission matrices* are checked nowhere automatic — the exact swap failure HIGH-4 was written about. One string edit to `.github/scripts/emulator-ci.sh`. Also note `ui-testing.yml` runs on PR + nightly, **not on push to `main`**. Detail in `APP_INTEGRATION_AUDIT.md` §6.2. |
| 3 | **HIGH-2's residue** — two unmarked `sw2345` citations in `docs/`, at `docs/audit/2026-08-17-neural-vs-ctc-parity.md:619-623` (finding 13, unstruck) and `docs/eval/2026-08-15-ctc-per-language-lambda.md:101, 112` | The drift guard was widened to `src/test` and `src/androidTest` (`f172bb8e`), which caught the test-side copy. It still does not scan `docs/`, which is where both survivors are. |
| 4 | **NEW-6** — the app's guide mirror and `memory/HANDOFF.md` still cite generation-1 ru artifacts (`ru_synth_ch80_fp16w.onnx`, `84ac284d…`, 77.41) | `APP_WIRING_CHECKLIST.md` §3 enumerates the sections to bring across. |
| 5 | **MEDIUM-4** — 11 MB of superseded ONNX in `src/androidTest/assets/ctc_bench/`, one labelled "the ship candidate" | Item 2 of the app's own `HANDOFF.md`. |
| 6 | **MEDIUM-5, MEDIUM-6, LOW-9 (half), LOW-1..LOW-8, LOW-10** | Cleanup. LOW-9's remaining half — a `supportsLayout` negative for a Cyrillic `KeyboardData` — stops being cosmetic the moment per-script routing removes gate 1. |

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
   to the wrong one looks landed and is not — that is exactly what happened to the Greek script
   tag, for months (§2.1). The two trees agree today; `LayoutScriptDeclarationTest` guards the
   one that ships.
9. **Do not wire the el lexicon with only the final-sigma repair.** `CtcGreekOrthography`
   (`swipe/ctc/CtcGreekOrthography.kt`, shipped `6f30d60f`) is correct and is the *last* of the
   four steps in the el projection. The step before it — NFD, drop combining marks, NFC — has
   **no app-side implementation**, and it is the step that makes the alphabet 25 letters. The
   el model's slot order contains no accented vowels, so an unprojected `λόγος` has a character
   with no emission slot. Repairing sigma alone converts "one word in four is mis-keyed" into
   "most of the pack is unrepresentable". Both halves or neither.
10. **Do not wire a `*_synth_ch80*`, `*_synth_v2_ch80*` or `*_synth_v2full_ch80*` model for any
    script.** Those are generations 1–3; the deployment bytes for **all six** are
    `*_synth_v3_ch80_fp16w*` (`PHASE_Q.md` §7.7, hashes in §4.2). The older rows survive in the
    registry only because §5's numbers were measured on them, and `he_synth_v2_ch80` additionally
    carries a parity flag no later generation revives.
11. **Do not quote a script's synthesis-holdout number as an accuracy figure.** el 92.12 is not
    "Greek at 92.12"; it is fit to the v3 generator's own distribution, on a probe this campaign
    has now shown three separate times to rank things real swipes do not rank (capacity, λ,
    donor footing) — and a v3 holdout is one turn *more* generator-relative than a v2 one,
    because there is no donor split left to be disjoint in. Quote margins against a fixed
    control, never levels. §4.7 gives the wording.
12. **Do not copy a `RESEARCH_ONLY` byte anywhere.** The sealed twin generator, its samples and
    its decoder are Yandex-derived and permanently unshippable. They exist to produce the upper
    bound U (§3.3) and nothing else. If a file is not in `ctc/artifacts/`, it is not wirable.
