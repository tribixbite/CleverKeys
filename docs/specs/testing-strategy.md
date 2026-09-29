# Testing Strategy Specification

## Overview

Comprehensive testing strategy for CleverKeys Android keyboard, designed to enable testing without ADB/emulator dependencies.

## Current State (2026-09-27)

**Measured, not estimated** — the two on-device suites were re-run for this line:

| Suite | Count | How it was obtained |
|---|---|---|
| Pure JVM | **2610** green | `scripts/gradle-guard.sh lintDebug runPureTests runMockTests`, 2026-09-27, pure suite 125 s |
| MockK | **859** green | same guarded run, 2026-09-27, mock suite 156 s |
| Instrumented | 1395 / 0 failures | last full ew-cli sweep, 2026-08-18 — NOT re-run for this update (needs the device + `EW_API_TOKEN`), so treat it as a floor, not a current count |

Every other count in this document is a DATED SNAPSHOT and is labelled as such. Where a
snapshot and this section disagree, this section wins.

> A "5 Robolectric unit / 6 instrumented" table stood here from the original 2026-01-18
> draft until 2026-08-21; it described the pre-`runPureTests` era and contradicted the
> doc's own later inventory. Removed rather than updated — the handful of original files
> are listed below for orientation only.

### Original (2026-01) Test Files — still present
- `swipe/SwipeEngineRouterTest.kt` - engine routing table
- `IntegrationTest.kt` - Robolectric integration tests (SwipeInput structure,
  gesture/circular-gesture creation; skipped on ARM64 — Robolectric needs x86_64)
- `ComposeKeyTest.kt` - Compose key sequences
- `OnnxPredictionTest.kt.local` - ONNX prediction basics (renamed `.kt.local` to exclude
  it from CI compilation — commit `afbd2bed`; not part of any suite)
- `MockClasses.kt` - Mock implementations

## Translation verification (2026-09-27, extended 2026-09-29)

All 21 locales are machine-translated, and none has had a native-speaker review. The
automated gates below catch structural, terminology and length defects. They cannot certify
fluency.

### Automated (pure JVM, in `runPureTests`)

- **`TranslationCoverageDriftTest`** checks structure. It pins which resources are
  `<string>` vs `<plurals>` and requires every pinned key in every locale. It also checks
  per-item plural placeholders, indexed format arguments, and guards against English
  copy-paste (at least 12 of 21 locales must differ from English). The 2026-09-27 audit
  confirmed all 21 files parse. Each has all 936 translatable resources, and the 19
  default-only ones are `translatable="false"`. Literal percentages in
  `formatted="false"` strings and Hungarian `%-a` are not formatter arguments. Android lint
  remains the formatting-syntax gate.
- **`TranslationGlossaryTest`** checks terminology. `docs/i18n/glossary.json` maps each
  product concept (swipe, dictionary, suggestion, learn, clipboard, layout, gesture;
  correction for zh/vi; "word" for tr) to the keys that express it. For each locale it lists
  allowed and forbidden term stems. Every keyed string must contain an allowed stem and no
  forbidden one. Matching is NFC-normalized, ROOT-lowercased, drops U+0307, and is
  word-initial unless the locale sets `matchAnywhere` (compounding languages, CJK). The
  checks are key-scoped, so Czech *tažením* meaning "drag" in an unrelated string is not
  flagged. The test also requires the glossary to cover exactly the `res/values-*` locales,
  and it pins the matcher with unit cases. Fail-first: 66 violations before the 2026-09-29
  fixes, 0 after. The choices and counts are in `docs/i18n/2026-09-29-terminology.md`.
  When adding a locale or concept, add its glossary entry with a `note` saying why.
