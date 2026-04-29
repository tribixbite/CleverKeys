# BackupRestore Import Preview + Export Counts — Design

**Date**: 2026-04-28
**Status**: Approved (pending spec-reviewer + user sign-off)
**Affected files**: `BackupRestoreManager.kt`, `BackupRestoreActivity.kt`, new `BackupRestorePreviewDialogs.kt`, new `BackupRestoreViewModel.kt`, plus tests.

## Problem

Today, `BackupRestoreManager.importConfig` and `importDictionaries` parse JSON and immediately commit to SharedPreferences via `editor.apply()` with no preview. Users cannot see what will change or reject specific entries. The result dialog reports "exported successfully" with **no count** on export, and the import path uses overlapping "skipped" terminology (validation-skipped vs no-op).

## Goals

1. Pre-apply preview modal for **settings JSON** and **dictionary JSON** imports, deltas only.
2. Per-key (settings) / per-word (dictionary) deselect, scaled via expand-to-deselect for languages.
3. Counts surfaced on **both** import and export result dialogs.
4. Headless Intent-action path (Termux automation) **must remain unchanged** — it bypasses the UI and writes atomically.
5. Build-time validation guarantees an apply-time write — no silently-vanishing rows after the user taps Apply.

## Non-goals

- Clipboard import preview.
- Structural diff inside JSON-blob preferences (`layouts`, `extra_keys`, `custom_extra_keys`) — v1 shows raw side-by-side text.
- Cross-device backup migration UX (separate concern).

## Architecture

### Manager API (build/apply split)

```kotlin
data class ScreenMetrics(val width: Int, val height: Int, val density: Float)

sealed class PrefValue {
    data class Bool(val v: Boolean) : PrefValue()
    data class IntV(val v: Int) : PrefValue()
    data class FloatV(val v: Float) : PrefValue()
    data class Str(val v: String) : PrefValue()
    data class JsonBlob(val raw: String) : PrefValue()    // canonicalized
    object Unset : PrefValue()                              // current==Unset ⇒ ADDED
}

sealed class EditorOp {
    data class Put(val key: String, val value: PrefValue) : EditorOp()
    data class Remove(val key: String) : EditorOp()
}

enum class ChangeType { ADDED, MODIFIED }
data class SettingsChange(val key: String, val current: PrefValue, val proposed: PrefValue, val type: ChangeType)
data class SkippedKey(val key: String, val reason: String)

data class SettingsImportPlan(
    val sourceVersion: String,
    val sourceScreen: ScreenMetrics,
    val currentScreen: ScreenMetrics,
    val changes: List<SettingsChange>,         // user-toggleable deltas
    val parseSkippedKeys: List<SkippedKey>,    // read-only (validation rejects + superseded legacy keys)
    val internalRemoves: List<String>,         // legacy keys deleted alongside their migrated counterparts
    val shortSwipeImportSize: Int,
    val shortSwipeImportRawJson: String?,      // ≤10 KB, retained for apply
)

data class LangWord(val lang: String, val word: String)
data class LangChanges(val newCustomWords: Map<String, Int>, val newDisabledWords: List<String>)

data class DictImportPlan(
    val sourceVersion: String,
    val perLanguage: Map<String, LangChanges>,
    val mergedCustomWordsByLang: Map<String, Map<String, Int>>,   // for apply
    val mergedDisabledWordsByLang: Map<String, Set<String>>,
)

enum class ShortSwipeImportMode { SKIP, MERGE, REPLACE }

class BackupRestoreManager(context: Context) {
    fun buildSettingsImportPlan(uri: Uri, prefs: SharedPreferences): SettingsImportPlan
    fun applySettingsImportPlan(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
        prefs: SharedPreferences
    ): ImportResult

    fun buildDictImportPlan(uri: Uri, prefs: SharedPreferences): DictImportPlan
    fun applyDictImportPlan(
        plan: DictImportPlan,
        excludedCustom: Set<LangWord>,
        excludedDisabled: Set<LangWord>,
        prefs: SharedPreferences
    ): DictionaryImportResult

    // Existing methods preserved unchanged for the headless Intent-action path:
    fun importConfig(uri: Uri, prefs: SharedPreferences): ImportResult       // calls build+apply with no exclusions internally
    fun importDictionaries(uri: Uri): DictionaryImportResult                  // same
}
```

