# ew-cli Instrumented Testing Skill

Use this skill when running instrumented tests on emulator.wtf cloud infrastructure.

## Prerequisites

```bash
# API key must be set (sourced from ~/.bashrc)
source ~/.bashrc
echo "EW_API_KEY: ${EW_API_KEY:0:10}..."

# Verify ew-cli is installed
which ew-cli || pip install emulatorwtf-cli
```

## Build APKs for Testing

```bash
# Build debug APK (app under test)
./build-on-termux.sh

# Build test APK
./gradlew assembleDebugAndroidTest

# APK locations
APP_APK="build/outputs/apk/debug/CleverKeys-*-x86_64.apk"
TEST_APK="build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk"
```

## Run All Tests

```bash
source ~/.bashrc
ew-cli \
  --app build/outputs/apk/debug/CleverKeys-*-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --device model=Pixel7,version=35 \
  --use-orchestrator \
  --clear-package-data
```

## Run Specific Test Class

```bash
source ~/.bashrc
ew-cli \
  --app build/outputs/apk/debug/CleverKeys-*-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --device model=Pixel7,version=35 \
  --use-orchestrator \
  --clear-package-data \
  -- -e class tribixbite.cleverkeys.AutocapitalizationTest
```

## Run Specific Test Method

```bash
source ~/.bashrc
ew-cli \
  --app build/outputs/apk/debug/CleverKeys-*-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --device model=Pixel7,version=35 \
  --use-orchestrator \
  --clear-package-data \
  -- -e class tribixbite.cleverkeys.AutocapitalizationTest#testIWordCapitalization_SingleI
```

## Run Tests by Pattern (Multiple Classes)

```bash
source ~/.bashrc
# Run all Config-related tests
ew-cli \
  --app build/outputs/apk/debug/CleverKeys-*-x86_64.apk \
  --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
  --device model=Pixel7,version=35 \
  --use-orchestrator \
  --clear-package-data \
  -- -e package tribixbite.cleverkeys
```

## Device Options

| Device | API | Notes |
|--------|-----|-------|
| `model=Pixel7,version=35` | 35 (Android 15) | Latest, fast |
| `model=Pixel7,version=34` | 34 (Android 14) | Stable |
| `model=Pixel6,version=33` | 33 (Android 13) | IS_SENSITIVE flag testing |
| `model=Pixel4,version=30` | 30 (Android 11) | Older device testing |

## Common Test Classes

| Class | Purpose |
|-------|---------|
| `AutocapitalizationTest` | Auto-caps, I-words (#72) |
| `ConfigIntegrationTest` | Settings persistence |
| `SwipePredictionTest` | Swipe typing accuracy |
| `ClipboardTest` | Clipboard history (#71) |
| `SubkeyTest` | Long-press subkeys |
| `WordPredictorTest` | Word suggestions |

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
Add `--timeout 600` for long-running tests (default 300s)

## Related Files

| File | Purpose |
|------|---------|
| `src/androidTest/kotlin/tribixbite/cleverkeys/` | Test sources |
| `build.gradle` | Test dependencies |
| `scripts/run-pure-tests.sh` | Local JVM tests |
| `.github/workflows/test.yml` | CI test workflow |
