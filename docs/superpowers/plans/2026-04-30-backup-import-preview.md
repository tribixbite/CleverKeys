# BackupRestore Import Preview + Export Counts — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a preview-and-deselect modal before settings + dictionary imports so users can review every change and uncheck individual entries, plus surface real counts on both import and export result dialogs.

**Architecture:** Two-phase split inside `BackupRestoreManager` — `buildSettingsImportPlan` / `buildDictImportPlan` parse the URI and produce a typed plan with all validation/coercion already done; `applySettingsImportPlan` / `applyDictImportPlan` take the plan plus a user-supplied exclusion set and write atomically via a single `editor.commit()`. Activity hosts two new Compose dialogs (settings + dict preview) backed by a `ViewModel` so plans survive rotation. The headless Intent-action path used by Termux automation keeps the existing public methods unchanged — they internally delegate to build-then-apply with empty exclusions.

**Tech Stack:** Kotlin, Android `SharedPreferences`, Jetpack Compose Material3, `ViewModel`, JUnit4, MockK, ew-cli (emulator.wtf) for instrumented tests.

**Spec:** `docs/superpowers/specs/2026-04-28-backup-import-preview-design.md` (read this first — it has the failure-mode reasoning and the dual-reviewer findings that justify the unconventional choices).

---

## Repository conventions you must follow

- **Termux ARM64**: standard `./gradlew test` is disabled. Use `./gradlew runPureTests` (pure JVM via JUnitCore), `./gradlew runMockTests` (MockK with android.jar stubs), or ew-cli for instrumented tests.
- **`runPureTests` registration**: every new pure JVM test class MUST be added to the `pureTestClasses` list in `build.gradle` (around line 337). Tests not in the list don't run.
- **Single test class**: `./gradlew runPureTests -PtestClass=ClassName` runs one class for fast iteration.
- **No emojis or co-author trailers** in commit messages. Sign with em-dash: `— opus 4.7`. Conventional commits: `feat:`, `fix:`, `test:`, `docs:`, `refactor:`.
- **Don't touch `archive/`**, `lint-baseline.xml`, `0words.py`, `scripts/sv_words.txt` — those are unrelated.
- **Headless Intent path is sacred**: `BackupRestoreActivity.kt:99-115` (`isHeadless`) is used by Termux automation. The existing `importConfig(uri, prefs)` and `importDictionaries(uri)` public method signatures and behavior MUST be preserved.

---

## Pre-flight checklist (run once before Chunk 1)

- [ ] **Step 0.1: Read the spec end-to-end.** `docs/superpowers/specs/2026-04-28-backup-import-preview-design.md`. The reviewer findings under §"Pre-existing latent bugs flagged but NOT fixed" are intentionally out of scope; do not fix them in this plan.
- [ ] **Step 0.2: Skim the source files** the plan modifies. Don't try to memorize them.
  - `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt` (1615 lines)
  - `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreActivity.kt` (875 lines)
  - `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` (focus on `importFromJson` signature)
  - `src/main/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeys.kt` (full file — small)
- [ ] **Step 0.3: Confirm tests run.** `./gradlew runPureTests` should print `OK (1085 tests)` (current count). If higher, that's fine — record the baseline.
- [ ] **Step 0.4: Confirm baseline build.** `./gradlew compileDebugKotlin` succeeds.

---

## File map

Each row is one unit with one responsibility. New files have one purpose; modifications follow the existing structure.

| Status | File | Responsibility |
|---|---|---|
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/PrefValue.kt` | Sealed class for typed pref values + `EditorOp` + `ScreenMetrics`. Pure data, no Android deps. |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlan.kt` | Plan + change types. Pure data. |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/DictImportPlan.kt` | Plan + `LangWord`. Pure data. |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt` | Pure JSON → `SettingsImportPlan` builder (no Android deps; takes pre-parsed snapshots + `ScreenMetrics`). |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsValidation.kt` | Pure validation rules (full port of `BackupRestoreManager.validatePreferenceValue` + `isInternalPreference`). Shared by IO + pure paths so they cannot diverge. |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportApplier.kt` | Stateless `object` taking `prefs` + `ShortSwipeImporter`; dispatches `EditorOp`s, runs drift detection, calls `editor.commit()`. Replaces the `applyPlanForTest` seam — directly testable without Context. |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/DictImportPlanBuilder.kt` | Pure JSON → `DictImportPlan` builder. |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/DictImportApplier.kt` | Stateless dispatcher mirroring SettingsImportApplier's shape. |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/ShortSwipeImporter.kt` | `interface ShortSwipeImporter { suspend fun importFromJson(json: String, merge: Boolean): Int }` + adapter wrapping the real `ShortSwipeCustomizationManager`. |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/ShortSwipeImportMode.kt` | Enum: `SKIP`/`MERGE`/`REPLACE`. |
| Modified | `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt` | Add 4 new methods that delegate to the new pure modules: `buildSettingsImportPlan`, `applySettingsImportPlan`, `buildDictImportPlan`, `applyDictImportPlan`. Update `exportConfig` to return `Int`, `exportDictionaries` to return existing counts. Existing `importConfig`/`importDictionaries` keep public signatures, internally delegate. |
| New | `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreViewModel.kt` | Holds `settingsPreviewPlan`, `dictPreviewPlan`, `isProcessing` across rotation. |
| New | `src/main/kotlin/tribixbite/cleverkeys/BackupRestorePreviewDialogs.kt` | `SettingsImportPreviewDialog` + `DictionaryImportPreviewDialog` composables + helpers (2-state-with-badge checkbox row, sub-group search). |
| Modified | `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreActivity.kt` | Migrate to ViewModel. Replace `performImport` / `performImportDictionaries` to build-then-show-preview. Result dialog gets new count fields. Export count surfaced. |
| New | `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt` | Pure JVM. |
| New | `src/test/kotlin/tribixbite/cleverkeys/backup/DictImportPlanBuilderTest.kt` | Pure JVM. |
| New | `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanApplyTest.kt` | MockK (mocks `SharedPreferences.Editor`). |
| New | `src/test/kotlin/tribixbite/cleverkeys/backup/DictImportPlanApplyTest.kt` | MockK. |
| New | `src/test/kotlin/tribixbite/cleverkeys/backup/BackupRestoreManagerHeadlessTest.kt` | MockK regression guard for the Termux path. |
| New | `src/androidTest/kotlin/tribixbite/cleverkeys/SettingsImportPreviewDialogComposeTest.kt` | Compose UI. |
| New | `src/androidTest/kotlin/tribixbite/cleverkeys/DictionaryImportPreviewDialogComposeTest.kt` | Compose UI. |
| Modified | `src/androidTest/kotlin/tribixbite/cleverkeys/BackupRestoreActivityComposeTest.kt` | Add empty-deltas-skip + export-count-display tests. |
| Modified | `build.gradle` | Add new pure test classes to `pureTestClasses` list and new MockK classes to `mockTestClasses`. |
| Modified | `memory/todo.md` | Completion entry + hard-won lessons. |

---

## Chunk 1: Foundation data classes + Settings plan builder (pure) + pure tests

This chunk delivers a fully-tested pure JVM module that produces a `SettingsImportPlan` from a JSON string + a current-prefs snapshot + screen metrics. No Android dependencies. After this chunk, you can run `./gradlew runPureTests -PtestClass=SettingsImportPlanBuilderTest` and see green.

### Task 1.1: Create the data classes

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/PrefValue.kt`
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlan.kt`
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/ShortSwipeImportMode.kt`

- [ ] **Step 1.1.1: Create `PrefValue.kt`.**

```kotlin
package tribixbite.cleverkeys.backup

/**
 * Typed preference value used by the import-plan + apply pipeline.
 *
 * Replaces the lossy `String` approach the legacy `importConfig` path used —
 * splitting build (parse + validate + coerce) from apply (pure dispatch)
 * means the apply loop must know the EXACT type to call `editor.put*` with.
 *
 * `LongV` and `StrSet` are deliberately omitted — Config.kt has no `getLong`
 * usage and `isStringSetPreference` always returns `false` for live prefs.
 * If a future pref needs them, add them here and to the apply-loop `when`.
 */
sealed class PrefValue {
    data class Bool(val v: Boolean) : PrefValue()
    data class IntV(val v: Int) : PrefValue()
    data class FloatV(val v: Float) : PrefValue()
    data class Str(val v: String) : PrefValue()
    /** Canonicalized JSON string for layouts / extra_keys / custom_extra_keys. */
    data class JsonBlob(val raw: String) : PrefValue()
    /** The key is absent from current prefs (used for ChangeType.ADDED). */
    object Unset : PrefValue()
}

/**
 * Atomic operation the apply loop executes against a single
 * `SharedPreferences.Editor`.
 */
sealed class EditorOp {
    data class Put(val key: String, val value: PrefValue) : EditorOp()
    data class Remove(val key: String) : EditorOp()
}

/**
 * Screen size + density needed by `migrateLegacyMargins` (dp→px) and the
 * Source vs Current screen-mismatch warning. Passing this to the pure
 * builder lets us avoid `context.resources.displayMetrics`.
 */
data class ScreenMetrics(
    val width: Int,
    val height: Int,
    val density: Float,
)
```

- [ ] **Step 1.1.2: Create `SettingsImportPlan.kt`.**

```kotlin
package tribixbite.cleverkeys.backup

enum class ChangeType { ADDED, MODIFIED }

data class SettingsChange(
    val key: String,
    val current: PrefValue,
    val proposed: PrefValue,
    val type: ChangeType,
)

data class SkippedKey(
    val key: String,
    val reason: String,
)

/**
 * Output of `buildSettingsImportPlan`. Pure data — no Android deps.
 *
 * `internalRemoves` is auto-applied alongside its counterpart `Put` and is
 * NOT user-toggleable. `excludedKeys` passed to `applySettingsImportPlan`
 * filters only `changes` rows.
 */
data class SettingsImportPlan(
    val sourceVersion: String,
    val sourceScreen: ScreenMetrics,
    val currentScreen: ScreenMetrics,
    val changes: List<SettingsChange>,
    val parseSkippedKeys: List<SkippedKey>,
    val internalRemoves: List<String>,
    val shortSwipeImportSize: Int,
    val shortSwipeImportRawJson: String?,
)
```

- [ ] **Step 1.1.3: Create `ShortSwipeImportMode.kt`.**

```kotlin
package tribixbite.cleverkeys.backup

/**
 * UI radio choice for short-swipe import:
 * - SKIP: ignore the file's short-swipe section.
 * - MERGE: additive — file entries fill gaps; existing mappings preserved.
 *   Default in the SAF preview UI.
 * - REPLACE: destructive — wipe existing mappings, install the file's set.
 */
enum class ShortSwipeImportMode { SKIP, MERGE, REPLACE }
```

- [ ] **Step 1.1.4: Verify compile.** `./gradlew compileDebugKotlin` (expect: BUILD SUCCESSFUL).

- [ ] **Step 1.1.5: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/PrefValue.kt \
        src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlan.kt \
        src/main/kotlin/tribixbite/cleverkeys/backup/ShortSwipeImportMode.kt
git commit -m "$(cat <<'EOF'
feat: foundation data classes for BackupRestore import preview

PrefValue sealed class, EditorOp ops, ScreenMetrics, SettingsImportPlan,
ChangeType, ShortSwipeImportMode — pure data, no Android deps. These back
the upcoming buildSettingsImportPlan / applySettingsImportPlan split.

— opus 4.7
EOF
)"
```

### Task 1.2: Failing test — empty-delta plan

**Files:**
- Create: `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt`
- Modify: `build.gradle:337` (register the test class)

- [ ] **Step 1.2.1: Add the test file with one failing test.**

```kotlin
package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsImportPlanBuilderTest {

    private val screen = ScreenMetrics(width = 1080, height = 2400, density = 3.0f)

    @Test
    fun emptyDelta_whenJsonMatchesCurrentSnapshot() {
        val json = """{"metadata":{"app_version":"1.4.0","screen_width":1080,"screen_height":2400},"preferences":{"key_height":50}}"""
        val current = mapOf<String, Any?>("key_height" to 50)

        val plan = SettingsImportPlanBuilder.fromJson(json, current, screen)

        assertThat(plan.changes).isEmpty()
        assertThat(plan.parseSkippedKeys).isEmpty()
        assertThat(plan.internalRemoves).isEmpty()
        assertThat(plan.sourceVersion).isEqualTo("1.4.0")
    }
}
```

- [ ] **Step 1.2.2: Register the test in `build.gradle`.** Insert after the `Issue136MaxSeqLengthClampTest` line:

```groovy
'tribixbite.cleverkeys.backup.SettingsImportPlanBuilderTest',
```

- [ ] **Step 1.2.3: Run the test. Expect compile failure** (`SettingsImportPlanBuilder` not defined).
```bash
./gradlew runPureTests -PtestClass=backup.SettingsImportPlanBuilderTest
```
Expected: `Unresolved reference 'SettingsImportPlanBuilder'`.

