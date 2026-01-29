---
title: Autocorrect
description: Automatic spelling corrections and undo
category: Typing
difficulty: beginner
related_spec: ../specs/typing/autocorrect-spec.md
---

# Autocorrect

CleverKeys automatically corrects common spelling mistakes as you type. You can undo corrections and add words to your personal dictionary.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Fix spelling mistakes automatically |
| **Access** | Automatic while typing |
| **Undo** | Tap original word in predictions |

## How Autocorrect Works

When you finish typing a word (by pressing space):

1. CleverKeys checks if the word is in the dictionary
2. If not found, it searches for similar words
3. If a close match exists, it automatically replaces your word
4. The original word appears in the prediction bar

## Undoing Autocorrect

If autocorrect changes a word you didn't want changed:

### Method 1: Tap the Original Word
1. Look at the prediction bar immediately after correction
2. Your original word appears as a suggestion
3. Tap it to restore the original spelling
4. The word is automatically added to your dictionary

### Method 2: Backspace and Retype
1. Press backspace to delete the corrected word
2. Retype your intended word
3. Add it to your dictionary to prevent future corrections

## Adding Words to Dictionary

When you type a word not in the dictionary:

1. Type the word and press space
2. If not autocorrected, a prompt appears: "Add 'word' to dictionary?"
3. Tap the prompt to add it
4. The word will be suggested in future and won't be autocorrected

> [!TIP]
> Added words appear in your predictions when you start typing them.

## Managing Your Dictionary

Access your personal dictionary:

1. Open Settings (gear icon on keyboard)
2. Go to **Activities > Dictionary Manager**
3. View, edit, or delete custom words

| Tab | Contents |
|-----|----------|
| **Active** | Words from main dictionary |
| **Disabled** | Words you've turned off |
| **User** | System user dictionary |
| **Custom** | Words you've added |

## Autocorrect Settings

Customize autocorrect behavior in the **Word Prediction** section of Settings:

| Setting | Description |
|---------|-------------|
| **Autocorrect** | Enable/disable automatic corrections |
| **Autocorrect Min Word Length** | Minimum characters before autocorrect applies |
| **Beam Autocorrect** | Autocorrect during swipe typing |
| **Final Autocorrect** | Apply correction on word completion |

## Tips and Tricks

- **Proper nouns**: Add names and places to prevent miscorrection
- **Technical terms**: Add jargon and abbreviations to your dictionary
- **Disable a word**: Add it to the Disabled list in Dictionary Manager to prevent autocorrect
- **Language-specific**: Each language has its own dictionary

## Common Questions

### Q: Why was my word corrected to something wrong?
A: The similar word had higher frequency. Add your word to dictionary to prevent this.

### Q: Can I turn off autocorrect completely?
A: Yes, in Settings > Word Prediction section > Autocorrect toggle.

### Q: Does autocorrect work with swipe typing?
A: Swipe typing uses neural predictions. Use the Beam Autocorrect and Final Autocorrect settings to control this behavior.

## Related Features

- [Swipe Typing](swipe-typing.md) - Neural word prediction
- [User Dictionary](user-dictionary.md) - Add custom words
- [Special Characters](special-characters.md) - Symbols and accents

## Technical Details

See [Autocorrect Technical Specification](../specs/typing/autocorrect-spec.md).
