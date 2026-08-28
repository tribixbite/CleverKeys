# Engineering Practices — Verification & Remediation

_Adversarial re-verification of the prior CleverKeys engineering-practices audit._
_Date: 2026-07-16. Method: fresh `rg`/`git ls-files`/`Read` evidence (no grep/find — device shims broken)._
_Repo: `/data/data/com.termux/files/home/git/swype/cleverkeys`. build.gradle version = **1.5.0** (versionCode 10500)._

---

## Verification Results

| # | Finding | Verdict | Evidence (fresh) |
|---|---------|---------|------------------|
| 1 | [P2] `release.keystore` in working dir, NOT committed; `.gitignore:33 *.keystore` covers it | **CONFIRMED (safe)** | `git ls-files \| rg keystore` → only `debug.keystore`, `debug.keystore.asc`. `git check-ignore -v release.keystore` → `.gitignore:33:*.keystore	release.keystore`. `git status --porcelain release.keystore` → empty (untracked+ignored). Working file exists: `-rw------- 2698 release.keystore` (0600 perms). Note: `debug.keystore.asc` is a **PGP AES-256 symmetric-encrypted** blob (`file` says so) — safe to commit; it's the debug key, encrypted. |
| 2 | [P2] Hand-maintained runner class arrays; `ClipboardSearchRegexTest` in NEITHER; only runs via non-gating `gradlew test` (build.yml:70-71, continue-on-error) | **CONFIRMED (worse — 31 orphaned @Test methods)** | `build.gradle:349-409` = 58 hardcoded classes (runPureTests); `build.gradle:443-461` = 17 classes (runMockTests). `rg ClipboardSearchRegexTest build.gradle` → **not found**. The file is pure (no Robolectric/MockK/android import) with **31 `@Test` methods**. build.yml:70-71: `run: ./gradlew test --stacktrace` immediately followed by `continue-on-error: true`. So its 31 tests execute in ZERO gating path. (3 other src/test classes — `ComposeKeyTest`, `IntegrationTest`, `NeuralPredictionTest` — are also absent but are `@RunWith(RobolectricTestRunner)` / `@Ignore`, legitimately excluded and superseded by `*PureTest`.) |
| 3 | [P2] 973 suppressed lint issues (10,660 lines) incl. 76 NewApi on minSdk 21; lint continue-on-error (ci.yml:53-55) | **CONFIRMED (count is 972, not 973)** | `wc -l lint-baseline.xml` = 10660. `rg '^\s*<issue$'` = **972** issue elements (the 973rd `<issue` line is the `<issues …>` container header — off-by-one in the prior audit). NewApi = **76** (`rg -c 'id="NewApi"'`). minSdk 21 (`build.gradle:107`). Top types: MissingTranslation 342, UnusedResources 209, UnusedAttribute 80, NewApi 76, SetTextI18n 52, AutoboxingStateCreation 47, LongLogTag 38. ci.yml:53-55: `run: ./gradlew lint --stacktrace` + `continue-on-error: true`. |
| 4 | [P2] R8/ProGuard disabled: `minifyEnabled false // REPRODUCIBILITY TEST`; 295-line proguard-rules.pro dormant; APK ~48-52MB | **CONFIRMED (APK size wrong: ~63-66MB)** | `build.gradle:255-257`: `// REPRODUCIBILITY TEST: Disable R8 …` / `minifyEnabled false` / `shrinkResources false`. `rg 'minifyEnabled true' build.gradle` → none (R8 never enabled anywhere). `proguard-rules.pro` = **295 lines**, real rules (InputMethodService keeps, ONNX/Compose), referenced at `build.gradle:259` but dormant because minify is off. **Release APK sizes are 62.8-64.1 MB** (arm64 66,359,913 B), debug 70-72 MB — the ~48-52MB claim is REFUTED by on-disk artifacts. |
| 5 | [P2] Logic-mirror tests reimplement production decision fn (AutoSpaceLogicTest.kt:25-51 "Mirrors SuggestionHandler.kt 634-655") | **CONFIRMED (worse — line refs already rotted)** | `AutoSpaceLogicTest.kt:27` doc: `Mirrors SuggestionHandler.kt lines 634-655 decision logic.`; a private `decideSpaceMode(...)` (lines ~35-51) reimplements the 4 branches; `:53` `Mirrors addedTrailingSpace logic from SuggestionHandler.kt lines 662-664`. But production logic has **drifted**: the real `auto_space_after_suggestion`/`hasSpaceAfter`/`isSwipeAutoInsert` decision now lives at `SuggestionHandler.kt:698-718` and `addedTrailingSpace` at `:717-718` — not 634-655/662-664. Lines 634-655 today contain unrelated `needsSpaceBefore`/`SmartAutoSpace` code. The mirror can pass green while production is wrong; nothing links them. |
| 6 | [P3] androidTest smoke tail (260 assertNotNull vs 982 assertEquals); 3 tracked `remote_v*/CHANGELOG.md`; `archive/` 341 files | **CONFIRMED (counts: 262 / 987)** | `src/androidTest`: assertNotNull = **262**, assertEquals = **987** (prior audit 260/982 — close). `git ls-files \| rg remote_v` → exactly **3**: `remote_v16/CHANGELOG.md`, `remote_v19_all/cleverkeys-release-v1.1.19/CHANGELOG.md`, `remote_v20_all/cleverkeys-release-v1.1.20/CHANGELOG.md`. `git ls-files archive \| wc -l` = **341** (mostly `archive/cleverkeys-kt/*.kt` — the old `tribixbite.keyboard2` package). 341/1966 total tracked = 17%. Neither `archive/` nor `remote_v*` is in `.gitignore`. |
| 7 | [P3] Duplicate CI (ci.yml + build.yml both assembleDebug on push/PR); multiple stale strings | **CONFIRMED** | See Stale-String Fix List below. Both `ci.yml` and `build.yml` trigger on `push: [main, develop]` + `pull_request` and both run `./gradlew assembleDebug --stacktrace` (ci.yml:35-36, build.yml:59-60). build.yml's header comment even mislabels itself "CI Workflow" while `name:` is "Build APK". |
| 8 | [P3] GitHub Actions pinned by major tag not SHA except trivy | **CONFIRMED** | Only SHA-pin repo-wide: `ci.yml:111 uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25`. All others `@vN`/`@vN.N.N`: e.g. `actions/checkout@v4`, `github/codeql-action/upload-sarif@v3`, `reactivecircus/android-emulator-runner@v2`, `softprops/action-gh-release@v2` (and `@v1` in build-apk.yml — itself a version-skew), `actions/configure-pages@v5`. |
| S | STRENGTH: pure suite assertThat≫assertNotNull; CI runs runPureTests non-optionally; signing env-driven w/ empty-string guard | **CONFIRMED** | `src/test`: **assertThat = 2608**, assertNotNull = **9** (prior audit 2539/9 — assertion-dense, behavior-checking). `ci.yml:38-39` runs `./gradlew runPureTests --stacktrace` with **no** continue-on-error → it gates. Signing guard `build.gradle:230-243`: `def keystorePath = System.env.RELEASE_KEYSTORE; if (keystorePath != null && keystorePath.trim().length() > 0)` → uses release keystore, else falls back to debug — correctly handles Groovy empty-string truthiness. Same guard again at `:265-268` decides whether to sign at all. |

