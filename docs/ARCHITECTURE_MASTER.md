# CleverKeys Master Architecture Document

**Version**: 1.0.0
**Last Updated**: 2025-12-04
**Status**: Complete

This document contains all parameters, weights, coefficients, thresholds, and configuration values used in CleverKeys.

---

## Table of Contents

1. [Neural Prediction Parameters](#1-neural-prediction-parameters)
2. [Swipe Detection Parameters](#2-swipe-detection-parameters)
3. [Gesture Recognition Parameters](#3-gesture-recognition-parameters)
4. [ONNX Model Configuration](#4-onnx-model-configuration)
5. [Beam Search Parameters](#5-beam-search-parameters)
6. [Vocabulary & Dictionary](#6-vocabulary--dictionary)
7. [UI Configuration](#7-ui-configuration)
8. [Performance Tuning](#8-performance-tuning)
9. [Data Flow Diagram](#9-data-flow-diagram)

---

## 1. Neural Prediction Parameters

### 1.1 Core Neural Settings (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `neural_prediction_enabled` | Boolean | false | - | Enable ONNX neural prediction |
| `neural_beam_width` | Int | 8 | 1-16 | Number of beams in beam search |
| `neural_max_length` | Int | 20 | 10-35 | Maximum word length |
| `neural_confidence_threshold` | Float | 0.1 | 0.0-1.0 | Minimum confidence to accept prediction |
| `neural_batch_beams` | Boolean | true | - | Batch beam inference (50-70% speedup) |
| `neural_greedy_search` | Boolean | false | - | Use greedy instead of beam search |

### 1.2 Beam Search Tuning (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `neural_beam_alpha` | Float | 0.6 | 0.0-2.0 | Length normalization alpha |
| `neural_beam_prune_confidence` | Float | 0.01 | 0.0-0.5 | Minimum beam confidence to continue |
| `neural_beam_score_gap` | Float | 5.0 | 0.0-20.0 | Max score gap for early stopping |

### 1.3 Neural Model Versioning (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `neural_model_version` | String | "v1" | Model version identifier |
| `neural_use_quantized` | Boolean | true | Use INT8 quantized models |
| `neural_user_max_seq_length` | Int | 150 | Trajectory sequence length |
| `neural_resampling_mode` | String | "linear" | Trajectory resampling: linear, spline |
| `neural_custom_encoder_path` | String | null | Custom encoder model path |
| `neural_custom_decoder_path` | String | null | Custom decoder model path |

### 1.5 Debug Settings (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `swipe_debug_detailed_logging` | Boolean | false | Enable detailed swipe logging |
| `swipe_debug_show_raw_output` | Boolean | false | Show raw neural output |
| `swipe_show_raw_beam_predictions` | Boolean | false | Show raw beam predictions |
| `swipe_show_debug_scores` | Boolean | false | Show debug scores in UI |

### 1.4 Token Mapping (BeamSearchEngine.kt)

```
PAD_IDX = 0   # Padding token
UNK_IDX = 1   # Unknown token
SOS_IDX = 2   # Start of sequence
EOS_IDX = 3   # End of sequence
a = 4, b = 5, c = 6, ..., z = 29
space = 30, apostrophe = 31, hyphen = 32
```

### 1.6 Word Prediction Settings (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `word_prediction_enabled` | Boolean | false | Enable word prediction |
| `context_aware_predictions_enabled` | Boolean | false | Dynamic N-gram learning |
| `personalized_learning_enabled` | Boolean | false | Personalized word frequency learning |
| `learning_aggression` | String | "BALANCED" | Learning level: CONSERVATIVE, BALANCED, AGGRESSIVE |
| `prediction_context_boost` | Float | 0.0 | Context boost multiplier (0.5-5.0) |
| `prediction_frequency_scale` | Float | 0.0 | Frequency scaling factor (100-5000) |

### 1.7 Multi-Language Settings (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `enable_multilang` | Boolean | false | Enable multi-language support |
| `primary_language` | String | "en" | Primary language code |
| `auto_detect_language` | Boolean | true | Auto-detect language from context |
| `language_detection_sensitivity` | Float | 0.6 | Detection sensitivity (0.0-1.0) |

---

## 2. Swipe Detection Parameters

### 2.1 Core Swipe Settings (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `swipe_typing_enabled` | Boolean | true | - | Enable swipe typing |
| `swipe_min_distance` | Float | 50.0 | 20-100 | Minimum swipe distance (px) |
| `swipe_min_key_distance` | Float | 40.0 | 15-80 | Minimum distance between keys (px) |
| `swipe_min_dwell_time` | Long | 10 | 0-50 | Minimum key dwell time (ms) |
| `swipe_noise_threshold` | Float | 2.0 | 0.5-10.0 | Movement noise filter (px) |
| `swipe_high_velocity_threshold` | Float | 1000.0 | 200-2000 | High velocity threshold (px/sec) |

### 2.2 Swipe Scoring Weights (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `swipe_confidence_weight` | Float | 0.8 | 0.0-1.0 | Neural confidence weight |
| `swipe_frequency_weight` | Float | 0.2 | 0.0-1.0 | Dictionary frequency weight |
| `swipe_common_words_boost` | Float | 1.0 | 0.5-2.0 | Boost for common words |
| `swipe_top5000_boost` | Float | 1.0 | 0.5-2.0 | Boost for top 5000 words |
| `swipe_rare_words_penalty` | Float | 1.0 | 0.5-2.0 | Penalty for rare words |

### 2.3 Swipe Auto-Correction (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `swipe_beam_autocorrect_enabled` | Boolean | true | Apply corrections during beam search |
| `swipe_final_autocorrect_enabled` | Boolean | true | Apply corrections to final output |
| `swipe_fuzzy_match_mode` | String | "edit_distance" | Algorithm: edit_distance, positional |
| `autocorrect_max_length_diff` | Int | 0 | Max length difference for correction |
| `autocorrect_prefix_length` | Int | 0 | Required matching prefix length |
| `autocorrect_max_beam_candidates` | Int | 0 | Max candidates to consider |

### 2.4 Auto-Correction General (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `autocorrect_enabled` | Boolean | false | - | Enable auto-correction |
| `autocorrect_min_word_length` | Int | 2 | 2-5 | Minimum word length |
| `autocorrect_char_match_threshold` | Float | 0.7 | 0.5-0.9 | Character match threshold |
| `autocorrect_confidence_min_frequency` | Int | 100 | 100-5000 | Minimum frequency threshold |

### 2.5 Slider Settings (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `slider_speed_smoothing` | Float | 0.7 | 0.1-0.95 | Smoothing factor for slider speed |
| `slider_speed_max` | Float | 4.0 | 1.0-10.0 | Maximum slider speed multiplier |

### 2.6 Swipe Trail Appearance (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `swipe_trail_enabled` | Boolean | true | Show swipe trail |
| `swipe_trail_effect` | String | "glow" | Effect: none, solid, glow, rainbow, fade |
| `swipe_trail_color` | Int | 0xFF9B59B6 | Trail color (jewel purple) |
| `swipe_trail_width` | Float | 8.0 | Trail stroke width (dp) |
| `swipe_trail_glow_radius` | Float | 6.0 | Glow radius (dp) |

---

## 3. Gesture Recognition Parameters

### 3.1 CGR Constants (ContinuousGestureRecognizer.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `DEFAULT_E_SIGMA` | 200.0 | Error sigma for Gaussian |
| `DEFAULT_BETA` | 400.0 | Beta parameter |
| `DEFAULT_LAMBDA` | 0.4 | Lambda interpolation weight |
| `DEFAULT_KAPPA` | 1.0 | Kappa parameter |

### 3.2 Gesture Timing (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `tap_duration_threshold` | Long | 150 | 50-500 | Max tap duration (ms) |
| `double_space_threshold` | Long | 500 | 0-1000 | Double-space to period time (ms) |
| `longPressTimeout` | Long | 600 | 200-1500 | Long press activation (ms) |
| `longPressInterval` | Long | 25 | 10-100 | Key repeat interval (ms) |

### 3.3 Loop Gesture Detection (LoopGestureDetector.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `MIN_LOOP_ANGLE` | 270.0° | Minimum angle for loop |
| `MAX_LOOP_ANGLE` | 450.0° | Maximum angle for loop |
| `MIN_LOOP_RADIUS` | 15.0px | Minimum loop radius |
| `MAX_LOOP_RADIUS_FACTOR` | 1.5 | Max radius multiplier |
| `MIN_LOOP_POINTS` | 8 | Minimum points for loop |
| `CLOSURE_THRESHOLD` | 30.0px | Distance for loop closure |

### 3.4 Rotation Detection (Gesture.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `ROTATION_THRESHOLD` | 2 | Rotation sensitivity |

---

## 4. ONNX Model Configuration

### 4.1 Model Files

| Model | File | Size | Description |
|-------|------|------|-------------|
| Encoder | `swipe_encoder_android.onnx` | ~4MB | Trajectory encoder (quantized INT8) |
| Decoder | `swipe_decoder_android.onnx` | ~4MB | Character decoder (quantized INT8) |

### 4.2 Encoder Input Tensors (SwipePredictorOrchestrator.kt)

| Tensor | Shape | Type | Description |
|--------|-------|------|-------------|
| `trajectory_features` | [1, 150, 6] | Float32 | (x, y, vx, vy, ax, ay) normalized |
| `nearest_keys` | [1, 150] | Int64 | Character indices (a=4, ..., z=29) |
| `src_mask` | [1, 150] | Float32 | Attention mask (0=valid, 1=pad) |

### 4.3 Feature Extraction (6 features)

| Feature | Index | Formula | Range |
|---------|-------|---------|-------|
| x | 0 | x / keyboard_width | [0, 1] |
| y | 1 | y / keyboard_height | [0, 1] |
| vx | 2 | (x[i] - x[i-1]) / dt | normalized |
| vy | 3 | (y[i] - y[i-1]) / dt | normalized |
| ax | 4 | (vx[i] - vx[i-1]) / dt | normalized |
| ay | 5 | (vy[i] - vy[i-1]) / dt | normalized |

### 4.4 Decoder Output

| Tensor | Shape | Type | Description |
|--------|-------|------|-------------|
| `logits` | [batch, seq_len, 35] | Float32 | Token probabilities |

---

## 5. Beam Search Parameters

### 5.1 Constants (BeamSearchEngine.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `BEAM_WIDTH` | 8 | Default beam width |
| `MAX_LENGTH` | 20 | Maximum output length |
| `PRUNE_STEP_THRESHOLD` | 2 | Steps before pruning starts |
| `ADAPTIVE_WIDTH_STEP` | 5 | Steps before width adaptation |
| `SCORE_GAP_STEP` | 3 | Steps before score gap check |

### 5.2 Greedy Search (GreedySearchEngine.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `PAD_IDX` | 0 | Padding token index |
| `SOS_IDX` | 2 | Start token index |
| `EOS_IDX` | 3 | End token index |

---

## 6. Vocabulary & Dictionary

### 6.1 Dictionary Loader (BinaryDictionaryLoader.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `MAGIC` | 0x54434944 | "DICT" magic number |
| `EXPECTED_VERSION` | 1 | Binary format version |
| `HEADER_SIZE` | 32 | Header size in bytes |

### 6.2 Dictionary Data Source (DictionaryDataSource.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `PREFIX_INDEX_MAX_LENGTH` | 3 | Max prefix index length |

### 6.3 Bigram Model (BigramModel.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `LAMBDA` | 0.95 | Interpolation weight for bigram |
| `MIN_PROB` | 0.0001 | Minimum probability for unseen words |

### 6.4 Language Detection (LanguageDetector.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `MIN_CONFIDENCE_THRESHOLD` | 0.6 | Minimum detection confidence |

---

## 7. UI Configuration

### 7.1 Keyboard Layout (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `keyboardHeightPercent` | Int | 35 (portrait) / 50 (landscape) | Keyboard height % |
| `characterSize` | Float | 1.18 | Character size multiplier |
| `labelBrightness` | Int | 100% → 255 | Label brightness (0-255) |
| `keyboardOpacity` | Int | 100% → 255 | Keyboard opacity (0-255) |
| `keyOpacity` | Int | 100% → 255 | Key opacity (0-255) |

### 7.2 Clipboard (ClipboardDatabase.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `HISTORY_TTL_MS` | 7 days | History retention time |
| `DATABASE_VERSION` | 1 | SQLite schema version |

### 7.3 Wide Screen Detection (Config.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `WIDE_DEVICE_THRESHOLD` | 600dp | Width threshold for wide layouts |

---

## 8. Performance Tuning

### 8.1 Async Handling (AsyncPredictionHandler.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `MSG_PREDICT` | 1 | Prediction message ID |
| `MSG_CANCEL_PENDING` | 2 | Cancel message ID |

### 8.2 Dictionary Manager (DictionaryManagerActivity.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `SEARCH_DEBOUNCE_MS` | 300 | Search debounce delay |

### 8.3 Model Version Manager (ModelVersionManager.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `MAX_CONSECUTIVE_FAILURES` | 3 | Max failures before fallback |

### 8.4 Swipe Gesture Recognizer (ImprovedSwipeGestureRecognizer.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `MAX_POINT_INTERVAL_MS` | 500 | Max time between points |

---

## 9. Data Flow Diagram

```
                              USER INPUT
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                       TOUCH EVENTS                               │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────┐    │
│  │ ACTION_DOWN │───▶│ SwipeDetector │───▶│ StartGesture    │    │
│  └─────────────┘    └──────────────┘    └─────────────────┘    │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────┐    │
│  │ ACTION_MOVE │───▶│ CollectPoints │───▶│ TouchedKeys     │    │
│  └─────────────┘    └──────────────┘    └─────────────────┘    │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────┐    │
│  │ ACTION_UP   │───▶│ FinalizeSwipe │───▶│ TriggerPredict  │    │
│  └─────────────┘    └──────────────┘    └─────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    FEATURE EXTRACTION                            │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ SwipeTrajectoryProcessor                                   │  │
│  │  ├── smoothTrajectory(windowSize=3)                       │  │
│  │  ├── normalizeCoordinates(x/width, y/height)              │  │
│  │  ├── calculateVelocities(Δpos/Δtime)                      │  │
│  │  ├── calculateAccelerations(Δvel/Δtime)                   │  │
│  │  ├── detectNearestKeys(QWERTY grid)                       │  │
│  │  └── padOrTruncate(target=150)                            │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                   │
│  Output: [1, 150, 6] trajectory + [1, 150] keys + [1, 150] mask │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ONNX ENCODER                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ swipe_encoder_android.onnx (4MB INT8 quantized)           │  │
│  │  ├── 6 Transformer encoder layers                         │  │
│  │  ├── 8-head self-attention                                │  │
│  │  └── Output: memory [1, 150, 256]                         │  │
│  └───────────────────────────────────────────────────────────┘  │
│  Latency: < 30ms on mid-range devices                           │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    BEAM SEARCH DECODER                           │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ BeamSearchEngine (width=8, max_length=20)                 │  │
│  │  ├── Initialize: beams = [SOS]                            │  │
│  │  ├── Loop: batched decoder inference                      │  │
│  │  │   ├── logits = decoder(memory, tokens)                │  │
│  │  │   ├── log_softmax(logits)                             │  │
│  │  │   ├── topK(beam_width)                                │  │
│  │  │   ├── score += log_prob                               │  │
│  │  │   └── finished if EOS or max_length                   │  │
│  │  └── Return: top N candidates sorted by score            │  │
│  └───────────────────────────────────────────────────────────┘  │
│  Latency: < 50ms (batched), < 200ms (sequential)                │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    POST-PROCESSING                               │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ SwipeTokenizer.decode()                                   │  │
│  │  └── tokens [2,8,5,12,12,15,3] → "hello"                 │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ VocabularyFilter (optional)                               │  │
│  │  └── Filter out-of-vocabulary predictions                │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ ScoreConversion                                           │  │
│  │  └── log_prob → confidence (0-1000 scale)                │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                       OUTPUT                                     │
│  PredictionResult(                                               │
│    words = ["hello", "hallo", "help"],                          │
│    scores = [950, 820, 780],                                    │
│    confidences = [0.95, 0.82, 0.78]                             │
│  )                                                               │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
                           SUGGESTION BAR
```

---

## Appendix A: Configuration File Locations

| File | Description |
|------|-------------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | Main configuration class |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/BeamSearchEngine.kt` | Beam search constants |
| `src/main/kotlin/tribixbite/cleverkeys/onnx/TensorFactory.kt` | Tensor creation |
| `src/main/kotlin/tribixbite/cleverkeys/ContinuousGestureRecognizer.kt` | CGR parameters |
| `src/main/kotlin/tribixbite/cleverkeys/LoopGestureDetector.kt` | Loop detection |
| `src/main/kotlin/tribixbite/cleverkeys/BigramModel.kt` | Language model |
| `res/xml/settings.xml` | Settings UI definitions |

---

## Appendix B: SharedPreferences Keys

All settings are stored in SharedPreferences with these keys:

### Neural/Swipe Keys
- `neural_prediction_enabled`, `neural_beam_width`, `neural_max_length`
- `swipe_typing_enabled`, `swipe_min_distance`, `swipe_trail_enabled`

### Auto-correction Keys
- `autocorrect_enabled`, `autocorrect_min_word_length`
- `swipe_beam_autocorrect_enabled`, `swipe_final_autocorrect_enabled`

### UI Keys
- `keyboard_height`, `theme`, `character_size`
- `keyboard_opacity`, `key_opacity`, `label_brightness`

### Clipboard Keys
- `clipboard_history_enabled`, `clipboard_history_limit`
- `clipboard_pane_height_percent`, `clipboard_max_item_size_kb`

---

**Document End**
