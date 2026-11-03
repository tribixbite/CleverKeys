# Native-speaker review sheets (2026-11)

One CSV per locale (`de.csv`, `fa.csv`, `zh-rCN.csv`, …) with every string the app gained or
changed since 2026-09-29. Every translation in CleverKeys was written by a model. These sheets
are for the native-speaker review in `memory/todo.md` item 10.

## What is in a sheet

- **Scope.** Every `<string>` and `<plurals>` whose English text in `res/values/strings.xml` was
  added or changed after commit `8aba4a8fbb` (the last commit before 2026-09-29): 1,523
  resources. Each sheet also has the older strings whose translation in that language changed
  after that commit (agent fixes, the LanguageTool pass): 0 to 40 per locale. Plurals get one row
  per quantity that the language uses, so ru/uk/pl/cs have `few`/`many` rows and
  ja/ko/zh/vi/in have `other` only. When English has no such form, the English column shows
  English `other`.
- **Header lines.** These start with `#`: the locale, the scope, row counts per feature, and the
  result of the machine sanity pass with every flagged row.
- **Rows** are grouped by feature in this order: Persian and right-to-left text, voice input,
  licences screen, autocorrect (Unfinished Words, Ranking & Tuning), familiar looks and Theme
  Creator, layout geometry, short swipes and calibration, subkeys, clipboard, command palette,
  extra keys, layouts, backup/restore, dictionary and language packs, GIF panel, swipe typing,
  suggestion bar, other settings, and keyboard/tile/system.

| column | meaning |
|---|---|
| `key` | resource name; a plural item is `name#quantity` |
| `feature` | the feature group |
| `english` | the English source |
| `translation` | the current text in this language |
| `context` | where the text appears: the screen, then `File.kt:line function()` from the code that uses it. Commands name their palette category. |
| `notes` | placeholders to keep, the length limit for one-line titles, palette rows and suggestion chips (it mirrors `TranslationLengthTest`), line breaks, the plural quantity, and open questions from `docs/i18n/review-flags.json` |
| `machine_flags` | the automatic findings (see below) |
| `reviewer_correction` | **you write here**: the full corrected text, only for rows that need a change |
| `reviewer_comment` | **optional**: a question or remark. It is not imported. |

## Machine sanity pass (no external service)

The script runs four checks and lists every flagged row in the header:

- **identical to English.** The text is not translated, or it is a name or brand that rightly
  stays English (theme names, "ZWNJ", "Intent"). Decide which.
- **placeholders differ / literal % / line breaks differ.** These are format defects.
  `TranslationFormatParityTest` already blocks them, so the count is 0 in every sheet.
- **too long.** The text is past the `TranslationLengthTest` limit. The count is 0.
- **glossary.** The English names a product concept (swipe, clipboard, layout, dictionary,
  suggestion, learn, gesture, …), but the translation has none of the glossary's terms for it,
  or it uses a term the glossary rejects (`docs/i18n/glossary.json`). These are leads, not
  defects. "Swipe" can mean the swipe-to-delete gesture, which takes a different verb in some
  languages.

