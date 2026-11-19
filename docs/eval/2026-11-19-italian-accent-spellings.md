# Italian accent misspellings in the dictionary, the elision tables and it.cklm (2026-11-19)

Maintainer: "fix the Italian unaccented words". Follows `83c81ec4` (it.cklm rebuilt so Italian
offers `l'attività`), whose notes left this open: the Italian lexicon held unaccented web
spellings (`perche`, `piu`, `citta`) beside their accented forms.

## What was wrong

`it_enhanced.bin` is built by `scripts/build_wordlist.py --lang it` from wordfreq's ranked stream.
wordfreq counts the web's unaccented typing as a token of its own, and pyspellchecker `it` (the
Tier B oracle, itself frequency-derived) and the AOSP list accept several of them, so the
classifier kept `perche` (zipf 4.95) beside `perché` (6.18), `piu` beside `più`, and wrong-accent
forms such as `perchè` and `pò`. Effects on the device:

- swipe: since the collision-alternates change (CHANGELOG 2.0.0) every canonical form of a
  surface is offered, so a swipe of p-e-r-c-h-e offered `perche` and `perchè` beside `perché`;
- tap: a typed `perche` was a dictionary word, so nothing corrected it, and the accent index
  offered it beside `perché`;
- next word: it.cklm's vocabulary is the lexicon, so the model named 29 of these spellings.

The ASK-derived elision tables also offered `qual'è` (PAIRED beside `quale`): the standard spelling
is `qual è` (truncation, no apostrophe), and the same holds for `qual'era`.

## Method

Inventory: every word of the shipped `it_enhanced.bin` whose accent-stripped form equals another
lexicon word — 337 pairs. Evidence per pair: CKDT rank and wordfreq zipf of both forms; hunspell
it_IT (LibreOffice; the dictionary `extract_apostrophe_words.py` already uses, sha256 `bae1e350…`
for the .dic) on the lowercase forms and on the Capitalised bare form; the AOSP it snapshot;
case counts in the Leipzig it-2019 web corpus (mid-sentence `Sara` vs `sara`).

Drop rule: hunspell rejects the bare lowercase form, accepts the accented twin, and the bare form is
not a name hunspell accepts Capitalised. Wrong-accent forms: dropped when they are standard
misspellings (`perchè` for `perché`, `pò` for `po'`, accented monosyllables `quì`/`stà`/`fà`/`sò`/`và`)
and they are not the key of an elision entry.

Judgement calls:

- `sara` and `fara` stay. They fail hunspell lowercase, but `Sara` (a common first name) and `Fara`
  (a place) are hunspell proper nouns, and the corpus shows the name use is as common as the
  misspelling (Sara 69 mid-sentence vs sara 85; Fara 9 vs fara 8). The lexicon has no case (no
  shipped CKDT dictionary holds a capital letter), and autocorrect keeps the typed capital, so
  dropping `sara` would turn "Sara" into "Sarà". A capitalised entry is not possible in this
  pipeline. `gesu` goes: the name is spelled `Gesù`.
- Valid but rare bare words stay (`pero` pear tree, `cosi` plural of `coso`, `eta` Greek letter,
  `meta`, `unita`, `necessita`, every `-o/-ò` and `-i/-ì` verb pair). Swipe already shows the more
  frequent accented form first; dropping a real word is the worse error.
- `cè`, `sè`, `nè`, `chè` stay although they are misspellings as words: they are the keys of the
  `c'è`/`s'è`/`n'è`/`ch'è` elision entries (`BundledContractionDataTest` pins `cè`/`nè`), and
  removing them from the lexicon would orphan those entries.
- The acute í/ú convention (`piú`, `cosí`, `sí`, `lí`) stays: older but legitimate typography.
  `dò` (accepted variant of `do`), `thè`, `vabbè`, `dèi`, `ché`, `subìto`, `princìpi` stay.
- Foreign names typed without their accents (`garcia`, `jose`, `pokemon`, `elite`, `premiere`) are
  not Italian accent errors and stay.

No pipeline step merges one spelling's frequency into another (the typo and foreign drops do not
either), so the accented forms keep their own wordfreq rank; dropping is what the pipeline does.

Elisions: `qual'è`, `qual'era`, the masculine `un'altrettanto` and `un'appassionato`, and the
typo bases `un'importate`/`un'insegnate` (`un'importante`/`un'insegnante` are mapped) are dropped.
`un'ormai` stays (correct before a feminine noun: "un'ormai celebre attrice"). `po'` is correct but
no table offers it: the tap key `po` shows `po`. Not added here (a new mapping, not a fix).

## Changes

