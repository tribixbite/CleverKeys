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
| LanguageTool | Grammar, agreement and punctuation rules for about 30 languages (ru uk pl de fr es pt nl it fa ja zh ro…) | Yes in principle: Java 21 is installed. Needs a separate download (about 250 MB) and about 1.5 GB of RAM for the local server. | `java -cp languagetool-server.jar org.languagetool.server.HTTPServer --port 8081`, then POST each string to `localhost:8081/v2/check` | Yes, 2026-10-25: the public API, approved by the maintainer; 14 locales, see §7 |
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
impact. **Items 1–5 were fixed by agents on 2026-10-25 (maintainer decision: 2.0 ships with
machine review; see §6). Items 6–11 still need the native speaker.**

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
6. **LanguageTool** (done once on 2026-10-25 through the public API; §7 has the recipe and
   the triage categories).
   - Rerun it for each batch of new strings. The public API's limits (20 requests and 75 KB
     per minute, 20 KB per request, 20 s of processing per check) make a full run take about
     40 minutes; its terms also say "do not send automated requests", so a local server
     (about 250 MB download, 1.5 GB RAM) is the right tool for anything more frequent.
   - Read only the non-speller rules in full; for the speller rules, read the distinct-token
     list (§7). Most of the signal is in agreement, punctuation and doubled-word rules.

## 6. Native-review leads fixed (2026-10-25)

The maintainer decided on 2026-10-25 that agents fix leads 1–5 of §4 and that 2.0 ships with
machine review. Every fix keeps the placeholders and `formatted="false"` attributes;
`TranslationFormatParityTest`, `TranslationGlossaryTest`, `TranslationCoverageDriftTest` and
`TranslationLengthTest` are the gate (run after the fixes, see §7).

| # | Lead (§4) | Fix | Commits |
|---|---|---|---|
| 1 | `cmd_clear_clipboard_desc` named pins and todos differently from the clipboard tabs | Each locale reuses its own `clipboard_tab_pinned` / `clipboard_tab_todos` wording (18 locales changed; cs and uk already matched; vi additionally *nhật ký* → *lịch sử* for history, as its tab says). The clipboard term itself (fa کلیپ‌بورد vs بریده‌دان, vi *khay nhớ tạm* vs *bộ nhớ tạm*) is the glossary split of §3.4 and was left alone. | `1ac944a9` `2e49403d` `367a748a` `80a3ddcd` |
| 2 | es/hu `clipboard_selection_clear` read as *delete the selection* | *Quitar selección* / *Kijelölés megszüntetése* (hu reuses the verb of its own `clipboard_deselect_all_matching`). | `d11baf11` |
| 3 | fa arrows reversed or misleading in RTL | See the rules below; 7 strings changed, 1 reviewed and kept. | `12a425b2` |
| 4 | ru plural grammar after a count | The flagged `#other` items are correct: in ru/pl/uk `other` is the fractional category, CLDR's own sample is the genitive singular (*1,5 дня*), and an integer `%d` never selects it. The real defects were counts that are not the selecting argument: `dict_word_too_long_for_swipe_msg` chooses its form from the constant `%3$d` (`EMISSION_FRAMES = 32`, always `few`), so `%2$d шага/kroków/кроків` disagreed for most values in ru/pl/uk; `backup_headless_dict_imported` chooses on `%2$d`, so ru/uk `%1$d пользовательских/власних` was wrong for 1. Both counts are now in case-neutral constructions (*требуется шагов: %2$d*; *пользовательских — %1$d, отключённых — %2$d*). cs is unaffected (one form for every integer above 4). pl `#other` items checked the same way: all genitive singular, correct. | `e16549d2` |
| 5 | tr/pl rendered Backspace as *undo* | *geri silme tuşuna bir kez basmak* / *jedno naciśnięcie Backspace*, the locales' own Backspace key names; tr `input_swipe_distance_threshold_desc` had the same slip (*geri al gibi geniş tuşlarda*). | `4c1207ff` |

**fa arrow rules.** Arrows are not `Bidi_Mirrored`, so what a reader sees depends on the
resolved direction of the run the arrow lands in, not on which arrow was typed. The test is
"the tail sits at the source": that reading is the same for an LTR and an RTL reader.

- Latin→Latin examples (`teh → the`) form one LTR run (UBA rule N1: a neutral between two
  L characters is L), so the arrow keeps its English direction. `donr ← don't` and `tpo ← to`
  had been flipped and rendered target-to-source; both are `→` again.
