---
title: Next-Word Prediction
description: Suggest your next word from learned phrases (plus a built-in starter list) before you type a letter
category: Typing
difficulty: intermediate
related_spec: ../specs/typing/next-word-prediction-spec.md
---

# Next-Word Prediction

Next-word prediction suggests what you are likely to type next — before you press a single
letter — from phrases the keyboard has learned from your own typing, plus a small built-in
list of common word pairs that fills the gap before it has learned anything. It is fully
on-device, opt-in, and off by default.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Suggest the next word from your learned phrases (built-in pairs cover the gap until it has learned some) |
| **Access** | Settings > Input Behavior > Word Prediction > Next-Word Prediction |
| **Default** | Off (opt-in) |
| **Data source** | Your own learned phrase patterns first; a small shipped list of common word pairs fills leftover slots. Nothing downloaded, nothing leaves the device |

## How It Works

As you type, CleverKeys privately learns which words you use after which (word pairs and
three-word sequences). With next-word prediction on, those learned phrases fill the
suggestion bar at moments when it would otherwise be empty. Learned phrases always come
first; if they do not fill all the slots, CleverKeys tops them up from a small built-in list
of common word pairs, so the feature is useful on day one instead of after a week of typing:

- **After you finish a word with a space** — the bar offers up to 3 likely next words.
- **After you tap a suggestion** — the bar refills from the new context, so you can chain
  whole familiar phrases by tapping suggestions without touching the letter keys.
- **After a swipe** — your swipe's alternate readings stay in the bar (so you can still
  correct the swipe), and up to 2 learned next words are appended after them.
- **When you tap into text** — parking the cursor at the end of what you typed this
  session can offer continuations, similar to other modern keyboards.

Learned suggestions only appear when the keyboard is reasonably confident: a phrase must
have been seen at least twice, and the continuation must be likely (at least a 5% chance
given your history). The built-in pairs have no such history to judge, so they are only ever
used to fill slots your own data did not — they can never push a learned suggestion aside.
**An empty bar is still normal** — showing nothing beats showing noise. Every suggestion,
built-in or learned, is filtered against your dictionary, so a typo you made twice will not
haunt the bar.

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
shows its source ("Next-word prediction") and, for a word learned from your own typing, the
statistics behind it, e.g. *After "want to": seen 14×, 63%*. Before the keyboard has learned
anything about a phrase it can fall back to a small built-in list of common continuations;
those say *After "the": common continuation (built-in, not learned)* instead of quoting
statistics they do not have. You can also enable colored origin dots for every suggestion
under Settings > Advanced > Suggestion Origin Markers.

## When suggestions will NOT appear

| Condition | Why |
|-----------|-----|
| Feature toggle off (default) | Opt-in |
| "Learn From My Typing" off (Privacy) | Next-word reads your learned data — with learning off, that data is not used at all |
| Password fields | Secure mode |
| Private/incognito fields (apps that request no personalized learning, e.g. browser private tabs) | The app asked the keyboard not to personalize |
| Terminal (Termux) | Terminal input is not prose |
| An autocorrect-undo or add-to-dictionary prompt is showing | Prompts take priority |
| Nothing learned yet above the confidence floor, and no built-in pair for the last word | Empty bar by design. The built-in list covers English, French, German, Italian, Portuguese and Spanish, and only the most common opening words in each |

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
> Give it a few days. The built-in pairs cover common openers from the start, but the
> confidence floor on your own data (a phrase must be seen at least twice) means the
> suggestions get noticeably more personal the more you type.

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
