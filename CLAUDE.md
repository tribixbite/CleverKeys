# CLAUDE.md - CleverKeys Development Context

## ⚠️ CONCURRENT SESSIONS SHARE THIS WORKING TREE
Multiple Claude sessions may work this repo simultaneously in the SAME directory
(confirmed 2026-07-20: a geoswipe session clobbered another session's uncommitted
edit via checkout). Rules: commit small and IMMEDIATELY after verifying a fix;
check `git log` for foreign commits before assuming tree state; never assume an
uncommitted working-tree edit survives across long waits; before editing a file
another session may own (check recent commit authorship/subjects), prefer
committed coordination over working-tree edits.

## 🚨 **SESSION STARTUP PROTOCOL - ALWAYS CHECK FIRST!**

**BEFORE STARTING ANY SESSION:**
1.  **CHECK `README.md`** - Production status and overview.
2.  **CHECK `memory/todo.md`** - **Active Task List** (The single source of truth).
3.  **CHECK `docs/TABLE_OF_CONTENTS.md`** - Master navigation for project docs.
4.  **CHECK `docs/specs/`** - Feature specifications for the area you are working on.

**CURRENT STATUS (2026-08-18):**
- Neural swipe engine REMOVED (ADR-011). Swipe = CTC (default) + geometric. See
  `docs/plans/2026-08-18-neural-engine-removal.md` and `docs/history/neural-engine/`.

**HISTORICAL (2026-07-17) — closed, kept for provenance only:**
- The 2026-07-17 code-quality audit (`docs/history/audits/2026-07-17-code-quality-audit.md`)
  and its Tier-1/Tier-2 remediation are DONE; do not treat that list as an open queue.
  Open work lives in `memory/HANDOFF.md` and `docs/audit/2026-08-28-archive-verification.md`.

**SPEC-DRIVEN DEVELOPMENT WORKFLOW:**
1. **Check Spec**: Is there a spec in `docs/specs/` for this feature?
2. **Create Spec**: If missing, create from `docs/specs/SPEC_TEMPLATE.md`
3. **Implement**: Follow spec's implementation plan.
4. **Test**: Use spec's testing strategy.
5. **Update**: Mark TODOs complete in `memory/todo.md`.

## 📚 **SKILL FILES (READ BEFORE TASK MATCHES)**

`.claude/skills/` contains task-specific reference docs. **ALWAYS read the relevant skill BEFORE starting work on a matching topic** — they encode hard-won lessons and exact procedures the main context doesn't reproduce.

| Trigger phrase | Skill file |
|---|---|
| "release", "tag", "publish", "version bump", "F-Droid", "fastlane", "changelog" | `.claude/skills/release-process.md` |
| "clipboard", "pinned", "todo", "tag" (clipboard) | `.claude/skills/clipboard-panel-architecture.md`, `clipboard-tag-system.md`, `clipboard-todo-system.md` |
| "IME toast", "feedback", "pulse" | `.claude/skills/ime-visual-feedback.md` |
| "key routing", "edit mode", "search mode" in IME | `.claude/skills/ime-key-routing.md` |
| "ew-cli", "instrumented test", "emulator.wtf" | `.claude/skills/ew-cli-testing.md` |
| "dictionary", "VocabularyTrie", "predictor" | `.claude/skills/dictionary-pipeline.md` |
| "contraction", "apostrophe", "elision", "collision", `don't`/`c'est` display | `.claude/skills/contraction-system.md` |
| "settings", "SharedPreferences" | `.claude/skills/settings-preferences.md` |
| "wiki", "Astro", "site docs" | `.claude/skills/wiki-documentation.md` |
| "emoji panel" | `.claude/skills/emoji-panel.md` |
| "content pane layout" | `.claude/skills/content-pane-layout.md` |

**Release-specific reminder**: When user says any release-related word, READ `.claude/skills/release-process.md` FIRST. It documents the fastlane changelog model (`fastlane/metadata/android/en-US/changelogs/{baseCode}{abi}.txt`), the F-Droid API queries for current state, and the version-code math. Do NOT confuse `metadata/fdroid/tribixbite.cleverkeys.yml` (build recipe) with the fastlane changelogs (release notes).

---

## 🎯 **PROJECT OVERVIEW**

