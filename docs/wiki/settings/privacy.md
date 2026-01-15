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
| **Access** | Settings > Privacy |
| **Principle** | Local-first, no cloud by default |

## Privacy Philosophy

CleverKeys is designed with privacy as a core principle:

- **Local processing**: All AI runs on your device
- **No cloud upload**: Data never leaves your device by default
- **No analytics**: No usage tracking or telemetry
- **You control data**: Export or delete anytime

## Learning Data

### Personal Dictionary

| Setting | Description |
|---------|-------------|
| **Save Words** | Remember words you add |
| **Auto-Learn** | Automatically learn new words |
| **Clear Dictionary** | Delete all learned words |

### Usage Patterns

| Setting | Description |
|---------|-------------|
| **Remember Frequency** | Track word usage for better predictions |
| **Clear Patterns** | Reset frequency data |

## Clipboard Privacy

### Clipboard History

| Setting | Description |
|---------|-------------|
| **Enable History** | Keep clipboard history |
| **History Duration** | How long to keep items |
| **Auto-Clear** | Clear after time period |
| **Clear Now** | Immediately clear history |

### Sensitive Fields

| Setting | Description |
|---------|-------------|
| **Detect Password Fields** | Don't save to history |
| **Incognito Mode** | Never save clipboard |

## Data Storage

### What's Stored

| Data | Location | Purpose |
|------|----------|---------|
| **Settings** | App preferences | Your configuration |
| **Dictionary** | App data | Personal words |
| **Profiles** | App data | Saved configurations |
| **Clipboard** | App cache | Recent clips |

### Storage Location

All data is stored locally on your device:

```
/data/data/app.cleverkeys/
├── shared_prefs/     # Settings
├── files/            # Dictionary, profiles
└── cache/            # Temporary data
```

## Secure Input

### Password Fields

When a text field is marked as password:

- Predictions disabled
- Learning disabled
- Clipboard disabled
- Incognito indicators shown

### Incognito Mode

Manual incognito for any field:

1. Long-press the keyboard settings key
2. Toggle **Incognito Mode**
3. All learning and history disabled
4. Indicator shows incognito active

## Data Export and Deletion

### Export Your Data

1. Settings > Privacy > Export Data
2. Choose what to export:
   - Personal dictionary
   - Usage patterns
   - Profiles
3. Save to file

### Delete Data

| Option | What's Deleted |
|--------|----------------|
| **Clear Dictionary** | Personal words only |
| **Clear Patterns** | Usage frequency data |
| **Clear Clipboard** | Clipboard history |
| **Reset All** | All personal data |

## Tips and Tricks

- **Shared devices**: Use incognito mode
- **Sensitive apps**: Enable incognito per-app
- **New start**: Clear all data for fresh experience
- **Backup first**: Export before clearing

> [!TIP]
> Enable auto-clear for clipboard to maintain privacy without manual intervention.

## Privacy Settings Reference

| Setting | Location | Default |
|---------|----------|---------|
| **Auto-Learn Words** | Privacy | On |
| **Clipboard History** | Privacy | On |
| **History Duration** | Privacy | 24 hours |
| **Detect Password** | Privacy | On |
| **Incognito Mode** | Privacy | Off |

## Network Privacy

CleverKeys does not require network access for core functionality:

| Feature | Network Required |
|---------|------------------|
| **Typing** | No |
| **Predictions** | No |
| **Autocorrect** | No |
| **Themes** | No |
| **Language Packs** | Yes (download only) |

## Common Questions

### Q: Does CleverKeys send data to servers?

A: No. All processing happens locally on your device. The only network use is downloading language packs.

### Q: Are my passwords safe?

A: Password fields are automatically protected - no learning, no clipboard, no predictions.

### Q: How do I completely clear my data?

A: Settings > Privacy > Reset All Data. This removes all personal data while keeping the app installed.

### Q: Can I use CleverKeys without any data storage?

A: Enable Incognito Mode permanently for zero data retention (note: predictions will be less personalized).

## Related Features

- [Clipboard History](../clipboard/clipboard-history.md) - Manage clipboard
- [Profiles](../layouts/profiles.md) - Export/import settings
- [Backup & Restore](../troubleshooting/backup-restore.md) - Data management
