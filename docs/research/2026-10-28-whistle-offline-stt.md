# Cactus "Whistle" as an optional offline speech-to-text plugin (2026-10-28)

## Question and short answer

Can CleverKeys offer Cactus Compute's Whistle (released 2026-10-02) as an OPTIONAL, separately
downloaded plugin for in-app, on-device, offline dictation, without adding the INTERNET
permission and without losing F-Droid inclusion?

**Short answer.** The *weights* can be offered that way: `whistle.cact` is one 16.9 MB
Apache-2.0 file covering seven languages, and it fits the language-pack delivery CleverKeys
already has (SAF import, manifest, sha256, atomic install). The *official runtime* cannot:
Cactus ships its engine only as prebuilt binaries (`libneedle.a` per platform; no source
anywhere we could find), the older Cactus SDK/Kotlin library is under a non-FLOSS
revenue-capped licence, is archived, needs INTERNET and has telemetry on by default. A prebuilt
engine from Hugging Face is not an allowed binary source under F-Droid's inclusion policy, and a
pack that carries a native library would be "downloading additional executable binary files".
So Whistle is only viable for CleverKeys through a FLOSS reimplementation of the runtime: the
Apache-2.0 ONNX export (runs on the ONNX Runtime the APK already ships) or the Apache-2.0 C99
engine from the ESP32 port (needs an NDK build CleverKeys does not have today). Neither has a
published Android latency number; a spike on the Pixel 8 Pro is the first step. The rest of this
note gives the evidence, the delivery options, the IME work, the alternatives and a phased plan.

Marking: **[U]** = not verified from a primary source (inference, third-party claim, or an
estimate). Everything else cites the page it was read from, on 2026-10-28.

Repo context read for this note: `memory/todo.md` "Open now", `CLAUDE.md`,
`docs/specs/dictionary-and-language-system.md` (pack caps, refusal, rollback),
`docs/specs/gif-panel-spec.md` (SAF import, no downloader by design), `AndroidManifest.xml`
(no INTERNET; `RECORD_AUDIO` present only as a commented-out line), `build.gradle`
(`onnxruntime-android:1.21.1`, ABI splits `armeabi-v7a`/`arm64-v8a`/`x86_64`, no
`externalNativeBuild`), `src/main/kotlin/tribixbite/cleverkeys/VoiceImeSwitcher.kt`,
`KeyboardReceiver.kt` (SWITCH_VOICE_TYPING), `LayoutModifier.kt` (`shouldOfferVoiceTyping`),
`TerminalUtils.kt` (`EditorKind`), `langpack/LanguagePackManager.kt`, `onnx/ModelLoader.kt`,
`LargeTextCommit.kt`, and the release APK `build/outputs/apk/release/CleverKeys-v2.0.0-arm64-v8a.apk`
(25.5 MB; `lib/arm64-v8a/libonnxruntime.so` is 17.9 MB uncompressed, `unzip -l`).

---

## 1. What Whistle is

### 1.1 Model facts

| Fact | Value | Source |
| --- | --- | --- |
| Release | 2026-10-02, Cactus Compute (YC S25, SF) | https://cactuscompute.com/blog/whistle |
| File | `whistle.cact`, 16.9 MB, one file, CPU only, "no dependencies" | https://huggingface.co/Cactus-Compute/whistle/tree/main |
| Parameters | 55.2 M (HF listing of the GGUF repack) | https://huggingface.co/models?other=base_model:quantized:Cactus-Compute/whistle |
| Quantisation | "Cactus Quants", 2–4 bit, group size 128 (`config.json`) | https://huggingface.co/Cactus-Compute/whistle/resolve/main/config.json |
| Master checkpoint | `checkpoints/whistle.safetensors`, 221 MB (half precision) | https://huggingface.co/Cactus-Compute/whistle/tree/main/checkpoints |
| Languages | en, de, fr, es, it, nl, pl; language emitted as a token, detected unless forced | https://cactuscompute.com/blog/whistle |
| Input | 16 kHz mono float PCM; one pass up to 30 s; longer audio windowed at sentence ends | https://cactuscompute.com/blog/whistle-best-practices |
| Front end | 80 log-mel bins, 25 ms window, 10 ms hop; conv stem halves 3× → one frame per 80 ms | https://cactuscompute.com/blog/whistle |
| Encoder | 8 "Simple Attention" blocks, non-causal (whole clip at once) | https://cactuscompute.com/blog/whistle |
| Decoder | 8 laddered blocks, width 512, GQA 8q:2kv, gated cross-attention per layer, engram lookups at layers 3 and 7; every depth 2…8 is a trained model (`--audio-depth`) | https://cactuscompute.com/blog/whistle |
| Decoding | 5 beams, length-normalised; keyword biasing via Aho-Corasick over caller phrases; transcript capped at 320 tokens; 8,192 pieces + 7 language tokens | https://cactuscompute.com/blog/whistle |
| Extras | word timestamps (80 ms resolution, from decoder attention), speech embeddings | https://cactuscompute.com/blog/whistle-best-practices |
| Streaming | `needle_stream_transcribe_process`: append ~1 s of PCM, the engine re-transcribes the buffered audio and commits words two consecutive passes agree on; `needle_stream_transcribe_stop` flushes the tail | https://huggingface.co/Cactus-Compute/needle3/resolve/main/android-arm64/needle.h |
| Silence | loudness gate before decoding: silence/steady noise → empty transcript; music and other sounds can still produce text, so put a VAD or wake word in front | https://huggingface.co/Cactus-Compute/whistle |
| Weights version | `weights_version 2.0.0`; `.cact` format is tied to the engine version ("rebuild it" on mismatch) | https://huggingface.co/Cactus-Compute/whistle/resolve/main/config.json, https://cactuscompute.com/blog/needle-python-docs |
| Popularity | 63,661 HF downloads in the last month; HN 964 points / 185 comments | https://huggingface.co/Cactus-Compute/whistle, https://news.ycombinator.com/item?id=50008427 |