CleverKeys is a **complete Kotlin rewrite** of `Julow/Unexpected-Keyboard` featuring:
- **On-device swipe prediction** — CTC (ONNX encoder + pure-JVM trie beam) and a geometric decoder; no CGR, no cloud.
- **Advanced gesture recognition** with sophisticated algorithms.
- **Modern Kotlin architecture** with significant code reduction.
- **Reactive programming** with coroutines and Flow streams.
- **Enterprise-grade** error handling and validation.

---

## 📋 **NAVIGATION GUIDE**

### Essential Files
1. **`memory/todo.md`** - **Current pending tasks and verified working features.**
2. **`docs/TABLE_OF_CONTENTS.md`** - Index of all documentation.
3. **`docs/history/session_log_dec_2025.md`** - Recent completed work log.

### Feature Specifications
*Located in `docs/specs/`*
- `short-swipe-customization.md`: Per-key gesture customization.
- `profile_system_restoration.md`: Layout import/export with gestures.
- `ctc-swipe-engine.md`: the shipping CTC swipe decoder.
- `geometric-swipe-engine.md`: the layout-agnostic geometric decoder.
- `core-keyboard-system.md`: Main keyboard logic.
- `clipboard-privacy.md`: Clipboard privacy features.

---

## 🚨 **CRITICAL DEVELOPMENT PRINCIPLES**

**IMPLEMENTATION STANDARDS:**
- **NEVER** use stubs, placeholders, or mock implementations.
- **NEVER** simplify functionality to make code compile.
- **ALWAYS** implement features properly and completely.
- **ALWAYS** do things the right way, not the expedient way.

**TESTING POLICY:**
- **NEVER** test locally via ADB (screencap, input, am start, etc.). ADB is for build-install only.
- **ALWAYS** write instrumented tests (ew-cli) or pure JVM tests when testing is possible.
- If a scenario cannot be tested via instrumented or pure tests, **ask the user to test manually**.

---

## 📁 **ARCHITECTURE OVERVIEW**

```
src/main/kotlin/tribixbite/cleverkeys/       # package tribixbite.cleverkeys
├── *.kt                            # 117 files flat at the package root
│                                   #   (IME service, keyboard views, Config,
│                                   #    predictors, gesture recognisers, etc.)
├── activities/                     # 14 *Activity.kt (Settings, Launcher, managers)
├── clipboard/                      # Clipboard history/db/views (16 files) + the
│   └── sanitize/                   #   private-copy plumbing and PII sanitizers (4)
├── emoji/                          # Emoji panel: grid, search, keyword index (6 files)
├── onnx/                           # ONNX session loader (ModelLoader.kt — CTC only)
├── ui/                             # UI (41 files; 2 at ui/ root)
│   └── settings/                   #   Settings screens (39 incl. subdirs)
│       ├── sections/               #     Per-section composables (20 files)
│       └── io/                     #     Import/export UI (9 files)
├── backup/                         # Backup & restore, import-plan diff (20 files)
├── swipe/                          # Engine routing + CTC (SwipeEngineRouter,
│   ├── ctc/                        #   CtcEngineAdapter, pure-JVM CTC beam decode)
│   └── geometric/                  # Geometric decoder (pure JVM) — WIRED since
│                                   #   2026-07-21 (WP9 steps 7-9): the fallback for
│                                   #   non-Latin/incomplete layouts + user-selectable
│                                   #   mode; spec: docs/specs/geometric-swipe-engine.md
├── customization/                  # Short Swipes, Profiles (14 files)
├── theme/                          # Theming (9 files)
├── gif/                            # GIF panel (7 files)
├── prefs/                          # Preference helpers (7 files)
├── personalization/               # Personalization
├── contextaware/                  # Context-aware prediction
├── autocorrect/                    # Autocorrect
├── ml/                             # ML helpers
├── langpack/                       # Language-pack import
└── autofill/                       # Autofill integration
```

