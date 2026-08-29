# Pre-archive verification of the July–August audit corpus

**Date:** 2026-08-28 · **Verified at HEAD:** `fddb19b0` · **Method:** 10 independent line-by-line
verification passes (one per doc group), each reading its docs in full and re-checking every
finding/recommendation/design-commitment against live source. Tracking baseline for "already
tracked": `memory/HANDOFF.md` + `docs/audit/2026-08-25-remediation-verification.md`.

**Scope:** the 26 audit/remediation docs staged for archival to `docs/history/audits/`
(everything in `docs/audit/` except the v1.5 delta trio, the 2026-08-25 verification, and this
file; `156-at-rest-clipboard-encryption.md` moves to `docs/plans/` as a live design).

**Aggregate:** ~430 finding-instances checked · ~330 verified implemented/superseded/tracked ·
**46 distinct LEAKED items** (valid at HEAD, tracked nowhere live before this doc). Leaked ≠
regression: most are deferred items whose only record was in the docs being archived.

One doc verified fully clean: `2026-07-18-accessibility-implementation-plan.md` (all three
⚠-skeleton corrections implemented, residuals already tracked as CK-150-027/-029).

---

## P2 items (fix or explicitly decide; ordered by user impact)

| ID | Item | Evidence |
|---|---|---|
| ARC-001 | **Private media rows can reach the OS clipboard.** Private media entries are constructible (sticky dedup merge via `getPrivateMarker` content-match `ClipboardDatabase.kt:579-592,:805-815`; import writes `media_path`+`is_private` uncoupled `:1649,:1706,:1791`; `ClipboardDatabaseV5MigrationTest:196-227` builds one) and the media-paste `commitContent`→`setPrimaryClip` fallback is unconditional (`KeyEventHandler.kt:204-217`). §5.6 of the private-copy design promised a gate + TODO; both absent. Fix: thread `is_private` through `ClipboardHistoryService.pasteMedia`, fail instead of falling back; add a test. | private-copy design §5.6 |
| ARC-002 | **#148 root-caused** (was "root cause unknown"): with `word_prediction_enabled` and `swipe_typing_enabled` both off, `PredictionViewSetup.kt:75` returns `contentPaneContainer = null`, so clipboard/emoji/GIF openers take the `setInputView(pane)` fallback (`KeyboardReceiver.kt:230-232,:296-297,:327`) and replace the whole keyboard until pane close (`:442-444`). Deterministic, device-independent. Fix: minimal container in the predictions-disabled branch. | gh-issue audit #148 |
| ARC-003 | **PII log class still open**: 12 ungated user-text `Log.d` sites survive release (minify off): `WordPredictor.kt:2168,2234,2255,2295,2303,2306,2534` (typed word + correction), `EmojiSearchManager.kt:109,270`, `DictionaryManager.kt:180`, `CustomShortSwipeExecutor.kt:70,430`, `SwipeMLDataStore.kt:338`. And `CoreImeHygieneDriftTest.kt:58-62` does not guard `ClipboardDatabase.kt`/`Keyboard2View.kt` (the files the original fix touched). Gate the sites + extend `piiSensitiveFiles`. | 2-data-security R3 |
| ARC-004 | **Learned-phrase delete is incomplete**: `BigramStore.removeBigram` (`:427-465`) leaves `(·,w1)→w2` trigrams; `TrigramStore` has no `removeTrigram`, and `ContextModel` prefers trigrams (`:340-348,:212-219`), so a deleted continuation still surfaces. Add `TrigramStore.removeContinuationsOf(w1,w2)` + cascade from `LearningDataSection.kt:295-298`. | context-LM rec §3.3 |
| ARC-005 | **`finger_occlusion_offset` dead on geometric**: applied only in `CtcEngineAdapter.kt:754-768`; `GeometricEngineAdapter.kt:259-263` snapshots unshifted, zero occlusion refs in `swipe/geometric/`. Engine-agnostic slider + copy (`GestureTuningSection.kt:326-337`, `strings.xml:137`) → dead knob for every geometric-served cell. Apply at `GesturePreprocessor` ingest or scope the string to CTC. | removed-settings audit fix #3 |
| ARC-006 | **`UnigramLanguageDetector` is write-only**: fed every commit (`SuggestionHandler.kt:1720-1725` → `PredictionCoordinator.kt:314`), but `getLanguageScores`/`getDetectedLanguage` (`:319-323`) have zero call sites since `OptimizedVocabulary` died; ADR-011 §E's promised pure test doesn't exist; the call is try/catch-swallowed. Delete the feed or give it a consumer; add the test either way. | neural-vs-ctc parity §1.2 |
| ARC-007 | ~~**Termux deletion strategy never decided** (WP9 R-1 step 7): `SuggestionHandler.kt:1092,1195,1586,1649,2220` retain key-event deletion branches, untested either way; the owed dedicated Termux instrumented test covers only the auto-space half. Decide keep-vs-unify + write the test.~~ **DECIDED 2026-08-29 — KEEP** (reversible). A terminal has no editable text buffer, so `deleteSurroundingText` over a pty-backed InputConnection is best-effort at most, while `KEYCODE_DEL`/Ctrl+W are the terminal's native vocabulary; unifying would trade a working path for an untested one with no user benefit. Decision record: `SuggestionHandler.isTermuxEditor` KDoc (also now the SINGLE detection point — six hand-rolled `packageName == "com.termux"` copies collapsed into it). Test: `TermuxDeletionInstrumentedTest`, 7 cases, Termux + ordinary-app control for REPLACE deletion, typed-partial deletion and delete-last-word. | 3-core-ime step 7 |
| ARC-008 | ~~**R8/ProGuard still off behind the 2025 "REPRODUCIBILITY TEST" comment** (`build.gradle:284-286`); `proguard-rules.pro` (244 lines) dormant.~~ **ENABLED 2026-08-29 for release only, ONE MANUAL SOAK OWED before any v1.6.0 tag.** Reflection-keep audit done (only production `Class.forName` is `MemoryProbe.kt:95` → a framework class; every reflective Gson bind target was already covered; five dead rules deleted — `SwipeResampler`, `SwipeDetector`, `onnx.SessionConfigurator`, and root-package `SwipeDirection`/`ActionType` that never matched since both enums live in `customization`). **Determinism PASS: two clean builds gave byte-identical APKs on all three ABIs**, refuting the 2025 fear. arm64 33,908,757 → 29,092,480 (-14.2%); DEX -54%. `shrinkResources` removed zero `raw:`/`xml:` resources; the only app resources dropped belong to the dead androidx.preference widget layer. **The soak cannot be discharged by ew-cli — that suite runs the unminified debug variant.** Two traps recorded in `memory/HANDOFF.md`: the coroutines `META-INF/services` excludes are no-ops that R8 now depends on, and `usage.txt` bare names mean inlined/merged, not deleted. | 3 docs independently |
| ARC-009 | **`.gitignore` fixture trap**: `:170` global `*.json` still swallows test fixtures (current goldens were `git add -f`'d; `src/androidTest/assets/dictionaries/en_enhanced.json` is silently ignored today). The multiscript plan requires six new per-script fixtures ×2 copies; nothing records the force-add requirement. Add `!src/test/resources/**/*.json` + `!src/androidTest/assets/**/*.json` before those land. | ctc exec brief |
| ARC-010 | ~~**`BigramModel` is a 174-pair hardcoded table feeding a live ≤10× tap-ranking multiplier** (`BigramModel.kt:62,184,228,271` → `WordPredictor.kt:2020`); `loadFromFile` (`:342`) has zero callers so the six shipped `assets/bigrams/*.json` are never read; the planned A/B venue (`SwipeCalibrationActivity`) was deleted. Load the assets or delete both.~~ **RESOLVED 2026-08-28** — assets loaded as the next-word seed's source of truth (`StaticBigramSeed.kt` + `BigramModel.loadStaticContinuationsAsync`); the multiplier keeps its hardcoded table for a measured reason, see the decision entry below. | 07-17 audit + roadmap WP4 |
| ARC-011 | **Provenance captured, never shown**: `source_package` written everywhere, zero UI consumers — no "via ⟨app⟩" line; the injection risk in the private-copy design §6.2/§6.6 was accepted *because of* this display. `docs/wiki/specs/clipboard/private-copy-spec.md:31,:62` claims it shipped. Render it + correct the spec. | private-copy design §6 |
| ARC-012 | **#79 settings header flicker**: unfixed, and the audit's "LazyColumn recomposition" diagnosis is wrong — the screen is `Column`+`verticalScroll` (`SettingsScreen.kt:92-99`). Re-diagnose before fixing. | gh-issue audit #79 |
| ARC-013 | ~~**UT-5 / UT-7 contraction-ranking deferrals never re-measured**: "doesnt"→"doesn't" top-1 rank post-contraction-rework unknown; no sentence-start ranking signal exists at all (0 hits for `sentenceStart|afterPeriod`; `capitalizeIWord` fixes casing only).~~ **MEASURED 2026-08-28, FIXED 2026-08-29.** UT-5 closed (all aliases rank 0). UT-7 was never a ranking problem: `id → i'd` was blocked by the tap path's `partial.length >= 3` paired-injection floor (the data has always carried the pairing), now owned by `ContractionInjectionPolicy` which admits first-person contractions at two characters — exactly one shipped base changes. The doubled `I'll` was one variant list holding `i'll` twice, because `ContractionManager.loadPairedContractions` merged onto the binary-derived pairs with a blind `add()` and the two English sources overlap on 599 of 2,258 bases. Device confirmation owed on the next ew-cli run. | v1.5.0 UT round |

## P3 items

**IME/engines**
- ARC-014 no geometric prewarm on mid-session language toggle → one synchronous 150–400 ms build (`prewarmGeometricEngine` has no language-switch trigger).
- ARC-015 `GeometricEngineAdapter.dictionaryMemo` single-slot (`:524`) — en↔fr toggle re-reads CKDT each switch; mirror `CtcEngineAdapter.trieMemos` 2-slot LRU.
- ARC-016 no test pins `CleanupHandler` executor shutdown; `SuggestionHandler.isPredictionExecutorShutdown()` (`:434`) has zero callers.
- ARC-017 owed `KeyEventHandlerSliderTest` for `moveCursorSel` d==0 (`KeyEventHandler.kt:817-819`).
- ARC-018 `pref_secondary_prediction_weight` tap-only; `CtcRankMerger.kt:20-27` hard-codes 1000/920 — thread pref or fix slider copy.
- ARC-019 CTC never contested on LOCAL combined (8,521 traces; geometric beat neural there) nor any robustness tier — now cheap via `CtcReplayEngine`.
- ~~ARC-020 next-word has no cold-start source (learned-only; dead until phrase typed ≥2× at ≥5%) — §4.2-2 static seed unadopted (see ARC-010).~~ **RESOLVED 2026-08-28** — `BigramModel.getPredictions` → `WordPredictor.getStaticNextWordSeed` → `NextWordPredictor.generate(staticSeed=…)`, a FILL-ONLY tier appended after the learned list is sorted, with scores capped below the learned floor so it can never displace real evidence. Runs inside the existing gate (no new gate read); seeded entries say "built-in, not learned" in the provenance sheet rather than reporting `seen 0×, 0%`, and the `NEXT_WORD` origin label dropped its now-inaccurate `(learned)` suffix.
- ARC-021 no test pins `DictionaryManager.kt:136` eviction-time `persistLearnedData()` (drift test pins the *other* flush path).
- ARC-022 backup drops learned trigrams (`BackupRestoreManager.kt:1124-1149`); spec `:272-274` overclaims "n-grams ride backup". Decide: export or state the loss.
- ARC-023 ~2 s EN trie cold build unbudgeted (`CtcEngineAdapter.kt:448-458`; `CtcLatencyGateTest` asserts no ceiling); precompiled-blob option untracked.
- ARC-024 `CtcPurityDriftTest` never written (`swipe/ctc/` pure today, NFR-1 unenforced).
- ARC-025 `CtcFeaturizer.kt:128-129` KDoc tells callers to use the production-dead 4/3 `normalizeRawX/Y` — contradicts `CtcEngineAdapter.kt:50-53`; trap for multiscript wiring.
- ARC-026 declined/undisposed knobs: `context_max_boost` (`ContextModel.kt:60-62`), `next_word_max_suggestions` (`NextWordPredictor.kt:21`) — expose or record as declined.

**Geometric decoder (deliberate OQ backlog, was recorded only in the geo-sloppy research doc)**
- ARC-027 OQ-9 direction-aware overshoot clamp in `PathScorer.endpointPenalty` (`:293-301`) — low cost, universal small lift.
- ARC-028 OQ-10 length-scaled ordering slack (Dvorak short-word reordering residual).
- ARC-029 OQ-11 reversal-count confidence signal (turn angles already computed and discarded, `GesturePreprocessor.kt:244-265`).
- ARC-030 SLOPPY prune-recall floors missing on weird (≥0.85) + Dvorak (≥0.90) accuracy tests; `GeoSloppyPruneRecallTest` asserts nothing.

**Data/backup/clipboard**
- ARC-031 drop `MAIN`/`DEFAULT` filter from exported `BackupRestoreActivity` (`AndroidManifest.xml:115-118`).
- ARC-032 no caller identity logged on headless backup path (`BackupRestoreActivity.kt:143-198`).
- ARC-033 `json_base64` decode uncapped + `cacheDir/import_base64_*.json` never deleted (`BackupRestoreActivity.kt:238-250`).
- ARC-034 clipboard import JSON arrays uncapped (`ClipboardDatabase.kt:1621,1671,1752`).
- ARC-035 `.ckenc` suffix never implemented but wiki claims it (`backup-restore.md:202-203` + HTML mirror) — implement or correct.
- ARC-036 encrypted-import provenance display absent (header timestamp decoded then discarded, `BackupRestoreManager.kt:646-647`; no 🔒 badge, no plaintext re-export notice) — §7 replay-risk acceptance leaned on it.
- ARC-037 private-copy toast unconditional (`PrivateCopyProcessTextActivity.kt:159`); promised suppressibility pref absent.
- ARC-038 #149 `GifPackManager.importPack` returns Success with `thumbCount=0` for legacy packs (`:104-123`) — reject instead.

**Engineering/CI/docs**
- ARC-039 `available()`-sized single unchecked `channel.read()` in `BinaryDictionaryLoader.kt:115,271,546` + `BinaryContractionLoader.kt:75` — adopt `readBytes()` + floor like `ModelLoader.kt:37-46`.
- ARC-040 duplicate `assembleDebug` per push (`ci.yml:36` + `build-apk.yml:69`); `build-apk.yml:7` header cites deleted `build.yml`.
- ARC-041 Actions SHA-pinning absent outside trivy; `action-gh-release@v1` (`build-apk.yml:106`) vs `@v2` elsewhere; no dependabot.yml.
- ARC-042 dangling `squoosh` gitlink (mode 160000, no `.gitmodules`) — `git submodule status` errors.
- ARC-043 `ModelLoader.kt:59-61,:228-230` EP-order docstrings contradict code (`:243` XNNPACK-first); QNN stub `:278-282` is a comment.
- ARC-044 androidTest assertion-weak: 271 `assertNotNull` / 0 `assertThat` (pure suite: 2,539) — start with the 6 curated release-gate classes.
- ARC-045 ~168 raw Compose `Text("…")` literals unextracted (LearningDataSection 21, IntentEditorDialog 19, CommandPaletteDialog 18, LayoutManagerActivity 18, …) — widen CK-150-030's backlog.
- ARC-046 web-demo F1/F2/F3 fixes have no committed regression gate (verification harness was a scratchpad file; tap path unexercised); Tailwind CDN vendoring (`demo/index.html:8`) tracked nowhere.
- ARC-047 delete the neural-era offline harness (`tools/test_cli_predict.py`, `scripts/run_futo100k_fixed*.sh`, `run_swipedata_20k.sh`, `test_onnx_simple.sh`) — their models were removed by ADR-011.
- ARC-048 architecture backlog, quietly worsening: `Config.globalConfig()` consumers 28→33 files (90 call sites), no `ConfigSnapshot`; 145-file flat package root; 6 `*Initializer` (841 lines) hand-wired; `WordPredictor` 2335→2636 lines, no interface; `SettingsActivity` 123 `mutableStateOf` fields + `SettingsScreen.kt:69-73` composition-body writes without `SideEffect{}` (the retained stale `composeScope` silently no-ops later scroll-to-setting launches); `CleverKeysService` static escape hatches (`:153,:176,:212`). Plan: `5-architecture.md` (archived).
- ARC-049 unexplained 2026-08-17 post-fix OOM (4.87 h, no app frames) — one long-run `MemoryProbe` + `dumpsys meminfo` on a current build closes or reopens it.
- ARC-050 FUTO eval-notes baselines (79.25/84.83/+5.88) predate the apostrophe-lexicon fix and are quoted as G2/G4 gate bars (`train-ctc-swipe-model.md:965,968,1066`) — annotate; re-run not worthwhile.

**Late additions (dropped during consolidation, restored 2026-08-28)**
- ARC-051 deprecated-pref plumbing survives: `SettingsPersistence.kt:183-206,:355-362` still parse
  six `DEPRECATED_KEYS` into `SettingsActivity.kt:394-401` fields with zero readers — delete both
  halves.
- ARC-052 deprecated keys can be resurrected: `SettingsResetPresets.kt:123` writes
  `swipe_fuzzy_match_mode`; `Config.kt:1203-1205` repairs three deprecated float keys. Fix + add a
  drift assertion that neither names a `DEPRECATED_KEYS` member.

**Dated addenda to the records above**

- **ARC-012 — #79 re-diagnosed and fixed (2026-08-29); the old diagnosis was wrong twice.**
  The archived audit blamed "LazyColumn recomposition". Not only is the screen
  `Column`+`verticalScroll`, `git show v1.2.5:src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt`
  shows it was that at the REPORTED version too — zero `LazyColumn` anywhere in
  `src/main/kotlin` at tag `ce562816`. A second surprise: **there is no header component at
  all.** The "header" is two plain `Text` composables inside the scrolling `Column`
  (`SettingsScreen.kt:102-115`); `rg` finds no `TopAppBar`, `scrollBehavior`, `stickyHeader`,
  `derivedStateOf`, `animateDpAsState`, `Modifier.shadow` or `tonalElevation` anywhere under
  `ui/settings/`. Nothing is pinned and nothing animates on scroll.
  **Actual defect:** `SettingsControls.kt:43` hoisted `mainScrollState?.value` into
  `CollapsibleSettingsSection`'s composition body, unconditionally (before the
  `if (sectionId != null)` guard, so all 18 sections paid it). `ScrollState.value` is
  snapshot-backed, so each scroll pixel invalidated all 18 section restart scopes; and since the
  `onGloballyPositioned` lambda **captured** the changed offset, each `Card`'s modifier was a new
  instance every frame, forcing a node-chain diff and a relayout inside the scrolling content —
  per-frame LAYOUT churn, not just recomposition, scaling with sections on screen. That matches
  the reporter's "specifically when interacting with the sections". Fixed by moving the read into
  the layout lambda, exactly as the other three call sites (`:167`, `:225`, `:296`) always did;
  the recorded value is byte-identical, so scroll-to-setting is unchanged. Amplifier also fixed:
  `SettingsSearch.kt:73` compiled a `Regex` on every `settingSlug` call, and `settingSlug` runs in
  the composition body of every switch/slider/dropdown — now a file-level constant.
  **Caveat, stated because it matters:** the offending hoist was introduced by `d2d0e456`
  (2026-07-03, the SettingsControls extraction), so it POSTDATES the January v1.2.5 report. It is
  a genuine defect in shipping code and plausibly what a user sees today, but it is not an
  explanation of the original report. #79 should not be closed on this fix alone — manual visual
  confirmation on a current build is owed. The one v1.2.5-era, top-edge-specific candidate left
  standing is a three-way inset conflict — `styles.xml:53-57` (`settingsTheme` sets
  `android:fitsSystemWindows=true`) vs `SettingsActivity.kt:674`
  (`WindowCompat.setDecorFitsSystemWindows(w, false)`) vs `SettingsScreen.kt:96`
  (`.statusBarsPadding()`), with decor and `android.R.id.content` backgrounds both blanked to
  TRANSPARENT at `:686-687`, leaving the status-bar band painted only by the Compose `Column`.
  The conflict is provable from source; that it produces a per-FRAME artifact is not (insets
  dispatch on window events). `adb shell setprop debug.hwui.show_dirty_regions true` while
  scrolling separates the two: status-bar strip only = insets, whole content area = the
  recomposition storm now fixed.
  Ruled out during the pass, so nobody re-checks them: `settingPositions` is a **plain**
  `mutableMapOf` (`SettingsActivity.kt:553`), not `mutableStateMapOf`, so the
  `onGloballyPositioned` → `recordSettingPosition` writes are not a layout-write-read loop; no
  `snapshotFlow`, scroll-driven `LaunchedEffect`, `animateContentSize`, `graphicsLayer` or custom
  overscroll exists in the settings tree; and there is no `AndroidView`/`SurfaceView` on the
  screen.
