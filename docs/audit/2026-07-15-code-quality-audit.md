# CleverKeys — Full Code Quality Audit

**Date:** 2026-07-15
**Version audited:** 1.5.0 (`versionName 1.5.0`, build.gradle)
**Commit:** `6c2ad7706` (branch `main`)
**Scope:** 277 Kotlin files / 91,489 LOC in `src/main/kotlin/tribixbite/cleverkeys/`, plus tests (80 pure + 80 instrumented), build system, CI, docs, and repo hygiene.
**Method:** Six parallel dimension audits (architecture, core IME, neural/dictionary pipeline, data/security, UI, engineering practices), each reading the actual code and citing `file:line` evidence, synthesized here.

---

## Executive Summary

CleverKeys is a **real, working, heavily-iterated neural swipe keyboard** with an unusually strong test culture and thoughtful engineering in its hot paths. It is also a **transitional codebase**: a Java-lineage flat keyboard core (visible Unexpected-Keyboard DNA) wrapped in a well-executed but **incomplete strangler-fig migration**. The newer modules — the `onnx/` inference pipeline, the `backup/` pure-JVM diff engine, the decomposed Compose settings screen — are genuinely professional. The older core — a 1,615-line global mutable `Config`, two ~1,300-line coordinator classes duplicating the entire suggestion pipeline, a 158-file flat root package — is where the debt lives.

The audit found a cluster of real but well-scoped defects that undercut the project's "Production Ready (Grade A)" self-billing. None require re-architecture to fix.

> **⚠️ Severity re-rating (2026-07-16).** The original draft of this document labelled two findings "P0/critical." A follow-up realistic-impact review (see [`2026-07-15-remediation-action-plan.md`](./2026-07-15-remediation-action-plan.md) → *Realistic Severity Re-rating*) **downgraded both**: the ONNX memory leak is a genuine but slow-burn **P1** (256 KB/swipe, reclaimed on process death, degrades gracefully), and the exported-activity issue is a genuine security bug of **medium** realistic severity (high impact × low likelihood — needs targeted malware already installed). Several performance/log claims were also deflated. The severities in the tables below are the **original, un-deflated** ratings; read them against the re-rating table in the action plan, which is authoritative.

### Overall grade: **B− / C+**

A competent, battle-hardened, well-tested open-source IME that sits **above the median for open-source Android** on test discipline and documentation, but **below professional-grade** on architectural coherence, a native resource leak, an unauthenticated data-export surface, and core-input accessibility. The distance from B− to a genuine A− is roughly **one to two weeks of focused, well-scoped work** — the fixes are known and mostly localized.

---

## Scorecard

| Dimension | Grade | One-line verdict |
|---|:---:|---|
| Architecture & structure | **C+** | Good newer modules; flat root package, global mutable Config, bridge/initializer sprawl. |
| Core IME implementation | **C+** | Thoughtful async/gesture code; two diverged duplicate pipelines, silent-catch state mutation, main-thread busy-wait. |
| Neural & dictionary pipeline | **B−** | Correct beam search & memory-conscious structures; **P1 per-swipe native leak** (~256 KB, slow-burn), ~2,300 dead lines. |
| Data, persistence & security | **C+** | Excellent parameterized SQL & PII-excluding backup rules; unauthenticated exported export/import (medium realistic severity). |
| UI layer | **B−** | Optimized renderer + de-god-ified settings; keyboard invisible to TalkBack (screen-reader users only). |
| Engineering practices | **B+** | Top-decile test discipline & real CI gate; 972-issue lint baseline, R8 disabled, keystore in worktree (untracked/safe). |

**Aggregate:** simple mean ≈ 2.6 on a 4.0 scale (between C+ and B−). The honest overall is **B−** once the leak (item A) and the export surface (item B) are fixed; without them, **C+**. See the action plan's *Realistic Severity Re-rating* for what each finding actually costs a user.

---

## Critical & High-Priority Findings (the action list)

These are the findings that most gate the "professional / production-ready" claim. Ordered by severity.

