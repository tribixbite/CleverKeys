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
| ARC-007 | **Termux deletion strategy never decided** (WP9 R-1 step 7): `SuggestionHandler.kt:1092,1195,1586,1649,2220` retain key-event deletion branches, untested either way; the owed dedicated Termux instrumented test covers only the auto-space half. Decide keep-vs-unify + write the test. | 3-core-ime step 7 |
| ARC-008 | **R8/ProGuard still off behind the 2025 "REPRODUCIBILITY TEST" comment** (`build.gradle:284-286`); `proguard-rules.pro` (244 lines) dormant. Size motive is spent (APK 33 MB post-ADR-011); what remains is dormant tooling + shrink headroom. Re-enable behind the full ew-cli soak + reflection-keep audit, or delete the rules and record the decision. | 3 docs independently |
| ARC-009 | **`.gitignore` fixture trap**: `:170` global `*.json` still swallows test fixtures (current goldens were `git add -f`'d; `src/androidTest/assets/dictionaries/en_enhanced.json` is silently ignored today). The multiscript plan requires six new per-script fixtures ×2 copies; nothing records the force-add requirement. Add `!src/test/resources/**/*.json` + `!src/androidTest/assets/**/*.json` before those land. | ctc exec brief |
| ARC-010 | ~~**`BigramModel` is a 174-pair hardcoded table feeding a live ≤10× tap-ranking multiplier** (`BigramModel.kt:62,184,228,271` → `WordPredictor.kt:2020`); `loadFromFile` (`:342`) has zero callers so the six shipped `assets/bigrams/*.json` are never read; the planned A/B venue (`SwipeCalibrationActivity`) was deleted. Load the assets or delete both.~~ **RESOLVED 2026-08-28** — assets loaded as the next-word seed's source of truth (`StaticBigramSeed.kt` + `BigramModel.loadStaticContinuationsAsync`); the multiplier keeps its hardcoded table for a measured reason, see the decision entry below. | 07-17 audit + roadmap WP4 |
| ARC-011 | **Provenance captured, never shown**: `source_package` written everywhere, zero UI consumers — no "via ⟨app⟩" line; the injection risk in the private-copy design §6.2/§6.6 was accepted *because of* this display. `docs/wiki/specs/clipboard/private-copy-spec.md:31,:62` claims it shipped. Render it + correct the spec. | private-copy design §6 |
| ARC-012 | **#79 settings header flicker**: unfixed, and the audit's "LazyColumn recomposition" diagnosis is wrong — the screen is `Column`+`verticalScroll` (`SettingsScreen.kt:92-99`). Re-diagnose before fixing. | gh-issue audit #79 |
| ARC-013 | **UT-5 / UT-7 contraction-ranking deferrals never re-measured**: "doesnt"→"doesn't" top-1 rank post-contraction-rework unknown; no sentence-start ranking signal exists at all (0 hits for `sentenceStart|afterPeriod`; `capitalizeIWord` fixes casing only). | v1.5.0 UT round |

## P3 items

**IME/engines**
- ARC-014 no geometric prewarm on mid-session language toggle → one synchronous 150–400 ms build (`prewarmGeometricEngine` has no language-switch trigger).
- ARC-015 `GeometricEngineAdapter.dictionaryMemo` single-slot (`:524`) — en↔fr toggle re-reads CKDT each switch; mirror `CtcEngineAdapter.trieMemos` 2-slot LRU.
- ARC-016 no test pins `CleanupHandler` executor shutdown; `SuggestionHandler.isPredictionExecutorShutdown()` (`:434`) has zero callers.
- ARC-017 owed `KeyEventHandlerSliderTest` for `moveCursorSel` d==0 (`KeyEventHandler.kt:817-819`).
- ARC-018 `pref_secondary_prediction_weight` tap-only; `CtcRankMerger.kt:20-27` hard-codes 1000/920 — thread pref or fix slider copy.
- ARC-019 CTC never contested on LOCAL combined (8,521 traces; geometric beat neural there) nor any robustness tier — now cheap via `CtcReplayEngine`.
- ARC-020 next-word has no cold-start source (learned-only; dead until phrase typed ≥2× at ≥5%) — §4.2-2 static seed unadopted (see ARC-010).
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