`importConfig` and `importDictionaries` keep their exact public signature — internally they delegate to `buildPlan` then `applyPlan(plan, emptySet(), MERGE, prefs)`. The headless Intent path at `BackupRestoreActivity.kt:99-115` keeps working with **no UI change**.

### Pure-vs-IO testability split

```kotlin
// Pure — runs in runPureTests with no Robolectric
internal object SettingsImportPlanBuilder {
    fun fromJson(
        jsonString: String,
        currentSnapshot: Map<String, Any?>,    // copy of prefs.all
        screen: ScreenMetrics,
        sourceVersion: String,
    ): SettingsImportPlan
}

internal object DictImportPlanBuilder {
    fun fromJson(
        jsonString: String,
        currentCustomByLang: Map<String, Map<String, Int>>,
        currentDisabledByLang: Map<String, Set<String>>,
        sourceVersion: String,
    ): DictImportPlan
}
```

The Manager wraps these with thin URI-reading + snapshot-building methods that are unit-testable via MockK or instrumented tests.

### Apply semantics

- Build a single `SharedPreferences.Editor`, dispatch all `EditorOp.Put` and `EditorOp.Remove` calls, then call `editor.commit()` ONCE. Synchronous, returns boolean — converted into `ImportResult.applied` count.
- Drift detection: immediately before `commit()`, snapshot `prefs.all` again; for each un-excluded key, compare the plan's `current` field to the now-current value. Log one line `BackupRestore: N keys drifted between preview and apply` to logcat. No UI surfacing.
- Dict apply uses the same "single editor + commit" pattern, replacing today's four separate `apply()` calls (BackupRestoreManager.kt:1248, 1271, 1298, 1329) — fixes existing partial-state-on-OOM risk.
- `applySettingsImportPlan` separately invokes `shortSwipeManager.importFromJson(rawJson, merge = (mode == MERGE))` only if `mode != SKIP`. Mode `REPLACE` calls with `merge=false`. Mode `MERGE` calls with `merge=true` — this **also fixes the pre-existing latent bug** where `importConfig` line 597 hardcoded `merge=false`.

### Validation lives at build time

Build runs the same logic as today's `importPreference` / `validatePreferenceValue` (BackupRestoreManager.kt:621-744, 789-1068). Every accepted entry materializes into a typed `PrefValue` that the apply loop dispatches via `when`:

```kotlin
when (op) {
    is EditorOp.Put -> when (op.value) {
        is PrefValue.Bool    -> editor.putBoolean(op.key, op.value.v)
        is PrefValue.IntV    -> editor.putInt(op.key, op.value.v)
        is PrefValue.FloatV  -> editor.putFloat(op.key, op.value.v)
        is PrefValue.Str     -> editor.putString(op.key, op.value.v)
        is PrefValue.JsonBlob -> editor.putString(op.key, op.value.raw)
        PrefValue.Unset      -> editor.remove(op.key)   // unreachable for Puts; included for exhaustiveness
    }
    is EditorOp.Remove -> editor.remove(op.key)
}
```

There is no validation at apply time — a row checked in the preview is **guaranteed** to land in prefs. Validation-rejected keys flow into `parseSkippedKeys` at build time and render read-only in the UI.

`PrefValue` deliberately omits `LongV` and `StrSet`:
- No live pref uses `putLong`/`getLong` (Config.kt scan, archive-only usage).
- `isStringSetPreference` returns `false` for every pref (BackupRestoreManager.kt:1028).

### Legacy-margin migration

`migrateLegacyMargins` (BackupRestoreManager.kt:876-939) runs at build time, **before** diffing. The old `horizontal_margin_*` keys produce two outputs in the plan:

