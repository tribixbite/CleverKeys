# CLI Test - ONNX Neural Pipeline Validator

Standalone JVM test for validating the ONNX neural prediction pipeline outside of Android.

## Requirements

- **JDK 17+** (JDK 21 recommended)
- **x86_64 Linux, macOS, or Windows** (ONNX Runtime native library is not compatible with ARM64/Android Bionic)
- Gradle 8.x+

## Running Tests

```bash
cd cli-test
gradle run
```

## Notes

- **Cannot run on Termux/ARM64**: ONNX Runtime's native library requires glibc (libdl.so.2), which is not available on Android's Bionic libc.
- **CI/CD Ready**: Designed to run in GitHub Actions workflows on ubuntu-latest
- **Model Paths**: Expects models in `assets/models/` directory:
  - `swipe_model_character_quant.onnx` (encoder)
  - `swipe_decoder_character_quant.onnx` (decoder)

## Test Coverage

1. Feature extraction (coordinates → normalized + velocities + accelerations)
2. ONNX tensor creation with correct shapes
3. Encoder inference
4. Beam search decoding
5. Word prediction validation

## Architecture

```
TestOnnxCli.kt
├── Data Structures (PointF, TrajectoryFeatures, BeamState, PredictionResult)
├── Constants (MAX_TRAJECTORY_POINTS=150, BEAM_WIDTH=8, token mappings)
├── Feature Extraction (normalize → pad → velocity/acceleration)
├── ONNX Tensor Creation (trajectory, nearest_keys, masks)
├── Model Loading (encoder + decoder sessions)
├── Neural Prediction Pipeline (encoder → beam search decoder)
└── Validation (word matching, gibberish detection, score checks)
```
