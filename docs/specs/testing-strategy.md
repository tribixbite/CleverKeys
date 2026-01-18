# Testing Strategy Specification

## Overview

Comprehensive testing strategy for CleverKeys Android keyboard, designed to enable testing without ADB/emulator dependencies.

## Current State

### Existing Tests
| Type | Location | Count | Framework | Works on ARM64 |
|------|----------|-------|-----------|----------------|
| Unit | `src/test/kotlin/` | 5 | Robolectric | No (x86_64 only) |
| Instrumented | `src/androidTest/kotlin/` | 6 | AndroidJUnit4 | Requires ADB |

### Existing Test Files
- `NeuralPredictionTest.kt` - SwipeInput data structure tests
- `IntegrationTest.kt` - Gesture creation helpers
- `ComposeKeyTest.kt` - Compose key sequences
- `OnnxPredictionTest.kt` - ONNX prediction basics
- `MockClasses.kt` - Mock implementations

## Architecture: Humble Object Pattern

### Goal
Decouple Android framework from testable business logic.

### Module Structure
```
:app (Android)
├── CleverKeysService.kt  → Humble Object, delegates to core
├── Keyboard2View.kt      → View layer only
└── SettingsActivity.kt   → UI only

:core (Pure Kotlin) [NEW]
├── prediction/
│   ├── NeuralEngine.kt      → Interface
│   ├── BeamSearchEngine.kt  → Pure algorithm
│   └── VocabularyTrie.kt    → Data structure
├── dictionary/
│   ├── DictionaryLoader.kt  → Binary parser
│   └── WordLookup.kt        → Search logic
├── gesture/
│   ├── TouchPoint.kt        → data class (replaces PointF)
│   ├── GestureClassifier.kt → Tap/Swipe/Hold detection
│   └── SwipeAnalyzer.kt     → Path analysis
└── text/
    ├── TextCommitter.kt     → Interface (replaces InputConnection)
    ├── AutoCorrector.kt     → Correction logic
    └── ContractionHandler.kt→ don't → don't
```

## Abstraction Interfaces

### NeuralEngine Interface
```kotlin
interface NeuralEngine {
    fun predict(features: FloatArray): PredictionResult
    fun isReady(): Boolean
}

data class PredictionResult(
    val probabilities: Map<Char, Float>,
    val confidence: Float
)
```

### TextCommitter Interface
```kotlin
interface TextCommitter {
    fun commitText(text: CharSequence)
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int)
    fun getTextBeforeCursor(length: Int): CharSequence?
    fun getTextAfterCursor(length: Int): CharSequence?
}
```

### TouchPoint (Replaces PointF)
```kotlin
data class TouchPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long = System.currentTimeMillis()
)
```

## Testing Framework

### Recommended Stack
```groovy
// build.gradle (:core module)
testImplementation "org.junit.jupiter:junit-jupiter:5.10.0"
testImplementation "io.mockk:mockk:1.13.8"
testImplementation "com.google.truth:truth:1.1.5"
testImplementation "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3"
```

## Coverage Priorities

### P0: Critical (Must Have)
| Component | Tests | Android Deps |
|-----------|-------|--------------|
| VocabularyTrie | Insert, lookup, prefix search | None |
| BeamSearchEngine | Decoding, pruning, scoring | None |
| DictionaryLoader | V2 binary parsing | None |
| ContractionHandler | Mapping, reverse lookup | None |
| AutoCorrector | Edit distance, threshold | None |

### P1: High Priority
| Component | Tests | Android Deps |
|-----------|-------|--------------|
| GestureClassifier | Tap vs swipe vs hold | TouchPoint only |
| SwipeAnalyzer | Path smoothing, key detection | TouchPoint only |
| FeatureExtractor | Velocity, acceleration | TouchPoint only |
| Config validation | Setting ranges, defaults | None |

