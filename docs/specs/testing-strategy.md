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

## Current Test Suite (as of 2026-03-15)

| Type | Location | Count | Framework | Runner |
|------|----------|-------|-----------|--------|
| Pure JVM | `src/test/kotlin/` | 987 | JUnit4 + Truth | `./gradlew runPureTests` |
| MockK | `src/test/kotlin/` | ~176 | JUnit4 + MockK | `./gradlew runMockTests` |
| Instrumented | `src/androidTest/kotlin/` | 887 | AndroidJUnit4 | emulator.wtf (Pixel7 API 34) |

### ARM64 Termux Compatibility
Standard `testDebugUnitTest` is disabled — custom `runPureTests` JavaExec task runs
pure JVM tests directly. `runMockTests` adds MockK + android.jar to classpath.
Single-class run: `./gradlew runPureTests -PtestClass=ClassName`

### emulator.wtf (ew-cli) Configuration
```bash
ew-cli \
  --app build/outputs/apk/debug/CleverKeys-v1.2.9-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --device model=Pixel7,version=34 \
  --use-orchestrator --clear-package-data \
  --timeout 15m
```
**Note**: timeout needs unit suffix (`10m` not `600`). APKs must be x86_64 for emulator.

---

## Full App Simulation — Typing Pipeline Tests (Espresso Plan)

### Motivation
The 5 bugs discovered in 2026-02-24 (contractions, toggle UI, custom words, perf)
all lived at **composition boundaries** — places where multiple components interact
in ways that unit tests miss. Specifically:
- SuggestionHandler calls ContractionManager.getNonPairedMapping() but not getPairedContractions()
- WordPredictor.autoCorrect() checks dictionary.containsKey() but dictionary was polluted by contraction aliases
- MainDictionarySource.toggleWord() updates SharedPreferences but not cached DictionaryWord objects
- WordPredictor.isWordDisabled() checks disabledWords but not customAndUserWords

### Architecture: Pipeline-Level Testing

```
                                    ┌─────────────────────────────┐
  User types "im" ────────────────▶ │ TypingSimulationTest.kt     │
                                    │                             │
                                    │ 1. ContractionManager       │
                                    │    .getNonPairedMapping()   │
                                    │    .getPairedContractions() │
                                    │                             │
                                    │ 2. WordPredictor             │
                                    │    .predictWordsWithContext()│
                                    │    .autoCorrect()           │
                                    │                             │
                                    │ 3. DictionaryDataSource      │
                                    │    .toggleWord()            │
                                    │    .getAllWords() (cache)    │
                                    └─────────────────────────────┘
                                                │
  Validates: "I'm" ◀───────────────────────────┘
```

**Key insight**: We test the PRODUCTION components with REAL data (full dictionary,
real contraction files, real SharedPreferences) — not mocks. This catches the
composition bugs that mocks hide.

### Test Categories in TypingSimulationTest.kt

| Category | Count | What It Tests |
|----------|-------|---------------|
| Paired contraction lookup | 6 | its→it's, well→we'll, case insensitivity |
| Non-paired contraction mapping | 4 | dont→don't, cant→can't, im→i'm, wont→won't |
| Autocorrect expansion | 10 | Contraction autocorrect, I-capitalization, case preservation |
| Autocorrect regression guards | 3 | "well"/"were"/"ill" should NOT autocorrect |
| Dictionary toggle coherence | 2 | Toggle updates cached list without reload |
| Custom word override | 2 | Custom word overrides disabled word |
| Tap-typing predictions | 3 | Prefix completion, multiple results |
| I-contraction capitalization | 3 | im→I'm, ill preserved, id documented |
| End-to-end scenarios | 3 | Full sentence typing, contraction-heavy, case |
| Pipeline integration | 3 | Scores descending, words=scores length, empty input |

### Why NOT Full Espresso UI Testing

InputMethodService runs in a separate process — Espresso can't instrument it directly.
Options considered:
1. **Test Activity with EditText + IME simulation** — complex, fragile, tests Android plumbing not our code
2. **UiAutomator keyboard interaction** — slow, brittle, device-dependent
3. **Pipeline-level testing (chosen)** — tests all production code paths with real data, fast, reliable

The pipeline approach gives us 95% of the coverage at 5% of the complexity. The remaining
5% (view rendering, touch coordinates, IME lifecycle) stays in manual QA.

### Future Expansion

1. **SuggestionHandler pipeline test** — requires mocking PredictionCoordinator
   (SuggestionHandler instantiation needs keyboard context). Could test the full
   contraction injection + merge + capitalization chain.
2. **Multi-language scenarios** — bilingual typing with secondary dictionary
3. **Adaptation learning** — verify UserAdaptationManager boosts recently used words
4. **Performance benchmarks** — dictionary load time, prediction latency, cache hit rates

---

## Metrics

### Coverage (2026-03-15)
| Type | Count | Execution Time |
|------|-------|---------------|
| Pure JVM | 987 | ~10s |
| MockK | ~176 | ~12s |
| Instrumented | 887 | ~15min (emulator.wtf with orchestrator) |
| **Total** | **~2,050** | — |

### New Test Classes (v1.3.0+)

| Class | Tests | Type | Purpose |
|-------|-------|------|---------|
| `ContractionFlickerTest` | 20 | Instrumented | Paired contraction pipeline, prefix guard validation, flag mechanism |
| `ContractionFlickerIntegrationTest` | 7 | Instrumented | Real SuggestionHandler + SuggestionBar + WordPredictor wired together |
| `SwipeLayoutSupportTest` | 66 | JVM | Swipe layout configuration validation |
| `BackspaceUndoTest` | 32 | JVM | Pipeline symmetry source scanning, backspace undo state |
| `TypingSimulationTest` | 62 | Instrumented | End-to-end typing with real dictionary + contractions |
| `DictionaryDataSourceTest` | 19 | Instrumented | Dictionary cache coherence, toggle word behavior |
| `VocabularyRankingTest` | 12 | Instrumented | Contraction scoring, trie lookup |
| `SuggestionBarAutofillTest` | 15 | Instrumented | Autofill padding, password mode |

### Dual Pipeline Test Coverage

The contraction flicker tests validate **pipeline symmetry** — both SuggestionHandler
(typing path) and InputCoordinator (cursor sync path) must produce identical results:

- **Paired contraction injection**: Both paths inject `it's` for `its`, `we'll` for `well`
- **Prefix guard**: Both paths skip paired injection for prefixes < 3 chars
- **exact_add support**: Both paths produce `exact_add:` entries for non-dictionary words
- **SuggestionBar deduplication**: Identical suggestion lists don't trigger re-render
- **Context clearing**: `onFinishInputView()` calls `clearAll()` to prevent cross-app leaking

---

*Updated: 2026-03-15*
*Original: 2026-01-18 (Gemini 3 Pro consultation)*
