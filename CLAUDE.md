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
  Open work lives in `memory/todo.md` "Open now"; `docs/audit/2026-08-28-archive-verification.md`
  is the closed ARC ledger.

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
| "content pane layout", "GIF pane", "topPane" | `.claude/skills/content-pane-layout.md` |
| "theme", "Monet", "Material You", "DIY theme", "Theme Manager", "per-layout theme", "magnifier colours" | `.claude/skills/theme-system.md` |
| "Quick Settings tile", "minimize", "floating button", "FAB", "summon", "hide_keyboard", "overlay permission" | `.claude/skills/keyboard-tile-and-minimize.md` |
| "autocorrect ranking", "channel ranking", "κ"/"kappa", "δ"/"delta", "Ranking & Tuning", "AutocorrectTuning" | `.claude/skills/settings-preferences.md` § Autocorrect Knobs + `autocorrect/AutocorrectTuning.kt` + `docs/eval/2026-10-18-channel-autocorrect-ranking.md` |
| "editor commands", "RustDesk", "blind field", "terminal", "TYPE_NULL", "process-text", "inline field" | `.claude/skills/ime-key-routing.md` |
| "swipe-to-delete", "Undo" (clipboard rows) | `.claude/skills/clipboard-panel-architecture.md` § Swipe to delete a row |
| "Persian", "half-space", "ZWNJ", "U+200C", "ArabicScriptFold", "WordChars" | `.claude/skills/dictionary-pipeline.md` § Arabic-script lookup fold, `ime-key-routing.md`, `contraction-system.md`; spec `docs/specs/dictionary-and-language-system.md` |
| "voice input", "speech", "dictation", "STT", "voice pack", "Moonshine", "microphone" | `docs/specs/voice-input.md` (engine-neutral `stt/`, only `stt/moonshine/` knows the engine — `SttArchitectureDriftTest`); research `docs/research/2026-10-28-stt-engine-deep-dive.md` |
| "translations", "strings audit", "locale", `formatted="false"`, escaped quotes | this file § "String resources and translations" + `docs/audit/2026-10-22-translation-audit.md` (tool matrix) + `TranslationFormatParityTest` |

**Release-specific reminder**: When user says any release-related word, READ `.claude/skills/release-process.md` FIRST. It documents the fastlane changelog model (`fastlane/metadata/android/en-US/changelogs/{baseCode}{abi}.txt`), the F-Droid API queries for current state, and the version-code math. Do NOT confuse `metadata/fdroid/tribixbite.cleverkeys.yml` (build recipe) with the fastlane changelogs (release notes).

---

## Active handoff (2026-10-31)

