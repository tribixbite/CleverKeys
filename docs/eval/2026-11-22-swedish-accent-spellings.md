# Accent misspellings in the Swedish dictionary (2026-11-22)

The Swedish pass that `docs/eval/2026-11-20-accent-spellings.md` measured and left open
(maintainer, 2026-11-22: "do the swedish pass too"). Same method, oracle and review rules; this
note records what is specific to Swedish.

## Candidates

`scripts/accent_twin_audit.py` on `scripts/dictionaries/sv/sv_words.txt` with LibreOffice sv_SE
(`sv_SE/dictionaries/sv_SE.dic`, sha256 bd44b19cd8c0…): 89 candidates, words that hunspell rejects
in lowercase and Capitalised while it accepts another member of their accent group.

## How each candidate was judged

Many Swedish candidates are English words too (`over`, `host`, `lost`, `at`), so "is it an English
word" decides nothing by itself. For each candidate, wordfreq gives two estimates of where its
Swedish frequency comes from:

- **ASCII typing of its twin**: the twin's Swedish frequency lowered by the typing baseline. The
  baseline is the median zipf gap of plain misspellings (`ocksa`/`också`, `maste`/`måste`,
  `tva`/`två`, `pa`/`på`, `aven`/`även`, …): **−2.55**, i.e. about 0.3% of a word's uses are typed
  without its å/ä/ö.
- **English use**: its English frequency lowered by how much English appears in Swedish text: the
  median gap is −2.33 for English function words (`the`, `and`, `with`) and −1.26 for content
  words (`weekend`, `party`, `game`).

Rules (the 2026-11-20 rules, made measurable):

- **Drop** when ASCII typing of the twin explains at least a tenth of the form's Swedish frequency,
  English word or not (`over` 35%, `gang` 42%, `lander` 52%, `ol` 13%, `nat` 21%), as fr `hotel`
  and de `fur`/`uber` were; when it is a Swedish place name written with å/ä/ö (`goteborg`,
  `malmo`, `orebro`, `lulea`, `umea`: English and URL use keeps their share low, but Swedish text
  writes the accent); when it is an é loanword typed without its accent (`entre`, `kvalite`,
  `kvaliten`); and when it carries a wrong mark (`muséet`, `ideér`, `löfvén`, `té`, and `endå` for
  `ändå`, whose accent-group twin `enda` is a different word).
- **Keep** an English word whose Swedish frequency is mostly English use and hardly ever the twin
  typed in ASCII (`are` 98% English, `lost`, `save`, `stand`, `hard`, `popular`, `host`, `do`,
  `on`, `at`, `bat`, `rat`, `pal`, `vat`, `rage`, `moss`, `rod`, `ago`); abbreviations and agency
  names (`Brå`, the crime-prevention council; `ÖB`; `NAS`; `aka`; `mar` for mars; `SÖ` as `sö`;
  `ak`); names that exist unaccented or are spelled their owner's way (`francois`, `jose`, `rene`,
  `kruger`, `ramón`, `pokemon`, `alta`, `tang`, `slade`, `moss`); loans and Latin Swedish text uses
  (`kata`, `mater`); colloquial and dialect forms (`sånna`, `äre` = *är det*, `åsså` = *och så*,
  `assa`, `ba` = *bara*, `dä`, `hä`, `jå`, `mä`, `sä`, `gö` = *göra*, `pöjk` = *pojke*, `ör`,
  `na`, `ma`, `ra`); the common supine `hållt` (standard `hållit`; it is not `hällt` misspelled);
  the fragment `samhäll`; the interjection `ahhh`; and `nad` (NAD, the audio brand and the
  coenzyme; the twin `nåd` explains 2% of it).

Sources for the colloquial forms that were not obvious: `äre` = *är det* in SMS and chat language
(SVT, "Det nya språket"; Svärdsudd 2016, DiVA, a study of youth chat), `pöjk` for *pojke* (Svenskt
dialektlexikon; slang dictionaries), `gö` for *göra* (ISOF *Svenska landsmål* 1937: "Ja ska gö inn").

Result: **33 dropped, 56 kept**. The drops are in `scripts/dictionaries/sv/sv_blocklist.txt`, each
with its accepted spelling. `at` stays even though Swedish text often types it for *att*: that is a
different typo, not an accent spelling, and English use is 40% of it.

## Rebuild

- Reproduction first: with HEAD inputs and the AOSP snapshot re-fetched (`sv_wordlist.combined.gz`,
  196,739 rows, sha256 b3ce19a0e700, as recorded in `scripts/data/PROVENANCE.md`),
  `build_wordlist.py --lang sv --write` rebuilt `sv_words.txt` and `sv_enhanced.bin` byte-for-byte.
- With the blocklist: 33 out, 33 next-in-band words in (`hembygd`, `himlar`, `huvudsta` — a Solna
  district — `jackets`, …). The audit on the rebuilt list finds exactly the 56 reviewed keeps.
- `langpack-sv.zip` (`build_all_languages.py --lang sv`): `dictionary.bin` byte-identical to the
  bundled bin; the stale `prefix_boost.bin` (no consumer since ADR-011) is gone, as in the it, fr
  and pt packs. `unigrams.txt` is unchanged.
- `contraction_collisions_fr.json`: the French elision key `da` (*d'a*) no longer lists sv. The
  de, en and it tables are byte-identical. `contractions_sv.json` is empty (Swedish has no elision
  table).

### Next-word model (gate pre-registered in `scripts/build_static_lm.py` before the build)

The shipped sv.cklm named 4 of the drops: `da`, `lander`, `niva`, `over`.

| gate | result |
|---|---|
| shipped reproduced first | HEAD inputs → sha256 6c42ee51…, 443,229 B, OOD-test next-word 10.32 / 17.42 |
| G1 names no dropped spelling; keeps every twin the shipped model named | 4 → 0; every twin kept |
| G2 size ≤ 1.10 × shipped | 443,229 → 443,229 B |
| G3 OOD-test next-word ≥ 10.22 / 17.32 | 10.32 / 17.42 |
| G4 StaticLmTapEvalTest end to end, every lm_static cell ≥ −0.10 pt | worst −0.01 (OOD prefix-1 top-3), best +0.36 (OOD prefix-2 top-1) |
| G4a same cells, new vs shipped model under the new lexicon | every cell +0.00 |
| G5 other models byte-identical | yes |

The vocabulary went from 16,669 to 16,668 words. Besides the four drops, `leonardo` and `vinci`
left the model: their only bigrams ran through `da`, now outside the vocabulary. Both stay in the
dictionary. The gains come from the lexicon (G4a is flat): a typed prefix no longer spends a
suggestion slot on `pa` beside `på`.

## Consequences

- A swipe or a typed `ocksa`, `maste`, `over` offers only `också`, `måste`, `över`; a typed one is
  corrected like any other non-word.
- `da` is gone: "Leonardo da Vinci" and Danish `da` now autocorrect towards `då` like any other
  non-word, as French and Italian users already get for words outside their dictionary.

## Open

- The keep/drop calls were made by a non-native reviewer, with the sources above. A Swedish
  speaker should look at the colloquial keeps (`assa`, `sä`, `ör`, `na`, `ma`, `ra`) and at `at`
  (todo item 37).
- The one-letter words (`å`, `ö`, `i`) are still outside the bundled Swedish list, as for the other
  languages (2026-11-20 note, "Found, not fixed").
