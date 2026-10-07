---
title: Multi-Language Input
description: Type in multiple languages seamlessly
category: Layouts
difficulty: intermediate
---

# Multi-Language Input

Type in multiple languages with smart language detection and combined predictions from multiple dictionaries.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Type in multiple languages on one layout |
| **Access** | Scroll to **Multi-Language** section in Settings |
| **Features** | Auto-detect, dual dictionaries, accent normalization |

## How Multi-Language Works

Instead of switching layouts, CleverKeys can provide predictions from multiple language dictionaries simultaneously. The swipe decoder outputs letter sequences, and the system checks them against dictionaries for both your primary and secondary languages.

### The Key Concept

1. You swipe a word on QWERTY layout
2. The swipe decoder suggests possible letter sequences
3. System checks both English AND Spanish (for example) dictionaries
4. Best matches from both languages appear in suggestions
5. Accent normalization maps "espanol" → "español"

## Setting Up Multi-Language

### Step 1: Open Multi-Language Settings

1. Open **Settings**
2. Scroll to the **Multi-Language** section (collapsible)
3. Expand to see language options

### Step 2: Configure Primary Language

Your primary language is the main dictionary:

1. **Primary Language** determines the swipe decoder's vocabulary base
2. English is the default primary language
3. The CTC decoder serves the 7 bundled Latin languages (English, French, German, Spanish, Italian, Portuguese, Swedish), 6 non-Latin languages via script routing (Russian, Ukrainian, Bulgarian, Macedonian, Greek, Hebrew — each needs its language pack imported), and any imported Latin pack whose vocabulary is a–z-typeable (Dutch, Indonesian, Malay, Tagalog, Swahili). Turkish uses the geometric engine because dotless `ı` has no a–z spelling; geometric is the automatic fallback for everything else too. Evidence differs sharply by language: only English/French/German/Spanish are measured on their own accuracy bar, Italian/Portuguese/Swedish ride the shared lexicon-scale evidence, Russian is validation-tier, and the remaining script languages and every imported pack have no real-swipe probe at any tier — never quote an accuracy figure for those

### Step 3: Add Secondary Language

Enable a second language for combined predictions:

1. In **Multi-Language** section, find **Secondary Language**
2. Select a language (Spanish, French, German, etc.)
3. Both dictionaries now contribute predictions

### Step 4: Enable Language Detection (Optional)

Auto-detect adjusts prediction weighting:

1. Enable **Language Detection**
2. The system analyzes recent words
3. If you're typing mostly Spanish, Spanish predictions get boosted

## One Language per Layout

If you type each language on its own layout (for example a Latin layout for English and a
Persian layout for Persian), give each layout its own language instead of mixing
dictionaries:

1. Open **Settings > Layout Manager**
2. Tap the **Language** chip on a layout
3. Choose the language (or **Follow Multi-Language settings** to leave it unbound)

While a layout with a language is active, that language is the only language used for
predictions, autocorrect, swipe typing, contractions and learning; the secondary language
is not mixed in. Switching layouts (`switch_forward`, `switch_backward`, the layout picker)
switches the language with it, and the suggestion bar shows the new language. Layouts left
on **Follow Multi-Language settings** use the Primary and Secondary languages above, exactly
as before. This also works for three or more languages: bind one layout per language.

- A custom layout can declare a default with `language="fa"` on its `<keyboard>` element;
  the choice in Layout Manager overrides it.
- If the chosen language has no dictionary installed, Layout Manager shows a warning and
  the keyboard says "no dictionary installed" when you switch to it. The layout still works
  for typing; import the language pack to get predictions and autocorrect.
- On a layout with a language, the Primary/Secondary language toggle commands change
  nothing and say that the layout sets the language. Language auto-detection is paused there.

## Detection Sensitivity

Control how quickly the system adapts to detected language:

| Sensitivity | Behavior |
|-------------|----------|
| **Low (0.4)** | Slow to switch, stable predictions |
| **Medium (0.6)** | Balanced adaptation |
| **High (0.9)** | Quick switching between languages |

## Available Languages

### Bundled Languages

These come pre-installed:

| Language | Code | Dictionary Size |
|----------|------|-----------------|
| English | en | 98,140 words |
| Spanish | es | 50,000 words |
| French | fr | 25,000 words |
| Portuguese | pt | 25,000 words |
| German | de | 25,000 words |
| Italian | it | 25,000 words |
| Swedish | sv | 40,000 words |

The English dictionary is built by a dedicated pipeline: the top 150,000 wordfreq candidates are classified against multiple spelling oracles (hunspell, aspell, the AOSP mobile-keyboard wordlist, and more), with typo-pattern and foreign-word filtering plus a curated allowlist/blocklist. That's why it's both larger *and* cleaner than a raw frequency list — common internet slang is in, corpus-noise typos like "teh" are out.

### Downloadable Languages

Via Language Packs:

| Language | Code | Status |
|----------|------|--------|
| Dutch | nl | Available |
| Indonesian | id | Available |
| Malay | ms | Available |
| Tagalog | tl | Available |
| Greek | el | Available |
| Russian | ru | Available |

See [Language Packs](language-packs.md) for download instructions.

## Accent Normalization

Multi-language mode automatically handles accented characters:

| You type | Suggestion |
|----------|------------|
| "cafe" | "café" |
| "espanol" | "español" |
| "francais" | "français" |
| "nino" | "niño" |

The system maps your 26-letter QWERTY input to properly accented words.

## Tips and Tricks

- **One layout, two languages**: No need to switch layouts for bilingual typing
- **Accent-free typing**: Just type the base letters, accents are added automatically
- **Detection window**: The system looks at your last ~10 words to detect language
- **Boost settings**: Adjust detection sensitivity if switching feels too fast/slow

> [!TIP]
> For best results, type a few words in one language before expecting accurate detection.

## Settings Reference

| Setting | Location | Description |
|---------|----------|-------------|
| **Primary Language** | Multi-Language section | Main dictionary |
| **Secondary Language** | Multi-Language section | Additional dictionary |
| **Language Detection** | Multi-Language section | Auto-detect toggle |
| **Detection Sensitivity** | Multi-Language section | 0.4-0.9 range |
| **Layout language** | Layout Manager (Language chip) | Language of one layout; overrides Primary/Secondary while that layout is active |

## Common Questions

### Q: Do I need to switch layouts to type in another language?

A: No! Multi-Language mode provides predictions from both languages on your current layout. Type naturally and the system suggests words from both dictionaries.

### Q: How does accent normalization work?

A: The swipe decoder outputs base letters. The dictionary lookup maps "espanol" to "español" using accent normalization tables.

### Q: What if I need characters not on QWERTY?

A: Use subkeys! Swipe on keys to access accented characters directly (e.g., swipe on 'n' for 'ñ').

### Q: Can I use more than two languages?

A: Yes. Bind each layout to its language in Layout Manager (see [One Language per Layout](#one-language-per-layout)) and switch layouts to switch languages. Within one unbound layout, predictions combine at most the primary and secondary languages.

## Technical Details

The multi-language system uses:

- **V2 Binary Dictionaries**: Optimized format with accent mapping
- **Unigram Language Detection**: Word frequency analysis to detect language
- **Suggestion Ranker**: Merges results from multiple dictionaries
- **Accent Normalizer**: Maps ASCII input to Unicode accented forms

See [Secondary Language Integration](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/secondary-language-integration.md) for implementation details.

## Related Features

- [Adding Layouts](adding-layouts.md) - Install language layouts
- [Language Packs](language-packs.md) - Download language support
- [Swipe Typing](../typing/swipe-typing.md) - How swipe prediction works
