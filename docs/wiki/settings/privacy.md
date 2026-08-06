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

## On-Device Learning (Master Switch)

Found at the top of the **Privacy & Data** section:

### Learn From My Typing

One switch controls ALL automatic learning from your typing behavior. **Default: On.**

When ON, CleverKeys builds private, on-device models from what you type:

| What is learned | Used for |
|-----------------|----------|
| **Phrase patterns** (word pairs and triples) | Context-aware suggestion boosting, next-word prediction |
| **Word usage** (personal vocabulary) | Personalized suggestion ranking |
| **Suggestion selections** | Adapting which suggestions rank higher |
| **Swipe traces** (only if Swipe Data Collection is also on) | Potential future model tuning |

When OFF:

- **Nothing new is recorded** — every learning path is stopped at the write layer.
- **Already-learned data goes inert** — it is neither updated nor used for suggestions
  (next-word prediction and learned-context boosting turn off entirely).
- Turning the switch off offers a one-tap **"Also forget learned data?"** dialog that
  deletes everything already learned (phrase patterns, word usage, and selection
  history). Choose "Keep it" to retain the data in case you re-enable learning later.

> [!NOTE]
> The switch covers *automatic* recording of typing behavior. Data you explicitly create
> is governed separately: swipe-calibration sessions you start yourself, prediction
> performance statistics (a separate toggle, no text content), and learned data you
> restore from your own backup.

### Private/incognito fields

Apps can mark a text field as "no personalized learning" (for example, a browser's
private tab). CleverKeys honors this automatically: nothing typed in such a field is
learned, and no personalized next-word suggestions appear there — regardless of your
settings.

### Reviewing and deleting learned data

Settings > Input Behavior > **Learning & Data** shows what has been learned (per-language
phrase counts, word usage) and lets you:

- **Browse phrases** — see every learned word pair with its count, delete individually
- **Browse words** — see your learned vocabulary with usage counts, delete individually
- **Forget phrases / Forget words** — bulk delete with confirmation

Learned phrases and words are included in dictionary exports (Backup & Restore), so your
learning survives reinstalls if you back up.

## Clipboard Privacy

### Clipboard Settings

Found under the **Clipboard** section in Settings:

| Setting | Description |
|---------|-------------|
| **Clipboard History** | Enable/disable clipboard history |
| **Clipboard History Limit** | Maximum items to keep (default: 50) |
| **Clipboard Size Limit** | Total size limit in MB |
| **Clipboard Max Item Size** | Maximum size per text item in KB (64-1024) |
| **Media Clipboard** | Enable/disable media capture (images, videos, PDFs) |
| **Text-Only Mode** | Hide all media entries from clipboard panel |
| **Max Media Size** | Maximum file size for media entries (1-50 MB) |
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
| **Clipboard Media** | App data | Copied images, videos, PDFs |

### Storage Location

All data is stored locally on your device:

```
/data/data/tribixbite.cleverkeys/
├── shared_prefs/         # Settings
├── files/                # Dictionary, profiles
│   └── clipboard_media/  # Copied images, videos, PDFs (excluded from backup)
└── databases/            # Clipboard history (text + media metadata)
```

> [!NOTE]
> Clipboard media files are excluded from Android Auto Backup to protect privacy and prevent consuming Google Drive quota.

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
| **Learn From My Typing** | Privacy & Data | On |
| **Clipboard History** | Clipboard | On |
| **History Limit** | Clipboard | 50 items |
| **History Duration** | Clipboard | Never expire |
| **Media Clipboard** | Clipboard | On |
| **Text-Only Mode** | Clipboard | Off |
| **Max Media Size** | Clipboard | 10 MB |
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

### Q: How do I stop the keyboard from learning my typing?

A: Turn off Privacy & Data > Learn From My Typing. Nothing new will be recorded, and
already-learned data stops being used. You will also be offered a one-tap delete of
everything already learned.

## Related Features

- [Clipboard History](../clipboard/clipboard-history.md) - Manage clipboard
- [Backup & Restore](../troubleshooting/backup-restore.md) - Data management
- [Next-Word Prediction](../typing/next-word-prediction.md) - Uses learned phrases (opt-in)
- [Input Behavior Settings](./input-behavior.md) - Learning & Data manager