- **`TranslationLengthTest`** is an overflow heuristic. It flags a translation longer than
  **1.75×** English for suggestion-bar copy (`suggestion_*`; a 2-line chip in a horizontal
  strip), or longer than **2.0×** English for `*_title` and `provenance_origin_*`. In both
  cases the translation must also exceed **40** code points. Format arguments count as a
  4-character word. It counts code points, not glyph width, so CJK expansion is invisible
  to it. A justified exception goes in its `accepted` map with a reason. On 2026-09-29 it
  found 8 offenders, all shortened: hu ×4 (2 titles, 2 bar offers), ru and es titles, uk and tr bar offers.

### Model-assisted (repeatable, not automated)

- **Blind back-translation.** Give a separate model only the target-language strings,
  under neutral IDs with no English and no key names. Ask for a literal English rendering
  plus notes, then diff it against the English source. Focus on privacy and destructive
  actions: recording vs reading, what gets deleted, what remains, reversibility. The first
  run is recorded in `docs/i18n/2026-09-29-back-translation-review.md` (reviewer = model).
  It found that the learning-off prompt's bare "selection history" read as text selection
  in about 15 locales. That was fixed in all 22, including English.
- **Terminology dominance.** Count the candidate terms per concept across each
  `strings.xml`. When usage is split, use AOSP LatinIME's translation (Apache-2.0; LineageOS
  mirror, since android.googlesource.com and the old aosp-mirror were unavailable).

### Pseudolocales (visual long-text and RTL checks)

The debug build type sets `pseudoLocalesEnabled true`; release does not. Install a debug
build on a dedicated test phone or emulator, never the Termux host. Then switch the
device language to **English (XA)** (accented, about 30% longer, bracketed) or **Arabic
(XB)** (mirrored RTL). Both appear after enabling Developer options. From adb:
`adb shell am start -a android.settings.LOCALE_SETTINGS`. On API 33+ you can instead set
the per-app language: `adb shell cmd locale set-app-locales tribixbite.cleverkeys.debug
--locales en-XA`, then reset with `--locales ""`. Restore the device language afterwards
(leave no trace). Resize screenshots below 2000 px in both dimensions and 4 MB before
processing. Check the suggestion-bar offers, the privacy dialogs, settings rows and the
Persian (real RTL) locale.

### Still requires native speakers

- Semantic and fluency review of every locale, starting with privacy and deletion copy. Each
  back-translation NOTE in the review doc is an open question.
- The established splits listed in `docs/i18n/2026-09-29-terminology.md`: es/nl/uk/fa layout,
  vi clipboard, tr kelime/sözcük, fil mungkahi/suhestiyon, and whether it *scorrimento*,
  tr *kaydırma*, fa *کشیدن*, lv *vilkšana* and pt *deslize* read as swipe typing.
- Device checks on a dedicated test phone or emulator, never the host phone: long text with
  enlarged fonts, TalkBack, and Persian RTL.

Record the reviewer's language competence and any unresolved wording. Never label machine
output as native-reviewed.

## Architecture: Humble Object Pattern

> **Status note (2026-08-21)**: everything from here through "Implementation Phases" is the
> original 2026-01-18 proposal, kept for rationale. The `:core` Gradle module was never
> created, and the neural-era classes the proposal names — `BeamSearchEngine`,
> `VocabularyTrie`, `PrefixBoostTrie` — were **deleted 2026-08-18 with the neural engine**
> (ADR-011). The pure-JVM goal was reached by a different route: the CTC decoder core
> (`swipe/ctc/` — `CtcBeamDecoder`, `CtcLexiconTrie`, `CtcCkdtLexicon`, all pure JVM) and
> the geometric engine (`swipe/geometric/`, pure JVM with a purity drift test), both run
> in-package via `runPureTests`. Current reality is the "Current Test Suite" section below.

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
│   ├── SwipeDecoder.kt      → Interface
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

