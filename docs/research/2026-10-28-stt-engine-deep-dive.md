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
4. **Whisper** (added 2026-11-05, §6.4): measured on the same clips with a source-built whisper.cpp.
   Its 30 s fixed encoder window makes the best config (tiny.en q8_0, 44 MB) cost ~3.4× Moonshine
   per 1 s pass on short dictation; shrinking the window makes it loop. Moonshine stays the pick.
5. **Follow-up** (2026-11-05, §6.5, every engine in §6.6, phones in §6.7): FUTO's ACFT checkpoints fix Whisper's
   shortened window, so ACFT tiny.en costs no more per pass than Moonshine tiny, but it retracts
   more. ACFT small.en is the most accurate model measured (tied with Moonshine small), but it is a
   252 MiB single file, about twice the 128 MiB per-file voice-pack cap; its only quantisation that
   fits (q3_k) doubles the long-dictation errors and runs 2.6–2.9× slower (§6.5.6). **Moonshine small-streaming**
   is the accuracy upgrade that fits the caps and the app's ORT: 10 vs 14 errors (5 s set), 6 vs 11
   (15 s) and 43 vs 83 on 30–72 s dictation, at 3.2× the bytes and about 2× the per-pass time. The app's 20 s
   cut is essential: without it Moonshine falls to 29–63 % WER on long clips. Two app findings: a
   stop that left under 80 samples failed the final pass (fixed, with a test), and the
   CPU EP with the arena off (the app's setting) runs the decoder 2–2.5× slower than arena on or
   XNNPACK; on a Saga that is 1.7–1.8× per pass (§6.7). No qualifying model was released in the past week.
   **On a phone** (§6.7, Saga): small keeps the 1 s cadence (387 ms median pass as shipped, 226 ms with
   XNNPACK) and through a real microphone makes 9–10 errors in 228 words against tiny's 18–19, for
   ~0.1 s more lag and ~150–200 MB more keyboard memory. It is published as an optional pack.

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

### 6.4 Whisper via whisper.cpp (added 2026-11-05)

The earlier note screened Whisper on paper (§5 there) and this dive did not measure it; an
outside review called that a gap, fairly. Filled here on the **same 28 clips, normaliser and
commit rule**: `whisper.cpp` 4da27c4 (2026-11-03) built from source on this box
(`cmake -DGGML_OPENMP=OFF`, NEON/DOTPROD/MATMUL_INT8 on), the official ggml checkpoints from
`huggingface.co/ggerganov/whisper.cpp`, greedy decoding (`-bs 1 -bo 1`, temperature fallback left
on), 4 threads, `-l en`. Harness `scripts/research/stt/whisper_cpp_bench.py`; one `whisper-cli`
process per clip or pass, timed by whisper.cpp's own `whisper_print_timings` as total − load (mel +
encode + decode, no process start or model load — what a resident JNI context would pay); RSS =
the child's `ru_maxrss` (whole native process, no Python baseline to subtract). Load average
**10–18** during these runs, i.e. a little quieter than the Oct 28 runs, which favours Whisper.

**Batch** (median over clips of the min of 2 repeats):

| model | file MB | 5 s WER | 15 s WER | 5 s median compute ms (enc) | 5 s RTF med / max | 15 s RTF med | peak RSS MB |
|---|---|---|---|---|---|---|---|
| tiny.en q5_1 | 32.2 | 20/280 = 7.14 % | 9/308 = 2.92 % | 1296 (1185) | 0.266 / 0.315 | 0.100 | 101 |
| **tiny.en q8_0** | 43.6 | 21/280 = 7.50 % | 9/308 = 2.92 % | **683** (579) | 0.142 / 0.178 | 0.055 | 112 |
| tiny.en f16 | 77.7 | 21/280 = 7.50 % | 9/308 = 2.92 % | 1516 (1369) | 0.317 / 0.463 | 0.117 | 147 |
| base.en q5_1 | 59.7 | 16/280 = 5.71 % | 7/308 = 2.27 % | 2916 (2741) | 0.618 / 0.708 | 0.220 | 150 |
| **base.en q8_0** | 81.8 | 13/280 = 4.64 % | 7/308 = 2.27 % | 1319 (1153) | 0.275 / 0.489 | 0.106 | 171 |
| base.en f16 | 148.0 | 13/280 = 4.64 % | 7/308 = 2.27 % | 3509 (3294) | 0.725 / 0.879 | 0.258 | 237 |
| tiny q5_1 (multilingual) | 32.2 | 27/280 = 9.64 % | 12/308 = 3.90 % | 1304 (1184) | 0.268 / 0.320 | 0.100 | 102 |
| base q5_1 (multilingual) | 59.7 | 17/280 = 6.07 % | 4/308 = 1.30 % | 2917 (2730) | 0.608 / 0.723 | 0.218 | 150 |
| tiny.en q5_1, 1 thread | 32.2 | 7.14 % | 2.92 % | 4540 (4316) | 0.949 / 1.121 | 0.338 | 100 |
| tiny.en q5_1, `-ac` scaled | 32.2 | **379/280 = 135 %** | 30/308 = 9.74 % | 261 (157) | 0.053 / 1.230 | 0.051 | 76 |
| *Moonshine tiny-streaming (§5.1)* | *51.2* | *14/280 = 5.00 %* | *11/308 = 3.57 %* | *364 (51)* | *0.076 / 0.093* | *0.101* | *112 steady, arena off* |

- **The encoder cost is fixed, not proportional.** Whisper pads every input to its 30 s window
  (1,500 encoder frames), so a 5 s clip costs the same encoder time as a 23 s one (tiny.en q8_0
  ≈ 580 ms on both sets; base.en q5_1 ≈ 2.7 s). That is why the 5 s RTFs are 2.5–3× the 15 s ones,
  and why dictation (short utterances) is Whisper's worst case. Moonshine's encoder spends 51 ms
  on the same clips.
- **q8_0 is the fast format on this CPU**, about 2× q5_1 and f16 at identical WER (int8 dot-product
  kernels vs the 5-bit unpack); q5_1 only saves bytes.
