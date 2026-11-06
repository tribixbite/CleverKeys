# Voice input (on-device speech to text)

## Feature Overview
**Feature Name**: Voice input — offline dictation inside the keyboard
**Priority**: P1
**Status**: In Progress (engine, pack format, import, IME wiring and settings implemented
2026-10-29; the pack is on the `langpacks` release since 2026-10-29; endpointing 2026-10-31;
device checks outstanding)
**Target Version**: 2.0.0 (maintainer decision 2026-10-29; first proposed for 2.1.0)

### Summary
A `voice_input` key/command records from the microphone while the keyboard is visible and types
what was said, using a speech model the user imports as a **voice pack** (a ZIP from the
`langpacks` GitHub release, imported with the file picker like a language pack). Recognition runs
on the ONNX Runtime the app already ships; no audio is stored and nothing leaves the device.

### Motivation
Users asked for dictation without switching to another voice IME. The research notes
`docs/research/2026-10-28-whistle-offline-stt.md` and
`docs/research/2026-10-28-stt-engine-deep-dive.md` measured three FLOSS engines on the app's exact
ORT 1.21.1 natives and recommended **Moonshine v2 tiny-streaming** (MIT weights and runtime,
incremental encoder, partial after 1.0 s, commit after 2.0 s, ~45 MB of official quantised
graphs, ~112 MB steady RSS with the ORT arena off).

### Maintainer decisions (2026-10-29)
1. Engine: Moonshine v2 tiny-streaming.
2. The model is an **opt-in import** from the `langpacks` release; the app keeps **no INTERNET
   permission**.
3. **No microphone permission is requested** until the user turns voice input on or imports a
   voice pack. (`RECORD_AUDIO` is declared in the manifest — `<uses-permission>` is static — but
   a dangerous permission is only *granted* through a runtime request, which happens only then.)
4. The code is **modular**: the engine will be replaced (Moonshine v3 or a successor). Swapping
   it is a new engine class, one registry entry and a new pack — nothing else changes.

## Requirements

### Functional Requirements
1. **FR-1** Engine abstraction (`stt/SpeechEngine`): load from a pack directory, open sessions,
   accept 16 kHz mono float PCM, emit partial and final hypotheses with stability information,
   cancel, close, report memory.
2. **FR-2** `SpeechEngineRegistry` resolves a pack's manifest `engine.id` + `engine.version` to a
   factory. Unknown id or an engine version outside the supported range is refused with "This
   voice pack needs a newer version of CleverKeys".
3. **FR-3** Voice pack format (manifest v1, below), SAF import with streamed extraction, per-member
   and total size caps, sha256 of every declared file, atomic install/replace, rollback,
   crash recovery and localized refusal messages — reusing `LanguagePackSwap` and the shared
   bounded extraction of the language-pack importer.
4. **FR-4** `voice_input` key and command (command palette, Short Swipes, extra keys). Tapping it
   starts listening; tapping again (or the bar's ✕) stops. A partial hypothesis is shown as
   composing text, stable words are committed.
4a. **FR-4a** Voice keys (2026-10-30, maintainer report: with a pack installed the mic key
   still offered only Google's voice IME, and with that disabled said "No voice typing app
   installed"). One rule, `VoiceKeyRouting`, applied by `VoiceImeSwitcher.onVoiceTypingKey` /
   `onVoiceChooserKey` for both `voice_typing` (the mic key and default extra key) and
   `voice_typing_chooser` (its long press):
   - built-in **ready** = voice input switched on AND a usable pack installed (the microphone
     permission is not part of it; the built-in path asks for it);
   - `voice_typing`: built-in when ready; otherwise the remembered system voice IME (chooser on
     first use or when the IME set changed, as before); with neither, the built-in path, whose
     bar message says what is missing ("Voice input is off…" / "No voice pack installed…") and
     opens Settings → Voice input on a second tap — the old dead-end dialog is gone, and the mic
     key is no longer hidden after a failed press;
   - `voice_typing_chooser`: a chooser only when there are two or more entries — "CleverKeys
     voice (offline)" first when ready, then every system voice IME; a single entry is taken
     directly; none goes to the built-in path. Picking a system IME is remembered for
     `voice_typing` while the built-in engine is not ready.
   Tests: `VoiceKeyRoutingTest` (pure, every combination).
4b. **FR-4b** A voice key in a letter's corner must not hijack word swipes (2026-10-30: the
   default extra-key placement put the mic on `a`; `Pointers.onTouchMove` fired Event subkeys
   as soon as the finger moved `swipe_dist_px` toward them, so "acid"-shaped swipes became voice
   presses and, held, the chooser dialog, over a trail that was never cleared). A gesture that
   may still be a word swipe (letter start, swipe typing on, not "short swipes only") leaves
   Event subkeys to the release; the eager path that remains (space-bar switches) resets the
   recognizer; a first finger down drops any press whose release never arrived. Tests:
   `PointersEventSubkeyWordSwipeTest` (mock).
5. **FR-5** Field policy: ordinary editors get composing partials + committed finals with
   auto-space and sentence capitalisation; blind REMOTE and TERMINAL editors receive **append-only
   final text** (no composing span, no rewrite); the keyboard's own inline fields (clipboard
   search/editor/tag, emoji and GIF search) receive final text through the inline-field path;
   password fields refuse dictation.
