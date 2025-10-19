# Review Documentation Organization by Feature/Component

## Proposed Structure (Feature-Based)

Instead of arbitrary chunks (one-of-four, two-of-four), organize by component:

### 1. REVIEW_CORE_SYSTEM.md
**Core keyboard infrastructure** (12 files):
- Config.java
- Keyboard2.java
- Keyboard2View.java
- KeyboardData.java
- Pointers.java
- KeyEventHandler.java
- KeyModifier.java
- KeyValue.java
- KeyValueParser.java
- Theme.java
- Logs.java
- Utils.java

### 2. REVIEW_GESTURES_INPUT.md
**Gesture recognition & input handling** (11 files):
- Gesture.java ✅ (File 84 - FIXED)
- GestureClassifier.java ✅ (File 85 - FIXED)
- SwipeGestureRecognizer.java
- ImprovedSwipeGestureRecognizer.java
- EnhancedSwipeGestureRecognizer.java
- ContinuousGestureRecognizer.java
- ContinuousSwipeGestureRecognizer.java
- LoopGestureDetector.java
- SwipeDetector.java
- SwipeInput.java
- FoldStateTracker.java ✅ (File 83 - REVIEWED)

### 3. REVIEW_NEURAL_PREDICTION.md
**Neural/ML prediction systems** (15 files):
- OnnxSwipePredictor.java
- NeuralSwipeTypingEngine.java
- EnhancedWordPredictor.java (File 81)
- WordPredictor.java
- AsyncPredictionHandler.java (File 73)
- PredictionResult.java
- PredictionSource.java
- SwipeTrajectoryProcessor.java
- ComprehensiveTraceAnalyzer.java (File 75)
- ProbabilisticKeyDetector.java
- NeuralVocabulary.java
- OptimizedVocabulary.java
- DictionaryManager.java
- BigramModel.java
- NgramModel.java

### 4. REVIEW_ML_TRAINING_DATA.md
**Machine learning & data collection** (7 files):
- ml/SwipeMLData.java (File 70)
- ml/SwipeMLDataStore.java (File 71)
- ml/SwipeMLTrainer.java (File 72)
- SwipePruner.java
- SwipeTokenizer.java
- PersonalizationManager.java
- UserAdaptationManager.java

### 5. REVIEW_UI_ACTIVITIES.md
**UI, settings, activities** (13 files):
- SettingsActivity.java
- LauncherActivity.java
- SwipeCalibrationActivity.java
- SwipeDebugActivity.java
- TemplateBrowserActivity.java
- CustomLayoutEditDialog.java
- SuggestionBar.java
- SwipeAdvancedSettings.java
- EmojiGridView.java
- EmojiGroupButtonsBar.java
- Emoji.java
- NonScrollListView.java
- VibratorCompat.java

### 6. REVIEW_CLIPBOARD_COMPOSE.md
**Clipboard & text composition** (8 files):
- ClipboardDatabase.java
- ClipboardHistoryService.java
- ClipboardHistoryView.java
- ClipboardHistoryCheckBox.java
- ClipboardPinView.java
- ComposeKey.java
- ComposeKeyData.java
- Autocapitalisation.java

### 7. REVIEW_LAYOUT_CUSTOMIZATION.md
**Layout & customization** (8 files):
- ExtraKeys.java ✅ (File 82 - FIXED)
- LayoutModifier.java
- Modmap.java
- NumberLayout.java
- DirectBootAwarePreferences.java
- prefs/* files
- WordGestureTemplateGenerator.java
- LanguageDetector.java

### 8. REVIEW_PERFORMANCE_MISC.md
**Performance & utilities** (3 files):
- PerformanceProfiler.java
- VoiceImeSwitcher.java
- misc utilities

---

## Current Status Mapping

**Files Reviewed So Far:**
- File 70: SwipeMLData.java → REVIEW_ML_TRAINING_DATA.md
- File 71: SwipeMLDataStore.java → REVIEW_ML_TRAINING_DATA.md
- File 72: SwipeMLTrainer.java → REVIEW_ML_TRAINING_DATA.md
- File 73: AsyncPredictionHandler.java → REVIEW_NEURAL_PREDICTION.md
- File 75: ComprehensiveTraceAnalyzer.java → REVIEW_NEURAL_PREDICTION.md
- File 76: ContinuousGestureRecognizer.java → REVIEW_GESTURES_INPUT.md
- File 77: ContinuousSwipeGestureRecognizer.java → REVIEW_GESTURES_INPUT.md
- File 80: EnhancedSwipeGestureRecognizer.java → REVIEW_GESTURES_INPUT.md
- File 81: EnhancedWordPredictor.java → REVIEW_NEURAL_PREDICTION.md
- File 82: ExtraKeys.java ✅ → REVIEW_LAYOUT_CUSTOMIZATION.md
- File 83: FoldStateTracker.java ✅ → REVIEW_GESTURES_INPUT.md
- File 84: Gesture.java ✅ → REVIEW_GESTURES_INPUT.md
- File 85: GestureClassifier.java ✅ → REVIEW_GESTURES_INPUT.md

---

## Migration Plan

**Step 1:** Create 8 new component-based files
**Step 2:** Extract reviews from existing REVIEW_PROGRESS-*.md and reorganize by component
**Step 3:** Delete old REVIEW_PROGRESS-[one-four]-of-four.md files
**Step 4:** Update CLAUDE.md with new organization

---

## Benefits

1. **Logical grouping**: Related bugs/features together
2. **Easier navigation**: Find gesture issues in REVIEW_GESTURES_INPUT.md
3. **Better context**: Neural prediction bugs all in one place
4. **Balanced file sizes**: ~8-12 files per component instead of 24K lines in one file
5. **Maintainable**: Can review entire subsystem at once
