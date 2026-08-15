# CleverKeys Settings Mapping

Complete mapping of settings to search terms, wiki guides, and implementation files.

---

## Activities

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Theme Manager | color, dark mode, light, appearance | [themes.md](wiki/customization/themes.md) | `ThemeSettingsActivity.kt`, `Theme.kt` |
| Dictionary Manager | words, custom, disabled, vocabulary | [clipboard-history.md](wiki/clipboard/clipboard-history.md) | `DictionaryManagerActivity.kt` |
| Layout Manager | keyboard layout, qwerty, azerty | [adding-layouts.md](wiki/layouts/adding-layouts.md) | `LayoutManagerActivity.kt` |
| Keyboard Calibration | height, size, foldable | [first-time-setup.md](wiki/getting-started/first-time-setup.md) | `SwipeCalibrationActivity.kt` |
| Per-Key Customization | short swipe, gesture, actions, commands | [per-key-actions.md](wiki/customization/per-key-actions.md) | `ShortSwipeCustomizationActivity.kt` |
| Short Swipe Calibration | calibrate, practice, tutorial, test | [short-swipes.md](wiki/gestures/short-swipes.md) | `ShortSwipeCalibrationActivity.kt` |
| Extra Keys | toolbar, arrows, numbers | [extra-keys.md](wiki/customization/extra-keys.md) | `ExtraKeysConfigActivity.kt` |
| CTC Settings | ctc, futo, swipe engine, beam, trie | [swipe-typing.md](wiki/typing/swipe-typing.md) | `CtcSettingsActivity.kt` (`ctc_beam_width`, default 100, range 10–300; engine opt-in via `swipe_engine_mode` = `ctc`; search entry ungated by engine mode) |
| Backup & Restore | backup, export, import, restore | [backup-restore.md](wiki/troubleshooting/backup-restore.md) | `BackupRestoreActivity.kt` |

---

## Neural Prediction

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Neural Settings | neural, ai, prediction, model, onnx | [neural-settings.md](wiki/settings/neural-settings.md) | `NeuralSettingsActivity.kt` |
| Swipe Typing | gesture, neural, glide, swipe | [swipe-typing.md](wiki/typing/swipe-typing.md) | `SwipePredictorOrchestrator.kt` |
| Prediction Engine | engine, neural, hybrid, geometric, ctc | [swipe-typing.md](wiki/typing/swipe-typing.md) | `ui/settings/sections/NeuralPredictionSection.kt` (`swipe_engine_mode`: neural/hybrid/geometric/ctc, default neural), `swipe/SwipeEngineRouter.kt` |
| Swipe on Password Fields | password, swipe, security | [privacy.md](wiki/settings/privacy.md) | `Config.kt`, `SuggestionHandler.kt` |
| Beam Width | accuracy, prediction, candidates | [neural-settings.md](wiki/settings/neural-settings.md) | `BeamSearchEngine.kt` |
| Confidence Threshold | accuracy, filter, confidence | [neural-settings.md](wiki/settings/neural-settings.md) | `BeamSearchEngine.kt` |
| Max Sequence Length | sequence, length, resampling | [neural-settings.md](wiki/settings/neural-settings.md) | `SwipeTrajectoryProcessor.kt` |

---

## Word Prediction & Autocorrect

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Word Predictions | prediction, suggestions, completion | [autocorrect.md](wiki/typing/autocorrect.md) | `WordPredictor.kt`, `SuggestionHandler.kt` |
| Suggestion Bar Opacity | opacity, transparency | [appearance.md](wiki/settings/appearance.md) | `SuggestionBar.kt` |
| Show Exact Typed Word | exact, typed, add to dictionary | [autocorrect.md](wiki/typing/autocorrect.md) | `SuggestionHandler.kt` |
| Context-Aware Predictions | context, aware, intelligent | [autocorrect.md](wiki/typing/autocorrect.md) | `PredictionContextTracker.kt` |
| Personalized Learning | learning, personalized, adapt | [autocorrect.md](wiki/typing/autocorrect.md) | `UserAdaptationManager.kt` |
| Next-Word Prediction | next word, phrase, predict, gboard | [next-word-prediction.md](wiki/typing/next-word-prediction.md) | `NextWordPredictor.kt`, `SuggestionHandler.kt` |
| Context Source | context, learned, built-in, source | [input-behavior.md](wiki/settings/input-behavior.md) | `SuggestionProvenance.kt` (`UnifiedScore`), `WordPredictor.kt` |
| Personalization Strength | personalization, weight, strength | [input-behavior.md](wiki/settings/input-behavior.md) | `PersonalizationEngine.kt`, `WordPredictor.kt` |
| Learning & Data Manager | learned, phrases, browse, forget, delete | [input-behavior.md](wiki/settings/input-behavior.md) | `LearningDataSection.kt`, `BigramStore.kt`, `UserVocabulary.kt` |
| Autocorrect | autocorrect, fix, error, typo | [autocorrect.md](wiki/typing/autocorrect.md) | `WordPredictor.kt` |
| Capitalize I Words | capitalize, i'm, i'll, uppercase | [autocorrect.md](wiki/typing/autocorrect.md) | `SuggestionHandler.kt`, `InputCoordinator.kt` |