6. **FR-6** Settings → Voice input (off by default): enable switch, import/remove pack, pack
   name, size, engine, languages and licence/NOTICE. Turning it on or importing a pack is the
   ONLY moment the microphone permission is requested, via a transparent activity.
7. **FR-7** Denied permission → voice stays off and the section/bar explains why; "don't ask
   again" → a button opens the app's system settings page. One policy for Settings and the
   keyboard (`MicPermissionGate`, 2026-10-30): `afterRequest(granted, showRationale)` reads every
   answer the same way (GRANTED turns voice on; DENIED toasts; BLOCKED = denied with no rationale
   → remember it and explain with "Open app settings"). The Settings switch and a pack import
   ALWAYS ask Android, even when the app remembers a block — the remembered state goes stale when
   the user changes the permission in system settings (Saga 2026-10-30: the switch then neither
   prompted nor responded), and a real block is answered at once without a prompt, which opens
   the "Microphone access is blocked" dialog (App info → Permissions → Microphone → Allow). The
   switch stays off until the permission is granted; coming back from App info with it granted
   turns voice input on (`turnOnAfterAppSettings`), and a granted permission clears the
   remembered block. The keyboard's voice key keeps trusting the remembered block (asking would
   flash an activity over the app being typed in): it explains in the bar and a second press
   opens App info; when its own request is refused without a prompt, `MicPermissionActivity`
   shows the same dialog as Settings.
8. **FR-8** Capture only while listening; stop on keyboard hide, field change, editor switch,
   `onDestroy`, cancel; cancel (✕, keyboard hide) discards uncommitted (composing) text. A field
   change ends the period WITHOUT any text operation (`VoiceEvent.FieldChanged` →
   `VoiceAction.Release`): the old field already finished its own composition on blur (Chrome
   and EditText keep the underlined words; the keyboard cannot reach that field any more), and
   the connection now belongs to the new field, where a clear would replace its selection.
9. **FR-9** TalkBack: the key's content description carries the state; start/stop/cancel and
   errors are announced; partials are not announced.
10. **FR-10** Endpointing (2026-10-31): a listening period is split into **speech segments** at
   pauses, each recognised by a fresh engine session and committed when it ends, so dictation is
   no longer cut at the engine's coherent length (Moonshine v2 tiny loops past ~40 s; the keyboard
   used to stop at 30 s) and the cost of a recognition pass no longer grows with the period.
   - Engine-neutral, in `stt/`: `SpeechSegmenter` (energy VAD) → `SegmentedRecognizer` (one
     `SpeechSession` per segment) → `VoiceEvent.SegmentFinal` → `VoiceTextPlanner.onSegmentEnd`.
     An engine only declares `SpeechEngine.maxSegmentSeconds` (default 30; Moonshine 20).
   - Approach of the official Moonshine runtime (`core/voice-activity-detector.cpp`, MIT):
     512-sample (32 ms) hops, a 0.512 s look-behind in front of each segment, a maximum segment
     length. That runtime scores hops with the Silero VAD model, which the pack format does not
     carry; here a hop is **voiced** when its RMS level is ≥ 12 dB above the noise floor and above
     −50 dBFS; the floor is the quietest hop of the last 3 s (minimum statistics; speech has quiet
     hops every few hundred ms, so the floor follows the room), primed at −60 dBFS so a user who
     talks at once is heard at once.
   - A segment **opens** when 4 of the last 8 hops are voiced (a click does not open one) and
     starts with the look-behind; it **ends** after 0.6 s (19 hops) without a voiced hop — the
     silent hops are included so the engine can finish the last word. Silence between segments
     reaches no engine (no cost, nothing to hallucinate on).
   - **Bound**: in the last 2 s before `maxSegmentSeconds`, hops are held back; a pause there
     ends the segment as usual; reaching the bound cuts it after the quietest held hop (level
     averaged over three hops) and the remaining held hops start the next segment at once.
     Nothing is lost or sent twice at a boundary (`SpeechSegmenterTest`). Partials pause for at
     most those 2 s, only in continuous speech approaching the bound.
   - **Text**: each segment's final is committed (TEXT fields: its composing tail becomes
     committed text); the next segment's spacing and capitalisation are decided from the text
     before the period plus everything the period committed — one space between segments, none
     doubled with "auto space after a suggestion" (that space follows each segment), a capital
     only after `.`/`?`/`!` (the engine punctuates), the engine's capital undone mid-sentence.
     Blind/remote fields still receive finals only, appended; the planner knows its own text there
     without reading the field, so segments are separated by one space.
   - **Limits** (`VoicePeriodLimits`): a period stops by itself after **5 minutes** or after
     **10 s without speech** (before the first word or since the last segment ended); the user's
     tap, cancel, keyboard hide and field change stop it as before. The buffered microphone ends
     the period if 70 s of audio are queued unread (a recogniser that is not catching up).

