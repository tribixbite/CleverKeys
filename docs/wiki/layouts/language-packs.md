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
| **Purpose** | Add language support (dictionary + predictions) |
| **Access** | Settings > 🌐 Multi-Language > **Import Pack** |
| **Contents** | Dictionary, frequency list, contractions |

> [!TIP]
> **Greek, German, French, Spanish, Italian, Portuguese, Dutch, Swedish,
> Turkish, Indonesian, Malay, Tagalog, Swahili and more are already built**
> — you don't need to build anything. See [Prebuilt Language Packs](#prebuilt-language-packs)
> below, then import via Settings > 🌐 Multi-Language > Import Pack.

## What's in a Language Pack

Each language pack includes:

| Component | Description |
|-----------|-------------|
| **Dictionary** | Binary word list for predictions |
| **Contractions** | Language-specific contractions (if available) |
| **Layout** | Keyboard layout with special keys |

## Bundled Languages

CleverKeys bundles these dictionaries in the app (no import needed):
**English, Spanish, French, German, Italian, Portuguese, Swedish.**

Every other language is added by importing a language pack (below).

## Prebuilt Language Packs

These packs are **already built** and ship in the repository under
[`scripts/dictionaries/`](https://github.com/tribixbite/CleverKeys/tree/main/scripts/dictionaries) —
download the `.zip` and import it (no need to build anything):

| Language | Pack file | Words |
|----------|-----------|-------|
| **Greek (Ελληνικά)** | `langpack-el.zip` | ~46,000 |
| German (Deutsch) | `langpack-de.zip` | |
| Spanish (Español) | `langpack-es.zip` | |
| French (Français) | `langpack-fr.zip` | |
| Italian (Italiano) | `langpack-it.zip` | |
| Portuguese (Português) | `langpack-pt.zip` | |
| Dutch (Nederlands) | `langpack-nl.zip` | |
| Swedish (Svenska) | `langpack-sv.zip` | |
| Turkish (Türkçe) | `langpack-tr.zip` | |
| Indonesian | `langpack-id.zip` | |
| Malay | `langpack-ms.zip` | |
| Tagalog/Filipino | `langpack-tl.zip` | |
| Swahili | `langpack-sw.zip` | |

> All packs are generated from the [`wordfreq`](https://github.com/rspeer/wordfreq)
> corpus (real word frequencies). See [Attribution](#attribution).

## Importing Language Packs

### Step 1: Obtain the Language Pack

- **Prebuilt** (recommended): download a `.zip` from
  [`scripts/dictionaries/`](https://github.com/tribixbite/CleverKeys/tree/main/scripts/dictionaries)
  (e.g. `langpack-el.zip` for Greek) to your device.
- **Build your own** for a language not listed (see below).

### Step 2: Import via Multi-Language

1. Open **Settings**
2. Go to the **🌐 Multi-Language** section
3. Tap **Import Pack**
4. Choose the language pack `.zip` file
5. The pack is extracted and installed (you'll see "Installed: N language pack(s)")

### Step 3: Select the Language

After import the language becomes selectable immediately:

1. In **Settings > 🌐 Multi-Language**, set **Primary Language** (or
   **Secondary Language**) to the imported language — e.g. **Greek (Ελληνικά)**.
2. The dictionary loads automatically for predictions and autocorrect.
3. Pick the matching keyboard layout (e.g. the Greek layout) if you want
   the native script on the keys.

## Building Custom Language Packs

For languages not bundled, you can create your own using the provided Python scripts:

### Requirements

- Python 3.x
- wordfreq package (optional, for frequency data)

### Using Build Scripts

```bash
# Navigate to scripts directory
cd scripts/

# Install prerequisite
pip install wordfreq

# Option 1: Two-step build from wordfreq (any language wordfreq supports)
python get_wordlist.py --lang sv --output sv_words.txt --count 50000
python build_langpack.py --lang sv --name "Swedish" --input sv_words.txt --use-wordfreq --output langpack-sv.zip

# Option 2: Build from pre-existing binary dictionary (.bin file)
python build_langpack.py --lang sv --name "Swedish" --dict ../src/main/assets/dictionaries/sv_enhanced.bin --output langpack-sv.zip

# Option 3: Build from custom word list CSV (format: word,frequency per line)
python build_dictionary.py --input my_words.csv --output custom.bin
python build_langpack.py --lang xx --name "MyLang" --dict custom.bin --output langpack-xx.zip
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
├── manifest.json          # Metadata: code, name, version, wordCount
├── dictionary.bin         # V2 binary dictionary (required)
├── unigrams.txt           # Word-frequency list for language detection
├── contractions.json      # Language contractions (optional)
└── prefix_boost.bin       # Aho-Corasick prefix trie (optional, non-English)
```

`manifest.json` and `dictionary.bin` are required; the importer rejects a
pack missing either, or a `dictionary.bin` without the V2 (`CKDT`) header.

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

A: If it's in the [Prebuilt Language Packs](#prebuilt-language-packs) list
(Greek, German, French, Spanish, Italian, Portuguese, Dutch, Swedish,
Turkish, and more), just download the `.zip` and import it via Settings >
🌐 Multi-Language > Import Pack — no building required. For an unlisted
language, build a pack with the Python scripts, then import the same way.

### Q: How do I add Greek?

A: Download `langpack-el.zip` from
[`scripts/dictionaries/`](https://github.com/tribixbite/CleverKeys/tree/main/scripts/dictionaries),
then Settings > 🌐 Multi-Language > Import Pack, and set Primary/Secondary
Language to **Greek (Ελληνικά)**. Greek word suggestions then work offline.

### Q: Can I use a language without importing a pack?

A: Basic typing works with any layout, but predictions and autocorrect require a dictionary.

### Q: Why is my language not available?

A: Build it yourself using the `build_langpack.py` script with the wordfreq package.

### Q: Do I need to download English?

A: No, English is included by default.

## Attribution

Prebuilt packs are generated from the [`wordfreq`](https://github.com/rspeer/wordfreq)
library (Robyn Speer et al.). Word-frequency data is CC-BY-SA-4.0,
incorporating OpenSubtitles 2018, SUBTLEX, Wikipedia, and Google Books
Ngrams; derived wordlists are redistributed under GPL-3.0 (CC-BY-SA-4.0 is
one-way compatible with GPLv3). See the repository `NOTICE` file.

## Related Features

- [Adding Layouts](adding-layouts.md) - Use downloaded layouts
- [Multi-Language](multi-language.md) - Type in multiple languages
- [Autocorrect](../typing/autocorrect.md) - Per-language corrections

## Technical Details

See [Language Packs Technical Specification](../specs/layouts/language-packs-spec.md).