- **ARC-048's `SettingsScreen.kt:69-73` gap is NOT a flicker source — and is now closed
  (2026-08-29).** Its two targets, `mainScrollState` and `composeScope`
  (`SettingsActivity.kt:554-555`), are plain non-snapshot `var`s, so writing them from a
  composition body invalidates nothing and cannot recompose anything. The bug is purely the
  lifecycle one ARC-048 describes: the write also runs for ABANDONED compositions, whose
  `rememberCoroutineScope()` is cancelled, so the Activity retained a dead scope and later
  `scrollToSetting()` calls silently no-opped. Wrapped in `SideEffect {}` (safe: `rg` confirms
  the only readers are `SettingsSearch.kt:22,25` inside the event handler and the layout lambdas
  in `SettingsControls.kt` — nothing reads either field during composition, and `SideEffect` runs
  after composition is applied but before the frame's layout pass). Worth recording: the ARC-048
  line was the harmless composition-body write; the damaging one was 30 lines away in a different
  file and no audit had flagged it.

**Won't-fix / decisions recorded**
- **ARC-010 — the shipped assets load, but they do NOT reach the scoring multiplier (2026-08-28).**
  The six `assets/bigrams/<lang>_bigrams.json` files are now read (async, at `setContext` and
  every language switch) and are the source of truth for the next-word cold-start seed. They are
  deliberately kept OUT of `getContextualProbability` → `getContextMultiplier`, which keeps its
  hardcoded per-language table. Reason, found by reading the files rather than assuming: the
  asset values are **per-previous-word rank scores, not probabilities** — the 15 continuations of
  `"i"` in `en_bigrams.json` sum to 12.37, and every group descends from ~0.92 to a 0.75 floor.
  The multiplier's interpolation (`λ·P(w|prev) + (1−λ)·P(w)`, λ=0.95) needs `P(w|prev)` on the
  same scale as the unigram table (0.008–0.07); feeding it a 0.9 rank score makes
  `contextProb/baseProb` exceed the 10× clamp for **every** listed pair, converting today's mixed
  boosts-and-penalties into a flat max boost and silently rewriting live tap ranking. So the two
  data sets stay on the two jobs their scales fit. Two further schema facts, both pinned in
  `StaticBigramSeedTest`: the never-called `loadFromFile` parsed whitespace-delimited PLAIN TEXT
  and would have thrown an uncaught `NumberFormatException` on the first JSON line (the assets
  could never have loaded through it); and 8 of the 794 shipped entries are not bigrams at all
  (`fr`: `"c'est"`, `"il y a"`, `"s'il vous plaît"`; `it`: `"c'è"`, `"nel"`, `"nella"`, `"del"`,
  `"della"`) and are dropped. Merge policy: asset wins on conflict, the hardcoded table fills the
  16 en / 6 es / 2 fr / 1 de pairs the assets never listed. `it`/`pt` gain static data they never
  had; the seed language therefore tracks the requested language instead of the multiplier's
  fall-back-to-English rule.
- **ARC-026 — both undisposed knobs DECLINED (2026-08-28).** `context_max_boost`
  (`ContextModel.kt` `MAX_BOOST`/`BOOST_EXPONENT`) and `next_word_max_suggestions`
  (`NextWordPredictor.MAX_SUGGESTIONS`) stay private constants. Rationale: the 2026-08-26
  next-word audit deliberately kept the user-facing surface minimal, and a boost-SHAPE
  control carries the same per-scale footgun as the rescoring λ — the value that helps at
  one context-probability scale hurts at another and the user has no instrument to tell
  which regime they are in. For the suggestion count, the failure mode is noise, which the
  confidence floors already control better than a count would. The decision is recorded in
  code at each constant so the next reader does not re-open it. Revisit only with an
  offline replay showing a per-user optimum spread wide enough to justify the exposure.
- snake_case `Config` fields mirror pref keys by design — convention, not debt.
- fdroid `CurrentVersion` staleness is documented bot-updated behavior (`release-process.md:21,353`) — stale local copy is cosmetic.
- Doc corrections applied with the archive commit (not tracked forward): HISTORICAL banner + license correction on `hybrid-engine-rank-fusion.md`; 4 stale rows in `ctc-architecture-and-multiscript-guide.md`; V5→V6 renumbering in the at-rest design; `CLAUDE.md` tree/geometric-wired fixes.

---

## Corrections to the archived docs' own records (carried here so the archive is honest)

- 3-core-ime m-1 is fully closed (enum renamed `PredictionSource.SWIPE`), not PARTIAL; its R-3 is
  superseded-by-ADR-011 with the guarantee drift-pinned (`CoreImeHygieneDriftTest`).
- The gh-issue audit's #75 (QWERTZ) and #128 (memory) are superseded by the CTC live-layout
  mapping and the measured 143.7 MB phase table respectively; #84's "no smart-punctuation logic
  exists" is no longer true.
- `web-demo-pipeline-findings.md`'s "four findings carry forward" line is superseded by its own
  table — all six fixes verified live in `web_demo/` at HEAD.
- The `context-lm-review-findings` doc verified 20/20 implemented with named in-code `H1..L10`
  comments — the cleanest remediation execution in the corpus.
- Incidental: `build.gradle:330-333` lint comment claims ~500 baseline-suppressed issues (actual
  0); `docs/user_settings_diff.md:18` documents removed `neural_beam_score_gap` as live;
  `ClipboardSettingsActivity.kt` (631 lines) is unreachable dead code (no manifest entry, no
  launch site); headless backup toasts append `e.message.take(60)` which can echo imported-file
  fragments to the user's own screen; both live context-LM specs still call the shipped
  editor-scan cursor-park "deferred" (`context-learning-and-next-word.md:172,:322`,
  wiki `next-word-prediction-spec.md:65`); `LearningGate.kt:31-33` + `strings.xml:668` still
  name the deleted `SwipeCalibrationActivity` trace collection as a master-gate exception.

---

## Backlog additions — 2026-08-29 remediation waves (ARC-053..078)

**Context:** waves A–N plus the CI-hardening pass fixed 45 of the original 52 items same-day
(see git history `31685cac..b12c4365`; every fix cites its ARC ID). What follows is everything
the waves surfaced, deferred, or left gated — the complete open backlog for the next agent.
`memory/HANDOFF.md` §0 is the prioritized index; this section is the evidence.

**Release-gated (block v1.6.0 tagging)**
- ARC-053 — **Maintainer soak of the MINIFIED release APK.** R8 on since `37ed9804` (−14% APK,
  −54% DEX, byte-deterministic). ew-cli does NOT discharge this (it builds unminified debug).
  Soak: install, type, swipe, clipboard/emoji/GIF panes, backup import, language toggle.
- ARC-054 — **Release-notes decision**: main serves ru + eligible imported packs; the v1.6.0
  notes say seven languages. `ReleaseMetadataDriftTest.SERVED_BUT_NOT_YET_ANNOUNCED = {ru}`
  pins the mismatch. Announce at real evidence tier (ru = val-only) or hold the wiring back.

**Multiscript follow-ons**
- ARC-055 — el routing: copy `el_synth_v3_ch80_fp16w.onnx` (`7083794c…`) + fixture
  (`d08d5501…`), flip the `CtcScriptSupport` row, add `el` to `SUPPORTED`, run the parity row.
  Blocked on evidence-tier appetite only (no Greek probe exists at any tier).
- ARC-056 (ML-side) — uk/bg/mk/he lexicons via `build_wordlist.py --lang`; `he` additionally
  needs a `hebrew` branch (0x0590–0x05FF) in `_is_script_word`.
- ARC-057 — 32-frame emit-budget sweep for the BUNDLED lexicons (en/fr/de/es/it/pt/sv + ru);
  `7cb98645` closed it for imported packs only. An over-budget word is unemittable, silently.
- ARC-058 — trie-memo capacity (`size > 2`) + second-ORT-session memory under a 3-language
  rotation with ru primary — never measured (extends CK-150-026).
- ARC-059 — `CtcLatencyGateTest:183` measures `CtcSwipeDecoder`, which release R8 now strips
  (zero prod callers; production decodes via `CtcBeamDecoder`). Move it to `src/test` or
  repoint the gate.
- ARC-060 (ML-side) — `ru_jcuken_default.json` lacks `source.app_xml` provenance and carries
  1080-px rasterization artifacts; regenerate via `app_layout.py --code ru` and diff (en's
  agreement was measured at 4.7e-4; ru's never was).
- ARC-061 (ML-side) — LOW-6 is falsely closed: both `ctc_golden.json` copies embed
  `source_onnx: /home/will/...` (non-secret). Fix belongs in `make_golden.py`; editing the JSON
  in-app breaks byte-identity + sha ties.

**R8 follow-ons**
- ARC-062 — the coroutines `META-INF/services` packaging excludes have NEVER worked, and under
  R8 that accident is load-bearing: `-assumenosideeffects` folds
  `FAST_SERVICE_LOADER_ENABLED=false`, so `Dispatchers.Main` resolves via
  `java.util.ServiceLoader` reading the very file the excludes claim to delete
  (baksmali-verified). Delete the dead excludes + keep the comment, in the same change as the
  soak so one test run covers it.
- ARC-063 — narrow the blanket `androidx.compose.**`/lifecycle/savedstate/coroutines keeps
  AFTER the first minified soak passes — deliberately not bundled with ARC-053.

**Langpack-CTC follow-ons (Wave J gaps)**
- ARC-064 — unasserted: secondary-language dual decode with an imported pack;
  pack language on a non-Latin board (generic gate assumed to catch it); pack
  `contractions.json` alias injection into the CTC trie.
- ARC-065 — out-of-band pack arrival (file restore / older-build import) → first swipe goes
  geometric while the background a-z measurement runs. Documented behavior; optional
  measure-on-boot if it ever bites.
- ARC-066 — `swipe_engine_mode_desc` is content-stale in English + 21 locales (predates ru and
  packs). One deliberate reword pass, accepting the full-locale invalidation.

**Translation debt (consolidated)**
- ARC-067 — the 21-locale pass: 317 ARC-045 extractions + wave C/E/J strings
  (`backup_base64_too_large`, `clipboard_private_copy_toast_*`, `clipboard_provenance_*`,
  pack-serving card strings) + `privacy_on_device_learning_desc` (21 locales still name deleted
  swipe-calibration) + `pref_secondary_prediction_weight` summary rescope.

**Verification owed**
- ARC-068 — #79: manual visual pass on a current build. If flicker persists, the v1.2.5-era
  candidate is the top-edge three-way inset conflict (`styles.xml:53-57` vs
  `setDecorFitsSystemWindows(false)` vs `.statusBarsPadding()`); the
  `debug.hwui.show_dirty_regions` discriminator is recorded under ARC-012 above.
- ARC-069 — maintainer-device checklist (consolidated): #148 visual (predictions off → pane
  overlays visible keyboard); `.ckenc` export-with-password; next-word cold-start bar
  (opt in, empty learned store); ARC-005 nonzero occlusion on a geometric-served layout;
  the collision-warning DIALOG rendering (the scanner branch is now emulator-covered by
  `CtcImportedPackInstrumentedTest`; the dialog itself is still unseen); plus the carried
  items: Italian swipe, first-swipe warm-up, pre-v1.6.0 backup import, pre-v1.1.86 upgrade.
- ARC-070 — one long-run `MemoryProbe` + `dumpsys meminfo` on a current build to close the
  unexplained 2026-08-17 OOM (was ARC-049).
- ARC-077 — CK-150-027 (a11y dense hit-region parity sweep) and CK-150-029
  (touch-exploration-ON smoke incl. a `dispatchKeyEvent` non-swallow assertion) remain open
  from the 2026-08-25 ledger.

**Hygiene / small**
- ARC-071 — astro 5→6 migration: bump astro past 6.4.6, drop the vite/js-yaml overrides in
  `site/package.json`, delete the two `.trivyignore` lines, rebuild (84 pages expected).
- ARC-073 — doc-path drift: ~25 spec/wiki citations to pre-reorg source paths (deliberately
  skipped by the rename-pure Wave N commit) + `scripts/verify-production-ready.sh:66,98,99`
  citing a `tribixbite/keyboard2/` tree that never existed.
- ARC-074 — `CrashGuardInstrumentedTest`'s `catch (Throwable)` guard is never exercised (all
  eight collaborators are final); needs a production seam to inject a throwing collaborator.
