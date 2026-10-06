---
title: Language Packs
description: Add language support via file import
category: Layouts
difficulty: beginner
related_spec: ../specs/layouts/language-packs-spec.md
---

# Language Packs

Add language dictionaries by importing language pack files. Keyboard layouts are configured separately.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Add language support (dictionary + predictions) |
| **Access** | Settings > 🌐 Multi-Language > **Import Pack** |
| **Contents** | Dictionary, frequency list, contractions |

> [!TIP]
> **Greek, Russian, German, French, Spanish, Italian, Portuguese, Dutch,
> Swedish, Turkish, Indonesian, Malay, Tagalog, Swahili and more are already
> built** — you don't need to build anything. See [Prebuilt Language Packs](#prebuilt-language-packs)
> below, then import via Settings > 🌐 Multi-Language > Import Pack.

## What's in a Language Pack

Each language pack includes:

| Component | Description |
|-----------|-------------|
| **Dictionary** | Binary word list for predictions |
| **Contractions** | Language-specific contractions (if available) |
| **Frequency list** | Word frequencies for the dictionary |
| **Notice** | Optional licensing and attribution text |
| **Swipe model** | Optional declared model; runtime approval is checked separately |

## Import Limits

Packs can contain up to **100,000 dictionary words**. The dictionary is limited to
16 MiB, an optional model to 8 MiB, and the archive to 64 entries and 64 MiB of
extracted data. CleverKeys checks the dictionary header and extracted bytes, so a
smaller advertised count does not bypass these limits. An oversized update is rejected
before replacing the existing dictionary.

A dictionary import does not automatically make every script swipe-compatible.
Bangla National and Provat tap layouts are available; Bangla transliteration and a
validated Bangla swipe dictionary/model remain outstanding.

## Bundled Languages

CleverKeys bundles these dictionaries in the app (no import needed):
**English, Spanish, French, German, Italian, Portuguese, Swedish.**

Every other language is added by importing a language pack (below).

## Prebuilt Language Packs

