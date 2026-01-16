# Clipboard Privacy

## Overview

CleverKeys includes privacy features for the clipboard history system, primarily automatic exclusion of clipboard entries from password managers. When enabled, CleverKeys detects when the foreground app is a password manager and skips storing clipboard content, preventing sensitive credentials from appearing in clipboard history.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `PASSWORD_MANAGER_PACKAGES` | Set of excluded package names |
| `src/main/kotlin/tribixbite/cleverkeys/ClipboardHistoryService.kt` | `getForegroundAppPackage()` | Detects current foreground app |
| `src/main/kotlin/tribixbite/cleverkeys/ClipboardHistoryService.kt` | `isPasswordManagerApp()` | Checks if package is excluded |
| `src/main/kotlin/tribixbite/cleverkeys/ClipboardHistoryService.kt` | `addCurrentClip()` | Skips storage if excluded app |
| `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` | Clipboard section | UI toggle for feature |

## Architecture

```
Clipboard Change Event
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
       v (if NOT excluded)
+----------------------+
| Store to clipboard   | -- Save to history database
| history              |
+----------------------+
```

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `clipboard_exclude_password_managers` | Boolean | true | Skip clipboard from password managers |

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