### Non-Functional Requirements
1. **NFR-1** Performance: ≤ 1 s chunks (1.0 s chunks proved the floor for two-pass agreement),
   decoding off the main thread, CPU EP with ORT's arena ON for the streaming sessions (since
   2026-11-05; it is freed when the idle engine is released; XNNPACK is not used, research §6.7.1), intra-op threads
   2. Session load from `.ort` files (0.3–0.8 s measured on the JVM).
2. **NFR-2** Privacy: no audio file is written; PCM lives in memory for the session only; the
   app has no network permission.
3. **NFR-3** Reliability: a pack can never be half-installed (manifest copied last, swap renames);
   an engine failure ends the session with a bar message, never a crash.
4. **NFR-4** APK size: the model is never in the APK (pinned by `VoicePackAssetDriftTest`).

## Technical Design

### Architecture
```
 voice_input key / command
        │  KeyValue.Event.VOICE_INPUT → KeyboardReceiver → service.voiceInput
        ▼
 VoiceInputController (Android; stt/)
   ├─ MicPermissionGate (pure) ── MicPermissionActivity (transparent, requests RECORD_AUDIO)
   ├─ VoicePackManager (pure JVM over files/voicepacks/) ── VoicePackManifest (pure)
   ├─ SpeechEngineRegistry (pure) ──► SpeechEngineFactory ──► SpeechEngine ──► SpeechSession
   │                                    └─ stt/moonshine/MoonshineV2StreamingEngine (ONLY place
   │                                       that knows Moonshine: five .ort graphs + tokenizer.bin)
   ├─ AudioCapture (AudioRecord, 16 kHz mono, VOICE_RECOGNITION) — only while listening
   ├─ VoiceSessionMachine (pure state machine)
   └─ VoiceTextPolicy (pure) ──► VoiceTextSink: app InputConnection | inline field
 SuggestionBar voice mode: "Listening…" + partial text + ✕ cancel
```

### Swapping the engine (the modularity contract)
- `stt/` owns the interfaces, the registry, the pack format and every policy; it is
  engine-neutral.
- `stt/moonshine/` holds the only Moonshine code. `SttArchitectureDriftTest` fails when any file
  outside `stt/moonshine/` refers to `stt.moonshine` or `Moonshine`, except the one registration
  line in `stt/SpeechEngineRegistry.kt`.
- A new engine: add `stt/<engine>/<Engine>.kt` implementing `SpeechEngineFactory`, add one entry
  to `SpeechEngineRegistry.DEFAULT_FACTORIES`, publish a pack whose manifest names the new
  `engine.id`. Old packs keep working while their factory is registered; a pack for an engine
  this build does not know is refused with the "newer CleverKeys" message.

### Voice pack format (manifest v1)
```json
{
  "kind": "voice",
  "format": 1,
  "id": "en-moonshine2-tiny",
  "name": "English (Moonshine v2 tiny streaming)",
  "version": 1,
  "engine": { "id": "moonshine-v2-streaming", "version": 1 },
  "languages": ["en"],
  "sampleRate": 16000,
  "minAppVersionCode": 0,
  "files": [ { "path": "encoder.ort", "sha256": "…", "size": 7675440 }, … ],
  "license": "MIT",
  "attribution": "Moonshine v2 tiny-streaming © 2025 Useful Sensors, Inc. (dba Moonshine AI), MIT",
  "source": "https://huggingface.co/moonshine-ai/moonshine-voice-assets/tree/<rev>/model/tiny-streaming-en/quantized_26_08_21"
}
```
Plus `LICENSE` and `NOTICE.txt` members. Rules (`VoicePackManifest.parse`):
- `kind` must be `voice` (a language pack imported here is refused, and vice versa: the language
  importer requires `dictionary.bin`); `format` must be 1.
- `id` is the install directory and must match `LanguagePackSwap.VALID_PACK_CODE`.
- `files`: 1–32 entries, flat names (`[A-Za-z0-9._-]`, no leading dot), unique, lowercase hex
  sha256, positive size ≤ 128 MiB each; total ≤ 256 MiB. Every file must be present with the
  declared size and hash; undeclared members other than `manifest.json`, `LICENSE`,
  `NOTICE.txt` are dropped.
