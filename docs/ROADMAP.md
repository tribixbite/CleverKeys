# CleverKeys roadmap

Updated: 2026-08-23

## Current release line

Version 1.6.0 is the active release candidate. CTC is the default swipe engine for
English, French, German, Spanish, Italian, Portuguese, and Swedish; the last three are
provisional pending language-specific corpora. The geometric engine covers other languages
and layouts.

The current release-verification queue is [`memory/todo.md`](../memory/todo.md). The detailed
post-1.5 release audit is [`docs/audit/2026-08-23-v1.5-delta-audit.md`](audit/2026-08-23-v1.5-delta-audit.md).

## Next work

- Validate provisional CTC languages and the expanded special-letter projection with real traces.
- Collect enough independent default-CTC exposure for a context-rescoring decision.
- Continue accessibility and backup restore coverage on supported Android API levels.
- Full-keyboard trackpad mode: resolve exit-gesture and cursor-placement UX first.
- Greek language support: resolve word-list licensing and distribution before bundling.

## Historical roadmap

The former v1.2 roadmap described work completed or superseded before v1.5. Git history remains
the authoritative archive; completed design records live under `docs/history/`, `docs/plans/`,
and `docs/specs/`.
