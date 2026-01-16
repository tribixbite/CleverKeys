---
title: Language Packs
description: Download language support packages
category: Layouts
difficulty: beginner
related_spec: ../specs/layouts/language-packs-spec.md
---

# Language Packs

Download language packs to add dictionaries, layouts, and predictions for additional languages.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Add language support |
| **Access** | Settings > Language Packs |
| **Contents** | Dictionary, layout, predictions |

## What's in a Language Pack

Each language pack includes:

| Component | Description |
|-----------|-------------|
| **Dictionary** | Word list for predictions |
| **Layout** | Keyboard layout with special keys |
| **Autocorrect** | Language-specific corrections |
| **Predictions** | Neural model vocabulary |

## How to Download

### Step 1: Open Language Packs

1. Open **Settings**
2. Navigate to **Language Packs**
3. See list of available packs

### Step 2: Browse Languages

Languages are organized by region:

| Region | Languages |
|--------|-----------|
| **European** | French, German, Spanish, Italian, Portuguese |
| **Nordic** | Swedish, Norwegian, Danish, Finnish |
| **Slavic** | Russian, Polish, Czech, Ukrainian |
| **Asian** | Japanese, Korean, Chinese (Pinyin) |
| **Middle Eastern** | Arabic, Hebrew, Farsi |
| **Indian** | Hindi, Tamil, Bengali |

### Step 3: Download Pack

1. Tap the language you need
2. View pack details (size, version)
3. Tap **Download**
4. Wait for download to complete

### Step 4: Activate Language

After download:

1. Go to **Settings > Languages**
2. Tap **Add Language**
3. Select the downloaded language
4. Configure layout association

## Managing Language Packs

### View Installed Packs

1. Go to **Settings > Language Packs**
2. **Downloaded** tab shows installed packs
3. See version and size for each

### Update Packs

1. Go to **Downloaded** tab
2. Packs with updates show indicator
3. Tap **Update** or **Update All**

### Remove Packs

1. Go to **Downloaded** tab
2. Swipe left on the pack
3. Tap **Remove**
4. Confirm removal

## Pack Sizes

| Pack Type | Approximate Size |
|-----------|------------------|
| **Basic** | 1-5 MB |
| **Standard** | 5-15 MB |
| **Enhanced** | 15-30 MB |

Enhanced packs include larger dictionaries and better prediction models.

## Offline vs Online

| Feature | Offline | Online |
|---------|---------|--------|
| **Basic typing** | ✅ | ✅ |
| **Autocorrect** | ✅ | ✅ |
| **Predictions** | ✅ | ✅ |
| **Updates** | ❌ | ✅ |

Once downloaded, all features work offline.

## Tips and Tricks

- **Download over WiFi**: Packs can be large; use WiFi when possible
- **Remove unused**: Free storage by removing languages you don't use
- **Check updates**: Updated packs have improved dictionaries
- **Enhanced packs**: Worth it for primary languages

> [!TIP]
> Download language packs before traveling to areas with limited internet.

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Available Packs** | Language Packs | Browse all packs |
| **Downloaded** | Language Packs | Manage installed |
| **Auto-Update** | Language Packs | Update automatically |
| **Download on WiFi Only** | Language Packs | Limit data usage |

## Common Questions

### Q: How much storage do language packs use?

A: Basic packs use 1-5 MB each. Enhanced packs use up to 30 MB. Check available storage before downloading.

### Q: Can I use a language without downloading its pack?

A: Basic typing works, but you won't get predictions, autocorrect, or special layouts without the pack.

### Q: Why is my language not available?

A: Some languages are still in development. Check back for updates or request support through feedback.

### Q: Do I need to download English?

A: No, English is included by default. You can download enhanced English for a larger dictionary.

## Creating Custom Language Packs

For languages not yet available (Swedish, Turkish, etc.), you can create your own:

### Using Python Scripts

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
└── manifest.json          # Metadata
```

### Supported by wordfreq

Languages available through wordfreq include:
- European: Swedish (sv), Norwegian (nb), Danish (da), Finnish (fi), Polish (pl), Czech (cs)
- Asian: Japanese (ja), Korean (ko), Chinese (zh)
- Other: Russian (ru), Arabic (ar), Hebrew (he), Hindi (hi), Turkish (tr)

> [!TIP]
> See the [README](https://github.com/tribixbite/CleverKeys#creating-custom-language-packs) for detailed instructions.

## Related Features

- [Adding Layouts](adding-layouts.md) - Use downloaded layouts
- [Multi-Language](multi-language.md) - Type in multiple languages
- [Autocorrect](../typing/autocorrect.md) - Per-language corrections

## Technical Details

See [Language Packs Technical Specification](../specs/layouts/language-packs-spec.md).
