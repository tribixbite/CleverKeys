# Clipboard Privacy

> **Note:** As of v1.4.0, the canonical version of this specification lives at
> [`docs/wiki/specs/clipboard/privacy-spec.md`](../wiki/specs/clipboard/privacy-spec.md) and renders at <https://cleverkeys.app/specs/clipboard/privacy-spec/>.
> This file is preserved for cross-references but may not be kept in sync.

## Overview

CleverKeys includes privacy features for the clipboard history system, primarily automatic exclusion of clipboard entries from password managers. When enabled, CleverKeys detects when the foreground app is a password manager and skips storing clipboard content, preventing sensitive credentials from appearing in clipboard history.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `PASSWORD_MANAGER_PACKAGES` | Set of excluded package names |
| `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt` | `getForegroundAppPackage()` | Detects current foreground app |
| `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt` | `isPasswordManagerApp()` | Checks if package is excluded |
| `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt` | `addCurrentClip()` | Skips storage if excluded app |
| `src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt` | Clipboard section | UI toggle for feature |

## Architecture

```
Clipboard Change Event (onPrimaryClipChanged)
       |
       v
+----------------------+
| ClipboardHistory     | -- System notifies of clipboard change
| Service              |
+----------------------+
       |
       v
+----------------------+
| getForegroundApp     | -- Detect which app copied
| Package()            |
+----------------------+
       |
       v
+----------------------+
| isPasswordManager    | -- Check against exclusion list
| App()                |
+----------------------+
       |
       v
+----------------------+
| IS_SENSITIVE flag    | -- Android 13+ sensitive content check
| (API 33+)           |
+----------------------+
       |
       v (if NOT excluded)
+------ text? --------+------- URI? ---------+
|                      |                      |
v                      v                      |
addClip(text)          Dispatchers.IO         |
                       processClipUri(uri)    |
                            |                 |
                   +--------+--------+        |
                   | text/* | media  |        |
                   v        v        |        |
              readTextFromUri  check media    |
                            toggles           |
                            |                 |
                   clipboard_media_enabled?   |
                   clipboard_text_only?       |
                            |                 |
                   (if both pass)             |
                   saveMedia() + addMediaClip |
+------------------------------------------+
```

### Media Privacy Gating

Media capture is gated by two independent settings:
- `clipboard_media_enabled` (default: true) — master toggle for media capture
- `clipboard_text_only` (default: false) — hides media from display AND blocks capture

Both must allow media for it to be captured. Media files are stored in app-private `filesDir/clipboard_media/` and excluded from Android Auto Backup.

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `clipboard_exclude_password_managers` | Boolean | true | Skip clipboard from password managers |
| `clipboard_respect_sensitive_flag` | Boolean | true | Honor Android 13+ IS_SENSITIVE flag |
| `clipboard_media_enabled` | Boolean | true | Enable media clipboard capture |
| `clipboard_text_only` | Boolean | false | Hide media, block media capture |

## Implementation Details

### Supported Password Managers

Package names recognized (defined in `Config.kt`):

| App | Package Name |
|-----|--------------|
| Bitwarden | `com.x8bit.bitwarden` |
| 1Password | `com.onepassword.android`, `com.agilebits.onepassword` |
| LastPass | `com.lastpass.lpandroid` |
| Dashlane | `com.dashlane` |
| KeePass2Android | `keepass2android.keepass2android` |
| KeePassDX | `com.kunzisoft.keepass.free`, `com.kunzisoft.keepass.pro` |
| Enpass | `io.enpass.app` |
| NordPass | `com.nordpass.android.app.password.manager` |
| RoboForm | `com.siber.roboform` |
| Keeper | `com.callpod.android_apps.keeper` |
| Proton Pass | `proton.android.pass` |
| SafeInCloud | `com.safeincloud` |
| mSecure | `com.msecure` |
| Zoho Vault | `com.zoho.vault` |
| Sticky Password | `com.stickypassword.android` |

### Foreground App Detection

Primary method uses `UsageStatsManager` (Android 5.1+):

```kotlin
private fun getForegroundAppPackage(): String? {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
        as UsageStatsManager
    val endTime = System.currentTimeMillis()
    val startTime = endTime - 1000 * 60  // Last minute

    val usageStats = usageStatsManager.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY, startTime, endTime
    )
    return usageStats.maxByOrNull { it.lastTimeUsed }?.packageName
}
```

Fallback uses `ActivityManager` (deprecated but still works):

```kotlin
private fun getForegroundAppPackageFallback(): String? {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
        as ActivityManager
    val runningTasks = activityManager.getRunningTasks(1)
    return runningTasks.firstOrNull()?.topActivity?.packageName
}
```

### Exclusion Check

```kotlin
private fun isPasswordManagerApp(packageName: String?): Boolean {
    if (packageName == null) return false
    return PASSWORD_MANAGER_PACKAGES.contains(packageName)
}

private fun addCurrentClip() {
    if (config.clipboardExcludePasswordManagers) {
        val foregroundApp = getForegroundAppPackage()
        if (isPasswordManagerApp(foregroundApp)) {
            return  // Skip storage
        }
    }
    // Proceed with normal storage
    saveToHistory(clipboardManager.primaryClip)
}
```