- **The audio-context workaround is not usable.** `-ac ceil(s·50)+16` (the knob whisper.cpp's
  `stream` example and several mobile ports use) cut the median encoder time 7.5× on the 5 s set and 2.4× on the 15 s set, but on 5 of 21 short clips
  the decoder looped ("This thought, however, did not enter the heads of the enthusiastic pair. This
  thought, however, …" ×N), WER 135 %. Whisper was trained only on 30 s windows; fine-tuned
  short-context checkpoints exist upstream as research but are not the official weights **[U]**.
- **Accuracy at this sample size**: tiny.en is 6–7 words worse than Moonshine on the 5 s set and
  2 better on the 15 s set; base.en q8_0 is 1 word better than Moonshine on 5 s and 4 better on
  15 s, at 3.6× Moonshine's compute and 1.6× its bytes. All within a few words on 588 reference
  words — no claim of a quality difference either way is supported by these clips.
- Threads: unlike Whistle on ORT (§5.3), whisper.cpp scaled with threads under this load (1 thread
  3.5× slower than 4).

**Pseudo-stream** (1 s chunks, same two-pass commit rule as §5.2; full 30 s context unless noted):

| run | passes | median pass ms | max pass ms | passes > 1 s | first commit | retractions | WER stream / batch |
|---|---|---|---|---|---|---|---|
| tiny.en q8_0, 5 s | 112 | 675 | 2011 | 7 | 2.0 s + 654 ms | 43 (15 %) | 22/280 = 7.86 % / 7.50 % |
| tiny.en q8_0, 15 s | 118 | 776 | 2264 | 10 | 2.0 s + 660 ms | 44 (14 %) | 15/308 = 4.87 % / 2.92 % |
| tiny.en q5_1, 5 s | 112 | 1287 | 2477 | **112 (all)** | 2.0 s + 1.26 s | 70 (25 %) | 19/280 = 6.79 % / 7.14 % |
| tiny.en q5_1, 15 s | 118 | 1382 | 2725 | **118 (all)** | 2.0 s + 1.24 s | 29 (9 %) | 15/308 = 4.87 % / 2.92 % |
| base.en q5_1, 5 s | 112 | 3264 | **15,954** | **112 (all)** | 2.0 s + 3.7 s | 24 (9 %) | 19/280 = 6.79 % / 5.71 % |
| base.en q5_1, 15 s | 118 | 3092 | 4583 | **118 (all)** | 2.0 s + 2.8 s | 66 (21 %) | 7/308 = 2.27 % / 2.27 % |
| tiny.en q5_1 `-ac` scaled, 5 s / 15 s | 112 / 118 | 228 / 617 | 6193 / 6230 | 25 / 25 | 2.0 s + 0.13–0.20 s | 186 % / 78 % | 116 % / 4.22 % |
| *Moonshine tiny-streaming (§5.2)* | *112 / 118* | *200 / 307* | *386 / 1328* | *0 / 5* | *2.0 s + 194 ms (partial @ 1.0 s)* | *24 % / 20 %* | *5.00 % / 3.25 %* |

Per pass on the 14.05 s reference clip `1320-122617-0001`: tiny.en q8_0 623, 661, 870, 716, 688, 687,
700, 732, 820, 806, 765, 798, 777, 893, 880 ms — **flat from the first second**, the opposite shape
to Whistle (§5.2: grows with the buffer), because the 30 s encoder pass dominates and does not
depend on how much audio is in it. That makes Whisper's first-second cost its *worst* relative to
the others (660 ms vs Moonshine's 116 ms), while the 30 s cap bounds the end. base.en's 8–16 s
outliers are passes where decode time was 2–4× encode time on 3–5 s buffers, consistent with
whisper.cpp's temperature fallback re-decoding a failed segment **[U]** (fallback counts were not
logged per pass). The tiny.en q8_0 15 s stream lost 6 words vs batch (4.87 % vs 2.92 %) where
Moonshine gained one; one config, small sample.

**Verdict.** Measured, Whisper does not displace Moonshine for in-keyboard dictation: the best
Whisper configuration (tiny.en q8_0, 44 MB) needs ~3.4× Moonshine's per-pass compute (675 vs
200 ms median on the 5 s set), its first commit is 0.45 s later, and its only fix for the fixed
30 s encoder (`-ac`) breaks decoding. base.en buys back at most a few words of accuracy at 10–16× the
per-pass cost of Moonshine and cannot keep a 1 s cadence on this box. It would also be a **second
native runtime** (ggml, one `.so` set per ABI, NDK build in the F-Droid recipe) next to the ORT the
app already ships. Where Whisper is the stronger option: **offline transcription of long
recordings** (the 30 s window is then amortised: RTF 0.055 for tiny.en q8_0 on the 15 s set) and
**language coverage** (99 languages in one multilingual checkpoint vs Moonshine's per-language
models); the multilingual tiny was the least accurate English model measured. Not measured:
Whisper exported to ONNX and run on the app's ORT (sherpa-onnx ships such exports; same 30 s
encoder, so the same shape is expected **[U]**), beam search, battery, any non-English clip.

### 6.5 Follow-up 2026-11-05: Whisper's audio-context fix, larger Moonshine, long dictation

Requested after §6.4: longer clips, a better Moonshine model, quantisation or fine-tune, a fix
for Whisper's `-ac` looping, ACFT small.en, and a check for releases in the past week. Same box,
clips, normaliser, commit rule and harnesses as §6.4; load average **11–18** throughout. Every
number in this section comes from one series of runs (2026-11-04 22:00 → 2026-11-05 00:50 EST),
so rows within it compare with each other. The Oct 28 rows in §5 were measured under a different
load **and, for Moonshine on the CPU EP, a different arena setting** (see the arena finding
below); do not mix absolute times across the two series.

**Long clips.** LibriSpeech test-clean has no utterance over 23.3 s. `make_long_clips.py` joins
consecutive utterances of one chapter (same reader, continuous text) with 0.3 s of silence,
which is below the app's 0.6 s endpointing hangover, so the app would see one segment up to its
engine bound. That is the worst case for an engine. Ten clips: six of 30.6–47.0 s and four of
61.6–71.8 s, 470 s and 1,260 reference words in total. `segmenting.py` copies the app's
forced-cut rule (`SpeechSegmenter`: at the bound, cut after the quietest 32 ms hop of the last
2 s) for the `--max-segment` streaming runs.

#### 6.5.1 Whisper: ACFT fixes `-ac`, plain weights need a floor

