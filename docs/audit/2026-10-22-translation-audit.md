# Translation audit (2026-10-22)

Scope: `res/values/strings.xml` (English) and the 21 locale `strings.xml` files. These are the
only resource files with translatable text: `layouts.xml` arrays are layout IDs and names and
are not translated per locale. The audit was done by a model, not a native speaker. The
mechanical findings below are certain. The wording findings are leads for the native-speaker
review in `memory/todo.md` "Open now" item 10.

## 1. Tool matrix

| Tool | What it catches | Runs here? | Install / command | Used |
|---|---|---|---|---|
| Android Lint translation checks | MissingTranslation, ExtraTranslation (error/fatal), StringFormatInvalid, StringFormatMatches (error), StringFormatCount (warning), Typos (warning, about 10 languages incl. de es fr it nl pt ru hu), PluralsCandidate / UnusedQuantity (warning), MissingQuantity / ImpliedQuantity (error), Untranslatable | Yes, but about 45 min on Termux. CI runs it on every PR (`ci.yml` lint, `release.yml` lintDebug). | `scripts/gradle-guard.sh lintDebug`; report in `build/reports/lint-results-debug.xml` | Read the last local report (2026-10-07) |
| translate-toolkit 3.x (`android2po` + `pofilter`) | 47 filters: printf, variables, xmltags, accelerators, doublespacing, endpunc, startpunc, untranslated, unchanged, long/short, brackets, quoting, numbers, nplurals, spellcheck (enchant) | Yes, with one workaround. The `unicode_segmentation_rs` wheel misses `PyModule_Type` on bionic, so preload libpython. | `python3.12 -m venv --system-site-packages v && v/bin/pip install translate-toolkit`; then `LD_PRELOAD=$PREFIX/lib/libpython3.12.so v/bin/android2po -t res/values/strings.xml res/values-de/strings.xml de.po` and `v/bin/pofilter --language=de de.po out.po` | Yes, all 21 locales |
| Weblate quality checks | Same family as pofilter, plus same-plurals, ICU MessageFormat, max-length, glossary-consistency, inconsistent-across-units | Not practical: a Django server (PostgreSQL, Redis). Most of its checks are pofilter's. | Hosted Weblate (third party: maintainer decision), or a self-hosted container elsewhere | No |
| LanguageTool | Grammar, agreement and punctuation rules for about 30 languages (ru uk pl de fr es pt nl it fa ja zh ro…) | Yes in principle: Java 21 is installed. Needs a separate download (about 250 MB) and about 1.5 GB of RAM for the local server. | `java -cp languagetool-server.jar org.languagetool.server.HTTPServer --port 8081`, then POST each string to `localhost:8081/v2/check` | No. Sending strings to the public api.languagetool.org would be a third-party upload: **maintainer decision** |
| Hunspell | Spelling of unknown words | Yes. Installed dictionaries: en_US, fr_FR, nl_NL, ru_RU. pacman also has hunspell-hu. No package for most other locales; LibreOffice dictionaries can be dropped into `$PREFIX/share/hunspell`. | `hunspell -d fr_FR -l strings.txt` | Yes (fr, nl, ru) |
| ICU / CLDR plural rules | Plural categories each locale needs (one/few/many/zero) | Yes, via `babel` (CLDR data, pure Python) | `v/bin/pip install babel`; `Locale.parse('ru').plural_form` | Yes. The checker's rule table matched babel for every integer 0–1000 and 10⁶ in all 21 locales. |
| Glossary / terminology | Same concept translated two ways | Yes: `TranslationGlossaryTest` with `docs/i18n/glossary.json` (key-scoped), plus a corpus-wide lift probe written for this audit | Throwaway `terms.py` (stems frequent in strings whose English contains the term) | Yes |
| Pseudo-localisation | Truncation and hard-coded text (en-XA), RTL mirroring (ar-XB) | Already enabled for debug builds (`pseudoLocalesEnabled true`). Needs a device run. | Install a debug build, set the device language to "English (XA)" / "Arabic (XB)" (Developer options) | Not run in this audit (device step) |
| Back-translation by a model | Meaning drift, omissions, wrong terms, register | Yes | 7 parallel model reviewers, 3 locales each, blind pass then compare | Yes, 255 entries × 21 locales |

## 2. Commands run

Throwaway scripts live in the session scratchpad and are not committed:

- `check.py` — XML-parses every locale against English. It checks coverage, the kind of each
  resource, argument signatures, literal `%` against `formatted`, escaping, tags,
  whitespace and `\n` parity, identical-to-English (split into brand/technical, proper names
  and the rest), length above 1.6× on short labels, Latin words inside fa/ru/uk/ja/ko/zh
  strings, and plural categories against CLDR.
