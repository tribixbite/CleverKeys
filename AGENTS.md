# CleverKeys Agent & Developer Handbook

This document serves as a high-level guide for AI agents and developers working on the CleverKeys project. It synthesizes the project's infrastructure, build system, and key architectural patterns.

## 1. Project Overview
**CleverKeys** is a privacy-focused, lightweight Android virtual keyboard featuring a on-device model-based swipe prediction engine (ONNX). It prioritizes local processing (no network permissions), minimal dependencies, and high performance. It was originally designed for Termux users but has evolved into a general-purpose keyboard.

## Design Context

### Users
CleverKeys serves privacy-conscious Android users who need dependable everyday typing, with special attention to Termux and power-user workflows. Users expect the keyboard to remain useful offline, start quickly, respect accessibility services, and preserve their language and customization choices.

### Brand Personality
Private, lightweight, dependable.

### Aesthetic Direction
Preserve the existing compact, utilitarian Android keyboard and settings language. Visual changes should feel native, calm, legible, and unobtrusive in both light and dark themes; decoration must never compete with typing.

### Design Principles
- Keep core typing available across supported layouts, languages, screen sizes, and accessibility modes.
- Prefer clear state and direct feedback over decorative effects.
- Treat privacy, local-only operation, latency, and battery/memory use as user-facing design constraints.
- Make touch, TalkBack, and keyboard navigation describe and activate the same logical controls.
- Reuse established components and theme tokens instead of introducing one-off visual systems.

## 1.1 Instruction and Skill Routing

Before changing files, read `CLAUDE.md`, `memory/REPO_INSTRUCTIONS.md`, `memory/todo.md`, `docs/TABLE_OF_CONTENTS.md`, and the relevant feature spec. More-specific `AGENTS.md`/`CLAUDE.md` files override this file within their directory.

Repository skills under `.claude/skills/` are mandatory references when their topic matches:

| Work area | Required reference |
|---|---|
| Release, version, tag, changelog, Fastlane, F-Droid | `.claude/skills/release-process.md` |
| Dictionary, lexicon, vocabulary, language support | `.claude/skills/dictionary-pipeline.md` |
| Contractions, apostrophes, canonical display, collisions | `.claude/skills/contraction-system.md` |
| Settings or SharedPreferences | `.claude/skills/settings-preferences.md` |
| Instrumented tests, emulator.wtf, ew-cli | `.claude/skills/ew-cli-testing.md` |
| Wiki, Astro, public specs | `.claude/skills/wiki-documentation.md` |
| Clipboard UI/data behavior | `.claude/skills/clipboard-panel-architecture.md` and the matching tag/todo skill when applicable |

Also follow any session-provided global skill whose trigger matches. Record reusable architecture or workflow decisions in this handbook and the canonical spec instead of relying on chat history.

For continuation status, read the operational handoff in
`docs/plans/2026-10-05-recommended-features-and-gaps.md` and `memory/todo.md`.
They distinguish tested artifacts from pending device validation. PAL uses
Gemini 3.8 (`gemini-3.8-flash`) only; never substitute Gemini 3.1.

## 2. Build Infrastructure

### Local Build (Termux Optimized)
The project is optimized for building directly on an Android device via Termux.
-   **Script:** `./build-on-termux.sh [debug|release]`
-   **Quirks:**
    -   **AAPT2 Override:** Uses a custom `aapt2` binary (`tools/aapt2-arm64/aapt2`) because the standard SDK version is incompatible with Termux environment. This is injected via `-Pandroid.aapt2FromMavenOverride`.
    -   **Memory:** JVM args are tuned (`-Xmx2048m`) for limited resource environments.
    -   **Layout Resources:** Keyboard layouts (`src/main/layouts/*.xml`) are processed and copied to `build/generated-resources/raw` via a custom Gradle `Copy` task (`copyLayoutDefinitions`) to ensure they are available as `raw` resources for the `LayoutManager`.
    -   **Temporary Output:** Do not use `/tmp` on Termux. Use an ignored directory under `build/`, `context.cacheDir` in Android code, or the documented `~/ew-output` location for ew-cli.

