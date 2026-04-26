# ew-cli Instrumented Testing Skill

Use this skill when running instrumented tests on emulator.wtf cloud infrastructure.

## Critical Defaults — ALWAYS USE THESE

- **`--use-orchestrator`**: ALWAYS. Runs each test in its own process, preventing OOM cascades.
- **`--timeout 15m`**: ALWAYS. Full suite takes ~13 min with orchestrator. Default is too short.
- **`--device model=Pixel7,version=34`**: ALWAYS use API 34+. The APK has x86_64 native ONNX
  libraries and API 30 emulators are 32-bit x86 only — causes ABI mismatch install failure.
- **`--outputs-dir ~/ew-output`**: ALWAYS. Must exist and be under home dir (Termux restricts /tmp).
- **Debug APK for `--app`**: ALWAYS use debug APK, NOT release. Test APK uses debug signing key;
  release APK has different signature → "Permission Denial: signature mismatch" error.

## Prerequisites

```bash
# API key must be set (sourced from ~/.bashrc)
source ~/.bashrc

# Verify ew-cli is installed
which ew-cli || pip install emulatorwtf-cli
```

## Build APKs for Testing

```bash
# Build both debug + test APKs together
./gradlew assembleDebug assembleDebugAndroidTest

# APK locations (version may change)
APP_APK="build/outputs/apk/debug/CleverKeys-v1.2.9-x86_64.apk"
TEST_APK="build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk"
```

**WARNING**: `build-on-termux.sh` runs assembleRelease which cleans the debug build outputs.
If you run build-on-termux.sh after assembleDebug, you MUST rebuild the debug APK.

## Run All Tests

```bash
mkdir -p ~/ew-output
ew-cli \
  --app build/outputs/apk/debug/CleverKeys-*-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --outputs-dir ~/ew-output \
  --timeout 15m \
  --device model=Pixel7,version=34 \
  --use-orchestrator
```

## Run Specific Test Class

```bash
mkdir -p ~/ew-output
ew-cli \
  --app build/outputs/apk/debug/CleverKeys-*-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --outputs-dir ~/ew-output \
  --timeout 15m \
  --device model=Pixel7,version=34 \
  --use-orchestrator \
  --test-targets "class tribixbite.cleverkeys.SuggestionBarAutofillTest"
```

## Run Specific Test Method

```bash
mkdir -p ~/ew-output
ew-cli \
  --app build/outputs/apk/debug/CleverKeys-*-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --outputs-dir ~/ew-output \
  --timeout 15m \
  --device model=Pixel7,version=34 \
  --use-orchestrator \
  --test-targets "class tribixbite.cleverkeys.AutocapitalizationTest#testIWordCapitalization_SingleI"
```

## Run Multiple Test Classes

`--test-targets` is a SINGLE-VALUE flag — repeating it only honors the LAST occurrence. To run multiple classes in one invocation, comma-separate inside one quoted arg:

```bash
ew-cli \
  --app ... \
  --test ... \
  --test-targets "class tribixbite.cleverkeys.Issue94VersionCopyComposeTest,tribixbite.cleverkeys.Issue93ThemeHexInputComposeTest,tribixbite.cleverkeys.Issue134ShowKeyboardButtonComposeTest"
```

The first class needs the `class ` prefix; subsequent ones are bare FQCNs. Format mirrors `am instrument -e class A,B,C` semantics.

## Compose UI Tests

Tests using `androidx.compose.ui.test.junit4.createAndroidComposeRule<ActivityClass>()` work via ew-cli with no extra flags. Required deps in `build.gradle`:

```gradle
androidTestImplementation "androidx.compose.ui:ui-test-junit4"
debugImplementation "androidx.compose.ui:ui-test-manifest"
```

Activity is auto-launched per test (~1.2-1.8s overhead). Assertion failures surface clean diagnostic output naming the missing `ContentDescription`/`Text` semantics — line numbers in the stack trace point at your test source. Verified: 6/6 RED-phase Compose tests on Pixel7 API 34, 8.266s total runtime.

## Device Options

| Device | API | Notes |
|--------|-----|-------|
| `model=Pixel7,version=34` | 34 (Android 14) | **Default — stable, x86_64 support** |
| `model=Pixel7,version=35` | 35 (Android 15) | Latest |
| `model=Pixel6,version=33` | 33 (Android 13) | IS_SENSITIVE flag testing |

**Do NOT use API 30** — x86 only, ONNX native libs are x86_64.