origin/main is `e3487b41`; everything through it is pushed. Pushes to origin/main are authorized
(maintainer, 2026-10-07/10-21); tag, version bump, release and GitHub posts still are not.
v2.0.0 is code-complete and NOT tagged; it now includes on-device voice input (pause-based
endpointing) and the in-app Open-source licenses screen. Release gates, item by item:
`docs/audit/2026-10-31-v2.0-release-readiness.md` §5 and its "Resolved" section — the agent
document items are done; the soak sign-off, the string-review decision and the tag itself are
the maintainer's. The only authoritative task list is the "Open now" section at the top of
`memory/todo.md` (its 2026-10-31 status block lists what is in 2.0, what blocks the tag and the
2.1 roadmap; owners: device / maintainer / agent / RTX, with the test phones' addresses);
everything below it there is dated history with the evidence pointers for the October work.
`memory/HANDOFF.md` holds reference state, dated pointers and rules,
`docs/audit/gh-issue-resolution.md` the per-issue status, the plan doc's "Current execution
status" table the roadmap state, and CHANGELOG.md's 2.0.0 section the user-visible summary.
`docs/plans/v2.0.0-manual-test-checklist.md`: agents MAY edit it and run its items (maintainer,
2026-10-25: "created and edited by agents… attempt to do every item"). Read those before editing or launching Gradle.

## Shared git INDEX between sessions (2026-10-23)

Never `git add` to the shared `.git/index`: another session's `git commit` takes everything
staged (a Persian pack commit swept in a whole uncommitted feature). Commit from a private index
(`GIT_INDEX_FILE=<scratch> git read-tree HEAD`, add, commit) and re-read HEAD right before
committing — an index read before a foreign commit records that commit's files as deleted.

## Blind fields and EditorInfo fixtures (2026-10-13)

`TerminalUtils.editorKind` treats RustDesk and ANY `inputType == TYPE_NULL` editor as a blind
REMOTE field (terminal-style input). An objenesis/relaxed-mock `EditorInfo` has inputType 0,
so test fixtures modelling an ordinary editor must set `TYPE_CLASS_TEXT` (11 mock tests broke).

## Rendered documentation link checks

Normalize relative HTML hrefs against each page URL before checking targets; an absolute-only
checker missed 29 broken engineering-note links. Run after the site build finishes (2026-10-06).

## Kotlin compiler execution on Termux

Use `-Pkotlin.compiler.execution.strategy=in-process` (Gradle property), not the old
`-D` system property. Kotlin 2.0 ignored the latter and spawned a separate 1 GiB daemon;
a full compile spent most CPU in parallel GC. `gradle-guard.sh` now uses `-P` (2026-10-06).

## Explicit editor edits and composition

`InputConnection.commitText` replaces an active composing span even when a different
selection was verified. Finish composition, require acknowledgement, and revalidate
exact editor/session readback before explicit template or suffix writes (2026-10-06).

## Generated Android-test assets and lint

Asset merge, `generate*AndroidTestLintModel` and `lintAnalyze*AndroidTest` all read
generated language packs; make each depend on `copyScriptLatencyPacks`. Analysis
reads them directly too; separate invocations hid these dependencies (2026-10-06).

## Orchestrator preference cleanup

Native test processes can exit before `SharedPreferences.apply()` reaches disk.
Snapshot and synchronously `commit()` exact prior preference state in teardown;
a leaked disabled `because` broke an unrelated autocorrect test in the full suite.

## Owned editor selection callbacks

A suffix/separator callback accepted by SuggestionHandler's exact bounded ledger must
also bypass manual-caret consumers in the service. Otherwise cursor sync clears the
new auto-space stamp and continuous tracking cancels the phrase (2026-10-06).

## Kotlin view initialization order

Fields used by `reset()`/`setKeyboard()` must precede the constructor `init` block.
Later property initializers run afterward; new continuous-swipe cancellation state
caused a real null-list constructor crash caught by native view tests (2026-10-06).

## Native APK alignment check

The installed Termux `zipalign` accepts `-c -p 4`, but lacks the newer `-P 16`
option. Record the check actually run; its success alone is not evidence of 16 KiB
page compatibility (2026-10-06).

## Compose dialog stacking order

Compose `Dialog` windows stack in the order they are FIRST composed. A sub-dialog composed
before its parent in the same frame (e.g. an edit mode that opens the label step at once)
ends up hidden UNDER the parent and taps look like no-ops. Compose sub-dialogs after the
parent `Dialog` (CommandPaletteDialog, Saga 2026-10-07).

## Compose "Recording currently in progress" crashes

A throw inside an AndroidView's draw leaves Compose's RenderNodeLayer recording open;
the NEXT frame dies with this message and no app frames, and Espresso swallows the real
throw. Draw the view into `RenderNode.beginRecording()` in a test to surface it (2026-10-08).

## Gradle-guard memory caps (2026-10-17)

With the current source size, `compileDebugKotlin` can OOM the heap at the 1 GiB default and
`compileDebugUnitTestKotlin` can hit `AssertionError: Metaspace` at 256 MiB. Use
`GRADLE_GUARD_XMX=1536m GRADLE_GUARD_METASPACE=384m` for test runs; check the log for the
real error before blaming code (a red build may never have reached the tests).

## Tap predictor vs swipe lexicon frequency scales

The tap predictor (autocorrect, suggestion bar) loads `en_enhanced.bin` (rank 0..255, full
spread); the CTC swipe lexicon uses `en_enhanced.json` (compressed 134..255). They are NOT
`byte = 255 − rank`: 98,139/98,140 words differ (help 180 vs 218). Autocorrect harnesses that
read the JSON measured the wrong dictionary; DD-M's M=32 failed on the Pixel (2026-10-12).

## Instrumented tests on API 36+ (2026-10-17)

Espresso < 3.7.0 dies on Android 16+ (`InputManager.getInstance` removed). The emulator.wtf
36/37 images report a hardware keyboard with `show_ime_with_hard_keyboard=0`, so the IME
input view never shows: tests that need it on screen use `ShowImeWithHardKeyboardRule`.
A bare `EditorInfo()` is `TYPE_NULL`, i.e. a blind REMOTE field — set an input type.

## Autocorrect tuning and long evals (2026-10-18)

Every autocorrect weight lives in `autocorrect/AutocorrectTuning.kt` (`Keys` = key, default and
range for prefs, validation, backup and UI); `Config.autocorrect_tuning` is null on an Objenesis
test Config, which means DEFAULT. The sweep's gates pass about 200 candidates per input (length
difference 2), so a weight grid needs a bound-based pre-filter (`ChannelRankingEval`). Run a
multi-hour eval from a separate `git worktree`: another session's half-finished edits in the
shared tree broke the eval's compile.

