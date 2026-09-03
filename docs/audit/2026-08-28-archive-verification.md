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
  notes say seven languages. `ReleaseMetadataDriftTest.SERVED_BUT_NOT_YET_ANNOUNCED` pins the
  mismatch — {ru} when written, {ru, el} after el's wiring, **cleared to {} on 2026-09-03 by
  the maintainer-approved ARC-054 announcement** (ru "validation-tested" val-only tier, el
  "early support" with no accuracy figure, both pack-gated; the pin mechanism stays for
  uk/bg/mk/he).

**Multiscript follow-ons**
- ARC-055 — el routing: copy `el_synth_v3_ch80_fp16w.onnx` (`7083794c…`) + fixture
  (`d08d5501…`), flip the `CtcScriptSupport` row, add `el` to `SUPPORTED`, run the parity row.
  Blocked on evidence-tier appetite only (no Greek probe exists at any tier).
- ~~ARC-056 (ML-side) — uk/bg/mk/he lexicons via `build_wordlist.py --lang`; `he` additionally
  needs a `hebrew` branch (0x0590–0x05FF) in `_is_script_word`.~~ **CLOSED 2026-09-01**:
  `538a1633` (hebrew `_is_script_word` branch + the four `LANG_CONFIG` entries, bootstrap
  builds) + `86156ea3` (CKDT v2 langpacks `langpack-{uk,bg,mk,he}.zip`).
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
- **ENV — RESOLVED same day**: `scripts/gradle-guard.sh` landed (`0765473d`, concurrent
  session) — device-wide flock singleton, bounded memory, `--no-daemon` + in-process Kotlin;
  mandatory for ALL Gradle invocations per CLAUDE.md. Effect measured by the ARC-083 agent:
  the same suites that took 20–45 min under daemon contention ran in ~2 min.
- **ENV (superseded by the above, kept for the record)** — `gradle.properties` sets
  `org.gradle.jvmargs` WITHOUT `-Xmx`, so when the Kotlin daemon cannot start under memory
  pressure the in-process fallback runs in Gradle's small default heap and OOMs
  ("Not enough memory to run compilation"). Proven workaround:
  `-Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=384m"
  -Dkotlin.compiler.execution.strategy=in-process --max-workers=1`. Also recorded: under
  saturation, Kotlin `internal` visibility in an out-of-Gradle harness needs
  `-module-name CleverKeys_release` + `-Xfriend-paths`.

---

## Remediation wave R2 — 2026-08-29 (continuation; TDD, Fable-reviewed)

- **ARC-083 FIXED** (`dbe9c0dc`) — new pure `swipe/CtcDecodeDelivery` seam classifies every
  decode outcome DELIVER / DROP / FALL_BACK: cancellation (incl. interrupts surfacing through
  native ORT as arbitrary exceptions — the disposition reads the thread's interrupt flag AFTER
  the catch) drops silently instead of racing the newer swipe; transient failures fall back to
  `performGeometricSwipeTyping` WITHOUT touching the per-asset latch (pinned); the previously
  non-throwing empty-slate paths now raise `DecodeInputsUnavailable` and take the same route.
  After the change no path inside the decode task answers with an empty slate. Rider
  (`e4b01c54`): `CtcMultiLanguageInstrumentedTest.decodeBlocking` asserts `onDecodeFailure`
  instead of waiting out a 60 s latch — compiled, runs on the next ew-cli pass.
- **ARC-057 CLOSED** (`eac7594f`) — 32-frame emit-budget sweep over the EMISSION SURFACE
  (post-projection: STRIP for en-JSON, `CtcAzProjection` for CKDT — ß→ss EXPANDS, `CtcScriptProjection`
  for ru) of all 8 bundled lexicons + 4 contraction alias tables: **zero over-budget**.
  Tightest headroom: it alias `dellelettroencefalogramma` 28/32; tightest real word de
  `wirtschaftswissenschaften` 26/32. `MAX_FRAMES_EARLY_WARNING = 30` band makes the next
  erosion a deliberate decision; negative controls cover both failure shapes (length AND the
  CTC collapse rule) + anti-vacuous-green minimums + a ru-is-actually-Cyrillic assertion.
- **ARC-072 slice 2 DONE** (`b081ee5c`) — per-POINTER capture at `onTouchDown` (each finger
  captures its own snapshot; a latched-modifier pseudo-pointer must not freeze config
  unboundedly, so no inheritance), Keyboard2View per-unit capture in measure/draw/geometry.
  Slice 1's union missed `_config?.` reads → snapshot 28→35 fields (`swipe_trail_*`,
  slider-speed). **`Config.edit {}`** is now the sanctioned direct-mutation form (bumps
  `version`, republishes via the single `publishSnapshot()` write site); the
  `InputBehaviorSection` hole is closed at the write site and
  `noDirectWriteToASnapshotMirroredConfigField` reds any future one. Ratchet ceiling 31→30;
  enumerated 22 direct-write sites — exactly one touched the read-model. Owed: instrumented
  T13 `configChangeMidGesture_doesNotAffectTheGestureInFlight` (next ew-cli run). Residue
  recorded in the plan doc (Theme.Computed live-Config per measure; two androidTest baseline
  blocks that diverge snapshot from live fields).
