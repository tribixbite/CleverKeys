# CleverKeys — Remediation Action Plan (Path to Straight-A)

**Date:** 2026-07-15 (verification pass 2026-07-16)
**Baseline audit:** [`2026-07-15-code-quality-audit.md`](./2026-07-15-code-quality-audit.md) — overall **B− / C+**
**Version:** 1.5.0 · **Commit at verification:** `b2a25742a`
**Status:** Every finding below was **independently re-verified against source** by a second adversarial pass (agents told to *refute*, not confirm). Per-dimension code-level fixes with before→after diffs live in [`remediation/`](./remediation/):

| Dimension | Detail doc | Current | Target | Effort to A |
|---|---|:---:|:---:|:---:|
| Neural & dictionary | [`1-neural-pipeline.md`](./remediation/1-neural-pipeline.md) | B− | A | 15–20 h |
| Data & security | [`2-data-security.md`](./remediation/2-data-security.md) | C+ | A | 9–11 h |
| Core IME | [`3-core-ime.md`](./remediation/3-core-ime.md) | C+ | A | 6.5–8.5 d |
| UI layer | [`4-ui-layer.md`](./remediation/4-ui-layer.md) | B− | A | 8–12 d |
| Architecture | [`5-architecture.md`](./remediation/5-architecture.md) | C+ | A | 5–6 d |
| Engineering practices | [`6-eng-practices.md`](./remediation/6-eng-practices.md) | B+ | A | 2–3 d (crit path) |

**Total to straight-A: ~4–6 engineer-weeks**, structured so the app compiles and ships after every increment. But **"straight-A" is a proxy, not the goal** — several grade-gating items (full pipeline unification, ConfigSnapshot, package reorg, TalkBack) are *maintainability or minority-inclusion* work with little or no impact on the typical user. The re-rating below separates **"real defects worth fixing"** from **"grade polish."** The genuinely-worth-doing set (Tiers 1–2) is **~2–3 days**.

---

## Realistic Severity Re-rating (authoritative)

The baseline audit and the per-dimension docs use P0–P3 labels that, on review, **over-stated several severities**. This table is the corrected, realistic assessment — *when* each defect actually bites a user, *how often*, and the honest impact. **Where this table disagrees with a P0/P1 label elsewhere in the audit set, this table wins.**

