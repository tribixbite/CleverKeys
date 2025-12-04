# CleverKeys Test Suite Specification

## Feature Overview
**Feature Name**: Comprehensive Test Suite
**Priority**: P0 (Critical for Production Readiness)
**Status**: Planning
**Target Version**: v1.0.0

### Summary
A professional-grade Android test suite providing complete coverage of all UI components, features, and functionality in CleverKeys, including unit tests, integration tests, UI tests, instrumentation tests, and ADB intent tests.

### Motivation
To ensure CleverKeys is production-ready, reliable, and maintainable, we need comprehensive automated testing covering all components. This prevents regressions, validates functionality across Android versions, and ensures compatibility with all device types.

---

## Testing Architecture

### Test Types & Tools

1. **Unit Tests** (JUnit 5 + MockK)
   - Pure Kotlin logic testing
   - View models, use cases, utilities
   - No Android framework dependencies

2. **Android Unit Tests** (Robolectric)
   - Tests requiring Android Context
   - Lightweight, fast execution
   - Preference management, resource loading

3. **Integration Tests** (AndroidX Test)
   - Multi-component interactions
   - Service integration, data flow
   - Repository + DAO + Service combinations

4. **UI Tests** (Espresso + UI Automator)
   - User interface interactions
   - View hierarchy validation
   - Gesture input, keyboard display

5. **Instrumentation Tests** (AndroidX Test)
   - On-device testing
   - IME lifecycle, system integration
   - Real keyboard input/output

6. **ADB Intent Tests** (Custom Framework)
   - Deep link testing
   - External app integration
   - System intent handling

7. **Performance Tests** (Macrobenchmark)
   - Startup time, memory usage
   - Swipe recognition latency
   - Prediction generation speed

---

## Test Coverage Matrix

### Core Keyboard System

#### Unit Tests (src/test/)
- [ ] `KeyboardLayoutLoaderTest.kt`
  - Layout XML parsing (qwerty, azerty, dvorak)
  - Key positioning calculations
  - Extra keys configuration
  - Layout validation edge cases

- [ ] `ConfigTest.kt`
  - Preference loading/saving
  - Configuration merging
  - Default value handling
  - Migration logic (v1 → v2)

- [ ] `KeyEventHandlerTest.kt`
  - Key press event routing
  - Modifier key combinations
  - Compose sequence handling
  - Special key actions (Ctrl, Alt, Meta)

- [ ] `ComposeKeyTest.kt` ✅ (Partially Complete)
  - Compose sequence validation
  - Multi-character outputs
  - Dead key handling
  - Edge cases (collisions, invalid sequences)

- [ ] `ModmapTest.kt`
  - Modifier mapping logic
  - Key transformation
  - State management

### Neural Prediction System

#### Unit Tests
- [ ] `OnnxSwipePredictorTest.kt`
  - Model loading/initialization
  - Input preprocessing
  - Output postprocessing
  - Prediction caching

- [ ] `PredictionCacheTest.kt` ✅ (Needs Expansion)
  - Cache hit/miss logic
  - LRU eviction
  - Similarity calculation
  - Thread safety validation

- [ ] `TypingPredictionEngineTest.kt`
  - N-gram prediction accuracy
  - Word frequency ranking
  - Autocorrection suggestions
  - User adaptation integration

- [ ] `AutoCorrectionEngineTest.kt`
  - Levenshtein distance calculations
  - Keyboard adjacency costs
  - Confidence scoring
  - Typo detection accuracy

- [ ] `UserAdaptationManagerTest.kt`
  - Word frequency tracking
  - Adaptation multipliers
  - Persistence/loading
  - Pruning logic

- [ ] `BigramModelTest.kt`
  - Bigram probability calculations
  - Context-aware predictions
  - Missing bigram handling
  - Model serialization

- [ ] `LanguageDetectorTest.kt`
  - Character frequency analysis
  - Common word detection
  - Multi-language text
  - Confidence thresholds

### UI Components

#### UI Tests (androidTest/)
- [ ] `Keyboard2ViewTest.kt`
  - Key rendering
  - Touch event handling
  - Gesture tracking
  - Theme application

