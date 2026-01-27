---
title: User Dictionary
description: Add custom words and preserve proper noun capitalization
category: Typing
difficulty: beginner
related_spec: ../specs/typing/user-dictionary-spec.md
---

# User Dictionary

CleverKeys learns new words you add and preserves their original capitalization. Names, places, technical terms, and other custom words appear exactly as you entered them - both when typing and swiping.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Add custom words with preserved capitalization |
| **Access** | Automatic prompts or Settings > Activities > Dictionary Manager |
| **Works with** | Tap typing, swipe typing, and suggestions |

## How It Works

When you type a word not in the dictionary:

1. CleverKeys prompts "Add 'Word' to dictionary?"
2. Tap to add the word with its **exact capitalization**
3. The word appears in predictions and swipe results
4. Capitalization is preserved automatically

### Case Preservation Examples

| You Type | Stored As | Predicted As |
|----------|-----------|--------------|
| `Boston` | Boston | Boston |
| `iPhone` | iPhone | iPhone |
| `McDONALD` | McDONALD | McDONALD |
| `API` | API | API |

This works for:
- **Proper nouns**: City names, people's names
- **Brand names**: iPhone, McDonald's, GitHub
- **Acronyms**: API, URL, HTML
- **Technical terms**: Your project-specific vocabulary

## Adding Words

### Method 1: Automatic Prompt

1. Type a new word and press space
2. If not autocorrected, a prompt appears
3. Tap "Add to dictionary" to save it
4. The word is saved with your capitalization

### Method 2: Dictionary Manager

1. Open keyboard settings (gear icon)
2. Go to **Activities > Dictionary Manager**
3. Tap **Custom** tab
4. Use the add button to enter new words

## Using Custom Words

Once added, custom words:

- **Appear in predictions** when you start typing them
- **Show in swipe results** when you swipe the pattern
- **Keep their capitalization** in both tap and swipe modes
- **Won't be autocorrected** to something else

### Swipe Typing with Custom Words

When you swipe a pattern matching a custom word:

1. Neural network predicts possible words
2. Custom word case is applied from your dictionary
3. Word appears with correct capitalization

> [!TIP]
> Add names and technical terms before you need them frequently. This improves both accuracy and capitalization.

## Managing Your Dictionary

### View Custom Words

1. Settings > Activities > Dictionary Manager
2. Select **Custom** tab
3. Browse your added words

### Delete a Custom Word

1. Find the word in Dictionary Manager
2. Tap the delete icon
3. Confirm deletion

### Disable Without Deleting

1. Find the word in Dictionary Manager
2. Toggle it off (moves to Disabled tab)
3. Toggle back on later if needed

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Dictionary Manager** | Settings > Activities | View and manage custom words |
| **Personalized Learning** | Word Prediction section | Adapt to your typing patterns |

## How Capitalization Works

CleverKeys uses a priority system for capitalization:

1. **User dictionary case** - Your saved capitalization (highest priority)
2. **Shift state** - Sentence-start capitalization
3. **I-words** - Automatic "I", "I'm", "I'll" capitalization
4. **Default** - Lowercase from main dictionary

### Example Flow

When you swipe "boston" after adding "Boston":

```
Neural network output: "boston" (lowercase)
         ↓
User dictionary check: Found "Boston"
         ↓
Apply saved case: "Boston"
         ↓
Check shift state: (if sentence start, stays "Boston")
         ↓
Final output: "Boston"
```

## Tips and Tricks

- **Add proper nouns early**: Prevents frustration with miscapitalization
- **Include variations**: Add both "API" and "APIs" if you use both
- **Brand names matter**: "GitHub" vs "Github" - add your preferred form
- **Export settings**: Use Backup & Restore to preserve your dictionary

> [!NOTE]
> Words in the main dictionary cannot have their case changed. Custom words override the main dictionary for your entries.

## Common Questions

### Q: Why isn't my custom word appearing?

A: Check that:
- The word is in the Custom tab of Dictionary Manager
- It's not disabled
- You're typing/swiping the correct pattern

### Q: Can I change capitalization of an existing word?

A: Delete and re-add with new capitalization, or edit in Dictionary Manager.

### Q: Does this work with swipe typing?

A: Yes! Swipe predictions apply your custom word capitalization.

## Related Features

- [Autocorrect](autocorrect.md) - Automatic spelling corrections
- [Swipe Typing](swipe-typing.md) - Neural word prediction
- [Special Characters](special-characters.md) - Accents and symbols

## Technical Details

See [User Dictionary Technical Specification](../specs/typing/user-dictionary-spec.md).
