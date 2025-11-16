# Architectural Decisions - CleverKeys

**Last Updated**: 2025-11-16

This document tracks intentional architectural changes from the original Unexpected-Keyboard Java implementation. These are NOT bugs to fix, but rather modern design improvements.

**Total ADRs**: 7

---

## Decision Log

### ADR-001: Pure ONNX Neural Prediction
**Status**: ✅ ACCEPTED
**Date**: 2025 (Initial Architecture)

**Context**:
- Original used WordPredictor.java (782 lines) with dictionary lookups, language models, and user adaptation
- Statistical approach with manual pattern matching

**Decision**:
Replace dictionary-based prediction with pure ONNX transformer neural networks.

**Rationale**:
- Superior prediction accuracy with learned patterns
- Better handling of complex gestures
- Single model architecture vs multiple heuristics
- Modern ML approach vs legacy statistical methods

**Consequences**:
- **Bug #262 (File 64)**: WordPredictor.java completely replaced
- Training now external (Python/PyTorch) vs on-device
- Requires ONNX Runtime dependency
- Model updates require full retraining

**References**: Files 64, 76-78 (CGR system replaced)

---

### ADR-002: Template Generation → Neural Training
**Status**: ✅ ACCEPTED
**Date**: 2025 (Initial Architecture)

**Context**:
- Original used WordGestureTemplateGenerator.java (406 lines) to generate gesture templates from dictionaries
- Manual template creation for each word

**Decision**:
Replace gesture template generation with neural network training on real swipe data.

**Rationale**:
- Learns from actual user behavior vs synthetic templates
- Captures natural gesture variations
- Automatic feature learning vs manual template engineering
- Transformer architecture superior to template matching

**Consequences**:
- **Bug #265 (File 69)**: WordGestureTemplateGenerator.java not ported
- Requires training dataset of real swipe gestures
- External training pipeline needed
- Cannot generate predictions for arbitrary words without retraining

**References**: Files 69, 72

---

### ADR-003: External ML Training
**Status**: ✅ ACCEPTED
**Date**: 2025 (Initial Architecture)

**Context**:
- Original used SwipeMLTrainer.java for on-device statistical pattern matching
- Limited to device CPU capabilities
- Heuristic-based "training" vs true neural networks

**Decision**:
Move ML training to external Python/PyTorch pipeline with GPU acceleration, export to ONNX.

**Rationale**:
- Real neural networks (transformers) vs statistical heuristics
- GPU acceleration for complex models
- Separation of training (offline) from inference (on-device)
- Modern ML tooling (PyTorch) vs custom Java code
- Can use large training datasets

**Consequences**:
- **Bug #274 (File 72)**: SwipeMLTrainer.java not ported
- Cannot train models on device
- Requires separate training infrastructure
- Model updates require rebuild/redeploy
- Better prediction quality

**References**: Files 72, 74 (NeuralSettingsActivity vs CGRSettingsActivity)

---

### ADR-004: Coroutines Over HandlerThread
**Status**: ✅ ACCEPTED
**Date**: 2025 (Initial Architecture)

**Context**:
- Original used AsyncPredictionHandler.java with HandlerThread + Message queue pattern
- Callback-based async with manual thread management

**Decision**:
Replace HandlerThread pattern with Kotlin Coroutines + Flow for async predictions.

**Rationale**:
- Modern Kotlin async/await patterns
- Structured concurrency vs manual thread lifecycle
- Flow streams for reactive prediction updates
- Better cancellation and error handling
- Reduced boilerplate code

**Consequences**:
- **Bug #275 (File 73)**: AsyncPredictionHandler.java replaced by PredictionRepository.kt + coroutines
- All prediction code uses suspend functions
- Lifecycle-aware coroutine scopes
- Better integration with Android lifecycle

**References**: Files 73, 91 (SwipePredictionService)

---

### ADR-005: Neural Feature Learning
**Status**: ✅ ACCEPTED
**Date**: 2025 (Initial Architecture)

**Context**:
- Original used ComprehensiveTraceAnalyzer.java (710 lines) with 40+ manually engineered features
- Speed, curvature, direction changes, pressure, etc. all calculated manually
- Statistical analysis on feature vectors

**Decision**:
Replace manual feature engineering with neural network automatic feature learning.

**Rationale**:
- Transformer models learn optimal features from data
- Reduced from 40+ manual features to 6 input features (x, y, t, pressure, key, finger)
- Model discovers complex patterns humans might miss
- Less code to maintain (710 lines → simplified preprocessing)
- Better generalization to unseen data

**Consequences**:
- **Bug #276 (File 75)**: ComprehensiveTraceAnalyzer.java not fully ported
- SwipeTrajectoryProcessor.kt is simplified (6 features only)
- Feature engineering complexity moved into neural network
- Harder to debug "why" predictions work
- Requires quality training data

