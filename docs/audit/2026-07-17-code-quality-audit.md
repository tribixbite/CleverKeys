# CleverKeys — Full Code Quality Audit & Grade

**Date:** 2026-07-17
**Version audited:** 1.5.0 (`VERSION_MAJOR.MINOR.PATCH = 1.5.0`, build.gradle:73-75)
**Commit:** `4ad8a536d` (branch `main`)
**Scope:** 277 Kotlin files / ~91,500 LOC in `src/main/kotlin/tribixbite/cleverkeys/`, plus the ~2,990 tests, the Python dictionary/langpack build pipeline (`scripts/`), the web demo (`web_demo/`), the build system, CI workflows, manifest, resources, and repo hygiene.
**Method:** Seven independent auditors, each reading source only and blinded to prior audits, across six dimensions (architecture, core IME, neural/dictionary, data/security, UI, engineering practices) plus one adversarial verifier tasked with *refuting* the predecessor audit's headline claims and hunting blind spots it never covered. Every finding below cites `file:line` evidence; the highest-impact and most surprising claims were then re-verified by hand (git object size, `minifyEnabled`, the encoder `finally` block, the manifest export, the Turkish-i path, the web-demo dictionary drift).

> **Relationship to the prior audit.** The `2026-07-15` audit in this directory was produced by a different model (Opus 4.8). This audit was run **independently and blind** to it, then reconciled. The short version: **its two headline findings are real and, if anything, understated** — but it materially **over-graded three dimensions** (neural, UI, engineering practices) by missing the dead swipe-results handler, the phantom multi-language model loading, the ~19.7 GB git history, the non-functional instrumented CI, and the forked/drifted web demo. The honest overall is a grade lower than its "B−": a **C+**.

---

## Executive Summary

CleverKeys is a **real, working, heavily-iterated neural swipe keyboard** built by someone who genuinely understands both beam-search decoding and Android memory constraints. Its best parts — the ONNX inference module, the pure-JVM backup/diff engine, the `VocabularyTrie`/`PrefixBoostTrie` data structures, the drift-test culture, the SAS-1 auto-space state machine — are professional-grade and, in places, better than typical production IME code.

It is also a **stalled strangler-fig migration**. A Java-lineage core (a 158-file flat root package, a 242-field mutable global `Config`, two ~1,300-line coordinator classes running duplicated-and-drifted suggestion pipelines, 18 thousand-line interface-free classes) coexists with the clean newer modules but was never converged. On top of that structural debt sit a cluster of concrete, well-scoped defects — a native memory leak on **every swipe**, an **unauthenticated exported data-export activity**, a **completely inaccessible keyboard**, a **non-functional instrumented-test CI**, and a **~19.7 GB git history** — that together contradict the project's own "Development 100% complete / Production Ready (Grade A)" self-billing.

None of this requires a rewrite. The distance from where it is to a genuine B+/A− is a **known, mostly-localized punch list** of roughly three to six focused engineer-weeks. But the top-line status claims in the docs are marketing, not measurement, and this audit rates the shipped reality below them.

### Overall grade: **C+**

A capable, well-tested, privacy-conscious open-source IME that sits **above the median for open-source Android** on test discipline, documentation, and hot-path craftsmanship, but **below professional-grade** on architectural coherence, native-resource correctness, security surface, accessibility, and release/CI rigor. It rises to a defensible **B/B+** once the Tier-1/2 punch list (the leak, the export lockdown, the CI and repo-hygiene fixes) is closed — the work is scoped and understood.

---

## Scorecard