- New synthesized keys (`margin_left_*`, `margin_right_*`) → normal `SettingsChange` rows (ADDED or MODIFIED depending on current state).
- Legacy keys → `internalRemoves` list. Apply dispatches `editor.remove(legacyKey)` so they actually leave the SharedPreferences file (current code leaves them as orphans forever).
- Same legacy keys also appear in `parseSkippedKeys` with reason="superseded by new key" so the user can audit what was dropped, but they cannot toggle them.

### JsonBlob handling

For `layouts`, `extra_keys`, `custom_extra_keys`:

- Build canonicalizes both current and proposed via `JsonParser.parseString(...).toString()` before equality check — eliminates whitespace-only false positives.
- Both source representations are accepted (legacy double-encoded JSON-as-string-primitive; new native JsonObject/JsonArray) — the canonical form goes into `PrefValue.JsonBlob.raw`.
- UI renders these in a separate "Complex preferences (all-or-nothing)" section in the dialog, NOT mixed with scalar `SettingsChange` rows. v1 shows "Tap to view raw" which expands a side-by-side scrollable text view of current vs proposed. Structural diff is out of scope.

### Dictionary build — three-source priority

`importDictionaries` accepts:

1. v2 `custom_words_by_language` (line 1227)
2. Legacy `custom_words` (line 1282) — routed to English
3. Legacy `user_words` (line 1303) — routed to English

Plus disabled-word equivalents.

Today's code achieves first-writer-wins via `!existingWords.containsKey(word)` re-checks across blocks. Plan-builder must replay this priority order explicitly:

```kotlin
val merged = LinkedHashMap<LangWord, Int>()
// 1. v2 first
v2Map.forEach { (lang, words) -> words.forEach { (w, f) -> merged.putIfAbsent(LangWord(lang, w), f) } }
// 2. legacy custom_words → English
legacyCustomWords?.forEach { (w, f) -> merged.putIfAbsent(LangWord("en", w), f) }
// 3. legacy user_words → English
legacyUserWords?.forEach { (w, f) -> merged.putIfAbsent(LangWord("en", w), f) }
```

Naive `Map.plus` would give last-writer-wins — opposite of current behavior. Documented in code.

`LangWord(lang, word)` keeps case-sensitive (matches current behavior). "FOO" and "foo" remain separate entries.

### OOM safety

- `buildDictImportPlan` parses JSON, materializes merged maps, then drops the raw string reference before returning. Plan does NOT carry `rawJson: String` for dict imports.
- `shortSwipeImportRawJson` IS retained because the underlying `ShortSwipeCustomizationManager.importFromJson` requires raw JSON, but that blob is bounded ≤10 KB.

## UI flow

### Activity wiring

`BackupRestoreActivity` is migrated to use `BackupRestoreViewModel : ViewModel` for plan state. This is the **only** way to survive rotation cleanly — serializing 100 KB of plan to `onSaveInstanceState` is hostile.

```kotlin
class BackupRestoreViewModel : ViewModel() {
    var settingsPreviewPlan by mutableStateOf<SettingsImportPlan?>(null)
    var dictPreviewPlan by mutableStateOf<DictImportPlan?>(null)
    var isProcessing by mutableStateOf(false)
}
```

The Activity reads the VM via `viewModels()` delegate. Compose `LaunchedEffect`s gate dialogs on `plan != null`.

### Settings preview dialog

`SettingsImportPreviewDialog(plan, onApply, onCancel)` — fullscreen `Dialog`:

- **TopAppBar**: "Import Preview", back arrow → `onCancel`, Apply text-button right.
- **Header card**: source version, screen-mismatch warning if applicable.
- **Sectioned `LazyColumn`**:
  - **Modified (M)** — rows with `key`, `currentValue → proposedValue`, type chip, checkbox (default ON).
  - **Added (N)** — rows with `(unset) → proposedValue`, checkbox (default ON).
  - **Complex preferences** (`JsonBlob` only, all-or-nothing per blob) — single checkbox each + "View raw" disclosure.
  - **Short-swipe customizations** — 3-radio: SKIP / MERGE / REPLACE. Default = MERGE. Red warning text shown only when REPLACE is selected: "This will REPLACE your X existing short-swipe customizations."
  - **Invalid/skipped (S)** — collapsed expander, read-only.
