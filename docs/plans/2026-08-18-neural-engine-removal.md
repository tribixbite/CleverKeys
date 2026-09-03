# Neural swipe engine removal — implementation plan (2026-08-18)

Decision: **remove the neural swipe engine entirely.** CTC replaces it (89.31 vs 74.62 top-1 on
test-2400); geometric covers what CTC cannot serve. Cost today: 10.3 MB of APK
(`swipe_encoder_android.onnx` 5,317,537 B + `swipe_decoder_android.onnx` 4,975,510 B) plus
~30–45 MB of heap when built.

Evidence base: `docs/history/audits/2026-08-17-neural-vs-ctc-parity.md` — **preserve verbatim**, it is
cited throughout and is the record of what this removal gives up.

## A. Load-bearing decisions (do these first; the rest is mechanical)

**A1. Mode enum becomes `{CTC, GEOMETRIC}`, default `"ctc"`.**
`Config.kt:320` `Defaults.SWIPE_ENGINE_MODE` `"neural"` → `"ctc"`. No stored-pref migration: the
pref shipped in `96cb37e3`, not an ancestor of v1.5.0, so no user has ever written a value.
`SwipeEngineRouter.Mode` (`swipe/SwipeEngineRouter.kt:68-107`): delete `NEURAL` and `HYBRID`
(hybrid was neural-on-QWERTY + geometric-elsewhere — with neural gone it has no residual
meaning). `fromPref` (`:100-105`): `"geometric" -> GEOMETRIC; else -> CTC`, so any legacy or
imported `"neural"`/`"hybrid"`/garbage string lands on the default and never crashes (backup
import accepts arbitrary strings, `backup/SettingsValidation.kt:342`). `Engine` (`:53-65`):
delete `NEURAL` and `NONE` — `NONE` existed only for "non-QWERTY layout in NEURAL mode" and no
surviving mode can produce it. `Config.isSwipeTypingSupportedForLayout` (`Config.kt:1241-1257`)
loses its last caller → delete it and `SwipeLayoutSupportTest` /
`SwipeLayoutSupportInstrumentedTest`.

**A2. The audit-M1 fallthrough redirects to geometric.** This is the change that keeps
Italian-on-QWERTY swiping. `InputCoordinator.performCtcSwipeTyping` (`:713-787`): the
`!CtcEngineAdapter.supportsLanguage(language)` branch (`:725-741`) currently splits
neural-on-QWERTY / geometric-elsewhere; it becomes unconditionally
`performGeometricSwipeTyping(...)`. `prewarmGeometricEngine` (`:796-840`): delete the
`warmNeuralEngineAsync()` arm (`:831-832`); the `else ->` arm becomes the whole non-CTC-served
case. Delete `dispatchNeuralSwipeTyping` (`:540-573`), `performSwipeTyping` (`:850-…`), and the
`Engine.NEURAL`/`Engine.NONE` branches in `handleSwipeTyping` (`:507`, `:522-528`).

**A3. The language detector re-homes into `PredictionCoordinator`.** `UnigramLanguageDetector`
(keep the file untouched) is owned by `onnx/SwipePredictorOrchestrator.kt:70` and fed on EVERY
commit — CTC and geometric included — from `SuggestionHandler.kt:1529`. That call is
try/catch-wrapped, so a botched re-home fails *silently*. Port `trackCommittedWord` (orchestrator
`:704-716`, minus the `vocabulary.updateLanguageMultiplier` line, whose consumer dies),
`clearLanguageHistory` (`:736-738`), `getLanguageScores`/`getDetectedLanguage` (`:723-730`), and
the lazy `initializeLanguageDetector` (`:675-695`). Re-point `SuggestionHandler.kt:1529` and
`CleverKeysService.kt:651`. Side win: today the first commit constructs the orchestrator plus a
98k-word `OptimizedVocabulary` even in ctc mode.

**A4. `OptimizedVocabulary` deletes whole — it is 100% neural-only in production.** All 23 public
methods are called only from `onnx/SwipePredictorOrchestrator` / `onnx/PredictionPostProcessor`
(sole `filterPredictions` caller: `PredictionPostProcessor.kt:117`), or are dead. Tap typing has
its own stack (`WordPredictor.kt:826-958, 1071-1130, 383`). The `MultiLanguageManager.kt:107` and
`MultiLanguageDictionaryManager.kt:62` instances are write-only/unreachable. **Three loose ends
that MUST be handled or something breaks silently:**
1. `LanguagePreferenceKeys.migrateToLanguageSpecific` (`:66`) is invoked ONLY from inside
   `OptimizedVocabulary` (`:223, :1724, :1838`). Re-home into `DictionaryManager` or
   `ManagerInitializer`, or pre-v1.1.86 upgraders lose custom/disabled words.
