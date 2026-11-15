# Voice Input Engine Skill

Use this skill for the on-device speech engine (Moonshine under ONNX Runtime) and the STT
research harness: ORT execution provider and arena settings, memory checks, and keeping the
Python harness faithful to the app. Feature spec: `docs/specs/voice-input.md`; research
`docs/research/2026-10-28-stt-engine-deep-dive.md`; IME-side routing of dictated text:
`ime-key-routing.md`.

<!-- moved verbatim from CLAUDE.md on 2026-11-14 -->
## STT research harness vs the app (2026-11-05)

`scripts/research/stt/moonshine_stream_ort.py` must mirror the app's guards, or it measures failures the
app never has. Use `--tokens-per-s 6.5` (the app's per-pass decode cap; without it one loop cost
20 s) and `--max-segment 20` for long clips. Moonshine's frontend rejects chunks < 80 samples;
both sides pad them. ORT's CPU EP with the arena off ran the official decoder 2–2.5× slower;
compare latencies only within one series, with the EP and arena recorded.
The app now runs the arena ON; never XNNPACK for the voice engine: ORT 1.21's XNNPACK allocator
is ONE process-wide BFC arena (`GetStoredAllocator`), deaf to `setCPUArenaAllocator(false)` and
never freed, so closing the engine left 274–540 MB in the process. Check leaks with
`Debug.getNativeHeapAllocatedSize()` after close, not RSS (RSS swings ±50 MB with freed pages).
