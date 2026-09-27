# Current work queue

Updated: 2026-09-27. Full execution state and test evidence: [HANDOFF.md](HANDOFF.md).
Campaign plan: [`docs/plans/2026-08-30-full-backlog-campaign.md`](../docs/plans/2026-08-30-full-backlog-campaign.md).

The September 1 campaign baseline was `5fb58037`; subsequent work through `79f0b464`
was pushed with maintainer authorization on September 27. Preserve shared-tree work.

## September 27 follow-through

- [x] Prior 54 commits through `79f0b464` pushed; commit-specific CI, site deployment,
  APK build and UI/performance workflows succeeded.
- [x] Existing `langpacks` release updated: Norvig asset removed; 22 replacements and
  description published; all 22 downloaded SHA-256 hashes verified.
- [x] Local Astro build: 84 pages pass under Bun using the current `bin/astro.mjs` entry.
  Legacy wiki HTML URLs are deployment-generated redirects, not stale published bodies.
- [x] Site TypeScript check and `build:termux` pass; Android Rollup is optional and the
  lockfile is synchronized. No dependency versions changed.
- [x] Builder now rejects unsupported `--lang` before any reads/writes; mypy passes after
  correcting fractional-count annotations/callback typing. CKLM encoding matches the previous
  implementation across all six corpus-selection weights on a controlled fixture.
- [x] Final local gates: Kotlin compilation, Android lint, 2,610 pure tests and 859 mock
  tests pass (guarded run 7m51s). Site build: 84 pages, 104 affected-page links resolve;
  TypeScript and Python mypy pass. Saga connected read-only; no app/settings changes.
- [x] Translation structural audit: 936/936 resources in all 21 locales; indexed arguments
  and plural items match. Expanded translation guard passes 6/6 focused tests.
- [x] Authorized multilingual design review completed with PAL `gemini-3.8-flash` only;
  accepted requirements and unmeasured reviewer claims recorded in the context-model spec.
- [ ] TODO: multilingual LM pilot/evaluation and pack-attribution UI. Spanish generation
  remains gated on corpus licences/pins, shipped-vocabulary extraction and measured evaluation.
- [ ] TODO: run isolated manual `tester-release` APK workflow on
  `testing/non-dev-apk`; production signing, artifact-only upload, no tag/release/version change.
  Live F-Droid metadata (2026-09-27) watches `/releases/latest`, still v1.5.0.
  Actionlint 1.7.7, YAML/shell syntax and semantic comparison of unchanged push/debug
  behavior pass. Existing main workflows and the maintainer's checklist are untouched.
  First run `36357331914` failed before compilation because setup-android defaults to
  removed SDK package `tools`; tester job now requests `platform-tools` explicitly.
- [ ] TODO: before an official release, correct the same pre-existing setup-android
  default in `release.yml`; no release workflow was changed or dispatched here.
- [ ] TODO: reconcile the disable-learning delete prompt (`privacy_forget_learned_body`)
  with swipe-correction deletion; the separate Forget dialog already names it.
- [ ] TODO: native-speaker translation review and device visual verification remain distinct
  from automated structural checks; preserve the maintainer's manual-checklist edits.

## Maintainer/release gates

- [ ] ARC-053: soak the minified release APK; ARC-062/096 implementation is already present.
- [ ] ARC-054: decide whether v1.6 release notes announce ru and synthesis-holdout-only el.
- [ ] ARC-063: narrow blanket R8 keeps only after the first minified soak.
- [ ] Decide any nonzero `finger_occlusion_offset` default only from device-trace A/B evidence.

## Agent-executable backlog

- [ ] ARC-067: translate the common 384 missing resources into all 21 locale files. Preserve
  placeholders and plurals shapes; do not use English copies. ARC-066/087 are complete.
- [x] Finish Wave E: ARC-073 citation/doc drift (`d20ed3b5`), ARC-098 phantom-`keyboard2`
  tooling sweep (`f482faf4`), the four verified doc-claim repairs, and the
  `contraction_pairings_cleaned.json` gate run (the file was already deleted in `030265ee`).
  ARC-076 and ARC-089 are complete. ARC-098's source-tree half (`gesture/`,
  Bridges/Initializers→`wiring/`) remains under ARC-072 slice 3 below.
- [ ] ARC-072 slice 3 composition-root/reorg work, folded with the gesture portion of ARC-098.
- [ ] ARC-027/028/029 geometric experiments, evidence-gated on non-regressing corpus replay.
- [ ] ARC-071 migration is superseded by the installed Astro 7 site; reconcile the remaining
  ARC-046 web regression gate/Tailwind vendoring evidence.
- [ ] ML-side ARC-060/061 and the documented verb-inversion feasibility work. (ARC-056
  uk/bg/mk/he lexicons/langpacks CLOSED 2026-09-01 — `538a1633`/`86156ea3`.)
- [ ] ARC-044 remaining assertion-strengthening batch (no Truth dependency in androidTest).

## Verification backlog

- [x] Final guarded host gates on implementation commit `5fb58037`: `runPureTests` 2,087 and
  `runMockTests` 343, both passing on 2026-09-01.
- [ ] Wave J: full ew-cli instrumented run, including ARC-058/064/074/077/091/092/095.
- [ ] Wave K: both authorized phones per the campaign protocol; restore IME/properties and
  never framework-restart Saga. Capture ARC-068/069/070 evidence.
- [ ] Wave L remainder: update the ARC ledger and maintainer-input report. HANDOFF, backlog, and
  campaign-plan state were consolidated on 2026-09-01.

## Release authority

Do not commit, tag, push, publish, or open external issues without explicit user authorization.
