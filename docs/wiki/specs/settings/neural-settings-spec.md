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
| Beam width default | `Config.kt` | 114 | `NEURAL_BEAM_WIDTH = 6` |
| Beam width range 1-20 | `SettingsActivity.kt` | 1400 | `valueRange = 1f..20f` |
| Max sequence length default | `Config.kt` | 115 | `NEURAL_MAX_LENGTH = 20` |
| Confidence threshold default | `Config.kt` | 116 | `NEURAL_CONFIDENCE_THRESHOLD = 0.01f` |
| ONNX threads default | `Config.kt` | 247 | `ONNX_XNNPACK_THREADS = 2` |
| ONNX threads range 1-8 | `onnx/ModelLoader.kt` | `tryXnnpack()` | `threadCount.coerceIn(1, 8)` |
| Swipe typing default on | `Config.kt` | 90 | `SWIPE_TYPING_ENABLED = true` |
| Swipe on password default off | `Config.kt` | 91 | `SWIPE_ON_PASSWORD_FIELDS = false` |
| ONNX model format | `onnx/ModelLoader.kt` | class | ONNX Runtime Android |
| Beam search decoder | `neural/BeamSearchDecoder.kt` | class | Vocabulary-constrained beam search |

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Neural Defaults | `Config.kt:Defaults` (line 114-135) | All default values |
| Settings UI | `SettingsActivity.kt` (line 1370-1450) | Neural Prediction section |
| Model Loader | `onnx/ModelLoader.kt` | ONNX model initialization and XNNPACK config |
| Beam Decoder | `neural/BeamSearchDecoder.kt` | Word prediction from swipe trajectories |
| Prediction Engine | `neural/NeuralPredictionEngine.kt` | Coordinates model inference and ranking |

## Settings Preference Keys

| Preference Key | Type | Default | Config.kt field |
|---------------|------|---------|-----------------|
| `swipe_typing` | Boolean | true | `swipe_typing_enabled` |
| `swipe_on_password` | Boolean | false | `swipe_on_password_fields` |
| `neural_beam_width` | Int | 6 | `neural_beam_width` |
| `neural_confidence_threshold` | Float | 0.01 | `neural_confidence_threshold` |
| `neural_max_length` | Int | 20 | `neural_max_length` |
| `onnx_xnnpack_threads` | Int | 2 | `onnx_xnnpack_threads` |
| `neural_frequency_weight` | Float | 0.57 | `neural_frequency_weight` |
| `neural_batch_beams` | Boolean | false | `neural_batch_beams` |

## Related Specifications

- [Swipe Typing Spec](../typing/swipe-typing-spec.md)
- [Setup Spec](../getting-started/setup-spec.md)