- **Contraction user-word guard TOTAL** (`6733f25d`, HANDOFF §1 deferral resolved) — read-side
  `Locale.ROOT` fold consulted only by the REPLACE guard (`isUserWordIgnoringCase`); persisted
  set semantics provably unchanged (the case-sensitivity pin was green before AND after);
  every `userWords` write routed through one invalidating helper, structurally pinned; the
  `CoreImeHygieneDriftTest` guard pin strengthened to require the folded accessor. Skill doc
  §5/§11 updated in the same commit.
- **ARC-081 + ARC-082 FIXED** (`732fd95e`, one commit — the re-warm trigger set IS the
  invalidation set the fingerprint widens) — platform `UserDictionary` words now reach BOTH
  swipe engines via pure `swipe/UserDictionarySnapshot` (provider read extracted from
  WordPredictor's two near-identical private copies into `UserDictionaryWords` — tap and swipe
  can no longer disagree); observed provider frequency passes through the existing
  `coerceIn(1,255)` custom-word treatment, preference wins collisions; both adapters' formerly
  byte-identical private memo-key hashers unified into `swipe/LexiconContentVersion` with the
  provider fingerprint as a new input. Mutation re-warm: `SwipeRewarmScheduler` (400 ms
  latest-wins window mirroring `REWARM_DEBOUNCE_MS`) fires from the pref keys AND the provider
  observer into the existing ARC-014 prewarm path; mid-rebuild swipe behavior unchanged by
  design (FOREGROUND decode cancels the prewarm — cited contract).

**New items from wave R2**
- **ARC-101 (LOW, decision)** — two sibling exact-match `isUserWord` sites left deliberately:
  `SuggestionHandler:1923` (add-to-dictionary prompt re-prompts for a differently-cased stored
  word) and `:2213` (EXACT_ADD lets a second casing be added). Both additive, not destructive;
  folding them changes when UI appears — a UX decision, not a guard bug.
- **ARC-102 (LOW, perf decision)** — the user-dictionary provider snapshot is read per lexicon
  build (one binder query per decode, two under multilang): correctness-over-cache chosen
  because any stale cache reintroduces ARC-081. Safe cache design recorded in `TODO(perf)` on
  `UserDictionaryWords.snapshot` (epoch bumped by the descendant-registered observer; disabled
  while the observer is not running). Unmeasured — pure JVM has no provider.
- Cross-reference: `DictionaryManager.userWords` (and the new folded view derived from it) keep
  their pre-existing absence of thread-safety; revisit if user-dictionary writes move off the
  main thread (relevant to the ARC-081 threading).
- Pre-existing, inherited not worsened: `GeometricEngineAdapter.dictionaryFor` region-subtag
  TODO (`fr-CA` → `custom_words_fr-ca` vs CTC's normalization) now also covers the provider
  locale query — latent, every producer feeds a bare code.

**Verification owed on the next ew-cli run (adds to the standing list)**: T13 mid-gesture
config-change test; the ARC-083 rider's `onDecodeFailure` assertion; an instrumented pass
through the injectable `userDictionarySource` seam (adapter wiring is currently source-scan
pinned only); an end-to-end typing test for the total user-word guard (mock-tier pinned today).
Gates after R2: `runPureTests` **2034**, `runMockTests` **342**.

---

## Wave R3 — 2026-09-01: audit of the third-party continuation (codex `5fb58037`/`bbbdc06e`)

The six wave-A/B/EL Opus agents were killed mid-flight by a spend limit; four of their commits
had landed first (ARC-099 `b16d9dd9`, ARC-075 `1e2cc2a0`, ARC-059 `395c8341`, ARC-096
`7c2628f1` — all reviewed OK, ARC-059 keeps `CtcSwipeDecoder` because CtcReplayEngine and
CtcModuleTest decode through it). A codex agent (`— gpt-5.6-sol`) then completed the remaining
briefs in one 88-file commit `5fb58037` + handoff `bbbdc06e`.

**Audit (2 Fable line-reviewers + independent gate re-runs + orchestrator spot-checks): PASS
with 2 defects, both fixed same-day.** Verified clean: NOTICE FUTO block untouched (new
per-model provenance section is additive and correct incl. Yandex eval-only wording); zero
decoder-constant diffs; every el `92.12` mention labeled synthesis-holdout-never-accuracy;
el model+fixtures byte-identical to CleverKeys-ML with tied shas, parity/sha coverage
TABLE-DERIVED from `CtcScriptSupport` with double vacuity guards; ARC-076 geometry CSV proven
value-equal to the deleted TS (26/26 keys, zero mismatches); ARC-089 "replacements" all trace
to the measured `GeoAccuracyThresholds.kt`; ARC-088 memoize (bounded 2048 + modmap-hook
invalidation, and the old `kw.modmap?.let{}` null-skip was itself a latent stale-modmap bug,
fixed); ARC-102 epoch cache (observer-down ⇒ bypass; @Synchronized across the binder read so
a torn epoch can only cause a spurious re-query); ARC-093 (12.5→13, 40.0→25, integer 40 still
rejected); ARC-100 RECLASSIFIED with preview tests after verifying no null-default read
remains; ARC-094 (interactive apply now consumes the previewed bytes — a bonus correctness
fix); ARC-065/074/077/091/092/095/101 all genuinely implemented (ARC-074's production seam =
`ConfigPropagationProbe`); ARC-066 reworded in EN + all 21 locales. Gates independently
reproduced: runPureTests 2087, runMockTests 343, lintVitalRelease green.

**Defects found and fixed (audit-fix commit, this wave):**
- The ARC-086 alphabet axis RE-IMPLEMENTED the adapter's centre-letter extraction (second
  private `letterOf`), and `SwipeEngineFallbackTest` carried a FALSE comment citing a
  nonexistent `CtcEngineAdapter.coveredSlots` as the queried gate. Fixed: single
  `swipe/KeyLetter.centreLetterOf` (strictest-union semantics, behavior-neutral) consumed by
  both `buildMappedLayout` and the card; comment corrected; new one-implementation scan
  `theCentreLetterDefinitionHasExactlyOneImplementation`.
