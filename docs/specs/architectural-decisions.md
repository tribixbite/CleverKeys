# Architectural Decisions

## Overview

This document records intentional architectural changes from the original Unexpected-Keyboard Java implementation. These are design decisions, not bugs.

## ADR-001: Pure ONNX Neural Prediction

**Decision**: Replace dictionary-based prediction with pure ONNX transformer neural networks.

**Rationale**:
- Superior prediction accuracy with learned patterns
- Better handling of complex gestures
- Single model architecture vs multiple heuristics
- Modern ML approach vs legacy statistical methods

**Consequences**:
- Training now external (Python/PyTorch) vs on-device
- Requires ONNX Runtime dependency
- Model updates require full retraining

## ADR-002: Template Generation → Neural Training

**Decision**: Replace gesture template generation with neural network training on real swipe data.

**Rationale**:
- Learns from actual user behavior vs synthetic templates
- Captures natural gesture variations
- Automatic feature learning vs manual template engineering
- Transformer architecture superior to template matching

**Consequences**:
- Requires training dataset of real swipe gestures
- External training pipeline needed
- Cannot generate predictions for arbitrary words without retraining

## ADR-003: External ML Training

**Decision**: Move ML training to external Python/PyTorch pipeline with GPU acceleration, export to ONNX.

**Rationale**:
- Real neural networks (transformers) vs statistical heuristics
- GPU acceleration for complex models
- Separation of training (offline) from inference (on-device)
- Modern ML tooling (PyTorch) vs custom Java code
- Can use large training datasets

**Consequences**:
- Cannot train models on device
- Requires separate training infrastructure
- Model updates require rebuild/redeploy
- Better prediction quality

## ADR-004: Coroutines Over HandlerThread

**Decision**: Replace HandlerThread pattern with Kotlin Coroutines + Flow for async predictions.

**Rationale**:
- Modern Kotlin async/await patterns
- Structured concurrency vs manual thread lifecycle
- Flow streams for reactive prediction updates
- Better cancellation and error handling
- Reduced boilerplate code

**Consequences**:
- All prediction code uses suspend functions
- Lifecycle-aware coroutine scopes
- Better integration with Android lifecycle

## ADR-005: Neural Feature Learning

**Decision**: Replace manual feature engineering with neural network automatic feature learning.

**Rationale**:
- Transformer models learn optimal features from data
- Reduced from 40+ manual features to 6 input features (x, y, t, pressure, key, finger)
- Model discovers complex patterns humans might miss
- Less code to maintain
- Better generalization to unseen data

**Consequences**:
- Feature engineering complexity moved into neural network
- Harder to debug "why" predictions work
- Requires quality training data

## ADR-006: Gaussian Key Model Replacement

**Decision**: Replace Gaussian key modeling with neural network spatial encoding.

**Rationale**:
- Neural network learns optimal spatial representations
- Transformer attention mechanism handles spatial relationships
- Simpler architecture vs manual probability calculations

**Consequences**:
- Spatial encoding handled by neural network
- Less interpretable than Gaussian distributions

## ADR-007: Component Initialization Order Dependencies

**Decision**: Enforce strict initialization order for components with dependencies in CleverKeysService.

**Implementation**:
```kotlin
// CORRECT ORDER
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

**Best Practice**:
When adding new components:
1. Identify all dependencies
2. Place initialization AFTER all dependencies
3. Pass dependencies explicitly via constructor/method parameters
4. Document dependency chain in comments if complex

## Summary

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
- Reduced code complexity
- Better async patterns with coroutines
- Scalable to larger models with external training
