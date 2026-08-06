# Offline decoder speedup investigation (2026-08-06)

**Question:** can we speed up our offline ONNX swipe decode (`tools/test_cli_predict.py`)?
Prompted by the "ffmpeg 9 ONNX" idea — which was rejected (ffmpeg's DNN backend is a
frame-based CNN filter with GPU/NPU providers absent on Android; it cannot express an
autoregressive trie-constrained beam). This tested the two *real* levers that surfaced.

## Verdict: **adopt neither.** No easy offline speedup is available.

| config | traces/min | speedup | prod t1/t3/t5 | accuracy Δ |
|---|---|---|---|---|
| **cpu-fp32** (default, shipped) | 337.8 | 1.00× | 73.7 / 85.0 / 90.3% | baseline |
| xnnpack EP | 269.4 | **0.80× (slower)** | 73.7 / 85.0 / 90.3% | 0.00 / 0.00 / 0.00 |
| cpu-int8 (re-quantized decoder) | 343.2 | 1.02× (noise) | 73.7 / 85.0 / 90.3% | 0.00 / 0.00 / 0.00 |

300 traces × 4 reps, **paired per-trace timing** (each trace decoded on all sessions
consecutively, rotated, post-warmup). All three are **bit-identical** in output.

### Why XNNPACK is *slower* (−20%)
Only **8 of 486 decoder node-executions per step** land on XNNPACK — and only the attention
`Softmax` ops. Every MatMul stays on CPU because the decoder's shapes are fully dynamic
(`num_beams`, `dec_seq`, `enc_seq` all symbolic), which XNNPACK won't claim. The 8 offloaded
Softmaxes add EP partition-boundary data copies on every autoregressive step (~19 steps × 6
beams × 2 passes), and that overhead exceeds any gain.

### Why int8 does nothing — the lever is already spent
The **shipped `swipe_decoder_android.onnx` is already int8-dynamic-quantized**: 29
`MatMulInteger` + 26 `DynamicQuantizeLinear` nodes, **4.2M INT8 weight params vs 146K FLOAT**.
`quantize_dynamic` emits *"model is already quantized"* and returns an identical file. The 16
remaining plain `MatMul`s are attention Q·Kᵀ / softmax·V (two dynamic activation inputs — not
weight-quantizable by design).

### The real path (future, not done)
A genuine decoder speedup needs **static shapes** (fixed beam-batch width + sequence length)
so XNNPACK/NNAPI can claim the MatMul hot path — an exported-model change, not quantization.

## Methodology caveat (applies to the whole eval harness)
Naive back-to-back subprocess benchmarking is **invalid** on this device: the CPU governor
ramps clock under sustained load, so a plain baseline→xnnpack→int8 sequence produced a bogus
monotonic 111→199→273 tr/min "speedup" purely from warmup. Only paired per-trace timing gives
trustworthy ratios.

## Code (opt-in diagnostics; default path unchanged)
- `make_session(path, threads, ep='cpu')` + `--ep {cpu,xnnpack}` (default `cpu`) and
  `--decoder-model PATH` in `tools/test_cli_predict.py`; additive `SUMMARY_JSON` line.
- `scripts/bench_decoder_speedups.py` — the paired benchmark.
- int8 model was a scratch artifact (not committed; the shipped decoder is already int8).
