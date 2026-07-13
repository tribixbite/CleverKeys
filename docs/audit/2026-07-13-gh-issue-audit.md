# GitHub Issue Audit — CleverKeys (2026-07-13)

Repo: `tribixbite/CleverKeys` · Branch: `main` · Open issues at audit time: 58

---

## 1. Verified-Fixed Table

Issues that are open on GitHub but have confirmed fixes in the repo, plus the internal
fix campaigns from June–July 2026.

### 1a. Open GH Issues — Already Fixed in Repo

| Issue/Item | Fix commit(s) | Date | How verified (test name + gate) |
|---|---|---|---|
| **#70** — Programmatic Intent import via json_base64 | `6888f77f7` | 2026-03-17 | `IssueRegressionTest.issue 70 — base64 JSON roundtrip is lossless` (runPureTests) |
| **#71** — Opening clipboard freezes/crashes device 2–3s | `e159dca56` | 2026-03-18 | Async DB loads; instrumented ClipboardDatabaseTest (ew-cli gate 1309/1309 2026-07-04) |
| **#72** — Capitalize "I" (suggestions) | Config `autocapitalize_i_words`, wired in `SuggestionHandler.kt` + `InputCoordinator.kt` | pre-audit | `IssueRegressionTest.issue 72 — autocapitalize I words enabled by default` (runPureTests); `SettingsToggleTest.testConfigAutonSetting` (ew-cli) |
| **#77** — Cannot completely disable Greek/Math toggle on Numeric Layer | `5a4b4ad2e` | 2026-01-22 | Instrumented ew-cli gate |
| **#78** — Suggestion appends instead of replaces in non-composing apps (Termux, Fennec, Google Keep) | `f30755f2b` | 2026-04-26 | `Issue78SuggestionReplaceTest` (runPureTests); ew-cli instrumented 1309/1309 |
| **#87** — When swipe typing is disabled, long swipes should fall back to short swipes | `a4a5a95f5` | pre-audit (gesture sprint) | GestureTest (runPureTests); `swipe_typing_enabled` gates all word-swipe routes (`231cb041b`) |
| **#93** — Custom Themes: add hex color input field | `26ed453e4` | 2026-04-26 | `Issue93ThemeHexInputComposeTest` (ew-cli instrumented 1309/1309) |
| **#94** — Copy version info on long-press in Settings | `5661375e6` | 2026-04-26 | `Issue94VersionCopyComposeTest` (ew-cli instrumented 1309/1309) |
| **#96** — Dictionary search resets after adjusting activity | `d92a1b91c` | 2026-02-11 | `IssueRegressionTest.issue 96 — refresh re-applies current search query and sort type` (runPureTests) |
| **#101** — Autocorrect / gesture recognition improvements (keyboard-adjacency scoring, contraction alias routing) | `2213f3b2f`, `39b9b5258` | 2026-05-21 | `AutocorrectTest` + `KeyAdjacencyTest` (ew-cli 79/79 batched 2026-07-13) |
| **#110** — Backspace undo swipe + autocorrect undo | `8d4bdf19f`, `d72ea9555`, `3a030003d` | pre-audit | `BackspaceUndoTest` (runPureTests); `BackspaceUndoInstrumentedTest` (ew-cli) |
| **#118** — Emoji panel renders "…" instead of glyphs on high-DPI/custom-font devices | `0696b4521` | 2026-04-27 | `Issue118EmojiOverflowTest` (runPureTests) |
| **#130** — Clipboard doesn't follow Custom Theme colors | `578f60534`, `91fb497f1` | 2026-06-02 | `ClipboardThemeTest` (ew-cli 1309/1309) |
| **#133** — Character size for secondary keys | `667938fc9`, `625c67a6f` | 2026-06-02 | `IssueRegressionTest.issue 133 — Defaults exposes SUBLABEL_TEXT_SIZE_FACTOR` (runPureTests 1288 green) |
| **#134** — Short key customization: "Show Keyboard" button disappears | `92e8bb576` | 2026-04-26 | `Issue134ShowKeyboardButtonComposeTest` (ew-cli instrumented 1309/1309) |
| **#135** — Add `clear` action (selectAll + delete) | `625c67a6f` | 2026-04-26 | `IssueRegressionTest.issue 135 — CommandRegistry includes clear command` (runPureTests) |
| **#136** — Swipe stops working (neural_user_max_seq_length exceeds model max) | `6f0c1dafd` | 2026-04-29 | `Issue136MaxSeqLengthClampTest` (runPureTests) |
| **#141** — Timestamp keys cannot be assigned via Short Swipe Customization | `44f58b3e7` | 2026-05-22 | Instrumented customization tests (ew-cli) |
| **#142** — One-click full backup as dated ZIP | `ef6443589` | 2026-05-22 | `BackupRestoreFullBackupTest` (runPureTests 1288); ew-cli BackupRestore tests 79/79 |
| **#145** — Custom-mapping gestures stop working on reboot when swipe typing is disabled | `3a947623f` (race fix, 2026-06-11) + `231cb041b` (gate fix) | 2026-06-11 / pre-audit | Mapping persistence: `customMapping_beatsWordCandidate` (pure GesturePrefAccessDriftTest); gate: `swipe_typing_enabled` integration (GestureTest) |
| **#49** — Turkish language support | `langpack-tr.zip` + `latn_qwerty_tr.xml` layout | pre-audit | Dictionary build pipeline verification; layout confirmed in `srcs/layouts/` |
| **#68** — Greek dictionary | `0fb56dc09` (Greek pack), `6f5517979` (Russian follow-up) | pre-audit | `langpack-el.zip` + `langpack-ru.zip` in `scripts/dictionaries/`; MEMORY.md verification |
| **#26** — Clarify language support + update README comparison table | README updated with 11-language count + Urik column | 2026-04-26 area | README `Open-Source Gesture Keyboard Comparison` table includes FUTO, Urik, multilingual footnotes |
| **#111** — Expanded comparison table (add Urik) | README comparison table | 2026-04-26 area | README table includes Urik column with 5 features |

