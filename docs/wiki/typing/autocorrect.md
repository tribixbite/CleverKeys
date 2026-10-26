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
| **Access** | Automatic while typing; toggle in Settings → Auto-Correction |
| **Undo** | Tap the original word in the prediction bar |
| **Layouts** | Latin letters use a built-in QWERTY map; non-Latin boards (Hebrew, Cyrillic, Greek …) use their own key grid |
| **Languages** | Latin accents (é, ñ, ü, etc.) covered out of the box; non-Latin letters get adjacency from your layout |

## How Autocorrect Works

When you finish a word (by pressing space or punctuation):

1. **Non-prose context check** — if the token at the cursor looks like a URL, email address, or file path (it contains `. / : @ # ? & = %` or digits), autocorrect skips it entirely. Domains and pasted-then-edited links never get "corrected".
2. **Exact alias check** — if the typed word is a known contraction base (`dont`, `im`, `cant`), it expands directly (`don't`, `I'm`, `can't`).
3. **Dictionary hit** — if the word is in the dictionary as-is, no correction.
4. **Elongation collapse** — a doubled letter that collapses to a real word is fixed directly: `gamees` → `games`, `embeer` → `ember`. The reverse, a **dropped double letter**, is fixed when doubling one letter gives a dictionary word that is not much rarer than the dictionary scan's own pick: `helo` → `hello`, while `bery`, `grom` and `sed` still become `very`, `from` and `see` (the margin was tuned and gated on the shipped dictionary, `docs/eval/2026-10-11-dropped-double-margin.md`).
5. **Inflection guard** — regular inflections of dictionary words (`-s`, `-ed`, `-ing`, `-er`, `-est`, `-ly`, ...) are treated as valid even if the inflected form isn't stored, so real words don't get "corrected".
6. **Possessive guard** — possessives of known words (`ember's`, `dogs'`) are valid and left alone. A possessive of a *typo* gets its base corrected while keeping the apostrophe: `embeer's` → `ember's`.
7. **Length / prefix gates** — words shorter than the minimum length, or that violate the prefix rule, are skipped.
8. **Adjacency-weighted dictionary scan** — every dictionary word within `max_length_diff` characters is scored by keyboard physics:
   - **Same length:** each position contributes a substitution score from `1.0` (exact match) down to `~0` (max-distant keys). Adjacent keys score `~0.89`. At most 2 substituted positions are allowed, so a single-typo match always beats a barely-similar lookalike.
   - **Adjacent swap:** transposing two neighboring letters (`teh` → `the`, `recieve` → `receive`) is recognized as its own typo class and scored nearly as high as a perfect match.
   - **Different length:** weighted Levenshtein distance where insert/delete cost 1.0 but substitution cost is the normalized key distance.
   - Words you've **disabled** in Dictionary Manager are never offered as correction targets.
9. **Rule-based tiebreaker** — a clearly better score wins outright; among close scores, a contraction wins only at equal-or-better score, one adjacent swap beats two independent substitutions, and otherwise the more common word wins.
10. **Frequency floor** — the winner must be a reasonably common word (see the slider below). Words you added yourself are exempt.
11. **Alias rerouting** — if the winning candidate is a contraction base (`dont` → `don't`), the apostrophe form is returned.
12. **Capitalization preservation** — `Teh` → `The`, `TEH` → `THE`.

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

**Latin letters** (`a`–`z` and the accented forms below) are scored on a built-in US-QWERTY map, whatever Latin layout is active. Using the live AZERTY / QWERTZ / Dvorak / custom geometry for them is planned but not enabled yet: it has to be measured on the autocorrect replay first.

**Letters the QWERTY map does not cover** — Hebrew, Cyrillic, Greek, Arabic and other non-Latin boards — are positioned from **the active layout's own key grid**, on the same key-pitch scale. A neighbour-key typo on a Hebrew board (`החא` for `הוא`, `ח` next to `ו`) earns the same credit as `tge` for `the` on QWERTY. Before 2026-10-08 these letters had no positions at all, so every substitution looked maximally distant and Hebrew neighbour typos were left alone or "corrected" to an anagram or a shorter word (`החא` → `האח`).

### Accented Letters

Common Latin diacritics (`é, ñ, ü, ç, ß, å, ø, ý` and ~20 others) share the position of their unaccented base. So:

- `cafe → café` — distance is 0 on the accent position. Correction is free.
- `senor → señor` — same.
- `mude → müde` (German) — same.

