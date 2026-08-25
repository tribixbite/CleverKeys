# Post-v1.5 remediation verification and residual plan

**Verification date:** 2026-08-25
**Verified revision:** `6b3b8bb9` (HEAD of `main`; matches `github/main` and `origin/main`)
**Inputs verified:** [2026-08-23-v1.5-delta-audit.md](2026-08-23-v1.5-delta-audit.md) (findings CK-150-001…018) and [2026-08-23-v1.5-delta-remediation.md](2026-08-23-v1.5-delta-remediation.md) (claimed resolutions)
**Method:** five independent source-level verifications (backup, CI/release, CTC engine, accessibility/i18n/keystore, metadata/misc), plus a live gate run at HEAD: `runPureTests` **OK (1,757 tests)**, `runMockTests` **OK (292 tests)**, BUILD SUCCESSFUL, output quiet (no `boom` stack trace, no ByteBuddy `FoldStateTracker` warning).

## 1. Verdict summary

**No original audit entry is removed** — all 18 were valid at audit time. Verification of the
remediation produced: 13 findings **resolved as claimed**, 4 **resolved with residuals**, and
**1 remediation claim refuted outright**. Verification also **modifies** the factual basis of
two original entries (CK-150-005 test claims, CK-150-007 German scope) and **appends** new
findings CK-150-019…036 below.

| Finding | Remediation claim | Verified verdict |
|---|---|---|
| CK-150-001 backup imports | Resolved | **Resolved with residuals** — bounds/staging/rollback machinery is real and tested, but the DB-failure→media-rollback path is dead code (CK-150-019) and three secondary defects remain (CK-150-020/021/022) |
| CK-150-002 a11y CI | Resolved | **Resolved with residuals** — real instrumented run + honest naming confirmed; TalkBack/touch-exploration-ON assertion still absent (CK-150-029); `OK (0 tests)` false-green possible (CK-150-028) |
| CK-150-003 release gates | Resolved | **Partially refuted** — release.yml gating is real and blocking, but the claim "ordinary CI Trivy scan no longer has unconditional continue-on-error" is **false**: `ci.yml:114` still has it (CK-150-023). Release Trivy is also near-vacuous without a Gradle lockfile |
| CK-150-004 v1.6 metadata | Resolved | **Resolved** — 1.6.0 SSoT, byte-identical 494-byte notes, drift test registered. Minor test blind spots (CK-150-032) |
| CK-150-005 fuzzy rescue | Implemented | **Resolved with residuals** — implementation and wiring confirmed; the claimed "rank-one preservation" pure test does **not exist** (`applyFuzzyRescue` is private and untested), and a score-clamp inversion can push a rescued word above rank-1's score (CK-150-025) |
| CK-150-006 secondary language | Resolved | **Resolved with residuals** — one encoder pass, per-language decode, rank-only merge, 2-slot LRU all confirmed; language identity is dropped after merge, leaking English possessive augmentation onto secondary-language words (CK-150-024); dual-language latency unmeasured (CK-150-026) |
| CK-150-007 `ß/œ/æ/ø` | Implemented | **Resolved, scope corrected** — code+tests confirmed, but the original entry's German claim was factually wrong: see §2 |
| CK-150-008 TalkBack regions | Resolved | **Resolved with residuals** — dead strips fixed with `keyAt`-parity ownership; "non-overlapping full tiling" wording is overstated and parity is not proven by a dense sweep (CK-150-027) |
| CK-150-009 context rescoring | Safely resolved | **Resolved** — default off (`Config.kt:334`), replay-fidelity work committed in `6a460102` (aliases at `CtcReplayEngine.kt:207-216`, rescue mirror at `:56,74-107`) |
| CK-150-010 localization | Resolved | **Resolved** — 13 names in all 21 locales with genuine translations, real `<plurals>` with correct CLDR categories, suppressions gone, drift test present. Stale comments remain and new English-only strings were introduced elsewhere (CK-150-030) |
| CK-150-011 passphrase store | Resolved | **Resolved** — fail-closed on API 23+ (`BackupPassphraseStore.kt:145-155`), `ProtectionState` surfaced in UI (`BackupPasswordBlock.kt:66-73`) and headless (`BackupRestoreActivity.kt:211-218`), mock + instrumented tests present. Minor: `commit()==false` branch untested |
| CK-150-012 NUL bytes | Resolved | **Resolved** — both files byte-scanned clean; `SourceTextHygieneTest` present |
| CK-150-013 docs SSOT | Resolved | **Resolved** — `memory/todo.md` 17 lines; ledger archived at `docs/history/task-ledgers/2026-08-23-pre-audit-ledger.md`; ROADMAP/VERSIONING corrected |
| CK-150-014 web demo | Resolved | **Resolved** — deploy runs parity/smoke/`cmp`/browser harness; `run_browser_tests.mjs:124` fails on `pageErrors`; data-URI favicon present (`rel="icon"`, so a literal `favicon` grep misses it) |
| CK-150-015 root exports | Resolved | **Resolved** — no `2026-06-10-*` files at root; release preflight rejects dirty trees (`release.yml:30`) |
| CK-150-016 lint | Resolved | **Resolved** — `Locale.ROOT` in `MemoryProbe.kt:216,220`, targeted `@SuppressLint("PrivateApi")` at `:91`, 8 dead strings removed repo-wide, `mutableIntStateOf` at `SettingsActivity.kt:256,260` |
| CK-150-017 whitespace | Resolved | **Resolved** — `git diff --check v1.5.0..HEAD` clean; enforced at `release.yml:31` |
| CK-150-018 test noise | Resolved | **Resolved and runtime-verified** — capturing handler in `CoroutineScopeLifecycleTest.kt:87-103`; `-XX:+EnableDynamicAgentLoading` at `build.gradle:647`; AAR→classes-jar `artifactView` at `build.gradle:686-697`. Fresh gate run at HEAD is quiet |

