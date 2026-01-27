---
title: Privacy Settings
description: Control data collection and storage
category: Settings
difficulty: beginner
---

# Privacy Settings

Control what data CleverKeys collects, stores, and how your information is handled.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Manage privacy and data |
| **Access** | Settings > Clipboard / Privacy sections |
| **Principle** | Local-first, no cloud by default |

## Privacy Philosophy

CleverKeys is designed with privacy as a core principle:

- **Local processing**: All AI runs on your device
- **No cloud upload**: Data never leaves your device by default
- **No analytics**: No usage tracking or telemetry
- **You control data**: Export or delete anytime

## Clipboard Privacy

### Clipboard Settings

Found under the **Clipboard** section in Settings:

| Setting | Description |
|---------|-------------|
| **Clipboard History** | Enable/disable clipboard history |
| **Clipboard History Limit** | Maximum items to keep (default: 50) |
| **Clipboard Size Limit** | Total size limit in MB |
| **Clipboard Max Item Size** | Maximum size per item in KB |
| **Exclude Password Managers** | Don't save clips from 1Password, Bitwarden, etc. |
| **Respect Sensitive Flag** | Honor Android 13+ IS_SENSITIVE flag |

### Sensitive Content Protection

CleverKeys automatically protects sensitive content:

| Protection | How It Works |
|------------|--------------|
| **Password Fields** | Detected automatically, clipboard disabled |
| **Password Managers** | Clips from password apps excluded (when enabled) |
| **Sensitive Flag** | Android 13+ apps can mark content as sensitive |

## Incognito Mode

Found in the **Privacy** section:

When enabled:
- Predictions disabled
- Learning disabled
- Nothing saved to history

Useful for entering sensitive information in any app.

## Data Storage

### What's Stored

| Data | Location | Purpose |
|------|----------|---------|
| **Settings** | App preferences | Your configuration |
| **Dictionary** | App data | Personal words |
| **Profiles** | App data | Saved configurations |
| **Clipboard** | App data | Recent clips (up to limit) |

### Storage Location

All data is stored locally on your device:

```
/data/data/tribixbite.cleverkeys/
├── shared_prefs/     # Settings
├── files/            # Dictionary, profiles
└── databases/        # Clipboard history
```

## Secure Input

### Password Fields

When a text field is marked as password:

- Predictions disabled
- Learning disabled
- Clipboard disabled
- Keyboard behaves in secure mode

## Data Export and Deletion

### Export Your Data

Use Settings > Backup & Restore to:
- Export settings and preferences
- Export clipboard history
- Export profiles

### Clear Data

| Method | What's Cleared |
|--------|----------------|
| **Clear Clipboard** | Delete items via clipboard panel |
| **Reset Settings** | Settings > Backup & Restore > Reset |
| **Clear App Data** | Android Settings > Apps > CleverKeys > Clear Data |

## Privacy Settings Reference

| Setting | Section | Default |
|---------|---------|---------|
| **Clipboard History** | Clipboard | On |
| **History Limit** | Clipboard | 50 items |
| **Exclude Password Managers** | Clipboard | On |
| **Respect Sensitive Flag** | Clipboard | On |
| **Incognito Mode** | Privacy | Off |

## Network Privacy

CleverKeys does not require network access for core functionality:

| Feature | Network Required |
|---------|------------------|
| **Typing** | No |
| **Predictions** | No |
| **Autocorrect** | No |
| **Themes** | No |

All processing happens locally on your device.

## Common Questions

### Q: Does CleverKeys send data to servers?

A: No. All processing happens locally on your device.

### Q: Are my passwords safe?

A: Password fields are automatically protected - no learning, no clipboard, no predictions.

### Q: How do I completely clear my data?

A: Go to Android Settings > Apps > CleverKeys > Storage > Clear Data. This removes all personal data while keeping the app installed.

### Q: Can I use CleverKeys without any data storage?

A: Enable Incognito Mode for reduced data retention (predictions will be less personalized).

## Related Features

- [Clipboard History](../clipboard/clipboard-history.md) - Manage clipboard
- [Backup & Restore](../troubleshooting/backup-restore.md) - Data management
