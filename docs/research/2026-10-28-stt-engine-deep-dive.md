# STT engine deep dive: needle, streaming ONNX, Whistle vs Moonshine vs Zipformer (2026-10-28)

Follow-up to `docs/research/2026-10-28-whistle-offline-stt.md` (its "Corrections and updates"
section lists what this dive changed there). Three questions from the maintainer:

1. **Did you check `cactus-compute/needle`?** Yes, cloned and read. It is the Python package
   (training, export, JAX reference, CLI, downloader) — **not** the engine. The engine is a closed
   C++ library that the package downloads prebuilt from Hugging Face; the repository has never
   held a line of C/C++ in its 365 commits, and the maintainer confirmed in issue #118 that "the
   engine source isn't public". The earlier note's conclusion stands; the details it got wrong or
   left unverified are corrected in §2 and in the earlier note.
2. **Is streaming ONNX feasible?** Pseudo-streaming (re-transcribe the buffer, commit what two
   passes agree on) works on the Apache-2.0 ONNX export and gives the same WER as batch at 1 s
   chunks. **True streaming with encoder state caching is not possible with Whistle** — its
   encoder is bidirectional full attention with a non-causal conv and per-utterance CMVN, and its
   decoder is attention-based — so every pass re-encodes the window and the pass time grows with
   the buffered audio (283 ms at 1 s → 1.3–1.9 s at 10 s with fp32 graphs under this box's load).
   Moonshine v2's streaming encoder, by contrast, is incremental by construction (13–68 ms of
   encoder work per 1 s chunk, flat), and the sherpa-onnx Zipformer transducer is fully streaming
   (40–58 ms per 320 ms step, no retractions).
3. **Which engine, and what does Android integration cost?** All candidate graphs load and run on
   the APK's exact `onnxruntime-android:1.21.1` natives (JVM probe, CPU and XNNPACK EPs). For a
   keyboard that wants live dictation, **Moonshine v2 tiny-streaming on the ORT the app already
   ships** is the engine I would pick for v2.x: MIT weights and runtime, 51 MB of official
   quantised graphs, incremental encoder, batch RTF 0.08–0.10 and ≤ 0.4 s per 1 s chunk here, 112 MB
   steady RSS with the ORT arena off, and an official five-graph runtime to copy from. Whistle stays
   the best *multilingual bytes-per-WER* option (17 MB for 7 languages), but through FLOSS ORT it
   costs 55 MB (int8) to 145 MB (fp32) of graphs, 130–360 MB RSS, 3–14 s session load and a
   pseudo-stream that falls behind real time past ~5–8 s of audio unless windowed. The official
   Cactus engine is 2–3× faster and 4× smaller in RAM than either (RTF 0.037, 39 MB), but it is a
   binary and cannot go into the F-Droid build.

Marking: **[U]** = not verified from a primary source. Everything else was read or measured on
2026-10-28; every measurement line carries the box's load average because other sessions were
running Gradle builds throughout (see §3.1).

---

## 1. Method and environment

| Item | Value |
| --- | --- |
| Box | Samsung SM-S938U1 (Snapdragon 8 Elite, SM8750), Android 15 kernel 6.6, Termux; `nproc` = 4 visible cores (2 × 4.47 GHz prime + performance cores per `cpuinfo_max_freq`), 11.4 GB RAM |
| Python | 3.13.13 with Termux pacman `python-onnxruntime` **1.26.0** (`NnapiExecutionProvider`, `XnnpackExecutionProvider`, `CPUExecutionProvider`), numpy 2.4.4, onnx 1.22.0, torch 2.11.0. `pip install onnxruntime` is unnecessary (and the Termux venv's `ensurepip` fails); the system package was used directly |
| App ORT | `com.microsoft.onnxruntime:onnxruntime-android:1.21.1` (`build.gradle:98`, `gradle.lockfile:133`); the pure-JVM tests already unpack its bionic arm64 natives to `build/ort-native/arm64-v8a` (`extractOrtNative`), and `onnxruntime-1.21.1.jar` is in the Gradle cache — used for the compatibility probe (§5.4) |
| Audio | 80 LibriSpeech test-clean utterances (CC BY 4.0) via the HF datasets-server rows API, FLAC → 16 kHz mono PCM16 with ffmpeg; two sets: **"5 s"** = 21 clips of 4.05–5.85 s (280 reference words), **"15 s"** = 7 clips of 13.2–23.3 s (308 words). `scripts/research/stt/fetch_librispeech.py` |
| WER | `scripts/research/stt/asr_metrics.py`: lower-case, hyphens → spaces, punctuation stripped, Levenshtein S/D/I. **Not** the Whisper normaliser (no number-word or spelling folds), so absolute values are a little above published figures and only comparable within this note |
| Timing | `time.perf_counter` around each stage; batch numbers are the minimum over 2–3 repeats per clip, then the median over clips; RSS = `/proc/self/status` VmRSS/VmHWM of the Python process (≈ 45 MB interpreter + numpy baseline included) |
| Scripts | `scripts/research/stt/`: `whistle_ort.py` (ORT pipeline, bench, pseudo-stream), `moonshine_stream_ort.py`, `sherpa_zipformer_ort.py`, `needle_cli_bench.py`, `quantize_whistle_int8.py`, `OrtCompatCheck.java`, `run_all.sh`, `summarise_results.py`, `fetch_librispeech.py`. Models, audio and result JSONs live in the session scratchpad, not in git |

### 1.1 Load-average caveat

Every run here shared the four visible cores with other Claude sessions' Gradle builds
(`runPureTests`, `assembleRelease`, aapt2). `uptime` load averages were **13–23** throughout; a
quiet re-run never became possible in the session. Consequences: absolute times are pessimistic and
noisy (the same Whistle 5 s set gave medians of 599 and 447 ms in two fp32 runs), 1 ORT thread beat
4 threads (§5.3), and the official engine's TTFT swung from 84 ms (first run, load 13) to 596 ms
(load 18) on the same clip. Ratios between engines measured minutes apart are more trustworthy than
any absolute number; treat all RTFs as upper bounds for a Pixel 8 Pro in the pocket **[U]**.

---

## 2. needle, verified against the repository

Clone: `git clone https://github.com/cactus-compute/needle` → HEAD `b4fc11b` "Pin engine 3.3.3",
365 commits, created 2026-02-24, pushed 2026-10-19, 14,373 stars, licence Apache-2.0 (relicensed
from MIT in `b188e11`, 2026-08-17), 6 open issues, no GitHub releases (PyPI releases via
`.github/workflows/release.yaml`, a daily train that tags `vX.Y.Z`; tags v3.0.6 … v3.2.8).

**What it is.** `pyproject.toml`: package `cactus-needle` 3.2.1 (tags lag the file), runtime
dependency `huggingface_hub` only; extras `train` (jax, flax, optax, safetensors, sentencepiece,
whisper-normalizer, soxr), `mic`, `compare` (openai-whisper, moonshine-voice), `mlx`. Tree:
`needle/{agent,environments,model,playground}`, `cli.py`, `platform.py`, `_worker.py`,
`_telemetry.py` — 6,155 lines of Python. `needle/model/` is the JAX/Flax reference
(`architecture.py`, `whistle.py`, `quantize.py`, `export.py` = the `.cact` writer, `finetune.py`,
`whistle_finetune.py`, an MLX port). `needle/agent/whistle.py` is a ctypes wrapper that loads
`libneedle.{so,dylib,dll}` and forwards to `needle_transcribe` / `needle_stream_transcribe_*`; the
two-pass commit logic lives inside the binary.

**Where the engine comes from.** `needle/agent/fetch.py`: `ENGINE_REPOS = {3: "Cactus-Compute/needle3",
"whistle": "Cactus-Compute/whistle"}`, `ENGINE_VERSIONS = {3: "3.3.3", "whistle": "2.0.0"}`; at
first use it downloads `python/cactus_needle-3.3.3-py3-none-<tag>.whl` (a wheel containing only the
shared library) for macOS arm64/x86_64, manylinux/musllinux aarch64/x86_64, Windows. The release
workflow refuses to publish a pin whose engine wheels are not on the Hub
(`fetch.unpublished_engine_wheels()`). `_is_android()` raises on Termux by design: *"no Needle
engine wheel is published for Android (Bionic) … The Android builds ship a static library and the
needle CLI"*. Extension census of the whole git history (`git log --all --name-only`): py 1415,
md 226, txt 124, toml 64, tex 22, … — **no `.c/.cc/.cpp/.h/CMakeLists/.rs/.zig` ever**.

**Maintainer statements.** Issue #118 "Where are the libneedle sources?" (2026-09-10, closed
2026-09-19, HenryNdubuaku): *"The engine source isn't public; the Python package, training and
export code are … a runtime can be written from scratch against the published file …the Linux
archives are built with zig."* Issue #164 (Termux `libm.so.6`), closed 2026-10-18: *"Python inside
Termux isn't a supported target … The Android build is native, and its `needle` CLI is built
against Android's libc, so it should run in Termux as is."*

