---
title: Backup & Restore
description: Save and restore your keyboard configuration
category: Troubleshooting
difficulty: beginner
---

# Backup & Restore

Protect your keyboard configuration by creating backups that can be restored later or transferred to another device.

## Quick Summary

| What | How |
|------|-----|
| **Backup** | Settings > Profiles > Export |
| **Restore** | Settings > Profiles > Import |
| **Format** | JSON file |

## What Gets Backed Up

### Full Profile Backup

| Component | Included |
|-----------|----------|
| **Settings** | All preferences |
| **Theme** | Colors, appearance |
| **Customizations** | Per-key actions |
| **Extra Keys** | Bottom row setup |
| **Layouts** | Installed layouts, order |
| **Personal Dictionary** | Optional |

### Backup Contents

```json
{
  "version": 1,
  "exported": "2024-12-15T10:30:00Z",
  "profile": {
    "name": "My Profile",
    "settings": { ... },
    "theme": { ... },
    "customizations": { ... },
    "layouts": [ ... ],
    "dictionary": [ ... ]  // If included
  }
}
```

## Creating a Backup

### Step 1: Open Profiles

1. Open **Settings**
2. Navigate to **Profiles**
3. Tap **Export Profile**

### Step 2: Choose What to Include

| Option | Description |
|--------|-------------|
| **Include Dictionary** | Personal words |
| **Include History** | Clipboard history |
| **Include Language Packs** | Downloaded packs |

### Step 3: Save File

1. Choose location (Downloads, Cloud, etc.)
2. Enter filename
3. Tap **Save**

### Step 4: Verify Backup

- Check file exists in chosen location
- File should be several KB to MB depending on contents

## Restoring from Backup

### Step 1: Access Import

1. Open **Settings > Profiles**
2. Tap **Import Profile**
3. Browse to backup file

### Step 2: Choose What to Restore

| Option | Description |
|--------|-------------|
| **Replace All** | Overwrite current config |
| **Merge** | Add to existing config |
| **Preview** | See contents first |

### Step 3: Confirm Import

1. Review what will be changed
2. Tap **Import**
3. Restart keyboard if prompted

## Automatic Backups

### Enable Auto-Backup

1. Go to **Settings > Profiles**
2. Enable **Auto-Backup**
3. Choose frequency:
   - Daily
   - Weekly
   - On change

### Backup Location

Auto-backups are stored in:

```
/storage/emulated/0/Android/data/app.cleverkeys/backups/
```

Or cloud location if configured.

### Manage Auto-Backups

| Setting | Options |
|---------|---------|
| **Keep** | Last 5, 10, 20 backups |
| **Location** | Local, Google Drive |
| **Include Dictionary** | Yes/No |

## Transfer to New Device

### Method 1: File Transfer

1. Export profile on old device
2. Transfer file (USB, email, cloud)
3. Import on new device

### Method 2: Cloud Storage

1. Export to Google Drive
2. On new device, import from Drive

### Method 3: QR Code

1. Generate QR code from profile
2. Scan on new device
3. Profile imports automatically

## Backup Best Practices

- **Regular backups**: Weekly or after major changes
- **Multiple copies**: Keep backups in different locations
- **Test restores**: Occasionally verify backups work
- **Before updates**: Backup before app updates
- **Before reset**: Always backup before resetting

> [!TIP]
> Enable auto-backup to ensure you always have a recent backup available.

## Troubleshooting Backups

### Import Fails

| Issue | Solution |
|-------|----------|
| **File corrupt** | Try different backup |
| **Version mismatch** | Update app first |
| **Permission denied** | Grant storage permission |

### Settings Not Applied

1. Force stop keyboard
2. Re-enable in system settings
3. Try import again

### Partial Restore

If only some settings restored:

1. Check what was included in backup
2. Try **Replace All** instead of **Merge**

## Export Individual Components

### Dictionary Only

1. Settings > Privacy
2. Export Dictionary
3. Imports as separate file

### Theme Only

1. Settings > Theme
2. Export Theme
3. Share with others

### Customizations Only

1. Settings > Customization
2. Export Customizations
3. Apply to other profiles

## Common Questions

### Q: How large are backup files?

A: Typically 10KB-1MB without dictionary, up to 5MB with large dictionary.

### Q: Can I share backups with others?

A: Yes, but personal dictionary may contain private words.

### Q: Will backup work across app versions?

A: Yes, backups include version info for compatibility.

### Q: Can I edit backup files manually?

A: Yes, they're JSON. But be careful not to corrupt the format.

## Related Topics

- [Profiles](../layouts/profiles.md) - Profile management
- [Reset Defaults](reset-defaults.md) - When backup isn't enough
- [Privacy](../settings/privacy.md) - What data is stored