### P2: Medium Priority
| Component | Tests | Android Deps |
|-----------|-------|--------------|
| KeyboardState | Layer switching, modifiers | None |
| LayoutParser | XML parsing | Resources abstraction |
| LanguageDetector | Unigram scoring | None |
| PrefixBoostTrie | Aho-Corasick traversal | None |

### P3: Low Priority (Keep Instrumented)
| Component | Tests | Reason |
|-----------|-------|--------|
| View rendering | Screenshot comparison | Needs real Views |
| IME lifecycle | onStartInput, onFinishInput | Needs Android |
| Haptics | Vibration patterns | Needs hardware |

## Quick Win Tests (No Refactor Needed)

### 1. Pure Algorithm Tests
Tests that can run today with minimal changes:

```kotlin
// VocabularyTrieTest.kt
@Test
fun `trie prefix search returns all matches`() {
    val trie = VocabularyTrie()
    trie.insert("hello", 1000)
    trie.insert("help", 900)
    trie.insert("helicopter", 500)

    val matches = trie.prefixSearch("hel")

    assertThat(matches).containsExactly("hello", "help", "helicopter")
}

// ContractionTest.kt
@Test
fun `contraction mapping works for common words`() {
    val handler = ContractionHandler()
    handler.loadMappings(mapOf("dont" to "don't", "cant" to "can't"))

    assertThat(handler.expand("dont")).isEqualTo("don't")
    assertThat(handler.isContractionKey("cant")).isTrue()
}

// EditDistanceTest.kt
@Test
fun `Levenshtein distance calculated correctly`() {
    assertThat(editDistance("hello", "hallo")).isEqualTo(1)
    assertThat(editDistance("hello", "hello")).isEqualTo(0)
    assertThat(editDistance("cat", "cut")).isEqualTo(1)
}
```

### 2. Binary Parser Tests
```kotlin
// DictionaryLoaderTest.kt
@Test
fun `V2 binary format parses correctly`() {
    val bytes = createValidV2Header() + createWordEntries(listOf("test", "word"))
    val dict = DictionaryLoader.loadFromBytes(bytes)

    assertThat(dict.contains("test")).isTrue()
    assertThat(dict.getFrequency("test")).isGreaterThan(0)
}

@Test
fun `invalid magic number throws exception`() {
    val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    assertThrows<InvalidDictionaryException> {
        DictionaryLoader.loadFromBytes(bytes)
    }
}
```

## CI/CD Configuration

### GitHub Actions Workflow
```yaml
name: Tests
on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Run Unit Tests
        run: ./gradlew test --continue

  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build Debug APK
        run: ./gradlew assembleDebug

  instrumented-tests:
    runs-on: ubuntu-latest
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v4
      - uses: ReactiveCircus/android-emulator-runner@v2
        with:
          api-level: 29
          script: ./gradlew connectedAndroidTest
```

## Implementation Phases

### Phase 1: Quick Wins (Week 1)
- [ ] Add JUnit 5 + MockK + Truth to build.gradle
- [ ] Create pure algorithm tests (no refactor needed)
- [ ] Add CI workflow for unit tests

### Phase 2: Abstractions (Week 2-3)
- [ ] Create TouchPoint data class
- [ ] Create NeuralEngine interface
- [ ] Create TextCommitter interface
- [ ] Refactor BeamSearchEngine to use abstractions

### Phase 3: Core Module (Week 4+)
- [ ] Create `:core` Gradle module
- [ ] Move testable code to `:core`
- [ ] Replace android.* imports with abstractions
- [ ] Achieve 80% coverage on `:core`

## Metrics

### Coverage Targets
| Module | Target | Current |
|--------|--------|---------|
| `:core` | 80% | N/A |
| `:app` | 30% | ~5% |

### Test Execution Time
| Type | Target | Current |
|------|--------|---------|
| Unit | <30s | N/A |
| Instrumented | <5min | Unknown |

---

*Generated: 2026-01-18*
*Based on Gemini 3 Pro consultation*
