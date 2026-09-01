---
title: Private Copy — Technical Specification
description: Copy selected text into CleverKeys' private clipboard history without ever touching the Android system clipboard
user_guide: ../../clipboard/private-copy.md
status: implemented
version: 1.5.0
---

# Private Copy Technical Specification

## Overview

Private copy (#156) adds a copy path that stores selected text directly into CleverKeys' clipboard database with an `is_private` marker, **never** invoking Android's `ClipboardManager`. It has two entry points that feed one storage primitive, `ClipboardHistoryService.addPrivateClip`, and surfaces private entries in the existing panel with a lock badge, provenance, an export-exclusion policy, and a confirm gate before any private entry can be pushed to the OS clipboard.

The baseline gap this fills: **paste from the panel was already private** (`KeyEventHandler.paste_from_clipboard_pane` commits text directly through the `InputConnection`, no `ClipboardManager`), but **every copy path went through the OS clipboard**. Private copy is the missing acquisition half — get selected text into `ClipboardDatabase` without the OS clipboard ever holding it.

Design source: `docs/history/audits/remediation-plans/156-private-copy-paste.md`.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| PROCESS_TEXT activity (entry point B) | `src/main/kotlin/tribixbite/cleverkeys/activities/PrivateCopyProcessTextActivity.kt:73` | Exported selection-toolbar receiver; entire lifecycle in `onCreate` → `handle()` → `finish()` |
| Intent parser (pure JVM) | `src/main/kotlin/tribixbite/cleverkeys/clipboard/PrivateCopyIntentParser.kt:17` | Only intent-reading code; validates action + single `EXTRA_PROCESS_TEXT`, trims, UTF-8 byte cap |
| Rate limiter (pure JVM) | `src/main/kotlin/tribixbite/cleverkeys/clipboard/PrivateCopyRateLimiter.kt:17` | Sliding-window 10/caller/min + 30/min global; injectable clock; process-lifetime state |
| Merge rule (pure JVM object) | `ClipboardDatabase.kt:68` (`object PrivateClipMergeRule`) | Sticky-privacy dedup: `mergeIsPrivate` (OR), `mergeSourcePackage` (most-recent-non-null) |
| Write path | `ClipboardHistoryService.kt:288` (`addPrivateClip`), `:301` (`storeClip`), `:923` (`privateCopy`) | Shared core with `addClip`; private path passes `rewriteOsClipboard = false` |
| V5 migration + schema | `ClipboardDatabase.kt:244` (`onUpgrade` branch), `:1802` (`DATABASE_VERSION = 5`) | `ALTER TABLE ADD COLUMN` on all three tables |
| Export exclusion | `ClipboardDatabase.kt:1397` (`exportToJSON(textOnly, includePrivate)`) | Option B: exclude from plaintext, include in encrypted, marker round-trips |
| Confirm gate (read path) | `ClipboardHistoryView.kt:761` (`copyEntryToSystemClipboard`) | Confirm dialog before pushing a private entry to the OS clipboard |
| Panel badge | `ClipboardHistoryView.kt:1078, :1174` (`privateBadge`) | Lock badge visible for `entry.isPrivate` |
| Panel provenance line | `ClipboardHistoryView.kt:108` (`provenanceText`), `:1285` (render), `clipboard/ClipboardProvenance.kt` (pure label rule) | "Private copy · via ⟨app label⟩" in the **expanded** row only. Label resolved via `PackageManager`, falling back to the raw package name when the source app is gone (or invisible under API-30+ package filtering); `"direct-launch"` renders as an injection tell. NULL `source_package` renders no line. |
| Editing key (entry point A) | `KeyValue.kt:103` (`Editing.COPY_PRIVATE`), `:719` (`"copy_private"` → `🔒⎘`) | Named key + glyph, `FLAG_SMALLER_FONT` |
| Editing-key dispatch | `KeyEventHandler.kt:645` (`handleEditingKey`), `Keyboard2View.kt:763` (`executeEditingCommand`) | `getSelectedText(0)` → `privateCopy`; edit-mode no-op |
| Settings toggle + component flip | `ClipboardSection.kt:238` (toggle), `:351` (`setPrivateCopyToolbarComponentEnabled`) | Flips the manifest-disabled component via `PackageManager` |

## Architecture

```
Entry point A (in-IME key)                Entry point B (PROCESS_TEXT toolbar)
──────────────────────────                ────────────────────────────────────
KeyEventHandler.handleEditingKey          PrivateCopyProcessTextActivity.onCreate
  / Keyboard2View.executeEditingCommand     ├─ Config.initGlobalConfig if null
  COPY_PRIVATE branch                       ├─ Guard: isUserUnlocked()
  ├─ getSelectedText(0)                      ├─ Guard: PREF_TOOLBAR_ENABLED
  ├─ null/empty → "No text selected"         ├─ PrivateCopyIntentParser.parse(...)
  └─ ClipboardHistoryService.privateCopy     ├─ PrivateCopyRateLimiter.tryAcquire(caller)
       (ctx, text, EditorInfo.packageName)   └─ ClipboardHistoryService.privateCopy
                                                  (ctx, text, callingPackage ?: "direct-launch")
                    │                                        │
                    └───────────────┬────────────────────────┘
                                    ▼
                 ClipboardHistoryService.addPrivateClip(text, sourcePackage)
                    → storeClip(rewriteOsClipboard = false, isPrivate = true, sourcePackage)
                        ├─ size cap  (identical to addClip)
                        ├─ URL sanitizer (identical — store hygiene)
                        ├─ systemClipboardRewrite  ← SKIPPED (rewriteOsClipboard = false)
                        └─ addClipboardEntry(content, expiry, isPrivate = true, sourcePackage)
                             → sticky-privacy dedup (PrivateClipMergeRule)
                             → INSERT is_private=1, source_package
                                    │
                                    ▼
                 Panel: lock badge (row) + provenance line (expanded row);
                 paste stays private (panel-paste path);
                 copyEntryToSystemClipboard() confirms before any OS-clipboard push.
```

## Key Code Patterns

### The single load-bearing difference: the private path never rewrites the OS clipboard

`addClip` and `addPrivateClip` share `storeClip`; they differ only in the `rewriteOsClipboard` flag, which gates the one `systemClipboardRewrite` call. From `ClipboardHistoryService.kt:275, :288`:

```kotlin
storeClip(clip, rewriteOsClipboard = true, isPrivate = false, sourcePackage = null)
```
```kotlin
fun addPrivateClip(text: String?, sourcePackage: String?) {
    storeClip(text, rewriteOsClipboard = false, isPrivate = true, sourcePackage = sourcePackage)
```

And the guarded call inside `storeClip` (`ClipboardHistoryService.kt:344`):

```kotlin
if (rewriteOsClipboard) {
    systemClipboardRewrite(
```

### Pure-JVM intent parsing (structurally closes the confused-deputy class)

`PrivateCopyIntentParser.parse` takes only primitives + a `CharSequence` — no `Intent`, so it cannot receive `clipData`, `EXTRA_STREAM`, or a URI (`PrivateCopyIntentParser.kt:49`):

```kotlin
fun parse(action: String?, text: CharSequence?, maxBytes: Int): Result {
    if (action != ACTION_PROCESS_TEXT) return Result.Reject(Reason.WRONG_ACTION)
    if (text == null) return Result.Reject(Reason.NO_EXTRA)
    val str = text.toString()
    val trimmed = str.trim()
    if (trimmed.isEmpty()) return Result.Reject(Reason.BLANK)
    if (maxBytes > 0) {
        val bytes = trimmed.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > maxBytes) return Result.Reject(Reason.OVER_CAP)
    }
    return Result.Accept(trimmed)
}
```

### Config-init trap in the standalone activity

The activity is a valid cold-start process entry point, and `Config.globalConfig()` throws when the IME hasn't initialized it. It follows the `SwipeCalibrationActivity` precedent (`PrivateCopyProcessTextActivity.kt:55`):

```kotlin
if (Config.globalConfigOrNull() == null) {
    val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
    Config.initGlobalConfig(prefs, resources, null, false)
}
```

## Schema V5 (clipboard DB)

`DATABASE_VERSION = 5` (`ClipboardDatabase.kt:1802`). The `onUpgrade` V4→V5 branch runs an O(1), non-destructive `ALTER TABLE ADD COLUMN` on all three tables (`clipboard_entries`, `pinned_entries`, `todo_entries` — pin/todo use COPY semantics, so the marker must travel). From `ClipboardDatabase.kt:253`:

```kotlin
db.execSQL("ALTER TABLE $table ADD COLUMN $COLUMN_IS_PRIVATE INTEGER NOT NULL DEFAULT 0")
db.execSQL("ALTER TABLE $table ADD COLUMN $COLUMN_SOURCE_PACKAGE TEXT")
```

| Column | Type | Semantics |
|--------|------|-----------|
| `is_private` (`COLUMN_IS_PRIVATE`, `:1830`) | `INTEGER NOT NULL DEFAULT 0` | 1 = private. Existing/normal rows default to 0. |
| `source_package` (`COLUMN_SOURCE_PACKAGE`, `:1831`) | `TEXT` (nullable) | Provenance. Entry A: `EditorInfo.packageName`; entry B: `getCallingPackage()` or `"direct-launch"`; NULL otherwise. |

### Sticky-privacy dedup

`addClipboardEntry` dedups by `content_hash` + `content` and moves a duplicate to the top. On merge it applies `PrivateClipMergeRule` (`ClipboardDatabase.kt:288`):

```kotlin
// #156: sticky-privacy merge — is_private ORs; source_package most-recent-non-null.
val mergedPrivate = PrivateClipMergeRule.mergeIsPrivate(cursor.getInt(1) != 0, isPrivate)
```

- **`is_private := old OR new`** — privately copying text that already exists as a normal entry upgrades the row to private (henceforth export-excluded and push-gated). A later normal copy of existing private content keeps `is_private = 1` (that normal copy already put the text on the OS clipboard; stickiness preserves policy, it does not un-expose).
- **`source_package`: most-recent-non-null wins.**

### COPY-propagation through pin / todo

`pinEntry` (`ClipboardDatabase.kt:549`) and `addTodoEntry` (`:776`) read the source row's `is_private`/`source_package`, sticky-merge with any explicit args, and write the merged values into the copy — so a pinned private entry stays private forever (pins don't expire).