## Compose slider steps (2026-10-20)

`Slider(steps = n)` means n stops BETWEEN the ends, so a 0..100 slider in 1s needs 99. About 40
settings sliders passed the interval count and every stop fell between two values. Settings
sliders take `increment` (`SettingsSlider`); raw `Slider`s must use `sliderSteps(range, inc)`.

## IME-side InputConnection results (2026-10-21)

Mutating calls (`performContextMenuAction`, `performEditorAction`, `commitText`, …) on the IME's
connection return the IPC's success (AOSP `IRemoteInputConnectionInvoker`), never the editor's
answer. A mock IC that returns false models nothing real; feedback built on it never fired.

## Large commits and batch edits (2026-10-25)

One `commitText` crosses binder as UTF-8 in a ONE-WAY transaction limited to half of the ~1 MB
buffer, so a 524,000-char paste failed with FAILED BINDER TRANSACTION and returned false,
silently. `LargeTextCommit` sends chunks of 192 KiB WIRE BYTES (not chars) in one batch edit.
Chrome never answers `getTextBeforeCursor` inside a batch edit (each call hit the 2 s timeout):
never read back between chunks; retry a refused chunk in place and report an incomplete paste.
Paste time is Chrome's, not IPC (2026-10-26): every `\n` becomes its own edit (13,100 lines ≈
340 s at any chunk size) and each commit re-lays out the field, so fewer chunks help only
newline-free text. Time pastes by `input` events over CDP, never by eye.

## Self-defined permissions and authorities (2026-10-26)

Write every `<permission>` and provider authority as `${applicationId}.…` (Kotlin:
`BuildConfig.APPLICATION_ID`). A fixed name blocks a differently signed package from installing
(INSTALL_FAILED_DUPLICATE_PERMISSION): the debug and release builds could not coexist.

## PAL model preference

Use Gemini 3.8 (`gemini-3.8-flash`) for PAL consultation; never use Gemini 3.1.
If PAL fails or is unavailable, proceed locally with independent validation rather
than blocking the work (maintainer instruction, 2026-10-02).

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

**TESTING POLICY** (rewritten 2026-09-03; supersedes the old "never test via ADB" rule):
- **ADB testing IS allowed.** PREFER the dedicated test phones: Pixel 8 Pro, the two Sagas (the
  second one has Termux) and the Seeker. Their addresses are DHCP leases — the current ones are
  kept in `memory/todo.md` "Open now" (test-phones paragraph); rediscover with `nmap -sn` and
  `getprop ro.product.model`. The Pixel's wireless-debugging port also rotates (global CLAUDE.md).
- **NEVER** run UI tests against the Termux HOST phone itself — this device is the dev box.
- Instrumented tests (ew-cli) and pure JVM tests remain **first choice** where they fit;
  use ADB for what they can't cover (visual verification, real-IME interaction, device state).
- Saga hard rules stand: **never** framework restart (`stop`/`start` — bricks it), leave no
  trace (return focus, reinstall nothing extra), restore any settings you change (e.g. `ime set`).
- Clipboard panes appear ABOVE the unchanged keyboard; a bottom-only screenshot cannot
  confirm opening/closing. UIAutomator may omit IME nodes, and pane coordinates move with
  host scroll/focus. Crop the header first and verify the synthetic query/count before
  capturing entry content or deleting (Seeker, 2026-10-06).
- Seeker streamed APK install stalled uncommitted near 99% after Wi-Fi trouble;
  `adb install --no-streaming -r <apk>` succeeded. Verify installed SHA before UI checks.
- Default landscape content panes are only ~120dp tall: separate 40dp search,
  48dp result and 32dp pagination rows leave no entries. Even 31dp clips entry text;
  require a full 48dp entry viewport and share search/results horizontally on wide panes.
- `am start` can launch behind expanded Quick Settings on Seeker; collapse it with
  `cmd statusbar collapse` before UI tests, then restore the original shade afterward.
