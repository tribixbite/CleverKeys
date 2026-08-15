# CleverKeys web demo

`demo/index.html` is the browser swipe demo. It runs four decode engines over
one shared gesture capture, selected from the **Engine** dropdown (the choice
persists in the demo config):

| Dropdown entry | Model | Lexicon | Decoder |
|---|---|---|---|
| Neural (shipped transformer) | `swipe_encoder_android.onnx` + `swipe_decoder_android.onnx` — the pair inside the APK | 98,140-word `en_enhanced.json` | GNMT length-normalised beam + `OptimizedVocabulary` rerank (production parity, audited 2026-07-21) |
| CTC (shipped app engine) | `ctc_swipe_encoder.onnx` (2.9 MB) — byte-identical to the APK asset `models/ctc_swipe_encoder.onnx`, sha256 `84718e6e…` | 98k `en_enhanced.json`, a-z-STRIPPED (98,081 words) | FUTO single-stream Viterbi trie beam at the SHIP preset `CtcScoringParams.tunedV2` (γ 0.9, λ 4.0, β 0.25, γₚ 0.25, βₚ 0.9882, width 100) |
| CTC accurate (ch128) | `demo/models/ch128_s1234.onnx` (2.8 MB) | 146,964-word `ctc_vocab.bin` | FUTO single-stream Viterbi trie beam (demo-tuned preset) |
| CTC fast (resbn80) | `demo/models/fast_resbn80_s1234.onnx` (1.1 MB) | same | same |

**CTC (shipped app engine)** is the browser twin of the Kotlin
`CtcEngineAdapter` / `swipe.ctc` engine that ships in the app: same ONNX, same
STRIP lexicon policy (`CtcLexiconTrie.loadStrippingNonAlphabet` — `don't` →
`dont`, so apostrophe forms are reachable on an a-z-only model), same scoring
preset, and it is gated on the same golden fixture the Kotlin port is
(`src/test/resources/ctc/ctc_golden.json`) via `tests/ctc_app_parity.mjs`.
The two "accurate/fast" entries are the earlier from-scratch experimental
encoders, kept for comparison.

Every model, lexicon and runtime is served from the repo — no CDN takes part in
decoding. onnxruntime-web 1.18.0 is vendored under `demo/vendor/ort/`. One CDN
dependency does remain, and it is cosmetic: `demo/index.html` still pulls
Tailwind from `cdn.tailwindcss.com` for styling. Vendoring it is a filed
follow-up (`memory/todo.md`).

## Run it

```bash
python3 web_demo/serve.py            # http://127.0.0.1:8765/demo/
```

`serve.py` reproduces what `.github/workflows/deploy-web-demo.yml` publishes:
CI copies `web_demo/demo/` into `site/dist/demo/` and then flattens
`web_demo/*.{onnx,js,json,...}` on top, so `demo/index.html` fetches those
parent-directory assets as siblings. Rather than duplicate ~11 MB of binaries
into `demo/`, the server serves `web_demo/` as the document root and falls back
from `/demo/<name>` to `web_demo/<name>`. Any static host works in production;
only local development needs this shim.

## What the CTC path does

The two CTC engines are a browser port of `CleverKeys-ML/ctc`. `ctc-engine.js`
is a direct translation of three Python references — a behavioural change in
either is a parity bug:

| JS | Python reference |
|---|---|
| `featurize()` | `futo_decoder_eval.py::featurize` (60 Hz linspace resample, then index-uniform resample to 64, clipped to `[0,1]`) |
| `sliceEmissions()` | `futo_decoder_ceiling.py::slice_emissions` |
| `futoViterbiBeam()` | `futo_decoder_ceiling.py::futo_viterbi_beam` |

Two scoring presets live in `ctc-engine.js`:

* `CTC_SCORING` (experimental engines, FUTO 147k lexicon): `gamma=1.05`,
  `lambda=1.1`, `beta=0.2`, `gammaPrune=0.3734`, `betaPrune=0.9882`,
  beam width 100, top-k 8.
* `CTC_APP_SCORING` (shipped app engine, en_enhanced STRIP trie): `gamma=0.9`,
  `lambda=4.0`, `beta=0.25`, `gammaPrune=0.25`, `betaPrune=0.9882`, beam width
  100, top-k 8 — a 1:1 port of the app's `CtcScoringParams.tunedV2()`. λ=4.0
  is deliberate: it multiplies en_enhanced's compressed 134–255 byte-score
  scale (`ln f ∈ [4.9, 5.54]`), not the raw AOSP counts the FUTO-blob λ sees.