| # | Finding | Orig. | **Realistic** | When / how often it bites | User impact | Fix cost |
|---|---|:---:|:---:|---|---|:---:|
| A | ONNX encoder `memory`+`Result` leak | P0 | **P1** | 256 KB **every swipe**; accumulates only within one IME process lifetime. Heavy swipers in a long-lived process (IMEs can live for days) reach hundreds of MB; typical users' process is recycled (app update, memory pressure, reboot) long before it matters. | Gradual native-heap growth → earlier OOM-kill of the IME (warm-state loss → reload jank); native OOM only in extreme long sessions. No crash/data-loss for typical use. **Fix anyway — it's ~3 lines and zero-risk.** | 3–4 h |
| B | Exported `BackupRestoreActivity` (no auth) | P1/P0 | **Medium** (High impact × **Low** likelihood) | Only when a **malicious app is already installed and specifically targets CleverKeys** (a niche F-Droid keyboard). No random malware does this. Zero-permission is the notable part (bypasses the permission model). | If exploited: clipboard history (may hold copied passwords/OTPs) + learned dictionary exfiltrated to a local app; inbound `json_base64` can silently overwrite settings/dictionary. Standard CWE-926/200 — the kind of thing an F-Droid/security reviewer flags. | 3–4 h |
| C | **Swipe path skips password guard** *(NEW)* | — | **P3 (UX correctness), not privacy** | `IC.handleSwipeTyping` has **no password-mode check** (verified: early-outs are only `swipe_typing_enabled`/layout/predictor); the only guard (`SuggestionBar:233`) suppresses *display*. **De-escalated by tracing:** swipe ML persistence is gated behind `config.swipe_debug_detailed_logging` (`InputCoordinator.kt:617,649`), **off by default** → no password-swipe data is persisted for normal users; the swipe path also has no `addUserWord` (no dictionary learning). The residual open question is only whether the predicted word is **auto-inserted** into the field when `swipe_on_password_fields` is off (default) — a **usability** concern (a wrong masked word), not exfiltration/persistence. | For normal users: at most a predicted word auto-inserted into a password field they're typing in (their own field, not leaked/stored). Worth a 1-test confirmation + a cheap defensive `if (isPasswordMode && !swipe_on_password_fields) return` in `handleSwipeTyping`. | 1 h verify + fix |
| D | Main-thread busy-wait (cold swipe) | P1 | **P2** | At most **once per IME process start**, and only if the user swipes *before* ONNX init finishes (init is typically <1 s on modern devices). Not every session. | A one-time hitch on the first swipe (~100–800 ms typical); the 5 s→ANR case needs a genuinely slow device with cold storage. Not "routinely ANRs." | ½ d |
| E | Silent `catch` around swipe commit | P1 | **P2** | Only when an InputConnection op throws mid-commit (rare) on the swipe path. | State desync between tracker and editor, silently — but rare. The real cost is *undiagnosable* bug reports. One-line fix. | 5 min |
| F | predict/cleanup race + Activity-context | P1 | **P2** | `cleanup()` runs on service destroy (keyboard disabled/switched/app-update); race needs a swipe firing at that exact moment. Rare. | A crash in an already-dying background process (usually invisible); Activity-context pin only if a calibration/settings Activity wins the init race. | 4–6 h |
| G | Config `!!` + global mutable | P1 | **P3 (crash) / maintainability** | The `!!` crash is **latent** — no observed pre-init consumer today. The global-mutable cost is testability, not user-facing. | None observed for users; the win is unit-isolation + a diagnosable error instead of a bare NPE. Do the 1 h null-safe fix; treat ConfigSnapshot as maintainability. | 1 h + refactor |
| H | Ungated user-text logs | P2 | **P3** | `READ_LOGS` is privileged since Android 4.1 — **other apps cannot read logcat**. Exposure = ADB/USB-debug access, or a user sharing a captured bug report. | Not a cross-app/remote threat. Real only for shared logs / on-device debugging. Cheap to gate; good practice. | 1.5 h |
| I | Zip-slip in backup import | P2 | **Low** | Needs the user to be socially-engineered into importing a **malicious backup ZIP**. | Arbitrary write **inside the app's own sandbox** (can't escape to other apps on modern Android) → DB/pref corruption. Cheap guard, reuse `GifPackManager`'s. | 1.5 h |
| J | TalkBack: keyboard invisible | P1 | **High for TalkBack users / none for others** | Every interaction, but **only for users running a screen reader**. | Total for that minority (keyboard unusable with TalkBack); zero for everyone else. Inclusion/legal-in-some-contexts, not a crash/security issue. | 2–3 d |
| K | onDraw per-frame allocations | P2 | **P3** | During redraws (continuous while a swipe trail animates). Common case = user has **no** custom short-swipe mappings → ~40 small string allocs/frame, cheap `filter` over an empty map. | Sub-ms/frame; GC pressure only; possible occasional dropped frame on **low-end** devices. Imperceptible on modern hardware. | ½ d |
| L | SuggestionBar rebuild/keystroke | P2 | **P3** | Each keystroke where suggestions change (dedup-guarded when unchanged). | ~1–2 ms/keystroke recreating 3–5 TextViews; imperceptible on modern devices, matters for low-end input latency + GC/battery. | 1 d |
| M | Pipeline duplication (full unification) | P1 | **Maintainability** (except item C) | The *code* is duplicated (700 ln, drift risk); the only **user-visible** divergences are the password guard (→ item C, fix surgically) and minor UX (swipe lacks possessives; occasional prompt clobber). | Fixing the whole thing is a 4–6 d **maintainability** refactor. The user-impact slice (C) is 1–2 h standalone. **Don't gate real quality on the full merge.** | 4–6 d |
| N | Dead code (~3 k LOC) | P2 | **Maintainability** | Never runs; **R8 (once re-enabled) strips it from the APK automatically**. | Zero user impact; source deletion is clarity, not size. | 2–3 h |
| O | R8 disabled → 63–66 MB APK | P2 | **P2 (real user benefit)** | Every install/update. | ~2× the download/storage a shrunk APK would need (~35–45 MB est.). Real benefit; **real risk** (R8 stripping a reflection-reached ONNX/IME class → runtime crash) → needs full instrumented soak before shipping. | 1 d |
| P | 76 NewApi lint entries | P2 | **Low** | Only on API 21–25 (Android 5–6) devices — a **<2–3 %, shrinking** population the README already says is unsupported ("API 26+"). | **Better fix: raise `minSdk` to 26** (matches README + real user base) — deletes the whole NewApi category *and* the doc mismatch in one move, losing only already-unsupported ancient devices. | 1 h (raise minSdk) |
| Q | Orphaned/mirror tests, lint gate | P2 | **P3 (process)** | No direct user impact; a gap in the regression net (31 clipboard-search tests in no gating path; auto-space test mirrors rotted line-refs). | Protects against *future* regressions. Cheap. | 4–6 h |