> Counts re-derived 2026-09-01 from `git ls-tree -r HEAD` (334 total .kt under
> `src/main/kotlin`, 117 flat at the package root; post-ADR-011, post-ARC-048 R4).
> Re-derive from HEAD, never from the working tree — concurrent sessions leave
> uncommitted moves in this shared checkout and a tree scan reports false drift.
> Subdirs not shown: `a11y/` (TalkBack), `persist/` (DebouncedPersister). The old
> `tribixbite/keyboard2/` tree with `core/swipe/data/config/…` never existed —
> the package is `tribixbite.cleverkeys` with a large flat root plus the
> subpackages above.
>
> **`activities/`, `clipboard/` and `emoji/` are DIRECTORY-ONLY groupings** (ARC-048 R4):
> the files inside them still declare `package tribixbite.cleverkeys`, because Kotlin
> does not couple directory to package. That is deliberate — it bought the tidier tree
> for zero import churn. Do not "fix" the package statements without also fixing every
> importer. Consequence to remember: a source-scanning drift test that addresses a file
> by repo path must use the new path (e.g. `activities/SettingsActivity.kt`), while
> anything addressing it by FQCN (AndroidManifest, `proguard-rules.pro` keeps,
> `pureTestClasses` in build.gradle) is unaffected.

---

## 🚀 **DEVELOPMENT COMMANDS**

### **BUILD:**

**🚨 ALL Gradle invocations MUST go through `scripts/gradle-guard.sh`** — never call
`gradlew`/`sh gradlew` directly, including from retry loops, background monitors, and
one-off "just check" builds. Written after the 2026-08-29 incident: ~21 concurrent
monitors stacked 8+ daemon JVMs, 12GB into swap, load average 40. The wrapper enforces:
a device-wide flock singleton (`$HOME/.cache/cleverkeys-build.lock`; queued builds wait,
exit 75 on timeout), `--no-daemon` + in-process Kotlin + leaked-JVM sweep on exit,
bounded memory (`-Xmx1024m`, SerialGC, 1 worker, exit 76 if MemAvailable < 1.5GB), and
retries capped at 3 with 60/300/900s backoff on *environmental* failures only. Env
knobs (`GRADLE_GUARD_XMX`, `GRADLE_GUARD_RETRIES`, …) are documented in its header.
Run at most ONE monitor loop per build and always kill it when the build ends.

```bash
# Test compilation
scripts/gradle-guard.sh compileDebugKotlin

# Full build & install (ALWAYS use this for testing; routes through gradle-guard)
./build-on-termux.sh

# Run tests
scripts/gradle-guard.sh test
```

**On the WSL/Linux checkout** (not Termux) Gradle needs both of these exported first,
or it fails with "requires Java 17 ... currently using Java 11" then "SDK location not found"
(sdkman's `current` JDK is 11 and there is no `local.properties`):
```bash
export JAVA_HOME=/home/will/.sdkman/candidates/java/17.0.13-tem
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=/home/will/Android/Sdk ANDROID_SDK_ROOT=$ANDROID_HOME
```
`~/Android/Sdk` is the complete one (platforms 19/34/36, build-tools 34/35); `~/android-sdk`
is the older Termux-style tree. `ew-cli` is NOT installed here and `EW_API_TOKEN` is NOT in
this environment — instrumented runs happen on the Termux device.

### **IMPORTANT: Always Install RELEASE APK**
**NEVER install debug APK for testing.** Always use release builds:
- `build/outputs/apk/release/CleverKeys-v*.apk` ✅
- `build/outputs/apk/debug/CleverKeys-v*.apk` ❌

Debug logging is controlled by `BuildConfig.ENABLE_VERBOSE_LOGGING` which is set
in build.gradle - release builds can have debug logging enabled when needed.
This gives best of both worlds: release performance + debug visibility.

### **DEBUGGING:**
```bash
# Check for compilation errors
./gradlew compileDebugKotlin --continue

# Tail logs for debugging
logcat -s "CleverKeys" "System.err" "AndroidRuntime"
```

**`ENABLE_VERBOSE_LOGGING` const-inlining trap (2026-08-17).** `BuildConfig.ENABLE_VERBOSE_LOGGING`
is `System.env.LOCAL_BUILD == "true"`, and Kotlin inlines it at every call site. Running
`gradlew compileReleaseKotlin` *without* `LOCAL_BUILD` bakes `false` into the consuming class,
and **incremental compilation keeps the stale constant** even after a later `LOCAL_BUILD=true`
build regenerates the flag as `true` — so debug-gated code silently no-ops with no error.
Fix: `rm -rf build/tmp/kotlin-classes/release`. Bit the `MemoryProbe` work; costs a whole
measurement run if unnoticed, because the symptom is *absence of log output*, not a failure.