| Dimension | Grade | This audit's one-line verdict | Prior audit |
|---|:---:|---|:---:|
| Architecture & structure | **C+** | Excellent newer modules; 57% flat root package, 242-field mutable global `Config`, 13 delegation shims, docs describe a package tree that doesn't exist. | C+ |
| Core IME implementation | **C+** | Sophisticated async/gesture code; two diverged live suggestion pipelines, the *richer* swipe handler is dead, main-thread busy-wait sized at the ANR limit. | C+ |
| Neural & dictionary pipeline | **C+** | Sound beam search & memory-conscious structures; **encoder tensor + Result leak on every swipe**, predict/cleanup race, phantom multi-language models, ~3,400 dead LOC. | B− |
| Data, persistence & security | **B−** | Exemplary parameterized SQL & PII-excluding backup rules; **unauthenticated exported export/import**, media zip-slip, ungated user-text logs shipped in release. | C+ |
| UI layer | **C+** | Real paint/path reuse + de-god-ified settings; **keyboard invisible to TalkBack**, dead a11y toggles, per-frame onDraw allocations, English-only UI, 4-way theming fork. | B− |
| Engineering practices | **B−** | Top-decile test/drift discipline & reproducible builds; **instrumented CI runs nothing**, releases ship untested, **~19.7 GB git history**, R8 disabled, 973-issue lint baseline never gates. | B+ |

**Aggregate:** simple mean ≈ **2.43 / 4.0**, i.e. squarely **C+**. The prior audit's B− assumed data/security and engineering were carried by strengths this pass found to be partly hollow (untested CI, unclonable repo). Fixing the two ship-blockers (leak + export) lifts neural and security to B/B+ and the overall to **B−**; fixing the CI/repo/accessibility tail lifts it to **B+**.

---

## What This Audit Changes vs. the 2026-07-15 (Opus 4.8) Audit

The user flagged that the prior audit "may be wrong or have missed things." An adversarial verifier tested its eight headline claims against source. **All eight CONFIRMED.** Three are **worse than it stated**, and it **over-graded three dimensions** by missing defects:

**Confirmed and sharpened (worse than prior audit said):**
- **The ONNX leak is bigger than "the memory tensor."** The entire encoder `OrtSession.Result` leaks alongside its `memory` tensor, on **100% of default-path swipes**; the greedy path leaks its final `Result` on every EOS as well — five distinct leak paths, two firing on every swipe (`EncoderWrapper.kt:90-121`, verified: the `finally` at :116-120 closes only the three *input* tensors). The prior audit's own follow-up "re-rated" this from P0 down to P1 on the theory that the process recycles before it matters; that re-rating is optimistic for an IME process that Android keeps near-foreground for days. **It is a genuine P0-severity correctness defect** (trivially fixable, ~3 lines).
- **The exported-activity bug is a concrete zero-permission exfiltration, not a "surface."** `BackupRestoreActivity` is `exported="true"` with a MAIN filter (`AndroidManifest.xml:140`); a zero-permission app can hand it a `content://` sink it owns and receive the full clipboard history + learned dictionary, or inject via a `json_base64` extra needing no URI grant at all.
- **Dead code is ~3,900 LOC, not ~2,300**, and it *all ships* because `minifyEnabled false` (build.gradle:256) — the 295-line ProGuard file never runs, so R8 never strips it.

**Missed by the prior audit (found this pass):**
- The **richer swipe-results handler is unreachable dead code**: `CleverKeysService.handlePredictionResults` has zero callers, so `SuggestionHandler`'s password gate *and* possessive augmentation never run on swipes; the live `InputCoordinator` path has neither.
- **`MultiLanguageManager` loads per-language ONNX models that don't exist** (`swipe_encoder_${language}.onnx`) — a guaranteed-failure path directly contradicting the README's "one model, all languages."
- **`BigramModel` is a 60-pair hardcoded toy** feeding up to a 10× multiplier into word ranking.
- **The git object store is ~19.7 GB** (`git count-objects -vH` → size-pack 18.38 GiB, verified) — effectively unclonable; the repo also carries a **broken submodule** (`squoosh` gitlink, no `.gitmodules`) and a 341-file `archive/` dir.
- **The instrumented CI is non-functional**: `ui-testing.yml` never runs `connectedAndroidTest`, every `adb` command is `|| true`, and it targets `tribixbite.cleverkeys` while the installed debug APK is `tribixbite.cleverkeys.debug` — so 1,371 instrumented tests gate nothing and the release pipeline runs **zero** tests.
- **The keyboard has no i18n** (English-only Compose/settings UI despite 25 `res/values-*` locale dirs), **no `supportsRtl`** (verified absent from the manifest), and a **Turkish-i locale bug** on the neural key-position path (`Keyboard2View.kt:1049` uses default-locale `toLowerCase()`, so 'I'→'ı' on Turkish devices mislocates keys).
- **Nine artifact-integrity defects in the Python `scripts/` pipeline** that produces the shipped dictionaries (spell-oracle inverts on subprocess failure, V2 builder truncates multi-token lines, non-reproducible langpack ZIPs).

