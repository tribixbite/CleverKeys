---
title: Language Packs
description: Add language support via file import
category: Layouts
difficulty: beginner
related_spec: ../specs/layouts/language-packs-spec.md
---

# Language Packs

Add language dictionaries and layouts by importing language pack files.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Add language support |
| **Access** | Settings > Activities > Backup & Restore > Import |
| **Contents** | Dictionary, contractions, layouts |

## What's in a Language Pack

Each language pack includes:

| Component | Description |
|-----------|-------------|
| **Dictionary** | Binary word list for predictions |
| **Contractions** | Language-specific contractions (if available) |
| **Layout** | Keyboard layout with special keys |

## Bundled Languages

CleverKeys includes English by default. The following dictionaries are bundled in the app:

- English (US) - default
- Additional layouts available via import

## Importing Language Packs

### Step 1: Obtain the Language Pack

Language packs are `.zip` files you can:
- Build yourself using the provided scripts
- Obtain from community resources

### Step 2: Import via Backup & Restore

1. Open **Settings**
2. Go to **Activities > Backup & Restore**
3. Select **Import**
4. Choose the language pack `.zip` file
5. The pack is extracted and installed

### Step 3: Configure Language

After import:

1. The new layout appears in Layout Manager
2. Dictionary is automatically loaded for predictions
3. Configure in Settings > Activities > Layout Manager

## Building Custom Language Packs

For languages not bundled, you can create your own using the provided Python scripts:

### Requirements

- Python 3.x
- wordfreq package (optional, for frequency data)

### Using Build Scripts

```bash
# Navigate to scripts directory
cd scripts/

# Option 1: Generate from wordfreq (requires Python wordfreq package)
pip install wordfreq
python build_langpack.py --lang sv --output langpack-sv.zip

# Option 2: Build from custom word list
# Create a CSV file: word,frequency (one per line)
python build_dictionary.py --input my_words.csv --output custom.bin
```

### Scripts Available

| Script | Purpose |
|--------|---------|
| `build_langpack.py` | Create .zip language pack from wordfreq |
| `build_dictionary.py` | Build binary dictionary from CSV |
| `build_all_languages.py` | Batch build all supported languages |
| `get_wordlist.py` | Extract top N words from wordfreq |

### Language Pack Structure

```
langpack-{lang}.zip
├── {lang}_enhanced.bin    # Binary dictionary
├── {lang}_enhanced.json   # Human-readable word list
├── contractions.json      # Language contractions (optional)
└── manifest.json          # Metadata
```

### Languages Supported by wordfreq

Languages available through the wordfreq Python package:

- **European**: Swedish (sv), Norwegian (nb), Danish (da), Finnish (fi), Polish (pl), Czech (cs), German (de), French (fr), Spanish (es), Italian (it), Portuguese (pt)
- **Asian**: Japanese (ja), Korean (ko), Chinese (zh)
- **Other**: Russian (ru), Arabic (ar), Hebrew (he), Hindi (hi), Turkish (tr)

## Managing Language Packs

### View Installed

1. Go to **Settings > Activities > Layout Manager**
2. Installed layouts show available languages

### Remove a Language Pack

1. Delete the language files from the app's internal storage
2. Or use Backup & Restore to reset to defaults

## Offline Operation

Once imported, all language features work offline:

| Feature | Works Offline |
|---------|---------------|
| **Typing** | ✅ |
| **Predictions** | ✅ |
| **Autocorrect** | ✅ |
| **Contractions** | ✅ |

## Tips and Tricks

- **Start with English**: English is fully bundled and ready to use
- **Build for your language**: Use the scripts to create packs for unsupported languages
- **Share packs**: Language pack files can be shared with other users
- **Backup first**: Export your settings before major imports

## Common Questions

### Q: How do I add a new language?

A: Build a language pack using the provided Python scripts, then import it via Settings > Activities > Backup & Restore.

### Q: Can I use a language without importing a pack?

A: Basic typing works with any layout, but predictions and autocorrect require a dictionary.

### Q: Why is my language not available?

A: Build it yourself using the `build_langpack.py` script with the wordfreq package.

### Q: Do I need to download English?

A: No, English is included by default.

## Related Features

- [Adding Layouts](adding-layouts.md) - Use downloaded layouts
- [Multi-Language](multi-language.md) - Type in multiple languages
- [Autocorrect](../typing/autocorrect.md) - Per-language corrections

## Technical Details

See [Language Packs Technical Specification](../specs/layouts/language-packs-spec.md).
