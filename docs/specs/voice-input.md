# Voice input (on-device speech to text)

## Feature Overview
**Feature Name**: Voice input — offline dictation inside the keyboard
**Priority**: P1
**Status**: In Progress (engine, pack format, import, IME wiring and settings implemented
2026-10-29; device test and the `langpacks` upload outstanding)
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
5. **FR-5** Field policy: ordinary editors get composing partials + committed finals with
   auto-space and sentence capitalisation; blind REMOTE and TERMINAL editors receive **append-only
   final text** (no composing span, no rewrite); the keyboard's own inline fields (clipboard
   search/editor/tag, emoji and GIF search) receive final text through the inline-field path;
   password fields refuse dictation.
6. **FR-6** Settings → Voice input (off by default): enable switch, import/remove pack, pack
   name, size, engine, languages and licence/NOTICE. Turning it on or importing a pack is the
   ONLY moment the microphone permission is requested, via a transparent activity.
7. **FR-7** Denied permission → voice stays off and the section/bar explains why; "don't ask
   again" → a button opens the app's system settings page.
8. **FR-8** Capture only while listening; stop on keyboard hide, field change, editor switch,
   `onDestroy`, cancel; cancel (✕, keyboard hide) discards uncommitted (composing) text. A field
   change ends the period WITHOUT any text operation (`VoiceEvent.FieldChanged` →
   `VoiceAction.Release`): the old field already finished its own composition on blur (Chrome
   and EditText keep the underlined words; the keyboard cannot reach that field any more), and
   the connection now belongs to the new field, where a clear would replace its selection.
9. **FR-9** TalkBack: the key's content description carries the state; start/stop/cancel and
   errors are announced; partials are not announced.

### Non-Functional Requirements
1. **NFR-1** Performance: ≤ 1 s chunks (1.0 s chunks proved the floor for two-pass agreement),
   decoding off the main thread, ORT arena disabled for the streaming sessions, intra-op threads
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
Stability: words two consecutive passes agree on are stable (`HypothesisStabilizer`, engine-
neutral); the final pass (stop) makes everything final.

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
    fun memoryReport(): SpeechMemoryReport }
interface SpeechSession : Closeable { fun accept(pcm: FloatArray): SpeechHypothesis?
    fun finish(): SpeechHypothesis; fun cancel() }
```

### State Management
`VoiceSessionMachine`: IDLE → PREPARING (pack load, off main thread) → LISTENING → (partials) →
FINISHING (stop: last audio flushed, final pass) → IDLE. Cancel from any state → IDLE with the
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

### Phase 3 — outstanding
- [ ] Device test (Saga/Pixel): permission flow, latency, RSS, composing behaviour in Chrome,
      Termux (append-only), RustDesk, inline fields, TalkBack
  Done 2026-10-29 on the second Saga (Android 13, release of this branch; the other Saga was off
  the network, the Pixel and RustDesk were not tested; TalkBack not driven): see below.
- [ ] Upload `voicepack-moonshine-v2-tiny-en.zip` + `.sha256` to the `langpacks` release
- [ ] Voice activity detection / endpointing: end a segment at a pause and keep listening, so a
      period is not capped at the engine's `maxUtteranceSeconds` (30 s for Moonshine tiny)

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
  2/89 errors; 46 s: 151/125); (3) a long partial pushed the bar's ✕ off screen and long bar
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
- `SttArchitectureDriftTest`, `VoicePackAssetDriftTest` (no model in APK assets).
- `MoonshineV2StreamingEngineTest`: real model on a LibriSpeech WAV when
  `~/.cache/cleverkeys-test/voicepack/` (or `-PvoicePackDir`) holds the pack; skipped otherwise.

Measured 2026-10-29 (pure JVM, the APK's ORT 1.21.1 bionic natives, box load ≈ 12–16): the
Kotlin engine, fed through an imported pack in 320 ms pieces, transcribed 8 LibriSpeech
test-clean clips (4.5–5.9 s, 125 words) at **5/125 = 4.0 % WER** with 4–5 partial passes each,
1.0–2.5 s compute per clip, engine load 225 ms. Pack: 45,126,272 B, sha256
`33ec8c87105f7adf40e6f284e21f3943b6497ba258dfeb6fc171855f49f61e19` (reproducible build).

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
- Permission missing → the transparent activity asks once per tap; denied → bar message.
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

## Future Enhancements
- VAD/endpointing; keyword biasing from the user dictionary; punctuation-free mode;
  a level meter.

---

**Created**: 2026-10-29
**Last Updated**: 2026-10-29
**Owner**: CleverKeys maintainers