- `sampleRate` must be 16000 (the only capture rate). `minAppVersionCode` above the running
  version → "needs a newer CleverKeys".
- `license`/`attribution`/`source` are trimmed and capped at 2,000 chars; `NOTICE.txt` and
  `LICENSE` at 64 KiB.
- Archive: ≤ 48 entries, ≤ 300 MiB extracted, extraction streamed in 8 KiB chunks.

The engine checks its own files at load (Moonshine: the eight files below must be present and
`streaming_config.json` must parse); the integrity of every byte was established at import.

**Security note.** Unlike the CTC swipe encoders (`CtcPackModel`), the voice-pack model hashes
are NOT pinned in the APK: the engine is meant to be swappable by pack, and a future model must
not need an app release. The trust decision is the user's (they choose the file); the pack's own
sha256 list proves integrity, not origin. ORT parses the graphs in-process; a hostile `.ort` is
the same attack surface as any ONNX model the user loads. Recorded as an open question.

### Moonshine v2 tiny-streaming pack (`voicepack-moonshine-v2-tiny-en.zip`)
Files, from `moonshine-ai/moonshine-voice-assets` revision
`0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae`, `model/tiny-streaming-en/quantized_26_08_21/`:
`frontend.model.ort` (23,344 B) + `frontend.weights.ort` (2,093,464; its outputs are fed to the
frontend as extra inputs, as the official runtime does), `encoder.ort` (7,675,440),
`adapter.ort` (1,319,664), `cross_kv.ort` (1,287,544), `decoder_kv.ort` (32,583,720),
`tokenizer.bin` (249,974), `streaming_config.json` (509). Built by
`scripts/build_voice_pack.py` (hashes pinned in the script; output under `build/voicepacks/`).

Runtime (port of `core/moonshine-streaming-model.cpp`, MIT):
frontend (audio chunk + 5 carried states → 50 Hz features) → encoder over a sliding window of
`16·depth` past frames, keeping frames older than `total_lookahead` (16) as stable →
adapter (`pos_offset`) → memory → `cross_kv` → `decoder_kv` greedy decode with the previous
pass's tokens re-verified in one call (speculative prefix), `max_tokens = ceil(sec · 6.5)`.
The frontend graph rejects a chunk shorter than one 5 ms hop (80 samples). Only the final
pass can be that short (a stop flushes a partial segmenter hop), so `runFrontend` pads such a
chunk with silence (2026-11-05).
Stability: words two consecutive passes agree on are stable (`HypothesisStabilizer`, engine-
neutral); the final pass (stop) makes everything final.

