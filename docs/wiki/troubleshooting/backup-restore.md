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
| **Settings** | Settings > Activities > Backup & Restore > Export/Import Config |
| **Dictionary** | Settings > Activities > Backup & Restore > Export/Import Dictionary |
| **Clipboard** | Settings > Activities > Backup & Restore > Export/Import Clipboard |
| **Format** | JSON (text-only) or ZIP (full backup with media) |

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
2. Tap **Backup & Restore** in the Activities section
3. Tap **Export Config**
4. Choose save location

### Dictionary Export

Exports your personal dictionary:

| Included | Description |
|----------|-------------|
| **User Words** | Words you've added |
| **Learned Words** | Words the keyboard learned |

### Clipboard Export

Exports clipboard history in two formats:

#### JSON Export (text-only, lightweight)

| Included | Description |
|----------|-------------|
| **History** | Recent text clipboard items |
| **Pinned** | Pinned text items |
| **Todos** | Todo text items |
| **Media metadata** | MIME types noted but media files not included |

#### ZIP Export (full backup with media)

| Included | Description |
|----------|-------------|
| **All text entries** | History, pinned, todos |
| **Media files** | Images, videos, PDFs, other files |
| **Thumbnails** | Regenerated on import (not in export) |

> [!TIP]
> Use JSON export for quick, lightweight backups of text entries. Use ZIP export when you want to preserve images and other media.

## Importing

### Import Config

1. Open **Settings > Activities > Backup & Restore**
2. Tap **Import Config**
3. Browse to your backup JSON file
4. Settings will be applied immediately

### Import Dictionary

1. Tap **Import Dictionary**
2. Select dictionary JSON file
3. Words are merged with existing dictionary

### Import Clipboard

1. Tap **Import Clipboard**
2. Select clipboard file (JSON or ZIP)
3. Items are added to history
4. For ZIP imports: media files are extracted and thumbnails regenerated

Import supports v2, v3, and v4 export formats — older backups work on newer versions.

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

Access via **Settings > Privacy & Data section > Export JSON/NDJSON**.

## Programmatic Import/Export (Termux / Automation)

All backup operations can be triggered via Android intents for scripting and automation.

### Intent Actions

| Action | Description |
|--------|-------------|
| `tribixbite.cleverkeys.action.EXPORT_SETTINGS` | Export settings JSON |
| `tribixbite.cleverkeys.action.IMPORT_SETTINGS` | Import settings JSON |
| `tribixbite.cleverkeys.action.EXPORT_DICTIONARIES` | Export dictionary JSON |
| `tribixbite.cleverkeys.action.IMPORT_DICTIONARIES` | Import dictionary JSON |
| `tribixbite.cleverkeys.action.EXPORT_CLIPBOARD` | Export clipboard JSON |
| `tribixbite.cleverkeys.action.IMPORT_CLIPBOARD` | Import clipboard JSON |

### Basic Examples

```bash
# Export settings to Downloads/CleverKeys/
am start -a tribixbite.cleverkeys.action.EXPORT_SETTINGS \
  -n tribixbite.cleverkeys/.BackupRestoreActivity

# Import dictionary via base64 (bypasses scoped storage)
am start -a tribixbite.cleverkeys.action.IMPORT_DICTIONARIES \
  -n tribixbite.cleverkeys/.BackupRestoreActivity \
  --es json_base64 "$(base64 -w0 < dictionaries.json)"
```

### The `json_base64` Extra

On Android 10+, files modified or created by external apps (cp, vim, Termux) become
inaccessible to CleverKeys due to scoped storage restrictions. The `json_base64` intent
extra bypasses this by passing file content inline as base64:

```bash
am start -a tribixbite.cleverkeys.action.IMPORT_SETTINGS \
  -n tribixbite.cleverkeys/.BackupRestoreActivity \
  --es json_base64 "$(base64 -w0 < backup.json)"
```

**Size limits**:
- **Direct `am` (Termux)**: ~96KB raw JSON (128KB base64 MAX_ARG_STRLEN)
- **Via `adb shell`**: ~48KB raw JSON (64KB adb protocol buffer)
- **UI file picker**: No size limit

### Importing Large Files (Chunked)

Clipboard backups can exceed the `am start` argument limit. This script splits a JSON
backup into chunks and imports each one. Requires `jq` and `python3`.

```bash
# Chunked clipboard import — handles files of any size
# Usage: python3 ck_import.py clip.json
python3 -c "
import json,base64,subprocess,sys,time
F=sys.argv[1]; MAX=125000  # base64 bytes; under 128KB MAX_ARG_STRLEN
d=json.load(open(F)); e=d['active_entries']
m={k:v for k,v in d.items() if k not in('active_entries','pinned_entries','todo_entries','total_active','total_pinned','total_todo')}
p=d.get('pinned_entries',[]); t=d.get('todo_entries',[])
chunks=[]; cur=[]; first=True
for x in e:
    tr=cur+[x]; td=dict(m,active_entries=tr)
    if first: td['pinned_entries']=p; td['todo_entries']=t
    bl=len(base64.b64encode(json.dumps(td,separators=(',',':')).encode()))
    if bl>MAX and cur:
        chunks.append((cur,first)); first=False; cur=[x]
    elif bl>MAX and not cur:
        chunks.append(([x],first)); first=False; cur=[]
    else: cur=tr
if cur: chunks.append((cur,first))
A='tribixbite.cleverkeys.action.IMPORT_CLIPBOARD'
C='tribixbite.cleverkeys/.BackupRestoreActivity'
print(f'{sum(len(c) for c,_ in chunks)} entries -> {len(chunks)} chunks')
for i,(ce,hp) in enumerate(chunks):
    cd=dict(m,active_entries=ce)
    if hp: cd['pinned_entries']=p; cd['todo_entries']=t
    b64=base64.b64encode(json.dumps(cd,separators=(',',':')).encode()).decode()
    r=subprocess.run(['am','start','-W','-a',A,'-n',C,'--es','json_base64',b64],capture_output=True,text=True)
    print(f'  [{i+1}/{len(chunks)}] {len(ce)} entries: {\"ok\" if r.returncode==0 else \"FAIL\"}')
    time.sleep(1)
" "$1"
```

> **Note**: Entries with content >96KB (rare — e.g., pasted logs) exceed MAX_ARG_STRLEN
> even as a single-entry chunk. For those, use the UI Import button.
>
> Entries are deduplicated by content hash — re-importing is safe (duplicates are skipped).

## Troubleshooting

### Import Fails

| Issue | Solution |
|-------|----------|
| **"Cannot read file"** | Scoped storage issue — use UI Import button or `json_base64` extra |
| **File corrupt** | Re-export from source device |
| **Wrong format** | Ensure file is CleverKeys JSON export |
| **"not a JSON object"** | File is empty or unreadable — likely scoped storage; see above |

### Settings Not Applied

1. Close and reopen the keyboard
2. If needed, disable and re-enable CleverKeys in system settings

### Clipboard History Disappearing

Entries expire based on the **History Duration** setting in Clipboard Settings:
- Default: **Never expire** (duration = -1)
- Can be set to 1 hour, 1 day, 7 days, or custom
- Pinned items and TODO items never expire regardless of this setting
- **Note**: Changing this setting only affects new entries. To refresh expiry on
  existing entries, re-import your clipboard backup (import assigns fresh timestamps)

## File Locations

Exported files are saved to the location you choose via Android's file picker (typically Downloads folder).

## Related Topics

- [Privacy](../settings/privacy.md) - Data collection settings
- [Per-Key Customization](../customization/custom-layouts.md) - Subkey settings
