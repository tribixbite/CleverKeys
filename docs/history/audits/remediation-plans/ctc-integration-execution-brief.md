# CTC integration — execution brief (planning review of APP_INTEGRATION_PLAN.md)

> **HISTORICAL — superseded 2026-08-18; banner added 2026-08-25 (checklist item MEDIUM-3).**
> Statements below that "Default engine stays `neural`" (§2 D7, §6) and that "Q1 model choice
> is SUPERSEDED-PENDING — a new model is training" (§7 sign-off) were true on 2026-08-11 and
> are now false: **no model swap is pending** (Phase N is terminal; Phase P shipped nothing
> into the APK), **`ctc` is the default engine**, and **the neural engine is deleted**
> (ADR-011, 2026-08-18). Current truth lives in `docs/specs/ctc-swipe-engine.md` and
> `docs/specs/ctc-architecture-and-multiscript-guide.md`; multi-script wiring work is planned
> in `docs/plans/2026-08-25-ctc-multiscript-wiring-plan.md`. Read the rest as a record of the
> 2026-08-11 planning review, not as instructions.

**Reviewed:** 2026-08-11, app HEAD `7ec6c11e`. **Subject:** `~/git/swype/CleverKeys-ML/ctc/APP_INTEGRATION_PLAN.md`
(1,821 lines, written 2026-08-08 vs app `79ddfb0f`). Verdict: **app-side diffs apply near-verbatim
(~95%, line offsets only); the ML-side inputs (model, preset, fixture) are TWO generations stale** —
Phases G/H/I-A (ML commits `2ca4ecb`+) supersede D1/D2 and partially reframe D6.

## 1. Drift catalog

**App-side (all hunks apply by content; only line offsets moved — commits since baseline touched
Config.kt +3, SettingsDefaults.kt +1, SettingsActivity.kt +1, SettingsPersistence.kt +1, plus the
androidTest CTC benchmark `74714245`):**
- `Config.kt` — anchors verified at :319/:325 (Defaults), :637/:641 (fields), :931/:934 (refresh). Apply as written.
- `swipe/SwipeEngineRouter.kt` — pre-image exact (:32-85). Extend KDoc mode list (:11-16) too.
- `InputCoordinator.kt` — anchors: route `when` :440-443, `shutdown` :306, geo adapter :525-528, `beginSwipeCapture` :487, `prewarmGeometricEngine` :577-592 (pre-image exact).
- `SuggestionProvenance.kt` (:26/:56-57/:192), `SuggestionBar.kt` (:405), `NeuralPredictionSection.kt` (:50), `res/values/strings.xml` (:122), `SettingsNavigation.kt` (:31), `AndroidManifest.xml` (:87), `SettingsDefaults.kt` (:263-267), `SettingsActivity.kt` (:579) — all match; apply as written.
- `GeometricSettingsActivity` private `ParameterSection`/`ParameterSlider` now at :151/:179 (plan said 150-222) — copy verbatim.
- API surfaces verified: `ModelLoader.loadModel(path, name, enableHardwareAcceleration=true, xnnpackThreads=2)` → `LoadedModel(session, executionProvider, modelSizeBytes)`; `PredictionTaskRunner` (defined in InputCoordinator.kt, same package) with `cancelAndSubmit`/`shutdown`; `KeyboardGeometry.computeKeyRects` + `Keyboard2View.geometryParams()` (:1225); `CtcLayout.of`, `CtcEmissions.sliceFromHead`, `CtcSwipeDecoder(model, layout, trie, params).decode(px,py,pt)`, `CtcLexiconTrie.loadStrippingNonAlphabet` (:186), `CtcFeaturizer.{MAX_KEYS=64, RESAMPLE_LENGTH=64, buildPaddedLayout, normalizeRawY}`, `KeyValue.getKind/Kind.Char`, `Defaults.ONNX_XNNPACK_THREADS` (:303). Plan's `Defaults_ONNX_THREADS_FALLBACK` property → replace with direct `Defaults.ONNX_XNNPACK_THREADS` per plan's own note.