- `terms.py` — the terminology-drift probe.
- android2po + pofilter for all 21 locales.
- `hunspell -l` for fr, nl and ru.
- babel's CLDR plural rules, compared with the checker's rule table.
- 7 back-translation reviewers over the 255 resource entries (244 keys) added or changed
  since 2026-10-01.
- `scripts/gradle-guard.sh runPureTests -PtestClass=TranslationFormatParityTest` (new) and
  the three existing Translation* tests.

## 3. Findings

### 3.1 Clean: no finding in any locale

- Missing or extra keys, and `<string>`/`<plurals>` kind mismatches: 0. All 21 locales carry
  every translatable resource.
- Placeholder parity, by count, index and type, in every `<string>` and in every plural
  `other` item: 0. pofilter's "printf" hits (hu 18, ko 7, tr 5) are false positives: `%-a`,
  `%로` and `%'si` are literal percents inside `formatted="false"` strings.
- Plural categories against CLDR: 0 missing and 0 unused. cs/pl/ru/uk have one/few/many,
  lv has zero/one, ro has one/few, es/fr/it/pt have the CLDR 42+ "many" for 10⁶. No item for
  a quantity that covers several integers (ru/uk/lv `one`, lv `zero`, fr/pt/fa `one`)
  drops the count. Some reviewers reported missing few/many in pl/ro/ru/uk. That came from the
  review sheet, which listed only the English one/other items; the real files have the forms.
- HTML/xliff tag parity: 0 (no inline markup in the base). Leading or trailing raw whitespace:
  0. `\n` / ` ` parity: 0. Unescaped apostrophes: 0.

### 3.2 Fixed: mechanical defects (commit `0a8fc939`)

| Defect | Locales | Strings |
|---|---|---|
| Base is `formatted="false"`, locale is not (lint `StringFormatCount` on de/es/in/it already flagged it) | cs de es hu in it ja ko lv | `calibration_tap_swipe_desc`, `calibration_boundary_desc` (18) |
| Literal `%` without `formatted="false"` (the vi "% chiều" CI-failure shape; vi already had the attribute) | ru uk | `clipboard_pane_height_desc` (2) |
| Unescaped `"` (aapt2 removes it, so the quotes never show) | **en** and 15 locales | `dict_word_too_long_for_swipe_msg` (en de es it nl pt, every item), `short_swipe_customize_key` (cs fa fil in lv nl pl), `clipboard_private_copy_other_apps_desc` (cs fil in it lv nl pl pt ro), `input_capitalize_i_desc` (fil in it lv nl pt ro), `input_capitalize_i_title` (it lv pt ro), `gesture_sensitivity_preset_desc` (it lv) — 32 elements, 44 items |

Per locale (resource items fixed): en 2, cs 4, de 4, es 5, fa 1, fil 3, hu 2, in 5, it 9, ja 2,
ko 2, lv 7, nl 6, pl 2, pt 6, ro 3, ru 1, uk 1. The fixes add `\"` or `formatted="false"` and
change no wording.

### 3.3 Heuristic findings per locale (not defects by themselves)

"Identical (review)" counts values equal to English that are neither brand/technical tokens
nor proper names: diacritic, key, theme and layout names. "Length" counts short labels
(≤ 30 characters, not `_desc`/`_message`/`_body`) longer than 1.6× English and at least 6
characters longer. "Strict" means English ≤ 20 characters, ratio ≥ 2.2 and translation ≥ 18
characters. Code-point length cannot see CJK width, so ja/ko/zh show 0.

| locale | identical (review) | identical (names) | identical (brand/tech) | length >1.6× | strict | Latin words in non-Latin strings |
|---|---|---|---|---|---|---|
| cs | 23 | 20 | 28 | 70 | 8 | – |
| de | 30 | 37 | 20 | 143 | 2 | – |
| es | 14 | 40 | 14 | 173 | 7 | – |
| fa | 7 | 4 | 16 | 37 | 2 | 108 |
| fil | 124 | 84 | 36 | 126 | 2 | – |
| fr | 22 | 37 | 18 | 225 | 13 | – |
| hu | 19 | 35 | 20 | 166 | 14 | – |
| in | 35 | 44 | 34 | 62 | 3 | – |
| it | 20 | 40 | 21 | 127 | 8 | – |
| ja | 12 | 10 | 20 | 0 | 0 | 136 |
| ko | 7 | 4 | 12 | 0 | 0 | 115 |
| lv | 13 | 37 | 21 | 144 | 7 | – |
| nl | 31 | 47 | 29 | 94 | 6 | – |
| pl | 22 | 26 | 27 | 101 | 4 | – |
| pt | 25 | 44 | 29 | 142 | 8 | – |
| ro | 37 | 49 | 27 | 125 | 6 | – |
| ru | 16 | 8 | 23 | 128 | 11 | 151 |
| tr | 16 | 31 | 19 | 60 | 5 | – |
| uk | 16 | 11 | 23 | 105 | 8 | 155 |
| vi | 18 | 35 | 28 | 46 | 4 | – |
| zh-rCN | 5 | 3 | 16 | 0 | 0 | 144 |