| # | Sev | Finding | Location | Fix effort |
|---|:---:|---|---|---|
| 1 | **P0** | **Encoder `memory` OnnxTensor + `OrtSession.Result` leaked on every swipe.** `EncoderWrapper.encode()` returns them "for the caller to own," but no caller closes them — orchestrator drops `memory`, `OrtDecoderSession.cleanup()` closes only the cached src-length tensor, greedy path doesn't close either. One native `[1, ≤250, hidden_dim]` tensor leaks per prediction in a process that lives for days. | `EncoderWrapper.kt:90-111`, `SwipePredictorOrchestrator.kt:382-431`, `OrtDecoderSession.kt:133-139` | ~½ day — close `memory` in `OrtDecoderSession.cleanup()` / after decode. |
| 2 | **P1** *(borderline P0)* | **`BackupRestoreActivity` exported with 6 import/export actions and no caller authentication.** Any zero-permission app can `startActivity(EXPORT_CLIPBOARD, …ownContentUri…)` and receive the **entire clipboard history + learned dictionary + settings**, or inject data inbound via `json_base64`. Only mitigation is an activity flash + Toast. | `AndroidManifest.xml:140-157`, `BackupRestoreActivity.kt:67-132`, `BackupRestoreManager.kt:97` | ~1 day — signature-level permission or drop `exported` + require in-app SAF confirmation. |
| 3 | **P1** | **Dual prediction pipeline duplication with behavioral drift.** `InputCoordinator.onSuggestionSelected` (346 ln) and `SuggestionHandler.onSuggestionSelected` (381 ln) are near-clones that have diverged (Termux backspace handling, possessive augmentation, password-mode respect, prompt-guard). Same operation behaves differently by which pipeline fires; every fix must be applied twice, and history shows several weren't. | `InputCoordinator.kt:535-880`, `SuggestionHandler.kt:378-758` | Multi-day — collapse to one pipeline (deletes dead IC half). |
| 4 | **P1** | **Keyboard is invisible to TalkBack.** The fully custom-drawn `Keyboard2View` (1,790 ln) has zero `AccessibilityNodeProvider`/`ExploreByTouchHelper`/`announceForAccessibility`. Keys cannot be explored or announced. Existential gap for an input method. | `Keyboard2View.kt` (no a11y node integration anywhere) | Multi-day — implement `ExploreByTouchHelper`. |
| 5 | **P1** | **Main-thread busy-wait on cold-start swipe.** `ensureNeuralEngineReady` spins `Thread.sleep(50)` up to 5000 ms, called on the UI thread from `onSwipeEnd`. Cold swipe with a slow ONNX load risks ANR. `neuralEngine` also not `@Volatile` while read cross-thread. | `PredictionCoordinator.kt:229-244`, `InputCoordinator.kt:1176` | ~½ day — await via callback/coroutine, not sleep-loop. |
| 6 | **P1** | **Silent exception swallow around InputConnection commit.** `InputCoordinator.onSuggestionSelected` wraps the entire delete/commit/state-update in `catch (e: Exception) { /* Silently catch */ }`. A mid-commit failure leaves `contextTracker` inconsistent with editor text, with no log even in debug. | `InputCoordinator.kt:869-871` | ~½ day — log + reset tracker state on failure. |
| 7 | **P1** | **Config global mutable singleton force-unwrapped.** `Config.globalConfig() = _globalConfig!!` crashes if any consumer runs before `initGlobalConfig`; ~150 public `@JvmField var` fields read statically by 21+ files make data flow untraceable and unit isolation impossible. | `Config.kt:1183`, `Config.kt:442-519` | Large — introduce immutable snapshot; incremental. |

### Secondary privacy leaks (fix alongside #2)

- **Ungated logging of user text** — flagged independently by two agents. `GreedySearchEngine.kt:116` logs the decoded swipe-typed word at INFO; `Keyboard2View.kt:793,826` logs full selected text; `ClipboardDatabase.kt:246,360,504,678` logs 20-char content prefixes. All un-gated by `BuildConfig.ENABLE_VERBOSE_LOGGING`. **Severity deflated to P3:** on Android 4.1+ `READ_LOGS` is a privileged/system permission, so *other apps cannot read logcat*. Realistic exposure is limited to someone with ADB/USB-debugging access to the physical device, or a user who captures and shares a bug report / logcat (leaking their own typed text). Not a remote or cross-app threat — but still worth gating (cheap, avoids PII in shared logs).
- **Zip-slip in clipboard/full-backup media extraction** — `importClipboardHistoryZip`/`importFullBackup` extract to `File(filesDir, entry.name)` with only a `startsWith("clipboard_media/")` guard (which `clipboard_media/../../foo` satisfies). The `GifPackManager` canonical-path pattern (`GifPackManager.kt:221-224`) already exists — reuse it. **P2.**

---

## Dimension Detail

### 1. Architecture & Structure — C+

