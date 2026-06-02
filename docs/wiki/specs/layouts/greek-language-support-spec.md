---
title: Greek Language Support — Design Specification
description: Plan for adding Greek (el) word suggestions + dictionary, and (phase 2) swipe via transliteration. Design proposal for issue #68.
status: planning
version: v1.4.0+
---

# Greek Language Support — Design Specification

> **Status:** Planning. Design proposal for
> [GitHub issue #68](https://github.com/tribixbite/CleverKeys/issues/68).
> NOT implemented. **Blocked on a licensing + distribution decision** (see
> "Decisions needed"). Linked from `docs/ROADMAP.md`.

## What the user asked for

Greek (`el`) word suggestions + dictionary. Autocorrect would be nice but
"good suggestions without aggressive autocorrect would already help a lot."
The reporter offered their own wordlist
([github.com/dim-geo/greekdictionary](https://github.com/dim-geo/greekdictionary))
and asked whether the swipe model can be retrained via a "pseudoenglish"
transliteration (map Greek letters to QWERTY key positions).

## What already exists (verified against current source)

CleverKeys' new-language infrastructure is mature — most of the surface is
already in place:

| Component | Status | Evidence |
|-----------|--------|----------|
| Greek keyboard layout | ✅ exists | `srcs/layouts/grek_qwerty.xml` (1 of 83 layouts) |
| Dictionary binary format | ✅ exists | 7 bundled `*_enhanced.bin` (de/en/es/fr/it/pt/sv) |
| Wordlist → binary converter | ✅ exists | `scripts/build_dictionary.py` (word-per-line or `word<tab>freq`) |
| Importable language packs | ✅ exists | `langpack/LanguagePackManager.kt` |
| Per-language prefs | ✅ generic | ISO-639-1 key scheme (`custom_words_<lang>`, etc.) |
| Language detection for `el` | ❌ missing | `data/LanguageDetector.kt` has no Greek patterns |
| Bundled/importable `el` dictionary | ❌ missing | no `el_enhanced.bin`, no Greek pack |
| Swipe model support for Greek | ❌ missing | tokenizer is Latin-only (see Phase 2) |

## Phase 1 — Suggestions + dictionary (bounded, ~6–12 hrs once a wordlist is chosen)

1. **Obtain a Greek wordlist with frequencies** (the blocker — see Decisions).
2. **Build the dictionary:**
   `python3 scripts/build_dictionary.py --lang el --input greek_words.txt --output el_enhanced.bin`
3. **Add Greek language detection** to `data/LanguageDetector.kt`
   (`initializeGreekPatterns()` — character-frequency + common-word block,
   mirroring the existing Spanish/German blocks). Greek's α–ω range is
   highly distinctive, so detection should be reliable.
4. **Optional contractions:** `contractions_el.json` (Greek uses few
   apostrophe contractions; can be empty/minimal).
5. **Distribute** (see Decisions): bundle `el_enhanced.bin` in
   `src/main/assets/dictionaries/` OR publish an importable
   `langpack-el.zip` via `LanguagePackManager`.

Greek typing + word suggestions work as soon as the dictionary + detection
land. No model or tokenizer work required for Phase 1.

## Phase 2 — Swipe via "pseudoenglish" transliteration (deferred, ~1–2 days)

The ONNX swipe model + tokenizer are Latin-QWERTY only. The reporter's
transliteration idea is sound *in principle*: Greek letters sit on the same
physical QWERTY key positions (`grek_qwerty.xml`), so the model sees the
same coordinate sequences; only the output characters differ. But the
`SwipeTokenizer` currently hard-maps lowercase Latin chars to token indices
with **no transliteration abstraction**.

Implementing Phase 2 requires:
- A per-language transliteration map (Greek↔Latin-key) loaded into
  `SwipeTokenizer` (e.g. `models/transliteration_el.json`).
- Pre-transliterate Greek layout chars → Latin tokens before model input,
  and map predicted Latin tokens → Greek output via the dictionary.
- **No neural retraining needed** — the model consumes coordinate sequences,
  not glyphs.

Phase 2 is a good stretch goal but not required for the user's core ask
(suggestions). Recommend shipping Phase 1 first.

## Decisions needed (cannot proceed without these)

1. **Wordlist source + license.** A dictionary is third-party data bundled
   into a GPL-3.0 app. Options:
   - Reporter's repo (dim-geo/greekdictionary) — license must be confirmed
     GPL-compatible.
   - HeliBoard / AnySoftKeyboard Greek dicts — Apache-2.0 / GPL respectively;
     must verify and attribute.
   - A permissively-licensed frequency list (e.g. derived from
     `wordfreq`, OpenSubtitles, or a CC-licensed corpus).
   This is a licensing call the maintainer must make; the agent will not
   fetch/bundle third-party data without explicit direction.
2. **Distribution:** bundle in the APK (~0.6–1 MB size increase, always
   available) vs. importable language pack (no APK bloat, allows community
   updates) vs. both.
3. **Scope:** Phase 1 only (suggestions) now, or commit to Phase 2 (swipe)
   as a follow-up?

## File touch list (Phase 1, when unblocked)

| File | Change |
|------|--------|
| `src/main/assets/dictionaries/el_enhanced.bin` | New (built via script) — if bundling |
| `src/main/kotlin/tribixbite/cleverkeys/data/LanguageDetector.kt` | `initializeGreekPatterns()` + registration |
| `src/main/assets/dictionaries/contractions_el.json` | Optional, minimal |
| `srcs/layouts/grek_qwerty.xml` | Already exists — verify it's selectable in layout settings |
| Tests | Language-detection unit test for Greek sample text |

## Related

- [Dictionary System](../../../specs/dictionary-and-language-system.md)
- [Multi-language](./multi-language-spec.md)
- [Language packs](./language-packs-spec.md)
- [Neural Prediction](../typing/neural-prediction-spec.md) (Phase 2 tokenizer)