### 1b. Internal Fix Campaigns — June–July 2026 (seed list verified)

All hashes confirmed present via `git show -s`; dates from `git show --format=%ci`.

| Item | Fix commit | Date | How verified |
|---|---|---|---|
| **AC-1** Autocorrect min-freq default 500 → 100; slider range 100..2000 | `f179c50f3` | 2026-07-13 | `AutocorrectDefaultsDriftTest` + `FrequencyFloorTest` (runPureTests 1288); ew-cli 79/79 |
| **AC-2** Custom/user words blocked as correction targets by floor | `a813969da` | 2026-07-13 | `AutocorrectTest.customWordExemptFromFloor` (ew-cli 79/79) |
| **AC-3** `OptimizedVocabulary` pre-config min-freq init aligned to Defaults (100) | `c1c0ef519` | 2026-07-13 | Same FrequencyFloor drift test; runPureTests 1288 green |
| **Autocorrect broight sub-cap** — single-typo match beats higher-freq lookalike | `1c9709f3e` | 2026-06-16 | `AutocorrectTest` calibrated cases (ew-cli) |
| **Autocorrect disabled-word target exemption** | `dfddb6d1e` | 2026-06-17 | `AutocorrectTest.disabled word not offered as correction target` (ew-cli) |
| **Autocorrect freq-floor scale to dictionary** | `62a5f8d4d` | 2026-06-22 | `FrequencyFloorTest` (runPureTests) |
| **Autocorrect possessive guard** ("ember's" no longer → "rivers") | `da78b98e2` | 2026-06-24 | `AutocorrectTest.possessive of known noun preserved` (ew-cli 79/79) |
| **Autocorrect Damerau transposition** (teh/hte/becuase/freind class) | `f12b44d89` | 2026-07-03 | `AutocorrectTest` transposition cases; runPureTests 1288 |
| **Autocorrect alias tiebreak** (alias can no longer overtake stronger match) | `db58c3257` | 2026-07-03 | `AutocorrectTest.alias does not overtake stronger match` (ew-cli) |
| **PERF** `weightedEditDistance`: memoize keyDistance + early-abandon DP + exact-gate-before-weight | `8812a8cf0` | 2026-07-13 | `KeyAdjacencyTest` (runPureTests); measured ~145→58ms on 98k bin |
| **Dictionary V4** 98,140-word English (was 52,002) | `5ca6c4025` | 2026-07-03 | `DictionaryBinFormatTest` (runPureTests); held-out eval 50% user-word coverage |
| **Dictionary junk removal** (40 corpus-noise entries + build-time filter) | `0a9f4e6d0` | 2026-06-21 | Build-time `generate_binary_dict.py` junk filter; `DictionaryBinFormatTest` |
| **DICT-1** Gradle task cannot silently downgrade en bin V2(CKDT)→V1(DICT) | `1c7c3c970` | 2026-07-13 | `DictionaryBinFormatTest.en_enhanced_bin_is_CKDT_v2` (runPureTests) |
| **DICT-2/3/5/6 + DOC-1/2/3 + CLEAN-1** Dictionary polish (accented-word filter, freq clamp, docs, stale artifacts) | `343d61e30` | 2026-07-13 | runPureTests 1288 green |
| **DEC-1** `gif_enabled` search gate incomplete (3 sites) | `935c6a117` | 2026-07-13 | `SettingsSearchCoverageTest.every_gatedBy_key_has_isGateEnabled_branch` (runPureTests) |
| **DEC-2** `collapseAllSections()` omits `testKeyboardExpanded` | `935c6a117` | 2026-07-13 | `SettingsSearchTest` (ew-cli 79/79) |
| **DEC-3/4/5/7/8/9** Dead state vars removed; SettingsNavigation/dead imports/launch polished | `3096ea17c` | 2026-07-13 | Compile + runPureTests 1288 green; ew-cli 79/79 |
| **SettingsActivity decomposition** 6,806→686 lines; 33 focused files | `bd893c073` merge | 2026-07-12 | ew-cli full suite 1309/1309 green (2026-07-04) |
| **T8 SettingsViewModel** — rotation-survivable settings state | `9a6504452` | 2026-07-13 | `SettingsViewModelRotationTest` (ew-cli 79/79 2026-07-13) |

