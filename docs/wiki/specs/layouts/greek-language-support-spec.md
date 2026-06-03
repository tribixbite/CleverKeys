---
title: Greek Language Support — Design Specification
description: Plan for Greek (el) word suggestions + dictionary via wordfreq, an "Import dictionary from file" feature, and (phase 2) swipe via transliteration. Issue #68.
status: planning
version: v1.4.0+
---

# Greek Language Support — Design Specification

> **Status (2026-06-03 update):** Phase 1 is effectively **already shipped** —
> the Greek pack `scripts/dictionaries/langpack-el.zip` was committed in
> `0fb56dc09` and is byte-identical to a fresh `wordfreq` build (verified),
> and the import → select → suggest chain already works with **zero app
> code** (the `el` display name is even present in `getLanguageDisplayName`).
> The real gap was **discoverability**: users didn't know the pack existed or
> how to import it. Fixed by rewriting `docs/wiki/layouts/language-packs.md`
> (correct path: Settings > 🌐 Multi-Language > Import Pack; lists prebuilt
> packs incl Greek; attribution) + adding the repo `NOTICE`.
>
> **Remaining:** (1) maintainer publishes the pack `.zip`s as downloadable
> release assets (currently only in-repo); (2) optional Phase 2 swipe
> (transliteration). Design for
> [issue #68](https://github.com/tribixbite/CleverKeys/issues/68).

## What the user asked for

Greek (`el`) word suggestions + dictionary; autocorrect optional. Plus a
broader ask: **offer importing the phone's system-installed language
dictionary** (a Russian/Greek phone has a built-in dict) as an import
option. Swipe via "pseudoenglish" transliteration was floated as a stretch.

## Decisions (resolved by research, 2026-06-02)

### Source + licensing — use `wordfreq`

The dictionary is generated from the **`wordfreq`** library via
`top_n_list('el', N)` / `get_wordlist.py`. Why:
- **Real corpus frequencies** (OpenSubtitles 2018, SUBTLEX, Wikipedia,
  Google Books) — not the *synthetic* Levenshtein-estimated frequencies in
  dim-geo/greekdictionary.
- **License is clean for a GPL-3.0 app:** wordfreq code is Apache-2.0; its
  data is CC-BY-SA-4.0, and **CC-BY-SA-4.0 is one-way compatible *into*
  GPLv3** (Creative Commons, 2015-10-08). We relicense the generated
  wordlist under GPL-3.0 when bundling. (The reverse is not permitted.)
- **Already wired** into `scripts/build_dictionary.py` (`--use-wordfreq`).
- **Generalizes** to Russian (`ru`) and dozens more — same one-command flow.

Rejected sources: **FrequencyWords** (data is CC-BY-SA-**3.0**, *not*
GPL-compatible — only 4.0 gained one-way compatibility); **HeliBoard
`main_el`** (184k, license unspecified). Acceptable alternates if ever
needed: **Hunspell el_GR** (tri-license; take the `GPL-2.0-or-later` arm,
no frequencies) and **dim-geo/greekdictionary** (GPL-3.0, but synthetic
freqs). wordfreq is preferred.

### Required NOTICE / attribution (when shipping)

Add to the app's credits / NOTICE:

> Greek (and other) word frequencies derived from **wordfreq** (Robyn Speer
> et al.), data under CC-BY-SA-4.0, incorporating OpenSubtitles 2018,
> SUBTLEX, Wikipedia, and Google Books Ngrams. Derived wordlist
> redistributed under GPL-3.0.

### "Import the phone's system dictionary" — NOT possible; ship file-import instead

Researched against Android platform docs: a third-party IME **cannot** read
the phone's system/Gboard/AOSP main language dictionary.
- Main `.dict` files are **app-private** (since API 28 an app can't
  world-share its data dir; since API 30 no app can read another's) and the
  format is proprietary.
- No content provider / service exports them; Gboard's are proprietary.
- `UserDictionary` exposes only the **user's manually-added** words (and
  CleverKeys *already* reads those — `OptimizedVocabulary.kt:1734-1768` —
  because the active IME is granted access; the permission has been IME-only
  since API 23). It is **not** the system language dictionary.
- The spell-checker API can only *validate words you supply*, never
  enumerate its dictionary.

**Therefore the real feature is "Import dictionary from a file"** via the
Storage Access Framework, reusing the existing
`langpack/LanguagePackManager.importLanguagePack(uri)` flow (same pattern as
GIF packs). The user imports a wordlist/freq file (or a `langpack-el.zip`);
we build the per-language trie on-device. This generalizes to any language
and is the honest, implementable version of the request.

## Existing infrastructure (verified against current source)

| Component | Status | Evidence |
|-----------|--------|----------|
| Greek keyboard layout | ✅ exists | `srcs/layouts/grek_qwerty.xml` (1 of 83) |
| wordfreq runs on this dev env | ✅ verified | installed on Termux; `top_n_list('el',…)` → 46,306 words |
| Wordlist → binary pipeline | ✅ exists + run | `get_wordlist.py` → `build_dictionary.py` → `el_enhanced.bin` |
| Unigrams (lang detection) | ✅ script | `generate_unigrams.py --lang el` |
| Contractions | ✅ script | `generate_binary_contractions.py` (Greek: minimal/empty) |
| Importable language pack | ✅ exists | `build_langpack.py` + `LanguagePackManager.kt:59-83` |
| UserDictionary user-words import | ✅ already works | `OptimizedVocabulary.kt:1734-1768` |
| Greek language detection | ❌ missing | no Greek patterns in `data/LanguageDetector.kt` |
| Bundled/packaged `el` dictionary | ❌ not yet | generated 1.82 MB artifact, not yet committed |
| Swipe model support for Greek | ❌ missing | tokenizer is Latin-only (Phase 2) |

## Generated artifact (proof-of-pipeline, 2026-06-02)

```
get_wordlist.py  --lang el --count 50000   → 46,306 words (wordfreq 'small')
build_dictionary.py --lang el --use-wordfreq → el_enhanced.bin = 1.82 MB
  46,306 canonical · 42,719 normalized · 84.3% accented · 3,587 collisions
```

**Bundle-size note:** 1.82 MB is larger than de/fr/it/pt (~620–650 KB) and
en/es (~1.26 MB) — Greek's 2-byte-per-char UTF-8 + heavy tonos accents
inflate it. Trimming to ~30k words would cut size materially. This is the
main go/no-go input for bundle-vs-pack.

## Open decision (maintainer go/no-go)

1. **Bundle vs pack vs both, and word count:** bundle 46k/1.82 MB in the
   APK, trim to ~30k, or ship pack-only (`langpack-el.zip`, zero APK bloat,
   importable)? "combo 1-3" pointed at bundle+pack+swipe.
2. **Scope now:** Phase 1 (suggestions) + the file-import feature, with
   Phase 2 (swipe) as a follow-up — or all together?

## Implementation plan

### Phase 1 — suggestions + dictionary (~½–1 day)
1. Generate + commit `src/main/assets/dictionaries/el_enhanced.bin` (or the
   pack). Add the NOTICE attribution.
2. `data/LanguageDetector.kt`: `initializeGreekPatterns()` — char-frequency
   + common-word block (Greek α–ω is highly distinctive → reliable). Wire
   `el_unigrams.txt` from `generate_unigrams.py`.
3. Register `el` so it's selectable; verify `grek_qwerty.xml` is offered.
4. Optional minimal `contractions_el.json`.

### "Import dictionary from file" feature (~½ day)
- Add an "Import dictionary" action (Settings → Languages) using
  `ACTION_OPEN_DOCUMENT` → `LanguagePackManager.importLanguagePack(uri)`
  (already parses `word[\tfreq]` / `unigrams.txt` in a zip).
- Document accepted formats; point users at wordfreq-derived or
  Hunspell/AOSP wordlists for languages we don't bundle.

### Phase 2 — swipe via transliteration (~1–2 days, deferred)
- Per-language Greek↔Latin-key map loaded into `SwipeTokenizer`
  (`models/transliteration_el.json`); pre-transliterate layout chars →
  Latin tokens before model input, map predictions back to Greek via the
  dictionary. **No neural retraining** — the model consumes coordinate
  sequences, not glyphs.

## Test plan

**Pure JVM**
- Language-detection unit test: feed Greek sample text (`"και το να του με"`)
  → detector returns `el`; feed English → not `el`.
- Dictionary-load test: `el_enhanced.bin` parses; word count > 40k; a known
  word (`και`) is present with non-zero frequency; accent-normalized lookup
  (`τησ` form) resolves.

**Instrumented (ew-cli, Pixel7 API34)**
- With `el` active + `grek_qwerty.xml`: typing a Greek prefix yields Greek
  suggestions from the bundled/imported dict.
- Import flow: SAF-pick a small `el` wordlist zip →
  `LanguagePackManager.importLanguagePack` → suggestions reflect imported
  words (mirror existing language-pack import test).
- Layout: `grek_qwerty.xml` selectable and renders Greek keys.

**Phase 2 (when built)**
- Swipe across the Greek layout for a known word → transliteration maps to
  the right Latin token sequence → correct Greek word predicted.

**Manual**
- Set a Greek wordlist, type a sentence, confirm suggestions; confirm
  language auto-detect switches correctly when typing Greek vs English.

## File touch list (Phase 1 + import)

| File | Change |
|------|--------|
| `src/main/assets/dictionaries/el_enhanced.bin` | New (1.82 MB @ 46k, or trimmed) — if bundling |
| `src/main/assets/unigrams/el_unigrams.txt` | New — language detection |
| `data/LanguageDetector.kt` | `initializeGreekPatterns()` + registration |
| `src/main/assets/dictionaries/contractions_el.json` | Optional, minimal |
| NOTICE / credits screen | wordfreq attribution text (above) |
| Settings (Languages) | "Import dictionary" SAF action → `LanguagePackManager` |
| Tests | detection + dict-load (pure) + import + layout (instrumented) |

## Related

- [Dictionary System](../../../specs/dictionary-and-language-system.md)
- [Multi-language](./multi-language-spec.md) · [Language packs](./language-packs-spec.md)
- [Neural Prediction](../typing/neural-prediction-spec.md) (Phase 2 tokenizer)