**Android artefacts** (`huggingface.co/Cactus-Compute/needle3`, Apache-2.0 LICENSE, 152 files):
`android-arm64/{libneedle.a 2,177,676 B, needle 1,586,416 B, needle.h 4,528 B}`, `android-armv7`,
`android-riscv64`; **no android-x86_64** (CleverKeys ships an x86_64 split). `file needle`: *ELF
64-bit PIE, ARM aarch64, interpreter /system/bin/linker64, for Android 24, built by NDK r28c*;
`llvm-readelf -d`: NEEDED `libm.so`, `libdl.so`, `libc.so` only. It ran here unmodified
(`./needle --model whistle.cact --audio clip.wav`). The `.a` is for JNI linking into an app — the
only artefact that matters for the APK question.

**Network / telemetry.** Plaintext scan of `libneedle.a`: no `socket`, `connect`, `getaddrinfo`,
`sendto`, `recv*`, `curl_*`, `SSL_*` references. The `needle` CLI has `socket/bind/listen/accept/
sendto/recvfrom` for its `--serve` loopback server and `http://127.0.0.1` as its only URL string;
no outbound client. The telemetry the README warns about (`NEEDLE_TELEMETRY=0`, `DO_NOT_TRACK=1`)
is implemented in `needle/_telemetry.py` (Python). The model card's "the engine reads no
environment variables" is consistent with this.