**Net:** nothing here is a "drop everything, the app is on fire" P0. The two loudest original findings are a **cheap-to-fix slow leak** (A) and a **low-likelihood targeted security bug** (B). Item C looked like it might be an everyday-user privacy bug, but tracing showed the persistence is debug-gated — so it too is minor. The realistic worst thing in the whole set is the **security surface (B)**, and its likelihood is low. This is a healthy codebase with a cheap, well-scoped punch list — not a fire drill.

---

## Reordered Work Plan (by realistic impact ÷ cost)

This supersedes the Phase 0–3 ordering below (kept for the detailed per-item content). Do the tiers in order; within a tier, order is flexible.

**Tier 1 — Fix now (hours, near-zero risk, real or cheap-correct):**
1. Item A — free the encoder `Result`/`memory` (§0.1). Real 256 KB/swipe leak, ~3-line fix. *Highest value-per-hour in the whole plan.*
2. Item E — log the swallowed IC exception (§0.4). One line; turns silent corruption into a diagnosable log.
3. §1.5 model-truncation, §1.11 null-safe `globalConfig`, §1.10 doc-drift + `Grade A`→real-grade, §1.13 stale strings, §1.12 delete stray artifacts. Cheap correctness/honesty.
4. Item C — add the defensive `if (isPasswordMode && !swipe_on_password_fields) return` to `IC.handleSwipeTyping` (mirrors `SuggestionHandler:289`); confirm with one manual swipe-in-password-field check. Cheap, closes the residual UX gap.

**Tier 2 — Real security/robustness (cheap–moderate):**
5. Item B — lock down exported `BackupRestoreActivity` (§0.2). The real security bug; own PR + instrumented test.
6. Item H — gate the PII logs (§1.4); Item I — zip-slip guard + bounded reads (§1.3, §1.6); §1.7 drop `exported` on 11 internal activities.
7. Item F — synchronize predict/cleanup + `applicationContext` (§0.5); Item D — `@Volatile` + latch for the busy-wait (§0.3).
8. Item Q — orphaned-test + drift-check + logic-mirror seam + lint-gate freeze (§3.3, §3.4, §3.2-phase1).

**Tier 3 — Real user benefit, larger/needs care:**
9. Item O — re-enable R8 + full instrumented soak (§3.1). Halves the APK.
10. Item P — raise `minSdk` to 26 (kills NewApi + the README mismatch).
11. Item N — delete dead code (§1.1, §1.2). Item K/L — onDraw cache + SuggestionBar recycling (§3.6, §3.5) for low-end smoothness.
12. Item J — TalkBack `ExploreByTouchHelper` (§2B). Prioritize iff accessibility is a project goal.

**Tier 4 — Maintainability refactors (deliberate; *not* prerequisites for real quality):**
13. Item M — full pipeline unification (§2A), *after* the surgical C fix. Item G — ConfigSnapshot (§2C). SettingsActivity state-hoisting (§2D). Package reorg / composition root / interfaces / theming (§3.7–3.10, arch R4–R6).