- ARC-075 — `GifPanelSection` couples on `status.startsWith("Error")` — an English-anchored
  match against a message produced elsewhere.
- ARC-076 — `tools/test_cli_predict.ts` (unrunnable; kept only for the authoritative
  `QWERTY_KEYS` geometry table cited by `GeoLocalCorpusReplayTest`) and the orphaned
  `scripts/swipedata_metrics.py`: move the geometry table into a test fixture, then delete both.

**Architecture projects (each its own effort; plans in `docs/history/audits/remediation/5-architecture.md`)**
- ARC-072 — R3 `ConfigSnapshot` hot-path read-model (static `Config.globalConfig()` consumers:
  33 files / 90 call sites) and R5 Initializer collapse into a composition root (6 files,
  841 lines). Done already from that plan: R4 reorg (root 145→114, dir-only), R6 `Predictor`
  interface, the `SideEffect{}` fix.

**Process note (recorded so it isn't re-litigated)**
- ARC-078 — the 2026-08-28 androidTest APKs (13.79 MB uploaded) carried **~10.3 MB of
  UNTRACKED filesystem payload** that no longer exists: a worktree build of the same SHA from
  tracked files yields 3.52 MB, and today's builds are 3.44 MB with all test classes and
  fixtures verified present. The size signature (stored-uncompressed, `noCompress 'onnx'`)
  matches leftover ONNX models parked under `src/androidTest/assets/` under names the bench
  test doesn't probe (its 2 by-design reds fired on BOTH days, so the *expected* bench models
  were never staged). The deleter left no commit record. Two session claims were WRONG in
  sequence and are retracted here: "lockfile scoping healed the bloat" (refuted by the worktree
  experiment) — the durable lesson stands: AGP packages the FILESYSTEM, not the index; when an
  APK size jumps, `unzip -l` immediately, before theorizing.