FUTO's **audio-context fine-tune (ACFT)** checkpoints (`futo-org/acft-whisper-*` on Hugging Face,
Apache-2.0; ggml q8_0 at `voiceinput.futo.org/VoiceInput/{tiny_en,base_en,small_en,tiny}_acft_q8_0.bin`)
are trained to decode from a shortened encoder window, `n_ctx = round(50·s) + 8`. With them
`-ac` works. On the stock weights only a floor on the window avoided the looping. In the sweep
(tiny.en q8_0, 5 s set, 1 repeat) a token cap did not fix it: auto 204 % WER, auto+128 23.6 %,
auto+256 12.1 %, floor 512 12.5 %, **floor 750 7.86 %**, auto with `-mt` = ceil(s·6)+8 52.5 %.
The `-mt` flag needs a 3-line patch to `examples/cli/cli.cpp` (`wparams.max_tokens`), which is
recorded in §8.

| model (q8_0) | file MB | context | 5 s WER | 15 s WER | 5 s median compute ms (enc) | 15 s median compute ms | 5 s RTF med / max | peak RSS MB |
|---|---|---|---|---|---|---|---|---|
| tiny.en | 43.6 | full 1500 | 21/280 = 7.50 % | 9/308 = 2.92 % | 683 (579) | — | 0.142 / 0.178 | 112 |
| tiny.en | 43.6 | floor 750 | 22/280 = 7.86 % | 11/308 = 3.57 % | 306 (210) | 502 | 0.063 / 0.077 | 96 |
| **ACFT tiny.en** | 43.6 | auto+8 | 23/280 = 8.21 % | 9/308 = 2.92 % | **131 (48)** | 417 | 0.028 / 0.033 | 87 |
| ACFT tiny.en | 43.6 | full 1500 | 22/280 = 7.86 % | 10/308 = 3.25 % | 691 (586) | 844 | 0.142 / 0.192 | 111 |
| ACFT tiny (multilingual) | 43.5 | auto+8 | 26/280 = 9.29 % | 12/308 = 3.90 % | 135 (50) | 431 | 0.029 / 0.033 | 87 |
| ACFT base.en | 81.8 | auto+8 | 20/280 = 7.14 % | 7/308 = 2.27 % | 204 (94) | 751 | 0.045 / 0.050 | 138 |
| **ACFT small.en** | **264.5** | auto+8 | **10/280 = 3.57 %** | **6/308 = 1.95 %** | 591 (402) | 1924 | 0.130 / 0.815 | 366 |
| small.en | 264.5 | full 1500 | 10/280 = 3.57 % | 6/308 = 1.95 % | 4159 (3781) | 4550 | 0.862 / 1.087 | 413 |

Pseudo-stream (1 s chunks, two-pass commit; same series):

| run | passes | median pass ms | max pass ms | passes > 1 s | first commit | retractions | WER stream / batch |
|---|---|---|---|---|---|---|---|
| tiny.en floor 750, 5 s / 15 s | 112 / 118 | 295 / 376 | 1708 / 2145 | 4 / 6 | 2.0 s + 0.27 s | 11 % / 12 % | 7.14 % / 7.86 % · 4.87 % / 3.57 % |
| ACFT tiny.en, 5 s / 15 s | 112 / 118 | **117 / 253** | 5441 / 2683 | 5 / 4 | 2.0 s + 0.08 s | **48 %** / 19 % | 9.29 % / 8.21 % · 4.87 % / 2.92 % |
| ACFT base.en, 5 s / 15 s | 112 / 118 | 162 / 429 | 1338 / 5149 | 2 / 17 | 2.0 s + 0.11 s | 14 % / 11 % | 9.64 % / 7.14 % · 2.92 % / 2.27 % |
| ACFT small.en, 5 s / 15 s | 112 / 118 | 436 / **1174** | 2057 / 3900 | 6 / **67** | 2.0 s + 0.27 s | 15 % / 5 % | 5.00 % / 3.57 % · 2.27 % / 1.95 % |

- ACFT makes Whisper's encoder cost proportional to the audio. ACFT tiny.en spends 48 ms of
  encoder time on a 5 s clip, against 579 ms at the full window. That removes the structural
  objection in §6.4: per pass it is now as cheap as Moonshine tiny.
- The cost moves to stability. With a window that grows each second, ACFT tiny.en's hypothesis
  changes more between passes: 48 % of words retracted on the 5 s set, against 15 % at the full
  window. Its stream WER is also 1–6 words worse than its batch WER. base.en and small.en
  retract less.
- **ACFT small.en is the most accurate English model measured in this series**: 10/280 and 6/308,
  equal to Moonshine small (below) and to plain small.en. ACFT gives it the same WER as plain
  small.en at 1/7 of the batch compute. It cannot keep a 1 s cadence once the buffer passes about
  5 s on this box (67 of 118 passes over budget on the 15 s set).
- **ACFT small.en does not fit a voice pack.** ggml is a single 264,477,561 B (252.2 MiB) file. That
  is under the 256 MiB total and 300 MiB extracted caps (`VoicePackManifest.MAX_TOTAL_BYTES`,
  `MAX_EXTRACTED_BYTES`), but **almost twice `MAX_FILE_BYTES` = 128 MiB**. Shipping it would
  need a per-file cap raised for one engine, or a split-tensor container plus a loader that
  joins it. In both cases it would add a second native runtime. Not recommended at this size.

#### 6.5.2 Moonshine: small and medium, other quantisations, the ORT arena

Official quantised graphs from `moonshine-ai/moonshine-voice-assets`
(`model/{tiny,small,medium}-streaming-en/quantized_26_08_21/`). That layout splits the frontend into
`frontend.model.ort` plus a weights graph with no inputs, whose outputs feed the model graph, and
the harness now loads it. Published streaming WER (model cards, LibriSpeech clean / other / Open
ASR average): tiny 34 M params 4.49 / 12.09 / 12.01, small 123 M 2.49 / 6.78 / 7.84, medium
245 M 2.08 / 5.00 / 6.65. All three use the same tokenizer (sha256 `6884b35f…`).