What these heuristics showed:

- **Identical to English.** These are mostly cognates or format-only strings (`Layout %1$d`,
  `Status`, `Normal`, `Intent: %1$s`). fil (124) is the recorded Taglish policy
  (`docs/i18n/2026-10-08-coverage-audit.md`).
- **Length.** The strict cases are ordinary expansions in the target language ("Autofill" →
  "Automatické vyplňování", "Show Numpad" → "Numerikus billentyűzet megjelenítése", "Cycle
  Case" → "Groß-/Kleinschreibung wechseln"). Whether they truncate is a device question;
  see §5 step 4.
- **Latin words in non-Latin strings.** These are key names (Backspace, Caps Lock, Insert,
  Compose), format examples (`HH:mm`, `YYYY-MM-DD`, SimpleDateFormat), typo examples (teh,
  helo) and product names (Material You, Monet, ClearURLs, logcat). For review: ru/uk/zh
  "Intent" (12/12/12) and ru/uk "Playground"; zh `hamza`/`alef` (diacritic names); ja "Todo"
  / "ToDo" / "To-Do" (three spellings).
- **Hunspell** (fr 302, nl 639, ru 475 distinct unknowns). Nearly all are brands, technical
  terms or compounds; ru "свайп*" is the deliberate glossary term. No real misspelling was
  confirmed.
- **pofilter.** Beyond the printf false positives above, its hits (endpunc, puncspacing,
  brackets, doublequoting, simplecaps) are typographic and language-specific. Its
  "unchanged" matches the identical counts. zh-rCN has 1 "endwhitespace": a full-width
  punctuation case, not a raw space.

### 3.4 Terminology drift (corpus-wide, beyond the glossary's keyed strings)

| locale | term | split | note |
|---|---|---|---|
| es | Layout | distribución 34 / diseño 21 | known glossary split, still growing |
| uk | Layout | макет 51 / розкладка 5 | known split; розкладка is the usual Ukrainian keyboard term |
| nl | Layout | lay-out 39 / indeling 16 | known split |
| vi | Clipboard | bộ nhớ tạm 56 / bảng tạm 19 / khay nhớ tạm | known split, plus a third form in the Oct strings |
| fa | Clipboard | بریده‌دان 69 / کلیپ‌بورد 7 | new: `cmd_clear_clipboard*`, `system_clipboard_*`, `clipboard_swipe_delete_title`, `clipboard_media_enabled_desc` |
| in | Clipboard | clipboard 70 / papan klip 6 | new: the same October keys |
| fr | Settings | paramètres 40 / réglages 8 | |
| pl | Swipe | gest* 112 / przesuw* 13 / przeciąg* 7 | `continuous_swipe_*`, `swipe_enable_desc`, `swipe_trail_enable_desc` (przesuń for an entry swipe is correct) |
| zh-rCN | Swipe | 滑动 121 / 滑行 (continuous_swipe_*, layout_language_dialog_desc) | |
| cs es it lv zh-rCN | Autocorrect | two forms each (e.g. it correzione automatica 10 / autocorrezione 4; zh 自动纠错 / 自动纠正 / 自动更正) | |
| de hu in it pl ro ru tr lv vi ja zh | Pinned / Todos in `cmd_clear_clipboard_desc` | uses other words than the clipboard tabs (e.g. it *elementi bloccati* = "locked", ro *activitățile*, hu *kitűzések / feladatok*) | in a "what is kept" sentence; see §4 |

## 4. Needs a native speaker (todo item 10)

Back-translation of the strings added since 2026-10-01 found **no HIGH (meaning-reversing)
drift in any locale**. `autocorrect_starting_letter_desc`, `appearance_border_radius_desc` and
the `clipboard_swipe_*` strings are correct in all 21. The concerns below are ordered by user
impact:

1. **`cmd_clear_clipboard_desc`, many locales.** The words for "pins" and "todos" differ from
   the clipboard tab names (it *bloccati*, hu, ro, ru *задачи*, lv, pl, vi *nhật ký* for
   history, zh 置顶 vs 已固定, ja ToDo). This sentence tells users what survives clearing.
2. **`clipboard_selection_clear`, es/hu.** "Borrar selección" / "Kijelölés törlése" read as
   *delete the selection* next to the bulk-delete button. English means deselect.
3. **fa arrows in RTL.** `autocorrect_channel_alias_bonus_desc` "(donr ← don't)",
   `autocorrect_short_deletion_scope_desc` "(tpo ← to)" and `cmd_case_cycle_desc` show a
   reversed or misleading direction. Confirm on a device.
4. **Grammar in plural items.** ru `dict_legacy_freq_notice#other` "пользовательского слова"
   and ru `clipboard_delete_selected_title/result#other` "выбранного фрагмента": the adjective
   case after a count. Check pl `#other` items the same way.
5. **Backspace rendered as "undo"** in tr/pl `cmd_append_possessive_desc`,
   `cmd_append_apostrophe_desc` ("bir geri alma", "cofnięcie").
6. **`command_palette_template_help` in cs de ko pl ru tr uk vi zh.** "Stay literal" (not
   expanded) became "remain unchanged".
7. **`autocorrect_channel_max_correction_cost_desc` in about 12 locales.** "than this" was
   dropped, so the threshold is only implied.
8. **Wrong terms.**
   - uk `autocorrect_short_deletion_scope_desc` uses *письменностей* where it means
     *писемностей* (scripts).
   - ja `advanced_custom_terminal_title` uses 端末 (device) for "terminal".
   - lv/ru/zh "dead key" became "silent key" (*klusais taustiņš*, *немая клавиша*, 前置组合键).
   - vi `*macron_below*` uses *dấu gạch dưới*, which means underscore.
   - nl `gesture_short_swipes_only_desc` says *vegen* (any swipe) where it means *veegtypen*;
     nl `gesture_selection_delete_mode_speed` "op afstand" means remote.
   - tr uses *kırpma* (cropping) for a clipboard clip.
9. **Omissions.**
   - de `dict_word_too_long_for_swipe_msg` drops "Swiping will not offer it."
   - es `autocorrect_elongation_collapse_desc` drops "extra".
   - ko/fr/fa `clipboard_filter_size_hint` narrow or blur "not space reclaimed".
10. **Register.**
    - hu `layout_language_not_installed` uses an informal *importálod* (the locale is formal Ön).
    - ro `autocorrect_tuning_reset_body` and `cmd_hide_keyboard_desc` use tu.
    - es `command_palette_template_help` and `appearance_height_*unfolded_desc` use usted in
      a tú file.
11. **Cosmetic.**
    - Curly quotes became `\'…\'` (in pt tr vi `suggestion_prefer_when_swiping_raised`).
    - zh has half-width punctuation in the Ranking & Tuning strings.
    - ja mixes （） and ().
    - fa uses Latin digits in two cost strings.
    - pt uses pt-PT "em falta" in a pt-BR file.

## 5. Recommended repeatable workflow

1. **Every PR (automatic, about 2 s in `runPureTests`).**
   - `TranslationFormatParityTest` (new): coverage and element kind for every resource;
     argument parity for every `<string>` and plural `other` item; the literal-`%` /
     `formatted="false"` rule in every locale, including English; and quote/apostrophe
     escaping.
   - It also runs its rules against an inline fixture with one deliberate defect per rule, so
     a rule that stops detecting fails.
   - Fail-first on the real tree before the fixes: 44 quoting and 20 literal-% defects; after
     the fixes it passes.
   - It sits beside `TranslationCoverageDriftTest`, `TranslationGlossaryTest` and
     `TranslationLengthTest`.
2. **CI lint (already gated).** Keep `lintDebug` / `lint`. Optionally add `lint { error
   'StringFormatCount', 'UnusedQuantity' }`. StringFormatCount reported this audit's
   calibration defect only as a warning, so it never failed CI. That change is a maintainer
   decision, because it changes the gate.
3. **Pre-release (manual, about 5 min).**
   - Run pofilter: `android2po` + `pofilter -t printf -t variables -t xmltags -t doublespacing
     -t untranslated -t nplurals` (commands in §1). Read only new hits relative to the last
     run; printf on literal `%` in `formatted="false"` strings is a known false positive.
   - Run the corpus-wide terminology probe for the glossary concepts, and add new splits to
     `docs/i18n/glossary.json`.
4. **Pre-release (device).**
   - Install a debug build and switch to en-XA and ar-XB pseudolocales. Screenshot Settings,
     the clipboard pane, the command palette and the suggestion bar, and look for truncation
     and mirroring.
   - Check the "strict" length list in §3.3 in hu, fr, ru and es.
5. **For each batch of new strings.**
   - Run a blind back-translation by a model (prompt as in
     `docs/i18n/2026-09-29-back-translation-review.md`), give the reviewer every quantity
     item, and record the results in item 10.
   - Then do the native-speaker pass.
6. **Optional: LanguageTool.**
   - A local server (about 250 MB download) adds grammar checks for ru/uk/pl plural
     agreement, which the model review only partly caught.
   - The public API is a third-party upload of every string, so it is a maintainer decision.