---

## 2. Outstanding Table

Open GH issues that are **not yet fixed** in the current codebase.

| # | Title | Type | Triage note |
|---|---|---|---|
| **#31** | Next word prediction | Feature | BigramModel.kt exists but not wired into the prediction pipeline as next-word suggestions; substantial ML/architecture work needed |
| **#35** | Overly dark dark mode / no follow-system light mode | Bug | PredefinedThemes.kt has 18 dark-leaning themes; no AppCompatDelegate DayNight or follow-system option; Settings Activity itself is always dark |
| **#52** | MessageEase layout contribution / Gesture Tuning UX | Feature | Layout XML could be added (low effort) but the "gesture tuning" / visual customization requested is substantial |
| **#58** | Scaling number keyboard | Feature | Keyboard height scales but number panel doesn't scale independently; Config/UI work |
| **#61** | Actively switch many languages (hot-swap) | Feature | 4-slot language config exists; the request is for on-screen quick-switching UI; medium UI effort |
| **#69** | Two-finger swiping | Feature | No multi-touch swipe gesture support; requires significant touch-handling architecture |
| **#75** | Swipe behaviour on Swiss French (QWERTZ Y/Z) | Bug | ONNX model trained on QWERTY coordinates; QWERTZ key positions not corrected before encoding — needs coordinate normalization per-layout |
| **#79** | UI/Header Flickering at top of Settings during scrolling | Bug | Compose LazyColumn recomposition jank; no targeted fix found; decomposition may have changed recomposition scope but no regression test covers it |
| **#80** | Clipboard Suggestion Strip & UI Navigation Improvements | Feature | No clipboard paste-suggestion strip in keyboard row; back/close button in sub-panels; medium effort |
| **#83** | "keys per direction" (sublabel short swipes) not honored at average swipe length | Bug | Symptom: the short/long boundary ignores sublabel key directionality at mid-range lengths; gesture routing audit did not explicitly address this case |
| **#84** | Smart Punctuation (threshold interval) | Feature | No auto-smart-punctuation logic exists |
| **#88** | Arabic language support | Feature | Arab layouts exist in srcs/layouts/ (arab_*.xml) but no Arabic langpack or ONNX Arabic swipe model |
| **#90** | Custom keyboard/row height | Feature | `keyboard_height_portrait` pref exists; row-level independent sizing not supported |
| **#115** | Foldable phone usability (split-screen + layout) | Feature | No foldable/split-screen layout adaptation |
| **#120** | Keypress sounds | Feature | Not implemented; VibratorCompat.kt exists for haptics but no audio path |
| **#121** | Custom fonts | Feature | KeyboardTypography.kt exists; custom font loading not exposed to user |
| **#128** | Lazy loading services (~300 MB runtime) | Feature | `c0189936b` removed runBlocking in cleanup (unrelated); dictionary lazy-loading (DictionaryManager) exists but ONNX models load at first keystroke; overall memory footprint remains a concern |
| **#137** | Offline Speech-to-Text (Whisper.cpp) | Feature | No STT integration; large scope |
| **#139** | Changing the bottom row | Feature | Short-swipe customization covers per-key actions but not per-row layout rearrangement |
| **#140** | Auto-insert highlighted suggestion on spacebar | Feature | Space currently commits the typed word, not the highlighted suggestion; small but intentional divergence |
| **#143** | Enhanced Trackpad Mode | Feature | Design spec exists at `docs/specs/`; not implemented |
| **#147** | Option to remove/disable long swipe gestures | Feature | `swipe_typing_enabled=false` disables all word swipes; there is no "keep short swipes only, drop long swipes" toggle distinct from disabling swipe entirely |
| **#148** | Keyboard body disappears when clipboard opens with prediction off | Bug | Specific to HyperOS + prediction disabled; no targeted fix; root cause unknown |
| **#149** | GIF zip packs show broken giphy.com URLs | Bug | GifPackManager expects `thumbs/` WebP files in the ZIP; if the user's pack was built with embedded giphy URLs (old pipeline), the app can't display them; `make_pack.py` generates valid packs but the user downloaded a legacy pack |
| **#151** | Tapping suggested word leaves partial word in some apps (Vanadium URL bar) | Bug | Same root cause as #78 (non-composing text). The #78 fix (`f30755f2b`) explicitly covered Fennec address bar but Vanadium may use a different InputType that bypasses the fix; needs targeted reproduction |
| **#152** | Full GIF pack (130k) unusably slow | Bug | GifGridView.kt caps Coil image cache at 32MB; 130k thumbnails at ~20KB each = 2.6GB → needs DB pagination + virtual/lazy grid; significant work |
| **#154** | Vibration delay on keypress | Bug | No system-default haptic option; VibratorCompat.kt uses `VibrationEffect.createOneShot`; delay is likely device-level (HyperOS); low-hanging fix: try `VibrationEffect.EFFECT_CLICK` for lower latency |
| **#156** | Encrypted Clipboard | Feature | No encryption layer; large scope (key management, SQLCipher integration) |
| **#158** | Arabizi support (numbers inside words treated as word characters) | Feature | No word-boundary model change; affects ContextTracker and WordPredictor |

