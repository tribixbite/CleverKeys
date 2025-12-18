# CleverKeys Agent & Developer Handbook

This document serves as a high-level guide for AI agents and developers working on the CleverKeys project. It synthesizes the project's infrastructure, build system, and key architectural patterns.

## 1. Project Overview
**CleverKeys** is a privacy-focused, lightweight Android virtual keyboard featuring a neural network-based swipe prediction engine (ONNX). It prioritizes local processing (no network permissions), minimal dependencies, and high performance. It was originally designed for Termux users but has evolved into a general-purpose keyboard.

## 2. Build Infrastructure

### Local Build (Termux Optimized)
The project is optimized for building directly on an Android device via Termux.
-   **Script:** `./build-on-termux.sh [debug|release]`
-   **Quirks:**
    -   **AAPT2 Override:** Uses a custom `aapt2` binary (`tools/aapt2-arm64/aapt2`) because the standard SDK version is incompatible with Termux environment. This is injected via `-Pandroid.aapt2FromMavenOverride`.
    -   **Memory:** JVM args are tuned (`-Xmx2048m`) for limited resource environments.
    -   **Layout Resources:** Keyboard layouts (`src/main/layouts/*.xml`) are processed and copied to `build/generated-resources/raw` via a custom Gradle `Copy` task (`copyLayoutDefinitions`) to ensure they are available as `raw` resources for the `LayoutManager`.

### Gradle Configuration (`build.gradle`)
-   **Single Source of Truth (SSoT):** Versioning is controlled by `ext.VERSION_MAJOR`, `MINOR`, and `PATCH` at the top of `build.gradle`. `versionCode` and `versionName` are derived from these.
-   **ABI Splits:** The build produces separate APKs for `armeabi-v7a`, `arm64-v8a`, and `x86_64` to reduce size.
    -   **Version Code Schema:** `baseVersionCode * 10 + abiCode` (1=armv7, 2=arm64, 3=x86).
-   **Signing:**
    -   **Debug:** Uses a committed `debug.keystore`.
    -   **Release:** Requires environment variables (`RELEASE_KEYSTORE`, `RELEASE_KEY_PASSWORD`, etc.) or falls back to debug signing for local testing.

### GitHub Actions CI/CD (`.github/workflows/`)
-   **Release Workflow (`release.yml`):**
    -   Triggered by tags matching `v*`.
    -   Verifies that the git tag matches the version in `build.gradle`.
    -   Builds signed release APKs.
    -   Renames APKs to `CleverKeys-vX.Y.Z-<abi>.apk`.
    -   Generates a changelog from commit messages.
    -   Creates a GitHub Release and uploads assets.

## 3. Key Architectural Patterns

### Window Management & UI
-   **Edge-to-Edge:** The keyboard window uses `WRAP_CONTENT` height (fixed in `WindowLayoutUtils.kt`) to avoid "white bar" artifacts during animation.
-   **Transparency:** A custom theme `CleverKeysIMETheme` (in `styles.xml`) enforces transparency (`windowIsTranslucent`, `windowBackground=@null`) to ensure the system background doesn't bleed through.
-   **Layout Loading:** `LayoutManager` loads keyboard layouts from raw resources. Layouts must be present in `src/main/layouts/` and are copied to the build directory during compilation.

### Neural Prediction
-   **ONNX Runtime:** Swipe prediction is handled by `com.microsoft.onnxruntime:onnxruntime-android`.
-   **Models:** Models (encoder/decoder) are loaded from assets or external storage.
-   **Privacy:** All inference happens strictly on-device.

## 4. Developer Quirks & Gotchas
-   **"White Bar" Artifact:** If the keyboard animation shows a white bar at the top, ensure `WindowLayoutUtils` sets height to `WRAP_CONTENT` and the Service theme is fully transparent.
-   **Resource Duplication:** **DO NOT** manually copy XML files to `res/raw`. The Gradle build task handles this. Manual copying causes "Duplicate resource" errors.
-   **F-Droid Compatibility:** The version code logic and split APK structure are designed to be compatible with F-Droid's build expectations.
-   **Termux Environment:** When running shell commands, always prefer `./build-on-termux.sh` over direct `./gradlew` calls to ensure the correct environment variables and AAPT2 overrides are applied.

## 5. Documentation Map
-   `docs/ARCHITECTURE_MASTER.md`: High-level system design.
-   `docs/ONNX_DECODE_PIPELINE.md`: Deep dive into the neural swipe engine.
-   `docs/VERSIONING.md`: Explanation of the versioning scheme.
-   `memory/`: Context files for AI agents.

This file should be updated when significant infrastructure changes occur.