| file | change | how |
|---|---|---|
| `scripts/dictionaries/it/it_blocklist.txt` | 78 force-drops, each with its standard spelling | curated |
| `scripts/build_wordlist.py` | curation lists accept accented Latin letters; a malformed allow/blocklist line is a hard error | the old `[a-z]` pattern IGNORED `pò`/`perchè` without a word; every existing list parses identically |
| `it_words.txt`, `it_enhanced.bin` | -78 words; the 40,000 cap admits the next 78 survivors (all zipf 2.87, e.g. `neurone`, `oculista`, `pescheria`); no other word's rank changes | `build_wordlist.py --lang it --write` (HEAD inputs first reproduced the shipped bin byte-for-byte) |
| `scripts/extract_apostrophe_words.py` | `MISSPELLED_DISPLAY["it"]`, applied to the extraction and the shipped files read back | regeneration: -5 REPLACE, -1 PAIRED, nothing else (HEAD inputs reproduced both files exactly) |
| `contraction_collisions_fr.json` | `dara` no longer lists `it`; `luni` (new it word) lists `it` | `build_contraction_collisions.py` |
| `lm/it.cklm`, `lm/it.json` | rebuilt | `build_static_lm.py --lang it --offline`, gate below |

The Italian langpack zip (`scripts/dictionaries/langpack-it.zip`) still carries the old dictionary;
Italian is bundled, so the pack matters only to someone who imports it. Not rebuilt here.

## it.cklm gate (pre-registered in the Italian `LangConfig` before the candidate was built)

The 2026-11-18 inputs reproduced the shipped it.cklm byte-for-byte (sha256 `0b5528a4…`) first.

| gate | bar | result |
|---|---|---|
| G1 | names none of the 78 dropped spellings or 6 dropped elisions; keeps every accented twin it named | shipped named 29 + `qual'è`; candidate 0; all 64 named twins kept |
| G2 | size <= 1.10 x 476,958 B and the drift budget | 476,420 B |
| G3 | builder OOD-test next-word top1/top3 >= 9.88 / 16.94 | 9.98 / 17.03 |
| G4 | StaticLmTapEvalTest it end to end, every lm_static cell >= shipped - 0.10 pt | worst +0.00, best +0.14 |
| G4a | same cells, shipped vs new model both under the NEW lexicon | worst -0.01 |
| G5 | other languages' models byte-identical | en de fr pt sv unchanged |
| G6 | drift/contraction tests green | see the commit |

Tap eval cells (`-PgeoFull=true STATIC_LM_EVAL_LANGS=it`, top-1 / top-3, %; before = shipped
lexicon + shipped model, after = new lexicon + new model):

| population, cell | lm_static before | lm_static after | none before | none after |
|---|---|---|---|---|
| OOD next-word | 9.86 / 16.83 | 9.86 / 16.83 | 4.80 / 9.35 | 4.80 / 9.35 |
| OOD prefix-1 | 24.04 / 40.27 | 24.04 / 40.27 | 19.21 / 32.43 | 19.21 / 32.43 |
| OOD prefix-2 | 17.61 / 38.88 | 17.61 / 38.88 | 0.24 / 31.36 | 0.24 / 31.36 |
| OOD prefix-3 | 28.23 / 48.99 | 28.28 / 49.00 | 18.99 / 44.72 | 19.03 / 44.72 |
| in-domain next-word | 10.08 / 17.55 | 10.08 / 17.56 | 4.02 / 8.10 | 4.02 / 8.10 |
| in-domain prefix-1 | 26.32 / 42.18 | 26.33 / 42.19 | 19.02 / 32.35 | 19.02 / 32.36 |
| in-domain prefix-2 | 20.75 / 41.41 | 20.75 / 41.42 | 0.16 / 32.91 | 0.16 / 32.92 |
| in-domain prefix-3 | 30.28 / 51.64 | 30.42 / 51.69 | 19.84 / 47.05 | 20.00 / 47.10 |

The gains come from the lexicon (the `none` arm moves the same way): a prefix no longer spends a
slot on `perche` beside `perché`. Position counts move by 1 (OOD) and 11 (in-domain): the
misspelled targets are no longer lexicon words. These populations are edited text, so they hold
few misspellings (in-domain held-out: 143 tokens of the 78 spellings in 989,005, 64 of them
`perchè`; OOD test: 1, `avro`).

No Italian human swipe traces exist on the dev box (the FUTO corpora are azerty, qwertz, german,
spanish, dvorak), so there is no Italian swipe replay. The swipe change is pinned by
`ItalianAccentSpellingDriftTest` (`perche` projects to `perché` alone).

## Follow-up (same day): elision audit, one-letter words, the Italian pack

### Elision audit

Every Italian PAIRED value (161) and the REPLACE value of every Italian collision key (105) was
checked with hunspell it_IT on the elided word, then by hand (proper nouns and acronyms are normal
after `l'`/`d'`: `l'Aida`, `d'Este`, `l'Eni`, `l'UCI`). Dropped through `MISSPELLED_DISPLAY`:

| key | form | why |
|---|---|---|
| `ai` | `a'i` | not an Italian form |
| `finora` | `fin'ora` | `fino` truncates: `finora` / `fin ora`, no apostrophe (as `qual è`) |
| `del` | `d'el` | `el` is Spanish |
| `lel` | `l'el` | same |
| `allo` | `all'o` | no reading |
| `dici` | `d'ici` | French |
| `lune` | `l'une` | French |
| `dune` | `d'une` | French |
| `dells` | `dell's` | English possessive |
| `dalles` | `dall'es` | no reading |

