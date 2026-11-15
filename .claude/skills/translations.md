# Translations Skill

Use this skill when adding or editing string resources in any of the 21 locales, or reviewing
translations: the mechanical rules `TranslationFormatParityTest` pins, aapt2 quoting traps, and
where the audit, review sheets and glossary live.

<!-- moved verbatim from CLAUDE.md on 2026-11-14 -->
### String resources and translations (2026-09-30, extended 2026-10-22)
- A translation containing a literal `%` (e.g. "as % of the key width") must carry `formatted="false"` in EVERY locale — the attribute is not inherited from the default file: CI `lintDebug` fails `StringFormatInvalid` when the following characters read as a conversion (vi "% chiều"). A string WITH arguments writes a literal percent as `%%`.
- aapt2 silently REMOVES an unescaped `"` inside a value (`"%1$s" wurde hinzugefügt` rendered without its quotes in 16 locales, `0a8fc939`): write `\"` or typographic quotes; an unescaped `'` outside a quoted region is a compile error.
- `TranslationFormatParityTest` (pure, `runPureTests`, 2026-10-22) now pins all four mechanical rules for every resource in the 21 locales — coverage and element kind, format-argument multiset (plural `other` items too), the literal-`%` rule and quote escaping — and runs each rule on an inline fixture with one deliberate defect, so a dead rule fails loudly. `lintDebug` (≈45 min on Termux) is still the only check for MissingTranslation wording-class lint; CI runs it.
- Tool matrix, commands and the native-review leads: `docs/audit/2026-10-22-translation-audit.md`; review sheets `scripts/export_translation_review.py`; glossary `docs/i18n/glossary.json` (`TranslationGlossaryTest`).
