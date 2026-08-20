# ctc_bench — arch-comparison models (NOT committed)

`CtcOnnxLatencyBenchmarkTest` measures four candidate encoders from the model-selection
campaign. The models are deliberately absent: they were 11.0 MB of **byte-identical copies** of
`CleverKeys-ML/ctc/artifacts/*.onnx`, carried inside the androidTest APK that CI now builds and
uploads on every run.

**None of them is the shipped model.** The app ships `phaseM_kd_fresh_w1_s1234_fp16w`
(`models/ctc_swipe_encoder.onnx`), a ch192 distilled student. Nothing in this directory was ever
measured on the encoder users run — an earlier KDoc called `ch128_s1234` "the ship candidate",
which it was at the time and did not remain.

To run the benchmark, restore them first:

```sh
cp ~/git/swype/CleverKeys-ML/ctc/artifacts/{ch128_s1234,ch192_s1234,\
   fast_resbn80_s1234,fast_resbn72_s1234}.onnx src/androidTest/assets/ctc_bench/
sh gradlew assembleDebug assembleDebugAndroidTest
```

The test fails with these instructions rather than skipping if they are missing — a benchmark
that silently no-ops reads as coverage it does not provide.

`*.onnx` here is gitignored so a local restore cannot be committed back by accident.
