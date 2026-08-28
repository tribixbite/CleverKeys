> **ARCHIVED 2026-08-18 — describes the REMOVED neural swipe engine.**
>
> The ONNX transformer swipe decoder, its vocabulary stack, its settings screen and its
> ~10 MB of bundled models were deleted on 2026-08-18. CTC (`swipe/ctc/`) replaced it —
> 89.31 vs 74.62 top-1 on test-2400, measured in `docs/history/audits/2026-08-17-neural-vs-ctc-parity.md`
> — with the geometric engine (`swipe/geometric/`) covering every (layout, language) pair
> CTC does not serve. Nothing below describes code that still exists. Kept as the record of
> what was built and what the removal gave up; see
> `docs/plans/2026-08-18-neural-engine-removal.md` and ADR-011.

---
title: Neural Prediction Settings - Technical Specification
user_guide: ../../settings/neural-settings.md
status: implemented
version: v1.2.9
---

# Neural Prediction Settings Technical Specification

## Source Location Reference

All facts in the [Neural Settings wiki page](../../settings/neural-settings.md) are sourced from:

| Fact | Source File | Line(s) | Value |
|------|------------|---------|-------|
| Beam width default | `Config.kt` | 130 | `NEURAL_BEAM_WIDTH = 6` |
| Beam width range 1-32 | `backup/SettingsValidation.kt` | - | `"neural_beam_width" -> value in 1..32` |
| Max sequence length default | `Config.kt` | 115 | `NEURAL_MAX_LENGTH = 20` |
| Confidence threshold default | `Config.kt` | 116 | `NEURAL_CONFIDENCE_THRESHOLD = 0.01f` |
| ONNX threads default | `Config.kt` | 247 | `ONNX_XNNPACK_THREADS = 2` |
| ONNX threads range 1-8 | `onnx/ModelLoader.kt` | `tryXnnpack()` | `threadCount.coerceIn(1, 8)` |
| Swipe typing default on | `Config.kt` | 90 | `SWIPE_TYPING_ENABLED = true` |
| Swipe on password default off | `Config.kt` | 91 | `SWIPE_ON_PASSWORD_FIELDS = false` |
| Prediction Engine default | `Config.kt` | 320 | `SWIPE_ENGINE_MODE = "neural"` |
| CTC beam width default | `Config.kt` | 330 | `CTC_BEAM_WIDTH = 100` |
| CTC beam width range 10-300 | `CtcSettingsActivity.kt` | slider + `loadSavedParameters()` | `valueRange = 10f..300f`, `.coerceIn(10, 300)` |
| ONNX model format | `onnx/ModelLoader.kt` | class | ONNX Runtime Android |
| Beam search decoder | `neural/BeamSearchDecoder.kt` | class | Vocabulary-constrained beam search |

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Neural Defaults | `Config.kt:Defaults` (line 114-135) | All default values |
| Settings UI | `ui/settings/sections/NeuralPredictionSection.kt` | Swipe Typing section (engine dropdown, toggles, engine-settings buttons) |
| Model Loader | `onnx/ModelLoader.kt` | ONNX model initialization and XNNPACK config |
| Beam Decoder | `neural/BeamSearchDecoder.kt` | Word prediction from swipe trajectories |
| Prediction Engine | `neural/NeuralPredictionEngine.kt` | Coordinates model inference and ranking |
| Engine Router | `swipe/SwipeEngineRouter.kt` | Routes swipes per `swipe_engine_mode` (neural/hybrid/geometric/ctc) |
| CTC Settings UI | `CtcSettingsActivity.kt` | Full CTC Settings screen (single beam-width knob) |

## Settings Preference Keys

| Preference Key | Type | Default | Config.kt field |
|---------------|------|---------|-----------------|
| `swipe_typing_enabled` | Boolean | true | `swipe_typing_enabled` |
| `swipe_on_password_fields` | Boolean | false | `swipe_on_password_fields` |
| `swipe_engine_mode` | String | "neural" | `swipe_engine_mode` (values `neural`/`hybrid`/`geometric`/`ctc`, lowercased at read) |
| `ctc_beam_width` | Int | 100 | `ctc_beam_width` (10-300) |
| `neural_beam_width` | Int | 6 | `neural_beam_width` |
| `neural_confidence_threshold` | Float | 0.01 | `neural_confidence_threshold` |
| `neural_max_length` | Int | 20 | `neural_max_length` |
| `onnx_xnnpack_threads` | Int | 2 | `onnx_xnnpack_threads` |
| `neural_frequency_weight` | Float | 0.57 | `neural_frequency_weight` |
| `neural_batch_beams` | Boolean | false | `neural_batch_beams` |

## Related Specifications

- [Swipe Typing Spec](../typing/swipe-typing-spec.md)
- [Setup Spec](../getting-started/setup-spec.md)