**Bottom line:** Tiers 1–2 (~2–3 days) fix everything with real or cheap-correct value and take the overall grade to a defensible **B+/A−**. Tiers 3–4 are genuine improvements but are *polish and inclusion*, not defect-fixing — pursue them for their own merits, not to chase a letter grade.

---

## Verification Outcome — Corrections to the Baseline Audit

The re-check **confirmed both critical findings and sharpened them**, and **corrected or downgraded eight softer claims**. The plan below is built on the corrected facts.

### Critical findings — confirmed & sharpened
- **P0 native leak (neural):** confirmed. The leaked object is the encoder's `OrtSession.Result` which owns the `memory` tensor `[1, ≤250, 256]` f32 ≈ **256 KB per swipe**, off-heap/GC-invisible. The greedy path *additionally* leaks its per-step `Result` on the EOS `break`. Fix is ownership-transfer + `finally`.
- **P1 export exploit (security):** confirmed **exploitable** with a concrete zero-permission attacker intent (`EXPORT_CLIPBOARD` → attacker `content://` provider). Exfil target is a local component (no INTERNET), still full clipboard/dictionary/settings PII compromise; inbound `json_base64` injection needs no URI at all.

### Claims corrected or downgraded (do NOT spend effort on these as stated)
| Baseline claim | Correction |
|---|---|
| "regex recompiled per candidate" in beam loop | **FALSE** — no regex exists in `BeamSearchEngine`; the per-char cost is a `String` alloc. |
| `NEURAL_BEAM_SCORE_GAP` 80 vs 8 = live 10× bug | **Doc drift only** — 8.0f is a never-used ctor default; prod always injects 80. |
| Weak 32-bit dedup hash → data loss | **Cannot false-merge** — dedup query also matches full content; correctness nit, not a bug. |
| `moveCursorSel` infinite loop | **Unreachable** — callers gate on `d != 0`; latent defect, one-line guard. |
| ~2,300 dead LOC | **Undercount** — the unreachable CGR/template/trace cluster + onnx orphans ≈ **2,956 LOC**. |
| Config "~150 fields / 21+ consumers / 1 unwrap" | **157** `@JvmField var` / **28** static consumers / **2** `!!` sites. |
| "19 files ≥1000 ln, interface-free monsters" | **18** files; `Keyboard2View`/`Pointers` *do* implement interfaces (only `WordPredictor`/`OptimizedVocabulary` don't). |
| Release APK "48–52 MB"; lint "973"; "9 Clipboard files" | **63–66 MB**; **972** issues; **11** Clipboard files. |
| Release keystore risk | **Already safe** — untracked, `.gitignore`-covered. No action needed. |
| `DictionaryManager` dead | **Live** (user-word tracking); only its `getPredictions()` is dead. |

### Which pipeline is live (resolves the core-IME ambiguity)
Both `SuggestionHandler` and `InputCoordinator` run in production, **split by trigger**: taps + all typing/backspace/delete → `SuggestionHandler`; swipe auto-insert → `InputCoordinator` (its own `onSuggestionSelected`). The swipe path therefore silently lacks SH's possessive augmentation, password guard, autocorrect-undo, dict-add handling, prompt guard, and error logging. Unification keeps **SuggestionHandler** and folds swipe into it.

---

## Phase 0 — Ship-Blockers (P0/P1) · ~2–4 days

These are the grade-gating defects and the only items that can cause user-visible failure (OOM over a long session, PII exfiltration, ANR, silent text corruption). Do first, in this order.

| # | Item | Files (file:line) | Effort | Detail |
|---|---|---|:---:|---|
| 0.1 | **[P0] Free encoder `memory` tensor + Result every swipe** — make `EncoderResult : AutoCloseable` owning the `Result`; close it in `predict()` `finally`; move greedy `result.close()` into a `finally` so it fires on the EOS break. | `EncoderWrapper.kt:88-121`, `SwipePredictorOrchestrator.kt:381-431`, `GreedySearchEngine.kt:66-92` | 3–4 h | [neural R1] |
| 0.2 | **[P1] Lock down `BackupRestoreActivity`** — add `signature`-level `permission` on the activity + in-`onCreate` caller check; strip the MAIN filter (inline UI lives in SettingsActivity). *Option B* (drop `exported`, require in-app SAF) if Termux automation is expendable. | `AndroidManifest.xml:140-157`, `BackupRestoreActivity.kt:67-132`, `BackupRestoreManager.kt:97` | 3–4 h | [security R1] |
| 0.3 | **[P1] Remove main-thread busy-wait** — mark `neuralEngine` `@Volatile`; replace the `Thread.sleep(50)`×100 poll with a `CountDownLatch.await(5s)`; ideally enqueue the swipe instead of blocking. | `PredictionCoordinator.kt:44,222-253` | ½ d | [core R-3] |
| 0.4 | **[P1] Log the swallowed InputConnection exception** — one-liner `Log.e` mirroring SH; stops silent swipe-commit state corruption. | `InputCoordinator.kt:869-871` | 5 min | [core R-4] |
| 0.5 | **[P1] Synchronize predict/cleanup + normalize context** — `inferenceLock` guarding both; capture sessions into locals (kills the `!!` TOCTOU); `getInstance(context.applicationContext)`. | `SwipePredictorOrchestrator.kt:30,45-49,382-431,710-716` | 4–6 h | [neural R2] |
| 0.6 | **[P1] Make failure modes honest** — `NeuralSwipeTypingEngine.initialize()` set `initialized=false` on catch (allow retry); keep empty-return but record `lastError`; rename the postproc "fallback" branch. | `NeuralSwipeTypingEngine.kt:79-84`, `SwipePredictorOrchestrator.kt:472-476`, `PredictionPostProcessor.kt:50-59` | 2 h | [neural R3] |

**Phase 0 tests:** `EncoderWrapperLeakTest` (close-count), `NeuralMemoryLeakInstrumentedTest` (500 predictions, native-heap delta < few MB), `BackupRestoreActivityInstrumentedTest` (external-caller rejection + merged-manifest permission assertion), `PredictionCoordinatorInitTest` (no busy-loop), `OrchestratorConcurrencyTest`.

**Exit criteria:** no native-heap growth over 500 swipes; external `am start EXPORT_CLIPBOARD` rejected; no >200 ms main-thread block on cold-swipe (Choreographer). → security **A−**, neural correctness un-blocked.

---

## Phase 1 — Quick Wins & Hygiene (P2/P3, low-risk) · ~3–4 days

High value-per-hour, low blast-radius. Parallelizable; no dependency on Phase 2.

| # | Item | Files | Effort | Detail |
|---|---|---|:---:|---|
| 1.1 | **Delete ~2,956 LOC dead code** — CGR + WordGestureTemplateGenerator + ComprehensiveTraceAnalyzer + TemplateBrowserActivity cluster (unreachable; not in manifest), `onnx/MemoryPool`, `BroadcastSupport`, `SessionConfigurator`, `DecoderWrapper` (+ its construction), `DictionaryManager.getPredictions()`, dead `DIVERSITY_LAMBDA`/`parentBeam`. Delete their tests too. | multiple | 2–3 h | [neural R4] |
| 1.2 | **Delete InputCoordinator dead code** — `handleRegularTyping`/`handleBackspace`/`updatePredictionsForCurrentWord`/`calculateDynamicKeyboardHeight` (zero callers; removes a stale divergent autocorrect impl). Do before the unification. | `InputCoordinator.kt:501-533,885-975,1113-1145` | ½ d | [core R-2] |
| 1.3 | **Zip-slip canonical guard** — centralize a traversal check in `ClipboardMediaManager.getMediaFile` (mirror `GifPackManager`); skip rejected entries. | `ClipboardMediaManager.kt:217`, `BackupRestoreManager.kt:902-910,1301-1306` | 1.5 h | [security R2] |
| 1.4 | **Gate/redact user-text logs** — wrap the decoded-word, selected-text, and clipboard-prefix logs in `BuildConfig.ENABLE_VERBOSE_LOGGING`, else log length only. DRY via a helper. | `GreedySearchEngine.kt:116`, `Keyboard2View.kt:793,826`, `ClipboardDatabase.kt` (10 sites) | 1.5 h | [security R3] |
| 1.5 | **content:// model truncation** — replace `ByteArray(stream.available())` with `stream.readBytes()`. | `ModelLoader.kt:146-155` | 30 min | [neural R5] |
| 1.6 | **Bounded archive reads** — `readBoundedBytes` (32 MB cap), base64 size guard, `MAX_IMPORT_ENTRIES` cap. | `BackupRestoreManager.kt:899,1271,1292-1298`, `BackupRestoreActivity.kt:123`, `ClipboardDatabase.kt:1400` | 1.5 h | [security R4] |
| 1.7 | **Drop `exported` on 11 internal activities** — keep only `SettingsActivity` + `LauncherActivity`. | `AndroidManifest.xml` (11 activities) | 1 h | [security R5] |
| 1.8 | **Executor + coroutine-scope lifecycle** — add `shutdown()` to IC/SH executors wired into `CleanupHandler`; convert orphan `CoroutineScope(Dispatchers.IO)` to cancellable member scopes. | `InputCoordinator.kt:109`, `SuggestionHandler.kt:167`, `Pointers.kt:42`, `EmojiKeywordIndex.kt:48`, `CleanupHandler.kt` | ½ d | [core R-5, R-6] |
| 1.9 | **Latent-hang + no-op-filter cleanup** — `if (d==0) return` guard in `moveCursorSel`; implement or delete `isLikelyNoise` (no silent always-false stub). | `KeyEventHandler.kt:805-813`, `ImprovedSwipeGestureRecognizer.kt:384-388` | ½ d | [core R-8, R-9] |
| 1.10 | **Fix CLAUDE.md doc drift** — replace the fictional `keyboard2/core/neural/...` tree with the real `cleverkeys/` package map; correct the stale "Grade A / 2026-03-26" status. | `CLAUDE.md:11,88-100` | 15 min | [arch R1] |
| 1.11 | **Null-safe `globalConfig()`** — add `globalConfigOrNull()`; replace the two `!!` with a diagnostic `error(...)`; migrate early consumers. | `Config.kt:1183,1186` | 1 h | [arch R2] |
| 1.12 | **Delete stray tracked artifacts** — `git rm -r archive/ remote_v16/ remote_v19_all/ remote_v20_all/` (17% of tracked files) + `.gitignore` them. | repo root | 20 min | [eng R6] |
| 1.13 | **Fix all stale strings** — SECURITY.md 1.0.x→1.5.x, README API 26 vs minSdk 21, fdroid CurrentVersion 1.2.8→1.5.0, CI test-count comments. | see [eng R7] Stale-String Fix List | 2–3 h | [eng R7] |

**Exit criteria:** ~3k fewer LOC; no PII in release logcat; no external-reachable settings activities; clean working tree; docs match reality.

---

## Phase 2 — Structural Refactors (the grade-defining work) · ~3–4 weeks

These four are the difference between "clears P0/P1" and "straight-A." Each is strangler-fig / test-gated; none is big-bang.

### 2A — Pipeline Unification (core IME → A) · ~4–6 days
Fold the swipe path into `SuggestionHandler`, deleting `InputCoordinator.onSuggestionSelected`. Highest leverage (removes ~700 lines of drifted duplication + the swipe/tap behavior inconsistency + the SuggestionBar race), highest risk (deletion/auto-space/Termux logic corrupts text silently if wrong). **Mandatory oracle-test suite first.** 7 test-gated, independently-committable steps, feature-flagged during the reroute — see [core R-1]. Preserves from SH: possessives, password mode, autocorrect-undo, dict/exact-add, prompt guard, Termux branches. Ports in from IC: shift/caps-lock-at-swipe-start, ML capture via `MLDataCollector`. Termux "delete for all apps" is a **separate, tested decision** — do not silently inherit.

### 2B — TalkBack Accessibility (UI → A) · ~2–3 days
Implement `ExploreByTouchHelper` (`a11y/KeyboardAccessibilityHelper.kt`): one virtual view per visible key, IDs from a shared `computeKeyRects()` geometry walk extracted from `onDraw`/`getKeyAtPosition` (also de-dupes existing hit-test math), `describe(KeyValue)` labeller (pure/testable), `ACTION_CLICK` → existing `key_down`/`key_up`. **Gated on `isTouchExplorationEnabled`** so the swipe fast-path is untouched when TalkBack is off. Full skeleton in [ui R1]. This is the single true grade-gate for UI.

### 2C — Config Immutability (architecture → A) · ~1.5–2 days
Layer an immutable `ConfigSnapshot` (prefs/) read by the four hot-path classes (`Gesture`, `Pointers`, `Keyboard2View`, `GestureClassifier`), rebuilt in `Config.refresh()`; keep mutable `Config` as the write side. Migrate file-by-file. **Progress metric:** `rg -l 'Config.globalConfig()' src/main/kotlin | wc -l` drops from **28** → ≤10 (hot-path files at 0). No `Config` freeze, no big-bang. See [arch R3].

### 2D — SettingsActivity State-Hoisting (UI → A) · ~3–5 days
Move the **143** `mutableStateOf` fields off the Activity into cohesive `SettingsViewModel` sub-states; make composables **top-level** functions with params (not `SettingsActivity.SettingsScreen()` receiver extensions); fix the composition-time side-effect (`mainScrollState=` → `SideEffect{}`/`LaunchedEffect`); add `@Preview`s (currently **2** app-wide). Per-section, one `*Section.kt` per commit, Activity fields as a thin façade until the last migrates. `SettingsSearchTest` is the regression tripwire. See [ui R4].

---

## Phase 3 — Polish Tail (P2/P3) · ~1 week, mostly parallel

| # | Item | Effort | Detail |
|---|---|:---:|---|
| 3.1 | **Re-enable R8** (`minifyEnabled true` + dormant `proguard-rules.pro`) — validate reflection/ONNX keeps, run full ew-cli suite, verify 2× build DEX determinism. APK 63 MB → ~35–45 MB. | 1 d | [eng R4] |
| 3.2 | **Lint gate + baseline freeze** (drop `continue-on-error`, `abortOnError true`), then burn down **76 NewApi** first (real crash risk on API 21–25). | 1 h + 1–2 d | [eng R3] |
| 3.3 | **Orphaned-test fix + drift-check** — add `ClipboardSearchRegexTest` (31 orphaned @Tests) to `pureTestClasses`; add `TestRunnerListDriftTest` (source-scan, mirrors `SettingsDefaultsDriftTest`); make `build.yml` run `runPureTests` not the non-gating `test`. | 2–3 h | [eng R2] |
| 3.4 | **Logic-mirror test → real seam** — extract `SmartAutoSpace.decideTrailingSpace(...)`, have production and `AutoSpaceLogicTest` both call it (kills the already-rotted line-ref mirror). | 2–4 h | [eng R5] |
| 3.5 | **SuggestionBar recycling + de-stringify + i18n** — pool TextViews (stop per-keystroke rebuild), replace `dict_add:`/`exact_add:` string protocol with a `sealed interface Suggestion`, move "Add to dictionary?" to `strings.xml`. | 1–1.5 d | [ui R3] |
| 3.6 | **onDraw allocation cache** — pre-index `mappingCache` as `Map<keyCode, Map<Direction, Mapping>>`, cache lowercased key codes, early-out when no mappings. | ½ d | [ui R2] |
| 3.7 | **Unify theming** — one `CleverKeysTheme` composable replacing the per-activity `darkColorScheme()/lightColorScheme()` re-rolls. | ½–1 d | [ui R5] |
| 3.8 | **Package reorg** — dir-only `git mv` (package stays `tribixbite.cleverkeys`, zero import churn): 11 Clipboard→`clipboard/`, 6 Emoji→`emoji/`, 15 Activities→`ui/settings`+`activities/`. Flat root 158 → <100. | 1 d | [arch R4] |
| 3.9 | **Composition-root object** — replace the 6 `*Initializer` factories (801 ln) with one `KeyboardComponentGraph` (`by lazy`, no DI lib); keep the 4 Bridges (genuine adapters). | 1 d | [arch R5] |
| 3.10 | **Extract `Predictor`/`Vocabulary` interfaces** — for the two no-interface monsters, unblocking test doubles (interface-first, don't split bodies yet). | 1 d | [arch R6] |
| 3.11 | **Beam scoring consistency** — unify ranking vs confidence length basis (gate on calibration A/B); align score-gap docs; optional hot-loop alloc reuse. | 3–5 h | [neural R6] |
| 3.12 | **CI dedup + SHA-pin actions** — merge `ci.yml`/`build.yml`; pin `uses:` to SHAs + Dependabot. | 3–5 h | [eng R7, R8] |

---

## Definition of Done — per dimension

- **Neural → A:** no native-heap growth over a long session (R1); predict/cleanup race-free (R2); honest failure modes (R3); dead code gone (R4); model-load robust (R5). *[R1+R2 are the gates.]*
- **Data & security → A:** no external caller can export/import (R1); no path traversal (R2); no PII in release logs (R3); bounded imports (R4); minimal export surface (R5). *[R1 is the gate.]*
- **Core IME → A:** one prediction pipeline, swipe==tap behavior (2A); no main-thread block (0.3); no silent catches (0.4); clean lifecycle (1.8). *[2A + 0.3 are the gates.]*
- **UI → A:** keyboard fully navigable by TalkBack (2B); Settings state in ViewModel with previews (2D); no per-frame allocs (3.6); recycled SuggestionBar (3.5); one theme (3.7). *[2B is the gate.]*
- **Architecture → A:** hot paths on immutable `ConfigSnapshot`, ≤10 static Config consumers (2C); flat root <100 (3.8); composition root (3.9); testable monsters (3.10); docs accurate (1.10). *[2C is the gate.]*
- **Eng practices → A:** every @Test in a gating runner + drift-check (3.3); no logic-mirror tests (3.4); lint gates (3.2); R8 on (3.1); clean repo/docs (1.12, 1.13). *[3.3+3.4+3.2+3.1 are the gates.]*

---

## Sequencing & Dependencies

```
Phase 0 (2–4 d) ──┬─ 0.1 leak ─┐
                  ├─ 0.2 export │ independent, do in parallel
                  ├─ 0.3 wait   │
                  ├─ 0.4 catch  │
                  ├─ 0.5 sync   │  (0.5 shares files with 0.1 → same PR)
                  └─ 0.6 honest ┘
Phase 1 (3–4 d) ── all independent of each other and of Phase 0
                   (1.2 dead-IC-code MUST precede 2A)
Phase 2:
   2A pipeline   ← needs 1.2 done; blocks nothing else
   2B talkback   ← independent
   2C config     ← independent; 3.6 easier after
   2D settings   ← independent
Phase 3 ── 3.1 R8 after code churn settles; rest parallel
```

**Critical path to A− (all P0/P1 + gating quick-wins):** Phase 0 + Phase 1 essentials + 2A + 2B ≈ **~2 weeks**.
**Critical path to straight-A:** + 2C + 2D + Phase 3 gates ≈ **~4–6 weeks** total, one engineer.

## Recommended First PR (½ day, unblocks the grade conversation)
Bundle the near-zero-risk, high-signal items: **0.1** (P0 leak), **0.4** (log the catch), **1.5** (model truncation), **1.10** (doc drift), **1.11** (null-safe config), **1.12** (delete artifacts). One reviewable PR that kills the P0, two latent crash vectors, and the most-misleading docs — then tackle **0.2** (export lockdown) as its own security-focused PR with the instrumented test.