- [ ] `SuggestionBarTest.kt`
  - Suggestion display
  - Tap selection
  - Scroll behavior
  - Empty state handling

- [ ] `EmojiGridViewTest.kt`
  - Emoji rendering (100+ emoji)
  - Category filtering
  - Recent emoji tracking
  - Search functionality

- [ ] `EmojiGroupButtonsBarTest.kt`
  - Category button navigation
  - Selected state indication
  - Icon display

- [ ] `ClipboardHistoryViewTest.kt`
  - Clipboard item display
  - Pin/unpin actions
  - Delete functionality
  - Empty state

- [ ] `ClipboardPinViewTest.kt`
  - Pinned item persistence
  - Paste action
  - Delete confirmation

### Settings & Preferences

#### Android Unit Tests (Robolectric)
- [ ] `SettingsActivityTest.kt`
  - Preference screen navigation
  - Setting value persistence
  - Export/import settings
  - Reset to defaults

- [ ] `ExtraKeysPreferenceTest.kt`
  - Key description localization (21 languages)
  - Custom key configuration
  - Validation logic

- [ ] `IntSlideBarPreferenceTest.kt`
  - Value adjustment
  - Min/max constraints
  - Display formatting
  - Density-independent rendering

- [ ] `SlideBarPreferenceTest.kt`
  - Float value handling
  - Range validation
  - Text display

- [ ] `LayoutsPreferenceTest.kt`
  - Layout selection
  - Custom layout editing
  - Layout validation
  - Serialization/deserialization

### Integration Tests

#### Component Integration
- [ ] `KeyboardInputFlowTest.kt`
  - Key press → EventHandler → InputConnection
  - Compose sequence → character output
  - Modifier application → transformed character

- [ ] `SwipePredictionFlowTest.kt`
  - Swipe gesture → coordinate capture
  - Coordinates → ONNX model → predictions
  - Prediction cache integration
  - Suggestion bar display

- [ ] `TapTypingFlowTest.kt`
  - Character tap → prediction generation
  - Autocorrection application
  - User adaptation tracking
  - Word completion

- [ ] `ClipboardIntegrationTest.kt`
  - System clipboard → history storage
  - Pin persistence across sessions
  - Paste → InputConnection output

- [ ] `ThemeApplicationTest.kt`
  - Theme selection → Config update
  - Theme reload → View re-rendering
  - Custom theme creation
  - System dark mode switching

### IME Lifecycle Tests

#### Instrumentation Tests
- [ ] `IMELifecycleTest.kt`
  - onCreate → onCreateInputView → onStartInput flow
  - Input field switching (text → email → url)
  - Keyboard show/hide transitions
  - Configuration changes (rotation, split-screen)
  - Memory cleanup on destroy

- [ ] `InputConnectionTest.kt`
  - Text insertion (ASCII, Unicode, emoji)
  - Text deletion (backspace, delete key)
  - Selection manipulation (cursor movement)
  - Batch edits performance

- [ ] `EditorInfoTest.kt`
  - Input type detection (text, number, email, password)
  - Action button handling (Done, Go, Search, Next)
  - IME options parsing
  - Package name extraction

### ADB Intent Tests

#### Custom Test Framework
- [ ] `DeepLinkTest.kt`
  ```bash
  adb shell am start -a android.intent.action.VIEW \
    -d "cleverkeys://settings/theme"
  ```
  - Theme deep link
  - Layout deep link
  - Settings navigation
  - Invalid URL handling

- [ ] `ExternalAppIntegrationTest.kt`
  ```bash
  # Test keyboard switching
  adb shell ime enable tribixbite.cleverkeys/.CleverKeysService
  adb shell ime set tribixbite.cleverkeys/.CleverKeysService

  # Test input in various apps
  adb shell input text "Test input"
  adb shell input keyevent KEYCODE_ENTER
  ```
  - Keyboard activation
  - Text input to Chrome
  - Text input to Messages
  - Text input to Notes apps

