---
title: Reset to Defaults
description: Restore keyboard to factory settings
category: Troubleshooting
difficulty: beginner
---

# Reset to Defaults

Restore CleverKeys settings to their original state when troubleshooting issues or wanting a fresh start.

## Quick Summary

| Reset Type | What's Affected |
|------------|-----------------|
| **Clear Cache** | Temporary data only |
| **Settings Reset** | All preferences |
| **Clear Dictionary** | Personal words |
| **Full Reset** | Everything (via app data clear) |

## Reset Options

### Option 1: Clear App Cache

Clears temporary data without affecting settings:

1. Go to **Android Settings > Apps > CleverKeys > Storage**
2. Tap **Clear Cache**
3. Keeps all settings and data

### Option 2: Clear Personal Dictionary

Removes learned words:

1. Open CleverKeys **Settings**
2. Scroll to **Privacy** section
3. Look for dictionary clear option
4. Confirm

### Option 3: Reset Per-Key Customizations

Restore default short swipe actions:

1. Open **Settings**
2. Tap **Per-Key Customization** in Activities section
3. Use **Reset All** option if available
4. Or manually reset each key

### Option 4: Full Reset (Clear App Data)

Restores everything to factory state:

| Affected |
|----------|
| All settings |
| All customizations |
| Personal dictionary |
| Clipboard history |
| Learned patterns |

**How to:**

1. Go to **Android Settings > Apps > CleverKeys > Storage**
2. Tap **Clear Data** (or Clear Storage)
3. Confirm
4. Keyboard returns to factory state

> [!WARNING]
> Clear Data cannot be undone. Use Backup & Restore first if you want to restore later.

## Before Resetting

### Backup First

Before any destructive reset, consider backing up:

1. Open CleverKeys **Settings**
2. Tap **Backup & Restore** in Activities section
3. Tap **Export Settings**
4. Save the backup file

See [Backup & Restore](backup-restore.md) for details.

### Try Less Drastic Options

Before full reset:

1. **Clear app cache** first (doesn't delete settings)
2. **Force stop** the app
   - Android Settings > Apps > CleverKeys > Force Stop
3. **Restart device**

## After Resetting

### First-Time Setup

After reset, go through setup:

1. **Enable keyboard** in Android system settings
2. **Set as default** keyboard when prompted
3. **Configure settings** as desired

See [First-Time Setup](../getting-started/first-time-setup.md) for guidance.

### Restore from Backup

If you exported a backup:

1. Open CleverKeys **Settings**
2. Tap **Backup & Restore** in Activities section
3. Tap **Import Settings**
4. Select your backup file
5. Settings are restored

## Reset for Specific Issues

| Issue | Recommended Reset |
|-------|-------------------|
| **Wrong predictions** | Clear Personal Dictionary |
| **Gesture issues** | Reset Per-Key Customizations |
| **Visual glitches** | Clear Cache, then Clear Data |
| **Keyboard not appearing** | Clear Data + re-enable in Android Settings |
| **General weirdness** | Full Reset (Clear Data) |

## Common Questions

### Q: Will reset delete my downloaded language packs?

A: Clear Cache doesn't. Clear Data will reset to bundled languages only.

### Q: Can I undo a reset?

A: No, but you can restore from a Backup & Restore backup file if you made one beforehand.

### Q: Will reset fix my keyboard not appearing?

A: Clear Data might help. After clearing, you'll need to re-enable the keyboard in Android Settings > System > Languages & input > On-screen keyboard.

### Q: Does uninstalling and reinstalling do the same thing?

A: Yes, but reinstalling is slower and requires you to re-enable the keyboard in system settings.

### Q: What's the difference between Clear Cache and Clear Data?

A: **Clear Cache** removes temporary files only - your settings stay. **Clear Data** removes everything including settings, like a fresh install.

## Related Topics

- [Backup & Restore](backup-restore.md) - Save before reset
- [Common Issues](common-issues.md) - Try fixes first
- [First-Time Setup](../getting-started/first-time-setup.md) - Setup after reset
