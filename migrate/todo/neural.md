# Neural & Swipe TODOs

This file tracks issues related to the swipe prediction and ONNX machine learning pipeline.

## 🔴 HIGH PRIORITY BUGS (From TODO_HIGH_PRIORITY.md)

### ML & Training Data (3 bugs)
- [ ] **Bug #270**: addRawPoint() incorrect time delta calculation
  - File: SwipeMLData.kt (File 70)
  - Impact: Training data timestamps wrong
  - Severity: HIGH

- [ ] **Bug #271**: addRegisteredKey() doesn't avoid consecutive duplicates
  - File: SwipeMLData.kt (File 70)
  - Impact: Noisy training data
  - Severity: HIGH

- [ ] **Bug #277**: Multi-language support missing
  - File: DictionaryManager.java → OptimizedVocabularyImpl.kt (File 79)
  - Impact: Only English supported
  - Severity: HIGH
  - Note: Missing user dictionary support and language switching

---

## 📋 NEURAL PREDICTION FILES STATUS (From REVIEW_TODO_NEURAL.md)

**Files Reviewed**: See Files 41-50, 57-65, 67-100 in core review
**Key Findings**:
- Pure ONNX architecture (no CGR system)
- 6 architectural replacements (intentional - see `docs/specs/architectural-decisions.md`)
- 5+ CATASTROPHIC missing files (BigramModel, NgramModel, etc.)

**Remaining Java Files Needing Review**:
- [ ] Additional prediction-related files in Files 142-251

---

## 🟢 ONNX PIPELINE BUGS

