# Translation coverage audit (2026-10-08)

Machine work (model translator, model reviewer). No string here has been reviewed by a native
speaker; every locale remains machine-quality pending the maintainer's native-review pass.

## Coverage

A parse of `res/values/strings.xml` (2,096 translatable `<string>`/`<plurals>`, skipping
`translatable="false"`) against all 21 locale files found **0 missing and 0 extra names in every
locale**. The `memory/todo.md` ARC-067 line ("384 missing resources") predated the 2026-09-01
Wave D closure (`docs/audit/2026-08-28-archive-verification.md`) and was stale.

All 73 `<plurals>` exist in every locale; every item keeps the default's indexed arguments
(`one`/`zero` may drop the count), and cs/pl/ru/uk carry one/few/many/other, lv zero/one/other,
ro one/few/other.

## English-identical values

Values identical to English (longer than 12 characters): 2–12 per locale except fil (91). Outside
fil these are cognates or format-only strings (de "Layout %1$d: %2$s", fr "Configuration", ro
"Active [%1$s]" = feminine plural matching "Dezactivate", nl "%1$d item"), left as is. Changed:

- **in** `short_swipe_action_desc_key_event` → "Peristiwa tombol: %1$s" (the file's own terms).
- **fil**: 31 titles whose structural words the file translates elsewhere (e.g. "Taas ng
  Keyboard (Portrait)") → margins, long-press, smoothing, punctuation, palette, practice area,
  selection mode, etc. Five uncertain choices are flagged in `review-flags.json`.
- fil kept in English on purpose: screen names that other fil strings cite verbatim (Swipe
  Typing, Swipe Trail, Accessibility, Auto-Correction, Layout/Theme/Dictionary Manager — the
  glossary records these as intentional borrowings), Unicode/technical key names (ZWJ, combining
  diacritics, Bidi, Slavonic Psili), product names (CleverKeys, Termux, TrackPoint, GitHub, CTC)
  and established Taglish borrowings the file already uses (Preview, Command, Haptic Feedback).

## Bug fixed

- **fa** `advanced_custom_terminal_invalid` hard-coded "۲۵۵" and dropped `%2$d`
  (`TerminalUtils.MAX_PACKAGE_LENGTH`); restored the argument.