- [ ] `SystemIntentTest.kt`
  ```bash
  # Test voice input trigger
  adb shell am start -a android.speech.action.RECOGNIZE_SPEECH

  # Test settings intent
  adb shell am start -a android.settings.INPUT_METHOD_SETTINGS
  ```
  - Voice input fallback
  - Settings screen launch
  - Language picker intent

- [ ] `ContentUriTest.kt`
  ```bash
  # Test file sharing
  adb shell am start -a android.intent.action.SEND \
    -t "text/plain" \
    --es android.intent.extra.TEXT "Shared text"
  ```
  - Share to clipboard
  - Layout import via URI
  - Theme import via URI

### Performance Tests

#### Macrobenchmark Suite
- [ ] `StartupBenchmark.kt`
  - Cold start time (onCreate → first render)
  - Warm start time (killed → restart)
  - Hot start time (backgrounded → foregrounded)
  - Target: <200ms cold start

- [ ] `SwipeLatencyBenchmark.kt`
  - Swipe gesture → first prediction
  - Swipe gesture → suggestion bar update
  - Target: <100ms end-to-end

- [ ] `MemoryBenchmark.kt`
  - Baseline memory usage
  - Memory after 1000 swipes
  - Memory after 1000 clipboard items
  - Target: <50MB baseline, no leaks

- [ ] `RenderingBenchmark.kt`
  - Key press → visual feedback
  - Theme switch → full re-render
  - Target: 60fps (16ms frame time)

### Compatibility Tests

#### Device Matrix Tests
- [ ] `AndroidVersionTest.kt`
  - Android 8.0 (API 26) - minimum
  - Android 10.0 (API 29) - scoped storage
  - Android 11.0 (API 30) - gesture navigation
  - Android 14.0 (API 34) - predictive back
  - Android 15.0 (API 35) - latest

- [ ] `ScreenSizeTest.kt`
  - Phone portrait (360x640dp)
  - Phone landscape (640x360dp)
  - Tablet portrait (600x960dp)
  - Tablet landscape (960x600dp)
  - Foldable (various form factors)

- [ ] `LocaleTest.kt` (21 Languages)
  - English, Spanish, French, German
  - Russian, Japanese, Chinese (Simplified/Traditional)
  - Arabic, Hebrew (RTL support)
  - Korean, Hindi, Portuguese, Italian
  - Dutch, Polish, Turkish, Vietnamese
  - Thai, Indonesian, Ukrainian, Czech

### Edge Cases & Error Handling

#### Error Scenario Tests
- [ ] `NetworkErrorTest.kt`
  - Offline model download retry
  - Network unavailable graceful degradation

- [ ] `StorageErrorTest.kt`
  - Disk full → cache eviction
  - Permissions denied → fallback behavior
  - Corrupted preferences → reset to defaults

- [ ] `InputValidationTest.kt`
  - Invalid layout XML → error message
  - Malformed theme JSON → fallback theme
  - Invalid ONNX model → disable neural predictions

- [ ] `ConcurrencyTest.kt`
  - Simultaneous swipe + tap input
  - Parallel prediction requests
  - Thread-safe clipboard access
  - Race condition detection

- [ ] `MemoryPressureTest.kt`
  - Low memory → cache clearing
  - Model unloading under pressure
  - Graceful degradation

---

## Implementation Plan

### Phase 1: Unit Test Foundation (2-3 weeks)
**Duration**: 2-3 weeks
**Deliverables**:
- [ ] Unit test framework setup (JUnit 5, MockK, Robolectric)
- [ ] Core keyboard logic tests (Config, KeyEventHandler, ComposeKey)
- [ ] Neural prediction unit tests (Cache, TypingEngine, AutoCorrection)
- [ ] Utility and helper tests (Extensions, Utils)
- [ ] Target: 70%+ code coverage for pure logic

### Phase 2: Integration Tests (1-2 weeks)
**Duration**: 1-2 weeks
**Deliverables**:
- [ ] Component integration tests (5 flows)
- [ ] Service integration tests (ClipboardService, SpellChecker)
- [ ] Repository + DAO tests
- [ ] End-to-end typing flow validation