Kept on purpose: `tutt'ora` (Treccani: the less common variant of `tuttora`), `ventiquattr'ore`,
`mezz'ora`, `bell'e`, `ch'io`, `m'è`/`t'è`/`v'è` (literary). Reported, not changed: `l'è`
(Tuscan for `è`, offered beside every `le`), `l'uni`, `l'ari`, `l'udi`, `l'air`, `d'or`, `d'eu`,
`l'ice` (foreign or acronym readings; plausible as proper nouns in Italian text). Result:
22,238 REPLACE (-3), 154 PAIRED (-7), 102 collisions (-3); `lel` also left the lexicon (it was
kept only as that contraction key), so `contraction_collisions_fr.json` drops `it` from `lel`.

French (report only; no change): of 471 PAIRED values, 37 elided words fail hunspell fr_FR.
Most are proper nouns (`d'Alton`, `d'Angers`, `d'Ivan`, `l'Isle`); with no French reading:
`l'aps`, `l'ard`, `d'ard`, `l'ance`, `l'ena`, `l'ens`, `l'is`, `l'ob`, `l'ol`, `l'ong`, `l'oua`,
`l'uc`, `l'uit`, `d'ur`, `d'ise`, `d'oum`. Of 283 collision keys, the REPLACE values with no French
reading include `d'dc`, `d'el`, `l'el`, `l'ess`, `l'ined`, `qu'ali`, `l'east`, `d'old`. None is a
misspelling of a French word, so they were left for a French-specific pass.

### One-letter Italian words

The Italian dictionary held no one-letter word: `build_wordlist.py` keeps a one-letter word only
when the shipped dictionary already had it, a carry-over written for English (whose list always
had `a`/`i`). No bundled non-English dictionary has one (fr, de, es, pt, sv, nor the nl/ru/tr
packs). What an Italian user saw (mock-tier probe through the real `WordPredictor` and the shipped
`it_enhanced.bin`):

- typed `è` (the Italian layout has the key): empty suggestion bar;
- typed `e`: `essere, era, ed, erano, ecco` (no `e`); typed `a`: `al, anche, alla, ad, anni`;
- autocorrect never touched them (its minimum length is 2), so nothing was rewritten;
- the next-word model could neither predict them nor use them as the previous word.

Attempted fix (NOT shipped): `it_allowlist.txt` force-keeps `e`, `è`, `a`, `i`, `o` (they rank 5,
18, 14, 25, 42; the size cap then cuts 4 tail words). With that, the same probe shows the typed
letter first (`e` → `e, essere, era`; `è` → `è`), and autocorrect still leaves them alone.

it.cklm: with the five words as ordinary vocabulary no Tatoeba weight fits the 512 KiB cap
(526,097 B at weight 0; they join nearly every row's top 20). As CONTEXT-ONLY rows (the mechanism
es was given; a builder change so a lexicon word can be one) the model is 476,740 B and OOD-test
next-word 10.28 / 17.78 (shipped 9.98 / 17.03). The pre-registered gate then FAILED G4, end to end:

| cell (lm_static, %) | shipped pair | candidate A | Δ | A model vs shipped model, both on A's lexicon |
|---|---|---|---|---|
| OOD next-word top-1 / top-3 | 9.86 / 16.83 | 9.52 / 16.42 | -0.34 / -0.41 | +0.28 / +0.66 |
| OOD prefix-1 | 24.04 / 40.27 | 23.15 / 40.46 | -0.89 / +0.19 | +0.21 / +0.58 |
| OOD prefix-2 | 17.61 / 38.88 | 18.39 / 39.36 | +0.78 / +0.48 | +0.78 / +0.48 |
| OOD prefix-3 | 28.28 / 49.00 | 28.90 / 49.16 | +0.62 / +0.16 | +0.62 / +0.16 |
| in-domain next-word | 10.08 / 17.56 | 9.79 / 17.11 | -0.29 / -0.45 | +0.42 / +0.77 |
| in-domain prefix-1 | 26.33 / 42.19 | 25.96 / 42.45 | -0.37 / +0.26 | +0.73 / +0.75 |
| in-domain prefix-2 | 20.75 / 41.42 | 22.09 / 41.92 | +1.34 / +0.50 | +1.34 / +0.50 |
| in-domain prefix-3 | 30.42 / 51.69 | 31.19 / 51.79 | +0.77 / +0.10 | +0.77 / +0.10 |

The model is better on every cell; the losses come from the lexicon. After one typed letter the bare
letter takes slot 1 (`none` prefix-1 top-1 falls 3.35 pt), and the next-word population grows by 902
OOD / 2,926 in-domain positions whose targets (`e`, `è`, ...) a context-only word can never be, so
those rates fall although the hit COUNTS rise (OOD next-word top-3: 2,233 → 2,327 hits). Under the
gate as written this is a fail, so it did not ship. The trade-off (the letter itself in slot 1, the
bar no longer empty for `è`) is for the maintainer. Parked as
`docs/eval/2026-11-19-italian-one-letter-words.patch` (`git apply` it): the 5 allowlist lines, the
builder's `vocab -= context_only`, `context_only=("a", "e", "i", "o", "è")` for it, and the fail-first
mock test `ItalianShortWordTest` (add it to `mockTestClasses`). Then regenerate as in "Changes",
including the contraction generator, until a pass changes nothing, and rebuild it.cklm.

