---
title: Next-Word Prediction
description: Suggest your next word before you type a letter, from a built-in English phrase model and (with learning on) your own phrases
category: Typing
difficulty: intermediate
related_spec: ../specs/typing/next-word-prediction-spec.md
---

# Next-Word Prediction

Next-word prediction suggests what you are likely to type next — before you press a single
letter. It draws on two sources: a **built-in phrase model** that ships with the keyboard
(identical on every device, nothing personal in it), and — only if you turn on on-device
learning — **phrases learned from your own typing**. It is fully on-device and **on by
default** (since v2.0); the built-in part works even with learning off.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Suggest the next word before you type a letter |
| **Access** | Settings > Input Behavior > Word Prediction > Next-Word Prediction |
| **Default** | On (since v2.0; an install where you had switched it off keeps it off) |
| **Data source** | Built-in phrase model (English: a model built from public text corpora; French, German, Italian, Portuguese, Spanish: a smaller built-in list of common word pairs). With Learn From My Typing on, your own learned phrases come first. Nothing downloaded, nothing leaves the device, and next-word itself records nothing |

## How It Works

Next-word suggestions fill the suggestion bar at moments when it would otherwise be empty.
They come in two tiers:

- **Built-in (always available)** — for English, a phrase model that ships inside the app:
  about 75,000 common word pairs over 15,000 words, built from the Leipzig Corpora Collection
  (English web text, 2018) and Tatoeba example sentences. For French, German, Italian,
  Portuguese and Spanish a smaller hand-picked list of common word pairs plays the same role.
  This tier contains nothing about you, so it works with on-device learning **off**, with
  Context-Aware Predictions off, and in private/incognito fields.
- **Learned (optional)** — with **Learn From My Typing** on (Privacy & Data) and
  **Context-Aware Predictions** on, CleverKeys also privately learns which words you use
  after which (word pairs and three-word sequences). Those learned phrases always come first;
  the built-in tier tops up any slots they leave free.

Next-word prediction itself never records anything — it only reads. Learning happens (or
not) according to your Privacy & Data settings, exactly as for ordinary typing.

Suggestions appear:

- **After you finish a word with a space** — the bar offers up to 3 likely next words.
- **After you tap a suggestion** — the bar refills from the new context, so you can chain
  whole familiar phrases by tapping suggestions without touching the letter keys.
- **After a swipe** — your swipe's alternate readings stay in the bar (so you can still
  correct the swipe), and up to 2 next words are appended after them.
- **When you tap into text** — parking the cursor after a word offers continuations of the
  text just before the cursor (including text typed in an earlier session), similar to other
  modern keyboards. The text is read only for that lookup and is not stored.

Learned suggestions only appear when the keyboard is reasonably confident: a phrase must
have been seen at least twice, and the continuation must be likely (at least a 5% chance
given your history). Built-in suggestions are only ever used to fill slots your own data did
not — they can never push a learned suggestion aside.
**An empty bar is still normal** — showing nothing beats showing noise. Every suggestion,
built-in or learned, is filtered against your dictionary, so a typo you made twice will not
haunt the bar.

## Walkthrough: typing a sentence

Say you often type "I want to go home", and next-word prediction is on. With learning
**off**, only built-in continuations appear — for example after `want`: `to · you · a`. The
learned entries below additionally need Learn From My Typing on and the phrase typed at least
twice before.

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
2. A moment later, up to 2 next words are appended after the alternates:
   `want · went · wart · to · more`. Tapping `to` *appends* "to" after "want" — it does
   not replace your swipe. Both behaviors live in the same bar; long-press any entry to
   see which kind it is.

**Seeing why a word was suggested:** long-press any next-word suggestion. A small sheet
shows its source ("Next-word prediction") and, for a word learned from your own typing, the
statistics behind it, e.g. *After "want to": seen 14×, 63%*. Built-in continuations say
*After "the": common continuation (built-in, not learned)* instead of quoting statistics they
do not have. You can also enable colored origin dots for every suggestion under
Settings > Advanced > Suggestion Origin Markers; with a screen reader (TalkBack) a marked
suggestion is read as the word followed by its origin, e.g. "to, Next-word prediction".

## When suggestions will NOT appear

| Condition | Why |
|-----------|-----|
| Feature toggle switched off | Your choice (it is on by default) |
| Word prediction switched off | Next-word is part of word prediction |
| Password fields | Secure mode |
| Terminal (Termux) | Terminal input is not prose |
| An autocorrect-undo, add-to-dictionary or "Prefer … when swiping?" prompt is showing | Prompts take priority |
| Start of a sentence (after `.` `?` `!`) | There is no previous word to continue |
| No learned phrase above the confidence floor, and no built-in continuation for the last word | Empty bar by design. The English model covers about 14,500 previous words; the French, German, Italian, Portuguese and Spanish lists only the most common opening words |

**Only the learned tier** is switched off — the built-in tier keeps working — when:

| Condition | Why |
|-----------|-----|
| "Learn From My Typing" off (Privacy & Data; the default on new installs) | Your learned phrases are neither recorded nor read |
| Context-Aware Predictions off | That setting controls the learned phrase model |
| Private/incognito fields (apps that request no personalized learning, e.g. browser private tabs) | The app asked the keyboard not to personalize; built-in suggestions are not personal, just like ordinary word completions there |

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| **Next-Word Prediction** | On | The feature toggle (Input Behavior > Word Prediction) |
| **Learn From My Typing** | Off on new installs (upgrades keep it on) | Master privacy switch (Privacy & Data) — needed only for the learned tier |
| **Context-Aware Predictions** | On | Learns phrase patterns — needed only for the learned tier |
| **Context Source** | Both | Which phrase model boosts predictions: built-in, your learned patterns, or both |
| **Personalization Strength** | 1.0 | How strongly your word usage boosts predictions (0 = off, 2 = double) |
| **Suggestion Origin Markers** | Off | Colored dot per suggestion showing which engine produced it (Advanced) |

## Tips and Tricks

> [!TIP]
> If you turn on Learn From My Typing, give it a few days. The built-in model covers common
> phrases from the start, but the confidence floor on your own data (a phrase must be seen
> at least twice) means the suggestions get noticeably more personal the more you type.

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