- Extra-key filtering reused a row’s unkeyed `remember` labels, showing Greek/Math
  for `clear_clipboard` on Seeker. Use stable lazy-item keys, derive labels from the
  current key/resources, and include displayed titles in search (2026-10-06).
- Terminal paste reads the OS clip and commits its text directly; old termux spec/Ctrl+V
  comments were stale. Keep shared package routing independent of automatic-space policy.
- Custom terminal additions must use exact validated IDs and immutable Config snapshots;
  backup imports and settings Save share the parser, with no installed-app enumeration.
- ADB Compose dumps can mark a disabled button’s TextView `enabled=true`; check its
  clickable parent node for the actual button state (Seeker, 2026-10-06).
- Extra Keys search/info/reset must share the rows' lazy viewport; fixed headers
  starved landscape rows on Seeker. Keep search saveable across recreation.
- Adding an extra key requires both `ExtraKeysPreference.EXTRA_KEYS` and the
  shared `ExtraKeysPreference.categorizedKeys` catalog; an unclassified key is counted and
  searchable in the data but silently absent from the UI (caught on Seeker, 2026-10-06).
- `clear_clipboard` clears only Android’s current clip via the shared platform operation;
  never route it to field CLEAR, history deletion or an InputConnection. API 21–27 keep
  an empty item; saved history/pinned/todo entries stay.
- Freeze source edits before Gradle validation: edits during a running incremental
  compile left an older unit-test class in use (2026-10-06). Force that task with
  `compileDebugUnitTestKotlin --rerun` and rerun tests after final source changes.
- Host tests can leave `Build.VERSION.SDK_INT` nonzero after another class; do not
  assume android.jar’s initial zero in routing assertions. Test both injected platform
  branches explicitly and assert the actual SDK branch for end-to-end routing.
- Host `-PtestClass` is relative to `tribixbite.cleverkeys` (the runner always prepends it):
  use `-PtestClass=clipboard.ClipboardHistoryViewStateGuardsTest`, not a fully qualified name.

---

## 📁 **ARCHITECTURE OVERVIEW**

```
src/main/kotlin/tribixbite/cleverkeys/       # package tribixbite.cleverkeys
├── *.kt                            # 138 files flat at the package root
│                                   #   (IME service, keyboard views, Config,
│                                   #    predictors, tile, WordChars, etc.)
├── activities/                     # 15 files: 14 *Activity.kt + LayoutThemePicker.kt
├── clipboard/                      # Clipboard history/db/views (23 files) + the
│   └── sanitize/                   #   private-copy plumbing and PII sanitizers (4)
├── emoji/                          # Emoji panel: grid, search, keyword index (6 files)
├── onnx/                           # ONNX session loader (ModelLoader.kt — CTC only)
├── ui/                             # UI (44 files; 2 at ui/ root)
│   └── settings/                   #   Settings screens (42 incl. subdirs)
│       ├── sections/               #     Per-section composables (20 files)
│       └── io/                     #     Import/export UI (9 files)
├── backup/                         # Backup & restore, import-plan diff (22 files)
├── swipe/                          # Engine routing + shared swipe code (18 files)
│   ├── ctc/                        #   CtcEngineAdapter, pure-JVM CTC beam decode (22)
│   └── geometric/                  # Geometric decoder (pure JVM, 21) — WIRED since
│                                   #   2026-07-21 (WP9 steps 7-9): the fallback for
│                                   #   non-Latin/incomplete layouts + user-selectable
│                                   #   mode; spec: docs/specs/geometric-swipe-engine.md
├── gesture/                        # Swipe recognisers, classifier, ContinuousSwipe (7)
├── wiring/                         # KeyboardComponentGraph + bridges (4)
├── popover/                        # Subkey popover (5)
├── minimize/                       # KeyboardMinimizer, MinimizedKeyboardView (2)
├── customization/                  # Short Swipes, commands, KeyMagnifierView (17 files)
├── theme/                          # Theming (12 files)
├── gif/                            # GIF panel (9 files)
├── autocorrect/                    # Autocorrect + AutocorrectTuning (9 files)
├── prefs/                          # Preference helpers (7 files)
├── contextaware/                   # Context-aware prediction (6)
├── ml/, langpack/                  # ML helpers (4), language-pack import (4)
├── personalization/, persist/, a11y/  # 3 each
└── autofill/                       # Autofill integration (1)
```

