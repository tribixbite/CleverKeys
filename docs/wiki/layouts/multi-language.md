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

Instead of switching layouts, CleverKeys can provide predictions from multiple language dictionaries simultaneously. The neural model outputs letter sequences, and the system checks them against dictionaries for both your primary and secondary languages.

### The Key Concept

1. You swipe a word on QWERTY layout
2. Neural model suggests possible letter sequences
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

1. **Primary Language** determines the neural model's vocabulary base
2. English is the default primary language
3. The neural model is trained primarily on English

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
| English | en | 50,000 words |
| Spanish | es | 50,000 words |
| French | fr | 25,000 words |
| Portuguese | pt | 25,000 words |
| German | de | 25,000 words |
| Italian | it | 25,000 words |

### Downloadable Languages

Via Language Packs:

| Language | Code | Status |
|----------|------|--------|
| Dutch | nl | Available |
| Indonesian | id | Available |
| Malay | ms | Available |
| Tagalog | tl | Available |
| Swedish | sv | Via wordfreq script |

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

## Common Questions

### Q: Do I need to switch layouts to type in another language?

A: No! Multi-Language mode provides predictions from both languages on your current layout. Type naturally and the system suggests words from both dictionaries.

### Q: How does accent normalization work?

A: The neural model outputs base letters. The dictionary lookup maps "espanol" to "español" using accent normalization tables.

### Q: What if I need characters not on QWERTY?

A: Use subkeys! Long-press or swipe on keys to access accented characters directly (e.g., swipe up on 'n' for 'ñ').

### Q: Can I use more than two languages?

A: Currently, the system supports primary + secondary language. For three or more languages, you'd switch between secondary languages.

## Technical Details

The multi-language system uses:

- **V2 Binary Dictionaries**: Optimized format with accent mapping
- **Unigram Language Detection**: Word frequency analysis to detect language
- **Suggestion Ranker**: Merges results from multiple dictionaries
- **Accent Normalizer**: Maps ASCII input to Unicode accented forms

See [Secondary Language Integration](../../specs/secondary-language-integration.md) for implementation details.

## Related Features

- [Adding Layouts](adding-layouts.md) - Install language layouts
- [Language Packs](language-packs.md) - Download language support
- [Swipe Typing](../typing/swipe-typing.md) - How swipe prediction works