Note on the front end: the blog says 250–3500 Hz; the ESP32 port measured the shipped filterbank
at 31–7969 Hz and warns that band-passing input hurts ("turn the light red" → "turn the leg
red"). https://github.com/andrisgauracs/whistle-esp32#troubleshooting

### 1.2 Accuracy and speed (Cactus's own numbers)

Whistle WER as labelled in Cactus's benchmark chart (Whisper normalizers, 86,174 utterances;
the Whisper-base and Moonshine-tiny-v2 bars are unlabelled, only the direction is stated).
Source: text extracted from
https://huggingface.co/Cactus-Compute/whistle/resolve/main/assets/whistle-benchmarks.svg and
the prose at https://cactuscompute.com/blog/whistle#benchmarks.

| Benchmark | Whistle WER % | Versus Whisper base (145.3 MB) |
| --- | --- | --- |
| LibriSpeech test-clean | 4.31 | Whistle ahead |
| LibriSpeech test-other | 10.49 | Whistle ahead |
| SPGISpeech | 7.65 | Whisper did not publish |
| Earnings-22 | 19.01 | Whisper did not publish |
| AMI (with empty refs) | 26.07 | Whisper ahead (AMI-IHM subset) |
| AMI cleaned | 22.87 | Whisper did not publish |
| TED-LIUM | 7.61 | Whisper ahead |
| FLEURS (7-language average) | 21.4 | Whistle ahead |
| MLS (6 languages, no English) | 24.9 | Whisper ahead |