- **Footer**: live "Apply N changes" count, Apply enabled even at zero (runs zero writes, surfaces "Nothing imported" in result).

### Dictionary preview dialog

`DictionaryImportPreviewDialog(plan, onApply, onCancel)`:

- TopAppBar same shape.
- Per-language collapsible sections, collapsed by default. Header row shows totals + a **2-state checkbox + count badge** (NOT Material `TriStateCheckbox`):
  - All children selected → checked
  - Zero children selected → unchecked
  - Partial → unchecked checkbox with "247 of 250 selected" badge
  - Tap toggles all
- Expanded section reveals "Custom Words" + "Disabled Words" sub-groups, each a nested `LazyColumn` with per-subgroup search field.
- **Why not Material tri-state**: tap on indeterminate cycles to "all on", which destroys cherry-picked deselections. Gmail/GitHub pattern is the standard for selection lists with parent control.
- Footer: live "Apply X custom + Y disabled", Apply always enabled.

### Result dialog

```
Import completed.
• Applied: N
• Excluded by you: M
• Invalid/skipped: S
• Source version: X.Y
[• Short-swipe: APPLIED (merge|replace) | SKIPPED]
[Screen-mismatch warning if applicable]

Restart the keyboard for changes to take effect.
```

Settings export:
```
Export complete.
• Settings exported: N
[• Short-swipe customizations: K]
File: <name>
```

Dict export:
```
Export complete.
• Custom words: N (across L languages)
• Disabled words: D
```

`exportConfig` modified to return `Int` count of exported preference entries. `exportDictionaries` already counts these internally (line 1127, 1146) but discards them — surface in return value.

### Empty-deltas shortcut

If `buildPlan` returns a plan with `changes.isEmpty() && parseSkippedKeys.isEmpty() && shortSwipeImportSize == 0`, the activity skips the preview dialog entirely and shows the result dialog with "No changes — backup file matches current settings." Same path for dict if `perLanguage.values.all { it.newCustomWords.isEmpty() && it.newDisabledWords.isEmpty() }`.

## Error handling

| Failure | When | Response |
|---|---|---|
| Malformed JSON / unreadable file | `buildPlan` throws | Skip preview; show "Import Failed" with exception message. |
| Zero deltas | Build returns empty plan | Skip preview; result dialog "No changes". |
| User deselects everything | `applyPlan` runs zero writes | Result dialog "Nothing imported (all changes excluded by you)". |
| `editor.commit()` returns false | Disk full, IPC death | Result dialog "Import partially failed: commit() returned false". |
| `DirectBootAwarePreferences.copy_preferences_to_protected_storage` throws | After applyPlan | Warning in result dialog: "Settings applied. Direct-boot copy failed — settings still active in user storage." (matches current behavior at line 612.) |
| Activity destroyed mid-build | Coroutine cancellation via `viewModelScope` | No leak; user re-opens activity, plan is gone. |
| User taps system back during preview | `BackHandler` inside dialog | Treat as Cancel; clear plan. |
| Plan drift between preview and apply | Detected at apply time | One-line logcat note for support; user sees their selections honored regardless. |

## Testing

### Pure JVM (`runPureTests`)

`SettingsImportPlanBuilderTest`:
- empty deltas when JSON matches snapshot
- modified key produces MODIFIED row with correct PrefValue type
- absent key produces ADDED row with `current = Unset`
- out-of-range value lands in `parseSkippedKeys` not `changes`
- legacy margin migration produces synthesized keys + `internalRemoves` entries + parseSkipped entries
- JsonBlob whitespace-only differences produce no diff (canonicalization)
- each PrefValue variant (Bool/IntV/FloatV/Str/JsonBlob) round-trips correctly
- internal preferences (like `last_input_method`) skipped

`DictImportPlanBuilderTest`:
- v2 format produces per-language deltas
- legacy `custom_words` routed to English
- legacy `user_words` routed to English
- v2 + legacy mix dedupes by `LangWord`, first-writer-wins (NOT last)
- existing words filtered out
- legacy disabled words routed to English

