---
title: Swipe Typing
description: Draw paths through letters to type words
category: Typing
difficulty: beginner
featured: true
---

# Swipe Typing

Swipe typing lets you type words by drawing a continuous path through letters. CleverKeys ships two on-device prediction engines — a CTC model (the default) and a geometric decoder — and the Prediction Engine setting chooses between them.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Type words faster by swiping |
| **Gesture** | Draw path through letters without lifting finger |
| **Engines** | CTC (default) and geometric — selectable in Settings |

## How It Works

Instead of tapping each letter:

1. **Touch the first letter** of your word
2. **Slide your finger** through each letter in order
3. **Lift your finger** at the last letter
4. The word appears in the text

For example, to type "hello":
- Touch **h** → slide to **e** → slide to **l** → slide to **l** → lift at **o**

## How to Use

### Step 1: Start on the First Letter

Touch and hold the first letter of your word. A trail appears showing your path.

### Step 2: Draw Through Letters

Without lifting your finger, slide through each letter in sequence. You don't need to be perfectly accurate - the AI predicts your intended word.

### Step 3: Lift to Complete

Lift your finger when you reach the last letter. The predicted word appears in your text.

### Step 4: Choose from Predictions

If the wrong word appears:
- Check the prediction bar for alternatives
- Tap the correct word to replace it

## Tips for Better Accuracy

- **Start and end precisely**: Begin and end on the correct letters
- **Hit key letters**: Pass through distinctive letters in the word
- **Maintain steady speed**: Don't rush or pause mid-word
- **Use longer swipes**: Longer words are easier to predict than short ones

> [!TIP]
> Double letters (like 'll' in 'hello') can be swiped in a small loop or just passed through once.

## Prediction Bar

After swiping, predictions appear in a horizontal row ordered by confidence (best match on the left). The first suggestion is auto-inserted and highlighted. Up to 6 alternatives may appear.

Tap any prediction to use it instead.

## Choosing a Prediction Engine

The **Prediction Engine** dropdown (Settings > Swipe Typing) selects which decoder handles your swipes. The right engine is picked automatically per swipe, based on your layout and language:

| Mode | Latin layout, CTC-served language | Non-Latin layout with a wired script (Cyrillic, Greek, Hebrew) | Anything else |
|------|-----------------------------------|----------------------------------------------------------------|---------------|
| **CTC** (default) | CTC | CTC (per-script encoder) | Geometric |
| **Geometric** | Geometric | Geometric | Geometric |

- **CTC** — the CleverKeys-trained decoder. In our benchmark on 2,400 held-out English
  swipes it got the intended word right on the first try about 89% of the time. It serves
  three groups:

  1. The **7 bundled Latin languages** — English, French, German, Spanish, Italian,
     Portuguese, Swedish — on any Latin layout that has all 26 letters (QWERTY, AZERTY,
     QWERTZ, Dvorak, Colemak…).
  2. **6 non-Latin languages via script routing** — Russian, Ukrainian, Bulgarian and
     Macedonian (Cyrillic), Greek, and Hebrew. Each has its own layout, emission alphabet
     and encoder, and becomes available once you import that language's pack.
  3. Any **imported Latin language pack whose vocabulary is a–z-typeable**. CleverKeys
     measures the pack itself rather than trusting a label: Dutch, Indonesian, Malay,
     Tagalog and Swahili all measure 100% typeable and are served.

  **Turkish is the deliberate exception.** Dotless `ı` has no a–z spelling at all, so a
  quarter of the Turkish vocabulary — and a sixth of its thousand most common words
  (`nasıl`, `artık`, `mı`, `aynı`) — could not be swiped under CTC. Turkish is routed to
  the geometric engine, which decodes over the board's real keys and can therefore reach
  those words. The same test rejects Polish `ł`, Vietnamese `đ` and Icelandic `þ`/`ð`
  packs.
- **Geometric** — a pure shape-matching decoder on all layouts. It is the automatic
  fallback for every language and layout CTC does not serve, so choosing CTC never leaves
  a layout without swipe typing. Also useful for comparison and battery-lean decoding.

### How much we actually know per language

Only **English, French, German and Spanish** have their own measured accuracy bar; the 89%
figure above is English.

**Italian, Portuguese and Swedish** were added on 2026-08-18 and are **provisional**. We
have never been able to measure CTC's accuracy on them — no public swipe corpus exists —
so what carries them is the decoder setting they share with French, German and Spanish (it
is calibrated against the dictionary's frequency scale, not the language) plus the fact
that the model reads key positions and never sees a language at all. The alternative was
the geometric engine, which has no measurement on those languages either and lost by 15–22
points everywhere both were measured.

**Russian** is validation-tier: it has a real-swipe probe, but not a held-out test bar of
the kind English has.

**Greek, Ukrainian, Bulgarian, Macedonian and Hebrew, and every imported pack**, have no
real-swipe probe at any tier. An imported pack is provisional permanently and by
construction — the word list is a file you brought, so there is no corpus to measure it
against. No accuracy number is published for any of these, and any figure you see quoted
for English/French/German/Spanish does *not* apply to them.

  Accented words work normally: a swipe traces the unaccented letters (there is no separate
  "é" key on the path), and the engine inserts the dictionary's accented spelling — swipe
  `c-a-f-e` in French and you get "café". Where two words share the same unaccented
  spelling, the more common one is offered.

> [!NOTE]
> Before v1.6.0 there were two further modes, **Neural** and **Hybrid**, backed by an ONNX
> transformer. That engine was removed: CTC beat it by a wide margin on the same test set
> (89% vs 75% first-try) while the transformer only worked on QWERTY and cost about 10 MB of
> app size. If your device still has "Neural" or "Hybrid" stored, it now behaves as CTC.

Whichever engine decodes a swipe, the results flow through the same suggestion bar, autocorrect, and contraction handling ("dont" is shown as "don't"). If you enable suggestion origin markers, each suggestion is tagged with the engine that actually produced it.

### CTC Settings

With the CTC engine selected, a **Full CTC Settings** button appears with one tuning knob:

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| **Beam Width** | 100 | 10–300 | How many word hypotheses the decoder keeps while tracing your swipe. 100 is the validated default; higher values cost CPU per swipe for marginal accuracy. |

The CTC scoring constants are calibrated offline and deliberately not user-tunable.

## Settings

Tune swipe typing in Settings > Swipe Typing:

| Setting | Description |
|---------|-------------|
| **Swipe Typing** | Enable/disable swipe input |
| **Prediction Engine** | CTC / Geometric (see above) |
| **Swipe on Password Fields** | Allow swipe typing in password fields (default: off) |
| **Backspace Undo Swipe** | Backspace deletes entire swiped word + auto-space (default: on) |

## Undoing a Swipe

If the wrong word was predicted after swiping:

### Quick Undo with Backspace

Press **backspace immediately** after swiping (before typing anything else). The entire swiped word and its trailing auto-space are deleted in one press, so you can swipe again.

This behavior is controlled by the **Backspace Undo Swipe** toggle in Settings > Word Prediction (enabled by default). When disabled, backspace deletes a single character as normal.

### Choose an Alternative

Check the prediction bar — alternative words are shown left-to-right by confidence. Tap any alternative to replace the auto-inserted word.

## When Swipe Typing Doesn't Work

Swipe typing may not activate when:
- Swipe typing is disabled in settings
- Typing in password fields (unless enabled in settings)
- The swipe is too short (detected as tap)
- No language pack is available

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - Quick access to subkeys
- [Autocorrect](autocorrect.md) - Fix mistakes automatically
