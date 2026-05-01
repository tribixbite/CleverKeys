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
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/DictImportPlanBuilder.kt` | Pure JSON → `DictImportPlan` builder. |
| New | `src/main/kotlin/tribixbite/cleverkeys/backup/ShortSwipeImportMode.kt` | Enum: `SKIP`/`MERGE`/`REPLACE`. |
| Modified | `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt` | Add 4 new methods: `buildSettingsImportPlan`, `applySettingsImportPlan`, `buildDictImportPlan`, `applyDictImportPlan`. Update `exportConfig` to return `Int`, `exportDictionaries` to return existing counts. Existing `importConfig`/`importDictionaries` keep public signatures, internally delegate. |
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

### Task 1.6: Internal-preference filtering + validation rejects → parseSkippedKeys

The legacy `importConfig` skips `isInternalPreference` keys (BackupRestoreManager.kt:960-969) and validation-rejects via `validatePreferenceValue` (line 789-1068). The pure builder must mirror.

- [ ] **Step 1.6.1: Failing tests.**

```kotlin
    @Test
    fun internalPreferenceKeys_landInSkippedNotChanges() {
        // last_input_method is the canonical example — service-managed state.
        val json = """{"preferences":{"last_input_method":"foo"}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        assertThat(plan.changes).isEmpty()
        assertThat(plan.parseSkippedKeys.map { it.key }).contains("last_input_method")
    }

    @Test
    fun outOfRangeIntValue_landsInSkipped() {
        // key_height has a sane range; absurd value rejected.
        val json = """{"preferences":{"key_height":99999}}"""
        val plan = SettingsImportPlanBuilder.fromJson(json, emptyMap(), screen)

        val rejected = plan.parseSkippedKeys.singleOrNull { it.key == "key_height" }
        assertThat(rejected).isNotNull()
        assertThat(rejected!!.reason).contains("out of range")
        assertThat(plan.changes.any { it.key == "key_height" }).isFalse()
    }
```

- [ ] **Step 1.6.2: Confirm fail.**

- [ ] **Step 1.6.3: Lift the validation predicates into the pure module.**

Inside `SettingsImportPlanBuilder`, add (verbatim names + rules from BackupRestoreManager — read lines 960-1068 and copy the contents into static helpers):

```kotlin
    private fun isInternalPreference(key: String): Boolean {
        // Mirrors BackupRestoreManager.isInternalPreference — line 960-969.
        return key in setOf(
            "last_input_method",
            "last_keyboard_layout",
            // Add any keys the original returns true for — diff against
            // BackupRestoreManager when this lands.
        )
    }

    private fun validateRange(key: String, value: PrefValue): String? {
        // Returns null if valid, otherwise an error message suitable for SkippedKey.reason.
        // Source: BackupRestoreManager.validatePreferenceValue + validateIntPreference
        // + validateFloatPreference (lines 789-1068).
        return when (key) {
            "key_height" -> if (value is PrefValue.IntV && value.v in 20..200) null else "out of range (20..200)"
            // ... copy the rest of the rules from validatePreferenceValue.
            else -> null
        }
    }
```

Then in the iteration loop, before adding a change:
```kotlin
            if (isInternalPreference(key)) {
                skipped += SkippedKey(key, "internal preference")
                continue
            }
            val rangeError = validateRange(key, proposed)
            if (rangeError != null) {
                skipped += SkippedKey(key, rangeError)
                continue
            }
```

- [ ] **Step 1.6.4: Run — confirm tests pass.**

**IMPORTANT**: When wiring `validateRange`, copy ALL existing validation rules from BackupRestoreManager.kt:789-1068. Do NOT skip or simplify — that would silently change validation behavior. If the rule set is large, factor into a separate file `backup/SettingsValidation.kt` and call from both the legacy IO path AND the new pure builder. The test list MUST include at least one positive + one negative case per rule category (int range, float range, string allowlist).

- [ ] **Step 1.6.5: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt
git commit -m "feat: settings plan validates + filters internal prefs — opus 4.7"
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

## Chunk 2: Settings plan apply + drift detection + MockK tests

This chunk adds `applySettingsImportPlan` to `BackupRestoreManager` plus its MockK test class. Keep the existing public `importConfig(uri, prefs)` working; route it internally through `buildSettingsImportPlan` + `applySettingsImportPlan(emptyExclusions, MERGE)` so the headless Intent path is unchanged.

### Task 2.1: Failing apply test — basic dispatch

**Files:**
- Create: `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanApplyTest.kt`
- Modify: `build.gradle` (add to `mockTestClasses` list, ~line 405)

- [ ] **Step 2.1.1: Add the test file.**

```kotlin
package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.Test
import tribixbite.cleverkeys.BackupRestoreManager

class SettingsImportPlanApplyTest {

    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    init {
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true
        every { prefs.all } returns emptyMap()
    }

    @Test
    fun apply_writesEachPrefValueVariantViaCorrectEditorMethod() {
        val plan = SettingsImportPlan(
            sourceVersion = "1.4.0",
            sourceScreen = ScreenMetrics(1080, 2400, 3.0f),
            currentScreen = ScreenMetrics(1080, 2400, 3.0f),
            changes = listOf(
                SettingsChange("b", PrefValue.Unset, PrefValue.Bool(true), ChangeType.ADDED),
                SettingsChange("i", PrefValue.Unset, PrefValue.IntV(42), ChangeType.ADDED),
                SettingsChange("f", PrefValue.Unset, PrefValue.FloatV(3.14f), ChangeType.ADDED),
                SettingsChange("s", PrefValue.Unset, PrefValue.Str("hi"), ChangeType.ADDED),
                SettingsChange("layouts", PrefValue.Unset, PrefValue.JsonBlob("[]"), ChangeType.ADDED),
            ),
            parseSkippedKeys = emptyList(),
            internalRemoves = emptyList(),
            shortSwipeImportSize = 0,
            shortSwipeImportRawJson = null,
        )

        // System under test uses the manager's apply method — instantiate once
        // we have a real Context-free apply impl. For now, this test fails
        // because applySettingsImportPlan does not exist yet.
        val mgr = BackupRestoreManager.applyPlanForTest(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs)
        val result = mgr

        verify(exactly = 1) { editor.putBoolean("b", true) }
        verify(exactly = 1) { editor.putInt("i", 42) }
        verify(exactly = 1) { editor.putFloat("f", 3.14f) }
        verify(exactly = 1) { editor.putString("s", "hi") }
        verify(exactly = 1) { editor.putString("layouts", "[]") }
        verify(exactly = 1) { editor.commit() }
        assertThat(result.importedCount).isEqualTo(5)
    }
}
```

- [ ] **Step 2.1.2: Register in `build.gradle` `mockTestClasses` list.**
- [ ] **Step 2.1.3: Run — expect compile failure** (`applyPlanForTest` doesn't exist; `applyPlan` doesn't exist).

### Task 2.2: Implement `applySettingsImportPlan`

**Files:**
- Modify: `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt`

- [ ] **Step 2.2.1: Add the new method.** Insert after the existing `importConfig` method (~line 615):

```kotlin
    /**
     * Atomic, type-safe write of a previously built `SettingsImportPlan`.
     * - `excludedKeys`: keys from `plan.changes` to omit (user deselected).
     *   `plan.internalRemoves` always execute.
     * - `shortSwipeMode`: SKIP / MERGE / REPLACE — see `ShortSwipeImportMode`.
     * - Single `editor.commit()` for atomicity (was 4 separate `apply()`s).
     * - Drift detection: snapshot `prefs.all` again right before commit; for
     *   every un-excluded key, compare to plan.current. Log a one-liner if any
     *   drifted; the user's selection is honored regardless.
     */
    fun applySettingsImportPlan(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
        prefs: SharedPreferences,
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
                result.excludedByUserCount++   // new field — see below
                continue
            }
            // Drift check: was the current value still what the plan promised?
            val nowCurrent = nowBeforeCommit[change.key]
            val planCurrent = change.current
            if (!planCurrentMatchesNow(planCurrent, nowCurrent)) driftCount++

            dispatchPut(editor, change.key, change.proposed)
            result.importedCount++
            result.importedKeys.add(change.key)
        }

        for (legacyKey in plan.internalRemoves) {
            editor.remove(legacyKey)
        }

        if (shortSwipeMode != ShortSwipeImportMode.SKIP && plan.shortSwipeImportRawJson != null) {
            val merge = (shortSwipeMode == ShortSwipeImportMode.MERGE)
            val ssCount = runBlocking {
                shortSwipeManager.importFromJson(plan.shortSwipeImportRawJson, merge = merge)
            }
            result.shortSwipeCustomizationsImported = ssCount
        }

        result.skippedCount = plan.parseSkippedKeys.size
        plan.parseSkippedKeys.forEach { result.skippedKeys.add(it.key) }
        result.driftCount = driftCount

        if (!editor.commit()) {
            Log.w(TAG, "applySettingsImportPlan: editor.commit() returned false")
        }
        if (driftCount > 0) Log.i(TAG, "BackupRestore: $driftCount keys drifted between preview and apply")

        return result
    }

    private fun dispatchPut(editor: SharedPreferences.Editor, key: String, value: PrefValue) {
        when (value) {
            is PrefValue.Bool -> editor.putBoolean(key, value.v)
            is PrefValue.IntV -> editor.putInt(key, value.v)
            is PrefValue.FloatV -> editor.putFloat(key, value.v)
            is PrefValue.Str -> editor.putString(key, value.v)
            is PrefValue.JsonBlob -> editor.putString(key, value.raw)
            PrefValue.Unset -> error("PrefValue.Unset is illegal inside EditorOp.Put — use Remove instead")
        }
    }

    private fun planCurrentMatchesNow(planCurrent: PrefValue, now: Any?): Boolean {
        // Compare what the plan recorded as "current at build time" against
        // the actual stored value at apply time. Returns true iff identical.
        if (planCurrent == PrefValue.Unset) return now == null
        return when (planCurrent) {
            is PrefValue.Bool -> now == planCurrent.v
            is PrefValue.IntV -> now == planCurrent.v
            is PrefValue.FloatV -> now == planCurrent.v
            is PrefValue.Str -> now == planCurrent.v
            is PrefValue.JsonBlob -> {
                if (now !is String) false
                else JsonParser.parseString(now).toString() == planCurrent.raw
            }
            PrefValue.Unset -> false   // unreachable
        }
    }
```

Add the new fields to the existing `ImportResult` class (~line 1070):

```kotlin
data class ImportResult(
    @JvmField var importedCount: Int = 0,
    @JvmField var skippedCount: Int = 0,
    @JvmField var excludedByUserCount: Int = 0,    // NEW
    @JvmField var driftCount: Int = 0,             // NEW
    // ... existing fields preserved
)
```

Add the test seam:
```kotlin
companion object {
    /**
     * Test-only seam — lets MockK tests exercise apply without
     * constructing a full `BackupRestoreManager` (which needs Context).
     */
    @VisibleForTesting
    fun applyPlanForTest(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
        prefs: SharedPreferences,
    ): ImportResult {
        // Mirror of applySettingsImportPlan but inline, for static testability.
        // Does NOT touch shortSwipeManager — passing SKIP avoids that path.
        // ... copy the body except the shortSwipeManager block
    }
}
```

- [ ] **Step 2.2.2: Run — confirm test passes.**

- [ ] **Step 2.2.3: Commit.**
```bash
git add src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt \
        src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanApplyTest.kt \
        build.gradle
git commit -m "feat: applySettingsImportPlan dispatches typed PrefValue + drift detection — opus 4.7"
```

### Task 2.3: Exclusion + internal removes + short-swipe mode tests

- [ ] **Step 2.3.1: Add tests.**

```kotlin
    @Test
    fun apply_omitsExcludedKeys() {
        val plan = SettingsImportPlan(/* two ADDED rows: a, b */)
        val result = BackupRestoreManager.applyPlanForTest(plan, setOf("a"), ShortSwipeImportMode.SKIP, prefs)

        verify(exactly = 0) { editor.putInt("a", any()) }
        verify(exactly = 1) { editor.putInt("b", any()) }
        assertThat(result.importedCount).isEqualTo(1)
        assertThat(result.excludedByUserCount).isEqualTo(1)
    }

    @Test
    fun apply_executesInternalRemovesEvenWhenChangesEmpty() {
        val plan = SettingsImportPlan(
            // ... empty changes, internalRemoves = listOf("legacy_key")
        )
        BackupRestoreManager.applyPlanForTest(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs)
        verify(exactly = 1) { editor.remove("legacy_key") }
    }

    @Test
    fun apply_shortSwipeMerge_invokesManagerWithMergeTrue() {
        // requires injecting a mock ShortSwipeManager; see Step 2.4 for seam.
    }
```

- [ ] **Step 2.3.2: Confirm fail; refine; pass; commit.**

### Task 2.4: ShortSwipe injection seam (deferred fakeable interface)

The `ShortSwipeCustomizationManager` does file I/O. For pure JVM tests we mock it. Define an internal interface:

```kotlin
internal interface ShortSwipeImporter {
    suspend fun importFromJson(json: String, merge: Boolean): Int
}
```

Have `BackupRestoreManager` accept a `ShortSwipeImporter` (defaulting to a thin adapter wrapping the real `ShortSwipeCustomizationManager`). MockK tests inject a stub.

- [ ] **Steps 2.4.1-2.4.4**: extract interface, add adapter, add stub-based test for MERGE/REPLACE/SKIP routing, commit.

### Task 2.5: Wire `importConfig` to delegate

The legacy public method must keep working for the headless Termux path.

- [ ] **Step 2.5.1: Modify `importConfig` to delegate.**

```kotlin
fun importConfig(uri: Uri, prefs: SharedPreferences): ImportResult {
    val plan = buildSettingsImportPlan(uri, prefs)
    return applySettingsImportPlan(plan, emptySet(), ShortSwipeImportMode.MERGE, prefs)
}
```

Note: legacy headless path used `merge=false` (BackupRestoreManager.kt:597). The plan default is MERGE (non-destructive). If you want to preserve the legacy destructive default for headless callers, parameterize via a private overload — but the spec recommends MERGE as the new default. **Confirm with the human before flipping.**

- [ ] **Step 2.5.2: Add regression test in `BackupRestoreManagerHeadlessTest.kt`.**

```kotlin
@Test
fun importConfig_legacyEntryPoint_runsBuildThenApply() {
    // Verify the public method's behavior via its observable side-effects
    // matches what a build-then-apply produces.
}
```

- [ ] **Step 2.5.3: Run all pure + mock tests; commit.**

### Task 2.6: Build the IO wrapper `buildSettingsImportPlan`

The Manager wraps the pure builder with URI reading + snapshot building.

- [ ] **Step 2.6.1: Add the method.**

```kotlin
fun buildSettingsImportPlan(uri: Uri, prefs: SharedPreferences): SettingsImportPlan {
    val jsonString = readJsonFromUri(uri)
    val snapshot = prefs.all.toMap()
    val dm = context.resources.displayMetrics
    val screen = ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.density)
    return SettingsImportPlanBuilder.fromJson(jsonString, snapshot, screen)
}
```

- [ ] **Step 2.6.2: Build + tests still pass; commit.**

### Task 2.7: Chunk 2 review

- [ ] **Step 2.7.1: Run all tests.** `./gradlew runPureTests runMockTests`. All green.
- [ ] **Step 2.7.2: Dispatch plan-document-reviewer for Chunk 2.**

---

## Chunk 3: Dictionary plan builder + apply + tests

Same shape as Chunks 1+2 but for dictionary import. Three-source priority (v2 ⊃ legacy `custom_words` ⊃ legacy `user_words`) is the showstopper detail; tests must lock it in.

### Task 3.1: Data classes (`DictImportPlan.kt`)

- [ ] **Step 3.1.1: Create file with `LangWord`, `LangChanges`, `DictImportPlan`.** Code from spec §Architecture > Manager API > DictImportPlan.
- [ ] **Step 3.1.2: Compile-only verification; commit.**

### Task 3.2: Pure dict plan builder — happy path (v2 format only)

- [ ] **Step 3.2.1: Failing test — v2 deltas.**
```kotlin
@Test
fun v2Format_perLanguageDeltas() {
    val json = """{"custom_words_by_language":{"en":{"foo":50,"bar":100}}}"""
    val plan = DictImportPlanBuilder.fromJson(json, emptyMap(), emptyMap())
    val en = plan.perLanguage["en"]!!
    assertThat(en.newCustomWords).containsEntry("foo", 50)
    assertThat(en.newCustomWords).containsEntry("bar", 100)
}
```
- [ ] **Step 3.2.2-3.2.5: Confirm fail; implement minimal builder; pass; commit.**

### Task 3.3: Three-source priority + dedup

- [ ] **Step 3.3.1: Failing test — first-writer-wins.**
```kotlin
@Test
fun mixed_v2AndLegacy_firstWriterWins() {
    val json = """{
      "custom_words_by_language":{"en":{"foo":10}},
      "custom_words":{"foo":20},
      "user_words":[{"word":"foo","frequency":30}]
    }"""
    val plan = DictImportPlanBuilder.fromJson(json, emptyMap(), emptyMap())
    val en = plan.perLanguage["en"]!!
    assertThat(en.newCustomWords["foo"]).isEqualTo(10)   // v2 wins
    assertThat(en.newCustomWords).hasSize(1)              // dedup'd
}
```
- [ ] **Step 3.3.2-3.3.5: Implement explicit priority via `LinkedHashMap.putIfAbsent`; pass; commit.**

### Task 3.4: Existing-word filtering

- [ ] **Step 3.4.1: Failing test — current word skipped.**
- [ ] **Step 3.4.2-3.4.5: Implement; pass; commit.**

### Task 3.5: Legacy disabled-words path (English-routed)

- [ ] **Step 3.5.1-3.5.5: Same TDD cycle.**

### Task 3.6: Case-sensitivity guard test

- [ ] **Step 3.6.1: Test that "foo" and "FOO" remain separate entries.** Spec calls this out as intentional.

### Task 3.7: Apply path + MockK tests

`applyDictImportPlan` builds one editor, dispatches all language puts, calls `commit()` once. Match the spec's atomicity contract.

- [ ] **Step 3.7.1: Failing test — single commit() call regardless of language count.**
- [ ] **Step 3.7.2: Implement; pass; commit.**

### Task 3.8: Wire `importDictionaries` to delegate

- [ ] **Step 3.8.1: Refactor `importDictionaries(uri)` to `buildDictImportPlan` + `applyDictImportPlan(plan, emptySet(), emptySet(), prefs)`.**
- [ ] **Step 3.8.2: All existing dict tests still pass; commit.**

### Task 3.9: Chunk 3 review

- [ ] **Step 3.9.1: All tests; build; reviewer dispatch.**

---

## Chunk 4: ViewModel + Compose preview dialogs + Compose tests

This chunk delivers the UI. Plans materialize from earlier chunks; here we render them.

### Task 4.1: `BackupRestoreViewModel.kt`

- [ ] **Step 4.1.1: Create the file.**
```kotlin
package tribixbite.cleverkeys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import tribixbite.cleverkeys.backup.DictImportPlan
import tribixbite.cleverkeys.backup.SettingsImportPlan

class BackupRestoreViewModel : ViewModel() {
    var settingsPreviewPlan by mutableStateOf<SettingsImportPlan?>(null)
    var dictPreviewPlan by mutableStateOf<DictImportPlan?>(null)
    var isProcessing by mutableStateOf(false)
    var resultTitle by mutableStateOf("")
    var resultMessage by mutableStateOf("")
    var showResultDialog by mutableStateOf(false)
}
```
- [ ] **Step 4.1.2: Commit.**

### Task 4.2: `SettingsImportPreviewDialog` composable

**Files:**
- Create: `src/main/kotlin/tribixbite/cleverkeys/BackupRestorePreviewDialogs.kt`

- [ ] **Step 4.2.1: Implement the dialog.** Render: top bar, header card, sectioned `LazyColumn` with Modified / Added / Complex / ShortSwipe (3-radio) / Skipped (collapsed). Live count, Apply button always enabled. Uses `Dialog(onDismissRequest = onCancel) { Surface { ... } }`.
- [ ] **Step 4.2.2: Compile.**
- [ ] **Step 4.2.3: Commit.**

### Task 4.3: `SettingsImportPreviewDialogComposeTest`

- [ ] **Step 4.3.1: Mirror the `DialogTruthComposeTest` pattern**: instantiate the dialog with a mock plan, capture `onApply` callback, verify exclusion-set delivery, default checkbox states, short-swipe radio default = MERGE, parseSkipped read-only, header counts update live.
- [ ] **Step 4.3.2: Run via ew-cli (existing recipe in CLAUDE.md skills).**
- [ ] **Step 4.3.3: Commit.**

### Task 4.4: `DictionaryImportPreviewDialog` composable

- [ ] **Step 4.4.1: Implement** — per-language collapsible sections, 2-state-with-badge header checkbox (NOT Material `TriStateCheckbox`), nested `LazyColumn` for word lists with `Modifier.heightIn(max = 400.dp)`, per-subgroup search.
- [ ] **Step 4.4.2: Commit.**

### Task 4.5: `DictionaryImportPreviewDialogComposeTest`

- [ ] **Step 4.5.1-4.5.3: Tests; run; commit.**

### Task 4.6: Chunk 4 review

- [ ] Reviewer dispatch.

---

## Chunk 5: Activity wiring + result dialog + export counts + final commit

Ties everything together. After this chunk, the feature is shippable.

### Task 5.1: Migrate `BackupRestoreActivity` to ViewModel

- [ ] **Step 5.1.1: Add `viewModels()` delegate.** Convert top-level `mutableStateOf` fields to read from the VM.
- [ ] **Step 5.1.2: All existing activity tests pass.** Commit.

### Task 5.2: Replace `performImport` with build-then-show-preview

- [ ] **Step 5.2.1: Update.**
```kotlin
private fun performImport(uri: Uri) {
    lifecycleScope.launch {
        viewModel.isProcessing = true
        try {
            val plan = withContext(Dispatchers.IO) { backupRestoreManager.buildSettingsImportPlan(uri, prefs) }
            val nothingToImport = plan.changes.isEmpty() && plan.parseSkippedKeys.isEmpty() && plan.shortSwipeImportSize == 0
            if (nothingToImport) {
                viewModel.resultTitle = "No changes"
                viewModel.resultMessage = "Backup file matches current settings."
                viewModel.showResultDialog = true
            } else {
                viewModel.settingsPreviewPlan = plan
            }
        } catch (e: Exception) {
            // existing failure dialog path
        } finally { viewModel.isProcessing = false }
    }
}
```
- [ ] **Step 5.2.2: Wire the dialog.**
```kotlin
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
```
- [ ] **Step 5.2.3: `applyPlannedSettings` calls `backupRestoreManager.applySettingsImportPlan` on IO, then shows the result dialog with the new counts.**
- [ ] **Step 5.2.4: Commit.**

### Task 5.3: Same for dictionary

- [ ] **Step 5.3.1: Update `performImportDictionaries`; wire dialog; result.**
- [ ] **Step 5.3.2: Commit.**

### Task 5.4: Result dialog upgrade

- [ ] **Step 5.4.1: Update the result dialog text builder** to read new counts and short-swipe applied/skipped status. Mirror spec §UI flow > Result dialog.
- [ ] **Step 5.4.2: Commit.**

### Task 5.5: Export count surfacing

- [ ] **Step 5.5.1: Modify `exportConfig` to return `Int` count.** Update `performExport` to read it.
- [ ] **Step 5.5.2: Same for `exportDictionaries`.**
- [ ] **Step 5.5.3: Update the export result dialog text.**
- [ ] **Step 5.5.4: Commit.**

### Task 5.6: Activity Compose tests

- [ ] **Step 5.6.1: Extend `BackupRestoreActivityComposeTest.kt`** with empty-deltas-skip + export-count-display.
- [ ] **Step 5.6.2: Run via ew-cli; commit.**

### Task 5.7: Final integration sweep

- [ ] **Step 5.7.1: Run full pure suite.** `./gradlew runPureTests` — count should be ~1140 (1085 baseline + ~55 new).
- [ ] **Step 5.7.2: Run mock tests.** `./gradlew runMockTests`.
- [ ] **Step 5.7.3: Build APKs.** `./gradlew assembleDebug assembleDebugAndroidTest`.
- [ ] **Step 5.7.4: Run ew-cli on all new instrumented test classes** (settings preview + dict preview + extended activity test). All green.
- [ ] **Step 5.7.5: Update `memory/todo.md`** with completion entry summarizing the build/apply split, drift detection, ViewModel rotation handling, 2-state-with-badge UX choice, three-source dict dedup priority. Capture hard-won lessons.
- [ ] **Step 5.7.6: Final commit + spec-of-done check.** All boxes checked, all tests green, no lint regressions.

### Task 5.8: Chunk 5 review

- [ ] Reviewer dispatch + final approval.

---

## Open questions to surface to the human before / during execution

1. **Headless `merge` default**: legacy `importConfig` line 597 hardcoded `merge=false`. Plan defaults the new build/apply split to `MERGE`. Headless callers (Termux automation) may rely on the destructive behavior. The spec defers this — **decide before Task 2.5**.
2. **`migrateLegacyMargins` parity**: the legacy code (BackupRestoreManager.kt:917-936) also rewrites large dp `margin_bottom_*` values to percents using screen height. Whether the pure builder needs to mirror this exactly depends on whether any user is mid-migration. Probe by reading the full legacy method; if the dp→% rewrite is significant, factor a shared helper that both legacy IO path and new pure builder use.
3. **`isInternalPreference` complete list**: hand-copy ALL keys from BackupRestoreManager.kt:960-969 into the pure builder. Don't truncate.
4. **Validation rules**: the pure builder's `validateRange` must contain ALL rules from `validatePreferenceValue`. If the rule set is large, factor `backup/SettingsValidation.kt` shared between IO and pure paths.

---

## Pre-existing latent bugs (out of scope but worth flagging if discovered)

The spec lists three. None are fixed in this plan. If you encounter another while reading the legacy code, add to `memory/todo.md` and keep moving.