- Persian→Persian sequences form one RTL run, so the arrow must point in the reading
  direction: `←`, as the Settings paths (`تنظیمات ← چندزبانه`) already did.
  `cmd_case_cycle_desc` had `→` and rendered UPPER→Title→lower; now `کوچک ← عنوانی ← بزرگ`.
- String placeholders of unknown script (learned phrase pairs, collision examples, backup
  value and theme diffs) used an LRI island with `→`: right for Latin values, reversed for
  Persian ones, because both operands and the arrow collapse into one RTL run inside the
  island. They now use an RLI island with each operand in its own FSI isolate,
  `⁧⁨%1$s⁩ ← ⁨%2$s⁩⁩`: isolates count as neutrals for the arrow, so it follows the
  island's direction whatever the operands or the surrounding paragraph are.
  `short_swipe_toast_mapped` keeps `%1$s ← «%2$s»`: its `%1$s` is always a Persian direction
  name, which pins both the paragraph and the arrow's run RTL.
- Numeric placeholders (`⁦%1$d → %2$d⁩`) and `↔` are unchanged: digits are LTR inside their
  island, and `↔` is symmetric.

Verified with `java.text.Bidi` (the JDK's UBA implementation, isolates included) for Latin and
Persian operands in LTR and RTL paragraphs; **not verified on a device** (this agent had no
phone). A device check of the Learning Data phrase list and the Backup preview in fa is the
remaining step.

## 7. LanguageTool pass (2026-10-25)

The maintainer approved the public API (`https://api.languagetool.org/v2/check`; the strings
are public in the repository anyway). One full pass was run on 2026-10-25 by an agent.

**Method.** A throwaway script in the session scratchpad (`lt_check.py`, not committed) parses
each `res/values-<locale>/strings.xml`, takes every translatable `<string>` and every
`<plurals>` item, resolves the Android escapes (`\'`, `\"`, `\n`, the whole-value `"…"`
wrapper), joins the texts with a blank line into batches of at most 12 KB, POSTs each batch
with `language=<code>` and `enabledOnly=false`, maps every match back to its key and quantity
by UTF-16 offset (LanguageTool is Java), and drops matches that touch a format placeholder.
Responses are cached by content hash, so a rerun re-sends only changed text. Rate limit:
one request every 17 s and at most 12 KB per request, against the published limits of 20
requests, 75 KB per minute and 20 KB per request. The server also stops a check after 20 s of
processing (HTTP 500 "took longer than 20.0 seconds"): French needed 12 KB batches and Dutch
had to be bisected down to 3 KB. The terms also say "do not send automated requests"; a one-off
cached pass at a third of the limits is the most conservative reading of the approval, and
anything recurring should use the local server (§1).

**Locales.** LanguageTool supports 14 of the 21: de (de-DE), es, fa, fil (tl-PH), fr, it,
ja (ja-JP), nl, pl (pl-PL), pt (pt-BR), ro (ro-RO), ru (ru-RU), uk (uk-UA), zh-rCN (zh-CN).
Not supported and therefore not covered: **cs, hu, in, ko, lv, tr, vi.**

**Triage rule.** Fixed only clear errors: real typos, agreement errors, doubled words and
punctuation spacing that the locale's own strings already follow. Ignored: style and
register suggestions, brand and product names, diacritic and vowel-point names (Macron,
Ogonek, Qamats…), format patterns (`yyyy-MM-dd HH:mm`), English typo examples (`teh → the`),
project terms (sottotasto, subtecla, saisie par glissé), UI fragments that are not sentences
(`vor %1$d Min.`, `Re Pág`, `Diz. utente`), the deliberate double spaces in
`learning_data_phrase_row` and `extra_key_description_with_shortcut`, punctuation-character
lists in `input_smart_punctuation_desc`, the comma-separated sample-word lists in
`theme_preview_sample_words`, and false positives. Nothing was reworded.

**Matches per locale** (after placeholder filtering; "speller" is the locale's
`MORFOLOGIK_RULE_*` / `*_SPELLER_RULE`, read as a distinct-token list; "other rules" were read
match by match):

| locale | LT code | entries | matches | speller | other rules | fixed (corrections) | commit |
|---|---|---|---|---|---|---|---|
| de | de-DE | 2323 | 400 | 338 | 62 | 2 | `bd00844a` |
| es | es | 2400 | 424 | 384 | 40 | 5 | `bd00844a` |
| fa | fa | 2323 | 13 | 0 | 13 | 1 | `bd00844a` |
| fil | tl-PH | 2323 | 4970 | 4912 | 58 | 0 | – |
| fr | fr | 2400 | 446 | 301 | 145 | 13 | `f03cee30` |
| it | it | 2400 | 540 | 516 | 24 | 0 | – |
| ja | ja-JP | 2246 | 1 | 0 | 1 | 0 | – |
| nl | nl | 2323 | 682 | 640 | 42 | 10 | `dfc27734` |
| pl | pl-PL | 2477 | 410 | 316 | 94 | 10 | `c3a21249` |
| pt | pt-BR | 2400 | 460 | 378 | 82 | 5 | `48a49119` |
| ro | ro-RO | 2400 | 488 | 455 | 33 | 13 | `349e2b23` |
| ru | ru-RU | 2477 | 341 | 300 | 41 | 3 | `ada395c7` |
| uk | uk-UA | 2477 | 369 | 317 | 52 | 12 | `4c0a812f` |
| zh-rCN | zh-CN | 2246 | 19 | 0 | 19 | 0 | – |
| **total** | 14 | 33 215 | **9 563** | **8 857** | **706** | **74** | |

The 8 857 speller hits are, with 15 exceptions, brand and product names (CleverKeys, ClearURLs,
TalkBack, Material You), diacritic and vowel-point names (Makron, Ogonek, Qamats, Hataf…),
format patterns and Java identifiers, English typo examples, key abbreviations, project terms
(subtecla, podklawisz, свайп) and, in fil, English words in the Taglish copy. The 706 other-rule
hits split into about 60 % false positives on UI fragments and lists, 25 % style and register
suggestions, and the 15 % that were fixed or recorded below.

**Fixed (74 corrections in 72 string/plural items, 65 resources).**

- *Typos (7)*: de `Wischtatwort` → gewischten Wort; nl `reaciekiezer` → reactiekiezer,
  `tekenttoetsen` → tekentoetsen; pl `przesuniecia` → przesunięcia, `klawisom` → klawiszom;
  ro `Crează` → Creează; uk `паттерн` → патерн (3 strings, 2019 orthography).
- *Agreement and grammar (8)*: fr `légères dépassements` → légers; uk `плаваючу кнопку` →
  плавучу (2), `зменшіть/збільшіть` → зменште/збільште, `неточних початку/кінця` →
  неточного, `неправильні першу/останню літери` → неправильну … літеру; fa doubled word
  `کامل کامل شد` → `کامل انجام شد`.
- *Punctuation the locale's own norm requires (31)*: comma before *pero* (es 4), *mais/car*
  (fr 10 items), *mas* (pt 3), *zanim* and *tyle, co* (pl 5); no comma before *etc.* (ro 3);
  the one fr semicolon without the space the file's other 47 carry; de sentence after a colon
  capitalised; ro's nine straight `\"…\"` pairs → „…” like the file's other 35; pl „I“ → „I”.
- *Compound spelling (12)*: nl `Maximum aantal` → Maximaal aantal (3), `Privé kopie(ën)` →
  Privékopie(ën) (2), `12-uurs tijd` → 12-uurstijd, `rozethema` → roze thema; pl `nie żądanego`
  → nieżądanego; pt `Verde esmeralda` → Verde-esmeralda, `verde floresta` → verde-floresta;
  ru/uk `Авто-пробел/Авто-пробіл` → Автопробел/Автопробіл (4).
- *Terminology inside one file (5)*: es `las URLs` → las URL (acronym plurals, RAE); fr
  `Touche modificateur` → Touche de modification; nl quoted menu label → `Privé kopiëren` (the
  real `private_copy_toolbar_label`); ru/uk `суб-клавиш/суб-клавіш` → подклавиш/підклавіш;
  uk `(у одиницях` → `(в одиницях`.

Every edited key was re-sent to LanguageTool afterwards (the script's `--recheck` mode): no new
non-speller match beyond the pre-existing false positives. `TranslationFormatParityTest`,
`TranslationGlossaryTest`, `TranslationCoverageDriftTest` and `TranslationLengthTest` were run
after the last edit (`4c0a812f`) through `scripts/gradle-guard.sh runPureTests`: OK (6), OK (5),
OK (6), OK (2), 2026-10-26 00:19–00:20. A Python mirror of the parity rules also reports 0
findings on the final tree.

**Ignored, by category** (counts are approximate, from the rule ids):

- Speller hits on names, format patterns and project terms: 8 842 (fil alone 4 912).
- Style, register and readability rules: de Auslassungspunkte/Mathe/Compound-Coherency, fr
  PLACE_ADJ (16) and repetition rules, it anglicism rule (input/output), pt barbarism rules
  (app, status, versus), uk recommended-synonym rules (Переключити, Двонапрямлена, Заготовки),
  ro cacophony, ja 「ことができる」, nl ERG_LANG_WOORD: about 180.
- UI fragments and lists read as sentences: lowercase after an abbreviation (`Max. mediagrootte`,
  `Dicc. usuario`), relative-time labels, the `. , ! ?` list in `input_smart_punctuation_desc`,
  the sample-word lists, separators (`, `), `H h` format letters, `{{ and }}`: about 150.
- Deliberate double spaces in `learning_data_phrase_row`, `learning_data_word_row`,
  `multilang_collision_example`, `extra_key_description_with_shortcut`: 5 per locale.
- False positives on correct text: de *Kombinierender Hatschek* (masculine), *Professionelles
  Anthrazit* (neuter colour noun); es *Activa predicciones* (verb), *Copia … guardada*; it *dello
  ZIP*, *ai fissati*; nl *accent grave*; pl brand casing (37), *we/wy*; ru/uk indeclinable Hebrew
  and Arabic names (патах, шин, седили), the `Экспортовано настроек: N` colon counts; fil
  ligature rules on *sa*-phrases and *bawat/buong*, English *raw*, Material *You*; zh 的 in
  attributive position, 补回, 移到: about 200.
- Optional punctuation: nl commas before *maar/omdat*; pt *etc.* without a comma, *nº*; uk
  euphonic й/та and в/у alternation (one fixed where the string was already open).

**Uncertain, left for a native speaker** (new leads beyond §4):

- de: hyphenation is inconsistent within concepts (*Zwischenablageeinträgen* vs
  *Zwischenablage-Eintrag*, *Wort-Tripel* vs *Worttripel*, *System-Zwischenablage* vs
  *Systemzwischenablage*).
- es: `calibration_tap_swipe_title` *Umbral toque / deslizamiento* reads as a missing *de*;
  *recencia*; key labels without abbreviation points (*Mín*, *Re Pág*).
- fr: *saisie par glissé* (the glossary noun; a grammar checker reads it as a participle);
  cardinal directions in capitals mirror the English emphasis; *1re/2e* vs superscript ordinals.
- it: *recentezza* (recency) is a coinage.
- nl: `theme_color_key_modifier` *Modifier-toets* beside *modificatietoets* elsewhere; *een even
  dichtbij gewoon woord*.
- pl: *zanim gest może być zakwalifikowany* (imperfective after *zanim*); the comma in
  `dict_legacy_freq_dialog_message` *zmienia tylko poniższe słowa, na %2$d* copied from English.
- pt: *da pipeline* (gender varies in usage); *Roxo joia*, *Vermelho rubi*, *Azul elétrico*
  unhyphenated while the file hyphenates its other compound colours.
- ro: *Majusculizează* (a coinage for *capitalize*); *Sesiune de test al glisării pornită*.
- ru: *Оценка недавности* (recency); Latin *a* naming the fatha vowel in two strings.
- uk: *Виключити менеджери паролів* (accusative of an inanimate; acceptable); *Заготовки* for
  presets.
- fa: *بلادرنگ* vs *بی‌درنگ* (both current; the checker prefers the Persian form).
- fil: the Tagalog ligature (*-ng/na*) and *d/r* alternation rules fire throughout the Taglish
  copy; a Filipino reader should decide whether the Taglish policy wants them.

**Repeat recipe.** `lt_check.py` (session scratchpad; about 250 lines of typed Python, no
dependencies beyond the standard library): `python3 lt_check.py [locale…]` for a full pass,
`python3 lt_check.py --recheck <locale> key1,key2` after edits; `triage.py <locale>` writes a
digest, `--words RULE` lists a speller rule's distinct tokens, `--skip RULE,…` prints everything
else with context; `report.py` prints the table above from `summary.json`. Keep MAX_BYTES at
12 KB and MIN_INTERVAL at 17 s, or point LT_URL at a local server and drop both.
