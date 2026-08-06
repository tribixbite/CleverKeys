---
title: Next-Word Prediction
description: Suggest your next word from learned phrases before you type a letter
category: Typing
difficulty: intermediate
related_spec: ../specs/typing/next-word-prediction-spec.md
---

# Next-Word Prediction

Next-word prediction suggests what you are likely to type next — before you press a single
letter — based on phrases the keyboard has learned from your own typing. It is fully
on-device, opt-in, and off by default.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Suggest the next word from your learned phrases |
| **Access** | Settings > Input Behavior > Word Prediction > Next-Word Prediction |
| **Default** | Off (opt-in) |
| **Data source** | Your own learned phrase patterns — nothing built-in, nothing downloaded |

## How It Works

As you type, CleverKeys privately learns which words you use after which (word pairs and
three-word sequences). With next-word prediction on, those learned phrases fill the
suggestion bar at moments when it would otherwise be empty:

- **After you finish a word with a space** — the bar offers up to 3 likely next words.
- **After you tap a suggestion** — the bar refills from the new context, so you can chain
  whole familiar phrases by tapping suggestions without touching the letter keys.
- **After a swipe** — your swipe's alternate readings stay in the bar (so you can still
  correct the swipe), and up to 2 learned next words are appended after them.
- **When you tap into text** — parking the cursor at the end of what you typed this
  session can offer continuations, similar to other modern keyboards.

Suggestions only appear when the keyboard is reasonably confident: a phrase must have been
seen at least twice, and the continuation must be likely (at least a 5% chance given your
history). **An empty bar is normal**, especially in the first days — showing nothing beats
showing noise. Suggestions are also filtered against your dictionary, so a typo you made
twice will not haunt the bar.

## Walkthrough: typing a sentence

Say you often type "I want to go home", and next-word prediction is on.

**Tap typing:**
1. Type `I` and press space. The bar shows learned continuations, for example
   `want · am · think`.
2. Tap `want`. The word commits with a space, and the bar refills: `to · a · more`.
3. Tap `to`, tap `go`… you can ride your own common phrases tap by tap.
4. Start typing a letter instead — the next-word suggestions vanish and normal
   letter-by-letter predictions take over.
5. Press backspace while no partial word exists — the next-word suggestions dismiss.
6. End a sentence with `.` `?` or `!` — the phrase context resets, so nothing carries
   across sentences.

**Swipe typing:**
1. Swipe "want". The word inserts automatically, and the bar shows the swipe's alternate
   readings (`want · went · wart`) — tapping one of those *replaces* the inserted word.
2. A moment later, up to 2 learned next words are appended after the alternates:
   `want · went · wart · to · more`. Tapping `to` *appends* "to" after "want" — it does
   not replace your swipe. Both behaviors live in the same bar; long-press any entry to
   see which kind it is.

**Seeing why a word was suggested:** long-press any next-word suggestion. A small sheet
shows its source ("Next-word prediction (learned)") and the statistics behind it, e.g.
*After "want to": seen 14×, 63%*. You can also enable colored origin dots for every
suggestion under Settings > Advanced > Suggestion Origin Markers.

## When suggestions will NOT appear

| Condition | Why |
|-----------|-----|
| Feature toggle off (default) | Opt-in |
| "Learn From My Typing" off (Privacy) | Next-word reads your learned data — with learning off, that data is not used at all |
| Password fields | Secure mode |
| Private/incognito fields (apps that request no personalized learning, e.g. browser private tabs) | The app asked the keyboard not to personalize |
| Terminal (Termux) | Terminal input is not prose |
| An autocorrect-undo or add-to-dictionary prompt is showing | Prompts take priority |
| Nothing learned yet above the confidence floor | Empty bar by design |

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| **Next-Word Prediction** | Off | The feature toggle (Input Behavior > Word Prediction) |
| **Learn From My Typing** | On | Master privacy switch (Privacy & Data) — must be on for next-word to work |
| **Context Source** | Both | Which phrase model boosts predictions: built-in, your learned patterns, or both |
| **Personalization Strength** | 1.0 | How strongly your word usage boosts predictions (0 = off, 2 = double) |
| **Suggestion Origin Markers** | Off | Colored dot per suggestion showing which engine produced it (Advanced) |

## Tips and Tricks

> [!TIP]
> Give it a few days. The confidence floor (a phrase must be seen at least twice) means
> the feature starts quiet and gets better the more you type.

> [!TIP]
> You can inspect and delete anything the keyboard has learned under
> Settings > Input Behavior > Learning & Data — browse learned phrases and words,
> delete individual entries, or forget everything.

## Related Features

- [Autocorrect & Predictions](./autocorrect.md) - The main prediction pipeline
- [Privacy Settings](../settings/privacy.md) - The master learning switch and data controls
- [Input Behavior Settings](../settings/input-behavior.md) - Where the toggles live

## Technical Details

See the [Next-Word Prediction Technical Specification](../specs/typing/next-word-prediction-spec.md).