### Phase 3: UI Tests (2-3 weeks)
**Duration**: 2-3 weeks
**Deliverables**:
- [ ] Espresso test setup
- [ ] Keyboard2View rendering tests
- [ ] Suggestion bar interaction tests
- [ ] Emoji grid navigation tests
- [ ] Clipboard UI tests
- [ ] Settings screen tests

### Phase 4: Instrumentation & IME Tests (1-2 weeks)
**Duration**: 1-2 weeks
**Deliverables**:
- [ ] IME lifecycle tests
- [ ] InputConnection integration tests
- [ ] EditorInfo handling tests
- [ ] Real device validation

### Phase 5: ADB & System Integration (1 week)
**Duration**: 1 week
**Deliverables**:
- [ ] ADB intent test framework
- [ ] Deep link tests
- [ ] External app integration tests
- [ ] System intent tests
- [ ] Automated ADB test runner

### Phase 6: Performance & Compatibility (1-2 weeks)
**Duration**: 1-2 weeks
**Deliverables**:
- [ ] Macrobenchmark setup
- [ ] Startup, latency, memory benchmarks
- [ ] Device matrix testing (5 Android versions)
- [ ] Screen size compatibility tests
- [ ] Locale testing (21 languages)

### Phase 7: CI/CD Integration (1 week)
**Duration**: 1 week
**Deliverables**:
- [ ] GitHub Actions workflow
- [ ] Automated test runs on PR
- [ ] Coverage reporting
- [ ] Performance regression detection
- [ ] Device farm integration (Firebase Test Lab)

---

## Testing Tools & Dependencies

### Test Dependencies
```gradle
dependencies {
    // Unit Testing
    testImplementation 'junit:junit:5.9.3'
    testImplementation 'io.mockk:mockk:1.13.5'
    testImplementation 'org.robolectric:robolectric:4.10.3'
    testImplementation 'androidx.test:core:1.5.0'
    testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3'

    // Android Testing
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
    androidTestImplementation 'androidx.test.espresso:espresso-intents:3.5.1'
    androidTestImplementation 'androidx.test.uiautomator:uiautomator:2.2.0'
    androidTestImplementation 'androidx.test:runner:1.5.2'
    androidTestImplementation 'androidx.test:rules:1.5.0'

    // Performance Testing
    androidTestImplementation 'androidx.benchmark:benchmark-macro-junit4:1.2.0'

    // Assertion Libraries
    testImplementation 'com.google.truth:truth:1.1.5'
    androidTestImplementation 'com.google.truth:truth:1.1.5'
}
```

### Test Infrastructure
- **GitHub Actions**: Automated CI/CD
- **Firebase Test Lab**: Real device testing
- **Gradle Test Runner**: Local execution
- **ADB Scripts**: Intent testing automation
- **Coverage Tools**: JaCoCo, Codecov

---

## Success Metrics

### Coverage Targets
- **Unit Tests**: 80%+ line coverage
- **Integration Tests**: All critical flows covered
- **UI Tests**: All user-facing screens covered
- **Performance**: All benchmarks passing
- **Compatibility**: 5 Android versions, 21 locales

### Quality Gates
- All tests must pass before merging PR
- No performance regressions >10%
- No memory leaks detected
- Code coverage must not decrease

### Acceptance Criteria
- ✅ 200+ unit tests passing
- ✅ 50+ integration tests passing
- ✅ 30+ UI tests passing
- ✅ 10+ instrumentation tests passing
- ✅ 15+ ADB intent tests passing
- ✅ 5+ performance benchmarks passing
- ✅ CI/CD pipeline green on all platforms

---

## Current Status

### Completed Tests
- ✅ `ComposeKeyTest.kt` (3 test methods, 14 assertions)
- ✅ `PredictionCache.kt` thread-safety (synchronized blocks)

### In Progress
- [ ] Test framework setup
- [ ] Test data fixtures
- [ ] Mock object definitions

### Blocked/Dependencies
- Need ONNX model sample data for prediction tests
- Need real device access for IME lifecycle tests
- Need Firebase Test Lab setup for compatibility matrix