If you're on a keyboard layout that has a **dedicated accent key**, the real key position overrides this default. The accent fallback only kicks in when the keyboard doesn't have a physical key for the accented form.

## When Autocorrect Stays Out of the Way

Autocorrect deliberately refuses to touch several categories of input:

- **URLs, emails, and file paths.** If the token you're typing contains `. / : @ # ? & = % ~ \` or digits (`example.com`, `user@host`, `~/notes/todo.txt`), autocorrect is suppressed for that token. It resumes on the next normal word.
- **Possessives.** `ember's` is valid English even though the possessive form isn't in the dictionary, so it's never corrected. Typos inside a possessive still get fixed: `embeer's` → `ember's`.
- **Regular inflections.** `-s`, `-es`, `-ed`, `-ing`, `-er`, `-est`, `-ly` forms of dictionary words are accepted as-is.
- **Disabled words** never get *picked as corrections* either — disable a word in Dictionary Manager and autocorrect will not steer your typing toward it.
- **Your own words.** Custom and user-dictionary words are protected from correction and exempt from the frequency floor.

### Suggestions in URL bars

Browser URL bars don't report cursor position the way normal text fields do. CleverKeys detects these fields and makes suggestion taps **replace the partial token you typed** (typing `exa` then tapping `example` yields `example`, not `exa example`), without adding the trailing space that would break a URL.

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
4. The word is automatically added to your dictionary so it won't be corrected again. The
   bar confirms with "Added “word” to dictionary" — tap that message twice to undo the add
   (see below).

### Method 2: Backspace and Retype
1. Press backspace to delete the corrected word.
2. Retype your intended word.
3. Add it to your dictionary via the prompt that appears.

## Adding Words to the Dictionary

When you type an unknown word and autocorrect doesn't fire:

1. Type the word and press space.
2. A prompt appears in the suggestion bar: "Add 'word' to dictionary?"
3. Tap to add. The word will be suggested in future and protected from autocorrect.
4. The bar confirms "Added “word” to dictionary". Changed your mind? **Tap that message
   twice**: the first tap shows "Tap again to undo", the second removes the word from your
   dictionary again ("Removed “word” from dictionary"). What you typed stays as it is. The
   confirmation disappears after about 3 seconds, or as soon as you keep typing.

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

All in the Settings → **Auto-Correction** section. Most users only ever touch the top three.

### Primary Knobs

| Setting | Default | What it does |
|---------|---------|--------------|
| **Autocorrect** (`autocorrect_enabled`) | on | Master toggle |
| **Min word length** (`autocorrect_min_word_length`) | 2 | Skip autocorrect on very short words |
| **Starting Letter Accuracy** (`autocorrect_prefix_length`) | 0 | Letters at the start of a word that autocorrect keeps (0–4). `0` also fixes a wrong first letter (`wuestion → question`); `1` or more never does. |

### Advanced Tuning

| Setting | Default | What it does |
|---------|---------|--------------|
| **Score threshold** (`autocorrect_char_match_threshold`) | 0.65 | Minimum match score (0.0–1.0) a candidate must reach. Higher = more conservative. |
| **Max length difference** (`autocorrect_max_length_diff`) | 2 | Allow candidates whose length differs by up to this many characters. Handles insertion/deletion typos like `questin → question`. |
| **Min frequency floor** (`autocorrect_confidence_min_frequency`) | 100 | How common the winning candidate must be before it replaces your word. The slider (100–2000) maps onto the loaded dictionary's own frequency scale: 100 = no floor (any dictionary word can win), 2000 = only fairly common words win. Words you added yourself are always exempt. |

### Swipe Typing

| Setting | Default | What it does |
|---------|---------|--------------|
| **Final Autocorrect** (`swipe_final_autocorrect_enabled`) | on | Apply dictionary-based correction to a swiped word when it is committed. (The older "beam autocorrect" option left with the removed neural engine.) |

### Ranking & Tuning

The **Ranking & Tuning** group at the bottom of the Auto-Correction section exposes every
weight the corrector uses. The defaults reproduce the shipped behaviour; change them only if
you can test the result on your own typos.