The app engine's trie is built at page load by `CtcTrie.fromFrequencyMap`
(~98k words in well under a second), a port of the Kotlin
`CtcLexiconTrie.loadStrippingNonAlphabet` insertion semantics: lowercase,
strip non-a-z, floor frequency at 1, `log_freq = ln(freq + 1e-10)` with
keep-max retention, children in insertion order (Kotlin's LinkedHashMap) so
beam tie-breaks match the app exactly.

### Contraction display

CTC candidates are raw a-z surfaces (`dont`, `im`) because the lexicons store
contractions as apostrophe-free aliases. The demo maps them to display forms
at the chip/auto-insert layer via `applyContractionFixup` — the shipped
`contractions_en.json` (120 non-paired aliases, e.g. `dont`→`don't`,
`im`→`i'm`) plus the paired-base guard (`well`, `were`, `hes`… are real words
and never rewritten). This mirrors the app's `ContractionOverlay` rules 1/2b
for English; the overlay's remaining behaviours — appending paired variants
("well" + "we'll") and the frequency-ordinal guard for real-word aliases —
are not implemented in the demo (the ordinal guard only matters for non-en
languages, which the demo doesn't decode).

Two details worth knowing:

* **Coordinates.** `layout_keys` always gets the *canonical* key centres from
  `models/en_qwerty.json`; they are never derived from the drawn keyboard. The
  drawn keyboard only supplies the trajectory, mapped from touch coordinates to
  `[0,1]×[0,1]` over the bounding box of the three letter rows — the same frame
  the canonical centres live in.
* **No prefix strings in the beam.** A trie node uniquely determines its
  prefix, so `nodeChar` / `nodeDepth` supply everything the Python reads off
  `prefix` (`prefix[-1]` and `len(prefix)`). Words are materialised only for
  the finalists, by walking `nodeParent` back to the root. That keeps the hot
  loop on integers.

### Vocabulary format

`demo/models/ctc_vocab.bin` is the 146,964 unique a-z surface forms that
`futo_decoder_eval.py::load_combined_vocab` derives from the 165,644-entry AOSP
`en_wordlist.combined`. Words are sorted and front-coded (shared-prefix byte,
suffix length, suffix), followed by a `u16` frequency column.

| | |
|---|---|
| Size | **918,638 bytes** (0.92 MB); **408,858 bytes** gzipped |
| Fetch (localhost) | ~8 ms |
| Trie build in JS | **~21 ms** → 330,762 CSR nodes |

Front-coding is what makes it small: a sorted a-z lexicon shares ~6 of ~8
characters with its predecessor. Storing the raw `u16` frequency instead of a
float32 `log_freq` is what makes it *exact* — every kept frequency is ≥ 1, so
the reference loader's keep-max guard degenerates to a plain maximum, and JS
recomputes `Math.log(freq + 1e-10)` in float64 exactly as Python does. A
float32 `log_freq` column would have introduced ~1e-7 of avoidable error.

Rebuild and re-verify against the Python loader:

```bash
python3 web_demo/tools/build_ctc_vocab.py \
    --wordlist ~/ctc-train/data/futo_en_wordlist.combined \
    --out      web_demo/demo/models/ctc_vocab.bin --verify
```

`--verify` asserts the blob reproduces the reference trie exactly: 146,964
words, max `log_freq` deviation **0.000e+00**.

Model provenance and sha256 sums: [`demo/models/PROVENANCE.md`](demo/models/PROVENANCE.md).

## Tests

```bash
python3 web_demo/tests/ctc_reference.py       # regenerate reference.json
python3 web_demo/serve.py --port 8765 &
cd web_demo/tests && bun install              # once — puppeteer-core + onnxruntime-web
node web_demo/tests/run_browser_tests.mjs     # drives headless Chrome

# App-engine gates (pure Node, no browser or server needed):
node web_demo/tests/ctc_app_parity.mjs        # golden-fixture parity gate
node web_demo/tests/ctc_app_smoke.mjs         # headless end-to-end smoke
```

### App-engine golden parity (`ctc_app_parity.mjs`, 2026-08-15)

Runs the real `demo/ctc-engine.js` in a Node VM plus the real
`ctc_swipe_encoder.onnx` via onnxruntime-web against
`src/test/resources/ctc/ctc_golden.json` — the same fixture that gates the
Kotlin port. Result: all 6 featurize cases **bit-exact float32 (128/128)**;
all 4 beam cases reproduce `greedy` and the top-k **words and order exactly**,
with max score delta **3.3e-6** (gate: 1e-3) and ORT-web-vs-fixture emission
drift ≤ 2.7e-5.

### App-engine end-to-end smoke (`ctc_app_smoke.mjs`, 2026-08-15)

Loads the shipped inline demo script (VM + DOM stubs, the a22b76ad pattern),
switches to `ctc_app` through the real dropdown handler, and replays the nine
`reference.json` trajectories through `processSwipe`. 7/9 decode to themselves
at top-1; `four` → `for` (`four` #2, all CTC engines do this) and `hello` →
`help` (`hello` #2 — the 98k en_enhanced trie ranks the far-more-frequent
`help` above `hello` on this synthetic constant-speed trace; the golden gate
proves this is a model+lexicon outcome, not port drift). Also asserts the
`dont`→`don't` display mapping, the `well` paired-base guard, and
custom-word insert/remove on the app trie. Decode latency in Node-WASM:
~17–55 ms per swipe.

`ctc_reference.py` synthesises deterministic Catmull-Rom swipe trajectories and
decodes them with the CleverKeys-ML pipeline itself (the featurizer, slicer and
beam are *imported* from `CleverKeys-ML/ctc`, not re-implemented). The browser
harness replays the **same point arrays** through `window.processSwipe` — the
entry point a finger-drawn swipe uses — so both sides always compare the same
gesture. Results land in `tests/results.json` and `tests/screenshots/`.

### Top-1 per engine, per word

Nine synthetic gestures, top-1 only. ✓ = top-1 correct. The `CTC app` column
is from the Node smoke (`ctc_app_smoke.mjs`, same trajectories through the
same `processSwipe` entry point); the other three are the headless-Chrome run.

| word | transformer | CTC app | CTC ch128 | CTC resbn80 |
|---|---|---|---|---|
| the | ✓ | ✓ | ✓ | ✓ |
| hello | ✓ | ✗ `help` (#2) | ✓ | ✓ |
| keyboard | ✗ `kettering` | ✓ | ✓ | ✓ |
| dont | ✗ `dolmen` | ✓ | ✓ | ✓ |
| world | ✓ | ✓ | ✓ | ✓ |
| this | ✓ | ✓ | ✓ | ✓ |
| about | ✓ | ✓ | ✓ | ✓ |
| four | ✗ `for` | ✗ `for` (#2) | ✗ `for` | ✗ `for` |
| something | ✗ `stomper` | ✓ | ✓ | ✓ |
| **total** | **5/9** | **7/9** | **8/9** | **8/9** |

Both experimental CTC engines reproduce the Python reference exactly,
including the single miss. `four` → `for` is a genuine outcome, not a port
defect: the `f-o-u-r` path passes within a key-width of the `f-o-r` path, and
`for` is far more frequent. The app engine's `hello` → `help` is the same
category of outcome on its 98k lexicon (see the smoke section below).

The transformer's 5/9 is a property of the **test fixture, not the engine**.
These synthetic trajectories are constant-speed spline walks; the transformer
consumes velocity and acceleration channels over 250 points, so a gesture with
no human speed profile is out of distribution for it. The CTC models only see
64 resampled `(x, y)` positions and are therefore insensitive to it. Do not
read this table as a model-quality comparison — for that, see
`docs/eval/2026-07-24-test2400-head2head.md`, which uses real human traces.

### JS ↔ Python parity

| check | ch128 | resbn80 |
|---|---|---|
| Featurizer max abs deviation (3 paths × 128 values) | **0** | **0** |
| Beam top-1 exact | 3/3 | 3/3 |
| Beam **top-8 ordering** exact | 3/3 | 3/3 |
| Greedy CTC string exact | 3/3 | 3/3 |
| Max per-candidate score delta | 1.9e-6 | 1.9e-6 |

The featurizer is **bit-identical** to Python — 0, not "under 1e-6" — which is
what the half-to-even `pyRound` helper and the `bisect_left` port buy. The
residual ~1e-6 on candidate *scores* is WASM-vs-native float accumulation
inside the ONNX encoder; it does not perturb any ranking.

### Latency (mean of 10 decodes, headless Chrome, `keyboard`)

| engine | total | featurize | encoder | beam |
|---|---|---|---|---|
| Neural (transformer) | 356.11 ms | 0.01 ms | 356.10 ms † | — |
| CTC accurate (ch128) | **3.13 ms** | 0.01 ms | 1.52 ms | 1.60 ms |
| CTC fast (resbn80) | **2.32 ms** | 0.01 ms | 0.76 ms | 1.55 ms |

† The transformer's beam is interleaved with per-step decoder sessions, so it
has no separable beam term — its search cost is inside the encoder column.

CTC is ~110–150× faster here, which is the expected shape: one encoder pass
plus a trie beam, versus an encoder pass plus ~6–12 autoregressive decoder
steps. Desktop WASM single-threaded numbers; they are not device numbers.

Screenshots: `tests/screenshots/{transformer,ctc_ch128,ctc_resbn80}.png`.

## Layout

```
web_demo/
├── demo/
│   ├── index.html              the demo (engine dropdown, capture, all decode paths)
│   ├── ctc-engine.js           featurizer + CSR trie (+ from-map builder) + FUTO Viterbi beam
│   ├── models/                 experimental CTC ONNX, en_qwerty.json, ctc_vocab.bin, PROVENANCE.md
│   └── vendor/ort/             onnxruntime-web 1.18.0 (MIT)
├── ctc_swipe_encoder.onnx      shipped app CTC encoder (== APK asset, sha256 84718e6e…)
├── tools/build_ctc_vocab.py    wordlist -> ctc_vocab.bin (with --verify)
├── tests/                      reference + browser harnesses, app-engine parity/smoke, results
├── serve.py                    local server mirroring the deploy layout
└── *.onnx, en_enhanced.json…   transformer + app-CTC assets, flattened into /demo/ at deploy
```
