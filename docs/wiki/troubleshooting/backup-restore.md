---
title: Backup & Restore
description: Export and import your keyboard configuration
category: Troubleshooting
difficulty: beginner
---

# Backup & Restore

Export and import your keyboard settings, dictionary, and clipboard history.

## Quick Summary

| What | How |
|------|-----|
| **Settings** | Settings > Backup & Restore > Export/Import Config |
| **Dictionary** | Settings > Backup & Restore > Export/Import Dictionary |
| **Clipboard** | Settings > Backup & Restore > Export/Import Clipboard |
| **Format** | JSON files |

## Available Exports

### Configuration Export

Exports all keyboard settings to a JSON file:

| Included | Description |
|----------|-------------|
| **All Settings** | Appearance, behavior, neural settings |
| **Theme Selection** | Current theme choice |
| **Custom Subkeys** | Per-key customizations |

**How to export:**
1. Open **Settings**
2. Scroll to **Backup & Restore** section
3. Tap **Export Config**
4. Choose save location

### Dictionary Export

Exports your personal dictionary:

| Included | Description |
|----------|-------------|
| **User Words** | Words you've added |
| **Learned Words** | Words the keyboard learned |

### Clipboard Export

Exports clipboard history:

| Included | Description |
|----------|-------------|
| **History** | Recent clipboard items |
| **Pinned** | Pinned items |
| **Todos** | Todo items |

## Importing

### Import Config

1. Open **Settings > Backup & Restore**
2. Tap **Import Config**
3. Browse to your backup JSON file
4. Settings will be applied immediately

### Import Dictionary

1. Tap **Import Dictionary**
2. Select dictionary JSON file
3. Words are merged with existing dictionary

### Import Clipboard

1. Tap **Import Clipboard**
2. Select clipboard JSON file
3. Items are added to history

## Transfer to New Device

1. **Export** config, dictionary, and clipboard on old device
2. **Transfer** the JSON files (USB, email, cloud storage)
3. **Import** each file on new device

## Data Export for ML

CleverKeys also supports exporting swipe training data:

| Format | Description |
|--------|-------------|
| **JSON** | Structured swipe data |
| **NDJSON** | Newline-delimited JSON for ML pipelines |

Access via **Settings > Privacy > View Collected Data > Export**.

## Troubleshooting

### Import Fails

| Issue | Solution |
|-------|----------|
| **File corrupt** | Re-export from source device |
| **Wrong format** | Ensure file is CleverKeys JSON export |
| **Permission denied** | Grant storage permission to CleverKeys |

### Settings Not Applied

1. Close and reopen the keyboard
2. If needed, disable and re-enable CleverKeys in system settings

## File Locations

Exported files are saved to the location you choose via Android's file picker (typically Downloads folder).

## Related Topics

- [Privacy](../settings/privacy.md) - Data collection settings
- [Per-Key Customization](../customization/custom-layouts.md) - Subkey settings