### Task 1.3: Minimal `SettingsImportPlanBuilder.fromJson` to pass empty-delta

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt`

- [ ] **Step 1.3.1: Implement minimal builder.**

```kotlin
package tribixbite.cleverkeys.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure JSON → `SettingsImportPlan` builder. No Android deps — all
 * snapshot data is injected by the caller. Mirror in `BackupRestoreManager`
 * is the IO wrapper that supplies the snapshots from real `SharedPreferences`.
 *
 * Build-time responsibilities:
 *  1. Parse + validate (today's `importPreference` logic, lifted out of IO).
 *  2. Run legacy-margin migration BEFORE diffing.
 *  3. Materialize each accepted entry as a typed `PrefValue` so apply is
 *     a pure dispatch loop.
 *  4. Categorize: changes / parseSkippedKeys / internalRemoves.
 */
object SettingsImportPlanBuilder {

    fun fromJson(
        jsonString: String,
        currentSnapshot: Map<String, Any?>,
        screen: ScreenMetrics,
    ): SettingsImportPlan {
        val root = JsonParser.parseString(jsonString).asJsonObject

        val sourceVersion = root.getAsJsonObject("metadata")
            ?.get("app_version")?.asString ?: "unknown"
        val sourceWidth = root.getAsJsonObject("metadata")
            ?.get("screen_width")?.asInt ?: screen.width
        val sourceHeight = root.getAsJsonObject("metadata")
            ?.get("screen_height")?.asInt ?: screen.height

        val sourceScreen = ScreenMetrics(sourceWidth, sourceHeight, screen.density)

        // TODO Task 1.4: implement preference iteration + diff
        return SettingsImportPlan(
            sourceVersion = sourceVersion,
            sourceScreen = sourceScreen,
            currentScreen = screen,
            changes = emptyList(),
            parseSkippedKeys = emptyList(),
            internalRemoves = emptyList(),
            shortSwipeImportSize = 0,
            shortSwipeImportRawJson = null,
        )
    }
}
```

- [ ] **Step 1.3.2: Run the test.**
```bash
./gradlew runPureTests -PtestClass=backup.SettingsImportPlanBuilderTest
```
Expected: PASS.

- [ ] **Step 1.3.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt \
        build.gradle
git commit -m "$(cat <<'EOF'
test: SettingsImportPlanBuilder skeleton + empty-delta case

— opus 4.7
EOF
)"
```

### Task 1.4: Implement preference diff — ADDED + MODIFIED

For each preference key in the JSON, compute its `PrefValue` (typed) and compare against the current snapshot. Build the `changes` list.

- [ ] **Step 1.4.1: Add failing tests for ADDED + MODIFIED + PrefValue variants.**

Append to `SettingsImportPlanBuilderTest.kt`:

```kotlin
    @Test
    fun modifiedKey_intValueChanged() {
        val json = """{"preferences":{"key_height":60}}"""
        val current = mapOf<String, Any?>("key_height" to 50)

        val plan = SettingsImportPlanBuilder.fromJson(json, mapOf("key_height" to 50), screen)

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        assertThat(c.key).isEqualTo("key_height")
        assertThat(c.type).isEqualTo(ChangeType.MODIFIED)
        assertThat(c.current).isEqualTo(PrefValue.IntV(50))
        assertThat(c.proposed).isEqualTo(PrefValue.IntV(60))
    }

    @Test
    fun addedKey_absentInSnapshot() {
        val json = """{"preferences":{"new_key":"hello"}}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.changes).hasSize(1)
        val c = plan.changes.single()
        assertThat(c.type).isEqualTo(ChangeType.ADDED)
        assertThat(c.current).isEqualTo(PrefValue.Unset)
        assertThat(c.proposed).isEqualTo(PrefValue.Str("hello"))
    }

    @Test
    fun prefValueVariants_eachJsonTypeMaterializesCorrectly() {
        val json = """{
            "preferences":{
                "b":true, "i":42, "f":3.14, "s":"hi"
            }
        }""".trimIndent()

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)
        val byKey = plan.changes.associateBy { it.key }

        assertThat(byKey["b"]!!.proposed).isEqualTo(PrefValue.Bool(true))
        assertThat(byKey["i"]!!.proposed).isEqualTo(PrefValue.IntV(42))
        // Gson treats 3.14 as Double — caller decides Float vs Double.
        // Settings uses Float; verify FloatV with the original literal.
        assertThat(byKey["f"]!!.proposed).isEqualTo(PrefValue.FloatV(3.14f))
        assertThat(byKey["s"]!!.proposed).isEqualTo(PrefValue.Str("hi"))
    }

    @Test
    fun jsonBlobPref_layoutsKey_classifiedAsJsonBlob() {
        val rawLayouts = """[{"name":"qwerty"},{"name":"dvorak"}]"""
        val json = """{"preferences":{"layouts":$rawLayouts}}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val c = plan.changes.single { it.key == "layouts" }
        assertThat(c.proposed).isInstanceOf(PrefValue.JsonBlob::class.java)
        // Canonicalized via JsonParser → toString — round-trip equality is the contract.
        val canonical = JsonParser.parseString(rawLayouts).toString()
        assertThat((c.proposed as PrefValue.JsonBlob).raw).isEqualTo(canonical)
    }
```

Also at the top of the file: `import com.google.gson.JsonParser`.

- [ ] **Step 1.4.2: Run — confirm fail.** `./gradlew runPureTests -PtestClass=backup.SettingsImportPlanBuilderTest`.

- [ ] **Step 1.4.3: Implement the diff in `SettingsImportPlanBuilder.fromJson`.** Replace the `TODO Task 1.4` section:

```kotlin
        val preferences: JsonObject = root.getAsJsonObject("preferences")
            ?: return SettingsImportPlan(
                sourceVersion, sourceScreen, screen,
                emptyList(), emptyList(), emptyList(), 0, null
            )

        // List of pref keys treated as JSON-blob (their value is a layout/extra_keys
        // structure stored as a JSON string in SharedPreferences).
        // Source of truth: BackupRestoreManager.isJsonStringPreference (line ~945).
        val jsonBlobKeys = setOf("layouts", "extra_keys", "custom_extra_keys")

        val changes = mutableListOf<SettingsChange>()
        val skipped = mutableListOf<SkippedKey>()

        for ((key, valueElement) in preferences.entrySet()) {
            val proposed = parsePrefValue(key, valueElement, jsonBlobKeys)
            if (proposed == null) {
                skipped += SkippedKey(key, "unsupported JSON shape")
                continue
            }
            val currentRaw = currentSnapshot[key]
            val current = currentToPrefValue(currentRaw, key in jsonBlobKeys)
            if (current == proposed) continue   // no delta — skip

            val type = if (current == PrefValue.Unset) ChangeType.ADDED else ChangeType.MODIFIED
            changes += SettingsChange(key, current, proposed, type)
        }

        return SettingsImportPlan(
            sourceVersion, sourceScreen, screen,
            changes, skipped,
            internalRemoves = emptyList(),
            shortSwipeImportSize = 0,
            shortSwipeImportRawJson = null,
        )
    }

    private fun parsePrefValue(
        key: String,
        element: com.google.gson.JsonElement,
        jsonBlobKeys: Set<String>,
    ): PrefValue? {
        if (key in jsonBlobKeys) {
            // Two source representations: native object/array (new format) or
            // JSON-string primitive (old format, double-encoded). Canonicalize.
            val canonical = when {
                element.isJsonObject || element.isJsonArray -> element.toString()
                element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                    JsonParser.parseString(element.asString).toString()
                else -> return null
            }
            return PrefValue.JsonBlob(canonical)
        }
        if (!element.isJsonPrimitive) return null
        val p = element.asJsonPrimitive
        return when {
            p.isBoolean -> PrefValue.Bool(p.asBoolean)
            p.isNumber -> {
                val n = p.asNumber
                // Heuristic: integer-valued numbers map to IntV; fractional to FloatV.
                // Matches Gson's default — apps that need Float-typed integer values
                // (rare) explicitly mark them; we mirror BackupRestoreManager's
                // existing behavior at importPreference line 658-683.
                if (n.toDouble().let { it == it.toInt().toDouble() }) PrefValue.IntV(n.toInt())
                else PrefValue.FloatV(n.toFloat())
            }
            p.isString -> PrefValue.Str(p.asString)
            else -> null
        }
    }

    private fun currentToPrefValue(value: Any?, isJsonBlob: Boolean): PrefValue {
        if (value == null) return PrefValue.Unset
        return when {
            isJsonBlob && value is String -> PrefValue.JsonBlob(JsonParser.parseString(value).toString())
            value is Boolean -> PrefValue.Bool(value)
            value is Int -> PrefValue.IntV(value)
            value is Float -> PrefValue.FloatV(value)
            value is Long -> PrefValue.IntV(value.toInt())   // see PrefValue header — Long unused in app
            value is String -> PrefValue.Str(value)
            else -> PrefValue.Unset
        }
    }
```

- [ ] **Step 1.4.4: Run — confirm 4 new tests PASS, original passes.**
```bash
./gradlew runPureTests -PtestClass=backup.SettingsImportPlanBuilderTest
```
Expected: `OK (5 tests)`.

- [ ] **Step 1.4.5: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt
git commit -m "feat: settings import plan builder — diff + PrefValue typing — opus 4.7"
```

### Task 1.5: Legacy margin migration in build path

`migrateLegacyMargins` (BackupRestoreManager.kt:876-939) rewrites `horizontal_margin_*` → `margin_left_*` / `margin_right_*` and converts large dp values to percents. The pure builder must run an equivalent transform BEFORE diffing.

- [ ] **Step 1.5.1: Failing test for legacy migration.**

```kotlin
    @Test
    fun legacyHorizontalMargin_synthesizesNewKeysAndQueuesRemove() {
        val json = """{"preferences":{"horizontal_margin_portrait":24}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        // The legacy key never lands in `changes` (user wouldn't see "old key" rows).
        assertThat(plan.changes.map { it.key }).containsExactly("margin_left_portrait", "margin_right_portrait")
        // Both new keys are ADDED (snapshot is empty).
        assertThat(plan.changes.all { it.type == ChangeType.ADDED }).isTrue()
        // The legacy key's removal is queued so apply explicitly drops it from prefs.
        assertThat(plan.internalRemoves).containsExactly("horizontal_margin_portrait")
        // And it's listed in parseSkippedKeys so the user can audit what was dropped.
        assertThat(plan.parseSkippedKeys.map { it.key }).contains("horizontal_margin_portrait")
    }
```

- [ ] **Step 1.5.2: Run — confirm fail.**

- [ ] **Step 1.5.3: Add `migrateLegacyMarginsPure` helper.** Inside `SettingsImportPlanBuilder`:

```kotlin
    /**
     * Pure equivalent of `BackupRestoreManager.migrateLegacyMargins`.
     * - `horizontal_margin_*` → `margin_left_*` + `margin_right_*` (same dp value).
     * - Old key dropped from preferences AND queued in `internalRemoves` so
     *   apply calls `editor.remove(oldKey)` to clean up SharedPreferences.
     * Density is a no-op for this migration (the dp→% legacy in the original
     * also depends on screen height; if your import contains absolute-dp
     * margin_bottom_* > some threshold, convert here using `screen.height`
     * + `screen.density` as the original code does).
     */
    private fun applyLegacyMigration(
        prefs: JsonObject,
        screen: ScreenMetrics,
    ): Pair<JsonObject, MigrationOutputs> {
        val out = JsonObject()
        val removes = mutableListOf<String>()
        val skipped = mutableListOf<SkippedKey>()
        for ((key, value) in prefs.entrySet()) {
            when {
                key == "horizontal_margin_portrait" || key == "horizontal_margin_landscape" -> {
                    val orient = key.removePrefix("horizontal_margin_")
                    if (!prefs.has("margin_left_$orient")) out.add("margin_left_$orient", value)
                    if (!prefs.has("margin_right_$orient")) out.add("margin_right_$orient", value)
                    removes += key
                    skipped += SkippedKey(key, "superseded by margin_left_$orient + margin_right_$orient")
                }
                else -> out.add(key, value)
            }
        }
        return out to MigrationOutputs(removes, skipped)
    }

    private data class MigrationOutputs(
        val removes: List<String>,
        val skipped: List<SkippedKey>,
    )
```

Then update `fromJson` to call the migration BEFORE the iteration loop, and pipe `removes`/`skipped` into the returned plan.

- [ ] **Step 1.5.4: Run — confirm 6 tests pass.**

- [ ] **Step 1.5.5: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt
git commit -m "feat: settings plan migrates horizontal_margin_* + queues old-key removes — opus 4.7"
```

### Task 1.6: Factor `SettingsValidation.kt` — single source of truth

The legacy `importConfig` skips `isInternalPreference` keys (BackupRestoreManager.kt:960-969) and validation-rejects via `validatePreferenceValue` (line 789-1068). To prevent the pure builder and the legacy IO path from drifting, both must call the same validator. Extract validation into a new file.

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsValidation.kt`
- Modify: `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt` (replace `isInternalPreference`/`validatePreferenceValue` bodies with delegation calls)
- Modify: `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt` (call into the validator)

- [ ] **Step 1.6.1: Read all of BackupRestoreManager.kt:789-1068.** Identify every key that has a validation rule. Make a list (write it down — you'll need it).

- [ ] **Step 1.6.2: Read BackupRestoreManager.kt:960-969 (`isInternalPreference`).** Copy the EXACT key list — do not paraphrase.

- [ ] **Step 1.6.3: Create the validator.**

```kotlin
package tribixbite.cleverkeys.backup

/**
 * Single source of truth for SharedPreferences-backup validation rules.
 *
 * The legacy IO-driven import path AND the new pure plan builder both
 * call into here. Adding a rule in one place is enough — diverging means
 * the preview lies about what will land in prefs.
 *
 * Source of truth port: `BackupRestoreManager.validatePreferenceValue` +
 * `validateIntPreference` + `validateFloatPreference` + `validateStringPreference`
 * + `isInternalPreference`. Lines 789-1068 of BackupRestoreManager.kt as of
 * v1.4.0.
 */
object SettingsValidation {

    /**
     * Service-managed state that should never be imported. Copied
     * verbatim from BackupRestoreManager.isInternalPreference — DO NOT
     * truncate. If you add a key to that legacy method, add it here too.
     */
    private val INTERNAL_KEYS: Set<String> = setOf(
        // PASTE the exact set from BackupRestoreManager.kt:960-969 here.
        // The list is the actual implementation — there is no shorter form.
    )

    fun isInternalPreference(key: String): Boolean = key in INTERNAL_KEYS

    /**
     * Returns null if the proposed value is valid for the key, or an
     * error message suitable for `SkippedKey.reason` if not.
     *
     * The rule set is exactly the union of the four `validate*Preference`
     * methods in BackupRestoreManager. Do not abbreviate; copy each rule.
     */
    fun validate(key: String, value: PrefValue): String? {
        // PASTE the full when-block here, structured by key. Each branch
        // returns null for valid or a message for invalid. Structure:
        //
        // return when (key) {
        //     "key_height" -> when {
        //         value !is PrefValue.IntV -> "expected Int, got ${value::class.simpleName}"
        //         value.v !in 20..200 -> "out of range (20..200), got ${value.v}"
        //         else -> null
        //     }
        //     "swipe_min_distance" -> ...
        //     ...etc...
        //     else -> null  // unknown key → caller decides (typically allow)
        // }
        TODO("Port validatePreferenceValue + validateIntPreference + validateFloatPreference + validateStringPreference from BackupRestoreManager.kt:789-1068. The TODO must NOT remain in the final code — every rule must be ported. Compile-time error from the TODO call enforces this.")
    }
}
```

The deliberate `TODO()` call is a hard fail — if you commit without porting all rules, every settings test will throw `NotImplementedError`. CI catches it.

- [ ] **Step 1.6.4: Port every rule from BackupRestoreManager.kt:789-1068 into the `validate` function.** Replace the `TODO("Port…")` with the full when-block. Verify by grepping `BackupRestoreManager.kt` for `validatePreferenceValue`, `validateIntPreference`, `validateFloatPreference`, `validateStringPreference` and confirming every key/branch is represented.

- [ ] **Step 1.6.5: Failing tests for both axes.**

```kotlin
    @Test
    fun internalPreferenceKeys_landInSkippedNotChanges() {
        // Pick THE FIRST key from BackupRestoreManager.isInternalPreference.
        // Update this string if you ported a different first key.
        val json = """{"preferences":{"last_input_method":"foo"}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.changes).isEmpty()
        assertThat(plan.parseSkippedKeys.map { it.key }).contains("last_input_method")
        assertThat(plan.parseSkippedKeys.first { it.key == "last_input_method" }.reason)
            .isEqualTo("internal preference")
    }

    @Test
    fun outOfRangeIntValue_landsInSkipped() {
        // key_height: BackupRestoreManager.validateIntPreference range is bounded;
        // confirm yours matches before relying on the literal range here.
        val json = """{"preferences":{"key_height":99999}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val rejected = plan.parseSkippedKeys.singleOrNull { it.key == "key_height" }
        assertThat(rejected).isNotNull()
        assertThat(rejected!!.reason).contains("out of range")
        assertThat(plan.changes.any { it.key == "key_height" }).isFalse()
    }

    @Test
    fun typeMismatch_landsInSkipped() {
        // Key with int rule receives a string — should reject.
        val json = """{"preferences":{"key_height":"definitely-not-a-number"}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.parseSkippedKeys.any { it.key == "key_height" }).isTrue()
    }

    @Test
    fun perRuleCategoryCoverage_intRangeFloatRangeStringAllowlist() {
        // Concrete keys for each category — pick ONE per category from your
        // actual ported ruleset. These three names come from common settings;
        // replace if your validator's rule set differs.
        val json = """{
            "preferences": {
                "swipe_min_distance": 999999,
                "swipe_trail_width": 9999.0,
                "primary_language": "klingon"
            }
        }""".trimIndent()
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        // All three rejected by their respective validators.
        val rejectedKeys = plan.parseSkippedKeys.map { it.key }.toSet()
        assertThat(rejectedKeys).containsAtLeast(
            "swipe_min_distance", "swipe_trail_width", "primary_language"
        )
    }
```

- [ ] **Step 1.6.6: Confirm fail (NotImplementedError from `TODO()`).**

- [ ] **Step 1.6.7: Wire `SettingsImportPlanBuilder` to call the validator.** In `SettingsImportPlanBuilder.fromJson`, before adding a change:

```kotlin
            if (SettingsValidation.isInternalPreference(key)) {
                skipped += SkippedKey(key, "internal preference")
                continue
            }
            val rangeError = SettingsValidation.validate(key, proposed)
            if (rangeError != null) {
                skipped += SkippedKey(key, rangeError)
                continue
            }
```

- [ ] **Step 1.6.8: Make `BackupRestoreManager.isInternalPreference` and `validatePreferenceValue` delegate.**

```kotlin
private fun isInternalPreference(key: String): Boolean =
    SettingsValidation.isInternalPreference(key)

private fun validatePreferenceValue(key: String, value: Any?): Boolean {
    // Convert Any? → PrefValue, call SettingsValidation.validate, return success.
    val pv = anyToPrefValue(value) ?: return false
    return SettingsValidation.validate(key, pv) == null
}
```

`anyToPrefValue` is a small helper in BackupRestoreManager — Boolean→Bool, Int→IntV, etc.

- [ ] **Step 1.6.9: Run all tests.** `./gradlew runPureTests runMockTests`. Confirm the full pre-existing test suite still passes — the validator port is correct iff old tests still pass.

- [ ] **Step 1.6.10: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/SettingsValidation.kt \
        src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt \
        src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt
git commit -m "feat: factor SettingsValidation.kt as single source of truth — opus 4.7"
```

### Task 1.7: Short-swipe section parse

- [ ] **Step 1.7.1: Failing test.**

```kotlin
    @Test
    fun shortSwipeSection_recordsSizeAndRawJson() {
        val ssJson = """{"q":{"up":"DEL"}}"""
        val json = """{"preferences":{}, "short_swipe_customizations":$ssJson}"""

        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.shortSwipeImportSize).isEqualTo(1)
        // Canonicalized — caller hands `shortSwipeImportRawJson` to ShortSwipeManager.importFromJson.
        assertThat(plan.shortSwipeImportRawJson).isEqualTo(JsonParser.parseString(ssJson).toString())
    }
```

- [ ] **Step 1.7.2: Confirm fail; implement; confirm pass; commit.**

```kotlin
// In fromJson, after the diff loop:
val shortSwipeJson = root.getAsJsonObject("short_swipe_customizations")
val shortSwipeRaw = shortSwipeJson?.toString()
val shortSwipeSize = shortSwipeJson?.entrySet()?.size ?: 0
```

```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt
git commit -m "feat: settings plan captures short-swipe section size + raw JSON — opus 4.7"
```

### Task 1.8: Chunk 1 review

- [ ] **Step 1.8.1: Run full pure suite.** `./gradlew runPureTests` should print `OK (1085+ tests)`.
- [ ] **Step 1.8.2: Smoke-check the build.** `./gradlew compileDebugKotlin` succeeds.
- [ ] **Step 1.8.3: Dispatch the plan-document-reviewer subagent on Chunk 1** as written. Address any issues it raises.

---

## Chunk 2: Settings plan apply (`SettingsImportApplier`) + ShortSwipe injection seam + MockK tests

This chunk extracts apply into its own stateless `object` so MockK can test it directly without constructing a `BackupRestoreManager` (which needs Context). The Manager's new `applySettingsImportPlan` method becomes a thin delegator. The legacy `importConfig(uri, prefs)` keeps its public signature and continues to use `merge=false` for short-swipe (headless Termux callers documented this destructive behavior — the spec preserves it; flipping the default is a separate change tracked in `memory/todo.md`).

### Task 2.1: `ShortSwipeImporter` interface + adapter

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/ShortSwipeImporter.kt`

- [ ] **Step 2.1.1: Create the interface + adapter.**

```kotlin
package tribixbite.cleverkeys.backup

import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager

/**
 * Injection seam for short-swipe importing. Production uses the real
 * `ShortSwipeCustomizationManager`; MockK tests inject a stub.
 *
 * Returns the number of mappings successfully imported.
 */
interface ShortSwipeImporter {
    suspend fun importFromJson(json: String, merge: Boolean): Int
}

class RealShortSwipeImporter(
    private val manager: ShortSwipeCustomizationManager,
) : ShortSwipeImporter {
    override suspend fun importFromJson(json: String, merge: Boolean): Int =
        manager.importFromJson(json, merge = merge)
}
```

- [ ] **Step 2.1.2: Compile.** `./gradlew compileDebugKotlin` succeeds.

- [ ] **Step 2.1.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/ShortSwipeImporter.kt
git commit -m "feat: ShortSwipeImporter interface for testable injection — opus 4.7"
```

### Task 2.2: `ImportResult` schema additions

**Files:**
- Modify: `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt` — find `data class ImportResult` (~line 1070).

- [ ] **Step 2.2.1: Add the new fields.**

```kotlin
data class ImportResult(
    @JvmField var importedCount: Int = 0,
    @JvmField var skippedCount: Int = 0,
    @JvmField var excludedByUserCount: Int = 0,    // NEW: user-deselected in preview
    @JvmField var driftCount: Int = 0,              // NEW: changed between build and apply
    @JvmField var sourceVersion: String = "unknown",
    @JvmField var sourceScreenWidth: Int = 0,
    @JvmField var sourceScreenHeight: Int = 0,
    @JvmField var currentScreenWidth: Int = 0,
    @JvmField var currentScreenHeight: Int = 0,
    @JvmField var importedKeys: MutableList<String> = mutableListOf(),
    @JvmField var skippedKeys: MutableList<String> = mutableListOf(),
    @JvmField var shortSwipeCustomizationsImported: Int = 0,
    // ... preserve any other existing fields you find in the live file
)
```

- [ ] **Step 2.2.2: Compile.**
- [ ] **Step 2.2.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt
git commit -m "feat: ImportResult schema gains excludedByUserCount + driftCount — opus 4.7"
```

### Task 2.3: Failing tests for `SettingsImportApplier` — basic dispatch

**Files:**
- Create: `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanApplyTest.kt`
- Modify: `build.gradle` — register `tribixbite.cleverkeys.backup.SettingsImportPlanApplyTest` in `mockTestClasses` list (~line 405).

- [ ] **Step 2.3.1: Add the test file with full constructor calls.** No placeholders.

```kotlin
package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class SettingsImportPlanApplyTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var ssImporter: ShortSwipeImporter

    private val screen = ScreenMetrics(1080, 2400, 3.0f)

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true
        every { prefs.all } returns emptyMap()
        ssImporter = mockk(relaxed = true)
        coEvery { ssImporter.importFromJson(any(), any()) } returns 0
    }

    private fun planWith(vararg changes: SettingsChange) = SettingsImportPlan(
        sourceVersion = "1.4.0",
        sourceScreen = screen,
        currentScreen = screen,
        changes = changes.toList(),
        parseSkippedKeys = emptyList(),
        internalRemoves = emptyList(),
        shortSwipeImportSize = 0,
        shortSwipeImportRawJson = null,
    )

    @Test
    fun apply_eachPrefValueVariant_dispatchesCorrectEditorMethod() {
        val plan = planWith(
            SettingsChange("b", PrefValue.Unset, PrefValue.Bool(true), ChangeType.ADDED),
            SettingsChange("i", PrefValue.Unset, PrefValue.IntV(42), ChangeType.ADDED),
            SettingsChange("f", PrefValue.Unset, PrefValue.FloatV(3.14f), ChangeType.ADDED),
            SettingsChange("s", PrefValue.Unset, PrefValue.Str("hi"), ChangeType.ADDED),
            SettingsChange("layouts", PrefValue.Unset, PrefValue.JsonBlob("[]"), ChangeType.ADDED),
        )

        val result = runBlocking {
            SettingsImportApplier.apply(
                plan = plan,
                excludedKeys = emptySet(),
                shortSwipeMode = ShortSwipeImportMode.SKIP,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }

        verify(exactly = 1) { editor.putBoolean("b", true) }
        verify(exactly = 1) { editor.putInt("i", 42) }
        verify(exactly = 1) { editor.putFloat("f", 3.14f) }
        verify(exactly = 1) { editor.putString("s", "hi") }
        verify(exactly = 1) { editor.putString("layouts", "[]") }
        verify(exactly = 1) { editor.commit() }
        assertThat(result.importedCount).isEqualTo(5)
        assertThat(result.excludedByUserCount).isEqualTo(0)
        coVerify(exactly = 0) { ssImporter.importFromJson(any(), any()) }
    }
}
```

- [ ] **Step 2.3.2: Register in `build.gradle`** under `mockTestClasses` (after the last entry):
```groovy
'tribixbite.cleverkeys.backup.SettingsImportPlanApplyTest',
```

- [ ] **Step 2.3.3: Run — confirm fail** (`SettingsImportApplier` not defined).
```bash
./gradlew runMockTests -PtestClass=backup.SettingsImportPlanApplyTest
```
Expected: `Unresolved reference 'SettingsImportApplier'`.

### Task 2.4: Implement `SettingsImportApplier`

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportApplier.kt`

