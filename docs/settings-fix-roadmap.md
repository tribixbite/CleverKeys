# SettingsActivity Decomposition — Implementation Plan


> **STATUS 2026-07-04: Tasks 0–7 COMPLETE** on branch `refactor/settings-decomposition`. SettingsActivity.kt 6,806→815 lines; 34 files; full ew-cli suite 1309/1309 green. Task 8 (state hoisting) deferred per §4 recommendation.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Break the 6,806-line `SettingsActivity.kt` God Object into ~26 focused files (all < 500 lines) **without changing any runtime behavior**.

**Architecture:** Behavior-preserving *Extract Class / Extract Function* refactoring. The Activity's private members are widened to `internal`; its composables and handler methods move into separate files in the same package as **extension functions/composables on `SettingsActivity`** (`fun SettingsActivity.foo()`). State stays where it is until the optional final phase. Because every extracted function keeps the same `SettingsActivity` receiver and reads the same `mutableStateOf`-backed properties, Compose recomposition and preference I/O are byte-for-byte unchanged.

**Tech Stack:** Kotlin, Jetbrains Compose (Material3), AndroidX Activity, SharedPreferences. Tests: `./gradlew runPureTests` (pure JVM) + ew-cli instrumented (Pixel7 API 34).

---

## 0. Terminology (answering "is this modularizing?")

| Term | Does it apply here? |
|---|---|
| **Modularization** (Gradle `:feature:settings` module) | Not required. That's a heavier, optional later step. |
| **Decomposing a God Object** (Fowler: *Extract Class*) | ✅ This is the core operation. |
| **Extract Function / Move Method** | ✅ Each composable & handler is moved out. |
| **Hoisting** (Compose state lifting) | ✅ Only in the optional Phase 6. |

The accurate name for this work is **"decomposing the God Activity by file-splitting via extension functions."** Full Gradle modularization is explicitly *out of scope* — you get ~90% of the maintainability win from the file split alone, at a fraction of the risk.

---

## 1. The enabling technique & its gotchas

Kotlin has no partial classes, so we use the standard idiom for splitting a large class across files:

1. **Widen visibility.** Change the members that extracted code touches from `private` to `internal`. `internal` keeps them invisible outside the module (no public API leak) but visible to extension functions elsewhere in the same module.
2. **Extract as extension functions.** `private fun SettingsActivity.SettingsSwitch(...)` in a new file. The receiver is still the Activity, so `this.keyboardHeight` etc. resolve exactly as before.
3. **Same package.** Keep everything in `tribixbite.cleverkeys` (or a `tribixbite.cleverkeys.ui.settings` sub-package, with `internal` still working module-wide).

**Gotchas to handle explicitly (do not gloss over these):**

