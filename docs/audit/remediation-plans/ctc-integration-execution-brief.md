# CTC integration — execution brief (planning review of APP_INTEGRATION_PLAN.md)

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
| D1 | **SUPERSEDED.** ch128 is two generations stale. Test-validated ship tier = `resbn80g_s1234.onnx` (1,142,727 B, sha `330cadfb…`, 0.213 ms; fixture already generated from it). Registered accuracy-first nominee = `resbn192i_s1234_fp16w.onnx` (3,052,318 B, val-only, needs user-approved final test-2400 unsealing + fixture regen at preset `0.975/3.0/0.35/0.25/0.9882`). The latency constraint was **retired by user directive** (size ≤5 MB is the bound) — see sign-off Q1. |
| D2 | **SUPERSEDED.** tunedV2 = the fixture's preset for whichever model ships (resbn80g: 0.9/4.0/0.25/α0/0.25/0.9882). |
| D3 | VALID. Beam width 100 default, `ctc_beam_width` pref 10-300. Confirm Phase-G config-B decode width in `PHASE_G.md` §6 during P0 (expected 100). |
| D4 | **VERIFIED.** `en_enhanced.json` present (1,834,729 B); `loadStrippingNonAlphabet` exists; Phase-G config B test-validated ON this exact trie+preset — the O3 lexicon risk is closed (λ=4.0 IS the app-trie-fitted value now). |
| D5 | **VERIFIED.** `normalizeRawY` (:171) is FUTO-contract-only; plan correctly bypasses it; fixture layout confirms uniform-frame letter coords. |
| D6 | **VALID for resbn80g** (not layout-augmented; QWERTY-Latin→CTC, else geometric is right). NOTE: Phase-H/I weights beat geometric on ALL measured a-z layouts (dvorak +12.7 t1) — if resbn80h/192i ships, a follow-up may route CTC on any a-z layout; keep plan routing for v1, file the follow-up. Router enum/pre-image verified; `swipe_engine_mode` is Str in SETTINGS_DEFAULTS (value add = no defaults change); `ctc_beam_width` IntV covered by SettingsDefaultsDriftTest scan (safeGetInt/putInt patterns). |
| D7 | VALID. Default stays `neural`; `fromPref` fallback protects downgrade. |
| D8 | **VERIFIED.** ModelLoader signature accommodates the second model incl. the parity test's `enableHardwareAcceleration=false, xnnpackThreads=1` call. Proguard `-keep ai.onnxruntime.**` at :193 — no new rules. |

## 3. Interaction requirements (audit `docs/audit/remediation/3-core-ime.md` §2026-08-11)

- **M-2 (warmUp/decode cancellation race) — REQUIRED, born-fixed.** The plan's `CtcEngineAdapter` clones the geo bug exactly: ONE `PredictionTaskRunner`, `warmUpAsync` AND `decodeAsync` both via `cancelAndSubmit`, prewarm fired from `onStartInputView` (CleverKeysService:687) → prewarm can cancel an in-flight decode; the result callback also replays captured `ic`/`editorInfo` with no stale-field guard. Since this is a NEW file, do not inherit a catalogued bug: (a) make warmUp submit-only-if-idle (skip if a task is queued/running) instead of `cancelAndSubmit`; (b) guard the replay like the neural path's `isReplayInputStillCurrent` precedent (InputCoordinator:461). Leave GeometricEngineAdapter to the audit's own remediation.
- **n-2 (untagged ML corpus) — DEFERRED-with-filing.** `performCtcSwipeTyping` calls the shared `beginSwipeCapture` (:487) whose `SwipeMLData("", "user_selection", …)` carries no engine/layout field — CTC traces will be indistinguishable from neural QWERTY traces in ML exports. Acceptable while `ctc` is opt-in-dark; MUST extend the audit's n-2 item to name CTC, and n-2's engine/layout tagging is a **hard gate before any default flip (O6)**.
- **m-1 (provenance) — bar origin COVERED by the plan** (`SuggestionOrigin.CTC` §1c-v, correct); **commit source REPEATS the mislabel**: `SuggestionHandler.kt:572` sets `PredictionSource.NEURAL_SWIPE` unconditionally for all swipe commits — CTC commits will be labeled NEURAL_SWIPE in context tracking. DEFERRED-with-filing (extend the existing hybrid-origin todo, memory/todo.md:85, to geo+CTC commit-source). If any new test pins this, mark it deliberately-wrong per the audit's m-1 instruction — never pin silently.

## 4. Test additions beyond the plan

- **`CtcPurityDriftTest` (NEW, required by convention):** `swipe/ctc/` has NO purity enforcement — clone `GeoPurityDriftTest` (scans `src/main/kotlin/.../swipe/geometric` for android/ORT imports) scoped to `swipe/ctc`, since spec NFR-1 demands the package stays Android/ORT-free and `OnnxCtcEmissionModel` now lives one level up. Register in `pureTestClasses` (`build.gradle`) — `TestRunnerListDriftTest` fails the build if you forget.
- Already registered: `CtcParityTest`/`CtcModuleTest` (build.gradle:489-490), `SwipeEngineRouterTest` (:456), `SuggestionProvenanceTest` (:506). Plan's modifications need no re-registration.
- Latency: `CtcOnnxLatencyBenchmarkTest` (androidTest, landed `74714245`) measures ch128/ch192/resbn72/resbn80 — `fast_resbn80` is graph-identical to `resbn80g` (same 279,346 params/1,142,727 B), so its numbers proxy the ship candidate. Desktop cross-check already done (todo.md: NN+beam@100 ≈ 9.6 ms mean, trie build 90 ms). **Check `memory/todo.md` § "CTC on-device latency" for the emulator/on-device numbers being produced in parallel BEFORE shipping the asset** — with the latency mandate retired they gate sanity ("≪ neural 100-300 ms"), not model choice.
- Oracle precedent: no CTC oracle test needed for v1 (`CtcEmissionModelParityTest` end-to-end + fixture pin covers determinism; GeometricSwipeOracleTest stays geo-only per plan §3.3).

