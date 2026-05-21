---
title: Clipboard Privacy - Technical Specification
description: Password manager exclusion, Android 13+ IS_SENSITIVE handling, and media gating for the clipboard history system.
status: implemented
version: v1.4.0
---

# Clipboard Privacy Technical Specification

## Overview

CleverKeys includes privacy features for the clipboard history system, primarily automatic exclusion of clipboard entries from password managers. When enabled, CleverKeys detects when the foreground app is a password manager and skips storing clipboard content, preventing sensitive credentials from appearing in clipboard history. Android 13+ adds a second layer via the `IS_SENSITIVE` `ClipData` flag. Media capture is gated by two independent settings on top of these privacy checks.

For the underlying database schema, pinned/todo tables, and overall clipboard data flow, see [Clipboard History](clipboard-history-spec.md).

## Key Components

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `Defaults.PASSWORD_MANAGER_PACKAGES` | Set of excluded package names (Config.kt:236) |
| `src/main/kotlin/tribixbite/cleverkeys/ClipboardHistoryService.kt` | `getForegroundAppPackage()` | Detects current foreground app (ClipboardHistoryService.kt:480) |
| `src/main/kotlin/tribixbite/cleverkeys/ClipboardHistoryService.kt` | `isPasswordManagerApp()` | Checks if package is excluded (ClipboardHistoryService.kt:525) |
| `src/main/kotlin/tribixbite/cleverkeys/ClipboardHistoryService.kt` | `addCurrentClip()` | Skips storage if excluded app or sensitive flag set (ClipboardHistoryService.kt:541) |
| `src/main/kotlin/tribixbite/cleverkeys/ClipboardSettingsActivity.kt` | Clipboard section | UI toggle for feature |
| `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` | Clipboard section | UI toggle for feature |

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

Package names recognized (defined in `Config.kt` inside the `Defaults` object as `PASSWORD_MANAGER_PACKAGES`):

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

Primary method uses `UsageStatsManager` (Android 5.1+) with `ActivityManager` as a fallback. Both paths are wrapped in `try/catch` to silently handle the common case where `PACKAGE_USAGE_STATS` permission has not been granted (`ClipboardHistoryService.kt:480-520`):

```kotlin
@Suppress("DEPRECATION")
private fun getForegroundAppPackage(): String? {
    return try {
        if (VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            // Android 5.1+: Use UsageStatsManager (requires PACKAGE_USAGE_STATS permission)
            val usageStatsManager = _context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager != null) {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 5000 // Last 5 seconds
                val usageStats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime
                )
                if (!usageStats.isNullOrEmpty()) {
                    // Find the most recently used app
                    val recentApp = usageStats.maxByOrNull { it.lastTimeUsed }
                    if (recentApp != null && recentApp.lastTimeUsed > startTime) {
                        return recentApp.packageName
                    }
                }
            }
        }

        // Fallback: Use ActivityManager (deprecated but works on older APIs)
        val activityManager = _context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val runningTasks = activityManager.getRunningTasks(1)
            if (!runningTasks.isNullOrEmpty()) {
                return runningTasks[0].topActivity?.packageName
            }
        }
        null
    } catch (e: SecurityException) {
        // Permission not granted - this is expected, silently return null
        null
    } catch (e: Exception) {
        null
    }
}
```

### Exclusion Check

```kotlin
private fun isPasswordManagerApp(packageName: String?): Boolean {
    if (packageName == null) return false
    return Defaults.PASSWORD_MANAGER_PACKAGES.contains(packageName)
}

private fun addCurrentClip() {
    try {
        // Check if password manager exclusion is enabled
        if (Config.globalConfig().clipboard_exclude_password_managers) {
            val foregroundApp = getForegroundAppPackage()
            if (isPasswordManagerApp(foregroundApp)) {
                return // Don't store clipboard from password managers
            }
        }

        val clip = _cm.primaryClip ?: return

        // #86: Android 13+ (API 33): Respect IS_SENSITIVE flag set by password managers
        // This is a more robust detection than package blocklisting
        if (Config.globalConfig().clipboard_respect_sensitive_flag &&
            VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = clip.description?.extras
            if (extras != null) {
                val isSensitive = extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
                if (isSensitive) {
                    return // Don't store sensitive content
                }
            }
        }
        // ... proceed with text or URI handling
    }
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
- **False Positives**: Very low — package names are specific
- **False Negatives**: Apps not in list won't be excluded (Android 13+ `IS_SENSITIVE` flag is the robust fallback)
- **Privacy**: Detection is purely local, no data leaves device
- **No INTERNET permission**: All clipboard processing is on-device; media files live in app-private `filesDir`

### Limitations

- Requires UsageStats permission on some devices (optional, falls back to ActivityManager)
- New password managers must be added to the package list manually
- Does not analyze clipboard content — only checks source app and the `IS_SENSITIVE` `ClipData` flag

## Related Specifications

- [Clipboard History](clipboard-history-spec.md) — schema, pinned/todo tables, media storage, search