Depth ladder on LibriSpeech test-clean (2,611 clips, Apple M4 Pro, from
https://cactuscompute.com/blog/whistle-best-practices#depth): 8L 4.75 % at 1,182 tok/s, 6L
5.07 %, 4L 5.59 % at 1,799 tok/s, 2L 7.66 % at 2,557 tok/s. (4.75 here vs 4.31 in the chart for
the same split: the two pages do not say which setting differs. **[U]** which is the
beam-5 figure.) Keyword biasing on the same split: 100 keywords cut biased-word error from 18.43 %
to 4.46 % and overall WER from 4.73 % to 2.93 % (same page).

Speed numbers exist ONLY for an Apple M4 Pro: time to first token 5.9 ms at 5 s of audio,
11.1 ms at 10 s, 36.3 ms at 30 s; decode 1,319 tok/s at 5 beams
(https://cactuscompute.com/blog/whistle#benchmarks). **No Android, iPhone or Raspberry Pi
timing is published by Cactus**, and none appeared in the HN thread. The only non-laptop data
point is the ESP32-S3 port at 240 MHz, depth 4, int8: 2 s of English took 6.3 s (log-mel 0.1 s,
conv stem 0.9 s, encoder 3.2 s, decoder 2.2 s); a 3.2 s French sentence 12.6 s
(https://github.com/andrisgauracs/whistle-esp32#performance). A phone big core with NEON should
be one to two orders of magnitude faster than that, i.e. well under real time, but that is an
extrapolation **[U]**; measure it (section 6).

Memory: Cactus publishes `peak_ram_mb` per response for Needle (88.5 MB in a sample) but no
figure for Whistle (https://cactuscompute.com/blog/needle-python-docs). The ESP32 port runs
depth 4 with 8 MB PSRAM and the weights memory-mapped from flash, which bounds the activation
footprint from above at a few MB **[U]** for the quantised engine. An fp16 ONNX build is a
different story (section 2.3).

### 1.3 Independent quality observations

- HN: "can handle" for "can't handle", "cannons" for "guns" (bambax); Spanish "writes non
  existing words" though English works with a Spanish accent (tecleandor); "does very poorly in
  detecting Dutch vs. German" (bwestergard); mumbled US English "not writing all words"
  (saturn8601); technical terms better than iOS dictation (flowerlad). One user got 70/170
  correct free-form vs 168 for a 1.7 B Qwen ASR model, then 164/170 after constraining Whistle
  to a template set. https://news.ycombinator.com/item?id=50008427
- ESP32 port, synthetic voices, colour commands: Dutch is weak "and that comes from Whistle
  itself: its own detection picks Dutch for only about half of these clips"; language detected
  correctly en 98 %, de 70 %, fr 88 %, es 84 %, it 71 %, nl 52 %, pl 83 %; the official engine
  "transcribes ten seconds of microphone noise as 'Thank you.'" (hence the firmware's own noise
  gate). Depth 4 scores 11.7 % WER on 73 LibriSpeech utterances in that port.
  https://github.com/andrisgauracs/whistle-esp32#accuracy
- GGUF repack: 30 FLEURS dev clips across the seven languages, 17.41 % WER for the port vs
  17.65 % for Cactus's engine; with Silero VAD in front 16.08 %, and 24/24 non-speech recordings
  return empty text. https://huggingface.co/ManniX-ITA/whistle-GGUF/resolve/main/README.md

Takeaway for a keyboard: good for English dictation of short utterances, usable for de/fr/es/it,
weak for nl and for auto language detection between close languages; it needs a VAD in front and
a hallucination guard; keyword biasing from the user's own dictionary is the lever that makes it
competitive on names.

### 1.4 Licences, source availability, redistribution

| Component | Licence | Source available? | Evidence |
| --- | --- | --- | --- |
| Weights `whistle.cact`, `whistle.safetensors` | Apache-2.0 (`license: apache-2.0` front matter; 11.4 kB LICENSE) | n/a (weights) | https://huggingface.co/Cactus-Compute/whistle/resolve/main/README.md, https://huggingface.co/Cactus-Compute/whistle/tree/main |
| Engine binaries (`needle`, `libneedle.a`, `needle.h` per platform) | Apache-2.0 LICENSE in the repo | **No.** Only binaries: android-arm64 `libneedle.a` 2.18 MB, `needle` 1.59 MB, `needle.h` 4.53 kB | https://huggingface.co/Cactus-Compute/needle3/tree/main/android-arm64, https://huggingface.co/Cactus-Compute/needle3/resolve/main/LICENSE |
| `cactus-needle` Python package (training, export, JAX reference, downloader) | Apache-2.0 (relicensed from MIT 2026-08-17) | Yes, but it is Python; the `needle/` tree is `agent`, `environments`, `model`, `playground`, `cli.py`, `platform.py`, `_telemetry.py` — no C++ | https://github.com/cactus-compute/needle, https://github.com/cactus-compute/needle/tree/main/needle |
| `cactus-compute/cactus` (the older general SDK: engine, kernels, quants) | Custom: free only for individuals, orgs under $2 M funding AND revenue, schools, 501(c)(3); others need a commercial licence; terminates on crossing a threshold | Yes (source-available, not FLOSS by OSI/FSF/DFSG) | https://github.com/cactus-compute/cactus/blob/main/LICENSE |
| `cactus-compute/cactus-kotlin` (Maven `com.cactuscompute:cactus:1.4.1-beta`) | Same family ("License" file, not an SPDX badge) | Archived read-only 2026-07-24; requires `INTERNET` ("for model downloads") and `RECORD_AUDIO`; "telemetry … enabled by default"; NPU needs a paid "Pro key"; its STT is **Whisper** (`whisper-tiny`/`whisper-base`), not Whistle; ARM64 only | https://github.com/cactus-compute/cactus-kotlin |
| ESP32 port engine (independent C99 reimplementation, matched tensor-by-tensor) | Apache-2.0 | Yes | https://github.com/andrisgauracs/whistle-esp32 |
| ONNX export + JS pipeline (mrfakename) | Apache-2.0 | Yes (PyTorch re-implementation, export scripts, JS reference) | https://huggingface.co/mrfakename/whistle-ONNX/resolve/main/README.md |
| GGUF repack (ManniX-ITA) | Apache-2.0 | Needs the `opencoti` llamafile fork; "a stock whisper.cpp / transcribe.cpp / llama.cpp build does not load it" | https://huggingface.co/ManniX-ITA/whistle-GGUF/resolve/main/README.md |

On engine source: Cactus's own porting guide says what a port has to work from is the `.cact`
file, the Python reference and the binaries ("Notes for anyone writing their own Needle 3
runtime … from the first community port … written in Rust against nothing but the .cact file and
the Python reference"), https://cactuscompute.com/blog/porting-needle; the ESP32 author states it
plainly: "Cactus ships no ESP32 build and no source for Whistle's engine" and "Whistle has no
published source; the official engine is a prebuilt library",
https://github.com/andrisgauracs/whistle-esp32. Blog coverage that calls the engine "open source
on GitHub" (e.g. https://www.eesel.ai/blog/cactus-whistle) conflates the Python package with the
engine.

On telemetry: the needle README says "By default, telemetry is turned on in the binary. To turn
it off, set environment variables NEEDLE_TELEMETRY=0 and DO_NOT_TRACK=1"
(https://github.com/cactus-compute/needle#deploy); the Python docs describe it as anonymous usage
counts (function name, version, OS, random install id)
(https://cactuscompute.com/blog/needle-python-docs#install); but the model card says "The engine
reads no environment variables" and the devices guide says of a native platform folder "nothing
in it phones home" (https://huggingface.co/Cactus-Compute/whistle,
https://cactuscompute.com/blog/needle-supported-devices#no-network-at-all). Whether the Android
`libneedle.a` contains any network code is therefore **[U]**; it would not matter at runtime in
an app without INTERNET, but it matters for the F-Droid "Tracking" anti-feature test ("tracks
and/or reports your activity … by default", https://f-droid.org/en/docs/Anti-Features/) and for
trust. A FLOSS runtime avoids the question.

On redistributing the weights in our own pack: Apache-2.0 permits redistribution in object form
with the licence and NOTICE (https://huggingface.co/Cactus-Compute/needle3/resolve/main/LICENSE
§4). CleverKeys packs already carry `NOTICE.txt` and manifest `license`/`attribution`/`source`
keys (`LanguagePackManager.kt` KDoc), so the mechanics exist. Apache §6 grants no trademark
rights beyond describing origin; a pack named "Whistle (Cactus Compute) voice pack" is
descriptive use, but the maintainer should decide the naming (section 6).

### 1.5 F-Droid rules that bite

From https://f-droid.org/en/docs/Inclusion_Policy/ :

- "All binary dependencies including JAR files must originate either from source compilation or
  Debian repository downloads. Prebuilt binaries should only come from authorized trusted
  sources." The allowed prebuilt sources are Debian main, trusted Maven repos (Maven Central,
  Google Maven, OSS Sonatype/JFrog, JitPack, Clojars — "Those binaries must still be freely
  licensed"), the Android/Flutter SDKs and Hermes, and some language toolchains. A `libneedle.a`
  downloaded from Hugging Face is none of these, and it has no source to build from. **Linking it
  into the APK makes the app unbuildable under the policy**, whatever its LICENSE file says.
- "Applications must not download additional executable binary files (e.g. add-ons,
  auto-updates, etc.) without explicit user consent" that is opt-in and explains the user is
  bypassing F-Droid's checks. A voice pack that carries a `.so` is exactly that; a pack that
  carries only weights is data, like today's language packs and GIF packs.
- ONNX Runtime itself is fine: `com.microsoft.onnxruntime:onnxruntime-android` comes from Maven
  Central and is MIT (https://raw.githubusercontent.com/microsoft/onnxruntime/main/LICENSE) —
  CleverKeys is already on F-Droid with it.

Anti-features (https://f-droid.org/en/docs/Anti-Features/): **NonFreeNet** does not apply (no
network at all); **NonFreeDep** applies to option 3(c) below when the feature depends on Google's
on-device recogniser ("may depend on other software already being installed on your device");
**NonFreeAssets** does not apply to Apache-2.0 weights; **NonFreeAdd** would apply if the app
promoted a proprietary add-on; **Tracking** applies to default-on telemetry.

Android's own rule on downloaded native code: apps targeting API 29+ "should load only the binary
code that's embedded within an app's APK file"; `execve()` on files in the app home directory is
blocked (https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission).
`dlopen()` of a downloaded `.so` from app-private storage is not blocked by that change, but it
is a W^X-style practice the platform is moving away from **[U]** on future versions, and it
would hand a third-party pack arbitrary code execution inside the IME. Runtime in the APK,
weights in the pack, is the only sane split.

---

## 2. Runtime options on Android

| Option | What it is | Licence / F-Droid | ABIs | APK cost | Streaming | Status |
| --- | --- | --- | --- | --- | --- | --- |
| **A. Cactus engine, prebuilt** | `libneedle.a` + `needle.h` via JNI; `needle_transcribe`, `needle_stream_transcribe_*`, keyword bias, `--audio-depth` | Apache-2.0 file, **no source** → not buildable for F-Droid main; telemetry question open | android-arm64, android-armv7, android-riscv64 — **no x86_64** (CleverKeys ships an x86_64 split) | ~1.6–2.2 MB per ABI **[U]** (static archive 2.18 MB; CLI binary 1.59 MB) | Yes, two-pass commit | Works today for sideloaded builds only |
| **B. Cactus Kotlin SDK** | `com.cactuscompute:cactus` | Non-FLOSS revenue-capped licence; archived; needs INTERNET; telemetry default on; STT is Whisper not Whistle | ARM64 | n/a | file/buffer, token callback | **Reject** |
| **C. ONNX Runtime (already shipped)** | mrfakename's export: `encoder.onnx` + `decoder.onnx` (one step, KV cache), host-side log-mel, BPE, engram lookup, greedy decode with LID, keyword bias, DTW timestamps | Apache-2.0 export; ORT MIT from Maven Central → F-Droid clean | all (ORT CPU/XNNPACK) | **0 bytes** of new runtime; Kotlin port of the JS pipeline | Not implemented in the port; the two-pass commit scheme is reproducible on top of batch transcription **[U]** effort | fp32 and fp16 graphs published; matched the engine on 4/4 reference clips |
| **D. FLOSS C engine (ESP32 port)** | portable C99, no dependencies, `.cact` v3/v4 reader, int8 kernels (Xtensa PIE path; generic C otherwise) | Apache-2.0 → F-Droid builds NDK code from source | any NDK ABI once built | small **[U]** (a few hundred kB) | Not in the port (push-to-talk clips); add on top | Needs CleverKeys to grow an NDK/CMake build (none today) and NEON kernels for speed **[U]** |
| **E. GGUF / whisper.cpp family** | `whistle.gguf` 17.0 MB with `CQ2_0`/`CQ4_0` tensor types | Apache-2.0, but only the `opencoti` llamafile fork loads it; stock whisper.cpp does not | whatever we build | large (a llama.cpp-class engine) | server-side SSE/realtime in that fork | Not a fit for an IME |

Details that matter for C (the recommended spike):

- Sizes: fp32 `encoder.onnx.data` 72.1 MB + `decoder.onnx.data` 73 MB; fp16 36.1 MB + 36.5 MB.
  The 17 MB `whistle.pack` holds the original 2/3/4-bit weights and `js/pack.js` "rebuilds any of
  the `.onnx.data` files from it byte-exactly (fp32)". So the *download* can stay at ~17 MB and
  the app can expand to fp16 at import; on-disk after import ≈ 73 MB, resident ≈ 75–150 MB while
  a session is open **[U]**. https://huggingface.co/mrfakename/whistle-ONNX/tree/main/onnx
- Decoder step I/O: `token`, `pos`, engram features `efeat [2,4,512]` computed on the host from
  token history, `prev [8,2,608]`, `past_k/past_v`, cross K/V from the encoder; outputs logits
  (8,199), caches, and cross-attention for timestamps. Decoding in that port is greedy (Cactus
  uses 5 beams), keyword bias is "+2 logit on the first token, +5 on continuations", an
  approximation. https://huggingface.co/mrfakename/whistle-ONNX/resolve/main/README.md
- The encoder's Sinkhorn step needed a numerically safe log-sum-exp for WebGPU; CPU is fine
  (same page). Opset and operator coverage against `onnxruntime-android:1.21.1` are
  **[U]** until loaded; `ModelLoader.kt` already prefers XNNPACK, then NNAPI, then CPU.
- A 4-bit `MatMulNBits` re-quantisation to cut the fp16 size would be our own work **[U]**;
  Cactus's 2-bit codebook format is not an ORT dtype.

Details for A, should the maintainer ever accept a non-F-Droid build: the C API is one
process-global, non-thread-safe model per kind; `needle_load(bytes, n)` takes the whole
`.cact` in memory; `needle_transcribe` returns JSON; streaming feeds ~1 s chunks
(https://huggingface.co/Cactus-Compute/needle3/resolve/main/android-arm64/needle.h). The
`.cact` is "tied to the engine version", so weights and engine must be pinned together
(https://cactuscompute.com/blog/needle-python-docs#mistakes-to-avoid).

ABI note: CleverKeys builds `armeabi-v7a`, `arm64-v8a` and `x86_64` splits (`build.gradle`
`splits.abi.include`); Cactus publishes no `android-x86_64`
(https://huggingface.co/Cactus-Compute/needle3/tree/main), so option A would silently lack the
feature on x86_64 (emulators, some ChromeOS). Options C and D cover all three.

APK impact summary: C adds no native code (ORT is already 17.9 MB uncompressed in the arm64
split); A adds roughly 2 MB per ABI; D adds a small engine plus the build infrastructure cost.
Weights never go into the APK under any option: 16.9 MB is more than the entire CTC swipe
encoder (3.05 MB) and would undo the 44→18 MB APK diet recorded in
`memory/` (`v2-release-state`).

---

## 3. Plugin delivery without INTERNET

| | (a) Voice pack via SAF, runtime in the APK | (b) Companion APK (own `RecognitionService`) | (c) System `SpeechRecognizer` / on-device recogniser |
| --- | --- | --- | --- |
| How | ZIP like a language pack: `manifest.json`, weights, `sha256`, `LICENSE`/`NOTICE.txt`; imported with the file picker; `LanguagePackSwap`-style atomic install and rollback | Second F-Droid app "CleverKeys Voice" exposing `android.speech.RecognitionService`; CleverKeys binds it with `createSpeechRecognizer(ctx, ComponentName)` after `queryIntentServices` | `SpeechRecognizer.isOnDeviceRecognitionAvailable()` / `createOnDeviceSpeechRecognizer()` (API 31+) or the default recogniser with `EXTRA_PREFER_OFFLINE` |
| Privacy | Best: one process, no IPC, audio never leaves the IME | Audio is captured by the service process; both apps offline; `<queries>` needed on API 30+ | Depends on the vendor; on GMS devices it is Google's recogniser; the API doc warns it "is likely to stream audio to remote servers" unless the on-device variant is used |
| Size | ~17 MB download (expands to ~73 MB fp16 under option C) | Same model size in the other APK; two apps to install | Zero |
| F-Droid | Clean if the runtime is FLOSS (C or D). A `.so` in the pack is forbidden without opt-in consent | Clean (Sayboard does it: GPL-3, Vosk, RecognitionService; note its changelog: "Do to a bug in Android, this requires the QUERY_ALL_PACKAGES permission") | **NonFreeDep** when it only works with Google's recogniser; absent on GMS-free phones |
| Maintenance | We own one runtime port and the pack format | Two apps, an AIDL/`RecognitionService` contract, two release trains | Nothing to maintain, nothing to control (languages, latency, partial results vary per vendor) |
| Streaming into the editor | Full control (section 4) | `onPartialResults` / `onResults` bundles; `RESULTS_RECOGNITION`, `CONFIDENCE_SCORES` | Same API, vendor-dependent support |
| Needs `RECORD_AUDIO` in CleverKeys | Yes (we open the mic) | **Yes**: "the application must have `Manifest.permission.RECORD_AUDIO` permission to use this class" | Yes |

Sources: https://developer.android.com/reference/android/speech/SpeechRecognizer (RECORD_AUDIO,
`createOnDeviceSpeechRecognizer` API 31, `isOnDeviceRecognitionAvailable`, `<queries>` for
API 30+, main-thread requirement, "not intended to be used for continuous recognition"),
https://developer.android.com/reference/android/speech/RecognitionService (`SERVICE_INTERFACE`,
`SERVICE_META_DATA`, `onStartListening`/`onStopListening`/`onCancel`),
https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_PREFER_OFFLINE,
https://f-droid.org/en/packages/com.elishaazaria.sayboard/.

What CleverKeys does today is a fourth path that already works: the `voice_typing` key fires
`SWITCH_VOICE_TYPING`, `VoiceImeSwitcher` lists enabled IMEs with a subtype of mode `voice`,
remembers the last one, or shows the chooser; with none installed it shows
`toast_no_voice_input` ("No voice typing app installed") and `LayoutModifier` hides the key
(`shouldOfferVoiceTyping`). FUTO Voice Input and FUTO Keyboard expose exactly that `voice`
subtype and list Unexpected Keyboard v1.23+ as supported
(https://github.com/futo-org/voice-input#api-support); Sayboard, Transcribro and (where present)
Google's voice IME do too. So "offline dictation for CleverKeys users" is available now through
a separate app; what Whistle would add is an in-keyboard, no-IME-switch experience under our own
UI and privacy story.

Recommendation among (a)–(c): **(a)**, with (c) kept as the zero-cost fallback the mic key can
offer when no pack is installed and an on-device recogniser exists (`isOnDeviceRecognitionAvailable`
true), flagged in the UI as "system recogniser". (b) only makes sense if the maintainer wants the
Cactus binary engine despite F-Droid — then the companion can live on GitHub Releases while the
keyboard stays clean — and that is a product decision, not a technical preference.

### 3.1 Pack format sketch (reusing the language-pack code)

`LanguagePackManager` already handles: SAF `Uri` → temp dir in `cacheDir`, manifest parse,
member size caps enforced during extraction, a declared `model` member verified against its
manifest sha256 at import (integrity) and against a sha256 compiled into the APK at load
(security), `NOTICE.txt` retained and shown, `LanguagePackSwap` for crash-safe install/remove
(`docs/specs/dictionary-and-language-system.md` "Import and loader bounds (GH #184)"). A voice
pack needs:

- its own directory (`files/voicepacks/<id>/`) and manifest kind (`"kind": "voice"`), so the
  language-pack code paths (dictionary count checks, 100,000-word limit) are not run on it;
- a **new size cap**: the language-pack model cap is 8 MiB and `whistle.cact` is 16.9 MB; the
  ONNX route needs either the 17 MB pack plus an expansion step or the ~73 MB fp16 files
  directly (then the cap and the free-space check are the open questions);
- the load-time pin: the APK carries the sha256 of every weights file it will run, exactly as
  `CtcPackModel` refuses an unpinned `model.onnx`; a new Cactus `weights_version` means a new
  CleverKeys release, which is the right coupling given "the .cact format is tied to the engine
  version";
- `languages`, `depth` (if we honour the ladder) and `license`/`attribution`/`source` keys;
- hosting: the existing `langpacks` GitHub release is the precedent (`langpack-fa.zip` was
  published there with a verified sha256, `memory/todo.md` item 2); re-hosting Apache weights is
  permitted with LICENSE + NOTICE, or the pack can point users at the Hugging Face file and carry
  only metadata — but then import must accept a bare `.cact`/`.onnx.data` plus a sidecar, which
  is more UI.

---

## 4. IME integration

**Mic key and command.** Keep `voice_typing` (switch to a voice IME) and add a local command
(`dictate` **[name TBD]**) in `CommandRegistry`/`AvailableCommand`, so Short Swipes and the
command palette can bind either. The key's visibility logic in `LayoutModifier`
(`shouldOfferVoiceTyping`) should become "a voice IME exists OR a voice pack is installed OR an
on-device system recogniser exists". The wiki palette page and README carry the command count
(240 on 2026-10-20, `memory/MEMORY.md`); both change.

**RECORD_AUDIO.** It is a dangerous permission; `Activity.requestPermissions` is the only way to
ask (https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/permission/Permissions.md).
An `InputMethodService` has no activity, so the AOSP keyboard ships a transparent
`PermissionsActivity` "used when no other activity is available, e.g. in InputMethodService"
(https://raw.githubusercontent.com/LineageOS/android_packages_inputmethods_LatinIME/lineage-22.2/java/src/com/android/inputmethod/latin/permissions/PermissionsActivity.java).
CleverKeys would do the same: first mic tap → activity with `FLAG_ACTIVITY_NEW_TASK |
FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` → system dialog → result back to the service; a denial
turns the key into an explainer. The manifest line is already there, commented out
(`AndroidManifest.xml` line 11). Adding it changes the F-Droid permission list for every user,
including the majority who will never import a pack; the maintainer must accept that (section
6). There is no way to request it only for pack users: `<uses-permission>` is static.

**Capture.** `AudioRecord`, `VOICE_RECOGNITION` source, 16 kHz mono PCM16 → float. Android 12+
shows the microphone privacy indicator while the IME records **[U]** (platform behaviour, not
re-verified from the Android 12 page this session). Android 14's `microphone` foreground-service
type exists to "Continue microphone capture from the background, such as voice recorders or
communication apps" and "you cannot create a microphone foreground service while your app is in
the background" (https://developer.android.com/about/versions/14/changes/fgs-types-required);
dictation only while the keyboard is visible needs no foreground service **[U]** and must stop
on `onFinishInputView`. A VAD/energy gate in front (Silero VAD, MIT, 0.9 MB ggml or an ONNX
build that ORT can run) both saves battery and suppresses the "Thank you." hallucination
(https://huggingface.co/ManniX-ITA/whistle-GGUF/resolve/main/README.md,
https://github.com/andrisgauracs/whistle-esp32#troubleshooting).

**Streaming partial results into the editor.** Whistle's streaming contract maps well onto
`InputConnection`: words the engine *commits* go through `commitText`; the *pending* tail is
`setComposingText` (underlined, replaceable). Lessons already recorded in `CLAUDE.md` apply:
"`InputConnection.commitText` replaces an active composing span even when a different selection
was verified" (2026-10-06) — finish composition before any explicit write; batch edits are not
atomic; Chrome does not answer `getTextBeforeCursor` inside a batch edit (2026-10-25); long
results go through `LargeTextCommit` chunking (192 KiB wire budget). Committed dictation should
enter through the same `sendText`/`KeyEventHandler` path as typed text so auto-space,
capitalisation after `.`, owned-suffix bookkeeping and the suggestion bar stay consistent, and
should bypass learning funnels the way inline fields do (`.claude/skills/ime-key-routing.md`
"Swipe typing into inline fields": "Learning: none").

**Blind/REMOTE and terminal fields.** `TerminalUtils.editorKind` returns `TERMINAL` for Termux
and the user's list, `REMOTE` for RustDesk-class apps and any `TYPE_NULL` editor; "Every
no-rewrite branch in the IME asks this one predicate." Dictation there must be append-only: no
composing span, no retroactive correction of committed words, commit only when the engine
commits (or at stop), exactly like terminal taps (`TerminalSuggestionPolicy`,
`docs/specs/termux_integration.md`). The two-pass commit scheme makes this natural: nothing
committed ever changes.

**Inline fields** (the keyboard's own search/emoji/clipboard query fields): route dictation
through the same inline-field text path (no learning, no smart punctuation), and refuse it in
password fields.

**Cancellation.** Mic tap again, Back, `onFinishInputView`, editor change
(`onStartInput` with a new `EditorInfo`), phone call/audio focus loss: stop `AudioRecord`, drop
the pending tail (or flush it with `needle_stream_transcribe_stop` semantics — a setting), clear
the composing region, release the session. Guard against the Compose/dialog stacking and
view-init traps already noted in `CLAUDE.md`.

**Accessibility.** `KeyboardAccessibilityHelper` already exposes keys and (since 2026-10-26)
short-swipe outputs as custom actions; the mic key needs state in its content description
("Dictation, listening" / "Dictation, stopped"), an announcement on start/stop, and committed
words should not be re-announced as they appear (TalkBack reads editor changes already); the
pending tail should not be announced on every re-pass. Haptic on start/stop; visual level meter
in the suggestion-bar row.

**Language selection.** Per-layout language binding (GH #186/#61) gives a default language for
the clip (`language="de"` instead of detection), which also sidesteps the nl/de confusion
reported above; Multi-Language mode can leave detection on.

**Keyword biasing.** Feed the user dictionary (words the user added) and, optionally, the
current field's recent words; never contacts (that would need another permission and a privacy
discussion). This is the single cheapest accuracy win Cactus documents (section 1.2).

---

## 5. Alternatives

| Engine / model | Licence (code / models) | Size on device | Languages | Quality (as published) | Streaming | Android story | F-Droid-clean? | Sources |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Whistle** (Cactus) | Apache-2.0 weights; official engine Apache-2.0 **binary only**; FLOSS ports exist | 16.9 MB (`.cact`); ~73 MB as fp16 ONNX | 7 (en de fr es it nl pl) | LS clean 4.31 %, other 10.49 %, FLEURS avg 21.4 %, MLS 24.9 % | two-pass commit (engine); not in the ONNX port yet | needle `libneedle.a` arm64/armv7/riscv64; or ORT | Only via a FLOSS port | section 1 |
| **whisper.cpp** (ggml) | MIT; OpenAI Whisper weights MIT | tiny 75 MiB, base 142 MiB, small 466 MiB (ggml) | multilingual checkpoints (FUTO exposes 17 with >1,000 training hours) | Whisper base beats Whistle on TED-LIUM, AMI, MLS; loses on LibriSpeech, SPGI, Earnings-22, FLEURS (Cactus chart) | chunked, not true streaming | mature Android example; FUTO and Transcribro use it | Yes (source build) | https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md, https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/LICENSE, https://raw.githubusercontent.com/openai/whisper/main/LICENSE |
| **Vosk** (Kaldi) | Apache-2.0 (vosk-api); most small models Apache-2.0 | small models ~40–50 MB, "about 300 Mb of memory in runtime" | 20+ with small models (en 40 M, de 45 M, fr 41 M, es 39 M, it 48 M, nl 39 M, pl 50 M …) | small en-us 0.15: 9.85 % LS clean, 10.38 % TED-LIUM | yes, native streaming, dynamic vocabulary | official Android lib; Sayboard (F-Droid, GPL-3) and vosk-android-service | Yes | https://alphacephei.com/vosk/models, https://raw.githubusercontent.com/alphacep/vosk-api/master/COPYING |
| **Moonshine Voice** | MIT code; models MIT by default (legacy non-English non-streaming under a non-commercial Community licence) | Tiny Streaming 34 M params (int8 `.ort`; Cactus lists Moonshine tiny v2 at 41.9 MB) | en, ar, de, ja, zh, es, tl, vi streaming; ko, uk legacy | Tiny Streaming LS clean 4.83 % quantised, Open ASR avg 12.0 %; Small Streaming 2.61 % / 7.84 % (123 M) | yes, designed for it | one library across Android/iOS/…; ORT-based | Yes | https://moonshine-voice.readthedocs.io/en/latest/models/available-models/, https://moonshine-voice.readthedocs.io/en/latest/models/accuracy/, https://github.com/moonshine-ai/moonshine |
| **sherpa-onnx** (k2-fsa) | Apache-2.0 | engine bundles its own ORT build; model sizes per model (tens of MB for int8 Zipformers **[U]**) | many; runs Zipformer, Paraformer, SenseVoice, Whisper, Moonshine | per model | yes (streaming Zipformer transducers) | first-class Android incl. x86_64 build scripts, WearOS, VAD, keyword spotting | Yes | https://github.com/k2-fsa/sherpa-onnx, https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/LICENSE |
| **FUTO Voice Input / FUTO Keyboard** | FUTO Source First 1.0: non-commercial use and free-of-charge distribution only — not OSI open source | Whisper via whisper.cpp | 17 listed (>1,000 training hours each) | Whisper-class | VAD + chunks | an IME `voice` subtype and the `RECOGNIZE_SPEECH` intent; **not** the `SpeechRecognizer` API; already usable from CleverKeys' mic key | No (licence) | https://github.com/futo-org/voice-input, https://github.com/futo-org/voice-input/blob/master/LICENSE.md |
| **Transcribro** | ISC; whisper.cpp + Silero VAD | Whisper | English only (others planned) | Whisper-class | — | voice IME and `RecognitionService`; Accrescent/GitHub, not F-Droid | n/a | https://github.com/soupslurpr/Transcribro |

Reading the table honestly: on size Whistle is unmatched (one 17 MB file for seven languages vs
~40 MB per language for Vosk and 75–142 MiB for Whisper tiny/base); on English accuracy
Moonshine Tiny/Small Streaming are as good or better and come with a FLOSS, ORT-based,
streaming-first runtime that already targets Android; Vosk is the most battle-tested on F-Droid
keyboards and the lightest on compute, at a WER cost. If the goal is "best FLOSS dictation in
the keyboard", sherpa-onnx or Moonshine are the lower-risk engines; if the goal is "smallest
multilingual pack", Whistle is, provided a port proves fast enough on phones.

---

## 6. Recommendation, phased plan, decisions

### Recommendation

1. **Do not** integrate Cactus's prebuilt engine or the Cactus Kotlin SDK into the F-Droid
   build: no engine source, a non-FLOSS SDK licence, default-on telemetry in the SDK, no x86_64
   build, and a `.cact`↔engine version coupling we would not control.
2. **Do** keep the pack-as-data model: weights by SAF import, runtime in the APK, FLOSS only.
3. **Spike first, decide second.** The entire case for Whistle over Moonshine/sherpa-onnx rests on
   phone-side latency and RAM of a FLOSS port, and nobody has published those numbers.
4. Whatever the engine, the IME work in section 4 (permission activity, capture, streaming
   commit/compose, REMOTE policy, cancellation, TalkBack) is engine-independent and is the larger
   half of the effort; design it behind a small `Dictation` interface so the engine is swappable.

### Phased plan

**Phase 0 — spike (agent, 2–4 days, no app code shipped).** A throwaway instrumented test or a
`debug` source-set activity on the Pixel 8 Pro and a Saga, using the ORT the APK already has:
load `encoder_fp16.onnx`/`decoder_fp16.onnx`, port the JS log-mel + BPE + engram pieces to Kotlin
just far enough to transcribe 5 s / 10 s / 30 s LibriSpeech clips; record time to first token,
real-time factor, peak RSS, output parity against the published reference clips; run the same
clips through sherpa-onnx with Moonshine Tiny Streaming int8 and a streaming Zipformer as the
baseline. Gate (proposal): RTF ≤ 0.3 on the Pixel big cores with XNNPACK, peak RSS ≤ 150 MB,
parity on the reference clips. If Whistle fails the gate and Moonshine passes, the rest of the
plan proceeds with Moonshine and section 3.1's pack carries `.ort` files instead.

**Phase 1 — pack format (1–2 weeks).** `VoicePackManager` beside `LanguagePackManager`
(shared swap/recovery, caps, sha256 pin in the APK, NOTICE display), manifest kind `voice`,
Settings "Voice packs" section with import/remove and the licence text, backup/restore
awareness (packs are not in backups, as language packs are not), `pureTestClasses` coverage
for refusal/rollback mirroring `LanguagePackSwapTest`.

**Phase 2 — IME (2–3 weeks).** `RECORD_AUDIO` + `PermissionsActivity`, `AudioRecord` capture
with VAD gate, the `Dictation` session (start/partial/commit/stop/cancel), `commitText` /
`setComposingText` policy with the REMOTE/TERMINAL append-only branch and the inline-field
branch, mic key state + command + palette entry, TalkBack strings in all 21 locales
(`TranslationFormatParityTest`), Settings: default language from layout binding, flush-or-drop
on cancel, keyword bias from the user dictionary. Device checks on the Pixel, a Saga (RustDesk
and Termux fields) and the Seeker (landscape content pane height).

**Phase 3 — polish (1 week).** Depth setting (speed vs accuracy), per-language detection
toggle, level meter, wiki page and README command count, release notes.

Effort is a working estimate **[U]**; the spike will sharpen it.

### Risks

- Latency/RAM on mid-range phones unknown; an fp16 ONNX graph is ~4× the resident size of the
  2-bit engine.
- Accuracy for conversational, accented or mumbled speech is reported as weak; nl detection is
  weak; music/noise can hallucinate text; the 30 s window needs the sentence-cut logic.
- Format churn: `weights_version` and engine releases move together at Cactus; our pinned sha
  means a new weights drop needs a CleverKeys release.
- Porting debt: we maintain the Kotlin pipeline (mel, BPE, engram, decode, timestamps) ourselves;
  the ONNX port is one person's repo (23 days old).
- Permission optics: `RECORD_AUDIO` appears for all users.
- Scope creep: dictation touches auto-space, suggestion bar, learning, blind fields and
  accessibility, all areas with documented regressions in this repo.

### Decisions needed from the maintainer

1. **Dependency licence policy.** FLOSS-only runtime (ORT port or a C99 port built from source)
   versus accepting a prebuilt Cactus engine in a non-F-Droid companion APK or GitHub-only
   build. This note assumes FLOSS-only.
2. **`RECORD_AUDIO` in the main APK** (needed for options a and c alike) versus pushing all
   dictation into a companion app so the keyboard's permission list stays as it is.
3. **Engine choice gate**: agree the Phase-0 thresholds, and that Moonshine/sherpa-onnx is the
   fallback if Whistle misses them.
4. **Pack hosting and naming**: re-host Apache weights in the `langpacks` GitHub release with
   LICENSE + NOTICE (precedent: `langpack-fa.zip`) versus metadata-only packs pointing at Hugging
   Face; whether the pack may be called "Whistle" (descriptive use under Apache §6).
5. **Languages and depth** to ship first (en only, or all seven with per-layout defaults).
6. Whether to **also** surface the system on-device recogniser (option c) with a NonFreeDep
   disclosure, or leave that to users' voice IMEs as today.

---

## Sources

Cactus: https://cactuscompute.com/blog/whistle · https://cactuscompute.com/blog/whistle-best-practices ·
https://cactuscompute.com/blog/needle-supported-devices · https://cactuscompute.com/blog/porting-needle ·
https://cactuscompute.com/blog/needle-python-docs · https://github.com/cactus-compute/needle ·
https://github.com/cactus-compute/needle/tree/main/needle · https://github.com/cactus-compute/cactus/blob/main/LICENSE ·
https://github.com/cactus-compute/cactus-kotlin · https://huggingface.co/Cactus-Compute/whistle ·
https://huggingface.co/Cactus-Compute/whistle/resolve/main/README.md ·
https://huggingface.co/Cactus-Compute/whistle/tree/main · https://huggingface.co/Cactus-Compute/whistle/tree/main/checkpoints ·
https://huggingface.co/Cactus-Compute/whistle/resolve/main/config.json ·
https://huggingface.co/Cactus-Compute/whistle/resolve/main/assets/whistle-benchmarks.svg ·
https://huggingface.co/Cactus-Compute/needle3/tree/main · https://huggingface.co/Cactus-Compute/needle3/tree/main/android-arm64 ·
https://huggingface.co/Cactus-Compute/needle3/resolve/main/android-arm64/needle.h ·
https://huggingface.co/Cactus-Compute/needle3/resolve/main/LICENSE · https://huggingface.co/Cactus-Compute/needle3/resolve/main/README.md

Ports and third-party: https://github.com/andrisgauracs/whistle-esp32 ·
https://huggingface.co/mrfakename/whistle-ONNX/resolve/main/README.md · https://huggingface.co/mrfakename/whistle-ONNX/tree/main/onnx ·
https://huggingface.co/ManniX-ITA/whistle-GGUF/resolve/main/README.md ·
https://huggingface.co/models?other=base_model:quantized:Cactus-Compute/whistle ·
https://news.ycombinator.com/item?id=50008427 · https://www.eesel.ai/blog/cactus-whistle

Android: https://developer.android.com/reference/android/speech/SpeechRecognizer ·
https://developer.android.com/reference/android/speech/RecognitionService ·
https://developer.android.com/reference/android/speech/RecognizerIntent ·
https://developer.android.com/about/versions/10/behavior-changes-10 ·
https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/permission/Permissions.md ·
https://raw.githubusercontent.com/LineageOS/android_packages_inputmethods_LatinIME/lineage-22.2/java/src/com/android/inputmethod/latin/permissions/PermissionsActivity.java

F-Droid: https://f-droid.org/en/docs/Inclusion_Policy/ · https://f-droid.org/en/docs/Anti-Features/ ·
https://f-droid.org/en/packages/com.elishaazaria.sayboard/

Alternatives: https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md ·
https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/LICENSE · https://alphacephei.com/vosk/models ·
https://raw.githubusercontent.com/alphacep/vosk-api/master/COPYING · https://github.com/moonshine-ai/moonshine ·
https://moonshine-voice.readthedocs.io/en/latest/models/available-models/ ·
https://moonshine-voice.readthedocs.io/en/latest/models/accuracy/ · https://github.com/k2-fsa/sherpa-onnx ·
https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/LICENSE · https://github.com/futo-org/voice-input ·
https://github.com/futo-org/voice-input/blob/master/LICENSE.md · https://github.com/soupslurpr/Transcribro ·
https://raw.githubusercontent.com/openai/whisper/main/LICENSE · https://raw.githubusercontent.com/microsoft/onnxruntime/main/LICENSE ·
https://developer.android.com/about/versions/14/changes/fgs-types-required

Not reachable from this box (snippets only, hence nothing quoted): the Reddit r/LocalLLaMA launch
thread (fetcher refuses reddit.com); the Medium write-up (paywalled; its "55-million-parameter"
figure agrees with the HF listing used above).