| model | bytes on disk | largest file | fits voice-pack caps | 5 s WER | 15 s WER | 5 s batch ms (CPU EP, arena off) | RSS MB (arena off) |
|---|---|---|---|---|---|---|---|
| tiny-streaming (shipped) | 45.0 MB | 31.1 MiB (decoder) | yes | 14/280 = 5.00 % | 11/308 = 3.57 % | 938 | 116 |
| **small-streaming** | 142.3 MB (135.7 MiB) | 78.1 MiB (decoder) | **yes** | 10/280 = 3.57 % | 6/308 = 1.95 % | 1670 | 230 |
| medium-streaming | 269.1 MB | **140.2 MiB** (decoder) | no (per-file 128 MiB, total 256 MiB) | 8/280 = 2.86 % | 5/308 = 1.62 % | 2374 | 362 |
| tiny, Masterx ONNX fp32 | 135.2 MB | — | yes | 13/280 = 4.64 % | 10/308 = 3.25 % | 360 | 237 |
| tiny, Masterx ONNX int8 | 76.4 MB | — | yes | 12/280 = 4.29 % | 11/308 = 3.57 % | 329 | 184 |

- **Small is the accuracy upgrade that fits.** Against tiny it makes 4 fewer errors on the 5 s
  set, 5 fewer on 15 s and 40 fewer on the long clips (below), at 3.2× the bytes and about 2× the
  RSS and per-pass time. Medium gains only 1–2 more words on the short sets and does not fit.
- **No better quantisation of tiny was found.** The community Masterx export of the same weights
  has a 53.6 MB int8 decoder (MatMulInteger), against the official 32.5 MB, and is 1.7× the
  official pack's total size. Its WER is within two words of the official graphs. I found no
  Moonshine streaming fine-tune with a usable licence.
- **With the ORT memory arena off, the CPU EP runs the official `.ort` decoder 2–2.5× slower.**
  Measured back to back on tiny, 5 s set: arena on 420 / 465 ms (decoder 286 / 336), arena off
  890–1048 ms (decoder 774–865), both at 14/280. The Masterx plain-ONNX graphs do not show it
  (360 / 329 ms with the arena off, table above), so this is specific to the official graphs as
  loaded by ORT 1.26. Why is not established **[U]**. §5.3 recommended the arena off for its RSS (112 vs 707 MB on long
  streams), and the app's engine does that (`MoonshineV2StreamingEngine`: "CPU EP, arena off").
  The Oct 28 latency rows (364 ms batch, 200 ms per pass) were measured **with the arena on**, so
  the app's real per-pass cost is about twice what §5 shows. The XNNPACK EP with the arena off gets
  arena-on speed back (batch 372–410 ms) at more RSS:

| Moonshine tiny, capped stream (6.5 tok/s) | median pass ms 5 s / 15 s | max pass ms | passes > 1 s | first commit | RSS end / peak MB (15 s) |
|---|---|---|---|---|---|
| CPU EP, arena off (the app today) | 398 / 478 | 1000 / 2404 | 1 / 11 | 2.0 s + 0.44 s | 114 / 243 |
| XNNPACK EP, arena off | **212 / 272** | 393 / 1161 | 0 / 1 | 2.0 s + 0.21 s | 281 / 296 |

  On the long clips (segmented at 20 s) XNNPACK cut tiny's median pass from 449 to 289 ms and its
  over-budget passes from 62 to 26 of 484, and small's from 911 to 537 ms and 200 to 58. The
  harness's end-of-run RSS after those runs was 1.25 GB (tiny) and 1.78 GB (small) on XNNPACK,
  against 81 / 194 MB on the CPU EP. Each long run also does a whole-clip batch pass of up to 72 s
  in the same process, so how much of that growth XNNPACK's own buffers account for is
  **unattributed [U]**. Which setting to ship needs an on-device measurement. It is listed in
  §7.5 and not decided here.
- **The harness now applies the app's per-pass decode cap**
  (`--tokens-per-s 6.5`, = `MoonshineV2StreamingEngine.decode`). Without it, one 5 s clip
  hallucinated "a-l-l-l-…" for 447 tokens on its second pass (20.3 s). The app stops that pass
  after 11 tokens. The capped rows are the ones to compare with the app. Batch rows are uncapped,
  as before.

#### 6.5.3 Long dictation (30–72 s, 1,260 words)