## 5. Phased execution checklist (each phase compiles + greens independently)

Conventions: `sh gradlew compileDebugKotlin`, `sh gradlew runPureTests [-PtestClass=X]` (Termux: `LD_PRELOAD=$PREFIX/lib/libtermux-exec.so` for scripts that nest gradle); ew-cli per `.claude/skills/ew-cli-testing.md`.

- **P0 — decision sync (no code).** Re-read `CleverKeys-ML/ctc/RESULTS.md` top (Phases G/H/I-A move fast); get user sign-off on Q1-Q3 below; read `memory/todo.md` latency numbers; confirm fixture triple-consistency (asset sha == fixture `source_onnx_sha256`; preset == fixture `preset`; fixture sha vs RESULTS.md).
- **P1 — assets + fixtures.** Copy chosen model → `src/main/assets/models/ctc_swipe_encoder.onnx` (sha-verify); OVERWRITE the stale ignored `src/test/resources/ctc/ctc_golden.json` with `ctc/artifacts/ctc_model_golden.json` (sha `ce3b5456…` for resbn80g) + copy to `src/androidTest/assets/ctc/`; fix `.gitignore` (negations) or `git add -f`; verify `git ls-files` shows both. Gate: `runPureTests -PtestClass=CtcParityTest` green (featurize cases now exercised).
- **P2 — pure model seam.** `CtcScoringParams.tunedV2` with the CURRENT preset values + rewritten KDoc; `OnnxCtcEmissionModel` (plan §1a verbatim); `CtcModuleTest` tunedV2 assertions; new `CtcPurityDriftTest` + build.gradle registration. Gate: compile + `runPureTests` full.
- **P3 — adapter + router + provenance.** `CtcEngineAdapter` (plan §1c-iii with the M-2 born-fixes of §3 above + Defaults ref cleanup); Router `Mode.CTC`/`Engine.CTC`; InputCoordinator 4 hunks; SuggestionProvenance/SuggestionBar; SwipeEngineRouterTest + SuggestionProvenanceTest rows. Gate: compile + `runPureTests -PtestClass=SwipeEngineRouterTest` + full pure run. Engine not yet user-reachable.
- **P4 — settings surface.** Config knob (+coerceIn 10-300), dropdown 4th option, `CtcSettingsActivity` + manifest + navigation + strings + `SETTINGS_DEFAULTS` + SearchableSetting; SettingsResetPresets comment-only per plan. Gate: compile + `runPureTests` incl. `SettingsDefaultsDriftTest`, `SettingsSearchCoverageTest`, `TestRunnerListDriftTest`, ConfigDefaults/backup tests.
- **P5 — instrumented.** `CtcEmissionModelParityTest` + `CtcLatencyGateTest` (plan §3); `sh gradlew assembleDebug assembleDebugAndroidTest`; ew-cli targeted run, then FULL suite once (`--use-orchestrator --timeout 25m+ --device model=Pixel7,version=34`, debug APK). Gate: both new tests + no regressions vs the known flake set.
- **P6 — docs + release staging.** NOTICE (plan §1f — adjust model/arm wording to shipped artifact), spec status flip, SETTINGS_MAPPING, README line, todo.md; fastlane changelog per release-process skill; NO tag/push without explicit permission. Version per sign-off Q3.

## 6. Rollback plan

Default engine stays `neural` — every phase is dark until P4, and even then `ctc` is opt-in. Rollback seams: each phase is a standalone revert; pref value `"ctc"` degrades to NEURAL via `fromPref` on any older build (backups/downgrades safe); `ctc_beam_width` is an ignorable unknown key to older importers; deleting the asset + adapter reverts cleanly (no schema, no migration). Model-swap rollback (e.g. resbn192i → resbn80g): swap asset + tunedV2 values + both fixture copies in ONE commit — never independently.

## 7. Open questions needing user sign-off

1. **Model (gates P1):** ship test-validated `resbn80g` now (+1.09 MB/ABI-APK, fixture ready), or wait for the `resbn192i` final unsealing (+2.91 MB fp16w, accuracy-first mandate, needs fixture regen + new preset 0.975/3.0/0.35/0.25/0.9882)? Plan's ch128 is off the table. Recommendation: resbn80g now, resbn192i as a follow-up swap (seams above make it a 1-commit swap).
2. **Asset size budget:** +1.09 MB (resbn80g) vs +2.9 MB (resbn192i fp16w) per ABI APK — confirm acceptable for F-Droid.
3. **Version:** v1.6.0 release notes are already staged for the context-LM wave (build.gradle still 1.5.0). Fold CTC into v1.6.0 or ship it as v1.7.0?
4. **n-2 gate:** confirm DEFERRED-with-filing (engine tagging before any default flip) vs tagging traces now.

— Fable 5