- [ ] **Step 2.4.1: Implement the applier.**

```kotlin
package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.JsonParser
import tribixbite.cleverkeys.BackupRestoreManager.ImportResult

/**
 * Stateless apply step for `SettingsImportPlan`.
 *
 * Owns the rules:
 *   1. ONE editor + ONE editor.commit() — atomic write, honest "applied: N" count.
 *   2. Drift detection: snapshot prefs.all again right before commit; for every
 *      un-excluded key, compare to plan.current. Log drift count if > 0; the
 *      user's selection is honored regardless.
 *   3. Internal removes always execute (not user-toggleable).
 *   4. Short-swipe is delegated through `ShortSwipeImporter` so tests inject
 *      a stub and the real path uses `RealShortSwipeImporter`.
 */
object SettingsImportApplier {

    private const val TAG = "SettingsImportApplier"

    suspend fun apply(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
        prefs: SharedPreferences,
        shortSwipeImporter: ShortSwipeImporter,
    ): ImportResult {
        val result = ImportResult().apply {
            sourceVersion = plan.sourceVersion
            sourceScreenWidth = plan.sourceScreen.width
            sourceScreenHeight = plan.sourceScreen.height
            currentScreenWidth = plan.currentScreen.width
            currentScreenHeight = plan.currentScreen.height
        }

        val editor = prefs.edit()
        val nowBeforeCommit: Map<String, Any?> = prefs.all
        var driftCount = 0

        for (change in plan.changes) {
            if (change.key in excludedKeys) {
                result.excludedByUserCount++
                continue
            }
            if (!planCurrentMatchesNow(change.current, nowBeforeCommit[change.key])) {
                driftCount++
            }
            dispatchPut(editor, change.key, change.proposed)
            result.importedCount++
            result.importedKeys.add(change.key)
        }

        for (legacyKey in plan.internalRemoves) {
            editor.remove(legacyKey)
        }

        if (shortSwipeMode != ShortSwipeImportMode.SKIP && plan.shortSwipeImportRawJson != null) {
            val merge = (shortSwipeMode == ShortSwipeImportMode.MERGE)
            val ssCount = shortSwipeImporter.importFromJson(plan.shortSwipeImportRawJson, merge = merge)
            result.shortSwipeCustomizationsImported = ssCount
        }

        result.skippedCount = plan.parseSkippedKeys.size
        plan.parseSkippedKeys.forEach { result.skippedKeys.add(it.key) }
        result.driftCount = driftCount

        if (!editor.commit()) {
            Log.w(TAG, "editor.commit() returned false — disk full or IPC failure")
        }
        if (driftCount > 0) {
            Log.i(TAG, "BackupRestore: $driftCount keys drifted between preview and apply")
        }
        return result
    }

    private fun dispatchPut(editor: SharedPreferences.Editor, key: String, value: PrefValue) {
        when (value) {
            is PrefValue.Bool -> editor.putBoolean(key, value.v)
            is PrefValue.IntV -> editor.putInt(key, value.v)
            is PrefValue.FloatV -> editor.putFloat(key, value.v)
            is PrefValue.Str -> editor.putString(key, value.v)
            is PrefValue.JsonBlob -> editor.putString(key, value.raw)
            PrefValue.Unset -> error(
                "PrefValue.Unset is illegal inside dispatchPut — use plan.internalRemoves or excludedKeys instead"
            )
        }
    }

    private fun planCurrentMatchesNow(planCurrent: PrefValue, now: Any?): Boolean {
        if (planCurrent == PrefValue.Unset) return now == null
        return when (planCurrent) {
            is PrefValue.Bool -> now == planCurrent.v
            is PrefValue.IntV -> now == planCurrent.v
            is PrefValue.FloatV -> now == planCurrent.v
            is PrefValue.Str -> now == planCurrent.v
            is PrefValue.JsonBlob -> now is String &&
                JsonParser.parseString(now).toString() == planCurrent.raw
            PrefValue.Unset -> false   // unreachable; guard above
        }
    }
}
```