**Where the prior audit was right and this pass agrees:** the flat root package, the mutable global `Config`, the dual-pipeline drift, the main-thread busy-wait, the parameterized-SQL and backup-rule strengths, the drift-test culture, and the "documentation overstates readiness" theme all reproduce cleanly.

---

## Critical & High-Priority Findings (the action list)

Ordered by realistic severity. "Realistic" folds in likelihood and blast radius, not just worst-case.

| # | Sev | Finding | Location | Fix effort |
|---|:---:|---|---|:---:|
| 1 | **P0** | **Encoder `memory` tensor + `OrtSession.Result` leak on every swipe.** `EncoderWrapper.encode()`'s `finally` closes only the 3 input tensors; the returned `EncoderResult.memory` and the encoder `Result` are never closed by any caller. `OrtDecoderSession.cleanup()` closes only the cached src-length tensor. Greedy path additionally leaks its final `Result` on the EOS `break`. ~256 KB native/swipe (d_model 256 × ≤250 seq), GC-invisible, in a process that lives for days. | `onnx/EncoderWrapper.kt:90-121`, `onnx/SwipePredictorOrchestrator.kt:382-476`, `onnx/OrtDecoderSession.kt:133-139`, `onnx/GreedySearchEngine.kt:66-96` | ~½ day |
| 2 | **P1** | **`BackupRestoreActivity` exported with import/export actions and no caller authentication.** Any zero-permission app can export the entire clipboard history + learned dictionary + settings to a `content://` sink it controls, or inject data via a `json_base64` extra. | `AndroidManifest.xml:140-157`, `BackupRestoreActivity.kt:84-132`, `BackupRestoreManager.kt:97` | ~½ day |
| 3 | **P1** | **Keyboard is invisible to TalkBack.** Zero `AccessibilityNodeProvider`/`ExploreByTouchHelper`/`AccessibilityNodeInfo` anywhere in `src/main`; the fully custom-drawn `Keyboard2View` exposes no virtual keys. A blind user cannot locate or activate any key — product-disqualifying for an IME. Compounded by an "Accessibility" settings section whose `sticky_keys`/`voice_guidance` toggles are read by no code. | `Keyboard2View.kt` (whole file), `ui/settings/sections/AccessibilitySection.kt:56-61` | Multi-day |
| 4 | **P1** | **Instrumented CI runs nothing; releases ship untested.** `ui-testing.yml` never invokes `connectedAndroidTest`, gates every `adb` call with `\|\| true`, and targets the wrong package id. `release.yml` builds→signs→publishes with no test job. 1,371 instrumented + 46 orphaned tests gate zero merges. | `.github/workflows/ui-testing.yml:153-181`, `release.yml`, `build.gradle:274` | 1 day |
| 5 | **P1** | **Dual prediction pipelines, diverged, with the richer half dead.** Swipe auto-insert runs `InputCoordinator.onSuggestionSelected`; tap runs `SuggestionHandler.onSuggestionSelected`. They diverge on Termux deletion policy (now *contradictory*), URL-field handling (#151), possessives, and capitalization preservation. `CleverKeysService.handlePredictionResults` (which would route swipes through SH's password gate + possessives) has **zero callers**, so those features never run on swipes. | `InputCoordinator.kt:535-880`, `SuggestionHandler.kt:282-758`, `CleverKeysService.kt:918` | Multi-day |
| 6 | **P1** | **Main-thread busy-wait sized at the ANR threshold.** `ensureNeuralEngineReady` spins `Thread.sleep(50)`×100 (up to 5000 ms) then synchronously loads the model, called on the UI thread from `handleSwipeTyping`. The input-dispatch ANR limit is 5 s. | `PredictionCoordinator.kt:222-253`, `InputCoordinator.kt:1176` | ~½ day |
| 7 | **P1** | **~19.7 GB git history.** size-pack 18.38 GiB (verified) plus a 341-file `archive/`, a broken `squoosh` submodule gitlink (no `.gitmodules`), and tracked legacy models/APKs — effectively unclonable for contributors. | `.git/objects/pack/`, `git ls-files archive` | Coordinated history rewrite |
| 8 | **P1** | **predict()/cleanup() race on the ONNX session.** `cleanup()` closes both sessions unsynchronized while `predict()` reads `decoderSession!!`/`encoderWrapper!!` on the worker thread — TOCTOU use-after-close on service destroy mid-swipe. Singleton also pins the first caller's (possibly Activity) `Context`. | `onnx/SwipePredictorOrchestrator.kt:270-271,382,710-716` | ~½ day |
| 9 | **P1** | **242-field mutable global `Config`, `!!`-unwrapped.** `Config.globalConfig() = _globalConfig!!` crashes on any pre-init race; 84 static call sites in 28 files make data flow untraceable and unit isolation impossible. | `Config.kt:426-538,1183` | Large (incremental) |
| 10 | **P1** | **User-typed text logged ungated, shipped in release.** `SuggestionHandler` logs every committed word (`:729` + ~10 more) with no `BuildConfig.ENABLE_VERBOSE_LOGGING` gate; `minifyEnabled false` + no `-assumenosideeffects` means the logs survive in release APKs. Hot-path `Pointers.onTouchMove`/`Autocapitalisation` also log per-event. | `SuggestionHandler.kt:729`, `Pointers.kt:772-881`, `build.gradle:256` | ~2 h |

### Secondary findings (fix alongside the above)

- **P2 — Zip-slip in clipboard-media import.** `File(context.filesDir, entry.name)` guarded only by `startsWith("clipboard_media/")`, which `clipboard_media/../databases/clipboard.db` satisfies — arbitrary write inside the sandbox. `GifPackManager.kt:221` already has the canonical-containment pattern to reuse. (`BackupRestoreManager.kt:905,1302` → `ClipboardMediaManager.kt:217`)
- **P2 — Prediction executors never shut down.** `InputCoordinator.predictionExecutor`, `SuggestionHandler.predictionExecutor`, `SwipePredictorOrchestrator.executor`, and a static `ContinuousGestureRecognizer` thread pool have no `shutdown()` and aren't in `CleanupHandler` — leaked threads + queued heavy tasks past `onDestroy`.
- **P2 — Silent catch around the live swipe-commit path.** `InputCoordinator.kt:869-871` — `catch (e: Exception) { /* Silently catch */ }` swallows delete/commit/state failures with no log, silently desyncing the context tracker from the editor.
- **P2 — Model buffer sized by `stream.available()`.** `ModelLoader.kt:146-155` silently truncates `content://` (imported) models; use `readBytes()` and validate.
- **P2 — Silent unrecoverable neural init.** `NeuralSwipeTypingEngine.initialize()` sets `initialized = true` *in the catch block* (`:79-84`), so a failed model load never retries and every swipe returns empty forever, with only logcat evidence — contradicting the "Pure ONNX, NO fallbacks" claim, which is also false (`PredictionPostProcessor` has an explicit fallback branch).
- **P2 — ~3,900 LOC dead/phantom code ships** (R8 off): `ContinuousGestureRecognizer` (916) + `WordGestureTemplateGenerator` (335) + unlaunchable `TemplateBrowserActivity` (294), `ComprehensiveTraceAnalyzer` (657), `NgramModel` (318), `onnx/DecoderWrapper` decode paths (285), `MemoryPool`/`BroadcastSupport`/`SessionConfigurator`, `MultiLanguageManager` phantom model loading.
- **P2 — Per-frame allocations in `onDraw`.** `drawCustomMappings` runs `.lowercase()` + `filter{}.associateBy{}` per key per frame under touch-move-frequency `invalidate()`; unmemoized `KeyModifier.modify()` per label per frame when shift is latched.
- **P2 — 973-issue lint baseline never gates** (`abortOnError=false`, `checkReleaseBuilds=false`, `continue-on-error: true` on both CI steps); R8 disabled behind a fossilized "REPRODUCIBILITY TEST" comment.
- **P3 cluster** — `moveCursorSel` latent infinite loop if `d==0`; `ImprovedSwipeGestureRecognizer.isLikelyNoise` always returns `false` (shipped stub); version-code math (`MAJOR*10000 + MINOR*100 + PATCH`) collides at MINOR/PATCH ≥ 100; no `supportsRtl`; stale `SECURITY.md` ("1.0.x current") and `CLAUDE.md` ("Grade A, 2026-03-26"); README "minSdk 26" vs actual `minSdk 21`.

---

## Dimension Detail

### 1. Architecture & Structure — C+

**Strengths.** Genuine manual constructor-injection composition root (`ManagerInitializer.kt` builds the dependency graph in order, returns a typed result). The `backup/` package is a clean hexagonal seam — 11 of 13 files are pure-JVM (no `android.*`) and unit-tested. `onnx/` is the best-factored module (14 focused files behind a `DecoderSessionInterface` test seam). Coroutine hygiene is above average: **0 `GlobalScope`**, 6 `runBlocking` all confined to one off-path file, 156 data classes, low `!!` density (~1.4/kLOC).

**Weaknesses.** 158 of 277 files (57%) sit in the flat root package, mixing every concern; concerns are split across two homes (12 root `Clipboard*` vs a `clipboard/` package, 15 neural root files vs `onnx/`, etc.) because each subpackage migration stalled halfway. `Config` is a 242-field mutable `@JvmField` global consumed statically from 28 files via a `!!` unwrap. 18 files ≥1000 lines (WordPredictor 2335, OptimizedVocabulary 2045, Pointers 1870, Keyboard2View 1790…) mostly without interfaces. 13 Initializer/Bridge/Propagator glue files (~1,821 LOC) are mechanical delegation, not abstraction. CLAUDE.md documents a `tribixbite/keyboard2/{core,neural,data,...}` tree that **does not exist**.

**Subscores:** package org 3.5 · layering/coupling 5.5 · separation of concerns 5 · state management 4 · Kotlin idiom 6.5.

### 2. Core IME Implementation — C+

**Strengths.** `AsyncPredictionHandler` is textbook async cancellation (dedicated HandlerThread, `AtomicInteger` request IDs, triple staleness re-checks, real `shutdown()`). The SAS-1 position-stamped auto-space state machine and `Pointers.onTouchUp` gesture disambiguation are genuinely sophisticated, defensively designed, and commented with regression provenance (issue numbers, commit refs). Backspace-undo verifies editor reality before deleting; Direct Boot correctness is handled; native cleanup is deliberate ("GC alone is unreliable").

**Weaknesses.** The dual-pipeline drift (#5) with the richer swipe handler unreachable; the main-thread busy-wait (#6); the silent commit-path catch; ~350 lines of dead drifting copies inside `InputCoordinator` (including an *older* autocorrect implementation that's a landmine for future edits); ungated hot-path logging on every touch-move; four executors never shut down; a cross-pipeline `SuggestionBar` race held together by ~80 lines of manually-mirrored code.

**Metrics:** 432 `catch (Exception)` project-wide (several empty/comment-only, including the live commit path); 131 `!!`; 80 TODO/FIXME; largest method `Pointers.onTouchUp` ≈455 lines.

**Subscores:** correctness 6.5 · concurrency 5.5 · error handling 5 · resource lifecycle 6 · cleanliness 5.5.

### 3. Neural & Dictionary Pipeline — C+

**Strengths.** Beam search is well-engineered: numerically stable log-softmax, consistent GNMT length normalization (applied in both ranking and final confidence), trie-guided masking with EOS gated on `containsWord`, capped Aho-Corasick prefix boosts with zero-alloc state transitions, converged-beam dedup — and a real 401-line pure-JVM test driving a `FakeDecoderSession`. `VocabularyTrie`/`PrefixBoostTrie` are models of memory-conscious design with honest savings analyses. Input tensors are diligently closed; hot path does zero prefs reads.

**Weaknesses.** The P0 leak (#1) — the pipeline fails its most important non-negotiable, ORT native lifetime, on every swipe. The predict/cleanup race (#8). Silent unrecoverable init failure. ~3,400 dead LOC including a never-used parallel `DecoderWrapper` decode stack. Phantom `MultiLanguageManager` per-language model loading contradicting the README. `BigramModel` is a 60-pair toy feeding a 10× ranking multiplier. Duplicate full-dictionary residency (`OptimizedVocabulary` + `WordPredictor` each load the 98k dict). `VocabularyCache` has no content-version → stale dictionary across app updates. `ModelLoader` docstrings drift from behavior (claims NNAPI-first, code is XNNPACK-first; QNN is a `false` stub).

**Subscores:** ONNX lifecycle 3 · algorithm quality 7 · memory management 5 · robustness 4 · maintainability 5.

### 4. Data, Persistence & Security — B−

**Strengths.** **SQL is 100% parameterized** — every value binds via `?`+`arrayOf`, all identifiers are compile-time constants, every cursor `.use{}`-wrapped, zero injection surface. Migrations are transactionally correct (v2→v3, v3→v4 rollback on throw). **Privacy-by-design is real**: no INTERNET permission (only VIBRATE + READ_USER_DICTIONARY), `backup_rules.xml`/`data_extraction_rules.xml` exclude *all* databases + every PII pref across both API tiers, clipboard capture honors Android-13 `IS_SENSITIVE` and skips password-manager foregrounds, suggestions suppressed in password fields. Signing is env-driven; no release secrets in git.

**Weaknesses.** The exported unauthenticated `BackupRestoreActivity` (#2) — the one architecturally serious hole. Media-import zip-slip (P2). Ungated user-text logs surviving into release (#10). 13 of 15 exported activities are internal settings screens needlessly reachable. v1→v2 migration swallows exceptions (inconsistent with later ones). Unbounded `readBytes()` on untrusted archive entries.

**Subscores:** database quality 9 · import/export safety 5 · privacy posture 8 · manifest/permissions 5 · migration robustness 8.

### 5. UI Layer — C+

**Strengths.** Real render-hot-path discipline: `Theme.Computed.Key` reuses paints, `Keyboard2View` reuses a single `Path` (rewind) + static `RectF`, computed themes are LruCache'd with correct `config.version` invalidation, char labels are allocation-free. Settings decomposition is deliberate and documented (754-line shell + `ui/settings/` sections); rotation-transient state correctly lives in ViewModels; three legacy adapters recycle `convertView`; careful edge-to-edge inset handling; dark mode follows system at the shared theme root.

**Weaknesses.** The TalkBack gap + dead a11y toggles (#3). Per-key-per-frame allocations in `drawCustomMappings` and unmemoized `modifyKey` — churn in the exact hot path the reuse patterns protect. `SuggestionBar` rebuilds its whole view tree per update via a stringly-typed `dict_add:`/`exact_add:` protocol. Business logic (pref writes, intent launches, text editing) lives inside the 1790-line render View. **English-only UI** (72 raw `Text("…")` literals vs 103 `R.string`) despite 40+ layouts + 25 locale dirs. 4-way Compose theming fork with two competing persistence stores. 143 `mutableStateOf` on `SettingsActivity` with composables as receiver extensions → **2 `@Preview`** app-wide.

**Subscores:** view architecture 6 · settings UI 6 · rendering performance 6 · accessibility 1 · theming consistency 5.

### 6. Engineering Practices — B−

**Strengths.** **Top-decile test discipline where it gates**: the pure suite (~1,621 `@Test`) is Truth-heavy (2,539 `assertThat` vs 269 `assertNotNull`), with six source-scanning **drift tests**, issue-pinned regression classes, and a real-dictionary E2E harness. Serious reproducible-build engineering (deterministic aapt/compose flags, PNG-crunch off, vcsInfo disabled) with a defensive `generateBinaryDictionaries` guard against V2→V1 downgrade. Exemplary CHANGELOG/spec/TOC discipline. Exact dependency pinning + a SHA-pinned trivy action.

**Weaknesses.** The non-functional instrumented CI + untested releases (#4). The ~19.7 GB history + broken submodule + 341-file archive (#7). Hand-maintained runner class lists orphan 46 tests (`ClipboardSearchRegexTest`'s 31 pure tests run *nowhere*). Lint triple-neutered. R8 disabled behind a fossilized comment → 48 MB unshrunk APK. 5 workflows all build on every push; "Nightly Build" has no `schedule:`. Logic-mirror tests reimplement production logic and can drift green. Stale status docs (SECURITY.md, CLAUDE.md, README minSdk). Nine artifact-integrity defects in the Python `scripts/` pipeline that builds the shipped dictionaries (spell-oracle inverts on subprocess failure; V2 builder truncates multi-token lines; non-reproducible langpack ZIPs).

**Subscores:** test quality 7.5 · build system 6.5 · CI 4.5 · repo hygiene 3 · documentation 6.5.

---

## Blind-Spot Findings (areas the standard six dimensions miss)

| Sev | Area | Finding | Evidence |
|:---:|---|---|---|
| P2 | Dictionary build | Spell-oracle inverts on aspell subprocess failure → empty `bad` set → every token treated as correctly spelled, poisoning the shipped `en_enhanced` dict. | `scripts/build_en_wordlist.py:121-128` |
| P2 | Dictionary build | V2 builder silently keeps only `parts[0]` when the last token isn't `isdigit()` (floats, multi-word) and substitutes a default frequency. | `scripts/build_dictionary.py:106-108` |
| P2 | Reproducibility | Langpack ZIPs embed source mtimes; `en_words.txt` embeds today's date → byte-differing artifacts on rebuild, defeating the reproducible-build effort. | `scripts/build_langpack.py:192-209`, `build_en_wordlist.py:386` |
| P2 | Web demo | Entire decode pipeline is **forked** into JS with no shared source of truth, and has **already drifted**: `web_demo/en_enhanced.json` is 52,042 words / 945 KB vs the APK's 98,140 / 1.83 MB (verified), while the page still claims parity. | `web_demo/demo/index.html`, `web_demo/en_enhanced.json` |
| P3 | i18n | Neural key-position path lowercases with default locale (`toLowerCase()`), so 'I'→'ı' on Turkish devices mislocates the I-key. | `Keyboard2View.kt:1049` |
| P3 | Packaging | Version-code formula `MAJOR*10000 + MINOR*100 + PATCH` (then `*10+abi`) collides once MINOR or PATCH reaches 100 — latent monotonicity break. | `build.gradle:88` |
| P3 | Build | `generate_compose_bin.py` masks codepoints > U+FFFF (`struct.pack('>H', state & 0xFFFF)`) on the JSON path. | `scripts/generate_compose_bin.py:247` |
| P3 | RTL | No `android:supportsRtl` in the manifest (verified absent) despite shipping RTL-script layouts. | `AndroidManifest.xml` |

---

## Cross-Cutting Themes

1. **Two eras, never converged.** Every dimension independently described the same split — a mature Java-lineage core (flat package, mutable `Config`, snake_case, monolithic `WordPredictor`/`Pointers`/`Keyboard2View`) versus clean, testable newer modules (`onnx/`, `backup/`, `ui/settings/`). The team clearly *can* build well; the migration just stopped at the halfway mark.

2. **Duplication is the recurring failure mode, and it has already drifted.** Two suggestion pipelines (now with *contradictory* Termux policies), two dictionary stacks, a forked web demo 46k words behind, logic-mirror tests, four theming roll-ups. Every duplicate has produced observed drift — this isn't a theoretical maintenance cost.

3. **The safety net has structural holes discipline alone doesn't cover.** The drift tests and issue-pinned regressions are excellent, but nearly half the test corpus gates nothing, releases run no tests, and the repo is too large to clone. Good tests that never run are documentation, not verification.

4. **Documentation overstates readiness.** "Development 100% complete / Production Ready (Grade A) / Pure ONNX NO fallbacks / NO CGR" — this audit found a P0 leak, silent fallbacks, ~3,900 lines of live CGR-adjacent dead code, phantom multi-language models, and a package tree in CLAUDE.md that doesn't exist. The docs are extensive and mostly excellent; the top-line *status* claims are the weakest, least-accurate part of the project.

5. **Privacy is designed-in but has holes.** The backup-exclusion rules and no-INTERNET posture are exemplary and undercut by exactly two things: one unauthenticated exported component and ungated user-text logs that ship in release. A small, fixable delta between "privacy-first design" and "privacy-first behavior."

---

## Recommended Remediation Order

**Tier 1 — ship-blockers, mostly hours, near-zero risk (do first):**
1. Close the encoder `Result`/`memory` every swipe + move greedy `result.close()` into `finally` (#1). Highest value-per-hour in the whole list.
2. Log the swallowed `InputCoordinator` commit exception (#5 secondary) — one line turns silent corruption into a diagnosable report.
3. Gate the user-text logs behind `ENABLE_VERBOSE_LOGGING` (#10).
4. Null-safe `globalConfig()`; fix the CLAUDE.md package tree + the "Grade A" status claims + stale SECURITY.md/README strings.

**Tier 2 — real security/robustness (cheap–moderate):**
5. Lock down or de-export `BackupRestoreActivity` + drop `exported` on the 11 internal activities (#2) — own PR with an instrumented external-caller-rejection test.
6. Zip-slip canonical guard on media import + bounded archive reads; `content://` model-load fix (`readBytes()`).
7. Synchronize predict/cleanup + normalize to `applicationContext` (#8); replace the busy-wait with a latch/queue (#6).
8. Fix the instrumented CI (run `connectedDebugAndroidTest`, correct package, drop `|| true`) and add a test job to the release pipeline (#4).

**Tier 3 — real user benefit, larger:**
9. Re-enable R8 with a full instrumented soak (halves the ~48 MB APK, strips the ~3,900 dead LOC automatically); then delete the dead source for clarity.
10. Rewrite git history to shed the ~19.7 GB (#7); add LFS for models; fix the broken submodule.
11. `ExploreByTouchHelper` for `Keyboard2View` + wire up or remove the dead a11y toggles (#3).
12. i18n the UI strings; add `supportsRtl`; fix the Turkish-i path.

**Tier 4 — maintainability refactors (deliberate, not grade-gates):**
13. Collapse the two suggestion pipelines into one (#5). Introduce an immutable `ConfigSnapshot` for hot-path reads (#9). Hoist `SettingsActivity` state into a ViewModel. Flatten the root package. Emit a shared `constants.json` consumed by both Kotlin and the web demo; CI-enforce dictionary/model parity.

---

## Verdict

**CleverKeys is a genuinely capable, well-tested, privacy-conscious neural keyboard held back from professional-grade by a native memory leak on every swipe, an unauthenticated export surface, a completely inaccessible keyboard, a non-functional test gate, and an unfinished architectural consolidation.** The engineering *instincts* on display — the beam-search decoder, the memory-conscious tries, the drift-test culture, the SAS-1 state machine, the documented fix history — are those of a skilled engineer. The gap to B+/A− is not a rewrite; it is closing a known, well-scoped punch list, and correcting documentation that currently claims the punch list is already closed.

**Overall: C+ (rising to B− once findings #1 and #2 are fixed, and to B+ once the CI, repo-hygiene, and accessibility tail is closed).**

*This audit was produced by seven independent source-only reviewers (six blinded to prior audits, one adversarial verifier), synthesized and hand-verified on the highest-impact claims. Where it disagrees with the 2026-07-15 audit, the disagreements and their evidence are documented in "What This Audit Changes," above.*
