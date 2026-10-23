# Persian (`fa`) word list: sources, filtering and coverage (2026-10-23)

Maintainer approval, 2026-10-23: "build the fa pack if you can find a way to filter the word list
well — 1.5M is way too many words." This note records how the list was built and filtered, the
count after every step, the size cutoff and why, and what a sample of the result looks like.
Research input: `docs/research/2026-10-21-persian-zwnj.md` (§3 minimal pairs, §5 sources, §7).

Result: `scripts/dictionaries/langpack-fa.zip` (545,291 bytes, sha256 `218024dfdabe…`, rebuilt
2026-10-23 with the glued-clitic filter of §2a),
**50,000 words**, 11,213 of them (22.4 %) with a half-space, 1,978 joined/half-space pairs, and
**97.45 % token coverage** of held-out Persian sentences. First build `479a7277`; the glued-clitic
rebuild is the commit that adds §2a.

Reproduce (downloads go to `~/.cache/cleverkeys-corpora/`, never the repo):

```bash
python3 scripts/corpus_blend.py --corpus fa                       # counts + attested-list curve
python3 scripts/build_wordlist.py --lang fa --bootstrap            # report mode, review artifacts
python3 scripts/build_wordlist.py --lang fa --bootstrap --write
python3 scripts/generate_unigrams.py --lang fa --from-words scripts/dictionaries/fa/fa_words.txt \
        --output scripts/dictionaries/fa/unigrams.txt --top-n 5000
python3 scripts/build_langpack.py --lang fa --name Persian --dict scripts/dictionaries/fa/fa_enhanced.bin \
        --unigrams scripts/dictionaries/fa/unigrams.txt --version 2 --output scripts/dictionaries/langpack-fa.zip
```

Two builds from the same inputs were byte-identical (`fa_enhanced.bin` and the zip).

## 1. Sources and licences

| Source | Role | Licence |
|---|---|---|
| Leipzig `fas_news_2016_300K`, `_2017_300K`, `_2018_300K` (one source, "news") | counts | CC BY 4.0 |
| Leipzig `fas_newscrawl_2019_1M` | counts | CC BY 4.0 |
| Leipzig `fas_wikipedia_2014_1M` | counts | CC BY 4.0 |
| wordfreq `fa` (`best`, 31,389 tokens) | counts (4th source) | CC BY-SA 4.0 |
| Lilak `b00f/lilak` @ `4f1f426113e7`, built into a hunspell dictionary | inclusion oracle | Apache-2.0 |
| LDNOOBW `fa` @ `64fc2514` | blocklist review input | CC BY 4.0 |

All five tarballs are sha256-pinned in `scripts/corpus_blend.py` `FA_BLEND`.

**Finding: most recent Leipzig Persian corpora have the half-space stripped.** Measured on the
first 50,000 sentences (10,000 for the probes) of each corpus, share of sentences containing a
U+200C:

| Corpus | sentences with ZWNJ | `می‌` / `می` |
|---|---|---|
| news 2016 / 2017 / 2018 | 53.5 % / 62.8 % / 73.0 % | 38.8 % / 46.7 % / 54.8 % |
| news 2019 | 24.4 % | 18.5 % |
| news 2020, 2021, 2022, 2023, 2024 | **0.0 %** | 0.0 % |
| web-public 2019 (`fas-ir`) | **0.0 %** | 0.0 % |
| news crawl 2011 / 2015-2017 / 2019 | 33-48 % | 27-30 % |
| Wikipedia 2010 / 2012 / 2014 | 62 % | 44-49 % |

The stripped corpora turn `شده‌اند` into `شدهاند` and `پالایشگاه‌های` into `پالایشگاههای`,
strings no writer types (a writer who skips the half-space types a full space after ه). The
research note's §5 assumed Leipzig preserves ZWNJ (inferred from HeliBoard's list); that holds
only for the older corpora. The stripped ones (news 2020+, web-public 2019) were not used, which
also means the list has no Leipzig web register: wordfreq (Twitter, subtitles, web) is the only
informal source. HeliBoard's 1.52M list was not needed.

Not used, per §5: FrequencyWords `fa` (0 ZWNJ, Arabic letters on 18,226 of 50,000 lines) and
Hazm's `words.dat` (undocumented counts, 2,818 Arabic-letter entries).

## 2. Filtering, with the count at each step

**Tokenising** (`corpus_blend.RAW_TOKEN`). A raw token is a run of letters, digits, combining
marks and the zero-width/bidi/tatweel format characters; everything else separates. A token that
contains a digit, a Latin letter or any character outside the alphabet is rejected **whole**,
never split, so a corpus-side mid-word break cannot leak a fragment.