- [ ] **Step 2.4.2: Run the test.**
```bash
./gradlew runMockTests -PtestClass=backup.SettingsImportPlanApplyTest
```
Expected: PASS.

- [ ] **Step 2.4.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportApplier.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanApplyTest.kt \
        build.gradle
git commit -m "feat: SettingsImportApplier with single commit + drift detection — opus 4.7"
```

### Task 2.5: Exclusion + internal removes + drift-count tests

- [ ] **Step 2.5.1: Add tests with full constructor calls.**

```kotlin
    @Test
    fun apply_omitsExcludedKeys() {
        val plan = planWith(
            SettingsChange("a", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED),
            SettingsChange("b", PrefValue.Unset, PrefValue.IntV(2), ChangeType.ADDED),
        )

        val result = runBlocking {
            SettingsImportApplier.apply(
                plan = plan,
                excludedKeys = setOf("a"),
                shortSwipeMode = ShortSwipeImportMode.SKIP,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }

        verify(exactly = 0) { editor.putInt("a", any()) }
        verify(exactly = 1) { editor.putInt("b", 2) }
        assertThat(result.importedCount).isEqualTo(1)
        assertThat(result.excludedByUserCount).isEqualTo(1)
    }

    @Test
    fun apply_executesInternalRemovesEvenWhenChangesEmpty() {
        val plan = SettingsImportPlan(
            sourceVersion = "1.4.0",
            sourceScreen = screen,
            currentScreen = screen,
            changes = emptyList(),
            parseSkippedKeys = emptyList(),
            internalRemoves = listOf("legacy_key"),
            shortSwipeImportSize = 0,
            shortSwipeImportRawJson = null,
        )

        runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

        verify(exactly = 1) { editor.remove("legacy_key") }
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun apply_driftCount_incrementsWhenCurrentChangedSinceBuild() {
        // Plan recorded current value = 50 at build time. By the time apply runs,
        // prefs.all returns 99 — drift!
        every { prefs.all } returns mapOf("key_height" to 99)
        val plan = planWith(
            SettingsChange("key_height", PrefValue.IntV(50), PrefValue.IntV(60), ChangeType.MODIFIED)
        )

        val result = runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

        // The user's selection is still honored — value 60 is still written.
        verify(exactly = 1) { editor.putInt("key_height", 60) }
        // But drift is logged.
        assertThat(result.driftCount).isEqualTo(1)
    }

    @Test
    fun apply_unsetSentinelInDispatch_throws() {
        // PrefValue.Unset must never reach dispatchPut — internalRemoves is the
        // correct path. Guard against a future bug where someone wires Unset
        // into a Put op.
        val plan = planWith(
            SettingsChange("k", PrefValue.Unset, PrefValue.Unset, ChangeType.ADDED)
        )

        try {
            runBlocking {
                SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
            }
            assert(false) { "Expected IllegalStateException" }
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("PrefValue.Unset is illegal")
        }
    }
```

- [ ] **Step 2.5.2: Run — confirm pass.**

- [ ] **Step 2.5.3: Commit.**
```bash
git add src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanApplyTest.kt
git commit -m "test: SettingsImportApplier exclusion + removes + drift coverage — opus 4.7"
```

### Task 2.6: Short-swipe routing tests (SKIP / MERGE / REPLACE)

- [ ] **Step 2.6.1: Add tests.**

```kotlin
    private fun planWithShortSwipe(rawJson: String, size: Int = 1) = SettingsImportPlan(
        sourceVersion = "1.4.0",
        sourceScreen = screen,
        currentScreen = screen,
        changes = emptyList(),
        parseSkippedKeys = emptyList(),
        internalRemoves = emptyList(),
        shortSwipeImportSize = size,
        shortSwipeImportRawJson = rawJson,
    )

    @Test
    fun apply_shortSwipeSkip_doesNotCallImporter() {
        runBlocking {
            SettingsImportApplier.apply(
                plan = planWithShortSwipe("""{"q":{"up":"DEL"}}"""),
                excludedKeys = emptySet(),
                shortSwipeMode = ShortSwipeImportMode.SKIP,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }
        coVerify(exactly = 0) { ssImporter.importFromJson(any(), any()) }
    }

    @Test
    fun apply_shortSwipeMerge_invokesImporterWithMergeTrue() {
        coEvery { ssImporter.importFromJson(any(), merge = true) } returns 3
        val result = runBlocking {
            SettingsImportApplier.apply(
                plan = planWithShortSwipe("""{"q":{"up":"DEL"}}"""),
                excludedKeys = emptySet(),
                shortSwipeMode = ShortSwipeImportMode.MERGE,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }
        coVerify(exactly = 1) { ssImporter.importFromJson(any(), merge = true) }
        assertThat(result.shortSwipeCustomizationsImported).isEqualTo(3)
    }

    @Test
    fun apply_shortSwipeReplace_invokesImporterWithMergeFalse() {
        coEvery { ssImporter.importFromJson(any(), merge = false) } returns 2
        runBlocking {
            SettingsImportApplier.apply(
                plan = planWithShortSwipe("""{"q":{"up":"DEL"}}"""),
                excludedKeys = emptySet(),
                shortSwipeMode = ShortSwipeImportMode.REPLACE,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }
        coVerify(exactly = 1) { ssImporter.importFromJson(any(), merge = false) }
    }

    @Test
    fun apply_shortSwipeNullRawJson_skipsRegardlessOfMode() {
        val plan = planWith()   // no short-swipe at all
        runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.MERGE, prefs, ssImporter)
        }
        coVerify(exactly = 0) { ssImporter.importFromJson(any(), any()) }
    }
```

- [ ] **Step 2.6.2: Run; pass; commit.**
```bash
git add src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanApplyTest.kt
git commit -m "test: SettingsImportApplier short-swipe routing for SKIP/MERGE/REPLACE — opus 4.7"
```

### Task 2.7: Wire `BackupRestoreManager.applySettingsImportPlan` thin delegator

**Files:**
- Modify: `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt`

- [ ] **Step 2.7.1: Add the field + delegator method.**

```kotlin
class BackupRestoreManager(
    private val context: Context,
    // Default uses the real ShortSwipeCustomizationManager; instrumented
    // tests can pass a stub via the secondary constructor.
    private val shortSwipeImporter: ShortSwipeImporter = RealShortSwipeImporter(
        ShortSwipeCustomizationManager.getInstance(context)
    ),
) {
    // ... existing code ...

    fun applySettingsImportPlan(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
        prefs: SharedPreferences,
    ): ImportResult = runBlocking {
        SettingsImportApplier.apply(
            plan, excludedKeys, shortSwipeMode, prefs, shortSwipeImporter
        )
    }
}
```

- [ ] **Step 2.7.2: Build.** `./gradlew compileDebugKotlin` succeeds.
- [ ] **Step 2.7.3: Commit.**

### Task 2.8: Build IO wrapper `buildSettingsImportPlan`

**Files:**
- Modify: `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt`

- [ ] **Step 2.8.1: Add the method.**

```kotlin
fun buildSettingsImportPlan(uri: Uri, prefs: SharedPreferences): SettingsImportPlan {
    val jsonString = readJsonFromUri(uri)
    val snapshot: Map<String, Any?> = prefs.all.toMap()
    val dm = context.resources.displayMetrics
    val screen = ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.density)
    return SettingsImportPlanBuilder.fromJson(jsonString, snapshot, screen)
}
```

- [ ] **Step 2.8.2: Build; commit.**

### Task 2.9: Wire legacy `importConfig` to delegate

The headless Termux callers depend on:
1. The public method signature `importConfig(uri, prefs): ImportResult`
2. The current short-swipe behavior (`merge=false` — destructive replace, BackupRestoreManager.kt:597)

We preserve both. Per the spec's pre-existing-bug list, flipping the headless default to `merge=true` is intentionally out of scope — it's a behavior change that automation users may rely on.

- [ ] **Step 2.9.1: Modify `importConfig`.**

```kotlin
fun importConfig(uri: Uri, prefs: SharedPreferences): ImportResult {
    val plan = buildSettingsImportPlan(uri, prefs)
    // Headless default: REPLACE (preserves legacy `merge=false` semantics
    // documented in `memory/todo.md` as a pre-existing latent behavior;
    // users running Termux automation rely on this).
    // SAF-flow default is MERGE — see BackupRestoreActivity.applyPlannedSettings.
    return applySettingsImportPlan(plan, emptySet(), ShortSwipeImportMode.REPLACE, prefs)
}
```

- [ ] **Step 2.9.2: Add headless regression test.** Create `src/test/kotlin/tribixbite/cleverkeys/backup/BackupRestoreManagerHeadlessTest.kt`:

```kotlin
package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.Before
import org.junit.Test

class BackupRestoreManagerHeadlessTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true
        every { prefs.all } returns emptyMap()
    }

    @Test
    fun headlessImportConfig_usesShortSwipeReplaceMode() {
        // Verify the default short-swipe mode for the headless entry point
        // matches the legacy destructive `merge=false` behavior. Changing
        // this default would break Termux automation users — keep this test
        // green by NOT modifying importConfig's mode unless coordinated.
        val ssImporter: ShortSwipeImporter = mockk(relaxed = true) {
            coEvery { importFromJson(any(), any()) } returns 0
        }

        val plan = SettingsImportPlan(
            sourceVersion = "1.4.0",
            sourceScreen = ScreenMetrics(1080, 2400, 3.0f),
            currentScreen = ScreenMetrics(1080, 2400, 3.0f),
            changes = emptyList(),
            parseSkippedKeys = emptyList(),
            internalRemoves = emptyList(),
            shortSwipeImportSize = 1,
            shortSwipeImportRawJson = """{"q":{"up":"DEL"}}""",
        )

        kotlinx.coroutines.runBlocking {
            SettingsImportApplier.apply(
                plan, emptySet(), ShortSwipeImportMode.REPLACE, prefs, ssImporter
            )
        }

        coVerify(exactly = 1) { ssImporter.importFromJson(any(), merge = false) }
    }
}
```

Register in `build.gradle` `mockTestClasses`.

- [ ] **Step 2.9.3: Run all tests, all green.** `./gradlew runPureTests runMockTests`.
- [ ] **Step 2.9.4: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/BackupRestoreManagerHeadlessTest.kt \
        build.gradle
git commit -m "feat: importConfig delegates to build/apply, preserves legacy short-swipe mode — opus 4.7"
```

### Task 2.10: Chunk 2 review

- [ ] **Step 2.10.1: Run all tests.** `./gradlew runPureTests runMockTests`. Pure: 1085 baseline + ~14 new = ~1099. Mock: ~9 new added.
- [ ] **Step 2.10.2: Dispatch plan-document-reviewer for Chunk 2.**

---

## Chunk 3: Dictionary plan builder + applier + tests