**ML-side (SUPERSEDED — do not apply plan §1b/§1d values verbatim):**
- Plan fixture sha `a18ea58c…`/140,204 B is gone. Current `ctc/artifacts/ctc_model_golden.json` = **139,728 B, sha `ce3b5456ad13…`, source_onnx = `resbn80g_s1234.onnx` (sha `330cadfb…`), preset `[0.9, 4.0, 0.25, 0.25, 0.9882]`** (γ, λ, β, γp, βp; α=0). Schema unchanged (6 featurize + 4 beam + `layout` block) — `CtcParityTest` and the plan's `CtcEmissionModelParityTest` consume it without code change.
- `tunedV2` preset values in plan §1b (E1: 1.05/1.1/0.2/0.3734/0.9882) are the **Phase-E values for ch128**. The shipping preset must match the fixture's model: for resbn80g it is **γ 0.9, λ 4.0, β 0.25, α 0.0, γp 0.25, βp 0.9882** (RESULTS.md Phase G). Rewrite the tunedV2 KDoc accordingly (E1 rationale text is stale). **Model + preset + fixture move together** — enforce by asserting the copied fixture's `source_onnx_sha256` equals the shipped asset's sha256.
- Plan §1d ship command copies `ch128_s1234.onnx` — stale (see D1).

**Working-tree trap (found during review):** `src/test/resources/ctc/ctc_golden.json` already EXISTS
(61,760 B, old pre-model schema from `scratchpad/gen_ctc_golden.py`) but is **untracked and invisible**
because `.gitignore:170` has a global `*.json` rule (emoji-data leftover; negations only for `site/`,
`web_demo/`). So `CtcParityTest` passes locally but fails on any fresh clone. Both fixture copies
(`src/test/resources/ctc/` and `src/androidTest/assets/ctc/`) MUST be `git add -f`'d (or add
`!src/test/resources/ctc/*.json` + `!src/androidTest/assets/ctc/*.json` negations — preferred, durable).
Verify with `git ls-files` after commit.

## 2. Decision verdicts (D1-D8)