### Settings UI

```kotlin
SettingsSwitch(
    title = "Exclude Password Managers",
    description = "Don't store clipboard from Bitwarden, 1Password, LastPass, KeePass, etc.",
    checked = clipboardExcludePasswordManagers,
    onCheckedChange = {
        clipboardExcludePasswordManagers = it
        saveSetting("clipboard_exclude_password_managers", it)
    }
)
```

### Security Considerations

- **Detection Timing**: Foreground app checked at moment clipboard changes
- **False Positives**: Very low - package names are specific
- **False Negatives**: Apps not in list won't be excluded
- **Privacy**: Detection is purely local, no data leaves device

### Limitations

- Requires UsageStats permission on some devices (optional, falls back to ActivityManager)
- New password managers must be added to the package list manually
- Does not analyze clipboard content - only checks source app

## Private Copy (#156)

> **Canonical spec:** [`docs/wiki/specs/clipboard/private-copy-spec.md`](../wiki/specs/clipboard/private-copy-spec.md)
> (renders at <https://cleverkeys.app/specs/clipboard/private-copy-spec/>).
> **User guide:** [`docs/wiki/clipboard/private-copy.md`](../wiki/clipboard/private-copy.md).
> **Design doc:** [`docs/history/audits/remediation-plans/156-private-copy-paste.md`](../audit/remediation-plans/156-private-copy-paste.md).
> This section is an engineering-notes summary; see the canonical spec for full detail.

Private copy adds a copy path that stores selected text directly into `ClipboardDatabase`
with an `is_private` marker and **never** calls `ClipboardManager`. It complements the
password-manager exclusion above: exclusion prevents *capture* of OS-clipboard content from
sensitive apps, while private copy provides a *deliberate* capture route that bypasses the OS
clipboard entirely.

### Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `PrivateCopyProcessTextActivity.kt` | `onCreate` → `handle()` | Exported (but `android:enabled="false"`) `ACTION_PROCESS_TEXT` selection-toolbar receiver; entry point B |
| `clipboard/PrivateCopyIntentParser.kt` | `parse()` | Pure-JVM validation; the only intent-reading code (no `Intent` param, so no `clipData`/URI reachable) |
| `clipboard/PrivateCopyRateLimiter.kt` | `tryAcquire()` | Sliding-window 10/caller/min + 30/min global |
| `ClipboardHistoryService.kt` | `addPrivateClip()`, `privateCopy()`, `storeClip()` | Shared core with `addClip`; private path passes `rewriteOsClipboard = false` (never `systemClipboardRewrite`) |
| `ClipboardDatabase.kt` | `PrivateClipMergeRule`, `onUpgrade` (V4→V5), `exportToJSON(includePrivate)` | V5 schema, sticky-privacy dedup, export exclusion |
| `ClipboardHistoryView.kt` | `copyEntryToSystemClipboard()`, `privateBadge` | Confirm gate + lock badge |
| `KeyValue.kt` | `Editing.COPY_PRIVATE`, `"copy_private"` (`🔒⎘`) | In-IME editing key; entry point A |
| `ui/settings/sections/ClipboardSection.kt` | toggle + `setPrivateCopyToolbarComponentEnabled()` | Settings toggle flips the manifest-disabled component |

### Schema V5

`DATABASE_VERSION = 5`. O(1) `ALTER TABLE ADD COLUMN` on all three tables
(`clipboard_entries`, `pinned_entries`, `todo_entries` — pin/todo COPY semantics carry the marker):

- `is_private INTEGER NOT NULL DEFAULT 0`
- `source_package TEXT` (nullable) — provenance (`EditorInfo.packageName`, `getCallingPackage()`, or `"direct-launch"`)

Dedup is sticky: `is_private` ORs across duplicate copies; `source_package` most-recent-non-null wins.

### Export policy (option B)

`exportToJSON(includePrivate)` excludes private entries from **plaintext** exports (counted in the
summary) and includes them in **encrypted (CKENC)** exports with the marker round-tripping through
restore.

### Threat model

The PROCESS_TEXT activity is exported but ships disabled (`android:enabled="false"`); the settings
toggle flips the component. It is strictly inbound (text in → local row + toast → `finish()` with
`RESULT_CANCELED`), never returns a result, never reads a URI, is rate-limited, and records
kernel-attested provenance. See the design doc §6 for the full exported-surface analysis.

### Tests

Pure JVM: `PrivateCopyIntentParserTest` (14), `PrivateCopyRateLimiterTest` (7),
`PrivateClipMergeRuleTest` (9). MockK: `PrivateCopyServiceTest` (7, pins
`verify(exactly = 0) { cm.setPrimaryClip(any()) }`). Instrumented:
`PrivateCopyProcessTextActivityTest` (5), `ClipboardDatabaseV5MigrationTest` (8),
`PrivateCopyEditingKeyTest` (2), `ClipboardPanelPrivateBadgeTest` (4).