### Moonshine v2 small-streaming pack (optional, `voicepack-moonshine-v2-small-en.zip`, 2026-11-05)
Same revision and runtime as tiny, `model/small-streaming-en/quantized_26_08_21/`, built with
`scripts/build_voice_pack.py --variant small`: `frontend.model.ort` (26,944 B),
`frontend.weights.ort` (7,769,464), `encoder.ort` (44,148,576), `adapter.ort` (2,870,368),
`cross_kv.ort` (5,356,536), `decoder_kv.ort` (81,878,600 = 78.1 MiB, the largest file),
`tokenizer.bin` (249,974, identical to tiny's), `streaming_config.json` (512: depth 10, encoder
620, decoder 512). 142,300,974 B of files (135.7 MiB, inside the 128 MiB per-file and 256 MiB
total caps); the ZIP is 142,193,602 B, sha256 `f75cd82a9b23f9f1545fdf651bdbe4bfd098524bdb869259d18bdbc9e8b29d74`
(reproducible; every file hash equals the Hub's LFS oid). `decoder_kv_with_attention.ort` is not
packed (alignment output, unused). No app code is specific to it: the engine sizes itself
from `streaming_config.json`. On the `langpacks` release since 2026-11-05 (with its `.sha256`;
the downloaded asset was re-hashed), marked experimental, tiny still the recommended default.
- JVM, app engine on ORT 1.21.1 (`MoonshineV2StreamingEngineTest -PvoicePack=<small zip>`): 8/8;
  WER 2/125 on the eight 5 s clips (tiny: 5/125), 7/228 on the 88 s long dictation through the
  segmenter, 0/57 on 24 s; load 686 ms. Pass times on this run are not usable (load ≈ 17).
- emulator.wtf Pixel 7 API 34 (x86_64 ORT), `VoiceInputInstrumentedTest
  -e voicePack voicepack-moonshine-v2-small-en.zip`: 4/4, 1/31 errors on the 11.1 s sample
  (tiny 1/31). Compute 11,244 ms for 11.1 s of audio fed at once (tiny 3,769 ms); load 7,973 ms
  (tiny 1,673). An emulator is not a phone; the phone figures are still owed.
- Device (Saga, 2026-11-05, below "Device test 2026-11-05"): 387 ms median pass as shipped (tiny
  168), 3 of 112 passes over 1 s; through a real microphone 9–10 errors in 228 words (tiny 18–19).
- With tiny and small both installed, `activePack` takes the first by NAME, i.e. small
  ("English — Moonshine v2 small…" sorts before "…tiny"). There is no choice in Settings; the
  user removes one to switch (Open Questions 4; roadmap: plan doc §3.2).

### Data Structures
```kotlin
data class SpeechHypothesis(val text: String, val stableWordCount: Int, val isFinal: Boolean)
enum class VoiceState { IDLE, PREPARING, LISTENING, FINISHING, ERROR }
sealed interface VoiceTextOp { SetComposing, Commit, FinishComposing, ClearComposing }
enum class VoiceFieldKind { TEXT, BLIND, INLINE, PASSWORD }
```

### API/Interface Design
```kotlin
interface SpeechEngineFactory { val engineId: String; val versions: IntRange
    fun load(packDir: File, options: SpeechEngineOptions): SpeechEngine }
interface SpeechEngine : Closeable { val sampleRate: Int; fun newSession(): SpeechSession
    fun memoryReport(): SpeechMemoryReport; val maxSegmentSeconds: Int /* default 30 */ }
class SegmentedRecognizer(engine: SpeechEngine) { fun accept(pcm: FloatArray): List<Update>
    fun finish(): SpeechHypothesis; fun cancel() }   // Update = Partial | SegmentFinal
interface SpeechSession : Closeable { fun accept(pcm: FloatArray): SpeechHypothesis?
    fun finish(): SpeechHypothesis; fun cancel() }
```

### State Management
`VoiceSessionMachine`: IDLE → PREPARING (pack load, off main thread) → LISTENING → (partials,
segment finals) → FINISHING (stop: last audio flushed, final pass) → IDLE. A `SegmentFinal`
applies in LISTENING and FINISHING (one decoded just before stop arrives after it, ahead of the
period's `Final`). Cancel from any state → IDLE with the
composing text cleared; FieldChanged from any state → IDLE with no text operation (FR-8). Any
engine/capture error → ERROR → IDLE after the bar message. Only the
`voice_input_enabled` preference persists; the active pack is the installed pack whose languages
contain the current language, else the first installed one.

## Implementation Plan

### Phase 1 — core (done 2026-10-29)
- [x] `stt/` interfaces, registry, manifest, stabilizer, session machine, text policy, permission gate
- [x] Voice pack import over `LanguagePackSwap` + shared bounded extraction
- [x] Moonshine engine on ORT 1.21.1; real-model JVM test gated on local files
- [x] `scripts/build_voice_pack.py`

### Phase 2 — IME (done 2026-10-29, device-unverified)
- [x] `voice_input` key/command/extra key, receiver routing, controller, AudioRecord capture
- [x] Transparent permission activity, Settings → Voice input, bar voice mode, TalkBack
- [x] Strings in 21 locales, wiki page, CHANGELOG (2.0.0; first drafted under Unreleased 2.1), NOTICE

### Phase 2b — voice keys (done 2026-10-30)
- [x] `voice_typing` / `voice_typing_chooser` route to the built-in engine (FR-4a)
- [x] Word swipes from a letter with the voice key in its corner stay words (FR-4b)

### Phase 3 — outstanding
- [ ] Device test (Saga/Pixel): permission flow, latency, RSS, composing behaviour in Chrome,
      Termux (append-only), RustDesk, inline fields, TalkBack
  Done 2026-10-29 on the second Saga (Android 13, release of this branch; the other Saga was off
  the network, the Pixel and RustDesk were not tested; TalkBack not driven): see below.
- [x] `voicepack-moonshine-v2-tiny-en.zip` + `.sha256` (sha256 `33ec8c87…`) are on the `langpacks`
      GitHub release since 2026-10-29, marked experimental; the release notes say "Requires
      CleverKeys 2.0 or later"
- [x] Voice activity detection / endpointing (FR-10, 2026-10-31): segments at pauses, a forced
      boundary at the engine's `maxSegmentSeconds`, 5-minute / 10-second-idle period limits.
      Device check outstanding (a real microphone's noise floor; speech-to-pause timing of a
      person rather than LibriSpeech)

### Device test 2026-10-29 (Saga, real microphone)
LibriSpeech test-clean clips played through the phone's own speaker from a Chrome tab while the
keyboard listened in another field (no audio file on the phone; acoustic path speaker → mic).
- Permission: `RECORD_AUDIO` declared, `granted=false` after install and after opening Settings;
  the prompt appears only on the switch (Settings' own request) or a key press (the transparent
  activity); deny keeps the switch off with a toast; "don't ask again" → the bar explains and a
  second press opens the app's system page. Import: 45 MB pack in ~3 s; a one-byte-changed
  `encoder.ort` is refused (checksum), an unknown engine id is refused ("needs a newer version").
- Tap → microphone open 0.25–0.33 s (engine load 112 ms); partials every ~1.0 s, the first
  ~0.2–1.4 s after speech starts; final ~1 s after stop. WER through the speaker: 0/12 (one
  clip), 1/57 and 14/68 (two 4-clip, ~24 s sessions) = 15/125 (12 %) vs 4.0 % on the clean JVM
  path — the loss is acoustic. Keyboard process RSS 350 MB idle with the model → 394 MB (7 s),
  484/524 MB (24 s); CPU ≈ 0.45–0.6 of one core while listening. The mic indicator turns off on
  stop, cancel, keyboard hide, field change and auto-stop (AudioService `rec stop` events).
- Fields: Chrome textarea (composing partials, committed finals); Termux (append-only, no
  composing, one clean line); CleverKeys clipboard search (inline; the app field untouched);
  Settings search. Password field: refused, no microphone.
- Bugs found and fixed on this branch: (1) a recognition pass on the capture thread overran the
  recorder's 4 s ring after ~35 s ("RecordThread: buffer overflow" ×5, sentences lost) →
  `BufferedPcmSource` + the engine decodes a backlog once; (2) beyond ~40 s Moonshine tiny loops
  ("some use of some use of…") → `SpeechEngine.maxUtteranceSeconds`, 30 s for Moonshine (34.7 s:
  2/89 errors; 46 s: 151/125) — replaced on 2026-10-31 by endpointing (FR-10,
  `maxSegmentSeconds`); (3) a long partial pushed the bar's ✕ off screen and long bar
  messages were cut off ("tap again" unreadable) → measured at the viewport, two lines;
  (4) the password refusal was silent (bar messages are ignored in password mode; a toast is
  dropped when notifications are off) → a notice in the password bar; (5) a pass that inserted
  a word before the committed boundary repeated the last committed word ("after all. all.") →
  `HypothesisStabilizer` aligns a pass's tail on the last committed words.
- Field change while words are underlined: Chrome keeps the composing words as typed text (it
  finishes the composition on blur, as for typed composing text). Until 2026-10-29 the keyboard
  then "cancelled" into the NEW field: `setComposingText("")` on the new connection deleted a
  focused input's selected value ("KEEP-ME-SELECTED" → empty, Saga, `deleteContentBackward`).
  Fixed by the FieldChanged/Release path (`VoiceSessionMachineTest`); re-checked on the Saga.

### Device test 2026-11-05 (Sagas + Pixel; tiny vs small; ORT settings)
Research §6.7 has the tables. Release build of `ee203b73` on the Saga `192.168.0.144` (Android 13):
- Import of both packs through Settings (small, 142 MB: ~10 s); the microphone prompt after the
  first import; small becomes active when both are installed (first by name); Settings → Remove
  switched back to tiny and the keyboard reloaded the engine by itself (no restart).
- Real microphone, the 10 long-set clips through the phone's speaker into Chrome (228 words), two
  runs each: tiny 19 and 18 errors, small 10 and 9; text shown a median 0.75 s (tiny) / 0.84 s
  (small) after each clip, max 1.58 s; keyboard RSS while listening 324–522 MB (tiny),
  459–698 MB (small); model load 117 / 286 ms.
- Engine benchmark (`VoiceEngineDeviceBenchmark`, debug + test APK): per-pass median tiny 168 ms,
  small 387 ms as shipped (CPU EP, arena off); arena on or XNNPACK 1.7–1.8× faster for both.
- Android 14 Saga (`192.168.1.130`), same flow at 25/25 speaker volume: tiny 45 and 41 errors,
  small 23 and 22; lag median ~1.0–1.2 s; benchmark tiny 203 ms, small 369 ms per pass as shipped,
  ~2× faster with XNNPACK or the arena on. Its first runs gave no text only because
  `cmd media_session volume --set` does not change the volume on that phone (Volume Up keys while
  media plays do); not a product finding. Play Protect blocks the debug APK there unless
  ADB-install verification is off (turned off for the run, restored). The Pixel was not used
  (busy). Every phone was restored (packs removed, voice off, microphone revoked, volume, tabs,
  debug APKs uninstalled, verifier setting deleted); the release app was updated to `ee203b73` on
  the two Sagas and the Pixel.
- Found: Settings → Remove deletes a voice pack without a confirmation.

### Engine setting, long-period check (2026-11-05 evening, research §6.7.1)
- Both Sagas, `VoiceEngineDeviceBenchmark` with 6 rounds (12 min of audio), 20 reloads and
  14–19 s utterances: the CPU EP with the arena ON is as fast as XNNPACK (small 218–303 vs
  360–462 ms per pass with it off; 0–2 vs 4–9 passes over 1 s), uses less CPU per second of audio,
  stays flat over the rounds and leaves 6–11 MB of native heap after close. XNNPACK leaves 274 MB
  (tiny) / 540 MB (small) after close for the life of the process (ORT 1.21's process-wide XNNPACK
  arena). Errors identical in every setting. The engine now defaults to the arena on.
- In the keyboard (release build, Saga `.144`, small, speaker 18/25): the log reads
  `CPU EP, 2 threads, arena on`; 14 and 10 errors in 228 words (10 and 9 with the arena off on
  11-05; the benchmark text is identical for both settings, so this is speaker → microphone
  variance); lag median 0.79 / 0.83 s, max 1.37 / 1.24 s; keyboard RSS while listening 617–739 /
  689–840 MB (459–698 MB with the arena off); 60 s after the session ended the engine was released
  and the keyboard fell to 480 / 510 MB (529 MB after the arena-off runs). Tiny was not re-run
  through the microphone (same code path; benchmark only). Restored afterwards.
- Saga `.1.130`, now in a sound box like `.144`'s (release build, arena on, speaker 18/25): tiny 28
  and 28 errors in 228 words, small 12 and 15 (45/41 and 23/22 before the box); lag median
  0.77–0.95 s. Keyboard memory: PSS 140–152 MB / RSS 250–263 MB with voice unused; dictating PSS
  330–460 (tiny) / 553–731 MB (small); after the idle release PSS 237–261 MB — the extra ~90 MB is
  freed native memory the allocator keeps (live native bytes back at baseline; research §6.7.1).
  Restored afterwards (packs removed, voice off, microphone revoked, volume 9).

## Testing Strategy

### Unit Tests (pure, `runPureTests`)
- `VoicePackManifestTest`: valid manifest; every refusal (kind, format, id, files, hashes,
  sizes, sample rate, newer app, engine missing).
- `SpeechEngineRegistryTest`: known id/version, unknown id, version out of range, duplicates.
- `VoicePackManagerTest`: import happy path; member/total caps; sha256 and size mismatch;
  missing declared file; traversal; replace keeps old pack on refused rename; rollback; recovery;
  removal.
- `HypothesisStabilizerTest`, `VoiceSessionMachineTest`, `VoiceTextPolicyTest`,
  `MicPermissionGateTest`.
- `SpeechSegmenterTest` (synthetic audio: silence, quiet and loud noise, clicks, syllable-modulated
  tones; pre-roll and hangover lengths, split/no-split pauses, the learned noise floor, forced
  boundaries at the bound and at the quietest point, flush, chunking invariance),
  `SegmentedRecognizerTest` (a fresh session per segment, finals in order, silence never decoded,
  one accept per backlog, cancel), `BufferedPcmSourceTest#theBacklogIsBoundedSeparatelyFromTheSessionLength`.
- `SttArchitectureDriftTest`, `VoicePackAssetDriftTest` (no model in APK assets).
- `MoonshineV2StreamingEngineTest`: real model on a LibriSpeech WAV when
  `~/.cache/cleverkeys-test/voicepack/` (or `-PvoicePackDir`) holds the pack; skipped otherwise.

Measured 2026-10-29 (pure JVM, the APK's ORT 1.21.1 bionic natives, box load ≈ 12–16): the
Kotlin engine, fed through an imported pack in 320 ms pieces, transcribed 8 LibriSpeech
test-clean clips (4.5–5.9 s, 125 words) at **5/125 = 4.0 % WER** with 4–5 partial passes each,
1.0–2.5 s compute per clip, engine load 225 ms. Pack: 45,126,272 B, sha256
`33ec8c87105f7adf40e6f284e21f3943b6497ba258dfeb6fc171855f49f61e19` (reproducible build).

Endpointing, measured 2026-10-31 (same setup, box load ≈ 14; `MoonshineV2StreamingEngineTest`,
ten further test-clean clips in `~/.cache/cleverkeys-test/voice-samples-long/` or
`-PvoiceLongSamples`, streamed in 320 ms pieces through `SegmentedRecognizer` and committed by
`VoiceTextPlanner` into a model editor):
- **Pauses** — 10 clips (79 s of speech) joined by 0.9/1.2 s of −65 dBFS noise, 88.3 s: 10
  segments, one per clip, all ended by the pause; WER **8/228 = 3.5 %** vs **9/228 = 3.9 %** for
  the same clips transcribed one by one; no repeated 4-gram; slowest pass per segment 403–1,118 ms
  (first three segments 501 ms on average, last three 600 ms) with no growth over the period;
  segment final 100–492 ms; 31 s of compute for 88 s of audio.
- **No pause** — 6 clips joined by 0.2 s gaps (below the hangover), 53.3 s: two boundaries
  forced at the 20 s bound (19.7 s and 19.3 s segments), WER **3/153 = 2.0 %** vs 7/153 per clip,
  no loop. Control, the same audio in ONE session (the pre-endpointing path without its 30 s
  stop): **169 errors for 153 words**, a loop ("it is you who are mistaken" ×14) and 260 s of
  compute.
- 23.6 s in one session (the bound test, now against `maxSegmentSeconds` = 20): 0/57.
- Saga (2026-10-31, release of `b243ce3d`, real microphone, the same 10 clips + 1 s gaps + 13 s
  of silence played through the phone's speaker into a Chrome textarea): 11 segments committed
  in order (one extra split at a pause inside the first clip), each 0.2–1.5 s after its clip
  ended; no loop; WER 45/228 = 19.7 % (acoustic path; the JVM path is 3.5 %); keyboard RSS
  345 MB idle, 346–461 MB while listening, back to 345 MB, flat over 104 s; the microphone
  closed by itself ~10 s after the last speech.
- Fail-first: `SegmentedRecognizerTest#theEngineBoundForcesANewSessionWithoutAPause` caught a
  segment 256 samples over a 10 s bound (not a whole number of 32 ms hops); the cut now happens
  before the next hop would cross the bound.

### Integration / UI
- `TranslationFormatParityTest`, `TranslationGlossaryTest`, `SettingsSearchCoverageTest`,
  `ExtraKeysPreference` catalog tests, `CommandRegistry` count tests.
- Device: see Phase 3.

## Dependencies
- Internal: `LanguagePackSwap`, `PackArchiveExtractor`, `TerminalUtils.editorKind`,
  `InlineTextTarget`/`InlineWordPlanner`, `SuggestionBar`, `CommandRegistry`, `ExtraKeysPreference`.
- External: `com.microsoft.onnxruntime:onnxruntime-android:1.21.1` (already shipped).
- Breaking changes: none. New permission `RECORD_AUDIO` (declared; requested only on opt-in).

## Security Considerations
- Untrusted ZIP: caps enforced while extracting, traversal and duplicate names refused, the
  manifest id is a validated path component, staging is invisible until complete.
- Microphone: recording only between a user tap and stop/cancel/hide; Android shows its
  microphone indicator; no audio persisted.
- Model trust: see the security note above (no APK pin, by design).

## Error Handling
- No pack → bar message "Import a voice pack in Settings → Voice input" and the settings
  screen opens on a second tap.
- Permission missing → the transparent activity asks once per tap; denied → toast; refused
  without a prompt ("don't ask again") → the "Microphone access is blocked" dialog with "Open app
  settings" (FR-7).
- Pack refused at import → localized `VoicePackImportFailure`.
- Engine load/inference error → session ends, bar message, the composing tail is cleared.
- `AudioRecord` init failure (mic busy) → bar message, nothing recorded.

## Documentation Updates
- [x] This spec; wiki page `docs/wiki/voice-input.md`
- [x] CHANGELOG 2.0.0 (moved from Unreleased 2.1.0 on 2026-10-29), fastlane 2.0.0 note,
      `docs/RELEASE_RECORD.md` v2.0.0 rows
- [x] NOTICE (Moonshine, MIT — only for the pack; nothing of Moonshine ships in the APK)

## Open Questions
1. Pin pack model hashes in the APK (like `CtcPackModel`) at the cost of an app release per model?
2. Default chunk size 1.0 s vs 0.5 s (0.5 s doubles retractions, research §5.2).
3. Languages: de/es/ja/zh/ar/vi/tl streaming tiny models exist upstream; one pack each.
4. Two packs for one language (tiny + the optional small): today the first by name is used and
   the user removes one to switch (2026-11-05). Roadmap: a per-language pack choice for voice AND
   dictionary packs (`docs/plans/2026-10-05-recommended-features-and-gaps.md` §3.2, target 2.1).
5. ~~Engine setting~~ DECIDED 2026-11-05 (research §6.7.1): the CPU EP with the arena ON (1.6–2×
   faster per pass, less CPU, flat RSS over 12 min, freed on release). XNNPACK is equally fast
   but ORT 1.21's XNNPACK allocator is a process-wide arena that keeps 274–540 MB after close.

## Future Enhancements
- A model VAD (Silero, as the official runtime) carried by the pack, if the energy VAD proves
  weak in noisy rooms on devices; keyword biasing from the user dictionary; punctuation-free mode;
  a level meter.

---

**Created**: 2026-10-29
**Last Updated**: 2026-11-05
**Owner**: CleverKeys maintainers