### Outstanding P2 tail from 2026-07-13 post-decomposition review

| Item | File | Fix |
|---|---|---|
| **DICT-4** Unify blocklists: `generate_binary_dict.ENGLISH_JUNK_BLOCKLIST` should read `en_blocklist.txt` | `scripts/build_en_wordlist.py` | Low effort; latent correctness gap |
| **DEC-6** `fallbackEncrypted()` misfiled in `SettingsResetPresets.kt` (lifecycle, not presets) | `SettingsResetPresets.kt:181` | One-line move |
| ~~**AC-4** Possessive-typo (`"embeer's"`) neither corrected nor handled — add TODO + optional base-correct + re-append `'s`~~ ✅ done 2026-07-13 (base-corrected recursively + elongation collapse; see Addendum) | `WordPredictor.kt ~1907` | ~~Medium; add TODO now, implement later~~ |
| **AC-5** KDoc note: `isAdjacentTransposition`/`AutocorrectCandidate` — distant-char alias transpositions can lose to 1-sub competitor (documented edge) | autocorrect/ | Docs only |
| ~~**TEST-1 tail** Morphology-guard vs transposition (`gamees`), capitalized alias winner (`Hadnr`), slider end-to-end~~ ✅ done 2026-07-13 (AutoCorrectEndToEndTest JVM + 6 instrumented cases; see Addendum) | `AutocorrectTest` (instrumented) | ~~3 new test cases~~ |
| **en.txt apostrophe review** — 105 apostrophe entries in calibration list; `alot` is blocklisted but listed | `scripts/dictionaries/en/en.txt` | Low effort cleanup |
| **ONNX .sh audit** — 8 dead ONNX-era scripts in `scripts/*.sh` | `scripts/` | Careful audit before delete |