Same shape as Chunks 1+2 but for dictionary import. Three-source priority (v2 ⊃ legacy `custom_words` ⊃ legacy `user_words`) is the showstopper detail; tests must lock it in via `LinkedHashMap.putIfAbsent`. Apply uses ONE editor + ONE `editor.commit()` for atomicity (replaces today's 4 separate `apply()` calls at BackupRestoreManager.kt:1248, 1271, 1298, 1329).

### Task 3.1: Data classes (`DictImportPlan.kt`)

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/DictImportPlan.kt`

- [ ] **Step 3.1.1: Create the file.**

```kotlin
package tribixbite.cleverkeys.backup

/**
 * (lang, word) pair — the unit of selection in the dictionary preview UI.
 * Case-sensitive on purpose (matches existing `importDictionaries` behavior
 * — "foo" and "FOO" are distinct entries today).
 */
data class LangWord(val lang: String, val word: String)

/**
 * Per-language deltas — only words/disabled entries that are NOT already
 * present in the user's current prefs.
 */
data class LangChanges(
    val newCustomWords: Map<String, Int>,
    val newDisabledWords: List<String>,
)

/**
 * Output of `buildDictImportPlan`. Pure data.
 *
 * `mergedCustomWordsByLang` and `mergedDisabledWordsByLang` carry the FULL
 * parsed merge result (v2 + legacy combined, first-writer-wins) so apply
 * doesn't re-read the URI. `perLanguage` is the deltas-only view used by
 * the preview UI.
 */
data class DictImportPlan(
    val sourceVersion: String,
    val perLanguage: Map<String, LangChanges>,
    val mergedCustomWordsByLang: Map<String, Map<String, Int>>,
    val mergedDisabledWordsByLang: Map<String, Set<String>>,
)
```

- [ ] **Step 3.1.2: Compile.** `./gradlew compileDebugKotlin`.
- [ ] **Step 3.1.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/DictImportPlan.kt
git commit -m "feat: DictImportPlan data classes for dictionary import preview — opus 4.7"
```

### Task 3.2: Failing test — v2 format happy path

**Files:**
- Create: `src/test/kotlin/tribixbite/cleverkeys/backup/DictImportPlanBuilderTest.kt`
- Modify: `build.gradle` — add `tribixbite.cleverkeys.backup.DictImportPlanBuilderTest` to `pureTestClasses`.

- [ ] **Step 3.2.1: Add the test file.**

```kotlin
package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DictImportPlanBuilderTest {

    @Test
    fun v2Format_perLanguageDeltas() {
        val json = """{"custom_words_by_language":{"en":{"foo":50,"bar":100}}}"""

        val plan = DictImportPlanBuilder.fromJson(
            jsonString = json,
            currentCustomByLang = emptyMap(),
            currentDisabledByLang = emptyMap(),
        )

        val en = plan.perLanguage["en"]
        assertThat(en).isNotNull()
        assertThat(en!!.newCustomWords).containsEntry("foo", 50)
        assertThat(en.newCustomWords).containsEntry("bar", 100)
        assertThat(en.newCustomWords).hasSize(2)
    }
}
```

- [ ] **Step 3.2.2: Register in `build.gradle`.**
- [ ] **Step 3.2.3: Run — confirm compile failure** (`DictImportPlanBuilder` not defined).
```bash
./gradlew runPureTests -PtestClass=backup.DictImportPlanBuilderTest
```

### Task 3.3: Minimal builder (v2 only, no current-prefs filter)

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/DictImportPlanBuilder.kt`

- [ ] **Step 3.3.1: Implement.**

```kotlin
package tribixbite.cleverkeys.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure JSON → `DictImportPlan` builder. No Android deps.
 *
 * Three-source merge priority (matches the legacy `importDictionaries`
 * `!containsKey` semantics):
 *   1. v2 `custom_words_by_language`
 *   2. legacy `custom_words` (routed to English)
 *   3. legacy `user_words` (routed to English)
 *
 * First-writer-wins via `LinkedHashMap.putIfAbsent`. `Map.plus` would give
 * last-writer-wins — opposite of current behavior — so don't refactor to that.
 */
object DictImportPlanBuilder {

    fun fromJson(
        jsonString: String,
        currentCustomByLang: Map<String, Map<String, Int>>,
        currentDisabledByLang: Map<String, Set<String>>,
    ): DictImportPlan {
        val root = JsonParser.parseString(jsonString).asJsonObject
        val sourceVersion = root.getAsJsonObject("metadata")
            ?.get("app_version")?.asString ?: "unknown"

        val mergedCustom = mergeCustomWords(root)
        val mergedDisabled = mergeDisabledWords(root)

        // Compute deltas: subtract current state from merged.
        val perLanguage = HashMap<String, LangChanges>()
        for ((lang, words) in mergedCustom) {
            val current = currentCustomByLang[lang] ?: emptyMap()
            val newOnly = words.filterKeys { it !in current }
            val currentDisabled = currentDisabledByLang[lang] ?: emptySet()
            val newDisabled = (mergedDisabled[lang] ?: emptySet())
                .filter { it !in currentDisabled }
            if (newOnly.isNotEmpty() || newDisabled.isNotEmpty()) {
                perLanguage[lang] = LangChanges(newOnly, newDisabled)
            }
        }
        // Languages that have ONLY disabled deltas (no custom)
        for ((lang, disabled) in mergedDisabled) {
            if (lang in perLanguage) continue
            val currentDisabled = currentDisabledByLang[lang] ?: emptySet()
            val newDisabled = disabled.filter { it !in currentDisabled }
            if (newDisabled.isNotEmpty()) {
                perLanguage[lang] = LangChanges(emptyMap(), newDisabled)
            }
        }

        return DictImportPlan(
            sourceVersion = sourceVersion,
            perLanguage = perLanguage,
            mergedCustomWordsByLang = mergedCustom,
            mergedDisabledWordsByLang = mergedDisabled,
        )
    }

    private fun mergeCustomWords(root: JsonObject): Map<String, Map<String, Int>> {
        // LinkedHashMap preserves insertion order — first-writer-wins via
        // putIfAbsent. Per-language LinkedHashMap so within a language, the
        // first source's frequency wins.
        val merged: MutableMap<String, MutableMap<String, Int>> = LinkedHashMap()

        // 1. v2 first
        root.getAsJsonObject("custom_words_by_language")?.entrySet()?.forEach { (lang, wordsEl) ->
            if (wordsEl.isJsonObject) {
                val map = merged.getOrPut(lang) { LinkedHashMap() }
                wordsEl.asJsonObject.entrySet().forEach { (w, freqEl) ->
                    map.putIfAbsent(w, freqEl.asInt)
                }
            }
        }

        // 2. legacy custom_words → English
        root.getAsJsonObject("custom_words")?.entrySet()?.forEach { (w, freqEl) ->
            val map = merged.getOrPut("en") { LinkedHashMap() }
            map.putIfAbsent(w, freqEl.asInt)
        }

        // 3. legacy user_words → English (array of {word, frequency} objects)
        root.getAsJsonArray("user_words")?.forEach { entry ->
            if (!entry.isJsonObject) return@forEach
            val obj = entry.asJsonObject
            val word = obj.get("word")?.asString ?: return@forEach
            val freq = obj.get("frequency")?.asInt ?: 1
            val map = merged.getOrPut("en") { LinkedHashMap() }
            map.putIfAbsent(word, freq)
        }

        return merged
    }

    private fun mergeDisabledWords(root: JsonObject): Map<String, Set<String>> {
        val merged: MutableMap<String, LinkedHashSet<String>> = LinkedHashMap()

        // 1. v2 disabled_words_by_language
        root.getAsJsonObject("disabled_words_by_language")?.entrySet()?.forEach { (lang, wordsEl) ->
            if (wordsEl.isJsonArray) {
                val set = merged.getOrPut(lang) { LinkedHashSet() }
                wordsEl.asJsonArray.forEach { set.add(it.asString) }
            }
        }

        // 2. legacy disabled_words → English
        root.getAsJsonArray("disabled_words")?.forEach {
            val set = merged.getOrPut("en") { LinkedHashSet() }
            set.add(it.asString)
        }

        return merged
    }
}
```

- [ ] **Step 3.3.2: Run — confirm test passes.**

- [ ] **Step 3.3.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/DictImportPlanBuilder.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/DictImportPlanBuilderTest.kt \
        build.gradle
git commit -m "feat: DictImportPlanBuilder with three-source merge — opus 4.7"
```

### Task 3.4: First-writer-wins guard test

- [ ] **Step 3.4.1: Add test.**

```kotlin
    @Test
    fun mixed_v2AndLegacy_firstWriterWins() {
        // Same word "foo" appears in all three sources with different
        // frequencies. Legacy code achieves first-writer-wins via the
        // !containsKey check at BackupRestoreManager.kt:1242 — the new
        // builder must replicate exactly via LinkedHashMap.putIfAbsent.
        val json = """{
            "custom_words_by_language":{"en":{"foo":10}},
            "custom_words":{"foo":20},
            "user_words":[{"word":"foo","frequency":30}]
        }""".trimIndent()

        val plan = DictImportPlanBuilder.fromJson(
            jsonString = json,
            currentCustomByLang = emptyMap(),
            currentDisabledByLang = emptyMap(),
        )

        val en = plan.perLanguage["en"]!!
        assertThat(en.newCustomWords["foo"]).isEqualTo(10)
        assertThat(en.newCustomWords).hasSize(1)
        // mergedCustomWordsByLang should also reflect this
        assertThat(plan.mergedCustomWordsByLang["en"]).containsExactly("foo", 10)
    }
```

- [ ] **Step 3.4.2: Run — should already pass with the implementation from Task 3.3.** If it fails, the merge logic is wrong; review Step 3.3.1.

- [ ] **Step 3.4.3: Commit.**

### Task 3.5: Existing-word filtering

- [ ] **Step 3.5.1: Add test.**

```kotlin
    @Test
    fun existingWord_filteredFromDeltas() {
        val json = """{"custom_words_by_language":{"en":{"foo":10,"bar":20}}}"""
        val current = mapOf("en" to mapOf("foo" to 5))   // user already has "foo"

        val plan = DictImportPlanBuilder.fromJson(json, current, emptyMap())

        val en = plan.perLanguage["en"]!!
        assertThat(en.newCustomWords).doesNotContainKey("foo")    // filtered
        assertThat(en.newCustomWords).containsKey("bar")           // genuinely new
    }
```

- [ ] **Step 3.5.2: Already passing with current implementation; verify and commit.**

### Task 3.6: Legacy disabled-words → English

- [ ] **Step 3.6.1: Add test.**

```kotlin
    @Test
    fun legacyDisabledWords_routedToEnglish() {
        val json = """{"disabled_words":["bad","words"]}"""

        val plan = DictImportPlanBuilder.fromJson(json, emptyMap(), emptyMap())

        val en = plan.perLanguage["en"]!!
        assertThat(en.newDisabledWords).containsExactly("bad", "words")
        assertThat(en.newCustomWords).isEmpty()
    }
```

- [ ] **Step 3.6.2: Verify passes; commit.**

### Task 3.7: Case-sensitivity test

- [ ] **Step 3.7.1: Add test (locks in current behavior).**

```kotlin
    @Test
    fun caseDifferentVariants_renderAsDistinctEntries() {
        // "foo" and "FOO" are distinct in current importDictionaries —
        // preserved here so the preview UI shows them as two rows.
        val json = """{"custom_words_by_language":{"en":{"foo":10,"FOO":20}}}"""

        val plan = DictImportPlanBuilder.fromJson(json, emptyMap(), emptyMap())

        val en = plan.perLanguage["en"]!!
        assertThat(en.newCustomWords).containsExactly("foo", 10, "FOO", 20)
    }
```

- [ ] **Step 3.7.2: Verify passes; commit.**

### Task 3.8: `DictImportApplier` — failing test for atomic commit

**Files:**
- Create: `src/test/kotlin/tribixbite/cleverkeys/backup/DictImportPlanApplyTest.kt`

- [ ] **Step 3.8.1: Add the test file.**

```kotlin
package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.Before
import org.junit.Test

class DictImportPlanApplyTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true
        // Default snapshots — empty current state
        every { prefs.getString(any(), any()) } returns "{}"
        every { prefs.getStringSet(any(), any()) } returns emptySet()
    }

    @Test
    fun apply_singleCommit_regardlessOfLanguageCount() {
        // Three languages — legacy code would call apply() three times
        // (BackupRestoreManager.kt:1248). New applier MUST coalesce to one commit().
        val plan = DictImportPlan(
            sourceVersion = "1.4.0",
            perLanguage = mapOf(
                "en" to LangChanges(mapOf("foo" to 10), emptyList()),
                "de" to LangChanges(mapOf("hallo" to 20), emptyList()),
                "fr" to LangChanges(mapOf("bonjour" to 30), emptyList()),
            ),
            mergedCustomWordsByLang = mapOf(
                "en" to mapOf("foo" to 10),
                "de" to mapOf("hallo" to 20),
                "fr" to mapOf("bonjour" to 30),
            ),
            mergedDisabledWordsByLang = emptyMap(),
        )

        DictImportApplier.apply(
            plan = plan,
            excludedCustom = emptySet(),
            excludedDisabled = emptySet(),
            prefs = prefs,
        )

        verify(exactly = 1) { editor.commit() }
        verify(exactly = 3) { editor.putString(any(), any()) }   // 3 langs × custom
    }
}
```

Register in `build.gradle` `mockTestClasses`.

- [ ] **Step 3.8.2: Run — confirm fail** (`DictImportApplier` not defined).

### Task 3.9: Implement `DictImportApplier`

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/backup/DictImportApplier.kt`

- [ ] **Step 3.9.1: Implement.**

```kotlin
package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import tribixbite.cleverkeys.LanguagePreferenceKeys

/**
 * Stateless apply step for `DictImportPlan`.
 *
 * Atomicity contract: ONE editor + ONE `editor.commit()` regardless of
 * language count. Replaces the legacy `importDictionaries` flow which
 * called `editor.apply()` separately per language (BackupRestoreManager.kt
 * lines 1248, 1271, 1298, 1329) — that's a pre-existing partial-state risk
 * we get to fix as a side-effect.
 */
object DictImportApplier {

    private val gson = Gson()
    private val mapType = object : TypeToken<MutableMap<String, Int>>() {}.type

    /**
     * Returns count summary as `Pair<customApplied, disabledApplied>`.
     * Caller wraps into the project's `DictionaryImportResult` type.
     */
    fun apply(
        plan: DictImportPlan,
        excludedCustom: Set<LangWord>,
        excludedDisabled: Set<LangWord>,
        prefs: SharedPreferences,
    ): Pair<Int, Int> {
        val editor = prefs.edit()
        var customApplied = 0
        var disabledApplied = 0

        // Custom words: read-modify-write per language, but write goes through
        // the SAME editor so commit() is atomic across all languages.
        for ((lang, words) in plan.mergedCustomWordsByLang) {
            val key = LanguagePreferenceKeys.customWordsKey(lang)
            val existing: MutableMap<String, Int> = try {
                gson.fromJson(prefs.getString(key, "{}"), mapType) ?: mutableMapOf()
            } catch (_: Exception) { mutableMapOf() }

            for ((word, freq) in words) {
                val lw = LangWord(lang, word)
                if (lw in excludedCustom) continue
                if (existing.containsKey(word)) continue   // already present (current state)
                existing[word] = freq
                customApplied++
            }

            editor.putString(key, gson.toJson(existing))
        }

        // Disabled words: same pattern with StringSet storage.
        for ((lang, words) in plan.mergedDisabledWordsByLang) {
            val key = LanguagePreferenceKeys.disabledWordsKey(lang)
            val existing: MutableSet<String> =
                prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()

            for (word in words) {
                val lw = LangWord(lang, word)
                if (lw in excludedDisabled) continue
                if (word in existing) continue
                existing.add(word)
                disabledApplied++
            }

            editor.putStringSet(key, existing)
        }

        editor.commit()
        return customApplied to disabledApplied
    }
}
```

- [ ] **Step 3.9.2: Run the failing test from Task 3.8 — confirm pass.**

- [ ] **Step 3.9.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/DictImportApplier.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/DictImportPlanApplyTest.kt \
        build.gradle
git commit -m "feat: DictImportApplier with single editor.commit() across all languages — opus 4.7"
```

### Task 3.10: Exclusion + existing-word skip tests

- [ ] **Step 3.10.1: Add tests.**

```kotlin
    @Test
    fun apply_excludesByLangWord() {
        val plan = DictImportPlan(
            sourceVersion = "1.4.0",
            perLanguage = mapOf("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
            mergedCustomWordsByLang = mapOf("en" to mapOf("foo" to 1, "bar" to 2)),
            mergedDisabledWordsByLang = emptyMap(),
        )

        val (customApplied, _) = DictImportApplier.apply(
            plan = plan,
            excludedCustom = setOf(LangWord("en", "foo")),
            excludedDisabled = emptySet(),
            prefs = prefs,
        )

        assertThat(customApplied).isEqualTo(1)
        // Verify the editor saw only "bar" in the saved JSON. Captured via
        // a slot:
        val saved = slot<String>()
        verify { editor.putString(any(), capture(saved)) }
        assertThat(saved.captured).contains("bar")
        assertThat(saved.captured).doesNotContain("foo")
    }

    @Test
    fun apply_existingWord_notRecounted() {
        every { prefs.getString(any(), any()) } returns """{"foo":99}"""    // user already has foo
        val plan = DictImportPlan(
            sourceVersion = "1.4.0",
            perLanguage = mapOf("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
            mergedCustomWordsByLang = mapOf("en" to mapOf("foo" to 1, "bar" to 2)),
            mergedDisabledWordsByLang = emptyMap(),
        )

        val (customApplied, _) = DictImportApplier.apply(
            plan, emptySet(), emptySet(), prefs
        )

        // "foo" already present — only "bar" counted.
        assertThat(customApplied).isEqualTo(1)
    }

    @Test
    fun apply_disabledWords_singleCommit() {
        val plan = DictImportPlan(
            sourceVersion = "1.4.0",
            perLanguage = mapOf("en" to LangChanges(emptyMap(), listOf("bad"))),
            mergedCustomWordsByLang = emptyMap(),
            mergedDisabledWordsByLang = mapOf("en" to setOf("bad")),
        )

        val (_, disabledApplied) = DictImportApplier.apply(
            plan, emptySet(), emptySet(), prefs
        )

        assertThat(disabledApplied).isEqualTo(1)
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 1) { editor.putStringSet(any(), any()) }
    }
```

- [ ] **Step 3.10.2: Run; pass; commit.**

### Task 3.11: Wire `BackupRestoreManager.applyDictImportPlan` + `buildDictImportPlan`

**Files:**
- Modify: `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt`

- [ ] **Step 3.11.1: Add the methods.**

```kotlin
fun buildDictImportPlan(uri: Uri, prefs: SharedPreferences): DictImportPlan {
    val jsonString = readJsonFromUri(uri)
    val currentCustom = readCurrentCustomWordsByLang(prefs)
    val currentDisabled = readCurrentDisabledWordsByLang(prefs)
    return DictImportPlanBuilder.fromJson(jsonString, currentCustom, currentDisabled)
}

fun applyDictImportPlan(
    plan: DictImportPlan,
    excludedCustom: Set<LangWord>,
    excludedDisabled: Set<LangWord>,
    prefs: SharedPreferences,
): DictionaryImportResult {
    val (customApplied, disabledApplied) = DictImportApplier.apply(
        plan, excludedCustom, excludedDisabled, prefs
    )
    return DictionaryImportResult().apply {
        sourceVersion = plan.sourceVersion
        userWordsImported = customApplied
        disabledWordsImported = disabledApplied
        excludedByUserCount = excludedCustom.size + excludedDisabled.size   // NEW field
    }
}

private fun readCurrentCustomWordsByLang(prefs: SharedPreferences): Map<String, Map<String, Int>> {
    // Iterate prefs.all looking for keys matching customWordsKey(lang) pattern.
    // Use LanguagePreferenceKeys to identify language codes from the prefix.
    val out = mutableMapOf<String, Map<String, Int>>()
    val gson = Gson()
    val mapType = object : TypeToken<Map<String, Int>>() {}.type
    for ((key, value) in prefs.all) {
        val lang = LanguagePreferenceKeys.languageFromCustomWordsKey(key) ?: continue
        if (value !is String) continue
        try {
            out[lang] = gson.fromJson(value, mapType) ?: emptyMap()
        } catch (_: Exception) { /* skip malformed */ }
    }
    return out
}

private fun readCurrentDisabledWordsByLang(prefs: SharedPreferences): Map<String, Set<String>> {
    val out = mutableMapOf<String, Set<String>>()
    for ((key, value) in prefs.all) {
        val lang = LanguagePreferenceKeys.languageFromDisabledWordsKey(key) ?: continue
        @Suppress("UNCHECKED_CAST")
        if (value is Set<*>) out[lang] = value as Set<String>
    }
    return out
}
```

If `LanguagePreferenceKeys.languageFromCustomWordsKey` doesn't exist yet, add it (regex over the prefix pattern). Add the `excludedByUserCount` field to `DictionaryImportResult` mirroring what was done for `ImportResult`.

- [ ] **Step 3.11.2: Build; tests pass; commit.**

### Task 3.12: Wire legacy `importDictionaries` to delegate

- [ ] **Step 3.12.1: Refactor.**

```kotlin
fun importDictionaries(uri: Uri): DictionaryImportResult {
    val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
    val plan = buildDictImportPlan(uri, prefs)
    return applyDictImportPlan(plan, emptySet(), emptySet(), prefs)
}
```

- [ ] **Step 3.12.2: Run all existing dict tests; confirm green.**

- [ ] **Step 3.12.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt \
        src/main/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeys.kt
git commit -m "feat: importDictionaries delegates to build/apply pipeline — opus 4.7"
```

### Task 3.13: Chunk 3 review

- [ ] **Step 3.13.1: Run all tests, all green.**
- [ ] **Step 3.13.2: Dispatch plan-document-reviewer for Chunk 3.**

---

## Chunk 4: ViewModel + Compose preview dialogs + Compose tests

This chunk delivers the UI. Plans materialize from earlier chunks; here we render them. Each major composable is decomposed into smaller composables (header card, change-row, language-section, etc.) so each unit is independently understandable.

### Task 4.1: `BackupRestoreViewModel.kt`

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreViewModel.kt`

- [ ] **Step 4.1.1: Create the file.**

```kotlin
package tribixbite.cleverkeys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import tribixbite.cleverkeys.backup.DictImportPlan
import tribixbite.cleverkeys.backup.SettingsImportPlan

/**
 * Holds preview-flow state across configuration changes (rotation, fold).
 * Without this VM, plans + user toggle state would be discarded on rotate
 * — see spec §Architecture > Persistence on rotation.
 */
class BackupRestoreViewModel : ViewModel() {
    var settingsPreviewPlan by mutableStateOf<SettingsImportPlan?>(null)
    var dictPreviewPlan by mutableStateOf<DictImportPlan?>(null)
    var isProcessing by mutableStateOf(false)
    var resultTitle by mutableStateOf("")
    var resultMessage by mutableStateOf("")
    var showResultDialog by mutableStateOf(false)
}
```

- [ ] **Step 4.1.2: Compile.** `./gradlew compileDebugKotlin`.
- [ ] **Step 4.1.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/BackupRestoreViewModel.kt
git commit -m "feat: BackupRestoreViewModel for rotation-safe preview state — opus 4.7"
```

### Task 4.2: Settings preview dialog skeleton

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/BackupRestorePreviewDialogs.kt`

This dialog is decomposed into ~6 smaller composables. We stub each, then fill them in subsequent tasks.

- [ ] **Step 4.2.1: Create the file with skeletons.**

```kotlin
package tribixbite.cleverkeys

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tribixbite.cleverkeys.backup.*

/**
 * Top-level entry point for the settings import preview.
 *
 * onApply receives the user's exclusion set and the chosen short-swipe
 * mode. Empty exclusion + MERGE mode = "approve everything" path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsImportPreviewDialog(
    plan: SettingsImportPlan,
    onCancel: () -> Unit,
    onApply: (excludedKeys: Set<String>, shortSwipeMode: ShortSwipeImportMode) -> Unit,
) {
    val excluded = remember { mutableStateOf(emptySet<String>()) }
    val shortSwipeMode = remember { mutableStateOf(ShortSwipeImportMode.MERGE) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsPreviewTopBar(
                    appliedCount = plan.changes.size - excluded.value.size,
                    onCancel = onCancel,
                    onApply = { onApply(excluded.value, shortSwipeMode.value) },
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item { SettingsPreviewHeaderCard(plan) }

                    val modified = plan.changes.filter { it.type == ChangeType.MODIFIED }
                    if (modified.isNotEmpty()) {
                        item { SectionHeader("Modified (${modified.size})") }
                        items(modified, key = { it.key }) { change ->
                            SettingsChangeRow(
                                change = change,
                                isExcluded = change.key in excluded.value,
                                onToggle = { excluded.value = toggle(excluded.value, change.key) },
                            )
                        }
                    }

                    val added = plan.changes.filter { it.type == ChangeType.ADDED }
                    if (added.isNotEmpty()) {
                        item { SectionHeader("Added (${added.size})") }
                        items(added, key = { it.key }) { change ->
                            SettingsChangeRow(
                                change = change,
                                isExcluded = change.key in excluded.value,
                                onToggle = { excluded.value = toggle(excluded.value, change.key) },
                            )
                        }
                    }

                    if (plan.shortSwipeImportSize > 0) {
                        item { SectionHeader("Short-swipe customizations") }
                        item {
                            ShortSwipeModeRadio(
                                size = plan.shortSwipeImportSize,
                                selected = shortSwipeMode.value,
                                onSelect = { shortSwipeMode.value = it },
                            )
                        }
                    }

                    if (plan.parseSkippedKeys.isNotEmpty()) {
                        item { SkippedSection(plan.parseSkippedKeys) }
                    }
                }
            }
        }
    }
}

private fun toggle(set: Set<String>, key: String): Set<String> =
    if (key in set) set - key else set + key
```

- [ ] **Step 4.2.2: Compile** — will fail until the helper composables are created. That's expected.

### Task 4.3: `SettingsPreviewTopBar` + `SettingsPreviewHeaderCard`

- [ ] **Step 4.3.1: Add to `BackupRestorePreviewDialogs.kt`.**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPreviewTopBar(
    appliedCount: Int,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    TopAppBar(
        title = { Text("Import preview") },
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel preview")
            }
        },
        actions = {
            // Always enabled — empty selection means "Nothing imported" result
            // (see spec §Error handling).
            TextButton(onClick = onApply) {
                Text("Apply ($appliedCount)")
            }
        }
    )
}