---

## Remediation Steps (severity-ordered)

### R2 — [P2] Fix orphaned test + add runner drift-check (highest value: 31 tests currently run in no gating path)

**2a. Add `ClipboardSearchRegexTest` to the pure runner.** `build.gradle`, in `pureTestClasses`. It is pure (no android/Robolectric/MockK), so it belongs with the JVM tests.

Before (`build.gradle:407-408`):
```groovy
    'tribixbite.cleverkeys.HapticsBehaviorDriftTest',
    // SwipePrunerTest excluded: requires android.util.Log (MockK + Robolectric)
```
After:
```groovy
    'tribixbite.cleverkeys.HapticsBehaviorDriftTest',
    'tribixbite.cleverkeys.ClipboardSearchRegexTest',
    // SwipePrunerTest excluded: requires android.util.Log (MockK + Robolectric)
```

**2b. Remove the non-gating fallback that masked the orphan** (`build.yml:69-71`). `gradlew test` is disabled on ARM64 (produces no unit-test results) and its `continue-on-error: true` gives false assurance. Either delete the step or convert to the real runner:

Before (`build.yml:69-71`):
```yaml
    - name: Run tests
      run: ./gradlew test --stacktrace
      continue-on-error: true
```
After:
```yaml
    - name: Run pure JVM tests
      run: ./gradlew runPureTests --stacktrace
```

