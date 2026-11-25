# CleverKeys roadmap

Updated: 2026-11-25

## Current release line

v2.0.0 is the next release: code-complete, versionCode 20000 (`build.gradle`, version 2/0/0),
not tagged. The planned 1.6.0 was re-versioned to 2.0.0 on 2026-09-09/10 and never
released; the last tag is v1.5.0. The tag waits on the maintainer's soak sign-off, the string-review
decision and the tag sequence. CTC is the default swipe engine for the languages listed in
[`docs/specs/ctc-swipe-engine.md`](specs/ctc-swipe-engine.md) (per-language enablement and evidence
tier); the geometric engine covers other languages and layouts.

The current task list is the "Open now" section of [`memory/todo.md`](../memory/todo.md). Release
readiness, item by item: [`docs/audit/2026-10-31-v2.0-release-readiness.md`](audit/2026-10-31-v2.0-release-readiness.md);
the latest pre-tag review: [`docs/audit/2026-11-25-pre-tag-review.md`](audit/2026-11-25-pre-tag-review.md).

## Next work

- Validate provisional CTC languages and the expanded special-letter projection with real traces.
- 2.1 (not started): a toolbar row above the keys, a card-grid clipboard and a pack library
  (choose the dictionary / voice pack per language); see `memory/todo.md` "Open now".
- Continue accessibility and backup restore coverage on supported Android API levels.
- Full-keyboard trackpad mode: resolve exit-gesture and cursor-placement UX first.
- Greek language support: resolve word-list licensing and distribution before bundling.

## Historical roadmap

The former v1.2 roadmap described work completed or superseded before v1.5. Git history remains
the authoritative archive; completed design records live under `docs/history/`, `docs/plans/`,
and `docs/specs/`.