## 2. Corrections to the audit record (modify)

### 2a. CK-150-007 — the German `ß` claim was wrong; the fix mostly measured French

The original entry says the projection gap made "common German `ß` words" (`groß`, `Straße`,
`weiß`) unreachable. Byte-scanning the shipped assets shows **`src/main/assets/dictionaries/de_enhanced.bin`
contains no `ß` entries at all** (nor `æ`/`ø`; `œ` appears 32× in `fr_enhanced.bin` and nowhere
else meaningful). Those German words were unreachable because they are absent from the bundled
lexicon, not because of `CtcAzProjection`. Consequences:

- The projection change (`CtcAzProjection.kt:36-41`) only actually recovered the **31 French `œ`
  words** (fr untypeable 31 → 0; surfaces 37,949 → 37,958; collisions 2,020 → 2,042).
- The remediation's demand for "fresh German/Nordic held-out evaluation" is measuring a no-op for
  German. **Re-scope required release evidence to French only** (top-1/top-3 + collision check on
  the fr corpus). German/Nordic evaluation becomes relevant only after 2b.
- The `ß → ss` expansion is still correct to keep — it makes future German dictionary updates safe.

### 2b. New sub-finding from 2a: the German lexicon itself lacks `ß` vocabulary (tracked as CK-150-036)

If `groß`/`Straße`/`weiß` should be swipeable in German, the fix is in the **dictionary build
pipeline**, not the engine. That is a content decision: regenerate `de_enhanced.bin` from a source
list that preserves `ß` forms, then the (already-landed) expansion projects them. Until then, the
release notes' German support claim is honest only for the vocabulary actually shipped.

### 2c. CK-150-005 — remediation overclaim on tests

`docs/audit/2026-08-23-v1.5-delta-remediation.md:97-98` claims pure tests pin "rank-one
preservation". No such test exists: `CtcFuzzyRescueTest.kt` covers the index/distance/budget only,
and `applyFuzzyRescue` (`CtcEngineAdapter.kt:669`) is a private method on an Android class with no
pure or instrumented coverage. The `limit = 2` cap is also untested. See CK-150-025.

### 2d. CK-150-003 — remediation claim about `ci.yml` Trivy is false