| engine | how | WER | median pass ms | passes > 1 s (of 484) | retractions |
|---|---|---|---|---|---|
| Moonshine tiny, whole clip in one pass | batch | 795/1260 = 63.1 % | — | — | — |
| Moonshine small, one pass | batch | 327/1260 = 26.0 % | — | — | — |
| Moonshine medium, one pass | batch | 385/1260 = 30.6 % | — | — | — |
| Moonshine tiny, streamed without a cut | stream | 366/1260 = 29.1 % | 734 | 193 | 357 % |
| **Moonshine tiny, cut at 20 s (the app)** | stream, capped | 83/1260 = **6.59 %** | 449 (XNNPACK 289) | 62 (26) | 35 % |
| **Moonshine small, cut at 20 s** | stream, capped | 43/1260 = **3.41 %** | 911 (XNNPACK 537) | 200 (58) | 13 % |
| Whisper tiny.en q8_0, full window | batch (whisper.cpp's own 30 s seek) | 69/1260 = 5.48 % | — | — | — |
| Whisper base.en q8_0, full window | batch | 121/1260 = 9.60 % | — | — | — |
| Whisper tiny.en q8_0, cut at 30 s | stream | 73/1260 = 5.79 % | 889 | 151 | 16 % |
| ACFT tiny.en, cut at 20 s | stream | 86/1260 = 6.83 % | 264 | 11 | 22 % |
| ACFT small.en, cut at 20 s | stream | 39/1260 = **3.10 %** | 1151 | **276** | 11 % |
| ACFT tiny.en / base.en / small.en, one pass | batch | 21.9 % / 26.8 % / 29.1 % | — | — | — |
| sherpa Zipformer 20M int8 | true streaming | 54/1260 = 4.29 % | 0.32 s steps | 0 | 0 |
| Whistle ORT int8, one pass | batch | 201/1260 = 16.0 % | — | — | — |
| needle CLI (official engine) | batch | 38/1260 = 3.02 % | — | — | — |

- **The app's 20 s forced cut is what makes Moonshine usable on long dictation.** Without it
  tiny's WER is 29–63 %; with it, 6.59 %. The per-clip errors with the cut (0–20 per clip)
  show no clip collapsing. Every Moonshine size breaks down past its trained length in one pass.
- ACFT checkpoints decoding a whole long clip through whisper.cpp's 30 s seek loop are poor
  (22–29 %). They were tuned for short windows. Cut into app-sized segments they are fine.
- On long dictation the accuracy order is ACFT small.en ≈ needle ≈ Moonshine small (39–43
  errors), then Zipformer (54), then Whisper tiny.en, Moonshine tiny and ACFT tiny.en (69–86).
  Of those, Moonshine small and Zipformer fit the voice-pack caps and the app's ORT.

#### 6.5.4 Releases in the past week (2026-10-29 → 11-05)

Searched Hugging Face (new ASR / speech-recognition models, sorted by creation date), GitHub and
the usual announcement channels for on-device English STT. **Nothing qualified**: a release had
to be open-licensed for F-Droid redistribution, small enough for a voice pack, and a complete
recogniser runnable on ORT or a FLOSS runtime. Excluded on licence: FUTO `asr4all` (FUTO
ethical-use licence, not OSI) and WhissleAI `arc` (CC-BY-NC). Older and outside the window, but
noted: **Phonon-2.5** (2026-10-22, CC-BY-4.0, 156 MB, its own runtime). It is not measured here and
is a candidate for a later spike only if its runtime is FLOSS **[U]**. The FUTO ACFT checkpoints
measured above are from 2024-06 (Apache-2.0 weights, MIT code).

#### 6.5.5 An engine bug the long-clip runs exposed

The frontend graph's first Conv rejects an `audio_chunk` shorter than one 5 ms hop (80 samples):
`INVALID_ARGUMENT … Invalid input shape: {4}`. A forced cut in the harness left a 4-sample tail.
In the app, segments arrive in whole 512-sample hops and the engine feeds 16,000-sample chunks,
so a final chunk under 80 samples needs a manual stop. The segmenter then flushes a partial hop,
and the hop count before it must be a multiple of 125. That is about 1 stop in 800. The failure
was a `SpeechEngineException` from `finish()`. `VoiceInputController` then fails the period with
the engine-error message, and that last segment's final text is never committed. Fixed: `runFrontend` pads a
sub-hop chunk with silence. The new test
`MoonshineV2StreamingEngineTest.aStopThatLeavesLessThanOneFrontendHopStillFinishes` covers tails
of 1, 4 and 79 samples and a session that holds only 4 samples. The harness pads the same way.

#### 6.5.6 ACFT small.en quantisations, and the Moonshine small pack built (2026-11-05, later)

**Quantisations.** FUTO publishes ACFT small.en as one ggml q8_0 file and as fp32 safetensors
(`futo-org/acft-whisper-small.en` @ `8fade991`, sha256 `9c833c79…`). `whisper-quantize` refuses an
already-quantised input, so `scripts/research/stt/acft_safetensors_to_ggml.py` rebuilds an f16 ggml:
FUTO's q8_0 file supplies the header, mel filters, vocabulary, tensor order and types; every
tensor's data comes from the safetensors. Each tensor differs from FUTO's dequantised q8_0 by at
most 0.42 % of its range (q8_0's own step), so the safetensors is the checkpoint FUTO quantised.
Same flags as §6.5.1 (`--audio-ctx auto+8`, 4 threads). WER is batch / stream; long = the 20 s
segmented stream. Pass times are from a separate back-to-back run with q8_0 before and after
(426 and 376 ms), because the WER runs shared the CPU with Gradle and their times are unusable.

| ACFT small.en | file | fits 128 MiB | 5 s (280 words) | 15 s (308) | long (1,260) | median pass, 5 s | passes > 1 s |
|---|---|---|---|---|---|---|---|
| q8_0 (FUTO) | 252.2 MiB | no | 10 / 14 | 6 / 7 | 39 (3.10 %) | 376–426 ms | 8–13 of 112 |
| q5_k | 167.1 MiB | no | 10 / 16 | 6 / 7 | — | — | — |
| q4_k | 138.7 MiB | no (+10.7 MiB) | 12 / 15 | 6 / 7 | — | 466 ms | 4 of 112 |
| q3_k | 108.6 MiB | yes | 17 / 18 | 6 / 7 | 78 (6.19 %) | **1,097 ms** | **65 of 112** |
| q2_k | 85.5 MiB | yes | 40 / 39 | 58 / 54 | stopped | — | — |

(q5_0 and q4_0 have the same sizes as q5_k and q4_k and were not run.)

- **No quantisation of ACFT small.en is worth pursuing for the keyboard.** Only q3_k and q2_k fit
  the per-file cap. q2_k is broken (14–19 % WER). q3_k keeps the 15 s set but loses 7 words on the
  5 s set, all on 5 of 21 clips, none better. On long dictation it doubles the errors (78 vs 39,
  worse on 7 of 10 clips, none better), which puts it level with Moonshine tiny (83) and behind
  Moonshine small (43). It is also 2.6–2.9× slower than q8_0, because ggml's 3-bit k-quant
  kernels are slow on this CPU. Its median pass is over the 1 s cadence.
- q4_k is nearly lossless (2 words on the 5 s set) at q8_0's speed, but it is 10.7 MiB over the
  cap. Shipping it would still mean raising the cap for one engine and a second native runtime,
  to match what Moonshine small already does on the app's own ORT at the same size.

**The Moonshine small pack** is built (`scripts/build_voice_pack.py --variant small`; spec
`docs/specs/voice-input.md` "Moonshine v2 small-streaming pack"): 142,300,974 B of files
(135.7 MiB), zip sha256 `f75cd82a…`, every file equal to the Hub's LFS oid. The app's Kotlin
engine runs it unchanged: JVM test 8/8, WER 2/125 on the eight 5 s clips (tiny 5/125) and 7/228
on the 88 s segmented dictation; emulator.wtf Pixel 7 API 34, 4/4. On that emulator it needed
11.2 s of compute for 11.1 s of audio fed at once (tiny 3.8 s) and 8.0 s to load (tiny 1.7 s).
Phone results (Saga): §6.7. The pack is on the `langpacks` release since 2026-11-05.

### 6.6 Every engine and configuration in one table

Sources: **[O]** = the Oct 28 series (§5, CPU EP with the arena **on** for Moonshine), **[A]** = the
§6.4 Whisper run (2026-11-05, first series), **[B]** = the §6.5 series, **[S]** = a Saga phone
(§6.7; its WER is on a different, 353-word clip set, so it is given in that section only). Long-clip WER is the app-like
configuration where one exists (segmented stream for streaming engines), otherwise a whole-clip
batch, marked "b". "Fits caps" means the voice-pack limits as coded: each file ≤ 128 MiB, total
≤ 256 MiB. Fitting the caps is not the same as fitting the app: only ORT engines run on the
runtime the app ships. Pass time is the median per 1 s pseudo-stream pass, 5 s set / 15 s set.

| engine · config | runtime · licence | on disk | fits caps | 5 s WER | 15 s WER | long WER | pass ms 5 s / 15 s | src |
|---|---|---|---|---|---|---|---|---|
| needle CLI, official Whistle engine | closed engine binary · Apache-2.0 assets | 17 MB `.cact` | n/a (not F-Droid) | 5.00 % | 0.97 % | 3.02 % b | 233 / 460 (engine) | O, B |
| Whistle ORT fp32 | ORT · Apache-2.0 export | 145 MB | yes | 5.00 % | 2.27 % | — | 509 / 1106 | O |
| Whistle ORT int8 | ORT · Apache-2.0 export | 55 MB | yes | 5.36 % | 1.95 % | 15.95 % b | 272 / 577 | O, B |
| **Moonshine tiny-streaming (shipped)**, CPU, arena on | ORT · MIT weights | 45 MB | yes | 5.00 % | 3.57 % | — | 200 / 307 | O |
| Moonshine tiny, CPU, arena off, capped (= the app) | ORT · MIT weights | 45 MB | yes | 5.00 % | 3.57 % | 6.59 % | 398 / 478 | B |
| Moonshine tiny, XNNPACK, arena off, capped | ORT · MIT weights | 45 MB | yes | 5.00 % | 3.57 % | 6.59 % | 212 / 272 | B |
| **Moonshine small-streaming**, CPU, arena off, capped | ORT · MIT weights | 142 MB | **yes** | 3.57 % | 1.95 % | 3.41 % | 676 / 866 | B |
| Moonshine tiny, **Saga** `.144` / `.1.130`, app engine: arena off / on / XNNPACK | ORT 1.21.1 (APK) · MIT weights | 45 MB | yes | §6.7 | §6.7 | §6.7 | 168 / 94 / 98 · 203 / 101 / 99 (all clips) | S |
| Moonshine small, **Saga** `.144` / `.1.130`, app engine: arena off / on / XNNPACK | ORT 1.21.1 (APK) · MIT weights | 142 MB | yes | §6.7 | §6.7 | §6.7 | 387 / 226 / 226 · 369 / 204 / 202 (all clips) | S |
| Moonshine medium-streaming | ORT · MIT weights | 269 MB | no (decoder 140 MiB) | 2.86 % | 1.62 % | 30.6 % b | — | B |
| Moonshine tiny, Masterx ONNX fp32 / int8 | ORT · MIT weights | 135 / 76 MB | yes | 4.64 / 4.29 % | 3.25 / 3.57 % | — | — | B |
| sherpa Zipformer 20M int8 | ORT · Apache-2.0 weights | 35 MB | yes | 5.71 % | 0.97 % | 4.29 % | 41–49 per 0.32 s step | O, B |
| Whisper tiny.en q5_1 | whisper.cpp · MIT weights | 32 MB | yes | 7.14 % | 2.92 % | — | 1287 / 1382 | A |
| Whisper tiny.en q8_0 | whisper.cpp · MIT weights | 44 MB | yes | 7.50 % | 2.92 % | 5.79 % (30 s cut) | 675 / 776 | A, B |
| Whisper tiny.en f16 | whisper.cpp · MIT weights | 78 MB | yes | 7.50 % | 2.92 % | — | — | A |
| Whisper tiny.en q8_0, `-ac` floor 750 | whisper.cpp · MIT weights | 44 MB | yes | 7.86 % | 3.57 % | — | 295 / 376 | B |
| Whisper tiny.en q5_1, `-ac` scaled (loops) | whisper.cpp · MIT weights | 32 MB | yes | 135 % | 9.74 % | — | 228 / 617 | A |
| Whisper tiny q5_1 (multilingual) | whisper.cpp · MIT weights | 32 MB | yes | 9.64 % | 3.90 % | — | — | A |
| Whisper base.en q5_1 | whisper.cpp · MIT weights | 60 MB | yes | 5.71 % | 2.27 % | — | 3264 / 3092 | A |
| Whisper base.en q8_0 | whisper.cpp · MIT weights | 82 MB | yes | 4.64 % | 2.27 % | 9.60 % b | — | A, B |
| Whisper base.en f16 | whisper.cpp · MIT weights | 148 MB | no (141 MiB file) | 4.64 % | 2.27 % | — | — | A |
| Whisper base q5_1 (multilingual) | whisper.cpp · MIT weights | 60 MB | yes | 6.07 % | 1.30 % | — | — | A |
| **ACFT tiny.en q8_0**, auto+8 | whisper.cpp (MIT) · Apache-2.0 weights | 44 MB | yes | 8.21 % | 2.92 % | 6.83 % | 117 / 253 | B |
| ACFT tiny q8_0 (multilingual), auto+8 | whisper.cpp (MIT) · Apache-2.0 weights | 44 MB | yes | 9.29 % | 3.90 % | — | — | B |
| ACFT base.en q8_0, auto+8 | whisper.cpp (MIT) · Apache-2.0 weights | 82 MB | yes | 7.14 % | 2.27 % | 26.8 % b | 162 / 429 | B |
| **ACFT small.en q8_0**, auto+8 | whisper.cpp (MIT) · Apache-2.0 weights | 264 MB | **no (252 MiB file)** | 3.57 % | 1.95 % | 3.10 % | 436 / 1174 | B |
| ACFT small.en q4_k, auto+8 | whisper.cpp (MIT) · Apache-2.0 weights | 145 MB | no (138.7 MiB file) | 4.29 % | 1.95 % | — | 466 / — | B |
| ACFT small.en q3_k, auto+8 | whisper.cpp (MIT) · Apache-2.0 weights | 114 MB | yes | 6.07 % | 1.95 % | 6.19 % | 1097 / — | B |
| ACFT small.en q2_k, auto+8 | whisper.cpp (MIT) · Apache-2.0 weights | 90 MB | yes | 14.29 % | 18.83 % | — | — | B |
| Whisper small.en q8_0, full window | whisper.cpp · MIT weights | 264 MB | no | 3.57 % | 1.95 % | — | — (4.2 s batch) | B |

WER denominators: 280 words (5 s), 308 (15 s), 1,260 (long). One word is 0.36 / 0.32 / 0.08
points, so differences of one or two words on the short sets are noise.

### 6.7 On the phones (2026-11-05)

Four test phones: the Pixel 8 Pro (Android 17) and three Sagas (Snapdragon 8+ Gen 1; Android 14,
13, 13). What ran where, and why:

- **Engine benchmark** (`VoiceEngineDeviceBenchmark`, debug APK + test APK, the app's Kotlin engine
  on the phone's ORT 1.21.1, no microphone): the Sagas `192.168.0.144` (Android 13) and
  `192.168.1.130` (Android 14). Play Protect blocked the debug APK on `.1.130` until ADB-install
  verification was turned off for the run (`settings put global verifier_verify_adb_installs 0`,
  deleted again afterwards); the Pixel was busy and not used. The third Saga had a game running in
  the foreground at 93 % CPU and was left alone.
- **Real microphone through the release app** (Settings import, Chrome textarea, the 10 long-set
  clips, 228 words, played through the phone's own speaker with 1.5 s gaps, 107 s): both Sagas.
  The first `.1.130` runs produced no text because the speaker stayed at 9/25: on that phone
  `cmd media_session volume --set` reports success and changes nothing (read back with `--get`);
  Volume Up key events while the page's audio plays do raise it. At 25/25 it transcribes. (An
  earlier draft blamed echo cancellation; that was wrong.) The Pixel was not used.

**Engine benchmark, Saga `.144`.** 18 LibriSpeech clips (the 10 long-set clips + the eight 5 s
clips; 119.5 s, 353 words), each fed in 1 s chunks = 112 passes per run, one pack and one setting per
process. Peak RSS is the test process's `VmHWM` and includes ~190 MB before the model loads.

| pack · ORT setting | load | median pass | p90 | max | passes > 1 s | final pass | errors | peak RSS |
|---|---|---|---|---|---|---|---|---|
| tiny · CPU EP, arena off (**the app today**) | 142 ms | **168 ms** | 267 | 597 | 0 | 111 ms | 14/353 | 472 MB |
| tiny · CPU EP, arena on | 148 ms | 94 ms | 134 | 259 | 0 | 81 ms | 14/353 | 536 MB |
| tiny · XNNPACK, arena off | 155 ms | 98 ms | 144 | 306 | 0 | 87 ms | 14/353 | 496 MB |
| small · CPU EP, arena off | 320 ms | **387 ms** | 497 | 1,533 | 3 | 344 ms | **9/353** | 674 MB |
| small · CPU EP, arena on | 339 ms | 226 ms | 313 | 741 | 0 | 249 ms | 9/353 | 786 MB |
| small · XNNPACK, arena off | 442 ms | 226 ms | 307 | 736 | 0 | 231 ms | 9/353 | 764 MB |

- **The arena-off slowdown of §6.5.2 is real on the phone**: 1.8× (tiny) and 1.7× (small) per pass
  on the APK's ORT 1.21.1. XNNPACK with the arena off is as fast as the arena on, with a smaller
  peak (tiny +24 vs +64 MB over arena off). Every pass here belongs to a clip of ≤ 23 s, so the
  long-session growth that made §5.3 turn the arena off (one 23 s utterance → 508 MB) is bounded
  by the app's 20 s segment cut but was **not** measured over a long period on the phone.
- **Small on a Saga keeps the 1 s cadence** as shipped (arena off): 387 ms median, 3 of 112 passes
  over 1 s (all early passes of a session); with XNNPACK none are.
- WER on this path (16 kHz WAV straight into the engine): small 9/353 = 2.5 %, tiny 14/353 = 4.0 %.
- **Saga `.1.130`** (Android 14, same chip), same benchmark: tiny 203 / 101 / 99 ms median (arena off
  / on / XNNPACK), 0 passes over 1 s; small 369 / 204 / 202 ms, 4 / 0 / 0 over 1 s; errors 14 and 9
  of 353 as on `.144`; peak RSS tiny 483 / 535 / 530 MB, small 712 / 811 / 789 MB. Arena off costs
  1.8–2.0× there.
- **XNNPACK is not a hardware feature**: it is ORT's set of ARM/x86 CPU kernels, compiled into the
  `libonnxruntime.so` the APK already ships for arm64-v8a, armeabi-v7a and x86_64 (its symbols are
  in all three). Every phone that runs the app can use it; no GPU/NPU/NNAPI is involved.

**Real microphone, Saga `.144`, release build of `ee203b73`** (keyboard process RSS from
`dumpsys meminfo`; lag = time from a clip's end until the field shows the final text through it).
Each pack twice, in the order tiny, small, small, tiny; switching to tiny exercised Settings →
Remove (the controller reloaded the engine on its own).

| pack | errors (228 words) | lag median / max | keyboard RSS while listening | after |
|---|---|---|---|---|
| tiny, run 1 / 2 | 19 (8.3 %) / 18 (7.9 %) | 0.75 / 1.21 s; 0.76 / 1.58 s | 324–469 / 374–522 MB | 362 MB |
| small, run 1 / 2 | **10 (4.4 %) / 9 (3.9 %)** | 0.84 / 1.15 s; 0.84 / 1.19 s | 483–678 / 459–698 MB | 529 MB |

**Real microphone, Saga `.1.130`** (release of `ee203b73`, speaker at 25/25, same clips; order tiny,
small, small, tiny): tiny 45 and 41 errors (19.7 / 18.0 %), small 23 and 22 (10.1 / 9.6 %); lag
median tiny 0.95 / 1.13 s, small 1.22 / 1.05 s (max 3.3 s); keyboard RSS while listening tiny
321–580 MB, small 454–661 MB. This Saga's speaker → microphone path is worse than `.144`'s for both
packs (louder playback, a different unit); the ratio holds.

Small halves the errors through a real speaker → microphone path on both Sagas, at ~0.1–0.2 s more
lag and ~150–200 MB more keyboard memory while dictating. Model load in the keyboard: tiny 117 ms, small
286 ms. Tiny's 8 % here is better than the 19.7 % of Oct 30 on the same clips and the same Saga
(that run: the release of `b243ce3d`, 1 s gaps instead of 1.5 s, an unrecorded volume), so compare
packs only within one session.
Found on the way: Remove deletes a pack at once, without a confirmation (a 142 MB re-import if
tapped by mistake).

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

**Whisper (whisper.cpp)**, measured after the fact (§6.4, 2026-11-05): not a better fit — its
fixed 30 s encoder makes the best config ~3.4× Moonshine's per-pass cost on short dictation, the
reduced-context workaround makes it loop, and it adds a second native runtime. Its strengths
(long-form batch, 99 languages) are not the keyboard's use case. The follow-up (§6.5.1) removes
the encoder objection for the **ACFT** checkpoints: ACFT tiny.en costs 117 ms per pass. It does
not change the verdict: ACFT tiny.en is no more accurate than Moonshine tiny and retracts more, and
the accurate one (ACFT small.en) breaks the per-file cap and the 1 s cadence, and its one
quantisation under the cap (q3_k) gives the accuracy back and runs slower (§6.5.6). It would also
still be a second native runtime.

**Upgrade path inside the same engine: Moonshine small-streaming** (§6.5.2–6.5.3). It is the same
runtime, tokenizer and pack format, 135.7 MiB within every cap, and makes fewer errors than tiny on
every set measured here (10 vs 14, 6 vs 11, 43 vs 83) and on a phone (§6.7: 9 vs 14 of 353 words
on the engine path, 9–10 vs 18–19 of 228 through a real microphone). Its cost is about 2.3× tiny's
per-pass time (387 vs 168 ms on a Saga as shipped) and ~150–200 MB more keyboard memory while
dictating. It is published as a second, optional English pack (2026-11-05); tiny stays the
default. Switching the engine to XNNPACK (or the arena on) would take ~40 % off every pass for both
packs (§6.7); that change is the maintainer's, after a long-period RSS check.

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

Open after the 2026-11-05 follow-up (§6.5, §6.7): the arena/XNNPACK timing is measured on a Saga
(§6.7: arena off costs 1.7×; XNNPACK matches arena on) but not on the Pixel (Play Protect refused
the debug APK there; the Pixel was busy on the second attempt), and not over a long listening period (RSS growth with the arena on or XNNPACK);
why the official graphs slow down with the arena off is not established; a person's voice and a
noisy room for small; non-English clips; Phonon-2.5, if its runtime turns out to be FLOSS.

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
# §6.4 Whisper baseline (2026-11-05)
git clone --depth 1 https://github.com/ggml-org/whisper.cpp && cmake -S whisper.cpp -B wcb -DCMAKE_BUILD_TYPE=Release -DGGML_OPENMP=OFF
cmake --build wcb -j3 --target whisper-cli
# models: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{tiny.en,base.en}{,-q8_0,-q5_1}.bin, ggml-{tiny,base}-q5_1.bin
python3 scripts/research/stt/whisper_cpp_bench.py --cli wcb/bin/whisper-cli --model ggml-tiny.en-q8_0.bin bench $STT/audio/manifest.json --ids "$(cat $STT/ids5.txt)"
python3 scripts/research/stt/whisper_cpp_bench.py --cli wcb/bin/whisper-cli --model ggml-tiny.en-q8_0.bin stream $STT/audio/manifest.json --ids "$(cat $STT/ids15.txt)" --verbose
#   add --audio-ctx auto for the reduced-context rows
# §6.5 follow-up (2026-11-05)
python3 scripts/research/stt/make_long_clips.py --manifest $STT/audio/manifest.json --out $STT/audio --target 30:6 --target 60:4
# ACFT: https://voiceinput.futo.org/VoiceInput/{tiny_en,base_en,small_en,tiny}_acft_q8_0.bin ; plain small.en: ggml-small.en-q8_0.bin
python3 scripts/research/stt/whisper_cpp_bench.py --cli wcb/bin/whisper-cli --model ggml-acft-tiny_en-q8_0.bin --audio-ctx auto+8 stream $STT/audio/manifest_long.json --max-segment 20
#   --audio-ctx auto:750 = window floor for the stock weights; --tokens-per-s X needs whisper-cli -mt:
#   examples/cli/cli.cpp: add `int32_t max_tokens = 0;` to whisper_params, parse `-mt/--max-tokens`,
#   set `wparams.max_tokens = params.max_tokens;`
# ACFT small.en quantisations (§6.5.6): fp32 safetensors -> f16 ggml (FUTO's q8_0 file as template) -> whisper-quantize
#   https://huggingface.co/futo-org/acft-whisper-small.en/resolve/8fade991687abdcf24e14f7c08c9153539cbc77c/model.safetensors
python3 -I scripts/research/stt/acft_safetensors_to_ggml.py --safetensors model.safetensors \
    --template ggml-acft-small_en-q8_0.bin --out ggml-acft-small_en-f16.bin
for q in q5_k q4_k q3_k q2_k; do wcb/bin/whisper-quantize ggml-acft-small_en-f16.bin ggml-acft-small_en-$q.bin $q; done
# Moonshine small voice pack: python3 scripts/build_voice_pack.py --variant small
# §6.7 phone benchmark: copy both pack zips to src/androidTest/assets/voicepack/ and the clips (<id>.wav +
#   <id>.txt) to src/androidTest/assets/voicepack/bench/, build assembleDebug assembleDebugAndroidTest, install both, then
adb shell am instrument -w -e class tribixbite.cleverkeys.stt.VoiceEngineDeviceBenchmark -e voiceBench 1 \
    -e voicePack voicepack-moonshine-v2-small-en.zip -e voiceBenchHints arena=off,ep=xnnpack \
    tribixbite.cleverkeys.debug.test/androidx.test.runner.AndroidJUnitRunner   # logcat -s VoiceBench
# Moonshine: moonshine-ai/moonshine-voice-assets model/{tiny,small,medium}-streaming-en/quantized_26_08_21/*
python3 scripts/research/stt/moonshine_stream_ort.py --model-dir $STT/models/small-streaming-en --tokenizer tokenizer.json \
    --no-arena --tokens-per-s 6.5 [--ep xnnpack] stream $STT/audio/manifest_long.json --max-segment 20
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
