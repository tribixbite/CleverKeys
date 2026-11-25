# German dictionary with ß (2026-11-25)

Maintainer: "rebuild the german dictionary with ß". Found on the Saga the same day: the bundled
German list had no `ß` word at all, so a swipe showed `Strasse` and a tapped `groß` + space
committed `gros` (that symptom was fixed first by `WordPredictor.sharpSDictionaryKey`).

## Cause

`scripts/build_wordlist.py` takes German candidates from wordfreq, which casefolds its corpus.
Python's casefold turns `ß` into `ss`, so the stream spells `strasse`, `grösse`, `weiss`, and the
list (and `de.cklm`, whose vocabulary is the list) followed it. The model's corpora keep `ß`, so
every `ß` token of them was out of vocabulary: the shipped model knew no `weiß`, `groß`, `heißt`.

## Method (`scripts/sharp_s.py`, tests `scripts/test_sharp_s.py`)

Every stream or shipped word with `ss` is read every way its `ss` can be `ß`, then:

| oracle (LibreOffice de_DE_frami, lower or Capitalised) | result |
|---|---|
| accepts the word only | kept (`dass`, `muss`, `Fluss`, `Schloss`) |
| accepts a ß reading, not the word | that reading (`straße`, `größe`, `weiß`); the corpus favourite if several |
| … but the corpus writes the word ≥ 3 times and ≥ ⅓ of the time | both, frequency split (`Hess`/`Heß`) |
| accepts both | both real words: wordfreq frequency split by corpus counts (`Masse`/`Maße`, `Busse`/`Buße`, `Strauss`/`Strauß`); a reading the corpus never shows is left out; no counts keeps the word |
| accepts neither | a ß reading only on a clear corpus majority (≥ 3 hits and > 2× the word: `Gauß`, `Rößler`) |

Counts: Leipzig German web 2021 300K (`deu-de_web_2021_300K`, the corpus `de.cklm` is built from,
pinned by sha256), lowercased. de_DE_frami is pinned by sha256 (`corpus_blend.FILE_HUNSPELL`);
the system aspell `de` knows no compounds (`Kreisstraße`, `Außenbereich`), de_DE_frami does and
rejects the pre-1996 spellings (`daß`, `Flußkilometer`, `Schloßstraße`).

Of 4,280 stream words with `ss`: 1,048 respelled with ß, 9 split, 3 by corpus majority, 3,219 kept.
Reviewed: the 15 kept words whose ß reading has more corpus hits are names with 1–2 hits or
pre-1996 spellings (`häßlichkeit`, `einflußreichen`); the 206 respelled words whose `ss` form has
as many hits are standard ß words (`äußerster`, `leichtfüßig`) and names the oracle knows (`Heß`,
now split with `Hess`). Of the shipped 40,000, 517 are respelled; 543 list words carry ß. After the
build, the only `ss` word left that the oracle rejects while accepting its ß reading is `hess`
(the split).

Reproduction first: with the AOSP snapshot re-fetched to its PROVENANCE sha256 (34f19e7d1af1;
sorted `headword\tflags` rows), the unchanged builder rebuilt the shipped `de_enhanced.bin` byte
for byte. The rebuild then lost 25 words at the 40,000 cap (zipf 2.97, `veranschaulichung` …):
the 9 split twins and the ß words that now pass the oracles take their slots.

Derived: `contractions_de.json` and every collision file regenerate unchanged; `langpack-de.zip`
rebuilt (its `dictionary.bin` equals the bundled one). Its `unigrams.txt` is still wordfreq's
casefolded top 5,000; nothing reads it since language detection was deleted (ARC-006).

## Next-word model (`de.cklm`)

Gate pre-registered in the German `LangConfig` of `scripts/build_static_lm.py` before the build;
the 2026-09-29 inputs first reproduced the shipped model (sha256 63892b2c…).

| gate | result |
|---|---|
| G1 names neither `weiss` nor `weisst`; names `weiß`, `weißt` | pass (+207 ß words) |
| G2 size ≤ 1.10 × 438,403 B | pass, 445,202 B |
| G3 OOD-test next-word ≥ 7.68 / 14.47 | pass, 7.93 / 14.75 (was 7.78 / 14.57) |
| G4 tap eval end to end, every lm_static cell ≥ shipped − 0.10 pt | **FAIL**: five prefix top-3 cells −0.21 … −0.23 |
| G4a new vs shipped model under the new lexicon | pass, worst +0.03 |
| G5 other models byte-identical | pass |

G4's population is not fixed: a target outside the lexicon is not scored, so the new lexicon adds
80 OOD and 377 in-domain positions (the ß words). In hit counts every cell gains end to end; the
added targets are hit less often than average, which lowers the rates. Measured afterwards (not
pre-registered) with `STATIC_LM_EVAL_TARGETS_IN`, on the 9,727 OOD / 42,575 in-domain positions
both lexicons can score: every one of the 16 cells is ≥ +0.00 pt (best +0.28, OOD next-word
top-3). Shipped on that evidence; the maintainer may overrule.

## Typing (`WordPredictor`, `WordPredictorSharpSTest`)

With the list spelling ß, a typed `strasse`, `weiss`, `heisst` + space becomes `straße`, `weiß`,
`heißt` (as `fur` becomes `für`), and `stras`/`strass` offer `straße` on the bar, both through the
existing fuzzy matcher. One bug the rebuild would have shipped: typed `gross` committed `gros`,
because the stuck-key collapse (`gamees` → `games`) read the `ss` as a doubled letter; a ß reading
now runs first. `dass`, `Masse`, `maße` stay as typed. Swiss spelling (no ß) is not served by this
list: a typed `strasse` is corrected; a user can add it as a custom word.

Pins: `GermanSharpSDictionaryDriftTest` (4; all fail on the old assets), `CtcAccentKeyLayoutReplayTest`
Neo 2 / Bone ß-key swipes now expect `straße` (red on the old lexicon), lexicon shape 39,588
surfaces / 412 collisions (`CtcCkdtLexiconTest`, `CtcMultiLanguageInstrumentedTest.SHAPES`).