Shipped instead (candidate B, gate pre-registered before it was built): the elision drops above at
the generators' fixed point. The lexicon differs from the 6559e110 one by two rare words (`lel` out:
it was kept only as the key of `l'el`; `pubblicarono` in under the size cap), and the rebuilt it.cklm
is byte-identical to the shipped model (sha256 `3f5a5c4c…`; it never named the 10 dropped forms), so
G1-G5 hold trivially; tap eval below.

Tap eval (`-PgeoFull=true`, it), candidate B vs the 6559e110 pair: every lm_static cell identical
(largest |Δ| 0.00 pt); one in-domain next-word position fewer (39,165 → 39,164).

### The Italian pack

`scripts/dictionaries/langpack-it.zip` rebuilt with `build_all_languages.py --lang it` (the
project's pack pipeline: classifier, unigrams, pack). Its `dictionary.bin` is byte-identical to the
bundled `it_enhanced.bin`; `contractions.json` is the current `contractions_it.json` (the old pack
carried a 761 KB table from before the re-keying); `prefix_boost.bin` is gone (no consumer since
ADR-011, and the orchestrator no longer builds one).

Running the full pipeline exposed a loop: the classifier reads the contraction keys as evidence and
the contraction generator reads the lexicon. Once `l'el` was dropped, the next classifier pass dropped
`lel` (it had been rescued only as that key) and the size cap admitted `pubblicarono`; a further pass
of all three generators changed nothing, so the shipped files are that fixed point.

## Found, not fixed

- No elision table offers `po'`; the key `po` shows `po`.
- The same one-letter gap in fr/es/pt/sv (above).

## Full table

Rank = CKDT rank byte in the shipped `it_enhanced.bin` (0 = most common); zipf = wordfreq `it`.
337 accent pairs; 79 involve a dropped word, 258 are kept.

### Dropped: bare final vowel (59)

| dropped | rank | zipf | standard | rank | zipf |
|---|---:|---:|---|---:|---:|
| `andra` | 170 | 3.18 | `andrà` | 114 | 4.64 |
| `attivita` | 144 | 3.86 | `attività` | 86 | 5.36 |
| `autorita` | 167 | 3.26 | `autorità` | 103 | 4.92 |
| `avra` | 162 | 3.38 | `avrà` | 101 | 4.97 |
| `avro` | 173 | 3.10 | `avrò` | 124 | 4.38 |
| `bensi` | 179 | 2.94 | `bensì` | 126 | 4.31 |
| `caffe` | 148 | 3.75 | `caffè` | 106 | 4.83 |
| `chissa` | 157 | 3.53 | `chissà` | 114 | 4.64 |
| `cio` | 133 | 4.14 | `ciò` | 73 | 5.70 |
| `cioe` | 144 | 3.86 | `cioè` | 89 | 5.28 |
| `citta` | 132 | 4.16 | `città` | 70 | 5.77 |
| `comunita` | 162 | 3.40 | `comunità` | 97 | 5.08 |
| `curiosita` | 171 | 3.15 | `curiosità` | 118 | 4.52 |
| `dara` | 174 | 3.08 | `darà` | 128 | 4.27 |
| `difficolta` | 160 | 3.44 | `difficoltà` | 99 | 5.01 |
| `dignita` | 170 | 3.18 | `dignità` | 116 | 4.58 |
| `dovra` | 174 | 3.07 | `dovrà` | 111 | 4.72 |
| `facolta` | 172 | 3.13 | `facoltà` | 114 | 4.64 |
| `finche` | 156 | 3.55 | `finché` | 106 | 4.85 |
| `gesu` | 164 | 3.34 | `gesù` | 106 | 4.85 |
| `gia` | 116 | 4.59 | `già` | 63 | 5.95 |
| `giovedi` | 149 | 3.72 | `giovedì` | 119 | 4.51 |
| `giu` | 135 | 4.08 | `giù` | 107 | 4.80 |
| `identita` | 172 | 3.12 | `identità` | 111 | 4.71 |
| `liberta` | 149 | 3.73 | `libertà` | 93 | 5.16 |
| `lunedi` | 144 | 3.85 | `lunedì` | 112 | 4.67 |
| `martedi` | 151 | 3.67 | `martedì` | 121 | 4.44 |
| `mercoledi` | 155 | 3.56 | `mercoledì` | 122 | 4.42 |
| `modalita` | 168 | 3.24 | `modalità` | 110 | 4.73 |
| `nonche` | 174 | 3.08 | `nonché` | 110 | 4.73 |
| `novita` | 165 | 3.31 | `novità` | 112 | 4.67 |
| `opportunita` | 170 | 3.19 | `opportunità` | 109 | 4.76 |
| `percio` | 175 | 3.04 | `perciò` | 108 | 4.78 |
| `perche` | 102 | 4.95 | `perché` | 54 | 6.18 |
| `personalita` | 180 | 2.91 | `personalità` | 115 | 4.60 |
| `piu` | 91 | 5.22 | `più` | 37 | 6.63 |
| `poiche` | 168 | 3.22 | `poiché` | 102 | 4.95 |
| `possibilita` | 148 | 3.75 | `possibilità` | 90 | 5.25 |
| `potra` | 167 | 3.27 | `potrà` | 104 | 4.89 |
| `potro` | 177 | 3.00 | `potrò` | 135 | 4.10 |
| `proprieta` | 159 | 3.46 | `proprietà` | 98 | 5.04 |
| `pubblicita` | 160 | 3.43 | `pubblicità` | 108 | 4.79 |
| `puo` | 115 | 4.61 | `può` | 61 | 6.01 |
| `qualita` | 148 | 3.74 | `qualità` | 93 | 5.18 |
| `quantita` | 167 | 3.26 | `quantità` | 105 | 4.86 |
| `realta` | 141 | 3.92 | `realtà` | 84 | 5.40 |
| `responsabilita` | 157 | 3.51 | `responsabilità` | 102 | 4.95 |
| `sanita` | 166 | 3.28 | `sanità` | 116 | 4.59 |
| `saro` | 161 | 3.41 | `sarò` | 113 | 4.65 |
| `schiavitu` | 180 | 2.92 | `schiavitù` | 137 | 4.04 |
| `societa` | 140 | 3.97 | `società` | 78 | 5.56 |
| `solidarieta` | 177 | 3.00 | `solidarietà` | 118 | 4.52 |
| `umanita` | 163 | 3.37 | `umanità` | 121 | 4.44 |
| `universita` | 152 | 3.64 | `università` | 95 | 5.11 |
| `vabbe` | 146 | 3.80 | `vabbè` | 123 | 4.40 |
| `velocita` | 168 | 3.23 | `velocità` | 106 | 4.83 |
| `venerdi` | 142 | 3.90 | `venerdì` | 108 | 4.79 |
| `verita` | 151 | 3.68 | `verità` | 93 | 5.16 |
| `volonta` | 168 | 3.24 | `volontà` | 107 | 4.80 |

### Dropped: wrong accent (19)

| dropped | rank | zipf | standard | note |
|---|---:|---:|---|---|
| `bè` | 157 | 3.53 | `be'` | apocope: apostrophe, not accent |
| `cafè` | 161 | 3.41 | `caffè` |  |
| `caffé` | 162 | 3.40 | `caffè` | open e: grave |
| `cioé` | 160 | 3.44 | `cioè` |  |
| `finchè` | 127 | 4.30 | `finché` | closed e: acute |
| `fà` | 149 | 3.72 | `fa` | monosyllable, no accent; imperative fa' |
| `giá` | 154 | 3.60 | `già` |  |
| `nonchè` | 145 | 3.83 | `nonché` |  |
| `perchè` | 75 | 5.65 | `perché` |  |
| `peró` | 150 | 3.70 | `però` |  |
| `poichè` | 133 | 4.13 | `poiché` |  |
| `pò` | 120 | 4.47 | `po'` | apocope of poco: apostrophe, not accent |
| `puó` | 149 | 3.73 | `può` |  |
| `quì` | 153 | 3.63 | `qui` | monosyllable, no accent |
| `sará` | 163 | 3.35 | `sarà` |  |
| `sò` | 162 | 3.39 | `so` | monosyllable, no accent |
| `stà` | 157 | 3.51 | `sta` | monosyllable, no accent |
| `vabbé` | 164 | 3.33 | `vabbè` |  |
| `và` | 162 | 3.39 | `va` | monosyllable, no accent; imperative va' |

### Kept: Both forms are Italian words (hunspell it_IT accepts the bare form) (210)

`cosi` 5.12 / `così` 6.14, `pero` 4.42 / `però` 5.70, `si` 6.88 / `sì` 5.58, `li` 5.80 / `lì` 5.28, `ne` 6.10 / `né` 5.21, `meta` 4.42 / `metà` 5.16, `eta` 3.73 / `età` 5.11, `la` 7.30 / `là` 5.03, `capacita` 3.50 / `capacità` 4.98, `se` 6.61 / `sé` 4.98, `da` 6.84 / `dà` 4.94, `necessita` 4.00 / `necessità` 4.93, `unita` 4.39 / `unità` 4.90, `faro` 4.03 / `farò` 4.76, `papa` 5.15 / `papà` 4.66, `felicita` 3.44 / `felicità` 4.50, `abilita` 3.35 / `abilità` 4.39, `stabilita` 3.88 / `stabilità` 4.33, `eredita` 3.19 / `eredità` 4.27, `te` 5.87 / `tè` 4.27, `mori` 3.91 / `morì` 4.24, `comincio` 3.96 / `cominciò` 4.23, `gravita` 3.09 / `gravità` 4.21, `mobilita` 3.01 / `mobilità` 4.19, `lascio` 4.48 / `lasciò` 4.17, `facilita` 3.44 / `facilità` 4.14, `porto` 5.03 / `portò` 4.14, `terra` 5.43 / `terrà` 4.13, `passo` 5.16 / `passò` 4.12, `trovo` 4.85 / `trovò` 4.11, `riusci` 2.94 / `riuscì` 4.09, `di` 7.59 / `dì` 4.08, `senno` 3.96 / `sennò` 4.08, `inizio` 5.11 / `iniziò` 4.04, `arrivo` 4.94 / `arrivò` 4.03, `onesta` 4.06 / `onestà` 4.03, `entro` 5.04 / `entrò` 4.00, `passera` 3.27 / `passerà` 3.99, `torno` 4.48 / `tornò` 3.98, `usci` 2.97 / `uscì` 3.95, `continuo` 4.78 / `continuò` 3.91, `dichiaro` 3.57 / `dichiarò` 3.88, `menu` 3.92 / `menù` 3.85, `cerco` 4.73 / `cercò` 3.82, `pubblico` 5.40 / `pubblicò` 3.81, `ordino` 3.30 / `ordinò` 3.79, `apri` 4.13 / `aprì` 3.78, `casino` 4.58 / `casinò` 3.77, `fini` 4.64 / `finì` 3.77, `segui` 4.10 / `seguì` 3.77, `presento` 3.81 / `presentò` 3.73, `chiamo` 4.38 / `chiamò` 3.70, `divento` 3.64 / `diventò` 3.66, `penso` 5.42 / `pensò` 3.66, `toto` 3.67 / `totò` 3.66, `duro` 4.60 / `durò` 3.64, `tento` 3.26 / `tentò` 3.64, `scopri` 4.34 / `scoprì` 3.63, `parti` 5.21 / `partì` 3.62, `parlo` 4.67 / `parlò` 3.61, `creo` 3.28 / `creò` 3.58, `resto` 5.22 / `restò` 3.54, `ritorno` 5.00 / `ritornò` 3.53, `senti` 4.71 / `sentì` 3.53, `fondo` 5.16 / `fondò` 3.52, `accetto` 4.09 / `accettò` 3.51, `calamita` 3.21 / `calamità` 3.51, `mostro` 4.30 / `mostrò` 3.51, `rifiuto` 4.42 / `rifiutò` 3.51, `dimostro` 3.00 / `dimostrò` 3.50, `mando` 4.09 / `mandò` 3.50, `giacche` 3.52 / `giacché` 3.49, `lancio` 4.53 / `lanciò` 3.49, `colpi` 4.53 / `colpì` 3.48, `fermo` 4.68 / `fermò` 3.48, `sali` 4.07 / `salì` 3.48, `sembro` 3.84 / `sembrò` 3.47, `incontro` 5.05 / `incontrò` 3.46, `cambio` 4.93 / `cambiò` 3.44, `capi` 4.55 / `capì` 3.43, `passero` 2.99 / `passerò` 3.43, `scoppio` 3.92 / `scoppiò` 3.43, `tratto` 4.75 / `trattò` 3.43, `occupo` 3.61 / `occupò` 3.42, `dedico` 3.45 / `dedicò` 3.41, `infinita` 4.13 / `infinità` 3.41, `partecipo` 3.30 / `partecipò` 3.41, `racconto` 4.65 / `raccontò` 3.41, `stabili` 3.89 / `stabilì` 3.41, `abbandono` 4.22 / `abbandonò` 3.40, `sposo` 3.98 / `sposò` 3.40, `guardo` 4.40 / `guardò` 3.38, `studio` 5.34 / `studiò` 3.38, `offri` 3.39 / `offrì` 3.37, `servi` 4.06 / `servì` 3.37, `cesso` 4.03 / `cessò` 3.36, `rene` 3.80 / `rené` 3.36, `spiego` 4.08 / `spiegò` 3.36, `uso` 5.32 / `usò` 3.36, `acquisto` 4.61 / `acquistò` 3.35, `lavoro` 5.91 / `lavorò` 3.34, `prosegui` 3.06 / `proseguì` 3.34, `provo` 4.50 / `provò` 3.34, `avvicino` 3.11 / `avvicinò` 3.33, `invio` 4.10 / `inviò` 3.33, `insegno` 3.37 / `insegnò` 3.32, `confermo` 3.70 / `confermò` 3.31, `salvo` 4.63 / `salvò` 3.31, `annuncio` 4.57 / `annunciò` 3.30, `ritiro` 4.38 / `ritirò` 3.30, `segno` 4.92 / `segnò` 3.29, `tocco` 4.16 / `toccò` 3.29, `alzo` 3.70 / `alzò` 3.27, `giro` 5.32 / `girò` 3.24, `invito` 4.54 / `invitò` 3.24, `nomino` 3.00 / `nominò` 3.24, `ritrovo` 4.14 / `ritrovò` 3.22, `uni` 4.22 / `unì` 3.22, `leggera` 4.36 / `leggerà` 3.21, `leggero` 4.33 / `leggerò` 3.20, `sviluppo` 5.23 / `sviluppò` 3.20, `riporto` 3.76 / `riportò` 3.19, `compro` 3.97 / `comprò` 3.18, `noto` 4.99 / `notò` 3.18, `pie` 3.62 / `piè` 3.18, `accompagno` 3.11 / `accompagnò` 3.17, `equipe` 3.30 / `équipe` 3.17, `rossoblu` 3.05 / `rossoblù` 3.17, `aiuto` 5.17 / `aiutò` 3.16, `avvio` 4.08 / `avviò` 3.16, `approvo` 3.35 / `approvò` 3.14, `ali` 4.62 / `alì` 3.13, `condanno` 3.04 / `condannò` 3.13, `dono` 4.39 / `donò` 3.13, `limito` 3.40 / `limitò` 3.13, `appoggio` 4.38 / `appoggiò` 3.12, `getto` 3.78 / `gettò` 3.12, `amo` 4.89 / `amò` 3.11, `comando` 4.66 / `comandò` 3.11, `costo` 4.88 / `costò` 3.11, `supero` 3.15 / `superò` 3.11, `esercito` 4.82 / `esercitò` 3.09, `osservo` 3.45 / `osservò` 3.09, `comunico` 2.99 / `comunicò` 3.08, `fuggi` 3.35 / `fuggì` 3.08, `organizzo` 3.14 / `organizzò` 3.08, `pena` 5.05 / `peña` 3.08, `sparo` 3.82 / `sparò` 3.08, `affido` 3.47 / `affidò` 3.07, `canto` 4.62 / `cantò` 3.07, `dona` 3.99 / `donà` 3.07, `preparo` 3.78 / `preparò` 3.07, `realizzo` 3.14 / `realizzò` 3.07, `attraverso` 5.36 / `attraversò` 3.06, `esegui` 2.98 / `eseguì` 3.06, `guido` 4.36 / `guidò` 3.06, `volo` 4.82 / `volò` 3.06, `manifesto` 4.32 / `manifestò` 3.05, `commento` 4.87 / `commentò` 3.04, `invento` 2.94 / `inventò` 3.04, `avanzo` 3.38 / `avanzò` 3.03, `pago` 4.02 / `pagò` 3.03, `ricordo` 5.16 / `ricordò` 3.03, `suarez` 3.16 / `suárez` 3.03, `capito` 5.28 / `capitò` 3.02, `compi` 2.99 / `compì` 3.02, `denuncio` 2.95 / `denunciò` 3.02, `libero` 5.08 / `liberò` 3.02, `manco` 4.55 / `mancò` 3.02, `assicuro` 4.05 / `assicurò` 3.01, `attacco` 4.87 / `attaccò` 3.01, `aumento` 4.89 / `aumentò` 3.01, `muto` 3.79 / `mutò` 3.01, `ultra` 4.04 / `ultrà` 3.01, `salto` 4.44 / `saltò` 3.00, `sposto` 3.17 / `spostò` 3.00, `suono` 4.58 / `suonò` 3.00, `conservo` 3.14 / `conservò` 2.99, `regalo` 4.64 / `regalò` 2.99, `calo` 4.30 / `calò` 2.98, `considero` 3.89 / `considerò` 2.98, `disonesta` 3.05 / `disonestà` 2.98, `imparo` 3.21 / `imparò` 2.98, `nego` 3.41 / `negò` 2.98, `butto` 3.78 / `buttò` 2.97, `curo` 3.18 / `curò` 2.97, `presto` 5.16 / `prestò` 2.97, `taglio` 4.62 / `tagliò` 2.97, `rientro` 4.18 / `rientrò` 2.96, `tiro` 4.54 / `tirò` 2.96, `consiglio` 5.36 / `consigliò` 2.95, `giuri` 2.99 / `giurì` 2.94, `gratuita` 4.16 / `gratuità` 2.94, `merce` 4.19 / `mercé` 2.94, `brucio` 2.91 / `bruciò` 2.93, `mimi` 3.17 / `mimì` 2.93, `assegno` 4.02 / `assegnò` 2.91, `informo` 3.24 / `informò` 2.91, `nardo` 2.91 / `nardò` 2.91, `regno` 4.96 / `regnò` 2.91, `domando` 3.96 / `domandò` 2.90, `genero` 3.51 / `generò` 2.90, `interesso` 2.95 / `interessò` 2.90, `registro` 4.39 / `registrò` 2.90, `spari` 3.95 / `sparì` 2.90, `grido` 4.06 / `gridò` 2.89, `scappo` 3.23 / `scappò` 2.89, `affronto` 3.47 / `affrontò` 2.88, `paterno` 3.45 / `paternò` 2.88, `forni` 3.65 / `fornì` 2.87

### Kept: Names: the Capitalised bare form is a hunspell proper noun (2)

| bare | zipf | accented | zipf | why |
|---|---:|---|---:|---|
| `sara` | 4.53 | `sarà` | 5.73 | Leipzig it-2019: Sara 69 mid-sentence vs sara 85 |
| `fara` | 3.61 | `farà` | 4.89 | Leipzig it-2019: Fara 9 vs fara 8 |

### Kept: Accented form is the key of an elision entry (4)

| bare | zipf | accented | zipf | why |
|---|---:|---|---:|---|
| `ne` | 6.10 | `nè` | 4.55 | `nè` keys the `n'è` entry |
| `se` | 6.61 | `sè` | 4.26 | `sè` keys the `s'è` entry |
| `ce` | 5.45 | `cè` | 3.17 | `cè` keys the `c'è` entry |
| `che` | 7.36 | `chè` | 3.13 | `chè` keys the `ch'è` entry |

### Kept: Acute í/ú convention (older but legitimate typography) (3)

| bare | zipf | accented | zipf | why |
|---|---:|---|---:|---|
| `cosi` | 5.12 | `cosí` | 4.07 | kept with the grave form |
| `si` | 6.88 | `sí` | 3.79 | kept with the grave form |
| `li` | 5.80 | `lí` | 3.25 | kept with the grave form |

### Kept: Accepted variant or valid accented word (6)

| bare | zipf | accented | zipf | why |
|---|---:|---|---:|---|
| `che` | 7.36 | `ché` | 3.91 | valid (poetic perché) |
| `do` | 4.87 | `dò` | 3.48 | accepted variant of `do` |
| `dei` | 6.61 | `dèi` | 3.41 | gods (plural of dio), valid |
| `subito` | 5.47 | `subìto` | 3.35 | disambiguating accent, valid |
| `principi` | 4.67 | `princìpi` | 3.34 | disambiguating accent, valid |
| `the` | 5.50 | `thè` | 3.01 | variant of `tè` |

### Kept: Foreign names and loans (not Italian accent errors) (33)

| bare | zipf | accented | zipf | why |
|---|---:|---|---:|---|
| `jose` | 3.54 | `josé` | 4.19 | proper noun/loan; both spellings in use |
| `elite` | 3.84 | `élite` | 3.75 | proper noun/loan; both spellings in use |
| `andre` | 3.53 | `andré` | 3.66 | proper noun/loan; both spellings in use |
| `pokemon` | 3.48 | `pokémon` | 3.66 | proper noun/loan; both spellings in use |
| `sao` | 3.03 | `são` | 3.64 | proper noun/loan; both spellings in use |
| `garcia` | 3.90 | `garcía` | 3.63 | proper noun/loan; both spellings in use |
| `maria` | 5.10 | `maría` | 3.62 | proper noun/loan; both spellings in use |
| `lopez` | 3.74 | `lópez` | 3.50 | proper noun/loan; both spellings in use |
| `gonzalez` | 3.17 | `gonzález` | 3.49 | proper noun/loan; both spellings in use |
| `francois` | 3.14 | `françois` | 3.48 | proper noun/loan; both spellings in use |
| `leon` | 3.80 | `león` | 3.46 | proper noun/loan; both spellings in use |
| `sanchez` | 3.62 | `sánchez` | 3.46 | proper noun/loan; both spellings in use |
| `perez` | 3.43 | `pérez` | 3.44 | proper noun/loan; both spellings in use |
| `fernandez` | 3.28 | `fernández` | 3.41 | proper noun/loan; both spellings in use |
| `rodriguez` | 3.76 | `rodríguez` | 3.40 | proper noun/loan; both spellings in use |
| `gomez` | 3.50 | `gómez` | 3.24 | proper noun/loan; both spellings in use |
| `sebastian` | 3.89 | `sebastián` | 3.24 | proper noun/loan; both spellings in use |
| `andre` | 3.53 | `andrè` | 3.22 | proper noun/loan; both spellings in use |
| `cesar` | 3.20 | `césar` | 3.19 | proper noun/loan; both spellings in use |
| `martinez` | 3.55 | `martínez` | 3.17 | proper noun/loan; both spellings in use |
| `felix` | 3.75 | `félix` | 3.16 | proper noun/loan; both spellings in use |
| `diaz` | 3.78 | `díaz` | 3.15 | proper noun/loan; both spellings in use |
| `jesus` | 3.64 | `jesús` | 3.13 | proper noun/loan; both spellings in use |
| `gio` | 3.75 | `giò` | 3.04 | proper noun/loan; both spellings in use |
| `premiere` | 3.27 | `première` | 3.04 | proper noun/loan; both spellings in use |
| `alvarez` | 3.42 | `álvarez` | 3.01 | proper noun/loan; both spellings in use |
| `jose` | 3.54 | `josè` | 3.01 | proper noun/loan; both spellings in use |
| `bolivar` | 2.90 | `bolívar` | 3.00 | proper noun/loan; both spellings in use |
| `orleans` | 3.76 | `orléans` | 2.97 | proper noun/loan; both spellings in use |
| `marquez` | 3.39 | `márquez` | 2.96 | proper noun/loan; both spellings in use |
| `ramirez` | 3.18 | `ramírez` | 2.96 | proper noun/loan; both spellings in use |
| `citroen` | 2.88 | `citroën` | 2.93 | proper noun/loan; both spellings in use |
| `antonio` | 4.86 | `antónio` | 2.90 | proper noun/loan; both spellings in use |

Total pairs: 337; kept pairs listed: 258