@Composable
private fun SettingsPreviewHeaderCard(plan: SettingsImportPlan) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Source: ${plan.sourceVersion}", fontWeight = FontWeight.SemiBold)
            val s = plan.sourceScreen
            val c = plan.currentScreen
            Text("Source screen: ${s.width}×${s.height}@${s.density}", fontSize = 12.sp)
            Text("Current screen: ${c.width}×${c.height}@${c.density}", fontSize = 12.sp)
            if (s.width != c.width || s.height != c.height) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Screen-size mismatch — some visual settings may need adjustment.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
```

- [ ] **Step 4.3.2: Compile** — still fails (`SectionHeader`, `SettingsChangeRow`, etc. missing).

### Task 4.4: `SectionHeader` + `SettingsChangeRow` + value-rendering helper

- [ ] **Step 4.4.1: Add.**

```kotlin
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsChangeRow(
    change: SettingsChange,
    isExcluded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = !isExcluded, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(change.key, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                text = renderDelta(change),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TypeChip(change.proposed)
    }
}

private fun renderDelta(change: SettingsChange): String {
    val current = renderPrefValue(change.current)
    val proposed = renderPrefValue(change.proposed)
    return "$current  →  $proposed"
}

private fun renderPrefValue(v: PrefValue): String = when (v) {
    PrefValue.Unset -> "(unset)"
    is PrefValue.Bool -> v.v.toString()
    is PrefValue.IntV -> v.v.toString()
    is PrefValue.FloatV -> v.v.toString()
    is PrefValue.Str -> "\"${v.v}\""
    // JsonBlob deliberately rendered as a marker — full diff is in a separate
    // section; mixing JSON walls with scalar diffs hurts UX (spec §UI flow).
    is PrefValue.JsonBlob -> "(JSON, tap to view)"
}