- The 38 new `provenance_*` strings were the file's only English-only cluster without
  `tools:ignore="MissingTranslation"`. The predicted release-gate red did NOT materialize
  (lintVitalRelease green — MissingTranslation is not in the vital set here), so this was
  convention-consistency, not a blocker; ignores added pending the ARC-067 translations.
- Small coverage fill: ARC-094 empty-payload NONE assertion. Still owed to Wave J: one
  instrumented out-of-band pack-arrival case for ARC-065.

**Maintainer flag (carried to the needs-input list): the ARC-066 reword shipped with
codex-authored translations in all 21 locales** — samples read correct but they are
unreviewed machine translations.

## Wave D — 2026-09-01: ARC-067 CLOSED (21 locales, machine-verified)

Seven parallel translator lanes filled the owed ~370-string set in every locale (per-locale
commits `c87ed06d..4799723f` + follow-ups), each with a placeholder-multiset verifier vs EN
(incl. `formatted="false"` carry-over, `%%` forms, Turkish `%%%2$d` prefix convention, CLDR
plural shapes per locale). Orchestrator consolidation closed the systematic gap the lanes'
`<string>`-only derivation missed (`layout_manager_hint` `<plurals>` in 11 locales, composed
from each file's own terminology + quantity template) and then **removed all 373
`MissingTranslation` suppressions from `res/values/strings.xml`** — `lintDebug` is green with
NOTHING suppressed, so full coverage is now enforced by lint itself, not asserted by a doc.
D6 also fixed a pre-existing ru mistranslation (`geo_settings_intro` said "non-Latin" for a
setting that means "non-QWERTY").

**Maintainer flags**: all ~7,800 new translations are machine translations pending native
review (each lane's low-confidence roster is in its commit-lane report; recurring: beam-search
jargon, the `command_palette_ts_*` preset labels, the launcher tagline, aggression-register).

**New items surfaced by wave D**
- **ARC-103 (P3)** — the `%1$d ... (s)`-style COUNT strings (e.g. `multilang_installed_count`)
  cannot be rendered correctly under Slavic/Baltic plural rules from a single form; convert
  them to `<plurals>` resources (the pl/uk lanes hit this independently).
- **ARC-104 (decision, maintainer)** — five ADB manual UI test-driver scripts
  (`test-theories.sh`, `test-keyboard-automated.sh`, `test-after-install.sh`,
  `test-activities.sh`, `quick-test-guide.sh`) conflict with the standing "never test via
  ADB" policy; identifiers fixed in the ARC-098 sweep but keep-vs-delete is a policy call.
- **ARC-105 (LOW)** — `PipelineCharacterizationTest.kt` KDoc cites "IC:539";
  `handlePredictionResults` is now `InputCoordinator.kt:428` (code-owned KDoc, one line).

## Final implementation batch — 2026-09-01/02 (waves G/044/inversions/103)

- **ARC-044 remainder CLOSED** (`da5171d0`/`cc07765f`/`a8f7ac03`): 16 classes strengthened,
  suite `assertNotNull` 282→163, `assertEquals` 1243→1351; the incident-class guards applied
  (WordPredictorTest setConfig; ClipboardHistoryTest loud-null setup); ~20 classes examined
  and left with recorded reasons. Runtime verification = Wave J.
- **ARC-103 CLOSED** (`f9b578fb`): 15 count strings → `<plurals>` across all 22 files
  (795 items, per-locale CLDR templates); 14 candidates re-judged and kept with in-file
  rationale; drift pins extended fail-first.
- **HANDOFF §1 verb inversions CLOSED** (`bd8984fe`): 272 PAIRED-only entries (fr pairs
  183→455), closed grammatical family, zero REPLACE, landmines pinned through the real
  overlay, sidecars byte-identical, reachability via existing trie injection.
- **ARC-027/028/029 CLOSED as measured declines** (`3237d23b`/`16d3ea8d`/`80238617`) — see
  the Wave-G section the executing agent wrote (mechanisms kept default-off + re-runnable
  `GeoOqSweepTest`, per the endpointInsetKw precedent).

**New items from the batch**
- **ARC-106 (P3)** — `Emoji.initNameMap` duplicate literal keys: later entries silently
  overwrite earlier ("heart" resolves to the `<3` emoticon, ❤️'s canonical name shifts).
  Dedup pass + a no-duplicate-keys test.
- **ARC-107 (LOW, decision)** — `"escape"` is not a special-key name (`"esc"` is); a layout
  using `escape` types literal text (now pinned as documented behavior). Consider an alias
  or a layout-docs note.
- **ARC-108 (P3, follow-up lead)** — OQ-10's ordering slack is **+3.0 top-1 on the real
  corpus** while regressing CLEAN synthetics: a per-decode ADAPTIVE slack gate could capture
  the real-corpus win without violating the any-tier rule. The mechanism + sweep instrument
  are in-tree default-off.
- **ARC-109 (LOW)** — `provenance_usage_count` ("Used %1$d times") is EN-wrong at n=1 but
  flows as a raw template into the pure formatter; plurals conversion needs a small
  plumbing refactor (quantity-aware string resolution at the Android layer).
- Process addendum: never use `git stash` on the shared tree (one agent did, recovered,
  rule now recorded in the shared-tree protocol memory).

## Waves J + K — 2026-09-02: campaign verification closed

**Wave J (ew-cli, Pixel7 API 34)**: full run **1,466 tests** — 2 permanent by-design bench
reds + 3 real reds, ALL THREE test bugs in campaign-written tests, all fixed and
rerun-verified green (`86d77a16`): T13 didn't model ConfigSnapshot's 0.8×`swipe_dist_px`
minimum cap (production fired the subkey CORRECTLY); the el row broke "PROVISIONAL ⇒
bundled" (el is langpack-sourced by design); the sanitizer test wrote a GUESSED pref store
while `reloadSanitizationSettings` reads Config's init-time store (now `Config.globalPrefs()`).
Zero production bugs from the first full exercise of all campaign-written instrumented
coverage. Also exposed + fixed: `gradle-guard`'s 256m metaspace cannot run R8 — no release
build had ever run under the guard; `build-on-termux.sh` release path now defaults to
2048m/1024m (`f3eb3ad8`).

**Wave K (device, evidence `docs/eval/2026-09-02-wave-k-device-verification.md`, `e87c5b97`)**:
Saga completed the full protocol on the fresh verbose v1.6.0 (sha `6894b2cc…`), restore
verified line-by-line, app left installed for the soak. Verdicts: **ARC-070 CLOSED — no leak
signal** (Dalvik fully GC-recovers per burst; native plateaus +1.7 MB by burst 3; settled PSS
~202 MB; full CKMemProbe cold-start table 22.8→135.9 MB); **first-swipe warm-up NON-ISSUE**
(61 ms first vs 23 ms warm — load happens at IME init); **ARC-068 #79: neither failure
signature reproduces** (idle Settings emits ZERO frames; no status-bar strip); swipe decode
e2e PASS (t→o "To"; sendevent path "world" with full pipeline logs); light/dark activities
correct. **Pixel 8 Pro UNREACHABLE all session** (ARP no-route; 33 reconnects; not on either
/24) — the whole pixel pass incl. the #148 repro is still OWED when it comes back online.

**New items from Wave K**
- **ARC-110 (P3, real UI bug)** — `DictionaryManagerActivity` loses its tab COUNTS on
  configuration change (uimode flip): tabs show "(0)" while the list stays populated.
  Reproducible; likely counts computed in state lost on recreation.
- **ARC-111 (decision)** — the keyboard renders dark-purple under system LIGHT mode: theme
  default or bug — maintainer call (screenshots in the Wave-K scratchpad set).
- Protocol facts recorded in the eval doc: `am force-stop` on the IME reverts the default
  IME to Gboard (re-`ime set` after); `debug.hwui.show_dirty_regions` needs a process
  restart to apply AND clear; residual learned words (~10 test words) noted — clearable via
  Settings → learned-data forget if the maintainer wants a pristine store.

## Nibble batch + Wave K2 — 2026-09-02: full convergence, both devices

**Nibbles closed** (`752e07da` ARC-106 emoji-name dedup with ownership policy; `d4af4ca8`
ARC-110 tab counts derive from loaded data; `5b498d3e` ARC-109 quantity-aware usage count in
all 22 locales; `e89bc451` **ARC-108 measured-and-PARKED** — six sloppiness signals swept, the
TYPICAL↔real frontier is monotone with no jointly-satisfiable threshold (best gate: weird
TYPICAL −0.3 vs the ±0.1 bar, or real retention +1.4 vs the +2.0 bar); mechanism + both sweep
instruments in-tree default-off, decode bit-identical; reopening prerequisite = a
device-captured sub-pixel corpus, the current corpus's ~0.03 kw quantization inflates the
signals). Pure suite **2115**.

**Wave K2** (`a5ee26bc`, appended to the Wave-K eval doc): PIXEL full protocol complete —
**#148 fixed behavior visually confirmed** (pane overlays a visible keyboard, prefs restored);
ARC-070 memory PASS (the 449 MB PSS peak is GL mtrack graphics buffers, not heap); warm-up
76ms/32ms; decode e2e PASS; **50/50 on-device instrumented** (Recreation 1, EmojiSearch 30,
PointersGestureRouting 14 incl. T13, PrivateCopy 5 — the 1 locked-screen failure was the
keyguard suppressing clipboard READS, green unlocked). SAGA delta PASS incl. ARC-110 counts
surviving the uimode flip on both devices. Both devices restored-and-verified; v1.6.0
(nibble-inclusive build, Sep 1 23:16) installed on both for the maintainer soak. Protocol
facts: the debug APK is package `.debug` and COEXISTS with release; the Pixel's
wireless-debug port rotates (40307→40621); a secure keyguard blocks UI work AND clipboard
reads but not installs or most instrumented tests.

**New items from K2**
- **ARC-112 (P2)** — dense-sampled two-key swipes are SILENTLY dropped:
  `registerKeyWithFiltering` compares `MIN_KEY_DISTANCE` (40px) against the PER-SAMPLE step,
  not distance since the last REGISTERED key, so a slow smooth swipe on a high-report-rate
  digitizer registers 1 key, classifies SWIPE, reaches the decoder, and dies with no commit,
  no log, no error. Not a regression (byte-identical since Aug 29); reproduced at two
  durations. Fix the accumulation basis + a loud empty-decode log + a pure test with
  dense-sampled traces.
  **2026-09-02 FIXED.** Registration extracted to a pure android-free core
  (`gesture/SwipeKeyRegistrar.kt`) that `ImprovedSwipeGestureRecognizer` delegates to; the
  minimum-travel gate now measures straight-line displacement from the last REGISTRATION
  POINT — invariant under sample density, while the property `MIN_KEY_DISTANCE` exists for
  (boundary chatter on a key seam must not register the neighbour) is preserved and pinned
  by test. One verbose-gated log at the recognizer's empty-decode return names the cause
  (keys-touched count) — the CtcDecodeDelivery "no path answers silently" philosophy.
  `SwipeKeyRegistrarTest` (6 tests, pure tier): fail-first red captured on the extracted
  pre-fix basis — dense 417 px/10 px-step trace registered `[t]` only, exactly the device
  symptom; the pass-through-then-dwell case failed for the same root cause; jitter +
  coarse controls green throughout. Geo replay/accuracy suites unaffected (the replay
  harness feeds decoders directly; no test references the recognizer). λ/γ/β/prune and all
  scoring untouched — input-side only.
- **ARC-113 (P2, forward-compat)** — `libonnxruntime.so`/`libonnxruntime4j_jni.so` are not
  16 KB-page aligned (Android 17 raises a system dialog; will hard-break on 16 KB-page-only
  devices). Fix: bump/rebuild the ORT Android dependency with page-size alignment, or repack
  with zipalign -P 16.
  **2026-09-02 investigation — BLOCKED on a minSdk decision (documented at the dependency
  line in build.gradle).** Facts established by ELF program-header parse of the Maven Central
  AARs: only `libonnxruntime4j_jni.so` is the offender (`libonnxruntime.so` is already
  p_align 0x4000 in 1.20.0; the jni lib is 0x1000); the FIRST fully-aligned release is
  **1.21.1** (1.21.0 still 0x1000; 1.21.1/1.22.0/1.23.0 all 0x4000). The bump was attempted
  and reverted: every release ≥ 1.21.0 declares AAR **minSdkVersion 24** and the manifest
  merger hard-fails against our minSdk 21 (README-documented Android 5.0+ support).
  `tools:overrideLibrary` is unsafe — the 1.21.1 libs import `__register_atfork`/`stderr`
  (bionic API-23 symbols; dlopen fails on API 21/22) and `OrtEnvironment.getEnvironment()`
  runs as a constructor-time initializer in `CtcEngineAdapter` where `UnsatisfiedLinkError`
  is uncaught (the load latch catches `Exception` only) → IME crash-loop on 5.0/5.1.
  zipalign -P 16 / `useLegacyPackaging` cannot fix ELF segment alignment (loader mmaps per
  p_align whether extracted or in-place). **Unblock = maintainer raises minSdk 21 → 24**,
  then: bump both ORT coordinates to 1.21.1+, lockfile regen per recipe, CTC parity suites
  must hold (a fixture delta = runtime numerics changed → investigate, never regenerate),
  and the real-graph check remains CtcEmissionModelParityTest on the next instrumented run.
- **ARC-114 (LOW)** — #79 addendum: an inset-strip dirty-region tint exists on Android 17
  (absent on 13), control-verified — the v1.2.5-era inset-conflict candidate gains its first
  observable; still not the reported whole-screen flicker.
- **ARC-111 UPGRADED** — dark-keyboard-under-system-light-mode reproduced on BOTH devices
  across OS 13/17: it is app behavior, not a device quirk. Maintainer call stands: intended
  default or bug.

**ARC-112/113 closure — 2026-09-02**: ARC-112 FIXED (`fc3f5197`, pure `SwipeKeyRegistrar`
with the chatter property pinned; fail-first reproduced the device symptom `[t,o]` vs `[t]`)
and **DEVICE-CONFIRMED on the Pixel** — the exact previously-silent dense 350 ms t→o swipe now
commits "To" with a full slate (evidence: `arc112-after-swipe.png`, Sep 2 fresh build).
ARC-113 closed DOCUMENTED-BLOCKED (`2a365208`): 1.21.1 is the first fully 16 KB-aligned ORT
AAR (empirical ELF parse of five versions), but every release ≥1.21.0 declares minSdk 24 and
the new libs import bionic API-23 symbols — the unblock is the maintainer's minSdk 21→24
distribution decision, recipe documented at the dependency line.

**Closed by R3 (Opus agents + codex + audit fixes)**: ARC-055, 059, 062, 065, 066(EN+21 MT),
074, 075, 076, 086, 087(structure; translations = 067), 088, 089, 090, 091, 092, 093, 094,
095, 096, 099, 100, 101, 102 — plus ARC-058/064/077 instrumented COVERAGE written (execution
= Wave J). Remaining open: ARC-067; ARC-027/028/029/030-floors-context; ARC-044-rest;
ARC-046; ARC-071; ARC-072 slice 3 + ARC-098; ARC-073; ARC-060/061 (ML-side, on-device —
ARC-056 closed 2026-09-01, `538a1633`/`86156ea3`); verb inversions; user-gated
ARC-053/054/063 (ARC-054 decided 2026-09-03: ru + el announced, pin cleared); Waves J
(ew-cli) and K (device adb).

## Wave H — 2026-09-01: ARC-071 and ARC-046 CLOSED (web lane)

**ARC-071 CLOSED** (`15814849`) — astro 5.18.1 → **6.4.8** (past the 6.4.6 CVE-fix floor),
@astrojs/svelte 7.2.5 → 8.1.2. All three `site/package.json` overrides dropped: without them
the tree resolves vite **7.3.6** (≥ the 6.4.3 pin), js-yaml 4.3.1 and devalue 5.8.1 (== their
pins); the vestigial direct js-yaml/vite deps that only carried the pins went too. Both
`.trivyignore` CVE lines (CVE-2026-54299/50146) deleted per their unblocking condition — the
suppression file is now empty. `astro.config.mjs` moved `remarkPlugins` onto the astro-6
`unified()` processor (deprecation cleared). Build verified: **84 pages** (matches the
pre-migration count), zero warnings/errors, wiki-link rewriting intact.

**ARC-046 CLOSED** (`af9bfd8e`) — committed regression gate `web_demo/tests/f_regression.mjs`
(Node-VM + DOM-stub over the shipped inline script; no ONNX): 32 checks across F1 (tier-0
witnesses + 200-word sample at 94% vs pre-fix 0%, equal-confidence ranking by frequency),
F2 (the previously-unexercised tap path via `handleKeyTap`/`generateTapPredictions`,
completions beyond the 100/3000 tier sets, 20k/2k pool sizes, fuzzy no longer throwing) and
F3 (UI-path personal word reaches the masking trie, pruned back out on removal; boosted
dictionary words restored). Verified green on HEAD and **failing against the pre-fix
35cbaee3~1 sources**. Wired as `bun run f-regression` beside the parity gates in
`deploy-web-demo.yml` and `release.yml`. Tailwind vendored: Play runtime v3.4.17 committed at
`demo/vendor/tailwind/` (sha256 in its PROVENANCE.md), CDN `<script>` removed — the demo now
has **zero network dependencies**, matching the app's no-INTERNET posture (closes the F6
README follow-up too).

## Wave G — 2026-09-01: geo OQ backlog measured (ARC-027/028/029)

Instrument: `GeoOqSweepTest` (`-PgeoSweep=true`) — same-JVM baseline-vs-variant decode of a
deterministic 250×3 stratified sample on en/qwerty + en/dvorak + en/weird across
CLEAN/TYPICAL/SLOPPY, plus the full ~8.5k-trace local real-corpus replay via the shared
`GeoLocalCorpus` loader (identical rows/geometry to the official A/B replay gate). Full
method + numbers: `docs/specs/geometric-swipe-engine.md` § "As-Built Notes — Wave-G OQ
sweep (2026-09-01)".

**ARC-027 CLOSED — tried, measured, DECLINED.** OQ-9 direction-aware overshoot clamp
implemented (`PathScorer.endAnchorDistanceKw` + `endOvershootCostScale`, bit-identical
no-op at the 1.0 default, unit-tested for the no-op identity, overshoot-only discount and
undershoot/orthogonal invariance). Sweep {0.5, 0.25, 0.0}: synthetic deltas all within
±0.1 pt and never positive; real corpus (n=8521) flat 55.2/68.0/71.7 at every scale. The
audit's "universal small lift" premise fails because `endNeighborRadius` (1.1 kw) already
exceeds real overshoot magnitudes (≤0.4 kw) — the symmetric penalty was already
effectively overshoot-free. Knob retained as a documented ablatable no-op.

**ARC-028 CLOSED — tried, measured, DECLINED (finding recorded).** OQ-10 length-scaled
ordering slack implemented (interior-only, length-gated location tunnel; bit-identical
no-op at `orderingSlackTunnelW = 0`). Sweep (W∈{1,2} × minLen∈{2,3,4} kw): SLOPPY lifts
everywhere (dvorak top-3 +3.6) but CLEAN/TYPICAL top-1/top-3 regress on every layout
(dvorak CLEAN top-1 −5.9; qwerty CLEAN top-3 would break its 0.97 floor) — the global
tunnel's failure mode survives the length gate and strict endpoints. Real-corpus
counterpoint recorded: +3.0 top-1 / +1.9 top-3 overall on the 8,521-trace local replay
(len≥4 driven) — a per-decode ADAPTIVE slack gate is the recorded follow-up; a static
default is barred by the any-tier non-regression rule. Knobs retained default-off.

**ARC-029 CLOSED — tried, measured, DECLINED (premise refuted).** OQ-11 implemented end
to end (turn angles → `ProcessedGesture.reversalCount` in the preprocessor; optional
reversal-scaled confidence temperature in the ranker, default-off, provably
ranking-neutral). Real-corpus measurement (8,505 decodes): reversal count does not
predict error (bucket accuracies 55.7/54.0/57.1/56.0% — flat), and the posterior is
already under-confident (mean top-1 conf 28.3% vs 55.3% acc), so flattening degrades ECE
monotonically (0.272→0.338 across slopes 0→1.0). Slope stays 0; count stays computed for
future corpora. Recorded follow-up: global temperature is too high for calibration —
sharpening, not flattening, is the direction (cosmetic today; nothing consumes absolute
confidence). Wave-G geo OQ backlog (ARC-027/028/029) is now fully closed: three
mechanisms built, three measured declines, zero default/behavior changes shipped.

## ARC-108 — 2026-09-02: per-decode adaptive gate for the OQ-10 ordering slack (measured, PARKED)

**Verdict: tried, measured, PARKED — no default change ships.** The Wave-G follow-up
(gate the +3.0-real-corpus ordering slack on a per-trace sloppiness signal so CLEAN
traces keep exact ordering) was built end to end and swept; no threshold satisfies both
ship bars simultaneously, and per the campaign rule the mechanism is committed
default-off with the tables recorded.

**Phase A — signal selection** (`GeoOqSweepTest.oq10a`, 750 traces/tier × qwerty/dvorak/
weird + 8,521 real traces; five candidates: mean interior turn, non-corner wobble,
resample arc-loss, raw-to-chord residual, nearest-key residual, plus ARC-029's reversal
count). Winner: **non-corner wobble** — the mean physical-frame turn angle (deg) at
interior resampled points below the 55° corner threshold, i.e. jitter that is not
letter geometry. Separation at the ≥7° threshold: CLEAN passes 3.5–8.4% per layout,
TYPICAL 27–47%, SLOPPY 61–68%, REAL corpus 74.2%. Runner-up (raw-to-chord residual)
separates REAL from TYPICAL slightly better but has a worse CLEAN tail; reversal count
does not separate at all (real median 1). Wired as
`ProcessedGesture.nonCornerWobbleDeg` (preprocessor, O(N)) →
`PathScorer.locationDistance` gate knob `orderingSlackWobbleGateDeg` (0 = ungated;
default-off overall via `orderingSlackTunnelW = 0`); unit tests pin closed-below/
open-at-threshold, gate-0 static equivalence, length-gate independence, straight/corner/
jitter signal semantics, and negative-knob fail-fast.

**Phase B — threshold sweep** (`oq10b`, same instrument as Wave-G: 250×3 stratified
sample × 3 layouts × 3 tiers + full local real-corpus replay; W=1, minLen=3 kw, gate
∈ {6,7,9,11}° plus minLen=4 variant). Synthetic top-1/3/5 deltas vs off, worst cell per
config, and real-corpus overall top-1 delta:

| config    | CLEAN worst | TYPICAL worst | SLOPPY range (top-1) | REAL top-1 |
|-----------|------------|---------------|----------------------|-----------|
| W1static  | −5.9 (dvorak) | −2.9 (dvorak) | +1.3…+3.2 | **+3.0** |
| gate6     | −0.1 | −1.1 (qw/dv top-1) | +1.7…+2.3 | +2.7 |
| gate7     | −0.1 | −0.8 (weird top-3) | +1.5…+2.1 | +2.5 |
| gate7len4 | −0.1 | −0.8 (weird top-3) | +1.5…+2.1 | +2.5 |
| gate9     | 0.0  | −0.7 (dvorak top-1) | +1.5…+2.1 | +2.0 |
| gate11    | 0.0  | **−0.3 (weird top-3/5)** | +1.2…+1.9 | **+1.4** |

Ship bars: (a) no synthetic tier beyond ±0.1 pt on any layout; (b) real corpus retains
≥ +2.0 of the static +3.0. The frontier is monotone and the bars never overlap: every
config retaining ≥ +2.0 (gate ≤ 9°) regresses TYPICAL by −0.3…−1.1 somewhere, and the
only near-clean config (gate11: qwerty+dvorak fully green, weird TYPICAL top-3/5 −0.3)
retains just +1.4. Root cause is the same pinch Wave-G measured from the other side:
real traces occupy the TYPICAL-to-SLOPPY band of every sloppiness signal, so any gate
open for most real traces admits a material fraction of TYPICAL synthetics. Determinism:
the full `oq10b` sweep re-run produced bit-identical tables. Signal caveat recorded: the
corpus's quantized coordinates (~0.03 kw y-quantum) inflate wobble/residual slightly vs
live sub-pixel touch streams, so any future on-device gate calibration should re-measure
thresholds on device-captured traces rather than reusing these.

Disposition: gate mechanism + both instruments committed default-off and re-runnable
(`-PgeoSweep=true -PoqOnly=oq10a|oq10b`); defaults bit-identical (slack W=0); no floor
moved; ARC-108 CLOSED as measured-and-parked.

---

## Round 2 closure (2026-09-03) — language-support todo + maintainer decisions

Executed in full per `docs/plans/2026-08-30-full-backlog-campaign.md` §Round 2 (wave table
there carries per-wave commits). Item dispositions, authoritative:

- **Langpacks distribution**: `langpacks` pre-release LIVE (23 assets, sha256 table;
  prerelease + non-`v*` tag ⇒ provably invisible to F-Droid's `releases/latest` HTTP check
  and to `release.yml`). README/wiki/in-app pointers repointed. Manifest-version normalize
  DEFERRED (byte-identity rule).
- **uk/bg/mk/he ROUTED** (`e99bccc1`,`1b17c318`): v3 fp16w models + goldens sha-verified
  against checklist §2.2; PROVISIONAL tier; emit-budget sweep now covers all six script
  langpacks — zero over-budget words; he's true max is 14 frames (abjad), handled as a
  documented per-language truncation-floor override (he→12), budget constant untouched.
- **Turkish DECIDED**: permanent TAP+geometric (no ı NFD decomposition, 73.34% projectable;
  fold = unmeasured serving-semantics change). Reopen only with ML-side tr-fold holdout
  evidence. Pinned in `CtcLanguagePresetTest`; prose in CtcScriptSupport + guide.
- **ARC-054 CLOSED** (`f6cc401d`): ru (validation tier) + el (early, no accuracy figure —
  92.12 ban now an assertion) announced in the pending v1.6.0 notes; 497 chars. Pin cleared,
  then correctly re-seeded `SERVED_BUT_NOT_YET_ANNOUNCED = {uk, bg, mk, he}` by M-LANG.
- **ARC-060 EXECUTED** (app `128c93f8`; ML `66c60ad`,`8778fef`): geometry+fixture+parity as
  one unit; new fixture `8951d7a3…` (159,778 B, basename `source_onnx` ⇒ ru LOW-6 discharged).
  Rider 1: en control reproduces 4.673e-4; ru replica agreement now **exact 0.0** (old
  geometry was 3.354e-3 = 7× en tolerance — the actual root cause, now closed).
  Rider 2 **BLOCKED on-device**: the 85.30±0.207 one-shot confirm needs `~/ctc-train`
  (Yandex valid-10k + seed evals) — OWED on the training box. Every published ru number
  predates the new geometry until that runs.
- **ARC-104 CLOSED** (`2fa6d632`): no-ADB-testing policy rescinded; prefer Saga/Pixel,
  never UI-test the Termux host; ew-cli/pure remain first choice.
- **ARC-107 CLOSED** (`1040f60b`,`62d6371b`): "escape" aliases "esc"; pure + instrumented pins
  (the old instrumented test pinned the MISS and would have gone red).
- **ARC-111 CLOSED in code** (`2b39c764` launcher DayNight + light palette; `26f3bf36`
  keyboard unset-theme pref now follows uiMode — the reproduced symptom's root). Visual
  confirmation on both devices owed to N-DEV.
- **ARC-113 CLOSED** (`48cd6bfb`): minSdk 21→24 (maintainer-approved) + ORT 1.21.1; p_align
  0x4000 verified empirically on AAR AND packaged APK; goldens bit-stable (2,120 pure green);
  lockfile stdlib-common trap fired and was hand-restored; `MinSdkApiUsageDriftTest` deleted
  per its own contract. User-facing Android-7.0+ claims updated (`434d2b0d`).
- **Swipe playground** (`5cb4a719`,`335fa9a3`,`0646fd53`,`0bdcfca2`): existing mechanism was
  PARTIAL (traces+word behind global collection prefs; no geometry/ranking/latency/export).
  Now: explicit-session recording while SwipeDebugActivity is open, per-key hit rects,
  displayed-bar ranking+scores, decode latency, in-activity export/share/clear, dedupe vs
  global store. Occlusion-A/B capture path complete; 8-step visual checklist → N-DEV.
- **Translation QA** (12 commits `129d3537`..`cfb98e03`): full back-translation sweep, all 21
  locales × 874 strings; 52 fixes (worst: tr backspace≡"undo" ×6, zh-rCN Tab/Tag collision,
  fa clipboard "fever"); both maintainer-flagged items (vi "Do Thái", lv "kirilicas")
  CONFIRMED CORRECT via CLDR. Placeholder mismatches: 0. Highest-value native review: tr, vi.
- **Doc truth**: M-DOCS reconciled HANDOFF/todo/guide/skill/ledger; three todo claims were
  themselves wrong (no stale `84ac284d` cites; en 98,140 correct — 98,122 is the CTC trie's
  distinct a–z surfaces; ARC-058 measured memory, latency still open). ML repo fully pushed
  (`4f01961`, then `8778fef`); "phantom" `7343355` was real-but-unpushed.

Owed forward: N-DEV device drive (daily-typing suite both phones + ARC-111/playground visual
checklists + per-script latency B2 via ew-cli); rider-2 ru probe (training box); native review
tr/vi; announcement of uk/bg/mk/he at next notes edit; manifest-version normalize on next
pack rebuild.