**2c. Add a drift-check test** (see "Test-List Drift-Check Proposal" below) so a future missed class fails CI instead of silently vanishing.

- **Risk:** LOW. 2a/2c add coverage; the added test compiles today (pure Kotlin). 2b makes build.yml actually gate — if any pure test is currently red, that surfaces (desirable). Verify locally: `./gradlew runPureTests -PtestClass=ClipboardSearchRegexTest`.

### R3 — [P2] Freeze/reduce the lint baseline and make lint gate

The 972-issue baseline (`lint-baseline.xml`) + `continue-on-error: true` (`ci.yml:53-55`) means lint regressions are invisible. Two-phase:

**Phase 1 (freeze, zero risk):** Make lint gate on *new* issues only. The baseline already suppresses existing ones, so gating just prevents new debt.

Before (`ci.yml:52-55`):
```yaml
    - name: Run lint checks
      run: ./gradlew lint --stacktrace
      continue-on-error: true
```
After:
```yaml
    - name: Run lint checks (fails on new issues; existing suppressed via lint-baseline.xml)
      run: ./gradlew lint --stacktrace
```
Also add to `build.gradle` `android { lint { … } }`:
```groovy
  lint {
    baseline = file("lint-baseline.xml")
    checkDependencies true
    abortOnError true      // was implicitly false via continue-on-error
    warningsAsErrors false // keep warnings non-fatal initially
  }
```

**Phase 2 (reduce, low risk, incremental):** Burn down the baseline by category. Priority order by real risk:
1. **NewApi (76)** — real crash risk on minSdk 21 API 21-25 devices. Each needs a `Build.VERSION.SDK_INT` guard or `@RequiresApi`. This is the P0-adjacent subset; do first. (Note README claims API 26+ — if minSdk is *raised* to 26 per R7, ~most NewApi entries evaporate legitimately.)
2. **StaticFieldLeak (6), DrawAllocation (4)** — memory/perf, fix in code.
3. **MissingTranslation (342), UnusedResources (209), UnusedAttribute (80)** — cosmetic; safe to leave suppressed or bulk-clean.

After each burn-down, regenerate the baseline: `./gradlew updateLintBaseline`, commit the shrunk file.

- **Risk:** Phase 1 LOW (baseline suppresses all current). Phase 2 NewApi fixes are MEDIUM (touch runtime code paths — cover with instrumented tests on an API-21/23 emulator via ew-cli).

### R4 — [P2] Re-enable R8 with the dormant rules

`minifyEnabled false // REPRODUCIBILITY TEST` (`build.gradle:255-257`) was a temporary diagnostic that became permanent. `proguard-rules.pro` (295 lines) already has the keeps. Re-enabling drops APK ~63MB → est. ~35-45MB and removes dead code.

Before (`build.gradle:255-257`):
```groovy
      // REPRODUCIBILITY TEST: Disable R8 to isolate if it causes non-deterministic DEX
      minifyEnabled false
      shrinkResources false
```
After:
```groovy
      // R8 enabled — determinism validated; rules in proguard-rules.pro
      minifyEnabled true
      shrinkResources true
```

**Validation plan (reflection/ONNX are the failure surfaces):**
1. Audit `proguard-rules.pro` keeps cover: `InputMethodService` subclasses (present), all `Activity`/`Service`/`Receiver` in the manifest, ONNX Runtime native/JNI classes (`ai.onnxruntime.**`), Compose runtime, Kotlin metadata/coroutines, any `Class.forName`/`getDeclaredMethod`/Gson/kotlinx-serialization reflection targets, and enum `values()` used via reflection.
2. `rg -n 'Class\.forName|getMethod|getDeclaredField|::class\.java|forName\(' src/main` to enumerate reflection sites and confirm each has a `-keep`.
3. Build release with R8 on, install, run the FULL ew-cli instrumented suite (`--use-orchestrator --timeout 25m --device model=Pixel7,version=34`) — this exercises IME lifecycle, ONNX prediction, clipboard DB, settings serialization end-to-end. R8 breakage surfaces as `ClassNotFoundException`/`NoSuchMethodException` at runtime, which instrumented tests catch.
4. Verify reproducibility (the original concern): build twice, `diff` the APK `classes.dex` sha256. R8 in AGP 8.x is deterministic by default (per the proguard-rules.pro header comment itself) — if non-determinism recurs, add `-dontobfuscate` or pin the R8 seed rather than disabling minify wholesale.
5. Keep `shrinkResources true` behind a smoke pass — it can strip resources referenced only by name (`getIdentifier`); add `keep.xml` for any dynamic resource lookups.