**Does it build for Android with the NDK / is it F-Droid-buildable?** There is nothing to build:
no CMake, no Android target, no source. The `.a` and CLI are prebuilt by Cactus (NDK r28c for the
Android folder, zig for Linux per #118). F-Droid's inclusion policy requires binaries to come
from source or an allowed repository (Maven Central, Debian, …); a `libneedle.a` from Hugging Face
is neither. **Not buildable, not includable** in the F-Droid build; usable only in a GitHub-only
build or a companion APK, as the earlier note said.

**How Whistle runs on it.** One engine, one container: `needle_load(bytes, n)` reads a `.cact`
and keeps a text model and a speech model side by side (`needle_models()` returns `NEEDLE_TEXT |
NEEDLE_SPEECH`). Speech API (`needle.h`): `needle_transcribe(pcm, n, language, keywords,
word_timestamps, out, cap)` — ≤ 30 s in one pass, longer audio as consecutive 30 s windows "each
decoded once and cut at its last sentence end"; `needle_stream_transcribe_process(pcm, n, language,
keywords, out, cap)` — *"appends 16 kHz mono float PCM, about one second at a time, re-transcribes
the buffered audio and commits the words two consecutive passes agree on"*;
`needle_stream_transcribe_stop`; `needle_embed`; `needle_complete` with `pcm` for audio → tool
calls. CLI flags: `--audio`, `--audio-language`, `--audio-keywords`, `--audio-word-timestamps`,
`--audio-depth N` (2..8 decoder rungs), `--audio-stream --audio-chunk S`, `--threads N` (default
"the device's fast cores, at most 4"). The `.cact` header pins the geometry (`cact.py` reader: 681
records; `rope_theta` 100000, engram 4 tables × 18432 slots × 128, sites [3, 7]) and the
filterbank, tokenizer and engram tables are records in the file, which is what lets the ONNX
pipeline below read them without any other asset.

**Other cactus-compute repos (gh API, 2026-10-28).** `cactus` (C++, "NOASSERTION" custom licence,
6,165 stars, pushed 2026-09-26): the general source-available engine; it **cannot load a `.cact`**
— issue #813 (open, 2026-09-22) shows `cactus_init` wanting its own bundle directory and the
converter misdetecting the Needle family — so it is not a route to a buildable Whistle either.
`cactus-kotlin`, `cactus-flutter`, `cactus-react-native`: archived, NOASSERTION. `needle-environments`
(Apache-2.0, Python). Community: `afshinm/cactus-needle` (npm; runs the official `needle.wasm`, not
a reimplementation), `geekgineer/needle-rs` (MIT, Rust + WASM, "token-exact with the JAX
reference", Needle 1/2/3 **text** only, no speech path as of 2026-09-19), `andrisgauracs/whistle-esp32`
(Apache-2.0 C99, covered in the earlier note), `mrfakename/whistle-ONNX` (Apache-2.0, used below),
`ManniX-ITA/whistle-GGUF`. Cactus's porting guide ("Porting Needle 3", 2026-09-18) documents the
tensor order, the oracle (float32 reference with quantisation simulated, cosine > 0.999) and invites
ports; it does not cover Whistle's streaming internals.

---

## 3. Whistle's architecture, as it matters for streaming

From `needle/model/whistle.py` (JAX reference, `WhistleConfig`) and the maintainer's front-end
description in issue #169 (2026-10-09), cross-checked against the export's `js/whistle.js` and the
`.cact` header.

| Stage | What it is | Streaming consequence |
| --- | --- | --- |
| Level gain | 99th-percentile RMS of 20 ms blocks → scale so that it is 0.1 (skip if ≤ 1e-6) | Depends on the whole clip; a growing buffer re-scales earlier frames |
| Features | 80 log-mel, 400-sample symmetric Hann, hop 160, 512 FFT, centred zero-padded frames, power spectrum, filterbank **read from the `.cact`** (257 × 80), `log(x + 2^-24)`, then **per-channel mean/variance normalisation over the whole utterance** (`features()`: `(x - mean) / (std + 1e-5)`, ddof 1) | Utterance-level CMVN: no frame has a final value until the clip ends; the first second normalised alone is noisy. Running statistics would change the input distribution the model was trained on |
| Stem | three depthwise 3 × 3 stride-2 convs (pad 1) + pointwise + SiLU → one frame per 80 ms (`T → ceil(T/8)`, padded to even) | Non-causal by one frame per stage (looks one input frame ahead) |
| Encoder | 8 blocks: macaron Hadamard MLP; **bidirectional self-attention** (mask = padding only), GQA 8 q / 2 kv heads, qk 48, v 64, **RoPE θ 100000**; Conformer-style conv module with **depthwise kernel 9, SAME padding** (4 frames each side); 4-lane hyper-connections with Sinkhorn mixing | **Full attention → no state caching.** Every frame attends to every other frame; adding a second of audio changes every encoder output. Cost per pass is O(T²) attention + O(T) rest, re-run from scratch |
| Memory PE | `sigmoid(pe_gate) · sinusoids(frames)` added to the encoder output before the decoder (`pe_gate` ≈ −6.11, "effectively closed" per #169) | Absolute positions of memory frames; harmless when the window start is fixed, but a sliding window shifts them |
| Decoder | 8 laddered blocks (depth 2…8 all trained), width 512, causal self-attention with RoPE and 3 causal conv taps on q/k/v (the `prev [8,2,608]` state), **gated cross-attention over the whole memory at every layer**, engram n-gram memory at layers 3 and 7 (hash of the last 2–3 tokens into 4 tables × 18432 slots × 128, 2-bit) | **AED (attention encoder–decoder)**: no monotonic alignment, no emission as audio arrives. A new memory invalidates the cross-attention K/V, so the transcript must be re-decoded (teacher-forcing a committed prefix is possible and was tested) |
| Output | BOS 2 → language token (8192 + idx, detected from the first step's logits or forced) → text tokens, EOS 1; cap `min(320, 16 + 12·ceil(T/100))`; 8,192 SentencePiece pieces + 7 language tokens | Language decided on the first pass's first step; the stream keeps it |

Verdict: Whistle is an offline full-context model (Whisper-shaped at the encoder, Needle-shaped at
the decoder). Streaming can only be **re-transcription of a window plus a commit rule**, exactly
what Cactus implemented in the engine; the knobs are chunk size, window length and whether the
committed prefix is forced. Moonshine v2 (§6.1) and the Zipformer transducer (§6.2) were designed
for the other answer.

---

## 4. The ONNX export, measured

`mrfakename/whistle-ONNX` (Apache-2.0, 2026-10-05): a PyTorch re-implementation loading the
dequantised `.cact` weights, exported with torch 2.14.1 (`dynamo=False`, `do_constant_folding=False`,
opset 17). README: identical greedy transcripts to the engine on 4/4 reference clips, log-mel max
abs error ≤ 1.1e-4, timestamps 85/92 identical.

| Graph | IR / opset | nodes | op types | inputs → outputs | size |
| --- | --- | --- | --- | --- | --- |
| `encoder.onnx` | 8 / 17 | 5,659 | 32 (Constant 1384, Unsqueeze 689, Gather 573, Mul 507, ReduceLogSumExp 304, MatMul 251, Conv 11, …) | `mel [T,80]` → `cross_k [8,8,S,48]`, `cross_v [8,8,S,64]` | 0.5 MB + 72.1 MB `.data` |
| `decoder.onnx` | 8 / 17 | 4,498 | 28 | `token [1]`, `pos [1]`, `efeat [2,4,512]`, `prev [8,2,608]`, `past_k [8,2,P,48]`, `past_v [8,2,P,64]`, `cross_k`, `cross_v` → `logits [8199]`, `prev_out`, `present_k/v [P+1]`, `xattn [4,8,S]` | 0.4 MB + 73.0 MB |
| `*_fp16.onnx` | 8 / 17 | 7,185 / 5,974 (+1,559 / +1,477 Cast) | same | same | 1.1 + 36.1 MB, 0.9 + 36.5 MB |

Host-side pipeline ported to Python in `whistle_ort.py`: `.cact` reader for the filterbank
(record 679), the tokenizer blob (record 680, 109 kB) and the two engram tables (records 228 and
232, 73,728 × 128, 2-bit, kept packed at 2.4 MB each and dequantised per row on demand with a
cache, as `pack.js` does); mel features; engram hash (`SEED 0x9E3779B9`, `PRIME 0x01000193`,
tables 0–1 order 2, 2–3 order 3, four taps at t − 3j); greedy decode with language ID; the
two-pass commit rule for streaming. First run reproduced the official engine's transcript of
`1320-122617-0000` except one proper noun ("Hawkeye" greedy vs "Hakai" at 5 beams — the greedy one
is the reference word).

**fp16 graphs are unusable on the CPU EP here.** Encoder outputs are finite (abs max 8.98) but the
decoder emits EOS at its first text step on every clip (WER 100 %, "steps 2"). The author validated
them on WebGPU; the Cast-heavy fp16 decoder path on ORT 1.26 CPU/arm64 is not it. Not investigated
further; fp16 compute is not what one wants on an Android CPU anyway.

**Dynamic int8 is the realistic small build.** `quantize_dynamic` on the published graphs quantises
nothing (no MatMul has a constant weight: the export reconstructs every matrix with Gather/Transpose
over layer-stacked initializers). After ORT's offline optimiser at `ORT_ENABLE_BASIC` folds those
(encoder 2,612 nodes, 179 of 251 MatMuls with constant B; the other 72 are attention products),
per-channel QInt8 on MatMul gives **encoder 19.1 MB + decoder 35.9 MB = 55 MB** single files, the
same transcripts on the clips tried (WER 5.36 % vs 5.00 % on the 5 s set, 1.95 % vs 2.27 % on the
15 s set — one word each way), session load 6.5 s → 3.0 s and RSS −100 MB (§5). Script:
`quantize_whistle_int8.py`. The decoder stays at 36 MB because the tied 8,199 × 512 embedding and
engram projections remain fp32 Gather tables.

---

## 5. Measurements

Tables produced by `summarise_results.py` from the JSON results; "RTF" = compute time ÷ audio
seconds (lower is better; 1.0 = real time). Load averages are `uptime` at the end of each run.
Official-engine times are wall-clock minus a 58 ms load floor (`--calibrate` with a 0.2 s silent
clip), so they include process start-up noise; its TTFT and tokens/s are the engine's own.

### 5.1 Batch (whole clip in one pass)

| run | clips | median s | median total ms | median enc ms | median dec ms | median steps | median RTF | max RTF | median TTFT ms | WER | RSS / peak MB | load avg |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **needle CLI (official engine, 5 beams)** 5 s | 21 | 4.8 | 173 | — | — | — | **0.037** | 0.043 | **51** | 14/280 = 5.00 % | 39 (child peak) | 18.0, 17.6, 16.3 |
| needle CLI 15 s | 7 | 14.4 | 534 | — | — | — | **0.036** | 0.037 | 151 | 3/308 = 0.97 % | 48 | 17.1, 17.4, 16.3 |
| needle CLI 5 s, `--audio-depth 4` | 21 | 4.8 | 124 | — | — | — | 0.027 | 0.031 | 45 | 17/280 = 6.07 % | 39 | 16.3, 17.2, 16.2 |
| Whistle ORT fp32, 4 thr, 5 s | 21 | 4.8 | 599 | 180 | 371 | 24 | 0.124 | 0.172 | 239 | 14/280 = 5.00 % | 268 / 289 | 16.8, 15.2, 13.3 |
| Whistle ORT fp32, 4 thr, 5 s (re-run) | 21 | 4.8 | 447 | 129 | 267 | 24 | 0.095 | 0.161 | 180 | 14/280 = 5.00 % | 266 / 287 | 19.9, 18.8, 17.1 |
| Whistle ORT fp32, **1 thr**, 5 s | 21 | 4.8 | 313 | 130 | 173 | 24 | 0.066 | 0.074 | 162 | 14/280 = 5.00 % | 266 / 285 | 15.2, 17.0, 15.8 |
| Whistle ORT fp32, XNNPACK EP, 5 s | 21 | 4.8 | 382 | 150 | 201 | 24 | 0.078 | 0.095 | 188 | 14/280 = 5.00 % | 256 / 288 | 16.1, 17.2, 15.9 |
| Whistle ORT fp32, 4 thr, 15 s | 7 | 14.4 | 2186 | 595 | 1526 | 67 | 0.146 | 0.212 | 722 | 7/308 = 2.27 % | 314 / 347 | 21.1, 16.6, 13.9 |
| Whistle ORT fp32, 4 thr, 15 s (re-run) | 7 | 14.4 | 1530 | 478 | 924 | 67 | 0.097 | 0.112 | 589 | 7/308 = 2.27 % | 358 / 396 | 18.1, 18.5, 17.0 |
| Whistle ORT **int8**, 4 thr, 5 s | 21 | 4.8 | 486 | 143 | 304 | 24 | 0.100 | 0.155 | 230 | 15/280 = 5.36 % | 130 / 180 | 18.9, 18.6, 17.1 |
| Whistle ORT int8, 4 thr, 15 s | 7 | 14.4 | 1009 | 343 | 548 | 67 | 0.068 | 0.072 | 467 | 6/308 = 1.95 % | 264 / 305 | 17.9, 18.4, 17.1 |
| Whistle ORT fp16, 5 s / 15 s | 21 / 7 | | | | | 2 | | | | **100 % (empty)** | | |
| **Moonshine v2 tiny-streaming** (official quantised `.ort`), 5 s | 21 | 4.8 | 364 | 51 (+ frontend) | 258 | 19 | **0.076** | 0.093 | — | 14/280 = 5.00 % | 217 / 220 | 15.6, 16.9, 15.8 |
| Moonshine tiny-streaming, 15 s | 7 | 14.4 | 1554 | 174 | 1227 | 57 | 0.101 | 0.112 | — | 11/308 = 3.57 % | 707 / 720 (arena; 112 without, §5.3) | 16.1, 17.0, 15.9 |
| **sherpa Zipformer 20M int8** (streaming by construction), 5 s | 21 | 4.8 | 643 | 565 | 36 | 15 steps | 0.142 | 0.214 | first token @ 0.96 s audio | 16/280 = 5.71 % | 105 / 115 | 21.0, 18.3, 16.7 |
| Zipformer 20M int8, 15 s | 7 | 14.4 | 2499 | 2234 | 158 | 45 steps | 0.158 | 0.190 | 0.96 s | 3/308 = 0.97 % | 105 / 147 | 22.6, 19.1, 17.1 |

Per-clip spreads (5 s set, total ms sorted): needle 144–224; Whistle fp32 1 thr 244–398; Whistle
fp32 XNNPACK 276–469; Moonshine 290–454; Whistle fp32 4 thr re-run 270–834; Whistle int8 232–668.
Decoder step cost (ORT, 4 thr, under load): Whistle ≈ 11–20 ms per token, Moonshine ≈ 13–21 ms;
the official engine decodes at 134–189 tok/s (5–7 ms per token at 5 beams). Zipformer's 40–58 ms per
320 ms step includes Python overhead for its 36 inputs and my numpy fbank (78–208 ms per clip).

Session load (first `InferenceSession`): Whistle fp32 4.0–7.0 s encoder + 1.2–2.8 s decoder in
Python ORT 1.26, **13.6 s** on the JVM with ORT 1.21.1 (graph optimisation of the un-folded fp32
graph); Whistle int8 1.6–2.0 + 0.7–1.0 s (3.3 s JVM); Moonshine's five `.ort` graphs 0.26–0.78 s
total; Zipformer 0.5–1.1 s. Pre-optimised ORT-format files (what Moonshine ships) are the fix for
the Whistle load time, not a code change.

### 5.2 Streaming and pseudo-streaming

Same commit rule for all ORT runs (words that two consecutive passes agree on are committed; the
surface form of the newer pass is kept), so "first commit", "retractions" (pending words that
changed between passes, as a share of reference words) and WER are comparable. The official engine
commits inside the binary and reports `pass_ms` itself.

| run | clips | chunk s | passes | median pass ms | max pass ms | passes > chunk | first commit (audio + compute) | retractions | WER stream | WER batch | load avg |
|---|---|---|---|---|---|---|---|---|---|---|---|
| needle CLI `--audio-stream`, 5 s | 21 | 1.0 | 75 | 233 | 994 | 0 | 2.0 s + 152 ms | n/a (engine) | 14/280 = 5.00 % | 5.00 % | 15.9, 17.0, 16.2 |
| needle CLI `--audio-stream`, 15 s | 7 | 1.0 | 88 | 460 | 2297 | **16** | 3.0 s + 99 ms | n/a | 4/308 = 1.30 % | 0.97 % | 18.6, 17.6, 16.5 |
| Whistle ORT fp32 pseudo-stream, 5 s | 21 | 1.0 | 112 | 509 | 1198 | 4 | 2.0 s + 376 ms | 43 (15 %) | 16/280 = 5.71 % | 5.00 % | 19.5, 17.4, 15.0 |
| Whistle ORT fp32 pseudo-stream, 15 s | 7 | 1.0 | 118 | **1106** | 2781 | **70** | 2.0 s + 293 ms | 80 (26 %) | 5/308 = 1.62 % | 2.27 % | 18.9, 17.9, 15.5 |
| Whistle ORT fp32, 15 s, **forced committed prefix** | 7 | 1.0 | 118 | 734 | 3093 | 34 | 2.0 s + 186 ms | 75 (24 %) | 4/308 = 1.30 % | 2.27 % | 18.4, 18.0, 15.9 |
| Whistle ORT fp32, 15 s, **0.5 s chunks** | 7 | 0.5 | 230 | 486 | 1483 | 112 | 1.5 s + 106 ms | 148 (48 %) | **15/308 = 4.87 %** | 2.27 % | 16.3, 17.4, 15.9 |
| Whistle ORT **int8** pseudo-stream, 5 s | 21 | 1.0 | 112 | 272 | 590 | 0 | 2.0 s + 184 ms | 41 (15 %) | 16/280 = 5.71 % | 5.36 % | 15.9, 17.4, 16.9 |
| Whistle ORT int8 pseudo-stream, 15 s | 7 | 1.0 | 118 | 577 | 2019 | 23 | 2.0 s + 255 ms | 84 (27 %) | 5/308 = 1.62 % | 1.95 % | 16.6, 17.7, 17.0 |
| Moonshine tiny-streaming, 5 s | 21 | 1.0 | 112 | **200** | 386 | **0** | 2.0 s + 194 ms (first partial @ 1.0 s) | 66 (24 %) | 14/280 = 5.00 % | 5.00 % | 16.9, 17.1, 16.0 |
| Moonshine tiny-streaming, 15 s | 7 | 1.0 | 118 | 307 | 1328 | 5 | 2.0 s + 183 ms | 63 (20 %) | 10/308 = 3.25 % | 3.57 % | 17.0, 17.1, 16.0 |
| Moonshine tiny-streaming, 15 s, 0.5 s chunks | 7 | 0.5 | 230 | 255 | 2356 | 39 | 1.5 s + 184 ms | 145 (47 %) | 13/308 = 4.22 % | 3.57 % | 18.9, 17.7, 16.4 |
| Zipformer 20M int8 (true streaming) | 28 | 0.32 | one step per 320 ms | 41–49 | 58 | 0 | first token @ 0.96 s audio + ≈ 45 ms | **0** | 5.71 % / 0.97 % | same | 21–23 |

Per-pass detail on one 14.05 s clip (`1320-122617-0001`), pass time in ms per 1 s chunk received:

- **Whistle fp32 pseudo-stream**: 283, 423, 735, 991, 1015, 1304, 1373, 1304, 1606, 1945, 1728,
  1313, 1300, 1525, 1549 — encoder 95 → 923 ms and decoder 177 → 1085 ms (6 → 63 steps) both grow
  with the buffer. From the fifth second each pass takes longer than the second of audio it covers.
- **Whistle fp32, committed prefix forced**: 97, 164, 247, 336, 417, 451, 694, 871, 762, 846, 1062,
  1692, 1034, 1150, 1103 — ~35 % less, still growing (the encoder pass and the per-step decoder
  cost remain; only the argmax/branching disappears).
- **Official engine `--audio-stream`**: 70, 125, 187, 236, 262, 250, 353, 422, 574, 573 — the same
  shape at a third of the cost (and the engine skips passes while it is behind: 10 passes for 14 s).
- **Moonshine tiny-streaming**: 116, 183, 192, 249, 246, 228, 340, 283, 306, 351, 892, 1328, 1204,
  338, 161 — encoder work **flat at 13–68 ms** (incremental sliding window), the growth is the
  decoder re-decoding a longer transcript (72 → 1,234 ms at 31–42 steps when the speculative prefix
  diverged); endpointing or a cap on re-verified tokens bounds it.
- **Zipformer**: 40–58 ms per 320 ms step from the first step to the last, independent of length.

Quality: at 1 s chunks the pseudo-stream WER equals batch WER within one or two words for every
engine (the agreement rule occasionally fixes a batch error: 5 vs 7 for Whistle, 10 vs 11 for
Moonshine). At **0.5 s chunks the rule commits too early**: Whistle's WER doubles (4.87 % vs 2.27 %)
and half the words are retracted at least once; Moonshine degrades less (4.22 % vs 3.57 %). One
second is the floor for two-pass agreement with these decoders. The first committed word arrives at
2.0 s of audio for both AED engines (two passes needed), at 1.0 s as a *partial* for Moonshine, and
at 0.96 s as a *final* token for the transducer.

#### 5.2.1 Whistle int8 pseudo-stream

The int8 graphs halve the pseudo-stream cost (median pass 272 ms vs 509 ms on the 5 s set, 577 vs
1106 ms on the 15 s set; 23 instead of 70 passes over their 1 s budget) at the same WER and the
same retraction rate, with RSS 174 MB (5 s) / 273 MB (15 s) arena-on. On the 14.05 s reference
clip the per-pass times were 173, 210, 289, 347, 385, 572, 668, 717, 616, 907, 788, 620, 721, 817,
1078 ms — the same linear growth, now crossing the 1 s budget at the last pass instead of the fifth.
With a sliding window cut at committed sentence ends (what the official engine does at 30 s) and
the committed prefix forced, int8 Whistle on ORT would keep real time on this box even under its
current load **[U]** (not run as one configuration).

### 5.3 Memory, threads, execution providers

- **ORT's CPU memory arena inflates RSS with streaming.** Every pass has a new input shape (longer
  memory, longer token prefix), and the BFC arena never returns high-water blocks. Measured on the
  23.3 s clip streamed in 1 s chunks: Moonshine **508 MB (peak 527) with the arena, 112 MB (peak
  238) with `enable_cpu_mem_arena = false`**; Whistle fp32 217 MB (peak 285) arena off vs 321–358 MB
  arena on; Whistle int8 **145 MB (peak 199)** arena off vs 264 MB on. An IME process should run
  these sessions with the arena off (or with `memory.enable_memory_arena_shrinkage` run options).
  Subtract ≈ 45 MB of Python/numpy baseline for a JNI estimate. The official 2-bit engine peaks at
  39–49 MB as a whole process.
- **Threads.** With the box this loaded, Whistle fp32 at **1 intra-op thread was faster than at 4**
  (median 313 vs 447–599 ms on the 5 s set; max RTF 0.074 vs 0.16–0.17) — spin-waiting threads lose
  to the competing JVMs. On a phone with the IME in the foreground the right count is likely 2
  **[U]**; make it a setting and measure on the Pixel.
- **EPs on ORT 1.26 (Python).** XNNPACK registered and took part of the fp32 Whistle encoder (382 vs
  447–599 ms median, same transcripts); NNAPI registered from Termux and produced bit-identical
  encoder outputs (abs max 8.829 both ways) at no speed-up — from a Termux process it has no HAL
  partition to speak of **[U]**; on a real device the int8 graph's `MatMulInteger`/
  `DynamicQuantizeLinear`/`ReduceLogSumExp` mix is a poor NNAPI fit and the app's existing order
  (XNNPACK → NNAPI → CPU in `onnx/ModelLoader.kt`) is right for the CPU-bound reality.

### 5.4 Compatibility with the APK's ORT 1.21.1 (JVM probe)

`OrtCompatCheck.java`, compiled against `onnxruntime-1.21.1.jar` from the Gradle cache and run
with `-Donnxruntime.native.path=build/ort-native/arm64-v8a` (the bionic `libonnxruntime.so` +
`libonnxruntime4j_jni.so` unpacked from `onnxruntime-android-1.21.1.aar` by the project's
`extractOrtNative` task). ORT reported `1.21.1`, providers `[CPU, NNAPI, XNNPACK]`. One dummy
inference per graph, first run after load (so slow), load 17–22:

| graph set | CPU EP | XNNPACK + CPU |
| --- | --- | --- |
| Whistle fp32 encoder (500 frames) + one decoder step | load 13,646 ms, run 418 ms, `cross_k [8,8,63,48]`, `logits [8199]` | load 12,874 ms, run 433 ms, same shapes |
| Whistle int8 encoder + decoder step | load 3,280 ms, run 274 ms | load 2,218 ms, run 223 ms |
| Moonshine official `.ort` × 5 (frontend, encoder, adapter, cross_kv, decoder_kv) | load 778 ms, run 207 ms, `features [1,50,320]`, `logits [1,1,32768]` | load 364 ms, run 55 ms |
| Moonshine plain-ONNX int8 × 5 (Masterx export) | load 1,421 ms, run 71 ms | load 1,057 ms, run 71 ms |
| Zipformer 20M int8 encoder step (36 inputs) | load 1,073 ms, run 84 ms, `encoder_out [1,8,512]` | load 1,643 ms, run 604 ms |

**Every candidate loads and runs on the app's exact ORT version and native library**, including
ORT-format `.ort` files and the int8 graphs; no ORT upgrade is needed for any of them. (The
Whistle graphs are IR 8 / opset 17; ORT 1.21 supports IR ≤ 10, opset ≤ 21.)

---

## 6. The comparison engines

### 6.1 Moonshine v2 "tiny-streaming" (moonshine-ai, MIT)

Paper: *Moonshine v2: Ergodic Streaming Encoder ASR for Latency-Critical Speech Applications*
(arXiv 2602.12241, Feb 2026). Tiny = 33.57 M parameters (preprocessor 2.08 M, encoder 7.39 M,
adapter 1.31 M, decoder 22.80 M), enc/dec width 320, 6/6 layers. Front end: 80-sample (5 ms)
windows, per-frame CMVN, `asinh`, two causal stride-2 convs → 50 Hz features (no right context).
Encoder: Transformer with **sliding-window attention, no positional embeddings** ("ergodic"):
windows (16 left, 4 right) in the first two and last two layers, (16, 0) in the middle; algorithmic
lookahead 80 ms, finalisation bound 16 frames = 320 ms (`total_lookahead` in `streaming_config.json`).
Adapter adds a learned absolute PE (4,096 positions) and projects to the decoder; decoder is a causal
Transformer with RoPE and cross-attention (AED, so partials are re-decoded like Whistle's, but over
an encoder memory that only *grows*). Published WER (Tiny): LibriSpeech clean 4.49, other 12.09,
Open ASR average 12.01; response latency after a VAD endpoint 50 ms on an Apple M3.

Runtime (`moonshine-ai/moonshine`, `core/moonshine-streaming-model.cpp`, MIT except
`core/third-party`): five ONNX/ORT graphs — frontend with five carried states; encoder run on a
window of `16·depth = 96` past frames + new frames, keeping only frames older than the lookahead;
adapter with a running `pos_offset`; `cross_kv` over the whole memory; `decoder_kv` that re-verifies
the previous tokens in one multi-token call (speculative prefix) and continues greedily with a self
K/V cache. Official assets on HF `moonshine-ai/moonshine-voice-assets` (`license: other` = per-model;
the LICENSE file says all streaming STT models are MIT): `model/tiny-streaming-en/quantized_26_08_21/`
= adapter 1.3 MB, cross_kv 1.3 MB, decoder_kv 32.6 MB, encoder 7.7 MB, frontend 2.1 MB, tokenizer
0.25 MB ≈ **45 MB** (51 MB as measured with the single-file frontend). Languages with streaming
tiny models: en, de, es, ja, zh, ar, vi, tl (one model each; HF `moonshine-ai/moonshine-streaming-*`).
`moonshine_stream_ort.py` reproduces the runtime in Python against the official `.ort` files (the
26_08_21 split frontend needs its weights fed as inputs; the plain-ONNX frontend from the Masterx
export of the same recipe was used instead — identical shapes and output).

### 6.2 sherpa-onnx streaming Zipformer transducer (k2-fsa, Apache-2.0)

`sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` from icefall
`pruned_transducer_stateless7_streaming` "small" (20 M parameters): encoder int8 32.8 MB (fp32 78.5
MB), decoder 2.1 MB, joiner int8 0.26 MB, 500 BPE tokens; published greedy WER test-clean 3.94 /
test-other 9.79 (model card). The encoder graph takes `x [1,39,80]` (32 new 10 ms frames + 7 of
right context, `decode_chunk_len=32`) and 35 state tensors (cached_len/avg/key/val/val2/conv1/conv2
for five stacks, left contexts 64/32/16/8/32) and returns 8 encoder frames (40 ms each) plus new
states — a **true streaming model**: constant per-step cost, tokens emitted by greedy transducer
search as they appear, nothing retracted. `sherpa_zipformer_ort.py` drives the three graphs with a
numpy Kaldi fbank (povey window, pre-emphasis, 20 Hz–7,600 Hz, no int16 scaling — the model expects
[-1, 1] floats; getting that wrong produced blank output); the measured WER (5.71 % / 0.97 %) says
the port is faithful enough. English only in this model; sherpa publishes other streaming
Zipformers (zh, zh-en, ko, …) and runs on Android with its own ORT build, or the graphs run on the
app's ORT as shown in §5.4.

### 6.3 The official Cactus engine (for reference)

`android-arm64/needle`, 5 beams by default: batch RTF 0.036–0.043 (net of a 58 ms load floor), TTFT
51 ms on 5 s clips and 142–262 ms on 13–23 s clips, 134–189 tokens/s, 39–49 MB peak RSS, WER
5.00 % / 0.97 % (best of all runs on the 15 s set); `--audio-depth 4` saves 30 % time for +1 point
WER. Its streaming grows per pass like the ONNX pseudo-stream (70 → 574 ms over 14 s) and 16 of
88 passes exceeded their 1 s chunk on the 15 s set under this load. It is the yardstick for what a
FLOSS Whistle runtime would have to match: about 3× the ORT fp32 speed at a quarter of the RAM.

---

## 7. Conclusions

### 7.1 Is true streaming feasible with Whistle on ORT?

**No — only windowed pseudo-streaming.** Full-attention encoder, non-causal conv, utterance-level
CMVN and an AED decoder mean there is no encoder state to cache and no monotonic emission; every
chunk re-runs encoder and decoder over the window. That is what Cactus's own engine does
(`needle_stream_transcribe_process`), and it reproduces on the ONNX export with the same quality
(stream WER = batch WER at 1 s chunks). The cost is structural: pass time ∝ buffered audio, so a
30 s dictation needs a sliding window (Cactus cuts at 30 s at a sentence end; a keyboard would cut
at every committed sentence or at ~8–10 s) and the committed prefix should be teacher-forced (−35 %
pass time here). Under this box's load the fp32 ORT pipeline fell behind real time from the fifth
second; int8 and a quiet phone help, but the growth remains. Chunks shorter than 1 s break the
two-pass rule (WER ×2).

### 7.2 Latency / quality trade-off

| | first word | partial feedback | per-chunk cost (1 s chunks, 14 s clip, this box) | retractions | WER vs batch |
| --- | --- | --- | --- | --- | --- |
| Whistle ORT pseudo-stream | 2.0 s + 0.2–0.4 s | pending tail every pass | 0.28 s → 1.9 s (fp32) / 0.1 → 1.7 s forced | 15–26 % of words | equal at 1 s; ×2 at 0.5 s |
| Official engine stream | 2–3 s + 0.1 s | same | 0.07 s → 0.57 s | engine-side | equal |
| Moonshine tiny-streaming | 2.0 s + 0.2 s (partial at 1.0 s) | partial every pass | 0.12 s → 0.35 s (encoder flat; decoder spikes to 1.3 s on long re-decodes) | 20–24 % | equal |
| Zipformer transducer | 0.96 s + 0.05 s | final tokens as they come | 0.04–0.06 s per 0.32 s, constant | 0 | n/a (it is the batch) |

For dictation into a text field, retractions are the user-visible cost: 15–26 % of words change at
least once in the pending tail before they are committed, which is why the earlier note's
`setComposingText` tail + `commitText` commit design is right for both AED engines, and why a
transducer (no retractions, half the first-word latency) is the better *fit* when its accuracy is
acceptable. Accuracy on LibriSpeech is a wash between the three at this sample size (280 + 308
words; differences of 1–3 words); published numbers favour Whistle on test-other (10.49 vs
Moonshine tiny 12.09 vs Zipformer 9.79).

### 7.3 Engine choice for CleverKeys v2.x

**Moonshine v2 tiny-streaming, on the app's ORT.** Reasons, in order: (1) F-Droid-clean end to
end — MIT weights and MIT runtime to copy from, graphs that load on `onnxruntime-android:1.21.1`
today; (2) built for the IME use case — incremental encoder, 1.0 s to a partial, 2.0 s to a commit,
bounded per-chunk cost, 112 MB steady RSS with the arena off, 45–51 MB of pack; (3) an official,
maintained five-graph runtime (C++, Android in the same repo) whose logic `moonshine_stream_ort.py`
reproduces in ~300 lines, so the Kotlin port is a transcription job, not research; (4) English
first matches where the keyboard's users are, with per-language streaming tiny models (de, es, ja,
zh, ar, vi, tl) as further packs — the pack format already fits one model per language.

**Keep Whistle as the multilingual-pack candidate, not the v2.x engine.** Its case is bytes: 17 MB
for seven European languages versus ~45 MB per Moonshine language. Through FLOSS ORT that advantage
disappears (55 MB int8 / 145 MB fp32 on disk, 130–360 MB RSS, 3–14 s load) and the AED
pseudo-stream is the slowest of the three. It becomes attractive again if (a) someone publishes a
FLOSS `.cact` runtime with speech (needle-rs has the container and the text model, not Whistle), or
(b) Cactus opens the engine. Revisit then.

**sherpa Zipformer** is the fallback if the Moonshine port stalls or if retractions prove
unacceptable in practice: true streaming, 35 MB int8, Apache-2.0, but English-only at this size,
a 2023 model, Kaldi-fbank plumbing and 36 state tensors per step to carry in Kotlin.

### 7.4 Android integration cost

- **ORT**: none for compatibility — all graphs run on 1.21.1 CPU/XNNPACK (§5.4). Ship ORT-format or
  offline-optimised graphs to avoid multi-second first loads (Whistle fp32: 13.6 s on the JVM;
  Moonshine `.ort`: 0.8 s for five graphs). Create sessions with `enable_cpu_mem_arena = false` for
  streaming sessions (§5.3) and an intra-op thread count that is a setting (1–2 likely **[U]**).
- **Model size**: Moonshine tiny-streaming-en 45–51 MB; Whistle int8 55 MB (+17 MB `.cact` for
  filterbank/tokenizer/engram, or extract those three records at import, ≈ 5 MB); Zipformer 35 MB.
  All exceed the language-pack 8 MiB model cap → the voice-pack kind needs its own cap and
  free-space check, as the earlier note's §3.1 said.
- **Code**: Moonshine — frontend state carry, window/lookahead bookkeeping (`total_lookahead` 16,
  left context 96 frames), `pos_offset`, cross-K/V refresh, speculative-prefix decode, SentencePiece
  decode from `tokenizer.json`/`tokenizer.bin`, plus VAD/endpointing to bound re-decodes. Whistle —
  `.cact` record reader, mel + CMVN + level gain, engram hash and 2-bit row dequant, greedy decode
  with LID, window-cut logic. Zipformer — Kaldi fbank, 35-tensor state carry, transducer greedy
  search. The IME half (RECORD_AUDIO activity, AudioRecord, composing/commit policy, REMOTE/terminal
  append-only, TalkBack) is unchanged from the earlier note and is engine-independent.
- **EPs**: CPU-bound in practice; XNNPACK takes a slice of the fp32 Whistle encoder, nothing
  measurable elsewhere; NNAPI is not worth wiring for these graphs **[U]** (no device partition test
  yet — do it on the Pixel with `ModelLoader`'s existing order and look at the ORT verbose log).
- **x86_64 split**: all three ORT routes cover it; the Cactus binary does not.

### 7.5 What this spike did not do

Quiet-box measurements (load 13–23 throughout; expect every absolute number to improve on an idle
Pixel); Whisper-normalised WER; more than 28 clips or any non-English clip; battery/thermal; an APK
build (the JVM probe used the APK's ORT natives, not an Android process); NNAPI on a real HAL;
keyword biasing in the pseudo-stream; a Kotlin port of any pipeline.

---

## 8. Reproduce

```sh
STT=<scratch>/stt   # models/, audio/, results/ — nothing under the repo
python3 scripts/research/stt/fetch_librispeech.py --out $STT/audio --rows 120
# models: huggingface.co/mrfakename/whistle-ONNX (onnx/*), Cactus-Compute/whistle (whistle.cact),
#   Cactus-Compute/needle3 (android-arm64/needle, needle.h), moonshine-ai/moonshine-voice-assets
#   (model/tiny-streaming-en/quantized_26_08_21/*), Masterx/moonshine-streaming-tiny-ONNX
#   (frontend.onnx, tokenizer.json), github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
#   (sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile.tar.bz2)
python3 scripts/research/stt/quantize_whistle_int8.py --onnx-dir $STT/models/whistle-onnx/onnx --out-dir $STT/models/whistle-onnx/onnx-int8
echo 6930-75918-0002,... > $STT/ids5.txt; echo 1320-122617-0001,... > $STT/ids15.txt   # ids in the result JSONs
STT=$STT scripts/research/stt/run_all.sh            # all steps, sequential, uptime-stamped
python3 scripts/research/stt/summarise_results.py $STT/results
JAR=$(ls ~/.gradle/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime/1.21.1/*/onnxruntime-1.21.1.jar)
javac -cp "$JAR" -d $STT/ortcheck scripts/research/stt/OrtCompatCheck.java
java -Donnxruntime.native.path=build/ort-native/arm64-v8a -cp "$JAR:$STT/ortcheck" OrtCompatCheck $STT/models
```

## Sources

needle: https://github.com/cactus-compute/needle (clone, HEAD b4fc11b) · issues #118, #164, #168,
#169, #179, #180 · `needle/agent/fetch.py`, `needle/agent/whistle.py`, `needle/model/whistle.py`,
`.github/workflows/release.yaml` · https://huggingface.co/Cactus-Compute/needle3 (tree, README,
`android-arm64/needle.h`) · https://huggingface.co/Cactus-Compute/whistle (README, config.json) ·
https://cactuscompute.com/blog/whistle-best-practices · https://cactuscompute.com/blog/porting-needle ·
https://github.com/cactus-compute/cactus/issues/813 · https://github.com/geekgineer/needle-rs ·
gh API listing of the cactus-compute org (2026-10-28).

ONNX export: https://huggingface.co/mrfakename/whistle-ONNX (README, `js/whistle.js`, `js/pack.js`,
`scripts/cact.py`, `scripts/tok.py`, `scripts/export_onnx.py`).

Moonshine: https://arxiv.org/html/2602.12241v1 · https://github.com/moonshine-ai/moonshine
(`LICENSE`, `core/moonshine-streaming-model.{h,cpp}`) · https://huggingface.co/moonshine-ai/moonshine-voice-assets ·
https://huggingface.co/moonshine-ai/moonshine-streaming-tiny · https://huggingface.co/Masterx/moonshine-streaming-tiny-ONNX.

Zipformer: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models ·
https://huggingface.co/desh2608/icefall-asr-librispeech-pruned-transducer-stateless7-streaming-small.

Audio: https://huggingface.co/datasets/openslr/librispeech_asr via datasets-server (test-clean).