---

## Open Questions

1. **Mock vs Real Data**: Should prediction tests use real ONNX models or mocked outputs?
2. **Test Data Management**: How to version control test dictionaries and sample data?
3. **Performance Baselines**: What are acceptable performance targets for each benchmark?
4. **Device Coverage**: Which specific devices should be in the test matrix?
5. **Locale Testing**: Full UI testing for all 21 languages or subset?

---

## Future Enhancements

- **Screenshot Testing**: Visual regression testing (Paparazzi, Shot)
- **Accessibility Testing**: TalkBack, large font, high contrast validation
- **Stress Testing**: 10,000+ key presses, 1,000+ clipboard items
- **Security Testing**: Input sanitization, XSS prevention, SQL injection
- **Fuzzing**: Random input generation for crash discovery

---

**Created**: 2025-11-13
**Last Updated**: 2025-11-13
**Owner**: CleverKeys Development Team
**Reviewers**: QA, Architecture Team

---

## Test Suite Todo List

### Priority 0: Foundation (Week 1)
- [ ] Setup JUnit 5 + MockK configuration
- [ ] Setup Robolectric for Android unit tests
- [ ] Create test fixtures and mock data classes
- [ ] Configure test source sets (test/, androidTest/)
- [ ] Setup CI/CD test pipeline (GitHub Actions)

### Priority 1: Core Logic Tests (Weeks 1-2)
- [ ] ConfigTest.kt - 15 test methods
- [ ] KeyEventHandlerTest.kt - 20 test methods
- [ ] ComposeKeyTest.kt - Expand to 10 methods
- [ ] ModmapTest.kt - 8 test methods
- [ ] KeyboardLayoutLoaderTest.kt - 12 test methods

### Priority 2: Neural Prediction Tests (Weeks 2-3)
- [ ] TypingPredictionEngineTest.kt - 25 test methods
- [ ] AutoCorrectionEngineTest.kt - 15 test methods
- [ ] UserAdaptationManagerTest.kt - 12 test methods
- [ ] BigramModelTest.kt - 18 test methods
- [ ] LanguageDetectorTest.kt - 10 test methods
- [ ] OnnxSwipePredictorTest.kt - 20 test methods
- [ ] PredictionCacheTest.kt - Expand to 15 methods

### Priority 3: UI Component Tests (Weeks 3-4)
- [ ] Keyboard2ViewTest.kt - 15 Espresso tests
- [ ] SuggestionBarTest.kt - 10 Espresso tests
- [ ] EmojiGridViewTest.kt - 12 Espresso tests
- [ ] ClipboardHistoryViewTest.kt - 10 Espresso tests
- [ ] SettingsActivityTest.kt - 20 Robolectric tests

### Priority 4: Integration Tests (Week 5)
- [ ] KeyboardInputFlowTest.kt - 8 scenarios
- [ ] SwipePredictionFlowTest.kt - 6 scenarios
- [ ] TapTypingFlowTest.kt - 6 scenarios
- [ ] ClipboardIntegrationTest.kt - 8 scenarios
- [ ] ThemeApplicationTest.kt - 5 scenarios

### Priority 5: Instrumentation Tests (Week 6)
- [ ] IMELifecycleTest.kt - 10 test scenarios
- [ ] InputConnectionTest.kt - 15 test scenarios
- [ ] EditorInfoTest.kt - 8 test scenarios

### Priority 6: ADB & Performance (Week 7)
- [ ] ADB test framework creation
- [ ] DeepLinkTest - 6 intent tests
- [ ] ExternalAppIntegrationTest - 10 intent tests
- [ ] StartupBenchmark - 3 scenarios
- [ ] SwipeLatencyBenchmark - 5 scenarios
- [ ] MemoryBenchmark - 4 scenarios

### Priority 7: Compatibility Matrix (Week 8)
- [ ] AndroidVersionTest - 5 API levels
- [ ] ScreenSizeTest - 5 configurations
- [ ] LocaleTest - 21 languages
- [ ] Device matrix testing (Firebase Test Lab)