---

## 3. Recommended Next Fixes

Clearest, smallest real **bugs** (not features), ranked by signal-to-effort ratio:

1. **#154 Vibration delay (HyperOS)** — Try `VibrationEffect.EFFECT_CLICK` (or `EFFECT_TICK`) in `VibratorCompat.kt` in place of `createOneShot(duration, amplitude)`. Zero-risk, 2-line change. If it helps, expose a "System default haptic" option that forwards to the OS effect constant.

2. **#35 No light/follow-system mode** — `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM)` in the Settings Activity + a "Follow System" entry in the theme picker. Medium: requires a light-mode color scheme but the ThemeProvider infrastructure is ready.

3. **#151 Partial word in Vanadium URL bar** — Reproduce with `InputType.TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI`; if the #78 fix misses it, add a `TYPE_TEXT_VARIATION_URI` branch to the composing-text fallback scan in `SuggestionHandler`. Estimated 1–3 hours once reproduced.

4. **#75 Swiss French QWERTZ Y/Z swap** — The ONNX encoder uses raw touch coordinates; a QWERTZ layout has Y and Z swapped relative to QWERTY. Add per-layout coordinate remapping in `InputCoordinator` before trace encoding (remap Y↔Z column x-positions for QWERTZ layouts). Test by swiping "yes"/"zone" on `latn_qwertz_*.xml`.

5. **#83 "keys per direction" ignored at average swipe length** — The symptom points to the boundary check in `InputCoordinator.handleShortGesture`: at mid-length swipes the recognizer may be classifying as SWIPE (word candidate) rather than SHORT_SWIPE (sublabel). Add a test case that proves which branch fires at 100px with a sublabel-bearing key.

6. **#148 Keyboard body disappears (clipboard + prediction off)** — Low-effort investigation: check whether `CleverKeysService.onStartInputView` adjusts `SOFT_INPUT_ADJUST_RESIZE` vs `ADJUST_NOTHING` differently when prediction is disabled; the height negotiation may collapse the keyboard pane. High user-facing impact.

---

## Counts

- Open GH issues at audit: **58**
- Verified fixed (open on GH, already in repo): **24** (items in §1a)
- Internal campaign items verified: **19** (§1b)
- Outstanding (genuinely not fixed): **30** issues + 7 P2 tail items
- Of outstanding: **8 bugs** (35, 75, 79, 83, 148, 149/152 GIF, 151, 154), **22 features**

### Addendum (post-audit fixes)

| Item | Fix commit | Date | Verification |
|---|---|---|---|
| URL/email/path autocorrect corruption (user-reported 2026-07-13) | (this commit) | 2026-07-13 | TDD: AutocorrectUrlGuardTest verified RED pre-fix then 7/7 GREEN + AutocorrectTest 49/49 (ew Pixel7 API34); AutocorrectContextGuardTest 10 pure |
| #154 Vibration delay on keypress | fa00cb0ae | 2026-07-13 | Root cause: settings layer forced vibrate_custom=true on every save, blocking the fast performHapticFeedback path. HapticsBehaviorDriftTest (3, red-first); 1301 pure green. Needs on-device feel check. |
| #154 follow-up: one-time stale `vibrate_custom` migration | (this commit) | 2026-07-13 | DONE. `Config.migrateForcedVibrateCustom()` (initGlobalConfig) clears bug-forced `vibrate_custom=true` when `vibrate_duration` is still at default (20); keeps it if user customized. Marker `vibrate_custom_migration_v1` persisted unconditionally (INTERNAL_KEYS-classified). Pure decision fn `HapticsMigration.shouldClearForcedVibrateCustom`; +6 HapticsBehaviorDriftTest cases (red-first via compile fail); 1307 pure green. |
| **AC-4** Possessive-typo base correction (`embeer's → ember's`) + **TEST-1 tail** (`gamees`, `Hadnr`, floor slider e2e) | (this commit) | 2026-07-13 | TDD: new JVM harness AutoCorrectEndToEndTest (real 98k dict + aliases via Objenesis/MockK, runMockTests) verified RED pre-fix (`embeer's→rivers`, `teh's→true`, `gamees` frozen by morph guard) then 13/13 GREEN; mock suite 223, pure 1301; 6 instrumented AutocorrectTest cases added (compile-gated, ew run pending) |