- File 41: **3 bugs** (OnnxSwipePredictor - LOW: redundant debugLogger field, misleading stub documentation, undocumented singleton lifecycle)
- File 42: **6 bugs → 5 bugs** (OnnxSwipePredictorImpl - ✅ FIXED Bug #165: undefined logD(); ⏳ REMAINING: orphaned comment, runBlocking in cleanup, code duplication, hardcoded thresholds, excessive logging)
- File 43: **4 bugs** (OptimizedTensorPool - MEDIUM: runBlocking in close(); LOW: useTensor runBlocking, large buffers, buffer position not reset)
- File 44: **6 bugs → 5 bugs** (OptimizedVocabularyImpl - ✅ FIXED Bug #170; ⏳ REMAINING: HIGH - filters out ALL OOV predictions; MEDIUM - RuntimeException on load failure; LOW - hardcoded limits, optimization issues)
- File 45: **5 bugs → 4 bugs** (PerformanceProfiler - ✅ FIXED Bug #176; ⏳ REMAINING: HIGH - thread-unsafe performanceData access; MEDIUM - unsafe JSON metadata; LOW - SimpleDateFormat without Locale, missing stopMonitoring)
- File 46: **2 bugs** (PipelineParallelismManager - CRITICAL: stub helper methods; LOW: isRunning flag not thread-safe)
- File 47: **6 bugs → 5 bugs** (PredictionCache - ✅ FIXED Bug #183; ⏳ REMAINING: HIGH - thread-unsafe cache access; MEDIUM - inefficient LRU eviction; LOW - mutable PointF in CacheKey, missing cache metrics, hardcoded thresholds)
- File 48: **8 bugs → 6 bugs** (PredictionRepository - ✅ FIXED Bug #189, #190; ⏳ REMAINING: HIGH - thread-unsafe stats; MEDIUM - non-functional stats, getStats() mutates channel, unbounded channel capacity; LOW - wrong cancellation, error type loss)
- File 49: **2 bugs** (PredictionResult - MEDIUM: no validation of list size consistency; LOW: inconsistent isEmpty check)
- File 50: **4 bugs → 3 bugs** (ProductionInitializer - ✅ FIXED Bug #199; ⏳ REMAINING: MEDIUM - SimpleDateFormat without Locale; LOW - unchecked BuildConfig access, no scope cleanup in failures)
- File 53: **4 bugs → 3 bugs** (RuntimeTestSuite - ✅ FIXED Bug #212; ⏳ REMAINING: MEDIUM - SimpleDateFormat without Locale; LOW - division by zero possible, no scope cleanup on failures)
- File 57: **1 CATASTROPHIC bug** (BigramModel.java - Bug #255: Entire 506-line contextual word prediction system COMPLETELY MISSING)
- File 58: **1 CATASTROPHIC bug** (KeyboardSwipeRecognizer.java - Bug #256: Entire 1000-line Bayesian keyboard-specific swipe recognition system COMPLETELY MISSING)
- File 59: **1 CATASTROPHIC bug** (LanguageDetector.java - Bug #257: COMPLETELY MISSING)
- File 60: **1 CATASTROPHIC bug** (LoopGestureDetector.java - Bug #258: COMPLETELY MISSING)
- File 61: **1 CATASTROPHIC bug** (NgramModel.java - Bug #259: COMPLETELY MISSING)
- File 62: **56% MISSING** (SwipeTypingEngine.java - Bug #260 ARCHITECTURAL - 145 lines missing)
- File 63: **100% MISSING** (SwipeScorer.java - Bug #261 ARCHITECTURAL - complete scoring system)
- File 64: **1 CATASTROPHIC bug** (WordPredictor.java - Bug #262: COMPLETELY MISSING)
- File 65: **100% MISSING** (UserAdaptationManager.java - Bug #263 CATASTROPHIC - no user learning)
- File 67: **FUNCTIONAL DIFFERENCE** (VibratorCompat.java vs VibratorCompat.kt)
- File 69: **ARCHITECTURAL** (WordGestureTemplateGenerator.java - Bug #265 - template gen replaced by ONNX training)
- File 70: **49% MISSING** (SwipeMLData.java - Bugs #270-272, 3 FIXED, 144 lines missing)
- File 71: **FIXED** (SwipeMLDataStore.java - Bug #273 FIXED - SQLite database implemented)
- File 72: **COMPLETELY MISSING** (SwipeMLTrainer.java - Bug #274 CATASTROPHIC - no ML training system)
- File 73: **COMPLETELY MISSING** (AsyncPredictionHandler.java - Bug #275 CATASTROPHIC - UI blocking, no async)
- File 74: **ARCHITECTURAL REPLACEMENT** (CGRSettingsActivity.java vs NeuralSettingsActivity.kt)
- File 75: **COMPLETELY MISSING** (ComprehensiveTraceAnalyzer.java - Bug #276 CATASTROPHIC - no advanced gesture analysis)
- File 76-78: **ARCHITECTURAL REPLACEMENT** (ContinuousGestureRecognizer, DTWPredictor -> ONNX)
- File 79: **PARTIAL** (DictionaryManager.java vs OptimizedVocabularyImpl.kt - Bug #277 HIGH - missing multi-lang & user dict)
- File 80-81: **ARCHITECTURAL REPLACEMENT/SIMPLIFICATION** (EnhancedSwipeGestureRecognizer, EnhancedWordPredictor -> ONNX)
- File 83: **ARCHITECTURAL REPLACEMENT** (GaussianKeyModel.java -> ONNX)
- File 85-88: **GOOD/EXCELLENT** (KeyboardLayout, GestureTemplateBrowser, PredictionPipeline, SwipeGestureData)
- File 89-91: **EXCELLENT** (SwipeTokenizer, SwipeGestureDetector, AsyncPredictionHandler -> SwipePredictionService)
- File 93-96: **EXCELLENT/REDUNDANT** (SwipeEngineCoordinator, SwipeTypingEngine, SwipeCalibrationActivity, PredictionTestActivity)
- File 98-99: **EXCELLENT** (TensorMemoryManager, BatchedMemoryOptimizer)
- File 102: **EXCELLENT** (BenchmarkSuite.kt - 1 bug logE() undefined)
- File 103: **CATASTROPHIC** (BuildConfig.kt - Bug #282 - manual stub)