**Normalising** (`corpus_blend.normalise`), in order: NFC; drop LRM/RLM/bidi embeddings/BOM/soft
hyphen; fold ي→ی, ك→ک, ى→ی, ۀ (U+06C0)→ه, ة→ه, ە (U+06D5)→ه (the app's fold; never آ or hamza);
strip combining marks without decomposing (harakat, ezafe hamza, superscript alef; آ أ إ ؤ ئ are
precomposed and stay); reject tatweel and ZWJ; collapse ZWNJ runs and strip edge ZWNJ (a token
that was only ZWNJ is dropped); reject a letter repeated 3+ times; reject one-letter tokens except
و. Alphabet: the 32 letters, آ, ء أ إ ؤ ئ, plus word-internal ZWNJ.

Training tokens per source after the 10 % held-out split (raw-token rejections in brackets):

| Source | train sentences | valid tokens | types | digit | Latin | edge ZWNJ stripped (kept) | bare ZWNJ | tatweel/ZWJ | other letter | 1-letter | repeat |
|---|---|---|---|---|---|---|---|---|---|---|---|
| news 2016-18 | 810,235 | 18,116,765 | 198,801 | 206,422 | 13,132 | 44,815 | 2,957 | 3,373 | 859 | 13,920 | 78 |
| news crawl 2019 | 900,127 | 20,339,245 | 187,753 | 370,419 | 44,623 | 44,139 | 3,768 | 5,308 | 2,133 | 17,087 | 96 |
| Wikipedia 2014 | 900,054 | 17,455,090 | 337,319 | 616,195 | 208,313 | 6,533 | 1,071 | 5,283 | 7,137 | 24,250 | 214 |
| wordfreq `fa` | — | (frequencies) | 27,901 | 161 | 1,084 | 45 | 3 | 2 | 57 | 34 | 7 |

The fold merged 2,097 of wordfreq's 2,100 Arabic-letter tokens into their Persian spelling
(frequencies summed).

| Step | Words |
|---|---|
| distinct normalised words seen in any source | 513,507 |
| attested: ≥ 3 training occurrences (wordfreq: listed) in ≥ 2 of the 4 sources | **66,386** |
| classifier universe (66,385 ranked + و from the allowlist) | 66,386 |
| dropped: band 2 (rank ≥ 60,000) without a Lilak acceptance | 3,227 |
| dropped: edit-distance-1 typo of a much more frequent Lilak word (`انجا`→`انجام`, `لست`→`است`, `وگفت`→`گفت`) | 2,141 |
| dropped: Arabic dominance (zipf in `ar` > 3 and > fa + 1; `الناس`, `السیسی`, `بشکل`) | 390 |
| dropped: 3-letter noise (no oracle, zipf < 3) | 119 |
| dropped: ZWNJ-stripping artefact (below) | 81 |
| dropped: 2-letter without evidence | 70 |
| dropped: blocklist (profanity/slurs) | 10 |
| dropped: glued clitic (§2a; rebuild — the rows above are the first build) | 120 |
| survivors before the size cap | 60,348 |
| dropped: size cap (`--limit 50000`, blended-frequency order) | 10,348 |
| **final** | **50,000** |

Positive oracle: Lilak accepted 48,283 of the 66,386 candidates. ZWNJ is a word character in the
built dictionary (`WORDCHARS` U+200C), otherwise hunspell splits `می‌شود` into `می` + `شود`.
ZWNJ is NOT in the edit-distance alphabet: `میشود` and `می‌شود` are both real spellings, never
each other's typo.

**Joined vs half-space spellings.** Both are kept when both are attested (§3): the joined form
is the more frequent spelling for about half the pairs, and for `به‌روز`/`بهروز` they are
different words. Two rules guard the joined side:

- a ZWNJ-stripping artefact is a joined spelling whose every attested half-space twin has a ZWNJ
  right after ه, that Lilak rejects, and that sits more than 1.0 zipf below its twin
  (`build_wordlist.py` Stage D, `ZWNJ_ARTEFACT_GAP`). The gap was set from the data: artefacts sit
  about 2 zipf below their twin (`شدهاند` −2.19, `برنامههای` −2.14, `رسانهها` −2.01, Wikipedia
  counts 19 vs 5,554 for `شدهاند`/`شده‌اند`), while real joined spellings sit near or above it
  (`زهکشی` +1.21, `بهشان` +1.09, `نگهداشتن` +0.15, `دایرهالمعارف` −0.15). 81 dropped;
- every other joined form competes like any word (band 1 keeps it unless a negative fires; band 2
  needs Lilak, which rejects most joined compounds, so only frequent joined forms survive).

All twelve §3 class-(i) forms survived (`ساله‌ای`/`سال‌های`, `حرفه‌ای`/`حرف‌های`, `نامه‌ای`/`نام‌های`,
`زمینه‌ای`/`زمین‌های`, `دسته‌ای`/`دست‌های`, `نقشه‌ای`/`نقش‌های`), with their joined strings, as did
`به‌روز`/`بهروز`, `دست‌کم`/`دستکم`, `گفت‌وگو`/`گفتگو`, `بیشتر`/`بیش‌تر`, `آن‌ها`/`آنها`.

### 2a. Glued-clitic artefacts (added 2026-10-23 after the Pixel device check)

On the Pixel the bar completed `می‌شود` to `می‌شودکه`: a common word with a conjunction or clitic
glued on where a space is missing. New Stage-D negative in `build_wordlist.py`
(`LangConfig.glued_suffixes`, fa: `که را و`): a candidate W = X + suffix is dropped when X is
among the `GLUED_HEAD` = 2,000 most frequent candidates and W is more than `GLUED_RATIO` = 200
times rarer than X, unless Lilak accepts W or the allowlist holds it.

Lilak accepts `چونکه`, `همینکه`, `آنجاکه`, `اینکه`, `آنکه`, `بلکه`, `چنانکه`, `کندو` (beehive), `اندو`,
`دومینو`, `شاهو`, so those stay. It rejects `وقتیکه`, `کسیکه`, `زیراکه`, `آنانکه`, `سندرا`, `سنتو`;
these, plus `تورا` (Torah), the colloquial object form `اینو` (= این را) and colloquial `توو`, are in
the reviewed allowlist (`fa_allowlist.txt`).

The filter flags 120 candidates. 18 of them were in the first 50,000 and are now dropped:
`آنهارا استرا اورا باشدکه خودرا داردکه سیاهو شمارا شودکه می‌شودکه نفرو هررا هزارو هستندکه هنریکه
ویرا پیکه کاررا`. The cap refilled 18 from the next-ranked survivors: `ایفرا تمساح‌های توفان‌ها
خونابه دهقی رقومی سایبورگ فرضهای فرنگی‌های فسفره قلی‌پور متمرد معاصری مهستان نقش‌آفرینان
پخش‌کنندگان پروتزهای پونته`. Coverage is unchanged at 97.45 %. Possible over-drops among the 18:
`اورا` (also "aura"), `پیکه` (piqué), `هنریکه` (Henrique), `سیاهو` (a place/surname) — not
reviewed by a native speaker. `PersianLangpackTest` pins both sides.

**Space-separated affixes are kept on purpose.** Informal writers type `می شود`, `کتاب ها`, so
`می`, `نمی`, `ها`, `های` are frequent standalone tokens in every source. Dropping them would make
the autocorrect rewrite them into a neighbour word (`ها`→`با`), so they stay. "Tokens split
mid-word by the corpus tokenizer" do not arise: the corpus tokenizer is not used, and a token
with a foreign character is rejected whole.

**Blocklist.** The other packs' blocklists are comment-only (en's holds typo fragments): none of
them removes profanity. For fa, `scripts/dictionaries/fa/fa_blocklist.txt` holds 34 words reviewed
from LDNOOBW `fa` plus vulgar insults and ethnic/homophobic slurs it lacks. LDNOOBW entries whose
main sense is ordinary vocabulary stay in the dictionary: کس (person, `هیچ‌کس`), حشر
(resurrection), تجاوز (aggression), منی, لخت, برهنه, سوپر, شهوت, هرزه, لش, داف, سکس, سکسی, پورن,
ارگاسم. 10 blocked words were in the candidates (`کون`, `جنده`, `کیر`, `کونی`, `کیری`, `کوس`,
`تخمی`, `جاکش`, `قحبه`, `جلق`).