**Strengths.** The god-object decomposition is real and tracked: `CleverKeysService.kt` is only 1,029 ln / 40 fns (small for an IME service) with 15+ version-stamped extractions and consistent **manual constructor injection** — no pipeline class takes the service as a constructor type. Interface seams exist at key boundaries (`Config.IKeyEventHandler`, `KeyEventHandler`→`IReceiver`, `onnx/DecoderSessionInterface`). The `onnx/` (14 files), `backup/` (13), and `theme/` (8) subsystems are cleanly layered with pure-JVM-testable cores. 156 data classes, 6 sealed result hierarchies, **zero `GlobalScope`**.

**Weaknesses.**
- **P1 — CLAUDE.md's documented architecture is fictional.** It describes `tribixbite/keyboard2/` with `core/ neural/ data/ config/ utils/ testing/`; the real package is `tribixbite/cleverkeys/` and none of those subdirs exist. Every future contributor/agent is misled.
- **P1 — 57% flat root package.** 158 of 277 files sit directly in the root, including keyboard core, prediction, 9 `Clipboard*.kt` files *despite* a `clipboard/` package existing (4 files), and 15 Activities. Two parallel homes per concern means package boundaries carry no meaning.
- **P1 — `Config` is a 1,615-line global mutable state bag** (see finding #7).
- **P2 — Service-as-singleton escape hatch** (`getInstance()`, static `findKeyByChar()`, `_customizationMode` global flag).
- **P2 — Bridge/initializer proliferation** (10 glue files) substitutes for a composition root; **no DI framework** present.
- **P2 — Monster classes remain in the core path**: `WordPredictor.kt` 2,335 ln, `OptimizedVocabulary.kt` 2,045, `Pointers.kt` 1,870, `Keyboard2View.kt` 1,790 — 19 files ≥1,000 ln, interface-free.
- **P3 — Snake_case Java residue** coexists with camelCase in the same files.

**Subscores:** package org 4 · layering/coupling 6 · separation of concerns 6 · state management 3 · Kotlin idiom 5.

### 2. Core IME Implementation — C+

**Strengths.** `AsyncPredictionHandler` is a clean, correct async design (dedicated HandlerThread, `AtomicInteger` request IDs, triple cancellation checks, real `shutdown()`). Service-level teardown is deliberate (unregisters receivers, frees ONNX sessions with "GC alone is unreliable", stops the ContentObserver). Gesture disambiguation in `Pointers.onTouchUp` and the SAS-1 auto-space state machine are sophisticated and well-commented. Correct main-thread posting via explicit `Handler(Looper.getMainLooper())` with documented rationale.

**Weaknesses.** Findings #3, #5, #6 above, plus:
- **P1 — Unmarked dead code.** `InputCoordinator.handleRegularTyping` (:885-961), `handleBackspace`, `updatePredictionsForCurrentWord`, `calculateDynamicKeyboardHeight` have zero callers and no deprecation marker; the dead `handleRegularTyping` still contains a **live-looking autocorrect implementation that silently diverges** from the real one.
- **P2 — Prediction executors never shut down** (`InputCoordinator:109`, `SuggestionHandler:167`) — two leaked threads past `onDestroy()`.
- **P2 — Orphan `CoroutineScope(Dispatchers.IO)`** per `Pointers`/`EmojiKeywordIndex` instance, never cancelled, re-created on every theme change.
- **P2 — Pipelines still race on `SuggestionBar`** (separate cancellation domains; a stale cursor-sync result can clobber an "Add to dictionary?" prompt that only `SuggestionHandler` guards).
- **P3 — Shipped stub**: `ImprovedSwipeGestureRecognizer.isLikelyNoise` always returns `false` ("For now"), making the zigzag filter a no-op.
- **P3 — Potential infinite loop**: `moveCursorSel` `do{}while(selStart==selEnd)` never terminates if `d==0`.

**Metrics:** 121 `!!` package-wide · **470** `catch (…: Exception)` (ad-hoc catch-and-continue, several empty) · 0 GlobalScope · 6 runBlocking (all off hot path) · 80 TODO/FIXME · largest method `Pointers.onTouchUp` ≈457 ln.

**Subscores:** correctness 6.5 · concurrency 6 · error handling 4.5 · resource lifecycle 6.5 · cleanliness 4.5.

### 3. Neural & Dictionary Pipeline — B−

**Strengths.** Session reuse done right (double-checked singleton, sessions persist for IME lifetime, explicit `cleanup()`). **Input-tensor hygiene is diligent** — every input tensor closed in `finally`, logits deep-copied before `Result.close()`. Beam search is algorithmically sound: numerically stable log-softmax with max-subtraction + temperature, trie-guided logit masking with EOS-only-at-word semantics, GNMT-style length normalization with a worked example in comments, Aho-Corasick prefix boosts. Memory-conscious structures: `VocabularyTrie` uses parallel `CharArray`/`Array<TrieNode?>` (~34 MB saving), binary vocab cache, length-bucketed fuzzy matching (98k→~2k iterations). Hot path avoids all SharedPreferences reads.

**Weaknesses.** Finding #1 (P0 leak) above, plus:
- **P1 — Singleton captures arbitrary `Context`** (no `applicationContext` normalization) — an Activity can be pinned for process lifetime.
- **P1 — Greedy path leaks the final decoder `Result`** on EOS `break` before `close()`.
- **P1 — Unsynchronized concurrent inference + cleanup races** — `SwipeCalibrationActivity` drives the same singleton while the IME worker thread runs `predict()`; shared mutable buffers, `cleanup()` can close sessions mid-predict (TOCTOU on `!!`).
- **P1 — "Pure ONNX, NO fallbacks" is inaccurate.** Failures resolve to a silent `Result(emptyList(), emptyList())` with no user signal; `PredictionPostProcessor` has an explicit "Fallback: Basic filtering" branch; `filterPredictions` returns raw candidates when vocab isn't loaded. The no-fallback claim holds only for the removed CGR path.
- **P2 — ~2,300 lines of dead legacy code shipped**, contradicting "NO CGR": `ContinuousGestureRecognizer` (916 ln, never instantiated), `ComprehensiveTraceAnalyzer` (657, zero refs), `MemoryPool`/`BroadcastSupport`/`SessionConfigurator`, `DecoderWrapper` decode paths.
- **P2 — Duplicated prediction infra**: `PredictionCoordinator` builds a `WordPredictor` *and* a `DictionaryManager` whose per-language cache loads the same dictionary again (~5-10 MB ×2); `DictionaryManager.getPredictions()` has no production caller. Contraction maps owned by 4 classes; two bigram implementations coexist.
- **P2 — `ModelLoader.loadModelBytes` sizes buffer with `stream.available()`** — silently truncates `content://` (imported) models.
- **P2 — Beam-search scoring inconsistencies**: ranking normalizes by `tokens.size` (incl. SOS/EOS) vs final confidence by `wordStr.length`; score-gap early-stop compares unnormalized scores; `NEURAL_BEAM_SCORE_GAP` default `80.0f` vs doc `8.0f`; dead "diversity" constants.
- **P2 — Allocation churn in beam hot loop** (per-step `BeamState` clones, boxed `PriorityQueue<Int>`, vocab-sized `FloatArray` per beam even with no boosts, regex recompiled per candidate).

**Subscores:** ONNX lifecycle 5 · algorithm quality 7 · memory management 5 · robustness/fallbacks 6 · maintainability 5.

### 4. Data, Persistence & Security — C+

**Strengths.** **SQL is uniformly parameterized** — all 66 `rawQuery` calls bind via `arrayOf(...)`, table/column names are compile-time consts, every cursor `.use{}`-wrapped, zero injection surface. FTS4 handled correctly (`sanitizeFtsQuery` strips syntax chars). Migrations are sound (CREATE-COPY-DROP-RENAME for pre-3.35 SQLite, ALTER ADD for v3→v4, rollback-on-throw, position tie-breakers, explicit transactions). **Privacy-by-design**: `backup_rules.xml`/`data_extraction_rules.xml` exclude ALL databases + every PII pref, whitelisting only theme aesthetics; **no INTERNET permission**; clipboard capture skips password managers and honors `IS_SENSITIVE`.

**Weaknesses.** Findings #2 (P1 exported export/import), the zip-slip and ungated-log P2s above, plus:
- **P3 — Unbounded in-memory reads on imported archives** (`zipIn.readBytes()`, full-file base64 decode, uncapped JSON array iteration) — zip-bomb/OOM.
- **P3 — Over-broad export surface**: ~13 activities `exported=true`; only `SettingsActivity` (GIF share target) and `LauncherActivity` need it.
- **P3 — Weak text dedup hash** (`String.hashCode()`, 32-bit) — mitigated by paired `hash=? AND content=?` check, so not a correctness bug.

**Metrics:** 66/66 rawQuery parameterized · exported: 2 system-protected services + 13 activities incl. unauthenticated `BackupRestoreActivity` · 2 ungated full-text logs + ~6 truncated.

**Subscores:** database quality 8 · import/export safety 4 · privacy posture 5 · permissions/manifest 4 · migration robustness 8.

### 5. UI Layer — B−

**Strengths.** **Rendering discipline in the hot path is genuinely good**: `Theme.Computed.Key` pre-allocates/reuses paints, `Keyboard2View` uses a static `_tmpRect`, a rewound `_swipeTrailPath` instead of per-frame `Path()`, and a theme-computation cache keyed on `name+width+version`; `onMeasure` guards null-keyboard crashes. **Settings was successfully de-god-ified** — `SettingsActivity.kt` is a 754-line shell hosting a declarative Compose `ui/settings/` package (6,835 ln, 17 sections). Context-leak hygiene solid (`applicationContext` everywhere checked). Dark mode universal + a rich user theme engine. Infinite-animator cleanup wired via `AndroidView(onRelease)`.

**Weaknesses.** Finding #4 (P1 TalkBack) above, plus:
- **P2 — 143 `mutableStateOf` fields on the Activity**, composables are extension functions on it (`@Composable fun SettingsActivity.SettingsScreen()`) — non-previewable (only **2 `@Preview`** in the whole app), untestable in isolation, inverted state hoisting.
- **P2 — Side effect during composition** (`mainScrollState = scrollState` assigned in composable body without `SideEffect{}`).
- **P2 — Per-frame allocations in `onDraw`**: `drawCustomMappings` runs per key per draw calling `.lowercase()` + `filter{}.associateBy{}` — GC churn in the hottest path under full-view `invalidate()` on every pointer event. Pre-index as `Map<keyCode, Map<Direction, Mapping>>`.
- **P2 — `SuggestionBar` rebuilds all TextViews every prediction post** (`removeAllViews()` + `TextView(context)` per suggestion, per keystroke); hardcoded English `"Add '$x' to dictionary?"`, stringly-typed `dict_add:` sentinel protocol.
- **P2 — Three parallel theming systems** (legacy `Theme.kt`, `theme/` Compose, `MaterialThemeManager`) + each settings activity re-rolls default unbranded M3 schemes; no shared `CleverKeysTheme`.
- **P3 — UI paradigm sprawl**: Compose (48 files) + imperative views (`SuggestionBar` 971 ln) + legacy `ListView`/`GridView` + 22 XML layouts + 8 custom-onDraw views.

**Metrics:** 76 `contentDescription` / **0** AccessibilityNodeInfo · 48 Compose files, 113 `@Composable`, **2 `@Preview`** · 629 `0xFF…` color literals (~460 legit palette defs).

**Subscores:** view architecture 6 · settings UI 7 · rendering performance 7.5 · accessibility 3 · theming consistency 6.5.

### 6. Engineering Practices — B+

**Strengths.** **Top-decile test discipline**: the pure suite (80 files, 1,621 `@Test`) has 2,539 Truth `assertThat` vs only **9 `assertNotNull`** — behavior-driven, not smoke. `BeamSearchEngineTest` drives beam search through a programmable `FakeDecoderSession` double; `AutocorrectTest` asserts concrete corrections with documented rationale. **Regression culture**: issue-numbered classes + **drift tests** that scan source to catch unclassified keys. **CI actually gates on the real suite** (`ci.yml` runs `runPureTests` non-optionally; instrumented tests on emulator + nightly; trivy SHA-pinned). Build system is thoughtful (single-source ABI-split version math matching F-Droid, env-driven signing with empty-string guard, reproducibility measures). Docs are extensive (219 files, TOC, 40+ specs, Keep-a-Changelog) and README uses footnoted comparisons over bare hyperbole.

**Weaknesses.**
- **P2 — `release.keystore` in the repo working directory** (untracked — `.gitignore` `*.keystore` covers it, so **not P0**) — a production signing key one `git add -f` from leaking. Move it out of the worktree.
- **P2 — Hand-maintained test-class lists orphan tests.** `runPureTests`/`runMockTests` hardcode class arrays; `ClipboardSearchRegexTest` is in neither and runs only under the non-gating `continue-on-error` `gradlew test` — a passing CI can ship it red. No drift check on the lists themselves.
- **P2 — 973 suppressed lint issues** (10,660-line baseline incl. 76 `NewApi` on `minSdk 21`); lint is `continue-on-error`, so the baseline only grows.
- **P2 — R8/ProGuard disabled with a temp comment made permanent** (`minifyEnabled false // REPRODUCIBILITY TEST`); 295-line `proguard-rules.pro` dormant, APK ~48-52 MB.
- **P2 — Logic-mirror tests** reimplement the production decision function and test the copy (`AutoSpaceLogicTest` "Mirrors SuggestionHandler.kt lines 634-655") — can drift green.
- **P3 — androidTest smoke tail** (260 `assertNotNull`), committed stray artifacts (3 `remote_v*/CHANGELOG.md`, `archive/` 341 dead files), root-dir junk drawer (48 MB APK, ~25 build logs, dex/hex dumps), duplicate CI workflows, stale docs (SECURITY.md "1.0.x current", README "Api 26+" vs `minSdk 21`, CLAUDE.md "CURRENT STATUS 2026-03-26").

**Metrics:** pure 1,621 `@Test` (assertThat 2,539 / assertNotNull 9) · instrumented 1,371 `@Test` (assertEquals 982 / assertNotNull 260) · MockK 16 files, Robolectric 18 · lint baseline 973 issues · release.keystore **not committed**.

**Subscores:** test quality 8 · build system 7 · CI 7 · repo hygiene 5 · documentation 8.

---

## Cross-Cutting Themes

1. **Two eras, never converged.** Every dimension independently described the same split: a mature Java-lineage core (flat package, mutable `Config`, snake_case, monolithic `WordPredictor`/`Pointers`/`Keyboard2View`) versus clean, recent, testable modules (`onnx/`, `backup/`, `ui/settings/`). The team clearly *can* build well — the strangler-fig migration just isn't finished.

2. **Duplication is the recurring failure mode.** Two suggestion pipelines, two `WordPredictor`/dictionary stacks, four contraction-map owners, two bigram implementations, three theme systems, logic-mirror tests. Each doubles maintenance surface and each has already produced observed drift.

3. **Documentation overstates readiness.** "Development 100% complete / Production Ready (Grade A) / Pure ONNX NO fallbacks / NO CGR" — the audit found a P0 leak, silent fallbacks, ~2,300 lines of live CGR-adjacent dead code, and a fictional package layout. The docs are extensive and mostly excellent, but the top-line status claims are marketing, not measurement.

4. **Privacy is designed-in but has holes.** The backup-exclusion rules and no-INTERNET posture are exemplary; they're undercut by one unauthenticated exported component and a handful of ungated user-text logs — a small, fixable delta between "privacy-first design" and "privacy-first behavior."

---

## Recommended Remediation Order

**Sprint 1 — ship-blockers (est. 2-4 days):**
1. Close the encoder `memory` tensor + `Result` (finding #1). *Half day, highest leverage.*
2. Authenticate or de-export `BackupRestoreActivity`; add canonical-path check to media extraction; gate/remove the user-text logs (findings #2 + secondary). *~1.5 days.*
3. Fix the main-thread busy-wait and the silent InputConnection catch (findings #5, #6). *~1 day.*

**Sprint 2 — structural debt (est. 1-2 weeks):**
4. Collapse `InputCoordinator`/`SuggestionHandler` into one pipeline; delete the dead `InputCoordinator` half and the ~2,300 lines of dead onnx/CGR code (findings #3 + neural P2). *This single change resolves the drift, the race, and roughly a third of the core maintenance surface.*
5. Implement `ExploreByTouchHelper` for `Keyboard2View` (finding #4).
6. Normalize the ONNX singleton to `applicationContext`; add inference/cleanup synchronization (neural P1s).

**Sprint 3 — hygiene & docs (ongoing):**
7. Move `release.keystore` out of the worktree; make lint gating (or at least freeze the baseline); re-enable R8.
8. Add a drift check for the hand-maintained test-class lists.
9. Correct the top-line status claims in CLAUDE.md/README/SECURITY.md to match reality; fix the documented package layout.
10. Flatten the root package into feature-first subpackages incrementally; introduce an immutable `Config` snapshot.

---

## Verdict

**CleverKeys is a genuinely capable, well-tested, privacy-conscious neural keyboard held back from professional-grade by a native memory leak, an unauthenticated export surface, an accessibility gap, and unfinished architectural consolidation.** The engineering *instincts* on display — the test culture, the hot-path optimization, the documented fix history, the modular newer subsystems — are those of a competent team. The gap to A− is not a rewrite; it is closing a known, well-scoped punch list, and correcting documentation that currently claims the punch list is already closed.

**Overall: B− (C+ until findings #1 and #2 are fixed).**
