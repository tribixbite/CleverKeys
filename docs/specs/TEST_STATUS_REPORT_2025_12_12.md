# Test Suite Status Report

**Date:** 2025-12-12
**Environment:** Android (Termux ARM64)

## 1. Summary
The current test suite is partially functional. The core neural prediction logic is verified via CLI scripts (Python/TypeScript/Kotlin). Standard Android unit tests (Gradle + Robolectric) cannot run on Termux ARM64 due to missing native library support, but standalone Kotlin tests using the ONNX Runtime CAN run if provided with a patched JAR containing Android native libraries.

## 2. Existing Tests & Status

| Test Component | Type | Location | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Feature Extraction (Kotlin)** | Unit (Script) | `tools/standalone_tests/test_decoding.kts` | ✅ **PASS** | Verifies math/logic for feature extraction. Pure Kotlin. |
| **Neural Prediction (CLI)** | Integration | `tools/test_cli_predict.py` | ✅ **PASS** | Top-3 Accuracy: 66.0%. Verifies ONNX models & logic via Python. |
| **Neural Prediction (TS)** | Integration | `tools/test_cli_predict.ts` | ✅ **PASS** | Top-3 Accuracy: 66.0%. Verifies web/JS compatibility. |
| **Standalone ONNX (Kotlin)** | Integration | `tools/standalone_tests/test_onnx_cli.kt` | ✅ **PASS** | **Requires patched JAR.** Runs successfully with `onnxruntime-1.20.0-android.jar` (Android native libs). |
| **Unit Tests (Gradle)** | Unit | `src/test/kotlin/...` | ❌ **FAIL** | Fails on ARM64 due to `UnsatisfiedLinkError` (Robolectric native deps). |
| **UI Tests** | UI | `testing/run-ui-tests.sh` | ❓ **Untested** | Requires ADB/Emulator setup. |

## 3. How to Run Standalone ONNX Test
To run `test_onnx_cli.kt` on Termux:
1.  Ensure `onnxruntime-1.20.0-android.jar` exists (created by repacking `onnxruntime-1.20.0.jar` with Android ARM64 `.so` files from the build output).
2.  Compile and run:
    ```bash
    kotlinc -cp onnxruntime-1.20.0-android.jar tools/standalone_tests/test_onnx_cli.kt -include-runtime -d test_onnx_cli.jar
    java -cp test_onnx_cli.jar:onnxruntime-1.20.0-android.jar Test_onnx_cliKt
    ```

## 4. Missing Tests (Gap Analysis)
Based on `docs/specs/test-suite.md`, the following critical areas are missing coverage:

### Core Keyboard System
- `KeyboardLayoutLoaderTest.kt`: Layout XML parsing validation.
- `ConfigTest.kt`: Preference loading/saving logic.
- `KeyEventHandlerTest.kt`: Complex key event routing and modifier handling.

### Neural Prediction (Unit Level)
- `TypingPredictionEngineTest.kt`: N-gram prediction logic (separate from ONNX).
- `AutoCorrectionEngineTest.kt`: Levenshtein distance and correction logic.
- `UserAdaptationManagerTest.kt`: Frequency tracking and learning.

### UI Components (Espresso/Robolectric)
- `Keyboard2ViewTest.kt`: Key rendering and touch handling.
- `SuggestionBarTest.kt`: Interaction with suggestions.
- `EmojiGridViewTest.kt`: Emoji rendering and selection.

## 5. Recommendations
1.  **Prioritize Core Logic Tests:** Refactor `Config`, `KeyEventHandler`, and `TypingPredictionEngine` to be pure Kotlin classes (decoupled from Android Context where possible) so they can be tested without Robolectric on ARM64.
2.  **Maintain Standalone Tests:** Keep `test_onnx_cli.kt` and `test_decoding.kts` as the primary verification methods for the neural pipeline on on-device development environments (Termux).
3.  **Automate JAR Patching:** Add a script to automatically build `onnxruntime-1.20.0-android.jar` from project dependencies to simplify testing.
