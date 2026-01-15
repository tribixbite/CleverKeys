---
title: Profiles
description: Save and restore keyboard configurations
category: Layouts
difficulty: intermediate
---

# Profiles

Save complete keyboard configurations as profiles and switch between them instantly. Back up your settings and share configurations.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Save/restore full configurations |
| **Access** | Settings > Profiles |
| **Features** | Multiple profiles, import/export, sharing |

## What's Saved in a Profile

| Component | Included |
|-----------|----------|
| **Layouts** | Active layouts and order |
| **Customizations** | Per-key actions, subkeys |
| **Theme** | Colors and appearance |
| **Settings** | All preferences |
| **Languages** | Language configuration |
| **Extra Keys** | Bottom row setup |

## Creating a Profile

### Step 1: Configure Keyboard

Set up your keyboard exactly how you want it:

- Add desired layouts
- Configure customizations
- Choose theme
- Set all preferences

### Step 2: Save Profile

1. Open **Settings > Profiles**
2. Tap **Save Current as Profile**
3. Enter a profile name
4. Tap **Save**

### Step 3: Profile Saved

Your configuration is now saved. You can:

- Switch to other configurations
- Make changes without worry
- Return to this profile anytime

## Managing Profiles

### View Profiles

1. Open **Settings > Profiles**
2. See list of saved profiles
3. Current profile is highlighted

### Switch Profiles

1. Go to **Settings > Profiles**
2. Tap the profile to activate
3. Keyboard configuration changes immediately

### Edit Profile

1. Long-press on a profile
2. Select **Rename** or **Delete**
3. Confirm your action

### Update Profile

To save changes to an existing profile:

1. Make your changes
2. Go to **Settings > Profiles**
3. Long-press the profile
4. Select **Update with Current Settings**

## Import and Export

### Export Profile

Share your configuration:

1. Open **Settings > Profiles**
2. Tap the profile to export
3. Tap **Export**
4. Choose destination (file, share)

### Import Profile

Load a shared profile:

1. Open **Settings > Profiles**
2. Tap **Import Profile**
3. Select the profile file
4. Profile appears in list

### Export Format

Profiles are saved as JSON:

```json
{
  "name": "My Profile",
  "version": 1,
  "layouts": [...],
  "theme": {...},
  "customizations": {...},
  "settings": {...}
}
```

## Profile Use Cases

### Work vs Personal

| Profile | Configuration |
|---------|---------------|
| **Work** | Professional theme, English only, no emoji |
| **Personal** | Fun theme, multiple languages, full customization |

### Languages

| Profile | Configuration |
|---------|---------------|
| **English** | QWERTY, English dictionary |
| **Multilingual** | Multiple layouts, all languages |

### Device Modes

| Profile | Configuration |
|---------|---------------|
| **One-Handed** | Compact layout, large keys |
| **Two-Handed** | Full layout, normal keys |
| **Tablet** | Wide layout, split keyboard |

## Tips and Tricks

- **Name clearly**: Use descriptive profile names
- **Backup regularly**: Export profiles for safety
- **Share useful profiles**: Help others with good configurations
- **Default profile**: Keep one profile as your baseline

> [!TIP]
> Before making major changes, save your current configuration as a profile so you can return to it.

## Quick Switch

Enable quick profile switching:

1. Go to **Settings > Profiles > Quick Switch**
2. Enable the feature
3. Triple-tap globe key to show profile picker

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Profiles** | Settings | Manage profiles |
| **Quick Switch** | Profiles | Enable fast switching |
| **Auto-Backup** | Profiles | Automatic backups |
| **Cloud Sync** | Profiles | Sync across devices |

## Common Questions

### Q: How many profiles can I have?

A: You can create up to 20 profiles.

### Q: What happens if I delete a profile I'm using?

A: CleverKeys switches to the Default profile automatically.

### Q: Can I sync profiles between devices?

A: Export profiles and import on other devices. Cloud sync may be available in future updates.

### Q: Do profiles include personal dictionary words?

A: Optional. When exporting, you can choose to include or exclude personal dictionary.

## Related Features

- [Custom Layouts](custom-layouts.md) - Create custom layouts
- [Themes](../customization/themes.md) - Customize appearance
- [Backup & Restore](../troubleshooting/backup-restore.md) - Full backup options