| # | Verdict |
|---|---|
| D1 | **REFRAMED — latency is off the table as a decision axis; accuracy/size/evidence-tier decide.** On-device benchmark (ew-cli run `66333372…`, todo.md `192084d5`): encoder is ~4% of the path; the whole candidate spread (0.265-0.919 ms) is smaller than one beam p50→p90 swing. So: `ch128` (2.8 MB, test t1 87.92, test-validated, D1's pick — stands on accuracy among test-validated candidates but its E1 fixture must be restored from ML git `e5c8ff3`); `resbn80g` (1.1 MB, test t1 87.68, test-validated, CURRENT fixture matches it); `resbn192i_fp16w` (3.05 MB, val t1 88.30, val-only, needs user-approved final test-2400 unsealing + fixture regen at preset `0.975/3.0/0.35/0.25/0.9882`). Latency mandate retired by user directive (size ≤5 MB bound) — see sign-off Q1. |
| D2 | **SUPERSEDED as written.** tunedV2 = the shipped model's fixture preset, whichever ships (ch128: E1 1.05/1.1/0.2/0.3734/0.9882; resbn80g: 0.9/4.0/0.25/α0/0.25/0.9882). |
| D3 | **VALID and now latency-load-bearing.** On-device full path = 24.3 ms mean / 42.0 ms p90, of which beam@100 is 23.1/40.9 (~95%). `ctc_beam_width` (10-300, default 100) is the REAL latency knob — future latency tuning targets it, not model choice. Default 100 validated at 24 ms mean. Confirm Phase-G config-B decode width in `PHASE_G.md` §6 during P0 (expected 100). |
| D4 | **VERIFIED.** `en_enhanced.json` present (1,834,729 B); `loadStrippingNonAlphabet` exists; Phase-G config B test-validated ON this exact trie+preset — the O3 lexicon risk is closed (λ=4.0 IS the app-trie-fitted value now). |
| D5 | **VERIFIED.** `normalizeRawY` (:171) is FUTO-contract-only; plan correctly bypasses it; fixture layout confirms uniform-frame letter coords. |
| D6 | **VALID for resbn80g** (not layout-augmented; QWERTY-Latin→CTC, else geometric is right). NOTE: Phase-H/I weights beat geometric on ALL measured a-z layouts (dvorak +12.7 t1) — if resbn80h/192i ships, a follow-up may route CTC on any a-z layout; keep plan routing for v1, file the follow-up. Router enum/pre-image verified; `swipe_engine_mode` is Str in SETTINGS_DEFAULTS (value add = no defaults change); `ctc_beam_width` IntV covered by SettingsDefaultsDriftTest scan (safeGetInt/putInt patterns). |
| D7 | VALID. Default stays `neural`; `fromPref` fallback protects downgrade. |
| D8 | **VERIFIED.** ModelLoader signature accommodates the second model incl. the parity test's `enableHardwareAcceleration=false, xnnpackThreads=1` call. Proguard `-keep ai.onnxruntime.**` at :193 — no new rules. |

## 3. Interaction requirements (audit `docs/history/audits/remediation/3-core-ime.md` §2026-08-11)

- **M-2 (warmUp/decode cancellation race) — REQUIRED, born-fixed.** The plan's `CtcEngineAdapter` clones the geo bug exactly: ONE `PredictionTaskRunner`, `warmUpAsync` AND `decodeAsync` both via `cancelAndSubmit`, prewarm fired from `onStartInputView` (CleverKeysService:687) → prewarm can cancel an in-flight decode; the result callback also replays captured `ic`/`editorInfo` with no stale-field guard. Since this is a NEW file, do not inherit a catalogued bug: (a) make warmUp submit-only-if-idle (skip if a task is queued/running) instead of `cancelAndSubmit`; (b) guard the replay like the neural path's `isReplayInputStillCurrent` precedent (InputCoordinator:461). Leave GeometricEngineAdapter to the audit's own remediation.
- **n-2 (untagged ML corpus) — DEFERRED-with-filing.** `performCtcSwipeTyping` calls the shared `beginSwipeCapture` (:487) whose `SwipeMLData("", "user_selection", …)` carries no engine/layout field — CTC traces will be indistinguishable from neural QWERTY traces in ML exports. Acceptable while `ctc` is opt-in-dark; MUST extend the audit's n-2 item to name CTC, and n-2's engine/layout tagging is a **hard gate before any default flip (O6)**.
- **m-1 (provenance) — bar origin COVERED by the plan** (`SuggestionOrigin.CTC` §1c-v, correct); **commit source REPEATS the mislabel**: `SuggestionHandler.kt:572` sets `PredictionSource.NEURAL_SWIPE` unconditionally for all swipe commits — CTC commits will be labeled NEURAL_SWIPE in context tracking. DEFERRED-with-filing (extend the existing hybrid-origin todo, memory/todo.md:85, to geo+CTC commit-source). If any new test pins this, mark it deliberately-wrong per the audit's m-1 instruction — never pin silently.
- **Trie build cost — REQUIRED (new, from the on-device benchmark).** `CtcLexiconTrie` build from `en_enhanced.json` measured **2,001 ms ON-DEVICE** (vs 90 ms desktop, 22× — `org.json` parse of the 1.8 MB file + `loadStrippingNonAlphabet`). 2 s anywhere near a synchronous keyboard-startup path is a P1 regression. Requirements: (a) the plan's design already builds lazily on the `PredictionTaskRunner` thread with `warmUpAsync` prewarm — KEEP that, but the M-2 born-fix above becomes doubly mandatory (a cancelled warmup here loses a 2 s build and the first swipe eats it); (b) the memo invalidates on ANY user-dictionary mutation → the NEXT swipe pays a full 2 s rebuild on the decode thread — the implementer must either rebuild opportunistically on mutation (background, off the decode critical path) or accept-and-document the one-swipe stall; (c) file a follow-up for a precompiled trie blob asset (the web demo's front-coded format, `tools/build_ctc_vocab.py`, is the precedent) — the durable fix for both cold start and rebuilds.

## 4. Test additions beyond the plan

