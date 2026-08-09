# CTC swipe-engine assets — provenance

Everything in this directory is an input to the **CTC** engines selectable from the
web demo's engine dropdown. The shipped-transformer engine keeps using the existing
`swipe_encoder_android.onnx` / `swipe_decoder_android.onnx` / `en_enhanced.json`
assets one directory up.

| File | Source | sha256 |
|---|---|---|
| `ch128_s1234.onnx` | `CleverKeys-ML/ctc/artifacts/ch128_s1234.onnx` | `6c1144949e545f626419e1fa7b29e80f9ecf3e303886f30411fc37ae72c45c51` |
| `fast_resbn80_s1234.onnx` | `CleverKeys-ML/ctc/artifacts/fast_resbn80_s1234.onnx` | `5e8c88756cbad5a5a8b8b3f289a990174fa6f3b6edfead46d8dbdb2927fb06f2` |
| `en_qwerty.json` | `CleverKeys-ML/ctc/en_qwerty.json` | `1965ecd59c9e4bff89446bb56ff3a2d0070b16eeae4ce424ce08b06ed6864632` |
| `ctc_vocab.bin` | built by `web_demo/tools/build_ctc_vocab.py` from `~/ctc-train/data/futo_en_wordlist.combined` | `daf49b9463a7533f1b410f2550cb3cebe18dabcf11b2350043366ae59dc69321` |

## Models

Both encoders were trained from scratch in `CleverKeys-ML/ctc` (train.py →
export_onnx.py) — they are **not** derived from FUTO's `honorable_sturgeon`
weights, only from its input/output contract. Seed `s1234` of each architecture
family is the one shipped here.

* `ch128_s1234` — "CTC accurate", 2.8 MB, the wider (channels=128) encoder.
* `fast_resbn80_s1234` — "CTC fast", 1.1 MB, the residual-BN width-80 encoder.

Shared I/O contract (verified with onnxruntime 1.22.1):

```
IN   features     float32 [1, 2, 64]   row 0 = x, row 1 = y, 64 resampled points in [0,1]
IN   layout_keys  float32 [1, 64, 2]   26 key centres (alphabetical a..z), then zeros
IN   layout_mask  bool    [1, 64]      26 true, then false
OUT  log_emissions float32 [1, 32, 65] log-softmaxed; blank is column 64
OUT  coefficients  float32 [1, 32, 64] refinement-head input (unused by this demo)
OUT  lambda        float32 [1, 32, 1]  refinement-head input (unused by this demo)
```

The demo slices `log_emissions` to `[32, 27]` (columns 0..25 = a..z, column 64 →
slot 26 = blank), exactly as `futo_decoder_ceiling.py::slice_emissions` does. The
`coefficients` / `lambda` heads feed FUTO's optional DFSMN refinement decoder,
which this demo does not run.

## Layout

`en_qwerty.json` supplies the **canonical** key centres the models were trained
against. Those centres go into `layout_keys` verbatim; they are never derived
from the drawn keyboard. The drawn keyboard only supplies the touch → `[0,1]`
mapping for the trajectory itself (bounding box of the three letter rows).

## Vocabulary

`ctc_vocab.bin` is a front-coded binary of the 146,964 unique a-z surface forms
produced by `futo_decoder_eval.py::load_combined_vocab` over the 165,644-entry
AOSP `en_wordlist.combined`. See the module docstring of
`web_demo/tools/build_ctc_vocab.py` for the byte layout and for why the raw u16
frequency (rather than a float32 `log_freq`) is the parity-safe thing to store.

Rebuild + re-verify against the Python reference loader:

```bash
python3 web_demo/tools/build_ctc_vocab.py \
    --wordlist ~/ctc-train/data/futo_en_wordlist.combined \
    --out      web_demo/demo/models/ctc_vocab.bin \
    --verify
```

## Vendored runtime

`../vendor/ort/` holds onnxruntime-web **1.18.0** (`ort.wasm.min.js` +
`ort-wasm-simd.wasm`, MIT). The demo previously pulled these from jsdelivr; they
are vendored so the demo has no CDN dependency and runs from a bare static
server. Only the SIMD, single-threaded WASM binary is vendored — the threaded
build additionally needs cross-origin isolation headers that a plain static host
does not send, and the non-SIMD fallback is dead weight for any browser from the
last five years.
