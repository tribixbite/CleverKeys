# Clipboard Privacy Specification

**Status**: Implemented
**Version**: 1.2.8
**Last Updated**: 2026-01-15

## Overview

CleverKeys includes privacy features for the clipboard history system. The primary feature is automatic exclusion of clipboard entries from password managers, preventing sensitive credentials from being stored in clipboard history.

## Password Manager Exclusion

### Feature Description

When enabled, CleverKeys automatically detects when the foreground app is a password manager and skips storing clipboard content from those apps. This prevents passwords, usernames, and other sensitive data from appearing in clipboard history.

### Supported Password Managers

The following package names are recognized (defined in `Config.kt`):

| App | Package Name |
|-----|--------------|
| Bitwarden | `com.x8bit.bitwarden` |
| 1Password | `com.onepassword.android`, `com.agilebits.onepassword` |
| LastPass | `com.lastpass.lpandroid` |
| Dashlane | `com.dashlane` |
| KeePass2Android | `keepass2android.keepass2android`, `keepass2android.keepass2android_nonet` |
| KeePassDX | `com.kunzisoft.keepass.free`, `com.kunzisoft.keepass.pro` |
| OpenKeePass | `de.slackspace.openkeepass` |
| Enpass | `io.enpass.app` |
| NordPass | `com.nordpass.android.app.password.manager` |
| RoboForm | `com.siber.roboform` |
| Keeper | `com.callpod.android_apps.keeper` |
| Proton Pass | `proton.android.pass` |
| SafeInCloud | `com.safeincloud` |
| mSecure | `com.msecure` |
| Zoho Vault | `com.zoho.vault` |
| Sticky Password | `com.stickypassword.android` |

### Implementation

#### Config.kt

```kotlin
// Default setting
const val CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS = true

// Package name list
val PASSWORD_MANAGER_PACKAGES = setOf(
    "com.x8bit.bitwarden",
    "com.onepassword.android",
    // ... (20+ packages)
)
```

#### ClipboardHistoryService.kt

Key methods:

| Method | Purpose |
|--------|---------|
| `getForegroundAppPackage()` | Detects current foreground app |
| `isPasswordManagerApp(packageName)` | Checks if package is in exclusion list |
| `addCurrentClip()` | Skips storage if password manager detected |

**Foreground App Detection:**

```kotlin
// Primary: UsageStatsManager (Android 5.1+)
val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
val usageStats = usageStatsManager.queryUsageStats(INTERVAL_DAILY, startTime, endTime)
val recentApp = usageStats.maxByOrNull { it.lastTimeUsed }

// Fallback: ActivityManager (deprecated but works)
val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
val runningTasks = activityManager.getRunningTasks(1)
return runningTasks[0].topActivity?.packageName
```

#### SettingsActivity.kt

UI toggle in Clipboard section:

```kotlin
SettingsToggle(
    title = "Exclude Password Managers",
    description = "Don't store clipboard from Bitwarden, 1Password, LastPass, KeePass, etc.",
    checked = clipboardExcludePasswordManagers,
    onCheckedChange = { saveSetting("clipboard_exclude_password_managers", it) }
)
```

### Settings

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| Exclude Password Managers | `clipboard_exclude_password_managers` | `true` | Skip clipboard from password managers |

### Security Considerations

1. **Detection Timing**: Foreground app is checked at the moment clipboard changes
2. **False Positives**: Very low - package names are specific
3. **False Negatives**: Apps not in list won't be excluded (users can request additions)
4. **No Internet**: Detection is purely local, no data leaves device

### Limitations

1. Requires UsageStats permission on some devices (optional, falls back to ActivityManager)
2. New password managers must be added to the package list manually
3. Does not detect clipboard content - only checks source app

## Future Enhancements

- User-configurable package exclusion list
- Regex-based package name matching
- Clipboard content analysis (detect password patterns)
- Temporary clipboard mode (auto-clear after paste)

## Related Files

- `Config.kt` - Default settings and package list
- `ClipboardHistoryService.kt` - Detection and filtering logic
- `SettingsActivity.kt` - UI toggle

## References

- [Android UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [GitHub Issue #62](https://github.com/Julow/Unexpected-Keyboard/issues/62) (Meta key - not related)
