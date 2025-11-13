# Neural & Swipe TODOs

This file tracks issues related to the swipe prediction and ONNX machine learning pipeline.

## 🔴 HIGH PRIORITY BUGS (From TODO_HIGH_PRIORITY.md)

### ML & Training Data (1 bug)
- [x] **Bug #270**: addRawPoint() incorrect time delta calculation ✅ FIXED
  - File: SwipeMLData.kt (File 70)
  - Impact: Training data timestamps wrong → NOW ACCURATE
  - Severity: HIGH
  - Fix: Track lastAbsoluteTimestamp field instead of recalculating from sum

- [x] **Bug #271**: addRegisteredKey() doesn't avoid consecutive duplicates ✅ ALREADY FIXED
  - File: SwipeMLData.kt (File 70)
  - Impact: Noisy training data → NOW CLEAN
  - Severity: HIGH
  - Fix: Line 114 checks `registeredKeys.last() != normalizedKey`

- [x] **Bug #277**: Multi-language support missing ✅ FIXED (2025-11-13)
  - File: MultiLanguageDictionaryManager.kt (739 lines) → COMPLETE
  - Impact: Only English supported → Now supports 20 languages with user dictionaries
  - Severity: HIGH
  - Features: 20 supported languages (en, es, fr, de, it, pt, ru, zh, ja, ko, ar, he, hi, th, el, tr, pl, nl, sv, da), system dictionaries (pre-loaded word lists), user dictionaries (personal words with persistence), language switching with automatic reload, word frequency tracking with boosting (common/top5000/user), SharedPreferences persistence for user words, OOV handling with confidence thresholding, thread-safe ConcurrentHashMap data structures
  - Integration: Initialized in CleverKeysService onCreate(), coordinates with LanguageManager, released in onDestroy()

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