- **`registerForActivityResult` launchers (lines 141–237) MUST stay in `SettingsActivity`.** They are lifecycle-registered `val`s initialized at construction; they cannot move to an extension. Their callbacks (`performConfigExport`, etc.) move out, but the launcher declarations stay.
- **`searchableSettings` is `private val ... by lazy { }` (596–633). Extension *properties cannot have backing fields or `by lazy`.** Convert it to either (a) a member `internal val searchableSettings by lazy` that stays in `SettingsActivity.kt`, or (b) an `internal fun searchableSettings(): List<SearchableSetting>` extension. **Chosen: keep as a member `internal val` in `SettingsActivity.kt`** (simplest, preserves laziness). The functions it references (`expanderFor`, `executeSearchAction`, …) move to `SettingsSearch.kt` as extensions and remain reachable.
- **Lifecycle overrides cannot be extensions** (`onCreate`, `onResume`, `onPause`, `onDestroy`, `onNewIntent`, `onSharedPreferenceChanged`). They stay as members in `SettingsActivity.kt`. Their *bodies* may delegate to extracted extension functions (e.g. `onNewIntent` calls `handleGifPackShareIntent`, which moves out; `onSharedPreferenceChanged`'s 203-line body itself moves into `SettingsPersistence.kt` as `internal fun SettingsActivity.handlePreferenceChanged(...)` and the override becomes a one-line delegate).
- **`@Composable` extension functions work** and preserve recomposition: reading `keyboardHeight` (a `by mutableStateOf` delegate) inside `fun SettingsActivity.AppearanceSection()` establishes the same snapshot read it does today.
- **Imports are per-file.** Each new file needs its own Compose/AndroidX imports. The original import block in `SettingsActivity.kt` gets trimmed to only what the residual file uses.

---

## 2. Target file structure

All paths under `src/main/kotlin/tribixbite/cleverkeys/`. Section composables go in a new `ui/settings/sections/` sub-folder; support files in `ui/settings/`.

```
SettingsActivity.kt                      (residual: class decl, companion, launchers, state, lifecycle)   ~650 lines (Phase A) → ~400 (after Phase 6)
ui/settings/SettingsScreen.kt            SettingsScreen() scaffold + section calls                          ~220
ui/settings/SettingsControls.kt          CollapsibleSettingsSection, SettingsSection, Switch, Slider, Dropdown  ~300
ui/settings/SettingsInfoCards.kt         VersionInfoCard, GitHubInfoCard, FAQSection, FAQItem, FAQItemCard, loadVersionInfo  ~230
ui/settings/SettingsDialogs.kt           CollectedDataViewerDialog, PerfStatsViewerDialog                    ~240
ui/settings/SettingsPrefsExt.kt          getSafeInt/Float/String/Boolean extensions                          ~90
ui/settings/SettingsSearch.kt            SearchableSetting + all search/scroll logic                         ~220
ui/settings/SettingsPersistence.kt       loadCurrentSettings, saveSetting, updateConfigFromSettings, handlePreferenceChanged  ~500
ui/settings/SettingsResetPresets.kt      resetAllSettings, fallbackEncrypted, get/applySwipeSensitivityPreset ~175
ui/settings/SettingsNavigation.kt        all open*() launchers + openWikiInBrowser, openGitHubReleases, openBackupRestore  ~80
ui/settings/io/SettingsBackupHandlers.kt config + full-backup export/import + applyPlannedSettings           ~210
ui/settings/io/SettingsDictionaryHandlers.kt dictionary export/import + applyPlannedDictionaries             ~110
ui/settings/io/SettingsClipboardHandlers.kt clipboard + zip export/import + custom-rules sanitize logic      ~230
ui/settings/io/SettingsLanguagePackHandlers.kt lang-pack import/delete + dictionary/lang display helpers      ~180
ui/settings/io/SettingsGifHandlers.kt    gif-pack share/import/remove/refresh                                 ~110
ui/settings/io/SettingsPrivacyDataHandlers.kt privacy clear + collected-data + perf-stats view/export        ~200
ui/settings/io/SettingsSwipeDataHandlers.kt swipe-data JSON/NDJSON export                                     ~70
ui/settings/sections/TestKeyboardSection.kt                                                                   ~37
ui/settings/sections/ActivitiesSection.kt                                                                     ~255
ui/settings/sections/NeuralPredictionSection.kt                                                              ~105
ui/settings/sections/AppearanceSection.kt                                                                     ~263
ui/settings/sections/SwipeTrailSection.kt                                                                     ~113
ui/settings/sections/InputBehaviorSection.kt                                                                  ~388
ui/settings/sections/AutoCorrectionSection.kt                                                                 ~247
ui/settings/sections/GestureTuningSection.kt                                                                  ~295
ui/settings/sections/AccessibilitySection.kt                                                                  ~168
ui/settings/sections/ClipboardSection.kt                                                                      ~282
ui/settings/sections/GifPanelSection.kt                                                                       ~187
ui/settings/sections/BackupRestoreSection.kt                                                                  ~162
ui/settings/sections/MultiLanguageSection.kt                                                                  ~363
ui/settings/sections/PrivacySection.kt                                                                        ~168
ui/settings/sections/AdvancedSection.kt                                                                       ~95
ui/settings/sections/VersionActionsSection.kt                                                                 ~27
ui/settings/sections/HelpSection.kt                                                                           ~24
```

Result: **largest file ≈ 650 lines (residual Activity, Phase A) or ≈ 500 (SettingsPersistence); every other file < 400.** The 1000+ line problem is eliminated.

---

## 3. Complete line-by-line traceability

This table accounts for **all of lines 99–6806** of the current `SettingsActivity.kt`. Blank lines / comment gaps between two declarations travel with whichever declaration they precede. Original line numbers are from the current `SettingsActivity.kt` (6,807 lines total incl. trailing newline).

### 3a. Stays in `SettingsActivity.kt`

| Lines | Declaration | Note |
|---|---|---|
| 1–98 | package + imports + file header | Trim to residual needs |
| 99–100 | `class SettingsActivity : ComponentActivity(), …OnSharedPreferenceChangeListener` | Class shell |
| 101–129 | `companion object` | Stays |
| 138–140 | `backupRestoreViewModel by viewModels()` | Stays |
| 141–242 | 15× `registerForActivityResult` launchers (`configExport`…`customRulesPicker`) | **Must stay** (lifecycle registration). Callbacks move out. |
| 244–486 | ~150 `var … by mutableStateOf(...)` state properties | **Widen `private`→`internal`.** Stay here in Phase A; hoisted in optional Phase 6. |
| 484 | `settingPositions` map | Stays (`internal`) |
| 485–486 | `mainScrollState`, `composeScope` | Stays (`internal`) |
| 506–515 | `searchResultsNestedScrollConnection` (anonymous object `val`) | Stays as member (`internal`); referenced by `SettingsScreen.kt` |
| 596–633 | `searchableSettings by lazy` | Stays as `internal val` (lazy can't be an extension property) |
| 701–707 | collected-data viewer state vars + `collectedDataPageSize` | Widen→`internal`, stay (Phase A) |
| 710–712 | `showPerfStatsViewer`, `perfStatsSummary` | Widen→`internal`, stay (Phase A) |
| 713–840 | `override fun onCreate` | Lifecycle — stays |
| 842–844 | `override fun onDestroy` | Stays |
| 846–850 | `override fun onResume` | Stays |
| 852–858 | `override fun onPause` | Stays |
| 860–1062 | `override fun onSharedPreferenceChanged` | **Override shell stays; 203-line body moves** to `SettingsPersistence.kt::handlePreferenceChanged`; override becomes 1-line delegate |
| 6079–6082 | `override fun onNewIntent` | Override stays; body delegates to `SettingsGifHandlers.kt::handleGifPackShareIntent` |
| 6806 | class closing brace | Stays |

### 3b. → `ui/settings/SettingsScreen.kt`

| Lines | Content | Note |
|---|---|---|
| 1066–1209 | `SettingsScreen()` scaffold: `rememberScrollState`, dialog hosts (1073–1088), `Column`, header text, search bar, search results card | Becomes `@Composable internal fun SettingsActivity.SettingsScreen()` |
| (rebuilt) | 17 calls to section composables in original order, replacing inline blocks 1210–4388 | New orchestration body — each call is `TestKeyboardSection()`, `ActivitiesSection()`, … |
| 4388 | `SettingsScreen` closing brace | |

### 3c. → `ui/settings/sections/*.kt` (the body of `SettingsScreen`, 1210–4388)

Each block below is a `CollapsibleSettingsSection(...) { … }` lambda extracted into `@Composable internal fun SettingsActivity.<Name>()`.

| Lines | Section block | Destination file |
|---|---|---|
| 1210–1246 | Test Keyboard Section | `sections/TestKeyboardSection.kt` |
| 1247–1501 | Activities Section (special feature managers) | `sections/ActivitiesSection.kt` |
| 1502–1606 | Neural Prediction Section | `sections/NeuralPredictionSection.kt` |
| 1607–1869 | Appearance Section (height/visual) | `sections/AppearanceSection.kt` |
| 1870–1982 | Swipe Trail Section | `sections/SwipeTrailSection.kt` |
| 1983–2370 | Input Behavior Section (incl. Word-Prediction-Advanced sub-block 2069+) | `sections/InputBehaviorSection.kt` |
| 2371–2617 | Auto-Correction Section | `sections/AutoCorrectionSection.kt` |
| 2618–2912 | Gesture Tuning Section (Short Gestures, Selection-Delete, Tap/Typing, Swipe-Recognition, Slider sub-blocks) | `sections/GestureTuningSection.kt` |
| 2913–3080 | Accessibility Section (incl. vibration block 2968+) | `sections/AccessibilitySection.kt` |
| 3081–3362 | Clipboard Section (incl. URL-handling sub-block 3278+) | `sections/ClipboardSection.kt` |
| 3363–3549 | GIF Panel Section | `sections/GifPanelSection.kt` |
| 3550–3711 | Backup & Restore Section | `sections/BackupRestoreSection.kt` |
| 3712–4074 | Multi-Language Section (Quick-Toggle 3896+, Language-Packs 3951+) | `sections/MultiLanguageSection.kt` |
| 4075–4242 | Privacy Section (incl. Performance-Metrics 4193+) | `sections/PrivacySection.kt` |
| 4243–4337 | Advanced Section (incl. Terminal Mode 4249+) | `sections/AdvancedSection.kt` |
| 4338–4364 | Version & Actions Section | `sections/VersionActionsSection.kt` |
| 4365–4388 | Help Section (FAQ + Wiki) | `sections/HelpSection.kt` |
| 3078–3080 | "Dictionary section removed" comment | Drop (dead comment) |

### 3d. → `ui/settings/SettingsControls.kt`

| Lines | Declaration |
|---|---|
| 4401–4460 | `CollapsibleSettingsSection(...)` |
| 4464–4488 | `SettingsSection(...)` |
| 4492–4557 | `SettingsSwitch(...)` |
| 4560–4624 | `SettingsSlider(...)` |
| 4627–4700 | `SettingsDropdown(...)` |

### 3e. → `ui/settings/SettingsInfoCards.kt`

| Lines | Declaration |
|---|---|
| 4704–4751 | `VersionInfoCard()` |
| 4754–4786 | `GitHubInfoCard()` |
| 4793–4835 | `FAQSection()` |
| 4837–4839 | `data class FAQItem` |
| 4840–4886 | `FAQItemCard(item)` |
| 5735–5753 | `loadVersionInfo(): Properties` (feeds VersionInfoCard) |

### 3f. → `ui/settings/SettingsDialogs.kt`

| Lines | Declaration |
|---|---|
| 4892–5088 | `CollectedDataViewerDialog(...)` |
| 5094–5127 | `PerfStatsViewerDialog(...)` |

### 3g. → `ui/settings/SettingsPrefsExt.kt`

| Lines | Declaration |
|---|---|
| 5132–5143 | `SharedPreferences.getSafeInt` |
| 5146–5156 | `SharedPreferences.getSafeFloat` |
| 5159–5181 | `SharedPreferences.getSafeString` |
| 5184–5212 | `SharedPreferences.getSafeBoolean` |

### 3h. → `ui/settings/SettingsSearch.kt`

| Lines | Declaration |
|---|---|
| 489–491 | `recordSettingPosition` |
| 494–503 | `scrollToSetting` |
| 518–536 | `collapseAllSections` |
| 545–552 | `data class SearchableSetting` |
| 558–560 | `settingSlug` |
| 562–576 | `sectionDisplayName` |
| 579–594 | `expanderFor` |
| 636–643 | `isGateEnabled` |
| 646–689 | `executeSearchAction` |
| 691–698 | `getFilteredSettings` |

*(`searchableSettings` lazy val stays in `SettingsActivity.kt` per §1 gotcha; `searchResultsNestedScrollConnection` stays as a member.)*

### 3i. → `ui/settings/SettingsPersistence.kt`

| Lines | Declaration |
|---|---|
| 860–1062 | body of `onSharedPreferenceChanged` → `handlePreferenceChanged(prefs, key)` |
| 5214–5443 | `loadCurrentSettings` |
| 5445–5476 | `saveSetting` |
| 5619–5639 | `updateConfigFromSettings` |

### 3j. → `ui/settings/SettingsResetPresets.kt`

| Lines | Declaration |
|---|---|
| 5575–5589 | `getSwipeSensitivityPreset` |
| 5594–5617 | `applySwipeSensitivityPreset` |
| 5788–5909 | `resetAllSettings` |
| 5914–5918 | `fallbackEncrypted` |

### 3k. → `ui/settings/SettingsNavigation.kt`

| Lines | Declaration |
|---|---|
| 4390–4397 | `openWikiInBrowser` |
| 5755–5757 | `openNeuralSettings` |
| 5759–5761 | `openCalibration` |
| 5763–5765 | `openSwipeDebugActivity` |
| 5767–5770 | `openDictionaryManager` |
| 5772–5774 | `openLayoutManager` |
| 5776–5778 | `openExtraKeysConfig` |
| 5780–5782 | `openShortSwipeCustomization` |
| 5784–5786 | `openAutoCorrectionSettings` |
| 5922–5924 | `openBackupRestore` |
| 6569–6578 | `openGitHubReleases` |

### 3l. → `ui/settings/io/SettingsBackupHandlers.kt`

| Lines | Declaration |
|---|---|
| 5927–5933 | `exportConfiguration` |
| 5935–5941 | `importConfiguration` |
| 5996–6003 | `exportFullBackup` |
| 6005–6011 | `importFullBackup` |
| 6188–6209 | `performConfigExport` |
| 6216–6242 | `performConfigImport` |
| 6249–6274 | `applyPlannedSettings` |
| 6482–6520 | `performFullBackupExport` |
| 6522–6567 | `performFullBackupImport` |

### 3m. → `ui/settings/io/SettingsDictionaryHandlers.kt`

| Lines | Declaration |
|---|---|
| 5943–5949 | `exportCustomDictionary` |
| 5951–5957 | `importCustomDictionary` |
| 6276–6298 | `performDictionaryExport` |
| 6305–6331 | `performDictionaryImport` |
| 6338–6363 | `applyPlannedDictionaries` |

### 3n. → `ui/settings/io/SettingsClipboardHandlers.kt`

| Lines | Declaration |
|---|---|
| 5486–5500 | `recomputeCustomRulesStatus` |
| 5508–5511 | `notifySanitizationRulesChanged` |
| 5526–5570 | `handleCustomRulesPicked` |
| 5959–5965 | `exportClipboardHistory` |
| 5967–5973 | `importClipboardHistory` |
| 5975–5981 | `exportClipboardZip` |
| 5983–5989 | `importClipboardZip` |
| 6365–6387 | `performClipboardExport` |
| 6389–6418 | `performClipboardImport` |
| 6420–6443 | `performClipboardZipExport` |
| 6445–6475 | `performClipboardZipImport` |

### 3o. → `ui/settings/io/SettingsLanguagePackHandlers.kt`

| Lines | Declaration |
|---|---|
| 5647–5674 | `detectAvailableV2Dictionaries` |
| 5676–5678 | `refreshAvailableSecondaryLanguages` |
| 5683–5719 | `getLanguageDisplayName` |
| 5726–5733 | `loadPrefixBoostForLanguage` |
| 6013–6020 | `importLanguagePack` |
| 6022–6051 | `performLanguagePackImport` |
| 6053–6066 | `deleteLanguagePack` |
| 6068–6075 | `refreshInstalledLanguagePacks` |

### 3p. → `ui/settings/io/SettingsGifHandlers.kt`

| Lines | Declaration |
|---|---|
| 6084–6095 | `handleGifPackShareIntent` |
| 6099–6139 | `performGifPackImport` |
| 6141–6152 | `performGifRemovePack` |
| 6154–6168 | `performGifRemoveAll` |
| 6170–6179 | `refreshInstalledGifPacks` |

### 3q. → `ui/settings/io/SettingsPrivacyDataHandlers.kt`

| Lines | Declaration |
|---|---|
| 6580–6613 | `clearAllPrivacyData` |
| 6618–6623 | `viewCollectedData` |
| 6628–6652 | `loadCollectedDataPage` |
| 6657–6665 | `viewPerfStats` |
| 6670–6678 | `exportPerfStats` |
| 6683–6703 | `deleteCollectedData` |
| 6769–6805 | `performPerfStatsExport` |

### 3r. → `ui/settings/io/SettingsSwipeDataHandlers.kt`

| Lines | Declaration |
|---|---|
| 6705–6713 | `exportSwipeDataJSON` |
| 6715–6723 | `exportSwipeDataNDJSON` |
| 6725–6745 | `performSwipeDataJsonExport` |
| 6747–6767 | `performSwipeDataNdjsonExport` |

> **Coverage check:** every numbered declaration in the full inventory (companion → `performPerfStatsExport`) appears exactly once above, and the contiguous ranges in §3a–§3r tile lines 99–6806 with no gaps. ✅

---

## 4. Execution phases

Each phase ends green (compiles + tests pass) and is independently committable. Order is **least-risk first** so a regression is caught against the smallest possible change set. Run the per-phase verification before committing.

**Global verification commands** (per [CLAUDE.md](../CLAUDE.md) / MEMORY constraints — `grep` is shimmed, `./gradlew` needs termux-exec):
- Compile: `sh gradlew compileDebugKotlin`
- Pure tests: `sh gradlew runPureTests`
- Instrumented (settings UI lives here, so this is the real safety net): ew-cli with `--use-orchestrator --timeout 25m --device model=Pixel7,version=34` against the **debug** APK, `--outputs-dir ~/ew-output`. See `.claude/skills/ew-cli-testing.md`.

---

### Task 0: Baseline & safety net

- [x] **Step 1: Confirm green baseline.**
  Run: `sh gradlew compileDebugKotlin && sh gradlew runPureTests`
  Expected: BUILD SUCCESSFUL, all pure tests pass.
- [x] **Step 2: Record the settings instrumented-test inventory** so we can prove no behavior change later.
  Run: `command grep -rl "SettingsActivity\|SettingsSearch\|BackupRestore" src/androidTest`
  Expected: a list of test files (e.g. `SettingsSearchTest`). Note them; these are the regression gate.
- [x] **Step 3: Create the branch.**
  ```bash
  git checkout -b refactor/settings-decomposition
  ```

---

### Task 1: Widen visibility (the enabler — no files moved yet)

**Files:** Modify `SettingsActivity.kt` only.

- [x] **Step 1: Change `private` → `internal` on every member that extracted code will touch.** This is all ~150 state `var`s (244–486), the dialog/perf/collected-data state (701–712), `settingPositions`/`mainScrollState`/`composeScope` (484–486), `searchResultsNestedScrollConnection` (506–515), `searchableSettings` (596–633), and every `private fun` listed in §3. Mechanical find/replace at column-0 `    private ` → `    internal ` within the class is **too broad** (leaves genuinely-private helpers exposed); instead change only the declarations enumerated in §3. Leave `companion object` internals and launchers as-is.
- [x] **Step 2: Compile.**
  Run: `sh gradlew compileDebugKotlin`
  Expected: BUILD SUCCESSFUL (visibility widening alone never breaks compilation).
- [x] **Step 3: Commit.**
  ```bash
  git add src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt
  git commit -m "refactor(settings): widen members to internal to enable file split"
  ```

---

### Task 2: Extract leaf UI (zero state dependencies first)

Extract in this sub-order; compile after **each** file so a bad import is caught immediately.

**Files:** Create `ui/settings/SettingsControls.kt`, `SettingsInfoCards.kt`, `SettingsDialogs.kt`, `SettingsPrefsExt.kt`.

- [x] **Step 1: Create `SettingsControls.kt`** with the 5 composables from §3d, each rewritten from `private fun X(` to `internal fun SettingsActivity.X(` (the controls don't even need the receiver, but keeping it uniform avoids churn). Add the package line `package tribixbite.cleverkeys` (or `…ui.settings` — see Task-9 note) and Compose imports. Delete lines 4401–4700 from `SettingsActivity.kt`.
- [x] **Step 2: Compile.** Run: `sh gradlew compileDebugKotlin` — Expected: SUCCESSFUL.
- [x] **Step 3: Create `SettingsPrefsExt.kt`** (§3g, lines 5132–5212). Delete from Activity. Compile.
- [x] **Step 4: Create `SettingsInfoCards.kt`** (§3e — note this pulls `loadVersionInfo` 5735–5753 too). Delete from Activity. Compile.
- [x] **Step 5: Create `SettingsDialogs.kt`** (§3f, 4892–5127). Delete from Activity. Compile.
- [x] **Step 6: Run pure tests.** Run: `sh gradlew runPureTests` — Expected: PASS.
- [x] **Step 7: Commit.**
  ```bash
  git add -A && git commit -m "refactor(settings): extract reusable controls, info cards, dialogs, prefs ext"
  ```

---

### Task 3: Extract the search subsystem

**Files:** Create `ui/settings/SettingsSearch.kt`. Modify `SettingsActivity.kt`.

- [x] **Step 1: Move §3h declarations** into `SettingsSearch.kt` as `SettingsActivity` extensions. Keep `searchableSettings` lazy val and `searchResultsNestedScrollConnection` in the Activity (per §1 gotcha). Delete the moved ranges (489–503, 518–536, 545–552, 558–594, 636–698) from the Activity.
- [x] **Step 2: Compile + pure tests.** Run: `sh gradlew compileDebugKotlin && sh gradlew runPureTests` — Expected: PASS, **including `SettingsSearchTest`** (the search-to-scroll regression guard).
- [x] **Step 3: Commit.**
  ```bash
  git add -A && git commit -m "refactor(settings): extract search/scroll subsystem"
  ```

---

### Task 4: Extract IO handlers (grouped by domain)

**Files:** Create the 7 files under `ui/settings/io/` (§3l–§3r). Modify `SettingsActivity.kt` (delete moved bodies) and the `onNewIntent` override (delegate to `handleGifPackShareIntent`).

- [x] **Step 1: For each domain file (Backup, Dictionary, Clipboard, LanguagePack, Gif, PrivacyData, SwipeData),** move its functions per §3l–§3r as `SettingsActivity` extensions, then delete the originals. The `registerForActivityResult` launchers (141–237) STAY; verify their method references (e.g. `::performConfigExport`) still resolve to the now-external extension functions — they do, because extensions are members-by-receiver at the call site. Compile after each file.
- [x] **Step 2: Rewrite `onNewIntent` (6079–6082)** to a one-line `handleGifPackShareIntent(intent)` delegate.
- [x] **Step 3: Compile + pure tests.** Run: `sh gradlew compileDebugKotlin && sh gradlew runPureTests` — Expected: PASS.
- [x] **Step 4: Commit.**
  ```bash
  git add -A && git commit -m "refactor(settings): extract import/export & data handlers by domain"
  ```

---

### Task 5: Extract persistence, presets, navigation

**Files:** Create `ui/settings/SettingsPersistence.kt`, `SettingsResetPresets.kt`, `SettingsNavigation.kt`.

- [x] **Step 1: Move §3i** (`loadCurrentSettings`, `saveSetting`, `updateConfigFromSettings`) into `SettingsPersistence.kt`. Move the **body** of `onSharedPreferenceChanged` (860–1062) into `internal fun SettingsActivity.handlePreferenceChanged(prefs, key)` in the same file; reduce the override to `override fun onSharedPreferenceChanged(p, k) = handlePreferenceChanged(p, k)`.
- [x] **Step 2: Move §3j** into `SettingsResetPresets.kt`, **§3k** into `SettingsNavigation.kt`. Delete originals.
- [x] **Step 3: Compile + pure tests.** Run: `sh gradlew compileDebugKotlin && sh gradlew runPureTests` — Expected: PASS.
- [x] **Step 4: Commit.**
  ```bash
  git add -A && git commit -m "refactor(settings): extract persistence, reset/presets, navigation"
  ```

---

### Task 6: Extract the 17 section composables (the big payoff)

**Files:** Create the 17 files in `ui/settings/sections/`. Create `ui/settings/SettingsScreen.kt`. Heavily reduce `SettingsActivity.kt`'s `SettingsScreen`.

- [x] **Step 1: Move `SettingsScreen` (1066–4388)** into `SettingsScreen.kt` as `@Composable internal fun SettingsActivity.SettingsScreen()`. Keep the scaffold (1066–1209) and dialog hosts.
- [x] **Step 2: For each section block (§3c),** cut the `CollapsibleSettingsSection(...) { … }` block into `@Composable internal fun SettingsActivity.<Name>Section()` in its own file, and replace the inline block in `SettingsScreen` with a call `TestKeyboardSection()`, `ActivitiesSection()`, …, preserving original order. Compile after every **3–4** sections (catches a mis-scoped state read early).
- [x] **Step 3: Update `onCreate`'s `setContent { … SettingsScreen() }`** — no signature change (still an extension on the same receiver), so this should be a no-op; verify it resolves.
- [x] **Step 4: Compile + pure tests.** Run: `sh gradlew compileDebugKotlin && sh gradlew runPureTests` — Expected: PASS.
- [x] **Step 5: Build the debug APK and run the instrumented settings tests** (this is the only layer that exercises the composables end-to-end):
  rebuild debug APK, then ew-cli per §4 globals filtering to the settings test classes recorded in Task 0.
  Expected: same pass count as the Task-0 baseline (no regressions).
- [x] **Step 6: Commit.**
  ```bash
  git add -A && git commit -m "refactor(settings): split SettingsScreen into 17 per-section composables"
  ```

---

### Task 7: Verify the size goal is met

- [x] **Step 1: Confirm no production file in the settings set is ≥ 1000 lines.**
  Run:
  ```bash
  python3 -c "import os,glob; [print(sum(1 for _ in open(f)),f) for f in glob.glob('src/main/kotlin/tribixbite/cleverkeys/**/Settings*.kt',recursive=True)+glob.glob('src/main/kotlin/tribixbite/cleverkeys/ui/settings/**/*.kt',recursive=True) if sum(1 for _ in open(f))>=500]"
  ```
  Expected: only `SettingsActivity.kt` (~650) and possibly `SettingsPersistence.kt` (~500); **nothing ≥ 1000.**
- [x] **Step 2: Full instrumented suite** (whole keyboard, not just settings) to confirm nothing downstream broke. Expected: matches pre-refactor pass count.
- [x] **Step 3: Update `memory/todo.md`** marking the decomposition done; conventional commit.

---

### Task 8 (OPTIONAL — defer): True state hoisting into a holder

Only attempt after Tasks 1–7 are merged and stable. This is the higher-risk, higher-reward step that converts the section composables from `SettingsActivity` extensions into **stateless** composables.

**Files:** Create `ui/settings/SettingsUiState.kt` (or `SettingsViewModel`). Modify every section file + `SettingsActivity.kt`.

- [ ] **Step 1: Move the ~150 `mutableStateOf` vars (244–486) + dialog/data state (701–712)** into a `class SettingsUiState` (plain holder with `mutableStateOf` properties) or a `SettingsViewModel : ViewModel()`. Given the codebase already uses `by viewModels()` (line 138), a `SettingsViewModel` matches the established pattern.
- [ ] **Step 2: Change section composables** from `SettingsActivity.AppearanceSection()` to `AppearanceSection(state: SettingsUiState, onSave: (String, Any) -> Unit, …)` — pure, previewable, testable in isolation.
- [ ] **Step 3: Move persistence/IO/search/navigation** off `SettingsActivity` extensions onto the ViewModel (or a `SettingsRepository`).
- [ ] **Step 4: Full pure + instrumented suites** (this phase changes data flow, so it is NOT behavior-trivial).
- [ ] **Step 5: Commit** — `SettingsActivity.kt` now drops to ~400 lines (lifecycle + launchers + `setContent`).

> **Recommendation:** Ship Tasks 1–7 first. They achieve the stated goal (kill the 1000+ line file, fully traceable) with near-zero behavior risk. Task 8 is a quality improvement (testable stateless composables) but is a genuine rewrite of data flow — treat it as a separate project with its own review.

---

## 5. Self-review

- **Coverage:** §3a–§3r tile lines 99–6806 contiguously; the inventory's every declaration is mapped exactly once (verified in §3 coverage check). ✅
- **Type/name consistency:** the delegating override `onSharedPreferenceChanged` → `handlePreferenceChanged` uses one name throughout (Tasks 5 & §3i). `handleGifPackShareIntent` is the single name used by both the `onNewIntent` delegate and §3p. ✅
- **Known residual > 500 lines:** `SettingsActivity.kt` (~650, Phase A) and `SettingsPersistence.kt` (~500). Both are < 1000 (goal met). If `SettingsPersistence.kt` measures over budget after extraction, split `loadCurrentSettings` (230 lines) into `SettingsLoad.kt` — flagged here so it is not a silent surprise.
- **Risk register:** the only behavior-bearing transformation in Tasks 1–7 is the `onSharedPreferenceChanged` body move (Task 5); everything else is pure relocation. That move is covered by the instrumented settings tests. Launchers and lazy `searchableSettings` deliberately stay put (§1).

---

## 6. Execution handoff

Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per Task (1–7), review the diff between each. Fast iteration, and each task is independently green/committable, which fits this plan perfectly.
2. **Inline Execution** — execute Tasks 1–7 in one session with a compile/test checkpoint after each.

Which approach?