@Composable
private fun TypeChip(value: PrefValue) {
    val label = when (value) {
        is PrefValue.Bool -> "Bool"
        is PrefValue.IntV -> "Int"
        is PrefValue.FloatV -> "Float"
        is PrefValue.Str -> "Str"
        is PrefValue.JsonBlob -> "JSON"
        PrefValue.Unset -> "—"
    }
    AssistChip(onClick = {}, label = { Text(label, fontSize = 10.sp) })
}
```

- [ ] **Step 4.4.2: Compile** — still fails on `ShortSwipeModeRadio` and `SkippedSection`.

### Task 4.5: `ShortSwipeModeRadio` (3-radio with red warning)

- [ ] **Step 4.5.1: Add.**

```kotlin
@Composable
private fun ShortSwipeModeRadio(
    size: Int,
    selected: ShortSwipeImportMode,
    onSelect: (ShortSwipeImportMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "$size short-swipe mappings in this backup",
            fontWeight = FontWeight.SemiBold,
        )
        ShortSwipeImportMode.entries.forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = (mode == selected), onClick = { onSelect(mode) })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(when (mode) {
                        ShortSwipeImportMode.SKIP -> "Skip — don't import"
                        ShortSwipeImportMode.MERGE -> "Merge — fill gaps, preserve existing (recommended)"
                        ShortSwipeImportMode.REPLACE -> "Replace — wipe existing, install file's set"
                    })
                    if (mode == ShortSwipeImportMode.REPLACE) {
                        Text(
                            text = "This will REPLACE all your existing short-swipe customizations.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4.5.2: Compile** — `SkippedSection` still missing.

### Task 4.6: `SkippedSection` (collapsed, read-only)

- [ ] **Step 4.6.1: Add.**

```kotlin
@Composable
private fun SkippedSection(skipped: List<SkippedKey>) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) "▼ Invalid/skipped (${skipped.size}) — tap to collapse"
                else "▶ Invalid/skipped (${skipped.size}) — tap to expand",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            skipped.forEach { sk ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(sk.key, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        sk.reason,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4.6.2: Compile.** Should now succeed.

- [ ] **Step 4.6.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/BackupRestorePreviewDialogs.kt
git commit -m "feat: SettingsImportPreviewDialog with sectioned LazyColumn + 3-radio short-swipe — opus 4.7"
```

### Task 4.7: `SettingsImportPreviewDialogComposeTest`

**Files:**
- Create: `src/androidTest/kotlin/tribixbite/cleverkeys/SettingsImportPreviewDialogComposeTest.kt`

This mirrors the existing `DialogTruthComposeTest` pattern (already in the repo).

- [ ] **Step 4.7.1: Add the test class.**

```kotlin
package tribixbite.cleverkeys

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.*

@RunWith(AndroidJUnit4::class)
class SettingsImportPreviewDialogComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleScreen = ScreenMetrics(1080, 2400, 3.0f)

    private fun planWith(vararg changes: SettingsChange, ssSize: Int = 0, ssRaw: String? = null) =
        SettingsImportPlan(
            sourceVersion = "1.4.0",
            sourceScreen = sampleScreen,
            currentScreen = sampleScreen,
            changes = changes.toList(),
            parseSkippedKeys = emptyList(),
            internalRemoves = emptyList(),
            shortSwipeImportSize = ssSize,
            shortSwipeImportRawJson = ssRaw,
        )

    @Test
    fun cancel_doesNotInvokeOnApply() {
        var applyCalled = false
        var cancelCalled = false
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(SettingsChange("k", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED)),
                        onCancel = { cancelCalled = true },
                        onApply = { _, _ -> applyCalled = true },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Cancel preview").performClick()
        assertTrue(cancelCalled)
        assertFalse(applyCalled)
    }

    @Test
    fun apply_passesEmptyExclusionSet_whenNothingDeselected() {
        var captured: Set<String>? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(
                            SettingsChange("a", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED),
                            SettingsChange("b", PrefValue.Unset, PrefValue.IntV(2), ChangeType.ADDED),
                        ),
                        onCancel = {},
                        onApply = { excluded, _ -> captured = excluded },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Apply (2)").performClick()
        assertEquals(emptySet<String>(), captured)
    }

    @Test
    fun applyButton_countDecrementsWhenRowDeselected() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(
                            SettingsChange("a", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED),
                            SettingsChange("b", PrefValue.Unset, PrefValue.IntV(2), ChangeType.ADDED),
                        ),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Apply (2)").assertIsDisplayed()
        // Click row "a" checkbox to deselect — node strategy: find by row's
        // key text, then locate the sibling Checkbox via testTag if added.
        // Conservative path: onNodeWithText("a").performClick() — Material's
        // checkbox is hit-tested through the row.
        composeRule.onNodeWithText("a").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apply (1)").assertIsDisplayed()
    }

    @Test
    fun shortSwipeRadio_defaultsToMerge() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(ssSize = 5, ssRaw = """{"q":{"up":"DEL"}}"""),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Merge — fill gaps, preserve existing (recommended)")
            .assertIsDisplayed()
        // Default is Merge → red warning is NOT shown
        composeRule.onNodeWithText("This will REPLACE all your existing", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun shortSwipeReplace_showsRedWarning() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(ssSize = 5, ssRaw = """{"q":{"up":"DEL"}}"""),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Replace — wipe existing, install file's set").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("This will REPLACE all your existing", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun applyButton_alwaysEnabledEvenAtZeroSelection() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SettingsImportPreviewDialog(
                        plan = planWith(SettingsChange("a", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED)),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        // Deselect the only row
        composeRule.onNodeWithText("a").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apply (0)").assertIsEnabled()
    }
}
```

- [ ] **Step 4.7.2: Build the androidTest APK.**
```bash
./gradlew assembleDebugAndroidTest
```

- [ ] **Step 4.7.3: Run via ew-cli.** (Reference `.claude/skills/ew-cli-testing.md` for the exact command. Target this single class: `--test-targets "class tribixbite.cleverkeys.SettingsImportPreviewDialogComposeTest"`.)

- [ ] **Step 4.7.4: Commit.**
```bash
git add src/androidTest/kotlin/tribixbite/cleverkeys/SettingsImportPreviewDialogComposeTest.kt
git commit -m "test: SettingsImportPreviewDialog truth tests — opus 4.7"
```

### Task 4.8: `LanguageSection` for the dictionary dialog (2-state-with-badge)

The dictionary dialog uses a **2-state** checkbox (NOT Material `TriStateCheckbox`) per the spec — tap-on-indeterminate going to "all on" destroys cherry-picked deselections.

- [ ] **Step 4.8.1: Add `LanguageSection` to `BackupRestorePreviewDialogs.kt`.**

```kotlin
@Composable
private fun LanguageSection(
    lang: String,
    changes: LangChanges,
    excludedCustom: Set<LangWord>,
    excludedDisabled: Set<LangWord>,
    onToggleWord: (LangWord, isCustom: Boolean) -> Unit,
    onToggleAll: (lang: String, allOn: Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val totalCount = changes.newCustomWords.size + changes.newDisabledWords.size
    val excludedCount = changes.newCustomWords.keys.count { LangWord(lang, it) in excludedCustom } +
        changes.newDisabledWords.count { LangWord(lang, it) in excludedDisabled }
    val selectedCount = totalCount - excludedCount
    val allSelected = (selectedCount == totalCount && totalCount > 0)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 2-state checkbox: checked iff all selected, unchecked otherwise.
            // Partial state is communicated via the badge — NOT via a tri-state
            // glyph that destroys cherry-picked selections on tap.
            Checkbox(
                checked = allSelected,
                onCheckedChange = { onToggleAll(lang, it) },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = lang.uppercase(),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$selectedCount of $totalCount selected",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Expand")
            }
        }

        if (expanded) {
            LangSubgroup(
                title = "Custom words (${changes.newCustomWords.size})",
                words = changes.newCustomWords.keys.toList(),
                isExcluded = { word -> LangWord(lang, word) in excludedCustom },
                onToggle = { word -> onToggleWord(LangWord(lang, word), true) },
                metadata = { word -> changes.newCustomWords[word]?.let { "freq $it" } ?: "" },
            )
            LangSubgroup(
                title = "Disabled words (${changes.newDisabledWords.size})",
                words = changes.newDisabledWords,
                isExcluded = { word -> LangWord(lang, word) in excludedDisabled },
                onToggle = { word -> onToggleWord(LangWord(lang, word), false) },
                metadata = { "" },
            )
        }
    }
}
```

- [ ] **Step 4.8.2: Add `LangSubgroup` with per-subgroup search.**

```kotlin
@Composable
private fun LangSubgroup(
    title: String,
    words: List<String>,
    isExcluded: (String) -> Boolean,
    onToggle: (String) -> Unit,
    metadata: (String) -> String,
) {
    if (words.isEmpty()) return
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, words) {
        if (query.isBlank()) words else words.filter { it.contains(query, ignoreCase = true) }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Bounded height — required because this LazyColumn is nested inside
        // a scrollable parent; without heightIn, Compose throws.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
        ) {
            items(filtered, key = { it }) { word ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = !isExcluded(word), onCheckedChange = { onToggle(word) })
                    Spacer(Modifier.width(8.dp))
                    Text(word, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    val meta = metadata(word)
                    if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
```

- [ ] **Step 4.8.3: Compile; commit.**

### Task 4.9: `DictionaryImportPreviewDialog` top-level

- [ ] **Step 4.9.1: Add.**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryImportPreviewDialog(
    plan: DictImportPlan,
    onCancel: () -> Unit,
    onApply: (excludedCustom: Set<LangWord>, excludedDisabled: Set<LangWord>) -> Unit,
) {
    val excludedCustom = remember { mutableStateOf(emptySet<LangWord>()) }
    val excludedDisabled = remember { mutableStateOf(emptySet<LangWord>()) }

    val totalCustom = plan.perLanguage.values.sumOf { it.newCustomWords.size }
    val totalDisabled = plan.perLanguage.values.sumOf { it.newDisabledWords.size }
    val applyCount = (totalCustom - excludedCustom.value.size) +
        (totalDisabled - excludedDisabled.value.size)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Dictionary import preview") },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel preview")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            onApply(excludedCustom.value, excludedDisabled.value)
                        }) {
                            Text("Apply ($applyCount)")
                        }
                    },
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(plan.perLanguage.entries.toList(), key = { it.key }) { (lang, changes) ->
                        LanguageSection(
                            lang = lang,
                            changes = changes,
                            excludedCustom = excludedCustom.value,
                            excludedDisabled = excludedDisabled.value,
                            onToggleWord = { lw, isCustom ->
                                if (isCustom) {
                                    excludedCustom.value = toggleLW(excludedCustom.value, lw)
                                } else {
                                    excludedDisabled.value = toggleLW(excludedDisabled.value, lw)
                                }
                            },
                            onToggleAll = { l, allOn ->
                                val langCustom = plan.perLanguage[l]!!.newCustomWords.keys
                                    .map { LangWord(l, it) }.toSet()
                                val langDisabled = plan.perLanguage[l]!!.newDisabledWords
                                    .map { LangWord(l, it) }.toSet()
                                if (allOn) {
                                    excludedCustom.value = excludedCustom.value - langCustom
                                    excludedDisabled.value = excludedDisabled.value - langDisabled
                                } else {
                                    excludedCustom.value = excludedCustom.value + langCustom
                                    excludedDisabled.value = excludedDisabled.value + langDisabled
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun toggleLW(set: Set<LangWord>, lw: LangWord): Set<LangWord> =
    if (lw in set) set - lw else set + lw
```

- [ ] **Step 4.9.2: Compile; commit.**

### Task 4.10: `DictionaryImportPreviewDialogComposeTest`

**Files:**
- Create: `src/androidTest/kotlin/tribixbite/cleverkeys/DictionaryImportPreviewDialogComposeTest.kt`

- [ ] **Step 4.10.1: Add the test class.**

```kotlin
package tribixbite.cleverkeys

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.*

@RunWith(AndroidJUnit4::class)
class DictionaryImportPreviewDialogComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun planWith(vararg langs: Pair<String, LangChanges>) = DictImportPlan(
        sourceVersion = "1.4.0",
        perLanguage = langs.toMap(),
        mergedCustomWordsByLang = langs.associate { (l, ch) -> l to ch.newCustomWords },
        mergedDisabledWordsByLang = langs.associate { (l, ch) -> l to ch.newDisabledWords.toSet() },
    )

    @Test
    fun languageSection_collapsedByDefault() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("foo" to 1), emptyList())),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        // Header visible, individual word "foo" should NOT be visible (collapsed)
        composeRule.onNodeWithText("EN").assertIsDisplayed()
        composeRule.onNodeWithText("foo").assertDoesNotExist()
    }

    @Test
    fun expand_revealsCustomWords() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Expand").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("foo").assertIsDisplayed()
        composeRule.onNodeWithText("bar").assertIsDisplayed()
    }

    @Test
    fun headerCheckbox_2state_uncheckRemovesAllInLang() {
        var captured: Set<LangWord>? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
                        onCancel = {},
                        onApply = { excludedC, _ -> captured = excludedC },
                    )
                }
            }
        }
        // Default — all selected. Tap header checkbox to deselect all.
        composeRule.onNodeWithText("EN").performClick()    // toggles header checkbox row
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apply (0)").performClick()
        assertEquals(setOf(LangWord("en", "foo"), LangWord("en", "bar")), captured)
    }

    @Test
    fun searchFilter_scopedToSubgroup() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("apple" to 1, "banana" to 2), emptyList())),
                        onCancel = {},
                        onApply = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Expand").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search…", substring = true).performTextInput("app")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("apple").assertIsDisplayed()
        composeRule.onNodeWithText("banana").assertDoesNotExist()
    }

    @Test
    fun perLangWordExclusions_passToOnApply() {
        var capturedC: Set<LangWord>? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DictionaryImportPreviewDialog(
                        plan = planWith("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
                        onCancel = {},
                        onApply = { excludedC, _ -> capturedC = excludedC },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Expand").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("foo").performClick()    // deselect foo
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apply (1)").performClick()
        assertEquals(setOf(LangWord("en", "foo")), capturedC)
    }
}
```

- [ ] **Step 4.10.2: Build + run via ew-cli; commit.**
```bash
git add src/androidTest/kotlin/tribixbite/cleverkeys/DictionaryImportPreviewDialogComposeTest.kt
git commit -m "test: DictionaryImportPreviewDialog truth tests — opus 4.7"
```

### Task 4.11: Chunk 4 review

- [ ] **Step 4.11.1: Run all instrumented tests via ew-cli** for `SettingsImportPreviewDialogComposeTest` + `DictionaryImportPreviewDialogComposeTest`. All green.
- [ ] **Step 4.11.2: Dispatch plan-document-reviewer for Chunk 4.**

---

## Chunk 5: Activity wiring + result dialog + export counts + final commit

Ties everything together. After this chunk, the feature is shippable.

### Task 5.1: Migrate `BackupRestoreActivity` to ViewModel

**Files:**
- Modify: `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreActivity.kt`

- [ ] **Step 5.1.1: Add the import + delegate.** Near the top of the class:

```kotlin
import androidx.activity.viewModels
// ...
class BackupRestoreActivity : ComponentActivity() {
    private val viewModel: BackupRestoreViewModel by viewModels()
    // ...
}
```

- [ ] **Step 5.1.2: Remove the local state fields.** Delete:
```kotlin
private var isProcessing by mutableStateOf(false)
private var showResultDialog by mutableStateOf(false)
private var resultTitle by mutableStateOf("")
private var resultMessage by mutableStateOf("")
```

- [ ] **Step 5.1.3: Replace every reference** to those fields with `viewModel.<name>` (e.g. `isProcessing` → `viewModel.isProcessing`). Build to find every callsite via compile errors — Kotlin's resolver flags them all.

- [ ] **Step 5.1.4: Run** `./gradlew compileDebugKotlin` until clean. Then `./gradlew assembleDebug` succeeds.

- [ ] **Step 5.1.5: Run existing `BackupRestoreActivityComposeTest` via ew-cli.** All previously-green tests stay green.

- [ ] **Step 5.1.6: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/BackupRestoreActivity.kt
git commit -m "refactor: BackupRestoreActivity hoists state into BackupRestoreViewModel — opus 4.7"
```

### Task 5.2: Replace `performImport` with build-then-preview

- [ ] **Step 5.2.1: Update `performImport`.** Find the existing method (BackupRestoreActivity.kt:603-652) and replace its body with:

```kotlin
private fun performImport(uri: Uri) {
    lifecycleScope.launch {
        viewModel.isProcessing = true
        try {
            val plan = withContext(Dispatchers.IO) {
                backupRestoreManager.buildSettingsImportPlan(uri, prefs)
            }
            val nothingToImport = plan.changes.isEmpty() &&
                plan.parseSkippedKeys.isEmpty() &&
                plan.shortSwipeImportSize == 0
            if (nothingToImport) {
                viewModel.resultTitle = "No changes"
                viewModel.resultMessage = "Backup file matches current settings."
                viewModel.showResultDialog = true
            } else {
                viewModel.settingsPreviewPlan = plan
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Build settings plan failed", e)
            viewModel.resultTitle = "Import Failed"
            viewModel.resultMessage = "Failed to read backup file:\n\n${e.message}"
            viewModel.showResultDialog = true
        } finally {
            viewModel.isProcessing = false
        }
    }
}

private fun applyPlannedSettings(
    plan: SettingsImportPlan,
    excludedKeys: Set<String>,
    shortSwipeMode: ShortSwipeImportMode,
) {
    lifecycleScope.launch {
        viewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.applySettingsImportPlan(plan, excludedKeys, shortSwipeMode, prefs)
            }
            DirectBootAwarePreferences.copy_preferences_to_protected_storage(this@BackupRestoreActivity, prefs)
            viewModel.resultTitle = "Import Successful"
            viewModel.resultMessage = buildSettingsResultMessage(result)
            viewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Apply settings plan failed", e)
            viewModel.resultTitle = "Import Failed"
            viewModel.resultMessage = "Failed to apply imported settings:\n\n${e.message}"
            viewModel.showResultDialog = true
        } finally {
            viewModel.isProcessing = false
        }
    }
}

private fun buildSettingsResultMessage(result: ImportResult): String = buildString {
    appendLine("Import completed.\n")
    appendLine("• Applied: ${result.importedCount}")
    if (result.excludedByUserCount > 0) {
        appendLine("• Excluded by you: ${result.excludedByUserCount}")
    }
    if (result.skippedCount > 0) {
        appendLine("• Invalid/skipped: ${result.skippedCount}")
    }
    if (result.shortSwipeCustomizationsImported > 0) {
        appendLine("• Short-swipe applied: ${result.shortSwipeCustomizationsImported}")
    }
    if (result.sourceVersion != "unknown") {
        appendLine("• Source version: ${result.sourceVersion}")
    }
    if (result.sourceScreenWidth > 0 && result.sourceScreenWidth != result.currentScreenWidth) {
        appendLine()
        appendLine("⚠️ Screen-size mismatch: source ${result.sourceScreenWidth}×${result.sourceScreenHeight}, current ${result.currentScreenWidth}×${result.currentScreenHeight}.")
    }
    appendLine()
    append("Restart the keyboard for changes to take effect.")
}
```

- [ ] **Step 5.2.2: Wire the dialog inside `setContent`.** Find the existing `BackupRestoreScreen()` call and wrap it:

```kotlin
setContent {
    KeyboardTheme {
        BackupRestoreScreen()
        viewModel.settingsPreviewPlan?.let { plan ->
            SettingsImportPreviewDialog(
                plan = plan,
                onCancel = { viewModel.settingsPreviewPlan = null },
                onApply = { excluded, ssMode ->
                    viewModel.settingsPreviewPlan = null
                    applyPlannedSettings(plan, excluded, ssMode)
                }
            )
        }
        viewModel.dictPreviewPlan?.let { plan ->
            DictionaryImportPreviewDialog(
                plan = plan,
                onCancel = { viewModel.dictPreviewPlan = null },
                onApply = { excludedCustom, excludedDisabled ->
                    viewModel.dictPreviewPlan = null
                    applyPlannedDictionaries(plan, excludedCustom, excludedDisabled)
                }
            )
        }
    }
}
```

- [ ] **Step 5.2.3: Compile; commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/BackupRestoreActivity.kt
git commit -m "feat: settings import goes through preview dialog — opus 4.7"
```

### Task 5.3: Same flow for dictionary

- [ ] **Step 5.3.1: Replace `performImportDictionaries` with build-then-preview.** Mirror the structure of Task 5.2 — replace the body of the existing method (BackupRestoreActivity.kt:686+) with `buildDictImportPlan` → `viewModel.dictPreviewPlan = plan` (or skip-to-result if empty) → `applyPlannedDictionaries` on confirm.

```kotlin
private fun performImportDictionaries(uri: Uri) {
    lifecycleScope.launch {
        viewModel.isProcessing = true
        try {
            val plan = withContext(Dispatchers.IO) {
                backupRestoreManager.buildDictImportPlan(uri, prefs)
            }
            val nothingToImport = plan.perLanguage.values.all {
                it.newCustomWords.isEmpty() && it.newDisabledWords.isEmpty()
            }
            if (nothingToImport) {
                viewModel.resultTitle = "No changes"
                viewModel.resultMessage = "Dictionary file has no new words to import."
                viewModel.showResultDialog = true
            } else {
                viewModel.dictPreviewPlan = plan
            }
        } catch (e: Exception) {
            // ... same error handling shape as performImport
        } finally {
            viewModel.isProcessing = false
        }
    }
}

private fun applyPlannedDictionaries(
    plan: DictImportPlan,
    excludedCustom: Set<LangWord>,
    excludedDisabled: Set<LangWord>,
) {
    lifecycleScope.launch {
        viewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.applyDictImportPlan(plan, excludedCustom, excludedDisabled, prefs)
            }
            viewModel.resultTitle = "Dictionary Import Successful"
            viewModel.resultMessage = buildDictResultMessage(result, excludedCustom.size + excludedDisabled.size)
            viewModel.showResultDialog = true
            LocalBroadcastManager.getInstance(this@BackupRestoreActivity)
                .sendBroadcast(Intent(ACTION_DICTIONARY_IMPORTED))
        } catch (e: Exception) {
            // error handling
        } finally {
            viewModel.isProcessing = false
        }
    }
}

