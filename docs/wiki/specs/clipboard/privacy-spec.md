---
title: Clipboard Privacy - Technical Specification
description: Password manager exclusion, Android 13+ IS_SENSITIVE handling, and media gating for the clipboard history system.
status: implemented
version: v1.4.0
---

# Clipboard Privacy Technical Specification

## Overview

CleverKeys includes privacy features for the clipboard history system. The **reliable** mechanism is the Android 13+ `IS_SENSITIVE` `ClipData` flag (`clipboard_respect_sensitive_flag`, default on): password managers mark credential copies as sensitive and CleverKeys skips storing them, regardless of which app copied. The package-list **password-manager exclusion** (`clipboard_exclude_password_managers`) is a *best-effort* extra layer: it can only skip a copy when Android reveals that a known password manager is in the foreground, which requires the `PACKAGE_USAGE_STATS` app-op — an op the manifest deliberately never declares (D-1, maintainer decision 2026-09-08, reworded in all locales in `870cf499`). Without that grant, foreground detection returns `null` and the exclusion fails open. Media capture is gated by two independent settings and a size cap on top of these privacy checks.

For the underlying database schema, pinned/todo tables, and overall clipboard data flow, see [Clipboard History](clipboard-history-spec.md).

## Key Components

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `Defaults.PASSWORD_MANAGER_PACKAGES` | Set of excluded package names (Config.kt:243) |
| `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt` | `getForegroundAppPackage()` | Attempts foreground-app detection; returns `null` without usage access (`ClipboardHistoryService.kt:625`) |
| `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt` | `isPasswordManagerApp()` | Checks if package is excluded (`ClipboardHistoryService.kt:670`) |
| `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt` | `addCurrentClip()` | Skips storage if excluded app detected or sensitive flag set (`ClipboardHistoryService.kt:687`) |
| `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/ClipboardSection.kt` | Clipboard section | UI toggles for both settings (`ClipboardSection.kt:177`, `:188`) |

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

Media capture is gated by two independent settings plus a size cap:

- `clipboard_media_enabled` (default: true) — master toggle for media capture
- `clipboard_text_only` (default: false) — hides media from display AND blocks capture
- `clipboard_max_media_size_mb` (default: 10, range 1-50) — larger files are skipped at capture (`ClipboardHistoryService.kt:774`); the shared range constant is `SettingsRanges.CLIPBOARD_MAX_MEDIA_SIZE_MB` (`Config.kt:456`), consumed by the slider, `SettingsValidation`, and the `Config` read site

Both toggles must allow media for it to be captured. Since 2026-09-08 (F-8, `68bafddc`) all three have controls in the Clipboard settings section — the media switch and MB slider are disabled-not-hidden while Text Only is on, which wins at the capture site. Media files are stored in app-private `filesDir/clipboard_media/` and excluded from Android Auto Backup.

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `clipboard_exclude_password_managers` | Boolean | true | Best-effort skip of clips from known password managers (requires foreground detection to succeed) |
| `clipboard_respect_sensitive_flag` | Boolean | true | Honor Android 13+ IS_SENSITIVE flag (the reliable protection) |
| `clipboard_media_enabled` | Boolean | true | Enable media clipboard capture |
| `clipboard_text_only` | Boolean | false | Hide media, block media capture |
| `clipboard_max_media_size_mb` | Int | 10 | Skip media larger than this (1-50 MB) |

## Implementation Details

### Recognized Password Manager Packages

Package names checked when foreground detection succeeds (defined in `Config.kt` inside the `Defaults` object as `PASSWORD_MANAGER_PACKAGES`, `Config.kt:243`):

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

### Foreground App Detection (why the exclusion is best-effort)

Primary method uses `UsageStatsManager` with `ActivityManager.getRunningTasks` as a fallback. Both paths are wrapped in `try/catch` and the method returns `null` on failure (`ClipboardHistoryService.kt:625`). In practice **both paths fail on effectively every device**:

- `UsageStatsManager.queryUsageStats` returns an empty list unless the user has granted usage access, and the manifest **never declares** `PACKAGE_USAGE_STATS` — so the grant screen isn't even reachable for CleverKeys (maintainer decision D-1: the app-op stays undeclared).
- `getRunningTasks` has returned only the caller's own tasks since API 21, so the fallback can never observe another app.

A `null` result means no exclusion happens — the setting fails open. This is why the UI wording and this spec name the `IS_SENSITIVE` flag as the protection to rely on.

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

`ClipboardSection.kt:175-183`:

```kotlin
// Privacy: Exclude password managers
SettingsSwitch(
    title = stringResource(R.string.clipboard_exclude_password_managers_title),
    description = stringResource(R.string.clipboard_exclude_password_managers_desc),
    checked = clipboardExcludePasswordManagers,
    onCheckedChange = {
        clipboardExcludePasswordManagers = it
        saveSetting("clipboard_exclude_password_managers", clipboardExcludePasswordManagers)
    }
)
```

The description string is deliberately honest (reworded in all 22 locales, `870cf499`; pinned against overpromise regression by `SettingsSurfaceDriftTest`):

> Best effort: only skips a copy when Android reveals that a known password manager is in the foreground, which is rarely possible without usage access (never requested). Rely on the sensitive-content setting below.

### Security Considerations

- **The `IS_SENSITIVE` flag is the load-bearing protection** (Android 13+): it needs no permissions, works for any password manager that sets it, and is on by default
- **Package-list exclusion normally does nothing**: without the never-declared `PACKAGE_USAGE_STATS` grant, detection returns `null` and the clip is stored — treat any skip it produces as a bonus, not a guarantee
- **Privacy**: Detection is purely local, no data leaves device
- **No INTERNET permission**: All clipboard processing is on-device; media files live in app-private `filesDir`

### Limitations

- Foreground detection requires the `PACKAGE_USAGE_STATS` app-op, which the manifest never declares — the user cannot grant it; the `getRunningTasks` fallback has been dead since API 21
- Pre-Android-13 devices have no `IS_SENSITIVE` flag, so clips from password managers that don't clear the clipboard themselves ARE captured there
- New password managers must be added to the package list manually
- Does not analyze clipboard content — only checks source app and the `IS_SENSITIVE` `ClipData` flag

## Related Specifications

- [Clipboard History](clipboard-history-spec.md) — schema, pinned/todo tables, media storage, search
