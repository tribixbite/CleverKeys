# Persian half-space (ZWNJ) for the `fa` pack (2026-10-21)

Research for todo item 2 (#186/#61 Persian half). Nothing is built yet.

## How Persian uses it

Persian joins a word's parts with U+200C ZERO WIDTH NON-JOINER ("half-space", نیم‌فاصله):
the verb prefix `می‌` (`می‌شود`), plurals `‌ها` / `‌های` (`کتاب‌های`), comparatives `‌تر` /
`‌ترین` (`مهم‌ترین`), and `‌ای` after a final ه (`حرفه‌ای`). Many writers, especially in
informal text, omit it (`میشود`, `میکنم`) or use a full space.

## What other keyboards do

- **Desktop standard (ISIRI 9147):** ZWNJ on Shift+Space (Windows also Ctrl+Shift+2).
- **Phone keyboards:** a dedicated half-space key, usually beside the space bar, or a
  long-press on space. HeliBoard, from AOSP LatinIME, adds a `zwnj` key automatically to the
  right of the space bar for the languages that need it (its `layouts.md`).
- **AOSP LatinIME config:** `values-fa` only changes the suggested punctuation; the half-space is
  not declared a word connector there, so how a word is split is left to each keyboard's code.

## The data (`wordfreq` `fa`, 31,317 words)

- 2,394 words contain a half-space. For 742 of them the joined spelling is also a listed word,
  often the more frequent one in chat text (`میکنم` 5.56 vs `می‌کنم` 5.13 Zipf; `اینکه` 5.92 vs
  `این‌که` 4.52).
- 2,024 words use the Arabic letters ي / ك (`كه`, `يك`) as duplicates of the Persian ی / ک
  spellings (`كتاب` 4.27 vs `کتاب` 5.88). These must be folded to ی / ک and their frequencies
  merged.
- One bare-ZWNJ entry must be dropped.

## Words that differ only by the half-space

Yes, and they are systematic. A stem ending in ه plus the suffix `‌ای` ("a ..." / adjective)
has exactly the same letters as the stem without ه plus the plural `‌های`; only the half-space
position differs. Typed without a half-space, the string is ambiguous:

| ه + `‌ای` | meaning | stem + `‌های` | meaning |
|---|---|---|---|
| ساله‌ای | an …-year-old | سال‌های | years (of) |
| حرفه‌ای | professional | حرف‌های | words (of) |
| نامه‌ای | a letter | نام‌های | names (of) |
| زمینه‌ای | contextual / a field | زمین‌های | lands (of) |
| دسته‌ای | a bunch / group | دست‌های | hands (of) |
| نقشه‌ای | a map | نقش‌های | roles (of) |

Those six are the ones in the top 31k list; the pattern applies to any such stem pair. There
are also half-space vs full-space contrasts (a compound vs two words), e.g. `به‌روز`
(up to date) vs `به روز` (to the day).

## CleverKeys today

- A `zwnj` / "halfspace" key exists (extra key and command), but `arab_pc_ir.xml` ("Persian
  PC") does not place it.
- Word scanning uses `isLetterOrDigit` (`Keyboard2View.isWordChar`, `EditorTextNavigation`,
  the prediction context tracker), so U+200C is currently a word boundary: `می‌شود` reads as
  `می` + `شود`.

## Recommendation (for the maintainer to confirm)

1. Put the half-space key beside the space bar on the Persian layout, as HeliBoard does.
2. Treat U+200C as part of a word for `fa`/Arabic-script layouts in word tracking, the bar and
   autocorrect, so compounds are single dictionary words.
3. Pack: keep half-space compounds as words; fold ي/ك → ی/ک and merge; drop the bare ZWNJ.
4. Do NOT autocorrect a joined spelling to the half-space form: the joined forms are real,
   frequent words in their own right (our rule: never rewrite a listed word). Instead offer the
   half-space form in the suggestion bar, and for the six-pair class offer both readings.
5. Bundle the decision on (2) with tests on the boundary code; it touches shared word-scanning
   paths used by every language.