| Setting | Default | What it does |
|---------|---------|--------------|
| **Correction Ranking** | Edit cost plus frequency | How autocorrect picks between several close dictionary words. *Score band, then frequency* is the older method; only the weights of the selected method are shown. |
| **Score Band Width** | 0.10 | (Band method) words whose scores differ by less than this count as a tie, and the more common word wins |
| **Swapped Letters Penalty** | 0.15 | (Band method) score lost by a word reached by swapping two neighbouring letters (`teh → the`), divided by word length |
| **Restore Missing Double Letter** | On | (Band method) correct a double letter typed once (`helo → hello`) even when another correction is more common |
| **Double-Letter Frequency Margin** | 64 | How much rarer the double-letter word may be and still win |
| **Same-Length Match Minimum** | 0.50 | Share of letters already in place before a same-length word is considered |
| **Maximum Wrong Letters** | 2 | Most letters a same-length correction may replace |
| **Length-Change Allowance** | 0.5 | Extra edit distance allowed, beyond the added or missing letters, for a correction of another length |
| **Remove Extra Repeated Letter** | On | Correct an accidental extra copy of a letter (`gamees → games`) |
| **Keep Unknown Word Forms** | On | Leave a known word plus `-s`, `-ed`, `-ing` alone (`immunizations`) |
| **Minimum Known Stem** | 4 | Shortest known word, before the ending, that keeps the typed word |
| **Distant-Key Cost** | 2.0 | (Edit-cost method) extra cost of a wrong letter far from the key you meant; 0 makes every wrong letter cost the same |
| **Missing or Extra Letter Cost** | 1.5 | (Edit-cost method) cost of a missing or extra letter; one wrong neighbouring key costs 1 |
| **Double-Letter Cost** | 1.0 | (Edit-cost method) cost of a missing or extra letter that repeats the letter beside it (`helo → hello`) |
| **Swapped Letters Cost** | 1.2 | (Edit-cost method) cost of two neighbouring letters typed in the wrong order |
| **Word Frequency Weight** | 0.005 | (Edit-cost method) how much a word's commonness offsets its edit cost |
| **Contraction Preference** | 0.25 | (Edit-cost method) bonus for a contraction whose apostrophe-free spelling you typed (`dont → don't`) |
| **Short-Word Deletion Cost** and its scope | 0.5, Hebrew-script words only | (Edit-cost method) extra cost of deleting onto a word of two letters or fewer |
| **Maximum Correction Distance** | 3.0 | (Edit-cost method) no correction above this edit cost (10 = off); protects valid words the dictionary lacks |
| **Word-Form Keep Cost** | 1.0 | (Edit-cost method) cost that keeps a known word plus an ending from being corrected (10 = always keep) |

Defaults are the `AUTOCORRECT_*` values in `Config.kt` at v2.0.0 and reproduce the behaviour
that shipped before these were settings; the control's own text in Settings is the authority
if they differ. Engineering detail: `docs/eval/2026-10-18-channel-autocorrect-ranking.md`.

> [!NOTE]
> Autocorrect's tap-typing path uses the absolute dictionary frequency directly via `autocorrect_confidence_min_frequency`. The swipe engines score candidates with their own per-language constants, which are calibrated offline and not user-tunable.

## The Highlighted Suggestion Is What Space Commits

While you tap-type a word, at most one suggestion is drawn in the highlight colour (the theme's
**High Confidence** accent), and it is always the word pressing **space** will write:

- If autocorrect will correct the word, the correction is highlighted in the **first** slot —
  moved there if the bar already listed it, added if it did not. Typing `Helo` highlights
  `Hello` because that is what space commits; other candidates such as `Help` stay in the
  bar to tap.
- If space will keep what you typed, nothing else is highlighted. A valid word you typed is
  highlighted where the bar lists it; a completion such as `hello` for `hel` is never
  highlighted, because space would leave `hel`.
- I-words follow the same rule: typing `i` highlights `I`.

Anything that stops autocorrect also stops the highlighted replacement: autocorrect switched
off, terminal apps, URL/email/path tokens, and words that are already valid. Tapping any
suggestion still commits exactly that suggestion.

Before 2026-10-11 the first bar entry was always highlighted, whichever ranker put it there,
while space used autocorrect's own pick. On 2,780 real misspellings the two disagreed 2,053
times (`docs/eval/2026-10-11-dropped-double-autocorrect.md` §2.5).

## Typo-Tolerant Suggestions

While you tap-type, the suggestion bar also offers likely *intended* words when what you
typed looks like a slip — for example `pka` or `pkay` offers `play` (the `k` sits next to
the `l`). Neighbouring-key slips, swapped letters and accent variants count as small
mistakes; other edits as larger ones, and the allowance grows with word length (nothing
below 3 letters). The search uses your active layout, so AZERTY, Dvorak and custom boards
get their own neighbour keys.