## Export Exclusion (Option B)

`exportToJSON(textOnly = false, includePrivate = true)` (`ClipboardDatabase.kt:1397`) gates private rows across all three tables:

```kotlin
if (isPrivate && !includePrivate) { privateSkipped++; continue }
```

- **Plaintext export** passes `includePrivate = false`: private entries are excluded and counted; the export summary appends `" (N private excluded)"` (`:1528`). A plaintext JSON on a shared drive never contains private entries.
- **Encrypted (CKENC) export** passes `includePrivate = true`: private rows are written with their `is_private`/`source_package` fields, so the marker round-trips through backup/restore. Absent fields default to non-private for old backups.

## Threat Model — the exported PROCESS_TEXT activity

The activity is exported by design but ships **`android:enabled="false"`** in the manifest (`AndroidManifest.xml:136`); the settings toggle flips the component. While disabled it is invisible to resolvers — zero exported surface for users who never opt in, preserving the prior de-export hardening posture.

| Property | Mitigation |
|----------|-----------|
| **Default-off** | `android:enabled="false"`; `setPrivateCopyToolbarComponentEnabled` flips via `setComponentEnabledSetting(..., DONT_KILL_APP)`, using `STATE_DISABLED` (not `_DEFAULT`) on the off-path (`ClipboardSection.kt:351`). |
| **Confidentiality** | No read path exists. The activity **never** calls `setResult` — default `RESULT_CANCELED`, no result extras, so a caller learns nothing and the host app's text/selection is never mutated. |
| **Confused deputy** | The pure-JVM parser is the only intent-reading code and cannot receive `clipData`/URIs — no `ContentResolver`, no file paths, no granted permissions. |
| **Rate / spam** | `PrivateCopyRateLimiter`: 10 accepts/caller/min + 30/min global, excess dropped silently with `Log.w` (no toast — no UI channel for a flooder). Layered on top of platform background-activity-launch restrictions. |
| **Provenance** | `getCallingPackage()` (kernel-attested, unspoofable) recorded as `source_package` **and rendered** in the expanded panel row as "Private copy · via ⟨app⟩"; a `forResult`-less launch (which the real toolbar never does) is recorded as `"direct-launch"` and rendered as an explicit injection tell. This display is what converts the unanswerable prevention question into a detection answer — it is load-bearing for the §6 risk acceptance, not decoration. |
| **Direct Boot** | `directBootAware="false"` + an `isUserUnlocked()` guard — the DB lives in Credential-Encrypted storage. |
| **Windowless** | `Theme.NoDisplay`; `finish()` runs inside `onCreate`'s `finally`, satisfying the "must finish before onResume" contract. |