## Common Test Classes

| Class | Tests | Purpose |
|-------|-------|---------|
| `TypingSimulationTest` | 62 | End-to-end typing, contractions, autocorrect |
| `GifTest` | 43 | GIF model, search, query matching |
| `ClipboardDatabaseTest` | 39 | Clipboard CRUD, dedup, pin/todo, export |
| `SwipeMLDataStoreTest` | 36 | Swipe data SQLite operations |
| `GifCategoryTest` | 32 | GIF category model and filtering |
| `LanguageDetectorTest` | 30 | Language detection with confidence |
| `SuggestionBarAutofillTest` | 15 | Autofill padding, password mode (#109) |
| `ContractionFlickerTest` | 20 | Pipeline symmetry, paired contraction prefix guard |
| `ContractionFlickerIntegrationTest` | 7 | Full real component wiring (SuggestionHandler+SuggestionBar+WordPredictor) |
| `DictionaryDataSourceTest` | 19 | Dictionary cache, toggle coherence |
| `VocabularyRankingTest` | 12 | Contraction scoring, trie lookup |
| `SettingsSearchTest` | 5 | Settings search-to-scroll crash regression |

## Writing New Tests

### Test File Location
```
src/androidTest/kotlin/tribixbite/cleverkeys/
```

### Test Template
```kotlin
package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyFeatureTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun testFeatureBehavior() {
        // Arrange
        val expected = "expected value"

        // Act
        val actual = myFunction()

        // Assert
        assertEquals(expected, actual)
    }
}
```

### Testing Config Settings
```kotlin
@Test
fun testConfigSetting() {
    try {
        val config = Config.globalConfig()
        if (config != null) {
            val original = config.my_setting
            try {
                config.my_setting = true
                assertTrue(config.my_setting)

                config.my_setting = false
                assertFalse(config.my_setting)
            } finally {
                config.my_setting = original
            }
        }
    } catch (e: NullPointerException) {
        // Config not available without full keyboard init
    }
}
```

## Debugging Failed Tests

### Get Detailed Output
```bash
source ~/.bashrc
ew-cli \
  --app build/outputs/apk/debug/CleverKeys-*-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --device model=Pixel7,version=35 \
  --use-orchestrator \
  --clear-package-data \
  --outputs-dir ./test-results \
  -- -e debug true
```

### View Logcat
```bash
# Results include logcat in outputs-dir
cat test-results/logcat.txt | grep -i "cleverkeys\|error\|exception"
```

## CI Integration

For GitHub Actions:
```yaml
- name: Run Instrumented Tests
  env:
    EW_API_KEY: ${{ secrets.EW_API_KEY }}
  run: |
    ew-cli \
      --app build/outputs/apk/debug/*.apk \
      --test build/outputs/apk/androidTest/debug/*.apk \
      --device model=Pixel7,version=35 \
      --use-orchestrator \
      --clear-package-data \
      --outputs-dir ./test-results
```

## Troubleshooting

### API Key Issues
```bash
# Verify key is exported
env | grep EW_API_KEY

# Re-source if needed
source ~/.bashrc
```

### APK Not Found
```bash
# Check APK locations
ls -la build/outputs/apk/debug/
ls -la build/outputs/apk/androidTest/debug/

# Rebuild if missing
./build-on-termux.sh
./gradlew assembleDebugAndroidTest
```

### Test Timeout
Always use `--timeout 15m`. Full orchestrator suite takes ~15 min (887 tests).
Without orchestrator: faster but OOM failures cascade.

### Signature Mismatch
```
Permission Denial: package tribixbite.cleverkeys.test does not have a signature matching the target
```
You're using the release APK for `--app`. Switch to the debug APK.

### ABI Mismatch
```
app apk installation failed due to ABI mismatch ... emulator supports ABIs: x86,armeabi-v7a
```
You're using API 30 which is 32-bit x86 only. Switch to `--device model=Pixel7,version=34`.

### Test APK Not Found After build-on-termux.sh
`build-on-termux.sh` runs assembleRelease which cleans debug outputs.
Rebuild: `./gradlew assembleDebug assembleDebugAndroidTest`

## Related Files

| File | Purpose |
|------|---------|
| `src/androidTest/kotlin/tribixbite/cleverkeys/` | Test sources |
| `build.gradle` | Test dependencies |
| `scripts/run-pure-tests.sh` | Local JVM tests |
| `.github/workflows/test.yml` | CI test workflow |
