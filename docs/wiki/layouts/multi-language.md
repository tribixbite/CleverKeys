---
title: Multi-Language Input
description: Type in multiple languages seamlessly
category: Layouts
difficulty: intermediate
related_spec: ../specs/layouts/multi-language-spec.md
---

# Multi-Language Input

Type in multiple languages with smart language detection, seamless switching, and per-language predictions.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Type in multiple languages |
| **Access** | Settings > Languages |
| **Features** | Auto-detect, per-language dictionary, mixed typing |

## Setting Up Multi-Language

### Step 1: Add Languages

1. Open **Settings > Languages**
2. Tap **Add Language**
3. Select languages you use
4. Each language can have its own layout

### Step 2: Configure Primary Language

1. Drag your main language to the top
2. This becomes the default for new text fields
3. Predictions prioritize this language

### Step 3: Enable Auto-Detection (Optional)

1. Toggle **Auto-Detect Language**
2. CleverKeys analyzes your typing
3. Predictions adapt to detected language

## How Multi-Language Works

### Language Detection

CleverKeys analyzes your typing patterns:

```
You type: "Bon" → Detects French
Predictions: "Bonjour" "Bonne" "Bonheur"

You type: "Good" → Detects English
Predictions: "Good" "Goodnight" "Goodbye"
```

### Per-Language Dictionaries

Each language has its own dictionary:

| Language | Dictionary | Personal Words |
|----------|------------|----------------|
| English | 100,000+ words | Your additions |
| French | 80,000+ words | Your additions |
| Spanish | 90,000+ words | Your additions |

### Mixed Typing

Type mixed-language sentences:

```
"Let's meet at the café mañana"
↑ English    ↑ French  ↑ Spanish
```

CleverKeys handles code-switching naturally.

## Language Layouts

Each language can have an associated layout:

| Language | Layout | Special Keys |
|----------|--------|--------------|
| English | QWERTY | Standard |
| French | AZERTY | é, è, à, ç |
| German | QWERTZ | ä, ö, ü, ß |
| Spanish | QWERTY | ñ, ¿, ¡ |

## Switching Languages

### Automatic Switching

With auto-detect enabled:

1. Start typing in any language
2. CleverKeys detects the language
3. Predictions and corrections adapt
4. No manual switch needed

### Manual Switching

Without auto-detect:

1. Tap the globe key to switch layout
2. Or swipe spacebar left/right
3. Layout and language change together

## Tips and Tricks

- **Primary language**: Set your most-used language as primary
- **Related languages**: Auto-detect works best with distinct languages
- **Personal dictionary**: Add words to each language separately
- **Layout association**: Associate each language with preferred layout

> [!TIP]
> For better auto-detection, type at least 3-4 characters before expecting language switch.

## Language-Specific Features

### Accented Characters

Access accents via long-press or short swipe:

| Key | Long-press options |
|-----|--------------------|
| **e** | é, è, ê, ë, ē |
| **a** | á, à, â, ä, ã, å |
| **n** | ñ, ń |
| **c** | ç, ć |

### Right-to-Left Languages

For Arabic, Hebrew, etc.:

- Keyboard flips to RTL
- Cursor behavior adapts
- Predictions flow RTL

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Languages** | Languages | Add/remove languages |
| **Auto-Detect** | Languages | Enable auto-detection |
| **Language Order** | Languages | Set priority |
| **Per-Language Dictionary** | Languages | Separate dictionaries |

## Common Questions

### Q: Does auto-detect work for similar languages?

A: It works best with distinct languages (English/French). Very similar languages (Norwegian/Swedish) may need manual switching.

### Q: Can I disable auto-detect for specific languages?

A: Yes, in Settings > Languages, toggle auto-detect per language.

### Q: How do I add words to a specific language's dictionary?

A: When adding a word, choose which language dictionary to add it to.

## Related Features

- [Adding Layouts](adding-layouts.md) - Install language layouts
- [Language Packs](language-packs.md) - Download language support
- [Autocorrect](../typing/autocorrect.md) - Per-language corrections

## Technical Details

See [Multi-Language Technical Specification](../specs/layouts/multi-language-spec.md).