**Diacritics policy.** As in the other packs, the canonical words carry no combining marks
(Hebrew's stream is unvocalised; `build_dictionary.py` strips marks for the normalised lookup
key). Note that the CKDT *normalised* key is NFD minus marks, so آ→ا and أ→ا there (as é→e for
Latin); the canonical word keeps آ.

## 3. Size and coverage

Held-out text: 10 % of the sentences of all three Leipzig sources, chosen by sentence hash
(`build_static_lm.is_heldout`; identical sentences in two corpora fall on the same side) and never
counted: 289,584 sentences, 6,198,503 valid tokens, 153,354 types. Caveat: wordfreq includes
Wikipedia, so the Wikipedia part of the held-out set is not fully unseen by the wordfreq source.

| Words (blended-frequency order) | attested list (≥ 2 sources) | single-source allowed | classifier survivors |
|---|---|---|---|
| 20,000 | 95.06 % | 95.07 % | 95.06 % |
| 30,000 | 96.39 % | 96.41 % | 96.38 % |
| 50,000 | 97.50 % | 97.62 % | **97.45 %** |
| 60,348 / 66,386 (all) | 97.83 % | — | 97.66 % |
| 80,000 | (list ends at 66,386) | 98.38 % | (60,348 survive) |
| 120,000 | — | 98.81 % | — |