These packs are **already built** and ship in the repository under
[the langpacks release](https://github.com/tribixbite/CleverKeys/releases/tag/langpacks) —
download the `.zip` and import it (no need to build anything):

| Language | Pack file | Words |
|----------|-----------|-------|
| **Greek (Ελληνικά)** | `langpack-el.zip` | 39,860 |
| **Russian (Русский)** | `langpack-ru.zip` | 50,000 |
| German (Deutsch) | `langpack-de.zip` | 40,000 |
| Spanish (Español) | `langpack-es.zip` | 50,000 |
| French (Français) | `langpack-fr.zip` | 40,000 |
| Italian (Italiano) | `langpack-it.zip` | 40,000 |
| Portuguese (Português) | `langpack-pt.zip` | 40,000 |
| Dutch (Nederlands) | `langpack-nl.zip` | 40,000 |
| Swedish (Svenska) | `langpack-sv.zip` | 40,000 |
| Turkish (Türkçe) | `langpack-tr.zip` | 40,000 |
| Indonesian | `langpack-id.zip` | 28,637 |
| Malay | `langpack-ms.zip` | 25,861 |
| Tagalog/Filipino | `langpack-tl.zip` | 27,922 |
| Swahili | `langpack-sw.zip` | 20,000 |
| **Ukrainian (Українська)** | `langpack-uk.zip` | 50,000 |
| **Bulgarian (Български)** | `langpack-bg.zip` | 35,027 |
| **Macedonian (Македонски)** | `langpack-mk.zip` | 50,000 |
| **Hebrew (עברית)** | `langpack-he.zip` | 50,000 |

> Most packs are generated from the [`wordfreq`](https://github.com/rspeer/wordfreq)
> corpus (real word frequencies), most of them validated against the AOSP
> LatinIME word list; Swahili comes from the Swwiki Swahili-Wikipedia list.
> See [Attribution](#attribution).
> The bundled *English* dictionary is different: it's built by a dedicated
> evidence-classification pipeline (98,140 words validated against multiple
> spelling oracles, with typo and junk filtering) rather than a raw
> frequency cut.

## Importing Language Packs

### Step 1: Obtain the Language Pack

- **Prebuilt** (recommended): download a `.zip` from
  [the langpacks release](https://github.com/tribixbite/CleverKeys/releases/tag/langpacks)
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
| `build_langpack.py` | Create .zip language pack (with licence metadata + `NOTICE.txt`) |
| `build_dictionary.py` | Build binary dictionary from CSV |
| `build_all_languages.py` | Batch build all supported languages |
| `get_wordlist.py` | Extract top N words from wordfreq |

### Language Pack Structure

```
langpack-{lang}.zip
├── manifest.json          # Metadata: code, name, version, wordCount, model
├── dictionary.bin         # V2 binary dictionary (required)
├── unigrams.txt           # Word-frequency list for language detection
├── contractions.json      # Language contractions (optional)
├── prefix_boost.bin       # Aho-Corasick prefix trie (optional, non-English)
├── model.onnx             # CTC swipe encoder (optional; the six non-Latin scripts)
└── NOTICE.txt             # Attribution, licence and change note for the word data
```

Since 2026-09-26 every prebuilt pack also records its licence in `manifest.json` —
`"license"` (`GPL-3.0-only`), `"attribution"` (credit line + upstream licence + change note)
and `"source"` (upstream URL) — so the credits travel with a pack downloaded on its own. The
importer keeps both on the device: the installed manifest holds the three keys and
`NOTICE.txt` is installed beside the dictionary, and the language-pack manager shows them
(see [Attribution](#attribution)). All three keys and `NOTICE.txt` are optional — a pack
without them still imports.

`manifest.json` and `dictionary.bin` are required; the importer rejects a
pack missing either, or a `dictionary.bin` without the V2 (`CKDT`) header.

### The `model.onnx` member (ru, el, uk, bg, mk, he)

The six non-Latin-script packs carry their own CTC swipe encoder. Those languages cannot
swipe-decode without their pack in the first place, so from 2026-09-10 the model travels with
the pack instead of sitting in the APK — ~3 MB every other user was carrying for nothing.

A pack that ships a model declares it:

```json
"model": { "file": "model.onnx", "sha256": "8fffa75c…" }
```

Two separate checks apply, and they answer different questions:

1. **On import** the bytes must hash to what the manifest says. This catches a corrupt or
   truncated download and fails the import with that reason, rather than installing a language
   whose swipe silently never works. A `model.onnx` with no manifest entry is skipped (nothing
   can verify it), and anything over 8 MiB is rejected outright.
2. **On load** the app compares the model against a SHA-256 **compiled into the app** and loads
   it only on byte-identity. It trusts nothing the manifest says — a pack's manifest is written
   by whoever built the pack. A mismatch is treated as "no model": swipe falls back to the
   geometric engine and the reason is logged.

So a hand-built pack can carry any `model.onnx` it likes and the app will still only ever run
the encoder it shipped a hash for. The practical consequence: a **new** script model needs an
app update as well as a pack, by design.

### Languages Supported by wordfreq

Languages available through the wordfreq Python package:

- **European**: Swedish (sv), Norwegian (nb), Danish (da), Finnish (fi), Polish (pl), Czech (cs), German (de), French (fr), Spanish (es), Italian (it), Portuguese (pt)
- **Asian**: Japanese (ja), Korean (ko), Chinese (zh)
- **Other**: Russian (ru), Arabic (ar), Hebrew (he), Hindi (hi), Turkish (tr)

## Managing Language Packs

### View Installed

1. Go to **Settings > Multi-Language** (with multi-language enabled)
2. Under **Language Packs**, tap **Manage**
3. Each installed pack is listed with its code and word count; tap **Source & license** on a
   pack to see where its word data came from (see [Attribution](#attribution))

### Remove a Language Pack

1. Open **Manage** as above
2. Tap **Delete** on the pack

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
[the langpacks release](https://github.com/tribixbite/CleverKeys/releases/tag/langpacks),
then Settings > 🌐 Multi-Language > Import Pack, and set Primary/Secondary
Language to **Greek (Ελληνικά)**. Greek word suggestions then work offline.

### Q: How do I add Russian?

A: Same flow — download `langpack-ru.zip` from
[the langpacks release](https://github.com/tribixbite/CleverKeys/releases/tag/langpacks),
Import Pack, set language to **Russian (Русский)**, and pick the Cyrillic
(ЙЦУКЕН) layout. ~50,000 words, works offline.

### Q: Can I use a language without importing a pack?

A: Basic typing works with any layout, but predictions and autocorrect require a dictionary.

### Q: Why is my language not available?

A: Build it yourself using the `build_langpack.py` script with the wordfreq package.

### Q: Do I need to download English?

A: No, English is included by default.

## Attribution

Each pack's word list is an adaptation ("converted to a CleverKeys frequency list") of
third-party data, and each zip carries a `NOTICE.txt` with the full credit:

| Packs | Source | Licence |
|-------|--------|---------|
| en, en-wordfreq, de, es, fr, it, nl, pt, sv, ru, el, tr, he, uk, bg, mk, id, ms, tl | [wordfreq](https://github.com/rspeer/wordfreq) (Robyn Speer et al.) — incorporating SUBTLEX (Brysbaert et al.), OpenSubtitles, Wikipedia and [Google Books Ngram](http://books.google.com/ngrams) data | CC BY-SA 4.0 |
| de, es, fr, it, nl, pt, sv, ru, el, tr, he | [AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/) word lists, used as an inclusion oracle | Apache-2.0 |
| en-opensubtitles, en-opensubtitles-50k | [FrequencyWords](https://github.com/hermitdave/FrequencyWords) 2018 (Hermit Dave), from [OpenSubtitles.org](http://www.opensubtitles.org/) via the OPUS corpus (Lison & Tiedemann, 2016) | CC BY-SA 4.0 |
| sw | [Swwiki](https://kevindonnelly.org.uk/swahili/swwiki/) (Kevin Donnelly), from Swahili Wikipedia | CC BY-SA 3.0, adapted under 4.0 |

The packs are distributed under **GPL-3.0-only**. CC BY-SA 4.0 adaptations may be licensed
under GPLv3 ([CC compatible licenses](https://creativecommons.org/compatible-licenses/));
Swwiki's CC BY-SA 3.0 reaches 4.0 through its §4(b) later-version clause; Apache-2.0 is
one-way compatible with GPLv3. The former `en-norvig-50k` pack was withdrawn on 2026-09-26
(Google Web 1T data, no redistribution grant). See the repository `NOTICE` file.

### Seeing a pack's attribution in the app

**Settings > Multi-Language > Language Packs > Manage**, then tap **Source & license** on a
pack. It expands to show:

- **Pack license** — the pack's licence (`GPL-3.0-only` for every prebuilt pack)
- the credit line from the manifest: upstream author, upstream licence and the change note
- **Source** — the upstream URL(s); tap one to open it in the browser (only `http`/`https`
  addresses are tappable, since a pack's contents come from whoever made it)
- **View full notice** — the pack's complete `NOTICE.txt`, as installed on the device

Packs built before 2026-09-27 carry none of this, and the section says so. Packs imported
with app versions before the attribution viewer kept the manifest keys but not
`NOTICE.txt`; **View full notice** then asks you to import the pack again. Re-importing the
current zip from the `langpacks` release fixes both cases and does not change your settings.

## Related Features

- [Adding Layouts](adding-layouts.md) - Use downloaded layouts
- [Multi-Language](multi-language.md) - Type in multiple languages
- [Autocorrect](../typing/autocorrect.md) - Per-language corrections

## Technical Details

See [Language Packs Technical Specification](../specs/layouts/language-packs-spec.md).