These are **suggestions only** — nothing is changed until you tap one (or autocorrect
applies its own correction when you press space). They run only when the exact matches are
missing, few, or rare, rank below an exact match of the same shape and frequency, and take
at most 2 bar slots. Long-press one to see "Correction of typed “pka”"; with Suggestion
Origin Markers on it carries its own purple dot, distinct from autocorrect's red.

## Suggestion Transparency

Every suggestion in the bar knows where it came from. To inspect one:

- **Long-press any suggestion** — a sheet shows its source (CTC swipe, geometric swipe,
  geometric decoder, dictionary prefix match, contraction, next-word, typo correction, etc.), its score, and — for
  dictionary predictions — the full score breakdown: prefix match, adaptation, built-in
  vs. learned context boost (and which one won), personalization, and frequency factor.
- **Suggestion Origin Markers** (Settings > Advanced, off by default) — adds a small
  colored dot to each suggestion indicating which engine produced it. With a screen reader
  (TalkBack), a marked suggestion is announced as the word followed by its origin — e.g.
  "play, Typo correction" — rather than the dot symbol, so the marker is never colour-only.

This makes it possible to answer "why is the keyboard suggesting THAT?" — for example, a
learned next-word candidate will show the statistics behind it, like
*After "want to": seen 14×, 63%*.

## Tips and Tricks

- **Proper nouns:** Add names and places to prevent miscorrection. They'll then short-circuit the dictionary scan and never get autocorrected.
- **Technical jargon:** Same as proper nouns — add to dictionary.
- **Disabled list:** Move a word to the Disabled tab in Dictionary Manager to prevent autocorrect from EVER picking it as a candidate.
- **Language-specific behavior:** Each language has its own dictionary AND its own contractions file. Adjacency follows the keys, not the language: Latin letters use the QWERTY map, other letters your current layout's grid.

## Common Questions

### Q: Why did `tge` correct to `the` but `tgw` didn't correct to anything?
A: `g↔h` is one adjacent-key substitution (score ~0.96) for `tge → the`. `tgw` would need a substitution for `g↔h` AND a substitution for `w↔e`, dropping the score below the threshold.

### Q: How does `teh` become `the` when t-e-h are all "wrong" positions?
A: Swapping two adjacent letters (a transposition) is one of the most common typo classes, so it gets a dedicated fast path: `teh → the`, `thsi → this`, `recieve → receive`. A transposition scores just below a perfect match and beats any candidate needing two independent substitutions.

### Q: Why won't autocorrect fix typos in my URL / email / file path?
A: By design. Tokens containing `. / : @ # ? & = %` or digits are treated as non-prose and skipped — "correcting" a domain or path usually breaks it. Finish the token and autocorrect resumes on the next word.

### Q: My word is technical jargon and keeps getting corrected. What do I do?
A: Add it to your dictionary via the "Add to dictionary?" prompt, or via Settings → Dictionary Manager → Custom tab. Once added, autocorrect skips it.

### Q: Does autocorrect work for languages other than English?
A: Yes. Each language has its own word dictionary and contractions file, and adjacency is about which keys you tapped: Latin letters are scored on the QWERTY map, and the letters of a non-Latin board (Hebrew, Cyrillic, Greek …) on that board's own key grid.

### Q: Why does `well` stay as `well` instead of becoming `we'll`?
A: `well` is a real English word, so it's on the protected list. CleverKeys won't expand it to `we'll` because that would be wrong in most contexts ("the well ran dry").

### Q: How does autocorrect interact with swipe typing?
A: They're separate paths. Swipe typing produces candidates with the CTC or geometric decoder, then optionally applies autocorrect on the top candidate (`swipe_final_autocorrect_enabled`). Tap typing uses the dictionary scan described above.

## Related Features

- [Swipe Typing](swipe-typing.md) - Word prediction for gesture input
- [Next-Word Prediction](next-word-prediction.md) - Next-word suggestions after each word (built-in + learned)
- [User Dictionary](user-dictionary.md) - Add custom words
- [Special Characters](special-characters.md) - Symbols and accents
- [Smart Punctuation](smart-punctuation.md) - Punctuation attachment and smart auto-space around punctuation

## Technical Details

See [Autocorrect Technical Specification](../specs/typing/autocorrect-spec.md) for the pipeline internals, weight calibration, and the four-tier selection logic.