`ci.yml:108-114` (the `security` job's Trivy step) still ends with `continue-on-error: true`.
Commit `bb70628a` removed `continue-on-error` from the *dependency analysis* step in
`code-quality`, not from the Trivy step the audit cited. It is the only `continue-on-error`
remaining under `.github/`. See CK-150-023.

### 2e. CK-150-008 — remediation wording overstates the partition

The doc comment at `KeyboardGeometry.kt:213` calls the accessibility rects "non-overlapping",
and the remediation implies a full-width tiling. Neither is strictly true:

- In an a/l row, keys **before** `a` get `[x, physicalRight)` while `a` gets `[0, …)` — an
  overlap resolved only because `KeyboardAccessibilityHelper.getVirtualViewAt` (`:64-68`) returns
  the **first** list match. Undocumented, untested ordering dependency.
- Rows without an a/l pair leave `[0, marginLeft)` unowned — parity-correct with `keyAt`
  returning null, but not a tiling.
- A **trailing** placeholder key leaves the right-slop band `[lastRealRight, hostWidth)` unowned;
  no test covers a trailing placeholder (only mid-row, `KeyboardGeometryTest.kt:183`).
- The dense parity sweep `computeRectsMatchKeyAtForDenseGrid` (`KeyboardGeometryTest.kt:238-255`)
  still runs against `computeKeyRects`, **not** `computeAccessibilityKeyRects`. Parity with
  `keyAt` is asserted only at 3 hand-picked centers. See CK-150-027.

## 3. New findings (append)

Severity uses the audit's scale. **CK-150-019 is the only new release blocker.**

### P1

- **CK-150-019 — Failed clipboard DB import silently reports success; media rollback is dead code.**
  `ClipboardDatabase.importFromJSON` (`ClipboardDatabase.kt:1569-1610`) wraps everything in
  `catch (e: Exception) { Log.e(...) }` and returns partial counts. The rollback path built for
  CK-150-001 (`BackupRestoreManager.kt:1474-1480`, and the outer catch feeding
  `FullBackupImportResult(success=false)` at `:2060`) can therefore never fire on a DB error: a
  rolled-back transaction returns `[0,0,0,0]`, staged media stays **committed**, and the user is
  told the import succeeded. The unit suite masks this because `clipboardDb` is a MockK stub.

### P2

- **CK-150-020 — `importFullBackup` settings/dictionary sections are not transactional.**
  `SettingsImportApplier.apply` (`BackupRestoreManager.kt:1967-1971`) and `DictImportApplier.apply`
  (`:1986-1988`) write SharedPreferences after the media commit; a later section failure rolls back
  media but leaves prefs partially applied.
- **CK-150-021 — ZIP directory entries are treated as media files.** Neither importer checks
  `entry.isDirectory` in the `clipboard_media/` branch (`BackupRestoreManager.kt:1451`, `:1924`).
  A `clipboard_media/x/` directory entry stages as an empty file; at commit,
  `File.copyTo(overwrite=true)` (`:2300`) silently replaces an empty live *directory* with an empty
  file (journal records `existed=false`, so rollback deletes instead of restoring) or throws
  `FileAlreadyExistsException` for a non-empty one. Duplicate *directory* entries are also exempt
  from the `seenEntries` guard (`!entry.isDirectory && !seenEntries.add(...)`).
- **CK-150-022 — `MediaCommit.rollback()` aborts on first failure.** `:2186-2195` has no per-entry
  try/catch: one failing restore `copyTo` skips all later entries' rollback, and an exception
  escaping a `catch` block bypasses the structured `success=false` result at `:2064`.
- **CK-150-023 — Security scanning: PR-path Trivy still advisory; release Trivy near-vacuous.**
  (a) `ci.yml:114` `continue-on-error: true` — the exact defect CK-150-003 flagged, still live on
  the PR/push path. (b) `release.yml:115-128` Trivy runs `scan-type: fs` with
  `ignore-unfixed: true` over a repo with **no Gradle lockfile** — the shipped APK's dependency
  tree is invisible to it; the only lockfiles are `site/bun.lock`, `web_demo/tests/bun.lock`, and
  vendored squoosh files.
- **CK-150-024 — English possessive augmentation leaks onto secondary-language swipe words.**
  `CtcRankMerger.Item.language` is discarded at `CtcEngineAdapter.kt:814` (`PredictionResult`
  carries only words/scores). `SuggestionHandler.shouldAugmentPossessives` (`SuggestionHandler.kt:119`,
  applied at `:624`) gates on the **primary** language only — so with en-primary/fr-secondary,
  French candidates get `'s` forms appended; with fr-primary/en-secondary, English candidates get
  none.
- **CK-150-025 — Fuzzy-rescue score clamp inverts when top ≈ second.** In `applyFuzzyRescue`
  (`CtcEngineAdapter.kt:684-693`): with `topScore == secondScore` (e.g. `[800, 800]`),
  `firstRescueScore = maxOf(801, 400).coerceAtMost(799) = 799`, then
  `.coerceAtLeast(secondScore + 1)` at `:693` raises the rescued score to **801 > topScore**.
  Positional order still lists the original word first, but `SwipeContextRescorer`'s rank-1 guard
  is a **score ratio** (`SuggestionHandler.kt:206`), so a rescued word can be promoted over a
  confident decode — exactly what the CK-150-005 acceptance rule forbids. Also: no rank-one
  preservation test exists (§2c).
- **CK-150-026 — Dual-language decode cost is unmeasured; LRU capacity 2 thrashes on a third
  language.** `CtcLatencyGateTest.productionDecodePath_meetsLatencyBudget_andReusesMemos` (`:108`)
  is single-language. Dual mode = 2 beam searches + up to 2×4,096-word rescue scans per swipe.
  `trieMemos` evicts at `size > 2` (`CtcEngineAdapter.kt:376-380`); a language switch cycle
  involving 3 codes rebuilds a ~19 MB memo each time.
- **CK-150-027 — Accessibility/touch parity is asserted, not proven.** See §2e. The strongest
  available invariant — sweep every x in `[0, hostWidth)` per row band and assert
  `keyAt(x,y)?.takeIf { it.key0 != null } == owner(accessibilityRectContaining(x,y))` — is not
  tested, and the existing dense sweep tests the wrong function.
- **CK-150-028 — Instrumentation gate accepts `OK (0 tests)`; curated class list unpinned.**
  `emulator-ci.sh:90` regex `^OK \([0-9]+ test` matches zero. Nothing references the curated list
  at `emulator-ci.sh:124` from any test (96 androidTest classes exist; 5 curated) — a renamed or
  emptied class false-greens the release device gate. This was an explicit CK-150-002 TODO, not done.
- **CK-150-029 — Still no TalkBack/touch-exploration-ON assertion.** `emulator-ci.sh:96` sets
  `accessibility_enabled=1` decoratively; nothing asserts behavior with touch exploration active
  (the hover test at `KeyboardAccessibilityInstrumentedTest.kt:199-210` requires TE **off**).
  Explicit CK-150-002 TODO, not done.
- **CK-150-030 — New English-only user-facing strings (regression of the CK-150-010 class).**
  Protection-state labels are hardcoded: `BackupPasswordBlock.kt:66-72` ("Protected by Android
  Keystore", etc.) and `BackupRestoreActivity.kt:212-215`. Untranslated and invisible to
  `TranslationCoverageDriftTest`.

### P3

- **CK-150-031 — EN rescue index silently drops accented lexicon entries.** `rescueFrequencies =
  merged` for the EN path (`CtcEngineAdapter.kt:504`) feeds `CtcFuzzyRescue.fromFrequencies`,
  which filters `word.all { it in 'a'..'z' }` (`CtcFuzzyRescue.kt:56`). ~200 accented
  `en_enhanced.json` entries (`abbé`, `adiós`, …) have trie surfaces but can never be rescued.
  CKDT languages are unaffected (indexed off `projected.freqs`).
- **CK-150-032 — Metadata/translation drift-test blind spots.** `ReleaseMetadataDriftTest`'s
  byte-parity assertion uses `texts.drop(1)`, excluding `RELEASE_NOTES.md` from the identity
  check; the `10600{1,2,3}.txt` paths are hardcoded (a version bump leaves the test green on stale
  files); `TranslationCoverageDriftTest` checks name presence only (an English copy-paste or a
  `<string>` where `<plurals>` is required passes).
- **CK-150-033 — Stale documentation left by the remediation itself.**
  (a) `docs/specs/ctc-swipe-engine.md:376` still shows the pre-expansion fr shape
  (`40,000 | 31 | 37,949 | 2,020`); code/tests now pin `40,000 | 0 | 37,958 | 2,042`.
  (b) `res/values/strings.xml:125,717,729` comments still describe `tools:ignore`
  suppressions that were removed and cite `memory/HANDOFF.md` debt that no longer exists.
  (c) `docs/audit/2026-08-23-v1.5-delta-remediation.md:71`'s Trivy statement is false (§2d) —
  corrected by addendum, not rewrite.
  (d) `CtcReplayEngine.kt:36-47` "known boundaries" list omits that replay builds rescue from raw
  `canonical` while shipping builds from post-`CtcLexiconMerge` `merged` (custom words in,
  disabled words out).
- **CK-150-034 — Backup test gaps.** No duplicate-ZIP-entry test; encrypted container/plaintext
  ceilings tested only at the `BackupCrypto` layer, never through `BackupRestoreManager`
  (`archiveContainerBytes` never set by a test); no test asserts `MediaCommit.rollback` deletes a
  **newly created** target; no test asserts the `ck_import_*` staging dir is actually removed;
  `ClipboardMediaManager` is `mockkConstructor`-stubbed in the limits test so traversal enforcement
  is never exercised through the importers.
- **CK-150-035 — Release workflow robustness.** `release.yml:31` hardcodes `v1.5.0..HEAD` (drifts
  into an ever-widening range at v1.7+; derive via `git describe --tags --abbrev=0` as `:346`
  already does); the tag↔Gradle version check (`:207-224`) runs in the final `release` job, after
  ~1h of gates, instead of in the `test` gate job.
- **CK-150-036 — German lexicon lacks `ß` vocabulary** (§2b). Decide: regenerate `de_enhanced.bin`
  preserving `ß` forms (then run the German eval), or document the vocabulary limitation.

## 4. Remediation plan for open items

Ordered by the release sequence in §5. Each item lists exact files, functions, and the intended
mechanics. Follow the repo testing policy: pure JVM tests where possible, ew-cli/instrumented
otherwise; never local ADB testing.

### 4.1 CK-150-019 — make DB import failure observable (P1, blocks release)

**Files:** `src/main/kotlin/tribixbite/cleverkeys/ClipboardDatabase.kt`,
`src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt`, tests.

1. In `ClipboardDatabase.importFromJSON` (`:1569`), delete the outer
   `try { … } catch (e: Exception) { Log.e(...) }` wrapper so exceptions propagate. Keep the inner
   `db.beginTransaction()/setTransactionSuccessful()/endTransaction()` structure exactly as-is —
   it already rolls the DB back correctly; the defect is only the swallow. Keep the success-path
   `Log.d`. Signature stays `fun importFromJSON(importData: JSONObject): IntArray`.
2. Audit the three call sites for the new throw:
   - `BackupRestoreManager.kt:1475` (clipboard ZIP): already wrapped — the existing
     `catch { mediaCommit?.rollback(); throw e }` becomes live code. No change.
   - `BackupRestoreManager.kt:2001` (full backup): confirm the enclosing try feeds the
     `catch` at `:2060` that returns `FullBackupImportResult(success=false)` **and** add
     `mediaCommit?.rollback()` on that path if it is not already reached with a non-null
     `mediaCommit` (mirror the clipboard-ZIP pattern: wrap the `importFromJSON` call, rollback,
     rethrow).
   - `BackupRestoreManager.kt:1568` (plain JSON import, no media): wrap in try/catch and convert
     to that function's failure return type; no media rollback needed.
3. Tests (`src/test/kotlin/tribixbite/cleverkeys/BackupRestoreArchiveLimitsTest.kt` or a new
   `BackupRestoreDbFailureTest.kt`): stub
   `every { clipboardDb.importFromJSON(any()) } throws SQLiteException("disk full")` (any
   `RuntimeException` works with the current signature) and assert: (a) result reports failure,
   (b) a pre-existing live media file's content is restored, (c) a newly created media file is
   deleted, (d) the `ck_import_*` staging dir is gone. This simultaneously closes the
   new-file-rollback and staging-removal gaps from CK-150-034.

**Acceptance:** a DB exception during either import path yields a failure result, restored media,
no new files, no staging residue.

### 4.2 CK-150-021 + CK-150-022 — directory entries and rollback robustness (P2, do with 4.1)

**File:** `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt`.

1. At the top of both `clipboard_media/` branches (`:1451`, `:1924`) and before the duplicate
   check, add: `if (entry.isDirectory) { zipIn.closeEntry(); continue }` (match the surrounding
   loop's entry-advance idiom). Also move duplicate tracking so directory entries are not exempt:
   simplest is to run `seenEntries.add(...)` for every named entry regardless of type.
2. In `MediaCommit.rollback()` (`:2186-2195`), wrap each entry's restore/delete in
   `runCatching { … }.onFailure { Log.e(TAG, "rollback failed for ${it}") }` so one failure cannot
   skip later entries; iterate the journal in reverse order of commit.
3. In `commitStagedMedia` (`:2273-2307`), record `existed = target.exists()` (not `isFile`) in the
   `CommittedMedia` journal so a replaced directory is at least not journaled as "new".
4. Tests: a ZIP with `clipboard_media/sub/` directory entry (must be skipped, no staging file); a
   rollback where the first restore target is made read-only/undeletable (assert later entries
   still restored).

### 4.3 CK-150-025 — fix the rescue score clamp; add the missing rank-1 tests (P2)

**File:** `src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt`, function
`applyFuzzyRescue` (`:669`).

1. Compute a safe ceiling first: `val ceiling = topScore - 1`. If `secondScore >= ceiling`
   (degenerate: top and second tied or inverted), append rescued words **below** the existing
   list instead of between rank 1 and 2 — i.e. give them `scores.last() - 1` descending — or skip
   insertion entirely. Then `firstRescueScore = maxOf(secondScore + 1, topScore / 2)
   .coerceAtMost(ceiling)` and change `:693` to
   `.coerceIn(secondScore + 1, ceiling)` so no rescued score can reach `topScore`.
   Mirror the identical logic in `CtcReplayEngine.kt:97-105` to keep replay faithful.
2. Testability: extract the merge into a pure, `internal` static function
   (e.g. `CtcFuzzyRescue.mergeIntoBeam(words: List<String>, scores: List<Int>, rescued: List<String>): Pair<List<String>, List<Int>>`
   in `swipe/ctc/CtcFuzzyRescue.kt`) and have `applyFuzzyRescue` delegate to it. Then add pure
   tests in `CtcFuzzyRescueTest.kt`: rank-1 word and score preserved for non-empty beams; tied
   `[800,800]` input never yields a rescued score ≥ 800; empty-slate fill; the `limit = 2` cap.

### 4.4 CK-150-024 — stop English possessives on secondary-language words (P2)

**Files:** `src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt`,
`swipe/ctc/CtcRankMerger.kt`, `SuggestionHandler.kt`.

Smallest correct fix: keep per-word language through the pipeline only as far as the possessive
gate needs. Options in order of preference:

1. **Filter at augmentation:** in dual-language mode have `CtcEngineAdapter` retain the merged
   items' language in a parallel structure on the result (e.g. widen `PredictionResult` with an
   optional `languages: List<String>?`, defaulting null so all single-language paths are
   untouched), and change `SuggestionHandler`'s possessive loop (`:618-630`) to skip words whose
   entry language is non-English. `shouldAugmentPossessives(activeLanguage)` remains the gate for
   the null case.
2. If widening `PredictionResult` is too invasive, an approximate fix — only augment words present
   in the English trie surface — is **not** acceptable (cross-language homographs), so prefer 1.

Add a unit test: en-primary/fr-secondary merged list where a French word must not gain `'s`, and
the English word still does.

### 4.5 CK-150-028 + CK-150-029 — close the false-green device-gate holes (P2)

**Files:** `.github/scripts/emulator-ci.sh`, `.github/workflows/ui-testing.yml`,
`src/test/kotlin/tribixbite/cleverkeys/` (new pin test),
`src/androidTest/kotlin/tribixbite/cleverkeys/a11y/KeyboardAccessibilityInstrumentedTest.kt`.

1. `emulator-ci.sh:90`: tighten to `grep -qE "^OK \([1-9][0-9]* tests?\)"`.
2. Pin the curated list: add a pure test (e.g. `CuratedInstrumentationListTest.kt`) that reads
   `.github/scripts/emulator-ci.sh`, extracts the comma-joined class list at the `gate` case, and
   asserts (a) each named class exists under `src/androidTest/kotlin` with ≥1 `@Test`, and
   (b) the list exactly equals a checked-in expected set — so adding/renaming curated classes is a
   conscious two-file change. (`SourceTextHygieneTest.kt:12` already shows the pattern of a pure
   test reading `.github`.)
3. TE-on smoke: add one instrumented test that enables touch exploration via
   `UiAutomation.setRunAsMonkey`-free route — on API 34 use
   `InstrumentationRegistry.getInstrumentation().uiAutomation` with
   `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` and an `AccessibilityServiceInfo` enabling
   `FLAG_REQUEST_TOUCH_EXPLORATION_MODE`, then assert `AccessibilityManager.isTouchExplorationEnabled`
   and that a hover event over a key routes through `KeyboardAccessibilityHelper.dispatchHoverEvent`
   (returns true) instead of the raw touch path. If the emulator image rejects TE enablement,
   document the limitation in the test's `assumeTrue` and keep the assertion honest.

### 4.6 CK-150-023 — make security scanning mean something (P2)

**Files:** `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `build.gradle`,
`gradle.lockfile` (new).

1. `ci.yml:114`: delete `continue-on-error: true`; keep the PR-path scan `severity: HIGH,CRITICAL`
   so it only blocks on what release blocks on.
2. Give Trivy something to scan: enable Gradle dependency locking —
   `dependencyLocking { lockAllConfigurations() }` in `build.gradle`, generate with
   `./gradlew dependencies --write-locks` (use the Termux aapt2 override), commit
   `gradle.lockfile`. Trivy's `fs` scanner reads `gradle.lockfile` natively.
3. Revisit `ignore-unfixed: true` in `release.yml:127`: keep it only with a written acceptance
   note in the workflow comment (the audit asked for an explicit threshold decision).

### 4.7 CK-150-020 — settings/dictionary rollback in full backup (P2)

**File:** `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt` (`:1955-1995`).

Snapshot-and-restore: before `SettingsImportApplier.apply`, capture the target
`SharedPreferences` as `prefs.all.toMap()`; wrap all section appliers in one try; on failure,
clear and rewrite from the snapshot (typed `putX` per value class — a small
`restorePrefsSnapshot(prefs, snapshot)` helper), roll back media, return `success=false`.
Order the sections so prefs apply **before** `commitStagedMedia` where feasible — prefs are
cheaper to roll back than media, so commit media last. Test: applier for section 2 throws →
section 1's prefs are restored to the snapshot.

### 4.8 CK-150-030 — externalize the protection-state strings (P3, batch with any i18n pass)

**Files:** `ui/settings/sections/BackupPasswordBlock.kt:66-72`, `BackupRestoreActivity.kt:212-215`,
`res/values*/strings.xml`, `TranslationCoverageDriftTest.kt`.

Add `backup_protection_state_{keystore,legacy,not_set}` (+ the error-state string used in
`BackupPasswordBlock.kt:146-147`) to default strings, translate in all 21 locales, replace the
literals, and append the names to `TranslationCoverageDriftTest`'s pinned list.

### 4.9 CK-150-026 — dual-language latency evidence (P2, release measurement)

Extend `CtcLatencyGateTest` with a dual-language case (en+fr) asserting the same p95 budget on the
production decode path, and a memo-reuse assertion across an en→fr→en switch (capacity 2 means no
eviction for exactly two languages — assert that). No capacity change unless the measurement fails;
if it does, raise `trieMemos` eviction threshold (`CtcEngineAdapter.kt:376-380`) to 3 and re-measure
memory with the existing `MemoryProbe` opt-in.

### 4.10 CK-150-027 — prove the accessibility partition (P3)

**Files:** `a11y/KeyboardGeometry.kt`, `src/test/kotlin/tribixbite/cleverkeys/a11y/KeyboardGeometryTest.kt`.

1. Add a dense parity test: for each row and for x in 0 until hostWidth step 1px (use the test's
   fixed geometry), assert `keyAt(x,y)` (filtered to real keys) matches the owner of the first
   accessibility rect containing `(x,y)`, including `keyAt == null` ⇔ no rect. Run it over: the
   QWERTY fixture, an a/l row, a mid-row placeholder, and a **trailing** placeholder row.
2. Either fix the trailing-placeholder right-slop (extend the last *real* key to `hostWidth` when
   trailing placeholders exist — but only if `keyAt` does the same; if `keyAt` returns the
   placeholder, exclude that band from the parity contract and document it) or codify the
   divergence in the test with an explicit allowlist.
3. Correct the "non-overlapping" doc comment at `KeyboardGeometry.kt:213` to state the actual
   contract: first-match-wins ordering with a documented pre-`a` overlap, or make rects truly
   disjoint by clipping the pre-`a` key's left edge at `a`'s right edge in the a/l case.

### 4.11 CK-150-031…035 — small fixes (P3, one cleanup commit each or batched)

- **031:** in `CtcEngineAdapter.kt:504`, build the EN rescue index from the same strip-projection
  used for the trie (reuse the loader's accent-strip so `abbé` indexes as `abbe` with display
  restored via `applyDisplay`), or document the exclusion in `CtcFuzzyRescue.fromFrequencies`.
- **032:** `ReleaseMetadataDriftTest`: assert `texts.distinct().size == 1` over **all four** files;
  derive the changelog filenames from `build.gradle`'s `VERSION_*` (read the file, regex the three
  ints, compute `baseCode`) so a version bump fails the test until notes exist.
  `TranslationCoverageDriftTest`: additionally assert element type (`<plurals` for the two
  quantity names) and assert the localized value differs from the default-locale value for at
  least N of the 21 locales (guards against wholesale English copies without banning legitimate
  cognates).
- **033:** update `docs/specs/ctc-swipe-engine.md:376` to `40,000 | 0 | 37,958 | 2,042`; rewrite
  the three stale comments in `res/values/strings.xml:125,717,729`; append the rescue-input note
  to `CtcReplayEngine.kt:36-47`; the remediation-report correction is the addendum already added
  at the top of that file.
- **034:** add the missing backup tests listed in §3 (duplicate entry; `archiveContainerBytes`
  through `BackupRestoreManager`; the rest land with 4.1/4.2).
- **035:** `release.yml:31` → `RANGE="$(git describe --tags --abbrev=0)..HEAD"`; move the
  tag↔version check block (`:207-224`) into the `test` job before the long gates.

### 4.12 CK-150-036 — German `ß` vocabulary decision (product, not code)

Decide with the maintainer: regenerate `de_enhanced.bin` from a `ß`-preserving wordlist via the
dictionary pipeline (see `.claude/skills/dictionary-pipeline.md`), which makes the already-landed
`ß→ss` expansion live for German — then run the German held-out evaluation. Otherwise add one
sentence to the release notes' German claim. Until decided this stays out of the v1.6 gate.

## 5. Updated release sequence (supersedes the 2026-08-23 sequences)

1. **CK-150-019** (+ 4.2's directory/rollback hardening and its tests) — the only new code blocker.
2. **CK-150-025** score-clamp fix + extracted merge tests (small, protects the headline CTC path).
3. Re-run `runPureTests`, `runMockTests`, `lintDebug`, `compileDebugAndroidTestKotlin` at the
   candidate SHA.
4. Run the curated API-34 emulator gate (GitHub Actions or ew-cli 1.3.4) on the exact candidate SHA
   — after 4.5's `OK (0 tests)` regex fix so the gate is meaningful.
5. **French** held-out evaluation for the projection change (per §2a, German/Nordic is out of
   scope until CK-150-036 is decided); record under `docs/eval/`.
6. Context rescoring stays default-off (unchanged from CK-150-009).
7. P2 items 4.4/4.6/4.7/4.9 before tag if time allows; otherwise schedule immediately post-release
   with the audit's explicit-acceptance rule. P3 items are non-blocking.
8. Tag/publish only with explicit maintainer authorization.