### MockK tests (`runMockTests`)

`SettingsImportPlanApplyTest`:
- writes only un-excluded keys
- empty plan → no editor calls
- short-swipe SKIP → no `shortSwipeManager` invocation
- short-swipe MERGE → manager called with `merge=true`
- short-swipe REPLACE → manager called with `merge=false`
- each PrefValue variant dispatches the correct `editor.put*`
- `internalRemoves` produces `editor.remove(key)` calls
- counts: applied / excludedByUser / parseSkipped / shortSwipeMode / driftCount

`DictImportPlanApplyTest`:
- excludes by `LangWord` correctly
- merges with existing prefs
- single `editor.commit()` call regardless of language count

### Compose UI tests (`androidTest`, ew-cli)

`SettingsImportPreviewDialogComposeTest`:
- Cancel does NOT invoke onApply
- onApply receives exact set of deselected keys
- parseSkipped rows are non-toggleable
- short-swipe defaults to MERGE radio
- short-swipe REPLACE shows red warning
- short-swipe SKIP suppresses warning
- Apply button stays enabled at zero selection
- header counts update live as rows toggle

`DictionaryImportPreviewDialogComposeTest`:
- language sections collapsed by default
- 2-state header checkbox + badge for partial selection
- tapping header toggles all word children
- search filter scoped to subgroup
- onApply passes per-LangWord exclusions

`BackupRestoreActivityComposeTest` (extend existing):
- empty deltas skip preview, show result directly
- export result dialog shows count
- headless Intent path bypasses preview (regression guard)

## Files

| Status | File | Δ lines |
|---|---|---|
| Modified | `BackupRestoreManager.kt` | +400 (~25%) |
| Modified | `BackupRestoreActivity.kt` | +80 |
| New | `BackupRestorePreviewDialogs.kt` | ~450 |
| New | `BackupRestoreViewModel.kt` | ~50 |
| New | `SettingsImportPlanBuilderTest.kt` | ~200 |
| New | `DictImportPlanBuilderTest.kt` | ~150 |
| New | `SettingsImportPlanApplyTest.kt` | ~120 |
| New | `DictImportPlanApplyTest.kt` | ~80 |
| New | `SettingsImportPreviewDialogComposeTest.kt` | ~150 |
| New | `DictionaryImportPreviewDialogComposeTest.kt` | ~120 |
| Modified | `BackupRestoreActivityComposeTest.kt` | +40 |
| Modified | `build.gradle` | +4 (register new pure tests) |

## Pre-existing latent bugs flagged but NOT fixed in this change

1. **`importConfig` line 597 hardcodes `merge=false` for short-swipe** — fixed implicitly by the new `ShortSwipeImportMode` flow. The legacy `importConfig` keeps `merge=false` for headless callers (Termux automation that documented this destructive behavior may rely on it; flag in todo.md if we want to flip the headless default later).
2. **`isIntegerStoredAsString` returns `false` always** despite the doc comment claiming it should fire for ListPreference-backed keys (BackupRestoreManager.kt:1018). Pre-existing; not in scope. Flag in todo.md as separate item.
3. **ListPreference `editor.putInt` overwrite of String-stored keys** — pre-existing read-side crash hazard in `importPreference` (line 689). Not in scope.

## Hard-won lessons to capture in `memory/todo.md`

- BackupRestoreManager build/apply split: validation MUST run at build time so a checked row is guaranteed to land in prefs.
- `editor.apply()` is fire-and-forget; for atomic count reporting use `commit()`.
- Tri-state checkbox is a footgun for selection lists with cherry-picked deselects — use 2-state-with-badge.
- `migrateLegacyMargins` synthesizes new keys but never removes old ones — orphans persist in SharedPreferences forever; use explicit `editor.remove(key)`.
- `importDictionaries` accepts THREE concurrent formats (v2 + 2 legacy) with `!containsKey` first-writer-wins semantics; preserve ordering when refactoring.
