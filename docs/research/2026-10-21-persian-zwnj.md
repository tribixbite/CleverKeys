# Persian half-space (ZWNJ) for the `fa` pack (2026-10-21, extended 2026-10-22)

Research for todo item 2 (#186/#61 Persian half). Nothing is built yet. The 2026-10-21 sections
are kept below with corrections marked **Correction (2026-10-22)**; the numbered sections after
them are the 2026-10-22 extension (per-keyboard evidence, minimal pairs, normalisation, corpora,
the word-boundary code, and the recommendation for decisions (a)–(d)).

Evidence marks used throughout: **[V]** = the source was opened, or the number was measured on
this machine on 2026-10-22; **[U]** = unverified (search snippet only, inference, or a page that
could not be opened). The full list of opened sources is at the end.

## How Persian uses it

Persian joins a word's parts with U+200C ZERO WIDTH NON-JOINER ("half-space", نیم‌فاصله):
the verb prefix `می‌` (`می‌شود`), plurals `‌ها` / `‌های` (`کتاب‌های`), comparatives `‌تر` /
`‌ترین` (`مهم‌ترین`), and `‌ای` after a final ه (`حرفه‌ای`). Many writers, especially in
informal text, omit it (`میشود`, `میکنم`) or use a full space.

Rules that matter for an IME, from the Persian Wikipedia style page ویکی‌پدیا:نیم‌فاصله, which
summarises the Academy's دستور خط فارسی [V]:

- `‌ای` (indefinite/relative ی) after a silent final ه takes the half-space: «خانه‌ای»; after a
  *pronounced* final ه it does NOT: «مشابهش» not «مشابه‌اش», «توجیهی» not «توجیه‌ای»,
  «فرماندهی» not «فرمانده‌ای» (fa.wikipedia فاصله مجازی §هاء ملفوظ [V]). Whether a final ه is
  silent is lexical, not orthographic, so no string rule can decide it.
- `است` after a silent ه is written with a FULL space («بوده است», «خانه است») [V].
- The suffix `تر`/`ترین` is always attached: «بهتر» is correct, «به‌تر» is wrong; «بیشتر» is
  the attached form (the half-space form «بیش‌تر» exists in text but is not the rule) [V].
- The prefix `به` takes a half-space («به‌ویژه») EXCEPT where it would create ambiguity with a
  proper name or a plain word: «بهروز» must not be written «به‌روز», «بهنام» not «به‌نام»,
  «بهیار» not «به‌یار» [V].
- Compound verbs whose parts keep their own meaning are written with a space («کار کردن»);
  compounds that make a new meaning are joined or half-spaced («نگه‌داشتن», «برگشتن»), and the
  page warns that noun + verb sequences must not be confused with the homographic compound
  noun (its example contrasts «سر رسید» the verb phrase with the compound noun) [V].
- Style guides disagree on some compound verbs ("در برخی شیوه‌نامه‌ها … اختلاف نظر وجود دارد",
  blog.faradars.org) [V].

## What other keyboards do

**Correction (2026-10-22):** the three bullets below were partly wrong or incomplete; the
evidence is in §1 and §2.

- ~~Desktop standard (ISIRI 9147): ZWNJ on Shift+Space (Windows also Ctrl+Shift+2).~~
  ISIRI 9147 puts U+200C on **both** Shift+Space and Shift+B (AltGr+B is U+200D ZWJ) [V].
  Windows "Persian (Standard)" (`kbdfar.dll`, Windows 8+) follows that; the legacy Windows
  "Persian" layout (`kbdfa.dll`) has no ZWNJ on Shift+Space and uses Ctrl+Shift+2 instead [V].
- Phone keyboards: a dedicated half-space key beside the space bar is the norm (AOSP, OpenBoard,
  HeliBoard, FUTO, AnySoftKeyboard; Gboard and Samsung per secondary sources) [V/U per §1].
  HeliBoard adds the key automatically for `fa`, `ne`, `kn`, `te` [V].
- AOSP LatinIME config: `values-fa` only changes `suggested_punctuations`; `symbols_word_connectors`
  stays `'-`, and `ScriptUtils.isLetterPartOfScript(SCRIPT_ARABIC)` excludes U+200C, so a
  half-space ENDS the composed word in AOSP [V]. AOSP ships no `fa` dictionary at all [V].

## The data (`wordfreq` `fa`)

**Correction (2026-10-22)** — re-measured with `wordfreq.get_frequency_list('fa', 'best')` [V]:

- 31,389 tokens (the 2026-10-21 note said 31,317). 2,395 contain U+200C; of those **48 are
  tokenisation artefacts** with the half-space at the edge (`و‌`, `به‌`, `در‌` …), 3 are bare
  ZWNJ runs, and **2,347 carry it word-internally** (7.5% of the list).
- For 692 of the internal-ZWNJ words the joined spelling is also listed (the earlier "742"
  counted the edge artefacts). The joined form is the MORE frequent one in 365 of the 692 pairs
  (`میکنم` 5.56 vs `می‌کنم` 5.13 Zipf; `اینکه` 5.92 vs `این‌که` 4.52; `بیشتر` 5.96 vs
  `بیش‌تر` 4.12) and the half-space form in 327 (`می‌شود` 5.84 vs `میشود` 5.14; `می‌کند` 5.64
  vs `میکند` 4.92).
- 2,024 words use the Arabic letters ي (U+064A) / ك (U+0643); 1,981 of them have the Persian
  ی / ک spelling in the list too, and the Persian twin is the more frequent one in 1,977 cases
  (`كتاب` 4.27 vs `کتاب` 5.88). These must be folded to ی / ک and their frequencies merged.
- The three bare-ZWNJ entries and the 48 edge-ZWNJ entries must be dropped (or merged into the
  bare word).
- Sources behind `fa` in wordfreq: Wikipedia, OpenSubtitles, web, Twitter (4 sources, no
  "large" list); data licence CC BY-SA 4.0 (wordfreq README) [V].

## Words that differ only by the half-space

Yes, and they are systematic. A stem ending in ه plus the suffix `‌ای` ("a ..." / adjective)
has exactly the same letters as the stem without ه plus the plural `‌های`; only the half-space
position differs. Typed without a half-space, the string is ambiguous:

| ه + `‌ای` | meaning | stem + `‌های` | meaning | joined string, also listed |
|---|---|---|---|---|
| ساله‌ای 3.49 | an …-year-old | سال‌های 4.70 | years (of) | سالهای 4.60 |
| حرفه‌ای 4.58 | professional | حرف‌های 3.89 | words (of) | حرفهای 3.73 |
| نامه‌ای 4.23 | a letter | نام‌های 3.99 | names (of) | نامهای 3.61 |
| زمینه‌ای 3.53 | contextual / a field | زمین‌های 3.63 | lands (of) | زمینهای 3.48 |
| دسته‌ای 3.30 | a bunch / group | دست‌های 3.50 | hands (of) | دستهای 3.34 |
| نقشه‌ای 3.44 | a map | نقش‌های 3.50 | roles (of) | — |

(Zipf frequencies from wordfreq `fa` [V]; all twelve half-space forms are also entries in
HeliBoard's Leipzig-derived `fa` wordlist [V]; the meanings are basic vocabulary — Wiktionary
confirms حرف‌ها / نام‌ها as plurals and زمینه as "background, field" [V], the rest are not
separately verified.) These are the only classes in the 31k list whose members differ in the
*position* of a half-space; the seventh such class is the spelling variant `گفت‌وگو` /
`گفت‌و‌گو` (same word) [V].

**Correction (2026-10-22):** the sentence "`به‌روز` (up to date) vs `به روز` (to the day)" was
the wrong example; the attested contrast is `به‌روز` (up to date, 3.24) vs `بهروز` (the given
name Behrouz, 4.27; fa.wiktionary: نیک‌روز, a name [V]) — the very pair the Wikipedia style
page uses to forbid half-spacing («بهروز» را نباید «به‌روز» نوشت) [V]. See §3.

## CleverKeys today

**Correction (2026-10-22):** ~~`arab_pc_ir.xml` ("Persian PC") does not place it.~~ It does:
`<key key0="ت" key1="halfspace" key2="+"/>` — the half-space is the top-left corner gesture of
the ت key, identical to upstream Julow/Unexpected-Keyboard master [V]. It is also on the ق key
(key1) in `arab_pc_ckb.xml` and the ج key (key7) in `arab_pc_ckb_fa.xml` [V]. `KeyValue.kt:779`
maps `"zwnj", "halfspace" -> charKey(0xE018, '‌', 0)`. There is NO dedicated key in the
bottom row, and todo item 2 (2026-10-21 correction note) is wrong on this point.

- Word scanning is inconsistent, not uniformly "ZWNJ is a boundary" (details in §6):
  `Keyboard2View.isWordChar` (`isLetterOrDigit || '\''`) and `EditorTextNavigation.isWordCodePoint`
  (letter/digit/combining marks) treat U+200C as a boundary; **`PredictionContextTracker.isWordChar`
  already treats it as a word character** (not a letter, not a digit, not an apostrophe, not in
  `WORD_BOUNDARIES`, and `Character.isWhitespace(0x200C)` is false → falls through to `true`) [V].

## Recommendation (2026-10-21 draft — superseded by §7)

1. Put the half-space key beside the space bar on the Persian layout, as HeliBoard does.
2. Treat U+200C as part of a word for `fa`/Arabic-script layouts in word tracking, the bar and
   autocorrect, so compounds are single dictionary words.
3. Pack: keep half-space compounds as words; fold ي/ك → ی/ک and merge; drop the bare ZWNJ.
4. Do NOT autocorrect a joined spelling to the half-space form: the joined forms are real,
   frequent words in their own right (our rule: never rewrite a listed word). Instead offer the
   half-space form in the suggestion bar, and for the six-pair class offer both readings.
5. Bundle the decision on (2) with tests on the boundary code; it touches shared word-scanning
   paths used by every language.

---

## 1. Per-keyboard findings (2026-10-22)

| Keyboard | How ZWNJ is entered | Suggestions / autocorrect and ZWNJ | Mark |
|---|---|---|---|
| AOSP LatinIME (LineageOS 22.2) | Dedicated key right of the space bar for layout set `farsi` (also bengali_akkhor, kannada, nepali, telugu): `key_space_5kw.xml` → `zwnjKeyStyle` = `!icon/zwnj_key\|&#x200C;`, ZWJ on long-press | No `fa` dictionary shipped (`dictionaries/` has 34 languages, no fa). Composition: U+200C is neither a letter of SCRIPT_ARABIC nor in `symbols_word_connectors` (`'-`), and `values-fa` overrides only `suggested_punctuations` → the half-space ends the composed word | [V] |
| OpenBoard | Same `key_space_5kw.xml` rule (`farsi\|kannada\|nepali_romanized\|nepali_traditional\|telugu` → `zwnjKeyStyle`) | Same AOSP engine (not re-checked) | [V] key / [U] engine |
| HeliBoard | `LocaleKeyboardInfos.hasZwnjKey` = `fa, ne, kn, te`; `KeyboardParser` inserts `KeyLabel.ZWNJ` right of the first space key in alphabet layouts; `layouts/main/farsi.txt` itself has no ZWNJ (auto-added); `locale_key_texts/fa.txt` popups: ه→ﻫ هٔ ة, ی→ئ ي ﯨ ى, ک→ك, ا→آ ء ٱ أ إ | Dictionary `main_fa_ir__experimental.dict` (Codeberg, 2025-10-27, "experimental"): 1,520,799 word entries, **666,520 (43.8%) contain U+200C**, 0 contain Arabic ي/ك, built from Leipzig wordlists (CC BY 4.0). Contains both `آن‌ها`/`آنها`, `کتاب‌ها`/`کتابها`, `به‌روز`/`بهروز`, `گفت‌وگو`/`گفتگو`; `می‌شود` yes, `میشود` no. Its `ScriptUtils` Arabic branch is the AOSP one (no U+200C); the composition call site was not opened, so whether typed `می‌شود` matches that entry is unverified | [V] key+dict / [U] matching |
| FUTO Keyboard | Layout `ArabicScript/farsi.yaml` (`futo-keyboard-layouts` submodule): `useZWNJKey: true`; `TemplateOptionalZWNJKey` sits right of the space key in the default bottom row (`Keyboard.kt`), spec `!icon/zwnj_key\|‌` with ZWJ on long-press; the layout test `Farsi.java` expects `[language switch, space, ZWNJ(+ZWJ)]` | Not researched | [V] key / [U] engine |
| AnySoftKeyboard | `languages/persian/pack/src/main/res/xml/persian_qwerty.xml` (branch `master`): bottom row `… ، [8204 ".|."] space ؟ . enter` — a ZWNJ key labelled `.\|.` immediately left of the space bar | Not researched | [V] key |
| FlorisBoard | `persian2.json` has `{ "code": 8204, "label": "half_space" }` as a key in the third letter row; `persian.json` and `persian3.json` have no ZWNJ key | No Persian suggestions researched | [V] |
| Unexpected Keyboard (upstream) | `arab_pc_ir.xml` identical to ours: `key1="halfspace"` on ت (corner gesture), no dedicated key; `arab_pc_ckb` ق key1, `arab_pc_ckb_fa` ج key7 | Upstream has no suggestions | [V] |
| Gboard | A small half-space key beside the space bar on the Persian layout (fa.wikipedia فاصله مجازی: "در کنار سمت راست کلید فاصله کلید کوچکی"; sedastore FAQ: "یک خط تیره کوچک در کنار کلید فاصله"; mashreghnews; zoomit points at a key in a screenshot — image not inspected). Some guides also mention long-press on space | Whether Gboard's suggestions insert/keep the half-space: no source found | [U] — secondary sources only, no Google document |
| Microsoft SwiftKey | Android: "hold the space key and pick the half-space option" (sedastore, single secondary source); iOS: a "Zwnj" key in the bottom-left corner after switching to Persian (mashreghnews, 2021). A changelog snippet says SwiftKey added "ZWNJ and ZWJ to Hindi, Malayalam, Marathi, Punjabi and Tamil layouts" (apk mirror, not opened). No Microsoft support page found | Not found | [U] |
| Samsung Keyboard | A dedicated half-space key exists on some devices (Tab S9 FE+) and vanished from the S24 Ultra layout; another user's screenshot (inspected) circles a keyboard-toolbar icon and the OP confirms "turning it off brings back the semi space" (Samsung Members, 2024-25); with that feature on, the bottom row is `!#1, emoji, space, cursor key, ., enter` and has no half-space key. zoomit also points at a Samsung key in a screenshot (not inspected). Which setting hides it is not identified | Not found | [V] thread / [U] mechanism |
| Apple iOS | Conflicting: learnfarsi.app says hold the space bar; sedastore says the stock keyboard has no half-space and recommends Text Replacement; zoomit points at a button in a screenshot (not inspected) | — | [U] |

Notes.

- The dedicated-key placement is unanimous among the open-source Android keyboards that handle
  it (AOSP/OpenBoard/HeliBoard/FUTO/AnySoftKeyboard): **right of (or next to) the space bar in
  the bottom row, with ZWJ on long-press**. FlorisBoard's community layout puts it in a letter
  row. Only Unexpected Keyboard hides it in a corner gesture.
- None of the open-source engines was found to *insert or remove* ZWNJ in autocorrect. AOSP's
  composer cannot: the half-space terminates the word. HeliBoard's experimental `fa`
  dictionary is the only open keyboard dictionary that stores half-space compounds as single
  words, which is the direction of decision (b).

## 2. Desktop layouts and ISIRI 9147

- **ISIRI 9147** (Iranian national standard, 2007; table transcribed on en.wikipedia "ISIRI 9147",
  which cites the ISIRI PDF — PDF not opened): key B05 (US `B`, Persian ذ): Shift = U+200C,
  AltGr = U+200D; key SPC: Shift = U+200C, AltGr = U+00A0 NBSP; key E00 (US `~`): base = U+200D
  ZWJ. So the standard gives TWO ways to type ZWNJ: Shift+Space and Shift+B [V].
- **Windows "Persian (Standard)"** (`kbdfar.dll`, KLID 00050429, Windows 8+, also the default for
  Dari): kbdlayout.info shows ZWNJ on Shift+B and on Shift+Space, ZWJ on the `~` key [V].
  **Legacy Windows "Persian"** (`kbdfa.dll`, 00000429): the space key is plain space in all
  shown shift states; ZWNJ is Ctrl+Shift+2 (fa.wikipedia; Microsoft Q&A threads in search
  results, not opened), and that chord does not work on the Standard layout [V text / U chord].
- **macOS**: input sources "Persian – Standard" and "Persian – ISIRI 2901" (learnfarsi.app [V]);
  ZWNJ on Shift+Space (learnfarsi, fa.wikipedia, zoomit [V]; no Apple document opened [U]).
- **Linux/X11 `ir` layout**: Shift+Space or Shift+B (fa.wikipedia [V]; a Manjaro thread reports
  Shift+Space producing a plain space in some setups — snippet only [U]).
- The todo item's "Shift+B and similar" is therefore the ISIRI 9147 position: on our PC layout the
  B-position key is ذ, not ت where the corner gesture currently lives.

## 3. Minimal pairs: evidence

Three distinct kinds of contrast exist; they need different handling.

**(i) Half-space POSITION changes the word (joined string ambiguous).** The six ه‌ای / ‌های
classes in the table above, plus any other silent-ه stem that also exists without its ه. In
wordfreq `fa` these are the only position-ambiguous classes (7 of 31,389 incl. گفت‌وگو), but
every member is an everyday word (Zipf 3.3–4.7). Note the asymmetry: the *spaced* typing
`نامه ای` vs `نام های` is NOT ambiguous (the split position is preserved); only the fully
joined `نامهای` is. HeliBoard's Leipzig list contains all twelve half-space forms [V].

**(ii) Half-space vs joined changes the word.** `به‌روز` (up to date) vs `بهروز` (name) — in
both wordfreq (3.24 / 4.27) and the HeliBoard list, and named by the Wikipedia style page as a
case where the half-space must not be used; same source lists `بهنام`/`به‌نام`, `بهیار`/`به‌یار`
[V]. Beyond `به‑`, meaning-changing joined/half-spaced pairs were not found in the corpora
checked: `پیش‌رفت`, `گل‌خانه`, `شاه‌نامه`, `یک‌دست`, `سر‌انجام` have Zipf 0.00 in wordfreq while
the joined forms are common (4–5), i.e. writers simply do not half-space lexicalised compounds
[V]. Most joined/half-space pairs are the SAME word in two spellings (692 pairs; `میشود`/`می‌شود`,
`آنها`/`آن‌ها`, `بی‌نهایت`/`بینهایت`, `گفتگو`/`گفت‌وگو`).

**(iii) Half-space vs full space changes the word.** Compound-vs-phrase pairs from the style
page: `یکدست` (uniform; Wiktionary "pure, homogeneous" [V]) vs `یک دست` (one hand), `دست‌کم`
(at least, 3.98; also `دستکم` 3.61) vs `دست کم`, prefixed verb vs noun+verb (`سر رسید`) [V page
/ U individual glosses except یکدست]. These are bigram phenomena; a unigram list cannot measure
them, and they are exactly what a space→half-space rewrite could corrupt.

How common, and can autocorrect corrupt meaning?

- Half-space words are 7.5% of the wordfreq list and 43.8% of a formal-text (Leipzig) list; the
  informal joined forms are the more frequent spelling for about half the pairs. So any
  Persian user will type both styles, and a dictionary that lacks either will misfire.
- A rewrite joined→half-space is unsafe twice over: it changes a listed word, and for class (i)
  it must *guess* the split (`نامهای` → `نامه‌ای` or `نام‌های`).
- A rewrite half-space→joined (or space→half-space) is unsafe for class (ii)/(iii) (`به‌روز` →
  `بهروز`, `یک دست` → `یک‌دست`) and for the `است`/pronounced-ه rules.
- A rewrite space→half-space is position-preserving, so it cannot cause (i); it can still
  cause (iii) and violates the `است` rule. Hazm's normaliser does it anyway for a closed list
  (`می `, `نمی `, ` ها`, `ه ی `) — it is a corpus-cleaning tool, not an IME [V].

## 4. Arabic ي/ك vs Persian ی/ک, heh and hamza forms: what others do

- **Unicode**: ي/ی (U+064A/U+06CC), ك/ک (U+0643/U+06A9), ة/ه (U+0629/U+0647), ە (U+06D5) are
  canonically distinct — NFC and NFKC leave all of them alone [V, `unicodedata`]. ۀ (U+06C0)
  decomposes to **U+06D5 U+0654** (Kurdish/Uyghur AE + hamza above), NOT to ه + ٔ; so the
  precomposed ۀ and the sequence هٔ (U+0647 U+0654, the form HeliBoard's ه popup offers) never
  become equal under normalisation [V]. en.wikipedia: in Persian ۀ/هٔ is not a letter but the
  ezafe mark on a final silent ه («خانهٔ من») [V].
- **CLDR `fa` collation** (`common/collation/fa.xml`): `&ک<<*ڪګكڬڭڮ`, `&ی<<*ىےيېۑۍێ`,
  `&ۏ<ه<<ە<<ہ<<ة<<ۃ<<ۀ<<ھ` (all SECONDARY, i.e. accent-level differences, so ICU sorting and
  search in Persian treat ي≈ی, ك≈ک, ة≈ۀ≈ە≈ه), but `&[before 1]ا<آ` — آ is a distinct PRIMARY
  letter from ا, and hamza carriers ء أ إ ؤ ئ are ordered after ا as separate elements [V].
- **Lucene / Elasticsearch `persian` analyzer** (search-time folding, lossy by design): a char
  filter maps **U+200C → space**, then `arabic_normalization` (hamza-seated alef → bare alef,
  ة → ه, ى → ي, strip harakat and tatweel) and `persian_normalization` (farsi yeh and yeh
  barree → arabic yeh, keheh → kaf, heh+hamza forms and heh goal → heh), then stop words and
  `persian_stem` [V, ES docs + Lucene 9.11 javadoc]. Two of its choices are wrong for an IME:
  splitting at the half-space (collapses `نامه‌ای`/`نام‌های`) and folding آ→ا (`آب`/`اب`).
- **Hazm** (Persian NLP, MIT): removes stray ZWNJs at word edges and doubled ZWNJs; `affix_spacing`
  rewrites `می `/`نمی ` → `می‌`, `ه ی ` → `ه‌ی `, `ه`+`ها` → `ه‌ها` [V, `constants.py`]. Its
  Arabic→Persian letter mapping was not checked in this pass [U].
- **Google**: no public statement on ي/ی folding in Search was found [U]. A Google-authored
  paper (Doctor, Gutkin, Johny, Roark, Sproat, "Graphemic Normalization of the Perso-Arabic
  Script", 2022, arXiv 2210.12273) documents per-language normalisation tables and measures
  gains from applying them; the abstract was opened [V], the ي→ی table itself is in the PDF
  (snippet only) [U].
- **Keyboards**: HeliBoard's `fa` wordlist has zero Arabic ي/ك (its Leipzig source is clean);
  FrequencyWords `fa_50k` (OpenSubtitles) has Arabic letters on 18,226 of 50,000 lines [V].
  HeliBoard and the Persian PC layouts type only ی/ک; ي/ك arrive via popups or paste.
- **CleverKeys today**: `normalizeForPrediction` = NFD + strip `\p{Mn}` + lowercase. Consequences
  for Persian [V by reading the code and the decompositions]: harakat are stripped (good);
  the ezafe hamza is stripped, so `خانهٔ` → `خانه` (acceptable) but `خانۀ` (U+06C0) → `خانە`
  with U+06D5 — a Kurdish letter, so it will never match `خانه` in a dictionary. A Persian
  normaliser must map U+06C0 → ه before/independently of NFD.

## 5. Open wordlists and corpora with correct half-spaces

| Source | What | ZWNJ / Arabic letters (measured) | Licence | Mark |
|---|---|---|---|---|
| Leipzig Corpora Collection `fas` | News 2016–2024 (10K–1M sentences), Newscrawl 2011–2019 (to 1M), Web-Public `fas-ir` 2019 (to 1M), Wikipedia 2010/2012/2014 (to 1M); sentence files + word counts | Preserves ZWNJ, inferred from HeliBoard's list derived from it (43.8% ZWNJ words, 0 Arabic ي/ك); the raw Leipzig archives were not downloaded | Download corpora **CC BY 4.0** (web/app data CC BY-NC); already attributed in NOTICE for en/de/fr/it | [V] catalogue+terms / [V-via-HeliBoard] content |
| HeliBoard `aosp-dictionaries` (Codeberg) `wordlists/main_fa_IR__experimental.combined` | 1,520,799 words with 0–255 frequencies + bigrams, "experimental", 2025-10-27 | 666,520 ZWNJ words; 0 Arabic ي/ك; 1 edge-ZWNJ entry | "source lists under CC BY 4.0" (Leipzig) | [V] |
| `wordfreq` `fa` | 31,389 words, Zipf; Wikipedia+subtitles+web+Twitter | 2,347 internal-ZWNJ, 48 edge artefacts, 3 bare; 2,024 Arabic-letter duplicates | Data CC BY-SA 4.0 (already used for other packs) | [V] |
| hermitdave FrequencyWords `content/2018/fa` | `fa_50k.txt` 764 KB, `fa_full.txt` 7.2 MB (OpenSubtitles 2018) | **0 ZWNJ** in the 50k list; Arabic ي/ك on 18,226/50,000 lines | "CC-by-sa-4.0 for content" | [V] — unusable without rebuilding from the subtitles |
| Hazm `hazm/data/words.dat` | 193,751 entries: word, count, POS | 13,278 ZWNJ; 2,818 Arabic ي/ك | MIT (repo); count provenance undocumented | [V] data / [U] provenance |
| Lilak (`b00f/lilak`) `src/data/lexicon` + `affixes` | ~93.9k lexicon lines with POS, Hunspell affix rules that carry ZWNJ (prefix `بی‌`, suffixes `‌ام ‌ات ‌اش ‌مان …`); no frequencies | 32,901 ZWNJ lines; 0 Arabic ي/ك | Apache-2.0 (LICENSE + README) | [V] |
| Persian Wikipedia dumps | full text | — | CC BY-SA 4.0 + GFDL (dumps.wikimedia.org/legal) | [V] |
| Bijankhan | ~2.6M POS-tagged tokens (news), Univ. of Tehran | — | "not free for commercial use" (en.wikipedia) → not shippable | [V] |
| Hamshahri v2 | 323,616 news articles 1996–2007, 1.42 GB XML | — | Licence not stated on the pages opened; DBRG site archived → treat as unavailable | [U] |

**Implemented in `479a7277` (2026-10-23):** `langpack-fa.zip`, 50,000 words, from Leipzig `fas`
news 2016-18, news crawl 2019 and Wikipedia 2014 blended with wordfreq, with Lilak as the
oracle (`docs/eval/2026-10-23-fa-wordlist.md`). **Correction:** Leipzig does NOT preserve ZWNJ
throughout — news 2020-2024 and web-public 2019 have every U+200C stripped (0 of 50,000
sentences), so the row above holds only for the older corpora; HeliBoard's list was not needed.

Practical reading: a `fa` pack can be built the way the other packs are, from **Leipzig `fas`
(news + web + wikipedia) blended with wordfreq**, which gives correct half-spaces, a clean
alphabet and a licence chain the project already carries. Lilak is the right cross-check for
"is this half-space form a real word" (its affix rules encode the orthography). FrequencyWords
and Hazm's list must not be used as-is.

## 6. CleverKeys word-boundary code (input to decision (b))

`rg -n isWordChar src/main/kotlin` gives two definitions; the behaviourally equivalent checks
elsewhere are listed too. "ZWNJ" = how U+200C is classified today [V].

| Site | Rule | ZWNJ today |
|---|---|---|
| `Keyboard2View.isWordChar` (2355), used by `wordAtCursor` (select/word-at-cursor) | `isLetterOrDigit \|\| '\''` | boundary |
| `PredictionContextTracker.isWordChar` (820), used by `extractWordPrefix`/`extractWordSuffix` (cursor-sync, the composing word) | letter → true; digit → false; apostrophe only between letters; else `!in WORD_BOUNDARIES && !isWhitespace` | **word char** (falls through) |
| `EditorTextNavigation.isWordCodePoint` (281) (editor word navigation/selection) | `isLetterOrDigit \|\| Mn/Mc/Me` | boundary |
| `VerifiedSuffixEdit.isWordContinuation` (43) (owned-tail check) | letter/digit, marks, `'’ʼ-‐‑`, surrogates | boundary |
| `SuggestionHandler.isIntraWordJoiner` (72) + `trimJoiners` (87); `isWordStart` (3039) | `' ’ -` | not a joiner |
| `NextWordPredictor` tokeniser (420) (bigram context) | `isLetter \|\| ' \|\| -` | splits `می‌شود` into `می`,`شود` |
| `swipe/UserJoinerPreference.JOINERS` (57), `ctc/CtcScriptProjection.JOINERS` (52), `ctc/CtcAzProjection.JOINERS` (33) | `' ’ -` (+ `ʼ ‘ \`` in CTC) | not a joiner |
| `swipe/geometric/LayoutProjection` (223) | 0x200C/0x200D/0x0640 are default-ignorable for key projection | ignored (correct) |
| `KeyEventHandler` 430/516, `InlineSwipeInput` 110, `EmojiSearchManager` 338/342, `SwipeCorrectionTracker` 216/225 | `isLetterOrDigit` | boundary |
| `ui/settings/SettingsSearchMatch` (31) | strips U+200C/U+200D before matching | — (good precedent) |

So today the composing word (`PredictionContextTracker`) can be `می‌شود` while word selection,
suffix ownership and the bigram tokeniser see `شود`. That inconsistency is a latent bug for any
script that uses U+200C (Persian, Kurdish `arab_pc_ckb*`, Sinhala — `sinhala_phonetic.xml`
already binds `zwj`/`zwnj` on shift), independent of decision (b).

**Script-scoped alternative.** Keep `isLetterOrDigit` as the universal rule and add ONE shared
predicate, e.g. in a small `WordChars` object:

```
isWordChar(text, i) = text[i].isLetterOrDigit()
    || isApostropheBetweenLetters(text, i)              // existing rule
    || (text[i] == '‌' && isArabicLetter(prev) && isArabicLetter(next))
```

with `isArabicLetter(c) = Character.UnicodeScript.of(c) == ARABIC` (or the AOSP block ranges).
Properties: no effect on Latin/Cyrillic/Greek/Hebrew (the neighbours are never Arabic-script),
no effect on Indic/Sinhala ZWNJ (left as today; can be added per script later), no new state,
and it mirrors the apostrophe-between-letters precedent already in `PredictionContextTracker`.
The cursor-edge case (ZWNJ just typed, nothing after it yet) needs the same look-across
`beforeText`/`afterText` handling the apostrophe code has at `i == lastIndex`.

Callers to switch to it: `Keyboard2View.isWordChar`, `PredictionContextTracker.isWordChar`,
`EditorTextNavigation.isWordCodePoint`, `VerifiedSuffixEdit.isWordContinuation`,
`NextWordPredictor`'s tokeniser, `SuggestionHandler.isIntraWordJoiner`/`isWordStart`, and the
auto-space check at `KeyEventHandler:430`. The swipe side needs U+200C added to the joiner
sets that are stripped from lexicon words for path matching (`CtcScriptProjection`,
`UserJoinerPreference`) so `می‌شود` can be swiped as `میشود` and committed with the half-space.
Dictionary lookup needs an exact key (ZWNJ significant) plus a ZWNJ-stripped fallback for
candidate generation (so `میشود` can *offer* `می‌شود`) — and `normalizeForPrediction` must map
U+06C0 → ه and ي/ك → ی/ک before NFD (see §4).

## 7. Recommendations for (a)–(d), with cost and risk

**(a) ZWNJ key — yes, a dedicated key beside the space bar, keep the corner gesture.**
Every open Android keyboard that handles Persian puts it right of (AOSP/HeliBoard/FUTO) or left
of (AnySoftKeyboard) the space bar with ZWJ on long-press; Gboard and Samsung users describe the
same thing. The existing corner gesture on ت is so undiscoverable that our own todo item missed
it. If ISIRI parity is wanted for the gesture, the standard's letter position is ذ (US `B`),
not ت. Cost: `srcs/layouts/arab_pc_ir.xml` (and `arab_pc_ckb_fa.xml`) only; the `halfspace`
key, glyph 0xE018, a11y label and the command already exist. Risk to other languages: none.
Open sub-question for the maintainer: whether the key should also appear on `arab_pc.xml`
(Arabic proper rarely needs it) — recommend no.

**Implemented (a) in `9aac669d` (2026-10-23):** a `halfspace` key right of space with `zwj` on
swipe-up, in an embedded bottom row on `arab_pc_ir.xml` and `arab_pc_ckb_fa.xml`
(`bottom_row="false"`; the shared `bottom_row.xml` and `arab_pc.xml` are unchanged); the ت / ج
corner gestures stay. Pinned by `PersianHalfSpaceKeyTest`. The pack side of (c) — fold and merge
in the build — is in `479a7277`.

**(b) ZWNJ as a word character — yes, but script-scoped as in §6, never globally.**
Half-space compounds are 7.5–44% of Persian vocabulary; without (b) the bar can never show
`می‌شود`, user words cannot contain it, and the bigram context is split mid-word. AOSP chose the
opposite (half-space ends the word) and consequently never shipped Persian suggestions;
HeliBoard's own `fa` dictionary stores the compounds as single words. Cost: one shared
predicate, ~8 call sites, plus the swipe-joiner sets and the lookup normaliser; tests on the
boundary matrix (en apostrophes, de hyphens, Sinhala ZWJ/ZWNJ, emoji ZWJ sequences, RTL
selection). Risk: the predicate changes nothing unless both neighbours are Arabic-script
letters, so Latin packs are unaffected by construction; Kurdish layouts gain the same
behaviour (desired). The present `PredictionContextTracker` inconsistency should be fixed in the
same change whichever way (b) goes.

**(c) Fold ي/ك → ی/ک — yes for the pack and for lookup; do not rewrite the user's text.**
Pack: merge the 1,981 duplicate entries into the Persian spelling (the Persian twin is already
the more frequent one in 1,977 cases). Lookup: normalise ي→ی, ك→ک, ۀ (U+06C0) → ه, ة → ه, ە
(U+06D5) → ه for the *key* only — this matches CLDR's secondary equivalences and every
reference normaliser — and leave the editor text as typed (a pasted Arabic ك stays ك). Do NOT
fold آ→ا or the hamza carriers (ء أ إ ؤ ئ): CLDR keeps آ a separate primary letter and `آب`/`اب`
are different words; Lucene's `arabic_normalization` folds them because search tolerates it,
an IME should not. Cost: a few lines in the normaliser plus pack-build dedupe. Risk: none to
other languages if the mapping is applied only to Arabic-script code points (it is a no-op
elsewhere).

**(d) Autocorrect into the half-space form — no silent rewrite in either direction; offer only.**
Evidence: joined forms are listed words and the more frequent spelling in 365/692 pairs; the
joined string is position-ambiguous for the ه‌ای/‌های class; `به‌روز`/`بهروز` and `یک‌دست`/`یک دست`
show that both half-space insertion and removal can change meaning; the `است` and pronounced-ه
rules are lexical, not string rules. Concretely: (1) never autocorrect joined→half-space;
show the half-space spelling as a bar candidate (via the ZWNJ-stripped fallback lookup) and
let the user's choice be learned per `UserJoinerPreference`-style preference; (2) never
autocorrect half-space→joined; (3) the only rewrite worth considering later is the
position-preserving space→half-space for a closed, high-confidence list (`می `/`نمی ` + verb,
` ها`/` های` after a noun, `‌ای` after ه) and only as an *offered* candidate, never on space
commit, never for `است`, and never when the right-hand token is itself a listed word (this is
what blocks `یک دست`); ship (3) only after a replay on Persian human text, as the project's
rule for ranking changes requires. Risk to other languages: none; all of this is gated on the
`fa` pack / Arabic-script word predicate.

## Sources opened (2026-10-22)

Code and data: CleverKeys `srcs/layouts/arab_pc_ir.xml`, `arab_pc_ckb.xml`, `arab_pc_ckb_fa.xml`,
`KeyValue.kt`, `Keyboard2View.kt`, `PredictionContextTracker.kt`, `EditorTextNavigation.kt`,
`VerifiedSuffixEdit.kt`, `SuggestionHandler.kt`, `NextWordPredictor.kt`, `LayoutProjection.kt`,
`SettingsSearchMatch.kt`, NOTICE; Julow/Unexpected-Keyboard `srcs/layouts/arab_pc_ir.xml`
(master); LineageOS `android_packages_inputmethods_LatinIME` lineage-22.2: `ScriptUtils.java`,
`RichInputConnection.java`, `SpacingAndPunctuations.java`, `key_styles_common.xml`,
`key_space_5kw.xml`, `row_qwerty4.xml`, `rows_farsi.xml`, `values/` and `values-fa/`
`donottranslate-config-spacing-and-punctuations.xml`, `dictionaries/` listing; openboard
`rows_farsi.xml`, `key_space_5kw.xml`; Helium314/HeliBoard `layouts.md`, `KeyboardParser.kt`,
`LocaleKeyboardInfos.kt`, `ScriptUtils.kt`, `SpacingAndPunctuations.java`,
`layouts/main/farsi.txt`, `locale_key_texts/fa.txt`; Codeberg Helium314/aosp-dictionaries
README, `dictionaries/`, `wordlists/main_fa_IR__experimental.{combined,source}`;
futo-org/android-keyboard `TemplateKeys.kt`, `Keyboard.kt`, `tests/.../Farsi.java`;
futo-org/futo-keyboard-layouts `ArabicScript/farsi.yaml`; AnySoftKeyboard/LanguagePack
`languages/persian/.../persian_qwerty.xml` (master); florisboard `persian{,2,3}.json`,
`extension.json`; unicode-org/cldr `common/collation/fa.xml`; roshan-research/hazm
`normalizer.py`, `constants.py`, LICENSE, `words.dat`; b00f/lilak README, LICENSE, `src/data/`
(affixes, lexicon); hermitdave/FrequencyWords README and `content/2018/fa/`; rspeer/wordfreq
README; wordfreq `fa` list and Python `unicodedata` / Java `Character` on this machine.

Pages: en.wikipedia "ISIRI 9147", "Bijankhan Corpus", "Hamshahri Corpus", "Hā with hamza
above"; fa.wikipedia "فاصله مجازی", "ویکی‌پدیا:نیم‌فاصله"; kbdlayout.info `kbdfar`, `kbdfa`;
Lucene 9.11 javadoc `PersianCharFilter`, `PersianNormalizer`, `ArabicNormalizer`; Elasticsearch
"Language analyzers" (persian); Wortschatz Leipzig terms of usage, downloads, `download/fas`;
dumps.wikimedia.org/legal; arXiv 2210.12273 abstract; Samsung Members "Semi-space in Samsung
Keyboard" (and its screenshot); zoomit.ir, sedastore.com, blog.faradars.org, mashreghnews.ir,
learnfarsi.app half-space guides; gsm.ir Gboard tips (one half-space mention, no key location).

Not opened (snippets only, hence [U]): Reddit r/farsi threads (blocked), Microsoft Q&A and
SwiftKey pages, Apple documentation, the ISIRI 9147 PDF, the arXiv PDF body, Hamshahri's
licence page.