2. `DictionaryManagerActivity.kt:467-468` and `PreferenceUIUpdateHandler.kt:107,112,139,147` drop
   their orchestrator reload calls (CTC self-invalidates via content-hash memo).
3. `CoreImeHygieneDriftTest.kt:546-612` source-scans `OptimizedVocabulary.kt` — retarget to the
   surviving alias-floor sites.
Dead alongside: `VocabularyCache/Trie/Types/Utils.kt`, `NeuralVocabulary.kt`,
`MultiLanguageDictionaryManager.kt`. **Trim, do not delete,** `MultiLanguageManager.kt` —
`WordPredictor.kt:528-535, 665` still use it. **Keep:** `NormalizedPrefixIndex`,
`AccentNormalizer`, `BinaryDictionaryLoader`, `ContractionJsonReader`, `LanguagePreferenceKeys`,
`SuggestionRanker`.

**A5. `onnx/ModelLoader.kt` SURVIVES** — `CtcEngineAdapter.kt:22,159` builds its ONNX session
through it. Everything else in `onnx/` deletes. Its "neural" hits are the Android **NNAPI** and
Qualcomm QNN provider names (`:59-60, 248, 253`) — legitimate grep remains. The
`onnx_xnnpack_threads` pref survives but **its only UI is in the dying `NeuralSettingsActivity`**
— re-home the slider into `CtcSettingsActivity` and retarget `SettingsActivity.kt:584`.

**A6. Renames that keep behaviour but purge the word.** `PredictionSource.NEURAL_SWIPE` → `SWIPE`
(it is the generic "last commit was a swipe" marker for ALL engines, `SuggestionHandler.kt:597`;
closes todo m-1). `SuggestionOrigin.NEURAL_BEAM` → delete, fallbacks become `CTC`.
`NeuralPerformanceStats` → `SwipePerformanceStats` (engine-agnostic selection tracking; only the
inference-time writer dies). `NeuralLayoutBridge.kt` deletes; the shared dynamic-height calc stays
as `KeyboardDimensionsHelper`. `NeuralPredictionSection.kt` → `SwipeTypingSection.kt`.

**A7. `SwipeCalibrationActivity` deletes** (1,184 lines, hard-depends on the engine, fatal-dialogs
without the models). Keep `SwipeMLData.ENGINE_NEURAL` and the `"neural_calibration"` row handling
for legacy DB/export compat; the write paths die.

## B. Inventory

**Delete (26 files + 2 activities):** `NeuralSwipeTypingEngine`, `AsyncPredictionHandler`,
`OptimizedVocabulary`, `Vocabulary{Cache,Trie,Types,Utils}`, `NeuralVocabulary`,
`MultiLanguageDictionaryManager`, `SwipeTokenizer`, `SwipeTrajectoryProcessor`,
`TrajectoryFeatureCalculator`, `TrajectoryObjectPool`, `CoordinateNormalizer`,
`ModelVersionManager`, `NeuralModelMetadata`, `NeuralLayoutBridge`, `NeuralSettingsActivity`,
`SwipeCalibrationActivity`, and all of `onnx/` except `ModelLoader.kt`. Also `src/benchmark/`
(never wired) and `src/main/{ck_sources,missing_sources}.txt`.

**Assets (APK −~12.3 MB, repo −~46 MB):** delete both neural `.onnx`, `tokenizer_config.json`,
`model_config.json`, `assets/prefix_boosts/` (11 `.bin`, 21 MB), the stray top-level
`assets/models/*` and `assets/{swipes,test_swipes}.jsonl` (all currently packaged via
`build.gradle:219`). **Keep** `ctc_swipe_encoder.onnx`, all dictionaries, `noCompress 'onnx'`,
both onnxruntime deps.

**Prefs — move (do not copy) to `SettingsValidation.DEPRECATED_KEYS`:** the 19 `neural_*` keys +
`neural_preset`; delete `PATTERN_DEFAULTS` and add **prefix matching** for
`neural_prefix_boost_{multiplier,max}_<lang>` since `DEPRECATED_KEYS` is exact-match.
`SettingsDefaultsDriftTest:100-115` fails on overlap, which is the intended guard. Also deprecate
`swipe_debug_show_raw_output`, `swipe_show_raw_beam_predictions`, `swipe_beam_autocorrect_enabled`,
`finger_occlusion_offset`. **Keep** all 8 `autocorrect_*` sliders — they are tap-path
(`WordPredictor.kt:2149-2297`), not neural.