- **Risk:** MEDIUM-HIGH — R8 stripping a reflection-reached class is the classic IME crash. Gate strictly behind the instrumented suite before shipping; ship to internal track first.

### R5 — [P2] Replace logic-mirror test with a real seam

`AutoSpaceLogicTest` reimplements `SuggestionHandler`'s decision and asserts against its own copy — it tests the copy, not production, and its line references (634-655/662-664) already point at the wrong code. Fix by extracting the decision into a pure, testable function that BOTH production and test call.

1. Extract the branch logic from `SuggestionHandler.kt:698-718` into a pure top-level/companion fn, e.g. `SmartAutoSpace.decideTrailingSpace(autoSpaceAfter: Boolean, isSwipeAutoInsert: Boolean, hasSpaceAfter: Boolean): Boolean` (mirror the real inputs, not the invented `termuxModeEnabled` branch).
2. Have `SuggestionHandler` CALL that fn (single source of truth).
3. Rewrite `AutoSpaceLogicTest` to call `SmartAutoSpace.decideTrailingSpace(...)` directly — delete the private `decideSpaceMode` reimplementation and the "Mirrors …lines N" comments.

- **Risk:** LOW-MEDIUM — refactor of live typing path; covered by existing contraction/auto-space instrumented tests. Do behavior-preserving extraction, verify with `runPureTests` + the auto-space instrumented tests.

### R6 — [P3] Delete stray tracked artifacts

`archive/` (341 files, old `tribixbite.keyboard2` package) and 3 `remote_v*/CHANGELOG.md` are dead weight (17% of tracked files) and can confuse `rg` audits.

```bash
git rm -r archive/ remote_v16/ remote_v19_all/ remote_v20_all/
```
Then add to `.gitignore` (append after the existing build section):
```
# Stray release-download / legacy-source directories (do not re-commit)
/archive/
/remote_v*/
```
- **Risk:** LOW. History is preserved in git; `archive/README.md` content, if still wanted, lives in the wiki. Confirm nothing in the build references `archive/` first: `rg -n 'archive/' build.gradle settings.gradle` (expected: none).

### R7 — [P3] De-duplicate CI + fix all stale strings

**7a. Merge or scope the two workflows.** `ci.yml` and `build.yml` both `assembleDebug` on every push/PR — double the runner cost and confusing. Options: (a) delete `build.yml`, fold its APK-upload + size-analysis steps into `ci.yml`; or (b) restrict `build.yml` to a distinct trigger (e.g. only PR-labeled `build-apk`). Prefer (a).

**7b. Fix stale strings** — see the Fix List table below (mechanical string edits, LOW risk).

- **Risk:** LOW. 7a changes CI topology — verify with a test PR that the merged workflow still uploads APK + test results.

### R8 — [P3] SHA-pin remaining GitHub Actions (optional hardening)

Pin all `uses:` to full commit SHAs (supply-chain hardening — mutable tags can be repointed). Lower priority; trivy already sets the precedent. Use `pinact` or Dependabot's action-pinning. Also fix the `softprops/action-gh-release` version skew (`@v2` in nightly.yml vs `@v1` in build-apk.yml — align to one).

- **Risk:** LOW. Mechanical; add Dependabot `package-ecosystem: github-actions` to auto-bump the pins.

---

## Stale-String Fix List

