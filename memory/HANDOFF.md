# HANDOFF — updated 2026-08-27

Read this first, then `docs/specs/ctc-architecture-and-multiscript-guide.md` (architecture,
routing rule, multi-script recipe, full audit table). This file is the **task list**; the guide is
the **reference**. Where they overlap, the guide wins on technical detail and this file on
priority.

**Completed work is DELETED from this file, not struck through.** Git history is the record of
what was done; this file is only what is left. Anything below is open.

## State at `05c0c25d`

Swipe is **CTC (default) + geometric**; the neural engine was deleted 2026-08-18
(`a7d03bc8`..`83220634`), −26.4 MB APK. `CtcLanguageSupport.SUPPORTED` is **eight** languages:
en/fr/de/es test-validated, it/pt/sv `PROVISIONAL` (scale-transferred, no per-language bar), and
**ru** `VAL_ONLY` since 2026-08-29 (`1561dbaf`, `da012ded` — the first non-Latin script; see the
geometric-removal section below for what is and is not established about it). **The table is no
longer the whole membership**: since `05c0c25d` an imported LATIN language pack that measures
a–z-typeable is served too (`CtcImportedPackSupport`), so `SUPPORTED.keys` is a lower bound and
`CtcLanguageSupport.sourceFor`/`isSupported` is the answer.
Gates: `runPureTests` **1946**, `runMockTests` **325**, `lintDebug` 0 errors, both compiles.
Last full instrumented run (ew-cli, Pixel7 API 34, 2026-08-28, run `2ca8b7c9` at `6d67a7c8`):
**1,430 tests, 3 red — all explained, none a code regression**: 2 `CtcOnnxLatencyBenchmarkTest`
reds that are BY DESIGN whenever the ctc_bench models are not staged (`3fcbf7b8`; restore via
`cp` from CleverKeys-ML/ctc/artifacts/ into src/androidTest/assets/ctc_bench/ only when actually
benchmarking — expect these 2 reds in any full run, do not chase them) + 1 first-revision
`ContractionSentenceStartMeasureTest` red (asserted at the wrong layer; rewritten against the
real SuggestionHandler+SuggestionBar wiring and re-verified green 3/3 on-device, run `1a851d40`).
Every wave-added instrumented test passed its first device execution, including
`CtcLatencyGateTest` (cold build **3,162 ms** vs the 4,500 ms budget — real margin, not
vacuous) and the dual-language latency tests CK-150-026 had left unexecuted.

**Contractions**: the whole system is now documented as-built in
`.claude/skills/contraction-system.md` — data model, the four guards, the regressions each one
prevents, regeneration commands, and the invariant→test table. Read it before touching anything
named `contraction*`. The 2026-08-20/21 cross-language, user-word and paired-base fixes live
there and in git history, not in this file.

---

## Before you trust an entry below

Three claims inherited by the 2026-08-20 session were investigated and **all three were false**
(`SwipeResampler` "consumer-less", `CoroutineScopeLifecycleTest` "flakes in combined runs",
`ctc_bench` "never packaged"). Inherited claims decay; the cheap experiment beats the plausible
story. Two habits came out of it and are worth keeping:

- **Read the `N) <test>(<class>)` failure header, never the noisiest stack trace.**
  `CoroutineScopeLifecycleTest` throws "boom" ON PURPOSE to prove `SupervisorJob` isolation, which
  made it the loudest thing in every red log and got it blamed for a year of flakes it never
  caused. Note the header does not always start a line — stdout and stderr interleave, so anchor
  the search with `[0-9]+\)` rather than `^[0-9]+\)`.