---

## Second-pass verification — 2026-08-29, HEAD `26b1d820` (ARC-079..098 + corrections)

**Method:** ten parallel line-by-line full re-reads of all 27 archived docs (7,470 lines),
every item re-verified BY SYMBOL against live source (the reorg staled all archived line refs),
with ARC-001..078 + CK-150 residuals as the tracked-baseline. ~900 finding-instances checked.
**Result: zero P0/P1, one P2, ~20 P3/LOW misses — and two corrections to this ledger's own
earlier entries.** Three docs verified fully clean twice (a11y plan, backup-encryption design,
memory-phase table); the context-LM review-findings' 20/20 held under independent re-check.

**Corrections to earlier ledger entries (the second pass auditing the first)**
- **ARC-050's annotation was FALSE and has been fixed in both live docs**: the post-apostrophe
  re-run WAS measured (`3b94b2b2`; overall floor 79.25→79.29, ceiling unchanged —
  `2026-07-24-test2400-head2head.md:144-157` + val leg `:115-135`). The "never performed /
  treat as approximate" wording actively mis-instructed the G2 gate for per-script runs;
  `futo-decoder-eval-notes.md` and `train-ctc-swipe-model.md` now carry the measured result.
- **ARC-043 is CLOSED** (verified: both `ModelLoader` EP docstrings match the XNNPACK-first
  code; QNN stub replaced with an honest not-implemented note).