**Tests:** delete with their `build.gradle` runner-list lines **in the same commit** —
`TestRunnerListDriftTest:117-122` enforces this.

**Docs:** delete `docs/wiki/specs/typing/neural-prediction-spec.md` (fix its 6 inbound wiki links
first or the Astro deploy breaks); move 5 specs + `ONNX_DECODE_PIPELINE.md` to `docs/history/`;
modify current-state claims across README, CHANGELOG (unreleased 1.6.0 only), fastlane
`full_description.txt`, site, web demo, scripts. **Keep as-is:** all `docs/audit/`, `docs/eval/`,
`docs/history/`, shipped CHANGELOG sections, existing fastlane changelogs.

## C. Ordered steps — each compiles, each committable

Gates: **G1** `sh gradlew compileDebugKotlin` · **G2** `compileDebugAndroidTestKotlin` ·
**G3** `runPureTests` + `runMockTests` · **G4** `lintDebug` · **G5** `assembleRelease` (proguard) ·
**G6** site/web-demo build.

1. **Routing flip, no deletions.** A1 + A2 + enum/picker updates. After this commit neural is
   unreachable but still compiled — **the tree is releasable at every subsequent point.**
   Gates G1 G2 G3 G4.
2. **Re-home survivors.** A3 detector, A4 loose-end 1 (`migrateToLanguageSpecific`), A5 ONNX-threads
   slider. Gates G1 G2 G3.
3. **Delete engine core + assets.** Gates G1 G2 G3 G4 **G5**.
4. **Delete settings surface + prefs.** Includes the locale-string mirror across 21–23 files, or
   lint `ExtraTranslation` fires. Gates G1 G2 G3 G4 G5.
5. **Rename pass.** A6. Gates G1 G2 G3.
6. **Docs / site / demo / scripts / fastlane.** Append a superseding ADR; mark ADR-001/002/005/006
   `Superseded` without rewriting them. Gate G6.
7. **Verification.** ew-cli instrumented run + a manual device pass.

## D. Routing — no cell loses swipe

Layout classes: **L1** QWERTY-named Latin a–z-complete · **L1b** `latn_qwerty_az`/`_tly`
(`w` corner-only) · **L2** non-QWERTY Latin a–z-complete · **L3** letter-incomplete Latin ·
**L4** non-Latin or script absent. Languages: **A** = CTC-served (en/fr/de/es) · **B** = all others.

| Mode (before) | L1+A | L1+B | L1b+A | L1b+B | L2+A | L2+B | L3 | L4 |
|---|---|---|---|---|---|---|---|---|
| neural (old default) | NEURAL | NEURAL | NEURAL | NEURAL | **NONE** | **NONE** | **NONE** | **NONE** |
| hybrid | NEURAL | NEURAL | NEURAL | NEURAL | GEO | GEO | GEO | GEO |
| geometric | GEO | GEO | GEO | GEO | GEO | GEO | GEO | GEO |
| ctc | CTC | NEURAL | GEO | NEURAL | CTC | GEO | GEO | GEO |

| Mode (after) | L1+A | L1+B | L1b+A | L1b+B | L2+A | L2+B | L3 | L4 |
|---|---|---|---|---|---|---|---|---|
| **ctc** (new default; absorbs `neural`/`hybrid`/unknown) | CTC | **GEO** | GEO | **GEO** | CTC | GEO | GEO | GEO |
| geometric | GEO | GEO | GEO | GEO | GEO | GEO | GEO | GEO |

`Engine.NONE` becomes unconstructible. Cells that change: L1+B and L1b+B go NEURAL→GEOMETRIC —
the Italian-on-QWERTY device is L1+B, so swipe continues via geometric. Out-of-the-box users
(previously on the `neural` default) **gain** swipe on L1b/L2/L3/L4 where they had NONE.

## E. Risk register (abridged — full table in the commit for step 1)

| Risk | Catch |
|---|---|
| Tap typing degrades via `OptimizedVocabulary` deletion | Call-graph proves no tap edge; `runPureTests` + instrumented `PipelineCharacterizationTest` + manual smoke |
| Language detector dies silently (call site is try/catch-wrapped) | New pure test in step 2; `rg -n 'SwipePredictorOrchestrator' src/` must return zero after step 3 |
| Old-backup import writes 20 dead keys / shows ADDED noise | Move-to-`DEPRECATED_KEYS` + prefix matching; `SettingsImportPlanBuilderTest`, `SettingsDefaultsDriftTest` |
| Imported `swipe_engine_mode="neural"` hits a dead enum | `fromPref` else→CTC; `SwipeEngineRouterTest:152-157` |
| Test-file deleted without its runner-list line | `TestRunnerListDriftTest:117-122` fails the same commit |
| CTC loses its ONNX loader | `ModelLoader.kt` explicitly kept; `CtcLatencyGateTest` instrumented |
| Pre-v1.1.86 custom-word migration orphaned | Re-home in step 2 + a pure test at the new call site |
| Wiki deploy breaks on dead links | Fix the 6 inbound links in the same commit; G6 |