- **Verify a quiet machine by LOAD, not by grepping for one process name.** `uptime` reads
  `sysinfo()` and works on Android even though `/proc/loadavg` is SELinux-denied (which is
  separately why the JVM's `getSystemLoadAverage()` returns -1 here). A "clean box" check that
  grepped for `java` missed 23 leaked `bash` CPU burners and produced eight bogus benchmark
  numbers plus two commits that had to be reverted.

---

## Open work, in priority order

### 0. Audit-archive leak ledger (2026-08-28) — remediation waves complete; residue below

The archive-verification pass (`docs/audit/2026-08-28-archive-verification.md`, ARC-001..052)
recovered 48 untracked findings; **41 were fixed the same day** across seven implementation
waves (commits `31685cac`..`fee6bd4d` + wave commits; every fix cites its ARC ID). Consult git
log and the verification doc before re-deriving anything. Still open:

- **ARC-008 — R8 ENABLED for release 2026-08-29. ONE MANUAL SOAK IS OWED BEFORE ANY v1.6.0 TAG.**
  `minifyEnabled true` + `shrinkResources true` in the release block only; debug is untouched and
  stays unminified. Landed after a full reflection/keep audit and a determinism proof.

  **OWED before tagging v1.6.0 — a maintainer must sideload the MINIFIED release APK and soak it
  by hand**: install it, type, swipe (both engines), open the clipboard / emoji / GIF panes,
  import a backup, toggle languages, and open every settings screen. **A full ew-cli run is NOT
  sufficient and does not discharge this gate** — the instrumented suite builds and runs the
  *debug* variant, which has R8 off, so it exercises none of the obfuscated/shrunk code. The pure
  gates likewise prove nothing here (`runPureTests` OK (1947), `runMockTests` OK (325) — both
  green, both debug/JVM).

  Evidence recorded at enable time:
  - **Size** (same commit, R8 off → on): arm64-v8a 33,908,757 → 29,092,480 (-4,816,277, -14.2%);
    armeabi-v7a -14.4%; x86_64 -13.8%. Uncompressed DEX 26,226,416 → 12,038,564 (-54%, 3 dex → 2).
    Resource entries 1007 → 892.
  - **Determinism: PASS, byte-identical.** Two `clean assembleRelease` runs produced identical
    APK sha256 on all three ABIs (arm64 `38aa814fbea0489c…`, v7a `93329f71a63c4234…`,
    x86_64 `6ee317a71f1b942e…`); `classes.dex` `5a20f58cd6478973…` and `classes2.dex`
    `ddaf66dfa3c1b997…` matched across both runs and across ABIs, as did `resources.arsc`
    `5439bf627bfa9575…`. **F-Droid reproducibility is preserved**, which was the original 2025
    fear behind the "REPRODUCIBILITY TEST" comment — that fear is now measured and refuted.
    The versioned release workflow uses the same AGP/R8, so determinism transfers; still, watch
    the FIRST published minified release for an F-Droid reproducibility mismatch.
  - **shrinkResources stripped nothing production-critical.** Zero `raw:` and zero `xml:`
    resources removed (all 86 keyboard layouts, `numeric`, `pin`, `emojis`, `version_info`,
    `method.xml` all retained). The 115 removed resources are Material/AndroidX leftovers plus
    six app layouts + four app strings that all belong to the **dormant androidx.preference
    widget layer** (`prefs/*Preference`, `CustomLayoutEditDialog`) — dead since Settings became
    Compose: there is no `PreferenceFragmentCompat`, no preference XML screen, and nothing
    constructs those Preference objects.
  - **Retention smoke on the shipped DEX**: `CleverKeysService` kept un-obfuscated (required —
    `CleverKeysService.kt:1059` feeds `javaClass.name` to the enabled-IME comparison),
    `ai.onnxruntime.*` classes, Gson bind targets (`IntentDefinition`,
    `ShortSwipeCustomizations`, `UserWordUsage`), all XML-inflated custom Views, and both ONNX
    JNI `.so`s all present.

  **Two traps recorded for whoever touches this next:**
  1. **`build.gradle` coroutines `META-INF/services` excludes are no-ops, and that is now
     load-bearing.** With R8 on, `-assumenosideeffects … FAST_SERVICE_LOADER_ENABLED return false`
     finally takes effect, and the shipped DEX (confirmed by baksmali) resolves `Dispatchers.Main`
     through `java.util.ServiceLoader` — which reads a file those excludes *claim* to remove.
     Making the excludes work would break every `Dispatchers.Main` dispatch in release only.
     Full explanation is in a comment at the exclude site.
  2. **`usage.txt` is not a list of deleted functionality.** R8 writes bare class names for
     classes that were inlined or merged away, and `mapping.txt` shows them as
     `R8$$REMOVED$$CLASS$$N`. `CtcCkdtLexicon`, `CtcLexiconMerge`, `ContractionInjectionPolicy`
     etc. all appear "removed" while their code demonstrably ships (string literals present,
     callers live). Only two classes are genuinely gone — `CtcSwipeDecoder` and
     `SuggestionRanker` — and both have **zero** production callers; they are referenced only
     from `src/test`/`src/androidTest`. Worth a follow-up: `CtcLatencyGateTest` is an
     *instrumented* gate measuring `CtcSwipeDecoder`, a class the release APK no longer ships,
     while production decodes via `CtcBeamDecoder` (`CtcEngineAdapter.kt:1012`).

  **Remaining shrink headroom (deferred, needs its own soak):** `proguard-rules.pro` still carries
  blanket `-keep class androidx.compose.**`, `androidx.lifecycle.**`, `androidx.savedstate.**` and
  `kotlinx.coroutines.** { *; }`. lifecycle and savedstate ship correct consumer rules in their
  own AARs, so those two are redundant; `androidx.compose.**` is the largest retained blob. They
  were kept deliberately so this first minified release does not ask one manual soak to cover
  both "R8 on" and "Compose narrowed" at once.
- **ARC-012 ROOT-CAUSED + FIXED 2026-08-29, manual visual confirmation owed.** #79 settings
  header flicker. The old "LazyColumn recomposition" diagnosis was wrong twice: the screen is
  `Column`+`verticalScroll`, and `git show v1.2.5:` confirms it was at the reported version too
  (zero `LazyColumn` anywhere in `src/main/kotlin` at that tag). There is also **no header
  component** — the "header" is two plain `Text`s inside the scrolling `Column`
  (`SettingsScreen.kt:102-115`); no `TopAppBar`, no sticky header, no scroll-derived elevation
  anywhere under `ui/settings/`. **Real cause:** `SettingsControls.kt:43` read
  `mainScrollState?.value` in `CollapsibleSettingsSection`'s COMPOSITION BODY.
  `ScrollState.value` is snapshot-backed, so all 18 sections re-composed on every scroll pixel,
  and because the `onGloballyPositioned` lambda captured the changed offset the `Card`'s modifier
  was a fresh instance each frame → node re-diff + relayout mid-scroll. Fixed by moving the read
  into the layout lambda, which is what the other three call sites (`:167`, `:225`, `:296`) always
  did. **Caveat that must stay attached:** that hoist was introduced by `d2d0e456` (2026-07-03),
  so it postdates the January v1.2.5 report — it is a real defect in shipping code and plausibly
  the symptom users see today, but it cannot be what the original reporter saw. Before closing
  #79, ask the user to re-check on a current build. Remaining v1.2.5-era candidate, unverified:
  a three-way inset conflict at the top edge (`styles.xml:53-57` `fitsSystemWindows=true` vs
  `SettingsActivity.kt:674` `setDecorFitsSystemWindows(false)` vs `SettingsScreen.kt:96`
  `.statusBarsPadding()`), with both decor and content backgrounds blanked at `:686-687`.
  Disambiguate with `setprop debug.hwui.show_dirty_regions true` — status-bar strip only = insets,
  whole content area = the (now fixed) recomposition storm.
- **Astro 5→6 migration owed** (2026-08-29): the site's two remaining HIGH CVEs are fixed only
  in astro 6.4.6, which needs vite 7+ — conflicting with the vite 6.4.3 CVE pin in
  `site/package.json` overrides. Suppressed in `.trivyignore` with rationale (build-time-only
  static generator). When migrating: bump astro, drop the vite/js-yaml overrides, delete the
  two `.trivyignore` lines, rebuild (84 pages expected).
- **ARC-013 FIXED 2026-08-29, awaiting device confirmation.** UT-5 closed 2026-08-28; UT-7's two
  measured defects are fixed in code and both root causes turned out NOT to be what the
  measurement doc guessed. (a) `id → i'd` was never missing from the DATA —
  `contraction_pairings.json` has carried it all along; the tap path's inline
  `partial.length >= 3` paired-injection floor excluded the only two-letter I-contraction base.
  The floor now lives in `ContractionInjectionPolicy`, which admits first-person contractions at
  two characters and nothing else — verified against the shipped table as **exactly one** base
  changing. No data change, so no regeneration and no new collision sidecar. (b) the doubled
  `I'll` was not two injection paths but ONE list holding the variant twice:
  `ContractionManager.loadPairedContractions` merged `contraction_pairings.json` on top of the
  binary-derived pairs with a blind `add()`, and the two sources overlap on **599** of 2,258
  bases (every doubled possessive too: `times → time's` twice). Deduped at the loader plus a
  final-list guard in `SuggestionHandler`. **Next ew-cli run must confirm on-device**:
  `ContractionSentenceStartMeasureTest` (now PINS `i'd` present for typed `id`, the literal `id`
  surviving alongside it, and no duplicate surface for typed `ill`) and
  `ContractionFlickerTest`'s rewritten prefix-guard cases.
- **ARC-019 CLOSED 2026-08-28**: same-inputs head-to-head on LOCAL combined (4,526 traces):
  CTC 90.7/95.4/96.1 vs geometric 63.0/75.2/78.3 top-1/3/5; geo-only recoveries 1.5%. The last
  accuracy argument for geometric-on-Latin is gone. Synthetic tiers: CTC degrades more
  gracefully than geometric (11.3 vs 19.6 pt TYPICAL→SLOPPY drop) but absolute synthetic levels
  carry a timing artifact — full record in `docs/eval/2026-08-28-arc019-ctc-local-head2head.md`.
- **ARC-044 (curated six DONE 2026-08-29, rest open)**: the 6 release-gate classes went
  141 → 223 assertions (`fe976d0e`), strengthening only — no test exercises anything new.
  Biggest win `CrashGuardInstrumentedTest` 1 → 35 (it asserted literally nothing before; now
  pins "ConfigPropagator is a pusher, never a mutator" via a reflective before/after snapshot of
  all 141 public `Config` fields, plus the builder's fluent contract). **Truth was deliberately
  NOT added to `androidTestImplementation`**: those configurations are dependency-locked and
  Trivy reads `gradle.lockfile` as one flat production tree, so truth would drag guava,
  checker-qual, asm and auto-value into the security gate's scope — the exact failure that made
  it go red on ~48 build-tooling CVEs and forced the current narrow lock scope (`build.gradle`
  header). Use JUnit assertions with explicit messages in androidTest. Still open: the other
  ~85 androidTest classes, and `CrashGuardInstrumentedTest`'s `catch (Throwable)` guard is
  still not EXERCISED (all eight collaborator types are final Kotlin classes — needs a
  production seam; TODO recorded in the class KDoc).
  Owed on the next ew-cli run: three new assertions are device-unverified by construction —
  the hard non-empty French control in `secondaryLanguageDecode_…` (was conditional, which
  silently skipped the rank-one pin), the per-decode non-empty check inside the geometric p95
  perf loop, and slate-distinctness on the geometric paired-base decode.
- **ARC-045 — DONE 2026-08-29** (4 commits: settings sections / customization dialogs /
  LayoutManagerActivity / tail). 317 new resources across 30 files. The audit's "~168" counted
  only single-line `Text("…")`; the real surface was larger once multiline `Text(\n "…")`,
  `text = "…"`, `label`/`placeholder`, and every hardcoded `contentDescription` were included —
  the accessibility strings in CommandPaletteDialog, LayoutManagerActivity and LauncherActivity
  were the least visible and arguably the worst of it. Three previously-untranslated activity
  strings now reuse the already-translated `autocorrect_*` keys (identical English), so those
  gained 21 locales for free. Wave E's "preview dialog copy is English by convention" is ended.
  Deliberately NOT extracted, each for a stated reason:
  - `GestureTuningSection` `listOf("Low","Medium","High","Custom")` — identity strings
    round-tripped through `applySwipeSensitivityPreset`/`getSwipeSensitivityPreset`
    (`SettingsResetPresets.kt`), not labels. Localizing breaks preset selection.
  - The pure-JVM renderers in `BackupRestorePreviewDialogs.kt` — `renderJsonBlobSummary`,
    `renderArraySummary`, `renderBackupSourceNotice`, the `+`/`−`/`~` diff markers, `TypeChip`'s
    type names. They take no Context, which is precisely what lets
    `BackupRestorePreviewRenderTest` assert them in the PURE suite; extracting would force
    Context injection into pure code and demote that coverage to instrumented.
  - Glyph-only `Text` ("✕", "X", "⌨", "↺", "◀", "▶") — promoted to named consts where they
    were inline, left as glyphs otherwise.
  - Technical placeholder values (`com.example.app`, `android.intent.action.VIEW`, `text/plain`,
    `yyyy-MM-dd HH:mm`) and unit-only display formats (`"%.0f px"`, `"${x}ms"`, `"$x%"`).
  - `GifPanelSection`'s `status.startsWith("Error")` branch — the status string is produced
    elsewhere; extracting only the comparison would silently break the match. Left as a known
    English-anchored coupling.
  No drift-test anchor had to move: `SettingsSearchCoverageTest` already resolves
  `stringResource` control titles (index unchanged at 128 entries), and the Compose
  instrumented tests match on RENDERED text, which is byte-identical because every extracted
  value reproduces its literal exactly.
- **ARC-048 — the cheap three-quarters is DONE (2026-08-29); the two expensive items are
  now explicitly deferred as their own projects, not backlog rot.**
  - **DONE — R4 package reorg** (`63fcb797`): 32 files moved into `activities/` (14),
    `clipboard/` (12), `emoji/` (6). Flat package root **145 → 113**. Directory-only: every
    moved file still declares `package tribixbite.cleverkeys`, so `git diff -M` is 32×R100
    and no import, manifest entry, proguard keep or `pureTestClasses` FQCN changed. The real
    cost was the eight things that address a file by REPO PATH (build.gradle's
    `generateSettingsSearchIndex` input, `scripts/generate_settings_search_index.py`, and six
    source-scanning drift tests) — all updated in the same commit. If you move more clusters,
    that list is the checklist; `GesturePrefAccessDriftTest` is safe because it matches on
    `File.name`.
  - **DONE — R6 `interface Predictor`** (`Predictor.kt`): the CONSUMED surface of the
    2,636-line `WordPredictor` (25 members, not all of its public API — construction-only
    members like `setContext`/`startObservingDictionaryChanges` are off it on purpose).
    `PredictionCoordinator.getWordPredictor()` and `Keyboard2View.setSwipeTypingComponents`
    now hand out `Predictor?`. `DictionaryManager` deliberately keeps the concrete type: it
    is the owner, not a consumer. Milestone met with a real fake —
    `PredictorContractTest` (pure, 7 cases) drives the next-word pipeline through a
    `FakePredictor`, which was impossible before (it needed a device, a dictionary load and
    ~5-10 MB of heap). Two drift pins keep the seam from silently closing again.
  - **DONE — the `SideEffect{}` gap** (`df396f86`); see the ARC-048 addendum in
    `docs/audit/2026-08-28-archive-verification.md:137` for why it was never a flicker source
    and what the actual damage was.
  - **DEFERRED (own project) — R3 `ConfigSnapshot` read-model.** Static
    `Config.globalConfig()` consumers are 33 files / 90 call sites. Plan is written and
    phased: `5-architecture.md` §"Config Immutability Migration Plan" steps 1-3 (snapshot type
    → `Gesture.kt` first → `Pointers`+`Keyboard2View`), with the CI grep count as the single
    progress metric and an explicit guardrail against freezing `Config` itself. Medium risk
    because it touches the touch hot loop — do not fold it into an unrelated commit.
  - **DEFERRED (own project) — R5 Initializer collapse.** 6 `*Initializer` files (841 lines)
    hand-wired in `onCreate`; plan is `5-architecture.md` §R5 + §"Bridge Consolidation" —
    one `KeyboardComponentGraph` composition root, keeping the 4 Bridges (they are genuine
    adapter seams, not wiring). No DI framework.
  - Also still open from the original entry: `SettingsActivity`'s 123 `mutableStateOf` fields
    and the `CleverKeysService` static escape hatches (`:153,:176,:212`).
- **CtcLatencyGateTest measures a class release no longer ships — needs a decision.**
  `src/androidTest/.../swipe/CtcLatencyGateTest.kt:183` constructs `CtcSwipeDecoder`, but
  `CtcSwipeDecoder` has **zero** consumers in `src/main` (only `CtcModuleTest`,
  `CtcReplayEngine` and this gate use it); production decodes via `CtcBeamDecoder`, reached
  from `CtcEngineAdapter`. Since R8 went on for release (`37ed9804`) it is stripped from the
  release APK as test-only-in-main. The gate still runs and passes — androidTest builds
  against debug, where R8 is off — so this is silent: a green latency gate over code the
  shipped APK does not contain. Two candidate fixes, pick one: move `CtcSwipeDecoder` to
  `src/test` (honest about what it is), or repoint the gate at `CtcBeamDecoder` (measures
  what ships). Don't do both.
- **ARC-049 (device)**: one long-run `MemoryProbe` + `dumpsys meminfo` on a current build to
  close the unexplained 2026-08-17 OOM.

**Written 2026-08-29, never executed on a device** — the next ew-cli run is their first:
`TermuxDeletionInstrumentedTest` (7 cases; ARC-007's owed test — Termux vs ordinary-app control
for REPLACE deletion, typed-partial deletion and delete-last-word) and the flipped
`ContractionSentenceStartMeasureTest` / `ContractionFlickerTest` contraction cases above.

**Verified by the 2026-08-28 full ew-cli run** (all green): ARC-023 cold-build budget
(3,162 ms), the provenance UI cases (14/14 `ClipboardPanelPrivateBadgeTest`), base64
temp-file / clipboard-cap / private-copy-toast instrumented tests, dual-language latency
(CK-150-026's device half).

**Still owed — manual on the maintainer's device or unwritten instrumented coverage**:
#148 visual pass (predictions off → open clipboard → keyboard stays visible below the pane);
private-media paste refusal behavior; ARC-005 nonzero occlusion on a geometric-served layout;
`.ckenc` SAF naming (one manual export-with-password); encrypted-import 🔒 preview rendering
(Compose dialog cases unwritten); GIF legacy-pack rejection paths (need a real ZIP +
GifDatabase); next-word cold-start bar on-device (opt into next-word, empty learned store).

**Translation debt added by the waves** (English-only, non-blocking):
`pref_secondary_prediction_weight` summary, `backup_base64_too_large`,
`clipboard_private_copy_toast_title/desc`, `clipboard_provenance_via/direct_launch`,
`privacy_on_device_learning_desc` (21-locale copies still name deleted swipe-calibration).
317 strings extracted from Compose literals 2026-08-29 (ARC-045), all English-only — the
21-locale pass is a follow-up. None are in `TranslationCoverageDriftTest.required`, which is
correct: that test asserts real per-locale values, so listing an untranslated name there would
fail rather than protect anything.

### 1. Contraction follow-ups, all deferred deliberately

- **Owed translations** for `collision_warning_title/body/examples` — English-only behind
  `tools:ignore="MissingTranslation"`.
- **User-word guard is case-partial by design.** `replaceModeContractionFor` probes the word plus
  its lowercase and capitalised forms, not a full case-insensitive match. Making it total means
  giving `userWords` case-insensitive membership, which changes add/remove/dedup semantics for a
  persisted user-owned set — its own change, not a side effect.
- **Verb inversions** (`est-elle`, `a-t-on`) deferred with named landmines: `estelle` is a native
  word @16343, `aton` is ASK-attested, `entretemps` is a classifier misfire needing
  `FORCED_APPEND`.

### 2. Context-LM: rescoring CLOSED; next-word is the consumer

**DECIDED 2026-08-26 (maintainer): `swipe_context_rescoring` stays default-OFF permanently; no
shadow mode; the investigation is closed.** The learned-context data's consumer is **next-word
prediction** (tap-to-accept) instead of swipe rescoring (silent auto-insert, which the offline
eval showed overturning correct decodes: 0 fixed / 6 broken on the no-eviction device corpus,
no (WEIGHT,R_MIN) grid point passing, tune winners losing on held-out).

Everything is in git history and these references — do not re-derive:

- **The eval + its 13-entry retraction ledger**: `docs/eval/2026-08-22-context-rescoring-first-replay.md`.
  Numbers are PINNED to a commit; the decoder moved twice under the measurement.
- **Drift canary**: `CtcReplayEngineSmokeTest.slateShapeHasNotDrifted` — red means RE-BASELINE
  the eval, not "decoder broken". Skips without the local trace corpus.
- **Harness**: `ContextRescoringReplayTest` (+ `CtcReplayEngine`, pure-JVM real CTC decode via
  `extractOrtNative`). Knobs: `-PreplayCorpus={device|ubuntu} -PreplayDecoys -PreplayMaxCtx`.
  Corpora live in `~/.cache/cleverkeys-corpora/` and are NEVER committed.
- **Next-word audit landed 2026-08-26** (`b9355be1`, `ececaa73`): `context_aware_predictions_enabled`
  is now a REQUIRED `NextWordPredictor.shouldShow` param and gates the cursor-park editor read;
  dependent settings controls (next-word switch, personalization strength, learning aggression)
  are visible-but-disabled, never hidden; search entries open the Advanced-Prediction panel.
  Pinned by `LearningWiringDriftTest` + `SettingsSearchCoverageTest` source-scans.

**Open (only if someone reopens rescoring)**: hub-confusable decoy rule; a second language.
**Open (next-word, minor)**: nothing known; feature remains opt-in default-OFF behind
`next_word_prediction_enabled` + the master learning gate + the context-LM pref.

### 3. Smaller, ride-along

- `contraction_pairings_cleaned.json` (32 entries, 5,177 bytes) has ZERO code references —
  verified 2026-08-21 across `src/`, `scripts/`, `tools/`. Candidate for deletion with the next
  data change; needs a gate run, not a decision.
- **Doc claims found but deliberately not fixed** during the 2026-08-21 deleted-class sweep,
  each needing verification against live code rather than a mechanical edit: invented API
  signatures in `core-keyboard-system.md` (`CleverKeysService.switchLayout`,
  `KeyEventHandler.handleKeyDown`); a self-contradicting test inventory in `testing-strategy.md`
  ("5 Robolectric / 6 instrumented" vs its own later 987/176/887); a `SwipeDetector` box and a
  `DATABASE_VERSION = 1` claim in `ARCHITECTURE_MASTER.md` §9/§7.2 (clipboard schema is V4); and
  an unverified file tree + "~3000 lines" in `settings-system.md`.
- Translations owed: `pref_secondary_prediction_weight` summary (English rescoped to tap-only
  2026-08-28/ARC-018; 21 locales still carry the unscoped wording), plus
  `swipe_context_rescoring_*`, `collision_warning_*`,
  `swipe_engine_pack_not_typeable` / `swipe_engine_pack_head_not_typeable` /
  `swipe_engine_pack_unusable` (added `05c0c25d`; the imported-pack refusal reasons on the
  swipe-engine fallback card), `gesture_touch_smoothing_*`,
  `gesture_finger_occlusion_*`, `dict_word_too_long_for_swipe_*` ship English-only behind
  `tools:ignore="MissingTranslation"`. The 21 `swipe_engine_mode_desc` translations were
  machine-extended and want a native reviewer — **and the English is now CONTENT-stale** as well
  as unreviewed: it names seven languages, predating both `ru` (2026-08-29) and imported-pack
  membership (`05c0c25d`), which is a per-device answer a fixed list cannot carry. Rewording it
  invalidates all 21 locales, so it wants one deliberate pass (probably: drop the enumeration and
  point at the fallback card, which is measured and always right).
- `finger_occlusion_offset` ships at default 0. A nonzero default needs a device-trace A/B at
  {0, 8, 12.5, 16}% — the old 12.5% was never measured anywhere in this repo's history.

---

## The geometric-removal question

The **language** dimension is closed for Latin — including, since `05c0c25d`, the imported-pack
cell that used to be the loudest counterexample: a user with `langpack-nl.zip` got geometric for a
language CTC decodes fine. **Cyrillic is now half-open**: the wiring is generic and Russian is
routed. What remains is the other five scripts' lexicons, and layout.

Layout census (`src/main/layouts/`, 86 XML — the tree `copyLayoutDefinitions` ships;
`srcs/layouts/` is divergent and read by no build task):

| bucket | count | routing |
|---|---|---|
| `script="latin"` and a–z-complete | 46 | CTC — for the eight table languages AND for any imported Latin pack that measures a–z-typeable (`05c0c25d`); a pack that does not (Turkish: 73 %) stays geometric, deliberately |
| `script="latin"` but a–z-incomplete | 2 | geometric, via the alphabet gate |
| `script="cyrillic"` | 11 | **CTC at gate 1** since 2026-08-29; only `ru` is served, so ten of the eleven fall through at the LANGUAGE gate |
| other non-Latin declared (14 scripts) | 25 | geometric at gate 1 |
| no `script` attribute | 2 | `numeric.xml`, `pin.xml` — not letter layouts |

**"Non-Latin can only be served by geometric" is false, and is now also disproven in the app.**
Per-script models are required — a Latin-trained encoder does not zero-shot another script well
enough on its own, because motor statistics and the learned character-transition prior are
trained even though geometry is an input — but they are cheap and proven: ~94k steps of
`resbn:80`, under an hour on one GPU, from a word list and layout geometry alone.
`phaseIB-ru-synth` saw zero real Cyrillic rows and decoded real Russian at 77.41 in-dict top-1;
the generation-4 successor (`ru_synth_v3_ch80`, learned-generator synthesis, Phase Q) reads
**85.07** on the same eval-only probe.

"The ALPHABET is hardcoded a–z" **was** true of two constants in one adapter file and false of
everything else. Both are gone (`1561dbaf`).

### As built — 2026-08-29, `1561dbaf` + `da012ded`

The infrastructure is generic and Russian is routed end to end. What exists now:

- **`swipe/ctc/CtcScriptSupport.kt`** — THE per-script table: alphabet (codepoint-sorted, the
  model's emission slot order), layout XML, `script` attribute, model asset, golden fixture,
  status, and for the unrouted five the exact gap and its unblocking condition. All six scripts
  have a row. Only `Status.ROUTED` widens the router, and the row's `init` refuses ROUTED unless
  both artifacts are named — rule 4 as a type invariant.
- **`swipe/ctc/CtcScriptProjection.kt`** — PHASE_O §3.4 mirrored exactly, for all six scripts,
  unit-tested (ru/bg/mk folds and NO NFD, el mark-strip + final sigma, uk ї/ґ rejection, he
  niqqud). It also owns the single collision-resolving lexicon loop `CtcAzProjection` now
  delegates to.
- **Adapter** — per-language alphabet, per-language model asset, per-ASSET ONNX session and
  failure latch (a dead script graph can no longer disable English), `supportsLayout(…,
  language)`, a `hasLexiconSource` gate, an alphabet-scoped `CtcFuzzyRescue`, and a
  layout-derived finger-occlusion row count.
- **`presetFor`** returns `tunedRuCkdt` for any language with a script row. It was unreachable
  for months, so the constants all six script models were gated and fixture-generated at could
  not be selected. λ/γ/β and both prune terms are unchanged.
- **Router** — gate 1 consults `CtcScriptSupport.ROUTABLE_SCRIPTS`, not `isLatinScript`. Routing
  stays per SCRIPT; serving stays per LANGUAGE, and that division is test-pinned.

### Imported Latin packs — `05c0c25d`, 2026-08-29

`CtcLanguageSupport.sourceFor` consults the static table first and the installed packs on a miss,
so an imported pack that measures a–z-typeable resolves to `CKDT_LANGPACK` and everything
downstream follows with **no new branch** — dispatcher, prewarm, `presetFor`'s λ,
`hasLexiconSource`, settings card. The precedent is it/pt/sv verbatim: the encoder never sees a
language, and λ calibrates to the lexicon's frequency SCALE, which an imported pack shares exactly
with the bundled six.

The gate is **measured a–z projectability**, not a manifest field (the manifest has no script), and
it is not ceremonial. Over every `scripts/dictionaries/langpack-*.zip`: nl/id/ms/sw/tl **100.00 %**,
ru/el **0.00 %**, tr **73.34 %** overall and **81.7 %** of the frequency head — dotless `ı` has no
NFD decomposition, so a quarter of Turkish cannot be spelled on an a–z board. Those words ARE
typeable on geometric, so serving Turkish would be a regression, not a gap. Thresholds 0.98 / 0.99
(head, top 1,000 by rank) sit in a 25-point empty band; the 1,000-word floor is a power floor on
the ratio.

State to respect: the verdict is cached in the single pref `ctc_langpack_verdicts`, keyed by the
pack file's length+mtime, and an UNMEASURED pack answers false and schedules the read — so the
first swipe after an import can go to geometric and every later one to CTC. `INTERNAL_KEYS`
excludes the pref from backups (a per-device measurement of per-device files). Tier: `PROVISIONAL`
by construction and permanently; **no accuracy number may ever be quoted for an imported pack**,
because there is no corpus and not even a fixed vocabulary.

**Russian, exactly.** Ship bytes `src/main/assets/models/ru_synth_v3_ch80_fp16w.onnx`
sha `8fffa75c…` (589,406 B), fixture `ru_synth_v3_ch80_fp16w_golden.json` sha `2e8de3c5…`
(160,384 B, two byte-identical copies), preset `tunedRuCkdt`, layout `cyrl_jcuken_ru.xml`,
alphabet `абвгдежзийклмнопрстуфхцчшщыьэюя` (31 — ё and ъ are CORNER values and are folded away by
the projection, never emission slots).

**The ru lexicon is the imported langpack, not a bundled asset.** `LexiconSource.CKDT_LANGPACK`
reads `filesDir/langpacks/ru/dictionary.bin`, which is byte-identical (sha `2bd8f244…`,
2,088,865 B, CKDT v2, 50,000 words) to `scripts/dictionaries/ru/ru_enhanced.bin` and to
`langpack-ru.zip`'s payload — the file `eval_cyrillic.build_trie` reads, so every published
Russian number is on exactly this lexicon. Consequence to state plainly: **Russian CTC only
works once the user imports `langpack-ru.zip`**; without it ru is not even selectable, and the
`hasLexiconSource` gate hands the swipe to geometric if a backup import sets the pref anyway.

**Evidence tier — say it exactly this way.** 85.07 in-dict top-1, measured on the Yandex
valid-10k, which is **eval-only by licence**. Russian CTC is **val-only permanently**: the
test-2400 seal is spent and no Cyrillic model was ever decoded on it, so no Russian number may
ever be called "test-validated". The model is license-clean synthesis (learned generator, MIT
data only) and saw zero real Cyrillic rows; a sealed twin puts the upper bound at 85.95 and
produced one number and no bytes. The model arm is three seeds (85.30 ± 0.207) and the shipped
s1234 bytes are the LOWEST of the three; the generator and the ceiling arms are single-seed.
**Nothing on-device has ever been measured for any script model** — no latency, no memory.
λ = 2.0 carries a measured, unconfirmed −0.63 t1 shortfall; γ, β and the prune terms are E1's.

### What is left, per script

`el` is the cheapest by a distance and was deliberately NOT routed in this wave. Everything is
ready except two files and a decision: `grek_qwerty.xml` exposes all 25 letters as centre keys,
`langpack-el.zip` exists on the same CKDT scale (and `scripts/dictionaries/el/el_enhanced.bin`
is already built), and **both halves** of the el projection are implemented and unit-tested. The
gap is `el_synth_v3_ch80_fp16w.onnx` (sha `7083794c…`) + its fixture (sha `d08d5501…`), plus the
fact that **Greek has no real-swipe probe at any tier** — its 92.12 is a synthesis-holdout level
and may never be quoted as accuracy — so the ew-cli run would be the only evidence Greek swipe
works at all. Wiring it is a table row, an asset copy and a `SUPPORTED` line.

`uk`, `bg`, `mk`, `he` are infrastructure-ready and blocked on LEXICONS, which must be built
ML-side (`build_wordlist.py --lang <code>` and packaged as CKDT v2 langpacks); `he` additionally
needs a new `hebrew` branch (0x0590–0x05FF) in `build_wordlist._is_script_word`, which currently
raises on any script but latin/greek/cyrillic. Their models and fixtures are also unshipped. Each
row in `CtcScriptSupport` states its own gap; that table is the live list, not this paragraph.

**Still true of every SCRIPT including ru**: the 32-frame budget has never been checked against a
real script lexicon. `CtcDecodableLength` computes it and a test covers `en_enhanced.json`; no
script pack has been swept, and Greek and Ukrainian carry long inflected forms. A word over
budget is unemittable with no error. **The Latin half of that question is now closed**
(`CtcImportedPackSupportTest`, 2026-08-29): zero words over budget in nl/id/ms/sw/tl, worst case
`gemeenteraadsverkiezingen` at 27 of 32 frames — which is why imported-pack eligibility gates on
spelling and not on length. The sweep for the script packs is the same six lines of test.

Geometric is removable **script by script**, ~an hour of GPU each plus a lexicon plus wiring. It
cannot be removed first: deleting it today does not downgrade a Bulgarian user, it removes their
swipe.

**Tier note**: Colemak and arbitrary user XML were never benchmarked. Covered by design; the
worst *measured* layout is german at 81.30 against geometric's ~77, so the expected-value case is
strong — but "Colemak ≥ geometric" is an inference, not a measurement. Say it that way.

---

## Rules that must not be broken

1. **No Yandex data** in any training run or shipped artifact — eval-only by licence. `ru-real`
   at 89.64 is better than everything else and permanently unusable.
2. **No FUTO weights or outputs** in anything we train or ship. Corpus + decode-algorithm lineage
   are the permitted inheritance; `NOTICE:46-64` states it correctly — do not "improve" it.
3. **Respect evidence tiers in prose.** "test-validated" = decoded on a sealed split whose read
   was spent from the ledger; everything else is val-only. Russian is val-only permanently,
   it/pt/sv are `PROVISIONAL`. Quoting a val-only finalist's number as the ship model's is exactly
   how the `sw2345` misattribution happened — twice.
4. **Never route a non-Latin script to CTC** without all three of: a per-script model, a
   per-script trie at the app's own frequency scale, and a golden fixture.
5. **Do not touch λ, γ, β, γ_prune, β_prune.** Corpus-fitted; the published-preset control
   measured −2.3 pt top-1. λ is per-lexicon-SCALE (4.0 en-JSON / 2.0 CKDT), so a raw user knob
   would be wrong by 2× on the wrong asset; expose only a bounded offset if ever.
6. **Testing policy**: never test locally via ADB (build-install and log-read only). ew-cli
   instrumented or pure JVM; if untestable, ask.
7. **CI emulator steps**: `reactivecircus/android-emulator-runner` runs each `script:` LINE as a
   separate `sh -c` (dash, no `pipefail`). Keep every `script:` a ONE-LINE call into
   `.github/scripts/emulator-ci.sh`; inline multi-line bash silently dies on line 1 and reports
   a misleading `adb: device offline`. That kept the workflow red for ~32 consecutive runs.
8. **`sh gradlew`**, not `./gradlew`. Temp files in the session scratchpad, never
   `$TMPDIR`/`$PREFIX/tmp`. `rg`, not `grep`. **Rebuild BOTH APKs before an ew-cli run** — a stale
   app APK gives `NoSuchMethodError` for code you just wrote.
9. **Consult Fable** when stuck, unsure of the optimal approach, on complex tasks, and to audit
   new architectures or risky changes. It has caught: the `sw2345` misattribution, the layout
   census, `finger_occlusion_offset` classified by filename rather than behaviour, and a bulk
   hyphen extraction that would have destroyed 73 native French words.

## Verification owed

- **The ru CTC path has never run on a device.** `da012ded` ships the Russian encoder and routes
  Cyrillic, and every gate that could be checked without hardware is green — but no instrumented
  run has happened since. The next ew-cli run must confirm, in this order: (1)
  `CtcEmissionModelParityTest` passes its NEW ru row, i.e. the packaged
  `ru_synth_v3_ch80_fp16w.onnx` actually loads through ORT and reproduces the fixture's emission
  matrices within 2e-3 and its top-k within 1e-3 — this is the only gate that executes the graph,
  and a stale-matrix fixture passes everything else; (2)
  `CtcMultiLanguageInstrumentedTest.theLayoutGateIsPerLanguage` — the deliberate flip: a Cyrillic
  board is now eligible for ru and still rejected for en, and no script board is eligible for
  another script's language; (3) `everyRoutedScriptShipsItsModelAssetInTheApk` — that aapt really
  packaged the new asset (589,406 B of ONNX is easy to lose to a packaging rule); (4) a ru decode
  end to end WITH `langpack-ru.zip` imported, which no emulator has today — the pack-installed
  path is unreachable on a clean emulator, so this half needs the maintainer's device; (5) the
  latency gate on a ru swipe, because **no script model has ever been timed or memory-profiled**
  — the graph is a fifth of the Latin encoder's bytes so the expectation is favourable, and
  expectation is not measurement. Also worth watching: the trie memo evicts at `size > 2`, and a
  ru primary now pulls a SECOND ORT session alongside the Latin one.
- **The imported-pack CTC path has never run on a device.** `05c0c25d` ships it and every
  hardware-free gate is green. The next ew-cli run must confirm
  `CtcImportedPackInstrumentedTest` (4 cases, written 2026-08-29, never executed): it builds a
  real CKDT v2 pack for the unassigned code `zz`, imports it through the shipping
  `LanguagePackManager`, and checks (1) the file lands where `candidateLangpackRelativePath` says,
  (2) `CtcInstalledPacks` measures it out of the real `filesDir` and the verdict reaches the
  STATIC `CtcEngineAdapter.supportsLanguage` gate, (3) the production merge path builds a trie
  from it, (4) deleting the pack unserves the language in the same process, and (5) a reimport
  with different content is re-measured. Watch for: `Uri.fromFile` through `contentResolver`
  (works in-process, but it is the one assumption that is device-shaped), and the trie memo
  evicting at `size > 2` if a pack language is active alongside two others.
- **The collision-warning dialog has still never been SEEN — but its precondition is now
  reachable in CI.** `CtcImportedPackInstrumentedTest.theCollisionScanSeesAnImportedPacksContractions`
  builds a fixture pack whose `contractions.json` rewrites a real English word (`were` → `we're`)
  and asserts `ContractionCollisionScanner.scan(…).hasPackCollisions`, which is the exact branch
  the dialog gates on and which no emulator could reach before (none has ever had a pack
  installed). What is still owed on the maintainer's device is the DIALOG itself — Compose
  rendering after a language re-selection with a real pack (nl is the bundled-adjacent one)
  alongside a bundled language.
- **Manual, on the maintainer's device**: the 2026-08-26 disabled-not-hidden settings rendering
  (Settings → Input → Advanced Prediction Settings: toggle Context-Aware off → Next-Word switch
  should DIM, not vanish; toggle Personalized Learning off → strength slider + aggression dropdown
  should dim, and the dropdown must not open). Pure-JVM cannot see Compose rendering. Also still
  owed: Italian swipe (moved neural→geometric→CTC in one day);
  first-swipe warm-up now that neural preload is gone; a pre-v1.6.0 backup import (no `neural_*`
  rows written); a pre-v1.1.86 upgrade (`migrateToLanguageSpecific` moved into
  `DictionaryManager.init`, ordering test-pinned but the migration untested end to end).
- **`docs/ARCHITECTURE_MASTER.md` §1/§4/§5** and the data-flow diagram were substantially
  rewritten from code by an agent whose bulk find/replace corrupted six settings docs mid-run
  (caught and redone by hand). Worth a read.
