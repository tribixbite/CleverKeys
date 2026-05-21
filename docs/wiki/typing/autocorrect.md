---
title: Autocorrect
description: How CleverKeys decides when and what to autocorrect, plus the keyboard-adjacency model that powers it
category: Typing
difficulty: beginner
related_spec: ../specs/typing/autocorrect-spec.md
---

# Autocorrect

CleverKeys autocorrect knows that **adjacent keys are more likely typos than distant ones**. When you type `tge` instead of `the`, it weights `g→h` as a much cheaper substitution than `g→x` because g and h are physically neighbors on QWERTY. This page walks through the full pipeline, the settings you can tune, and how contractions and accents interact with it.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Fix tap-typing mistakes using keyboard physics + dictionary frequency |
| **Access** | Automatic while typing; toggle in Settings → Word Prediction |
| **Undo** | Tap the original word in the prediction bar |
| **Layouts** | Adapts automatically to AZERTY / QWERTZ / Dvorak / custom layouts |
| **Languages** | Latin accents (é, ñ, ü, etc.) covered out of the box |

## How Autocorrect Works

When you finish a word (by pressing space or punctuation):

1. **Exact alias check** — if the typed word is a known contraction base (`dont`, `im`, `cant`), it expands directly (`don't`, `I'm`, `can't`).
2. **Dictionary hit** — if the word is in the dictionary as-is, no correction.
3. **Length / prefix gates** — words shorter than the minimum length, or that violate the prefix rule, are skipped.
4. **Adjacency-weighted dictionary scan** — every dictionary word within `max_length_diff` characters is scored by keyboard physics:
   - **Same length:** each position contributes a substitution score from `1.0` (exact match) down to `~0` (max-distant keys). Adjacent keys score `~0.89`.
   - **Different length:** weighted Levenshtein distance where insert/delete cost 1.0 but substitution cost is the normalized key distance.
5. **Four-tier tiebreaker** — picks the winner using score, frequency, and alias preference. Details below.
6. **Alias rerouting** — if the winning candidate is a contraction base (`dont` → `don't`), the apostrophe form is returned.
7. **Capitalization preservation** — `Teh` → `The`, `TEH` → `THE`.

## The Key Proximity System

Each letter has an `(x, y)` position on the keyboard grid. Substitution cost between two letters is the **normalized euclidean distance** between their key centers:

| Pair | Why | Score |
|------|-----|-------|
| `e ↔ e` | Same key | `1.00` |
| `g ↔ h` | Adjacent home row | `~0.89` |
| `t ↔ y` | Adjacent top row | `~0.89` |
| `q ↔ s` | Diagonal one key away | `~0.80` |
| `q ↔ p` | Opposite ends of top row | `0.00` |

So `tge → the` (one `g↔h` substitution, score ~0.96) ranks far above `tge → tax` (which would require two distant substitutions). The result: closer to your actual fingertip travel, less random-looking corrections.

### Layout-Aware Adjacency

Adjacency is computed from **your actual keyboard layout**, not a hardcoded QWERTY map. When you switch to AZERTY (where `a` and `q` swap from QWERTY positions), or use Dvorak, or load a custom layout, the proximity model updates automatically. French AZERTY users get the right adjacency for their `q↔a` typos; QWERTZ users get `y↔z` treated as adjacent.

### Accented Letters

Common Latin diacritics (`é, ñ, ü, ç, ß, å, ø, ý` and ~20 others) share the position of their unaccented base. So:

- `cafe → café` — distance is 0 on the accent position. Correction is free.
- `senor → señor` — same.
- `mude → müde` (German) — same.

If you're on a keyboard layout that has a **dedicated accent key**, the real key position overrides this default. The accent fallback only kicks in when the keyboard doesn't have a physical key for the accented form.

## Contractions

If you type a contraction base — `dont`, `cant`, `im`, `hadnt`, `couldnt`, `youre` — it expands to the apostrophe form (`don't`, `can't`, `I'm`, `hadn't`, `couldn't`, `you're`). The full list lives in `dictionaries/contractions_en.json`.

If you **mistype** a contraction base — `donr`, `hadnr`, `couldnr` — the adjacency-weighted scan picks the closest contraction and reroutes to the apostrophe form. So `donr → don't`, `hadnr → hadn't`, `couldnr → couldn't`. The selection logic prefers structural closeness (a single adjacent-key sub) over dictionary popularity here — `hadnr` becomes `hadn't` not `hasn't` because the d-to-t alignment requires fewer substitutions.

Words that **are** valid English even though they look like contraction bases (`well`, `were`, `hell`, `shed`, `shell`, `wed`) are protected from this expansion — `well` stays `well`, not `we'll`.

## Undoing Autocorrect

If autocorrect changes a word you didn't want changed:

### Method 1: Tap the Original Word
1. Look at the prediction bar immediately after correction.
2. Your original word appears as a suggestion.
3. Tap it to restore the original spelling.
4. The word is automatically added to your dictionary so it won't be corrected again.