## F. What is lost — recorded for sign-off

- **Main-dictionary fuzzy rescue** (`OptimizedVocabulary:611-746`) — the audit's "single largest
  behavioural deletion". **Deferred, not silently dropped**: needs a follow-up ticket to add a
  post-beam rescue inside `CtcEngineAdapter`, which has the merged lexicon and raw beam in hand.
  The algorithm is portable and not model-dependent.
- **Custom-word fuzzy autocorrect** — dropped; CTC decodes custom words directly from the merged
  trie, so only the sloppy-swipe rescue of a custom word is lost.
- **Secondary-language swipe blending** — ~~dropped~~ **SHIPPED as the dual-trie option named
  here.** `CtcEngineAdapter.kt:986-999` resolves a `secondaryLexicon` alongside the primary and
  decodes both against the SAME emission matrix (the encoder runs once). The one restriction
  discovered while building it: a secondary is only expressible when it shares the primary's
  emission alphabet (`sharesEmissionAlphabet`), because a Latin secondary alongside a Cyrillic
  primary would produce words with no keys on the board being swiped. A missing secondary lexicon
  degrades to a single-language slate rather than failing.
- **~11 `neural_*` beam prefs, the Neural Settings screen, `neural_strict_start_char`,
  `finger_occlusion_offset`** — dropped; swipe tuning becomes `ctc_beam_width` + geo knobs.
- **Langpack prefix-boost tries** — dropped; the importer must keep *tolerating* the file so
  existing packs still import.
- **it/pt/sv + langpacks on QWERTY move neural→geometric** — per-language delta unmeasured; the
  honest statement is "likely a few points down until CTC serves them" (en proxy: 74.62 vs 67.50).
- **`SwipeCalibrationActivity`** — deleted; it cannot run without the models.

## G. Expected `rg -i neural` remains

Legitimate hits after completion: historical docs (`docs/audit/`, `docs/eval/`, `docs/history/`,
`docs/migrate/`); shipped CHANGELOG sections and existing fastlane changelogs, plus the new
changelog/ADR announcing the removal; measured comparison rows kept as evidence in the CTC and
geometric specs; `onnx/ModelLoader.kt:59-60,248,253` (NNAPI / Qualcomm SDK are OS/vendor API
names); `SwipeMLData.ENGINE_NEURAL` + `"neural_calibration"` for legacy row compat; the
`proshian/neural-swipe-typing` credit in `README.md:466-467`; `CLAUDE.md:128`; corpus-provenance
comments in `scripts/build_local_corpus_replay.mjs`.

**Zero hits expected** in `src/main` (outside `ModelLoader` and the ML constants), `src/test`,
`src/androidTest`, `res/`, `AndroidManifest.xml`, `build.gradle`, proguard, fastlane
`full_description`, `site/`, `web_demo/` post-regeneration, and `.github/` current-state text.

## Open items for the maintainer

1. ~~The fuzzy-rescue port to CTC is deferred and needs a ticket.~~ **DONE** — shipped as
   `swipe/ctc/CtcFuzzyRescue.kt`, an alphabet-scoped post-beam rescue inside the CTC adapter.
2. ~~`swipe_engine_mode_desc` retranslation across 21 locales is flagged, not done (English
   fallback interim).~~ **DONE (ARC-066, 2026-09-01)** — `swipe_engine_mode_desc` is present in
   all 21 `res/values-*/strings.xml`; the wider locale pass also filled the plurals gap and
   removed all 373 `MissingTranslation` suppressions. Machine translations still await maintainer
   native review.
3. The it/pt/sv accuracy delta on QWERTY is unmeasured. **Still open** — no swipe corpus exists
   for those three, so this cannot be closed by effort alone.
4. Deleting the `model/` training toolchain vs archiving it on a branch is a judgement call.
   **Still open.**
5. ~~Instrumented (ew-cli) verification and a manual device pass are required before release.~~
   **DONE (Waves J/K, 2026-09-02)** — Wave J ran the full ew-cli suite (1,466 tests, Pixel7 API
   34); Wave K completed device passes on both authorized phones, and as of 2026-09-03 both run
   the byte-identical release build.
