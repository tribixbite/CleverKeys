# CleverKeys web demo

`demo/index.html` is the browser swipe demo. It runs three decode engines over
one shared gesture capture, selected from the **Engine** dropdown:

| Dropdown entry | Model | Lexicon | Decoder |
|---|---|---|---|
| Neural (shipped transformer) | `swipe_encoder_android.onnx` + `swipe_decoder_android.onnx` — the pair inside the APK | 98,140-word `en_enhanced.json` | GNMT length-normalised beam + `OptimizedVocabulary` rerank (production parity, audited 2026-07-21) |
| CTC accurate (ch128) | `demo/models/ch128_s1234.onnx` (2.8 MB) | 146,964-word `ctc_vocab.bin` | FUTO single-stream Viterbi trie beam |
| CTC fast (resbn80) | `demo/models/fast_resbn80_s1234.onnx` (1.1 MB) | same | same |

Everything is served from the repo — there is no CDN dependency.
onnxruntime-web 1.18.0 is vendored under `demo/vendor/ort/`.

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

Scoring preset (`CTC_SCORING`): `gamma=1.05`, `lambda=1.1`, `beta=0.2`,
`gammaPrune=0.3734`, `betaPrune=0.9882`, beam width 100, top-k 8.

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
cd web_demo/tests && bun install              # once — puppeteer-core
node web_demo/tests/run_browser_tests.mjs     # drives headless Chrome
```

`ctc_reference.py` synthesises deterministic Catmull-Rom swipe trajectories and
decodes them with the CleverKeys-ML pipeline itself (the featurizer, slicer and
beam are *imported* from `CleverKeys-ML/ctc`, not re-implemented). The browser
harness replays the **same point arrays** through `window.processSwipe` — the
entry point a finger-drawn swipe uses — so both sides always compare the same
gesture. Results land in `tests/results.json` and `tests/screenshots/`.

### Top-1 per engine, per word

Nine synthetic gestures, top-1 only. ✓ = top-1 correct.

| word | transformer | CTC ch128 | CTC resbn80 |
|---|---|---|---|
| the | ✓ | ✓ | ✓ |
| hello | ✓ | ✓ | ✓ |
| keyboard | ✗ `kettering` | ✓ | ✓ |
| dont | ✗ `dolmen` | ✓ | ✓ |
| world | ✓ | ✓ | ✓ |
| this | ✓ | ✓ | ✓ |
| about | ✓ | ✓ | ✓ |
| four | ✗ `for` | ✗ `for` | ✗ `for` |
| something | ✗ `stomper` | ✓ | ✓ |
| **total** | **5/9** | **8/9** | **8/9** |

Both CTC engines reproduce the Python reference exactly, including the single
miss. `four` → `for` is a genuine outcome, not a port defect: the `f-o-u-r`
path passes within a key-width of the `f-o-r` path, and `for` is far more
frequent.

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
│   ├── index.html              the demo (engine dropdown, capture, both decode paths)
│   ├── ctc-engine.js           featurizer + CSR trie + FUTO Viterbi beam
│   ├── models/                 CTC ONNX, en_qwerty.json, ctc_vocab.bin, PROVENANCE.md
│   └── vendor/ort/             onnxruntime-web 1.18.0 (MIT)
├── tools/build_ctc_vocab.py    wordlist -> ctc_vocab.bin (with --verify)
├── tests/                      reference harness, browser harness, results, screenshots
├── serve.py                    local server mirroring the deploy layout
└── *.onnx, en_enhanced.json…   transformer assets, flattened into /demo/ at deploy
```
