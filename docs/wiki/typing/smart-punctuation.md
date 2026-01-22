---
title: Smart Punctuation
description: Automatic punctuation attachment and spacing behavior
category: Typing
difficulty: beginner
related_spec: ../specs/typing/smart-punctuation-spec.md
---

# Smart Punctuation

Smart punctuation automatically attaches punctuation marks to the end of words, removing unnecessary spaces. This creates cleaner text without manual editing.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Attach punctuation to words automatically |
| **Access** | Settings > Input Behavior > Smart Punctuation |
| **Works with** | Swipe typing and suggestion taps |

## How It Works

When you type punctuation after a word, smart punctuation checks whether the preceding space was **auto-inserted** (by swipe or suggestion) or **manually typed**.

### Auto-Inserted Spaces

If the space was auto-inserted (from swipe completion or tapping a suggestion), punctuation attaches to the previous word:

```
Swipe "hello" → type "!" → Result: "hello!"
```

### Manually Typed Spaces

If you explicitly pressed the spacebar, the punctuation stays where you typed it:

```
Type "hello" → press space → type ":" → Result: "hello :"
```

This respects your intent when you deliberately add a space before punctuation.

## Punctuation Characters

Smart punctuation affects these characters:

| Character | Name | Behavior |
|-----------|------|----------|
| `.` | Period | Attaches to word |
| `,` | Comma | Attaches to word |
| `!` | Exclamation | Attaches to word |
| `?` | Question mark | Attaches to word |
| `;` | Semicolon | Attaches to word |
| `:` | Colon | Attaches to word |
| `)` | Close parenthesis | Attaches to word |
| `]` | Close bracket | Attaches to word |
| `}` | Close brace | Attaches to word |

### Quote Handling

Quotes have special handling:

- **Closing quotes** (`"` after odd count): Attach to word
- **Opening quotes** (`"` after even count): Keep space before
- **Apostrophes** (`'` after letter): Never removed (contractions)

## Examples

### Swipe Typing Flow

| Action | Result | Explanation |
|--------|--------|-------------|
| Swipe "The" → swipe "quick" → type "." | `The quick. ` | Period attaches + space added for autocap |
| Swipe "Hello" → type "," → swipe "world" | `Hello, world` | Comma attaches to "Hello" |
| Swipe "Hello" → type "." → swipe "world" | `Hello. World` | Period adds space, next word capitalized |

### Touch Typing Flow

| Action | Result | Explanation |
|--------|--------|-------------|
| Type "Note" → space → type ":" → space → type "text" | `Note : text` | Manual space preserved |
| Type "It" → type "'" → type "s" | `It's` | Apostrophe handled as contraction |

### Mixed Flow

| Action | Result | Explanation |
|--------|--------|-------------|
| Swipe "hello" → manual space → type "!" | `hello !` | Manual space overrides auto behavior |
| Type "hello" → tap suggestion "world" → type "." | `hello world.` | Suggestion's auto-space is removed |

## Configuration

| Setting | Location | Default | Description |
|---------|----------|---------|-------------|
| **Smart Punctuation** | Settings > Input Behavior | On | Enable/disable punctuation attachment |

## Double-Space to Period

A related feature: pressing space twice quickly converts to period + space:

```
Type "Hello" → space → space → Result: "Hello. "
```

| Setting | Location | Default | Description |
|---------|----------|---------|-------------|
| **Double Space to Period** | Settings > Input Behavior | On | Enable double-space shortcut |
| **Threshold** | Settings > Input Behavior | 500ms | Max time between spaces |

## Tips and Tricks

> [!TIP]
> If you need a space before punctuation (like ` :` in some languages), just press spacebar manually before typing the punctuation.

> [!TIP]
> Smart punctuation only activates after auto-inserted spaces. Touch-typed text always keeps your exact spacing.

> [!NOTE]
> Apostrophes in contractions (like "don't", "it's") are never affected by smart punctuation.

> [!TIP]
> Sentence-ending punctuation (. ! ?) automatically adds a space after attachment, enabling autocapitalization for the next word you type or swipe.

## Common Questions

### Q: Why does my colon sometimes have a space before it?

A: If you manually pressed spacebar before the colon, CleverKeys respects your intent and keeps the space. This is useful for formatting like `Note : important`.

### Q: Can I disable this for specific punctuation?

A: Currently, smart punctuation is all-or-nothing. You can disable it entirely in Settings > Input Behavior.

### Q: Does this work in all apps?

A: Yes, smart punctuation works in any text field. However, some apps may have their own auto-formatting that could interact with it.

## Related Features

- [Autocorrect](autocorrect.md) - Automatic spelling corrections
- [Swipe Typing](swipe-typing.md) - Gesture-based word input
- [Special Characters](special-characters.md) - Symbols and accents

## Technical Details

See [Smart Punctuation Technical Specification](../specs/typing/smart-punctuation-spec.md).