| File | Current (line) | Correct value |
|------|----------------|---------------|
| `.github/workflows/ci.yml` | `name: Run pure JVM tests (987 tests)` (ci.yml:38) | Drop the hardcoded count or make it accurate. Runner arrays hold **58 pure classes** (runPureTests) — actual executed @Test method count is ~1000+ and drifts. Use `name: Run pure JVM tests` (no number) to avoid future rot. |
| `.github/workflows/ci.yml` | `# MockK tests (176) …` (ci.yml:40) | Verify against `runMockTests` (17 classes); update or drop the "176" figure. |
| `SECURITY.md` | `\| 1.0.x   \| :white_check_mark: \| Current stable release \|` (SECURITY.md:13) | `\| 1.5.x   \| :white_check_mark: \| Current stable release \|` |
| `SECURITY.md` | `\| < 1.0   \| :x: \| Development versions (not released) \|` (SECURITY.md:14) | `\| < 1.5   \| :x:                \| Unsupported \|` (adjust support window to policy) |
| `SECURITY.md` | `**Version**: 1.0` (SECURITY.md:371, 398); `- v1.0.0` / `- v1.0.1` example (SECURITY.md:81-82); `**Last Updated**: 2025-11-16` (SECURITY.md:370) | Bump document Version + Last Updated to current; refresh example affected-version placeholders. |
| `README.md` | `- Minimum SDK level 26 (Android 8.0+)` (README.md:350) | Reconcile with `build.gradle:107 minSdk 21`. Either **(pick one)**: change README to `- Minimum SDK level 21 (Android 5.0+)`, OR raise `build.gradle` to `minSdk 26` (recommended — kills most of the 76 NewApi baseline entries; verify no <26 install-base need). |
| `README.md` | `- Android SDK (API 26+)` (README.md:364) | Match the decision above: `- Android SDK (API 21+)` or keep 26 if minSdk is raised. |
| `metadata/fdroid/tribixbite.cleverkeys.yml` | `CurrentVersion: 1.2.8` (line 453) | `CurrentVersion: 1.5.0` |
| `metadata/fdroid/tribixbite.cleverkeys.yml` | `CurrentVersionCode: 102083` (line 454) | `CurrentVersionCode: 105002` (1.5.0 base 10500 → arm64 ABI code `*10+2`; confirm against the ABI mapping in build.gradle:91-101 and the F-Droid abiCodes) |
| `CLAUDE.md` | `**CURRENT STATUS (2026-03-26):**` + `✅ Production Ready (Grade A)` (CLAUDE.md:11,13) | Update date to current; the recent audit commit `e67594037` records **B-/C+**, so "Grade A" is inaccurate — change to the real current grade. |
| `.github/workflows/build.yml` | Header comment `# CI Workflow - Build and Test` while `name: Build APK` (build.yml:1,9) | Align comment to `name:` (or resolve via R7a dedup). |

---

## Test-List Drift-Check Proposal

Mirror the project's existing `SettingsDefaultsDriftTest` pattern (source-scanning pure JVM test). This test scans `src/test` + `src/androidTest` for `@Test`-bearing classes and asserts each is either in a runner array or explicitly exempt — so a future missed class (like `ClipboardSearchRegexTest`) fails `runPureTests` instead of silently not running.

Place at `src/test/kotlin/tribixbite/cleverkeys/TestRunnerListDriftTest.kt` and add its own FQCN to `pureTestClasses`.