---

## Appearance

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Key Height | size, keyboard, tall, short | [appearance.md](wiki/settings/appearance.md) | `Theme.kt`, `Keyboard2View.kt` |
| Keyboard Height Portrait/Landscape | height, portrait, landscape | [appearance.md](wiki/settings/appearance.md) | `Config.kt`, `Theme.kt` |
| Key Borders | outline, visible, border | [appearance.md](wiki/settings/appearance.md) | `KeyMagnifierView.kt` |
| Border Radius/Width | corner, radius, rounded | [appearance.md](wiki/settings/appearance.md) | `Theme.kt` |
| Margins (Horizontal/Bottom) | padding, edge, margin | [appearance.md](wiki/settings/appearance.md) | `Config.kt`, `Keyboard2View.kt` |
| Keyboard/Key Opacity | opacity, transparent | [appearance.md](wiki/settings/appearance.md) | `Theme.kt` |
| Label Brightness | brightness, text, visibility | [appearance.md](wiki/settings/appearance.md) | `Theme.kt` |
| Character Size | size, font, text | [appearance.md](wiki/settings/appearance.md) | `Theme.kt` |
| Number Row | 123, digits, top row | [appearance.md](wiki/settings/appearance.md) | `KeyboardData.kt` |
| Numpad Layout | numpad, 123, 789 | [appearance.md](wiki/settings/appearance.md) | `Config.kt` |

---

## Swipe Trail

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Swipe Trail | gesture, path, visual, effect | [swipe-typing.md](wiki/typing/swipe-typing.md) | `SwipeTrailView.kt` |
| Trail Effect | sparkle, glow, rainbow, fade | [swipe-typing.md](wiki/typing/swipe-typing.md) | `SwipeTrailView.kt` |
| Trail Color | purple, rainbow, glow | [swipe-typing.md](wiki/typing/swipe-typing.md) | `Config.kt` |
| Trail Width/Glow | width, thickness, glow | [swipe-typing.md](wiki/typing/swipe-typing.md) | `SwipeTrailView.kt` |

---

## Input Behavior

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Autocapitalization | uppercase, sentence, shift | [input-behavior.md](wiki/settings/input-behavior.md) | `Autocapitalisation.kt` |
| Long Press Timeout/Interval | hold, delay, repeat | [input-behavior.md](wiki/settings/input-behavior.md) | `Pointers.kt` |
| Key Repeat | hold, backspace, delete | [input-behavior.md](wiki/settings/input-behavior.md) | `Pointers.kt` |
| Double Tap Shift Lock | caps lock, shift | [input-behavior.md](wiki/settings/input-behavior.md) | `Config.kt` |
| Smart Punctuation | punctuation, space | [input-behavior.md](wiki/settings/input-behavior.md) | `KeyEventHandler.kt` |

---

## Gesture Tuning

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Short Gestures | short swipe, quick, action | [short-swipes.md](wiki/gestures/short-swipes.md) | `Pointers.kt` |
| Short Gesture Min/Max Distance | minimum, maximum, distance | [short-swipes.md](wiki/gestures/short-swipes.md) | `Config.kt`, `Pointers.kt` |
| Double-Space to Period | punctuation, auto, shortcut | [input-behavior.md](wiki/settings/input-behavior.md) | `KeyEventHandler.kt` |
| Finger Occlusion | offset, touch, compensation | [common-issues.md](wiki/troubleshooting/common-issues.md) | `Keyboard2View.kt` |
| Circle Sensitivity | circle, gesture | [circle-gestures.md](wiki/gestures/circle-gestures.md) | `Pointers.kt` |
| Slider Sensitivity | slider, cursor, spacebar | [cursor-navigation.md](wiki/gestures/cursor-navigation.md) | `Pointers.kt` |
| Selection-Delete Threshold/Speed | selection, delete, vertical | [selection-delete.md](wiki/gestures/selection-delete.md) | `Pointers.kt` |

---

## Accessibility & Haptics

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Vibration | haptic, feedback, tactile | [haptics.md](wiki/settings/haptics.md) | `VibratorCompat.kt` |
| Haptic Events | keypress, prediction, trackpoint, long press, swipe | [haptics.md](wiki/settings/haptics.md) | `VibratorCompat.kt`, `Config.kt` |
| Sound on Keypress | audio, click, noise | [haptics.md](wiki/settings/haptics.md) | `KeyEventHandler.kt` |