private fun buildDictResultMessage(result: DictionaryImportResult, excludedCount: Int): String = buildString {
    appendLine("Dictionary import completed.\n")
    appendLine("• Custom words applied: ${result.userWordsImported}")
    appendLine("• Disabled words applied: ${result.disabledWordsImported}")
    if (excludedCount > 0) appendLine("• Excluded by you: $excludedCount")
    if (result.sourceVersion != "unknown") appendLine("• Source version: ${result.sourceVersion}")
}
```

- [ ] **Step 5.3.2: Compile; commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/BackupRestoreActivity.kt
git commit -m "feat: dictionary import goes through preview dialog — opus 4.7"
```

### Task 5.4: Export count surfacing — settings

**Files:**
- Modify: `BackupRestoreManager.kt` (return `Int` from `exportConfig`)
- Modify: `BackupRestoreActivity.kt` (read it, render in result dialog)

- [ ] **Step 5.4.1: Modify `exportConfig` signature.** Find the existing method (BackupRestoreManager.kt:367-ish) and change its return type from whatever it is today (likely `Boolean` or `Unit`) to `Int` — count of preferences written. Most existing callers will still work because `Int` is non-Unit; instrumentation tests catch any that depend on the old return.

```kotlin
fun exportConfig(uri: Uri, prefs: SharedPreferences): Int {
    // ... existing parse + write logic ...
    val count = preferences.size()    // existing local
    Log.i(TAG, "Exported $count preferences to $uri")
    return count
}
```

- [ ] **Step 5.4.2: Add a regression test for headless export.** This is a public-API behavior change; add `BackupRestoreManagerHeadlessTest.headlessExportConfig_returnsCountForCallers` to confirm the `Int` is returned and matches the prefs.size:

```kotlin
@Test
fun headlessExportConfig_returnsCountForCallers() {
    // Mocked prefs with 3 entries; exportConfig writes them and returns 3.
    every { prefs.all } returns mapOf("a" to 1, "b" to 2, "c" to 3)
    // ... exercise via the Manager constructed with mock context if feasible, OR
    // assert the contract via a hand-rolled call to the underlying writer.
}
```

If constructing a `BackupRestoreManager` is too heavy for MockK, capture the contract via a separate object (e.g. an internal `SettingsExporter` along the same shape as `SettingsImportApplier`) — this is symmetry with the import side and keeps tests light.

- [ ] **Step 5.4.3: Update `performExport` in the Activity** to read and render the count.

```kotlin
private fun performExport(uri: Uri) {
    lifecycleScope.launch {
        viewModel.isProcessing = true
        try {
            val count = withContext(Dispatchers.IO) {
                backupRestoreManager.exportConfig(uri, prefs)
            }
            viewModel.resultTitle = "Export Successful"
            viewModel.resultMessage = "Settings exported: $count\n\nFile: ${uri.lastPathSegment}\n\nYou can transfer this file to another device or keep it as a backup."
            viewModel.showResultDialog = true
        } catch (e: Exception) {
            // ... existing error handling
        } finally { viewModel.isProcessing = false }
    }
}
```

- [ ] **Step 5.4.4: Run all tests; commit.**

### Task 5.5: Export count surfacing — dictionary

`exportDictionaries` already counts internally (BackupRestoreManager.kt:1127, 1146 — `totalCustomWords`, `totalDisabledWords`) but discards them.

- [ ] **Step 5.5.1: Define a return type.**

```kotlin
data class DictionaryExportSummary(
    val customWordsCount: Int,
    val disabledWordsCount: Int,
    val languageCount: Int,
)

fun exportDictionaries(uri: Uri): DictionaryExportSummary {
    // ... existing logic ...
    return DictionaryExportSummary(totalCustomWords, totalDisabledWords, languageCount)
}
```

- [ ] **Step 5.5.2: Update `performExportDictionaries` to render.**
```kotlin
viewModel.resultMessage = "Custom words: ${summary.customWordsCount} (across ${summary.languageCount} languages)\nDisabled words: ${summary.disabledWordsCount}\n\nFile: ${uri.lastPathSegment}"
```

- [ ] **Step 5.5.3: Run tests; commit.**

### Task 5.6: Activity Compose tests — empty-delta skip + export count

**Files:**
- Modify: `src/androidTest/kotlin/tribixbite/cleverkeys/BackupRestoreActivityComposeTest.kt`

- [ ] **Step 5.6.1: Add tests.**

```kotlin
@Test
fun import_emptyDeltas_skipsPreviewAndShowsResultDirectly() {
    // Pre-condition: prefer to construct an activity that imports a JSON
    // identical to the current state. Mock the file-picker to return a
    // sentinel URI, plumb a fake BackupRestoreManager that returns a plan
    // with no deltas. Verify "No changes — backup file matches current
    // settings." is the dialog shown.
}

@Test
fun export_resultDialogShowsCount() {
    // Construct activity, drive performExport, verify the result dialog
    // text contains "Settings exported: <some Int>".
}
```

The exact wiring for these tests depends on whether the activity supports test injection. If not, add a minimal seam via a `BackupRestoreManager` factory that ComposeRule fakes can replace. Document the seam in `memory/todo.md` if added.

- [ ] **Step 5.6.2: Run via ew-cli; commit.**

### Task 5.7: Final integration sweep

- [ ] **Step 5.7.1: Run full pure suite.**
```bash
./gradlew runPureTests
```
Expected: 1085 baseline + ~25 new = ~1110. (Reconciled lower than the original 1140 estimate — Chunks 1+3 net ~17 pure tests; Chunk 2 adds a few; some are MockK rather than pure.)

- [ ] **Step 5.7.2: Run all mock tests.**
```bash
./gradlew runMockTests
```
Baseline 176 + ~13 new = ~189.

- [ ] **Step 5.7.3: Build APKs.**
```bash
./gradlew assembleDebug assembleDebugAndroidTest
```

- [ ] **Step 5.7.4: Run instrumented test sweep via ew-cli** for the 3 new/extended classes:
- `SettingsImportPreviewDialogComposeTest` (~6 tests)
- `DictionaryImportPreviewDialogComposeTest` (~5 tests)
- `BackupRestoreActivityComposeTest` (extended +2)

All green.

- [ ] **Step 5.7.5: Update `memory/todo.md`.**

```markdown
## ✅ BackupRestore import preview + export counts (2026-04-30)

Two-phase build/apply split inside BackupRestoreManager. Settings + dictionary
imports now show a preview dialog with deltas-only listings + per-key /
per-word deselect. Export dialogs show real counts.

- SettingsImportApplier + DictImportApplier: stateless, single editor.commit()
  per import (replaces 4 separate apply()s in legacy dict path)
- SettingsValidation.kt: single source of truth shared by IO + pure paths
- ViewModel rotation: BackupRestoreViewModel hoists plan state through
  config changes; user toggle state survives device rotation
- 2-state-with-badge language header (NOT TriStateCheckbox) — tap on
  partial-selection deselect-all destroys cherry-picked work
- ShortSwipeImportMode 3-radio: SKIP / MERGE (default in SAF flow) / REPLACE
- Headless Termux path preserved: importConfig keeps legacy merge=false
  destructive default for ACTION_IMPORT_SETTINGS Intent callers

### Hard-won lessons

- BaseInputConnection-style fire-and-forget: `editor.apply()` is async; for
  honest post-write counts use `editor.commit()` (synchronous).
- `migrateLegacyMargins` synthesizes new keys but never calls
  `editor.remove(oldKey)` — old margin keys orphan in SharedPreferences
  forever. The new build path queues an explicit `internalRemoves` list.
- `importDictionaries` accepts THREE concurrent formats (v2 + 2 legacy)
  with first-writer-wins via `!containsKey` — when refactoring, preserve
  ordering via `LinkedHashMap.putIfAbsent` (NOT `Map.plus` which is
  last-writer-wins).
- Material `TriStateCheckbox` cycles On→Off→Indeterminate→On; in a
  selection list with cherry-picked deselects, that destroys user work.
  Use a 2-state checkbox + count-badge instead (Gmail/GitHub pattern).
```

- [ ] **Step 5.7.6: Final commit.**
```bash
git add memory/todo.md
git commit -m "docs(memory): BackupRestore import preview completion entry — opus 4.7"
```

### Task 5.8: Chunk 5 review

- [ ] **Step 5.8.1: Dispatch plan-document-reviewer for Chunk 5.**
- [ ] **Step 5.8.2: Address findings; surface to human for final approval.**

---

## Resolved decisions (locked in by the plan)

These were open during brainstorming; the plan codifies them so no implementation-time pause is needed.

1. **Headless `merge` default**: `importConfig` (the headless entry point used by Termux automation) keeps `ShortSwipeImportMode.REPLACE` to preserve legacy `merge=false` semantics. The SAF preview flow defaults to `MERGE`. Locked in Task 2.9; regression-tested in `BackupRestoreManagerHeadlessTest.headlessImportConfig_usesShortSwipeReplaceMode`.
2. **`migrateLegacyMargins` density**: `ScreenMetrics(width, height, density)` includes density so dp→% rewrites in the pure builder match the legacy IO method. Locked in Task 1.1.1.
3. **`isInternalPreference` complete list**: ported verbatim into `SettingsValidation.INTERNAL_KEYS` in Task 1.6. The deliberate `TODO()` in the validate() body fails CI if implementer skips the port.
4. **Validation rules**: extracted into `backup/SettingsValidation.kt` (Task 1.6) — single source of truth shared by IO + pure paths. Adding a rule on either side is impossible; both go through the validator.
5. **`applyPlanForTest` companion seam**: REJECTED. Replaced with `SettingsImportApplier` + `DictImportApplier` stateless objects + `ShortSwipeImporter` interface. Tests directly invoke the appliers without constructing a `BackupRestoreManager`.

---

## Pre-existing latent bugs (out of scope but worth flagging if discovered)

The spec lists three. None are fixed in this plan. If you encounter another while reading the legacy code, add to `memory/todo.md` and keep moving.