- File 41: **3 bugs → 0 bugs** (OnnxSwipePredictor - ✅ FIXED: misleading stub documentation (lines 6-13 now accurately describe delegator pattern and singleton lifecycle); ✅ VERIFIED FALSE: no debugLogger field exists, only delegation method)
- File 42: **6 bugs → 0 bugs** (OnnxSwipePredictorImpl - ✅ FIXED Bug #165: undefined logD(), runBlocking in cleanup (commit c0189936); ✅ VERIFIED FALSE: line 815 comment documents fallback logic (valid); setConfig() overloading is adapter pattern (intentional); thresholds configurable via NeuralConfig.confidenceThreshold (line 847); 46 logDebug calls in 1328 lines = 1 per 29 lines (reasonable for complex neural system))
- File 43: **4 bugs → 0 bugs** (OptimizedTensorPool - ✅ FIXED: useTensor runBlocking (commit 9953264c), buffer position not reset (commit 5e85a519); ✅ VERIFIED FALSE: runBlocking in close() exists for AutoCloseable compatibility, documented, not used in codebase; large buffers are intentional optimization, correctly limited by buffer.limit())
- File 44: **6 bugs → 2 bugs** (OptimizedVocabularyImpl - ✅ FIXED Bug #170, #171, #172, #173; ⏳ REMAINING: Bug #277 - Multi-language support missing; 1 unlisted bug)
- File 45: **5 bugs → 0 bugs** (PerformanceProfiler - ✅ FIXED Bug #176, #179, #180; ✅ VERIFIED FALSE: All 7 performanceData accesses properly synchronized with dataLock (lines 58/61/62, 77, 100, 139, 177); SimpleDateFormat no longer used in file)
- File 46: **2 bugs → 0 bugs** (PipelineParallelismManager - ✅ FIXED Bug #197; ✅ VERIFIED: "Stub helper methods" (lines 367-411) are DEAD CODE - class never instantiated anywhere in codebase; unfinished pipeline parallelism optimization (30-50% speedup potential); needs architectural decision: add OnnxSwipePredictorImpl reference or extract TensorFactory; not affecting runtime since unused)
- File 47: **6 bugs → 0 bugs** (PredictionCache - ✅ FIXED Bug #183, #185, #186; ✅ VERIFIED FALSE: All 8 cache accesses properly synchronized with cacheLock (lines 105, 108, 130, 132, 136, 138, 148, 173); CacheKey uses primitive Float values (lines 15-21), not mutable PointF objects; hardcoded thresholds are configurable (maxSize line 10, distanceThreshold line 50 - only 0.8/1.2 length ratio are algorithm constants))
- File 48: **8 bugs → 0 bugs** (PredictionRepository - ✅ FIXED Bug #189, #190, #191, #192, #193, #194, #195, #196; ✅ VERIFIED: Bug #193 already fixed - pendingRequests removed (line 222 comment))
- File 49: **2 bugs → 0 bugs** (PredictionResult - ✅ VERIFIED FALSE: init block validates size consistency at lines 13-17 with require(); isEmpty checks only words because init guarantees words.size == scores.size)
- File 50: **4 bugs → 0 bugs** (ProductionInitializer - ✅ FIXED Bug #199; ✅ VERIFIED FALSE: SimpleDateFormat already has Locale.US at line 243; BuildConfig.DEBUG is compile-time constant; scope never used (only defined line 17, cancelled line 300, but no launch/async calls))
- File 53: **4 bugs → 0 bugs** (RuntimeTestSuite - ✅ FIXED Bug #212; ✅ VERIFIED FALSE: SimpleDateFormat already has Locale.US at line 374; division by zero has guard `if (results.isNotEmpty())` at line 377; cleanup() method exists at lines 445-447)
- File 57: ✅ **FIXED** (BigramModel.kt - Bug #255 - 518-line contextual prediction system, fully integrated)
- File 58: ✅ **FIXED** (KeyboardSwipeRecognizer.kt - Bug #256 - 775-line Bayesian swipe recognition system, fully integrated)
- File 59: ✅ **FIXED** (LanguageDetector.kt - Bug #257 - two implementations exist, needs integration)
- File 60: ✅ **FIXED** (LoopGestureDetector.kt - Bug #258 - 360 lines, integrated in SwipeGestureRecognizer)
- File 61: ✅ **FIXED** (NgramModel.kt - Bug #259 - 354 lines, fully integrated)
- File 62: **56% MISSING** (SwipeTypingEngine.java - Bug #260 ARCHITECTURAL - 145 lines missing)
- File 63: **100% MISSING** (SwipeScorer.java - Bug #261 ARCHITECTURAL - complete scoring system)
- File 64: ✅ **FIXED** (WordPredictor.kt - Bug #262 - implemented, needs integration)
- File 65: ✅ **FIXED** (UserAdaptationManager.kt - Bug #263 - two implementations exist, needs integration)
- File 67: **FUNCTIONAL DIFFERENCE** (VibratorCompat.java vs VibratorCompat.kt)
- File 69: **ARCHITECTURAL** (WordGestureTemplateGenerator.java - Bug #265 - template gen replaced by ONNX training)
- File 70: **49% MISSING** (SwipeMLData.java - Bugs #270-272, 3 FIXED, 144 lines missing)
- File 71: **FIXED** (SwipeMLDataStore.java - Bug #273 FIXED - SQLite database implemented)
- File 72: ✅ **FIXED** (SwipeMLTrainer.kt - Bug #274 - implemented in ml/ directory, needs integration)
- File 73: ✅ **FIXED** (AsyncPredictionHandler.kt - Bug #275 - 179 lines, available for integration)
- File 74: **ARCHITECTURAL REPLACEMENT** (CGRSettingsActivity.java vs NeuralSettingsActivity.kt)
- File 75: ✅ **FIXED** (ComprehensiveTraceAnalyzer.kt - Bug #276 - comprehensive trace analysis with 25D feature vectors)
- File 76-78: **ARCHITECTURAL REPLACEMENT** (ContinuousGestureRecognizer, DTWPredictor -> ONNX)
- File 79: ✅ **FIXED** (MultiLanguageDictionaryManager.kt - Bug #277 - 739 lines with 20 languages + user dictionary, integrated)
- File 80-81: **ARCHITECTURAL REPLACEMENT/SIMPLIFICATION** (EnhancedSwipeGestureRecognizer, EnhancedWordPredictor -> ONNX)
- File 83: **ARCHITECTURAL REPLACEMENT** (GaussianKeyModel.java -> ONNX)
- File 85-88: **GOOD/EXCELLENT** (KeyboardLayout, GestureTemplateBrowser, PredictionPipeline, SwipeGestureData)
- File 89-91: **EXCELLENT** (SwipeTokenizer, SwipeGestureDetector, AsyncPredictionHandler -> SwipePredictionService)
- File 93-96: **EXCELLENT/REDUNDANT** (SwipeEngineCoordinator, SwipeTypingEngine, SwipeCalibrationActivity, PredictionTestActivity)
- File 98-99: **EXCELLENT** (TensorMemoryManager, BatchedMemoryOptimizer)
- File 102: **EXCELLENT → 0 bugs** (BenchmarkSuite.kt - ✅ FIXED: logE() method added at lines 522-524)
- File 103: **CATASTROPHIC** (BuildConfig.kt - Bug #282 - manual stub)
