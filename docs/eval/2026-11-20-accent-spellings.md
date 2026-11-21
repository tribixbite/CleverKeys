# Accent misspellings in the French, Spanish, Portuguese and German dictionaries (2026-11-20)

The Italian pass of 2026-11-19 (`docs/eval/2026-11-19-italian-accent-spellings.md`, maintainer:
"fix the Italian unaccented words") applied to the other bundled Latin lexicons with accented
letters. Found by the full instrumented run of 2026-11-20 (API 34–37, release-candidate code
`e505d449`): `CtcMultiLanguageInstrumentedTest` saw the French swipe slate offer `francais`
beside `français`, on all four API levels.

## What was wrong

`<lang>_enhanced.bin` is built by `scripts/build_wordlist.py` from wordfreq's ranked stream, and
wordfreq counts the web's unaccented typing as a token of its own. Where a spellchecker oracle or
the AOSP list accepted it, or the band rules kept it, the dictionary held the misspelling beside
the accented word (`francais` ranked above `français` in the French list). Effects:

- swipe: since the collision alternates (2026-11-18, `CtcSurfaceForms`) every canonical form of a
  surface is offered, so `francais`, `tambien`, `nao` appeared as second forms;
- tap: a typed misspelling was a dictionary word, so nothing corrected it;
- next word: a static model's vocabulary is the lexicon, so fr.cklm named 67 of these spellings
  and pt.cklm 22.

## Method

`scripts/accent_twin_audit.py` (new; the Italian procedure as a script): group the word list by
accent-stripped form; a member is a **candidate** when no hunspell oracle accepts it in lowercase
or Capitalised while one accepts another member of its group. The Capitalised probe keeps names
(`Sara`) and German nouns (the lexicon is lowercase, so `bär` stands for `Bär`) out of the
candidates. Every candidate was then reviewed by hand; the drops went to
`scripts/dictionaries/<lang>/<lang>_blocklist.txt`, each followed by `#` and its accepted
spelling(s).

Oracles (LibreOffice dictionaries unless noted; sha256 of the .dic, first 12):

| lang | oracle | sha256 |
|---|---|---|
| fr | Grammalecte "toutes variantes" v7.7 (Debian `hunspell-fr-comprehensive` 7.7-1), so the 1990 reform spellings (`apparaitre`, `aout`, `connaitre`) are valid; the installed `fr_FR` "classique" rejects them | 140c5296b514 |
| es | es_ES + es_MX + es_AR (es_AR so voseo `tenés`/`creés` count) | 6975dddec3d5, 44ce35af2209, 4fe3e193425b |
| pt | pt_BR + pt_PT (the pack is the union of both) | a38bfb26b68e, e29ba2d7aa8a |
| de | de_DE_frami | 4ca3c958b0e5 |

Review rules, the Italian ones carried over:

- **Drop** a word typed without its accent (`francais`, `tambien`, `nao`), a wrong-accent form
  (`trés`, `déja`, `fué`, `dió`, `jóven`, `exámen`), a native word that happens to be an English
  word too when it is the native word's misspelling in this language's text (fr `cafe`, `hotel`,
  `video`, `education`; es `region`, `version`, `television`), and a place name the language
  writes with accents (fr `montreal`, `quebec`; es `mexico`, `peru`, `bogota`; pt `brasilia`).
- **Keep** a real word that collides (`la`/`là`, `esta`/`está`/`ésta`, `schon`/`schön`, `pais`/`país`;
  the oracle accepts these, so they are never candidates), words the language's text uses as they
  are (fr `the`, `email`, `edit`, `real`, `beta`, `metal`; es `closet`, `comic`, `poster`,
  `master`, `super`, `trailer`; pt `karate`, `premiere`, `taxi`), Latin (`idem`, `curriculum`),
  abbreviations (`ref`, `dec`, `pag`, `cia`, `sec`, `sos`), compound fragments (`pêle` of
  pêle-mêle, `aigues` of Aigues-Mortes, `aït`, `aïn`), informal forms (fr `indé`; es `na`, `uni`,
  `seño`), personal names and surnames that exist unaccented in other languages or are spelled
  their owner's way (`adele`, `felix`, `noel`, `garcia`, `lopez`, `martin`, `antonio`, `fabio`,
  `luiza`, `junior`, `kévin`, `nuñez`), brands and public figures (`decathlon`, `bose`, `blatter`,
  `höcke`), voseo (`podés`), and spellings that were standard before a reform: French 1990 (the
  oracle already accepts them), Spanish pre-2010 `guión`, the Portuguese pre-2009 orthography
  (`idéia`, `freqüente`, `vôo`, `pêlo`, `apóia`). German keeps its colloquial forms (`hä`, `wär`,
  `schäm`, `kämpf`) and the Swiss `mässig`.

Dropping a word frees a slot under the per-language size cap, so the builder admits the next words
of the band (French: 313 ordinary words at zipf ~2.78 such as `abaissant`, `blogosphère`). The audit
was re-run on every rebuilt list: the only candidates left are the reviewed keeps.