### SwipeDecoder Interface
```kotlin
interface SwipeDecoder {
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
| VocabularyTrie *(deleted 2026-08-18; now `CtcLexiconTrie`)* | Insert, lookup, prefix search | None |
| BeamSearchEngine *(deleted 2026-08-18; now `CtcBeamDecoder`)* | Decoding, pruning, scoring | None |
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
| PrefixBoostTrie *(deleted 2026-08-18 with the neural engine; no replacement — the CTC/geometric engines use no prefix-boost tries)* | Aho-Corasick traversal | None |

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
// (Historical example — VocabularyTrie and its VocabularyTrieTest were deleted 2026-08-18
// with the neural engine. The equivalent live coverage is the pure swipe/ctc suite:
// CtcModuleTest exercises CtcLexiconTrie build + lookup, CtcParityTest pins decode.)

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
- [ ] Create SwipeDecoder interface
- [ ] Create TextCommitter interface
- [x] ~~Refactor BeamSearchEngine to use abstractions~~ (obsolete — `BeamSearchEngine` was deleted 2026-08-18 with the neural engine; its successor `CtcBeamDecoder` was born pure JVM in `swipe/ctc/`)

### Phase 3: Core Module (Week 4+)
- [ ] Create `:core` Gradle module
- [ ] Move testable code to `:core`
- [ ] Replace android.* imports with abstractions
- [ ] Achieve 80% coverage on `:core`

## Current Test Suite

> Counts re-measured 2026-09-01. The 2026-03-15 column is kept beside them because the
> growth rate is the useful signal; do not quote the old column on its own.

| Type | Location | Count (2026-09-01) | was 2026-03-15 | Framework | Runner |
|------|----------|-------|-------|-----------|--------|
| Pure JVM | `src/test/kotlin/` | **2093** | 987 | JUnit4 + Truth | `scripts/gradle-guard.sh runPureTests` |
| MockK | `src/test/kotlin/` | **343** | ~176 | JUnit4 + MockK | `scripts/gradle-guard.sh runMockTests` |
| Instrumented | `src/androidTest/kotlin/` | 1395 (2026-08-18 sweep) | 887 | AndroidJUnit4 | emulator.wtf (Pixel7 API 34) |

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

### Coverage (2026-09-01, measured on this device)
| Type | Count | Execution Time |
|------|-------|---------------|
| Pure JVM | 2093 | 81 s |
| MockK | 343 | 56 s |
| Instrumented | 1395 (2026-08-18 sweep, not re-run) | ~31 min (emulator.wtf with orchestrator; use `--timeout 40m`) |
| **Total** | **~3,831** | — |

### New Test Classes (v1.3.0+)

| Class | Tests | Type | Purpose |
|-------|-------|------|---------|
| `ContractionFlickerTest` | 20 | Instrumented | Paired contraction pipeline, prefix guard validation, flag mechanism |
| `ContractionFlickerIntegrationTest` | 7 | Instrumented | Real SuggestionHandler + SuggestionBar + WordPredictor wired together |
| ~~`SwipeLayoutSupportTest`~~ | — | JVM | Deleted 2026-08-18 (`a7d03bc8` — it validated the neural allowlist, which is gone). Layout routing is now covered by `swipe/SwipeEngineRouterTest` (routing table) + `swipe/LayoutScriptDeclarationTest` (layout `script` declarations) |
| `BackspaceUndoTest` | 32 | JVM | Pipeline symmetry source scanning, backspace undo state |
| `TypingSimulationTest` | 62 | Instrumented | End-to-end typing with real dictionary + contractions |
| `DictionaryDataSourceTest` | 19 | Instrumented | Dictionary cache coherence, toggle word behavior |
| ~~`VocabularyRankingTest`~~ | — | Instrumented | Deleted 2026-08-18 with the neural engine (`64f401d2` — it scored through `OptimizedVocabulary`). Contraction ranking/trie coverage now lives in the pure `swipe/ctc/` suite: `CtcContractionRankingTest` (real beam decoder over real shipped assets), `CtcContractionKeysTest`, `CtcModuleTest` |
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