- **`CtcPurityDriftTest` (NEW, required by convention):** `swipe/ctc/` has NO purity enforcement — clone `GeoPurityDriftTest` (scans `src/main/kotlin/.../swipe/geometric` for android/ORT imports) scoped to `swipe/ctc`, since spec NFR-1 demands the package stays Android/ORT-free and `OnnxCtcEmissionModel` now lives one level up. Register in `pureTestClasses` (`build.gradle`) — `TestRunnerListDriftTest` fails the build if you forget.
- Already registered: `CtcParityTest`/`CtcModuleTest` (build.gradle:489-490), `SwipeEngineRouterTest` (:456), `SuggestionProvenanceTest` (:506). Plan's modifications need no re-registration.
- Latency: **MEASURED on-device** (`CtcOnnxLatencyBenchmarkTest`, ew-cli run `66333372…` 2026-08-11, 2/2 PASSED — full tables in `memory/todo.md` § "CTC on-device latency", commit `192084d5`). Encoders (XNNPACK@2 mean): ch128 0.555 ms, ch192 0.919, resbn80 0.303, resbn72 0.265 (`fast_resbn80` is graph-identical to `resbn80g`, so proxies it). Full path ch128@beam100: featurize 0.12 + NN 1.02 + slice 0.006 + beam 23.1 = **24.3 ms mean / 42.0 ms p90** — comfortably inside the 100-300 ms neural budget; G3 answered. Latency gates model choice NO further; it gates the trie-build requirement (§3) and validates `ctc_beam_width`=100.
- Oracle precedent: no CTC oracle test needed for v1 (`CtcEmissionModelParityTest` end-to-end + fixture pin covers determinism; GeometricSwipeOracleTest stays geo-only per plan §3.3).

## 5. Phased execution checklist (each phase compiles + greens independently)

Conventions: `sh gradlew compileDebugKotlin`, `sh gradlew runPureTests [-PtestClass=X]` (Termux: `LD_PRELOAD=$PREFIX/lib/libtermux-exec.so` for scripts that nest gradle); ew-cli per `.claude/skills/ew-cli-testing.md`.