All Gradle tasks use `scripts/gradle-guard.sh` for the device-wide singleton and
bounded memory. In-process Kotlin is the Gradle property
`-Pkotlin.compiler.execution.strategy=in-process`; `-D` did not enforce it here.
Fields used by view construction/reset must precede the Kotlin `init` block.

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

### Swipe Prediction
-   **ONNX Runtime:** Swipe prediction is handled by `com.microsoft.onnxruntime:onnxruntime-android`.
-   **Models:** Models (encoder/decoder) are loaded from assets or external storage.
-   **Privacy:** All inference happens strictly on-device.

### Verified Editor Actions

- `EditorReadback` guards editor identity, exact selection and bounded surrounding
  text. Finish composition and revalidate before explicit template/suffix edits.
  Never retry uncertain writes or infer success from a return value alone.
- Continuous swipe is optional/default-off and uses bounded dwell segments plus one
  serialized decode/commit queue. Revalidate editor/layout/config/language ownership
  before every result and advance learning only after accepted readback.
- Suffix learning replacement uses opaque instance/commit/epoch receipts and exact
  owned store increments; it must not fall back to spelling-based rollback.
- The shared `SuggestionHandler.onEditorSelectionChanged` gate keeps selection UI
  unconditional but prevents verified owned callbacks from reaching manual caret
  consumers. Android supplies no operation IDs; retain the bounded-ledger limitation.
- TEMPLATE is explicit and nonrecursive; TEXT stays literal. Per-key and popover
  assignment share validation and preserve action type through label/persistence/export.

Canonical contracts: `docs/specs/context-learning-and-next-word.md`,
`docs/specs/ctc-swipe-engine.md` and
`docs/wiki/specs/customization/per-key-actions-spec.md`.

## 4. Developer Quirks & Gotchas
-   **"White Bar" Artifact:** If the keyboard animation shows a white bar at the top, ensure `WindowLayoutUtils` sets height to `WRAP_CONTENT` and the Service theme is fully transparent.
-   **Numeric Height Scaling:** `KeyboardData.numpad_height` is an explicit XML opt-in, default false; numeric/PIN layouts set it true. Never use `bottom_row=false` as a numeric-layout proxy: compact custom boards also omit the bottom row (GH #90). Preserve the flag through every layout transformation; see `docs/specs/layout-system.md`.
-   **Resource Duplication:** **DO NOT** manually copy XML files to `res/raw`. The Gradle build task handles this. Manual copying causes "Duplicate resource" errors.
-   **F-Droid Compatibility:** The version code logic and split APK structure are designed to be compatible with F-Droid's build expectations.
-   **Termux Environment:** When running shell commands, always prefer `./build-on-termux.sh` over direct `./gradlew` calls to ensure the correct environment variables and AAPT2 overrides are applied.
-   **Dirty Worktrees:** Multiple sessions may share this checkout. Inspect `git status` and the diff before editing; never discard, overwrite, stage, or commit another session's changes.
-   **Release Authority:** Never create/push a tag, publish a release, or interact with external issues/MRs without explicit user authorization. Preparing and validating release files does not grant publication authority.
-   **Untrusted Imports:** Validate and bound archive/container input in a staging area before applying it. Enforce per-entry and aggregate decompressed limits, reject duplicates/path traversal, and roll back staged/live file changes on failure.
-   **Accessibility Geometry:** Custom-drawn keyboard accessibility nodes must use the same finite ownership partition as normal touch hit-testing; gaps and edge slop may not become TalkBack dead zones.

## 5. Documentation Map
-   `docs/ARCHITECTURE_MASTER.md`: High-level system design.
-   `docs/ONNX_DECODE_PIPELINE.md`: Deep dive into the swipe engine.
-   `docs/VERSIONING.md`: Explanation of the versioning scheme.
-   `memory/`: Context files for AI agents.

This file should be updated when significant infrastructure changes occur.