### Method 2: Backspace and Retype
1. Press backspace to delete the corrected word.
2. Retype your intended word.
3. Add it to your dictionary via the prompt that appears.

## Adding Words to the Dictionary

When you type an unknown word and autocorrect doesn't fire:

1. Type the word and press space.
2. A prompt appears in the suggestion bar: "Add 'word' to dictionary?"
3. Tap to add. The word will be suggested in future and protected from autocorrect.

> [!TIP]
> Added words appear in your predictions when you start typing them.

## Managing Your Dictionary

Open Settings (gear icon on keyboard), then **Activities → Dictionary Manager**:

| Tab | Contents |
|-----|----------|
| **Active** | Words from the bundled / language-pack dictionary |
| **Disabled** | Words you've turned off — never auto-suggested or autocorrected |
| **User** | Words from Android's system user dictionary |
| **Custom** | Words you've added yourself |

## Autocorrect Settings

All in Settings → **Word Prediction**. Most users only ever touch the top three.

### Primary Knobs

| Setting | Default | What it does |
|---------|---------|--------------|
| **Autocorrect** (`autocorrect_enabled`) | on | Master toggle |
| **Min word length** (`autocorrect_min_word_length`) | 2 | Skip autocorrect on very short words |
| **Required prefix length** (`autocorrect_prefix_length`) | 0 | Candidate must share this many starting chars with your typed word. `0` = no prefix required (lets first-character typos like `wuestion → question` correct). `2` is the legacy stricter mode. |

### Advanced Tuning

| Setting | Default | What it does |
|---------|---------|--------------|
| **Score threshold** (`autocorrect_char_match_threshold`) | 0.65 | Minimum match score (0.0–1.0) a candidate must reach. Higher = more conservative. |
| **Max length difference** (`autocorrect_max_length_diff`) | 2 | Allow candidates whose length differs by up to this many characters. Handles insertion/deletion typos like `questin → question`. |
| **Min frequency floor** (`autocorrect_confidence_min_frequency`) | 100 | The winning candidate must have at least this dictionary frequency to actually replace your word. Filters out very rare words. |

### Swipe Typing

| Setting | Default | What it does |
|---------|---------|--------------|
| **Beam autocorrect** (`swipe_beam_autocorrect_enabled`) | on | Apply correction during swipe beam search |
| **Final autocorrect** (`swipe_final_autocorrect_enabled`) | on | Apply correction on swipe completion |
| **Neural frequency weight** (`neural_frequency_weight`) | 0.57 | How much to weight dictionary frequency vs. neural model confidence in swipe predictions. Higher = trust frequency more. |

> [!NOTE]
> `neural_frequency_weight` lives in the Neural Settings activity (Settings → Neural prediction). It controls the swipe pipeline; autocorrect's tap-typing path uses the absolute frequency directly via `autocorrect_confidence_min_frequency`.

## Tips and Tricks

- **Proper nouns:** Add names and places to prevent miscorrection. They'll then short-circuit the dictionary scan and never get autocorrected.
- **Technical jargon:** Same as proper nouns — add to dictionary.
- **Disabled list:** Move a word to the Disabled tab in Dictionary Manager to prevent autocorrect from EVER picking it as a candidate.
- **Language-specific behavior:** Each language has its own dictionary AND its own contractions file. The adjacency model uses your current keyboard layout, regardless of language.

## Common Questions

### Q: Why did `tge` correct to `the` but `tgw` didn't correct to anything?
A: `g↔h` is one adjacent-key substitution (score ~0.96) for `tge → the`. `tgw` would need a substitution for `g↔h` AND a substitution for `w↔e`, dropping the score below the threshold.

### Q: My word is technical jargon and keeps getting corrected. What do I do?
A: Add it to your dictionary via the "Add to dictionary?" prompt, or via Settings → Dictionary Manager → Custom tab. Once added, autocorrect skips it.

### Q: Does autocorrect work for languages other than English?
A: Yes — the adjacency model is language-independent (it's about which keys you tapped). Each language has its own word dictionary and contractions file.

### Q: Why does `well` stay as `well` instead of becoming `we'll`?
A: `well` is a real English word, so it's on the protected list. CleverKeys won't expand it to `we'll` because that would be wrong in most contexts ("the well ran dry").

### Q: How does autocorrect interact with swipe typing?
A: They're separate paths. Swipe typing produces candidates via the neural model + beam search, then optionally applies autocorrect on the top candidate (`swipe_beam_autocorrect_enabled`, `swipe_final_autocorrect_enabled`). Tap typing uses the dictionary scan described above.

## Related Features

- [Swipe Typing](swipe-typing.md) - Neural word prediction for gesture input
- [User Dictionary](user-dictionary.md) - Add custom words
- [Special Characters](special-characters.md) - Symbols and accents

## Technical Details

See [Autocorrect Technical Specification](../specs/typing/autocorrect-spec.md) for the pipeline internals, weight calibration, and the four-tier selection logic.