```kotlin
package tribixbite.cleverkeys

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Drift guard: every @Test-bearing class in src/test / src/androidTest must be
 * reachable by CI — either listed in build.gradle's runPureTests/runMockTests
 * arrays, or explicitly exempt (Robolectric/@Ignore, run via ew-cli instrumented).
 *
 * Mirrors SettingsDefaultsDriftTest's "scan source, assert classification" approach.
 * Prevents the ClipboardSearchRegexTest orphan class (31 @Test methods that ran in
 * no gating path) from recurring.
 */
class TestRunnerListDriftTest {

    // Resolve repo root from the test's working dir (Gradle runs from module dir).
    private val repoRoot = File(System.getProperty("user.dir"))
    private val buildGradle = File(repoRoot, "build.gradle").readText()

    // Classes deliberately NOT in a JVM runner. Each entry MUST justify itself:
    // Robolectric (can't run on ARM64 JVM), @Ignore, or instrumented-only (androidTest,
    // run via ew-cli on Pixel7/API34, not the JVM runners). Keep this list tight.
    private val exemptSimpleNames = setOf(
        "ComposeKeyTest",        // @RunWith(RobolectricTestRunner) @Ignore — superseded by ComposeKeyPureTest
        "IntegrationTest",       // Robolectric — superseded by IntegrationPureTest
        "NeuralPredictionTest",  // Robolectric — superseded by NeuralPredictionPureTest
        "SwipePrunerTest",       // needs android.util.Log — noted excluded in build.gradle
    )

    @Test
    fun everyUnitTestClassIsInARunnerOrExempt() {
        val testDir = File(repoRoot, "src/test/kotlin")
        val orphans = mutableListOf<String>()

        testDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val text = f.readText()
                if (!text.contains("@Test")) return@forEach
                // Skip pure Robolectric files entirely (they can't run on the JVM runner).
                if (text.contains("RobolectricTestRunner")) return@forEach

                val fqcn = fqcnOf(f, text) ?: return@forEach
                val simple = fqcn.substringAfterLast('.')
                if (simple in exemptSimpleNames) return@forEach

                // Must appear (quoted) in build.gradle's runner arrays.
                if (!buildGradle.contains("'$fqcn'")) {
                    orphans += "$fqcn  (${f.relativeTo(repoRoot)})"
                }
            }

        if (orphans.isNotEmpty()) {
            fail(
                "Orphaned @Test classes not in any Gradle runner array " +
                "(add to pureTestClasses/mockTestClasses in build.gradle, " +
                "or to exemptSimpleNames with a justification):\n  " +
                orphans.joinToString("\n  ")
            )
        }
    }

    @Test
    fun exemptListHasNoStaleEntries() {
        // A name in the exempt set must still exist as a source file, else it's rot.
        val testFiles = File(repoRoot, "src/test/kotlin")
            .walkTopDown().filter { it.extension == "kt" }
            .map { it.nameWithoutExtension }.toSet()
        val stale = exemptSimpleNames.filter { it !in testFiles }
        assertTrue("Stale exempt entries (file deleted): $stale", stale.isEmpty())
    }

    /** Derive FQCN from `package …` + filename (one public test class per file, project convention). */
    private fun fqcnOf(file: File, text: String): String? {
        val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1) ?: return null
        return "$pkg.${file.nameWithoutExtension}"
    }
}
```

Notes:
- Uses `System.getProperty("user.dir")` (the module dir under Gradle) to find sources — the same filesystem-scan technique `SettingsDefaultsDriftTest`/`GesturePrefAccessDriftTest` already use, so no new infra.
- Covers `src/test` for the JVM runners. A parallel assertion could scan `src/androidTest` against the ew-cli config, but those already run via package-scan on-device, so the exempt-set model suffices; the primary gap this closes is the JVM runner arrays.
- After adding, `./gradlew runPureTests -PtestClass=TestRunnerListDriftTest` should FAIL until `ClipboardSearchRegexTest` is added (proving it works), then pass.

---

## Effort Estimate to Reach "A" Grade

| Item | Effort | Blocker to A? |
|------|--------|---------------|
| R2 orphan + drift-check (2a/2b/2c) | 2-3 h | **Yes** — untested code is the core gap |
| R5 logic-mirror → real seam | 2-4 h | **Yes** — false-confidence tests |
| R3 Phase 1 lint gate + baseline freeze | 1 h | **Yes** — stop new debt |
| R3 Phase 2 NewApi burn-down (76) | 1-2 days | Partial — gate blocks new; burn-down is quality |
| R4 re-enable R8 + full instrumented validation | 1 day (incl. emulator suite) | **Yes** — dormant tooling, 63MB APK |
| R6 delete archive/ + remote_v* | 20 min | No — hygiene |
| R7 CI dedup + stale strings | 2-3 h | Partial — stale docs mislead |
| R8 SHA-pin actions | 1-2 h | No — hardening |

**Critical path to A (the "Yes" rows): ~2-3 focused days.** R2 + R5 + R3-Phase1 + R4 remove the substantive gaps (untested orphan, self-referential tests, ungated lint, disabled optimizer). Full polish incl. NewApi burn-down and CI dedup: ~1 week. The keystore posture is already correct — no security remediation needed there.