- **P0 — decision sync (no code).** Re-read `CleverKeys-ML/ctc/RESULTS.md` top (Phases G/H/I-A move fast); get user sign-off on Q1-Q3 below; read `memory/todo.md` latency numbers; confirm fixture triple-consistency (asset sha == fixture `source_onnx_sha256`; preset == fixture `preset`; fixture sha vs RESULTS.md).
- **P1 — assets + fixtures.** Copy chosen model → `src/main/assets/models/ctc_swipe_encoder.onnx` (sha-verify); OVERWRITE the stale ignored `src/test/resources/ctc/ctc_golden.json` with `ctc/artifacts/ctc_model_golden.json` (sha `ce3b5456…` for resbn80g) + copy to `src/androidTest/assets/ctc/`; fix `.gitignore` (negations) or `git add -f`; verify `git ls-files` shows both. Gate: `runPureTests -PtestClass=CtcParityTest` green (featurize cases now exercised).
- **P2 — pure model seam.** `CtcScoringParams.tunedV2` with the CURRENT preset values + rewritten KDoc; `OnnxCtcEmissionModel` (plan §1a verbatim); `CtcModuleTest` tunedV2 assertions; new `CtcPurityDriftTest` + build.gradle registration. Gate: compile + `runPureTests` full.
- **P3 — adapter + router + provenance.** `CtcEngineAdapter` (plan §1c-iii with the M-2 born-fixes AND the trie-build requirements of §3 above — no synchronous 2 s build reachable from startup or a dict-mutation swipe without documented decision + Defaults ref cleanup); Router `Mode.CTC`/`Engine.CTC`; InputCoordinator 4 hunks; SuggestionProvenance/SuggestionBar; SwipeEngineRouterTest + SuggestionProvenanceTest rows. Gate: compile + `runPureTests -PtestClass=SwipeEngineRouterTest` + full pure run. Engine not yet user-reachable.
- **P4 — settings surface.** Config knob (+coerceIn 10-300), dropdown 4th option, `CtcSettingsActivity` + manifest + navigation + strings + `SETTINGS_DEFAULTS` + SearchableSetting; SettingsResetPresets comment-only per plan. Gate: compile + `runPureTests` incl. `SettingsDefaultsDriftTest`, `SettingsSearchCoverageTest`, `TestRunnerListDriftTest`, ConfigDefaults/backup tests.
- **P5 — instrumented.** `CtcEmissionModelParityTest` + `CtcLatencyGateTest` (plan §3); `sh gradlew assembleDebug assembleDebugAndroidTest`; ew-cli targeted run, then FULL suite once (`--use-orchestrator --timeout 25m+ --device model=Pixel7,version=34`, debug APK). Two hard-won gotchas from the benchmark run (inherit, don't rediscover): (1) instrumentation executes in the app-under-test's process/uid, so the test package's `context.cacheDir` is UNWRITABLE — any ORT optimized-graph cache / `SaveToOrtFormat` path must use `getInstrumentation().targetContext.cacheDir` (fix pattern committed in `CtcOnnxLatencyBenchmarkTest`, `192084d5`); (2) ew-cli needs **`--outputs logcat`** or Log-based measurement output is lost — only `results.xml` is pulled (skill doc updated, `7e390881`). Gate: both new tests green + no regressions vs the known flake set.
- **P6 — docs + release staging.** NOTICE (plan §1f — adjust model/arm wording to shipped artifact), spec status flip, SETTINGS_MAPPING, README line, todo.md; fastlane changelog per release-process skill; NO tag/push without explicit permission. Version per sign-off Q3.

## 6. Rollback plan

Default engine stays `neural` — every phase is dark until P4, and even then `ctc` is opt-in. Rollback seams: each phase is a standalone revert; pref value `"ctc"` degrades to NEURAL via `fromPref` on any older build (backups/downgrades safe); `ctc_beam_width` is an ignorable unknown key to older importers; deleting the asset + adapter reverts cleanly (no schema, no migration). Model-swap rollback (e.g. resbn192i → resbn80g): swap asset + tunedV2 values + both fixture copies in ONE commit — never independently.

## 7. Open questions needing user sign-off

1. **Model (gates P1):** latency is measured OUT as a differentiator (all four candidates ≤0.92 ms in a 24 ms path) — pick on accuracy/size/evidence tier: (a) `resbn80g` +1.09 MB, test t1 87.68, fixture READY (sha `ce3b5456…`); (b) `ch128` +2.67 MB, test t1 87.92 — the test-validated accuracy leader, per the plan's D1 — but its E1 fixture must be restored from ML git `e5c8ff3` (sha `a18ea58c…`); (c) wait for the `resbn192i` final unsealing, +2.91 MB fp16w, val t1 88.30, accuracy-first mandate, needs fixture regen at its preset. Recommendation: (a) or (b) now — both are 1-commit swappable to (c) later via the model+preset+fixture seam.
2. **Asset size budget:** +1.09 MB (resbn80g) / +2.67 MB (ch128) / +2.91 MB (resbn192i fp16w) per ABI APK — confirm acceptable for F-Droid.
3. **Version:** v1.6.0 release notes are already staged for the context-LM wave (build.gradle still 1.5.0). Fold CTC into v1.6.0 or ship it as v1.7.0?
4. **n-2 gate:** confirm DEFERRED-with-filing (engine tagging before any default flip) vs tagging traces now.

— Fable 5

## Sign-off outcomes (2026-08-11, user)
- **Q1 model choice: SUPERSEDED-PENDING** — a new model is training and an ML-side agent is
  running comparisons on a batch of new candidates. Wave-3 implementation HOLDS until that
  batch concludes; the model+preset+fixture seam (§P1) makes the eventual choice a single
  commit. Relay package for the ML comparison effort recorded in the session log (latency
  weighting ≈ 0, per-layout geo baselines, fixture contract, on-device bench harness).
- **Q3 release vehicle: FOLD INTO v1.6.0** — release notes/content not finalized; note the
  geo engine itself is still under evaluation for v1.6.0 inclusion, so the release framing
  should treat BOTH new engines as opt-in/experimental until the engine-choice evaluation
  settles.
- Q2 (asset budget) implicitly deferred to the model outcome; Q4 (n-2) now MOOT — provenance
  tagging landed in fb86a641 (layoutName+engine in SwipeMLData); the CTC adapter must simply
  populate engine="ctc".