> Counts re-derived 2026-10-23 from `git ls-tree -r HEAD --name-only` (410 total .kt under
> `src/main/kotlin`, 138 flat at the package root; post-ADR-011, post-ARC-048 R4, after the
> Persian work). Re-derive from HEAD, never from the working tree — concurrent sessions leave
> uncommitted moves in this shared checkout and a tree scan reports false drift. The old
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
### Heap-probe Java-local roots (2026-09-10)
- Do not read service objects or WeakReference.get() in a long-lived instrumented memory-test frame: ART can retain those Java locals across GCs. Shark found a 41 MB destroyed IME rooted in the test thread. Read/count on runOnMainSync and return primitives; verify suspicious retention with a heap graph before attributing it to production.

### Emulator.wtf authorization (2026-09-10)
- The maintainer explicitly grants permanent ongoing approval to upload app/test APKs to emulator.wtf for this project's testing, including rebuilt diagnostics and synthetic emulator heap artifacts. Do not re-request this approval for routine test iterations. This does not authorize publishing releases or uploading the phone's personal data.

### Local wiki build (2026-09-27)
- `cd site && bun run build:termux` keeps Astro 7 and its native bindings under Bun; the entry is `astro/bin/astro.mjs`, not the removed `astro/astro.js`. Verified 84 pages. Legacy wiki HTML paths are generated redirects in deployment, not copies of their old bodies.

### Static LM language guard (2026-09-27)
- `build_static_lm.py --lang` accepts only languages with a `LangConfig` in `CONFIGS` (en es de fr it pt sv). A config is NOT a ship decision: each language ships only if it passes ITS OWN pre-registered S1 gate (maintainer rule, 2026-09-29). Shipped: en, de, fr, it (`4775300e`) and, after a pre-registered retry with new corpora, pt and sv (`807c56bd`; `src/main/assets/lm/{en,de,fr,it,pt,sv}.cklm` at HEAD); es failed twice (+4.96 vs +5) — do not retry without a new stated reason (`docs/eval/2026-09-29-static-lm-multilingual.md`). Build candidates with `--out-dir` outside `src/main/assets/lm` and evaluate with `STATIC_LM_MODEL_DIR=<dir>`, so no unevaluated model is ever packaged from the shared tree; a shipped language also needs its `scripts/data/tatoeba-contributors-<lang>.txt` (or Tatoeba weight 0) and a NOTICE line naming its Leipzig corpus — `StaticLmAssetDriftTest` enforces both. The `en` config keeps `nfc=False`/`exclude_eval_overlap=False` only to stay byte-identical — verify `en.cklm` sha256 after any builder change.
- The model names contractions by DISPLAY form (`don't`, `c'est`) while tap candidates are apostrophe-free keys: every lookup must go through a model built with `withReplaceAliases(<REPLACE bucket>)` (BigramModel does this at load; tests use `StaticLmLanguageData.replaceAliases`). A raw `StaticContextLm.parse` scores `dont` by backoff — the 2026-09-29 bug.

### Test-rename and mock-test traps (2026-09-30)
- `docs/RELEASE_RECORD.md` anchors TEST METHOD NAMES (`File.kt#method`) and released sections are hash-pinned (`ReleaseRecordDriftTest.historyIsImmutable`): renaming an anchored test breaks the anchor, and editing the old row breaks the hash. Keep the old method name (note why in its KDoc) or add a superseding row in the current release. `rg -n '<method>' docs/RELEASE_RECORD.md` before renaming any test.
- Adding `android.util.Log` to a handler path breaks `runMockTests` classes that drive it (`RuntimeException: Stub!`): mock it next to Toast (`mockkStatic(android.util.Log::class)`).