## Configuration

| Setting | Key | Default | Source |
|---------|-----|---------|--------|
| **Private copy in other apps' selection menus** | `clipboard_private_copy_toolbar_enabled` | `false` | `PrivateCopyProcessTextActivity.kt:119` (`PREF_TOOLBAR_ENABLED`), toggle at `ClipboardSection.kt:238`, classified in `backup/SettingsDefaults.kt:231` |

The pref is the single source of truth; the component-enabled state is derived from it (no second source of truth). The `SettingsDefaultsDriftTest` tripwire requires the key to be classified in `SETTINGS_DEFAULTS`.

## Test Coverage

| Suite | File | Cases |
|-------|------|-------|
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/clipboard/PrivateCopyIntentParserTest.kt` | 14 |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/clipboard/PrivateCopyRateLimiterTest.kt` | 7 |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/clipboard/PrivateClipMergeRuleTest.kt` | 9 |
| MockK | `src/test/kotlin/tribixbite/cleverkeys/PrivateCopyServiceTest.kt` | 7 |
| Instrumented | `src/androidTest/kotlin/tribixbite/cleverkeys/PrivateCopyProcessTextActivityTest.kt` | 5 |
| Instrumented | `src/androidTest/kotlin/tribixbite/cleverkeys/ClipboardDatabaseV5MigrationTest.kt` | 8 |
| Instrumented | `src/androidTest/kotlin/tribixbite/cleverkeys/PrivateCopyEditingKeyTest.kt` | 2 |
| Instrumented | `src/androidTest/kotlin/tribixbite/cleverkeys/ClipboardPanelPrivateBadgeTest.kt` | 4 |

`PrivateCopyServiceTest` pins the load-bearing regression: `verify(exactly = 0) { cm.setPrimaryClip(any()) }` across the whole private path (it lives in `src/test/` but uses MockK, so it runs under `runMockTests`). A shared `PrivateCopyClipboardTestHelper.kt` supports the instrumented suites.

## Related Specifications

- [Clipboard History Spec](./clipboard-history-spec.md) - The panel and database private entries live in
- [Clipboard Privacy Spec](./privacy-spec.md) - Password-manager exclusion and other clipboard privacy