- **The HANDOFF §0 "complete open list" claim was wrong**: ARC-027/028/029 (geo OQ backlog)
  and **ARC-046** (web-demo regression gate + Tailwind vendoring — confirmed untouched by any
  wave, both halves open) are open ARC-001..052 items the index omitted. §0 now says so.
- Upgrades to archived docs' own records: 3-core-ime m-1 fully closed (not PARTIAL), m-4 fixed
  at the root; Tier-1.1's 27-child trie fix is now *exercised in production* by ru's 31-letter
  alphabet; custom-word fuzzy rescue (an ADR-011 accepted loss) was incidentally restored.

**P2**
- **ARC-079 — duplicate full-dictionary residency.** `DictionaryManager.setLanguage`
  (`:150-174`) keeps a per-language `WordPredictor` cache that RE-loads the dictionary
  `PredictionCoordinator` (`:124-135`) already loaded (~5–10 MB × up to 4 languages, the
  file's own estimate). The cache has **no prediction consumer** — readers are `isLoading`,
  `flushLearnedData`, `cleanup`, and the zero-caller `preloadLanguages()`. Instrumented by
  `MemoryProbe.mark("wordPredictor.dictionaryManager")` but never adjudicated; plausible
  ARC-070 OOM contributor. Fix: delete the cache (route isLoading/flush through the
  coordinator's predictor) or make the coordinator source from the manager; measure.

**P3**
- **ARC-080 — n-gram denominators are not persisted; probabilities inflate after restart.**
  `BigramStore.serialize` writes entries only; `loadInto` reconstructs the prefix total as the
  SUM OF SURVIVORS, but caps (20 bigram / 10 trigram continuations) drop entries while
  `word1Frequencies` counted all observations — so post-restart renormalization inflates
  sibling probabilities (concrete: 4.95%→~7% crosses `MIN_LEARNED_PROBABILITY` 0.05). The
  concurrency test deliberately stays under the cap. Fix: persist a totals map (absent ⇒
  legacy summing) + a >cap round-trip test.
- **ARC-081 — platform `UserDictionary.Words` entries are tap-only.** `WordPredictor` merges
  provider+pref (`loadCustomAndUserWords`), but both swipe adapters read only the
  `custom_words_<lang>` pref — a word added to the Android user dictionary completes on tap
  and cannot be swiped on either engine, while the adapter KDoc reads as covering it. Decide:
  feed a provider snapshot into `CtcLexiconMerge` (fingerprint must include it; use observed
  frequency, not the 255 clamp) or document the exclusion in KDocs + spec.
- **ARC-082 — post-dictionary-mutation trie rebuild stall.** The exec brief required
  "background rebuild on mutation OR accept-and-document the one-swipe ~2 s stall"; neither
  happened. Add-to-dictionary writes `custom_words_<lang>` → memo invalidates → next swipe
  pays the full build on the decode thread. Fix: re-warm on the custom/disabled-words write
  (mirror ARC-014) or record acceptance in the spec.
- **ARC-083 — transient CTC decode exception clears the bar with no retry.**
  `CtcEngineAdapter.decodeAsync`'s catch posts an empty slate; the dispatcher's fallbacks
  cover latched-only failures, and its own comment names this exact gap. Fix: route a caught
  decode exception to `performGeometricSwipeTyping`.
- **ARC-084 — dead CGR plumbing ships.** `storeCGRPredictions` (`Keyboard2View`) has zero
  callers; `_cgrPredictions` is permanently empty; the chain (getters,
  `KeyboardDimensionsHelper.updateCGR/checkCGR`, two `CleverKeysService` hatches) is kept by
  blanket `-keep {*;}` so R8 ships it — while CLAUDE.md/ADR-011 assert "no CGR". Delete
  (~60 lines, 3 files).
- **ARC-085 — `swipe_correction_preset` is a fully dead control.** The "Correction Style"
  dropdown writes a pref NOTHING reads (UI plumbing only; zero hits in Config/predictors/
  adapters). Predates the neural removal, so both sweeps' scopes missed it — the exact
  responds-to-touch-changes-nothing class, 30 lines above the tombstone of the one they
  caught. Delete (dropdown + 2 strings + state field) or wire.
- **ARC-086 — layout-caused CTC→geometric fallback is invisible.** The fallback card gates on
  LANGUAGE only; script-missing, a–z-incomplete (`latn_qwerty_az.xml` is live) and
  corner-letter custom layouts fall back silently. AND no authoring doc anywhere states the
  `script="latin"` + 26-center-letters requirement. Extend the card's predicate to the layout
  axis + one authoring paragraph in the layout docs.
- **ARC-087 — the provenance sheet is hardcoded English in 21 locales.** Every string the
  long-press sheet renders is pure-Kotlin (`ProvenanceFormatter`, `provenanceNote`,
  `explainBoost`) — invisible to the coverage drift test, not in ARC-045/067's scope, while
  the *translated* marker description advertises the sheet. Extract via an Android-layer
  label pass (the formatter takes pre-resolved strings).
- **ARC-088 — `KeyModifier.modify()` unmemoized per label per frame** — the un-fixed second
  half of the onDraw-allocation finding: `drawLabel`/`drawSubLabel` call it for every label
  (1+≤8 per key) on every frame with no cache; with Shift latched the whole `applyShift`
  chain re-runs. Memoize per (KeyValue, Modifiers) as the sibling fix did.
- **ARC-089 — geometric spec quotes pre-regeneration accuracy.**
  `geometric-swipe-engine.md:617-625` + `:700-721` carry the superseded fr/de/ru tables and
  25k-lexicon figures; the corrected numbers live only in `GeoAccuracyThresholds.kt:26-52`
  (fr SLOPPY top-3 85.5→80.6). Annotate — the re-measurement already exists (same class as
  the ARC-050 lesson).
- **ARC-090 — NOTICE under-enumerates shipped models.** Attribution names only
  `ctc_swipe_encoder.onnx`; `ru_synth_v3_ch80_fp16w.onnx` ships since `da012ded` (same MIT
  corpora — no rule violated). Additive fix per script model; do NOT alter the `:46-64` FUTO
  lineage wording. Release-relevant beside ARC-054.

**LOW / test-and-doc debt**
- **ARC-091 — zip-slip is never exercised through an importer**: every importer test stubs
  `getMediaFile` with an UNCHECKED pass-through, so the validation call and its
  ordering-before-write have no regression guard (re-opens CK-150-034's exact gap); the
  prescribed `clipboard_media/../evil`-through-`importClipboardHistoryZip` test was never
  written.
- **ARC-092 — private-copy unpinned assertions**: Decision #4's outcome (private copy works
  with `clipboard_history_enabled=false` — zero test hits), sanitizer-on-private-path,
  listener-fired, plus the hostile-clipData/EXTRA_STREAM PROCESS_TEXT case.
- **ARC-093 — legacy fractional `finger_occlusion_offset` import** (v1.5.x float 12.5/40.0)
  bypasses the −25..25 guard (`validateFloat` has no int-key branch) and silently clamps at
  Config read. Fix is a DECISION: coerce fractional→`IntV(round)` to preserve calibrated
  values, don't mechanically reject.
- **ARC-094 — learned-data restore is invisible to the import preview**: bigrams/trigrams/
  vocabulary merge with no count row while the flow's purpose is showing what changes.
- **ARC-095 — no regression test for SuggestionBar view recycling** (`rebindSuggestionViews`
  hot path; six legitimate `removeAllViews` sites would mask a re-introduced rebuild).
- **ARC-096 — lint has never seen the release variant** (`checkReleaseBuilds = false`; both CI
  invocations are debug) — material now that release is the only minified variant. Flip it or
  add `lintVitalRelease` to the release gate; pair with the ARC-053 soak.
- **ARC-097 — `SuggestionOrigin.forRoutedEngine` has zero production callers** while its KDoc
  + 3 live docs claim it is the production mechanism (production passes enum literals). Wire
  it from the two router branches or delete it and fix the four citations.
- **ARC-098 — finish the R4 reorg + tooling sweep**: the `gesture/` cluster (7 files) and
  Bridges/Initializers→`wiring/` (folds into ARC-072 R5) toward the <100-root milestone
  (now 114); plus the phantom-`keyboard2` tooling sweep — 18 scripts + 
  `tools/generate_compose_data.py` cite the never-existed tree (`verify_pipeline.sh` greps a
  deleted file 16×, so it verifies nothing; `run-pure-tests.sh:39` carries an UNPINNED second
  pure-test list naming deleted classes; `test-runtime.sh` probes deleted neural assets;
  `strip-repo-history.sh` is a spent one-shot) — batch with ARC-076.

**Appends to existing entries**
- ARC-069 += the two never-tracked visual-approval gates: #35 light-mode pass of the Compose
  settings screens; UT-1 Dictionary Manager in light + dark.
- ARC-072 += `Keyboard2View` (1,968 ln, +178 since audit; pref writes + `startActivity` in the
  render View), the `WordPredictor` size trend (2,335→2,671; the `Predictor` seam is unused
  for decomposition), and the preview/receiver-extension halves (previewability is R4§2D's own
  acceptance criterion).
- ARC-073 += the doc-drift micro-bucket: CLAUDE.md:27-28 stale "in progress" block; ctc spec
  FR-1's phantom `weight` term + ":1009 plan §7.3" cite; hybrid banner "§2c.1" + stale
  `SwipeEngineRouter:41-44` ref; head2head `:165` invokes the ARC-047-deleted script; wp9
  KDoc "IC:539"; 5 stale clipboard-doc source paths; unanchored "study §N" CTC KDocs; the
  unreachable `len=` else-arm at the two Keyboard2View redaction sites; ARC-067 += the
  provenance sheet (ARC-087), 3 raw `AutoCorrectionSection` sub-headers, and the
  private-copy/backup feature strings.
- Recorded as DECLINED (won't-fix): clipboard text dedup stays 32-bit `hashCode` (dedup SQL
  also matches full content; note at the hash sites is the cheap disposition — and delete the
  stale PENDING entry at `memory/clipboard-analysis.md:25-31`); `SECURITY.md`'s v1.0.0
  example placeholder (inside a fenced sample report); `BackupPassphraseStore.PREF_WRAPPED`
  absent from `INTERNAL_KEYS` (own prefs file, unread by exports — one-line note optional).

---

## Remediation wave R1 — 2026-08-29 (TDD, fail-first evidence per commit)

Six parallel Opus implementers under Fable review; every fix ran and FAILED its test before
implementation (evidence quoted in each commit's report). Fixed and reviewed:

- **ARC-079 FIXED** (`f77a6d52`) — DictionaryManager's per-language `WordPredictor` cache
  deleted (~5-10 MB × up to 4 slots, zero prediction consumers, redundant per-language
  UserDictionary observers, a wasted device-locale predictor per construction). Learned data
  provably unaffected: the stores are process singletons. `LearningWiringDriftTest` now pins
  residency (word-boundary anchored — `getWordPredictor()` contains the substring
  `WordPredictor(`, a general hazard for source-scan pins); `LanguageSlotCoverageDriftTest`
  repointed from the deleted retention set to the surviving slot-wiring seams;
  `PredictionCoordinatorLifecycleTest` (new, mock tier) pins persist→clearContext→stopObserving.
  MemoryProbe mark renamed `dictionaryManager.userWordsOnly`; ARC-070 still adjudicates on
  device. **Residual (new, LOW): `NON_DEFAULTED_KEYS` rationale is stale** — it documents a
  null-default read site (`getConfiguredLanguages()`) that no longer exists; reclassifying
  changes backup-preview behavior, so it is a small deliberate follow-up, not a mechanical one.
- **ARC-080 FIXED** (`84139e01`) — n-gram denominators persisted (v2 `{version,entries,totals}`
  blob; STRUCTURAL legacy detection: bare array = v1 keeps sum-of-survivors; lenient v2 read;
  p>1 clamp for truncated blobs). Fail-first reproduced the exact predicted inflation
  (0.0495→0.061 bigram / 0.049→0.119 trigram across the 0.05 floor). Backup contract untouched.
  **Residual (LOW, documented in-code): totals for fully-pruned contexts are deliberately not
  persisted** (unbounded-blob risk), so live vs restored state can differ for that narrow case.
- **ARC-083** — in flight (transient CTC decode exception → geometric retry).
- **ARC-084 FIXED** (`ae4d04c4`) — CGR chain deleted (112 lines, 5 files; zero-caller proof per
  symbol; `SuggestionBar.setAlwaysVisible` orphan removed behavior-identically; no CGR-specific
  proguard rules existed, confirming the blanket-keep diagnosis). Pinned by
  `DeadPlumbingDriftTest` (fail-first 4/4 red).
- **ARC-085 FIXED** (`51e7da52`) — `swipe_correction_preset` control deleted (78 deletions,
  27 files incl. 22 locale string files). Deliberate deviation from "absent from src/main":
  the key moves to `DEPRECATED_KEYS` because every released backup carries it — the ARC-051
  tombstone precedent; drift test exempts comment-only mentions.
- **ARC-097 FIXED — WIRED** (`cb7f7f62`) — both decode callbacks now derive origin via
  `forRoutedEngine(Engine.…)`; the KDoc + 3 doc citations became TRUE, so no doc edits needed.
- **ARC-072 slice 1 DONE** (`caee60dc`, plan `docs/plans/2026-08-29-arc072-config-snapshot-and-composition-root.md`) —
  `prefs/ConfigSnapshot` (28 mirrored fields, no defaults so a new field breaks the builder at
  compile time), `Config.snapshot` @Volatile rebuilt as the last statement of single-exit
  `refresh()`, Gesture (per-gesture constructor capture) + GestureClassifier (per-call arg;
  Context param deleted — only user was a caller-less dpToPx) migrated;
  `ConfigSnapshotRatchetTest` pins per-file zero-use + global static-consumer ceiling
  (33→31, ratchets down only). Slice 2 (Pointers gesture-scoped, Keyboard2View frame-scoped)
  in flight. Noted for slice 2: `InputBehaviorSection.kt:398` writes a Config field directly
  outside refresh() (snapshot-staleness source); `Gesture`'s state-machine methods have no
  production callers beyond construction (test-only — candidate wire-or-delete).
- **Release record book NEW** (`3eb46757`) — `docs/RELEASE_RECORD.md`: 32 releases, 265
  published claims mapped (**148 GUARDED / 82 PRESENT-UNTESTED / 25 REMOVED / 10
  UNATTRIBUTABLE**), append-only with per-block SHA-256 pins in `ReleaseRecordDriftTest`,
  completeness forced from the fastlane changelog dir, unreleased v1.6.0 held un-pinned in
  `PENDING_RELEASES` until tagged. The 82 PRESENT-UNTESTED rows are a ready-made test backlog
  (extends ARC-044). One superseded claim found: v1.3.0's "swipe auto-disabled on non-QWERTY"
  is contradicted by the layout-agnostic router → recorded REMOVED.

**New items surfaced by the wave**

- **ARC-099 (P3)** — `KeyboardDimensionsHelper.updateSwipePredictions` /
  `completeSwipePredictions` / `clearSwipePredictions` + their three `CleverKeysService`
  pass-throughs are fully dead (~30 lines, zero callers outside the pass-throughs, which
  themselves have zero callers) — the exact ARC-084 shape, found during that deletion and
  deliberately left out of a shared-tree commit. Delete with `DeadPlumbingDriftTest` pins.
- **ARC-100 (LOW)** — `NON_DEFAULTED_KEYS` stale rationale (see ARC-079 residual above).
- **ENV (build infra, worth one commit when the tree is quiet)** — `gradle.properties` sets
  `org.gradle.jvmargs` WITHOUT `-Xmx`, so when the Kotlin daemon cannot start under memory
  pressure the in-process fallback runs in Gradle's small default heap and OOMs
  ("Not enough memory to run compilation"). Proven workaround:
  `-Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=384m"
  -Dkotlin.compiler.execution.strategy=in-process --max-workers=1`. Also recorded: under
  saturation, Kotlin `internal` visibility in an out-of-Gradle harness needs
  `-module-name CleverKeys_release` + `-Xfriend-paths`.
