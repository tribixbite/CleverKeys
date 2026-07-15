---
title: Smart Punctuation
description: Automatic punctuation attachment and smart auto-space around punctuation
category: Typing
difficulty: beginner
related_spec: ../specs/typing/smart-punctuation-spec.md
---

# Smart Punctuation

Smart punctuation automatically attaches punctuation marks to the end of words, removing unnecessary spaces. Since v1.5.0, the same system also skips the leading auto-space when you swipe a word right after an opening bracket or quote. Together these create clean text without manual editing.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Attach punctuation to words and place auto-spaces intelligently |
| **Access** | Settings > Input Behavior > Smart Punctuation |
| **Works with** | Swipe typing and suggestion taps |

## How It Works

When you swipe a word or tap a suggestion, CleverKeys automatically adds a trailing space so you can keep swiping. When you then type punctuation, smart punctuation checks whether the preceding space was **auto-inserted** (by swipe or suggestion) or **manually typed** — and only removes the automatic one.

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

> [!NOTE]
> Since v1.5.0, CleverKeys double-checks before removing anything: the character before your cursor must actually be the automatic space, and your cursor must still be exactly where the swipe or suggestion left it. Moving the cursor, pressing backspace, or switching to another text field cancels the pending removal — a space you placed yourself is never eaten.

## No Space After Opening Punctuation (v1.5.0)

The reverse direction also works: when you swipe a word (or tap a suggestion) right after an **opening** bracket or quote, no leading space is inserted:

```
Type "(" → swipe "word" → Result: "(word"     — not "( word"
Type '"' → swipe "hello" → Result: '"hello'   — not '" hello'
```

Opening punctuation that suppresses the leading space:

| Characters | Name |
|------------|------|
| `(` `[` `{` | Opening brackets |
| `“` `‘` | Curly opening quotes |
| `¿` `¡` | Inverted question/exclamation (Spanish) |
| `"` `'` | Straight quotes — only when they are actually opening |

Straight quotes are ambiguous, so CleverKeys looks at the character *before* the quote:

- At the start of the field, after a space, or after another opener → it's an **opening** quote, so the space is skipped: `He said "` + swipe → `He said "word`
- After a letter or digit → it's a **possessive or closing** quote, so the space is kept: `kids'` + swipe → `kids' toys`

## Punctuation Characters

Smart punctuation attaches these characters to the previous word:

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
| `”` `’` | Curly closing quotes | Attach to word (v1.5.0) |
| `…` | Ellipsis | Attaches to word (v1.5.0) |
| `'` | Apostrophe | Attaches to word after an auto-space (v1.5.0) |

### Quote Handling

Quotes have special handling:

- **Straight double quote** (`"`): counted for balance — after an odd number of prior `"` it's closing (attaches), after an even number it's opening (keeps the space)
- **Apostrophes** (`'`): mid-word apostrophes in contractions (don't, it's) are never touched — there is no space to remove. An apostrophe typed right after an auto-space is treated as a possessive or closing quote and attaches: swipe "kids" → type `'` → `kids'`

### Punctuation Chaining

Sentence-ending punctuation (`.` `!` `?`) re-adds a space after attaching, and that space is itself marked automatic — so you can chain punctuation:

```
Swipe "word" → type "." → "word. " → type ")" → "word.)"
```

Each closer removes exactly one automatic space; a manually typed space in between stops the chain.

## Examples

### Swipe Typing Flow

| Action | Result | Explanation |
|--------|--------|-------------|
| Swipe "The" → swipe "quick" → type "." | `The quick. ` | Period attaches + space added for autocap |
| Swipe "Hello" → type "," → swipe "world" | `Hello, world` | Comma attaches to "Hello" |
| Swipe "Hello" → type "." → swipe "world" | `Hello. World` | Period adds space, next word capitalized |
| Type "(" → swipe "example" | `(example` | No leading space after opener |
| Swipe "kids" → type "'" | `kids'` | Apostrophe attaches for possessive |

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
| Swipe "hello" → move cursor away and back → type "." | `hello .` | Cursor movement cancels the pending removal |

## Configuration

| Setting | Location | Default | Description |
|---------|----------|---------|-------------|
| **Smart Punctuation** | Settings > Input Behavior | On | Enable/disable punctuation attachment |
| **Auto-space before suggestion** | Settings > Input Behavior | On | Leading space before tapped suggestions (openers suppress it) |
| **Auto-space after suggestion** | Settings > Input Behavior | On | Trailing space after swipe/suggestion commits |

There is no separate toggle for the v1.5.0 smart auto-space behavior — it refines the existing auto-space and smart punctuation features and follows their settings.

## Double-Space to Period

A related feature: pressing space twice quickly converts to period + space:

```
Type "Hello" → space → space → Result: "Hello. "
```

| Setting | Location | Default | Description |
|---------|----------|---------|-------------|
| **Double Space to Period** | Settings > Gesture Tuning | On | Enable double-space shortcut |
| **Threshold** | Settings > Gesture Tuning | 500ms | Max time between spaces |

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

### Q: Why did the space disappear when I typed a period after swiping?

A: That space was added automatically by the swipe, and smart punctuation removes it so the period attaches to the word (`word.` instead of `word .`). Spaces you type yourself are never removed — press spacebar manually before the punctuation if you want to keep it.

### Q: Why is there no space before my swiped word after a quote?

A: You swiped right after opening punctuation like `(` or `"`, so the leading auto-space is skipped to produce `("word` instead of `( "word`. After a possessive apostrophe (`kids'`) the space is kept.

### Q: Why does my colon sometimes have a space before it?

A: If you manually pressed spacebar before the colon, CleverKeys respects your intent and keeps the space. This is useful for formatting like `Note : important`.

### Q: Can I disable this for specific punctuation?

A: Currently, smart punctuation is all-or-nothing. You can disable it entirely in Settings > Input Behavior.

### Q: Does this work in all apps?

A: Yes, smart punctuation works in any text field. In editors that don't report cursor positions, CleverKeys falls back to the pre-v1.5.0 check (automatic-space flag + a real space before the cursor), so the feature keeps working there too.

## Related Features

- [Autocorrect](autocorrect.md) - Automatic spelling corrections
- [Swipe Typing](swipe-typing.md) - Gesture-based word input
- [Special Characters](special-characters.md) - Symbols and accents
- [Input Behavior Settings](../settings/input-behavior.md) - Auto-space and punctuation settings

## Technical Details

See [Smart Punctuation Technical Specification](../specs/typing/smart-punctuation-spec.md).