## Result

| lang | candidates | dropped | kept on review | shape before → after (surfaces / collisions) |
|---|---:|---:|---:|---|
| fr | 384 | 313 | 71 | 37,958 / 2,042 → 38,255 / 1,745 |
| es | 778 | 668 | 110 | 47,955 / 2,045 → 48,605 / 1,395 |
| pt | 347 | 160 | 187 | (not pinned) |
| de | 64 | 4 (`fur`, `munchen`, `uber`, `ol`) | 60 | 39,594 / 406 → 39,598 / 402 |

German `wahrend` is a real word (present participle of *wahren*), accepted by the oracle and kept;
a swipe of w-a-h-r-e-n-d now offers it after `während`, as the alternates intend.

Reproduction first: with the current inputs `build_wordlist.py` rebuilt es, pt and de byte-for-byte.
French did not: the elision tables gained `d'ai`, `d'âne`, `d'où` on 2026-11-07/09, after the July
build, and the classifier keeps contraction keys, so a rebuild adds `dai`, `dane`, `dou` and drops
three words at the cap (`sanctionnées`, `santerre`, `sapeur`). That six-word drift is part of the
shipped French list. The AOSP snapshots the builder needs (removed from the tree for F-Droid) were
re-fetched and matched their PROVENANCE.md sha256 exactly (fr maps `not_a_word=true` to `nonword`;
pt is pt_BR + pt_PT with BR flags winning).

Derived files: the fr/es/pt/de elision tables regenerate byte-identical;
`contraction_collisions_fr.json` and `_it.json` change by a few language tags (French elision keys
now collide with different es/pt words, and `deco` no longer lists fr). The four language packs
(`scripts/dictionaries/langpack-<lang>.zip`) were rebuilt with `build_all_languages.py --lang`;
each `dictionary.bin` is byte-identical to the bundled dictionary, and the stale
`prefix_boost.bin` (no consumer since ADR-011) is gone, as in the Italian pack.

### Next-word models (gates pre-registered in `scripts/build_static_lm.py` before each build)

de.cklm names none of the four German drops: not rebuilt. es ships no model.

| gate | fr | pt |
|---|---|---|
| shipped reproduced first | 4775300e inputs → sha256 5ced1c6f… | 807c56bd inputs → sha256 1ff51269… |
| G1 names no dropped spelling; keeps every twin the shipped model named | 67 → 0; 269 twins kept | 22 → 0; 125 twins kept |
| G2 size ≤ 1.10 × shipped | 458,180 → 470,952 B | 351,738 → 350,521 B |
| G3 OOD-test next-word ≥ shipped − 0.10 | 11.73 / 19.55 → 11.85 / 19.75 | 9.69 / 16.96 → 9.69 / 16.95 |
| G4 StaticLmTapEvalTest end to end, every lm_static cell ≥ −0.10 pt | worst −0.01, best +0.41 | worst −0.01, best +1.05 |
| G4a same cells, new vs shipped model under the new lexicon | worst +0.02 | worst −0.01 |
| G5 other models byte-identical | yes | yes |

The French model also gains 485 accented elisions (`d'accès`, `c'était`) the elision tables added
on 2026-11-07/09, after the 2026-09-29 model was built (the Italian 2026-11-18 rebuild did the
same). The Portuguese gain (+1.05 pt in-domain prefix-2 top-1) comes from the lexicon: a typed
prefix no longer spends a slot on `nao` beside `não`.

## The instrumented test

`CtcMultiLanguageInstrumentedTest.assertSlateIsCanonical` asserted that no slate word is a bare
surface with an accented canonical form. Since the 2026-11-18 alternates that is wrong for real
pairs (`schon` beside `schön`, German `wahrend` beside `während`), so it now allows a bare word that
is itself a canonical form of its surface (`Projected.alternates`). Whether a bare form is a real
word or a misspelling is the dictionary's question, pinned per language by
`AccentSpellingDriftTest` (fr/es/pt/de) and `ItalianAccentSpellingDriftTest`. Its lexicon shapes
were updated with `CtcCkdtLexiconTest`'s.

## Measured, not fixed: Swedish

The same audit on `sv_words.txt` with LibreOffice sv_SE (sha256 bd44b19cd8c0) finds 89 candidates.
Some are plain ASCII typing (`ocksa`, `maste`, `aven`, `tva`, `niva`, `goteborg`, `malmo`,
`orebro`), many are English words (`are`, `host`, `lost`, `save`, `stand`, `over`), and several are
dialect or colloquial forms (`asså`, `åsså`, `sånna`, `jå`, `mä`, `pöjk`) that need a Swedish
speaker's judgement; sv.cklm would need its own gated rebuild. Left for a Swedish pass.

## Found, not fixed

- One-letter words are still absent from fr/es/pt/sv (`à`, `a`, `y`, `é`); see the Italian note.
- The bundled lexicons are lowercase, so a name kept here (`garcia`) and its accented twin
  (`garcía`) both stay; autocorrect keeps the typed capital either way.