Flagged rows per locale (regenerated 2026-11-03): cs 59, de 76, es 69, fa 35, fil 186, fr 90,
hu 77, in 96, it 75, ja 31, ko 21, lv 70, nl 92, pl 71, pt 88, ro 102, ru 40, tr 65, uk 41, vi 82,
zh-rCN 29. That is 1,495 flagged out of 34,121 rows (ru/uk dropped by 4 each after `8335bd02`
put the glossary's swipe term into `dict_word_too_long_for_swipe_msg`). fil is high because it deliberately uses Taglish
(`docs/i18n/2026-10-08-coverage-audit.md`).

## LanguageTool findings (2026-11-03)

The exporter has no input for external findings, so they are listed here instead of in the sheet
headers. Scope: every string and plural item whose translation was added or changed after the
2026-10-25 LanguageTool pass (`69e318a4`) up to `8335bd02`, in the 14 locales LanguageTool
supports. Full counts and method: `docs/audit/2026-10-22-translation-audit.md` §8.

- **Fixed (already in the sheets' `translation` column):** es `voice_pack_error_invalid_id`
  (*un id* → *un ID*); pl `appearance_look_space_language_desc` (no comma before a verbless
  *tak jak* comparison); pt `layout_manager_export_include_theme`,
  `theme_export_xml_failed` (*ficheiro* → *arquivo*), `theme_section_look`,
  `theme_section_look_colors` (*aspeto* → *aspecto*), `theme_function_chars` (*Carateres* →
  *Caracteres*): European-Portuguese spellings in the pt-BR file.
- **Needs your judgement** (LanguageTool raised it; the agent did not change it):
  - de `theme_section_hints` *Gestenhinweise* while `appearance_look_show_hints_title` writes
    *Gesten-Hinweise*; pick one spelling.
  - nl `theme_bar_emphasis_centre` *Zijwoorden gedimd*: *zijwoorden* is a coinage for "side
    words" (the suggestions left and right of the centre one).
  - it: *input vocale* in 19 voice-input strings; LanguageTool suggests *ingresso/immissione
    vocale*. The file uses *input* elsewhere too, so this is a terminology decision.
  - pt: *depois de* → *após* (2) and *app* → *aplicativo* (1) are style suggestions; the
    Android pt-BR system UI itself says *Informações do app*.
- **False positives, nothing to do:** brand and product names (CleverKeys, Gboard, SwiftKey,
  iOS), the English examples *tomorr/timor/tomorrow* in the Unfinished Words strings, the
  `voice_input` command id, project terms (subtecla, sottotasto, podklawisz, подклавиша, свайп),
  key abbreviations (Verr. Maj, Bloq, Maiusc, Доп., Вимк.), lone quotation-mark characters in
  `bar_quote_open`/`bar_quote_close`, case-neutral count labels (ru *сочетаний: %1$d*), and in
  fil the English words of the Taglish copy.
- **Not checked by LanguageTool** (unsupported): cs, hu, in, ko, lv, tr, vi. These need the
  native review alone.

## How to review

1. Open your language's CSV in LibreOffice Calc, Google Sheets or Excel. The encoding is UTF-8
   and the file has a BOM, so Excel detects it.
2. Read the flagged rows in the header first, then each feature group. Use `context` to picture
   where the text appears.
3. For a wrong row, write the **complete** corrected text in `reviewer_correction`. Leave it
   empty when the translation is fine. Do not edit the other columns, and do not delete or
   reorder the header lines or the `key` column.
4. Keep every placeholder exactly as written (`%1$s`, `%2$d`, `%d`). You may move a placeholder
   within the sentence. Write `%%` for a percent sign, except in rows whose notes say
   `formatted="false"`; there you write a single `%`. Keep `\n` where the English has a line
   break. You may also press Enter inside the cell; the import turns that into `\n`.
5. Write apostrophes and quotation marks as normal characters. The import adds the Android
   escapes.
6. Save as CSV (UTF-8) under the same name and send the file back: attach it to the review
   issue, or give it to the maintainer.

## Re-import (maintainer or agent)

```bash
python3 scripts/export_translation_review.py --import docs/i18n/review/2026-11/de.csv --dry-run
python3 scripts/export_translation_review.py --import docs/i18n/review/2026-11/de.csv
```

- The locale comes from the sheet's `# locale:` line. `--locale de` overrides it. Several
  sheets can be passed at once.
- The import changes only the text of each corrected element in `res/values-<locale>/strings.xml`.
  The rest of the file stays byte for byte. Rows whose correction equals the current text are
  counted as unchanged.
- The import **refuses** a row, and leaves that string untouched, in these cases: its
  placeholders differ from the current translation's; it has a literal `%` in a formatted
  string; the resource has inline markup; the key is missing; or the text would not read back
  identically. Refused rows go to stderr, and the exit status is 1.
- After an import, run the translation gate through `scripts/gradle-guard.sh`:
  `runPureTests -PtestClass=TranslationFormatParityTest`, then `TranslationGlossaryTest`,
  `TranslationLengthTest` and `TranslationCoverageDriftTest`. A correction that uses a different
  glossary term fails `TranslationGlossaryTest`. Either the reviewer's term replaces the
  glossary entry (update `docs/i18n/glossary.json` with a note), or the correction is adjusted.
- Remove an answered question from `docs/i18n/review-flags.json`.

## Regenerate

```bash
python3 scripts/export_translation_review.py --scope-since 2026-09-29 --out docs/i18n/review/2026-11
python3 scripts/test_export_translation_review.py   # importer/exporter tests, pure Python
```

Regenerating overwrites the sheets, so collect returned sheets first. The full sheet with every
string (the older mode) is still `--out <dir> [--since <ref>]`.