### String resources and translations (2026-09-30, extended 2026-10-22)
- A translation containing a literal `%` (e.g. "as % of the key width") must carry `formatted="false"` in EVERY locale — the attribute is not inherited from the default file: CI `lintDebug` fails `StringFormatInvalid` when the following characters read as a conversion (vi "% chiều"). A string WITH arguments writes a literal percent as `%%`.
- aapt2 silently REMOVES an unescaped `"` inside a value (`"%1$s" wurde hinzugefügt` rendered without its quotes in 16 locales, `0a8fc939`): write `\"` or typographic quotes; an unescaped `'` outside a quoted region is a compile error.
- `TranslationFormatParityTest` (pure, `runPureTests`, 2026-10-22) now pins all four mechanical rules for every resource in the 21 locales — coverage and element kind, format-argument multiset (plural `other` items too), the literal-`%` rule and quote escaping — and runs each rule on an inline fixture with one deliberate defect, so a dead rule fails loudly. `lintDebug` (≈45 min on Termux) is still the only check for MissingTranslation wording-class lint; CI runs it.
- Tool matrix, commands and the native-review leads: `docs/audit/2026-10-22-translation-audit.md`; review sheets `scripts/export_translation_review.py`; glossary `docs/i18n/glossary.json` (`TranslationGlossaryTest`).
- Settings search scroll targets are recorded by `onGloballyPositioned`, which also fires mid expand-animation with in-flight offsets. Scroll only once a position is registered AND unchanged across polls (`expandAndScrollTo`), and record positions relative to the scroll viewport (`contentYOf`), never raw `positionInRoot`.

### Release APK raw-layout verification (2026-10-02)
- Minified release APKs shorten raw XML paths (e.g. res/-0.xml), so looking for res/raw/numeric.xml falsely suggests the layout is absent. Verify packaged layouts by matching source-byte SHA-256 against archive entries; do not rely on resource filenames.

### Dictionary import allocations (2026-10-03)
- Streaming ZIP extraction does not bound later trie/word-array allocations. GH #184 shares 100,000 canonical entries / 16 MiB CKDT limits through `CkdtDictionaryReader`; validate actual header counts at import and load, never manifest metadata. Archive caps/refusal/rollback are in `docs/specs/dictionary-and-language-system.md`.

### Pure helpers beside Android views (2026-10-03)
- An Android View companion can still class-load Android-only outer types under pure JVM tests. Keep Android-free policies in a separate object in the existing source file (as `EmojiGlyphSupport`), rather than the View companion.

- **Compose/navigation tap lifecycle (GH #188, 2026-10-05):** the bottom-row Compose key also has arrow subkeys. A deferred tap must fall through to shared latch handling after key-down; early key-up plus clearLatched cancels its prefix immediately. State-machine-only tests miss this; pin the pointer release path.

- **Custom apostrophe routing (2026-10-05):** single ASCII/curly apostrophe TEXT flicks must use `KeyEventHandler` to preserve owned auto-space, inline search and bookkeeping; literal multi-character macros do not acquire suffix semantics. Possessive commands need an explicit verified-word transaction and suffix-only undo (roadmap §2.1).

- **Short-word CTC experiments (2026-10-05):** wet/wt have identical collinear geometric templates, so endpoint/path-only penalties cannot resolve them. Equal-duration letter timing fits the canonical generator by construction; synthetic 19/19 plus one human trace is not shipping evidence. Validate on separate human writers/sessions before changing shared ranking/model behavior.

- **Human screen of synthetic ranking fixes (2026-10-05):** frozen geometry/timing weights reduced 93/100 correct to 83/100 and 61/100 on 100 real traces (92 words); reject both despite synthetic success. Keep distinct trace/word counts and do not retune against the screening set. Evidence in the October 5 roadmap and ignored probe logs.

- **Short-word root cause, re-measured (2026-10-07):** `ad`→`as`/`wet`→`we` is the encoder's learned word prior at the trace END (last frame reads a stroke ending on `d`/`t` as an overshooting `as`/`we`; training has `we` 562× vs `wet` 4×) plus no emission for collinear pass-through letters. λ 4 is the dev optimum and endpoint rescoring is neutral on 4,000 held-out traces. Separately, the app featurizes the recognizer's SMOOTHED path with dwell samples dropped: raw input is +0.37 pt held-out top-1 (p 0.029). `docs/eval/2026-10-07-short-word-ctc.md`, `scripts/short_word_ctc_eval.py`.

## Native editor and dialog test lessons (2026-10-06)

- Editor fixtures must report both real selection endpoints. Cursor-start equality
  alone does not permit removing a prior automatic space when replacing a range;
  InputConnection offsets are UTF-16, and a batch edit is not an atomic transaction.
- Nonfocusable IME dialogs need UIAutomator interactions, rather than Espresso's
  focused-window assumption. Wait for AlertDialog dismissal before reopening.
  Remeasure detached views after changing RTL; integer bounds can round by one pixel.

- An unfiltered ew-cli run needs the four ignored experimental `ctc_bench` encoders
  from the local CleverKeys-ML artifacts in the test APK. Preflight their presence and
  hashes; absent assets fail two real benchmarks. Never substitute production weights.