---

## Clipboard

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Clipboard History | copy, paste, buffer | [clipboard-history.md](wiki/clipboard/clipboard-history.md) | `ClipboardHistoryService.kt` |
| Clipboard Limits | history, limit, size | [clipboard-history.md](wiki/clipboard/clipboard-history.md) | `Config.kt` |
| Exclude Password Managers | password, exclude, security | [clipboard-history.md](wiki/clipboard/clipboard-history.md) | `ClipboardHistoryService.kt` |

---

## Multi-Language

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Enable Multi-Language | multilingual, bilingual | [multi-language.md](wiki/layouts/multi-language.md) | `MultiLanguageManager.kt` |
| Primary/Secondary Language | dictionary, locale | [multi-language.md](wiki/layouts/multi-language.md) | `OptimizedVocabulary.kt` |
| Language Detection | auto, detect, switch | [multi-language.md](wiki/layouts/multi-language.md) | `UnigramLanguageDetector.kt` |
| Prefix Boost | prefix, boost, language | [multi-language.md](wiki/layouts/multi-language.md) | `PrefixBoostTrie.kt` |

---

## Privacy

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Learn From My Typing (master gate) | learning, privacy, on-device, forget | [privacy.md](wiki/settings/privacy.md) | `LearningGate.kt`, `PrivacySection.kt` |
| Incognito Mode | private, secret, hide | [privacy.md](wiki/settings/privacy.md) | `Config.kt` |
| Swipe Data Collection | data, collection | [privacy.md](wiki/settings/privacy.md) | `PrivacyManager.kt` |
| Performance Metrics | performance, analytics | [privacy.md](wiki/settings/privacy.md) | `NeuralPerformanceStats.kt` |

---

## Advanced

| Setting | Search Keywords | Wiki | Files |
|---------|-----------------|------|-------|
| Suggestion Origin Markers | origin, marker, provenance, transparency | [autocorrect.md](wiki/typing/autocorrect.md) | `SuggestionBar.kt`, `SuggestionProvenance.kt` |
| Debug Logging | log, developer, verbose | [common-issues.md](wiki/troubleshooting/common-issues.md) | `Config.kt` |
| Terminal Mode | terminal, termux | [basic-typing.md](wiki/getting-started/basic-typing.md) | `Config.kt` |
| Detailed Swipe Logging | detailed, swipe, logging | [performance.md](wiki/troubleshooting/performance.md) | `SwipeDebugActivity.kt` |

---

## Missing Wiki Coverage

Settings without dedicated wiki pages (use general pages):

| Setting | Suggested Wiki |
|---------|----------------|
| Beam Autocorrect | neural-settings.md or autocorrect.md |
| Final Autocorrect | autocorrect.md |
| Suggestion Bar Opacity | appearance.md |
| Vertical Key Spacing | appearance.md |
| Min Dwell Time | swipe-typing.md |
| Noise Threshold | swipe-typing.md |
| High Velocity Threshold | swipe-typing.md |

---

## Config.kt Setting Keys

For backup/restore and preference access:

```kotlin
// Neural
"swipe_typing", "beam_width", "confidence_threshold", "max_seq_length"

// Prediction
"word_prediction", "autocorrect", "autocapitalize_i_words"

// Appearance
"keyboard_height_percent_portrait", "keyboard_height_percent_landscape"
"margin_left", "margin_right", "margin_bottom_portrait", "margin_bottom_landscape"
"key_opacity", "keyboard_opacity", "label_brightness"

// Gestures
"short_gestures", "short_gesture_min_distance", "short_gesture_max_distance"
"double_space_to_period", "selection_delete_vertical_threshold"

// Haptics
"haptic_enabled", "haptic_key_press", "haptic_prediction_tap"

// Clipboard
"clipboard_enabled", "clipboard_history_max_entries"

// Multi-language
"multi_lang_enabled", "pref_primary_language", "pref_secondary_language"

// Privacy & learning (2026-08-06)
"incognito_mode", "privacy_collect_swipe_data", "privacy_collect_performance"
"on_device_learning_enabled"        // master gate over ALL typing-behavior learning (default true)
"next_word_prediction_enabled"      // opt-in learned next-word suggestions (default false)
"context_source"                    // "both" | "learned_only" | "static_only" (default "both")
"personalization_weight"            // 0.0-2.0 continuous strength (default 1.0)
"personalization_max_words"         // learned-vocabulary size cap, 1000-20000 (default 5000)
"suggestion_provenance_markers"     // opt-in origin dots on suggestions (default false)
```