For comparison, wordfreq `fa` alone (27,901 words after normalisation) covers 95.08 %.

The cutoff is 50,000: from 30k to 50k coverage rises 1.1 points; from 50k to all 60,348
survivors only 0.21 more; reaching 80k needs single-source words and buys 0.8 points with the
very words the two-source rule exists to keep out. 50k also matches the he/uk/mk/ru/es packs
(fr/de/it/pt/sv/nl/tr: 40k) and stays far inside the import caps (100,000 entries; the
dictionary is 1.80 MB of the 16 MiB limit).

## 4. Sample

40 entries drawn at random (seed 20261023) from three frequency bands of the final list; glosses
are mine and not native-reviewed.

| Rank | Word | Gloss |
|---|---|---|
| 188 | گرفت | took |
| 631 | سپس | then |
| 1,656 | درگیری | clash, conflict |
| 1,674 | خویش | oneself |
| 1,972 | فولاد | steel |
| 2,073 | اتوبوس | bus |
| 2,166 | میانگین | average |
| 2,890 | تخصیص | allocation |
| 3,007 | اربعین | Arbaeen (religious observance) |
| 3,265 | مایع | liquid |
| 3,336 | موسسات | institutions |
| 4,372 | مادری | maternal |
| 4,696 | کوهستان | mountains |
| 5,325 | حماسه | epic |
| 5,709 | بخورد | (that he) eat |
| 7,362 | بهنام | Behnam (given name) |
| 9,252 | نفوذی | infiltrator |
| 9,851 | نفوس | population ("souls") |
| 10,112 | آبروی | honour of |
| 10,676 | گرایانه | "-ist(ic)", the second half of a space-split compound (`واقع گرایانه`) |
| 10,819 | آوای | voice of |
| 11,607 | مداحان | eulogy reciters |
| 14,295 | سیاق | manner, context |
| 14,454 | ناسیونالیسم | nationalism |
| 14,657 | تضییع | violation (of rights) |
| 19,327 | نخوردم | I did not eat |
| 21,776 | مستقلا | independently |
| 24,845 | حاشیه‌هایی | some controversies / margins |
| 27,378 | اعمالش | his/her deeds |
| 29,334 | گنبدها | domes |
| 29,556 | جهانبگلو | Jahanbegloo (surname) |
| 32,332 | استرسی | stressful |
| 33,367 | لیاقتی | a merit |
| 34,950 | فنچ | finch |
| 38,828 | ملزوم | required |
| 39,539 | شیزوفرنی | schizophrenia |
| 44,172 | بریجز | Bridges (name) |
| 46,640 | الخالدیه | Al-Khalidiya (Arabic place name) |
| 48,348 | شریدان | Sheridan |
| 49,098 | جدید‌ترین | newest (half-spaced superlative, a style variant of جدیدترین) |

Reading: 34 of 40 are ordinary vocabulary; 5 are proper names (as in the other packs, which
keep frequent names) and one is a space-split compound half. No junk, no Arabic-letter spelling,
no stripped-ZWNJ artefact in the sample.

## 5. Swipe and app integration

- Swipe: no CTC. `fa` has no `CtcLanguageSupport` row and `CtcImportedPackSupport.evaluate`
  refuses the pack (not a–z projectable), so the geometric engine serves it. On Persian PC
  (`arab_pc_ir.xml`) the geometric projection templates 49,828 of 50,000 words; the 172 that
  fail all contain أ, ؤ or إ, which the layout has no key or corner for. The half-space is a
  default-ignorable control in `LayoutProjection`, so `می‌شود` and `میشود` swipe the same path.
- Tests: `langpack.PersianLangpackTest` (mock tier) loads the pack through
  `BinaryDictionaryLoader`, checks the CKDT caps via `CkdtDictionaryReader`, the ZWNJ forms and
  pairs, the alphabet, the top ranks, and the geometric/CTC routing above;
  `LanguagePackImportTest.everyShippedPackImportsAndCarriesItsAttribution` imports it through the
  real importer.
- Device (Pixel, 2026-10-23, coordinator): the half-space key stays inside the word, and joined
  `میشود` offers `می‌شود` without autocorrecting it. One synthetic adb swipe of `سلام` on Persian PC
  produced `سن`; that is not evidence either way, because adb motion events are coarse.
  **Persian swipe quality on the geometric engine is UNVALIDATED.**
- No native-speaker review of the list.

## 6. Open items

- A native-speaker pass over `fa_words.txt` and the drops review
  (`build_wordlist.py --review-dir`), especially the 2,141 typo drops, which include colloquial
  forms Lilak lacks (`پیشش`, `کشه`, `شمام`).
- Persian PC has no key for أ ؤ إ; 172 pack words cannot be typed or swiped on it.
- `اب` (a misspelling of آب, also an Arabic word) is in the list through the 2-letter rule.
