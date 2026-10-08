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
5. The bar confirms "Added “word” to dictionary". Tap that message **twice** to undo: the
   first tap shows "Tap again to undo", the second removes the word again. The text you
   typed is left as it is. The confirmation times out after about 3 seconds.

The same tap-twice undo follows every add made from the keyboard: the prompt, the tap-to-add
chip for the exact letters you typed, undoing an autocorrection, and accepting a
"Prefer “word” when swiping?" offer ([Swipe Typing](swipe-typing.md#prefer-a-word-when-swiping)).

### Method 2: Dictionary Manager

1. Open keyboard settings (gear icon)
2. Go to **Activities > Dictionary Manager**
3. Tap **Custom** tab
4. Use the add button to enter new words

## Frequency and Swipe Priority

Each custom word in the Dictionary Manager has two numbers you can edit (tap the word's edit
button). The two numbers do different jobs.

### Frequency (1–255)

Frequency ranks your word against the built-in dictionary. **255, the default, is already the
top**, so a word you add is ranked like the most common dictionary words. Lower it to demote a
word, for example a name that should not crowd out a common word on the same keys.

Each engine reads it on its own scale:

| Where | What frequency does |
|-------|---------------------|
| Tap suggestions | Ranks the word among completions; 255 is the strongest |
| CTC swipe (default engine) | Maps 1–255 onto the dictionary's own range; 255 ties the most common words |
| Geometric swipe | Orders your words among themselves; they always sit ahead of dictionary words |

Raising frequency cannot go past the top. That is why some words still lose when you swipe
them, even at 255.

### Swipe priority (Normal / High / Highest)

Some words still lose when swiped, even as a custom word at 255. The decoder may not notice a
letter that the swipe passes straight through. Or a short swipe may look like a more common
word that ends near the same key. Examples: `adb` comes out as `an`, `ad` as `as`, `wet` as
`we`. Swipe priority adds a fixed boost for that word on top of its frequency:

| Level | What it does |
|-------|--------------|
| **Normal** (default) | No boost. The word swipes exactly as before |
| **High** | A moderate boost. Fixes most swipes of the word that lose narrowly |
| **Highest** | A strong boost, for words that still lose at High |

The boost has limits:

- It only applies when your swipe already passed close to the word. It never puts the word on
  a swipe that went somewhere else.
- It applies **only to swiping**. Tap suggestions and autocorrect stay as they were.
- It works with both swipe engines (CTC and Geometric).

> [!WARNING]
> A raised word also wins some swipes of **similar words**. With `ad` at High, some `as`
> swipes become `ad`. With `wet` at Highest, some `we` swipes become `wet`. Raise only the
> words you need, start at High, and use Highest only if High is not enough. The measured
> trade-offs are in
> [User swipe priority (eval)](https://github.com/tribixbite/CleverKeys/blob/main/docs/eval/2026-10-08-user-swipe-priority.md).

The keyboard can also raise a word for you. If you keep correcting the same swipe to a word,
the bar asks "Prefer “word” when swiping?". Accepting the first time adds the word at Normal.
If you still correct swipes to that word, the bar asks again, and accepting raises it to High.
The bar never sets Highest; only the Dictionary Manager does
([Swipe Typing](swipe-typing.md#prefer-a-word-when-swiping)).

Swipe priority is saved with the word. Dictionary backups include it, and it is restored
with the word. Deleting a word removes its priority. Renaming a word keeps it.

## Using Custom Words

Once added, custom words:

- **Appear in predictions** when you start typing them
- **Show in swipe results** when you swipe the pattern
- **Keep their capitalization** in both tap and swipe modes
- **Won't be autocorrected** to something else

### Swipe Typing with Custom Words

When you swipe a pattern matching a custom word:

1. The swipe decoder predicts possible words
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
| **Dictionary Manager** | Settings > Activities | View and manage custom words, their frequency and swipe priority |
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
Swipe decoder output: "boston" (lowercase)
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

### Q: I added a word and swiping still gives a different word. What now?

A: Edit the word in the Dictionary Manager and set **Swipe priority** to High (see
[Frequency and Swipe Priority](#frequency-and-swipe-priority)). Raising the frequency
does not help: 255 is already the top.

## Related Features

- [Autocorrect](autocorrect.md) - Automatic spelling corrections
- [Swipe Typing](swipe-typing.md) - Word prediction for gesture input
- [Special Characters](special-characters.md) - Accents and symbols

## Technical Details

See [User Dictionary Technical Specification](../specs/typing/user-dictionary-spec.md).