**References**: Files 75, 80-81 (EnhancedSwipeGestureRecognizer, EnhancedWordPredictor)

---

### ADR-006: Gaussian Key Model Replacement
**Status**: ✅ ACCEPTED
**Date**: 2025 (Initial Architecture)

**Context**:
- Original used GaussianKeyModel.java for probabilistic key touch modeling
- Manual Gaussian distribution calculations

**Decision**:
Replace Gaussian key modeling with neural network spatial encoding.

**Rationale**:
- Neural network learns optimal spatial representations
- Transformer attention mechanism handles spatial relationships
- Simpler architecture vs manual probability calculations

**Consequences**:
- **Bug #283 (File 83)**: GaussianKeyModel.java architectural replacement
- Spatial encoding handled by neural network
- Less interpretable than Gaussian distributions

**References**: Files 83

---

## Summary

**Total Architectural Changes**: 6 major decisions

**Philosophy**:
- **Modern ML**: ONNX transformers > statistical heuristics
- **Modern Kotlin**: Coroutines > HandlerThread callbacks
- **Automatic Learning**: Neural networks > manual feature engineering
- **External Training**: GPU acceleration > device CPU "training"

**Trade-offs Accepted**:
- External training dependency (Python/PyTorch required)
- Cannot train on-device (by design)
- Less interpretable models (neural networks are black boxes)
- Requires quality training datasets
- Model updates need rebuild/redeploy

**Benefits Gained**:
- Superior prediction accuracy
- Modern codebase with Kotlin best practices
- Reduced code complexity (2000+ lines of Java not ported)
- Better async patterns with coroutines
- Scalable to larger models with external training

---

### ADR-007: Component Initialization Order Dependencies
**Status**: ✅ ACCEPTED
**Date**: 2025-11-14

**Context**:
- CleverKeysService.kt initializes 90+ components in `onCreate()`
- Some components have dependencies on other components
- WordPredictor requires LanguageDetector and UserAdaptationManager references
- Initial implementation had wrong initialization order causing null references

**Problem**:
Early implementation initialized WordPredictor before its dependencies:
```kotlin
// WRONG ORDER (before fix)
wordPredictor = initializeWordPredictor()  // Called first
languageDetector = LanguageDetector()      // Called later - too late!
userAdaptationManager = UserAdaptationManager() // Called later - too late!
```

This caused WordPredictor to receive null references for language detection and user adaptation features.

**Decision**:
Enforce strict initialization order for components with dependencies:
1. Initialize standalone components first (no dependencies)
2. Initialize dependency components next
3. Initialize dependent components last

**Implementation** (Commit 6aab63a4):
```kotlin
// CORRECT ORDER (after fix)
// Step 1: Initialize dependencies first
languageDetector = LanguageDetector()
userAdaptationManager = UserAdaptationManager(context)

// Step 2: Initialize components that depend on them
wordPredictor = initializeWordPredictor(
    languageDetector = languageDetector,
    userAdaptationManager = userAdaptationManager
)
```

**Rationale**:
- Prevents null reference bugs at initialization
- Makes dependency relationships explicit
- Enables proper feature integration
- Follows dependency injection principles
- Ensures all features work correctly from first use

**Consequences**:
- ✅ Language detection now works correctly
- ✅ User adaptation features fully functional
- ✅ No null pointer exceptions during initialization
- ✅ Clear dependency chain documented in code
- ⚠️ Must maintain correct order when adding new components
- ⚠️ Adding new dependencies requires order review

**Best Practice Established**:
When adding new components to CleverKeysService initialization:
1. Identify all dependencies (what does it need?)
2. Place initialization AFTER all dependencies
3. Pass dependencies explicitly via constructor/method parameters
4. Document dependency chain in comments if complex

**References**:
- Commit: 6aab63a4
- Files: CleverKeysService.kt lines 311-406 (onCreate initialization)
- Related: WordPredictor.kt, LanguageDetector.kt, UserAdaptationManager.kt

---

## Future Considerations

**Potential Enhancements**:
1. On-device fine-tuning for personalization (federated learning)
2. Multi-language model support (separate models per language)
3. Model quantization for smaller size/faster inference
4. A/B testing framework for model comparisons

**Not Planned**:
- Reverting to dictionary-based prediction
- On-device full model training
- Manual feature engineering approaches
- Template-based gesture matching

---

**See Also**:
- `docs/ONNX_DECODE_PIPELINE.md` - Neural prediction pipeline details
- `migrate/claude_history.md` - Historical development decisions
- `migrate/completed_reviews.md` - Original file reviews
